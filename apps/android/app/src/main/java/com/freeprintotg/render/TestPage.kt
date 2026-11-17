package com.freeprintotg.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Setup-assistant test page: a few large lines inside a frame, sized to fit anything from A4 to a 50 x 25 mm label. */
object TestPage {
    fun render(lines: List<String>, paper: PaperSize, dpi: Int): Bitmap {
        val w = paper.widthPt * dpi / 72
        // Receipts and labels need only the text; sheets keep their full height so the page feeds normally.
        val h = paper.heightPt * dpi / 72
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val margin = w / 20f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD }
        // Largest size at which the longest line fits the width and all lines fit a label's height.
        val longest = lines.maxBy { it.length }
        paint.textSize = 100f
        val byWidth = 100f * (w - 2 * margin) / paint.measureText(longest)
        val byHeight = (minOf(h, w) - 2 * margin) / (lines.size * 1.3f)
        paint.textSize = minOf(byWidth, byHeight, dpi / 3f)
        var y = margin + paint.textSize
        for (line in lines) { c.drawText(line, margin, y, paint); y += paint.textSize * 1.3f }
        val frame = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = maxOf(2f, dpi / 75f) }
        c.drawRect(margin / 2, margin / 2, w - margin / 2, y - paint.textSize * 0.6f + margin / 2, frame)
        return bmp
    }
}
