package com.shivtej.billscanner.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.shivtej.billscanner.core.ocr.Heal
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import com.shivtej.billscanner.core.ocr.ParsedInvoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * The scanner checks its own reading. Every line whose numbers do not add up (pairs × rate − discount ≠ amount)
 * is cut out of the photo, enlarged and cleaned in a few ways, and read again. A new reading is kept only if that
 * line then adds up and no line that was already right goes wrong. All on the phone.
 */
object SelfCheck {
    class Result(val pages: List<OcrPage>, val parsed: ParsedInvoice, val tried: Int, val fixed: Int)

    /** The bitmap a page was read from (same size as its OCR), or null for a PDF page read from its own text. */
    class Source(val bitmap: suspend () -> Bitmap?)

    private enum class Look { GREY, SHARP, BLACK_WHITE }

    private fun good(p: ParsedInvoice) = p.items.filter { Heal.sure(it) }.mapNotNull { it.current.sr }.toSet()

    suspend fun run(
        pages: List<OcrPage>, sources: List<Source?>, parse: (List<OcrPage>) -> ParsedInvoice,
        budgetMs: Long = 15_000,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.Default) {
        // Kept quick: the person is waiting. Lines a finger or an icon covered cannot be read anyway.
        val deadline = System.currentTimeMillis() + budgetMs
        val cur = pages.toMutableList()
        var parsed = parse(cur)
        val bad = parsed.items.filter { !Heal.sure(it) && it.kind == com.shivtej.billscanner.core.model.ItemKind.ARTICLE && it.current.sr != null && it.at != null && sources.getOrNull(it.at!!.page) != null }.take(60)
        val bitmaps = HashMap<Int, Bitmap?>()
        var fixed = 0
        for ((n, item) in bad.withIndex()) {
            onProgress(n + 1, bad.size)
            val a = item.at!!
            val bmp = bitmaps.getOrPut(a.page) { runCatching { sources[a.page]!!.bitmap() }.getOrNull() } ?: continue
            if (bmp.width != a.pageW || bmp.height != a.pageH) continue
            // Fixed already by an earlier re-read (neighbouring line)?
            val now = parsed.items.firstOrNull { it.current.sr == item.current.sr } ?: continue
            if (Heal.sure(now)) continue
            val page = cur[a.page]
            val mh = page.words.map { it.box.h }.sorted().let { if (it.isEmpty()) 20 else it[it.size / 2] }.coerceAtLeast(8)
            val top = (a.t - mh / 2).coerceAtLeast(0); val bottom = (a.b + mh / 2).coerceAtMost(bmp.height)
            if (bottom - top < 6) continue
            val before = good(parsed)
            if (System.currentTimeMillis() > deadline) break
            search@ for ((look, letter) in listOf(Look.GREY to 40, Look.BLACK_WHITE to 36, Look.SHARP to 30)) {
                if (System.currentTimeMillis() > deadline) break@search
                val words = readStrip(bmp, top, bottom, mh, letter, look) ?: continue
                // This band's words replaced by the new reading of it.
                val inBand = { w: OcrWord -> w.box.cy >= a.t.toDouble() && w.box.cy <= a.b.toDouble() }
                val trial = cur.toMutableList().also { it[a.page] = page.copy(words = page.words.filterNot(inBand) + words.filter(inBand)) }
                val p2 = parse(trial)
                val after = good(p2)
                val line = p2.items.firstOrNull { it.current.sr == item.current.sr }
                if (after.containsAll(before) && after.size > before.size && line != null && Heal.sure(line) && p2.items.size <= parsed.items.size + 1) {
                    cur[a.page] = trial[a.page]; parsed = p2; fixed++
                    break@search
                }
            }
        }
        Result(cur, parsed, bad.size, fixed)
    }

    /** One strip of the page, enlarged so letters are about [letter] px tall and cleaned, read again; words in page coordinates. */
    private suspend fun readStrip(page: Bitmap, top: Int, bottom: Int, mh: Int, letter: Int, look: Look): List<OcrWord>? = runCatching {
        val part = Bitmap.createBitmap(page, 0, top, page.width, bottom - top)
        val k = min(letter.toDouble() / mh, 4096.0 / page.width).coerceAtLeast(1.0)
        val big = Bitmap.createScaledBitmap(part, (part.width * k).toInt(), max(1, (part.height * k).toInt()), true)
        if (big !== part) part.recycle()
        val clean = when (look) {
            Look.GREY -> filtered(big, greyContrast(1.5f))
            Look.SHARP -> filtered(big, greyContrast(2.2f))
            Look.BLACK_WHITE -> blackWhite(big)
        }
        if (clean !== big) big.recycle()
        val ws = Ocr.recogniseAt(clean, 0, top, k)
        clean.recycle()
        ws
    }.getOrNull()

    private fun greyContrast(c: Float) = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, 128f * (1 - c), 0f, c, 0f, 0f, 128f * (1 - c), 0f, 0f, c, 0f, 128f * (1 - c), 0f, 0f, 0f, 1f, 0f)))
    }

    private fun filtered(src: Bitmap, m: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    /** Ink black, paper white: each pixel against the brightness around it (shadows and stamps fade out). */
    private fun blackWhite(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h) { i -> val c = px[i]; ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
        // Integral image for the local mean.
        val sum = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0L; for (x in 0 until w) { row += g[y * w + x]; sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row } }
        val r = max(8, h / 2)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1); val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            val s = sum[y1 * (w + 1) + x1] - sum[y0 * (w + 1) + x1] - sum[y1 * (w + 1) + x0] + sum[y0 * (w + 1) + x0]
            val mean = s / ((x1 - x0) * (y1 - y0))
            px[y * w + x] = if (g[y * w + x] < mean * 0.85) -0x1000000 else -1
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
