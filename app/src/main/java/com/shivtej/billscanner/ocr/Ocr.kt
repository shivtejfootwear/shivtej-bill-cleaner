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

    suspend fun read(bmp: Bitmap): OcrPage = withContext(Dispatchers.Default) {
        val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val words = text.textBlocks.flatMap { b -> b.lines.flatMap { l -> l.elements } }.mapNotNull { e ->
            val r = e.boundingBox ?: return@mapNotNull null
            OcrWord(e.text, Box(r.left, r.top, r.right, r.bottom))
        }
        OcrPage(bmp.width, bmp.height, words)
    }
}
