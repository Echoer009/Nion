package com.echonion.nion.ui.companion.weather

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import uniffi.nion_core.NionCore
import java.util.concurrent.TimeUnit

/**
 * 天气服务 —— 调用 Open-Meteo API 获取天气数据。
 *
 * Open-Meteo 是免费的天气 API，无需 API Key，支持全球城市（含中国）。
 * 直接通过经纬度查询，返回当前天气 + 逐小时预报 + 逐日预报。
 *
 * API 端点：https://api.open-meteo.com/v1/forecast
 * 请求参数：
 * - latitude / longitude：经纬度
 * - current：当前天气字段（temperature_2m, relative_humidity_2m, wind_speed_10m, weather_code, precipitation）
 * - hourly：逐小时字段（temperature_2m, precipitation_probability, wind_speed_10m, weather_code, uv_index, precipitation）
 * - daily：逐日字段（temperature_2m_max, temperature_2m_min, precipitation_sum, wind_speed_10m_max, uv_index_max, precipitation_probability_max）
 * - timezone：auto（自动检测时区）
 * - forecast_days：7（预报天数）
 *
 * 缓存策略：
 * - 天气数据缓存 15 分钟，避免频繁请求
 * - 缓存存储在 NionCore settings 中
 *
 * 使用方式：
 * ```kotlin
 * val weather = WeatherService.fetchWeather(context, core)
 * // weather.current.temperature 等字段可直接使用
 * ```
 */
object WeatherService {

    private const val TAG = "WeatherService"

    /** Open-Meteo API 基础 URL */
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    /** 天气数据缓存 key */
    private const val CACHE_KEY = "weather_data_cache"
    private const val CACHE_TS_KEY = "weather_data_cache_ts"

    /** 缓存有效期：15 分钟（毫秒） */
    private const val CACHE_DURATION_MS = 15 * 60 * 1000L

    /** OkHttp 客户端，设置 10 秒超时 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 获取完整天气数据（当前 + 逐小时 + 逐日）。
     *
     * 执行流程：
     * 1. 获取用户位置（GPS 或缓存）
     * 2. 检查天气数据缓存是否有效
     * 3. 缓存无效 → 调用 Open-Meteo API
     * 4. 解析 JSON → 构建数据模型 → 缓存 → 返回
     *
     * @param context Android 上下文，用于 GPS 定位
     * @param core NionCore 单例，用于读取位置和缓存
     * @return 完整天气数据，获取失败返回 null
     */
    suspend fun fetchWeather(context: Context, core: NionCore): FullWeatherData? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 获取位置
                val location = LocationHelper.getLocation(context, core)
                if (location == null) {
                    Log.w(TAG, "无法获取位置，跳过天气查询")
                    return@withContext null
                }
                val parts = location.split(",")
                val lat = parts[0].trim()
                val lon = parts[1].trim()

                // 2. 检查缓存（同位置 15 分钟内不重复请求）
                val cached = tryReadCache(core, location)
                if (cached != null) {
                    Log.d(TAG, "使用天气缓存")
                    return@withContext cached
                }

                // 3. 调用 Open-Meteo API
                val url = buildUrl(lat, lon)
                Log.d(TAG, "请求天气 API: $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body.isNullOrBlank()) {
                    Log.e(TAG, "天气 API 请求失败: code=${response.code}")
                    return@withContext null
                }

                // 4. 解析 JSON 响应
                val weather = parseResponse(body)

                // 5. 同时获取空气质量数据（独立缓存，失败不影响天气数据）
                val airQuality = try {
                    AirQualityService.fetchAirQuality(context, core)
                } catch (e: Exception) {
                    Log.w(TAG, "获取空气质量数据失败，不影响天气数据", e)
                    null
                }

                // 6. 合并天气 + 空气质量 + 缓存天气原始 JSON
                val fullData = weather?.copy(airQuality = airQuality)

