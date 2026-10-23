package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.ocr.BillPattern
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.core.synth.SynthBill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillPatternTest {
    private val p = BillPattern(
        "Yash Enterprises", gstins = listOf("27FSRPD6977D1Z5"), names = listOf("YASHENTERPRISES"),
        headings = mapOf("quantity" to InvoiceParser.Kind.QTY), order = listOf(InvoiceParser.Kind.SR, InvoiceParser.Kind.DESC),
        mrpInDesc = """-(\d{2,4})\b""", note = "article-colour-MRP",
    )

    @Test fun codeGoesAndComesBack() {
        val code = p.encode()
        assertTrue(code.startsWith("SBP1:"))
        // Pasted with text and line breaks around it (as WhatsApp gives it).
        val back = BillPattern.decode("यह कोड पेस्ट करें:\n" + code.chunked(30).joinToString("\n") + "\nधन्यवाद")!!
        assertEquals(p, back)
        assertEquals(null, BillPattern.decode("SBP1:not-a-code"))
    }

    @Test fun matchesTheSupplierEvenWhenTheGstinIsMisread() {
        assertTrue(p.matches(Header(supplierGstin = "27FSRPD6977DIZ5")))
        assertTrue(p.matches(Header(supplierName = "Yash Enterprises")))
        assertTrue(!p.matches(Header(supplierGstin = "27ABCDE1234F1Z5", supplierName = "Other Traders")))
    }

    @Test fun theMrpInTheDescriptionIsTakenWithThePattern() {
        // A bill without an MRP column whose MRP is inside the description ("MRP 999").
        val b = (1L..200L).asSequence().map { SynthBill.make(it, SynthBill.Style.SIMPLE) }.first { "mrp-in-desc" in it.features }
        val pat = BillPattern(b.supplier, names = listOf(b.supplier.uppercase().filter(Char::isLetterOrDigit)), mrpInDesc = """MRP\s*(\d{2,5})""")
        val r = BillPattern.parse(b.pages, listOf(pat))
        assertTrue(r.notes.any { it.contains(b.supplier) })
        assertEquals(b.lines.size, r.items.count { it.current.mrp != null })
    }
}

class BackupAndKnownTest {
    @Test fun aBackupComesBackWhole() {
        val p = com.shivtej.billscanner.core.ocr.KnownPatterns.ALL.first()
        val text = com.shivtej.billscanner.core.store.Backup.write(listOf(p), "{\"x\":1}", "a\u001Fb\u001FQTY")
        val back = com.shivtej.billscanner.core.store.Backup.read(text)
        assertEquals(listOf(p), back.patterns); assertEquals("{\"x\":1}", back.learnt); assertEquals("a\u001Fb\u001FQTY", back.taught)
    }

    @Test fun patelTradingComesWithTheApp() {
        assertTrue(com.shivtej.billscanner.core.ocr.KnownPatterns.ALL.any { it.name == "Patel Trading Corporation" })
        assertTrue(com.shivtej.billscanner.core.ocr.KnownPatterns.ALL.first().matches(com.shivtej.billscanner.core.model.Header(supplierGstin = "27ABWPP2432J1Z5")))
    }
}
