package com.echonion.nion.ui.task

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun FlatTaskRow(
    item: FlatTaskItem,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
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

    val contentAlpha by animateFloatAsState(
        targetValue = if (task.isDone) 0.5f else 1f,
        animationSpec = tween(300),
        label = "contentAlpha",
    )

    if (item.depth == 0) {
        MainTaskRow(
            task = task,
            cardColor = cardColor,
            contentAlpha = contentAlpha,
            priorityColor = priorityColor,
            isGroupLast = item.isGroupLast,
            onToggleDone = onToggleDone,
            onClick = onClick,
            isSelected = isSelected,
            modifier = modifier,
        )
    } else {
        SubTaskRow(
            item = item,
            cardColor = cardColor,
            priorityColor = priorityColor,
            onToggleDone = onToggleDone,
            onClick = onClick,
            isSelected = isSelected,
            modifier = modifier,
        )
    }
}

@Composable
private fun MainTaskRow(
    task: TaskItem,
    cardColor: Color,
    contentAlpha: Float,
    priorityColor: Color,
    isGroupLast: Boolean,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = if (isGroupLast) MaterialTheme.shapes.medium
        else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

    val borderShape = if (isGroupLast) RoundedCornerShape(16.dp)
        else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

    val checkScale = remember { Animatable(1f) }
    LaunchedEffect(task.isDone) {
        if (task.isDone) {
            checkScale.animateTo(1.3f, tween(120))
            checkScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh))
        }
    }

    val checkBgColor by animateColorAsState(
        targetValue = if (task.isDone) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "checkBg",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cardColor, shape)
            .then(if (isSelected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), borderShape) else Modifier)
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
        }
    }
}

@Composable
private fun SubTaskRow(
    item: FlatTaskItem,
    cardColor: Color,
    priorityColor: Color,
    onToggleDone: (TaskItem) -> Unit,
    onClick: (TaskItem) -> Unit,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val task = item.task
    val checkSize = 18.dp
    val indentPerLevel = 20.dp
    val indent = indentPerLevel * item.depth

    val shape = if (item.isGroupLast)
        RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    else
        RoundedCornerShape(0.dp)

    val borderShape = if (item.isGroupLast)
        RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
    else
        RoundedCornerShape(0.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cardColor, shape)
            .then(if (isSelected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), borderShape) else Modifier)
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
