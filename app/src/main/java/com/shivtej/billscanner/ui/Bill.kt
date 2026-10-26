package com.shivtej.billscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.export.InvoiceExport
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Column as BillColumn
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.model.Target
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warning
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.InvoiceParser.Kind as K
import com.shivtej.billscanner.teachColumn
import com.shivtej.billscanner.reread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * The bill, as one sheet: every column as printed, the green "बैठा %" beside it, and — once a
 * target % is set — the new rate and amount. Tap a line to edit it; tap a column heading to say what it is.
 */
@Composable
fun BillScreen(id: String, onBack: () -> Unit, onTargets: () -> Unit, onExport: () -> Unit, onPhoto: () -> Unit, onView: () -> Unit = {}, onFix: () -> Unit = {}, onCheck: () -> Unit = {}) {
    val ctxApp = LocalContext.current.applicationContext
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
    var editing by remember { mutableStateOf<String?>(null) }
    var editHeader by remember { mutableStateOf(false) }
    var askVerify by remember { mutableStateOf(false) }
    var askDelete by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var teach by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val warnings = Warnings.of(inv, previous)
    // Lines the scan did not understand: asked about once, as soon as the bill opens.
    var askFix by remember(inv.id) { mutableStateOf(inv.id !in askedFix && doubtful(inv).isNotEmpty()) }
    if (askFix) DoubtfulDialog(inv, onFix = { askedFix += inv.id; askFix = false; onFix() }, onLater = { askedFix += inv.id; askFix = false })

    BillContent(
        inv, warnings, thumb, onBack = onBack, onTargets = onTargets, onExport = onExport, onPhoto = onPhoto, onView = onView, onCheck = onCheck,
        onRow = { editing = it }, onColumn = { teach = it }, onEditHeader = { editHeader = true },
        onVerify = {
            if (inv.status == InvoiceStatus.VERIFIED) ed.change { Edits.setStatus(it, InvoiceStatus.DRAFT, System.currentTimeMillis()) } else askVerify = true
        },
        onHistory = { history = true }, onDelete = { askDelete = true },
        onAdd = { ed.change { Edits.addItem(it, System.currentTimeMillis()) } },
        onGst = { on -> ed.change { Edits.setSittingWithGst(it, on, System.currentTimeMillis()) } },
        canUndo = ed.canUndo, onUndo = { ed.undo() }, message = message, onFix = onFix,
        onSendClaude = { scope.launch { withContext(Dispatchers.IO) { runCatching { Files.shareForClaude(ctxApp, inv) } } } },
        onReread = {
            scope.launch {
                val r = c.reread(inv)
                message = if (r == null) tr2(c.lang, "इस बिल की स्कैन जानकारी नहीं मिली — नया स्कैन करें।", "This bill's scan data is not available — scan again.")
                else {
                    val sure = r.items.count { it.kind == ItemKind.ARTICLE && com.shivtej.billscanner.core.ocr.Heal.sure(it) }
                    tr2(c.lang, "🔄 दोबारा पढ़ा — $sure/${r.items.count { it.kind == ItemKind.ARTICLE }} लाइन पक्की", "🔄 Read again — $sure/${r.items.count { it.kind == ItemKind.ARTICLE }} lines add up")
                }
            }
        },
    )

    editing?.let { itemId ->
        val item = inv.items.firstOrNull { it.id == itemId }
        // The printed line from the bill's own photo/PDF page, shown above the fields while fixing it.
        val strip by produceState<Bitmap?>(null, itemId) { value = withContext(Dispatchers.IO) { item?.let { lineStrip(inv, it) } } }
        if (item == null) editing = null else ItemSheet(
            item, onDismiss = { editing = null },
            onSave = { v ->
                // The scanner learns from the fix: the same misreading on this supplier's next bills is corrected by itself.
                val (learnt, lessons) = com.shivtej.billscanner.core.ocr.Learning.fromEdit(c.prefs.learnt, inv, item!!, v)
                if (lessons.isNotEmpty()) { c.prefs.learnt = learnt; message = tr2(c.lang, "🧠 सीख लिया (अगले बिल में खुद ठीक होगा): ", "🧠 Learnt (fixed by itself next time): ") + lessons.joinToString(" · ") }
                ed.change { Edits.updateItem(it, itemId, v, System.currentTimeMillis()) }
            },
            onVerified = { on -> ed.change { Edits.setVerified(it, itemId, on, System.currentTimeMillis()) } },
            onRevert = { ed.change { Edits.revertItem(it, itemId, System.currentTimeMillis()) } },
            onRecalc = { ed.change { Edits.recalcItem(it, itemId, System.currentTimeMillis()) } },
            onDuplicate = { ed.change { Edits.duplicateItem(it, itemId, System.currentTimeMillis()) }; editing = null },
            onDelete = {
                val before = c.prefs.learnt
                val after = com.shivtej.billscanner.core.ocr.Learning.fromDelete(before, inv, item!!)
                if (after != before) { c.prefs.learnt = after; message = tr2(c.lang, "🧠 सीख लिया: ऐसी लाइन अगली बार इस पार्टी के बिल में नहीं आएगी", "🧠 Learnt: such a line is skipped next time") }
                ed.change { Edits.deleteItem(it, itemId, System.currentTimeMillis()) }; editing = null
            },
            header = inv.header, withGst = inv.sittingWithGst, target = Target.pct(inv, item.copy(targetPct = null)),
            onTarget = { p -> ed.change { Edits.setTarget(it, itemId, p, System.currentTimeMillis()) } },
            strip = strip,
        )
    }
    teach?.let { ci ->
        val col = InvoiceExport.printedColumns(inv).getOrNull(ci)
        if (col == null) teach = null else TeachDialog(col, onDismiss = { teach = null }) { kind ->
            teach = null
            scope.launch {
                val r = c.teachColumn(inv, col.label, kind)
                message = if (r == null) tr2(c.lang, "इस बिल की स्कैन जानकारी नहीं मिली — नया स्कैन करें।", "This bill's scan data is not available — scan again.")
                else tr2(c.lang, "याद रख लिया ✓ — \"${col.label}\" अब इस सप्लायर के हर बिल में ऐसे ही पढ़ा जाएगा।", "Learnt ✓ — \"${col.label}\" will be read this way on this supplier's bills.")
            }
        }
    }
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
        confirmButton = { TextButton(onClick = { askDelete = false; scope.launch { c.repo.delete(inv.id); c.pages.deleteAll(inv.id); c.checks.clear(inv.id); onBack() } }) { Text(tr("हटाएँ", "Delete"), color = C.err) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text(tr("नहीं", "No")) } },
    )
    if (history) HistoryDialog(inv) { history = false }
}

