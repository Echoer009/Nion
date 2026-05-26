package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 循环规则选择器 —— 用于任务创建表单和任务详情中。
 *
 * 支持选择"不重复"或"每天"循环。当选择"每天"时，展开显示提醒时间选择器。
 *
 * @param recurrenceRule 当前循环规则：null 或 "none" 表示不循环，"daily" 表示每日循环
 * @param reminderTime 提醒时间，格式 "HH:MM"，仅当 recurrenceRule="daily" 时有效
 * @param onRecurrenceChanged 循环规则变更回调，传入新的 rule 值（null/"none"/"daily"）
 * @param onReminderTimeChanged 提醒时间变更回调，传入 "HH:MM" 格式字符串
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurrenceSelector(
    recurrenceRule: String?,
    reminderTime: String?,
    onRecurrenceChanged: (String?) -> Unit,
    onReminderTimeChanged: (String) -> Unit,
) {
    // showTimePicker: 是否显示时间选择对话框
    var showTimePicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 循环规则标题行：图标 + 当前状态文字
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Repeat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (recurrenceRule == "daily") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "重复",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 循环规则切换按钮：不重复 / 每天
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                // "不重复" 选项按钮
                RecurrenceOptionChip(
                    text = "不重复",
                    isSelected = recurrenceRule != "daily",
                    onClick = {
                        onRecurrenceChanged(null) // null 或 "none" 表示不循环
                    },
                    modifier = Modifier.weight(1f),
                )
                // "每天" 选项按钮
                RecurrenceOptionChip(
                    text = "每天",
                    isSelected = recurrenceRule == "daily",
                    onClick = {
                        onRecurrenceChanged("daily")
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 提醒时间行：仅当循环规则为 "daily" 时展开显示
        AnimatedVisibility(
            visible = recurrenceRule == "daily",
            // 展开：提醒时间行淡入，使用 tween(250) 遵循现有颜色过渡规范
            enter = fadeIn(tween(250, easing = FastOutSlowInEasing)),
            // 收起：提醒时间行淡出
            exit = fadeOut(tween(250, easing = FastOutSlowInEasing)),
        ) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                // 提醒时间选择行：时钟图标 + 当前时间文字
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showTimePicker = true },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (reminderTime != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "提醒时间",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = reminderTime ?: "09:00",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (reminderTime != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 时间选择对话框：当用户点击提醒时间行时弹出
    if (showTimePicker) {
        TimePickerDialog(
            initialTime = reminderTime,
            onConfirm = { time ->
                onReminderTimeChanged(time)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

/**
 * 循环规则选项按钮 —— 在"不重复"和"每天"之间切换的 Chip 样式按钮。
 *
 * 选中时使用 primary 背景色 + onPrimary 文字色，未选中时透明背景 + 默认文字色。
 * 颜色过渡使用 animateColorAsState 默认 tween，与现有规范一致。
 *
 * @param text 按钮文字（"不重复" / "每天"）
 * @param isSelected 是否选中状态
 * @param onClick 点击回调
 * @param modifier 外部 modifier
 */
@Composable
private fun RecurrenceOptionChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHighest
    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant

    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
        )
    }
}
