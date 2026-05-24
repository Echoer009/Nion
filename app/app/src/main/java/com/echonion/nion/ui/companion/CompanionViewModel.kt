package com.echonion.nion.ui.companion

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.ui.companion.tools.ToolExecutor
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 已保存的 Provider 配置 —— 用户可保存多组 API 配置，在它们之间自由切换。
 *
 * @param id 唯一标识
 * @param provider 内置 Provider 名称或自定义名称
 * @param apiKey API 密钥
 * @param model 模型名称
 * @param baseUrl 自定义 baseUrl（仅"自定义"provider 需要）
 * @param apiType API 类型名
 */
data class SavedConfig(
    val id: String,
    val provider: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val apiType: String,
)

/**
 * 伙伴界面的 ViewModel —— 管理聊天消息、API 配置、Agent Loop。
 *
 * 职责分离：
 * - API 通信 → [ChatService]
 * - 工具执行 → [ToolExecutor]
 * - 配置持久化 → 通过 Rust 层 [NionCore] 的 settings 表读写
 * - UI 状态 → 本类管理的 Compose mutableState
 *
 * Agent Loop 工作流程：
 * 1. 用户发送消息 → 追加到对话历史
 * 2. 调用 LLM API（附带工具 Schema）
 * 3. 如果 LLM 返回 tool_calls → 执行工具 → 结果回传 LLM → 回到步骤 2
 * 4. 如果 LLM 返回纯文本 → 显示给用户 → 结束
 *
 * @param core NionCore 单例，用于存取设置 + 工具执行
 * @param app  Application 实例，用于发出数据变更通知
 */
