package com.echonion.nion.ui.task


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.echonion.nion.ui.components.DualPanelState
import kotlinx.coroutines.flow.distinctUntilChanged
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    dualState: DualPanelState,
    viewModel: TaskViewModel,
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var showDetailSheet by remember { mutableStateOf<TaskItem?>(null) }
    var showAddSubtaskFor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            if (viewModel.isSelectionMode) {
                SelectionTopBar(
                    selectedCount = viewModel.selectedTaskIds.size,
                    onClearSelection = { viewModel.clearSelection() },
                    onDeleteSelected = { viewModel.deleteSelectedTasks() },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (dualState.isRightOpen) dualState.closeRight()
                            dualState.toggleLeft()
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "清单", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (dualState.isLeftOpen) dualState.closeLeft()
                            dualState.toggleRight()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "伙伴", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    title = {
                        Column {
                            Text(viewModel.activeChecklistName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "${viewModel.todoCount}项待办 · ${viewModel.doneCount}项已完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, hoveredElevation = 8.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Default.Add, contentDescription = "添加任务") }
        },
    ) { innerPadding ->
        if (viewModel.tasks.isEmpty()) {
            EmptyTaskView(modifier = Modifier.padding(innerPadding))
        } else {
            TaskList(
                viewModel = viewModel,
                innerPadding = innerPadding,
                onTaskClick = { showDetailSheet = it },
            )
        }
    }

    if (showAddSheet) {
        AddTaskBottomSheet(
            onDismiss = { showAddSheet = false },
            onAdd = { title, desc, priority ->
                viewModel.createTask(title, desc, priority)
                showAddSheet = false
            },
        )
    }

    showDetailSheet?.let { task ->
        TaskDetailBottomSheet(
            task = task,
            onDismiss = { showDetailSheet = null },
            onDelete = { viewModel.deleteTask(task.id); showDetailSheet = null },
            onAddSubtask = { showDetailSheet = null; showAddSubtaskFor = task.id },
        )
    }

    showAddSubtaskFor?.let { parentId ->
        AddSubtaskBottomSheet(
            onDismiss = { showAddSubtaskFor = null },
            onAdd = { title, priority -> viewModel.createSubtask(parentId, title, priority); showAddSubtaskFor = null },
        )
    }
}

/**
 * 任务列表组件
 * 分为"待办"和"已完成"两个区域，使用 LazyColumn + reorderable 实现拖拽排序
 *
 * 拖拽逻辑：
 * - onMove 时移动列表中的元素位置
 * - onDragStopped 时，如果 wasMoved=true 则持久化排序；如果 wasMoved=false 则触发多选
 *
 * 性能优化要点：
 * - snapshotFlow + distinctUntilChanged 监听 todoTasks 变化，仅在列表内容实际改变时同步
 * - LazyColumn items 使用 key + contentType 减少不必要的重组
 * - onToggleDone 用 remember 提升到 LazyColumn 外层，避免每个 item 都创建新 lambda
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskList(
    viewModel: TaskViewModel,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onTaskClick: (TaskItem) -> Unit,
) {
    val listState = rememberLazyListState()
    // 可重排列表，独立于 viewModel.todoTasks，用于拖拽期间的局部重排
    val reorderableTasks = remember { mutableStateListOf<TaskItem>() }
    val haptic = LocalHapticFeedback.current
    val isSelectionMode = viewModel.isSelectionMode
    val selectedIds = viewModel.selectedTaskIds

    // 监听 viewModel.todoTasks 变化并同步到 reorderableTasks
    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.todoTasks }
            .distinctUntilChanged()
            .collect { newTasks ->
                if (reorderableTasks.isEmpty() || reorderableTasks.size != newTasks.size ||
                    reorderableTasks.zip(newTasks).any { (a, b) -> a != b }) {
                    reorderableTasks.clear()
                    reorderableTasks.addAll(newTasks)
                }
            }
    }

    // wasMoved：区分"长按选择"和"长按拖拽排序"
    // 在 onMove 回调中设为 true，在 onDragStopped 中判断
    var wasMoved by remember { mutableStateOf(false) }
    var longPressedTaskId by remember { mutableStateOf<String?>(null) }

    val headerCount = 2
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            if (!wasMoved) {
                wasMoved = true
                viewModel.clearSelection()
            }
            val fromIdx = from.index - headerCount
            val toIdx = to.index - headerCount
            if (fromIdx in reorderableTasks.indices && toIdx in reorderableTasks.indices) {
                reorderableTasks.add(toIdx, reorderableTasks.removeAt(fromIdx))
            }
        },
    )

    val onToggleDone = remember(viewModel) { { task: TaskItem -> viewModel.toggleDone(task) } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "top_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(8.dp)) }

        if (reorderableTasks.isNotEmpty()) {
            item(key = "todo_header", contentType = "header") { SectionHeader("待办", reorderableTasks.size) }

            items(
                items = reorderableTasks,
                key = { it.id },
                contentType = { "task" },
            ) { task ->
                val isSelected = task.id in selectedIds
                ReorderableItem(state = reorderableState, key = task.id) { isDragging ->
                    val cardShape = remember { RoundedCornerShape(16.dp) }
                    Box(
                        modifier = Modifier
                            .animateItemPlacement()
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    wasMoved = false
                                    longPressedTaskId = task.id
                                    viewModel.toggleSelection(task.id)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    if (wasMoved) {
                                        viewModel.reorderTasks(reorderableTasks.map { it.id })
                                    }
                                    longPressedTaskId = null
                                },
                            )
                            .graphicsLayer {
                                val scale = if (isDragging) 1.03f else 1f
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                shape = cardShape
                                clip = true
                            }
                    ) {
                        TaskCard(
                            task = task,
                            onToggleDone = onToggleDone,
                            onClick = { clickedTask ->
                                if (longPressedTaskId == task.id) return@TaskCard
                                if (isSelectionMode) {
                                    viewModel.toggleSelection(clickedTask.id)
                                } else {
                                    onTaskClick(clickedTask)
                                }
                            },
                            onSubtaskClick = { sub ->
                                if (isSelectionMode) {
                                    viewModel.toggleSelection(sub.id)
                                } else {
                                    onTaskClick(sub)
                                }
                            },
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .then(
                                    if (isSelected) Modifier.border(
                                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                        cardShape,
                                    ) else Modifier
                                ),
                        )
                    }
                }
            }
        }

        // 已完成区域（不可拖拽排序）
        val doneTasks = viewModel.doneTasks
        if (doneTasks.isNotEmpty()) {
            item(key = "done_header", contentType = "header") { SectionHeader("已完成", doneTasks.size) }
            items(
                items = doneTasks,
                key = { it.id },
                contentType = { "done_task" },
            ) { task ->
                val isSelected = task.id in selectedIds
                val cardShape = remember { RoundedCornerShape(16.dp) }
                TaskCard(
                    task = task,
                    onToggleDone = onToggleDone,
                    onClick = { clickedTask ->
                        if (isSelectionMode) {
                            viewModel.toggleSelection(clickedTask.id)
                        } else {
                            onTaskClick(clickedTask)
                        }
                    },
                    onSubtaskClick = { sub ->
                        if (isSelectionMode) {
                            viewModel.toggleSelection(sub.id)
                        } else {
                            onTaskClick(sub)
                        }
                    },
                    modifier = Modifier
                        .animateItemPlacement()
                        .then(
                            if (isSelected) Modifier.border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                cardShape,
                            ) else Modifier
                        ),
                )
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

/** 分区标题（如"待办"、"已完成"），右侧显示数量气泡 */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "$count",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 空任务提示页面 */
@Composable
private fun EmptyTaskView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("暂无任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            Text("点击 + 添加第一个任务", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 多选模式的顶部操作栏 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = "取消选择")
            }
        },
        title = {
            Text(
                "已选择 $selectedCount 项",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        actions = {
            IconButton(onClick = onDeleteSelected) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除选中",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}
