package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord

/**
 * The PATEL TRADELINES tax invoice (G2/2627/1309, 20-Oct-2026) as OCR words with positions,
 * built to look like the phone photo: slightly tilted, two columns in the header,
 * HSN codes and some MRPs wrapping onto the next line, an item with two colours,
 * a freight line and an item without colour. Values are transcribed from the photo.
 */
object PatelInvoice {
    private const val CH = 11.0   // character width
    private const val H = 22      // text height
    private const val SLOPE = 0.012 // photo tilt (dy/dx)

    private class Builder {
        val words = mutableListOf<OcrWord>()
        /** Places [text] starting at x; spaces split it into OCR words. */
        fun at(x: Int, y: Int, text: String) {
            var cx = x.toDouble()
            for (part in text.split(' ')) {
                if (part.isEmpty()) { cx += CH; continue }
                val w = (part.length * CH).toInt()
                val yy = (y + SLOPE * cx).toInt()
                words += OcrWord(part, Box(cx.toInt(), yy, cx.toInt() + w, yy + H))
                cx += w + CH
            }
        }
    }

    // Column x positions (left edges) of the item table.
    private const val SR = 40; private const val DESC = 80; private const val HSN = 600; private const val QTY = 700
    private const val RATE = 770; private const val TOTAL = 870; private const val DP = 990; private const val DA = 1060
    private const val TAX = 1160; private const val CP = 1270; private const val CA = 1330; private const val SP = 1420; private const val SA = 1480

    data class Line(
        val sr: Int, val code: String, val mrp: String, val mrpOnNextLine: Boolean, val colours: List<String>,
        val hsn: String, val qty: String, val rate: String, val total: String, val dp: String, val da: String,
        val taxable: String, val cp: String, val ca: String, val sp: String, val sa: String,
    )

    val LINES = listOf(
        Line(1, "1180-Paralite-Gents-EVA-6X10", "235.00", false, listOf("BLK 6X8/1"), "64029990", "18", "170.12", "3062.16", "3.00", "91.86", "2970.30", "2.50", "74.26", "2.50", "74.26"),
        Line(2, "1210-Paralite-Gents-EVA-6X10", "169.50", false, listOf("Black 10/24"), "64022090", "24", "122.71", "2945.04", "3.00", "88.35", "2856.69", "2.50", "71.42", "2.50", "71.42"),
        Line(3, "1251-Paralite-Gents-EVA-6X10", "245.00", false, listOf("BlackRed 8/5,9/4,10/3"), "64029990", "12", "165.00", "1980.00", "3.00", "59.40", "1920.60", "2.50", "48.02", "2.50", "48.02"),
        Line(4, "1553-Paralite-Gents-EVA-6X10", "174.00", false, listOf("Black 6/2,7/3,8/3,9/2,10/2", "Grey 7/3,8/3,9/3,10/3"), "64022090", "24", "117.19", "2812.56", "3.00", "84.38", "2728.18", "2.50", "68.22", "2.50", "68.22"),
        Line(5, "1562-Paralite-Gents-EVA-6X10", "194.00", false, listOf("BLK 6X10/1"), "64022090", "18", "130.66", "2351.88", "3.00", "70.56", "2281.32", "2.50", "57.03", "2.50", "57.03"),
        Line(6, "6962-Vertex-Gents-PU-6X10", "255.00", true, listOf("RedBlack 6/2,7/3,8/3,9/2,10/2"), "64029990", "12", "171.73", "2060.76", "3.00", "61.82", "1998.94", "2.50", "49.97", "2.50", "49.97"),
        Line(7, "E6225-EEken-Gents-PU-6X10", "605.00", true, listOf("Black 6/2,7/3,8/3,9/2,10/2"), "64029990", "12", "374.50", "4494.00", "3.00", "134.82", "4359.18", "2.50", "108.98", "2.50", "108.98"),
        Line(8, "E6225-EEken-Gents-PU-6X10", "639.00", true, listOf("Tan 6/2,7/3,8/3,9/2,10/2"), "64029990", "12", "389.33", "4671.96", "3.00", "140.16", "4531.80", "2.50", "113.28", "2.50", "113.28"),
        Line(9, "E6227-eeken-Gents-PU-6X10", "555.00", true, listOf("Brown 7/1"), "64029990", "1", "343.55", "343.55", "3.00", "10.31", "333.24", "2.50", "8.33", "2.50", "8.33"),
        Line(10, "FREIGHT-NA-FREIGHT-NA-0X0", "446.00", false, listOf("MIX 0/1"), "64029990", "1", "446.00", "446.00", "3.00", "13.38", "432.62", "2.50", "10.82", "2.50", "10.82"),
        Line(11, "S11500-Stimulus-Ladies-PU-4X8", "579.00", false, emptyList(), "64029990", "1", "358.41", "358.41", "3.00", "10.75", "347.66", "2.50", "8.69", "2.50", "8.69"),
    )

