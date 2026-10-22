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
import com.shivtej.billscanner.ocr.PdfBill
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

    private fun report(name: String, dir: File, pages: List<OcrPage>) {
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

    /**
     * A bill PDF made by billing software (text inside, like the ones on WhatsApp): the Dharashiv R-2395 lines
     * written into a 2-page PDF, then read back with [PdfBill] — no OCR involved.
     */
    @Test fun pdfDigital() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val dir = File(ins.targetContext.filesDir, "ocrtest/pdf_digital").apply { mkdirs() }
        val f = File(dir, "dharashiv.pdf")
        val doc = android.graphics.pdf.PdfDocument()
        val p = android.graphics.Paint().apply { textSize = 7f; color = android.graphics.Color.BLACK }
        val big = android.graphics.Paint(p).apply { textSize = 12f; isFakeBoldText = true }
        fun header(c: android.graphics.Canvas, title: String) {
            c.drawText(title, 260f, 20f, p)
            c.drawText("DHARASHIV FOOTWEAR PVT LTD", 170f, 45f, big)
            c.drawText("B-6, MIDC, OSMANABAD GSTIN/UIN: 27AAFCD3805J1ZO", 180f, 60f, p)
            c.drawText("Buyer : Shivtej Footwear..........Akluj", 15f, 85f, p)
            c.drawText("Invoice No : R-2395", 310f, 95f, p); c.drawText("Date : 27-Jul-26", 450f, 95f, p)
            c.drawText("Party GST No: 27ECIPS0222J1ZI", 15f, 105f, p)
            listOf(10f to "Sr.No", 60f to "Item Description", 290f to "HSN", 328f to "GST%", 360f to "Size", 392f to "MRP", 425f to "Qty",
                463f to "Rate", 493f to "Disc.", 548f to "Amount").forEach { (x, t) -> c.drawText(t, x, 125f, p) }
        }
        fun right(c: android.graphics.Canvas, x: Float, y: Float, t: String) = c.drawText(t, x - p.measureText(t), y, p)
        fun line(c: android.graphics.Canvas, y: Float, l: List<String>) {
            c.drawText(l[0] + ".", 10f, y, p); c.drawText(l[1], 30f, y, p); c.drawText("64029990", 285f, y, p); c.drawText("5 %", 330f, y, p)
            c.drawText(l[2], 358f, y, p); right(c, 412f, y, l[3]); c.drawText(l[4] + " pair", 420f, y, p); right(c, 483f, y, l[5])
            right(c, 517f, y, l[6] + " %"); right(c, 587f, y, l[7])
        }
        val rows = DIGITAL.lines().filter { it.isNotBlank() }.map { it.split("|") }
        var pg = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 842, 1).create())
        header(pg.canvas, "TAX INVOICE")
        var y = 140f
        for (r in rows.take(rows.size - 1)) { line(pg.canvas, y, r); y += 13f }
        pg.canvas.drawText("continued ...", 500f, y + 12f, p)
        doc.finishPage(pg)
        pg = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 842, 2).create())
        header(pg.canvas, "Tax Invoice(Page 2)")
        line(pg.canvas, 140f, rows.last())
        y = 420f
        pg.canvas.drawText("Total", 383f, y, p); pg.canvas.drawText("212 pair", 418f, y, p); right(pg.canvas, 587f, y, "1,02,237.72")
        pg.canvas.drawText("Output CGST @2.5% 2.50 %", 393f, y + 12f, p); right(pg.canvas, 587f, y + 12f, "2,555.93")
        pg.canvas.drawText("Output SGST @2.5% 2.50 %", 393f, y + 21f, p); right(pg.canvas, 587f, y + 21f, "2,555.93")
        pg.canvas.drawText("Round Off", 466f, y + 30f, p); right(pg.canvas, 587f, y + 30f, "0.42")
        pg.canvas.drawText("₹", 520f, y + 40f, big); right(pg.canvas, 587f, y + 40f, "1,07,350.00")
        doc.finishPage(pg)
        f.outputStream().use { doc.writeTo(it) }; doc.close()
        val t0 = System.currentTimeMillis()
        val read = PdfBill.read(ins.targetContext, f)
        log("[pdf_digital] pages=${read.size} fromText=${read.map { it.fromText }} words=${read.map { it.words.words.size }} ms=${System.currentTimeMillis() - t0}")
        read.forEachIndexed { i, r -> File(dir, "ocr_${i + 1}.tsv").writeText(OcrCodec.encode(r.words)) }
        report("pdf_digital", dir, read.map { it.words })
    }

    /** A PDF that is only a scanned picture: rendered and read with the OCR. */
    @Test fun pdfScanned() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val dir = File(ins.targetContext.filesDir, "ocrtest/pdf_scanned").apply { mkdirs() }
        val bmp = ins.context.assets.open("dharashiv_v3617_screenshot.jpg").use { BitmapFactory.decodeStream(it) }
        val f = File(dir, "v3617_scan.pdf")
        val doc = android.graphics.pdf.PdfDocument()
        val w = 595; val h = (595f * bmp.height / bmp.width).toInt()
        val pg = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, 1).create())
        pg.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(pg)
        f.outputStream().use { doc.writeTo(it) }; doc.close()
        val read = PdfBill.read(ins.targetContext, f)
        log("[pdf_scanned] pages=${read.size} fromText=${read.map { it.fromText }} words=${read.map { it.words.words.size }}")
        report("pdf_scanned", dir, read.map { it.words })
    }

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
        report(name, dir, pages)
        // The self-check: doubtful lines cut out, enlarged, read again.
        val before = InvoiceParser.parse(pages).items.count { it.needs.isNotEmpty() }
        val t0 = System.currentTimeMillis()
        val checked = com.shivtej.billscanner.ocr.SelfCheck.run(pages, bitmaps.map { b -> com.shivtej.billscanner.ocr.SelfCheck.Source { b } }, { InvoiceParser.parse(it) })
        val after = checked.parsed.items.count { it.needs.isNotEmpty() }
        log("[$name] SELFCHECK tried=${checked.tried} fixed=${checked.fixed} doubtful before=$before after=$after ms=${System.currentTimeMillis() - t0}")
        val dir2 = File(dir, "selfcheck").apply { mkdirs() }
        checked.pages.forEachIndexed { i, p -> File(dir2, "ocr_${i + 1}.tsv").writeText(OcrCodec.encode(p)) }
        report("${name}_selfcheck", dir2, checked.pages)
    }

    @Test fun patel() = read("patel", listOf("patel_tradelines_g2_2627_1309.jpg"))

    @Test fun dharashivV3617() = read("v3617", listOf("dharashiv_v3617_screenshot.jpg"))

    @Test fun dharashiv() = read("dharashiv", listOf("dharashiv_r2395_page1_screenshot.jpg", "dharashiv_r2395_page2_screenshot.jpg"))
}

