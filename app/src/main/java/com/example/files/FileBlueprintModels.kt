package com.example.files

import com.squareup.moshi.Moshi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GeneratedFileFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
    val badgeLabel: String,
    val spokenLabel: String,
    val defaultAccentHex: String
) {
    PDF(
        id = "pdf",
        extension = "pdf",
        mimeType = "application/pdf",
        badgeLabel = "PDF",
        spokenLabel = "PDF",
        defaultAccentHex = "#1E3A8A"
    ),
    XLSX(
        id = "xlsx",
        extension = "xlsx",
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        badgeLabel = "XLSX",
        spokenLabel = "Excel",
        defaultAccentHex = "#0F766E"
    ),
    DOCX(
        id = "docx",
        extension = "docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        badgeLabel = "DOCX",
        spokenLabel = "Word",
        defaultAccentHex = "#1E3A8A"
    ),
    CSV(
        id = "csv",
        extension = "csv",
        mimeType = "text/csv",
        badgeLabel = "CSV",
        spokenLabel = "CSV",
        defaultAccentHex = "#0F766E"
    );

    companion object {
        fun fromString(raw: String?): GeneratedFileFormat {
            val lower = raw.orEmpty().trim().lowercase(Locale.US)
            return when {
                lower == "xlsx" || lower == "xls" || lower.contains("excel") || lower.contains("spreadsheet") || lower.contains("sheet") -> XLSX
                lower == "docx" || lower == "doc" || lower.contains("word") -> DOCX
                lower == "csv" -> CSV
                else -> PDF
            }
        }
    }
}

data class BlueprintKpiCard(
    val label: String,
    val value: String,
    val subtext: String = ""
)

data class BlueprintCallout(
    val title: String = "Key Insight",
    val text: String,
    val tone: String = "info" // info | tip | warning
)

data class BlueprintTable(
    val headers: List<String>,
    val columnWeights: List<Float> = emptyList(),
    val alignments: List<String> = emptyList(), // left | center | right
    val rows: List<List<String>>,
    val totalRow: List<String> = emptyList()
)

data class BlueprintSection(
    val heading: String,
    val paragraphs: List<String> = emptyList(),
    val bullets: List<String> = emptyList(),
    val callout: BlueprintCallout? = null,
    val table: BlueprintTable? = null
)

data class BlueprintColumn(
    val header: String,
    val type: String = "text", // text | currency | number | percent | date
    val alignment: String = "",
    val widthHint: Int? = null
)

data class BlueprintSheet(
    val name: String,
    val title: String = "",
    val subtitle: String = "",
    val columns: List<BlueprintColumn>,
    val rows: List<List<String>>,
    val totalRow: List<String> = emptyList(),
    val kpiSummary: List<BlueprintKpiCard> = emptyList()
)

data class FileBlueprint(
    val fileType: GeneratedFileFormat,
    val fileName: String,
    val title: String,
    val subtitle: String = "",
    val categoryTag: String = "EXECUTIVE REPORT",
    val accentColorHex: String = "#1E3A8A",
    val executiveSummary: String = "",
    val kpiCards: List<BlueprintKpiCard> = emptyList(),
    val sections: List<BlueprintSection> = emptyList(),
    val sheets: List<BlueprintSheet> = emptyList(),
    val generatedDateLabel: String = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date())
)

/**
 * Deterministic detector that identifies file generation requests and target format
 * ("create an Excel monthly expense tracker", "make a PDF study plan", "export CSV", etc.).
 */
object FileRequestDetector {
    data class DetectedFileRequest(
        val format: GeneratedFileFormat,
        val cleanGoal: String
    )

    private val creationVerbRegex = Regex(
        """\b(?:create|make|generate|build|prepare|draft|design|export|save|produce|compile|write|download|convert\s+.+\s+to)\b""",
        RegexOption.IGNORE_CASE
    )

    private val excelKeywordRegex = Regex(
        """\b(?:excel|xlsx|xls|spreadsheet|workbook|excel\s+sheet|tracker\s+sheet)\b""",
        RegexOption.IGNORE_CASE
    )

    private val pdfKeywordRegex = Regex(
        """\b(?:pdf|pdf\s+report|pdf\s+plan|pdf\s+document|pdf\s+guide|pdf\s+file|as\s+a?\s*pdf)\b""",
        RegexOption.IGNORE_CASE
    )

    private val docxKeywordRegex = Regex(
        """\b(?:word\s+doc(?:ument)?|word\s+report|word\s+file|docx|ms\s+word|doc\s+file|as\s+a?\s*word)\b""",
        RegexOption.IGNORE_CASE
    )

