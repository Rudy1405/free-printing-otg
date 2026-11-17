package com.freeprintotg.transport

import android.content.Context
import android.hardware.usb.UsbManager
import com.freeprintotg.Printer
import com.freeprintotg.discovery.UsbDiscovery
import kotlinx.coroutines.delay
import java.io.File

/**
 * Quirk `firmware_upload`: host-based HP LaserJets (1000/1005/1018/1020, P1005/P1006/P1505...) forget their
 * firmware at every power-off and print nothing until it is sent again over USB, as foo2zjs does.
 *
 * The firmware is HP's, so the app does not ship it: the user copies it once to [dir] (reachable from a PC over USB).
 * "Already loaded" is read from the Device ID: a loaded printer reports an `FWVER` key (foo2zjs convention,
 * not yet confirmed on hardware). The ESP32 bridge does the same on its side (firmware plan H7).
 */
object FirmwareUpload {
    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "firmware")

    /** Returns the printer as seen after the upload (it usually reconnects with a new USB device name). */
    suspend fun ensure(ctx: Context, usb: UsbManager, printer: Printer, file: String, onStatus: (String) -> Unit): Printer {
        val ref = printer.usb ?: return printer
        val id = printer.deviceId
        if (id == null) {
            onStatus("Cannot tell whether ${printer.name} has its firmware loaded; not sending it")
            return printer
        }
        if (id.raw.contains("FWVER", ignoreCase = true)) return printer

        val fw = File(dir(ctx), file)
        if (!fw.isFile) error("${printer.name} needs its firmware ($file) after every power-on. Copy $file to ${fw.parent} and print again.")
        onStatus("Sending printer firmware $file…")
        UsbBulkSink(usb, ref).use { it.write(fw.readBytes()) }
        delay(FIRMWARE_SETTLE_MS)

        val dev = usb.deviceList.values.firstOrNull { it.vendorId == ref.vendorId && it.productId == ref.productId }
            ?: error("${printer.name} did not come back after the firmware upload")
        if (!UsbPermission.ensure(ctx, usb, dev)) error("USB permission denied")
        return UsbDiscovery.printersOf(usb, dev).firstOrNull { it.usb?.interfaceIndex == ref.interfaceIndex } ?: printer
    }

    private const val FIRMWARE_SETTLE_MS = 5_000L
}
