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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Warning
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun rs(v: BigDecimal?): String = v?.let { "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(Calc.r2(it)) } ?: "—"
fun num(v: BigDecimal?): String = v?.let { Calc.r2(it).toPlainString() } ?: "—"
fun qtyText(v: BigDecimal?): String = v?.stripTrailingZeros()?.toPlainString() ?: "—"
fun pct(v: BigDecimal?): String = v?.let { Calc.r2(it).toPlainString() + "%" } ?: "—"
fun fmtDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.ENGLISH).format(Date(ms))

@Composable
fun Card(modifier: Modifier = Modifier, pad: Dp = 14.dp, border: Color = C.line, bg: Color = C.card, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).border(1.dp, border, RoundedCornerShape(16.dp)).padding(pad),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.navy, modifier = modifier)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, color: Color = C.muted) = Text(text, fontSize = 13.sp, color = color, modifier = modifier)

@Composable
fun Pill(text: String, fg: Color, bg: Color) =
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, modifier = Modifier.background(bg, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 2.dp))

@Composable
fun KV(k: String, v: String, vColor: Color = C.ink, bold: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(k, fontSize = 14.sp, color = C.muted, modifier = Modifier.weight(0.45f))
        Text(v, fontSize = 14.sp, color = vColor, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, right: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(C.bg).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("वापस", "Back"), tint = C.navy) }
        else Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = C.navy, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        right()
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, orange: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (orange) C.orange else C.navy, contentColor = Color.White),
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(14.dp)) {
        if (icon != null) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = C.navy); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.navy)
    }
}

/** The orange box beside every article: the discount % the supplier printed on the bill. */
@Composable
fun BillDiscountBox(pct: BigDecimal?, onClick: () -> Unit, edited: Boolean = false) {
    val missing = pct == null
    Column(
        Modifier.widthIn(min = 76.dp).background(if (missing) C.warnSoft else C.orangeSoft, RoundedCornerShape(12.dp))
            .border(1.5.dp, if (missing) C.warn else C.orange, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("बिल डिस्काउंट", "Bill discount"), fontSize = 10.sp, color = C.orangeDark)
        if (missing) Text("Needs\nVerification", fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, color = C.warn, textAlign = TextAlign.Center)
        else Text(pct(pct), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.orange)
        Text(if (edited) tr("✎ बदला", "✎ edited") else "✎", fontSize = 10.sp, color = C.orangeDark)
    }
}

@Composable
fun WarningRow(w: Warning) {
    val (fg, bg) = when (w.level) { Warning.Level.ERROR -> C.err to C.errSoft; Warning.Level.CHECK -> C.warn to C.warnSoft; Warning.Level.TIP -> C.navy to C.navySoft }
    Text(
        (when (w.level) { Warning.Level.ERROR -> "⛔ "; Warning.Level.CHECK -> "⚠ "; Warning.Level.TIP -> "💡 " }) + tr(w.hindi, w.english),
        fontSize = 13.sp, color = fg, modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(10.dp),
    )
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) C.navy else C.card, RoundedCornerShape(12.dp)).border(1.5.dp, if (on) C.navy else C.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else C.navy) }
}
