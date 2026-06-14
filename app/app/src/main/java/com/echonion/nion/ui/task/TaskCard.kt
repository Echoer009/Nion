package com.echonion.nion.ui.task

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.components.SharedTaskCard
import com.echonion.nion.ui.components.TaskCardModel
import com.echonion.nion.ui.theme.LocalPriorityColors

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

    // 在 SharedTaskCard 的 background() 之前注入 group border / selection / shared element
    SharedTaskCard(
        model = cardModel,
        onToggleDone = { onToggleDone(task) },
        onClick = { onClick(task) },
        shape = shape,
        showCollapseArrow = showCollapseArrow,
        arrowRotation = arrowRotation,
        onToggleCollapse = { onToggleCollapse?.invoke(task.id) },
        isNotebook = isNotebook,
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
 * @param item 扁平任务条目，包含任务数据和层级信息
 * @param onToggleDone 点击勾选框切换完成状态时触发
 * @param onClick 点击任务行时触发，打开任务详情
 * @param isSelected 当前任务是否处于选中状态（多选模式）
 * @param isGroupSelected 当前任务所属分组是否被选中
 * @param modifier 修饰符
 * @param sharedElementModifier shared element 动画 modifier，用于任务详情展开时的 morph 动画
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
) {
    val task = item.task
    val priorityColors = LocalPriorityColors.current
    val priorityColor = task.priority.priorityColor(priorityColors)
    val checkSize = 18.dp
    val indentPerLevel = 20.dp
    val indent = indentPerLevel * item.depth

    /* 子任务背景色动画：完成后渐变到 surfaceContainerLow，与 SharedTaskCard 一致 */
    val cardColor by animateColorAsState(
        targetValue = if (task.isDone)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(300),
        label = "cardColor",
    )

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
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                modifier = Modifier.size(checkSize),
            )
        } else {
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
                    Icon(
                        Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = priorityColor.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                        modifier = Modifier.size(checkSize)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            task.name,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            color = if (task.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = NionAlpha.TEXT_SUBTITLE) else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
