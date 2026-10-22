package com.shivtej.billscanner.core.model

import java.math.BigDecimal

data class SizeQty(val colour: String, val size: String, val qty: Int)

/**
 * Reads size assortments printed on supplier bills, keeping the original text untouched:
 * - "6/2,7/3,8/3"    size 6 x2, size 7 x3 ...
 * - "10/24"          size 10 x24
 * - "6X8/1"          sizes 6..8, one of each per set; the bill quantity says how many sets
 * - "Black 6/2,7/3 | Grey 7/3"  one part per colour
 * Supplier shorthand differs, so anything that does not add up is reported, never guessed.
 */
object Sizes {
    enum class State { EXACT, FROM_SETS, MISMATCH, UNCLEAR, NONE }

    data class Result(val state: State, val rows: List<SizeQty>, val hindi: String) {
        val total: Int get() = rows.sumOf { it.qty }
    }

    private val PAIR = Regex("""^(\d{1,2}(?:\.\d)?|[A-Z]{1,3}\d{0,2})/(\d{1,4})$""", RegexOption.IGNORE_CASE)
    private val RANGE = Regex("""^(\d{1,2})[Xx×](\d{1,2})/(\d{1,3})$""")

    /** "Black 6/2,7/3 | Grey 7/3" -> [("Black","6/2,7/3"), ("Grey","7/3")]. */
    fun parts(sizeText: String): List<Pair<String, String>> =
        sizeText.split('|').map { it.trim() }.filter { it.isNotEmpty() }.map { p ->
            val m = Regex("""^([A-Za-z][A-Za-z .-]*?)\s+(\S.*)$""").find(p)
            if (m != null && m.groupValues[2].any(Char::isDigit)) m.groupValues[1].trim() to m.groupValues[2].trim() else "" to p
        }

    fun read(sizeText: String, billQty: BigDecimal?): Result {
        val qty = billQty?.toInt()
        val ps = parts(sizeText)
        if (ps.isEmpty()) return Result(State.NONE, emptyList(), "साइज़ नहीं छपे हैं")
        val rows = mutableListOf<SizeQty>()
        var sets: Pair<String, MatchResult>? = null
        for ((colour, text) in ps) {
            for (tok in text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val r = RANGE.matchEntire(tok)
                val p = PAIR.matchEntire(tok)
                when {
                    r != null -> { if (sets == null && ps.size == 1) sets = colour to r else return Result(State.UNCLEAR, emptyList(), "\"$tok\" का मतलब साफ़ नहीं") }
                    p != null -> { val n = p.groupValues[2].toInt(); if (n > 0) rows += SizeQty(colour, p.groupValues[1], n) }
                    else -> return Result(State.UNCLEAR, emptyList(), "\"$tok\" साइज़ जैसा नहीं पढ़ा गया")
                }
            }
        }
        val s = sets
        if (s != null) {
            if (rows.isNotEmpty()) return Result(State.UNCLEAR, emptyList(), "साइज़ सेट और अलग साइज़ दोनों छपे हैं — जाँचें")
            val (colour, m) = s
            val from = m.groupValues[1].toInt(); val to = m.groupValues[2].toInt(); val per = m.groupValues[3].toInt()
            if (to < from || per < 1) return Result(State.UNCLEAR, emptyList(), "\"${m.value}\" का मतलब साफ़ नहीं")
            val sizes = (from..to).toList()
            val perSet = sizes.size * per
            if (qty == null || qty % perSet != 0) {
                return Result(State.UNCLEAR, emptyList(),
                    "\"${m.value}\": $qty जोड़ी ${sizes.size} साइज़ (${from}–${to}) में बराबर नहीं बँटती — साइज़-वार गिनती जाँचें")
            }
            val setsN = qty / perSet
            return Result(State.FROM_SETS, sizes.map { SizeQty(colour, it.toString(), per * setsN) },
                "$setsN सेट × ${sizes.size} साइज़ (${from}–${to}) = $qty जोड़ी")
        }
        if (rows.isEmpty()) return Result(State.NONE, emptyList(), "साइज़ में गिनती नहीं")
        val total = rows.sumOf { it.qty }
        return if (qty != null && total != qty) Result(State.MISMATCH, rows, "साइज़-वार जोड़ $total, पर बिल में मात्रा $qty — जाँचें")
        else Result(State.EXACT, rows, "साइज़-वार जोड़ $total = बिल की मात्रा")
    }
}
