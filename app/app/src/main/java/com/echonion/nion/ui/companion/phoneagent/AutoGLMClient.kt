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
 * AutoGLM 模型使用特殊的响应格式（目前为 <answer> 标签），不支持标准 tool calling。
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
     * 构建 system message，直接使用 AutoGLM 原始 prompt（与 prompts_zh.py 一致）。
     */
    private fun buildSystemMessage(): JSONObject {
        val today = LocalDate.now().format(dateFormatter)
        val systemPrompt = """
今天的日期是: $today

你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

操作指令及其作用如下：
- do(action="Launch", app="xxx")
    Launch是启动目标app的操作，这比通过主屏幕导航更快。
- do(action="Tap", element=[x,y])
    Tap是点击操作，点击屏幕上的特定点。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。
- do(action="Tap", element=[x,y], message="重要操作")
    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
- do(action="Type", text="xxx")
    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前请确保输入框已被聚焦（先点击它）。
- do(action="Type_Name", text="xxx")
    Type_Name是输入人名的操作，基本功能同Type。
- do(action="Interact")
    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])
    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。
- do(action="Note", message="True")
    记录当前页面内容以便后续总结。
- do(action="Call_API", instruction="xxx")
    总结或评论当前页面或已记录的内容。
- do(action="Long Press", element=[x,y])
    Long Press是长按操作，在屏幕上的特定点长按指定时间。
- do(action="Double Tap", element=[x,y])
    Double Tap在屏幕上的特定点快速连续点按两次。
- do(action="Take_over", message="xxx")
    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
- do(action="Back")
    导航返回到上一个屏幕或关闭当前对话框。
- do(action="Home")
    Home是回到系统桌面的操作。
- do(action="Wait", duration="x seconds")
    等待页面加载，x为需要等待多少秒。
- finish(message="xxx")
    finish是结束任务的操作，表示准确完整完成任务，message是终止信息。

必须遵循的规则：
1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行Launch。
2. 如果进入到了无关页面，先执行Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
3. 如果页面未加载出内容，最多连续Wait三次，否则执行Back重新进入。
4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试Swipe滑动查找。
6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
7. 如果当前页面找不到目标，可以尝试修改搜索词重试。必要时可以使用AI搜索。
8. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。
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
 * 构建 OpenAI 格式的 vision user message，包含截图 base64 和文本内容。
 */
private const val PHONE_AGENT_SPEC_CHAR = "\u9050"
