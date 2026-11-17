package com.freeprintotg.driver

import com.freeprintotg.Printer
import com.freeprintotg.render.PaperSize
import org.json.JSONObject

/**
 * Per-printer exceptions kept as data (`packages/driver-manifest/quirks.json`), shared by the app and the ESP32
 * bridge firmware, so supporting an odd printer means editing data, never code. See
 * docs/GENERIC-PRINTING-PLAN.md section 6. USB-level fields are applied by whoever owns the USB link: the app over
 * OTG, the bridge firmware otherwise.
 */
data class Quirk(
    val id: String,
    val usbIds: Set<String> = emptySet(),
    val mfg: List<String> = emptyList(),
    val mdlRegex: Regex? = null,
    /** File name of the firmware a host-based printer needs after every power-on (e.g. sihp1020.dl). */
    val firmwareUpload: String? = null,
    /** USB: release and claim the interface again between jobs. */
    val reclaimBetweenJobs: Boolean = false,
    /** USB: keep reading the bulk IN endpoint while sending, or the printer stalls. */
    val drainBulkIn: Boolean = false,
    /** Wrap PCL/PostScript in PJL even if the Device ID does not list PJL. */
    val needsPjl: Boolean = false,
    /** Language to use whatever the printer reports (for printers whose CMD list is wrong). */
    val forceLanguage: Language? = null,
    /** Media to use when the user left a sheet size on a label/receipt printer. */
    val mediaDefault: PaperSize? = null,
    /** When set, the printer cannot be driven and this message is shown instead of printing. */
    val unsupported: String? = null,
) {
    fun matches(who: PrinterIdentity, rawMdl: String?): Boolean {
        if (usbIds.isNotEmpty()) {
            val vid = who.vendorId ?: return false
            val pid = who.productId ?: return false
            return "%04x:%04x".format(vid, pid) in usbIds
        }
        if (mfg.isNotEmpty() && mfg.none { (Vendors.canonical(it) ?: it).equals(who.vendor, ignoreCase = true) }) return false
        if (mdlRegex != null && listOfNotNull(rawMdl, who.model).none(mdlRegex::containsMatchIn)) return false
        return mfg.isNotEmpty() || mdlRegex != null
    }

    companion object {
        /** Combines several matching entries: for each field the first entry that sets it wins. */
        fun merge(all: List<Quirk>): Quirk? = all.takeIf { it.isNotEmpty() }?.reduce { a, b ->
            a.copy(
                id = "${a.id}+${b.id}",
                firmwareUpload = a.firmwareUpload ?: b.firmwareUpload,
                reclaimBetweenJobs = a.reclaimBetweenJobs || b.reclaimBetweenJobs,
                drainBulkIn = a.drainBulkIn || b.drainBulkIn,
                needsPjl = a.needsPjl || b.needsPjl,
                forceLanguage = a.forceLanguage ?: b.forceLanguage,
                mediaDefault = a.mediaDefault ?: b.mediaDefault,
                unsupported = a.unsupported ?: b.unsupported,
            )
        }
    }
}

class Quirks(val entries: List<Quirk>) {

    fun forPrinter(p: Printer): Quirk? = Quirk.merge(entries.filter { it.matches(PrinterIdentity.of(p), p.deviceId?.mdl) })

    companion object {
        val EMPTY = Quirks(emptyList())

        fun parse(json: String): Quirks {
            val arr = JSONObject(json).getJSONArray("quirks")
            return Quirks((0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val m = o.optJSONObject("match") ?: JSONObject()
                fun list(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
                fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && !o.isNull(k) }
                val q = Quirk(
                    id = o.getString("id"),
                    usbIds = list("usb").map { it.lowercase() }.toSet(),
                    mfg = list("mfg"),
                    mdlRegex = m.optString("mdl").takeIf { it.isNotEmpty() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
                    firmwareUpload = str("firmware_upload"),
                    reclaimBetweenJobs = o.optBoolean("reclaim_between_jobs"),
                    drainBulkIn = o.optBoolean("drain_bulk_in"),
                    needsPjl = o.optBoolean("needs_pjl"),
                    forceLanguage = str("force_language")?.let(Language::valueOf),
                    mediaDefault = str("media_default")?.let(PaperSize::valueOf),
                    unsupported = str("unsupported"),
                )
                require(q.usbIds.isNotEmpty() || q.mfg.isNotEmpty() || q.mdlRegex != null) { "Quirk ${q.id} matches nothing" }
                q
            })
        }
    }
}
