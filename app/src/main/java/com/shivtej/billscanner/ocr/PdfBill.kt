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
            // Words exactly as PDFBox spaced them (its own spacing rules know the fonts), each with the
            // boxes of its own letters. Our own gap check only splits further where letters are far apart.
            var pi = 0
            for (word in text.split(' ', '\t', '\u00A0').filter { it.isNotEmpty() }) {
                while (pi < positions.size && positions[pi].unicode.isNullOrBlank()) pi++
                val start = pi
                var got = 0
                while (pi < positions.size && got < word.length) { got += (positions[pi].unicode?.length ?: 0).coerceAtLeast(1); pi++ }
                val ps = positions.subList(start, pi)
                if (ps.isEmpty()) continue
                // A wide gap inside one "word" (two columns PDFBox ran together): split there.
                var cur = mutableListOf<TextPosition>()
                var txt = StringBuilder()
                fun flush() {
                    val t = txt.toString().trim()
                    if (cur.isNotEmpty() && t.isNotEmpty()) add(cur, t)
                    cur = mutableListOf(); txt = StringBuilder()
                }
                for (p in ps) {
                    val prev = cur.lastOrNull()
                    if (prev != null) {
                        val gap = p.xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                        val ch = maxOf(prev.widthDirAdj, p.widthDirAdj, 1f)
                        if (gap > 1.5f * ch || p.xDirAdj < prev.xDirAdj - ch) flush()
                    }
                    cur += p; txt.append(p.unicode.orEmpty())
                }
                flush()
            }
        }

        private fun add(ps: List<TextPosition>, t: String) {
            val l = ps.minOf { it.xDirAdj }; val r = ps.maxOf { it.xDirAdj + it.widthDirAdj }
            val b = ps.maxOf { it.yDirAdj }
            val h = ps.maxOf { if (it.heightDir.isFinite()) it.heightDir else 0f }.let { if (it < 3f) ps.maxOf { p -> p.fontSizeInPt } * 0.7f else it }.coerceAtLeast(3f)
            words += OcrWord(t, Box((l * K).toInt(), ((b - h) * K).toInt(), (r * K).toInt().coerceAtLeast((l * K).toInt() + 1), (b * K + h * 0.25f * K).toInt()))
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
