package com.echonion.nion.ui.companion

import android.util.Log
import com.echonion.nion.ui.companion.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * API 类型枚举 —— 决定 chat 和 models 端点的请求/响应格式。
 */
enum class ApiType {
    /** OpenAI 兼容 API（GPT、DeepSeek、vLLM、Ollama 等） */
    OPENAI_COMPATIBLE,
    /** Anthropic Messages API（Claude） */
    ANTHROPIC,
}

/**
 * LLM Provider 配置信息。
 *
 * @property name 显示名称
 * @property baseUrl API 基础地址（不含端点路径）
 * @property apiType API 类型
 */
data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiType: ApiType,
)

/**
 * 预置 Provider 列表。
 * 模型名称不再内置 —— 用户输入 API Key 后通过 API 动态获取可用模型列表。
 */
val builtInProviders = listOf(
    ProviderConfig("OpenAI", "https://api.openai.com/v1", ApiType.OPENAI_COMPATIBLE),
    ProviderConfig("Anthropic", "https://api.anthropic.com/v1", ApiType.ANTHROPIC),
    ProviderConfig("DeepSeek", "https://api.deepseek.com/v1", ApiType.OPENAI_COMPATIBLE),
    ProviderConfig("自定义", "", ApiType.OPENAI_COMPATIBLE),
)

/**
 * 聊天服务 —— 负责与 LLM API 通信，支持 Tool Calling。
 *
 * 支持两种 API 格式：
 * - OpenAI 兼容（/chat/completions）：用于 OpenAI、DeepSeek、自定义
 * - Anthropic（/messages）：用于 Claude
 *
 * Tool Calling 流程：
 * 1. 请求时附带 tools 参数（工具 Schema 列表）
 * 2. 响应中可能包含 tool_calls（LLM 请求执行的工具）
 * 3. 调用方执行工具后，将结果以特定格式追加到消息列表，再次请求
 */
object ChatService {

