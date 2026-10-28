package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.model.Totals

/** One bill line for the goods check. */
data class CheckLine(val id: String, val no: String, val name: String, val mrp: String, val size: String, val qty: Int, val got: Int) {
    val left get() = (qty - got).coerceAtLeast(0)
    fun matches(q: String): Boolean {
        val t = q.trim(); if (t.isEmpty()) return true
        return no == t || listOf(name, mrp, size).any { it.contains(t, true) }
    }
}

fun checkLines(inv: Invoice, got: Map<String, Int>): List<CheckLine> =
    inv.items.filter { it.kind == ItemKind.ARTICLE }.mapIndexed { i, it -> line(it, i, got[it.id] ?: 0) }

private fun line(it: LineItem, i: Int, got: Int): CheckLine {
    val v = it.current
    val q = v.qty?.toInt()?.coerceAtLeast(1) ?: 1
    val name = listOf(v.article, v.name).filter { s -> s.isNotBlank() }.distinct().joinToString(" ").ifBlank { "—" }
    // Size only ("6X10", "6/2,7/3"), without the colour written before it.
    val size = v.sizeText.split(" | ").map { s -> s.trim().replace(Regex("""^[A-Za-z][A-Za-z ./-]*?\s+(?=\d)"""), "") }.filter { s -> s.any(Char::isDigit) }.joinToString(",")
    return CheckLine(it.id, "${v.sr ?: (i + 1)}", name, v.mrp?.stripTrailingZeros()?.toPlainString() ?: "", size, q, got.coerceIn(0, q))
}

/** Pairs received and pairs on the bill. */
fun checkCount(inv: Invoice, got: Map<String, Int>): Pair<Int, Int> = checkLines(inv, got).let { l -> l.sumOf { it.got } to l.sumOf { it.qty } }

private val Green = Color(0xFF1F7A3A)
private val GreenSoft = Color(0xFFE3F3E6)

// ---------------------------------------------------------------- after a scan: regular or goods check

@Composable
fun PickScreen(id: String, onRegular: () -> Unit, onCheck: () -> Unit) {
    val c = LocalContext.current.container
    val inv by remember(id) { c.repo.watch(id) }.collectAsState(initial = null)
    val t = inv?.let { Totals.of(it) }
    Column(Modifier.fillMaxSize().background(C.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(20.dp))
        Text(tr("बिल स्कैन हो गया", "Bill scanned"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.navy)
        if (inv != null && t != null) Muted("${inv?.header?.supplierName ?: "—"} · ${t.articleLines} ${tr("आर्टिकल", "articles")} · ${qtyText(t.qty)} ${tr("जोड़ी", "pairs")}")
        Spacer(Modifier.height(8.dp))
        PickCard(Icons.Outlined.Percent, C.orange, tr("रेगुलर जाँच", "Regular check"), tr("डिस्काउंट %, नया बिल, Excel / PDF", "Discount %, new bill, Excel / PDF"), C.line, onRegular)
        PickCard(Icons.Outlined.Checklist, Green, tr("माल चेक", "Goods check"), tr("आया हुआ माल टिक करो", "Tick the goods that arrived"), Green, onCheck)
        Muted(tr("बाद में भी बिल खोलकर दोनों कर सकते हैं।", "You can do both later from the bill too."))
    }
}

@Composable
private fun PickCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, sub: String, border: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(16.dp)).border(1.5.dp, border, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.navy)
            Text(sub, fontSize = 13.sp, color = C.muted)
        }
    }
}

// ---------------------------------------------------------------- the goods check screen

@Composable
fun CheckScreen(id: String, onBack: () -> Unit, onDone: () -> Unit = onBack) {
    val c = LocalContext.current.container
    val inv by remember(id) { c.repo.watch(id) }.collectAsState(initial = null)
    val bill = inv ?: return
    CheckContent(bill, c.checks.of(id), onSet = { line, n -> c.checks.set(id, line, n) }, onBack = onBack, onDone = onDone)
}

