package com.example.files

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin RFC-4180 CSV generator with UTF-8 BOM (\uFEFF) so Microsoft Excel
 * and Google Sheets open Indian Rupee (₹) symbols, commas, and accents cleanly.
 */
object CsvGenerator {

    fun generate(blueprint: FileBlueprint, outputFile: File): File {
        outputFile.parentFile?.mkdirs()
        val csvContent = buildCsvString(blueprint)
        FileOutputStream(outputFile).use { fos ->
            // UTF-8 BOM for Microsoft Excel compatibility with ₹ and Unicode
            fos.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            fos.write(csvContent.toByteArray(StandardCharsets.UTF_8))
        }
        return outputFile
    }

    fun buildCsvString(blueprint: FileBlueprint): String {
        val sb = StringBuilder()

        val primarySheet = blueprint.sheets.firstOrNull()
        val primaryTable = blueprint.sections.firstNotNullOfOrNull { it.table }

        if (primarySheet != null) {
            appendRow(sb, primarySheet.columns.map { it.header })
            primarySheet.rows.forEach { row ->
                appendRow(sb, normalizeRowWidth(row, primarySheet.columns.size))
            }
            if (primarySheet.totalRow.isNotEmpty()) {
                appendRow(sb, normalizeRowWidth(primarySheet.totalRow, primarySheet.columns.size))
            }
        } else if (primaryTable != null) {
            appendRow(sb, primaryTable.headers)
            primaryTable.rows.forEach { row ->
                appendRow(sb, normalizeRowWidth(row, primaryTable.headers.size))
            }
            if (primaryTable.totalRow.isNotEmpty()) {
                appendRow(sb, normalizeRowWidth(primaryTable.totalRow, primaryTable.headers.size))
            }
        } else {
            appendRow(sb, listOf("Section", "Detail"))
            if (blueprint.executiveSummary.isNotBlank()) {
                appendRow(sb, listOf("Executive Summary", blueprint.executiveSummary))
            }
            blueprint.kpiCards.forEach { kpi ->
                appendRow(sb, listOf(kpi.label, "${kpi.value}${if (kpi.subtext.isNotBlank()) " (${kpi.subtext})" else ""}"))
            }
            blueprint.sections.forEach { sec ->
                sec.paragraphs.forEach { p -> appendRow(sb, listOf(sec.heading, p)) }
                sec.bullets.forEach { b -> appendRow(sb, listOf(sec.heading, b.replace("**", ""))) }
            }
        }

        return sb.toString()
    }

    private fun normalizeRowWidth(row: List<String>, targetWidth: Int): List<String> {
        if (targetWidth <= 0) return row
        return List(targetWidth) { idx -> row.getOrElse(idx) { "" } }
    }

    private fun appendRow(sb: StringBuilder, cells: List<String>) {
        cells.forEachIndexed { idx, cell ->
            if (idx > 0) sb.append(',')
            sb.append(escapeRfc4180(cell))
        }
        sb.append("\r\n")
    }

    fun escapeRfc4180(value: String): String {
        val needsQuoting = value.contains(',') ||
            value.contains('"') ||
            value.contains('\n') ||
            value.contains('\r')
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
