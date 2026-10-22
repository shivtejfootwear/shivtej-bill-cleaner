package com.shivtej.billscanner.core.model

import java.math.BigDecimal

/** A line on the supplier invoice: a footwear article, or a charge such as freight. */
enum class ItemKind { ARTICLE, CHARGE }

/** Fields of a line item that can be flagged for checking. */
enum class F {
    SR, ARTICLE, NAME, COLOUR, SIZE, HSN, QTY, MRP, RATE, GROSS, DISC_PCT, DISC_AMT, TAXABLE,
    CGST_PCT, CGST_AMT, SGST_PCT, SGST_AMT, IGST_PCT, IGST_AMT, OTHER,
    /** GST % printed on the line (tax itself charged at the bottom of the bill). */
    GST_PCT,
    /** Line amount including tax, when the bill prints one. */
    TOTAL,
}

/**
 * Values of one line exactly as one column set. `null` = not found on the bill.
 * Money is BigDecimal (never floating point).
 */
data class ItemValues(
    val sr: Int? = null,
    val article: String = "",
    val name: String = "",
    val brand: String = "",
    val colour: String = "",
    /** Size assortment text exactly as printed, e.g. "Black 6/2,7/3 | Grey 7/3". */
    val sizeText: String = "",
    val hsn: String = "",
    val qty: BigDecimal? = null,
    val mrp: BigDecimal? = null,
    /** Rate per pair as printed (before the bill discount, before tax). */
    val rate: BigDecimal? = null,
    /** "Total" column: qty x rate. */
    val gross: BigDecimal? = null,
    /** Discount % printed by the supplier for this line. */
    val discPct: BigDecimal? = null,
    val discAmt: BigDecimal? = null,
    val taxable: BigDecimal? = null,
    val cgstPct: BigDecimal? = null,
    val cgstAmt: BigDecimal? = null,
    val sgstPct: BigDecimal? = null,
    val sgstAmt: BigDecimal? = null,
    val igstPct: BigDecimal? = null,
    val igstAmt: BigDecimal? = null,
    val other: BigDecimal? = null,
    val notes: String = "",
    /** GST % printed on this line, for bills that charge the tax at the bottom. */
    val gstPct: BigDecimal? = null,
    /** Line amount including tax, when printed. */
    val printedTotal: BigDecimal? = null,
)

data class LineItem(
    val id: String,
    val kind: ItemKind,
    /** What the scan read. Never changed by editing. */
    val original: ItemValues,
    /** What is shown and exported; starts equal to [original]. */
    val current: ItemValues,
    /** Fields the scan could not read reliably. */
    val needs: Set<F> = emptySet(),
    val verified: Boolean = false,
    /** Target discount % against MRP for the adjusted sheet (null = company / invoice target). */
    val targetPct: BigDecimal? = null,
    /** Every cell of this line exactly as printed, in the order of [Invoice.columns]. */
    val raw: List<String> = emptyList(),
) {
    val edited: Set<F> get() = Calc.diff(original, current)
}

/** Invoice header. `null` = not found. */
data class Header(
    val supplierName: String? = null,
    val supplierAddress: String? = null,
    val supplierGstin: String? = null,
    val supplierContact: String? = null,
    val invoiceNo: String? = null,
    val invoiceDate: String? = null,
    val orderNo: String? = null,
    val transporter: String? = null,
    val cartons: String? = null,
    val packing: String? = null,
    val buyerName: String? = null,
    val buyerGstin: String? = null,
    val printedGrandTotal: BigDecimal? = null,
    val overallDiscount: BigDecimal? = null,
    val otherCharges: BigDecimal? = null,
    val roundOff: BigDecimal? = null,
    val pageNote: String? = null,
    /** Tax charged at the bottom of the bill ("Output CGST @2.5%  2,555.93"). */
    val cgstPct: BigDecimal? = null,
    val cgstAmt: BigDecimal? = null,
    val sgstPct: BigDecimal? = null,
    val sgstAmt: BigDecimal? = null,
    val igstPct: BigDecimal? = null,
    val igstAmt: BigDecimal? = null,
    /** "Total" row of the item table as printed: pairs and amount. */
    val printedQty: BigDecimal? = null,
    val printedSubtotal: BigDecimal? = null,
)

/** One column of the bill's item table, as printed, and what it means (null = kept as text only). */
data class Column(val label: String, val field: F?)

/** One edit made by the person, kept so it can be reviewed or undone. */
data class Change(val at: Long, val itemId: String?, val field: String, val from: String, val to: String)

enum class InvoiceStatus { DRAFT, VERIFIED }

data class Invoice(
    val id: String,
    val createdAt: Long,
    val headerOriginal: Header,
    val header: Header,
    val items: List<LineItem>,
    /** Saved original photos (file paths inside the app). */
    val pages: List<String> = emptyList(),
    val status: InvoiceStatus = InvoiceStatus.DRAFT,
    /** Default target discount % against MRP for the adjusted sheet. */
    val defaultTargetPct: BigDecimal? = null,
    val changes: List<Change> = emptyList(),
    val exportedAt: Long? = null,
    /** The item table's columns exactly as printed on the bill. */
    val columns: List<Column> = emptyList(),
    /** Target discount % against MRP per company / brand group (see [Groups]). */
    val groupTargets: Map<String, BigDecimal> = emptyMap(),
    /** Whether "बैठा %" counts GST in the landed cost (true = MRP vs rate after discount + GST). */
    val sittingWithGst: Boolean = true,
)
