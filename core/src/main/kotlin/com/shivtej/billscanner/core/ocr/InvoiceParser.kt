package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import java.math.BigDecimal
import kotlin.math.abs

/** What the scan produced, before the person reviews it. */
data class ParsedInvoice(val header: Header, val items: List<LineItem>, val notes: List<String>)

/**
 * Reads a footwear supplier's tax invoice from OCR words.
 *
 * Works on positions, not on the order the OCR returned text in: words are put back into
 * visual lines, the item table is found from its header ("Description … Rate … Taxable …"),
 * and every number of a line is checked against the bill's own arithmetic
 * (qty × rate = total, total × % = discount, total − discount = taxable, taxable × % = GST).
 * Anything missing or not adding up is flagged for the person to check — never invented.
 */
object InvoiceParser {
    private val NUM = Regex("""^\(?₹?(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.(\d{1,3}))?\)?[,.:;]?$""")
    private val GSTIN = Regex("""(?<![A-Z0-9])\d{2}[A-Z]{5}\d{4}[A-Z][A-Z0-9]Z[A-Z0-9](?![A-Z0-9])""")
    private val MRP = Regex("""(?i)M\s*R\s*P\s*[:.;]?\s*(?:Rs\.?|₹)?\s*(\d{1,6}(?:\.\d{1,2})?)""")
    private val END = Regex("""(?i)\b(our\s+bankers|bank\s+name|terms|declaration|e\s*&\s*o\s*e|authori[sz]ed\s+signatory)\b""")
    private val DATE = Regex("""\b(\d{1,2}[-/. ](?:[A-Za-z]{3,9}|\d{1,2})[-/. ]\d{2,4})\b""")

    fun number(text: String): BigDecimal? {
        val t = text.trim().replace('O', '0').replace('o', '0')
        val m = NUM.matchEntire(t) ?: return null
        val whole = m.groupValues[1].replace(",", "")
        val frac = m.groupValues[2]
        return BigDecimal(if (frac.isEmpty()) whole else "$whole.$frac")
    }

    private fun isNum(w: OcrWord) = number(w.text) != null

    fun parse(pages: List<OcrPage>): ParsedInvoice {
        var header = Header()
        val items = mutableListOf<LineItem>()
        val notes = mutableListOf<String>()
        pages.forEachIndexed { i, p ->
            val r = parsePage(p, items.size)
            header = merge(header, r.header)
            items += r.items
            notes += r.notes.map { if (pages.size > 1) "पेज ${i + 1}: $it" else it }
        }
        if (items.isEmpty()) notes += "बिल में आर्टिकल की टेबल नहीं मिली। फ़ोटो साफ़ और पूरी हो, फिर दोबारा स्कैन करें या आर्टिकल हाथ से जोड़ें।"
        return ParsedInvoice(header, items, notes)
    }

    private fun merge(a: Header, b: Header) = Header(
        supplierName = a.supplierName ?: b.supplierName, supplierAddress = a.supplierAddress ?: b.supplierAddress,
        supplierGstin = a.supplierGstin ?: b.supplierGstin, supplierContact = a.supplierContact ?: b.supplierContact,
        invoiceNo = a.invoiceNo ?: b.invoiceNo, invoiceDate = a.invoiceDate ?: b.invoiceDate, orderNo = a.orderNo ?: b.orderNo,
        transporter = a.transporter ?: b.transporter, cartons = a.cartons ?: b.cartons, packing = a.packing ?: b.packing,
        buyerName = a.buyerName ?: b.buyerName, buyerGstin = a.buyerGstin ?: b.buyerGstin,
        // The total printed last is the bill total.
        printedGrandTotal = b.printedGrandTotal ?: a.printedGrandTotal,
        overallDiscount = a.overallDiscount ?: b.overallDiscount, otherCharges = a.otherCharges ?: b.otherCharges,
        roundOff = b.roundOff ?: a.roundOff, pageNote = b.pageNote ?: a.pageNote,
    )

    // ---------------------------------------------------------------- one page

    private class Columns(
        val descRight: Double,
        val hsnX: Double?,
        /** Centres of the number columns, left to right, with their meaning. */
        val numeric: List<Pair<F, Double>>,
    )

    private data class PageResult(val header: Header, val items: List<LineItem>, val notes: List<String>)

