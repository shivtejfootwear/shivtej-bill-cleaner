package com.shivtej.billscanner.core.synth

import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Footwear bills of many kinds, made up from what such bills look like, with the true values of every line.
 * Used to train and check the reading on patterns no one has sent yet.
 *
 * Kinds (the software or the way the supplier prints):
 *  TALLY     Sl No | Description of Goods | HSN/SAC | Quantity | Rate | per | Disc % | Amount; CGST/SGST rows inside the table,
 *            "Amount Chargeable (in words)", HSN tax analysis table.
 *  BUSY      S.N. | Description of Goods | HSN/SAC Code | Qty. | Unit | Price | Disc.% | Amount; "Add : CGST @ 2.50 %" lines.
 *  MARG      Sr | Product | HSN | Qty | Free | MRP | Rate | Disc% | GST% | Amount; GST summary and NET AMOUNT.
 *  VYAPAR    # | Item name | HSN/ SAC | Quantity | Unit | Price/ Unit | Discount | GST | Amount (GST included).
 *  BRAND     Article | Colour | Size | Ctn | Pairs | MRP | Rate | Gross Amt | Disc % | Disc Amt | Taxable Value | CGST % | CGST Amt | SGST % | SGST Amt | Total.
 *  CRYSTAL   S.No. | Description | HSN | Old MRP | New MRP | Qty In C/N | Units In Pairs | Rate/Unit | Value; LESS/ADD lines, IGST.
 *  EBASE     Sr | Description | HSN | Quantity | MRP | Rate (with GST) | Net Rate | GST | Amount (with GST).
 *  ESTIMATE  order bill / estimate without GST: Sr | Particulars | Size | Qty | (MRP) | Rate | Amount; "Less : Discount" and Net.
 *  SIMPLE    Sr | Article | Colour | Size | HSN | GST% | MRP | Pairs | Rate | Disc% | Amount; CGST / SGST / Round Off / Grand Total.
 */
object SynthBill {
    enum class Style { TALLY, BUSY, MARG, VYAPAR, BRAND, CRYSTAL, EBASE, ESTIMATE, SIMPLE, MATRIX, SCHEME }

    data class TLine(
        val sr: Int, val article: String, val colour: String, val size: String, val hsn: String,
        val qty: Int, val cartons: Int, val mrp: BigDecimal?, val oldMrp: BigDecimal?, val rate: BigDecimal,
        val discPct: BigDecimal?, val discAmt: BigDecimal, val gross: BigDecimal, val taxable: BigDecimal,
        val gstPct: BigDecimal, val tax: BigDecimal, val withGst: BigDecimal,
        /** Rate per pair with GST (EBASE). */
        val rateGst: BigDecimal?,
        /** Second discount on what is left after the first (scheme / special). */
        val disc2Pct: BigDecimal? = null, val disc1Amt: BigDecimal = discAmt, val disc2Amt: BigDecimal = BigDecimal.ZERO,
        /** Pairs per size (MATRIX). */
        val perSize: List<Int> = emptyList(),
    )

    class Bill(
        val seed: Long, val style: Style, val pages: List<OcrPage>, val lines: List<TLine>, val grand: BigDecimal,
        val invoiceNo: String, val supplier: String, val features: List<String>,
        /** Line amounts include GST (the reader's taxable is worked back, so paise may differ). */
        val inclusive: Boolean, val showsMrp: Boolean,
    )

    private val BRANDS = listOf("RELAXO", "SPARX", "BAHAMAS", "FLITE", "PARAGON", "VKC", "ACTION", "CAMPUS", "WALKAROO", "TOUCH", "AQUALITE",
        "LIBERTY", "KHADIM", "AJANTA", "ASIAN", "PARALITE", "INDUS", "BINNY", "KIERO", "LANCER", "PU", "EVA", "STIMULUS", "HITZ", "ADDA")
    private val COLOURS = listOf("BLACK", "BLK", "NAVY", "N.BLUE", "GREY", "TAN", "BROWN", "BRN", "WHITE", "WHT/BLUE", "OLIVE", "RED", "PINK",
        "BEIGE", "CHERRY", "CAMEL", "BLK/RED", "GRAPHITE", "MAROON", "Black", "Grey", "Navy Blue", "Tan")
    private val GENDER = listOf("GENTS", "LADIES", "KIDS", "BOYS", "GIRLS", "G", "L", "")
    private val SUPPLIERS = listOf("SHREE GANESH FOOTWEAR", "RAJ SHOE COMPANY", "NEW INDIA FOOTWEAR PVT LTD", "SAI DISTRIBUTORS",
        "MAHALAXMI ENTERPRISES", "BALAJI TRADERS", "KRISHNA FOOTWEAR AGENCIES", "ROYAL SHOES PVT. LTD.", "OM SAI AGENCY", "JAI HIND FOOTWEAR")
    private val HSNS = listOf("64029990", "64041920", "64039990", "6402", "640219", "64029910", "640299", "64041000", "64059000")

    private fun bd(x: Double) = BigDecimal(x).setScale(2, RoundingMode.HALF_UP)
    private fun r2(x: BigDecimal) = x.setScale(2, RoundingMode.HALF_UP)

    /** Indian grouping: 1,02,237.72 */
    fun indian(x: BigDecimal): String {
        val s = r2(x).toPlainString(); val neg = s.startsWith("-"); val p = s.removePrefix("-").split('.')
        var i = p[0]
        val out = if (i.length <= 3) i else { val last3 = i.takeLast(3); i = i.dropLast(3); i.reversed().chunked(2).joinToString(",").reversed() + "," + last3 }
        return (if (neg) "-" else "") + out + "." + p[1]
    }
    fun western(x: BigDecimal): String = String.format(java.util.Locale.US, "%,.2f", r2(x))
    fun plain(x: BigDecimal): String = r2(x).toPlainString()

