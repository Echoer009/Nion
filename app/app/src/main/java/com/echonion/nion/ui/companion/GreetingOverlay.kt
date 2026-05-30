package com.echonion.nion.ui.companion

import com.echonion.nion.ui.theme.NionAlpha
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.reminder.GreetingEvent
import com.echonion.nion.reminder.NotificationHelper
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 问候悬浮卡片 —— 监听 NionApp 的问候事件，在任意页面显示底部悬浮通知卡片。
 *
 * 与 ReminderOverlay 设计一致，但更精简：
 * - 无贪睡按钮、无专注计时、无紧迫度渐变
 * - 只有问候文案 + 关闭按钮 + "好的" 按钮
 * - 强调色固定为 primary（温暖友好）
 *
 * 放置在 NionApp.kt 导航层之外，确保无论用户在哪个页面都能收到问候。
 *
 * @param app Application 实例，用于获取事件总线和 NionCore
 */
@Composable
fun GreetingOverlay(
    app: Application,
) {
    // 当前待显示的问候事件数据，null 表示没有待处理事件
    var currentEvent by remember { mutableStateOf<GreetingEvent?>(null) }

    // 控制 AnimatedVisibility 的可见状态，与 currentEvent 分离以支持退出动画
    var cardVisible by remember { mutableStateOf(false) }

    // 监听问候事件流
    val nionApp = app as NionApp
    LaunchedEffect(Unit) {
        nionApp.greetingEvents.collect { event ->
            // 只在当前没有弹窗时才显示新事件，避免覆盖
            if (currentEvent == null) {
                currentEvent = event
                // 设为可见，触发进入动画
                cardVisible = true
                // 前台 Overlay 已接管，取消系统通知避免双重提醒
                NotificationHelper.dismissGreetingNotification(app, event.greetingType)
            }
        }
    }

    // 退出动画时长（ms），与 AnimatedVisibility 的 exit tween(250) 对齐
    val exitAnimationDurationMs = 260L

    /**
     * 关闭悬浮卡片的统一方法。
     * 先将 cardVisible 设为 false 触发退出动画（向底部滑出 + 淡出），
     * 等待退出动画播放完毕后再清空 currentEvent，让 Compose 树回收。
     */
    fun dismissWithAnimation() {
        cardVisible = false
        MainScope().launch {
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
            // 根据问候类型获取显示标题
            val title = GreetingEvent.getTitle(event.greetingType)

            // 问候卡片强调色使用 tertiary（温暖友好的装饰色）
            val accentColor = MaterialTheme.colorScheme.tertiary
            val cardColor = MaterialTheme.colorScheme.surface
            val onCardColor = MaterialTheme.colorScheme.onSurface

            // 水平滑动偏移量，用于实现滑动关闭手势
            var offsetX by remember { mutableFloatStateOf(0f) }

            // 使用独立的 cardVisible 控制 AnimatedVisibility，
            // 确保退出动画有足够时间播放
            AnimatedVisibility(
                visible = cardVisible,
                // 从底部滑入，带弹簧回弹效果
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) + fadeIn(animationSpec = tween(200)),
                // 向底部滑出收回
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
                                        dismissWithAnimation()
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
                            ambientColor = cardColor.copy(alpha = NionAlpha.SHADOW_AMBIENT),
                            spotColor = cardColor.copy(alpha = NionAlpha.SHADOW_SPOT),
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
                        // ── 标题栏：图标 + 问候标题 + 关闭按钮 ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 问候图标，半透明圆形背景
                            Surface(
                                shape = CircleShape,
                                color = accentColor.copy(alpha = NionAlpha.BG_DECORATION),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.WbSunny,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // 问候标题（加粗）
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onCardColor,
                                modifier = Modifier.weight(1f),
                            )
                            // 关闭按钮
                            Surface(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape),
                                color = onCardColor.copy(alpha = NionAlpha.BG_DECORATION),
                                shape = CircleShape,
                                onClick = { dismissWithAnimation() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "关闭",
                                        modifier = Modifier.size(14.dp),
                                        tint = onCardColor.copy(alpha = NionAlpha.TEXT_MEDIUM),
                                    )
                                }
                            }
                        }

                        // ── 问候文案（支持表情包渲染）──
                        val stickers = remember { app.core.getStickers() }
                        MarkdownText(
                            content = event.message,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = onCardColor.copy(alpha = NionAlpha.TEXT_HIGH),
                            ),
                            stickers = stickers,
                        )

                        // ── "好的" 按钮：实心填充，使用强调色 ──
                        Button(
                            onClick = { dismissWithAnimation() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("好的", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
