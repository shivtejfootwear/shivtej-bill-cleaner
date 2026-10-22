package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.export.InvoiceExport
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.model.Warnings
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.store.InvoiceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportTest {
    private fun invoice(blank: Set<Int> = emptySet()): Invoice {
        val p = InvoiceParser.parse(listOf(PatelInvoice.page(blank)))
        return Invoice("t1", 1_761_000_000_000, p.header, p.header, p.items, defaultTargetPct = BigDecimal("35"))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    @Test fun xlsxIsAValidWorkbookWithTheBillDiscountColumn() {
        val inv = invoice(blank = setOf(5))
        val bos = ByteArrayOutputStream(); InvoiceExport.xlsx(inv, bos)
        System.getProperty("xlsx.out")?.let { File(it).writeBytes(bos.toByteArray()) }
        val parts = unzip(bos.toByteArray())
        assertTrue(parts.keys.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet6.xml")))
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, xml) in parts) f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))  // every part is well-formed XML
        val items = parts.getValue("xl/worksheets/sheet5.xml")
        assertTrue(items.contains("Original Bill Discount (%)"))
        assertTrue(items.contains("Needs Verification"))                      // line 5's % was unreadable
        assertTrue(items.contains("state=\"frozen\"") && items.contains("<autoFilter"))
        assertTrue(items.contains("<f>IFERROR(ROUND((I2-J2)/I2*100,2),&quot;&quot;)</f><v>27.61</v>"))
        // Column K (11th) is the bill discount, right after the rate.
        assertEquals(10, InvoiceExport.ITEM_COLUMNS.indexOf("Original Bill Discount (%)"))
        assertEquals("Original Rate", InvoiceExport.ITEM_COLUMNS[9])
    }

    @Test fun stockCsvOnlyHasSizesThatAddUp() {
        val inv = invoice()
        val rows = InvoiceExport.stockRows(inv)
        // 1180 (6X8/1 -> 6+6+6), 1210 (10/24), 1251, 1553 (two colours), 6962, E6225 x2, E6227 = 18+24+12+24+12+12+12+1
        assertEquals(115, rows.sumOf { it.qty })
        assertTrue(rows.none { it.article == "1562" })      // 6X10/1 with 18 pairs is unclear -> left out
        assertTrue(rows.none { it.article == "FREIGHT" })
        val csv = InvoiceExport.stockCsv(inv)
        assertTrue(csv.lines()[0].endsWith("Article,Colour,Size,Quantity,MRP,Purchase Rate,Supplier,Invoice Number,Invoice Date"))
        assertTrue(csv.contains("1180,BLK,6,6,235.00,165.02,PATEL TRADELINES,G2/2627/1309,20-Oct-2026"))
    }

    @Test fun savingAndLoadingKeepsEveryPaisa() {
        val inv = invoice(blank = setOf(5))
        val edited = inv.copy(items = inv.items.mapIndexed { i, it -> if (i == 4) it.copy(current = Calc.recalc(it.current.copy(discPct = BigDecimal("3.00")))) else it })
        val back = InvoiceCodec.decode(InvoiceCodec.encode(edited))
        assertEquals(edited, back)
        assertEquals(null, back.items[4].original.discPct)       // the scan's value stays as read
        assertEquals(BigDecimal("3.00"), back.items[4].current.discPct)
    }

    @Test fun warningsExplainInHindi() {
        val w = Warnings.of(invoice(blank = setOf(5)))
        assertTrue(w.joinToString { it.hindi }, w.any { it.hindi.contains("फ़र्क़ ₹13.43") })
        assertTrue(w.any { it.hindi.contains("Needs Verification") })
        assertTrue(w.any { it.hindi.contains("Page 1 of 2") })
        assertTrue(w.any { it.hindi.contains("1562") && it.hindi.contains("बराबर नहीं") })
        assertTrue(w.none { it.hindi.contains("पैकिंग में") })     // (134) = 134 pairs
    }
}

class EditsTest {
    private val p = com.shivtej.billscanner.core.ocr.InvoiceParser.parse(listOf(PatelInvoice.page(setOf(5))))
    private val inv = Invoice("t", 0, p.header, p.header, p.items)

    @Test fun editingTheBillPercentRecalculatesAndLogs() {
        val it5 = inv.items[4]
        val e = com.shivtej.billscanner.core.model.Edits.updateItem(inv, it5.id, it5.current.copy(discPct = BigDecimal("3.00")), 100)
        val v = e.items[4].current
        assertEquals(BigDecimal("70.56"), v.discAmt); assertEquals(BigDecimal("2281.32"), v.taxable); assertEquals(BigDecimal("57.03"), v.cgstAmt)
        assertEquals(null, e.items[4].original.discPct)
        assertEquals(listOf("billDiscountPct"), e.changes.map { it.field })
        assertEquals("—", e.changes[0].from); assertEquals("3.00", e.changes[0].to)
        val r = com.shivtej.billscanner.core.model.Edits.revertItem(e, it5.id, 200)
        assertEquals(null, r.items[4].current.discPct)
        assertEquals(2, r.changes.size)
    }

    @Test fun addDuplicateDelete() {
        val E = com.shivtej.billscanner.core.model.Edits
        val a = E.addItem(inv, 1); assertEquals(12, a.items.size); assertEquals(12, a.items.last().current.sr)
        val d = E.duplicateItem(a, inv.items[0].id, 2); assertEquals(13, d.items.size); assertEquals(d.items[0].current, d.items[1].current)
        val x = E.deleteItem(d, d.items[1].id, 3); assertEquals(12, x.items.size)
        assertEquals(listOf("added", "duplicated", "deleted"), x.changes.map { it.field })
    }
}
