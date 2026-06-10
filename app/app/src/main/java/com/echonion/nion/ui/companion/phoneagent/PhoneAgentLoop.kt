package com.echonion.nion.ui.companion.phoneagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Phone Agent 子循环 —— 完全对齐 Python phone_agent/agent.py PhoneAgent._execute_step。
 *
 * 核心流程（与 Python 一一对应）：
 * 1. 截图 → 获取 base64 PNG
 * 2. 构建 user message → 添加到 context
 * 3. 调用模型（stream=true）→ 发送完整 context
 * 4. 从最后一条 user message 中移除图片（节省上下文空间）
 * 5. 解析响应 → 分离思考过程和动作指令
 * 6. 执行动作 → 通过 PhoneAgentBridge 调用 AccessibilityService
 * 7. 添加 assistant message 到 context
 * 8. 判断是否完成 → finish 则退出，否则回到步骤 1
 *
 * 上下文管理（与 Python self._context 完全一致）：
 * - context 保存完整对话历史：[system, user, assistant, user, assistant, ...]
 * - 第一步：添加 system message + user message
 * - 后续步：只添加 user message（含截图）
 * - 模型调用后：移除最新 user message 中的图片，添加 assistant message
 *
 * 取消机制：
 * - 用户点击取消 → cancelled=true
 * - 流式读取期间每行检查 cancelled 标志
 * - delay 等待期间每 100ms 检查 cancelled 标志
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

        /** 最大执行步数，防止无限循环。Python 默认 100，我们保守设 50 */
        private const val MAX_STEPS = 50

        /** 全局取消标志，设为 true 后所有 PhoneAgentLoop 实例立即终止 */
        @Volatile
        var cancelled = false
            private set

        /** 设置取消标志 */
        fun cancel() {
            cancelled = true
            Log.d(TAG, "已收到取消信号")
        }

        /** 重置取消标志，供下次任务启动前调用 */
        fun resetCancel() {
            cancelled = false
        }
    }

    /**
     * 单步执行信息，用于向主 AI 汇报进度。
     * @property stepNumber 当前步数（1-based）
     * @property thinking 模型的思考过程
     * @property action 执行的动作名称
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
     * 对齐 Python PhoneAgent.run(task)：
     * 1. 初始化空的 context
     * 2. 第一步带 user_prompt 调用 _execute_step(isFirst=true)
     * 3. 后续步不带 prompt 调用 _execute_step(isFirst=false)
     * 4. 循环直到 finish 或达到最大步数
     *
     * @param task 用户的任务描述（如"打开微信发消息给张三"）
     * @return 最终执行结果
     */
    suspend fun run(task: String): AgentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "开始执行任务: $task")

        // 完整对话上下文，与 Python self._context 一致
        val context = mutableListOf<JSONObject>()
        val steps = mutableListOf<StepInfo>()

        try {
            for (step in 1..MAX_STEPS) {
                // 每步开始前检查取消标志
                checkCancelled(step)

                Log.d(TAG, "--- 第 $step 步 ---")

                // ==================== 1. 截图 ====================
                Log.d(TAG, "第 $step 步: 正在截图...")
                val screenshot = PhoneAgentBridge.takeScreenshot()
                Log.d(TAG, "第 $step 步: 截图结果 = ${if (screenshot != null) "成功(${screenshot.length} chars)" else "null"}")
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

                // ==================== 2. 构建消息并添加到上下文 ====================
                // 对齐 Python _execute_step 的 is_first 分支逻辑
                val currentApp = PhoneAgentBridge.getCurrentAppName() ?: "Unknown"
                val screenInfo = AutoGLMClient.buildScreenInfo(currentApp)

                if (step == 1) {
                    // 第一步：添加 system message + user message（含用户任务）
                    context.add(AutoGLMClient.createSystemMessage(AutoGLMClient.buildSystemPrompt()))
                    val textContent = "$task\n\n$screenInfo"
                    context.add(AutoGLMClient.createUserMessage(textContent, screenshot))
                    Log.d(TAG, "第 $step 步: 首步消息已添加, system+user")
                } else {
                    // 后续步：只添加 user message（含截图 + 屏幕信息）
                    // 对齐 Python: text_content = f"** Screen Info **\n\n{screen_info}"
                    val textContent = "** Screen Info **\n\n$screenInfo"
                    context.add(AutoGLMClient.createUserMessage(textContent, screenshot))
                    Log.d(TAG, "第 $step 步: 后续消息已添加, user")
                }

                // ==================== 3. 调用模型 ====================
                // 发送完整 context，取消检查通过 isCancelled 回调
                Log.d(TAG, "第 $step 步: 开始调用 AutoGLM 模型 (stream=true)...")
                val rawResponse = try {
                    client.request(context) { cancelled }
                } catch (e: CancellationException) {
                    Log.w(TAG, "第 $step 步: 模型调用期间被取消")
                    throw e
                }
                Log.d(TAG, "第 $step 步: 模型响应 = ${rawResponse.take(200)}")

                // ==================== 4. 从最后一条消息中移除图片 ====================
                // 对齐 Python: self._context[-1] = MessageBuilder.remove_images_from_message(self._context[-1])
                // 此时 context 的最后一条是刚添加的 user message，移除其图片以节省上下文空间
                val lastIdx = context.lastIndex
                context[lastIdx] = AutoGLMClient.removeImagesFromMessage(context[lastIdx])

                // ==================== 5. 解析响应 ====================
                val parsed = PhoneActionParser.parse(rawResponse)
                Log.d(TAG, "解析结果: action=${parsed.action}, finished=${parsed.finished}")

                // ==================== 6. 检查是否完成 ====================
                if (parsed.finished) {
                    // 添加 assistant 消息到上下文
                    context.add(
                        AutoGLMClient.createAssistantMessage(
                            "༧${parsed.thinking}ཊ\n<answer>${parsed.rawAction}</answer>"
                        )
                    )
                    val message = parsed.params["message"]?.toString() ?: "任务完成"
                    steps.add(StepInfo(step, parsed.thinking, parsed.action, parsed.params, true, message))
                    Log.d(TAG, "任务完成: $message")
                    return@withContext AgentResult(
                        success = true,
                        message = message,
                        totalSteps = step,
                        steps = steps.toList(),
                    )
                }

                // ==================== 7. 执行动作 ====================
                val stepSuccess = executeAction(parsed.action, parsed.params)
                val stepMsg = if (stepSuccess) "${parsed.action} 执行成功" else "${parsed.action} 执行失败"
                steps.add(StepInfo(step, parsed.thinking, parsed.action, parsed.params, stepSuccess, stepMsg))

                // ==================== 8. 通知上层 ====================
                onStep?.invoke(steps.last())

                // ==================== 9. 添加 assistant 消息到上下文 ====================
                // 对齐 Python: self._context.append(MessageBuilder.create_assistant_message(f"༧{thinking}ཊ\n<answer>{action}</answer>"))
                context.add(
                    AutoGLMClient.createAssistantMessage(
                        "༧${parsed.thinking}ཊ\n<answer>${parsed.rawAction}</answer>"
                    )
                )
                Log.d(TAG, "第 $step 步: 上下文大小 = ${context.size}")

                // ==================== 10. 动作后等待页面响应 ====================
                cancellableDelay(1500L)
            }

            // 达到最大步数
            Log.w(TAG, "达到最大步数 $MAX_STEPS")
            return@withContext AgentResult(
                success = false,
                message = "达到最大执行步数（$MAX_STEPS），任务可能未完成",
                totalSteps = MAX_STEPS,
                steps = steps.toList(),
            )
        } catch (e: CancellationException) {
            Log.w(TAG, "即时取消触发，终止循环（已执行 ${steps.size} 步）")
            return@withContext AgentResult(
                success = false,
                message = "用户取消了操作",
                totalSteps = steps.size,
                steps = steps.toList(),
            )
        }
    }

    /**
     * 可取消的 delay —— 每 100ms 检查一次 cancelled 标志。
     *
     * @param millis 总等待时长（毫秒）
     * @throws CancellationException 用户取消时抛出
     */
    private suspend fun cancellableDelay(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            if (cancelled) {
                Log.w(TAG, "等待期间检测到取消，立即中断")
                throw CancellationException("用户取消")
            }
            val chunk = minOf(remaining, 100L)
            delay(chunk)
            remaining -= chunk
        }
    }

    /**
     * 检查取消标志，如已取消则抛出 CancellationException。
     *
     * @param step 当前步数
     * @throws CancellationException 已取消时抛出
     */
    private fun checkCancelled(step: Int) {
        if (cancelled) {
            Log.w(TAG, "用户取消，终止循环（第 $step 步开始前）")
            throw CancellationException("用户取消")
        }
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
                val durationStr = params["duration"]?.toString() ?: "1 seconds"
                val seconds = try {
                    durationStr.replace(Regex("[^0-9.]"), "").toDouble()
                } catch (_: Exception) {
                    1.0
                }
                cancellableDelay((seconds * 1000).toLong())
                true
            }

            "Take_over" -> {
                val message = params["message"]?.toString() ?: "需要人工操作"
                Log.w(TAG, "请求人工接管: $message")
                cancellableDelay(30_000)
                true
            }

            "Note", "Call_API", "Interact" -> {
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