    private fun parsePage(page: OcrPage, startIndex: Int): PageResult {
        val rows = Layout.rows(page.words)
        val mh = Layout.medianHeight(page.words)
        val notes = mutableListOf<String>()
        val hIdx = rows.indexOfFirst { r -> val t = r.text.lowercase(); ("description" in t || "particular" in t) && ("rate" in t || "hsn" in t || "qty" in t) }
            .takeIf { it >= 0 } ?: rows.indexOfFirst { r -> val t = r.text.lowercase(); "rate" in t && "total" in t }
        val header = readHeader(rows, if (hIdx >= 0) hIdx else rows.size, mh)
        if (hIdx < 0) return PageResult(header, emptyList(), listOf("आर्टिकल टेबल का हेडर (Description, Rate…) नहीं मिला"))

        val cols = columns(rows, hIdx, page.width)
        // Skip the 1–3 header lines ("Qty / in / Pairs", "% / Amount").
        var start = hIdx + 1
        while (start < rows.size && start <= hIdx + 3 && rows[start].words.none { isNum(it) && it.box.cx > cols.descRight && number(it.text)!! > BigDecimal(100) }) start++
        val endIdx = (start until rows.size).firstOrNull { END.containsMatchIn(rows[it].text) } ?: rows.size

        // Rows that start a new line item: serial no. or article code on the left, many numbers on the right.
        fun rightNums(r: Row) = r.words.filter { it.box.cx > cols.descRight && isNum(it) }
        fun isItemStart(r: Row): Boolean {
            val left = r.words.filter { it.box.cx <= cols.descRight }
            if (left.isEmpty()) return false
            return rightNums(r).size >= 5
        }
        fun isTotalRow(r: Row): Boolean {
            val left = r.words.filter { it.box.cx <= cols.descRight }
            val nums = rightNums(r)
            return left.isEmpty() && nums.size in 1..2 && nums.all { number(it.text)!!.scale() >= 0 } && nums.any { it.text.contains('.') } &&
                (cols.numeric.find { it.first == F.GROSS }?.second?.let { gx -> nums.all { it.box.cx > gx } } ?: true)
        }

        val starts = (start until endIdx).filter { isItemStart(rows[it]) }
        val items = mutableListOf<LineItem>()
        var printedTotal: BigDecimal? = null
        starts.forEachIndexed { k, s ->
            var e = if (k + 1 < starts.size) starts[k + 1] else endIdx
            // A lone total box under the table closes the last item.
            val totalRow = (s + 1 until e).firstOrNull { isTotalRow(rows[it]) }
            if (totalRow != null && k == starts.lastIndex) {
                printedTotal = rows[totalRow].words.mapNotNull { number(it.text) }.maxOrNull()
                e = totalRow
            }
            items += readItem(rows.subList(s, e), cols, startIndex + items.size + 1)
        }
        if (printedTotal == null) {
            // "Grand Total 26012.00" written as text, or a total box after the table.
            printedTotal = (endIdx - 1 downTo start).asSequence().map { rows[it] }
                .firstOrNull { r -> Regex("""(?i)(grand\s*total|net\s*amount|invoice\s*total|bill\s*amount)""").containsMatchIn(r.text) }
                ?.words?.mapNotNull { number(it.text) }?.maxOrNull()
        }
        if (items.isEmpty()) notes += "टेबल मिली पर कोई आर्टिकल लाइन नहीं पढ़ी जा सकी"
        val pageNote = rows.firstNotNullOfOrNull { Regex("""(?i)page\s*(\d+)\s*of\s*(\d+)""").find(it.text)?.value }
        return PageResult(header.copy(printedGrandTotal = printedTotal, pageNote = pageNote), items, notes)
    }

