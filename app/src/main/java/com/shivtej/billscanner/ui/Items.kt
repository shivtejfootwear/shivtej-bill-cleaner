package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.model.Sizes
import java.math.BigDecimal

// ------------------------------------------------------------------ edit one line

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(
    item: LineItem, onDismiss: () -> Unit, onSave: (ItemValues) -> Unit, onVerified: (Boolean) -> Unit, onRevert: () -> Unit,
    onRecalc: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
    header: Header = Header(), withGst: Boolean = true, target: BigDecimal? = null, onTarget: (BigDecimal?) -> Unit = {},
    strip: android.graphics.Bitmap? = null,
) {
    val o = item.original
    val c = item.current
    var f by remember(item) { mutableStateOf(Fields.of(c)) }
    var askDelete by remember { mutableStateOf(false) }
    var askReplaceVerified by remember { mutableStateOf<ItemValues?>(null) }
    val draft = f.apply(c)
    val preview = Calc.recalc(draft)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${c.sr ?: ""} · ${c.article.ifEmpty { "?" }} · " + tr("बदलें", "Edit"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.navy)
            // What the bill really says on this line: look here and type the numbers below.
            if (strip != null) {
                var big by remember { mutableStateOf(false) }
                Muted(tr("बिल में यह लाइन (टैप = बड़ा / छोटा):", "This line on the bill (tap = bigger / smaller):"))
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().border(1.dp, C.line, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).clickable { big = !big },
                ) {
                    androidx.compose.foundation.Image(
                        strip.asImageBitmap(), contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        modifier = if (big) Modifier.width((strip.width / 1.3f).coerceAtLeast(600f).dp) else Modifier.fillMaxWidth(),
                    )
                }
            }
            if (item.needs.isNotEmpty()) Text(
                "⚑ " + tr("पीले ख़ाने ठीक से नहीं पढ़े गए: ", "Not read well: ") + item.needs.joinToString(", ") { com.shivtej.billscanner.core.model.Warnings.hindi(it) } +
                    tr(" — ऊपर बिल देखकर नीचे सही संख्या लिखें और सेव करें।", " — look at the bill above, type the right numbers below and save."),
                fontSize = 12.5.sp, color = C.warn, modifier = Modifier.fillMaxWidth().background(C.warnSoft, RoundedCornerShape(10.dp)).padding(8.dp),
            )
            Muted(tr("बिल पर छपा (स्कैन): ", "Printed on bill (scan): ") + (o.discPct?.let { pct(it) } ?: "Needs Verification") + " · " + tr("असली मान अलग सुरक्षित रहता है", "the original is kept separately"))
            OutlinedTextField(
                f.discPct, { f = f.copy(discPct = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Original Bill Discount (%)") }, suffix = { Text("%", color = C.orange, fontWeight = FontWeight.Bold) },
                textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.navy),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.orange, unfocusedBorderColor = C.orange),
            )
            val sitting = Calc.sittingPct(preview, header, withGst)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mini(tr("नेट रेट / जोड़ी", "Net rate / pair"), rs(Calc.netPerPair(preview)), C.navySoft, Modifier.weight(1f))
                Mini(if (withGst) tr("GST सहित / जोड़ी", "With GST / pair") else tr("GST बिना", "Before GST"), rs(if (withGst) Calc.landedPerPair(preview, header) else Calc.netPerPair(preview)), C.orangeSoft, Modifier.weight(1f))
                Mini(tr("बैठा %", "Baitha %"), pct(sitting), C.okSoft, Modifier.weight(1f))
            }
            // The person's target for this one article (empty = the company's / whole bill's).
            var tgt by remember(item.id, target) { mutableStateOf(item.targetPct?.toPlainString().orEmpty()) }
            OutlinedTextField(
                tgt, { tgt = it; onTarget(Calc.bd(it)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("इस आर्टिकल का टार्गेट % (ख़ाली = कंपनी वाला)", "Target % for this article (empty = company's)")) },
                placeholder = { Text(target?.let { pct(it) } ?: "—") }, suffix = { Text("%") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.ok, unfocusedBorderColor = C.ok),
            )
            val drivers = draft.qty != c.qty || draft.rate != c.rate || draft.discPct != c.discPct || draft.cgstPct != c.cgstPct ||
                draft.sgstPct != c.sgstPct || draft.igstPct != c.igstPct
            val shown = if (drivers) preview else draft
            val checks = Calc.checkLine(shown)
            val g = shown.gross; val da = shown.discAmt
            if (shown.discPct == null) Text(
                "⚑ " + tr("छपा डिस्काउंट % नहीं पढ़ा गया।", "The printed discount % was not read.") +
                    (if (g != null && da != null && g.signum() != 0) " ₹${num(da)} ÷ ₹${num(g)} = ${pct(Calc.r2(da * BigDecimal(100) / g))} " +
                        tr("बनता है — बिल फ़ोटो में देखकर ही भरें।", "— check the photo before filling it in.") else ""),
                fontSize = 12.5.sp, color = C.warn, modifier = Modifier.fillMaxWidth().background(C.warnSoft, RoundedCornerShape(10.dp)).padding(8.dp),
            ) else if (checks.isEmpty() && shown.gross != null) Text(
                "✓ " + tr("मिलान", "Check") + ": ${qtyText(shown.qty)} × ${num(shown.rate)} = ${num(shown.gross)} · ${pct(shown.discPct)} = ${num(shown.discAmt)}",
                fontSize = 12.5.sp, color = C.ok, modifier = Modifier.fillMaxWidth().background(C.okSoft, RoundedCornerShape(10.dp)).padding(8.dp),
            ) else checks.forEach { ch ->
                Text("⚠ ${com.shivtej.billscanner.core.model.Warnings.hindi(ch.field)}: ${tr("बिल पर", "printed")} ${num(ch.printed)}, ${tr("हिसाब से", "expected")} ${num(ch.expected)}",
                    fontSize = 12.5.sp, color = C.warn, modifier = Modifier.fillMaxWidth().background(C.warnSoft, RoundedCornerShape(10.dp)).padding(8.dp))
            }
            Title(tr("बाकी जानकारी", "Other details"))
            @Composable fun t(label: String, v: String, orig: String, num: Boolean = false, set: (String) -> Unit) {
                val scanned = tr("स्कैन में: ", "Scanned: ") + orig.ifEmpty { "—" }
                val sup: (@Composable () -> Unit)? = if (v != orig) { { Text(scanned, color = C.orangeDark) } } else null
                OutlinedTextField(
                    v, set, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
                    supportingText = sup,
                    keyboardOptions = if (num) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                )
            }
            val of = Fields.of(o)
            t(tr("आर्टिकल नंबर", "Article no."), f.article, of.article) { f = f.copy(article = it) }
            t(tr("नाम / विवरण", "Name / description"), f.name, of.name) { f = f.copy(name = it) }
            t(tr("कलर", "Colour"), f.colour, of.colour) { f = f.copy(colour = it) }
            t(tr("साइज़ (जैसा छपा)", "Size assortment (as printed)"), f.size, of.size) { f = f.copy(size = it) }
            val sz = Sizes.read(f.size, Calc.bd(f.qty))
            if (f.size.isNotEmpty()) Muted(sz.hindi + if (sz.rows.isNotEmpty()) " · " + sz.rows.joinToString(", ") { "${it.colour.take(5)} ${it.size}×${it.qty}".trim() } else "",
                color = if (sz.state == Sizes.State.EXACT || sz.state == Sizes.State.FROM_SETS) C.ok else C.warn)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { t(tr("मात्रा", "Qty"), f.qty, of.qty, true) { f = f.copy(qty = it) } }
                Box(Modifier.weight(1f)) { t("MRP", f.mrp, of.mrp, true) { f = f.copy(mrp = it) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { t(tr("रेट", "Rate"), f.rate, of.rate, true) { f = f.copy(rate = it) } }
                Box(Modifier.weight(1f)) { t(tr("डिस्काउंट ₹", "Discount ₹"), f.discAmt, of.discAmt, true) { f = f.copy(discAmt = it) } }
            }
            t("Taxable ₹", f.taxable, of.taxable, true) { f = f.copy(taxable = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { t("CGST %", f.cgstPct, of.cgstPct, true) { f = f.copy(cgstPct = it) } }
                Box(Modifier.weight(1f)) { t("SGST %", f.sgstPct, of.sgstPct, true) { f = f.copy(sgstPct = it) } }
                Box(Modifier.weight(1f)) { t("IGST %", f.igstPct, of.igstPct, true) { f = f.copy(igstPct = it) } }
            }
            t(tr("दूसरे चार्ज ₹", "Other charges ₹"), f.other, of.other, true) { f = f.copy(other = it) }
            t(tr("नोट", "Notes"), f.notes, of.notes) { f = f.copy(notes = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("यह लाइन जाँच ली (Verified)", "This line is checked (verified)"), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(item.verified, onVerified)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRevert) { Icon(Icons.Outlined.Restore, null, tint = C.navy); Text(tr(" असली पर", " Original"), color = C.navy) }
                TextButton(onClick = onRecalc) { Icon(Icons.Outlined.Calculate, null, tint = C.navy); Text(tr(" हिसाब", " Recalc"), color = C.navy) }
                TextButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, null, tint = C.navy) }
                TextButton(onClick = { askDelete = true }) { Icon(Icons.Outlined.Delete, null, tint = C.err) }
            }
            BigButton(tr("सेव करें", "Save"), {
                if (item.verified && draft != c) askReplaceVerified = draft else { onSave(draft); onDismiss() }
            }, Modifier.fillMaxWidth(), orange = true)
        }
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text(tr("यह लाइन हटाएँ?", "Delete this line?")) },
        text = { Text("${c.sr ?: ""} ${c.article} — " + tr("इतिहास में दर्ज रहेगा; Undo से वापस आ सकती है।", "kept in history; Undo brings it back.")) },
        confirmButton = { TextButton(onClick = { askDelete = false; onDelete() }) { Text(tr("हटाएँ", "Delete"), color = C.err) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(tr("नहीं", "No")) } },
    )
    askReplaceVerified?.let { d ->
        AlertDialog(
            onDismissRequest = { askReplaceVerified = null },
            title = { Text(tr("यह लाइन Verified है", "This line is verified")) },
            text = { Text(tr("जाँची हुई जानकारी बदलनी है? बदलने के बाद इसे फिर से जाँचना होगा।", "Replace checked data? It will need checking again.")) },
            confirmButton = { TextButton(onClick = { askReplaceVerified = null; onSave(d); onDismiss() }) { Text(tr("हाँ, बदलें", "Yes, replace")) } },
            dismissButton = { TextButton(onClick = { askReplaceVerified = null }) { Text(tr("नहीं", "No")) } },
        )
    }
}

