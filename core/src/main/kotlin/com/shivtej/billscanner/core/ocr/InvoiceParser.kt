package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Column
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineAt
import com.shivtej.billscanner.core.model.LineItem
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the scan produced, before the person reviews it. */
data class ParsedInvoice(
    val header: Header,
    val items: List<LineItem>,
    val notes: List<String>,
    /** The item table's columns as printed (labels in bill order). */
    val columns: List<Column> = emptyList(),
)

/**
 * Reads a supplier's tax invoice from OCR words — any layout, not one supplier's format.
 *
 * 1. Words are put back into lines ([Layout]), straightening tilt and bends.
 * 2. The item table is found from its header: the line with most column words
 *    (Sr, Item/Description, HSN, GST%, Size, MRP, Qty, Rate, Disc, Amount, Taxable, CGST …),
 *    including headers printed over 2–3 lines ("Discount" over "%" and "Amount").
 * 3. Every word of a table line goes under the column it sits in; column edges are learned from
 *    the table itself, so columns that lean in a photo are followed.
 * 4. Lines with a serial number (or several numbers) start an article; lines with only text continue it.
 * 5. Below the table: Total, CGST/SGST/IGST, round off and the bill total.
 * Every line is then checked with the bill's own arithmetic. Nothing missing is invented.
 */
object InvoiceParser {
    private val NUM = Regex("""^[(\-]?(?:₹|rs\.?)?(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.(\d{1,3}))?\)?[,.:;]?$""", RegexOption.IGNORE_CASE)
    private val MRP = Regex("""(?i)M\s*\.?\s*R\s*\.?\s*P\s*\.?\s*[:.;]?\s*(?:Rs\.?|₹)?\s*(\d{1,6}(?:\.\d{1,2})?)""")
    private val DATE = Regex("""\b(\d{1,2}[-/. ](?:[A-Za-z0][A-Za-z]{2,8}|\d{1,2})[-/. ]\d{2,4})\b""")
    /** Words that are labels on a bill, never a name. */
    private val LABELS = Regex("""(?i)^\s*(address|invoice|date|state|place|gstin|party|dc\s*no|eway|pay\s*mode|due|mobile|phone|contact)\b""")
    /** Words of the lines under an item table (tax, totals, bank, signature): such a line is not an article. */
    private val FOOTERISH = Regex("""(?i)\b(c\s*gst|s\s*gst|igst|utgst|hsn|taxable|total|round|bank|branch|a\s*[/l1i]\s*c|ifsc?|e\s*&\s*o|rupees|in\s*words|signatory|declaration)\b|\bfor\s*:""")
    /** Words that never belong to an article line (bank details, signature, the page's own labels). */
    val NOT_ARTICLE = Regex("""(?i)\b(bank|a\s*[/l1i]\s*c\s*(no|number)|account\s*no|ifsc?|branch|signature|signatory|declaration|jurisdiction|e\s*&\s*o\s*e|party'?s|total\s+\d)|\bfor\s*:""")
    /** Text this app prints on its own files (a photo PDF shared back into the app): never part of a bill. */
    private val OWN_TEXT = Regex("""(?i)(original\s+invoice\s+photo|copy\s+for\s+refere|shivtej\s+bill\s+scanner|not\s+an\s+official\s+digital|not\s+the\s+supplier'?s\s+original).*$""")
    private val DOTTED = Regex("""^\d{1,3}(\.\d{3})+\.\d{2}$""")
    private val UNITS = setOf("pair", "pairs", "prs", "pr", "pcs", "pc", "nos", "no", "set", "sets", "%", "pkt", "box", "dz", "doz")
    private val STOP = Regex("""(?i)\b(continued|amount\s*(rs\.?)?\s*in\s*words|rupees|e\s*&\s*o\s*e|our\s+bankers|bank\s+(name|details)|terms\s*(&|and)?\s*conditions|declaration|authori[sz]ed\s+signatory|output\s*[cs]gst|sub\s*-?\s*total|grand\s*total|round\s*off)\b""")
    private val CHARGE = Regex("""(?i)^(freight|transport|packing|forwarding|cartage|hamali|courier|insurance|postage|loading)""")

    /** Number in a cell: "1,049", "9.82 %", "6 pair", "₹ 1,07,350.00", "(0.42)". Null when it is not one number. */
    fun number(text: String): BigDecimal? {
        val parts = text.trim().replace("(-)", "-").replace(Regex("""^-\s+"""), "-").split(' ').map { it.trim() }.filter { it.isNotEmpty() && it.lowercase().trim('.', ':') !in UNITS && it != "₹" && !it.equals("rs", true) && !it.equals("rs.", true) }
        if (parts.size != 1) return null
        var t = parts[0].removeSuffix("%").removeSuffix("/-")
        // "2,504,44": a comma read for the decimal point.
        if (Regex("""^\d{1,3}(,\d{3})*,\d{2}$""").matches(t)) t = t.substring(0, t.lastIndexOf(',')) + "." + t.substring(t.lastIndexOf(',') + 1)
        // "6pair", "12prs", "5pcs" read as one word.
        Regex("""(?i)^([\d.,]+)(pairs?|prs?|pcs?|nos?|sets?)$""").matchEntire(t)?.let { t = it.groupValues[1] }
        // Letters OCR confuses with digits, only when the rest looks numeric.
        if (t.count { it.isDigit() } >= max(1, t.length / 2)) t = t.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
        val m = NUM.matchEntire(t) ?: return null
        val whole = m.groupValues[1].replace(",", "")
        val frac = m.groupValues[2]
        val v = BigDecimal(if (frac.isEmpty()) whole else "$whole.$frac")
        return if (t.startsWith("-") || (t.startsWith("(") && t.endsWith(")"))) v.negate() else v
    }

    private fun isNum(text: String) = number(text) != null

    fun parse(pages: List<OcrPage>, memory: LayoutMemory? = null): ParsedInvoice {
        var header = Header()
        val items = mutableListOf<LineItem>()
        val notes = mutableListOf<String>()
        var columns = emptyList<Column>()
        var footerNumbers = emptyList<BigDecimal>()
        pages.forEachIndexed { i, p ->
            val r = parsePage(p, items.size, memory, i)
            if (r.footerNumbers.isNotEmpty()) footerNumbers = r.footerNumbers
            header = merge(header, r.header, i == pages.lastIndex)
            items += r.items
            if (columns.isEmpty()) columns = r.columns
            notes += r.notes.map { if (pages.size > 1) "पेज ${i + 1}: $it" else it }
        }
        // The same bill added twice (a photo and its PDF, or one file twice): a serial number repeats with the same article.
        // Keep the better reading of each line, in the first one's place.
        run {
            fun key(i: LineItem) = i.current.article.uppercase().filter(Char::isLetterOrDigit)
            val drop = mutableSetOf<Int>()
            for (j in items.indices) {
                val sj = items[j].current.sr ?: continue
                fun same(a: LineItem, b: LineItem): Boolean {
                    if (a.current.sr != b.current.sr) return false
                    if (key(a).isNotEmpty() && key(a) == key(b)) return true
                    // Article text misread in one copy: the same serial with the same printed numbers is the same line.
                    fun eq(x: BigDecimal?, y: BigDecimal?) = x != null && y != null && x.compareTo(y) == 0
                    val va = a.current; val vb = b.current
                    return eq(va.taxable, vb.taxable) && (eq(va.mrp, vb.mrp) || eq(va.rate, vb.rate) || eq(va.qty, vb.qty))
                }
                val i = (0 until j).firstOrNull { it !in drop && same(items[it], items[j]) } ?: continue
                // The better reading: fewer doubtful fields, then the longer article text.
                val better = compareValuesBy(items[j], items[i], { -it.needs.size }, { it.current.article.length }) > 0
                if (better) items[i] = items[j]
                drop += j
            }
            if (drop.isNotEmpty()) {
                val kept = items.filterIndexed { k, _ -> k !in drop }
                items.clear(); items += kept
                notes += "एक ही बिल दो बार जुड़ा था — दोहराई गई ${drop.size} लाइनें हटाईं (हर लाइन की सबसे साफ़ पढ़ाई रखी)"
            }
        }
        if (items.isEmpty()) notes += "बिल में आर्टिकल की टेबल नहीं मिली। फ़ोटो साफ़ और पूरी हो, फिर दोबारा स्कैन करें या आर्टिकल हाथ से जोड़ें।"
        // A printed total that is nowhere near the lines is a misread (e.g. "2.00" taken from a page number).
        val lines = items.fold(BigDecimal.ZERO) { a, i -> a + (i.current.taxable ?: BigDecimal.ZERO) }
        if (lines.signum() > 0) {
            fun far(x: BigDecimal?, lo: String, hi: String) = x != null && (x < lines * BigDecimal(lo) || x > lines * BigDecimal(hi))
            if (far(header.printedGrandTotal, "0.6", "1.6")) { notes += "बिल का Total (${header.printedGrandTotal}) लाइनों से मेल नहीं खाता — गलत पढ़ा गया, छोड़ दिया"; header = header.copy(printedGrandTotal = null) }
            if (far(header.printedSubtotal, "0.6", "1.6")) header = header.copy(printedSubtotal = null, printedQty = null)
            val tax = listOfNotNull(header.cgstAmt, header.sgstAmt, header.igstAmt).fold(BigDecimal.ZERO) { a, b -> a + b }
            if (tax > lines * BigDecimal("0.3")) header = header.copy(cgstAmt = null, sgstAmt = null, igstAmt = null)
            // Bill total not read: the number below the table that equals lines + GST + round off is it.
            if (header.printedGrandTotal == null) {
                val tax2 = listOfNotNull(header.cgstAmt, header.sgstAmt, header.igstAmt).fold(BigDecimal.ZERO) { a, b -> a + b }
                val expected = lines + tax2 + (header.roundOff ?: BigDecimal.ZERO) + (header.otherCharges ?: BigDecimal.ZERO)
                footerNumbers.filter { (it - expected).abs() <= BigDecimal.ONE }.minByOrNull { (it - expected).abs() }
                    ?.let { header = header.copy(printedGrandTotal = it) }
            }
        }
        val healed = Heal.byColumns(items, columns)
        if (healed.fixed > 0) notes += "बिल के बाकी आर्टिकल देखकर ${healed.fixed} लाइनें खुद ठीक कीं (जैसे सब पर एक ही डिस्काउंट %/GST %, और हिसाब पूरा बैठा)"
        return ParsedInvoice(header, healed.items, notes, columns)
    }

    private fun merge(a: Header, b: Header, last: Boolean) = Header(
        supplierName = a.supplierName ?: b.supplierName, supplierAddress = a.supplierAddress ?: b.supplierAddress,
        supplierGstin = a.supplierGstin ?: b.supplierGstin, supplierContact = a.supplierContact ?: b.supplierContact,
        invoiceNo = a.invoiceNo ?: b.invoiceNo, invoiceDate = a.invoiceDate ?: b.invoiceDate, orderNo = a.orderNo ?: b.orderNo,
        transporter = a.transporter ?: b.transporter, cartons = a.cartons ?: b.cartons, packing = a.packing ?: b.packing,
        buyerName = a.buyerName ?: b.buyerName, buyerGstin = a.buyerGstin ?: b.buyerGstin,
        // Totals printed on a later page win.
        printedGrandTotal = b.printedGrandTotal ?: a.printedGrandTotal,
        overallDiscount = b.overallDiscount ?: a.overallDiscount, otherCharges = b.otherCharges ?: a.otherCharges,
        roundOff = b.roundOff ?: a.roundOff, pageNote = if (last) b.pageNote ?: a.pageNote else a.pageNote ?: b.pageNote,
        cgstPct = b.cgstPct ?: a.cgstPct, cgstAmt = b.cgstAmt ?: a.cgstAmt, sgstPct = b.sgstPct ?: a.sgstPct, sgstAmt = b.sgstAmt ?: a.sgstAmt,
        igstPct = b.igstPct ?: a.igstPct, igstAmt = b.igstAmt ?: a.igstAmt,
        printedQty = b.printedQty ?: a.printedQty, printedSubtotal = b.printedSubtotal ?: a.printedSubtotal,
    )

    // ================================================================ column words

    /** What a header word means. */
    enum class Role { SR, DESC, HSN, GST, SIZE, COLOUR, MRP, QTY, UNIT, RATE, DISC, TAXABLE, VALUE, AMOUNT, TOTAL, CGST, SGST, IGST, PCT, NOISE }