    private fun columns(rows: List<Row>, hIdx: Int, pageWidth: Int): Columns {
        val hdr = rows.subList(hIdx, minOf(rows.size, hIdx + 3)).flatMap { it.words }
        fun x(vararg names: String) = hdr.firstOrNull { w -> names.any { w.text.lowercase().trim(':', '.', '(', ')').startsWith(it) } }
        val hsn = x("hsn"); val qty = x("qty", "quantity", "pairs"); val rate = x("rate")
        val total = hdr.firstOrNull { it.text.equals("Total", true) }
        val taxable = x("taxable"); val desc = x("description", "particular")
        val pcts = hdr.filter { it.text.trim() == "%" }.sortedBy { it.box.cx }
        val amts = hdr.filter { it.text.lowercase().startsWith("amount") || it.text.lowercase() == "amt" }.sortedBy { it.box.cx }
        val firstNumLeft = listOfNotNull(hsn?.box?.l, qty?.box?.l, rate?.box?.l).minOrNull()
        val descRight = when {
            firstNumLeft != null -> firstNumLeft - 4.0
            desc != null -> desc.box.r + (desc.box.w * 1.2)
            else -> pageWidth * 0.42
        }
        val numeric = mutableListOf<Pair<F, Double>>()
        qty?.let { numeric += F.QTY to it.box.cx }
        rate?.let { numeric += F.RATE to it.box.cx }
        total?.let { numeric += F.GROSS to it.box.cx }
        val kinds = listOf(F.DISC_PCT to F.DISC_AMT, F.CGST_PCT to F.CGST_AMT, F.SGST_PCT to F.SGST_AMT)
        pcts.forEachIndexed { i, p -> kinds.getOrNull(i)?.let { numeric += it.first to p.box.cx } }
        amts.forEachIndexed { i, a -> kinds.getOrNull(i)?.let { numeric += it.second to a.box.cx } }
        taxable?.let { numeric += F.TAXABLE to it.box.cx }
        return Columns(descRight, hsn?.box?.cx, numeric.sortedBy { it.second })
    }

    // ---------------------------------------------------------------- one line item

    private val ORDER10 = listOf(F.QTY, F.RATE, F.GROSS, F.DISC_PCT, F.DISC_AMT, F.TAXABLE, F.CGST_PCT, F.CGST_AMT, F.SGST_PCT, F.SGST_AMT)
    private val ORDER8 = listOf(F.QTY, F.RATE, F.GROSS, F.DISC_PCT, F.DISC_AMT, F.TAXABLE, F.IGST_PCT, F.IGST_AMT)

