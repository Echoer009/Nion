package com.echonion.nion.ui.task

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 根据优先级字符串返回对应的颜色：高=红，中=橙，低=灰蓝 */
val String.priorityColor: Color
    get() = when (this) {
        "high" -> Color(0xFFD32F2F)
        "medium" -> Color(0xFFF4511E)
        else -> Color(0xFF607D8B)
    }

/** 根据优先级字符串返回中文标签 */
val String.priorityLabel: String
    get() = when (this) {
        "high" -> "高"
        "medium" -> "中"
        else -> "低"
    }

/** ISO 日期字符串（如 "2026-06-01"）格式化为中文显示（如 "6月1日 周一"） */
fun String?.formatDueDate(): String? {
    if (this.isNullOrBlank()) return null
    return try {
        val date = LocalDate.parse(this, DateTimeFormatter.ISO_LOCAL_DATE)
        val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        "${date.monthValue}月${date.dayOfMonth}日 ${weekDays[date.dayOfWeek.value - 1]}"
    } catch (_: Exception) {
        null
    }
}

/** 判断截止日期是否已逾期（早于今天） */
fun String?.isOverdue(): Boolean {
    if (this.isNullOrBlank()) return false
    return try {
        LocalDate.parse(this, DateTimeFormatter.ISO_LOCAL_DATE) < LocalDate.now()
    } catch (_: Exception) {
        false
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
