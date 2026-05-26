package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 用户记忆工具 —— 让 AI 主动记录关于用户的事实性信息。
 *
 * 与 [RememberTool]（偏好规则）不同，本工具记录的是**关于用户的知识**：
 * - RememberTool：规范性 —— "AI 应该怎么做"（如"不要用 emoji"）
 * - MemoryTool：描述性 —— "用户是什么样的人"（如"用户是大三学生"）
 *
 * AI 应在以下场景**主动**调用此工具，无需等待用户明确要求：
 * - 用户首次提及自己的姓名、身份 → identity
 * - 用户说"最近在忙 XXX" → context
 * - 用户表达情绪（"我好累"、"太开心了"） → emotion
 * - 用户提到固定安排（"每周三有课"） → schedule
 *
 * 去重/更新策略：
 * - 同类别下已有相关记忆时，应使用 update 而非新增（如"正在备考"→"考完了"）
 * - context 类记忆可能过期，可带 expires_hint 字段供 AI 后续判断
 *
 * 支持四种操作：
 * - add：添加一条记忆（需指定 content 和 category）
 * - list：列出所有记忆（可按 category 筛选）
 * - remove：删除某条记忆（需指定 id）
 * - update：更新某条记忆的内容（需指定 id + content）
 *
 * 记忆分类（14 类）：
 * - identity（身份）：姓名、年龄、职业、学生/上班族等
 * - study（学习）：专业、课程、考试、学习进度
 * - work（工作）：项目、岗位、工作内容
 * - hobby（兴趣爱好）：喜欢什么、玩什么
 * - habit（习惯）：生活习惯、作息、饮食偏好
 * - health（健康）：身体状况、过敏、运动习惯
 * - emotion（情绪状态）：当前心情、心理状态
 * - goal（目标）：近期想完成的事、长远规划
 * - schedule（日程安排）：日常作息、重要日期
 * - social（社交关系）：朋友、家人、室友等
 * - location（地点）：所在城市、常去的地方
 * - pet（宠物）：养了什么宠物
 * - context（当前状态）：正在忙什么、短期情境
 * - other（其他）：不属于以上类别的记忆
 */
object MemoryTool : Tool {
    override val name = "memory"
    override val description = "记忆工具 —— 主动记录关于用户的事实性信息（身份、状态、兴趣等）。" +
        "与 remember（偏好规则）不同，此工具记录的是描述性知识。" +
        "当用户在对话中透露个人信息、当前状态、兴趣爱好等时，主动调用此工具记录。" +
        "通过 action 字段指定操作类型。" +
        "add：添加记忆（content + category，可带 expires_hint）；" +
        "list：列出记忆（可按 category 筛选）；" +
        "update：更新记忆（id + content）；" +
        "remove：删除记忆（id）。"

    /** setting key，用于在 Rust 层 settings 表中读写记忆数据 */
    const val SETTING_KEY = "companion_user_memories"

    /**
     * 分类名称映射：英文键 → 中文显示名
     * 用于工具返回结果中的可读描述和系统提示词注入
     */
    val categoryLabels = mapOf(
        "identity" to "身份",
        "study" to "学习",
        "work" to "工作",
        "hobby" to "兴趣爱好",
        "habit" to "习惯",
        "health" to "健康",
        "emotion" to "情绪状态",
        "goal" to "目标",
        "schedule" to "日程安排",
        "social" to "社交关系",
        "location" to "地点",
        "pet" to "宠物",
        "context" to "当前状态",
        "other" to "其他",
    )

    /**
     * 所有有效的分类键集合，用于参数校验
     */
    private val validCategories = categoryLabels.keys

