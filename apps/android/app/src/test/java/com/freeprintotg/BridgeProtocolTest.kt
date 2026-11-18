package com.freeprintotg

import com.freeprintotg.ble.*
import org.junit.Assert.*
import org.junit.Test

/** The BLE protocol shared with the ESP32 firmware (docs/BLE-APP-PLAN.md section 4). Same byte vectors as ble_bridge.c. */
class BridgeProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test fun controlMessagesAreLittleEndian() {
        assertEquals("01 34 12 00 00 01 00", hex(BridgeProtocol.openJob(0x1234, 65536)))
        assertEquals("02 34 12", hex(BridgeProtocol.endJob(0x1234)))
        assertEquals("03 01 00", hex(BridgeProtocol.abort(1)))
        assertEquals("05", hex(BridgeProtocol.getInfo()))
    }

    @Test fun wifiSetCarriesLengths() {
        assertEquals("04 04 48 6F 6D 65 08 70 61 73 73 77 6F 72 64", hex(BridgeProtocol.wifiSet("Home", "password")))
        assertEquals("04 02 4F 70 00", hex(BridgeProtocol.wifiSet("Op", "")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortWifiPasswordIsRejected() { BridgeProtocol.wifiSet("Home", "short") }

    @Test fun notificationsDecode() {
        assertEquals(Notice.Credit(7, 32768), BridgeProtocol.parse(byteArrayOf(0x81.toByte(), 7, 0, 0, 0x80.toByte(), 0, 0)))
        assertEquals(Notice.Done(7), BridgeProtocol.parse(byteArrayOf(0x82.toByte(), 7, 0)))
        assertEquals(Notice.Error(7, BridgeError.BUSY), BridgeProtocol.parse(byteArrayOf(0x83.toByte(), 7, 0, 1)))
        assertEquals(Notice.Error(0, BridgeError.NEEDS_PAIRING), BridgeProtocol.parse(byteArrayOf(0x83.toByte(), 0, 0, 7)))
        assertEquals(Notice.WifiState(2, "192.168.1.40"), BridgeProtocol.parse(byteArrayOf(0x84.toByte(), 2, 192.toByte(), 168.toByte(), 1, 40)))
        val st = BridgeProtocol.parse(byteArrayOf(0x85.toByte(), 0b0110)) as Notice.Status
        assertFalse(st.paperEmpty); assertTrue(st.selected); assertTrue(st.noError)
        assertTrue(BridgeProtocol.parse(byteArrayOf(0x81.toByte(), 1)) is Notice.Unknown) // truncated
    }

    @Test fun chunksRespectMtu() {
        val data = ByteArray(1000) { it.toByte() }
        val parts = BridgeProtocol.chunks(data, 0, 1000, mtu = 247).toList()
        assertEquals(listOf(244, 244, 244, 244, 24), parts.map { it.size })
        assertArrayEquals(data, parts.reduce { a, b -> a + b })
        assertEquals(listOf(10), BridgeProtocol.chunks(data, 5, 10, mtu = 247).map { it.size }.toList())
    }

    @Test fun creditNeverGoesNegative() {
        val w = CreditWindow()
        assertEquals(0, w.take(100))
        w.grant(150)
        assertEquals(100, w.take(100))
        assertEquals(50, w.take(100))
        assertEquals(0, w.available)
    }

    @Test fun infoParses() {
        val i = BridgeInfo.parse(
            """{"v":1,"fw":"0.2.0","id":"MFG:EPSON;MDL:L3150;CMD:ESCPL2,ESCPR1;","vid":1208,"pid":4352,
               "usb_mfr":"EPSON","usb_product":"L3150 Series","protos":[2],"ipp":false,"state":"ready","ip":"192.168.1.40","mtu":247}""",
        )
        assertEquals(0x04B8, i.vendorId)
        assertEquals("192.168.1.40", i.ip)
        assertTrue(i.hasPrinter)
        val none = BridgeInfo.parse("""{"v":1,"fw":"0.2.0","id":"","vid":0,"pid":0,"state":"no_printer","ip":""}""")
        assertFalse(none.hasPrinter)
        assertNull(none.vendorId)
        assertNull(none.ip)
    }
}
