package com.shivtej.billscanner

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Edits
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.ocr.InvoiceParser
import com.shivtej.billscanner.data.BillDb
import com.shivtej.billscanner.data.InvoiceRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepoTest {
    private fun invoice(blank: Set<Int> = setOf(5)): Invoice {
        val p = InvoiceParser.parse(listOf(PatelInvoice.page(blank)))
        return Invoice("patel", 1_761_000_000_000, p.header, p.header, p.items, defaultTargetPct = BigDecimal("35"))
    }

    @Test fun savesEditsAndKeepsOriginals() = runBlocking {
        val db = BillDb.inMemory(ApplicationProvider.getApplicationContext())
        val repo = InvoiceRepo(db)
        val inv = invoice()
        repo.save(inv)
        val line5 = inv.items[4]
        assertNull(line5.current.discPct)
        assertTrue(F.DISC_PCT in line5.needs)
        // The person types the printed 3% for line 5.
        val edited = Edits.updateItem(inv, line5.id, line5.current.copy(discPct = BigDecimal("3")), 2)
        repo.save(edited)
        val back = repo.get("patel")!!
        val l5 = back.items[4]
        assertEquals("3.00", Calc.fmt(l5.current.discPct))
        assertNull(l5.original.discPct)                 // what the scan read is kept
        assertTrue(back.changes.any { it.field == "billDiscountPct" })
        assertEquals(1, repo.all().first().size)
        repo.delete("patel")
        assertEquals(0, repo.allNow().size)
        db.close()
    }
    // PDFs need Android's native PDF writer, which the JVM test runner does not have;
    // they are made and checked on the emulator (OcrOnRealInvoiceTest).
}
