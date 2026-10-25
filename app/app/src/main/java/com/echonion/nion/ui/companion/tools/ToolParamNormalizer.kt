package com.echonion.nion.ui.companion.tools

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数规范化器 —— 在参数校验前修正 LLM 常见的参数偏差。
 *
 * 背景：实测发现 LLM（尤其是小参数模型）在 function calling 时经常出现三类偏差，
 * 每一类都会让 [ToolValidator] 判为校验失败，导致工具调用反复重试仍失败，
 * 用户侧表现为"AI 无法顺畅地建任务"：
 *
 * 1. **字段名偏差**：使用常识字段名而非 schema 定义的字段名。
 *    最典型的是传 `title` 而不是 `name`（OpenAI 官方示例、大多数应用的
 *    任务 schema 都用 title），以及传 `due_date` 而不是 `reminder`。
 *    旧逻辑把未知字段直接判为校验失败，LLM 收到报错后重试时往往
 *    换一个仍然错误的字段名，陷入循环。
 * 2. **枚举值偏差**：传中文或大写枚举值（如 priority="高"、"High"）。
 *    enum 校验是大小写敏感的精确匹配，直接失败。
 * 3. **批量元素偏差**：批量 items 数组的每个元素里犯同样的字段名错误。
 *
 * 修正策略（按顺序执行）：
 * - **别名映射**：把高置信度的常见字段名映射到 schema 定义的字段。
 *   仅当目标字段不存在或为空时填充，绝不覆盖 LLM 显式传的正确字段。
 * - **枚举归一**：首尾空格与大小写折叠 + 中英文对照 + 英文复数折叠。
 * - **未知字段剔除**：schema 未定义且无别名映射的字段直接移除（打日志）。
 *   LLM 编造的额外字段（如 tags、id、status）静默丢弃，比让整次工具
 *   调用失败更符合用户预期——任务仍然建得出来，多余的描述性字段本就无处安放。
 */
object ToolParamNormalizer {

    private const val TAG = "ToolParamNormalizer"

    /**
     * 高置信度字段别名表：LLM 常用字段名 → schema 字段名。
     * 仅收录在任务/清单语境下几乎无歧义的映射，宁缺毋滥——
     * 错误映射（例如把正文 content 映射成任务名）比丢弃更糟。
     */
    private val fieldAliases: Map<String, String> = mapOf(
        // 实体名称：LLM 极常用 title 指代 name
        "title" to "name",
        "task_name" to "name",
        "task_title" to "name",
        "entity_name" to "name",
        // 描述/备注：content 在任务语境下是正文描述，不是名称
        "desc" to "description",
        "detail" to "description",
        "details" to "description",
        "note" to "description",
        "notes" to "description",
        "content" to "description",
        "备注" to "description",
        // 归属清单：LLM 常用 list / category 指代 checklist
        "list" to "checklist",
        "list_name" to "checklist",
        "checklist_name" to "checklist",
        "category" to "checklist",
        "category_name" to "checklist",
        // 分组
        "group_name" to "group",
        // 父任务
        "parent_name" to "parent",
        "parent_task" to "parent",
        "parent_task_name" to "parent",
        // 提醒时间：一次性/循环提醒的常见别名（date/time 在 create/update
        // 语境下几乎只会指提醒时间，收进来收益明显）
        "due" to "reminder",
        "due_date" to "reminder",
        "due_time" to "reminder",
        "datetime" to "reminder",
        "date_time" to "reminder",
        "deadline" to "reminder",
        "remind_at" to "reminder",
        "remind_time" to "reminder",
        "time" to "reminder",
        "date" to "reminder",
        // 优先级
        "importance" to "priority",
        "urgency" to "priority",
        // 实体类型
        "type" to "entity_type",
        "entity" to "entity_type",
        "object_type" to "entity_type",
    )

    /**
     * 枚举值的中文 → 英文对照表。
     * 注意：同一个中文词在不同枚举字段下含义可能冲突（如"待办"既可能是
     * entity_type=task 也可能是 status=todo），冲突词条不收录，只收
     * 无歧义映射；有歧义的值交回校验器报错引导 LLM 自行修正。
     */
    private val enumTranslations: Map<String, String> = mapOf(
        // priority 优先级
        "高" to "high", "中" to "medium", "低" to "low",
        "高优先级" to "high", "中优先级" to "medium", "低优先级" to "low",
        "紧急" to "high", "重要" to "high", "一般" to "medium", "普通" to "medium",
        // entity_type 实体类型
        "任务" to "task", "清单" to "checklist", "分组" to "group", "笔记" to "note",
        "待办" to "task", "事项" to "task", "列表" to "checklist", "笔记型" to "notebook",
        // checklist_type 清单类型
        "笔记本" to "notebook", "任务型" to "task",
        // memory scope 记忆范围
        "偏好" to "preference", "事实" to "fact", "记忆" to "fact",
        // status 任务状态
        "未完成" to "todo", "已完成" to "done", "完成" to "done",
        // recurrence 循环规则
        "每日" to "daily", "每天" to "daily", "不循环" to "none", "无" to "none",
    )

