package com.echonion.nion.ui.task

import com.echonion.nion.ui.theme.NionAlpha
import com.echonion.nion.util.BitmapUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 附件 UI 模型 —— 用于在列表中显示附件信息
 */
data class AttachmentUiItem(
    val id: String,
    /** 原始文件名 */
    val fileName: String,
    /** 应用内部存储路径 */
    val filePath: String,
    /** MIME 类型 */
    val mimeType: String,
    /** 文件大小（字节） */
    val fileSize: Long,
    /** 是否为图片类型 */
    val isImage: Boolean,
)

/**
 * 附件列表 —— 横向滚动显示已添加的附件缩略图/图标。
 *
 * 图片附件显示缩略图 + 文件名，其他文件显示图标 + 文件名。
 * 支持点击预览和删除操作。
 *
 * @param attachments 附件 UI 模型列表
 * @param onRemove 点击删除按钮时触发，传入附件 ID
 * @param onPreview 点击附件时触发，传入附件 UI 模型（用于图片全屏预览）
 * @param modifier 修饰符，由调用方传入
 * @param showRemove 是否显示删除按钮
 */
@Composable
fun AttachmentList(
    attachments: List<AttachmentUiItem>,
    onRemove: (String) -> Unit,
    onPreview: (AttachmentUiItem) -> Unit,
    modifier: Modifier = Modifier,
    showRemove: Boolean = true,
) {
    if (attachments.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "附件 (${attachments.size})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(
                items = attachments,
                key = { it.id },
            ) { attachment ->
                AttachmentCard(
                    attachment = attachment,
                    onRemove = { onRemove(attachment.id) },
                    onPreview = { onPreview(attachment) },
                    showRemove = showRemove,
                )
            }
        }
    }
}

/**
 * 单个附件卡片 —— 显示缩略图/图标 + 文件名 + 可选删除按钮。
 *
 * @param attachment 附件 UI 模型
 * @param onRemove 删除回调
 * @param onPreview 预览回调
 * @param showRemove 是否显示删除按钮
 */
@Composable
private fun AttachmentCard(
    attachment: AttachmentUiItem,
    onRemove: () -> Unit,
    onPreview: () -> Unit,
    showRemove: Boolean,
) {
    // 卡片宽度：图片缩略图宽 80dp，文件卡片宽 100dp
    val cardWidth = if (attachment.isImage) 80.dp else 100.dp

    Box(
        modifier = Modifier
            .width(cardWidth)
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onPreview),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (attachment.isImage) {
                // 图片类型：显示缩略图（从内部文件加载 Bitmap）
                val bitmap = remember(attachment.filePath) {
                    try {
                        val file = File(attachment.filePath)
                        if (file.exists()) {
                            // 目标尺寸 56dp * 2（假设 2x 密度），自适应采样避免模糊
                            val targetSize = 112
                            BitmapUtils.decodeFileAdaptive(
                                file.absolutePath, targetSize, targetSize
                            )?.asImageBitmap()
                        } else {
                            null
                        }
                    } catch (_: Exception) { null }
                }

                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = attachment.fileName,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // 加载失败时显示占位图标
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_HINT),
                    )
                }
            } else {
                // 非图片类型：显示文件图标，使用 tertiary 装饰色
                Icon(
                    imageVector = fileIconForType(attachment.mimeType),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 文件名：单行省略
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )

            // 文件大小
            Text(
                text = formatFileSize(attachment.fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY),
            )
        }

        // 删除按钮：右上角 × 图标
        if (showRemove) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .padding(0.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = NionAlpha.OVERLAY_MEDIUM),
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "删除附件",
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * 根据 MIME 类型返回对应的文件图标
 */
private fun fileIconForType(mimeType: String) = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.Description
    mimeType.contains("pdf") -> Icons.Default.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/**
 * 格式化文件大小为人类可读格式
 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
