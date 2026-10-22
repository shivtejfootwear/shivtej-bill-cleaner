package com.shivtej.billscanner

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shivtej.billscanner.ui.AddScreen
import com.shivtej.billscanner.ui.BillTheme
import com.shivtej.billscanner.ui.C
import com.shivtej.billscanner.ui.ExportScreen
import com.shivtej.billscanner.ui.HomeScreen
import com.shivtej.billscanner.ui.PhotoScreen
import com.shivtej.billscanner.ui.ProcessingScreen
import com.shivtej.billscanner.ui.BillScreen
import com.shivtej.billscanner.ui.TargetsScreen
import com.shivtej.billscanner.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        takeShared(intent)
        setContent { BillTheme(c.lang) { Root(c.sharedTick) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** A bill shared to the app (PDF or photos) goes straight to "add invoice". */
    private fun takeShared(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(i.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val c = container
        for (u in uris) {
            val type = contentResolver.getType(u) ?: i.type ?: ""
            c.pending += PendingPage(u, pdf = type.contains("pdf") || u.toString().lowercase().endsWith(".pdf"))
        }
        c.sharedTick++
    }
}

@Composable
fun Root(sharedTick: Int = 0) {
    val nav = rememberNavController()
    androidx.compose.runtime.LaunchedEffect(sharedTick) { if (sharedTick > 0) nav.navigate("add") { launchSingleTop = true } }
    Box(Modifier.fillMaxSize().background(C.navy).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            NavHost(nav, startDestination = "home") {
                composable("home") { HomeScreen(onScan = { nav.navigate("add") }, onOpen = { nav.navigate("inv/$it") }, onSettings = { nav.navigate("settings") }) }
                composable("add") { AddScreen(onBack = { nav.popBackStack() }, onStart = { nav.navigate("scan") }) }
                composable("scan") {
                    ProcessingScreen(onDone = { id -> nav.navigate("inv/$id") { popUpTo("home") } }, onBack = { nav.popBackStack() })
                }
                composable("inv/{id}") { e ->
                    val id = e.arguments!!.getString("id")!!
                    BillScreen(id, onBack = { nav.popBackStack() }, onTargets = { nav.navigate("inv/$id/targets") },
                        onExport = { nav.navigate("inv/$id/export") }, onPhoto = { nav.navigate("inv/$id/photo") }, onView = { nav.navigate("inv/$id/view") })
                }
                composable("inv/{id}/targets") { e ->
                    val id = e.arguments!!.getString("id")!!
                    TargetsScreen(id, onBack = { nav.popBackStack() }, onExport = { nav.navigate("inv/$id/export") })
                }
                composable("inv/{id}/export") { e -> ExportScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
                composable("inv/{id}/view") { e -> FullBillScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
                composable("inv/{id}/photo") { e -> PhotoScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
