package com.freeprintotg.driver

import android.content.Context
import com.freeprintotg.*
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Output language a driver produces. Each maps to one encoder in [com.freeprintotg.render.Encoders] or
 * [com.freeprintotg.render.RasterEncoders].
 */
enum class Language(val defaultDpi: Int? = null) {
    PDF, PWG_RASTER, POSTSCRIPT, PCL_MONO,
    PCL_XL(300), URF,
    /** Receipt and label printers are almost all 8 dots/mm. */
    ESC_POS(203), TSPL(203), ZPL(203),
    ;

    val isLabelOrReceipt get() = this == ESC_POS || this == TSPL || this == ZPL
}

data class DriverEntry(
    val id: String,
    val name: String,
    val language: Language,
    val mfg: List<String>,
    val mdlRegex: Regex?,
    val commands: List<String>,
    val url: String?,
    val sha256: String?,
) {
    val builtin get() = url == null
}

/**
 * [fallback] marks a last-resort guess; the caller may replace it with an engine pack from the hosted catalogue.
 * [blocker] is set when [language] must not be sent as is: only an engine pack can print, and without one the job
 * fails with this message instead of printing garbage.
 */
data class Plan(
    val language: Language,
    val driver: DriverEntry?,
    val note: String,
    val fallback: Boolean = false,
    val blocker: String? = null,
)

private val ENGINE_ONLY = setOf(PdlFamily.ESCPR, PdlFamily.ESCP2, PdlFamily.SPL)

/** Usual language per manufacturer, used only when the printer names no language and no model matches. */
private val PCL_VENDORS = setOf(
    "HP", "Brother", "Kyocera", "Lexmark", "Ricoh", "OKI", "Konica Minolta", "Sharp",
    "Xerox", "Fuji Xerox", "Dell", "Toshiba", "Samsung", "Pantum",
)
private val INKJET_VENDORS = setOf("Epson", "Canon")
/** Receipt printer makers whose models accept ESC/POS (Star models in their ESC/POS emulation). */
private val RECEIPT_VENDORS = setOf("Star", "Citizen", "Bixolon")

/**
 * Chooses how to talk to any printer from what it reports, never from a hard-coded model
 * (see docs/GENERIC-PRINTING-PLAN.md section 3):
 *  0. A language saved for this printer ([learned], keyed by [PrinterIdentity.key]); a [Quirk.unsupported] printer stops here.
 *  1. Format the printer advertises: PDF, then PWG raster (IPP Everywhere / AirPrint class); then [Quirk.forceLanguage].
 *  2. Manifest entry matching the normalised manufacturer / model / command set.
 *  3. Language families in the IEEE-1284 CMD list.
 *  4. Manufacturer alone (Device ID, USB vendor id or name): its usual language.
 *  5. Unknown: generic PCL, flagged as a guess.
 * Printers whose only language is closed or not encodable yet get a [Plan.blocker] instead of a wrong guess.
 */