    private val ROLE_WORDS: Map<String, Role> = buildMap {
        listOf("sr", "srno", "sno", "slno", "sl", "s", "sn", "#", "srn").forEach { put(it, Role.SR) }
        listOf("item", "items", "description", "descriptions", "particular", "particulars", "product", "products", "goods", "article",
            "articles", "design", "style", "name", "model", "itemname", "itemdescription", "art", "artno").forEach { put(it, Role.DESC) }
        listOf("hsn", "hsnsac", "sac", "hsncode", "hsnsaccode").forEach { put(it, Role.HSN) }
        listOf("gst", "gst%", "gstrate", "tax%", "taxrate", "gstper", "gst%rate").forEach { put(it, Role.GST) }
        listOf("size", "sizes", "sz").forEach { put(it, Role.SIZE) }
        listOf("colour", "color", "clr", "shade", "col").forEach { put(it, Role.COLOUR) }
        listOf("mrp", "m.r.p", "mrp₹", "mrprs").forEach { put(it, Role.MRP) }
        listOf("qty", "quantity", "qnty", "quan", "qnt", "pcs", "pairs", "prs", "nos", "qtyprs", "qtypairs").forEach { put(it, Role.QTY) }
        listOf("unit", "uom", "per").forEach { put(it, Role.UNIT) }
        listOf("rate", "price", "ratepair", "unitprice", "rate₹", "raters").forEach { put(it, Role.RATE) }
        listOf("disc", "discount", "dis", "disc%", "cd", "cd%", "dis%", "discper", "d%", "less").forEach { put(it, Role.DISC) }
        listOf("taxable", "taxablevalue", "taxableamt", "taxableamount", "assessable").forEach { put(it, Role.TAXABLE) }
        listOf("value", "val").forEach { put(it, Role.VALUE) }
        listOf("amount", "amt", "amount₹", "amountrs", "netamount", "netamt", "net").forEach { put(it, Role.AMOUNT) }
        listOf("total", "totalamount", "totalamt", "gross", "grossamt").forEach { put(it, Role.TOTAL) }
        listOf("cgst", "cgst%").forEach { put(it, Role.CGST) }
        listOf("sgst", "utgst", "sgstutgst", "sgst%").forEach { put(it, Role.SGST) }
        listOf("igst", "igst%").forEach { put(it, Role.IGST) }
        listOf("%", "per%", "rate%").forEach { put(it, Role.PCT) }
        listOf("of", "in", "no", "code", "rs", "₹", "the", "&", "and").forEach { put(it, Role.NOISE) }
    }

    private fun norm(t: String) = t.lowercase().replace(Regex("""[^a-z0-9%#₹]"""), "")

    fun role(word: String): Role? {
        val n = norm(word)
        ROLE_WORDS[n]?.let { return it }
        if (n.endsWith("%")) ROLE_WORDS[n.dropLast(1)]?.let { return if (it == Role.DISC || it == Role.GST || it == Role.CGST || it == Role.SGST || it == Role.IGST) it else null }
        if (n.startsWith("pair")) return Role.QTY
        if (n.startsWith("rate")) return Role.RATE
        if (n.startsWith("qty")) return Role.QTY
        if (n.startsWith("disc")) return Role.DISC
        if (n.startsWith("descr")) return Role.DESC
        if (n.startsWith("amount")) return Role.AMOUNT
        if (n.startsWith("srno") || n == "sr" || n == "sno" || n == "slno" || n == "sn") return Role.SR
        return null
    }

    private val MAIN = setOf(Role.SR, Role.DESC, Role.HSN, Role.GST, Role.SIZE, Role.COLOUR, Role.MRP, Role.QTY, Role.RATE, Role.DISC,
        Role.TAXABLE, Role.AMOUNT, Role.TOTAL, Role.CGST, Role.SGST, Role.IGST)

    /** One column of the item table. */
    private class Col(var label: String, var l: Double, var r: Double, val roles: MutableList<Role>) {
        var kind: Kind = Kind.OTHER
        /** Set by what the person taught: never re-guessed. */
        var locked = false
        val cx get() = (l + r) / 2
    }

    enum class Kind { SR, DESC, HSN, GST_PCT, SIZE, COLOUR, MRP, QTY, UNIT, RATE, GROSS, DISC_PCT, DISC_AMT, TAXABLE, CGST_PCT, CGST_AMT, SGST_PCT, SGST_AMT, IGST_PCT, IGST_AMT, TOTAL, AMOUNT, OTHER }

    private val NUMERIC = setOf(Kind.MRP, Kind.QTY, Kind.RATE, Kind.GROSS, Kind.DISC_PCT, Kind.DISC_AMT, Kind.TAXABLE, Kind.CGST_PCT, Kind.CGST_AMT,
        Kind.SGST_PCT, Kind.SGST_AMT, Kind.IGST_PCT, Kind.IGST_AMT, Kind.TOTAL, Kind.AMOUNT)

    // ================================================================ one page

    private data class PageResult(val header: Header, val items: List<LineItem>, val notes: List<String>, val columns: List<Column>,
                                  val footerNumbers: List<BigDecimal> = emptyList())

    private fun headerScore(r: Row): Int {
        val roles = r.words.mapNotNull { role(it.text) }.filter { it in MAIN }.toSet()
        val bigNumbers = r.words.count { w -> number(w.text)?.let { it > BigDecimal(99) && w.text.contains('.') } == true }
        return if (bigNumbers >= 2) 0 else roles.size
    }

    private class Band(val start: Int, val best: Int, val end: Int)

    private class TableResult(val items: List<LineItem>, val footer: Footer, val cols: List<Col>, val footerNumbers: List<BigDecimal>)

    private fun parsePage(page: OcrPage, startIndex: Int, memory: LayoutMemory?, pageNo: Int = 0): PageResult {
        val base = Layout.dedupe(Layout.clean(page.words))
        val slope = Layout.skew(base)
        val words = Layout.straighten(base, slope)
        val rows = Layout.lines(words)
        val mh = Layout.medianHeight(words)
        val notes = mutableListOf<String>()

        val scores = rows.map { headerScore(it) }
        val top = scores.maxOrNull() ?: 0
        // A page without an item table may still hold the bill's totals (the last page of a long bill).
        fun noTable(): PageResult {
            val h = readHeader(rows, rows.size, mh, words)
            val f = readFooter(footerZone(rows), emptyList(), Bounds(DoubleArray(0)), mh)
            return PageResult(
                h.copy(pageNote = pageNote(rows), printedGrandTotal = f.grand, roundOff = f.roundOff, otherCharges = f.other, overallDiscount = f.discount,
                    cgstPct = f.cgstPct, cgstAmt = f.cgstAmt, sgstPct = f.sgstPct, sgstAmt = f.sgstAmt, igstPct = f.igstPct, igstAmt = f.igstAmt,
                    printedQty = f.qty, printedSubtotal = f.subtotal),
                emptyList(), listOf("आर्टिकल टेबल का हेडर (Item, Qty, Rate, Amount…) नहीं मिला"), emptyList(),
                rows.flatMap { r -> r.words.mapNotNull { w -> number(w.text) } },
            )
        }
        if (top < 3) return noTable()
        fun looksLikeData(r: Row) = r.words.count { isNum(it.text) && (number(it.text)!! > BigDecimal(99) || it.text.contains('.')) } >= 2
        fun band(best: Int): Band {
            var bandStart = best
            val above = rows.getOrNull(best - 1)
            if (above != null && rows[best].y - above.y < 1.7 * mh && !looksLikeData(above) &&
                above.words.count { role(it.text) in MAIN } >= 2 && above.words.count { role(it.text) != null } * 2 >= above.words.size) bandStart = best - 1
            var bandEnd = best
            while (bandEnd + 1 < rows.size && bandEnd - best < 3 && rows[bandEnd + 1].y - rows[bandEnd].y < 2.2 * mh && !looksLikeData(rows[bandEnd + 1]) &&
                rows[bandEnd + 1].words.none { w -> w.text.trim('.').toIntOrNull()?.let { it in 1..999 } == true && w.box.l < rows[best].words.first().box.l + 3 * mh }) bandEnd++
            return Band(bandStart, best, bandEnd)
        }
        // One photo can hold more than one table heading (e.g. a screenshot of a 2-page PDF): each part is read with its own.
        val bands = mutableListOf<Band>()
        fun mainRoles(r: Row) = r.words.mapNotNull { role(it.text) }.filter { it in MAIN }.toSet()
        val bestRow = scores.indexOf(top)
        for (i in rows.indices) {
            // The same heading printed again lower down (page 2 in the same photo), even if partly misread or covered.
            val repeat = scores[i] >= 3 && i != bestRow && Role.DESC in mainRoles(rows[i]) && (mainRoles(rows[i]) intersect mainRoles(rows[bestRow])).size >= 3 &&
                rows.getOrNull(i + 1)?.let { looksLikeData(it) || rows.getOrNull(i + 2)?.let(::looksLikeData) == true } == true
            if (scores[i] < maxOf(3, top - 1) && !repeat) continue
            if (bands.isNotEmpty() && i <= bands.last().end + 1) continue
            val b = band(i)
            // An item table has a description, quantity or serial column; the HSN/tax summary under a Tally bill has none.
            val bandRoles = (b.start..b.end).flatMap { mainRoles(rows[it]) }.toSet()
            if (bandRoles.none { it == Role.DESC || it == Role.QTY || it == Role.SR }) continue
            bands += b
        }
        if (bands.isEmpty()) return noTable()
        val header = readHeader(rows, bands.first().start, mh, words)
        val results = mutableListOf<TableResult>()
        var next = startIndex
        // Article lines above the first heading (the photo starts in the middle of a table).
        val pre = rows.subList(0, bands.first().start)
        // A heading that reads worse than the best one on the page uses the best one's words (same bill, same columns).
        val bestBand = bands.maxByOrNull { scores[it.best] }!!
        fun cols(b: Band) = columns(rows.subList(b.start, b.end + 1), mh).size
        fun headingOf(b: Band) = if (b !== bestBand && (scores[b.best] < scores[bestBand.best] - 1 || cols(b) != cols(bestBand))) bestBand else b
        for ((k, b0) in bands.withIndex()) {
            val b = headingOf(b0)
            val body = rows.subList(b0.end + 1, bands.getOrNull(k + 1)?.start ?: rows.size)
            // Same heading as a table already read: its columns mean what they meant there.
            val known = if (b !== b0 || (k > 0 && results.isNotEmpty())) results.lastOrNull { it.items.size >= 5 }?.cols?.associate { it.label to it.kind } else null
            val r = readTable(rows.subList(b.start, b.end + 1), body, mh, memory, header, next, known = known)
            if (k == 0 && pre.size >= 3) {
                val before = readTable(rows.subList(b.start, b.end + 1), pre, mh, memory, header, next, requireSerialRun = true)
                if (before.items.size >= 3) { results += before; next += before.items.size }
            }
            val again = if (results.isNotEmpty() && k == 0) readTable(rows.subList(b.start, b.end + 1), body, mh, memory, header, next) else r
            results += again; next += again.items.size
        }
        // Line positions back from the straightened words to the page as photographed.
        val th = kotlin.math.atan(slope); val cs = kotlin.math.cos(th); val sn = kotlin.math.sin(th)
        fun unturn(x: Double, y: Double) = (x * cs - y * sn) to (x * sn + y * cs)
        val items = results.flatMap { it.items }.map { it ->
            val a = it.at ?: return@map it
            val pts = listOf(unturn(a.l.toDouble(), a.t.toDouble()), unturn(a.r.toDouble(), a.t.toDouble()), unturn(a.l.toDouble(), a.b.toDouble()), unturn(a.r.toDouble(), a.b.toDouble()))
            it.copy(at = LineAt(pageNo, pts.minOf { p -> p.first }.toInt().coerceAtLeast(0), pts.minOf { p -> p.second }.toInt().coerceAtLeast(0),
                pts.maxOf { p -> p.first }.toInt().coerceAtMost(page.width), pts.maxOf { p -> p.second }.toInt().coerceAtMost(page.height), page.width, page.height))
        }
        val f = results.map { it.footer }
        fun <T> last(g: (Footer) -> T?): T? = f.lastOrNull { g(it) != null }?.let(g)
        var footer = Footer(last { it.qty }, last { it.subtotal }, last { it.grand }, last { it.roundOff }, last { it.other }, last { it.discount },
            last { it.cgstPct }, last { it.cgstAmt }, last { it.sgstPct }, last { it.sgstAmt }, last { it.igstPct }, last { it.igstAmt })
        // No totals under the table (e.g. a crooked last page where the heading was not found): look for them anywhere on the page.
        if (footer.grand == null && footer.subtotal == null && footer.cgstAmt == null && footer.igstAmt == null) {
            val whole = readFooter(footerZone(rows), emptyList(), Bounds(DoubleArray(0)), mh)
            if (whole.grand != null || whole.cgstAmt != null || whole.igstAmt != null) footer = whole
        }
        if (items.isEmpty()) notes += "टेबल मिली पर कोई आर्टिकल लाइन नहीं पढ़ी जा सकी"
        val cols = results.firstOrNull { it.items.isNotEmpty() }?.cols ?: results.first().cols
        return PageResult(
            header.copy(
                printedGrandTotal = footer.grand, roundOff = footer.roundOff, otherCharges = footer.other, overallDiscount = footer.discount,
                cgstPct = footer.cgstPct, cgstAmt = footer.cgstAmt, sgstPct = footer.sgstPct, sgstAmt = footer.sgstAmt,
                igstPct = footer.igstPct, igstAmt = footer.igstAmt, printedQty = footer.qty, printedSubtotal = footer.subtotal,
                pageNote = pageNote(rows),
            ),
            items, notes, cols.map { Column(it.label, fieldOf(it.kind)) }, results.flatMap { it.footerNumbers },
        )
    }

