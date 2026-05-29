package com.echonion.nion.ui.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 规范化序列化工具 —— 保证同一个逻辑 JSON 始终产出相同的字符串。
 *
 * 解决的问题：Android 的 [JSONObject.toString()] 内部用 HashMap 存储 key-value，
 * 迭代顺序不确定，导致同一个 JSON 对象的 toString() 在不同次调用时可能产出不同字符串。
 * 这会破坏 DeepSeek Prefix Caching（要求 byte-for-byte 一致的前缀）。
 *
 * 规则：
 * - [JSONObject]：key 按字母序排列后逐个输出值
 * - [JSONArray]：保持元素原始顺序，递归规范化每个元素
 * - 基本类型（String / Number / Boolean / null）：使用 [JSONObject] 的标准转义
 */
object JsonCanonicalizer {

    /**
     * 将任意 JSON 值规范化为稳定字符串。
     *
     * 递归处理 [JSONObject]（排序 key）和 [JSONArray]（保持元素顺序），
     * 基本类型直接输出标准 JSON 表示。
     *
     * @param value JSON 值：[JSONObject]、[JSONArray]、String、Number、Boolean、null、[JSONObject.NULL]
     * @return 规范化的 JSON 字符串，同一逻辑 JSON 始终产出相同结果
     */
    fun canonicalize(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> canonicalizeObject(value)
            is JSONArray -> canonicalizeArray(value)
            is String -> JSONObject.quote(value)
            is Number -> JSONObject.numberToString(value)
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    /**
     * 将 JSONObject 的 key 按字母序排列后输出稳定的 JSON 对象字符串。
     *
     * @param obj 待序列化的 JSON 对象
     * @return key 已排序的 JSON 字符串，如 `{"a":1,"b":2}`
     */
    private fun canonicalizeObject(obj: JSONObject): String {
        // 收集所有 key 并排序，保证输出顺序跨进程/跨调用一致
        val sortedKeys = obj.keys().asSequence().toList().sorted()
        val sb = StringBuilder("{")
        sortedKeys.forEachIndexed { index, key ->
            if (index > 0) sb.append(",")
            sb.append(JSONObject.quote(key))
            sb.append(":")
            sb.append(canonicalize(obj.get(key)))
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 将 JSONArray 的每个元素递归规范化后输出 JSON 数组字符串。
     *
     * 数组元素顺序保持不变（语义上有序），但对每个元素递归调用 [canonicalize]。
     *
     * @param arr 待序列化的 JSON 数组
     * @return 规范化的 JSON 数组字符串
     */
    private fun canonicalizeArray(arr: JSONArray): String {
        val sb = StringBuilder("[")
        for (i in 0 until arr.length()) {
            if (i > 0) sb.append(",")
            sb.append(canonicalize(arr.get(i)))
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * 将 JSONObject 列表序列化为稳定的 JSON 数组字符串。
     *
     * 便捷方法，常用于将 [List] 格式的对话历史直接序列化为 API 请求或 DB 存储格式。
     * 等价于先构建 JSONArray 再调用 [canonicalize]，但避免了中间对象。
     *
     * @param list JSON 对象列表
     * @return 规范化的 JSON 数组字符串
     */
    fun stableArrayToString(list: List<JSONObject>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { index, obj ->
            if (index > 0) sb.append(",")
            sb.append(canonicalizeObject(obj))
        }
        sb.append("]")
        return sb.toString()
    }
}
