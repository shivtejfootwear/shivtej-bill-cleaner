package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import java.math.BigDecimal
import kotlinx.coroutines.launch

/** One bill line for the goods check. */
data class CheckLine(val id: String, val no: String, val name: String, val mrp: String, val size: String, val qty: Int, val got: Int, val rate: String = "") {
    val left get() = (qty - got).coerceAtLeast(0)
    fun matches(q: String): Boolean {
        val t = q.trim(); if (t.isEmpty()) return true
        return no == t || listOf(name, mrp, size, rate).any { it.contains(t, true) }
    }
}

fun checkLines(inv: Invoice, got: Map<String, Int>): List<CheckLine> =
    inv.items.filter { it.kind == ItemKind.ARTICLE }.mapIndexed { i, it -> line(it, i, got[it.id] ?: 0) }

private fun line(it: LineItem, i: Int, got: Int): CheckLine {
    val v = it.current
    val q = v.qty?.toInt()?.coerceAtLeast(1) ?: 1
    val name = listOf(v.article, v.name).filter { s -> s.isNotBlank() }.distinct().joinToString(" ").ifBlank { "—" }
    // Size only ("6X10", "6/2,7/3"), without the colour written before it.
    val size = v.sizeText.split(" | ").map { s -> s.trim().replace(Regex("""^[A-Za-z][A-Za-z ./-]*?\s+(?=\d)"""), "") }.filter { s -> s.any(Char::isDigit) && !s.contains("MRP", true) && !s.startsWith(":") }.joinToString(",")
    return CheckLine(it.id, "${v.sr ?: (i + 1)}", name, v.mrp?.stripTrailingZeros()?.toPlainString() ?: "", size, q, got.coerceIn(0, q), v.rate?.stripTrailingZeros()?.toPlainString() ?: "")
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
    var locked by remember { mutableStateOf(c.prefs.checkLocked) }
    val ctx = LocalContext.current
    CheckContent(bill, c.checks.of(id), onSet = { line, n -> c.checks.set(id, line, n) }, onBack = onBack, onDone = onDone,
        locked = locked, onLock = { on -> locked = on; c.prefs.checkLocked = on },
        onLockedTap = { android.widget.Toast.makeText(ctx, tr2(c.lang, "टिक बंद है — टिक करने के लिए 🔒 दबाएँ", "Ticking is locked — tap 🔒 to tick"), android.widget.Toast.LENGTH_SHORT).show() },
        onPdf = { received ->
            // The list as it is now: what arrived (pieces got) or what is still to come (pieces left).
            val now = checkLines(bill, c.checks.of(id)).filter { if (received) it.got > 0 else it.left > 0 }
            val rows = now.map { l ->
                val v = bill.items.firstOrNull { it.id == l.id }?.current
                com.shivtej.billscanner.export.Pdf.GoodsRow(l.no, v?.let { com.shivtej.billscanner.core.ocr.KachaBill.billOf(it) }.orEmpty().takeIf { s -> s.all(Char::isDigit) }.orEmpty(),
                    l.name, v?.colour.orEmpty(), l.size, v?.rate, v?.mrp, if (received) l.got else l.left, l.qty)
            }
            c.scope.launch {
                runCatching {
                    val name = com.shivtej.billscanner.core.export.InvoiceExport.fileBase(bill) + if (received) "_Maal_Mila.pdf" else "_Maal_Nahi_Mila.pdf"
                    val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        java.io.ByteArrayOutputStream().also { com.shivtej.billscanner.export.Pdf.goods(bill, rows, received, it) }.toByteArray()
                            .also { d -> Files.saveToDownloads(ctx, name, "application/pdf", d) }
                    }
                    Files.open(ctx, name, "application/pdf", data)
                }.onFailure { e -> android.widget.Toast.makeText(ctx, tr2(c.lang, "PDF नहीं बनी: ", "PDF failed: ") + e.message, android.widget.Toast.LENGTH_LONG).show() }
            }
        },
        onRate = { line, r ->
            // The rate from the company, typed in during the goods check: saved on the bill line (amount worked out again).
            val item = bill.items.firstOrNull { it.id == line }
            if (item != null) c.scope.launch { c.repo.save(com.shivtej.billscanner.core.model.Edits.updateItem(bill, line, com.shivtej.billscanner.core.model.Calc.recalc(item.current.copy(rate = r)), System.currentTimeMillis())) }
        })
}

