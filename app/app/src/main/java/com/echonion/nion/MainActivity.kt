package com.echonion.nion

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.echonion.nion.ui.NionApp

/**
 * 应用主 Activity。
 *
 * 负责：
 * - 设置 Compose 内容
 * - 请求 Android 13+ 的通知权限（POST_NOTIFICATIONS）
 * - 处理提醒通知的点击跳转（通过 intent extra 携带操作指令）
 *
 * 通知跳转流程：
 * 1. 通知的 contentIntent 或 Action 按钮点击 → 启动/唤醒此 Activity
 * 2. onNewIntent() 解析 extras → 存入 pendingXxx 字段
 * 3. Compose 层通过 LaunchedEffect 读取 pendingXxx → 执行导航/展开面板
 * 4. 调用 clearPendingIntent() 清除，避免重复执行
 */
class MainActivity : ComponentActivity() {

    /**
     * 待处理的 Intent 操作，Compose 层读取后执行对应导航。
     * null 表示无待处理操作。
     */
    var pendingIntentAction: String? = null
        private set

    /** 待处理的任务 ID（从通知 intent 传入） */
    var pendingTaskId: String? = null
        private set

    /** 待处理的任务标题（从通知 intent 传入） */
    var pendingTaskTitle: String? = null
        private set

    /**
     * 通知权限请求 launcher。
     * Android 13+（API 33+）需要运行时请求 POST_NOTIFICATIONS 权限，
     * 否则系统通知不会显示。
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // 用户拒绝了通知权限，提醒功能会受到限制
            // 不强制弹窗，用户可以在系统设置中手动开启
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 请求通知权限（Android 13+）
        requestNotificationPermission()

        // 解析初始 intent（app 从通知冷启动时）
        parseIntentExtras(intent)

        setContent {
            NionApp()
        }
    }

    /**
     * Activity 已存在时，新的 intent 通过此回调传入。
     * 适用于用户在通知栏点击时 app 已在后台的场景。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseIntentExtras(intent)
    }

    /**
     * 解析 intent extras，存入 pendingXxx 字段供 Compose 层读取。
     *
     * 支持的 extras：
     * - "reminder_action" → "start_focus"（开始做了）| "open_companion"（展开伙伴面板）
     * - "reminder_task_id" → 任务 ID
     * - "focus_task_title" → 任务标题
     * - "auto_start_focus" → 是否自动开始专注
     * - "open_companion" → boolean，是否展开伙伴面板
     */
    private fun parseIntentExtras(intent: Intent?) {
        if (intent == null) return

        val reminderAction = intent.getStringExtra("reminder_action")
        val reminderTaskId = intent.getStringExtra("reminder_task_id")
        val openCompanion = intent.getBooleanExtra("open_companion", false)

        if (reminderAction != null) {
            // 来自通知 Action 按钮（如「开始做了」）
            pendingIntentAction = reminderAction
            pendingTaskId = reminderTaskId
            pendingTaskTitle = intent.getStringExtra("focus_task_title")
            Log.d("MainActivity", "解析通知 Intent: action=$reminderAction, taskId=$reminderTaskId")
        } else if (reminderTaskId != null && openCompanion) {
            // 来自通知主体点击 → 展开伙伴面板
            pendingIntentAction = "open_companion"
            pendingTaskId = reminderTaskId
            Log.d("MainActivity", "解析通知 Intent: open_companion, taskId=$reminderTaskId")
        } else if (reminderTaskId != null) {
            // 旧版兼容：只带 reminder_task_id，无特殊操作
            pendingIntentAction = "open_companion"
            pendingTaskId = reminderTaskId
            Log.d("MainActivity", "解析通知 Intent (兼容): taskId=$reminderTaskId")
        }
    }

    /**
     * 清除待处理的 Intent 数据。
     * Compose 层读取完 pendingXxx 后必须调用，避免重复执行。
     */
    fun clearPendingIntent() {
        pendingIntentAction = null
        pendingTaskId = null
        pendingTaskTitle = null
    }

    /**
     * 请求通知权限。
     * 仅在 Android 13（API 33）及以上版本需要运行时请求。
     * 低于 Android 13 的版本，只需在 Manifest 声明权限即可。
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
            if (status != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
