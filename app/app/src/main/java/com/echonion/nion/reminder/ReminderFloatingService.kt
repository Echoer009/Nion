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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForInspector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.NionTheme

/**
 * 系统级悬浮窗 Service —— 在 App 退到后台时，通过 WindowManager 显示悬浮提醒卡片。
 *
 * 工作原理：
 * 1. ReminderWorker 检测到 App 不在前台 → 启动此 Service（携带提醒数据）
 * 2. Service 创建前台通知（保活），然后通过 WindowManager.addView() 添加悬浮窗
 * 3. 悬浮窗使用 ComposeView 渲染与 App 内相同的卡片 UI
 * 4. 用户操作后（开始/稍后/关闭）→ 播放收回动画 → 移除悬浮窗 → 停止 Service
 *
 * 前提条件：
 * - AndroidManifest 声明 SYSTEM_ALERT_WINDOW 权限
 * - 用户已授予悬浮窗权限（Settings.canDrawOverlays()）
 *
 * @see ReminderWorker 闹钟触发后的调用方
 */
class ReminderFloatingService : Service() {

    companion object {
        private const val TAG = "ReminderFloating"
        private const val CHANNEL_ID = "floating_reminder"
        private const val NOTIFICATION_ID = 2001

        // Intent Extra keys
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_TITLE = "task_title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TRIGGER_COUNT = "trigger_count"

        /**
         * 启动悬浮窗 Service。
         * 只有在拥有悬浮窗权限时才应调用。
         */
        fun start(
            context: Context,
            taskId: String,
            taskTitle: String,
            message: String,
            triggerCount: Int,
        ) {
            val intent = Intent(context, ReminderFloatingService::class.java).apply {
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_TASK_TITLE, taskTitle)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_TRIGGER_COUNT, triggerCount)
            }
            // Android 12+ 后台启动 Service 需要前台 Service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "启动悬浮窗 Service: taskId=$taskId")
        }

        /**
         * 停止悬浮窗 Service 并移除悬浮窗。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, ReminderFloatingService::class.java))
        }
    }

    // 悬浮窗相关
    private var windowManager: WindowManager? = null
    private var floatingView: android.view.View? = null

    // 是否正在播放收回动画，防止重复触发关闭逻辑
    private var isAnimatingDismiss = false

    // 提醒数据
    private var taskId: String = ""
    private var taskTitle: String = ""
    private var message: String = ""
    private var triggerCount: Int = 1

    // ComposeView 所需的 Lifecycle 和 SavedState 支持
    private lateinit var lifecycleOwner: FloatingLifecycleOwner

    override fun onCreate() {
        super.onCreate()
        // 创建前台通知渠道和通知，保活 Service
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        Log.d(TAG, "悬浮窗 Service 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 读取提醒数据
        taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run { stopSelf(); return START_NOT_STICKY }
        taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE) ?: ""
        message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        triggerCount = intent.getIntExtra(EXTRA_TRIGGER_COUNT, 1)

        Log.d(TAG, "显示悬浮窗: taskId=$taskId, title=$taskTitle, trigger=$triggerCount")

        // 取消系统通知（悬浮窗已替代）
        NotificationHelper.dismissNotification(this, taskId)

        // 显示悬浮窗
        showFloatingWindow()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Service 销毁时直接移除，不播放动画（可能是系统强制销毁）
        removeFloatingWindow(animated = false)
        super.onDestroy()
        Log.d(TAG, "悬浮窗 Service 已销毁")
    }

    /**
     * 创建前台通知渠道。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮提醒",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "悬浮窗提醒保活通知"
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
            .setContentTitle("Nion 提醒")
            .setContentText("正在显示提醒...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 通过 WindowManager 显示悬浮窗。
     *
     * 使用 ComposeView 渲染与 App 内相同的卡片 UI，
     * 通过 TYPE_APPLICATION_OVERLAY 让窗口显示在其他 App 上方。
     */
    private fun showFloatingWindow() {
        if (floatingView != null) {
            // 已存在悬浮窗，先移除旧的（直接移除，不播放动画）
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
                val colorTheme = try {
                    val saved = app.core.getSetting("color_theme")
                    NionColorTheme.entries.find { it.name == saved } ?: NionColorTheme.BURNT_ORANGE
                } catch (_: Exception) {
                    NionColorTheme.BURNT_ORANGE
                }

                NionTheme(colorTheme = colorTheme) {
                    FloatingReminderCard(
                        taskTitle = taskTitle,
                        message = message,
                        triggerCount = triggerCount,
                        onFocus = { handleFocus() },
                        onSnooze = { minutes -> handleSnooze(minutes) },
                        onAcknowledge = { handleAcknowledge() },
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
                // 收回动画：向下平移一段距离并淡出，模拟卡片「收回底部」的效果
                // translationY 为卡片高度的一半，配合 alpha 淡出
                view.animate()
                    .translationY(view.height.toFloat() * 0.5f)
                    .alpha(0f)
                    // 300ms 收回动画，先快后慢的减速曲线
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        // 动画结束后真正移除 View 并清理资源
                        actuallyRemoveView(view)
                        isAnimatingDismiss = false
                        // 收回动画结束后停止 Service
                        stopSelf()
                    }
                    .start()
            } else {
                // 直接移除（Service 销毁、重复调用等场景）
                actuallyRemoveView(view)
            }
        }
        // 非动画模式立即清空引用；动画模式在 actuallyRemoveView 中清空
        if (!animated) {
            floatingView = null
        }
    }

    /**
     * 真正从 WindowManager 移除 View 并清理 Lifecycle 资源。
     * 被 removeFloatingWindow 的两种模式最终调用。
     */
    private fun actuallyRemoveView(view: android.view.View) {
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "移除悬浮窗失败", e)
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
     * 处理右上角专注图标 —— 终止循环 + 跳转 App 专注页面（不自动开始）+ 播放收回动画后关闭悬浮窗。
     */
    private fun handleFocus() {
        // 终止提醒循环
        ReminderStore.resetTriggerCount(this, taskId)
        ReminderScheduler.cancelReminder(this, taskId)
        NotificationHelper.dismissNotification(this, taskId)

        // 跳转 App 专注页面，auto_start_focus=false 让用户自己设置时间
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            launchIntent.putExtra("reminder_action", "start_focus")
            launchIntent.putExtra("reminder_task_id", taskId)
            launchIntent.putExtra("focus_task_title", taskTitle)
            launchIntent.putExtra("auto_start_focus", false)
            startActivity(launchIntent)
        }

        Log.d(TAG, "开始专注: taskId=$taskId")
        // 播放收回动画，动画结束后自动 stopSelf()
        removeFloatingWindow(animated = true)
    }

    /**
     * 处理「知道了」主按钮 —— 终止循环 + 关闭悬浮窗，不跳转 App。
     */
    private fun handleAcknowledge() {
        // 终止提醒循环
        ReminderStore.resetTriggerCount(this, taskId)
        ReminderScheduler.cancelReminder(this, taskId)
        NotificationHelper.dismissNotification(this, taskId)
        Log.d(TAG, "知道了: taskId=$taskId")
        // 播放收回动画，动画结束后自动 stopSelf()
        removeFloatingWindow(animated = true)
    }

    /**
     * 处理「稍后提醒」—— 播放收回动画 + 调度延迟闹钟后关闭悬浮窗。
     */
    private fun handleSnooze(minutes: Int) {
        NotificationHelper.dismissNotification(this, taskId)
        ReminderScheduler.scheduleSnoozeReminder(this, taskId, minutes)
        Log.d(TAG, "稍后提醒 ${minutes}分钟: taskId=$taskId")
        // 播放收回动画，动画结束后自动 stopSelf()
        removeFloatingWindow(animated = true)
    }

    /**
     * 处理关闭/「今天算了」—— 终止循环 + 播放收回动画后关闭悬浮窗。
     */
    private fun handleDismiss() {
        // 如果是「今天算了」（triggerCount < 5），终止循环
        if (triggerCount < 5) {
            ReminderStore.resetTriggerCount(this, taskId)
            ReminderScheduler.cancelReminder(this, taskId)
        }
        NotificationHelper.dismissNotification(this, taskId)
        Log.d(TAG, "关闭悬浮窗: taskId=$taskId")
        // 播放收回动画，动画结束后自动 stopSelf()
        removeFloatingWindow(animated = true)
    }
}

