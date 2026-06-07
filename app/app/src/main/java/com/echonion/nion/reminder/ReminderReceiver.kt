package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 提醒闹钟触发接收器 —— AlarmManager 到点时回调此 Receiver。
 *
 * 职责：
 * 1. 将闹钟事件转发给 ReminderWorker（WorkManager），由 Worker 执行核心逻辑
 * 2. Worker 负责：读取任务 → 递增 trigger_count → 生成文案 → 发通知 → 调度循环
 *
 * 注意：Receiver 的 onReceive 必须快速返回，所以只做 WorkManager 入队操作。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 安全校验：只处理我们自己的 action
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return

        val taskId = intent.getStringExtra(ReminderScheduler.EXTRA_TASK_ID) ?: return
        val type = intent.getStringExtra(ReminderScheduler.EXTRA_TYPE) ?: return

        Log.d(TAG, "闹钟触发: taskId=$taskId, type=$type")

        // 将处理逻辑委托给 ReminderWorker
        ReminderWorker.enqueue(context, taskId, type)
    }

    companion object {
        private const val TAG = "ReminderReceiver"
    }
}

/**
 * 提醒事件数据类 —— 从 ReminderWorker 发送到 UI 层的事件。
 *
 * @property taskId 触发提醒的任务 ID
 * @property taskTitle 任务标题，用于弹窗显示
 * @property type 闹钟类型："exact" 或 "daily"
 * @property message Nion 的个性化提醒文案
 * @property triggerCount 当前触发次数（1-5），用于 UI 展示紧迫度
 * @property priority 任务优先级："low" / "medium" / "high"
 */
data class ReminderEvent(
    val taskId: String,
    val taskTitle: String,
    val type: String,
    val message: String = "",
    val triggerCount: Int = 1,
    val priority: String = "medium",
)
