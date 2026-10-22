package com.shivtej.billscanner.data

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.shivtej.billscanner.core.model.Invoice
import com.shivtej.billscanner.core.store.InvoiceCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** One saved invoice: the full record as JSON plus a few columns for lists and search. */
@Entity(tableName = "invoices")
data class InvoiceRow(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val supplier: String,
    val invoiceNo: String,
    val status: String,
    val lines: Int,
    val json: String,
)

@Dao
interface InvoiceDao {
    @Upsert suspend fun put(r: InvoiceRow)
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC") fun all(): Flow<List<InvoiceRow>>
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC") suspend fun allNow(): List<InvoiceRow>
    @Query("SELECT * FROM invoices WHERE id = :id") suspend fun get(id: String): InvoiceRow?
    @Query("SELECT * FROM invoices WHERE id = :id") fun watch(id: String): Flow<InvoiceRow?>
    @Query("DELETE FROM invoices WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [InvoiceRow::class], version = 1, exportSchema = false)
abstract class BillDb : RoomDatabase() {
    abstract fun invoices(): InvoiceDao

    companion object {
        fun open(ctx: Context) = Room.databaseBuilder(ctx, BillDb::class.java, "bill_scanner.db").build()
        fun inMemory(ctx: Context) = Room.inMemoryDatabaseBuilder(ctx, BillDb::class.java).allowMainThreadQueries().build()
    }
}

class InvoiceRepo(private val db: BillDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.invoices()

    fun all(): Flow<List<Invoice>> = dao.all().map { rows -> rows.mapNotNull { runCatching { InvoiceCodec.decode(it.json) }.getOrNull() } }
    fun watch(id: String): Flow<Invoice?> = dao.watch(id).map { r -> r?.let { runCatching { InvoiceCodec.decode(it.json) }.getOrNull() } }
    suspend fun get(id: String): Invoice? = dao.get(id)?.let { InvoiceCodec.decode(it.json) }
    suspend fun allNow(): List<Invoice> = dao.allNow().mapNotNull { runCatching { InvoiceCodec.decode(it.json) }.getOrNull() }

    suspend fun save(inv: Invoice) = dao.put(
        InvoiceRow(inv.id, inv.createdAt, clock(), inv.header.supplierName.orEmpty(), inv.header.invoiceNo.orEmpty(), inv.status.name, inv.items.size, InvoiceCodec.encode(inv)),
    )

    suspend fun delete(id: String) = dao.delete(id)
}

/** Keeps the original photos exactly as received (byte for byte), one folder per invoice. */
class PageStore(private val ctx: Context) {
    fun dir(invoiceId: String) = File(ctx.filesDir, "invoices/$invoiceId").apply { mkdirs() }

    suspend fun keep(invoiceId: String, uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapIndexed { i, u ->
            val f = File(dir(invoiceId), "page_${i + 1}.jpg")
            ctx.contentResolver.openInputStream(u)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            f.absolutePath
        }
    }

    /** Copies one file the person picked into the invoice folder, exactly as it is. */
    fun keepFile(invoiceId: String, uri: Uri, name: String): File {
        val f = File(dir(invoiceId), name)
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        return f
    }

    /** A page picture made from a PDF (for viewing and the photo PDF). */
    fun keepBitmap(invoiceId: String, n: Int, bmp: android.graphics.Bitmap): String {
        val f = File(dir(invoiceId), "page_$n.jpg")
        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    /** What the OCR read on each page, so the bill can be read again later without the OCR. */
    fun saveOcr(invoiceId: String, pages: List<com.shivtej.billscanner.core.ocr.OcrPage>) =
        pages.forEachIndexed { i, p -> File(dir(invoiceId), "ocr_${i + 1}.tsv").writeText(com.shivtej.billscanner.core.store.OcrCodec.encode(p)) }

    fun loadOcr(invoiceId: String): List<com.shivtej.billscanner.core.ocr.OcrPage> =
        (1..50).map { File(dir(invoiceId), "ocr_$it.tsv") }.takeWhile { it.exists() }.map { com.shivtej.billscanner.core.store.OcrCodec.decode(it.readText()) }

    fun deleteAll(invoiceId: String) { File(ctx.filesDir, "invoices/$invoiceId").deleteRecursively() }
}

/** Small personal settings. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var language: String
        get() = p.getString("lang", "hi") ?: "hi"
        set(v) { p.edit().putString("lang", v).apply() }
    /** Quick target discount choices, e.g. "30,35,40,45,50". */
    var discountChoices: List<Int>
        get() = (p.getString("disc", "30,35,40,45,50") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
        set(v) { p.edit().putString("disc", v.distinct().sorted().joinToString(",")).apply() }
    var pdfLandscape: Boolean
        get() = p.getBoolean("pdf_land", true)
        set(v) { p.edit().putBoolean("pdf_land", v).apply() }

    /** What the scanner learnt from the person's corrections (see core Learnt). */
    var learnt: com.shivtej.billscanner.core.ocr.Learnt
        get() = com.shivtej.billscanner.core.ocr.Learnt.decode(p.getString("learnt", ""))
        set(v) { p.edit().putString("learnt", v.encode()).apply() }

    /** Column meanings the person taught, per supplier: "supplier\u001Fheading\u001FKIND" lines. */
    var taught: Map<String, Map<String, com.shivtej.billscanner.core.ocr.InvoiceParser.Kind>>
        get() = (p.getString("taught", "") ?: "").lines().mapNotNull { l ->
            val x = l.split('\u001F'); if (x.size != 3) null else runCatching { Triple(x[0], x[1], com.shivtej.billscanner.core.ocr.InvoiceParser.Kind.valueOf(x[2])) }.getOrNull()
        }.groupBy { it.first }.mapValues { (_, v) -> v.associate { it.second to it.third } }
        set(v) { p.edit().putString("taught", v.flatMap { (s, m) -> m.map { (h, k) -> "$s\u001F$h\u001F${k.name}" } }.joinToString("\n")).apply() }
}
