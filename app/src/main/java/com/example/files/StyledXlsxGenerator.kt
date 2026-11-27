package com.example.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Business-grade pure-Kotlin multi-sheet OOXML (.xlsx) workbook generator.
 * Zero external dependencies — produces valid Microsoft Excel / Google Sheets files
 * with accounting styles, ₹ currency formats, frozen header rows, auto column widths,
 * zebra striping, and live `<f>` formulas (including cross-sheet references).
 */
object StyledXlsxGenerator {

    // Style indices inside xl/styles.xml <cellXfs>
    private const val STYLE_DEFAULT = 0
    private const val STYLE_TITLE = 1
    private const val STYLE_SUBTITLE = 2
    private const val STYLE_HEADER_LEFT = 3
    private const val STYLE_HEADER_CENTER = 4
    private const val STYLE_HEADER_RIGHT = 5

    // Data rows — White (#FFFFFF)
    private const val STYLE_DATA_TEXT_W = 6
    private const val STYLE_DATA_CURR_W = 7
    private const val STYLE_DATA_NUM_W = 8
    private const val STYLE_DATA_PCT_W = 9
    private const val STYLE_DATA_DATE_W = 10

    // Data rows — Zebra (#F8FAFC)
    private const val STYLE_DATA_TEXT_Z = 11
    private const val STYLE_DATA_CURR_Z = 12
    private const val STYLE_DATA_NUM_Z = 13
    private const val STYLE_DATA_PCT_Z = 14
    private const val STYLE_DATA_DATE_Z = 15

    // Accounting Total Row (#EEF2FF fill, bold, thin top + double bottom border)
    private const val STYLE_TOTAL_TEXT = 16
    private const val STYLE_TOTAL_CURR = 17
    private const val STYLE_TOTAL_NUM = 18
    private const val STYLE_TOTAL_PCT = 19
    private const val STYLE_TOTAL_DATE = 20

    fun generate(blueprint: FileBlueprint, outputFile: File): File {
        outputFile.parentFile?.mkdirs()
        val bytes = generateBytes(blueprint)
        FileOutputStream(outputFile).use { it.write(bytes) }
        return outputFile
    }

