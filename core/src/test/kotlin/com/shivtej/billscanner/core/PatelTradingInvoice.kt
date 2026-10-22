package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord

/**
 * PATEL TRADING CORPORATION, Sales (LT) 26-27/ALT-496, 29-Sep-2026 (EBase billing software), 2 pages.
 * Sr | Description | HSN/SAC | Quantity | MRP | Rate | Net Rate | Disc (per) | Disc.(%) | Disc. Amt. | GST | Amount.
 * Rate includes GST, Net Rate = Rate ÷ 1.05, Amount = pairs × Rate (GST included). Many lines print MRP 0.00
 * (non-brand goods billed by rate only). Discount columns are empty. Each page ends with a total row of numbers only
 * (running total), then "Items: 33"; page 2 has the HSN/tax summary table. Values transcribed from the photos.
 */
object PatelTradingInvoice {
    data class L(val sr: Int, val desc: String, val qty: Int, val mrp: String, val rate: String, val net: String, val amount: String)

    val LINES = listOf(
        L(1, "Primo Basket Ball S Grn 5/7", 6, "0.00", "107.00", "101.90", "642.00"),
        L(2, "Primo Goal Bk Red 5/7", 6, "0.00", "107.00", "101.90", "642.00"),
        L(3, "Primo Wildlife 8/10", 6, "0.00", "112.00", "106.67", "672.00"),
        L(4, "Primo Basket Ball S Grn 8/10", 6, "0.00", "112.00", "106.67", "672.00"),
        L(5, "Blibers Jordy Hike Gry Rd 7/11", 6, "0.00", "194.00", "184.76", "1,164.00"),
        L(6, "Kiero Ninja 15 Onion 5/8", 6, "0.00", "165.00", "157.14", "990.00"),
        L(7, "Kiero Ninja 26 BK 7/10", 6, "0.00", "165.00", "157.14", "990.00"),
        L(8, "Binny Saniya Br 11- 9/10", 24, "0.00", "116.00", "110.48", "2,784.00"),
        L(9, "Loose Indus Health Mix 7/10", 24, "0.00", "75.00", "71.43", "1,800.00"),
        L(10, "Binny Dolphin 103 Bk 6/9", 36, "0.00", "137.00", "130.48", "4,932.00"),
        L(11, "Binny Pl 154 Gry 5/8", 6, "0.00", "140.00", "133.33", "840.00"),
        L(12, "BINNY PL 167 Gry 6/8", 6, "0.00", "140.00", "133.33", "840.00"),
        L(13, "BINNY PL 167 Bk 5/8", 6, "0.00", "140.00", "133.33", "840.00"),
        L(14, "BINNY GB 21 BR 7/10", 6, "0.00", "200.00", "190.48", "1,200.00"),
        L(15, "BINNY GB 21 BK 7/10", 12, "0.00", "200.00", "190.48", "2,400.00"),
        L(16, "BINNY MONIKA BR 1/3", 6, "0.00", "98.00", "93.33", "588.00"),
        L(17, "Indus 6909 Bk 5/8", 6, "229.00", "144.00", "137.14", "864.00"),
        L(18, "Indus 11803 Bk 5/8", 6, "319.00", "201.00", "191.43", "1,206.00"),
        L(19, "Indus 22238 Blush 5/8", 6, "319.00", "201.00", "191.43", "1,206.00"),
        L(20, "Indus 12612 Brn 6/8", 6, "359.00", "226.00", "215.24", "1,356.00"),
        L(21, "Indus 22239 BK 5/8", 6, "329.00", "207.00", "197.14", "1,242.00"),
        L(22, "Indus 33645 Bg 5/8", 6, "319.00", "201.00", "191.43", "1,206.00"),
        L(23, "INDUS 19001 BK 5/8", 6, "229.00", "144.00", "137.14", "864.00"),
        L(24, "Indus 1917 Grape 5/8", 6, "319.00", "201.00", "191.43", "1,206.00"),
        L(25, "Indus 12507 Crm 5/8", 6, "339.00", "214.00", "203.81", "1,284.00"),
        L(26, "Indus 3324 Bk 6/8", 6, "339.00", "214.00", "203.81", "1,284.00"),
        L(27, "Indus Yahoo 142 Bk 5/8", 6, "289.00", "182.00", "173.33", "1,092.00"),
        L(28, "Indus Ig 216 Bk 2/5.", 6, "464.00", "292.00", "278.10", "1,752.00"),
    )
    val PAGE2 = listOf(
        L(29, "Indus Ig 216 Bk 11/1.", 6, "444.00", "280.00", "266.67", "1,680.00"),
        L(30, "INDUS IG 203 BR 7/10 ..", 6, "549.00", "346.00", "329.52", "2,076.00"),
        L(31, "Indus Yn 52 L Gry 6/9", 6, "439.00", "277.00", "263.81", "1,662.00"),
        L(32, "Kiero Flynet 23 Gry 2/5", 6, "0.00", "230.00", "219.05", "1,380.00"),
        L(33, "Kiero Flynet 12 Bk 11/1", 6, "0.00", "220.00", "209.52", "1,320.00"),
    )