/** Dharashiv R-2395: Sr|Description|Size|MRP|Qty|Rate|Disc %|Amount — 50 lines on page 1, ASIAN on page 2. */
private val DIGITAL = """
1|SM 651 N.BLUE GREY|6X10|1,049|6|720.00|9.82|3,895.78
2|SM 1120 D.GREY/LT.GREY|6X10|1,099|5|754.29|9.82|3,401.09
3|SM 1067 D.GREY/LT.GREY|7X10|1,049|6|720.00|9.82|3,895.78
4|SM 1105 D.GREY/LT.GREY|6X10|1,249|1|857.14|9.82|772.97
5|SM 1105 D.GREY/ LT.GREY|7X10|1,299|4|891.43|9.82|3,215.57
6|SM 722 GREY NEON ORANGE|7X10|1,399|6|960.00|9.82|5,194.37
7|SM 766 N.BLUE/ B.GRAY|7X10|1,049|1|720.00|9.82|649.30
8|SM 766 N.BLUE B.GREY|7X10|1,099|5|754.29|9.82|3,401.09
9|SM 472 GREY N ORANGE|11X12|1,699|4|1,165.71|9.82|4,204.95
10|SM 472 GREY NEON ORANGE|7X10|1,549|2|1,062.86|9.82|1,916.97
11|SM 472 BLACK RED|7X10|1,549|1|1,062.86|9.82|958.49
12|SM 472 BLACK RED|6X10|1,599|2|1,097.14|9.82|1,978.80
13|SM 472 BLACK RED|7X10|1,699|1|1,165.71|9.82|1,051.24
14|SFG 48 CAMEL F.ORANGE|6X10|549|2|377.14|9.82|680.21
15|SFG 48 CAMAL F ORANGE|7X10.|599|4|411.43|9.82|1,484.11
16|SFG 48 BLACK RED|7X10|599|14|411.43|9.82|5,194.39
17|SFG 192 BLACK SILVER|7X10|449.50|5|308.57|9.82|1,391.34
18|SFG 57 D.GREY F.ORANGE|6X10|999|9|685.71|9.82|5,565.36
19|SFG 57 BROWN/TAN|7X10|999|4|685.71|9.82|2,473.49
20|SFG 57 BLK YELLOW|6X10|999|2|685.71|9.82|1,236.75
21|SFG 143 BLACK WHITE|6X10|499.50|1|342.86|9.82|309.19
22|SFG 4049 GREY BLACK|8X10|499.50|6|342.86|9.82|1,855.15
23|SFG 4049 OLIVE BEIGE|6X10|449.50|2|308.57|9.82|556.54
24|SFG 183 CAMEL BROWN|6X10|949|1|651.43|9.82|587.46
25|SFG 183 CAMAL BROWN|6X10|999|4|685.71|9.82|2,473.49
26|SFG 196 FT.GREEN CAMEL|7X10|599|5|411.43|9.82|1,855.14
27|SFG 241 D.GREY NEON GREEN|8X10|549|6|377.14|9.82|2,040.63
28|SFG 241 BLACK M. ORANGE|7X10|549.50|8|377.14|9.82|2,720.84
29|SS 101 BLACK|6X10|825|9|565.71|9.82|4,591.42
30|SL 309 LT. GREY PINK|5X8|999|2|685.71|9.82|1,236.75
31|SFL 2115 BLK WHT|5X8|399.50|6|274.29|9.82|1,484.13
32|SFG 48 BLACK RED|11X12|649|4|445.71|9.82|1,607.77
33|SS 101 -BLACK|11X1|649|6|445.71|9.82|2,411.65
34|SFG 240 BROWN CAMEL|6X10|549|6|377.14|9.82|2,040.63
35|SFG 234 CAMEL BROWN|7X10|599|5|411.43|9.82|1,855.14
36|WLR 70414 MAUVE|5X8|429|6|286.67|7.45|1,591.88
37|SFG 241 BROWN BEIGN|7X10|499.50|8|342.86|9.82|2,473.53
38|SM 1090 N.BLUE/LT.GREY|9X10|849|2|582.86|9.82|1,051.25
39|SS 703 BLACK RED|7X10|649|1|445.71|9.82|401.94
40|SS 703 BLACK RED|7X10|599|2|411.43|9.82|742.06
41|SS 727 BROWN TAN|7X10|749|6|514.29|9.82|2,782.72
42|SS 481 BLACK BEIGE|7X10|999|2|685.71|9.82|1,236.75
43|SM 722 BLACK GOLD|7X10|1,299|2|891.43|9.82|1,607.78
44|SS 728 BLACK RED|7X10|675|6|462.86|9.82|2,504.44
45|SS 729 TAN BLACK|7X10|675|6|462.86|9.82|2,504.44
46|SS 727 FT. GREEN PISTA|6X10|749|6|514.29|9.82|2,782.72
47|SL 309 LT. GREY PINK|5X8|999|1|685.71|9.82|618.37
48|FL 366 BEIGN MAROON|5X8|164.50|1|113.33|9.82|102.20
49|SFG 57 BROWN/TAN|7X10|899.50|1|617.14|9.82|556.54
50|FL 532 PISTA|5X7|219.50|1|151.43|9.82|136.56
51|ASIAN SAFARI-15 BLACK-GREY|7X10|279|6|265.71|40|956.56
"""
