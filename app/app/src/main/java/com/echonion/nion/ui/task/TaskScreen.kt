package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
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
    /** 拖拽开始时保存的子任务数据，用于拖拽过程中渲染分组卡片以及在拖拽停止后恢复 */
    var draggedSubItems by remember { mutableStateOf<List<FlatTaskItem>>(emptyList()) }
    /** 长按选中项所在的组 ID 集合（含主任务自身），用于判断 isInDraggedGroup */
    var preCollapseGroupIds by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 拖拽浮起时的圆角形状，让 graphicsLayer 的阴影也跟随圆角而非直角 */
    val dragCardShape = remember { RoundedCornerShape(12.dp) }
    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.flatTodoTasks }
            .collect { newItems ->
                // 拖拽期间不覆盖 reorderableItems，否则 onMove 刚移除的子任务会被 ViewModel 数据恢复
                if (draggedGroupId != null) return@collect
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

            /*
             * 首次移动：从列表中移除子任务，产生"主任务已经浮起、子任务从原位消失"的视觉效果。
             * 子任务实际数据已保存在 draggedSubItems 中，拖拽停止后会恢复。
             */
            if (draggedGroupId != null && !subsRemoved && preCollapseGroupIds.size > 1) {
                subsRemoved = true
                val subIds = preCollapseGroupIds.toSet() - draggedGroupId!!
                val removedCount = subIds.size
                reorderableItems.removeAll { it.task.id in subIds }
                val adjustedToIdx = if (toIdx > fromIdx) toIdx - removedCount else toIdx
                if (fromIdx !in reorderableItems.indices || adjustedToIdx !in reorderableItems.indices) {
                    return@rememberReorderableLazyListState
                }
                reorderableItems.add(adjustedToIdx, reorderableItems.removeAt(fromIdx))
                return@rememberReorderableLazyListState
            }

            if (fromIdx !in reorderableItems.indices || toIdx !in reorderableItems.indices) return@rememberReorderableLazyListState
            reorderableItems.add(toIdx, reorderableItems.removeAt(fromIdx))
        },
    )

    /* onToggleDone 回调：点击勾选框时切换任务完成状态。
     * 使用 remember + lambda 包装，避免在 items 重组时频繁创建新实例。 */
    val onToggleDone = remember(viewModel) { { task: TaskItem -> viewModel.toggleDone(task) } }

    /* 预计算每个主任务组的成员 ID 集合，以及每个 item 到其主任务的映射。
     * 避免 groupSelected 计算中反复调用 collectDescendantIds 导致 O(n²) 开销。
     * 必须在 LazyColumn 外部计算，因为 LazyListScope 不是 @Composable 上下文。 */
    val groupIdsMap = remember(reorderableItems.toList()) {
        val map = mutableMapOf<String, MutableSet<String>>()
        var currentMainId: String? = null
        for (item in reorderableItems) {
            if (item.depth == 0) {
                currentMainId = item.task.id
                map[item.task.id] = mutableSetOf(item.task.id)
            } else {
                currentMainId?.let { map[it]?.add(item.task.id) }
            }
        }
        map.mapValues { (_, v) -> v.toSet() }
    }
    val itemToMainId = remember(reorderableItems.toList()) {
        val map = mutableMapOf<String, String>()
        var currentMainId: String? = null
        for (item in reorderableItems) {
            if (item.depth == 0) {
                currentMainId = item.task.id
            }
            currentMainId?.let { map[item.task.id] = it }
        }
        map.toMap()
    }

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

                /* 使用预计算的 groupIdsMap 替代每次调用 collectDescendantIds，
                 * O(1) 查找 vs O(n) 全表扫描 */
                val groupSelected = if (flatItem.parentId == null) {
                    val groupIds = groupIdsMap[flatItem.task.id] ?: setOf(flatItem.task.id)
                    isSelected && groupIds.all { it in selectedIds }
                } else {
                    val mainId = itemToMainId[flatItem.task.id] ?: flatItem.parentId!!
                    val groupIds = groupIdsMap[mainId] ?: setOf(mainId)
                    groupIds.all { it in selectedIds }
                }

                val spacingModifier = when {
                    /* 长按未移动时（!subsRemoved）移除间距，配合分组卡片整体浮起；
                     * 开始移动后（subsRemoved）子任务已从列表移除，间距无关 */
                    isInDraggedGroup && !subsRemoved -> Modifier
                    displayItem.isGroupFirst -> Modifier.padding(top = 8.dp)
                    displayItem.isGroupLast -> Modifier.padding(bottom = 8.dp)
                    else -> Modifier
                }

                /* 长按未移动时（!subsRemoved）隐藏原始子任务项，避免与分组卡片重复渲染；
                     * 开始移动后子任务已从列表移除，无需隐藏 */
                val isSubDuringDrag = !subsRemoved && draggedGroupId != null && isInDraggedGroup && flatItem.task.id != draggedGroupId

                ReorderableItem(
                    state = reorderableState,
                    key = flatItem.task.id,
                    animateItemModifier = if (!isInDraggedGroup) Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null) else Modifier,
                ) { isDragging ->
                    val density = LocalDensity.current

                    /*
                     * 拖拽时的缩放动画 —— 使用 spring 弹簧让卡片从 1.0 平滑过渡到 1.03，
                     * 避免之前直接从 1.0 跳变到 1.03 引起的闪烁。
                     */
                    val dragScale by animateFloatAsState(
                        targetValue = if (isDragging) 1.03f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "dragScale",
                    )

                    /*
                     * 拖拽时的阴影动画 —— 从 0dp 平滑过渡到 8dp，
                     * shadowElevation 在 graphicsLayer 中使用像素值。
                     */
                    val dragElevationPx by animateFloatAsState(
                        targetValue = if (isDragging) with(density) { 8.dp.toPx() } else 0f,
                        animationSpec = tween(150),
                        label = "dragElevation",
                    )

                    /*
                     * 被拖拽的主任务（有子任务时）：渲染为分组 Column。
                     * 不依赖 subsRemoved，整个拖拽期间都渲染 Column，
                     * 子任务区域通过 AnimatedVisibility 控制收缩动画。
                     */
                    val showGroup = isDragging && draggedSubItems.isNotEmpty() && flatItem.task.id == draggedGroupId

                    Box(
                        modifier = Modifier
                            .then(spacingModifier)
                            .then(if (isSubDuringDrag) Modifier.height(0.dp) else Modifier)
                            .then(
                                Modifier.graphicsLayer {
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    shadowElevation = dragElevationPx
                                    alpha = if (isSubDuringDrag) 0f else 1f
                                    /* 拖拽时使用圆角形状，让阴影也跟随圆角而非默认的直角矩形 */
                                    shape = dragCardShape
                                    clip = isDragging || dragElevationPx > 0f
                                }
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

                                    /*
                                     * 保存子任务数据供拖拽时渲染分组卡片使用。
                                     * 不从列表移除——移除在 onMove 首次触发时执行。
                                     */
                                    val subIds = descendantIds.toSet()
                                    draggedSubItems = reorderableItems.filter { it.task.id in subIds }
                                },
                                onDragStopped = {
                                    if (wasMoved) {
                                        val draggedId = draggedGroupId ?: flatItem.task.id
                                        val mainIdx = reorderableItems.indexOfFirst { it.task.id == draggedId }
                                        if (mainIdx >= 0 && draggedGroupId != null && preCollapseGroupIds.size > 1) {
                                            val subIds = preCollapseGroupIds.toSet() - draggedId
                                            val subs = viewModel.flatTodoTasks.filter { it.task.id in subIds }
                                            reorderableItems.addAll(mainIdx + 1, subs)
                                        }
                                        handleDrop(reorderableItems, draggedId, viewModel)
                                    } else {
                                        /* 长按未拖动 → 进入选择模式 */
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
                                            reorderableItems.clear()
                                            reorderableItems.addAll(fresh)
                                        }
                                    }
                                    longPressedTaskId = null
                                    draggedGroupId = null
                                    draggedSubItems = emptyList()
                                    preCollapseGroupIds = emptyList()
                                    subsRemoved = false
                                },
                            )
                    ) {
                        if (showGroup) {
                            /*
                             * 拖拽浮起的分组卡片：主任务 + 子任务合为 Column。
                             * Column 添加圆角背景填充 graphicsLayer 裁剪后露出的圆角区域，
                             * 子任务区域用 AnimatedVisibility 实现收缩动画。
                             */
                            Column(
                                modifier = Modifier.background(
                                    MaterialTheme.colorScheme.surfaceContainerLowest,
                                    dragCardShape,
                                )
                            ) {
                                FlatTaskRow(
                                    item = displayItem.copy(isGroupLast = false),
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
                                    isSelected = false,
                                    isGroupSelected = false,
                                )
                                /* 子任务区域：长按时展开显示，手指移动后（subsRemoved=true）
                                 * 向上收缩+淡出，模拟子任务"收进"主任务的效果 */
                                AnimatedVisibility(
                                    visible = !subsRemoved,
                                    exit = shrinkVertically(
                                        shrinkTowards = Alignment.Top,
                                        animationSpec = tween(200),
                                    ) + fadeOut(tween(150)),
                                ) {
                                    Column {
                                        draggedSubItems.forEachIndexed { index, subItem ->
                                            val isSubLast = index == draggedSubItems.lastIndex
                                            FlatTaskRow(
                                                item = subItem.copy(isGroupFirst = false, isGroupLast = isSubLast),
                                                onToggleDone = onToggleDone,
                                                onClick = { clickedTask ->
                                                    if (longPressedTaskId == flatItem.task.id) return@FlatTaskRow
                                                    if (isSelectionMode) viewModel.toggleSelection(clickedTask.id)
                                                    else onTaskClick(clickedTask)
                                                },
                                                isSelected = false,
                                                isGroupSelected = false,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
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

/**
 * 处理拖拽释放后的层级和排序逻辑。
 *
 * 核心规则：
 * - 主任务（depth=0）拖拽后始终保持为主任务，不会变成其他任务的子任务
 * - 子任务（depth>0）根据放置位置决定新的 parent_id：
 *   - 放在主任务下方 → 成为该主任务的子任务
 *   - 放在另一个子任务下方 → 继承该子任务的 parent_id
 *   - 放在列表顶部或两个主任务之间 → 提升为主任务
 *
 * @param reorderableItems 拖拽完成后的扁平列表（已包含位置调整）
 * @param draggedId 被拖拽项的 ID
 * @param viewModel TaskViewModel 实例，用于调用 moveAndReorderTasks
 */
private fun handleDrop(
    reorderableItems: List<FlatTaskItem>,
    draggedId: String,
    viewModel: TaskViewModel,
) {
    val currentIdx = reorderableItems.indexOfFirst { it.task.id == draggedId }
    if (currentIdx == -1) return
    val draggedItem = reorderableItems[currentIdx]

    /* 计算拖拽后的新父任务 ID */
    val newParentId: String? = if (currentIdx == 0) {
        /* 放在列表最顶部，一定是主任务 */
        null
    } else if (draggedItem.depth == 0) {
        /* 关键修复：主任务拖拽后始终保持为主任务，不会变为子任务。
         * 无论它被放到哪个位置（主任务下方、子任务下方），都保持 parent_id=null。
         * 这修复了"主任务A挪到带子任务b1的主任务B下方时，A错误地变为B的子任务"的 bug。 */
        null
    } else {
        /* 子任务的层级判定：根据上方元素的 depth 和 parentId 决定归属 */
        val aboveItem = reorderableItems[currentIdx - 1]
        val belowIsTopLevel = currentIdx + 1 >= reorderableItems.size || reorderableItems[currentIdx + 1].depth == 0
        when {
            /* 上方是主任务且下方也是主任务级别 → 提升为主任务 */
            aboveItem.depth == 0 && belowIsTopLevel -> null
            /* 上方是主任务 → 成为该主任务的子任务 */
            aboveItem.depth == 0 -> aboveItem.task.id
            /* 上方是子任务 → 继承其 parent_id */
            else -> aboveItem.parentId
        }
    }

    /* 收集同级兄弟 ID 列表，用于批量更新 sort_order */
    val siblingIds = when (newParentId) {
        null -> {
            /* 主任务层级：收集所有 depth=0 的项 + 被拖拽项本身 */
            val result = mutableListOf<String>()
            for (item in reorderableItems) {
                if (item.task.id == draggedId) {
                    result.add(item.task.id)
                } else if (item.depth == 0) {
                    result.add(item.task.id)
                }
            }
            result
        }
        else -> {
            /* 子任务层级：收集目标父任务下所有同 parent_id 的子任务 + 被拖拽项 */
            val parentIdx = reorderableItems.indexOfFirst { it.task.id == newParentId }
            if (parentIdx < 0) {
                /* 父任务未找到时的回退逻辑：按主任务层级处理 */
                val result = mutableListOf<String>()
                for (item in reorderableItems) {
                    if (item.task.id == draggedId) result.add(item.task.id)
                    else if (item.depth == 0) result.add(item.task.id)
                }
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
                result
            }
        }
    }

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