    /** Reads one item table: [band] = its heading lines, [body] = the lines below it (until the next heading). */
    private fun readTable(band: List<Row>, body: List<Row>, mh: Double, memory: LayoutMemory?, header: Header, startIndex: Int,
                          requireSerialRun: Boolean = false, known: Map<String, Kind>? = null): TableResult {
        // Read as the heading says, and once more moved to where the lines fit their headings best;
        // keep the reading under which the bill's arithmetic works out on more lines.
        val first = readTableAt(band, body, mh, memory, header, startIndex, requireSerialRun, 0.0, known)
        val dx = bestOffset(body.take(60), columns(band, mh), mh)
        if (dx == 0.0) return first
        val second = readTableAt(band, body, mh, memory, header, startIndex, requireSerialRun, dx, known)
        fun good(t: TableResult) = t.items.count { val v = it.current; v.qty != null && v.taxable != null && v.mrp != null && (v.rate != null) && Calc.checkLine(v).isEmpty() }
        return if (good(second) > good(first)) second else first
    }

    private fun readTableAt(band: List<Row>, body: List<Row>, mh: Double, memory: LayoutMemory?, header: Header, startIndex: Int,
                            requireSerialRun: Boolean, offset: Double, known: Map<String, Kind>? = null): TableResult {
        var cols = columns(band, mh)
        if (known != null) for (c in cols) known[c.label]?.let { c.kind = it; c.locked = true }
        // What the person taught for this supplier's columns comes first.
        if (memory != null) for (c in cols) memory.kindFor(header, c.label)?.let { c.kind = it; c.locked = true }

        // ---- table lines
        val tableRows = mutableListOf<Row>()
        var footerAt = body.size
        for ((i, r) in body.withIndex()) {
            if (STOP.containsMatchIn(r.text) && !hasSerial(r, cols, mh)) { footerAt = i; break }
            if (Regex("""(?i)^\s*total\b""").containsMatchIn(r.text) || (r.words.firstOrNull()?.text?.equals("total", true) == true)) { footerAt = i; break }
            tableRows += r
        }

        // Words to columns, following columns that lean in the photo.
        var (cellRows, bounds, shifts) = assignTable(tableRows, cols, mh, offset)
        // A heading the photo lost (two columns of numbers under one heading): split it, then work out what it is.
        splitColumns(cols, cellRows, shifts, mh)?.let { c ->
            cols = c
            assignTable(tableRows, cols, mh, offset).let { cellRows = it.first; bounds = it.second; shifts = it.third }
        }
        checkColumns(cols, cellRows)
        inferKinds(cols, cellRows)
        solveByArithmetic(cols, cellRows)
        val assigned = tableRows.zip(cellRows)

        // ---- items: a line with a serial number (or 2+ numbers) starts an item; text-only lines continue it
        val srCol = cols.indexOfFirst { it.kind == Kind.SR }
        val blocks = mutableListOf<MutableList<Map<Int, List<OcrWord>>>>()
        fun startsItem(cells: Map<Int, List<OcrWord>>): Boolean {
            val sr = if (srCol >= 0) cells[srCol]?.let { ws -> serialOf(ws.joinToString("") { it.text }) } else null
            val numericCells = cells.count { (ci, ws) -> cols[ci].kind in NUMERIC && number(ws.joinToString(" ") { it.text }) != null }
            val hasText = cells.any { (ci, ws) -> cols[ci].kind == Kind.DESC && ws.isNotEmpty() }
            // A full line of numbers with its description is an article even when the photo lost its serial number.
            val text = cells.values.flatten().joinToString(" ") { it.text }
            val fullLine = !requireSerialRun && hasText && numericCells >= 3 && !FOOTERISH.containsMatchIn(text)
            return if (srCol >= 0) (sr != null && (numericCells >= (if (requireSerialRun) 3 else 1) || (!requireSerialRun && hasText))) || fullLine
            else numericCells >= 2 && hasText && !FOOTERISH.containsMatchIn(text)
        }
        // A line without a serial number counts only inside the run of numbered lines, or when its own arithmetic works.
        val serialAt = assigned.indexOfLast { (_, c) -> srCol >= 0 && c[srCol]?.let { ws -> serialOf(ws.joinToString("") { it.text }) } != null }
        fun fits(cells: Map<Int, List<OcrWord>>): Boolean {
            fun v(k: Kind) = cols.indexOfFirst { it.kind == k }.takeIf { it >= 0 }?.let { ci -> number(cellText(cells[ci])) }
            val q = v(Kind.QTY) ?: return false; val r = v(Kind.RATE) ?: return false
            val t = v(Kind.TAXABLE) ?: v(Kind.AMOUNT) ?: v(Kind.GROSS) ?: return false
            val d = v(Kind.DISC_PCT) ?: BigDecimal.ZERO
            val e = q * r * (BigDecimal(100) - d) / BigDecimal(100)
            return (e - t).abs() <= BigDecimal("0.06") + t.abs() * BigDecimal("0.002") || (q * r - t).abs() <= BigDecimal("0.06")
        }
        val starts = assigned.mapIndexed { idx, (_, cells) ->
            val hasSr = srCol >= 0 && cells[srCol]?.let { ws -> serialOf(ws.joinToString("") { it.text }) } != null
            startsItem(cells) && (srCol < 0 || hasSr || idx < serialAt || fits(cells))
        }
        val lastStart = starts.lastIndexOf(true)
        var lastItemRowIdx = -1
        fun numericOf(cells: Map<Int, List<OcrWord>>) = cells.filter { (ci, ws) -> cols[ci].kind in NUMERIC && number(ws.joinToString(" ") { it.text }) != null }.keys
        // The numbers of a line the photo split in two may come just before its text part.
        var pending: Map<Int, List<OcrWord>>? = null
        for ((idx, pair) in assigned.withIndex()) {
            val (_, cells) = pair
            val numericCells = numericOf(cells).size
            val blockNums = blocks.lastOrNull()?.flatMap { numericOf(it) }?.toSet().orEmpty()
            when {
                starts[idx] -> {
                    val p = pending
                    blocks += if (p != null && numericCells == 0) mutableListOf(cells, p) else mutableListOf(cells)
                    pending = null; lastItemRowIdx = idx
                }
                blocks.isNotEmpty() && idx <= lastStart && numericCells >= 3 && blockNums.containsAll(numericOf(cells)) && starts.getOrElse(idx + 1) { false } -> pending = cells
                blocks.isEmpty() -> {}
                // A lone number under the amount columns after the last item = the table total box.
                idx > lastStart && cells.keys.none { cols[it].kind == Kind.DESC || cols[it].kind == Kind.SR || cols[it].kind == Kind.HSN } &&
                    cells.values.flatten().all { isNum(it.text) } && cells.values.flatten().size in 1..2 && numericCells >= 1 -> {
                    footerAt = body.indexOf(pair.first); break
                }
                idx - lastItemRowIdx > 6 -> {}  // far below the last item: not part of it
                else -> { blocks.last() += cells; lastItemRowIdx = idx }
            }
        }
        val items = blocks.mapIndexed { k, b -> readItem(b, cols, startIndex + k + 1) }.toMutableList()
        // Text with numbers but no serial, pairs, MRP or rate (bank details, a tax summary) is not an article;
        // nor a line that names a bank, account, IFSC, signature… without adding up as an article.
        val junk = items.indices.filter { k ->
            val it = items[k]; val v = it.current
            (v.sr == null && v.qty == null && v.mrp == null && v.rate == null) ||
                (NOT_ARTICLE.containsMatchIn(it.raw.joinToString(" ") + " " + v.article) && !Heal.proven(v))
        }.toSet()
        if (junk.isNotEmpty()) {
            val keepAt = items.indices.filter { it !in junk }
            val kb = keepAt.map { blocks[it] }; val ki = keepAt.map { items[it] }
            blocks.clear(); blocks.addAll(kb); items.clear(); items.addAll(ki)
        }
        // Serial numbers the photo lost: the one between its neighbours, or glued to the description ("31251-…" = 3 + 1251-…).
        if (srCol >= 0) for (k in items.indices.toList() + items.indices.reversed()) {
            if (items[k].current.sr != null) continue
            val prev = items.getOrNull(k - 1)?.current?.sr
            val next = items.getOrNull(k + 1)?.current?.sr
            val expect = when {
                prev != null && (next == null || next == prev + 2) -> prev + 1
                prev == null && next != null && next > 1 -> next - 1
                else -> continue
            }
            val b = blocks[k]
            val descCol = cols.indexOfFirst { it.kind == Kind.DESC }
            val w = if (descCol >= 0) b.first()[descCol]?.firstOrNull() else null
            val restText = w?.text?.takeIf { it.startsWith("$expect") }?.substring(expect.toString().length)?.trimStart('.', '/', ',', ')', '|')
            val glued = w != null && w.box.l < cols[srCol].r + mh && restText != null && restText.length >= 2
            items[k] = if (glued) {
                val rest = OcrWord(restText!!, w!!.box)
                val first = b.first().toMutableMap()
                first[srCol] = listOf(OcrWord("$expect.", w.box))
                first[descCol] = listOf(rest) + b.first()[descCol]!!.drop(1)
                readItem(listOf(first) + b.drop(1), cols, startIndex + k + 1)
            } else items[k].let { it.copy(current = it.current.copy(sr = expect), original = it.original.copy(sr = expect)) }
        }
        // Where each line is (straightened coordinates; turned back to the photo by the page).
        for (k in items.indices) {
            val ws = blocks[k].flatMap { it.values.flatten() }
            if (ws.isNotEmpty()) items[k] = items[k].copy(at = LineAt(0, ws.minOf { it.box.l }, ws.minOf { it.box.t }, ws.maxOf { it.box.r }, ws.maxOf { it.box.b }, 0, 0))
        }
        val footRows = body.drop(footerAt)
        val footer = readFooter(footRows, cols, bounds, mh)
        val numbers = footRows.flatMap { r -> r.words.mapNotNull { w -> number(w.text) } }
        // A heading word with (almost) nothing under it, e.g. a stamp beside the table: not a column of the bill.
        val keep = cols.indices.filter { ci -> cols[ci].kind != Kind.OTHER || items.isEmpty() || items.count { it.raw.getOrNull(ci)?.isNotBlank() == true } * 3 > items.size }
        if (keep.size < cols.size) {
            val kept = keep.map { cols[it] }
            return TableResult(items.map { it.copy(raw = keep.map { ci -> it.raw.getOrElse(ci) { "" } }) }, footer, kept, numbers)
        }
        return TableResult(items, footer, cols, numbers)
    }

    /** "12.", "12)", "3(", "41/": a serial number with the dot or line next to it misread. */
    private fun serialOf(t: String): Int? = t.trim().trimEnd('.', ')', '(', ',', '/', '|', ':', ' ').trimStart('(').toIntOrNull()?.takeIf { it in 1..9999 }

    private fun hasSerial(r: Row, cols: List<Col>, mh: Double): Boolean {
        val sr = cols.firstOrNull { it.kind == Kind.SR } ?: return false
        val w = r.words.firstOrNull() ?: return false
        return w.box.cx < sr.r + 2 * mh && w.text.trim('.', ')').toIntOrNull() != null
    }

    private fun pageNote(rows: List<Row>): String? =
        rows.firstNotNullOfOrNull { Regex("""(?i)page\s*(no\.?)?\s*(\d+)\s*(of|/)\s*(\d+)""").find(it.text)?.value }
            ?: rows.firstNotNullOfOrNull { r -> Regex("""(?i)\(?page\s*(\d+)\)?""").find(r.text)?.let { "Page ${it.groupValues[1]}" } }
            ?: if (rows.any { Regex("""(?i)\bcontinued\b|\bc/f\b|carried\s*forward""").containsMatchIn(it.text) }) "continued" else null

