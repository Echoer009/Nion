package com.echonion.nion.ui.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 任务卡片组件
 * 显示单个任务的标题、描述、优先级色标、完成状态切换按钮
 * 完成时有颜色变化、透明度降低、删除线等动画效果
 *
 * @param task 任务数据
 * @param onToggleDone 切换完成状态的回调
 * @param onClick 点击任务的回调（打开详情弹窗）
 * @param onSubtaskClick 点击子任务的回调
 * @param modifier 外部 modifier
 * @param elevation 卡片阴影高度（拖拽排序时使用）
 */
@Composable
fun TaskCard(
    task: TaskItem,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    onSubtaskClick: (TaskItem) -> Unit,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
) {
    val priorityColor = task.priority.priorityColor

    // 卡片背景色动画：完成时使用更深的容器色
    val cardColor by animateColorAsState(
        targetValue = if (task.isDone)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(300),
        label = "cardColor",
    )

    // 内容透明度动画：完成时降低到 50%
    val contentAlpha by animateFloatAsState(
        targetValue = if (task.isDone) 0.5f else 1f,
        animationSpec = tween(300),
        label = "contentAlpha",
    )

    // 勾选按钮的弹性缩放动画
    val checkScale = remember { Animatable(1f) }
    LaunchedEffect(task.isDone) {
        if (task.isDone) {
            checkScale.animateTo(1.3f, tween(120))
            checkScale.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessHigh,
                ),
            )
        }
    }

    // 勾选按钮背景色动画
    val checkBgColor by animateColorAsState(
        targetValue = if (task.isDone) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "checkBg",
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = cardColor,
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = androidx.compose.material3.CardDefaults.elevatedCardElevation(
            defaultElevation = elevation,
        ),
    ) {
        Column {
            // 任务主体行：勾选按钮 + 标题/描述
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onClick(task) },
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 勾选按钮
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
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已完成",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        // 未完成时显示优先级色圈的空心圆
                        Icon(
                            Icons.Default.RadioButtonUnchecked,
                            contentDescription = "未完成",
                            tint = priorityColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                // 标题和描述区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = contentAlpha }
                ) {
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
                }
            }

            // 子任务列表（递归渲染）
            if (task.subtasks.isNotEmpty()) {
                SubtaskList(
                    subtasks = task.subtasks,
                    onToggleDone = onToggleDone,
                    onClick = onSubtaskClick,
                )
            }
        }
    }
}

/**
 * 子任务列表组件
 * 通过左侧缩进表示层级关系，支持递归嵌套渲染
 */
@Composable
private fun SubtaskList(
    subtasks: List<TaskItem>,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
) {
    val checkSize = 18.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, bottom = 4.dp),
    ) {
        subtasks.forEach { sub ->
            val priorityColor = sub.priority.priorityColor
            val subCheckBg = if (sub.isDone) MaterialTheme.colorScheme.primary else Color.Transparent

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 子任务勾选按钮 — 独立点击区域，不被父级 Row 拦截
                Box(
                    modifier = Modifier
                        .size(checkSize)
                        .clip(CircleShape)
                        .background(subCheckBg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onToggleDone(sub) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sub.isDone) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
                    } else {
                        Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null, tint = priorityColor.copy(alpha = 0.5f), modifier = Modifier.size(checkSize))
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 标题区域：点击打开详情
                Text(
                    sub.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (sub.isDone) TextDecoration.LineThrough else null,
                    color = if (sub.isDone) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onClick(sub) },
                        ),
                )
            }

            // 递归渲染更深层的子任务
            if (sub.subtasks.isNotEmpty()) {
                SubtaskList(
                    subtasks = sub.subtasks,
                    onToggleDone = onToggleDone,
                    onClick = onClick,
                )
            }
        }
    }
}
