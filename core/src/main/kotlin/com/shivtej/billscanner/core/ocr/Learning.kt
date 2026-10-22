package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.store.Json
import java.math.BigDecimal

/**
 * What the scanner has learnt on this phone, from the person's corrections and from bills that added up.
 *
 * - [fixes]: per supplier and column, a printed text the scan misread and the value it really is ("745 %" → 7.45).
 * - [swaps]: letters the scan reads in place of digits ("G" for 6, "S" for 5), with how often the person fixed them.
 * - [junk]: per supplier, starts of lines the person deleted (bank details, a tax summary…).
 * - [mrp]: per supplier, the MRP of each article from lines whose numbers added up, to check a misread MRP.
 */
data class Learnt(
    val fixes: Map<String, Map<F, Map<String, String>>> = emptyMap(),
    val swaps: Map<String, Int> = emptyMap(),
    val junk: Map<String, Set<String>> = emptyMap(),
    val mrp: Map<String, Map<String, String>> = emptyMap(),
) {
    val size get() = fixes.values.sumOf { m -> m.values.sumOf { it.size } } + swaps.size + junk.values.sumOf { it.size }

    fun encode(): String = Json.write(linkedMapOf(
        "fixes" to fixes.mapValues { (_, m) -> m.mapKeys { it.key.name } },
        "swaps" to swaps, "junk" to junk.mapValues { it.value.toList() }, "mrp" to mrp,
    ))

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun decode(text: String?): Learnt {
            if (text.isNullOrBlank()) return Learnt()
            return runCatching {
                val m = Json.parse(text) as Map<String, Any?>
                Learnt(
                    fixes = (m["fixes"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) ->
                        (v as? Map<String, Any?>).orEmpty().mapNotNull { (f, x) -> runCatching { F.valueOf(f) }.getOrNull()?.let { it to (x as? Map<String, Any?>).orEmpty().mapValues { e -> e.value.toString() } } }.toMap()
                    },
                    swaps = (m["swaps"] as? Map<String, Any?>).orEmpty().mapValues { (it.value as? Number)?.toInt() ?: it.value.toString().toDoubleOrNull()?.toInt() ?: 1 },
                    junk = (m["junk"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) -> (v as? List<Any?>).orEmpty().map { it.toString() }.toSet() },
                    mrp = (m["mrp"] as? Map<String, Any?>).orEmpty().mapValues { (_, v) -> (v as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() } },
                )
            }.getOrDefault(Learnt())
        }
    }
}

object Learning {
    /** Columns whose numbers can be taught. */
    val NUMBERS = listOf(F.QTY, F.MRP, F.RATE, F.GROSS, F.DISC_PCT, F.DISC_AMT, F.TAXABLE, F.GST_PCT, F.CGST_PCT, F.SGST_PCT, F.IGST_PCT, F.CGST_AMT, F.SGST_AMT, F.IGST_AMT, F.RATE_GST, F.TOTAL)
    private val ARITH = setOf(F.QTY, F.RATE, F.DISC_PCT, F.TAXABLE, F.GROSS, F.DISC_AMT)
    private val UNIT = Regex("""(?i)\s*(pairs?|prs?|pcs?|nos?|sets?|%|₹|rs\.?|/-)\s*""")

    fun supplier(h: Header): String? = TaughtLayouts.key(h)
    fun norm(raw: String) = raw.trim().replace(Regex("""\s+"""), " ").uppercase()
    private fun lineStart(i: LineItem) = norm(i.raw.firstOrNull { it.any(Char::isLetter) } ?: i.current.article).split(' ').take(2).joinToString(" ")
    fun articleKey(v: ItemValues) = (v.article + " " + v.colour).uppercase().filter(Char::isLetterOrDigit)

    private fun get(v: ItemValues, f: F): BigDecimal? = when (f) {
        F.QTY -> v.qty; F.MRP -> v.mrp; F.RATE -> v.rate; F.GROSS -> v.gross; F.DISC_PCT -> v.discPct; F.DISC_AMT -> v.discAmt
        F.TAXABLE -> v.taxable; F.GST_PCT -> v.gstPct; F.CGST_PCT -> v.cgstPct; F.SGST_PCT -> v.sgstPct; F.IGST_PCT -> v.igstPct
        F.CGST_AMT -> v.cgstAmt; F.SGST_AMT -> v.sgstAmt; F.IGST_AMT -> v.igstAmt; F.RATE_GST -> v.rateGst; F.TOTAL -> v.printedTotal; else -> null
    }

