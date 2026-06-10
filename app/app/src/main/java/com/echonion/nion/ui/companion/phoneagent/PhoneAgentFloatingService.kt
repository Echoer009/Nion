package com.echonion.nion.ui.companion.phoneagent

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
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
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
import com.echonion.nion.ui.theme.CustomThemeEntry
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.NionTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Phone Agent 悬浮窗 Service —— 在 Agent 执行手机操作时悬浮显示思考和操作过程。
 *
 * 两种显示状态：
 * - 展开态（Expanded）：顶部标题栏 + 滚动日志区 + 底部暂停/停止按钮
 * - 最小化态（Minimized）：可拖拽圆球，只显示当前步数
 *
 * 关闭按钮仅隐藏悬浮窗，Agent 继续后台执行；
 * 停止按钮终止 Agent 任务。
 *
 * 状态更新通过 [stateFlow] 和 [logFlow] 从外部注入。
 */
class PhoneAgentFloatingService : Service() {

    /** 悬浮窗显示状态 */
    enum class FloatingState {
        EXPANDED,
        MINIMIZED,
    }

    /** 日志条目 */
    data class LogEntry(
        val type: String,
        val content: String,
        val stepNumber: Int,
        val success: Boolean? = null,
    )

    init {
        Log.d(TAG, "PhoneAgentFloatingService init")
    }

