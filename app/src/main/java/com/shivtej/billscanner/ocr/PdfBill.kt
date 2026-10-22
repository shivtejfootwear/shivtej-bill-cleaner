package com.shivtej.billscanner.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bills that come as a PDF (e.g. on WhatsApp). A PDF made by billing software holds the text itself:
 * every word and its exact position is taken straight from the file — no guessing from a photo.
 * Only a PDF that is just a scanned picture is rendered and read with the OCR.
 */
object PdfBill {
    /** PDF points → the pixel scale the reader works in (letters ~25 px tall, like a good photo). */
    private const val K = 3.0f

    class Page(val words: OcrPage, val fromText: Boolean)

    private class Stripper(private val pageNo: Int) : PDFTextStripper() {
        val words = mutableListOf<OcrWord>()
        init { startPage = pageNo; endPage = pageNo; sortByPosition = true }

        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            // Split the run into words at spaces (and at wide gaps between letters).
            var cur = mutableListOf<TextPosition>()
            fun flush() {
                if (cur.isEmpty()) return
                val t = cur.joinToString("") { it.unicode }.trim()
                if (t.isNotEmpty()) {
                    val l = cur.minOf { it.xDirAdj }; val r = cur.maxOf { it.xDirAdj + it.widthDirAdj }
                    val b = cur.maxOf { it.yDirAdj }; val h = cur.maxOf { it.heightDir }.coerceAtLeast(4f)
                    words += OcrWord(t, Box((l * K).toInt(), ((b - h) * K).toInt(), (r * K).toInt(), (b * K + h * 0.25f * K).toInt()))
                }
                cur = mutableListOf()
            }
            for (p in positions) {
                val prev = cur.lastOrNull()
                val gap = if (prev != null) p.xDirAdj - (prev.xDirAdj + prev.widthDirAdj) else 0f
                if (p.unicode.isBlank()) { flush(); continue }
                if (prev != null && (gap > p.widthOfSpace * 0.8f || p.xDirAdj < prev.xDirAdj)) flush()
                cur += p
            }
            flush()
        }
    }

    fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }

    /** The page as a picture (to keep and show, and to OCR a scanned PDF). */
    fun render(file: File, index: Int, scale: Float = 2.5f): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(index).use { pg ->
                    val w = (pg.width * scale).toInt().coerceAtMost(4000); val h = (pg.height * scale).toInt().coerceAtMost(5600)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    b
                }
            }
        }

    /** Words of every page: from the PDF's own text, or by OCR when a page has no text. */
    suspend fun read(ctx: Context, file: File, onPage: (Int, Int) -> Unit = { _, _ -> }): List<Page> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val out = mutableListOf<Page>()
        val textPages = runCatching {
            PDDocument.load(file).use { doc ->
                (1..doc.numberOfPages).map { n ->
                    val s = Stripper(n)
                    s.getText(doc)
                    val box = doc.getPage(n - 1).mediaBox
                    Triple(s.words.toList(), (box.width * K).toInt(), (box.height * K).toInt())
                }
            }
        }.getOrNull()
        val count = textPages?.size ?: pageCount(file)
        for (i in 0 until count) {
            onPage(i + 1, count)
            val t = textPages?.getOrNull(i)
            if (t != null && t.first.count { w -> w.text.any(Char::isDigit) } >= 15) {
                out += Page(OcrPage(t.second, t.third, t.first), true)
            } else {
                val bmp = render(file, i, 3f)
                out += Page(Ocr.read(bmp), false)
                bmp.recycle()
            }
        }
        out
    }
}
