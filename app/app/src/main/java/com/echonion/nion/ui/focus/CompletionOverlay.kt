package com.echonion.nion.ui.focus

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
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.ui.companion.MarkdownText
import com.echonion.nion.ui.companion.PromptDefaults
import com.echonion.nion.ui.theme.NionAlpha
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 专注完成悬浮卡片 —— 监听 NionApp 的专注完成事件，在任意页面显示底部鼓励卡片。
 *
 * 设计与 GreetingOverlay 保持一致：
 * - 底部悬浮卡片，弹簧动画入场，滑动/按钮关闭
 * - 标题显示"专注"，图标使用 Timer
 * - 收到事件后异步调用 CompletionMotivator 生成文案（LLM 或模板兜底）
 * - 4 秒自动消失
 *
 * 放置在 NionApp.kt 导航层之外，确保无论用户在哪个页面都能收到通知。
 *
 * @param app Application 实例，用于获取事件总线和 NionCore
 */
@Composable
fun CompletionOverlay(
    app: Application,
) {
    // 当前待显示的专注完成事件，null 表示没有待处理事件
    var currentEvent by remember { mutableStateOf<CompletionEvent?>(null) }

    // 鼓励文案，LLM 生成完成后更新，null 表示正在加载
    var message by remember { mutableStateOf<String?>(null) }

    // 控制 AnimatedVisibility 的可见状态，与 currentEvent 分离以支持退出动画
    var cardVisible by remember { mutableStateOf(false) }

    val nionApp = app as NionApp
    val core = app.core()

    // 监听专注完成事件流
    LaunchedEffect(Unit) {
        nionApp.completionEvents.collect { event ->
            // 只在当前没有弹窗时才显示新事件，避免覆盖
            if (currentEvent == null) {
                currentEvent = event
                // 先显示加载状态
                message = null
                // 触发入场动画
                cardVisible = true

                // 异步调用 LLM 生成鼓励文案，文案加载完成后才开始 10 秒自动消失倒计时
                // 这样无论 LLM 响应快慢，用户都有足够时间阅读完整文案
                launch {
                    val result = CompletionMotivator.generate(core, event)
                    message = result
                    // 文案就绪后等待 10 秒再触发退出动画
                    delay(10000L)
                    cardVisible = false
                    delay(260L)
                    currentEvent = null
                    message = null
                }
            }
        }
    }

    // 退出动画时长（ms），与 AnimatedVisibility 的 exit tween(250) 对齐
    val exitAnimationDurationMs = 260L

    /**
     * 关闭悬浮卡片的统一方法。
     * 先将 cardVisible 设为 false 触发退出动画（向底部滑出 + 淡出），
     * 等待退出动画播放完毕后再清空 currentEvent 和 message，让 Compose 树回收。
     */
    fun dismissWithAnimation() {
        cardVisible = false
        MainScope().launch {
            delay(exitAnimationDurationMs)
            currentEvent = null
            message = null
        }
    }

    // 读取伙伴名称用于标题显示
    val companionName = remember {
        try { core.getSetting("companion_name") ?: PromptDefaults.DEFAULT_COMPANION_NAME }
        catch (_: Exception) { PromptDefaults.DEFAULT_COMPANION_NAME }
    }

    // 悬浮卡片容器：覆盖整个屏幕，卡片定位在底部中央
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        currentEvent?.let { event ->
            // 专注卡片强调色使用 primary
            val accentColor = MaterialTheme.colorScheme.primary
            val cardColor = MaterialTheme.colorScheme.surface
            val onCardColor = MaterialTheme.colorScheme.onSurface

            // 水平滑动偏移量，用于实现滑动关闭手势
            var offsetX by remember { mutableFloatStateOf(0f) }

            // 使用独立的 cardVisible 控制 AnimatedVisibility
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
                        // ── 标题栏：图标 + 标题（伙伴名字）+ 关闭按钮 ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 专注图标，半透明圆形背景
                            Surface(
                                shape = CircleShape,
                                color = accentColor.copy(alpha = NionAlpha.BG_DECORATION),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            // 标题显示伙伴名字
                            Text(
                                companionName,
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

                        // ── 鼓励文案（LLM 加置中显示占位文本）──
                        val displayText = message ?: "..."
                        val stickers = remember { app.core.getStickers() }
                        MarkdownText(
                            content = displayText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = onCardColor.copy(alpha = NionAlpha.TEXT_HIGH),
                            ),
                            stickers = stickers,
                        )

                        // ── "谢谢" 按钮：实心填充，使用强调色 ──
                        Button(
                            onClick = { dismissWithAnimation() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("谢谢", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
