package com.echonion.nion.ui.task

import com.echonion.nion.ui.theme.NionAlpha
import com.echonion.nion.util.BitmapUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 图片全屏预览浮层 —— 覆盖全屏显示图片，支持捏合缩放和关闭按钮。
 *
 * 点击背景或右上角关闭按钮可关闭预览。
 *
 * @param filePath 图片在内部存储中的绝对路径
 * @param onDismiss 关闭回调
 */
@Composable
fun ImagePreviewOverlay(
    filePath: String,
    onDismiss: () -> Unit,
) {
    // 缩放比例，1.0 = 原始大小
    var scale by remember { mutableFloatStateOf(1f) }
    // 平移偏移
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 从文件加载 Bitmap，限制最大尺寸防止 OOM
    val bitmap = remember(filePath) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                // 限制最大边长为 2048 像素，自适应采样
                val maxSize = 2048
                BitmapUtils.decodeFileAdaptive(file.absolutePath, maxSize, maxSize)?.asImageBitmap()
            } else {
                null
            }
        } catch (_: Exception) { null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = NionAlpha.OVERLAY_DARK))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 图片：支持捏合缩放和拖动
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "图片预览",
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // 限制缩放范围：0.5x ~ 5x
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                contentScale = ContentScale.Fit,
            )
        }

        // 右上角关闭按钮
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding(),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭预览",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
