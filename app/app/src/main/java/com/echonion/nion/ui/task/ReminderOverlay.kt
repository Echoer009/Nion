package com.echonion.nion.ui.task

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.reminder.NotificationHelper
import com.echonion.nion.reminder.ReminderEvent
import com.echonion.nion.reminder.ReminderMessageGenerator
import com.echonion.nion.reminder.ReminderScheduler
import com.echonion.nion.reminder.ReminderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局提醒悬浮卡片 —— 监听 NionApp 的提醒事件，在任意页面显示底部悬浮通知卡片。
 *
 * 与传统 ModalBottomSheet 不同，悬浮卡片不阻断用户操作：
 * - 无遮罩层，用户可以继续操作底下的界面
 * - 支持左右滑动关闭
 * - 不自动消失，仅支持手动关闭
 * - 紧迫感通过卡片背景色渐变体现（triggerCount 越高颜色越醒目）
 *
 * 放置在 NionApp.kt 导航层之外，确保无论用户在哪个页面都能收到提醒。
 *
 * @param app Application 实例，用于获取事件总线和 NionCore
 * @param onStartFocus 用户点击「开始做了」时触发，回调 (taskId, taskTitle) 到导航层
 */
@Composable
fun ReminderOverlay(
    app: Application,
    onStartFocus: ((String, String) -> Unit)? = null,
) {
    // 当前待显示的提醒事件数据，null 表示没有待处理事件
    var currentEvent by remember { mutableStateOf<ReminderEvent?>(null) }

    // 控制 AnimatedVisibility 的可见状态，与 currentEvent 分离以支持退出动画
    // 关闭时先设为 false 触发退出动画，动画完成后再清空 currentEvent
    var cardVisible by remember { mutableStateOf(false) }

    // 监听提醒事件流
    val nionApp = app as NionApp
    LaunchedEffect(Unit) {
        nionApp.reminderEvents.collect { event ->
            // 只在当前没有弹窗时才显示新事件，避免覆盖
            if (currentEvent == null) {
                currentEvent = event
                // 设为可见，触发进入动画
                cardVisible = true
                // 只有 app 在前台时才取消通知栏提醒，避免双重打扰。
                // 防御性检查：即使 Worker 已经做了前台判断，这里再兜底一次
                if (nionApp.isInForeground) {
                    NotificationHelper.dismissNotification(app, event.taskId)
                }
            }
        }
    }

    // 退出动画时长（ms），与 AnimatedVisibility 的 exit tween(250) 对齐
    // 用于在退出动画播放完毕后清空事件数据
    val exitAnimationDurationMs = 260L

    /**
     * 关闭悬浮卡片的统一方法。
     * 先将 cardVisible 设为 false 触发退出动画（向底部滑出 + 淡出），
     * 等待退出动画播放完毕后再清空 currentEvent，让 Compose 树回收。
     */
    fun dismissWithAnimation() {
        cardVisible = false
        // 在协程中等待退出动画完成，然后清空事件数据
        kotlinx.coroutines.MainScope().launch {
            delay(exitAnimationDurationMs)
            currentEvent = null
        }
    }

    // 悬浮卡片容器：覆盖整个屏幕，卡片定位在底部中央
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        currentEvent?.let { event ->
            // 根据紧迫度获取按钮文案
            val labels = ReminderMessageGenerator.getActionLabels(event.triggerCount)
            // 显示文案：优先用 LLM 生成的 message，fallback 到模板
            val displayMessage = event.message.ifBlank {
                ReminderMessageGenerator.generateFromTemplate(event.taskTitle, event.triggerCount)
            }

            // ── 紧迫感颜色渐变：强调色从主题色 primary 线性过渡到 error 红色 ──
            // triggerCount 1 = 纯 primary（焦橙），5 = 纯 error（红色），中间线性插值
            // 卡片背景和文字固定不变，只有强调色（图标、按钮、高亮）随紧迫度渐变
            val progress = ((event.triggerCount - 1).toFloat() / 4f).coerceIn(0f, 1f)
            val cardColor = MaterialTheme.colorScheme.surface
            val onCardColor = MaterialTheme.colorScheme.onSurface
            val accentColor = lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error, progress)

            // 水平滑动偏移量，用于实现滑动关闭手势
            var offsetX by remember { mutableFloatStateOf(0f) }

            // 使用独立的 cardVisible 控制 AnimatedVisibility，
            // 而不是依赖 currentEvent != null，确保退出动画有足够时间播放
            AnimatedVisibility(
                visible = cardVisible,
                // 从底部滑入，带弹簧回弹效果，先快后慢
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeIn(animationSpec = tween(200)),
                // 向底部滑出收回，与进入动画形成对称的收回效果
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                ) + fadeOut(animationSpec = tween(150)),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        // 距离底部导航栏 72dp，避免遮挡
                        .padding(bottom = 72.dp)
                        // 水平滑动偏移：跟随手指实时移动
                        .offset(x = with(LocalDensity.current) { offsetX.toDp() })
                        // 水平滑动关闭手势：滑动超过卡片宽度 40% 时自动关闭
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(offsetX) > size.width * 0.4f) {
                                        dismissEvent(app, event) { dismissWithAnimation() }
                                    } else {
                                        // 未达到阈值，弹回原位
                                        offsetX = 0f
                                    }
                                },
                            ) { _, dragAmount ->
                                offsetX += dragAmount
                            }
                        }
                        // 卡片阴影：营造悬浮感
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(20.dp),
                            ambientColor = cardColor.copy(alpha = 0.3f),
                            spotColor = cardColor.copy(alpha = 0.2f),
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = cardColor,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // ── 标题栏：图标 + 任务名 + 提醒次数 + 关闭按钮 ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 提醒图标，半透明圆形背景
                            Surface(
                                shape = CircleShape,
                                color = accentColor.copy(alpha = 0.15f),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Alarm,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // 任务标题（加粗）+ 提醒次数标签
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    event.taskTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = onCardColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 第 2 次起显示提醒次数
                                if (event.triggerCount > 1) {
                                    Text(
                                        "第 ${event.triggerCount} 次提醒",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = onCardColor.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            // ── 右上角操作按钮组：专注图标 + 关闭按钮 ──
                            // 专注图标按钮：点击跳转专注页（不自动开始），用于需要专注的任务
                            Surface(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            // 终止提醒循环
                                            ReminderStore.resetTriggerCount(app, event.taskId)
                                            ReminderScheduler.cancelReminder(app, event.taskId)
                                            NotificationHelper.dismissNotification(app, event.taskId)
                                            // 跳转专注页，不自动开始
                                            onStartFocus?.invoke(event.taskId, event.taskTitle)
                                            dismissWithAnimation()
                                        },
                                    ),
                                // 使用强调色半透明背景，暗示"可操作"
                                color = accentColor.copy(alpha = 0.15f),
                                shape = CircleShape,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = "开始专注",
                                        modifier = Modifier.size(14.dp),
                                        tint = accentColor,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            // 关闭按钮：圆形，半透明背景，点击触发收回动画
                            Surface(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            dismissEvent(app, event) { dismissWithAnimation() }
                                        },
                                    ),
                                color = onCardColor.copy(alpha = 0.15f),
                                shape = CircleShape,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "关闭",
                                        modifier = Modifier.size(14.dp),
                                        tint = onCardColor.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }

                        // ── Nion 的个性化提醒文案 ──
                        Text(
                            displayMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onCardColor.copy(alpha = 0.85f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )

                        // ── 稍后提醒选项：药丸 Chip 样式，始终可见 ──
                        // 三个等宽圆角按钮，点击即触发对应延迟的 snooze
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("5 分钟" to 5, "10 分钟" to 10, "30 分钟" to 30).forEach { (label, minutes) ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    // 半透明底色，与卡片背景区分
                                    color = onCardColor.copy(alpha = 0.12f),
                                    onClick = {
                                        snoozeReminder(app, event.taskId, minutes)
                                        dismissWithAnimation()
                                    },
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = onCardColor,
                                        )
                                    }
                                }
                            }
                        }

                        // ── 操作按钮行：「知道了」主按钮 ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 「知道了」主按钮：实心填充，使用强调色，点击终止循环并关闭
                            val ackLabel = labels.first.ifBlank { "知道了" }
                            Button(
                                onClick = {
                                    // 终止提醒循环
                                    ReminderStore.resetTriggerCount(app, event.taskId)
                                    ReminderScheduler.cancelReminder(app, event.taskId)
                                    NotificationHelper.dismissNotification(app, event.taskId)
                                    dismissWithAnimation()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(ackLabel, fontWeight = FontWeight.SemiBold)
                            }

                            // 「今天算了」次按钮（仅 triggerCount < 5 时显示，第 5 次只有一个「知道了」主按钮）
                            if (labels.third.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        dismissEvent(app, event) { dismissWithAnimation() }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        labels.third,
                                        fontWeight = FontWeight.Medium,
                                        color = onCardColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 关闭事件：取消通知栏通知 + 清除 UI 状态。
 */
private fun dismissEvent(
    app: Application,
    event: ReminderEvent,
    onClear: () -> Unit,
) {
    NotificationHelper.dismissNotification(app, event.taskId)
    onClear()
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
