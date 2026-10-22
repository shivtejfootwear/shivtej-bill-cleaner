package com.shivtej.billscanner.core.model

import java.math.BigDecimal

/**
 * Every change the person makes goes through here, so each one is written into the
 * change history and the scanned original stays untouched.
 */
object Edits {
    private fun show(v: Any?) = when (v) { null -> "—"; is BigDecimal -> v.toPlainString(); else -> v.toString().ifEmpty { "—" } }

    private fun log(now: Long, id: String?, a: ItemValues, b: ItemValues): List<Change> {
        val pairs = listOf(
            "sr" to (a.sr to b.sr), "article" to (a.article to b.article), "name" to (a.name to b.name), "colour" to (a.colour to b.colour),
            "size" to (a.sizeText to b.sizeText), "hsn" to (a.hsn to b.hsn), "qty" to (a.qty to b.qty), "mrp" to (a.mrp to b.mrp),
            "rate" to (a.rate to b.rate), "total" to (a.gross to b.gross), "billDiscountPct" to (a.discPct to b.discPct),
            "discountAmt" to (a.discAmt to b.discAmt), "taxable" to (a.taxable to b.taxable), "cgstPct" to (a.cgstPct to b.cgstPct),
            "cgstAmt" to (a.cgstAmt to b.cgstAmt), "sgstPct" to (a.sgstPct to b.sgstPct), "sgstAmt" to (a.sgstAmt to b.sgstAmt),
            "igstPct" to (a.igstPct to b.igstPct), "igstAmt" to (a.igstAmt to b.igstAmt), "other" to (a.other to b.other), "notes" to (a.notes to b.notes),
        )
        return pairs.filter { (_, p) -> show(p.first) != show(p.second) && !(p.first is BigDecimal && p.second is BigDecimal && (p.first as BigDecimal).compareTo(p.second as BigDecimal) == 0) }
            .map { (k, p) -> Change(now, id, k, show(p.first), show(p.second)) }
    }

    /**
     * Saves new values for one line. When qty, rate, bill % or a tax % changed, the dependent
     * amounts are worked out again straight away.
     */
    fun updateItem(inv: Invoice, id: String, new: ItemValues, now: Long): Invoice {
        val old = inv.items.firstOrNull { it.id == id } ?: return inv
        val drivers = old.current.qty != new.qty || old.current.rate != new.rate || old.current.discPct != new.discPct ||
            old.current.cgstPct != new.cgstPct || old.current.sgstPct != new.sgstPct || old.current.igstPct != new.igstPct
        val v = if (drivers) Calc.recalc(new) else new
        val ch = log(now, id, old.current, v)
        if (ch.isEmpty()) return inv
        return inv.copy(items = inv.items.map { if (it.id == id) it.copy(current = v, verified = false) else it }, changes = inv.changes + ch)
    }

    fun recalcItem(inv: Invoice, id: String, now: Long): Invoice {
        val old = inv.items.firstOrNull { it.id == id } ?: return inv
        val v = Calc.recalc(old.current)
        val ch = log(now, id, old.current, v)
        return inv.copy(items = inv.items.map { if (it.id == id) it.copy(current = v) else it }, changes = inv.changes + ch)
    }

    fun recalcAll(inv: Invoice, now: Long): Invoice = inv.items.fold(inv) { acc, i -> recalcItem(acc, i.id, now) }

    /** Back to exactly what the scan read. */
    fun revertItem(inv: Invoice, id: String, now: Long): Invoice {
        val old = inv.items.firstOrNull { it.id == id } ?: return inv
        val ch = log(now, id, old.current, old.original)
        return inv.copy(items = inv.items.map { if (it.id == id) it.copy(current = it.original, verified = false) else it }, changes = inv.changes + ch)
    }

    fun setVerified(inv: Invoice, id: String, on: Boolean, now: Long): Invoice = inv.copy(
        items = inv.items.map { if (it.id == id) it.copy(verified = on) else it },
        changes = inv.changes + Change(now, id, "verified", (!on).toString(), on.toString()),
    )

    fun setTarget(inv: Invoice, id: String?, pct: BigDecimal?, now: Long): Invoice =
        if (id == null) inv.copy(defaultTargetPct = pct, changes = inv.changes + Change(now, null, "targetPct", show(inv.defaultTargetPct), show(pct)))
        else inv.copy(items = inv.items.map { if (it.id == id) it.copy(targetPct = pct) else it })

    fun addItem(inv: Invoice, now: Long): Invoice {
        val id = "m" + now.toString(36) + inv.items.size
        val v = ItemValues(sr = (inv.items.mapNotNull { it.current.sr }.maxOrNull() ?: 0) + 1)
        val item = LineItem(id, ItemKind.ARTICLE, v, v, needs = setOf(F.ARTICLE, F.QTY, F.RATE, F.DISC_PCT))
        return inv.copy(items = inv.items + item, changes = inv.changes + Change(now, id, "added", "—", "new line"))
    }

    fun duplicateItem(inv: Invoice, id: String, now: Long): Invoice {
        val i = inv.items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return inv
        val src = inv.items[i]
        val copy = src.copy(id = "d" + now.toString(36) + inv.items.size, original = src.current, verified = false)
        return inv.copy(items = inv.items.toMutableList().also { it.add(i + 1, copy) }, changes = inv.changes + Change(now, copy.id, "duplicated", src.id, copy.id))
    }

    fun deleteItem(inv: Invoice, id: String, now: Long): Invoice {
        val it = inv.items.firstOrNull { it.id == id } ?: return inv
        return inv.copy(items = inv.items - it, changes = inv.changes + Change(now, id, "deleted", "${it.current.sr ?: ""} ${it.current.article}".trim(), "—"))
    }

    fun updateHeader(inv: Invoice, h: Header, now: Long): Invoice {
        val fields = listOf<Pair<String, Pair<Any?, Any?>>>(
            "supplierName" to (inv.header.supplierName to h.supplierName), "supplierGstin" to (inv.header.supplierGstin to h.supplierGstin),
            "invoiceNo" to (inv.header.invoiceNo to h.invoiceNo), "invoiceDate" to (inv.header.invoiceDate to h.invoiceDate),
            "orderNo" to (inv.header.orderNo to h.orderNo), "transporter" to (inv.header.transporter to h.transporter),
            "printedGrandTotal" to (inv.header.printedGrandTotal to h.printedGrandTotal), "otherCharges" to (inv.header.otherCharges to h.otherCharges),
            "roundOff" to (inv.header.roundOff to h.roundOff), "overallDiscount" to (inv.header.overallDiscount to h.overallDiscount),
        )
        val ch = fields.filter { (_, p) -> show(p.first) != show(p.second) }.map { (k, p) -> Change(now, null, k, show(p.first), show(p.second)) }
        return inv.copy(header = h, changes = inv.changes + ch)
    }

    fun setStatus(inv: Invoice, s: InvoiceStatus, now: Long) = inv.copy(status = s, changes = inv.changes + Change(now, null, "status", inv.status.name, s.name))
}
