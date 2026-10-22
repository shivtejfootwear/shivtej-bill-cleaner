package com.shivtej.billscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.export.InvoiceExport
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.ocr.Heal
import com.shivtej.billscanner.core.ocr.Learning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/** Article lines the scan could not read for sure: they do not add up, or a number is missing. */
fun doubtful(inv: Invoice): List<LineItem> = inv.items.filter { it.kind == ItemKind.ARTICLE && !Heal.sure(it) }

/** Right after a scan: "these N lines were not understood — fix them now?" */
@Composable
fun DoubtfulDialog(inv: Invoice, onFix: () -> Unit, onLater: () -> Unit) {
    val list = doubtful(inv)
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(tr("${list.size} लाइन ठीक से समझ नहीं आईं", "${list.size} lines were not understood")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("बाकी ${inv.items.count { it.kind == ItemKind.ARTICLE } - list.size} लाइनों का हिसाब पक्का है (✓)। इन्हें बिल देखकर एक-एक करके ठीक कर दें — फ़ोटो की वही लाइन साथ में दिखेगी:",
                    "The other lines add up (✓). Fix these one by one — the same line of the photo is shown:"), fontSize = 14.sp)
                list.take(12).forEach { Text("⚠ ${it.current.sr ?: ""}. ${it.current.article}  ${it.current.colour}".trim(), fontSize = 14.sp, color = C.warn, fontWeight = FontWeight.SemiBold) }
                if (list.size > 12) Text("… +${list.size - 12}", fontSize = 13.sp, color = C.muted)
            }
        },
        confirmButton = { TextButton(onClick = onFix) { Text(tr("अभी ठीक करें", "Fix now"), fontWeight = FontWeight.Bold, color = C.orangeDark) } },
        dismissButton = { TextButton(onClick = onLater) { Text(tr("बाद में", "Later")) } },
    )
}

/**
 * One doubtful line at a time: the printed line from the photo, what was read, and boxes for
 * pairs, MRP, rate, Disc. % and amount. The line is saved exactly as typed, and the scanner learns from it.
 */
@Composable
fun FixScreen(id: String, onDone: () -> Unit) {
    val c = LocalContext.current.container
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    // The list is fixed when the screen opens, so a fixed line does not jump away under the finger.
    val queue = remember(id) { doubtful(inv).map { it.id } }
    var at by remember { mutableStateOf(0) }
    var learnt by remember { mutableStateOf<String?>(null) }
    if (queue.isEmpty() || at >= queue.size) {
        Column(Modifier.fillMaxSize().background(C.bg)) {
            TopBar(tr("लाइनें ठीक करें", "Fix lines"), onDone)
            Card(Modifier.padding(14.dp)) {
                Text(tr("✓ सब लाइनें हो गईं।", "✓ All lines done."), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.ok)
                learnt?.let { Muted(it) }
                BigButton(tr("बिल पर वापस", "Back to the bill"), onDone, Modifier.fillMaxWidth(), orange = true)
            }
        }
        return
    }
    val item = inv.items.firstOrNull { it.id == queue[at] } ?: run {
        androidx.compose.runtime.LaunchedEffect(at) { at++ }
        return
    }
    val strip by produceState<Bitmap?>(null, item.id) { value = withContext(Dispatchers.IO) { lineStrip(inv, item) } }
    FixContent(inv, item, strip, at, queue.size, learnt,
        onSave = { v ->
            val (l, lessons) = Learning.fromEdit(c.prefs.learnt, inv, item, v)
            if (lessons.isNotEmpty()) { c.prefs.learnt = l; learnt = tr2(c.lang, "🧠 सीख लिया: ", "🧠 Learnt: ") + lessons.joinToString(" · ") }
            ed.change { Edits.fixItem(it, item.id, v, System.currentTimeMillis()) }
            at++
        },
        onSkip = { at++ },
        onDelete = {
            val l = Learning.fromDelete(c.prefs.learnt, inv, item)
            if (l != c.prefs.learnt) { c.prefs.learnt = l; learnt = tr2(c.lang, "🧠 सीख लिया: ऐसी लाइन अगली बार नहीं आएगी", "🧠 Learnt: such a line is skipped next time") }
            ed.change { Edits.deleteItem(it, item.id, System.currentTimeMillis()) }
            at++
        },
        onBack = onDone,
    )
}

