package com.shivtej.billscanner.core

import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.ocr.BillPattern
import com.shivtej.billscanner.core.ocr.KnownPatterns
import com.shivtej.billscanner.core.store.OcrCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/**
 * The typed PDF of the 12 Patel Trading kachha bills (163 lines, 1093 pairs), read as the app reads a text PDF
 * (words and places straight from the file). On the phone only 129 lines / 865 pairs came: page 1 was lost.
 */
class KachaPdfTest {
    private fun page(n: Int) = OcrCodec.decode(javaClass.classLoader!!.getResource("real/kacha_pdf_$n.tsv")!!.readText())
    private fun show(p: com.shivtej.billscanner.core.ocr.ParsedInvoice) =
        "lines=${p.items.size} srs=${p.items.map { it.current.sr }.take(40)} first=${p.items.take(3).map { it.current.article + "/" + it.current.qty + "/" + it.current.rate }} notes=${p.notes}"

    @Test fun pageOneAlone() {
        val p = BillPattern.parse(listOf(page(1)), KnownPatterns.ALL)
        assertEquals(show(p), 34, p.items.count { it.kind == ItemKind.ARTICLE })
    }

    @Test fun everyLineOfEveryPage() {
        val p = BillPattern.parse((1..4).map { page(it) }, KnownPatterns.ALL)
        val arts = p.items.filter { it.kind == ItemKind.ARTICLE }
        assertEquals(show(p), 163, arts.size)
        assertEquals(show(p), 0, arts.fold(BigDecimal.ZERO) { a, i -> a + (i.current.qty ?: BigDecimal.ZERO) }.compareTo(BigDecimal(1093)))
        assertEquals("SWISS-116", arts[0].current.article)
        assertEquals(0, arts[0].current.rate!!.compareTo(BigDecimal(226)))
    }
}
