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
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Groups
import com.shivtej.billscanner.core.model.Target
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
    private const val GREEN = 0xFF15803D.toInt()
    private const val GREEN_SOFT = 0xFFE7F6EC.toInt()

    private class Col(val title: String, val w: Float, val right: Boolean = true, val orange: Boolean = false, val green: Boolean = false)

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
                    fill.color = if (col.orange) ORANGE else if (col.green) GREEN else NAVY
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
                    if (col.orange || col.green) { fill.color = if (col.green) GREEN_SOFT else ORANGE_SOFT; c!!.drawRect(x, y, x + cw, y + rowH, fill) }
                    val p = Paint(if (col.orange || col.green) bold else text).apply { textSize = 7.6f; color = if ((ri to ci) in warnCol || col.orange) ORANGE_DARK else if (col.green) GREEN else 0xFF14202E.toInt() }
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

    private fun weight(f: F?): Float = when (f) {
        F.SR -> 18f; F.ARTICLE -> 120f; F.HSN -> 42f; F.GST_PCT -> 26f; F.SIZE -> 40f; F.COLOUR -> 50f; F.MRP -> 36f; F.QTY -> 32f
        F.RATE -> 40f; F.GROSS -> 44f; F.DISC_PCT -> 34f; F.DISC_AMT -> 38f; F.TAXABLE -> 48f; F.TOTAL -> 48f
        F.CGST_PCT, F.SGST_PCT, F.IGST_PCT -> 26f; F.CGST_AMT, F.SGST_AMT, F.IGST_AMT -> 36f; else -> 40f
    }

    /** A. Bill report: every column as printed (or as corrected), with the बैठा % beside it. */
    fun report(inv: Invoice, landscape: Boolean, out: OutputStream) {
        val doc = PdfDocument()
        val wr = Writer(doc, if (landscape) 842 else 595, if (landscape) 595 else 842,
            "Shivtej Bill Scanner AI · bill report · generated ${stamp()} · not the supplier's original invoice")
        val h = inv.header; val t = Totals.of(inv)
        wr.newPage()
        wr.line("${h.supplierName ?: "Supplier not found"}", wr.bold, 14f, NAVY, 5f)
        wr.line("Invoice ${h.invoiceNo ?: "Not found"} · Date ${h.invoiceDate ?: "Not found"} · GSTIN ${h.supplierGstin ?: "Not found"}" + (h.pageNote?.let { " · $it" } ?: ""), size = 8.5f)
        wr.line("Buyer: ${h.buyerName ?: "Not found"} · GSTIN ${h.buyerGstin ?: "Not found"} · Status: ${if (inv.status.name == "VERIFIED") "Verified" else "Not verified yet"}", size = 8.5f, gap = 8f)
        val cols = InvoiceExport.printedColumns(inv)
        val pcols = cols.map { c -> Col(c.label, weight(c.field), right = c.field != null && c.field !in setOf(F.ARTICLE, F.SIZE, F.COLOUR, F.HSN, F.SR), orange = c.field == F.DISC_PCT) } +
            Col("Baitha % (on MRP${if (inv.sittingWithGst) ", with GST" else ""})", 46f, green = true)
        val warn = mutableSetOf<Pair<Int, Int>>()
        val rows = inv.items.mapIndexed { r, it ->
            cols.mapIndexed { ci, c ->
                if (c.field != null && c.field in it.needs) warn += r to ci
                InvoiceExport.printedText(it, ci, c).ifEmpty { if (c.field == F.DISC_PCT && it.current.discPct == null) NEEDS_VERIFICATION else "" }
            } + (if (it.kind == ItemKind.CHARGE) "" else p(Calc.sittingPct(it.current, h, inv.sittingWithGst)))
        }
        wr.table(pcols, rows, warn)
        wr.line("Totals", wr.bold, 10f, NAVY, 4f)
        wr.line("Articles ${t.articleLines} · Pairs ${t.qty.toPlainString()} · Amount before GST ₹${n(t.taxable)} · CGST ₹${n(t.cgst)} · SGST ₹${n(t.sgst)}" +
            (if (t.igst.signum() != 0) " · IGST ₹${n(t.igst)}" else "") + (h.roundOff?.let { " · Round off ₹${n(it)}" } ?: ""))
        wr.line("Calculated total ₹${n(t.grand)} · Printed on bill ₹${n(t.printedGrand)} · Difference ₹${n(t.difference)} · Baitha % on the whole bill ${p(Calc.sittingTotal(inv))}", wr.bold, 9f, NAVY, 8f)
        val ws = Warnings.of(inv).filter { it.level.name != "TIP" }
        if (ws.isNotEmpty()) {
            wr.line("Points to check", wr.bold, 10f, NAVY, 4f)
            ws.forEach { wr.line("• ${it.english}", size = 8f, color = ORANGE_DARK) }
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

    /** C. The new bill at the person's target % — their own calculation, labelled as such on every page. */
    fun target(inv: Invoice, out: OutputStream) {
        val doc = PdfDocument()
        val wr = Writer(doc, 595, 842, "Buyer's calculation at target % · not the supplier's tax invoice · Shivtej Bill Scanner AI · ${stamp()}")
        val h = inv.header
        val sheet = Target.sheet(inv)
        wr.newPage()
        wr.line("New bill at target %", wr.bold, 15f, ORANGE, 5f)
        wr.line("${h.supplierName ?: "Supplier"} · invoice ${h.invoiceNo ?: ""} · ${h.invoiceDate ?: ""}", wr.bold, 9.5f, NAVY, 3f)
        val by = Groups.of(inv).joinToString("  ·  ") { g -> "${g.key}: ${(g.target ?: inv.defaultTargetPct)?.let { p(it) } ?: "as billed"}" }
        wr.line("Target % — $by", size = 8.5f, color = NAVY, gap = 3f)
        wr.line("Rates worked out from MRP" + (if (inv.sittingWithGst) " (target % includes GST)" else "") + ". The supplier's invoice is unchanged.", size = 8f, color = MUTED, gap = 8f)
        val cols = listOf(Col("#", 16f, false), Col("Article", 118f, false), Col("Size", 34f, false), Col("MRP", 34f), Col("Pairs", 26f),
            Col("Baitha % (bill)", 36f, green = true), Col("Target %", 32f, orange = true), Col("New Rate / Pair", 40f), Col("New Amount", 46f), Col("Bill Amount", 46f))
        val rows = sheet.lines.map { l ->
            val v = l.item.current
            listOf("${v.sr ?: ""}", listOf(v.article, v.name, v.colour).filter { it.isNotBlank() }.joinToString(" "), v.sizeText, n(v.mrp), v.qty?.stripTrailingZeros()?.toPlainString() ?: "",
                p(Calc.sittingPct(v, h, inv.sittingWithGst)), p(l.pct), n(l.net), n(l.amount), n(l.billAmount))
        } + sheet.untouched.filter { it.current.taxable != null }.map { i ->
            val v = i.current
            listOf("${v.sr ?: ""}", listOf(v.article, v.name, v.colour).filter { it.isNotBlank() }.joinToString(" ") + " (as billed)", v.sizeText, n(v.mrp),
                v.qty?.stripTrailingZeros()?.toPlainString() ?: "", p(Calc.sittingPct(v, h, inv.sittingWithGst)), "—", n(v.rate), n(v.taxable), n(v.taxable))
        }
        wr.table(cols, rows)
        wr.line("Amount before GST ₹${n(sheet.taxable)}   ·   GST ₹${n(sheet.gst)}   ·   Total ₹${n(sheet.rounded)}", wr.bold, 11f, NAVY, 5f)
        wr.line("Supplier's bill total ₹${n(sheet.billTotal)}   ·   Difference ₹${n(sheet.difference)}", wr.bold, 10f, if (sheet.difference.signum() < 0) GREEN else ORANGE_DARK, 4f)
        wr.finish(); doc.writeTo(out); doc.close()
    }

    /** All pages of a PDF stacked into one PNG (for sending as a photo). */
    fun toPng(ctx: android.content.Context, pdf: ByteArray, out: OutputStream) {
        val f = java.io.File(ctx.cacheDir, "render.pdf").apply { writeBytes(pdf) }
        android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { r ->
                val k = 2f
                val pages = (0 until r.pageCount).map { i ->
                    r.openPage(i).use { pg ->
                        val b = Bitmap.createBitmap((pg.width * k).toInt(), (pg.height * k).toInt(), Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        pg.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
                val all = Bitmap.createBitmap(pages.maxOf { it.width }, pages.sumOf { it.height }, Bitmap.Config.ARGB_8888)
                val cv = Canvas(all)
                var y = 0f
                for (b in pages) { cv.drawBitmap(b, 0f, y, null); y += b.height; b.recycle() }
                all.compress(Bitmap.CompressFormat.PNG, 100, out)
                all.recycle()
            }
        }
        f.delete()
    }
}
