package com.echonion.nion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 任务完成爆发动画 —— 光芒射线（Rays）风格。
 *
 * 从画布中心向四周发射多条锥形渐变光束（类似太阳光芒），中心叠加一个柔和的
 * 径向渐变光晕作为"光源"。光束长度随进度 easeOut 增长、透明度随之淡出。
 *
 * 设计为**纯绘制组件**：本身不持有动画状态，由外部通过 [progress] 驱动。
 * 触发时机、时序协调、动画结束回调都集中在外层（SharedTaskCard 顶层）。
 *
 * 视觉规格：
 * - 普通（每天第一个完成）：8 条光束，400ms
 * - 盛大（"今天"清单清空全部待办）：16 条光束，600ms
 * - 颜色建议传 `MaterialTheme.colorScheme.primary`
 *
 * 美感设计要点：
 * 1. **中心光晕** —— 径向渐变圆（亮→透），1.6 倍速快速扩散并淡出，
 *    在光束最盛时已隐去，给爆发一个明确的"光源"起点
 * 2. **锥形渐变光束** —— 每条用填充三角（底边粗、顶点细）+ linearGradient（内亮→外透），
 *    从"等宽线段"升级为有质感的"光束"
 * 3. **角度/长度微随机** —— 基于 index 的稳定伪随机（角度 ±0.12rad，长度 0.8~1.2），
 *    打破等距等长的车轮辐条感，形态更自然有机
 * 4. **easeOut 缓动** —— 长度用 1-(1-t)² 增长（先快后慢），模拟爆发初速度高、逐渐减速
 *
 * @param progress 爆发进度，0~1。由外层 Animatable 驱动；<=0 时跳过绘制节省开销
 * @param color 光束与光晕颜色，建议 `MaterialTheme.colorScheme.primary` 以跟随主题
 * @param modifier 修饰符，决定爆发画布尺寸（建议 50~60dp，居中叠加在勾选框上方）
 * @param rayCount 光束数量：普通 8、盛大 16
 */
@Composable
fun CompletionBurst(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    /** 光束数量：普通完成 8 条，"今天"清单清空全部待办时用 16 条营造盛大感 */
    rayCount: Int = 8,
) {
    Canvas(modifier = modifier) {
        // progress <= 0 时不绘制（动画未启动或本任务不需要爆发），避免无意义的画布刷新
        if (progress <= 0f) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        val p = progress
        val maxRadius = size.minDimension / 2f

        // ── 1. 中心光晕（径向渐变圆）──
        // 1.6 倍速：在 progress≈0.63 时已完成扩散+淡出，让光源在光束最盛时已隐去，
        // 避免光晕与光束同时最强导致画面过曝
        val flashT = (p * 1.6f).coerceAtMost(1f)
        // 光晕半径从 15% 扩到 50% 画布半径，形成一个柔和扩散的"光球"
        val flashRadius = maxRadius * (0.15f + 0.35f * flashT)
        // 光晕透明度随扩散线性淡出，峰值约 0.55，保持柔和不刺眼
        val flashAlpha = (1f - flashT) * 0.55f
        if (flashAlpha > 0f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = flashAlpha), color.copy(alpha = 0f)),
                    center = center,
                    radius = flashRadius,
                ),
                center = center,
                radius = flashRadius,
            )
        }

        // ── 2. 锥形渐变光束 ──
        // innerR：光束起点离中心的偏移，留出光芒中心给上面的光晕填充
        val innerR = 3.dp.toPx()
        val maxLen = maxRadius - innerR - 2.dp.toPx()
        // easeOutQuad：长度先快后慢增长（1-(1-t)²），模拟爆发初速度高、逐渐减速的物理感
        val eased = 1f - (1f - p) * (1f - p)
        val baseLen = maxLen * eased
        // 整体透明度随进度线性淡出
        val alpha = (1f - p).coerceIn(0f, 1f)
        // halfBase：光束底边半宽（粗端），决定光束的"粗细"
        val halfBase = 1.8.dp.toPx()

        for (i in 0 until rayCount) {
            val baseAngle = (i.toFloat() / rayCount) * 2f * Math.PI.toFloat()
            // 基于 index 的稳定伪随机（不依赖 Random，避免每帧抖动）：
            // - angleJitter：角度 ±0.12 弧度（约 ±7°），打破完全等距
            // - lenJitter：长度 0.8~1.2 倍，让光束长短不一
            val angleJitter = (((i * 37) % 100) / 100f - 0.5f) * 0.24f
            val lenJitter = 0.8f + (((i * 73) % 100) / 100f) * 0.4f
            val angle = baseAngle + angleJitter
            val len = baseLen * lenJitter

            val dirX = cos(angle)
            val dirY = sin(angle)
            // 垂直方向（光束底边的法向），用于计算底边两端点
            val perpX = -dirY
            val perpY = dirX

            val startX = center.x + dirX * innerR
            val startY = center.y + dirY * innerR
            val endX = center.x + dirX * (innerR + len)
            val endY = center.y + dirY * (innerR + len)
            val start = Offset(startX, startY)
            val end = Offset(endX, endY)

            // 锥形 path：底边（粗，2×halfBase 宽）垂直于射线方向在 start 处，
            // 顶点（细，收敛到一点）在 end 处，形成"内粗外细"的光束形状
            val beamPath = Path().apply {
                moveTo(startX + perpX * halfBase, startY + perpY * halfBase)
                lineTo(startX - perpX * halfBase, startY - perpY * halfBase)
                lineTo(endX, endY)
                close()
            }
            // linearGradient：沿光束方向从 start（亮）→ 中点（半亮）→ end（透明），
            // 让光束有"射出后消散"的渐变质感，而非生硬的等宽填充
            drawPath(
                path = beamPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        color.copy(alpha = alpha),
                        color.copy(alpha = alpha * 0.55f),
                        color.copy(alpha = 0f),
                    ),
                    start = start,
                    end = end,
                ),
            )
        }
    }
}
