package com.echonion.nion.ui.companion

import com.echonion.nion.ui.theme.NionAlpha
import android.util.Log
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import uniffi.nion_core.StickerData
import com.echonion.nion.ui.companion.sticker.StickerService

private const val TAG = "MarkdownText"

/**
 * 轻量级 Markdown 渲染组件 —— 将 AI 返回的 Markdown 文本渲染为 Compose UI。
 *
 * 支持的语法：
 * - 标题：`#` ~ `######`（以不同字号 + 加粗展示）
 * - 粗体：`**text**`、斜体：`*text*`、高亮：`==text==`、内联代码：`` `code` ``、链接 `[text](url)`
 * - 代码块：``` ``` ``` 包裹的多行代码块
 * - 无序列表：`- ` / `* `、有序列表：`1. `
 * - 任务列表：`- [ ]` / `- [x]`（带勾选框样式）
 * - 引用块：`> text`（左侧竖线 + 缩进）
 * - 表格：`| 列1 | 列2 |` 格式，表头加粗 + 分隔线
 * - 水平分割线：`---` / `***` / `___`
 * - 段落：连续非空行自动合并
 *
 * 崩溃保护：渲染异常时回退到纯文本显示，防止整个 App 崩溃。
 *
 * @param content  Markdown 原始文本
 * @param modifier 修饰符
 * @param style    普通文本的文字样式
 * @param stickers 可用的表情包列表，用于将 <标签名> 渲染为行内图片
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    stickers: List<StickerData> = emptyList(),
) {
    val textColor = style.color
    // 高亮背景色：primary 色 12% 透明度，跟随主题变化
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = NionAlpha.BG_HIGHLIGHT)
    // 构建标签名 → 表情包的映射表，供 parseInline 和渲染使用
    val stickerMap = remember(stickers) { stickers.associateBy { it.tag } }

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
                is MdBlock.Header -> HeaderBlock(block, textColor, stickerMap, highlightColor)
                is MdBlock.CodeBlock -> CodeBlockBlock(block, textColor)
                is MdBlock.ListBlock -> ListBlockBlock(block, style, textColor, stickerMap, highlightColor)
                is MdBlock.TaskListBlock -> TaskListBlockBlock(block, style, textColor, stickerMap, highlightColor)
                is MdBlock.Blockquote -> BlockquoteBlock(block, style, textColor, stickerMap, highlightColor)
                is MdBlock.TableBlock -> TableBlockBlock(block, style, textColor, stickerMap, highlightColor)
                is MdBlock.Paragraph -> ParagraphBlock(block, style, textColor, stickerMap, highlightColor)
                is MdBlock.HorizontalRule -> HorizontalRuleBlock()
            }
            if (index < blocks.size - 1) Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ──────────── 预编译正则（避免每次 parseBlocks 调用时重新编译） ────────────

/** 标题行正则：`# ` ~ `###### ` */
private val HEADER_REGEX = Regex("""^(#{1,6})\s+(.+)$""")

/** 任务列表行正则：`- []`、`- [ ]` 或 `- [x]`（方括号内字符可选） */
private val TASK_LIST_REGEX = Regex("""^[-*]\s+\[[ xX]?\]\s+.*""")

/** 任务列表前缀剥离正则 */
private val TASK_PREFIX_REGEX = Regex("""^[-*]\s+\[[ xX]?\]\s+""")

/** 无序列表行正则：`- ` 或 `* ` */
private val UNORDERED_LIST_REGEX = Regex("""^[-*]\s+.+""")

/** 无序列表前缀剥离正则 */
private val UNORDERED_PREFIX_REGEX = Regex("""^[-*]\s+""")

/** 有序列表行正则：`1. ` */
private val ORDERED_LIST_REGEX = Regex("""^\d+\.\s+.+""")

/** 有序列表前缀剥离正则 */
private val ORDERED_PREFIX_REGEX = Regex("""^\d+\.\s+""")

/** 水平分割线正则：`---`、`***`、`___` */
private val HR_REGEX = Regex("""^[-*_]{3,}\s*$""")

/** 段落检测：标题行 */
private val PARAGRAPH_HEADER_REGEX = Regex("""^(#{1,6}\s)""")

