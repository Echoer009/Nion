package com.echonion.nion.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.echonion.nion.ui.task.TaskItem
import com.echonion.nion.ui.theme.NionAlpha

/**
 * 笔记编辑页 —— 全屏 Dialog，包含标题输入、Markdown 实时编辑器和关联任务区域。
 *
 * 用户打开笔记后在此编辑标题和 Markdown 正文。正文使用 MarkdownEditor 组件
 * 实现 Typora 风格的实时渲染（输入 `#` 自动变为大标题样式）。
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
    onDismiss: () -> Unit,
) {
    // 正文使用 rememberSaveable，确保屏幕旋转等配置变更时内容不丢失
    var content by rememberSaveable(note.id) { mutableStateOf(note.description ?: "") }

    // 标题同样使用 rememberSaveable，确保屏幕旋转时不丢失
    var title by rememberSaveable(note.id) { mutableStateOf(note.name) }

    // 关联任务选择器对话框：true=显示，false=隐藏
    var showLinkDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 用 Column 包裹 Scaffold，在最顶部放一条彩色装饰条，
        // 视觉上区分笔记编辑页和普通任务页
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部彩色装饰条：4dp 高的 tertiary 色，标记笔记编辑器身份
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
            Scaffold(
                modifier = Modifier.weight(1f),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        // 返回按钮：关闭编辑器
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    title = {
                        // 可编辑标题：透明背景无边框的 TextField，实时回调持久化
                        OutlinedTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                onUpdateTitle(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "无标题",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            },
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.tertiary,
                            ),
                            shape = RoundedCornerShape(0.dp),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                // 底部工具栏：关联任务区域
                LinkedTasksBar(
                    linkedTasks = linkedTasks,
                    onAddLink = { showLinkDialog = true },
                    onUnlinkTask = onUnlinkTask,
                )
            },
        ) { innerPadding ->
            // Markdown 实时编辑器：占满剩余空间，IME padding 确保键盘不遮挡
            MarkdownEditor(
                text = content,
                onTextChange = {
                    content = it
                    onUpdateContent(it)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
            )
            } // 关闭 Scaffold → Column
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
