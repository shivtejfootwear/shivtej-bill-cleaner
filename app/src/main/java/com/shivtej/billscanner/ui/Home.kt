package com.shivtej.billscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.core.model.Totals
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

enum class HomeFilter { ALL, PENDING, VERIFIED, EXPORTED }

@Composable
fun HomeScreen(onScan: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val c = LocalContext.current.container
    val list by c.repo.all().collectAsState(initial = emptyList())
    var q by rememberSaveable { mutableStateOf("") }
    var f by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    HomeContent(list, q, { q = it }, f, { f = it }, onScan, onOpen, onSettings)
}

@Composable
fun HomeContent(
    all: List<Invoice>, query: String, onQuery: (String) -> Unit, filter: HomeFilter, onFilter: (HomeFilter) -> Unit,
    onScan: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, now: Long = System.currentTimeMillis(),
) {
    val month = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    val thisMonth = all.filter { it.createdAt >= month }
    val totals = thisMonth.map { Totals.of(it) }
    val buy = totals.fold(BigDecimal.ZERO) { a, t -> a + (t.printedGrand ?: t.grand) }
    val gross = totals.fold(BigDecimal.ZERO) { a, t -> a + t.gross }
    val disc = totals.fold(BigDecimal.ZERO) { a, t -> a + t.discount }
    val avg = if (gross.signum() > 0) Calc.r2(disc.multiply(Calc.HUNDRED).divide(gross, 6, RoundingMode.HALF_UP)) else null
    val shown = all.filter { inv ->
        when (filter) {
            HomeFilter.ALL -> true
            HomeFilter.PENDING -> inv.status != InvoiceStatus.VERIFIED
            HomeFilter.VERIFIED -> inv.status == InvoiceStatus.VERIFIED
            HomeFilter.EXPORTED -> inv.exportedAt != null
        }
    }.filter { inv ->
        val t = query.trim()
        t.isEmpty() || listOfNotNull(inv.header.invoiceNo, inv.header.supplierName, inv.header.invoiceDate).any { it.contains(t, true) } ||
            inv.items.any { it.current.article.contains(t, true) || it.current.name.contains(t, true) }
    }

    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(C.headerBrush).padding(start = 18.dp, end = 6.dp, top = 22.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Shivtej Bill Scanner AI", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(tr("Shivtej Footwear · सप्लायर बिल जाँच", "Shivtej Footwear · supplier bill check"), fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = tr("सेटिंग", "Settings"), tint = Color.White) }
                }
            }
        }
        item {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().background(C.orange, RoundedCornerShape(18.dp)).clickable(onClick = onScan).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(tr("नया बिल स्कैन करें", "Scan new invoice"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(tr("कैमरा या गैलरी · कई पेज", "Camera or gallery · multi-page"), fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("${all.count { it.status != InvoiceStatus.VERIFIED }}", tr("जाँच बाकी", "Pending check"), Modifier.weight(1f)) { onFilter(HomeFilter.PENDING) }
                    Stat("${thisMonth.size}", tr("इस महीने के बिल", "Bills this month"), Modifier.weight(1f)) { onFilter(HomeFilter.ALL) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat(rs(buy).substringBefore('.'), tr("इस महीने की ख़रीद", "Purchases this month"), Modifier.weight(1f)) {}
                    Stat(pct(avg), tr("औसत बिल डिस्काउंट", "Avg bill discount"), Modifier.weight(1f)) {}
                }
                Row(
                    Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = C.muted)
                    Box(Modifier.weight(1f).padding(start = 8.dp)) {
                        if (query.isEmpty()) Text(tr("बिल नंबर, सप्लायर या आर्टिकल खोजें", "Search invoice no., supplier or article"), fontSize = 14.sp, color = C.muted)
                        BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = C.ink), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(tr("सभी", "All"), filter == HomeFilter.ALL) { onFilter(HomeFilter.ALL) }
                    Chip(tr("जाँच बाकी", "Pending"), filter == HomeFilter.PENDING) { onFilter(HomeFilter.PENDING) }
                    Chip(tr("जाँचे", "Verified"), filter == HomeFilter.VERIFIED) { onFilter(HomeFilter.VERIFIED) }
                    Chip(tr("फ़ाइलें", "Exported"), filter == HomeFilter.EXPORTED) { onFilter(HomeFilter.EXPORTED) }
                }
                Title(if (filter == HomeFilter.ALL) tr("हाल के बिल", "Recent invoices") else tr("बिल", "Invoices"))
                if (shown.isEmpty()) Muted(if (all.isEmpty()) tr("अभी कोई बिल नहीं। ऊपर \"नया बिल स्कैन करें\" दबाएँ।", "No invoices yet. Tap \"Scan new invoice\".") else tr("कोई बिल नहीं मिला", "No invoice found"))
            }
        }
        items(shown, key = { it.id }) { inv -> InvoiceRow(inv) { onOpen(inv.id) } }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(C.card, RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.navy, maxLines = 1)
        Text(label, fontSize = 12.sp, color = C.muted)
    }
}

@Composable
private fun InvoiceRow(inv: Invoice, onClick: () -> Unit) {
    val t = Totals.of(inv)
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 5.dp).fillMaxWidth().background(C.card, RoundedCornerShape(14.dp))
            .border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(inv.header.supplierName ?: tr("सप्लायर (नाम नहीं पढ़ा)", "Supplier (name not read)"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = C.navy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (inv.status == InvoiceStatus.VERIFIED) Pill(tr("जाँचा ✓", "Verified ✓"), C.ok, C.okSoft) else Pill(tr("जाँच बाकी", "To check"), C.warn, C.warnSoft)
        }
        Spacer(Modifier.height(2.dp))
        Muted("${inv.header.invoiceNo ?: "—"} · ${inv.header.invoiceDate ?: "—"} · ${t.articleLines} ${tr("आर्टिकल", "articles")} · ${qtyText(t.qty)} ${tr("जोड़ी", "pairs")}")
        Muted(rs(t.printedGrand ?: t.grand) + (if (inv.exportedAt != null) " · Excel/PDF ✓" else "") + " · " + fmtDate(inv.createdAt))
    }
}