    companion object {
        private const val TAG = "PhoneAgentFloating"
        private const val CHANNEL_ID = "phone_agent_floating"
        private const val NOTIFICATION_ID = 3001

        private val _stateFlow = MutableStateFlow(FloatingState.EXPANDED)
        val stateFlow: StateFlow<FloatingState> = _stateFlow.asStateFlow()

        private val _stepFlow = MutableStateFlow(0)
        val stepFlow: StateFlow<Int> = _stepFlow.asStateFlow()

        private val _logFlow = MutableStateFlow<List<LogEntry>>(emptyList())
        val logFlow: StateFlow<List<LogEntry>> = _logFlow.asStateFlow()

        private val _isPausedFlow = MutableStateFlow(false)
        val isPausedFlow: StateFlow<Boolean> = _isPausedFlow.asStateFlow()

        /** 当前步正在流式接收的模型响应文本，逐 token 追加 */
        private val _currentResponseFlow = MutableStateFlow("")
        val currentResponseFlow: StateFlow<String> = _currentResponseFlow.asStateFlow()

        /** 追加一个 token 到当前响应 */
        fun appendToken(token: String) {
            _currentResponseFlow.value = _currentResponseFlow.value + token
        }

        /** 清空当前响应 */
        fun clearCurrentResponse() {
            _currentResponseFlow.value = ""
        }

        /** 更新当前步数 */
        fun updateStep(step: Int) {
            _stepFlow.value = step
        }

        /** 追加日志条目 */
        fun addLog(entry: LogEntry) {
            _logFlow.value = _logFlow.value + entry
        }

        /** 更新暂停状态 */
        fun updatePaused(paused: Boolean) {
            _isPausedFlow.value = paused
        }

        // ── 窗口管理（供 toggleMinimize / drag 使用）──
        private var windowManagerRef: WindowManager? = null
        private var windowViewRef: android.view.View? = null
        private var windowParamsRef: WindowManager.LayoutParams? = null
        private var screenDensity = 1f
        private var screenWidthPx = 1080

        /** 移动窗口（由原生触摸监听拖动时调用） */
        fun moveWindowBy(dx: Int, dy: Int) {
            val p = windowParamsRef ?: return
            p.x += dx
            p.y += dy
            anchorY = p.y
            try { windowManagerRef?.updateViewLayout(windowViewRef, p) } catch (_: Exception) {}
        }

        /** 设置窗口引用（在 showFloatingWindow 中调用） */
        fun setWindowRefs(
            wm: WindowManager,
            view: android.view.View,
            params: WindowManager.LayoutParams,
            density: Float,
            widthPx: Int,
        ) {
            windowManagerRef = wm
            windowViewRef = view
            windowParamsRef = params
            screenDensity = density
            screenWidthPx = widthPx
        }

        /** 切换到展开态窗口布局（大窗，顶部居中） */
        private fun applyExpandedLayout() {
            val params = windowParamsRef ?: return
            params.width = (screenWidthPx * 0.88).toInt()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = 0
            params.y = (60 * screenDensity).toInt()
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            try { windowManagerRef?.updateViewLayout(windowViewRef, params) } catch (_: Exception) {}
        }

        /** 切换到最小化态窗口布局（小窗，保持当前位置不变） */
        private fun applyMinimizedLayout() {
            val params = windowParamsRef ?: return
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            // 不修改 x/y，保持窗口原位，仅缩小尺寸
            try { windowManagerRef?.updateViewLayout(windowViewRef, params) } catch (_: Exception) {}
        }

        /** 切换展开/最小化，同时调整窗口大小。先改布局再改状态，减少视觉卡顿。 */
        fun toggleMinimize() {
            val newState = if (_stateFlow.value == FloatingState.EXPANDED)
                FloatingState.MINIMIZED else FloatingState.EXPANDED
            // 先调整窗口布局（缩小/放大），再触发 Compose 重组，避免大面板卸载与小窗口创建的串行开销
            if (newState == FloatingState.MINIMIZED) applyMinimizedLayout() else applyExpandedLayout()
            _stateFlow.value = newState
        }

        /** 窗口 Y 轴锚点，拖拽时实时更新 */
        @Volatile
        var anchorY = 0

        /** 重置所有状态（新任务开始前调用） */
        fun resetState() {
            _stateFlow.value = FloatingState.EXPANDED
            _stepFlow.value = 0
            _logFlow.value = emptyList()
            _isPausedFlow.value = false
            _currentResponseFlow.value = ""
            dismissed = false
            anchorY = 0
        }

        /** 用户是否手动关闭了悬浮窗（关闭后 Agent 继续后台执行，不重启悬浮窗） */
        @Volatile
        var dismissed = false

        /** 标记悬浮窗已关闭，下次 onStartCommand 不再重建 */
        fun dismiss() {
            dismissed = true
        }

        /** 启动悬浮窗 Service */
        fun start(context: Context) {
            val intent = Intent(context, PhoneAgentFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止悬浮窗 Service */
        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneAgentFloatingService::class.java))
        }
    }

    // 悬浮窗相关
    private var windowManager: WindowManager? = null
    private var floatingView: android.view.View? = null

    // Lifecycle
    private lateinit var lifecycleOwner: FloatingLifecycleOwner

    // ── 原生触摸拖动状态（绕过 FLAG_NOT_FOCUSABLE 对 Compose 手势的影响）──
    /** 触摸起始 rawX，用于计算拖动增量 */
    private var dragLastRawX = 0f
    /** 触摸起始 rawY，用于计算拖动增量 */
    private var dragLastRawY = 0f
    /** 是否正在拖动中（区分点击与拖动） */
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        Log.d(TAG, "PhoneAgentFloatingService 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand, dismissed=$dismissed")
        if (!dismissed && floatingView == null) {
            showFloatingWindow()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeFloatingWindow()
        super.onDestroy()
        Log.d(TAG, "PhoneAgentFloatingService 已销毁")
    }

    /** 创建前台通知渠道。 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone Agent",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Phone Agent 悬浮窗保活通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /** 构建前台 Service 保活通知（低优先级静默）。 */
    private fun buildForegroundNotification(): android.app.Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Phone Agent")
            .setContentText("正在执行手机操控...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSound(null)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 通过 WindowManager 显示 Compose 悬浮窗。
     * 使用原生 View.OnTouchListener 处理最小化态拖动（FLAG_NOT_FOCUSABLE 不会阻塞原生触摸）。
     */
    private fun showFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        lifecycleOwner = FloatingLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                // 加载当前主题配色（与 ReminderFloatingService 一致）
                val app = applicationContext as NionApp
                val themePalette = try {
                    val mode = app.core.getSetting("theme_mode") ?: "preset"
                    when (mode) {
                        "custom" -> {
                            val activeId = app.core.getSetting("active_custom_theme_id") ?: ""
                            if (activeId.isBlank()) {
                                NionColorTheme.CORAL.palette()
                            } else {
                                val themes = CustomThemeEntry.listFromJson(
                                    app.core.getSetting("custom_themes_list") ?: "[]"
                                )
                                val entry = themes.find { it.id == activeId }
                                entry?.palette ?: NionColorTheme.CORAL.palette()
                            }
                        }
                        else -> {
                            val name = app.core.getSetting("color_theme") ?: "CORAL"
                            NionColorTheme.entries.find { it.name == name }?.palette()
                                ?: NionColorTheme.CORAL.palette()
                        }
                    }
                } catch (_: Exception) {
                    NionColorTheme.CORAL.palette()
                }

                NionTheme(palette = themePalette) {
                    FloatingPhoneAgentPanel(
                        onMinimize = { toggleMinimize() },
                        onClose = {
                            dismissed = true
                            removeFloatingWindow()
                        },
                        onPauseResume = {
                            PhoneAgentLoop.togglePause()
                            PhoneAgentFloatingService.updatePaused(PhoneAgentLoop.paused)
                        },
                        onStop = {
                            PhoneAgentLoop.cancel()
                        },
                    )
                }
            }

            // 原生触摸监听 —— 绕过 FLAG_NOT_FOCUSABLE 对 Compose 手势的限制
            // 最小化态：处理拖动 + 点击展开；展开态：返回 false 让 Compose 正常处理按钮点击
            setOnTouchListener { _, event ->
                if (_stateFlow.value == FloatingState.MINIMIZED) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragLastRawX = event.rawX
                            dragLastRawY = event.rawY
                            isDragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - dragLastRawX
                            val dy = event.rawY - dragLastRawY
                            // 移动超过阈值才判定为拖动，避免手抖误触
                            if (abs(dx) > 8 || abs(dy) > 8 || isDragging) {
                                isDragging = true
                                moveWindowBy(dx.toInt(), dy.toInt())
                                dragLastRawX = event.rawX
                                dragLastRawY = event.rawY
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                // 短触摸 = 点击 → 展开
                                toggleMinimize()
                            }
                            isDragging = false
                            true
                        }
                        else -> true
                    }
                } else {
                    false // 展开态：不拦截触摸，Compose 处理 IconButton 等点击
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )

        val density = resources.displayMetrics.density
        val widthPx = resources.displayMetrics.widthPixels
        setWindowRefs(windowManager!!, composeView, params, density, widthPx)
        applyExpandedLayout()

        windowManager!!.addView(composeView, params)
        floatingView = composeView
    }

    /** 从 WindowManager 移除悬浮窗。 */
    private fun removeFloatingWindow() {
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "移除悬浮窗失败", e)
            }
        }
        floatingView = null
        try {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Exception) {}
    }
}

