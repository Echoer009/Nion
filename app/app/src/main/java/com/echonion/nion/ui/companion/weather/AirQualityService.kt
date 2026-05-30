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
 * 空气质量服务 —— 调用 Open-Meteo Air Quality API 获取 AQI、PM2.5 等数据。
 *
 * API 端点：https://air-quality-api.open-meteo.com/v1/air-quality
 * 免费、无需 API Key，支持全球范围。
 *
 * 请求参数：
 * - latitude / longitude：经纬度
 * - current：pm2_5, pm10, nitrogen_dioxide, sulphur_dioxide, ozone, carbon_monoxide, european_aqi
 * - timezone：auto
 *
 * 缓存策略：
 * - 缓存 30 分钟，与天气缓存独立
 * - 缓存存储在 NionCore settings 中
 *
 * 注意：Open-Meteo 返回的 european_aqi 是欧洲标准 AQI，
 * 实际展示时会用 [estimateChineseAqi] 基于 PM2.5 估算中国标准 AQI。
 */
object AirQualityService {

    private const val TAG = "AirQualityService"

    /** Air Quality API 端点 */
    private const val BASE_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

    /** 空气质量缓存 key */
    private const val CACHE_KEY = "aqi_data_cache"
    private const val CACHE_TS_KEY = "aqi_data_cache_ts"
    private const val CACHE_LOCATION_KEY = "aqi_cache_location"

    /** 缓存有效期：30 分钟（毫秒），空气质量变化较慢，可以比天气缓存久一些 */
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L

    /** OkHttp 客户端，复用 WeatherService 的超时配置 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 获取当前空气质量数据。
     *
     * 执行流程：
     * 1. 获取用户位置（GPS 或手动）
     * 2. 检查缓存是否有效（30 分钟内 + 同位置）
     * 3. 缓存无效 → 调用 Air Quality API
     * 4. 解析 JSON → 构建数据模型 → 用 PM2.5 估算国标 AQI → 缓存 → 返回
     *
     * @param context Android 上下文
     * @param core NionCore 单例
     * @return 空气质量数据，获取失败返回 null
     */
    suspend fun fetchAirQuality(context: Context, core: NionCore): AirQualityData? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 获取位置
                val location = LocationHelper.getLocation(context, core)
                if (location == null) {
                    Log.w(TAG, "无法获取位置，跳过空气质量查询")
                    return@withContext null
                }

                // 2. 检查缓存
                val cached = tryReadCache(core, location)
                if (cached != null) {
                    Log.d(TAG, "使用空气质量缓存")
                    return@withContext cached
                }

                // 3. 调用 API
                val parts = location.split(",")
                val lat = parts[0].trim()
                val lon = parts[1].trim()
                val url = buildUrl(lat, lon)
                Log.d(TAG, "请求空气质量 API: $url")

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (!response.isSuccessful || body.isNullOrBlank()) {
                    Log.e(TAG, "空气质量 API 请求失败: code=${response.code}")
                    return@withContext null
                }

                // 4. 解析 JSON
                val aqData = parseResponse(body)
                if (aqData != null) {
                    tryWriteCache(core, location, body)
                    Log.d(TAG, "空气质量获取成功: PM2.5=${aqData.pm25}, AQI=${aqData.aqi} (${aqiDescription(aqData.aqi)})")
                }

                aqData
            } catch (e: Exception) {
                Log.e(TAG, "获取空气质量数据异常", e)
                null
            }
        }
    }

    /**
     * 构建 Air Quality API 请求 URL。
     *
     * 请求的 current 字段：
     * - pm2_5 / pm10：颗粒物浓度
     * - nitrogen_dioxide / sulphur_dioxide / ozone / carbon_monoxide：常规污染物
     * - european_aqi：欧洲标准 AQI（仅供参考，实际用 PM2.5 估算国标）
     */
    private fun buildUrl(lat: String, lon: String): String {
        return "$BASE_URL?" +
            "latitude=$lat&longitude=$lon" +
            "&current=pm2_5,pm10,nitrogen_dioxide,sulphur_dioxide,ozone,carbon_monoxide,european_aqi" +
            "&timezone=auto"
    }

    /**
     * 解析 Air Quality API 的 JSON 响应为 AirQualityData。
     */
    private fun parseResponse(body: String): AirQualityData? {
        return try {
            val json = JSONObject(body)
            val currentJson = json.getJSONObject("current")

            val pm25 = currentJson.optDouble("pm2_5", 0.0)
            val pm10 = currentJson.optDouble("pm10", 0.0)
            // 用 PM2.5 估算中国标准 AQI，比 european_aqi 更准确
            val aqi = estimateChineseAqi(pm25)

            AirQualityData(
                pm25 = pm25,
                pm10 = pm10,
                aqi = aqi,
                no2 = currentJson.optDouble("nitrogen_dioxide", 0.0),
                so2 = currentJson.optDouble("sulphur_dioxide", 0.0),
                o3 = currentJson.optDouble("ozone", 0.0),
                co = currentJson.optDouble("carbon_monoxide", 0.0),
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析空气质量数据失败", e)
            null
        }
    }

    /**
     * 尝试从缓存读取空气质量数据。
     */
    private fun tryReadCache(core: NionCore, location: String): AirQualityData? {
        val cachedLocation = core.getSetting(CACHE_LOCATION_KEY) ?: return null
        if (cachedLocation != location) return null

        val timestamp = core.getSetting(CACHE_TS_KEY)?.toLongOrNull() ?: return null
        if ((System.currentTimeMillis() - timestamp) > CACHE_DURATION_MS) return null

        val cachedJson = core.getSetting(CACHE_KEY) ?: return null
        return parseResponse(cachedJson)
    }

    /**
     * 写入空气质量数据缓存。
     */
    private fun tryWriteCache(core: NionCore, location: String, body: String) {
        core.setSetting(CACHE_KEY, body)
        core.setSetting(CACHE_TS_KEY, System.currentTimeMillis().toString())
        core.setSetting(CACHE_LOCATION_KEY, location)
    }
}
