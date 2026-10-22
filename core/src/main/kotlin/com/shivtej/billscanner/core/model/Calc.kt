package com.shivtej.billscanner.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * All money and percentage maths. BigDecimal only, rounded half-up to 2 decimals for display.
 *
 * Words used:
 * - rate: price per pair printed on the bill (before the bill discount, before tax)
 * - bill discount %: the % the supplier printed on this line
 * - MRP discount %: ((MRP - rate) / MRP) x 100 — a different number, never mixed with the bill %
 * - net rate: rate after the bill discount = rate x (1 - bill% / 100)
 * - effective MRP discount %: ((MRP - net rate) / MRP) x 100
 */
object Calc {
    val HUNDRED = BigDecimal(100)
    fun r2(v: BigDecimal): BigDecimal = v.setScale(2, RoundingMode.HALF_UP)
    fun bd(s: String?): BigDecimal? = s?.trim()?.replace(",", "")?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    /** Calculated Discount Against MRP (%) = ((MRP - Rate) / MRP) x 100. */
    fun mrpDiscPct(v: ItemValues): BigDecimal? {
        val mrp = v.mrp ?: return null; val rate = v.rate ?: return null
        if (mrp.signum() <= 0) return null
        return r2((mrp - rate).multiply(HUNDRED).divide(mrp, 10, RoundingMode.HALF_UP))
    }

    /** Rate per pair after the supplier's bill discount (still before tax). */
    fun netRate(v: ItemValues): BigDecimal? {
        val rate = v.rate ?: return null
        val d = v.discPct ?: return null
        return r2(rate.multiply(HUNDRED - d).divide(HUNDRED, 10, RoundingMode.HALF_UP))
    }

    /** Effective discount against MRP, using the net rate (bill discount applied once). */
    fun effectiveMrpPct(v: ItemValues): BigDecimal? {
        val mrp = v.mrp ?: return null; val net = netRate(v) ?: return null
        if (mrp.signum() <= 0) return null
        return r2((mrp - net).multiply(HUNDRED).divide(mrp, 10, RoundingMode.HALF_UP))
    }

    /** Rate needed for a target discount % against MRP. */
    fun targetRate(mrp: BigDecimal?, targetPct: BigDecimal?): BigDecimal? {
        if (mrp == null || targetPct == null) return null
        return r2(mrp.multiply(HUNDRED - targetPct).divide(HUNDRED, 10, RoundingMode.HALF_UP))
    }

    fun taxTotal(v: ItemValues): BigDecimal =
        (v.cgstAmt ?: BigDecimal.ZERO) + (v.sgstAmt ?: BigDecimal.ZERO) + (v.igstAmt ?: BigDecimal.ZERO)

    /** Final Line Total = taxable value + CGST + SGST + IGST + other charges. */
    fun lineTotal(v: ItemValues): BigDecimal? {
        val t = v.taxable ?: return null
        return r2(t + taxTotal(v) + (v.other ?: BigDecimal.ZERO))
    }

    /**
     * Recalculates the dependent amounts from qty, rate, bill % and tax % (used after an edit).
     * The supplier's printed amounts stay in the original values.
     */
    fun recalc(v: ItemValues): ItemValues {
        val gross = if (v.qty != null && v.rate != null) r2(v.qty * v.rate) else v.gross
        val disc = if (gross != null && v.discPct != null) r2(gross * v.discPct / HUNDRED) else v.discAmt
        val taxable = if (gross != null && disc != null) r2(gross - disc) else v.taxable
        fun tax(p: BigDecimal?, old: BigDecimal?) = if (taxable != null && p != null) r2(taxable * p / HUNDRED) else old
        return v.copy(
            gross = gross, discAmt = disc, taxable = taxable,
            cgstAmt = tax(v.cgstPct, v.cgstAmt), sgstAmt = tax(v.sgstPct, v.sgstAmt), igstAmt = tax(v.igstPct, v.igstAmt),
        )
    }

    /** How the bill's own numbers on one line agree with each other. */
    data class LineCheck(val field: F, val printed: BigDecimal, val expected: BigDecimal) {
        val diff: BigDecimal get() = printed - expected
    }

    /** Small rounding differences (a few paise) are normal on supplier bills. */
    val TOLERANCE = BigDecimal("0.03")

