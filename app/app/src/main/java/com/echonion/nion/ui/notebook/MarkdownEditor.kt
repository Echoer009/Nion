package com.echonion.nion.ui.notebook

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.editor.MarkwonEditor
import io.noties.markwon.editor.MarkwonEditorTextWatcher
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markdown 编辑器组件 —— 使用 Markwon + MarkwonEditor 实现实时 Markdown 高亮渲染。
 *
 * 用户输入 `#` 开头的行会立即渲染为大标题样式，输入 `- ` 会渲染为列表样式，
 * 类似 Typora/Obsidian 的"所见即所得"编辑模式。Markdown 语法符号保留可见，
 * 但通过字体大小、粗细、颜色等样式实时渲染效果。
 *
 * 内部使用 AndroidView 包装 EditText，因为 MarkwonEditor 原生支持 EditText 的
 * Spannable 文本处理。Compose 的 TextField 目前无法直接应用 Android Span。
 *
 * @param text 当前 Markdown 文本内容
 * @param onTextChange 文本变更回调，每次编辑都会触发
 * @param modifier 外部修饰符
 */
@Composable
fun MarkdownEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 从 Material 主题获取颜色，确保暗色模式下文字可见
    val editorTextColor = MaterialTheme.colorScheme.onSurface
    val editorHintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val editorHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

    // 创建 Markwon 实例：配置表格、任务列表、自动链接
    // 不使用代码高亮（Prism4j 需额外注解处理器），后续迭代可加
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
    }

    // 创建 MarkwonEditor：将 Markwon 的渲染能力适配为 EditText 的实时编辑高亮
    val editor = remember(markwon) { MarkwonEditor.create(markwon) }

    // 保存上一次设置的文本，避免 onTextChange → setText 循环
    val lastSetText = remember { arrayOf(text) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            // 创建 EditText，配置多行编辑样式
            val editText = EditText(ctx).apply {
                // 透明背景，融入 Compose 主题
                setBackground(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                // 文字颜色：从 Material 主题获取，修复暗色模式下黑字黑底不可见的问题
                setTextColor(editorTextColor.toArgb())
                // 提示文字颜色（暗色模式下也需要适配）
                setHintTextColor(editorHintColor.toArgb())
                // 选中文字的高亮背景色
                highlightColor = editorHighlightColor.toArgb()
                // 内边距：左右 16dp，顶部 12dp，底部留 IME 空间
                val padH = (16 * resources.displayMetrics.density).toInt()
                val padV = (12 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                textSize = 15f
                setLineSpacing(4f, 1.1f)
                // 关键：文字从顶部开始，默认 center_vertical 会导致从中间开始写
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                isVerticalScrollBarEnabled = true
                setHorizontallyScrolling(false)
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

                // 初始设置文本（不触发 onTextChange）
                setText(text)

                // MarkwonEditor 的 TextWatcher：实时应用 Markdown 高亮样式。
                // withProcess 在文本变化后自动计算并应用 spans（如标题变大变粗、列表缩进等）
                addTextChangedListener(MarkwonEditorTextWatcher.withProcess(editor))

                // 额外的 TextWatcher：监听文本变化并回调外部。
                // 注意：MarkwonEditorTextWatcher 先执行（应用 spans），此 TextWatcher 后执行（回调）
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val newText = s?.toString() ?: ""
                        // 避免外部 setText 触发的回调再次回调外部
                        if (newText != lastSetText[0]) {
                            onTextChange(newText)
                        }
                    }
                })
            }
            editText
        },
        update = { editText ->
            // 外部 text 变化时（如加载笔记内容），同步到 EditText。
            // 判断条件防止 setText 触发的 onTextChange 反复回写
            if (text != editText.text.toString()) {
                lastSetText[0] = text
                editText.setText(text)
            } else {
                lastSetText[0] = text
            }
        },
    )
}
