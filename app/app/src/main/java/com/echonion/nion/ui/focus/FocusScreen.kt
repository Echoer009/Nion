package com.echonion.nion.ui.focus

import android.app.Application
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echonion.nion.core
import com.echonion.nion.ui.theme.NionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nion_core.NionCore
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class FocusTask(
    val id: String,
    val title: String,
    /** 该任务累计专注秒数 */
    val focusSeconds: Long,
)

class FocusSetupViewModel(private val core: NionCore) : ViewModel() {
    var tasks by mutableStateOf<List<FocusTask>>(emptyList())
        private set

    /** 从数据库加载未完成的任务列表 */
    fun loadTasks() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    core.getTasks()
                        .filter { it.status != "done" }
                        .map { FocusTask(it.id, it.title, it.focusSeconds) }
                }
                // 按累计专注时长降序排列，时长越高越靠前
                tasks = loaded.sortedByDescending { it.focusSeconds }
            } catch (_: Exception) {}
        }
    }
}

@Composable
private fun focusSetupViewModel(): FocusSetupViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return FocusSetupViewModel(app.core()) as T
            }
        }
    )
}

/**
 * 专注计时器主界面。
 *
 * 交互设计：
 * - 正常态：300dp 圆形计时器，60 根圆角长条刻度（5 的倍数更长），中间显示时间
 * - 点击外圈刻度：直接设置专注时长（根据刻度位置计算分钟数）
 * - 点击中心区域：深橘色背景从时钟中心向外扩展为全屏，
 *   内部显示任务卡片列表（累计专注越久颜色越深），
 *   选中任务后背景从外围收缩回时钟中心
 *
 * @param onOpenCompanion 点击右上角伙伴图标的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    onOpenCompanion: () -> Unit = {},
) {
    // focusMinutes: 用户设置的专注时长（分钟），默认 25
    var focusMinutes by remember { mutableIntStateOf(25) }
    // selectedTaskId / selectedTaskTitle: 当前关联的任务信息
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var selectedTaskTitle by remember { mutableStateOf<String?>(null) }
    // isRunning: 计时器是否正在运行
    var isRunning by remember { mutableStateOf(false) }
    // remainingSeconds: 剩余秒数
    var remainingSeconds by remember { mutableIntStateOf(focusMinutes * 60) }
    // completedSessions: 已完成的专注次数
    var completedSessions by remember { mutableIntStateOf(0) }
    // showTaskPanel: 是否显示任务选择面板
    var showTaskPanel by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 提前获取 core 实例，供倒计时协程使用
    val context = LocalContext.current
    val core = remember { (context.applicationContext as Application).core() }

    val totalSeconds = focusMinutes * 60
    // progress: 剩余时间占总时间的比例
    val progress = remainingSeconds.toFloat() / totalSeconds.toFloat()

    // animatedProgress: 进度的动画值，平滑过渡
    val animatedProgress = remember { Animatable(1f) }
    LaunchedEffect(progress) {
        animatedProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(
                durationMillis = 600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }

    // 计时器倒计时协程：每秒 -1，到 0 自动停止并 +1 session，同时累加专注时长
    LaunchedEffect(isRunning) {
        while (isRunning) {
            if (remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
            } else {
                isRunning = false
                completedSessions++
                // 专注结束，累加到关联任务的 focus_seconds
                if (selectedTaskId != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            core.addFocusTime(selectedTaskId!!, totalSeconds.toLong())
                        } catch (_: Exception) {}
                    }
                }
                break
            }
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    // dotFraction: 指示圆点在圆周上的位置比例（0=12点, focusMinutes/120=设定时长对应的角度）
    val dotFraction = if (isRunning || remainingSeconds < focusMinutes * 60) {
        // 运行中或暂停：圆点跟随剩余进度
        (focusMinutes.toFloat() / 120f) * (remainingSeconds.toFloat() / (focusMinutes * 60f))
    } else {
        // 初始状态：圆点在设定时长对应位置
        focusMinutes.toFloat() / 120f
    }
    // dotFractionAnim: 指示圆点的平滑动画值，避免圆点瞬时跳动到新位置
    val dotFractionAnim = remember { Animatable(dotFraction) }
    LaunchedEffect(dotFraction) {
        dotFractionAnim.animateTo(
            targetValue = dotFraction,
            animationSpec = tween(
                durationMillis = 600,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    // playScale: 播放按钮缩放动画
    val playScale = remember { Animatable(1f) }
    LaunchedEffect(isRunning) {
        playScale.animateTo(0.85f, tween(100))
        playScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f))
    }

    // expandFraction: 任务面板的展开比例，0=未展开（时钟大小），1=完全展开（接近全屏）
    val expandFraction by animateFloatAsState(
        targetValue = if (showTaskPanel) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "expandFraction",
    )

    // panelAlpha: 任务面板内容的透明度
    val panelAlpha by animateFloatAsState(
        targetValue = if (showTaskPanel) 1f else 0f,
        animationSpec = tween(durationMillis = 300, delayMillis = if (showTaskPanel) 150 else 0),
        label = "panelAlpha",
    )

    val timerSize = 300.dp

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        // 主内容层
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "专注",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (isRunning) "计时进行中..." else "点击/拖拽刻度设置时长",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenCompanion) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = "伙伴",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // ---- 计时器时钟 ----
                // 300dp 圆形，60 根圆角长条刻度 + 中间时间文字
                // 点击外圈刻度 → 吸附到最近的 5 分钟刻度；拖拽 → 实时调整时长
                // 点击中心区域 → 展开任务面板
                Box(
                    modifier = Modifier
                        .size(timerSize)
                        .pointerInput(isRunning) {
                            if (isRunning) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val downPos = down.position
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val outerR = kotlin.math.min(size.width, size.height).toFloat() / 2f
                                // 刻度判断区域：外圈 50% 半径以上为刻度/拖拽区
                                val tickZoneStart = outerR * 0.5f

                                var dragged = false

                                /** 根据触点位置计算对应的分钟数（1~120，未吸附） */
                                fun minutesFromPosition(pos: Offset): Int {
                                    val dx = pos.x - center.x
                                    val dy = pos.y - center.y
                                    val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    var normalized = angle + 90f
                                    if (normalized < 0) normalized += 360f
                                    val fraction = (normalized / 360f).coerceIn(0f, 1f)
                                    return (1 + fraction * 119).roundToInt().coerceIn(1, 120)
                                }

                                // 等待后续事件，判断是点击还是拖拽
                                var lastPos = downPos
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    // 通过比较当前与上一次触点位置来判断是否发生拖拽
                                    if (change.position != lastPos) {
                                        change.consume()
                                        dragged = true
                                        lastPos = change.position
                                        val newMinutes = minutesFromPosition(change.position)
                                        focusMinutes = newMinutes
                                        remainingSeconds = newMinutes * 60
                                    }
                                    if (!change.pressed) {
                                        // 手指抬起
                                        change.consume()
                                        break
                                    }
                                }

                                if (dragged) {
                                    // 拖拽结束：吸附到最近的 5 分钟整数倍
                                    val snapped = ((focusMinutes + 2) / 5) * 5
                                    val final = snapped.coerceIn(5, 120)
                                    focusMinutes = final
                                    remainingSeconds = final * 60
                                    scope.launch { animatedProgress.snapTo(1f) }
                                } else {
                                    // 点击：根据触点位置判断操作
                                    val dx = downPos.x - center.x
                                    val dy = downPos.y - center.y
                                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (dist >= tickZoneStart) {
                                        // 点击刻度区域 → 吸附到最近的 5 分钟刻度
                                        val rawMinutes = minutesFromPosition(downPos)
                                        val snapped = ((rawMinutes + 2) / 5) * 5
                                        val newMinutes = snapped.coerceIn(5, 120)
                                        focusMinutes = newMinutes
                                        remainingSeconds = newMinutes * 60
                                        scope.launch { animatedProgress.snapTo(1f) }
                                    } else {
                                        // 点击中心区域 → 展开任务面板
                                        showTaskPanel = true
                                    }
                                }
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                    // 圆角长条刻度 Canvas
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val outerRadius = size.minDimension / 2f - 8.dp.toPx()
                        // 根据进度计算已点亮的刻度数
                        val litCount = (animatedProgress.value * 60).roundToInt()

                        for (i in 0 until 60) {
                            // 从12点位置开始顺时针
                            val angleDeg = -90.0 + 360.0 * i / 60
                            val angleRad = Math.toRadians(angleDeg)
                            val isLit = i < litCount
                            // 5 的倍数 = 时刻刻度，更长更宽
                            val isMajor = i % 5 == 0

                            // 刻度长度：时刻刻度更长
                            val tickLength = if (isMajor) 22.dp.toPx() else 12.dp.toPx()
                            // 刻度宽度：时刻刻度更宽
                            val tickWidth = if (isMajor) 3.5f.dp.toPx() else 2f.dp.toPx()

                            // 用旋转画布的方式绘制圆角矩形刻度
                            // 旋转到对应角度后，在上方绘制一个水平圆角矩形
                            val tickColor = if (isLit) primaryColor
                                else primaryColor.copy(alpha = if (isMajor) 0.3f else 0.12f)
                            rotate(angleDeg.toFloat(), pivot = Offset(cx, cy)) {
                                drawRoundRect(
                                    color = tickColor,
                                    topLeft = Offset(
                                        cx - tickWidth / 2f,
                                        cy - outerRadius,
                                    ),
                                    size = Size(tickWidth, tickLength),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(tickWidth / 2f),
                                )
                            }
                        }

                        // 指示圆点（在刻度内侧）
                        // 使用动画后的位置值以产生平滑过渡效果
                        val dotAngle = -90.0 + 360.0 * dotFractionAnim.value
                        val dotAngleRad = Math.toRadians(dotAngle)
                        // 圆点轨道在刻度内侧，离刻度有间距
                        val dotOrbitRadius = outerRadius - 32.dp.toPx()
                        val dotX = cx + dotOrbitRadius * cos(dotAngleRad).toFloat()
                        val dotY = cy + dotOrbitRadius * sin(dotAngleRad).toFloat()
                        // 用更深的主题色（onPrimary 的暗色版）
                        drawCircle(
                            color = onSurfaceColor,
                            radius = 5.dp.toPx(),
                            center = Offset(dotX, dotY),
                        )
                    }

                    // 中间内容：时间文字 + 任务名
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    ) {
                        Text(
                            timeText,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.Light,
                                letterSpacing = 2.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (selectedTaskTitle != null) {
                            Text(
                                selectedTaskTitle!!,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                "${focusMinutes} 分钟",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // 播放控制行
                Spacer(modifier = Modifier.height(40.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 重置按钮
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(52.dp),
                    ) {
                        IconButton(
                            onClick = {
                                remainingSeconds = focusMinutes * 60
                                isRunning = false
                                scope.launch { animatedProgress.snapTo(1f) }
                            },
                            modifier = Modifier.size(52.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "重置",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    // 播放/暂停按钮
                    Surface(
                        shape = CircleShape,
                        color = primaryColor,
                        modifier = Modifier
                            .size(72.dp)
                            .scale(playScale.value)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { isRunning = !isRunning },
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isRunning) "暂停" else "开始",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    // 提前结束按钮
                    Surface(
                        shape = CircleShape,
                        color = if (isRunning) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(52.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (isRunning) completedSessions++
                                remainingSeconds = focusMinutes * 60
                                isRunning = false
                                scope.launch { animatedProgress.snapTo(1f) }
                            },
                            modifier = Modifier.size(52.dp),
                            enabled = isRunning || remainingSeconds < focusMinutes * 60,
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "提前结束",
                                tint = if (isRunning) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---- 任务选择面板（深橘色背景从时钟中心扩展/收缩） ----
        if (expandFraction > 0.01f) {
            TaskPanelOverlay(
                expandFraction = expandFraction,
                panelAlpha = panelAlpha,
                selectedTaskId = selectedTaskId,
                onSelectTask = { taskId, taskTitle ->
                    selectedTaskId = taskId
                    selectedTaskTitle = taskTitle
                    showTaskPanel = false
                },
                onDismiss = {
                    showTaskPanel = false
                },
            )
        }
    }
}

/**
 * 任务选择面板覆盖层 —— 深橘色背景从时钟中心向外扩展，内部显示任务卡片列表。
 *
 * 动画原理：
 * - expandFraction 从 0 → 1：一个圆形遮罩从时钟大小扩展到覆盖全屏，
 *   视觉上像是从时钟中心「长出来」
 * - expandFraction 从 1 → 0：反向收缩回时钟中心
 * - 深橘色背景随扩展比例渐变出现
 *
 * @param expandFraction 展开比例，0=时钟大小，1=全屏
 * @param panelAlpha 任务列表内容的透明度
 * @param selectedTaskId 当前已关联的任务 ID
 * @param onSelectTask 选择任务后的回调，传入 (任务ID, 任务标题)，null=不关联
 * @param onDismiss 点击空白区域关闭面板的回调
 */
@Composable
private fun TaskPanelOverlay(
    expandFraction: Float,
    panelAlpha: Float,
    selectedTaskId: String?,
    onSelectTask: (taskId: String?, taskTitle: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val vm = focusSetupViewModel()
    LaunchedEffect(Unit) { vm.loadTasks() }

    val primaryColor = MaterialTheme.colorScheme.primary
    val creamWhite = NionColors.Warm50
    val panelBg = creamWhite

    // 计算所有任务中的最大专注秒数，用于归一化颜色深度
    val maxFocusSeconds = vm.tasks.maxOfOrNull { it.focusSeconds }?.coerceAtLeast(1L) ?: 1L

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // 扩展的深橘色圆形背景
        // 初始大小 = 时钟 300dp，最终覆盖全屏
        // 使用 graphicsLayer 的 scaleX/scaleY 配合 clip 实现圆形扩展效果
        val initialSize = 300.dp
        Box(
            modifier = Modifier
                .size(initialSize)
                .graphicsLayer {
                    // 从 1x 扩展到 5x（覆盖全屏）
                    val s = 1f + expandFraction * 4f
                    scaleX = s
                    scaleY = s
                    // 透明度随扩展渐变
                    alpha = expandFraction
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .clip(CircleShape)
                .background(panelBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        // 任务列表内容（在扩展背景之上）
        if (panelAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = panelAlpha }
                    .padding(horizontal = 32.dp)
                    .padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 面板标题
                Text(
                    "选择专注任务",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "不选择则以空任务专注",
                    style = MaterialTheme.typography.bodySmall,
                    color = primaryColor.copy(alpha = 0.6f),
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (vm.tasks.isEmpty()) {
                    Text(
                        "暂无待办任务",
                        style = MaterialTheme.typography.bodyLarge,
                        color = primaryColor.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 不关联任务选项
                        item {
                            TaskCardRow(
                                title = "不关联任务",
                                subtitle = "空任务专注",
                                colorFraction = 0f,
                                selected = selectedTaskId == null,
                                onClick = { onSelectTask(null, null) },
                            )
                        }
                        items(vm.tasks, key = { it.id }) { task ->
                            // colorFraction: 0~1，累计时长越高值越大，颜色越深
                            val colorFraction = if (maxFocusSeconds > 0) {
                                task.focusSeconds.toFloat() / maxFocusSeconds.toFloat()
                            } else 0f
                            TaskCardRow(
                                title = task.title,
                                subtitle = formatFocusTime(task.focusSeconds),
                                colorFraction = colorFraction,
                                selected = selectedTaskId == task.id,
                                onClick = { onSelectTask(task.id, task.title) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 格式化累计专注时间（秒 → 可读字符串）。
 *
 * @param seconds 累计秒数
 * @return 格式化后的字符串，如 "2小时30分钟"、"45分钟"、"从未专注"
 */
private fun formatFocusTime(seconds: Long): String {
    if (seconds <= 0) return "从未专注"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
        hours > 0 -> "${hours}小时"
        minutes > 0 -> "${minutes}分钟"
        else -> "不到1分钟"
    }
}

/**
 * 任务卡片行 —— 纯白半透明卡片在米白背景上，累计专注越久越透明。
 *
 * 选中态使用主题橙半透明背景 + 主题橙边框 + 主题橙文字。
 *
 * @param title 任务标题
 * @param subtitle 副标题（如累计时长）
 * @param colorFraction 颜色深度比例 0~1，0=最实（纯白），1=最透明
 * @param selected 是否被选中
 * @param onClick 点击回调
 */
@Composable
private fun TaskCardRow(
    title: String,
    subtitle: String,
    colorFraction: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val cardWhite = Color.White
    // 卡片背景：纯白 → 透明（融入米白色背景），专注越久越透明
    val cardColor = lerpColor(
        cardWhite,                          // colorFraction=0: 纯白卡片
        cardWhite.copy(alpha = 0f),         // colorFraction=1: 完全透明（透出米白底）
        colorFraction,
    )
    // 选中状态：仅描边 + 文字变色 + 打勾，不添加背景色
    val borderColor = if (selected) primaryColor else Color.Transparent

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        border = if (selected) BorderStroke(2.dp, borderColor) else null,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) primaryColor else Color(0xFF322E2A),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) primaryColor.copy(alpha = 0.7f)
                        else Color(0xFF322E2A).copy(alpha = 0.5f),
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * 在两个颜色之间线性插值。
 *
 * @param start 起始颜色
 * @param end 结束颜色
 * @param fraction 插值比例 0~1
 * @return 插值后的颜色
 */
private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = start.alpha + (end.alpha - start.alpha) * f,
    )
}
