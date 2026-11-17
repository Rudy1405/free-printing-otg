package com.freeprintotg.driver

import com.freeprintotg.Printer

/**
 * Printer languages as families. IEEE-1284 `CMD` lists spell the same language many ways
 * (PS, PS3, POSTSCRIPT2, BR-Script...); every decision is made on the family, never on the raw token.
 */
enum class PdlFamily {
    PDF, PWG, URF, POSTSCRIPT, PCL5, PCLXL, PCL3GUI, ESCPR, ESCP2, SPL,
    ESCPOS, TSPL, ZPL, EPL,
    /** Closed host-based languages with no open driver: printing would only waste paper. */
    CLOSED_HOST_BASED,
}

/** Who the printer is, after cleaning up what it reports over USB, mDNS or BLE. Pure Kotlin, no Android types. */
data class PrinterIdentity(
    /** Canonical manufacturer ("HP", "Epson"...), or null when nothing identifies it. */
    val vendor: String?,
    /** Model without the manufacturer prefix or "Series" suffix, or null. */
    val model: String?,
    val families: Set<PdlFamily>,
    val vendorId: Int? = null,
    val productId: Int? = null,
) {
    /** Stable key for remembering a per-printer choice: USB ids when known, else vendor + model. */
    val key: String?
        get() = when {
            vendorId != null && productId != null -> "usb:%04x:%04x".format(vendorId, productId)
            vendor != null && model != null -> "name:" + Catalogue.norm("$vendor $model")
            else -> null
        }

    companion object {
        fun of(p: Printer): PrinterIdentity {
            val id = p.deviceId
            val vid = p.usb?.vendorId
            val vendor = Vendors.canonical(id?.mfg) ?: vid?.let(Vendors::fromVid) ?: Vendors.fromName(p.name)
            // Without a Device ID, the display name is a model only when it names a maker ("HP LaserJet 1020"),
            // not when it is a placeholder like "USB printer 03f0:0517".
            val rawModel = id?.mdl?.takeIf { it.isNotBlank() } ?: p.name.takeIf { Vendors.fromName(it) != null }
            return PrinterIdentity(
                vendor = vendor,
                model = rawModel?.let { Models.normalize(vendor, it) }?.ifBlank { null },
                families = Languages.families(id?.commands.orEmpty()),
                vendorId = vid,
                productId = p.usb?.productId,
            )
        }
    }
}

object Vendors {
    /** Lower-case spellings seen in Device IDs, mDNS `ty` and USB strings, mapped to one name. */
    private val aliases: Map<String, String> = buildMap {
        fun add(name: String, vararg spellings: String) { put(name.lowercase(), name); spellings.forEach { put(it, name) } }
        add("HP", "hewlett-packard", "hewlett packard", "hp inc", "hp inc.", "hewlettpackard")
        add("Epson", "seiko epson", "seiko epson corp", "seiko epson corporation", "epson america")
        add("Canon", "canon inc", "canon inc.")
        add("Brother", "brother industries", "brother industries, ltd", "brother industries ltd")
        add("Samsung", "samsung electronics", "samsung electronics co., ltd.")
        add("Xerox", "xerox corporation")
        add("Fuji Xerox", "fujixerox", "fuji-xerox")
        add("Lexmark", "lexmark international", "lexmark international, inc.")
        add("Kyocera", "kyocera mita", "kyocera document solutions", "kyoceramita")
        add("Ricoh", "ricoh company", "ricoh co., ltd.")
        add("OKI", "oki data", "oki data corp", "okidata", "oki electric")
        add("Konica Minolta", "konicaminolta", "konica-minolta", "minolta", "konica")
        add("Sharp", "sharp corporation")
        add("Toshiba", "toshiba tec")
        add("Dell")
        add("Pantum")
        add("Zebra", "zebra technologies", "zebra technologies corporation")
        add("TSC", "tsc auto id", "tsc auto id technology")
        add("Star", "star micronics", "star micronics co., ltd.")
        add("Citizen", "citizen systems")
        add("Bixolon")
    }

