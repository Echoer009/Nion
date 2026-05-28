package com.echonion.nion.reminder

import android.util.Log
import com.echonion.nion.ui.companion.PromptDefaults
import uniffi.nion_core.NionCore

/**
 * 提醒文案生成器 —— 根据任务信息和紧迫度生成个性化的提醒消息。
 *
 * 两种生成策略：
 * - **LLM 生成**：有 API key 配置时，通过 ReminderLlmClient 生成上下文感知的个性化文案
 * - **模板兜底**：无 API key 时，使用预设的紧迫度模板（保证基本功能可用）
 */
object ReminderMessageGenerator {

    private const val TAG = "ReminderMsgGen"

    /**
     * 紧迫度对应的语气描述，用于 LLM prompt 和模板文案。
     * 索引 0 未使用，1-5 对应 5 个紧迫度级别。
     */
    private val TONE_DESCRIPTIONS = arrayOf(
        "", // 占位，索引 0 未使用
        "温和友好，像朋友轻轻提醒",
        "稍微催促，但不要让用户觉得烦",
        "明确紧迫，提醒已经拖了一段时间了",
        "认真询问，质疑用户是否还要做这件事",
        "温柔告别，表示不再打扰",
    )

    /**
     * 模板文案，无 LLM 时使用。
     * 索引 0 未使用，1-5 对应 5 个紧迫度级别。
     */
    private val TEMPLATES = arrayOf(
        "", // 占位
        "「%s」的时间到了～准备好了吗？",
        "「%s」还没开始哦，现在开始吧？",
        "「%s」已经拖了一会儿了！该行动了！",
        "「%s」—— 你确定今天还要做吗？",
        "好吧，「%s」的提醒就到这里。需要的时候随时找我～",
    )

    /**
     * 根据紧迫度获取通知按钮文案。
     * 不同紧迫度下按钮文字不同，增强递进感。
     *
     * @param triggerCount 当前触发次数（1-5）
     * @return Triple(开始按钮文案, 贪睡按钮文案, 取消按钮文案)
     */
    fun getActionLabels(triggerCount: Int): Triple<String, String, String> {
        return when (triggerCount) {
            1 -> Triple("知道了", "等5分钟", "")
            2 -> Triple("知道了", "等5分钟", "")
            3 -> Triple("知道了", "最后5分钟", "")
            4 -> Triple("知道了", "真的不做了", "")
            else -> Triple("知道了", "", "")
        }
    }

    /**
     * 使用 LLM 生成个性化提醒文案。
     *
     * 通过 ReminderLlmClient 统一读取 API 配置并发起调用。
     * 如果 LLM 调用失败，自动回退到模板文案。
     *
     * @param core NionCore 实例，用于读取 API 配置
     * @param taskTitle 任务标题
     * @param taskPriority 任务优先级
     * @param triggerCount 当前触发次数（1-5）
     * @return 生成的提醒文案
     */
    suspend fun generateWithLLM(
        core: NionCore,
        taskTitle: String,
        taskPriority: String,
        triggerCount: Int,
    ): String {
        // 尝试通过共享 LLM 客户端生成
        val client = ReminderLlmClient.fromCore(core)
        if (client != null) {
            val systemPrompt = buildSystemPrompt(core, triggerCount)
            val userMessage = buildUserMessage(taskTitle, taskPriority, triggerCount)
            val result = client.chat(systemPrompt, userMessage)
            if (result != null) return result
        }

        // LLM 不可用或调用失败，使用模板兜底
        return generateFromTemplate(taskTitle, triggerCount)
    }

    /**
     * 使用模板生成提醒文案（无 LLM 时的兜底方案）。
     *
     * @param taskTitle 任务标题
     * @param triggerCount 当前触发次数（1-5）
     * @return 模板生成的提醒文案
     */
    fun generateFromTemplate(taskTitle: String, triggerCount: Int): String {
        val level = triggerCount.coerceIn(1, 5)
        return TEMPLATES[level].format(taskTitle)
    }

    /**
     * 构建 LLM system prompt。
     * 从 settings 表读取用户自定义的提示词模板，替换模板变量。
     */
    private fun buildSystemPrompt(core: NionCore, triggerCount: Int): String {
        val level = triggerCount.coerceIn(1, 5)
        val tone = TONE_DESCRIPTIONS[level]
        val companionName = core.getSetting("companion_name") ?: "Nion"
        val template = core.getSetting(PromptDefaults.KEY_REMINDER) ?: PromptDefaults.REMINDER
        return template
            .replace("{name}", companionName)
            .replace("{level}", level.toString())
            .replace("{tone}", tone)
    }

    /**
     * 构建 LLM user message。
     * 包含任务的完整上下文信息。
     */
    private fun buildUserMessage(taskTitle: String, taskPriority: String, triggerCount: Int): String {
        val priorityLabel = when (taskPriority) {
            "high" -> "高优先级"
            "medium" -> "中优先级"
            "low" -> "低优先级"
            else -> taskPriority
        }
        val elapsedMinutes = (triggerCount - 1) * ReminderStore.LOOP_INTERVAL_MINUTES

        return """任务：$taskTitle
优先级：$priorityLabel
已提醒次数：$triggerCount
${if (triggerCount > 1) "距离设定时间已过约 $elapsedMinutes 分钟" else ""}"""
    }
}
