package com.shivtej.billscanner

import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord

/**
 * The DHARASHIV FOOTWEAR PVT LTD tax invoice R-2395 (27-Jul-26, 2 pages) as OCR words with positions.
 * A different layout from PATEL: Sr | Item Description | HSN | GST% | Size | MRP | Qty | Rate | Disc. | Amount,
 * with GST charged once at the bottom of page 2. Values are transcribed from the photos
 * (they add up to the printed 212 pairs and ₹1,02,237.72). The page is warped like a phone photo
 * of a bent sheet: tilted, and more tilted towards the bottom.
 */
object DharashivInvoice {
    data class L(val sr: Int, val desc: String, val size: String, val mrp: String, val qty: Int, val rate: String, val disc: String, val amount: String)

    val LINES = listOf(
        L(1, "SM 651 N.BLUE GREY", "6X10", "1,049", 6, "720.00", "9.82", "3,895.78"),
        L(2, "SM 1120 D.GREY/LT.GREY", "6X10", "1,099", 5, "754.29", "9.82", "3,401.09"),
        L(3, "SM 1067 D.GREY/LT.GREY", "7X10", "1,049", 6, "720.00", "9.82", "3,895.78"),
        L(4, "SM 1105 D.GREY/LT.GREY", "6X10", "1,249", 1, "857.14", "9.82", "772.97"),
        L(5, "SM 1105 D.GREY/ LT.GREY", "7X10", "1,299", 4, "891.43", "9.82", "3,215.57"),
        L(6, "SM 722 GREY NEON ORANGE", "7X10", "1,399", 6, "960.00", "9.82", "5,194.37"),
        L(7, "SM 766 N.BLUE/ B.GRAY", "7X10", "1,049", 1, "720.00", "9.82", "649.30"),
        L(8, "SM 766 N.BLUE B.GREY", "7X10", "1,099", 5, "754.29", "9.82", "3,401.09"),
        L(9, "SM 472 GREY N ORANGE", "11X12", "1,699", 4, "1,165.71", "9.82", "4,204.95"),
        L(10, "SM 472 GREY NEON ORANGE", "7X10", "1,549", 2, "1,062.86", "9.82", "1,916.97"),
        L(11, "SM 472 BLACK RED", "7X10", "1,549", 1, "1,062.86", "9.82", "958.49"),
        L(12, "SM 472 BLACK RED", "6X10", "1,599", 2, "1,097.14", "9.82", "1,978.80"),
        L(13, "SM 472 BLACK RED", "7X10", "1,699", 1, "1,165.71", "9.82", "1,051.24"),
        L(14, "SFG 48 CAMEL F.ORANGE", "6X10", "549", 2, "377.14", "9.82", "680.21"),
        L(15, "SFG 48 CAMAL F ORANGE", "7X10.", "599", 4, "411.43", "9.82", "1,484.11"),
        L(16, "SFG 48 BLACK RED", "7X10", "599", 14, "411.43", "9.82", "5,194.39"),
        L(17, "SFG 192 BLACK SILVER", "7X10", "449.50", 5, "308.57", "9.82", "1,391.34"),
        L(18, "SFG 57 D.GREY F.ORANGE", "6X10", "999", 9, "685.71", "9.82", "5,565.36"),
        L(19, "SFG 57 BROWN/TAN", "7X10", "999", 4, "685.71", "9.82", "2,473.49"),
        L(20, "SFG 57 BLK YELLOW", "6X10", "999", 2, "685.71", "9.82", "1,236.75"),
        L(21, "SFG 143 BLACK WHITE", "6X10", "499.50", 1, "342.86", "9.82", "309.19"),
        L(22, "SFG 4049 GREY BLACK", "8X10", "499.50", 6, "342.86", "9.82", "1,855.15"),
        L(23, "SFG 4049 OLIVE BEIGE", "6X10", "449.50", 2, "308.57", "9.82", "556.54"),
        L(24, "SFG 183 CAMEL BROWN", "6X10", "949", 1, "651.43", "9.82", "587.46"),
        L(25, "SFG 183 CAMAL BROWN", "6X10", "999", 4, "685.71", "9.82", "2,473.49"),
        L(26, "SFG 196 FT.GREEN CAMEL", "7X10", "599", 5, "411.43", "9.82", "1,855.14"),
        L(27, "SFG 241 D.GREY NEON GREEN", "8X10", "549", 6, "377.14", "9.82", "2,040.63"),
        L(28, "SFG 241 BLACK M. ORANGE", "7X10", "549.50", 8, "377.14", "9.82", "2,720.84"),
        L(29, "SS 101 BLACK", "6X10", "825", 9, "565.71", "9.82", "4,591.42"),
        L(30, "SL 309 LT. GREY PINK", "5X8", "999", 2, "685.71", "9.82", "1,236.75"),
        L(31, "SFL 2115 BLK WHT", "5X8", "399.50", 6, "274.29", "9.82", "1,484.13"),
        L(32, "SFG 48 BLACK RED", "11X12", "649", 4, "445.71", "9.82", "1,607.77"),
        L(33, "SS 101 -BLACK", "11X1", "649", 6, "445.71", "9.82", "2,411.65"),
        L(34, "SFG 240 BROWN CAMEL", "6X10", "549", 6, "377.14", "9.82", "2,040.63"),
        L(35, "SFG 234 CAMEL BROWN", "7X10", "599", 5, "411.43", "9.82", "1,855.14"),
        L(36, "WLR 70414 MAUVE", "5X8", "429", 6, "286.67", "7.45", "1,591.88"),
        L(37, "SFG 241 BROWN BEIGN", "7X10", "499.50", 8, "342.86", "9.82", "2,473.53"),
        L(38, "SM 1090 N.BLUE/LT.GREY", "9X10", "849", 2, "582.86", "9.82", "1,051.25"),
        L(39, "SS 703 BLACK RED", "7X10", "649", 1, "445.71", "9.82", "401.94"),
        L(40, "SS 703 BLACK RED", "7X10", "599", 2, "411.43", "9.82", "742.06"),
        L(41, "SS 727 BROWN TAN", "7X10", "749", 6, "514.29", "9.82", "2,782.72"),
        L(42, "SS 481 BLACK BEIGE", "7X10", "999", 2, "685.71", "9.82", "1,236.75"),
        L(43, "SM 722 BLACK GOLD", "7X10", "1,299", 2, "891.43", "9.82", "1,607.78"),
        L(44, "SS 728 BLACK RED", "7X10", "675", 6, "462.86", "9.82", "2,504.44"),
        L(45, "SS 729 TAN BLACK", "7X10", "675", 6, "462.86", "9.82", "2,504.44"),
        L(46, "SS 727 FT. GREEN PISTA", "6X10", "749", 6, "514.29", "9.82", "2,782.72"),
        L(47, "SL 309 LT. GREY PINK", "5X8", "999", 1, "685.71", "9.82", "618.37"),
        L(48, "FL 366 BEIGN MAROON", "5X8", "164.50", 1, "113.33", "9.82", "102.20"),
        L(49, "SFG 57 BROWN/TAN", "7X10", "899.50", 1, "617.14", "9.82", "556.54"),
        L(50, "FL 532 PISTA", "5X7", "219.50", 1, "151.43", "9.82", "136.56"),
    )
    val ASIAN = L(51, "ASIAN SAFARI-15 BLACK-GREY", "7X10", "279", 6, "265.71", "40", "956.56")

