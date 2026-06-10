package com.echonion.nion.ui.companion.phoneagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * AutoGLM 模型 API 客户端。
 *
 * 完全对齐 Python 版 phone_agent/model/client.py 的实现：
 * - 使用流式请求（stream=true），与 Python OpenAI SDK 的 stream=True 一致
 * - 提供 MessageBuilder 静态方法（createSystemMessage, createUserMessage 等）
 * - 系统提示词从 prompts_zh.py 逐字复制
 *
 * @property baseUrl 模型 API 地址（如 https://api-inference.modelscope.cn/v1）
 * @property apiKey  API 认证密钥
 * @property model   模型名称（如 ZhipuAI/AutoGLM-Phone-9B）
 */
class AutoGLMClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "ZhipuAI/AutoGLM-Phone-9B",
) {

    companion object {
        private const val TAG = "AutoGLMClient"

        /** 日期格式化器，与 Python prompts_zh.py 的 weekday_names + strftime 一致 */
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE", Locale.CHINA)

        // ==================== MessageBuilder 静态方法 ====================
        // 对齐 Python phone_agent/model/client.py MessageBuilder

        /**
         * 构建系统消息。
         * 对齐 Python MessageBuilder.create_system_message(content)
         */
        fun createSystemMessage(content: String): JSONObject = JSONObject().apply {
            put("role", "system")
            put("content", content)
        }

        /**
         * 构建用户消息（含可选截图）。
         * 对齐 Python MessageBuilder.create_user_message(text, image_base64)
         *
         * @param text       文本内容
         * @param imageBase64 可选的 base64 截图，非 null 时以 image_url 类型添加到 content 数组
         */
        fun createUserMessage(text: String, imageBase64: String? = null): JSONObject {
            val content = JSONArray()
            if (imageBase64 != null) {
                content.put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$imageBase64")
                        })
                    }
                )
            }
            content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                }
            )
            return JSONObject().apply {
                put("role", "user")
                put("content", content)
            }
        }

        /**
         * 构建 assistant 消息。
         * 对齐 Python MessageBuilder.create_assistant_message(content)
         */
        fun createAssistantMessage(content: String): JSONObject = JSONObject().apply {
            put("role", "assistant")
            put("content", content)
        }

        /**
         * 从消息中移除图片（节省上下文空间）。
         * 对齐 Python MessageBuilder.remove_images_from_message(message)
         *
         * 将 content 数组中 type=="image_url" 的条目删除，只保留 text 条目。
         */
        fun removeImagesFromMessage(message: JSONObject): JSONObject {
            val content = message.opt("content")
            if (content is JSONArray) {
                val filtered = JSONArray()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i)
                    if (item != null && item.optString("type") == "text") {
                        filtered.put(item)
                    }
                }
                message.put("content", filtered)
            }
            return message
        }

        /**
         * 构建屏幕信息 JSON 字符串。
         * 对齐 Python MessageBuilder.build_screen_info(current_app)
         */
        fun buildScreenInfo(currentApp: String): String {
            return JSONObject().apply {
                put("current_app", currentApp)
            }.toString()
        }

        /**
         * 构建完整系统提示词。
         * 对齐 Python config/prompts_zh.py SYSTEM_PROMPT，逐字复制。
         *
         * 格式："今天的日期是: {date}\n{prompt_body}"
         */
        fun buildSystemPrompt(): String {
            val today = LocalDate.now().format(dateFormatter)
            return "今天的日期是: $today\n" + SYSTEM_PROMPT_BODY
        }

        /**
         * 系统提示词主体，从 Python config/prompts_zh.py SYSTEM_PROMPT 逐字复制。
         *
         * 包含：动作指令定义（15 种）、必须遵循的规则（18 条）。
         * 注意：使用 trimIndent() 去除 Kotlin 三引号字符串的首行换行和缩进。
         */
        private val SYSTEM_PROMPT_BODY = """
你是一个智能体分析专家，可以根据操作历史和当前状态图执行一系列操作来完成任务。
你必须严格按照要求输出以下格式：
༧{think}ཊ
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

操作指令及其作用如下：
- do(action="Launch", app="xxx")  
    Launch是启动目标app的操作，这比通过主屏幕导航更快。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y])  
    Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y], message="重要操作")  
    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
- do(action="Type", text="xxx")  
    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。重要提示：手机可能正在使用 ADB 键盘，该键盘不会像普通键盘那样占用屏幕空间。要确认键盘已激活，请查看屏幕底部是否显示 'ADB Keyboard {ON}' 类似的文本，或者检查输入框是否处于激活/高亮状态。不要仅仅依赖视觉上的键盘显示。自动清除文本：当你使用输入操作时，输入框中现有的任何文本（包括占位符文本和实际输入）都会在输入新文本前自动清除。你无需在输入前手动清除文本——直接使用输入操作输入所需文本即可。操作完成后，你将自动收到结果状态的截图。
- do(action="Type_Name", text="xxx")  
    Type_Name是输入人名的操作，基本功能同Type。
- do(action="Interact")  
    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  
    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。可用于滚动内容、在屏幕之间导航、下拉通知栏以及项目栏或进行基于手势的导航。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。滑动持续时间会自动调整以实现自然的移动。此操作完成后，您将自动收到结果状态的截图。
- do(action="Note", message="True")  
    记录当前页面内容以便后续总结。
- do(action="Call_API", instruction="xxx")  
    总结或评论当前页面或已记录的内容。
- do(action="Long Press", element=[x,y])  
    Long Pres是长按操作，在屏幕上的特定点长按指定时间。可用于触发上下文菜单、选择文本或激活长按交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
- do(action="Double Tap", element=[x,y])  
    Double Tap在屏幕上的特定点快速连续点按两次。使用此操作可以激活双击交互，如缩放、选择文本或打开项目。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Take_over", message="xxx")  
    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
- do(action="Back")  
    导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。使用此操作可以从更深的屏幕返回、关闭弹出窗口或退出当前上下文。此操作完成后，您将自动收到结果状态的截图。
- do(action="Home") 
    Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。使用此操作可退出当前应用并返回启动器，或从已知状态启动新任务。此操作完成后，您将自动收到结果状态的截图。
- do(action="Wait", duration="x seconds")  
    等待页面加载，x为需要等待多少秒。
- finish(message="xxx")  
    finish是结束任务的操作，表示准确完整完成任务，message是终止信息。 

必须遵循的规则：
1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
7. 在做小红书总结类任务时一定要筛选图文笔记。
8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
9. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将"群"字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish message说明点击不生效。
15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish message说明但没找到要求的项目。
16. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish(message="原因")。
18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。
""".trimIndent()
    }

    /**
     * 执行一次 AutoGLM 模型流式请求。
     *
     * 对齐 Python ModelClient.request(messages)：
     * - 使用 stream=true 逐块读取 SSE 响应
     * - 通过 isCancelled 回调支持即时取消
     * - 累积所有 delta.content 得到完整 raw_content
     *
     * @param messages   完整的消息列表（由 PhoneAgentLoop 维护的上下文）
     * @param isCancelled 取消检查回调，返回 true 时立即中断流式读取
     * @param onToken 每次收到 delta.content 时的回调，用于实时流式显示
     * @return 模型的完整响应文本（raw_content）
     */
    suspend fun request(
        messages: List<JSONObject>,
        isCancelled: () -> Boolean = { false },
        onToken: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val messagesArray = JSONArray().apply { messages.forEach { put(it) } }
        Log.d(TAG, "request: 发送 ${messages.size} 条消息, model=$model")
        callApiStream(messagesArray, isCancelled, onToken)
    }

    /**
     * 流式调用 OpenAI 兼容 API。
     *
     * 对齐 Python ModelClient.request 中的 stream=True 逻辑：
     * - 发送 stream=true 请求
     * - 逐行读取 SSE data: {...} 格式
     * - 累积 choices[0].delta.content
     * - 遇到 data: [DONE] 结束
     *
     * @param messages    消息 JSONArray
     * @param isCancelled 取消检查回调
     * @param onToken 每收到一个 delta.content 块就调用一次（用于实时流式显示）
     * @return 完整的模型响应文本
     */
    private fun callApiStream(messages: JSONArray, isCancelled: () -> Boolean, onToken: (String) -> Unit = {}): String {
        val url = URL("$baseUrl/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        connection.connectTimeout = 30_000
        // 读超时设短（10秒），配合 isCancelled 实现快速取消：模型思考期间无数据流入，
        // 超时后 catch IOException → 检查 isCancelled → 如果已取消则抛出 CancellationException
        connection.readTimeout = 10_000

        try {
            // 构建请求体，参数与 Python ModelConfig / ModelClient.request 一致
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 2048)
                put("temperature", 0.0)
                put("top_p", 0.85)
                put("frequency_penalty", 0.2)
                put("stream", true)
            }

            val outputStream: OutputStream = connection.outputStream
            outputStream.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            outputStream.flush()
            outputStream.close()
            Log.d(TAG, "callApiStream: 流式请求已发送，等待响应...")

            val responseCode = connection.responseCode
            Log.d(TAG, "callApiStream: HTTP 响应码 = $responseCode")
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "callApiStream: API 错误 $responseCode: $errorBody")
                throw Exception("API 错误 $responseCode: $errorBody")
            }

            // 逐行读取 SSE 流
            val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
            val rawContent = StringBuilder()

            try {
                var line: String? = reader.readLine()
                while (line != null) {
                    // 每读取一行检查取消标志，实现即时中断
                    if (isCancelled()) {
                        Log.w(TAG, "callApiStream: 流式读取期间检测到取消")
                        throw CancellationException("用户取消")
                    }

                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") {
                            Log.d(TAG, "callApiStream: 收到 [DONE]")
                            break
                        }
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                if (delta != null) {
                                    val content = delta.optString("content", "")
                                    if (content.isNotEmpty()) {
                                        rawContent.append(content)
                                        onToken(content)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // 跳过格式异常的 JSON 行
                        }
                    }

                    line = reader.readLine()
                }
            } finally {
                reader.close()
            }

            val result = rawContent.toString()
            Log.d(TAG, "callApiStream: 响应完成, 长度=${result.length}, 前300字=${result.take(300)}")
            if (result.isBlank()) {
                throw Exception("模型返回空内容")
            }
            return result
        } catch (e: CancellationException) {
            Log.w(TAG, "callApiStream: 用户取消")
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            // 读超时：可能是模型思考中无数据流出，检查是否用户取消
            if (isCancelled()) {
                Log.w(TAG, "callApiStream: 读超时 + 用户取消，立即中断")
                throw CancellationException("用户取消")
            }
            Log.e(TAG, "callApiStream: 读超时", e)
            throw e
        } catch (e: Exception) {
            // 其他异常也检查取消：用户可能断开了连接
            if (isCancelled()) {
                Log.w(TAG, "callApiStream: 异常 + 用户取消，视为取消")
                throw CancellationException("用户取消")
            }
            Log.e(TAG, "callApiStream: 请求失败", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
