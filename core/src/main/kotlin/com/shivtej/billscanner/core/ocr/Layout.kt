package com.shivtej.billscanner.core.ocr

import java.math.BigDecimal

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Rectangle on the photo, in pixels. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val cx: Double get() = (l + r) / 2.0
    val cy: Double get() = (t + b) / 2.0
    val h: Int get() = b - t
    val w: Int get() = r - l
}

/** One word the OCR read, and where it is. */
data class OcrWord(val text: String, val box: Box)

/** Everything the OCR read on one photo. */
data class OcrPage(val width: Int, val height: Int, val words: List<OcrWord>)

/** Words on one visual line of the bill, left to right. */
class Row(val words: List<OcrWord>, val y: Double) {
    val text: String get() = words.joinToString(" ") { it.text }

    /** Pieces of the line separated by a wide empty gap (e.g. left box vs right box of the header). */
    fun segments(gap: Double): List<List<OcrWord>> {
        val out = mutableListOf<MutableList<OcrWord>>()
        for (w in words) {
            val last = out.lastOrNull()?.lastOrNull()
            if (last == null || w.box.l - last.box.r > gap) out += mutableListOf(w) else out.last() += w
        }
        return out
    }

    override fun toString() = text
}

/**
 * Puts OCR words back into the lines of the bill.
 *
 * Phone photos are tilted and often bent (the bottom of a page more tilted than the top), so:
 * 1. the overall tilt is measured and taken out ([skew], [straighten]);
 * 2. words are chained to their nearest neighbour on the right that sits on the same line,
 *    which follows a line even when it curves ([lines]).
 */