class DriverResolver(
    private val entries: List<DriverEntry>,
    private val learned: Map<String, Language> = emptyMap(),
    private val quirks: Quirks = Quirks.EMPTY,
) {

    fun resolve(printer: Printer, advertised: Set<String>): Plan {
        val who = PrinterIdentity.of(printer)
        who.key?.let(learned::get)?.let { return Plan(it, null, "Saved setting for this printer: $it") }
        val quirk = quirks.forPrinter(printer)
        quirk?.unsupported?.let { return unsupported(it) }

        val fmts = (advertised + printer.formats).map { it.lowercase() }.toSet()
        if ("application/pdf" in fmts) return Plan(Language.PDF, null, "Driverless PDF")
        if ("image/pwg-raster" in fmts) return Plan(Language.PWG_RASTER, null, "Driverless PWG raster")
        if ("image/urf" in fmts) return Plan(Language.URF, null, "Driverless Apple raster (URF)")

        quirk?.forceLanguage?.let { return Plan(it, null, "Printer setting (${quirk.id}): $it") }

        entries.firstOrNull { matches(it, who, printer.deviceId) }?.let { return Plan(it.language, it, "Driver: ${it.name}") }

        byLanguage(who)?.let { return it }
        byVendor(who)?.let { return it }
        return Plan(Language.PCL_MONO, null, "Unknown printer: generic PCL fallback (may not work)", fallback = true)
    }

    private fun byLanguage(who: PrinterIdentity): Plan? {
        val f = who.families
        return when {
            PdlFamily.PDF in f -> Plan(Language.PDF, null, "Printer reports PDF")
            PdlFamily.PWG in f -> Plan(Language.PWG_RASTER, null, "Printer reports PWG raster")
            PdlFamily.POSTSCRIPT in f -> Plan(Language.POSTSCRIPT, null, "Printer reports PostScript")
            PdlFamily.PCL5 in f || PdlFamily.PCL3GUI in f ->
                Plan(Language.PCL_MONO, null, "Generic PCL driver", fallback = true)
            PdlFamily.PCLXL in f -> Plan(Language.PCL_XL, null, "Generic PCL 6 (PCL XL) driver", fallback = true)
            PdlFamily.ESCPR in f || PdlFamily.ESCP2 in f || PdlFamily.SPL in f ->
                needsEngine("Printer speaks ${f.first { it in ENGINE_ONLY }}: needs a driver pack")
            PdlFamily.TSPL in f -> Plan(Language.TSPL, null, "Label printer: TSPL")
            PdlFamily.ZPL in f -> Plan(Language.ZPL, null, "Label printer: ZPL")
            PdlFamily.ESCPOS in f -> Plan(Language.ESC_POS, null, "Receipt printer: ESC/POS")
            PdlFamily.EPL in f -> unsupported("Label printer that only speaks EPL: not supported yet")
            PdlFamily.CLOSED_HOST_BASED in f ->
                unsupported("This printer only works with its Windows driver (closed host-based language)")
            else -> null
        }
    }

    private fun byVendor(who: PrinterIdentity): Plan? {
        val v = who.vendor ?: return null
        return when (v) {
            in PCL_VENDORS -> Plan(Language.PCL_MONO, null, "Probably PCL ($v)", fallback = true)
            in INKJET_VENDORS -> needsEngine("$v printer: needs a driver pack")
            "Zebra" -> Plan(Language.ZPL, null, "Probably ZPL ($v)")
            "TSC" -> Plan(Language.TSPL, null, "Probably TSPL ($v)")
            in RECEIPT_VENDORS -> Plan(Language.ESC_POS, null, "Probably ESC/POS ($v)")
            else -> null
        }
    }

    private fun needsEngine(msg: String) = Plan(Language.PCL_MONO, null, msg, fallback = true, blocker = msg)
    private fun unsupported(msg: String) = Plan(Language.PCL_MONO, null, msg, blocker = msg)

    private fun matches(e: DriverEntry, who: PrinterIdentity, id: DeviceId?): Boolean {
        if (e.mfg.isNotEmpty()) {
            val vendor = who.vendor ?: id?.mfg ?: return false
            if (e.mfg.none { (Vendors.canonical(it) ?: it).equals(vendor, ignoreCase = true) }) return false
        }
        if (e.mdlRegex != null && listOfNotNull(id?.mdl, who.model).none(e.mdlRegex::containsMatchIn)) return false
        if (e.commands.isNotEmpty()) {
            val raw = id?.commands.orEmpty()
            val sameToken = e.commands.any { c -> raw.any { it.equals(c, true) } }
            if (!sameToken && Languages.families(e.commands).intersect(who.families).isEmpty()) return false
        }
        return e.mfg.isNotEmpty() || e.mdlRegex != null
    }

    companion object {
        fun parse(json: String): List<DriverEntry> {
            val arr = JSONObject(json).getJSONArray("drivers")
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val m = o.optJSONObject("match")
                fun list(k: String) = m?.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
                DriverEntry(
                    id = o.getString("id"), name = o.getString("name"),
                    language = Language.valueOf(o.getString("language")),
                    mfg = list("mfg"), mdlRegex = m?.optString("mdl")?.takeIf { it.isNotEmpty() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
                    commands = list("cmd"), url = o.optString("url").ifEmpty { null }, sha256 = o.optString("sha256").ifEmpty { null },
                )
            }
        }
    }
}

/**
 * Downloads driver packs listed in the manifest. A pack is a zip with a `driver.json`; it is only
 * kept when its SHA-256 equals the manifest value, so a hijacked mirror cannot slip code or data in.
 */
class DriverStore(private val ctx: Context) {
    private val root get() = File(ctx.filesDir, "drivers").apply { mkdirs() }

    fun bundled(): List<DriverEntry> =
        DriverResolver.parse(ctx.assets.open("drivers.json").bufferedReader().use { it.readText() })

    /** Bundled `quirks.json`; an unreadable file must never stop printing, so it degrades to no quirks. */
    fun quirks(): Quirks = runCatching {
        Quirks.parse(ctx.assets.open("quirks.json").bufferedReader().use { it.readText() })
    }.onFailure { com.freeprintotg.AppLog.d("quirks.json unreadable: ${it.message}") }.getOrDefault(Quirks.EMPTY)

    fun fetchManifest(url: String): List<DriverEntry> =
        DriverResolver.parse(URL(url).openStream().bufferedReader().use { it.readText() })

    fun isInstalled(e: DriverEntry) = e.builtin || File(root, "${e.id}/driver.json").exists()

    /** Returns the installed directory. Throws when the hash does not match. */
    fun install(e: DriverEntry): File {
        val url = e.url ?: return root
        val expected = e.sha256 ?: error("Manifest entry ${e.id} has no sha256; refusing to install")
        val bytes = URL(url).openStream().use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, ignoreCase = true)) { "Driver pack hash mismatch for ${e.id}" }
        val dir = File(root, e.id).apply { deleteRecursively(); mkdirs() }
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { z ->
                val f = File(dir, z.name).canonicalFile
                require(f.path.startsWith(dir.canonicalPath + File.separator)) { "Bad zip entry ${z.name}" }
                if (z.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
            }
        }
        return dir
    }
}
