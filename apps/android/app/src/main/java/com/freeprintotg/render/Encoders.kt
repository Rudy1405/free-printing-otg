package com.freeprintotg.render

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/** Reads an Android bitmap row by row for the pure encoders in [RasterEncoders]. */
fun Bitmap.asRaster(): Raster = object : Raster {
    override val width get() = this@asRaster.width
    override val height get() = this@asRaster.height
    override fun row(y: Int, out: IntArray) = getPixels(out, 0, width, 0, y, width, 1)
}

/** Raster sequence over rendered bitmaps; each bitmap is recycled once the next one is requested or the end is reached. */
fun Sequence<Bitmap>.asRasters(): Sequence<Raster> = sequence {
    for (bmp in this@asRasters) { yield(bmp.asRaster()); bmp.recycle() }
}

/** Page-description encoders. Each takes rendered pages and returns the byte stream a printer language expects. */
object Encoders {

    /** Wrap bitmaps in a PDF (used when the printer wants PDF but the source was not a PDF). */
    fun pdf(pages: Sequence<Bitmap>, paper: PaperSize): ByteArray {
        val doc = PdfDocument()
        pages.forEachIndexed { i, bmp ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(paper.widthPt, paper.heightPt, i + 1).create())
            page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, paper.widthPt, paper.heightPt), null)
            doc.finishPage(page)
            bmp.recycle()
        }
        return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
    }

    /**
     * PWG Raster (PWG 5102.4), the driverless raster format every IPP Everywhere printer must accept.
     * 8-bit sRGB or sGray, big-endian 1796-byte page header per page.
     */
    fun pwgRaster(pages: Sequence<Bitmap>, paper: PaperSize, dpi: Int, color: Boolean, duplex: Boolean = false, copies: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RaS2".toByteArray())
        for (bmp in pages) {
            val w = bmp.width
            val h = bmp.height
            val bpp = if (color) 24 else 8
            val bytesPerLine = w * bpp / 8
            val hdr = ByteArray(1796)
            fun str(off: Int, s: String) = s.toByteArray().copyInto(hdr, off)
            fun int(off: Int, v: Int) {
                hdr[off] = (v shr 24).toByte(); hdr[off + 1] = (v shr 16).toByte()
                hdr[off + 2] = (v shr 8).toByte(); hdr[off + 3] = v.toByte()
            }
            str(0, "PwgRaster")
            str(128, "stationery")
            str(192, "")
            int(272, if (duplex) 1 else 0)
            int(276, dpi); int(280, dpi)
            int(340, copies)
            int(352, paper.widthPt); int(356, paper.heightPt)
            int(372, w); int(376, h)
            int(384, 8); int(388, bpp); int(392, bytesPerLine)
            int(396, 0)                         // chunky
            int(400, if (color) 19 else 18)     // sRGB / sGray
            int(420, if (color) 3 else 1)
            str(1732, paper.pwgName)
            out.write(hdr)

            val px = IntArray(w)
            var prev: ByteArray? = null
            var repeat = 0
            fun flush() {
                val p = prev ?: return
                out.write(repeat - 1)
                RasterCodec.packPixels(p, if (color) 3 else 1, out)
            }
            for (y in 0 until h) {
                bmp.getPixels(px, 0, w, 0, y, w, 1)
                val row = ByteArray(bytesPerLine)
                if (color) {
                    for (x in 0 until w) {
                        row[x * 3] = (px[x] shr 16).toByte(); row[x * 3 + 1] = (px[x] shr 8).toByte(); row[x * 3 + 2] = px[x].toByte()
                    }
                } else {
                    for (x in 0 until w) row[x] = luma(px[x]).toByte()
                }
                if (prev != null && row.contentEquals(prev) && repeat < 256) repeat++
                else { flush(); prev = row; repeat = 1 }
            }
            flush()
            bmp.recycle()
        }
        return out.toByteArray()
    }

    /** Monochrome PCL 5 raster (HP LaserJet compatible and many clones). Bayer-dithered 1 bit/pixel. */
    fun pclMono(pages: Sequence<Bitmap>, paper: PaperSize, dpi: Int, copies: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun esc(s: String) = out.write(("\u001B" + s).toByteArray(Charsets.ISO_8859_1))
        esc("E")
        esc("&l${if (paper == PaperSize.A4) 26 else 2}A")
        esc("&l${copies}X")
        for (bmp in pages) {
            val w = bmp.width; val h = bmp.height
            esc("*t${dpi}R"); esc("*r0F"); esc("&a0H"); esc("&a0V"); esc("*p0x0Y")
            esc("*r${w}S"); esc("*r1A"); esc("*b0M")
            val px = IntArray(w)
            val bytes = (w + 7) / 8
            val line = ByteArray(bytes)
            for (y in 0 until h) {
                bmp.getPixels(px, 0, w, 0, y, w, 1)
                java.util.Arrays.fill(line, 0)
                for (x in 0 until w) {
                    if (luma(px[x]) < BAYER[(y and 3) * 4 + (x and 3)]) line[x shr 3] = (line[x shr 3].toInt() or (0x80 shr (x and 7))).toByte()
                }
                esc("*b${bytes}W"); out.write(line)
            }
            esc("*rC")
            out.write(0x0C) // form feed
            bmp.recycle()
        }
        esc("E")
        return out.toByteArray()
    }

    /** PostScript Level 2 with JPEG (DCTDecode) page images, ASCII85 wrapped. */
    fun postScript(pages: Sequence<Bitmap>, paper: PaperSize): ByteArray {
        val out = ByteArrayOutputStream()
        fun s(t: String) = out.write(t.toByteArray(Charsets.ISO_8859_1))
        s("%!PS-Adobe-3.0\n%%LanguageLevel: 2\n")
        for (bmp in pages) {
            val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            s("%%Page: 1 1\nsave\n${paper.widthPt} ${paper.heightPt} scale\n/DeviceRGB setcolorspace\n")
            s("<< /ImageType 1 /Width ${bmp.width} /Height ${bmp.height} /BitsPerComponent 8 /Decode [0 1 0 1 0 1]\n")
            s("/ImageMatrix [${bmp.width} 0 0 -${bmp.height} 0 ${bmp.height}]\n")
            s("/DataSource currentfile /ASCII85Decode filter /DCTDecode filter >> image\n")
            s(ascii85(jpg)); s("\nrestore\nshowpage\n")
            bmp.recycle()
        }
        s("%%EOF\n")
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val n = minOf(4, data.size - i)
            var v = 0L
            for (k in 0 until 4) v = (v shl 8) or (if (k < n) (data[i + k].toLong() and 0xFF) else 0L)
            if (v == 0L && n == 4) sb.append('z') else {
                val c = CharArray(5)
                for (k in 4 downTo 0) { c[k] = (v % 85 + 33).toInt().toChar(); v /= 85 }
                sb.append(c, 0, n + 1)
            }
            i += 4
            if (sb.length % 80 < 5) sb.append('\n')
        }
        return sb.append("~>").toString()
    }

    private fun luma(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    private val BAYER = intArrayOf(8, 136, 40, 168, 200, 72, 232, 104, 56, 184, 24, 152, 248, 120, 216, 88)
}
