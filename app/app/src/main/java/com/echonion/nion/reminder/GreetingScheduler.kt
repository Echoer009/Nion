package com.echonion.nion.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import uniffi.nion_core.NionCore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 情景问候调度器 —— 根据用户设置调度早安/午间/晚间问候闹钟。
 *
 * 三种问候类型：
 * - **早安问候**：汇总今日待办，给出建议（默认 8:00）
 * - **午间检查**：检查上午完成情况（默认 12:00）
 * - **晚间总结**：总结今日成就（默认 21:00）
 *
 * 每种问候独立调度，可以分别开关。
 * 闹钟触发后由 [GreetingReceiver] 接收并启动 [GreetingWorker]。
 */
object GreetingScheduler {

    private const val TAG = "GreetingScheduler"

    /** Intent action：问候闹钟触发 */
    const val ACTION_GREETING = "com.echonion.nion.ACTION_GREETING"
    /** Intent extra：问候类型（"morning" / "noon" / "evening"） */
    const val EXTRA_GREETING_TYPE = "greeting_type"

    /** 问候类型常量 */
    const val TYPE_MORNING = "morning"
    const val TYPE_NOON = "noon"
    const val TYPE_EVENING = "evening"

    /**
     * 全量重调度所有问候闹钟。
     * 在 app 启动、设备重启、设置变更时调用。
     *
     * 读取 settings 表中的问候配置，调度已启用的问候类型。
     *
     * @param context 上下文
     * @param core NionCore 实例
     */
    fun rescheduleAll(context: Context, core: NionCore) {
        // 先取消所有已有问候闹钟
        cancelAll(context)

        // 调度早安问候（独立开关）
        val morningEnabled = core.getSetting("greeting_morning_enabled")
        if (morningEnabled != "false") {
            val morningTime = core.getSetting("greeting_morning_time") ?: "08:00"
            scheduleGreeting(context, TYPE_MORNING, morningTime)
        }

        // 调度午间检查（独立开关 + 可自定义时间）
        val noonEnabled = core.getSetting("greeting_noon_enabled")
        if (noonEnabled != "false") {
            val noonTime = core.getSetting("greeting_noon_time") ?: "12:00"
            scheduleGreeting(context, TYPE_NOON, noonTime)
        }

        // 调度晚间总结（独立开关 + 可自定义时间）
        val eveningEnabled = core.getSetting("greeting_evening_enabled")
        if (eveningEnabled != "false") {
            val eveningTime = core.getSetting("greeting_evening_time") ?: "21:00"
            scheduleGreeting(context, TYPE_EVENING, eveningTime)
        }

        Log.d(TAG, "问候闹钟重调度完成")
    }

    /**
     * 调度单个问候闹钟。
     * 计算今天或明天的触发时间，使用 AlarmManager 精确调度。
     *
     * @param context 上下文
     * @param type 问候类型（"morning" / "noon" / "evening"）
     * @param timeStr 时间字符串，格式 "HH:MM"
     */
    private fun scheduleGreeting(context: Context, type: String, timeStr: String) {
        val parts = timeStr.split(":")
        if (parts.size != 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        val now = LocalDateTime.now()
        val todayTime = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        // 如果今天的时间已过，调度到明天
        val triggerTime = if (todayTime.isAfter(now)) todayTime else todayTime.plusDays(1)
        val triggerMillis = triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, type)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            pendingIntent,
        )
        Log.d(TAG, "调度问候: type=$type, time=$timeStr, nextTrigger=$triggerTime")
    }

    /**
     * 调度明天的问候闹钟。
     * 由 GreetingWorker 在问候发送完成后调用，确保每天持续触发。
     *
     * @param context 上下文
     * @param type 问候类型
     * @param timeStr 时间字符串 "HH:MM"
     */
    fun scheduleNextDay(context: Context, type: String, timeStr: String) {
        val parts = timeStr.split(":")
        if (parts.size != 2) return
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        val tomorrow = LocalDate.now().plusDays(1)
        val triggerTime = LocalDateTime.of(tomorrow, LocalTime.of(hour, minute))
        val triggerMillis = triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, type)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            pendingIntent,
        )
        Log.d(TAG, "调度明天问候: type=$type, trigger=$triggerTime")
    }

    /**
     * 取消所有问候闹钟。
     */
    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(context, TYPE_MORNING))
        alarmManager.cancel(createPendingIntent(context, TYPE_NOON))
        alarmManager.cancel(createPendingIntent(context, TYPE_EVENING))
    }

    /**
     * 创建问候闹钟的 PendingIntent。
     * 每种问候类型有独立的 requestCode，确保不会互相覆盖。
     */
    private fun createPendingIntent(context: Context, type: String): PendingIntent {
        val intent = Intent(context, GreetingReceiver::class.java).apply {
            action = ACTION_GREETING
            putExtra(EXTRA_GREETING_TYPE, type)
        }
        val requestCode = ("greeting_$type").hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
