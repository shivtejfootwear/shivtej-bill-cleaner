package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Real photos are never perfect: extra tilt, bends, boxes a few pixels off, and words the OCR glues together.
 * The reader may miss a value then — but it must flag it, never put a wrong number in silently.
 */
class RobustnessTest {
    private fun shake(p: OcrPage, seed: Long, px: Int, rot: Double, joins: Int): OcrPage {
        val r = java.util.Random(seed)
        val moved = p.words.map { w ->
            val x = w.box.cx - rot * w.box.cy; val y = w.box.cy + rot * w.box.cx
            val hw = w.box.w / 2.0; val hh = w.box.h / 2.0 + Math.abs(rot) * w.box.w / 2
            val dx = r.nextInt(2 * px + 1) - px; val dy = r.nextInt(2 * px + 1) - px
            OcrWord(w.text, Box((x - hw).toInt() + dx, (y - hh).toInt() + dy, (x + hw).toInt() + dx, (y + hh).toInt() + dy))
        }.sortedWith(compareBy({ (it.box.cy / 15).toInt() }, { it.box.l }))
        val out = mutableListOf<OcrWord>()
        var i = 0
        while (i < moved.size) {
            val w = moved[i]; val n = moved.getOrNull(i + 1)
            if (r.nextInt(100) < joins && n != null && n.box.l - w.box.r in 0..15 && Math.abs(n.box.cy - w.box.cy) < 6) {
                out += OcrWord(w.text + n.text, Box(w.box.l, minOf(w.box.t, n.box.t), n.box.r, maxOf(w.box.b, n.box.b))); i += 2; continue
            }
            out += w; i++
        }
        return OcrPage(p.width, p.height, out.shuffled(r))
    }

    private fun d(s: String) = BigDecimal(s.replace(",", ""))

    @Test fun dharashivUnderBadPhotos() {
        val all = DharashivInvoice.LINES + DharashivInvoice.ASIAN
        for ((seed, rot) in listOf(1L to 0.03, 2L to -0.05, 3L to 0.0, 4L to 0.06, 5L to -0.02)) {
            val p = InvoiceParser.parse(listOf(
                shake(DharashivInvoice.page1(bend = 0.00002), seed, 4, rot, 4), shake(DharashivInvoice.page2(bend = 0.00002), seed + 50, 4, rot, 4),
            ))
            var ok = 0
            for (l in all) {
                val it = p.items.firstOrNull { x -> x.current.sr == l.sr } ?: continue
                val v = it.current
                val good = v.mrp?.compareTo(d(l.mrp)) == 0 && v.qty?.toInt() == l.qty && v.rate?.compareTo(d(l.rate)) == 0 &&
                    v.discPct?.compareTo(d(l.disc)) == 0 && v.taxable?.compareTo(d(l.amount)) == 0
                if (good) ok++ else assertTrue("seed $seed line ${l.sr} wrong but not flagged: ${it.raw}", it.needs.isNotEmpty())
            }
            assertTrue("seed $seed: only $ok/51 lines read", ok >= 49)
            assertEquals(d("107350.00"), p.header.printedGrandTotal)
        }
    }

    @Test fun patelUnderBadPhotos() {
        for ((seed, rot) in listOf(1L to 0.04, 2L to -0.04, 3L to -0.08)) {
            val p = InvoiceParser.parse(listOf(shake(PatelInvoice.page(), seed, 3, rot, 0)))
            assertEquals(11, p.items.size)
            for ((i, l) in PatelInvoice.LINES.withIndex()) {
                val it = p.items[i]; val v = it.current
                val good = v.taxable?.toPlainString() == l.taxable && v.mrp?.toPlainString() == l.mrp && v.discPct?.toPlainString() == l.dp
                assertTrue("seed $seed line ${l.sr}: $v", good || it.needs.isNotEmpty())
            }
        }
    }
}