    private class Builder(val bend: Double) {
        val words = mutableListOf<OcrWord>()
        private fun warp(x: Double, y: Double): Pair<Double, Double> =
            (x - 0.03 * y) to (y + 0.03 * x + bend * x * y)

        /** Places [text] starting at x; spaces split it into OCR words. */
        fun at(x: Int, y: Int, text: String, h: Int = 20) {
            val ch = h * 0.55
            var cx = x.toDouble()
            for (part in text.split(' ')) {
                if (part.isEmpty()) { cx += ch; continue }
                val w = part.length * ch
                val corners = listOf(warp(cx, y.toDouble()), warp(cx + w, y.toDouble()), warp(cx, y + h.toDouble()), warp(cx + w, y + h.toDouble()))
                words += OcrWord(part, Box(corners.minOf { it.first }.toInt(), corners.minOf { it.second }.toInt(), corners.maxOf { it.first }.toInt(), corners.maxOf { it.second }.toInt()))
                cx += w + ch
            }
        }
        /** Right-aligned at xRight. */
        fun right(xRight: Int, y: Int, text: String, h: Int = 20) = at((xRight - text.length * h * 0.55).toInt(), y, text, h)
    }

    private fun top(b: Builder, pageTitle: String) {
        b.at(700, 20, pageTitle, 24)
        b.at(40, 60, "Subject to Osmanabad Jurisdiction."); b.at(760, 60, "Tax Invoice")
        b.at(790, 100, "Tax Invoice")
        b.at(470, 132, "DHARASHIV FOOTWEAR PVT LTD", 34)
        b.at(520, 185, "B-6, MIDC, OSMANABAD GSTIN/UIN: 27AAFCD3805J1ZO")
        b.at(380, 212, "Email: dharashivfootwear25@gmail.com,Mobile No:9527671319,9604282892,8459624627")
        b.at(820, 238, "GSTIN/UIN")
        b.at(40, 262, "Buyer : Shivtej Footwear..........Akluj"); b.at(660, 262, "State Name : Maharashtra, Code : 27")
        b.at(40, 292, "Address : C.S.N 2260,"); b.at(920, 292, "Invoice No : R-2395"); b.at(1350, 292, "Date : 27-Jul-26")
        b.at(40, 320, "Place of Supply: Maharashtra Code: 27"); b.at(920, 320, "DC No : 2889"); b.at(1350, 320, "Date : 27-Jul-26")
        b.at(40, 348, "Party GST No: 27ECIPS0222J1ZI"); b.at(920, 348, "Eway Bill No:"); b.at(1350, 348, "Date : 27-Jul-26")
        b.at(920, 376, "Pay Mode: CREDIT"); b.at(1450, 376, "Due On : 27-Jul-26")
        b.at(30, 410, "Sr.No"); b.at(200, 410, "Item Description"); b.at(880, 410, "HSN"); b.at(985, 410, "GST%"); b.at(1080, 410, "Size")
        b.at(1170, 410, "MRP"); b.at(1275, 410, "Qty"); b.at(1390, 410, "Rate"); b.at(1480, 410, "Disc."); b.at(1640, 410, "Amount")
    }

