package com.echonion.nion.ui.focus

import android.app.Application
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    onOpenCompanion: () -> Unit = {},
) {
    var focusMinutes by remember { mutableIntStateOf(45) }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var selectedTaskTitle by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(focusMinutes * 60) }
    var completedSessions by remember { mutableIntStateOf(0) }
    var showSetup by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val totalSeconds = focusMinutes * 60
    val progress = remainingSeconds.toFloat() / totalSeconds.toFloat()

    val animatedProgress = remember { Animatable(1f) }
    LaunchedEffect(progress) {
        animatedProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(durationMillis = 600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }

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
            Spacer(modifier = Modifier.height(64.dp))

            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (!isRunning) showSetup = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 10.dp.toPx()
                    val diameter = size.minDimension - strokeWidth * 2
                    val topLeft = Offset(
                        (size.width - diameter) / 2f,
                        (size.height - diameter) / 2f,
                    )
                    val arcSize = Size(diameter, diameter)

                    drawArc(
                        color = ringColor.copy(alpha = 0.12f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )

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

    if (showSetup) {
        FocusSetupSheet(
            currentMinutes = focusMinutes,
            currentTaskId = selectedTaskId,
            onDismiss = { showSetup = false },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusSetupSheet(
    currentMinutes: Int,
    currentTaskId: String?,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, taskId: String?, taskTitle: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val vm = focusSetupViewModel()

    LaunchedEffect(Unit) { vm.loadTasks() }

    var selectedMinutes by remember { mutableIntStateOf(currentMinutes) }
    var selectedTaskId by remember { mutableStateOf(currentTaskId) }
    val selectedTaskTitle = vm.tasks.find { it.id == selectedTaskId }?.title

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "专注设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start),
            )

            Spacer(modifier = Modifier.height(24.dp))

            DialTimePicker(
                minutes = selectedMinutes,
                onMinutesChange = { selectedMinutes = it },
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "关联任务",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start),
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (vm.tasks.isEmpty()) {
                Text(
                    "暂无待办任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        TaskOptionRow(
                            title = "不关联任务",
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

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onConfirm(selectedMinutes, selectedTaskId, selectedTaskTitle)
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "开始专注",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

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