@Composable
fun CheckContent(
    inv: Invoice, got: Map<String, Int>, onSet: (String, Int) -> Unit, onBack: () -> Unit, onDone: () -> Unit = onBack,
    locked: Boolean = false, onLock: (Boolean) -> Unit = {}, onLockedTap: () -> Unit = {}, onRate: (String, BigDecimal) -> Unit = { _, _ -> },
    onPdf: (Boolean) -> Unit = {},
) {
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

    val sToFound = tr("माल मिला में गया", "moved to received")
    val sCount = tr("गिनती बदली", "count changed")
    val sBack = tr("माल नहीं मिला में लौटा", "moved back to not received")
    // The last tick (or untick), for 5 seconds: "↩ वापस" puts the line back.
    var last by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    LaunchedEffect(last) { if (last != null) { kotlinx.coroutines.delay(5000); last = null } }
    fun move(l: CheckLine, to: Int, msg: String) {
        if (locked) { onLockedTap(); return }
        last = Triple(l.id, l.got, msg); onSet(l.id, to); editing = null
    }
    // Share of the height for "माल नहीं मिला" (the rest is "माल मिला"); 0 or 1 = one list on the whole screen.
    var frac by rememberSaveable { mutableStateOf(0.62f) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        // ---- header with progress
        Column(Modifier.fillMaxWidth().background(C.headerBrush).padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("वापस", "Back"), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("${inv.header.supplierName ?: "—"} · ${inv.header.invoiceNo ?: "—"}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr("माल चेक", "Goods check"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Row(Modifier.padding(end = 6.dp).background(if (locked) C.orange else Green, RoundedCornerShape(14.dp)).clickable { onLock(!locked) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(if (locked) tr(" टिक बंद", " Locked") else tr(" टिक चालू", " Ticking on"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                if (total > 0 && done >= total) Text(tr("पूरा मिला ✓", "All received ✓"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.background(Green, RoundedCornerShape(10.dp)).clickable { askDone = true }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Box(Modifier.padding(start = 12.dp, top = 4.dp).fillMaxWidth().height(5.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(if (total > 0) done.toFloat() / total else 0f).fillMaxHeight().background(C.orange, RoundedCornerShape(3.dp)))
            }
            Text("$done / $total ${tr("जोड़ी मिली", "pairs received")}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 12.dp, top = 3.dp))
        }

        if (locked) Text(tr("🔒 देखने का मोड — किसी लाइन पर टिक नहीं होगा", "🔒 Look-only mode — no line gets ticked"), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = C.orangeDark, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(C.orangeSoft).padding(vertical = 5.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val handle = 26.dp; val bar = 36.dp
            val free = maxHeight - handle
            val topH = when {
                frac <= 0f -> bar
                frac >= 1f -> free - bar
                else -> (free * frac).coerceIn(bar, maxOf(bar, free - bar))
            }
            val freePx = with(density) { free.toPx() }.coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                // ---- not received
                Section(Modifier.height(topH), tr("माल नहीं मिला", "Not received"), "${pending.size} ${tr("आर्टिकल", "articles")}", C.orangeDark, C.orangeSoft,
                    qp, { qp = it }, tr("नाम, रेट, MRP, साइज़ या नंबर", "Name, rate, MRP, size or no."), collapsed = frac <= 0f, onExpand = { frac = 0.5f }, onPdf = { onPdf(false) }) {
                    val shown = pending.filter { it.matches(qp) }
                    HeadRow()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { "p" + it.id }) { l ->
                            if (editing == l.id) EditRow(l, l.left, onOk = { n, r -> r?.let { onRate(l.id, it) }; if (n > 0) move(l, l.got + n.coerceIn(0, l.left), "✓ ${l.name} " + sToFound); editing = null }, onCancel = { editing = null })
                            else LineRow(l, false, locked, onClick = { move(l, l.qty, "✓ ${l.name} " + sToFound) }, onEdit = { if (locked) onLockedTap() else editing = l.id })
                        }
                        if (shown.isEmpty()) item { Empty(if (pending.isEmpty()) tr("सारा माल मिल गया", "Everything received") else tr("कुछ नहीं मिला", "Nothing found")) }
                    }
                }
                // ---- the bar between the two lists: drag up = "माल मिला" on the whole screen, down = "माल नहीं मिला"
                Column(
                    Modifier.fillMaxWidth().height(handle).draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> frac = (frac.coerceIn(0.02f, 0.98f) + d / freePx).coerceIn(0f, 1f) },
                        onDragStopped = { frac = when { frac < 0.12f -> 0f; frac > 0.88f -> 1f; else -> frac } },
                    ).clickable { frac = if (frac in 0.3f..0.7f) 0.62f else 0.5f },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.width(54.dp).height(5.dp).background(C.muted.copy(alpha = 0.6f), RoundedCornerShape(3.dp)))
                    Text(tr("⇕ ऊपर-नीचे खींचें", "⇕ Drag up or down"), fontSize = 9.sp, color = C.muted)
                }
                // ---- received
                Section(Modifier.weight(1f).padding(bottom = 8.dp), tr("माल मिला", "Received"), "${found.size} ${tr("आर्टिकल", "articles")}", Green, GreenSoft,
                    qf, { qf = it }, tr("मिले माल में खोजें", "Search received"), collapsed = frac >= 1f, onExpand = { frac = 0.5f }, onPdf = { onPdf(true) }) {
                    val shown = found.filter { it.matches(qf) }
                    HeadRow()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { "f" + it.id }) { l ->
                            if (editing == "f" + l.id) EditRow(l, l.got, onOk = { n, r -> r?.let { onRate(l.id, it) }; if (n != l.got) move(l, n.coerceIn(0, l.qty), "↩ ${l.name} " + sCount); editing = null }, onCancel = { editing = null })
                            else LineRow(l, true, locked, onClick = { move(l, 0, "↩ ${l.name} " + sBack) }, onEdit = { if (locked) onLockedTap() else editing = "f" + l.id })
                        }
                        if (shown.isEmpty()) item { Empty(if (found.isEmpty()) tr("ऊपर लाइन दबाओ, माल यहाँ आएगा", "Tap a line above; it comes here") else tr("कुछ नहीं मिला", "Nothing found")) }
                    }
                }
            }
        }
    }
    // ---- "↩ वापस" for 5 seconds after a tick
    last?.let { (lid, before, msg) ->
        Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().background(C.navy, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(msg, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(tr("↩ वापस", "↩ Undo"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.orange,
                modifier = Modifier.clickable { onSet(lid, before); last = null }.padding(start = 12.dp))
        }
    }
    }
}