    /**
     * 规范化工具参数（原地修改并返回同一对象，便于调用方直接使用）。
     *
     * 调用时机：[ToolExecutor.execute] 在 JSON 解析成功后、[ToolValidator.validate]
     * 之前调用。规范化后校验几乎总能通过，工具得以真正执行。
     *
     * @param params LLM 传来的原始参数对象（会被原地修改）
     * @param schema 工具的参数 Schema（决定哪些字段合法、哪些字段有 enum 约束）
     * @return 规范化后的参数对象（与传入对象为同一实例）
     */
    fun normalize(params: JSONObject, schema: JSONObject): JSONObject {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        // 外层参数规范化（别名映射 / 枚举归一 / 未知字段剔除）
        normalizeObject(params, properties)

        // 批量模式：items 数组内的每个元素同样规范化。
        // schema 对 items 元素只声明了 {"type":"object"}，没有独立子 schema，
        // 用外层 properties 作为合法字段参考——批量元素的合法字段本来就是
        // 外层字段的子集（executeBatchCreate 的继承逻辑如此约定）。
        val items = params.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val element = items.optJSONObject(i) ?: continue
                normalizeObject(element, properties)
            }
        }
        return params
    }

    /**
     * 对单个参数对象执行别名映射 / 枚举归一 / 未知字段剔除（原地修改）。
     *
     * @param obj        参数对象（LLM 的直接参数，或 items 数组中的元素）
     * @param properties 该层级的 schema 属性定义，用作合法字段集合与 enum 来源
     */
    private fun normalizeObject(obj: JSONObject, properties: JSONObject) {
        // ── 第一步：别名映射 ──
        // 仅当目标字段不存在或为空时才用别名填充，绝不覆盖 LLM 显式传的正确字段。
        // 先快照键集合再遍历：映射过程会写入新键，避免 ConcurrentModificationException。
        for (alias in fieldAliases.keys.toList()) {
            if (!obj.has(alias)) continue
            val target = fieldAliases.getValue(alias)
            val targetIsEmpty = !obj.has(target) || obj.isNull(target) ||
                (obj.opt(target) is String && obj.optString(target).isEmpty())
            if (targetIsEmpty) {
                obj.put(target, obj.get(alias))
                Log.d(TAG, "别名映射: $alias → $target")
            }
            // 无论是否成功映射，别名键本身都不在 schema 中，统一交由第三步剔除
        }

        // ── 第二步：枚举归一 ──
        // 只处理 schema 声明了 enum 约束的字符串字段
        for (field in obj.keys().asSequence().toList()) {
            val fieldSchema = properties.optJSONObject(field) ?: continue
            val enumArray = fieldSchema.optJSONArray("enum") ?: continue
            if (obj.opt(field) !is String) continue
            val value = obj.getString(field)
            val normalized = normalizeEnumValue(value, enumArray)
            if (normalized != null && normalized != value) {
                obj.put(field, normalized)
                Log.d(TAG, "枚举归一: $field '$value' → '$normalized'")
            }
        }

        // ── 第三步：未知字段剔除 ──
        // schema 未定义的字段（含已处理的别名键）直接移除。
        // 旧逻辑在此判"未知参数"导致整次调用失败，这正是 AI 建任务反复失败的
        // 根因之一；现在改为静默剔除并打日志，丢弃编造字段不影响任务创建。
        for (key in obj.keys().asSequence().toList()) {
            if (!properties.has(key)) {
                val removed = obj.remove(key)
                Log.w(TAG, "剔除未知字段: $key = $removed（schema 未定义，已忽略）")
            }
        }
    }

    /**
     * 将枚举值归一到 schema 允许的取值列表内。
     *
     * 依次尝试：原文匹配 → 去首尾空格 + 小写匹配 → 中文对照匹配 →
     * 英文复数折叠（tasks → task）。全部失败返回 null，
     * 交由 [ToolValidator] 报错引导 LLM 在下一轮修正。
     *
     * @param value     LLM 传来的原始枚举值
     * @param enumArray schema 允许的枚举值数组（字符串数组）
     * @return 归一后的合法枚举值；无法归一时返回 null
     */
    private fun normalizeEnumValue(value: String, enumArray: JSONArray): String? {
        // 允许值列表（快照，避免多次 length() 调用）
        val allowed = (0 until enumArray.length()).map { enumArray.getString(it) }
        // 原文命中，无需处理
        if (value in allowed) return value

        // 首尾空格与大小写折叠（"High " → "high"）
        val folded = value.trim().lowercase()
        if (folded in allowed) return folded

        // 中文 → 英文对照（"高" → "high"）
        val translated = enumTranslations[value.trim()] ?: enumTranslations[folded]
        if (translated != null && translated in allowed) return translated

        // 英文复数折叠（"tasks" → "task"），仅尝试去尾 s / es
        for (suffix in listOf("s", "es")) {
            val singular = folded.removeSuffix(suffix)
            if (folded.endsWith(suffix) && singular in allowed) return singular
        }
        return null
    }
}
