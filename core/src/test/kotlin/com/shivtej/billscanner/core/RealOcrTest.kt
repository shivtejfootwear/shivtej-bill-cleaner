package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.store.OcrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * What the phone's own OCR (ML Kit, on an Android emulator) really returned for real bills, read by the parser.
 * These are the real problems: lost serial numbers, glued words ("640299905%6X10", "pair1,165.719.82"),
 * lost decimal points ("745 %"), a bent photo, a second page inside one screenshot, a stamp beside the table.
 */
class RealOcrTest {
    private fun page(name: String): OcrPage = OcrCodec.decode(javaClass.classLoader!!.getResource("real/$name")!!.readText())
    private fun bd(s: String) = BigDecimal(s.replace(",", ""))

    @Test fun dharashivV3617ScreenshotOfTwoPages() {
        val p = InvoiceParser.parse(listOf(page("v3617_1.tsv")))
        assertEquals(54, p.items.size)
        assertEquals((1..54).toList(), p.items.map { it.current.sr })
        assertEquals("V-3617", p.header.invoiceNo)
        assertEquals("DHARASHIV FOOTWEAR PVT LTD", p.header.supplierName)
        assertTrue(p.header.buyerName!!.startsWith("Shivtej Footwear"))
        // Lines 46–50 are partly covered by an app button in the screenshot: they must be flagged, every other line exact.
        for (it in p.items) {
            val sr = it.current.sr!!
            if (sr in 46..50) assertTrue("line $sr should be flagged", it.needs.isNotEmpty())
            else assertTrue("line $sr: ${it.needs} ${it.raw}", it.needs.isEmpty())
        }
        val l21 = p.items[20].current          // "745%" read without its point
        assertEquals(0, l21.discPct!!.compareTo(bd("7.45"))); assertEquals(0, l21.taxable!!.compareTo(bd("2147.18")))
        val l6 = p.items[5].current            // "1.29 5.68" = 1,295.68
        assertEquals(0, l6.taxable!!.compareTo(bd("1295.68")))
        val l3 = p.items[2].current            // "640299905%6X10" = HSN + GST + size
        assertEquals("64029990", l3.hsn); assertEquals("6X10", l3.sizeText); assertEquals(0, l3.gstPct!!.compareTo(BigDecimal(5)))
        assertEquals("11X13", p.items[36].current.sizeText)
    }

    @Test fun dharashivR2395BentPhoto() {
        val p = InvoiceParser.parse(listOf(page("dharashiv_r2395_1.tsv"), page("dharashiv_r2395_2.tsv")))
        val inv = Invoice("d", 0, p.header, p.header, p.items, columns = p.columns)
        // Page 1: all 50 lines, serial numbers the photo lost filled in, every value as printed.
        assertEquals((1..50).toList(), p.items.map { it.current.sr })
        for ((i, l) in DharashivInvoice.LINES.withIndex()) {
            val v = p.items[i].current
            assertEquals("line ${l.sr} qty", l.qty, v.qty!!.toInt())
            assertEquals("line ${l.sr} mrp", 0, v.mrp!!.compareTo(bd(l.mrp)))
            assertEquals("line ${l.sr} rate", 0, v.rate!!.compareTo(bd(l.rate)))
            assertEquals("line ${l.sr} disc", 0, v.discPct!!.compareTo(bd(l.disc)))
            assertEquals("line ${l.sr} amount", 0, v.taxable!!.compareTo(bd(l.amount)))
        }
        // Page 2 is a crooked photo: its one line (ASIAN) is not read, but the totals are, so the gap shows.
        assertEquals(0, p.header.printedGrandTotal!!.compareTo(bd("107350.00")))
        assertEquals(0, p.header.cgstAmt!!.compareTo(bd("2555.93")))
        assertEquals(0, Totals.of(inv).difference!!.abs().compareTo(bd("956.56")))
    }

    @Test fun patelWithStampAndGluedSerial() {
        val p = InvoiceParser.parse(listOf(page("patel_1309_1.tsv")))
        assertEquals("PATEL TRADELINES", p.header.supplierName)
        assertEquals((1..11).toList(), p.items.map { it.current.sr })
        assertEquals(0, p.items.filter { it.kind == com.shivtej.billscanner.core.model.ItemKind.ARTICLE }.fold(BigDecimal.ZERO) { a, i -> a + i.current.qty!! }.compareTo(BigDecimal(134)))   // = "Packing/Cartoon: (134)"
        assertEquals("1251", p.items[2].current.article)      // "31251-Paralite…" = serial 3 + article 1251
        assertTrue(p.columns.none { it.label.contains("PROPRIETOR") })
        assertTrue(p.items.filter { it.current.sr != 11 }.all { it.needs.isEmpty() })
    }

