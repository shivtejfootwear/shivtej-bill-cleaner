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
