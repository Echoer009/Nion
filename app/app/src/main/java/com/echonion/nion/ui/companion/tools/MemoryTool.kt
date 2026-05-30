package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 统一记忆工具 —— 合并了原来的 remember（偏好规则）和 memory（事实记忆）。
 *
 * 通过 `scope` 参数区分两种语义：
 * - `preference`（偏好）：规范性规则 —— "AI 应该怎么做"（如"不要用 emoji"、"主动提醒截止日期"）
 *   当用户表达不满、提出习惯性要求或想让 Nion 记住某些规则时使用。
 * - `fact`（事实）：描述性知识 —— "用户是什么样的人"（如"用户是大三学生"）
 *   AI 应在对话中**主动**调用，无需等待用户明确要求。
 *
 * 支持四种操作（通过 action 字段指定）：
 * - add：添加一条记忆/偏好（需 scope + content + category）
 * - list：列出记忆/偏好（可按 category 筛选）
 * - update：更新记忆/偏好的内容（需 scope + id + content，仅 fact 支持）
 * - remove：删除记忆/偏好（需 scope + id）
 *
 * 两种 scope 的分类体系不同：
 * - preference 分类（4 类）：style（风格）、behavior（行为）、format（格式）、other（其他）
 * - fact 分类（14 类）：identity、study、work、hobby、habit、health、emotion、goal、
 *   schedule、social、location、pet、context、other
 *
 * 数据存储：两种 scope 使用独立的 setting key，合并后无需数据迁移，完全向后兼容。
 *
 * 去重/更新策略（fact scope）：
 * - 同类别下已有相关记忆时，应使用 update 而非新增（如"正在备考"→"考完了"）
 * - context 类记忆可能过期，可带 expires_hint 字段供 AI 后续判断
 */
object MemoryTool : Tool {
    override val name = "memory"
    override val affectsData = setOf(DataType.PREFERENCES, DataType.MEMORIES)
    override val description = "统一记忆工具，记住用户的偏好规则和事实性信息。" +
        "scope=preference 时记录 AI 行为偏好，如不要使用 emoji。" +
        "scope=fact 时记录关于用户的事实性信息，如用户是大三学生。" +
        "action=add 添加记忆需传 content 和 category，action=list 列出记忆可按 category 筛选，" +
        "action=update 更新记忆内容需传 id 和 content 且仅 fact 支持，action=remove 删除记忆需传 id。" +
        "preference 分类：style/behavior/format/other；" +
        "fact 分类：identity/study/work/hobby/habit/health/emotion/goal/schedule/social/location/pet/context/other。" +
        "用户表达不满或提出习惯性要求时用 preference，对话中透露个人信息或当前状态时主动用 fact 记录。"

    /** preference scope 的 setting key，用于在 Rust 层 settings 表中读写偏好数据 */
    const val PREFS_SETTING_KEY = "companion_user_preferences"

    /** fact scope 的 setting key，用于在 Rust 层 settings 表中读写事实记忆数据 */
    const val FACTS_SETTING_KEY = "companion_user_memories"

    /** 向后兼容：原 RememberTool.SETTING_KEY 的别名 */
    @Deprecated("Use PREFS_SETTING_KEY instead", ReplaceWith("PREFS_SETTING_KEY"))
    const val SETTING_KEY = FACTS_SETTING_KEY

    /**
     * preference scope 的分类名称映射：英文键 → 中文显示名
     */
    val preferenceCategoryLabels = mapOf(
        "style" to "风格",
        "behavior" to "行为",
        "format" to "格式",
        "other" to "其他",
    )

