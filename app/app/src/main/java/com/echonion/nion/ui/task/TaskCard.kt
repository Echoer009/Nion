package com.echonion.nion.ui.task

import com.echonion.nion.ui.components.TaskCheckbox
import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.components.SharedTaskCard
import com.echonion.nion.ui.components.TaskCardModel
import com.echonion.nion.ui.theme.LocalPriorityColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import com.echonion.nion.ui.icons.LucideIcons

/**
 * 扁平任务行 —— 根据 depth 分发到主任务行或子任务行。
 *
 * @param item 扁平任务条目，包含任务数据和层级信息
 * @param onToggleDone 点击勾选框切换完成状态时触发
 * @param onClick 点击任务行时触发，打开任务详情
 * @param isSelected 当前任务是否处于选中状态（多选模式）
 * @param isGroupSelected 当前任务所属分组是否被选中
 * @param onToggleCollapse 点击折叠/展开图标时触发，传入任务 ID；null 表示不显示折叠图标
 * @param modifier 修饰符
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
 * @param playCompletionAnim 是否正在播放完成动画（由上层 animatingDoneIds 决定）
 * @param visualDone 视觉完成态覆盖：动画期间传 true 让 UI 呈现已完成，但 model.isDone 仍为 false
 * @param showBurst 是否在完成动画中显示光芒射线爆发（每天第一个/最后一个）
 * @param isGrand 是否为盛大爆发（"今天"清单清空全部待办时射线翻倍）
 * @param onCompletionAnimEnd 完成动画全部播放完毕的回调，上层在此调用真正的 toggleDone
 */
@Composable
fun FlatTaskRow(
    item: FlatTaskItem,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    isGroupSelected: Boolean,
    onToggleCollapse: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    sharedElementModifier: Modifier = Modifier,
    /** 笔记模式：true 时复选框替换为笔记图标 */
    isNotebook: Boolean = false,
    /** 是否正在播放完成动画（由上层 animatingDoneIds 决定） */
    playCompletionAnim: Boolean = false,
    /** 视觉完成态覆盖：动画期间传 true 让 UI 呈现已完成，但 model.isDone 仍为 false */
    visualDone: Boolean? = null,
    /** 是否在完成动画中显示光芒射线爆发（第一个/最后一个） */
    showBurst: Boolean = false,
    /** 盛大爆发：射线翻倍，用于"今天"清单清空全部待办 */
    isGrand: Boolean = false,
    /** 完成动画全部播放完毕的回调，上层在此调用真正的 toggleDone */
    onCompletionAnimEnd: () -> Unit = {},
) {
    val task = item.task

    if (item.depth == 0) {
        MainTaskRow(
            task = task,
            isGroupLast = item.isGroupLast,
            isCollapsed = item.isCollapsed,
            onToggleDone = onToggleDone,
            onClick = onClick,
            isSelected = isSelected,
            isGroupSelected = isGroupSelected,
            onToggleCollapse = onToggleCollapse,
            modifier = modifier,
            sharedElementModifier = sharedElementModifier,
            isNotebook = isNotebook,
            playCompletionAnim = playCompletionAnim,
            visualDone = visualDone,
            showBurst = showBurst,
            isGrand = isGrand,
            onCompletionAnimEnd = onCompletionAnimEnd,
        )
    } else {
        SubTaskRow(
            item = item,
            onToggleDone = onToggleDone,
            onClick = onClick,
            isSelected = isSelected,
            isGroupSelected = isGroupSelected,
            modifier = modifier,
            sharedElementModifier = sharedElementModifier,
            isNotebook = isNotebook,
            playCompletionAnim = playCompletionAnim,
            visualDone = visualDone,
            showBurst = showBurst,
            isGrand = isGrand,
            onCompletionAnimEnd = onCompletionAnimEnd,
        )
    }
}

