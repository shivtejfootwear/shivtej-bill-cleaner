package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.BuildConfig
import com.shivtej.billscanner.container

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    var choices by remember { mutableStateOf(c.prefs.discountChoices) }
    var land by remember { mutableStateOf(c.prefs.pdfLandscape) }
    var learnt by remember { mutableStateOf(c.prefs.learnt) }
    SettingsContent(
        c.lang, { c.setLanguage(it) }, choices,
        onAddChoice = { p -> choices = (choices + p).distinct().sorted(); c.prefs.discountChoices = choices },
        onRemoveChoice = { p -> choices = choices - p; c.prefs.discountChoices = choices },
        landscape = land, onLandscape = { land = it; c.prefs.pdfLandscape = it }, onBack = onBack,
        learnt = learnt, onForget = { c.prefs.learnt = com.shivtej.billscanner.core.ocr.Learnt(); c.prefs.taught = emptyMap(); learnt = c.prefs.learnt },
        taughtColumns = c.prefs.taught.values.sumOf { it.size },
        patterns = { PatternsCard() },
    )
}

/**
 * Bill pattern codes: for a bill the scanner did not understand, the owner sends it to Claude ("📤 Claude को भेजें"
 * on the bill), gets back a short "SBP1:…" code and pastes it here. Bills of that supplier are then read with it.
 */