    private class Builder(val slope: Double) {
        val words = mutableListOf<OcrWord>()
        fun at(x: Int, y: Int, text: String, h: Int = 20) {
            val ch = h * 0.5
            var cx = x.toDouble()
            for (part in text.split(' ')) {
                if (part.isEmpty()) { cx += ch; continue }
                val w = part.length * ch
                val yy = (y + slope * cx).toInt()
                words += OcrWord(part, Box(cx.toInt(), yy, (cx + w).toInt(), yy + h))
                cx += w + ch
            }
        }
        fun right(xr: Int, y: Int, text: String) = at((xr - text.length * 10.0).toInt(), y, text)
    }

    private fun top(b: Builder) {
        b.at(560, 20, "Tax Invoice")
        b.at(350, 50, "PATEL TRADING CORPORATION", 30)
        b.at(600, 95, "Address:"); b.at(560, 118, "KURDUWADI ROAD"); b.at(600, 141, "BARSHI"); b.at(560, 164, "Tel : 9422240077")
        b.at(440, 200, "email : pateltradingcorporation786@gmail.com")
        b.at(510, 240, "GSTIN : 27ABWPP2432J1Z5")
        b.at(20, 280, "Customer Name"); b.at(560, 275, "Sales (LT)")
        b.at(20, 305, "Shivtej Footwear Akluj"); b.at(630, 305, "Invoice No."); b.at(760, 305, ": 26-27/ALT-496"); b.at(1030, 300, "Date"); b.at(1110, 300, ": 29-Sep-2026")
        b.at(20, 335, "SHIVSHANKAR BAZAR SAMOR, C.S.N 2260,,"); b.at(630, 332, "Note"); b.at(760, 332, ": CART")
        b.at(20, 360, "SHANKARNAGAR ROAD, AKLUJ, TAL")
        b.at(20, 385, "L MALSHIRAS, Solapur, Maharashtra,")
        b.at(20, 410, "413101 PIN:413101")
        b.at(20, 440, "GSTIN/UIN : 27ECIPS0222J1ZI")
        b.at(20, 468, "State : Maharashtra"); b.at(300, 468, "StateCode : 27")
        // Item table heading, two lines.
        b.at(25, 505, "Sr."); b.at(70, 505, "Description of Goods / Services"); b.at(470, 505, "HSN/"); b.at(545, 510, "Quantity"); b.at(640, 505, "MRP")
        b.at(735, 505, "Rate"); b.at(820, 500, "Net Rate"); b.at(925, 500, "Disc"); b.at(975, 500, "Disc."); b.at(1025, 500, "Disc."); b.at(1075, 500, "GST"); b.at(1140, 495, "Amount")
        b.at(25, 530, "No."); b.at(475, 530, "SAC"); b.at(925, 525, "(per)"); b.at(975, 525, "(%)"); b.at(1025, 525, "Amt.")
    }

