package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.Groups
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Target
import java.math.BigDecimal

/** "Which company at what %": one % for the whole bill, or one per company, and the new bill total. */
@Composable
fun TargetsScreen(id: String, onBack: () -> Unit, onExport: () -> Unit) {
    val c = LocalContext.current.container
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    var custom by remember { mutableStateOf<String?>(null) }   // null = closed, "" = whole bill, else group
    val now = { System.currentTimeMillis() }
    TargetsContent(
        inv, c.prefs.discountChoices, onBack, onExport,
        onAll = { p -> ed.change { Edits.setAllTargets(it, p, now()) } },
        onGroup = { g, p -> ed.change { Edits.setGroupTarget(it, g, p, now()) } },
        onCustom = { custom = it },
        onGst = { on -> ed.change { Edits.setSittingWithGst(it, on, now()) } },
    )
    custom?.let { g ->
        var v by remember(g) { mutableStateOf((if (g.isEmpty()) inv.defaultTargetPct else inv.groupTargets[g])?.toPlainString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { custom = null },
            title = { Text(if (g.isEmpty()) tr("पूरे बिल पर टार्गेट %", "Target % for the whole bill") else tr("$g का टार्गेट %", "Target % for $g")) },
            text = {
                OutlinedTextField(v, { v = it }, singleLine = true, suffix = { Text("%") }, label = { Text(tr("MRP पर डिस्काउंट %", "Discount on MRP %")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = Calc.bd(v)?.takeIf { it > BigDecimal.ZERO && it < BigDecimal(100) }
                    if (g.isEmpty()) ed.change { Edits.setAllTargets(it, p, now()) } else ed.change { Edits.setGroupTarget(it, g, p, now()) }
                    custom = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { custom = null }) { Text(tr("रुकें", "Cancel")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetsContent(
    inv: Invoice, choices: List<Int>, onBack: () -> Unit, onExport: () -> Unit,
    onAll: (BigDecimal?) -> Unit, onGroup: (String, BigDecimal?) -> Unit, onCustom: (String) -> Unit, onGst: (Boolean) -> Unit,
) {
    val groups = Groups.of(inv)
    val sheet = Target.sheet(inv)
    val def = inv.defaultTargetPct
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("% लगाएँ — नया बिल", "Set % — new bill"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                tr("हरा = डीलर ने MRP पर असल में कितना % दिया (बैठा %)। पीला = आपको कितना % चाहिए। उसी से नया रेट और नया बिल बनेगा।",
                    "Green = what the dealer really gave on MRP (baitha %). Yellow = the % you want. The new rate and bill follow it."),
                fontSize = 13.sp, color = C.navy, modifier = Modifier.fillMaxWidth().background(C.navySoft, RoundedCornerShape(10.dp)).padding(10.dp),
            )
            // Without MRP a % on MRP cannot make a new rate: say so instead of buttons that seem to do nothing.
            val arts = inv.items.filter { it.kind == com.shivtej.billscanner.core.model.ItemKind.ARTICLE }
            val noMrp = arts.count { !Target.targetable(inv, it) }
            if (arts.isNotEmpty() && noMrp > 0) {
                Text(
                    if (noMrp == arts.size) tr("इस बिल की किसी भी लाइन में MRP नहीं मिला, इसलिए % से नया रेट नहीं बन सकता। पीछे जाकर \"ठीक करें\" में हर लाइन का MRP डालें।",
                        "No line of this bill has an MRP, so a % cannot make a new rate. Go back and enter each line's MRP in \"Fix\".")
                    else tr("$noMrp लाइनों में MRP नहीं मिला — उन पर % नहीं लगेगा, वे बिल की रकम पर ही रहेंगी।",
                        "$noMrp lines have no MRP — the % does not apply to them; they stay at the bill amount."),
                    fontSize = 14.sp, color = C.err, modifier = Modifier.fillMaxWidth().background(C.errSoft, RoundedCornerShape(10.dp)).padding(10.dp),
                )
            }
            Card {
                Title(tr("पूरे बिल पर एक %", "One % for the whole bill"))
                PctChips(choices, if (inv.groupTargets.isEmpty()) def else null, onPick = onAll, onCustom = { onCustom("") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("% में GST शामिल (MRP से सीधी तुलना)", "% includes GST (straight against MRP)"), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(inv.sittingWithGst, onGst)
                }
            }
            Title(tr("कंपनी-वार — हर कंपनी का अलग %", "Company-wise — a % for each"))
            groups.forEach { g ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.key, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.navy, modifier = Modifier.weight(1f))
                        Pill("${g.items.size} " + tr("लाइन", "lines") + " · ${qtyText(g.pairs)} " + tr("जोड़ी", "pairs"), C.navy, C.navySoft)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stat(tr("बिल रकम", "Bill amount"), rs(g.billValue), C.navySoft, C.navy, Modifier.weight(1.2f))
                        Stat(tr("अभी बैठा", "Baitha now"), pct(g.sittingPct), C.okSoft, C.ok, Modifier.weight(1f))
                        Stat(tr("टार्गेट", "Target"), (g.target ?: def)?.let { pct(it) } ?: "—", Color(0xFFFFF3C4), C.orangeDark, Modifier.weight(1f))
                    }
                    PctChips(choices, g.target, onPick = { onGroup(g.key, it) }, onCustom = { onCustom(g.key) }, clearText = if (g.target != null) tr("हटाएँ", "Clear") else null)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Column(Modifier.fillMaxWidth().background(C.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Stat(tr("डीलर का बिल", "Dealer's bill"), rs(sheet.billTotal), C.navySoft, C.navy, Modifier.weight(1f))
                Spacer(Modifier.padding(3.dp))
                Stat(tr("आपके % से", "At your %"), if (sheet.any) rs(sheet.rounded) else "—", Color(0xFFFFF3C4), C.orangeDark, Modifier.weight(1f))
                Spacer(Modifier.padding(3.dp))
                Stat(tr("फ़र्क़", "Difference"), if (sheet.any) rs(sheet.difference) else "—", if (sheet.difference.signum() < 0) C.okSoft else C.errSoft,
                    if (sheet.difference.signum() < 0) C.ok else C.err, Modifier.weight(1f))
            }
            if (sheet.any && sheet.untouched.any { it.kind == com.shivtej.billscanner.core.model.ItemKind.ARTICLE })
                Muted(tr("जिन आर्टिकल पर % नहीं लगा, वे बिल वाली रकम पर ही गिने गए।", "Articles without a % are counted at the bill amount."))
            BigButton(tr("नया बिल — Excel / PDF / फ़ोटो", "New bill — Excel / PDF / image"), onExport, Modifier.fillMaxWidth(), orange = true, enabled = sheet.any)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PctChips(choices: List<Int>, selected: BigDecimal?, onPick: (BigDecimal?) -> Unit, onCustom: () -> Unit, clearText: String? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { p -> Chip("$p%", selected?.compareTo(BigDecimal(p)) == 0) { onPick(BigDecimal(p)) } }
        val isCustom = selected != null && choices.none { BigDecimal(it).compareTo(selected) == 0 }
        Chip(tr("कस्टम", "Custom") + if (isCustom) " " + pct(selected) else "", isCustom, onCustom)
        if (clearText != null || selected != null) Chip(clearText ?: tr("हटाएँ", "Clear"), false) { onPick(null) }
    }
}

@Composable
private fun Stat(label: String, value: String, bg: Color, fg: Color, modifier: Modifier) {
    Column(modifier.background(bg, RoundedCornerShape(10.dp)).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = C.muted, textAlign = TextAlign.Center)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = fg, textAlign = TextAlign.Center)
    }
}
