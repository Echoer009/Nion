package com.echonion.nion.ui.companion.weather

/**
 * 天气数据模型 —— 定义 Open-Meteo API 返回值的 Kotlin 数据结构。
 *
 * 三层模型：
 * - [CurrentWeather]：当前天气实况（温度、湿度、风速、天气代码、降水）
 * - [HourlyForecast]：未来 24-48 小时逐小时预报（温度、降水概率、风速、紫外线）
 * - [DailyForecast]：未来 7 天逐日预报（最高/最低温、总降水、最大风速、紫外线峰值）
 *
 * WMO 天气代码映射见 [weatherDescription]，用于将数字代码转为人类可读描述。
 */

/**
 * 当前天气实况。
 *
 * @property temperature 当前气温（°C）
 * @property humidity 相对湿度（%）
 * @property windSpeed 10 米高度风速（km/h）
 * @property weatherCode WMO 天气代码（0=晴, 1-3=多云, 45-48=雾, 51-67=雨, 71-77=雪, 80-82=阵雨, 95-99=雷暴）
 * @property precipitation 过去 1 小时降水量（mm）
 */
data class CurrentWeather(
    val temperature: Double,
    val humidity: Int,
    val windSpeed: Double,
    val weatherCode: Int,
    val precipitation: Double,
)

/**
 * 逐小时预报中的单条数据。
 *
 * @property hour 小时标识，格式 "YYYY-MM-DDTHH:00"
 * @property temperature 该小时气温（°C）
 * @property precipitationProb 降水概率（0-100%）
 * @property windSpeed 该小时风速（km/h）
 * @property weatherCode WMO 天气代码
 * @property uvIndex 紫外线指数（0-11+）
 * @property precipitation 该小时降水量（mm）
 */
data class HourlyData(
    val hour: String,
    val temperature: Double,
    val precipitationProb: Int,
    val windSpeed: Double,
    val weatherCode: Int,
    val uvIndex: Double,
    val precipitation: Double,
)

/**
 * 逐小时预报集合。
 *
 * @property hours 逐小时数据列表，通常包含未来 24-48 小时
 */
data class HourlyForecast(
    val hours: List<HourlyData>,
)

/**
 * 逐日预报中的单条数据。
 *
 * @property date 日期，格式 "YYYY-MM-DD"
 * @property tempMax 当日最高气温（°C）
 * @property tempMin 当日最低气温（°C）
 * @property precipitationSum 当日总降水量（mm）
 * @property windSpeedMax 当日最大风速（km/h）
 * @property uvIndexMax 当日紫外线指数峰值
 * @property precipitationProbabilityMax 当日最大降水概率（%）
 */
data class DailyData(
    val date: String,
    val tempMax: Double,
    val tempMin: Double,
    val precipitationSum: Double,
    val windSpeedMax: Double,
    val uvIndexMax: Double,
    val precipitationProbabilityMax: Int,
)

/**
 * 逐日预报集合。
 *
 * @property days 逐日数据列表，通常包含未来 7 天
 */
data class DailyForecast(
    val days: List<DailyData>,
)

/**
 * 完整天气数据 —— 聚合当前实况 + 逐小时预报 + 逐日预报。
 *
 * 用于 [WeatherService] 返回完整结果，供 WeatherTool 和预警 Worker 使用。
 */
data class FullWeatherData(
    val current: CurrentWeather,
    val hourly: HourlyForecast,
    val daily: DailyForecast,
)

/**
 * 天气预警判断结果 —— 由 [WeatherAlertChecker] 产出。
 *
 * @property shouldAlert 是否需要提醒用户
 * @property reasons 预警原因列表（如"未来 3 小时降水概率 85%"），用于传给 LLM 生成个性化文案
 * @property severity 预警严重程度：info / warning / urgent
 */
data class WeatherAlertResult(
    val shouldAlert: Boolean,
    val reasons: List<String>,
    val severity: String,
)

/**
 * WMO 天气代码 → 中文描述映射。
 *
 * 参考：https://open-meteo.com/en/docs#weathervariables
 * WMO Code Table 4677 简化版。
 */
fun weatherDescription(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "大部晴"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "雾"
    51 -> "小毛毛雨"
    53 -> "中毛毛雨"
    55 -> "大毛毛雨"
    56 -> "冻毛毛雨（小）"
    57 -> "冻毛毛雨（大）"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66 -> "冻雨（小）"
    67 -> "冻雨（大）"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    77 -> "雪粒"
    80 -> "小阵雨"
    81 -> "中阵雨"
    82 -> "大阵雨"
    85 -> "小阵雪"
    86 -> "大阵雪"
    95 -> "雷暴"
    96 -> "雷暴伴小冰雹"
    99 -> "雷暴伴大冰雹"
    else -> "未知 ($code)"
}

/**
 * 判断 WMO 天气代码是否表示降水（雨/雪/冰雹）。
 */
fun isPrecipitation(code: Int): Boolean = code in 51..67 || code in 71..77 || code in 80..82 || code in 85..86 || code in 95..99
