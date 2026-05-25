package com.echonion.nion.ui.companion

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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

private const val TAG = "MarkdownText"

/**
 * 轻量级 Markdown 渲染组件 —— 将 AI 返回的 Markdown 文本渲染为 Compose UI。
 *
 * 支持的语法：
 * - 标题：`#` ~ `######`（以不同字号 + 加粗展示）
 * - 粗体：`**text**`、斜体：`*text*`、内联代码：`` `code` ``、链接 `[text](url)`
 * - 代码块：``` ``` ``` 包裹的多行代码块
 * - 无序列表：`- ` / `* `、有序列表：`1. `
 * - 任务列表：`- [ ]` / `- [x]`（带勾选框样式）
 * - 引用块：`> text`（左侧竖线 + 缩进）
 * - 树形结构：`├──` `│` `└──` box-drawing 字符（转为缩进层级列表）
 * - 表格：`| 列1 | 列2 |` 格式，表头加粗 + 分隔线
 * - 水平分割线：`---` / `***` / `___`
 * - 段落：连续非空行自动合并
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

    // 完整文本日志，用于调试 Markdown 解析（不受流式 delta 影响）
    Log.d(TAG, "=== FULL CONTENT START ===\n$content\n=== FULL CONTENT END ===")

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
                is MdBlock.TaskListBlock -> TaskListBlockBlock(block, style, textColor)
                is MdBlock.Blockquote -> BlockquoteBlock(block, style, textColor)
                is MdBlock.TreeBlock -> TreeBlockBlock(block, style, textColor)
                is MdBlock.TableBlock -> TableBlockBlock(block, style, textColor)
                is MdBlock.Paragraph -> ParagraphBlock(block, style, textColor)
                is MdBlock.HorizontalRule -> HorizontalRuleBlock()
            }
            if (index < blocks.size - 1) Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

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

    /**
     * 树形结构块：items 每项含 depth（层级深度，0=根）和 text（内容）。
     * 来源于 AI 使用 `├──` `│` `└──` box-drawing 字符构建的树形列表。
     */
    data class TreeBlock(val items: List<TreeItem>) : MdBlock()

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

/**
 * 树形结构条目 —— 对应 AI 使用 box-drawing 字符构建的层级列表。
 * @param depth 层级深度，0 = 顶层节点
 * @param text  节点文本（已去除 ├── │ └── 等前缀符号）
 */
data class TreeItem(val depth: Int, val text: String)

// ──────────── 树形结构检测 ────────────

/** box-drawing 分支字符集，用于检测树形结构行 */
private val TREE_CHARS = setOf('\u2502', '\u250C', '\u251C', '\u2514', '\u2500')
// \u2502 = │, \u250C = ┌, \u251C = ├, \u2514 = └, \u2500 = ─

/**
 * 判断一行是否为树形结构的行。
 * 条件：行首（跳过前导空格后）以 ├── │ 或 └── 开头。
 */
private fun isTreeLine(line: String): Boolean {
    val trimmed = line.trimStart()
    if (trimmed.isEmpty()) return false
    return trimmed.first() in TREE_CHARS
}

/**
 * 从一行树形文本中提取层级深度和内容文本。
 *
 * 解析规则：
 * - 每个 `│  `（│ + 2空格）或 `   `（3空格）算一级缩进（3字符一组）
 * - `├─ ` 或 `└─ ` 后面的部分为节点文本（单横线）
 * - 也兼容双横线 `├── ` / `└── `
 *
 * @param line 原始行文本
 * @return TreeItem(depth, text) 或 null（如果不是合法的树形节点行）
 */
