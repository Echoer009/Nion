package com.echonion.nion.reminder

/**
 * 天气预警事件数据类 —— 从 WeatherAlertWorker 发送到 UI 层的事件。
 *
 * WeatherAlertOverlay 监听此事件流，收到事件后弹出天气预警悬浮卡片。
 * 后台场景下由 WeatherAlertFloatingService 直接处理，不经过此事件流。
 *
 * @property severity 预警严重程度：info / warning / urgent
 * @property message LLM 生成的预警文案（或模板兜底文案）
 */
data class WeatherAlertEvent(
    val severity: String,
    val message: String,
) {
    companion object {
        /** 根据严重程度获取显示标题 */
        fun getTitle(severity: String): String = when (severity) {
            "urgent" -> "天气紧急提醒"
            "warning" -> "天气提醒"
            else -> "天气提示"
        }
    }
}
