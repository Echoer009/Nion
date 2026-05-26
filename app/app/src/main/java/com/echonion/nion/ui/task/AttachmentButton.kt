package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 附件添加按钮 —— 使用 Material Design 的 AttachFile（回形针）图标。
 *
 * 交互流程（两步展开，与专注信息行风格一致）：
 * - 状态 A（收起）：只显示回形针图标，点击后展开
 * - 状态 B（展开）：回形针右侧滑出「图片」和「文件」两个图标按钮
 *   - 点击图片/文件图标：执行对应操作并自动收起
 *   - 再次点击回形针：收起选项
 *
 * 使用 AnimatedContent + fadeIn/slideIn 实现平滑的展开/收起过渡。
 *
 * @param onPickImage 点击"选择图片"时触发
 * @param onPickFile 点击"选择文件"时触发
 * @param modifier 可选的额外 modifier
 */
@Composable
fun AttachmentButton(
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // expanded: 是否展开为两个选项按钮
    var expanded by remember { mutableStateOf(false) }

    Row(modifier = modifier) {
        // 回形针按钮：始终可见，点击切换展开/收起
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = "添加附件",
                modifier = Modifier.size(20.dp),
                tint = if (expanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 展开时从右侧滑入图片和文件两个按钮
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                if (targetState) {
                    // 展开：图片/文件图标从右侧滑入 + 淡入
                    (slideInHorizontally(tween(200, easing = FastOutSlowInEasing)) { width -> width }
                        + fadeIn(tween(200, easing = FastOutSlowInEasing)))
                        .togetherWith fadeOut(tween(100))
                        .using(SizeTransform(clip = false))
                } else {
                    // 收起：向右滑出 + 淡出
                    fadeOut(tween(150))
                        .togetherWith fadeOut(tween(150))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "attachmentExpand",
        ) { showOptions ->
            if (showOptions) {
                Row {
                    // 选择图片按钮
                    IconButton(
                        onClick = {
                            expanded = false
                            onPickImage()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = "选择图片",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    // 选择文件按钮
                    IconButton(
                        onClick = {
                            expanded = false
                            onPickFile()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.InsertDriveFile,
                            contentDescription = "选择文件",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
