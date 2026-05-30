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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
 * @param attachments 当前任务的附件列表
 * @param onPickImage 点击添加图片附件时触发
 * @param onPickFile 点击添加文件附件时触发
 * @param onRemoveAttachment 删除附件时触发，传入附件 ID
 * @param onPreviewImage 预览图片附件时触发，传入附件 UI 模型
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
) {
    // 备注内容：初始值为任务描述，可随时编辑，每次变更自动保存
    var notes by remember(task.id) { mutableStateOf(task.description ?: "") }
    // 当前面板状态：DETAIL=详情 / ADD_SUBTASK=添加子任务
    var panel by remember { mutableStateOf(DetailPanel.DETAIL) }
    // 子任务创建表单的标题和优先级
    var subtaskTitle by remember { mutableStateOf("") }
    var subtaskPriority by remember { mutableStateOf("medium") }
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
            // 内部 AnimatedContent：在三个面板之间切换，与添加子任务使用相同的 morph 动画
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
                        // ===== 添加子任务表单 =====
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题栏：图标 + "新建子任务" 文字
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.AddCircleOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
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
                                IconButton(onClick = { panel = DetailPanel.DETAIL }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }

                            // 提示：正在为哪个任务添加子任务
                            Text(
                                "添加到：${task.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // 子任务标题输入框
                            OutlinedTextField(
                                value = subtaskTitle,
                                onValueChange = { subtaskTitle = it },
                                label = { Text("标题") },
                                placeholder = { Text("输入子任务标题...") },
                                modifier = Modifier.fillMaxWidth(),
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
                                        panel = DetailPanel.DETAIL
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
                                            panel = DetailPanel.DETAIL
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

                    DetailPanel.RECURRENCE -> {
                        // ===== 循环设置页面 =====
                        // 使用 RecurrenceSelector，与新建任务表单中的样式一致
                        var localRule by remember(task.id) { mutableStateOf(task.recurrenceRule) }
                        var localTime by remember(task.id) { mutableStateOf(task.recurrenceReminderTime) }
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题栏：图标 + "重复设置" + 关闭按钮
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Repeat,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
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
                                IconButton(onClick = { panel = DetailPanel.DETAIL }) {
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

                            // 操作按钮：取消 + 保存 + 移除循环
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
                                            panel = DetailPanel.DETAIL
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
                                    onClick = { panel = DetailPanel.DETAIL },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                                Button(
                                    onClick = {
                                        onUpdateRecurrence(localRule, localTime)
                                        panel = DetailPanel.DETAIL
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                ) { Text("保存", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            }
                        }
                    }

                    DetailPanel.REMINDER -> {
                        // ===== 提醒设置页面 =====
                        // 使用 ReminderTimePicker（startExpanded=true：直接显示日历，隐藏标题行）
                        // ReminderTimePicker 自带 清除/取消/确定 按钮
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题栏：图标 + "提醒设置" + 关闭按钮（返回 DETAIL 面板）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Alarm,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
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
                                IconButton(onClick = { panel = DetailPanel.DETAIL }) {
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
                                onDismiss = { panel = DetailPanel.DETAIL },
                            )
                        }
                    }

                    DetailPanel.DETAIL -> {
                        // ===== 任务详情 =====
                        // 用 Column + weight 替代 AdaptiveHeightLayout (SubcomposeLayout)，
                        // 避免多次 subcompose 导致的"先显示居中标题，后续才出现内容"闪烁。
                        Column(
                            modifier = Modifier
                                .heightIn(max = maxCardHeight)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // 顶部：任务标题 + 日历图标 + 关闭按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    task.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }

                        // 循环 + 提醒设置行：左右并排，各自可点击进入对应面板
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
                                        onClick = { panel = DetailPanel.RECURRENCE },
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (task.recurrenceRule == "daily") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (task.recurrenceRule == "daily") {
                                        if (task.recurrenceReminderTime != null) "每天 ${task.recurrenceReminderTime}"
                                        else "每天提醒"
                                    } else "设置重复",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (task.recurrenceRule == "daily") FontWeight.Medium else FontWeight.Normal,
                                    color = if (task.recurrenceRule == "daily") MaterialTheme.colorScheme.primary
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
                                        onClick = { panel = DetailPanel.REMINDER },
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Alarm,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (task.reminder != null) MaterialTheme.colorScheme.primary
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
                                    color = if (task.reminder != null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                                )
                            }
                        }

                            // 可编辑的备注输入框 —— weight(1f, fill=false) 占据 header/footer 之外的剩余空间
                            // 内容少时收缩（最低 120dp），内容多时自动扩展，超出后内部滚动
                            // 右下角内嵌附件按钮（回形针图标），clip 防止附件按钮展开时溢出框外
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .heightIn(min = 120.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                            ) {
                                OutlinedTextField(
                                    value = notes,
                                    onValueChange = {
                                        notes = it
                                        onUpdateNotes(it)
                                    },
                                    placeholder = { Text("添加备注...") },
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
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

                            // 底部固定区域：专注信息 + 工具栏，始终显示
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                /**
                                 * 专注信息行 —— 两步交互：
                                 * 状态 A（默认）：[⏱ 专注时间 2h 30m ▶]，点击 ▶ 进入状态 B
                                 * 状态 B（展开）：[▶ 25分钟▲▼]，播放按钮从右侧平滑滑动到左侧，右侧显示可切换的预选时长
                                 *   - 点击 "25分钟 ▲▼" 循环切换 25→45→60→25
                                 *   - 点击 ▶ 启动计时并跳转专注页面
                                 *
                                 * 使用局部 SharedTransitionLayout + sharedBounds 让播放按钮在过渡期间保持可见，
                                 * 从右侧平滑平移到左侧，而非淡入淡出。
                                 */
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
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column {
                                                            Text(
                                                                "专注时间",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                            Text(
                                                                formatFocusTime(task.focusSeconds),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.Medium,
                                                            )
                                                        }
                                                    }
                                                }

                                                /**
                                                 * 状态 B 左侧播放按钮：放在 Spacer 之前，确保在最左端
                                                 * 与状态 A 右侧的播放按钮共享 sharedBounds key，
                                                 * 过渡期间从右侧平滑滑动到最左侧
                                                 */
                                                if (expanded) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        modifier = Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState(
                                                                "focusPlayBtn_${task.id}"
                                                            ),
                                                            animatedVisibilityScope = animatedScope,
                                                            boundsTransform = { _, _ ->
                                                                spring(
                                                                    dampingRatio = 0.75f,
                                                                    stiffness = Spring.StiffnessMedium,
                                                                )
                                                            },
                                                        ),
                                                        onClick = {
                                                            onStartFocus(selectedDuration)
                                                        },
                                                    ) {
                                                        Icon(
                                                            Icons.Default.PlayArrow,
                                                            contentDescription = "开始专注",
                                                            modifier = Modifier
                                                                .padding(8.dp)
                                                                .size(20.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.weight(1f))

                                                /**
                                                 * 状态 A 右侧播放按钮：点击后进入状态 B
                                                 * 与状态 B 左侧的播放按钮共享 sharedBounds key，
                                                 * 过渡期间从当前位置滑动到左侧
                                                 */
                                                if (!expanded) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        modifier = Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState(
                                                                "focusPlayBtn_${task.id}"
                                                            ),
                                                            animatedVisibilityScope = animatedScope,
                                                            boundsTransform = { _, _ ->
                                                                spring(
                                                                    dampingRatio = 0.75f,
                                                                    stiffness = Spring.StiffnessMedium,
                                                                )
                                                            },
                                                        ),
                                                        onClick = { focusExpanded = true },
                                                    ) {
                                                        Icon(
                                                            Icons.Default.PlayArrow,
                                                            contentDescription = "开始专注",
                                                            modifier = Modifier
                                                                .padding(8.dp)
                                                                .size(20.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                }

                                                if (expanded) {
                                                    // 状态 B 右侧：预选专注时长，点击循环切换 25→45→60→25
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.clickable {
                                                            selectedDuration = when (selectedDuration) {
                                                                25 -> 45
                                                                45 -> 60
                                                                else -> 25
                                                            }
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

                                // 附件区域：附件列表
                                if (attachments.isNotEmpty()) {
                                    AttachmentList(
                                        attachments = attachments,
                                        onRemove = onRemoveAttachment,
                                        onPreview = onPreviewImage,
                                    )
                                }

                                // 工具栏：添加子任务 + 删除
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
                                        // 添加子任务按钮 —— 点击后卡片内容 morph 为子任务创建表单
                                        TextButton(
                                            onClick = { panel = DetailPanel.ADD_SUBTASK },
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
                        }
                    }
                }
            }
        }
    }
}
