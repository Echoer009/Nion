package com.echonion.nion.ui.notebook

/**
 * Markdown 工具函数集合 —— 提供从 Markdown 文本中提取纯文本预览等通用功能。
 *
 * 被 SharedTaskCard（笔记列表预览）、NoteSearchScreen（搜索结果预览）、
 * LinkItemDialog（关联选择器预览）等组件共用，避免多处重复实现。
 */

/**
 * 去除 Markdown 语法符号，提取纯文本预览。
 *
 * 移除常见 Markdown 标记（标题 #、粗体 **、斜体 *、删除线 ~~、行内代码 `、
 * 无序列表 -、有序列表 1.、引用 >、链接 [text](url)、图片 ![alt](url)），
 * 并将换行替换为空格，使预览文本单行可读。
 *
 * @param text 原始 Markdown 文本
 * @return 去除语法符号后的纯文本
 */
fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("^#{1,6}\\s+"), "") // 标题符号 # ~ ######
        .replace(Regex("\\*{1,3}(.+?)\\*{1,3}"), "$1") // 粗体/斜体 **text** / *text*
        .replace(Regex("~~(.+?)~~"), "$1") // 删除线 ~~text~~
        .replace(Regex("`([^`]+)`"), "$1") // 行内代码 `code`
        .replace(Regex("^[-*+]\\s+"), "") // 无序列表标记 - / * / +
        .replace(Regex("^\\d+\\.\\s+"), "") // 有序列表标记 1.
        .replace(Regex("^>\\s*"), "") // 引用 >
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // 链接 [text](url)
        .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1") // 图片 ![alt](url)
        .trim()
        .replace(Regex("\n+"), " ") // 换行转空格，保持单行可读
}

/**
 * 提取笔记正文预览：去除 Markdown 语法后截取指定长度。
 *
 * @param description 原始 Markdown 正文，null 或空白返回 null
 * @param maxChars 最大字符数，默认 80
 * @return 截取后的纯文本预览，若结果为空则返回 null
 */
fun notePreview(description: String?, maxChars: Int = 80): String? {
    return description
        ?.let { stripMarkdown(it) }
        ?.take(maxChars)
        ?.ifBlank { null }
}
