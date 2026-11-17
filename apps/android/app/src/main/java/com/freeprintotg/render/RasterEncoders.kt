package com.freeprintotg.render

import java.io.ByteArrayOutputStream

/**
 * Printer languages that need no driver pack: they take a rendered page and produce the exact bytes the printer
 * expects. Pure Kotlin (see [Raster]) so every encoder is covered by byte-exact JVM tests.
 * Plan: docs/GENERIC-PRINTING-PLAN.md section 4 (G2).
 */
object RasterEncoders {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val UEL = "\u001B%-12345X"

    // ---------------------------------------------------------------- receipts: ESC/POS

    /**
     * ESC/POS raster (`GS v 0`), 1 bit per dot, 1 = black. Trailing white rows are dropped, then the paper is fed
     * and cut (`GS V 66 0`). Bands of [band] rows keep within small printer buffers.
     */
    fun escPos(pages: Sequence<Raster>, copies: Int = 1, band: Int = 128): ByteArray {
        val job = ByteArrayOutputStream()
        job.write(ESC); job.write('@'.code)
        for (r in pages) {
            val h = RasterCodec.contentHeight(r)
            val bytes = (r.width + 7) / 8
            val px = IntArray(r.width)
            var y0 = 0
            while (y0 < h) {
                val rows = minOf(band, h - y0)
                job.write(GS); job.write('v'.code); job.write('0'.code); job.write(0)
                job.write(bytes and 0xFF); job.write(bytes shr 8)
                job.write(rows and 0xFF); job.write(rows shr 8)
                for (y in y0 until y0 + rows) { r.row(y, px); job.write(RasterCodec.monoRow(px, r.width, y, bytes)) }
                y0 += rows
            }
            job.write(ESC); job.write('d'.code); job.write(4)             // feed 4 lines past the cutter
            job.write(GS); job.write('V'.code); job.write(66); job.write(0) // feed and partial cut
        }
        val one = job.toByteArray()
        return ByteArrayOutputStream().apply { repeat(copies.coerceAtLeast(1)) { write(one) } }.toByteArray()
    }

    // ---------------------------------------------------------------- labels: TSPL (TSC and clones), ZPL (Zebra)

    /** TSPL label: `BITMAP` mode 0 where a 0 bit prints a dot (TSPL's convention), one label per page. */
    fun tspl(pages: Sequence<Raster>, widthMm: Int, heightMm: Int, copies: Int = 1, gapMm: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        fun s(t: String) = out.write(t.toByteArray(Charsets.US_ASCII))
        s("SIZE $widthMm mm,$heightMm mm\r\nGAP $gapMm mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\n")
        for (r in pages) {
            val bytes = (r.width + 7) / 8
            val px = IntArray(r.width)
            s("CLS\r\nBITMAP 0,0,$bytes,${r.height},0,")
            for (y in 0 until r.height) { r.row(y, px); out.write(RasterCodec.monoRow(px, r.width, y, bytes, inkIsZero = true)) }
            s("\r\nPRINT 1,${copies.coerceAtLeast(1)}\r\n")
        }
        return out.toByteArray()
    }

