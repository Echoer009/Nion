package com.echonion.nion.ui.companion.weather

import com.echonion.nion.NionApp
import uniffi.nion_core.NionCore

/**
 * 天气预警判断器 —— 纯代码逻辑判断是否需要向用户发送天气预警。
 *
 * 由 [WeatherAlertWorker] 每小时调用一次，根据阈值判断天气状况是否需要提醒。
 * 判断维度包括：降水、温度剧变、大风、强紫外线、极端温度。
 *
 * 设计原则：
 * - **代码优先**：先用阈值判断，避免每次都调用 LLM（节省 API 费用）
 * - **去重机制**：同类预警在 6 小时内不重复提醒（通过 settings 表记录上次提醒时间）
 * - **分级预警**：severity 分为 info / warning / urgent，用于 LLM 调整语气
 */
object WeatherAlertChecker {

    private const val TAG = "WeatherAlertChecker"

    /** 降水概率阈值（%）：超过此值触发预警 */
    private const val PRECIP_PROB_THRESHOLD = 60

    /** 温度剧变阈值（°C）：24 小时内温差超过此值触发预警 */
    private const val TEMP_CHANGE_THRESHOLD = 8.0

    /** 大风阈值（km/h）：风速超过此值触发预警 */
    private const val STRONG_WIND_THRESHOLD = 40.0

    /** 紫外线强阈值：UV 指数超过此值触发预警 */
    private const val UV_HIGH_THRESHOLD = 8.0

    /** 极端高温阈值（°C） */
    private const val EXTREME_HEAT_THRESHOLD = 38.0

    /** 极端低温阈值（°C） */
    private const val EXTREME_COLD_THRESHOLD = -10.0

    /** 同类预警去重间隔（毫秒）：6 小时 */
    private const val ALERT_DEDUP_MS = 6 * 60 * 60 * 1000L

    /** 去重 key 前缀 */
    private const val DEDUP_KEY_PREFIX = "weather_alert_dedup_"

    /**
     * 判断当前天气数据是否需要向用户发送预警。
     *
     * @param data 完整天气数据
     * @param core NionCore 单例，用于读写去重时间戳
     * @return 预警判断结果，包含是否需要提醒、原因列表、严重程度
     */
    fun check(data: FullWeatherData, core: NionCore): WeatherAlertResult {
        val reasons = mutableListOf<String>()
        var maxSeverity = "info"

        // 1. 降水预警：检查未来 12 小时内的降水概率和类型
        val precipResult = checkPrecipitation(data)
        if (precipResult != null) {
            reasons.add(precipResult.first)
            if (severityOrder(precipResult.second) > severityOrder(maxSeverity)) {
                maxSeverity = precipResult.second
            }
        }

        // 2. 温度剧变预警：今天 vs 明天的温差
        val tempResult = checkTemperatureChange(data)
        if (tempResult != null) {
            reasons.add(tempResult.first)
            if (severityOrder(tempResult.second) > severityOrder(maxSeverity)) {
                maxSeverity = tempResult.second
            }
        }

        // 3. 大风预警
        val windResult = checkStrongWind(data)
        if (windResult != null) {
            reasons.add(windResult.first)
            if (severityOrder(windResult.second) > severityOrder(maxSeverity)) {
                maxSeverity = windResult.second
            }
        }

        // 4. 强紫外线预警
        val uvResult = checkUv(data)
        if (uvResult != null) {
            reasons.add(uvResult.first)
            if (severityOrder(uvResult.second) > severityOrder(maxSeverity)) {
                maxSeverity = uvResult.second
            }
        }

        // 5. 极端温度预警
        val extremeResult = checkExtremeTemperature(data)
        if (extremeResult != null) {
            reasons.add(extremeResult.first)
            if (severityOrder(extremeResult.second) > severityOrder(maxSeverity)) {
                maxSeverity = extremeResult.second
            }
        }

        // 6. 去重检查：过滤掉 6 小时内已经提醒过的类型
        val filteredReasons = reasons.filter { reason ->
            val dedupKey = DEDUP_KEY_PREFIX + reason.hashCode().toString()
            val lastAlert = core.getSetting(dedupKey)?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastAlert > ALERT_DEDUP_MS) {
                // 记录本次提醒时间
                core.setSetting(dedupKey, now.toString())
                true
            } else {
                false
            }
        }