    private val schema = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["add", "list", "remove", "update"],
                "description": "操作类型：add=添加记忆, list=列出记忆, update=更新记忆, remove=删除记忆"
            },
            "content": {
                "type": "string",
                "description": "记忆内容（add/update 时必填），如'用户是大三计算机系学生'"
            },
            "category": {
                "type": "string",
                "enum": ["identity", "study", "work", "hobby", "habit", "health", "emotion", "goal", "schedule", "social", "location", "pet", "context", "other"],
                "description": "记忆分类（add 时必填）。identity=身份, study=学习, work=工作, hobby=兴趣, habit=习惯, health=健康, emotion=情绪, goal=目标, schedule=日程, social=社交, location=地点, pet=宠物, context=当前状态, other=其他"
            },
            "id": {
                "type": "string",
                "description": "记忆 ID（update/remove 时必填），指定要操作的记忆条目"
            },
            "expires_hint": {
                "type": "string",
                "description": "（可选）记忆的预期过期时间，格式为日期字符串如'2026-06-10'。仅适用于 context 等短期记忆，AI 后续可据此判断是否需要更新/删除。长期记忆不需要此字段"
            },
            "filter_category": {
                "type": "string",
                "enum": ["identity", "study", "work", "hobby", "habit", "health", "emotion", "goal", "schedule", "social", "location", "pet", "context", "other"],
                "description": "（可选）list 操作时按分类筛选，不传则返回所有记忆"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    /**
     * 执行记忆操作。
     *
     * 路由到对应的 add/list/update/remove 方法。
     * 记忆数据通过 [NionCore.getSetting]/[NionCore.setSetting] 持久化。
     *
     * @param params 已校验的参数
     * @param core   NionCore 单例，用于读写 settings
     * @return 操作结果的 JSON 字符串
     */
    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.getString("action")
        return when (action) {
            "add" -> executeAdd(params, core)
            "list" -> executeList(params, core)
            "update" -> executeUpdate(params, core)
            "remove" -> executeRemove(params, core)
            else -> """{"error":"不支持的操作: $action"}"""
        }
    }

    /**
     * 添加一条记忆。
     *
     * 从参数中提取 content、category 和可选的 expires_hint，生成 UUID 作为 ID，
     * 追加到现有记忆列表后持久化存储。
     *
     * @param params 包含 content（必填）、category（必填）、expires_hint（可选）的参数
     * @param core   NionCore 单例
     * @return 添加结果的 JSON 字符串
     */
    private fun executeAdd(params: JSONObject, core: NionCore): String {
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return """{"error":"添加记忆时 content 不能为空"}"""
        }
        val category = params.optString("category", "other")
        if (category !in validCategories) {
            return """{"error":"无效的分类: $category，可选: ${validCategories.joinToString(", ")}"}"""
        }

        // 读取现有记忆列表
        val memories = loadMemories(core)
        val id = java.util.UUID.randomUUID().toString()
        val now = java.time.LocalDateTime.now().toString()

        // 构建新记忆项并追加
        val newMemory = JSONObject().apply {
            put("id", id)
            put("content", content)
            put("category", category)
            put("created_at", now)
            put("updated_at", now)
            // 可选的过期提示，仅 context 等短期记忆使用
            val expiresHint = params.optString("expires_hint", "").trim()
            if (expiresHint.isNotEmpty()) {
                put("expires_hint", expiresHint)
            }
        }
        memories.put(newMemory)

        // 持久化
        core.setSetting(SETTING_KEY, memories.toString())

        val label = categoryLabels[category] ?: category
        return JSONObject().apply {
            put("success", true)
            put("message", "已记住：[$label] $content")
            put("memory", newMemory)
        }.toString()
    }

    /**
     * 列出所有记忆，可按分类筛选。
     *
     * @param params 可包含 filter_category（可选）用于按分类筛选
     * @param core   NionCore 单例
     * @return 记忆列表的 JSON 字符串
     */
    private fun executeList(params: JSONObject, core: NionCore): String {
        val memories = loadMemories(core)
        // 可选的分类筛选
        val filterCategory = params.optString("filter_category", "").trim()
        val items = JSONArray()

        for (i in 0 until memories.length()) {
            val mem = memories.getJSONObject(i)
            // 如果指定了筛选分类，只返回匹配的记忆
            if (filterCategory.isNotEmpty() && mem.optString("category", "") != filterCategory) {
                continue
            }
            val label = categoryLabels[mem.optString("category", "other")] ?: "其他"
            items.put(JSONObject().apply {
                put("id", mem.getString("id"))
                put("content", mem.getString("content"))
                put("category", mem.optString("category", "other"))
                put("category_label", label)
                put("created_at", mem.optString("created_at", ""))
                put("updated_at", mem.optString("updated_at", ""))
                // 过期提示（仅部分记忆有此字段）
                val expiresHint = mem.optString("expires_hint", "")
                if (expiresHint.isNotEmpty()) {
                    put("expires_hint", expiresHint)
                }
            })
        }

        return JSONObject().apply {
            put("success", true)
            put("count", items.length())
            put("memories", items)
            put("message", if (items.length() == 0) "目前没有任何记忆" else "共 ${items.length()} 条记忆")
        }.toString()
    }

    /**
     * 更新一条已有记忆的内容。
     *
     * 根据 id 找到对应记忆，更新其 content 和 updated_at 时间戳。
     * 用于"正在备考"→"考完了"等情境变化。
     *
     * @param params 包含 id（必填）和 content（必填）的参数
     * @param core   NionCore 单例
     * @return 更新结果的 JSON 字符串
     */
    private fun executeUpdate(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) {
            return """{"error":"更新记忆时 id 不能为空"}"""
        }
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return """{"error":"更新记忆时 content 不能为空"}"""
        }

        val memories = loadMemories(core)
        var found = false
        val now = java.time.LocalDateTime.now().toString()

        // 遍历查找匹配的记忆并更新
        for (i in 0 until memories.length()) {
            val mem = memories.getJSONObject(i)
            if (mem.getString("id") == id) {
                mem.put("content", content)
                mem.put("updated_at", now)
                // 更新时也可修改 expires_hint
                val newExpiresHint = params.optString("expires_hint", "").trim()
                if (newExpiresHint.isNotEmpty()) {
                    mem.put("expires_hint", newExpiresHint)
                } else if (params.has("expires_hint")) {
                    // 传入空字符串表示清除 expires_hint
                    mem.remove("expires_hint")
                }
                found = true
                break
            }
        }

        if (!found) {
            return """{"error":"未找到 ID 为 $id 的记忆"}"""
        }

        // 持久化更新后的列表
        core.setSetting(SETTING_KEY, memories.toString())

        return JSONObject().apply {
            put("success", true)
            put("message", "已更新记忆")
        }.toString()
    }

    /**
     * 删除一条记忆。
     *
     * 根据 id 从记忆列表中找到并移除对应条目。
     *
     * @param params 包含 id（必填）的参数
     * @param core   NionCore 单例
     * @return 删除结果的 JSON 字符串
     */
    private fun executeRemove(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) {
            return """{"error":"删除记忆时 id 不能为空"}"""
        }

        val memories = loadMemories(core)
        var found = false

        // 遍历查找并移除匹配的记忆
        val newArr = JSONArray()
        for (i in 0 until memories.length()) {
            val mem = memories.getJSONObject(i)
            if (mem.getString("id") == id) {
                found = true
            } else {
                newArr.put(mem)
            }
        }

        if (!found) {
            return """{"error":"未找到 ID 为 $id 的记忆"}"""
        }

        // 持久化更新后的列表
        core.setSetting(SETTING_KEY, newArr.toString())

        return JSONObject().apply {
            put("success", true)
            put("message", "已删除记忆")
        }.toString()
    }

    /**
     * 从 settings 表加载记忆列表。
     *
     * @param core NionCore 单例
     * @return 记忆 JSONArray，无数据时返回空数组
     */
    fun loadMemories(core: NionCore): JSONArray {
        val json = core.getSetting(SETTING_KEY)
        return if (!json.isNullOrEmpty()) {
            JSONArray(json)
        } else {
            JSONArray()
        }
    }

    /**
     * 将记忆列表保存到 settings 表。
     * 供 ViewModel 中的 UI 管理操作调用。
     *
     * @param core    NionCore 单例
     * @param memories 要保存的记忆 JSONArray
     */
    fun saveMemories(core: NionCore, memories: JSONArray) {
        core.setSetting(SETTING_KEY, memories.toString())
    }
}