@Composable
fun CheckContent(inv: Invoice, got: Map<String, Int>, onSet: (String, Int) -> Unit, onBack: () -> Unit, onDone: () -> Unit = onBack) {
    val lines = checkLines(inv, got)
    var qp by rememberSaveable { mutableStateOf("") }
    var qf by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = lines.filter { it.left > 0 }
    val found = lines.filter { it.got > 0 }
    val total = lines.sumOf { it.qty }
    val done = lines.sumOf { it.got }
    var askDone by rememberSaveable { mutableStateOf(false) }
    if (askDone) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askDone = false },
        title = { Text(tr("माल चेक पूरा ✓", "Goods check complete ✓")) },
        text = { Text(tr("इस बिल का सारा माल ($done जोड़ी) मिल गया। बिल पर \"माल पूरा मिला\" लिखा रहेगा।", "All goods of this bill ($done pairs) arrived. The bill will show \"All goods received\".")) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { askDone = false; onDone() }) { Text(tr("होम पर जाएँ", "Go home"), color = Green) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { askDone = false }) { Text(tr("यहीं रहें", "Stay"), color = C.navy) } },
    )

    Column(Modifier.fillMaxSize().background(C.bg)) {
        // ---- header with progress
        Column(Modifier.fillMaxWidth().background(C.headerBrush).padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("वापस", "Back"), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("${inv.header.supplierName ?: "—"} · ${inv.header.invoiceNo ?: "—"}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("माल चेक", "Goods check"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (total > 0 && done >= total) Text(tr("पूरा मिला ✓", "All received ✓"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.background(Green, RoundedCornerShape(10.dp)).clickable { askDone = true }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Box(Modifier.padding(start = 12.dp, top = 4.dp).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(if (total > 0) done.toFloat() / total else 0f).fillMaxHeight().background(C.orange, RoundedCornerShape(3.dp)))
            }
            Text("$done / $total ${tr("जोड़ी मिली", "pairs received")}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 12.dp, top = 3.dp))
        }

        // ---- not received (bigger part)
        Section(Modifier.weight(0.62f), tr("माल नहीं मिला", "Not received"), "${pending.size} ${tr("आर्टिकल", "articles")}", C.orangeDark, C.orangeSoft,
            qp, { qp = it }, tr("नाम, MRP, साइज़ या नंबर", "Name, MRP, size or no.")) {
            val shown = pending.filter { it.matches(qp) }
            HeadRow()
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { "p" + it.id }) { l ->
                    if (editing == l.id) EditRow(l, onOk = { n -> onSet(l.id, l.got + n.coerceIn(0, l.left)); editing = null }, onCancel = { editing = null })
                    else LineRow(l, false, onClick = { onSet(l.id, l.qty); editing = null }, onEdit = { editing = l.id })
                }
                if (shown.isEmpty()) item { Empty(if (pending.isEmpty()) tr("सारा माल मिल गया", "Everything received") else tr("कुछ नहीं मिला", "Nothing found")) }
            }
        }
        // ---- received
        Section(Modifier.weight(0.38f).padding(bottom = 8.dp), tr("माल मिला", "Received"), "${found.size} ${tr("आर्टिकल", "articles")}", Green, GreenSoft,
            qf, { qf = it }, tr("मिले माल में खोजें", "Search received")) {
            val shown = found.filter { it.matches(qf) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { "f" + it.id }) { l -> LineRow(l, true, onClick = { onSet(l.id, 0) }, onEdit = {}) }
                if (shown.isEmpty()) item { Empty(if (found.isEmpty()) tr("ऊपर लाइन दबाओ, माल यहाँ आएगा", "Tap a line above; it comes here") else tr("कुछ नहीं मिला", "Nothing found")) }
            }
        }
    }
}

@Composable
private fun Section(
    modifier: Modifier, title: String, count: String, fg: Color, bg: Color, query: String, onQuery: (String) -> Unit, hint: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(horizontal = 8.dp).padding(top = 6.dp).fillMaxWidth().background(C.card, RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.weight(1f))
            Text(count, fontSize = 12.sp, color = fg)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).border(1.dp, C.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = C.muted, modifier = Modifier.size(16.dp))
            Box(Modifier.weight(1f).padding(start = 6.dp)) {
                if (query.isEmpty()) Text(hint, fontSize = 13.sp, color = C.muted)
                BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(fontSize = 13.sp, color = C.ink), modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotEmpty()) Icon(Icons.Outlined.Close, contentDescription = tr("साफ़ करें", "Clear"), tint = C.muted, modifier = Modifier.size(18.dp).clickable { onQuery("") })
        }
        content()
    }
}

private val W_CK = 18.dp; private val W_NO = 22.dp; private val W_MRP = 50.dp; private val W_SIZE = 46.dp; private val W_PCS = 46.dp; private val W_ED = 24.dp

@Composable
private fun Cell(text: String, w: Dp, color: Color = C.ink, bold: Boolean = false, end: Boolean = true) =
    Text(text, fontSize = 12.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = if (end) TextAlign.End else TextAlign.Start, modifier = Modifier.width(w))