    private fun readItem(block: List<Row>, cols: Columns, index: Int): LineItem {
        val first = block.first()
        val leftWords = block.map { r -> r.words.filter { it.box.cx <= cols.descRight } }
        // Serial number: a small integer at the very left of the first line.
        var sr: Int? = null
        val firstLeft = leftWords.first().toMutableList()
        firstLeft.firstOrNull()?.text?.trim('.', ')')?.toIntOrNull()?.takeIf { it in 1..999 && firstLeft.size > 1 }?.let { sr = it; firstLeft.removeAt(0) }
        val descLines = (listOf(firstLeft) + leftWords.drop(1)).map { ws -> ws.joinToString(" ") { it.text }.trim() }.filter { it.isNotEmpty() }

        // Numbers on the first line, without the HSN code.
        val nums = first.words.filter { it.box.cx > cols.descRight && isNum(it) }.toMutableList()
        var hsn = ""
        val hsnWord = nums.firstOrNull { w ->
            val t = w.text.trim(); t.length in 4..8 && t.all(Char::isDigit) && (cols.hsnX == null || abs(w.box.cx - cols.hsnX) < abs(w.box.cx - (cols.numeric.firstOrNull()?.second ?: Double.MAX_VALUE)))
        }
        if (hsnWord != null) {
            nums.remove(hsnWord); hsn = hsnWord.text.trim()
            // The rest of the HSN code often wraps onto the next line ("64029" / "990").
            block.drop(1).firstNotNullOfOrNull { r -> r.words.firstOrNull { w -> w.text.trim().all(Char::isDigit) && w.text.trim().length in 1..4 && abs(w.box.cx - hsnWord.box.cx) < hsnWord.box.w } }
                ?.let { if (hsn.length + it.text.trim().length <= 8) hsn += it.text.trim() }
        }
        val values = mutableMapOf<F, BigDecimal>()
        val needs = mutableSetOf<F>()
        val order = when (nums.size) { 10 -> ORDER10; 8 -> ORDER8; else -> null }
        if (order != null) {
            order.forEachIndexed { i, f -> values[f] = number(nums[i].text)!! }
        } else if (cols.numeric.isNotEmpty()) {
            // Fewer numbers than columns: put each under the nearest column header, then let the maths decide.
            for (w in nums) {
                val f = cols.numeric.minByOrNull { abs(it.second - w.box.cx) }!!.first
                if (f !in values) values[f] = number(w.text)!!
            }
        }
        // Text part: code, MRP, colour and sizes.
        val joined = descLines.joinToString("\n")
        val mrpMatch = MRP.find(joined)
        val mrp = mrpMatch?.groupValues?.get(1)?.let { BigDecimal(it) }
        val codeToken = descLines.firstOrNull()?.split(' ')?.firstOrNull().orEmpty()
        val (article, name, brand) = splitCode(codeToken)
        val rest = joined.replaceFirst(codeToken, "").let { t -> if (mrpMatch != null) t.replaceFirst(mrpMatch.value, "") else t }
            .lines().map { it.trim() }.filter { it.isNotEmpty() && it != ":" }
        val parts = mutableListOf<Pair<String, String>>()
        for (l in rest) {
            val m = Regex("""^([A-Za-z][A-Za-z]*(?:\s[A-Za-z]+)?)\s+(\S*\d.*)$""").find(l)
            when {
                m != null -> parts += m.groupValues[1] to m.groupValues[2].replace(" ", "")
                l.all { it.isLetter() || it == ' ' } -> parts += l to ""
                parts.isNotEmpty() -> parts[parts.lastIndex] = parts.last().first to (parts.last().second + l.replace(" ", ""))
                else -> parts += "" to l.replace(" ", "")
            }
        }
        val colour = parts.map { it.first }.filter { it.isNotEmpty() }.distinct().joinToString(" / ")
        val sizeText = parts.filter { it.second.isNotEmpty() }.joinToString(" | ") { (c, s) -> if (c.isEmpty()) s else "$c $s" }

        val kind = if (Regex("""(?i)^(freight|transport|packing|cartage|hamali|courier)""").containsMatchIn(article.ifEmpty { codeToken })) ItemKind.CHARGE else ItemKind.ARTICLE
        var v = ItemValues(
            sr = sr, article = article, name = name, brand = brand, colour = colour, sizeText = sizeText, hsn = hsn,
            qty = values[F.QTY], mrp = mrp, rate = values[F.RATE], gross = values[F.GROSS],
            discPct = values[F.DISC_PCT], discAmt = values[F.DISC_AMT], taxable = values[F.TAXABLE],
            cgstPct = values[F.CGST_PCT], cgstAmt = values[F.CGST_AMT], sgstPct = values[F.SGST_PCT], sgstAmt = values[F.SGST_AMT],
            igstPct = values[F.IGST_PCT], igstAmt = values[F.IGST_AMT],
        )
        v = repair(v)

        // Flags: missing values, and numbers that do not agree with the bill's own arithmetic.
        if (v.qty == null) needs += F.QTY
        if (v.rate == null) needs += F.RATE
        if (v.gross == null) needs += F.GROSS
        if (v.discPct == null) needs += F.DISC_PCT
        if (v.discAmt == null) needs += F.DISC_AMT
        if (v.taxable == null) needs += F.TAXABLE
        if (v.mrp == null && kind == ItemKind.ARTICLE) needs += F.MRP
        if (article.isEmpty()) needs += F.ARTICLE
        if (kind == ItemKind.ARTICLE && colour.isEmpty()) needs += F.COLOUR
        if (v.igstPct == null && (v.cgstPct == null || v.sgstPct == null)) { needs += F.CGST_PCT; needs += F.SGST_PCT }
        for (c in Calc.checkLine(v)) {
            needs += c.field
            when (c.field) {
                F.GROSS -> { needs += F.QTY; needs += F.RATE }
                F.DISC_AMT -> needs += F.DISC_PCT
                else -> {}
            }
        }
        return LineItem("i$index", kind, v, v, needs)
    }

    /**
     * If exactly one value of a line is missing and the others agree, the bill's arithmetic gives it —
     * but only for amounts, never for the printed discount % (that must be read from the bill).
     */
    private fun repair(v: ItemValues): ItemValues = v

