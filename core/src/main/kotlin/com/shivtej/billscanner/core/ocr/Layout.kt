package com.shivtej.billscanner.core.ocr

import kotlin.math.abs

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

object Layout {
    private val HEADER_WORDS = setOf("description", "rate", "taxable", "cgst", "sgst", "igst", "discount", "total")

    fun medianHeight(words: List<OcrWord>): Double =
        words.map { it.box.h }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 20.0 else it[it.size / 2].toDouble() }

    /**
     * Tilt of the photo, from the table header words that sit on one line
     * ("Description ... Rate ... Total ... Taxable ... CGST ... SGST").
     * Returned as dy/dx; 0 when it cannot be measured.
     */
    fun skew(words: List<OcrWord>): Double {
        // Projection profile: at the right tilt, words of one line fall into the same narrow band,
        // so the histogram of straightened y positions is sharpest (largest sum of squares).
        if (words.size < 8) return 0.0
        val mh = medianHeight(words)
        val bin = (mh * 0.5).coerceAtLeast(2.0)
        var best = 0.0; var bestScore = -1.0
        var s = -0.10
        while (s <= 0.1000001) {
            val counts = HashMap<Long, Int>()
            for (w in words) {
                val k = Math.floorDiv((w.box.cy - s * w.box.cx).toLong(), bin.toLong().coerceAtLeast(1))
                counts[k] = (counts[k] ?: 0) + 1
            }
            val score = counts.values.sumOf { it.toDouble() * it }
            if (score > bestScore + 1e-9 || (abs(score - bestScore) < 1e-9 && abs(s) < abs(best))) { bestScore = score; best = s }
            s += 0.002
        }
        return best
    }

    /** Groups words into visual lines, straightening the photo's tilt first. */
    fun rows(words: List<OcrWord>, slope: Double = skew(words)): List<Row> {
        if (words.isEmpty()) return emptyList()
        val mh = medianHeight(words)
        fun y(w: OcrWord) = w.box.cy - slope * w.box.cx
        val sorted = words.sortedBy { y(it) }
        val groups = mutableListOf<MutableList<OcrWord>>()
        var mean = 0.0
        for (w in sorted) {
            val g = groups.lastOrNull()
            if (g != null && abs(y(w) - mean) <= 0.55 * mh) {
                g += w; mean = g.map { y(it) }.average()
            } else {
                groups += mutableListOf(w); mean = y(w)
            }
        }
        return groups.map { g -> Row(g.sortedBy { it.box.l }, g.map { y(it) }.average()) }
    }
}