    fun fieldOf(k: Kind): F? = when (k) {
        Kind.SR -> F.SR; Kind.DESC -> F.ARTICLE; Kind.HSN -> F.HSN; Kind.GST_PCT -> F.GST_PCT; Kind.SIZE -> F.SIZE; Kind.COLOUR -> F.COLOUR
        Kind.MRP -> F.MRP; Kind.QTY -> F.QTY; Kind.RATE -> F.RATE; Kind.GROSS -> F.GROSS; Kind.DISC_PCT -> F.DISC_PCT; Kind.DISC_AMT -> F.DISC_AMT
        Kind.TAXABLE -> F.TAXABLE; Kind.CGST_PCT -> F.CGST_PCT; Kind.CGST_AMT -> F.CGST_AMT; Kind.SGST_PCT -> F.SGST_PCT; Kind.SGST_AMT -> F.SGST_AMT
        Kind.IGST_PCT -> F.IGST_PCT; Kind.IGST_AMT -> F.IGST_AMT; Kind.TOTAL -> F.TOTAL; Kind.AMOUNT -> F.TAXABLE; Kind.UNIT, Kind.OTHER -> null
    }

    // ================================================================ header → columns

    private class Phrase(val words: MutableList<OcrWord>) {
        val l get() = words.first().box.l.toDouble()
        val r get() = words.last().box.r.toDouble()
        val text get() = words.joinToString(" ") { it.text }
        val roles get() = words.mapNotNull { role(it.text) }
    }

    private fun joinable(have: List<Role>, next: Role?): Boolean {
        if (next == null || next == Role.NOISE || next == Role.UNIT) return true
        val last = have.lastOrNull { it != Role.NOISE && it != Role.UNIT } ?: return true
        if (next == last) return true
        if (next == Role.PCT && last in setOf(Role.GST, Role.DISC, Role.CGST, Role.SGST, Role.IGST)) return true
        if (next == Role.VALUE && last in setOf(Role.TAXABLE, Role.TOTAL, Role.AMOUNT)) return true
        if (next == Role.AMOUNT && last == Role.TOTAL) return true
        if (next == Role.QTY && last == Role.RATE) return true     // "Rate/Pair"
        return false
    }

    private fun columns(band: List<Row>, mh: Double): List<Col> {
        // Phrases per line: words close together that belong to one heading.
        val lines = band.map { r ->
            val out = mutableListOf<Phrase>()
            for (w in r.words) {
                val p = out.lastOrNull()
                if (p != null && w.box.l - p.r <= 0.9 * mh && joinable(p.roles, role(w.text))) p.words += w else out += Phrase(mutableListOf(w))
            }
            out
        }
        // Bottom line first: its phrases are columns; a heading above covering 2+ columns is a group title ("Discount" over "%", "Amount").
        val cols = mutableListOf<Col>()
        for (line in lines.reversed()) {
            val before = cols.toList()
            for (p in line) {
                val pad = 0.6 * mh
                val under = before.filter { c -> min(c.r, p.r + pad) - max(c.l, p.l - pad) > 0 }
                when {
                    under.isEmpty() -> cols += Col(p.text, p.l, p.r, p.roles.toMutableList())
                    under.size == 1 -> under[0].let { c -> c.label = "${p.text} ${c.label}"; c.l = min(c.l, p.l); c.r = max(c.r, p.r); c.roles.addAll(0, p.roles) }
                    else -> under.forEach { c -> c.label = "${p.text} ${c.label}"; c.roles.addAll(0, p.roles) }
                }
            }
        }
        cols.sortBy { it.l }
        // Meaning of each column.
        for (c in cols) {
            val rs = c.roles
            val main = rs.firstOrNull { it in MAIN }
            c.kind = when {
                Role.CGST in rs -> if (Role.AMOUNT in rs || Role.VALUE in rs) Kind.CGST_AMT else if (Role.PCT in rs || c.label.contains('%')) Kind.CGST_PCT else Kind.CGST_AMT
                Role.SGST in rs -> if (Role.AMOUNT in rs || Role.VALUE in rs) Kind.SGST_AMT else if (Role.PCT in rs || c.label.contains('%')) Kind.SGST_PCT else Kind.SGST_AMT
                Role.IGST in rs -> if (Role.AMOUNT in rs || Role.VALUE in rs) Kind.IGST_AMT else if (Role.PCT in rs || c.label.contains('%')) Kind.IGST_PCT else Kind.IGST_AMT
                main == Role.DISC -> if (Role.AMOUNT in rs || Role.VALUE in rs) Kind.DISC_AMT else Kind.DISC_PCT
                main == Role.SR -> Kind.SR
                main == Role.DESC -> Kind.DESC
                main == Role.HSN -> Kind.HSN
                main == Role.GST -> if (Role.AMOUNT in rs) Kind.OTHER else Kind.GST_PCT
                main == Role.SIZE -> Kind.SIZE
                main == Role.COLOUR -> Kind.COLOUR
                main == Role.MRP -> Kind.MRP
                main == Role.QTY -> Kind.QTY
                main == Role.RATE -> Kind.RATE
                main == Role.TAXABLE -> Kind.TAXABLE
                main == Role.TOTAL -> Kind.TOTAL
                main == Role.AMOUNT -> Kind.AMOUNT
                rs.contains(Role.UNIT) -> Kind.UNIT
                rs.contains(Role.VALUE) -> Kind.AMOUNT
                else -> Kind.OTHER
            }
        }
        // A lone "%" heading just before a tax or discount amount ("CGST" printed over the amount only) is that tax's %.
        for (i in 0 until cols.size - 1) {
            val c = cols[i]
            if (c.kind != Kind.OTHER || norm(c.label) != "%") continue
            c.kind = when (cols[i + 1].kind) {
                Kind.CGST_AMT -> Kind.CGST_PCT; Kind.SGST_AMT -> Kind.SGST_PCT; Kind.IGST_AMT -> Kind.IGST_PCT; Kind.DISC_AMT -> Kind.DISC_PCT
                else -> Kind.OTHER
            }
            if (c.kind != Kind.OTHER) c.label = cols[i + 1].label.split(' ').first() + " %"
        }
        // A heading like "Sr" may be missing: the first column holding small numbers is found later; a missing
        // description heading is the widest text column next to Sr.
        if (cols.none { it.kind == Kind.DESC }) cols.firstOrNull { it.kind == Kind.OTHER }?.kind = Kind.DESC
        // "Total"/"Amount" before the discount = qty × rate; the last amount after it is the line value.
        val discAt = cols.indexOfFirst { it.kind == Kind.DISC_PCT || it.kind == Kind.DISC_AMT }
        val hasTaxable = cols.any { it.kind == Kind.TAXABLE }
        val hasLineTax = cols.any { it.kind in setOf(Kind.CGST_AMT, Kind.SGST_AMT, Kind.IGST_AMT) }
        cols.forEachIndexed { i, c ->
            if ((c.kind == Kind.TOTAL || c.kind == Kind.AMOUNT) && discAt > i) c.kind = Kind.GROSS
        }
        val amounts = cols.withIndex().filter { it.value.kind == Kind.TOTAL || it.value.kind == Kind.AMOUNT }
        for ((n, iv) in amounts.withIndex()) {
            val c = iv.value
            val isLast = n == amounts.lastIndex
            c.kind = when {
                !isLast -> if (!hasTaxable && iv.index < (cols.indexOfFirst { it.kind in setOf(Kind.CGST_AMT, Kind.SGST_AMT, Kind.IGST_AMT, Kind.CGST_PCT, Kind.SGST_PCT, Kind.IGST_PCT) }.takeIf { it >= 0 } ?: Int.MAX_VALUE)) Kind.AMOUNT else Kind.GROSS
                hasLineTax && iv.index > cols.indexOfLast { it.kind in setOf(Kind.CGST_AMT, Kind.SGST_AMT, Kind.IGST_AMT) } -> Kind.TOTAL
                hasTaxable -> Kind.TOTAL
                else -> Kind.AMOUNT
            }
        }
        // Two columns can't both be the line amount.
        if (cols.count { it.kind == Kind.AMOUNT } > 1) cols.filter { it.kind == Kind.AMOUNT }.dropLast(1).forEach { it.kind = Kind.GROSS }
        return cols
    }

    // ================================================================ words → cells

    /** Column edges from the headings; [shift] moves them all sideways (a photo's columns lean). */
    private class Bounds(val edges: DoubleArray, val shift: Double = 0.0) {
        fun at(i: Int) = edges[i] + shift
        fun shifted(dx: Double) = Bounds(edges, shift + dx)

        companion object {
            fun fromHeader(cols: List<Col>): Bounds {
                val n = max(0, cols.size - 1)
                return Bounds(DoubleArray(n) { (cols[it].r + cols[it + 1].l) / 2 })
            }
        }
    }

    private fun median(xs: List<Double>): Double = xs.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    /** Edge of a cell that lines up down a column: numbers line up on the right, text on the left. */
    private fun edge(ws: List<OcrWord>, numeric: Boolean): Double =
        if (numeric) ws.maxOf { it.box.r }.toDouble() else ws.minOf { it.box.l }.toDouble()

    /**
     * Assigns every table line, top to bottom, following how far the columns have drifted sideways
     * since the heading (a bent or leaning photo). Returns the cells of each line and the drift at the end.
     */
    /** How well a line's words fit the columns they fell into (numbers under number headings, sizes under Size…). */
    private fun fit(cells: Map<Int, List<OcrWord>>, cols: List<Col>): Int {
        var sc = 0
        for ((ci, ws) in cells) {
            val t = cellText(ws)
            val n = numberWords(ws).size
            when (cols[ci].kind) {
                in NUMERIC -> sc += when { n == 1 -> 2; n == 0 -> -1; else -> -2 * (n - 1) }
                Kind.HSN -> sc += if (Regex("""^\d{4,8}$""").matches(t.replace(" ", ""))) 1 else -1
                Kind.GST_PCT -> sc += if (number(t)?.let { it <= BigDecimal(28) } == true) 1 else -1
                Kind.SIZE -> sc += if (Regex("""\d\s*[xX×]\s*\d""").containsMatchIn(t)) 1 else if (n > 0) -1 else 0
                Kind.SR -> sc += if (t.trim('.', ')').toIntOrNull() != null) 1 else -1
                Kind.DESC -> sc += if (t.any { it.isLetter() }) 1 else -1
                else -> {}
            }
        }
        return sc
    }

    /** Sideways offset under which the lines fit their column headings best (another page in the same photo). */
    private fun bestOffset(rows: List<Row>, cols: List<Col>, mh: Double): Double {
        val b = Bounds.fromHeader(cols)
        val sample = rows.filter { r -> r.words.count { isNum(it.text) } >= 3 }.take(20)
        if (sample.size < 3) return 0.0
        var bestShift = 0.0; var bestScore = sample.sumOf { fit(assign(it, cols, b, mh), cols) }
        var dx = -4 * mh
        while (dx <= 4 * mh + 0.1) {
            val sc = sample.sumOf { fit(assign(it, cols, b.shifted(dx), mh), cols) }
            if (sc > bestScore || (sc == bestScore && abs(dx) < abs(bestShift))) { bestScore = sc; bestShift = dx }
            dx += mh / 4
        }
        return bestShift
    }

    private fun assignTable(rows: List<Row>, cols: List<Col>, mh: Double, offset: Double = 0.0): Triple<List<Map<Int, List<OcrWord>>>, Bounds, List<Double>> {
        val shifts = mutableListOf<Double>()
        var b = Bounds.fromHeader(cols).shifted(offset)
        val refs = Array(cols.size) { mutableListOf<Double>() }
        val out = mutableListOf<Map<Int, List<OcrWord>>>()
        fun numericCells(cells: Map<Int, List<OcrWord>>) = cells.filter { (ci, ws) -> ws.isNotEmpty() && cols[ci].kind in NUMERIC && isNum(cellText(ws)) }
        for (r in rows) {
            var cells = assign(r, cols, b, mh)
            // Only full article lines (3+ numbers) are used to follow the drift.
            val nc = numericCells(cells)
            if (nc.size >= 3) {
                val res = nc.mapNotNull { (ci, ws) -> if (refs[ci].size < 2) null else edge(ws, true) - b.shift - median(refs[ci]) }
                if (res.size >= 2) {
                    val d = median(res).coerceIn(-1.2 * mh, 1.2 * mh)
                    if (abs(d) > 0.5) { b = b.shifted(d); cells = assign(r, cols, b, mh) }
                }
                for ((ci, ws) in numericCells(cells)) { refs[ci] += edge(ws, true) - b.shift; if (refs[ci].size > 12) refs[ci].removeAt(0) }
            }
            out += cells; shifts += b.shift
        }
        return Triple(out, b, shifts)
    }

