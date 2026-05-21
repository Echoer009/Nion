package com.echonion.nion.ui.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class FocusMode(val label: String, val minutes: Int) {
    FOCUS("专注", 25),
    SHORT_BREAK("短休", 5),
    LONG_BREAK("长休", 15),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    onOpenCompanion: () -> Unit = {},
) {
    var mode by remember { mutableStateOf(FocusMode.FOCUS) }
    var isRunning by remember { mutableStateOf(false) }
    var remainingSeconds by remember { mutableIntStateOf(mode.minutes * 60) }
    var completedSessions by remember { mutableIntStateOf(0) }

    val totalSeconds = mode.minutes * 60
    val progress = remainingSeconds.toFloat() / totalSeconds.toFloat()

    val animatedProgress = remember { Animatable(1f) }
    LaunchedEffect(progress) {
        animatedProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(durationMillis = 600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        )
    }

    LaunchedEffect(mode) {
        remainingSeconds = mode.minutes * 60
        isRunning = false
        animatedProgress.snapTo(1f)
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            if (remainingSeconds > 0) {
                delay(1000)
                remainingSeconds--
            } else {
                isRunning = false
                if (mode == FocusMode.FOCUS) completedSessions++
                break
            }
        }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val timeText = String.format("%02d:%02d", minutes, seconds)

    val ringColor = when (mode) {
        FocusMode.FOCUS -> MaterialTheme.colorScheme.primary
        FocusMode.SHORT_BREAK -> MaterialTheme.colorScheme.tertiary
        FocusMode.LONG_BREAK -> MaterialTheme.colorScheme.secondary
    }

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
                            if (isRunning) "计时进行中..." else "准备开始",
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
            Spacer(modifier = Modifier.height(16.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                FocusMode.entries.forEachIndexed { index, fm ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = FocusMode.entries.size),
                        onClick = { mode = fm },
                        selected = mode == fm,
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = ringColor.copy(alpha = 0.12f),
                            activeContentColor = ringColor,
                        ),
                    ) {
                        Text(fm.label, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            Box(
                modifier = Modifier.size(260.dp),
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

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        timeText,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            remainingSeconds = mode.minutes * 60
                            isRunning = false
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
                Box(modifier = Modifier.size(52.dp))
            }

            Spacer(modifier = Modifier.height(40.dp))

            if (mode == FocusMode.FOCUS) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        StatItem("$completedSessions", "已完成", MaterialTheme.colorScheme.primary)
                        StatItem("${completedSessions * FocusMode.FOCUS.minutes}", "分钟", MaterialTheme.colorScheme.primary)
                        StatItem("${completedSessions / 4}", "轮次", MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
