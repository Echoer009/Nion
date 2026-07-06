package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.echonion.nion.NionApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 通知栏 Action 按钮点击接收器 —— 处理用户在通知栏的直接交互。
 *
 * 四个 Action：
 * - [ACTION_START]：开始做了 —— 取消提醒循环 + 跳转专注页面
 * - [ACTION_COMPLETE]：知道了 —— 标记任务完成 + 终止提醒循环 + 取消通知
 * - [ACTION_SNOOZE]：等5分钟 —— 取消当前通知 + 5 分钟后重新提醒
 * - [ACTION_DISMISS]：今天算了 —— 取消提醒循环 + 取消通知
 *
 * 每个 action 通过 PendingIntent 触发此 Receiver，
 * Receiver 根据 action 类型执行对应的业务逻辑。
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val action = intent.action ?: return

        Log.d(TAG, "通知栏操作: action=$action, taskId=$taskId")

        when (action) {
            ACTION_START -> handleStart(context, taskId, intent)
            ACTION_COMPLETE -> handleComplete(context, taskId)
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

    /**
     * 处理「知道了」—— 标记任务完成 + 终止提醒循环 + 取消通知。
     *
     * 完成操作区分每日/普通任务，由 ReminderCompleteHelper 统一处理：
     * - 每日任务：completeDailyTaskInstance + 为自动创建的明天实例注册闹钟
     * - 普通任务：updateTask 将 status 改为 "done"
     *
     * 使用 goAsync() 让 BroadcastReceiver 在异步完成操作结束前保持存活
     * （onReceive 返回后系统可能立即销毁 Receiver，普通 launch 会被中断）。
     */
    private fun handleComplete(context: Context, taskId: String) {
        val app = context.applicationContext as? NionApp
        // goAsync() 保持 Receiver 存活，直到异步任务完成调用 finish()
        val pendingResult = goAsync()
        // 终止提醒循环（同步立即执行，不依赖协程）
        ReminderStore.resetTriggerCount(context, taskId)
        ReminderScheduler.cancelReminder(context, taskId)
        NotificationHelper.dismissNotification(context, taskId)

        if (app != null) {
            // 标记任务完成：在协程中等待 IO 完成，finally 中释放 Receiver
            MainScope().launch {
                try {
                    ReminderCompleteHelper.completeTaskFromReminderSync(app, taskId)
                } catch (e: Exception) {
                    Log.w(TAG, "知道了完成任务失败: taskId=$taskId", e)
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            // 拿不到 NionApp 时直接释放，避免 Receiver 泄漏
            pendingResult.finish()
        }

        Log.d(TAG, "知道了(完成任务): taskId=$taskId")
    }

    companion object {
        private const val TAG = "ReminderAction"

        /** Action：用户点击「开始做了」 */
        const val ACTION_START = "com.echonion.nion.ACTION_REMINDER_START"
        /** Action：用户点击「知道了」（标记任务完成） */
        const val ACTION_COMPLETE = "com.echonion.nion.ACTION_REMINDER_COMPLETE"
        /** Action：用户点击「等5分钟」 */
        const val ACTION_SNOOZE = "com.echonion.nion.ACTION_REMINDER_SNOOZE"
        /** Action：用户点击「今天算了」 */
        const val ACTION_DISMISS = "com.echonion.nion.ACTION_REMINDER_DISMISS"

        /** Intent extra：任务 ID */
        const val EXTRA_TASK_ID = "task_id"
        /** Intent extra：任务标题（用于跳转专注页面时传递） */
        const val EXTRA_TASK_TITLE = "task_title"
    }
}
