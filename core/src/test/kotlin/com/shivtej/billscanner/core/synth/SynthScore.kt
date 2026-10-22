package com.shivtej.billscanner.core.synth

import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.ocr.InvoiceParser
import java.math.BigDecimal

/** How well the reader did on one made-up bill, line by line. */
object SynthScore {
    class Result(val bill: SynthBill.Bill, val linesOk: Int, val problems: List<String>, val flagged: Int = 0, val silent: List<String> = emptyList()) {
        val ok get() = problems.isEmpty()
    }

    private fun near(a: BigDecimal?, b: BigDecimal?, tol: String) = a != null && b != null && (a - b).abs() <= BigDecimal(tol)

    fun of(b: SynthBill.Bill): Result {
        val p = runCatching { InvoiceParser.parse(b.pages) }.getOrElse { return Result(b, 0, listOf("CRASH ${it::class.simpleName}: ${it.message}")) }
        val items = p.items.filter { it.kind == ItemKind.ARTICLE }
        val problems = mutableListOf<String>()
        if (items.size != b.lines.size) problems += "lines ${items.size}/${b.lines.size}"
        var ok = 0; var flagged = 0
        val silent = mutableListOf<String>()
        val bySr = items.groupBy { it.current.sr }
        val matched = HashSet<String>()
        for (t in b.lines) {
            // The line with this serial; without serials (or a serial lost), the line with these numbers.
            fun same(i: com.shivtej.billscanner.core.model.LineItem) = i.current.qty?.compareTo(BigDecimal(t.qty)) == 0 && near(i.current.taxable, t.taxable, "1.00")
            val got = bySr[t.sr]?.firstOrNull { same(it) } ?: items.firstOrNull { same(it) } ?: bySr[t.sr]?.firstOrNull() ?: items.getOrNull(t.sr - 1)
            if (got == null) { problems += "line ${t.sr}: missing"; flagged++; continue }
            matched += got.id
            val v = got.current
            val bad = mutableListOf<String>()
            if (v.qty?.compareTo(BigDecimal(t.qty)) != 0) bad += "qty ${v.qty}≠${t.qty}"
            if (b.showsMrp && t.mrp != null && v.mrp?.compareTo(t.mrp) != 0) bad += "mrp ${v.mrp}≠${t.mrp}"
            if (!near(v.taxable, t.taxable, if (b.inclusive) "1.00" else "0.02")) bad += "taxable ${v.taxable}≠${t.taxable}"
            val wrong = bad.isNotEmpty()
            if (got.needs.any { it in com.shivtej.billscanner.core.ocr.Heal.MONEY } || !com.shivtej.billscanner.core.ocr.Heal.sure(got)) { bad += "needs ${got.needs}"; flagged++ }
            else if (wrong) silent += "line ${t.sr}: ${bad.joinToString(", ")} raw=${got.raw}"
            if (bad.isEmpty()) ok++ else if (problems.size < 12) problems += "line ${t.sr}: ${bad.joinToString(", ")} raw=${got.raw}"
        }
        if (!near(p.header.printedGrandTotal, b.grand, "0.01")) problems += "grand ${p.header.printedGrandTotal}≠${b.grand}"
        if (p.header.invoiceNo != b.invoiceNo) problems += "invoiceNo ${p.header.invoiceNo}≠${b.invoiceNo}"
        // Extra lines that are not on the bill and look sure are silent errors too.
        if (items.size > b.lines.size) items.filter { i -> i.id !in matched && com.shivtej.billscanner.core.ocr.Heal.sure(i) }.forEach { silent += "extra line ${it.current.sr} ${it.raw}" }
        return Result(b, ok, problems, flagged, silent)
    }
}
