package com.freeprintotg.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.freeprintotg.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wi-Fi / LAN discovery over mDNS (DNS-SD). NokoPrint browses the same service types
 * (discovery/m.java: _pdl-datastream, _printer, _ipp, _ipps).
 */
class NetworkDiscovery(ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val serviceTypes = listOf("_ipp._tcp", "_ipps._tcp", "_pdl-datastream._tcp", "_printer._tcp")

    fun discover(): Flow<Printer> = callbackFlow {
        val lock = wifi.createMulticastLock("fpo-mdns").apply { setReferenceCounted(true); acquire() }
        val listeners = serviceTypes.map { type ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) { resolve(s, type, 5) }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            l
        }
        awaitClose {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            if (lock.isHeld) lock.release()
        }
    }

    /** NsdManager resolves one service at a time (pre API 34): FAILURE_ALREADY_ACTIVE just means "try again shortly". */
    private fun ProducerScope<Printer>.resolve(s: NsdServiceInfo, type: String, triesLeft: Int) {
        @Suppress("DEPRECATION")
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                AppLog.d("mDNS resolve failed for ${s.serviceName} ($type), code $code")
                if (triesLeft > 0) launch { delay(400L * (6 - triesLeft)); resolve(s, type, triesLeft - 1) }
            }
            override fun onServiceResolved(r: NsdServiceInfo) {
                val p = toPrinter(r, type)
                AppLog.d("mDNS found ${p?.name ?: r.serviceName} $type ${p?.host}:${r.port}")
                p?.let { trySend(it) }
            }
        })
    }

    private fun toPrinter(r: NsdServiceInfo, type: String): Printer? {
        // IPv6 literals need brackets and a scope id that the HTTP/socket code here does not handle; IPv4 is what printers resolve to.
        @Suppress("DEPRECATION") val host = (r.host as? java.net.Inet4Address)?.hostAddress ?: return null
        val txt = r.attributes.mapValues { it.value?.toString(Charsets.UTF_8).orEmpty() }
        val name = txt["ty"]?.ifBlank { null } ?: r.serviceName
        val formats = txt["pdl"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        // Lets the driver choice work for raw/LPD print servers (e.g. the ESP32 bridge) the same way it does over OTG.
        val deviceId = DeviceId.fromTxt(txt)
        return when (type) {
            "_ipp._tcp", "_ipps._tcp" -> Printer(
                id = "ipp:$host:${r.port}", name = name, transport = Transport.IPP,
                host = host, port = r.port, tls = type == "_ipps._tcp",
                ippPath = "/" + (txt["rp"] ?: "ipp/print").trimStart('/'),
                formats = formats, deviceId = deviceId,
            )
            "_pdl-datastream._tcp" -> Printer(
                id = "raw:$host:${r.port}", name = name, transport = Transport.RAW_9100,
                host = host, port = r.port, formats = formats, deviceId = deviceId,
            )
            else -> Printer(
                id = "lpd:$host:${r.port}", name = name, transport = Transport.LPD,
                host = host, port = r.port, formats = formats, deviceId = deviceId,
            )
        }
    }
}