@Composable
private fun Mini(label: String, value: String, bg: Color, modifier: Modifier) {
    Column(modifier.background(bg, RoundedCornerShape(10.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = C.muted, textAlign = TextAlign.Center)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.navy)
    }
}

/** Text-box values of one line while editing. Empty = not found. */
data class Fields(
    val article: String, val name: String, val colour: String, val size: String, val qty: String, val mrp: String, val rate: String,
    val discPct: String, val discAmt: String, val taxable: String, val cgstPct: String, val sgstPct: String, val igstPct: String,
    val other: String, val notes: String,
) {
    fun apply(v: ItemValues) = v.copy(
        article = article.trim(), name = name.trim(), colour = colour.trim(), sizeText = size.trim(), qty = Calc.bd(qty), mrp = Calc.bd(mrp),
        rate = Calc.bd(rate), discPct = Calc.bd(discPct), discAmt = Calc.bd(discAmt), taxable = Calc.bd(taxable),
        cgstPct = Calc.bd(cgstPct), sgstPct = Calc.bd(sgstPct), igstPct = Calc.bd(igstPct), other = Calc.bd(other), notes = notes,
    )

    companion object {
        private fun s(b: BigDecimal?) = b?.toPlainString().orEmpty()
        fun of(v: ItemValues) = Fields(v.article, v.name, v.colour, v.sizeText, s(v.qty), s(v.mrp), s(v.rate), s(v.discPct), s(v.discAmt),
            s(v.taxable), s(v.cgstPct), s(v.sgstPct), s(v.igstPct), s(v.other), v.notes)
    }
}
