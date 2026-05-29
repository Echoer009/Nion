package com.echonion.nion.reminder

import android.util.Log
import uniffi.nion_core.NionCore
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

    /**
     * 构建表情包可用列表的 prompt 片段 —— 从 DB 读取所有表情包，格式化为 LLM 可理解的列表。
     *
     * 将所有表情包标签以尖括号形式列出（如 <微笑>、<开心>、<专注>），
     * 让 LLM 知道哪些标签是可用的，避免生成不存在的标签导致渲染失败。
     *
     * 用于所有后台 LLM 调用的 system prompt 注入（提醒、问候、天气预警）。
     *
     * @param core NionCore 单例，用于查询 stickers 表
     * @return 格式化后的表情列表 prompt 片段，无表情时返回空串
     */
    fun buildStickerListPrompt(core: NionCore): String {
        return try {
            val stickers = core.getStickers()
            if (stickers.isEmpty()) ""
            else {
                val tagList = stickers.joinToString("、") { "<${it.tag}>" }
                "\n可用表情包（用尖括号包裹标签名即可插入图片）：$tagList"
            }
        } catch (_: Exception) {
            ""
        }
    }
}
