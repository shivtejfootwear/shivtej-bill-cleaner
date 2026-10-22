package com.shivtej.billscanner

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
import com.shivtej.billscanner.ui.AnalysisScreen
import com.shivtej.billscanner.ui.BillTheme
import com.shivtej.billscanner.ui.C
import com.shivtej.billscanner.ui.ExportScreen
import com.shivtej.billscanner.ui.HomeScreen
import com.shivtej.billscanner.ui.ItemsScreen
import com.shivtej.billscanner.ui.PhotoScreen
import com.shivtej.billscanner.ui.ProcessingScreen
import com.shivtej.billscanner.ui.ReviewScreen
import com.shivtej.billscanner.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent { BillTheme(c.lang) { Root() } }
    }
}

@Composable
fun Root() {
    val nav = rememberNavController()
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
                    ReviewScreen(id, onBack = { nav.popBackStack() }, onItems = { nav.navigate("inv/$id/items") }, onAnalysis = { nav.navigate("inv/$id/analysis") },
                        onExport = { nav.navigate("inv/$id/export") }, onPhoto = { nav.navigate("inv/$id/photo") })
                }
                composable("inv/{id}/items") { e -> ItemsScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
                composable("inv/{id}/analysis") { e ->
                    val id = e.arguments!!.getString("id")!!
                    AnalysisScreen(id, onBack = { nav.popBackStack() }, onExport = { nav.navigate("inv/$id/export") })
                }
                composable("inv/{id}/export") { e -> ExportScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
                composable("inv/{id}/photo") { e -> PhotoScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }) }
                composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }
}
