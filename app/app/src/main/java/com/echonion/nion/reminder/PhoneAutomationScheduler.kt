package com.echonion.nion.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echonion.nion.ui.companion.tools.ScheduledPhoneTaskTool
import org.json.JSONObject
import uniffi.nion_core.NionCore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定时手机任务调度器 —— 基于 AlarmManager 管理定时 Phone Agent 自动化任务的闹钟。
 *
 * 职责：
 * - 读取 settings 表中的定时任务列表（JSON 数组）
 * - 为每个 enabled=true 的任务计算下次触发时间并注册精确闹钟
 * - 支持两种调度类型：once（一次性）和 daily（每日循环）
 * - 取消指定或全部任务的闹钟
 * - 每次触发后由 Worker 负责调度下一次（daily 类型）
 *
 * 闹钟策略（对齐 GreetingScheduler 模式）：
 * - 使用 AlarmManager.setExactAndAllowWhileIdle() 确保精确触发
 * - daily 类型不使用 setRepeating（Android 上不精确），每次触发后由 Worker 调度下次
 * - 所有 PendingIntent 使用 FLAG_IMMUTABLE
 *
 * 与 GreetingScheduler 的区别：
 * - 每个定时任务有独立的 ID，闹钟的 requestCode 基于 ID 生成
 * - 任务列表是动态的（用户可随时增删改），需全量重调度
 */
object PhoneAutomationScheduler {

    private const val TAG = "PhoneAutomationScheduler"

    /** Intent action：定时手机任务闹钟触发 */
    const val ACTION_PHONE_AUTOMATION = "com.echonion.nion.ACTION_PHONE_AUTOMATION"
    /** Intent extra key：定时任务 ID */
    const val EXTRA_TASK_ID = "automation_task_id"

