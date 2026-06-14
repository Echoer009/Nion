package com.echonion.nion.ui.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.task.TaskItem

/**
 * 笔记-任务关联选择器对话框 —— 搜索并选择要关联的任务或笔记。
 *
 * 用于双向关联：
 * - 从任务侧：选择要关联的笔记
 * - 从笔记侧：选择要关联的任务
 *
 * @param title 对话框标题（如"关联笔记"或"关联任务"）
 * @param items 可选的项目列表（任务或笔记）
 * @param linkedIds 已关联的 ID 集合（已关联的显示"取消关联"按钮）
 * @param onLink 关联回调，传入项目 ID
 * @param onUnlink 取消关联回调，传入项目 ID
 * @param onDismiss 关闭对话框回调
 */
@Composable
fun LinkItemDialog(
    title: String,
    items: List<TaskItem>,
    linkedIds: Set<String>,
    onLink: (String) -> Unit,
    onUnlink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    // 本地过滤：按标题匹配搜索词
    val filteredItems = if (searchQuery.isBlank()) {
        items
    } else {
        items.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(title, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                // 搜索框：实时过滤项目列表
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索...") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 项目列表：高度限制在 300dp 以内
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val isLinked = item.id in linkedIds
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isLinked)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (isLinked) onUnlink(item.id) else onLink(item.id)
                                    },
                                ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    // 已关联=Link（已连接），未关联=AddCircleOutline（可添加）
                                    if (isLinked) Icons.Default.Link else Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = if (isLinked)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        item.name.ifBlank { "无标题" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isLinked) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // 显示正文预览（笔记去除 Markdown 语法后显示，任务直接显示描述）
                                    item.description?.let { desc ->
                                        val previewDesc = stripMarkdown(desc).take(50)
                                        if (previewDesc.isNotBlank()) {
                                            Text(
                                                previewDesc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (filteredItems.isEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (searchQuery.isBlank()) "暂无可选项目" else "没有匹配的项目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
