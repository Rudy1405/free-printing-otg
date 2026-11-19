package com.freeprintotg

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import com.freeprintotg.ble.BridgeClient
import com.freeprintotg.ble.BridgeError
import com.freeprintotg.discovery.BleDiscovery
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

/** Languages the app produces completely by itself: no engine pack is looked up for them. */
private val SELF_SUFFICIENT = setOf(
    Language.PDF, Language.PWG_RASTER, Language.POSTSCRIPT, Language.URF,
    Language.ESC_POS, Language.TSPL, Language.ZPL,
)
private const val RAW = "application/octet-stream"
/** Above this a Bluetooth job takes long enough to warn about (about 20-100 KB/s, see BLE-APP-PLAN.md section 3). */
private const val BLE_SLOW_BYTES = 512L * 1024

class PrintJob(private val ctx: Context, private val store: DriverStore, private val engines: EngineStore = EngineStore(ctx)) {

    private val usb get() = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)

    /** USB permission, a fresh Device ID (only readable with permission) and the firmware upload quirk. */
    private suspend fun prepare(selected: Printer, quirks: Quirks, onStatus: (String) -> Unit): Pair<Printer, Quirk?> {
        var printer = selected
        if (selected.usb != null) {
            val dev = usb.deviceList[selected.usb.deviceName] ?: error(s(R.string.job_usb_unplugged))
            if (!UsbPermission.ensure(ctx, usb, dev)) error(s(R.string.job_usb_denied))
            printer = UsbDiscovery.printersOf(usb, dev).firstOrNull { it.id == selected.id } ?: selected
        }
        if (selected.ble != null) printer = prepareBridge(selected, onStatus)
        AppLog.d("Printer: ${printer.name} via ${printer.transport} id=${printer.deviceId?.raw ?: "none"}")
        val quirk = quirks.forPrinter(printer)
        quirk?.let { AppLog.d("Quirk: ${it.id}") }
        if (printer.transport == Transport.USB_BULK && quirk?.firmwareUpload != null) {
            printer = FirmwareUpload.ensure(ctx, usb, printer, quirk.firmwareUpload, onStatus)
        }
        return printer to quirk
    }

    /**
     * Bluetooth bridge: reads who the printer behind it is (so driver choice, quirks and saved settings work as over
     * OTG). When the bridge is also on this phone's Wi-Fi, its RAW 9100 port is used instead: same printer, many
     * times faster than Bluetooth.
     */
    private fun prepareBridge(p: Printer, onStatus: (String) -> Unit): Printer {
        val ref = p.ble ?: return p
        if (BleDiscovery(ctx).missingPermissions().isNotEmpty()) error(s(R.string.job_ble_permission))
        onStatus(s(R.string.job_connecting_bt, p.name))
        val info = BridgeClient.connect(ctx, ref.address).use { it.readInfo() }
        AppLog.d("Bridge info: fw ${info.firmware}, state ${info.state}, id=${info.deviceId}, ip=${info.ip}")
        if (!info.hasPrinter) error(BridgeError.NO_PRINTER.message)
        val id = info.deviceId.takeIf { it.isNotBlank() }?.let(DeviceId::parse)
        val bridged = p.copy(
            name = listOfNotNull(id?.mfg, id?.mdl).joinToString(" ").ifBlank { "${info.usbManufacturer} ${info.usbProduct}".trim() }
                .ifBlank { p.name } + " (Bluetooth)",
            deviceId = id,
            ble = ref.copy(vendorId = info.vendorId, productId = info.productId),
        )
        val ip = info.ip ?: return bridged
        val reachable = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, 9100), 1500) } }.isSuccess
        if (!reachable) return bridged
        onStatus(s(R.string.job_bridge_on_wifi, ip))
        return bridged.copy(transport = Transport.RAW_9100, host = ip, port = 9100)
    }

    /**
     * Setup assistant (G4): one test page in [lang], sent raw. Office languages are preceded by a PJL
     * Universal Exit Language so a printer left in another language starts clean.
     */
    suspend fun printTestPage(selected: Printer, lang: Language, index: Int, total: Int, sheet: PaperSize, status: (String) -> Unit) {
        val onStatus: (String) -> Unit = { AppLog.d(it); status(it) }
        val (printer, quirk) = prepare(selected, store.quirks(), onStatus)
        val paper = LanguageProbe.testPaper(lang, sheet)
        val dpi = lang.defaultDpi ?: if (lang == Language.POSTSCRIPT) 150 else 300
        val page = TestPage.render(LanguageProbe.testText(lang, index, total), paper, dpi)
        val doc = RenderedDoc("test-$index", DocKind.IMAGE, null, sequenceOf(page))
        val (bytes, _) = encode(lang, doc, PrintOptions(paper = paper, color = false, dpi = dpi), dpi, pjl = false)
        val reset = if (lang.isLabelOrReceipt) ByteArray(0) else "\u001B%-12345X".toByteArray(Charsets.US_ASCII)
        onStatus(s(R.string.job_sending_test, index, total, lang.title))
        val all = reset + bytes
        openSink(printer, usb, quirk, all.size.toLong(), onStatus).use { it.write(all) }
    }

    suspend fun print(selected: Printer, uris: List<Uri>, opt: PrintOptions, status: (String) -> Unit) {
        val onStatus: (String) -> Unit = { AppLog.d(it); status(it) }
        val usb = usb
        val quirks = store.quirks()
        val (prepared, quirk) = prepare(selected, quirks, onStatus)
        val printer = prepared
        val ref = printer.usb
        val resolver = DriverResolver(store.bundled(), learned = LearnedLanguages.all(ctx), quirks = quirks)
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
                if (!useEngine) plan.blocker?.let { error(it) }
                onStatus(if (useEngine) s(R.string.job_driver, engine!!.entry.model ?: engine.entry.engine, engine.entry.engine) else plan.note)
                plan.driver?.takeIf { !store.isInstalled(it) }?.let {
                    onStatus(s(R.string.job_downloading_driver, it.name)); store.install(it)
                }

                val o = opt.copy(paper = mediaFor(plan, useEngine, opt.paper, quirk?.mediaDefault))
                if (o.paper != opt.paper) onStatus(s(R.string.job_using_paper, o.paper.label))

                onStatus(s(R.string.job_rendering))
                val dpi = when {
                    useEngine -> ENGINE_DPI[engine!!.entry.engine] ?: o.dpi
                    plan.language == Language.POSTSCRIPT -> 150
                    else -> plan.language.defaultDpi ?: o.dpi
                }
                val doc = DocumentRenderer.open(ctx, uri, o.paper, dpi)
                // PJL lets a multi-language printer switch to PCL/PostScript on its own; not used over IPP.
                val pjl = ippClient == null &&
                    (quirk?.needsPjl == true || printer.deviceId?.commands?.any { it.equals("PJL", ignoreCase = true) } == true)
                val (bytes, mime) = if (useEngine) runEngine(engine!!, doc, o, dpi, onStatus) else encode(plan.language, doc, o, dpi, pjl)

                onStatus(s(R.string.job_sending, bytes.size / 1024, printer.name))
                if (useEngine) {
                    // engine output is the printer's own language: raw port, never IPP document-format negotiation
                    openSink(printer.takeIf { it.transport != Transport.IPP } ?: printer.copy(transport = Transport.RAW_9100, port = 9100), usb, quirk, bytes.size.toLong(), onStatus).use { it.write(bytes) }
                } else if (ippClient != null) {
                    ippClient.printJob(bytes, mime, doc.name, o.copies, o.duplex, o.color)
                } else {
                    openSink(printer, usb, quirk, bytes.size.toLong(), onStatus).use { it.write(bytes) }
                }
                onStatus(s(R.string.job_sent, doc.name))
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
        val driverless = plan.driver == null && !plan.fallback && plan.language in SELF_SUFFICIENT
        if (driverless || plan.driver != null) return null
        val cat = runCatching { engines.catalogue() }.recoverCatching { engines.catalogue(refresh = true) }
            .onFailure { onStatus(s(R.string.job_catalogue_unavailable, it.message ?: "")) }.getOrNull() ?: return null
        val m = cat.match(printer.deviceId, printer.name, PrinterIdentity.of(printer)) ?: return null
        return if (m.quality == Catalogue.Quality.GENERIC && !plan.fallback) null else m
    }

    private fun runEngine(m: Catalogue.Match, doc: RenderedDoc, o: PrintOptions, dpi: Int, onStatus: (String) -> Unit): Pair<ByteArray, String> {
        val cat = engines.catalogue()
        val pack = cat.engines[m.entry.engine] ?: error("Catalogue has no engine ${m.entry.engine}")
        if (!engines.isInstalled(pack)) onStatus(s(R.string.job_downloading_pack, pack.id))
        val ppd = engines.ensurePpd(m.entry)
        val raster = Encoders.pwgRaster(doc.pages, o.paper, dpi, o.color, o.duplex, o.copies)
        val options = buildString {
            append("media=").append(o.paper.pwgName)
            if (!o.color) append(" print-color-mode=monochrome ColorModel=Gray")
            if (o.duplex) append(" sides=two-sided-long-edge")
        }
        onStatus(s(R.string.job_running, pack.id))
        return engines.run(pack, ppd, raster, doc.name, o.copies, options) to "application/octet-stream"
    }

    /** Receipt/label languages on a sheet size make no sense: switch to the usual roll or label. */
    private fun mediaFor(plan: Plan, useEngine: Boolean, chosen: PaperSize, quirkDefault: PaperSize?): PaperSize = when {
        useEngine || chosen.kind != MediaKind.SHEET -> chosen
        plan.language.isLabelOrReceipt && quirkDefault != null -> quirkDefault
        plan.language == Language.ESC_POS -> PaperSize.ROLL_58  // 384 dots also fit 80 mm printers
        plan.language == Language.TSPL || plan.language == Language.ZPL -> PaperSize.LABEL_50X25
        else -> chosen
    }

    private fun encode(lang: Language, doc: RenderedDoc, o: PrintOptions, dpi: Int, pjl: Boolean): Pair<ByteArray, String> = when (lang) {
        Language.PDF -> (doc.pdfBytes ?: Encoders.pdf(doc.pages, o.paper)) to Pdl.PDF.mime
        Language.PWG_RASTER -> Encoders.pwgRaster(doc.pages, o.paper, dpi, o.color, o.duplex, o.copies) to Pdl.PWG_RASTER.mime
        Language.POSTSCRIPT -> Encoders.postScript(doc.pages, o.paper).let { if (pjl) RasterEncoders.pjl(it, "POSTSCRIPT") else it } to Pdl.POSTSCRIPT.mime
        Language.PCL_MONO -> Encoders.pclMono(doc.pages, o.paper, dpi, o.copies).let { if (pjl) RasterEncoders.pjl(it, "PCL") else it } to Pdl.PCL.mime
        Language.PCL_XL -> RasterEncoders.pclXl(doc.pages.asRasters(), o.paper, dpi, o.copies) to Pdl.PCLXL.mime
        Language.URF -> RasterEncoders.urf(doc.pages.asRasters(), dpi, o.color, o.duplex) to Pdl.URF.mime
        Language.ESC_POS -> RasterEncoders.escPos(doc.pages.asRasters(), o.copies) to RAW
        Language.TSPL -> RasterEncoders.tspl(doc.pages.asRasters(), o.paper.widthMm, o.paper.heightMm, o.copies) to RAW
        Language.ZPL -> RasterEncoders.zpl(doc.pages.asRasters(), o.copies) to RAW
    }

    private fun openSink(p: Printer, usb: UsbManager, quirk: Quirk?, total: Long, onStatus: (String) -> Unit): PrintSink = when (p.transport) {
        Transport.BLE_BRIDGE -> {
            if (total > BLE_SLOW_BYTES) onStatus(s(R.string.job_bt_slow, total / 1024))
            var lastPct = -1
            BleSink(ctx, p.ble!!.address, total) { sent, all ->
                val pct = (sent * 100 / all.coerceAtLeast(1)).toInt()
                if (pct / 10 != lastPct / 10) { lastPct = pct; onStatus(s(R.string.job_bt_progress, pct, all / 1024)) }
            }
        }
        Transport.USB_BULK -> UsbBulkSink(usb, p.usb!!, drainIn = quirk?.drainBulkIn == true)
        Transport.RAW_9100 -> RawSocketSink(p.host!!, p.port.takeIf { it > 0 } ?: 9100)
        Transport.LPD -> LpdSink(p.host!!, p.port)
        else -> error("No raw sink for ${p.transport}")
    }
}