// ──────────── 块模型 ────────────

/**
 * Markdown 块级元素的数据模型。
 * 每种块类型对应一个子类，包含渲染所需的全部数据。
 */
private sealed class MdBlock {
    /** 标题块：level 为 1-6，text 为标题文字 */
    data class Header(val level: Int, val text: String) : MdBlock()

    /** 代码块：code 为代码内容（不含 ``` 围栏） */
    data class CodeBlock(val code: String) : MdBlock()

    /** 列表块：ordered 区分有序/无序，items 为条目文本列表 */
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock()

    /**
     * 任务列表块：items 中每项的 checked 标记完成/未完成，text 为条目文字。
     * 来源于 `- [x] 已完成` 或 `- [ ] 未完成` 语法。
     */
    data class TaskListBlock(val items: List<TaskItem>) : MdBlock()

    /**
     * 引用块：lines 为去除 `>` 前缀后的文本行。
     * 来源于 `> 引用文本` 语法，可连续多行。
     */
    data class Blockquote(val lines: List<String>) : MdBlock()

    data class TableBlock(
        val header: List<String>,
        val alignments: List<String>,
        val rows: List<List<String>>,
    ) : MdBlock()

    data class Paragraph(val text: String) : MdBlock()

    /** 水平分割线：`---`、`***`、`___` */
    data object HorizontalRule : MdBlock()
}

/**
 * 任务列表条目 —— 对应 `- [x] 文字` 或 `- [ ] 文字`。
 * @param checked 是否已完成（`[x]` 为 true，`[ ]` 为 false）
 * @param text    条目文本（已去除 `- [x]` 前缀）
 */
data class TaskItem(val checked: Boolean, val text: String)


// ──────────── 块级解析 ────────────

/**
 * 将 Markdown 文本拆分为块级元素的列表。
 *
 * 解析优先级（从高到低）：
 * 1. 代码块 ```` ``` ````（最高优先，内容原样保留）
 * 2. 标题 `#`
 * 3. 表格 `|`
 * 4. 引用块 `>`
 * 5. 任务列表 `- [ ]` / `- [x]`
 * 6. 无序列表 `- ` / `* `
 * 7. 有序列表 `1. `
 * 8. 水平分割线 `---`
 * 9. 段落（兜底）
 */
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
                // 收集代码行直到遇到结束的 ```
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                // 去掉末尾空行
                while (codeLines.isNotEmpty() && codeLines.last().isBlank()) {
                    codeLines.removeAt(codeLines.lastIndex)
                }
                val codeContent = codeLines.joinToString("\n")
                blocks.add(MdBlock.CodeBlock(codeContent))
                i++ // 跳过结束的 ```
            }

            // ── 标题 ──
            HEADER_REGEX.find(line) != null -> {
                val match = HEADER_REGEX.find(line)!!
                val level = match.groupValues[1].length
                val text = match.groupValues[2].trim()
                blocks.add(MdBlock.Header(level, text))
                i++
            }

            // ── 表格 ──
            isTableLine(line) -> {
                val tableLines = mutableListOf<String>()
                // 连续收集表格行
                while (i < lines.size && isTableLine(lines[i])) {
                    tableLines.add(lines[i])
                    i++
                }
                val table = parseTable(tableLines)
                if (table != null) blocks.add(table)
                else blocks.add(MdBlock.Paragraph(tableLines.joinToString("\n")))
            }

            // ── 引用块 > ──
            line.trimStart().startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                // 连续收集以 > 开头的行
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    // 去除 > 前缀和可能的一个空格
                    val content = lines[i].trimStart().removePrefix(">").let {
                        if (it.startsWith(" ")) it.substring(1) else it
                    }
                    quoteLines.add(content)
                    i++
                }
                blocks.add(MdBlock.Blockquote(quoteLines))
            }

            // ── 任务列表 - [ ] / - [x] ──
            TASK_LIST_REGEX.matches(line) -> {
                val items = mutableListOf<TaskItem>()
                // 连续收集任务列表行
                while (i < lines.size && TASK_LIST_REGEX.matches(lines[i])) {
                    val checked = lines[i].contains("[x]", ignoreCase = true)
                    // 去除 `- [x] ` 或 `- [ ] ` 前缀
                    val text = TASK_PREFIX_REGEX.replaceFirst(lines[i], "")
                    items.add(TaskItem(checked = checked, text = text))
                    i++
                }
                blocks.add(MdBlock.TaskListBlock(items))
            }

            // ── 无序列表（不匹配任务列表的 - [ ] 格式） ──
            UNORDERED_LIST_REGEX.matches(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && UNORDERED_LIST_REGEX.matches(lines[i])) {
                    items.add(UNORDERED_PREFIX_REGEX.replaceFirst(lines[i], ""))
                    i++
                }
                blocks.add(MdBlock.ListBlock(ordered = false, items = items))
            }

            // ── 有序列表 ──
            ORDERED_LIST_REGEX.matches(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED_LIST_REGEX.matches(lines[i])) {
                    items.add(ORDERED_PREFIX_REGEX.replaceFirst(lines[i], ""))
                    i++
                }
                blocks.add(MdBlock.ListBlock(ordered = true, items = items))
            }

            // ── 水平分割线 ──
            HR_REGEX.matches(line) -> {
                blocks.add(MdBlock.HorizontalRule)
                i++
            }

            // ── 空行 ──
            line.isBlank() -> {
                i++
            }

            // ── 普通段落（兜底：连续的非特殊行合并） ──
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size &&
                    lines[i].isNotBlank() &&
                    !lines[i].trimStart().startsWith("```") &&
                    !PARAGRAPH_HEADER_REGEX.matches(lines[i]) &&
                    !isTableLine(lines[i]) &&
                    !lines[i].trimStart().startsWith(">") &&
                    !UNORDERED_LIST_REGEX.matches(lines[i]) &&
                    !ORDERED_LIST_REGEX.matches(lines[i])
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
    return blocks
}