    /** ZPL II label: `^GFA` ASCII-hex graphic field, 1 = black, one `^XA..^XZ` format per page. */
    fun zpl(pages: Sequence<Raster>, copies: Int = 1): ByteArray {
        val out = StringBuilder()
        for (r in pages) {
            val bytes = (r.width + 7) / 8
            val total = bytes * r.height
            val px = IntArray(r.width)
            out.append("^XA^PW").append(r.width).append("^LL").append(r.height).append("^LH0,0^FO0,0")
            out.append("^GFA,").append(total).append(',').append(total).append(',').append(bytes).append(',')
            for (y in 0 until r.height) {
                r.row(y, px)
                for (b in RasterCodec.monoRow(px, r.width, y, bytes)) out.append(HEX[b.toInt() shr 4 and 0xF]).append(HEX[b.toInt() and 0xF])
            }
            out.append("^FS^PQ").append(copies.coerceAtLeast(1)).append("^XZ\n")
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    // ---------------------------------------------------------------- lasers: PCL XL (PCL 6)

    /**
     * PCL XL class 1.1 stream: one 8-bit gray image per page, RLE (PackBits) compressed in bands, rows padded to
     * 4 bytes. Operator, attribute and enum codes from Ghostscript's gdevpxop.h / gdevpxat.h / gdevpxen.h;
     * session layout from gdevpxut.c.
     */
    fun pclXl(pages: Sequence<Raster>, paper: PaperSize, dpi: Int, copies: Int = 1, band: Int = 64): ByteArray {
        val x = Pxl()
        x.ascii("$UEL@PJL SET RESOLUTION=$dpi\n@PJL ENTER LANGUAGE = PCLXL\n) HP-PCL XL;1;1;Comment free-printing-otg\u0000\n")
        x.uint16xy(dpi, dpi); x.attr(0x89)        // UnitsPerMeasure
        x.ubyte(0); x.attr(0x86)                  // Measure = eInch
        x.ubyte(3); x.attr(0x8F)                  // ErrorReport = eBackChAndErrPage
        x.op(0x41)                                // BeginSession
        x.ubyte(0); x.attr(0x88)                  // SourceType = eDefaultDataSource
        x.ubyte(1); x.attr(0x82)                  // DataOrg = eBinaryLowByteFirst
        x.op(0x48)                                // OpenDataSource
        val media = when (paper) { PaperSize.LETTER -> 0; else -> 2 }  // eLetterPaper / eA4Paper
        for (r in pages) {
            x.ubyte(0); x.attr(0x28)              // Orientation = ePortraitOrientation
            x.ubyte(media); x.attr(0x25)          // MediaSize
            x.op(0x43)                            // BeginPage
            x.ubyte(1); x.attr(0x03)              // ColorSpace = eGray
            x.op(0x6A)                            // SetColorSpace
            x.sint16xy(0, 0); x.attr(0x4C)        // Point
            x.op(0x6B)                            // SetCursor
            x.ubyte(0); x.attr(0x64)              // ColorMapping = eDirectPixel
            x.ubyte(2); x.attr(0x62)              // ColorDepth = e8Bit
            x.uint16(r.width); x.attr(0x6C)       // SourceWidth
            x.uint16(r.height); x.attr(0x6B)      // SourceHeight
            x.uint16xy(r.width, r.height); x.attr(0x67) // DestinationSize
            x.op(0xB0)                            // BeginImage
            val stride = (r.width + 3) and 3.inv()
            val px = IntArray(r.width)
            var y0 = 0
            while (y0 < r.height) {
                val rows = minOf(band, r.height - y0)
                val raw = ByteArray(stride * rows)
                for (k in 0 until rows) {
                    r.row(y0 + k, px)
                    for (i in 0 until r.width) raw[k * stride + i] = RasterCodec.luma(px[i]).toByte()
                }
                val packed = ByteArrayOutputStream().also { RasterCodec.packBits(raw, it) }.toByteArray()
                x.uint16(y0); x.attr(0x6D)        // StartLine
                x.uint16(rows); x.attr(0x63)      // BlockHeight
                x.ubyte(1); x.attr(0x65)          // CompressMode = eRLECompression
                x.op(0xB1)                        // ReadImage
                x.data(packed)
                y0 += rows
            }
            x.op(0xB2)                            // EndImage
            x.uint16(copies.coerceAtLeast(1)); x.attr(0x31) // PageCopies
            x.op(0x44)                            // EndPage
        }
        x.op(0x49)                                // CloseDataSource
        x.op(0x42)                                // EndSession
        x.ascii(UEL)
        return x.bytes()
    }

    /** Little-endian PCL XL binary writer. */
    private class Pxl {
        private val o = ByteArrayOutputStream()
        fun ascii(s: String) = o.write(s.toByteArray(Charsets.ISO_8859_1))
        fun op(code: Int) = o.write(code)
        fun attr(id: Int) { o.write(0xF8); o.write(id) }
        fun ubyte(v: Int) { o.write(0xC0); o.write(v) }
        fun uint16(v: Int) { o.write(0xC1); le16(v) }
        fun uint16xy(a: Int, b: Int) { o.write(0xD1); le16(a); le16(b) }
        fun sint16xy(a: Int, b: Int) { o.write(0xD3); le16(a); le16(b) }
        fun data(d: ByteArray) {
            o.write(0xFA)
            o.write(d.size); o.write(d.size shr 8); o.write(d.size shr 16); o.write(d.size shr 24)
            o.write(d)
        }
        private fun le16(v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF) }
        fun bytes(): ByteArray = o.toByteArray()
    }

    // ---------------------------------------------------------------- AirPrint: URF (Apple raster)

    /**
     * Apple raster ("UNIRAST"), the format AirPrint printers accept when they offer neither PDF nor PWG.
     * File and page headers follow CUPS raster-stream.c (CUPS_RASTER_WRITE_APPLE); rows use the same
     * compression as PWG raster. Pages are encoded first because the file header carries the page count.
     */
    fun urf(pages: Sequence<Raster>, dpi: Int, color: Boolean, duplex: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream()
        var count = 0
        val bpp = if (color) 3 else 1
        for (r in pages) {
            count++
            val hdr = ByteArray(32)
            hdr[0] = (bpp * 8).toByte()
            hdr[1] = (if (color) 1 else 0).toByte()   // sRGB / sGray
            hdr[2] = (if (duplex) 3 else 1).toByte()  // long-edge duplex / simplex
            hdr[3] = 4                                // normal quality
            be32(hdr, 12, r.width); be32(hdr, 16, r.height); be32(hdr, 20, dpi)
            body.write(hdr)
            rows(r, bpp, body)
        }
        val out = ByteArrayOutputStream()
        out.write("UNIRAST".toByteArray(Charsets.US_ASCII)); out.write(0)
        out.write(ByteArray(4).also { be32(it, 0, count) })
        body.writeTo(out)
        return out.toByteArray()
    }

    /** Row-repeat byte then [RasterCodec.packPixels], identical rows merged (max 256). */
    private fun rows(r: Raster, bpp: Int, out: ByteArrayOutputStream) {
        val px = IntArray(r.width)
        var prev: ByteArray? = null
        var repeat = 0
        fun flush() { val p = prev ?: return; out.write(repeat - 1); RasterCodec.packPixels(p, bpp, out) }
        for (y in 0 until r.height) {
            r.row(y, px)
            val row = ByteArray(r.width * bpp)
            for (x in 0 until r.width) {
                if (bpp == 3) { row[x * 3] = (px[x] shr 16).toByte(); row[x * 3 + 1] = (px[x] shr 8).toByte(); row[x * 3 + 2] = px[x].toByte() }
                else row[x] = RasterCodec.luma(px[x]).toByte()
            }
            if (prev != null && row.contentEquals(prev) && repeat < 256) repeat++ else { flush(); prev = row; repeat = 1 }
        }
        flush()
    }

    private fun be32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 24).toByte(); b[off + 1] = (v shr 16).toByte(); b[off + 2] = (v shr 8).toByte(); b[off + 3] = v.toByte()
    }

    // ---------------------------------------------------------------- PJL

    /**
     * Wraps a PCL 5 or PostScript stream in PJL so a multi-language printer switches to it even if it was left in
     * another language. Only for printers whose Device ID lists PJL.
     */
    fun pjl(body: ByteArray, language: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("$UEL@PJL\r\n@PJL ENTER LANGUAGE=$language\r\n".toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.write(UEL.toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}
