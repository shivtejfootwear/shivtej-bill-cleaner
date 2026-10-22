package com.shivtej.billscanner

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.data.BillDb
import com.shivtej.billscanner.data.InvoiceRepo
import com.shivtej.billscanner.data.PageStore
import com.shivtej.billscanner.data.Prefs
import com.shivtej.billscanner.ocr.Ocr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A photo waiting to be scanned, and how far the person turned it. */
data class PendingPage(val uri: Uri, val rotation: Int = 0)

enum class ScanStep { SAVE, OCR, FIELDS, CHECK, DONE, FAILED }

class Container(val ctx: Context, db: BillDb) {
    val repo = InvoiceRepo(db)
    val pages = PageStore(ctx)
    val prefs = Prefs(ctx)
    var lang by mutableStateOf(prefs.language)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- the bill being added
    val pending = mutableStateListOf<PendingPage>()
    var step by mutableStateOf(ScanStep.SAVE)
    var progress by mutableStateOf(0f)
    var pageNow by mutableStateOf(0)
    var scanError by mutableStateOf<String?>(null)
    var scannedId by mutableStateOf<String?>(null)

    fun setLanguage(l: String) { prefs.language = l; lang = l }

    /** Original photos kept as they are; OCR on each page; the invoice read and saved for review. */
    fun startScan() {
        val list = pending.toList()
        if (list.isEmpty()) return
        scanError = null; scannedId = null; step = ScanStep.SAVE; progress = 0.05f; pageNow = 0
        scope.launch {
            runCatching {
                val id = UUID.randomUUID().toString()
                val files = pages.keep(id, list.map { it.uri })
                step = ScanStep.OCR
                val ocr = mutableListOf<OcrPage>()
                files.forEachIndexed { i, path ->
                    pageNow = i + 1
                    val bmp = withContext(Dispatchers.IO) { Ocr.rotate(Ocr.loadBitmap(File(path)), list[i].rotation) }
                    ocr += Ocr.read(bmp)
                    bmp.recycle()
                    progress = 0.1f + 0.6f * (i + 1) / files.size
                }
                step = ScanStep.FIELDS; progress = 0.8f
                val parsed = withContext(Dispatchers.Default) { InvoiceParser.parse(ocr) }
                step = ScanStep.CHECK; progress = 0.92f
                val inv = Invoice(id, System.currentTimeMillis(), parsed.header, parsed.header, parsed.items, files)
                repo.save(inv)
                id
            }.onSuccess { id -> scannedId = id; step = ScanStep.DONE; progress = 1f; pending.clear() }
                .onFailure { scanError = it.message ?: it.toString(); step = ScanStep.FAILED }
        }
    }
}

class BillApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this, BillDb.open(this))
    }
}

val Context.container: Container get() = (applicationContext as BillApp).container
