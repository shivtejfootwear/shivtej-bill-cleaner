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
import com.shivtej.billscanner.ui.BillContent
import com.shivtej.billscanner.ui.TargetsContent
import com.shivtej.billscanner.ui.BillTheme
import com.shivtej.billscanner.ui.ExportContent
import com.shivtej.billscanner.ui.HomeContent
import com.shivtej.billscanner.ui.CheckContent
import com.shivtej.billscanner.ui.HomeFilter
import com.shivtej.billscanner.ui.ItemSheet
import com.shivtej.billscanner.ui.ProcessingContent
import com.shivtej.billscanner.ui.SettingsContent
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
        shot("01_home") { HomeContent(listOf(dharashiv(), a, b), "", {}, HomeFilter.ALL, {}, {}, {}, {}, now) }
    }

    @Test fun s01d_home_check() {
        val d = dharashiv(); val got = d.items.take(3).associate { it.id to 100 }
        shot("01d_home_check") { HomeContent(listOf(d, invoice()), "", {}, HomeFilter.ALL, {}, {}, {}, {}, now, checks = mapOf(d.id to got)) }
    }

    @Test fun s30_goods_check() {
        val d = dharashiv(); val got = d.items.take(4).associate { it.id to 100 } + (d.items[5].id to 1)
        shot("30_goods_check") { CheckContent(d, got, { _, _ -> }, {}) }
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

    private fun dharashiv(targets: Boolean = false): Invoice {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(), DharashivInvoice.page2()))
        var inv = Invoice("dh", now, p.header, p.header, p.items, columns = p.columns)
        if (targets) { inv = Edits.setAllTargets(inv, BigDecimal("35"), now); inv = Edits.setGroupTarget(inv, "ASIAN", BigDecimal("40"), now) }
        return inv
    }

    @Test fun s10_fix_line() {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(blankDiscOf = setOf(36)), DharashivInvoice.page2()))
        val inv = Invoice("dh", now, p.header, p.header, p.items, columns = p.columns)
        val item = com.shivtej.billscanner.ui.doubtful(inv).first()
        shot("10_fix_line") { com.shivtej.billscanner.ui.FixContent(inv, item, null, 0, 1, null, {}, {}, {}, {}) }
    }

    @Test fun s10b_doubtful_popup() {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(blankDiscOf = setOf(36)), DharashivInvoice.page2()))
        val inv = Invoice("dh", now, p.header, p.header, p.items, columns = p.columns)
        shot("10b_doubtful_popup") { BillContent(inv, Warnings.of(inv), null, {}, {}, {}, {}); com.shivtej.billscanner.ui.DoubtfulDialog(inv, {}, {}) }
    }

    @Test fun s04_bill_dharashiv() {
        val inv = dharashiv()
        shot("04_bill_dharashiv") { BillContent(inv, Warnings.of(inv), null, {}, {}, {}, {}) }
    }

    @Test fun s04b_bill_dharashiv_targets() {
        val inv = dharashiv(targets = true)
        shot("04b_bill_with_new_rates") { BillContent(inv, Warnings.of(inv), null, {}, {}, {}, {}) }
    }

    @Test fun s04c_bill_patel() {
        val inv = invoice().copy(columns = InvoiceParser.parse(listOf(PatelInvoice.page(setOf(5)))).columns)
        shot("04c_bill_patel_warnings") { BillContent(inv, Warnings.of(inv), null, {}, {}, {}, {}, showWarnings = true) }
    }

    @Config(qualifiers = "w393dp-h1700dp-xxhdpi")
    @Test fun s06_item_sheet() {
        val inv = invoice()
        val item = inv.items[4]   // line 5: printed % unreadable -> Needs Verification
        shot("06_item_sheet") { ItemSheet(item, {}, {}, {}, {}, {}, {}, {}, header = inv.header) }
    }

    @Config(qualifiers = "w393dp-h1500dp-xxhdpi")
    @Test fun s07_targets() {
        val inv = dharashiv(targets = true)
        shot("07_targets_company_wise") { TargetsContent(inv, listOf(30, 35, 40, 45, 50), {}, {}, {}, { _, _ -> }, {}, {}) }
    }

    @Config(qualifiers = "w393dp-h1300dp-xxhdpi")
    @Test fun s08_export() = shot("08_export") { ExportContent(invoice(), false, {}, null, null, {}, { _, _ -> }) }

    @Test fun s09_settings() = shot("09_settings") { SettingsContent("hi", {}, listOf(30, 35, 40, 45, 50), {}, {}, false, {}, {}) }
}