    private fun numberWords(ws: List<OcrWord>): List<OcrWord> = ws.filter { isNum(it.text) && it.text.lowercase().trim('.', ':') !in UNITS }
    private fun isItemLike(cells: Map<Int, List<OcrWord>>, cols: List<Col>) = cells.count { (ci, ws) -> cols[ci].kind != Kind.SR && numberWords(ws).isNotEmpty() } >= 3

    /** Splits a column whose cells keep holding two separate numbers (a heading the OCR missed). */
    private fun splitColumns(cols: List<Col>, rows: List<Map<Int, List<OcrWord>>>, shifts: List<Double>, mh: Double): List<Col>? {
        val items = rows.indices.filter { isItemLike(rows[it], cols) }
        if (items.size < 3) return null
        for ((ci, c) in cols.withIndex()) {
            if (c.kind == Kind.DESC || c.kind == Kind.SR || c.kind == Kind.SIZE || c.kind == Kind.COLOUR || c.locked) continue
            val mids = items.mapNotNull { r ->
                val ns = numberWords(rows[r][ci] ?: return@mapNotNull null)
                if (ns.size == 2 && ns[1].box.l - ns[0].box.r > 0.3 * mh) (ns[0].box.r + ns[1].box.l) / 2.0 - shifts[r] else null
            }
            if (mids.size >= maxOf(3, (items.size * 0.6).toInt())) {
                val x = median(mids)
                val left = c.cx < x
                val a = Col(if (left) c.label else "", minOf(c.l, x - 2), x - 1, if (left) c.roles else mutableListOf())
                // (a split keeps any taught meaning on the part under the heading)
                val b = Col(if (left) "" else c.label, x + 1, maxOf(c.r, x + 2), if (left) mutableListOf() else c.roles)
                a.kind = if (left) c.kind else Kind.OTHER; b.kind = if (left) Kind.OTHER else c.kind
                return cols.take(ci) + a + b + cols.drop(ci + 1)
            }
        }
        return null
    }

    /** A heading the OCR misplaced: the numbers under it do not fit what it says, so it is worked out again. */
    private fun checkColumns(cols: List<Col>, rows: List<Map<Int, List<OcrWord>>>) {
        val items = rows.filter { isItemLike(it, cols) }
        if (items.size < 3) return
        for ((ci, c) in cols.withIndex()) {
            if (c.locked) continue
            val texts = items.mapNotNull { r -> r[ci]?.let { cellText(it) }?.takeIf { it.isNotEmpty() } }
            if (texts.size < items.size / 2) continue
            val nums = texts.mapNotNull { number(it) }
            val withPct = texts.count { it.contains('%') }
            val bad = when (c.kind) {
                Kind.DISC_PCT, Kind.GST_PCT, Kind.CGST_PCT, Kind.SGST_PCT, Kind.IGST_PCT ->
                    nums.count { it > BigDecimal(100) } > nums.size / 5
                Kind.AMOUNT, Kind.TAXABLE, Kind.GROSS, Kind.TOTAL, Kind.RATE, Kind.MRP, Kind.DISC_AMT, Kind.CGST_AMT, Kind.SGST_AMT, Kind.IGST_AMT ->
                    withPct * 2 > texts.size
                Kind.QTY -> withPct * 2 > texts.size || nums.count { it.scale() > 0 && it.stripTrailingZeros().scale() > 0 } * 2 > nums.size
                else -> false
            }
            if (bad) c.kind = Kind.OTHER
        }
    }

    /**
     * The bill's own arithmetic decides which columns are Qty, Rate, Disc % and Amount:
     * every way of reading the number columns is tried, and the one under which
     * Qty × Rate × (1 − Disc%) = Amount holds on most lines wins — whatever the headings say.
     */
    private fun solveByArithmetic(cols: List<Col>, rows: List<Map<Int, List<OcrWord>>>) {
        val items = rows.filter { isItemLike(it, cols) }
        if (items.size < 3) return
        val numericCols = cols.indices.filter { ci ->
            val c = cols[ci]
            !c.locked && c.kind !in setOf(Kind.SR, Kind.DESC, Kind.HSN, Kind.SIZE, Kind.COLOUR) &&
                items.count { r -> r[ci]?.let { number(cellText(it)) } != null } * 10 >= items.size * 6
        }
        if (numericCols.size < 3) return
        val vals = numericCols.associateWith { ci -> items.map { r -> r[ci]?.let { number(cellText(it)) } } }
        val h = BigDecimal(100)
        fun close(a: BigDecimal, b: BigDecimal) = (a - b).abs() <= maxOf(BigDecimal("0.06"), b.abs() * BigDecimal("0.002"))
        fun score(q: Int, r: Int, d: Int?, t: Int): Int = items.indices.count { i ->
            val qv = vals[q]!![i] ?: return@count false; val rv = vals[r]!![i] ?: return@count false; val tv = vals[t]!![i] ?: return@count false
            val dv = if (d == null) BigDecimal.ZERO else vals[d]!![i] ?: return@count false
            if (dv >= h || qv.signum() <= 0) return@count false
            close(qv * rv * (h - dv) / h, tv)
        }
        fun idx(k: Kind) = cols.indexOfFirst { it.kind == k }.takeIf { it in numericCols }
        val curQ = idx(Kind.QTY); val curR = idx(Kind.RATE); val curD = idx(Kind.DISC_PCT); val curT = idx(Kind.AMOUNT) ?: idx(Kind.TAXABLE)
        val current = if (curQ != null && curR != null && curT != null) score(curQ, curR, curD, curT) else 0
        if (current * 10 >= items.size * 9) return       // the headings already add up
        var best = current; var pick: List<Int?>? = null
        for (q in numericCols) for (r in numericCols) { if (r == q) continue
            for (t in numericCols) { if (t == q || t == r) continue
                for (d in listOf<Int?>(null) + numericCols) { if (d == q || d == r || d == t) continue
                    val sc = score(q, r, d, t)
                    if (sc > best) { best = sc; pick = listOf(q, r, d, t) }
                }
            }
        }
        val p = pick ?: return
        if (best * 10 < items.size * 6) return
        val (q, r, d, t) = p
        // Whatever these columns were called before, they now are what the arithmetic says.
        val taxableNamed = cols.any { it.kind == Kind.TAXABLE }
        listOfNotNull(q, r, d, t).forEach { ci -> cols[ci].kind = Kind.OTHER }
        cols[q!!].kind = Kind.QTY; cols[r!!].kind = Kind.RATE; d?.let { cols[it].kind = Kind.DISC_PCT }
        cols[t!!].kind = if (taxableNamed && cols[t].roles.contains(Role.TAXABLE)) Kind.TAXABLE else Kind.AMOUNT
        if (cols.count { it.kind == Kind.TAXABLE || it.kind == Kind.AMOUNT } > 1) cols.filter { (it.kind == Kind.TAXABLE || it.kind == Kind.AMOUNT) && it !== cols[t] }.forEach { it.kind = Kind.OTHER }
        // MRP: the number column (left over) that is above the rate on almost every line.
        val mrpNow = idx(Kind.MRP)
        val rateVals = vals[r]!!
        fun mrpScore(ci: Int) = items.indices.count { i -> val m = vals[ci]!![i]; val rv = rateVals[i]; m != null && rv != null && m > rv && m < rv * BigDecimal(4) }
        if (mrpNow == null || mrpScore(mrpNow) * 10 < items.size * 7) {
            val cand = numericCols.filter { it !in setOf(q, r, d, t) }.maxByOrNull { mrpScore(it) }
            if (cand != null && mrpScore(cand) * 10 >= items.size * 7) { mrpNow?.let { cols[it].kind = Kind.OTHER }; cols[cand].kind = Kind.MRP }
        }
        // Give relabelled columns a clear name when the heading was something else.
        for ((ci, k) in listOf(q to "Qty", r to "Rate", d to "Disc %", t to "Amount")) if (ci != null && cols[ci].label.isBlank()) cols[ci].label = k
    }

    /**
     * Columns without a readable heading: tries what each could be and keeps the meaning
     * under which the bill's own arithmetic works out on most lines.
     */
    private fun inferKinds(cols: List<Col>, rows: List<Map<Int, List<OcrWord>>>) {
        val items = rows.filter { isItemLike(it, cols) }
        if (items.size < 3) return
        val have = cols.map { it.kind }.toMutableSet()
        fun v(r: Map<Int, List<OcrWord>>, k: Kind): BigDecimal? = cols.indexOfFirst { it.kind == k }.takeIf { it >= 0 }?.let { ci -> r[ci]?.let { number(cellText(it)) } }
        val h = BigDecimal(100)
        for ((ci, c) in cols.withIndex()) {
            if (c.kind != Kind.OTHER || c.locked) continue
            val texts = items.map { cellText(it[ci]) }
            val sizeLike = texts.count { Regex("""^\d{1,2}\s*[xX×]\s*\d{1,2}\.?$""").matches(it) }
            if (sizeLike >= items.size * 0.6 && Kind.SIZE !in have) { c.kind = Kind.SIZE; have += Kind.SIZE; continue }
            val us = items.map { it[ci]?.let { ws -> number(cellText(ws)) } }
            if (us.count { it != null } < items.size * 0.6) continue
            fun close(a: BigDecimal?, b: BigDecimal?) = a != null && b != null && (a - b).abs() <= maxOf(BigDecimal("0.05"), b.abs() * BigDecimal("0.001"))
            val scores = mutableMapOf<Kind, Int>()
            fun d(r: Map<Int, List<OcrWord>>) = v(r, Kind.DISC_PCT) ?: BigDecimal.ZERO
            fun t(r: Map<Int, List<OcrWord>>) = v(r, Kind.AMOUNT) ?: v(r, Kind.TAXABLE)
            items.forEachIndexed { i, r ->
                val u = us[i] ?: return@forEachIndexed
                val q = v(r, Kind.QTY); val rate = v(r, Kind.RATE)
                if (Kind.DISC_PCT !in have && u < h && q != null && rate != null && close(q * rate * (h - u) / h, t(r))) scores.merge(Kind.DISC_PCT, 1, Int::plus)
                if (Kind.QTY !in have && rate != null && close(u * rate * (h - d(r)) / h, t(r))) scores.merge(Kind.QTY, 1, Int::plus)
                if (Kind.RATE !in have && q != null && close(q * u * (h - d(r)) / h, t(r))) scores.merge(Kind.RATE, 1, Int::plus)
                if (Kind.AMOUNT !in have && Kind.TAXABLE !in have && q != null && rate != null && close(q * rate * (h - d(r)) / h, u)) scores.merge(Kind.AMOUNT, 1, Int::plus)
                if (Kind.GROSS !in have && q != null && rate != null && close(q * rate, u)) scores.merge(Kind.GROSS, 1, Int::plus)
                if (Kind.MRP !in have && rate != null && u > rate) scores.merge(Kind.MRP, 1, Int::plus)
                if (Kind.GST_PCT !in have && u.stripTrailingZeros().toPlainString() in setOf("0", "3", "5", "12", "18", "28")) scores.merge(Kind.GST_PCT, 1, Int::plus)
            }
            // Arithmetic first; "bigger than the rate" and "a GST rate" only when nothing adds up.
            val order = listOf(Kind.DISC_PCT, Kind.QTY, Kind.RATE, Kind.AMOUNT, Kind.GROSS, Kind.GST_PCT, Kind.MRP)
            val need = (items.size * 0.6).toInt().coerceAtLeast(2)
            val best = order.filter { (scores[it] ?: 0) >= need }.maxByOrNull { (scores[it] ?: 0) * 10 - order.indexOf(it) }
            if (best != null) {
                c.kind = best; have += best
                if (c.label.isEmpty()) c.label = when (best) { Kind.DISC_PCT -> "Disc %"; Kind.QTY -> "Qty"; Kind.RATE -> "Rate"; Kind.AMOUNT -> "Amount"; Kind.GROSS -> "Total"; Kind.GST_PCT -> "GST%"; Kind.MRP -> "MRP"; else -> "" }
            }
        }
    }