private fun Modifier.groupBorder(
    isGroupFirst: Boolean,
    isGroupLast: Boolean,
    color: Color,
    widthPx: Float,
): Modifier = this.drawWithContent {
    drawContent()
    val w = widthPx
    val half = w / 2
    val r = 12f * this@drawWithContent.density
    val sw = this@drawWithContent.size.width
    val sh = this@drawWithContent.size.height
    if (isGroupFirst && isGroupLast) {
        drawRoundRect(color, style = Stroke(w), cornerRadius = CornerRadius(r))
        return@drawWithContent
    }
    val path = Path()
    if (isGroupFirst) {
        path.moveTo(half, sh)
        path.lineTo(half, r)
        path.quadraticTo(half, half, r, half)
        path.lineTo(sw - r, half)
        path.quadraticTo(sw - half, half, sw - half, r)
        path.lineTo(sw - half, sh)
    } else if (isGroupLast) {
        path.moveTo(half, 0f)
        path.lineTo(half, sh - r)
        path.quadraticTo(half, sh - half, r, sh - half)
        path.lineTo(sw - r, sh - half)
        path.quadraticTo(sw - half, sh - half, sw - half, sh - r)
        path.lineTo(sw - half, 0f)
    } else {
        path.moveTo(half, 0f)
        path.lineTo(half, sh)
        path.moveTo(sw - half, 0f)
        path.lineTo(sw - half, sh)
    }
    drawPath(path, color, style = Stroke(w))
}

/**
 * 主任务行 —— 委托 SharedTaskCard 渲染，在此基础上添加任务列表特有的
 * group border / selection border / shared element 动画 modifier。
 * 有子任务时右侧显示折叠/展开箭头图标。
 *
 * 完成动画参数（playCompletionAnim / visualDone / showBurst / isGrand / onCompletionAnimEnd）
 * 透传给 SharedTaskCard，由 SharedTaskCard 内部协调对勾绘制 / 删除线 / 爆发等子动画。
 *
 * @param task 任务数据
 * @param isGroupLast 是否为分组中最后一个任务（控制底部圆角）
 * @param isCollapsed 当前是否处于折叠状态
 * @param onToggleDone 点击勾选框切换完成状态时触发
 * @param onClick 点击任务行时触发，打开任务详情
 * @param isSelected 当前任务是否处于选中状态（多选模式）
 * @param isGroupSelected 当前任务所属分组是否被选中
 * @param onToggleCollapse 点击折叠/展开图标时触发，传入任务 ID；null 表示不显示折叠图标
 * @param modifier 修饰符
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
 * @param playCompletionAnim 是否正在播放完成动画
 * @param visualDone 视觉完成态覆盖
 * @param showBurst 是否显示光芒射线爆发
 * @param isGrand 是否盛大爆发
 * @param onCompletionAnimEnd 完成动画结束回调
 */