@Composable
fun HistoryDialog(inv: Invoice, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
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
    confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
)

/** "What is this column?" — the answer is remembered for this supplier. */
@Composable
fun TeachDialog(col: BillColumn, onDismiss: () -> Unit, onPick: (InvoiceParser.Kind) -> Unit) {
    val choices = listOf(
        K.MRP to "MRP", K.QTY to tr("मात्रा (Qty)", "Quantity"), K.RATE to tr("रेट", "Rate"), K.DISC_PCT to tr("डिस्काउंट %", "Discount %"),
        K.DISC_AMT to tr("डिस्काउंट ₹", "Discount ₹"), K.AMOUNT to tr("रकम (Amount, GST से पहले)", "Amount (before GST)"), K.GROSS to tr("Total (मात्रा × रेट)", "Total (qty × rate)"),
        K.TAXABLE to "Taxable", K.GST_PCT to "GST %", K.SIZE to tr("साइज़", "Size"), K.COLOUR to tr("कलर", "Colour"), K.HSN to "HSN",
        K.DESC to tr("आर्टिकल / नाम", "Article / name"), K.SR to tr("क्रम (Sr)", "Serial no."), K.OTHER to tr("कुछ नहीं — छोड़ दें", "Nothing — ignore"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("\"${col.label}\" कॉलम क्या है?", "What is the \"${col.label}\" column?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted(tr("ऐप इसी सप्लायर के आगे के हर बिल में यह याद रखेगा। यह बिल अभी दोबारा पढ़ा जाएगा।", "The app remembers this for this supplier's next bills. This bill is read again now."))
                choices.forEach { (k, label) ->
                    val now = InvoiceParser.fieldOf(k) == col.field && (k != K.OTHER || col.field == null)
                    Text((if (now) "● " else "○ ") + label, fontSize = 15.sp, color = if (now) C.orange else C.navy, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(k) }.padding(vertical = 9.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("रुकें", "Cancel")) } },
    )
}

@Composable
fun BillContent(
    inv: Invoice, warnings: List<Warning>, thumb: Bitmap?, onBack: () -> Unit, onTargets: () -> Unit, onExport: () -> Unit, onPhoto: () -> Unit,
    onRow: (String) -> Unit = {}, onColumn: (Int) -> Unit = {}, onEditHeader: () -> Unit = {}, onVerify: () -> Unit = {}, onHistory: () -> Unit = {},
    onDelete: () -> Unit = {}, onAdd: () -> Unit = {}, onGst: (Boolean) -> Unit = {}, canUndo: Boolean = false, onUndo: () -> Unit = {},
    message: String? = null, showWarnings: Boolean = false, onView: () -> Unit = {}, onFix: () -> Unit = {},
    onSendClaude: () -> Unit = {}, onReread: () -> Unit = {}, onCheck: () -> Unit = {},
) {
    val h = inv.header
    val t = Totals.of(inv)
    val open = warnings.filter { it.level != Warning.Level.TIP }
    var showW by remember { mutableStateOf(showWarnings) }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(h.supplierName ?: tr("सप्लायर नहीं पढ़ा गया", "Supplier not read"), onBack) {
            if (canUndo) TextButton(onClick = onUndo) { Text("Undo", color = C.navy) }
            IconButton(onClick = onCheck) { Icon(Icons.Outlined.Checklist, contentDescription = tr("माल चेक", "Goods check"), tint = C.ok) }
            IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, contentDescription = tr("इतिहास", "History"), tint = C.navy) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = tr("हटाएँ", "Delete"), tint = C.muted) }
        }
        // ---- summary
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp, 58.dp).background(Color.White, RoundedCornerShape(6.dp)).border(1.dp, C.line, RoundedCornerShape(6.dp)).clickable(onClick = onPhoto)) {
                    thumb?.let { Image(it.asImageBitmap(), contentDescription = tr("असली फ़ोटो", "Original photo"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(tr("बिल ", "Bill ") + (h.invoiceNo ?: "?") + " · " + (h.invoiceDate ?: "?"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.navy)
                    Text("${t.articleLines} " + tr("आर्टिकल", "articles") + " · ${qtyText(t.qty)} " + tr("जोड़ी", "pairs") + " · " + rs(t.printedGrand ?: t.grand), fontSize = 13.sp, color = C.muted)
                    val arts = inv.items.filter { it.kind == ItemKind.ARTICLE }
                    val sure = arts.count { com.shivtej.billscanner.core.ocr.Heal.sure(it) }
                    if (arts.isNotEmpty()) Text(
                        "✓ $sure/${arts.size} " + tr("लाइन का हिसाब पक्का", "lines add up") + if (sure < arts.size) " · ⚠ ${arts.size - sure} " + tr("ठीक करें ›", "fix ›") else "",
                        fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (sure == arts.size) C.ok else C.warn,
                        modifier = if (sure < arts.size) Modifier.clickable(onClick = onFix) else Modifier,
                    )
                    val d = t.difference
                    when {
                        t.printedGrand == null -> Pill(tr("बिल का Total नहीं पढ़ा", "Bill total not read"), C.warn, C.warnSoft)
                        d != null && d.abs() <= BigDecimal("0.99") -> Pill(tr("✓ Total बिल से मिला", "✓ Matches the bill total"), C.ok, C.okSoft)
                        else -> Pill(tr("फ़र्क़ ", "Difference ") + rs(d), C.err, C.errSoft)
                    }
                }
                IconButton(onClick = onEditHeader) { Icon(Icons.Outlined.Edit, contentDescription = tr("बदलें", "Edit"), tint = C.navy) }
            }
            Row(Modifier.fillMaxWidth().background(C.okSoft, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("पूरे बिल पर बैठा %", "Baitha % on the whole bill"), fontSize = 12.sp, color = C.ok)
                    Text(pct(Calc.sittingTotal(inv)), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.ok)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(tr("MRP से तुलना", "Compared with MRP"), fontSize = 11.sp, color = C.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SmallToggle(tr("GST सहित", "With GST"), inv.sittingWithGst) { onGst(true) }
                        SmallToggle(tr("GST बिना", "No GST"), !inv.sittingWithGst) { onGst(false) }
                    }
                }
            }
            if (open.isNotEmpty() || message != null) Column(Modifier.fillMaxWidth()) {
                message?.let { Text(it, fontSize = 13.sp, color = C.navy, modifier = Modifier.fillMaxWidth().background(C.navySoft, RoundedCornerShape(10.dp)).padding(8.dp)) }
                if (open.isNotEmpty()) {
                    Text((if (showW) "▾ " else "▸ ") + tr("${open.size} जगह जाँचें", "${open.size} to check"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.warn,
                        modifier = Modifier.fillMaxWidth().clickable { showW = !showW }.padding(vertical = 4.dp))
                    if (showW) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) { open.forEach { WarningRow(it, onOpen = onRow) } }
                }
            }
        }
        // ---- the sheet
        BillGrid(inv, onRow, onColumn, Modifier.weight(1f).padding(top = 8.dp))
        // ---- actions
        Column(Modifier.fillMaxWidth().background(C.card).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton(tr("% लगाएँ", "Set %"), onTargets, Modifier.weight(1f), orange = true, compact = true)
                BigButton(tr("🔍 पूरा बिल", "🔍 Whole bill"), onView, Modifier.weight(1.1f), compact = true)
                BigButton("Excel/PDF", onExport, Modifier.weight(1f), compact = true)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null, tint = C.navy); Text(tr(" लाइन जोड़ें", " Add line"), color = C.navy) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onVerify) {
                    Text(if (inv.status == InvoiceStatus.VERIFIED) tr("Verified ✓ (हटाएँ)", "Verified ✓ (undo)") else tr("जाँचकर Verified करें", "Mark verified"),
                        color = if (inv.status == InvoiceStatus.VERIFIED) C.ok else C.navy, fontWeight = FontWeight.SemiBold)
                }
            }
            // A bill the reader did not understand: sent to Claude for a pattern code, then read again with it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSendClaude) { Text(tr("📤 समझ नहीं आया? Claude को भेजें", "📤 Not understood? Send to Claude"), color = C.orangeDark, fontSize = 13.sp) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onReread) { Text(tr("🔄 दोबारा पढ़ें", "🔄 Read again"), color = C.navy, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SmallToggle(text: String, on: Boolean, onClick: () -> Unit) = Text(
    text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else C.ok,
    modifier = Modifier.background(if (on) C.ok else Color.White, RoundedCornerShape(8.dp)).border(1.dp, C.ok, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
)

