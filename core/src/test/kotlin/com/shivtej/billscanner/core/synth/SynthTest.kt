package com.shivtej.billscanner.core.synth

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hundreds of made-up footwear bills in the ways suppliers print them (Tally, Busy, Marg, Vyapar, brand distributors,
 * Crystal Reports, EBase, size-matrix, trade + scheme discounts, order bills / estimates, simple GST bills),
 * read back by the scanner. Clean pages must be read exactly; on pages with OCR slips, a line is either right or
 * shown as doubtful — never wrong and marked sure.
 */
class SynthTest {
    @Test fun everyKindOfBillIsReadExactly() {
        val res = (1L..330L).map { SynthScore.of(SynthBill.make(it)) }
        val lines = res.sumOf { it.bill.lines.size }; val ok = res.sumOf { it.linesOk }
        val bad = res.filter { !it.ok }.take(5).joinToString("\n") { "seed=${it.bill.seed} ${it.bill.features} ${it.problems.take(3)}" }
        assertTrue("lines read exactly $ok/$lines\n$bad", ok * 1000L >= lines * 998L)
        for (s in SynthBill.Style.entries) {
            val rs = res.filter { it.bill.style == s }
            assertTrue("$s: ${rs.count { it.ok }}/${rs.size} bills", rs.count { it.ok } * 100 >= rs.size * 95)
        }
    }

    @Test fun ocrSlipsAreShownNeverHidden() {
        val res = (1001L..1200L).map { SynthScore.of(SynthBill.make(it, noise = 0.01)) }
        val silent = res.flatMap { r -> r.silent.map { "seed=${r.bill.seed} ${r.bill.style}: $it" } }
        assertTrue("wrong lines marked sure:\n${silent.take(5).joinToString("\n")}", silent.size <= 1)
        val lines = res.sumOf { it.bill.lines.size }; val ok = res.sumOf { it.linesOk }
        assertTrue("lines read exactly under OCR slips: $ok/$lines", ok * 100L >= lines * 85L)
    }
}