@Composable
private fun Section(
    modifier: Modifier, title: String, count: String, fg: Color, bg: Color, query: String, onQuery: (String) -> Unit, hint: String,
    collapsed: Boolean = false, onExpand: () -> Unit = {}, onPdf: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(horizontal = 8.dp).padding(top = 6.dp).fillMaxWidth().background(C.card, RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(if (collapsed) 10.dp else 0.dp)).then(if (collapsed) Modifier.clickable(onClick = onExpand) else Modifier).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
            Text(count, fontSize = 12.sp, color = fg, modifier = Modifier.align(Alignment.CenterVertically))
            // This list as a PDF, as it stands now (works even when the list is closed to its title).
            if (onPdf != null) Text("PDF", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.padding(start = 8.dp).background(fg, RoundedCornerShape(6.dp)).clickable(onClick = onPdf).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        if (collapsed) return@Column
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

private val W_CK = 18.dp; private val W_NO = 22.dp; private val W_RATE = 44.dp; private val W_MRP = 44.dp; private val W_SIZE = 42.dp; private val W_PCS = 38.dp; private val W_ED = 24.dp

@Composable
private fun Cell(text: String, w: Dp, color: Color = C.ink, bold: Boolean = false, end: Boolean = true) =
    Text(text, fontSize = 12.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = if (end) TextAlign.End else TextAlign.Start, modifier = Modifier.width(w))

@Composable
private fun HeadRow() {
    Row(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.width(W_CK)); Cell("#", W_NO, C.muted, end = false)
        Text(tr("आर्टिकल", "Article"), fontSize = 11.sp, color = C.muted, modifier = Modifier.weight(1f))
        Cell(tr("रेट", "Rate"), W_RATE, C.orangeDark); Cell("MRP", W_MRP, C.muted); Cell(tr("साइज़", "Size"), W_SIZE, C.muted); Cell(tr("पीस", "Pcs"), W_PCS, C.muted); Spacer(Modifier.width(W_ED))
    }
}

@Composable
private fun Box16(on: Boolean, faded: Boolean = false) = Box(
    Modifier.size(16.dp).background(if (on) Green.copy(alpha = if (faded) 0.35f else 1f) else Color.Transparent, RoundedCornerShape(4.dp))
        .border(1.3.dp, if (on) Green.copy(alpha = if (faded) 0.35f else 1f) else C.muted.copy(alpha = if (faded) 0.25f else 0.5f), RoundedCornerShape(4.dp)),
    contentAlignment = Alignment.Center,
) { if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) }