    /**
     * LAKHANI FOOTWEAR (Crystal Reports PDF, 1972/26-27/R266): Old MRP and New MRP columns, cartons and pairs columns,
     * bill-level "LESS : per carton / 2% special / 4% prompt payment" and "ADD : insurance" lines, IGST, then an HSN tax summary.
     */
    @Test fun lakhaniOldAndNewMrpCartonsAndBillDiscounts() {
        val p = InvoiceParser.parse(listOf(page("lakhani_1972_1.tsv")))
        assertEquals((1..9).toList(), p.items.map { it.current.sr })
        assertEquals("1972/26-27/R266", p.header.invoiceNo)
        assertEquals("06AAACL3112H1Z2", p.header.supplierGstin)
        assertTrue(p.header.buyerName.orEmpty(), p.header.buyerName!!.contains("SHIVTEJ FOOTWEAR"))
        for (it in p.items) {
            val v = it.current
            assertTrue("line ${v.sr}: ${it.needs}", it.needs.isEmpty())
            assertEquals("line ${v.sr}: pairs, not cartons", 12, v.qty!!.toInt())
            assertTrue("line ${v.sr}: colour stays out of HSN", v.hsn!!.all(Char::isDigit))
        }
        // New MRP, never the old one.
        assertEquals(0, p.items[0].current.mrp!!.compareTo(bd("1899.00")))
        assertEquals(0, p.items[5].current.mrp!!.compareTo(bd("1749.00")))
        assertEquals(0, p.items[8].current.mrp!!.compareTo(bd("1949.00")))
        assertEquals("7*10", p.items[0].current.sizeText)
        // The bill total, not the HSN summary's taxable value.
        assertEquals(0, p.header.printedGrandTotal!!.compareTo(bd("106153.00")))
        assertEquals(0, p.header.igstAmt!!.compareTo(bd("5054.87")))
        // Bill-level discounts and charges spread over every line: lines 108972 → 101097.78 before tax.
        val f = p.header.costFactor!!
        assertEquals(0, (bd("108972.00") * f).setScale(2, java.math.RoundingMode.HALF_UP).compareTo(bd("101097.78")))
        val inv = Invoice("l", 0, p.header, p.header, p.items, columns = p.columns)
        val t = Totals.of(inv)
        assertTrue("difference ${t.difference}", t.difference == null || t.difference!!.abs() <= BigDecimal.ONE)
    }

    /** The same Lakhani PDF as the phone reads it (its own text, letter-tall boxes): heading on two lines far apart, IGST amount apart. */
    @Test fun lakhaniPdfAsThePhoneReadsIt() {
        val p = InvoiceParser.parse(listOf(page("lakhani_1972_phone.tsv")))
        assertEquals((1..9).toList(), p.items.map { it.current.sr })
        assertTrue(p.items.all { it.needs.isEmpty() && it.current.qty!!.toInt() == 12 })
        assertEquals(0, p.header.igstAmt!!.compareTo(bd("5054.87")))
        val inv = Invoice("l", 0, p.header, p.header, p.items, columns = p.columns)
        assertEquals(0, Totals.of(inv).difference!!.signum())
    }

    @Test fun sameBillAddedTwiceKeepsOneCopy() {
        val pg = page("v3617_1.tsv")
        val p = InvoiceParser.parse(listOf(pg, pg))
        assertEquals((1..54).toList(), p.items.map { it.current.sr })
        assertTrue(p.notes.any { it.contains("दो बार") })
    }

    @Test fun lostPairsAndLostPointTogetherAreWorkedOut() {
        // Line 32 (WLR 78041 PLUM: 12 pair, 213.33, "745 %", 2,369.24) with the "12" of "12 pair" lost as well.
        val pg = page("v3617_1.tsv")
        val y = pg.words.first { it.text == "78041" }.box.cy
        val lost = pg.copy(words = pg.words.filterNot { it.text == "12" && kotlin.math.abs(it.box.cy - y) < 12 })
        val l = InvoiceParser.parse(listOf(lost)).items.first { it.current.sr == 32 }
        assertEquals(0, l.current.qty!!.compareTo(BigDecimal(12)))
        assertEquals(0, l.current.discPct!!.compareTo(bd("7.45")))
        assertTrue(l.needs.isEmpty())
    }

    @Test fun eachLineKnowsWhereItIsOnThePhoto() {
        val pg = page("v3617_1.tsv")
        val p = InvoiceParser.parse(listOf(pg))
        val at = p.items[31].at!!                                    // line 32: "32. WLR 78041 PLUM … 2,369.24" at y≈1121–1141
        assertTrue("$at", at.t in 1100..1125 && at.b in 1135..1165 && at.l < 30 && at.r > 1000 && at.pageW == pg.width)
        val inv = Invoice("x", 0, p.header, p.header, p.items, columns = p.columns)
        val back = com.shivtej.billscanner.core.store.InvoiceCodec.decode(com.shivtej.billscanner.core.store.InvoiceCodec.encode(inv))
        assertEquals(inv.items.map { it.at }, back.items.map { it.at })
    }

    @Test fun aLineThatSlidSidewaysIsDealtAgainByWhatItsWordsLookLike() {
        // Line 53 (GP 4258 CAMEL … 319 | 8 pair | 213.33 | 7.45 % | 1,579.50) with everything after the description
        // moved one column to the left, as on the phone: HSN under GST, "8 pair 213.33" under Qty, "7.45% 1,579.50" under Rate.
        val pg = page("v3617_1.tsv")
        val y = pg.words.first { it.text == "4258" }.box.cy
        val moved = pg.copy(words = pg.words.map { w ->
            if (kotlin.math.abs(w.box.cy - y) < 12 && w.box.l > 500) w.copy(box = w.box.copy(l = w.box.l - 70, r = w.box.r - 70)) else w
        })
        val l = InvoiceParser.parse(listOf(moved)).items.first { it.current.sr == 53 }
        assertTrue("${l.needs} ${l.raw}", l.needs.isEmpty())
        assertEquals(0, l.current.qty!!.compareTo(BigDecimal(8)))
        assertEquals(0, l.current.rate!!.compareTo(bd("213.33")))
        assertEquals(0, l.current.taxable!!.compareTo(bd("1579.50")))
        assertEquals(0, l.current.mrp!!.compareTo(BigDecimal(319)))
    }

    @Test fun bankAndSignatureLinesAreNeverArticles() {
        val p = InvoiceParser.parse(listOf(page("dharashiv_r2395_1.tsv"), page("dharashiv_r2395_2.tsv")))
        assertTrue(p.items.none { com.shivtej.billscanner.core.ocr.InvoiceParser.NOT_ARTICLE.containsMatchIn(it.raw.joinToString(" ")) })
    }
}
