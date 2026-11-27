package com.example.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Executive-grade pure-Kotlin Word (.docx) OOXML generator.
 * Zero external dependencies — builds valid .docx archives with styled title headers,
 * KPI summary cards, colored H1/H2 headings with subtle bottom borders, bold-lead-in
 * bullet lists, shaded accent callout boxes, and full-width bordered tables with dark headers.
 */
object StyledDocxGenerator {

    fun generate(blueprint: FileBlueprint, outputFile: File): File {
        outputFile.parentFile?.mkdirs()
        val bytes = generateBytes(blueprint)
        FileOutputStream(outputFile).use { it.write(bytes) }
        return outputFile
    }

    fun generateBytes(blueprint: FileBlueprint): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", buildContentTypesXml())
            writeZipEntry(zos, "_rels/.rels", buildRootRelsXml())
            writeZipEntry(zos, "word/_rels/document.xml.rels", buildDocumentRelsXml())
            writeZipEntry(zos, "word/styles.xml", buildStylesXml(blueprint.accentColorHex))
            writeZipEntry(zos, "word/document.xml", buildDocumentXml(blueprint))
        }
        return baos.toByteArray()
    }

    private fun buildContentTypesXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """</Types>"""

    private fun buildRootRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            """</Relationships>"""

    private fun buildDocumentRelsXml(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            """</Relationships>"""

    private fun buildStylesXml(accentColorHex: String): String {
        val accent = accentColorHex.removePrefix("#").uppercase(Locale.US).ifBlank { "1E3A8A" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>
        <w:color w:val="1E293B"/>
        <w:sz w:val="21"/>
        <w:szCs w:val="21"/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:spacing w:after="120" w:line="276" w:lineRule="auto"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:qFormat/>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/>
    <w:basedOn w:val="Normal"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:spacing w:before="280" w:after="120"/>
      <w:pBdr>
        <w:bottom w:val="single" w:sz="8" w:space="4" w:color="CBD5E1"/>
      </w:pBdr>
    </w:pPr>
    <w:rPr>
      <w:b/>
      <w:color w:val="$accent"/>
      <w:sz w:val="28"/>
      <w:szCs w:val="28"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/>
    <w:basedOn w:val="Normal"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:spacing w:before="220" w:after="80"/>
    </w:pPr>
    <w:rPr>
      <w:b/>
      <w:color w:val="1E293B"/>
      <w:sz w:val="24"/>
      <w:szCs w:val="24"/>
    </w:rPr>
  </w:style>
</w:styles>"""
    }

    private fun buildDocumentXml(blueprint: FileBlueprint): String {
        val accent = blueprint.accentColorHex.removePrefix("#").uppercase(Locale.US).ifBlank { "1E3A8A" }
        val sections = normalizeSections(blueprint)

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            append("""<w:body>""")

            // 1. Category Tag & Generated Date bar
            append(
                """<w:p><w:pPr><w:spacing w:before="0" w:after="60"/></w:pPr>""" +
                    """<w:r><w:rPr><w:b/><w:color w:val="$accent"/><w:sz w:val="18"/></w:rPr>""" +
                    """<w:t xml:space="preserve">${xmlEscape(blueprint.categoryTag)}  •  ${xmlEscape(blueprint.generatedDateLabel)}</w:t></w:r></w:p>"""
            )

            // 2. Executive Title with bottom accent rule
            append(
                """<w:p><w:pPr><w:spacing w:before="0" w:after="100"/>""" +
                    """<w:pBdr><w:bottom w:val="single" w:sz="16" w:space="6" w:color="$accent"/></w:pBdr></w:pPr>""" +
                    """<w:r><w:rPr><w:b/><w:color w:val="0F172A"/><w:sz w:val="40"/></w:rPr>""" +
                    """<w:t>${xmlEscape(blueprint.title)}</w:t></w:r></w:p>"""
            )

            // 3. Subtitle
            if (blueprint.subtitle.isNotBlank()) {
                append(
                    """<w:p><w:pPr><w:spacing w:before="40" w:after="180"/></w:pPr>""" +
                        """<w:r><w:rPr><w:i/><w:color w:val="64748B"/><w:sz w:val="22"/></w:rPr>""" +
                        """<w:t>${xmlEscape(blueprint.subtitle)}</w:t></w:r></w:p>"""
                )
            }

            // 4. Executive Summary box
            if (blueprint.executiveSummary.isNotBlank()) {
                appendCalloutBox(
                    title = "Executive Summary",
                    body = blueprint.executiveSummary,
                    accentHex = accent,
                    fillHex = "F8FAFC"
                )
            }

            // 5. KPI Cards Row (as clean horizontal bordered grid)
            if (blueprint.kpiCards.isNotEmpty()) {
                appendKpiGrid(blueprint.kpiCards.take(4), accent)
            }

            // 6. Structured Sections
            sections.forEach { sec ->
                // Styled Heading 1
                append(
                    """<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr>""" +
                        """<w:r><w:t>${xmlEscape(sec.heading)}</w:t></w:r></w:p>"""
                )

                // Paragraphs
                sec.paragraphs.forEach { para ->
                    appendRichParagraph(para)
                }

                // Bold-lead-in Bullet Items
                sec.bullets.forEach { bullet ->
                    appendBulletItem(bullet, accent)
                }

                // Shaded Callout Box
                sec.callout?.let { callout ->
                    val (borderHex, fillHex) = when (callout.tone.lowercase(Locale.US)) {
                        "warning" -> "B45309" to "FFFBEB"
                        "tip" -> "0F766E" to "F0FDFA"
                        else -> accent to "EEF2FF"
                    }
                    appendCalloutBox(
                        title = callout.title,
                        body = callout.text,
                        accentHex = borderHex,
                        fillHex = fillHex
                    )
                }

                // Full-width Styled Table
                sec.table?.let { table ->
                    appendStyledTable(table, accent)
                }
            }

            // Page setup: A4 portrait with balanced 0.85in margins
            append(
                """<w:sectPr>""" +
                    """<w:pgSz w:w="11906" w:h="16838"/>""" +
                    """<w:pgMar w:top="1080" w:right="1080" w:bottom="1080" w:left="1080" w:header="540" w:footer="540" w:gutter="0"/>""" +
                    """</w:sectPr>"""
            )
            append("""</w:body></w:document>""")
        }
    }

    private fun normalizeSections(blueprint: FileBlueprint): List<BlueprintSection> {
        if (blueprint.sections.isNotEmpty()) return blueprint.sections
        if (blueprint.sheets.isNotEmpty()) {
            return blueprint.sheets.map { sheet ->
                BlueprintSection(
                    heading = sheet.title.ifBlank { sheet.name },
                    paragraphs = listOfNotNull(sheet.subtitle.takeIf { it.isNotBlank() }),
                    table = BlueprintTable(
                        headers = sheet.columns.map { it.header },
                        alignments = sheet.columns.map { col ->
                            when (col.type) {
                                "currency", "number", "percent" -> "right"
                                "date" -> "center"
                                else -> "left"
                            }
                        },
                        rows = sheet.rows,
                        totalRow = sheet.totalRow
                    )
                )
            }
        }
        return listOf(
            BlueprintSection(
                heading = "Report Overview",
                paragraphs = listOf(blueprint.executiveSummary.ifBlank { blueprint.title })
            )
        )
    }

    private fun StringBuilder.appendRichParagraph(rawText: String) {
        append("""<w:p><w:pPr><w:spacing w:after="130" w:line="280" w:lineRule="auto"/></w:pPr>""")
        appendInlineRuns(rawText)
        append("""</w:p>""")
    }

    private fun StringBuilder.appendBulletItem(rawBullet: String, accentHex: String) {
        val cleaned = rawBullet.trim().removePrefix("- ").removePrefix("• ").removePrefix("* ")
        append(
            """<w:p><w:pPr><w:ind w:left="420" w:hanging="220"/><w:spacing w:after="90" w:line="272" w:lineRule="auto"/></w:pPr>""" +
                """<w:r><w:rPr><w:b/><w:color w:val="$accentHex"/></w:rPr><w:t xml:space="preserve">•  </w:t></w:r>"""
        )
        // Support both "**Lead-in:** body" and plain "Lead-in: body"
        if (cleaned.contains("**")) {
            appendInlineRuns(cleaned)
        } else {
            val colonIdx = cleaned.indexOf(':')
            if (colonIdx in 2..48) {
                val lead = cleaned.substring(0, colonIdx + 1)
                val rest = cleaned.substring(colonIdx + 1)
                append("""<w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t xml:space="preserve">${xmlEscape(lead)}</w:t></w:r>""")
                append("""<w:r><w:t xml:space="preserve">${xmlEscape(rest)}</w:t></w:r>""")
            } else {
                append("""<w:r><w:t xml:space="preserve">${xmlEscape(cleaned)}</w:t></w:r>""")
            }
        }
        append("""</w:p>""")
    }

    private fun StringBuilder.appendInlineRuns(text: String) {
        val regex = Regex("""\*\*(.+?)\*\*""")
        var cursor = 0
        regex.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                val plain = text.substring(cursor, match.range.first).replace("**", "")
                append("""<w:r><w:t xml:space="preserve">${xmlEscape(plain)}</w:t></w:r>""")
            }
            val bold = match.groupValues[1]
            append("""<w:r><w:rPr><w:b/><w:color w:val="0F172A"/></w:rPr><w:t xml:space="preserve">${xmlEscape(bold)}</w:t></w:r>""")
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor).replace("**", "")
            append("""<w:r><w:t xml:space="preserve">${xmlEscape(tail)}</w:t></w:r>""")
        }
    }

    private fun StringBuilder.appendCalloutBox(
        title: String,
        body: String,
        accentHex: String,
        fillHex: String
    ) {
        append(
            """<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/>""" +
                """<w:tblBorders>""" +
                """<w:left w:val="single" w:sz="24" w:space="0" w:color="$accentHex"/>""" +
                """<w:top w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>""" +
                """<w:right w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>""" +
                """<w:bottom w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>""" +
                """</w:tblBorders>""" +
                """<w:tblCellMar><w:top w:w="130" w:type="dxa"/><w:bottom w:w="130" w:type="dxa"/><w:left w:w="180" w:type="dxa"/><w:right w:w="160" w:type="dxa"/></w:tblCellMar>""" +
                """</w:tblPr><w:tr><w:tc><w:tcPr><w:tcW w:w="5000" w:type="pct"/><w:shd w:val="clear" w:color="auto" w:fill="$fillHex"/></w:tcPr>""" +
                """<w:p><w:pPr><w:spacing w:before="0" w:after="60"/></w:pPr>""" +
                """<w:r><w:rPr><w:b/><w:color w:val="$accentHex"/><w:sz w:val="20"/></w:rPr><w:t>${xmlEscape(title)}</w:t></w:r></w:p>""" +
                """<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="270" w:lineRule="auto"/></w:pPr>"""
        )
        appendInlineRuns(body)
        append("""</w:p></w:tc></w:tr></w:tbl><w:p><w:pPr><w:spacing w:before="0" w:after="80"/></w:pPr></w:p>""")
    }

    private fun StringBuilder.appendKpiGrid(cards: List<BlueprintKpiCard>, accentHex: String) {
        val colPct = 5000 / cards.size.coerceAtLeast(1)
        append(
            """<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/>""" +
                """<w:tblBorders>""" +
                """<w:top w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>""" +
                """<w:bottom w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>""" +
                """<w:left w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>""" +
                """<w:right w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>""" +
                """<w:insideV w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>""" +
                """</w:tblBorders>""" +
                """<w:tblCellMar><w:top w:w="120" w:type="dxa"/><w:bottom w:w="120" w:type="dxa"/><w:left w:w="140" w:type="dxa"/><w:right w:w="140" w:type="dxa"/></w:tblCellMar>""" +
                """</w:tblPr><w:tr>"""
        )
        cards.forEach { card ->
            append("""<w:tc><w:tcPr><w:tcW w:w="$colPct" w:type="pct"/><w:shd w:val="clear" w:color="auto" w:fill="F8FAFC"/></w:tcPr>""")
            append("""<w:p><w:pPr><w:spacing w:before="0" w:after="40"/></w:pPr><w:r><w:rPr><w:color w:val="64748B"/><w:sz w:val="17"/></w:rPr><w:t>${xmlEscape(card.label)}</w:t></w:r></w:p>""")
            append("""<w:p><w:pPr><w:spacing w:before="0" w:after="30"/></w:pPr><w:r><w:rPr><w:b/><w:color w:val="$accentHex"/><w:sz w:val="28"/></w:rPr><w:t>${xmlEscape(card.value)}</w:t></w:r></w:p>""")
            if (card.subtext.isNotBlank()) {
                append("""<w:p><w:pPr><w:spacing w:before="0" w:after="0"/></w:pPr><w:r><w:rPr><w:color w:val="64748B"/><w:sz w:val="16"/></w:rPr><w:t>${xmlEscape(card.subtext)}</w:t></w:r></w:p>""")
            }
            append("""</w:tc>""")
        }
        append("""</w:tr></w:tbl><w:p><w:pPr><w:spacing w:before="0" w:after="100"/></w:pPr></w:p>""")
    }

    private fun StringBuilder.appendStyledTable(table: BlueprintTable, accentHex: String) {
        val colCount = table.headers.size.coerceAtLeast(table.rows.maxOfOrNull { it.size } ?: 1)
        val rawWeights = if (table.columnWeights.size == colCount) {
            table.columnWeights.map { it.coerceAtLeast(0.5f) }
        } else {
            List(colCount) { 1f }
        }
        val totalWeight = rawWeights.sum().coerceAtLeast(1f)
        val pctWidths = rawWeights.map { ((it / totalWeight) * 5000f).toInt().coerceAtLeast(400) }

        append(
            """<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/>""" +
                """<w:tblBorders>""" +
                """<w:top w:val="single" w:sz="6" w:space="0" w:color="CBD5E1"/>""" +
                """<w:bottom w:val="single" w:sz="8" w:space="0" w:color="$accentHex"/>""" +
                """<w:left w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>""" +
                """<w:right w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>""" +
                """<w:insideH w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>""" +
                """<w:insideV w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>""" +
                """</w:tblBorders>""" +
                """<w:tblCellMar><w:top w:w="95" w:type="dxa"/><w:bottom w:w="95" w:type="dxa"/><w:left w:w="120" w:type="dxa"/><w:right w:w="120" w:type="dxa"/></w:tblCellMar>""" +
                """</w:tblPr>"""
        )

        // Dark Slate Header Row (marked w:tblHeader so Word repeats it across pages)
        if (table.headers.isNotEmpty()) {
            append("""<w:tr><w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>""")
            for (c in 0 until colCount) {
                val align = wordAlignment(table.alignments.getOrElse(c) { "left" })
                val headerText = table.headers.getOrElse(c) { "" }
                append(
                    """<w:tc><w:tcPr><w:tcW w:w="${pctWidths[c]}" w:type="pct"/>""" +
                        """<w:shd w:val="clear" w:color="auto" w:fill="1E293B"/></w:tcPr>""" +
                        """<w:p><w:pPr><w:jc w:val="$align"/><w:spacing w:before="0" w:after="0"/></w:pPr>""" +
                        """<w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/><w:sz w:val="19"/></w:rPr>""" +
                        """<w:t>${xmlEscape(headerText)}</w:t></w:r></w:p></w:tc>"""
                )
            }
            append("""</w:tr>""")
        }

        // Zebra-striped Data Rows
        table.rows.forEachIndexed { rIdx, row ->
            val bg = if (rIdx % 2 == 1) "F8FAFC" else "FFFFFF"
            append("""<w:tr><w:trPr><w:cantSplit/></w:trPr>""")
            for (c in 0 until colCount) {
                val align = wordAlignment(table.alignments.getOrElse(c) { "left" })
                val cellText = row.getOrElse(c) { "" }
                append(
                    """<w:tc><w:tcPr><w:tcW w:w="${pctWidths[c]}" w:type="pct"/>""" +
                        """<w:shd w:val="clear" w:color="auto" w:fill="$bg"/></w:tcPr>""" +
                        """<w:p><w:pPr><w:jc w:val="$align"/><w:spacing w:before="0" w:after="0"/></w:pPr>""" +
                        """<w:r><w:rPr><w:color w:val="1E293B"/><w:sz w:val="19"/></w:rPr>""" +
                        """<w:t>${xmlEscape(cellText)}</w:t></w:r></w:p></w:tc>"""
                )
            }
            append("""</w:tr>""")
        }

        // Bold Tinted Total Row
        if (table.totalRow.isNotEmpty()) {
            append("""<w:tr><w:trPr><w:cantSplit/></w:trPr>""")
            for (c in 0 until colCount) {
                val align = wordAlignment(table.alignments.getOrElse(c) { "left" })
                val cellText = table.totalRow.getOrElse(c) { "" }
                append(
                    """<w:tc><w:tcPr><w:tcW w:w="${pctWidths[c]}" w:type="pct"/>""" +
                        """<w:shd w:val="clear" w:color="auto" w:fill="EEF2FF"/></w:tcPr>""" +
                        """<w:p><w:pPr><w:jc w:val="$align"/><w:spacing w:before="0" w:after="0"/></w:pPr>""" +
                        """<w:r><w:rPr><w:b/><w:color w:val="0F172A"/><w:sz w:val="19"/></w:rPr>""" +
                        """<w:t>${xmlEscape(cellText)}</w:t></w:r></w:p></w:tc>"""
                )
            }
            append("""</w:tr>""")
        }

        append("""</w:tbl><w:p><w:pPr><w:spacing w:before="0" w:after="100"/></w:pPr></w:p>""")
    }

    private fun wordAlignment(raw: String): String = when (raw.lowercase(Locale.US)) {
        "right" -> "right"
        "center" -> "center"
        else -> "left"
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
