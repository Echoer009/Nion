package com.echonion.nion.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 问候闹钟触发接收器 —— AlarmManager 到点时回调。
 *
 * 职责单一：将问候事件转发给 GreetingWorker（WorkManager）。
 * Receiver 的 onReceive 必须快速返回，所有业务逻辑在 Worker 中执行。
 */
class GreetingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GreetingReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GreetingScheduler.ACTION_GREETING) return

        val type = intent.getStringExtra(GreetingScheduler.EXTRA_GREETING_TYPE) ?: return

        Log.d(TAG, "问候闹钟触发: type=$type")

        // 委托给 GreetingWorker 处理
        GreetingWorker.enqueue(context, type)
    }
}
