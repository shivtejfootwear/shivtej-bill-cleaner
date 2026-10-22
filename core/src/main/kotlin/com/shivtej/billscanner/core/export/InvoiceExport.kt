package com.shivtej.billscanner.core.export

import com.shivtej.billscanner.core.model.Calc
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

    fun xlsx(inv: Invoice, out: OutputStream) = Xlsx.write(listOf(summary(inv), items(inv), stock(inv)), out)

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