// ──────────── 表格解析 ────────────

/**
 * 判断一行是否为 Markdown 表格行。
 * 条件：以 | 开头和结尾，或者包含至少 1 个 | 且不以 ` 开头。
 */
private fun isTableLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("|") && trimmed.endsWith("|")) return true
    val pipeCount = trimmed.count { it == '|' }
    return pipeCount >= 1 && !trimmed.startsWith("`")
}

/** 表格分隔行正则：仅包含 |、-、:、空格 */
private val SEPARATOR_REGEX = Regex("""^[\|\-\:\s]+$""")

/**
 * 解析 Markdown 表格为 TableBlock。
 * @param lines 连续的表格行（至少含表头 + 分隔行）
 * @return 解析成功的 TableBlock，或 null（格式不合法时）
 */
private fun parseTable(lines: List<String>): MdBlock.TableBlock? {
    if (lines.size < 2) return null

    // 第二行必须是分隔行（如 |---|---|）
    if (!SEPARATOR_REGEX.matches(lines[1].trim())) return null

    val header = parseCells(lines[0]) ?: return null
    if (header.isEmpty()) return null

    // 从分隔行推断每列的对齐方式：:--- 左对齐，:---: 居中，---: 右对齐
    val sepCells = parseCells(lines[1]) ?: return null
    val alignments = sepCells.map { cell ->
        val s = cell.trim()
        when {
            s.startsWith(":") && s.endsWith(":") -> "center"
            s.endsWith(":") -> "right"
            else -> "left"
        }
    }.take(header.size)

    // 解析数据行，不足列数的补空串
    val rows = mutableListOf<List<String>>()
    for (j in 2 until lines.size) {
        val cells = parseCells(lines[j]) ?: continue
        val padded = cells.toMutableList()
        while (padded.size < header.size) padded.add("")
        rows.add(padded.take(header.size))
    }

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
        return if (parts.size <= 1) null else emptyList()
    }
    return parts.subList(start, end).map { it.trim() }
}

// ──────────── 块渲染 ────────────

/**
 * 渲染标题块。
 * H1-H4 用 Bold 字重，H5-H6 用 SemiBold。字号随级别递减。
 */
