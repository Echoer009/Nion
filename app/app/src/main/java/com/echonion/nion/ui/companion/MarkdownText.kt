package com.echonion.nion.ui.companion

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val TAG = "MarkdownText"

/**
 * 轻量级 Markdown 渲染组件 —— 将 AI 返回的 Markdown 文本渲染为 Compose UI。
 *
 * 支持的语法：
 * - 标题：`# ## ###`（仅支持 H1-H3，以不同字号+加粗展示）
 * - 粗体：`**text**`、斜体：`*text*`、内联代码：`` `code` ``
 * - 代码块：``` ``` ``` 包裹的多行代码块
 * - 无序列表：`- ` / `* ` 、有序列表：`1. ` `2. `
 * - 表格：`| 列1 | 列2 |` 格式，表头加粗 + 分隔线，适配气泡宽
 * - 段落：连续的非空、非特殊行自动合并
 *
 * 崩溃保护：渲染异常时回退到纯文本显示，防止整个 App 崩溃。
 *
 * @param content  Markdown 原始文本
 * @param modifier 修饰符
 * @param style    普通文本的文字样式
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
) {
    val textColor = style.color

    val blocks = try {
        parseBlocks(content)
    } catch (e: Exception) {
        Log.e(TAG, "parse crash, fallback plain. ${content.take(200)}", e)
        Text(text = content, modifier = modifier, style = style, color = textColor)
        return
    }

    Column(modifier = modifier) {
        for ((index, block) in blocks.withIndex()) {
            when (block) {
                is MdBlock.Header -> HeaderBlock(block, textColor)
                is MdBlock.CodeBlock -> CodeBlockBlock(block, textColor)
                is MdBlock.ListBlock -> ListBlockBlock(block, style, textColor)
                is MdBlock.TableBlock -> TableBlockBlock(block, style, textColor)
                is MdBlock.Paragraph -> ParagraphBlock(block, style, textColor)
                is MdBlock.HorizontalRule -> HorizontalRuleBlock()
            }
            if (index < blocks.size - 1) Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ──────────── 块模型 ────────────

private sealed class MdBlock {
    data class Header(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock()
    data class TableBlock(
        val header: List<String>,
        val alignments: List<String>,
        val rows: List<List<String>>,
    ) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    /** 水平分割线：`---`、`***`、`___` */
    data object HorizontalRule : MdBlock()
}

// ──────────── 块级解析 ────────────

private fun parseBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.lines()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        when {
            // ── 代码块 ──
            line.trimStart().startsWith("```") -> {
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                while (codeLines.isNotEmpty() && codeLines.last().isBlank()) {
                    codeLines.removeAt(codeLines.lastIndex)
                }
                blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n")))
                i++ // 跳过结束 ```
            }

            else -> {
                val headerMatch = Regex("""^(#{1,6})\s+(.+)$""").find(line)
                if (headerMatch != null) {
                    val level = headerMatch.groupValues[1].length
                    val text = headerMatch.groupValues[2].trim()
                    blocks.add(MdBlock.Header(level, text))
                    i++
                }

                // ── 表格 ──
                else if (isTableLine(line)) {
                    val tableLines = mutableListOf<String>()
                    while (i < lines.size && isTableLine(lines[i])) {
                        tableLines.add(lines[i])
                        i++
                    }
                    // 仅输出原始表格文本（vivo 限 250行/秒，减少日志量）
                    Log.d(TAG, "TABLE_RAW:\n${tableLines.joinToString("\n")}")
                    val table = parseTable(tableLines)
                    if (table != null) blocks.add(table)
                    else blocks.add(MdBlock.Paragraph(tableLines.joinToString("\n")))
                }

                // ── 无序列表 ──
                else if (line.matches(Regex("""^[-*]\s+.+"""))) {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(Regex("""^[-*]\s+.+"""))) {
                        items.add(lines[i].replaceFirst(Regex("""^[-*]\s+"""), ""))
                        i++
                    }
                    blocks.add(MdBlock.ListBlock(ordered = false, items = items))
                }

                // ── 有序列表 ──
                else if (line.matches(Regex("""^\d+\.\s+.+"""))) {
                    val items = mutableListOf<String>()
                    while (i < lines.size && lines[i].matches(Regex("""^\d+\.\s+.+"""))) {
                        items.add(lines[i].replaceFirst(Regex("""^\d+\.\s+"""), ""))
                        i++
                    }
                    blocks.add(MdBlock.ListBlock(ordered = true, items = items))
                }

                // ── 水平分割线 ──
                else if (line.matches(Regex("""^[-*_]{3,}\s*$"""))) {
                    blocks.add(MdBlock.HorizontalRule)
                    i++
                }

                // ── 空行 ──
                else if (line.isBlank()) {
                    i++
                }

                // ── 普通段落 ──
                else {
                    val paragraphLines = mutableListOf<String>()
                    while (i < lines.size &&
                        lines[i].isNotBlank() &&
                        !lines[i].trimStart().startsWith("```") &&
                        !lines[i].matches(Regex("""^(#{1,6}\s)""")) &&
                        !isTableLine(lines[i]) &&
                        !lines[i].matches(Regex("""^[-*]\s""")) &&
                        !lines[i].matches(Regex("""^\d+\.\s"""))
                    ) {
                        paragraphLines.add(lines[i])
                        i++
                    }
                    if (paragraphLines.isNotEmpty()) {
                        blocks.add(MdBlock.Paragraph(paragraphLines.joinToString("\n")))
                    }
                }
            }
        }
    }
    return blocks
}

