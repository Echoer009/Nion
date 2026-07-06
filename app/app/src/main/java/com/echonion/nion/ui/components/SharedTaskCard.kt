package com.echonion.nion.ui.components

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.task.formatReminder
import com.echonion.nion.ui.task.formatReminderDate
import com.echonion.nion.ui.task.isReminderOverdue
import com.echonion.nion.ui.task.isReminderToday
import com.echonion.nion.ui.task.priorityColor
import com.echonion.nion.ui.notebook.stripMarkdown
import com.echonion.nion.ui.theme.LocalPriorityColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * 共享任务卡片 UI 数据模型 —— 统一任务列表和日程页面的卡片数据。
 *
 * 两个页面各自的 ViewModel 数据模型（TaskItem / ScheduleTaskItem）
 * 通过 toCardModel() 扩展函数映射到此类型，供 SharedTaskCard 消费。
 */
data class TaskCardModel(
    val id: String,
    val name: String,
    val description: String? = null,
    val priority: String,
    val isDone: Boolean,
    val isDaily: Boolean = false,
    val reminderTime: String? = null,
    /** 一次性提醒时间，格式 "YYYY-MM-DDTHH:MM"，由卡片上的铃铛图标 + 完整日期时间展示 */
    val reminder: String? = null,
)

/**
 * 共享任务卡片 —— 任务列表和日程页面复用的任务行渲染组件。
 *
 * 视觉规范：
 * - 背景色随完成状态动画切换（surfaceContainerLowest / surfaceContainerLow）
 * - 勾选框：未完成时显示 RadioButtonUnchecked 图标（颜色跟随优先级）；
 *   完成动画期间用 PathMeasure 逐笔画出对勾，背景填充 primary
 * - 标题完成时颜色渐变到中灰，并叠加一条从左到右划过的删除线
 * - 每日任务末尾显示循环图标；可选显示描述（单行省略）和截止日期/提醒时间行
 * - 可选显示折叠/展开箭头图标（有子任务的主任务使用）
 * - 完成动画播放完毕后通过 [onCompletionAnimEnd] 回调通知上层，由上层决定何时真正写入完成状态，
 *   从而实现"先在原位播放动画，动画结束后任务再移入已完成区"的视觉顺序
 *
 * 完成动画时序（由 [playCompletionAnim] 一次性触发，总时长约 500~700ms）：
 * - t=0：对勾开始绘制（200ms，线性）
 * - t=40ms：删除线开始划过（200ms）
 * - t=0：标题颜色渐变到灰（250ms，由 animateColorAsState 自动驱动）
 * - t=100ms：光芒射线爆发（仅 [showBurst] 时；普通 400ms / 盛大 [isGrand] 600ms）
 * - 全部结束 → 触发 [onCompletionAnimEnd]
 *
 * @param model 任务卡片数据
 * @param onToggleDone 勾选框点击回调，切换任务完成状态。注意：上层会包装此回调，
 *   完成（todo→done）时延迟到动画结束后才真正调用，取消完成则立即调用
 * @param onClick 卡片整体点击回调，null 表示不可点击（日程页面不需要点击打开详情）
 * @param shape 卡片圆角形状，用于背景裁切；任务列表在有分组时需要特殊 shape
 * @param modifier 在 background() 之前注入的 modifier，用于任务列表添加 group border / selection / shared element
 * @param compact 紧凑模式，true 时缩小内边距（日程页面使用）
 * @param showCollapseArrow 是否显示折叠/展开箭头图标
 * @param arrowRotation 箭头旋转角度（展开=0°朝下，折叠=-90°朝右）
 * @param onToggleCollapse 点击箭头图标时触发，切换折叠状态
 * @param playCompletionAnim 是否正在播放完成动画。true 时驱动对勾绘制/删除线/爆发等子动画。
 *   由上层（TaskScreen）的 animatingDoneIds 集合决定
 * @param visualDone 视觉完成态。null 时使用 model.isDone；非 null 时覆盖（动画期间传 true，
 *   使视觉上呈现已完成，但 model.isDone 仍为 false 直到上层真正写入）
 * @param showBurst 是否在完成动画中显示光芒射线爆发。由上层根据"每天第一个/最后一个"规则判断
 * @param isGrand 是否为盛大爆发（射线数量翻倍 + 持续时间更长），用于"今天"清单清空全部待办时
 * @param onCompletionAnimEnd 完成动画全部播放完毕的回调，上层在此调用真正的 toggleDone
 */
