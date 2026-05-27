package com.echonion.nion.reminder

import android.util.Log
import com.echonion.nion.ui.companion.ApiType
import com.echonion.nion.ui.companion.ChatService
import com.echonion.nion.ui.companion.ProviderConfig
import com.echonion.nion.ui.companion.builtInProviders
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 提醒系统共享 LLM 客户端 —— 统一管理 LLM 配置读取、调用、结果清理。
 *
 * 提取自 ReminderMessageGenerator / GreetingWorker / BatchReminderWorker，
 * 消除三处重复的「读取 API 配置 → 构建 ProviderConfig → 调用 ChatService → 清理 Markdown」流程。
 *
 * 使用方式：
 * ```kotlin
 * val client = ReminderLlmClient.fromCore(core) ?: return fallback()
 * val result = client.chat(systemPrompt, userMessage)
 * if (result != null) { /* 使用 LLM 结果 */ }
 * else { /* 模板兜底 */ }
 * ```
 */
object ReminderLlmClient {

    private const val TAG = "ReminderLlmClient"

    /**
     * 从 NionCore 的 settings 表读取 LLM 配置并创建客户端实例。
     *
     * 必须同时存在 llm_provider、llm_api_key、llm_model 三个配置项才能成功创建。
     *
     * @param core NionCore 实例
     * @return 配置完成的客户端实例，缺少必要配置时返回 null
     */
    fun fromCore(core: NionCore): Instance? {
        val providerName = core.getSetting("llm_provider") ?: return null
        val apiKey = core.getSetting("llm_api_key") ?: return null
        val model = core.getSetting("llm_model") ?: return null
        val baseUrl = core.getSetting("llm_base_url") ?: ""

        // 从内置 Provider 列表中查找匹配的配置（获取正确的 baseUrl 和 apiType）
        val providerConfig = builtInProviders.find { it.name == providerName }
            ?: ProviderConfig(providerName, baseUrl, ApiType.OPENAI_COMPATIBLE)

        return Instance(providerConfig, apiKey, model)
    }

    /**
     * LLM 客户端实例 —— 封装一次完整的 LLM 配置，支持多次调用。
     *
     * @property provider Provider 配置（baseUrl + apiType）
     * @property apiKey API 密钥
     * @property model 模型名称
     */
    class Instance(
        private val provider: ProviderConfig,
        private val apiKey: String,
        private val model: String,
    ) {
        /**
         * 发送 system + user 消息并返回清理后的纯文本结果。
         *
         * 自动处理：
         * - 调用 ChatService.chatSimple（不带工具的纯文本请求）
         * - 清理 LLM 输出中的 Markdown 格式（粗体、代码块等）
         * - 空结果或异常返回 null（由调用方决定兜底策略）
         *
         * @param systemPrompt 系统提示词
         * @param userMessage 用户消息
         * @return 清理后的纯文本，失败返回 null
         */
        suspend fun chat(systemPrompt: String, userMessage: String): String? {
            return try {
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

                val result = ChatService.chatSimple(provider, apiKey, model, messages)
                val text = result.getOrNull()

                if (!text.isNullOrBlank()) {
                    ReminderUtils.stripMarkdown(text)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "LLM 调用失败", e)
                null
            }
        }
    }
}
