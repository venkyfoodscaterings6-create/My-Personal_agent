package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MonoCodeStyle

/**
 * Pure-Kotlin Markdown AST blocks & inline spans for fast, zero-lag Material 3 rendering
 * and direct JVM unit testing.
 */
sealed class MarkdownInline {
    data class Plain(val text: String) : MarkdownInline()
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline()
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline()
    data class BoldItalic(val children: List<MarkdownInline>) : MarkdownInline()
    data class InlineCode(val code: String) : MarkdownInline()
    data class Strike(val children: List<MarkdownInline>) : MarkdownInline()
    data class Link(val label: String, val url: String) : MarkdownInline()
}

sealed class MarkdownBlock {
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class BulletItem(val indentLevel: Int, val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class NumberedItem(val number: String, val indentLevel: Int, val inlines: List<MarkdownInline>) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class Table(
        val headers: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>
    ) : MarkdownBlock()
    data class BlockQuote(val inlines: List<MarkdownInline>) : MarkdownBlock()
    data object HorizontalRule : MarkdownBlock()
}

object GeminiMarkdownParser {
    private val headingRegex = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val hrRegex = Regex("""^\s*(?:[-*_]\s*){3,}$""")
    private val bulletRegex = Regex("""^(\s*)[-*+•]\s+(.+)$""")
    private val numberedRegex = Regex("""^(\s*)(\d+)[.)]\s+(.+)$""")
    private val quoteRegex = Regex("""^\s*>\s?(.*)$""")
    private val fenceStartRegex = Regex("""^\s*```([A-Za-z0-9_+#.-]*)\s*$""")
    private val tableSeparatorRegex = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 1. Fenced code block ```lang ... ```
            val fenceMatch = fenceStartRegex.matchEntire(line)
            if (fenceMatch != null) {
                val lang = fenceMatch.groupValues[1].trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size && lines[i].trim().startsWith("```")) {
                    i++
                }
                blocks.add(MarkdownBlock.CodeBlock(language = lang, code = codeLines.joinToString("\n")))
                continue
            }

