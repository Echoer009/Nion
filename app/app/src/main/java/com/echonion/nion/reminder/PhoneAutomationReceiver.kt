package com.echonion.nion.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echonion.nion.R
import com.echonion.nion.ui.companion.tools.ScheduledPhoneTaskTool
import org.json.JSONObject

/**
 * 定时手机任务闹钟触发接收器 —— AlarmManager 到点时回调。
 *
 * 触发后的行为（通知确认模式）：
 * 1. 从 Intent 中读取 task_id
 * 2. 从 settings 读取任务详情（名称、描述）
 * 3. 显示一条高优先级通知：
 *    - 标题：「定时任务：{任务名称}」
 *    - 正文：「{任务描述}」
 *    - 按钮1「执行」→ 启动 PhoneAutomationWorker
 *    - 按钮2「跳过」→ 取消通知
 * 4. 用户点击「执行」后，Worker 接管后续逻辑（启动 PhoneAgentLoop）
 *
 * 设计理由：
 * - Phone Agent 会实际操控手机（点击、滑动、输入），不适合在后台静默执行
 * - 通过通知让用户知晓并手动确认，避免用户正在使用手机时被干扰
 * - 一次性任务触发后自动禁用，每日任务触发后由 Worker 调度明天的闹钟
 */
class PhoneAutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhoneAutomationScheduler.ACTION_PHONE_AUTOMATION) return

        val taskId = intent.getStringExtra(PhoneAutomationScheduler.EXTRA_TASK_ID) ?: return
        Log.d(TAG, "定时手机任务闹钟触发: taskId=$taskId")

        // 检查是否是「执行」按钮的 action
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_EXECUTE -> {
                // 用户点击了「执行」按钮 → 入队 Worker
                Log.d(TAG, "用户确认执行: taskId=$taskId")
                PhoneAutomationWorker.enqueue(context, taskId)
                // 取消等待执行的通知
                dismissTriggerNotification(context, taskId)
                return
            }
            ACTION_SKIP -> {
                // 用户点击了「跳过」按钮 → 取消通知，调度下次（daily 类型）
                Log.d(TAG, "用户跳过执行: taskId=$taskId")
                dismissTriggerNotification(context, taskId)
                scheduleNextIfDaily(context, taskId)
                return
            }
        }

        // 首次触发（非按钮点击）→ 显示等待确认的通知
        val app = context.applicationContext as? com.echonion.nion.NionApp
        if (app == null) {
            Log.e(TAG, "无法获取 NionApp 实例")
            return
        }

        val task = PhoneAutomationScheduler.findTaskById(app.core, taskId)
        if (task == null) {
            Log.w(TAG, "未找到定时任务: taskId=$taskId，可能已被删除")
            return
        }

        // 检查任务是否仍启用
        if (!task.optBoolean("enabled")) {
            Log.d(TAG, "任务已禁用，跳过: taskId=$taskId")
            return
        }

        val taskName = task.optString("name", "定时任务")
        val taskDesc = task.optString("task_description", "")
        val scheduleType = task.optString("schedule_type", "once")

        showTriggerNotification(context, taskId, taskName, taskDesc, scheduleType)
        Log.d(TAG, "已显示等待确认通知: taskId=$taskId, name=$taskName")
    }

    /**
     * 显示等待用户确认的触发通知。
     * 包含「执行」和「跳过」两个 Action 按钮。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param taskName 任务名称
     * @param taskDesc 任务描述
     * @param scheduleType 调度类型（once/daily）
     */
    private fun showTriggerNotification(
        context: Context,
        taskId: String,
        taskName: String,
        taskDesc: String,
        scheduleType: String,
    ) {
        val notificationId = ("phone_auto:$taskId").hashCode() and 0x7FFFFFFF

        // 点击通知主体 → 打开 app
        val contentIntent = Intent(context, com.echonion.nion.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 「执行」按钮 → 发送广播到自身，携带 action=execute
        val executeIntent = Intent(context, PhoneAutomationReceiver::class.java).apply {
            action = PhoneAutomationScheduler.ACTION_PHONE_AUTOMATION
            putExtra(PhoneAutomationScheduler.EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_ACTION, ACTION_EXECUTE)
        }
        val executePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            executeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 「跳过」按钮 → 发送广播到自身，携带 action=skip
        val skipIntent = Intent(context, PhoneAutomationReceiver::class.java).apply {
            action = PhoneAutomationScheduler.ACTION_PHONE_AUTOMATION
            putExtra(PhoneAutomationScheduler.EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_ACTION, ACTION_SKIP)
        }
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 构建通知文案
        val typeHint = if (scheduleType == "daily") "（每日任务）" else "（一次性任务）"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("定时任务：$taskName")
            .setContentText(taskDesc.ifEmpty { "点击执行或跳过$typeHint" })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("定时任务：$taskName")
                    .bigText(taskDesc.ifEmpty { "Phone Agent 将自动执行此操作$typeHint" })
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(false) // 不自动取消，等用户点击按钮
            .setContentIntent(contentPendingIntent)
            .addAction(0, "执行", executePendingIntent)
            .addAction(0, "跳过", skipPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 取消等待执行的通知。
     */
    private fun dismissTriggerNotification(context: Context, taskId: String) {
        val notificationId = ("phone_auto:$taskId").hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.cancel(notificationId)
    }

    /**
     * 如果是 daily 类型，调度明天的闹钟（即使本次被跳过）。
     * once 类型被跳过则标记为禁用。
     */
    private fun scheduleNextIfDaily(context: Context, taskId: String) {
        val app = context.applicationContext as? com.echonion.nion.NionApp ?: return
        val task = PhoneAutomationScheduler.findTaskById(app.core, taskId) ?: return
        val scheduleType = task.optString("schedule_type", "once")

        if (scheduleType == "daily") {
            val scheduleTime = task.optString("schedule_time", "08:00")
            PhoneAutomationScheduler.scheduleNextDay(context, taskId, scheduleTime)
        } else {
            // 一次性任务被跳过 → 标记禁用
            disableTask(app.core, taskId)
        }
    }

    /**
     * 禁用指定任务（更新 settings 中的 JSON 数组）。
     * 一次性任务执行完成或被跳过后调用。
     */
    private fun disableTask(core: uniffi.nion_core.NionCore, taskId: String) {
        try {
            val tasks = ScheduledPhoneTaskTool.loadTasks(core)
            for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                if (task.getString("id") == taskId) {
                    task.put("enabled", false)
                    break
                }
            }
            core.setSetting(ScheduledPhoneTaskTool.SETTING_KEY, tasks.toString())
        } catch (e: Exception) {
            Log.e(TAG, "禁用任务失败: $taskId", e)
        }
    }

    companion object {
        private const val TAG = "PhoneAutomationReceiver"

        /** 通知渠道 ID */
        const val CHANNEL_ID = "phone_automation"

        /** Intent extra：操作类型 */
        private const val EXTRA_ACTION = "action"
        /** 执行操作 */
        private const val ACTION_EXECUTE = "execute"
        /** 跳过操作 */
        private const val ACTION_SKIP = "skip"
    }
}
