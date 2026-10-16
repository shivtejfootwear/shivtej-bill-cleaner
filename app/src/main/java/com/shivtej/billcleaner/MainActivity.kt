package com.shivtej.billcleaner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

private val Blue = Color(0xFF1565E0)
private val BlueLight = Color(0xFFE6EFFD)
private val Ink = Color(0xFF14171F)
private val Muted = Color(0xFF6B7280)
private val CardBorder = Color(0xFFE3E8F0)

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShared(intent)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue,
                    onPrimary = Color.White,
                    surface = Color.White,
                    background = Color(0xFFF4F7FC),
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FC)) {
                    BillCleanerScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** WhatsApp/Gallery se "Share > Bill Cleaner" se aayi photo. */
    private fun handleShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) vm.setInput(listOf(uri), scanned = false)
    }
}

@Composable
fun BillCleanerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var zoomBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    // Camera scan (ML Kit): auto crop + seedha + shadow/daag hatane ka option
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val uris = scan?.pages?.map { it.imageUri }.orEmpty()
            vm.setInput(uris, scanned = true)
        }
    }
    val startScan: () -> Unit = {
        val activity = context as Activity
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(MainViewModel.MAX_PAGES)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                vm.message = "Scanner shuru nahi hua (Google Play services update karo): ${e.message}"
            }
    }

    // Gallery se seedha photo (crop ke bina)
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_PAGES)
    ) { uris -> vm.setInput(uris, scanned = false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Header()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BlueLight)
                .padding(14.dp)
        ) {
            Text(
                "Kisi bhi bill photo ko saaf, clear aur scanned PDF jaisa banao. Photo lete waqt aayi shadow bhi hat jayegi.",
                color = Ink, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ---- Upload ----
        SectionCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("⬆", fontSize = 30.sp, color = Blue)
                Text("Upload Bill Photo", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("JPG, PNG · ek saath $MAX_PAGES_TEXT page tak", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = startScan,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("📷  Camera se Scan (Auto Crop)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, Blue)
                ) { Text("🖼  Choose Photo (Gallery)", fontSize = 15.sp, color = Blue) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tedhi ya extra background wali photo ho to Camera Scan se kholo, wahan Gallery ka option bhi hai.",
                    fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center
                )
            }
        }

        // ---- Before / After ----
        if (vm.originalPreviews.isNotEmpty()) {
            val pageCount = vm.originalPreviews.size
            val idx = vm.page.coerceIn(0, pageCount - 1)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PreviewCard(
                    label = "Original Photo", labelColor = Color(0xFF3F4A5A),
                    bitmap = vm.originalPreviews[idx], modifier = Modifier.weight(1f),
                    emptyText = "", onClick = { zoomBitmap = it }
                )
                PreviewCard(
                    label = "Clean & Clear", labelColor = Blue,
                    bitmap = vm.cleanedPreviews.getOrNull(idx), modifier = Modifier.weight(1f),
                    emptyText = "Neeche \"Clean & Generate\" dabao", onClick = { zoomBitmap = it }
                )
            }
            if (pageCount > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = { vm.page = (idx - 1).coerceAtLeast(0) }, enabled = idx > 0) { Text("◀ Pichla") }
                    Text("Page ${idx + 1} / $pageCount", color = Ink, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.page = (idx + 1).coerceAtMost(pageCount - 1) }, enabled = idx < pageCount - 1) { Text("Agla ▶") }
                }
            }
            Text("Photo par tap karke zoom karke dekho", fontSize = 12.sp, color = Muted,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }

        // ---- Auto Enhancement ----
        SectionCard {
            Text("Auto Enhancement", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            CheckRow("Auto Crop (extra background hatao)", vm.fromScanner || vm.originals.isEmpty(), enabled = false,
                hint = "Camera Scan mein apne aap hota hai")
            CheckRow("Straighten (tedha seedha karo)", vm.fromScanner || vm.originals.isEmpty(), enabled = false,
                hint = "Camera Scan mein apne aap hota hai")
            CheckRow("Shadow & Noise hatao", vm.removeShadow, onChange = vm::updateRemoveShadow)
            CheckRow("Brightness & Contrast badhao", vm.enhance, onChange = vm::updateEnhance)
            CheckRow("PDF-Style clear lines", vm.sharpen, onChange = vm::updateSharpen)
            CheckRow("Black & White scan look", vm.blackWhite, onChange = vm::updateBlackWhite)
            CheckRow("Keep All Original Details", true, enabled = false,
                hint = "Text dobara type nahi hota, har number original rehta hai")
        }

        // ---- Output format ----
        SectionCard {
            Text("Output Format", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
            RadioRow("Image (JPG)", "Small Size", selected = !vm.asPdf) { vm.updateAsPdf(false) }
            RadioRow("PDF", "Small Size", selected = vm.asPdf) { vm.updateAsPdf(true) }
        }

        // ---- Generate ----
        Button(
            onClick = { vm.generate() },
            enabled = !vm.busy && vm.originals.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Blue)
        ) {
            if (vm.busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Kaam chal raha hai…", fontSize = 18.sp)
            } else {
                Text("✨  Clean & Generate", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (vm.originals.isEmpty()) {
            Text("Pehle bill ki photo chuno ya scan karo", fontSize = 12.sp, color = Muted,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }

        // ---- Save / Share ----
        if (vm.outputs.isNotEmpty()) {
            Text(
                "Ready: ${vm.outputs.size} file · ${formatSize(vm.outputSize)}",
                color = Ink, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.save() }, enabled = !vm.busy,
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E5B))
                ) { Text("💾  Save", fontSize = 16.sp) }
                Button(
                    onClick = { context.startActivity(OutputFiles.shareIntent(context, vm.outputs)) },
                    enabled = !vm.busy,
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25A244))
                ) { Text("📤  Share", fontSize = 16.sp) }
            }
        }

        FeatureStrip()
        Spacer(Modifier.height(8.dp))
    }

    zoomBitmap?.let { bmp -> ZoomDialog(bmp) { zoomBitmap = null } }
}