    private val csvKeywordRegex = Regex(
        """\b(?:csv|csv\s+file|comma[\s-]separated)\b""",
        RegexOption.IGNORE_CASE
    )

    fun detect(rawInput: String): DetectedFileRequest? {
        val text = rawInput.trim().replace(Regex("\\s+"), " ")
        if (text.length < 6) return null

        // Never treat questions like "what is a PDF?" or "how does Excel work?" as file generation
        val lower = text.lowercase(Locale.US)
        if (Regex("""^(?:what\s+is|explain|how\s+does|how\s+to\s+open|why\s+is|define)\b""").containsMatchIn(lower) &&
            !creationVerbRegex.containsMatchIn(lower)
        ) {
            return null
        }

        val hasFormat = excelKeywordRegex.containsMatchIn(text) ||
            pdfKeywordRegex.containsMatchIn(text) ||
            docxKeywordRegex.containsMatchIn(text) ||
            csvKeywordRegex.containsMatchIn(text)

        if (!hasFormat) return null

        // Require either a creation verb OR explicit phrase like "monthly budget excel" / "resume pdf"
        val explicitDirectFormatPhrase = Regex(
            """\b(?:in|as|to|into)\s+(?:a\s+|an\s+)?(?:pdf|excel|xlsx|word|docx|csv)\b|\b(?:pdf|excel|xlsx|word|docx|csv)\s+(?:for|of|about|on|tracker|report|plan|schedule|budget|invoice|resume|notes|sheet|table)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

        if (!creationVerbRegex.containsMatchIn(text) && !explicitDirectFormatPhrase) {
            return null
        }

        val format = when {
            csvKeywordRegex.containsMatchIn(text) -> GeneratedFileFormat.CSV
            excelKeywordRegex.containsMatchIn(text) -> GeneratedFileFormat.XLSX
            docxKeywordRegex.containsMatchIn(text) -> GeneratedFileFormat.DOCX
            else -> GeneratedFileFormat.PDF
        }
        return DetectedFileRequest(format = format, cleanGoal = text)
    }
}

object ExecutiveBlueprintPrompt {
    fun buildSystemPrompt(targetFormat: GeneratedFileFormat, currentDateTime: String): String = buildString {
        appendLine("You are an Executive Document & Financial Modeling Architect for My Personal Agent.")
        appendLine(currentDateTime)
        appendLine("Your job is to output ONE strict, complete, valid JSON object representing a publication-quality ${targetFormat.badgeLabel} document.")
        appendLine()
        appendLine("NON-NEGOTIABLE QUALITY RULES:")
        appendLine("1. ZERO-FILLER QUALITY: NEVER output placeholders like 'Item 1', 'Lorem ipsum', 'Sample text', 'TBD', or shallow 3-row tables. Generate thorough, realistic, immediately usable, domain-accurate content tailored to the user's exact request.")
        appendLine("2. INDIAN CONVENTIONS: Use Indian Rupee (₹) and realistic Indian context/examples whenever the request involves budgets, expenses, salaries, taxes, study schedules, or Indian topics unless another currency is explicitly requested.")
        appendLine("3. REFINED VISUAL PALETTE: Pick an executive accentColorHex such as '#1E3A8A' (Navy), '#0F766E' (Teal), '#1E293B' (Slate), or '#3730A3' (Indigo).")
        appendLine("4. STRICT JSON ONLY: Output raw JSON only — no markdown fences, no commentary before or after.")
        appendLine()
        appendLine("REQUIRED JSON SCHEMA:")
        appendLine("{")
        appendLine("""  "fileType": "${targetFormat.id}",""")
        appendLine("""  "fileName": "descriptive_snake_case_name.${targetFormat.extension}",""")
        appendLine("""  "title": "Executive Document Title",""")
        appendLine("""  "subtitle": "Concise context line (scope, timeframe, or purpose)",""")
        appendLine("""  "categoryTag": "SHORT UPPERCASE TAG (e.g. PERSONAL FINANCE, STUDY PLAN, PROJECT REPORT)",""")
        appendLine("""  "accentColorHex": "${targetFormat.defaultAccentHex}",""")
        appendLine("""  "executiveSummary": "Crisp 2-3 sentence executive summary highlighting the main takeaways and structure.",""")
        appendLine("""  "kpiCards": [""")
        appendLine("""    {"label": "Metric Name", "value": "₹48,500", "subtext": "Context detail"}""")
        appendLine("""  ],""")
        appendLine("""  "sections": [""")
        appendLine("""    {""")
        appendLine("""      "heading": "Clear Section Heading",""")
        appendLine("""      "paragraphs": ["Substantive well-written paragraph..."],""")
        appendLine("""      "bullets": ["**Bold Lead-in:** Specific actionable point with real numbers or steps."],""")
        appendLine("""      "callout": {"title": "Pro Tip / Key Insight", "text": "High-value practical insight.", "tone": "tip"},""")
        appendLine("""      "table": {""")
        appendLine("""        "headers": ["Column A", "Column B", "Amount"],""")
        appendLine("""        "columnWeights": [2.2, 1.5, 1.0],""")
        appendLine("""        "alignments": ["left", "left", "right"],""")
        appendLine("""        "rows": [["Real row 1", "Detail", "₹12,000"]],""")
        appendLine("""        "totalRow": ["Total", "", "₹12,000"]""")
        appendLine("""      }""")
        appendLine("""    }""")
        appendLine("""  ],""")
        appendLine("""  "sheets": [""")
        appendLine("""    {""")
        appendLine("""      "name": "Summary",""")
        appendLine("""      "title": "Dashboard Overview",""")
        appendLine("""      "subtitle": "Key Totals Linked Dynamically",""")
        appendLine("""      "columns": [""")
        appendLine("""        {"header": "Category", "type": "text"},""")
        appendLine("""        {"header": "Budget", "type": "currency"},""")
        appendLine("""        {"header": "Actual", "type": "currency"},""")
        appendLine("""        {"header": "Share", "type": "percent"}""")
        appendLine("""      ],""")
        appendLine("""      "rows": [""")
        appendLine("""        ["Housing & Rent", "18000", "=SUM(Details!C5:C8)", "=C5/${'$'}C${'$'}10"]""")
        appendLine("""      ],""")
        appendLine("""      "totalRow": ["Grand Total", "=SUM(B5:B9)", "=SUM(C5:C9)", "=SUM(D5:D9)"]""")
        appendLine("""    }""")
        appendLine("""  ]""")
        appendLine("}")
        appendLine()
        when (targetFormat) {
            GeneratedFileFormat.XLSX -> {
                appendLine("EXCEL-SPECIFIC REQUIREMENTS:")
                appendLine("- ALWAYS populate 'sheets' with at least 2 meaningful tabs (e.g. 'Summary' dashboard + 'Details' line items with 8-15 realistic rows).")
                appendLine("- In 'columns', set 'type' accurately to one of: 'text', 'currency', 'number', 'percent', 'date'.")
                appendLine("- For currency/number cells, put clean raw numbers (e.g. '18000', '450.50') or real Excel formulas starting with '=' (e.g. '=SUM(C5:C16)', '=AVERAGE(C5:C16)', '=B5-C5', '=SUM(Details!D5:D16)').")
                appendLine("- Note that the generator places Title on Row 1, Subtitle on Row 2, blank Row 3, Table Header on Row 4, and Data Rows starting at Row 5! Align all Excel cell formula row numbers starting at Row 5.")
                appendLine("- For percent cells, use decimals like '0.25' for 25% or formulas like '=C5/\$C\$12'.")
                appendLine("- Include a 'totalRow' on every sheet with real '=SUM(...)' formulas.")
            }
            GeneratedFileFormat.PDF -> {
                appendLine("PDF-SPECIFIC REQUIREMENTS:")
                appendLine("- Include 3-4 meaningful 'kpiCards' and 3-5 rich 'sections' with real tables (6-12 rows where appropriate), bold-lead-in bullets, and at least 1 'callout' box.")
                appendLine("- Provide balanced 'columnWeights' and 'alignments' ('right' for numbers/currency, 'left' for text) on every table.")
            }
            GeneratedFileFormat.DOCX -> {
                appendLine("WORD REPORT REQUIREMENTS:")
                appendLine("- Include 3-4 'kpiCards' and 3-5 well-structured 'sections' with executive prose, bold-lead-in bullets, shaded 'callout' boxes, and full structured 'table' blocks.")
            }
            GeneratedFileFormat.CSV -> {
                appendLine("CSV REQUIREMENTS:")
                appendLine("- Provide 1 rich sheet in 'sheets' (or primary table in 'sections') with clean headers and 10-20 realistic, immediately usable data rows (no Excel formulas in CSV — use computed values).")
            }
        }
    }
}

object FileBlueprintParser {
    private val moshi = Moshi.Builder().build()

    @Suppress("UNCHECKED_CAST")
    fun parse(rawResponse: String, fallbackFormat: GeneratedFileFormat, userGoal: String): FileBlueprint? {
        val jsonText = extractBalancedJson(rawResponse) ?: return null
        val cleaned = jsonText
            .replace(Regex(",\\s*([}\\]])"), "$1")
            .trim()

        val root = runCatching {
            moshi.adapter(Map::class.java).fromJson(cleaned) as? Map<String, Any?>
        }.getOrNull() ?: return null

        val format = fallbackFormat
        val title = root.stringOrBlank("title").ifBlank { buildTitleFromGoal(userGoal) }
        val subtitle = root.stringOrBlank("subtitle")
        val categoryTag = root.stringOrBlank("categoryTag").ifBlank {
            when (format) {
                GeneratedFileFormat.XLSX -> "FINANCIAL & DATA WORKBOOK"
                GeneratedFileFormat.DOCX -> "EXECUTIVE BRIEF"
                GeneratedFileFormat.CSV -> "STRUCTURED DATASET"
                GeneratedFileFormat.PDF -> "EXECUTIVE REPORT"
            }
        }
        val accentColorHex = sanitizeAccentHex(root.stringOrBlank("accentColorHex"), format.defaultAccentHex)
        val rawFileName = root.stringOrBlank("fileName")
        val safeFileName = sanitizeFileName(rawFileName.ifBlank { title }, format.extension)
        val summary = root.stringOrBlank("executiveSummary")

        val kpiCards = (root["kpiCards"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val label = map.stringOrBlank("label")
            val value = map.stringOrBlank("value")
            if (label.isBlank() || value.isBlank()) return@mapNotNull null
            BlueprintKpiCard(
                label = label,
                value = value,
                subtext = map.stringOrBlank("subtext")
            )
        }.orEmpty().take(4)

        val sections = (root["sections"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val heading = map.stringOrBlank("heading")
            val paragraphs = (map["paragraphs"] as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()
            val bullets = (map["bullets"] as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .orEmpty()

            val callout = (map["callout"] as? Map<String, Any?>)?.let { cMap ->
                val cText = cMap.stringOrBlank("text")
                if (cText.isBlank()) null
                else BlueprintCallout(
                    title = cMap.stringOrBlank("title").ifBlank { "Key Insight" },
                    text = cText,
                    tone = cMap.stringOrBlank("tone").ifBlank { "info" }
                )
            }

            val table = (map["table"] as? Map<String, Any?>)?.let { tMap ->
                val headers = (tMap["headers"] as? List<*>)
                    ?.map { it?.toString()?.trim().orEmpty() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val rows = (tMap["rows"] as? List<*>)?.mapNotNull { r ->
                    (r as? List<*>)?.map { formatScalar(it) }
                }.orEmpty()
                if (headers.isEmpty() && rows.isEmpty()) null
                else {
                    val weights = (tMap["columnWeights"] as? List<*>)
                        ?.mapNotNull { (it as? Number)?.toFloat() ?: it?.toString()?.toFloatOrNull() }
                        .orEmpty()
                    val alignments = (tMap["alignments"] as? List<*>)
                        ?.map { it?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }
                        .orEmpty()
                    val totalRow = (tMap["totalRow"] as? List<*>)
                        ?.map { formatScalar(it) }
                        .orEmpty()
                    BlueprintTable(
                        headers = headers,
                        columnWeights = weights,
                        alignments = alignments,
                        rows = rows,
                        totalRow = totalRow
                    )
                }
            }

            if (heading.isBlank() && paragraphs.isEmpty() && bullets.isEmpty() && table == null && callout == null) {
                return@mapNotNull null
            }
            BlueprintSection(
                heading = heading.ifBlank { "Overview" },
                paragraphs = paragraphs,
                bullets = bullets,
                callout = callout,
                table = table
            )
        }.orEmpty()

        val sheets = (root["sheets"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val name = map.stringOrBlank("name").ifBlank { "Sheet" }
            val columns = (map["columns"] as? List<*>)?.mapNotNull { col ->
                when (col) {
                    is Map<*, *> -> {
                        val cMap = col as Map<String, Any?>
                        val header = cMap.stringOrBlank("header").ifBlank { cMap.stringOrBlank("name") }
                        if (header.isBlank()) null
                        else BlueprintColumn(
                            header = header,
                            type = cMap.stringOrBlank("type").lowercase(Locale.US).ifBlank { "text" },
                            alignment = cMap.stringOrBlank("alignment").lowercase(Locale.US),
                            widthHint = (cMap["widthHint"] as? Number)?.toInt()
                        )
                    }
                    is String -> if (col.isBlank()) null else BlueprintColumn(header = col.trim())
                    else -> null
                }
            }.orEmpty()

            val rows = (map["rows"] as? List<*>)?.mapNotNull { r ->
                (r as? List<*>)?.map { formatScalar(it) }
            }.orEmpty()

            val totalRow = (map["totalRow"] as? List<*>)?.map { formatScalar(it) }.orEmpty()

            if (columns.isEmpty() && rows.isEmpty()) return@mapNotNull null
            BlueprintSheet(
                name = sanitizeSheetName(name),
                title = map.stringOrBlank("title").ifBlank { title },
                subtitle = map.stringOrBlank("subtitle").ifBlank { subtitle },
                columns = columns.ifEmpty {
                    val colCount = rows.maxOfOrNull { it.size } ?: 1
                    (1..colCount).map { BlueprintColumn("Column $it") }
                },
                rows = rows,
                totalRow = totalRow
            )
        }.orEmpty()

        if (sections.isEmpty() && sheets.isEmpty()) {
            return null
        }

        return FileBlueprint(
            fileType = format,
            fileName = safeFileName,
            title = title,
            subtitle = subtitle,
            categoryTag = categoryTag.uppercase(Locale.US).take(32),
            accentColorHex = accentColorHex,
            executiveSummary = summary,
            kpiCards = kpiCards,
            sections = sections,
            sheets = sheets
        )
    }

    fun extractBalancedJson(raw: String): String? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trim()
            if (text.endsWith("```")) {
                text = text.removeSuffix("```").trim()
            }
        }
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    fun sanitizeFileName(raw: String, extension: String): String {
        val base = raw.substringBeforeLast('.')
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "executive_document" }
        return "$base.$extension"
    }

    fun sanitizeSheetName(raw: String): String {
        return raw.replace(Regex("""[:\\/?*\[\]]"""), " ")
            .trim()
            .take(31)
            .ifBlank { "Sheet1" }
    }

    fun sanitizeAccentHex(raw: String, fallback: String): String {
        val trimmed = raw.trim()
        return if (Regex("^#[0-9A-Fa-f]{6}$").matches(trimmed)) trimmed.uppercase(Locale.US) else fallback
    }

    fun buildTitleFromGoal(goal: String): String {
        val cleaned = goal
            .replace(Regex("""^(?:please\s+)?(?:create|make|generate|build|prepare|export|save)\s+(?:me\s+)?(?:a|an)?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:pdf|excel|xlsx|word|docx|csv)\s*(?:file|document|sheet|report)?\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '!', '?')
        return cleaned.replaceFirstChar { it.uppercase() }.ifBlank { "Executive Document" }.take(64)
    }

    fun buildChatExecutiveSummary(blueprint: FileBlueprint, sizeBytes: Long): String = buildString {
        appendLine("### ${blueprint.title}")
        if (blueprint.executiveSummary.isNotBlank()) {
            appendLine()
            appendLine(blueprint.executiveSummary.trim())
        }
        if (blueprint.kpiCards.isNotEmpty()) {
            appendLine()
            appendLine("**Key Highlights:**")
            blueprint.kpiCards.forEach { kpi ->
                val sub = if (kpi.subtext.isNotBlank()) " _(${kpi.subtext})_" else ""
                appendLine("- **${kpi.label}:** ${kpi.value}$sub")
            }
        }
        val structureItems = when (blueprint.fileType) {
            GeneratedFileFormat.XLSX -> blueprint.sheets.map { s ->
                "**${s.name}** (${s.rows.size} rows, ${s.columns.size} columns)"
            }
            else -> blueprint.sections.map { sec ->
                val extra = sec.table?.let { " · ${it.rows.size}-row table" }.orEmpty()
                "**${sec.heading}**$extra"
            }
        }
        if (structureItems.isNotEmpty()) {
            appendLine()
            val label = if (blueprint.fileType == GeneratedFileFormat.XLSX) "Included Sheets:" else "Included Sections:"
            appendLine("**$label**")
            structureItems.take(6).forEach { item ->
                appendLine("- $item")
            }
        }
        appendLine()
        append("Ready to **Open**, **Save to Downloads**, or **Share** (`${formatFileSize(sizeBytes)}`).")
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun Map<String, Any?>.stringOrBlank(key: String): String {
        return when (val v = this[key]) {
            null -> ""
            is String -> v.trim()
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            else -> v.toString().trim()
        }
    }

    private fun formatScalar(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value.trim()
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is Float -> if (value % 1f == 0f) value.toLong().toString() else value.toString()
            else -> value.toString().trim()
        }
    }
}
