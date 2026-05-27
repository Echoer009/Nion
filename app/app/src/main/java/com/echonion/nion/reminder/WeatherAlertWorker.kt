package com.echonion.nion.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.weather.WeatherAlertChecker
import com.echonion.nion.ui.companion.weather.WeatherService

/**
 * 天气预警 Worker —— 每小时检查一次天气数据，判断是否需要提醒用户。
 *
 * 执行流程：
 * 1. 通过 WeatherService 获取天气数据（含当前 + 逐小时 + 逐日预报）
 * 2. 用 WeatherAlertChecker 做纯代码阈值判断（降水/温度剧变/大风/UV/极端温度）
 * 3. 不需要提醒 → 静默结束
 * 4. 需要提醒 → 通过 ReminderLlmClient 调用 LLM 生成个性化提醒文案
 * 5. 通过 NotificationHelper 发送天气预警通知
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

    companion object {
        private const val TAG = "WeatherAlertWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "开始天气预警检查")

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
            return Result.success() // 不重试，下次定时任务再试
        }

        // 3. 代码层判断是否需要预警
        val alertResult = WeatherAlertChecker.check(weather, core)
        if (!alertResult.shouldAlert) {
            Log.d(TAG, "天气正常，无需预警")
            return Result.success()
        }

        Log.d(TAG, "检测到天气预警: severity=${alertResult.severity}, reasons=${alertResult.reasons}")

        // 4. 通过 LLM 生成个性化提醒文案
        val message = generateAlertMessage(core, weather, alertResult)

        // 5. 发送通知
        NotificationHelper.showWeatherAlertNotification(
            applicationContext,
            message,
            alertResult.severity,
        )

        Log.d(TAG, "天气预警通知已发送")
        return Result.success()
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

            val systemPrompt = """你是 Nion，用户的 AI 伙伴。你需要根据天气预警信息，给用户发一条简短温馨的提醒。
规则：
- 不要用 Markdown 格式
- 不要加表情符号前缀
- 语气温暖关心，像朋友一样
- 根据严重程度调整语气：${severityDesc}级别
- 2-3句话即可
- 给出实用建议（如带伞、加衣服、避免户外活动等）"""

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
}
