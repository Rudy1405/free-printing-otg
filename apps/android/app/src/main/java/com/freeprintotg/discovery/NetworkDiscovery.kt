package com.freeprintotg.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.freeprintotg.*
import kotlinx.coroutines.channels.awaitClose
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
                override fun onServiceFound(s: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(r: NsdServiceInfo) { toPrinter(r, type)?.let { trySend(it) } }
                    })
                }
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

    private fun toPrinter(r: NsdServiceInfo, type: String): Printer? {
        @Suppress("DEPRECATION") val host = r.host?.hostAddress ?: return null
        val txt = r.attributes.mapValues { it.value?.toString(Charsets.UTF_8).orEmpty() }
        val name = txt["ty"]?.ifBlank { null } ?: r.serviceName
        val formats = txt["pdl"]?.split(',')?.map { it.trim() }?.toSet().orEmpty()
        return when (type) {
            "_ipp._tcp", "_ipps._tcp" -> Printer(
                id = "ipp:$host:${r.port}", name = name, transport = Transport.IPP,
                host = host, port = r.port, tls = type == "_ipps._tcp",
                ippPath = "/" + (txt["rp"] ?: "ipp/print").trimStart('/'),
                formats = formats,
            )
            "_pdl-datastream._tcp" -> Printer(
                id = "raw:$host:${r.port}", name = name, transport = Transport.RAW_9100,
                host = host, port = r.port, formats = formats,
            )
            else -> Printer(
                id = "lpd:$host:${r.port}", name = name, transport = Transport.LPD,
                host = host, port = r.port, formats = formats,
            )
        }
    }
}
