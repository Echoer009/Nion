package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echonion.nion.NionApp

/**
 * 开机重调度接收器 —— 监听 BOOT_COMPLETED 广播，重新注册所有提醒闹钟。
 *
 * Android 的 AlarmManager 闹钟在设备重启后会被清空，
 * 必须在开机后重新调度所有任务的提醒闹钟。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "收到开机广播，开始重调度提醒闹钟")

        val app = context.applicationContext as? NionApp ?: run {
            Log.w(TAG, "无法获取 NionApp 实例，跳过重调度")
            return
        }

        try {
            ReminderScheduler.rescheduleAll(context, app.core)
            Log.d(TAG, "开机重调度完成")
        } catch (e: Exception) {
            Log.e(TAG, "开机重调度失败", e)
        }
    }
}