@Composable
fun PatternsCard() {
    val ctx = LocalContext.current
    val c = ctx.container
    var list by remember { mutableStateOf(c.prefs.patterns) }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val lang = c.lang
    // Everything taught, as one small file in Download/Shivtej Bills (kept even if the app is removed).
    fun backupNow(): String? = runCatching {
        val text = com.shivtej.billscanner.core.store.Backup.write(c.prefs.patterns, c.prefs.learnt.encode(), c.prefs.taughtRaw)
        Files.saveToDownloads(ctx, "Shivtej_Bill_Codes_Backup.txt", "text/plain", text.toByteArray(Charsets.UTF_8))
    }.getOrNull()
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
        val b = text?.let { com.shivtej.billscanner.core.store.Backup.read(it) }
        if (b == null || (b.patterns.isEmpty() && b.learnt == null && b.taught == null)) { msg = tr2(lang, "इस फ़ाइल में कोई कोड नहीं मिला।", "No codes in this file."); return@rememberLauncherForActivityResult }
        list = (b.patterns + list).distinctBy { it.name.uppercase() }; c.prefs.patterns = list
        b.learnt?.let { l -> if (c.prefs.learnt.size == 0) c.prefs.learnt = com.shivtej.billscanner.core.ocr.Learnt.decode(l) }
        b.taught?.let { t -> if (c.prefs.taughtRaw.isBlank()) c.prefs.taughtRaw = t }
        msg = tr2(lang, "✓ बैकअप से ${b.patterns.size} कोड वापस आ गए।", "✓ ${b.patterns.size} codes restored from the backup.")
    }
    Card {
        Title(tr("🧩 बिल समझने का कोड (नई पार्टी)", "🧩 Bill pattern codes (new suppliers)"))
        Muted(tr("कोई बिल समझ न आए: बिल खोलकर \"📤 Claude को भेजें\" दबाएँ। Claude जो कोड (SBP1:…) देगा उसे यहाँ पेस्ट करके \"जोड़ें\" दबाएँ, फिर बिल में \"🔄 दोबारा पढ़ें\"। कोड एक बार डालना काफ़ी है — ऐप याद रखता है, और अगले अपडेट में वह ऐप के अंदर ही आ जाता है।",
            "A bill not understood: open it and tap \"📤 Send to Claude\". Paste the code (SBP1:…) here, tap Add, then \"🔄 Read again\". Once is enough — the app keeps it, and the next update has it built in."))
        OutlinedTextField(code, { code = it; msg = null }, label = { Text(tr("कोड यहाँ पेस्ट करें", "Paste the code here")) }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val got = com.shivtej.billscanner.core.store.Backup.read(code).patterns
                if (got.isEmpty()) msg = tr2(lang, "यह कोड सही नहीं है — पूरा कोड (SBP1: से शुरू) पेस्ट करें।", "Not a valid code — paste the whole code starting with SBP1:")
                else {
                    list = (got + list).distinctBy { it.name.uppercase() }; c.prefs.patterns = list; code = ""
                    val saved = backupNow()
                    msg = tr2(lang, "✓ ${got.joinToString { "\"${it.name}\"" }} जुड़ गया और याद रख लिया" + (if (saved != null) " (बैकअप: Download/Shivtej Bills)" else "") + "। अब बिल खोलकर \"🔄 दोबारा पढ़ें\" दबाएँ।",
                        "✓ ${got.joinToString { "\"${it.name}\"" }} added and kept" + (if (saved != null) " (backup in Download/Shivtej Bills)" else "") + ". Open the bill and tap \"🔄 Read again\".")
                }
            }) { Text(tr("जोड़ें", "Add"), color = C.orangeDark) }
            msg?.let { Text(it, fontSize = 13.sp, color = C.navy, modifier = Modifier.weight(1f)) }
        }
        // Codes in use: the owner's, then those built into the app. They are not removed by mistake.
        val builtIn = com.shivtej.billscanner.core.ocr.KnownPatterns.ALL.map { it.name.uppercase() }.toSet()
        com.shivtej.billscanner.core.ocr.KnownPatterns.withOwn(list).forEach { p ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("✓ " + p.name + if (p.name.uppercase() in builtIn) tr("  · ऐप में पक्का", "  · built in") else tr("  · याद रखा", "  · kept"), fontSize = 15.sp, color = C.ok)
                p.note?.let { Muted(it) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { msg = if (backupNow() != null) tr2(lang, "✓ बैकअप Download/Shivtej Bills में सेव हुआ (Shivtej_Bill_Codes_Backup.txt)।", "✓ Backup saved in Download/Shivtej Bills.") else tr2(lang, "बैकअप सेव नहीं हुआ।", "Backup not saved.") }) {
                Text(tr("💾 बैकअप सेव करें", "💾 Save backup"), color = C.navy)
            }
            TextButton(onClick = { restore.launch(arrayOf("text/plain", "*/*")) }) { Text(tr("📂 बैकअप से वापस लाएँ", "📂 Restore backup"), color = C.navy) }
        }
        Muted(tr("नया फ़ोन या ऐप दोबारा install किया? \"📂 बैकअप से वापस लाएँ\" से Download/Shivtej Bills वाली फ़ाइल चुनें। Google बैकअप चालू हो तो कोड और सेटिंग अपने आप भी वापस आते हैं।",
            "New phone or reinstalled? Pick the file in Download/Shivtej Bills with \"📂 Restore backup\". With Google backup on, codes and settings also come back by themselves."))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    lang: String, onLang: (String) -> Unit, choices: List<Int>, onAddChoice: (Int) -> Unit, onRemoveChoice: (Int) -> Unit,
    landscape: Boolean, onLandscape: (Boolean) -> Unit, onBack: () -> Unit,
    learnt: com.shivtej.billscanner.core.ocr.Learnt = com.shivtej.billscanner.core.ocr.Learnt(), onForget: () -> Unit = {}, taughtColumns: Int = 0,
    patterns: @Composable () -> Unit = {},
) {
    var add by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("सेटिंग", "Settings"), onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            patterns()
            Card {
                Title(tr("🧠 स्कैनर ने क्या सीखा", "🧠 What the scanner learnt"))
                val fixes = learnt.fixes.values.sumOf { m -> m.values.sumOf { it.size } }
                val mrps = learnt.mrp.values.sumOf { it.size }
                Text(
                    tr("आपके सुधार: $fixes · अक्षर/अंक की पहचान: ${learnt.swaps.size} · हटाई गई फ़ालतू लाइनें: ${learnt.junk.values.sumOf { it.size }} · " +
                        "याद किए कॉलम: $taughtColumns · आर्टिकल MRP (${learnt.mrp.size} पार्टी): $mrps",
                        "Your fixes: $fixes · letters for digits: ${learnt.swaps.size} · junk lines: ${learnt.junk.values.sumOf { it.size }} · " +
                            "columns taught: $taughtColumns · article MRPs (${learnt.mrp.size} suppliers): $mrps"),
                    fontSize = 14.sp, color = C.navy,
                )
                Muted(tr("आप किसी लाइन को ठीक करते हैं तो app याद रखता है कि उस पार्टी के बिल में वह गलती कैसी थी, और अगली बार खुद ठीक करता है। सब इसी फ़ोन पर रहता है।",
                    "When you fix a line, the app remembers that supplier's misreading and fixes it by itself next time. Stays on this phone."))
                if (learnt.size + mrps + taughtColumns > 0) TextButton(onClick = onForget) { Text(tr("सब भूल जाओ (शुरू से)", "Forget everything"), color = C.err) }
            }
            Card {
                Title(tr("भाषा", "Language"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("हिंदी", lang == "hi") { onLang("hi") }
                    Chip("English", lang == "en") { onLang("en") }
                }
                Muted(tr("आर्टिकल नंबर, GSTIN और बिल नंबर हमेशा वैसे ही दिखते हैं जैसे छपे हैं।", "Article numbers, GSTIN and invoice numbers always stay as printed."))
            }
            Card {
                Title(tr("टार्गेट डिस्काउंट के बटन", "Target discount buttons"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { p -> Chip("$p%  ✕", false) { onRemoveChoice(p) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(add, { add = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text(tr("नया %", "New %")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    TextButton(onClick = { add.toIntOrNull()?.takeIf { it in 1..95 }?.let(onAddChoice); add = "" }) { Text(tr("जोड़ें", "Add")) }
                }
            }
            Card {
                Title(tr("PDF", "PDF"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("पुरानी चौड़ी टेबल वाली PDF (बंद = फ़ोन में बैठने वाले कार्ड)", "Old wide table PDF (off = phone-size cards)"), modifier = Modifier.weight(1f)); Switch(landscape, onLandscape)
                }
            }
            Card {
                Title(tr("टैक्स का हिसाब", "Tax calculation"))
                Muted(tr("बिल पर जैसा छपा, वैसा: पहले डिस्काउंट, फिर Taxable पर CGST + SGST (या IGST)। 2–3 पैसे का round-off फ़र्क़ ग़लती नहीं माना जाता।",
                    "As on the bill: discount first, then CGST + SGST (or IGST) on the taxable value. A 2–3 paise round-off is not treated as an error."))
                Muted(tr("मुद्रा: ₹ (INR)", "Currency: ₹ (INR)"))
            }
            Card {
                Title(tr("डेटा और सुरक्षा", "Data and privacy"))
                Muted(tr("बिल इसी फ़ोन पर पढ़े जाते हैं (Google ML Kit, बिना internet)। फ़ोटो और रिकॉर्ड फ़ोन में ही रहते हैं; फ़ोन का Google बैकअप चालू हो तो उसमें भी।",
                    "Bills are read on this phone (Google ML Kit, offline). Photos and records stay on the phone, and in the phone's Google backup if it is on."))
                Muted(tr("सारे बिल Excel/PDF में निकालकर अलग भी रख सकते हैं।", "You can export every bill to Excel/PDF to keep a copy."))
            }
            Muted("Shivtej Bill Scanner AI ${BuildConfig.VERSION_NAME} · Shivtej Footwear")
        }
    }
}
