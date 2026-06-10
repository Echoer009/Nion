package com.echonion.nion.ui.companion.phoneagent

import android.util.Log

/**
 * AutoGLM 模型响应解析器，与 Python 源码 _parse_response + parse_action 逻辑一致。
 *
 * 解析优先级（与 Python _parse_response 一致）：
 * 1. finish(message= → finish 动作
 * 2. do(action= → do 动作
 * 3. <answer> 标签 → legacy 格式
 * 4. 全文作为 action
 *
 * 动作参数解析（与 Python parse_action 一致）：
 * - do(action="Tap", element=[x,y])
 * - do(action="Type", text="xxx")
 * - finish(message="xxx")
 */
object PhoneActionParser {

    private const val TAG = "PhoneActionParser"

    data class ParsedAction(
        val action: String,
        val params: Map<String, Any?>,
        val thinking: String,
        val finished: Boolean,
        /** 原始 action 字符串（从 do(action= 或 finish(message= 开始），用于写入上下文 */
        val rawAction: String = "",
    )

    /**
     * 解析 AutoGLM 模型的完整响应文本。
     * 优先级与 Python _parse_response 一致。
     */
    fun parse(rawContent: String): ParsedAction {
        Log.d(TAG, "解析响应: ${rawContent.take(200)}")

        // Rule 1: finish(message=
        if (rawContent.contains("finish(message=")) {
            val splitIndex = rawContent.indexOf("finish(message=")
            val thinking = rawContent.substring(0, splitIndex).trim()
            val actionStr = rawContent.substring(splitIndex).trim()
            val message = parseFinishMessage(actionStr)
            return ParsedAction("finish", mapOf("message" to message), thinking, true, actionStr)
        }

        // Rule 2: do(action=
        if (rawContent.contains("do(action=")) {
            val splitIndex = rawContent.indexOf("do(action=")
            val thinking = rawContent.substring(0, splitIndex).trim()
            val actionStr = rawContent.substring(splitIndex).trim()
            return try {
                parseDoAction(actionStr, thinking)
            } catch (e: Exception) {
                Log.e(TAG, "动作解析失败", e)
                ParsedAction("finish", mapOf("message" to "动作解析失败: ${e.message}"), thinking, true, actionStr)
            }
        }

        // Rule 3: <answer> legacy 格式
        if (rawContent.contains("<answer>") && rawContent.contains("</answer>")) {
            var thinking = ""
            if (rawContent.contains("༧") && rawContent.contains("ཊ")) {
                val thinkStart = rawContent.indexOf("༧") + 7
                val thinkEnd = rawContent.indexOf("ཊ")
                if (thinkStart < thinkEnd) {
                    thinking = rawContent.substring(thinkStart, thinkEnd).trim()
                }
            }
            val answerStart = rawContent.indexOf("<answer>") + 8
            val answerEnd = rawContent.indexOf("</answer>")
            val actionStr = rawContent.substring(answerStart, answerEnd).trim()

            if (actionStr.contains("finish(message=")) {
                val finishIdx = actionStr.indexOf("finish(message=")
                val rawAct = actionStr.substring(finishIdx)
                val message = parseFinishMessage(rawAct)
                return ParsedAction("finish", mapOf("message" to message), thinking, true, rawAct)
            }
            if (actionStr.contains("do(action=")) {
                val doIndex = actionStr.indexOf("do(action=")
                val rawAct = actionStr.substring(doIndex)
                thinking = (thinking + " " + actionStr.substring(0, doIndex)).trim()
                return parseDoAction(rawAct, thinking)
            }
        }

        // Rule 4: 无法解析，全文作为 finish message
        Log.w(TAG, "无法识别动作格式，全文作为 finish message")
        return ParsedAction("finish", mapOf("message" to rawContent.trim()), "", true, rawContent.trim())
    }

    private fun parseDoAction(actionStr: String, thinking: String): ParsedAction {
        val action = extractStringValue(actionStr, "action=\"", "\"")
            ?: extractSingleQuoted(actionStr, "action=")
            ?: "unknown"

        val params = mutableMapOf<String, Any?>()

        when (action) {
            "Tap", "Double Tap", "Long Press" -> {
                extractListValue(actionStr, "element=[")?.let { params["element"] = it }
                extractStringValue(actionStr, "message=\"", "\"")?.let { params["message"] = it }
            }
            "Swipe" -> {
                extractListValue(actionStr, "start=[")?.let { params["start"] = it }
                extractListValue(actionStr, "end=[")?.let { params["end"] = it }
            }
            "Type", "Type_Name" -> {
                params["text"] = extractStringValue(actionStr, "text=\"", "\"")
                    ?: extractSingleQuoted(actionStr, "text=") ?: ""
            }
            "Launch" -> {
                params["app"] = extractStringValue(actionStr, "app=\"", "\"")
                    ?: extractSingleQuoted(actionStr, "app=") ?: ""
            }
            "Wait" -> {
                params["duration"] = extractStringValue(actionStr, "duration=\"", "\"") ?: "1 seconds"
            }
            "Take_over" -> {
                params["message"] = extractStringValue(actionStr, "message=\"", "\"") ?: "需要人工操作"
            }
            "Back", "Home", "Interact", "Note", "Call_API" -> { }
        }

        return ParsedAction(action, params.toMap(), thinking, false, actionStr)
    }

    private fun parseFinishMessage(actionStr: String): String {
        return extractStringValue(actionStr, "message=\"", "\"")
            ?: extractSingleQuoted(actionStr, "message=")
            ?: "任务完成"
    }

    private fun extractStringValue(source: String, prefix: String, suffix: String): String? {
        val startIndex = source.indexOf(prefix)
        if (startIndex == -1) return null
        val valueStart = startIndex + prefix.length
        val endIndex = source.indexOf(suffix, valueStart)
        if (endIndex == -1) return null
        return source.substring(valueStart, endIndex)
    }

    private fun extractSingleQuoted(source: String, key: String): String? {
        val startIndex = source.indexOf(key)
        if (startIndex == -1) return null
        val valueStart = startIndex + key.length
        if (valueStart >= source.length) return null
        val quote = source[valueStart]
        if (quote != '\'' && quote != '"') return null
        val endIndex = source.indexOf(quote, valueStart + 1)
        if (endIndex == -1) return null
        return source.substring(valueStart + 1, endIndex)
    }

    private fun extractListValue(source: String, prefix: String): List<Int>? {
        val startIndex = source.indexOf(prefix)
        if (startIndex == -1) return null
        val arrayStart = startIndex + prefix.length
        var depth = 0
        var arrayEnd = -1
        for (i in arrayStart until source.length) {
            when (source[i]) {
                '[' -> depth++
                ']' -> {
                    if (depth == 0) {
                        arrayEnd = i
                        break
                    }
                    depth--
                }
            }
        }
        if (arrayEnd == -1) return null
        val arrayContent = source.substring(arrayStart, arrayEnd)
        return try {
            arrayContent.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toDouble().toInt() }
        } catch (e: Exception) {
            Log.e(TAG, "数组解析失败: $arrayContent", e)
            null
        }
    }
}