// ──────────── 表格解析 ────────────

private fun isTableLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("|") && trimmed.endsWith("|")) return true
    val pipeCount = trimmed.count { it == '|' }
    return pipeCount >= 1 && !trimmed.startsWith("`")
}

private val SEPARATOR_REGEX = Regex("""^[\|\-\:\s]+$""")

private fun parseTable(lines: List<String>): MdBlock.TableBlock? {
    Log.d(TAG, "parseTable: ${lines.size} lines, L0='${lines.getOrElse(0){""}.take(60)}', L1='${lines.getOrElse(1){""}.take(60)}'")
    if (lines.size < 2) {
        Log.d(TAG, "parseTable: too few lines (${lines.size})")
        return null
    }

    // 第二行必须是分隔行
    if (!SEPARATOR_REGEX.matches(lines[1].trim())) {
        Log.d(TAG, "parseTable: L1 not separator: '${lines[1].trim()}'")
        return null
    }

    // 解析表头
    val header = parseCells(lines[0])
    Log.d(TAG, "parseTable: header=$header")
    if (header == null || header.isEmpty()) {
        Log.d(TAG, "parseTable: header null/empty")
        return null
    }

    // 分隔行 → 对齐方式
    val sepCells = parseCells(lines[1])
    if (sepCells == null) {
        Log.d(TAG, "parseTable: cannot parse separator cells")
        return null
    }
    val alignments = sepCells.map { cell ->
        val s = cell.trim()
        when {
            s.startsWith(":") && s.endsWith(":") -> "center"
            s.endsWith(":") -> "right"
            else -> "left"
        }
    }.take(header.size)

    // 数据行
    val rows = mutableListOf<List<String>>()
    for (j in 2 until lines.size) {
        val cells = parseCells(lines[j])
        if (cells == null) {
            Log.d(TAG, "parseTable: row $j parseCells returned null, skipping")
            continue
        }
        val padded = cells.toMutableList()
        while (padded.size < header.size) padded.add("")
        rows.add(padded.take(header.size))
    }

    Log.d(TAG, "parseTable: OK header=${header.size}cols alignments=$alignments rows=${rows.size}")
    return MdBlock.TableBlock(header = header, alignments = alignments, rows = rows)
}

/**
 * 从一行表格文本中提取各单元格内容。
 * 按 `|` 分割，丢弃首尾空段，trim 每段。
 *
 * 示例：`"| 任务 | 优先级 |"` → `["任务", "优先级"]`
 */
private fun parseCells(line: String): List<String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val parts = trimmed.split("|")
    // 受首尾 | 影响，parts[0] 和 parts[last] 可能为空串，需跳过
    val start = if (parts.isNotEmpty() && parts[0].isEmpty()) 1 else 0
    val end = if (parts.size > 1 && parts.last().isEmpty()) parts.size - 1 else parts.size
    if (start >= end) {
        // 无有效列（如整行仅一个 |）
        return if (parts.size <= 1) null else emptyList()
    }
    return parts.subList(start, end).map { it.trim() }
}

// ──────────── 块渲染 ────────────

@Composable
private fun HeaderBlock(block: MdBlock.Header, textColor: androidx.compose.ui.graphics.Color) {
    val fontSize = when (block.level) {
        1 -> 20.sp
        2 -> 18.sp
        3 -> 16.sp
        4 -> 15.sp
        5 -> 14.sp
        else -> 13.sp
    }
    val fontWeight = if (block.level <= 4) FontWeight.Bold else FontWeight.SemiBold
    Text(
        text = block.text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize, fontWeight = fontWeight),
        color = textColor,
    )
}