    /** [blankDiscountOf]: serial numbers whose printed discount % is unreadable (left out). */
    fun page(blankDiscountOf: Set<Int> = emptySet()): OcrPage {
        val b = Builder()
        b.at(60, 20, "Paragon"); b.at(740, 30, "TAX INVOICE")
        b.at(40, 80, "PATEL TRADELINES"); b.at(920, 80, "Invoice No. :"); b.at(1110, 80, "G2/2627/1309")
        b.at(40, 115, "Distributors for PARAGON RUBBER INDUSTRIES"); b.at(920, 115, "Invoice Date"); b.at(1110, 115, "20-Oct-2026")
        b.at(40, 145, "GSTIN : 27AHTPP0412H1Z9 PAN No. : AHTPP0412H"); b.at(920, 150, "Order No.& Qty : PTHO/2627/6772")
        b.at(40, 175, "CIN : U74999PN2014PTC153082")
        b.at(40, 210, "GAT NO 735/3/1, 735/3/2, Kurduwadi Road, Dhage Mala, Barshi,"); b.at(920, 200, "No of Cartons : 0.00C - 1 Bundle(0.00)")
        b.at(40, 238, "Solapur, Maharashtra, 413401-413411 Contact No.:02184796594"); b.at(920, 240, "Transporter Details :SELF")
        b.at(1110, 290, "1")
        b.at(40, 320, "Consignee & Delivery Address.:"); b.at(920, 322, "Packed By : WASIM")
        b.at(40, 355, "SHIVTEJ FOOTWEAR AKLUJ"); b.at(920, 358, "Packing/Cartoon : (134)")
        b.at(40, 385, "OPP. SHIVSHANKER BAZAR, AKLUJ")
        b.at(40, 460, "Mobile : 8668369187 Contact No : 7775878677")
        b.at(40, 490, "GSTIN : 27ECIPS0222J1ZI PAN No:ECIPS0222J"); b.at(920, 495, "All amounts and rates mentioned are in Indian rupees(INR)")
        // Table header (3 lines).
        b.at(SR, 525, "Sr"); b.at(160, 525, "Description Of Goods"); b.at(HSN, 525, "HSN"); b.at(QTY, 525, "Qty"); b.at(RATE, 528, "Rate")
        b.at(TOTAL + 20, 535, "Total"); b.at(DP + 20, 535, "Discount"); b.at(TAX, 538, "Taxable"); b.at(CP + 20, 540, "CGST"); b.at(SP + 20, 540, "SGST")
        b.at(SR, 550, "No."); b.at(HSN, 552, "Code"); b.at(QTY, 552, "in"); b.at(RATE, 553, "/Pair(₹)"); b.at(TAX + 10, 560, "Value")
        b.at(QTY, 575, "Pairs"); b.at(DP + 10, 568, "%"); b.at(DA + 10, 568, "Amount"); b.at(CP + 5, 572, "%"); b.at(CA + 10, 572, "Amount")
        b.at(SP + 5, 572, "%"); b.at(SA + 10, 572, "Amount")
        var y = 610
        for (l in LINES) {
            b.at(SR, y, "${l.sr}")
            b.at(DESC, y, if (l.mrpOnNextLine) "${l.code} MRP :" else l.code)
            b.at(HSN, y, l.hsn.take(5)); b.at(QTY, y, l.qty); b.at(RATE, y, l.rate); b.at(TOTAL, y, l.total)
            if (l.sr !in blankDiscountOf) b.at(DP, y, l.dp)
            b.at(DA, y, l.da); b.at(TAX, y, l.taxable); b.at(CP, y, l.cp); b.at(CA, y, l.ca); b.at(SP, y, l.sp); b.at(SA, y, l.sa)
            y += 24
            b.at(DESC, y, if (l.mrpOnNextLine) l.mrp else "MRP : ${l.mrp}"); b.at(HSN, y, l.hsn.drop(5))
            for (c in l.colours) {
                y += 24
                val (colour, sizes) = c.split(' ', limit = 2).let { it[0] to it[1] }
                b.at(DESC, y, colour); b.at(DESC + 380 - (sizes.length * CH).toInt(), y, sizes)
            }
            y += 34
        }
        b.at(1340, y + 10, "26012.00")
        b.at(1360, y + 40, "E&OE")
        b.at(40, y + 60, "Our Bankers :")
        b.at(40, y + 90, "Bank Name:- Federal Bank")
        b.at(40, y + 260, "Terms & Conditions :")
        b.at(40, y + 300, "Page 1 of 2"); b.at(1000, y + 300, "For PATEL TRADELINES")
        // OCR returns words in its own block order, not reading order.
        return OcrPage(1600, y + 400, b.words.shuffled(java.util.Random(7)))
    }

