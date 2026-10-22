package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Groups
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Target
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.InvoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A second, different bill layout: tax at the bottom, two pages, bent photo. */
class DharashivTest {
    private fun d(s: String) = BigDecimal(s.replace(",", ""))
    private val parsed = InvoiceParser.parse(listOf(DharashivInvoice.page1(), DharashivInvoice.page2()))
    private val inv = Invoice("dh", 0, parsed.header, parsed.header, parsed.items, columns = parsed.columns)

    @Test fun headerIsRead() {
        val h = parsed.header
        assertEquals("DHARASHIV FOOTWEAR PVT LTD", h.supplierName)
        assertEquals("27AAFCD3805J1ZO", h.supplierGstin)
        assertEquals("27ECIPS0222J1ZI", h.buyerGstin)
        assertEquals("R-2395", h.invoiceNo)
        assertEquals("27-Jul-26", h.invoiceDate)
        assertEquals("Shivtej Footwear Akluj", h.buyerName)
        assertEquals("B-6, MIDC, OSMANABAD", h.supplierAddress)
        assertEquals("9527671319", h.supplierContact)
    }

    @Test fun columnsAreTheBillsOwn() {
        assertEquals(listOf("Sr.No", "Item Description", "HSN", "GST%", "Size", "MRP", "Qty", "Rate", "Disc.", "Amount"), parsed.columns.map { it.label })
        assertEquals(listOf(F.SR, F.ARTICLE, F.HSN, F.GST_PCT, F.SIZE, F.MRP, F.QTY, F.RATE, F.DISC_PCT, F.TAXABLE), parsed.columns.map { it.field })
    }

    @Test fun all51LinesOverTwoPages() {
        assertEquals(parsed.notes.joinToString() + "\n" + parsed.items.joinToString("\n") { it.current.toString() }, 51, parsed.items.size)
        for ((i, l) in (DharashivInvoice.LINES + DharashivInvoice.ASIAN).withIndex()) {
            val it = parsed.items[i]; val v = it.current
            val msg = "line ${l.sr}: $v raw=${it.raw}"
            assertEquals(msg, l.sr, v.sr)
            assertEquals(msg, d(l.mrp), v.mrp)
            assertEquals(msg, BigDecimal(l.qty), v.qty)
            assertEquals(msg, d(l.rate), v.rate)
            assertEquals(msg, d(l.disc), v.discPct)
            assertEquals(msg, d(l.amount), v.taxable)
            assertEquals(msg, d("5"), v.gstPct)
            assertEquals(msg, "64029990", v.hsn)
            assertEquals(msg, l.size.trimEnd('.'), v.sizeText)
            assertEquals(msg, l.desc.split(' ').take(2).joinToString(" "), v.article)
            assertTrue(msg + " needs=${it.needs}", it.needs.isEmpty())
            // Printed copy keeps the bill's own text.
            assertEquals(msg, "${l.mrp}", it.raw[5]); assertEquals(msg, "${l.qty} pair", it.raw[6])
        }
        assertEquals("ASIAN", parsed.items[50].current.brand)
        assertEquals("BLACK-GREY", parsed.items[50].current.colour)
        assertEquals("N.BLUE GREY", parsed.items[0].current.colour)
    }

    @Test fun totalsMatchTheBill() {
        val h = parsed.header
        assertEquals(d("212"), h.printedQty); assertEquals(d("102237.72"), h.printedSubtotal)
        assertEquals(d("2.5"), h.cgstPct); assertEquals(d("2555.93"), h.cgstAmt); assertEquals(d("2555.93"), h.sgstAmt)
        assertEquals(d("0.42"), h.roundOff); assertEquals(d("107350.00"), h.printedGrandTotal)
        val t = Totals.of(inv)
        assertEquals(d("212"), t.qty); assertEquals(d("102237.72"), t.taxable)
        assertEquals(d("107350.00"), t.grand); assertEquals(0, t.difference!!.signum())
        val w = Warnings.of(inv)
        assertTrue(w.joinToString("\n") { it.hindi }, w.none { it.level != com.shivtej.billscanner.core.model.Warning.Level.TIP })
    }

    @Test fun sittingPercentIsWhatTheShopReallyGets() {
        // SM 651: MRP 1049, rate 720, 9.82% → 649.30 + 5% GST = 681.77 → 35.01% of MRP.
        val sm = parsed.items[0].current
        assertEquals(d("35.01"), Calc.sittingPct(sm, parsed.header))
        assertEquals(d("38.10"), Calc.sittingPct(sm, parsed.header, withGst = false))
        // ASIAN SAFARI-15: MRP 279, rate 265.71, 40% → 40.00%.
        assertEquals(d("40.00"), Calc.sittingPct(parsed.items[50].current, parsed.header))
        val groups = Groups.of(inv).associateBy { it.key }
        assertEquals(setOf("SM", "SFG", "SS", "SL", "SFL", "WLR", "FL", "ASIAN"), groups.keys)
        assertEquals(d("40.00"), groups.getValue("ASIAN").sittingPct)
        assertEquals(d("6"), groups.getValue("ASIAN").pairs)
    }