@Composable
private fun HeaderBlock(
    block: MdBlock.Header,
    textColor: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
) {
    val fontSize = when (block.level) {
        1 -> 20.sp
        2 -> 18.sp
        3 -> 16.sp
        4 -> 15.sp
        5 -> 14.sp
        else -> 13.sp
    }
    val fontWeight = if (block.level <= 4) FontWeight.Bold else FontWeight.SemiBold
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize, fontWeight = fontWeight)
    val parsed = parseInline(block.text, style, textColor, stickerMap, highlightColor)
    StickerAwareText(parsed, style, textColor, stickerMap)
}

/**
 * 渲染代码块。
 * 使用圆角 Surface 背景 + 等宽字体，模拟终端效果。
 */
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

/**
 * 渲染有序/无序列表。
 * 有序列表使用数字序号，无序列表使用 `•` 圆点。
 * 每个条目支持内联 Markdown 解析（粗体、斜体等）。
 */
@Composable
private fun ListBlockBlock(
    block: MdBlock.ListBlock,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
) {
    Column {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                val marker = if (block.ordered) "${index + 1}. " else "\u2022  "
                Text(text = marker, style = baseStyle, color = textColor)
                Spacer(modifier = Modifier.width(4.dp))
                val parsed = parseInline(item, baseStyle, textColor, stickerMap, highlightColor)
                StickerAwareText(parsed, baseStyle, textColor, stickerMap)
            }
            if (index < block.items.size - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/**
 * 渲染任务列表。
 * 每项前显示勾选框：[x] 已完成为实心勾选，[ ] 未完成为空心方框。
 * 条目文本支持内联 Markdown 解析，已完成项使用较浅的文字颜色。
 */
@Composable
private fun TaskListBlockBlock(
    block: MdBlock.TaskListBlock,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
) {
    Column {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                val itemColor = if (item.checked) textColor.copy(alpha = NionAlpha.TEXT_SECONDARY) else textColor
                val iconSize = with(LocalDensity.current) {
                    baseStyle.fontSize.toDp()
                }
                Icon(
                    imageVector = if (item.checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                    contentDescription = if (item.checked) "已完成" else "未完成",
                    modifier = Modifier
                        .width(iconSize)
                        .height(iconSize),
                    tint = itemColor,
                )
                Spacer(modifier = Modifier.width(6.dp))
                val parsed = parseInline(item.text, baseStyle, itemColor, stickerMap, highlightColor)
                StickerAwareText(parsed, baseStyle, itemColor, stickerMap)
            }
            if (index < block.items.size - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/**
 * 渲染引用块。
 * 左侧 3dp 竖线（主题色）+ 内容区域 12dp 左缩进。
 * 使用 IntrinsicSize.Min 让 Row 高度由内容决定，竖线自动匹配内容高度。
 * 引用内容逐行渲染，每行支持内联 Markdown 解析。
 */
@Composable
private fun BlockquoteBlock(
    block: MdBlock.Blockquote,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // 左侧竖线装饰，使用主题 primary 色，高度由兄弟 Column 决定
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(1.5.dp),
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.clipToBounds()) {
            for ((index, line) in block.lines.withIndex()) {
                if (line.isNotBlank()) {
                    val quoteColor = textColor.copy(alpha = NionAlpha.TEXT_HIGH)
                    val parsed = parseInline(line, baseStyle, quoteColor, stickerMap, highlightColor)
                    StickerAwareText(parsed, baseStyle.copy(color = quoteColor), quoteColor, stickerMap)
                }
                if (index < block.lines.size - 1) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

/**
 * 渲染表格。
 * 表头行背景加粗，与数据行之间用分割线分隔。
 * 每列使用 weight(1f) 等宽分配，支持对齐方式和文本溢出省略。
 */
@Composable
private fun TableBlockBlock(
    block: MdBlock.TableBlock,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
) {
    val header = block.header
    val rows = block.rows

    if (header.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // ── 表头行：加粗 + 深色背景 ──
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
                        val parsed = parseInline(cellText, baseStyle, textColor, stickerMap, highlightColor)
                        StickerAwareText(
                            parsed, baseStyle, textColor, stickerMap,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            textAlign = getCellAlignment(block.alignments, colIndex),
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (rowIndex < rows.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = NionAlpha.TEXT_HINT))
                }
            }
        }
    }
}

/** 根据表格分隔行语法推断单元格对齐方式 */
private fun getCellAlignment(alignments: List<String>, colIndex: Int): androidx.compose.ui.text.style.TextAlign {
    return when (alignments.getOrElse(colIndex) { "left" }) {
        "center" -> androidx.compose.ui.text.style.TextAlign.Center
        "right" -> androidx.compose.ui.text.style.TextAlign.End
        else -> androidx.compose.ui.text.style.TextAlign.Start
    }
}

/**
 * 渲染普通段落。
 * 整段文本通过 parseInline 解析内联 Markdown 后显示。
 */
@Composable
private fun ParagraphBlock(
    block: MdBlock.Paragraph,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
) {
    val parsed = parseInline(block.text, baseStyle, textColor, stickerMap, highlightColor)
    StickerAwareText(parsed, baseStyle, textColor, stickerMap)
}

/** 渲染水平分割线：全宽细线，上下留间距 */
@Composable
private fun HorizontalRuleBlock() {
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
    )
    Spacer(modifier = Modifier.height(4.dp))
}

// ──────────── 表情包内联渲染 ────────────

/** 匹配表情包标签的正则：<tag> 格式，tag 不含尖括号 */
private val STICKER_TAG_REGEX = Regex("""<([^<>]+)>""")

/**
 * 表情包内联解析结果 —— 包含解析后的 AnnotatedString 和发现的表情包标签集合。
 *
 * @param annotatedString 解析后的富文本
 * @param foundStickerTags 文本中发现的表情包标签名集合
 * @param stickerOffsets \uFFFC 占位符在 AnnotatedString 中的偏移量 → 对应标签名
 */
private data class InlineParseResult(
    val annotatedString: AnnotatedString,
    val foundStickerTags: Set<String>,
    val stickerOffsets: Map<Int, String>,
)

/**
 * 支持表情包的 Text 组件 —— 渲染文本并用 overlay 方式在 \uFFFC 占位符位置覆盖表情图片。
 * 通过 StickerService.loadForRender() 自适应采样解码，消除锯齿。
 *
 * @param result parseInline 的解析结果
 * @param style  文本样式
 * @param color  文本颜色
 * @param stickerMap 标签名 → 表情包数据的映射
 * @param modifier 修饰符
 * @param textAlign 文本对齐方式
 * @param maxLines  最大行数
 * @param overflow  文本溢出处理方式
 */
@Composable
private fun StickerAwareText(
    result: InlineParseResult,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData>,
    modifier: Modifier = Modifier,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    // 无表情包时使用普通 Text，避免 overlay 开销
    if (result.stickerOffsets.isEmpty()) {
        Text(
            text = result.annotatedString,
            style = style,
            color = color,
            modifier = modifier,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = true,
        )
        return
    }

    // 从 TextLayoutResult 中获取每个 \uFFFC 占位符的像素矩形
    val density = LocalDensity.current
    var stickerRects by remember(result.annotatedString) {
        mutableStateOf<List<Rect>>(emptyList())
    }
    val offsetTagPairs = result.stickerOffsets.entries.sortedBy { it.key }

    Box(modifier = modifier) {
        Text(
            text = result.annotatedString,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = true,
            onTextLayout = { textLayoutResult ->
                // 遍历所有占位符偏移量，获取其像素矩形
                val rects = offsetTagPairs.mapNotNull { (offset, _) ->
                    try {
                        textLayoutResult.getBoundingBox(offset)
                    } catch (_: Exception) { null }
                }
                stickerRects = rects
            },
        )

        // 在占位符位置覆盖表情图片（通过 StickerService 自适应采样，消除锯齿）
        for ((index, rect) in stickerRects.withIndex()) {
            if (index >= offsetTagPairs.size) break
            val tag = offsetTagPairs[index].value
            val sticker = stickerMap[tag] ?: continue
            // 自适应采样解码：targetSizePx 基于占位符高度 × density
            val targetPx = with(density) { (rect.height * density.density).toInt().coerceAtLeast(48) }
            val bitmap = remember(sticker.filePath) {
                StickerService.loadForRender(sticker.filePath, targetPx)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "<$tag>",
                    modifier = Modifier
                        .offset(
                            x = with(density) { rect.left.toDp() },
                            y = with(density) { rect.top.toDp() },
                        )
                        .size(
                            width = with(density) { rect.width.toDp() },
                            height = with(density) { rect.height.toDp() },
                        )
                        .scale(1.2f), // 视觉放大 20%，不改变布局尺寸
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

// ──────────── 内联解析 ────────────

/**
 * 字符级扫描器，解析段落内内联 Markdown 并构建 AnnotatedString。
 *
 * 支持：**粗体**、*斜体*、==高亮==、`代码`、~~删除线~~、[链接](url)、<表情包标签>
 * 注意：本函数非 @Composable，不可调用 MaterialTheme 等 Composable API。
 *
 * @param highlightColor ==高亮== 语法的背景色，由调用方从 MaterialTheme 主题获取
 */
private fun parseInline(
    raw: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    stickerMap: Map<String, StickerData> = emptyMap(),
    highlightColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
): InlineParseResult {
    // 预扫描所有 <tag> 匹配位置，用于字符级扫描器快速判断
    val tagMatches = STICKER_TAG_REGEX.findAll(raw).toList()
    val stickerPositions = mutableMapOf<Int, Pair<Int, String>>() // startPos → (endPos, tag)
    for (match in tagMatches) {
        val tag = match.groupValues[1]
        if (stickerMap.containsKey(tag)) {
            stickerPositions[match.range.first] = match.range.last + 1 to tag
        }
    }
    val foundTags = mutableSetOf<String>()
    // 记录每个 \uFFFC 占位符在 AnnotatedString 中的偏移量 → 标签名
    val stickerOffsetMap = mutableMapOf<Int, String>()

    val annotated = buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            // 优先检查当前位置是否是已知的表情包标签（在 withStyle 外部处理）
            val stickerInfo = stickerPositions[i]
            if (stickerInfo != null) {
                val (endPos, tag) = stickerInfo
                foundTags.add(tag)
                // 记录当前偏移量（即占位符将要被 append 的位置）
                stickerOffsetMap[length] = tag
                // 占位符需要占据空间（供 onTextLayout 定位），但必须透明不可见
                // 占位符 fontSize 取 1.7x 与 lineHeight 的较小值，确保不破坏行高
                val placeholderSize = (style.fontSize.value * 1.7f)
                val safeSize = if (style.lineHeight.isSp)
                    // lineHeight = 22sp, base=15sp, 取安全上限=lineHeight: 22 vs 25.5→22sp
                    placeholderSize.coerceAtMost(style.lineHeight.value).sp
                else
                    placeholderSize.sp
                withStyle(SpanStyle(
                    fontSize = safeSize,
                    color = androidx.compose.ui.graphics.Color.Transparent,
                )) {
                    append("\uFFFC")
                }
                i = endPos
                continue
            }
            // 非表情包内容，统一用基础颜色样式包裹
            withStyle(SpanStyle(color = color)) {
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

                    // ~~删除线~~
                    raw.startsWith("~~", i) -> {
                        val end = raw.indexOf("~~", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                            )) {
                                append(raw.substring(i + 2, end))
                            }
                            i = end + 2
                        } else { append(raw[i]); i++ }
                    }

                    // ==高亮==（primary 色半透明背景，像荧光笔效果）
                    raw.startsWith("==", i) -> {
                        val end = raw.indexOf("==", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(
                                background = highlightColor,
                            )) {
                                append(raw.substring(i + 2, end))
                            }
                            i = end + 2
                        } else { append(raw[i]); i++ }
                    }

                    // *斜体*（不与 ** 冲突：确保 * 前后都不是 *）
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
                                background = color.copy(alpha = NionAlpha.BG_HIGHLIGHT),
                            )) {
                                append(raw.substring(i + 1, end))
                            }
                            i = end + 1
                        } else { append('`'); i++ }
                    }

                    // [链接](url) —— 显示链接文字（暂不可点击跳转）
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
    return InlineParseResult(annotated, foundTags, stickerOffsetMap)
}
