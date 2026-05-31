package com.echonion.nion.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.PromptDefaults
import com.echonion.nion.ui.companion.weather.WeatherAlertChecker
import com.echonion.nion.ui.companion.weather.WeatherService
import java.time.LocalTime

/**
 * 天气预警 Worker —— 每小时检查一次天气数据，判断是否需要提醒用户。
 *
 * 执行流程：
 * 1. 静默时段检查（22:00 ~ 07:00 直接跳过，夜间不骚扰用户）
 * 2. 通过 WeatherService 获取天气数据（含当前 + 逐小时 + 逐日预报）
 * 3. 用 WeatherAlertChecker 做纯代码阈值判断（降水/温度剧变/大风/UV/极端温度）
 * 4. 不需要提醒 → 静默结束
 * 5. 需要提醒 → 通过 ReminderLlmClient 调用 LLM 生成个性化提醒文案
 * 6. 通过 OverlayDispatcher 三模分发：前台悬浮卡 / 后台悬浮窗 / 系统通知兜底
 *
 * 调度方式：
 * - 由 WeatherAlertScheduler 注册 PeriodicWorkRequest，间隔 1 小时
 * - 开机后由 BootReceiver 自动恢复调度
 *
 * 去重机制：
 * - WeatherAlertChecker 内部通过 settings 表记录同类预警的最近提醒时间
 * - 同类预警 6 小时内不重复提醒
 */
class WeatherAlertWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "开始天气预警检查")

        // ── 静默时段检查：夜间 22:00 ~ 07:00 不发预警 ──
        val now = LocalTime.now()
        val inQuietHours = if (QUIET_START.isBefore(QUIET_END)) {
            // 不跨午夜的情况（如 06:00 ~ 08:00）
            now.isAfter(QUIET_START) || now.isBefore(QUIET_END)
        } else {
            // 跨午夜的情况（22:00 ~ 07:00）
            now.isAfter(QUIET_START) || now.isBefore(QUIET_END)
        }
        if (inQuietHours) {
            Log.d(TAG, "当前处于静默时段 ($QUIET_START ~ $QUIET_END)，跳过天气预警")
            return Result.success()
        }

        val app = applicationContext as? NionApp ?: return Result.failure()
        val core = app.core

        // 1. 检查天气预警是否开启
        val enabled = core.getSetting("weather_alert_enabled")
        if (enabled == "false") {
            Log.d(TAG, "天气预警已关闭，跳过")
            return Result.success()
        }

        // 2. 获取天气数据
        val weather = WeatherService.fetchWeather(applicationContext, core)
        if (weather == null) {
            Log.w(TAG, "获取天气数据失败，跳过预警检查")
            return Result.success()
        }

        // 3. 代码层判断是否需要预警
        val alertResult = WeatherAlertChecker.check(weather, core)
        if (!alertResult.shouldAlert) {
            Log.d(TAG, "天气正常，无需预警")
            return Result.success()
        }

        Log.d(TAG, "检测到天气预警: severity=${alertResult.severity}, reasons=${alertResult.reasons}")

        // ── 立即显示"正在输入..."通知，让用户知道 Nion 在工作 ──
        val companionName = core.getSetting("companion_name")
            ?: com.echonion.nion.ui.companion.PromptDefaults.DEFAULT_COMPANION_NAME
        NotificationHelper.showTypingNotification(applicationContext, "weather_alert", companionName)

        try {
            // 4. 通过 LLM 生成个性化提醒文案
            val message = generateAlertMessage(core, weather, alertResult)

            // 5. 三模分发：前台悬浮卡 / 后台悬浮窗 / 系统通知兜底
            // 先发系统通知作为兜底（前台/悬浮窗接管后会主动取消）
            NotificationHelper.showWeatherAlertNotification(
                applicationContext,
                message,
                alertResult.severity,
            )

            val severity = alertResult.severity
            OverlayDispatcher.dispatch(
                context = applicationContext,
                onForeground = {
                    // 前台：发 SharedFlow 事件 → WeatherAlertOverlay 弹 App 内悬浮卡片
                    app.postWeatherAlertEvent(WeatherAlertEvent(severity, message))
                    // 前台 Overlay 已接管，取消系统通知避免双重提醒
                    NotificationHelper.dismissWeatherAlertNotification(applicationContext)
                },
                onBackgroundOverlay = {
                    // 后台 + 有悬浮窗权限 → 启动天气预警悬浮窗 Service
                    WeatherAlertFloatingService.start(applicationContext, severity, message)
                    // 悬浮窗已接管，取消系统通知避免双重提醒
                    NotificationHelper.dismissWeatherAlertNotification(applicationContext)
                },
                onFallback = {
                    // 兜底：保留系统通知（上面已发送）
                    Log.d(TAG, "App 在后台且无悬浮窗权限，保留系统通知")
                },
            )

            // "正在输入..."通知已完成使命，取消它
            NotificationHelper.dismissTypingNotification(applicationContext, "weather_alert")

            Log.d(TAG, "天气预警已分发")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "天气预警处理失败", e)
            // 异常时也要取消"正在输入"通知，避免残留
            NotificationHelper.dismissTypingNotification(applicationContext, "weather_alert")
            return Result.failure()
        }
    }

    /**
     * 通过 LLM 生成个性化的天气预警文案。
     *
     * 将天气数据和预警原因作为上下文传给 LLM，让 Nion 以温暖友好的语气提醒用户。
     * LLM 不可用时使用模板兜底。
     *
     * @param core NionCore 单例
     * @param weather 天气数据
     * @param alert 预警判断结果
     * @return 个性化提醒文案
     */
    private suspend fun generateAlertMessage(
        core: uniffi.nion_core.NionCore,
        weather: com.echonion.nion.ui.companion.weather.FullWeatherData,
        alert: com.echonion.nion.ui.companion.weather.WeatherAlertResult,
    ): String {
        val client = ReminderLlmClient.fromCore(core)
        if (client != null) {
            val current = weather.current
            val severityDesc = when (alert.severity) {
                "urgent" -> "紧急"
                "warning" -> "提醒"
                else -> "提示"
            }

            val template = core.getSetting(PromptDefaults.KEY_WEATHER_ALERT) ?: PromptDefaults.WEATHER_ALERT
            val scenePrompt = template.replace("{severity}", severityDesc)
            // 使用统一方法构建与聊天对话完全一致的 system prompt 前缀
            val systemPrompt = ReminderUtils.buildSystemPrompt(core, scenePrompt)

            val reasonsText = alert.reasons.joinToString("\n") { "- $it" }
            val userMsg = """当前天气：${com.echonion.nion.ui.companion.weather.weatherDescription(current.weatherCode)}，${current.temperature}°C
预警原因：
$reasonsText"""

            val result = client.chat(systemPrompt, userMsg)
            if (result != null) return result
        }

        // LLM 不可用，模板兜底
        return generateFallbackMessage(alert)
    }

    /**
     * 模板兜底生成预警文案。
     */
    private fun generateFallbackMessage(
        alert: com.echonion.nion.ui.companion.weather.WeatherAlertResult,
    ): String {
        val mainReason = alert.reasons.firstOrNull() ?: "天气变化"
        return when (alert.severity) {
            "urgent" -> "注意！$mainReason。请做好防护准备！"
            "warning" -> "提醒一下～$mainReason。出门记得做好准备哦！"
            else -> "提示：$mainReason。"
        }
    }

    companion object {
        private const val TAG = "WeatherAlertWorker"

        /** 静默时段起始时间（22:00）—— 此时间之后不发天气预警 */
        private val QUIET_START = LocalTime.of(22, 0)

        /** 静默时段结束时间（07:00）—— 此时间之前不发天气预警 */
        private val QUIET_END = LocalTime.of(7, 0)
    }
}