    /**
     * 从 Provider API 获取可用模型列表。
     *
     * @param provider Provider 配置
     * @param apiKey  API 密钥
     * @return 模型 ID 列表（如 ["gpt-4o", "gpt-4o-mini", ...]）
     */
    suspend fun fetchModels(
        provider: ProviderConfig,
        apiKey: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = when (provider.apiType) {
                ApiType.OPENAI_COMPATIBLE -> URL("${provider.baseUrl}/models")
                ApiType.ANTHROPIC -> URL("${provider.baseUrl}/models?limit=100")
            }

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                when (provider.apiType) {
                    ApiType.OPENAI_COMPATIBLE -> {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    ApiType.ANTHROPIC -> {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                }
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
                return@withContext Result.failure(Exception("获取模型列表失败 (HTTP $responseCode): $errorBody"))
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseText)
            val models = json.getJSONArray("data").let { arr ->
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).getString("id")
                }
            }
            Result.success(models)
        } catch (e: Exception) {
            Result.failure(Exception("获取模型列表异常: ${e.message}", e))
        }
    }

    /**
     * 向 LLM API 发送带 Tool Calling 支持的聊天请求。
     *
     * 与旧版的区别：
     * - 自动附带 [ToolRegistry] 中注册的所有工具 Schema
     * - 返回 [ChatResponse]（可能包含 tool_calls）而非纯文本
     * - messages 格式支持 tool / function 角色
     *
     * @param provider Provider 配置
     * @param apiKey   API 密钥
     * @param model    模型名称
     * @param messages 消息列表，每项是完整的 JSON 对象（含 role / content / tool_calls 等）
     * @return 成功时返回 [ChatResponse]，失败时返回异常
     */
    suspend fun chatWithTools(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            when (provider.apiType) {
                ApiType.OPENAI_COMPATIBLE -> chatOpenAIWithTools(provider.baseUrl, apiKey, model, messages)
                ApiType.ANTHROPIC -> chatAnthropicWithTools(provider.baseUrl, apiKey, model, messages)
            }
        } catch (e: Exception) {
            Result.failure(Exception("网络请求异常: ${e.message}", e))
        }
    }

    /**
     * OpenAI 兼容 API 的 Tool Calling 实现。
     *
     * 请求体格式：
     * - tools: [{type:"function", function:{name, description, parameters}}]
     * - messages: 标准 OpenAI 消息数组，支持 role=tool
     *
     * 响应解析：
     * - choices[0].message.content → 文本回复
     * - choices[0].message.tool_calls → 工具调用列表
     */
    private fun chatOpenAIWithTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
    ): Result<ChatResponse> {
        val url = URL("$baseUrl/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        // 构建请求体：model + messages + tools
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                for (msg in messages) put(msg)
            })
            // 附加工具 Schema
            put("tools", ToolRegistry.toOpenAITools())
        }

        val bodyStr = requestBody.toString()
        Log.d("ChatService", "OpenAI request body: ${bodyStr.take(2000)}")

        connection.outputStream.use { os ->
            os.write(bodyStr.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            Log.e("ChatService", "API 请求失败 (HTTP $responseCode): $errorBody")
            return Result.failure(Exception("API 请求失败 (HTTP $responseCode): $errorBody"))
        }

        val responseText = connection.inputStream.bufferedReader()?.readText()
            ?: return Result.failure(Exception("响应体为空"))
        val json = JSONObject(responseText)
        val message = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        return Result.success(parseOpenAIResponse(message))
    }

    /**
     * 解析 OpenAI 格式的响应消息。
     *
     * 处理两种情况：
     * - message.content 有值 → 纯文本回复
     * - message.tool_calls 有值 → 工具调用请求
     */
    private fun parseOpenAIResponse(message: JSONObject): ChatResponse {
        // 提取文本内容（可能为 null）
        val text = message.optString("content", null)?.takeIf { it.isNotEmpty() && it != "null" }

        // 提取工具调用（OpenAI 格式）
        val toolCallsArray = message.optJSONArray("tool_calls")
        val toolCalls = if (toolCallsArray != null && toolCallsArray.length() > 0) {
            (0 until toolCallsArray.length()).map { i ->
                val tc = toolCallsArray.getJSONObject(i)
                val function = tc.getJSONObject("function")
                ToolCall(
                    id = tc.getString("id"),
                    name = function.getString("name"),
                    arguments = function.getString("arguments"),
                )
            }
        } else null

        return ChatResponse(text = text, toolCalls = toolCalls, rawMessage = message)
    }

    /**
     * Anthropic Messages API 的 Tool Calling 实现。
     *
     * 与 OpenAI 的关键差异：
     * - tools 格式：{name, description, input_schema}
     * - system 是顶层字段
     * - 响应 content 数组可同时包含 text 和 tool_use 块
     * - 工具结果以 role=user + content[{type:tool_result}] 回传
     */
    private fun chatAnthropicWithTools(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
    ): Result<ChatResponse> {
        val url = URL("$baseUrl/messages")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        // 分离 system 提示词和对话消息
        val systemPrompt = messages
            .firstOrNull { it.optString("role") == "system" }
            ?.optString("content", "") ?: ""
        val conversationMessages = JSONArray().apply {
            for (msg in messages) {
                val role = msg.optString("role", "")
                // system 不进入 messages 数组，tool 角色需要转换为 Anthropic 格式
                if (role != "system") {
                    put(msg)
                }
            }
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            if (systemPrompt.isNotEmpty()) put("system", systemPrompt)
            put("messages", conversationMessages)
            // Anthropic 格式的工具 Schema
            put("tools", ToolRegistry.toAnthropicTools())
        }

        val bodyStr = requestBody.toString()
        Log.d("ChatService", "Anthropic request body: ${bodyStr.take(2000)}")

        connection.outputStream.use { os ->
            os.write(bodyStr.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            Log.e("ChatService", "Anthropic API 失败 (HTTP $responseCode): $errorBody")
            return Result.failure(Exception("API 请求失败 (HTTP $responseCode): $errorBody"))
        }

        val responseText = connection.inputStream.bufferedReader()?.readText()
            ?: return Result.failure(Exception("响应体为空"))
        val json = JSONObject(responseText)

        return Result.success(parseAnthropicResponse(json))
    }

    /**
     * 解析 Anthropic 格式的响应。
     *
     * Anthropic 响应格式：
     * {
     *   "content": [
     *     {"type": "text", "text": "..."},
     *     {"type": "tool_use", "id": "...", "name": "...", "input": {...}}
     *   ]
     * }
     *
     * content 数组可同时包含多个 text 和 tool_use 块。
     */
    private fun parseAnthropicResponse(json: JSONObject): ChatResponse {
        val contentArray = json.optJSONArray("content") ?: JSONArray()

        var text: String? = null
        val toolCalls = mutableListOf<ToolCall>()

        for (i in 0 until contentArray.length()) {
            val block = contentArray.getJSONObject(i)
            when (block.optString("type")) {
                "text" -> {
                    // 合并多个文本块
                    val blockText = block.getString("text")
                    text = if (text == null) blockText else "$text\n$blockText"
                }
                "tool_use" -> {
                    // Anthropic 的 input 是 JSON 对象，需要序列化为字符串
                    val input = block.opt("input")
                    val argumentsStr = when (input) {
                        is JSONObject -> input.toString()
                        is String -> input
                        else -> "{}"
                    }
                    toolCalls.add(ToolCall(
                        id = block.getString("id"),
                        name = block.getString("name"),
                        arguments = argumentsStr,
                    ))
                }
            }
        }

        return ChatResponse(
            text = text,
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
        )
    }
}