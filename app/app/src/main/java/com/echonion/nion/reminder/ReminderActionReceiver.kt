package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echonion.nion.NionApp

/**
 * 通知栏 Action 按钮点击接收器 —— 处理用户在通知栏的直接交互。
 *
 * 三个 Action：
 * - [ACTION_START]：开始做了 —— 取消提醒循环 + 跳转专注页面
 * - [ACTION_SNOOZE]：等5分钟 —— 取消当前通知 + 5 分钟后重新提醒
 * - [ACTION_DISMISS]：今天算了 —— 取消提醒循环 + 取消通知
 *
 * 每个 action 通过 PendingIntent 触发此 Receiver，
 * Receiver 根据 action 类型执行对应的业务逻辑。
 */
class ReminderActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ReminderAction"

        /** Action：用户点击「开始做了」 */
        const val ACTION_START = "com.echonion.nion.ACTION_REMINDER_START"
        /** Action：用户点击「等5分钟」 */
        const val ACTION_SNOOZE = "com.echonion.nion.ACTION_REMINDER_SNOOZE"
        /** Action：用户点击「今天算了」 */
        const val ACTION_DISMISS = "com.echonion.nion.ACTION_REMINDER_DISMISS"

        /** Intent extra：任务 ID */
        const val EXTRA_TASK_ID = "task_id"
        /** Intent extra：任务标题（用于跳转专注页面时传递） */
        const val EXTRA_TASK_TITLE = "task_title"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val action = intent.action ?: return

        Log.d(TAG, "通知栏操作: action=$action, taskId=$taskId")

        when (action) {
            ACTION_START -> handleStart(context, taskId, intent)
            ACTION_SNOOZE -> handleSnooze(context, taskId)
            ACTION_DISMISS -> handleDismiss(context, taskId)
        }
    }

    /**
     * 处理「开始做了」—— 取消提醒循环 + 跳转专注页面。
     *
     * 1. 重置 trigger_count（终止循环）
     * 2. 取消 AlarmManager 闹钟
     * 3. 取消通知
     * 4. 打开 MainActivity 并携带专注页面参数
     */
    private fun handleStart(context: Context, taskId: String, intent: Intent) {
        // 终止提醒循环
        ReminderStore.resetTriggerCount(context, taskId)
        ReminderScheduler.cancelReminder(context, taskId)
        NotificationHelper.dismissNotification(context, taskId)

        // 获取任务标题（优先从 intent extra 读取，fallback 到 DB）
        val taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: try {
            val app = context.applicationContext as? NionApp
            app?.core?.getTask(taskId)?.name ?: ""
        } catch (_: Exception) {
            ""
        }

        // 跳转专注页面：携带 taskId + title + autoStart
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            launchIntent.putExtra("reminder_action", "start_focus")
            launchIntent.putExtra("reminder_task_id", taskId)
            launchIntent.putExtra("focus_task_title", taskTitle)
            launchIntent.putExtra("auto_start_focus", true)
            context.startActivity(launchIntent)
        }

        Log.d(TAG, "开始做了: taskId=$taskId, title=$taskTitle")
    }

    /**
     * 处理「等5分钟」—— 取消当前通知 + 调度贪睡闹钟。
     *
     * 贪睡不重置 trigger_count，循环继续递进。
     * 下一次触发时紧迫度与正常循环一致。
     */
    private fun handleSnooze(context: Context, taskId: String) {
        // 取消当前通知，但保留 trigger_count（循环继续）
        NotificationHelper.dismissNotification(context, taskId)
        // 调度贪睡闹钟（复用 LOOP_INTERVAL_MINUTES 间隔）
        ReminderScheduler.scheduleSnoozeReminder(
            context,
            taskId,
            ReminderStore.LOOP_INTERVAL_MINUTES.toInt(),
        )

        Log.d(TAG, "等5分钟: taskId=$taskId, 将在 ${ReminderStore.LOOP_INTERVAL_MINUTES} 分钟后再次提醒")
    }

    /**
     * 处理「今天算了」—— 取消提醒循环 + 取消通知。
     *
     * 彻底终止该任务的提醒循环，trigger_count 重置为 0。
     */
    private fun handleDismiss(context: Context, taskId: String) {
        // 终止提醒循环
        ReminderStore.resetTriggerCount(context, taskId)
        ReminderScheduler.cancelReminder(context, taskId)
        NotificationHelper.dismissNotification(context, taskId)

        Log.d(TAG, "今天算了: taskId=$taskId, 提醒循环已终止")
    }
}
