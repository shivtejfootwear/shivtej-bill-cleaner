package com.shivtej.billscanner.core.model

import java.math.BigDecimal

/** A note for the person: never an automatic change. */
data class Warning(val level: Level, val itemId: String?, val hindi: String, val english: String) {
    enum class Level { ERROR, CHECK, TIP }
}

/**
 * Checks on a scanned invoice. [previous] = earlier invoices on this phone,
 * used for duplicate bill numbers and rate comparisons.
 */
object Warnings {
    fun of(inv: Invoice, previous: List<Invoice> = emptyList()): List<Warning> {
        val out = mutableListOf<Warning>()
        val h = inv.header
        val t = Totals.of(inv)

        // Whole invoice.
        if (h.invoiceNo != null && previous.any { it.id != inv.id && it.header.invoiceNo == h.invoiceNo && it.header.supplierGstin == h.supplierGstin }) {
            out += Warning(Warning.Level.ERROR, null, "बिल नंबर ${h.invoiceNo} पहले भी स्कैन हो चुका है — कहीं दोबारा तो नहीं जोड़ रहे?",
                "Invoice ${h.invoiceNo} was scanned before.")
        }
        t.difference?.let { d ->
            // The bill's own total is higher by about one line's discount (Patel: Freight 426 − 3% = 12.78 never taken off the total).
            val disc = if (d.abs() > BigDecimal("0.99")) inv.items.firstOrNull { i -> i.current.discAmt?.let { a -> (a - d.abs()).abs() <= BigDecimal("0.50") } == true } else null
            if (disc != null) out += Warning(Warning.Level.CHECK, null,
                "सप्लायर के बिल का Total लाइनों से ₹${Calc.fmt(d)} ज़्यादा है — यह \"${disc.current.article.ifBlank { disc.current.name }}\" के डिस्काउंट ₹${Calc.fmt(disc.current.discAmt)} जितना है। लाइन में डिस्काउंट दिखाया पर Total में घटाया नहीं — app का पढ़ना सही है, सप्लायर से पूछें।",
                "The bill total is ₹${Calc.fmt(d)} above its lines — about the discount ₹${Calc.fmt(disc.current.discAmt)} shown on \"${disc.current.article}\" but not taken off the total.")
            else if (d.abs() > BigDecimal("0.99")) out += Warning(Warning.Level.CHECK, null,
                "लाइनों का जोड़ ₹${Calc.fmt(t.grand)}, पर बिल पर कुल ₹${Calc.fmt(t.printedGrand)} छपा है — फ़र्क़ ₹${Calc.fmt(d)}। कोई और चार्ज, round-off या दूसरा पेज तो नहीं?",
                "Lines add up to ₹${Calc.fmt(t.grand)} but the bill total is ₹${Calc.fmt(t.printedGrand)} (difference ₹${Calc.fmt(d)}).")
            else if (d.signum() != 0) out += Warning(Warning.Level.TIP, null, "कुल में ₹${Calc.fmt(d)} का round-off फ़र्क़ है।", "Round-off difference ₹${Calc.fmt(d)}.")
        }
        if (h.printedGrandTotal == null) out += Warning(Warning.Level.CHECK, null, "बिल का कुल (Grand Total) नहीं पढ़ा गया — हाथ से डालें।", "Grand total not found.")
        Regex("""\((\d+)\)""").find(h.packing.orEmpty())?.groupValues?.get(1)?.toBigDecimalOrNull()?.let { packed ->
            if (packed.compareTo(t.qty) != 0) out += Warning(Warning.Level.CHECK, null,
                "पैकिंग में $packed लिखा है, पर आर्टिकल की कुल जोड़ी ${t.qty.toPlainString()} है।", "Packing says $packed, items total ${t.qty.toPlainString()} pairs.")
        }
        if (h.pageNote != null) Regex("""(\d+)\s*of\s*(\d+)""", RegexOption.IGNORE_CASE).find(h.pageNote)?.let { m ->
            val (p, n) = m.destructured
            if (p.toInt() < n.toInt() && inv.pages.size < n.toInt()) out += Warning(Warning.Level.CHECK, null,
                "बिल पर \"${h.pageNote}\" लिखा है — बाकी पेज भी जोड़ें।", "The bill says ${h.pageNote}; add the remaining page(s).")
        }

        if (h.pageNote == "continued") out += Warning(Warning.Level.CHECK, null,
            "बिल पर \"continued\" लिखा है — अगला पेज भी जोड़ें।", "The bill continues on another page; add it.")
        h.printedQty?.let { pq -> if (pq.compareTo(t.qty) != 0) out += Warning(Warning.Level.CHECK, null,
            "बिल के Total में ${pq.toPlainString()} जोड़ी छपी है, पर आर्टिकल की जोड़ी ${t.qty.toPlainString()} है — कोई लाइन छूटी तो नहीं?",
            "The bill total says ${pq.toPlainString()} pairs; lines add up to ${t.qty.toPlainString()}.") }
        h.printedSubtotal?.let { ps -> if ((ps - t.taxable).abs() > BigDecimal("0.99")) out += Warning(Warning.Level.CHECK, null,
            "बिल के Total में ₹${Calc.fmt(ps)} छपा है, पर लाइनों का जोड़ ₹${Calc.fmt(t.taxable)} है — फ़र्क़ ₹${Calc.fmt(ps - t.taxable)}।",
            "Table total ₹${Calc.fmt(ps)} vs lines ₹${Calc.fmt(t.taxable)}.") }

        // Lines.
        val seen = HashMap<String, String>()
        for (it in inv.items) {
            val v = it.current
            val name = "${v.sr ?: ""} ${v.article}".trim()
            if (F.DISC_PCT in it.needs && v.discPct == null) out += Warning(Warning.Level.CHECK, it.id,
                "$name: बिल डिस्काउंट % साफ़ नहीं पढ़ा गया — Needs Verification", "$name: bill discount % needs verification")
            for (c in Calc.checkLine(v)) out += Warning(Warning.Level.CHECK, it.id,
                "$name: ${hindi(c.field)} बिल पर ${Calc.fmt(c.printed)} है, हिसाब से ${Calc.fmt(c.expected)} होना चाहिए",
                "$name: ${c.field} printed ${Calc.fmt(c.printed)}, expected ${Calc.fmt(c.expected)}")
            if (it.kind != ItemKind.ARTICLE) continue
            if (v.mrp != null && v.rate != null && v.rate > v.mrp) out += Warning(Warning.Level.CHECK, it.id,
                "$name: रेट ₹${Calc.fmt(v.rate)} MRP ₹${Calc.fmt(v.mrp)} से ज़्यादा है — अजीब लग रहा है", "$name: rate above MRP")
            // MRP 0.00 printed (goods billed by rate only) is not asked about.
            if (v.mrp == null && (F.MRP in it.needs || inv.columns.isEmpty())) out += Warning(Warning.Level.CHECK, it.id, "$name: MRP नहीं मिला", "$name: MRP not found")
            else if (v.mrp != null && v.taxable != null && v.qty != null && Calc.sittingPct(v, h, inv.sittingWithGst) == null) out += Warning(Warning.Level.CHECK, it.id,
                "$name: MRP ₹${Calc.fmt(v.mrp)} और रकम ₹${Calc.fmt(v.taxable)} आपस में नहीं बैठते — कोई नंबर गलत पढ़ा गया, जाँचें", "$name: MRP and amount do not fit — a number was misread")
            // Colour is not needed for the bill: never asked about.
            // Size counts are checked only where the bill prints them per size ("6/2,7/3"); "6X10" alone is a size range.
            if (v.sizeText.contains('/')) {
                val s = Sizes.read(v.sizeText, v.qty)
                // "6X10/1", "BRN 8X10/1 OLV 7X10/1" (Paragon): a box of a size range, pairs per size not printed — nothing to check.
                val onlyRanges = Regex("""\d{1,2}[Xx×]\d{1,2}/\d""").containsMatchIn(v.sizeText) && !Regex("""(?<![Xx×\d])\d{1,2}(\.\d)?/\d""").containsMatchIn(v.sizeText)
                if (s.state == Sizes.State.MISMATCH || s.state == Sizes.State.UNCLEAR) out += Warning(if (onlyRanges && s.state == Sizes.State.UNCLEAR) Warning.Level.TIP else Warning.Level.CHECK, it.id,
                    "$name: ${if (onlyRanges) "साइज़ सेट (${v.sizeText}) — बिल में साइज़-वार गिनती नहीं छपी" else s.hindi}", "$name: size quantities need checking")
            } else if (v.sizeText.isEmpty() && inv.columns.none { c -> c.field == F.SIZE }) out += Warning(Warning.Level.TIP, it.id, "$name: साइज़ नहीं छपे", "$name: sizes missing")
            val key = "${v.article}|${v.colour}|${v.sizeText}|${v.mrp}"
            seen[key]?.let { other -> out += Warning(Warning.Level.TIP, it.id, "$name: यही आर्टिकल, कलर और MRP लाइन $other में भी है", "$name: same article/colour/MRP as line $other") }
            seen.putIfAbsent(key, name)
            // Same article from the same supplier at a different rate before.
            val before = previous.filter { p -> p.id != inv.id && p.header.supplierGstin == h.supplierGstin }
                .flatMap { p -> p.items.map { i -> p to i.current } }
                .filter { (_, o) -> o.article == v.article && o.mrp?.compareTo(v.mrp) == 0 && o.rate != null }
                .maxByOrNull { (p, _) -> p.createdAt }
            if (before != null && v.rate != null && before.second.rate!!.compareTo(v.rate) != 0) out += Warning(Warning.Level.TIP, it.id,
                "$name: पिछली बार रेट ₹${Calc.fmt(before.second.rate)} था (बिल ${before.first.header.invoiceNo ?: ""}), अब ₹${Calc.fmt(v.rate)}",
                "$name: rate was ₹${Calc.fmt(before.second.rate)} last time, now ₹${Calc.fmt(v.rate)}")
        }
        if (inv.status != InvoiceStatus.VERIFIED && out.none { it.level != Warning.Level.TIP }) out += Warning(Warning.Level.TIP, null,
            "सब ठीक लग रहा है। जाँचकर \"Verified\" करें, फिर चाहें तो साइज़-वार स्टॉक फ़ाइल बनाएँ।", "Everything looks fine — verify, then export stock if you want.")
        return out
    }

    fun hindi(f: F): String = when (f) {
        F.RATE_GST -> "रेट (GST सहित)"; F.SR -> "क्रम"; F.ARTICLE -> "आर्टिकल"; F.NAME -> "नाम"; F.COLOUR -> "कलर"; F.SIZE -> "साइज़"; F.HSN -> "HSN"
        F.QTY -> "मात्रा"; F.MRP -> "MRP"; F.RATE -> "रेट"; F.GROSS -> "Total (मात्रा × रेट)"; F.DISC_PCT -> "बिल डिस्काउंट %"
        F.DISC_AMT -> "डिस्काउंट ₹"; F.TAXABLE -> "Taxable"; F.CGST_PCT -> "CGST %"; F.CGST_AMT -> "CGST ₹"
        F.SGST_PCT -> "SGST %"; F.SGST_AMT -> "SGST ₹"; F.IGST_PCT -> "IGST %"; F.IGST_AMT -> "IGST ₹"; F.OTHER -> "दूसरे चार्ज"
        F.GST_PCT -> "GST %"; F.TOTAL -> "लाइन का कुल"
    }
}
