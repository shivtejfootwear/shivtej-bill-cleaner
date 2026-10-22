package com.shivtej.billscanner.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.shivtej.billscanner.PendingPage
import com.shivtej.billscanner.ScanStep
import com.shivtej.billscanner.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AddScreen(onBack: () -> Unit, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val c = ctx.container
    var msg by remember { mutableStateOf<String?>(null) }
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(10)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build(),
        )
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.forEach { c.pending += PendingPage(it.imageUri) }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> uris.forEach { c.pending += PendingPage(it) } }
    AddContent(
        pages = c.pending.toList(), message = msg, onBack = onBack,
        onCamera = {
            val act = ctx as? Activity ?: return@AddContent
            scanner.getStartScanIntent(act)
                .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                .addOnFailureListener { msg = tr2(c.lang, "कैमरा स्कैनर नहीं खुला (Google Play services चाहिए)। गैलरी से फ़ोटो चुनें।", "Scanner did not open (needs Google Play services). Pick photos from the gallery.") }
        },
        onGallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRotate = { i -> c.pending[i] = c.pending[i].copy(rotation = (c.pending[i].rotation + 90) % 360) },
        onRemove = { i -> c.pending.removeAt(i) },
        onStart = { c.startScan(); onStart() },
    )
}

fun tr2(lang: String, hi: String, en: String) = if (lang == "en") en else hi

@Composable
fun AddContent(
    pages: List<PendingPage>, message: String?, onBack: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit,
    onRotate: (Int) -> Unit, onRemove: (Int) -> Unit, onStart: () -> Unit, thumbs: Map<Uri, Bitmap>? = null,
) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("बिल जोड़ें", "Add invoice"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile(Icons.Outlined.DocumentScanner, tr("कैमरा", "Camera"), tr("फ़ोटो खींचें · अपने-आप क्रॉप", "Photo · auto crop"), Modifier.weight(1f), onCamera)
                SourceTile(Icons.Outlined.PhotoLibrary, tr("गैलरी", "Gallery"), tr("एक या कई फ़ोटो", "One or many photos"), Modifier.weight(1f), onGallery)
            }
            message?.let { Muted(it, color = C.warn) }
            Card {
                Title(tr("पेज (एक ही बिल के)", "Pages (same invoice)") + " · ${pages.size}")
                if (pages.isEmpty()) Muted(tr("ऊपर से बिल की फ़ोटो जोड़ें। लंबा या 2 पेज का बिल हो तो सारे पेज जोड़ें।", "Add the bill photo(s) above. For a long or 2-page bill add every page."))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pages.forEachIndexed { i, p -> PageThumb(i, p, thumbs?.get(p.uri), { onRotate(i) }, { onRemove(i) }) }
                }
                Muted(tr("कैमरा स्कैनर किनारे काटता, सीधा करता और छाया साफ़ करता है। हर पेज यहाँ घुमा भी सकते हैं।", "The camera scanner crops, straightens and cleans shadows. You can rotate each page here."))
            }
            Card {
                Title(tr("फ़ोटो की सुरक्षा", "Photo safety"))
                KV(tr("असली फ़ोटो सुरक्षित रखें", "Keep the original photo"), tr("हमेशा", "Always"), C.ok)
                KV(tr("बिल कहाँ पढ़ा जाता है", "Where the bill is read"), tr("इसी फ़ोन पर", "On this phone"), C.ok)
                Muted(tr("🔒 कोई फ़ोटो internet पर नहीं भेजी जाती। app में internet की अनुमति ही नहीं है।", "🔒 No photo is sent over the internet — the app has no internet permission."))
            }
            Spacer(Modifier.height(6.dp))
        }
        Column(Modifier.fillMaxWidth().background(C.card).padding(14.dp)) {
            BigButton(tr("स्कैन शुरू करें", "Start scan") + if (pages.isNotEmpty()) " · ${pages.size} ${tr("पेज", "page(s)")}" else "",
                onStart, Modifier.fillMaxWidth(), orange = true, enabled = pages.isNotEmpty())
        }
    }
}

