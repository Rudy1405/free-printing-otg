package com.freeprintotg

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import com.freeprintotg.discovery.UsbDiscovery
import com.freeprintotg.driver.*
import com.freeprintotg.ipp.HttpIppChannel
import com.freeprintotg.ipp.IppClient
import com.freeprintotg.render.*
import com.freeprintotg.transport.*

data class PrintOptions(
    val paper: PaperSize = PaperSize.A4,
    val copies: Int = 1,
    val color: Boolean = true,
    val duplex: Boolean = false,
    val dpi: Int = 300,
)

/**
 * End-to-end print: render -> pick language -> encode -> deliver over IPP / socket / LPD / USB.
 * [onStatus] gets human readable progress lines.
 */
private val ENGINE_DPI = mapOf("escpr" to 360, "hplip" to 600, "splix" to 600, "gutenprint" to 360)

class PrintJob(private val ctx: Context, private val store: DriverStore, private val engines: EngineStore = EngineStore(ctx)) {

    suspend fun print(selected: Printer, uris: List<Uri>, opt: PrintOptions, status: (String) -> Unit) {
        val onStatus: (String) -> Unit = { AppLog.d(it); status(it) }
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        var printer = selected
        if (selected.usb != null) {
            val dev = usb.deviceList[selected.usb.deviceName] ?: error("USB printer unplugged")
            if (!UsbPermission.ensure(ctx, usb, dev)) error("USB permission denied")
            // the IEEE-1284 id can only be read once permission is granted
            printer = UsbDiscovery.printersOf(usb, dev).firstOrNull { it.id == selected.id } ?: selected
        }
        val ref = printer.usb
        AppLog.d("Printer: ${printer.name} via ${printer.transport} id=${printer.deviceId?.raw ?: "none"}")

        val resolver = DriverResolver(store.bundled())
        for (uri in uris) {
            // IPP printers tell us exactly what they accept; ask once per job.
            var ippClient: IppClient? = null
            var usbChannel: UsbIppChannel? = null
            var advertised = emptySet<String>()
            try {
                if (printer.transport == Transport.IPP) {
                    val scheme = if (printer.tls) "https" else "http"
                    val url = "$scheme://${printer.host}:${printer.port}${printer.ippPath}"
                    ippClient = IppClient(HttpIppChannel(url, printer.tls), url.replaceFirst(scheme, if (printer.tls) "ipps" else "ipp"))
                    advertised = runCatching { ippClient.supportedFormats() }.getOrDefault(emptySet())
                } else if (printer.transport == Transport.IPP_USB) {
                    usbChannel = UsbIppChannel(usb, ref!!)
                    ippClient = IppClient(usbChannel, "ipp://localhost/ipp/print")
                    advertised = runCatching { ippClient.supportedFormats() }.getOrDefault(emptySet())
                }

                var plan = resolver.resolve(printer, advertised)
                val engine = if (engines.configured()) chooseEngine(printer, plan, onStatus) else null
                if (engine != null && engine.entry.engine == "builtin-ps") plan = Plan(Language.POSTSCRIPT, null, "Generic PostScript")
                val useEngine = engine != null && engine.entry.engine != "builtin-ps"
                onStatus(if (useEngine) "Driver: ${engine!!.entry.model ?: engine.entry.engine} (${engine.entry.engine})" else plan.note)
                plan.driver?.takeIf { !store.isInstalled(it) }?.let {
                    onStatus("Downloading driver ${it.name}…"); store.install(it)
                }

                onStatus("Rendering…")
                val dpi = when {
                    useEngine -> ENGINE_DPI[engine!!.entry.engine] ?: opt.dpi
                    plan.language == Language.POSTSCRIPT -> 150
                    else -> opt.dpi
                }
                val doc = DocumentRenderer.open(ctx, uri, opt.paper, dpi)
                val (bytes, mime) = if (useEngine) runEngine(engine!!, doc, opt, dpi, onStatus) else encode(plan.language, doc, opt, dpi)

                onStatus("Sending ${bytes.size / 1024} KiB to ${printer.name}…")
                if (useEngine) {
                    // engine output is the printer's own language: raw port, never IPP document-format negotiation
                    openSink(printer.takeIf { it.transport != Transport.IPP } ?: printer.copy(transport = Transport.RAW_9100, port = 9100), usb).use { it.write(bytes) }
                } else if (ippClient != null) {
                    ippClient.printJob(bytes, mime, doc.name, opt.copies, opt.duplex, opt.color)
                } else {
                    openSink(printer, usb).use { it.write(bytes) }
                }
                onStatus("Sent ${doc.name}")
            } finally {
                usbChannel?.close()
            }
        }
    }