        return WeatherAlertResult(
            shouldAlert = filteredReasons.isNotEmpty(),
            reasons = filteredReasons,
            severity = maxSeverity,
        )
    }

    /**
     * 检查降水情况：未来 12 小时内是否有显著降水。
     */
    private fun checkPrecipitation(data: FullWeatherData): Pair<String, String>? {
        // 在逐小时预报中查找未来 12 小时内的高降水概率时段
        var maxProb = 0
        var precipHour = ""
        var hasRain = false
        var hasSnow = false

        for (h in data.hourly.hours.take(12)) {
            if (h.precipitationProb > maxProb) {
                maxProb = h.precipitationProb
                precipHour = h.hour
            }
            if (h.precipitationProb >= PRECIP_PROB_THRESHOLD) {
                if (isSnowCode(h.weatherCode)) hasSnow = true
                else if (isPrecipitation(h.weatherCode)) hasRain = true
            }
        }

        if (maxProb < PRECIP_PROB_THRESHOLD) return null

        val timeLabel = formatHour(precipHour)
        val type = when {
            hasSnow -> "降雪"
            hasRain -> "降雨"
            else -> "降水"
        }
        val severity = if (maxProb >= 85) "urgent" else "warning"

        return Pair("未来12小时内${type}概率达 $maxProb%（$timeLabel 前后）", severity)
    }

    /**
     * 检查温度剧变：今天和明天的温差。
     */
    private fun checkTemperatureChange(data: FullWeatherData): Pair<String, String>? {
        if (data.daily.days.size < 2) return null

        val today = data.daily.days[0]
        val tomorrow = data.daily.days[1]
        val todayAvg = (today.tempMax + today.tempMin) / 2
        val tomorrowAvg = (tomorrow.tempMax + tomorrow.tempMin) / 2
        val diff = kotlin.math.abs(tomorrowAvg - todayAvg)

        if (diff < TEMP_CHANGE_THRESHOLD) return null

        val direction = if (tomorrowAvg > todayAvg) "升温" else "降温"
        val severity = if (diff >= 15.0) "urgent" else "warning"

        return Pair("明天将${direction} ${"%.0f".format(diff)}°C（今天 ${"%.0f".format(todayAvg)}°C → 明天 ${"%.0f".format(tomorrowAvg)}°C）", severity)
    }

    /**
     * 检查大风情况：未来 24 小时内是否有大风。
     */
    private fun checkStrongWind(data: FullWeatherData): Pair<String, String>? {
        var maxWind = 0.0
        var windHour = ""

        for (h in data.hourly.hours.take(24)) {
            if (h.windSpeed > maxWind) {
                maxWind = h.windSpeed
                windHour = h.hour
            }
        }

        if (maxWind < STRONG_WIND_THRESHOLD) return null

        val severity = if (maxWind >= 60) "urgent" else "warning"
        val timeLabel = formatHour(windHour)

        return Pair("未来24小时内有大风，风速可达 ${"%.0f".format(maxWind)} km/h（$timeLabel 前后）", severity)
    }

    /**
     * 检查紫外线强度：今天 UV 指数峰值。
     */
    private fun checkUv(data: FullWeatherData): Pair<String, String>? {
        if (data.daily.days.isEmpty()) return null
        val todayUv = data.daily.days[0].uvIndexMax

        if (todayUv < UV_HIGH_THRESHOLD) return null

        val severity = if (todayUv >= 11) "urgent" else "info"
        return Pair("今天紫外线指数高达 ${"%.0f".format(todayUv)}，注意防晒", severity)
    }

    /**
     * 检查极端温度：今天的最高/最低温是否达到极端值。
     */
    private fun checkExtremeTemperature(data: FullWeatherData): Pair<String, String>? {
        if (data.daily.days.isEmpty()) return null
        val today = data.daily.days[0]

        if (today.tempMax >= EXTREME_HEAT_THRESHOLD) {
            return Pair("今天最高气温达 ${"%.0f".format(today.tempMax)}°C，高温预警", "urgent")
        }
        if (today.tempMin <= EXTREME_COLD_THRESHOLD) {
            return Pair("今天最低气温达 ${"%.0f".format(today.tempMin)}°C，严寒预警", "urgent")
        }

        return null
    }

    /**
     * 判断 WMO 天气代码是否为降雪类型。
     */
    private fun isSnowCode(code: Int): Boolean = code in 71..77 || code in 85..86

    /**
     * 严重程度排序值，用于取最严重的级别。
     */
    private fun severityOrder(severity: String): Int = when (severity) {
        "info" -> 0
        "warning" -> 1
        "urgent" -> 2
        else -> 0
    }

    /**
     * 格式化小时字符串为可读格式。
     * "2026-05-27T14:00" → "今天14:00" 或 "明天02:00"
     */
    private fun formatHour(hour: String): String {
        val today = java.time.LocalDate.now().toString()
        val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
        return when {
            hour.startsWith(today) -> "今天" + hour.substring(11)
            hour.startsWith(tomorrow) -> "明天" + hour.substring(11)
            else -> hour
        }
    }
}