    /** USB vendor ids of printer makers (subset of usb.ids). Used when a printer returns no Device ID. */
    private val byVid = mapOf(
        0x03F0 to "HP", 0x04B8 to "Epson", 0x04A9 to "Canon", 0x04F9 to "Brother", 0x04E8 to "Samsung",
        0x0924 to "Xerox", 0x043D to "Lexmark", 0x0482 to "Kyocera", 0x05CA to "Ricoh", 0x06BC to "OKI",
        0x132B to "Konica Minolta", 0x04DD to "Sharp", 0x413C to "Dell", 0x0A5F to "Zebra", 0x1203 to "TSC",
        0x0519 to "Star", 0x1D90 to "Citizen", 0x1504 to "Bixolon",
    )

    fun canonical(mfg: String?): String? {
        val k = mfg?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return aliases[k] ?: aliases[k.removeSuffix(".").removeSuffix(",").trim()]
    }

    fun fromVid(vid: Int): String? = byVid[vid]

    /** "HP LaserJet 1020" -> HP. Longest alias first so "Fuji Xerox" wins over "Xerox". */
    fun fromName(name: String): String? {
        val n = name.trim().lowercase()
        return aliases.keys.sortedByDescending { it.length }
            .firstOrNull { n == it || n.startsWith("$it ") }?.let { aliases[it] }
    }

    /** All spellings that mean [vendor]; used to strip it from model names. */
    internal fun spellings(vendor: String): List<String> =
        aliases.filterValues { it == vendor }.keys.sortedByDescending { it.length }
}

object Models {
    private val noise = Regex("\\s+(series|printer|usb)$", RegexOption.IGNORE_CASE)

    /** "HP LaserJet 1020 Series" -> "LaserJet 1020"; keeps the model when the vendor is unknown. */
    fun normalize(vendor: String?, model: String): String {
        var m = model.trim().replace(Regex("\\s+"), " ")
        if (vendor != null) {
            val lower = m.lowercase()
            Vendors.spellings(vendor).firstOrNull { lower.startsWith("$it ") }?.let { m = m.substring(it.length).trim() }
        }
        while (true) {
            val next = noise.replace(m, "").trim()
            if (next == m) break
            m = next
        }
        return m
    }
}

object Languages {
    private fun family(token: String): PdlFamily? {
        val t = token.trim().uppercase().replace(" ", "")
        return when {
            t == "PDF" || t.startsWith("PDF") -> PdlFamily.PDF
            t.startsWith("PWG") -> PdlFamily.PWG
            t == "URF" || t.startsWith("APPLERASTER") -> PdlFamily.URF
            t == "PS" || t.startsWith("PS2") || t.startsWith("PS3") || t.startsWith("POSTSCRIPT") ||
                t.replace("-", "").startsWith("BRSCRIPT") || t == "KPDL" -> PdlFamily.POSTSCRIPT
            t == "PCLXL" || t == "PCL6" || t == "PCL-XL" -> PdlFamily.PCLXL
            t.startsWith("PCL3") -> PdlFamily.PCL3GUI
            t.startsWith("PCL") -> PdlFamily.PCL5
            t.startsWith("ESCPR") || t == "ESC/P-R" -> PdlFamily.ESCPR
            t.startsWith("ESCPL2") || t.startsWith("ESC/P2") || t.startsWith("ESCP2") -> PdlFamily.ESCP2
            t == "SPL" || t.startsWith("SPL") -> PdlFamily.SPL
            t == "ESC/POS" || t == "ESCPOS" || t.startsWith("ESC/POS") -> PdlFamily.ESCPOS
            t.startsWith("TSPL") -> PdlFamily.TSPL
            t.startsWith("ZPL") -> PdlFamily.ZPL
            t.startsWith("EPL") -> PdlFamily.EPL
            t == "CAPT" || t == "GDI" || t.startsWith("UFRIILT") -> PdlFamily.CLOSED_HOST_BASED
            else -> null
        }
    }

    fun families(commands: List<String>): Set<PdlFamily> = commands.mapNotNull(::family).toSet()
}