    private fun set(v: ItemValues, f: F, x: BigDecimal): ItemValues = when (f) {
        F.QTY -> v.copy(qty = x); F.MRP -> v.copy(mrp = x); F.RATE -> v.copy(rate = x); F.GROSS -> v.copy(gross = x)
        F.DISC_PCT -> v.copy(discPct = x); F.DISC_AMT -> v.copy(discAmt = x); F.TAXABLE -> v.copy(taxable = x); F.GST_PCT -> v.copy(gstPct = x)
        F.CGST_PCT -> v.copy(cgstPct = x); F.SGST_PCT -> v.copy(sgstPct = x); F.IGST_PCT -> v.copy(igstPct = x)
        F.CGST_AMT -> v.copy(cgstAmt = x); F.SGST_AMT -> v.copy(sgstAmt = x); F.IGST_AMT -> v.copy(igstAmt = x); F.RATE_GST -> v.copy(rateGst = x); F.TOTAL -> v.copy(printedTotal = x); else -> v
    }

    /**
     * The person corrected a line: remember, for this supplier, what each wrongly read cell really said,
     * and which letters stood for which digits. Returns what was learnt (Hindi, to show) and the new memory.
     */
    fun fromEdit(l: Learnt, inv: Invoice, old: LineItem, new: ItemValues): Pair<Learnt, List<String>> {
        val s = supplier(inv.header) ?: supplier(inv.headerOriginal) ?: return l to emptyList()
        // Only a line the scan itself doubted teaches anything (a number changed on purpose on a line that added up does not).
        if (Heal.sure(old.copy(current = old.original))) return l to emptyList()
        // …and only when what the person typed makes the line add up (a % typed in the wrong box teaches nothing).
        val mrpOk = new.mrp != null && com.shivtej.billscanner.core.model.Calc.sittingPct(new, inv.header) != null
        if (!Heal.proven(new) && !mrpOk) return l to emptyList()
        val lessons = mutableListOf<String>()
        val fixes = l.fixes.mapValues { (_, m) -> m.mapValues { it.value.toMutableMap() }.toMutableMap() }.toMutableMap()
        val swaps = l.swaps.toMutableMap()
        for (f in NUMBERS) {
            val was = get(old.original, f); val now = get(new, f) ?: continue
            if (was != null && was.compareTo(now) == 0) continue
            if (f in ARITH && !Heal.proven(new)) continue
            if (f == F.MRP && !mrpOk) continue
            val ci = inv.columns.indexOfFirst { it.field == f }
            val raw = old.raw.getOrNull(ci)?.takeIf { ci >= 0 && it.isNotBlank() } ?: continue
            fixes.getOrPut(s) { mutableMapOf() }.getOrPut(f) { mutableMapOf() }[norm(raw)] = now.stripTrailingZeros().toPlainString()
            lessons += "\"$raw\" = ${now.stripTrailingZeros().toPlainString()}"
            // Letters read for digits: same length once units are dropped.
            val t = raw.replace(UNIT, "").replace(",", ""); val d = now.stripTrailingZeros().toPlainString()
            if (t.length == d.length) for (k in t.indices) if (t[k] != d[k] && t[k].isLetter() && d[k].isDigit()) {
                val key = "${t[k].uppercaseChar()}>${d[k]}"
                swaps[key] = (swaps[key] ?: 0) + 1
                lessons += "अक्षर \"${t[k]}\" = ${d[k]}"
            }
        }
        return l.copy(fixes = fixes, swaps = swaps) to lessons.distinct()
    }

    /** The person deleted a line that was not an article: lines starting the same way are skipped for this supplier. */
    fun fromDelete(l: Learnt, inv: Invoice, item: LineItem): Learnt {
        val s = supplier(inv.header) ?: return l
        // Only lines the scan doubted (an article that added up is not junk, it may just be a repeat).
        if (Heal.sure(item)) return l
        val start = lineStart(item).takeIf { it.length >= 2 } ?: return l
        return l.copy(junk = l.junk + (s to (l.junk[s].orEmpty() + start)))
    }