    internal fun debug(page: OcrPage): String {
        val words = Layout.straighten(page.words); val rows = Layout.lines(words); val mh = Layout.medianHeight(words)
        val best = rows.map { headerScore(it) }.withIndex().filter { it.value >= 3 }.maxByOrNull { it.value }?.index ?: return "no header"
        var bandEnd = best
        while (bandEnd + 1 < rows.size && bandEnd - best < 3 && rows[bandEnd + 1].y - rows[bandEnd].y < 2.2 * mh && rows[bandEnd + 1].words.count { isNum(it.text) && (number(it.text)!! > BigDecimal(99) || it.text.contains('.')) } < 2) bandEnd++
        val cols = columns(rows.subList(best, bandEnd + 1), mh)
        val sb = StringBuilder("mh=$mh band=$best..$bandEnd\n")
        cols.forEach { sb.append("  ${it.kind} '${it.label}' ${it.l.toInt()}..${it.r.toInt()}\n") }
        val (cells, _) = assignTable(rows.drop(bandEnd + 1).take(8), cols, mh)
        cells.forEach { c -> sb.append("  " + c.entries.joinToString(" | ") { (ci, ws) -> "${cols[ci].kind}=${cellText(ws)}" } + "\n") }
        return sb.toString()
    }

    /** Words of one line, by column index. */
    private fun assign(r: Row, cols: List<Col>, bounds: Bounds, mh: Double): Map<Int, List<OcrWord>> {
        val out = sortedMapOf<Int, MutableList<OcrWord>>()
        var prev: Pair<OcrWord, Int>? = null
        for (w in r.words) {
            var ci = 0
            while (ci < cols.size - 1 && w.box.cx > bounds.at(ci)) ci++
            // "6 pair", "9.82 %", "₹ 120" stay with their number.
            val lw = w.text.lowercase().trim('.', ':')
            val p = prev
            if (p != null && w.box.l - p.first.box.r < 0.8 * mh && (lw in UNITS || p.first.text == "₹" || p.first.text.equals("rs.", true))) ci = p.second
            // Text spilling over from the description stays in it.
            if (p != null && cols[p.second].kind == Kind.DESC && ci != p.second && cols[ci].kind != Kind.SIZE && cols[ci].kind != Kind.COLOUR &&
                !isNum(w.text) && w.box.l - p.first.box.r < 0.8 * mh) ci = p.second
            // The Sr column holds only the serial number; text there belongs to the description.
            if (cols[ci].kind == Kind.SR && ci + 1 < cols.size && (w.text.trim('.', ')').toIntOrNull() == null || out[ci]?.isNotEmpty() == true || out.keys.any { it > ci })) ci += 1
            out.getOrPut(ci) { mutableListOf() } += w
            prev = w to ci
        }
        return out
    }

    // ================================================================ one item

    private fun cellText(ws: List<OcrWord>?) = ws?.joinToString(" ") { it.text }?.trim().orEmpty()

    /**
     * One line. When its numbers do not add up, the words right of the description are dealt to the columns again
     * by what they look like (HSN, GST %, size, then the numbers in order) — a line that slid sideways in the photo.
     */
    private fun readItem(block0: List<Map<Int, List<OcrWord>>>, cols: List<Col>, index: Int): LineItem {
        val first = readItemOnce(block0, cols, index)
        if (first.kind != ItemKind.ARTICLE || Heal.sure(first)) return first
        val moved = reflow(block0, cols) ?: return first
        val again = readItemOnce(moved, cols, index)
        return if (Heal.proven(again.current) && again.needs.size <= first.needs.size) again else first
    }

    private fun reflow(block: List<Map<Int, List<OcrWord>>>, cols: List<Col>): List<Map<Int, List<OcrWord>>>? {
        val row = block.first()
        val descAt = cols.indexOfLast { it.kind == Kind.DESC }.takeIf { it >= 0 } ?: return null
        val targets = (descAt + 1 until cols.size).filter { cols[it].kind != Kind.OTHER }
        if (targets.size < 3) return null
        // Words right of the description, in reading order; a unit or % stays with its number.
        val ws = row.filterKeys { it > descAt }.values.flatten().sortedBy { it.box.l }
        val tokens = mutableListOf<MutableList<OcrWord>>()
        for (w in ws) {
            val lw = w.text.lowercase().trim('.', ':')
            if (tokens.isNotEmpty() && (lw in UNITS || w.text == "%")) tokens.last() += w else tokens += mutableListOf(w)
        }
        fun text(t: List<OcrWord>) = t.joinToString(" ") { it.text }
        fun fits(k: Kind, t: String): Boolean = when (k) {
            Kind.HSN -> Regex("""^\d{4,8}$""").matches(t)
            Kind.GST_PCT, Kind.CGST_PCT, Kind.SGST_PCT, Kind.IGST_PCT -> Regex("""^\d{1,2}(\.\d{1,2})?\s*%$""").matches(t)
            Kind.SIZE -> Regex("""^\d{1,2}\s*[xX×]\s*\d{1,2}\.?$""").matches(t) || (t.contains('/') && t.any(Char::isDigit))
            Kind.COLOUR -> t.any(Char::isLetter) && number(t) == null
            Kind.QTY -> number(t)?.let { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 } == true
            Kind.DISC_PCT -> number(t) != null       // "745 %" too: its point is put back by the repair
            in NUMERIC -> number(t) != null
            else -> false
        }
        val out = row.filterKeys { it <= descAt }.toMutableMap()
        var i = 0
        for (ci in targets) {
            if (i >= tokens.size) break
            if (fits(cols[ci].kind, text(tokens[i]))) { out[ci] = tokens[i]; i++ }
        }
        if (i < tokens.size) return null      // something left over: not a simple slide
        if (out == row) return null
        return listOf(out) + block.drop(1)
    }

    private fun readItemOnce(block0: List<Map<Int, List<OcrWord>>>, cols: List<Col>, index: Int): LineItem {
        val block = shiftCrowded(block0, cols)
        val first = block.first()
        // A line split in two by the photo still gives its numbers: take each from the first line that has it.
        fun firstNum(k: Kind): BigDecimal? = cols.indexOfFirst { it.kind == k }.takeIf { it >= 0 }?.let { ci -> block.firstNotNullOfOrNull { number(cellText(it[ci])) } }
        // Numbers may wrap onto the next line (e.g. MRP); take the first line that has one.
        fun anyNum(k: Kind): BigDecimal? = cols.indexOfFirst { it.kind == k }.takeIf { it >= 0 }?.let { ci -> block.firstNotNullOfOrNull { number(cellText(it[ci])) } }
        fun textOf(k: Kind, sep: String = "\n"): String = cols.withIndex().filter { it.value.kind == k }.joinToString(sep) { (ci, _) -> block.mapNotNull { cellText(it[ci]).ifEmpty { null } }.joinToString(sep) }.trim()

        val srCol = cols.indexOfFirst { it.kind == Kind.SR }
        var sr = if (srCol >= 0) serialOf(cellText(first[srCol])) else null
        // Description lines (and anything in an unnamed column on its left).
        var descLines = cols.withIndex().filter { it.value.kind == Kind.DESC }.flatMap { (ci, _) -> block.map { cellText(it[ci]).replace(OWN_TEXT, "").trim() } }.filter { it.isNotEmpty() }.toMutableList()
        if (sr == null && descLines.isNotEmpty()) {
            // "1 1180-Paralite…" when there is no Sr heading.
            val t = descLines[0].split(' ')
            t.first().trim('.', ')').toIntOrNull()?.takeIf { it in 1..999 && t.size > 1 }?.let { sr = it; descLines[0] = t.drop(1).joinToString(" ") }
        }
        val hsn = cols.withIndex().filter { it.value.kind == Kind.HSN }.joinToString("") { (ci, _) -> block.joinToString("") { cellText(it[ci]).replace(" ", "") } }.filter { it.isLetterOrDigit() }

        val joined = descLines.joinToString("\n")
        val mrpCol = anyNum(Kind.MRP)
        val mrpMatch = if (mrpCol == null) MRP.find(joined) else null
        val mrp = mrpCol ?: mrpMatch?.groupValues?.get(1)?.let { BigDecimal(it) }

        // ---- text: article code, name, brand, colour, sizes
        val firstLine = descLines.firstOrNull().orEmpty().let { if (mrpMatch != null) it.replace(mrpMatch.value, "").trim() else it }
        val tokens = firstLine.split(' ').filter { it.isNotEmpty() && it != ":" && !it.equals("MRP", true) }
        val article: String; val name: String; val brand: String; var colour: String; var sizeText: String
        val codeToken = tokens.firstOrNull().orEmpty()
        val restLines = joined.lines().drop(1).joinToString("\n").let { if (mrpMatch != null) it.replace(mrpMatch.value, "") else it }
            .lines().map { it.trim() }.filter { it.isNotEmpty() && it != ":" && !MRP.matches(it) && number(it) == null }
        if (codeToken.contains('-') && codeToken.any(Char::isDigit) && codeToken.split('-').size >= 3) {
            // "1180-Paralite-Gents-EVA-6X10": code - brand - details; colour and sizes on the next lines.
            val (a, n, b) = splitCode(codeToken)
            article = a; name = n; brand = b
            val parts = mutableListOf<Pair<String, String>>()
            for (l in (tokens.drop(1).joinToString(" ").let { if (it.isNotBlank()) listOf(it) else emptyList() } + restLines)) {
                val m = Regex("""^([A-Za-z][A-Za-z]*(?:\s[A-Za-z]+)?)\s+(\S*\d.*)$""").find(l)
                when {
                    m != null -> parts += m.groupValues[1] to m.groupValues[2].replace(" ", "")
                    l.all { it.isLetter() || it == ' ' } -> parts += l to ""
                    parts.isNotEmpty() -> parts[parts.lastIndex] = parts.last().first to (parts.last().second + l.replace(" ", ""))
                    else -> parts += "" to l.replace(" ", "")
                }
            }
            colour = parts.map { it.first }.filter { it.isNotEmpty() }.distinct().joinToString(" / ")
            sizeText = parts.filter { it.second.isNotEmpty() }.joinToString(" | ") { (c, s) -> if (c.isEmpty()) s else "$c $s" }
        } else {
            // "SM 651 N.BLUE GREY", "ASIAN SAFARI-15 BLACK-GREY": brand + number = article, the rest = colour.
            val k = tokens.indexOfFirst { t -> t.any(Char::isDigit) }
            if (k in 0..2) {
                article = tokens.take(k + 1).joinToString(" ")
                brand = if (k >= 1) tokens[0].filter { it.isLetter() } else ""
                colour = tokens.drop(k + 1).joinToString(" ").trim('-', ' ')
            } else {
                article = tokens.take(2).joinToString(" "); brand = tokens.firstOrNull()?.filter { it.isLetter() }.orEmpty()
                colour = tokens.drop(2).joinToString(" ")
            }
            name = ""
            sizeText = restLines.joinToString(" | ")
        }
        // "11X1 3": a size range the photo broke in two.
        textOf(Kind.SIZE, " ").takeIf { it.isNotEmpty() }?.let { sizeText = it.trimEnd('.').replace(Regex("""\b(\d{1,2}[xX×]1)\s([0-5])\b"""), "$1$2") }
        textOf(Kind.COLOUR, " ").takeIf { it.isNotEmpty() }?.let { colour = it }

        val kind = if (CHARGE.containsMatchIn(article.ifEmpty { codeToken }) || CHARGE.containsMatchIn(firstLine)) ItemKind.CHARGE else ItemKind.ARTICLE
        val amount = firstNum(Kind.AMOUNT)
        var v = ItemValues(
            sr = sr, article = article, name = name, brand = brand, colour = colour, sizeText = sizeText, hsn = hsn,
            qty = firstNum(Kind.QTY), mrp = mrp, rate = firstNum(Kind.RATE), gross = firstNum(Kind.GROSS),
            discPct = firstNum(Kind.DISC_PCT), discAmt = firstNum(Kind.DISC_AMT), taxable = firstNum(Kind.TAXABLE) ?: amount,
            cgstPct = firstNum(Kind.CGST_PCT), cgstAmt = firstNum(Kind.CGST_AMT), sgstPct = firstNum(Kind.SGST_PCT), sgstAmt = firstNum(Kind.SGST_AMT),
            igstPct = firstNum(Kind.IGST_PCT), igstAmt = firstNum(Kind.IGST_AMT), gstPct = firstNum(Kind.GST_PCT), printedTotal = firstNum(Kind.TOTAL),
        )
        // Disc column printed as an amount (e.g. "88.35") instead of a %: keep it as the amount.
        if (v.discPct != null && v.discAmt == null && cols.none { it.kind == Kind.DISC_AMT }) {
            val dTxt = cellText(cols.indexOfFirst { it.kind == Kind.DISC_PCT }.let { first[it] })
            val base = v.gross ?: v.qty?.let { q -> v.rate?.let { q * it } }
            if (!dTxt.contains('%') && v.discPct!! > BigDecimal(100) && base != null) v = v.copy(discAmt = v.discPct, discPct = null)
        }

        // ---- a decimal point the photo lost or misread ("745 %" for 7.45 %, "1.29 5.68" for 1,295.68):
        // the reading that makes the line's own arithmetic work is taken, and the printed cell shown that way.
        val raw = cols.indices.map { ci -> block.mapNotNull { cellText(it[ci]).replace(OWN_TEXT, "").trim().ifEmpty { null } }.joinToString(" ") }.toMutableList()
        if (v.taxable == null || v.qty == null || (v.discPct ?: BigDecimal.ZERO) >= BigDecimal(100) || Calc.checkLine(v).isNotEmpty())
            repairDecimals(v, cols, raw)?.let { v = it }
        // "640299905" + "%": the GST % glued to an 8-digit HSN code.
        val hc = cols.indexOfFirst { it.kind == Kind.HSN }; val gc = cols.indexOfFirst { it.kind == Kind.GST_PCT }
        if (hc >= 0 && gc >= 0 && v.gstPct == null && Regex("""^\d{9,10}$""").matches(v.hsn)) {
            val g = v.hsn.substring(8).toBigDecimal()
            if (g.signum() > 0 && g <= BigDecimal(28)) {
                v = v.copy(hsn = v.hsn.substring(0, 8), gstPct = g)
                raw[hc] = v.hsn; raw[gc] = "${g.toPlainString()} %"
            }
        }

        // ---- what is missing or does not add up
        val has = cols.map { it.kind }.toSet()
        val needs = mutableSetOf<F>()
        if (v.qty == null) needs += F.QTY
        if (v.rate == null && Kind.RATE in has) needs += F.RATE
        if (v.gross == null && Kind.GROSS in has) needs += F.GROSS
        if (v.discPct == null && Kind.DISC_PCT in has) needs += F.DISC_PCT
        if (v.discAmt == null && Kind.DISC_AMT in has) needs += F.DISC_AMT
        if (v.taxable == null) needs += F.TAXABLE
        if (v.mrp == null && kind == ItemKind.ARTICLE) needs += F.MRP
        // Amount per pair far from the MRP (a part of the number lost): one of the two is misread.
        val q0 = v.qty; val t0 = v.taxable; val m0 = v.mrp
        if (kind == ItemKind.ARTICLE && q0 != null && t0 != null && m0 != null && q0.signum() > 0 && m0.signum() > 0) {
            val perPair = t0.divide(q0, 4, java.math.RoundingMode.HALF_UP)
            if (perPair < m0 * BigDecimal("0.05") || perPair > m0 * BigDecimal("1.3")) { needs += F.TAXABLE; needs += F.MRP }
        }
        if (article.isEmpty()) needs += F.ARTICLE
        // A colour not printed is not a misreading (a tip says so); it never makes the line "not understood".
        if ((Kind.CGST_PCT in has || Kind.SGST_PCT in has) && (v.cgstPct == null || v.sgstPct == null)) { needs += F.CGST_PCT; needs += F.SGST_PCT }
        for (c in Calc.checkLine(v)) {
            needs += c.field
            when (c.field) {
                F.GROSS -> { needs += F.QTY; needs += F.RATE }
                F.DISC_AMT -> needs += F.DISC_PCT
                F.TAXABLE -> if (v.gross == null) { needs += F.QTY; needs += F.RATE; if (v.discPct != null) needs += F.DISC_PCT }
                else -> {}
            }
        }
        // Every cell as printed, in column order.
        return LineItem("i$index", kind, v, v, needs, raw = raw)
    }