class CompanionViewModel(
    private val core: NionCore,
    private val app: android.app.Application,
) : ViewModel() {

    private val TAG = "NionAgent"

    /** 聊天消息列表，UI 通过 LazyColumn 渲染（仅包含用户可见的文本消息） */
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    /** 是否正在等待 AI 回复或执行工具，用于显示加载指示器 */
    var isLoading by mutableStateOf(false)
        private set

    /** 流式输出当前累积的 AI 回复文本（实时更新），流式结束时固化为消息并置 null */
    var streamingAssistantText by mutableStateOf<String?>(null)
        private set

    /** 流式消息的时间戳 */
    var streamingMessageTimestamp by mutableStateOf("")
        private set

    /** 输入框当前文本 */
    var inputText by mutableStateOf("")

    /** 当前已配置的 Provider，null 表示尚未配置 API key */
    var currentProvider by mutableStateOf<ProviderConfig?>(null)
        private set

    /** 当前已保存的 API key */
    var apiKey by mutableStateOf<String?>(null)
        private set

    /** 当前使用的模型名称 */
    var modelName by mutableStateOf<String?>(null)
        private set

    /** 是否已完成初始化设置检查（防止启动时闪烁） */
    var isInitialized by mutableStateOf(false)
        private set

    /** 伙伴显示名称，可从编辑页修改，默认 "Nion" */
    var companionName by mutableStateOf("Nion")
        private set

    /** 伙伴系统提示词，定义 AI 的行为和语气，可从编辑页修改 */
    var companionPrompt by mutableStateOf("")
        private set

    /** 伙伴头像 URI，从系统图片选择器选取后保存，null 表示使用默认首字母头像 */
    var companionAvatarUri by mutableStateOf<String?>(null)
        private set

    /** 已保存的多组 Provider 配置，支持在它们之间切换 */
    var savedConfigs by mutableStateOf<List<SavedConfig>>(emptyList())
        private set

    /**
     * 完整的 API 对话历史。
     *
     * 与 [messages] 不同，此列表包含所有角色消息，包括：
     * - system / user / assistant 的文本消息
     * - assistant 的 tool_calls 消息（LLM 请求执行工具）
     * - tool 角色的工具执行结果消息
     *
     * 每个元素是一个完整的 JSONObject，直接传递给 API。
     */
    private var conversationHistory: MutableList<JSONObject> = mutableListOf()

    /** 工具执行器，注入 NionCore 单例 + 数据变更通知回调 */
    private val toolExecutor = ToolExecutor(core) { type ->
        (app as NionApp).notifyDataChanged(type)
    }

    /**
     * Agent Loop 的最大迭代次数，防止 LLM 无限循环调用工具。
     * 正常场景下 2-3 次即可完成，10 次是非常安全的上限。
     */
    private val maxAgentIterations = 10

    /** 伙伴系统提示词的默认值，首次使用或重置时使用 */
    private val defaultCompanionPrompt = """
你是 Nion，一个温暖友好的 AI 伴侣，同时也是用户的私人任务管理助手。

你的核心能力：
- 管理任务：创建、查看、修改、删除任务和子任务
- 管理清单：创建、查看、重命名、删除任务清单
- 设置优先级、截止日期、提醒等任务属性

行为准则：
- 主动但不过度：当用户提到任务相关的事情时，主动使用工具帮忙
- 自然对话：不是每次都要调用工具，闲聊时正常回复即可
- 确认重要操作：删除任务等不可逆操作前，先向用户确认
- 用中文回复，语气温暖简洁
    """.trimIndent()

    init {
        loadSettings()
        loadChatMessages()
    }

    /**
     * 从 Rust 层 settings 表中加载已保存的 provider / API key / 模型配置。
     * 同时加载多配置列表，向后兼容旧版单配置存储。
     * 加载完成后设 isInitialized = true，UI 据此决定显示设置页还是聊天页。
     */
    private fun loadSettings() {
        try {
            // ── 加载多配置列表 ──
            val configsJson = core.getSetting("llm_saved_configs")
            if (!configsJson.isNullOrEmpty()) {
                val arr = JSONArray(configsJson)
                val configs = mutableListOf<SavedConfig>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    configs.add(SavedConfig(
                        id = obj.getString("id"),
                        provider = obj.getString("provider"),
                        apiKey = obj.getString("apiKey"),
                        model = obj.getString("model"),
                        baseUrl = obj.optString("baseUrl", ""),
                        apiType = obj.optString("apiType", "OPENAI_COMPATIBLE"),
                    ))
                }
                savedConfigs = configs
            }

            // ── 加载当前激活的配置（优先用单键，向后兼容） ──
            val savedProviderName = core.getSetting("llm_provider")
            val savedApiKey = core.getSetting("llm_api_key")
            val savedModel = core.getSetting("llm_model")
            val savedBaseUrl = core.getSetting("llm_base_url")
            val savedApiTypeName = core.getSetting("llm_api_type")

            if (!savedProviderName.isNullOrEmpty() && !savedApiKey.isNullOrEmpty() && !savedModel.isNullOrEmpty()) {
                apiKey = savedApiKey
                modelName = savedModel
                val savedApiType = try {
                    ApiType.valueOf(savedApiTypeName ?: "OPENAI_COMPATIBLE")
                } catch (_: Exception) {
                    ApiType.OPENAI_COMPATIBLE
                }
                currentProvider = builtInProviders.find { it.name == savedProviderName }
                    ?: ProviderConfig(
                        name = savedProviderName,
                        baseUrl = savedBaseUrl ?: "",
                        apiType = savedApiType,
                    )
                // 如果多配置列表为空但单配置存在，自动迁移到多配置列表
                if (savedConfigs.isEmpty()) {
                    migrateToMultiConfig(savedProviderName, savedApiKey, savedModel, savedBaseUrl ?: "", savedApiTypeName ?: "OPENAI_COMPATIBLE")
                }
            }
        } catch (_: Exception) {
            // 设置读取失败（如首次启动无记录），保持未配置状态即可
        }

        // 加载伙伴名称和提示词，无保存值时使用默认值
        try {
            val savedName = core.getSetting("companion_name")
            if (!savedName.isNullOrEmpty()) {
                companionName = savedName
            }
            val savedPrompt = core.getSetting("companion_prompt")
            companionPrompt = if (!savedPrompt.isNullOrEmpty()) {
                savedPrompt
            } else {
                defaultCompanionPrompt
            }
        } catch (_: Exception) {
            companionPrompt = defaultCompanionPrompt
        }

        // 加载伙伴头像 URI
        try {
            val savedAvatar = core.getSetting("companion_avatar_uri")
            if (!savedAvatar.isNullOrEmpty()) {
                companionAvatarUri = savedAvatar
            }
        } catch (_: Exception) {}

        isInitialized = true
    }

    /** 将旧版单配置数据迁移到多配置列表 */
    private fun migrateToMultiConfig(
        providerName: String, apiKey: String, model: String, baseUrl: String, apiType: String,
    ) {
        val config = SavedConfig(
            id = UUID.randomUUID().toString(),
            provider = providerName,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            apiType = apiType,
        )
        savedConfigs = listOf(config)
        saveConfigsToStorage()
    }

    /** 将多配置列表序列化为 JSON 存入 Rust settings */
    private fun saveConfigsToStorage() {
        try {
            val arr = JSONArray()
            for (config in savedConfigs) {
                arr.put(JSONObject().apply {
                    put("id", config.id)
                    put("provider", config.provider)
                    put("apiKey", config.apiKey)
                    put("model", config.model)
                    put("baseUrl", config.baseUrl)
                    put("apiType", config.apiType)
                })
            }
            core.setSetting("llm_saved_configs", arr.toString())
        } catch (_: Exception) {}
    }

    /**
     * 将当前消息列表序列化为 JSON 并持久化到 Rust settings 的 chat_history 键。
     * 每次消息变更（发送/接收/清空）后调用。
     */
    private fun saveChatMessages() {
        try {
            val jsonArr = JSONArray()
            for (msg in messages) {
                jsonArr.put(JSONObject().apply {
                    put("id", msg.id)
                    put("text", msg.text)
                    put("isFromUser", msg.isFromUser)
                    put("timestamp", msg.timestamp)
                })
            }
            core.setSetting("chat_history", jsonArr.toString())
        } catch (_: Exception) {}
    }

    /**
     * 从 Rust settings 的 chat_history 键加载持久化的消息列表。
     * 在 init 中调用，恢复上次会话的消息。
     */
    private fun loadChatMessages() {
        try {
            val json = core.getSetting("chat_history")
            if (!json.isNullOrEmpty()) {
                val jsonArr = JSONArray(json)
                val loaded = mutableListOf<ChatMessage>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    loaded.add(ChatMessage(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        isFromUser = obj.getBoolean("isFromUser"),
                        timestamp = obj.getString("timestamp"),
                    ))
                }
                messages = loaded
            }
        } catch (_: Exception) {}
    }

    /**
     * 将当前消息列表归档到 Rust settings 的 chat_history_archive 键。
     * 清空前调用，归档的消息可在历史面板中查看。
     */
    private fun archiveCurrentChat() {
        if (messages.isEmpty()) return
        try {
            val jsonArr = JSONArray()
            for (msg in messages) {
                jsonArr.put(JSONObject().apply {
                    put("id", msg.id)
                    put("text", msg.text)
                    put("isFromUser", msg.isFromUser)
                    put("timestamp", msg.timestamp)
                })
            }
            core.setSetting("chat_history_archive", jsonArr.toString())
        } catch (_: Exception) {}
    }

    /**
     * 从 Rust settings 的 chat_history_archive 键加载归档的消息列表，
     * 供给历史面板展示（只读）。
     *
     * @return 归档的消息列表，无归档时返回空列表
     */
    fun loadArchivedChat(): List<ChatMessage> {
        try {
            val json = core.getSetting("chat_history_archive")
            if (!json.isNullOrEmpty()) {
                val jsonArr = JSONArray(json)
                val loaded = mutableListOf<ChatMessage>()
                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    loaded.add(ChatMessage(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        isFromUser = obj.getBoolean("isFromUser"),
                        timestamp = obj.getString("timestamp"),
                    ))
                }
                return loaded
            }
        } catch (_: Exception) {}
        return emptyList()
    }

    /**
     * 保存 API 配置到 Rust 层 settings 表并更新本地状态。
     * 同时更新多配置列表，支持后续切换。
     *
     * @param provider 用户选中的 Provider
     * @param key      API 密钥
     * @param model    用户指定的模型名
     * @param baseUrl  自定义 baseUrl（仅"自定义"provider 需要）
     */
    fun saveApiConfig(
        provider: ProviderConfig,
        key: String,
        model: String = "",
        baseUrl: String = "",
    ) {
        val actualModel = model
        val actualBaseUrl = if (provider.name == "自定义") baseUrl else provider.baseUrl

        try {
            core.setSetting("llm_provider", provider.name)
            core.setSetting("llm_api_key", key)
            core.setSetting("llm_model", actualModel)
            core.setSetting("llm_base_url", actualBaseUrl)
            core.setSetting("llm_api_type", provider.apiType.name)
        } catch (_: Exception) {
            // 持久化失败不影响内存状态，用户下次打开时需重新输入
        }

        apiKey = key
        modelName = actualModel
        currentProvider = provider.copy(baseUrl = actualBaseUrl)

        // 更新或新增到多配置列表
        val existing = savedConfigs.find { it.provider == provider.name && it.model == actualModel }
        val newConfigs = if (existing != null) {
            // 更新已存在的配置
            savedConfigs.map { c ->
                if (c.id == existing.id) c.copy(apiKey = key, baseUrl = actualBaseUrl, apiType = provider.apiType.name)
                else c
            }
        } else {
            // 新增配置
            savedConfigs + SavedConfig(
                id = UUID.randomUUID().toString(),
                provider = provider.name,
                apiKey = key,
                model = actualModel,
                baseUrl = actualBaseUrl,
                apiType = provider.apiType.name,
            )
        }
        savedConfigs = newConfigs
        saveConfigsToStorage()
    }

    /**
     * 切换到指定的已保存配置 —— 只更新当前 provider/API key/模型，不清理消息。
     *
     * @param configId 目标配置的 ID
     */
    fun switchToConfig(configId: String) {
        val config = savedConfigs.find { it.id == configId } ?: return
        apiKey = config.apiKey
        modelName = config.model
        val apiType = try {
            ApiType.valueOf(config.apiType)
        } catch (_: Exception) {
            ApiType.OPENAI_COMPATIBLE
        }
        currentProvider = builtInProviders.find { it.name == config.provider }
            ?: ProviderConfig(
                name = config.provider,
                baseUrl = config.baseUrl,
                apiType = apiType,
            )

        // 持久化当前激活配置到单键（向后兼容）
        try {
            core.setSetting("llm_provider", config.provider)
            core.setSetting("llm_api_key", config.apiKey)
            core.setSetting("llm_model", config.model)
            core.setSetting("llm_base_url", config.baseUrl)
            core.setSetting("llm_api_type", config.apiType)
        } catch (_: Exception) {}
    }

    /**
     * 删除指定的已保存配置，如果当前正使用该配置则一并清空。
     *
     * @param configId 要删除的配置 ID
     */
    fun deleteConfig(configId: String) {
        val config = savedConfigs.find { it.id == configId } ?: return
        savedConfigs = savedConfigs.filter { it.id != configId }
        saveConfigsToStorage()

        // 如果删除的是当前激活的配置，清空当前状态
        if (currentProvider?.name == config.provider && apiKey == config.apiKey) {
            apiKey = null
            modelName = null
            currentProvider = null
        }
    }

    /**
     * 更新伙伴名称和系统提示词，同时持久化到 Rust 层 settings 表。
     *
     * @param name   新的伙伴名称，非空时更新
     * @param prompt 新的系统提示词，非空时更新
     */
    fun updateCompanionInfo(name: String, prompt: String) {
        if (name.isNotEmpty()) {
            companionName = name
            try { core.setSetting("companion_name", name) } catch (_: Exception) {}
        }
        if (prompt.isNotEmpty()) {
            companionPrompt = prompt
            try { core.setSetting("companion_prompt", prompt) } catch (_: Exception) {}
        }
    }

    /**
     * 更新伙伴头像 URI，同时持久化到 Rust 层 settings 表。
     * 传入 null 表示清除头像，恢复默认首字母头像。
     *
     * @param uri 图片 URI 字符串，null 表示清除
     */
    fun updateAvatarUri(uri: String?) {
        companionAvatarUri = uri
        try {
            if (uri != null) {
                core.setSetting("companion_avatar_uri", uri)
            } else {
                core.setSetting("companion_avatar_uri", "")
            }
        } catch (_: Exception) {}
    }

    /**
     * 清除 API 配置 —— 清空本地状态和聊天记录。
     * 用于"切换 provider"或"重置设置"场景。
     */
    fun clearApiConfig() {
        archiveCurrentChat() // 清空前归档当前消息
        apiKey = null
        modelName = null
        currentProvider = null
        messages = emptyList()
        conversationHistory.clear()
        saveChatMessages()
        try {
            core.setSetting("llm_api_key", "")
        } catch (_: Exception) {
        }
    }

    /**
     * 发送用户消息并启动 Agent Loop。
     *
     * Agent Loop 流程：
     * 1. 将用户消息追加到 UI 消息列表和 API 对话历史
     * 2. 调用 LLM API（附带工具 Schema）
     * 3. 循环处理响应：
     *    - tool_calls → 执行工具 → 结果追加到对话历史 → 再次调用 LLM
     *    - 纯文本 → 显示给用户 → 退出循环
     * 4. 达到最大迭代次数 → 提示用户 → 退出循环
     */
    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() || isLoading) return

        val provider = currentProvider ?: return
        val key = apiKey ?: return
        val model = modelName ?: return

        val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            isFromUser = true,
            timestamp = now,
        )

        // 先追加用户消息到 UI 列表，让 UI 立即刷新
        messages = messages + userMessage
        inputText = ""
        isLoading = true
        saveChatMessages()

        // 将用户消息追加到 API 对话历史
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        viewModelScope.launch {
            runAgentLoop(provider, key, model)
            isLoading = false
        }
    }

    /**
     * Agent Loop 核心循环 —— 持续调用 LLM（流式）直到获得纯文本回复。
     *
     * 每次迭代使用 [ChatService.chatStream] 进行 SSE 流式请求：
     * 1. 构建完整的 API 消息列表（system + conversationHistory）
     * 2. 调用 [ChatService.chatStream] 发送流式请求
     *    - 文本增量通过 onTextDelta 回调实时更新 [streamingAssistantText]
     *    - 工具调用增量在流结束后统一组装
     * 3. 处理响应：
     *    - 有 tool_calls → 将已流式显示的文本固化为消息 → 执行工具 → 继续循环
     *    - 有文本（无 tool_calls）→ 将流式文本固化为最终消息 → 结束循环
     *    - 失败 → 显示错误消息，结束循环
     *
     * @param provider Provider 配置
     * @param apiKey   API 密钥
     * @param model    模型名称
     */
    private suspend fun runAgentLoop(provider: ProviderConfig, apiKey: String, model: String) {
        var iteration = 0

        while (iteration < maxAgentIterations) {
            iteration++
            Log.d(TAG, "Agent loop iteration=$iteration provider=${provider.name} model=$model")

            // 构建完整的 API 消息列表：system prompt + 对话历史
            val apiMessages = buildApiMessages()

            // 初始化流式输出状态：记录开始时间、清空累积文本
            val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            streamingMessageTimestamp = timestamp
            streamingAssistantText = ""

            // 发送流式 SSE 请求，onTextDelta 在 IO 线程被调用（mutableState 是线程安全的）
            val result = ChatService.chatStream(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = apiMessages,
                onTextDelta = { delta ->
                    // 每次收到文本增量时累积到流式状态，UI 自动刷新
                    streamingAssistantText = (streamingAssistantText ?: "") + delta
                },
            )

            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.e(TAG, "API 失败: $err")
                streamingAssistantText = null // 清除流式残留
                appendAssistantMessage("抱歉，出了点问题：$err")
                break
            }

            val response = result.getOrThrow()
            Log.d(TAG, "Response: streamedText=${streamingAssistantText?.take(100)}, toolCalls=${response.toolCalls?.size}")

            // 情况 1：LLM 请求执行工具
            if (response.toolCalls != null && response.toolCalls.isNotEmpty()) {
                // 将已流式显示的文本固化为一条完整消息（如果有文本的话）
                val streamedText = streamingAssistantText?.trim()
                if (!streamedText.isNullOrEmpty()) {
                    appendAssistantMessage(streamedText, reasoningContent = response.reasoningContent)
                }
                streamingAssistantText = null // 清除流式状态，准备下一轮迭代

                // 追加 assistant tool_calls 到对话历史（含 reasoning_content）
                appendAssistantToolCallsMessage(response.toolCalls, response.reasoningContent)

                // 逐个执行工具，结果回传对话历史
                for (call in response.toolCalls) {
                    Log.d(TAG, "Executing tool: ${call.name} args=${call.arguments.take(200)}")
                    val toolResult = toolExecutor.execute(call.name, call.arguments)
                    Log.d(TAG, "Tool result: success=${toolResult.success} data=${toolResult.data.take(200)}")
                    appendToolResultMessage(call.id, toolResult.data)
                }

                // 继续循环，让 LLM 根据工具结果生成下一步响应
                continue
            }

            // 情况 2：LLM 返回纯文本回复（无工具调用）
            val finalText = streamingAssistantText?.trim()
            if (!finalText.isNullOrEmpty()) {
                // 使用流式累积的文本（与 UI 显示的完全一致），固化为最终消息
                // 附带 reasoning_content 以满足 DeepSeek 推理模型的要求
                appendAssistantMessage(finalText, reasoningContent = response.reasoningContent)
            } else if (!response.text.isNullOrBlank()) {
                appendAssistantMessage(response.text.trim(), reasoningContent = response.reasoningContent)
            } else {
                appendAssistantMessage("（${companionName} 没有回复内容）")
            }
            streamingAssistantText = null
            break
        }

        // 确保迭代结束时流式状态被清除（防止残留的 loading 状态）
        streamingAssistantText = null

        // 达到最大迭代次数，安全退出
        if (iteration >= maxAgentIterations) {
            appendAssistantMessage("抱歉，工具调用次数过多，请简化你的请求后重试。")
        }
    }

    /**
     * 将 AI 文本回复追加到 UI 消息列表和 API 对话历史。
     *
     * @param text AI 回复文本
     * @param rawMessage 可选的原始 assistant message JSON（含 reasoning_content 等）
     * @param reasoningContent DeepSeek 推理模型的思考内容，必须回传到后续请求
     */
    private fun appendAssistantMessage(
        text: String,
        rawMessage: JSONObject? = null,
        reasoningContent: String? = null,
    ) {
        val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        messages = messages + ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            isFromUser = false,
            timestamp = now,
        )
        saveChatMessages()
        // 同步追加到 API 对话历史，优先使用原始消息（保留 reasoning_content 等）
        if (rawMessage != null) {
            conversationHistory.add(rawMessage)
        } else {
            conversationHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", text)
                // DeepSeek 推理模型要求回传 reasoning_content
                if (!reasoningContent.isNullOrEmpty()) {
                    put("reasoning_content", reasoningContent)
                }
            })
        }
    }

    /**
     * 将 assistant 的 tool_calls 消息追加到 API 对话历史。
     *
     * OpenAI 格式要求 assistant 消息包含 tool_calls 数组，
     * 格式为：{"role":"assistant", "tool_calls": [{id, type, function:{name, arguments}}]}
     *
     * Anthropic 也接受同样的格式（在 messages 数组中）。
     *
     * @param toolCalls        LLM 请求的工具调用列表
     * @param reasoningContent DeepSeek 推理模型的思考内容，必须回传到后续请求
     */
    private fun appendAssistantToolCallsMessage(
        toolCalls: List<ToolCall>,
        reasoningContent: String? = null,
    ) {
        val toolCallsJson = JSONArray()
        for (call in toolCalls) {
            toolCallsJson.put(JSONObject().apply {
                put("id", call.id)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", call.name)
                    put("arguments", call.arguments)
                })
            })
        }
        conversationHistory.add(JSONObject().apply {
            put("role", "assistant")
            put("content", null) // 有 tool_calls 时 content 通常为 null
            put("tool_calls", toolCallsJson)
            // DeepSeek 推理模型要求回传 reasoning_content
            if (!reasoningContent.isNullOrEmpty()) {
                put("reasoning_content", reasoningContent)
            }
        })
    }

    /**
     * 将工具执行结果追加到 API 对话历史。
     *
     * OpenAI 格式：{"role":"tool", "tool_call_id":"...", "content":"..."}
     * 此格式同时兼容 Anthropic（Anthropic 在 messages 中也接受 role=tool）。
     *
     * @param toolCallId 对应的 tool call ID，用于关联请求和结果
     * @param result     工具执行的 JSON 结果字符串
     */
    private fun appendToolResultMessage(toolCallId: String, result: String) {
        conversationHistory.add(JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", result)
        })
    }

    /**
     * 构建发送给 LLM API 的完整消息列表。
     *
     * 结构：
     * 1. system 消息（系统提示词 + 当前时间）
     * 2. conversationHistory 中的所有消息（user / assistant / tool）
     *
     * @return 完整的 API 消息列表，每个元素是 JSONObject
     */
    private fun buildApiMessages(): List<JSONObject> {
        val apiMessages = mutableListOf<JSONObject>()

        // 系统提示词：注入当前时间，让 LLM 理解时间上下文
        apiMessages.add(JSONObject().apply {
            put("role", "system")
            put("content", "$companionPrompt\n\n当前时间：${java.time.LocalDateTime.now()}")
        })

        // 追加完整的对话历史（包含工具调用和结果）
        apiMessages.addAll(conversationHistory)
        return apiMessages
    }

    companion object {
        /**
         * ViewModel 工厂 —— 从 Application 获取 NionCore 单例注入 ViewModel。
         */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = checkNotNull(extras[APPLICATION_KEY]) {
                    "CompanionViewModel requires Application in CreationExtras"
                }
                return CompanionViewModel(app.core(), app) as T
            }
        }
    }
}
