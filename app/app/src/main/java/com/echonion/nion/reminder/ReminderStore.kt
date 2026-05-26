package com.echonion.nion.reminder

import android.content.Context
import android.content.SharedPreferences

/**
 * 提醒状态持久化存储 —— 记录每个任务的提醒触发次数和最后触发时间。
 *
 * 使用 SharedPreferences 存储，确保 app 重启后提醒循环状态不丢失。
 * 每个任务独立维护 trigger_count，最大循环次数由 [MAX_TRIGGER_COUNT] 控制。
 */
object ReminderStore {

    /** 存储文件名 */
    private const val PREFS_NAME = "nion_reminder_state"

    /** 最大提醒循环次数，超过此次数后自动终止循环 */
    const val MAX_TRIGGER_COUNT = 5

    /** 循环间隔（分钟），每次未响应后等待多久再次提醒 */
    const val LOOP_INTERVAL_MINUTES = 5L

    /**
     * 获取指定任务的提醒触发次数。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @return 已触发次数（0 表示尚未触发）
     */
    fun getTriggerCount(context: Context, taskId: String): Int {
        return prefs(context).getInt(key(taskId), 0)
    }

    /**
     * 设置指定任务的提醒触发次数。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @param count 新的触发次数
     */
    fun setTriggerCount(context: Context, taskId: String, count: Int) {
        prefs(context).edit().putInt(key(taskId), count).apply()
    }

    /**
     * 重置指定任务的提醒触发次数为 0。
     * 用户响应提醒（开始/取消）后调用，终止循环。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     */
    fun resetTriggerCount(context: Context, taskId: String) {
        prefs(context).edit().remove(key(taskId)).apply()
    }

    /**
     * 递增触发次数并返回新值。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @return 递增后的触发次数
     */
    fun incrementTriggerCount(context: Context, taskId: String): Int {
        val newCount = getTriggerCount(context, taskId) + 1
        setTriggerCount(context, taskId, newCount)
        return newCount
    }

    /**
     * 判断指定任务是否还能继续触发提醒循环。
     *
     * @param context 上下文
     * @param taskId 任务 ID
     * @return true 表示还能继续循环
     */
    fun canTriggerAgain(context: Context, taskId: String): Boolean {
        return getTriggerCount(context, taskId) < MAX_TRIGGER_COUNT
    }

    /** 获取 SharedPreferences 实例 */
    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 生成存储 key：trigger_count_{taskId} */
    private fun key(taskId: String): String = "trigger_count_$taskId"
}
