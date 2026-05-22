package com.echonion.nion.ui.focus

import android.app.Application
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echonion.nion.core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nion_core.NionCore
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MIN_MINUTES = 1
private const val MAX_MINUTES = 120

data class FocusTask(
    val id: String,
    val title: String,
)

class FocusSetupViewModel(private val core: NionCore) : ViewModel() {
    var tasks by mutableStateOf<List<FocusTask>>(emptyList())
        private set

    fun loadTasks() {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    core.getTasks()
                        .filter { it.status != "done" }
                        .map { FocusTask(it.id, it.title) }
                }
                tasks = loaded
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
 * 交互设计（方案 A —— 圆圈原地放大）：
 * - 正常态：260dp 圆形计时器，显示时间 + 进度环，下方有播放控制按钮
 * - 点击圆圈：圆圈从 260dp 放大到接近全屏宽度（~400dp），保持圆形
 *   放大过程中时间文字淡出，设置内容（表盘、任务列表、按钮）依次淡入
 * - 关闭设置：圆圈缩回 260dp，设置内容淡出，时间文字淡入
 *
 * @param onOpenCompanion 点击右上角伙伴图标的回调，用于打开右侧伙伴侧栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    onOpenCompanion: () -> Unit = {},
) {
    // focusMinutes: 用户设置的专注时长（分钟），默认 45
    var focusMinutes by remember { mutableIntStateOf(45) }
    // selectedTaskId / selectedTaskTitle: 当前关联的任务信息
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var selectedTaskTitle by remember { mutableStateOf<String?>(null) }
    // isRunning: 计时器是否正在运行
    var isRunning by remember { mutableStateOf(false) }
    // remainingSeconds: 剩余秒数
    var remainingSeconds by remember { mutableIntStateOf(focusMinutes * 60) }
    // completedSessions: 已完成的专注次数
    var completedSessions by remember { mutableIntStateOf(0) }
    // showSetup: 是否显示设置模式（圆圈放大态）
    var showSetup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val totalSeconds = focusMinutes * 60
    val progress = remainingSeconds.toFloat() / totalSeconds.toFloat()

    // animatedProgress: 进度环的动画进度，用 tween 做 600ms 平滑过渡
    val animatedProgress = remember { Animatable(1f) }
    LaunchedEffect(progress) {
        animatedProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(durationMillis = 600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }

    // 计时器倒计时协程：每秒 -1，到 0 自动停止并 +1 session
    LaunchedEffect(isRunning) {
        while (isRunning) {
            if (remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
            } else {
                isRunning = false
                completedSessions++
                break
            }
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    val ringColor = MaterialTheme.colorScheme.primary

    // pulseAlpha: 计时器运行时的脉冲光晕透明度，用无限循环动画实现呼吸效果
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            pulseAlpha.animateTo(
                targetValue = 0.35f,
                animationSpec = tween(800, easing = LinearEasing),
            )
            launch {
                pulseAlpha.animateTo(
                    targetValue = 0.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                )
            }
        } else {
            pulseAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(600, easing = LinearEasing),
            )
        }
    }

    // playScale: 播放/暂停按钮的缩放动画，按下时缩小再弹回
    val playScale = remember { Animatable(1f) }
    LaunchedEffect(isRunning) {
        playScale.animateTo(
            targetValue = 0.85f,
            animationSpec = tween(100),
        )
        playScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        )
    }

    // circleSize: 圆圈当前尺寸，260dp ↔ 400dp，用 spring 物理动画让膨胀/收缩有弹性
    val circleSize by animateDpAsState(
        targetValue = if (showSetup) 400.dp else 260.dp,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "circleSize",
    )

    // contentAlpha: 设置内容的透明度，展开时淡入，收回时淡出
    val contentAlpha by animateFloatAsState(
        targetValue = if (showSetup) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (showSetup) 400 else 250,
            delayMillis = if (showSetup) 150 else 0,
        ),
        label = "contentAlpha",
    )

    // timerAlpha: 计时器内容的透明度，和 contentAlpha 相反
    val timerAlpha by animateFloatAsState(
        targetValue = if (showSetup) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (showSetup) 150 else 300,
            delayMillis = if (showSetup) 0 else 100,
        ),
        label = "timerAlpha",
    )

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
                            if (isRunning) "计时进行中..." else "点击计时器设置",
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

            // 圆圈容器：一个 Surface，始终保持 CircleShape，尺寸动画从 260dp → 400dp
            Surface(
                modifier = Modifier.size(circleSize),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = if (showSetup) 8.dp else 0.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            // 点击圆圈：未运行时切换设置模式，设置模式时关闭
                            onClick = {
                                if (!isRunning) showSetup = !showSetup
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // ---- 计时器内容层（正常态显示，设置态淡出） ----
                    if (timerAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = timerAlpha },
                            contentAlignment = Alignment.Center,
                        ) {
                            // 进度环 Canvas
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 10.dp.toPx()
                                val diameter = size.minDimension - strokeWidth * 2
                                val topLeft = Offset(
                                    (size.width - diameter) / 2f,
                                    (size.height - diameter) / 2f,
                                )
                                val arcSize = Size(diameter, diameter)

                                // 背景轨道环
                                drawArc(
                                    color = ringColor.copy(alpha = 0.12f),
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                                )

                                // 运行时的脉冲光晕环
                                if (pulseAlpha.value > 0.01f) {
                                    drawArc(
                                        color = ringColor.copy(alpha = pulseAlpha.value),
                                        startAngle = -90f,
                                        sweepAngle = 360f * animatedProgress.value,
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth + 6.dp.toPx(), cap = StrokeCap.Round),
                                    )
                                }

                                // 进度环
                                drawArc(
                                    color = ringColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f * animatedProgress.value,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                                )
                            }

                            // 时间文本 + 任务/分钟标签
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 32.dp),
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
                    }

                    // ---- 设置内容层（设置态显示，正常态淡出） ----
                    if (contentAlpha > 0.01f) {
                        FocusSetupContent(
                            currentMinutes = focusMinutes,
                            currentTaskId = selectedTaskId,
                            alpha = contentAlpha,
                            onConfirm = { minutes, taskId, taskTitle ->
                                focusMinutes = minutes
                                selectedTaskId = taskId
                                selectedTaskTitle = taskTitle
                                remainingSeconds = minutes * 60
                                scope.launch { animatedProgress.snapTo(1f) }
                                showSetup = false
                            },
                        )
                    }
                }
            }

            // 播放控制行：仅在非设置态显示
            if (!showSetup) {
                Spacer(modifier = Modifier.height(40.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    Surface(
                        shape = CircleShape,
                        color = ringColor,
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
                    Surface(
                        shape = CircleShape,
                        color = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
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
                                tint = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 设置面板内容 —— 嵌在放大后的圆圈内部。
 * 包含：表盘时间选择器、任务列表、开始按钮。
 * 整体用 graphicsLayer alpha 控制淡入淡出。
 *
 * @param currentMinutes 打开时当前的专注时长，用作表盘初始值
 * @param currentTaskId 打开时当前关联的任务 ID
 * @param alpha 内容的透明度，由外层动画控制
 * @param onConfirm 点击"开始专注"时触发，回调 (分钟, 任务ID, 任务标题)
 */
@Composable
private fun FocusSetupContent(
    currentMinutes: Int,
    currentTaskId: String?,
    alpha: Float,
    onConfirm: (minutes: Int, taskId: String?, taskTitle: String?) -> Unit,
) {
    val vm = focusSetupViewModel()
    LaunchedEffect(Unit) { vm.loadTasks() }

    // 面板内部编辑状态
    var selectedMinutes by remember { mutableIntStateOf(currentMinutes) }
    var selectedTaskId by remember { mutableStateOf(currentTaskId) }
    val selectedTaskTitle = vm.tasks.find { it.id == selectedTaskId }?.title

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标题
        Text(
            "专注设置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 缩小版表盘（圆内空间有限，用 150dp）
        DialTimePicker(
            minutes = selectedMinutes,
            onMinutesChange = { selectedMinutes = it },
            modifier = Modifier.size(150.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 关联任务（紧凑列表）
        if (vm.tasks.isEmpty()) {
            Text(
                "暂无待办任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    TaskOptionRow(
                        title = "不关联",
                        selected = selectedTaskId == null,
                        onClick = { selectedTaskId = null },
                    )
                }
                items(vm.tasks, key = { it.id }) { task ->
                    TaskOptionRow(
                        title = task.title,
                        selected = selectedTaskId == task.id,
                        onClick = { selectedTaskId = task.id },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 开始按钮
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .clickable {
                    onConfirm(selectedMinutes, selectedTaskId, selectedTaskTitle)
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(
                modifier = Modifier.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "开始",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/**
 * 圆弧表盘时间选择器 —— 拖拽/点击圆弧设置专注时长（1-120 分钟）。
 *
 * @param minutes 当前选中的分钟数
 * @param onMinutesChange 分钟数变化时的回调
 * @param modifier 外部 modifier
 */
@Composable
private fun DialTimePicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = primaryColor.copy(alpha = 0.12f)
    val tickColor = primaryColor.copy(alpha = 0.25f)
    val majorTickColor = primaryColor.copy(alpha = 0.6f)
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }

    val fraction = (minutes - MIN_MINUTES).toFloat() / (MAX_MINUTES - MIN_MINUTES)
    val animatedFraction = remember { Animatable(fraction) }
    LaunchedEffect(fraction) {
        animatedFraction.animateTo(
            fraction,
            animationSpec = if (isDragging) tween(80) else tween(300),
        )
    }

    val dialSize = 220.dp
    val strokeWidth = 8.dp
    val tickRadius = 3.dp
    val majorTickRadius = 4.5.dp
    val knobRadius = 14.dp
    val tickCount = 60

    Box(
        modifier = modifier.size(dialSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(dialSize) {
                    val centerOffset = Offset(size.width / 2f, size.height / 2f)
                    val radiusPx = with(density) { (dialSize / 2 - knobRadius - 4.dp).toPx() }

                    fun angleToFraction(angle: Float): Float {
                        var normalized = angle + 90f
                        if (normalized < 0) normalized += 360f
                        val f = normalized / 360f
                        return f.coerceIn(0f, 1f)
                    }

                    fun fractionToMinutes(f: Float): Int {
                        return (MIN_MINUTES + f * (MAX_MINUTES - MIN_MINUTES)).roundToInt()
                            .coerceIn(MIN_MINUTES, MAX_MINUTES)
                    }

                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val dx = offset.x - centerOffset.x
                            val dy = offset.y - centerOffset.y
                            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            onMinutesChange(fractionToMinutes(angleToFraction(angle)))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val dx = change.position.x - centerOffset.x
                            val dy = change.position.y - centerOffset.y
                            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            onMinutesChange(fractionToMinutes(angleToFraction(angle)))
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                    )
                }
                .pointerInput(dialSize) {
                    detectTapGestures { offset ->
                        val centerOffset = Offset(size.width / 2f, size.height / 2f)
                        val dx = offset.x - centerOffset.x
                        val dy = offset.y - centerOffset.y
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        val outerRing = with(density) { (dialSize / 2).toPx() }
                        if (dist < outerRing) {
                            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            var normalized = angle + 90f
                            if (normalized < 0) normalized += 360f
                            val f = (normalized / 360f).coerceIn(0f, 1f)
                            val m = (MIN_MINUTES + f * (MAX_MINUTES - MIN_MINUTES)).roundToInt()
                                .coerceIn(MIN_MINUTES, MAX_MINUTES)
                            onMinutesChange(m)
                        }
                    }
                }
        ) {
            val swPx = strokeWidth.toPx()
            val diameter = size.minDimension - swPx * 2
            val topLeft = Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val arcRadius = diameter / 2f

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = swPx, cap = StrokeCap.Round),
            )

            val sweep = 360f * animatedFraction.value
            if (sweep > 0.5f) {
                drawArc(
                    color = primaryColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = swPx, cap = StrokeCap.Round),
                )
            }

            val innerTickRadius = arcRadius - swPx / 2 - tickRadius.toPx() - 4.dp.toPx()
            val outerTickRadius = arcRadius - swPx / 2 - 2.dp.toPx()
            for (i in 0 until tickCount) {
                val angleRad = Math.toRadians((-90.0 + 360.0 * i / tickCount))
                val isMajor = i % (tickCount / 12) == 0
                val r = if (isMajor) outerTickRadius else innerTickRadius
                val x = cx + r * cos(angleRad).toFloat()
                val y = cy + r * sin(angleRad).toFloat()
                drawCircle(
                    color = if (isMajor) majorTickColor else tickColor,
                    radius = if (isMajor) majorTickRadius.toPx() else tickRadius.toPx(),
                    center = Offset(x, y),
                )
            }

            val knobAngleRad = Math.toRadians((-90.0 + 360.0 * animatedFraction.value))
            val knobR = arcRadius
            val knobX = cx + knobR * cos(knobAngleRad).toFloat()
            val knobY = cy + knobR * sin(knobAngleRad).toFloat()

            drawCircle(
                color = primaryColor.copy(alpha = 0.3f),
                radius = (knobRadius + 4.dp).toPx(),
                center = Offset(knobX, knobY),
            )
            drawCircle(
                color = primaryColor,
                radius = knobRadius.toPx(),
                center = Offset(knobX, knobY),
            )
            drawCircle(
                color = Color.White,
                radius = knobRadius.toPx() * 0.35f,
                center = Offset(knobX, knobY),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$minutes",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = primaryColor,
            )
            Text(
                "分钟",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 任务选项行 —— 用于专注设置面板中的任务列表。
 * 单选模式，选中时高亮背景 + 显示勾选图标。
 *
 * @param title 任务标题
 * @param selected 是否被选中
 * @param onClick 点击回调，触发选中切换
 */
@Composable
private fun TaskOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