    private fun line(b: Builder, y: Int, l: L, blankDisc: Boolean) {
        b.at(30, y, "${l.sr}."); b.at(90, y, l.desc); b.at(860, y, "64029990"); b.at(985, y, "5 %"); b.at(1065, y, l.size)
        b.right(1235, y, l.mrp); b.at(1255, y, "${l.qty} pair"); b.right(1450, y, l.rate)
        if (!blankDisc) b.right(1550, y, "${l.disc} %")
        b.right(1760, y, l.amount)
    }

    /** Page 1: lines 1–50, "continued ...". [blankDiscOf]: serial numbers whose Disc. % is unreadable. */
    fun page1(blankDiscOf: Set<Int> = emptySet(), bend: Double = 0.000012): OcrPage {
        val b = Builder(bend)
        top(b, "TAX INVOICE")
        var y = 445
        for (l in LINES) { line(b, y, l, l.sr in blankDiscOf); y += 31 }
        b.at(1500, y + 40, "continued ...")
        return OcrPage(1800, y + 200, b.words.shuffled(java.util.Random(11)))
    }

    /** Page 2: line 51 (ASIAN), table total, GST at the bottom, HSN summary. */
    fun page2(bend: Double = 0.000012): OcrPage {
        val b = Builder(bend)
        top(b, "Tax Invoice(Page 2)")
        line(b, 445, ASIAN, false)
        val y = 1300
        b.at(1150, y, "Total"); b.at(1215, y, "0"); b.at(1255, y, "212 pair"); b.right(1760, y, "1,02,237.72")
        b.at(1180, y + 35, "Output CGST @2.5% 2.50 %"); b.right(1760, y + 35, "2,555.93")
        b.at(1180, y + 62, "Output SGST @2.5% 2.50 %"); b.right(1760, y + 62, "2,555.93")
        b.at(1400, y + 89, "Round Off"); b.right(1760, y + 89, "0.42")
        b.at(1560, y + 118, "₹"); b.right(1760, y + 118, "1,07,350.00", 24)
        b.at(40, y + 160, "Amount Rs.Inwords: INR One Lakh Seven Thousand Three Hundred Fifty Only.")
        b.at(900, y + 190, "HSN/SAC Taxable Value Rate Amount Rate Amount Tax Amount")
        b.at(40, y + 200, "Company's Bank Details")
        b.at(900, y + 222, "64029990 1,02,237.72 2.50% 2,555.93 2.50% 2,555.93 5,111.86")
        b.at(40, y + 230, "Bank Name : ICICI Bank 163705016263")
        b.at(900, y + 250, "Total 1,02,237.72 2,555.93 2,555.93 5,111.86")
        b.at(40, y + 260, "Branch & IFS Code : Osmanabad ,ICIC0001637")
        return OcrPage(1800, y + 400, b.words.shuffled(java.util.Random(12)))
    }
}