private fun parseTreeLine(line: String): TreeItem? {
    val trimmed = line.trimEnd()
    if (trimmed.isEmpty()) return null

    var depth = 0
    var pos = 0

    // 统计前导缩进：每 3 字符（│ + 2空格 或 3空格）为一级
    while (pos + 3 <= trimmed.length) {
        val seg = trimmed.substring(pos, pos + 3)
        if (seg == "\u2502  " || seg == "   ") {
            depth++
            pos += 3
        } else {
            break
        }
    }

    // 跳过分支前缀（兼容单横线 └─ 和双横线 └──），提取文本内容
    val rest = trimmed.substring(pos)
    val content = when {
        // 单横线：├─ 或 └─（后跟空格或直接结尾）
        rest.startsWith("\u251C\u2500 ") -> rest.removePrefix("\u251C\u2500 ")
        rest.startsWith("\u2514\u2500 ") -> rest.removePrefix("\u2514\u2500 ")
        rest.startsWith("\u250C\u2500 ") -> rest.removePrefix("\u250C\u2500 ")
        // 双横线：├── 或 └──（后跟空格或直接结尾）
        rest.startsWith("\u251C\u2500\u2500 ") -> rest.removePrefix("\u251C\u2500\u2500 ")
        rest.startsWith("\u2514\u2500\u2500 ") -> rest.removePrefix("\u2514\u2500\u2500 ")
        rest.startsWith("\u250C\u2500\u2500 ") -> rest.removePrefix("\u250C\u2500\u2500 ")
        // 无尾部空格
        rest.startsWith("\u251C\u2500") -> rest.removePrefix("\u251C\u2500")
        rest.startsWith("\u2514\u2500") -> rest.removePrefix("\u2514\u2500")
        rest.startsWith("\u250C\u2500") -> rest.removePrefix("\u250C\u2500")
        rest.startsWith("\u251C\u2500\u2500") -> rest.removePrefix("\u251C\u2500\u2500")
        rest.startsWith("\u2514\u2500\u2500") -> rest.removePrefix("\u2514\u2500\u2500")
        rest.startsWith("\u250C\u2500\u2500") -> rest.removePrefix("\u250C\u2500\u2500")
        else -> rest
    }

    return TreeItem(depth = depth, text = content.trim())
}

/**
 * 判断一段代码文本是否实际上是一个树形结构。
 * 条件：超过一半的非空行以树形字符（├ └ │）或列表标记开头。
 */
private fun isTreeContent(code: String): Boolean {
    val lines = code.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    val treeLines = lines.count { line ->
        val trimmed = line.trimStart()
        trimmed.firstOrNull() in TREE_CHARS
    }
    // 超过一半的行是树形行，就认为是树形结构
    return treeLines >= (lines.size * 0.4).toInt().coerceAtLeast(1)
}

/**
 * 从代码文本中解析出树形结构条目列表。
 * 逐行调用 parseTreeLine，跳过无法解析的行（如纯标题行）。
 */
private fun parseTreeFromCode(code: String): List<TreeItem> {
    val items = mutableListOf<TreeItem>()
    for (line in code.lines()) {
        if (line.isBlank()) continue
        val item = parseTreeLine(line)
        if (item != null) items.add(item)
    }
    return items
}

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
 * 8. 树形结构 `├──` `└──` `│`
 * 9. 水平分割线 `---`
 * 10. 段落（兜底）
 */
