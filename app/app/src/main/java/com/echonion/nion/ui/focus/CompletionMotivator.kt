package com.echonion.nion.ui.focus

import android.util.Log
import com.echonion.nion.reminder.ReminderLlmClient
import com.echonion.nion.reminder.ReminderUtils
import com.echonion.nion.ui.companion.PromptDefaults
import com.echonion.nion.ui.companion.tools.ToolPhrasePool
import uniffi.nion_core.NionCore

/**
 * 专注完成鼓励生成器 —— 调用 LLM 生成个性化鼓励文案，失败时使用 ToolPhrasePool 兜底。
 *
 * 工作流程：
 * 1. 读取人设 prompt 作为 system prompt（和问候系统一致，人设已包含性格定义）
 * 2. 根据自然完成/中断选择不同的 prompt 模板，替换变量后作为 user message
 * 3. 调用 LLM 获取鼓励文案
 * 4. LLM 失败时从 ToolPhrasePool 随机选取一条模板文案
 *
 * 使用方式：
 * ```kotlin
 * val message = CompletionMotivator.generate(core, event)
 * ```
 */
object CompletionMotivator {

    private const val TAG = "CompletionMotivator"

    /**
     * 根据专注完成事件生成鼓励文案。
     *
     * 优先尝试 LLM 生成，失败时使用 ToolPhrasePool 兜底。
     *
     * @param core NionCore 实例，用于读取人设、风格、API 配置
     * @param event 专注完成事件数据
     * @return 鼓励文案字符串
     */
    suspend fun generate(core: NionCore, event: CompletionEvent): String {
        // 尝试 LLM 生成
        val llmResult = tryLlm(core, event)
        if (llmResult != null) return llmResult

        // LLM 失败 → ToolPhrasePool 兜底
        return fallback(core, event)
    }

    /**
     * 尝试调用 LLM 生成鼓励文案。
     *
     * system prompt = 人设 prompt（已包含角色性格定义）
     * user message = 专注数据上下文（从 PromptDefaults 模板替换变量得到）
     *
     * @return LLM 生成的文案，失败返回 null
     */
    private suspend fun tryLlm(core: NionCore, event: CompletionEvent): String? {
        val client = ReminderLlmClient.fromCore(core) ?: return null

        // 根据完成/中断选择 prompt 模板
        val promptKey = if (event.isEarlyStop) {
            PromptDefaults.KEY_FOCUS_INTERRUPTED
        } else {
            PromptDefaults.KEY_FOCUS_COMPLETE
        }
        val defaultPrompt = if (event.isEarlyStop) {
            PromptDefaults.FOCUS_INTERRUPTED
        } else {
            PromptDefaults.FOCUS_COMPLETE
        }

        // 从 settings 读取用户自定义的 prompt（可能被用户编辑过），缺失则用默认值
        val promptTemplate = core.getSetting(promptKey) ?: defaultPrompt

        // 替换模板变量，作为 user message
        val userMessage = promptTemplate
            .replace("{taskName}", event.taskName ?: "自由专注")
            .replace("{sessionMinutes}", event.sessionMinutes.toString())
            .replace("{plannedMinutes}", event.plannedMinutes.toString())
            .replace("{totalMinutes}", event.totalMinutes.toString())
            .replace("{todaySessions}", event.todaySessions.toString())
            .replace("{todayMinutes}", event.todayMinutes.toString())

        // 使用统一方法构建与聊天对话完全一致的 system prompt 前缀（无场景模板）
        val systemPrompt = ReminderUtils.buildSystemPrompt(core)
        val result = client.chat(systemPrompt, userMessage)
        if (result != null) return result

        Log.w(TAG, "LLM 返回空结果，使用模板兜底")
        return null
    }

    /**
     * ToolPhrasePool 模板兜底 —— 当 LLM 不可用时从预设模板随机选取鼓励文案。
     *
     * 根据当前伙伴风格和完成/中断类型选取对应的话术。
     *
     * @return 模板鼓励文案
     */
    private fun fallback(core: NionCore, event: CompletionEvent): String {
        val style = core.getSetting(PromptDefaults.KEY_COMPANION_STYLE)
            ?: PromptDefaults.DEFAULT_COMPANION_STYLE
        val subKey = if (event.isEarlyStop) "focus_interrupted" else "focus_complete"
        val vars = mapOf(
            "name" to (event.taskName ?: "自由专注"),
            "count" to event.sessionMinutes.toString(),
        )
        return ToolPhrasePool.pick(style, subKey, vars)
    }
}
