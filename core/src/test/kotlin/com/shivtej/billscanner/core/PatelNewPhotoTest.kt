package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.ocr.BillPattern
import com.shivtej.billscanner.core.ocr.Heal
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.InvoiceParser.Kind
import com.shivtej.billscanner.core.ocr.LayoutMemory
import com.shivtej.billscanner.core.store.OcrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Patel Tradelines (Paragon) G2/2627/1309, a new phone photo of both pages (real ML Kit OCR from the emulator).
 * On the owner's phone every line came out doubtful: a column taught or a code pasted for this supplier must
 * never spoil a bill whose plain reading adds up.
 */
class PatelNewPhotoTest {
    private fun page(n: String) = OcrCodec.decode(javaClass.classLoader!!.getResource("real/$n")!!.readText())
    private val pages get() = listOf(page("patel_1309_new_1.tsv"), page("patel_1309_new_2.tsv"))

    private fun check(p: com.shivtej.billscanner.core.ocr.ParsedInvoice) {
        assertEquals("G2/2627/1309", p.header.invoiceNo)
        val arts = p.items.filter { it.kind == ItemKind.ARTICLE }
        assertEquals(10, arts.size)
        for (it in arts) assertTrue("${it.current.sr}: ${it.needs} ${it.current}", Heal.sure(it) && it.current.mrp != null)
        val l7 = arts.first { it.current.sr == 7 }.current
        assertEquals(0, l7.mrp!!.compareTo(BigDecimal("605.00"))); assertEquals(0, l7.rate!!.compareTo(BigDecimal("374.50")))
        assertEquals(0, l7.discPct!!.compareTo(BigDecimal(3))); assertEquals(0, l7.taxable!!.compareTo(BigDecimal("4359.18")))
    }

    @Test fun readsEveryLine() = check(InvoiceParser.parse(pages))

    @Test fun aWronglyTaughtColumnCannotSpoilIt() {
        val wrong = object : LayoutMemory {
            override fun kindFor(header: Header, label: String): Kind? = when (BillPattern.norm(label)) {
                "total" -> Kind.TAXABLE; "taxablevalue" -> Kind.TOTAL; "rate" -> Kind.MRP; "qtyinpairs" -> Kind.RATE; else -> null
            }
        }
        check(BillPattern.parse(pages, emptyList(), wrong))
    }

    /** Even read with wrong columns (no safety net), the bill's own arithmetic solves every line. */
    @Test fun lineSolverFixesLinesWhateverTheColumns() {
        val variants = listOf(
            mapOf("rate" to Kind.MRP, "total" to Kind.TAXABLE),
            mapOf("qtyinpairs" to Kind.RATE, "taxablevalue" to Kind.TOTAL),
            mapOf("discount%" to Kind.OTHER, "%" to Kind.OTHER, "discountamount" to Kind.OTHER))
        for (m in variants) {
            val mem = object : LayoutMemory { override fun kindFor(header: Header, label: String) = m[BillPattern.norm(label)] }
            val arts = InvoiceParser.parse(pages, mem).items.filter { it.kind == ItemKind.ARTICLE }
            assertEquals("$m", 10, arts.count { Heal.sure(it) && it.current.mrp != null })
            val l7 = arts.first { it.current.sr == 7 }.current
            assertEquals("$m", 0, l7.taxable!!.compareTo(BigDecimal("4359.18"))); assertEquals("$m", 0, l7.mrp!!.compareTo(BigDecimal("605.00")))
        }
    }

    /**
     * The phone's document scanner crops at the table's right border: the last digit of every SGST amount is lost.
     * MRP, pairs, rate, discount and amount still add up, so no line may be called "not understood".
     */
    @Test fun gstDigitsCutAtTheEdgeDoNotMakeLinesDoubtful() {
        val big = listOf(page("patel_1309_big_1.tsv"), page("patel_1309_big_2.tsv"))
        val maxR = big[0].words.maxOf { it.box.r }
        val cut = listOf(big[0].copy(words = big[0].words.map { w -> if (w.box.l > maxR - 90 && Regex("""^\d+\.\d\d$""").matches(w.text)) w.copy(text = w.text.dropLast(1)) else w }), big[1])
        val arts = BillPattern.parse(cut, emptyList()).items.filter { it.kind == ItemKind.ARTICLE }
        assertEquals(10, arts.count { Heal.sure(it) })
        // A GST amount that is really different still shows a wrong amount.
        val l = arts.first { it.current.sr == 7 }.current.copy(discPct = null, discAmt = null, gross = null, taxable = BigDecimal("4494.00"))
        assertTrue(!Heal.proven(l))
    }

    @Test fun aWrongPastedCodeCannotSpoilIt() {
        val bad = BillPattern("Patel Tradelines", names = listOf("PATELTRADELINES"),
            headings = mapOf("total" to Kind.TAXABLE, "rate" to Kind.MRP), order = listOf(Kind.SR, Kind.DESC, Kind.QTY, Kind.MRP))
        check(BillPattern.parse(pages, listOf(bad)))
    }
}
