package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import java.math.BigDecimal

/** Target-rate figures for one invoice. These are the person's estimates, never the supplier's bill. */
object Target {
    fun pctFor(inv: Invoice, itemId: String): BigDecimal? = inv.items.firstOrNull { it.id == itemId }?.let { it.targetPct ?: inv.defaultTargetPct }

    /** Σ target rate × qty for articles (pre-tax). */
    fun value(inv: Invoice): BigDecimal = inv.items.filter { it.kind == ItemKind.ARTICLE }.fold(BigDecimal.ZERO) { a, i ->
        val r = Calc.targetRate(i.current.mrp, i.targetPct ?: inv.defaultTargetPct) ?: return@fold a
        a + r * (i.current.qty ?: BigDecimal.ZERO)
    }

    /** Σ net rate × qty for the same articles, as the supplier billed them (pre-tax). */
    fun billedValue(inv: Invoice): BigDecimal = inv.items.filter { it.kind == ItemKind.ARTICLE && (it.targetPct ?: inv.defaultTargetPct) != null }.fold(BigDecimal.ZERO) { a, i ->
        a + (i.current.taxable ?: BigDecimal.ZERO)
    }
}

@Composable
fun AnalysisScreen(id: String, onBack: () -> Unit, onExport: () -> Unit) {
    val c = LocalContext.current.container
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    var custom by remember { mutableStateOf<String?>(null) }   // null = closed; "" = all items; else item id
    AnalysisContent(
        inv, c.prefs.discountChoices, onBack, onExport,
        onPickAll = { p -> ed.change { Edits.setTarget(it, null, p, System.currentTimeMillis()) } },
        onCustomAll = { custom = "" },
        onItem = { itemId -> custom = itemId },
    )
    custom?.let { target ->
        var v by remember(target) { mutableStateOf((if (target.isEmpty()) inv.defaultTargetPct else Target.pctFor(inv, target))?.toPlainString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { custom = null },
            title = { Text(if (target.isEmpty()) tr("सब आर्टिकल पर टार्गेट %", "Target % for all articles") else tr("इस आर्टिकल का टार्गेट %", "Target % for this article")) },
            text = {
                Column {
                    OutlinedTextField(v, { v = it }, singleLine = true, suffix = { Text("%") }, label = { Text(tr("MRP पर डिस्काउंट %", "Discount on MRP %")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (target.isNotEmpty()) Muted(tr("ख़ाली छोड़ें = सबका जो टार्गेट है वही", "Leave empty = use the invoice target"))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Calc.bd(v)
                    ed.change { Edits.setTarget(it, target.ifEmpty { null }, p, System.currentTimeMillis()) }
                    custom = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { custom = null }) { Text(tr("रुकें", "Cancel")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisContent(
    inv: Invoice, choices: List<Int>, onBack: () -> Unit, onExport: () -> Unit,
    onPickAll: (BigDecimal?) -> Unit, onCustomAll: () -> Unit, onItem: (String) -> Unit,
) {
    val def = inv.defaultTargetPct
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("डिस्काउंट विश्लेषण", "Discount analysis"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Title(tr("सब आर्टिकल पर टार्गेट डिस्काउंट (MRP पर)", "Target discount on MRP for all articles"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { p -> Chip("$p%", def?.compareTo(BigDecimal(p)) == 0) { onPickAll(BigDecimal(p)) } }
                    Chip(tr("कस्टम", "Custom") + if (def != null && choices.none { BigDecimal(it).compareTo(def) == 0 }) " ${pct(def)}" else "",
                        def != null && choices.none { BigDecimal(it).compareTo(def) == 0 }) { onCustomAll() }
                    if (def != null) Chip(tr("हटाएँ", "Clear"), false) { onPickAll(null) }
                }
                Muted(tr("किसी आर्टिकल पर tap करके उसका अलग टार्गेट रख सकते हैं।", "Tap an article to give it its own target."))
            }
            val cols = listOf(tr("आर्टिकल", "Article") to 92.dp, tr("बिल %", "Bill %") to 70.dp, tr("MRP पर %", "MRP %") to 66.dp,
                tr("असर %", "Eff. %") to 62.dp, tr("टार्गेट", "Target") to 60.dp, tr("टार्गेट रेट", "Target rate") to 78.dp, tr("रेट से फ़र्क़", "vs rate") to 72.dp)
            Card(pad = 0.dp) {
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row(Modifier.background(C.navy, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))) { cols.forEachIndexed { i, (h, w) -> Cell(h, w, header = true, orange = i == 1) } }
                    inv.items.filter { it.kind == ItemKind.ARTICLE }.forEach { it ->
                        val v = it.current
                        val t = it.targetPct ?: def
                        val tr8 = Calc.targetRate(v.mrp, t)
                        val rate = v.rate
                        val diff = if (tr8 != null && rate != null) tr8 - rate else null
                        Row(Modifier.clickable { onItem(it.id) }) {
                            Cell("${v.sr ?: ""} ${v.article}", cols[0].second, left = true)
                            Cell(if (v.discPct == null) "Needs Verif." else pct(v.discPct), cols[1].second, orange = true, warn = v.discPct == null)
                            Cell(pct(Calc.mrpDiscPct(v)), cols[2].second); Cell(pct(Calc.effectiveMrpPct(v)), cols[3].second)
                            Cell(t?.let { p -> pct(p) + if (it.targetPct != null) "*" else "" } ?: "—", cols[4].second)
                            Cell(rs(tr8), cols[5].second)
                            Cell(diff?.let { d -> (if (d.signum() > 0) "+" else "") + num(d) } ?: "—", cols[6].second, color = if (diff == null) C.muted else if (diff.signum() < 0) C.err else C.ok)
                        }
                        Box(Modifier.width(cols.fold(0.dp) { a, c -> a + c.second }).height(1.dp).background(C.line))
                    }
                }
            }
            Muted(tr("बिल % = सप्लायर ने छापा · MRP पर % = (MRP − रेट) ÷ MRP · असर % = बिल छूट के बाद के नेट रेट पर · * = इस आर्टिकल का अलग टार्गेट",
                "Bill % = printed by supplier · MRP % = (MRP − rate) ÷ MRP · Eff. % = on the net rate after the bill discount · * = own target"))
            Card {
                KV(tr("असली बिल (सप्लायर), Taxable", "Original invoice (supplier), taxable"), rs(Target.billedValue(inv)))
                KV(tr("टार्गेट पर (आपका अनुमान)", "At target (your estimate)"), if (def == null && inv.items.none { it.targetPct != null }) "—" else rs(Target.value(inv)), C.orange)
                Text(tr("⚑ टार्गेट शीट आपका अनुमान है, सप्लायर का बिल नहीं। असली बिल के आँकड़े नहीं बदलते।",
                    "⚑ The target sheet is your estimate, not the supplier's invoice. The original figures never change."),
                    fontSize = 12.5.sp, color = C.navy, modifier = Modifier.fillMaxWidth().background(C.navySoft, RoundedCornerShape(10.dp)).padding(10.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().background(C.card).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(tr("असली बिल", "Original invoice"), onBack, Modifier.weight(1f))
            BigButton(tr("टार्गेट शीट बनाएँ", "Make target sheet"), onExport, Modifier.weight(1f), orange = true)
        }
    }
}

@Composable
private fun Cell(text: String, w: Dp, header: Boolean = false, orange: Boolean = false, warn: Boolean = false, left: Boolean = false, color: Color? = null) {
    Text(
        text, fontSize = if (header) 11.sp else 12.sp, fontWeight = if (header || orange) FontWeight.SemiBold else FontWeight.Normal,
        color = color ?: when { header -> Color.White; warn -> C.warn; orange -> C.orangeDark; else -> C.ink },
        textAlign = if (left) TextAlign.Start else TextAlign.End, maxLines = 2,
        modifier = Modifier.width(w).background(if (header && orange) C.orange else if (orange) C.orangeSoft else Color.Transparent).padding(horizontal = 6.dp, vertical = 9.dp),
    )
}
