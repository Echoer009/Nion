package com.echonion.nion.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForInspector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.echonion.nion.MainActivity
import com.echonion.nion.NionApp
import com.echonion.nion.R
import com.echonion.nion.core
import com.echonion.nion.ui.companion.MarkdownText
import com.echonion.nion.ui.theme.CustomThemeEntry
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.NionTheme
import com.echonion.nion.ui.theme.ThemePalette
import uniffi.nion_core.StickerData

/**
 * 天气预警悬浮窗 Service —— 在 App 退到后台时，通过 WindowManager 显示天气预警悬浮卡片。
 *
 * 与 GreetingFloatingService 结构一致，但针对天气预警场景调整了：
 * - 图标使用 Warning（警告图标）
 * - 标题根据 severity 动态变化（天气紧急提醒 / 天气提醒 / 天气提示）
 * - 强调色根据 severity 变化（urgent=error, warning=tertiary, info=primary）
 *
 * 前提条件：
 * - AndroidManifest 声明 SYSTEM_ALERT_WINDOW 权限
 * - 用户已授予悬浮窗权限（Settings.canDrawOverlays()）
 *
 * @see OverlayDispatcher 三模分发器，决定何时启动此 Service
 * @see WeatherAlertWorker 天气预警逻辑执行方
 */
class WeatherAlertFloatingService : Service() {

    companion object {
        private const val TAG = "WeatherAlertFloating"
        private const val CHANNEL_ID = "weather_alert_floating"
        private const val NOTIFICATION_ID = 4001

        // Intent Extra keys
        const val EXTRA_SEVERITY = "severity"
        const val EXTRA_MESSAGE = "message"

        /**
         * 启动天气预警悬浮窗 Service。
         * 只有在拥有悬浮窗权限时才应调用。
         *
         * @param context 上下文
         * @param severity 预警严重程度："info" / "warning" / "urgent"
         * @param message 预警文案
         */
        fun start(context: Context, severity: String, message: String) {
            val intent = Intent(context, WeatherAlertFloatingService::class.java).apply {
                putExtra(EXTRA_SEVERITY, severity)
                putExtra(EXTRA_MESSAGE, message)
            }
            // Android 8+ 后台启动 Service 需要前台 Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "启动天气预警悬浮窗 Service: severity=$severity")
        }
    }

    // 悬浮窗相关
    private var windowManager: WindowManager? = null
    private var floatingView: android.view.View? = null

    // 是否正在播放收回动画，防止重复触发关闭逻辑
    private var isAnimatingDismiss = false

    // 预警数据
    private var severity: String = "info"
    private var message: String = ""

    // ComposeView 所需的 Lifecycle 和 SavedState 支持
    private lateinit var lifecycleOwner: FloatingLifecycleOwner

    override fun onCreate() {
        super.onCreate()
        // 创建前台通知渠道和通知，保活 Service
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        Log.d(TAG, "天气预警悬浮窗 Service 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 读取预警数据
        severity = intent.getStringExtra(EXTRA_SEVERITY) ?: "info"
        message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""

        Log.d(TAG, "显示天气预警悬浮窗: severity=$severity")

        // 显示悬浮窗
        showFloatingWindow()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Service 销毁时直接移除，不播放动画（可能是系统强制销毁）
        removeFloatingWindow(animated = false)
        super.onDestroy()
        Log.d(TAG, "天气预警悬浮窗 Service 已销毁")
    }

    /**
     * 创建前台通知渠道。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "天气预警悬浮窗",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "天气预警悬浮窗保活通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 构建前台 Service 保活通知。
     * 使用低优先级、静默通知，不打扰用户。
     */
    private fun buildForegroundNotification(): android.app.Notification {
        // 点击通知 → 打开 App
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nion 天气预警")
            .setContentText("正在显示天气预警...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 通过 WindowManager 显示悬浮窗。
     *
     * 使用 ComposeView 渲染天气预警卡片 UI，
     * 通过 TYPE_APPLICATION_OVERLAY 让窗口显示在其他 App 上方。
     */
    private fun showFloatingWindow() {
        if (floatingView != null) {
            // 已存在悬浮窗，先移除旧的
            removeFloatingWindow(animated = false)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 初始化 LifecycleOwner，ComposeView 需要
        lifecycleOwner = FloatingLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // 创建 ComposeView
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            // 设置 Lifecycle 和 SavedState 树，Compose 才能正常工作
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                // 从 NionApp 获取当前主题设置
                val app = applicationContext as NionApp
                val themePalette = try {
                    val mode = app.core.getSetting("theme_mode") ?: "preset"
                    when (mode) {
                        "custom" -> {
                            val activeId = app.core.getSetting("active_custom_theme_id") ?: ""
                            if (activeId.isBlank()) NionColorTheme.CORAL.palette()
                            else {
                                val themes = CustomThemeEntry.listFromJson(app.core.getSetting("custom_themes_list") ?: "[]")
                                val entry = themes.find { it.id == activeId }
                                entry?.palette ?: NionColorTheme.CORAL.palette()
                            }
                        }
                        else -> {
                            val name = app.core.getSetting("color_theme") ?: "CORAL"
                            NionColorTheme.entries.find { it.name == name }?.palette() ?: NionColorTheme.CORAL.palette()
                        }
                    }
                } catch (_: Exception) {
                    NionColorTheme.CORAL.palette()
                }
                val stickers = remember { app.core.getStickers() }

                NionTheme(palette = themePalette) {
                    FloatingWeatherAlertCard(
                        severity = severity,
                        message = message,
                        stickers = stickers,
                        onDismiss = { handleDismiss() },
                    )
                }
            }
        }

        // 配置 WindowManager LayoutParams
        val params = WindowManager.LayoutParams(
            // 宽度占屏幕 92%，水平居中
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // Android 8+ 使用 TYPE_APPLICATION_OVERLAY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // 不获取焦点，不阻挡触摸事件传递到底层窗口
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 距离底部 80dp，避免遮挡导航栏
            val density = resources.displayMetrics.density
            y = (80 * density).toInt()
            // 92% 屏幕宽度
            width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        }

        windowManager!!.addView(composeView, params)
        floatingView = composeView
    }

    /**
     * 移除悬浮窗并清理资源。
     *
     * @param animated 是否播放收回动画。
     *   true = 播放向下平移 + 淡出动画后再移除 View 并停止 Service，
     *         用于用户主动关闭的场景（点击按钮、滑动关闭）。
     *   false = 直接移除，用于 Service 被系统销毁、替换旧悬浮窗等场景。
     */
    private fun removeFloatingWindow(animated: Boolean = false) {
        floatingView?.let { view ->
            if (animated && !isAnimatingDismiss) {
                // 标记正在动画中，防止重复触发
                isAnimatingDismiss = true
                // 收回动画：向下平移 + 淡出
                view.animate()
                    .translationY(view.height.toFloat() * 0.5f)
                    .alpha(0f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        actuallyRemoveView(view)
                        isAnimatingDismiss = false
                        stopSelf()
                    }
                    .start()
            } else {
                actuallyRemoveView(view)
            }
        }
        if (!animated) {
            floatingView = null
        }
    }

    /**
     * 真正从 WindowManager 移除 View 并清理 Lifecycle 资源。
     */
    private fun actuallyRemoveView(view: android.view.View) {
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "移除天气预警悬浮窗失败", e)
        }
        floatingView = null

        // 清理 Lifecycle
        try {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Exception) {}
    }