// ═══════════════════════════════════════════════════════════════
// Composable UI（package-level）
// ═══════════════════════════════════════════════════════════════

/** 悬浮窗主面板 —— 根据 FloatingState 切换展开/最小化 */
@Composable
private fun FloatingPhoneAgentPanel(
    onMinimize: () -> Unit = {},
    onClose: () -> Unit = {},
    onPauseResume: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    val floatingState by PhoneAgentFloatingService.stateFlow.collectAsState()
    val stepNumber by PhoneAgentFloatingService.stepFlow.collectAsState()
    val logs by PhoneAgentFloatingService.logFlow.collectAsState()
    val isPaused by PhoneAgentFloatingService.isPausedFlow.collectAsState()
    val currentResponse by PhoneAgentFloatingService.currentResponseFlow.collectAsState()

    // Crossfade 动画：展开态 ↔ 最小化态平滑过渡，掩盖布局切换的重组开销
    Crossfade(
        targetState = floatingState,
        animationSpec = tween(durationMillis = 250),
        label = "floatingStateCrossfade",
    ) { state ->
        when (state) {
            PhoneAgentFloatingService.FloatingState.EXPANDED -> {
                ExpandedPanel(
                    stepNumber = stepNumber,
                    logs = logs,
                    currentResponse = currentResponse,
                    isPaused = isPaused,
                    onMinimize = onMinimize,
                    onClose = onClose,
                    onPauseResume = onPauseResume,
                    onStop = onStop,
                )
            }
            PhoneAgentFloatingService.FloatingState.MINIMIZED -> {
                MinimizedBadge(
                    stepNumber = stepNumber,
                    onExpand = onMinimize,
                )
            }
        }
    }
}

/** 展开态面板 —— 标题栏 + 滚动日志 + 底部控制按钮 */
@Composable
private fun ExpandedPanel(
    stepNumber: Int,
    logs: List<PhoneAgentFloatingService.LogEntry>,
    currentResponse: String,
    isPaused: Boolean,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val logListState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 标题栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Phone Agent${if (stepNumber > 0) " · 第 $stepNumber 步" else ""}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMinimize, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "最小化", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }

            // ── 日志区 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)),
            ) {
                if (logs.isEmpty() && currentResponse.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "等待模型响应...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                } else {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(logs, key = { it.hashCode() }) { entry -> LogEntryRow(entry) }
                        if (currentResponse.isNotEmpty()) {
                            item(key = "streaming") { StreamingRow(text = currentResponse) }
                        }
                    }
                }
            }

            // ── 底部控制按钮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPauseResume,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (isPaused) "恢复" else "暂停", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (isPaused) "恢复" else "暂停", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Button(
                    onClick = onStop,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "停止", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

/** 单条日志行 */
@Composable
private fun LogEntryRow(entry: PhoneAgentFloatingService.LogEntry) {
    val prefix = when (entry.type) {
        "thinking" -> "[思考]"
        "action" -> "[动作]"
        "status" -> "[状态]"
        else -> ""
    }
    val textColor = when (entry.type) {
        "thinking" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        "action" -> when (entry.success) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.tertiary
        }
        "status" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }
    val bgColor = when (entry.type) {
        "action" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (entry.type == "action") Modifier.clip(RoundedCornerShape(6.dp)).background(bgColor).padding(horizontal = 6.dp, vertical = 3.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = prefix, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = entry.content, style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
    }
}

/** 流式接收行 —— 实时显示模型正在生成的响应 */
@Composable
private fun StreamingRow(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = "... ", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
    }
}

/** 最小化态 —— 圆球，只显示当前步数。拖动由原生 OnTouchListener 处理，这里只负责渲染。 */
@Composable
private fun MinimizedBadge(
    stepNumber: Int,
    onExpand: () -> Unit,
) {
    if (stepNumber == 0) return

    Box(
        modifier = Modifier.padding(0.dp), // 紧贴窗口边界，让原生触摸覆盖整个窗口
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$stepNumber",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

/** 自定义 LifecycleOwner —— 为悬浮窗 ComposeView 提供 Lifecycle 支持。 */
internal class FloatingLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performRestore(null)
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
