package com.shivtej.billscanner.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.shivtej.billscanner.core.export.NEEDS_VERIFICATION
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.export.InvoiceExport
import java.io.OutputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Print-ready A4 PDFs. Tables continue over pages with the column headers repeated. */
object Pdf {
    private const val NAVY = 0xFF0E2A47.toInt()
    private const val ORANGE = 0xFFF28A1E.toInt()
    private const val ORANGE_SOFT = 0xFFFFF1E2.toInt()
    private const val ORANGE_DARK = 0xFFB45309.toInt()
    private const val LINE = 0xFFD5DCE6.toInt()
    private const val MUTED = 0xFF5D6B7A.toInt()

    private class Col(val title: String, val w: Float, val right: Boolean = true, val orange: Boolean = false)

    private class Writer(val doc: PdfDocument, val w: Int, val h: Int, val footer: String) {
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f
        var n = 0
        val margin = 30f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = 0xFF14202E.toInt(); typeface = Typeface.DEFAULT }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val fill = Paint().apply { style = Paint.Style.FILL }
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = LINE }

        fun newPage() {
            finish()
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create())
            c = page!!.canvas
            y = margin
        }

        fun finish() {
            val p = page ?: return
            val cv = p.canvas
            val f = Paint(text).apply { textSize = 7f; color = MUTED }
            cv.drawText(footer, margin, h - 14f, f)
            cv.drawText("Page $n", w - margin - f.measureText("Page $n"), h - 14f, f)
            doc.finishPage(p)
            page = null
        }

        fun ensure(space: Float, onNew: () -> Unit = {}) { if (page == null || y + space > h - 34f) { newPage(); onNew() } }

        fun line(s: String, p: Paint = text, size: Float = 8.5f, color: Int = 0xFF14202E.toInt(), gap: Float = 3f) {
            val pp = Paint(p).apply { textSize = size; this.color = color }
            for (part in wrap(s, pp, w - 2 * margin)) { ensure(size + gap); y += size; c!!.drawText(part, margin, y, pp); y += gap }
        }

        fun wrap(s: String, p: Paint, max: Float): List<String> {
            val out = mutableListOf<String>(); var cur = ""
            for (word in s.split(' ')) {
                val t = if (cur.isEmpty()) word else "$cur $word"
                if (p.measureText(t) > max && cur.isNotEmpty()) { out += cur; cur = word } else cur = t
            }
            if (cur.isNotEmpty()) out += cur
            return out.ifEmpty { listOf("") }
        }

        fun fit(s: String, p: Paint, max: Float): String {
            if (p.measureText(s) <= max) return s
            var t = s
            while (t.isNotEmpty() && p.measureText("$t…") > max) t = t.dropLast(1)
            return "$t…"
        }

        fun table(cols: List<Col>, rows: List<List<String>>, warnCol: Set<Pair<Int, Int>> = emptySet()) {
            val total = cols.sumOf { it.w.toDouble() }.toFloat()
            val k = (w - 2 * margin) / total
            val rowH = 13f
            fun header() {
                var x = margin
                val hp = Paint(bold).apply { textSize = 7.2f; color = Color.WHITE }
                val hh = 22f
                for (col in cols) {
                    fill.color = if (col.orange) ORANGE else NAVY
                    c!!.drawRect(x, y, x + col.w * k, y + hh, fill)
                    val lines = wrap(col.title, hp, col.w * k - 4f).take(2)
                    lines.forEachIndexed { i, l -> c!!.drawText(fit(l, hp, col.w * k - 4f), x + 2f, y + 9f + i * 8.5f, hp) }
                    x += col.w * k
                }
                y += hh
            }
            ensure(40f) { }
            header()
            rows.forEachIndexed { ri, r ->
                if (y + rowH > h - 34f) { newPage(); header() }
                var x = margin
                for ((ci, col) in cols.withIndex()) {
                    val cw = col.w * k
                    if (col.orange) { fill.color = ORANGE_SOFT; c!!.drawRect(x, y, x + cw, y + rowH, fill) }
                    val p = Paint(if (col.orange) bold else text).apply { textSize = 7.6f; color = if ((ri to ci) in warnCol || col.orange) ORANGE_DARK else 0xFF14202E.toInt() }
                    val s = fit(r.getOrElse(ci) { "" }, p, cw - 4f)
                    c!!.drawText(s, if (col.right) x + cw - 2f - p.measureText(s) else x + 2f, y + 9.5f, p)
                    x += cw
                }
                c!!.drawLine(margin, y + rowH, margin + total * k, y + rowH, stroke)
                y += rowH
            }
            y += 6f
        }
    }

    private fun stamp() = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
    private fun n(v: BigDecimal?) = v?.let { Calc.r2(it).toPlainString() } ?: "Not found"
    private fun p(v: BigDecimal?) = v?.let { Calc.r2(it).toPlainString() + "%" } ?: ""

    /** A. Verified data report: built from the reviewed values, not a copy of the supplier's invoice. */
    fun report(inv: Invoice, landscape: Boolean, out: OutputStream) {
        val doc = PdfDocument()
        val wr = Writer(doc, if (landscape) 842 else 595, if (landscape) 595 else 842,
            "Shivtej Bill Scanner AI · Verified data report · generated ${stamp()} · not the supplier's original invoice")
        val h = inv.header; val t = Totals.of(inv)
        wr.newPage()
        wr.line("Invoice Review Report", wr.bold, 15f, NAVY, 6f)
        wr.line("${h.supplierName ?: "Supplier not found"} · GSTIN ${h.supplierGstin ?: "Not found"}", wr.bold, 10f, NAVY)
        h.supplierAddress?.let { wr.line(it, size = 8f, color = MUTED) }
        wr.line("Invoice ${h.invoiceNo ?: "Not found"} · Date ${h.invoiceDate ?: "Not found"} · Order ${h.orderNo ?: "Not found"} · Transport ${h.transporter ?: "Not found"} · Packing ${h.packing ?: "Not found"}" +
            (h.pageNote?.let { " · $it" } ?: ""), size = 8.5f)
        wr.line("Buyer: ${h.buyerName ?: "Not found"} · GSTIN ${h.buyerGstin ?: "Not found"} · Status: ${if (inv.status.name == "VERIFIED") "Verified" else "Not verified yet"}", size = 8.5f, gap = 8f)
        val cols = if (landscape) listOf(
            Col("#", 18f, false), Col("Article No. & Name", 120f, false), Col("Colour", 52f, false), Col("Size", 78f, false), Col("Qty", 26f),
            Col("MRP", 40f), Col("Original Rate", 44f), Col("Original Bill Discount (%)", 54f, orange = true), Col("Discount ₹", 46f),
            Col("Calc. Disc. vs MRP (%)", 48f), Col("Taxable", 52f), Col("GST ₹", 42f), Col("Final Line Total", 56f), Col("Status", 58f, false),
        ) else listOf(
            Col("#", 16f, false), Col("Article No. & Name", 96f, false), Col("Qty", 24f), Col("MRP", 38f), Col("Original Rate", 42f),
            Col("Original Bill Discount (%)", 52f, orange = true), Col("Discount ₹", 44f), Col("Calc. Disc. vs MRP (%)", 46f), Col("Final Line Total", 52f), Col("Status", 52f, false),
        )
        val warn = mutableSetOf<Pair<Int, Int>>()
        val rows = inv.items.mapIndexed { i, it ->
            val v = it.current
            val disc = if (v.discPct == null) NEEDS_VERIFICATION.also { warn += i to (if (landscape) 7 else 5) } else p(v.discPct)
            val name = "${v.article} ${v.name}".trim() + if (it.kind == ItemKind.CHARGE) " (charge)" else ""
            val status = InvoiceExport.status(it)
            if (landscape) listOf("${v.sr ?: ""}", name, v.colour, v.sizeText, v.qty?.stripTrailingZeros()?.toPlainString() ?: "", n(v.mrp), n(v.rate), disc, n(v.discAmt),
                p(Calc.mrpDiscPct(v)), n(v.taxable), Calc.fmt(Calc.taxTotal(v)), n(Calc.lineTotal(v)), status)
            else listOf("${v.sr ?: ""}", name, v.qty?.stripTrailingZeros()?.toPlainString() ?: "", n(v.mrp), n(v.rate), disc, n(v.discAmt), p(Calc.mrpDiscPct(v)), n(Calc.lineTotal(v)), status)
        }
        wr.table(cols, rows, warn)
        wr.line("Totals", wr.bold, 10f, NAVY, 4f)
        wr.line("Lines ${t.lines} · Articles ${t.articleLines} · Pairs ${t.qty.toPlainString()} · Before discount ₹${n(t.gross)} · Bill discount ₹${n(t.discount)} · Taxable ₹${n(t.taxable)}")
        wr.line("CGST ₹${n(t.cgst)} · SGST ₹${n(t.sgst)} · IGST ₹${n(t.igst)} · Other ₹${n(t.other)} · Recalculated total ₹${n(t.grand)} · Printed total ₹${n(t.printedGrand)} · Difference ₹${n(t.difference)}", gap = 8f)
        val ws = Warnings.of(inv)
        if (ws.isNotEmpty()) {
            wr.line("Verification notes", wr.bold, 10f, NAVY, 4f)
            ws.forEach { wr.line("• ${it.level.name}: ${it.english}", size = 8f, color = if (it.level.name == "TIP") MUTED else ORANGE_DARK) }
        }
        wr.finish(); doc.writeTo(out); doc.close()
    }

    /** B. The original photos embedded as they are — a copy for reference, not a digital original. */
    fun photos(pages: List<Bitmap>, title: String, out: OutputStream) {
        val doc = PdfDocument()
        val wr = Writer(doc, 595, 842, "Original invoice photo — copy for reference only, not an official digital invoice")
        pages.forEachIndexed { i, b ->
            wr.newPage()
            wr.line("$title · photo ${i + 1} of ${pages.size}", wr.bold, 9f, NAVY, 4f)
            val box = RectF(wr.margin, wr.y + 4f, 595 - wr.margin, 842 - 40f)
            val k = minOf(box.width() / b.width, box.height() / b.height)
            val dw = b.width * k; val dh = b.height * k
            val dst = RectF(box.left + (box.width() - dw) / 2, box.top, box.left + (box.width() - dw) / 2 + dw, box.top + dh)
            wr.c!!.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        wr.finish(); doc.writeTo(out); doc.close()
    }

    /** C. Adjusted / target-rate sheet — the person's own estimate, labelled as such on every page. */
    fun target(inv: Invoice, out: OutputStream) {
        val doc = PdfDocument()
        val wr = Writer(doc, 842, 595, "USER-GENERATED ESTIMATE · not the supplier's invoice · Shivtej Bill Scanner AI · ${stamp()}")
        wr.newPage()
        wr.line("Adjusted / Target-Rate Calculation (user estimate)", wr.bold, 14f, ORANGE, 5f)
        wr.line("Based on ${inv.header.supplierName ?: "supplier"} invoice ${inv.header.invoiceNo ?: ""} ${inv.header.invoiceDate ?: ""}. The supplier's invoice values are unchanged.", size = 8.5f, color = MUTED, gap = 8f)
        val cols = listOf(Col("#", 18f, false), Col("Article", 110f, false), Col("Qty", 28f), Col("MRP", 44f), Col("Original Rate", 50f),
            Col("Original Bill Discount (%)", 58f, orange = true), Col("Calc. Disc. vs MRP (%)", 52f), Col("Target Discount (%)", 52f),
            Col("Target Rate", 50f), Col("Target − Original Rate", 58f), Col("Value at Target", 62f))
        var total = BigDecimal.ZERO
        val rows = inv.items.filter { it.kind == ItemKind.ARTICLE }.map { it ->
            val v = it.current
            val tp = it.targetPct ?: inv.defaultTargetPct
            val tr = Calc.targetRate(v.mrp, tp)
            val value = if (tr != null && v.qty != null) Calc.r2(tr * v.qty) else null
            value?.let { total += it }
            listOf("${v.sr ?: ""}", "${v.article} ${v.name}".trim(), v.qty?.stripTrailingZeros()?.toPlainString() ?: "", n(v.mrp), n(v.rate),
                v.discPct?.let { p(it) } ?: NEEDS_VERIFICATION, p(Calc.mrpDiscPct(v)), p(tp), tr?.let { n(it) } ?: "", if (tr != null && v.rate != null) n(tr - v.rate) else "", value?.let { n(it) } ?: "")
        }
        wr.table(cols, rows)
        wr.line("Total value at target rates (before tax): ₹${n(total)}", wr.bold, 10f, NAVY)
        wr.finish(); doc.writeTo(out); doc.close()
    }
}