    /**
     * Engine packs only when the printer cannot take PDF/PWG/PostScript itself. A model-level catalogue match wins
     * over the generic PCL guess; a Generic PPD is used only for [Plan.fallback].
     */
    private fun chooseEngine(printer: Printer, plan: Plan, onStatus: (String) -> Unit): Catalogue.Match? {
        val driverless = plan.driver == null && !plan.fallback && plan.language in setOf(Language.PDF, Language.PWG_RASTER, Language.POSTSCRIPT)
        if (driverless || plan.driver != null) return null
        val cat = runCatching { engines.catalogue() }.recoverCatching { engines.catalogue(refresh = true) }
            .onFailure { onStatus("Driver catalogue unavailable: ${it.message}") }.getOrNull() ?: return null
        val m = cat.match(printer.deviceId, printer.name) ?: return null
        return if (m.quality == Catalogue.Quality.GENERIC && !plan.fallback) null else m
    }

    private fun runEngine(m: Catalogue.Match, doc: RenderedDoc, o: PrintOptions, dpi: Int, onStatus: (String) -> Unit): Pair<ByteArray, String> {
        val cat = engines.catalogue()
        val pack = cat.engines[m.entry.engine] ?: error("Catalogue has no engine ${m.entry.engine}")
        if (!engines.isInstalled(pack)) onStatus("Downloading ${pack.id} driver…")
        val ppd = engines.ensurePpd(m.entry)
        val raster = Encoders.pwgRaster(doc.pages, o.paper, dpi, o.color, o.duplex, o.copies)
        val options = buildString {
            append("media=").append(o.paper.pwgName)
            if (!o.color) append(" print-color-mode=monochrome ColorModel=Gray")
            if (o.duplex) append(" sides=two-sided-long-edge")
        }
        onStatus("Running ${pack.id}…")
        return engines.run(pack, ppd, raster, doc.name, o.copies, options) to "application/octet-stream"
    }

    private fun encode(lang: Language, doc: RenderedDoc, o: PrintOptions, dpi: Int): Pair<ByteArray, String> = when (lang) {
        Language.PDF -> (doc.pdfBytes ?: Encoders.pdf(doc.pages, o.paper)) to Pdl.PDF.mime
        Language.PWG_RASTER -> Encoders.pwgRaster(doc.pages, o.paper, dpi, o.color, o.duplex, o.copies) to Pdl.PWG_RASTER.mime
        Language.POSTSCRIPT -> Encoders.postScript(doc.pages, o.paper) to Pdl.POSTSCRIPT.mime
        Language.PCL_MONO -> Encoders.pclMono(doc.pages, o.paper, dpi, o.copies) to Pdl.PCL.mime
    }

    private fun openSink(p: Printer, usb: UsbManager): PrintSink = when (p.transport) {
        Transport.USB_BULK -> UsbBulkSink(usb, p.usb!!)
        Transport.RAW_9100 -> RawSocketSink(p.host!!, p.port.takeIf { it > 0 } ?: 9100)
        Transport.LPD -> LpdSink(p.host!!, p.port)
        else -> error("No raw sink for ${p.transport}")
    }
}
