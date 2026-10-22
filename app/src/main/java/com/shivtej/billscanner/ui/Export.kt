package com.shivtej.billscanner.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.export.InvoiceExport
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.export.Pdf
import com.shivtej.billscanner.ocr.Ocr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

enum class ExportKind(val ext: String, val mime: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF_REPORT("pdf", "application/pdf"),
    PDF_PHOTO("pdf", "application/pdf"),
    PDF_TARGET("pdf", "application/pdf"),
    IMAGE_TARGET("png", "image/png"),
    STOCK_CSV("csv", "text/csv"),
    OCR_TEXT("txt", "text/plain"),
}

object Files {
    fun name(inv: Invoice, k: ExportKind) = InvoiceExport.fileBase(inv) + when (k) {
        ExportKind.XLSX -> "_Items.xlsx"; ExportKind.PDF_REPORT -> "_Report.pdf"; ExportKind.PDF_PHOTO -> "_Original_Photo.pdf"
        ExportKind.PDF_TARGET -> "_New_Bill_Target.pdf"; ExportKind.IMAGE_TARGET -> "_New_Bill_Target.png"; ExportKind.STOCK_CSV -> "_Stock.csv"
        ExportKind.OCR_TEXT -> "_Scan_Data.txt"
    }

    fun bytes(ctx: Context, inv: Invoice, k: ExportKind, landscape: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        when (k) {
            ExportKind.XLSX -> InvoiceExport.xlsx(inv, out)
            ExportKind.PDF_REPORT -> Pdf.report(inv, landscape, out)
            ExportKind.PDF_PHOTO -> {
                val bmps = inv.pages.mapNotNull { runCatching { Ocr.loadBitmap(File(it)) }.getOrNull() }
                Pdf.photos(bmps, "${inv.header.supplierName ?: ""} ${inv.header.invoiceNo ?: ""}".trim(), out)
                bmps.forEach { it.recycle() }
            }
            ExportKind.PDF_TARGET -> Pdf.target(inv, out)
            ExportKind.IMAGE_TARGET -> {
                val pdf = ByteArrayOutputStream().also { Pdf.target(inv, it) }.toByteArray()
                Pdf.toPng(ctx, pdf, out)
            }
            ExportKind.STOCK_CSV -> out.write(InvoiceExport.stockCsv(inv).toByteArray(Charsets.UTF_8))
            ExportKind.OCR_TEXT -> {
                // What the scanner read, word by word with positions — for improving the reader on a difficult bill.
                val pages = ctx.container.pages.loadOcr(inv.id)
                out.write(("Shivtej Bill Scanner AI — scan data\n${inv.header.supplierName ?: ""} ${inv.header.invoiceNo ?: ""}\n\n" +
                    pages.joinToString("\n") { com.shivtej.billscanner.core.store.OcrCodec.encode(it) }).toByteArray(Charsets.UTF_8))
            }
        }
        return out.toByteArray()
    }

    /** Saves into Downloads/Shivtej Bills. Returns where it went. */
    fun saveToDownloads(ctx: Context, name: String, mime: String, data: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Shivtej Bills"); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("file not created")
            r.openOutputStream(uri)!!.use { it.write(data) }
            r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return "Download/Shivtej Bills/$name"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Shivtej Bills").apply { mkdirs() }
        File(dir, name).writeBytes(data)
        return File(dir, name).absolutePath
    }

    fun share(ctx: Context, name: String, mime: String, data: ByteArray) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, name).apply { writeBytes(data) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, name); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun ExportScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    val scope = rememberCoroutineScope()
    var landscape by remember { mutableStateOf(c.prefs.pdfLandscape) }
    var busy by remember { mutableStateOf<ExportKind?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var askStock by remember { mutableStateOf<Boolean?>(null) }

    fun run(k: ExportKind, share: Boolean) {
        busy = k
        scope.launch {
            runCatching {
                val data = withContext(Dispatchers.IO) { Files.bytes(ctx, inv, k, landscape) }
                val name = Files.name(inv, k)
                if (share) Files.share(ctx, name, k.mime, data) else withContext(Dispatchers.IO) { Files.saveToDownloads(ctx, name, k.mime, data) }.also { msg = tr2(c.lang, "सेव हो गई: $it", "Saved: $it") }
                ed.change { it.copy(exportedAt = System.currentTimeMillis()) }
            }.onFailure { msg = tr2(c.lang, "फ़ाइल नहीं बनी: ${it.message}", "Could not create the file: ${it.message}") }
            busy = null
        }
    }

    ExportContent(inv, landscape, { landscape = it; c.prefs.pdfLandscape = it }, busy, msg, onBack) { k, share ->
        if (k == ExportKind.STOCK_CSV) askStock = share else run(k, share)
    }
    askStock?.let { share ->
        val n = InvoiceExport.stockRows(inv).sumOf { it.qty }
        AlertDialog(
            onDismissRequest = { askStock = null },
            title = { Text(tr("साइज़-वार स्टॉक फ़ाइल बनाएँ?", "Create the size-wise stock file?")) },
            text = { Text(tr("$n जोड़ी की साइज़-वार लाइनें बनेंगी — सिर्फ़ उन आर्टिकल की जिनके साइज़ बिल की मात्रा से मेल खाते हैं।", "$n pairs, only for articles whose sizes match the bill quantity.") +
                if (inv.status != InvoiceStatus.VERIFIED) tr("\n\nबिल अभी Verified नहीं है।", "\n\nThe invoice is not verified yet.") else "") },
            confirmButton = { TextButton(onClick = { askStock = null; run(ExportKind.STOCK_CSV, share) }) { Text(tr("हाँ, बनाएँ", "Yes, create")) } },
            dismissButton = { TextButton(onClick = { askStock = null }) { Text(tr("रुकें", "Cancel")) } },
        )
    }
}

