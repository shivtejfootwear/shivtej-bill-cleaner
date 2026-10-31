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
    /** "HSW-012-NAVY-239": article, colour, MRP. */
    private val CODE_MRP = Regex("""^((?=[^-]*\d|.*-[^-]*\d[^-]*-)[A-Za-z0-9.]+(?:-[A-Za-z0-9.]+)*?)-([A-Za-z][A-Za-z./]*)-(\d{2,4})$""")
    // "MRP:64029 255.00": the HSN code (footwear, chapter 64) glued after the label is skipped, the price after it taken.
    private val MRP = Regex("""(?i)M\s*\.?\s*R\s*\.?\s*P\s*\.?\s*[:.;]?\s*(?:64\d{3,6}\s*[:.;]?\s*)?(?:Rs\.?|₹)?\s*(\d{1,6}(?:\.\d{1,2})?)""")
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
    private val UNITS = setOf("pair", "pairs", "prs", "pr", "pcs", "pc", "nos", "no", "set", "sets", "%", "pkt", "box", "dz", "doz",
        "palrs", "palr", "paiis", "parrs", "pa1rs", "pars", "pairss", "paire", "palrs.", "pairs.")
    /** "T O T A L" (letters spaced out in a PDF) → "TOTAL". */
    fun squash(t: String) = t.replace(Regex("""\b(?:[A-Za-z] ){2,}[A-Za-z]\b""")) { it.value.replace(" ", "") }

    private val STOP = Regex("""(?i)\b(continued|amount\s*(rs\.?)?\s*in\s*words|rupees|e\s*&\s*o\s*e|our\s+bankers|bank\s+(name|details)|terms\s*(&|and)?\s*conditions|declaration|authori[sz]ed\s+signatory|output\s*[cs]gst|sub\s*-?\s*total|grand\s*total|round(ed|ing)?\s*off)\b|(?i)\bitems?\s*:\s*\d+|^\s*(less|add)\s*:|(?i)^\s*(dis|disc|discount)\b.*\(\s*-\s*\)|(?i)^\s*(output\s+)?(c|s|i|ut)\s?gst\b""")
    private val CHARGE = Regex("""(?i)^(freight|transport|packing|forwarding|cartage|hamali|courier|insurance|postage|loading)""")

    /** Number in a cell: "1,049", "9.82 %", "6 pair", "₹ 1,07,350.00", "(0.42)". Null when it is not one number. */
    fun number(text: String): BigDecimal? {
        val parts = text.trim().replace("(-)", "-").replace(Regex("""^-\s+"""), "-").split(' ').map { it.trim() }.filter { it.isNotEmpty() && it.lowercase().trim('.', ':') !in UNITS && it != "₹" && !it.equals("rs", true) && !it.equals("rs.", true) }
        // "4,521.60 nt": a scrap of another sheet's text beside the amount.
        val parts2 = if (parts.size == 2) parts.filterNot { p -> p.length <= 2 && p.all(Char::isLetter) } else parts
        if (parts2.size != 1) return null
        var t = parts2[0].replace(Regex("""\(\d{1,2}(\.\d{1,3})?%\)$"""), "").replace(Regex("""^(₹|(?i:rs\.?))"""), "").removeSuffix("%").removeSuffix("/-")
        // "4449:52", "0:00": a colon read for the decimal point.
        if (Regex("""^-?\d[\d,]*:\d{2}$""").matches(t)) t = t.replace(':', '.')
        // "209,3": a comma for the decimal point (one or two digits after it, no other comma).
        if (Regex("""^\d+,\d{1,2}$""").matches(t)) t = t.replace(',', '.')
        // "2,504,44": a comma read for the decimal point.
        if (Regex("""^\d{1,3}(,\d{3})*,\d{2}$""").matches(t)) t = t.substring(0, t.lastIndexOf(',')) + "." + t.substring(t.lastIndexOf(',') + 1)
        // "6pair", "12prs", "5pcs" read as one word.
        Regex("""(?i)^([\d.,]+)(pa[il1]?rs?|prs?|pcs?|nos?|sets?)$""").matchEntire(t)?.let { t = it.groupValues[1] }
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
        var carried: Map<String, Kind>? = null
        var carriedFrom = 0
        // "Page 1 of 1" then a "Page 2 of 2": the later sheet is another bill (scanned with it by mistake) and is left out.
        val pageOf = Regex("""(?i)page\s*(?:no\.?)?\s*(\d+)\s*(?:of|/)\s*(\d+)""")
        var lastOf: Pair<Int, Int>? = null
        pages.forEachIndexed { i, p ->
            val r = parsePage(p, items.size, memory, i, carried)
            val po = r.header.pageNote?.let { pageOf.find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            val digits = { s: String? -> s?.filter(Char::isDigit)?.takeIf { it.length >= 3 } }
            val otherBill = i > 0 && (
                // (The same page twice is not dropped here: the repeated lines are merged further down.)
                (lastOf != null && po != null && po.first > 1 && po != lastOf && (lastOf!!.first >= lastOf!!.second || po.second != lastOf!!.second)) ||
                    // A sheet with its own bill head (GSTIN) and another invoice number, as its first page: another bill.
                    ((po?.first ?: r.header.pageNote?.let { Regex("""(?i)page\D{0,6}(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1) == 1 && header.supplierGstin != null && r.header.supplierGstin != null &&
                        digits(header.invoiceNo) != null && digits(r.header.invoiceNo) != null && digits(header.invoiceNo) != digits(r.header.invoiceNo) &&
                        // and another date too: a misread number on the same bill's next page never drops it.
                        digits(header.invoiceDate) != null && digits(r.header.invoiceDate) != null && digits(header.invoiceDate) != digits(r.header.invoiceDate)))
            if (otherBill) {
                notes += "पेज ${i + 1} किसी दूसरे बिल का है" + (r.header.pageNote?.let { " ($it)" } ?: "") + (lastOf?.let { " — पिछला पेज Page ${it.first} of ${it.second} था" } ?: "") + " — उसे छोड़ दिया। उस बिल को अलग से स्कैन करें।"
                return@forEachIndexed
            }
            if (po != null) lastOf = po
            if (r.articleCount >= 5 && r.articleCount >= carriedFrom) { carried = r.kinds; carriedFrom = r.articleCount }
            if (r.footerNumbers.isNotEmpty()) footerNumbers = r.footerNumbers
            // A line that goes on to the next page: its colour / sizes printed at the top of that page.
            if (r.lead.isNotEmpty() && items.isNotEmpty()) {
                val last = items.removeAt(items.lastIndex)
                items += continued(last, r.lead, columns)
            }
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
            // (Wide: when lines were lost in the photo the bill total is still the bill total, and the gap shows it.)
            if (far(header.printedGrandTotal, "0.3", "3.0")) { notes += "बिल का Total (${header.printedGrandTotal}) लाइनों से मेल नहीं खाता — गलत पढ़ा गया, छोड़ दिया"; header = header.copy(printedGrandTotal = null) }
            if (far(header.printedSubtotal, "0.6", "1.6")) header = header.copy(printedSubtotal = null, printedQty = null)
            // CGST and SGST are always equal: one misread ("294189" beside "294.89") or missing takes the other.
            header.cgstAmt?.let { c -> val sg = header.sgstAmt
                if (header.igstAmt == null && c.signum() > 0 && (sg == null || sg > c * BigDecimal(3) || sg * BigDecimal(3) < c)) header = header.copy(sgstAmt = c, sgstPct = header.sgstPct ?: header.cgstPct) }
            header.sgstAmt?.let { sg -> if (header.cgstAmt == null && header.igstAmt == null) header = header.copy(cgstAmt = sg, cgstPct = header.cgstPct ?: header.sgstPct) }
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
        // Discounts / charges on the whole bill ("LESS 2% special", "ADD insurance"): spread over every rupee of the lines.
        run {
            val lines = items.fold(BigDecimal.ZERO) { a, i -> a + (i.current.taxable ?: BigDecimal.ZERO) }
            val d = header.overallDiscount ?: BigDecimal.ZERO; val o = header.otherCharges ?: BigDecimal.ZERO
            if (lines.signum() > 0 && (d.signum() != 0 || o.signum() != 0)) {
                val f = (lines - d + o).divide(lines, 8, java.math.RoundingMode.HALF_UP)
                if (f > BigDecimal("0.5") && f < BigDecimal("1.5")) header = header.copy(costFactor = f)
            }
        }
        // No tax printed apart, but the bill total is the lines + 5 / 12 / 18 %: that is the GST (Taha: "₹ 1,59,258" on 1,51,674.60).
        run {
            if (header.cgstAmt != null || header.sgstAmt != null || header.igstAmt != null) return@run
            val arts = items.filter { it.kind == ItemKind.ARTICLE }
            if (arts.isEmpty() || arts.any { Calc.gstPct(it.current, header) != null }) return@run
            val g = header.printedGrandTotal ?: return@run
            val base = arts.fold(BigDecimal.ZERO) { a, i -> a + (i.current.taxable ?: BigDecimal.ZERO) }.let { b -> header.costFactor?.let { b * it } ?: b }
            if (base.signum() <= 0) return@run
            for (p in listOf("5", "12", "18")) {
                val half = Calc.r2(base * BigDecimal(p) / BigDecimal(200))
                if ((base + half + half - g).abs() <= BigDecimal.ONE + base * BigDecimal("0.001")) {
                    header = header.copy(cgstPct = BigDecimal(p).divide(BigDecimal(2)), sgstPct = BigDecimal(p).divide(BigDecimal(2)), cgstAmt = half, sgstAmt = half,
                        roundOff = header.roundOff ?: Calc.r2(g - base - half - half).takeIf { it.signum() != 0 })
                    notes += "बिल पर GST अलग नहीं छपा — Total से $p% GST माना"
                    break
                }
            }
        }
        // "CGST 892.46" without its %: the % is that amount on the lines (after the bill's own discounts), when it is a GST rate.
        run {
            val arts = items.filter { it.kind == ItemKind.ARTICLE }
            if (arts.any { Calc.gstPct(it.current, header) != null }) return@run
            val base = arts.fold(BigDecimal.ZERO) { a, i -> a + (i.current.taxable ?: BigDecimal.ZERO) }.let { b -> header.costFactor?.let { b * it } ?: b }
            if (base.signum() <= 0) return@run
            fun rate(amt: BigDecimal?, allowed: List<String>) = amt?.let { a -> (a * BigDecimal(100)).divide(base, 4, java.math.RoundingMode.HALF_UP) }
                ?.let { r -> allowed.map(::BigDecimal).firstOrNull { (it - r).abs() <= BigDecimal("0.06") } }
            val c = rate(header.cgstAmt, listOf("2.5", "6", "9", "14")); val sg = rate(header.sgstAmt, listOf("2.5", "6", "9", "14")) ?: c
            val i = rate(header.igstAmt, listOf("5", "12", "18", "28"))
            if (header.igstPct == null && i != null) header = header.copy(igstPct = i)
            else if (header.cgstPct == null && c != null) header = header.copy(cgstPct = c, sgstPct = header.sgstPct ?: sg)
        }
        // A Disc % / Disc amount column empty on (almost) every line of the bill: nothing to ask (a stray mark is not a discount).
        val nArts = items.count { it.kind == ItemKind.ARTICLE }
        if (items.count { it.kind == ItemKind.ARTICLE && it.current.discPct != null } * 5 <= nArts)
            for (k in items.indices) if (F.DISC_PCT in items[k].needs) items[k] = items[k].copy(needs = items[k].needs - F.DISC_PCT)
        if (items.count { it.kind == ItemKind.ARTICLE && it.current.discAmt != null } * 5 <= nArts)
            for (k in items.indices) if (F.DISC_AMT in items[k].needs) items[k] = items[k].copy(needs = items[k].needs - F.DISC_AMT)
        // MRP read from the description on most lines but not on some: those are doubtful (not a rate-only bill).
        if (columns.none { it.field == F.MRP }) {
            val arts = items.filter { it.kind == ItemKind.ARTICLE }
            if (arts.count { it.current.mrp != null } * 2 > arts.size)
                for (k in items.indices) if (items[k].kind == ItemKind.ARTICLE && items[k].current.mrp == null) items[k] = items[k].copy(needs = items[k].needs + F.MRP)
        }
        val cut = Heal.cutAmounts(items)
        if (cut.fixed > 0) { items.clear(); items.addAll(cut.items); notes += "फ़ोटो के किनारे पर कटी ${cut.fixed} रकम जोड़ी × रेट से पूरी कीं (छपे अंक मिलते हैं)" }
        val solved = LineSolver.rescue(items)
        if (solved.fixed > 0) { items.clear(); items.addAll(solved.items); notes += "बिल के अपने हिसाब से ${solved.fixed} लाइनें खुद मिलाईं (MRP → जोड़ी × रेट → डिस्काउंट → रकम)" }
        val healed = Heal.byColumns(items, columns)
        if (healed.fixed > 0) notes += "बिल के बाकी आर्टिकल देखकर ${healed.fixed} लाइनें खुद ठीक कीं (जैसे सब पर एक ही डिस्काउंट %/GST %, और हिसाब पूरा बैठा)"
        return ParsedInvoice(header, healed.items, notes, columns)
    }

    /** "Golden 5/1" at the top of the next page: colour and sizes of the previous page's last line. */
    private fun continued(item: LineItem, lead: List<String>, columns: List<Column>): LineItem {
        var v = item.current
        for (l in lead) {
            val m = Regex("""^([A-Za-z][A-Za-z .&/-]*?)\s+(\S*\d.*)$""").find(l)
            val colour = m?.groupValues?.get(1)?.trim() ?: l.takeIf { it.all { c -> c.isLetter() || c == ' ' } }
            val size = m?.groupValues?.get(2)?.replace(" ", "")
            if (!colour.isNullOrBlank() && v.colour.isBlank()) v = v.copy(colour = colour)
            if (!size.isNullOrBlank()) v = v.copy(sizeText = listOf(v.sizeText, (colour?.let { "$it " } ?: "") + size).filter { it.isNotBlank() }.joinToString(" | "))
        }
        val di = columns.indexOfFirst { it.field == F.ARTICLE }
        val raw = item.raw.toMutableList().also { if (di in it.indices) it[di] = (it[di] + " " + lead.joinToString(" ")).trim() }
        return item.copy(current = v, original = v, raw = raw)
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
        roundOff = b.roundOff ?: a.roundOff, pageNote = if (last) b.pageNote else a.pageNote ?: b.pageNote,   // the last page says whether more is to come
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
        listOf("qty", "quantity", "qnty", "quan", "qnt", "pcs", "pairs", "prs", "nos", "qtyprs", "qtypairs", "oty", "aty", "qly", "qtv", "qiy", "gty").forEach { put(it, Role.QTY) }
        listOf("unit", "uom", "per").forEach { put(it, Role.UNIT) }
        listOf("rate", "price", "ratepair", "unitprice", "rate₹", "raters").forEach { put(it, Role.RATE) }
        listOf("disc", "discount", "dis", "disc%", "cd", "cd%", "dis%", "discper", "d%", "less", "td", "sd", "sch", "scheme", "schdisc", "schemedisc",
            "trade", "tradedisc", "spl", "spldisc", "special", "disc1", "disc2", "disc1%", "disc2%", "cashdisc", "tdisc", "sdisc").forEach { put(it, Role.DISC) }
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
        if (n.startsWith("mrp")) return Role.MRP
        // "HSNI", "Ratel": a table line read as a letter after the heading.
        if (n.length >= 4) ROLE_WORDS[n.dropLast(1)]?.let { if (it != Role.NOISE && it != Role.UNIT) return it }
        // "THSN/SAC", "|Rate": a table line read as a letter in front of the heading.
        if (n.length >= 4 && n[0] in "tl1i|") ROLE_WORDS[n.drop(1)]?.let { if (it != Role.NOISE) return it }
        // "NewMRP", "OldMRP", "NetRate" read as one word.
        for (pre in listOf("new", "old", "net", "total", "unit")) if (n.length > pre.length + 1 && n.startsWith(pre)) ROLE_WORDS[n.removePrefix(pre)]?.let { if (it != Role.NOISE) return it }
        if (n.startsWith("pair")) return Role.QTY
        if (n.startsWith("rate")) return Role.RATE
        if (n.startsWith("qty")) return Role.QTY
        // "Quantty", "Quantiy", "Qunatity": quantity misspelt or misread.
        if (n.startsWith("quan") || n.startsWith("qunat") || n.startsWith("quant")) return Role.QTY
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

    enum class Kind { SR, DESC, HSN, GST_PCT, SIZE, COLOUR, MRP, QTY, UNIT, RATE, GROSS, DISC_PCT, DISC_AMT, TAXABLE, CGST_PCT, CGST_AMT, SGST_PCT, SGST_AMT, IGST_PCT, IGST_AMT, TOTAL, AMOUNT, OTHER, RATE_GST,
        /** A second discount column (scheme, special, trade): a % or an amount, taken off after the first. */
        DISC2 }

    private val NUMERIC = setOf(Kind.MRP, Kind.QTY, Kind.RATE, Kind.GROSS, Kind.DISC_PCT, Kind.DISC_AMT, Kind.TAXABLE, Kind.CGST_PCT, Kind.CGST_AMT,
        Kind.SGST_PCT, Kind.SGST_AMT, Kind.IGST_PCT, Kind.IGST_AMT, Kind.TOTAL, Kind.AMOUNT, Kind.RATE_GST, Kind.DISC2)

    // ================================================================ one page

    private data class PageResult(val header: Header, val items: List<LineItem>, val notes: List<String>, val columns: List<Column>,
                                  val footerNumbers: List<BigDecimal> = emptyList(),
                                  /** Text lines at the top of the table before its first article: the rest of the previous page's last line. */
                                  val lead: List<String> = emptyList(),
                                  /** What each heading meant on this page (carried to the next page of the same bill). */
                                  val kinds: Map<String, Kind> = emptyMap(), val articleCount: Int = 0)

    private fun headerScore(r: Row): Int {
        val roles = r.words.mapNotNull { role(it.text) }.filter { it in MAIN }.toSet()
        val bigNumbers = r.words.count { w -> number(w.text)?.let { it > BigDecimal(99) && w.text.contains('.') } == true }
        return if (bigNumbers >= 2) 0 else roles.size
    }

    private class Band(val start: Int, val best: Int, val end: Int)

    private class TableResult(val items: List<LineItem>, val footer: Footer, val cols: List<Col>, val footerNumbers: List<BigDecimal>,
                              val lead: List<String> = emptyList())

    private fun parsePage(page: OcrPage, startIndex: Int, memory: LayoutMemory?, pageNo: Int = 0, carried: Map<String, Kind>? = null): PageResult {
        val base = Layout.glue(Layout.dedupe(Layout.clean(page.words)))
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
        // Usual distance between two lines of the page.
        val pitch = rows.zipWithNext { a, b -> b.y - a.y }.filter { it > 0.5 * mh }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        fun looksLikeData(r: Row) = r.words.count { isNum(it.text) && (number(it.text)!! > BigDecimal(99) || it.text.contains('.')) } >= 2
        fun band(best: Int): Band {
            var bandStart = best
            val above = rows.getOrNull(best - 1)
            if (above != null && rows[best].y - above.y < 1.7 * mh && !looksLikeData(above) &&
                above.words.count { role(it.text) in MAIN } >= 2 && above.words.count { role(it.text) != null } * 2 >= above.words.size) bandStart = best - 1
            var bandEnd = best
            // (PDF text gives letter-tall boxes: heading lines can be over two letter heights apart, as far as the lines of the table.)
            while (bandEnd + 1 < rows.size && bandEnd - best < 3 && rows[bandEnd + 1].y - rows[bandEnd].y < maxOf(2.2 * mh, 1.15 * pitch) && !looksLikeData(rows[bandEnd + 1]) &&
                rows[bandEnd + 1].words.any { role(it.text) != null } &&
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
            // "HSN/SAC | Units In Pairs | Taxable Value | CGST | SGST | IGST": the tax summary under the bill, not a second item table.
            if (i != bestRow && Role.DESC !in bandRoles && Role.HSN in bandRoles && bandRoles.any { it == Role.CGST || it == Role.SGST || it == Role.IGST || it == Role.TAXABLE }) continue
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
            val known = (if (b !== b0 || (k > 0 && results.isNotEmpty())) results.lastOrNull { it.items.size >= 5 }?.cols?.associate { it.label to it.kind } else null)
                // A later page with only a few lines: its headings mean what they meant on the earlier page.
                ?: carried?.takeIf { c -> val labels = columns(rows.subList(b.start, b.end + 1), mh).map { it.label }; labels.isNotEmpty() && labels.count { it in c } * 10 >= labels.size * 8 }
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
        // Totals under a table that has article lines win over a table that has none (a tax summary read as a table).
        val f = results.filter { it.items.isEmpty() }.map { it.footer } + results.filter { it.items.isNotEmpty() }.map { it.footer }
        fun <T> last(g: (Footer) -> T?): T? = f.lastOrNull { g(it) != null }?.let(g)
        var footer = Footer(last { it.qty }, last { it.subtotal }, last { it.grand }, last { it.roundOff }, last { it.other }, last { it.discount },
            last { it.cgstPct }, last { it.cgstAmt }, last { it.sgstPct }, last { it.sgstAmt }, last { it.igstPct }, last { it.igstAmt })
        // No totals under the table (e.g. a crooked last page where the heading was not found): look for them anywhere on the page.
        if (footer.grand == null && footer.subtotal == null && footer.cgstAmt == null && footer.igstAmt == null) {
            val whole = readFooter(footerZone(rows), emptyList(), Bounds(DoubleArray(0)), mh)
            if (whole.grand != null || whole.cgstAmt != null || whole.igstAmt != null) footer = whole
        }
        if (items.isEmpty() && footer.grand == null && footer.subtotal == null) notes += "टेबल मिली पर कोई आर्टिकल लाइन नहीं पढ़ी जा सकी"
        val cols = results.firstOrNull { it.items.isNotEmpty() }?.cols ?: results.first().cols
        return PageResult(
            header.copy(
                printedGrandTotal = footer.grand, roundOff = footer.roundOff, otherCharges = footer.other, overallDiscount = footer.discount,
                cgstPct = footer.cgstPct, cgstAmt = footer.cgstAmt, sgstPct = footer.sgstPct, sgstAmt = footer.sgstAmt,
                igstPct = footer.igstPct, igstAmt = footer.igstAmt, printedQty = footer.qty, printedSubtotal = footer.subtotal,
                pageNote = pageNote(rows),
            ),
            items, notes, cols.map { Column(it.label, fieldOf(it.kind)) }, results.flatMap { it.footerNumbers },
            results.firstOrNull()?.lead.orEmpty(),
            kinds = cols.associate { it.label to it.kind }, articleCount = items.count { it.kind == ItemKind.ARTICLE },
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
        // A pasted pattern gives the columns left to right: used when the same number of columns is found.
        memory?.orderFor(header)?.takeIf { it.size == cols.size }?.let { order -> cols.forEachIndexed { i, c -> c.kind = order[i]; c.locked = true } }

        // ---- table lines
        // Words far outside the headings (the edge of another book or sheet in the photo) are not the bill's.
        val spanL = cols.minOf { it.l } - 3 * mh + offset; val spanR = cols.maxOf { it.r } + 4 * mh + offset
        val body = body.map { r -> r.words.filter { w -> w.box.r >= spanL && w.box.l <= spanR }.let { ws -> if (ws.size == r.words.size) r else Row(ws, r.y) } }.filter { it.words.isNotEmpty() }
        val tableRows = mutableListOf<Row>()
        // Pre-printed serial numbers of empty lines ("11", "12" … "27" with nothing beside them) are not lines of the bill.
        val srColX = cols.firstOrNull { it.kind == Kind.SR || norm(it.label) in setOf("no", "sno", "srno", "slno", "sr") }
        fun emptyNumbered(r: Row) = srColX != null && r.words.size == 1 && r.words[0].text.trimEnd('.').toIntOrNull()?.let { it in 1..999 } == true &&
            r.words[0].box.r < srColX.r + 3 * mh + offset
        var footerAt = body.size
        for ((i, r) in body.withIndex()) {
            val rt = squash(r.text)
            if (STOP.containsMatchIn(rt) && !hasSerial(r, cols, mh)) { footerAt = i; break }
            if (Regex("""(?i)^\s*total\b""").containsMatchIn(rt) || (r.words.firstOrNull()?.text?.equals("total", true) == true)) { footerAt = i; break }
            if (emptyNumbered(r)) continue
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
        // The amount heading lost after the discount columns ("Gross | Disc % | Sch | ???"): the numbers there,
        // smaller than the gross on every line, are the line amount; the one read as amount is the gross.
        run {
            val amt = cols.indexOfLast { it.kind == Kind.TAXABLE || it.kind == Kind.AMOUNT }.takeIf { it >= 0 } ?: return@run
            val disc = cols.indexOfLast { it.kind == Kind.DISC_PCT || it.kind == Kind.DISC_AMT || it.kind == Kind.DISC2 }
            if (disc < amt || cols[amt].locked) return@run
            val items = cellRows.filter { isItemLike(it, cols) }
            if (items.size < 3) return@run
            val cand = (disc + 1 until cols.size).firstOrNull { ci -> cols[ci].kind == Kind.OTHER && !cols[ci].locked &&
                items.count { r -> val a = r[amt]?.let { number(cellText(it)) }; val b = r[ci]?.let { number(cellText(it)) }; a != null && b != null && b < a && b > a * BigDecimal("0.4") } * 10 >= items.size * 7 }
                ?: return@run
            cols[cand].kind = cols[amt].kind; cols[amt].kind = Kind.GROSS
        }
        // A column of 4–8 digit codes beginning with 64 (footwear HSN), whatever its heading was read as, is the HSN.
        if (cols.none { it.kind == Kind.HSN }) {
            val items = cellRows.filter { isItemLike(it, cols) }
            cols.indices.firstOrNull { ci -> !cols[ci].locked && items.size >= 2 &&
                items.count { r -> Regex("""^6[34]\d{2,6}$""").matches(cellText(r[ci]).replace(" ", "").replace('o', '0').replace('O', '0')) } * 10 >= items.size * 7 }
                ?.let { cols[it].kind = Kind.HSN }
        }
        // No quantity heading read: the column whose cells say "12 Pair" / "6 PRS" is the quantity.
        if (cols.none { it.kind == Kind.QTY }) {
            val items = cellRows.filter { isItemLike(it, cols) }
            val unitRe = Regex("""(?i)^\d{1,4}(\.0+)?\s*(pa[il1]?rs?|prs?\.?|pcs\.?|nos\.?|pr)$""")
            cols.indices.filter { !cols[it].locked && cols[it].kind in setOf(Kind.OTHER, Kind.UNIT) }
                .maxByOrNull { ci -> items.count { r -> unitRe.matches(cellText(r[ci])) } }
                ?.takeIf { ci -> items.size >= 2 && items.count { r -> unitRe.matches(cellText(r[ci])) } * 10 >= items.size * 6 }
                ?.let { cols[it].kind = Kind.QTY }
        }
        inferKinds(cols, cellRows)
        findRateWithGst(cols, cellRows)
        solveByArithmetic(cols, cellRows)
        cellRows = textIntoDescription(cols, cellRows)
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
            // Rate with GST × pairs = the amount with GST (EBase).
            val withGst = v(Kind.RATE_GST)?.let { rg -> v(Kind.TOTAL)?.let { tt -> (q * rg - tt).abs() <= BigDecimal("0.06") } } == true
            return withGst || (e - t).abs() <= BigDecimal("0.06") + t.abs() * BigDecimal("0.002") || (q * r - t).abs() <= BigDecimal("0.06")
        }
        // A row with its own description, pairs and amount is a line of its own, even when its serial is hidden (a ring hole).
        fun ownLine(cells: Map<Int, List<OcrWord>>): Boolean {
            fun has(vararg k: Kind) = cols.withIndex().any { (ci, c) -> c.kind in k && number(cellText(cells[ci])) != null }
            val desc = cols.withIndex().any { (ci, c) -> c.kind == Kind.DESC && cellText(cells[ci]).count(Char::isLetter) >= 3 }
            return desc && has(Kind.QTY) && has(Kind.TOTAL, Kind.TAXABLE, Kind.AMOUNT) && (has(Kind.RATE) || has(Kind.RATE_GST))
        }
        val starts = assigned.mapIndexed { idx, (_, cells) ->
            val hasSr = srCol >= 0 && cells[srCol]?.let { ws -> serialOf(ws.joinToString("") { it.text }) } != null
            startsItem(cells) && (srCol < 0 || hasSr || idx < serialAt || fits(cells) || ownLine(cells))
        }
        val lastStart = starts.lastIndexOf(true)
        var lastItemRowIdx = -1
        fun numericOf(cells: Map<Int, List<OcrWord>>) = cells.filter { (ci, ws) -> cols[ci].kind in NUMERIC && number(ws.joinToString(" ") { it.text }) != null }.keys
        fun colNum(cells: Map<Int, List<OcrWord>>, vararg k: Kind) = cols.indexOfFirst { it.kind in k }.takeIf { it >= 0 }?.let { ci -> number(cellText(cells[ci])) }
        fun totalRow(cells: Map<Int, List<OcrWord>>): Boolean {
            val ws = cells.values.flatten()
            if (ws.size < 3 || ws.any { w -> w.text.any(Char::isLetter) && w.text.lowercase().trim('.', ':') !in UNITS }) return false
            if (ws.count { isNum(it.text) } < 3) return false
            val starters = assigned.filterIndexed { i, _ -> starts[i] }.map { it.second }
            val q = colNum(cells, Kind.QTY); val sumQ = starters.mapNotNull { colNum(it, Kind.QTY) }.fold(BigDecimal.ZERO, BigDecimal::add)
            val a = colNum(cells, Kind.TOTAL, Kind.AMOUNT, Kind.TAXABLE)
            val maxA = starters.mapNotNull { colNum(it, Kind.TOTAL, Kind.AMOUNT, Kind.TAXABLE) }.maxOrNull()
            return (q != null && sumQ.signum() > 0 && q >= sumQ) || (a != null && maxA != null && a > maxA * BigDecimal("1.5"))
        }
        // The numbers of a line the photo split in two may come just before its text part.
        var pending: Map<Int, List<OcrWord>>? = null
        val lead = mutableListOf<String>()
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
                // Text before the first article ("Golden 5/1"): the end of the last line of the page before.
                blocks.isEmpty() -> if (numericCells == 0 && idx < 4) lead += cells.values.flatten().sortedBy { it.box.l }.joinToString(" ") { it.text }.trim()
                // A lone number under the amount columns after the last item = the table total box.
                idx > lastStart && cells.keys.none { cols[it].kind == Kind.DESC || cols[it].kind == Kind.SR || cols[it].kind == Kind.HSN } &&
                    cells.values.flatten().all { isNum(it.text) } && cells.values.flatten().size in 1..2 && numericCells >= 1 -> {
                    footerAt = body.indexOf(pair.first); break
                }
                // A row of numbers only after the last article, adding up the columns (pairs, MRP, amount) = the table's total row.
                idx > lastStart && totalRow(cells) -> { footerAt = body.indexOf(pair.first); break }
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
        // A column the bill leaves empty (e.g. "Disc." on a bill billed at plain rates) is not a doubt on any line.
        if (items.size >= 3) for (ci in cols.indices) {
            val f = fieldOf(cols[ci].kind) ?: continue
            val blank = items.count { it.raw.getOrNull(ci).isNullOrBlank() }
            if (blank * 10 >= items.size * 8) for (k in items.indices) {
                val it = items[k]
                if (f in it.needs && it.raw.getOrNull(ci).isNullOrBlank()) items[k] = it.copy(needs = it.needs - f)
            }
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
        // "130 FITNESS", "3377 OFM": the serial glued to an article that starts with digits (1 + "30 FITNESS", 3 + "377 OFM").
        if (srCol >= 0) {
            val descCol = cols.indexOfFirst { it.kind == Kind.DESC }
            var prev: Int? = null
            for (k in items.indices) {
                val sr = items[k].current.sr
                val next = items.getOrNull(k + 1)?.current?.sr
                val st = sr?.toString()
                // (A later page starts after the lines read so far, not at 1.)
                val expect = prev?.plus(1) ?: listOfNotNull(startIndex + 1, next?.takeIf { it in 2..999 }?.minus(1)).firstOrNull { st != null && st.startsWith("$it") } ?: (startIndex + 1)
                if (sr != null && sr != expect && descCol >= 0 && st!!.startsWith("$expect") && st.length > "$expect".length && (next == null || next != sr + 1)) {
                    val b = blocks[k]
                    val w = b.first()[srCol]?.firstOrNull { it.text.trimEnd('.').toIntOrNull() == sr }
                    if (w != null) {
                        val first = b.first().toMutableMap()
                        first[srCol] = listOf(OcrWord("$expect.", w.box))
                        first[descCol] = listOf(OcrWord(st.substring("$expect".length), w.box)) + (b.first()[descCol] ?: emptyList())
                        items[k] = readItem(listOf(first) + b.drop(1), cols, startIndex + k + 1).let { it.copy(current = it.current.copy(sr = expect), original = it.original.copy(sr = expect)) }
                    }
                }
                // "1" for 21 (its first digit under a ring hole): the end of the serial that comes next.
                else if (sr != null && prev != null && sr <= prev && "$expect".endsWith(st!!) && (next == null || next == expect + 1))
                    items[k] = items[k].let { it.copy(current = it.current.copy(sr = expect), original = it.original.copy(sr = expect)) }
                prev = items[k].current.sr ?: prev?.plus(1)
            }
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
            return TableResult(items.map { it.copy(raw = keep.map { ci -> it.raw.getOrElse(ci) { "" } }) }, footer, kept, numbers, lead.filter { it.isNotEmpty() })
        }
        return TableResult(items, footer, cols, numbers, lead.filter { it.isNotEmpty() })
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
        Kind.IGST_PCT -> F.IGST_PCT; Kind.IGST_AMT -> F.IGST_AMT; Kind.TOTAL -> F.TOTAL; Kind.AMOUNT -> F.TAXABLE; Kind.RATE_GST -> F.RATE_GST; Kind.UNIT, Kind.OTHER, Kind.DISC2 -> null
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
        if (next == Role.RATE && last == Role.AMOUNT) return true   // "Net Rate"
        return false
    }

    /** A heading word the OCR broke in two ("Quan" "tity", "Disc" "%", "Am" "ount"): joined when only the whole is a heading word. */
    private fun mendHeading(ws: List<OcrWord>, mh: Double): List<OcrWord> {
        val out = mutableListOf<OcrWord>()
        // Two heading words read as one ("GrossDisc1%", "QtyRate"): split where both halves are heading words.
        val split = ws.flatMap { w ->
            val t = w.text
            if (role(t) != null || t.length < 5) return@flatMap listOf(w)
            val k = (2..t.length - 2).firstOrNull { k -> role(t.substring(0, k)).let { it != null && it != Role.NOISE } && role(t.substring(k)).let { it != null && it != Role.NOISE } }
                ?: return@flatMap listOf(w)
            val x = w.box.l + (w.box.w * k / t.length)
            listOf(OcrWord(t.substring(0, k), Box(w.box.l, w.box.t, x - 1, w.box.b)), OcrWord(t.substring(k), Box(x + 1, w.box.t, w.box.r, w.box.b)))
        }
        for (w in split) {
            val p = out.lastOrNull()
            if (p != null && w.box.l - p.box.r <= 0.35 * mh) {
                val joined = p.text + w.text
                if (role(joined) != null && role(joined) != Role.NOISE && (role(p.text) == null || role(w.text) == null || role(p.text) == Role.NOISE)) {
                    out[out.lastIndex] = OcrWord(joined, Box(p.box.l, min(p.box.t, w.box.t), w.box.r, max(p.box.b, w.box.b))); continue
                }
            }
            out += w
        }
        return out
    }

    private fun columns(band: List<Row>, mh: Double): List<Col> {
        // Phrases per line: words close together that belong to one heading.
        val lines = band.map { r ->
            val out = mutableListOf<Phrase>()
            for (w in mendHeading(r.words, mh)) {
                val p = out.lastOrNull()
                // "Old MRP", "New MRP": the word before a heading starts it (it does not end the heading on its left).
                val starts = norm(w.text) in setOf("old", "new", "revised", "current", "latest", "prev", "previous")
                // "per" (the unit column of Tally) is its own column, not the start of "Disc. %" beside it.
                val perAlone = p != null && p.words.size == 1 && norm(p.words[0].text) == "per" && role(w.text) in setOf(Role.DISC, Role.AMOUNT, Role.TAXABLE, Role.TOTAL)
                if (p != null && !starts && !perAlone && w.box.l - p.r <= 0.9 * mh && joinable(p.roles, role(w.text))) p.words += w else out += Phrase(mutableListOf(w))
            }
            out
        }
        // Bottom line first: its phrases are columns; a heading above covering 2+ columns is a group title ("Discount" over "%", "Amount").
        val cols = mutableListOf<Col>()
        for (line in lines.reversed()) {
            val before = cols.toList()
            val pad = 0.6 * mh
            fun ov(c: Col, p: Phrase) = min(c.r, p.r + pad) - max(c.l, p.l - pad)
            // Each column below belongs to the heading word of this line that covers it most
            // ("Disc  Disc.  Disc." over "(per) (%) Amt." are three headings, not one title over all).
            val owner = before.associateWith { c -> line.filter { ov(c, it) > 0 }.maxByOrNull { ov(c, it) } }
            for (p in line) {
                val under = before.filter { c -> ov(c, p) > 0 && owner[c] === p }
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
        // Two discount % columns (trade + scheme, "Disc1%" + "Disc2%"), or a discount % then a "Sch Disc" amount:
        // the later one is a second discount, taken off what is left after the first.
        run {
            val discs = cols.filter { it.kind == Kind.DISC_PCT || it.kind == Kind.DISC_AMT }
            val firstPct = discs.firstOrNull { it.kind == Kind.DISC_PCT }
            if (firstPct != null) for (c in discs) {
                if (c === firstPct || c.l < firstPct.l) continue
                val second = c.kind == Kind.DISC_PCT || Regex("""(?i)\b(sch|scheme|spl|special|trade|td|sd|cash|disc\s*2)""").containsMatchIn(c.label)
                if (second) c.kind = Kind.DISC2
            }
        }
        // Two MRP columns ("Old MRP", "New MRP"): the new one is the MRP; the old one is kept as printed only.
        val mrps = cols.filter { it.kind == Kind.MRP }
        if (mrps.size > 1) {
            val newer = mrps.firstOrNull { Regex("""(?i)(new|revised|current|latest)""").containsMatchIn(it.label) } ?: mrps.last()
            mrps.filter { it !== newer }.forEach { it.kind = Kind.OTHER; it.locked = true }
            newer.locked = true
        }
        // Only an "Old MRP" heading read (the "New MRP" heading beside it damaged): the column right after it is the new MRP.
        if (mrps.size == 1 && Regex("""(?i)\b(old|prev|previous)\b""").containsMatchIn(mrps[0].label)) {
            val i = cols.indexOf(mrps[0])
            val next = cols.getOrNull(i + 1)
            if (next != null && !next.locked && (next.kind == Kind.OTHER || next.kind == Kind.UNIT || Regex("""(?i)(new|revised|current)""").containsMatchIn(next.label))) {
                mrps[0].kind = Kind.OTHER; mrps[0].locked = true; next.kind = Kind.MRP; next.locked = true
            }
        }
        // Two quantity columns (cartons and pairs): the pairs are the quantity.
        val qtys = cols.filter { it.kind == Kind.QTY }
        if (qtys.size > 1) {
            val pairs = qtys.firstOrNull { Regex("""(?i)pair|prs""").containsMatchIn(it.label) }
            if (pairs != null) { qtys.filter { it !== pairs }.forEach { it.kind = Kind.OTHER; it.locked = true }; pairs.locked = true }
        }
        // "Rate" beside a "Net Rate": the first includes GST, the net one does not; the Amount is then pairs × rate with GST.
        val netAt = cols.indexOfFirst { norm(it.label).contains("netrate") }
        if (netAt >= 0) {
            cols[netAt].kind = Kind.RATE
            cols.forEachIndexed { i, c -> if (i != netAt && c.kind == Kind.RATE) c.kind = Kind.RATE_GST }
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
        // With a rate including GST, the Amount includes GST too.
        if (cols.any { it.kind == Kind.RATE_GST } && cols.none { it.kind == Kind.TAXABLE }) {
            cols.lastOrNull { it.kind == Kind.AMOUNT || it.kind == Kind.TOTAL }?.let { it.kind = Kind.TOTAL; it.locked = true }
            // The headings say it plainly: not to be re-guessed from the numbers.
            cols.filter { it.kind == Kind.RATE || it.kind == Kind.RATE_GST || it.kind == Kind.QTY }.forEach { it.locked = true }
        }
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
            val pctKind = c.kind in setOf(Kind.DISC_PCT, Kind.GST_PCT, Kind.CGST_PCT, Kind.SGST_PCT, Kind.IGST_PCT)
            // "392.52(7.45%)": the % beside the amount is what a % column holds.
            val nums = texts.mapNotNull { (if (pctKind) Layout.bracketPct(it) else null) ?: number(it) }
            val withPct = texts.count { it.contains('%') && Layout.bracketPct(it) == null }
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
     * A rate with GST beside the rate without it (its heading misread, e.g. "Net Rate" broken): one column is the other
     * × 1.05 / 1.12 / 1.18 and pairs × that one = the Amount. Then the Amount includes GST.
     */
    private fun findRateWithGst(cols: List<Col>, rows: List<Map<Int, List<OcrWord>>>) {
        if (cols.any { it.kind == Kind.RATE_GST || it.kind == Kind.TAXABLE }) return
        val items = rows.filter { isItemLike(it, cols) }
        if (items.size < 3) return
        val q = cols.indexOfFirst { it.kind == Kind.QTY }.takeIf { it >= 0 } ?: return
        val t = cols.indexOfLast { it.kind == Kind.AMOUNT || it.kind == Kind.TOTAL }.takeIf { it >= 0 } ?: return
        fun n(r: Map<Int, List<OcrWord>>, ci: Int) = r[ci]?.let { number(cellText(it)) }
        val cand = cols.indices.filter { it != q && it != t && cols[it].kind in setOf(Kind.RATE, Kind.OTHER, Kind.MRP, Kind.GROSS, Kind.AMOUNT) && !cols[it].locked }
        for (a in cand) for (b in cand) {
            if (a == b) continue
            val ok = items.count { r ->
                val av = n(r, a) ?: return@count false; val bv = n(r, b) ?: return@count false; val qv = n(r, q) ?: return@count false; val tv = n(r, t) ?: return@count false
                av.signum() > 0 && listOf("1.05", "1.12", "1.18").any { g -> (av * BigDecimal(g) - bv).abs() <= BigDecimal("0.6") + bv * BigDecimal("0.002") } &&
                    (qv * bv - tv).abs() <= BigDecimal("0.06")
            }
            if (ok * 10 >= items.size * 6) {
                cols[a].kind = Kind.RATE; cols[b].kind = Kind.RATE_GST; cols[t].kind = Kind.TOTAL
                listOf(a, b, q, t).forEach { cols[it].locked = true }
                return
            }
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
        // Two discounts (trade + scheme): the headings are right when that chain gives the amount on most lines.
        val d2Cols = cols.indices.filter { cols[it].kind == Kind.DISC2 }
        if (d2Cols.isNotEmpty() && curQ != null && curR != null && curT != null) {
            val gCol = cols.indexOfFirst { it.kind == Kind.GROSS }.takeIf { it >= 0 }; val aCol = cols.indexOfFirst { it.kind == Kind.DISC_AMT }.takeIf { it >= 0 }
            val okLines = items.count { r ->
                fun n(ci: Int?) = ci?.let { r[it]?.let { w -> number(cellText(w)) } }
                val q = n(curQ) ?: return@count false; val rt = n(curR) ?: return@count false; val t = n(curT) ?: return@count false
                val d2 = d2Cols.mapNotNull { ci -> n(ci)?.let { it to cellText(r[ci]).contains('%') } }
                chainAddsUp(n(gCol) ?: Calc.r2(q * rt), n(curD), n(aCol), d2, t)
            }
            if (okLines * 10 >= items.size * 6) return
        }
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
        // Pairs × rate = the gross column, with discounts after it: that is the gross, not the line amount.
        if (d == null && cols[t!!].kind == Kind.GROSS && cols.any { it.kind == Kind.DISC_PCT || it.kind == Kind.DISC_AMT || it.kind == Kind.DISC2 }) return
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
    private fun readItem(block0: List<Map<Int, List<OcrWord>>>, cols: List<Col>, index: Int): LineItem = runCatching { readItemChecked(block0, cols, index) }.getOrElse {
        // One line that cannot be read never stops the bill: it is shown as printed, to be filled in.
        val raw = cols.indices.map { ci -> block0.mapNotNull { r -> cellText(r[ci]).ifEmpty { null } }.joinToString(" ") }
        val v = ItemValues(article = raw.getOrNull(cols.indexOfFirst { it.kind == Kind.DESC }).orEmpty())
        LineItem("i$index", ItemKind.ARTICLE, v, v, setOf(F.QTY, F.RATE, F.TAXABLE), raw = raw)
    }

    private fun readItemChecked(block0: List<Map<Int, List<OcrWord>>>, cols: List<Col>, index: Int): LineItem {
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

    /**
     * The serial column ("Sl No.", often read as just "No.") is narrow but its heading sits far left of "Description":
     * article text that fell under it ("2 HSW-012-NAVY-239", "1FL-05-N.NAVY-319") goes to the description; the serial stays.
     */
    private fun textIntoDescription(cols: List<Col>, rows: List<Map<Int, List<OcrWord>>>): List<Map<Int, List<OcrWord>>> {
        val d = cols.indexOfFirst { it.kind == Kind.DESC }.takeIf { it > 0 } ?: return rows
        // A lone "No." heading at the far left is the serial column.
        if (cols[0].kind == Kind.OTHER && norm(cols[0].label) in setOf("no", "no.", "sno", "slno")) cols[0].kind = Kind.SR
        val left = (0 until d).filter { cols[it].kind == Kind.SR || cols[it].kind == Kind.OTHER }
        if (left.isEmpty()) return rows
        val srAt = cols.indexOfFirst { it.kind == Kind.SR }
        return rows.map { r0 ->
            // "11SOFTY 5X8" with the serial cell empty: the serial glued to the article, in the description column.
            val r = if (srAt >= 0 && r0[srAt].isNullOrEmpty()) r0[d]?.firstOrNull()?.let { w ->
                Regex("""^(\d{1,3})\.?([A-Z][A-Za-z].*)$""").matchEntire(w.text)?.let { m ->
                    val cw = w.box.w.toDouble() / w.text.length; val x = w.box.l + (cw * m.groupValues[1].length).toInt()
                    r0.toMutableMap().also { it[srAt] = listOf(OcrWord(m.groupValues[1], Box(w.box.l, w.box.t, x, w.box.b))); it[d] = listOf(OcrWord(m.groupValues[2], Box(x + 1, w.box.t, w.box.r, w.box.b))) + r0[d]!!.drop(1) }
                }
            } ?: r0 else r0
            val moved = mutableListOf<OcrWord>()
            val out = r.toMutableMap()
            for (ci in left) {
                val ws = r[ci] ?: continue
                val keep = mutableListOf<OcrWord>()
                for (w in ws) {
                    val glued = Regex("""^(\d{1,3})\.?([A-Za-z(].*)$""").matchEntire(w.text)
                    when {
                        cols[ci].kind == Kind.SR && glued != null && keep.isEmpty() -> {
                            val cw = w.box.w.toDouble() / w.text.length; val x = w.box.l + (cw * glued.groupValues[1].length).toInt()
                            keep += OcrWord(glued.groupValues[1], Box(w.box.l, w.box.t, x, w.box.b)); moved += OcrWord(glued.groupValues[2], Box(x + 1, w.box.t, w.box.r, w.box.b))
                        }
                        cols[ci].kind == Kind.SR && w.text.trim('.', ')').toIntOrNull() != null && keep.isEmpty() && moved.isEmpty() -> keep += w
                        w.text.any(Char::isLetter) || moved.isNotEmpty() -> moved += w
                        else -> keep += w
                    }
                }
                if (keep.isEmpty()) out.remove(ci) else out[ci] = keep
            }
            if (moved.isNotEmpty()) out[d] = (moved + (r[d] ?: emptyList())).sortedBy { it.box.l }
            out
        }
    }

    /** Base less a first discount (% or amount) and then the second ones (% when printed with % or when that adds up, else amounts) = amount? */
    private fun chainAddsUp(base: BigDecimal, d1Pct: BigDecimal?, d1Amt: BigDecimal?, d2: List<Pair<BigDecimal, Boolean>>, t: BigDecimal): Boolean {
        val h = BigDecimal(100)
        var left = base
        if (d1Pct != null) left -= Calc.r2(left * d1Pct / h) else if (d1Amt != null) left -= d1Amt
        // Each second discount: as a % or as an amount, whichever leads to the amount.
        fun go(i: Int, l: BigDecimal): Boolean {
            if (i == d2.size) return (l - t).abs() <= BigDecimal("0.05")
            val (x, pct) = d2[i]
            if (x < h && go(i + 1, l - Calc.r2(l * x / h))) return true
            return !pct && go(i + 1, l - x)
        }
        return go(0, left)
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
        // A word of letters in the HSN cell ("GRAPHITE 640319") is the end of the description that ran into that column.
        val hsnCis = cols.withIndex().filter { it.value.kind == Kind.HSN }.map { it.index }
        fun spill(row: Map<Int, List<OcrWord>>) = hsnCis.flatMap { ci -> cellText(row[ci]).split(' ').filter { w -> w.length >= 2 && w.none(Char::isDigit) && w.count(Char::isLetter) >= 2 } }
        val descCis = cols.withIndex().filter { it.value.kind == Kind.DESC }.map { it.index }
        // No serial column: text in an unnamed column left of the description ("1 REMO" left of "( 81.6854 )") is its start.
        val leftCis = if (cols.none { it.kind == Kind.SR } && descCis.isNotEmpty()) cols.indices.filter { it < descCis.first() && cols[it].kind == Kind.OTHER } else emptyList()
        fun left(row: Map<Int, List<OcrWord>>) = leftCis.joinToString(" ") { cellText(row[it]) }.trim()
        var descLines = descCis.flatMap { ci -> block.map { row -> ((if (ci == descCis.first()) left(row) + " " else "") + cellText(row[ci]) + (if (ci == descCis.last()) spill(row).joinToString(" ", prefix = " ") else "")).replace(OWN_TEXT, "").trim() } }.filter { it.isNotEmpty() }.toMutableList()
        if (sr == null && descLines.isNotEmpty()) {
            // "1 1180-Paralite…" when there is no Sr heading; ") 29 Indus…" when a mark beside the serial was read too.
            descLines[0] = descLines[0].trimStart(')', '(', '|', '.', ',', ']', '[', '/', ' ')
            val t = descLines[0].split(' ')
            t.first().trim('.', ')').toIntOrNull()?.takeIf { it in 1..999 && t.size > 1 }?.let { sr = it; descLines[0] = t.drop(1).joinToString(" ") }
        }
        // "990 -7/1", "64041 190 9/1": the line's sizes printed so far right that they sit in the HSN column.
        val hsnSizes = mutableListOf<String>()
        val sizeWord = Regex("""^[-–_(]?([0-9Oo]{1,2}(?:[xX][0-9]{1,2})?/[0-9]{1,2})\)?$""")
        val hsn = hsnCis.joinToString("") { ci -> block.joinToString("") { row -> cellText(row[ci]).split(' ').filter { w -> w.any(Char::isDigit) }
            .filter { w -> sizeWord.find(w)?.let { m -> hsnSizes += m.groupValues[1].replace('O', '0').replace('o', '0'); false } ?: true }.joinToString("") } }.filter { it.isLetterOrDigit() }

        descLines = descLines.map { it.replace(Regex("""\(\s+"""), "(").replace(Regex("""\s+\)"""), ")") }.toMutableList()
        // "MRP64029 :615.00", "MRP64029 (: 645.00" (Patel): the HSN printed beside it ran into "MRP" — the HSN goes back to its column.
        var gluedHsn: String? = null
        Regex("""(?i)M\.?R\.?P\.?\s*(64\d{3,6})\s*[(\[{|]?\s*[:;.]\s*(\d{2,5}[.,]\d{2})""").let { re ->
            val all = descLines.joinToString("\n")
            re.find(all)?.let { m -> gluedHsn = m.groupValues[1]; descLines = all.replace(m.value, "MRP : " + m.groupValues[2].replace(',', '.')).lines().toMutableList() }
        }
        val joined = descLines.joinToString("\n")
        val mrpCol = anyNum(Kind.MRP)
        val mrpMatch = if (mrpCol == null) MRP.find(joined) else null
        val mrp = mrpCol ?: mrpMatch?.groupValues?.get(1)?.let { BigDecimal(it) }

        // ---- text: article code, name, brand, colour, sizes
        val firstLine = descLines.firstOrNull().orEmpty().let { if (mrpMatch != null) it.replace(mrpMatch.value, "").trim() else it }
        val tokens = firstLine.split(' ').filter { it.isNotEmpty() && it != ":" && !it.equals("MRP", true) }
        val article: String; val name: String; val brand: String; var colour: String; var sizeText: String
        var mrpInCode: BigDecimal? = null
        val codeToken = tokens.firstOrNull().orEmpty()
        val restLines = joined.lines().drop(1).joinToString("\n").let { if (mrpMatch != null) it.replace(mrpMatch.value, "") else it }
            .lines().map { it.trim() }.filter { it.isNotEmpty() && it != ":" && !MRP.matches(it) && number(it) == null }
        // "HSW-012-NAVY-239", "FL-05-N.NAVY-319": article - colour - MRP in one code (Tally bills of many wholesalers).
        val codeMrp = CODE_MRP.matchEntire(codeToken.trimEnd('.', ',', '|'))
        if (codeMrp != null) {
            article = codeMrp.groupValues[1]; colour = codeMrp.groupValues[2]; name = ""
            brand = article.split('-').firstOrNull { it.any(Char::isLetter) }?.filter(Char::isLetter).orEmpty()
            mrpInCode = BigDecimal(codeMrp.groupValues[3])
            sizeText = (tokens.drop(1) + restLines).joinToString(" ").trim()
        } else if (codeToken.contains('-') && codeToken.any(Char::isDigit) && codeToken.split('-').size >= 3) {
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
            // "30 FITNESS 6X10", "377 OFM 5-8": a number then the name — both are the article.
            val k = tokens.indexOfFirst { t -> t.any(Char::isDigit) }.let { k -> if (k == 0 && tokens[0].all(Char::isDigit) && tokens.getOrNull(1)?.all { it.isLetter() } == true) 1 else k }
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
            // "TOUCH 16-714 7*10 GRAPHITE": the size range sits between the article and the colour.
            val range = Regex("""(?<![\w/])(\d{1,2})\s*[*xX×]\s*(\d{1,2})(?![\w/])""")
            range.find(colour)?.let { m ->
                sizeText = listOf(m.value.replace(" ", ""), sizeText).filter { it.isNotBlank() }.joinToString(" | ")
                colour = colour.removeRange(m.range).replace(Regex("""\s+"""), " ").trim('-', ' ')
            }
        }
        // "11X1 3": a size range the photo broke in two.
        textOf(Kind.SIZE, " ").takeIf { it.isNotEmpty() }?.let { sizeText = it.trimEnd('.').replace(Regex("""\b(\d{1,2}[xX×]1)\s([0-5])\b"""), "$1$2") }
        textOf(Kind.COLOUR, " ").takeIf { it.isNotEmpty() }?.let { colour = it }
        hsnSizes.filter { it !in sizeText.replace(" ", "") }.takeIf { it.isNotEmpty() }?.let { extra ->
            sizeText = (listOf(sizeText.trim(' ', '|', '_')) + extra).filter { it.isNotBlank() }.joinToString(" ")
        }

        val kind = if (CHARGE.containsMatchIn(article.ifEmpty { codeToken }) || CHARGE.containsMatchIn(firstLine)) ItemKind.CHARGE else ItemKind.ARTICLE
        val amount = firstNum(Kind.AMOUNT)
        var v = ItemValues(
            sr = sr, article = article, name = name, brand = brand, colour = colour, sizeText = sizeText, hsn = gluedHsn?.takeIf { hsn.length <= 4 && !hsn.startsWith(it) }?.let { it + hsn } ?: hsn,
            qty = firstNum(Kind.QTY), mrp = mrp, rate = firstNum(Kind.RATE), gross = firstNum(Kind.GROSS),
            discPct = firstNum(Kind.DISC_PCT), discAmt = firstNum(Kind.DISC_AMT), taxable = firstNum(Kind.TAXABLE) ?: amount,
            cgstPct = firstNum(Kind.CGST_PCT), cgstAmt = firstNum(Kind.CGST_AMT), sgstPct = firstNum(Kind.SGST_PCT), sgstAmt = firstNum(Kind.SGST_AMT),
            igstPct = firstNum(Kind.IGST_PCT), igstAmt = firstNum(Kind.IGST_AMT), gstPct = firstNum(Kind.GST_PCT), printedTotal = firstNum(Kind.TOTAL),
            rateGst = firstNum(Kind.RATE_GST),
        )
        // The MRP at the end of the article code: taken when there is no MRP column and it is above the rate.
        if (v.mrp == null && mrpInCode != null && cols.none { it.kind == Kind.MRP }) {
            val r = v.rate ?: v.rateGst ?: v.taxable?.let { t -> v.qty?.takeIf { it.signum() > 0 }?.let { t.divide(it, 2, java.math.RoundingMode.HALF_UP) } }
            if (r == null || (mrpInCode >= r && mrpInCode <= r * BigDecimal(5))) v = v.copy(mrp = mrpInCode)
        }
        // "… MRP" in the description and "699 6x8" under Size: an MRP printed in the text that ran into the next column.
        if (v.mrp == null && cols.none { it.kind == Kind.MRP }) {
            val textCols = cols.indices.filter { cols[it].kind in setOf(Kind.DESC, Kind.SIZE, Kind.COLOUR, Kind.OTHER, Kind.HSN) }
            val wide = block.joinToString("\n") { row -> textCols.joinToString(" ") { cellText(row[it]) } }
            MRP.find(wide)?.let { m ->
                val num = m.groupValues[1]
                fun strip(x: String) = x.replace(Regex("""(?i)\bM\.?R\.?P\.?\s*[:.]?\s*(Rs\.?|₹)?\s*$num\b"""), "").replace(Regex("""(?i)\bM\.?R\.?P\.?\s*$"""), "")
                    .replace(Regex("""^\s*$num\b"""), "").replace(Regex("""\s+"""), " ").trim(' ', '|')
                val hsnLeft = if (v.hsn.startsWith(num) && v.hsn.length - num.length in 4..8) v.hsn.substring(num.length) else v.hsn
                v = v.copy(mrp = BigDecimal(num), sizeText = strip(v.sizeText), colour = strip(v.colour), article = strip(v.article), hsn = hsnLeft)
            }
        }
        // Two discounts one after the other (trade % then scheme % or amount): the line's discount is what both take off.
        var chained = false
        run {
            val d2 = cols.withIndex().filter { it.value.kind == Kind.DISC2 }.mapNotNull { (ci, _) -> block.firstNotNullOfOrNull { row -> number(cellText(row[ci])) }?.let { it to cellText(first[ci]).contains('%') } }
            if (d2.isEmpty()) return@run
            val base = v.gross ?: v.qty?.let { q -> v.rate?.let { Calc.r2(q * it) } } ?: return@run
            val t = v.taxable ?: return@run
            if (chainAddsUp(base, v.discPct, v.discAmt, d2, t)) { v = v.copy(discPct = null, discAmt = base - t); chained = true }
        }
        // "₹ 392.52 (7.45%)" in a Discount column, "₹ 341.73 (5%)" in a GST column (Vyapar): an amount with its % beside it.
        fun bracket(k: Kind): Pair<BigDecimal, BigDecimal>? = cols.indexOfFirst { it.kind == k }.takeIf { it >= 0 }?.let { ci ->
            block.firstNotNullOfOrNull { row -> cellText(row[ci]).takeIf { it.isNotEmpty() }?.let { t -> Layout.bracketPct(t)?.let { p -> number(t)?.let { it to p } } } }
        }
        bracket(Kind.DISC_PCT)?.let { (amt, p) -> v = v.copy(discPct = p, discAmt = v.discAmt ?: amt) }
        bracket(Kind.DISC_AMT)?.let { (amt, p) -> v = v.copy(discAmt = amt, discPct = v.discPct ?: p) }
        val gstBracket = bracket(Kind.GST_PCT)
        gstBracket?.let { (_, p) -> v = v.copy(gstPct = p) }
        // The amount includes that GST (pairs × price − discount + GST = amount): the amount before GST is worked out.
        if (gstBracket != null && v.taxable != null && cols.none { it.kind == Kind.TAXABLE }) {
            val tax = gstBracket.first
            val base = v.qty?.let { q -> v.rate?.let { Calc.r2(q * it) } }?.let { g -> g - (v.discAmt ?: BigDecimal.ZERO) }
            if (base != null && (base + tax - v.taxable!!).abs() <= BigDecimal("1.00")) v = v.copy(printedTotal = v.taxable, taxable = v.taxable!! - tax)
        }
        // Pairs not found under a Qty heading (its heading misread and joined to the next): "42 Pairs" anywhere on the line.
        val qtyCell = cols.indexOfFirst { it.kind == Kind.QTY }.takeIf { it >= 0 }?.let { ci -> block.joinToString(" ") { cellText(it[ci]) } }
        if (v.qty == null && (qtyCell == null || Regex("""\b6\d{3,7}\b""").containsMatchIn(qtyCell))) {
            val all = cols.indices.filter { cols[it].kind != Kind.DESC }.joinToString(" ") { ci -> block.joinToString(" ") { cellText(it[ci]) } }
            Regex("""(?i)(?<![\d.,])(\d{1,4})\s*(pa[il1]?rs?|prs)\b""").findAll(all).map { it.groupValues[1] }.distinct().singleOrNull()?.toBigDecimalOrNull()?.let { v = v.copy(qty = it) }
        }
        // "MRP 0.00": a line without MRP (non-brand goods billed by rate only) — not a misreading.
        if (v.mrp?.signum() == 0) v = v.copy(mrp = null)
        // "CD% 10.00" printed on the line but Amount = pairs × rate: the cash discount is taken at the bill's foot
        // ("Cash Discount 1310.32"), so it is the bill's discount, not this line's (Charan Care, Next Grip memos).
        var discAtFoot = false
        run {
            val d = v.discPct ?: return@run; val q = v.qty ?: return@run; val r = v.rate ?: return@run; val t = v.taxable ?: return@run
            if (v.gross != null || v.discAmt != null || d.signum() <= 0) return@run
            val gross = q * r
            if ((gross - t).abs() <= BigDecimal("0.06") && (gross * (BigDecimal(100) - d) / BigDecimal(100) - t).abs() > BigDecimal("0.06")) { v = v.copy(discPct = null); discAtFoot = true }
        }
        // Amount printed with GST (pairs × rate with GST): the amount before GST is worked out from it.
        if (v.rateGst != null && v.printedTotal != null && v.taxable == null && cols.none { it.kind == Kind.TAXABLE }) {
            val g = v.gstPct ?: v.rate?.takeIf { it.signum() > 0 }?.let { r -> (v.rateGst!! / r - BigDecimal.ONE).multiply(BigDecimal(100)).setScale(0, java.math.RoundingMode.HALF_UP) }
            if (g != null) v = v.copy(taxable = Calc.r2(v.printedTotal!!.multiply(BigDecimal(100)).divide(BigDecimal(100) + g, 10, java.math.RoundingMode.HALF_UP)), gstPct = v.gstPct ?: g)
        }
        // Disc column printed as an amount (e.g. "88.35") instead of a %: keep it as the amount.
        if (v.discPct != null && v.discAmt == null && cols.none { it.kind == Kind.DISC_AMT }) {
            val dTxt = cellText(cols.indexOfFirst { it.kind == Kind.DISC_PCT }.let { first[it] })
            val base = v.gross ?: v.qty?.let { q -> v.rate?.let { q * it } }
            if (!dTxt.contains('%') && v.discPct!! > BigDecimal(100) && base != null) v = v.copy(discAmt = v.discPct, discPct = null)
        }

        // ---- a decimal point the photo lost or misread ("745 %" for 7.45 %, "1.29 5.68" for 1,295.68):
        // the reading that makes the line's own arithmetic work is taken, and the printed cell shown that way.
        val raw = cols.indices.map { ci -> block.mapNotNull { cellText(it[ci]).replace(OWN_TEXT, "").trim().ifEmpty { null } }.joinToString(" ") }.toMutableList()
        // Words that ran from the description into the HSN column go back to the description (shown as printed).
        if (descCis.isNotEmpty()) for (ci in hsnCis) {
            val moved = block.flatMap { spill(it) }
            if (moved.isEmpty()) continue
            raw[descCis.last()] = (raw[descCis.last()] + " " + moved.joinToString(" ")).trim()
            raw[ci] = raw[ci].split(' ').filter { w -> w !in moved }.joinToString(" ")
        }
        val plainMismatch = v.gross == null && v.discPct == null && v.discAmt == null && v.rateGst == null && v.qty != null && v.rate != null && v.taxable != null &&
            (v.qty * v.rate - v.taxable).abs() > BigDecimal("0.06") && cols.none { it.kind == Kind.DISC2 }
        if (v.taxable == null || v.qty == null || (v.discPct ?: BigDecimal.ZERO) >= BigDecimal(100) || Calc.checkLine(v).isNotEmpty() || plainMismatch)
            repairDecimals(v, cols, raw)?.let { r ->
                // Pairs worked out with no line discount though a CD % is printed: that % is taken at the foot.
                if (r.discPct?.signum() == 0 && (v.discPct?.signum() ?: 0) > 0) { v = r.copy(discPct = null); discAtFoot = true } else v = r
            }
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
        if (v.discPct == null && Kind.DISC_PCT in has && !chained && !discAtFoot) needs += F.DISC_PCT
        if (v.discAmt == null && Kind.DISC_AMT in has) needs += F.DISC_AMT
        if (v.taxable == null) needs += F.TAXABLE
        val mrpCi = cols.indexOfFirst { it.kind == Kind.MRP }
        val mrpPrintedZero = mrpCi >= 0 && number(block.firstNotNullOfOrNull { cellText(it[mrpCi]).ifEmpty { null } } ?: "")?.signum() == 0
        // No MRP column at all: a bill by rate only (checked again across the bill in parse()).
        if (v.mrp == null && kind == ItemKind.ARTICLE && !mrpPrintedZero && mrpCi >= 0) needs += F.MRP
        // Amount per pair far from the MRP (a part of the number lost): one of the two is misread.
        val q0 = v.qty; val t0 = v.taxable; val m0 = v.mrp
        if (kind == ItemKind.ARTICLE && q0 != null && t0 != null && m0 != null && q0.signum() > 0 && m0.signum() > 0) {
            val perPair = t0.divide(q0, 4, java.math.RoundingMode.HALF_UP)
            if (perPair < m0 * BigDecimal("0.05") || perPair > m0 * BigDecimal("1.3")) { needs += F.TAXABLE; needs += F.MRP }
        }
        // An MRP many times the rate ("121,099.00" = pairs glued to the MRP) is a misreading.
        if (kind == ItemKind.ARTICLE && v.mrp != null && v.rate != null && v.rate.signum() > 0 && v.mrp > v.rate * BigDecimal(6)) needs += F.MRP
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
        fun ok(c: ItemValues) = c.qty != null && c.rate != null && c.taxable != null && Calc.checkLine(c).isEmpty() &&
            (c.gross != null || c.discPct != null || c.discAmt != null || (c.qty * c.rate - c.taxable).abs() <= BigDecimal("0.06"))
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
        val qc = col(Kind.QTY)
        // Pairs misread: a pen tick read as a digit ("6✓" → 62), or a digit that looks like another (8 ↔ 3, 6, 9, 0; 1 ↔ 7).
        if (qc >= 0 && v.qty != null && v.rate != null && v.taxable != null && v.rate.signum() > 0) {
            val look = mapOf('8' to "3690", '3' to "8", '6' to "85", '5' to "6", '9' to "8", '0' to "8", '1' to "7", '7' to "1")
            val q = v.qty.stripTrailingZeros().toPlainString()
            val cands = mutableSetOf<String>()
            if (q.length >= 2) cands += q.dropLast(1)
            for ((i, c) in q.withIndex()) look[c]?.forEach { o -> cands += q.substring(0, i) + o + q.substring(i + 1) }
            for (cq in cands) {
                val nq = cq.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
                for (dd in listOfNotNull(v.discPct, if (v.discPct != null) BigDecimal.ZERO else null).ifEmpty { listOf(BigDecimal.ZERO) }) {
                    val nv = v.copy(qty = nq, discPct = if (v.discPct != null) dd else null)
                    tries += Try(nv, listOf(qc to (cq + " " + raw[qc].filter(Char::isLetter)).trim()))
                }
            }
        }
        // Pairs lost ("pair" left alone, or nothing): the whole number of pairs that makes the line add up, if there is exactly one.
        // Not when the amount may include GST (a GST column holding amounts, "₹ 341.73 (5%)"): pairs × 1.05 could look whole.
        val gc = col(Kind.GST_PCT)
        val gstAmounts = gc >= 0 && raw.getOrNull(gc)?.let { t -> Layout.bracketPct(t) != null || (number(t)?.let { it > BigDecimal(28) } == true) || t.contains('₹') } == true
        if (qc >= 0 && v.qty == null && v.rate != null && v.taxable != null && v.rate.signum() > 0 && !gstAmounts && cols.none { it.kind == Kind.TOTAL || it.kind == Kind.RATE_GST } &&
            raw[qc].filter(Char::isDigit).isEmpty()) {
            val discTries = tries.filter { it.values.discPct != v.discPct }
            val discs: List<Pair<BigDecimal, Try?>> = (listOfNotNull(v.discPct).map { it to null } + discTries.map { it.values.discPct!! to it } +
                // the % printed on the line may be taken at the bill's foot (Amount = pairs × rate)
                (if (v.discPct != null) listOf(BigDecimal.ZERO to null) else emptyList()))
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
        var grandAfterRound = false
        var subFromTax = false
        val pctRe = Regex("""@?\s*(\d{1,2}(?:\.\d{1,2})?)\s*%""")
        val qtyCol = cols.indexOfFirst { it.kind == Kind.QTY }
        var stop = false
        // A tax summary table ("Taxable Value | SGST | SGST Amt | CGST | CGST Amt …"): its headings' places, to read the numbers under them.
        var taxCols: Map<String, Double>? = null
        var taxFromTotal = false
        // Numbers right of the table (the edge of another sheet under the bill) are not the bill's.
        val tableRight = (cols.filter { it.kind != Kind.OTHER }.ifEmpty { cols }).maxOfOrNull { it.r }
        for (r in rows) {
            if (stop) break
            val t = squash(r.text)
            val ns = nums(r).filter { (w, _) -> tableRight == null || w.box.l <= tableRight + 3 * mh }
            val lowT = t.lowercase()
            if (ns.none { it.second.abs() >= BigDecimal(100) } && listOf("cgst", "sgst", "igst").count { lowT.contains(it) } >= 2) {
                val ws = r.words
                val m = mutableMapOf<String, Double>()
                for (tax in listOf("cgst", "sgst", "igst")) {
                    val i = ws.indexOfFirst { it.text.lowercase().contains(tax) }
                    if (i < 0) continue
                    // "CGST Amt" (one word or two) — else the next "Amt" to its right before another tax word.
                    val others = listOf("cgst", "sgst", "igst") - tax
                    val amt = ws.withIndex().firstOrNull { (k, w) -> k >= i && Regex("""(?i)amt|amount""").containsMatchIn(w.text) &&
                        ws.subList(i + 1, k + 1).none { x -> others.any { x.text.lowercase().contains(it) } } }?.value
                    m[tax] = (amt ?: ws[i]).box.cx
                }
                ws.firstOrNull { it.text.lowercase().startsWith("taxable") }?.let { m["taxable"] = it.box.cx }
                if (m.size >= 2) { taxCols = m; continue }
            }
            val tc = taxCols
            if (tc != null && ns.isNotEmpty()) {
                val isTotal = Regex("""(?i)^\s*total\b""").containsMatchIn(t)
                if (!taxFromTotal || isTotal) {
                    val width = (r.words.maxOfOrNull { it.box.r } ?: 1000) * 0.06
                    for ((w, v) in ns) {
                        if (w.text.contains('%')) continue
                        val near = tc.minByOrNull { (_, x) -> kotlin.math.abs(x - w.box.cx) } ?: continue
                        if (kotlin.math.abs(near.value - w.box.cx) > width) continue
                        when (near.key) { "cgst" -> ca = v; "sgst" -> sa = v; "igst" -> ia = v; "taxable" -> if (sub == null || (isTotal && subFromTax)) { sub = v; subFromTax = true } }   // the table's own total row wins over the summary (after bill discounts); in the summary its Total row wins
                    }
                    pctRe.findAll(t).map { it.groupValues[1].toBigDecimalOrNull() }.filterNotNull().toList().let { ps ->
                        if (ps.size >= 2) { if (cp == null) cp = ps[1]; if (sp == null) sp = ps[0] }
                    }
                    if (isTotal) taxFromTotal = true
                }
                continue
            }
            // The table's total row (numbers only, under the item columns): pairs, and the amount with GST when the bill prints one.
            // ("Items: 21" printed at its start, EBase).
            if (cols.isNotEmpty() && ns.size >= 3 && t.replace(Regex("""(?i)\bitems?\s*:?\s*\d+"""), "").none { it.isLetter() }) {
                val cells = assign(r, cols, bounds, mh)
                fun at(vararg k: Kind) = cols.indexOfFirst { it.kind in k }.takeIf { it >= 0 }?.let { ci -> cells[ci]?.let { number(cellText(it)) } }
                at(Kind.QTY)?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.let { qty = it }
                at(Kind.TOTAL)?.let { g -> grand = grand?.let { maxOf(it, g) } ?: g }
                if (ca == null) ca = at(Kind.CGST_AMT)
                if (sa == null) sa = at(Kind.SGST_AMT)
                if (ia == null) ia = at(Kind.IGST_AMT)
                if (cols.none { it.kind == Kind.TOTAL }) at(Kind.TAXABLE, Kind.AMOUNT)?.let { sub = it }
                continue
            }
            if (Regex("""(?i)in\s*words|rupees|only\b""").containsMatchIn(t) && !Regex("""(?i)grand|net""").containsMatchIn(t)) {
                // "Amount in words : … Only   ₹ 26012.00": the total printed at the end of the same line.
                // (the ₹ sign is often lost in a photo: then the last amount at the end of the line)
                (Regex("""₹\s*([\d,]+\.\d{2})""").find(t)?.groupValues?.get(1) ?: Regex("""([\d,]{3,}\.\d{2})\s*$""").find(t.trim())?.groupValues?.get(1))
                    ?.let { m -> number(m)?.let { g -> grand = grand?.let { maxOf(it, g) } ?: g } }
                continue
            }
            // Bank details on the left and "CGST 294.89" on the right of the same line: the tax is still read.
            if (Regex("""(?i)bank|a/c|ifs|terms|declaration|signatory|hsn\s*/?\s*sac""").containsMatchIn(t) &&
                !Regex("""(?i)\b(c\s?gst|s\s?gst|igst|total\s*amount|grand\s*total|cash\s*discount|round)""").containsMatchIn(t)) { if (grand != null) stop = true; continue }
            if (ns.isEmpty()) continue
            val lastBig = ns.lastOrNull { (w, v) -> !w.text.contains('%') && !(w.text.startsWith("@")) && v.abs() >= BigDecimal.ONE }?.second
            when {
                Regex("""(?i)\bcgst\b""").containsMatchIn(t) && ca == null -> { cp = pctRe.find(t)?.groupValues?.get(1)?.toBigDecimalOrNull(); ca = lastBig }
                Regex("""(?i)\b(sgst|utgst)\b""").containsMatchIn(t) && sa == null -> { sp = pctRe.find(t)?.groupValues?.get(1)?.toBigDecimalOrNull(); sa = lastBig }
                Regex("""(?i)\bigst\b""").containsMatchIn(t) && ia == null -> { ip = pctRe.find(t)?.groupValues?.get(1)?.toBigDecimalOrNull(); ia = lastBig }
                Regex("""(?i)round\s*(ed|ing)?\s*off|r\s*/?\s*off|rounding""").containsMatchIn(t) -> {
                    val v = ns.last().second
                    round = if (Regex("""(?i)less|\(-\)|-\s*\d""").containsMatchIn(t) && v.signum() > 0) v.negate() else v
                }
                // "Sub Total ₹ 1,31,665.97": before round off — not the bill total.
                Regex("""(?i)^\s*sub\s*-?\s*total\b""").containsMatchIn(t) && ns.size <= 2 && grand == null -> { if (ns.last().second.signum() > 0) sub = ns.last().second }
                // "Received ₹ 0.00", "Balance ₹ …", "Previous balance": payments, not the bill.
                Regex("""(?i)^\s*(received|balance|paid|previous|advance|current\s*balance|you\s*saved)\b""").containsMatchIn(t) -> {}
                Regex("""(?i)grand\s*total|net\s*(amount|payable|total|amt)|amount\s*payable|total\s*amount|invoice\s*(total|value|amount)|bill\s*amount|₹|\brs\.?\s*\d""").containsMatchIn(t) &&
                    !Regex("""(?i)taxable|^\s*(less|add)\b""").containsMatchIn(t) -> grand = ns.maxOf { it.second }.let { g ->
                        // The first total line after the round off is the bill total (it may be below a "Total" before rounding).
                        if (round != null && !grandAfterRound) { grandAfterRound = true; g } else grand?.let { maxOf(it, g) } ?: g }
                Regex("""(?i)^\s*(sub\s*-?\s*)?total(\b|(?=\d))""").containsMatchIn(t) && sub == null -> {
                    // Under the table's own columns: pairs, the taxable total, and the tax amounts of a bill with GST per line.
                    val cells = if (cols.isNotEmpty()) assign(r, cols, bounds, mh) else emptyMap()
                    fun at(vararg k: Kind) = cols.indexOfFirst { it.kind in k }.takeIf { it >= 0 }?.let { ci -> cells[ci]?.let { number(cellText(it)) } }
                    if (qtyCol >= 0) qty = at(Kind.QTY)?.takeIf { it.stripTrailingZeros().scale() <= 0 } ?: qty
                    // "Total 209 Prs ₹ 37,482.00" after the tax and round-off lines (Tally): the bill total itself.
                    if (grand == null && (round != null || ca != null || sa != null || ia != null)) { grand = at(Kind.TAXABLE, Kind.AMOUNT, Kind.TOTAL) ?: ns.maxOf { it.second }; continue }
                    sub = at(Kind.TAXABLE) ?: ns.maxOf { it.second }
                    if (ca == null) ca = at(Kind.CGST_AMT)
                    if (sa == null) sa = at(Kind.SGST_AMT)
                    if (ia == null) ia = at(Kind.IGST_AMT)
                }
                CHARGE.containsMatchIn(t.trim()) || Regex("""(?i)\b(freight|transport(ation)?|packing|forwarding|cartage|hamali|courier)\b""").containsMatchIn(t) ->
                    other = (other ?: BigDecimal.ZERO) + ns.last().second
                Regex("""(?i)(less|cash|trade|special)\s*:?\s*disc""").containsMatchIn(t) || Regex("""(?i)^\s*less\b""").containsMatchIn(t) ||
                    Regex("""(?i)^\s*(dis|disc|discount)\b.*\(\s*-\s*\)""").containsMatchIn(t) ->
                    disc = (disc ?: BigDecimal.ZERO) + ns.last().second.abs()      // every "LESS :" line (per carton, special, prompt payment…)
                Regex("""(?i)^\s*add\b""").containsMatchIn(t) && !Regex("""(?i)gst|round""").containsMatchIn(t) ->
                    other = (other ?: BigDecimal.ZERO) + ns.last().second          // "ADD : transit insurance", freight…
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
                // "Invoice No, :" — a comma or semicolon the OCR read beside the label is not the value.
                val rest = s.substring(m.range.last + 1).trimStart { it in ":.-,; " }.trim()
                val v = rest.ifEmpty { row.getOrNull(i + 1)?.trim()?.trimStart(':')?.trim() ?: "" }
                if (v.isNotEmpty()) return v
            }
            return null
        }
        val all = top.joinToString("\n") { it.text }
        // ("Original For Recipient" in the corner is the copy's name, not the buyer.)
        val consigneeAt = top.indexOfFirst { BUYER.containsMatchIn(it.text.replace(Regex("""(?i)\bfor\s+(recipient|transporter|supplier|buyer)\b|\bbuyer'?s\s*order(\s*no\.?)?"""), "")) }
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
        val beforeBuyer = top.take(listOf(firstGstinRow + 1, consigneeAt.takeIf { it >= 0 } ?: top.size).minOrNull() ?: top.size)
        val biz = Regex("""(?i)\b(enterprises?|traders?|trading|agenc(y|ies)|sons|corporation|distributors?|pvt|ltd|limited|company|industries|footwears?|shoes?|& ?co|exports?|impex|marketing|sales)\b""")
        val bizName = beforeBuyer.flatMap { r -> r.segments(gap) }.firstOrNull { sg -> val t = sg.joinToString(" ") { it.text }
            sg.first().box.l < midX && biz.containsMatchIn(t) && !skip.containsMatchIn(t) && !BUYER.containsMatchIn(t) && !Regex("""(?i)\b(road|nagar|complex|chowk|galli|lane|marg|near|opp\.?|behind|shop\s*no|plot)\b""").containsMatchIn(t) }
            ?.joinToString(" ") { it.text }?.replace(Regex("""\(?\s*\b20\d{2}\s*-\s*\d{2,4}\s*\)?"""), "")?.trim()
        val supplierName = tallest?.takeIf { it.second >= 1.25 * mh && (bizName == null || biz.containsMatchIn(it.first)) }?.first
            ?: bizName
            // "Yash Enterprises", "S H PATEL & SONS ( 2026-27 )": a firm's name at the top, not printed larger (Tally).
            ?: beforeBuyer.flatMap { r -> r.segments(gap) }.firstOrNull { sg -> sg.first().box.l < midX &&
                Regex("""(?i)\b(enterprises?|traders?|trading|agenc(y|ies)|sons|corporation|distributors?|pvt|ltd|limited|company|industries|footwears?|shoes?|& ?co|exports?|impex|marketing|sales)\b""").containsMatchIn(sg.joinToString(" ") { it.text }) &&
                !skip.containsMatchIn(sg.joinToString(" ") { it.text }) && !BUYER.containsMatchIn(sg.joinToString(" ") { it.text }) }
                ?.joinToString(" ") { it.text }?.replace(Regex("""\(\s*\d{4}\s*-\s*\d{2,4}\s*\)"""), "")?.trim()
            ?: top.take(firstGstinRow).flatMap { r -> r.segments(gap).map { s -> s.joinToString(" ") { it.text } } }
                .firstOrNull { s -> s.length >= 5 && s.none { it.isLowerCase() } && s.count { it.isLetter() } >= 5 && !skip.containsMatchIn(s) }
        val nameRow = top.indexOfFirst { r -> supplierName != null && r.text.contains(supplierName) }
        // (Only the buyer's GSTIN may be on the page: then there is no supplier GSTIN row.)
        val gRow = top.getOrNull(firstGstinRow)
        val addrFrom = if (gRow != null && gstins.isNotEmpty() && firstGstinRow > nameRow && !Regex("""(?i)^\s*gstin""").containsMatchIn(gRow.text) &&
            gRow.text.substringBefore("GSTIN").substringBefore("Gstin").count { it.isLetter() } >= 4) firstGstinRow - 1 else firstGstinRow
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
            val segsW = top[consigneeAt].segments(gap)
            val sameLine = BUYER.find(seg)?.let { seg.substring(it.range.last + 1) }.orEmpty().trim().trimStart(':', '.', '-').trim()
                // Only what stands in the buyer's half of the line ("Buyer … Despatch Document No" is the box beside it).
                .ifEmpty { segsW.drop(at + 1).filter { it.first().box.l < midX }.map { sg -> sg.joinToString(" ") { it.text } }
                    .firstOrNull { it.trim().trimStart(':').trim().count(Char::isLetter) >= 3 && !LABELS.containsMatchIn(it) }.orEmpty() }
            val rest = sameLine
                .replace(Regex("""(?i)^[\s&:.\-]*((delivery|billing|shipping)\s*)?((address|name|details)\b)?[\s.:\-]*"""), "")
                .replace(Regex("""\.{2,}"""), " ").replace(Regex("""\s+"""), " ").trim()
                // "Details of Receiver (Billed to )": a second label in brackets, not a name.
                .replace(Regex("""\(([^)]*)\)""")) { m -> if (BUYER.containsMatchIn(m.groupValues[1]) || m.groupValues[1].isBlank()) "" else m.value }.trim()
            if (rest.count { it.isLetter() } >= 3 && gstin(rest) == null) rest.trimStart(':', ' ')
            // The name on the next line in the buyer's half (a line of the box beside it may come between); "Name : …" without its label.
            else top.drop(consigneeAt + 1).take(2).firstNotNullOfOrNull { r ->
                val segs = r.segments(gap).filter { it.first().box.l < midX }.map { sg -> sg.joinToString(" ") { it.text } }
                val joined = segs.take(2).joinToString(" ")
                Regex("""(?i)^\s*(name|m/s\.?|to)\s*[:.\-]?\s*(.+)$""").find(joined)?.groupValues?.get(2)?.trimStart(':', ' ')?.takeIf { it.count(Char::isLetter) >= 3 }
                    ?: segs.firstOrNull()?.takeIf { !LABELS.containsMatchIn(it) && it.count(Char::isLetter) >= 3 } }
        } else null
        val contact = Regex("""(?i)(contact|mobile|mob|phone|ph|tel|cell)\.?\s*(no)?\.?\s*[:.]?\s*(\+?91[\s-]?)?(\d[\d ]{7,12}\d)""")
            .find(top.take(if (consigneeAt >= 0) consigneeAt else top.size).joinToString("\n") { it.text })?.groupValues?.get(4)?.replace(" ", "")
        // "INVOICE NO." printed as a heading with the number under it (a box of the bill head).
        fun under(label: Regex): String? {
            for ((i, r) in top.withIndex()) {
                val ws = r.words
                for (k in ws.indices) {
                    val two = ws[k].text + (ws.getOrNull(k + 1)?.text?.let { " $it" } ?: "")
                    if (!label.containsMatchIn(two) || !Regex("""(?i)^in""").containsMatchIn(ws[k].text)) continue
                    val l = ws[k].box.l; val rr = (ws.getOrNull(k + 1) ?: ws[k]).box.r
                    for (below in top.drop(i + 1).take(2)) {
                        val v = below.words.filter { w -> w.box.cx >= l - 2 * mh && w.box.cx <= rr + 2 * mh }
                        val t = v.joinToString("") { it.text }
                        if (t.any(Char::isDigit) && DATE.find(t)?.value != t) return t
                    }
                }
            }
            return null
        }
        // "G2/2627/ 1309": a number the OCR split after "/" or "-" is joined again.
        fun firstNo(t: String): String? {
            val ws = t.split(' ').filter { it.isNotEmpty() }
            val k = ws.indexOfFirst { w -> w.any(Char::isDigit) }.takeIf { it >= 0 } ?: return null
            var no = ws[k]
            for (n in ws.drop(k + 1)) { if ((no.endsWith('/') || no.endsWith('-')) && n.first().isLetterOrDigit() && DATE.find(n) == null) no += n else break }
            return no
        }
        val invoiceNo = ((after(Regex("""(?i)\bin[vy][o0][il1|]?c[e3]\s*(no|number|#)\.?\s*:?""")) ?: after(Regex("""(?i)\b(inv|bill|memo|voucher)\.?\s*(no|number|#)\.?\s*:?""")))
            ?.let { firstNo(it) }
            ?: under(Regex("""(?i)\bin[vy][o0][il1|]?c[e3]\s*(no|number|#)"""))
            // "Invoice  - 0754": the label without "No".
            ?: after(Regex("""(?i)^\s*in[vy][o0][il1|]?c[e3]\s*[-:.]?\s*$|^\s*in[vy][o0][il1|]?c[e3]\s*[-:.]\s*(?=\S*\d)"""))?.trimStart('-', ':', '.', ' ')?.split(' ')?.firstOrNull { t -> t.any(Char::isDigit) && DATE.find(t) == null }
            // An order bill / estimate / challan: its own number.
            ?: after(Regex("""(?i)\b(estimate|est|order|challan|dc|quotation|qtn|ref)\.?\s*(no|number|#)\.?\s*:?"""))?.split(' ')?.firstOrNull { t -> t.any(Char::isDigit) }
            // A bare "No. : 2761" (hand-made bills), not a phone, shop or account number.
            ?: segs.flatten().firstNotNullOfOrNull { seg ->
                Regex("""(?i)(?:^|(\S+)\s+)no\.?\s*:\s*(\S*\d\S*)""").find(seg)?.takeIf { m ->
                    val before = m.groupValues[1].lowercase().trim('.', ':')
                    before !in setOf("mob", "mobile", "ph", "phone", "tel", "contact", "a/c", "ac", "account", "vehicle", "lr", "gst", "pan", "cell", "whatsapp", "shop", "plot", "gala", "room", "flat", "house", "survey", "s", "sr", "sl") &&
                        m.groupValues[2].filter(Char::isDigit).length < 10
                }?.groupValues?.get(2)
            })?.trim(',', ';')
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
    /** This supplier's item columns left to right (from a pasted bill pattern), or null. */
    fun orderFor(header: Header): List<InvoiceParser.Kind>? = null
}

/** A simple [LayoutMemory]: supplier key (GSTIN, else name) → heading → meaning. */
class TaughtLayouts(val map: Map<String, Map<String, InvoiceParser.Kind>>) : LayoutMemory {
    override fun kindFor(header: Header, label: String): InvoiceParser.Kind? = key(header)?.let { map[it]?.get(norm(label)) }

    companion object {
        fun key(h: Header): String? = h.supplierGstin ?: h.supplierName?.uppercase()?.replace(Regex("""[^A-Z0-9]"""), "")?.ifEmpty { null }
        fun norm(label: String) = label.lowercase().replace(Regex("""[^a-z0-9%]"""), "")
    }
}
