package com.echonion.nion.ui.companion.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import uniffi.nion_core.NionCore
import kotlin.coroutines.resume

/**
 * 位置服务助手 —— 获取用户当前 GPS 坐标并缓存到本地。
 *
 * 定位策略：
 * 1. 优先使用缓存位置（避免频繁请求 GPS，省电）
 * 2. 缓存过期或首次使用时，通过 FusedLocationProviderClient 获取当前位置
 * 3. GPS 不可用时返回 null，调用方需做降级处理
 *
 * 缓存机制：
 * - 位置数据存储在 NionCore settings 表中，key = "weather_cached_location"
 * - 格式："{latitude},{longitude}" （如 "39.9042,116.4074"）
 * - 缓存有效期通过 [CACHE_DURATION_MS] 控制（默认 30 分钟）
 *
 * 权限要求：
 * - ACCESS_FINE_LOCATION（精确定位）
 * - 调用前需确保已获取运行时权限，否则返回 null
 */
object LocationHelper {

    private const val TAG = "LocationHelper"

    /** 位置缓存 key */
    private const val SETTING_KEY = "weather_cached_location"

    /** 缓存时间戳 key */
    private const val SETTING_KEY_TIMESTAMP = "weather_cached_location_ts"

    /** 缓存有效期：30 分钟（毫秒） */
    private const val CACHE_DURATION_MS = 30 * 60 * 1000L

    /**
     * 获取当前位置（纬度, 经度）。
     *
     * 执行流程：
     * 1. 检查缓存是否有效（30 分钟内）
     * 2. 缓存有效 → 直接返回
     * 3. 缓存无效 → 检查位置权限 → GPS 获取 → 缓存 → 返回
     *
     * @param context Android 上下文，用于检查权限和获取 LocationClient
     * @param core NionCore 单例，用于读写位置缓存
     * @return "纬度,经度" 格式的字符串，获取失败返回 null
     */
    suspend fun getLocation(context: Context, core: NionCore): String? {
        // 1. 尝试读取缓存
        val cached = tryReadCache(core)
        if (cached != null) {
            Log.d(TAG, "使用缓存位置: $cached")
            return cached
        }

        // 2. 检查位置权限
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "没有位置权限，无法获取位置")
            return null
        }

        // 3. 通过 GPS 获取当前位置
        val location = getCurrentLocation(context) ?: run {
            Log.w(TAG, "GPS 获取位置失败")
            // GPS 失败时，尝试读取过期缓存作为降级
            return tryReadCache(core, ignoreExpiry = true)
        }

        // 4. 缓存并返回
        val locationStr = "${location.latitude},${location.longitude}"
        tryWriteCache(core, locationStr)
        Log.d(TAG, "GPS 获取位置成功: $locationStr")
        return locationStr
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
