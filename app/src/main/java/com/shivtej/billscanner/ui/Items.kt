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
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.model.Sizes
import java.math.BigDecimal

enum class SortBy { SR, ARTICLE, QTY, RATE, DISCOUNT }

@Composable
fun ItemsScreen(id: String, onBack: () -> Unit) {
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    var q by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortBy.SR) }
    var table by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    ItemsContent(
        inv, q, { q = it }, sort, { sort = it }, table, { table = !table },
        onBack = onBack, onOpen = { editing = it },
        onAdd = { ed.change { Edits.addItem(it, System.currentTimeMillis()) } },
        onRecalcAll = { ed.change { Edits.recalcAll(it, System.currentTimeMillis()) } },
        canUndo = ed.canUndo, onUndo = { ed.undo() },
    )
    editing?.let { itemId ->
        val item = inv.items.firstOrNull { it.id == itemId }
        if (item == null) editing = null else ItemSheet(item, onDismiss = { editing = null },
            onSave = { v -> ed.change { Edits.updateItem(it, itemId, v, System.currentTimeMillis()) } },
            onVerified = { on -> ed.change { Edits.setVerified(it, itemId, on, System.currentTimeMillis()) } },
            onRevert = { ed.change { Edits.revertItem(it, itemId, System.currentTimeMillis()) } },
            onRecalc = { ed.change { Edits.recalcItem(it, itemId, System.currentTimeMillis()) } },
            onDuplicate = { ed.change { Edits.duplicateItem(it, itemId, System.currentTimeMillis()) }; editing = null },
            onDelete = { ed.change { Edits.deleteItem(it, itemId, System.currentTimeMillis()) }; editing = null },
        )
    }
}

fun sorted(items: List<LineItem>, q: String, sort: SortBy): List<LineItem> {
    val t = q.trim()
    val f = items.filter { t.isEmpty() || listOf(it.current.article, it.current.name, it.current.colour, it.current.sizeText).any { s -> s.contains(t, true) } }
    return when (sort) {
        SortBy.SR -> f
        SortBy.ARTICLE -> f.sortedBy { it.current.article }
        SortBy.QTY -> f.sortedByDescending { it.current.qty ?: BigDecimal.ZERO }
        SortBy.RATE -> f.sortedByDescending { it.current.rate ?: BigDecimal.ZERO }
        SortBy.DISCOUNT -> f.sortedByDescending { it.current.discPct ?: BigDecimal("-1") }
    }
}

@Composable
fun ItemsContent(
    inv: Invoice, query: String, onQuery: (String) -> Unit, sort: SortBy, onSort: (SortBy) -> Unit, table: Boolean, onTable: () -> Unit,
    onBack: () -> Unit, onOpen: (String) -> Unit, onAdd: () -> Unit = {}, onRecalcAll: () -> Unit = {}, canUndo: Boolean = false, onUndo: () -> Unit = {},
) {
    val list = sorted(inv.items, query, sort)
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("आर्टिकल", "Articles") + " (${inv.items.size})", onBack) {
            if (canUndo) TextButton(onClick = onUndo) { Text("Undo", color = C.navy) }
            IconButton(onClick = onTable) { Icon(if (table) Icons.Outlined.ViewAgenda else Icons.Outlined.TableChart, contentDescription = tr("टेबल", "Table"), tint = C.navy) }
        }
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = C.muted)
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    if (query.isEmpty()) Text(tr("आर्टिकल, नाम, कलर खोजें", "Search article, name, colour"), fontSize = 14.sp, color = C.muted)
                    BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = C.ink), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(tr("क्रम", "Order"), sort == SortBy.SR) { onSort(SortBy.SR) }
                Chip(tr("आर्टिकल", "Article"), sort == SortBy.ARTICLE) { onSort(SortBy.ARTICLE) }
                Chip(tr("मात्रा", "Qty"), sort == SortBy.QTY) { onSort(SortBy.QTY) }
                Chip(tr("रेट", "Rate"), sort == SortBy.RATE) { onSort(SortBy.RATE) }
                Chip(tr("डिस्काउंट", "Discount"), sort == SortBy.DISCOUNT) { onSort(SortBy.DISCOUNT) }
            }
        }
        if (table) ItemTable(list, onOpen, Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { ItemCard(it) { onOpen(it.id) } }
            item {
                Muted(tr("नारंगी डिब्बा = सप्लायर ने इस आर्टिकल पर बिल में जो डिस्काउंट % छापा। \"MRP पर %\" = MRP और रेट का फ़र्क़ — यह अलग हिसाब है।",
                    "Orange box = the discount % the supplier printed for this article. \"MRP %\" = gap between MRP and rate — a separate calculation."))
            }
        }
        Row(Modifier.fillMaxWidth().background(C.card).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(tr("+ लाइन", "+ Line"), onAdd, Modifier.weight(1f), Icons.Outlined.Add)
            GhostButton(tr("सब का हिसाब", "Recalc all"), onRecalcAll, Modifier.weight(1f), Icons.Outlined.Calculate)
        }
    }
}

