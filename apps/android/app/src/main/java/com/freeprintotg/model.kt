package com.freeprintotg

/** How bytes reach the printer. Mirrors NokoPrint's ipp:// pdl:// lpd:// usb:// ipp-usb:// URI schemes. */
enum class Transport { IPP, RAW_9100, LPD, USB_BULK, IPP_USB, BLE_BRIDGE }

/** A PrintBridge (ESP32-S3) reached over Bluetooth LE. USB ids are what the bridge reports about its printer. */
data class BleRef(val address: String, val vendorId: Int? = null, val productId: Int? = null)

/** Page description languages the app can produce, as IPP document-format MIME types. */
enum class Pdl(val mime: String) {
    PDF("application/pdf"),
    PWG_RASTER("image/pwg-raster"),
    JPEG("image/jpeg"),
    POSTSCRIPT("application/postscript"),
    PCL("application/vnd.hp-PCL"),
    PCLXL("application/vnd.hp-PCLXL"),
    URF("image/urf"),
}

/** IEEE-1284 device id (MFG / MDL / CMD), read from a USB printer or advertised over mDNS. */
data class DeviceId(val mfg: String?, val mdl: String?, val commands: List<String>, val raw: String = "") {
    companion object {
        fun parse(raw: String): DeviceId {
            val kv = raw.split(';').mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(0, i).trim().uppercase() to it.substring(i + 1).trim()
            }.toMap()
            val mfg = kv["MFG"] ?: kv["MFR"] ?: kv["MANUFACTURER"]
            val mdl = kv["MDL"] ?: kv["MODEL"]
            val cmd = (kv["CMD"] ?: kv["COMMAND SET"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return DeviceId(mfg, mdl, cmd, raw)
        }

        /**
         * Bonjour printing TXT keys `usb_MFG`, `usb_MDL`, `usb_CMD` (print servers, the ESP32 bridge, many printers).
         * Key case varies between advertisers, so lookup ignores it. Null when none is present.
         */
        fun fromTxt(txt: Map<String, String>): DeviceId? {
            val lower = txt.mapKeys { it.key.lowercase() }
            val mfg = lower["usb_mfg"]?.trim()?.ifEmpty { null }
            val mdl = lower["usb_mdl"]?.trim()?.ifEmpty { null }
            val cmd = lower["usb_cmd"]?.trim()?.ifEmpty { null }
            if (mfg == null && mdl == null && cmd == null) return null
            val raw = listOfNotNull(mfg?.let { "MFG:$it" }, mdl?.let { "MDL:$it" }, cmd?.let { "CMD:$it" }).joinToString(";", postfix = ";")
            return parse(raw)
        }
    }
}

data class UsbEndpointRef(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val interfaceIndex: Int,
    val outEndpointIndex: Int,
    val inEndpointIndex: Int?,
    val protocol: Int, // 1 unidirectional, 2 bidirectional, 4 IPP-over-USB
)

data class Printer(
    val id: String,
    val name: String,
    val transport: Transport,
    val host: String? = null,
    val port: Int = 0,
    val ippPath: String = "/ipp/print",
    val tls: Boolean = false,
    val usb: UsbEndpointRef? = null,
    val ble: BleRef? = null,
    val deviceId: DeviceId? = null,
    /** Document formats advertised by the printer (mDNS `pdl` TXT key or IPP document-format-supported). */
    val formats: Set<String> = emptySet(),
)
