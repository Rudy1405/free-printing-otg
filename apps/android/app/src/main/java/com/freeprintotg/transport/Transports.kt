package com.freeprintotg.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.freeprintotg.*
import com.freeprintotg.ipp.IppChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/** Sends an already-encoded print stream (PDF, PWG raster, PCL...) to a printer. */
interface PrintSink : AutoCloseable {
    fun write(data: ByteArray, off: Int = 0, len: Int = data.size)
}

class RawSocketSink(host: String, port: Int) : PrintSink {
    private val socket = Socket().apply { connect(InetSocketAddress(host, port), 10_000) }
    private val out = socket.getOutputStream()
    override fun write(data: ByteArray, off: Int, len: Int) = out.write(data, off, len)
    override fun close() { out.flush(); socket.close() }
}

/** RFC 1179 line printer daemon, queue "lp". Used when a printer only advertises _printer._tcp. */
class LpdSink(host: String, port: Int, private val queue: String = "lp") : PrintSink {
    private val socket = Socket().apply { connect(InetSocketAddress(host, if (port > 0) port else 515), 10_000) }
    private val buf = ByteArrayOutputStream()
    override fun write(data: ByteArray, off: Int, len: Int) { buf.write(data, off, len) }
    override fun close() {
        val data = buf.toByteArray()
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()
        fun ack() { if (inp.read() != 0) error("LPD printer refused the request") }
        out.write("\u0002$queue\n".toByteArray()); out.flush(); ack()
        val control = "Hfreeprint\nPandroid\nldfA001freeprint\nUdfA001freeprint\nNjob\n".toByteArray()
        out.write("\u0002${control.size} cfA001freeprint\n".toByteArray()); out.flush(); ack()
        out.write(control); out.write(0); out.flush(); ack()
        out.write("\u0003${data.size} dfA001freeprint\n".toByteArray()); out.flush(); ack()
        out.write(data); out.write(0); out.flush(); ack()
        socket.close()
    }
}

object UsbPermission {
    private const val ACTION = "com.freeprintotg.USB_PERMISSION"

    suspend fun ensure(ctx: Context, usb: UsbManager, dev: UsbDevice): Boolean {
        if (usb.hasPermission(dev)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    ctx.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ACTION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usb.requestPermission(dev, pi)
            cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
        }
    }
}

/**
 * USB printer-class bulk-OUT writer. Same sequence as NokoPrint's core/a0.java + core/z.java:
 * openDevice, claimInterface, 16 KiB bulk chunks, and a zero-length packet when the last
 * chunk ends on a max-packet boundary so the printer sees end-of-transfer.
 */
class UsbBulkSink(usb: UsbManager, ref: UsbEndpointRef, drainIn: Boolean = false) : PrintSink {
    private val dev: UsbDevice = usb.deviceList[ref.deviceName] ?: error("USB printer unplugged")
    private val itf: UsbInterface = dev.getInterface(ref.interfaceIndex)
    private val out: UsbEndpoint = itf.getEndpoint(ref.outEndpointIndex)
    private val conn: UsbDeviceConnection = usb.openDevice(dev) ?: error("Cannot open USB device (permission?)")
    private var lastFull = false
    @Volatile private var open = true

    init {
        if (!conn.claimInterface(itf, true)) { conn.close(); error("Cannot claim USB printer interface") }
        if (itf.alternateSetting > 0) conn.setInterface(itf)
        // Quirk drain_bulk_in: some printers stop accepting data while their status replies go unread.
        val inEp = ref.inEndpointIndex?.let(itf::getEndpoint)
        if (drainIn && inEp != null) Thread({
            val buf = ByteArray(inEp.maxPacketSize.coerceAtLeast(64) * 8)
            while (open) conn.bulkTransfer(inEp, buf, buf.size, 200)
        }, "usb-drain-in").apply { isDaemon = true }.start()
    }

