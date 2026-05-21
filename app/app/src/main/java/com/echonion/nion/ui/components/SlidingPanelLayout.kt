package com.echonion.nion.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class DualPanelState {
    var isOpen by mutableStateOf(false)
        internal set

    internal val offset = Animatable(0f)
    internal var scope: CoroutineScope? = null
    internal var leftWidthPx: Float = 0f
    internal var rightWidthPx: Float = 0f

    val isLeftOpen: Boolean get() = offset.value > 0
    val isRightOpen: Boolean get() = offset.value < 0

    fun openLeft() {
        val s = scope ?: return
        if (offset.value != 0f) return
        isOpen = true
        s.launch { offset.animateTo(leftWidthPx, tween(250)) }
    }

    fun openRight() {
        val s = scope ?: return
        if (offset.value != 0f) return
        isOpen = true
        s.launch { offset.animateTo(-rightWidthPx, tween(250)) }
    }

    fun closeLeft() {
        val s = scope ?: return
        s.launch {
            offset.animateTo(0f, tween(250))
            isOpen = false
        }
    }

    fun closeRight() {
        val s = scope ?: return
        s.launch {
            offset.animateTo(0f, tween(250))
            isOpen = false
        }
    }

    fun toggleLeft() {
        if (isLeftOpen) closeLeft() else openLeft()
    }

    fun toggleRight() {
        if (isRightOpen) closeRight() else openRight()
    }

    internal fun handleDrag(delta: Float) {
        val s = scope ?: return
        val current = offset.value
        val newVal = when {
            current > 0f -> (current + delta).coerceIn(0f, leftWidthPx)
            current < 0f -> (current + delta).coerceIn(-rightWidthPx, 0f)
            delta > 0f -> delta.coerceIn(0f, leftWidthPx)
            delta < 0f -> delta.coerceIn(-rightWidthPx, 0f)
            else -> 0f
        }
        s.launch { offset.snapTo(newVal) }
    }

    internal suspend fun settle() {
        val current = offset.value
        val threshold = 0.50f
        when {
            current > 0f -> {
                val shouldClose = current < leftWidthPx * threshold
                offset.animateTo(if (shouldClose) 0f else leftWidthPx, tween(250))
                isOpen = !shouldClose
            }
            current < 0f -> {
                val shouldClose = -current < rightWidthPx * threshold
                offset.animateTo(if (shouldClose) 0f else -rightWidthPx, tween(250))
                isOpen = !shouldClose
            }
        }
    }
}

@Composable
fun rememberDualPanelState(): DualPanelState {
    return remember { DualPanelState() }
}

@Composable
fun DualPanelLayout(
    leftPanelWidth: Dp = 260.dp,
    rightPanelWidth: Dp = 280.dp,
    panelPadding: Dp = 8.dp,
    state: DualPanelState,
    enableLeftSwipe: Boolean = true,
    enableRightSwipe: Boolean = true,
    modifier: Modifier = Modifier,
    leftPanel: @Composable (
        onDrag: (Float) -> Unit,
        onDragStopped: () -> Unit,
        modifier: Modifier,
    ) -> Unit,
    rightPanel: @Composable (
        onDrag: (Float) -> Unit,
        onDragStopped: () -> Unit,
        modifier: Modifier,
    ) -> Unit,
    mainContent: @Composable () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val leftPx = with(LocalDensity.current) { leftPanelWidth.toPx() }
    val rightPx = with(LocalDensity.current) { rightPanelWidth.toPx() }
    val cachedShadowElevation = with(LocalDensity.current) { 16.dp.toPx() }
    val cachedShape = remember { RoundedCornerShape(24.dp) }
    val noShape = remember { RoundedCornerShape(0.dp) }

    SideEffect {
        state.scope = coroutineScope
        state.leftWidthPx = leftPx
        state.rightWidthPx = rightPx
    }

    Box(modifier = modifier.fillMaxSize()) {
        val currentOffset = state.offset.value

        if (currentOffset >= 0f) {
            leftPanel(
                { delta -> if (state.offset.value > 0f) state.handleDrag(delta) },
                { coroutineScope.launch { state.settle() } },
                Modifier
                    .width(leftPanelWidth)
                    .padding(start = panelPadding, top = 16.dp, bottom = 16.dp),
            )
        }

        if (currentOffset <= 0f) {
            rightPanel(
                { delta -> if (state.offset.value < 0f) state.handleDrag(delta) },
                { coroutineScope.launch { state.settle() } },
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(rightPanelWidth)
                    .padding(top = 16.dp, bottom = 16.dp, end = panelPadding),
            )
        }

        val currentMaxPx = if (currentOffset >= 0f) leftPx else rightPx

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val absOffset = kotlin.math.abs(currentOffset)
                    val fraction = if (currentMaxPx > 0f) absOffset / currentMaxPx else 0f
                    translationX = currentOffset
                    scaleX = 1f - fraction * 0.05f
                    scaleY = 1f - fraction * 0.05f
                    shadowElevation = fraction * cachedShadowElevation
                    shape = if (fraction > 0.01f) cachedShape else noShape
                    clip = true
                }
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val current = state.offset.value
                        val filtered = when {
                            current > 0f -> if (enableLeftSwipe) delta else 0f
                            current < 0f -> if (enableRightSwipe) delta else 0f
                            delta > 0f -> if (enableLeftSwipe) delta else 0f
                            delta < 0f -> if (enableRightSwipe) delta else 0f
                            else -> 0f
                        }
                        if (filtered != 0f) state.handleDrag(filtered)
                    },
                    onDragStopped = {
                        coroutineScope.launch { state.settle() }
                    },
                ),
        ) {
            mainContent()
        }
    }
}
