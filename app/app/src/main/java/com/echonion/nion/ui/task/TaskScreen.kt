package com.echonion.nion.ui.task

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskList(
    viewModel: TaskViewModel,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onTaskClick: (TaskItem) -> Unit,
) {
    val listState = rememberLazyListState()
    val reorderableItems = remember { mutableStateListOf<FlatTaskItem>() }
    val haptic = LocalHapticFeedback.current
    val isSelectionMode = viewModel.isSelectionMode
    val selectedIds = viewModel.selectedTaskIds

    var wasMoved by remember { mutableStateOf(false) }
    var longPressedTaskId by remember { mutableStateOf<String?>(null) }
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var collapsedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.flatTodoTasks }
            .distinctUntilChanged()
            .collect { newItems ->
                if (reorderableItems.isEmpty() || reorderableItems.size != newItems.size ||
                    reorderableItems.zip(newItems).any { (a, b) -> a != b }) {
                    reorderableItems.clear()
                    reorderableItems.addAll(newItems)
                }
            }
    }

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
            if (fromIdx !in reorderableItems.indices || toIdx !in reorderableItems.indices) return@rememberReorderableLazyListState

            val movedItem = reorderableItems[fromIdx]

            if (movedItem.depth == 0 && draggedGroupId == null) {
                draggedGroupId = movedItem.task.id
                val groupIds = collectGroupIds(reorderableItems.toList(), movedItem.task.id)
                if (groupIds.size > 1) {
                    val toCollapse = groupIds.toSet() - movedItem.task.id
                    collapsedIds = toCollapse
                    val filtered = reorderableItems.filter { it.task.id !in toCollapse }
                    reorderableItems.clear()
                    reorderableItems.addAll(filtered)
                    val newFrom = reorderableItems.indexOfFirst { it.task.id == movedItem.task.id }
                    if (newFrom >= 0) {
                        var newTo = newFrom + (toIdx - fromIdx)
                        newTo = newTo.coerceIn(0, reorderableItems.lastIndex)
                        if (newFrom != newTo) {
                            reorderableItems.add(newTo, reorderableItems.removeAt(newFrom))
                        }
                    }
                    return@rememberReorderableLazyListState
                }
            }

            reorderableItems.add(toIdx, reorderableItems.removeAt(fromIdx))
        },
    )

    val onToggleDone = remember(viewModel) { { task: TaskItem -> viewModel.toggleDone(task) } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "top_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(8.dp)) }

        if (reorderableItems.isNotEmpty()) {
            item(key = "todo_header", contentType = "header") {
                SectionHeader("待办", reorderableItems.count { it.depth == 0 && it.task.id !in collapsedIds })
            }

            items(
                items = reorderableItems,
                key = { it.task.id },
                contentType = { if (it.depth == 0) "main_task" else "sub_task" },
            ) { flatItem ->
                if (flatItem.task.id in collapsedIds) return@items

                val isSelected = flatItem.task.id in selectedIds
                val groupFirst = flatItem.isGroupFirst
                val groupLast = flatItem.isGroupLast
                val isDraggedMain = draggedGroupId == flatItem.task.id && flatItem.depth == 0

                val spacingModifier = when {
                    isDraggedMain -> Modifier.padding(bottom = 8.dp)
                    groupFirst -> Modifier.padding(top = 8.dp)
                    groupLast -> Modifier.padding(bottom = 8.dp)
                    else -> Modifier
                }

                ReorderableItem(state = reorderableState, key = flatItem.task.id) { isDragging ->
                    val cardShape = remember { RoundedCornerShape(16.dp) }
                    Box(
                        modifier = Modifier
                            .animateItemPlacement()
                            .then(spacingModifier)
                            .then(
                                if (isDragging) Modifier
                                    .shadow(8.dp, cardShape)
                                    .graphicsLayer {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                    }
                                else Modifier
                            )
                            .zIndex(if (isDragging) 1f else 0f)
                            .longPressDraggableHandle(
                                onDragStarted = {
                                    wasMoved = false
                                    longPressedTaskId = flatItem.task.id
                                    viewModel.toggleSelection(flatItem.task.id)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    if (wasMoved) {
                                        handleDrop(reorderableItems, draggedGroupId, flatItem, viewModel)
                                    }
                                    longPressedTaskId = null
                                    draggedGroupId = null
                                    collapsedIds = emptySet()
                                },
                            )
                    ) {
                        FlatTaskRow(
                            item = flatItem,
                            onToggleDone = onToggleDone,
                            onClick = { clickedTask ->
                                if (longPressedTaskId == flatItem.task.id) return@FlatTaskRow
                                if (isSelectionMode) {
                                    viewModel.toggleSelection(clickedTask.id)
                                } else {
                                    onTaskClick(clickedTask)
                                }
                            },
                            isSelected = isSelected,
                        )
                    }
                }
            }
        }

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
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .animateItemPlacement()
                        .then(
                            if (isSelected) Modifier.border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                cardShape,
                            ) else Modifier
                        )
                ) {
                    FlatTaskRow(
                        item = FlatTaskItem(task, 0, null, true, true),
                        onToggleDone = onToggleDone,
                        onClick = { clickedTask ->
                            if (isSelectionMode) {
                                viewModel.toggleSelection(clickedTask.id)
                            } else {
                                onTaskClick(clickedTask)
                            }
                        },
                        isSelected = isSelected,
                    )
                }
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

private fun collectGroupIds(items: List<FlatTaskItem>, mainTaskId: String): List<String> {
    val result = mutableListOf(mainTaskId)
    val startIdx = items.indexOfFirst { it.task.id == mainTaskId }
    if (startIdx >= 0) {
        for (i in (startIdx + 1) until items.size) {
            if (items[i].depth == 0) break
            result.add(items[i].task.id)
        }
    }
    return result
}

private fun handleDrop(
    reorderableItems: List<FlatTaskItem>,
    draggedGroupId: String?,
    currentDragItem: FlatTaskItem,
    viewModel: TaskViewModel,
) {
    val draggedId = draggedGroupId ?: currentDragItem.task.id
    val currentIdx = reorderableItems.indexOfFirst { it.task.id == draggedId }
    if (currentIdx == -1) return

    val draggedDepth = reorderableItems[currentIdx].depth
    var newParentId: String? = null

    if (currentIdx == 0) {
        newParentId = null
    } else {
        val aboveItem = reorderableItems[currentIdx - 1]
        when {
            aboveItem.depth == 0 && draggedDepth > 0 -> newParentId = aboveItem.task.id
            aboveItem.depth == 0 -> newParentId = null
            aboveItem.depth > draggedDepth -> newParentId = aboveItem.task.id
            aboveItem.depth == draggedDepth -> newParentId = aboveItem.parentId
            aboveItem.depth < draggedDepth -> {
                var si = currentIdx - 1
                while (si >= 0 && reorderableItems[si].depth > draggedDepth) si--
                if (si >= 0) {
                    val anc = reorderableItems[si]
                    newParentId = if (anc.depth == draggedDepth) anc.parentId else anc.task.id
                }
            }
        }
    }

    viewModel.moveTask(draggedId, newParentId)

    val siblingIds = reorderableItems
        .filter { it.parentId == newParentId }
        .map { it.task.id }
    if (siblingIds.size > 1) {
        viewModel.reorderTasks(siblingIds)
    }
}

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
                Icon(Icons.Default.Delete, contentDescription = "删除选中", tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}
