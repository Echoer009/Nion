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
import com.echonion.nion.ui.companion.tools.ToolResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore
import uniffi.nion_core.ConversationData
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

    /** 流式输出的节流显示文本（~80ms 更新一次），避免每 token 触发 Markdown 全量重解析 */
    var displayedStreamingText by mutableStateOf<String?>(null)
        private set

    /** 流式节流信号通道 —— CONFLATED 确保高频 token 到来时只保留最新信号 */
    private val streamingThrottleChannel = Channel<Unit>(Channel.CONFLATED)

    /** 流式消息的时间戳 */
    var streamingMessageTimestamp by mutableStateOf("")
        private set

    /** 输入框当前文本 */
    var inputText by mutableStateOf("")

    /** 面板关闭时保存的滚动位置（firstVisibleItemIndex），用于面板重新打开时恢复 */
    var savedScrollIndex by mutableStateOf(0)
        private set

    /** 面板关闭时保存的滚动偏移（firstVisibleItemScrollOffset），配合 savedScrollIndex 精确恢复 */
    var savedScrollOffset by mutableStateOf(0)
        private set

    /** 工具执行状态描述文本（如"正在创建任务..."），null 表示非工具执行阶段 */
    var toolExecutionStatus by mutableStateOf<String?>(null)
        private set

    /** 面板关闭时记录的消息数量，用于判断面板打开时是否有新消息需要滚到底部 */
    var lastSeenMessageCount by mutableStateOf(0)
        private set

    /** 保存当前滚动位置和消息数量，面板关闭时调用 */
    fun saveScrollPosition(index: Int, offset: Int, messageCount: Int) {
        savedScrollIndex = index
        savedScrollOffset = offset
        lastSeenMessageCount = messageCount
    }

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

    /** 用户自定义回复要求，追加在系统提示词之后，可从编辑页修改 */
    var replyRules by mutableStateOf("")
        private set

    /** 伙伴头像 URI，从系统图片选择器选取后保存，null 表示使用默认首字母头像 */
    var companionAvatarUri by mutableStateOf<String?>(null)
        private set

    /** 已保存的多组 Provider 配置，支持在它们之间切换 */
    var savedConfigs by mutableStateOf<List<SavedConfig>>(emptyList())
        private set

    /** 当前活跃的对话 ID，null 表示新对话尚未保存到数据库 */
    var currentConversationId by mutableStateOf<String?>(null)
        private set

    /** 对话历史列表（不含完整消息），供给历史面板展示 */
    var conversationList by mutableStateOf<List<ConversationData>>(emptyList())
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

你有 5 个工具可用（均支持批量操作，减少往返次数）：
1. query：查询任务、清单、分组数据
2. create：创建任务、清单、分组。批量：传 items 数组
3. update：更新任务属性（标题/优先级/状态等）、清单名称、分组名称/颜色。批量：传 ids 数组，所有实体应用相同变更
4. delete：删除任务、清单、分组。批量：传 ids 数组
5. move：移动任务到其他清单/分组，移动分组到其他清单（保留专注时长等数据）。批量：传 ids 数组

行为准则：
- 当用户要求创建/更新/删除/移动多项时，尽量在一次工具调用中批量完成
- 主动但不过度：当用户提到任务相关的事情时，主动使用工具帮忙
- 自然对话：不是每次都要调用工具，闲聊时正常回复即可
- 确认重要操作：删除等不可逆操作前，先向用户确认
- 移动优先：用户要移动任务/分组时，用 move 工具而非删除+重建，避免丢失专注时长
- 用中文回复，语气温暖简洁

回复格式要求：
- 不要用 emoji
- 展示任务层级结构时，用 Markdown 缩进列表表示层级关系
  - 父任务名 [状态]
    - 子任务名 [状态]
    - 子任务名 [状态]
