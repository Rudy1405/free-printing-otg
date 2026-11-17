package com.freeprintotg.render

import java.io.ByteArrayOutputStream

/**
 * A page image read one row at a time, as ARGB ints. Pure Kotlin so encoders can be unit-tested on the JVM;
 * Android bitmaps are adapted with [android.graphics.Bitmap.asRaster].
 */
interface Raster {
    val width: Int
    val height: Int
    /** Fills [out] (size >= [width]) with row [y]. */
    fun row(y: Int, out: IntArray)
}

class ArrayRaster(override val width: Int, override val height: Int, private val argb: IntArray) : Raster {
    init { require(argb.size == width * height) }
    override fun row(y: Int, out: IntArray) { argb.copyInto(out, 0, y * width, y * width + width) }
}

/** Shared pixel helpers for every raster language. */
object RasterCodec {
    fun luma(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    private val BAYER = intArrayOf(8, 136, 40, 168, 200, 72, 232, 104, 56, 184, 24, 152, 248, 120, 216, 88)

    /** Ordered-dither threshold: true when the pixel at (x, y) should be a printed (black) dot. */
    fun isDot(argb: Int, x: Int, y: Int) = luma(argb) < BAYER[(y and 3) * 4 + (x and 3)]

    /**
     * One row as 1 bit per pixel, MSB first, [bytes] wide. A set bit means "black" unless [inkIsZero]
     * (TSPL and PCL XL gray use 0 for black). Pixels past the raster width are white.
     */
    fun monoRow(px: IntArray, width: Int, y: Int, bytes: Int, inkIsZero: Boolean = false): ByteArray {
        val line = ByteArray(bytes)
        for (x in 0 until minOf(width, bytes * 8)) {
            if (isDot(px[x], x, y)) line[x shr 3] = (line[x shr 3].toInt() or (0x80 shr (x and 7))).toByte()
        }
        if (inkIsZero) for (i in line.indices) line[i] = line[i].toInt().inv().toByte()
        return line
    }

    /** Number of rows to keep so trailing all-white rows are dropped (receipts are cut where content ends). */
    fun contentHeight(r: Raster): Int {
        val px = IntArray(r.width)
        for (y in r.height - 1 downTo 0) {
            r.row(y, px)
            for (x in 0 until r.width) if (isDot(px[x], x, y)) return y + 1
        }
        return 0
    }

    /**
     * PostScript RunLengthEncode / PackBits, as used by PCL XL eRLECompression: length byte 0..127 copies
     * the next length+1 bytes, 129..255 repeats the next byte 257-length times. No EOD byte.
     */
    fun packBits(data: ByteArray, out: ByteArrayOutputStream) {
        var i = 0
        val n = data.size
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && data[i + run] == data[i]) run++
            if (run >= 2) {
                out.write(257 - run); out.write(data[i].toInt()); i += run
            } else {
                var lit = 1
                while (i + lit < n && lit < 128 && !(i + lit + 1 < n && data[i + lit] == data[i + lit + 1])) lit++
                out.write(lit - 1); out.write(data, i, lit); i += lit
            }
        }
    }

    /**
     * PWG / Apple raster row compression (CUPS raster-stream.c): n in 0..127 repeats the next pixel n+1 times;
     * 257-n copies n literal pixels. [bpp] is bytes per pixel.
     */
    fun packPixels(row: ByteArray, bpp: Int, out: ByteArrayOutputStream) {
        val n = row.size / bpp
        fun same(a: Int, b: Int): Boolean { for (k in 0 until bpp) if (row[a * bpp + k] != row[b * bpp + k]) return false; return true }
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && same(i, i + run)) run++
            if (run >= 2 || i == n - 1) {
                out.write(run - 1); out.write(row, i * bpp, bpp); i += run
            } else {
                var lit = 1
                while (i + lit < n && lit < 128 && !(i + lit + 1 < n && same(i + lit, i + lit + 1))) lit++
                if (lit == 1) { out.write(0); out.write(row, i * bpp, bpp) }
                else { out.write(257 - lit); out.write(row, i * bpp, lit * bpp) }
                i += lit
            }
        }
    }
}
