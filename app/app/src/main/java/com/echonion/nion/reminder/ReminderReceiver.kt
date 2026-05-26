package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echonion.nion.NionApp

/**
 * 提醒闹钟触发接收器 —— AlarmManager 到点时回调此 Receiver。
 *
 * 职责：
 * 1. 发送系统通知（无论 App 是否在前台）
 * 2. 如果 App 进程存活，通过 NionApp.reminderEvents 发事件到 UI 层
 * 3. 如果是每日任务（type=daily），自动调度明天的闹钟
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 安全校验：只处理我们自己的 action
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return

        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: return
        val type = intent.getStringExtra(ReminderScheduler.EXTRA_TYPE) ?: return

        Log.d(TAG, "闹钟触发: taskId=$taskId, type=$type")

        // 从数据库获取任务标题（闹钟触发时 App 可能在后台，需要读 DB）
        val app = context.applicationContext as? NionApp
        val taskTitle = try {
            app?.core?.getTask(taskId)?.title ?: "任务提醒"
        } catch (e: Exception) {
            Log.w(TAG, "获取任务标题失败: $taskId", e)
            "任务提醒"
        }

        // 1. 发送系统通知（后台时用户看到的唯一入口）
        NotificationHelper.showReminderNotification(context, taskId, taskTitle)

        // 2. 如果 App 进程存活，发事件到 UI 层（前台弹窗）
        if (app != null) {
            try {
                app.postReminderEvent(ReminderEvent(taskId, taskTitle, type))
                Log.d(TAG, "已发送提醒事件到 UI: $taskTitle")
            } catch (e: Exception) {
                Log.w(TAG, "发送提醒事件失败", e)
            }
        }

        // 3. 每日任务：自动调度明天的闹钟
        if (type == ReminderScheduler.TYPE_DAILY) {
            scheduleNextDaily(context, taskId)
        }
    }

    /**
     * 调度每日任务的明天闹钟。
     * 从数据库读取 recurrence_reminder_time，解析 HH:MM 后调度明天同一时刻。
     */
    private fun scheduleNextDaily(context: Context, taskId: String) {
        val app = context.applicationContext as? NionApp ?: return
        try {
            val task = app.core.getTask(taskId)
            val time = task.recurrenceReminderTime ?: return
            val parts = time.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull() ?: return
                val minute = parts[1].toIntOrNull() ?: return
                // 先取消当前闹钟，再调度明天
                ReminderScheduler.cancelReminder(context, taskId)
                ReminderScheduler.scheduleDailyReminder(context, taskId, hour, minute)
                Log.d(TAG, "已调度明天每日提醒: taskId=$taskId, $hour:$minute")
            }
        } catch (e: Exception) {
            Log.w(TAG, "调度明天每日提醒失败: $taskId", e)
        }
    }
}

/**
 * 提醒事件数据类 —— 从 ReminderReceiver 发送到 UI 层的事件。
 *
 * @property taskId 触发提醒的任务 ID
 * @property taskTitle 任务标题，用于弹窗显示
 * @property type 闹钟类型："exact" 或 "daily"
 */
data class ReminderEvent(
    val taskId: String,
    val taskTitle: String,
    val type: String,
)