@Composable
private fun LineRow(l: CheckLine, found: Boolean, locked: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(W_CK).then(if (locked) Modifier.background(Color.Transparent) else Modifier)) { Box16(found, faded = locked) }
        Cell(l.no, W_NO, C.muted, end = false)
        Text(l.name, fontSize = 12.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Cell(if (l.rate.isEmpty()) "—" else "₹" + l.rate, W_RATE, C.orangeDark, bold = true)
        Cell(if (l.mrp.isEmpty()) "—" else "₹" + l.mrp, W_MRP)
        Cell(l.size.ifBlank { "—" }, W_SIZE, C.muted)
        if (found) Cell("${l.got}/${l.qty}", W_PCS, Green, bold = true) else Cell("${l.left}", W_PCS, bold = true)
        Box(Modifier.width(W_ED), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Edit, contentDescription = tr("गिनती बदलें", "Change count"), tint = C.navy, modifier = Modifier.size(18.dp).clickable(onClick = onEdit))
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.line))
}

/** Part of a line arrived: type how many pieces came, and the rate from the company if it is missing or different. */
@Composable
private fun EditRow(l: CheckLine, start: Int, onOk: (Int, BigDecimal?) -> Unit, onCancel: () -> Unit) {
    var t by remember(l.id) { mutableStateOf("$start") }
    var r by remember(l.id) { mutableStateOf(l.rate) }
    val ok = {
        val n = t.trim().toIntOrNull() ?: start
        val rate = r.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.stripTrailingZeros().toPlainString() != l.rate }
        onOk(n, rate)
    }
    Column(Modifier.fillMaxWidth().background(C.orangeSoft).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text("${l.no}. ${l.name}", fontSize = 12.sp, color = C.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("कितने मिले?", "How many?"), fontSize = 11.sp, color = C.muted)
            BasicTextField(
                t, { s -> t = s.filter { it.isDigit() }.take(4) }, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = C.ink, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.width(44.dp).background(C.card, RoundedCornerShape(6.dp)).border(1.dp, C.line, RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
            )
            Text("/ ${l.qty}", fontSize = 12.sp, color = C.muted)
            Text(tr("रेट ₹", "Rate ₹"), fontSize = 11.sp, color = C.orangeDark)
            BasicTextField(
                r, { s -> r = s.filter { it.isDigit() || it == '.' }.take(7) }, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = C.ink, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { ok() }),
                modifier = Modifier.width(60.dp).background(C.card, RoundedCornerShape(6.dp)).border(1.dp, C.line, RoundedCornerShape(6.dp)).padding(vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("OK", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.background(C.orange, RoundedCornerShape(6.dp)).clickable { ok() }.padding(horizontal = 10.dp, vertical = 4.dp))
            Icon(Icons.Outlined.Close, contentDescription = tr("रद्द", "Cancel"), tint = C.muted, modifier = Modifier.size(18.dp).clickable(onClick = onCancel))
        }
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