@Composable
fun ExportContent(inv: Invoice, landscape: Boolean, onLandscape: (Boolean) -> Unit, busy: ExportKind?, message: String?, onBack: () -> Unit, onMake: (ExportKind, Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("फ़ाइल बनाएँ", "Create files"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (inv.status != InvoiceStatus.VERIFIED) Text(tr("⚠ बिल अभी Verified नहीं है — फ़ाइल में \"Not verified yet\" लिखा रहेगा।", "⚠ Invoice not verified yet — files will say \"Not verified yet\"."),
                fontSize = 13.sp, color = C.warn, modifier = Modifier.fillMaxWidth().background(C.warnSoft, RoundedCornerShape(10.dp)).padding(10.dp))
            message?.let { Text(it, fontSize = 13.sp, color = C.ok, modifier = Modifier.fillMaxWidth().background(C.okSoft, RoundedCornerShape(10.dp)).padding(10.dp)) }
            ExportRow("XLSX", Color(0xFF13795B), "Excel (.xlsx)", tr("बिल जैसा छपा + हरा बैठा % + पीला टार्गेट % (Excel में बदल सकते हैं) · कंपनी-वार · नया बिल · स्टॉक", "Bill as printed + green baitha % + yellow target % (editable in Excel) · company-wise · new bill · stock"), busy == ExportKind.XLSX, { onMake(ExportKind.XLSX, false) }) { onMake(ExportKind.XLSX, true) }
            ExportRow("%", C.orange, tr("नया बिल (आपके %) PDF", "New bill at your % — PDF"), tr("कंपनी-वार %, नया रेट, नई रकम, Total और फ़र्क़", "Company %, new rate, amount, total and difference"), busy == ExportKind.PDF_TARGET, { onMake(ExportKind.PDF_TARGET, false) }) { onMake(ExportKind.PDF_TARGET, true) }
            ExportRow("PNG", C.orangeDark, tr("नया बिल — फ़ोटो (WhatsApp के लिए)", "New bill — image (for WhatsApp)"), tr("एक लंबी फ़ोटो में पूरा नया बिल", "The whole new bill as one image"), busy == ExportKind.IMAGE_TARGET, { onMake(ExportKind.IMAGE_TARGET, false) }) { onMake(ExportKind.IMAGE_TARGET, true) }
            ExportRow("PDF", C.err, tr("बिल रिपोर्ट PDF (बैठा % सहित)", "Bill report PDF (with baitha %)"), tr("बिल के सारे कॉलम जैसे छपे + बैठा % · हर पेज पर हेडर", "All bill columns as printed + baitha % · headers on every page"), busy == ExportKind.PDF_REPORT, { onMake(ExportKind.PDF_REPORT, false) }) { onMake(ExportKind.PDF_REPORT, true) }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted(tr("PDF आड़ा (landscape) — ज़्यादा कॉलम", "Landscape PDF — more columns"), Modifier.weight(1f)); Switch(landscape, onLandscape)
            }
            ExportRow("IMG", C.navy, tr("असली बिल की फ़ोटो PDF", "Original invoice photo PDF"), tr("फ़ोटो जैसी है वैसी, कोई बदलाव नहीं", "The photo as it is, unchanged"), busy == ExportKind.PDF_PHOTO, { onMake(ExportKind.PDF_PHOTO, false) }) { onMake(ExportKind.PDF_PHOTO, true) }
            ExportRow("CSV", C.navy2, tr("साइज़-वार स्टॉक CSV", "Size-wise stock CSV"), "Article, Colour, Size, Quantity, MRP, Purchase Rate, Supplier, Invoice Number, Invoice Date", busy == ExportKind.STOCK_CSV, { onMake(ExportKind.STOCK_CSV, false) }) { onMake(ExportKind.STOCK_CSV, true) }
            ExportRow("TXT", C.muted, tr("स्कैन की कच्ची जानकारी", "Raw scan data"), tr("कोई बिल ठीक से न पढ़ा जाए तो यह फ़ाइल भेजें — उससे पढ़ना सुधारा जाएगा", "If a bill is not read well, send this file so the reader can be improved"), busy == ExportKind.OCR_TEXT, { onMake(ExportKind.OCR_TEXT, false) }) { onMake(ExportKind.OCR_TEXT, true) }
            Muted(tr("फ़ाइलें फ़ोन के Download/Shivtej Bills फ़ोल्डर में सेव होती हैं। शेयर से WhatsApp, Gmail, Drive पर भेज सकते हैं।", "Files are saved in Download/Shivtej Bills. Share sends them to WhatsApp, Gmail, Drive."))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ExportRow(tag: String, color: Color, title: String, sub: String, busy: Boolean, onSave: () -> Unit, onShare: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onSave).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(color, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(tag, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.navy)
            Text(sub, fontSize = 12.sp, color = C.muted)
        }
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = tr("शेयर", "Share"), tint = C.navy) }
    }
}