    fun checkLine(v: ItemValues): List<LineCheck> {
        val out = mutableListOf<LineCheck>()
        fun cmp(f: F, printed: BigDecimal?, expected: BigDecimal?) {
            if (printed != null && expected != null && (printed - expected).abs() > TOLERANCE) out += LineCheck(f, printed, r2(expected))
        }
        if (v.qty != null && v.rate != null) cmp(F.GROSS, v.gross, v.qty * v.rate)
        if (v.gross != null && v.discPct != null) cmp(F.DISC_AMT, v.discAmt, v.gross * v.discPct / HUNDRED)
        if (v.gross != null && v.discAmt != null) cmp(F.TAXABLE, v.taxable, v.gross - v.discAmt)
        // Bills without a "Total" column: Amount = qty x rate less the discount.
        if (v.gross == null && v.qty != null && v.rate != null && v.taxable != null) {
            if (v.discPct != null) cmp(F.TAXABLE, v.taxable, v.qty * v.rate * (HUNDRED - v.discPct) / HUNDRED)
            else if (v.discAmt != null) cmp(F.TAXABLE, v.taxable, v.qty * v.rate - v.discAmt)
        }
        if (v.taxable != null) {
            v.cgstPct?.let { cmp(F.CGST_AMT, v.cgstAmt, v.taxable * it / HUNDRED) }
            v.sgstPct?.let { cmp(F.SGST_AMT, v.sgstAmt, v.taxable * it / HUNDRED) }
            v.igstPct?.let { cmp(F.IGST_AMT, v.igstAmt, v.taxable * it / HUNDRED) }
        }
        return out
    }

    /** Fields where [b] differs from [a]. */
    fun diff(a: ItemValues, b: ItemValues): Set<F> = buildSet {
        if (a.sr != b.sr) add(F.SR)
        if (a.article != b.article) add(F.ARTICLE)
        if (a.name != b.name) add(F.NAME)
        if (a.colour != b.colour) add(F.COLOUR)
        if (a.sizeText != b.sizeText) add(F.SIZE)
        if (a.hsn != b.hsn) add(F.HSN)
        fun n(f: F, x: BigDecimal?, y: BigDecimal?) { if ((x == null) != (y == null) || (x != null && y != null && x.compareTo(y) != 0)) add(f) }
        n(F.QTY, a.qty, b.qty); n(F.MRP, a.mrp, b.mrp); n(F.RATE, a.rate, b.rate); n(F.GROSS, a.gross, b.gross)
        n(F.DISC_PCT, a.discPct, b.discPct); n(F.DISC_AMT, a.discAmt, b.discAmt); n(F.TAXABLE, a.taxable, b.taxable)
        n(F.CGST_PCT, a.cgstPct, b.cgstPct); n(F.CGST_AMT, a.cgstAmt, b.cgstAmt); n(F.SGST_PCT, a.sgstPct, b.sgstPct)
        n(F.SGST_AMT, a.sgstAmt, b.sgstAmt); n(F.IGST_PCT, a.igstPct, b.igstPct); n(F.IGST_AMT, a.igstAmt, b.igstAmt)
        n(F.OTHER, a.other, b.other)
    }

    // ------------------------------------------------------------ बैठा % (what the article really costs against MRP)

    /** GST % of the whole bill when tax is charged at the bottom (CGST + SGST, or IGST). */
    fun billGstPct(h: Header): BigDecimal? = when {
        h.igstPct != null -> h.igstPct
        h.cgstPct != null && h.sgstPct != null -> h.cgstPct + h.sgstPct
        else -> null
    }

    /** GST % that applies to this line. */
    fun gstPct(v: ItemValues, h: Header): BigDecimal? = v.gstPct
        ?: if (v.cgstPct != null && v.sgstPct != null) v.cgstPct + v.sgstPct else v.igstPct ?: billGstPct(h)

    /** Cost of one pair after every discount on the bill, before GST. */
    fun netPerPair(v: ItemValues): BigDecimal? {
        val q = v.qty
        if (v.taxable != null && q != null && q.signum() > 0) return v.taxable.divide(q, 6, RoundingMode.HALF_UP)
        return netRate(v) ?: v.rate
    }

