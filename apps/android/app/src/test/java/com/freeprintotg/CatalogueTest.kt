package com.freeprintotg

import com.freeprintotg.driver.Catalogue
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Runs against dist/catalogue.json from tools/pack/build_dist.py; skipped when dist/ has not been built. */
class CatalogueTest {
    private val file = File("../../../dist/catalogue.json")

    private fun cat(): Catalogue { assumeTrue(file.exists()); return Catalogue.parse(file.readText()) }

    @Test fun epsonModelUsesEscpr() {
        val m = cat().match(DeviceId.parse("MFG:EPSON;CMD:ESCPR1;MDL:XP-420 Series;"), "EPSON XP-420")!!
        assertEquals("escpr", m.entry.engine)
        assertEquals(Catalogue.Quality.DEVICE_ID, m.quality)
    }

    @Test fun samsungLaserUsesSplix() {
        val m = cat().match(DeviceId.parse("MFG:Samsung;CMD:SPL;MDL:ML-2160 Series;"), "Samsung ML-2160")!!
        assertEquals("splix", m.entry.engine)
    }

    @Test fun unknownEpsonFallsBackToGenericEscpr() {
        val m = cat().match(DeviceId.parse("MFG:EPSON;CMD:ESCPR1;MDL:ZZ-9999;"), "ZZ-9999")!!
        assertTrue(m.entry.generic)
        assertEquals("escpr", m.entry.engine)
    }

    @Test fun unknownPclColorGetsGenericHplip() {
        val m = cat().match(DeviceId.parse("MFG:Acme;CMD:PCL,PCLXL;MDL:Foo;"), "Foo")!!
        assertTrue(m.entry.generic)
        assertEquals("hplip", m.entry.engine)
    }

    @Test fun everyPackHasAnArmBuild() {
        for (e in cat().engines.values) assertTrue(e.id, "arm64-v8a" in e.abis)
    }
}
