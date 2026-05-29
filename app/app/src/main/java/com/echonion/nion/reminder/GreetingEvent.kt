package com.echonion.nion.reminder

/**
 * 问候事件数据类 —— 从 GreetingWorker 发送到 UI 层的事件。
 *
 * GreetingOverlay 监听此事件流，收到事件后弹出问候悬浮卡片。
 * 后台场景下由 GreetingFloatingService 直接处理，不经过此事件流。
 *
 * @property greetingType 问候类型："morning" / "noon" / "evening"
 * @property message LLM 生成的问候文案（或模板兜底文案）
 */
data class GreetingEvent(
    val greetingType: String,
    val message: String,
) {
    companion object {
        /** 根据问候类型获取显示标题 */
        fun getTitle(type: String): String = when (type) {
            "morning" -> "早上好"
            "noon" -> "中午好"
            "evening" -> "晚上好"
            else -> "你好"
        }
    }
}
