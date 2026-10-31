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

    private class Writer(val doc: PdfDocument, val w: Int, val h: Int, val footer: String, val margin: Float = 30f) {
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f
        var n = 0
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

    /**
     * A. Bill report: every column as printed (or as corrected), the बैठा % beside it, and — when a % of ours is set —
     * our % and the new amount, with the bill's totals beside the new totals.
     */
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
        val sheet = Target.sheet(inv)
        val lines = sheet.lines.associateBy { it.item.id }
        val pcols = cols.map { c -> Col(c.label, weight(c.field), right = c.field != null && c.field !in setOf(F.ARTICLE, F.SIZE, F.COLOUR, F.HSN, F.SR), orange = c.field == F.DISC_PCT) } +
            Col("Baitha % (on MRP${if (inv.sittingWithGst) ", with GST" else ""})", 46f, green = true) +
            (if (sheet.any) listOf(Col("Our %", 32f, orange = true), Col("New Amount", 48f, orange = true)) else emptyList())
        val warn = mutableSetOf<Pair<Int, Int>>()
        val rows = inv.items.mapIndexed { r, it ->
            cols.mapIndexed { ci, c ->
                if (c.field != null && c.field in it.needs) warn += r to ci
                InvoiceExport.printedText(inv, it, ci, c).ifEmpty { if (c.field == F.DISC_PCT && it.current.discPct == null && F.DISC_PCT in it.needs) NEEDS_VERIFICATION else "" }
            } + (if (it.kind == ItemKind.CHARGE) "" else p(Calc.sittingPct(it.current, h, inv.sittingWithGst))) +
                (if (!sheet.any) emptyList() else lines[it.id]?.let { l -> listOf(p(l.pct), n(Target.newShown(inv, l))) } ?: listOf("—", Target.keptShown(inv, it)?.let { x -> n(x) } ?: ""))
        }
        wr.table(pcols, rows, warn)
        wr.line("Totals", wr.bold, 10f, NAVY, 4f)
        wr.line("Articles ${t.articleLines} · Pairs ${t.qty.toPlainString()} · Amount before GST ₹${n(t.taxable)} · CGST ₹${n(t.cgst)} · SGST ₹${n(t.sgst)}" +
            (if (t.igst.signum() != 0) " · IGST ₹${n(t.igst)}" else "") + (h.roundOff?.let { " · Round off ₹${n(it)}" } ?: ""))
        wr.line("Calculated total ₹${n(t.grand)} · Printed on bill ₹${n(t.printedGrand)} · Difference ₹${n(t.difference)} · Baitha % on the whole bill ${p(Calc.sittingTotal(inv))}", wr.bold, 9f, NAVY, 8f)
        if (sheet.any) {
            val by = Groups.of(inv).joinToString("  ·  ") { g -> "${g.key}: ${(g.target ?: inv.defaultTargetPct)?.let { p(it) } ?: "as billed"}" }
            wr.line("At our % — $by", wr.bold, 10f, ORANGE_DARK, 3f)
            wr.line("New amount ₹${n(sheet.taxable)} · GST ₹${n(sheet.gst)} · New total ₹${n(sheet.rounded)} · Bill total ₹${n(sheet.billTotal)} · Difference ₹${n(sheet.difference)}", wr.bold, 9.5f, NAVY, 3f)
            wr.line("New amounts are our own calculation from MRP, not the supplier's invoice.", size = 7.5f, color = MUTED, gap = 8f)
        }
        val ws = Warnings.of(inv).filter { it.level.name != "TIP" }
        if (ws.isNotEmpty()) {
            wr.line("Points to check", wr.bold, 10f, NAVY, 4f)
            ws.forEach { wr.line("• ${it.english}", size = 8f, color = ORANGE_DARK) }
        }
        wr.finish(); doc.writeTo(out); doc.close()
    }

    /**
     * The bill as cards on a phone-size page (owner's choice, option A): one card per article with the supplier's
     * rate and amount, our rate and amount at the target % below it (orange), and the baitha %. Fits a phone
     * screen without zooming out; zoom still works.
     */
    fun cards(inv: Invoice, out: OutputStream) {
        val doc = PdfDocument()
        val pw = 360; val ph = 740
        val wr = Writer(doc, pw, ph, "Shivtej Bill Scanner AI · ${stamp()} · सप्लायर का असली बिल नहीं", margin = 12f)
        val h = inv.header; val t = Totals.of(inv)
        val sheet = Target.sheet(inv)
        val lines = sheet.lines.associateBy { it.item.id }
        val m = wr.margin; val right = pw - m
        val ink = 0xFF14202E.toInt()
        fun paint(size: Float, color: Int = ink, b: Boolean = false) = Paint(if (b) wr.bold else wr.text).apply { textSize = size; this.color = color }
        fun rs(v: BigDecimal?) = v?.let { "₹" + String.format(Locale.ENGLISH, "%,.2f", Calc.r2(it)) } ?: "—"
        fun cut(s: String, p: Paint, max: Float): String {
            if (p.measureText(s) <= max) return s
            var e = s
            while (e.isNotEmpty() && p.measureText("$e…") > max) e = e.dropLast(1)
            return "$e…"
        }
        // Left text and right text on one line.
        fun pair(y: Float, l: String, r: String, lp: Paint, rp: Paint, inset: Float = 8f) {
            val rw = rp.measureText(r)
            wr.c!!.drawText(cut(l, lp, right - m - 2 * inset - rw - 8f), m + inset, y, lp)
            wr.c!!.drawText(r, right - inset - rw, y, rp)
        }
        fun band() {
            // Navy head on every page.
            wr.fill.color = NAVY
            wr.c!!.drawRect(0f, 0f, pw.toFloat(), 48f, wr.fill)
            wr.c!!.drawText(cut(h.supplierName ?: "सप्लायर", paint(13f, Color.WHITE, true), pw - 24f), m, 20f, paint(13f, Color.WHITE, true))
            val sub = "बिल ${h.invoiceNo ?: "?"} · ${h.invoiceDate ?: "?"} · ${t.qty.stripTrailingZeros().toPlainString()} जोड़ी" + (if (wr.n > 1) " · पेज ${wr.n}" else "")
            wr.c!!.drawText(cut(sub, paint(8.5f, 0xFFD5DCE6.toInt()), pw - 24f), m, 36f, paint(8.5f, 0xFFD5DCE6.toInt()))
            wr.y = 58f
        }
        fun ensure(space: Float) { if (wr.page == null || wr.y + space > ph - 30f) { wr.newPage(); band() } }
        val rowH = 13f
        for (it in inv.items) {
            val v = it.current
            if (it.kind == ItemKind.CHARGE) {
                ensure(24f)
                val top = wr.y
                wr.stroke.color = LINE
                wr.c!!.drawRoundRect(RectF(m, top, right, top + 20f), 7f, 7f, wr.stroke)
                pair(top + 13.5f, listOf(v.article, v.name).filter { s -> s.isNotBlank() }.joinToString(" ").ifBlank { "खर्च" } + (v.gross?.let { g -> " · ${rs(g)}" } ?: ""),
                    rs(v.taxable ?: v.gross), paint(8.5f, MUTED), paint(8.5f, MUTED))
                wr.y = top + 26f
                continue
            }
            val l = lines[it.id]
            val sit = Calc.sittingPct(v, h, inv.sittingWithGst)
            val n = 3 + (if (l != null) 1 else 0) + (if (sit != null) 1 else 0) + (if (it.needs.isNotEmpty()) 1 else 0)
            val cardH = 10f + n * rowH
            ensure(cardH + 6f)
            val top = wr.y
            val box = RectF(m, top, right, top + cardH)
            wr.stroke.color = if (it.needs.isNotEmpty()) ORANGE else LINE
            wr.stroke.strokeWidth = if (it.needs.isNotEmpty()) 1f else 0.6f
            wr.c!!.drawRoundRect(box, 8f, 8f, wr.stroke)
            wr.stroke.strokeWidth = 0.5f
            var y = top + 14f
            pair(y, "${v.sr?.let { s -> "$s. " } ?: ""}${v.article.ifBlank { v.name }}", v.mrp?.let { mm -> "MRP ${rs(mm)}" } ?: "MRP —", paint(10f, NAVY, true), paint(8.5f, MUTED))
            y += rowH
            val qty = v.qty?.stripTrailingZeros()?.toPlainString()
            pair(y, listOf(v.name.takeIf { s -> s != v.article }.orEmpty(), listOf(v.colour, v.sizeText).filter { s -> s.isNotBlank() }.joinToString(" "), qty?.let { q -> "$q जोड़ी" }.orEmpty())
                .filter { s -> s.isNotBlank() }.joinToString(" · "), "", paint(8f, MUTED), paint(8f, MUTED))
            y += rowH
            pair(y, "उनका रेट ${rs(v.rate)}" + (v.discPct?.let { d -> " · ${p(d)}" } ?: ""), rs(v.taxable), paint(9f), paint(9.5f, ink, true))
            if (l != null) { y += rowH; pair(y, "अपना रेट ${rs(l.net)} · ${p(l.pct)}", rs(Target.newShown(inv, l)), paint(9f, ORANGE_DARK), paint(9.5f, ORANGE_DARK, true)) }
            if (sit != null) { y += rowH; pair(y, "बैठा ${p(sit)}", "", paint(9f, GREEN, true), paint(9f)) }
            if (it.needs.isNotEmpty()) { y += rowH; pair(y, "⚠ जाँचें: " + it.needs.joinToString(", ") { f -> f.name.lowercase() }, "", paint(8f, ORANGE_DARK), paint(8f)) }
            wr.y = top + cardH + 6f
        }
        // Totals: theirs, ours, the difference.
        val tl = 3 + (if (sheet.any) 2 else 0)
        ensure(14f + tl * 14f)
        val top = wr.y + 2f
        val tb = RectF(m, top, right, top + 10f + tl * 14f)
        wr.fill.color = 0xFFF4F6FA.toInt(); wr.c!!.drawRoundRect(tb, 8f, 8f, wr.fill)
        var y = top + 16f
        val bill = t.printedGrand ?: t.grand
        pair(y, "उनका Total (बिल पर)", rs(bill), paint(9.5f), paint(10f, NAVY, true)); y += 14f
        val d = t.difference
        pair(y, "लाइनों से जोड़ ${rs(t.grand)}", if (d == null || d.abs() <= BigDecimal("0.99")) "✓ मिला" else "फ़र्क़ ${rs(d)}", paint(8.5f, MUTED),
            paint(8.5f, if (d == null || d.abs() <= BigDecimal("0.99")) GREEN else ORANGE_DARK, true)); y += 14f
        if (sheet.any) {
            pair(y, "अपना Total", rs(sheet.rounded), paint(9.5f, ORANGE_DARK), paint(10f, ORANGE_DARK, true)); y += 14f
            pair(y, "फ़र्क़", rs(sheet.difference), paint(9.5f), paint(10f, if (sheet.difference.signum() < 0) GREEN else ORANGE_DARK, true)); y += 14f
        }
        pair(y, "पूरे बिल पर बैठा %", p(Calc.sittingTotal(inv)), paint(9.5f, GREEN), paint(10f, GREEN, true))
        wr.y = tb.bottom + 10f
        val ws = Warnings.of(inv).filter { w -> w.level.name != "TIP" }
        if (ws.isNotEmpty()) {
            ensure(16f); wr.c!!.drawText("जाँचें", m, wr.y + 8f, paint(10f, NAVY, true)); wr.y += 16f
            for (w in ws) for (ln in wr.wrap("• ${w.hindi}", paint(8f, ORANGE_DARK), right - m)) {
                ensure(11f); wr.c!!.drawText(ln, m, wr.y + 7f, paint(8f, ORANGE_DARK)); wr.y += 11f
            }
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
