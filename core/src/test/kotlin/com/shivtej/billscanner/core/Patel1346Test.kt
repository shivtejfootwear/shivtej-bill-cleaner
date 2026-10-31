package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.ocr.BillPattern
import com.shivtej.billscanner.core.ocr.Heal
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.KnownPatterns
import com.shivtej.billscanner.core.ocr.ParsedInvoice
import com.shivtej.billscanner.core.store.OcrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Patel Tradelines (Paragon) G2/2627/1346 ("Page 1 of 1", ₹34,506), sent from the owner's phone together with
 * page 2 of ANOTHER bill ("Page 2 of 2", ₹26,012, "Golden 5/1" at its top). Real ML Kit OCR from the phone.
 */
class Patel1346Test {
    private fun page(n: String) = OcrCodec.decode(javaClass.classLoader!!.getResource("real/$n")!!.readText())
    private val both get() = listOf(page("patel_1346_1.tsv"), page("patel_1346_2.tsv"))

    private fun check(p: ParsedInvoice) {
        assertEquals("G2/2627/1346", p.header.invoiceNo)
        val arts = p.items.filter { it.kind == ItemKind.ARTICLE }
        assertEquals(p.items.joinToString("\n") { "${it.current.sr} ${it.current.article} ${it.needs}" }, 8, arts.size)
        for (it in arts) assertTrue("${it.current.sr}: ${it.needs} ${it.current}", Heal.sure(it) && it.current.mrp != null)
        val l9 = arts.first { it.current.sr == 9 }.current
        assertFalse(l9.sizeText, l9.sizeText.contains("Golden"))
        assertEquals(0, l9.mrp!!.compareTo(BigDecimal("999.00")))
        val l6 = arts.first { it.current.sr == 6 }.current
        assertTrue(l6.sizeText, l6.sizeText.replace(" ", "").contains("7/1"))
        assertFalse(l6.hsn, l6.hsn.contains("71"))
        val l5 = arts.first { it.current.sr == 5 }.current
        assertEquals(0, l5.mrp!!.compareTo(BigDecimal("619.00"))); assertEquals(0, l5.taxable!!.compareTo(BigDecimal("2230.05")))
    }

    @Test fun pageOfAnotherBillIsLeftOut() {
        val p = InvoiceParser.parse(both)
        check(p)
        assertNotNull(p.header.printedGrandTotal)
        assertEquals(0, p.header.printedGrandTotal!!.compareTo(BigDecimal("34506.00")))
        assertTrue(p.notes.toString(), p.notes.any { it.contains("दूसरे बिल") })
    }

    @Test fun firstPageAlone() = check(InvoiceParser.parse(listOf(page("patel_1346_1.tsv"))))

    @Test fun withTheBuiltInCode() {
        assertTrue(KnownPatterns.ALL.any { it.name.startsWith("Patel Tradelines") })
        val p = BillPattern.parse(both, KnownPatterns.ALL)
        check(p)
        assertEquals(0, p.header.printedGrandTotal!!.compareTo(BigDecimal("34506.00")))
    }
}

class Patel1346WarningsTest {
    private fun page(n: String) = com.shivtej.billscanner.core.store.OcrCodec.decode(javaClass.classLoader!!.getResource("real/$n")!!.readText())

    @Test fun onlyTheSuppliersOwnTotalGapIsAsked() {
        val p = InvoiceParser.parse(listOf(page("patel_1346_1.tsv"), page("patel_1346_2.tsv")))
        val inv = com.shivtej.billscanner.core.model.Invoice("p", 0, p.header, p.header, p.items, columns = p.columns)
        val ws = com.shivtej.billscanner.core.model.Warnings.of(inv).filter { it.level != com.shivtej.billscanner.core.model.Warning.Level.TIP }
        val all = ws.joinToString("\n") { it.hindi }
        assertTrue(all, ws.none { it.hindi.contains("साइज़") })
        assertTrue(all, ws.any { it.hindi.contains("12.78") && it.hindi.contains("सप्लायर") })
    }
}
