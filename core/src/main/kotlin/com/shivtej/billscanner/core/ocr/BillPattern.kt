package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.store.Json
import java.math.BigDecimal
import java.util.Base64

/**
 * How one supplier prints its bills, given to the app as a short code ("SBP1:…") that the owner pastes in.
 * Made from a bill the scanner did not understand: which printed column is what, the columns left to right,
 * and where the MRP is when it is written inside the description ("HSW-012-NAVY-239").
 * Stays on the phone; nothing is sent anywhere.
 */
data class BillPattern(
    /** Name shown in the app ("Yash Enterprises"). */
    val name: String,
    /** Supplier GSTINs this is for. */
    val gstins: List<String> = emptyList(),
    /** Or words of the supplier's name (letters and digits only, upper case), e.g. "YASHENTERPRISES". */
    val names: List<String> = emptyList(),
    /** Printed heading (letters, digits, % only, lower case) → what it is. */
    val headings: Map<String, InvoiceParser.Kind> = emptyMap(),
    /** The item table's columns left to right, used when the same number of columns is found. */
    val order: List<InvoiceParser.Kind> = emptyList(),
    /** Where the MRP is inside the description: a pattern whose first group is the MRP. */
    val mrpInDesc: String? = null,
    val note: String? = null,
) {
    fun matches(h: Header): Boolean {
        val g = h.supplierGstin?.let(::fuzzy)
        if (g != null && gstins.any { fuzzy(it) == g }) return true
        val n = h.supplierName?.uppercase()?.filter(Char::isLetterOrDigit).orEmpty()
        return n.isNotEmpty() && names.any { it.isNotEmpty() && (n.contains(it) || it.contains(n) && n.length >= 6) }
    }

    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(linkedMapOf(
        "v" to 1, "n" to name, "g" to gstins, "s" to names,
        "h" to LinkedHashMap(headings.mapValues { it.value.name }), "o" to order.map { it.name },
        "m" to mrpInDesc, "t" to note,
    )).toByteArray(Charsets.UTF_8))

    companion object {
        const val PREFIX = "SBP1:"

        /** OCR mixes up O/0, I/1, S/5, B/8, Z/2 in GSTINs. */
        fun fuzzy(g: String) = g.uppercase().filter(Char::isLetterOrDigit)
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; 'S' -> '5'; 'B' -> '8'; 'Z' -> '2'; else -> it } }.joinToString("")

        fun norm(label: String) = label.lowercase().replace(Regex("""[^a-z0-9%]"""), "")

        /** Reads a pasted code; null when it is not one (spaces, line breaks and text around it are allowed). */
        @Suppress("UNCHECKED_CAST")
        fun decode(text: String): BillPattern? = runCatching {
            val code = Regex("""SBP1:([A-Za-z0-9_\-\s]+)""").find(text)?.groupValues?.get(1)?.filterNot(Char::isWhitespace) ?: return null
            val m = Json.parse(String(Base64.getUrlDecoder().decode(code), Charsets.UTF_8)) as Map<String, Any?>
            fun strs(k: String) = (m[k] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            fun kind(s: Any?) = (s as? String)?.let { runCatching { InvoiceParser.Kind.valueOf(it) }.getOrNull() }
            BillPattern(
                name = m["n"] as? String ?: return null,
                gstins = strs("g"), names = strs("s"),
                headings = (m["h"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> kind(v)?.let { norm(k) to it } }.toMap(),
                order = (m["o"] as? List<*>).orEmpty().mapNotNull { kind(it) },
                mrpInDesc = (m["m"] as? String)?.takeIf { it.isNotBlank() && runCatching { Regex(it) }.isSuccess },
                note = m["t"] as? String,
            )
        }.getOrNull()

        /**
         * Reads the bill: as usual first; when a pasted pattern is for this supplier, again with it.
         * [base] is what the person taught by hand (it still comes first for a heading it knows).
         */
        fun parse(pages: List<OcrPage>, patterns: List<BillPattern>, base: LayoutMemory? = null): ParsedInvoice {
            val first = InvoiceParser.parse(pages, base)
            val p = patterns.firstOrNull { it.matches(first.header) } ?: return first
            val memory = object : LayoutMemory {
                override fun kindFor(header: Header, label: String) = base?.kindFor(header, label) ?: p.headings[norm(label)]
                override fun orderFor(header: Header) = p.order.ifEmpty { null }
            }
            val again = InvoiceParser.parse(pages, memory)
            return apply(again, p).let { it.copy(notes = it.notes + "${p.name} का पैटर्न लगाया (आपका जोड़ा हुआ कोड)") }
        }

        /** The MRP written inside the description, where the bill has no MRP column. */
        fun apply(parsed: ParsedInvoice, p: BillPattern): ParsedInvoice {
            val re = p.mrpInDesc?.let { runCatching { Regex(it) }.getOrNull() } ?: return parsed
            val items = parsed.items.map { i ->
                if (i.kind != ItemKind.ARTICLE || i.current.mrp != null) return@map i
                val text = listOf(i.current.article, i.current.colour, i.current.sizeText, i.current.name).joinToString(" ") + " " + i.raw.joinToString(" ")
                val mrp = re.find(text)?.groupValues?.getOrNull(1)?.replace(",", "")?.toBigDecimalOrNull() ?: return@map i
                if (mrp.signum() <= 0) return@map i
                val v = i.current.copy(mrp = mrp)
                i.copy(current = v, original = v, needs = i.needs - F.MRP)
            }
            return parsed.copy(items = items)
        }
    }
}