    /** Cost of one pair including GST (what lands in the shop). */
    fun landedPerPair(v: ItemValues, h: Header): BigDecimal? {
        val net = netPerPair(v) ?: return null
        val g = gstPct(v, h) ?: return net
        return net * (HUNDRED + g) / HUNDRED
    }

    /**
     * बैठा % — the discount the shop really gets against MRP:
     * ((MRP − cost per pair) ÷ MRP) × 100, with cost including GST when [withGst].
     */
    fun sittingPct(v: ItemValues, h: Header, withGst: Boolean = true): BigDecimal? {
        val mrp = v.mrp ?: return null
        if (mrp.signum() <= 0) return null
        val cost = (if (withGst) landedPerPair(v, h) else netPerPair(v)) ?: return null
        return r2((mrp - cost).multiply(HUNDRED).divide(mrp, 10, RoundingMode.HALF_UP))
    }

    /** बैठा % of the whole bill, weighted by MRP value. */
    fun sittingTotal(inv: Invoice): BigDecimal? {
        var mrpV = BigDecimal.ZERO; var costV = BigDecimal.ZERO
        for (i in inv.items) {
            if (i.kind != ItemKind.ARTICLE) continue
            val v = i.current; val q = v.qty ?: continue; val m = v.mrp ?: continue
            val c = (if (inv.sittingWithGst) landedPerPair(v, inv.header) else netPerPair(v)) ?: continue
            mrpV += m * q; costV += c * q
        }
        return if (mrpV.signum() > 0) r2((mrpV - costV).multiply(HUNDRED).divide(mrpV, 10, RoundingMode.HALF_UP)) else null
    }

    fun fmt(v: BigDecimal?): String = v?.let { r2(it).toPlainString() } ?: ""
    fun pct(v: BigDecimal?): String = v?.let { r2(it).toPlainString() + "%" } ?: ""
}

/** Totals of a whole invoice and how they compare with the printed grand total. */
data class Totals(
    val lines: Int,
    val articleLines: Int,
    val qty: BigDecimal,
    val gross: BigDecimal,
    val discount: BigDecimal,
    val taxable: BigDecimal,
    val cgst: BigDecimal,
    val sgst: BigDecimal,
    val igst: BigDecimal,
    val other: BigDecimal,
    val grand: BigDecimal,
    val printedGrand: BigDecimal?,
) {
    val difference: BigDecimal? get() = printedGrand?.let { it - grand }

    companion object {
        fun of(inv: Invoice): Totals {
            val z = BigDecimal.ZERO
            val vs = inv.items.map { it.current }
            fun sum(f: (ItemValues) -> BigDecimal?) = vs.fold(z) { a, v -> a + (f(v) ?: z) }
            val other = sum { it.other } + (inv.header.otherCharges ?: z)
            val taxable = sum { it.taxable }
            var cgst = sum { it.cgstAmt }; var sgst = sum { it.sgstAmt }; var igst = sum { it.igstAmt }
            // Tax charged once at the bottom of the bill.
            if (cgst.signum() == 0 && sgst.signum() == 0 && igst.signum() == 0) {
                val h = inv.header
                cgst = h.cgstAmt ?: h.cgstPct?.let { Calc.r2(taxable * it / Calc.HUNDRED) } ?: z
                sgst = h.sgstAmt ?: h.sgstPct?.let { Calc.r2(taxable * it / Calc.HUNDRED) } ?: z
                igst = h.igstAmt ?: h.igstPct?.let { Calc.r2(taxable * it / Calc.HUNDRED) } ?: z
            }
            val grand = Calc.r2(taxable + cgst + sgst + igst + other + (inv.header.roundOff ?: z) - (inv.header.overallDiscount ?: z))
            return Totals(
                lines = inv.items.size,
                articleLines = inv.items.count { it.kind == ItemKind.ARTICLE },
                qty = inv.items.filter { it.kind == ItemKind.ARTICLE }.fold(z) { a, i -> a + (i.current.qty ?: z) },
                gross = sum { it.gross }, discount = sum { it.discAmt }, taxable = taxable,
                cgst = cgst, sgst = sgst, igst = igst, other = other, grand = grand,
                printedGrand = inv.header.printedGrandTotal,
            )
        }
    }
}
