package com.shivtej.billscanner

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.ui.AddContent
import com.shivtej.billscanner.ui.AnalysisContent
import com.shivtej.billscanner.ui.BillTheme
import com.shivtej.billscanner.ui.ExportContent
import com.shivtej.billscanner.ui.HomeContent
import com.shivtej.billscanner.ui.HomeFilter
import com.shivtej.billscanner.ui.ItemSheet
import com.shivtej.billscanner.ui.ItemsContent
import com.shivtej.billscanner.ui.ProcessingContent
import com.shivtej.billscanner.ui.ReviewContent
import com.shivtej.billscanner.ui.SettingsContent
import com.shivtej.billscanner.ui.SortBy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal

/** Pictures of every screen, filled through the real parser with the PATEL TRADELINES bill. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h852dp-xxhdpi", sdk = [34])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = File("build/screenshots").apply { mkdirs() }
    private val now = 1_761_000_000_000L   // 21-Oct-2025 style fixed clock

    private fun invoice(blank: Set<Int> = setOf(5), target: BigDecimal? = null): Invoice {
        val p = InvoiceParser.parse(listOf(PatelInvoice.page(blank)))
        return Invoice("patel", now, p.header, p.header, p.items, defaultTargetPct = target)
    }

    private fun shot(name: String, lang: String = "hi", content: @Composable () -> Unit) {
        compose.setContent { BillTheme(lang) { content() } }
        compose.waitForIdle()
        captureScreenRoboImage(File(out, "$name.png").path)
    }

    @Test fun s01_home() {
        val a = invoice()
        val b = Edits.setStatus(invoice(blank = emptySet()).copy(id = "b", createdAt = now - 86_400_000L * 3), InvoiceStatus.VERIFIED, now)
        shot("01_home") { HomeContent(listOf(a, b), "", {}, HomeFilter.ALL, {}, {}, {}, {}, now) }
    }

    @Test fun s01b_home_empty() = shot("01b_home_empty") { HomeContent(emptyList(), "", {}, HomeFilter.ALL, {}, {}, {}, {}, now) }

    @Test fun s01c_home_english() = shot("01c_home_english", "en") { HomeContent(listOf(invoice()), "", {}, HomeFilter.ALL, {}, {}, {}, {}, now) }

    @Test fun s02_add() {
        val pages = listOf(PendingPage(Uri.parse("content://x/1")), PendingPage(Uri.parse("content://x/2"), 90))
        shot("02_add") { AddContent(pages, null, {}, {}, {}, {}, {}, {}) }
    }

    @Test fun s02b_add_empty() = shot("02b_add_empty") { AddContent(emptyList(), null, {}, {}, {}, {}, {}, {}) }

    @Test fun s03_processing() = shot("03_processing") { ProcessingContent(ScanStep.FIELDS, 0.62f, 1, null, {}) }

    @Test fun s03b_processing_failed() = shot("03b_processing_failed") { ProcessingContent(ScanStep.FAILED, 0.3f, 1, "Photo could not be read", {}) }

    @Config(qualifiers = "w393dp-h1900dp-xxhdpi")
    @Test fun s04_review() {
        val inv = invoice()
        shot("04_review") { ReviewContent(inv, Warnings.of(inv), null, {}, {}, {}, {}, {}) }
    }

    @Test fun s05_items_cards() {
        val inv = invoice()
        shot("05_items_cards") { ItemsContent(inv, "", {}, SortBy.SR, {}, false, {}, {}, {}) }
    }

    @Test fun s05b_items_table() {
        val inv = invoice()
        shot("05b_items_table") { ItemsContent(inv, "", {}, SortBy.SR, {}, true, {}, {}, {}) }
    }

    @Config(qualifiers = "w393dp-h1700dp-xxhdpi")
    @Test fun s06_item_sheet() {
        val inv = invoice()
        val item = inv.items[4]   // line 5: printed % unreadable -> Needs Verification
        shot("06_item_sheet") { ItemSheet(item, {}, {}, {}, {}, {}, {}, {}) }
    }

    @Config(qualifiers = "w393dp-h1300dp-xxhdpi")
    @Test fun s07_analysis() {
        val inv = invoice(target = BigDecimal("35"))
        shot("07_analysis") { AnalysisContent(inv, listOf(30, 35, 40, 45, 50), {}, {}, {}, {}, {}) }
    }

    @Config(qualifiers = "w393dp-h1300dp-xxhdpi")
    @Test fun s08_export() = shot("08_export") { ExportContent(invoice(), false, {}, null, null, {}, { _, _ -> }) }

    @Test fun s09_settings() = shot("09_settings") { SettingsContent("hi", {}, listOf(30, 35, 40, 45, 50), {}, {}, false, {}, {}) }
}
