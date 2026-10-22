package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.Learning
import com.shivtej.billscanner.core.ocr.Learnt
import com.shivtej.billscanner.core.store.OcrCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A line the person fixes once is read right on the next bill of the same supplier. */
class LearningFromFixesTest {
    private fun page() = OcrCodec.decode(javaClass.classLoader!!.getResource("real/v3617_1.tsv")!!.readText())

    private fun spoil(pg: com.shivtej.billscanner.core.ocr.OcrPage, rowWord: String, from: String, to: String): com.shivtej.billscanner.core.ocr.OcrPage {
        val y = pg.words.first { it.text == rowWord }.box.cy
        return pg.copy(words = pg.words.map { if (it.text == from && kotlin.math.abs(it.box.cy - y) < 12) it.copy(text = to) else it })
    }

    @Test fun aFixTeachesTheNextBill() {
        // The scan reads the MRP of line 2 (WLR 74002 ESP) as "2B9" — a letter for a digit: no MRP, so no बैठा %.
        val spoiled = spoil(page(), "74002", "289", "2B9")
        val first = InvoiceParser.parse(listOf(spoiled))
        val inv = Invoice("a", 0, first.header, first.header, first.items, columns = first.columns)
        val l2 = inv.items.first { it.current.sr == 2 }
        assertTrue(l2.needs.toString(), com.shivtej.billscanner.core.model.F.MRP in l2.needs)
        // The person types the right MRP once.
        val (learnt, lessons) = Learning.fromEdit(Learnt(), inv, l2, l2.current.copy(mrp = BigDecimal(289)))
        assertTrue(lessons.toString(), lessons.any { it.contains("2B9") } && lessons.any { it.contains("\"B\" = 8") })
        // Saved and read back; the next bill with the same misreading is fixed by itself.
        val again = Learnt.decode(learnt.encode())
        assertEquals(learnt, again)
        val (next, fixed) = Learning.apply(again, InvoiceParser.parse(listOf(spoiled)))
        val n2 = next.items.first { it.current.sr == 2 }
        assertEquals(0, n2.current.mrp!!.compareTo(BigDecimal(289)))
        assertTrue(n2.needs.toString(), n2.needs.isEmpty())
        assertTrue(fixed >= 1)
        // A different number with the same letter ("1B6.67" for the rate 186.67 of line 12) is fixed from the learnt letter.
        val other = spoil(page(), "7825", "186.67", "1B6.67")
        val before = InvoiceParser.parse(listOf(other)).items.first { it.current.sr == 12 }
        assertTrue(before.needs.isNotEmpty())
        val n12 = Learning.apply(again, InvoiceParser.parse(listOf(other))).first.items.first { it.current.sr == 12 }
        assertEquals(0, n12.current.rate!!.compareTo(BigDecimal("186.67")))
        assertTrue(n12.needs.toString(), n12.needs.isEmpty())
    }

    @Test fun aDeletedJunkLineIsSkippedNextTime() {
        val p = InvoiceParser.parse(listOf(page()))
        val inv = Invoice("a", 0, p.header, p.header, p.items, columns = p.columns)
        val doubtful = inv.items.first { it.needs.isNotEmpty() }
        val l = Learning.fromDelete(Learnt(), inv, doubtful)
        assertEquals(1, l.junk.values.sumOf { it.size })
        // A line that added up never becomes junk.
        val sure = inv.items.first { it.needs.isEmpty() }
        assertEquals(l, Learning.fromDelete(l, inv, sure))
    }
}