    /**
     * 全量重调度所有定时手机任务的闹钟。
     *
     * 调用场景：
     * - 创建/更新/删除定时任务后
     * - App 启动时（NionApp.onCreate）
     * - 设备重启后（BootReceiver.onReceive）
     *
     * 策略：先取消所有已有的任务闹钟，然后重新读取任务列表并逐个调度。
     */
    fun rescheduleAll(context: Context, core: NionCore) {
        try {
            // 先取消所有已有闹钟
            cancelAll(context, core)

            val tasks = ScheduledPhoneTaskTool.loadTasks(core)
            var scheduledCount = 0

            for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                // 跳过禁用的任务
                if (!task.optBoolean("enabled")) continue

                val id = task.getString("id")
                val scheduleType = task.getString("schedule_type")
                val scheduleTime = task.getString("schedule_time")

                if (scheduleType == "once") {
                    // 一次性任务：需要检查日期是否已过
                    val scheduleDate = task.optString("schedule_date", null)
                    if (scheduleDate == null) continue

                    val triggerMillis = calculateOnceTriggerMillis(scheduleDate, scheduleTime)
                    if (triggerMillis != null && triggerMillis > System.currentTimeMillis()) {
                        scheduleAlarm(context, id, triggerMillis)
                        scheduledCount++
                        Log.d(TAG, "调度一次性任务: id=$id, time=$scheduleDate $scheduleTime")
                    } else {
                        Log.d(TAG, "跳过已过期的一次性任务: id=$id, time=$scheduleDate $scheduleTime")
                    }
                } else if (scheduleType == "daily") {
                    // 每日任务：计算今天或明天的触发时间
                    val triggerMillis = calculateDailyTriggerMillis(scheduleTime)
                    scheduleAlarm(context, id, triggerMillis)
                    scheduledCount++
                    Log.d(TAG, "调度每日任务: id=$id, time=$scheduleTime")
                }
            }

            Log.d(TAG, "全量重调度完成，共调度 $scheduledCount 个任务")
        } catch (e: Exception) {
            Log.e(TAG, "全量重调度失败", e)
        }
    }

    /**
     * 调度明天的定时任务闹钟。
     * 由 PhoneAutomationWorker 在 daily 任务执行完成后调用，确保每天持续触发。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param scheduleTime 时间字符串 "HH:MM"
     */
    fun scheduleNextDay(context: Context, taskId: String, scheduleTime: String) {
        val triggerMillis = calculateDailyNextDayMillis(scheduleTime)
        scheduleAlarm(context, taskId, triggerMillis)
        Log.d(TAG, "调度明天定时任务: id=$taskId, time=$scheduleTime")
    }

    /**
     * 取消指定任务的闹钟。
     */
    fun cancelTask(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(context, taskId))
        Log.d(TAG, "取消任务闹钟: id=$taskId")
    }

    /**
     * 取消所有定时任务的闹钟。
     * 读取任务列表，逐个取消每个任务的 PendingIntent。
     */
    fun cancelAll(context: Context, core: NionCore) {
        val tasks = ScheduledPhoneTaskTool.loadTasks(core)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            val id = task.getString("id")
            alarmManager.cancel(createPendingIntent(context, id))
        }
        Log.d(TAG, "已取消所有定时任务闹钟，共 ${tasks.length()} 个")
    }

    /**
     * 注册精确闹钟。
     * 使用 setExactAndAllowWhileIdle 确保 Doze 模式下也能触发。
     */
    private fun scheduleAlarm(context: Context, taskId: String, triggerMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, taskId)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            pendingIntent,
        )
    }

    /**
     * 计算一次性任务的触发时间戳。
     * 将日期和时间组合为 LocalDateTime，转为 epoch 毫秒。
     *
     * @param scheduleDate 日期字符串 "YYYY-MM-DD"
     * @param scheduleTime 时间字符串 "HH:MM"
     * @return epoch 毫秒，解析失败返回 null
     */
    private fun calculateOnceTriggerMillis(scheduleDate: String, scheduleTime: String): Long? {
        return try {
            val date = LocalDate.parse(scheduleDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val timeParts = scheduleTime.split(":")
            val time = LocalTime.of(timeParts[0].toInt(), timeParts[1].toInt())
            LocalDateTime.of(date, time)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "解析一次性任务时间失败: $scheduleDate $scheduleTime", e)
            null
        }
    }

    /**
     * 计算每日任务的下次触发时间戳。
     * 如果今天的触发时间已过，则调度到明天同一时刻。
     *
     * @param scheduleTime 时间字符串 "HH:MM"
     * @return epoch 毫秒
     */
    private fun calculateDailyTriggerMillis(scheduleTime: String): Long {
        val timeParts = scheduleTime.split(":")
        val hour = timeParts[0].toInt()
        val minute = timeParts[1].toInt()

        val now = LocalDateTime.now()
        val todayTime = LocalDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute))
        // 如果今天的时间已过，调度到明天
        val triggerTime = if (todayTime.isAfter(now)) todayTime else todayTime.plusDays(1)
        return triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 计算每日任务明天的触发时间戳。
     * 由 Worker 在当天任务执行完后调用。
     *
     * @param scheduleTime 时间字符串 "HH:MM"
     * @return epoch 毫秒
     */
    private fun calculateDailyNextDayMillis(scheduleTime: String): Long {
        val timeParts = scheduleTime.split(":")
        val hour = timeParts[0].toInt()
        val minute = timeParts[1].toInt()

        val tomorrow = LocalDate.now().plusDays(1)
        val triggerTime = LocalDateTime.of(tomorrow, LocalTime.of(hour, minute))
        return triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 创建定时任务闹钟的 PendingIntent。
     * 每个任务有独立的 requestCode（基于 ID 的 hashCode），确保不会互相覆盖。
     */
    private fun createPendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, PhoneAutomationReceiver::class.java).apply {
            action = ACTION_PHONE_AUTOMATION
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = ("phone_automation:$taskId").hashCode() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 从任务列表中按 ID 查找任务对象。
     * 供 Receiver 和 Worker 使用，避免重复读取逻辑。
     *
     * @param core NionCore 实例
     * @param taskId 任务 ID
     * @return 任务 JSONObject，未找到返回 null
     */
    fun findTaskById(core: NionCore, taskId: String): JSONObject? {
        val tasks = ScheduledPhoneTaskTool.loadTasks(core)
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            if (task.getString("id") == taskId) return task
        }
        return null
    }
}