@Composable
fun SharedTaskCard(
    model: TaskCardModel,
    onToggleDone: () -> Unit,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showCollapseArrow: Boolean = false,
    arrowRotation: Float = 0f,
    onToggleCollapse: (() -> Unit)? = null,
    /** 笔记模式：true 时复选框替换为笔记图标 */
    isNotebook: Boolean = false,
    /** 是否正在播放完成动画（由上层 animatingDoneIds 决定） */
    playCompletionAnim: Boolean = false,
    /** 视觉完成态覆盖：动画期间传 true 让 UI 呈现已完成，但 model.isDone 仍为 false */
    visualDone: Boolean? = null,
    /** 是否在完成动画中显示光芒射线爆发（第一个/最后一个） */
    showBurst: Boolean = false,
    /** 盛大爆发：射线翻倍 + 时长更长，用于"今天"清单清空全部待办 */
    isGrand: Boolean = false,
    /** 完成动画全部播放完毕的回调，上层在此调用真正的 toggleDone */
    onCompletionAnimEnd: () -> Unit = {},
) {
    // effectiveDone：实际用于驱动视觉的完成态。visualDone 非 null 时覆盖 model.isDone，
    // 使得动画期间（model.isDone 还是 false）UI 已经呈现已完成外观
    val effectiveDone = visualDone ?: model.isDone

    // 背景色动画：已完成 → surfaceContainerLow，未完成 → surfaceContainerLowest。
    // 用 effectiveDone 替代原来的 model.isDone，让动画期间背景也跟着过渡
    val cardColor by animateColorAsState(
        targetValue = if (effectiveDone)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(300),
        label = "cardColor",
    )

    // 标题颜色动画：完成时渐变到中灰（onSurface × TEXT_MEDIUM），未完成时正常 onSurface。
    // 用 effectiveDone 驱动，使动画期间颜色平滑过渡
    val titleColor by animateColorAsState(
        targetValue = if (effectiveDone)
            MaterialTheme.colorScheme.onSurface.copy(alpha = NionAlpha.TEXT_MEDIUM)
        else
            MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(250),
        label = "titleColor",
    )

    // 勾选框背景色动画：完成时 primary，未完成时透明
    val checkBgColor by animateColorAsState(
        targetValue = if (effectiveDone) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "checkBg",
    )

    // ============ 完成动画的三个子进度（仅 playCompletionAnim 期间被驱动） ============
    // checkProgress：对勾笔画绘制进度 0→1
    val checkProgress = remember { Animatable(0f) }
    // strikeProgress：删除线划过进度 0→1
    val strikeProgress = remember { Animatable(0f) }
    // burstProgress：光芒射线爆发进度 0→1
    val burstProgress = remember { Animatable(0f) }

    // 完成动画时序协调：playCompletionAnim 从 false→true 时启动整个动画序列。
    // 各子动画并行启动（不同起始延迟），全部完成后调用 onCompletionAnimEnd。
    LaunchedEffect(playCompletionAnim) {
        if (!playCompletionAnim) return@LaunchedEffect
        // 重置进度，防止重复触发时残留旧值
        checkProgress.snapTo(0f)
        strikeProgress.snapTo(0f)
        burstProgress.snapTo(0f)
        // coroutineScope + launch 并行各子动画，joinAll 等待最慢的（爆发）完成
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            // 对勾绘制：立即开始，200ms 线性（笔画匀速写出）
            jobs += launch {
                checkProgress.animateTo(1f, tween(200, easing = LinearEasing))
            }
            // 删除线：延迟 40ms 让对勾先起笔，200ms 划过
            jobs += launch {
                delay(40)
                strikeProgress.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            }
            // 光芒射线爆发：延迟 100ms（对勾已大致成型），仅 showBurst 时启动。
            // isGrand 盛大模式持续 600ms，普通 400ms
            if (showBurst) {
                jobs += launch {
                    delay(100)
                    burstProgress.animateTo(
                        1f,
                        tween(if (isGrand) 600 else 400, easing = FastOutSlowInEasing),
                    )
                }
            }
            jobs.joinAll()
        }
        // 全部子动画结束，通知上层：可以真正写入完成状态了（任务此时才会移入已完成区）
        onCompletionAnimEnd()
    }

    // 优先级颜色，通过 LocalPriorityColors 获取跟随主题变化的颜色
    val priorityColors = LocalPriorityColors.current
    val priorityColor = model.priority.priorityColor(priorityColors)

    // 紧凑模式下缩小内边距
    val horizontalPadding = if (compact) 14.dp else 16.dp
    val verticalPadding = if (compact) 10.dp else 14.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .background(cardColor, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 计算各子动画的当前显示值：
        // - 动画期间（playCompletionAnim）用各 Animatable 的实时值
        // - 非动画时：已完成态显示完整对勾/删除线（progress=1），未完成态不显示（progress=0）
        // 在 if-else 外声明，使 TaskTextContent（在 else 块外）也能访问 strikeDraw
        val checkDraw = if (playCompletionAnim) checkProgress.value else if (effectiveDone) 1f else 0f
        val strikeDraw = if (playCompletionAnim) strikeProgress.value else if (effectiveDone) 1f else 0f
        // 爆发只在动画期间显示（playCompletionAnim=false 时即使 burstProgress 残留也不绘制）
        val burstDraw = if (playCompletionAnim) burstProgress.value else 0f

        // 勾选框：笔记模式显示笔记图标，任务模式显示带动画的 checkbox + 可选爆发
        if (isNotebook) {
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                modifier = Modifier.size(24.dp),
            )
        } else {
            TaskCheckbox(
                effectiveDone = effectiveDone,
                checkDrawProgress = checkDraw,
                checkBgColor = checkBgColor,
                priorityColor = priorityColor,
                onToggleDone = onToggleDone,
                burstProgress = burstDraw,
                burstColor = MaterialTheme.colorScheme.primary,
                isGrand = isGrand,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        // 文字信息列：标题 + 描述 + 提醒时间。标题颜色和删除线由外层算好传入
        TaskTextContent(
            model = model,
            isNotebook = isNotebook,
            titleColor = titleColor,
            strikeDraw = strikeDraw,
            modifier = Modifier.weight(1f),
        )
        // 折叠/展开箭头图标，仅在有子任务时显示
        if (showCollapseArrow && onToggleCollapse != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (arrowRotation == -90f) "展开子任务" else "折叠子任务",
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleCollapse,
                    ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 任务勾选框 —— 圆形 checkbox，集成对勾绘制动画、背景填充、光芒射线爆发。
 *
 * 完成过程视觉：
 * 1. 背景圆从透明填充为 primary（由 checkBgColor 的 animateColorAsState 驱动）
 * 2. 对勾笔画用 PathMeasure 从起点逐段绘制到终点（由 checkDrawProgress 驱动）
 * 3. 光芒射线从勾选框中心向外迸发并淡出（由 burstProgress 驱动，仅 showBurst 时）
 *
 * 层级（从底到顶）：爆发层（requiredSize 60dp 超出 24dp 父框绘制）→ 背景圆 → 对勾图标。
 * 爆发在底层使其被勾选框中心遮住，视觉上"光芒从勾选框后方射出"。
 *
 * @param effectiveDone 实际视觉完成态（visualDone ?: isDone）
 * @param checkDrawProgress 对勾笔画绘制进度 0~1；0 显示空心圆，>0 绘制对应比例的对勾
 * @param checkBgColor 勾选框背景色（完成 primary / 未完成 透明），由外层 animateColorAsState 驱动
 * @param priorityColor 优先级颜色，未完成时 RadioButtonUnchecked 图标用此色
 * @param onToggleDone 点击切换完成状态回调
 * @param burstProgress 光芒射线爆发进度 0~1；<=0 不绘制爆发层
 * @param burstColor 爆发射线颜色（primary）
 * @param isGrand 盛大模式：射线数量翻倍（16 条 vs 8 条）
 */
@Composable
internal fun TaskCheckbox(
    effectiveDone: Boolean,
    checkDrawProgress: Float,
    checkBgColor: Color,
    priorityColor: Color,
    onToggleDone: () -> Unit,
    burstProgress: Float,
    burstColor: Color,
    isGrand: Boolean,
    /** 勾选框外框尺寸：主任务 24dp，子任务 18dp */
    boxSize: Dp = 24.dp,
    /** 对勾图标尺寸，与 boxSize 成比例：主任务 14dp，子任务 10dp */
    checkSize: Dp = 14.dp,
    /** 爆发画布尺寸：勾选框通用 60dp，保证射线有足够空间向外延伸 */
    burstSize: Dp = 60.dp,
) {
    // 外层 Box：boxSize，负责点击区域。不带 clip，允许爆发层绘制超出边界
    Box(
        modifier = Modifier
            .size(boxSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleDone,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // ── 光芒射线爆发（已禁用：视觉效果不理想，暂时只保留对勾绘制 + 删除线）──
        // 若日后想恢复，取消下方注释即可；上层 TaskScreen 的 burst 判断逻辑仍保留，
        // burstProgress 仍会正常驱动，只是此处不再绘制。
        // if (burstProgress > 0f) {
        //     CompletionBurst(
        //         progress = burstProgress,
        //         color = burstColor,
        //         modifier = Modifier.requiredSize(burstSize),
        //         rayCount = if (isGrand) 16 else 8,
        //     )
        // }
        // 背景圆：matchParentSize + clip(CircleShape)，只裁剪自身的 background 到圆形，
        // 不影响兄弟的爆发层（爆发在它之前声明 = 更底层）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(checkBgColor),
        )
        // 勾选图标：effectiveDone 或对勾开始绘制时显示对勾，否则显示空心圆
        if (effectiveDone || checkDrawProgress > 0f) {
            // 已完成但非动画（checkDrawProgress 可能=0 但 effectiveDone=true）时显示完整对勾（progress=1）
            AnimatedCheckIcon(
                progress = if (checkDrawProgress <= 0f && effectiveDone) 1f else checkDrawProgress,
                modifier = Modifier.size(checkSize),
            )
        } else {
            Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = "未完成",
                tint = priorityColor,
                modifier = Modifier.size(boxSize),
            )
        }
    }
}

/**
 * 对勾绘制图标 —— 用 PathMeasure 按进度截取对勾折线，模拟笔画从起点绘制到终点。
 *
 * 对勾由两段线组成（左下起点 → 中下拐点 → 右上终点），在 14dp 画布内用相对坐标定义。
 * progress=0 时无可见笔画，progress=1 时完整对勾，中间值显示对应比例的前缀笔画。
 *
 * @param progress 绘制进度 0~1
 * @param modifier 画布尺寸修饰符，建议 14dp（与原 Check 图标尺寸一致）
 */
@Composable
private fun AnimatedCheckIcon(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // 完成态对勾颜色：在 primary 背景上用 onPrimary，保证对比度
    val checkColor = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier = modifier) {
        if (progress <= 0f) return@Canvas
        // 对勾折线的三个关键点（相对坐标 0~1，乘以画布尺寸得到实际坐标）
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0.18f * w, 0.50f * h)  // 左下起点
            lineTo(0.42f * w, 0.72f * h)  // 中下拐点（对勾底部）
            lineTo(0.82f * w, 0.26f * h)  // 右上终点
        }
        // PathMeasure 测量整条 path 长度，按 progress 截取 [0, length*progress] 段
        val measure = PathMeasure().apply { setPath(path, false) }
        val dst = Path()
        measure.getSegment(0f, measure.length * progress, dst, true)
        // 圆角线帽让笔画两端柔和，strokeWidth 与画布尺寸协调
        drawPath(
            dst,
            color = checkColor,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * 任务文字内容区 —— 标题 + 描述 + 提醒时间行的纵向排列。
 *
 * 标题行包含任务名称（完成时颜色渐变 + 叠加删除线动画）和每日循环图标。
 * 删除线由 drawWithContent 绘制（长度随 strikeDraw 增长），代替原 TextDecoration.LineThrough 的硬切换。
 *
 * @param model 任务卡片数据
 * @param isNotebook 笔记模式：true 时描述去除 Markdown 语法符号后显示
 * @param titleColor 标题文字颜色（外层 animateColorAsState 算好，完成时为中灰）
 * @param strikeDraw 删除线绘制进度 0~1（外层根据动画/完成态算好）
 */
@Composable
private fun TaskTextContent(
    model: TaskCardModel,
    modifier: Modifier = Modifier,
    isNotebook: Boolean = false,
    titleColor: Color,
    strikeDraw: Float,
) {
    Column(modifier = modifier) {
        // 标题行：任务名 + 每日循环图标
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                // 颜色由外层 titleColor 控制（完成时渐变到中灰）
                color = titleColor,
                // 去掉原 TextDecoration.LineThrough，改用下方 drawWithContent 绘制可动画的删除线
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .drawWithContent {
                        // 先画文字内容
                        drawContent()
                        // 再叠加删除线：水平线在文字垂直中线，长度随 strikeDraw 从 0 增长到满宽
                        if (strikeDraw > 0f) {
                            val y = size.height / 2f
                            val lineEnd = size.width * strikeDraw
                            drawLine(
                                color = titleColor,
                                start = Offset(0f, y),
                                end = Offset(lineEnd, y),
                                strokeWidth = 1.4.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    },
            )
            // 每日任务：卡片末尾只显示循环图标，使用 tertiary 装饰色
            if (model.isDaily) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Repeat,
                    contentDescription = "每天",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.TEXT_MEDIUM),
                )
            }
        }
        // 描述：单行省略，仅在有内容时显示
        // 笔记模式下去除 Markdown 语法符号再显示预览
        val displayDesc = if (isNotebook) model.description?.let { stripMarkdown(it) } else model.description
        if (!displayDesc.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = displayDesc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 提醒时间显示行
        TaskReminderInfoRow(model = model)
    }
}

