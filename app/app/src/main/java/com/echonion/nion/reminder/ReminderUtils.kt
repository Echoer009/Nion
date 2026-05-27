package com.echonion.nion.reminder

import android.util.Log
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒系统共享工具类 —— 集中管理跨文件复用的解析和格式化逻辑。
 *
 * 提取自 ReminderScheduler / BatchReminderWorker / ReminderMessageGenerator / GreetingWorker，
 * 消除 parseReminderToMillis（3 份重复）和 Markdown 清理（2 份重复）的代码冗余。
 */
object ReminderUtils {

    private const val TAG = "ReminderUtils"

    /**
     * 解析 reminder 字符串为毫秒时间戳。
     *
     * 支持三种格式：
     * - "YYYY-MM-DDTHH:MM"（本地时间，最常用）
     * - "YYYY-MM-DDTHH:MM:SS"（ISO_LOCAL_DATE_TIME）
     * - RFC 3339（带时区后缀，如 "2025-01-15T14:30:00+08:00"）
     *
     * @param reminder reminder 字段原始字符串
     * @return 毫秒时间戳（epoch），解析失败返回 null
     */
    fun parseReminderToMillis(reminder: String): Long? {
        return try {
            // 优先尝试 "YYYY-MM-DDTHH:MM" 格式（大多数场景）
            val ldt = try {
                LocalDateTime.parse(reminder, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(reminder, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                } catch (_: Exception) {
                    // 最后尝试带时区的 RFC 3339
                    return try {
                        java.time.OffsetDateTime.parse(reminder).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            ldt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "无法解析 reminder: $reminder", e)
            null
        }
    }

    /**
     * 清理 LLM 输出中的 Markdown 格式。
     *
     * 通知和弹窗中不应出现 Markdown 语法，需要统一清理：
     * - 去除粗体 (**text** / __text__)
     * - 去除代码块 (```...```)
     * - 去除行内代码 (`text`)
     *
     * @param text 原始 LLM 输出
     * @return 清理后的纯文本
     */
    fun stripMarkdown(text: String): String {
        return text.trim()
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("__(.*?)__"), "$1")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace("`", "")
            .trim()
    }
}
