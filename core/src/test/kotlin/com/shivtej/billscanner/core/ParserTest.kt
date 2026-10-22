package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.Sizes
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.ocr.InvoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ParserTest {
    private fun d(s: String) = BigDecimal(s)
    private val parsed = InvoiceParser.parse(listOf(PatelInvoice.page()))

    @Test fun headerIsRead() {
        val h = parsed.header
        assertEquals("PATEL TRADELINES", h.supplierName)
        assertEquals("27AHTPP0412H1Z9", h.supplierGstin)
        assertEquals("27ECIPS0222J1ZI", h.buyerGstin)
        assertEquals("G2/2627/1309", h.invoiceNo)
        assertEquals("20-Oct-2026", h.invoiceDate)
        assertEquals("PTHO/2627/6772", h.orderNo)
        assertEquals("SELF", h.transporter)
        assertEquals("0.00C - 1 Bundle(0.00)", h.cartons)
        assertEquals("(134)", h.packing)
        assertEquals("02184796594", h.supplierContact)
        assertEquals("SHIVTEJ FOOTWEAR AKLUJ", h.buyerName)
        assertEquals("GAT NO 735/3/1, 735/3/2, Kurduwadi Road, Dhage Mala, Barshi, Solapur, Maharashtra, 413401-413411 Contact No.:02184796594", h.supplierAddress)
        assertEquals(d("26012.00"), h.printedGrandTotal)
        assertEquals("Page 1 of 2", h.pageNote)
    }

    @Test fun everyLineAndEveryNumberIsRead() {
        assertEquals(parsed.items.joinToString("\n") { it.current.toString() }, 11, parsed.items.size)
        for ((i, l) in PatelInvoice.LINES.withIndex()) {
            val v = parsed.items[i].current
            val msg = "line ${l.sr}: $v"
            assertEquals(msg, l.sr, v.sr)
            assertEquals(msg, l.code.substringBefore('-'), v.article)
            assertEquals(msg, l.hsn, v.hsn)
            assertEquals(msg, d(l.mrp), v.mrp)
            assertEquals(msg, d(l.qty), v.qty)
            assertEquals(msg, d(l.rate), v.rate)
            assertEquals(msg, d(l.total), v.gross)
            assertEquals(msg, d(l.dp), v.discPct)
            assertEquals(msg, d(l.da), v.discAmt)
            assertEquals(msg, d(l.taxable), v.taxable)
            assertEquals(msg, d(l.cp), v.cgstPct); assertEquals(msg, d(l.ca), v.cgstAmt)
            assertEquals(msg, d(l.sp), v.sgstPct); assertEquals(msg, d(l.sa), v.sgstAmt)
        }
    }

    @Test fun textPartsAreKept() {
        val it1 = parsed.items[0].current
        assertEquals("Paralite Gents EVA 6X10", it1.name); assertEquals("Paralite", it1.brand)
        assertEquals("BLK", it1.colour); assertEquals("BLK 6X8/1", it1.sizeText)
        val it4 = parsed.items[3].current
        assertEquals("Black / Grey", it4.colour)
        assertEquals("Black 6/2,7/3,8/3,9/2,10/2 | Grey 7/3,8/3,9/3,10/3", it4.sizeText)
        assertEquals("RedBlack", parsed.items[5].current.colour)
        assertEquals(ItemKind.CHARGE, parsed.items[9].kind)              // FREIGHT
        assertTrue(parsed.items[10].needs.isEmpty() && parsed.items[10].current.colour.isEmpty())   // S11500 has no colour printed: a tip, not a doubt
        assertTrue(parsed.items.take(10).all { it.needs.isEmpty() || it.kind == ItemKind.CHARGE })
    }

    @Test fun unreadableDiscountIsNeedsVerificationNotInvented() {
        val p = InvoiceParser.parse(listOf(PatelInvoice.page(blankDiscountOf = setOf(5))))
        val it5 = p.items[4]
        assertNull(it5.current.discPct)
        assertTrue(F.DISC_PCT in it5.needs)
        assertEquals(d("70.56"), it5.current.discAmt)   // the amount is still read as printed
    }

    @Test fun discountMaths() {
        val v = parsed.items[0].current      // 1180: MRP 235, rate 170.12, bill 3%
        assertEquals(d("3.00"), v.discPct)                       // Original Bill Discount (%)
        assertEquals(d("27.61"), Calc.mrpDiscPct(v))            // ((235-170.12)/235)*100
        assertEquals(d("165.02"), Calc.netRate(v))              // 170.12 x 0.97
        assertEquals(d("29.78"), Calc.effectiveMrpPct(v))
        assertEquals(d("3118.82"), Calc.lineTotal(v))           // 2970.30 + 74.26 + 74.26
        assertEquals(d("164.50"), Calc.targetRate(v.mrp, d("30")))
        // Editing the bill % recalculates the line; the printed original stays.
        val edited = Calc.recalc(v.copy(discPct = d("5")))
        assertEquals(d("153.11"), edited.discAmt); assertEquals(d("2909.05"), edited.taxable)
        assertEquals(d("3.00"), parsed.items[0].original.discPct)
    }

    @Test fun supplierRoundingIsNotAnError() {
        // Line 4: 2728.18 x 2.5% = 68.2045, bill says 68.22 (2 paise) — within tolerance.
        assertTrue(Calc.checkLine(parsed.items[3].current).isEmpty())
        // A real mistake is caught.
        val bad = parsed.items[0].current.copy(gross = d("3100.00"))
        assertTrue(Calc.checkLine(bad).any { it.field == F.GROSS })
    }

    @Test fun totalsAndReconciliation() {
        val inv = Invoice("x", 0, parsed.header, parsed.header, parsed.items)
        val t = Totals.of(inv)
        assertEquals(11, t.lines); assertEquals(10, t.articleLines)
        assertEquals(d("134"), t.qty)                    // matches "Packing/Cartoon : (134)"
        assertEquals(d("25526.32"), t.gross)
        assertEquals(d("765.79"), t.discount)
        assertEquals(d("24760.53"), t.taxable)
        assertEquals(d("619.02"), t.cgst); assertEquals(d("619.02"), t.sgst)
        assertEquals(d("25998.57"), t.grand)
        assertEquals(d("13.43"), t.difference)           // printed 26012.00 — shown as a warning
    }

    @Test fun sizeAssortments() {
        val r1 = Sizes.read("BLK 6X8/1", d("18"))
        assertEquals(Sizes.State.FROM_SETS, r1.state); assertEquals(listOf(6, 6, 6), r1.rows.map { it.qty })
        assertEquals(Sizes.State.EXACT, Sizes.read("Black 10/24", d("24")).state)
        val r4 = Sizes.read("Black 6/2,7/3,8/3,9/2,10/2 | Grey 7/3,8/3,9/3,10/3", d("24"))
        assertEquals(Sizes.State.EXACT, r4.state); assertEquals(24, r4.total)
        assertEquals(listOf("Black", "Grey"), r4.rows.map { it.colour }.distinct())
        // 18 pairs do not split evenly over sizes 6-10: asked, not guessed.
        assertEquals(Sizes.State.UNCLEAR, Sizes.read("BLK 6X10/1", d("18")).state)
        assertEquals(Sizes.State.MISMATCH, Sizes.read("Tan 6/2,7/3", d("12")).state)
        assertEquals(Sizes.State.NONE, Sizes.read("", d("1")).state)
    }
}