/**
 * 任务提醒信息行 —— 显示一次性提醒和/或每日循环提醒时间。
 *
 * 显示规则：
 * - 每日循环任务：只显示时钟图标 + HH:MM，逾期时变红
 * - 一次性提醒任务：显示铃铛图标 + 完整日期时间，逾期时变红
 * - 两者可同时显示（每日循环任务也可设置一次性提醒）
 *
 * @param model 任务卡片数据
 */
@Composable
private fun TaskReminderInfoRow(
    model: TaskCardModel,
) {
    val hasReminderTime = !model.reminderTime.isNullOrBlank()
    // 非每日任务：有 reminder 就显示铃铛；每日任务：reminder 日期不是今天时也显示日期
    val hasReminder = !model.reminder.isNullOrBlank() && (!model.isDaily || !model.reminder.isReminderToday())
    // 每日任务的逾期判断：通过 reminder 字段（自动设为今天日期+时间）检测是否已过时
    val isDailyOverdue = model.isDaily && model.reminder.isReminderOverdue() && !model.isDone
    if (hasReminder || hasReminderTime) {
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 一次性提醒部分：铃铛图标 + 格式化的完整日期时间，逾期变红
            if (hasReminder) {
                val isOverdue = model.reminder.isReminderOverdue() && !model.isDone
                Icon(
                    if (model.isDaily) Icons.Outlined.CalendarToday else Icons.Outlined.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.TEXT_MEDIUM),
                )
                Spacer(modifier = Modifier.width(4.dp))
                // 每日循环任务只显示日期（如"6月3日"），非每日任务显示完整日期时间
                val displayText = if (model.isDaily) {
                    model.reminder.formatReminderDate() ?: ""
                } else {
                    model.reminder.formatReminder() ?: ""
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.TEXT_MEDIUM),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 每日循环提醒时间部分：时钟图标 + HH:MM 时间，逾期时图标和文字变红
            if (hasReminderTime) {
                if (hasReminder) Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = if (isDailyOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = model.reminderTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDailyOverdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
