package com.echonion.nion.reminder

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.echonion.nion.NionApp

/**
 * 悬浮通知三模分发器 —— 封装「前台/后台/兜底」的通知展示策略。
 *
 * 所有需要弹出悬浮通知的场景（任务提醒、早中晚问候等）都通过此分发器统一决策，
 * 避免每个 Worker 各自重复编写前后台判断 + 悬浮窗权限检查的逻辑。
 *
 * 三种模式：
 * 1. **前台模式** — App 在前台，通过 SharedFlow 发事件给 Compose Overlay（App 内悬浮卡片）
 * 2. **后台悬浮窗模式** — App 在后台 + 有 SYSTEM_ALERT_WINDOW 权限 → 启动 FloatingService
 * 3. **兜底模式** — App 在后台 + 无悬浮窗权限 → 系统通知栏
 */
object OverlayDispatcher {

    private const val TAG = "OverlayDispatcher"

    /**
     * 根据当前 App 前后台状态和悬浮窗权限，选择通知展示方式。
     *
     * 调用方只需提供三种回调，分发器负责判断走哪个分支。
     *
     * @param context 上下文，用于获取 NionApp 实例和检查权限
     * @param onForeground App 在前台时调用，通常发 SharedFlow 事件给 Compose Overlay
     * @param onBackgroundOverlay App 在后台且有悬浮窗权限时调用，通常启动 FloatingService
     * @param onFallback 兜底回调，通常发送系统通知栏通知
     */
    fun dispatch(
        context: Context,
        onForeground: () -> Unit,
        onBackgroundOverlay: () -> Unit,
        onFallback: () -> Unit,
    ) {
        val app = context as? NionApp ?: run {
            Log.w(TAG, "无法获取 NionApp 实例，走兜底路径")
            onFallback()
            return
        }

        if (app.isInForeground) {
            // 前台：发 SharedFlow 事件 → Compose Overlay 显示 App 内悬浮卡片
            try {
                onForeground()
                Log.d(TAG, "已选择前台模式（SharedFlow → Overlay）")
            } catch (e: Exception) {
                Log.w(TAG, "前台模式失败，走兜底路径", e)
                onFallback()
            }
        } else {
            // 后台：检查是否有悬浮窗权限
            val hasOverlayPermission = hasOverlayPermission(context)

            if (hasOverlayPermission) {
                try {
                    onBackgroundOverlay()
                    Log.d(TAG, "已选择后台悬浮窗模式（FloatingService）")
                } catch (e: Exception) {
                    Log.w(TAG, "启动悬浮窗失败，走兜底路径", e)
                    onFallback()
                }
            } else {
                Log.d(TAG, "App 在后台且无悬浮窗权限，走兜底路径（系统通知）")
                onFallback()
            }
        }
    }

    /**
     * 检查是否拥有悬浮窗权限（SYSTEM_ALERT_WINDOW）。
     *
     * Android M (6.0) 以下版本默认拥有此权限，无需检查。
     */
    private fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}
