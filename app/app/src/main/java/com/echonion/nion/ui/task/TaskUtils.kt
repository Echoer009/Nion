package com.echonion.nion.ui.task

import androidx.compose.ui.graphics.Color
import com.echonion.nion.ui.theme.LocalPriorityColors
import androidx.compose.runtime.Composable
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 根据优先级字符串返回对应的主题颜色。
 * 颜色跟随主题变化（通过 LocalPriorityColors），不再硬编码。
 *
 * @param priorityColors 当前主题的优先级颜色配置
 * @return 对应优先级的颜色
 */
fun String.priorityColor(priorityColors: com.echonion.nion.ui.theme.PriorityColors): Color = when (this) {
    "high" -> priorityColors.high
    "medium" -> priorityColors.medium
    else -> priorityColors.low
}

/** 根据优先级字符串返回中文标签 */
val String.priorityLabel: String
    get() = when (this) {
        "high" -> "高"
        "medium" -> "中"
        else -> "低"
    }

/**
 * 将一次性提醒字符串（"YYYY-MM-DDTHH:MM"）格式化为卡片显示文本。
 *
 * 返回格式示例："6月1日 09:00"。解析失败时返回 null。
 * 用于 SharedTaskCard 在卡片上显示提醒时间。
 */
fun String?.formatReminder(): String? {
    if (this.isNullOrBlank()) return null
    return try {
        val date = LocalDate.parse(substringBefore("T"), DateTimeFormatter.ISO_LOCAL_DATE)
        val timePart = substringAfter("T")
        val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val weekDay = weekDays[date.dayOfWeek.value - 1]
        "${date.monthValue}月${date.dayOfMonth}日 $weekDay $timePart"
    } catch (_: Exception) {
        null
    }
}

/** 判断提醒时间是否已逾期（日期部分早于今天） */
fun String?.isReminderOverdue(): Boolean {
    if (this.isNullOrBlank()) return false
    return try {
        val dateStr = substringBefore("T")
        LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE) < LocalDate.now()
    } catch (_: Exception) {
        false
    }
}

/** 判断提醒字符串中的日期部分是否就是今天，解析失败返回 false */
fun String?.isReminderToday(): Boolean {
    if (this.isNullOrBlank()) return false
    return try {
        val dateStr = substringBefore("T")
        LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE) == LocalDate.now()
    } catch (_: Exception) {
        false
    }
}

/** 从提醒字符串中提取日期部分，格式化为中文（如 "6月15日"），解析失败返回 null */
fun String?.formatReminderDate(): String? {
    if (this.isNullOrBlank()) return null
    return try {
        val date = LocalDate.parse(substringBefore("T"), DateTimeFormatter.ISO_LOCAL_DATE)
        "${date.monthValue}月${date.dayOfMonth}日"
    } catch (_: Exception) {
        null
    }
}

/**
 * 格式化累计专注时间（秒 → 可读字符串）。
 *
 * @param seconds 累计秒数
 * @return 格式化后的字符串，如 "2h 30m"、"45m"、"暂无专注"
 */
fun formatFocusTime(seconds: Long): String {
    if (seconds <= 0) return "暂无专注"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "不到1分钟"
    }
}