    @Test fun newBillAtTargetPerCompany() {
        var e = Edits.setAllTargets(inv, d("35"), 1)
        e = Edits.setGroupTarget(e, "ASIAN", d("40"), 2)
        val s = Target.sheet(e)
        assertEquals(51, s.lines.size)
        val sm = s.lines[0]
        assertEquals(d("681.85"), sm.landed)          // 1049 × 65%
        assertEquals(d("649.38"), sm.net)             // ÷ 1.05
        assertEquals(d("3896.28"), sm.amount)         // × 6
        val asian = s.lines[50]
        assertEquals(d("40"), asian.pct); assertEquals(d("167.40"), asian.landed)
        // Close to the supplier's own bill, since it already sits at ~35% / 40%.
        assertTrue("diff ${s.difference}", s.difference.abs() < d("300"))
        assertEquals(d("107350.00"), s.billTotal)
    }

    @Test fun unreadableDiscIsNotInvented() {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(blankDiscOf = setOf(7)), DharashivInvoice.page2()))
        val it7 = p.items[6]
        assertNull(it7.current.discPct)
        assertTrue(F.DISC_PCT in it7.needs)
        assertEquals(d("649.30"), it7.current.taxable)
    }

    @Test fun misplacedHeadingsAreFixedByTheBillsOwnArithmetic() {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(shiftedHeading = true)))
        assertEquals(50, p.items.size)
        for ((i, l) in DharashivInvoice.LINES.withIndex()) {
            val v = p.items[i].current
            val msg = "line ${l.sr}: $v cols=${p.columns}"
            assertEquals(msg, d(l.mrp), v.mrp); assertEquals(msg, BigDecimal(l.qty), v.qty); assertEquals(msg, d(l.rate), v.rate)
            assertEquals(msg, d(l.disc), v.discPct); assertEquals(msg, d(l.amount), v.taxable)
        }
    }

    @Test fun flatPhotoToo() {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(bend = 0.0), DharashivInvoice.page2(bend = 0.0)))
        assertEquals(51, p.items.size)
        assertTrue(p.items.all { it.needs.isEmpty() })
    }
}

class DharashivExportTest {
    @Test fun excelHasThePrintedBillTheGreenColumnAndTheNewBill() {
        val p = InvoiceParser.parse(listOf(DharashivInvoice.page1(), DharashivInvoice.page2()))
        var inv = Invoice("dh", 0, p.header, p.header, p.items, columns = p.columns)
        inv = Edits.setAllTargets(inv, BigDecimal("35"), 1)
        inv = Edits.setGroupTarget(inv, "ASIAN", BigDecimal("40"), 2)
        val bos = java.io.ByteArrayOutputStream()
        com.shivtej.billscanner.core.export.InvoiceExport.xlsx(inv, bos)
        System.getProperty("xlsx.dir")?.let { java.io.File(it, "dharashiv.xlsx").writeBytes(bos.toByteArray()) }
        val zip = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bos.toByteArray()))
        val parts = HashMap<String, String>()
        while (true) { val e = zip.nextEntry ?: break; parts[e.name] = zip.readBytes().toString(Charsets.UTF_8) }
        val s1 = parts.getValue("xl/worksheets/sheet1.xml")
        assertTrue(s1.contains("बैठा %")); assertTrue(s1.contains("SM 651 N.BLUE GREY")); assertTrue(s1.contains("6 pair"))
        assertTrue(s1.contains("<v>35.01</v>"))      // बैठा % of SM 651
        assertTrue(parts.getValue("xl/workbook.xml").contains("Company-wise"))
    }
}

class LearningTest {
    @Test fun aTaughtColumnIsUsedNextTime() {
        val pages = listOf(DharashivInvoice.page1(), DharashivInvoice.page2())
        val first = InvoiceParser.parse(pages)
        val key = com.shivtej.billscanner.core.ocr.TaughtLayouts.key(first.header)!!
        assertEquals("27AAFCD3805J1ZO", key)
        // The person says the "Disc." column of this supplier is not a % (just for the test).
        val taught = com.shivtej.billscanner.core.ocr.TaughtLayouts(mapOf(key to mapOf("disc" to InvoiceParser.Kind.OTHER)))
        val again = InvoiceParser.parse(pages, taught)
        assertNull(again.items[0].current.discPct)
        assertEquals(null, again.columns[8].field)
        // Saved OCR reads back the same.
        val back = com.shivtej.billscanner.core.store.OcrCodec.decode(com.shivtej.billscanner.core.store.OcrCodec.encode(pages[0]))
        assertEquals(pages[0], back)
    }
}