    /** Page 2 of 2: only the end of line 11 ("Golden 5/1"), the table total, tax, round off and the bill total. */
    fun page2(): OcrPage {
        val b = Builder()
        b.at(SR, 25, "Sr"); b.at(160, 25, "Description Of Goods"); b.at(HSN, 25, "HSN"); b.at(QTY, 25, "Qty"); b.at(RATE, 28, "Rate")
        b.at(TOTAL + 20, 35, "Total"); b.at(DP + 20, 35, "Discount"); b.at(TAX, 38, "Taxable"); b.at(CP + 20, 40, "CGST"); b.at(SP + 20, 40, "SGST")
        b.at(SR, 50, "No."); b.at(HSN, 52, "Code"); b.at(QTY, 52, "in"); b.at(RATE, 53, "/Pair(₹)"); b.at(TAX + 10, 60, "Value")
        b.at(QTY, 75, "Pairs"); b.at(DP + 10, 68, "%"); b.at(DA + 10, 68, "Amount"); b.at(CP + 5, 72, "%"); b.at(CA + 10, 72, "Amount")
        b.at(SP + 5, 72, "%"); b.at(SA + 10, 72, "Amount")
        b.at(DESC, 110, "Golden"); b.at(DESC + 380 - 33, 110, "5/1")
        b.at(DESC + 20, 150, "Total"); b.at(QTY, 150, "134"); b.at(TOTAL, 150, "25526.32"); b.at(DA, 150, "765.79"); b.at(TAX, 150, "24760.53")
        b.at(CA, 150, "619.02"); b.at(SA, 150, "619.02")
        b.at(CA, 185, "ROff"); b.at(SA, 185, "13.43")
        b.at(40, 1300, "Amount in words : Rs.Twenty Six Thousand Twelve Only"); b.at(1300, 1300, "₹"); b.at(1340, 1300, "26012.00")
        b.at(40, 1340, "Our Bankers :"); b.at(40, 1370, "Bank Name:- Federal Bank"); b.at(40, 1400, "A/C No:-16365500001481")
        b.at(40, 1430, "Branch & IFS Code:- Barshi & FDRL0001636")
        b.at(40, 1520, "Page 2 of 2"); b.at(1000, 1520, "For PATEL TRADELINES")
        return OcrPage(1600, 1700, b.words.shuffled(java.util.Random(8)))
    }
}
