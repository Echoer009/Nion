package com.echonion.nion.ui.companion.phoneagent

import android.util.Log
import org.json.JSONObject

/**
 * AutoGLM 模型响应解析器。
 *
 * AutoGLM-Phone-9B 模型的输出格式为：
 * ```
 * 遐{思考过程} 😄
 * <answer>{动作指令}</answer>
 * ```
 *
 * 其中 {动作指令} 的格式为：
 * - do(action="Tap", element=[x,y])     → 点击
 * - do(action="Swipe", start=[x1,y1], end=[x2,y2]) → 滑动
 * - do(action="Type", text="xxx")      → 输入文本
 * - do(action="Launch", app="xxx")     → 启动应用
 * - do(action="Back")                  → 返回
 * - do(action="Home")                  → 桌面
 * - do(action="Wait", duration="x seconds") → 等待
 * - do(action="Long Press", element=[x,y]) → 长按
 * - do(action="Double Tap", element=[x,y]) → 双击
 * - do(action="Take_over", message="xxx") → 人工接管
 * - finish(message="xxx")              → 任务完成
 *
 * 坐标范围：0-999（相对坐标），由上层转换为绝对像素。
 */
object PhoneActionParser {

    private const val TAG = "PhoneActionParser"

    /**
     * 解析后的动作。
     *
     * @property action 动作类型（Tap, Swipe, Type, Launch, Back, Home, Wait, Long Press, Double Tap, Take_over, finish）
     * @property params 动作参数字典
     * @property thinking 模型的思考过程文本
     * @property finished 是否为 finish（任务完成）
     */
    data class ParsedAction(
        val action: String,
        val params: Map<String, Any?>,
        val thinking: String,
        val finished: Boolean,
    )

    /**
     * 解析 AutoGLM 模型的完整响应文本。
     *
     * @param rawContent 模型返回的完整文本（包含思考 + 动作）
     * @return 解析后的动作，解析失败时返回 finish 动作
     */
    fun parse(rawContent: String): ParsedAction {
        Log.d(TAG, "解析响应: ${rawContent.take(200)}")

        var thinking = ""
        var actionStr = ""

        // 优先处理标准格式：<think>...</think><answer>...</answer>
        if (rawContent.contains("<answer>") && rawContent.contains("</answer>")) {
            // 提取思考过程
            if (rawContent.contains("<think>") && rawContent.contains("</think>")) {
                val thinkStart = rawContent.indexOf("<think>") + 7
                val thinkEnd = rawContent.indexOf("</think>")
                thinking = rawContent.substring(thinkStart, thinkEnd).trim()
            }
            // 提取动作
            val answerStart = rawContent.indexOf("<answer>") + 8
            val answerEnd = rawContent.indexOf("</answer>")
            actionStr = rawContent.substring(answerStart, answerEnd).trim()

            if (actionStr.startsWith("finish(message=")) {
                return parseFinishMessage(actionStr).let { msg ->
                    ParsedAction("finish", mapOf("message" to msg), thinking, true)
                }
            }
            if (actionStr.startsWith("do(action=")) {
                return parseDoAction(actionStr, thinking)
            }
        }

        // 回退：直接匹配 finish(message=...)
        if (rawContent.contains("finish(message=")) {
            val splitIndex = rawContent.indexOf("finish(message=")
            thinking = rawContent.substring(0, splitIndex).trim()
            actionStr = rawContent.substring(splitIndex).trim()
            return try {
                val message = parseFinishMessage(actionStr)
                ParsedAction("finish", mapOf("message" to message), thinking, true)
            } catch (e: Exception) {
                ParsedAction("finish", mapOf("message" to "任务完成"), thinking, true)
            }
        }

        // 回退：直接匹配 do(action=...)
        if (rawContent.contains("do(action=")) {
            val splitIndex = rawContent.indexOf("do(action=")
            thinking = rawContent.substring(0, splitIndex).trim()
            actionStr = rawContent.substring(splitIndex).trim()
            return try {
                parseDoAction(actionStr, thinking)
            } catch (e: Exception) {
                Log.e(TAG, "动作解析失败", e)
                ParsedAction("finish", mapOf("message" to "动作解析失败: ${e.message}"), thinking, true)
            }
        }

        // 完全无法解析
        Log.w(TAG, "无法识别动作格式")
        return ParsedAction("finish", mapOf("message" to rawContent.trim()), "", true)
    }

