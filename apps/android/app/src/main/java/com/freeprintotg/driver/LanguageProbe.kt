package com.freeprintotg.driver

import com.freeprintotg.Printer
import com.freeprintotg.Transport
import com.freeprintotg.render.PaperSize
import org.json.JSONObject

/**
 * The setup assistant of docs/GENERIC-PRINTING-PLAN.md section 5 (G4): when the app cannot tell what a printer
 * speaks, print one short test page per candidate language and let the user say which one came out readable.
 * Pure logic; the Android side renders and sends the pages.
 */
object LanguageProbe {
    private val OFFICE = listOf(Language.PCL_MONO, Language.POSTSCRIPT, Language.PCL_XL, Language.PDF)
    private val LABEL_RECEIPT = listOf(Language.ESC_POS, Language.TSPL, Language.ZPL)
    private val LABEL_VENDORS = setOf("Zebra", "TSC", "Star", "Citizen", "Bixolon")

    /** IPP printers say what they accept; only raw links (USB, 9100, LPD) need the assistant. */
    fun applies(p: Printer) = p.transport == Transport.USB_BULK || p.transport == Transport.RAW_9100 || p.transport == Transport.LPD ||
        p.transport == Transport.BLE_BRIDGE

    /**
     * Languages to try, most likely first. Office languages fail quietly on the wrong printer (a page or nothing),
     * so they lead unless the printer looks like a label or receipt printer, where they would waste a roll.
     */
    fun candidates(who: PrinterIdentity): List<Language> {
        val declared = buildList {
            val f = who.families
            if (PdlFamily.PCL5 in f || PdlFamily.PCL3GUI in f) add(Language.PCL_MONO)
            if (PdlFamily.POSTSCRIPT in f) add(Language.POSTSCRIPT)
            if (PdlFamily.PCLXL in f) add(Language.PCL_XL)
            if (PdlFamily.PDF in f) add(Language.PDF)
            if (PdlFamily.ESCPOS in f) add(Language.ESC_POS)
            if (PdlFamily.TSPL in f) add(Language.TSPL)
            if (PdlFamily.ZPL in f) add(Language.ZPL)
        }
        val looksLabel = who.vendor in LABEL_VENDORS || declared.any { it in LABEL_RECEIPT }
        val rest = if (looksLabel) LABEL_RECEIPT + OFFICE else OFFICE + LABEL_RECEIPT
        return (declared + rest).distinct()
    }

    /** Media for a test page: a label or a receipt roll for those languages, else the user's sheet size. */
    fun testPaper(lang: Language, sheet: PaperSize): PaperSize = when (lang) {
        Language.ESC_POS -> PaperSize.ROLL_58
        Language.TSPL, Language.ZPL -> PaperSize.LABEL_50X25
        else -> sheet
    }

    /** Text printed on test page [index] (1-based) of [total]: short enough for a 50 x 25 mm label. */
    fun testText(lang: Language, index: Int, total: Int) = listOf("Test $index/$total", lang.title, "Readable? Choose it in the app")
}

/** A language the user confirmed for one printer. [toJson] is what "Share setting" sends, for the catalogue. */
data class LearnedEntry(val key: String, val language: Language, val vendor: String?, val model: String?, val deviceId: String?) {
    fun toJson(): String = JSONObject().apply {
        put("key", key)
        put("language", language.name)
        vendor?.let { put("vendor", it) }
        model?.let { put("model", it) }
        deviceId?.let { put("device_id", it) }
        put("source", "free-printing-otg setup assistant")
    }.toString(2)

    companion object {
        fun of(p: Printer, lang: Language): LearnedEntry? {
            val who = PrinterIdentity.of(p)
            return who.key?.let { LearnedEntry(it, lang, who.vendor, who.model, p.deviceId?.raw?.ifBlank { null }) }
        }
    }
}
