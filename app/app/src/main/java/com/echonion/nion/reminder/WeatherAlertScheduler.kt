package com.echonion.nion.reminder

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 天气预警调度器 —— 管理天气预警 PeriodicWorkRequest 的注册和取消。
 *
 * 使用 WorkManager 的 PeriodicWorkRequest 实现 1 小时间隔的定时检查。
 * 相比 AlarmManager，WorkManager 的优势：
 * - Doze 模式下也能执行（setAndAllowWhileIdle）
 * - 设备重启后自动恢复（无需 BootReceiver）
 * - 支持约束条件（如要求网络连接）
 *
 * 使用方式：
 * ```kotlin
 * // 启动天气预警（应用启动时调用）
 * WeatherAlertScheduler.start(context)
 *
 * // 停止天气预警
 * WeatherAlertScheduler.stop(context)
 * ```
 */
object WeatherAlertScheduler {

    private const val TAG = "WeatherAlertScheduler"

    /** WorkManager 唯一工作名称，用于标识和去重 */
    private const val WORK_NAME = "weather_alert_periodic"

    /** 检查间隔：1 小时 */
    private const val INTERVAL_HOURS = 1L

    /**
     * 启动天气预警定时检查。
     *
     * 如果已有同名的 PeriodicWorkRequest，使用 KEEP 策略（保留现有的）。
     * 这意味着重复调用 start() 是安全的。
     *
     * @param context Android 上下文
     */
    fun start(context: Context) {
        Log.d(TAG, "启动天气预警定时检查（间隔 ${INTERVAL_HOURS}h）")

        val request = PeriodicWorkRequestBuilder<WeatherAlertWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            // 设置弹性窗口：实际执行时间可能在间隔结束前后 15 分钟内
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )

        Log.d(TAG, "天气预警定时检查已注册")
    }

    /**
     * 停止天气预警定时检查。
     *
     * 取消 WorkManager 中的 PeriodicWorkRequest。
     *
     * @param context Android 上下文
     */
    fun stop(context: Context) {
        Log.d(TAG, "停止天气预警定时检查")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 重启天气预警定时检查。
     *
     * 先取消现有任务，再重新注册。
     * 用于用户更改设置后强制刷新。
     *
     * @param context Android 上下文
     */
    fun restart(context: Context) {
        Log.d(TAG, "重启天气预警定时检查")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        start(context)
    }
}
