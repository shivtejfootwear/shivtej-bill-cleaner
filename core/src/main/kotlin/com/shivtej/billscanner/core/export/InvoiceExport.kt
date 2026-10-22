package com.shivtej.billscanner.core.export

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Column
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Groups
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.Target
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.LineItem
import com.shivtej.billscanner.core.model.Sizes
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warnings
import java.io.OutputStream
import java.math.BigDecimal

/** Text used when the supplier's printed discount could not be read. */
const val NEEDS_VERIFICATION = "Needs Verification"
const val NOT_FOUND = "Not found"

object InvoiceExport {
    /** Item Details columns, in order. The bill discount column sits right after the rate. */
    val ITEM_COLUMNS = listOf(
        "Sr No.", "Article No.", "Article Name", "Brand", "Colour", "Size Assortment", "HSN", "Quantity",
        "MRP", "Original Rate", "Original Bill Discount (%)", "Original Discount Amount (₹)",
        "Calculated Discount Against MRP (%)", "Net Rate After Bill Discount", "Effective Discount Against MRP (%)",
        "Taxable Value", "CGST (%)", "CGST (₹)", "SGST (%)", "SGST (₹)", "IGST (%)", "IGST (₹)", "Other Charges",
        "Final Line Total", "Target Discount (%) — estimate", "Target Rate — estimate", "Target Rate − Original Rate",
        "Scanned Rate (as read)", "Scanned Bill Discount (%) (as read)", "Edited Fields", "Verification Status", "Notes",
    )

    fun fileBase(inv: Invoice): String {
        val sup = (inv.header.supplierName ?: "Supplier").replace(Regex("""[^A-Za-z0-9]+"""), "_").trim('_').take(24)
        val no = (inv.header.invoiceNo ?: inv.id.take(6)).replace(Regex("""[^A-Za-z0-9]+"""), "-").trim('-')
        return "${sup}_$no"
    }

    fun status(i: LineItem): String = when {
        i.verified -> "Verified"
        i.needs.isNotEmpty() || Calc.checkLine(i.current).isNotEmpty() -> NEEDS_VERIFICATION
        else -> "Not verified yet"
    }

    /** One sheet only: the bill as printed, then बैठा %, अपना %, नई रकम and the totals (company-wise % below). */
    fun xlsx(inv: Invoice, out: OutputStream) = Xlsx.write(listOf(printed(inv)), out)

    /** The older detailed workbook (summary, items, stock) — kept for the stock file and checks. */
    fun xlsxDetailed(inv: Invoice, out: OutputStream) =
        Xlsx.write(listOf(printed(inv), companies(inv), newBill(inv), summary(inv), items(inv), stock(inv)), out)

    // ------------------------------------------------------------ sheet 1: the bill as printed + बैठा % + target

    /** Columns of the printed copy: the bill's own, or a standard set for lines typed in by hand. */
    fun printedColumns(inv: Invoice): List<Column> = inv.columns.ifEmpty {
        listOf(Column("Sr", F.SR), Column("Article", F.ARTICLE), Column("Colour", F.COLOUR), Column("Size", F.SIZE), Column("HSN", F.HSN),
            Column("Qty", F.QTY), Column("MRP", F.MRP), Column("Rate", F.RATE), Column("Disc %", F.DISC_PCT), Column("Amount", F.TAXABLE))
    }

