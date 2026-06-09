package com.echonion.nion.ui.companion.phoneagent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone Agent 子循环 —— 实现截图→调模型→解析→执行的循环逻辑。
 *
 * 这是对 AutoGLM Python 版 agent.py 中 _execute_step 的 Kotlin 移植。
 *
 * 循环流程：
 * 1. 截图 → 获取 base64 PNG
 * 2. 构建消息 → 截图 + 屏幕信息 + 任务 → 发给 AutoGLM API
 * 3. 解析响应 → 分离思考过程和动作指令
 * 4. 执行动作 → 通过 PhoneAgentBridge 调用 AccessibilityService
 * 5. 判断是否完成 → finish 则退出，否则回到步骤 1
 *
 * @property client  AutoGLM API 客户端
 * @property onStep 每步执行回调，用于上报状态给主 AI (thinking, action, result)
 */
class PhoneAgentLoop(
    private val client: AutoGLMClient,
    private val onStep: ((StepInfo) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "PhoneAgentLoop"
        /** 最大执行步数，防止无限循环 */
        private const val MAX_STEPS = 50
    }

    /**
     * 单步执行信息，用于向主 AI 汇报进度。
     * @property stepNumber 当前步数（1-based）
     * @property thinking 模型的思考过程
     * @property action 执行的動作名称
     * @property params 动作参数
     * @property success 是否执行成功
     * @property message 结果消息
     */
    data class StepInfo(
        val stepNumber: Int,
        val thinking: String,
        val action: String,
        val params: Map<String, Any?>,
        val success: Boolean,
        val message: String,
    )

    /**
     * Agent 循环最终结果。
     * @property success 是否成功完成
     * @property message 完成消息
     * @property totalSteps 总步数
     * @property steps 每步的详细信息
     */
    data class AgentResult(
        val success: Boolean,
        val message: String,
        val totalSteps: Int,
        val steps: List<StepInfo>,
    )

    /**
     * 运行 Phone Agent 循环。
     *
     * @param task 用户的任务描述（如"打开微信发消息给张三"）
     * @return 最终执行结果
     */
    suspend fun run(task: String): AgentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "开始执行任务: $task")

        val context = mutableListOf<JSONObject>()  // 会话上下文（存历史 assistant 响应）
        val steps = mutableListOf<StepInfo>()
        var isFirstStep = true

        for (step in 1..MAX_STEPS) {
            Log.d(TAG, "--- 第 $step 步 ---")

            // 1. 截图
            val screenshot = PhoneAgentBridge.takeScreenshot()
            if (screenshot == null) {
                Log.e(TAG, "截图失败，终止循环")
                steps.add(StepInfo(step, "", "screenshot", emptyMap(), false, "截图失败，请确认无障碍服务已开启"))
                return@withContext AgentResult(
                    success = false,
                    message = "截图失败，请确认 Phone Agent 无障碍服务已开启",
                    totalSteps = step,
                    steps = steps.toList(),
                )
            }

            // 2. 获取屏幕信息（当前所在 App 名称等）
            val screenSize = PhoneAgentBridge.getScreenSize()
            val screenInfo = if (screenSize != null) {
                "** Screen Info **\n\n屏幕尺寸: ${screenSize.x}x${screenSize.y}"
            } else {
                "** Screen Info **\n\n屏幕信息不可用"
            }

            // 3. 调用 AutoGLM 模型
            val userTaskParam = if (isFirstStep) task else null
            val rawResponse = try {
                client.request(
                    screenshotBase64 = screenshot,
                    screenInfo = screenInfo,
                    userTask = userTaskParam,
                    context = if (isFirstStep) emptyList() else context,
                )
            } catch (e: Exception) {
                Log.e(TAG, "模型请求失败", e)
                steps.add(StepInfo(step, "", "model_error", emptyMap(), false, "模型请求失败: ${e.message}"))
                return@withContext AgentResult(
                    success = false,
                    message = "模型请求失败: ${e.message}",
                    totalSteps = step,
                    steps = steps.toList(),
                )
            }

            // 4. 解析响应
            val parsed = PhoneActionParser.parse(rawResponse)
            Log.d(TAG, "解析结果: action=${parsed.action}, finished=${parsed.finished}")

            // 5. 检查是否完成
            if (parsed.finished) {
                val message = parsed.params["message"]?.toString() ?: "任务完成"
                steps.add(StepInfo(step, parsed.thinking, parsed.action, parsed.params, true, message))
                return@withContext AgentResult(
                    success = true,
                    message = message,
                    totalSteps = step,
                    steps = steps.toList(),
                )
            }

            // 6. 执行动作
            val stepSuccess = executeAction(parsed.action, parsed.params)
            val stepMsg = if (stepSuccess) "${parsed.action} 执行成功" else "${parsed.action} 执行失败"
            steps.add(StepInfo(step, parsed.thinking, parsed.action, parsed.params, stepSuccess, stepMsg))

            // 7. 通知上层
            onStep?.invoke(steps.last())

            // 8. 将本轮响应存入上下文（供下一轮复用 system prompt）
            val assistantMsg = JSONObject().apply {
                put("role", "assistant")
                put("content", rawResponse)
            }
            context.add(assistantMsg)

            // 9. 动作后等待页面响应（默认 1.5 秒）
            isFirstStep = false
            delay(1500)
        }

        // 达到最大步数
        Log.w(TAG, "达到最大步数 $MAX_STEPS")
        return@withContext AgentResult(
            success = false,
            message = "达到最大执行步数（$MAX_STEPS），任务可能未完成",
            totalSteps = MAX_STEPS,
            steps = steps.toList(),
        )
    }

    /**
     * 根据解析的动作执行对应的手机操作。
     *
     * @param action 动作名称（Tap, Swipe, Type, Launch 等）
     * @param params 动作参数
     * @return 是否执行成功
     */
    private suspend fun executeAction(action: String, params: Map<String, Any?>): Boolean {
        Log.d(TAG, "执行动作: $action, 参数: $params")

        val screenSize = PhoneAgentBridge.getScreenSize()

        return when (action) {
            "Tap" -> {
                val element = params["element"] as? List<*> ?: return false
                val coords = element.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val abs = PhoneAgentBridge.convertToAbsolute(coords, screenSize)
                    PhoneAgentBridge.tap(abs[0], abs[1])
                } else {
                    false
                }
            }

            "Double Tap" -> {
                val element = params["element"] as? List<*> ?: return false
                val coords = element.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val abs = PhoneAgentBridge.convertToAbsolute(coords, screenSize)
                    PhoneAgentBridge.doubleTap(abs[0], abs[1])
                } else {
                    false
                }
            }

            "Long Press" -> {
                val element = params["element"] as? List<*> ?: return false
                val coords = element.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val abs = PhoneAgentBridge.convertToAbsolute(coords, screenSize)
                    PhoneAgentBridge.longPress(abs[0], abs[1])
                } else {
                    false
                }
            }

            "Swipe" -> {
                val start = params["start"] as? List<*> ?: return false
                val end = params["end"] as? List<*> ?: return false
                val startCoords = start.map { (it as Number).toInt() }
                val endCoords = end.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val absStart = PhoneAgentBridge.convertToAbsolute(startCoords, screenSize)
                    val absEnd = PhoneAgentBridge.convertToAbsolute(endCoords, screenSize)
                    PhoneAgentBridge.swipe(absStart[0], absStart[1], absEnd[0], absEnd[1])
                } else {
                    false
                }
            }

            "Type", "Type_Name" -> {
                val text = params["text"]?.toString() ?: ""
                PhoneAgentBridge.inputText(text)
            }

            "Launch" -> {
                val appName = params["app"]?.toString() ?: return false
                val packageName = AppPackages.getPackageName(appName)
                if (packageName != null) {
                    PhoneAgentBridge.launchApp(packageName)
                } else {
                    Log.w(TAG, "未找到应用: $appName")
                    false
                }
            }

            "Back" -> {
                PhoneAgentBridge.pressBack()
            }

            "Home" -> {
                PhoneAgentBridge.pressHome()
            }

            "Wait" -> {
                // 解析等待时长（如 "2 seconds" → 2000ms）
                val durationStr = params["duration"]?.toString() ?: "1 seconds"
                val seconds = try {
                    durationStr.replace(Regex("[^0-9.]"), "").toDouble()
                } catch (_: Exception) {
                    1.0
                }
                delay((seconds * 1000).toLong())
                true // Wait 总是成功
            }

            "Take_over" -> {
                // 人工接管 —— 暂停循环，等待用户操作
                val message = params["message"]?.toString() ?: "需要人工操作"
                Log.w(TAG, "请求人工接管: $message")
                // 在实际使用中应弹出通知或在 UI 中显示提示
                // 这里先简单等待 30 秒
                delay(30_000)
                true
            }

            "Note", "Call_API", "Interact" -> {
                // 这些是辅助动作，在 Phone Agent 简版中暂时跳过
                Log.d(TAG, "跳过辅助动作: $action")
                true
            }

            else -> {
                Log.e(TAG, "未知动作: $action")
                false
            }
        }
    }
}