    /**
     * 解析 do(action=...) 格式的动作字符串。
     *
     * 使用安全的字符串解析而非 eval，通过关键字提取参数值。
     * AutoGLM 模型输出的格式是 Python 风格：do(action="Tap", element=[500, 300])
     *
     * @param actionStr  动作字符串（从 do(action= 到 )
     * @param thinking   思考过程
     * @return 解析后的动作
     */
    private fun parseDoAction(actionStr: String, thinking: String): ParsedAction {
        // 提取 action 类型
        val action = extractStringValue(actionStr, "action=\"", "\"")
            ?: extractSingleQuoted(actionStr, "action=")
            ?: "unknown"

        val params = mutableMapOf<String, Any?>()

        when (action) {
            "Tap", "Double Tap", "Long Press" -> {
                // do(action="Tap", element=[500, 300]) 或 do(action="Tap", element=[500,300], message="xxx")
                val element = extractListValue(actionStr, "element=[")
                if (element != null) {
                    params["element"] = element
                }
                val message = extractStringValue(actionStr, "message=\"", "\"")
                if (message != null) {
                    params["message"] = message
                }
            }
            "Swipe" -> {
                // do(action="Swipe", start=[100,500], end=[100,200])
                val start = extractListValue(actionStr, "start=[")
                val end = extractListValue(actionStr, "end=[")
                if (start != null) params["start"] = start
                if (end != null) params["end"] = end
            }
            "Type", "Type_Name" -> {
                // do(action="Type", text="你好")
                val text = extractStringValue(actionStr, "text=\"", "\"")
                    ?: extractSingleQuoted(actionStr, "text=")
                    ?: ""
                params["text"] = text
            }
            "Launch" -> {
                // do(action="Launch", app="微信")
                val app = extractStringValue(actionStr, "app=\"", "\"")
                    ?: extractSingleQuoted(actionStr, "app=")
                    ?: ""
                params["app"] = app
            }
            "Wait" -> {
                // do(action="Wait", duration="2 seconds") 默认 1 秒
                val duration = extractStringValue(actionStr, "duration=\"", "\"")
                    ?: "1 seconds"
                params["duration"] = duration
            }
            "Take_over" -> {
                val message = extractStringValue(actionStr, "message=\"", "\"")
                    ?: "需要人工操作"
                params["message"] = message
            }
            "Back", "Home", "Interact", "Note", "Call_API" -> {
                // 无参数
            }
        }

        return ParsedAction(
            action = action,
            params = params.toMap(),
            thinking = thinking,
            finished = false,
        )
    }

    /**
     * 解析 finish(message="xxx") 格式的完成消息。
     *
     * @param actionStr 动作字符串（从 finish(message= 到 )）
     * @return 完成消息文本
     */
    private fun parseFinishMessage(actionStr: String): String {
        return extractStringValue(actionStr, "message=\"", "\"")
            ?: extractSingleQuoted(actionStr, "message=")
            ?: "任务完成"
    }

    // ── 辅助提取方法 ──────────────────────────────────────────────

    /**
     * 从字符串中提取双引号包裹的值。
     * 示例：extractStringValue("do(action=\"Tap\"", "action=\"", "\"") → "Tap"
     *
     * @param source 源字符串
     * @param prefix 值的前缀（含双引号，如 "action=\""）
     * @param suffix 值的后缀（通常为 "\""）
     * @return 提取的值，未找到返回 null
     */
    private fun extractStringValue(source: String, prefix: String, suffix: String): String? {
        val startIndex = source.indexOf(prefix)
        if (startIndex == -1) return null
        val valueStart = startIndex + prefix.length
        val endIndex = source.indexOf(suffix, valueStart)
        if (endIndex == -1) return null
        return source.substring(valueStart, endIndex)
    }

    /**
     * 从字符串中提取单引号包裹的值。
     * 示例：extractSingleQuoted("app='微信'", "app=") → "微信"
     *
     * @param source 源字符串
     * @param key    key= 前缀
     * @return 提取的值，未找到返回 null
     */
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

    /**
     * 从字符串中提取数组值 [a, b]。
     * 示例：extractListValue("element=[500, 300]", "element=[") → listOf(500, 300)
     *
     * @param source 源字符串
     * @param prefix 数组的前缀（如 "element=["）
     * @return 整数列表，未找到或解析失败返回 null
     */
    private fun extractListValue(source: String, prefix: String): List<Int>? {
        val startIndex = source.indexOf(prefix)
        if (startIndex == -1) return null
        val arrayStart = startIndex + prefix.length

        // 查找匹配的 ]
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
            // 分离逗号分隔的数值
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
