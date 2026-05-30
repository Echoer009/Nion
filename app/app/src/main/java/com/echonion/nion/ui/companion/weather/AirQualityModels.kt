package com.echonion.nion.ui.companion.weather

/**
 * 空气质量数据模型 —— 定义 Open-Meteo Air Quality API 返回值的 Kotlin 数据结构。
 *
 * 空气质量数据来源：Open-Meteo Air Quality API
 * 端点：https://air-quality-api.open-meteo.com/v1/air-quality
 *
 * 提供的指标：
 * - PM2.5、PM10（颗粒物浓度，μg/m³）
 * - AQI（空气质量指数，中国标准）
 * - NO2、SO2、O3、CO 等常规污染物浓度
 */

/**
 * 当前空气质量数据。
 *
 * @property pm25 PM2.5 浓度（μg/m³），中国 AQI 标准核心指标
 * @property pm10 PM10 浓度（μg/m³）
 * @property aqi 空气质量指数（综合评分，数值越高越差）
 * @property no2 二氧化氮浓度（μg/m³）
 * @property so2 二氧化硫浓度（μg/m³）
 * @property o3 臭氧浓度（μg/m³）
 * @property co 一氧化碳浓度（μg/m³）
 */
data class AirQualityData(
    val pm25: Double,
    val pm10: Double,
    val aqi: Int,
    val no2: Double,
    val so2: Double,
    val o3: Double,
    val co: Double,
)

/**
 * 根据 AQI 数值获取空气质量等级描述。
 *
 * 参考中国 AQI 标准：
 * - 0-50：优
 * - 51-100：良
 * - 101-150：轻度污染
 * - 151-200：中度污染
 * - 201-300：重度污染
 * - 300+：严重污染
 */
fun aqiDescription(aqi: Int): String = when {
    aqi <= 50 -> "优"
    aqi <= 100 -> "良"
    aqi <= 150 -> "轻度污染"
    aqi <= 200 -> "中度污染"
    aqi <= 300 -> "重度污染"
    else -> "严重污染"
}

/**
 * 根据 PM2.5 浓度（μg/m³）估算中国标准 AQI。
 *
 * Open-Meteo 返回的 european_aqi 可能与国标有差异，
 * 此函数提供基于 PM2.5 的简化国标 AQI 估算。
 * 对应关系参考《环境空气质量指数（AQI）技术规定》：
 *
 * PM2.5 (μg/m³) → IAQI 分段线性插值
 */
fun estimateChineseAqi(pm25: Double): Int {
    // PM2.5 浓度与 IAQI 对应的分段点（国标 HJ 633-2012）
    val breakpoints = listOf(
        0.0 to 0, 35.0 to 50, 75.0 to 100, 115.0 to 150,
        150.0 to 200, 250.0 to 300, 350.0 to 400, 500.0 to 500,
    )
    for (i in 0 until breakpoints.lastIndex) {
        val (cLow, iLow) = breakpoints[i]
        val (cHigh, iHigh) = breakpoints[i + 1]
        if (pm25 <= cHigh) {
            // 分段线性插值公式：IAQI = (Ihigh - Ilow) / (Chigh - Clow) * (C - Clow) + Ilow
            return ((iHigh - iLow) / (cHigh - cLow) * (pm25 - cLow) + iLow).toInt().coerceIn(0, 500)
        }
    }
    return 500
}