    /**
     * Two numbers in one number column with the column beside it empty ("12 389.33" under Rate, nothing under Qty):
     * the line leaned into the next column, so the extra number goes back to the empty one.
     */
    private fun shiftCrowded(block: List<Map<Int, List<OcrWord>>>, cols: List<Col>): List<Map<Int, List<OcrWord>>> {
        val rows = block.map { it.toMutableMap() }
        fun empty(ci: Int) = rows.all { it[ci].isNullOrEmpty() }
        for (r in rows) for (ci in cols.indices) {
            if (cols[ci].kind !in NUMERIC) continue
            val ws = r[ci] ?: continue
            val nums = ws.filter { isNum(it.text) && it.text.lowercase().trim('.', ':') !in UNITS }
            if (nums.size != 2 || ws.size > 3) continue
            if (ci > 0 && cols[ci - 1].kind in NUMERIC && empty(ci - 1)) {
                val moved = ws.takeWhile { it !== nums[1] }
                r[ci - 1] = moved; r[ci] = ws.drop(moved.size)
            } else if (ci + 1 < cols.size && cols[ci + 1].kind in NUMERIC && empty(ci + 1)) {
                val keep = ws.takeWhile { it !== nums[1] }
                r[ci] = keep; r[ci + 1] = ws.drop(keep.size)
            }
        }
        return rows
    }

    /**
     * Tries the readings of a misread number that fix the line: one field at a time, only with the digits printed.
     * Returns the fixed values (and puts the corrected text in [raw]), or null when no single fix makes the line add up.
     */
    private fun repairDecimals(v: ItemValues, cols: List<Col>, raw: MutableList<String>): ItemValues? {
        fun ok(c: ItemValues) = c.qty != null && c.rate != null && c.taxable != null && Calc.checkLine(c).isEmpty()
        class Try(val values: ItemValues, val edits: List<Pair<Int, String>>) {
            constructor(values: ItemValues, col: Int, text: String) : this(values, listOf(col to text))
        }
        val tries = mutableListOf<Try>()
        fun col(k: Kind) = cols.indexOfFirst { it.kind == k }
        val digits = Regex("""\d[\d,.]*""")
        // Disc % without its point: 745 → 7.45, 982 → 9.82.
        val dc = col(Kind.DISC_PCT)
        val d = v.discPct
        if (dc >= 0 && d != null && d >= BigDecimal(100) && d.stripTrailingZeros().scale() <= 0) for (k in listOf(2, 1)) {
            val nd = d.movePointLeft(k)
            tries += Try(v.copy(discPct = nd), dc, digits.replaceFirst(raw[dc], nd.toPlainString()))
        }
        // Amounts: "1.29 5.68" / "1.295.68" = 1,295.68; a lost point ("129568" = 1,295.68).
        for (k in listOf(Kind.TAXABLE, Kind.AMOUNT, Kind.RATE, Kind.MRP, Kind.GROSS)) {
            val ci = col(k).takeIf { it >= 0 } ?: continue
            val tx = raw[ci].trim()
            fun set(x: BigDecimal) = when (k) {
                Kind.TAXABLE, Kind.AMOUNT -> v.copy(taxable = x); Kind.RATE -> v.copy(rate = x); Kind.MRP -> v.copy(mrp = x); else -> v.copy(gross = x)
            }
            val joined = tx.replace(" ", "")
            if (DOTTED.matches(joined)) {
                val t = joined.substring(0, joined.lastIndexOf('.')).replace(".", ",") + joined.substring(joined.lastIndexOf('.'))
                tries += Try(set(BigDecimal(t.replace(",", ""))), ci, t)
            }
            val cur = number(tx)
            if (cur != null && !tx.contains('.') && cur >= BigDecimal(100)) {
                val x = cur.movePointLeft(2)
                tries += Try(set(x), ci, x.toPlainString())
            }
        }
        // Pairs lost ("pair" left alone, or nothing): the whole number of pairs that makes the line add up, if there is exactly one.
        val qc = col(Kind.QTY)
        if (qc >= 0 && v.qty == null && v.rate != null && v.taxable != null && v.rate.signum() > 0 &&
            raw[qc].filter(Char::isDigit).isEmpty()) {
            val discTries = tries.filter { it.values.discPct != v.discPct }
            val discs: List<Pair<BigDecimal, Try?>> = (listOfNotNull(v.discPct).map { it to null } + discTries.map { it.values.discPct!! to it })
                .ifEmpty { listOf(BigDecimal.ZERO to null) }
            for ((dd, dt) in discs) {
                val per = v.rate * (BigDecimal(100) - dd) / BigDecimal(100)
                if (per.signum() <= 0) continue
                val q = v.taxable.divide(per, 4, java.math.RoundingMode.HALF_UP)
                val qi = q.setScale(0, java.math.RoundingMode.HALF_UP)
                if (qi.signum() > 0 && qi <= BigDecimal(9999) && (q - qi).abs() <= BigDecimal("0.002")) {
                    val unit = raw[qc].trim().ifEmpty { "" }
                    var nv = v.copy(qty = qi, discPct = if (v.discPct != null) dd else v.discPct)
                    val edits = mutableListOf(qc to (qi.toPlainString() + " " + unit).trim())
                    // "5496 pair" under MRP: the pairs were glued to the MRP.
                    val mc = col(Kind.MRP)
                    val mt = if (mc >= 0) raw[mc].trim() else ""
                    val glued = Regex("""^(\d[\d,]*(?:\.\d{1,2})?)(\d+)\s*(pairs?|prs?|pcs?)$""", RegexOption.IGNORE_CASE).matchEntire(mt)
                    if (glued != null && glued.groupValues[2] == qi.toPlainString()) {
                        nv = nv.copy(mrp = BigDecimal(glued.groupValues[1].replace(",", "")))
                        edits += mc to glued.groupValues[1]
                        edits[0] = qc to "${qi.toPlainString()} ${glued.groupValues[3]}"
                    }
                    tries += Try(nv, edits + dt?.edits.orEmpty())
                }
            }
        }
        val good = tries.filter { ok(it.values) }
        if (good.size != 1 && good.map { it.values }.distinct().size != 1) return null
        val g = good.first()
        for ((c, t) in g.edits) raw[c] = t
        return g.values
    }

    /** "1180-Paralite-Gents-EVA-6X10" -> article 1180, name "Paralite Gents EVA 6X10", brand Paralite. */
    fun splitCode(code: String): Triple<String, String, String> {
        val parts = code.split('-').filter { it.isNotEmpty() }
        if (parts.size < 2) return Triple(code, "", "")
        return Triple(parts[0], parts.drop(1).joinToString(" "), parts[1])
    }

    // ================================================================ below the table

    private class Footer(
        val qty: BigDecimal? = null, val subtotal: BigDecimal? = null, val grand: BigDecimal? = null, val roundOff: BigDecimal? = null,
        val other: BigDecimal? = null, val discount: BigDecimal? = null,
        val cgstPct: BigDecimal? = null, val cgstAmt: BigDecimal? = null, val sgstPct: BigDecimal? = null, val sgstAmt: BigDecimal? = null,
        val igstPct: BigDecimal? = null, val igstAmt: BigDecimal? = null,
    )

    private fun nums(r: Row): List<Pair<OcrWord, BigDecimal>> {
        // Join "₹" / "Rs." with the following number, and "1,02,237" split by OCR is left as is.
        return r.words.mapNotNull { w -> number(w.text)?.let { w to it } }
    }

    /** Rows from the first total / tax line down (where the bill's totals are when no table heading was found). */
    private fun footerZone(rows: List<Row>): List<Row> {
        val at = rows.indexOfFirst { Regex("""(?i)^\s*(sub\s*-?\s*)?total|\b(output\s*)?[cs]gst\b|\bigst\b|round\s*off""").containsMatchIn(it.text) }
        return if (at < 0) emptyList() else rows.drop(at)
    }

