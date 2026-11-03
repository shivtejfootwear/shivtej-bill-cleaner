package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Warning
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.BillPattern
import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.Heal
import com.shivtej.billscanner.core.ocr.KachaBill
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Patel Trading kachha bill 4672 (02-11-2026), written as the shop writes it: MRP first, "—v—" for "same as above". */
class KachaBillTest {
    private val rows = listOf(
        listOf("359 - SWISS-116", "BK", "7X10", "08", "226"),
        listOf("739-INDUS-OXYGEN-04", "White", "7X10", "08", "443"),
        listOf("409 —v— QUBE-01", "BR", "8X10", "06", "258"),
        listOf("384 —v— SWISS-115", "BR", "7X10", "08", "242"),
        listOf("384 —v——", "BK", "7X10", "08", "242"),
        listOf("289 —v— SlIDER-432", "D.Gry/St/Gry", "8X10", "06", "182"),
        listOf("289 —v—— -461", "Firozi/St/Gry", "7X10", "06", "182"),
        listOf("289 —v—— -416", "D.Gry/St/Gry", "8X10", "06", "182"),
        listOf("289 —v—— -432", "D.Gry/St/Gry", "8X10", "06", "182"),
        listOf("274 —v— 6227", "BK", "6X8", "06", "173"),
        listOf("274 —v— TIYA-58", "FIG", "6x8", "06", "173"),
        listOf("274 —1— 6227", "CPR", "6X8", "06", "173"),
        listOf("274 —1— 6227", "Grey", "6X8", "O6", "173"),
        listOf("404 —v— CLS-04", "Grey", "7X10", "08", "255"),
        listOf("404 —v— CLS-28", "BR", "8X10", "06", "255"),
        listOf("384 —v—— 09", "Tan", "8X10", "06", "242"),
        listOf("384 —1—— 10", "BR", "8X10", "06", "242"),
        listOf("384 —v— 10", "BK", "9X10", "06", "242"),
        listOf("359 —v— HIKE-05", "TAN", "8X10", "06", "226"),
    )
    private val want = listOf(
        "359|SWISS-116|BK|7X10|8|226", "739|INDUS OXYGEN-04|WHITE|7X10|8|443", "409|QUBE-01|BR|8X10|6|258", "384|SWISS-115|BR|7X10|8|242",
        "384|SWISS-115|BK|7X10|8|242", "289|SLIDER-432|D.GRY/ST/GRY|8X10|6|182", "289|SLIDER-461|FIROZI/ST/GRY|7X10|6|182",
        "289|SLIDER-416|D.GRY/ST/GRY|8X10|6|182", "289|SLIDER-432|D.GRY/ST/GRY|8X10|6|182", "274|6227|BK|6X8|6|173", "274|TIYA-58|FIG|6X8|6|173",
        "274|6227|CPR|6X8|6|173", "274|6227|GREY|6X8|6|173", "404|CLS-04|GREY|7X10|8|255", "404|CLS-28|BR|8X10|6|255", "384|CLS-09|TAN|8X10|6|242",
        "384|CLS-10|BR|8X10|6|242", "384|CLS-10|BK|9X10|6|242", "359|HIKE-05|TAN|8X10|6|226",
    )

    private fun page(total: String = "124"): OcrPage {
        val ws = mutableListOf<OcrWord>()
        fun w(t: String, x: Int, y: Int, cw: Int = 21) { ws += OcrWord(t, Box(x, y, x + cw * t.length, y + 40)) }
        w("SINCE", 560, 360); w("PATEL", 520, 400); w("TRADING", 530, 480); w("CORPORATION", 700, 480); w("No.", 1080, 420); w("4672", 1200, 380)
        w("Date", 1075, 515); w("2/11/2026", 1180, 520); w("M/s.", 10, 600)
        w("Article", 250, 760); w("Colour", 590, 760); w("Size", 745, 760); w("Pair", 868, 760); w("Rate", 1000, 760); w("Amount", 1178, 760)
        for ((i, r) in rows.withIndex()) {
            // Handwriting does not keep to one baseline.
            val y = 815 + i * 47 + listOf(0, 6, -5, 3, -2)[i % 5]
            var x = 20
            for (t in r[0].split(' ')) { w(t, x, y + (x % 7)); x += 21 * t.length + 18 }
            w(r[1], 600, y - 4, 11); w(r[2], 735, y + 3); w(r[3], 860, y); w(r[4], 985, y - 6)
        }
        w(total, 880, 1790)
        w("Received", 440, 1900); w("By", 610, 1900); w("Packed", 815, 1880); w("Checked", 1170, 1860)
        return OcrPage(1660, 2200, ws)
    }

    private fun check(pages: List<OcrPage>) = KachaBill.parse(pages)!!.also { p ->
        val arts = p.items.take(19)
        assertEquals(want.joinToString("\n"), arts.joinToString("\n") { i -> i.current.let { v -> "${v.mrp?.toPlainString()}|${v.article}|${v.colour}|${v.sizeText}|${v.qty?.toPlainString()}|${v.rate?.toPlainString()}" } })
        assertTrue(arts.all { Heal.sure(it) })
        assertEquals("PATEL TRADING CORPORATION", p.header.supplierName)
        assertEquals("02-11-2026", p.header.invoiceDate)
    }

    @Test fun readsTheShopsWayOfWriting() {
        val p = check(listOf(page()))
        assertEquals("4672", p.header.invoiceNo)
        assertEquals(0, p.header.printedQty!!.compareTo(BigDecimal(124)))
        val inv = Invoice("k", 0, p.header, p.header, p.items, columns = p.columns)
        val bad = Warnings.of(inv).filter { it.level != Warning.Level.TIP }
        assertTrue(bad.joinToString("\n") { it.hindi }, bad.isEmpty())
        // Through the app's own reading path too.
        assertEquals(19, BillPattern.parse(listOf(page()), emptyList()).items.size)
    }

    @Test fun aPhotoTakenSideways() {
        val p0 = page()
        // The same page photographed turned 90° (text running bottom to top).
        val side = OcrPage(p0.height, p0.width, p0.words.map { w -> OcrWord(w.text, Box(w.box.t, p0.width - w.box.r, w.box.b, p0.width - w.box.l)) })
        check(listOf(side))
    }

    @Test fun theRunningTotalShowsAWrongPairCount() {
        // Second bill of the day: 250 written at the bottom after 124 → 126 pairs, but its lines have 124.
        val p = check(listOf(page(), page("250")))
        val inv = Invoice("k", 0, p.header, p.header, p.items, columns = p.columns)
        val ws = Warnings.of(inv).filter { it.level == Warning.Level.CHECK }
        assertTrue(ws.joinToString { it.hindi }, ws.any { it.hindi.contains("126") && it.hindi.contains("124") })
    }

    @Test fun aTaxInvoiceIsNotAKachhaBill() {
        val ws = page().words + OcrWord("GSTIN:", Box(100, 300, 200, 330))
        assertNull(KachaBill.parse(listOf(OcrPage(1660, 2200, ws))))
        assertNotNull(KachaBill.closest("SWlSS-ll6", KachaBill.ARTICLES))
    }
}