- 展示简单列表时用 Markdown 无序列表（- 开头）
- 展示数据对比时用 Markdown 表格（| 列 | 格式）
    """.trimIndent()

    init {
        loadSettings()
        loadChatMessages()
        loadConversationList()
        // 流式文本显示节流：每 ~80ms 才更新一次 UI，避免每次 token 触发 Markdown O(n²) 重解析
        viewModelScope.launch {
            var lastDisplay = 0L
            for (signal in streamingThrottleChannel) {
                val elapsed = System.currentTimeMillis() - lastDisplay
                if (elapsed < 80L) delay(80L - elapsed)
                displayedStreamingText = streamingAssistantText
                lastDisplay = System.currentTimeMillis()
            }
        }
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
            val savedRules = core.getSetting("companion_reply_rules")
            if (!savedRules.isNullOrEmpty()) {
                replyRules = savedRules
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
     * 将当前消息列表序列化为 JSON 并持久化。
     * 如果 currentConversationId 存在则保存到 chat_conversations 表，
     * 否则保存到 settings 的 chat_history 键（向后兼容）。
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
                    put("isToolMessage", msg.isToolMessage)
                    put("toolDone", msg.toolDone)
                })
            }
            val jsonStr = jsonArr.toString()
            // 将 conversationHistory 序列化为 JSON 数组字符串
            val apiHistoryStr = JSONArray(conversationHistory.map { it.toString() }).toString()
            val convId = currentConversationId
            if (convId != null) {
                val title = generateConversationTitle()
                core.saveConversation(convId, title, jsonStr, apiHistoryStr)
            } else {
                core.setSetting("chat_history", jsonStr)
            }
        } catch (_: Exception) {}
    }

    /**
     * 加载聊天消息。
     * 优先从 settings 中的 current_conversation_id 恢复上次活跃对话，
     * 否则从旧版 chat_history settings 键加载。
     */
    private fun loadChatMessages() {
        try {
            val convId = core.getSetting("current_conversation_id")
            if (!convId.isNullOrEmpty()) {
                val conv = core.getConversation(convId)
                currentConversationId = conv.id
                messages = parseMessagesJson(conv.messages)
                // 恢复 API 层对话上下文
                conversationHistory.clear()
                val apiHistoryStr = conv.apiHistory
                if (apiHistoryStr.isNotEmpty() && apiHistoryStr != "[]") {
                    val arr = JSONArray(apiHistoryStr)
                    for (i in 0 until arr.length()) {
                        conversationHistory.add(JSONObject(arr.getString(i)))
                    }
                }
                return
            }
            val json = core.getSetting("chat_history")
            if (!json.isNullOrEmpty()) {
                messages = parseMessagesJson(json)
            }
        } catch (_: Exception) {
            currentConversationId = null
        }
    }

    /**
     * 从 JSON 字符串解析消息列表。
     */
    private fun parseMessagesJson(json: String): List<ChatMessage> {
        val jsonArr = JSONArray(json)
        val loaded = mutableListOf<ChatMessage>()
        for (i in 0 until jsonArr.length()) {
            val obj = jsonArr.getJSONObject(i)
            loaded.add(ChatMessage(
                id = obj.getString("id"),
                text = obj.getString("text"),
                isFromUser = obj.getBoolean("isFromUser"),
                timestamp = obj.getString("timestamp"),
                isToolMessage = obj.optBoolean("isToolMessage", false),
                toolDone = obj.optBoolean("toolDone", false),
            ))
        }
        return loaded
    }

    /**
     * 根据消息内容生成对话标题：取第一条用户消息的前 30 字符。
     */
    private fun generateConversationTitle(): String {
        val firstUserMsg = messages.firstOrNull { it.isFromUser }
        return if (firstUserMsg != null) {
            val text = firstUserMsg.text.trim()
            if (text.length > 30) text.take(30) + "..." else text
        } else {
            "新对话"
        }
    }

    /**
     * 加载对话历史列表（不含完整消息），供给历史面板展示。
     */
    fun loadConversationList() {
        try {
            conversationList = core.getConversations()
        } catch (_: Exception) {
            conversationList = emptyList()
        }
    }

    /**
     * 开始新对话 —— 将当前对话保存到历史，然后清空消息开始全新对话。
     * 如果当前对话为空则不做任何操作。
     */
    fun startNewConversation() {
        // 保存当前对话到数据库
        if (messages.isNotEmpty()) {
            saveChatMessages()
        }
        // 清空状态，开始新对话
        currentConversationId = null
        messages = emptyList()
        conversationHistory.clear()
        saveChatMessages()
    }

    /**
     * 恢复历史对话 —— 从数据库加载指定对话的消息，设为当前活跃对话。
     *
     * @param id 要恢复的对话 ID
     */
    fun loadConversation(id: String) {
        try {
            if (messages.isNotEmpty()) {
                saveChatMessages()
            }
            val conv = core.getConversation(id)
            currentConversationId = conv.id
            messages = parseMessagesJson(conv.messages)
            // 从持久化的 api_history 恢复 API 层对话上下文
            conversationHistory.clear()
            val apiHistoryStr = conv.apiHistory
            if (apiHistoryStr.isNotEmpty() && apiHistoryStr != "[]") {
                val arr = JSONArray(apiHistoryStr)
                for (i in 0 until arr.length()) {
                    conversationHistory.add(JSONObject(arr.getString(i)))
                }
            }
            core.setSetting("current_conversation_id", id)
        } catch (_: Exception) {}
    }

    /**
     * 删除历史对话。
     *
     * @param id 要删除的对话 ID
     */
    fun deleteConversation(id: String) {
        try {
            core.deleteConversation(id)
            // 如果删除的是当前对话，清空状态
            if (currentConversationId == id) {
                currentConversationId = null
                messages = emptyList()
                conversationHistory.clear()
                core.setSetting("current_conversation_id", "")
            }
            loadConversationList()
        } catch (_: Exception) {}
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
     * 更新用户自定义回复要求，同时持久化到 Rust 层 settings 表。
     * 回复要求会追加在系统提示词之后发给 LLM。
     *
     * @param rules 新的回复要求文本
     */
    fun updateReplyRules(rules: String) {
        replyRules = rules
        try { core.setSetting("companion_reply_rules", rules) } catch (_: Exception) {}
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
        // 保存当前对话到历史
        if (messages.isNotEmpty()) {
            saveChatMessages()
        }
        apiKey = null
        modelName = null
        currentProvider = null
        currentConversationId = null
        messages = emptyList()
        conversationHistory.clear()
        saveChatMessages()
        try {
            core.setSetting("llm_api_key", "")
            core.setSetting("current_conversation_id", "")
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

        // 首次发送消息时分配对话 ID，后续消息自动关联
        if (currentConversationId == null) {
            currentConversationId = UUID.randomUUID().toString()
            try {
                core.setSetting("current_conversation_id", currentConversationId!!)
            } catch (_: Exception) {}
        }

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
    /**
     * 将工具名 + 参数转换为中文状态描述，供 UI 在工具执行期间显示。
     * 例如："create" + entity_type="task" → "正在创建任务..."
     */
    private fun toolDisplayName(toolName: String, argumentsJson: String): String {
        val entityLabel = try {
            val args = JSONObject(argumentsJson)
            when (args.optString("entity_type", "")) {
                "task" -> "任务"
                "checklist" -> "清单"
                "group" -> "分组"
                else -> null
            }
        } catch (_: Exception) { null }

        val suffix = if (entityLabel != null) "${entityLabel}..." else "..."
        return when (toolName) {
            "query" -> "正在查询$suffix"
            "create" -> "正在创建$suffix"
            "update" -> "正在更新$suffix"
            "delete" -> "正在删除$suffix"
            "move" -> "正在移动$suffix"
            else -> "正在执行操作..."
        }
    }

    /**
     * 在聊天消息列表中插入一条工具执行状态消息（永久保留）。
     * @param toolCallId 工具调用 ID，用于后续更新
     * @param text 显示文本，如"正在创建任务..."
     */
    private fun appendToolStatusMessage(toolCallId: String, text: String) {
        messages = messages + ChatMessage(
            id = "tool_$toolCallId",
            text = text,
            isFromUser = false,
            timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            isToolMessage = true,
        )
    }

    /**
     * 更新已有的工具状态消息文本（执行完成后改为结果描述）。
     * @param toolCallId 工具调用 ID
     * @param text 新的状态文本
     */
    private fun updateToolStatusMessage(toolCallId: String, text: String) {
        val msgId = "tool_$toolCallId"
        messages = messages.map { if (it.id == msgId) it.copy(text = text, toolDone = true) else it }
    }

    /**
     * 根据工具执行结果生成简短的中文完成描述。
     * 从 JSON 结果中提取关键数量信息（如"已创建"、"已删除"、"查询到 N 条"）。
     */
    private fun toolResultText(toolName: String, argumentsJson: String, result: ToolResult): String {
        if (!result.success) return "操作失败"
        return try {
            val resultJson = JSONObject(result.data)
            val entityType = try {
                JSONObject(argumentsJson).optString("entity_type", "")
            } catch (_: Exception) { "" }
            val entityLabel = when (entityType) {
                "task" -> "任务"
                "checklist" -> "清单"
                "group" -> "分组"
                else -> ""
            }
            when (toolName) {
                "query" -> {
                    // 从结果 JSON 中统计返回的数据条数
                    val count = if (resultJson.has("checklists")) resultJson.getJSONArray("checklists").length()
                        else if (resultJson.has("groups")) resultJson.getJSONArray("groups").length()
                        else if (resultJson.has("tasks")) resultJson.getJSONArray("tasks").length()
                        else 0
                    "已查询到 $count 条$entityLabel"
                }
                "create" -> {
                    val name = resultJson.optJSONObject(entityType)?.optString("name", "")
                        ?: resultJson.optString("name", "")
                    if (name.isNotEmpty()) "已创建${entityLabel}「$name」"
                    else "已创建$entityLabel"
                }
                "update" -> "已更新$entityLabel"
                "delete" -> {
                    val name = resultJson.optJSONObject(entityType)?.optString("name", "")
                        ?: resultJson.optString("name", "")
                    if (name.isNotEmpty()) "已删除${entityLabel}「$name」"
                    else "已删除$entityLabel"
                }
                "move" -> "已移动$entityLabel"
                else -> "操作完成"
            }
        } catch (_: Exception) {
            when (toolName) {
                "query" -> "查询完成"
                "create" -> "创建完成"
                "update" -> "更新完成"
                "delete" -> "删除完成"
                "move" -> "移动完成"
                else -> "操作完成"
            }
        }
    }

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
            displayedStreamingText = ""

            // 发送流式 SSE 请求，onTextDelta 在 IO 线程被调用（mutableState 是线程安全的）
            val result = ChatService.chatStream(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = apiMessages,
                onTextDelta = { delta ->
                    // 每次收到文本增量时累积到流式状态
                    streamingAssistantText = (streamingAssistantText ?: "") + delta
                    // 通知节流协程有新文本到达（CONFLATED 自动丢弃积压信号）
                    streamingThrottleChannel.trySend(Unit)
                },
            )

            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.e(TAG, "API 失败: $err")
                streamingAssistantText = null // 清除流式残留
                displayedStreamingText = null
                toolExecutionStatus = null
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
                displayedStreamingText = null

                // 追加 assistant tool_calls 到对话历史（含 reasoning_content）
                appendAssistantToolCallsMessage(response.toolCalls, response.reasoningContent)

                // 逐个执行工具，结果回传对话历史
                for (call in response.toolCalls) {
                    Log.d(TAG, "Executing tool: ${call.name} args=${call.arguments.take(200)}")
                    // 在对话中插入一条持久的工具状态消息，让用户看到伙伴在做什么
                    val statusText = toolDisplayName(call.name, call.arguments)
                    toolExecutionStatus = statusText
                    appendToolStatusMessage(call.id, statusText)

                    val toolResult = toolExecutor.execute(call.name, call.arguments)
                    Log.d(TAG, "Tool result: success=${toolResult.success} data=${toolResult.data.take(200)}")

                    // 工具执行完成，更新状态消息为结果描述
                    val resultText = toolResultText(call.name, call.arguments, toolResult)
                    updateToolStatusMessage(call.id, resultText)
                    appendToolResultMessage(call.id, toolResult.data)
                }
                // 工具执行完毕，清除状态描述
                toolExecutionStatus = null

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
            displayedStreamingText = null
            break
        }

        // 确保迭代结束时流式状态被清除（防止残留的 loading 状态）
        streamingAssistantText = null
        displayedStreamingText = null

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
        // 若有用户自定义回复要求，追加在末尾
        val content = buildString {
            append(companionPrompt)
            append("\n\n当前时间：${java.time.LocalDateTime.now()}")
            if (replyRules.isNotBlank()) {
                append("\n\n---\n用户回复要求：\n$replyRules")
            }
        }
        apiMessages.add(JSONObject().apply {
            put("role", "system")
            put("content", content)
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
