package com.echonion.nion.ui.companion.tools

import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.weather.FullWeatherData
import com.echonion.nion.ui.companion.weather.WeatherService
import com.echonion.nion.ui.companion.weather.weatherDescription
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 天气工具 —— 让 Agent 查看当前天气和未来预报。
 *
 * Agent 可在以下场景主动使用此工具：
 * - 用户问天气相关问题（"今天天气怎么样"、"要不要带伞"）
 * - 用户提到户外活动安排时，主动查看天气给出建议
 * - 结合任务上下文：用户有户外任务时，提醒天气状况
 *
 * 支持两种查询模式：
 * - current：返回当前天气实况（温度、湿度、风速、天气状况）
 * - forecast：返回未来 24 小时逐小时预报 + 7 天逐日预报
 *
 * 数据来源：Open-Meteo（免费，无需 API Key），通过 GPS 定位获取用户位置。
 */
object WeatherTool : Tool {

    private const val TAG = "WeatherTool"

    override val name = "weather"
    override val affectsData = emptySet<DataType>()

    override val description = "天气工具 —— 查看用户所在位置的当前天气和未来预报。" +
        "当用户问到天气、户外活动安排、穿衣建议时，或你想结合天气信息给出更好的建议时使用。" +
        "通过 type 参数指定查询类型。" +
        "current：当前天气实况（温度、湿度、风速、天气状况）；" +
        "forecast：未来天气趋势（逐小时 + 逐日预报）。" +
        "注意：此工具需要 GPS 定位权限，如果获取失败会返回错误提示。"

    private val schema = """
    {
        "type": "object",
        "properties": {
            "type": {
                "type": "string",
                "enum": ["current", "forecast"],
                "description": "查询类型：current=当前天气实况, forecast=未来天气趋势预报"
            }
        },
        "required": ["type"]
    }
    """.trimIndent()

    override fun parametersSchema(): JSONObject = JSONObject(schema)

    /**
     * 执行天气查询。
     *
     * 从 NionApp 获取 Context，调用 WeatherService 获取数据，
     * 根据 type 参数返回格式化的天气文本给 LLM。
     *
     * @param params 包含 type（"current" 或 "forecast"）的参数
     * @param core NionCore 单例（用于读取位置缓存和天气缓存）
     * @return 格式化的天气信息文本
     */
    override suspend fun execute(params: JSONObject, core: NionCore): String {
        val type = params.getString("type")

        // 获取 NionApp 上下文（WeatherService 需要 Context 来访问 GPS）
        val app = NionApp.instance
            ?: return """{"error":"无法获取应用上下文"}"""

        val weather = WeatherService.fetchWeather(app, core)
            ?: return """{"error":"获取天气数据失败，可能是定位不可用或网络问题。请告诉用户天气功能暂时不可用，建议检查定位权限和网络连接。"}"""

        return when (type) {
            "current" -> formatCurrentWeather(weather)
            "forecast" -> formatForecast(weather)
            else -> """{"error":"不支持的查询类型: $type"}"""
        }
    }

    /**
     * 格式化当前天气实况为 LLM 可理解的文本。
     *
     * 输出格式示例：
     * ```
     * 当前天气：晴
     * 温度：22°C
     * 湿度：65%
     * 风速：12 km/h
     * 降水量：0 mm
     * ```
     */
    private fun formatCurrentWeather(data: FullWeatherData): String {
        val current = data.current
        val desc = weatherDescription(current.weatherCode)

        // 附带今天的温度范围作为补充信息
        val todayRange = if (data.daily.days.isNotEmpty()) {
            val today = data.daily.days[0]
            "\n今日温度范围：${"%.0f".format(today.tempMin)}°C ~ ${"%.0f".format(today.tempMax)}°C"
        } else ""

        return buildString {
            append("当前天气：$desc\n")
            append("温度：${"%.1f".format(current.temperature)}°C\n")
            append("湿度：${current.humidity}%\n")
            append("风速：${"%.1f".format(current.windSpeed)} km/h\n")
            append("降水量：${"%.1f".format(current.precipitation)} mm")
            append(todayRange)
        }
    }

    /**
     * 格式化天气预报为 LLM 可理解的文本。
     *
     * 包含两部分：
     * 1. 未来 24 小时逐小时预报（温度、降水概率、天气、风速、UV）
     * 2. 未来 7 天逐日预报（最高/最低温、总降水、最大风速、UV峰值）
     */
    private fun formatForecast(data: FullWeatherData): String {
        val sb = StringBuilder()

        // 逐小时预报（未来 24 小时）
        sb.appendLine("=== 未来 24 小时逐小时预报 ===")
        for (h in data.hourly.hours) {
            val timeLabel = h.hour.substring(11) // 取 "HH:00" 部分
            val desc = weatherDescription(h.weatherCode)
            val precipMark = if (h.precipitationProb >= 50) " ⚠" else ""
            sb.appendLine(
                "$timeLabel | $desc | ${"%.0f".format(h.temperature)}°C | 降水${h.precipitationProb}% | 风速${"%.0f".format(h.windSpeed)}km/h | UV${"%.0f".format(h.uvIndex)}$precipMark"
            )
        }

        sb.appendLine()

        // 逐日预报（未来 7 天）
        sb.appendLine("=== 未来 7 天逐日预报 ===")
        for (d in data.daily.days) {
            val dateLabel = d.date.substring(5) // 取 "MM-DD" 部分
            sb.appendLine(
                "$dateLabel | ${"%.0f".format(d.tempMin)}°C ~ ${"%.0f".format(d.tempMax)}°C | 降水${d.precipitationProbabilityMax}% ${"%.1f".format(d.precipitationSum)}mm | 最大风速${"%.0f".format(d.windSpeedMax)}km/h | UV峰值${"%.0f".format(d.uvIndexMax)}"
            )
        }

        return sb.toString()
    }
}
