package com.echonion.nion.ui.notebook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.companion.MarkdownText
import com.echonion.nion.ui.task.TaskItem
import com.echonion.nion.ui.theme.NionAlpha

/**
 * 笔记编辑页 —— 全屏 Dialog，包含标题输入、Markdown 编辑/预览切换器和关联任务区域。
 *
 * 用户打开笔记后在此编辑标题和 Markdown 正文。正文区提供两种模式：
 * - 编辑态（previewMode = false）：纯文本多行输入框，原样编辑 Markdown 源码
 * - 预览态（previewMode = true）：调用 MarkdownText 渲染为富文本展示
 * 顶栏右侧的图标按钮切换两态，避免 Markwon 实时渲染带来的语法符号残留问题。
 * 底部显示已关联的任务，可通过关联选择器添加/移除关联。
 *
 * 每次文本变更都会立即回调 `onUpdate`，由调用方负责持久化到数据库。
 *
 * @param note 当前编辑的笔记数据
 * @param linkedTasks 已关联的任务列表
 * @param allTasks 所有可选的任务列表（用于关联选择器）
 * @param onUpdateTitle 标题变更回调
 * @param onUpdateContent 正文变更回调
 * @param onLinkTask 关联任务回调，传入任务 ID
 * @param onUnlinkTask 取消关联任务回调，传入任务 ID
 * @param sharedElementModifier shared element 动画 modifier，与列表中笔记卡片共享 bounds 实现打开放大/关闭缩回的 morph
 * @param onDismiss 关闭编辑器回调（返回按钮或外部返回手势）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookEditor(
    note: TaskItem,
    linkedTasks: List<TaskItem> = emptyList(),
    allTasks: List<TaskItem> = emptyList(),
    onUpdateTitle: (String) -> Unit,
    onUpdateContent: (String) -> Unit,
    onLinkTask: (String) -> Unit = {},
    onUnlinkTask: (String) -> Unit = {},
    /** shared element 动画 modifier：与列表中的笔记卡片共享 bounds，实现打开放大/关闭缩回的 morph 动画 */
    sharedElementModifier: Modifier,
    onDismiss: () -> Unit,
) {
    // 正文使用 rememberSaveable，确保屏幕旋转等配置变更时内容不丢失
    var content by rememberSaveable(note.id) { mutableStateOf(note.description ?: "") }

    // 标题同样使用 rememberSaveable，确保屏幕旋转时不丢失
    var title by rememberSaveable(note.id) { mutableStateOf(note.name) }

    // 编辑/预览模式：false=编辑态（纯文本输入），true=预览态（Markdown 渲染展示）
    // 使用 note.id 作为 key，切换笔记时重置为编辑态
    var previewMode by rememberSaveable(note.id) { mutableStateOf(false) }

    // 关联任务选择器对话框：true=显示，false=隐藏
    var showLinkDialog by remember { mutableStateOf(false) }

    // 半透明 scrim 遮罩：morph 期间（Surface 尚未铺满）可见，提供暗化背景
    // 点击空白区域关闭编辑器（铺满后被 Surface 完全遮挡，此回调仅在 morph 过程中可达）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = NionAlpha.OVERLAY_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 全屏编辑器 Surface：通过 sharedElementModifier 与笔记卡片共享 bounds
        // 打开时卡片放大 morph 为编辑器，关闭时编辑器缩回卡片
        // 不透明背景，确保 morph 过程中底层列表不透出
        Surface(
            modifier = sharedElementModifier
                .fillMaxSize()
                // 消费点击事件，阻止穿透到外层 scrim 误触发 onDismiss
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            color = MaterialTheme.colorScheme.surface,
        ) {
        Scaffold(
            topBar = {
                // 顶栏整体用 surfaceContainer 底色，与关联任务栏一致，形成统一的头部区域
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    // 取状态栏高度的约 60% 作为顶部间距（不是全量 statusBarsPadding），
                    // 让标题更贴近屏幕顶部但仍避开状态栏图标
                    val density = LocalDensity.current
                    val compactTopPad = with(density) {
                        (WindowInsets.statusBars.getTop(density) * 3 / 5).toDp()
                    }
                    // 标题行：用 Box 替代 IconButton 消除 48dp 最小触摸目标限制
                    // Row 实际高度由 Box(32dp) 决定，标题中心仅离屏幕顶 compactTopPad + 16dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = compactTopPad)
                            .padding(start = 4.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 返回按钮：自定义 Box 避免 IconButton 的 48dp minimumInteractiveComponentSize 约束
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onDismiss,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        // 可编辑标题：BasicTextField 无最小高度约束，文字在 Row 内垂直居中
                        BasicTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                onUpdateTitle(it)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                            decorationBox = { innerTextField ->
                                // 占位符：标题为空时显示灰色提示
                                if (title.isEmpty()) {
                                    Text(
                                        "无标题",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    )
                                }
                                innerTextField()
                            },
                        )
                        // 编辑/预览切换按钮：与左侧返回按钮同样的 Box(32dp) + clickable 样式，
                        // 避免 IconButton 的 48dp 最小触摸目标撑高整行。
                        // 编辑态显示 Visibility（点击进入预览），预览态显示 Edit（点击回到编辑）
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { previewMode = !previewMode },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (previewMode) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = if (previewMode) "切换到编辑" else "切换到预览",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    // 关联任务栏：紧接标题下方，元信息集中在顶部
                    LinkedTasksBar(
                        linkedTasks = linkedTasks,
                        onAddLink = { showLinkDialog = true },
                        onUnlinkTask = onUnlinkTask,
                    )
                }
            },
        ) { innerPadding ->
            // 正文区：AnimatedContent 按 previewMode 切换"编辑态"与"预览态"两个分支。
            // transitionSpec 使用纯淡入淡出（crossfade），不带位移，避免键盘弹起时
            // 与 IME padding 叠加产生跳动；ContentTransform 默认保留两分支布局，
            // 配合 SizeTransform(clip = false) 让高度变化更自然。
            AnimatedContent(
                targetState = previewMode,
                transitionSpec = {
                    // 150ms 短淡入淡出：足够看清切换，又不拖沓
                    fadeIn() togetherWith fadeOut()
                },
                label = "notebookEditorMode",
            ) { isPreview ->
                if (isPreview) {
                    // 预览态：用 MarkdownText 渲染为纯净富文本，语法符号消失，展示粗体/标题/列表等
                    // 独立滚动状态，确保切换回编辑态再回到预览态时滚动位置互不影响
                    val previewScrollState = rememberScrollState()
                    MarkdownText(
                        content = content,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .imePadding()
                            .verticalScroll(previewScrollState)
                            // 内边距与编辑态保持一致（左右 16dp、上下 12dp），保证视觉对齐
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    // 编辑态：纯文本多行输入，原样编辑 Markdown 源码，不依赖任何 Span 渲染。
                    // 用 foundation 的 BasicTextField（与标题输入框一致），它原生支持 cursorBrush
                    // 和 decorationBox；material3 的 TextField 无 cursorBrush 参数故不适用。
                    val scrollState = rememberScrollState()
                    BasicTextField(
                        value = content,
                        onValueChange = {
                            content = it
                            onUpdateContent(it)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .imePadding()
                            .verticalScroll(scrollState)
                            // 内边距对齐旧 MarkdownEditor 的视觉（左右 16dp、上下 12dp）
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                        decorationBox = { innerTextField ->
                            // 占位符：正文为空时显示灰色提示
                            if (content.isEmpty()) {
                                Text(
                                    "开始写笔记…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
            } // 关闭 Scaffold 内容 lambda → Surface → Box
        }
    }

    // 关联任务选择器对话框
    if (showLinkDialog) {
        LinkItemDialog(
            title = "关联任务",
            items = allTasks,
            linkedIds = linkedTasks.map { it.id }.toSet(),
            onLink = { taskId ->
                onLinkTask(taskId)
            },
            onUnlink = { taskId ->
                onUnlinkTask(taskId)
            },
            onDismiss = { showLinkDialog = false },
        )
    }
}

/**
 * 底部关联任务栏 —— 显示已关联的任务标签和添加按钮。
 *
 * 优化点：
 * - 空状态时添加按钮带 "关联任务" 文字标签，非空时只显示 + 图标
 * - 超过 3 个关联时 +N 可点击，打开关联选择器查看全部
 * - alpha 使用 NionAlpha 常量，保持全局视觉一致
 *
 * @param linkedTasks 已关联的任务列表
 * @param onAddLink 点击添加关联按钮时触发
 * @param onUnlinkTask 点击已关联任务标签时触发（取消关联）
 */
@Composable
private fun LinkedTasksBar(
    linkedTasks: List<TaskItem>,
    onAddLink: () -> Unit,
    onUnlinkTask: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 已关联的任务标签（最多显示 3 个）
            linkedTasks.take(3).forEach { task ->
                LinkedTaskChip(
                    taskName = task.name.ifBlank { "无标题任务" },
                    onClick = { onUnlinkTask(task.id) },
                )
            }
            // 超过 3 个时显示可点击的 "+N" 标签，点击打开关联选择器
            if (linkedTasks.size > 3) {
                Surface(
                    onClick = onAddLink,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = NionAlpha.BG_SUBTLE),
                ) {
                    Text(
                        "+${linkedTasks.size - 3}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            // 添加关联按钮：空状态带文字，非空状态只显示图标
            Surface(
                onClick = onAddLink,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = NionAlpha.BG_SUBTLE),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "关联任务",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    if (linkedTasks.isEmpty()) {
                        Text(
                            "关联任务",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 已关联任务的小标签 —— 点击可取消关联。
 * 使用 NionAlpha 常量统一透明度，保持视觉一致。
 *
 * @param taskName 任务名称
 * @param onClick 点击回调（取消关联）
 */
@Composable
private fun LinkedTaskChip(
    taskName: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = NionAlpha.BG_SUBTLE),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.Assignment,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                taskName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
            )
        }
    }
}
