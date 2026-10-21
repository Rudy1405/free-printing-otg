package com.freeprintotg.discovery

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.freeprintotg.*

/**
 * Finds printers on the OTG port, following the rules NokoPrint applies in discovery/o.java:
 * interface class 7 (printer), protocol 1/2 = classic bulk, protocol 4 = IPP-over-USB,
 * then read the IEEE-1284 device id with a class GET_DEVICE_ID control request.
 */
object UsbDiscovery {
    private const val PRINTER_CLASS = 7
    private const val REQ_GET_DEVICE_ID = 0

    fun scan(usb: UsbManager): List<Printer> =
        usb.deviceList.values.flatMap { dev -> printersOf(usb, dev) }

    fun printersOf(usb: UsbManager, dev: UsbDevice): List<Printer> {
        val out = mutableListOf<Printer>()
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            if (itf.interfaceClass != PRINTER_CLASS) continue
            val proto = itf.interfaceProtocol
            if (proto != 1 && proto != 2 && proto != 4) continue
            var outEp = -1
            var inEp = -1
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp < 0) outEp = e
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp < 0) inEp = e
            }
            if (outEp < 0) continue
            val id = if (usb.hasPermission(dev)) readDeviceId(usb, dev, itf.id, itf.alternateSetting) else null
            val name = listOfNotNull(id?.mfg, id?.mdl).joinToString(" ").ifBlank {
                dev.productName ?: "USB printer %04x:%04x".format(dev.vendorId, dev.productId)
            }
            out += Printer(
                id = "usb:${dev.vendorId}_${dev.productId}_$i",
                name = name,
                transport = if (proto == 4) Transport.IPP_USB else Transport.USB_BULK,
                usb = UsbEndpointRef(dev.deviceName, dev.vendorId, dev.productId, i, outEp, inEp.takeIf { it >= 0 }, proto),
                deviceId = id,
            )
        }
        return out
    }

    /** GET_DEVICE_ID: bmRequestType 0xA1, bRequest 0, wIndex = (interface << 8) | alt. First 2 bytes are a big-endian length. */
    private fun readDeviceId(usb: UsbManager, dev: UsbDevice, itf: Int, alt: Int): DeviceId? {
        val conn = usb.openDevice(dev) ?: return null
        try {
            val buf = ByteArray(1024)
            val n = conn.controlTransfer(0xA1, REQ_GET_DEVICE_ID, 0, (itf shl 8) or alt, buf, buf.size, 3000)
            if (n < 3) return null
            val declared = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val len = if (declared in 2..n) declared else n
            return DeviceId.parse(String(buf, 2, len - 2, Charsets.ISO_8859_1))
        } finally {
            conn.close()
        }
    }
}
