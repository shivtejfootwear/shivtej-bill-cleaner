package com.shivtej.billscanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Navy, white and orange — Shivtej's colours. */
object C {
    val navy = Color(0xFF0E2A47)
    val navy2 = Color(0xFF163B63)
    val navySoft = Color(0xFFE8EEF6)
    val orange = Color(0xFFF28A1E)
    val orangeSoft = Color(0xFFFFF1E2)
    val orangeDark = Color(0xFFB45309)
    val bg = Color(0xFFF3F5F9)
    val card = Color.White
    val ink = Color(0xFF14202E)
    val muted = Color(0xFF5D6B7A)
    val line = Color(0xFFE3E8EF)
    val ok = Color(0xFF13795B)
    val okSoft = Color(0xFFE3F4EC)
    val warn = Color(0xFFB45309)
    val warnSoft = Color(0xFFFFF4DB)
    val err = Color(0xFFB42318)
    val errSoft = Color(0xFFFDECEA)
    val headerBrush = Brush.linearGradient(listOf(navy, navy2))
}

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/** "hi" or "en". */
val LocalLang = staticCompositionLocalOf { "hi" }

/** Picks the Hindi or English text for the current language. */
@Composable
@ReadOnlyComposable
fun tr(hi: String, en: String): String = if (LocalLang.current == "en") en else hi

@Composable
fun BillTheme(lang: String, content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = C.navy, onPrimary = Color.White, secondary = C.orange, onSecondary = Color.White,
        background = C.bg, surface = C.card, onSurface = C.ink, onBackground = C.ink, outline = C.line,
        primaryContainer = C.navySoft, secondaryContainer = C.orangeSoft, surfaceVariant = Color(0xFFF0F3F8), error = C.err,
        surfaceContainerHighest = Color(0xFFE6EBF2), surfaceContainerHigh = Color(0xFFEEF2F7), surfaceContainer = Color(0xFFF3F6FA),
        surfaceContainerLow = Color.White, surfaceContainerLowest = Color.White, onSurfaceVariant = C.muted, outlineVariant = C.line,
    )
    CompositionLocalProvider(LocalLang provides lang) { MaterialTheme(colorScheme = scheme, content = content) }
}
