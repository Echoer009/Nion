package com.echonion.nion

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.echonion.nion.ui.NionApp
import com.echonion.nion.ui.music.MusicSettings
import com.echonion.nion.ui.music.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    /** 待处理的音乐注册深链用户名（nion://musicauth?u=..&p=.. 回传）；null 表示无待处理注册 */
    var pendingMusicAuthUsername: String? = null
        private set

    /** 待处理的音乐注册深链密码（与 [pendingMusicAuthUsername] 成对出现） */
    var pendingMusicAuthPassword: String? = null
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

        // 音乐注册深链与通知 extras 互不干扰：先尝试深链（ACTION_VIEW + data），
        // 匹配到 nion://musicauth 就落库并交给 Compose 层，否则走原通知解析
        handleMusicAuthDeepLink(intent)

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
     * 处理音乐模块注册深链。
     *
     * 流程：配置引导表单「注册账号」按钮 → 系统浏览器打开网页注册页
     * （https://echonion.org/music/reg/start）→ 注册完成网页重定向
     * nion://musicauth?u=用户名&p=密码 → 本方法解析：
     * 1. 账密立即写入 settings 表（键名与配置表单保存共用，见 [MusicSettings]），
     *    同时把音源切到类脑娘服务器 —— 直接落库保证 Compose 层未组合音乐页也不丢数据
     * 2. 存入 pendingMusicAuthXxx 字段，由音乐界面的 LaunchedEffect 消费，
     *    同步 ViewModel 内存状态并自动触发连接（照通知 Intent 的处理模式）
     * 3. Toast 提示注册成功
     */
    private fun handleMusicAuthDeepLink(intent: Intent) {
        // 只认 ACTION_VIEW + nion://musicauth，其他 intent（通知等）原样放过
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "nion" || data.host != "musicauth") return
        val username = data.getQueryParameter("u")?.trim().orEmpty()
        val password = data.getQueryParameter("p").orEmpty()
        Log.d("MainActivity", "解析注册深链: u=$username, 携带密码=${password.isNotEmpty()}")
        // 参数不完整视为非法回调，静默忽略（不覆盖已有配置）
        if (username.isEmpty() || password.isEmpty()) return
        // 落库（同步 DB 调用切到 IO 线程执行，与 MusicViewModel 的写法一致）
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                application.core().apply {
                    setSetting(MusicSettings.KEY_BRAINGIRL_USERNAME, username)
                    setSetting(MusicSettings.KEY_BRAINGIRL_PASSWORD, password)
                    setSetting(MusicSettings.KEY_SOURCE, MusicSource.BRAINGIRL.key)
                }
            }.onFailure { Log.e("MainActivity", "注册深链配置落库失败", it) }
        }
        // 交给音乐界面消费：同步 ViewModel 状态 + 自动触发连接
        pendingMusicAuthUsername = username
        pendingMusicAuthPassword = password
        Toast.makeText(this, "注册成功，已自动配置类脑娘音源", Toast.LENGTH_SHORT).show()
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
     * 清除待处理的音乐注册深链数据。
     * 音乐界面 LaunchedEffect 读完 pendingMusicAuthXxx 后必须调用，避免重复执行。
     */
    fun clearPendingMusicAuth() {
        pendingMusicAuthUsername = null
        pendingMusicAuthPassword = null
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