@Composable
private fun MainTaskRow(
    task: TaskItem,
    isGroupLast: Boolean,
    isCollapsed: Boolean,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    isGroupSelected: Boolean,
    onToggleCollapse: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    sharedElementModifier: Modifier = Modifier,
    /** 笔记模式：true 时复选框替换为笔记图标 */
    isNotebook: Boolean = false,
    /** 是否正在播放完成动画（透传给 SharedTaskCard） */
    playCompletionAnim: Boolean = false,
    /** 视觉完成态覆盖（透传给 SharedTaskCard） */
    visualDone: Boolean? = null,
    /** 是否显示光芒射线爆发（透传给 SharedTaskCard） */
    showBurst: Boolean = false,
    /** 盛大爆发（透传给 SharedTaskCard） */
    isGrand: Boolean = false,
    /** 完成动画结束回调（透传给 SharedTaskCard） */
    onCompletionAnimEnd: () -> Unit = {},
) {
    // 分组最后一行用 medium 圆角，否则仅顶部圆角（底部与子任务相连）
    val shape = if (isGroupLast) MaterialTheme.shapes.medium
        else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

    val borderColor = MaterialTheme.colorScheme.primary
    val borderWidth = with(LocalDensity.current) { 2.dp.toPx() }

    // 将 TaskItem 映射为共享数据模型
    val cardModel = remember(task) {
        TaskCardModel(
            id = task.id,
            name = task.name,
            description = task.description,
            priority = task.priority,
            isDone = task.isDone,
            isDaily = task.isDaily,
            reminderTime = task.recurrenceReminderTime,
            reminder = task.reminder,
        )
    }

    /* 折叠箭头旋转动画：展开=0°（朝下），折叠=-90°（朝右） */
    val arrowRotation by animateFloatAsState(
        targetValue = if (isCollapsed) -90f else 0f,
        animationSpec = spring(
            dampingRatio = 1.0f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "collapseArrow",
    )

    // 是否显示折叠箭头：有子任务且提供了回调
    val showCollapseArrow = onToggleCollapse != null && task.subtasks.isNotEmpty()

    // 在 SharedTaskCard 的 background() 之前注入 group border / selection / shared element，
    // 并透传完成动画参数（由 SharedTaskCard 内部协调子动画时序）
    SharedTaskCard(
        model = cardModel,
        onToggleDone = { onToggleDone(task) },
        onClick = { onClick(task) },
        shape = shape,
        showCollapseArrow = showCollapseArrow,
        arrowRotation = arrowRotation,
        onToggleCollapse = { onToggleCollapse?.invoke(task.id) },
        isNotebook = isNotebook,
        playCompletionAnim = playCompletionAnim,
        visualDone = visualDone,
        showBurst = showBurst,
        isGrand = isGrand,
        onCompletionAnimEnd = onCompletionAnimEnd,
        modifier = modifier
            .then(sharedElementModifier)
            .then(
                when {
                    isGroupSelected -> Modifier.groupBorder(true, isGroupLast, borderColor, borderWidth)
                    isSelected -> Modifier.border(BorderStroke(2.dp, borderColor), shape)
                    else -> Modifier
                }
            ),
    )
}

/**
 * 子任务行 —— 带缩进、小图标，嵌套在主任务下方。
 *
 * 与 MainTaskRow 委托 SharedTaskCard 不同，SubTaskRow 有独立的渲染逻辑，
 * 因此完成动画（对勾绘制 / 删除线 / 颜色渐变 / 爆发）需要在此自行协调。
 * 复用 components 层的 [TaskCheckbox] 组件（参数化尺寸 18dp）保证视觉一致。
 *
 * 动画时序与 SharedTaskCard 完全一致（见 SharedTaskCard 的文档注释）：
 * - t=0 对勾绘制 200ms；t=40ms 删除线 200ms；t=100ms 爆发（showBurst 时）
 * - 全部结束 → 触发 [onCompletionAnimEnd]
 *
 * @param item 扁平任务条目，包含任务数据和层级信息
 * @param onToggleDone 点击勾选框切换完成状态时触发
 * @param onClick 点击任务行时触发，打开任务详情
 * @param isSelected 当前任务是否处于选中状态（多选模式）
 * @param isGroupSelected 当前任务所属分组是否被选中
 * @param modifier 修饰符
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
 * @param playCompletionAnim 是否正在播放完成动画
 * @param visualDone 视觉完成态覆盖
 * @param showBurst 是否显示光芒射线爆发
 * @param isGrand 盛大爆发
 * @param onCompletionAnimEnd 完成动画结束回调
 */
@Composable
private fun SubTaskRow(
    item: FlatTaskItem,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    isGroupSelected: Boolean,
    modifier: Modifier = Modifier,
    sharedElementModifier: Modifier = Modifier,
    /** 笔记模式：true 时复选框替换为笔记图标，不可点击切换完成 */
    isNotebook: Boolean = false,
    /** 是否正在播放完成动画 */
    playCompletionAnim: Boolean = false,
    /** 视觉完成态覆盖：动画期间传 true */
    visualDone: Boolean? = null,
    /** 是否显示光芒射线爆发 */
    showBurst: Boolean = false,
    /** 盛大爆发 */
    isGrand: Boolean = false,
    /** 完成动画结束回调 */
    onCompletionAnimEnd: () -> Unit = {},
) {
    val task = item.task
    // effectiveDone：实际视觉完成态。visualDone 非 null 时覆盖 task.isDone
    val effectiveDone = visualDone ?: task.isDone

    val priorityColors = LocalPriorityColors.current
    val priorityColor = task.priority.priorityColor(priorityColors)
    val checkSize = 18.dp
    val indentPerLevel = 20.dp
    val indent = indentPerLevel * item.depth

    /* 子任务背景色动画：完成后渐变到 surfaceContainerLow。用 effectiveDone 让动画期间也跟随 */
    val cardColor by animateColorAsState(
        targetValue = if (effectiveDone)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(300),
        label = "cardColor",
    )

    /* 勾选框背景色动画：完成时 primary，未完成时透明 */
    val checkBgColor by animateColorAsState(
        targetValue = if (effectiveDone) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "checkBg",
    )

    /* 标题颜色动画：完成时渐变到中灰 */
    val titleColor by animateColorAsState(
        targetValue = if (effectiveDone)
            MaterialTheme.colorScheme.onSurface.copy(alpha = NionAlpha.TEXT_MEDIUM)
        else
            MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(250),
        label = "titleColor",
    )

    // ============ 完成动画的三个子进度（仅 playCompletionAnim 期间被驱动） ============
    val checkProgress = remember { Animatable(0f) }
    val strikeProgress = remember { Animatable(0f) }
    val burstProgress = remember { Animatable(0f) }

    // 完成动画时序协调：与 SharedTaskCard 完全一致
    LaunchedEffect(playCompletionAnim) {
        if (!playCompletionAnim) return@LaunchedEffect
        checkProgress.snapTo(0f)
        strikeProgress.snapTo(0f)
        burstProgress.snapTo(0f)
        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            // 对勾绘制：立即开始，200ms 线性
            jobs += launch {
                checkProgress.animateTo(1f, tween(200, easing = LinearEasing))
            }
            // 删除线：延迟 40ms，200ms 划过
            jobs += launch {
                delay(40)
                strikeProgress.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            }
            // 光芒射线爆发：延迟 100ms，仅 showBurst 时；盛大 600ms / 普通 400ms
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
        onCompletionAnimEnd()
    }

    // 计算各子动画的当前显示值（动画期间用 Animatable 实时值，否则按完成态取 0 或 1）
    val checkDraw = if (playCompletionAnim) checkProgress.value else if (effectiveDone) 1f else 0f
    val strikeDraw = if (playCompletionAnim) strikeProgress.value else if (effectiveDone) 1f else 0f
    val burstDraw = if (playCompletionAnim) burstProgress.value else 0f

    val shape = if (item.isGroupLast)
        RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    else
        RoundedCornerShape(0.dp)

    val borderColor = MaterialTheme.colorScheme.primary
    val borderWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(sharedElementModifier)
            .then(
                when {
                    isGroupSelected -> Modifier.groupBorder(false, item.isGroupLast, borderColor, borderWidth)
                    isSelected -> Modifier.border(BorderStroke(2.dp, borderColor), shape)
                    else -> Modifier
                }
            )
            .background(cardColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(task) },
            )
            .padding(start = indent + 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isNotebook) {
            // 笔记模式：显示笔记图标，不可点击切换完成
            androidx.compose.material3.Icon(
                LucideIcons.FileText,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                modifier = Modifier.size(checkSize),
            )
        } else {
            // 任务模式：复用 components.TaskCheckbox，传 18dp 尺寸 + 子任务的 priorityColor 带 alpha
            // 爆发画布 50dp（比主任务 60dp 略小，体现视觉层次）
            TaskCheckbox(
                effectiveDone = effectiveDone,
                checkDrawProgress = checkDraw,
                checkBgColor = checkBgColor,
                priorityColor = priorityColor.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                onToggleDone = { onToggleDone(task) },
                burstProgress = burstDraw,
                burstColor = MaterialTheme.colorScheme.primary,
                isGrand = isGrand,
                boxSize = checkSize,
                checkSize = 10.dp,
                burstSize = 50.dp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            task.name,
            style = MaterialTheme.typography.bodyMedium,
            // 去掉原 TextDecoration.LineThrough，改用 drawWithContent 绘制可动画的删除线
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .drawWithContent {
                    drawContent()
                    // 删除线：水平线在文字垂直中线，长度随 strikeDraw 增长
                    if (strikeDraw > 0f) {
                        val y = size.height / 2f
                        val lineEnd = size.width * strikeDraw
                        drawLine(
                            color = titleColor,
                            start = Offset(0f, y),
                            end = Offset(lineEnd, y),
                            strokeWidth = 1.2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                },
        )
    }
}
