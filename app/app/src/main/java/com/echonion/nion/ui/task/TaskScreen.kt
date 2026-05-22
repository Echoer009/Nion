package com.echonion.nion.ui.task

import android.util.Log
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.echonion.nion.ui.components.DualPanelState
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
    var subsRemoved by remember { mutableStateOf(false) }
    var longPressedTaskId by remember { mutableStateOf<String?>(null) }
    var draggedGroupId by remember { mutableStateOf<String?>(null) }
    var preCollapseGroupIds by remember { mutableStateOf<List<String>>(emptyList()) }
    Log.w("TaskScreen", "TaskScreen STARTUP — reorderableItems init")

    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.flatTodoTasks }
            .collect { newItems ->
                if (reorderableItems.isEmpty() || reorderableItems.size != newItems.size ||
                    reorderableItems.zip(newItems).any { (a, b) -> a != b }) {
                    reorderableItems.clear()
                    reorderableItems.addAll(newItems)
                }
            }
    }

    val headerCount = 2

    val effectiveLastMap by remember {
        derivedStateOf {
            val map = mutableMapOf<String, Boolean>()
            for (i in reorderableItems.indices) {
                val item = reorderableItems[i]
                val isLast = i == reorderableItems.lastIndex || reorderableItems[i + 1].depth == 0
                map[item.task.id] = isLast
            }
            map
        }
    }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            wasMoved = true
            val fromIdx = from.index - headerCount
            val toIdx = to.index - headerCount
            Log.w("TaskScreen", "onMove from=$fromIdx to=$toIdx draggedId=$draggedGroupId subsRemoved=$subsRemoved preCollapseSize=${preCollapseGroupIds.size} listSize=${reorderableItems.size}")

            if (draggedGroupId != null && !subsRemoved && preCollapseGroupIds.size > 1) {
                subsRemoved = true
                val subIds = preCollapseGroupIds.toSet() - draggedGroupId!!
                val removedCount = subIds.size
                Log.w("TaskScreen", "onMove FIRST: removing subIds=$subIds removedCount=$removedCount")
                reorderableItems.removeAll { it.task.id in subIds }
                Log.w("TaskScreen", "onMove FIRST: after remove listSize=${reorderableItems.size}")
                val adjustedToIdx = if (toIdx > fromIdx) toIdx - removedCount else toIdx
                Log.w("TaskScreen", "onMove FIRST: adjustedToIdx=$adjustedToIdx (orig=$toIdx)")
                if (fromIdx !in reorderableItems.indices || adjustedToIdx !in reorderableItems.indices) {
                    Log.w("TaskScreen", "onMove FIRST: index out of bounds, returning")
                    return@rememberReorderableLazyListState
                }
                reorderableItems.add(adjustedToIdx, reorderableItems.removeAt(fromIdx))
                Log.w("TaskScreen", "onMove FIRST: swap done, listSize=${reorderableItems.size}")
                return@rememberReorderableLazyListState
            }

            if (fromIdx !in reorderableItems.indices || toIdx !in reorderableItems.indices) return@rememberReorderableLazyListState
            reorderableItems.add(toIdx, reorderableItems.removeAt(fromIdx))
            Log.w("TaskScreen", "onMove NORMAL: swapped $fromIdx -> $toIdx")
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
                SectionHeader("待办", reorderableItems.count { it.depth == 0 })
            }

            items(
                items = reorderableItems,
                key = { it.task.id },
                contentType = { if (it.depth == 0) "main_task" else "sub_task" },
            ) { flatItem ->
                val isSelected = flatItem.task.id in selectedIds
                val isInDraggedGroup = draggedGroupId != null && flatItem.task.id in preCollapseGroupIds

                val effectiveIsLast = effectiveLastMap[flatItem.task.id] ?: flatItem.isGroupLast
                val displayItem = flatItem.copy(isGroupLast = effectiveIsLast)

                val groupSelected = if (flatItem.parentId == null) {
                    val allInGroup = (listOf(flatItem.task.id) + collectDescendantIds(reorderableItems.toList(), flatItem.task.id)).all { it in selectedIds }
                    isSelected && allInGroup
                } else {
                    val mainId = flatItem.parentId!!
                    val allInGroup = (listOf(mainId) + collectDescendantIds(reorderableItems.toList(), mainId)).all { it in selectedIds }
                    allInGroup
                }

                val spacingModifier = when {
                    isInDraggedGroup -> Modifier
                    displayItem.isGroupFirst -> Modifier.padding(top = 8.dp)
                    displayItem.isGroupLast -> Modifier.padding(bottom = 8.dp)
                    else -> Modifier
                }

                ReorderableItem(
                    state = reorderableState,
                    key = flatItem.task.id,
                    animateItemModifier = if (!isInDraggedGroup) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null) else Modifier,
                ) { isDragging ->
                    val cardShape = remember { RoundedCornerShape(16.dp) }
                    Box(
                        modifier = Modifier
                            .then(spacingModifier)
                            .then(
                                if (isDragging) Modifier
                                    .shadow(8.dp, cardShape)
                                    .graphicsLayer {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                        clip = false
                                    }
                                else Modifier
                            )
                            .zIndex(if (isDragging) 1f else 0f)
                             .longPressDraggableHandle(
                                onDragStarted = {
                                    wasMoved = false
                                    subsRemoved = false
                                    longPressedTaskId = flatItem.task.id
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                                    draggedGroupId = flatItem.task.id
                                    val descendantIds = collectDescendantIds(reorderableItems.toList(), flatItem.task.id)
                                    preCollapseGroupIds = listOf(flatItem.task.id) + descendantIds.toList()
                                    Log.w("TaskScreen", "onDragStarted id=$draggedGroupId depth=${flatItem.depth} parentId=${flatItem.parentId} preCollapse=$preCollapseGroupIds descendantCount=${descendantIds.size} listSize=${reorderableItems.size}")
                                },
                                onDragStopped = {
                                    Log.w("TaskScreen", "onDragStopped wasMoved=$wasMoved draggedId=$draggedGroupId preCollapseSize=${preCollapseGroupIds.size} listSize=${reorderableItems.size}")
                                    if (wasMoved) {
                                        val draggedId = draggedGroupId ?: flatItem.task.id
                                        val mainIdx = reorderableItems.indexOfFirst { it.task.id == draggedId }
                                        Log.w("TaskScreen", "onDragStopped WAS_MOVED mainIdx=$mainIdx draggedId=$draggedId")
                                        if (mainIdx >= 0 && draggedGroupId != null && preCollapseGroupIds.size > 1) {
                                            val subIds = preCollapseGroupIds.toSet() - draggedId
                                            val subs = viewModel.flatTodoTasks.filter { it.task.id in subIds }
                                            Log.w("TaskScreen", "onDragStopped re-inserting subs: subIds=$subIds subsCount=${subs.size} at mainIdx+1=${mainIdx + 1}")
                                            reorderableItems.addAll(mainIdx + 1, subs)
                                        }
                                        handleDrop(reorderableItems, draggedId, viewModel)
                                    } else {
                                        Log.w("TaskScreen", "onDragStopped SELECTION mode, preCollapseSize=${preCollapseGroupIds.size}")
                                        if (preCollapseGroupIds.size > 1) {
                                            val groupIds = preCollapseGroupIds
                                            val allSelected = groupIds.all { it in selectedIds }
                                            for (id in groupIds) {
                                                if (allSelected) {
                                                    if (id in selectedIds) viewModel.toggleSelection(id)
                                                } else {
                                                    if (id !in selectedIds) viewModel.toggleSelection(id)
                                                }
                                            }
                                        } else {
                                            viewModel.toggleSelection(flatItem.task.id)
                                        }
                                        val fresh = viewModel.flatTodoTasks
                                        if (reorderableItems.size != fresh.size || reorderableItems.zip(fresh).any { (a, b) -> a != b }) {
                                            Log.w("TaskScreen", "onDragStopped syncing from fresh list: freshSize=${fresh.size} currentSize=${reorderableItems.size}")
                                            reorderableItems.clear()
                                            reorderableItems.addAll(fresh)
                                        }
                                    }
                                    longPressedTaskId = null
                                    draggedGroupId = null
                                    preCollapseGroupIds = emptyList()
                                    subsRemoved = false
                                },
                            )
                    ) {
                        FlatTaskRow(
                            item = displayItem,
                            onToggleDone = onToggleDone,
                            onClick = { clickedTask ->
                                if (longPressedTaskId == flatItem.task.id) return@FlatTaskRow
                                if (isSelectionMode) {
                                    val descendantIds = collectDescendantIds(reorderableItems.toList(), flatItem.task.id)
                                    if (descendantIds.isNotEmpty()) {
                                        val groupIds = listOf(flatItem.task.id) + descendantIds.toList()
                                        val allSelected = groupIds.all { it in selectedIds }
                                        for (id in groupIds) {
                                            if (allSelected) {
                                                if (id in selectedIds) viewModel.toggleSelection(id)
                                            } else {
                                                if (id !in selectedIds) viewModel.toggleSelection(id)
                                            }
                                        }
                                    } else {
                                        viewModel.toggleSelection(clickedTask.id)
                                    }
                                } else {
                                    onTaskClick(clickedTask)
                                }
                            },
                            isSelected = isSelected,
                            isGroupSelected = groupSelected,
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
                        isGroupSelected = isSelected,
                    )
                }
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

private fun collectDescendantIds(items: List<FlatTaskItem>, parentId: String): Set<String> {
    val result = mutableSetOf<String>()
    val children = items.filter { it.parentId == parentId }
    for (child in children) {
        result.add(child.task.id)
        result.addAll(collectDescendantIds(items, child.task.id))
    }
    return result
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
    draggedId: String,
    viewModel: TaskViewModel,
) {
    val currentIdx = reorderableItems.indexOfFirst { it.task.id == draggedId }
    if (currentIdx == -1) {
        Log.w("TaskScreen", "handleDrop: draggedId=$draggedId not found in list")
        return
    }
    val draggedItem = reorderableItems[currentIdx]
    Log.w("TaskScreen", "handleDrop: currentIdx=$currentIdx draggedId=$draggedId depth=${draggedItem.depth} parentId=${draggedItem.parentId}")

    val newParentId: String? = if (currentIdx == 0) {
        Log.w("TaskScreen", "handleDrop: at top of list -> newParentId=null")
        null
    } else {
        val aboveItem = reorderableItems[currentIdx - 1]
        val belowIsTopLevel = currentIdx + 1 >= reorderableItems.size || reorderableItems[currentIdx + 1].depth == 0
        Log.w("TaskScreen", "handleDrop: aboveItem id=${aboveItem.task.id} depth=${aboveItem.depth} belowIsTopLevel=$belowIsTopLevel draggedDepth=${draggedItem.depth}")
        when {
            aboveItem.depth == 0 && belowIsTopLevel && draggedItem.depth == 0 -> {
                Log.w("TaskScreen", "handleDrop: case A: main between two mains -> null")
                null
            }
            aboveItem.depth == 0 -> {
                Log.w("TaskScreen", "handleDrop: case B: above is main, become child -> parentId=${aboveItem.task.id}")
                aboveItem.task.id
            }
            else -> {
                Log.w("TaskScreen", "handleDrop: case C: above is child, inherit parent -> parentId=${aboveItem.parentId}")
                aboveItem.parentId
            }
        }
    }

    val siblingIds = when (newParentId) {
        null -> {
            val result = mutableListOf<String>()
            for (item in reorderableItems) {
                if (item.task.id == draggedId) {
                    result.add(item.task.id)
                } else if (item.depth == 0) {
                    result.add(item.task.id)
                }
            }
            Log.w("TaskScreen", "handleDrop: siblings for null parent: $result")
            result
        }
        else -> {
            val parentIdx = reorderableItems.indexOfFirst { it.task.id == newParentId }
            if (parentIdx < 0) {
                val result = mutableListOf<String>()
                for (item in reorderableItems) {
                    if (item.task.id == draggedId) result.add(item.task.id)
                    else if (item.depth == 0) result.add(item.task.id)
                }
                Log.w("TaskScreen", "handleDrop: parentIdx not found ($newParentId), fallback siblings: $result")
                result
            } else {
                val result = mutableListOf<String>()
                var i = parentIdx + 1
                while (i < reorderableItems.size) {
                    val item = reorderableItems[i]
                    if (item.task.id == draggedId) {
                        result.add(item.task.id)
                        i++
                        continue
                    }
                    if (item.depth == 0) break
                    if (item.parentId == newParentId) {
                        result.add(item.task.id)
                    }
                    i++
                }
                Log.w("TaskScreen", "handleDrop: siblings for parent $newParentId (parentIdx=$parentIdx): $result")
                result
            }
        }
    }

    Log.w("TaskScreen", "handleDrop: CALLING moveAndReorderTasks(draggedId=$draggedId, newParentId=$newParentId, siblingIds=$siblingIds)")
    viewModel.moveAndReorderTasks(draggedId, newParentId, siblingIds)
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
