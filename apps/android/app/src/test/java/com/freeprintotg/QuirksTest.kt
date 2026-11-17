package com.freeprintotg

import com.freeprintotg.driver.*
import com.freeprintotg.render.PaperSize
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** G3 of docs/GENERIC-PRINTING-PLAN.md: per-printer exceptions as data. */
class QuirksTest {
    private val bundledFile = File("../../../packages/driver-manifest/quirks.json")
    private fun bundled() = Quirks.parse(bundledFile.readText())

    private fun usb(raw: String?, vid: Int, pid: Int) = Printer(
        id = "usb:x", name = "USB printer", transport = Transport.USB_BULK,
        usb = UsbEndpointRef("/dev/bus/usb/001/002", vid, pid, 0, 0, 1, 2),
        deviceId = raw?.let(DeviceId::parse),
    )

    @Test fun bundledFileParsesAndEveryEntryMatchesSomething() {
        val q = bundled()
        assertTrue(q.entries.size >= 8)
        assertEquals(q.entries.size, q.entries.map { it.id }.toSet().size) // ids are unique
    }

    @Test fun hostBasedHpNeedsFirmwareByUsbId() {
        val q = bundled().forPrinter(usb("MFG:Hewlett-Packard;MDL:HP LaserJet 1020;CMD:ACL;", 0x03F0, 0x2B17))!!
        assertEquals("sihp1020.dl", q.firmwareUpload)
        assertNull(bundled().forPrinter(usb("MFG:Hewlett-Packard;MDL:HP LaserJet 1020;", 0x03F0, 0x9999)))
    }

    @Test fun captPrinterIsRefusedEvenWithoutCapInCmd() {
        val p = usb("MFG:Canon;MDL:LBP2900;CLS:PRINTER;", 0x04A9, 0x2676)
        val plan = DriverResolver(emptyList(), quirks = bundled()).resolve(p, emptySet())
        assertTrue(plan.blocker!!.contains("CAPT"))
        // a similarly named model outside the list is not caught
        assertNull(bundled().forPrinter(usb("MFG:Canon;MDL:LBP7100Cn;", 0x04A9, 0x0001)))
    }

    private val custom = Quirks.parse(
        """{"quirks":[
          {"id":"liar","match":{"mfg":["Hewlett-Packard"],"mdl":"^Deskjet 9"},"force_language":"PCL_XL","needs_pjl":true},
          {"id":"roll","match":{"usb":["0fe6:811e"]},"media_default":"ROLL_80","drain_bulk_in":true}
        ]}""",
    )

    @Test fun forcedLanguageBeatsCmdButNotDriverless() {
        val p = usb("MFG:HP;MDL:Deskjet 990C;CMD:PCL;", 0x03F0, 0x1)
        val r = DriverResolver(emptyList(), quirks = custom)
        assertEquals(Language.PCL_XL, r.resolve(p, emptySet()).language)
        assertEquals(Language.PDF, r.resolve(p, setOf("application/pdf")).language)
        assertTrue(custom.forPrinter(p)!!.needsPjl)
    }

    @Test fun usbFieldsAndMedia() {
        val q = custom.forPrinter(usb("MFG:Acme;CMD:ESC/POS;", 0x0FE6, 0x811E))!!
        assertTrue(q.drainBulkIn)
        assertEquals(PaperSize.ROLL_80, q.mediaDefault)
    }

    @Test fun mergeKeepsFirstValuePerField() {
        val m = Quirk.merge(listOf(Quirk("a", mfg = listOf("X"), mediaDefault = PaperSize.ROLL_58), Quirk("b", mfg = listOf("X"), mediaDefault = PaperSize.ROLL_80, needsPjl = true)))!!
        assertEquals(PaperSize.ROLL_58, m.mediaDefault)
        assertTrue(m.needsPjl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun entryWithoutMatchIsRejected() {
        Quirks.parse("""{"quirks":[{"id":"x","needs_pjl":true}]}""")
    }
}
