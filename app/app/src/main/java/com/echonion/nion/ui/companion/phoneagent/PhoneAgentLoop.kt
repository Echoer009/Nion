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

        /** 全局暂停标志，设为 true 后循环在每步开始处挂起等待恢复 */
        @Volatile
        var paused = false
            private set

        /** 设置取消标志 */
        fun cancel() {
            cancelled = true
            Log.d(TAG, "已收到取消信号")
        }

        /** 重置取消标志，供下次任务启动前调用 */
        fun resetCancel() {
            cancelled = false
            paused = false
        }

        /** 暂停循环，循环将在下一步开始处挂起 */
        fun pause() {
            paused = true
            Log.d(TAG, "已暂停")
        }

        /** 恢复循环，解除挂起继续执行 */
        fun resume() {
            paused = false
            Log.d(TAG, "已恢复")
        }

        /** 切换暂停/恢复状态 */
        fun togglePause() {
            if (paused) resume() else pause()
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

                // 每步开始前检查暂停标志，暂停时挂起等待恢复
                checkPaused()

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

                // ==================== 3. 调用模型（流式）====================
                // 发送完整 context，取消检查通过 isCancelled 回调
                // onToken 每次收到 delta.content 时回调，实时更新悬浮窗流式内容
                Log.d(TAG, "第 $step 步: 开始调用 AutoGLM 模型 (stream=true)...")
                PhoneAgentFloatingService.clearCurrentResponse()
                val rawResponse = try {
                    client.request(context, isCancelled = { cancelled }) { token ->
                        PhoneAgentFloatingService.appendToken(token)
                    }
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
                // 清除流式内容，即将作为正式日志显示
                PhoneAgentFloatingService.clearCurrentResponse()
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
                val actionResult = executeAction(parsed.action, parsed.params)
                val stepSuccess = actionResult.success
                steps.add(StepInfo(step, parsed.thinking, parsed.action, parsed.params, stepSuccess, actionResult.message))

                // ==================== 8. 通知上层 ====================
                onStep?.invoke(steps.last())

                // ==================== 9. 添加 assistant 消息到上下文 ====================
                // 失败时追加详细原因到上下文，让模型知道为什么失败，可以调整策略
                val execResult = if (stepSuccess) "" else "\n[执行结果: 失败 - ${actionResult.message}]"
                context.add(
                    AutoGLMClient.createAssistantMessage(
                        "༧${parsed.thinking}ཊ\n<answer>${parsed.rawAction}</answer>$execResult"
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
     * 暂停检查 —— 暂停时循环挂起，每 200ms 检查一次恢复或取消。
     * 暂停期间不截图不调 API，恢复后从下一步继续执行。
     */
    private suspend fun checkPaused() {
        while (paused && !cancelled) {
            delay(200)
        }
    }

    /**
     * 动作执行结果，包含成功/失败状态和详细描述。
     * @property success 是否成功
     * @property message 结果描述，失败时包含具体原因（传递给模型上下文）
     */
    private data class ActionResult(
        val success: Boolean,
        val message: String,
    )

    /**
     * 根据解析的动作执行对应的手机操作。
     * 每个阻塞操作前后都检查取消标志，实现快速停止。
     *
     * @param action 动作名称（Tap, Swipe, Type, Launch 等）
     * @param params 动作参数
     * @return ActionResult 包含成功状态和详细结果描述
     */
    private suspend fun executeAction(action: String, params: Map<String, Any?>): ActionResult {
        // 执行前先检查取消
        if (cancelled) throw CancellationException("用户取消")
        Log.d(TAG, "执行动作: $action, 参数: $params")

        val screenSize = PhoneAgentBridge.getScreenSize()

        return when (action) {
            "Tap" -> {
                val element = params["element"] as? List<*>
                    ?: return ActionResult(false, "Tap 失败: 缺少 element 参数")
                val coords = element.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val abs = PhoneAgentBridge.convertToAbsolute(coords, screenSize)
                    val ok = PhoneAgentBridge.tap(abs[0], abs[1])
                    ActionResult(ok, if (ok) "点击成功" else "点击失败: 无障碍服务可能未正确执行手势")
                } else {
                    ActionResult(false, "Tap 失败: 无法获取屏幕尺寸")
                }
            }

            "Double Tap" -> {
                val element = params["element"] as? List<*>
                    ?: return ActionResult(false, "Double Tap 失败: 缺少 element 参数")
                val coords = element.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val abs = PhoneAgentBridge.convertToAbsolute(coords, screenSize)
                    val ok = PhoneAgentBridge.doubleTap(abs[0], abs[1])
                    ActionResult(ok, if (ok) "双击成功" else "双击失败")
                } else {
                    ActionResult(false, "Double Tap 失败: 无法获取屏幕尺寸")
                }
            }

            "Long Press" -> {
                val element = params["element"] as? List<*>
                    ?: return ActionResult(false, "Long Press 失败: 缺少 element 参数")
                val coords = element.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val abs = PhoneAgentBridge.convertToAbsolute(coords, screenSize)
                    val ok = PhoneAgentBridge.longPress(abs[0], abs[1])
                    ActionResult(ok, if (ok) "长按成功" else "长按失败")
                } else {
                    ActionResult(false, "Long Press 失败: 无法获取屏幕尺寸")
                }
            }

            "Swipe" -> {
                val start = params["start"] as? List<*>
                    ?: return ActionResult(false, "Swipe 失败: 缺少 start 参数")
                val end = params["end"] as? List<*>
                    ?: return ActionResult(false, "Swipe 失败: 缺少 end 参数")
                val startCoords = start.map { (it as Number).toInt() }
                val endCoords = end.map { (it as Number).toInt() }
                if (screenSize != null) {
                    val absStart = PhoneAgentBridge.convertToAbsolute(startCoords, screenSize)
                    val absEnd = PhoneAgentBridge.convertToAbsolute(endCoords, screenSize)
                    val ok = PhoneAgentBridge.swipe(absStart[0], absStart[1], absEnd[0], absEnd[1])
                    ActionResult(ok, if (ok) "滑动成功" else "滑动失败")
                } else {
                    ActionResult(false, "Swipe 失败: 无法获取屏幕尺寸")
                }
            }

            "Type", "Type_Name" -> {
                // 输入前检查取消
                if (cancelled) throw CancellationException("用户取消")
                val text = params["text"]?.toString() ?: ""
                val ok = PhoneAgentBridge.inputText(text)
                ActionResult(ok, if (ok) "输入成功: $text" else "输入失败: 未找到可编辑的输入框")
            }

            "Launch" -> {
                val appName = params["app"]?.toString()
                    ?: return ActionResult(false, "Launch 失败: 缺少 app 参数")
                val packageName = AppPackages.getPackageName(appName)
                Log.d(TAG, "Launch: appName=$appName, packageName=$packageName")
                if (packageName != null) {
                    val ok = PhoneAgentBridge.launchApp(packageName)
                    ActionResult(ok, if (ok) "已启动 $appName" else "启动 $appName 失败: 应用可能未安装或无障碍服务权限不足")
                } else {
                    // 未找到包名映射，fallback 直接尝试原始名称
                    Log.w(TAG, "Launch: 未找到应用映射 appName=$appName, 尝试直接启动")
                    val ok = PhoneAgentBridge.launchApp(appName)
                    ActionResult(ok, if (ok) "已启动 $appName" else "启动 $appName 失败: 未找到该应用的包名")
                }
            }

            "Back" -> {
                val ok = PhoneAgentBridge.pressBack()
                ActionResult(ok, if (ok) "返回成功" else "返回失败")
            }

            "Home" -> {
                val ok = PhoneAgentBridge.pressHome()
                ActionResult(ok, if (ok) "回到桌面成功" else "回到桌面失败")
            }

            "Wait" -> {
                val durationStr = params["duration"]?.toString() ?: "1 seconds"
                val seconds = try {
                    durationStr.replace(Regex("[^0-9.]"), "").toDouble()
                } catch (_: Exception) {
                    1.0
                }
                cancellableDelay((seconds * 1000).toLong())
                ActionResult(true, "等待 ${seconds}s 完成")
            }

            "Take_over" -> {
                val message = params["message"]?.toString() ?: "需要人工操作"
                Log.w(TAG, "请求人工接管: $message")
                cancellableDelay(30_000)
                ActionResult(true, "人工接管完成")
            }

            "Note", "Call_API", "Interact" -> {
                Log.d(TAG, "跳过辅助动作: $action")
                ActionResult(true, "$action 已跳过")
            }

            else -> {
                Log.e(TAG, "未知动作: $action")
                ActionResult(false, "未知动作: $action")
            }
        }
    }
}
