package com.echonion.nion.ui.companion.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import uniffi.nion_core.NionCore
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 位置服务助手 —— 获取用户位置并缓存到本地。
 *
 * 定位策略：
 * 1. 优先检查用户是否手动指定了城市（settings 中的 user_manual_location）
 * 2. 未手动指定时，通过 FusedLocationProviderClient GPS 定位
 * 3. GPS 获取后通过 Geocoder 反解析城市名，一并缓存
 * 4. GPS 不可用时返回 null，调用方需做降级处理
 *
 * 缓存机制：
 * - 位置数据存储在 NionCore settings 表中
 * - GPS 缓存格式："{latitude},{longitude}"（如 "39.9042,116.4074"）
 * - 手动位置格式：同上，额外存 city_name
 * - 缓存有效期通过 [CACHE_DURATION_MS] 控制（默认 30 分钟）
 *
 * 权限要求：
 * - ACCESS_FINE_LOCATION（精确定位）
 * - 调用前需确保已获取运行时权限，否则返回 null
 */
object LocationHelper {

    private const val TAG = "LocationHelper"

    /** GPS 位置缓存 key */
    private const val SETTING_KEY = "weather_cached_location"

    /** GPS 位置缓存时间戳 key */
    private const val SETTING_KEY_TIMESTAMP = "weather_cached_location_ts"

    /** 缓存有效期：30 分钟（毫秒） */
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L

    /** 手动位置坐标 key，格式 "{lat},{lon}" */
    private const val MANUAL_LOCATION_KEY = "user_manual_location"

    /** 手动位置城市名 key */
    private const val MANUAL_CITY_NAME_KEY = "user_manual_city_name"

    /** GPS 定位后反解析的城市名缓存 key */
    private const val GPS_CITY_NAME_KEY = "weather_cached_city_name"

