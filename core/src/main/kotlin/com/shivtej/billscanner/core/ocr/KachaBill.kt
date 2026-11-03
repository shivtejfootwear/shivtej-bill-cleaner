package com.shivtej.billscanner.core.ocr

import com.shivtej.billscanner.core.model.Calc
import com.shivtej.billscanner.core.model.Column
import com.shivtej.billscanner.core.model.F
import com.shivtej.billscanner.core.model.Header
import com.shivtej.billscanner.core.model.ItemKind
import com.shivtej.billscanner.core.model.ItemValues
import com.shivtej.billscanner.core.model.LineAt
import com.shivtej.billscanner.core.model.LineItem
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Handwritten "कच्चा बिल" on a printed form (Patel Trading Corporation: Article | Colour | Size | Pair | Rate | Amount),
 * one bill per photo. The owner's rules:
 *  - the first number written in the Article column is the MRP ("359 - SWISS-116"); lines of brands without MRP
 *    (Binny, Kiero, Flymax, BMG …) start with the article itself;
 *  - "—v—", "—1—" = same as the line above: "384 —v— 10" under "CLS-09" is CLS-10, a bare "384 —v——" is the same article;
 *  - the number written under the Pair column at the bottom is the running total of pairs of the day's bills.
 * Each line is found from the numbers in the Rate / Pair columns (handwriting does not keep to one baseline),
 * misread names are corrected against the articles and colours of earlier bills, and a line whose numbers
 * do not make sense is left for the person to check (never guessed).
 */
object KachaBill {
    const val NOTE = "kacha"

    /** Articles and colours from the owner's bills (Patel Trading, 02-11-2026), to correct misread names. */
    val ARTICLES: List<String> = (
        "6227|ABROS FEROS ASSG7995|ALZA MAYRA-1|AMTOC MARSHAL-12|AMTOC PACKER-1|AMTOC PACKER-15|" +
        "APPROUTE FIGHTER-12|BINNY DOLPHIN-103|BINNY GB-21|BINNY GS-103|BMG CROSS-4|BMG CROSS-6|BMG ET-4|" +
        "BMG JET-2|BMG MTR-8|BMG PRIME-2|CLS-04|CLS-09|CLS-10|CLS-28|CLS-29|DELTA BLASTER|DELTA BLASTER-1|" +
        "DOREMON-210|DOREMON-533|DOREMON-534|DOREMON-535|DOREMON-553|ESP FUTURE-09|EVG-015|EVG-15|" +
        "FLYMAX FASHION|FLYMAX ROLEX-1|FLYMAX SULTAN-1|GIMMY METRO-07|HIKE-05|IG-1002|IG-203|IG-211|IG-215|" +
        "IG-221|IG-222|IG-238|IG-243|IG-251|IG-255|IG-280|IG-292|IG-422|IG-705|INDUS 22182|INDUS 33182|" +
        "INDUS IG-1005|INDUS IG-206|INDUS IG-209|INDUS IG-222|INDUS IG-251|INDUS IG-258|INDUS IG-259|" +
        "INDUS IG-265|INDUS IG-273|INDUS IG-274|INDUS IG-312|INDUS IG-424|INDUS IG-705|INDUS IG-901|" +
        "INDUS IG-905|INDUS K-IG-222|INDUS OXYGEN-04|INDUS SLIDER-415|INDUS STUDIO-02|INDUS SWISS PLUS-02|" +
        "K-IG-206|K-IG-210|K-IG-212|K-IG-222|K.PERFECT-03|KIERO BRUNO-33|KIERO BRUNO-38|KIERO HERO-18|" +
        "KIERO NINJA-15|KIERO NINJA-29|KIERO NINJA-30|KOOH BLADE-1|KOOH ZOOM-2|ORTHO P103|ORTHO-06|" +
        "PU VLK HONNY-29|QUBE-01|REENA-55|RM-101|RUSTIK-08|RUSTIK-09|SLIDER-416|SLIDER-432|SLIDER-461|" +
        "SPEED AIRSTRIKE|SPEED BOUNCER|SPEED CLOUD|SPEED EASY-1NN|SPEED GALAXY|SPEED MERCURY|SPEED RAFALE|" +
        "SPEED RUNWAY|SPEED SIGNAL|SPEED TRAINING|SPEED UNIGLOW|STUDIO-01|STUDIO-04|SWISS PLUS-01|SWISS-115|" +
        "SWISS-116|TIYA-58|YAHOO|YAHOO-150|YMSTAR-507|ZORA ARMAN").split('|')
    val COLOURS: List<String> = (
        "BEIGE|BGI|BK|BK/GRY|BK/MHD|BK/ORNG|BK/RED|BL/GRY|BR|COFFEE|CPR|CRM|CRM/BR|D.GRY|D.GRY/L.GRY|" +
        "D.GRY/LEM|D.GRY/ST/GRY|D.GRY/WHT|FIG|FIROZI/ST/GRY|GR/ORG|GRE|GREY|GRY|GRY/BK|L.GRY/BK|L.GRY/SLATE|" +
        "MHD|MOV|MS|NA-10|NA-11|NA-13|NA-5|NA-6|NA-9|NAVY/GRY|OLIVE|ONION|ONION/PCH|ORG/GRY|ORN/D.GRY|P.WHT|" +
        "PCH|PENT/PURPLE|PINK|PITCH/WHT|TAN|WHITE|WHT/BK|WHT/BL|WHT/GRY|WHT/MLT|WHT/SLATE|WHT/SLV|WHT/SLVR").split('|')

