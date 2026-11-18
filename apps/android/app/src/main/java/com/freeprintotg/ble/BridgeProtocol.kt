package com.freeprintotg.ble

import org.json.JSONObject
import java.util.UUID

/**
 * PrintBridge BLE protocol, the single source of truth shared with the ESP32 firmware (src/ble_bridge.c).
 * Spec: docs/BLE-APP-PLAN.md section 4. Pure Kotlin: no Android types, so it is unit-tested on the JVM.
 *
 * Control messages are `opcode u8` + payload, integers little-endian. Data carries raw job bytes and the app never
 * sends more than the credits the bridge granted.
 */
object BridgeProtocol {
    val SERVICE: UUID = UUID.fromString("9b7f0001-4f6e-4c1a-8d3b-5a2c7e1f0a10")
    val INFO: UUID = UUID.fromString("9b7f0002-4f6e-4c1a-8d3b-5a2c7e1f0a10")
    val CONTROL: UUID = UUID.fromString("9b7f0003-4f6e-4c1a-8d3b-5a2c7e1f0a10")
    val DATA: UUID = UUID.fromString("9b7f0004-4f6e-4c1a-8d3b-5a2c7e1f0a10")
    /** Standard Client Characteristic Configuration descriptor, written to enable notifications. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OPEN_JOB = 0x01
    const val END_JOB = 0x02
    const val ABORT = 0x03
    const val WIFI_SET = 0x04
    const val GET_INFO = 0x05
    const val CREDIT = 0x81
    const val DONE = 0x82
    const val ERROR = 0x83
    const val WIFI_STATE = 0x84
    const val STATUS = 0x85

    /** ATT header taken from every write: payload per Data write is MTU - 3. */
    const val ATT_OVERHEAD = 3

    fun openJob(jobId: Int, total: Long): ByteArray = byteArrayOf(OPEN_JOB.toByte()) + u16(jobId) + u32(total)
    fun endJob(jobId: Int): ByteArray = byteArrayOf(END_JOB.toByte()) + u16(jobId)
    fun abort(jobId: Int): ByteArray = byteArrayOf(ABORT.toByte()) + u16(jobId)
    fun getInfo(): ByteArray = byteArrayOf(GET_INFO.toByte())

    fun wifiSet(ssid: String, password: String): ByteArray {
        val s = ssid.toByteArray(Charsets.UTF_8)
        val p = password.toByteArray(Charsets.UTF_8)
        require(s.size in 1..32) { "Network name must be 1 to 32 bytes" }
        require(p.isEmpty() || p.size in 8..64) { "Password must be empty or 8 to 64 characters" }
        return byteArrayOf(WIFI_SET.toByte(), s.size.toByte()) + s + byteArrayOf(p.size.toByte()) + p
    }

    /** Splits [len] bytes from [off] into Data writes no bigger than the MTU allows. */
    fun chunks(data: ByteArray, off: Int, len: Int, mtu: Int): Sequence<ByteArray> {
        val size = (mtu - ATT_OVERHEAD).coerceAtLeast(20)
        return generateSequence(off) { it + size }.takeWhile { it < off + len }
            .map { data.copyOfRange(it, minOf(it + size, off + len)) }
    }

    /** Parses one Control notification; unknown or short messages become [Notice.Unknown]. */
    fun parse(b: ByteArray): Notice {
        if (b.isEmpty()) return Notice.Unknown(b)
        fun u16(at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        return when (b[0].toInt() and 0xFF) {
            CREDIT -> if (b.size >= 7) Notice.Credit(u16(1), u32(3)) else Notice.Unknown(b)
            DONE -> if (b.size >= 3) Notice.Done(u16(1)) else Notice.Unknown(b)
            ERROR -> if (b.size >= 4) Notice.Error(u16(1), BridgeError.of(b[3].toInt() and 0xFF)) else Notice.Unknown(b)
            WIFI_STATE -> if (b.size >= 6) Notice.WifiState(b[1].toInt() and 0xFF, (2..5).joinToString(".") { (b[it].toInt() and 0xFF).toString() }) else Notice.Unknown(b)
            STATUS -> if (b.size >= 2) Notice.Status(b[1].toInt() and 0xFF) else Notice.Unknown(b)
            else -> Notice.Unknown(b)
        }
    }

    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun u32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

sealed class Notice {
    data class Credit(val jobId: Int, val bytes: Long) : Notice()
    data class Done(val jobId: Int) : Notice()
    data class Error(val jobId: Int, val error: BridgeError) : Notice()
    /** [state]: 0 failed, 1 saved (the bridge restarts), 2 connected. */
    data class WifiState(val state: Int, val ip: String) : Notice()
    data class Status(val flags: Int) : Notice() {
        val paperEmpty get() = flags and 1 != 0
        val selected get() = flags and 2 != 0
        val noError get() = flags and 4 != 0
        val rawJobRunning get() = flags and 8 != 0
    }
    class Unknown(val bytes: ByteArray) : Notice()
}

enum class BridgeError(val code: Int, val message: String) {
    BUSY(1, "The printer is busy with a job from another device"),
    NO_PRINTER(2, "No printer is plugged into the bridge"),
    USB_ERROR(3, "The bridge could not send the data to the printer over USB"),
    TIMEOUT(4, "The bridge stopped the job: no data for 10 seconds"),
    OVERFLOW(5, "The bridge received more data than it allowed"),
    WIFI_FAIL(6, "The bridge could not save the Wi-Fi network"),
    NEEDS_PAIRING(7, "Pair with the bridge first (Bluetooth pairing)"),
    NOT_IN_SETUP(8, "Press the bridge's BOOT button briefly, then try again within 5 minutes"),
    UNKNOWN(0, "Unknown bridge error"),
    ;

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN }
}

/** The Info characteristic: who the printer behind the bridge is. */
data class BridgeInfo(
    val firmware: String,
    val deviceId: String,
    val vendorId: Int?,
    val productId: Int?,
    val usbManufacturer: String,
    val usbProduct: String,
    val ippUsb: Boolean,
    val state: String,
    val ip: String?,
) {
    val hasPrinter get() = state != "no_printer"

    companion object {
        fun parse(json: String): BridgeInfo {
            val o = JSONObject(json)
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k).takeIf { it > 0 } else null
            return BridgeInfo(
                firmware = o.optString("fw"),
                deviceId = o.optString("id"),
                vendorId = int("vid"),
                productId = int("pid"),
                usbManufacturer = o.optString("usb_mfr"),
                usbProduct = o.optString("usb_product"),
                ippUsb = o.optBoolean("ipp"),
                state = o.optString("state", "no_printer"),
                ip = o.optString("ip").takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * Flow control on the sending side: bytes may be written only while credit remains. The bridge grants credit as
 * its ring buffer drains to USB, so the phone never overruns it.
 */
class CreditWindow {
    var available: Long = 0
        private set

    @Synchronized fun grant(bytes: Long) { available += bytes }

    /** Takes up to [wanted] bytes of credit; 0 when none is left. */
    @Synchronized fun take(wanted: Int): Int {
        val n = minOf(wanted.toLong(), available).toInt()
        available -= n
        return n
    }
}
