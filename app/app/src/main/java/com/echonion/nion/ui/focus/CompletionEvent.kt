package com.echonion.nion.ui.focus

/**
 * 专注完成事件数据类 —— 从 FocusTimerViewModel 发送到 UI 层的事件。
 *
 * CompletionOverlay 监听此事件流，收到事件后调用 CompletionMotivator 获取鼓励文案，
 * 然后弹出底部悬浮卡片展示。
 *
 * @property taskName 任务名称，无关联任务时为 null
 * @property sessionMinutes 本次实际专注分钟数
 * @property plannedMinutes 计划专注分钟数（设定的总时长）
 * @property totalMinutes 该任务累计专注分钟数（含本次）
 * @property todaySessions 今日已完成专注次数（含本次）
 * @property todayMinutes 今日专注总分钟数（含本次）
 * @property isEarlyStop 是否提前结束（≥5 分钟才触发事件）
 */
data class CompletionEvent(
    val taskName: String?,
    val sessionMinutes: Int,
    val plannedMinutes: Int,
    val totalMinutes: Int,
    val todaySessions: Int,
    val todayMinutes: Int,
    val isEarlyStop: Boolean,
)
