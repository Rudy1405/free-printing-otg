package com.freeprintotg

import com.freeprintotg.render.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Byte-exact checks of the driverless encoders (G2 of docs/GENERIC-PRINTING-PLAN.md).
 * The test page is 10 x 2 pixels: row 0 = B W B W B W B W B B, row 1 all white.
 * Pure black/white never depends on the dither matrix, so the expected bits are fixed:
 * row 0 = 0xAA 0xC0 (1 = black), row 1 = 0x00 0x00.
 */
class RasterEncodersTest {
    private val B = 0xFF000000.toInt()
    private val W = 0xFFFFFFFF.toInt()
    private fun page() = ArrayRaster(10, 2, intArrayOf(B, W, B, W, B, W, B, W, B, B) + IntArray(10) { W })

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
    private fun assertBytes(expected: ByteArray, actual: ByteArray) = assertEquals(hex(expected), hex(actual))
    private fun contains(hay: ByteArray, needle: ByteArray) =
        (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }

    @Test fun monoRowPacksMsbFirst() {
        val px = IntArray(10); page().row(0, px)
        assertBytes(bytes(0xAA, 0xC0), RasterCodec.monoRow(px, 10, 0, 2))
        assertBytes(bytes(0x55, 0x3F), RasterCodec.monoRow(px, 10, 0, 2, inkIsZero = true))
    }

    @Test fun escPosTrimsBlankRowsFeedsAndCuts() {
        val one = bytes(
            0x1B, 0x40,                               // ESC @
            0x1D, 0x76, 0x30, 0x00, 0x02, 0x00, 0x01, 0x00, // GS v 0, 2 bytes x 1 row (blank row trimmed)
            0xAA, 0xC0,
            0x1B, 0x64, 0x04,                         // ESC d 4
            0x1D, 0x56, 0x42, 0x00,                   // GS V 66 0
        )
        assertBytes(one, RasterEncoders.escPos(sequenceOf(page())))
        assertBytes(one + one, RasterEncoders.escPos(sequenceOf(page()), copies = 2))
    }

    @Test fun tsplUsesZeroForInk() {
        val expected = ascii("SIZE 50 mm,25 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\nCLS\r\nBITMAP 0,0,2,2,0,") +
            bytes(0x55, 0x3F, 0xFF, 0xFF) + ascii("\r\nPRINT 1,3\r\n")
        assertBytes(expected, RasterEncoders.tspl(sequenceOf(page()), 50, 25, copies = 3))
    }

    @Test fun zplGraphicFieldIsHex() {
        assertEquals(
            "^XA^PW10^LL2^LH0,0^FO0,0^GFA,4,4,2,AAC00000^FS^PQ1^XZ\n",
            String(RasterEncoders.zpl(sequenceOf(page())), Charsets.US_ASCII),
        )
    }

    @Test fun packBitsRunsAndLiterals() {
        val out = ByteArrayOutputStream()
        RasterCodec.packBits(bytes(0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0, 0, 0) + ByteArray(10) { -1 } + bytes(0, 0), out)
        assertBytes(bytes(0x07, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0xFD, 0x00, 0xF7, 0xFF, 0xFF, 0x00), out.toByteArray())
    }

    @Test fun pclXlSessionPageAndImage() {
        val out = RasterEncoders.pclXl(sequenceOf(page()), PaperSize.A4, 300, copies = 2)
        val header = ascii("\u001B%-12345X@PJL SET RESOLUTION=300\n@PJL ENTER LANGUAGE = PCLXL\n) HP-PCL XL;1;1;Comment free-printing-otg") + bytes(0, 0x0A)
        val session = bytes(
            0xD1, 0x2C, 0x01, 0x2C, 0x01, 0xF8, 0x89,   // UnitsPerMeasure 300x300
            0xC0, 0x00, 0xF8, 0x86,                     // Measure eInch
            0xC0, 0x03, 0xF8, 0x8F,                     // ErrorReport
            0x41,                                       // BeginSession
            0xC0, 0x00, 0xF8, 0x88, 0xC0, 0x01, 0xF8, 0x82, 0x48, // OpenDataSource, low byte first
        )
        assertBytes(header + session, out.copyOfRange(0, header.size + session.size))
        // A4, gray, 8-bit direct image 10 x 2
        assertTrue(contains(out, bytes(0xC0, 0x02, 0xF8, 0x25, 0x43)))
        assertTrue(contains(out, bytes(0xC0, 0x00, 0xF8, 0x64, 0xC0, 0x02, 0xF8, 0x62, 0xC1, 0x0A, 0x00, 0xF8, 0x6C, 0xC1, 0x02, 0x00, 0xF8, 0x6B)))
        // one band: rows padded to 12 bytes, RLE compressed (same data as packBitsRunsAndLiterals)
        val block = bytes(0xC1, 0, 0, 0xF8, 0x6D, 0xC1, 2, 0, 0xF8, 0x63, 0xC0, 1, 0xF8, 0x65, 0xB1, 0xFA, 15, 0, 0, 0) +
            bytes(0x07, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0xFD, 0x00, 0xF7, 0xFF, 0xFF, 0x00)
        assertTrue(contains(out, block))
        assertTrue(contains(out, bytes(0xB2, 0xC1, 0x02, 0x00, 0xF8, 0x31, 0x44))) // EndImage, PageCopies 2, EndPage
        assertBytes(bytes(0x49, 0x42) + ascii("\u001B%-12345X"), out.copyOfRange(out.size - 11, out.size))
    }

    @Test fun urfHeaderAndRows() {
        val pageHeader = ByteArray(32).also {
            it[0] = 8; it[1] = 0; it[2] = 1; it[3] = 4
            it[15] = 10; it[19] = 2; it[22] = 0x01; it[23] = 0x2C
        }
        val expected = ascii("UNIRAST") + bytes(0, 0, 0, 0, 1) + pageHeader +
            bytes(0x00, 0xF9, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0, 0xFF, 0x01, 0x00) + // row 0: 8 literals, then run of 2 black
            bytes(0x00, 0x09, 0xFF)                                                // row 1: 10 white
        assertBytes(expected, RasterEncoders.urf(sequenceOf(page()), 300, color = false))
    }

    @Test fun pjlWrapsBody() {
        assertEquals(
            "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\nBODY\u001B%-12345X",
            String(RasterEncoders.pjl(ascii("BODY"), "PCL"), Charsets.US_ASCII),
        )
    }

    @Test fun mediaSizesInMillimetres() {
        assertEquals(50, PaperSize.LABEL_50X25.widthMm)
        assertEquals(25, PaperSize.LABEL_50X25.heightMm)
        // rendered width must fit the printable dots of each roll at 203 dpi (384 on 58 mm, 576 on 80 mm)
        assertTrue(PaperSize.ROLL_58.widthPt * 203 / 72 in 380..384)
        assertTrue(PaperSize.ROLL_80.widthPt * 203 / 72 in 572..576)
    }
}