    /**
     * 获取当前位置（纬度, 经度）。
     *
     * 执行流程：
     * 1. 检查用户是否手动指定了位置（优先级最高）
     * 2. 手动位置存在 → 直接返回手动坐标
     * 3. 未手动指定 → 检查 GPS 缓存是否有效（30 分钟内）
     * 4. 缓存有效 → 直接返回
     * 5. 缓存无效 → 检查位置权限 → GPS 获取 → 缓存 → 返回
     *
     * @param context Android 上下文，用于检查权限和获取 LocationClient
     * @param core NionCore 单例，用于读写位置缓存
     * @return "纬度,经度" 格式的字符串，获取失败返回 null
     */
    suspend fun getLocation(context: Context, core: NionCore): String? {
        // 1. 优先检查手动指定的位置
        val manualLocation = core.getSetting(MANUAL_LOCATION_KEY)
        if (!manualLocation.isNullOrBlank()) {
            Log.d(TAG, "使用手动指定位置: $manualLocation")
            return manualLocation
        }

        // 2. 尝试读取 GPS 缓存
        val cached = tryReadCache(core)
        if (cached != null) {
            Log.d(TAG, "使用缓存位置: $cached")
            return cached
        }

        // 3. 检查位置权限
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "没有位置权限，无法获取位置")
            return null
        }

        // 4. 通过 GPS 获取当前位置
        val location = getCurrentLocation(context) ?: run {
            Log.w(TAG, "GPS 获取位置失败")
            // GPS 失败时，尝试读取过期缓存作为降级
            return tryReadCache(core, ignoreExpiry = true)
        }

        // 5. 缓存并返回
        val locationStr = "${location.latitude},${location.longitude}"
        tryWriteCache(core, locationStr)

        // 6. 异步反解析城市名（不阻塞当前位置返回）
        resolveCityName(context, core, location.latitude, location.longitude)

        Log.d(TAG, "GPS 获取位置成功: $locationStr")
        return locationStr
    }

    /**
     * 强制刷新 GPS 位置（跳过缓存），并返回位置 + 城市名。
     *
     * 用于设置页"立即定位"按钮：点击后清除缓存，重新 GPS 定位，
     * 同步等待 Geocoder 反解析完成，返回完整的位置信息。
     *
     * @param context Android 上下文
     * @param core NionCore 单例
     * @return LocationResult 包含坐标和城市名，失败返回 null
     */
    suspend fun forceRefreshLocation(context: Context, core: NionCore): LocationResult? {
        // 手动模式下不执行 GPS 定位
        val manualLocation = core.getSetting(MANUAL_LOCATION_KEY)
        if (!manualLocation.isNullOrBlank()) {
            val cityName = core.getSetting(MANUAL_CITY_NAME_KEY) ?: ""
            return LocationResult(manualLocation, cityName)
        }

        if (!hasLocationPermission(context)) {
            Log.w(TAG, "没有位置权限，无法强制刷新")
            return null
        }

        val location = getCurrentLocation(context) ?: return null
        val locationStr = "${location.latitude},${location.longitude}"
        tryWriteCache(core, locationStr)

        // 同步反解析城市名（强制刷新时需要立即拿到结果）
        val cityName = resolveCityNameSync(context, core, location.latitude, location.longitude)

        Log.d(TAG, "强制刷新位置成功: $locationStr, 城市: $cityName")
        return LocationResult(locationStr, cityName)
    }

    /**
     * 设置手动位置（用户选择城市后调用）。
     *
     * @param core NionCore 单例
     * @param location "纬度,经度" 格式的坐标
     * @param cityName 城市名称
     */
    fun setManualLocation(core: NionCore, location: String, cityName: String) {
        core.setSetting(MANUAL_LOCATION_KEY, location)
        core.setSetting(MANUAL_CITY_NAME_KEY, cityName)
        Log.d(TAG, "设置手动位置: $cityName ($location)")
    }

    /**
     * 清除手动位置，恢复 GPS 模式。
     *
     * @param core NionCore 单例
     */
    fun clearManualLocation(core: NionCore) {
        core.setSetting(MANUAL_LOCATION_KEY, "")
        core.setSetting(MANUAL_CITY_NAME_KEY, "")
        Log.d(TAG, "清除手动位置，恢复 GPS 模式")
    }

    /**
     * 获取当前显示用的位置信息（城市名 + 坐标）。
     *
     * 优先返回手动指定的城市名，其次返回 GPS 反解析的城市名。
     *
     * @param core NionCore 单例
     * @return LocationResult 或 null
     */
    fun getCurrentLocationInfo(core: NionCore): LocationResult? {
        // 手动位置
        val manualLocation = core.getSetting(MANUAL_LOCATION_KEY)
        if (!manualLocation.isNullOrBlank()) {
            val cityName = core.getSetting(MANUAL_CITY_NAME_KEY) ?: ""
            return LocationResult(manualLocation, cityName)
        }

        // GPS 缓存
        val cached = core.getSetting(SETTING_KEY) ?: return null
        val cityName = core.getSetting(GPS_CITY_NAME_KEY) ?: ""
        return LocationResult(cached, cityName)
    }

    /**
     * 检查是否有精确定位权限。
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 通过 Geocoder 反解析城市名（同步版本，用于强制刷新场景）。
     *
     * 使用 Android 原生 Geocoder，API 33+ 支持直接返回 Address 对象，
     * 旧版本通过 getFromLocation 获取。解析成功后缓存城市名。
     *
     * @param context Android 上下文
     * @param core NionCore 单例，用于缓存城市名
     * @param lat 纬度
     * @param lon 经度
     * @return 城市名，解析失败返回空字符串
     */
    private suspend fun resolveCityNameSync(
        context: Context,
        core: NionCore,
        lat: Double,
        lon: Double,
    ): String = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.CHINA)
            val cityName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 使用异步 Geocoder
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        val name = addresses.firstOrNull()?.let { addr ->
                            // 优先用 locality（城市），其次 subAdminArea（区/县），再次 adminArea（省）
                            addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                        } ?: ""
                        cont.resume(name)
                    }
                }
            } else {
                // Android 13 以下使用同步 Geocoder
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                addresses?.firstOrNull()?.let { addr ->
                    addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                } ?: ""
            }

            // 缓存城市名
            if (cityName.isNotBlank()) {
                core.setSetting(GPS_CITY_NAME_KEY, cityName)
            }
            cityName
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder 反解析城市名失败", e)
            ""
        }
    }

    /**
     * 异步反解析城市名（不阻塞调用方）。
     * 仅缓存结果，不返回值。
     */
    private suspend fun resolveCityName(
        context: Context,
        core: NionCore,
        lat: Double,
        lon: Double,
    ) {
        resolveCityNameSync(context, core, lat, lon)
    }

    /**
     * 通过 FusedLocationProviderClient 获取当前位置。
     *
     * 使用 getCurrentLocation() 而非 getLastLocation()，因为前者会主动触发一次定位，
     * 后者可能返回过时的缓存位置。设置超时 10 秒。
     *
     * @param context Android 上下文
     * @return Location 对象，获取失败返回 null
     */
    private suspend fun getCurrentLocation(context: Context): Location? {
        return try {
            val client: FusedLocationProviderClient =
                LocationServices.getFusedLocationProviderClient(context)

            // CancellationTokenSource 用于取消请求，10 秒超时
            val cts = CancellationTokenSource()
            val timeoutRunnable = Runnable { cts.cancel() }
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(timeoutRunnable, 10_000L)

            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { location ->
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .removeCallbacks(timeoutRunnable)
                        cont.resume(location)
                    }
                    .addOnFailureListener { e ->
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .removeCallbacks(timeoutRunnable)
                        Log.w(TAG, "获取位置失败", e)
                        cont.resume(null)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "位置服务异常", e)
            null
        }
    }

    /**
     * 尝试从缓存读取位置。
     *
     * @param core NionCore 单例
     * @param ignoreExpiry 是否忽略缓存过期时间（用于 GPS 失败时的降级）
     * @return "纬度,经度" 字符串，无缓存或过期返回 null
     */
    private fun tryReadCache(core: NionCore, ignoreExpiry: Boolean = false): String? {
        val cached = core.getSetting(SETTING_KEY) ?: return null
        val timestamp = core.getSetting(SETTING_KEY_TIMESTAMP)?.toLongOrNull() ?: return cached

        // 检查缓存是否过期
        val now = System.currentTimeMillis()
        if (!ignoreExpiry && (now - timestamp) > CACHE_DURATION_MS) {
            return null
        }
        return cached
    }

    /**
     * 写入位置缓存。
     */
    private fun tryWriteCache(core: NionCore, location: String) {
        core.setSetting(SETTING_KEY, location)
        core.setSetting(SETTING_KEY_TIMESTAMP, System.currentTimeMillis().toString())
    }
}

/**
 * 位置信息结果 —— 包含坐标和城市名。
 *
 * @property location "纬度,经度" 格式的坐标字符串
 * @property cityName 城市名称（可能为空字符串，表示未能反解析）
 */
data class LocationResult(
    val location: String,
    val cityName: String,
)