    private class Heads(val article: Box, val colour: Box?, val size: Box, val pair: Box, val rate: Box, val amount: Box?) {
        val bottom get() = listOfNotNull(article, colour, size, pair, rate, amount).maxOf { it.b }
        val h get() = listOf(article, size, pair, rate).map { it.h }.sorted()[1]
        // Column edges: a little left of each printed heading (headings are centred in their columns).
        fun edges(): List<Double> {
            val col = colour ?: Box(((article.r + size.l) / 2.0).toInt() - 10, size.t, ((article.r + size.l) / 2.0).toInt() + 10, size.b)
            val amt = amount ?: Box(rate.r + (rate.l - pair.r), rate.t, rate.r + (rate.l - pair.r) + rate.w, rate.b)
            fun edge(b: Box) = b.l - 0.5 * b.w
            return listOf(edge(col), edge(size), edge(pair), edge(rate), edge(amt))
        }
    }

    private fun n(s: String) = s.lowercase().filter { it.isLetter() }

    private fun findHeads(p: OcrPage): Heads? {
        val ws = p.words
        fun pick(test: (String) -> Boolean) = ws.filter { test(n(it.text)) }
        val arts = pick { it.startsWith("artic") || it == "article" }
        for (a in arts) {
            fun near(test: (String) -> Boolean) = pick(test).filter { abs(it.box.cy - a.box.cy) < 2.5 * a.box.h && it.box.l > a.box.l }.minByOrNull { abs(it.box.cy - a.box.cy) }
            val size = near { it == "size" || it == "sise" || it == "siz" } ?: continue
            val pair = near { it.startsWith("pai") || it == "pairs" || it == "pair" } ?: continue
            val rate = near { it.startsWith("rat") } ?: continue
            if (!(a.box.cx < size.box.cx && size.box.cx < pair.box.cx && pair.box.cx < rate.box.cx)) continue
            val colour = near { it.startsWith("colo") || it.startsWith("colr") }?.takeIf { it.box.cx > a.box.cx && it.box.cx < size.box.cx }
            val amount = near { it.startsWith("amou") || it.startsWith("amnt") }?.takeIf { it.box.cx > rate.box.cx }
            return Heads(a.box, colour?.box, size.box, pair.box, rate.box, amount?.box)
        }
        return null
    }

    /** The page turned (0/90/180/270°) so the table headings read left to right. */
    fun upright(p: OcrPage): OcrPage? {
        for (k in 0..3) { val q = turn(p, k); if (findHeads(q) != null) return q }
        return null
    }