// ================================================================ Excel-like grid

private val FROZEN = 120.dp

/** On the phone the columns you decide with come first: MRP, rate, bill %, बैठा %, then the rest in bill order. */
private val FIRST = listOf(F.MRP, F.RATE, F.DISC_PCT)
private val AFTER_BAITHA = listOf(F.QTY, F.TAXABLE, F.TOTAL)

private fun widthOf(f: F?): Dp = when (f) {
    F.SR -> 36.dp; F.HSN -> 76.dp; F.GST_PCT -> 50.dp; F.SIZE -> 64.dp; F.COLOUR -> 84.dp; F.MRP -> 60.dp; F.QTY -> 58.dp
    F.RATE -> 66.dp; F.GROSS -> 78.dp; F.DISC_PCT -> 58.dp; F.DISC_AMT -> 70.dp; F.TAXABLE -> 84.dp; F.TOTAL -> 84.dp
    F.CGST_PCT, F.SGST_PCT, F.IGST_PCT -> 52.dp; F.CGST_AMT, F.SGST_AMT, F.IGST_AMT -> 66.dp; else -> 74.dp
}

private enum class Tone { NORMAL, ORANGE, GREEN, TARGET }

@Composable
private fun HeadCell(text: String, w: Dp, tone: Tone, onClick: (() -> Unit)? = null) = Text(
    text, fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis,
    textAlign = TextAlign.Center,
    modifier = Modifier.width(w).height(42.dp)
        .background(when (tone) { Tone.ORANGE -> C.orange; Tone.GREEN -> C.ok; Tone.TARGET -> C.orangeDark; Tone.NORMAL -> C.navy })
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 3.dp, vertical = 4.dp),
)

