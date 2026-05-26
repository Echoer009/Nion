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
import com.echonion.nion.ui.companion.tools.MemoryTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * 流式文本的 StringBuilder —— 替代 O(n²) 的字符串拼接。
     * 每次 onTextDelta 追加到 StringBuilder，节流协程定期 toString() 刷新 UI。
     * 因为 onTextDelta 可能从不同 IO 线程回调，用 @Volatile + synchronized 保护。
     */
    private val streamingBuilder = StringBuilder()

    /** 对 streamingBuilder 的访问锁，防止并发 append 和 toString 冲突 */
    private val streamingBuilderLock = Any()

    /** 流式输出是否处于活跃状态，用于防止节流协程在流式结束后仍把旧文本写回 displayedStreamingText */
    @Volatile
    private var streamingActive = false

    /**
     * 保存消息防抖通道 —— CONFLATED 保证短时间内多次 saveChatMessages() 调用只执行最后一次。
     * 解决问题：agent loop 中工具执行完成后连续调用 saveChatMessages，避免重复序列化 + SQLite 写入。
     */
    private val saveChannel = Channel<Unit>(Channel.CONFLATED)

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

    /**
     * 用户偏好列表 —— AI 记住的用户要求，每次对话都注入系统提示词。
     * 数据结构：JSONArray of {id, content, category, created_at}
     */
    var userPreferences by mutableStateOf<JSONArray>(JSONArray())
        private set

    /**
     * 用户记忆列表 —— AI 主动记录的关于用户的事实性信息，每次对话都注入系统提示词。
     * 数据结构：JSONArray of {id, content, category, created_at, updated_at, expires_hint?}
     */
    var userMemories by mutableStateOf<JSONArray>(JSONArray())
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

    /** 工具执行器，注入 NionCore 单例 + 数据变更通知回调 + 提醒调度回调 */
    private val toolExecutor = ToolExecutor(
        core,
        onDataChanged = { type ->
            (app as NionApp).notifyDataChanged(type)
        },
        onScheduleReminder = { taskId ->
            // 当工具修改了 reminder/recurrence 字段时，重新调度闹钟
            try {
                val task = core.getTask(taskId)
                val reminder = task.reminder
                val recurrenceRule = task.recurrenceRule
                val recurrenceTime = task.recurrenceReminderTime

                // 先取消旧闹钟
                com.echonion.nion.reminder.ReminderScheduler.cancelReminder(app, taskId)

                // 调度每日循环提醒
                if (recurrenceRule == "daily" && recurrenceTime != null) {
                    val parts = recurrenceTime.split(":")
                    if (parts.size == 2) {
                        val hour = parts[0].toIntOrNull()
                        val minute = parts[1].toIntOrNull()
                        if (hour != null && minute != null) {
                            com.echonion.nion.reminder.ReminderScheduler.scheduleDailyReminder(app, taskId, hour, minute)
                        }
                    }
                }

                // 调度一次性提醒
                if (reminder != null) {
                    val dt = com.echonion.nion.reminder.ReminderScheduler.parseReminderToMillisPublic(reminder)
                    if (dt != null && dt > System.currentTimeMillis()) {
                        com.echonion.nion.reminder.ReminderScheduler.scheduleExactReminder(app, taskId, dt)
                    }
                }
            } catch (_: Exception) {}
        },
    )

    /**
     * Agent Loop 的最大迭代次数，防止 LLM 无限循环调用工具。
     * 正常场景下 2-3 次即可完成，10 次是非常安全的上限。
     */
    private val maxAgentIterations = 10

    /** 伙伴系统提示词的默认值，首次使用或重置时使用 */
    private val defaultCompanionPrompt = """
你是 Nion，一个温暖友好的 AI 伴侣，同时也是用户的私人任务管理助手。

你有 8 个工具可用（均支持批量操作，减少往返次数）：
1. query：查询任务、清单、分组数据
2. create：创建任务、清单、分组。批量：传 items 数组
3. update：更新任务属性（标题/优先级/状态等）、清单名称、分组名称/颜色。批量：传 ids 数组，所有实体应用相同变更
4. delete：删除任务、清单、分组。批量：传 ids 数组
5. move：移动任务到其他清单/分组，移动分组到其他清单，调整任务层级（保留专注时长等数据）。批量：传 ids 数组。支持：task→task（把任务变成另一任务的子任务）、task→root（把子任务提升为独立主任务）
6. manage：通用操作（设置/移除每日循环等非 CRUD 操作）
7. remember：记住用户偏好规则。当用户表达不满、提出习惯性要求、或希望你记住某条规则时，调用 add 操作记录下来。当用户说"不用遵守 xxx 了"时，调用 remove 删除。你应主动识别用户意图并调用此工具，而非等到用户明确说"记住这个"
8. memory：主动记录关于用户的事实性信息。与 remember（偏好规则）不同，此工具记录的是描述性知识。在以下场景主动调用：
   - 用户首次提到自己的姓名、身份、职业 → identity
   - 用户说"最近在忙 XXX"、"正在准备 XXX" → context
   - 用户表达情绪（"我好累"、"太开心了"） → emotion
   - 用户提到兴趣爱好 → hobby
   - 用户提到固定安排（"每周三有课"） → schedule
   - 用户提到学习/工作相关情况 → study / work
   - 用户提到其他值得记住的信息 → 对应分类
   记忆去重：同类别下已有相关记忆时，用 update 而非新增（如"正在备考"→"考完了"）

行为准则：
- 当用户要求创建/更新/删除/移动多项时，尽量在一次工具调用中批量完成
- 主动但不过度：当用户提到任务相关的事情时，主动使用工具帮忙
- 自然对话：不是每次都要调用工具，闲聊时正常回复即可
- 确认重要操作：删除等不可逆操作前，先向用户确认
- 移动优先：用户要移动任务/分组时，用 move 工具而非删除+重建，避免丢失专注时长
- 记住偏好：用户表达不满或提出偏好时，主动用 remember 工具记录，后续必须遵守
- 主动记忆：从对话中发现有价值的信息时，主动用 memory 工具记录，让后续对话更个性化
- 用中文回复，语气温暖简洁

回复格式要求：
- 你可以使用以下 Markdown 格式，它们会被正确渲染：
  - 标题：# ~ ######
  - 粗体：**文字**
  - 斜体：*文字*
  - 内联代码：`代码`
  - 删除线：~~文字~~
  - 代码块：```包裹的多行代码```
  - 无序列表：- 或 * 开头
  - 有序列表：1. 开头
  - 任务列表：- [ ] 未完成 / - [x] 已完成
  - 引用块：> 开头
  - 表格：| 列1 | 列2 | 格式（支持 :--- 左对齐、:---: 居中、---: 右对齐）
  - 分割线：---
  - 链接：[文字](url)（仅显示文字，不可点击，尽量少用）
- 不支持的格式请勿使用：图片(![...](...))、HTML标签、嵌套缩进列表、脚注等
- 展示任务层级结构时，用无序列表表示层级关系：
  - 父任务名 [状态]
  - 子任务名 [状态]
- 展示数据对比时用表格
    """.trimIndent()

    /**
     * 数据加载状态，true 表示正在从数据库加载（settings、消息、对话列表）。
     * UI 据此显示加载指示器，避免加载完成前闪烁空白页。
     */
    var isDataLoading by mutableStateOf(true)
        private set

    init {
        /**
         * 关键：协程在 Main dispatcher 上启动，确保所有 mutableStateOf 写入发生在主线程。
         * 仅数据库 I/O 通过 withContext(Dispatchers.IO) 切到后台线程。
         * 之前直接用 Dispatchers.IO 启动协程会导致 Compose 快照不触发重组（跨线程写入延迟）。
         */
        viewModelScope.launch {
            Log.d("NionCompanion", "[init] loadSettings 开始（isInitialized=$isInitialized）")
            loadSettings()
            Log.d("NionCompanion", "[init] loadSettings 完成 → isInitialized=$isInitialized, provider=${currentProvider?.name}, apiKey=${if (apiKey != null) "已配置" else "未配置"}")
            loadChatMessages()
            loadConversationList()
            isDataLoading = false
            Log.d("NionCompanion", "[init] 全部加载完成 → messages.size=${messages.size}, conversations.size=${conversationList.size}")
        }

        // 安全超时：5 秒后如果 isInitialized 仍未变 true，强制初始化
        // 防止因协程取消/线程阻塞等极端情况导致永久空白
        viewModelScope.launch {
            delay(5000L)
            if (!isInitialized) {
                Log.w(TAG, "[init] loadSettings 超时未完成（isInitialized=$isInitialized, isDataLoading=$isDataLoading），强制初始化")
                isInitialized = true
            }
        }
        // 流式文本显示节流：每 ~80ms 才更新一次 UI，避免每次 token 触发 Markdown O(n²) 重解析
        // 从 streamingBuilder 读取（加锁），而非从 streamingAssistantText（已废弃拼接逻辑）
        // streamingActive 标志防止节流协程在流式结束后把旧文本重新写回
        viewModelScope.launch {
            var lastDisplay = 0L
            for (signal in streamingThrottleChannel) {
                if (!streamingActive) continue
                val elapsed = System.currentTimeMillis() - lastDisplay
                if (elapsed < 80L) delay(80L - elapsed)
                displayedStreamingText = synchronized(streamingBuilderLock) { streamingBuilder.toString() }
                lastDisplay = System.currentTimeMillis()
            }
        }
        // 消息保存防抖消费者：从 channel 收到信号后在 IO 线程执行实际保存
        // CONFLATED channel 保证短时间内多次 requestSave() 只触发一次实际 I/O
        viewModelScope.launch(Dispatchers.IO) {
            for (signal in saveChannel) {
                // 短暂延迟，合并连续的保存请求
                delay(50)
                performSave()
            }
        }
    }

    /**
     * 从 Rust 层 settings 表中加载已保存的 provider / API key / 模型配置。
     * 同时加载多配置列表，向后兼容旧版单配置存储。
     * 加载完成后设 isInitialized = true，UI 据此决定显示设置页还是聊天页。
     *
     * suspend 设计：调用方必须在协程中调用。数据库 I/O 通过 withContext(Dispatchers.IO)
     * 在后台线程执行，mutableStateOf 写入留在主线程以确保 Compose 快照正确观察。
     */
    private suspend fun loadSettings() {
        /** 阶段 1 从 DB 读取的原始数据，用于在 IO 线程收集、在主线程写入 mutableStateOf */
        data class RawData(
            val configsJson: String?,
            val providerName: String?,
            val apiKey: String?,
            val model: String?,
            val baseUrl: String?,
            val apiTypeName: String?,
            val companionName: String?,
            val companionPrompt: String?,
            val prefsJson: String?,
            val memoriesJson: String?,
            val avatarUri: String?,
        )

        /**
         * 阶段 1：在 IO 线程读取所有 DB 数据，存入局部变量。
         * 关键：core.getSetting 内部有互斥锁，必须异步执行避免阻塞主线程。
         */
        val loaded: RawData = withContext(Dispatchers.IO) {
            try {
                RawData(
                    configsJson = core.getSetting("llm_saved_configs"),
                    providerName = core.getSetting("llm_provider"),
                    apiKey = core.getSetting("llm_api_key"),
                    model = core.getSetting("llm_model"),
                    baseUrl = core.getSetting("llm_base_url"),
                    apiTypeName = core.getSetting("llm_api_type"),
                    companionName = core.getSetting("companion_name"),
                    companionPrompt = core.getSetting("companion_prompt"),
                    prefsJson = core.getSetting("companion_user_preferences"),
                    memoriesJson = core.getSetting(MemoryTool.SETTING_KEY),
                    avatarUri = core.getSetting("companion_avatar_uri"),
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadSettings DB 读取异常", e)
                RawData(null, null, null, null, null, null, null, null, null, null, null)
            }
        }

        /**
         * 阶段 2：在主线程处理数据并写入 mutableStateOf。
         * 此时已回到调用协程的 dispatcher（Main），所有 Compose 状态写入可被正确观察。
         */
        try {
            // ── 解析多配置列表 ──
            if (!loaded.configsJson.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(loaded.configsJson)
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
                } catch (_: Exception) {}
            }

            // ── 加载当前激活的配置 ──
            val pName = loaded.providerName
            val pKey = loaded.apiKey
            val pModel = loaded.model
            if (!pName.isNullOrEmpty() && !pKey.isNullOrEmpty() && !pModel.isNullOrEmpty()) {
                apiKey = pKey
                modelName = pModel
                val apiType = try {
                    ApiType.valueOf(loaded.apiTypeName ?: "OPENAI_COMPATIBLE")
                } catch (_: Exception) {
                    ApiType.OPENAI_COMPATIBLE
                }
                currentProvider = builtInProviders.find { it.name == pName }
                    ?: ProviderConfig(name = pName, baseUrl = loaded.baseUrl ?: "", apiType = apiType)
                // 旧版单配置自动迁移到多配置列表
                if (savedConfigs.isEmpty()) {
                    migrateToMultiConfig(pName, pKey, pModel, loaded.baseUrl ?: "", loaded.apiTypeName ?: "OPENAI_COMPATIBLE")
                }
            }

            // ── 加载伙伴名称和提示词 ──
            if (!loaded.companionName.isNullOrEmpty()) companionName = loaded.companionName
            companionPrompt = loaded.companionPrompt ?: defaultCompanionPrompt

            // ── 加载用户偏好 ──
            if (!loaded.prefsJson.isNullOrEmpty()) {
                try { userPreferences = JSONArray(loaded.prefsJson) } catch (_: Exception) {}
            }

            // ── 加载用户记忆 ──
            if (!loaded.memoriesJson.isNullOrEmpty()) {
                try { userMemories = JSONArray(loaded.memoriesJson) } catch (_: Exception) {}
            }

            // ── 加载头像 ──
            if (!loaded.avatarUri.isNullOrEmpty()) companionAvatarUri = loaded.avatarUri
        } catch (e: Exception) {
            // 兜底：内层未捕获异常
            Log.e(TAG, "loadSettings 状态写入异常", e)
        } finally {
            // 无论任何情况，标记初始化完成，防止面板永久空白
            isInitialized = true
        }
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
     * 请求异步保存消息 —— 通过防抖 channel 合并短时间内的多次保存请求。
     * 所有需要保存消息的地方都应调用此方法，而非直接调用 [performSave]。
     */
    private fun requestSave() {
        saveChannel.trySend(Unit)
    }

    /**
     * 实际执行保存操作：序列化消息列表 + API 对话历史，写入 SQLite。
     * 此方法应在 IO 线程执行（由 saveChannel 消费者协程调用）。
     *
     * 如果 currentConversationId 存在则保存到 chat_conversations 表，
     * 否则保存到 settings 的 chat_history 键（向后兼容）。
     */
    private fun performSave() {
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
     *
     * DB 读取在 IO 线程，mutableStateOf 写入在主线程。
     */
    private suspend fun loadChatMessages() {
        /** 阶段 1 从 DB 读取的原始聊天数据 */
        data class RawChat(
            val convId: String?,
            val convMessages: String?,
            val convApiHistory: String?,
            val chatHistory: String?,
        )

        // 阶段 1：IO 线程读取 DB
        val chatData: RawChat = withContext(Dispatchers.IO) {
            try {
                val convId = core.getSetting("current_conversation_id")
                if (!convId.isNullOrEmpty()) {
                    val conv = core.getConversation(convId)
                    RawChat(convId = conv.id, convMessages = conv.messages, convApiHistory = conv.apiHistory, chatHistory = null)
                } else {
                    val json = core.getSetting("chat_history")
                    RawChat(convId = null, convMessages = null, convApiHistory = null, chatHistory = json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadChatMessages DB 读取异常", e)
                RawChat(null, null, null, null)
            }
        }

        // 阶段 2：主线程解析 JSON 并写入 mutableStateOf
        try {
            if (chatData.convId != null) {
                currentConversationId = chatData.convId
                messages = parseMessagesJson(chatData.convMessages ?: "[]")
                // 恢复 API 层对话上下文（conversationHistory 是普通列表，非 Compose 状态）
                conversationHistory.clear()
                val apiHistoryStr = chatData.convApiHistory
                if (!apiHistoryStr.isNullOrEmpty() && apiHistoryStr != "[]") {
                    val arr = JSONArray(apiHistoryStr)
                    for (i in 0 until arr.length()) {
                        conversationHistory.add(JSONObject(arr.getString(i)))
                    }
                }
            } else if (!chatData.chatHistory.isNullOrEmpty()) {
                messages = parseMessagesJson(chatData.chatHistory)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadChatMessages 解析异常", e)
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
     *
     * DB 读取在 IO 线程，mutableStateOf 写入在主线程。
     */
    suspend fun loadConversationList() {
        val list = withContext(Dispatchers.IO) {
            try { core.getConversations() } catch (_: Exception) { emptyList() }
        }
        conversationList = list
    }

    /**
     * 开始新对话 —— 将当前对话保存到历史，然后清空消息开始全新对话。
     * 如果当前对话为空则不做任何操作。
     */
    fun startNewConversation() {
        // 结束任何残留的流式输出，防止旧文本气泡残留
        endStreaming()
        // 保存当前对话到数据库（同步执行，因为后续要清空状态）
        if (messages.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { performSave() }
        }
        // 清空状态，开始新对话
        currentConversationId = null
        messages = emptyList()
        conversationHistory.clear()
        viewModelScope.launch(Dispatchers.IO) { performSave() }
        // 刷新历史列表 —— loadConversationList 内部已处理 IO/State 切线程，此处无需指定 Dispatchers.IO
        viewModelScope.launch { loadConversationList() }
    }

    /**
     * 恢复历史对话 —— 从数据库加载指定对话的消息，设为当前活跃对话。
     * 协程在 Main 启动确保 mutableStateOf 写入能被 Compose 正确观察。
     *
     * @param id 要恢复的对话 ID
     */
    fun loadConversation(id: String) {
        endStreaming()
        viewModelScope.launch {
            try {
                // performSave 内部只做 DB 写，放 IO 线程避免阻塞主线程
                if (messages.isNotEmpty()) {
                    withContext(Dispatchers.IO) { performSave() }
                }
                // DB 读 + JSON 解析在 IO 线程
                val conv = withContext(Dispatchers.IO) { core.getConversation(id) }
                // mutableStateOf 写入在主线程
                currentConversationId = conv.id
                messages = parseMessagesJson(conv.messages)
                // 恢复 API 层对话上下文（conversationHistory 是普通列表，非 Compose 状态）
                conversationHistory.clear()
                val apiHistoryStr = conv.apiHistory
                if (apiHistoryStr.isNotEmpty() && apiHistoryStr != "[]") {
                    val arr = JSONArray(apiHistoryStr)
                    for (i in 0 until arr.length()) {
                        conversationHistory.add(JSONObject(arr.getString(i)))
                    }
                }
                withContext(Dispatchers.IO) { core.setSetting("current_conversation_id", id) }
            } catch (_: Exception) {}
        }
    }

    /**
     * 删除历史对话。
     * 协程在 Main 启动确保 mutableStateOf 写入能被 Compose 正确观察，
     * 数据库 I/O 通过 withContext(Dispatchers.IO) 隔离。
     *
     * @param id 要删除的对话 ID
     */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            try {
                // DB 删除在 IO 线程
                withContext(Dispatchers.IO) { core.deleteConversation(id) }
                // 状态写入在主线程
                if (currentConversationId == id) {
                    currentConversationId = null
                    messages = emptyList()
                    conversationHistory.clear()
                    withContext(Dispatchers.IO) { core.setSetting("current_conversation_id", "") }
                }
                loadConversationList()
            } catch (_: Exception) {}
        }
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
     * 添加一条用户偏好，同时持久化到 Rust 层 settings 表。
     * UI 管理界面和 remember 工具都可调用此方法。
     *
     * @param content  偏好内容
     * @param category 分类：style / behavior / format / other
     */
    fun addPreference(content: String, category: String) {
        val prefs = userPreferences
        val id = java.util.UUID.randomUUID().toString()
        val now = java.time.LocalDateTime.now().toString()
        val newPref = org.json.JSONObject().apply {
            put("id", id)
            put("content", content)
            put("category", category)
            put("created_at", now)
        }
        prefs.put(newPref)
        userPreferences = prefs
        try { com.echonion.nion.ui.companion.tools.RememberTool.savePreferences(core, prefs) } catch (_: Exception) {}
    }

    /**
     * 删除一条用户偏好，同时持久化到 Rust 层 settings 表。
     *
     * @param id 要删除的偏好条目 ID
     */
    fun removePreference(id: String) {
        val prefs = userPreferences
        val newArr = org.json.JSONArray()
        for (i in 0 until prefs.length()) {
            val pref = prefs.getJSONObject(i)
            if (pref.getString("id") != id) {
                newArr.put(pref)
            }
        }
        userPreferences = newArr
        try { com.echonion.nion.ui.companion.tools.RememberTool.savePreferences(core, newArr) } catch (_: Exception) {}
    }

    /**
     * 刷新用户偏好列表 —— 从 Rust 层重新加载最新数据。
     * 在 remember 工具执行成功后调用，确保 UI 显示最新偏好。
     */
    fun refreshPreferences() {
        try {
            val prefsJson = core.getSetting("companion_user_preferences")
            userPreferences = if (!prefsJson.isNullOrEmpty()) {
                org.json.JSONArray(prefsJson)
            } else {
                org.json.JSONArray()
            }
        } catch (_: Exception) {}
    }

    /**
     * 刷新用户记忆列表 —— 从 Rust 层重新加载最新数据。
     * 在 memory 工具执行成功后调用，确保 UI 显示最新记忆，以及后续系统提示词注入最新记忆。
     */
    fun refreshMemories() {
        try {
            val memoriesJson = core.getSetting(MemoryTool.SETTING_KEY)
            userMemories = if (!memoriesJson.isNullOrEmpty()) {
                JSONArray(memoriesJson)
            } else {
                JSONArray()
            }
        } catch (_: Exception) {}
    }

    /**
     * 删除一条用户记忆，同时持久化到 Rust 层 settings 表。
     * 供 UI 管理界面调用。
     *
     * @param id 要删除的记忆条目 ID
     */
    fun removeMemory(id: String) {
        val memories = userMemories
        val newArr = JSONArray()
        for (i in 0 until memories.length()) {
            val mem = memories.getJSONObject(i)
            if (mem.getString("id") != id) {
                newArr.put(mem)
            }
        }
        userMemories = newArr
        try { MemoryTool.saveMemories(core, newArr) } catch (_: Exception) {}
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
     *
     * mutableStateOf 写入必须在主线程，DB 操作通过 withContext(IO) 隔离。
     */
    fun clearApiConfig() {
        endStreaming()
        viewModelScope.launch {
            // DB 保存在 IO 线程
            withContext(Dispatchers.IO) {
                if (messages.isNotEmpty()) {
                    performSave()
                }
            }
            // 状态写入在主线程 —— 关键：确保 Compose 快照正确观察
            apiKey = null
            modelName = null
            currentProvider = null
            currentConversationId = null
            messages = emptyList()
            conversationHistory.clear()
            // 后续 DB 写也在 IO
            withContext(Dispatchers.IO) {
                performSave()
                try {
                    core.setSetting("llm_api_key", "")
                    core.setSetting("current_conversation_id", "")
                } catch (_: Exception) {}
            }
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
        if (text.isEmpty() || isLoading) {
            Log.d(TAG, "[sendMessage] 拦截 → textEmpty=${text.isEmpty()}, isLoading=$isLoading")
            return
        }

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

        Log.d(TAG, "[sendMessage] 发送 → text=$text, msgCount=${messages.size}")

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

        requestSave()

        // 将用户消息追加到 API 对话历史
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        Log.d(TAG, "[sendMessage] 启动 AgentLoop → msgCount=${messages.size}")
        viewModelScope.launch {
            runAgentLoop(provider, key, model)
            isLoading = false
            Log.d(TAG, "[sendMessage] AgentLoop 结束 → msgCount=${messages.size}")
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
            "manage" -> "正在管理设置..."
            "remember" -> "正在记录偏好..."
            "memory" -> "$companionName 记住了一些事情..."
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
                "remember" -> resultJson.optString("message", "偏好已更新")
                "memory" -> resultJson.optString("message", "记忆已更新")
                else -> "操作完成"
            }
        } catch (_: Exception) {
            when (toolName) {
                "query" -> "查询完成"
                "create" -> "创建完成"
                "update" -> "更新完成"
                "delete" -> "删除完成"
                "move" -> "移动完成"
                "remember" -> "记忆偏好中"
                "memory" -> "$companionName 记住了一些事情"
                else -> "操作完成"
            }
        }
    }

    private suspend fun runAgentLoop(provider: ProviderConfig, apiKey: String, model: String) {
        var iteration = 0
        Log.d(TAG, "[AgentLoop] 开始 iteration=0 msgCount=${messages.size}")
        while (iteration < maxAgentIterations) {
            iteration++
            Log.d(TAG, "[AgentLoop] iteration=$iteration 开始")
            Log.d(TAG, "Agent loop iteration=$iteration provider=${provider.name} model=$model")

            // 构建完整的 API 消息列表：system prompt + 对话历史
            val apiMessages = buildApiMessages()

            // 初始化流式输出状态：记录开始时间、清空累积文本
            val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            streamingMessageTimestamp = timestamp
            streamingAssistantText = "" // 标记"正在流式输出"，UI 据此显示流式气泡
            displayedStreamingText = ""
            streamingActive = true
            // 清空 StringBuilder，准备接收新一轮流式文本
            synchronized(streamingBuilderLock) { streamingBuilder.clear() }

            // 发送流式 SSE 请求，onTextDelta 在 IO 线程被调用
            val result = ChatService.chatStream(
                provider = provider,
                apiKey = apiKey,
                model = model,
                messages = apiMessages,
                onTextDelta = { delta ->
                    // 追加到 StringBuilder（O(1) 均摊），替代 O(n) 的字符串拼接
                    synchronized(streamingBuilderLock) { streamingBuilder.append(delta) }
                    // 同步更新 streamingAssistantText，供 runAgentLoop 后续判断是否有文本
                    streamingAssistantText = synchronized(streamingBuilderLock) { streamingBuilder.toString() }
                    // 通知节流协程有新文本到达（CONFLATED 自动丢弃积压信号）
                    streamingThrottleChannel.trySend(Unit)
                },
            )

            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "unknown"
                Log.e(TAG, "API 失败: $err")
                endStreaming()
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
                Log.d(TAG, "[AgentLoop] 有工具调用 → streamedText=${streamedText?.take(60)}, toolCount=${response.toolCalls.size}")
                if (!streamedText.isNullOrEmpty()) {
                    appendAssistantMessage(streamedText, reasoningContent = response.reasoningContent)
                }
                endStreaming()

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

                // 如果有 remember 工具被调用，刷新偏好列表确保 UI 和后续提示词同步
                val hasRememberTool = response.toolCalls.any { it.name == "remember" }
                if (hasRememberTool) {
                    refreshPreferences()
                }

                // 如果有 memory 工具被调用，刷新记忆列表确保 UI 和后续提示词同步
                val hasMemoryTool = response.toolCalls.any { it.name == "memory" }
                if (hasMemoryTool) {
                    refreshMemories()
                }

                // 继续循环，让 LLM 根据工具结果生成下一步响应
                continue
            }

            // 情况 2：LLM 返回纯文本回复（无工具调用）
            val finalText = streamingAssistantText?.trim()
            Log.d(TAG, "[AgentLoop] 纯文本 → finalText=${finalText?.take(60)}, response.text=${response.text?.take(60)}")
            if (!finalText.isNullOrEmpty()) {
                // 使用流式累积的文本（与 UI 显示的完全一致），固化为最终消息
                // 附带 reasoning_content 以满足 DeepSeek 推理模型的要求
                appendAssistantMessage(finalText, reasoningContent = response.reasoningContent)
            } else if (!response.text.isNullOrBlank()) {
                appendAssistantMessage(response.text.trim(), reasoningContent = response.reasoningContent)
            } else {
                appendAssistantMessage("（${companionName} 没有回复内容）")
            }
            endStreaming()
            break
        }

        // 确保迭代结束时流式状态被清除（防止残留的 loading 状态）
        endStreaming()

        // 达到最大迭代次数，安全退出
        if (iteration >= maxAgentIterations) {
            appendAssistantMessage("抱歉，工具调用次数过多，请简化你的请求后重试。")
        }
    }

    /**
     * 安全结束流式输出 —— 先关闭 streamingActive 标志防止节流协程的残留信号
     * 把旧文本重新写回，再清空 builder 和状态变量。
     *
     * 调用顺序是关键：streamingActive=false 必须在前，
     * 确保后续的节流信号一律跳过，不会覆盖紧接着的 null 赋值。
     */
    private fun endStreaming() {
        streamingActive = false
        synchronized(streamingBuilderLock) { streamingBuilder.clear() }
        streamingAssistantText = null
        displayedStreamingText = null
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
        val newId = UUID.randomUUID().toString()
        Log.d(TAG, "[appendMsg] id=$newId text=${text.take(80)}")
        messages = messages + ChatMessage(
            id = newId,
            text = text,
            isFromUser = false,
            timestamp = now,
        )
        requestSave()
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
     * 结构（适配 DeepSeek Prefix Caching）：
     * 1. system 消息（系统提示词 + 用户偏好）—— 稳定前缀，可被缓存
     * 2. conversationHistory 中的所有消息（user / assistant / tool）
     * 3. system 消息（当前时间）—— 可变部分放在末尾，不影响前缀缓存
     *
     * @return 完整的 API 消息列表，每个元素是 JSONObject
     */
    private fun buildApiMessages(): List<JSONObject> {
        val apiMessages = mutableListOf<JSONObject>()

        // 系统提示词（稳定前缀）：系统提示词 + 用户偏好记录 + 用户记忆
        // 不包含当前时间等可变内容，确保前缀在连续请求间完全一致，命中 DeepSeek 缓存
        val stableContent = buildString {
            append(companionPrompt)
            // 注入用户偏好记录（AI 通过 remember 工具和 UI 手动添加的偏好）
            if (userPreferences.length() > 0) {
                append("\n\n---\n用户偏好记录（必须始终遵守）：")
                val prefCategoryLabels = mapOf(
                    "style" to "风格", "behavior" to "行为",
                    "format" to "格式", "other" to "其他",
                )
                for (i in 0 until userPreferences.length()) {
                    val pref = userPreferences.getJSONObject(i)
                    val label = prefCategoryLabels[pref.optString("category", "other")] ?: "其他"
                    append("\n- [$label] ${pref.getString("content")}")
                }
            }
            // 注入用户记忆（AI 通过 memory 工具主动记录的关于用户的事实性信息）
            if (userMemories.length() > 0) {
                val memoryCategoryLabels = MemoryTool.categoryLabels
                append("\n\n---\n关于用户的记忆（${companionName}的笔记本）：")
                for (i in 0 until userMemories.length()) {
                    val mem = userMemories.getJSONObject(i)
                    val label = memoryCategoryLabels[mem.optString("category", "other")] ?: "其他"
                    append("\n- [$label] ${mem.getString("content")}")
                }
            }
        }
        apiMessages.add(JSONObject().apply {
            put("role", "system")
            put("content", stableContent)
        })

        // 追加完整的对话历史（包含工具调用和结果）
        apiMessages.addAll(conversationHistory)

        // 当前时间注入到消息列表末尾（可变部分）
        // 放在最后一条 system 消息中，避免破坏稳定前缀导致 DeepSeek 缓存失效
        apiMessages.add(JSONObject().apply {
            put("role", "system")
            put("content", "当前时间：${java.time.LocalDateTime.now()}")
        })

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
