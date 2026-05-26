package com.echonion.nion.reminder

import android.util.Log
import com.echonion.nion.ui.companion.ApiType
import com.echonion.nion.ui.companion.ChatService
import com.echonion.nion.ui.companion.ProviderConfig
import com.echonion.nion.ui.companion.builtInProviders
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 提醒文案生成器 —— 根据任务信息和紧迫度生成个性化的提醒消息。
 *
 * 两种生成策略：
 * - **LLM 生成**：有 API key 配置时，调用 ChatService 生成上下文感知的个性化文案
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
            1 -> Triple("开始做了", "等5分钟", "今天算了")
            2 -> Triple("开始做了", "等5分钟", "今天算了")
            3 -> Triple("马上开始", "最后5分钟", "今天算了")
            4 -> Triple("现在开始", "真的不做了", "")
            else -> Triple("知道了", "", "")
        }
    }

    /**
     * 使用 LLM 生成个性化提醒文案。
     *
     * 从 settings 表读取 API 配置，构建精简的 prompt 调用 ChatService。
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
        try {
            // 读取 API 配置
            val providerName = core.getSetting("llm_provider") ?: return generateFromTemplate(taskTitle, triggerCount)
            val apiKey = core.getSetting("llm_api_key") ?: return generateFromTemplate(taskTitle, triggerCount)
            val model = core.getSetting("llm_model") ?: return generateFromTemplate(taskTitle, triggerCount)
            val baseUrl = core.getSetting("llm_base_url") ?: ""
            val apiTypeStr = core.getSetting("llm_api_type") ?: "OPENAI_COMPATIBLE"

            // 找到对应的 ProviderConfig
            val providerConfig = builtInProviders.find { it.name == providerName }
                ?: ProviderConfig(providerName, baseUrl, ApiType.OPENAI_COMPATIBLE)

            // 构建精简的 prompt（不需要工具，只需要纯文本回复）
            val systemPrompt = buildSystemPrompt(triggerCount)
            val userMessage = buildUserMessage(taskTitle, taskPriority, triggerCount)

            val messages = listOf(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                },
                JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                },
            )

            // 使用不带工具的简单聊天（只需要纯文本回复，节省 token）
            val result = ChatService.chatSimple(providerConfig, apiKey, model, messages)
            val response = result.getOrNull()

            if (!response.isNullOrBlank()) {
                // 清理可能的 Markdown 格式（通知中不应该有 Markdown）
                return response.trim()
                    .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // 去粗体
                    .replace(Regex("__(.*?)__"), "$1") // 去下划线粗体
                    .replace(Regex("```[\\s\\S]*?```"), "") // 去代码块
                    .replace("`", "") // 去行内代码
                    .trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM 生成提醒文案失败，使用模板兜底", e)
        }

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
     * 根据紧迫度级别指定 Nion 的语气和行为。
     */
    private fun buildSystemPrompt(triggerCount: Int): String {
        val level = triggerCount.coerceIn(1, 5)
        val tone = TONE_DESCRIPTIONS[level]
        val companionName = "Nion"

        return """你是 $companionName，用户的 AI 伙伴。现在需要你给用户发一条任务提醒消息。
当前紧迫度级别：$level/5（1=温和，5=最后通牒）
语气要求：$tone
规则：
- 只说 1-2 句话，简短有力
- 不要用任何 Markdown 格式（**粗体**、#标题、代码块等）
- 不要加表情符号前缀
- 直接说内容，不要说"提醒你"之类的废话
- 如果是最后一级（5），温柔告别即可，不要催促"""
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