    /**
     * 处理关闭 —— 播放收回动画后关闭悬浮窗。
     */
    private fun handleDismiss() {
        Log.d(TAG, "关闭天气预警悬浮窗: severity=$severity")
        removeFloatingWindow(animated = true)
    }
}

/**
 * 天气预警悬浮窗卡片 UI —— 精简版设计，包含预警文案和关闭操作。
 *
 * 与问候卡片设计语言一致，但针对天气预警调整：
 * - 图标使用 Warning（警告图标）
 * - 标题根据 severity 动态变化
 * - 强调色根据 severity 变化（urgent=error, warning=tertiary, info=primary）
 *
 * @param severity 预警严重程度："info" / "warning" / "urgent"
 * @param message 预警文案（LLM 生成或模板）
 * @param stickers 可用的表情包列表，用于渲染 <标签名> 为行内图片
 * @param onDismiss 关闭回调（点击关闭按钮 / "知道了" 按钮 / 滑动关闭）
 */
@Composable
private fun FloatingWeatherAlertCard(
    severity: String,
    message: String,
    stickers: List<StickerData>,
    onDismiss: () -> Unit,
) {
    // 根据 severity 选择强调色：urgent=error红, warning=tertiary黄, info=primary蓝
    val accentColor = when (severity) {
        "urgent" -> MaterialTheme.colorScheme.error
        "warning" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val cardColor = MaterialTheme.colorScheme.surface
    val onCardColor = MaterialTheme.colorScheme.onSurface

    // 根据 severity 获取显示标题
    val title = WeatherAlertEvent.getTitle(severity)

    // 水平滑动偏移量，用于实现滑动关闭手势
    var offsetX by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = with(LocalDensity.current) { offsetX.toDp() })
            // 左右滑动关闭手势：滑动超过卡片宽度 40% 时自动关闭
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(offsetX) > size.width * 0.4f) {
                            onDismiss()
                        } else {
                            offsetX = 0f
                        }
                    },
                ) { _, dragAmount ->
                    offsetX += dragAmount
                }
            }
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = cardColor.copy(alpha = 0.3f),
                spotColor = cardColor.copy(alpha = 0.2f),
            ),
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── 标题栏：警告图标 + 预警标题 + 关闭按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 警告图标，半透明圆形背景
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                // 预警标题（加粗）
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onCardColor,
                    modifier = Modifier.weight(1f),
                )
                // 关闭按钮：圆形，半透明背景，点击触发收回动画
                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    color = onCardColor.copy(alpha = 0.15f),
                    shape = CircleShape,
                    onClick = onDismiss,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(14.dp),
                            tint = onCardColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // ── 预警文案（支持表情包渲染）──
            MarkdownText(
                content = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = onCardColor.copy(alpha = 0.85f),
                ),
                stickers = stickers,
            )

            // ── "知道了" 按钮：实心填充，使用强调色 ──
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("知道了", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 同包下 ReminderFloatingService.kt 中已定义 FloatingLifecycleOwner，
 * 此处不再重复声明，直接复用同一 internal class。
 */
