package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echonion.nion.NionApp

/**
 * 开机重调度接收器 —— 监听 BOOT_COMPLETED 广播，重新注册所有闹钟。
 *
 * Android 的 AlarmManager 闹钟在设备重启后会被清空，
 * 必须在开机后重新调度：
 * 1. 所有任务的提醒闹钟（ReminderScheduler.rescheduleAll）
 * 2. 情景问候闹钟（GreetingScheduler.rescheduleAll）
 * 3. 批量提醒闹钟（BatchReminderWorker.scheduleBatchReminders）
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "收到开机广播，开始重调度所有闹钟")

        val app = context.applicationContext as? NionApp ?: run {
            Log.w(TAG, "无法获取 NionApp 实例，跳过重调度")
            return
        }

        try {
            // 重调度任务提醒闹钟
            ReminderScheduler.rescheduleAll(context, app.core)
            Log.d(TAG, "任务提醒重调度完成")
        } catch (e: Exception) {
            Log.e(TAG, "任务提醒重调度失败", e)
        }

        try {
            // 重调度情景问候闹钟
            GreetingScheduler.rescheduleAll(context, app.core)
            Log.d(TAG, "问候闹钟重调度完成")
        } catch (e: Exception) {
            Log.e(TAG, "问候闹钟重调度失败", e)
        }

        try {
            // 扫描并调度批量提醒
            BatchReminderWorker.scheduleBatchReminders(context, app.core)
            Log.d(TAG, "批量提醒重调度完成")
        } catch (e: Exception) {
            Log.e(TAG, "批量提醒重调度失败", e)
        }

        try {
            // 恢复天气预警定时检查
            val weatherEnabled = app.core.getSetting("weather_alert_enabled")
            if (weatherEnabled != "false") {
                WeatherAlertScheduler.start(context)
                Log.d(TAG, "天气预警调度恢复完成")
            }
        } catch (e: Exception) {
            Log.e(TAG, "天气预警调度恢复失败", e)
        }
    }
}