/**
 * 悬浮窗卡片 UI —— 复用与 ReminderOverlay 相同的设计语言。
 *
 * 区别在于：此卡片用于系统级悬浮窗（App 在后台时），由 Service 的 ComposeView 渲染。
 *
 * @param taskTitle 任务标题
 * @param message 提醒文案
 * @param triggerCount 触发次数（1-5）
 * @param onFocus 点击右上角专注图标回调，跳转专注页但不自动开始
 * @param onSnooze 点击稍后提醒回调，参数为延迟分钟数
 * @param onAcknowledge 点击「知道了」主按钮回调，终止循环并关闭
 * @param onDismiss 关闭按钮/滑动关闭回调
 */
@Composable
private fun FloatingReminderCard(
    taskTitle: String,
    message: String,
    triggerCount: Int,
    onFocus: () -> Unit,
    onSnooze: (Int) -> Unit,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    // ── 紧迫感颜色渐变：强调色从主题色 primary 线性过渡到 error 红色 ──
    // 与 App 内 ReminderOverlay 保持完全一致的插值逻辑
    val progress = ((triggerCount - 1).toFloat() / 4f).coerceIn(0f, 1f)
    val cardColor = MaterialTheme.colorScheme.surface
    val onCardColor = MaterialTheme.colorScheme.onSurface
    val accentColor = lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error, progress)

    // 按钮文案
    val labels = ReminderMessageGenerator.getActionLabels(triggerCount)

    // 水平滑动偏移量
    var offsetX by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = with(LocalDensity.current) { offsetX.toDp() })
            // 左右滑动关闭手势
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
            // ── 标题栏 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 图标
                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Alarm,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                // 任务标题 + 提醒次数
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        taskTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onCardColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (triggerCount > 1) {
                        Text(
                            "第 $triggerCount 次提醒",
                            style = MaterialTheme.typography.labelSmall,
                            color = onCardColor.copy(alpha = 0.6f),
                        )
                    }
                }
                // ── 右上角操作按钮组：专注图标 + 关闭按钮 ──
                // 专注图标按钮：点击跳转专注页（不自动开始），用于需要专注的任务
                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    // 使用强调色半透明背景，暗示"可操作"
                    color = accentColor.copy(alpha = 0.15f),
                    shape = CircleShape,
                    onClick = onFocus,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = "开始专注",
                            modifier = Modifier.size(14.dp),
                            tint = accentColor,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 关闭按钮
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

            // ── 提醒文案 ──
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = onCardColor.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            // ── 稍后提醒选项 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("5 分钟" to 5, "10 分钟" to 10, "30 分钟" to 30).forEach { (label, minutes) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = onCardColor.copy(alpha = 0.12f),
                        onClick = { onSnooze(minutes) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = onCardColor,
                            )
                        }
                    }
                }
            }

            // ── 操作按钮：「知道了」主按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 「知道了」主按钮：实心填充，使用强调色，点击终止循环并关闭
                val ackLabel = labels.first.ifBlank { "知道了" }
                Button(
                    onClick = onAcknowledge,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(ackLabel, fontWeight = FontWeight.SemiBold)
                }

                if (labels.third.isNotEmpty()) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            labels.third,
                            fontWeight = FontWeight.Medium,
                            color = onCardColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自定义 LifecycleOwner —— 为 ComposeView 提供 Lifecycle 支持。
 *
 * ComposeView 必须在一个拥有 Lifecycle 和 SavedState 的 View 树中才能正常工作。
 * 在 Service 中没有现成的 LifecycleOwner，所以需要手动创建并管理生命周期。
 */
internal class FloatingLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performRestore(null)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
