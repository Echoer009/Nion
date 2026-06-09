package com.echonion.nion.ui.companion.phoneagent

import android.util.Base64
import android.util.Log
import com.echonion.nion.ui.companion.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * AutoGLM 模型 API 客户端。
 *
 * 使用标准 OpenAI 兼容 API 调用 AutoGLM-Phone-9B 模型。
 * 区别于 Nion 的 ChatService（支持 tools/function calling），
 * AutoGLM 模型使用特殊的响应格式（遐 + <answer>），不支持标准 tool calling。
 *
 * 此客户端直接构造原始 HTTP 请求调用 API，因为 AutoGLM 格式与标准 OpenAI 不同。
 *
 * @property baseUrl 模型 API 地址（如 https://open.bigmodel.cn/api/paas/v4）
 * @property apiKey  API 认证密钥
 * @property model   模型名称（如 autoglm-phone）
 */
class AutoGLMClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "autoglm-phone",
) {

    companion object {
        private const val TAG = "AutoGLMClient"

        /** 当前日期格式化器，用于 system prompt 中的日期 */
        private val dateFormatter = DateTimeFormatter.ofPattern(
            "yyyy年MM月dd日 EEEE",
            Locale.CHINA
        )
    }

    /**
     * 执行一次 AutoGLM 模型请求。
     *
     * 每次请求包含：
     * 1. system message（Phone Agent 系统提示词 + 今日日期）
     * 2. 当前截图（base64 PNG）
     * 3. 会话上下文（之前的思考+动作历史）
     *
     * @param screenshotBase64 当前屏幕截图的 base64 编码 PNG
     * @param screenInfo       屏幕信息文本（当前所在 App 名称等）
     * @param userTask         用户任务描述（仅首次请求时需要，后续请求传 null）
     * @param context          历史会话上下文（排除第一轮 system prompt 后的 assistant 消息）
     * @return 模型的完整响应文本
     */
    suspend fun request(
        screenshotBase64: String,
        screenInfo: String,
        userTask: String? = null,
        context: List<JSONObject> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val messages = buildMessages(screenshotBase64, screenInfo, userTask, context)
        callApi(messages)
    }

    /**
     * 构建 API 请求的消息列表。
     *
     * 格式遵循 OpenAI Vision API：
     * - system message 包含 Phone Agent 系统提示词
     * - user message 包含 text + image_url（截图）
     *
     * @return OpenAI 格式的 messages JSONArray
     */
    private fun buildMessages(
        screenshotBase64: String,
        screenInfo: String,
        userTask: String?,
        context: List<JSONObject>,
    ): JSONArray {
        val messages = JSONArray()

        // 第一轮：插入 system prompt
        if (context.isEmpty()) {
            messages.put(buildSystemMessage())
        } else {
            // 后续轮次：复用第一轮的 system message
            for (msg in context) {
                messages.put(msg)
            }
        }

        // 构建当前轮次的 user message（含截图 + 屏幕信息）
        val userContent = JSONArray()

        // 先放截图
        userContent.put(
            JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/png;base64,$screenshotBase64")
                })
            }
        )

        // 再放文本（屏幕信息 + 用户任务）
        val textContent = if (userTask != null) {
            "$userTask\n\n$screenInfo"
        } else {
            screenInfo
        }
        userContent.put(
            JSONObject().apply {
                put("type", "text")
                put("text", textContent)
            }
        )

        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            }
        )

        return messages
    }

    /**
     * 构建 system message，包含 Phone Agent 系统提示词 + 今日日期。
     *
     * 使用 AutoGLM 原始的中文 prompt，确保模型输出正确的格式。
     */
    private fun buildSystemMessage(): JSONObject {
        val today = LocalDate.now().format(dateFormatter)
        val systemPrompt = """
今天的日期是: $today

你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。

你的输出必须严格遵循以下格式：
```
${PHONE_AGENT_SPEC_CHAR}{think} 😄
<answer>{action}</answer>
```

其中：
- ${PHONE_AGENT_SPEC_CHAR} 后跟简短的思考过程（为什么执行这个操作）
- {action} 是具体的操作指令，必须严格遵循下方定义的指令格式

所有操作指令必须精确遵循以下格式：
- do(action="Launch", app="xxx") ：启动目标应用，app 参数必须与支持列表中的应用名严格一致
- do(action="Tap", element=[x,y]) ：点击屏幕坐标，x 和 y 为横向像素/1000 坐标，范围 0-999
- do(action="Tap", element=[x,y], message="重要操作") ：点击涉及财产、隐私等敏感内容时触发人工确认
- do(action="Type", text="xxx") ：在当前聚焦的输入框输入文本，输入前会自动清空已有文本
- do(action="Type_Name", text="xxx") ：在人物指定场景中输入人物名字
- do(action="Interact") ：询问用户进行选择
- do(action="Swipe", start=[x1,y1], end=[x2,y2]) ：从起始坐标滑动到结束坐标
- do(action="Long Press", element=[x,y]) ：在指定坐标长按
- do(action="Double Tap", element=[x,y]) ：双击指定坐标
- do(action="Note", message="True") ：记录当前页面信息用于后续总结
- do(action="Call_API", instruction="xxx") ：总结或评论记录的内容
- do(action="Take_over", message="xxx") ：需要人工接管（登录、验证码等场景）
- do(action="Back") ：返回上一页
- do(action="Home") ：回到桌面
- do(action="Wait", duration="x seconds") ：等待 x 秒页面加载
- finish(message="xxx") ：任务完成，message 为完成情况的简短描述

操作规则：
1. 执行操作前先检查当前 App 是否正确，不对则先 Launch
2. 进入错误页面按 Back，Back 无效则点击左上角返回按钮或右上角 X
3. 最多连续 Wait 3 次，之后必须 Back 或重新进入
4. 网络错误时点击重新加载按钮
5. 找不到目标时滑动屏幕继续搜索
6. 滑动失败时增大滑动距离，反向滑动重试
7. 无搜索结果时退回上一级重试（最多 3 次）
8. 结束前确认没有错误/遗漏/多余选择
        """.trimIndent()

        return JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        }
    }

    /**
     * 调用 OpenAI 兼容 API。
     *
     * 使用非流式请求（stream=false），因为 Phone Agent 需要完整的动作指令才能解析。
     *
     * @param messages 消息列表
     * @return 模型的完整响应文本
     */
    private fun callApi(messages: JSONArray): String {
        val url = URL("$baseUrl/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        connection.connectTimeout = 60_000
        connection.readTimeout = 60_000

        try {
            // 构建请求体
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 3000)
                put("temperature", 0.0)
                put("top_p", 0.85)
                put("frequency_penalty", 0.2)
                // 非流式，一次性获取完整的响应
                put("stream", false)
            }

            // 发送请求
            val outputStream: OutputStream = connection.outputStream
            outputStream.write(requestBody.toString().toByteArray())
            outputStream.flush()
            outputStream.close()

            // 读取响应
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "API 返回错误 $responseCode: $errorBody")
                throw Exception("API 错误 $responseCode: $errorBody")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()

            // 解析 OpenAI 格式响应
            val response = JSONObject(responseBody)
            val choices = response.getJSONArray("choices")
            if (choices.length() == 0) {
                throw Exception("API 返回空响应")
            }

            val message = choices.getJSONObject(0).getJSONObject("message")
            val content = message.optString("content", "")
            if (content.isBlank()) {
                throw Exception("模型返回空内容")
            }

            Log.d(TAG, "模型响应: ${content.take(300)}")
            return content
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * 思考过程占位符 - 与 AutoGLM Python 版保持一致
 * 遐 = U+9050，模型通过此字符识别思考标记
 */
private const val PHONE_AGENT_SPEC_CHAR = "\u9050"
