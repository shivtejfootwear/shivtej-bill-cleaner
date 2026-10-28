package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Totals
import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.BillPattern
import com.shivtej.billscanner.core.ocr.KnownPatterns
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * VENKATESHWARA CORPORATION (26-27), L-1235, 8-Oct-26: photo whose right edge cuts the Amount column
 * ("Amoun", "2,293.3E", "₹ 9,968.0"). The built-in code names the cut heading; pairs × rate completes the cut amounts.
 */
class VenkateshwaraTest {
    private class B(val slope: Double) {
        val words = mutableListOf<OcrWord>()
        fun at(x0: Int, y0: Int, text: String, h0: Int = 16) {
            val x = x0 * 1.2; val y = y0 * 1.2; val h = h0 * 1.2; val ch = h * 0.5
            var cx = x
            for (part in text.split(' ')) {
                if (part.isEmpty()) { cx += ch; continue }
                val w = part.length * ch; val yy = (y + slope * cx).toInt()
                words += OcrWord(part, Box(cx.toInt(), yy, (cx + w).toInt(), yy + h.toInt())); cx += w + ch
            }
        }
        fun right(xr: Int, y: Int, t: String) = at((xr - t.length * 8.0).toInt(), y, t)
    }

    private fun page(slope: Double, cut: Boolean): OcrPage {
        val b = B(slope)
        b.at(400, 498, "Tax Invoice"); b.at(14, 515, "Subject to Dharashiv Jurisdition.")
        b.at(405, 540, "Tax Invoice"); b.at(175, 560, "VENKATESHWARA CORPORATION (26-27)", 24)
        b.at(188, 588, "PLOT NO A111, MIDC AREA,Dharashiv 413501 GST NO:- 27AAVFV7392K1Z0")
        b.at(160, 603, "Email: venkateshwaracorp2022@gmail.com,Mobile No:9657945561,7972843048,8788219071")
        b.at(412, 620, "GSTIN/UIN"); b.at(328, 636, "State Name : Maharashtra, Code : 27")
        b.at(14, 650, "Buyer"); b.at(92, 650, ": Shivtej Footwear Akluj"); b.at(456, 654, "Invoice No"); b.at(530, 650, ": L-1235"); b.at(683, 645, "Date"); b.at(760, 645, ": 8-Oct-26")
        b.at(14, 667, "Address"); b.at(92, 667, ":"); b.at(456, 670, "DC No"); b.at(530, 667, ": 1616"); b.at(683, 661, "Date"); b.at(760, 661, ": 8-Oct-26")
        b.at(14, 687, "Place of Supply: Maharashtra Code: 27"); b.at(456, 686, "Eway Bill No:"); b.at(683, 678, "Date"); b.at(760, 678, ": 8-Oct-26")
        b.at(14, 703, "Party GST No: 27ECIPS0222J1ZI"); b.at(456, 703, "Pay Mode: CREDIT"); b.at(737, 694, "Due On :"); b.at(832, 694, "7-Nov-26")
        // table heading
        b.at(14, 728, "Sr.No"); b.at(53, 728, "Item Description"); b.at(437, 730, "HSN"); b.at(488, 728, "GST%"); b.at(538, 726, "Size"); b.at(588, 724, "MRP")
        b.at(663, 722, "Qty"); b.at(725, 721, "Rate"); b.at(773, 720, "Disc."); b.at(838, 719, if (cut) "Amoun" else "Amount")
        val L = listOf(listOf("1.", "G-PRO 4 DGR", "5X8", "499", "333.33", "2,666.64"), listOf("2.", "FN-44/DG TITAN GRY", "5X8", "449", "300.00", "2,400.00"),
            listOf("3.", "G-FN 1011 F. BLACK", "5X8", "429", "286.67", if (cut) "2,293.3E" else "2,293.36"), listOf("4.", "G-FN 1 / 1 F. BLACK", "4X7", "399", "266.67", if (cut) "2,133.3E" else "2,133.36"))
        var y = 746
        for (l in L) {
            b.at(25, y, l[0]); b.at(55, y, l[1]); b.at(425, y, "64029990"); b.at(490, y, "5 %"); b.at(540, y, l[2]); b.at(590, y, l[3])
            b.at(655, y, "8 pair"); b.right(760, y, l[4]); b.right(893, y - 1, l[5]); y += 17
        }
        b.at(535, 1357, "Total"); b.at(590, 1357, "0"); b.at(655, 1357, "32 pair"); b.right(893, 1355, if (cut) "9,493.3" else "9,493.36")
        b.at(598, 1378, "Output CGST @2.5% 2.50 %"); b.right(893, 1377, "237.33")
        b.at(598, 1399, "Output SGST @2.5% 2.50 %"); b.right(893, 1398, "237.33")
        b.at(715, 1420, "Round Off"); b.right(893, 1420, "(-)0.02")
        b.at(780, 1445, if (cut) "₹ 9,968.0" else "₹ 9,968.00", 22)
        b.at(12, 1472, "Amount Rs.Inwords: INR Nine Thousand Nine Hundred Sixty Eight Only.")
        b.at(12, 1492, "Company's Bank Details"); b.at(386, 1492, "HSN/SAC"); b.at(482, 1492, "Taxable"); b.at(580, 1494, "CGST"); b.at(726, 1494, "SGST/UTGST"); b.at(838, 1492, "Total")
        b.at(12, 1510, "Bank Name"); b.at(165, 1510, ": ICICI Bank 763905000194"); b.at(497, 1510, "Value"); b.at(552, 1510, "Rate"); b.at(616, 1510, "Amount"); b.at(688, 1510, "Rate"); b.at(752, 1510, "Amount"); b.at(818, 1510, "Tax Amount")
        b.at(12, 1527, "A/c No"); b.at(165, 1527, ": 763905000194"); b.at(386, 1527, "64029990"); b.right(541, 1527, "9,493.36"); b.at(545, 1527, "2.50%"); b.right(670, 1527, "237.33"); b.at(688, 1527, "2.50%"); b.right(800, 1527, "237.33"); b.right(893, 1527, "474.66")
        b.at(12, 1545, "Branch & IFS Code"); b.at(165, 1545, ": Chincholi MIDC ,ICIC0007639"); b.at(455, 1542, "Total"); b.right(541, 1542, "9,493.36"); b.right(670, 1542, "237.33"); b.right(800, 1542, "237.33"); b.right(893, 1542, "474.66")
        b.at(173, 1703, "Party's SIGNATURE"); b.at(548, 1708, "For : VENKATESHWARA CORPORATION (26-27)")
        return OcrPage(1080, 2100, b.words.shuffled(java.util.Random(7)))
    }


    @Test fun cutPhotoIsReadFully() {
        for (cut in listOf(true, false)) {
            val p = BillPattern.parse(listOf(page(0.004, cut)), KnownPatterns.ALL)
            assertEquals("L-1235", p.header.invoiceNo)
            assertEquals(listOf(1, 2, 3, 4), p.items.map { it.current.sr })
            val amounts = listOf("2666.64", "2400.00", "2293.36", "2133.36")
            for ((i, it) in p.items.withIndex()) {
                assertEquals("line ${i + 1}", 0, it.current.taxable!!.compareTo(BigDecimal(amounts[i])))
                assertTrue("line ${i + 1}: ${it.needs}", it.needs.isEmpty())
                assertEquals(null, it.current.discPct)
            }
            val inv = Invoice("v", 0, p.header, p.header, p.items, columns = p.columns)
            assertEquals(0, Totals.of(inv).grand.compareTo(BigDecimal("9968.00")))
            assertEquals(0, Calc.sittingPct(inv.items[0].current, inv.header)!!.compareTo(BigDecimal("29.86")))
        }
    }
}
