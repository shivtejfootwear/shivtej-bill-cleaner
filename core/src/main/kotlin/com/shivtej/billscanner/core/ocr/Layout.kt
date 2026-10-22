package com.shivtej.billscanner.core.ocr

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
        return rows.map { r -> Row(r.sortedBy { it.box.l }, r.map { it.box.cy }.average()) }.sortedBy { it.y }
    }

    /** Lines of a page: tilt taken out, then chained. */
    fun rows(words: List<OcrWord>): List<Row> = lines(straighten(words))
}
