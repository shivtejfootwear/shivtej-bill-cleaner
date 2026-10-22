package com.shivtej.billscanner.core.store

import com.shivtej.billscanner.core.model.Change
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.InvoiceStatus
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineItem
import java.math.BigDecimal

/** Saves an invoice as JSON. Money is written as text so no paisa is ever lost to rounding. */
object InvoiceCodec {
    const val VERSION = 1

    fun encode(inv: Invoice): String = Json.write(linkedMapOf(
        "v" to VERSION, "id" to inv.id, "createdAt" to inv.createdAt, "status" to inv.status.name,
        "headerOriginal" to header(inv.headerOriginal), "header" to header(inv.header),
        "items" to inv.items.map { item(it) }, "pages" to inv.pages,
        "defaultTargetPct" to inv.defaultTargetPct?.toPlainString(),
        "changes" to inv.changes.map { linkedMapOf("at" to it.at, "item" to it.itemId, "field" to it.field, "from" to it.from, "to" to it.to) },
        "exportedAt" to inv.exportedAt,
    ))

    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): Invoice {
        val m = Json.parse(text) as Map<String, Any?>
        return Invoice(
            id = m.str("id"), createdAt = m.lng("createdAt"),
            headerOriginal = header(m.obj("headerOriginal") ?: emptyMap()), header = header(m.obj("header") ?: emptyMap()),
            items = m.list("items").map { item(it as Map<String, Any?>) },
            pages = m.list("pages").map { it.toString() },
            status = runCatching { InvoiceStatus.valueOf(m.str("status")) }.getOrDefault(InvoiceStatus.DRAFT),
            defaultTargetPct = (m["defaultTargetPct"] as? String)?.toBigDecimalOrNull(),
            changes = m.list("changes").map { c -> (c as Map<String, Any?>).let { Change(it.lng("at"), it["item"] as? String, it.str("field"), it.str("from"), it.str("to")) } },
            exportedAt = (m["exportedAt"] as? Number)?.toLong(),
        )
    }

    private fun b(v: BigDecimal?) = v?.toPlainString()
    private fun Map<String, Any?>.dec(k: String): BigDecimal? = (this[k] as? String)?.toBigDecimalOrNull()
    private fun Map<String, Any?>.s(k: String): String? = this[k] as? String

    private fun header(h: Header) = linkedMapOf(
        "supplierName" to h.supplierName, "supplierAddress" to h.supplierAddress, "supplierGstin" to h.supplierGstin,
        "supplierContact" to h.supplierContact, "invoiceNo" to h.invoiceNo, "invoiceDate" to h.invoiceDate, "orderNo" to h.orderNo,
        "transporter" to h.transporter, "cartons" to h.cartons, "packing" to h.packing, "buyerName" to h.buyerName,
        "buyerGstin" to h.buyerGstin, "printedGrandTotal" to b(h.printedGrandTotal), "overallDiscount" to b(h.overallDiscount),
        "otherCharges" to b(h.otherCharges), "roundOff" to b(h.roundOff), "pageNote" to h.pageNote,
    )

    private fun header(m: Map<String, Any?>) = Header(
        m.s("supplierName"), m.s("supplierAddress"), m.s("supplierGstin"), m.s("supplierContact"), m.s("invoiceNo"),
        m.s("invoiceDate"), m.s("orderNo"), m.s("transporter"), m.s("cartons"), m.s("packing"), m.s("buyerName"),
        m.s("buyerGstin"), m.dec("printedGrandTotal"), m.dec("overallDiscount"), m.dec("otherCharges"), m.dec("roundOff"), m.s("pageNote"),
    )

    private fun values(v: ItemValues) = linkedMapOf(
        "sr" to v.sr, "article" to v.article, "name" to v.name, "brand" to v.brand, "colour" to v.colour, "sizeText" to v.sizeText,
        "hsn" to v.hsn, "qty" to b(v.qty), "mrp" to b(v.mrp), "rate" to b(v.rate), "gross" to b(v.gross), "discPct" to b(v.discPct),
        "discAmt" to b(v.discAmt), "taxable" to b(v.taxable), "cgstPct" to b(v.cgstPct), "cgstAmt" to b(v.cgstAmt),
        "sgstPct" to b(v.sgstPct), "sgstAmt" to b(v.sgstAmt), "igstPct" to b(v.igstPct), "igstAmt" to b(v.igstAmt),
        "other" to b(v.other), "notes" to v.notes,
    )

    private fun values(m: Map<String, Any?>) = ItemValues(
        sr = (m["sr"] as? Number)?.toInt(), article = m.str("article"), name = m.str("name"), brand = m.str("brand"),
        colour = m.str("colour"), sizeText = m.str("sizeText"), hsn = m.str("hsn"), qty = m.dec("qty"), mrp = m.dec("mrp"),
        rate = m.dec("rate"), gross = m.dec("gross"), discPct = m.dec("discPct"), discAmt = m.dec("discAmt"), taxable = m.dec("taxable"),
        cgstPct = m.dec("cgstPct"), cgstAmt = m.dec("cgstAmt"), sgstPct = m.dec("sgstPct"), sgstAmt = m.dec("sgstAmt"),
        igstPct = m.dec("igstPct"), igstAmt = m.dec("igstAmt"), other = m.dec("other"), notes = m.str("notes"),
    )

    private fun item(i: LineItem) = linkedMapOf(
        "id" to i.id, "kind" to i.kind.name, "original" to values(i.original), "current" to values(i.current),
        "needs" to i.needs.map { it.name }, "verified" to i.verified, "targetPct" to b(i.targetPct),
    )

    @Suppress("UNCHECKED_CAST")
    private fun item(m: Map<String, Any?>) = LineItem(
        id = m.str("id"), kind = runCatching { ItemKind.valueOf(m.str("kind")) }.getOrDefault(ItemKind.ARTICLE),
        original = values(m.obj("original") ?: emptyMap()), current = values(m.obj("current") ?: emptyMap()),
        needs = m.list("needs").mapNotNull { n -> runCatching { F.valueOf(n.toString()) }.getOrNull() }.toSet(),
        verified = m.bool("verified"), targetPct = m.dec("targetPct"),
    )
}