    private fun line(b: Builder, y: Int, l: L) {
        b.right(50, y, "${l.sr}"); b.at(70, y, l.desc); b.at(450, y, "64041920"); b.right(625, y, "${l.qty} Pairs"); b.right(715, y, l.mrp)
        b.right(805, y, l.rate); b.right(905, y, l.net); b.at(1080, y, "5%"); b.right(1250, y, l.amount)
    }

    fun page1(slope: Double = 0.01): OcrPage {
        val b = Builder(slope)
        top(b)
        var y = 560
        for (l in LINES) { line(b, y, l); y += 25 }
        b.right(625, y + 4, "240"); b.right(715, y + 4, "23,118.00"); b.right(805, y + 4, "36,558.00")
        b.right(960, y + 4, "0.00"); b.right(1010, y + 4, "0.00"); b.right(1060, y + 4, "0.00"); b.right(1250, y + 4, "36,558.00")
        b.at(60, y + 30, "Items: 33")
        b.at(20, y + 55, "Continued on Next Page....")
        b.at(440, y + 400, "EBase by Impression Systems : www.ebase.co.in")
        return OcrPage(1300, y + 450, b.words.shuffled(java.util.Random(21)))
    }

    fun page2(slope: Double = -0.008): OcrPage {
        val b = Builder(slope)
        top(b)
        var y = 560
        for (l in PAGE2) { line(b, y, l); y += 25 }
        y = 1300
        b.right(625, y, "270"); b.right(715, y, "31,710.00"); b.right(805, y, "44,676.00")
        b.right(960, y, "0.00"); b.right(1010, y, "0.00"); b.right(1060, y, "0.00"); b.right(1250, y, "44,676.00")
        b.at(60, y + 28, "Items: 33")
        b.at(20, y + 55, "Total Amount in Words : Fourty Four Thousand Six Hundred Seventy Six Only")
        b.at(250, y + 85, "Taxable Value"); b.at(420, y + 85, "SGST"); b.at(560, y + 85, "SGST Amt"); b.at(680, y + 85, "CGST"); b.at(820, y + 85, "CGST Amt")
        b.at(960, y + 85, "IGST"); b.at(1040, y + 85, "IGST Amt"); b.at(1130, y + 85, "Total Tax Amount")
        b.at(20, y + 110, "HSN/SAC"); b.right(1250, y + 110, "2,127.40")
        b.at(20, y + 135, "64041920"); b.right(400, y + 135, "42,548.57"); b.at(420, y + 135, "2.5%"); b.right(640, y + 135, "1,063.70"); b.at(680, y + 135, "2.5%")
        b.right(900, y + 135, "1,063.70"); b.right(1250, y + 135, "2,127.40")
        b.at(20, y + 165, "Total"); b.right(400, y + 165, "42,548.57"); b.right(640, y + 165, "1,063.70"); b.right(900, y + 165, "1,063.70")
        b.at(20, y + 195, "Total Taxable Value : Fourty Two Thousand Five Hundred Fourty Eight And Paise Fifty Seven Only")
        b.at(20, y + 222, "Total GST Amount : Two Thousand One Hundred Twenty Seven And Paise Fourty Only")
        b.at(20, y + 250, "Declaration:")
        b.at(20, y + 300, "IRN: 8a2f5b305303e0216c4da70f59e51312dce7ebbe769d28af939fccba11c89b12, AckNo.: 122635030942766, AckDt.: 07-10-2026 06:05:00 PM")
        b.at(500, y + 330, "Subject to BARSHI Jurisdiction"); b.at(950, y + 330, "For PATEL TRADING CORPORATION")
        return OcrPage(1300, y + 400, b.words.shuffled(java.util.Random(22)))
    }
}
