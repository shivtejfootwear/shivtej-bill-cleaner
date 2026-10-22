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
import com.shivtej.billscanner.core.store.InvoiceCodec
import com.shivtej.billscanner.export.Pdf
import com.shivtej.billscanner.ocr.Ocr
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real on-device OCR on the real PATEL TRADELINES photo and writes what it found.
 * It does not fail on wrong values: the output is for checking accuracy by eye.
 */
@RunWith(AndroidJUnit4::class)
class OcrOnRealInvoiceTest {
    private fun log(s: String) = s.chunked(3000).forEach { Log.i("OCRTEST", it) }

    @Test fun readPatelInvoice() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val bytes = ins.context.assets.open("patel_tradelines_g2_2627_1309.jpg").use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val t0 = System.currentTimeMillis()
        val page = Ocr.read(bmp)
        log("OCR words=${page.words.size} size=${page.width}x${page.height} ms=${System.currentTimeMillis() - t0}")
        val parsed = InvoiceParser.parse(listOf(page))
        val inv = Invoice("real", System.currentTimeMillis(), parsed.header, parsed.header, parsed.items)
        val h = inv.header
        log("HEADER supplier=${h.supplierName} | gstin=${h.supplierGstin} | no=${h.invoiceNo} | date=${h.invoiceDate} | order=${h.orderNo} | buyer=${h.buyerName} | buyerGstin=${h.buyerGstin} | grand=${h.printedGrandTotal} | packing=${h.packing} | page=${h.pageNote}")
        for (i in inv.items) {
            val v = i.current
            log("ITEM ${v.sr} | ${i.kind} | ${v.article} | ${v.name} | col=${v.colour} | sizes=${v.sizeText} | hsn=${v.hsn} | qty=${v.qty} | mrp=${v.mrp} | rate=${v.rate} | gross=${v.gross} | disc%=${v.discPct} | discAmt=${v.discAmt} | taxable=${v.taxable} | cgst=${v.cgstPct}/${v.cgstAmt} | sgst=${v.sgstPct}/${v.sgstAmt} | igst=${v.igstPct}/${v.igstAmt} | mrpDisc=${Calc.mrpDiscPct(v)} | needs=${i.needs}")
        }
        val t = Totals.of(inv)
        log("TOTALS $t")
        parsed.notes.forEach { log("NOTE $it") }
        Warnings.of(inv).forEach { log("WARN ${it.level} ${it.english}") }

        // Raw OCR words too, so the parser can be tuned against the real layout.
        val dir = ins.targetContext.getExternalFilesDir(null)!!.apply { mkdirs() }
        File(dir, "ocr_words.tsv").writeText(page.words.joinToString("\n") { "${it.box.l}\t${it.box.t}\t${it.box.r}\t${it.box.b}\t${it.text}" })
        File(dir, "invoice.json").writeText(InvoiceCodec.encode(inv))
        File(dir, "real_invoice.xlsx").outputStream().use { InvoiceExport.xlsx(inv, it) }
        File(dir, "real_report_landscape.pdf").outputStream().use { Pdf.report(inv, true, it) }
        File(dir, "real_report_portrait.pdf").outputStream().use { Pdf.report(inv, false, it) }
        File(dir, "real_target_sheet.pdf").outputStream().use { Pdf.target(inv.copy(defaultTargetPct = java.math.BigDecimal("35")), it) }
        File(dir, "real_photo.pdf").outputStream().use { Pdf.photos(listOf(bmp), "PATEL TRADELINES G2/2627/1309", it) }
        log("FILES ${dir.absolutePath}")
    }
}
