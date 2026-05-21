package com.echonion.nion.ui.task

import androidx.compose.ui.graphics.Color

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
