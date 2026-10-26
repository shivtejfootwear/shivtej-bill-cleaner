package com.shivtej.billscanner.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Goods check ("माल चेक"): for each bill, how many pieces of each line have arrived.
 * Kept in its own settings file, so it is part of the Google backup and never changes the bill itself.
 * Stored as invoiceId -> "lineId=count,lineId=count".
 */
class CheckStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("mal_check", Context.MODE_PRIVATE)
    private val cache = mutableStateMapOf<String, Map<String, Int>>().apply {
        p.all.forEach { (k, v) -> put(k, decode(v as? String)) }
    }

    /** Pieces received so far for each line of this bill (missing = 0). */
    fun of(invoiceId: String): Map<String, Int> = cache[invoiceId] ?: emptyMap()

    fun started(invoiceId: String): Boolean = of(invoiceId).values.any { it > 0 }

    fun set(invoiceId: String, lineId: String, count: Int) {
        val m = of(invoiceId).toMutableMap()
        if (count <= 0) m.remove(lineId) else m[lineId] = count
        cache[invoiceId] = m
        p.edit().putString(invoiceId, m.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }

    fun clear(invoiceId: String) { cache.remove(invoiceId); p.edit().remove(invoiceId).apply() }

    private fun decode(s: String?): Map<String, Int> = (s ?: "").split(',').mapNotNull {
        val i = it.lastIndexOf('='); if (i <= 0) null else it.substring(i + 1).toIntOrNull()?.let { n -> it.substring(0, i) to n }
    }.toMap()
}
