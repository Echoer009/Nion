package com.echonion.nion.reminder

import android.util.Log
import com.echonion.nion.ui.companion.PromptDefaults
import com.echonion.nion.ui.companion.tools.MemoryTool
import uniffi.nion_core.NionCore
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒系统共享工具类 —— 集中管理跨文件复用的解析和格式化逻辑。
 *
 * 提取自 ReminderScheduler / ReminderMessageGenerator / GreetingWorker，
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
     * 清理 LLM 输出中的 Markdown 格式和表情包标签。
     *
     * 仅用于系统通知（NotificationHelper），悬浮窗和 App 内 Overlay 使用 MarkdownText 渲染。
     * - 去除粗体 (**text** / __text__)
     * - 去除代码块 (```...```)
     * - 去除行内代码 (`text`)
     * - 去除表情包标签 (<微笑>、<开心> 等)
     *
     * @param text 原始 LLM 输出
     * @return 清理后的纯文本
     */
    fun stripMarkdown(text: String): String {
        return text.trim()
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("__(.*?)__"), "$1")
            .replace(Regex("\\|\\|(.*?)\\|\\|"), "$1")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace("`", "")
            .replace(Regex("<[^>]+>"), "")
            .trim()
    }

    /**
     * 构建与聊天对话完全一致的 system prompt 前缀。
     *
     * 结构：人设 + 回复格式 + 用户偏好 + 用户记忆 + 表情包 + 场景模板
     * 所有段落的拼接顺序、排序逻辑、分隔符与 CompanionViewModel.buildApiMessages() 完全一致，
     * 确保 system 前缀在聊天和提醒系统间完全相同，命中同一份 API Prefix Cache。
     *
     * @param core NionCore 单例，用于读取 settings 和 stickers
     * @param scenePrompt 场景模板（如问候、提醒、天气预警的具体上下文），拼在最后
     * @return 完整的 system prompt 字符串
     */
    fun buildSystemPrompt(core: NionCore, scenePrompt: String = ""): String {
        val companionName = core.getSetting("companion_name") ?: PromptDefaults.DEFAULT_COMPANION_NAME

        return buildString {
            // ── 人设 ──
            val persona = (core.getSetting(PromptDefaults.KEY_PERSONA) ?: PromptDefaults.PERSONA)
                .replace("{name}", companionName)
            append(persona)

            // ── 回复格式 ──
            val format = core.getSetting(PromptDefaults.KEY_FORMAT) ?: PromptDefaults.FORMAT
            if (format.isNotBlank()) {
                append("\n\n")
                append(format)
            }

            // ── 用户偏好记录 ──
            val prefsJson = core.getSetting(MemoryTool.PREFS_SETTING_KEY)
            if (!prefsJson.isNullOrEmpty()) {
                try {
                    val prefs = org.json.JSONArray(prefsJson)
                    if (prefs.length() > 0) {
                        append("\n\n---\n用户偏好记录（必须始终遵守）：")
                        val categoryLabels = mapOf(
                            "style" to "风格", "behavior" to "行为",
                            "format" to "格式", "other" to "其他",
                        )
                        // 按 id 排序确保前缀稳定
                        val sorted = (0 until prefs.length())
                            .map { prefs.getJSONObject(it) }
                            .sortedBy { it.optString("id", "") }
                        for (pref in sorted) {
                            val label = categoryLabels[pref.optString("category", "other")] ?: "其他"
                            append("\n- [$label] ${pref.getString("content")}")
                        }
                    }
                } catch (_: Exception) {}
            }

            // ── 用户记忆 ──
            val memoriesJson = core.getSetting(MemoryTool.FACTS_SETTING_KEY)
            if (!memoriesJson.isNullOrEmpty()) {
                try {
                    val memories = org.json.JSONArray(memoriesJson)
                    if (memories.length() > 0) {
                        val memoryCategoryLabels = MemoryTool.categoryLabels
                        append("\n\n---\n关于用户的记忆（${companionName}的笔记本）：")
                        // 按 id 排序确保前缀稳定
                        val sorted = (0 until memories.length())
                            .map { memories.getJSONObject(it) }
                            .sortedBy { it.optString("id", "") }
                        for (mem in sorted) {
                            val label = memoryCategoryLabels[mem.optString("category", "other")] ?: "其他"
                            append("\n- [$label] ${mem.getString("content")}")
                        }
                    }
                } catch (_: Exception) {}
            }

            // ── 表情包 ──
            append(buildStickerListPrompt(core))

            // ── 场景模板 ──
            if (scenePrompt.isNotBlank()) {
                append("\n\n---\n")
                append(scenePrompt)
            }
        }
    }

    /**
     * 构建表情包可用列表的 prompt 片段。
     *
     * 与 CompanionViewModel.buildStickerPrompt() 完全一致（文本、排序、分隔符），
     * 确保 system 前缀相同以命中缓存。
     *
     * @param core NionCore 单例，用于查询 stickers 表
     * @return 格式化后的表情列表 prompt 片段，无表情时返回空串
     */
    fun buildStickerListPrompt(core: NionCore): String {
        return try {
            val stickers = core.getStickers()
            if (stickers.isEmpty()) {
                ""
            } else {
                // 按 id 排序，与 CompanionViewModel.buildStickerPrompt() 保持一致
                val sorted = stickers.sortedBy { it.id }
                val sb = StringBuilder("\n\n---\n可以使用以下表情包：")
                for (sticker in sorted) {
                    sb.append("\n- <${sticker.tag}>")
                }
                sb.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }
}
