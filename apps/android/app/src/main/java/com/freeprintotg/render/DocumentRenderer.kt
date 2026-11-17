package com.freeprintotg.render

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

enum class MediaKind { SHEET, LABEL, ROLL }

/**
 * Printable media. For [MediaKind.ROLL] the width is the printable width (48 mm on 58 mm paper, 72 mm on 80 mm,
 * i.e. 384 / 576 dots at 203 dpi) and the height is a maximum: receipt encoders cut where the content ends.
 */
enum class PaperSize(val label: String, val widthPt: Int, val heightPt: Int, val pwgName: String, val kind: MediaKind = MediaKind.SHEET) {
    A4("A4", 595, 842, "iso_a4_210x297mm"),
    LETTER("Letter", 612, 792, "na_letter_8.5x11in"),
    LABEL_50X25("Label 50×25 mm", 142, 71, "oe_50x25mm", MediaKind.LABEL),
    LABEL_4X6("Label 4×6 in", 288, 432, "na_index-4x6_4x6in", MediaKind.LABEL),
    ROLL_58("Receipt 58 mm", 136, 842, "roll_58mm", MediaKind.ROLL),
    ROLL_80("Receipt 80 mm", 204, 842, "roll_80mm", MediaKind.ROLL),
    ;

    val widthMm get() = Math.round(widthPt * 25.4 / 72).toInt()
    val heightMm get() = Math.round(heightPt * 25.4 / 72).toInt()
}

/** A document kind the print pipeline understands. */
enum class DocKind { IMAGE, PDF, TEXT, HTML, UNSUPPORTED }

class RenderedDoc(val name: String, val kind: DocKind, val pdfBytes: ByteArray?, val pages: Sequence<Bitmap>)

/**
 * Turns any supported source into page bitmaps at a given DPI.
 * Images: JPEG PNG GIF WEBP BMP HEIC (whatever the platform decoder handles).
 * PDF: PdfRenderer. TEXT/HTML: laid out with StaticLayout.
 * Office formats are not rendered here; they need a conversion pack (see docs).
 */
object DocumentRenderer {

    fun kindOf(mime: String?, name: String): DocKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime?.startsWith("image/") == true || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") -> DocKind.IMAGE
            mime == "application/pdf" || ext == "pdf" -> DocKind.PDF
            mime == "text/html" || ext in setOf("html", "htm") -> DocKind.HTML
            mime?.startsWith("text/") == true || ext in setOf("txt", "log", "csv", "md") -> DocKind.TEXT
            else -> DocKind.UNSUPPORTED
        }
    }

    fun displayName(ctx: Context, uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "document"

    fun open(ctx: Context, uri: Uri, paper: PaperSize, dpi: Int): RenderedDoc {
        val name = displayName(ctx, uri)
        val kind = kindOf(ctx.contentResolver.getType(uri), name)
        val w = paper.widthPt * dpi / 72
        val h = paper.heightPt * dpi / 72
        return when (kind) {
            DocKind.IMAGE -> RenderedDoc(name, kind, null, sequenceOf(renderImage(ctx, uri, w, h)))
            DocKind.PDF -> pdf(ctx, uri, name, w, h)
            DocKind.TEXT -> RenderedDoc(name, kind, null, renderText(readText(ctx, uri), w, h, dpi))
            DocKind.HTML -> RenderedDoc(
                name, kind, null,
                renderText(Html.fromHtml(readText(ctx, uri), Html.FROM_HTML_MODE_LEGACY).toString(), w, h, dpi),
            )
            DocKind.UNSUPPORTED -> error("Unsupported file type for $name (Office documents need a conversion pack)")
        }
    }

    private fun readText(ctx: Context, uri: Uri) =
        ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }

    private fun renderImage(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap {
        val src = ctx.contentResolver.openInputStream(uri)!!.use { s ->
            val bytes = s.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot decode image")
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(page)
        c.drawColor(Color.WHITE)
        // rotate landscape photos to use the page, then fit inside 5mm-ish margins
        val rotate = src.width > src.height
        val sw = if (rotate) src.height else src.width
        val sh = if (rotate) src.width else src.height
        val scale = minOf(w * 0.96f / sw, h * 0.96f / sh)
        val m = Matrix()
        if (rotate) m.postRotate(90f, src.width / 2f, src.height / 2f).also { m.postTranslate((src.height - src.width) / 2f, (src.width - src.height) / 2f) }
        m.postScale(scale, scale)
        m.postTranslate((w - sw * scale) / 2f, (h - sh * scale) / 2f)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return page
    }

    private fun pdf(ctx: Context, uri: Uri, name: String, w: Int, h: Int): RenderedDoc {
        val tmp = File.createTempFile("job", ".pdf", ctx.cacheDir)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val bytes = tmp.readBytes()
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd)
        val pages = sequence {
            try {
                for (i in 0 until r.pageCount) {
                    r.openPage(i).use { p ->
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        val s = minOf(w.toFloat() / p.width, h.toFloat() / p.height)
                        val m = Matrix().apply { postScale(s, s); postTranslate((w - p.width * s) / 2f, (h - p.height * s) / 2f) }
                        p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        yield(bmp)
                    }
                }
            } finally { r.close(); pfd.close(); tmp.delete() }
        }
        return RenderedDoc(name, DocKind.PDF, bytes, pages)
    }

    private fun renderText(text: String, w: Int, h: Int, dpi: Int): Sequence<Bitmap> = sequence {
        val margin = dpi // 1 inch
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f * dpi / 72f; typeface = Typeface.MONOSPACE }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val usable = h - 2 * margin
        var y = 0
        do {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.save()
            c.clipRect(margin, margin, w - margin, margin + usable)
            c.translate(margin.toFloat(), (margin - y).toFloat())
            layout.draw(c)
            c.restore()
            yield(bmp)
            y += usable
        } while (y < layout.height)
    }
}