@Composable
fun ItemCard(it: LineItem, onClick: () -> Unit) {
    val v = it.current
    val flag = it.needs.isNotEmpty() || Calc.checkLine(v).isNotEmpty()
    Row(
        Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(14.dp))
            .border(if (flag && !it.verified) 1.5.dp else 1.dp, if (it.verified) C.ok else if (flag) C.warn else C.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${v.sr ?: "•"} · ${v.article.ifEmpty { "?" }}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.navy)
                Spacer(Modifier.width(6.dp))
                if (it.kind == ItemKind.CHARGE) Pill(tr("चार्ज", "Charge"), C.navy, C.navySoft)
                if (it.verified) Pill("✓", C.ok, C.okSoft)
                if (it.edited.isNotEmpty()) Pill(tr("बदला", "edited"), C.orangeDark, C.orangeSoft)
            }
            Text(listOf(v.name, v.colour).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" }, fontSize = 12.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (v.sizeText.isNotEmpty()) Text(v.sizeText, fontSize = 11.5.sp, color = C.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row { Cell3("MRP", rs(v.mrp), F.MRP in it.needs); Cell3(tr("रेट", "Rate"), rs(v.rate), F.RATE in it.needs); Cell3(tr("जोड़ी", "Qty"), qtyText(v.qty), F.QTY in it.needs) }
            Row { Cell3(tr("छूट ₹", "Disc ₹"), num(v.discAmt), F.DISC_AMT in it.needs); Cell3(tr("MRP पर %", "MRP %"), pct(Calc.mrpDiscPct(v)), false); Cell3(tr("कुल", "Total"), rs(Calc.lineTotal(v)), F.TAXABLE in it.needs) }
        }
        Spacer(Modifier.width(8.dp))
        BillDiscountBox(v.discPct, onClick, edited = F.DISC_PCT in it.edited)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell3(label: String, value: String, warn: Boolean) {
    Column(Modifier.weight(1f)) {
        Text(label, fontSize = 10.5.sp, color = C.muted)
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (warn) C.warn else C.ink, maxLines = 1)
    }
}

/** Spreadsheet-style view with the 8 key columns; the bill discount sits right after the rate. */
@Composable
fun ItemTable(list: List<LineItem>, onOpen: (String) -> Unit, modifier: Modifier) {
    val cols = listOf(
        tr("आर्टिकल नं. और नाम", "Article No. & Name") to 116.dp, "MRP" to 60.dp, tr("असली रेट", "Original Rate") to 64.dp,
        "Original Bill Discount (%)" to 92.dp, tr("डिस्काउंट ₹", "Original Discount ₹") to 82.dp, tr("MRP पर डिस्काउंट %", "Calc. Discount vs MRP (%)") to 92.dp,
        tr("मात्रा", "Qty") to 52.dp, tr("लाइन का कुल", "Final Line Total") to 92.dp,
    )
    Column(modifier.padding(horizontal = 14.dp, vertical = 8.dp).horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(C.navy, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))) {
            cols.forEachIndexed { i, (h, w) -> TCell(h, w, header = true, orange = i == 3) }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            list.forEach { it ->
                val v = it.current
                Row(Modifier.background(C.card).clickable { onOpen(it.id) }) {
                    TCell("${v.sr ?: ""} ${v.article} ${v.name}".trim(), cols[0].second, left = true)
                    TCell(num(v.mrp), cols[1].second); TCell(num(v.rate), cols[2].second)
                    TCell(if (v.discPct == null) "Needs Verification" else pct(v.discPct), cols[3].second, orange = true, warn = v.discPct == null)
                    TCell(num(v.discAmt), cols[4].second); TCell(pct(Calc.mrpDiscPct(v)), cols[5].second)
                    TCell(qtyText(v.qty), cols[6].second); TCell(num(Calc.lineTotal(v)), cols[7].second)
                }
                Box(Modifier.width(cols.fold(0.dp) { a, c -> a + c.second }).height(1.dp).background(C.line))
            }
        }
    }
}

@Composable
private fun TCell(text: String, w: Dp, header: Boolean = false, orange: Boolean = false, warn: Boolean = false, left: Boolean = false) {
    Text(
        text, fontSize = if (header) 11.sp else 12.sp, lineHeight = 14.sp,
        fontWeight = if (header || orange) FontWeight.SemiBold else FontWeight.Normal,
        color = when { header -> Color.White; warn -> C.warn; orange -> C.orangeDark; else -> C.ink },
        textAlign = if (left) TextAlign.Start else TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(w).background(if (header && orange) C.orange else if (orange) C.orangeSoft else Color.Transparent).padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

// ------------------------------------------------------------------ edit one line

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheet(
    item: LineItem, onDismiss: () -> Unit, onSave: (ItemValues) -> Unit, onVerified: (Boolean) -> Unit, onRevert: () -> Unit,
    onRecalc: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
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
            Muted(tr("बिल पर छपा (स्कैन): ", "Printed on bill (scan): ") + (o.discPct?.let { pct(it) } ?: "Needs Verification") + " · " + tr("असली मान अलग सुरक्षित रहता है", "the original is kept separately"))
            OutlinedTextField(
                f.discPct, { f = f.copy(discPct = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Original Bill Discount (%)") }, suffix = { Text("%", color = C.orange, fontWeight = FontWeight.Bold) },
                textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.navy),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.orange, unfocusedBorderColor = C.orange),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Mini(tr("डिस्काउंट ₹", "Discount ₹"), num(preview.discAmt), C.navySoft, Modifier.weight(1f))
                Mini(tr("नेट रेट", "Net rate"), rs(Calc.netRate(preview)), C.orangeSoft, Modifier.weight(1f))
                Mini(tr("MRP पर %", "MRP %"), pct(Calc.mrpDiscPct(preview)), C.okSoft, Modifier.weight(1f))
            }
            Mini(tr("बिल छूट के बाद MRP पर असर वाला %", "Effective discount vs MRP after bill discount"), pct(Calc.effectiveMrpPct(preview)), C.navySoft, Modifier.fillMaxWidth())
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
