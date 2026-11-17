package com.freeprintotg

import com.freeprintotg.driver.*
import org.junit.Assert.*
import org.junit.Test

/** G1 of docs/GENERIC-PRINTING-PLAN.md: identity clean-up and the model-independent decision chain. */
class GenericResolverTest {
    private fun usb(raw: String?, vid: Int = 0x1234, pid: Int = 0x0001, name: String = "USB printer") = Printer(
        id = "usb:x", name = name, transport = Transport.USB_BULK,
        usb = UsbEndpointRef("/dev/bus/usb/001/002", vid, pid, 0, 0, null, 2),
        deviceId = raw?.let(DeviceId::parse),
    )

    private val manifest = DriverResolver.parse(
        """{"drivers":[{"id":"generic-pcl","name":"Generic PCL","language":"PCL_MONO",
           "match":{"cmd":["PCL","PCL5"],"mfg":["HP"]}}]}""",
    )

    // --- identity ---------------------------------------------------------------------------

    @Test fun vendorAliasesCollapse() {
        assertEquals("HP", Vendors.canonical("Hewlett-Packard"))
        assertEquals("HP", Vendors.canonical(" hp "))
        assertEquals("Epson", Vendors.canonical("SEIKO EPSON"))
        assertEquals("Brother", Vendors.canonical("Brother Industries, Ltd."))
        assertNull(Vendors.canonical("Acme"))
    }

    @Test fun vendorFromUsbIdAndName() {
        assertEquals("Epson", Vendors.fromVid(0x04B8))
        assertEquals("TSC", Vendors.fromVid(0x1203))
        assertEquals("Fuji Xerox", Vendors.fromName("Fuji Xerox DocuPrint P115"))
        assertEquals("HP", Vendors.fromName("HP LaserJet 1020"))
        assertNull(Vendors.fromName("Printer 192.168.1.5 (IPP)"))
        assertNull(Vendors.fromName("USB printer 03f0:0517"))
    }

    @Test fun modelLosesVendorAndSeries() {
        assertEquals("LaserJet 1020", Models.normalize("HP", "Hewlett-Packard LaserJet 1020 Series"))
        assertEquals("XP-420", Models.normalize("Epson", "EPSON XP-420 Series"))
        assertEquals("XP-420", Models.normalize(null, "XP-420 series"))
    }

    @Test fun languageSpellingsBecomeFamilies() {
        val f = Languages.families(listOf("PJL", "PS3", "PCL6", "BR-Script", "ESCPR1", "ESC/POS", "TSPL2", "CAPT"))
        assertEquals(
            setOf(PdlFamily.POSTSCRIPT, PdlFamily.PCLXL, PdlFamily.ESCPR, PdlFamily.ESCPOS, PdlFamily.TSPL, PdlFamily.CLOSED_HOST_BASED),
            f,
        )
    }

    @Test fun identityWithoutDeviceIdUsesUsbIds() {
        val who = PrinterIdentity.of(usb(null, vid = 0x04B8, pid = 0x0005))
        assertEquals("Epson", who.vendor)
        assertNull(who.model) // "USB printer" is a placeholder, not a model
        assertEquals("usb:04b8:0005", who.key)
    }

    // --- decision chain ---------------------------------------------------------------------

    @Test fun manifestMatchesAliasedVendor() {
        val p = usb("MFG:Hewlett-Packard;MDL:LaserJet 1022;CMD:PJL,PCL;")
        val plan = DriverResolver(manifest).resolve(p, emptySet())
        assertEquals("generic-pcl", plan.driver?.id)
    }

    @Test fun savedChoiceWins() {
        val p = usb("MFG:Acme;MDL:X;CMD:PCL;", vid = 0x1111, pid = 0x2222)
        val plan = DriverResolver(emptyList(), mapOf("usb:1111:2222" to Language.POSTSCRIPT)).resolve(p, setOf("application/pdf"))
        assertEquals(Language.POSTSCRIPT, plan.language)
    }

    @Test fun driverlessStillBeatsDeviceId() {
        val p = usb("MFG:HP;MDL:X;CMD:PCL;")
        assertEquals(Language.PWG_RASTER, DriverResolver(manifest).resolve(p, setOf("image/pwg-raster")).language)
    }

    @Test fun postScriptSpelledAnyWay() {
        val p = usb("MFG:Brother;MDL:HL-L5100DN;CMD:PJL,PCL,PCLXL,BR-Script3;")
        assertEquals(Language.POSTSCRIPT, DriverResolver(emptyList()).resolve(p, emptySet()).language)
    }