private fun parseBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.lines()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        when {
            // ── 代码块（最高优先级，但检测树形内容时转为 TreeBlock） ──
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
                // 检测代码块内容是否为树形结构，如果是则按树渲染
                if (isTreeContent(codeContent)) {
                    val treeItems = parseTreeFromCode(codeContent)
                    if (treeItems.isNotEmpty()) {
                        blocks.add(MdBlock.TreeBlock(treeItems))
                    } else {
                        blocks.add(MdBlock.CodeBlock(codeContent))
                    }
                } else {
                    blocks.add(MdBlock.CodeBlock(codeContent))
                }
                i++ // 跳过结束的 ```
            }

            // ── 标题 ──
            Regex("""^(#{1,6})\s+(.+)$""").find(line) != null -> {
                val match = Regex("""^(#{1,6})\s+(.+)$""").find(line)!!
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
            line.matches(Regex("""^[-*]\s+\[[ xX]\]\s+.*""")) -> {
                val items = mutableListOf<TaskItem>()
                // 连续收集任务列表行
                while (i < lines.size && lines[i].matches(Regex("""^[-*]\s+\[[ xX]\]\s+.*"""))) {
                    val checked = lines[i].contains("[x]", ignoreCase = true)
                    // 去除 `- [x] ` 或 `- [ ] ` 前缀
                    val text = lines[i].replaceFirst(Regex("""^[-*]\s+\[[ xX]\]\s+"""), "")
                    items.add(TaskItem(checked = checked, text = text))
                    i++
                }
                blocks.add(MdBlock.TaskListBlock(items))
            }

            // ── 无序列表（不匹配任务列表的 - [ ] 格式） ──
            line.matches(Regex("""^[-*]\s+.+""")) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].matches(Regex("""^[-*]\s+.+"""))) {
                    items.add(lines[i].replaceFirst(Regex("""^[-*]\s+"""), ""))
                    i++
                }
                blocks.add(MdBlock.ListBlock(ordered = false, items = items))
            }

            // ── 有序列表 ──
            line.matches(Regex("""^\d+\.\s+.+""")) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].matches(Regex("""^\d+\.\s+.+"""))) {
                    items.add(lines[i].replaceFirst(Regex("""^\d+\.\s+"""), ""))
                    i++
                }
                blocks.add(MdBlock.ListBlock(ordered = true, items = items))
            }

            // ── 树形结构 ├── │ └── ──
            isTreeLine(line) -> {
                val treeItems = mutableListOf<TreeItem>()
                // 连续收集树形结构行
                while (i < lines.size && isTreeLine(lines[i])) {
                    val item = parseTreeLine(lines[i])
                    if (item != null) {
                        treeItems.add(item)
                    }
                    i++
                }
                if (treeItems.isNotEmpty()) {
                    blocks.add(MdBlock.TreeBlock(treeItems))
                }
            }

            // ── 水平分割线 ──
            line.matches(Regex("""^[-*_]{3,}\s*$""")) -> {
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
                    !lines[i].matches(Regex("""^(#{1,6}\s)""")) &&
                    !isTableLine(lines[i]) &&
                    !lines[i].trimStart().startsWith(">") &&
                    !lines[i].matches(Regex("""^[-*]\s""")) &&
                    !lines[i].matches(Regex("""^\d+\.\s""")) &&
                    !isTreeLine(lines[i])
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
) {
    Column {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // 有序列表使用 "1. " 格式，无序列表使用 "•  " 圆点
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
) {
    Column {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // 已完成项使用半透明颜色表示"已完成"状态
                val itemColor = if (item.checked) textColor.copy(alpha = 0.6f) else textColor
                // Material 图标：已完成为实心 CheckBox，未完成为空心 CheckBoxOutlineBlank
                // 图标大小与正文字号一致，居中对齐文字基线
                val iconSize = with(androidx.compose.ui.platform.LocalDensity.current) {
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
                Text(
                    text = parseInline(item.text, baseStyle, itemColor),
                    style = baseStyle,
                    color = itemColor,
                )
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
                    Text(
                        text = parseInline(line, baseStyle, textColor),
                        style = baseStyle.copy(
                            color = textColor.copy(alpha = 0.85f),
                        ),
                    )
                }
                if (index < block.lines.size - 1) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

/**
 * 渲染树形结构。
 * 每个节点根据 depth（层级深度）添加递增的左缩进（每级 16dp），
 * 并在节点前显示连接符号：顶层用 `•`，非顶层根据是否为末子节点选择 `├` 或 `└`。
 * 节点文本支持内联 Markdown 解析。
 */
@Composable
private fun TreeBlockBlock(
    block: MdBlock.TreeBlock,
    baseStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
) {
    Column {
        for ((index, item) in block.items.withIndex()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // 每级缩进 12dp，形成视觉层级
                Spacer(modifier = Modifier.width((item.depth * 12).dp))
                // 连接符号：根据与下一项的关系选择 ├ 或 └
                val connector = when {
                    item.depth == 0 -> "\u2022 " // 顶层节点用圆点
                    // 如果下一项的 depth <= 当前 depth，说明当前是最后一个子节点
                    index < block.items.size - 1 && block.items[index + 1].depth <= item.depth -> "\u2514\u2500 "
                    // 如果是整个列表的最后一项，也是末尾节点
                    index == block.items.size - 1 -> "\u2514\u2500 "
                    else -> "\u251C\u2500 " // 中间子节点用 ├─
                }
                Text(text = connector, style = baseStyle, color = textColor.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = parseInline(item.text, baseStyle, textColor),
                    style = baseStyle,
                    color = textColor,
                )
            }
            if (index < block.items.size - 1) {
                Spacer(modifier = Modifier.height(2.dp))
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
 * 支持：**粗体**、*斜体*、`代码`、~~删除线~~、[链接](url)
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
                                background = color.copy(alpha = 0.12f),
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
}
