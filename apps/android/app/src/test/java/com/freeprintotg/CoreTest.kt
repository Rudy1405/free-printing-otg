package com.freeprintotg

import com.freeprintotg.driver.*
import com.freeprintotg.ipp.Ipp
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun parsesIeee1284() {
        val id = DeviceId.parse("MFG:HP;CMD:PJL,PCL,PCLXL,POSTSCRIPT;MDL:LaserJet 1020;CLS:PRINTER;")
        assertEquals("HP", id.mfg)
        assertEquals("LaserJet 1020", id.mdl)
        assertEquals(listOf("PJL", "PCL", "PCLXL", "POSTSCRIPT"), id.commands)
    }

    @Test fun ippRoundTrip() {
        val req = Ipp.Request(Ipp.OP_GET_PRINTER_ATTRIBUTES).uri("printer-uri", "ipp://x/ipp/print").build()
        assertEquals(0x0B, req[3].toInt())
        // response: successful-ok, printer group, one mimeMediaType value "application/pdf"
        val name = "document-format-supported".toByteArray()
        val value = "application/pdf".toByteArray()
        val resp = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 1, 0x04, 0x49, 0, name.size.toByte()) + name +
            byteArrayOf(0, value.size.toByte()) + value + byteArrayOf(0x03)
        val parsed = Ipp.parse(resp)
        assertTrue(parsed.ok)
        assertEquals(listOf("application/pdf"), parsed.attributes["document-format-supported"])
    }

    @Test fun prefersDriverlessPdf() {
        val r = DriverResolver(emptyList())
        val p = Printer("x", "x", Transport.IPP)
        assertEquals(Language.PDF, r.resolve(p, setOf("application/pdf", "image/pwg-raster")).language)
        assertEquals(Language.PWG_RASTER, r.resolve(p, setOf("image/pwg-raster")).language)
    }

    @Test fun usbUsesCmdThenFallsBackToPcl() {
        val r = DriverResolver(emptyList())
        val ps = Printer("u", "u", Transport.USB_BULK, deviceId = DeviceId("X", "Y", listOf("POSTSCRIPT")))
        assertEquals(Language.POSTSCRIPT, r.resolve(ps, emptySet()).language)
        val unknown = Printer("u", "u", Transport.USB_BULK)
        assertEquals(Language.PCL_MONO, r.resolve(unknown, emptySet()).language)
    }
}