@Composable
private fun DataCell(text: String, w: Dp, tone: Tone, right: Boolean, warn: Boolean = false, edited: Boolean = false, bold: Boolean = false) = Text(
    text, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    fontWeight = if (bold || tone == Tone.GREEN || tone == Tone.ORANGE) FontWeight.SemiBold else FontWeight.Normal,
    color = when { warn -> C.warn; edited -> C.orangeDark; tone == Tone.GREEN -> C.ok; tone == Tone.ORANGE -> C.orangeDark; tone == Tone.TARGET -> C.navy; else -> C.ink },
    textAlign = if (right) TextAlign.End else TextAlign.Start,
    modifier = Modifier.width(w).background(
        when { warn -> C.warnSoft; tone == Tone.GREEN -> C.okSoft; tone == Tone.ORANGE -> C.orangeSoft; tone == Tone.TARGET -> Color(0xFFFFF7D6); else -> Color.Transparent },
    ).padding(horizontal = 5.dp, vertical = 8.dp),
)

@Composable
fun BillGrid(inv: Invoice, onRow: (String) -> Unit, onColumn: (Int) -> Unit, modifier: Modifier = Modifier, hs: ScrollState = rememberScrollState()) {
    val cols = InvoiceExport.printedColumns(inv)
    val frozen = cols.indices.filter { cols[it].field == F.SR || cols[it].field == F.ARTICLE }
    val others = cols.indices - frozen.toSet()
    // The bill's own columns in the bill's order; then ours: बैठा %, अपना %, नई रकम.
    val first = others
    val after = emptyList<Int>()
    val tail = emptyList<Int>()
    val sheet = Target.sheet(inv)
    val lines = sheet.lines.associateBy { it.item.id }
    val t = Totals.of(inv)
    Column(modifier.padding(horizontal = 12.dp).background(C.card, RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp))) {
        Row {
            HeadCell(tr("आर्टिकल (टैप = बदलें)", "Article (tap = edit)"), FROZEN, Tone.NORMAL)
            Row(Modifier.horizontalScroll(hs)) {
                @Composable fun head(ci: Int) = HeadCell(cols[ci].label + " ▾", widthOf(cols[ci].field), if (cols[ci].field == F.DISC_PCT) Tone.ORANGE else Tone.NORMAL) { onColumn(ci) }
                first.forEach { head(it) }
                HeadCell(tr("बैठा %", "Baitha %"), 62.dp, Tone.GREEN)
                if (sheet.any) HeadCell(tr("अपना %", "Our %"), 60.dp, Tone.TARGET)
                if (sheet.any) HeadCell(tr("नई रकम", "New amount"), 84.dp, Tone.TARGET)
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(inv.items, key = { it.id }) { item ->
                GridRow(inv, item, cols, frozen, first, after, tail, lines[item.id], sheet.any, hs) { onRow(item.id) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            }
            item {
                Row(Modifier.background(C.navySoft)) {
                    DataCell(tr("कुल", "Total"), FROZEN, Tone.NORMAL, false, bold = true)
                    Row(Modifier.horizontalScroll(hs)) {
                        @Composable fun tot(ci: Int) {
                            val f = cols[ci].field
                            val v = when (f) { F.QTY -> qtyText(t.qty); F.TAXABLE -> num(t.taxable); F.GROSS -> num(t.gross); F.DISC_AMT -> num(t.discount); else -> "" }
                            DataCell(v, widthOf(f), if (f == F.DISC_PCT) Tone.ORANGE else Tone.NORMAL, true, bold = true)
                        }
                        first.forEach { tot(it) }
                        DataCell(pct(Calc.sittingTotal(inv)), 62.dp, Tone.GREEN, true, bold = true)
                        if (sheet.any) DataCell("", 60.dp, Tone.TARGET, true)
                        if (sheet.any) DataCell(num(if (Target.inclusive(inv)) sheet.total else sheet.taxable), 84.dp, Tone.TARGET, true, bold = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridRow(
    inv: Invoice, item: LineItem, cols: List<BillColumn>, frozen: List<Int>, first: List<Int>, after: List<Int>, tail: List<Int>,
    line: Target.Line?, anyTarget: Boolean, hs: ScrollState, onClick: () -> Unit,
) {
    val warnRow = item.kind == ItemKind.ARTICLE && !com.shivtej.billscanner.core.ocr.Heal.sure(item)
    Row(Modifier.clickable(onClick = onClick)) {
        val left = frozen.joinToString(" ") { InvoiceExport.printedText(inv, item, it, cols[it]) }.trim()
            .ifEmpty { "${item.current.sr ?: ""} ${item.current.article}".trim() } + if (item.kind == ItemKind.CHARGE) tr(" (चार्ज)", " (charge)") else ""
        // ✓ = the line's own numbers add up (pairs × rate − discount = amount); ⚠ = look at it.
        val mark = when {
            item.kind != ItemKind.ARTICLE -> ""
            com.shivtej.billscanner.core.ocr.Heal.sure(item) -> "✓ "
            item.kind == ItemKind.ARTICLE -> "⚠ "
            else -> ""
        }
        DataCell(mark + left, FROZEN, Tone.NORMAL, false, warn = warnRow)
        Row(Modifier.horizontalScroll(hs)) {
            @Composable fun cell(ci: Int) {
                val col = cols[ci]
                val text = InvoiceExport.printedText(inv, item, ci, col).ifEmpty { if (col.field == F.DISC_PCT && item.current.discPct == null && F.DISC_PCT in item.needs) "Needs Verif." else "" }
                val isNum = col.field != null && col.field !in setOf(F.SIZE, F.COLOUR, F.HSN, F.ARTICLE)
                DataCell(text, widthOf(col.field), if (col.field == F.DISC_PCT) Tone.ORANGE else Tone.NORMAL, isNum,
                    warn = col.field != null && col.field in item.needs, edited = InvoiceExport.isEdited(item, col))
            }
            first.forEach { cell(it) }
            val s = Calc.sittingPct(item.current, inv.header, inv.sittingWithGst)
            DataCell(if (item.kind == ItemKind.CHARGE) "" else pct(s), 62.dp, Tone.GREEN, true, warn = s == null && item.kind == ItemKind.ARTICLE)
            if (anyTarget) DataCell(line?.let { pct(it.pct) + if (item.targetPct != null) "*" else "" } ?: "—", 60.dp, Tone.TARGET, true)
            // Lines without our % keep the bill amount (shown plain).
            if (anyTarget) DataCell(line?.let { num(Target.newShown(inv, it)) } ?: Target.keptShown(inv, item)?.let { num(it) }.orEmpty(), 84.dp, if (line != null) Tone.TARGET else Tone.NORMAL, true, bold = line != null)
        }
    }
}

/** The strip of the bill page holding [item] (with a little of the lines around it), or null if not known. */
fun lineStrip(inv: Invoice, item: LineItem): Bitmap? = runCatching {
    val a = item.at ?: return null
    val path = inv.pages.getOrNull(a.page) ?: return null
    val page = com.shivtej.billscanner.ocr.Ocr.loadBitmap(java.io.File(path))
    // The scan's size and this picture's must be the same page the same way up.
    val k = page.width.toDouble() / a.pageW
    if (a.pageW <= 0 || kotlin.math.abs(page.height / k - a.pageH) > a.pageH * 0.03) { page.recycle(); return null }
    val h = (a.b - a.t).coerceAtLeast(10)
    val top = ((a.t - h * 0.9) * k).toInt().coerceIn(0, page.height - 1)
    val bottom = ((a.b + h * 0.9) * k).toInt().coerceIn(top + 1, page.height)
    val out = Bitmap.createBitmap(page, 0, top, page.width, bottom - top).copy(Bitmap.Config.ARGB_8888, true)
    page.recycle()
    // The line itself marked in yellow, its neighbours left plain.
    val cv = android.graphics.Canvas(out)
    val y0 = (a.t * k - top).toFloat(); val y1 = (a.b * k - top).toFloat()
    cv.drawRect(0f, y0, out.width.toFloat(), y1, android.graphics.Paint().apply { color = 0x55FFD400 })
    cv.drawRect(0f, y0, out.width.toFloat(), y1, android.graphics.Paint().apply { color = 0xFFF28A1E.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f })
    out
}.getOrNull()

/** Bills whose "lines not understood" question was already asked in this session. */
private val askedFix = mutableSetOf<String>()
