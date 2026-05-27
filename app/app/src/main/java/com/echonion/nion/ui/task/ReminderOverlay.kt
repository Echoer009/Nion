package com.echonion.nion.ui.task

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.reminder.NotificationHelper
import com.echonion.nion.reminder.ReminderEvent
import com.echonion.nion.reminder.ReminderMessageGenerator
import com.echonion.nion.reminder.ReminderScheduler
import com.echonion.nion.reminder.ReminderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局提醒弹窗 —— 监听 NionApp 的提醒事件，在任意页面弹出 BottomSheet。
 *
 * 放置在 NionApp.kt 导航层之外，确保无论用户在哪个页面都能收到提醒。
 * 弹窗显示 Nion 的个性化提醒文案，提供三个操作：
 * - "开始做了"：跳转专注页面 + 取消提醒循环
 * - "稍后提醒"：选择 5/10/30 分钟后再次提醒
 * - "关闭"：取消通知 + 关闭弹窗（不终止循环，5 分钟后还会再来）
 *
 * @param app Application 实例，用于获取事件总线和 NionCore
 * @param onStartFocus 用户点击「开始做了」时触发，回调 (taskId, taskTitle) 到导航层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderOverlay(
    app: Application,
    onStartFocus: ((String, String) -> Unit)? = null,
) {
    // 当前待显示的提醒事件，null 表示不显示弹窗
    var currentEvent by remember { mutableStateOf<ReminderEvent?>(null) }
    // 稍后提醒选项是否展开
    var showSnoozeOptions by remember { mutableStateOf(false) }

    // 监听提醒事件流
    val nionApp = app as NionApp
    LaunchedEffect(Unit) {
        nionApp.reminderEvents.collect { event ->
            // 只在当前没有弹窗时才显示新事件，避免覆盖
            if (currentEvent == null) {
                currentEvent = event
                // 只有 app 在前台时才取消通知栏提醒，避免双重打扰。
                // 防御性检查：即使 Worker 已经做了前台判断，这里再兜底一次，
                // 防止极端情况下（如 Worker 发事件瞬间 app 退到后台）通知被误撤。
                if (nionApp.isInForeground) {
                    NotificationHelper.dismissNotification(app, event.taskId)
                }
            }
        }
    }

    // 当有事件时弹出 BottomSheet
    currentEvent?.let { event ->
        // 根据紧迫度获取按钮文案
        val labels = ReminderMessageGenerator.getActionLabels(event.triggerCount)
        // 显示文案：优先用 LLM 生成的 message，fallback 到模板
        val displayMessage = event.message.ifBlank {
            ReminderMessageGenerator.generateFromTemplate(event.taskTitle, event.triggerCount)
        }

        ModalBottomSheet(
            onDismissRequest = {
                // 用户点击外部关闭：取消通知，但不清除 trigger_count（循环继续）
                NotificationHelper.dismissNotification(app, event.taskId)
                currentEvent = null
                showSnoozeOptions = false
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // 标题栏：Nion 图标 + 提醒次数 + 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Nion",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        // 显示提醒次数（第 2 次起）
                        if (event.triggerCount > 1) {
                            Text(
                                "第 ${event.triggerCount} 次提醒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 关闭按钮：仅关闭弹窗，不终止循环
                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    NotificationHelper.dismissNotification(app, event.taskId)
                                    currentEvent = null
                                    showSnoozeOptions = false
                                },
                            ),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Nion 的个性化提醒文案
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        displayMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // 稍后提醒选项区域（可展开/收起），使用 PeriodTabRow 风格
                AnimatedVisibility(
                    visible = showSnoozeOptions,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically(),
                ) {
                    SnoozeTabRow(
                        options = listOf("5分钟", "10分钟", "30分钟"),
                        onSelect = { index ->
                            // 根据点击位置映射延迟分钟数
                            val minutes = listOf(5, 10, 30)[index]
                            snoozeReminder(app, event.taskId, minutes)
                            currentEvent = null
                            showSnoozeOptions = false
                        },
                    )
                }

                // 操作按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 「开始做了」按钮（主按钮）
                    val startLabel = labels.first.ifBlank { "开始做了" }
                    androidx.compose.material3.Button(
                        onClick = {
                            // 终止提醒循环
                            ReminderStore.resetTriggerCount(app, event.taskId)
                            ReminderScheduler.cancelReminder(app, event.taskId)
                            NotificationHelper.dismissNotification(app, event.taskId)

                            // 如果有跳转专注回调，使用它；否则标记完成
                            if (onStartFocus != null) {
                                onStartFocus(event.taskId, event.taskTitle)
                            } else {
                                completeTask(app, event.taskId)
                            }

                            currentEvent = null
                            showSnoozeOptions = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(startLabel, fontWeight = FontWeight.SemiBold)
                    }

                    // 「稍后提醒」按钮（次按钮）
                    TextButton(
                        onClick = { showSnoozeOptions = !showSnoozeOptions },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("稍后提醒", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 稍后提醒选项 Tab 栏 —— 参照 FocusStatsPanel 的日/周/月 PeriodTabRow 设计。
 *
 * 布局结构：
 * - 等宽文字按钮行（透明背景，无深色容器）
 * - 底部滑动指示条跟随选中项移动
 * - 点击即触发对应延迟的 snooze 操作
 *
 * @param options 选项标签列表（如 "5分钟", "10分钟", "30分钟"）
 * @param onSelect 点击回调，传入选中项的索引
 */
@Composable
private fun SnoozeTabRow(
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current

    // 当前高亮项索引，-1 表示无高亮（初始状态）
    var hoveredIndex by remember { mutableIntStateOf(-1) }

    // Tab 栏总宽度（像素），用于计算每个 Tab 宽度和指示器位置
    var tabRowWidthPx by remember { mutableIntStateOf(0) }

    // 单个 Tab 宽度（dp），所有 Tab 等宽
    val tabWidthDp = with(density) { (tabRowWidthPx / options.size).toDp() }

    // 指示器偏移量动画：根据 hoveredIndex 平滑滑动
    // 使用 FastOutSlowInEasing 缓动，先快后慢
    val indicatorOffsetDp: Dp by animateDpAsState(
        targetValue = tabWidthDp * hoveredIndex.coerceAtLeast(0),
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing,
        ),
        label = "snoozeIndicatorOffset",
    )

    // 指示器宽度，比 Tab 窄，左右各留 12dp 边距
    val indicatorWidthDp = tabWidthDp - 24.dp * 2

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { tabRowWidthPx = it.width },
    ) {
        // 底部滑动指示条，跟随高亮项水平移动
        if (tabRowWidthPx > 0 && hoveredIndex >= 0) {
            Box(
                modifier = Modifier
                    .offset(x = 24.dp + indicatorOffsetDp)
                    .align(Alignment.BottomStart)
                    .padding(bottom = 4.dp)
                    .width(indicatorWidthDp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        // Tab 按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            options.forEachIndexed { index, label ->
                val isHovered = index == hoveredIndex

                // 文字颜色动画：高亮时 primary，默认 onSurfaceVariant
                val textColor by animateColorAsState(
                    targetValue = if (isHovered) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(300),
                    label = "snoozeTabTextColor$index",
                )

                Surface(
                    modifier = Modifier.weight(1f),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    onClick = {
                        // 点击时先高亮指示器，再触发 snooze
                        hoveredIndex = index
                        onSelect(index)
                    },
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isHovered) FontWeight.Bold else FontWeight.Normal,
                        color = textColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 标记任务完成。
 * 在 IO 线程调用 Rust core 更新任务状态为 "done"。
 */
private fun completeTask(app: Application, taskId: String) {
    kotlinx.coroutines.MainScope().launch {
        try {
            withContext(Dispatchers.IO) {
                app.core().updateTask(taskId, null, null, null, "done", null, null, null, null, null)
            }
        } catch (e: Exception) {
            // 静默失败，不影响用户体验
        }
    }
}

/**
 * 稍后提醒：取消当前通知，调度延迟后的新闹钟。
 */
private fun snoozeReminder(app: Application, taskId: String, delayMinutes: Int) {
    NotificationHelper.dismissNotification(app, taskId)
    ReminderScheduler.scheduleSnoozeReminder(app, taskId, delayMinutes)
}
