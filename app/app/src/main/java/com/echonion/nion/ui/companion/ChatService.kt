package com.echonion.nion.ui.companion

import android.util.Log
import com.echonion.nion.ui.companion.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

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
     * 流式聊天客户端的单例 —— 长超时配置，复用连接池。
     * connectTimeout=30s, readTimeout=120s（流式推送可能间隔较长）。
     */
    private val streamingClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 向 LLM API 发送简单的纯文本聊天请求（不带工具）。
     *
     * 用于只需要纯文本回复的场景（如生成提醒文案、问候语），
     * 不附带 tools 参数，节省 token 开销。
     *
     * @param provider Provider 配置
     * @param apiKey   API 密钥
     * @param model    模型名称
     * @param messages 消息列表
     * @return 成功时返回文本内容，失败时返回异常
     */
    suspend fun chatSimple(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val text = when (provider.apiType) {
                ApiType.OPENAI_COMPATIBLE -> chatOpenAISimple(provider.baseUrl, apiKey, model, messages)
                ApiType.ANTHROPIC -> chatAnthropicSimple(provider.baseUrl, apiKey, model, messages)
            }
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("简单聊天请求异常: ${e.message}", e))
        }
    }

    /**
     * OpenAI 格式的简单聊天（不带工具）。
     */
    private fun chatOpenAISimple(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
    ): String {
        val url = URL("$baseUrl/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                for (msg in messages) put(msg)
            })
        }

        connection.outputStream.use { os ->
            os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            throw Exception("API 请求失败 (HTTP $responseCode): $errorBody")
        }

        val responseText = connection.inputStream.bufferedReader()?.readText()
            ?: throw Exception("响应体为空")
        val json = JSONObject(responseText)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content", "")
    }

    /**
     * Anthropic 格式的简单聊天（不带工具）。
     */
    private fun chatAnthropicSimple(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
    ): String {
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

        val systemPrompt = messages
            .firstOrNull { it.optString("role") == "system" }
            ?.optString("content", "") ?: ""
        val conversationMessages = JSONArray().apply {
            for (msg in messages) {
                if (msg.optString("role") != "system") put(msg)
            }
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            if (systemPrompt.isNotEmpty()) put("system", systemPrompt)
            put("messages", conversationMessages)
        }

        connection.outputStream.use { os ->
            os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            throw Exception("API 请求失败 (HTTP $responseCode): $errorBody")
        }

        val responseText = connection.inputStream.bufferedReader()?.readText()
            ?: throw Exception("响应体为空")
        val json = JSONObject(responseText)
        val contentArr = json.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.getJSONObject(i)
            if (block.optString("type") == "text") {
                sb.append(block.optString("text", ""))
            }
        }
        return sb.toString()
    }

    /**
     * 向 LLM API 发送带 Tool Calling 支持的聊天请求（非流式）。
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
     * 向 LLM API 发送流式（SSE）聊天请求。
     *
     * 与 [chatWithTools] 的关键区别：
     * - 请求体中附带 `"stream": true`，启用服务端 SSE 推送
     * - 通过 [onTextDelta] 回调实时传递文本增量，供 UI 逐字显示
     * - 工具调用增量会被累积，流结束后统一组装为 [ChatResponse.toolCalls]
     * - 返回的 [ChatResponse] 包含完整文本 + 工具调用列表
     *
     * @param provider    Provider 配置
     * @param apiKey      API 密钥
     * @param model       模型名称
     * @param messages    消息列表
     * @param onTextDelta 每次收到文本增量时的回调（运行在 IO 线程，调用方自行切换线程）
     * @return 流式完成后的完整 [ChatResponse]
     */
    suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
        onTextDelta: (String) -> Unit,
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            when (provider.apiType) {
                ApiType.OPENAI_COMPATIBLE -> chatStreamOpenAI(provider.baseUrl, apiKey, model, messages, onTextDelta)
                ApiType.ANTHROPIC -> chatStreamAnthropic(provider.baseUrl, apiKey, model, messages, onTextDelta)
            }
        } catch (e: Exception) {
            Result.failure(Exception("流式网络请求异常: ${e.message}", e))
        }
    }

    /**
     * OpenAI 兼容 API 的 SSE 流式实现。
     *
     * SSE 格式示例：
     * ```
     * data: {"choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}
     * data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}
     * data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
     * data: [DONE]
     * ```
     *
     * 工具调用在流式中的增量格式：
     * ```
     * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_xxx","type":"function","function":{"name":"query","arguments":""}}]}}]}
     * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"enti"}}]}}]}
     * data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ty_type\":\"task\"}"}}]}}]}
     * ```
     *
     * 工具调用的 id/name 出现在第一个 chunk 中，arguments 分散在后续 chunk 中。
     * 本方法使用 [ToolCallAccumulator] 按 index 累积每个工具调用的完整参数。
     */
    private fun chatStreamOpenAI(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
        onTextDelta: (String) -> Unit,
    ): Result<ChatResponse> {
        val url = "$baseUrl/chat/completions"

        // 构建请求体：stream=true + tools schema
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                for (msg in messages) put(msg)
            })
            put("tools", ToolRegistry.toOpenAITools())
            put("stream", true) // 启用 SSE 流式输出
        }

        val bodyStr = requestBody.toString()
        Log.d("ChatService", "OpenAI stream request: ${bodyStr.take(500)}")

        // 使用 OkHttp 发送请求（支持流式响应读取）
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()

        val response = streamingClient.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "未知错误"
            response.close()
            Log.e("ChatService", "OpenAI stream 失败 (HTTP ${response.code}): $errorBody")
            return Result.failure(Exception("API 请求失败 (HTTP ${response.code}): $errorBody"))
        }

        // ── SSE 流式读取 ──
        val textContent = StringBuilder() // 累积的完整文本
        val reasoningContent = StringBuilder() // 累积的 reasoning_content（DeepSeek R1 等推理模型）
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>() // 按 index 累积工具调用

        response.body?.source()?.let { source ->
            try {
                while (!source.exhausted()) {
                    // 检查线程是否被中断（协程取消时会中断 IO 线程）
                    if (Thread.interrupted()) break
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue // 跳过非 data 行
                    val data = line.removePrefix("data: ").trim()

                    if (data == "[DONE]") break // SSE 流结束标志

                    try {
                        val chunk = JSONObject(data)
                        val choices = chunk.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                        // 处理文本增量（delta.content 可能为 JSON null，optString 会转为字符串 "null"）
                        val contentDelta = delta.optString("content", "")
                        if (contentDelta.isNotEmpty() && !delta.isNull("content")) {
                            textContent.append(contentDelta)
                            onTextDelta(contentDelta) // 回调给 ViewModel 刷新 UI
                        }

                        // 处理推理内容增量（DeepSeek R1 等模型的 reasoning_content）
                        // 此字段必须原样回传到后续请求的 assistant 消息中
                        val reasoningDelta = delta.optString("reasoning_content", "")
                        if (reasoningDelta.isNotEmpty() && !delta.isNull("reasoning_content")) {
                            reasoningContent.append(reasoningDelta)
                        }

                        // 处理工具调用增量
                        val toolCallsDelta = delta.optJSONArray("tool_calls")
                        if (toolCallsDelta != null) {
                            for (j in 0 until toolCallsDelta.length()) {
                                val tcDelta = toolCallsDelta.getJSONObject(j)
                                val tcIndex = tcDelta.optInt("index", j)
                                val acc = toolCallAccumulators.getOrPut(tcIndex) {
                                    ToolCallAccumulator()
                                }
                                // 第一个 chunk 包含 id 和 function.name
                                if (tcDelta.has("id")) {
                                    acc.id = tcDelta.getString("id")
                                }
                                val function = tcDelta.optJSONObject("function")
                                if (function != null) {
                                    if (function.has("name")) {
                                        acc.name = function.getString("name")
                                    }
                                    if (function.has("arguments")) {
                                        acc.arguments.append(function.getString("arguments"))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // 某些 chunk 的 JSON 可能不完整（如仅含 usage），跳过即可
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatService", "OpenAI SSE 读取异常: ${e.message}")
                throw e
            } finally {
                response.close()
            }
        } ?: run {
            response.close()
            return Result.failure(Exception("响应体为空"))
        }

        // ── 组装最终响应 ──
        val text = textContent.toString().takeIf { it.isNotEmpty() }
        val reasoning = reasoningContent.toString().takeIf { it.isNotEmpty() }
        val toolCalls = toolCallAccumulators.values
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            .map { ToolCall(id = it.id, name = it.name, arguments = it.arguments.toString()) }
            .takeIf { it.isNotEmpty() }

        return Result.success(ChatResponse(text = text, toolCalls = toolCalls, reasoningContent = reasoning))
    }

    /**
     * Anthropic Messages API 的 SSE 流式实现。
     *
     * Anthropic SSE 使用 `event:` 字段区分事件类型：
     * ```
     * event: message_start
     * data: {"type":"message_start","message":{...}}
     *
     * event: content_block_start
     * data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
     *
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}
     *
     * event: content_block_stop
     * data: {"type":"content_block_stop","index":0}
     *
     * event: message_delta
     * data: {"type":"message_delta","usage":{...}}
     *
     * event: message_stop
     * data: {"type":"message_stop"}
     * ```
     *
     * 工具使用事件：
     * ```
     * event: content_block_start
     * data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_xxx","name":"get_tasks","input":{}}}
     *
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"checklist_id\":\"abc\"}"}}
     *
     * event: content_block_stop
     * data: {"type":"content_block_stop","index":1}
     * ```
     */
    private fun chatStreamAnthropic(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
        onTextDelta: (String) -> Unit,
    ): Result<ChatResponse> {
        val url = "$baseUrl/messages"

        // 分离 system 提示词和对话消息（Anthropic 特有格式）
        val systemPrompt = messages
            .firstOrNull { it.optString("role") == "system" }
            ?.optString("content", "") ?: ""
        val conversationMessages = JSONArray().apply {
            for (msg in messages) {
                val role = msg.optString("role", "")
                if (role != "system") put(msg)
            }
        }

        // 构建请求体：stream=true + max_tokens=4096 + tools schema
        val requestBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            if (systemPrompt.isNotEmpty()) put("system", systemPrompt)
            put("messages", conversationMessages)
            put("tools", ToolRegistry.toAnthropicTools())
            put("stream", true) // 启用 SSE 流式输出
        }

        val bodyStr = requestBody.toString()
        Log.d("ChatService", "Anthropic stream request: ${bodyStr.take(500)}")

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()

        val response = streamingClient.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "未知错误"
            response.close()
            Log.e("ChatService", "Anthropic stream 失败 (HTTP ${response.code}): $errorBody")
            return Result.failure(Exception("API 请求失败 (HTTP ${response.code}): $errorBody"))
        }

        // ── SSE 流式读取 ──
        val textBlocks = mutableMapOf<Int, StringBuilder>() // 按 index 累积文本块
        val toolUseBlocks = mutableMapOf<Int, ToolCallAccumulator>() // 按 index 累积工具调用
        var currentEventType = ""

        response.body?.source()?.let { source ->
            try {
                while (!source.exhausted()) {
                    // 检查线程是否被中断（协程取消时会中断 IO 线程）
                    if (Thread.interrupted()) break
                    val line = source.readUtf8Line() ?: break

                    when {
                        // 记录当前事件类型
                        line.startsWith("event: ") -> {
                            currentEventType = line.removePrefix("event: ").trim()
                        }
                        // data 行包含 JSON payload
                        line.startsWith("data: ") -> {
                            val data = line.removePrefix("data: ").trim()
                            try {
                                val event = JSONObject(data)
                                val eventType = event.optString("type", currentEventType)

                                when (eventType) {
                                    "content_block_start" -> {
                                        val contentBlock = event.optJSONObject("content_block")
                                        if (contentBlock != null) {
                                            val blockType = contentBlock.optString("type")
                                            val idx = event.optInt("index", 0)
                                            when (blockType) {
                                                "text" -> {
                                                    textBlocks[idx] = StringBuilder()
                                                }
                                                "tool_use" -> {
                                                    toolUseBlocks[idx] = ToolCallAccumulator(
                                                        id = contentBlock.optString("id", ""),
                                                        name = contentBlock.optString("name", ""),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val delta = event.optJSONObject("delta")
                                        val idx = event.optInt("index", 0)
                                        if (delta != null) {
                                            when (delta.optString("type")) {
                                                "text_delta" -> {
                                                    val text = delta.optString("text", "")
                                                    if (text.isNotEmpty() && !delta.isNull("text")) {
                                                        textBlocks.getOrPut(idx) { StringBuilder() }
                                                            .append(text)
                                                        onTextDelta(text) // 回调给 ViewModel
                                                    }
                                                }
                                                "input_json_delta" -> {
                                                    val partialJson = delta.optString("partial_json", "")
                                                    if (partialJson.isNotEmpty()) {
                                                        toolUseBlocks.getOrPut(idx) {
                                                            ToolCallAccumulator()
                                                        }.arguments.append(partialJson)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "message_stop" -> {
                                        // 流结束标志，跳出循环
                                    }
                                }

                                if (eventType == "message_stop") break
                            } catch (_: Exception) {
                                // 忽略无法解析的 JSON 行
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatService", "Anthropic SSE 读取异常: ${e.message}")
                throw e
            } finally {
                response.close()
            }
        } ?: run {
            response.close()
            return Result.failure(Exception("响应体为空"))
        }

        // ── 组装最终响应 ──
        val text = textBlocks.values
            .map { it.toString() }
            .joinToString("")
            .takeIf { it.isNotEmpty() }

        val toolCalls = toolUseBlocks.values
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            .map { ToolCall(id = it.id, name = it.name, arguments = it.arguments.toString()) }
            .takeIf { it.isNotEmpty() }

        return Result.success(ChatResponse(text = text, toolCalls = toolCalls))
    }

    /**
     * 流式工具调用的增量累加器。
     *
     * 流式 SSE 中工具调用的 id/name 出现在第一帧，
     * arguments 分散在多帧中逐步传输，需要累加。
     */
    private class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

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