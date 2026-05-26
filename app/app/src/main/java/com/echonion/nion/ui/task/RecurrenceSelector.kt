package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 循环规则选择器 —— 用于任务创建表单和任务详情中。
 *
 * 支持选择"不重复"或"每天"循环。当选择"每天"时，展开内联滚轮时间选择器，
 * 上下滑动切换小时和分钟，点击保存后生效。
 *
 * @param recurrenceRule 当前循环规则：null 或 "none" 表示不循环，"daily" 表示每日循环
 * @param reminderTime 提醒时间，格式 "HH:MM"，仅当 recurrenceRule="daily" 时有效
 * @param onRecurrenceChanged 循环规则变更回调，传入新的 rule 值（null/"none"/"daily"）
 * @param onReminderTimeChanged 提醒时间变更回调，传入 "HH:MM" 格式字符串
 */
@Composable
fun RecurrenceSelector(
    recurrenceRule: String?,
    reminderTime: String?,
    onRecurrenceChanged: (String?) -> Unit,
    onReminderTimeChanged: (String) -> Unit,
) {
    // 解析初始小时和分钟，默认 09:00
    val initialHour = remember(reminderTime) {
        reminderTime?.substringBefore(":")?.toIntOrNull() ?: 9
    }
    val initialMinute = remember(reminderTime) {
        reminderTime?.substringAfter(":")?.toIntOrNull() ?: 0
    }

    // 当前选中的小时和分钟（本地状态，由滚轮驱动更新）
    var selectedHour by remember { mutableStateOf(initialHour) }
    var selectedMinute by remember { mutableStateOf(initialMinute) }

    // 当外部 reminderTime 变更时（如切换任务），重置选中值
    LaunchedEffect(initialHour, initialMinute) {
        selectedHour = initialHour
        selectedMinute = initialMinute
    }

    // 当切换到"每天"时，确保外部有提醒时间值（避免 localTime 为 null）
    LaunchedEffect(recurrenceRule == "daily") {
        if (recurrenceRule == "daily") {
            onReminderTimeChanged("%02d:%02d".format(selectedHour, selectedMinute))
        }
    }

    // 滚轮可见行数和行高，与 WheelSpinner 配合
    val visibleItemCount = 5
    val itemHeight: Dp = 48.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        // 循环规则标题行：图标 + "重复" 文字
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
                        // null 或 "none" 表示不循环
                        onRecurrenceChanged(null)
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

        // 内联滚轮时间选择器：仅当循环规则为 "daily" 时展开
        AnimatedVisibility(
            visible = recurrenceRule == "daily",
            // 展开：淡入 + 从上往下展开，tween(300) 让展开动画更柔和
            enter = fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                    expandVertically(tween(300, easing = FastOutSlowInEasing)),
            // 收起：淡出 + 从下往上收起
            exit = fadeOut(tween(200, easing = FastOutSlowInEasing)) +
                   shrinkVertically(tween(250, easing = FastOutSlowInEasing)),
        ) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))

                // "小时" 和 "分钟" 标签行，对齐到各自滚轮的中心位置
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "小时",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 48.dp),
                    )
                    Spacer(modifier = Modifier.width(24.dp))
                    Text(
                        "分钟",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 48.dp),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 滚轮选择区域：高亮背景 + 小时滚轮 + 冒号 + 分钟滚轮
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight * visibleItemCount),
                    contentAlignment = Alignment.Center,
                ) {
                    // 选中行的高亮背景
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {}

                    // 两列滚轮并排
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 小时滚轮 (0-23)，weight(1f) 平分宽度
                        WheelSpinner(
                            items = (0..23).map { "%02d".format(it) },
                            initialIndex = initialHour,
                            visibleItemCount = visibleItemCount,
                            itemHeight = itemHeight,
                            onSelected = {
                                selectedHour = it
                                // 实时回调时间变更
                                onReminderTimeChanged(
                                    "%02d:%02d".format(it, selectedMinute)
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )

                        // 冒号分隔符
                        Text(
                            ":",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )

                        // 分钟滚轮 (0-59)，weight(1f) 平分宽度
                        WheelSpinner(
                            items = (0..59).map { "%02d".format(it) },
                            initialIndex = initialMinute,
                            visibleItemCount = visibleItemCount,
                            itemHeight = itemHeight,
                            onSelected = {
                                selectedMinute = it
                                // 实时回调时间变更
                                onReminderTimeChanged(
                                    "%02d:%02d".format(selectedHour, it)
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 循环规则选项按钮 —— 在"不重复"和"每天"之间切换的 Chip 样式按钮。
 *
 * 选中时使用 primary 背景色 + onPrimary 文字色，未选中时透明背景 + 默认文字色。
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
