package com.echonion.nion.ui.task

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.reminder.NotificationHelper
import com.echonion.nion.reminder.ReminderEvent
import com.echonion.nion.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局提醒弹窗 —— 监听 NionApp 的提醒事件，在任意页面弹出 BottomSheet。
 *
 * 放置在 NionApp.kt 导航层之外，确保无论用户在哪个页面都能收到提醒。
 * 弹窗提供三个操作：
 * - "完成"：标记任务完成 + 取消通知
 * - "稍后提醒"：选择 5/10/30 分钟后再次提醒
 * - "关闭"：仅关闭弹窗 + 取消通知
 *
 * @param app Application 实例，用于获取事件总线和 NionCore
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderOverlay(app: Application) {
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
            }
        }
    }

    // 当有事件时弹出 BottomSheet
    currentEvent?.let { event ->
        ModalBottomSheet(
            onDismissRequest = {
                // 用户点击外部关闭：取消通知，清除事件
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
                // 标题栏：闹钟图标 + "任务提醒" 文字 + 关闭按钮
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
                    Text(
                        "任务提醒",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    // 关闭按钮：取消通知并关闭弹窗
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

                // 任务标题显示
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "「${event.taskTitle}」的提醒时间到了",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                // 稍后提醒选项区域（可展开/收起）
                AnimatedVisibility(
                    visible = showSnoozeOptions,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            // 5 分钟后提醒
                            SnoozeOptionChip("5分钟", onClick = {
                                snoozeReminder(app, event.taskId, 5)
                                currentEvent = null
                                showSnoozeOptions = false
                            })
                            // 10 分钟后提醒
                            SnoozeOptionChip("10分钟", onClick = {
                                snoozeReminder(app, event.taskId, 10)
                                currentEvent = null
                                showSnoozeOptions = false
                            })
                            // 30 分钟后提醒
                            SnoozeOptionChip("30分钟", onClick = {
                                snoozeReminder(app, event.taskId, 30)
                                currentEvent = null
                                showSnoozeOptions = false
                            })
                        }
                    }
                }

                // 操作按钮行：完成 + 稍后提醒
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "完成"按钮：标记任务完成
                    androidx.compose.material3.Button(
                        onClick = {
                            completeTask(app, event.taskId)
                            NotificationHelper.dismissNotification(app, event.taskId)
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
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("完成", fontWeight = FontWeight.SemiBold)
                    }

                    // "稍后提醒"按钮：展开延迟选项
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
 * 稍后提醒选项按钮。
 *
 * @param label 显示文字（如"5分钟"）
 * @param onClick 点击回调
 */
@Composable
private fun SnoozeOptionChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
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
                app.core().updateTask(taskId, null, null, null, "done", null, null, null, null, null, null)
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
