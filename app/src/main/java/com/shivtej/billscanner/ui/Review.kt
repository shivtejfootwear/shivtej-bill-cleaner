package com.shivtej.billscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warning
import com.shivtej.billscanner.core.model.Warnings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ReviewScreen(id: String, onBack: () -> Unit, onItems: () -> Unit, onAnalysis: () -> Unit, onExport: () -> Unit, onPhoto: () -> Unit) {
    val c = LocalContext.current.container
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    val scope = rememberCoroutineScope()
    val previous by produceState(emptyList<Invoice>(), inv.id) { value = c.repo.allNow() }
    val thumb by produceState<Bitmap?>(null, inv.pages.firstOrNull()) {
        value = withContext(Dispatchers.IO) {
            inv.pages.firstOrNull()?.let { p -> runCatching { android.graphics.BitmapFactory.decodeFile(p, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) }.getOrNull() }
        }
    }
    var editHeader by remember { mutableStateOf(false) }
    var askVerify by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val warnings = Warnings.of(inv, previous)

    ReviewContent(
        inv, warnings, thumb, onBack, onItems, onAnalysis, onExport, onPhoto,
        onEditHeader = { editHeader = true },
        onVerify = {
            if (inv.status == InvoiceStatus.VERIFIED) ed.change { Edits.setStatus(it, InvoiceStatus.DRAFT, System.currentTimeMillis()) }
            else askVerify = true
        },
        onHistory = { history = true }, onDelete = { askDelete = true },
        canUndo = ed.canUndo, onUndo = { ed.undo() },
    )

    if (editHeader) HeaderDialog(inv.header, { editHeader = false }) { h -> editHeader = false; ed.change { Edits.updateHeader(it, h, System.currentTimeMillis()) } }
    if (askVerify) {
        val open = warnings.count { it.level != Warning.Level.TIP }
        AlertDialog(
            onDismissRequest = { askVerify = false },
            title = { Text(tr("बिल को \"Verified\" करें?", "Mark the invoice as verified?")) },
            text = { Text(if (open > 0) tr("अभी $open बातें जाँचनी बाकी हैं। फिर भी Verified करना है?", "$open points still need checking. Verify anyway?") else tr("सब जाँच लिया है — पक्का?", "Everything checked — sure?")) },
            confirmButton = { TextButton(onClick = { askVerify = false; ed.change { Edits.setStatus(it, InvoiceStatus.VERIFIED, System.currentTimeMillis()) } }) { Text(tr("हाँ, Verified", "Yes, verified")) } },
            dismissButton = { TextButton(onClick = { askVerify = false }) { Text(tr("रुकें", "Cancel")) } },
        )
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text(tr("यह बिल हटाएँ?", "Delete this invoice?")) },
        text = { Text(tr("बिल का रिकॉर्ड और उसकी फ़ोटो इस फ़ोन से हट जाएँगे। पहले से बनी Excel/PDF फ़ाइलें रहेंगी।", "The record and its photos are removed from this phone. Exported files stay.")) },
        confirmButton = { TextButton(onClick = { askDelete = false; scope.launch { c.repo.delete(inv.id); c.pages.deleteAll(inv.id); onBack() } }) { Text(tr("हटाएँ", "Delete"), color = C.err) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(tr("नहीं", "No")) } },
    )
    if (history) AlertDialog(
        onDismissRequest = { history = false },
        title = { Text(tr("बदलाव का इतिहास", "Change history")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (inv.changes.isEmpty()) item { Text(tr("अभी तक कोई बदलाव नहीं।", "No changes yet.")) }
                items(inv.changes.reversed()) { ch ->
                    val item = inv.items.firstOrNull { it.id == ch.itemId }?.current
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text("${item?.let { "${it.sr ?: ""} ${it.article} · " } ?: ""}${ch.field}: ${ch.from} → ${ch.to}", fontSize = 13.sp)
                        Text(fmtDate(ch.at), fontSize = 11.sp, color = C.muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { history = false }) { Text("OK") } },
    )
}

@Composable
fun ReviewContent(
    inv: Invoice, warnings: List<Warning>, thumb: Bitmap?, onBack: () -> Unit, onItems: () -> Unit, onAnalysis: () -> Unit,
    onExport: () -> Unit, onPhoto: () -> Unit, onEditHeader: () -> Unit = {}, onVerify: () -> Unit = {}, onHistory: () -> Unit = {},
    onDelete: () -> Unit = {}, canUndo: Boolean = false, onUndo: () -> Unit = {},
) {
    val h = inv.header
    val t = Totals.of(inv)
    val open = warnings.count { it.level != Warning.Level.TIP }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("बिल की जानकारी", "Invoice details"), onBack) {
            if (canUndo) TextButton(onClick = onUndo) { Text("Undo", color = C.navy) }
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, contentDescription = tr("इतिहास", "History"), tint = C.navy) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = tr("हटाएँ", "Delete"), tint = C.muted) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inv.status == InvoiceStatus.VERIFIED) Pill(tr("Verified ✓", "Verified ✓"), C.ok, C.okSoft)
                else if (open > 0) Pill(tr("$open जगह जाँचें", "$open to check"), C.warn, C.warnSoft)
                else Pill(tr("जाँच बाकी", "Not verified"), C.navy, C.navySoft)
            }
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(70.dp, 94.dp).background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, C.line, RoundedCornerShape(8.dp)).clickable(onClick = onPhoto),
                    ) { thumb?.let { Image(it.asImageBitmap(), contentDescription = tr("असली फ़ोटो", "Original photo"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(h.supplierName ?: "Not found", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (h.supplierName == null) C.warn else C.navy)
                        Muted("GSTIN: ${h.supplierGstin ?: "Not found"}")
                        h.supplierAddress?.let { Muted(it.take(90)) }
                        Text(tr("असली फ़ोटो देखें ›", "View original photo ›") + " (${inv.pages.size})", fontSize = 13.sp, color = C.orange, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onPhoto).padding(top = 4.dp))
                    }
                    IconButton(onClick = onEditHeader) { Icon(Icons.Outlined.Edit, contentDescription = tr("बदलें", "Edit"), tint = C.navy) }
                }
            }
            Card {
                fun v(s: String?) = s ?: "Not found"
                fun col(s: Any?) = if (s == null) C.warn else C.ink
                KV(tr("बिल नंबर", "Invoice no."), v(h.invoiceNo), col(h.invoiceNo), onClick = onEditHeader)
                KV(tr("तारीख़", "Date"), v(h.invoiceDate), col(h.invoiceDate), onClick = onEditHeader)
                KV(tr("ऑर्डर नंबर", "Order no."), v(h.orderNo), col(h.orderNo), onClick = onEditHeader)
                KV(tr("ट्रांसपोर्ट", "Transporter"), v(h.transporter), col(h.transporter), onClick = onEditHeader)
                KV(tr("कार्टन / बंडल", "Cartons"), v(h.cartons), col(h.cartons))
                KV(tr("पैकिंग", "Packing"), v(h.packing), col(h.packing))
                KV(tr("ख़रीदार", "Buyer"), v(h.buyerName), col(h.buyerName))
                val taxRates = inv.items.mapNotNull { it.current.cgstPct }.distinct()
                KV("CGST + SGST", if (taxRates.isEmpty()) "Not found" else taxRates.joinToString(", ") { "${pct(it)} + ${pct(it)}" })
                h.pageNote?.let { KV(tr("पेज", "Page"), it) }
            }
            Card {
                Title(tr("कुल मिलान", "Totals and reconciliation"))
                KV(tr("आर्टिकल · जोड़ी", "Articles · pairs"), "${t.articleLines} · ${qtyText(t.qty)}")
                KV(tr("डिस्काउंट से पहले", "Before discount"), rs(t.gross))
                KV(tr("कुल बिल डिस्काउंट", "Total bill discount"), rs(t.discount), C.orange)
                KV("Taxable", rs(t.taxable))
                KV("CGST + SGST" + if (t.igst.signum() != 0) " + IGST" else "", rs(t.cgst + t.sgst + t.igst))
                if (t.other.signum() != 0) KV(tr("दूसरे चार्ज", "Other charges"), rs(t.other))
                KV(tr("लाइनों का कुल", "Lines total"), rs(t.grand))
                KV(tr("बिल पर छपा कुल", "Printed total"), rs(t.printedGrand), if (t.printedGrand == null) C.warn else C.ink, onClick = onEditHeader)
                t.difference?.let { d ->
                    val ok = d.abs() <= java.math.BigDecimal("0.99")
                    KV(tr("फ़र्क़", "Difference"), rs(d), if (ok) C.ok else C.err)
                }
            }
            if (warnings.isNotEmpty()) Card {
                Title(tr("जाँचने की बातें", "Points to check") + " (${warnings.size})")
                warnings.forEach { WarningRow(it) }
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.fillMaxWidth().background(C.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(tr("आर्टिकल देखें", "View articles") + " (${inv.items.size}) ›", onItems, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(tr("डिस्काउंट विश्लेषण", "Discount analysis"), onAnalysis, Modifier.weight(1f))
                GhostButton(tr("Excel / PDF", "Excel / PDF"), onExport, Modifier.weight(1f))
            }
            if (inv.status != InvoiceStatus.VERIFIED) BigButton(tr("जाँचकर Verified करें", "Mark as verified"), onVerify, Modifier.fillMaxWidth(), orange = true)
            else TextButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) { Text(tr("Verified हटाएँ (फिर से जाँचना है)", "Remove verified (check again)"), color = C.muted) }
        }
    }
}

