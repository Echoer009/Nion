package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 用户偏好记忆工具 —— 让 AI 记住用户的偏好和要求。
 *
 * 当用户表达不满、提出习惯性要求或想让 Nion 记住某些规则时，
 * AI 可调用此工具将偏好持久化存储。这些偏好会在后续对话中
 * 注入系统提示词，确保 AI 始终遵守。
 *
 * 支持三种操作：
 * - add：添加一条偏好（需指定 content 和 category）
 * - list：列出所有已记住的偏好
 * - remove：删除某条偏好（需指定 id）
 *
 * 偏好分类：
 * - style（风格）：语言风格、语气等偏好
 * - behavior（行为）：AI 应该主动做什么或不做什么
 * - format（格式）：回复格式、排版等偏好
 * - other（其他）：不属于以上类别的偏好
 */
object RememberTool : Tool {
    override val name = "remember"
    override val affectsData = setOf(DataType.PREFERENCES)
    override val description = "记住用户偏好工具。当用户表达不满、提出习惯性要求、或想让 Nion 记住某条规则时使用。" +
        "通过 action 字段指定操作类型。" +
        "add：添加一条偏好（需 content + category）；" +
        "list：列出所有已记住的偏好；" +
        "remove：删除某条偏好（需 id）。"

    /** setting key，用于在 Rust 层 settings 表中读写偏好数据 */
    const val SETTING_KEY = "companion_user_preferences"

    /**
     * 分类名称映射：英文键 → 中文显示名
     * 用于工具返回结果中的可读描述
     */
    private val categoryLabels = mapOf(
        "style" to "风格",
        "behavior" to "行为",
        "format" to "格式",
        "other" to "其他",
    )

    private val schema = """
    {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "enum": ["add", "list", "remove"],
                "description": "操作类型：add=添加偏好, list=列出所有偏好, remove=删除偏好"
            },
            "content": {
                "type": "string",
                "description": "偏好内容（add 时必填），如'不要使用emoji'、'主动提醒截止日期'"
            },
            "category": {
                "type": "string",
                "enum": ["style", "behavior", "format", "other"],
                "description": "偏好分类（add 时必填）。style=语言风格, behavior=AI行为, format=回复格式, other=其他"
            },
            "id": {
                "type": "string",
                "description": "偏好 ID（remove 时必填），指定要删除的偏好条目"
            }
        },
        "required": ["action"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    /**
     * 执行偏好操作。
     *
     * 路由到对应的 add/list/remove 方法。
     * 偏好数据通过 [NionCore.getSetting]/[NionCore.setSetting] 持久化。
     *
     * @param params 已校验的参数
     * @param core   NionCore 单例，用于读写 settings
     * @return 操作结果的 JSON 字符串
     */
    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val action = params.getString("action")
        return when (action) {
            "add" -> executeAdd(params, core)
            "list" -> executeList(core)
            "remove" -> executeRemove(params, core)
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
    private fun executeAdd(params: JSONObject, core: NionCore): String {
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) {
            return """{"error":"添加偏好时 content 不能为空"}"""
        }
        val category = params.optString("category", "other")
        if (category !in categoryLabels) {
            return """{"error":"无效的分类: $category，可选: style, behavior, format, other"}"""
        }

        // 读取现有偏好列表
        val prefs = loadPreferences(core)
        val id = java.util.UUID.randomUUID().toString()
        val now = java.time.LocalDateTime.now().toString()

        // 构建新偏好项并追加
        val newPref = JSONObject().apply {
            put("id", id)
            put("content", content)
            put("category", category)
            put("created_at", now)
        }
        prefs.put(newPref)

        // 持久化
        core.setSetting(SETTING_KEY, prefs.toString())

        val label = categoryLabels[category] ?: category
        return JSONObject().apply {
            put("success", true)
            put("message", "已记住偏好：[$label] $content")
            put("preference", newPref)
        }.toString()
    }

    /**
     * 列出所有已记住的用户偏好。
     *
     * @param core NionCore 单例
     * @return 偏好列表的 JSON 字符串
     */
    private fun executeList(core: NionCore): String {
        val prefs = loadPreferences(core)
        val items = JSONArray()

        // 将每条偏好格式化为可读描述
        for (i in 0 until prefs.length()) {
            val pref = prefs.getJSONObject(i)
            val label = categoryLabels[pref.optString("category", "other")] ?: "其他"
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
            put("count", prefs.length())
            put("preferences", items)
            put("message", if (prefs.length() == 0) "目前没有记录任何偏好" else "共 ${prefs.length()} 条偏好"
            )
        }.toString()
    }

    /**
     * 删除一条用户偏好。
     *
     * 根据 id 从偏好列表中找到并移除对应条目。
     *
     * @param params 包含 id（必填）的参数
     * @param core   NionCore 单例
     * @return 删除结果的 JSON 字符串
     */
    private fun executeRemove(params: JSONObject, core: NionCore): String {
        val id = params.optString("id", "").trim()
        if (id.isEmpty()) {
            return """{"error":"删除偏好时 id 不能为空"}"""
        }

        val prefs = loadPreferences(core)
        var found = false

        // 遍历查找并移除匹配的偏好
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

        // 持久化更新后的列表
        core.setSetting(SETTING_KEY, newArr.toString())

        return JSONObject().apply {
            put("success", true)
            put("message", "已删除偏好")
        }.toString()
    }

    /**
     * 从 settings 表加载偏好列表。
     *
     * @param core NionCore 单例
     * @return 偏好 JSONArray，无数据时返回空数组
     */
    fun loadPreferences(core: NionCore): JSONArray {
        val json = core.getSetting(SETTING_KEY)
        return if (!json.isNullOrEmpty()) {
            JSONArray(json)
        } else {
            JSONArray()
        }
    }

    /**
     * 将偏好列表保存到 settings 表。
     * 供 ViewModel 中的 UI 管理操作调用。
     *
     * @param core  NionCore 单例
     * @param prefs 要保存的偏好 JSONArray
     */
    fun savePreferences(core: NionCore, prefs: JSONArray) {
        core.setSetting(SETTING_KEY, prefs.toString())
    }
}
