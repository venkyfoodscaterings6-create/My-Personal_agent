package com.example.files

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max

/**
 * Publication-quality multi-page A4 PDF generator built strictly on Android's native
 * `android.graphics.pdf.PdfDocument` + `StaticLayout` text wrapping.
 *
 * Uses a two-pass pre-pagination layout engine:
 *  - Pass 1 measures every visual block into explicit pages so headings are NEVER orphaned,
 *    table rows are NEVER sliced in half, and table headers automatically repeat on continuation pages.
 *  - Pass 2 draws every page cleanly with exact "Page X of Y" footers and hairline rules.
 */
object StyledPdfGenerator {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_H = 42f
    private const val MARGIN_TOP = 42f
    private const val FOOTER_RESERVED = 44f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_H * 2f) // 511pt
    private const val MAX_Y = PAGE_HEIGHT - FOOTER_RESERVED

    private sealed class PdfElement(val height: Float, val spacingAfter: Float = 8f) {
        class HeaderBand(
            val categoryTag: String,
            val titleLayout: StaticLayout,
            val subtitleLayout: StaticLayout?,
            val dateLabel: String,
            height: Float
        ) : PdfElement(height, spacingAfter = 14f)

        class KpiRow(
            val cards: List<MeasuredKpiCard>,
            height: Float
        ) : PdfElement(height, spacingAfter = 14f)

        class SectionHeading(
            val textLayout: StaticLayout,
            height: Float
        ) : PdfElement(height, spacingAfter = 6f)

        class ParagraphBlock(
            val layout: StaticLayout,
            height: Float
        ) : PdfElement(height, spacingAfter = 7f)

        class BulletBlock(
            val layout: StaticLayout,
            height: Float
        ) : PdfElement(height, spacingAfter = 5f)

        class CalloutBlock(
            val titleLayout: StaticLayout,
            val bodyLayout: StaticLayout,
            val borderColor: Int,
            val bgColor: Int,
            height: Float
        ) : PdfElement(height, spacingAfter = 10f)

        class TableRowElement(
            val cells: List<StaticLayout>,
            val colWidths: List<Float>,
            val isHeader: Boolean,
            val isTotal: Boolean,
            val isZebra: Boolean,
            val rowHeight: Float
        ) : PdfElement(rowHeight, spacingAfter = 0f)
    }

    private data class MeasuredKpiCard(
        val labelLayout: StaticLayout,
        val valueLayout: StaticLayout,
        val subtextLayout: StaticLayout?
    )

    private data class PositionedElement(
        val y: Float,
        val element: PdfElement
    )

    fun generate(blueprint: FileBlueprint, outputFile: File): File {
        outputFile.parentFile?.mkdirs()

        val accentColor = parseColorSafe(blueprint.accentColorHex, Color.parseColor("#1E3A8A"))
        val accentLightTint = blendWithWhite(accentColor, 0.90f)
        val slateHeaderBg = Color.parseColor("#1E293B")
        val textPrimary = Color.parseColor("#0F172A")
        val textBody = Color.parseColor("#1E293B")
        val textMuted = Color.parseColor("#64748B")
        val surfaceTint = Color.parseColor("#F8FAFC")
        val borderLight = Color.parseColor("#E2E8F0")
        val tableBorder = Color.parseColor("#CBD5E1")
        val totalFill = Color.parseColor("#EEF2FF")

        val titlePaint = makeTextPaint(20f, textPrimary, Typeface.BOLD)
        val subtitlePaint = makeTextPaint(10f, textMuted, Typeface.NORMAL)
        val categoryPaint = makeTextPaint(8f, accentColor, Typeface.BOLD)
        val datePaint = makeTextPaint(8.5f, textMuted, Typeface.NORMAL)
        val h2Paint = makeTextPaint(13f, accentColor, Typeface.BOLD)
        val bodyPaint = makeTextPaint(9.5f, textBody, Typeface.NORMAL)
        val tableHeaderPaint = makeTextPaint(8.5f, Color.WHITE, Typeface.BOLD)
        val tableBodyPaint = makeTextPaint(8.5f, textBody, Typeface.NORMAL)
        val tableTotalPaint = makeTextPaint(8.5f, textPrimary, Typeface.BOLD)
        val kpiLabelPaint = makeTextPaint(8f, textMuted, Typeface.NORMAL)
        val kpiValuePaint = makeTextPaint(14f, accentColor, Typeface.BOLD)
        val kpiSubPaint = makeTextPaint(7.5f, textMuted, Typeface.NORMAL)
        val calloutTitlePaint = makeTextPaint(9.5f, accentColor, Typeface.BOLD)
        val footerPaint = makeTextPaint(8f, textMuted, Typeface.NORMAL)

        // Pass 1: Measure all elements & build orphan-free, split-free pages
        val pages = mutableListOf<MutableList<PositionedElement>>()
        var currentPage = mutableListOf<PositionedElement>()
        pages.add(currentPage)
        var cursorY = MARGIN_TOP

        fun startNewPage() {
            currentPage = mutableListOf()
            pages.add(currentPage)
            cursorY = MARGIN_TOP
        }

        fun placeElement(el: PdfElement) {
            if (cursorY + el.height > MAX_Y && currentPage.isNotEmpty()) {
                startNewPage()
            }
            currentPage.add(PositionedElement(cursorY, el))
            cursorY += el.height + el.spacingAfter
        }

        // 1. Top Executive Header Band
        val titleLayout = buildStaticLayout(blueprint.title, titlePaint, CONTENT_WIDTH.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.15f)
        val subLayout = blueprint.subtitle.takeIf { it.isNotBlank() }?.let {
            buildStaticLayout(it, subtitlePaint, CONTENT_WIDTH.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.25f)
        }
        val headerHeight = 10f + 22f + titleLayout.height + (subLayout?.let { it.height + 5f } ?: 0f) + 10f
        placeElement(
            PdfElement.HeaderBand(
                categoryTag = blueprint.categoryTag,
                titleLayout = titleLayout,
                subtitleLayout = subLayout,
                dateLabel = blueprint.generatedDateLabel,
                height = headerHeight
            )
        )

        // 2. Executive Summary Callout (if present)
        if (blueprint.executiveSummary.isNotBlank()) {
            val summaryCallout = measureCallout(
                title = "Executive Summary",
                body = blueprint.executiveSummary,
                titlePaint = calloutTitlePaint,
                bodyPaint = bodyPaint,
                borderColor = accentColor,
                bgColor = surfaceTint
            )
            placeElement(summaryCallout)
        }

        // 3. Horizontal KPI Metric Cards
        if (blueprint.kpiCards.isNotEmpty()) {
            val cards = blueprint.kpiCards.take(4)
            val gap = 8f
            val cardW = (CONTENT_WIDTH - gap * (cards.size - 1)) / cards.size
            val innerW = (cardW - 18f).toInt().coerceAtLeast(40)
            val measuredCards = cards.map { card ->
                MeasuredKpiCard(
                    labelLayout = buildStaticLayout(card.label, kpiLabelPaint, innerW, Layout.Alignment.ALIGN_NORMAL, 1.15f),
                    valueLayout = buildStaticLayout(card.value, kpiValuePaint, innerW, Layout.Alignment.ALIGN_NORMAL, 1.1f),
                    subtextLayout = card.subtext.takeIf { it.isNotBlank() }?.let {
                        buildStaticLayout(it, kpiSubPaint, innerW, Layout.Alignment.ALIGN_NORMAL, 1.15f)
                    }
                )
            }
            val maxCardH = measuredCards.maxOf { m ->
                10f + m.labelLayout.height + 4f + m.valueLayout.height +
                    (m.subtextLayout?.let { 3f + it.height } ?: 0f) + 10f
            }.coerceAtLeast(56f)
            placeElement(PdfElement.KpiRow(measuredCards, maxCardH))
        }

        // 4. Sections (convert sheets into sections if blueprint came from sheet-heavy schema)
        val sections = if (blueprint.sections.isNotEmpty()) {
            blueprint.sections
        } else {
            blueprint.sheets.map { sheet ->
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

        sections.forEach { section ->
            val headingLayout = buildStaticLayout(section.heading, h2Paint, CONTENT_WIDTH.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.15f)
            val headingEl = PdfElement.SectionHeading(headingLayout, headingLayout.height + 8f)

            // Orphan-heading guard: keep heading together with at least 48pt of following content
            if (cursorY + headingEl.height + 52f > MAX_Y && currentPage.isNotEmpty()) {
                startNewPage()
            }
            placeElement(headingEl)

            // Paragraphs
            section.paragraphs.forEach { para ->
                val styled = parseBoldSpans(para)
                val layout = buildStaticLayout(styled, bodyPaint, CONTENT_WIDTH.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.45f)
                placeElement(PdfElement.ParagraphBlock(layout, layout.height.toFloat()))
            }

            // Bullets with bold lead-ins
            section.bullets.forEach { bullet ->
                val cleaned = bullet.trim().removePrefix("- ").removePrefix("• ").removePrefix("* ")
                val styled = parseBulletSpans(cleaned)
                val layout = buildStaticLayout(styled, bodyPaint, (CONTENT_WIDTH - 14f).toInt(), Layout.Alignment.ALIGN_NORMAL, 1.40f)
                placeElement(PdfElement.BulletBlock(layout, layout.height.toFloat()))
            }

            // Callout / Insight Box
            section.callout?.let { callout ->
                val (cBorder, cBg) = when (callout.tone.lowercase(Locale.US)) {
                    "warning" -> Color.parseColor("#B45309") to Color.parseColor("#FFFBEB")
                    "tip" -> Color.parseColor("#0F766E") to Color.parseColor("#F0FDFA")
                    else -> accentColor to accentLightTint
                }
                val titleP = makeTextPaint(9.5f, cBorder, Typeface.BOLD)
                val calloutEl = measureCallout(callout.title, callout.text, titleP, bodyPaint, cBorder, cBg)
                placeElement(calloutEl)
            }

            // Smart Proportional Table with Header Repetition on Page Breaks
            section.table?.let { table ->
                val colCount = table.headers.size.coerceAtLeast(table.rows.maxOfOrNull { it.size } ?: 0)
                if (colCount > 0) {
                    val colWidths = computeTableColumnWidths(table, colCount)
                    val alignments = List(colCount) { c ->
                        when (table.alignments.getOrElse(c) { "" }.lowercase(Locale.US)) {
                            "right" -> Layout.Alignment.ALIGN_OPPOSITE
                            "center" -> Layout.Alignment.ALIGN_CENTER
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }
                    }

                    val headerRowEl = if (table.headers.isNotEmpty()) {
                        measureTableRow(
                            cells = List(colCount) { table.headers.getOrElse(it) { "" } },
                            colWidths = colWidths,
                            alignments = alignments,
                            paint = tableHeaderPaint,
                            isHeader = true,
                            isTotal = false,
                            isZebra = false
                        )
                    } else null

                    val dataRows = table.rows.mapIndexed { rIdx, row ->
                        measureTableRow(
                            cells = List(colCount) { row.getOrElse(it) { "" } },
                            colWidths = colWidths,
                            alignments = alignments,
                            paint = tableBodyPaint,
                            isHeader = false,
                            isTotal = false,
                            isZebra = rIdx % 2 == 1
                        )
                    }

                    val totalRowEl = if (table.totalRow.isNotEmpty()) {
                        measureTableRow(
                            cells = List(colCount) { table.totalRow.getOrElse(it) { "" } },
                            colWidths = colWidths,
                            alignments = alignments,
                            paint = tableTotalPaint,
                            isHeader = false,
                            isTotal = true,
                            isZebra = false
                        )
                    } else null

                    // Never place table header alone at page bottom without at least the first data row
                    val firstRowH = dataRows.firstOrNull()?.rowHeight ?: totalRowEl?.rowHeight ?: 0f
                    if (headerRowEl != null && cursorY + headerRowEl.rowHeight + firstRowH > MAX_Y && currentPage.isNotEmpty()) {
                        startNewPage()
                    }
                    headerRowEl?.let { placeElement(it) }

                    dataRows.forEach { rowEl ->
                        if (cursorY + rowEl.rowHeight > MAX_Y && currentPage.isNotEmpty()) {
                            startNewPage()
                            // Repeat table header cleanly on continuation page
                            headerRowEl?.let { placeElement(it) }
                        }
                        placeElement(rowEl)
                    }

                    totalRowEl?.let { tot ->
                        if (cursorY + tot.rowHeight > MAX_Y && currentPage.isNotEmpty()) {
                            startNewPage()
                            headerRowEl?.let { placeElement(it) }
                        }
                        placeElement(tot)
                    }

                    cursorY += 10f
                }
            }
        }

        // Pass 2: Render all paginated pages into PdfDocument with exact "Page X of Y" footers
        val totalPages = pages.size
        val pdfDocument = PdfDocument()
        try {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

            pages.forEachIndexed { pageIndex, items ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // White page canvas
                fillPaint.color = Color.WHITE
                canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), fillPaint)

                // Top slim executive accent strip on page 1
                if (pageIndex == 0) {
                    fillPaint.color = accentColor
                    canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 5f, fillPaint)
                }

                items.forEach { positioned ->
                    drawElement(
                        canvas = canvas,
                        y = positioned.y,
                        element = positioned.element,
                        accentColor = accentColor,
                        accentLightTint = accentLightTint,
                        slateHeaderBg = slateHeaderBg,
                        surfaceTint = surfaceTint,
                        borderLight = borderLight,
                        tableBorder = tableBorder,
                        totalFill = totalFill,
                        categoryPaint = categoryPaint,
                        datePaint = datePaint,
                        fillPaint = fillPaint,
                        strokePaint = strokePaint
                    )
                }

                // Clean hairline footer: Document title on left, "Page X of Y" on right
                val footerRuleY = PAGE_HEIGHT - 34f
                strokePaint.color = borderLight
                strokePaint.strokeWidth = 0.7f
                canvas.drawLine(MARGIN_H, footerRuleY, PAGE_WIDTH - MARGIN_H, footerRuleY, strokePaint)

                val footerTextY = PAGE_HEIGHT - 20f
                canvas.drawText(blueprint.title.take(58), MARGIN_H, footerTextY, footerPaint)
                val pageLabel = "Page ${pageIndex + 1} of $totalPages"
                val labelW = footerPaint.measureText(pageLabel)
                canvas.drawText(pageLabel, PAGE_WIDTH - MARGIN_H - labelW, footerTextY, footerPaint)

                pdfDocument.finishPage(page)
            }

            FileOutputStream(outputFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
        } finally {
            pdfDocument.close()
        }
        return outputFile
    }

    private fun drawElement(
        canvas: Canvas,
        y: Float,
        element: PdfElement,
        accentColor: Int,
        accentLightTint: Int,
        slateHeaderBg: Int,
        surfaceTint: Int,
        borderLight: Int,
        tableBorder: Int,
        totalFill: Int,
        categoryPaint: TextPaint,
        datePaint: TextPaint,
        fillPaint: Paint,
        strokePaint: Paint
    ) {
        when (element) {
            is PdfElement.HeaderBand -> {
                // Category pill
                val pillText = element.categoryTag
                val pillW = categoryPaint.measureText(pillText) + 16f
                val pillRect = RectF(MARGIN_H, y, MARGIN_H + pillW, y + 17f)
                fillPaint.color = accentLightTint
                canvas.drawRoundRect(pillRect, 8f, 8f, fillPaint)
                canvas.drawText(pillText, MARGIN_H + 8f, y + 11.5f, categoryPaint)

                // Right-aligned generated date
                val dateW = datePaint.measureText(element.dateLabel)
                canvas.drawText(element.dateLabel, PAGE_WIDTH - MARGIN_H - dateW, y + 11.5f, datePaint)

                var curY = y + 24f
                canvas.save()
                canvas.translate(MARGIN_H, curY)
                element.titleLayout.draw(canvas)
                canvas.restore()
                curY += element.titleLayout.height

                element.subtitleLayout?.let { sub ->
                    curY += 5f
                    canvas.save()
                    canvas.translate(MARGIN_H, curY)
                    sub.draw(canvas)
                    canvas.restore()
                    curY += sub.height
                }

                // Accent bottom hairline
                strokePaint.color = accentColor
                strokePaint.strokeWidth = 1.5f
                canvas.drawLine(MARGIN_H, curY + 8f, PAGE_WIDTH - MARGIN_H, curY + 8f, strokePaint)
            }

            is PdfElement.KpiRow -> {
                val gap = 8f
                val cardW = (CONTENT_WIDTH - gap * (element.cards.size - 1)) / element.cards.size
                element.cards.forEachIndexed { idx, card ->
                    val left = MARGIN_H + idx * (cardW + gap)
                    val rect = RectF(left, y, left + cardW, y + element.height)
                    fillPaint.color = surfaceTint
                    canvas.drawRoundRect(rect, 7f, 7f, fillPaint)
                    strokePaint.color = borderLight
                    strokePaint.strokeWidth = 1f
                    canvas.drawRoundRect(rect, 7f, 7f, strokePaint)

                    var innerY = y + 9f
                    canvas.save()
                    canvas.translate(left + 9f, innerY)
                    card.labelLayout.draw(canvas)
                    canvas.restore()
                    innerY += card.labelLayout.height + 4f

                    canvas.save()
                    canvas.translate(left + 9f, innerY)
                    card.valueLayout.draw(canvas)
                    canvas.restore()
                    innerY += card.valueLayout.height

                    card.subtextLayout?.let { sub ->
                        innerY += 3f
                        canvas.save()
                        canvas.translate(left + 9f, innerY)
                        sub.draw(canvas)
                        canvas.restore()
                    }
                }
            }

            is PdfElement.SectionHeading -> {
                canvas.save()
                canvas.translate(MARGIN_H, y)
                element.textLayout.draw(canvas)
                canvas.restore()
                val ruleY = y + element.textLayout.height + 4f
                strokePaint.color = borderLight
                strokePaint.strokeWidth = 0.8f
                canvas.drawLine(MARGIN_H, ruleY, PAGE_WIDTH - MARGIN_H, ruleY, strokePaint)
            }

            is PdfElement.ParagraphBlock -> {
                canvas.save()
                canvas.translate(MARGIN_H, y)
                element.layout.draw(canvas)
                canvas.restore()
            }

            is PdfElement.BulletBlock -> {
                fillPaint.color = accentColor
                canvas.drawCircle(MARGIN_H + 4f, y + 6f, 2.2f, fillPaint)
                canvas.save()
                canvas.translate(MARGIN_H + 14f, y)
                element.layout.draw(canvas)
                canvas.restore()
            }

            is PdfElement.CalloutBlock -> {
                val rect = RectF(MARGIN_H, y, MARGIN_H + CONTENT_WIDTH, y + element.height)
                fillPaint.color = element.bgColor
                canvas.drawRoundRect(rect, 5f, 5f, fillPaint)
                // Solid 3pt accent left bar
                fillPaint.color = element.borderColor
                canvas.drawRect(MARGIN_H, y, MARGIN_H + 3.2f, y + element.height, fillPaint)

                var innerY = y + 9f
                canvas.save()
                canvas.translate(MARGIN_H + 12f, innerY)
                element.titleLayout.draw(canvas)
                canvas.restore()
                innerY += element.titleLayout.height + 4f

                canvas.save()
                canvas.translate(MARGIN_H + 12f, innerY)
                element.bodyLayout.draw(canvas)
                canvas.restore()
            }

            is PdfElement.TableRowElement -> {
                val bg = when {
                    element.isHeader -> slateHeaderBg
                    element.isTotal -> totalFill
                    element.isZebra -> surfaceTint
                    else -> Color.WHITE
                }
                fillPaint.color = bg
                canvas.drawRect(MARGIN_H, y, MARGIN_H + CONTENT_WIDTH, y + element.rowHeight, fillPaint)

                strokePaint.color = if (element.isHeader) slateHeaderBg else tableBorder
                strokePaint.strokeWidth = 0.5f
                canvas.drawRect(MARGIN_H, y, MARGIN_H + CONTENT_WIDTH, y + element.rowHeight, strokePaint)

                if (element.isTotal) {
                    strokePaint.color = slateHeaderBg
                    strokePaint.strokeWidth = 1f
                    canvas.drawLine(MARGIN_H, y, MARGIN_H + CONTENT_WIDTH, y, strokePaint)
                }

                var x = MARGIN_H
                element.cells.forEachIndexed { cIdx, cellLayout ->
                    val colW = element.colWidths[cIdx]
                    if (cIdx > 0) {
                        strokePaint.color = if (element.isHeader) Color.parseColor("#334155") else tableBorder
                        strokePaint.strokeWidth = 0.5f
                        canvas.drawLine(x, y, x, y + element.rowHeight, strokePaint)
                    }
                    val padV = (element.rowHeight - cellLayout.height) / 2f
                    canvas.save()
                    canvas.translate(x + 6f, y + padV.coerceAtLeast(4f))
                    cellLayout.draw(canvas)
                    canvas.restore()
                    x += colW
                }
            }
        }
    }

    private fun measureCallout(
        title: String,
        body: String,
        titlePaint: TextPaint,
        bodyPaint: TextPaint,
        borderColor: Int,
        bgColor: Int
    ): PdfElement.CalloutBlock {
        val innerWidth = (CONTENT_WIDTH - 22f).toInt()
        val tLayout = buildStaticLayout(title, titlePaint, innerWidth, Layout.Alignment.ALIGN_NORMAL, 1.15f)
        val bLayout = buildStaticLayout(parseBoldSpans(body), bodyPaint, innerWidth, Layout.Alignment.ALIGN_NORMAL, 1.38f)
        val totalH = 9f + tLayout.height + 4f + bLayout.height + 9f
        return PdfElement.CalloutBlock(tLayout, bLayout, borderColor, bgColor, totalH)
    }

    private fun measureTableRow(
        cells: List<String>,
        colWidths: List<Float>,
        alignments: List<Layout.Alignment>,
        paint: TextPaint,
        isHeader: Boolean,
        isTotal: Boolean,
        isZebra: Boolean
    ): PdfElement.TableRowElement {
        val layouts = cells.mapIndexed { idx, text ->
            val innerW = (colWidths[idx] - 12f).toInt().coerceAtLeast(24)
            buildStaticLayout(text, paint, innerW, alignments[idx], 1.25f)
        }
        val maxContentH = layouts.maxOfOrNull { it.height } ?: 12
        val minRowH = if (isHeader || isTotal) 24f else 20f
        val rowH = max(minRowH, maxContentH + 10f)
        return PdfElement.TableRowElement(
            cells = layouts,
            colWidths = colWidths,
            isHeader = isHeader,
            isTotal = isTotal,
            isZebra = isZebra,
            rowHeight = rowH
        )
    }

    private fun computeTableColumnWidths(table: BlueprintTable, colCount: Int): List<Float> {
        val weights = if (table.columnWeights.size == colCount) {
            table.columnWeights.map { it.coerceAtLeast(0.5f) }
        } else {
            // Auto-measure natural content widths so description columns get wider space
            List(colCount) { c ->
                val hLen = table.headers.getOrElse(c) { "" }.length
                val avgLen = table.rows.take(12).map { it.getOrElse(c) { "" }.length }.average()
                val score = max(hLen.toDouble(), if (avgLen.isNaN()) 8.0 else avgLen)
                score.toFloat().coerceIn(7f, 32f)
            }
        }
        val sum = weights.sum().coerceAtLeast(1f)
        return weights.map { (it / sum) * CONTENT_WIDTH }
    }

    private fun parseBoldSpans(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        val regex = Regex("""\*\*(.+?)\*\*""")
        var cursor = 0
        regex.findAll(raw).forEach { match ->
            if (match.range.first > cursor) {
                sb.append(raw.substring(cursor, match.range.first).replace("**", ""))
            }
            val start = sb.length
            sb.append(match.groupValues[1])
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            sb.append(raw.substring(cursor).replace("**", ""))
        }
        return sb
    }

    private fun parseBulletSpans(raw: String): CharSequence {
        if (raw.contains("**")) return parseBoldSpans(raw)
        val colonIdx = raw.indexOf(':')
        if (colonIdx in 2..48) {
            val sb = SpannableStringBuilder(raw)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, colonIdx + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        return raw
    }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        widthPx: Int,
        alignment: Layout.Alignment,
        spacingMult: Float
    ): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(24))
            .setAlignment(alignment)
            .setLineSpacing(0f, spacingMult)
            .setIncludePad(false)
            .build()
    }

    private fun makeTextPaint(sizePt: Float, colorInt: Int, style: Int): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePt
            color = colorInt
            typeface = Typeface.create(Typeface.SANS_SERIF, style)
        }
    }

    private fun parseColorSafe(hex: String, fallback: Int): Int {
        return runCatching { Color.parseColor(hex) }.getOrDefault(fallback)
    }

    private fun blendWithWhite(color: Int, whiteRatio: Float): Int {
        val inv = 1f - whiteRatio
        val r = (Color.red(color) * inv + 255f * whiteRatio).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * inv + 255f * whiteRatio).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * inv + 255f * whiteRatio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