    /** Lines that added up teach each article's MRP for this supplier. */
    fun fromBill(l: Learnt, inv: Invoice): Learnt {
        val s = supplier(inv.header) ?: return l
        val add = inv.items.filter { it.kind == ItemKind.ARTICLE && Heal.sure(it) && it.current.mrp != null && articleKey(it.current).length >= 3 }
            .associate { articleKey(it.current) to it.current.mrp!!.stripTrailingZeros().toPlainString() }
        if (add.isEmpty()) return l
        return l.copy(mrp = l.mrp + (s to (l.mrp[s].orEmpty() + add)))
    }

    /** Uses what was learnt on a newly read bill. Returns the bill and how many lines it fixed. */
    fun apply(l: Learnt, p: ParsedInvoice): Pair<ParsedInvoice, Int> {
        val s = supplier(p.header) ?: return p to 0
        val fixes = l.fixes[s].orEmpty(); val junk = l.junk[s].orEmpty(); val mrps = l.mrp[s].orEmpty()
        val swaps = l.swaps.filterValues { it >= 1 }.keys.mapNotNull { k -> k.split('>').takeIf { it.size == 2 && it[0].length == 1 && it[1].length == 1 }?.let { it[0][0] to it[1][0] } }.toMap()
        if (fixes.isEmpty() && junk.isEmpty() && mrps.isEmpty() && swaps.isEmpty()) return p to 0
        var fixed = 0
        val items = p.items.mapNotNull { item ->
            if (item.kind != ItemKind.ARTICLE) return@mapNotNull item
            val sure = Heal.sure(item)
            if (!sure && lineStart(item) in junk) return@mapNotNull null
            if (sure) return@mapNotNull item
            var v = item.current
            for (f in NUMBERS) {
                val ci = p.columns.indexOfFirst { it.field == f }
                val raw = item.raw.getOrNull(ci)?.takeIf { ci >= 0 && it.isNotBlank() } ?: continue
                val taught = fixes[f]?.get(norm(raw))?.toBigDecimalOrNull()
                if (taught != null) { v = set(v, f, taught); continue }
                // Letters the person taught as digits ("G pair" = 6).
                if (get(v, f) == null && swaps.isNotEmpty() && raw.any { it.uppercaseChar() in swaps }) {
                    val t = raw.replace(UNIT, " ").trim().map { c -> swaps[c.uppercaseChar()] ?: c }.joinToString("")
                    InvoiceParser.number(t)?.let { v = set(v, f, it) }
                }
            }
            // A learnt number is kept only if the line then adds up (a wrong lesson can never spoil a bill).
            if (!Heal.proven(v)) v = v.copy(qty = item.current.qty, rate = item.current.rate, discPct = item.current.discPct,
                taxable = item.current.taxable, gross = item.current.gross, discAmt = item.current.discAmt)
            if (v.mrp != item.current.mrp && v.mrp != null && com.shivtej.billscanner.core.model.Calc.sittingPct(v, p.header) == null) v = v.copy(mrp = item.current.mrp)
            // A misread MRP: the article's MRP from this supplier's earlier bills.
            val known = mrps[articleKey(v)]?.toBigDecimalOrNull()
            if (known != null && (F.MRP in item.needs || v.mrp == null)) v = v.copy(mrp = known)
            if (v == item.current) return@mapNotNull item
            val needs = (if (Heal.proven(v)) item.needs - ARITH - F.MRP else item.needs).let { n -> if (known != null && v.mrp != null) n - F.MRP else n }
            if (needs.size < item.needs.size) fixed++
            item.copy(current = v, original = v, needs = needs)
        }
        val dropped = p.items.size - items.size
        val notes = p.notes + listOfNotNull(
            if (fixed > 0) "पिछली बार आपके सुधार से सीखा: $fixed लाइनें खुद ठीक कीं" else null,
            if (dropped > 0) "आपने पहले ऐसी लाइनें हटाई थीं: $dropped लाइनें खुद हटाईं" else null,
        )
        return p.copy(items = items, notes = notes) to fixed + dropped
    }
}