    fun generateBytes(blueprint: FileBlueprint): ByteArray {
        val sheets = normalizeSheets(blueprint)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", buildContentTypesXml(sheets.size))
            writeZipEntry(zos, "_rels/.rels", buildRootRelsXml())
            writeZipEntry(zos, "xl/workbook.xml", buildWorkbookXml(sheets))
            writeZipEntry(zos, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml(sheets.size))
            writeZipEntry(zos, "xl/styles.xml", buildStylesXml(blueprint.accentColorHex))

            sheets.forEachIndexed { idx, sheet ->
                val sheetXml = buildWorksheetXml(sheet = sheet, isTabSelected = idx == 0)
                writeZipEntry(zos, "xl/worksheets/sheet${idx + 1}.xml", sheetXml)
            }
        }
        return baos.toByteArray()
    }

    /**
     * Ensures at least one rich sheet exists even if the AI blueprint populated sections instead of sheets,
     * and ensures unique sanitized Excel sheet names.
     */
    internal fun normalizeSheets(blueprint: FileBlueprint): List<BlueprintSheet> {
        val rawSheets = if (blueprint.sheets.isNotEmpty()) {
            blueprint.sheets
        } else {
            val fromTables = blueprint.sections.mapNotNull { sec ->
                val tbl = sec.table ?: return@mapNotNull null
                val cols = tbl.headers.mapIndexed { idx, h ->
                    val colType = inferColumnType(h, tbl.rows.mapNotNull { it.getOrNull(idx) })
                    BlueprintColumn(
                        header = h,
                        type = colType,
                        alignment = tbl.alignments.getOrElse(idx) { "" }
                    )
                }
                BlueprintSheet(
                    name = FileBlueprintParser.sanitizeSheetName(sec.heading),
                    title = sec.heading,
                    subtitle = blueprint.subtitle,
                    columns = cols,
                    rows = tbl.rows,
                    totalRow = tbl.totalRow
                )
            }
            if (fromTables.isNotEmpty()) {
                fromTables
            } else {
                listOf(
                    BlueprintSheet(
                        name = "Summary",
                        title = blueprint.title,
                        subtitle = blueprint.subtitle.ifBlank { blueprint.executiveSummary },
                        columns = listOf(
                            BlueprintColumn("Section", "text"),
                            BlueprintColumn("Details", "text")
                        ),
                        rows = buildList {
                            blueprint.kpiCards.forEach { add(listOf(it.label, "${it.value} ${it.subtext}".trim())) }
                            blueprint.sections.forEach { sec ->
                                sec.paragraphs.forEach { add(listOf(sec.heading, it)) }
                                sec.bullets.forEach { add(listOf(sec.heading, it.replace("**", ""))) }
                            }
                            if (isEmpty()) {
                                add(listOf("Overview", blueprint.executiveSummary.ifBlank { blueprint.title }))
                            }
                        }
                    )
                )
            }
        }

        val usedNames = mutableSetOf<String>()
        return rawSheets.mapIndexed { idx, sheet ->
            var base = FileBlueprintParser.sanitizeSheetName(sheet.name.ifBlank { "Sheet${idx + 1}" })
            var unique = base
            var suffix = 2
            while (!usedNames.add(unique.lowercase(Locale.US))) {
                val tail = " $suffix"
                unique = base.take(31 - tail.length) + tail
                suffix++
            }
            sheet.copy(name = unique)
        }
    }

    private fun inferColumnType(header: String, sampleValues: List<String>): String {
        val h = header.lowercase(Locale.US)
        if (h.contains("₹") || h.contains("inr") || h.contains("amount") || h.contains("cost") ||
            h.contains("price") || h.contains("budget") || h.contains("expense") || h.contains("salary") ||
            h.contains("income") || h.contains("total") || h.contains("fee") || h.contains("spend")
        ) return "currency"
        if (h.contains("%") || h.contains("percent") || h.contains("share") || h.contains("rate") || h.contains("ratio")) {
            return "percent"
        }
        if (h.contains("date") || h.contains("day") && sampleValues.any { it.contains("-") }) return "date"
        if (sampleValues.any { it.contains("₹") }) return "currency"
        if (sampleValues.any { it.endsWith("%") }) return "percent"
        if (sampleValues.isNotEmpty() && sampleValues.all { parseCleanNumber(it) != null || it.startsWith("=") }) {
            return "number"
        }
        return "text"
    }

    private fun buildContentTypesXml(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("""</Types>""")
    }

    private fun buildRootRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun buildWorkbookXml(sheets: List<BlueprintSheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<bookViews><workbookView xWindow="0" yWindow="0" windowWidth="24000" windowHeight="14000"/></bookViews>""")
        append("""<sheets>""")
        sheets.forEachIndexed { idx, sheet ->
            val sheetId = idx + 1
            append("""<sheet name="${xmlEscape(sheet.name)}" sheetId="$sheetId" r:id="rId$sheetId"/>""")
        }
        append("""</sheets>""")
        append("""<calcPr calcId="191029" fullCalcOnLoad="1"/>""")
        append("""</workbook>""")
    }

    private fun buildWorkbookRelsXml(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("""</Relationships>""")
    }

    private fun buildStylesXml(accentColorHex: String): String {
        val accentRgb = accentColorHex.removePrefix("#").uppercase(Locale.US).ifBlank { "1E3A8A" }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

            // Custom number formats: currency (₹), integer/number, percent, date
            append("""<numFmts count="4">""")
            append("""<numFmt numFmtId="164" formatCode="&quot;₹&quot;#,##0.00"/>""")
            append("""<numFmt numFmtId="165" formatCode="#,##0.##"/>""")
            append("""<numFmt numFmtId="166" formatCode="0.0%"/>""")
            append("""<numFmt numFmtId="167" formatCode="dd-mmm-yyyy"/>""")
            append("""</numFmts>""")

            // Fonts: 0=body, 1=title 14pt bold navy, 2=subtitle 10pt italic gray, 3=header 10.5pt bold white, 4=total 10.5pt bold dark
            append("""<fonts count="5">""")
            append("""<font><sz val="10"/><color rgb="FF0F172A"/><name val="Calibri"/></font>""")
            append("""<font><b/><sz val="14"/><color rgb="FF$accentRgb"/><name val="Calibri"/></font>""")
            append("""<font><i/><sz val="10"/><color rgb="FF64748B"/><name val="Calibri"/></font>""")
            append("""<font><b/><sz val="10.5"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""")
            append("""<font><b/><sz val="10.5"/><color rgb="FF1E293B"/><name val="Calibri"/></font>""")
            append("""</fonts>""")

            // Fills: 0=none, 1=gray125 (OOXML required), 2=header #1E293B, 3=white #FFFFFF, 4=zebra #F8FAFC, 5=total #EEF2FF
            append("""<fills count="6">""")
            append("""<fill><patternFill patternType="none"/></fill>""")
            append("""<fill><patternFill patternType="gray125"/></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FF1E293B"/><bgColor indexed="64"/></patternFill></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FFFFFFFF"/><bgColor indexed="64"/></patternFill></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FFF8FAFC"/><bgColor indexed="64"/></patternFill></fill>""")
            append("""<fill><patternFill patternType="solid"><fgColor rgb="FFEEF2FF"/><bgColor indexed="64"/></patternFill></fill>""")
            append("""</fills>""")

            // Borders: 0=none, 1=data thin #CBD5E1, 2=header thin #1E293B, 3=accounting total (thin top + double bottom)
            append("""<borders count="4">""")
            append("""<border><left/><right/><top/><bottom/><diagonal/></border>""")
            append(
                """<border>""" +
                    """<left style="thin"><color rgb="FFCBD5E1"/></left>""" +
                    """<right style="thin"><color rgb="FFCBD5E1"/></right>""" +
                    """<top style="thin"><color rgb="FFCBD5E1"/></top>""" +
                    """<bottom style="thin"><color rgb="FFCBD5E1"/></bottom>""" +
                    """<diagonal/></border>"""
            )
            append(
                """<border>""" +
                    """<left style="thin"><color rgb="FF334155"/></left>""" +
                    """<right style="thin"><color rgb="FF334155"/></right>""" +
                    """<top style="thin"><color rgb="FF1E293B"/></top>""" +
                    """<bottom style="medium"><color rgb="FF$accentRgb"/></bottom>""" +
                    """<diagonal/></border>"""
            )
            append(
                """<border>""" +
                    """<left style="thin"><color rgb="FFCBD5E1"/></left>""" +
                    """<right style="thin"><color rgb="FFCBD5E1"/></right>""" +
                    """<top style="thin"><color rgb="FF1E293B"/></top>""" +
                    """<bottom style="double"><color rgb="FF1E293B"/></bottom>""" +
                    """<diagonal/></border>"""
            )
            append("""</borders>""")

            append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")

            // CellXfs matching style constants 0..20
            append("""<cellXfs count="21">""")
            // 0: default
            append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""")
            // 1: title
            append("""<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>""")
            // 2: subtitle
            append("""<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>""")
            // 3, 4, 5: headers (left, center, right)
            append("""<xf numFmtId="0" fontId="3" fillId="2" borderId="2" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" wrapText="1"/></xf>""")
            append("""<xf numFmtId="0" fontId="3" fillId="2" borderId="2" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>""")
            append("""<xf numFmtId="0" fontId="3" fillId="2" borderId="2" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center" wrapText="1"/></xf>""")

            // 6..10: white data rows (text, currency, number, percent, date)
            appendDataXf(numFmtId = 0, fontId = 0, fillId = 3, borderId = 1, horiz = "left")
            appendDataXf(numFmtId = 164, fontId = 0, fillId = 3, borderId = 1, horiz = "right")
            appendDataXf(numFmtId = 165, fontId = 0, fillId = 3, borderId = 1, horiz = "right")
            appendDataXf(numFmtId = 166, fontId = 0, fillId = 3, borderId = 1, horiz = "right")
            appendDataXf(numFmtId = 167, fontId = 0, fillId = 3, borderId = 1, horiz = "center")

            // 11..15: zebra data rows
            appendDataXf(numFmtId = 0, fontId = 0, fillId = 4, borderId = 1, horiz = "left")
            appendDataXf(numFmtId = 164, fontId = 0, fillId = 4, borderId = 1, horiz = "right")
            appendDataXf(numFmtId = 165, fontId = 0, fillId = 4, borderId = 1, horiz = "right")
            appendDataXf(numFmtId = 166, fontId = 0, fillId = 4, borderId = 1, horiz = "right")
            appendDataXf(numFmtId = 167, fontId = 0, fillId = 4, borderId = 1, horiz = "center")

            // 16..20: accounting total rows (bold, #EEF2FF fill, thin top + double bottom border)
            appendDataXf(numFmtId = 0, fontId = 4, fillId = 5, borderId = 3, horiz = "left")
            appendDataXf(numFmtId = 164, fontId = 4, fillId = 5, borderId = 3, horiz = "right")
            appendDataXf(numFmtId = 165, fontId = 4, fillId = 5, borderId = 3, horiz = "right")
            appendDataXf(numFmtId = 166, fontId = 4, fillId = 5, borderId = 3, horiz = "right")
            appendDataXf(numFmtId = 167, fontId = 4, fillId = 5, borderId = 3, horiz = "center")

            append("""</cellXfs>""")
            append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
            append("""</styleSheet>""")
        }
    }

    private fun StringBuilder.appendDataXf(
        numFmtId: Int,
        fontId: Int,
        fillId: Int,
        borderId: Int,
        horiz: String
    ) {
        append(
            """<xf numFmtId="$numFmtId" fontId="$fontId" fillId="$fillId" borderId="$borderId" xfId="0" """ +
                """applyNumberFormat="${if (numFmtId > 0) 1 else 0}" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">""" +
                """<alignment horizontal="$horiz" vertical="center" wrapText="1"/></xf>"""
        )
    }

    private fun buildWorksheetXml(sheet: BlueprintSheet, isTabSelected: Boolean): String {
        val colCount = max(1, sheet.columns.size)
        val colWidths = calculateColumnWidths(sheet, colCount)
        val headerRowNumber = 4
        val firstDataRow = 5
        val lastDataRow = firstDataRow + max(0, sheet.rows.size - 1)
        val totalRowValues = resolveTotalRow(sheet, firstDataRow, lastDataRow)

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")

            // Freeze title + header rows (ySplit = 4 -> data starts at A5)
            append("""<sheetViews><sheetView ${if (isTabSelected) """tabSelected="1" """ else ""}workbookViewId="0">""")
            append("""<pane ySplit="$headerRowNumber" topLeftCell="A${headerRowNumber + 1}" activePane="bottomLeft" state="frozen"/>""")
            append("""</sheetView></sheetViews>""")
            append("""<sheetFormatPr defaultRowHeight="20"/>""")

            // Auto-sized generous column widths (12..42) preventing ### truncation
            append("""<cols>""")
            colWidths.forEachIndexed { idx, w ->
                val c = idx + 1
                append("""<col min="$c" max="$c" width="${String.format(Locale.US, "%.1f", w)}" customWidth="1"/>""")
            }
            append("""</cols>""")

            append("""<sheetData>""")

            // Row 1: Executive Title
            val titleText = sheet.title.ifBlank { sheet.name }
            append("""<row r="1" ht="28" customHeight="1">""")
            appendInlineStringCell("A1", titleText, STYLE_TITLE)
            append("""</row>""")

            // Row 2: Muted Subtitle
            val subText = sheet.subtitle.ifBlank { "Generated by My Personal Agent" }
            append("""<row r="2" ht="19" customHeight="1">""")
            appendInlineStringCell("A2", subText, STYLE_SUBTITLE)
            append("""</row>""")

            // Row 3: Clean visual spacer
            append("""<row r="3" ht="10" customHeight="1"/>""")

            // Row 4: Dark Slate Table Header Row
            append("""<row r="$headerRowNumber" ht="26" customHeight="1">""")
            sheet.columns.forEachIndexed { colIdx, col ->
                val ref = "${columnIndexToLetter(colIdx)}$headerRowNumber"
                val style = when {
                    col.alignment == "right" || col.type in setOf("currency", "number", "percent") -> STYLE_HEADER_RIGHT
                    col.alignment == "center" || col.type == "date" -> STYLE_HEADER_CENTER
                    else -> STYLE_HEADER_LEFT
                }
                appendInlineStringCell(ref, col.header, style)
            }
            append("""</row>""")

            // Data Rows starting at Row 5
            sheet.rows.forEachIndexed { rowOffset, rowValues ->
                val rowNum = firstDataRow + rowOffset
                val isZebra = rowOffset % 2 == 1
                val rowHeight = estimateRowHeight(rowValues, colWidths)
                append("""<row r="$rowNum" ht="${String.format(Locale.US, "%.1f", rowHeight)}" customHeight="1">""")
                for (colIdx in 0 until colCount) {
                    val col = sheet.columns[colIdx]
                    val rawVal = rowValues.getOrElse(colIdx) { "" }.trim()
                    val ref = "${columnIndexToLetter(colIdx)}$rowNum"
                    val style = dataStyleFor(col.type, isZebra)
                    appendTypedCell(ref, rawVal, col.type, style)
                }
                append("""</row>""")
            }

            // Accounting Total Row (with live Excel <f> formulas)
            if (totalRowValues.isNotEmpty()) {
                val totalRowNum = firstDataRow + sheet.rows.size
                append("""<row r="$totalRowNum" ht="25" customHeight="1">""")
                for (colIdx in 0 until colCount) {
                    val col = sheet.columns[colIdx]
                    val rawVal = totalRowValues.getOrElse(colIdx) { "" }.trim()
                    val ref = "${columnIndexToLetter(colIdx)}$totalRowNum"
                    val style = totalStyleFor(col.type)
                    appendTypedCell(ref, rawVal, col.type, style)
                }
                append("""</row>""")
            }

            append("""</sheetData>""")

            // Merge title & subtitle across all columns cleanly
            if (colCount > 1) {
                val lastCol = columnIndexToLetter(colCount - 1)
                append("""<mergeCells count="2">""")
                append("""<mergeCell ref="A1:${lastCol}1"/>""")
                append("""<mergeCell ref="A2:${lastCol}2"/>""")
                append("""</mergeCells>""")
            }

            append("""<pageMargins left="0.5" right="0.5" top="0.7" bottom="0.7" header="0.3" footer="0.3"/>""")
            append("""</worksheet>""")
        }
    }

    private fun resolveTotalRow(
        sheet: BlueprintSheet,
        firstDataRow: Int,
        lastDataRow: Int
    ): List<String> {
        if (sheet.rows.isEmpty()) return emptyList()
        if (sheet.totalRow.isNotEmpty()) {
            // Preserve explicit formulas/labels and backfill missing numeric totals with real =SUM() formulas
            return sheet.columns.mapIndexed { idx, col ->
                val provided = sheet.totalRow.getOrElse(idx) { "" }.trim()
                if (provided.isNotBlank()) {
                    provided
                } else if (idx == 0) {
                    "Total"
                } else if (col.type == "currency" || col.type == "number") {
                    val letter = columnIndexToLetter(idx)
                    "=SUM($letter$firstDataRow:$letter$lastDataRow)"
                } else {
                    ""
                }
            }
        }
        // Automatically synthesize an accounting total row if sheet contains numeric/currency columns
        val hasNumeric = sheet.columns.any { it.type == "currency" || it.type == "number" }
        if (!hasNumeric) return emptyList()
        return sheet.columns.mapIndexed { idx, col ->
            when {
                idx == 0 -> "Total"
                col.type == "currency" || col.type == "number" -> {
                    val letter = columnIndexToLetter(idx)
                    "=SUM($letter$firstDataRow:$letter$lastDataRow)"
                }
                else -> ""
            }
        }
    }

    private fun StringBuilder.appendTypedCell(
        ref: String,
        rawVal: String,
        colType: String,
        styleIdx: Int
    ) {
        if (rawVal.isBlank()) {
            append("""<c r="$ref" s="$styleIdx"/>""")
            return
        }

        // Real Excel formula (<f>...</f>) — works for =SUM(...), =AVERAGE(...), =SUM(Details!C5:C20), etc.
        if (rawVal.startsWith("=")) {
            val expr = rawVal.removePrefix("=").trim()
            if (expr.isNotBlank()) {
                append("""<c r="$ref" s="$styleIdx"><f>${xmlEscape(expr)}</f></c>""")
                return
            }
        }

        // Numeric / currency / percent value
        if (colType in setOf("currency", "number", "percent")) {
            val parsed = parseCleanNumber(rawVal, isPercent = colType == "percent")
            if (parsed != null) {
                append("""<c r="$ref" s="$styleIdx"><v>$parsed</v></c>""")
                return
            }
        }

        // Default: inline string (no sharedStrings mismatch bugs)
        appendInlineStringCell(ref, rawVal, styleIdx)
    }

    private fun StringBuilder.appendInlineStringCell(ref: String, text: String, styleIdx: Int) {
        append("""<c r="$ref" s="$styleIdx" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(text)}</t></is></c>""")
    }

    private fun dataStyleFor(colType: String, zebra: Boolean): Int = when (colType) {
        "currency" -> if (zebra) STYLE_DATA_CURR_Z else STYLE_DATA_CURR_W
        "number" -> if (zebra) STYLE_DATA_NUM_Z else STYLE_DATA_NUM_W
        "percent" -> if (zebra) STYLE_DATA_PCT_Z else STYLE_DATA_PCT_W
        "date" -> if (zebra) STYLE_DATA_DATE_Z else STYLE_DATA_DATE_W
        else -> if (zebra) STYLE_DATA_TEXT_Z else STYLE_DATA_TEXT_W
    }

    private fun totalStyleFor(colType: String): Int = when (colType) {
        "currency" -> STYLE_TOTAL_CURR
        "number" -> STYLE_TOTAL_NUM
        "percent" -> STYLE_TOTAL_PCT
        "date" -> STYLE_TOTAL_DATE
        else -> STYLE_TOTAL_TEXT
    }

    private fun calculateColumnWidths(sheet: BlueprintSheet, colCount: Int): List<Double> {
        return List(colCount) { idx ->
            val col = sheet.columns[idx]
            var maxLen = col.header.length + 4
            sheet.rows.forEach { row ->
                val raw = row.getOrElse(idx) { "" }
                val effectiveLen = when {
                    raw.startsWith("=") && col.type == "currency" -> 16
                    raw.startsWith("=") -> 14
                    col.type == "currency" -> max(raw.length + 5, 14)
                    else -> raw.split('\n').maxOfOrNull { it.length } ?: raw.length
                }
                if (effectiveLen > maxLen) maxLen = effectiveLen
            }
            val totalCell = sheet.totalRow.getOrElse(idx) { "" }
            if (totalCell.isNotBlank() && !totalCell.startsWith("=")) {
                maxLen = max(maxLen, totalCell.length + 3)
            }
            val hint = col.widthHint?.toDouble()
            val computed = (maxLen * 1.15 + 3.0)
            (hint ?: computed).coerceIn(12.0, 42.0)
        }
    }

    private fun estimateRowHeight(rowValues: List<String>, colWidths: List<Double>): Double {
        var maxLines = 1
        rowValues.forEachIndexed { idx, value ->
            if (!value.startsWith("=") && value.isNotBlank()) {
                val widthChars = (colWidths.getOrElse(idx) { 16.0 } - 2.0).coerceAtLeast(10.0)
                val lines = value.split('\n').sumOf { seg ->
                    max(1, ceil(seg.length / widthChars).toInt())
                }
                if (lines > maxLines) maxLines = lines
            }
        }
        return (maxLines.coerceAtMost(5) * 16.0 + 6.0).coerceAtLeast(21.0)
    }

    internal fun parseCleanNumber(raw: String, isPercent: Boolean = false): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("=")) return null
        val hadPercentSign = trimmed.endsWith("%")
        val cleaned = trimmed
            .replace("₹", "")
            .replace("INR", "", ignoreCase = true)
            .replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true)
            .replace("$", "")
            .replace(",", "")
            .replace("%", "")
            .trim()
        val num = cleaned.toDoubleOrNull() ?: return null
        return if (isPercent && hadPercentSign) num / 100.0 else num
    }

    internal fun columnIndexToLetter(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append(('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun writeZipEntry(zos: ZipOutputStream, entryName: String, content: String) {
        zos.putNextEntry(ZipEntry(entryName))
        zos.write(content.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    private fun xmlEscape(input: String): String = buildString(input.length + 12) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') append(ch)
            }
        }
    }
}
