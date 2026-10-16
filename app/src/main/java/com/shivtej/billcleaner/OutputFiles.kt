package com.shivtej.billcleaner

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object OutputFiles {
    const val FOLDER = "Shivtej Bill Cleaner"
    private const val PDF_PAGE_WIDTH_PT = 595      // A4 ki chaudai
    private const val PDF_IMAGE_WIDTH_PX = 1800    // ~220 dpi: text saaf, size theek

    /** Saaf pages se final JPG(s) ya ek PDF banata hai, cache/out/ mein. */
    fun build(ctx: Context, cleanedPages: List<File>, asPdf: Boolean, baseName: String): List<File> {
        val outDir = File(ctx.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
        return if (asPdf) {
            val pdf = File(outDir, "$baseName.pdf")
            makePdf(cleanedPages, pdf)
            listOf(pdf)
        } else {
            cleanedPages.mapIndexed { i, f ->
                val name = if (cleanedPages.size == 1) "$baseName.jpg" else "${baseName}_p${i + 1}.jpg"
                f.copyTo(File(outDir, name), overwrite = true)
            }
        }
    }

    private fun makePdf(pages: List<File>, out: File) {
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { i, f ->
                val full = BitmapFactory.decodeFile(f.path) ?: return@forEachIndexed
                val bmp = if (full.width > PDF_IMAGE_WIDTH_PX) {
                    val h = (full.height * PDF_IMAGE_WIDTH_PX.toFloat() / full.width).roundToInt()
                    Bitmap.createScaledBitmap(full, PDF_IMAGE_WIDTH_PX, h, true)
                        .also { if (it !== full) full.recycle() }
                } else full
                val pw = PDF_PAGE_WIDTH_PT
                val ph = (pw * bmp.height.toFloat() / bmp.width).roundToInt().coerceAtLeast(1)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                page.canvas.drawBitmap(
                    bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
                doc.finishPage(page)
                bmp.recycle()
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** Phone mein save: JPG -> Pictures/Shivtej Bill Cleaner, PDF -> Download/Shivtej Bill Cleaner */
    fun saveToPhone(ctx: Context, files: List<File>): String {
        var where = ""
        for (f in files) {
            val isPdf = f.name.endsWith(".pdf", ignoreCase = true)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isPdf) "application/pdf" else "image/jpeg")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isPdf) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_PICTURES) + "/" + FOLDER
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection: Uri = if (isPdf) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = ctx.contentResolver.insert(collection, values)
                ?: error("Phone mein file nahi ban payi")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            } ?: error("Phone mein file likh nahi paye")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            where = if (isPdf) "Download/$FOLDER" else "Pictures/$FOLDER"
        }
        return where
    }

    /** WhatsApp ya kisi bhi app par bhejne ka Intent. */
    fun shareIntent(ctx: Context, files: List<File>): Intent {
        val authority = "${ctx.packageName}.files"
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, authority, it) })
        val type = if (files.first().name.endsWith(".pdf", true)) "application/pdf" else "image/jpeg"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        send.type = type
        val clip = ClipData.newRawUri(files.first().name, uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Bill bhejo")
    }
}
