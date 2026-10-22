package com.shivtej.billscanner.core.store

import com.shivtej.billscanner.core.ocr.Box
import com.shivtej.billscanner.core.ocr.OcrPage
import com.shivtej.billscanner.core.ocr.OcrWord

/** What the OCR read on a page, kept so a bill can be read again (e.g. after the person teaches a column). */
object OcrCodec {
    fun encode(p: OcrPage): String = buildString {
        append("page\t${p.width}\t${p.height}\n")
        for (w in p.words) append("${w.box.l}\t${w.box.t}\t${w.box.r}\t${w.box.b}\t${w.text.replace('\t', ' ').replace('\n', ' ')}\n")
    }

    fun decode(text: String): OcrPage {
        val lines = text.lines().filter { it.isNotEmpty() }
        val head = lines.firstOrNull()?.split('\t')
        val (w, h) = if (head != null && head[0] == "page") head[1].toInt() to head[2].toInt() else 0 to 0
        val words = lines.drop(if (head?.get(0) == "page") 1 else 0).mapNotNull { l ->
            val p = l.split('\t', limit = 5)
            if (p.size < 5) null else OcrWord(p[4], Box(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt()))
        }
        return OcrPage(w, h, words)
    }
}
