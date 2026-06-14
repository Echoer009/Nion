package com.echonion.nion.ui.notebook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echonion.nion.ui.task.TaskItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color

/**
 * 笔记型清单主界面 —— 显示笔记列表，支持搜索过滤和新建笔记。
 *
 * 与任务型清单的 TaskScreenContent 对应，但展示风格不同：
 * - 笔记卡片显示标题 + 正文预览 + 更新时间
 * - 点击打开全屏 Markdown 编辑器
 * - 顶栏有搜索按钮，展开后实时过滤当前清单的笔记
 *
 * @param notes 当前笔记本中的笔记列表
 * @param notebookName 笔记本名称（用于顶栏标题）
 * @param onNoteClick 点击笔记卡片时触发，传入笔记 ID
 * @param onCreateNote 点击 FAB 时触发，新建一条空白笔记
 * @param onSearchQuery 搜索关键词变更回调，null=不搜索
 * @param modifier 外部修饰符
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookContent(
    notes: List<TaskItem>,
    notebookName: String,
    onNoteClick: (String) -> Unit,
    onCreateNote: () -> Unit,
    onSearchQuery: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 搜索框展开状态：false=隐藏搜索框，true=显示
    var isSearchOpen by remember { mutableStateOf(false) }
    // 当前搜索关键词
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏：笔记本名称 + 搜索按钮
        TopAppBar(
            title = {
                Column {
                    Text(
                        notebookName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${notes.size} 条笔记",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                // 搜索按钮：点击展开/收起搜索框
                IconButton(onClick = {
                    isSearchOpen = !isSearchOpen
                    if (!isSearchOpen) {
                        searchQuery = ""
                        onSearchQuery(null)
                    }
                }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索笔记",
                        tint = if (isSearchOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // 搜索框：展开时滑出，输入关键词实时过滤笔记
        AnimatedVisibility(
            visible = isSearchOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchQuery(it.ifBlank { null })
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索笔记...") },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                singleLine = true,
            )
        }

        // 笔记列表
        if (notes.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (searchQuery.isNotBlank()) "没有找到匹配的笔记"
                        else "点击右下角按钮创建第一条笔记",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = notes,
                    key = { it.id },
                ) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onNoteClick(note.id) },
                    )
                }
            }
        }

        // FAB：新建笔记
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FloatingActionButton(
                onClick = onCreateNote,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

/**
 * 笔记卡片 —— 显示笔记标题、正文预览、更新时间。
 *
 * @param note 笔记数据（复用 TaskItem，name=标题，description=正文）
 * @param onClick 点击卡片时触发
 */
@Composable
private fun NoteCard(
    note: TaskItem,
    onClick: () -> Unit,
) {
    // 提取正文预览：去掉 Markdown 语法符号，取前 80 个字符（使用共享工具函数）
    val preview = remember(note.description) {
        notePreview(note.description)
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // 笔记标题
            Text(
                note.name.ifBlank { "无标题" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 笔记正文预览（如果有内容）
            if (preview != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