                // 7. 缓存天气结果（空气质量有自己独立的缓存）
                if (weather != null) {
                    tryWriteCache(core, location, body)
                    Log.d(TAG, "天气数据获取成功: 当前温度=${weather.current.temperature}°C, 天气=${weatherDescription(weather.current.weatherCode)}")
                    if (airQuality != null) {
                        Log.d(TAG, "空气质量: PM2.5=${airQuality.pm25}, AQI=${airQuality.aqi} (${aqiDescription(airQuality.aqi)})")
                    }
                }

                fullData
            } catch (e: Exception) {
                Log.e(TAG, "获取天气数据异常", e)
                null
            }
        }
    }

    /**
     * 获取简化的当前天气描述文本。
     * 用于问候和通知等场景，不需要完整数据结构。
     *
     * @return 格式化的天气描述文本，包含体感温度和空气质量（如有）
     */
    suspend fun fetchWeatherSummary(context: Context, core: NionCore): String? {
        val weather = fetchWeather(context, core) ?: return null
        val current = weather.current
        val desc = weatherDescription(current.weatherCode)
        val dayNight = if (current.isDay) "" else "（夜间）"
        val base = "$desc$dayNight，气温 ${current.temperature}°C"
        val apparent = if (current.apparentTemperature != current.temperature) {
            "，体感 ${current.apparentTemperature}°C"
        } else ""
        val baseInfo = "$base$apparent，湿度 ${current.humidity}%，风速 ${current.windSpeed} km/h"

        // 附加空气质量信息
        val aqInfo = weather.airQuality?.let { aq ->
            "，PM2.5 ${aq.pm25}μg/m³，AQI ${aq.aqi}（${aqiDescription(aq.aqi)}）"
        } ?: ""

        return baseInfo + aqInfo
    }

    /**
     * 构建 Open-Meteo API 请求 URL。
     *
     * current 字段包含：气温、体感温度、湿度、风速、天气代码、降水、是否白天、云量、能见度。
     * hourly / daily 字段同前，保持逐小时和逐日预报数据。
     */
    private fun buildUrl(lat: String, lon: String): String {
        return "$BASE_URL?" +
            "latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,precipitation,is_day,cloud_cover,visibility" +
            "&hourly=temperature_2m,precipitation_probability,wind_speed_10m,weather_code,uv_index,precipitation" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max,uv_index_max,precipitation_probability_max" +
            "&timezone=auto" +
            "&forecast_days=7"
    }

    /**
     * 解析 Open-Meteo API 的 JSON 响应为 Kotlin 数据模型。
     */
    private fun parseResponse(body: String): FullWeatherData? {
        return try {
            val json = JSONObject(body)

            // 解析 current 部分
            val currentJson = json.getJSONObject("current")
            val current = CurrentWeather(
                temperature = currentJson.getDouble("temperature_2m"),
                humidity = currentJson.getInt("relative_humidity_2m"),
                windSpeed = currentJson.getDouble("wind_speed_10m"),
                weatherCode = currentJson.getInt("weather_code"),
                precipitation = currentJson.optDouble("precipitation", 0.0),
                // 体感温度：综合考虑风速、湿度、辐射的"实际感受"温度
                apparentTemperature = currentJson.optDouble("apparent_temperature", currentJson.getDouble("temperature_2m")),
                // 是否白天：1=白天，0=夜间，影响天气描述（如"晴"vs"晴夜"）
                isDay = currentJson.optInt("is_day", 1) == 1,
                // 云量：0-100%，天空被云覆盖的比例
                cloudCover = currentJson.optInt("cloud_cover", 0),
                // 能见度：单位米，低于 1000m 为大雾级别
                visibility = currentJson.optDouble("visibility", 10000.0),
            )

            // 解析 hourly 部分
            val hourlyJson = json.getJSONObject("hourly")
            val hourlyTimes = hourlyJson.getJSONArray("time")
            val hourlyTemps = hourlyJson.getJSONArray("temperature_2m")
            val hourlyPrecipProb = hourlyJson.getJSONArray("precipitation_probability")
            val hourlyWind = hourlyJson.getJSONArray("wind_speed_10m")
            val hourlyWeatherCode = hourlyJson.getJSONArray("weather_code")
            val hourlyUv = hourlyJson.getJSONArray("uv_index")
            val hourlyPrecip = hourlyJson.getJSONArray("precipitation")

            // 只取未来 24 小时的数据（从当前时间开始）
            // 找到当前小时的索引
            val now = java.time.LocalDateTime.now()
            val nowStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00"))
            var startIdx = 0
            for (i in 0 until hourlyTimes.length()) {
                if (hourlyTimes.getString(i) >= nowStr) {
                    startIdx = i
                    break
                }
            }

            // 取未来 24 小时
            val hours = mutableListOf<HourlyData>()
            val endIdx = minOf(startIdx + 24, hourlyTimes.length())
            for (i in startIdx until endIdx) {
                hours.add(
                    HourlyData(
                        hour = hourlyTimes.getString(i),
                        temperature = hourlyTemps.getDouble(i),
                        precipitationProb = hourlyPrecipProb.optInt(i, 0),
                        windSpeed = hourlyWind.getDouble(i),
                        weatherCode = hourlyWeatherCode.getInt(i),
                        uvIndex = hourlyUv.optDouble(i, 0.0),
                        precipitation = hourlyPrecip.optDouble(i, 0.0),
                    )
                )
            }

            // 解析 daily 部分
            val dailyJson = json.getJSONObject("daily")
            val dailyTime = dailyJson.getJSONArray("time")
            val dailyMax = dailyJson.getJSONArray("temperature_2m_max")
            val dailyMin = dailyJson.getJSONArray("temperature_2m_min")
            val dailyPrecipSum = dailyJson.getJSONArray("precipitation_sum")
            val dailyWindMax = dailyJson.getJSONArray("wind_speed_10m_max")
            val dailyUvMax = dailyJson.getJSONArray("uv_index_max")
            val dailyPrecipProbMax = dailyJson.getJSONArray("precipitation_probability_max")

            val days = mutableListOf<DailyData>()
            for (i in 0 until dailyTime.length()) {
                days.add(
                    DailyData(
                        date = dailyTime.getString(i),
                        tempMax = dailyMax.getDouble(i),
                        tempMin = dailyMin.getDouble(i),
                        precipitationSum = dailyPrecipSum.getDouble(i),
                        windSpeedMax = dailyWindMax.getDouble(i),
                        uvIndexMax = dailyUvMax.optDouble(i, 0.0),
                        precipitationProbabilityMax = dailyPrecipProbMax.optInt(i, 0),
                    )
                )
            }

            FullWeatherData(
                current = current,
                hourly = HourlyForecast(hours),
                daily = DailyForecast(days),
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析天气数据失败", e)
            null
        }
    }

    /**
     * 尝试从缓存读取天气数据。
     */
    private fun tryReadCache(core: NionCore, location: String): FullWeatherData? {
        val cachedLocation = core.getSetting("weather_cache_location") ?: return null
        if (cachedLocation != location) return null

        val timestamp = core.getSetting(CACHE_TS_KEY)?.toLongOrNull() ?: return null
        if ((System.currentTimeMillis() - timestamp) > CACHE_DURATION_MS) return null

        val cachedJson = core.getSetting(CACHE_KEY) ?: return null
        return parseResponse(cachedJson)
    }

    /**
     * 写入天气数据缓存。
     */
    private fun tryWriteCache(core: NionCore, location: String, body: String) {
        core.setSetting(CACHE_KEY, body)
        core.setSetting(CACHE_TS_KEY, System.currentTimeMillis().toString())
        core.setSetting("weather_cache_location", location)
    }
}