    /**
     * fact scope 的分类名称映射：英文键 → 中文显示名
     * 用于工具返回结果中的可读描述和系统提示词注入
     */
    val factCategoryLabels = mapOf(
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
     * 向后兼容：外部代码仍可通过 categoryLabels 访问 fact 分类的中文映射
     */
    val categoryLabels: Map<String, String> get() = factCategoryLabels

    /** preference scope 的有效分类键集合 */
    private val validPrefCategories = preferenceCategoryLabels.keys

    /** fact scope 的有效分类键集合 */
    private val validFactCategories = factCategoryLabels.keys

    private val schema = """
    {
        "type": "object",
        "properties": {
            "scope": {
                "type": "string",
                "enum": ["preference", "fact"],
                "description": "记忆类型：preference=AI 行为偏好如不要用 emoji，fact=关于用户的事实如用户是大三学生"
            },
            "action": {
                "type": "string",
                "enum": ["add", "list", "remove", "update"],
                "description": "操作类型：add=添加, list=列出, update=更新内容仅 fact 支持, remove=删除"
            },
            "content": {
                "type": "string",
                "description": "记忆或偏好内容"
            },
            "category": {
                "type": "string",
                "description": "分类。preference 可选 style/behavior/format/other，fact 可选 identity/study/work/hobby/habit/health/emotion/goal/schedule/social/location/pet/context/other"
            },
            "id": {
                "type": "string",
                "description": "记忆或偏好的 ID，指定要操作的条目"
            },
            "expires_hint": {
                "type": "string",
                "description": "记忆的预期过期时间，格式为日期字符串如 2026-06-10，适用于 context 等短期记忆，长期记忆不需要此字段"
            },
            "filter_category": {
                "type": "string",
                "description": "按分类筛选，不传则返回所有条目"
            }
        },
        "required": ["scope", "action"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    /**
     * 执行记忆操作。
     *
     * 根据 scope 路由到 preference 或 fact 的处理逻辑，
     * 再根据 action 路由到 add/list/update/remove 方法。
     *
     * @param params 已校验的参数，必须包含 scope 和 action
     * @param core   NionCore 单例，用于读写 settings
     * @return 操作结果的 JSON 字符串
     */
    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val scope = params.getString("scope")
        val action = params.getString("action")

        return when (scope) {
            "preference" -> executePreferenceAction(action, params, core)
            "fact" -> executeFactAction(action, params, core)
            else -> """{"error":"不支持的 scope: $scope"}"""
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // preference scope 的操作路由
    // ═══════════════════════════════════════════════════════════════

    /**
     * preference scope 的操作路由。
     * preference 不支持 update（偏好通常是增删，不需要更新）。
     */
    private fun executePreferenceAction(action: String, params: JSONObject, core: NionCore): String {
        return when (action) {
            "add" -> executeAddPreference(params, core)
            "list" -> executeListPreferences(params, core)
            "remove" -> executeRemovePreference(params, core)
            "update" -> """{"error":"preference scope 不支持 update 操作，请先 remove 再 add"}"""
            else -> """{"error":"不支持的操作: $action"}"""
        }
    }

    /**
     * 添加一条用户偏好。
     *
     * 从参数中提取 content 和 category，生成 UUID 作为 ID，
     * 追加到现有偏好列表后持久化存储。
     *
     * @param params 包含 content（必填）和 category（必填）的参数
     * @param core   NionCore 单例
     * @return 添加结果的 JSON 字符串
     */
    private fun executeAddPreference(params: JSONObject, core: NionCore): String {
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return """{"error":"添加偏好时 content 不能为空"}"""
        }
        val category = params.optString("category", "other")
        if (category !in validPrefCategories) {
            return """{"error":"无效的偏好分类: $category，可选: ${validPrefCategories.joinToString(", ")}"}"""
        }

        val prefs = loadFromSettings(core, PREFS_SETTING_KEY)
        val id = java.util.UUID.randomUUID().toString()
        val now = java.time.LocalDateTime.now().toString()

        val newPref = JSONObject().apply {
            put("id", id)
            put("content", content)
            put("category", category)
            put("created_at", now)
        }
        prefs.put(newPref)

        core.setSetting(PREFS_SETTING_KEY, prefs.toString())

        val label = preferenceCategoryLabels[category] ?: category
        return JSONObject().apply {
            put("success", true)
            put("scope", "preference")
            put("message", "已记住偏好：[$label] $content")
            put("item", newPref)
        }.toString()
    }

    /**
     * 列出所有用户偏好，可按分类筛选。
     *
     * @param params 可包含 filter_category（可选）用于按分类筛选
     * @param core   NionCore 单例
     * @return 偏好列表的 JSON 字符串
     */
    private fun executeListPreferences(params: JSONObject, core: NionCore): String {
        val prefs = loadFromSettings(core, PREFS_SETTING_KEY)
        val filterCategory = params.optString("filter_category", "").trim()
        val items = JSONArray()

        for (i in 0 until prefs.length()) {
            val pref = prefs.getJSONObject(i)
            if (filterCategory.isNotEmpty() && pref.optString("category", "") != filterCategory) {
                continue
            }
            val label = preferenceCategoryLabels[pref.optString("category", "other")] ?: "其他"
            items.put(JSONObject().apply {
                put("id", pref.getString("id"))
                put("content", pref.getString("content"))
                put("category", pref.optString("category", "other"))
                put("category_label", label)
                put("created_at", pref.optString("created_at", ""))
            })
        }

        return JSONObject().apply {
            put("success", true)
            put("scope", "preference")
            put("count", items.length())
            put("items", items)
            put("message", if (items.length() == 0) "目前没有记录任何偏好" else "共 ${items.length()} 条偏好")
        }.toString()
    }

    /**
     * 删除一条用户偏好。
     *
     * @param params 包含 id（必填）的参数
     * @param core   NionCore 单例
     * @return 删除结果的 JSON 字符串
     */
    private fun executeRemovePreference(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) {
            return """{"error":"删除偏好时 id 不能为空"}"""
        }

        val prefs = loadFromSettings(core, PREFS_SETTING_KEY)
        var found = false

        val newArr = JSONArray()
        for (i in 0 until prefs.length()) {
            val pref = prefs.getJSONObject(i)
            if (pref.getString("id") == id) {
                found = true
            } else {
                newArr.put(pref)
            }
        }

        if (!found) {
            return """{"error":"未找到 ID 为 $id 的偏好"}"""
        }

        core.setSetting(PREFS_SETTING_KEY, newArr.toString())

        return JSONObject().apply {
            put("success", true)
            put("scope", "preference")
            put("message", "已删除偏好")
        }.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    // fact scope 的操作路由
    // ═══════════════════════════════════════════════════════════════

    /**
     * fact scope 的操作路由。
     * 支持 add/list/update/remove 四种操作。
     */
    private fun executeFactAction(action: String, params: JSONObject, core: NionCore): String {
        return when (action) {
            "add" -> executeAddFact(params, core)
            "list" -> executeListFacts(params, core)
            "update" -> executeUpdateFact(params, core)
            "remove" -> executeRemoveFact(params, core)
            else -> """{"error":"不支持的操作: $action"}"""
        }
    }

    /**
     * 添加一条事实记忆。
     *
     * 从参数中提取 content、category 和可选的 expires_hint，
     * 生成 UUID 作为 ID，追加到现有记忆列表后持久化存储。
     *
     * @param params 包含 content（必填）、category（必填）、expires_hint（可选）的参数
     * @param core   NionCore 单例
     * @return 添加结果的 JSON 字符串
     */
    private fun executeAddFact(params: JSONObject, core: NionCore): String {
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return """{"error":"添加记忆时 content 不能为空"}"""
        }
        val category = params.optString("category", "other")
        if (category !in validFactCategories) {
            return """{"error":"无效的分类: $category，可选: ${validFactCategories.joinToString(", ")}"}"""
        }

        val memories = loadFromSettings(core, FACTS_SETTING_KEY)
        val id = java.util.UUID.randomUUID().toString()
        val now = java.time.LocalDateTime.now().toString()

        val newMemory = JSONObject().apply {
            put("id", id)
            put("content", content)
            put("category", category)
            put("created_at", now)
            put("updated_at", now)
            val expiresHint = params.optString("expires_hint", "").trim()
            if (expiresHint.isNotEmpty()) {
                put("expires_hint", expiresHint)
            }
        }
        memories.put(newMemory)

        core.setSetting(FACTS_SETTING_KEY, memories.toString())

        val label = factCategoryLabels[category] ?: category
        return JSONObject().apply {
            put("success", true)
            put("scope", "fact")
            put("message", "已记住：[$label] $content")
            put("item", newMemory)
        }.toString()
    }

    /**
     * 列出所有事实记忆，可按分类筛选。
     *
     * @param params 可包含 filter_category（可选）用于按分类筛选
     * @param core   NionCore 单例
     * @return 记忆列表的 JSON 字符串
     */
    private fun executeListFacts(params: JSONObject, core: NionCore): String {
        val memories = loadFromSettings(core, FACTS_SETTING_KEY)
        val filterCategory = params.optString("filter_category", "").trim()
        val items = JSONArray()

        for (i in 0 until memories.length()) {
            val mem = memories.getJSONObject(i)
            if (filterCategory.isNotEmpty() && mem.optString("category", "") != filterCategory) {
                continue
            }
            val label = factCategoryLabels[mem.optString("category", "other")] ?: "其他"
            items.put(JSONObject().apply {
                put("id", mem.getString("id"))
                put("content", mem.getString("content"))
                put("category", mem.optString("category", "other"))
                put("category_label", label)
                put("created_at", mem.optString("created_at", ""))
                put("updated_at", mem.optString("updated_at", ""))
                val expiresHint = mem.optString("expires_hint", "")
                if (expiresHint.isNotEmpty()) {
                    put("expires_hint", expiresHint)
                }
            })
        }

        return JSONObject().apply {
            put("success", true)
            put("scope", "fact")
            put("count", items.length())
            put("items", items)
            put("message", if (items.length() == 0) "目前没有任何记忆" else "共 ${items.length()} 条记忆")
        }.toString()
    }

    /**
     * 更新一条已有事实记忆的内容。
     *
     * 根据 id 找到对应记忆，更新其 content 和 updated_at 时间戳。
     * 用于"正在备考"→"考完了"等情境变化。
     *
     * @param params 包含 id（必填）和 content（必填）的参数
     * @param core   NionCore 单例
     * @return 更新结果的 JSON 字符串
     */
    private fun executeUpdateFact(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) {
            return """{"error":"更新记忆时 id 不能为空"}"""
        }
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return """{"error":"更新记忆时 content 不能为空"}"""
        }

        val memories = loadFromSettings(core, FACTS_SETTING_KEY)
        var found = false
        val now = java.time.LocalDateTime.now().toString()

        for (i in 0 until memories.length()) {
            val mem = memories.getJSONObject(i)
            if (mem.getString("id") == id) {
                mem.put("content", content)
                mem.put("updated_at", now)
                val newExpiresHint = params.optString("expires_hint", "").trim()
                if (newExpiresHint.isNotEmpty()) {
                    mem.put("expires_hint", newExpiresHint)
                } else if (params.has("expires_hint")) {
                    mem.remove("expires_hint")
                }
                found = true
                break
            }
        }

        if (!found) {
            return """{"error":"未找到 ID 为 $id 的记忆"}"""
        }

        core.setSetting(FACTS_SETTING_KEY, memories.toString())

        return JSONObject().apply {
            put("success", true)
            put("scope", "fact")
            put("message", "已更新记忆")
        }.toString()
    }

    /**
     * 删除一条事实记忆。
     *
     * @param params 包含 id（必填）的参数
     * @param core   NionCore 单例
     * @return 删除结果的 JSON 字符串
     */
    private fun executeRemoveFact(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) {
            return """{"error":"删除记忆时 id 不能为空"}"""
        }

        val memories = loadFromSettings(core, FACTS_SETTING_KEY)
        var found = false

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

        core.setSetting(FACTS_SETTING_KEY, newArr.toString())

        return JSONObject().apply {
            put("success", true)
            put("scope", "fact")
            put("message", "已删除记忆")
        }.toString()
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共辅助方法 —— 供 ViewModel 调用
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 settings 表加载 JSON 数组。
     *
     * @param core NionCore 单例
     * @param key  settings 表中的键名
     * @return JSONArray，无数据时返回空数组
     */
    private fun loadFromSettings(core: NionCore, key: String): JSONArray {
        val json = core.getSetting(key)
        return if (!json.isNullOrEmpty()) {
            JSONArray(json)
        } else {
            JSONArray()
        }
    }

    /**
     * 从 settings 表加载偏好列表。
     * 供 ViewModel 中的 UI 管理操作调用。
     *
     * @param core NionCore 单例
     * @return 偏好 JSONArray，无数据时返回空数组
     */
    fun loadPreferences(core: NionCore): JSONArray = loadFromSettings(core, PREFS_SETTING_KEY)

    /**
     * 将偏好列表保存到 settings 表。
     * 供 ViewModel 中的 UI 管理操作调用。
     *
     * @param core  NionCore 单例
     * @param prefs 要保存的偏好 JSONArray
     */
    fun savePreferences(core: NionCore, prefs: JSONArray) {
        core.setSetting(PREFS_SETTING_KEY, prefs.toString())
    }

    /**
     * 从 settings 表加载事实记忆列表。
     * 供 ViewModel 中的 UI 管理操作调用。
     *
     * @param core NionCore 单例
     * @return 记忆 JSONArray，无数据时返回空数组
     */
    fun loadMemories(core: NionCore): JSONArray = loadFromSettings(core, FACTS_SETTING_KEY)

    /**
     * 将事实记忆列表保存到 settings 表。
     * 供 ViewModel 中的 UI 管理操作调用。
     *
     * @param core     NionCore 单例
     * @param memories 要保存的记忆 JSONArray
     */
    fun saveMemories(core: NionCore, memories: JSONArray) {
        core.setSetting(FACTS_SETTING_KEY, memories.toString())
    }
}
