package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The bill's own arithmetic, without trusting the columns: for a line that did not add up, every number printed
 * on it is taken and the one reading is searched where the numbers explain each other —
 * MRP (written "MRP : 605.00" anywhere on the line), pairs × rate = total, total × Disc % = discount,
 * total − discount = amount (and amount × GST % = GST amount, when printed).
 * A reading is kept only when the printed numbers prove it, so a line that already added up is never touched.
 */
object LineSolver {
    class Result(val items: List<LineItem>, val fixed: Int)

    private val TOL = BigDecimal("0.06")
    private val HUNDRED = BigDecimal(100)
    private val MRP = Regex("""(?i)\bM\s*\.?\s*R\s*\.?\s*P\s*\.?\s*[:;.=\-]*\s*(?:Rs\.?|₹)?\s*[:;]?\s*(\d[\d,]*(?:\.\d{1,2})?)""")
    private val SIZE = Regex("""(?i)^\d{1,2}\s*[xX/]\s*\d{1,2}(/\d+)?$""")

    private val GST = listOf("2.5", "5", "6", "9", "12", "14", "18", "28", "1.5", "0.75").map(::BigDecimal)

    private fun near(a: BigDecimal, b: BigDecimal) = (a - b).abs() <= TOL

    /** The numbers printed on the line, each as often as it is printed (HSN codes, sizes and the MRP left out). */
    fun numbers(text: String, mrpText: String?): List<BigDecimal> {
        var t = text
        if (mrpText != null) t = t.replaceFirst(mrpText, " ")
        return t.split(Regex("""[\s|]+""")).mapNotNull { raw ->
            var w = raw.trim().trimEnd(',', ';', ':').replace(Regex("""(?i)(pairs?|prs?|pcs|nos?|%)$"""), "")
            // Letters read in place of digits inside a number: "1980.0o", "2.50O", "12l".
            if (w.isNotEmpty() && w.first().isDigit() && w.any { it in "oOlIS" } && w.all { it.isDigit() || it in ".,oOlIS" })
                w = w.map { when (it) { 'o', 'O' -> '0'; 'l', 'I' -> '1'; 'S' -> '5'; else -> it } }.joinToString("")
            if (w.isEmpty() || SIZE.matches(w) || !w.first().isDigit() && w.first() != '.') return@mapNotNull null
            if (w.any { it.isLetter() } || w.count { it == '-' || it == '/' } > 0) return@mapNotNull null
            // "3062,16": the OCR read the decimal point as a comma.
            val w2 = if (Regex("""^\d+,\d{2}$""").matches(w)) w.replace(',', '.') else w.replace(",", "")
            val n = w2.toBigDecimalOrNull() ?: return@mapNotNull null
            if (n.signum() <= 0) return@mapNotNull null
            if (n.scale() <= 0 && n.toPlainString().length >= 6) return@mapNotNull null   // HSN 64029990, phone numbers
            n
        }
    }

    data class Fit(val qty: BigDecimal, val rate: BigDecimal, val gross: BigDecimal?, val discPct: BigDecimal?, val discAmt: BigDecimal?,
                   val taxable: BigDecimal, val gstPct: BigDecimal?, val gstAmt: BigDecimal?, val score: Int)

