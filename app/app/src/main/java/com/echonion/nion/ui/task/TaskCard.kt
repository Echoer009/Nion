package com.echonion.nion.ui.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 扁平任务行 —— 根据 depth 分发到主任务行或子任务行。
 *
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
 */
@Composable
fun FlatTaskRow(
    item: FlatTaskItem,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    isGroupSelected: Boolean,
    modifier: Modifier = Modifier,
    sharedElementModifier: Modifier = Modifier,
) {
    val task = item.task
    val priorityColor = task.priority.priorityColor

    val cardColor by animateColorAsState(
        targetValue = if (task.isDone)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(300),
        label = "cardColor",
    )

    if (item.depth == 0) {
        MainTaskRow(
            task = task,
            cardColor = cardColor,
            priorityColor = priorityColor,
            isGroupLast = item.isGroupLast,
            onToggleDone = onToggleDone,
            onClick = onClick,
            isSelected = isSelected,
            isGroupSelected = isGroupSelected,
            modifier = modifier,
            sharedElementModifier = sharedElementModifier,
        )
    } else {
        SubTaskRow(
            item = item,
            cardColor = cardColor,
            priorityColor = priorityColor,
            onToggleDone = onToggleDone,
            onClick = onClick,
            isSelected = isSelected,
            isGroupSelected = isGroupSelected,
            modifier = modifier,
            sharedElementModifier = sharedElementModifier,
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
 * 主任务行 —— 显示主任务标题、描述、完成状态。
 *
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
 */
@Composable
private fun MainTaskRow(
    task: TaskItem,
    cardColor: Color,
    priorityColor: Color,
    isGroupLast: Boolean,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    isGroupSelected: Boolean,
    modifier: Modifier = Modifier,
    sharedElementModifier: Modifier = Modifier,
) {
    val shape = if (isGroupLast) MaterialTheme.shapes.medium
        else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

    val checkScale = remember { Animatable(1f) }
    /**
     * 记录上一次 render 时的 isDone 状态，用于区分：
     * - 首次进入 composition（isDone 本来就是 true）→ 跳过动画
     * - 真正的状态变化（isDone 从 false 变为 true）→ 播放弹跳动画
     */
    var wasDone by remember { mutableStateOf(task.isDone) }
    LaunchedEffect(task.isDone) {
        if (task.isDone && !wasDone) {
            checkScale.animateTo(1.3f, tween(120))
            checkScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh))
        }
        wasDone = task.isDone
    }

    val checkBgColor by animateColorAsState(
        targetValue = if (task.isDone) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "checkBg",
    )

    val borderColor = MaterialTheme.colorScheme.primary
    val borderWidth = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(sharedElementModifier)
            .then(
                when {
                    isGroupSelected -> Modifier.groupBorder(true, isGroupLast, borderColor, borderWidth)
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .scale(checkScale.value)
                .clip(CircleShape)
                .background(checkBgColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onToggleDone(task) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (task.isDone) {
                Icon(Icons.Default.Check, contentDescription = "已完成", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            } else {
                Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "未完成", tint = priorityColor, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!task.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 截止日期 + 时间显示：日历图标 + 格式化日期，逾期时文字变红；有时间则追加显示
            if (!task.dueDate.isNullOrBlank() || !task.recurrenceReminderTime.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 图标选择：有日期用日历图标，只有时间用时钟图标
                    Icon(
                        if (!task.dueDate.isNullOrBlank()) Icons.Outlined.CalendarToday
                            else Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (task.dueDate.isOverdue()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = buildString {
                            task.dueDate.formatDueDate()?.let { append(it) }
                            if (!task.dueDate.isNullOrBlank() && !task.recurrenceReminderTime.isNullOrBlank()) {
                                append(" ")
                            }
                            task.recurrenceReminderTime?.let { append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.dueDate.isOverdue()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 子任务行 —— 带缩进、小图标，嵌套在主任务下方。
 *
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
 */
@Composable
private fun SubTaskRow(
    item: FlatTaskItem,
    cardColor: Color,
    priorityColor: Color,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    isGroupSelected: Boolean,
    modifier: Modifier = Modifier,
    sharedElementModifier: Modifier = Modifier,
) {
    val task = item.task
    val checkSize = 18.dp
    val indentPerLevel = 20.dp
    val indent = indentPerLevel * item.depth

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
        Box(
            modifier = Modifier
                .size(checkSize)
                .clip(CircleShape)
                .background(if (task.isDone) MaterialTheme.colorScheme.primary else Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onToggleDone(task) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (task.isDone) {
                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
            } else {
                Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = priorityColor.copy(alpha = 0.5f), modifier = Modifier.size(checkSize))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            color = if (task.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