    override fun write(data: ByteArray, off: Int, len: Int) {
        var p = off
        val end = off + len
        while (p < end) {
            val n = minOf(CHUNK, end - p)
            val sent = conn.bulkTransfer(out, data, p, n, 0)
            if (sent < 0) error("USB transfer error. Cannot send data.")
            p += sent
            lastFull = sent > 0 && sent % out.maxPacketSize == 0
        }
    }

    override fun close() {
        if (lastFull) conn.bulkTransfer(out, ByteArray(0), 0, 0, 1000)
        Thread.sleep(500) // let the printer drain before the interface is released
        open = false
        conn.releaseInterface(itf)
        conn.close()
    }

    companion object { private const val CHUNK = 16 * 1024 }
}

/** IPP-over-USB (interface protocol 4): plain HTTP/1.1 framed over a bulk OUT/IN pair. Experimental. */
class UsbIppChannel(usb: UsbManager, private val ref: UsbEndpointRef) : IppChannel {
    private val dev: UsbDevice = usb.deviceList[ref.deviceName] ?: error("USB printer unplugged")
    private val itf: UsbInterface = dev.getInterface(ref.interfaceIndex)
    private val out: UsbEndpoint = itf.getEndpoint(ref.outEndpointIndex)
    private val inp: UsbEndpoint = itf.getEndpoint(ref.inEndpointIndex ?: error("IPP-USB needs a bulk IN endpoint"))
    private val conn: UsbDeviceConnection = usb.openDevice(dev) ?: error("Cannot open USB device (permission?)")

    init {
        if (!conn.claimInterface(itf, true)) { conn.close(); error("Cannot claim IPP-USB interface") }
        if (itf.alternateSetting > 0) conn.setInterface(itf)
    }

    override fun post(body: ByteArray): ByteArray {
        val head = ("POST /ipp/print HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/ipp\r\n" +
            "Content-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray()
        send(head + body)
        return readHttpBody()
    }

    private fun send(all: ByteArray) {
        var p = 0
        while (p < all.size) {
            val n = conn.bulkTransfer(out, all, p, minOf(16 * 1024, all.size - p), 5000)
            if (n < 0) error("USB transfer error (IPP-USB write)")
            p += n
        }
    }

    private fun readHttpBody(): ByteArray {
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(inp.maxPacketSize.coerceAtLeast(512) * 8)
        var headerEnd = -1
        var contentLength = -1
        var chunked = false
        while (true) {
            val n = conn.bulkTransfer(inp, buf, buf.size, 60_000)
            if (n < 0) error("USB transfer error (IPP-USB read)")
            acc.write(buf, 0, n)
            val bytes = acc.toByteArray()
            if (headerEnd < 0) {
                val i = indexOf(bytes, "\r\n\r\n".toByteArray())
                if (i >= 0) {
                    headerEnd = i + 4
                    val head = String(bytes, 0, i).lowercase()
                    contentLength = Regex("content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: -1
                    chunked = head.contains("transfer-encoding: chunked")
                    if (!head.startsWith("http/1.1 200")) error("IPP-USB: ${head.lineSequence().first()}")
                }
            }
            if (headerEnd >= 0) {
                if (!chunked && contentLength >= 0 && bytes.size >= headerEnd + contentLength)
                    return bytes.copyOfRange(headerEnd, headerEnd + contentLength)
                if (chunked) dechunk(bytes.copyOfRange(headerEnd, bytes.size))?.let { return it }
            }
        }
    }

    private fun dechunk(b: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var p = 0
        while (true) {
            val e = indexOf(b, "\r\n".toByteArray(), p)
            if (e < 0) return null
            val size = String(b, p, e - p).trim().toInt(16)
            p = e + 2
            if (size == 0) return out.toByteArray()
            if (p + size + 2 > b.size) return null
            out.write(b, p, size)
            p += size + 2
        }
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    fun close() { conn.releaseInterface(itf); conn.close() }
}
