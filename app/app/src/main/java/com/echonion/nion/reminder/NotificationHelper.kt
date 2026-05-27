package com.echonion.nion.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.echonion.nion.MainActivity
import com.echonion.nion.R

/**
 * 通知工具类 —— 管理任务提醒的通知渠道和通知发送。
 *
 * 所有提醒通知使用同一个渠道 "task_reminders"，IMPORTANCE_HIGH 确保能发出声音和弹出横幅。
 * 每个任务使用 notificationId = taskId.hashCode()，保证同一任务只会有一个通知。
 *
 * 通知包含：
 * - 正文：Nion 的个性化提醒文案（LLM 生成或模板）
 * - 3 个 Action 按钮（根据紧迫度变化）：开始做了 / 等5分钟 / 今天算了
 * - 点击通知主体 → 打开 app 并展开伙伴面板
 */
object NotificationHelper {

    /** 通知渠道 ID，所有任务提醒共用此渠道 */
    private const val CHANNEL_ID = "task_reminders"
    /** 渠道名称，显示在系统设置中 */
    private const val CHANNEL_NAME = "任务提醒"
    /** 渠道描述 */
    private const val CHANNEL_DESC = "Nion 伙伴的个性化任务提醒"

    /** 天气预警通知渠道 ID */
    private const val WEATHER_CHANNEL_ID = "weather_alerts"
    /** 天气预警渠道名称 */
    private const val WEATHER_CHANNEL_NAME = "天气预警"
    /** 天气预警渠道描述 */
    private const val WEATHER_CHANNEL_DESC = "Nion 的天气变化提醒"

    /**
     * 创建通知渠道。
     * 必须在 Application.onCreate() 中调用，且只在 Android 8.0+ 需要创建。
     * 重复调用是安全的（系统会忽略已存在的渠道）。
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            // 天气预警渠道（默认优先级，不紧急）
            val weatherChannel = NotificationChannel(
                WEATHER_CHANNEL_ID,
                WEATHER_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = WEATHER_CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(weatherChannel)
        }
    }

    /**
     * 显示任务提醒通知（带 Action 按钮）。
     *
     * 根据 triggerCount 调整按钮文案，实现渐进式递进效果：
     * - triggerCount 1-2：开始做了 / 等5分钟 / 今天算了
     * - triggerCount 3：马上开始 / 最后5分钟 / 今天算了
     * - triggerCount 4：现在开始 / 真的不做了（无第三个按钮）
     * - triggerCount 5：知道了（仅一个按钮）
     *
     * @param context 上下文
     * @param taskId 任务 ID，同时用作通知 ID（通过 hashCode）
     * @param taskTitle 任务标题，用于 Action 按钮 Intent 传递
     * @param message Nion 的提醒文案（LLM 生成或模板）
     * @param triggerCount 当前触发次数（1-5），决定按钮文案
     */
    fun showReminderNotification(
        context: Context,
        taskId: String,
        taskTitle: String,
        message: String,
        triggerCount: Int,
    ) {
        val notificationId = taskId.hashCode() and 0x7FFFFFFF
        val labels = ReminderMessageGenerator.getActionLabels(triggerCount)

        // 点击通知主体 → 打开 app 并展开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("reminder_task_id", taskId)
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 收拢状态：标题用任务名，正文极短，确保按钮在收拢通知栏中直接可见。
        // 展开后：显示 Nion 的完整提醒文案（BigTextStyle.bigText）。
        val collapsedText = if (triggerCount == 1) "该做啦～" else "第${triggerCount}次提醒"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(taskTitle)
            .setContentText(collapsedText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(taskTitle)
                    .bigText(message)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)

        // 按钮1：「开始做了」（或「马上开始」「现在开始」）
        if (labels.first.isNotEmpty()) {
            val startIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_START
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
                putExtra(ReminderActionReceiver.EXTRA_TASK_TITLE, taskTitle)
            }
            val startPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 1,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, labels.first, startPendingIntent)
        }

        // 按钮2：「等5分钟」（或「最后5分钟」「真的不做了」）
        if (labels.second.isNotEmpty()) {
            // triggerCount >= 4 时，第二个按钮变为「真的不做了」(DISMISS)
            val snoozeAction = if (triggerCount >= 4) {
                ReminderActionReceiver.ACTION_DISMISS
            } else {
                ReminderActionReceiver.ACTION_SNOOZE
            }
            val snoozeIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                action = snoozeAction
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 2,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, labels.second, snoozePendingIntent)
        }

        // 按钮3：「今天算了」（仅 triggerCount < 4 时显示）
        if (labels.third.isNotEmpty()) {
            val dismissIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DISMISS
                putExtra(ReminderActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                notificationId + 3,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, labels.third, dismissPendingIntent)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, builder.build())
    }

    /**
     * 显示简易通知（无 Action 按钮，用于最终告别等场景）。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param message 通知文案
     */
    fun showSimpleNotification(context: Context, taskId: String, message: String) {
        val notificationId = taskId.hashCode() and 0x7FFFFFFF

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nion")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 显示问候通知（无 Action 按钮，点击打开伙伴面板）。
     *
     * 用于早安/午间/晚间问候场景，通知优先级为 DEFAULT（不紧急）。
     *
     * @param context 上下文
     * @param type 问候类型（"morning"/"noon"/"evening"），用于生成通知 ID 和标题
     * @param message 问候文案（LLM 生成或模板）
     */
    fun showGreetingNotification(context: Context, type: String, message: String) {
        val notificationId = ("greeting_$type").hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app 并展开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 根据问候类型设置标题
        val title = when (type) {
            "morning" -> "早安问候"
            "noon" -> "午间检查"
            "evening" -> "晚间总结"
            else -> "Nion"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 显示批量提醒通知（无 Action 按钮，点击打开伙伴面板）。
     *
     * 用于密集时段汇总提醒，通知优先级为 DEFAULT。
     *
     * @param context 上下文
     * @param message 汇总文案（LLM 生成或模板）
     */
    fun showBatchNotification(context: Context, message: String) {
        val notificationId = "batch_reminder".hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nion")
            .setContentText("任务密集时段提醒")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 显示天气预警通知（无 Action 按钮，点击打开伙伴面板）。
     *
     * 用于天气变化预警场景（降雨、降温、大风等），通知优先级根据严重程度调整。
     *
     * @param context 上下文
     * @param message 预警文案（LLM 生成或模板）
     * @param severity 严重程度：info/warning/urgent
     */
    fun showWeatherAlertNotification(context: Context, message: String, severity: String) {
        val notificationId = "weather_alert".hashCode() and 0x7FFFFFFF

        // 点击通知 → 打开 app 并展开伙伴面板
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_companion", true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (severity) {
            "urgent" -> "天气紧急提醒"
            "warning" -> "天气提醒"
            else -> "天气提示"
        }

        val priority = when (severity) {
            "urgent" -> NotificationCompat.PRIORITY_HIGH
            "warning" -> NotificationCompat.PRIORITY_DEFAULT
            else -> NotificationCompat.PRIORITY_LOW
        }

        val notification = NotificationCompat.Builder(context, WEATHER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    /**
     * 取消指定任务的通知。
     * 当用户在应用内处理了提醒后调用，避免通知栏还残留。
     */
    fun dismissNotification(context: Context, taskId: String) {
        val notificationId = taskId.hashCode() and 0x7FFFFFFF
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId)
    }
}
