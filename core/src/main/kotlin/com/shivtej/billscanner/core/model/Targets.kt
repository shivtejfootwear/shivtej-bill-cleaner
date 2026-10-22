package com.shivtej.billscanner.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Companies / brands inside one bill ("SM", "SFG", "ASIAN", "Paralite"…), so each can get its own target %.
 * The group is the brand printed with the article (the letters before the article number).
 */
object Groups {
    fun key(v: ItemValues): String {
        val b = v.brand.ifBlank { v.article.split(' ', '-').firstOrNull { it.any(Char::isLetter) }?.filter { it.isLetter() }.orEmpty() }
        return b.uppercase().ifEmpty { "अन्य" }
    }

    data class Group(
        val key: String,
        val items: List<LineItem>,
        val pairs: BigDecimal,
        /** Bill value of these lines before GST. */
        val billValue: BigDecimal,
        /** Average बैठा % of the group, weighted by bill value. */
        val sittingPct: BigDecimal?,
        val target: BigDecimal?,
    )

    fun of(inv: Invoice): List<Group> = inv.items.filter { it.kind == ItemKind.ARTICLE }.groupBy { key(it.current) }.map { (k, list) ->
        val z = BigDecimal.ZERO
        val value = list.fold(z) { a, i -> a + (i.current.taxable ?: z) }
        // Weighted by MRP value: Σ(MRP×qty − cost×qty) / Σ(MRP×qty).
        var mrpValue = z; var costValue = z
        for (i in list) {
            val v = i.current
            val q = v.qty ?: continue; val m = v.mrp ?: continue
            if (Calc.sittingPct(v, inv.header, inv.sittingWithGst) == null) continue
            val cost = (if (inv.sittingWithGst) Calc.landedPerPair(v, inv.header) else Calc.netPerPair(v)) ?: continue
            mrpValue += m * q; costValue += cost * q
        }
        val sitting = if (mrpValue.signum() > 0) Calc.r2((mrpValue - costValue).multiply(Calc.HUNDRED).divide(mrpValue, 10, RoundingMode.HALF_UP)) else null
        Group(k, list, list.fold(z) { a, i -> a + (i.current.qty ?: z) }, Calc.r2(value), sitting, inv.groupTargets[k])
    }.sortedByDescending { it.billValue }
}

/**
 * The new bill at the person's target % against MRP. Always the person's own estimate —
 * the supplier's bill is never changed.
 */
object Target {
    /** Target % for a line: its own, else its company's, else the whole bill's. */
    fun pct(inv: Invoice, item: LineItem): BigDecimal? =
        if (item.kind != ItemKind.ARTICLE) null else item.targetPct ?: inv.groupTargets[Groups.key(item.current)] ?: inv.defaultTargetPct

    data class Line(
        val item: LineItem,
        val pct: BigDecimal,
        /** New cost per pair including GST = MRP × (1 − target %). */
        val landed: BigDecimal,
        /** New rate per pair before GST. */
        val net: BigDecimal,
        /** New line value before GST = net × qty. */
        val amount: BigDecimal,
        val gst: BigDecimal,
        /** Bill's line value before GST. */
        val billAmount: BigDecimal?,
    ) {
        val difference: BigDecimal? get() = billAmount?.let { Calc.r2(amount - it) }
    }

    fun line(inv: Invoice, item: LineItem): Line? {
        val p = pct(inv, item) ?: return null
        val v = item.current
        val mrp = v.mrp ?: return null; val q = v.qty ?: return null
        // MRP or amount misread (बैठा % makes no sense): no new rate until it is fixed.
        if (v.taxable != null && Calc.sittingPct(v, inv.header, inv.sittingWithGst) == null) return null
        val g = Calc.gstPct(v, inv.header) ?: BigDecimal.ZERO
        val h = Calc.HUNDRED
        val net: BigDecimal; val landed: BigDecimal
        if (inv.sittingWithGst) {
            landed = Calc.r2(mrp.multiply(h - p).divide(h, 10, RoundingMode.HALF_UP))
            net = Calc.r2(mrp.multiply(h - p).divide(h + g, 10, RoundingMode.HALF_UP))
        } else {
            net = Calc.r2(mrp.multiply(h - p).divide(h, 10, RoundingMode.HALF_UP))
            landed = Calc.r2(net.multiply(h + g).divide(h, 10, RoundingMode.HALF_UP))
        }
        val amount = Calc.r2(net * q)
        return Line(item, p, landed, net, amount, Calc.r2(amount.multiply(g).divide(h, 10, RoundingMode.HALF_UP)), v.taxable)
    }

    data class Sheet(
        val lines: List<Line>,
        /** Lines without a target keep their bill value. */
        val untouched: List<LineItem>,
        val taxable: BigDecimal,
        val gst: BigDecimal,
        val total: BigDecimal,
        /** Total rounded to the rupee, as bills are. */
        val rounded: BigDecimal,
        val billTotal: BigDecimal,
    ) {
        val difference: BigDecimal get() = Calc.r2(rounded - billTotal)
        val any: Boolean get() = lines.isNotEmpty()
    }

    /**
     * GST % each line of the new bill is taxed at, and the tax already printed on a line that keeps its bill
     * value. GST is worked out on the total of each rate, as bills do.
     */
    fun gstRate(inv: Invoice, item: LineItem): BigDecimal = Calc.gstPct(item.current, inv.header) ?: BigDecimal.ZERO

    /** A line a target % can be put on: an article with MRP and pairs whose बैठा % makes sense. */
    fun targetable(inv: Invoice, item: LineItem): Boolean {
        val v = item.current
        return item.kind != ItemKind.CHARGE && v.mrp != null && v.qty != null && (v.taxable == null || Calc.sittingPct(v, inv.header, inv.sittingWithGst) != null)
    }

    fun ownTax(item: LineItem): BigDecimal? {
        val v = item.current
        return if (v.cgstAmt != null || v.sgstAmt != null || v.igstAmt != null) Calc.taxTotal(v) else null
    }

    fun sheet(inv: Invoice): Sheet {
        val z = BigDecimal.ZERO
        val lines = inv.items.mapNotNull { line(inv, it) }
        val done = lines.map { it.item.id }.toSet()
        val untouched = inv.items.filter { it.id !in done }
        val byRate = sortedMapOf<BigDecimal, BigDecimal>()
        var taxable = z
        var fixedTax = z
        for (l in lines) { taxable += l.amount; byRate.merge(gstRate(inv, l.item).stripTrailingZeros(), l.amount, BigDecimal::add) }
        for (i in untouched) {
            val t = i.current.taxable ?: continue
            taxable += t
            val own = if (targetable(inv, i)) null else ownTax(i)
            if (own != null) fixedTax += own else byRate.merge(gstRate(inv, i).stripTrailingZeros(), t, BigDecimal::add)
        }
        val gst = byRate.entries.fold(fixedTax) { a, (g, amt) -> a + Calc.r2(amt.multiply(g).divide(Calc.HUNDRED, 10, RoundingMode.HALF_UP)) }
        val bill = Totals.of(inv)
        val total = Calc.r2(taxable + gst + (inv.header.otherCharges ?: z) - (inv.header.overallDiscount ?: z))
        return Sheet(lines, untouched, Calc.r2(taxable), Calc.r2(gst), total, total.setScale(0, RoundingMode.HALF_UP).setScale(2), bill.printedGrand ?: bill.grand)
    }
}
