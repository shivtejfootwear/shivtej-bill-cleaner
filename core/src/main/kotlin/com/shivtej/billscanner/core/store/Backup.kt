package com.shivtej.billscanner.core.store

import com.shivtej.billscanner.core.ocr.BillPattern
import java.util.Base64

/**
 * What the app has been taught (pasted bill codes, fixes it learnt, columns taught) in one small text file,
 * saved to the phone's Download folder or shared, and read back after a new install or on a new phone.
 */
object Backup {
    private const val HEAD = "Shivtej Bill Scanner AI — backup (bill codes and what the scanner learnt)"

    class Content(val patterns: List<BillPattern>, val learnt: String?, val taught: String?)

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun un64(s: String) = runCatching { String(Base64.getUrlDecoder().decode(s.trim()), Charsets.UTF_8) }.getOrNull()

    fun write(patterns: List<BillPattern>, learnt: String, taught: String): String = buildString {
        append(HEAD).append('\n')
        patterns.forEach { append(it.encode()).append('\n') }
        append("LEARNT:").append(b64(learnt)).append('\n')
        append("TAUGHT:").append(b64(taught)).append('\n')
    }

    /** Reads a backup file, or any text with codes in it (a WhatsApp message with several codes works too). */
    fun read(text: String): Content {
        val pats = Regex("""SBP1:[A-Za-z0-9_\-]+""").findAll(text).mapNotNull { BillPattern.decode(it.value) }.toList()
        val lines = text.lines()
        return Content(pats, lines.firstOrNull { it.startsWith("LEARNT:") }?.removePrefix("LEARNT:")?.let(::un64),
            lines.firstOrNull { it.startsWith("TAUGHT:") }?.removePrefix("TAUGHT:")?.let(::un64))
    }
}
