package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray

/**
 * 批量提醒触发接收器 —— AlarmManager 到点时唤醒并启动 BatchReminderWorker。
 *
 * 由于 BatchReminderWorker 的 InputData 需要携带任务 ID 列表和时段信息，
 * 而这些数据通过 Intent extras 传递，所以需要一个中间 Receiver 来接收并转发给 Worker。
 */
class BatchReminderTriggerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatchTriggerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.echonion.nion.ACTION_BATCH_REMINDER") return

        val taskIdsJson = intent.getStringExtra(BatchReminderWorker.KEY_TASK_IDS) ?: return
        val periodStart = intent.getStringExtra(BatchReminderWorker.KEY_PERIOD_START) ?: return
        val periodEnd = intent.getStringExtra(BatchReminderWorker.KEY_PERIOD_END) ?: return

        // 解析任务 ID 列表
        val taskIdsArr = JSONArray(taskIdsJson)
        val taskIds = (0 until taskIdsArr.length()).map { taskIdsArr.getString(it) }

        Log.d(TAG, "批量提醒闹钟触发: ${taskIds.size} 个任务, 时段 $periodStart-$periodEnd")

        // 入队 Worker
        BatchReminderWorker.enqueue(context, taskIds, periodStart, periodEnd)
    }
}