            // 2. Markdown table (header row + separator row)
            if (trimmed.contains("|") && i + 1 < lines.size && tableSeparatorRegex.matches(lines[i + 1])) {
                val headerCells = splitTableRow(line).map { parseInlines(it) }
                i += 2
                val bodyRows = mutableListOf<List<List<MarkdownInline>>>()
                while (i < lines.size) {
                    val rowLine = lines[i]
                    if (rowLine.isBlank() || !rowLine.contains("|")) break
                    val rawCells = splitTableRow(rowLine)
                    val paddedCells = MutableList(headerCells.size) { colIdx ->
                        parseInlines(rawCells.getOrElse(colIdx) { "" })
                    }
                    bodyRows.add(paddedCells)
                    i++
                }
                if (headerCells.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Table(headers = headerCells, rows = bodyRows))
                    continue
                }
            }

            // 3. Heading (# .. ######)
            val headingMatch = headingRegex.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceIn(1, 6)
                val titleText = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level = level, inlines = parseInlines(titleText)))
                i++
                continue
            }

            // 4. Horizontal rule (---, ***, ___)
            if (hrRegex.matches(line)) {
                blocks.add(MarkdownBlock.HorizontalRule)
                i++
                continue
            }

            // 5. Blockquote (> ...)
            val quoteMatch = quoteRegex.matchEntire(line)
            if (quoteMatch != null) {
                val quoteLines = mutableListOf(quoteMatch.groupValues[1])
                i++
                while (i < lines.size) {
                    val nextQuote = quoteRegex.matchEntire(lines[i]) ?: break
                    quoteLines.add(nextQuote.groupValues[1])
                    i++
                }
                blocks.add(MarkdownBlock.BlockQuote(parseInlines(quoteLines.joinToString("\n").trim())))
                continue
            }

            // 6. Unordered bullet list item
            val bulletMatch = bulletRegex.matchEntire(line)
            if (bulletMatch != null) {
                val indentLevel = (bulletMatch.groupValues[1].length / 2).coerceIn(0, 3)
                blocks.add(
                    MarkdownBlock.BulletItem(
                        indentLevel = indentLevel,
                        inlines = parseInlines(bulletMatch.groupValues[2].trim())
                    )
                )
                i++
                continue
            }

            // 7. Numbered list item
            val numMatch = numberedRegex.matchEntire(line)
            if (numMatch != null) {
                val indentLevel = (numMatch.groupValues[1].length / 2).coerceIn(0, 3)
                val number = numMatch.groupValues[2]
                blocks.add(
                    MarkdownBlock.NumberedItem(
                        number = number,
                        indentLevel = indentLevel,
                        inlines = parseInlines(numMatch.groupValues[3].trim())
                    )
                )
                i++
                continue
            }

            // 8. Standard paragraph (merge contiguous non-special lines cleanly)
            val paraLines = mutableListOf(trimmed)
            i++
            while (i < lines.size) {
                val nextLine = lines[i]
                val nextTrim = nextLine.trim()
                if (nextTrim.isEmpty() ||
                    fenceStartRegex.matches(nextLine) ||
                    headingRegex.matches(nextLine) ||
                    hrRegex.matches(nextLine) ||
                    quoteRegex.matches(nextLine) ||
                    bulletRegex.matches(nextLine) ||
                    numberedRegex.matches(nextLine) ||
                    (nextTrim.contains("|") && i + 1 < lines.size && tableSeparatorRegex.matches(lines[i + 1]))
                ) {
                    break
                }
                paraLines.add(nextTrim)
                i++
            }
            blocks.add(MarkdownBlock.Paragraph(parseInlines(paraLines.joinToString("\n"))))
        }

        return blocks
    }

    private fun splitTableRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }

    /**
     * Parses inline Markdown spans (links, inline code, bold-italic, bold, italic, strikethrough)
     * and strips any dangling unclosed `**` / `*` markers so raw stars never appear in UI.
     */
    fun parseInlines(input: String): List<MarkdownInline> {
        if (input.isEmpty()) return emptyList()
        val result = mutableListOf<MarkdownInline>()
        val plainBuf = StringBuilder()
        var i = 0
        val n = input.length

        fun flushPlain() {
            if (plainBuf.isNotEmpty()) {
                result.add(MarkdownInline.Plain(plainBuf.toString()))
                plainBuf.setLength(0)
            }
        }

        while (i < n) {
            val ch = input[i]

            // Escaped markdown punctuation (\*, \#, \_, \`, \[)
            if (ch == '\\' && i + 1 < n && input[i + 1] in charArrayOf('*', '_', '`', '#', '[', ']', '~')) {
                plainBuf.append(input[i + 1])
                i += 2
                continue
            }

            // Inline code `code`
            if (ch == '`') {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    result.add(MarkdownInline.InlineCode(input.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }

            // Markdown link [label](https://...)
            if (ch == '[') {
                val closeBracket = input.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && input[closeBracket + 1] == '(') {
                    val closeParen = input.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = input.substring(i + 1, closeBracket).trim()
                        val url = input.substring(closeBracket + 2, closeParen).trim()
                        if (isSafeWebUrl(url)) {
                            flushPlain()
                            result.add(MarkdownInline.Link(label.ifBlank { url }, url))
                            i = closeParen + 1
                            continue
                        }
                    }
                }
            }

            // Bare http:// or https:// URL
            if ((input.startsWith("https://", i) || input.startsWith("http://", i))) {
                var end = i
                while (end < n && !input[end].isWhitespace() && input[end] !in charArrayOf(')', ']', '>', '"', '\'')) {
                    end++
                }
                val rawUrl = input.substring(i, end).trimEnd('.', ',', ';', '!', '?')
                if (rawUrl.length > 10 && isSafeWebUrl(rawUrl)) {
                    flushPlain()
                    val display = rawUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                    result.add(MarkdownInline.Link(display.take(48), rawUrl))
                    i += rawUrl.length
                    continue
                }
            }

            // Bold + Italic ***text***
            if (input.startsWith("***", i)) {
                val end = input.indexOf("***", i + 3)
                if (end > i + 3) {
                    flushPlain()
                    val inner = input.substring(i + 3, end)
                    result.add(MarkdownInline.BoldItalic(parseInlines(inner)))
                    i = end + 3
                    continue
                } else {
                    // Strip dangling unclosed *** during streaming
                    i += 3
                    continue
                }
            }

            // Bold **text**
            if (input.startsWith("**", i)) {
                val end = input.indexOf("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val inner = input.substring(i + 2, end)
                    result.add(MarkdownInline.Bold(parseInlines(inner)))
                    i = end + 2
                    continue
                } else {
                    // Strip stray/unclosed ** so raw stars never show
                    i += 2
                    continue
                }
            }

            // Bold __text__
            if (input.startsWith("__", i)) {
                val end = input.indexOf("__", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val inner = input.substring(i + 2, end)
                    result.add(MarkdownInline.Bold(parseInlines(inner)))
                    i = end + 2
                    continue
                }
            }

            // Strikethrough ~~text~~
            if (input.startsWith("~~", i)) {
                val end = input.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    val inner = input.substring(i + 2, end)
                    result.add(MarkdownInline.Strike(parseInlines(inner)))
                    i = end + 2
                    continue
                }
            }

            // Italic *text* (only when not preceded/followed by space inside)
            if (ch == '*') {
                val nextChar = if (i + 1 < n) input[i + 1] else ' '
                if (!nextChar.isWhitespace()) {
                    val end = findSingleStarEnd(input, i + 1)
                    if (end > i + 1) {
                        flushPlain()
                        val inner = input.substring(i + 1, end)
                        result.add(MarkdownInline.Italic(parseInlines(inner)))
                        i = end + 1
                        continue
                    }
                }
                // Ignore stray lone markdown asterisk if it appears at start/end of a word
                if (i == 0 || input[i - 1].isWhitespace() || i + 1 >= n || input[i + 1].isWhitespace()) {
                    i++
                    continue
                }
            }

            // Italic _text_ (avoid breaking snake_case identifiers)
            if (ch == '_' && (i == 0 || !input[i - 1].isLetterOrDigit())) {
                val end = input.indexOf('_', i + 1)
                if (end > i + 1 && (end + 1 >= n || !input[end + 1].isLetterOrDigit())) {
                    flushPlain()
                    val inner = input.substring(i + 1, end)
                    result.add(MarkdownInline.Italic(parseInlines(inner)))
                    i = end + 1
                    continue
                }
            }

            plainBuf.append(ch)
            i++
        }

        flushPlain()
        return result
    }

    private fun findSingleStarEnd(input: String, start: Int): Int {
        var idx = start
        while (idx < input.length) {
            if (input[idx] == '*') {
                val isDouble = (idx + 1 < input.length && input[idx + 1] == '*') ||
                    (idx > start && input[idx - 1] == '*')
                if (!isDouble && !input[idx - 1].isWhitespace()) {
                    return idx
                }
            }
            idx++
        }
        return -1
    }

    fun isSafeWebUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    /**
     * Extracts plain visible text from parsed blocks (used in unit tests to verify
     * zero raw `*` or `#` markdown markers leak into rendered output).
     */
    fun extractPlainRenderedText(blocks: List<MarkdownBlock>): String {
        return buildString {
            blocks.forEachIndexed { idx, block ->
                if (idx > 0) append('\n')
                when (block) {
                    is MarkdownBlock.Heading -> append(flattenInlines(block.inlines))
                    is MarkdownBlock.Paragraph -> append(flattenInlines(block.inlines))
                    is MarkdownBlock.BulletItem -> append("• ").append(flattenInlines(block.inlines))
                    is MarkdownBlock.NumberedItem -> append("${block.number}. ").append(flattenInlines(block.inlines))
                    is MarkdownBlock.CodeBlock -> append(block.code)
                    is MarkdownBlock.BlockQuote -> append(flattenInlines(block.inlines))
                    is MarkdownBlock.Table -> {
                        append(block.headers.joinToString(" | ") { flattenInlines(it) })
                        block.rows.forEach { row ->
                            append('\n')
                            append(row.joinToString(" | ") { flattenInlines(it) })
                        }
                    }
                    MarkdownBlock.HorizontalRule -> append("—")
                }
            }
        }
    }

    fun flattenInlines(inlines: List<MarkdownInline>): String = buildString {
        for (span in inlines) {
            when (span) {
                is MarkdownInline.Plain -> append(span.text)
                is MarkdownInline.Bold -> append(flattenInlines(span.children))
                is MarkdownInline.Italic -> append(flattenInlines(span.children))
                is MarkdownInline.BoldItalic -> append(flattenInlines(span.children))
                is MarkdownInline.InlineCode -> append(span.code)
                is MarkdownInline.Strike -> append(flattenInlines(span.children))
                is MarkdownInline.Link -> append(span.label)
            }
        }
    }
}

