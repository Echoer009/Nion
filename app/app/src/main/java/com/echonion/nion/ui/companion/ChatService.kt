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
     * Per-route 缓存的 OpenAI tools JSON 字符串。
     * key = 工具名称列表的逗号连接，value = 规范化序列化后的稳定 JSON。
     * 每种工具组合（即每个路由）只构建一次，后续直接从缓存取，命中 API 前缀缓存。
     */
    private val cachedOpenAIToolsJson: MutableMap<String, String> = mutableMapOf()

    /**
     * Per-route 缓存的 Anthropic tools JSON 字符串。
     */
    private val cachedAnthropicToolsJson: MutableMap<String, String> = mutableMapOf()

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
     * 获取指定路由的 OpenAI tools JSON 缓存。
     * 从 ToolRegistry 获取对应路由的工具集后缓存，保证同一路由的 tools 序列化完全一致。
     */
    private fun getOpenAIToolsJson(route: String?): String {
        val tools = ToolRegistry.toolsForRoute(route)
        val key = tools.joinToString(",") { it.name }
        return cachedOpenAIToolsJson.getOrPut(key) {
            JsonCanonicalizer.canonicalize(ToolRegistry.toOpenAITools(route))
        }
    }

    /**
     * 获取指定路由的 Anthropic tools JSON 缓存。
     */
    private fun getAnthropicToolsJson(route: String?): String {
        val tools = ToolRegistry.toolsForRoute(route)
        val key = tools.joinToString(",") { it.name }
        return cachedAnthropicToolsJson.getOrPut(key) {
            JsonCanonicalizer.canonicalize(ToolRegistry.toAnthropicTools(route))
        }
    }

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
            // 规范化序列化，保证请求体 key 顺序一致
            os.write(JsonCanonicalizer.canonicalize(requestBody).toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            Log.e("ChatService", "OpenAI simple 失败 (HTTP $responseCode): $errorBody")
            throw Exception("API 请求失败 (HTTP $responseCode): $errorBody")
        }

        val responseText = connection.inputStream.bufferedReader().readText()
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

        // 收集所有 system 消息并拼接（不丢弃末尾的时间等 system 信息）
        val systemParts = messages.filter { it.optString("role") == "system" }
            .map { it.optString("content", "") }
        val systemPrompt = systemParts.joinToString("\n\n")
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
            // 规范化序列化，保证请求体 key 顺序一致
            os.write(JsonCanonicalizer.canonicalize(requestBody).toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            Log.e("ChatService", "Anthropic simple 失败 (HTTP $responseCode): $errorBody")
            throw Exception("API 请求失败 (HTTP $responseCode): $errorBody")
        }

        val responseText = connection.inputStream.bufferedReader().readText()
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
     * @param currentRoute 当前导航路由，用于注入路由相关工具上下文，null 表示无路由
     * @return 成功时返回 [ChatResponse]，失败时返回异常
     */
    suspend fun chatWithTools(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
        currentRoute: String? = null,
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            when (provider.apiType) {
                ApiType.OPENAI_COMPATIBLE -> chatOpenAIWithTools(provider.baseUrl, apiKey, model, messages, currentRoute)
                ApiType.ANTHROPIC -> chatAnthropicWithTools(provider.baseUrl, apiKey, model, messages, currentRoute)
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
     * @param currentRoute 当前导航路由，决定注入哪些工具（null=基础工具）
     * @param onTextDelta 每次收到文本增量时的回调（运行在 IO 线程，调用方自行切换线程）
     * @return 流式完成后的完整 [ChatResponse]
     */
    suspend fun chatStream(
        provider: ProviderConfig,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
        currentRoute: String? = null,
        onTextDelta: (String) -> Unit,
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            when (provider.apiType) {
                ApiType.OPENAI_COMPATIBLE -> chatStreamOpenAI(provider.baseUrl, apiKey, model, messages, currentRoute, onTextDelta)
                ApiType.ANTHROPIC -> chatStreamAnthropic(provider.baseUrl, apiKey, model, messages, currentRoute, onTextDelta)
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
        currentRoute: String? = null,
        onTextDelta: (String) -> Unit,
    ): Result<ChatResponse> {
        val url = "$baseUrl/chat/completions"

        // 构建请求体：使用稳定序列化保证 key 顺序一致
        val messagesArray = JSONArray().apply {
            for (msg in messages) put(msg)
        }
        val bodyStr = buildStableOpenAIBody(model, messagesArray, getOpenAIToolsJson(currentRoute), stream = true)

        Log.d("ChatService", "OpenAI stream request: ${bodyStr.take(500)}")

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()

        // 执行流式请求，统一处理 HTTP 层错误
        val response = executeStreamRequest(httpRequest)
            .getOrElse { return Result.failure(it) }

        // SSE 流式读取的累积状态
        val textContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        var cacheHitTokens = 0
        var cacheMissTokens = 0

        // 读取 SSE 流，逐行处理 OpenAI 格式的 data 帧
        if (!readSseLines(response) { line ->
            // 跳过非 data 行
            if (!line.startsWith("data: ")) return@readSseLines false
            val data = line.removePrefix("data: ").trim()
            // SSE 流结束标志
            if (data == "[DONE]") return@readSseLines true
            // 解析并处理单个 SSE 数据块
            processOpenAISseChunk(data, textContent, reasoningContent, toolCallAccumulators, onTextDelta) { hit, miss ->
                cacheHitTokens = hit
                cacheMissTokens = miss
            }
            false
        }) {
            return Result.failure(Exception("响应体为空"))
        }

        // 组装最终响应
        return Result.success(ChatResponse(
            text = textContent.toString().takeIf { it.isNotEmpty() },
            toolCalls = accumulatorsToToolCalls(toolCallAccumulators),
            reasoningContent = reasoningContent.toString().takeIf { it.isNotEmpty() },
            cacheHitTokens = cacheHitTokens,
            cacheMissTokens = cacheMissTokens,
        ))
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
        currentRoute: String? = null,
        onTextDelta: (String) -> Unit,
    ): Result<ChatResponse> {
        val url = "$baseUrl/messages"

        // 分离 system 提示词和对话消息（Anthropic 特有格式）
        // 收集所有 system 消息，拼接为完整 system 字符串（包含末尾的时间信息）
        val systemParts = messages.filter { it.optString("role") == "system" }
            .map { it.optString("content", "") }
        val systemPrompt = systemParts.joinToString("\n\n")
        val conversationMessages = JSONArray().apply {
            for (msg in messages) {
                val role = msg.optString("role", "")
                if (role != "system") put(msg)
            }
        }

        // 使用稳定序列化构建请求体，添加 cache_control 启用 Prompt Caching
        val bodyStr = buildStableAnthropicBody(
            model = model,
            systemPrompt = systemPrompt,
            messages = conversationMessages,
            toolsJson = getAnthropicToolsJson(currentRoute),
            stream = true,
            enableCacheControl = true,
        )

        Log.d("ChatService", "Anthropic stream request: ${bodyStr.take(500)}")

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            // 启用 Anthropic Prompt Caching，减少重复前缀的 token 计费
            .addHeader("anthropic-beta", "prompt-caching-2024-07-31")
            .post(bodyStr.toRequestBody("application/json".toMediaType()))
            .build()

        // 执行流式请求，统一处理 HTTP 层错误
        val response = executeStreamRequest(httpRequest)
            .getOrElse { return Result.failure(it) }

        // SSE 流式读取的累积状态
        val textBlocks = mutableMapOf<Int, StringBuilder>()
        val toolUseBlocks = mutableMapOf<Int, ToolCallAccumulator>()
        var currentEventType = ""
        var cacheHitTokens = 0
        var cacheMissTokens = 0

        // 读取 SSE 流，处理 Anthropic 的 event/data 行
        if (!readSseLines(response) { line ->
            when {
                // 记录当前事件类型，供后续 data 行使用
                line.startsWith("event: ") -> {
                    currentEventType = line.removePrefix("event: ").trim()
                    false
                }
                // data 行包含 JSON payload，交由事件处理器解析
                line.startsWith("data: ") -> {
                    val data = line.removePrefix("data: ").trim()
                    processAnthropicSseData(data, currentEventType, textBlocks, toolUseBlocks, onTextDelta) { hit, miss ->
                        cacheHitTokens = hit
                        cacheMissTokens = miss
                    }
                }
                else -> false
            }
        }) {
            return Result.failure(Exception("响应体为空"))
        }

        // 组装最终响应
        val text = textBlocks.values
            .map { it.toString() }
            .joinToString("")
            .takeIf { it.isNotEmpty() }

        return Result.success(ChatResponse(
            text = text,
            toolCalls = accumulatorsToToolCalls(toolUseBlocks),
            cacheHitTokens = cacheHitTokens,
            cacheMissTokens = cacheMissTokens,
        ))
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

    // ═══════════════════════════════════════════════════════════════════
    // SSE 流式辅助方法 —— 降低 chatStreamOpenAI / chatStreamAnthropic 的复杂度
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 执行流式 HTTP 请求并统一处理 HTTP 层错误。
     *
     * 封装 OkHttp 请求执行、响应码检查和错误响应体读取。
     * 成功时返回 OkHttp Response（调用方负责关闭），失败时返回异常。
     *
     * @param httpRequest 已构建好的 OkHttp Request
     * @return 成功返回 Response，失败返回 Exception
     */
    private fun executeStreamRequest(httpRequest: Request): Result<okhttp3.Response> {
        val response = streamingClient.newCall(httpRequest).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "未知错误"
            response.close()
            Log.e("ChatService", "Stream 请求失败 (HTTP ${response.code}): $errorBody")
            return Result.failure(Exception("API 请求失败 (HTTP ${response.code}): $errorBody"))
        }
        return Result.success(response)
    }

    /**
     * 从 OkHttp Response 中逐行读取 SSE 数据。
     *
     * 统一封装 SSE 流的读取循环、线程中断检测和响应关闭逻辑。
     * 对每一行原始内容调用 [lineProcessor]，处理器返回 true 时停止读取（如遇到 [DONE] 或 message_stop）。
     *
     * @param response OkHttp 响应对象，读取完毕或异常后自动关闭
     * @param lineProcessor 行处理器，接收原始行内容（含 "data: " / "event: " 前缀），返回 true 表示应停止读取
     * @return true 表示成功读取完成，false 表示响应体为空
     */
    private fun readSseLines(
        response: okhttp3.Response,
        lineProcessor: (String) -> Boolean,
    ): Boolean {
        response.body?.source()?.let { source ->
            try {
                while (!source.exhausted()) {
                    // 检查线程是否被中断（协程取消时会中断 IO 线程）
                    if (Thread.interrupted()) break
                    val line = source.readUtf8Line() ?: break
                    // 处理器返回 true 表示应停止读取（如遇到 [DONE] 或 message_stop）
                    if (lineProcessor(line)) break
                }
            } catch (e: Exception) {
                Log.e("ChatService", "SSE 读取异常: ${e.message}")
                throw e
            } finally {
                response.close()
            }
            return true
        } ?: run {
            response.close()
            return false
        }
    }

    /**
     * 处理单个 OpenAI SSE 数据块。
     *
     * 从 SSE `data:` 行的 JSON 字符串中提取四类信息：
     * 1. **文本增量**（delta.content）→ 追加到 [textContent] 并通过 [onTextDelta] 回调
     * 2. **推理内容增量**（delta.reasoning_content，DeepSeek R1 等模型）→ 追加到 [reasoningContent]
     * 3. **工具调用增量**（delta.tool_calls）→ 按 index 累积到 [toolCallAccumulators]
     * 4. **缓存统计**（usage 字段，DeepSeek Prefix Caching）→ 通过 [onUsage] 回调
     *
     * @param data SSE data 行的 JSON 字符串
     * @param textContent 累积文本内容的 StringBuilder
     * @param reasoningContent 累积推理内容的 StringBuilder
     * @param toolCallAccumulators 按 index 累积工具调用的映射
     * @param onTextDelta 文本增量回调，触发时传递增量文本片段
     * @param onUsage 缓存统计回调，触发时传递 (hitTokens, missTokens)
     */
    private fun processOpenAISseChunk(
        data: String,
        textContent: StringBuilder,
        reasoningContent: StringBuilder,
        toolCallAccumulators: MutableMap<Int, ToolCallAccumulator>,
        onTextDelta: (String) -> Unit,
        onUsage: (hit: Int, miss: Int) -> Unit,
    ) {
        try {
            val chunk = JSONObject(data)

            // 提取 usage 字段（DeepSeek 在流式最后一个 chunk 中返回，含缓存命中数据）
            // usage 格式：{"prompt_tokens":N,"completion_tokens":N,"prompt_cache_hit_tokens":N,"prompt_cache_miss_tokens":N}
            val usage = chunk.optJSONObject("usage")
            if (usage != null) {
                onUsage(
                    usage.optInt("prompt_cache_hit_tokens", 0),
                    usage.optInt("prompt_cache_miss_tokens", 0),
                )
            }

            val choices = chunk.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return

            // 处理文本增量（delta.content 可能为 JSON null，optString 会转为字符串 "null"）
            val contentDelta = delta.optString("content", "")
            if (contentDelta.isNotEmpty() && !delta.isNull("content")) {
                textContent.append(contentDelta)
                onTextDelta(contentDelta)
            }

            // 处理推理内容增量（DeepSeek R1 等模型的 reasoning_content）
            // 此字段必须原样回传到后续请求的 assistant 消息中
            val reasoningDelta = delta.optString("reasoning_content", "")
            if (reasoningDelta.isNotEmpty() && !delta.isNull("reasoning_content")) {
                reasoningContent.append(reasoningDelta)
            }

            // 处理工具调用增量：按 index 累积 id/name/arguments
            accumulateOpenAIToolCalls(delta, toolCallAccumulators)
        } catch (_: Exception) {
            // 某些 chunk 的 JSON 可能不完整（如仅含 usage），跳过即可
        }
    }

    /**
     * 累积 OpenAI 格式的工具调用增量。
     *
     * OpenAI 流式中工具调用的 id/name 出现在第一个 chunk，
     * arguments 分散在后续 chunk 中逐步传输，需要按 index 累积。
     *
     * @param delta SSE chunk 中的 delta JSON 对象
     * @param toolCallAccumulators 按 index 累积的累加器映射
     */
    private fun accumulateOpenAIToolCalls(
        delta: JSONObject,
        toolCallAccumulators: MutableMap<Int, ToolCallAccumulator>,
    ) {
        val toolCallsDelta = delta.optJSONArray("tool_calls") ?: return
        for (j in 0 until toolCallsDelta.length()) {
            val tcDelta = toolCallsDelta.getJSONObject(j)
            val tcIndex = tcDelta.optInt("index", j)
            val acc = toolCallAccumulators.getOrPut(tcIndex) { ToolCallAccumulator() }
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

    /**
     * 处理单个 Anthropic SSE data 事件。
     *
     * 根据事件类型（content_block_start / content_block_delta / message_delta / message_stop）
     * 分别处理内容块初始化、文本/工具调用增量累积和缓存统计提取。
     *
     * @param data SSE data 行的 JSON 字符串
     * @param eventType 当前事件类型（由 "event:" 行设定）
     * @param textBlocks 按 index 累积文本块的映射
     * @param toolUseBlocks 按 index 累积工具调用的映射
     * @param onTextDelta 文本增量回调，触发时传递增量文本片段
     * @param onUsage 缓存统计回调，触发时传递 (hitTokens, missTokens)
     * @return true 表示收到 message_stop 应停止读取，false 表示继续
     */
    private fun processAnthropicSseData(
        data: String,
        eventType: String,
        textBlocks: MutableMap<Int, StringBuilder>,
        toolUseBlocks: MutableMap<Int, ToolCallAccumulator>,
        onTextDelta: (String) -> Unit,
        onUsage: (hit: Int, miss: Int) -> Unit,
    ): Boolean {
        try {
            val event = JSONObject(data)
            val type = event.optString("type", eventType)

            when (type) {
                "content_block_start" -> {
                    // 初始化文本块或工具调用块的累加器
                    val contentBlock = event.optJSONObject("content_block") ?: return false
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
                "content_block_delta" -> {
                    // 处理文本增量或工具参数增量
                    val delta = event.optJSONObject("delta")
                    val idx = event.optInt("index", 0)
                    if (delta != null) {
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty() && !delta.isNull("text")) {
                                    textBlocks.getOrPut(idx) { StringBuilder() }.append(text)
                                    onTextDelta(text)
                                }
                            }
                            "input_json_delta" -> {
                                val partialJson = delta.optString("partial_json", "")
                                if (partialJson.isNotEmpty()) {
                                    toolUseBlocks.getOrPut(idx) { ToolCallAccumulator() }
                                        .arguments.append(partialJson)
                                }
                            }
                        }
                    }
                }
                "message_delta" -> {
                    // message_delta 事件包含 usage 信息（Anthropic 缓存命中统计）
                    // {"type":"message_delta","usage":{"cache_read_input_tokens":N,"cache_creation_input_tokens":N}}
                    val usage = event.optJSONObject("usage")
                    if (usage != null) {
                        onUsage(
                            usage.optInt("cache_read_input_tokens", 0),
                            usage.optInt("cache_creation_input_tokens", 0),
                        )
                    }
                }
                "message_stop" -> return true
            }
        } catch (_: Exception) {
            // 忽略无法解析的 JSON 行
        }
        return false
    }

    /**
     * 将工具调用累加器映射转换为 [ToolCall] 列表。
     *
     * 过滤掉不完整的累加器（id 或 name 为空），仅保留有效调用。
     * OpenAI 和 Anthropic 的流式方法共享此转换逻辑。
     *
     * @param accumulators 按 index 累积的 [ToolCallAccumulator] 映射
     * @return 有效的 [ToolCall] 列表，或 null（无工具调用时）
     */
    private fun accumulatorsToToolCalls(
        accumulators: Map<Int, ToolCallAccumulator>,
    ): List<ToolCall>? {
        return accumulators.values
            .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            .map { ToolCall(id = it.id, name = it.name, arguments = it.arguments.toString()) }
            .takeIf { it.isNotEmpty() }
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
        currentRoute: String? = null,
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

        // 使用稳定序列化构建请求体，保证 tools 部分跨请求完全一致
        val messagesArray = JSONArray().apply {
            for (msg in messages) put(msg)
        }
        val bodyStr = buildStableOpenAIBody(model, messagesArray, getOpenAIToolsJson(currentRoute), stream = false)

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

        val responseText = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)
        val message = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")

        // 提取 usage 中的缓存命中数据（DeepSeek 非流式响应）
        val usage = json.optJSONObject("usage")
        val cacheHit = usage?.optInt("prompt_cache_hit_tokens", 0) ?: 0
        val cacheMiss = usage?.optInt("prompt_cache_miss_tokens", 0) ?: 0

        return Result.success(parseOpenAIResponse(message).copy(
            cacheHitTokens = cacheHit,
            cacheMissTokens = cacheMiss,
        ))
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
        val text = message.optString("content", "").takeIf { it.isNotEmpty() && it != "null" }

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
        } else {
            null
        }

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
        currentRoute: String? = null,
    ): Result<ChatResponse> {
        val url = URL("$baseUrl/messages")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            // 启用 Anthropic Prompt Caching，减少重复前缀的 token 计费
            setRequestProperty("anthropic-beta", "prompt-caching-2024-07-31")
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }

        // 分离 system 提示词和对话消息
        // 收集所有 system 消息并拼接，避免丢失末尾的时间信息
        val systemParts = messages.filter { it.optString("role") == "system" }
            .map { it.optString("content", "") }
        val systemPrompt = systemParts.joinToString("\n\n")
        val conversationMessages = JSONArray().apply {
            for (msg in messages) {
                val role = msg.optString("role", "")
                if (role != "system") {
                    put(msg)
                }
            }
        }

        // 使用稳定序列化构建请求体，添加 cache_control 启用 Prompt Caching
        val bodyStr = buildStableAnthropicBody(
            model = model,
            systemPrompt = systemPrompt,
            messages = conversationMessages,
            toolsJson = getAnthropicToolsJson(currentRoute),
            stream = false,
            enableCacheControl = true,
        )

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

        val responseText = connection.inputStream.bufferedReader().readText()
        val json = JSONObject(responseText)

        // Anthropic 缓存命中统计在顶层 usage 字段
        val usage = json.optJSONObject("usage")
        val cacheHit = usage?.optInt("cache_read_input_tokens", 0) ?: 0
        val cacheMiss = usage?.optInt("cache_creation_input_tokens", 0) ?: 0

        return Result.success(parseAnthropicResponse(json).copy(
            cacheHitTokens = cacheHit,
            cacheMissTokens = cacheMiss,
        ))
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

    // ═══════════════════════════════════════════════════════════════════
    // 稳定 JSON 序列化 —— 保证请求体 key 顺序一致，命中 API 缓存
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 构建稳定的 OpenAI 格式请求体 JSON 字符串。
     *
     * 解决 org.json.JSONObject.toString() key 顺序不可预测导致 API Prefix Caching 失效的问题。
     * 通过手动拼接 JSON 字符串保证固定 key 顺序：model → tools → messages → stream。
     *
     * **tools 必须在 messages 前面**：tools 是稳定不变的（~2500 tokens），
     * messages 随对话增长而变化。DeepSeek Prefix Caching 从 byte 0 匹配前缀，
     * 把稳定的 tools 放前面，即使 messages 变化，tools 部分也始终命中缓存。
     * 如果 tools 在 messages 后面，messages 一变化就会在 tools 之前断开前缀，
     * 导致 tools 永远无法被缓存。
     *
     * @param model 模型名称
     * @param messages 消息 JSONArray
     * @param toolsJson 预序列化的 tools JSON 字符串（已缓存，保证稳定）
     * @param stream 是否启用流式输出
     * @return 稳定的 JSON 请求体字符串
     */
    private fun buildStableOpenAIBody(
        model: String,
        messages: JSONArray,
        toolsJson: String,
        stream: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"model\":")
        sb.append(JSONObject.quote(model))
        // tools 放在 messages 前面：tools 稳定不变（~2500 tokens），放在前缀中始终命中缓存
        sb.append(",\"tools\":")
        sb.append(toolsJson)
        sb.append(",\"messages\":")
        // 使用规范化序列化替代 messages.toString()，
        // 保证每条消息的 key（role/content/tool_calls 等）按固定顺序输出
        sb.append(JsonCanonicalizer.canonicalize(messages))
        if (stream) {
            sb.append(",\"stream\":true")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 构建稳定的 Anthropic 格式请求体 JSON 字符串。
     *
     * key 顺序：model → max_tokens → system → tools → messages → stream。
     * tools 在 messages 前面，保证稳定的工具定义在前缀中始终被缓存（同 OpenAI 版本的优化逻辑）。
     *
     * @param model 模型名称
     * @param systemPrompt 系统提示词
     * @param messages 对话消息 JSONArray
     * @param toolsJson 预序列化的 tools JSON 字符串（已缓存）
     * @param stream 是否启用流式输出
     * @param enableCacheControl 是否为 system 和 tools 添加 cache_control 标记（启用 Anthropic Prompt Caching）
     * @return 稳定的 JSON 请求体字符串
     */
    private fun buildStableAnthropicBody(
        model: String,
        systemPrompt: String,
        messages: JSONArray,
        toolsJson: String,
        stream: Boolean,
        enableCacheControl: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.append("{\"model\":")
        sb.append(JSONObject.quote(model))
        sb.append(",\"max_tokens\":4096")

        // Anthropic Prompt Caching：system 以数组形式传入，附带 cache_control 标记
        // 非缓存模式：system 直接作为字符串传入
        if (systemPrompt.isNotEmpty()) {
            if (enableCacheControl) {
                // 缓存模式：system 为数组 [{type:"text",text:"...",cache_control:{type:"ephemeral"}}]
                sb.append(",\"system\":[{\"type\":\"text\",\"text\":")
                sb.append(JSONObject.quote(systemPrompt))
                sb.append(",\"cache_control\":{\"type\":\"ephemeral\"}}]")
            } else {
                sb.append(",\"system\":")
                sb.append(JSONObject.quote(systemPrompt))
            }
        }

        // tools 放在 messages 前面：tools 稳定不变，放在前缀中始终命中缓存
        if (enableCacheControl) {
            sb.append(",\"tools\":")
            sb.append(addCacheControlToToolsJson(toolsJson))
        } else {
            sb.append(",\"tools\":")
            sb.append(toolsJson)
        }

        sb.append(",\"messages\":")
        // 使用规范化序列化保证消息 key 顺序一致，命中 API 缓存
        sb.append(JsonCanonicalizer.canonicalize(messages))

        if (stream) {
            sb.append(",\"stream\":true")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 为 Anthropic tools JSON 数组中的每个工具对象追加 cache_control 标记。
     *
     * Anthropic Prompt Caching 要求在需要缓存的工具定义中添加：
     * ```json
     * {"name":"...", "description":"...", "input_schema":{...}, "cache_control":{"type":"ephemeral"}}
     * ```
     *
     * 注意：只在最后一个工具上加 cache_control 即可让整个 tools 前缀被缓存，
     * 但在所有工具上都加也没有副作用，且更稳定。
     *
     * @param toolsJson 原始 tools JSON 数组字符串
     * @return 追加了 cache_control 的 tools JSON 数组字符串
     */
    private fun addCacheControlToToolsJson(toolsJson: String): String {
        // 解析原始 tools 数组，为每个元素追加 cache_control
        val toolsArray = JSONArray(toolsJson)
        val result = JSONArray()
        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.getJSONObject(i)
            tool.put("cache_control", JSONObject().apply {
                put("type", "ephemeral")
            })
            result.put(tool)
        }
        // 规范化序列化，保证 cache_control 字段的 key 顺序稳定
        return JsonCanonicalizer.canonicalize(result)
    }
}