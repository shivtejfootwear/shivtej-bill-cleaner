package com.shivtej.billscanner.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivtej.billscanner.container
import com.shivtej.billscanner.export.Pdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * "पूरा बिल" — the whole bill (as printed + बैठा % + our % + new amount) on one screen.
 * It opens fitted to the screen width; pinch to zoom in or out (down to the whole bill at once), drag to move,
 * double-tap to switch between fit-width and the whole bill.
 */
@Composable
fun FullBillScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    val ed = rememberEditor(id)
    val inv = ed.invoice ?: return
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val pages by produceState<List<ImageBitmap>?>(null, inv) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pdf = ByteArrayOutputStream().also { Pdf.cards(inv, it) }.toByteArray()
                renderPages(File(ctx.cacheDir, "view_${inv.id}.pdf").apply { writeBytes(pdf) }).map { it.asImageBitmap() }
            }.getOrElse { emptyList() }
        }
    }
    fun act(k: ExportKind, share: Boolean) = scope.launch {
        runCatching {
            val data = withContext(Dispatchers.IO) { Files.bytes(ctx, inv, k, c.prefs.pdfLandscape) }
            val name = Files.name(inv, k)
            if (share) Files.share(ctx, name, k.mime, data) else {
                withContext(Dispatchers.IO) { Files.saveToDownloads(ctx, name, k.mime, data) }
                Files.open(ctx, name, k.mime, data)
            }
        }.onFailure { msg = tr2(c.lang, "नहीं खुला: ${it.message}", "Could not open: ${it.message}") }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF3A4350))) {
        TopBar(tr("पूरा बिल", "Whole bill"), onBack) {
            TextButton(onClick = { act(ExportKind.XLSX, false) }) { Text(tr("Excel खोलें", "Open Excel"), color = C.navy) }
            IconButton(onClick = { act(ExportKind.PDF_REPORT, true) }) { Icon(Icons.Outlined.Share, contentDescription = tr("PDF शेयर", "Share PDF"), tint = C.navy) }
        }
        msg?.let { Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(C.err).padding(8.dp)) }
        val list = pages
        when {
            list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            list.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(tr("बिल नहीं बन पाया", "Could not draw the bill"), color = Color.White) }
            else -> ZoomPages(list, Modifier.weight(1f).fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().background(C.card).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(tr("दो उँगली से ज़ूम · डबल-टैप = पूरा बिल / चौड़ाई भर", "Pinch to zoom · double-tap = whole bill / fit width"))
        }
    }
}

/** Pages stacked top to bottom, drawn at the current zoom. */
@Composable
fun ZoomPages(pages: List<ImageBitmap>, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val gap = 12f
    // Content laid out at "fit width": each page as wide as the screen.
    fun contentHeight(w: Float) = pages.sumOf { (it.height.toFloat() * w / it.width).toDouble() }.toFloat() + gap * (pages.size - 1)
    fun clamp(s: Float, o: Offset): Offset {
        val w = view.width.toFloat(); val h = view.height.toFloat()
        val cw = w * s; val ch = contentHeight(w) * s
        val x = if (cw <= w) (w - cw) / 2 else o.x.coerceIn(w - cw, 0f)
        val y = if (ch <= h) (h - ch) / 2 else o.y.coerceIn(h - ch, 0f)
        return Offset(x, y)
    }
    fun minScale(): Float {
        val w = view.width.toFloat(); val h = view.height.toFloat()
        if (w <= 0f) return 1f
        return minOf(1f, h / contentHeight(w))
    }
    Canvas(
        modifier.clipToBounds().onSizeChanged { view = it; offset = clamp(scale, offset) }
            .pointerInput(pages) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(minScale(), 6f)
                    val k = ns / scale
                    offset = clamp(ns, Offset(centroid.x - (centroid.x - offset.x) * k + pan.x, centroid.y - (centroid.y - offset.y) * k + pan.y))
                    scale = ns
                }
            }
            .pointerInput(pages) {
                detectTapGestures(onDoubleTap = { p ->
                    val target = if (scale > minScale() + 0.01f) minScale() else 1f
                    val k = target / scale
                    offset = clamp(target, Offset(p.x - (p.x - offset.x) * k, p.y - (p.y - offset.y) * k))
                    scale = target
                })
            },
    ) {
        val w = size.width
        var y = 0f
        for (img in pages) {
            val ph = img.height.toFloat() * w / img.width
            val top = offset.y + y * scale
            val dh = ph * scale
            if (top + dh >= 0 && top <= size.height) {
                drawImage(
                    img, dstOffset = IntOffset(offset.x.toInt(), top.toInt()), dstSize = IntSize((w * scale).toInt(), dh.toInt()),
                    filterQuality = FilterQuality.High,
                )
            }
            y += ph + gap
        }
    }
}

/** Every page of a PDF as a picture, sharp enough to zoom in on. */
fun renderPages(f: File, scale: Float = 2.2f): List<Bitmap> =
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r ->
            (0 until r.pageCount).map { i ->
                r.openPage(i).use { pg ->
                    val b = Bitmap.createBitmap((pg.width * scale).toInt(), (pg.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            }
        }
    }
