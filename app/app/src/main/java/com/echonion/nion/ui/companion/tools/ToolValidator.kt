package com.echonion.nion.ui.companion.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pydantic 风格参数校验器 —— 根据 JSON Schema 校验 LLM 传来的工具参数。
 *
 * 校验流程类似 Python pydantic：
 * 1. 检查所有 required 字段是否存在
 * 2. 检查每个字段的类型是否匹配（string / integer / number / boolean / array / object）
 * 3. 检查 enum 约束是否满足
 *
 * 校验失败时返回 [ValidationResult]，包含详细的错误信息列表，
 * 格式为 "字段名: 错误原因"，方便 LLM 理解并修正参数。
 */
object ToolValidator {

    /**
     * 校验结果。
     *
     * @property isValid 是否全部校验通过
     * @property errors  校验失败的错误信息列表，每条格式为 "字段名: 原因"
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
    )

    /**
     * 对参数执行完整的 JSON Schema 校验。
     *
     * @param params LLM 传入的参数对象
     * @param schema 工具定义的 JSON Schema（必须含 properties 和 required）
     * @return 校验结果
     */
    fun validate(params: JSONObject, schema: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()

        // 获取 Schema 中的属性定义和必填字段列表
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required")

        // 第一步：检查 required 字段是否存在
        if (required != null) {
            for (i in 0 until required.length()) {
                val field = required.getString(i)
                if (!params.has(field) || params.isNull(field)) {
                    errors.add("$field: 必填字段缺失")
                }
            }
        }

        // 第二步：逐字段检查类型和 enum 约束
        val propKeys = properties.keys()
        while (propKeys.hasNext()) {
            val field = propKeys.next()
            // 只校验参数中实际存在的字段（非必填的可以不传）
            if (!params.has(field) || params.isNull(field)) continue

            val value = params.get(field)
            val fieldSchema = properties.getJSONObject(field)

            // 类型校验
            val typeError = validateType(field, value, fieldSchema)
            if (typeError != null) {
                errors.add(typeError)
                continue // 类型不匹配时跳过后续校验
            }

            // enum 校验
            val enumError = validateEnum(field, value, fieldSchema)
            if (enumError != null) {
                errors.add(enumError)
            }
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
        )
    }

    /**
     * 校验字段值的类型是否匹配 Schema 定义。
     *
     * 支持的类型：string, integer, number, boolean, array, object
     * 注意：JSON 中 integer 和 number 都是 Number 类型，此处区分 int vs float。
     *
     * @param field       字段名
     * @param value       字段值
     * @param fieldSchema 该字段的 Schema 定义
     * @return 错误信息，null 表示通过
     */
    private fun validateType(field: String, value: Any, fieldSchema: JSONObject): String? {
        val expectedType = fieldSchema.optString("type", "") ?: return null
        if (expectedType.isEmpty()) return null

        val actualType = when (value) {
            is String -> "string"
            is Int -> "integer"
            is Long -> "integer"
            is Float -> "number"
            is Double -> "number"
            is Boolean -> "boolean"
            is JSONArray -> "array"
            is JSONObject -> "object"
            else -> "unknown"
        }

        // integer 是 number 的子类型，number 类型也接受 integer
        if (expectedType == "number" && actualType == "integer") return null

        return if (actualType == expectedType) null
        else "$field: 类型错误，期望 $expectedType，实际 $actualType"
    }

    /**
     * 校验字段值是否在 Schema 定义的 enum 列表中。
     *
     * @param field       字段名
     * @param value       字段值
     * @param fieldSchema 该字段的 Schema 定义（可能含 enum 数组）
     * @return 错误信息，null 表示通过
     */
    private fun validateEnum(field: String, value: Any, fieldSchema: JSONObject): String? {
        val enumArray = fieldSchema.optJSONArray("enum") ?: return null

        val allowedValues = (0 until enumArray.length()).map { enumArray.get(it).toString() }
        val valueStr = value.toString()

        return if (allowedValues.contains(valueStr)) null
        else "$field: 值 '$valueStr' 不在允许范围 [${allowedValues.joinToString(", ")}] 内"
    }
}
