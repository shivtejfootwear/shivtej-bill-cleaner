package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Column
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The bill checks itself: what most lines of one bill share (the same Disc. %, the same GST % for the same HSN)
 * is tried on a line the photo spoiled, and kept only when that line's own printed numbers then add up
 * (pairs × rate − discount = amount). Nothing is filled in that the line's arithmetic does not prove.
 */
object Heal {
    class Result(val items: List<LineItem>, val fixed: Int)

    private val HUNDRED = BigDecimal(100)
    private val ARITH = setOf(F.QTY, F.RATE, F.DISC_PCT, F.TAXABLE, F.GROSS, F.DISC_AMT)

    /** The line's numbers are proven by its own arithmetic. */
    fun proven(v: ItemValues): Boolean =
        v.qty != null && v.rate != null && v.taxable != null && Calc.checkLine(v).isEmpty() &&
            (v.discPct != null || v.discAmt != null || v.gross != null || (v.qty * v.rate - v.taxable).abs() <= BigDecimal("0.06"))

    /** Most common value and how many lines have it. */
    private fun mode(xs: List<BigDecimal>): Pair<BigDecimal, Int>? =
        xs.groupBy { it.stripTrailingZeros() }.maxByOrNull { it.value.size }?.let { it.key to it.value.size }

    fun byColumns(items: List<LineItem>, columns: List<Column>): Result {
        val arts = items.filter { it.kind == ItemKind.ARTICLE }
        if (arts.size < 5) return Result(items, 0)
        val dc = columns.indexOfFirst { it.field == F.DISC_PCT }
        val gc = columns.indexOfFirst { it.field == F.GST_PCT }
        val qc = columns.indexOfFirst { it.field == F.QTY }
        val good = arts.filter { proven(it.current) && it.needs.none { f -> f in ARITH } }
        val disc = mode(good.mapNotNull { it.current.discPct })?.takeIf { (_, n) -> n * 10 >= good.size * 7 && good.size >= 4 }?.first
        val gstByHsn = arts.filter { it.current.gstPct != null && it.current.hsn.isNotEmpty() }.groupBy { it.current.hsn }
            .mapValues { (_, l) -> mode(l.map { it.current.gstPct!! })?.takeIf { (_, n) -> n * 10 >= l.size * 8 }?.first }
        val gstAll = mode(arts.mapNotNull { it.current.gstPct })?.takeIf { (_, n) -> n * 10 >= arts.size * 8 }?.first
        var fixed = 0
        val out = items.map { item ->
            if (item.kind != ItemKind.ARTICLE) return@map item
            var v = item.current
            val raw = item.raw.toMutableList()
            var changed = false
            // GST % of this HSN on (almost) every other line.
            if (v.gstPct == null) (gstByHsn[v.hsn] ?: gstAll)?.let { g ->
                v = v.copy(gstPct = g); changed = true
                if (gc in raw.indices && raw[gc].none(Char::isDigit)) raw[gc] = "${g.toPlainString()} %"
            }
            // The bill's common Disc. % (and the pairs that then fit), proven by the line's own numbers.
            if (!proven(v) && disc != null && v.rate != null && v.taxable != null) {
                val tryDisc = v.copy(discPct = disc)
                val withQty = if (tryDisc.qty == null) {
                    val per = v.rate * (HUNDRED - disc) / HUNDRED
                    val q = if (per.signum() > 0) v.taxable.divide(per, 4, RoundingMode.HALF_UP) else null
                    val qi = q?.setScale(0, RoundingMode.HALF_UP)
                    if (q != null && qi != null && qi.signum() > 0 && (q - qi).abs() <= BigDecimal("0.002")) tryDisc.copy(qty = qi) else tryDisc
                } else tryDisc
                if (proven(withQty)) {
                    if (v.qty == null && withQty.qty != null && qc in raw.indices) raw[qc] = "${withQty.qty.toPlainString()} ${raw[qc].filter(Char::isLetter)}".trim()
                    // Marked as worked out, so nobody takes it for what the photo showed.
                    if (dc in raw.indices) raw[dc] = if (raw[dc].any(Char::isDigit)) "${disc.toPlainString()} %" else "${disc.toPlainString()} % (हिसाब से)"
                    v = withQty; changed = true
                }
            }
            if (!changed) return@map item
            val needs = if (proven(v)) item.needs - ARITH else item.needs
            if (proven(v) && !proven(item.current)) fixed++
            item.copy(current = v, original = v, needs = needs, raw = raw)
        }
        return Result(out, fixed)
    }
}
