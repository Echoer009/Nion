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
 */
object NotificationHelper {

    /** 通知渠道 ID，所有任务提醒共用此渠道 */
    private const val CHANNEL_ID = "task_reminders"
    /** 渠道名称，显示在系统设置中 */
    private const val CHANNEL_NAME = "任务提醒"
    /** 渠道描述 */
    private const val CHANNEL_DESC = "任务到点提醒通知"

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
                // 高优先级渠道默认会发声、振动、显示横幅
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 显示任务提醒通知。
     *
     * @param context 上下文
     * @param taskId 任务 ID，同时用作通知 ID（通过 hashCode）
     * @param title 任务标题，显示在通知内容中
     */
    fun showReminderNotification(context: Context, taskId: String, title: String) {
        val notificationId = taskId.hashCode() and 0x7FFFFFFF

        // 点击通知时打开 MainActivity，携带 taskId 以便导航到对应任务
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("reminder_task_id", taskId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("任务提醒")
            .setContentText(title)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("「$title」的提醒时间到了")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
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