@Composable
private fun HeadRow() {
    Row(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.width(W_CK)); Cell("#", W_NO, C.muted, end = false)
        Text(tr("आर्टिकल", "Article"), fontSize = 11.sp, color = C.muted, modifier = Modifier.weight(1f))
        Cell("MRP", W_MRP, C.muted); Cell(tr("साइज़", "Size"), W_SIZE, C.muted); Cell(tr("पीस", "Pcs"), W_PCS, C.muted); Spacer(Modifier.width(W_ED))
    }
}

@Composable
private fun Box16(on: Boolean) = Box(
    Modifier.size(16.dp).background(if (on) Green else Color.Transparent, RoundedCornerShape(4.dp)).border(1.3.dp, if (on) Green else C.muted.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
    contentAlignment = Alignment.Center,
) { if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }

@Composable
private fun LineRow(l: CheckLine, found: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(W_CK)) { Box16(found) }
        Cell(l.no, W_NO, C.muted, end = false)
        Text(l.name, fontSize = 12.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Cell(if (l.mrp.isEmpty()) "—" else "₹" + l.mrp, W_MRP)
        Cell(l.size.ifBlank { "—" }, W_SIZE, C.muted)
        if (found) Cell("${l.got}/${l.qty}", W_PCS, Green, bold = true) else Cell("${l.left}", W_PCS, bold = true)
        Box(Modifier.width(W_ED), contentAlignment = Alignment.Center) {
            if (!found) Icon(Icons.Outlined.Edit, contentDescription = tr("गिनती बदलें", "Change count"), tint = C.navy, modifier = Modifier.size(18.dp).clickable(onClick = onEdit))
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.line))
}

/** Part of a line arrived: type how many pieces came. */
@Composable
private fun EditRow(l: CheckLine, onOk: (Int) -> Unit, onCancel: () -> Unit) {
    var t by remember(l.id) { mutableStateOf("${l.left}") }
    val ok = { t.trim().toIntOrNull()?.let { if (it > 0) onOk(it) else onCancel() } ?: onCancel() }
    Row(Modifier.fillMaxWidth().background(C.orangeSoft).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${l.no}. ${l.name}", fontSize = 12.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(tr("कितने मिले?", "How many?"), fontSize = 11.sp, color = C.muted)
        BasicTextField(
            t, { s -> t = s.filter { it.isDigit() }.take(4) }, singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = C.ink, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { ok() }),
            modifier = Modifier.width(48.dp).background(C.card, RoundedCornerShape(6.dp)).border(1.dp, C.line, RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
        )
        Text("/ ${l.left}", fontSize = 12.sp, color = C.muted)
        Text("OK", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.background(C.orange, RoundedCornerShape(6.dp)).clickable { ok() }.padding(horizontal = 10.dp, vertical = 4.dp))
        Icon(Icons.Outlined.Close, contentDescription = tr("रद्द", "Cancel"), tint = C.muted, modifier = Modifier.size(18.dp).clickable(onClick = onCancel))
    }
}

@Composable
private fun Empty(text: String) = Text(text, fontSize = 12.sp, color = C.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(14.dp))

// ---------------------------------------------------------------- strip on the home card

@Composable
fun CheckStrip(got: Int, total: Int, onOpen: () -> Unit) {
    val full = total > 0 && got >= total
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().background(GreenSoft, RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Checklist, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(if (full) tr("माल पूरा मिला ✓", "All goods received ✓") else "${tr("माल मिला", "Received")} $got / $total ${tr("जोड़ी", "pairs")}", fontSize = 12.sp, color = Green, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(4.dp).background(Green.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(if (total > 0) got.toFloat() / total else 0f).fillMaxHeight().background(Green, RoundedCornerShape(2.dp)))
            }
        }
        Text(tr("खोलो", "Open"), fontSize = 12.sp, color = Color.White, modifier = Modifier.background(Green, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Green button on the home screen, beside "scan". */
@Composable
fun CheckHomeButton(modifier: Modifier, open: Int, onClick: () -> Unit) {
    Column(modifier.background(Green, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp)) {
        Icon(Icons.Outlined.Checklist, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(tr("माल चेक करें", "Goods check"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 4.dp))
        Text(if (open > 0) "$open ${tr("बिल अधूरे", "unfinished")}" else tr("बिल से माल मिलाओ", "Match goods to bill"), fontSize = 12.sp, color = Color.White.copy(alpha = 0.9f))
    }
}