    private fun readFooter(rows: List<Row>, cols: List<Col>, bounds: Bounds, mh: Double): Footer {
        var qty: BigDecimal? = null; var sub: BigDecimal? = null; var grand: BigDecimal? = null; var round: BigDecimal? = null
        var other: BigDecimal? = null; var disc: BigDecimal? = null
        var cp: BigDecimal? = null; var ca: BigDecimal? = null; var sp: BigDecimal? = null; var sa: BigDecimal? = null
        var ip: BigDecimal? = null; var ia: BigDecimal? = null
        var lone: BigDecimal? = null
        val pctRe = Regex("""@?\s*(\d{1,2}(?:\.\d{1,2})?)\s*%""")
        val qtyCol = cols.indexOfFirst { it.kind == Kind.QTY }
        var stop = false
        for (r in rows) {
            if (stop) break
            val t = r.text
            val ns = nums(r)
            if (Regex("""(?i)in\s*words|rupees|only\b""").containsMatchIn(t) && !Regex("""(?i)grand|net""").containsMatchIn(t)) continue
            if (Regex("""(?i)bank|a/c|ifs|terms|declaration|signatory|hsn\s*/?\s*sac""").containsMatchIn(t)) { if (grand != null) stop = true; continue }
            if (ns.isEmpty()) continue
            val lastBig = ns.lastOrNull { (w, v) -> !w.text.contains('%') && !(w.text.startsWith("@")) && v.abs() >= BigDecimal.ONE }?.second
            when {
                Regex("""(?i)\bcgst\b""").containsMatchIn(t) && ca == null -> { cp = pctRe.find(t)?.groupValues?.get(1)?.toBigDecimalOrNull(); ca = lastBig }
                Regex("""(?i)\b(sgst|utgst)\b""").containsMatchIn(t) && sa == null -> { sp = pctRe.find(t)?.groupValues?.get(1)?.toBigDecimalOrNull(); sa = lastBig }
                Regex("""(?i)\bigst\b""").containsMatchIn(t) && ia == null -> { ip = pctRe.find(t)?.groupValues?.get(1)?.toBigDecimalOrNull(); ia = lastBig }
                Regex("""(?i)round\s*(ed)?\s*off|r\s*/?\s*off|rounding""").containsMatchIn(t) -> {
                    val v = ns.last().second
                    round = if (Regex("""(?i)less|\(-\)|-\s*\d""").containsMatchIn(t) && v.signum() > 0) v.negate() else v
                }
                Regex("""(?i)grand\s*total|net\s*(amount|payable|total|amt)|amount\s*payable|total\s*amount|invoice\s*(total|value|amount)|bill\s*amount|₹|\brs\.?\s*\d""").containsMatchIn(t) &&
                    !Regex("""(?i)taxable""").containsMatchIn(t) -> grand = ns.maxOf { it.second }.let { g -> grand?.let { maxOf(it, g) } ?: g }
                Regex("""(?i)^\s*(sub\s*-?\s*)?total(\b|(?=\d))""").containsMatchIn(t) && sub == null -> {
                    if (qtyCol >= 0) {
                        val cells = assign(r, cols, bounds, mh)
                        qty = cells[qtyCol]?.let { number(cellText(it)) } ?: qty
                    }
                    sub = ns.maxOf { it.second }
                }
                CHARGE.containsMatchIn(t.trim()) || Regex("""(?i)\b(freight|transport(ation)?|packing|forwarding|cartage|hamali|courier)\b""").containsMatchIn(t) ->
                    other = (other ?: BigDecimal.ZERO) + ns.last().second
                Regex("""(?i)(less|cash|trade|special)\s*:?\s*disc""").containsMatchIn(t) -> disc = ns.last().second.abs()
                lone == null && ns.size <= 2 && r.words.size <= 3 -> lone = ns.maxOf { it.second }
            }
        }
        // A total box with nothing else around it (no tax lines below) is the bill total.
        val anyTax = ca != null || sa != null || ia != null
        if (grand == null && !anyTax && (sub != null || lone != null)) { grand = sub ?: lone; if (sub != null && lone == null) sub = null }
        if (grand == null && anyTax && sub != null) {
            val expected = sub + (ca ?: BigDecimal.ZERO) + (sa ?: BigDecimal.ZERO) + (ia ?: BigDecimal.ZERO) + (round ?: BigDecimal.ZERO) + (other ?: BigDecimal.ZERO)
            if (lone != null && (lone - expected).abs() <= BigDecimal.ONE) grand = lone
        }
        return Footer(qty, sub, grand, round, other, disc, cp, ca, sp, sa, ip, ia)
    }

    // ================================================================ header block

    private val BUYER = Regex("""(?i)\b(buyer|consignee|customer|party|bill\s*to|billed\s*to|ship\s*to|shipped\s*to|receiver|recipient|purchaser|sold\s*to|m/s)\b""")

    /** GSTIN, tolerant to OCR mixing up O/0, I/1, S/5, B/8 in the wrong places. */
    fun gstin(text: String): String? {
        val cand = Regex("""[A-Za-z0-9]{15}""")
        val cleaned = text.uppercase().replace(Regex("""(?<=[A-Z0-9])\s(?=[A-Z0-9]{1,6}\b)"""), "")
        for (t in listOf(text.uppercase(), cleaned)) for (m in cand.findAll(t)) {
            val s = m.value
            if (m.range.first > 0 && t[m.range.first - 1].isLetterOrDigit()) continue
            if (m.range.last + 1 < t.length && t[m.range.last + 1].isLetterOrDigit()) continue
            val fixed = fixGstin(s) ?: continue
            return fixed
        }
        return null
    }

    private fun fixGstin(s: String): String? {
        val toD = mapOf('O' to '0', 'I' to '1', 'L' to '1', 'S' to '5', 'B' to '8', 'Z' to '2', 'G' to '6')
        val toL = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G')
        val c = s.toCharArray()
        for (i in c.indices) {
            val digit = i in 0..1 || i in 7..10
            val letter = i in 2..6 || i == 11
            if (digit && !c[i].isDigit()) c[i] = toD[c[i]] ?: return null
            if (letter && !c[i].isLetter()) c[i] = toL[c[i]] ?: return null
        }
        if (c[13] == '2') c[13] = 'Z'
        val r = String(c)
        return if (Regex("""\d{2}[A-Z]{5}\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]""").matches(r)) r else null
    }

    private fun readHeader(rows: List<Row>, tableStart: Int, mh: Double, words: List<OcrWord>): Header {
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
        val consigneeAt = top.indexOfFirst { BUYER.containsMatchIn(it.text) }
        val gstins = top.mapIndexedNotNull { i, r -> gstin(r.text)?.let { i to it } }
        val buyerGstin = gstins.firstOrNull { (i, _) -> BUYER.containsMatchIn(top[i].text) || Regex("""(?i)party|buyer|customer|recipient""").containsMatchIn(top[i].text) }?.second
            ?: gstins.firstOrNull { consigneeAt >= 0 && it.first > consigneeAt }?.second
        val supplierGstin = gstins.firstOrNull { it.second != buyerGstin }?.second
        val firstGstinRow = gstins.firstOrNull { it.second == supplierGstin }?.first ?: tableStart
        val midX = (top.flatMap { it.words }.maxOfOrNull { it.box.r } ?: 0) / 2.0
        val skip = Regex("""(?i)invoice|tax|original|duplicate|triplicate|distributor|gstin|bill|subject|jurisdiction|e-?mail|mobile|phone|state|debit|credit|memo|copy|estimate|quotation|challan|manager|proprietor|signator|signature|receiver|partner|authori|for\s|packing|packed|cart(o|oo)n|transport|order|consignee|delivery|dispatch""")
        // The supplier's name is usually printed largest.
        val phrases = top.flatMap { r -> r.segments(gap) }.map { s -> s.joinToString(" ") { it.text } to s.map { it.box.h }.sorted()[s.size / 2] }
        val nameZone = if (gstins.isNotEmpty()) top.take(firstGstinRow + 1) else top
        val zonePhrases = nameZone.flatMap { r -> r.segments(gap) }.map { s -> s.joinToString(" ") { it.text } to s.map { it.box.h }.sorted()[s.size / 2] }
        val tallest = (zonePhrases.ifEmpty { phrases }).filter { (s, _) -> s.count { it.isLetter() } >= 8 && s.trim().contains(' ') && !skip.containsMatchIn(s) && !BUYER.containsMatchIn(s) }.maxByOrNull { it.second }
        val supplierName = tallest?.takeIf { it.second >= 1.25 * mh }?.first
            ?: top.take(firstGstinRow).flatMap { r -> r.segments(gap).map { s -> s.joinToString(" ") { it.text } } }
                .firstOrNull { s -> s.length >= 5 && s.none { it.isLowerCase() } && s.count { it.isLetter() } >= 5 && !skip.containsMatchIn(s) }
        val nameRow = top.indexOfFirst { r -> supplierName != null && r.text.contains(supplierName) }
        val addrFrom = if (gstins.isNotEmpty() && firstGstinRow > nameRow && !Regex("""(?i)^\s*gstin""").containsMatchIn(top[firstGstinRow].text) &&
            top[firstGstinRow].text.substringBefore("GSTIN").substringBefore("Gstin").count { it.isLetter() } >= 4) firstGstinRow - 1 else firstGstinRow
        val addressRows = top.withIndex().filter { (i, _) -> i > addrFrom && (consigneeAt < 0 || i < consigneeAt) }
            .map { (_, r) -> r.segments(gap).firstOrNull()?.takeIf { it.first().box.l < midX }?.joinToString(" ") { it.text }.orEmpty() }
            .map { it.replace(Regex("""(?i)\s*gstin\s*(/\s*uin)?\s*[:.]?\s*[A-Z0-9]{15}.*$"""), "").trim() }
            .filter { s -> s.isNotEmpty() && !Regex("""(?i)^gstin|cin\s*:|pan\b|e-?mail|website|^state\b|jurisdiction""").containsMatchIn(s) }
        // Buyer: "Buyer : Shivtej Footwear....Akluj" on the same line, or the line below the label.
        val buyerName = if (consigneeAt >= 0) {
            val segsHere = top[consigneeAt].segments(gap).map { s -> s.joinToString(" ") { it.text } }
            val at = segsHere.indexOfFirst { BUYER.containsMatchIn(it) }
            val seg = segsHere.getOrNull(at).orEmpty()
            // "Buyer" and ": Shivtej Footwear…" may be apart on the line: take what follows the label on the same line.
            val sameLine = BUYER.find(seg)?.let { seg.substring(it.range.last + 1) }.orEmpty().trim().trimStart(':', '.', '-').trim()
                .ifEmpty { segsHere.drop(at + 1).firstOrNull { it.trim().trimStart(':').trim().count(Char::isLetter) >= 3 && !LABELS.containsMatchIn(it) }.orEmpty() }
            val rest = sameLine
                .replace(Regex("""(?i)^[\s&:.\-]*((delivery|billing|shipping)\s*)?((address|name|details)\b)?[\s.:\-]*"""), "")
                .replace(Regex("""\.{2,}"""), " ").replace(Regex("""\s+"""), " ").trim()
            if (rest.count { it.isLetter() } >= 3 && gstin(rest) == null) rest.trimStart(':', ' ')
            else top.getOrNull(consigneeAt + 1)?.segments(gap)?.firstOrNull()?.joinToString(" ") { it.text }?.takeIf { !LABELS.containsMatchIn(it) }
        } else null
        val contact = Regex("""(?i)(contact|mobile|mob|phone|ph|tel|cell)\.?\s*(no)?\.?\s*[:.]?\s*(\+?91[\s-]?)?(\d[\d ]{7,12}\d)""")
            .find(top.take(if (consigneeAt >= 0) consigneeAt else top.size).joinToString("\n") { it.text })?.groupValues?.get(4)?.replace(" ", "")
        val invoiceNo = (after(Regex("""(?i)\bin[vy][o0][il1|]?c[e3]\s*(no|number|#)\.?\s*:?""")) ?: after(Regex("""(?i)\b(inv|bill|memo|voucher)\.?\s*(no|number|#)\.?\s*:?""")))
            ?.split(' ')?.firstOrNull { t -> t.any(Char::isDigit) }?.trim(',', ';')
        val dateText = after(Regex("""(?i)(invoice\s*|bill\s*)?date\s*:?"""))
        val date = (dateText?.let { DATE.find(it)?.value } ?: DATE.find(all)?.value)?.replace(Regex("""(?<=[-/. ])0(?=[a-z]{2})"""), "O")
        return Header(
            supplierName = supplierName,
            supplierAddress = addressRows.joinToString(" ").ifEmpty { null },
            supplierGstin = supplierGstin, supplierContact = contact,
            invoiceNo = invoiceNo, invoiceDate = date,
            orderNo = after(Regex("""(?i)order\s*no\.?[^:]*:"""))?.split(' ')?.firstOrNull(),
            transporter = after(Regex("""(?i)transporter(\s*details)?\s*:?""")) ?: after(Regex("""(?i)\btransport\s*:""")),
            cartons = after(Regex("""(?i)no\.?\s*of\s*cart(o|oo)ns?\s*:?""")),
            packing = after(Regex("""(?i)packing\s*/\s*cart(o|oo)ns?\s*:?""")),
            buyerName = buyerName, buyerGstin = buyerGstin,
        )
    }
}

/**
 * The scanner's own memory of bill layouts: when the person fixes which column is which for a supplier,
 * the next bill from the same supplier is read the same way. Stays on the phone.
 */
interface LayoutMemory {
    /** What this supplier's column with this printed heading means, or null to work it out. */
    fun kindFor(header: Header, label: String): InvoiceParser.Kind?
}

/** A simple [LayoutMemory]: supplier key (GSTIN, else name) → heading → meaning. */
class TaughtLayouts(val map: Map<String, Map<String, InvoiceParser.Kind>>) : LayoutMemory {
    override fun kindFor(header: Header, label: String): InvoiceParser.Kind? = key(header)?.let { map[it]?.get(norm(label)) }

    companion object {
        fun key(h: Header): String? = h.supplierGstin ?: h.supplierName?.uppercase()?.replace(Regex("""[^A-Z0-9]"""), "")?.ifEmpty { null }
        fun norm(label: String) = label.lowercase().replace(Regex("""[^a-z0-9%]"""), "")
    }
}
