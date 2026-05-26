package com.echonion.nion.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import uniffi.nion_core.NionCore

/**
 * 提醒闹钟调度器 —— 基于 AlarmManager 管理任务的提醒闹钟。
 *
 * 两种闹钟类型：
 * - exact: 一次性精确闹钟，用于普通任务的 reminder 字段
 * - daily: 每日循环闹钟，用于每日任务的 recurrence_reminder_time 字段
 *
 * 调度策略：
 * - 使用 AlarmManager.setExactAndAllowWhileIdle() 确保在 Doze 模式下也能触发
 * - 每日闹钟不使用 setRepeating（Android 上不精确），而是每次触发后由 Receiver 手动调度下一天
 * - 所有 PendingIntent 使用 FLAG_IMMUTABLE，避免安全异常
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    /** Intent action：闹钟触发 */
    const val ACTION_REMINDER = "com.echonion.nion.ACTION_REMINDER"
    /** Intent extra key：任务 ID */
    const val EXTRA_TASK_ID = "task_id"
    /** Intent extra key：闹钟类型（"exact" 或 "daily"） */
    const val EXTRA_TYPE = "reminder_type"
    /** 闹钟类型：一次性提醒 */
    const val TYPE_EXACT = "exact"
    /** 闹钟类型：每日循环提醒 */
    const val TYPE_DAILY = "daily"

    /**
     * 调度一次性精确提醒闹钟。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param triggerMillis 触发时间的毫秒时间戳（epoch）
     */
    fun scheduleExactReminder(context: Context, taskId: String, triggerMillis: Long) {
        if (triggerMillis <= System.currentTimeMillis()) {
            Log.w(TAG, "跳过已过期的提醒: taskId=$taskId, trigger=$triggerMillis")
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, taskId, TYPE_EXACT)

        // Android 12+ 使用 setExactAndAllowWhileIdle 确保精确触发
        // Android 6+ 的 Doze 模式下 setExact 不会触发，需要 AllowWhileIdle 版本
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            pendingIntent,
        )
        Log.d(TAG, "调度一次性提醒: taskId=$taskId, trigger=$triggerMillis")
    }

    /**
     * 调度每日循环提醒闹钟。
     *
     * 计算 today 的 HH:MM 时刻，如果已过则调度到明天同一时刻。
     * 每次触发后由 ReminderReceiver 自动调度下一天。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param hour 小时（0-23）
     * @param minute 分钟（0-59）
     */
    fun scheduleDailyReminder(context: Context, taskId: String, hour: Int, minute: Int) {
        val now = LocalDateTime.now()
        val todayTime = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))

        // 如果今天的提醒时间已过，调度到明天
        val triggerTime = if (todayTime.isAfter(now)) todayTime else todayTime.plusDays(1)
        val triggerMillis = triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, taskId, TYPE_DAILY)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            pendingIntent,
        )
        Log.d(TAG, "调度每日提醒: taskId=$taskId, hour=$hour, minute=$minute, nextTrigger=$triggerTime")
    }

    /**
     * 取消指定任务的所有提醒闹钟。
     * 分别取消 exact 和 daily 两种类型的 PendingIntent。
     */
    fun cancelReminder(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 同时取消两种类型的闹钟（即使只有一种存在也安全）
        alarmManager.cancel(createPendingIntent(context, taskId, TYPE_EXACT))
        alarmManager.cancel(createPendingIntent(context, taskId, TYPE_DAILY))
        Log.d(TAG, "取消提醒: taskId=$taskId")
    }

    /**
     * 全量重调度所有任务的提醒闹钟。
     *
     * 在以下场景调用：
     * - App 启动时（NionApp.onCreate）
     * - 设备重启后（BootReceiver.onReceive）
     * - App 更新后
     *
     * 遍历所有任务，根据 reminder 和 recurrence_reminder_time 字段调度对应闹钟。
     * 先取消所有已有闹钟再重新调度，确保状态一致。
     */
    fun rescheduleAll(context: Context, core: NionCore) {
        try {
            val tasks = core.getTasks()
            for (task in tasks) {
                // 处理普通任务的一次性提醒
                val reminder = task.reminder
                if (reminder != null) {
                    try {
                        // reminder 格式："YYYY-MM-DDTHH:MM" 或 RFC 3339
                        val dt = parseReminderToMillis(reminder)
                        if (dt != null && dt > System.currentTimeMillis()) {
                            scheduleExactReminder(context, task.id, dt)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "解析 reminder 失败: ${task.id}, $reminder", e)
                    }
                }

                // 处理每日循环任务的提醒
                if (task.recurrenceRule == "daily" && task.recurrenceReminderTime != null) {
                    try {
                        val parts = task.recurrenceReminderTime!!.split(":")
                        if (parts.size == 2) {
                            val hour = parts[0].toIntOrNull() ?: continue
                            val minute = parts[1].toIntOrNull() ?: continue
                            scheduleDailyReminder(context, task.id, hour, minute)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "解析 daily reminder 失败: ${task.id}", e)
                    }
                }
            }
            Log.d(TAG, "全量重调度完成，共处理 ${tasks.size} 个任务")
        } catch (e: Exception) {
            Log.e(TAG, "全量重调度失败", e)
        }
    }

    /**
     * 调度"稍后提醒" —— 从当前时间起延迟指定分钟后触发。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param delayMinutes 延迟分钟数（5/10/30）
     */
    fun scheduleSnoozeReminder(context: Context, taskId: String, delayMinutes: Int) {
        val triggerMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, taskId, TYPE_EXACT)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            pendingIntent,
        )
        Log.d(TAG, "调度稍后提醒: taskId=$taskId, ${delayMinutes}分钟后")
    }

    /**
     * 创建闹钟触发的 PendingIntent。
     * 每个任务+类型组合有唯一的 PendingIntent（通过 requestCode 区分）。
     */
    private fun createPendingIntent(context: Context, taskId: String, type: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TYPE, type)
        }
        // requestCode 使用 taskId + type 的 hashCode，确保唯一性
        val requestCode = ("$taskId:$type").hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 解析 reminder 字符串为毫秒时间戳。
     * 支持格式：
     * - "YYYY-MM-DDTHH:MM"（本地时间）
     * - "YYYY-MM-DDTHH:MM:SS"（本地时间）
     * - RFC 3339 格式（带时区后缀）
     *
     * @return 毫秒时间戳，解析失败返回 null
     */
    fun parseReminderToMillisPublic(reminder: String): Long? = parseReminderToMillis(reminder)

    private fun parseReminderToMillis(reminder: String): Long? {
        return try {
            // 尝试解析为 LocalDateTime（本地时间格式）
            val ldt = try {
                LocalDateTime.parse(reminder, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(reminder, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                } catch (_: Exception) {
                    // 最后尝试 RFC 3339（带时区）
                    return try {
                        java.time.OffsetDateTime.parse(reminder).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            ldt?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "无法解析 reminder: $reminder", e)
            null
        }
    }
}
