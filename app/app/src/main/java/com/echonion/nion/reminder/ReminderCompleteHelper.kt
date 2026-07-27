package com.echonion.nion.reminder

import android.app.Application
import android.util.Log
import com.echonion.nion.NionApp
import com.echonion.nion.notifyDataChanged
import com.echonion.nion.ui.companion.tools.DataType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nion_core.NionCore
import uniffi.nion_core.TaskData

/**
 * 提醒完成助手 —— 在悬浮窗 / 前台 Overlay / 通知栏点击「知道了」时，
 * 统一将任务标记为完成。
 *
 * 区分任务类型（与 [com.echonion.nion.ui.task.TaskViewModel.toggleDone] 保持一致）：
 * - 每日任务（recurrenceRule="daily"）：调用 [NionCore.completeDailyTaskInstance] 完成当前实例，
 *   并为自动创建的明天实例注册每日提醒闹钟，保证每日任务链不断裂。
 * - 普通任务：直接 [NionCore.updateTask] 将 status 改为 "done"。
 *
 * 所有数据库操作在 IO 线程异步执行，不阻塞 UI；失败时仅记录日志，
 * 不影响悬浮窗关闭等用户体验。
 */
object ReminderCompleteHelper {

    private const val TAG = "ReminderComplete"
    /** recurrence_rule 字段值：每日循环 */
    private const val RECURRENCE_DAILY = "daily"
    /** tasks.status 字段值：已完成 */
    private const val STATUS_DONE = "done"

    /**
     * 从提醒入口（悬浮窗 / Overlay）标记任务完成 —— fire-and-forget 模式。
     *
     * 异步执行：数据库调用切到 IO 线程，主线程立即返回，避免悬浮窗关闭动画卡顿。
     * 失败仅记日志，不影响用户体验。
     *
     * 适用于悬浮窗 Service / Composable Overlay 等长生命周期调用方。
     * BroadcastReceiver 请使用 [completeTaskFromReminderSync] 配合 `goAsync()`。
     *
     * @param app Application 上下文（用于获取 NionCore 单例与调度闹钟）
     * @param taskId 要完成的任务 ID
     */
    fun completeTaskFromReminder(app: Application, taskId: String) {
        // MainScope 绑定应用级生命周期，悬浮窗 Service 关闭后仍可完成写入
        MainScope().launch {
            try {
                completeTaskFromReminderSync(app, taskId)
            } catch (e: Exception) {
                // 完成失败仅记录日志，不影响用户关闭悬浮窗的体验
                Log.w(TAG, "通过提醒完成任务失败: taskId=$taskId", e)
            }
        }
    }

    /**
     * 同步标记任务完成（suspend，需在协程中调用）。
     *
     * 与 [completeTaskFromReminder] 的区别：调用方可控地等待完成，
     * 适用于 BroadcastReceiver 等需要在 `onReceive` 返回前确保异步完成的场景
     * （配合 `goAsync()` + `PendingResult.finish()`）。
     *
     * 内部按 recurrenceRule 区分每日 / 普通任务，与主界面勾选完成的语义一致。
     *
     * @param app Application 上下文
     * @param taskId 要完成的任务 ID
     */
    suspend fun completeTaskFromReminderSync(app: Application, taskId: String) {
        withContext(Dispatchers.IO) {
            val core = (app as NionApp).core
            val task = core.getTask(taskId)
            if (task.recurrenceRule == RECURRENCE_DAILY) {
                // 每日任务：完成当前实例，core 会自动创建明天的实例。
                // 双保险：即使 completeDailyTaskInstance 因异常失败，也降级为直接标记 done，
                // 确保用户点「知道了」后任务一定被完成（Rust 侧已保证先标记完成，这里是第二道防线）。
                try {
                    val result = core.completeDailyTaskInstance(taskId)
                    // 为自动生成的明天实例注册每日提醒闹钟，保证每日提醒链不断裂
                    result.newTask?.let { newTask ->
                        scheduleReminderForTask(app, newTask)
                        Log.d(TAG, "每日任务已完成并续期: taskId=$taskId, newTaskId=${newTask.id}")
                    }
                } catch (e: Exception) {
                    // completeDailyTaskInstance 失败时降级为直接标记 status=done
                    Log.w(TAG, "completeDailyTaskInstance 失败，降级为直接标记完成: taskId=$taskId", e)
                    core.updateTask(taskId, null, null, null, STATUS_DONE, null, null, null, null, null)
                }
            } else {
                // 普通任务：updateTask 第 5 个参数为 status，传入 "done"
                core.updateTask(taskId, null, null, null, STATUS_DONE, null, null, null, null, null)
                Log.d(TAG, "普通任务已完成: taskId=$taskId")
            }
            // 通知 UI 刷新任务列表 —— 修复完成后界面不更新导致用户以为「知道了」没生效的问题。
            // TaskViewModel 监听 dataEvents，收到 TASK_DATA 事件后会重新加载任务列表。
            (app as NionApp).notifyDataChanged(setOf(DataType.TASK_DATA))
        }
    }

    /**
     * 为任务注册提醒闹钟（复用 TaskViewModel.scheduleReminderIfNeeded 的逻辑）。
     *
     * - 每日循环任务：解析 recurrenceReminderTime（"HH:MM"）调度每日闹钟
     * - 普通任务：解析 reminder（"yyyy-MM-dd'T'HH:mm"）调度一次性精确闹钟
     *
     * @param app 上下文
     * @param task 需要注册闹钟的任务（通常是 completeDailyTaskInstance 生成的明天实例）
     */
    private fun scheduleReminderForTask(app: Application, task: TaskData) {
        // 先取消已有闹钟，避免重复注册
        ReminderScheduler.cancelReminder(app, task.id)

        // 每日循环任务：解析 HH:MM 调度每日闹钟
        if (task.recurrenceRule == RECURRENCE_DAILY && task.recurrenceReminderTime != null) {
            val parts = task.recurrenceReminderTime!!.split(":")
            if (parts.size == 2) {
                val hour = parts[0].toIntOrNull()
                val minute = parts[1].toIntOrNull()
                if (hour != null && minute != null) {
                    ReminderScheduler.scheduleDailyReminder(app, task.id, hour, minute)
                }
            }
        }

        // 普通任务：解析 reminder 时间戳调度一次性闹钟
        if (task.reminder != null) {
            try {
                // reminder 格式可能为 "YYYY-MM-DDTHH:MM" 或 RFC 3339
                val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                val ldt = java.time.LocalDateTime.parse(task.reminder, formatter)
                val millis = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (millis > System.currentTimeMillis()) {
                    ReminderScheduler.scheduleExactReminder(app, task.id, millis)
                }
            } catch (_: Exception) {
                // 解析失败静默忽略（与 TaskViewModel 行为一致）
            }
        }
    }
}