    private val ONES = listOf("", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen",
        "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
    private val TENS = listOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")
    private fun two(n: Int) = if (n < 20) ONES[n] else (TENS[n / 10] + " " + ONES[n % 10]).trim()
    private fun three(n: Int) = listOfNotNull(if (n >= 100) ONES[n / 100] + " Hundred" else null, two(n % 100).ifEmpty { null }).joinToString(" ")
    fun words(x: BigDecimal): String {
        var n = x.setScale(0, RoundingMode.HALF_UP).toLong()
        val parts = mutableListOf<String>()
        val cr = n / 10000000; n %= 10000000; val lk = n / 100000; n %= 100000; val th = n / 1000; n %= 1000
        if (cr > 0) parts += three(cr.toInt()) + " Crore"; if (lk > 0) parts += two(lk.toInt()) + " Lakh"; if (th > 0) parts += two(th.toInt()) + " Thousand"
        if (n > 0) parts += three(n.toInt())
        return parts.joinToString(" ")
    }

    /** One column: its heading (one or two rows), alignment, the text of a line's cell, and the cell of the total row. */
    private class C(val head: List<String>, val right: Boolean, val cell: (TLine) -> String, val total: String? = null) {
        var x = 0; var w = 0
    }

    private class Ink(val rnd: Random, val slope: Double, val h: Int, val jitter: Int) {
        val words = mutableListOf<OcrWord>()
        val ch get() = h * 0.5
        fun at(x: Int, y: Int, text: String, hh: Int = h) {
            val c = hh * 0.5
            var cx = x.toDouble()
            for (part in text.split(' ')) {
                if (part.isEmpty()) { cx += c; continue }
                val w = part.length * c
                val yy = (y + slope * cx + (if (jitter > 0) rnd.nextInt(-jitter, jitter + 1) else 0)).toInt()
                words += OcrWord(part, Box(cx.toInt(), yy, (cx + w).toInt(), yy + hh))
                cx += w + c
            }
        }
        fun right(xr: Int, y: Int, text: String) = at((xr - text.length * ch).toInt(), y, text)
    }

    /**
     * What a phone camera's OCR does to a page: 0 read as O, a comma read as a point, a lost decimal point, a word
     * lost, a word broken in two, two words read as one. [p] is the chance per word of each kind of slip.
     */
    fun noisy(page: OcrPage, p: Double, rnd: Random): OcrPage {
        if (p <= 0) return page
        val ws = page.words.sortedWith(compareBy({ it.box.t / 10 }, { it.box.l })).toMutableList()
        val out = mutableListOf<OcrWord>()
        var i = 0
        while (i < ws.size) {
            var w = ws[i]
            val t = w.text
            val isNum = t.any(Char::isDigit) && t.count(Char::isDigit) * 2 >= t.length
            when {
                rnd.nextDouble() < p / 2 -> { i++; continue }                                           // lost
                isNum && t.contains('0') && rnd.nextDouble() < p -> w = w.copy(text = t.replaceFirst('0', 'O'))
                isNum && t.contains(',') && rnd.nextDouble() < p -> w = w.copy(text = t.replaceFirst(',', '.'))
                isNum && t.contains('.') && rnd.nextDouble() < p / 2 -> w = w.copy(text = t.replace(".", ""))
                t.length >= 4 && rnd.nextDouble() < p -> {                                                  // broken in two
                    val k = rnd.nextInt(1, t.length); val cw = w.box.w.toDouble() / t.length
                    val x = w.box.l + (cw * k).toInt()
                    out += OcrWord(t.substring(0, k), Box(w.box.l, w.box.t, x - 1, w.box.b)); w = OcrWord(t.substring(k), Box(x + 1, w.box.t, w.box.r, w.box.b))
                }
                i + 1 < ws.size && rnd.nextDouble() < p / 2 -> {                                            // two read as one
                    val n = ws[i + 1]
                    if (kotlin.math.abs(n.box.t - w.box.t) < w.box.h / 2 && n.box.l - w.box.r in 0..(w.box.h)) {
                        w = OcrWord(t + n.text, Box(w.box.l, minOf(w.box.t, n.box.t), n.box.r, maxOf(w.box.b, n.box.b))); i++
                    }
                }
            }
            out += w; i++
        }
        return page.copy(words = out.shuffled(rnd))
    }

    private fun style0(s: Style) = s

    fun make(seed: Long, style: Style? = null, noise: Double = 0.0): Bill {
        val rnd = Random(seed)
        val st = style ?: Style.entries[rnd.nextInt(Style.entries.size)]
        val features = mutableListOf<String>()
        fun <T> pick(l: List<T>) = l[rnd.nextInt(l.size)]
        fun chance(p: Double) = rnd.nextDouble() < p

        // Hand-made order bills often print whole rupees only.
        val whole = style0(st) == Style.ESTIMATE && chance(0.4)
        val fmt0: (BigDecimal) -> String = when (rnd.nextInt(3)) { 0 -> { x -> indian(x) }; 1 -> { x -> western(x) }; else -> { x -> plain(x) } }
        val fmt: (BigDecimal) -> String = if (whole) { x -> fmt0(x).removeSuffix(".00") } else fmt0
        val interState = st == Style.CRYSTAL || chance(0.15)
        val gst = BigDecimal(if (chance(0.9)) 5 else 18)
        val noGst = st == Style.ESTIMATE
        val hasMrp = when (st) { Style.TALLY, Style.BUSY -> chance(0.4); Style.ESTIMATE -> chance(0.5); Style.VYAPAR -> chance(0.3); else -> true }
        val hasDisc = when (st) { Style.CRYSTAL, Style.EBASE, Style.SCHEME -> false; Style.ESTIMATE -> !whole && chance(0.3); else -> chance(0.7) }
        val sameDisc = chance(0.6)
        val billDisc = st == Style.CRYSTAL || (st == Style.ESTIMATE && chance(0.5))
        val n = rnd.nextInt(3, if (chance(0.3)) 45 else 22)
        val discChoices = listOf("2", "5", "7.45", "10", "12.5", "15", "20", "3")
        val oneDisc = BigDecimal(pick(discChoices))
        val sizeInDesc = st in setOf(Style.TALLY, Style.BUSY, Style.VYAPAR, Style.EBASE, Style.MARG) || chance(0.4)
        val sizeStyle = rnd.nextInt(7)
        fun sizeText(): String { val a = rnd.nextInt(1, 9); val b = a + rnd.nextInt(2, 5)
            return when (sizeStyle) { 0 -> "${a}X$b"; 1 -> "${a}x$b"; 2 -> "$a-$b"; 3 -> "$a/$b"; 4 -> "$a*$b"; 5 -> "${a + 34}-${b + 34}"; else -> "${a}X1${rnd.nextInt(0, 3)}" } }
        val colourInDesc = sizeInDesc || chance(0.5)
        val multiline = chance(0.25) && st in setOf(Style.TALLY, Style.BUSY, Style.SIMPLE, Style.MARG)
        val qtySuffix = when (st) { Style.TALLY -> pick(listOf(" Prs", " PRS", " Pairs", " Pair", "")); Style.BUSY, Style.SIMPLE -> pick(listOf("", "", " Pair", ".00")); else -> "" }

        val matrixSizes = pick(listOf(listOf("6", "7", "8", "9", "10"), listOf("5", "6", "7", "8", "9"), listOf("1", "2", "3", "4", "5"), listOf("40", "41", "42", "43", "44", "45"), listOf("6", "7", "8", "9", "10", "11")))
        val mrpInDesc = st == Style.SIMPLE && chance(0.3)
        val lines = (1..n).map { k ->
            val brand = pick(BRANDS); val code = when (rnd.nextInt(5)) { 0 -> "${rnd.nextInt(100, 9999)}"; 1 -> "${pick(listOf("SM", "FL", "BH", "W", "PU", "TG", "DX"))}-${rnd.nextInt(10, 999)}"
                2 -> "${pick(listOf("SM", "FL", "BH", "W", "PU"))} ${rnd.nextInt(10, 999)}"; 3 -> "${rnd.nextInt(10, 99)}-${rnd.nextInt(100, 999)}"; else -> "${rnd.nextInt(1000, 99999)}" }
            val article = listOf(brand, code, pick(GENDER)).filter { it.isNotEmpty() }.joinToString(" ")
            val mrpV = BigDecimal(pick(listOf(149, 199, 249, 299, 349, 399, 449, 499, 549, 599, 649, 699, 799, 899, 999, 1099, 1299, 1499, 1749, 1899, 1999, 2499))).setScale(2)
            val qty = if (st == Style.CRYSTAL || chance(0.5)) pick(listOf(6, 12, 12, 18, 24, 8, 10, 36)) else rnd.nextInt(1, 40)
            val cartons = maxOf(1, qty / pick(listOf(6, 12)))
            val discPct = if (!hasDisc) null else if (sameDisc) oneDisc else BigDecimal(pick(discChoices))
            // Rate such that the shop sits 30–55 % below MRP.
            val sit = rnd.nextDouble(0.30, 0.55)
            val landed = mrpV.toDouble() * (1 - sit)
            val dp = discPct?.toDouble() ?: 0.0
            val rate = (if (noGst) bd(landed / (1 - dp / 100)) else bd(landed / (1 + gst.toDouble() / 100) / (1 - dp / 100))).let { if (whole) it.setScale(0, RoundingMode.HALF_UP).setScale(2) else it }
            val gross = r2(rate * BigDecimal(qty))
            val dAmt = discPct?.let { r2(gross * it / BigDecimal(100)) } ?: BigDecimal.ZERO.setScale(2)
            val taxable = gross - dAmt
            val tax = if (noGst) BigDecimal.ZERO.setScale(2) else r2(taxable * gst / BigDecimal(100))
            val rateGst = if (st == Style.EBASE) r2(rate * (BigDecimal.ONE + gst.movePointLeft(2))).setScale(0, RoundingMode.HALF_UP).setScale(2) else null
            if (rateGst != null) {
                val withG = rateGst * BigDecimal(qty)
                val tx = r2(withG.divide(BigDecimal.ONE + gst.movePointLeft(2), 10, RoundingMode.HALF_UP))
                TLine(k, article, pick(COLOURS), sizeText(), pick(HSNS), qty, cartons, mrpV, null, r2(rateGst.divide(BigDecimal.ONE + gst.movePointLeft(2), 10, RoundingMode.HALF_UP)),
                    null, BigDecimal.ZERO, withG, tx, gst, withG - tx, withG, rateGst)
            } else if (st == Style.SCHEME) {
                val d1 = BigDecimal(pick(listOf("5", "7", "10", "12", "15"))); val d2 = BigDecimal(pick(listOf("2", "3", "4", "5")))
                val r = bd(landed / (1 + gst.toDouble() / 100) / (1 - d1.toDouble() / 100) / (1 - d2.toDouble() / 100))
                val g = r2(r * BigDecimal(qty)); val a1 = r2(g * d1 / BigDecimal(100)); val a2 = r2((g - a1) * d2 / BigDecimal(100))
                val tx = g - a1 - a2; val tax = r2(tx * gst / BigDecimal(100))
                TLine(k, article, pick(COLOURS), sizeText(), pick(HSNS), qty, cartons, mrpV, null, r, d1, a1 + a2, g, tx, gst, tax, tx + tax, null, d2, a1, a2)
            } else if (st == Style.MATRIX) {
                val sizes = matrixSizes.size
                val per = IntArray(sizes); repeat(qty) { per[rnd.nextInt(sizes)]++ }
                TLine(k, article, pick(COLOURS), "", pick(HSNS), qty, cartons, mrpV, null, rate, discPct, dAmt, gross, taxable, gst, tax, taxable + tax, null, perSize = per.toList())
            } else TLine(k, article, pick(COLOURS), sizeText(), pick(HSNS), qty, cartons, if (hasMrp) mrpV else null,
                if (st == Style.CRYSTAL) mrpV - BigDecimal(pick(listOf(50, 100, 114, 154))) else null, rate, discPct, dAmt, gross, taxable, gst, tax, taxable + tax, null)
        }
        val inclusive = st == Style.EBASE || st == Style.VYAPAR
        features += st.name
        if (hasMrp) features += "mrp" else features += "no-mrp"
        if (hasDisc) features += if (sameDisc) "disc-same" else "disc-mixed"
        if (sizeInDesc) features += "size-in-desc" else features += "size-col"
        if (multiline) features += "multiline"
        if (interState) features += "igst"
        if (n > 26) features += "two-pages"

        if (mrpInDesc) features += "mrp-in-desc"
        if (whole) features += "whole-rupees"
        fun desc(l: TLine) = buildString {
            append(l.article)
            if (!multiline) { if (colourInDesc) append(" ").append(l.colour); if (sizeInDesc && l.size.isNotEmpty()) append(" ").append(l.size) }
            if (mrpInDesc) append(" MRP ").append(l.mrp!!.setScale(0).toPlainString())
        }
        fun q(l: TLine) = "${l.qty}$qtySuffix"
        val sumQty = lines.sumOf { it.qty }
        val sumTaxable = lines.fold(BigDecimal.ZERO) { a, l -> a + l.taxable }
        val sumTax = lines.fold(BigDecimal.ZERO) { a, l -> a + l.tax }
        val sumGross = lines.fold(BigDecimal.ZERO) { a, l -> a + l.gross }
        val sumDisc = lines.fold(BigDecimal.ZERO) { a, l -> a + l.discAmt }
        val sumWith = lines.fold(BigDecimal.ZERO) { a, l -> a + l.withGst }
        fun pct(x: BigDecimal) = x.stripTrailingZeros().toPlainString()

        val cols = mutableListOf<C>()
        val serial = st != Style.BRAND || chance(0.5)
        when (st) {
            Style.TALLY -> {
                cols += C(listOf(pick(listOf("Sl", "SI", "S")), "No."), true, { "${it.sr}" })
                cols += C(listOf("Description of Goods"), false, ::desc)
                cols += C(listOf("HSN/SAC"), false, { it.hsn })
                if (chance(0.5)) cols += C(listOf("GST", "Rate"), true, { pct(it.gstPct) + " %" })
                if (hasMrp) cols += C(listOf("MRP/", "Marginal"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Quantity"), true, ::q, "$sumQty$qtySuffix")
                cols += C(listOf("Rate"), true, { fmt(it.rate) })
                cols += C(listOf("per"), false, { pick(listOf("Prs", "Pair", "PRS", "NOS")) })
                if (hasDisc) cols += C(listOf("Disc. %"), true, { pct(it.discPct!!) + " %" })
                cols += C(listOf("Amount"), true, { fmt(it.taxable) })
            }
            Style.BUSY -> {
                cols += C(listOf("S.N."), true, { "${it.sr}." })
                cols += C(listOf("Description of Goods"), false, ::desc)
                cols += C(listOf("HSN/SAC", "Code"), false, { it.hsn })
                if (hasMrp) cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Qty."), true, ::q, "$sumQty")
                cols += C(listOf("Unit"), false, { pick(listOf("Pair", "Prs.", "PRS")) })
                cols += C(listOf("Price"), true, { fmt(it.rate) })
                if (hasDisc) cols += C(listOf("Disc.%"), true, { pct(it.discPct!!) })
                cols += C(listOf("Amount(Rs.)"), true, { fmt(it.taxable) }, fmt(sumTaxable))
            }
            Style.MARG -> {
                cols += C(listOf("Sr"), true, { "${it.sr}" })
                cols += C(listOf("Product"), false, ::desc)
                cols += C(listOf("HSN"), false, { it.hsn })
                cols += C(listOf("Qty"), true, { "${it.qty}" }, "$sumQty")
                cols += C(listOf("Free"), true, { "0" })
                cols += C(listOf("M.R.P."), true, { fmt(it.mrp!!) })
                cols += C(listOf("Rate"), true, { fmt(it.rate) })
                if (hasDisc) cols += C(listOf("Disc%"), true, { pct(it.discPct!!) })
                cols += C(listOf("GST%"), true, { pct(it.gstPct) })
                cols += C(listOf("Amount"), true, { fmt(it.taxable) }, fmt(sumTaxable))
            }
            Style.VYAPAR -> {
                cols += C(listOf("#"), true, { "${it.sr}" })
                cols += C(listOf("Item name"), false, ::desc)
                cols += C(listOf("HSN/ SAC"), false, { it.hsn })
                if (hasMrp) cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Quantity"), true, { "${it.qty}" }, "$sumQty")
                cols += C(listOf("Unit"), false, { "Pair" })
                cols += C(listOf("Price/ Unit"), true, { "₹ " + fmt(it.rate) })
                if (hasDisc) cols += C(listOf("Discount"), true, { "₹ " + fmt(it.discAmt) + " (" + pct(it.discPct!!) + "%)" }, "₹ " + fmt(sumDisc))
                cols += C(listOf("GST"), true, { "₹ " + fmt(it.tax) + " (" + pct(it.gstPct) + "%)" }, "₹ " + fmt(sumTax))
                cols += C(listOf("Amount"), true, { "₹ " + fmt(it.withGst) }, "₹ " + fmt(sumWith))
            }
            Style.BRAND -> {
                if (serial) cols += C(listOf("S.No"), true, { "${it.sr}" })
                cols += C(listOf(pick(listOf("Article", "Article No", "Art. No.", "Design No"))), false, { it.article })
                cols += C(listOf(pick(listOf("Colour", "Color", "Shade"))), false, { it.colour })
                cols += C(listOf("Size"), false, { it.size })
                cols += C(listOf("HSN"), false, { it.hsn })
                if (chance(0.5)) cols += C(listOf("Ctn"), true, { "${it.cartons}" }, "${lines.sumOf { it.cartons }}")
                cols += C(listOf("Pairs"), true, { "${it.qty}" }, "$sumQty")
                cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Rate"), true, { fmt(it.rate) })
                if (hasDisc) {
                    cols += C(listOf("Gross", "Amt"), true, { fmt(it.gross) }, fmt(sumGross))
                    cols += C(listOf("Disc", "%"), true, { pct(it.discPct!!) })
                    cols += C(listOf("Disc", "Amt"), true, { fmt(it.discAmt) }, fmt(sumDisc))
                }
                cols += C(listOf("Taxable", "Value"), true, { fmt(it.taxable) }, fmt(sumTaxable))
                if (interState) {
                    cols += C(listOf("IGST", "%"), true, { pct(it.gstPct) })
                    cols += C(listOf("IGST", "Amt"), true, { fmt(it.tax) }, fmt(sumTax))
                } else {
                    val half = { l: TLine -> r2(l.tax.divide(BigDecimal(2), 10, RoundingMode.HALF_UP)) }
                    cols += C(listOf("CGST", "%"), true, { pct(it.gstPct.divide(BigDecimal(2))) })
                    cols += C(listOf("CGST", "Amt"), true, { fmt(half(it)) }, fmt(lines.fold(BigDecimal.ZERO) { a, l -> a + half(l) }))
                    cols += C(listOf("SGST", "%"), true, { pct(it.gstPct.divide(BigDecimal(2))) })
                    cols += C(listOf("SGST", "Amt"), true, { fmt(it.tax - half(it)) }, fmt(lines.fold(BigDecimal.ZERO) { a, l -> a + l.tax - half(l) }))
                }
                cols += C(listOf("Total"), true, { fmt(it.withGst) }, fmt(sumWith))
            }
            Style.CRYSTAL -> {
                cols += C(listOf("S.No."), true, { "${it.sr}" })
                cols += C(listOf("Description of Goods"), false, ::desc)
                cols += C(listOf("HSN/SAC"), false, { it.hsn })
                cols += C(listOf("Old MRP", "Per Unit"), true, { fmt(it.oldMrp!!) })
                cols += C(listOf("New MRP", "Per Unit"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Qty In", "C/N"), true, { "${it.cartons}" }, "${lines.sumOf { it.cartons }}")
                cols += C(listOf("Units In", "Pairs"), true, { "${it.qty}" }, "$sumQty")
                cols += C(listOf("Rate/", "Unit"), true, { fmt(it.rate) })
                cols += C(listOf("VALUE", "(IN RS.)"), true, { fmt(it.taxable) }, fmt(sumTaxable))
            }
            Style.EBASE -> {
                cols += C(listOf("Sr.", "No."), true, { "${it.sr}" })
                cols += C(listOf("Description of Goods / Services"), false, ::desc)
                cols += C(listOf("HSN/", "SAC"), false, { it.hsn })
                cols += C(listOf("Quantity"), true, { "${it.qty} Pairs" }, "$sumQty")
                cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Rate"), true, { fmt(it.rateGst!!) })
                cols += C(listOf("Net Rate"), true, { fmt(it.rate) })
                cols += C(listOf("GST"), false, { pct(it.gstPct) + "%" })
                cols += C(listOf("Amount"), true, { fmt(it.withGst) }, fmt(sumWith))
            }
            Style.ESTIMATE -> {
                if (serial) cols += C(listOf(pick(listOf("Sr.", "No.", "S.No."))), true, { "${it.sr}" })
                cols += C(listOf(pick(listOf("Particulars", "Item", "Article", "Description"))), false, ::desc)
                if (!sizeInDesc) cols += C(listOf("Size"), false, { it.size })
                cols += C(listOf(pick(listOf("Qty", "Pairs", "Prs"))), true, { "${it.qty}" }, "$sumQty")
                if (hasMrp) cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
                cols += C(listOf("Rate"), true, { fmt(it.rate) })
                if (hasDisc) cols += C(listOf("Disc %"), true, { pct(it.discPct!!) })
                cols += C(listOf("Amount"), true, { fmt(it.taxable) }, fmt(sumTaxable))
            }
            Style.MATRIX, Style.SCHEME -> {}
            Style.SIMPLE -> {
                cols += C(listOf(pick(listOf("Sr", "Sr.No", "S.No."))), true, { "${it.sr}" })
                cols += C(listOf(pick(listOf("Article", "Item Description", "Particulars", "Product Name"))), false, ::desc)
                if (!colourInDesc) cols += C(listOf("Colour"), false, { it.colour })
                if (!sizeInDesc) cols += C(listOf("Size"), false, { it.size })
                cols += C(listOf("HSN"), false, { it.hsn })
                if (chance(0.5)) cols += C(listOf("GST%"), true, { pct(it.gstPct) })
                if (!mrpInDesc) cols += C(listOf(pick(listOf("MRP", "M.R.P.", "MRP/Pair"))), true, { fmt(it.mrp!!) })
                cols += C(listOf(pick(listOf("Pairs", "Qty", "Qty (Prs)", "Pcs", "Nos"))), true, ::q, "$sumQty")
                cols += C(listOf(pick(listOf("Rate", "Rate/Pair", "Price"))), true, { fmt(it.rate) })
                if (hasDisc) cols += C(listOf(pick(listOf("Disc%", "Disc %", "Dis%", "CD%"))), true, { pct(it.discPct!!) })
                cols += C(listOf(pick(listOf("Amount", "Amount Rs.", "Net Amt", "Taxable Value"))), true, { fmt(it.taxable) }, fmt(sumTaxable))
            }
        }

        if (st == Style.MATRIX) {
            if (chance(0.6)) cols += C(listOf("Sr"), true, { "${it.sr}" })
            cols += C(listOf(pick(listOf("Article", "Art No", "Design"))), false, { it.article })
            cols += C(listOf(pick(listOf("Colour", "Color"))), false, { it.colour })
            matrixSizes.forEachIndexed { si, sz -> cols += C(listOf(sz), true, { l -> l.perSize[si].takeIf { it > 0 }?.toString() ?: "" }) }
            cols += C(listOf(pick(listOf("Pairs", "Total Prs", "Qty"))), true, { "${it.qty}" }, "$sumQty")
            cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
            cols += C(listOf("Rate"), true, { fmt(it.rate) })
            if (hasDisc) cols += C(listOf("Disc%"), true, { pct(it.discPct!!) })
            cols += C(listOf("Amount"), true, { fmt(it.taxable) }, fmt(sumTaxable))
            features += "size-matrix"
        }
        if (st == Style.SCHEME) {
            cols += C(listOf("Sr."), true, { "${it.sr}" })
            cols += C(listOf("Article"), false, ::desc)
            cols += C(listOf("HSN"), false, { it.hsn })
            cols += C(listOf("Pairs"), true, { "${it.qty}" }, "$sumQty")
            cols += C(listOf("MRP"), true, { fmt(it.mrp!!) })
            cols += C(listOf("Rate"), true, { fmt(it.rate) })
            cols += C(listOf("Gross", "Amount"), true, { fmt(it.gross) }, fmt(sumGross))
            val pctHeads = pick(listOf(listOf("TD%", "SD%"), listOf("Disc1%", "Disc2%"), listOf("Trade", "Sch."), listOf("CD%", "Sch%")))
            cols += C(listOf(pctHeads[0]), true, { pct(it.discPct!!) })
            if (chance(0.5)) cols += C(listOf(pctHeads[1]), true, { pct(it.disc2Pct!!) })
            else { cols += C(listOf("Sch", "Disc"), true, { fmt(it.disc2Amt) }); features += "scheme-amount" }
            cols += C(listOf(pick(listOf("Net Amt", "Taxable", "Net Value", "Amount"))), true, { fmt(it.taxable) }, fmt(sumTaxable))
            features += "two-discounts"
        }

        // ---- widths and places
        val h = if (chance(0.5)) 20 else rnd.nextInt(16, 26)
        val ch = h * 0.5
        var x = 20
        for (c in cols) {
            val chars = maxOf(c.head.maxOf { it.length }, lines.maxOf { c.cell(it).length }, c.total?.length ?: 0)
            c.w = ((chars + 2) * ch).toInt(); c.x = x; x += c.w
        }
        val pageW = maxOf(x + 30, (60 * ch).toInt())
        val slope = if (chance(0.6)) rnd.nextDouble(-0.012, 0.012) else 0.0
        val jitter = if (chance(0.5)) rnd.nextInt(0, 3) else 0
        val rowH = (h * rnd.nextDouble(1.35, 1.8)).toInt()
        val supplier = pick(SUPPLIERS)
        val invNo = when (rnd.nextInt(4)) { 0 -> "${rnd.nextInt(1, 999)}"; 1 -> "GST/${rnd.nextInt(1, 999)}/26-27"; 2 -> "${pick(listOf("A", "R", "SI", "V"))}-${rnd.nextInt(100, 9999)}"; else -> "26-27/${rnd.nextInt(1, 999)}" }
        val pages = mutableListOf<OcrPage>()
        val perPage = if (multiline) 14 else 26
        val chunks = lines.chunked(perPage)
        val amountCol = cols.last()
        val descCol = cols.first { !it.right }

        fun head(ink: Ink, first: Boolean): Int {
            var y = 30
            val title = when (st) { Style.ESTIMATE -> pick(listOf("ESTIMATE", "ORDER", "ORDER FORM", "QUOTATION", "CHALLAN")); else -> pick(listOf("TAX INVOICE", "Tax Invoice", "GST INVOICE", "INVOICE")) }
            ink.at(pageW / 2 - 60, y, title); y += rowH + 4
            ink.at(pageW / 2 - supplier.length * 8, y, supplier, (h * 1.5).toInt()); y += (h * 1.5).toInt() + 10
            ink.at(pageW / 2 - 200, y, "${pick(listOf("Shop No. 12", "Plot No. 45", "B-6, MIDC", "Gala No. 3"))}, ${pick(listOf("Mumbai", "Pune", "Agra", "Delhi", "Kolkata", "Kanpur", "Solapur"))}"); y += rowH
            if (!noGst) { ink.at(pageW / 2 - 150, y, "GSTIN : 27ABCDE${rnd.nextInt(1000, 9999)}F1Z5"); y += rowH }
            ink.at(pageW / 2 - 150, y, "Mob : 98${rnd.nextInt(10000000, 99999999)}"); y += rowH + 8
            if (!first && chance(0.5)) { ink.at(20, y, "Invoice No. : $invNo"); y += rowH + 4 }
            else {
                val label = if (st == Style.ESTIMATE) pick(listOf("Estimate No. :", "Order No. :", "Challan No. :", "Bill No :", "No. :")) else pick(listOf("Invoice No. :", "Bill No :", "Inv. No.", "Invoice # ", "Voucher No.", "Invoice No"))
                ink.at(20, y, pick(listOf("Buyer :", "Bill To :", "Party :", "M/s.", "To,"))); ink.at(pageW - 420, y, "$label $invNo"); y += rowH
                ink.at(20, y, "SHIVTEJ FOOTWEAR AKLUJ"); ink.at(pageW - 420, y, "${pick(listOf("Date :", "Dated :", "Date"))} ${rnd.nextInt(1, 28)}/0${rnd.nextInt(1, 9)}/2026"); y += rowH
                ink.at(20, y, "SHANKARNAGAR ROAD, AKLUJ, Dist. Solapur"); y += rowH
                if (!noGst) { ink.at(20, y, "GSTIN : 27ECIPS0222J1ZI"); y += rowH }
                y += 10
            }
            // headings
            val two = cols.any { it.head.size > 1 }
            for (row in 0 until (if (two) 2 else 1)) {
                for (c in cols) {
                    val t = c.head.getOrNull(row) ?: continue
                    if (c.right) ink.right(c.x + c.w - (ch).toInt(), y, t) else ink.at(c.x + (ch / 2).toInt(), y, t)
                }
                y += (h * 1.2).toInt()
            }
            return y + (h * 0.6).toInt()
        }

        for ((pi, chunk) in chunks.withIndex()) {
            val ink = Ink(rnd, slope, h, jitter)
            var y = head(ink, pi == 0)
            for (l in chunk) {
                for (c in cols) {
                    val t = c.cell(l); if (t.isEmpty()) continue
                    if (c.right) ink.right(c.x + c.w - ch.toInt(), y, t) else ink.at(c.x + (ch / 2).toInt(), y, t)
                }
                y += rowH
                if (multiline) { ink.at(descCol.x + (ch / 2).toInt() + (ch * 2).toInt(), y, "${l.colour} ${l.size}"); y += rowH }
            }
            val last = pi == chunks.lastIndex
            if (!last) {
                ink.at(20, y + rowH, pick(listOf("Continued...", "Continued on Next Page....", "c/f", "Page ${pi + 1} of ${chunks.size}")))
                pages += OcrPage(pageW, y + rowH * 4, ink.words.shuffled(rnd)); continue
            }
            y = footer(ink, st, cols, y, rowH, ch, lines, sumTaxable, sumTax, sumWith, interState, billDisc, fmt, rnd, features).let { (yy, g) -> grandOut = g; yy }
            pages += OcrPage(pageW, y + rowH * 3, ink.words.shuffled(rnd))
        }
        if (noise > 0) features += "noise"
        return Bill(seed, st, pages.map { noisy(it, noise, rnd) }, lines, grandOut!!, invNo, supplier, features, inclusive, hasMrp)
    }

    private var grandOut: BigDecimal? = null

    /** The rows under the items; returns the y after them and the bill's grand total. */
    private fun footer(
        ink: Ink, st: Style, cols: List<C>, y0: Int, rowH: Int, ch: Double, lines: List<TLine>, sumTaxable: BigDecimal, sumTax: BigDecimal,
        sumWith: BigDecimal, inter: Boolean, billDisc: Boolean, fmt: (BigDecimal) -> String, rnd: Random, features: MutableList<String>,
    ): Pair<Int, BigDecimal> {
        var y = y0 + (rowH * 0.3).toInt()
        val amount = cols.last()
        val ar = amount.x + amount.w - ch.toInt()
        val labelX = cols.first { !it.right }.x + (ch / 2).toInt()
        fun totalRow(label: String?) {
            if (label != null) ink.at(labelX, y, label)
            for (c in cols) c.total?.let { if (c.right) ink.right(c.x + c.w - ch.toInt(), y, it) else ink.at(c.x, y, it) }
            y += rowH
        }
        fun lv(label: String, v: String, lx: Int = labelX) { ink.at(lx, y, label); ink.right(ar, y, v); y += rowH }
        val half = r2(sumTax.divide(BigDecimal(2), 10, RoundingMode.HALF_UP))
        val g = lines.first().gstPct
        val hg = g.divide(BigDecimal(2)).stripTrailingZeros().toPlainString()
        val gs = g.stripTrailingZeros().toPlainString()
        fun round(x: BigDecimal) = x.setScale(0, RoundingMode.HALF_UP).setScale(2)
        val rightX = cols[maxOf(0, cols.size - 4)].x
        when (st) {
            Style.TALLY -> {
                y += rowH / 2
                if (inter) lv("IGST", fmt(sumTax), cols[1].x + (cols[1].w * 0.6).toInt())
                else { lv(rnd.let { if (it.nextBoolean()) "CGST" else "Output CGST @$hg%" }, fmt(half), cols[1].x + (cols[1].w * 0.5).toInt()); lv(if (rnd.nextBoolean()) "SGST" else "Output SGST @$hg%", fmt(sumTax - half), cols[1].x + (cols[1].w * 0.5).toInt()) }
                val total = sumTaxable + sumTax; val gr = round(total)
                if (gr.compareTo(total) != 0) lv("Round Off", fmt(gr - total), cols[1].x + (cols[1].w * 0.5).toInt())
                ink.at(labelX + (cols[1].w * 0.6).toInt(), y, "Total")
                cols.firstOrNull { it.head.first() == "Quantity" }?.let { c -> ink.right(c.x + c.w - ch.toInt(), y, c.total!!) }
                ink.right(ar, y, "₹ " + fmt(gr)); y += rowH
                ink.at(20, y, "Amount Chargeable (in words)"); ink.right(ar, y, "E. & O.E"); y += rowH
                ink.at(20, y, "INR ${words(gr)} Only"); y += rowH + 6
                val a = 300; val b = a + 220
                ink.at(20, y, "HSN/SAC"); ink.at(a, y, "Taxable"); if (inter) ink.at(b, y, "Integrated Tax") else { ink.at(b, y, "Central Tax"); ink.at(b + 260, y, "State Tax") }; ink.at(b + 520, y, "Total"); y += rowH
                ink.at(a, y, "Value"); ink.at(b, y, "Rate"); ink.at(b + 110, y, "Amount"); if (!inter) { ink.at(b + 260, y, "Rate"); ink.at(b + 370, y, "Amount") }; ink.at(b + 520, y, "Tax Amount"); y += rowH
                ink.at(20, y, lines.first().hsn); ink.right(a + 160, y, fmt(sumTaxable)); ink.at(b, y, if (inter) "$gs%" else "$hg%"); ink.right(b + 220, y, fmt(if (inter) sumTax else half))
                if (!inter) { ink.at(b + 260, y, "$hg%"); ink.right(b + 480, y, fmt(sumTax - half)) }; ink.right(b + 640, y, fmt(sumTax)); y += rowH
                ink.at(20, y, "Total"); ink.right(a + 160, y, fmt(sumTaxable)); ink.right(b + 220, y, fmt(if (inter) sumTax else half)); if (!inter) ink.right(b + 480, y, fmt(sumTax - half)); ink.right(b + 640, y, fmt(sumTax)); y += rowH
                ink.at(20, y, "Tax Amount (in words) : INR ${words(sumTax)} Only"); y += rowH
                ink.at(20, y, "Declaration"); ink.at(ar - 400, y, "for SUPPLIER NAME"); y += rowH
                ink.at(ar - 300, y, "Authorised Signatory"); y += rowH
                return y to gr
            }
            Style.BUSY, Style.SIMPLE, Style.MARG, Style.MATRIX, Style.SCHEME -> {
                totalRow(if (rnd.nextBoolean()) "Total" else "Sub Total")
                var total = sumTaxable + sumTax
                if (inter) lv("Add : IGST @ $gs %", fmt(sumTax), rightX)
                else { lv(if (st == Style.MARG) "CGST $hg% on ${fmt(sumTaxable)}" else "Add : CGST @ $hg %", fmt(half), rightX); lv(if (st == Style.MARG) "SGST $hg% on ${fmt(sumTaxable)}" else "Add : SGST @ $hg %", fmt(sumTax - half), rightX) }
                if (rnd.nextDouble() < 0.3) { val fr = BigDecimal(rnd.nextInt(50, 400)).setScale(2); lv("Freight", fmt(fr), rightX); total += fr; features += "freight" }
                val gr = round(total)
                if (gr.compareTo(total) != 0) lv(if (gr < total) "Less : Rounded Off (-)" else "Add : Rounded Off (+)", fmt((gr - total).abs()), rightX)
                lv(when (st) { Style.MARG -> "NET AMOUNT"; Style.SIMPLE -> "Grand Total"; else -> "Grand Total" }, fmt(gr), rightX)
                ink.at(20, y, "Rs. ${words(gr)} Only"); y += rowH
                ink.at(20, y, "Bank : ${listOf("HDFC BANK", "SBI", "BANK OF MAHARASHTRA").random(rnd)} A/c No. : 5010023${rnd.nextInt(10000, 99999)} IFSC : HDFC000${rnd.nextInt(1000, 9999)}"); y += rowH
                ink.at(20, y, "Terms & Conditions : Goods once sold will not be taken back."); ink.at(ar - 300, y, "Authorised Signatory"); y += rowH
                return y to gr
            }
            Style.VYAPAR -> {
                totalRow("Total")
                val gr = round(sumWith)
                lv("Sub Total", "₹ " + fmt(sumWith), rightX)
                if (gr.compareTo(sumWith) != 0) lv("Round off", "₹ " + fmt(gr - sumWith), rightX)
                lv("Total", "₹ " + fmt(gr), rightX)
                ink.at(20, y, "Invoice Amount In Words"); y += rowH
                ink.at(20, y, "${words(gr)} Rupees only"); y += rowH
                lv("Received", "₹ 0.00", rightX); lv("Balance", "₹ " + fmt(gr), rightX)
                ink.at(20, y, "Terms and conditions"); ink.at(ar - 300, y, "Authorized Signatory"); y += rowH
                return y to gr
            }
            Style.BRAND -> {
                totalRow("TOTAL")
                y += rowH / 2
                lv("Taxable Amount", fmt(sumTaxable), rightX)
                if (inter) lv("IGST @ $gs%", fmt(sumTax), rightX) else { lv("CGST @ $hg%", fmt(half), rightX); lv("SGST @ $hg%", fmt(sumTax - half), rightX) }
                val gr = round(sumWith)
                if (gr.compareTo(sumWith) != 0) lv("Round Off", fmt(gr - sumWith), rightX)
                lv("Net Amount", fmt(gr), rightX)
                ink.at(20, y, "Rupees ${words(gr)} Only"); y += rowH
                return y to gr
            }
            Style.CRYSTAL -> {
                totalRow("T O T A L")
                val c = lines.sumOf { it.cartons }
                var run = sumTaxable
                val perCtn = BigDecimal(listOf(100, 150, 180).random(rnd)).setScale(2)
                val d1 = perCtn * BigDecimal(c); run -= d1
                lv("LESS : DISCOUNT OF RS.${perCtn.toPlainString()} PER C/N", fmt(d1)); ink.right(ar, y, fmt(run)); y += rowH
                val p2 = BigDecimal("2.00"); val d2 = r2(run * p2 / BigDecimal(100)); run -= d2
                lv("LESS : 2.00 % SPECIAL DISCOUNT", fmt(d2)); ink.right(ar, y, fmt(run)); y += rowH
                val ins = r2(run * BigDecimal("0.001")); run += ins
                lv("ADD : 0.10 % TRANSIT INSURANCE", fmt(ins)); ink.right(ar, y, fmt(run)); y += rowH
                val tax = r2(run * lines.first().gstPct / BigDecimal(100))
                lv("ADD : IGST @ $gs.00%", fmt(tax)); val tot = run + tax; ink.right(ar, y, fmt(tot)); y += rowH
                val gr = round(tot)
                if (gr.compareTo(tot) != 0) lv("ADD : ROUND OFF", fmt(gr - tot))
                lv("GRAND TOTAL", fmt(gr))
                ink.at(20, y, "Total Invoice Value (In Words) ${words(gr).uppercase()} ONLY"); y += rowH
                ink.at(20, y, "HSN / SAC"); ink.at(300, y, "Units In"); ink.at(450, y, "Taxable"); ink.at(600, y, "CGST"); ink.at(700, y, "SGST"); ink.at(800, y, "IGST"); ink.at(900, y, "Total Tax"); y += rowH
                ink.at(300, y, "Pairs"); ink.at(450, y, "Value"); ink.at(600, y, "Amount"); ink.at(700, y, "Amount"); ink.at(800, y, "Amount"); y += rowH
                ink.at(20, y, "TOTAL"); ink.right(380, y, "${lines.sumOf { it.qty }}"); ink.right(580, y, fmt(run)); ink.right(680, y, "0.00"); ink.right(780, y, "0.00"); ink.right(880, y, fmt(tax)); ink.right(1000, y, fmt(tax)); y += rowH
                features += "bill-less-add"
                return y to gr
            }
            Style.EBASE -> {
                totalRow(null)
                ink.at(60, y, "Items: ${lines.size}"); y += rowH
                val gr = round(sumWith)
                ink.at(20, y, "Total Amount in Words : ${words(gr)} Only"); y += rowH
                val a = 250
                ink.at(a, y, "Taxable Value"); ink.at(a + 170, y, "SGST"); ink.at(a + 300, y, "SGST Amt"); ink.at(a + 430, y, "CGST"); ink.at(a + 560, y, "CGST Amt"); y += rowH
                ink.at(20, y, lines.first().hsn); ink.right(a + 150, y, fmt(sumTaxable)); ink.at(a + 170, y, "$hg%"); ink.right(a + 400, y, fmt(half)); ink.at(a + 430, y, "$hg%"); ink.right(a + 660, y, fmt(sumWith - sumTaxable - half)); y += rowH
                ink.at(20, y, "Total"); ink.right(a + 150, y, fmt(sumTaxable)); ink.right(a + 400, y, fmt(half)); ink.right(a + 660, y, fmt(sumWith - sumTaxable - half)); y += rowH
                return y to gr
            }
            Style.ESTIMATE -> {
                totalRow("Total")
                var total = sumTaxable
                if (billDisc) {
                    val p = BigDecimal(listOf(2, 3, 5, 10).random(rnd)); val d = r2(total * p / BigDecimal(100)); total -= d
                    lv("Less : Discount ${p.toPlainString()}%", fmt(d), rightX); features += "bill-less"
                }
                val gr = round(total)
                if (gr.compareTo(total) != 0) lv("Round Off", fmt(gr - total), rightX)
                lv(listOf("Net Amount", "Grand Total", "Net Payable").random(rnd), fmt(gr), rightX)
                ink.at(20, y, "Rs. ${words(gr)} Only"); y += rowH
                return y to gr
            }
        }
    }
}
