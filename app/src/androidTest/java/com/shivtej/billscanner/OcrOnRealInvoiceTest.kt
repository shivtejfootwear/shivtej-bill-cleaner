package com.shivtej.billscanner

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shivtej.billscanner.core.export.InvoiceExport
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.store.InvoiceCodec
import com.shivtej.billscanner.core.store.OcrCodec
import com.shivtej.billscanner.export.Pdf
import com.shivtej.billscanner.ocr.Ocr
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real on-device OCR on real bill photos and writes what it found.
 * It does not fail on wrong values: the output is for checking accuracy by eye.
 */
@RunWith(AndroidJUnit4::class)
class OcrOnRealInvoiceTest {
    private fun log(s: String) = s.chunked(3000).forEach { Log.i("OCRTEST", it) }

    private fun read(name: String, assets: List<String>) = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val dir = File(ins.targetContext.filesDir, "ocrtest/$name").apply { mkdirs() }
        val pages = mutableListOf<OcrPage>()
        val bitmaps = assets.map { a -> ins.context.assets.open(a).use { BitmapFactory.decodeStream(it) } }
        for ((i, bmp) in bitmaps.withIndex()) {
            val t0 = System.currentTimeMillis()
            val page = Ocr.read(bmp)
            log("[$name] page ${i + 1}: words=${page.words.size} size=${page.width}x${page.height} ms=${System.currentTimeMillis() - t0}")
            File(dir, "ocr_${i + 1}.tsv").writeText(OcrCodec.encode(page))
            pages += page
        }
        val parsed = InvoiceParser.parse(pages)
        val inv = Invoice(name, System.currentTimeMillis(), parsed.header, parsed.header, parsed.items, columns = parsed.columns)
        val h = inv.header
        log("[$name] HEADER supplier=${h.supplierName} | gstin=${h.supplierGstin} | no=${h.invoiceNo} | date=${h.invoiceDate} | buyer=${h.buyerName} | buyerGstin=${h.buyerGstin} | grand=${h.printedGrandTotal} | cgst=${h.cgstAmt} sgst=${h.sgstAmt} | qty=${h.printedQty} sub=${h.printedSubtotal}")
        log("[$name] COLUMNS " + parsed.columns.joinToString(" | ") { "${it.label}=${it.field}" })
        for (i in inv.items) {
            val v = i.current
            log("[$name] ITEM ${v.sr} | ${v.article} | col=${v.colour} | size=${v.sizeText} | qty=${v.qty} | mrp=${v.mrp} | rate=${v.rate} | disc%=${v.discPct} | amount=${v.taxable} | gst=${v.gstPct} | baitha=${Calc.sittingPct(v, h)} | needs=${i.needs} | raw=${i.raw}")
        }
        log("[$name] TOTALS ${Totals.of(inv)}")
        parsed.notes.forEach { log("[$name] NOTE $it") }
        Warnings.of(inv).forEach { log("[$name] WARN ${it.level} ${it.english}") }
        File(dir, "invoice.json").writeText(InvoiceCodec.encode(inv))
        File(dir, "$name.xlsx").outputStream().use { InvoiceExport.xlsx(inv, it) }
        File(dir, "${name}_report.pdf").outputStream().use { Pdf.report(inv, true, it) }
        log("[$name] FILES ${dir.absolutePath}")
    }

    @Test fun patel() = read("patel", listOf("patel_tradelines_g2_2627_1309.jpg"))

    @Test fun dharashivV3617() = read("v3617", listOf("dharashiv_v3617_screenshot.jpg"))

    @Test fun dharashiv() = read("dharashiv", listOf("dharashiv_r2395_page1_screenshot.jpg", "dharashiv_r2395_page2_screenshot.jpg"))
}
