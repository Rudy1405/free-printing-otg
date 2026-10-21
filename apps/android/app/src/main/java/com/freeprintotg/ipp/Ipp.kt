package com.freeprintotg.ipp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Minimal IPP/2.0 codec: just the operations a print client needs. RFC 8010/8011. */
object Ipp {
    const val OP_PRINT_JOB = 0x0002
    const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    private const val TAG_OPERATION = 0x01
    private const val TAG_JOB = 0x02
    private const val TAG_END = 0x03
    private const val TAG_PRINTER = 0x04
    private const val TAG_INTEGER = 0x21
    private const val TAG_ENUM = 0x23
    private const val TAG_KEYWORD = 0x44
    private const val TAG_URI = 0x45
    private const val TAG_CHARSET = 0x47
    private const val TAG_LANGUAGE = 0x48
    private const val TAG_MIME = 0x49
    private const val TAG_NAME = 0x42

    class Request(private val op: Int, private val requestId: Int = 1) {
        private val out = ByteArrayOutputStream()
        init {
            out.write(byteArrayOf(2, 0)) // IPP 2.0
            out.write(be16(op)); out.write(be32(requestId))
            out.write(TAG_OPERATION)
            attr(TAG_CHARSET, "attributes-charset", "utf-8")
            attr(TAG_LANGUAGE, "attributes-natural-language", "en")
        }
        fun uri(name: String, v: String) = apply { attr(TAG_URI, name, v) }
        fun mime(name: String, v: String) = apply { attr(TAG_MIME, name, v) }
        fun name(name: String, v: String) = apply { attr(TAG_NAME, name, v) }
        fun keywords(name: String, vararg v: String) = apply {
            v.forEachIndexed { i, s -> attr(TAG_KEYWORD, if (i == 0) name else "", s) }
        }
        fun jobGroup() = apply { out.write(TAG_JOB) }
        fun int(name: String, v: Int) = apply {
            out.write(TAG_INTEGER); str16(name); out.write(be16(4)); out.write(be32(v))
        }
        fun keyword(name: String, v: String) = apply { attr(TAG_KEYWORD, name, v) }
        fun build(document: ByteArray? = null): ByteArray {
            out.write(TAG_END)
            document?.let { out.write(it) }
            return out.toByteArray()
        }
        private fun attr(tag: Int, name: String, v: String) {
            out.write(tag); str16(name); str16(v)
        }
        private fun str16(s: String) { val b = s.toByteArray(); out.write(be16(b.size)); out.write(b) }
    }

    class Response(val status: Int, val attributes: Map<String, List<String>>) {
        val ok get() = status < 0x0100 // successful-ok .. successful-ok-conflicting-attributes
    }

    fun parse(data: ByteArray): Response {
        if (data.size < 8) return Response(0xFFFF, emptyMap())
        val status = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val attrs = linkedMapOf<String, MutableList<String>>()
        var p = 8
        var current = ""
        fun u16(i: Int) = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        while (p < data.size) {
            val tag = data[p].toInt() and 0xFF
            p++
            if (tag == TAG_END) break
            if (tag <= 0x05) continue // group delimiter
            if (p + 2 > data.size) break
            val nl = u16(p); p += 2
            val name = String(data, p, nl); p += nl
            val vl = u16(p); p += 2
            if (p + vl > data.size) break
            if (name.isNotEmpty()) current = name
            val value = when (tag) {
                TAG_INTEGER, TAG_ENUM ->
                    if (vl == 4) ((u16(p) shl 16) or u16(p + 2)).toString() else ""
                else -> String(data, p, vl)
            }
            attrs.getOrPut(current) { mutableListOf() }.add(value)
            p += vl
        }
        return Response(status, attrs)
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}

/** Carries IPP bytes to the printer: HTTP over TCP, or HTTP over a USB bulk pipe. */
interface IppChannel {
    fun post(body: ByteArray): ByteArray
}

class HttpIppChannel(private val url: String, private val tls: Boolean) : IppChannel {
    override fun post(body: ByteArray): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (tls && conn is HttpsURLConnection) {
            // Printers ship self-signed certificates; the host comes from local discovery, not the internet.
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            conn.sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode} from printer")
        return conn.inputStream.use(InputStream::readBytes)
    }
}

class IppClient(private val channel: IppChannel, private val printerUri: String) {

    fun supportedFormats(): Set<String> {
        val req = Ipp.Request(Ipp.OP_GET_PRINTER_ATTRIBUTES)
            .uri("printer-uri", printerUri)
            .keywords("requested-attributes", "document-format-supported", "printer-state", "printer-state-reasons")
            .build()
        val r = Ipp.parse(channel.post(req))
        return r.attributes["document-format-supported"].orEmpty().toSet()
    }

    fun printJob(document: ByteArray, mime: String, name: String, copies: Int, duplex: Boolean, color: Boolean) {
        val req = Ipp.Request(Ipp.OP_PRINT_JOB)
            .uri("printer-uri", printerUri)
            .name("requesting-user-name", "free-print-otg")
            .name("job-name", name)
            .mime("document-format", mime)
            .jobGroup()
            .int("copies", copies)
            .keyword("sides", if (duplex) "two-sided-long-edge" else "one-sided")
            .keyword("print-color-mode", if (color) "color" else "monochrome")
            .build(document)
        val r = Ipp.parse(channel.post(req))
        if (!r.ok) error("Printer rejected job: IPP status 0x%04x".format(r.status))
    }
}