    /** "1180-Paralite-Gents-EVA-6X10" -> article 1180, name "Paralite Gents EVA 6X10", brand Paralite. */
    fun splitCode(code: String): Triple<String, String, String> {
        val parts = code.split('-').filter { it.isNotEmpty() }
        if (parts.size < 2) return Triple(code, "", "")
        return Triple(parts[0], parts.drop(1).joinToString(" "), parts[1])
    }

    // ---------------------------------------------------------------- header

    private fun readHeader(rows: List<Row>, tableStart: Int, mh: Double): Header {
        val top = rows.take(tableStart)
        val gap = 1.8 * mh
        val segs: List<List<String>> = top.map { r -> r.segments(gap).map { s -> s.joinToString(" ") { it.text } } }
        fun after(label: Regex): String? {
            for (row in segs) for ((i, s) in row.withIndex()) {
                val m = label.find(s) ?: continue
                val rest = s.substring(m.range.last + 1).trim().trimStart(':', '.', '-').trim()
                val v = rest.ifEmpty { row.getOrNull(i + 1)?.trim()?.trimStart(':')?.trim() ?: "" }
                if (v.isNotEmpty()) return v
            }
            return null
        }
        val all = top.joinToString("\n") { it.text }
        val consigneeAt = top.indexOfFirst { Regex("""(?i)consignee|bill\s*to|buyer|ship\s*to""").containsMatchIn(it.text) }
        val gstins = top.mapIndexedNotNull { i, r -> GSTIN.find(r.text)?.let { i to it.value } }
        val supplierGstin = gstins.firstOrNull { consigneeAt < 0 || it.first < consigneeAt }?.second
        val buyerGstin = gstins.firstOrNull { consigneeAt >= 0 && it.first > consigneeAt }?.second
        val firstGstinRow = gstins.firstOrNull()?.first ?: tableStart
        val midX = (top.flatMap { it.words }.maxOfOrNull { it.box.r } ?: 0) / 2.0
        val supplierName = top.take(firstGstinRow).flatMap { r -> r.segments(gap).map { s -> s.joinToString(" ") { it.text } } }
            .firstOrNull { s ->
                s.length >= 5 && s.none { it.isLowerCase() } && s.count { it.isLetter() } >= 5 &&
                    !Regex("""(?i)invoice|tax|original|duplicate|distributor|gstin|bill""").containsMatchIn(s)
            }
        val addressRows = top.withIndex().filter { (i, _) -> i > firstGstinRow && (consigneeAt < 0 || i < consigneeAt) }
            .map { (_, r) -> r.segments(gap).firstOrNull()?.takeIf { it.first().box.l < midX }?.joinToString(" ") { it.text }.orEmpty() }
            .filter { s -> s.isNotEmpty() && !Regex("""(?i)gstin|cin\s*:|pan\b""").containsMatchIn(s) }
        val buyerName = if (consigneeAt >= 0) top.getOrNull(consigneeAt + 1)?.segments(gap)?.firstOrNull()?.joinToString(" ") { it.text } else null
        val contact = Regex("""(?i)contact\s*no\.?\s*[:.]?\s*(\d[\d ]{7,13}\d)""").find(top.take(if (consigneeAt >= 0) consigneeAt else top.size).joinToString("\n") { it.text })?.groupValues?.get(1)?.replace(" ", "")
        val invoiceNo = after(Regex("""(?i)invoice\s*no\.?\s*:?"""))?.split(' ')?.firstOrNull { t -> t.any(Char::isDigit) }
        val dateText = after(Regex("""(?i)(invoice\s*)?date\s*:?"""))
        val date = dateText?.let { DATE.find(it)?.value } ?: DATE.find(all)?.value
        return Header(
            supplierName = supplierName,
            supplierAddress = addressRows.joinToString(" ").ifEmpty { null },
            supplierGstin = supplierGstin, supplierContact = contact,
            invoiceNo = invoiceNo, invoiceDate = date,
            orderNo = after(Regex("""(?i)order\s*no\.?[^:]*:"""))?.split(' ')?.firstOrNull(),
            transporter = after(Regex("""(?i)transporter(\s*details)?\s*:?""")),
            cartons = after(Regex("""(?i)no\.?\s*of\s*cart(o|oo)ns?\s*:?""")),
            packing = after(Regex("""(?i)packing\s*/\s*cart(o|oo)ns?\s*:?""")),
            buyerName = buyerName, buyerGstin = buyerGstin,
        )
    }
}