@Composable
fun HeaderDialog(h: Header, onDismiss: () -> Unit, onSave: (Header) -> Unit) {
    var supplier by remember { mutableStateOf(h.supplierName.orEmpty()) }
    var gstin by remember { mutableStateOf(h.supplierGstin.orEmpty()) }
    var no by remember { mutableStateOf(h.invoiceNo.orEmpty()) }
    var date by remember { mutableStateOf(h.invoiceDate.orEmpty()) }
    var order by remember { mutableStateOf(h.orderNo.orEmpty()) }
    var transporter by remember { mutableStateOf(h.transporter.orEmpty()) }
    var total by remember { mutableStateOf(h.printedGrandTotal?.toPlainString().orEmpty()) }
    var other by remember { mutableStateOf(h.otherCharges?.toPlainString().orEmpty()) }
    var round by remember { mutableStateOf(h.roundOff?.toPlainString().orEmpty()) }
    var overall by remember { mutableStateOf(h.overallDiscount?.toPlainString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("बिल की जानकारी बदलें", "Edit invoice details")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable fun f(label: String, v: String, num: Boolean = false, set: (String) -> Unit) = OutlinedTextField(
                    v, set, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = if (num) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                )
                f(tr("सप्लायर", "Supplier"), supplier) { supplier = it }
                f("GSTIN", gstin) { gstin = it.uppercase() }
                f(tr("बिल नंबर", "Invoice no."), no) { no = it }
                f(tr("तारीख़", "Date"), date) { date = it }
                f(tr("ऑर्डर नंबर", "Order no."), order) { order = it }
                f(tr("ट्रांसपोर्ट", "Transporter"), transporter) { transporter = it }
                f(tr("बिल पर छपा कुल ₹", "Printed grand total ₹"), total, true) { total = it }
                f(tr("दूसरे चार्ज ₹", "Other charges ₹"), other, true) { other = it }
                f("Round-off ₹", round, true) { round = it }
                f(tr("पूरे बिल पर अलग डिस्काउंट ₹", "Overall invoice discount ₹"), overall, true) { overall = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                fun s(x: String) = x.trim().ifEmpty { null }
                onSave(h.copy(
                    supplierName = s(supplier), supplierGstin = s(gstin), invoiceNo = s(no), invoiceDate = s(date), orderNo = s(order),
                    transporter = s(transporter), printedGrandTotal = Calc.bd(total), otherCharges = Calc.bd(other), roundOff = Calc.bd(round),
                    overallDiscount = Calc.bd(overall),
                ))
            }) { Text(tr("सेव करें", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("रुकें", "Cancel")) } },
    )
}

/** Full-screen original photo(s), pinch to zoom. */
@Composable
fun PhotoScreen(id: String, onBack: () -> Unit) {
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    var page by remember { mutableStateOf(0) }
    val bmp by produceState<Bitmap?>(null, inv.pages.getOrNull(page)) {
        value = withContext(Dispatchers.IO) { inv.pages.getOrNull(page)?.let { runCatching { com.shivtej.billscanner.ocr.Ocr.loadBitmap(File(it)) }.getOrNull() } }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(tr("असली फ़ोटो", "Original photo") + " · ${page + 1}/${inv.pages.size}", onBack) {
            if (inv.pages.size > 1) TextButton(onClick = { page = (page + 1) % inv.pages.size; scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) { Text(tr("अगला पेज", "Next page")) }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset += pan }
            },
            contentAlignment = Alignment.Center,
        ) {
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
            }
        }
    }
}