    @Test fun labelAndReceiptPrintersGetTheirOwnLanguage() {
        val label = DriverResolver(emptyList()).resolve(usb("MFG:TSC;MDL:TTP-244 Pro;CMD:TSPL2;"), emptySet())
        assertEquals(Language.TSPL, label.language)
        assertNull(label.blocker)
        assertFalse(label.fallback)
        assertEquals(Language.ZPL, DriverResolver(emptyList()).resolve(usb("MFG:Zebra;MDL:ZD220;CMD:ZPL;"), emptySet()).language)
        // no Device ID at all: the USB vendor id of a receipt maker is enough
        assertEquals(Language.ESC_POS, DriverResolver(emptyList()).resolve(usb(null, vid = 0x0519), emptySet()).language)
    }

    @Test fun eplOnlyIsRefused() {
        assertNotNull(DriverResolver(emptyList()).resolve(usb("MFG:Acme;MDL:LP;CMD:EPL2;"), emptySet()).blocker)
    }

    @Test fun pcl6OnlyGetsPclXlButPcl5StillWins() {
        val r = DriverResolver(emptyList())
        assertEquals(Language.PCL_XL, r.resolve(usb("MFG:Acme;MDL:L;CMD:PJL,PCLXL;"), emptySet()).language)
        assertEquals(Language.PCL_MONO, r.resolve(usb("MFG:Acme;MDL:L;CMD:PJL,PCL,PCLXL;"), emptySet()).language)
    }

    @Test fun airPrintOnlyUrf() {
        val p = Printer("ipp:x", "x", Transport.IPP)
        assertEquals(Language.URF, DriverResolver(emptyList()).resolve(p, setOf("image/urf", "image/jpeg")).language)
    }

    @Test fun closedHostBasedIsRefused() {
        val plan = DriverResolver(emptyList()).resolve(usb("MFG:Canon;MDL:LBP2900;CMD:CAPT;"), emptySet())
        assertNotNull(plan.blocker)
        assertFalse(plan.fallback) // no generic engine either
    }

    @Test fun epsonWithoutDeviceIdNeedsAnEngine() {
        val plan = DriverResolver(emptyList()).resolve(usb(null, vid = 0x04B8), emptySet())
        assertTrue(plan.fallback) // the engine catalogue may supply ESC/P-R
        assertNotNull(plan.blocker) // but raw PCL must never reach an Epson inkjet
    }

    @Test fun knownLaserVendorGuessesPcl() {
        val plan = DriverResolver(emptyList()).resolve(usb(null, vid = 0x04F9), emptySet())
        assertEquals(Language.PCL_MONO, plan.language)
        assertNull(plan.blocker)
        assertTrue(plan.note.contains("Brother"))
    }

    @Test fun unknownStaysGenericPcl() {
        val plan = DriverResolver(emptyList()).resolve(usb(null), emptySet())
        assertEquals(Language.PCL_MONO, plan.language)
        assertTrue(plan.fallback)
        assertNull(plan.blocker)
    }

    // --- engine catalogue ----------------------------------------------------------------------

    private fun entry(engine: String, model: String?, mfg: String? = null, mdl: String? = null, generic: Boolean = false, cmd: List<String> = emptyList()) =
        CatalogueEntry(engine, "$engine-$model.ppd", "00", mfg, model, mfg, mdl, cmd, generic, null)

    private val catalogue = Catalogue(
        "", emptyMap(),
        listOf(
            entry("hplip", "HP LaserJet 1022", mfg = "HP", mdl = "LaserJet 1022"),
            entry("escpr", "Generic ESC/P-R", generic = true),
        ),
    )

    @Test fun catalogueMatchesNormalisedName() {
        val p = usb("MFG:Hewlett-Packard;MDL:Hewlett-Packard LaserJet 1022 Series;CMD:PJL;")
        val m = catalogue.match(p.deviceId, p.name, PrinterIdentity.of(p))!!
        assertEquals("hplip", m.entry.engine)
        assertEquals(Catalogue.Quality.DEVICE_ID, m.quality)
    }

    @Test fun catalogueGivesEpsonWithoutDeviceIdTheGenericEscpr() {
        val p = usb(null, vid = 0x04B8)
        val m = catalogue.match(p.deviceId, p.name, PrinterIdentity.of(p))!!
        assertEquals("escpr", m.entry.engine)
        assertEquals(Catalogue.Quality.GENERIC, m.quality)
    }

    // --- mDNS TXT ------------------------------------------------------------------------------

    @Test fun deviceIdFromBonjourTxt() {
        val id = DeviceId.fromTxt(mapOf("ty" to "Bridge", "USB_MFG" to "EPSON", "usb_MDL" to "L3150 Series", "usb_CMD" to "ESCPL2,BDC,D4,ESCPR1"))!!
        assertEquals("EPSON", id.mfg)
        assertEquals("L3150 Series", id.mdl)
        assertEquals(listOf("ESCPL2", "BDC", "D4", "ESCPR1"), id.commands)
        assertNull(DeviceId.fromTxt(mapOf("ty" to "x", "pdl" to "application/pdf")))
    }
}
