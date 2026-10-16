package com.shivtej.billcleaner

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_SIDE = 2200      // saaf output ki lambi side (px)
        const val PREVIEW_SIDE = 1200  // screen par dikhane ke liye
        const val JPEG_QUALITY = 80    // chhota size, text saaf
        const val MAX_PAGES = 10
    }

    // Input
    var originals by mutableStateOf<List<Uri>>(emptyList()); private set
    var originalPreviews by mutableStateOf<List<Bitmap>>(emptyList()); private set
    var fromScanner by mutableStateOf(false); private set

    // Output
    var cleanedPreviews by mutableStateOf<List<Bitmap>>(emptyList()); private set
    var outputs by mutableStateOf<List<File>>(emptyList()); private set
    var outputSize by mutableStateOf(0L); private set

    // Screen
    var page by mutableIntStateOf(0)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    // Options (badalte hi purana output hata dete hain, taaki galat file save/share na ho)
    var removeShadow by mutableStateOf(true); private set
    var enhance by mutableStateOf(true); private set
    var sharpen by mutableStateOf(true); private set
    var blackWhite by mutableStateOf(false); private set
    var asPdf by mutableStateOf(false); private set

    fun updateRemoveShadow(v: Boolean) { removeShadow = v; clearOutput() }
    fun updateEnhance(v: Boolean) { enhance = v; clearOutput() }
    fun updateSharpen(v: Boolean) { sharpen = v; clearOutput() }
    fun updateBlackWhite(v: Boolean) { blackWhite = v; clearOutput() }
    fun updateAsPdf(v: Boolean) { asPdf = v; clearOutput() }

    private fun clearOutput() {
        cleanedPreviews = emptyList()
        outputs = emptyList()
        outputSize = 0L
    }

    fun setInput(uris: List<Uri>, scanned: Boolean) {
        if (uris.isEmpty() || busy) return
        val list = uris.take(MAX_PAGES)
        busy = true
        viewModelScope.launch {
            try {
                val previews = withContext(Dispatchers.IO) {
                    list.mapNotNull { ImageLoader.decodeScaled(getApplication<Application>(), it, PREVIEW_SIDE) }
                }
                if (previews.isEmpty()) {
                    message = "Photo khul nahi payi, dobara chuno"
                } else {
                    originals = list
                    originalPreviews = previews
                    fromScanner = scanned
                    page = 0
                    clearOutput()
                    if (uris.size > MAX_PAGES) message = "Ek baar mein $MAX_PAGES page tak liye gaye"
                }
            } catch (e: Throwable) {
                message = "Photo khulne mein dikkat: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun generate() {
        if (originals.isEmpty() || busy) return
        busy = true
        val opts = CleanOptions(removeShadow, enhance, sharpen, blackWhite)
        val pdf = asPdf
        val input = originals
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val result = withContext(Dispatchers.Default) {
                    val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                    val pages = mutableListOf<File>()
                    val previews = mutableListOf<Bitmap>()
                    input.forEachIndexed { i, uri ->
                        val src = ImageLoader.decodeScaled(ctx, uri, MAX_SIDE)
                            ?: error("Page ${i + 1} ki photo khul nahi payi")
                        val clean = BillCleaner.clean(src, opts)
                        src.recycle()
                        val f = File(work, "page_${i + 1}.jpg")
                        FileOutputStream(f).use {
                            clean.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                        }
                        pages += f
                        previews += ImageLoader.scaleDown(clean, PREVIEW_SIDE)
                    }
                    val outs = OutputFiles.build(ctx, pages, pdf, baseName())
                    Triple(previews.toList(), outs, outs.sumOf { it.length() })
                }
                cleanedPreviews = result.first
                outputs = result.second
                outputSize = result.third
                message = "Bill saaf ho gaya (${formatSize(outputSize)})"
            } catch (e: OutOfMemoryError) {
                message = "Photo bahut badi hai, kam page ek saath try karo"
            } catch (e: Throwable) {
                message = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        if (outputs.isEmpty() || busy) return
        busy = true
        viewModelScope.launch {
            try {
                val where = withContext(Dispatchers.IO) {
                    OutputFiles.saveToPhone(getApplication<Application>(), outputs)
                }
                message = "Save ho gaya: $where"
            } catch (e: Throwable) {
                message = "Save nahi hua: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun baseName(): String =
        "Bill_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    else -> "${(bytes + 1023) / 1024} KB"
}