@Composable
fun FixContent(
    inv: Invoice, item: LineItem, strip: Bitmap?, at: Int, total: Int, learnt: String?,
    onSave: (com.shivtej.billscanner.core.model.ItemValues) -> Unit, onSkip: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit,
) {
    val v = item.current
    fun s(x: BigDecimal?) = x?.stripTrailingZeros()?.toPlainString().orEmpty()
    var qty by remember(item.id) { mutableStateOf(s(v.qty)) }
    var mrp by remember(item.id) { mutableStateOf(s(v.mrp)) }
    var rate by remember(item.id) { mutableStateOf(s(v.rate)) }
    var disc by remember(item.id) { mutableStateOf(s(v.discPct)) }
    var amount by remember(item.id) { mutableStateOf(v.taxable?.let { Calc.r2(it).toPlainString() }.orEmpty()) }
    var big by remember { mutableStateOf(false) }
    val draft = v.copy(qty = Calc.bd(qty), mrp = Calc.bd(mrp), rate = Calc.bd(rate), discPct = Calc.bd(disc), taxable = Calc.bd(amount))
    val q = draft.qty; val r = draft.rate; val d = draft.discPct ?: BigDecimal.ZERO
    val expected = if (q != null && r != null) Calc.r2(q * r * (BigDecimal(100) - d) / BigDecimal(100)) else null
    val adds = Heal.proven(draft)
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("लाइन ठीक करें", "Fix line") + " · ${at + 1}/$total", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            learnt?.let { Text(it, fontSize = 13.sp, color = C.navy, modifier = Modifier.fillMaxWidth().background(C.navySoft, RoundedCornerShape(10.dp)).padding(8.dp)) }
            Text("${v.sr ?: ""}. ${v.article} ${v.colour}".trim(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.navy)
            // Bank details, signature, tax summary…: not an article — say so plainly and offer to remove it.
            val allText = item.raw.joinToString(" ") + " " + v.article
            if (com.shivtej.billscanner.core.ocr.InvoiceParser.NOT_ARTICLE.containsMatchIn(allText)) Column(
                Modifier.fillMaxWidth().background(C.errSoft, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("यह बैंक / खाता नंबर / IFSC / हस्ताक्षर / टैक्स की लाइन है — आर्टिकल नहीं। इसे हटा दें, app याद रखेगा।",
                    "This is bank / account / IFSC / signature / tax text — not an article. Remove it; the app will remember."),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.err)
                BigButton(tr("🗑 हटाएँ (आर्टिकल नहीं है)", "🗑 Remove (not an article)"), onDelete, Modifier.fillMaxWidth())
            }
            if (strip != null) {
                Muted(tr("बिल में यह लाइन (टैप = बड़ा / छोटा):", "This line on the bill (tap = bigger / smaller):"))
                Box(Modifier.fillMaxWidth().border(1.dp, C.line, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).clickable { big = !big }) {
                    Image(strip.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                        modifier = if (big) Modifier.width((strip.width / 1.3f).coerceAtLeast(600f).dp) else Modifier.fillMaxWidth())
                }
            } else Muted(tr("इस लाइन की फ़ोटो नहीं मिली — 'असली फ़ोटो' में देखकर भरें।", "No picture of this line — check the original photo."))
            // What the scan read, cell by cell, as printed.
            val cols = InvoiceExport.printedColumns(inv)
            Text(tr("स्कैन ने पढ़ा: ", "The scan read: ") + cols.indices.joinToString("  |  ") { InvoiceExport.printedText(inv, item, it, cols[it]).ifEmpty { "—" } },
                fontSize = 12.5.sp, color = C.muted)
            @Composable fun box(label: String, value: String, wrong: Boolean, set: (String) -> Unit, modifier: Modifier) = OutlinedTextField(
                value, set, singleLine = true, modifier = modifier, label = { Text(label) }, isError = wrong,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            val needs = item.needs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                box(tr("जोड़ी (Qty)", "Pairs"), qty, com.shivtej.billscanner.core.model.F.QTY in needs, { qty = it }, Modifier.weight(1f))
                box("MRP", mrp, com.shivtej.billscanner.core.model.F.MRP in needs, { mrp = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                box(tr("रेट", "Rate"), rate, com.shivtej.billscanner.core.model.F.RATE in needs, { rate = it }, Modifier.weight(1f))
                box(tr("बिल का Disc. %", "Bill's Disc. %"), disc, com.shivtej.billscanner.core.model.F.DISC_PCT in needs || (Calc.bd(disc)?.let { it >= BigDecimal(100) } == true), { disc = it }, Modifier.weight(1f))
            }
            box(tr("रकम (Amount, बिल जैसी)", "Amount (as on the bill)"), amount, com.shivtej.billscanner.core.model.F.TAXABLE in needs, { amount = it }, Modifier.fillMaxWidth())
            Muted(tr("Disc. % में वही भरें जो बिल पर छपा है। अपना % (35, 40…) यहाँ नहीं — वह \"% लगाएँ\" में डालें।",
                "Disc. % = what the bill prints. Your own % (35, 40…) goes in \"Set %\", not here."))
            // Live check of the line's own arithmetic.
            val (msg, fg, bg) = when {
                adds -> Triple(tr("✓ हिसाब मिला: ${qtyText(q)} × ${num(r)} − ${pct(draft.discPct)} = ${num(draft.taxable)}", "✓ Adds up"), C.ok, C.okSoft)
                expected != null && draft.taxable == null -> Triple(tr("रकम ख़ाली है — हिसाब से ${num(expected)} बनती है", "Amount empty — works out to ${num(expected)}"), C.warn, C.warnSoft)
                expected != null -> Triple(tr("⚠ हिसाब नहीं मिला: ${qtyText(q)} × ${num(r)} − ${pct(draft.discPct)} = ${num(expected)}, बिल पर ${num(draft.taxable)}", "⚠ Does not add up: works out to ${num(expected)}"), C.warn, C.warnSoft)
                else -> Triple(tr("जोड़ी और रेट भरें", "Fill in pairs and rate"), C.muted, C.navySoft)
            }
            Text(msg, fontSize = 14.sp, color = fg, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(10.dp))
            if (expected != null && !adds) TextButton(onClick = { amount = expected.toPlainString() }) { Text(tr("रकम हिसाब से भरें (${num(expected)})", "Fill the amount (${num(expected)})"), color = C.navy) }
            Calc.sittingPct(draft, inv.header, inv.sittingWithGst)?.let { Muted(tr("बैठा %: ", "Baitha %: ") + pct(it)) }
        }
        Column(Modifier.fillMaxWidth().background(C.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BigButton(if (adds) tr("✓ सेव करें · अगली लाइन", "✓ Save · next line") else tr("सेव करें · अगली लाइन", "Save · next line"), { onSave(draft) }, Modifier.fillMaxWidth(), orange = true)
            Row {
                TextButton(onClick = onSkip) { Text(tr("छोड़ें", "Skip"), color = C.navy) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(tr("🗑 यह आर्टिकल नहीं है (हटाएँ)", "🗑 Not an article (delete)"), color = C.err) }
            }
        }
    }
}