    private fun valueOf(v: ItemValues, f: F): String = when (f) {
        F.SR -> v.sr?.toString().orEmpty(); F.ARTICLE -> listOf(v.article, v.name, v.colour).filter { it.isNotBlank() }.joinToString(" ")
        F.NAME -> v.name; F.COLOUR -> v.colour; F.SIZE -> v.sizeText; F.HSN -> v.hsn
        F.QTY -> v.qty?.stripTrailingZeros()?.toPlainString().orEmpty(); F.MRP -> Calc.fmt(v.mrp); F.RATE -> Calc.fmt(v.rate)
        F.GROSS -> Calc.fmt(v.gross); F.DISC_PCT -> v.discPct?.let { Calc.pct(it) } ?: NEEDS_VERIFICATION; F.DISC_AMT -> Calc.fmt(v.discAmt)
        F.TAXABLE -> Calc.fmt(v.taxable); F.CGST_PCT -> Calc.pct(v.cgstPct); F.CGST_AMT -> Calc.fmt(v.cgstAmt); F.SGST_PCT -> Calc.pct(v.sgstPct)
        F.SGST_AMT -> Calc.fmt(v.sgstAmt); F.IGST_PCT -> Calc.pct(v.igstPct); F.IGST_AMT -> Calc.fmt(v.igstAmt); F.OTHER -> Calc.fmt(v.other)
        F.GST_PCT -> Calc.pct(v.gstPct); F.TOTAL -> Calc.fmt(v.printedTotal)
    }

    /** Whether the person changed the value shown in this printed column. */
    fun isEdited(i: LineItem, c: Column): Boolean {
        val f = c.field ?: return false
        return f in i.edited || (f == F.ARTICLE && (F.NAME in i.edited || F.COLOUR in i.edited))
    }

    /** Text of one printed cell: exactly as printed, unless the person corrected that value. */
    fun printedText(inv: Invoice, i: LineItem, ci: Int, c: Column): String {
        val f = c.field
        // The printed words belong to the bill's own columns; with the standard set (lines typed in) they do not apply.
        val raw = inv.columns.isNotEmpty()
        return if (raw && !isEdited(i, c) && ci < i.raw.size) i.raw[ci] else if (f != null) valueOf(i.current, f) else if (raw) i.raw.getOrNull(ci).orEmpty() else ""
    }

    /** One cell of the printed copy: exactly as printed, unless the person corrected that value. */
    private fun printedCell(inv: Invoice, i: LineItem, ci: Int, c: Column): Cell {
        val f = c.field
        val edited = isEdited(i, c)
        val text = printedText(inv, i, ci, c)
        if (f == F.DISC_PCT && i.current.discPct == null) return Cell.Text(text.ifEmpty { NEEDS_VERIFICATION }, St.WARN)
        val plain = text.trim()
        val num = if (Regex("""^-?[\d,]+(\.\d+)?$""").matches(plain)) plain.replace(",", "").toBigDecimalOrNull() else null
        val st = if (f == F.DISC_PCT) St.BILL_PCT else St.TEXT
        return when {
            num != null && f != F.SR && f != F.HSN && f != F.ARTICLE -> Cell.Num(num, if (num.scale() > 0) St.MONEY else St.NUM)
            f == F.DISC_PCT && i.current.discPct != null && !edited -> Cell.Text(text, St.BILL_PCT)
            else -> Cell.Text(text, st)
        }
    }

    const val SITTING_HEADER = "बैठा % (उन्होंने MRP पर असल कितना दिया)"
    const val TARGET_HEADER = "अपना % (बदल सकते हैं)"
    const val NEW_AMOUNT_HEADER = "नई रकम (अपने % से, GST से पहले)"

