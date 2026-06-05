package com.echonion.nion.ui.components

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.task.AttachmentButton
import com.echonion.nion.ui.task.AttachmentList
import com.echonion.nion.ui.task.AttachmentUiItem
import com.echonion.nion.ui.task.PrioritySelector
import com.echonion.nion.ui.task.RecurrenceSelector
import com.echonion.nion.ui.task.ReminderTimePicker
import com.echonion.nion.ui.task.TaskItem
import com.echonion.nion.ui.task.formatFocusTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 任务详情浮层内部的面板状态：
 * - DETAIL：显示任务详情（备注、工具栏）
 * - ADD_SUBTASK：显示子任务创建表单
 * - RECURRENCE：显示循环设置页面
 * - REMINDER：显示提醒设置页面（日历 + 时间滚轮）
 */
enum class DetailPanel { DETAIL, ADD_SUBTASK, RECURRENCE, REMINDER }

/**
 * 任务详情浮层 —— 从任务卡片位置 morph 展开为居中的任务详情/子任务表单。
 *
 * 内部使用 AnimatedContent 在「任务详情」「添加子任务表单」「日期选择」「附件管理」四个面板之间切换，
 * 三种状态共享的卡片通过 sharedElementModifier 与触发卡片做 morph 动画。
 *
 * 卡片本身无边框，通过 tonalElevation 形成粗阴影边缘（与任务列表卡片风格一致）。
 *
 * @param task 要展示详情的任务对象
 * @param onDismiss 关闭回调（点击外部区域）
 * @param onDelete 删除任务回调
 * @param onCreateSubtask 创建子任务回调，传入 (标题, 优先级)
 * @param onUpdateNotes 备注内容变更时触发，自动保存到数据库
 * @param onUpdateReminder 提醒时间变更回调，传入 "YYYY-MM-DDTHH:MM" 格式或 null（清除）
 * @param onUpdateRecurrence 循环设置变更回调，传入 (recurrenceRule, reminderTime)
 * @param onRemoveRecurrence 移除循环回调
 * @param sharedElementModifier shared element 动画 modifier（与触发卡片共享 key）
 * @param onStartFocus 用户点击"开始专注"按钮时触发，传入预选的专注时长（分钟）
 * @param attachments 当前任务的附件列表
 * @param onPickImage 点击添加图片附件时触发
 * @param onPickFile 点击添加文件附件时触发
 * @param onRemoveAttachment 删除附件时触发，传入附件 ID
 * @param onPreviewImage 预览图片附件时触发，传入附件 UI 模型
 * @param onRename 任务标题重命名回调，传入新标题
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TaskDetailOverlay(
    task: TaskItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onCreateSubtask: (String, String) -> Unit,
    onUpdateNotes: (String) -> Unit,
    /** 更新每日循环设置回调，传入 (recurrenceRule, reminderTime) */
    onUpdateRecurrence: (String?, String?) -> Unit = { _, _ -> },
    /** 移除每日循环回调 */
    onRemoveRecurrence: () -> Unit = {},
    /** 更新一次性提醒时间回调，传入 "YYYY-MM-DDTHH:MM" 或 null（清除） */
    onUpdateReminder: (String?) -> Unit = {},
    sharedElementModifier: Modifier,
    /** 用户点击"开始专注"按钮时触发，传入预选的专注时长（分钟） */
    onStartFocus: (Int) -> Unit = {},
    /** 当前任务的附件列表 */
    attachments: List<AttachmentUiItem> = emptyList(),
    /** 点击添加图片附件时触发 */
    onPickImage: () -> Unit = {},
    /** 点击添加文件附件时触发 */
    onPickFile: () -> Unit = {},
    /** 删除附件时触发 */
    onRemoveAttachment: (String) -> Unit = {},
    /** 预览图片附件时触发 */
    onPreviewImage: (AttachmentUiItem) -> Unit = {},
    /** 任务标题重命名回调，传入新标题 */
    onRename: (String) -> Unit = {},
) {
    // 备注内容：初始值为任务描述，可随时编辑，每次变更自动保存
    var notes by remember(task.id) { mutableStateOf(task.description ?: "") }
    // 当前面板状态：DETAIL=详情 / ADD_SUBTASK=添加子任务
    var panel by remember { mutableStateOf(DetailPanel.DETAIL) }
    // focusExpanded: 专注行是否展开（播放按钮移到左侧，显示预选时长）
    var focusExpanded by remember { mutableStateOf(false) }
    // selectedDuration: 预选专注时长（分钟），可在 25/45/60 之间循环切换
    var selectedDuration by remember { mutableStateOf(25) }

    // 半透明遮罩 + 点击外部关闭（与 AddTaskOverlay 一致的 scrim 背景，防止交叉淡入淡出期间背景透出）
    BoxWithConstraints(
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
        // maxCardHeight：卡片允许的最大高度 = 屏幕高度的 85%，保证不超出屏幕
        val maxCardHeight = maxHeight * 0.85f

        // 卡片：通过 sharedElementModifier 与触发卡片共享 bounds 动画
        // 无边框，使用 tonalElevation=12dp 形成粗阴影边缘
        // heightIn 限制最大高度，防止内容过多时扩出屏幕
        Surface(
            modifier = sharedElementModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maxCardHeight)
                // 消费卡片上的点击事件，阻止穿透到外层遮罩触发 onDismiss
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 12.dp,
        ) {
            // 内部 AnimatedContent：在四个面板之间切换
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    if (targetState != DetailPanel.DETAIL) {
                        // 展开子表单/日期选择：快速淡入新面板，缓慢淡出详情
                        (fadeIn(tween(280, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    } else {
                        // 返回详情：快速淡入详情，缓慢淡出面板
                        (fadeIn(tween(200, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(350, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    }
                },
                label = "detailPanel",
            ) { currentPanel ->
                when (currentPanel) {
                    DetailPanel.ADD_SUBTASK -> {
                        AddSubtaskPanel(
                            taskName = task.name,
                            onCreateSubtask = onCreateSubtask,
                            onBack = { panel = DetailPanel.DETAIL },
                        )
                    }

                    DetailPanel.RECURRENCE -> {
                        RecurrencePanel(
                            task = task,
                            onUpdateRecurrence = onUpdateRecurrence,
                            onRemoveRecurrence = onRemoveRecurrence,
                            onBack = { panel = DetailPanel.DETAIL },
                        )
                    }

                    DetailPanel.REMINDER -> {
                        ReminderPanel(
                            task = task,
                            onUpdateReminder = onUpdateReminder,
                            onBack = { panel = DetailPanel.DETAIL },
                        )
                    }

                    DetailPanel.DETAIL -> {
                        // 任务详情面板：用 Column + weight 实现自适应高度布局
                        // 避免多次 subcompose 导致的闪烁问题
                        Column(
                            modifier = Modifier
                                .heightIn(max = maxCardHeight)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // 顶部标题行，支持点击标题进入编辑模式
                            DetailPanelHeader(
                                taskName = task.name,
                                onDismiss = onDismiss,
                                onRename = onRename,
                            )

                            // 循环 + 提醒快捷入口行
                            DetailRecurrenceReminderRow(
                                task = task,
                                onNavigateToRecurrence = { panel = DetailPanel.RECURRENCE },
                                onNavigateToReminder = { panel = DetailPanel.REMINDER },
                            )

                            // 可编辑备注区域，weight 占据 header/footer 之外的剩余空间
                            DetailNotesField(
                                notes = notes,
                                onUpdateNotes = {
                                    notes = it
                                    onUpdateNotes(it)
                                },
                                onPickImage = onPickImage,
                                onPickFile = onPickFile,
                                modifier = Modifier.weight(1f, fill = false),
                            )

                            // 底部固定区域：专注信息 + 附件 + 工具栏，始终显示
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                FocusInfoRow(
                                    taskId = task.id,
                                    focusSeconds = task.focusSeconds,
                                    focusExpanded = focusExpanded,
                                    onFocusExpandedChange = { focusExpanded = it },
                                    selectedDuration = selectedDuration,
                                    onSelectedDurationChange = { selectedDuration = it },
                                    onStartFocus = onStartFocus,
                                )

                                // 附件列表
                                if (attachments.isNotEmpty()) {
                                    AttachmentList(
                                        attachments = attachments,
                                        onRemove = onRemoveAttachment,
                                        onPreview = onPreviewImage,
                                    )
                                }

                                // 底部工具栏
                                DetailBottomToolbar(
                                    onAddSubtask = { panel = DetailPanel.ADD_SUBTASK },
                                    onDelete = onDelete,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 私有子 Composable ====================

/**
 * 添加子任务面板 —— 包含标题输入框、优先级选择和操作按钮。
 *
 * 点击取消或成功创建后通过 onBack 返回详情面板。
 * 标题和优先级为面板内部临时状态，随面板生命周期创建和销毁。
 *
 * @param taskName 父任务名称，显示为"添加到：xxx"提示
 * @param onCreateSubtask 创建子任务回调，传入 (标题, 优先级)；仅当标题非空时触发
 * @param onBack 返回详情面板回调，点击取消或成功创建后触发
 */
@Composable
private fun AddSubtaskPanel(
    taskName: String,
    onCreateSubtask: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    // 子任务标题输入内容
    var subtaskTitle by remember { mutableStateOf("") }
    // 子任务优先级，默认 medium
    var subtaskPriority by remember { mutableStateOf("medium") }
    // 自动聚焦标题输入框，面板出现时立即呼出键盘
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 标题栏：图标 + "新建子任务" 文字 + 关闭按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                "新建子任务",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        // 提示：正在为哪个任务添加子任务
        Text(
            "添加到：$taskName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 子任务标题输入框
        OutlinedTextField(
            value = subtaskTitle,
            onValueChange = { subtaskTitle = it },
            label = { Text("标题") },
            placeholder = { Text("输入子任务标题...") },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            singleLine = true,
        )

        // 优先级选择
        Text(
            "优先级",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrioritySelector(selected = subtaskPriority, onSelect = { subtaskPriority = it })

        // 操作按钮：取消 + 添加
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    subtaskTitle = ""
                    subtaskPriority = "medium"
                    onBack()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text("取消", fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = {
                    if (subtaskTitle.isNotBlank()) {
                        onCreateSubtask(subtaskTitle.trim(), subtaskPriority)
                        subtaskTitle = ""
                        subtaskPriority = "medium"
                        onBack()
                    }
                },
                enabled = subtaskTitle.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            ) { Text("添加", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/**
 * 循环设置面板 —— 设置任务的每日循环规则和提醒时间。
 *
 * 使用 RecurrenceSelector 让用户选择循环规则，本地编辑 localRule/localTime，
 * 点保存时才通过 onUpdateRecurrence 提交到数据库。
 *
 * @param task 当前任务，用于读取现有循环设置和判断是否显示"移除循环"按钮
 * @param onUpdateRecurrence 保存循环设置回调，传入 (recurrenceRule, reminderTime)
 * @param onRemoveRecurrence 移除循环回调，点击"移除循环"按钮时触发
 * @param onBack 返回详情面板回调，点击取消/保存/移除后触发
 */
@Composable
private fun RecurrencePanel(
    task: TaskItem,
    onUpdateRecurrence: (String?, String?) -> Unit,
    onRemoveRecurrence: () -> Unit,
    onBack: () -> Unit,
) {
    // 本地编辑中的循环规则，初始值为任务当前设置，按 task.id 重置
    var localRule by remember(task.id) { mutableStateOf(task.recurrenceRule) }
    // 本地编辑中的提醒时间，初始值为任务当前设置，按 task.id 重置
    var localTime by remember(task.id) { mutableStateOf(task.recurrenceReminderTime) }

    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 标题栏：图标 + "重复设置" + 关闭按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Repeat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                "重复设置",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        // 提示正在为哪个任务设置循环
        Text(
            "为「${task.name}」设置每日循环",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 循环选择器：不重复 / 每天 + 提醒时间
        RecurrenceSelector(
            recurrenceRule = localRule,
            reminderTime = localTime,
            onRecurrenceChanged = { localRule = it },
            onReminderTimeChanged = { localTime = it },
        )

        // 操作按钮：移除循环（条件显示） + 取消 + 保存
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 移除循环按钮（仅当已设置循环时显示）
            if (task.recurrenceRule == "daily") {
                TextButton(
                    onClick = {
                        onRemoveRecurrence()
                        onBack()
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error.copy(alpha = NionAlpha.TEXT_MEDIUM),
                    ),
                ) {
                    Text("移除循环", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            TextButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1) }
            Button(
                onClick = {
                    onUpdateRecurrence(localRule, localTime)
                    onBack()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
            ) { Text("保存", fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/**
 * 提醒设置面板 —— 设置任务的一次性提醒时间。
 *
 * 内嵌 ReminderTimePicker（startExpanded=true：直接显示日历，隐藏标题行），
 * ReminderTimePicker 自带 清除/取消/确定 按钮，onDismiss 负责返回 DETAIL 面板。
 *
 * @param task 当前任务，用于读取现有提醒设置
 * @param onUpdateReminder 提醒时间变更回调，传入 "YYYY-MM-DDTHH:MM" 格式或 null（清除）
 * @param onBack 返回详情面板回调，ReminderTimePicker 关闭时触发
 */
@Composable
private fun ReminderPanel(
    task: TaskItem,
    onUpdateReminder: (String?) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 标题栏：图标 + "提醒设置" + 关闭按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Alarm,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                "提醒设置",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = "关闭")
            }
        }

        // 使用 ReminderTimePicker（startExpanded=true：直接显示日历，无标题行）
        // onReminderChanged 只管数据，onDismiss 负责返回 DETAIL 面板
        ReminderTimePicker(
            reminder = task.reminder,
            onReminderChanged = { newReminder ->
                onUpdateReminder(newReminder)
            },
            startExpanded = true,
            onDismiss = onBack,
        )
    }
}

/**
 * 任务详情面板顶部标题行 —— 显示任务标题和关闭按钮，支持点击标题进入内联编辑模式。
 *
 * 点击标题文字后切换为 OutlinedTextField，键盘 Done 或失焦时保存新标题。
 * 参考 SettingsScreen 的主题重命名模式：isEditing 状态 + focusRequester + hasBeenFocused 防误触。
 *
 * @param taskName 任务标题文本
 * @param onDismiss 关闭浮层回调，点击关闭按钮时触发
 * @param onRename 标题修改后的保存回调，传入新标题
 */
@Composable
private fun DetailPanelHeader(
    taskName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    // isEditing: 是否处于标题编辑模式
    var isEditing by remember { mutableStateOf(false) }
    // editName: 编辑中的标题文本
    var editName by remember { mutableStateOf(taskName) }
    // focusRequester: 用于编辑态自动聚焦输入框
    val focusRequester = remember { FocusRequester() }
    // hasBeenFocused: 防止初次组合时 onFocusChanged 误触发保存（焦点从未获得→失焦不应保存）
    var hasBeenFocused by remember { mutableStateOf(false) }

    // 进入编辑态时自动聚焦并弹出键盘
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

    /**
     * 提交标题修改：标题非空且与原标题不同时才保存，然后退出编辑态。
     */
    fun submitRename() {
        if (!isEditing) return
        val trimmed = editName.trim()
        if (trimmed.isNotBlank() && trimmed != taskName) {
            onRename(trimmed)
        }
        isEditing = false
        hasBeenFocused = false
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isEditing) {
            // 编辑态：显示输入框，支持 Done / 失焦保存
            OutlinedTextField(
                value = editName,
                onValueChange = { editName = it },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            // 标记已获得过焦点，后续失焦才触发保存
                            hasBeenFocused = true
                        } else if (hasBeenFocused) {
                            // 失焦且有变更 → 提交保存
                            submitRename()
                        }
                    },
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { submitRename() },
                ),
            )
        } else {
            // 展示态：只读标题，点击进入编辑
            Text(
                taskName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        editName = taskName
                        hasBeenFocused = false
                        isEditing = true
                    },
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
        }
    }
}

/**
 * 循环/提醒快捷入口行 —— 左右并排显示循环和提醒状态，点击可进入对应设置面板。
 *
 * 左半区显示循环设置入口（激活时显示"每天 HH:MM"，未激活时显示"设置重复"），
 * 右半区显示提醒设置入口（激活时显示"MM月DD日 HH:MM"，未激活时显示"设置提醒"）。
 *
 * @param task 当前任务，用于读取循环规则和提醒时间
 * @param onNavigateToRecurrence 点击循环入口时触发，导航到循环设置面板
 * @param onNavigateToReminder 点击提醒入口时触发，导航到提醒设置面板
 */
@Composable
private fun DetailRecurrenceReminderRow(
    task: TaskItem,
    onNavigateToRecurrence: () -> Unit,
    onNavigateToReminder: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 左半区：循环设置入口，点击进入 RECURRENCE 面板
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNavigateToRecurrence,
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Repeat,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                // 重复激活状态使用 secondary（信息性指示）
                tint = if (task.recurrenceRule == "daily") MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (task.recurrenceRule == "daily") {
                    if (task.recurrenceReminderTime != null) "每天 ${task.recurrenceReminderTime}"
                    else "每天提醒"
                } else {
                    "设置重复"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (task.recurrenceRule == "daily") FontWeight.Medium else FontWeight.Normal,
                color = if (task.recurrenceRule == "daily") MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
            )
        }

        // 右半区：提醒设置入口，点击进入 REMINDER 面板
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNavigateToReminder,
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Alarm,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                // 提醒激活状态使用 secondary（信息性指示）
                tint = if (task.reminder != null) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = task.reminder?.let { r ->
                    try {
                        val ldt = LocalDateTime.parse(
                            r, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                        )
                        "%d月%d日 %02d:%02d".format(ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
                    } catch (_: Exception) { "已设置提醒" }
                } ?: "设置提醒",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (task.reminder != null) FontWeight.Medium else FontWeight.Normal,
                color = if (task.reminder != null) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
            )
        }
    }
}

/**
 * 备注编辑区域 —— 可编辑的备注输入框，右下角内嵌附件按钮。
 *
 * 使用 clip 防止附件按钮展开时溢出框外。内容少时收缩（最低 120dp），
 * 内容多时自动扩展，超出后内部滚动。外部通过 modifier 传入 weight 控制在 Column 中的占比。
 *
 * @param notes 当前备注内容
 * @param onUpdateNotes 备注内容变更回调，传入新文本；由调用方负责同步更新本地状态和持久化
 * @param onPickImage 点击添加图片附件时触发
 * @param onPickFile 点击添加文件附件时触发
 * @param modifier 外部传入的 modifier（用于 Column 中的 weight 布局）
 */
@Composable
private fun DetailNotesField(
    notes: String,
    onUpdateNotes: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        OutlinedTextField(
            value = notes,
            onValueChange = onUpdateNotes,
            placeholder = { Text("添加备注...") },
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                // 备注输入框聚焦边框使用 tertiary（装饰性区分）
                focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
        // 附件按钮：定位在备注输入框的右下角
        AttachmentButton(
            onPickImage = onPickImage,
            onPickFile = onPickFile,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * 专注信息行 —— 两步交互的专注计时器控件。
 *
 * 状态 A（默认）：[时钟图标 专注时间 Xh Xm 播放按钮]，点击播放按钮进入状态 B
 * 状态 B（展开）：[播放按钮 N分钟上下箭头]，播放按钮从右侧滑动到左侧，右侧显示预选时长
 *   - 点击 "N分钟 上下箭头" 循环切换 25 -> 45 -> 60 -> 25
 *   - 点击播放按钮启动计时并跳转专注页面
 *
 * 使用局部 SharedTransitionLayout + sharedBounds 让播放按钮在过渡期间保持可见，
 * 从右侧平滑平移到左侧，而非淡入淡出。
 *
 * @param taskId 任务 ID，用于 sharedBounds 的唯一 key
 * @param focusSeconds 已累计的专注时长（秒），用于格式化显示
 * @param focusExpanded 专注行是否处于展开状态（状态 B）
 * @param onFocusExpandedChange 展开状态变更回调，用户点击播放按钮时触发并传入 true
 * @param selectedDuration 预选的专注时长（分钟），在 25/45/60 之间循环
 * @param onSelectedDurationChange 预选时长变更回调，用户点击时长切换器时触发
 * @param onStartFocus 点击开始专注时触发，传入预选时长（分钟）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FocusInfoRow(
    taskId: String,
    focusSeconds: Long,
    focusExpanded: Boolean,
    onFocusExpandedChange: (Boolean) -> Unit,
    selectedDuration: Int,
    onSelectedDurationChange: (Int) -> Unit,
    onStartFocus: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = focusExpanded,
                transitionSpec = {
                    // 淡入淡出 300ms，播放按钮由 sharedBounds 独立处理位移动画
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(300, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                },
                label = "focusRow",
            ) { expanded ->
                // 捕获 AnimatedVisibilityScope，避免 this@AnimatedContent 标签歧义
                val animatedScope = this
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!expanded) {
                        // 状态 A 左侧：时钟图标 + 专注时间文字
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    "专注时间",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    formatFocusTime(focusSeconds),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    // 状态 B 左侧播放按钮：放在 Spacer 之前，确保在最左端
                    // 与状态 A 右侧的播放按钮共享 sharedBounds key，
                    // 过渡期间从右侧平滑滑动到最左侧
                    if (expanded) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    "focusPlayBtn_$taskId"
                                ),
                                animatedVisibilityScope = animatedScope,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.75f,
                                        stiffness = Spring.StiffnessMedium,
                                    )
                                },
                            ),
                            onClick = { onStartFocus(selectedDuration) },
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "开始专注",
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(20.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 状态 A 右侧播放按钮：点击后进入状态 B
                    // 与状态 B 左侧的播放按钮共享 sharedBounds key，
                    // 过渡期间从当前位置滑动到左侧
                    if (!expanded) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    "focusPlayBtn_$taskId"
                                ),
                                animatedVisibilityScope = animatedScope,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.75f,
                                        stiffness = Spring.StiffnessMedium,
                                    )
                                },
                            ),
                            onClick = { onFocusExpandedChange(true) },
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "开始专注",
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(20.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }

                    if (expanded) {
                        // 状态 B 右侧：预选专注时长，点击循环切换 25 -> 45 -> 60 -> 25
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                onSelectedDurationChange(
                                    when (selectedDuration) {
                                        25 -> 45
                                        45 -> 60
                                        else -> 25
                                    }
                                )
                            },
                        ) {
                            Text(
                                "${selectedDuration}分钟",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Default.ArrowDropUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部工具栏 —— 包含"添加子任务"和"删除"两个操作按钮，以分隔圆点分开。
 *
 * @param onAddSubtask 点击添加子任务按钮时触发，通常用于切换到 ADD_SUBTASK 面板
 * @param onDelete 点击删除按钮时触发，用于删除当前任务
 */
@Composable
private fun DetailBottomToolbar(
    onAddSubtask: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 添加子任务按钮
            TextButton(
                onClick = onAddSubtask,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    Icons.Default.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加子任务", fontWeight = FontWeight.Medium)
            }
            // 分隔圆点
            Text(
                "·",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.BG_SUBTLE),
            )
            // 删除按钮
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error.copy(alpha = NionAlpha.TEXT_MEDIUM),
                ),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("删除", fontWeight = FontWeight.Medium)
            }
        }
    }
}
