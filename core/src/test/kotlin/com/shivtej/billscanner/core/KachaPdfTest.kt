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
 * (words and places straight from the file). The first PDF had a sentence above the table that was read as part of the
 * heading, and page 1 was lost on the phone (129 lines / 865 pairs): the sentence is now under the table.
 */
class KachaPdfTest {
    private fun page(n: String) = OcrCodec.decode(javaClass.classLoader!!.getResource("real/$n.tsv")!!.readText())
    private fun show(p: com.shivtej.billscanner.core.ocr.ParsedInvoice) =
        "lines=${p.items.size} srs=${p.items.map { it.current.sr }.take(40)} first=${p.items.take(3).map { it.current.article + "/" + it.current.qty + "/" + it.current.rate }} notes=${p.notes}"

    @Test fun pageOneAlone() {
        val p = BillPattern.parse(listOf(page("kacha_part_1")), KnownPatterns.ALL)
        val arts = p.items.filter { it.kind == ItemKind.ARTICLE }
        assertEquals(show(p), 34, arts.size)
        assertEquals(show(p), "SWISS-116", arts[0].current.article)
        assertEquals(show(p), 0, arts.fold(BigDecimal.ZERO) { a, i -> a + (i.current.qty ?: BigDecimal.ZERO) }.compareTo(BigDecimal(228)))
    }

    @Test fun everyLineOfEveryPage() {
        val p = BillPattern.parse((1..4).map { page("kacha_pdf2_$it") }, KnownPatterns.ALL)
        val arts = p.items.filter { it.kind == ItemKind.ARTICLE }
        assertEquals(show(p), 163, arts.size)
        assertEquals(show(p), 0, arts.fold(BigDecimal.ZERO) { a, i -> a + (i.current.qty ?: BigDecimal.ZERO) }.compareTo(BigDecimal(1093)))
        assertEquals(show(p), "SWISS-116", arts[0].current.article)
        assertEquals(show(p), "INDUS IG-251", arts.last().current.article)
        assertEquals(0, arts[0].current.rate!!.compareTo(BigDecimal(226)))
    }
}