    /**
     * Sheet 1 — the bill exactly as printed, then only three columns of ours:
     * green बैठा % (what they really gave on MRP), yellow अपना % (change it in Excel), and the new amount at that %.
     * Below: the bill's totals as printed beside the new totals.
     */
    private fun printed(inv: Invoice): Sheet {
        val s = Sheet("Bill + Baitha %")
        val h = inv.header
        val cols = printedColumns(inv)
        val n = cols.size
        s.row(Cell.Text("${h.supplierName ?: "Supplier"} — Invoice ${h.invoiceNo ?: NOT_FOUND} — ${h.invoiceDate ?: ""}", St.TITLE))
        s.row(Cell.Text("Buyer: ${h.buyerName ?: NOT_FOUND}   ·   Supplier GSTIN: ${h.supplierGstin ?: NOT_FOUND}   ·   Buyer GSTIN: ${h.buyerGstin ?: NOT_FOUND}", St.NORMAL))
        s.row(Cell.Text(
            "Columns up to \"${cols.last().label}\" = supplier's bill as printed. Green बैठा % = (MRP − cost per pair" +
                (if (inv.sittingWithGst) " incl. GST" else " before GST") + ") ÷ MRP. Yellow अपना % can be changed here; the new amount and totals follow it. " +
                "New amounts are our estimate, not the supplier's bill.", St.NORMAL))
        s.row(Cell.Empty)
        val headRow = 5
        s.freezeRows = headRow; s.filterRow = headRow
        s.widths = cols.map { c -> when (c.field) { F.ARTICLE -> 30.0; F.SR -> 6.0; F.HSN -> 11.0; F.SIZE -> 14.0; F.COLOUR -> 14.0; else -> 11.0 } } + listOf(14.0, 13.0, 16.0)
        s.row(cols.map { Cell.Text(it.label, if (it.field == F.DISC_PCT) St.HEADER_ORANGE else St.HEADER) } +
            listOf(Cell.Text(SITTING_HEADER, St.HEADER_GREEN), Cell.Text(TARGET_HEADER, St.HEADER_ORANGE), Cell.Text(NEW_AMOUNT_HEADER, St.HEADER)))
        val tc = Xlsx.col(n + 1); val ac = Xlsx.col(n + 2)
        val first = headRow + 1
        val byRate = sortedMapOf<BigDecimal, MutableList<String>>()      // GST % → cells of new amounts at that rate
        var fixedTax = BigDecimal.ZERO
        inv.items.forEachIndexed { k, it ->
            val r = first + k
            val v = it.current
            val g = Target.gstRate(inv, it).stripTrailingZeros()
            val tl = Target.line(inv, it)
            val bill = v.taxable
            val sit = Calc.sittingPct(v, h, inv.sittingWithGst)
            val own = Target.ownTax(it)
            val canTarget = Target.targetable(inv, it)
            if (canTarget || bill != null) { if (canTarget || own == null) byRate.getOrPut(g) { mutableListOf() } += "$ac$r" else fixedTax += own }
            val sitCell = when {
                it.kind == ItemKind.CHARGE -> Cell.Text("")
                sit != null -> Cell.Num(sit, St.GREEN_PCT)
                else -> Cell.Text(NEEDS_VERIFICATION, St.WARN)
            }
            val targetCell = when {
                canTarget -> Target.pct(inv, it)?.let { x -> Cell.Num(x, St.EDIT_PCT) } ?: Cell.Text("", St.EDIT_PCT)
                it.kind == ItemKind.CHARGE -> Cell.Text("")
                else -> Cell.Text("MRP जाँचें", St.WARN)
            }
            val billNum = bill?.toPlainString() ?: "0"
            val amountCell: Cell = if (canTarget) {
                val mrp = v.mrp!!.toPlainString(); val q = v.qty!!.toPlainString()
                val rate = if (inv.sittingWithGst) "ROUND($mrp*(100-$tc$r)/(100+$g),2)" else "ROUND($mrp*(100-$tc$r)/100,2)"
                Cell.Formula("IF($tc$r=\"\",$billNum,ROUND($rate*$q,2))", tl?.amount ?: bill ?: BigDecimal.ZERO, St.MONEY)
            } else bill?.let { x -> Cell.Num(x, St.MONEY) } ?: Cell.Text("")
            s.row(cols.mapIndexed { ci, c -> printedCell(inv, it, ci, c) } + listOf(sitCell, targetCell, amountCell))
        }
        val last = first + inv.items.size - 1
        val t = Totals.of(inv)
        val sheet = Target.sheet(inv)
        val width = n + 3
        val labelAt = cols.indexOfFirst { it.field == F.ARTICLE }.takeIf { it >= 0 } ?: 0
        val amountAt = cols.indexOfLast { it.field == F.TAXABLE || it.field == F.TOTAL }
        val qtyAt = cols.indexOfFirst { it.field == F.QTY }
        fun line(label: String, vararg put: Pair<Int, Cell>) {
            val cells = MutableList<Cell>(width) { Cell.Empty }
            cells[labelAt] = Cell.Text(label, St.BOLD_TEXT)
            for ((k, c) in put) if (k in 0 until width) cells[k] = c
            s.row(cells)
        }
        val sumRow = last + 1
        val billCol = if (amountAt >= 0) amountAt else -1
        line("Total",
            billCol to Cell.Num(t.taxable, St.BOLD_MONEY),
            qtyAt to (if (qtyAt >= 0) Cell.Text("${t.qty.toPlainString()} pairs", St.BOLD_TEXT) else Cell.Empty),
            n to (Calc.sittingTotal(inv)?.let { Cell.Num(it, St.GREEN_PCT) } ?: Cell.Empty),
            n + 2 to Cell.Formula("SUM($ac$first:$ac$last)", sheet.taxable, St.BOLD_MONEY))
        val gstBill = t.cgst + t.sgst + t.igst
        val gstFormula = (byRate.entries.map { (g, cells) -> "ROUND((${cells.joinToString("+")})*$g/100,2)" } +
            (if (fixedTax.signum() != 0) listOf(fixedTax.toPlainString()) else emptyList())).ifEmpty { listOf("0") }.joinToString("+")
        line("GST (CGST ${Calc.fmt(t.cgst)} + SGST ${Calc.fmt(t.sgst)}${if (t.igst.signum() != 0) " + IGST ${Calc.fmt(t.igst)}" else ""})",
            billCol to Cell.Num(gstBill, St.BOLD_MONEY),
            n + 2 to Cell.Formula(gstFormula, sheet.gst, St.BOLD_MONEY))
        val extra = (h.otherCharges ?: BigDecimal.ZERO) - (h.overallDiscount ?: BigDecimal.ZERO)
        h.roundOff?.let { ro -> line("Round off", billCol to Cell.Num(ro, St.BOLD_MONEY)) }
        line("Grand Total" + if (t.printedGrand != null) " (printed on bill)" else " (calculated)",
            billCol to Cell.Num(t.printedGrand ?: t.grand, St.BOLD_MONEY),
            n + 1 to Cell.Text("नया Total →", St.BOLD_TEXT),
            n + 2 to Cell.Formula("ROUND($ac$sumRow+$ac${sumRow + 1}" + (if (extra.signum() != 0) "+${extra.toPlainString()}" else "") + ",0)", sheet.rounded, St.BOLD_MONEY))
        t.difference?.let { d -> if (d.signum() != 0) line("Printed total − calculated = ₹${Calc.fmt(d)} — check the bill", billCol to Cell.Num(d, St.WARN)) }
        // Company-wise: what each brand really gave and the % set for it.
        val groups = Groups.of(inv)
        if (groups.size > 1) {
            s.row(Cell.Empty)
            line("कंपनी-वार", n to Cell.Text(SITTING_HEADER, St.HEADER_GREEN), n + 1 to Cell.Text(TARGET_HEADER, St.HEADER_ORANGE), n + 2 to Cell.Text("नई रकम", St.HEADER))
            for (g in groups) {
                val lines = sheet.lines.filter { l -> g.items.any { it.id == l.item.id } }
                line("${g.key} (${g.items.size} lines · ${g.pairs.stripTrailingZeros().toPlainString()} pairs)",
                    billCol to Cell.Num(g.billValue, St.BOLD_MONEY),
                    n to (g.sittingPct?.let { Cell.Num(it, St.GREEN_PCT) } ?: Cell.Text(NEEDS_VERIFICATION, St.WARN)),
                    n + 1 to ((g.target ?: inv.defaultTargetPct)?.let { Cell.Num(it, St.EDIT_PCT) } ?: Cell.Text("")),
                    n + 2 to (if (lines.isNotEmpty()) Cell.Num(Calc.r2(lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount }), St.BOLD_MONEY) else Cell.Text("")))
            }
        }
        return s
    }

    // ------------------------------------------------------------ sheet 2: company-wise

    private fun companies(inv: Invoice): Sheet {
        val s = Sheet("Company-wise %")
        s.freezeRows = 1
        s.widths = listOf(16.0, 8.0, 9.0, 16.0, 16.0, 12.0, 16.0, 14.0)
        s.row(listOf("Company / Brand", "Lines", "Pairs", "Bill Amount (before GST)", "बैठा % (average)", "Target %", "New Amount (before GST)", "Difference")
            .mapIndexed { k, t -> Cell.Text(t, if (k == 4) St.HEADER_GREEN else if (k == 5) St.HEADER_ORANGE else St.HEADER) })
        val sheet = Target.sheet(inv)
        for (gr in Groups.of(inv)) {
            val lines = sheet.lines.filter { l -> gr.items.any { it.id == l.item.id } }
            val newAmt = lines.fold(BigDecimal.ZERO) { a, l -> a + l.amount }
            s.row(Cell.Text(gr.key, St.BOLD_TEXT), Cell.Num(BigDecimal(gr.items.size), St.INT), Cell.Num(gr.pairs, St.INT), Cell.Num(gr.billValue),
                gr.sittingPct?.let { Cell.Num(it, St.GREEN_PCT) } ?: Cell.Text(NEEDS_VERIFICATION, St.WARN),
                (gr.target ?: inv.defaultTargetPct)?.let { Cell.Num(it, St.EDIT_PCT) } ?: Cell.Text(""),
                if (lines.isNotEmpty()) Cell.Num(Calc.r2(newAmt)) else Cell.Text(""),
                if (lines.isNotEmpty()) Cell.Num(Calc.r2(newAmt - lines.fold(BigDecimal.ZERO) { a, l -> a + (l.billAmount ?: BigDecimal.ZERO) })) else Cell.Text(""))
        }
        return s
    }

    // ------------------------------------------------------------ sheet 3: new bill at target %

    private fun newBill(inv: Invoice): Sheet {
        val s = Sheet("New Bill at Target %")
        val h = inv.header
        val sheet = Target.sheet(inv)
        s.widths = listOf(6.0, 32.0, 10.0, 10.0, 8.0, 10.0, 14.0, 14.0, 14.0)
        s.row(Cell.Text("Rate calculation at target % — ${h.supplierName ?: "Supplier"} invoice ${h.invoiceNo ?: ""} (${h.invoiceDate ?: ""})", St.TITLE))
        s.row(Cell.Text("Prepared by the buyer for discussion. This is an estimate, not the supplier's tax invoice.", St.NORMAL))
        s.row(Cell.Empty)
        s.freezeRows = 4
        s.row(listOf("Sr", "Article", "Size", "MRP", "Pairs", "Target %", "New Rate / Pair (before GST)", "Amount (before GST)", "Cost / Pair with GST")
            .mapIndexed { k, t -> Cell.Text(t, if (k == 5) St.HEADER_ORANGE else St.HEADER) })
        if (!sheet.any) { s.row(Cell.Empty, Cell.Text("No target % set yet — set one in the app (or in the yellow column of sheet 1).", St.WARN)); return s }
        for (l in sheet.lines) {
            val v = l.item.current
            s.row(v.sr?.let { Cell.Num(BigDecimal(it), St.INT) } ?: Cell.Text(""), Cell.Text(listOf(v.article, v.name, v.colour).filter { it.isNotBlank() }.joinToString(" ")),
                Cell.Text(v.sizeText), Cell.Num(v.mrp!!), Cell.Num(v.qty!!, St.INT), Cell.Num(l.pct, St.EDIT_PCT), Cell.Num(l.net), Cell.Num(l.amount), Cell.Num(l.landed))
        }
        for (i in sheet.untouched) {
            val v = i.current
            s.row(v.sr?.let { Cell.Num(BigDecimal(it), St.INT) } ?: Cell.Text(""), Cell.Text(listOf(v.article, v.name, v.colour).filter { it.isNotBlank() }.joinToString(" ") + "  (as billed)"),
                Cell.Text(v.sizeText), v.mrp?.let { Cell.Num(it) } ?: Cell.Text(""), v.qty?.let { Cell.Num(it, St.INT) } ?: Cell.Text(""), Cell.Text("—"),
                v.rate?.let { Cell.Num(it) } ?: Cell.Text(""), v.taxable?.let { Cell.Num(it) } ?: Cell.Text(NOT_FOUND, St.WARN), Cell.Text(""))
        }
        s.row(Cell.Empty)
        fun tot(k: String, v: BigDecimal, st: St = St.BOLD_MONEY) = s.row(Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Text(k, St.BOLD_TEXT), Cell.Num(v, st))
        tot("Amount before GST", sheet.taxable); tot("GST", sheet.gst); tot("Total (rounded)", sheet.rounded)
        tot("Supplier's bill total", sheet.billTotal); tot("Difference", sheet.difference, St.WARN)
        return s
    }

    private fun t(s: String?, st: St = St.TEXT) = Cell.Text(s?.takeIf { it.isNotBlank() } ?: NOT_FOUND, st)
    private fun n(v: BigDecimal?, st: St = St.MONEY): Cell = if (v == null) Cell.Text(NOT_FOUND, St.WARN) else Cell.Num(v, st)

    private fun summary(inv: Invoice): Sheet {
        val s = Sheet("Invoice Summary")
        val h = inv.header; val tt = Totals.of(inv)
        s.widths = listOf(38.0, 60.0)
        s.row(Cell.Text("Shivtej Bill Scanner AI — Invoice Summary", St.TITLE))
        s.row(Cell.Text("Verified data report generated from the scanned supplier invoice. Not an official copy of the supplier's invoice.", St.TEXT))
        s.row(Cell.Empty)
        fun kv(k: String, v: Cell) = s.row(Cell.Text(k, St.LABEL), v)
        kv("Supplier", t(h.supplierName)); kv("Supplier Address", t(h.supplierAddress)); kv("Supplier GSTIN", t(h.supplierGstin))
        kv("Supplier Contact", t(h.supplierContact)); kv("Invoice Number", t(h.invoiceNo)); kv("Invoice Date", t(h.invoiceDate))
        kv("Order Number", t(h.orderNo)); kv("Transporter", t(h.transporter)); kv("Cartons / Bundles", t(h.cartons))
        kv("Packing", t(h.packing)); kv("Buyer", t(h.buyerName)); kv("Buyer GSTIN", t(h.buyerGstin)); kv("Page", t(h.pageNote))
        s.row(Cell.Empty)
        kv("Line items", Cell.Num(BigDecimal(tt.lines), St.INT)); kv("Article lines", Cell.Num(BigDecimal(tt.articleLines), St.INT))
        kv("Total quantity (pairs, articles only)", Cell.Num(tt.qty, St.INT))
        kv("Total value before discount", n(tt.gross)); kv("Total invoice discount", n(tt.discount)); kv("Taxable subtotal", n(tt.taxable))
        kv("CGST total", n(tt.cgst)); kv("SGST total", n(tt.sgst)); kv("IGST total", n(tt.igst)); kv("Other charges", n(tt.other))
        kv("Recalculated grand total", n(tt.grand)); kv("Printed grand total", n(tt.printedGrand)); kv("Reconciliation difference", n(tt.difference))
        kv("Status", Cell.Text(if (inv.status == InvoiceStatus.VERIFIED) "Verified" else "Not verified yet"))
        kv("Target discount (estimate)", if (inv.defaultTargetPct != null) Cell.Num(inv.defaultTargetPct, St.PCT) else Cell.Text("—"))
        s.row(Cell.Empty)
        s.row(Cell.Text("Notes", St.LABEL))
        Warnings.of(inv).forEach { w -> s.row(Cell.Text(w.level.name, St.LABEL), Cell.Text(w.english, St.WARN)) }
        val unresolved = inv.items.filter { it.kind == ItemKind.ARTICLE }.filter { i -> Sizes.read(i.current.sizeText, i.current.qty).state !in setOf(Sizes.State.EXACT, Sizes.State.FROM_SETS) }
        if (unresolved.isNotEmpty()) s.row(Cell.Text("Size-wise stock not created for", St.LABEL), Cell.Text(unresolved.joinToString(", ") { it.current.article }, St.WARN))
        s.row(Cell.Text("Purchase Rate in stock sheet", St.LABEL), Cell.Text("Rate after the supplier's bill discount, before GST"))
        return s
    }

    private fun items(inv: Invoice): Sheet {
        val s = Sheet("Item Details")
        s.freezeRows = 1; s.filterRow = 1
        s.widths = listOf(6.0, 11.0, 26.0, 11.0, 12.0, 26.0, 11.0, 9.0, 9.0, 11.0, 14.0, 14.0, 15.0, 13.0, 15.0, 12.0, 8.0, 10.0, 8.0, 10.0, 8.0, 10.0, 10.0, 13.0, 14.0, 13.0, 13.0, 12.0, 15.0, 18.0, 16.0, 24.0)
        s.row(ITEM_COLUMNS.mapIndexed { i, h -> Cell.Text(h, if (i == 10) St.HEADER_ORANGE else St.HEADER) })
        val c = ITEM_COLUMNS.indices.associateBy { ITEM_COLUMNS[it] }.mapValues { Xlsx.col(it.value) }
        inv.items.forEachIndexed { k, it ->
            val r = k + 2
            val v = it.current
            val mrp = c.getValue("MRP") + r; val rate = c.getValue("Original Rate") + r; val dp = c.getValue("Original Bill Discount (%)") + r
            val net = c.getValue("Net Rate After Bill Discount") + r; val tx = c.getValue("Taxable Value") + r
            val tgt = c.getValue("Target Discount (%) — estimate") + r; val trate = c.getValue("Target Rate — estimate") + r
            val target = it.targetPct ?: inv.defaultTargetPct
            val trateV = Calc.targetRate(v.mrp, target)
            s.row(
                v.sr?.let { Cell.Num(BigDecimal(it), St.INT) } ?: Cell.Text(""),
                t(v.article), t(v.name), Cell.Text(v.brand), Cell.Text(v.colour.ifEmpty { NOT_FOUND }), Cell.Text(v.sizeText.ifEmpty { NOT_FOUND }),
                Cell.Text(v.hsn.ifEmpty { NOT_FOUND }), n(v.qty, St.INT), n(v.mrp), n(v.rate),
                if (v.discPct == null) Cell.Text(NEEDS_VERIFICATION, St.WARN) else Cell.Num(v.discPct, St.BILL_PCT),
                n(v.discAmt),
                Cell.Formula("IFERROR(ROUND(($mrp-$rate)/$mrp*100,2),\"\")", Calc.mrpDiscPct(v), St.PCT),
                Cell.Formula("IFERROR(ROUND($rate*(100-$dp)/100,2),\"\")", Calc.netRate(v)),
                Cell.Formula("IFERROR(ROUND(($mrp-$net)/$mrp*100,2),\"\")", Calc.effectiveMrpPct(v), St.PCT),
                n(v.taxable),
                v.cgstPct?.let { Cell.Num(it, St.PCT) } ?: Cell.Text(""), v.cgstAmt?.let { Cell.Num(it) } ?: Cell.Text(""),
                v.sgstPct?.let { Cell.Num(it, St.PCT) } ?: Cell.Text(""), v.sgstAmt?.let { Cell.Num(it) } ?: Cell.Text(""),
                v.igstPct?.let { Cell.Num(it, St.PCT) } ?: Cell.Text(""), v.igstAmt?.let { Cell.Num(it) } ?: Cell.Text(""),
                v.other?.let { Cell.Num(it) } ?: Cell.Text(""),
                Cell.Formula("ROUND(N($tx)+N(${c.getValue("CGST (₹)")}$r)+N(${c.getValue("SGST (₹)")}$r)+N(${c.getValue("IGST (₹)")}$r)+N(${c.getValue("Other Charges")}$r),2)", Calc.lineTotal(v)),
                target?.let { Cell.Num(it, St.PCT) } ?: Cell.Text(""),
                if (target != null) Cell.Formula("IFERROR(ROUND($mrp*(100-$tgt)/100,2),\"\")", trateV) else Cell.Text(""),
                if (target != null) Cell.Formula("IFERROR($trate-$rate,\"\")", if (trateV != null && v.rate != null) trateV - v.rate else null) else Cell.Text(""),
                n(it.original.rate), if (it.original.discPct == null) Cell.Text(NEEDS_VERIFICATION, St.WARN) else Cell.Num(it.original.discPct, St.PCT),
                Cell.Text(it.edited.joinToString(", ") { f -> f.name }), Cell.Text(status(it), if (status(it) == NEEDS_VERIFICATION) St.WARN else St.TEXT),
                Cell.Text((if (it.kind == ItemKind.CHARGE) "Charge line (not an article). " else "") + v.notes),
            )
        }
        return s
    }

    data class StockRow(val article: String, val colour: String, val size: String, val qty: Int, val mrp: BigDecimal?, val rate: BigDecimal?)

    /** Size-wise stock rows, only for articles whose sizes add up exactly. */
    fun stockRows(inv: Invoice): List<StockRow> = inv.items.filter { it.kind == ItemKind.ARTICLE }.flatMap { i ->
        val v = i.current
        val r = Sizes.read(v.sizeText, v.qty)
        if (r.state != Sizes.State.EXACT && r.state != Sizes.State.FROM_SETS) emptyList()
        else r.rows.map { StockRow(v.article, it.colour.ifEmpty { v.colour }, it.size, it.qty, v.mrp, Calc.netRate(v) ?: v.rate) }
    }

    val STOCK_COLUMNS = listOf("Article", "Colour", "Size", "Quantity", "MRP", "Purchase Rate", "Supplier", "Invoice Number", "Invoice Date")

    private fun stock(inv: Invoice): Sheet {
        val s = Sheet("Size-wise Stock")
        s.freezeRows = 1; s.filterRow = 1
        s.widths = listOf(12.0, 14.0, 8.0, 10.0, 10.0, 14.0, 24.0, 18.0, 14.0)
        s.row(STOCK_COLUMNS.map { Cell.Text(it, St.HEADER) })
        for (r in stockRows(inv)) s.row(
            Cell.Text(r.article), Cell.Text(r.colour), Cell.Text(r.size), Cell.Num(BigDecimal(r.qty), St.INT), n(r.mrp), n(r.rate),
            t(inv.header.supplierName), t(inv.header.invoiceNo), t(inv.header.invoiceDate),
        )
        return s
    }

    /** The optional stock-import CSV (UTF-8, Excel friendly). */
    fun stockCsv(inv: Invoice): String {
        fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder("﻿").append(STOCK_COLUMNS.joinToString(",")).append("\r\n")
        for (r in stockRows(inv)) sb.append(listOf(r.article, r.colour, r.size, r.qty.toString(), Calc.fmt(r.mrp), Calc.fmt(r.rate),
            inv.header.supplierName.orEmpty(), inv.header.invoiceNo.orEmpty(), inv.header.invoiceDate.orEmpty()).joinToString(",") { q(it) }).append("\r\n")
        return sb.toString()
    }
}
