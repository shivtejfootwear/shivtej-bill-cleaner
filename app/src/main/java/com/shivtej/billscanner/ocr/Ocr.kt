package com.shivtej.billscanner.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * Reads the text on a bill photo with Google ML Kit, on the phone (no internet).
 * Every word comes back with its position, which the invoice reader needs to find columns.
 */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Large enough for small print, small enough for older phones. */
    const val MAX_SIDE = 3200

    fun loadBitmap(open: () -> InputStream, exifOpen: () -> InputStream): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2
        var bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("फ़ोटो नहीं खुली")
        val k = MAX_SIDE.toDouble() / max(bmp.width, bmp.height)
        if (k < 1) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val rot = runCatching { exifOpen().use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        return bmp
    }

    fun loadBitmap(ctx: Context, uri: Uri) = loadBitmap({ ctx.contentResolver.openInputStream(uri)!! }, { ctx.contentResolver.openInputStream(uri)!! })
    fun loadBitmap(file: File) = loadBitmap({ file.inputStream() }, { file.inputStream() })

    /** Rotates a page by 90° steps (when the person turns it in the app). */
    fun rotate(bmp: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /** Words of [bmp] (a part of a page enlarged [k] times, placed at [dx], [dy]) in page coordinates. */
    suspend fun recogniseAt(bmp: Bitmap, dx: Int, dy: Int, k: Double) = recognise(bmp, dx, dy, k)

    private suspend fun recognise(bmp: Bitmap, dx: Int, dy: Int, k: Double): List<OcrWord> {
        val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        return text.textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements } }.mapNotNull { e ->
            val r = e.boundingBox ?: return@mapNotNull null
            OcrWord(e.text, Box((r.left / k).toInt() + dx, (r.top / k).toInt() + dy, (r.right / k).toInt() + dx, (r.bottom / k).toInt() + dy))
        }
    }

    private fun digits(ws: List<OcrWord>) = ws.sumOf { w -> w.text.count { it.isDigit() } }

    /**
     * Reads a page. Dense bills (many small lines) are also read in overlapping strips at a larger size —
     * the OCR needs each letter to be big enough — and the reading with more numbers is kept.
     */
    suspend fun read(bmp: Bitmap): OcrPage = withContext(Dispatchers.Default) {
        val whole = recognise(bmp, 0, 0, 1.0)
        val heights = whole.map { it.box.h }.sorted()
        val mh = if (heights.isEmpty()) 0 else heights[heights.size / 2]
        var best = whole
        if (mh in 1..27 && bmp.height > 600) {
            // Strips across the page (lines stay whole), each enlarged so letters are ~32 px tall.
            val strips = if (bmp.height > 2 * bmp.width) 4 else 3
            val overlap = (bmp.height * 0.06).toInt()
            val step = bmp.height / strips
            val scale = (32.0 / mh).coerceIn(1.0, 2.5)
            val tiled = mutableListOf<OcrWord>()
            for (s in 0 until strips) {
                val top = maxOf(0, s * step - overlap); val bottom = minOf(bmp.height, (s + 1) * step + overlap)
                val part = Bitmap.createBitmap(bmp, 0, top, bmp.width, bottom - top)
                val big = if (scale > 1.05) Bitmap.createScaledBitmap(part, (part.width * scale).toInt(), (part.height * scale).toInt(), true) else part
                val ws = recognise(big, 0, top, scale)
                if (big !== part) big.recycle()
                part.recycle()
                // Each word is kept from exactly one strip: the cut lines do not overlap.
                val keepTop = if (s == 0) 0 else s * step
                val keepBottom = if (s == strips - 1) bmp.height else (s + 1) * step
                tiled += ws.filter { it.box.cy >= keepTop && it.box.cy < keepBottom }
            }
            val dedup = com.shivtej.billscanner.core.ocr.Layout.dedupe(tiled)
            if (digits(dedup) > digits(whole)) best = dedup
        }
        OcrPage(bmp.width, bmp.height, best)
    }
}