private const val MAX_PAGES_TEXT = MainViewModel.MAX_PAGES

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Blue),
            contentAlignment = Alignment.Center
        ) { Text("📄", fontSize = 26.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Ink)) { append("Bill ") }
                    withStyle(SpanStyle(color = Blue)) { append("Cleaner") }
                },
                fontSize = 30.sp, fontWeight = FontWeight.ExtraBold
            )
            Text("Photo to Clear PDF-Style Bill", fontSize = 14.sp, color = Ink)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun PreviewCard(
    label: String,
    labelColor: Color,
    bitmap: Bitmap?,
    emptyText: String,
    modifier: Modifier,
    onClick: (Bitmap) -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(labelColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF1F3F6))
                    .then(if (bitmap != null) Modifier.clickable { onClick(bitmap) } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(emptyText, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    text: String,
    checked: Boolean,
    enabled: Boolean = true,
    hint: String? = null,
    onChange: (Boolean) -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, color = Ink, fontSize = 15.sp)
            if (hint != null) Text(hint, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RadioRow(title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("  —  $note", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun FeatureStrip() {
    val items = listOf(
        Triple("✂", "Auto Crop", Color(0xFF7C5CE6)),
        Triple("↻", "Straighten", Color(0xFFEF5340)),
        Triple("☀", "Enhance", Color(0xFFF2B70F)),
        Triple("▤", "PDF-Style", Color(0xFF2E9E5B)),
        Triple("⇲", "Small Size", Color(0xFF1E88E5)),
    )
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        items.forEach { (icon, label, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(color),
                    contentAlignment = Alignment.Center
                ) { Text(icon, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 11.sp, color = Ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ZoomDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Zoom",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .border(1.dp, Color.White, CircleShape)
            ) { Text("✕ Band karo", color = Color.White) }
        }
    }
}
