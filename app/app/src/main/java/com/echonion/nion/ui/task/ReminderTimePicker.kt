package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 一次性提醒时间选择器 —— 复用 NionCalendar（日期）+ WheelSpinner（时间）。
 *
 * 交互流程：
 * 1. 显示当前提醒时间或"设置提醒"
 * 2. 点击后展开日历选日期
 * 3. 日期选完后展开时间滚轮（HH:MM）
 * 4. 确认后回调传出 "YYYY-MM-DDTHH:MM" 格式的字符串
 *
 * @param reminder 当前提醒时间，格式 "YYYY-MM-DDTHH:MM"，null 表示未设置
 * @param onReminderChanged 提醒时间变更回调，传入新的时间字符串或 null（清除）
 */
@Composable
fun ReminderTimePicker(
    reminder: String?,
    onReminderChanged: (String?) -> Unit,
) {
    // 解析现有 reminder 的日期和时间部分
    val initialDate = remember(reminder) {
        try {
            if (reminder != null) reminder.substringBefore("T")
            else null
        } catch (_: Exception) { null }
    }
    val initialHour = remember(reminder) {
        try {
            if (reminder != null) {
                val timePart = reminder.substringAfter("T")
                timePart.substringBefore(":").toIntOrNull() ?: 9
            } else 9
        } catch (_: Exception) { 9 }
    }
    val initialMinute = remember(reminder) {
        try {
            if (reminder != null) {
                val timePart = reminder.substringAfter("T")
                timePart.substringAfter(":").substringBefore(":").toIntOrNull() ?: 0
            } else 0
        } catch (_: Exception) { 0 }
    }

    // 是否展开选择器
    var isExpanded by remember { mutableStateOf(false) }
    // 已选择的日期
    var selectedDate by remember(initialDate) {
        mutableStateOf(initialDate?.let {
            try { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) { null }
        })
    }
    // 已选择的小时和分钟
    var selectedHour by remember { mutableStateOf(initialHour) }
    var selectedMinute by remember { mutableStateOf(initialMinute) }

    // 当外部 reminder 变更时同步内部状态
    LaunchedEffect(initialHour, initialMinute) {
        selectedHour = initialHour
        selectedMinute = initialMinute
    }

    // 滚轮参数
    val visibleItemCount = 5
    val itemHeight: Dp = 48.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题行：闹钟图标 + "提醒" 文字 + 当前值
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Alarm,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (reminder != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "提醒",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            // 当前提醒时间或"设置提醒"，点击展开/收起
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (reminder != null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isExpanded = !isExpanded },
                    ),
            ) {
                Text(
                    text = formatReminderDisplay(reminder),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (reminder != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 展开的选择器区域
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                    expandVertically(tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(200, easing = FastOutSlowInEasing)) +
                   shrinkVertically(tween(250, easing = FastOutSlowInEasing)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(16.dp))

                // 日历选择区域
                NionCalendar(
                    initialYearMonth = selectedDate?.let { YearMonth.from(it) } ?: YearMonth.now(),
                    today = LocalDate.now(),
                    selectedDate = selectedDate,
                    onSelect = { selectedDate = it },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 时间滚轮选择区域
                // "小时" 和 "分钟" 标签行
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

                // 滚轮区域：高亮背景 + 小时滚轮 + 冒号 + 分钟滚轮
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 小时滚轮 (0-23)
                        WheelSpinner(
                            items = (0..23).map { "%02d".format(it) },
                            initialIndex = selectedHour,
                            visibleItemCount = visibleItemCount,
                            itemHeight = itemHeight,
                            onSelected = { selectedHour = it },
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

                        // 分钟滚轮 (0-59)
                        WheelSpinner(
                            items = (0..59).map { "%02d".format(it) },
                            initialIndex = selectedMinute,
                            visibleItemCount = visibleItemCount,
                            itemHeight = itemHeight,
                            onSelected = { selectedMinute = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 操作按钮行：清除 + 取消 + 确定
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 清除提醒
                    TextButton(
                        onClick = {
                            onReminderChanged(null)
                            isExpanded = false
                            selectedDate = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("清除", fontWeight = FontWeight.SemiBold, maxLines = 1) }

                    // 取消
                    TextButton(
                        onClick = { isExpanded = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1) }

                    // 确定：拼接日期+时间并回调
                    Button(
                        onClick = {
                            if (selectedDate != null) {
                                val result = "%sT%02d:%02d".format(
                                    selectedDate!!.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                    selectedHour,
                                    selectedMinute,
                                )
                                onReminderChanged(result)
                            }
                            isExpanded = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                    ) { Text("确定", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * 格式化提醒时间为显示文字。
 * "2026-06-15T14:30" → "6月15日 14:30"
 * null → "设置提醒"
 */
private fun formatReminderDisplay(reminder: String?): String {
    if (reminder == null) return "设置提醒"
    return try {
        val ldt = LocalDateTime.parse(reminder, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        "%d月%d日 %02d:%02d".format(
            ldt.monthValue,
            ldt.dayOfMonth,
            ldt.hour,
            ldt.minute,
        )
    } catch (_: Exception) {
        reminder
    }
}
