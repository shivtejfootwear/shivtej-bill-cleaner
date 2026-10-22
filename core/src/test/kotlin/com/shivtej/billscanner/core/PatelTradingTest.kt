package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Target
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.Heal
import com.shivtej.billscanner.core.ocr.InvoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** A different billing software: rate with GST beside a net rate, amounts with GST, MRP 0.00 on non-brand goods. */
class PatelTradingTest {
    private fun bd(s: String) = BigDecimal(s.replace(",", ""))
    private fun parse() = InvoiceParser.parse(listOf(PatelTradingInvoice.page1(), PatelTradingInvoice.page2()))

    @Test fun everyLineIsReadAndAddsUp() {
        val p = parse()
        val all = PatelTradingInvoice.LINES + PatelTradingInvoice.PAGE2
        assertEquals(all.map { it.sr }, p.items.map { it.current.sr })
        for ((i, l) in all.withIndex()) {
            val it = p.items[i]; val v = it.current
            val msg = "line ${l.sr}: $v ${it.needs}"
            assertTrue(msg, Heal.sure(it))
            assertEquals(msg, l.qty, v.qty!!.toInt())
            assertEquals(msg, 0, v.rateGst!!.compareTo(bd(l.rate)))      // rate with GST
            assertEquals(msg, 0, v.rate!!.compareTo(bd(l.net)))          // net rate
            assertEquals(msg, 0, v.printedTotal!!.compareTo(bd(l.amount)))
            if (bd(l.mrp).signum() == 0) assertTrue(msg, v.mrp == null) else assertEquals(msg, 0, v.mrp!!.compareTo(bd(l.mrp)))
        }
        val h = p.header
        assertEquals("PATEL TRADING CORPORATION", h.supplierName)
        assertEquals("26-27/ALT-496", h.invoiceNo)
        assertEquals(0, h.printedGrandTotal!!.compareTo(bd("44676.00")))
        assertEquals(0, h.cgstAmt!!.compareTo(bd("1063.70"))); assertEquals(0, h.sgstAmt!!.compareTo(bd("1063.70")))
        assertEquals(0, h.printedQty!!.compareTo(BigDecimal(270)))
        val inv = Invoice("pt", 0, h, h, p.items, columns = p.columns)
        assertTrue(Totals.of(inv).difference!!.abs() <= BigDecimal("0.99"))
        // Nothing to ask: no "MRP not found" on goods billed by rate only, no "continued" once page 2 is there.
        assertTrue(Warnings.of(inv).filter { it.level != com.shivtej.billscanner.core.model.Warning.Level.TIP }.toString(),
            Warnings.of(inv).none { it.level != com.shivtej.billscanner.core.model.Warning.Level.TIP })
    }

    @Test fun baithaIsAgainstTheRateWithGstAndNewAmountsIncludeGstLikeTheBill() {
        val p = parse()
        var inv = Invoice("pt", 0, p.header, p.header, p.items, columns = p.columns)
        val l29 = inv.items.first { it.current.sr == 29 }       // MRP 444, rate with GST 280
        assertEquals(0, Calc.sittingPct(l29.current, inv.header)!!.compareTo(bd("36.94")))
        assertTrue(Target.inclusive(inv))
        inv = Edits.setAllTargets(inv, BigDecimal(40), 1)
        val line = Target.line(inv, inv.items.first { it.current.sr == 29 })!!
        assertEquals(0, line.amountWithGst.compareTo(bd("1598.40")))        // 444 × 60% = 266.40 × 6
        // Lines without MRP keep their bill amount (rate only).
        assertNull(Target.line(inv, inv.items.first { it.current.sr == 1 }))
        val bos = java.io.ByteArrayOutputStream(); com.shivtej.billscanner.core.export.InvoiceExport.xlsx(inv, bos)
        System.getProperty("xlsx.dir")?.let { java.io.File(it, "patel_trading.xlsx").writeBytes(bos.toByteArray()) }
    }
}