@Composable
private fun CodeBlockBlock(
    block: MdBlock.CodeBlock,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = block.code,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            color = textColor,
        )
    }
}

@Composable
private fun ListBlockBlock(
    block: MdBlock.ListBlock,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Column {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                val marker = if (block.ordered) "${index + 1}. " else "\u2022  "
                Text(text = marker, style = baseStyle, color = textColor)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = parseInline(item, baseStyle, textColor), style = baseStyle)
            }
            if (index < block.items.size - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun TableBlockBlock(
    block: MdBlock.TableBlock,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
) {
    val header = block.header
    val rows = block.rows

    Log.d(TAG, "TableBlockBlock render: ${header.size} cols, ${rows.size} rows, header=[${header.joinToString(" | ")}]")

    if (header.isEmpty()) {
        Log.w(TAG, "TableBlockBlock: empty header, skip")
        return
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // ── 表头 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                for (colIndex in header.indices) {
                    val headerText = header[colIndex].trim()
                    // # 列是 AI 生成的编号列，表头显示为空白
                    val displayText = if (headerText == "#") "" else headerText
                    Text(
                        text = displayText,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        style = baseStyle.copy(fontWeight = FontWeight.Bold),
                        color = textColor,
                        textAlign = getCellAlignment(block.alignments, colIndex),
                        softWrap = true,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ── 数据行 ──
            for (rowIndex in rows.indices) {
                val row = rows[rowIndex]
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (colIndex in header.indices) {
                        val cellText = row.getOrElse(colIndex) { "" }
                        Text(
                            text = parseInline(cellText, baseStyle, textColor),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            style = baseStyle,
                            color = textColor,
                            textAlign = getCellAlignment(block.alignments, colIndex),
                            softWrap = true,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowIndex < rows.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

private fun getCellAlignment(alignments: List<String>, colIndex: Int): androidx.compose.ui.text.style.TextAlign {
    return when (alignments.getOrElse(colIndex) { "left" }) {
        "center" -> androidx.compose.ui.text.style.TextAlign.Center
        "right" -> androidx.compose.ui.text.style.TextAlign.End
        else -> androidx.compose.ui.text.style.TextAlign.Start
    }
}

@Composable
private fun ParagraphBlock(
    block: MdBlock.Paragraph,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Text(text = parseInline(block.text, baseStyle, textColor), style = baseStyle)
}

/** 渲染水平分割线：全宽细线，上下留间距 */
@Composable
private fun HorizontalRuleBlock() {
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
    Spacer(modifier = Modifier.height(4.dp))
}

// ──────────── 内联解析 ────────────

/**
 * 字符级扫描器，解析段落内内联 Markdown 并构建 AnnotatedString。
 *
 * 支持：**粗体**、*斜体*、`代码`、[链接](url)
 * 注意：本函数非 @Composable，不可调用 MaterialTheme 等 Composable API。
 */
private fun parseInline(
    raw: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(SpanStyle(color = color)) {
            var i = 0
            while (i < raw.length) {
                when {
                    // **粗体**
                    raw.startsWith("**", i) -> {
                        val end = raw.indexOf("**", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(raw.substring(i + 2, end))
                            }
                            i = end + 2
                        } else { append(raw[i]); i++ }
                    }

                    // *斜体*（不与 ** 冲突）
                    raw[i] == '*' && (i == 0 || raw[i - 1] != '*') -> {
                        val end = raw.indexOf('*', i + 1)
                        if (end != -1 && end < raw.length - 1 && raw[end + 1] != '*') {
                            if (end > i + 1) {
                                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                    append(raw.substring(i + 1, end))
                                }
                                i = end + 1
                            } else { append('*'); i++ }
                        } else { append('*'); i++ }
                    }

                    // `内联代码`
                    raw[i] == '`' -> {
                        val end = raw.indexOf('`', i + 1)
                        if (end != -1) {
                            withStyle(SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = (style.fontSize.value * 0.9f).sp,
                                background = color.copy(alpha = 0.12f),
                            )) {
                                append(raw.substring(i + 1, end))
                            }
                            i = end + 1
                        } else { append('`'); i++ }
                    }

                    // [链接](url)
                    raw.startsWith("[", i) -> {
                        val closeBracket = raw.indexOf("](", i + 1)
                        if (closeBracket != -1) {
                            val closeParen = raw.indexOf(')', closeBracket + 2)
                            if (closeParen != -1) {
                                append(raw.substring(i + 1, closeBracket))
                                i = closeParen + 1
                            } else { append('['); i++ }
                        } else { append('['); i++ }
                    }

                    // 普通字符
                    else -> { append(raw[i]); i++ }
                }
            }
        }
    }
}