@Composable
private fun SourceTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(C.card, RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 18.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(54.dp).background(C.navySoft, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = C.navy, modifier = Modifier.size(30.dp)) }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.navy, modifier = Modifier.padding(top = 8.dp))
        Text(sub, fontSize = 12.sp, color = C.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PageThumb(i: Int, p: PendingPage, preset: Bitmap?, onRotate: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState(preset, p.uri) {
        if (preset == null) value = withContext(Dispatchers.IO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                ctx.contentResolver.openInputStream(p.uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(86.dp, 116.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp).rotate(p.rotation.toFloat())) }
            Text("${tr("पेज", "Page")} ${i + 1}", fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(C.navy, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp))
        }
        Row {
            Icon(Icons.Outlined.RotateRight, contentDescription = tr("घुमाएँ", "Rotate"), tint = C.navy, modifier = Modifier.clickable(onClick = onRotate).padding(6.dp))
            Icon(Icons.Outlined.Close, contentDescription = tr("हटाएँ", "Remove"), tint = C.err, modifier = Modifier.clickable(onClick = onRemove).padding(6.dp))
        }
    }
}

@Composable
fun ProcessingScreen(onDone: (String) -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current.container
    LaunchedEffect(c.step, c.scannedId) { c.scannedId?.let { if (c.step == ScanStep.DONE) onDone(it) } }
    ProcessingContent(c.step, c.progress, c.pageNow, c.scanError, onBack)
}

@Composable
fun ProcessingContent(step: ScanStep, progress: Float, page: Int, error: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        TopBar(tr("स्कैन हो रहा है", "Scanning"), if (step == ScanStep.FAILED) onBack else null)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Box(Modifier.fillMaxWidth().height(140.dp).background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    if (step == ScanStep.FAILED) Text("⚠", fontSize = 40.sp, color = C.warn) else CircularProgressIndicator(color = C.orange)
                }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = C.orange, trackColor = C.line)
                Text("${(progress * 100).toInt()}%" + if (page > 0) " · ${tr("पेज", "page")} $page" else "", fontWeight = FontWeight.Bold, color = C.navy, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Card {
                StepRow(1, tr("असली फ़ोटो सुरक्षित करना", "Saving the original photo"), step, ScanStep.SAVE)
                StepRow(2, tr("अक्षर पढ़ना (OCR, फ़ोन पर)", "Reading text (OCR, on phone)"), step, ScanStep.OCR)
                StepRow(3, tr("आर्टिकल, रेट, डिस्काउंट पहचानना", "Finding articles, rates, discounts"), step, ScanStep.FIELDS)
                StepRow(4, tr("हिसाब और मिलान", "Calculation and checks"), step, ScanStep.CHECK)
            }
            if (error != null) Card(border = C.warn, bg = C.warnSoft) {
                Text(tr("बिल नहीं पढ़ा जा सका: ", "Could not read the bill: ") + error, color = C.warn, fontSize = 14.sp)
                Muted(tr("फ़ोटो साफ़ और पूरी हो, रोशनी अच्छी हो — फिर दोबारा कोशिश करें।", "Use a clear, complete photo in good light and try again."))
            } else Muted(tr("जिन जगहों पर भरोसा कम होगा, उन्हें नारंगी रंग से दिखाएँगे — आप जाँच लें।", "Uncertain values are shown in orange for you to check."), Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun StepRow(n: Int, text: String, now: ScanStep, mine: ScanStep) {
    val order = listOf(ScanStep.SAVE, ScanStep.OCR, ScanStep.FIELDS, ScanStep.CHECK, ScanStep.DONE)
    val done = now == ScanStep.DONE || (order.indexOf(now) > order.indexOf(mine) && now != ScanStep.FAILED)
    val active = now == mine
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(26.dp).background(if (done) C.ok else if (active) C.orange else C.line, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (done || active) C.ink else C.muted, modifier = Modifier.padding(start = 10.dp))
    }
}
