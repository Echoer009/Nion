package com.echonion.nion.ui.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.echonion.nion.core
import com.echonion.nion.ui.task.TaskItem
import com.echonion.nion.ui.task.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局笔记搜索页面 —— 搜索所有笔记型清单中的笔记。
 *
 * 通过 Rust 核心库的 searchNotes 方法进行全文搜索，匹配笔记标题和正文。
 * 搜索结果按更新时间降序排列，点击笔记可跳转到编辑器。
 *
 * @param onNoteClick 点击搜索结果中的笔记时触发，传入笔记 ID
 * @param onBack 返回回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSearchScreen(
    onNoteClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 搜索防抖：输入变化后延迟 300ms 执行搜索，避免频繁查库
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(300)
        try {
            // 调用 Rust 核心库全文搜索：query, checklistId=null（搜索所有笔记清单）
            val notes = withContext(Dispatchers.IO) {
                app.core().searchNotes(query.trim(), null).map { it.toUi() }
            }
            results = notes
        } catch (_: Exception) {
            results = emptyList()
        }
        isSearching = false
    }

    // 进入页面自动聚焦搜索框
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("搜索笔记...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            // 清除搜索内容按钮：输入框非空时显示
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "清除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        singleLine = true,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (query.isBlank()) {
                // 初始状态：提示输入关键词
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "输入关键词搜索所有笔记",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            } else if (isSearching) {
                // 搜索中：显示旋转加载指示器
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "搜索中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (results.isEmpty()) {
                // 无结果状态：图标 + 提示
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "没有找到匹配「$query」的笔记",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            } else {
                // 搜索结果列表
                Text(
                    "找到 ${results.size} 条结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.id }) { note ->
                        NoteSearchResultCard(
                            note = note,
                            highlightQuery = query.trim(),
                            onClick = { onNoteClick(note.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果卡片 —— 显示笔记标题和正文预览，高亮匹配的关键词。
 *
 * 标题和预览中的关键词使用 primary 色背景高亮，帮助用户快速定位匹配位置。
 *
 * @param note 笔记数据
 * @param highlightQuery 搜索关键词（用于高亮匹配位置）
 * @param onClick 点击回调
 */
@Composable
private fun NoteSearchResultCard(
    note: TaskItem,
    highlightQuery: String,
    onClick: () -> Unit,
) {
    // 提取正文预览：去掉 Markdown 语法符号后取前 80 字符（使用共享工具函数）
    val preview = remember(note.description) {
        notePreview(note.description)
    }

    // 高亮样式颜色：从 Material 主题获取
    val highlightColor = MaterialTheme.colorScheme.primary
    val highlightBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)

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
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题：高亮匹配关键词
            Text(
                text = highlightText(note.name.ifBlank { "无标题" }, highlightQuery, highlightColor, highlightBg),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview != null) {
                Spacer(modifier = Modifier.height(4.dp))
                // 预览：高亮匹配关键词
                Text(
                    text = highlightText(preview, highlightQuery, highlightColor, highlightBg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 高亮文本中的匹配关键词 —— 使用 AnnotatedString 将匹配部分着色。
 *
 * 不区分大小写匹配。匹配文本使用 primary 色文字 + 半透明 primaryContainer 背景，
 * 视觉上突出但不至于过分抢眼。
 *
 * @param text 原始文本
 * @param query 要高亮的关键词
 * @param color 匹配文字颜色
 * @param bgColor 匹配背景颜色
 * @return 带 SpanStyle 高亮的 AnnotatedString
 */
private fun highlightText(
    text: String,
    query: String,
    color: Color,
    bgColor: Color,
): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    return buildAnnotatedString {
        // 不区分大小写查找所有匹配位置
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var lastIndex = 0
        var searchStart = 0

        while (true) {
            val matchIndex = lowerText.indexOf(lowerQuery, searchStart)
            if (matchIndex == -1) break
            // 添加匹配前的普通文本
            append(text.substring(lastIndex, matchIndex))
            // 添加高亮的匹配文本：primary 色 + 半透明背景
            withStyle(
                SpanStyle(
                    color = color,
                    background = bgColor,
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }
            lastIndex = matchIndex + query.length
            searchStart = matchIndex + query.length
        }
        // 添加最后一段普通文本
        append(text.substring(lastIndex))
    }
}
