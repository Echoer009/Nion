package com.echonion.nion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
 * - 处理提醒通知的点击跳转（通过 intent extra "reminder_task_id"）
 */
class MainActivity : ComponentActivity() {

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

        setContent {
            NionApp()
        }
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
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (status != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