object Layout {
    fun medianHeight(words: List<OcrWord>): Double =
        words.map { it.box.h }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 20.0 else it[it.size / 2].toDouble() }

    /** Tilt of the photo as dy/dx (0 when it cannot be measured), by projection profile. */
    fun skew(words: List<OcrWord>): Double {
        // At the right tilt, words of one line fall into the same narrow band,
        // so the histogram of straightened y positions is sharpest (largest sum of squares).
        if (words.size < 8) return 0.0
        val mh = medianHeight(words)
        val bin = (mh * 0.5).coerceAtLeast(2.0).toLong()
        fun score(s: Double): Double {
            val counts = HashMap<Long, Int>()
            for (w in words) {
                val k = Math.floorDiv((w.box.cy - s * w.box.cx).toLong(), bin)
                counts[k] = (counts[k] ?: 0) + 1
            }
            return counts.values.sumOf { it.toDouble() * it }
        }
        var best = 0.0; var bestScore = -1.0
        var s = -0.15
        while (s <= 0.1500001) {
            val sc = score(s)
            if (sc > bestScore + 1e-9 || (abs(sc - bestScore) < 1e-9 && abs(s) < abs(best))) { bestScore = sc; best = s }
            s += 0.002
        }
        // Finer search around the best step.
        var f = best - 0.002
        val top = best + 0.002
        while (f <= top) {
            val sc = score(f)
            if (sc > bestScore + 1e-9) { bestScore = sc; best = f }
            f += 0.0004
        }
        return best
    }

    /**
     * Rotates word positions so that lines with tilt [slope] become level. Box height is reduced by the
     * part that came only from the tilt (a tilted long word has a tall bounding box).
     */
    fun straighten(words: List<OcrWord>, slope: Double = skew(words)): List<OcrWord> {
        if (abs(slope) < 1e-6) return words
        val th = atan(slope); val c = cos(th); val s = sin(th)
        return words.map { w ->
            val x = w.box.cx * c + w.box.cy * s
            val y = -w.box.cx * s + w.box.cy * c
            val h = max(w.box.h * 0.4, w.box.h - w.box.w * abs(s))
            val wd = w.box.w.toDouble()
            OcrWord(w.text, Box((x - wd / 2).toInt(), (y - h / 2).toInt(), (x + wd / 2).toInt(), (y + h / 2).toInt()))
        }
    }

    private fun overlap(a: Box, b: Box): Double =
        (min(a.b, b.b) - max(a.t, b.t)).toDouble() / max(1, min(a.h, b.h))

    /** Least-squares slope of the words' centres (dy/dx), or null when the words span too little. */
    private fun slopeOf(ws: List<OcrWord>, mh: Double): Double? {
        if (ws.size < 3) return null
        val span = ws.maxOf { it.box.cx } - ws.minOf { it.box.cx }
        if (span < 5 * mh) return null
        val mx = ws.map { it.box.cx }.average(); val my = ws.map { it.box.cy }.average()
        val vx = ws.sumOf { (it.box.cx - mx) * (it.box.cx - mx) }
        if (vx < 1e-6) return null
        return (ws.sumOf { (it.box.cx - mx) * (it.box.cy - my) } / vx).coerceIn(-0.08, 0.08)
    }

    /** Groups (already straightened) words into visual lines, top to bottom. */
    fun lines(words: List<OcrWord>): List<Row> {
        if (words.isEmpty()) return emptyList()
        val mh = medianHeight(words)
        val ws = words.sortedBy { it.box.l }
        val n = ws.size
        // 1. Short links only: words of one cell or neighbouring columns.
        val maxGap = 4 * mh
        val right = IntArray(n) { -1 }; val rightCost = DoubleArray(n) { Double.MAX_VALUE }
        val left = IntArray(n) { -1 }; val leftCost = DoubleArray(n) { Double.MAX_VALUE }
        for (i in 0 until n) {
            val a = ws[i].box
            for (j in i + 1 until n) {
                val b = ws[j].box
                if (b.l - a.r > maxGap) { if (b.l - a.r > maxGap + 40 * mh) break else continue }
                if (b.cx <= a.r - 0.25 * min(a.w, b.w) || b.l < a.l) continue
                if (overlap(a, b) < 0.55) continue
                val cost = max(0, b.l - a.r) + 4 * abs(a.cy - b.cy)
                if (cost < rightCost[i]) { rightCost[i] = cost; right[i] = j }
                if (cost < leftCost[j]) { leftCost[j] = cost; left[j] = i }
            }
        }
        val next = IntArray(n) { -1 }; val hasPrev = BooleanArray(n)
        for (i in 0 until n) { val j = right[i]; if (j >= 0 && left[j] == i) { next[i] = j; hasPrev[j] = true } }
        val chains = mutableListOf<MutableList<OcrWord>>()
        for (i in 0 until n) if (!hasPrev[i]) {
            val ch = mutableListOf<OcrWord>(); var k = i
            while (k >= 0) { ch += ws[k]; k = next[k] }
            chains += ch
        }
        // 2. How tilted lines still are at each height of the page (a bent page tilts more at one end).
        val samples = chains.mapNotNull { c -> slopeOf(c, mh)?.let { Triple(c.map { it.box.cy }.average(), it, c.last().box.cx - c.first().box.cx) } }
        val field: (Double) -> Double = if (samples.size >= 3) {
            val wsum = samples.sumOf { it.third }
            val my = samples.sumOf { it.first * it.third } / wsum; val ms = samples.sumOf { it.second * it.third } / wsum
            val vy = samples.sumOf { it.third * (it.first - my) * (it.first - my) }
            val b = if (vy < 1e-6) 0.0 else samples.sumOf { it.third * (it.first - my) * (it.second - ms) } / vy
            ({ y: Double -> (ms + b * (y - my)).coerceIn(-0.08, 0.08) })
        } else ({ _: Double -> 0.0 })
        // 3. Join pieces of one line across wide gaps, extending each piece along its own tilt.
        // A piece's own tilt is trusted in proportion to how long it is.
        fun tilt(ws: List<OcrWord>): Double {
            val f = field(ws.map { it.box.cy }.average())
            val own = slopeOf(ws, mh) ?: return f
            val span = ws.last().box.cx - ws.first().box.cx
            val w = span / (span + 20 * mh)
            return w * own + (1 - w) * f
        }
        chains.sortBy { it.first().box.l }
        val rows = mutableListOf<MutableList<OcrWord>>()
        val rowSlope = mutableListOf<Double>()
        for (ch in chains) {
            val first = ch.first().box
            val cs = tilt(ch)
            var bestRow = -1; var bestD = Double.MAX_VALUE
            for ((ri, r) in rows.withIndex()) {
                val last = r.last().box
                if (last.r > first.l + 0.5 * mh) continue
                val rs = rowSlope[ri]
                val mx = (last.cx + first.cx) / 2
                val d = abs((last.cy + rs * (mx - last.cx)) - (first.cy + cs * (mx - first.cx)))
                if (d < 0.6 * mh && d < bestD) { bestD = d; bestRow = ri }
            }
            if (bestRow >= 0) { rows[bestRow].addAll(ch); rowSlope[bestRow] = tilt(rows[bestRow]) }
            else { rows += ch.toMutableList(); rowSlope += tilt(ch) }
        }
        // Two pieces at the same height that do not overlap side by side are one line
        // ("ADD : IGST @ 5.00%" … "5054.87" when a short piece's own tilt sent them apart).
        val built = rows.map { r -> Row(r.sortedBy { it.box.l }, r.map { it.box.cy }.average()) }.sortedBy { it.y }.toMutableList()
        var k = 0
        while (k < built.size - 1) {
            val a = built[k]; val b = built[k + 1]
            val apart = a.words.last().box.r < b.words.first().box.l || b.words.last().box.r < a.words.first().box.l
            if (abs(b.y - a.y) < 0.35 * mh && apart) {
                val ws = (a.words + b.words).sortedBy { it.box.l }
                built[k] = Row(ws, ws.map { it.box.cy }.average()); built.removeAt(k + 1)
            } else k++
        }
        return built
    }

    // ---------------------------------------------------------------- words the OCR glued together

    private val TWO_DECIMALS = Regex("""^(\d{1,3}(?:,\d{2,3})*\.\d{2})(\d{1,3}(?:,\d{2,3})*\.\d{1,3}%?)$""")
    private val HSN_GST = Regex("""^(\d{4,8})(\d{1,2}(?:\.\d{1,2})?%)$""")
    private val UNIT_NUM = Regex("""(?i)^(pairs?|prs?|pcs?|nos?)(\d[\d,.]*%?)$""")
    private val PCT_NUM = Regex("""^(\d{1,2}(?:\.\d{1,2})?%)(\d[\d,]*(?:\.\d{1,2})?)$""")
    private val SR_TEXT = Regex("""^(\d{1,3}\.)([A-Za-z].*)$""")
    /** "5%6X10", "640299905%7X10": a % glued to the size after it. */
    private val PCT_SIZE = Regex("""^(.*\d%)(\d{1,2}[xX×]\d.*)$""")
    /** "%6X10", "%11X13329": the % of the GST column glued in front of the next cell. */
    private val LEAD_PCT = Regex("""^(%)(\d.*)$""")
    private val SIZE_NUM = Regex("""^(\d{1,2})[xX×](\d{1,3})(\d[\d,]*(?:\.\d{1,2})?)?$""")
    private val VALID_NUM = Regex("""^[1-9]\d{0,2}(?:,\d{2,3})*(?:\.\d{1,2})?$|^[1-9]\d*(?:\.\d{1,2})?$""")

    private fun splitBox(w: OcrWord, parts: List<String>): List<OcrWord> {
        val total = parts.sumOf { it.length }.coerceAtLeast(1)
        var x = w.box.l.toDouble()
        return parts.filter { it.isNotEmpty() }.map { p ->
            val wd = w.box.w * p.length.toDouble() / total
            OcrWord(p, Box(x.toInt(), w.box.t, (x + wd).toInt(), w.box.b)).also { x += wd }
        }
    }

    /** "6X101,249" → "6X10" + "1,249" (size range, then MRP): the split whose two parts both make sense. */
    private fun sizeSplit(t: String): List<String>? {
        val m = Regex("""^(\d{1,2}[xX×])(\d+(?:,\d{3})*(?:\.\d{1,2})?)$""").matchEntire(t) ?: return null
        val head = m.groupValues[1]; val rest = m.groupValues[2]
        val options = (1..2).mapNotNull { n ->
            if (rest.length <= n) null else {
                val sz = rest.substring(0, n); val num = rest.substring(n)
                // The number after the size is an MRP: at least two digits ("11X13" is a size, not "11X1" + 3).
                if (VALID_NUM.matches(num) && num.count(Char::isDigit) >= 2 && sz.toInt() in 1..15) Triple(sz, num, n) else null
            }
        }
        if (options.isEmpty()) return null
        val first = head.dropLast(1).toInt()
        // A size range usually goes up (6X10, 11X13); prefer that, then the longer size number.
        val pick = options.sortedWith(compareByDescending<Triple<String, String, Int>> { it.first.toInt() >= first }.thenByDescending { it.third }).first()
        return listOf(head + pick.first, pick.second)
    }

    /**
     * Cleans what the OCR returned: table lines read as "|" are dropped, and words it glued together
     * ("754.299.82", "640299905%", "6X101,249", "pair1,165.71", "33.SS") are split back.
     */
    fun clean(words: List<OcrWord>): List<OcrWord> = words.flatMap { w0 ->
        var t = w0.text.trim()
        if (t.all { it == '|' || it == '!' || it == '[' || it == ']' }) return@flatMap emptyList()
        // Inner pipes are column lines.
        if ('|' in t) {
            val parts = t.split('|').filter { it.isNotBlank() }
            if (parts.isEmpty()) return@flatMap emptyList()
            if (parts.size > 1) return@flatMap clean(splitBox(w0, parts))
            t = parts[0].trim()
        }
        // "1,099.00/prs", "499/-", "753.60/pr": the per-pair mark after an amount is not part of it.
        Regex("""(?i)^(.*\d)\s*/\s*(prs?|pairs?|pcs?|nos?|-)$""").matchEntire(t)?.let { t = it.groupValues[1] }
        val w = OcrWord(t, w0.box)
        // Each split part is cleaned again: "pair1,165.719.82" → "pair" + "1,165.719.82" → "1,165.71" + "9.82".
        fun again(parts: List<String>) = if (parts.size > 1 && parts.all { it.isNotEmpty() }) clean(splitBox(w, parts)) else listOf(w)
        for (re in listOf(PCT_SIZE, TWO_DECIMALS, HSN_GST, UNIT_NUM, PCT_NUM, LEAD_PCT, SR_TEXT))
            re.matchEntire(t)?.let { return@flatMap again(listOf(it.groupValues[1], it.groupValues[2])) }
        sizeSplit(t)?.let { return@flatMap again(it) }
        listOf(w)
    }

    private val CURRENCY = Regex("""(?i)^(₹|rs\.?|inr|`)$""")
    private val PAREN_PCT = Regex("""^\((\d{1,2}(?:\.\d{1,3})?)\s*%\)$""")
    private val PLAIN_NUM = Regex("""^-?\d[\d,]*(\.\d+)?$""")

    /**
     * "₹ 1,129.84" and "392.52 (7.45%)" (Vyapar and other apps): the ₹ sign and the % in brackets are glued to their
     * number, so each cell is one word under its heading.
     */
    private val MONEY2 = Regex("""^\d{1,3}(,\d{2,3})+\.\d{2}$|^\d+\.\d{2}$""")

    /**
     * A number the OCR broke in two with almost no gap ("176.2" "3", "2,881" ".53", "44" "9.00", HSN "6403" "9990"):
     * put back together when the joined text is a proper amount (or an 8-digit HSN) and the pieces are not two amounts.
     */
    fun mendSplitNumbers(words: List<OcrWord>): List<OcrWord> {
        val byLine = words.sortedBy { it.box.l }
        val used = HashSet<Int>()
        val out = ArrayList<OcrWord>(words.size)
        val idx = byLine.indices.toList()
        for (i in idx) {
            if (i in used) continue
            val a = byLine[i]
            var merged: OcrWord? = null
            if (a.text.any(Char::isDigit) && a.text.all { it.isDigit() || it == ',' || it == '.' }) {
                for (j in i + 1 until byLine.size) {
                    val b = byLine[j]
                    if (b.box.l - a.box.r > 0.3 * max(a.box.h, b.box.h)) break
                    if (j in used || b.box.l < a.box.r - 2) continue
                    if (min(a.box.b, b.box.b) - max(a.box.t, b.box.t) < 0.6 * min(a.box.h, b.box.h)) continue
                    if (!b.text.all { it.isDigit() || it == ',' || it == '.' } || b.text.none(Char::isDigit) && b.text != ".") continue
                    val t = a.text + b.text
                    val money = MONEY2.matches(t) && !(MONEY2.matches(a.text) && MONEY2.matches(b.text))
                    val hsn = a.text.all(Char::isDigit) && b.text.all(Char::isDigit) && t.length == 8 && a.text.length >= 2 && b.text.length >= 2 && t.startsWith("6")
                    if (money || hsn) { merged = OcrWord(t, Box(a.box.l, min(a.box.t, b.box.t), b.box.r, max(a.box.b, b.box.b))); used += j }
                    break
                }
            }
            out += merged ?: a
        }
        return out
    }

    fun glue(words0: List<OcrWord>): List<OcrWord> {
        val words = mendSplitNumbers(words0)
        if (words.none { CURRENCY.matches(it.text) || PAREN_PCT.matches(it.text) }) return words
        val gone = HashSet<OcrWord>()
        val out = words.toMutableList()
        fun sameLine(a: OcrWord, b: OcrWord) = min(a.box.b, b.box.b) - max(a.box.t, b.box.t) >= 0.5 * min(a.box.h, b.box.h)
        fun merge(a: OcrWord, b: OcrWord, text: String) = OcrWord(text, Box(min(a.box.l, b.box.l), min(a.box.t, b.box.t), max(a.box.r, b.box.r), max(a.box.b, b.box.b)))
        for (i in out.indices) {
            val w = out[i]
            if (w in gone || !CURRENCY.matches(w.text)) continue
            val n = out.filter { it !== w && it !in gone && sameLine(w, it) && it.box.l >= w.box.r - 2 && it.box.l - w.box.r <= 1.5 * max(w.box.h, it.box.h) }
                .minByOrNull { it.box.l } ?: continue
            if (!PLAIN_NUM.matches(n.text)) continue
            val j = out.indexOf(n)
            out[j] = merge(w, n, (if (w.text == "₹") "₹" else "") + n.text); gone += w
        }
        val mid = out.filterNot { it in gone }.toMutableList()
        val gone2 = HashSet<OcrWord>()
        for (k in mid.indices) {
            val p = mid[k]
            val m = PAREN_PCT.matchEntire(p.text) ?: continue
            val n = mid.filter { it !== p && it !in gone2 && sameLine(p, it) && it.box.r <= p.box.l + 2 && p.box.l - it.box.r <= 1.5 * max(p.box.h, it.box.h) }
                .maxByOrNull { it.box.r } ?: continue
            if (!PLAIN_NUM.matches(n.text.removePrefix("₹"))) continue
            val j = mid.indexOf(n)
            mid[j] = merge(n, p, n.text + "(" + m.groupValues[1] + "%)"); gone2 += p
        }
        return mid.filterNot { it in gone2 }
    }

    /** "392.52(7.45%)": the % in brackets glued to an amount by [glue]. */
    fun bracketPct(text: String): BigDecimal? = Regex("""\((\d{1,2}(?:\.\d{1,3})?)%\)$""").find(text.trim())?.groupValues?.get(1)?.toBigDecimalOrNull()

    /** The same word read twice (e.g. overlapping OCR strips): keep one. */
    fun dedupe(ws: List<OcrWord>): List<OcrWord> {
        val out = ArrayList<OcrWord>(ws.size)
        val sorted = ws.sortedBy { it.box.t }
        for (w in sorted) {
            var dup = false
            for (k in out.indices.reversed()) {
                val o = out[k]
                if (o.box.b < w.box.t - 2 * max(1, w.box.h)) break
                if (o.text != w.text) continue
                val ix = min(o.box.r, w.box.r) - max(o.box.l, w.box.l); val iy = min(o.box.b, w.box.b) - max(o.box.t, w.box.t)
                if (ix > 0 && iy > 0 && ix.toLong() * iy * 2 > min(o.box.w.toLong() * o.box.h, w.box.w.toLong() * w.box.h)) { dup = true; break }
            }
            if (!dup) out += w
        }
        return out
    }

    /** Lines of a page: tilt taken out, then chained. */
    fun rows(words: List<OcrWord>): List<Row> = lines(straighten(words))
}
