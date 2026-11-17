package com.freeprintotg

import com.freeprintotg.driver.*
import com.freeprintotg.render.PaperSize
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** G4 of docs/GENERIC-PRINTING-PLAN.md: the setup assistant's choices and what it remembers. */
class LanguageProbeTest {
    private fun usb(raw: String?, vid: Int = 0x1234, pid: Int = 0x0001) = Printer(
        id = "usb:x", name = "USB printer", transport = Transport.USB_BULK,
        usb = UsbEndpointRef("/dev/bus/usb/001/002", vid, pid, 0, 0, null, 2),
        deviceId = raw?.let(DeviceId::parse),
    )

    @Test fun unknownPrinterTriesOfficeLanguagesFirst() {
        val c = LanguageProbe.candidates(PrinterIdentity.of(usb(null)))
        assertEquals(
            listOf(Language.PCL_MONO, Language.POSTSCRIPT, Language.PCL_XL, Language.PDF, Language.ESC_POS, Language.TSPL, Language.ZPL),
            c,
        )
    }

    @Test fun declaredLanguagesLeadWithoutDuplicates() {
        val c = LanguageProbe.candidates(PrinterIdentity.of(usb("MFG:Acme;MDL:X;CMD:PJL,PCLXL,POSTSCRIPT;")))
        assertEquals(listOf(Language.POSTSCRIPT, Language.PCL_XL), c.take(2))
        assertEquals(c.size, c.toSet().size)
    }

    @Test fun labelMakersTryLabelLanguagesBeforeWastingSheets() {
        val c = LanguageProbe.candidates(PrinterIdentity.of(usb(null, vid = 0x1203))) // TSC
        assertEquals(listOf(Language.ESC_POS, Language.TSPL, Language.ZPL), c.take(3))
    }

    @Test fun onlyRawLinksNeedTheAssistant() {
        assertTrue(LanguageProbe.applies(usb(null)))
        assertTrue(LanguageProbe.applies(Printer("raw:h:9100", "h", Transport.RAW_9100, host = "h", port = 9100)))
        assertFalse(LanguageProbe.applies(Printer("ipp:h:631", "h", Transport.IPP, host = "h", port = 631)))
    }

    @Test fun testMediaFollowsLanguage() {
        assertEquals(PaperSize.ROLL_58, LanguageProbe.testPaper(Language.ESC_POS, PaperSize.LETTER))
        assertEquals(PaperSize.LABEL_50X25, LanguageProbe.testPaper(Language.ZPL, PaperSize.LETTER))
        assertEquals(PaperSize.LETTER, LanguageProbe.testPaper(Language.PCL_XL, PaperSize.LETTER))
        assertEquals(listOf("Test 2/7", "PCL 6", "Readable? Choose it in the app"), LanguageProbe.testText(Language.PCL_XL, 2, 7))
    }

    @Test fun networkPrinterWithoutIdentityStillHasAKey() {
        val p = Printer("raw:10.0.0.9:9100", "Printer 10.0.0.9 (raw 9100)", Transport.RAW_9100, host = "10.0.0.9", port = 9100)
        assertEquals("net:10.0.0.9:9100", PrinterIdentity.of(p).key)
        // what the assistant saved is used on the next print, ahead of the generic PCL guess
        val plan = DriverResolver(emptyList(), learned = mapOf("net:10.0.0.9:9100" to Language.ESC_POS)).resolve(p, emptySet())
        assertEquals(Language.ESC_POS, plan.language)
    }

    @Test fun sharedSettingCarriesWhatTheCatalogueNeeds() {
        val e = LearnedEntry.of(usb("MFG:Acme;MDL:Thing 9;CMD:PJL;", vid = 0x0ABC, pid = 0x0102), Language.PCL_XL)!!
        val o = JSONObject(e.toJson())
        assertEquals("usb:0abc:0102", o.getString("key"))
        assertEquals("PCL_XL", o.getString("language"))
        assertEquals("Thing 9", o.getString("model"))
        assertEquals("MFG:Acme;MDL:Thing 9;CMD:PJL;", o.getString("device_id"))
        assertFalse(o.has("vendor")) // "Acme" is not a known maker
    }
}