/**
 * Fast, cached Material 3 Markdown renderer styled like Google Gemini.
 * Caches parsed AST with `remember(messageId, content)` so LazyColumn scrolling stays 100% smooth.
 */
@Composable
fun GeminiMarkdownText(
    messageId: String,
    content: String,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val blocks = remember(messageId, content) {
        GeminiMarkdownParser.parse(content)
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val primaryColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val inlineCodeFg = MaterialTheme.colorScheme.onSurfaceVariant

    val openSafeUrl: (String) -> Unit = remember(context) {
        { url ->
            if (GeminiMarkdownParser.isSafeWebUrl(url)) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("markdown_content_$messageId"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    }
                    val annotated = remember(block, primaryColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(block.inlines, primaryColor, inlineCodeBg, inlineCodeFg, openSafeUrl)
                    }
                    Text(
                        text = annotated,
                        style = headingStyle,
                        color = textColor,
                        modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 4.dp)
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    val annotated = remember(block, primaryColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(block.inlines, primaryColor, inlineCodeBg, inlineCodeFg, openSafeUrl)
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = textColor
                    )
                }

                is MarkdownBlock.BulletItem -> {
                    val annotated = remember(block, primaryColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(block.inlines, primaryColor, inlineCodeBg, inlineCodeFg, openSafeUrl)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (8 + block.indentLevel * 16).dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (block.indentLevel == 0) "•" else "◦",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = primaryColor
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.NumberedItem -> {
                    val annotated = remember(block, primaryColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(block.inlines, primaryColor, inlineCodeBg, inlineCodeFg, openSafeUrl)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (6 + block.indentLevel * 16).dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = primaryColor
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    MarkdownCodeCard(
                        language = block.language,
                        code = block.code,
                        onCopy = {
                            clipboard.setText(AnnotatedString(block.code))
                            onCopyCode("Code copied to clipboard")
                        }
                    )
                }

                is MarkdownBlock.Table -> {
                    MarkdownTableGrid(
                        headers = block.headers,
                        rows = block.rows,
                        primaryColor = primaryColor,
                        inlineCodeBg = inlineCodeBg,
                        inlineCodeFg = inlineCodeFg,
                        onOpenUrl = openSafeUrl
                    )
                }

                is MarkdownBlock.BlockQuote -> {
                    val glass = com.example.ui.theme.LocalAgentGlassColors.current
                    val annotated = remember(block, primaryColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(block.inlines, primaryColor, inlineCodeBg, inlineCodeFg, openSafeUrl)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(12.dp))
                            .background(glass.nestedCardFill)
                            .border(1.dp, glass.nestedCardBorder, RoundedCornerShape(12.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .fillMaxHeight()
                                .background(primaryColor)
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                                lineHeight = 21.sp
                            ),
                            color = mutedColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = com.example.ui.theme.LocalAgentGlassColors.current.hairlineDivider
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeCard(
    language: String,
    code: String,
    onCopy: () -> Unit
) {
    val glass = com.example.ui.theme.LocalAgentGlassColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = glass.nestedCardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, glass.nestedCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(glass.pillFill)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onCopy)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("copy_code_block_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = MonoCodeStyle.copy(
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MarkdownTableGrid(
    headers: List<List<MarkdownInline>>,
    rows: List<List<List<MarkdownInline>>>,
    primaryColor: Color,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
    onOpenUrl: (String) -> Unit
) {
    val glass = com.example.ui.theme.LocalAgentGlassColors.current
    val borderColor = glass.nestedCardBorder
    val headerBg = glass.pillFill
    val altRowBg = glass.nestedCardFill

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .background(headerBg)
                    .height(IntrinsicSize.Min)
            ) {
                headers.forEachIndexed { colIdx, cellInlines ->
                    val annotated = remember(cellInlines, primaryColor, inlineCodeBg, inlineCodeFg) {
                        buildInlineAnnotatedString(cellInlines, primaryColor, inlineCodeBg, inlineCodeFg, onOpenUrl)
                    }
                    Box(
                        modifier = Modifier
                            .widthIn(min = 104.dp, max = 220.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (colIdx < headers.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(borderColor)
                        )
                    }
                }
            }

            // Data rows
            rows.forEachIndexed { rowIdx, row ->
                HorizontalDivider(color = borderColor)
                Row(
                    modifier = Modifier
                        .background(if (rowIdx % 2 == 1) altRowBg else Color.Transparent)
                        .height(IntrinsicSize.Min)
                ) {
                    row.forEachIndexed { colIdx, cellInlines ->
                        val annotated = remember(cellInlines, primaryColor, inlineCodeBg, inlineCodeFg) {
                            buildInlineAnnotatedString(cellInlines, primaryColor, inlineCodeBg, inlineCodeFg, onOpenUrl)
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(min = 104.dp, max = 220.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = annotated,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (colIdx < row.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildInlineAnnotatedString(
    inlines: List<MarkdownInline>,
    linkColor: Color,
    inlineCodeBg: Color,
    inlineCodeFg: Color,
    onOpenUrl: (String) -> Unit
): AnnotatedString = buildAnnotatedString {
    fun appendSpans(items: List<MarkdownInline>) {
        for (item in items) {
            when (item) {
                is MarkdownInline.Plain -> append(item.text)
                is MarkdownInline.Bold -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendSpans(item.children)
                    pop()
                }
                is MarkdownInline.Italic -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendSpans(item.children)
                    pop()
                }
                is MarkdownInline.BoldItalic -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    appendSpans(item.children)
                    pop()
                }
                is MarkdownInline.Strike -> {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendSpans(item.children)
                    pop()
                }
                is MarkdownInline.InlineCode -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = inlineCodeBg,
                            color = inlineCodeFg,
                            fontSize = 13.5.sp
                        )
                    )
                    append(" ${item.code} ")
                    pop()
                }
                is MarkdownInline.Link -> {
                    pushLink(
                        LinkAnnotation.Url(
                            url = item.url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            ),
                            linkInteractionListener = { onOpenUrl(item.url) }
                        )
                    )
                    append(item.label)
                    pop()
                }
            }
        }
    }
    appendSpans(inlines)
}
