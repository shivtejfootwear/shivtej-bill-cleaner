package com.shivtej.billscanner.core.export

import java.io.OutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Cell styles available in the workbook (index = position in cellXfs). */
enum class St(val xf: Int) {
    NORMAL(0), HEADER(1), MONEY(2), PCT(3), INT(4), TEXT(5), BILL_PCT(6), TITLE(7), WARN(8), LABEL(9), HEADER_ORANGE(10),
    HEADER_GREEN(11), GREEN_PCT(12), GREEN_MONEY(13), EDIT_PCT(14), BOLD_MONEY(15), BOLD_TEXT(16), GREEN_INT(17), NUM(18),
}

sealed class Cell {
    data class Text(val s: String, val st: St = St.TEXT) : Cell()
    data class Num(val v: BigDecimal, val st: St = St.MONEY) : Cell()
    /** A formula with its value already worked out, so the file shows numbers even before Excel recalculates. */
    data class Formula(val f: String, val cached: BigDecimal?, val st: St = St.MONEY) : Cell()
    data object Empty : Cell()
}

class Sheet(val name: String) {
    val rows = mutableListOf<List<Cell>>()
    var widths: List<Double> = emptyList()
    var freezeRows = 0
    /** Row (1-based) that holds the filter buttons, 0 = none. */
    var filterRow = 0
    fun row(vararg c: Cell) { rows += c.toList() }
    fun row(c: List<Cell>) { rows += c }
}

/**
 * Minimal, standards-compliant .xlsx writer (Office Open XML, SpreadsheetML).
 * Opens in Microsoft Excel, Google Sheets and LibreOffice.
 */
object Xlsx {
    fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }

    fun write(sheets: List<Sheet>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", contentTypes(sheets.size))
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", workbook(sheets))
            put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheet(s)) }
        }
    }

    private fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;"); ch == '<' -> append("&lt;"); ch == '>' -> append("&gt;"); ch == '"' -> append("&quot;")
            ch.code < 0x20 && ch != '\n' && ch != '\t' -> {}
            else -> append(ch)
        }
    }

    private fun contentTypes(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..n) append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets>")
        // Filter ranges must also be declared as hidden defined names for Excel.
        val filters = sheets.withIndex().filter { it.value.filterRow > 0 && it.value.rows.size > it.value.filterRow }
        if (filters.isNotEmpty()) {
            append("<definedNames>")
            for ((i, s) in filters) append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${esc(s.name.take(31))}'!${filterRef(s, true)}</definedName>""")
            append("</definedNames>")
        }
        append("""<calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>""")
    }

    private fun filterRef(s: Sheet, abs: Boolean): String {
        val cols = s.rows.getOrNull(s.filterRow - 1)?.size ?: 1
        val d = if (abs) "$" else ""
        return "$d${col(0)}$d${s.filterRow}:$d${col(cols - 1)}$d${s.rows.size}"
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
    }

    private fun sheet(s: Sheet) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        if (s.freezeRows > 0) {
            append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="${s.freezeRows}" topLeftCell="A${s.freezeRows + 1}" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft"/></sheetView></sheetViews>""")
        }
        if (s.widths.isNotEmpty()) {
            append("<cols>")
            s.widths.forEachIndexed { i, w -> append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
            append("</cols>")
        }
        append("<sheetData>")
        s.rows.forEachIndexed { ri, cells ->
            val r = ri + 1
            append("""<row r="$r">""")
            cells.forEachIndexed { ci, c ->
                val ref = "${col(ci)}$r"
                when (c) {
                    is Cell.Text -> append("""<c r="$ref" t="inlineStr" s="${c.st.xf}"><is><t xml:space="preserve">${esc(c.s)}</t></is></c>""")
                    is Cell.Num -> append("""<c r="$ref" s="${c.st.xf}"><v>${c.v.toPlainString()}</v></c>""")
                    is Cell.Formula -> append("""<c r="$ref" s="${c.st.xf}"><f>${esc(c.f)}</f>${c.cached?.let { "<v>${it.toPlainString()}</v>" } ?: ""}</c>""")
                    Cell.Empty -> {}
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        if (s.filterRow > 0 && s.rows.size > s.filterRow) append("""<autoFilter ref="${filterRef(s, false)}"/>""")
        append("""<pageMargins left="0.4" right="0.4" top="0.5" bottom="0.5" header="0.3" footer="0.3"/>""")
        append("""<pageSetup orientation="landscape" paperSize="9" fitToWidth="1" fitToHeight="0"/>""")
        append("</worksheet>")
    }

    // Navy header, orange for the supplier's bill discount column, ₹ money and 2-decimal percentages.
    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="4"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="0.00&quot;%&quot;"/><numFmt numFmtId="166" formatCode="0"/><numFmt numFmtId="167" formatCode="#,##0.##"/></numFmts>
<fonts count="7"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFB45309"/><name val="Calibri"/></font><font><b/><sz val="14"/><color rgb="FF0E2A47"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FF0E2A47"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FF166534"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="8"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0E2A47"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF1E2"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF28A1E"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FF15803D"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE7F6EC"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFF3C4"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD5DCE6"/></left><right style="thin"><color rgb="FFD5DCE6"/></right><top style="thin"><color rgb="FFD5DCE6"/></top><bottom style="thin"><color rgb="FFD5DCE6"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="19">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="center" horizontal="center"/></xf>
<xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="166" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>
<xf numFmtId="165" fontId="2" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment wrapText="1"/></xf>
<xf numFmtId="0" fontId="4" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/>
<xf numFmtId="0" fontId="1" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="center" horizontal="center"/></xf>
<xf numFmtId="0" fontId="1" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment wrapText="1" vertical="center" horizontal="center"/></xf>
<xf numFmtId="165" fontId="5" fillId="6" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="164" fontId="5" fillId="6" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="165" fontId="6" fillId="7" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="164" fontId="6" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyBorder="1"/>
<xf numFmtId="0" fontId="6" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1"/>
<xf numFmtId="166" fontId="5" fillId="6" borderId="1" xfId="0" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"/>
<xf numFmtId="167" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
