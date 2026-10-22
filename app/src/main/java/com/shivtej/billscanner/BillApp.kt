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
import com.shivtej.billscanner.core.ocr.TaughtLayouts
import com.shivtej.billscanner.data.BillDb
import com.shivtej.billscanner.data.InvoiceRepo
import com.shivtej.billscanner.data.PageStore
import com.shivtej.billscanner.data.Prefs
import com.shivtej.billscanner.ocr.Ocr
import com.shivtej.billscanner.ocr.PdfBill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A photo waiting to be scanned, and how far the person turned it. */
data class PendingPage(val uri: Uri, val rotation: Int = 0, val pdf: Boolean = false)

enum class ScanStep { SAVE, OCR, FIELDS, CHECK, DONE, FAILED }

class Container(val ctx: Context, db: BillDb) {
    val repo = InvoiceRepo(db)
    val pages = PageStore(ctx)
    val prefs = Prefs(ctx)
    var lang by mutableStateOf(prefs.language)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- the bill being added
    val pending = mutableStateListOf<PendingPage>()
    /** Adds a page/PDF to the bill being added, once. */
    fun addPending(p: PendingPage) { if (pending.none { it.uri == p.uri }) pending += p }
    var step by mutableStateOf(ScanStep.SAVE)
    var progress by mutableStateOf(0f)
    var pageNow by mutableStateOf(0)
    var scanError by mutableStateOf<String?>(null)
    var scannedId by mutableStateOf<String?>(null)
    /** Bumped when a bill is shared to the app from WhatsApp / Files. */
    var sharedTick by mutableStateOf(0)

    fun setLanguage(l: String) { prefs.language = l; lang = l }

    /** Original photos kept as they are; OCR on each page; the invoice read and saved for review. */
    fun startScan() {
        val list = pending.toList()
        if (list.isEmpty()) return
        scanError = null; scannedId = null; step = ScanStep.SAVE; progress = 0.05f; pageNow = 0
        scope.launch {
            runCatching {
                val id = UUID.randomUUID().toString()
                val ocr = mutableListOf<OcrPage>()
                val files = mutableListOf<String>()
                step = ScanStep.OCR
                // The same file added twice (picked again, or shared twice) is read once.
                val seen = mutableSetOf<String>()
                fun firstTime(f: File): Boolean {
                    val h = java.security.MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                    return seen.add(h).also { if (!it) f.delete() }
                }
                for ((k, item) in list.distinctBy { it.uri }.withIndex()) {
                    if (item.pdf) {
                        // A PDF bill: its own text is read directly; pages are also kept as pictures to look at.
                        val src = withContext(Dispatchers.IO) { pages.keepFile(id, item.uri, "bill_${k + 1}.pdf") }
                        if (!withContext(Dispatchers.IO) { firstTime(src) }) continue
                        val read = PdfBill.read(ctx, src) { n, total -> pageNow = files.size + 1; progress = 0.1f + 0.6f * n / total }
                        read.forEachIndexed { i, pg ->
                            ocr += pg.words
                            files += withContext(Dispatchers.IO) {
                                val bmp = PdfBill.render(src, i, 2f)
                                pages.keepBitmap(id, files.size + 1, bmp).also { bmp.recycle() }
                            }
                        }
                    } else {
                        val kept = withContext(Dispatchers.IO) { pages.keepFile(id, item.uri, "page_${files.size + 1}.jpg") }
                        if (!withContext(Dispatchers.IO) { firstTime(kept) }) continue
                        val path = kept.absolutePath
                        files += path
                        pageNow = files.size
                        val bmp = withContext(Dispatchers.IO) { Ocr.rotate(Ocr.loadBitmap(File(path)), item.rotation) }
                        ocr += Ocr.read(bmp)
                        bmp.recycle()
                        progress = 0.1f + 0.6f * (k + 1) / list.size
                    }
                }
                withContext(Dispatchers.IO) { pages.saveOcr(id, ocr) }
                step = ScanStep.FIELDS; progress = 0.8f
                val parsed = withContext(Dispatchers.Default) { InvoiceParser.parse(ocr, TaughtLayouts(prefs.taught)) }
                step = ScanStep.CHECK; progress = 0.92f
                val inv = Invoice(id, System.currentTimeMillis(), parsed.header, parsed.header, parsed.items, files, columns = parsed.columns)
                repo.save(inv)
                id
            }.onSuccess { id -> scannedId = id; step = ScanStep.DONE; progress = 1f; pending.clear() }
                .onFailure { scanError = it.message ?: it.toString(); step = ScanStep.FAILED }
        }
    }
}

/**
 * Teaches what one printed column of this supplier's bills means, and reads the bill again with it.
 * Next bills from the same supplier use it straight away. Returns the re-read invoice (null if the
 * OCR of this bill was not kept).
 */
suspend fun Container.teachColumn(inv: Invoice, label: String, kind: InvoiceParser.Kind): Invoice? {
    val key = TaughtLayouts.key(inv.header) ?: TaughtLayouts.key(inv.headerOriginal) ?: return null
    val all = prefs.taught.toMutableMap()
    all[key] = (all[key] ?: emptyMap()) + (TaughtLayouts.norm(label) to kind)
    prefs.taught = all
    val ocr = withContext(Dispatchers.IO) { pages.loadOcr(inv.id) }
    if (ocr.isEmpty()) return null
    val p = withContext(Dispatchers.Default) { InvoiceParser.parse(ocr, TaughtLayouts(all)) }
    val now = System.currentTimeMillis()
    val again = inv.copy(
        headerOriginal = p.header, header = p.header, items = p.items, columns = p.columns, status = com.shivtej.billscanner.core.model.InvoiceStatus.DRAFT,
        changes = inv.changes + com.shivtej.billscanner.core.model.Change(now, null, "column:$label", "", kind.name),
    )
    repo.save(again)
    return again
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