    /** The reading that explains the most printed numbers, or null when none is proven. */
    fun fit(nums: List<BigDecimal>): Fit? {
        if (nums.size < 3 || nums.size > 40) return null
        fun count(x: BigDecimal) = nums.count { near(it, x) }
        fun has(x: BigDecimal) = nums.any { near(it, x) }
        fun find(x: BigDecimal) = nums.firstOrNull { near(it, x) }
        var best: Fit? = null
        val qtys = nums.filter { it.stripTrailingZeros().scale() <= 0 && it <= BigDecimal(5000) }.distinct()
        for (q in qtys) for (r in nums.distinct()) {
            if (r == q && count(r) < 2) continue
            val g = (q * r).setScale(2, RoundingMode.HALF_UP)
            // A line of at least ₹20; a GST % printed twice is not a rate.
            if (g < BigDecimal(20) || GST.any { it.compareTo(r) == 0 }) continue
            // The total must be printed (for one pair: the same number twice, rate and total).
            val gPrinted = if (q.compareTo(BigDecimal.ONE) == 0) count(r) >= 2 else has(g)
            // Or the total spoilt but proven by the discount and amount printed after it.
            val chain = q.compareTo(BigDecimal.ONE) != 0 && nums.distinct().any { d -> d < BigDecimal(90) && (g * d / HUNDRED).setScale(2, RoundingMode.HALF_UP).let { a -> a >= BigDecimal("0.5") && has(a) && has(g - a) } }
            if (!gPrinted && !chain) continue
            val cands = mutableListOf<Fit>()
            // Disc % with its amount, then the amount after it.
            for (d in nums.distinct().filter { it > BigDecimal.ZERO && it < BigDecimal(90) }) {
                val a = (g * d / HUNDRED).setScale(2, RoundingMode.HALF_UP)
                val t = g - a
                val aP = has(a); val tP = has(t)
                if (a < BigDecimal("0.5")) continue
                if (!gPrinted && !(aP && tP)) continue
                if (tP && (aP || d.scale() > 0)) cands += Fit(q, r, g, d, if (aP) find(a) else a, find(t)!!, null, null, 3 + (if (aP) 1 else 0))
            }
            // No discount on the line: the amount is the total.
            if (count(g) >= (if (q.compareTo(BigDecimal.ONE) == 0) 3 else 2) || cands.isEmpty()) cands += Fit(q, r, g, null, null, g, null, null, 2)
            for (c in cands) {
                // GST on the line: % and amount.
                var f = c
                for (p in nums.distinct().filter { x -> GST.any { it.compareTo(x) == 0 } }) {
                    val amt = (c.taxable * p / HUNDRED).setScale(2, RoundingMode.HALF_UP)
                    if (has(amt)) { f = c.copy(gstPct = p, gstAmt = amt, score = c.score + 1); break }
                }
                if (best == null || f.score > best!!.score || f.score == best!!.score && f.taxable > best!!.taxable) best = f
            }
        }
        return best
    }

    /** Lines that did not add up, solved from their own printed numbers. */
    fun rescue(items: List<LineItem>): Result {
        var fixed = 0
        val out = items.map { item ->
            if (item.kind != ItemKind.ARTICLE || Heal.sure(item)) return@map item
            val text = item.raw.joinToString(" ")
            val m = MRP.find(text)
            val mrp = m?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            val f = fit(numbers(text, m?.value)) ?: return@map item
            if (f.score < 3 && f.discPct == null && item.current.taxable != null && !near(item.current.taxable, f.taxable)) return@map item
            val v0 = item.current
            var v = v0.copy(qty = f.qty, rate = f.rate, gross = if (v0.gross != null || f.discPct != null) f.gross else v0.gross,
                discPct = f.discPct, discAmt = f.discAmt, taxable = f.taxable, mrp = v0.mrp ?: mrp)
            if (f.gstPct != null) v = v.copy(cgstPct = v0.cgstPct?.takeIf { near(it, f.gstPct) } ?: v0.cgstPct, cgstAmt = v0.cgstAmt)
            // A misread MRP that is printed with its "MRP" label.
            if (mrp != null && v0.mrp != null && (v0.mrp - mrp).abs() > TOL && F.MRP in item.needs) v = v.copy(mrp = mrp)
            if (com.shivtej.billscanner.core.model.Calc.checkLine(v).isNotEmpty()) {
                // GST columns that do not fit the solved amount are dropped rather than keeping the line doubtful.
                v = v.copy(cgstAmt = null, sgstAmt = null, igstAmt = null, cgstPct = null, sgstPct = null, igstPct = null)
            }
            if (!Heal.proven(v)) return@map item
            fixed++
            val needs = item.needs - Heal.MONEY + (if (v.mrp == null && F.MRP in item.needs) setOf(F.MRP) else emptySet())
            item.copy(current = v, original = v, needs = needs)
        }
        return Result(out, fixed)
    }
}
