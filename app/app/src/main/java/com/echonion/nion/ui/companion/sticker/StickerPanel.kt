package com.echonion.nion.ui.companion.sticker

import com.echonion.nion.ui.theme.NionAlpha
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.companion.CompanionViewModel
import uniffi.nion_core.StickerData

/**
 * 表情包管理面板 —— 独立面板，用于添加、查看和删除表情包。
 * 布局与 MemoriesPanel / PreferencesPanel 保持一致。
 *
 * @param viewModel ViewModel 实例，用于读写表情包数据
 * @param onClose 点击返回按钮时触发
 */
@Composable
internal fun StickersPanel(
    viewModel: CompanionViewModel,
    onClose: () -> Unit,
) {
    // 是否显示添加表情包的对话框
    var showAddDialog by remember { mutableStateOf(false) }
    val stickers = viewModel.stickers

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp, horizontal = 16.dp),
    ) {
        // 顶部导航栏：返回按钮 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "表情包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (stickers.isEmpty()) "暂无表情包" else "共 ${stickers.size} 个表情包",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 说明文本
        Text(
            "添加表情包后，AI 会在回复中用 <标签名> 来发送表情",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 添加表情包按钮 —— 用禁用的 OutlinedTextField 做点击热区（视觉统一）
        OutlinedTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            label = { Text("添加表情包") },
            leadingIcon = {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAddDialog = true },
            enabled = false,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 表情包列表
        if (stickers.isEmpty()) {
            // 空状态
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(40.dp))
                Icon(
                    Icons.Outlined.SentimentSatisfied,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.BG_SUBTLE),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "还没有添加表情包",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                )
                Text(
                    "点击上方按钮添加",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.BG_SUBTLE),
                )
            }
        } else {
            // 表情包列表
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (sticker in stickers) {
                    StickerItem(
                        sticker = sticker,
                        onDelete = { viewModel.deleteSticker(sticker.id) },
                    )
                }
            }
        }
    }

    // 添加表情包对话框
    if (showAddDialog) {
        AddStickerDialog(
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
        )
    }
}

/**
 * 表情包缩略图卡片 —— 显示表情图片 + 标签名 + 删除按钮。
 * 在 StickersPanel 中以列表项形式展示（水平排列）。
 *
 * 使用 StickerService.loadThumbnail() 加载缩略图（4× 降采样 + LRU 缓存）。
 *
 * @param sticker 表情包数据
 * @param onDelete 点击删除按钮时触发
 */
@Composable
private fun StickerItem(
    sticker: StickerData,
    onDelete: () -> Unit,
) {
    // 通过 StickerService 加载缩略图（自适应采样率 + LRU 缓存，避免重复解码）
    val bitmap = remember(sticker.filePath) {
        StickerService.loadThumbnail(sticker.filePath)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = NionAlpha.OVERLAY_MEDIUM),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 表情缩略图（40dp 方框，圆角 6dp）
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = sticker.tag,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // 解码失败时的占位文字
                    Text(
                        "?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 标签名（以 <tag> 格式显示）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "<${sticker.tag}>",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 删除按钮
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除表情包",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                )
            }
        }
    }
}

/**
 * 添加表情包对话框 —— 选择图片 + 输入标签名。
 *
 * 流程：
 * 1. 用户点击图片区域 → 打开系统图片选择器（PickVisualMedia.ImageOnly）
 * 2. 选取后复制到 cacheDir 临时文件（解决 content URI 无法直接作为文件路径的问题）
 * 3. 用户输入标签名（自动过滤 <、>、空格）
 * 4. 点击"添加" → 调用 viewModel.addSticker()，文件复制由 StickerService 完成
 *
 * @param viewModel ViewModel 实例，用于调用 addSticker
 * @param onDismiss 点击取消或添加完成后触发
 */
@Composable
private fun AddStickerDialog(
    viewModel: CompanionViewModel,
    onDismiss: () -> Unit,
) {
    var tag by remember { mutableStateOf("") }
    // 暂存已选图片的信息：tempFilePath, mimeType, fileName, fileSize
    var pickedTempPath by remember { mutableStateOf<String?>(null) }
    var pickedMimeType by remember { mutableStateOf("image/png") }
    var pickedFileName by remember { mutableStateOf("") }
    var pickedFileSize by remember { mutableStateOf(0L) }

    val context = LocalContext.current

    // 图片选择器启动器 —— 选取后复制到临时文件以获取可读文件路径
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@rememberLauncherForActivityResult
                val tempFile = java.io.File(context.cacheDir, "sticker_temp_${System.currentTimeMillis()}")
                tempFile.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()
                val mimeType = context.contentResolver.getType(uri) ?: "image/png"
                val fileName = uri.lastPathSegment ?: "sticker.png"
                pickedTempPath = tempFile.absolutePath
                pickedMimeType = mimeType
                pickedFileName = fileName
                pickedFileSize = tempFile.length()
            } catch (_: Exception) {}
        }
    }

    // 预览图 —— 使用 loadForRender 加载更高分辨率（400px），避免 100dp 预览区域模糊
    val previewBitmap = remember(pickedTempPath) {
        pickedTempPath?.let { StickerService.loadForRender(it, 400) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加表情包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 图片选择区域
                Text("选择图片：", style = MaterialTheme.typography.labelMedium)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = NionAlpha.BG_SUBTLE))
                        .clickable {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "预览",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("点击选择图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 标签输入框 —— 自动过滤尖括号和空格
                Text("表情标签：", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = tag,
                    onValueChange = {
                        // 过滤掉尖括号和空格，只保留有效标签字符
                        tag = it.replace("<", "").replace(">", "").replace(" ", "")
                    },
                    label = { Text("标签名，如：开心") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 标签预览：显示 <标签名> 格式
                if (tag.isNotEmpty()) {
                    Text(
                        "预览：<$tag>",
                        style = MaterialTheme.typography.bodySmall,
                        // 预览文字使用 tertiary（装饰性信息）
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = pickedTempPath
                    if (path != null && tag.isNotBlank()) {
                        viewModel.addSticker(
                            tag = tag.trim(),
                            sourcePath = path,
                            fileName = pickedFileName,
                            mimeType = pickedMimeType,
                            fileSize = pickedFileSize,
                        )
                        onDismiss()
                    }
                },
                enabled = pickedTempPath != null && tag.isNotBlank(),
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