    private fun turn(p: OcrPage, k: Int): OcrPage {
        val w = p.width; val h = p.height
        fun b(x: Box) = when (k) {
            1 -> Box(h - x.b, x.l, h - x.t, x.r)          // turned 90° clockwise
            2 -> Box(w - x.r, h - x.b, w - x.l, h - x.t)
            3 -> Box(x.t, w - x.r, x.b, w - x.l)          // turned 90° anticlockwise
            else -> x
        }
        if (k == 0) return p
        return OcrPage(if (k % 2 == 1) h else w, if (k % 2 == 1) w else h, p.words.map { OcrWord(it.text, b(it.box)) })
    }

    /** A kachha form: the printed Article…Rate headings, and nothing of a tax invoice. */
    fun looksLike(pages: List<OcrPage>): Boolean = pages.isNotEmpty() && pages.all { pg ->
        val all = pg.words.joinToString(" ") { it.text }.lowercase()
        upright(pg) != null && !Regex("""gstin|hsn|taxable|cgst|sgst|igst|tax\s*invoice""").containsMatchIn(all)
    }

    // ---------------------------------------------------------------- numbers

    private val DIGITISH = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', '!' to '1',
        'Z' to '2', 'z' to '2', 'S' to '5', 's' to '5', 'B' to '8', 'G' to '6', 'b' to '6', 'g' to '9', 'q' to '9', 'T' to '7', ')' to '1')

    /** A handwritten number: letters the OCR confuses with digits turned back, strokes and underlines dropped. */
    fun number(t: String): BigDecimal? {
        val s = t.trim().trim('-', '_', '~', '=', '—', '–', '.', ',', ':', ';', '/', '\\', '(', '[', '{', '*', '"', '\'')
        if (s.isEmpty()) return null
        val d = s.map { c -> DIGITISH[c] ?: c }.joinToString("")
        val m = Regex("""^(\d{1,5})(?:[.,](\d{1,2}))?$""").matchEntire(d) ?: return null
        return BigDecimal(m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""))
    }

    private val SIZE = Regex("""(\d{1,2})\s*[xX×*+kKyY]\s*(\d{1,2})""")

    fun size(t: String): String? {
        val d = t.replace(" ", "").map { c -> if (c in "OoDQ") '0' else if (c in "Il|i") '1' else if (c == 'S' || c == 's') '5' else if (c == 'T') '7' else if (c == 'g' || c == 'q') '9' else c }.joinToString("")
        val m = SIZE.find(d) ?: return null
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        if (a !in 1..13 || b !in 1..13) return null
        return "${a}X$b"
    }

    // ---------------------------------------------------------------- names

    private val CONF = mapOf('0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B', '2' to 'Z', '6' to 'G', '9' to 'G', '7' to 'T', 'L' to 'I', 'Q' to 'O', 'D' to 'O', 'U' to 'V')
    private fun key(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /** Edit distance where letters the OCR mixes up (0/O, 1/I, 5/S, 8/B …) cost less. */
    private fun dist(a: String, b: String): Double {
        val d = Array(a.length + 1) { DoubleArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i.toDouble()
        for (j in 0..b.length) d[0][j] = j.toDouble()
        for (i in 1..a.length) for (j in 1..b.length) {
            val x = a[i - 1]; val y = b[j - 1]
            val sub = if (x == y) 0.0 else if ((CONF[x] ?: x) == (CONF[y] ?: y)) 0.3 else 1.0
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + sub)
        }
        return d[a.length][b.length]
    }

    /** The known name this reading is a slip of, or null when none is close enough (or two are equally close). */
    fun closest(read: String, known: Collection<String>): String? {
        val k = key(read)
        if (k.length < 2) return null
        val scored = known.map { it to dist(k, key(it)) }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second == 0.0) return best.first
        val allowed = if (k.length <= 4) 0.6 else if (k.length <= 8) 1.3 else 2.3
        val second = scored.getOrNull(1)?.second ?: 99.0
        return best.first.takeIf { best.second <= allowed && second - best.second >= 0.4 }
    }

    // Ditto strokes at the start of the article after the MRP: "—v—", "-1-", "~", "u".
    private val DITTO = Regex("""^(?:[\-—–~=_.|/\\:,]+|[vVuUlLI1nNrwWyY](?=[\s\-—–~=_]|$))+""")

    private fun clean(s: String) = s.replace('—', '-').replace('–', '-').replace('_', '-').replace('~', '-').replace(Regex("""\s+"""), " ").trim()

    private fun stripDitto(s: String): String {
        var t = s.trim()
        while (true) { val m = DITTO.find(t) ?: break; if (m.value.isEmpty()) break; t = t.substring(m.range.last + 1).trim() }
        return t.trim('-', ' ', '.', ',')
    }

    /** "SLIDER-432" → "SLIDER-": what a ditto line with only a new number keeps from the line above. */
    private fun stem(a: String) = a.replace(Regex("""\d+$"""), "")

    // ---------------------------------------------------------------- reading

    private class Line(val y: Double, val cells: Array<MutableList<OcrWord>>)

    fun parse(pages0: List<OcrPage>, learnt: Collection<String> = emptyList()): ParsedInvoice? {
        if (!looksLike(pages0)) return null
        val known = (ARTICLES + learnt).distinct()
        val items = mutableListOf<LineItem>()
        val notes = mutableListOf<String>()
        val bills = mutableListOf<String>()
        var date: String? = null
        var supplier: String? = null
        val pagePairs = mutableListOf<Pair<BigDecimal, BigDecimal?>>()   // pairs read, running total written
        val firstLineOfPage = mutableListOf<Int>()
        for ((pi, pg0) in pages0.withIndex()) {
            val pg = upright(pg0) ?: continue
            val hd = findHeads(pg) ?: continue
            val edges = hd.edges()
            fun col(x: Double) = edges.indexOfFirst { x < it }.let { if (it < 0) edges.size else it }   // 0 article … 4 rate, 5 amount
            val top = pg.words.filter { it.box.b < hd.article.t }
            val topText = top.joinToString(" ") { it.text }
            if (Regex("""(?i)patel""").containsMatchIn(topText) && Regex("""(?i)trad""").containsMatchIn(topText)) supplier = "PATEL TRADING CORPORATION"
            // Bill number: the printed red 4-digit number at the top right.
            val no = top.filter { it.box.cx > pg.width * 0.5 }.mapNotNull { w -> number(w.text)?.takeIf { it.scale() <= 0 && it.toInt() in 1000..99999 }?.let { w to it } }
                .minByOrNull { it.first.box.t }?.second?.toPlainString()
            bills += no ?: "?"
            if (date == null) date = Regex("""(\d{1,2})/(\d{1,2})/(20\d{2})""").find(topText.replace(" ", "").replace("|", "/").replace("\\", "/"))?.let { m ->
                "%02d-%02d-%s".format(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) }
            // The end of the table: the printed words under it.
            val footY = pg.words.filter { n(it.text).let { t -> t.startsWith("received") || t.startsWith("packed") || t.startsWith("checked") || t.startsWith("scanfor") || t == "scan" } && it.box.t > hd.bottom }
                .minOfOrNull { it.box.t }?.toDouble() ?: pg.height.toDouble()
            val body = pg.words.filter { it.box.cy > hd.bottom + 0.3 * hd.h && it.box.cy < footY }
            // Lines: one per number written in the Rate column (or, failing that, the Pair column).
            val rateNums = body.filter { col(it.box.cx) == 4 && number(it.text)?.let { v -> v >= BigDecimal(20) && v <= BigDecimal(9999) } == true }
            val pairNums = body.filter { col(it.box.cx) == 3 && number(it.text)?.let { v -> v >= BigDecimal.ONE && v <= BigDecimal(60) } == true }
            val anchors0 = (rateNums.map { it.box.cy } + pairNums.filter { p -> rateNums.none { abs(it.box.cy - p.box.cy) < hd.h * 0.9 } }.map { it.box.cy }).sorted()
            val pitch = anchors0.zipWithNext { a, b -> b - a }.filter { it > hd.h * 0.6 }.sorted().let { if (it.isEmpty()) hd.h * 1.6 else it[it.size / 2] }
            val anchors = mutableListOf<Double>()
            for (y in anchors0) if (anchors.isEmpty() || y - anchors.last() > pitch * 0.45) anchors += y
            // The running total written under the Pair column, below the last line.
            val lastY = anchors.lastOrNull() ?: continue
            val written = body.filter { it.box.cy > lastY + pitch * 0.55 && col(it.box.cx) in 2..4 }
                .mapNotNull { w -> number(w.text)?.takeIf { it.scale() <= 0 && it.toInt() in 10..99999 } }.maxOrNull()
            val lines = anchors.map { Line(it, Array(6) { mutableListOf<OcrWord>() }) }
            for (w in body) {
                if (w.box.cy > lastY + pitch * 0.55) continue
                val ln = lines.minByOrNull { abs(it.y - w.box.cy) } ?: continue
                if (abs(ln.y - w.box.cy) > pitch * 0.75) continue
                ln.cells[col(w.box.cx)].add(w)
            }
            firstLineOfPage += items.size
            var pagePairsRead = BigDecimal.ZERO
            val prevOnPage = mutableListOf<ItemValues>()
            for (ln in lines) {
                fun text(c: Int) = ln.cells[c].sortedBy { it.box.l }.joinToString(" ") { it.text }
                var art = clean(text(0))
                val colourText = clean(text(1))
                // A colour written so long that it ran into the article column, or the other way round.
                val sizeText = text(2)
                val pair = ln.cells[3].firstNotNullOfOrNull { number(it.text) }
                val rate = ln.cells[4].sortedBy { it.box.l }.firstNotNullOfOrNull { number(it.text) } ?: number(text(4).replace(" ", ""))
                // MRP: the first number written in the Article column.
                var mrp: BigDecimal? = null
                Regex("""^([0-9OoIlSBZgq]{2,4})(?=[\s\-—–~=_.,:]|$)""").find(art)?.let { m ->
                    val v = number(m.value)
                    if (v != null && v >= BigDecimal(50)) { mrp = v; art = art.substring(m.range.last + 1) }
                }
                val prev = prevOnPage.lastOrNull()
                val rest = stripDitto(art)
                var article = when {
                    rest.isEmpty() -> prev?.article ?: ""
                    // "—v— 10" under CLS-09 = CLS-10; "—v— 6227" (4 digits) is an article number of its own.
                    rest.none { it.isLetter() } && prev != null && rest.count { it.isDigit() } in 1..3 -> stem(prev.article) + rest.filter { it.isDigit() }
                    else -> rest.uppercase()
                }
                // A whole ditto line ("—v——" with no MRP of its own) keeps the MRP above.
                if (rest.isEmpty() && mrp == null && prev != null) mrp = prev.mrp
                closest(article, known)?.let { article = it }
                var colour = stripDitto(colourText).uppercase()
                if (colour.isEmpty()) {
                    // "SWISS-116 —BK": the colour written past the column line.
                    val last = article.split(' ', '-').lastOrNull().orEmpty()
                    if (last.length >= 2 && article.length > last.length + 2 && last in COLOURS) { colour = last; article = article.removeSuffix(last).trim('-', ' ') }
                }
                closest(colour, COLOURS)?.let { colour = it }
                val sz = size(sizeText) ?: size(colourText.takeLast(6))
                val needs = mutableSetOf<F>()
                if (article.isBlank()) needs += F.ARTICLE
                if (pair == null || pair.signum() <= 0 || pair > BigDecimal(60)) needs += F.QTY
                if (rate == null) needs += F.RATE
                val m0 = mrp
                if (m0 != null && rate != null && (m0 < rate * BigDecimal("1.05") || m0 > rate * BigDecimal(4))) { needs += F.MRP; needs += F.RATE }
                if (sz == null) needs += F.SIZE
                val q = pair?.takeIf { F.QTY !in needs }
                val amount = if (q != null && rate != null) Calc.r2(q * rate) else null
                if (q != null) pagePairsRead += q
                val v = ItemValues(
                    sr = items.size + 1, article = article, brand = article.split(' ', '-').firstOrNull { it.any(Char::isLetter) }.orEmpty(),
                    colour = colour, sizeText = sz ?: clean(sizeText), qty = q ?: pair, mrp = mrp, rate = rate, gross = amount, taxable = amount, gstPct = BigDecimal.ZERO,
                    notes = no ?: "",
                )
                prevOnPage += v
                val ws = ln.cells.flatMap { it }
                val at = if (ws.isEmpty()) null else LineAt(pi, ws.minOf { it.box.l }, ws.minOf { it.box.t }, ws.maxOf { it.box.r }, ws.maxOf { it.box.b }, pg.width, pg.height)
                items += LineItem("k${items.size}", ItemKind.ARTICLE, v, v, needs, raw = (0..4).map { text(it) }, at = if (pg === pg0) at else null)
            }
            pagePairs += pagePairsRead to written
        }
        if (items.isEmpty()) return null
        // The running total written at the bottom: each bill's pairs = its total − the bill before.
        var printedQty: BigDecimal? = null
        if (pagePairs.isNotEmpty()) {
            val first = pagePairs.first()
            val base = first.second?.let { w -> if (w.compareTo(first.first) == 0) BigDecimal.ZERO else null }
            var before = base
            for ((k, pp) in pagePairs.withIndex()) {
                val (read, w) = pp
                val should = if (w != null && before != null) w - before else null
                if (should != null && should.compareTo(read) != 0) {
                    val i = firstLineOfPage.getOrNull(k) ?: continue
                    val it0 = items[i]
                    val msg = "⚠ बिल ${bills.getOrNull(k) ?: k + 1}: नीचे कुल ${w!!.toPlainString()} लिखा है" + (if (before!!.signum() > 0) " (पिछले तक ${before.toPlainString()})" else "") +
                        " → इस बिल में ${should.toPlainString()} जोड़ी होनी चाहिए, लाइनों में ${read.toPlainString()} पढ़ी — इस बिल की जोड़ी जाँचें"
                    notes += msg
                    items[i] = it0.copy(current = it0.current.copy(notes = it0.current.notes + "|" + msg), original = it0.original.copy(notes = it0.original.notes + "|" + msg))
                }
                before = w ?: before?.let { it + read }
            }
            if (base != null) printedQty = pagePairs.last().second?.let { it - base }
        }
        val no = bills.filter { it != "?" }.let { if (it.isEmpty()) null else if (it.size == 1) it[0] else "${it.first()}-${it.last()}" }
        val header = Header(supplierName = supplier ?: "कच्चा बिल", invoiceNo = no, invoiceDate = date, pageNote = NOTE, printedQty = printedQty)
        val columns = listOf(Column("Article", F.ARTICLE), Column("MRP", F.MRP), Column("Colour", F.COLOUR), Column("Size", F.SIZE),
            Column("Pair", F.QTY), Column("Rate", F.RATE), Column("Amount", F.TAXABLE))
        notes += "कच्चा बिल पढ़ा: ${pages0.size} पेज, ${items.size} लाइन (पहला नंबर = MRP, \"—v—\" = ऊपर जैसा)"
        return ParsedInvoice(header, items, notes, columns)
    }

    /** A warning per bill whose written running total does not fit its lines. */
    fun pairNotes(items: List<LineItem>): List<Pair<String, String>> = items.flatMap { i ->
        i.current.notes.split('|').filter { it.startsWith("⚠") }.map { i.id to it }
    }

    fun billOf(v: ItemValues) = v.notes.substringBefore('|')
}
