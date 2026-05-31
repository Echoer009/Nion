package com.echonion.nion.ui.components

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.runtime.setValue

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.echonion.nion.ui.task.FlatTaskItem
import com.echonion.nion.ui.task.FlatTaskRow
import com.echonion.nion.ui.task.TaskItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import uniffi.nion_core.OverdueDailyTask

/**
 * 共享任务列表 —— LazyColumn + 拖拽排序。
 *
 * 参数化的可复用组件，不依赖 TaskViewModel，通过外部参数驱动。
 *
 * @param todoItems 待办任务的扁平列表，display order
 * @param doneItems 已完成任务的扁平列表，display order
 * @param overdueTasks 过期每日任务列表，仅需展示时传入非空
 * @param onToggleDone 点击勾选框切换任务完成状态时触发
 * @param onTaskClick 点击任务行（非选择模式）时触发
 * @param onToggleOverdueDailyDone 完成过期每日任务时触发，传入 (taskId, date, done)
 * @param onToggleSelection 切换单任务选中状态时触发
 * @param reorderCallback 拖拽释放后决定层级和排序回调，传入 (draggedId, newParentId, siblingIds)
 * @param isSelectionMode 是否处于多选模式
 * @param selectedIds 当前选中的任务 ID 集合
 * @param taskSharedModifier 为每个任务卡片生成 sharedElement modifier 的函数，用于详情展开 morph 动画
 * @param listState 外部传入的列表滚动状态，提升到外层以保留跨过渡的滚动位置
 * @param innerPadding Scaffold 传入的内边距，由调用方决定
 * @param modifier 传给 LazyColumn 的 modifier
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SharedTaskList(
    todoItems: List<FlatTaskItem>,
    doneItems: List<FlatTaskItem>,
    overdueTasks: List<OverdueDailyTask> = emptyList(),
    onToggleDone: (TaskItem) -> Unit = {},
    onTaskClick: (TaskItem) -> Unit = {},
    onToggleOverdueDailyDone: (taskId: String, date: String, done: Boolean) -> Unit = { _, _, _ -> },
    onToggleSelection: (String) -> Unit = {},
    reorderCallback: (draggedId: String, newParentId: String?, siblingIds: List<String>) -> Unit = { _, _, _ -> },
    isSelectionMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    taskSharedModifier: @Composable (String) -> Modifier = { Modifier },
    /** 外部传入的可重排项列表，提升到 AnimatedContent 外以保留跨过渡的拖拽状态 */
    reorderableItems: SnapshotStateList<FlatTaskItem> = remember { mutableStateListOf() },
    /** 外部传入的列表滚动状态，提升到 AnimatedContent 外以保留跨过渡的滚动位置 */
    listState: LazyListState = rememberLazyListState(),
    innerPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    /**
     * todoItems 的最新值包装为 State。
     * longPressDraggableHandle 的 pointerInput 协程仅在 key 变化时重启，
     * onDragStopped 闭包可能捕获到旧的 todoItems 参数值。
     * 通过 rememberUpdatedState 创建的 State 对象引用不变、.value 始终是最新的，
     * 即使闭包过期也能读到当前 todoItems，避免拖拽后长按导致 reorderableItems 被回滚。
     */
    val currentTodoItems by rememberUpdatedState(todoItems)

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

    /**
     * 拖拽异步写库中间态标志。
     * onDragStopped 调用 handleDrop（launch coroutine）前设为 true，
     * 表示"拖拽已完成但异步 moveAndReorderTasks 尚未写回新顺序"，
     * 此时 todoItems 还是旧顺序而 reorderableItems 已是拖拽后顺序，需要跳过同步避免回滚。
     * 每次 remember(todoItems) 触发时消费此标志（置 false），
     * 之后若再次收到同 ID 不同顺序的 todoItems，说明是外部排序（如 AI 工具），需正常同步。
     */
    var pendingDragReorder by remember { mutableStateOf(false) }

     /**
      * 同步 todoItems 到 reorderableItems — 使用 remember(todoItems) 在 composition 阶段同步执行，
      * 确保 reorderableItems 与 todoItems 在同一帧内一致，避免 todo/done 区出现重复 key 导致闪退。
      *
      * 增量 diff（只移除离开的、只添加新来的）避免 clear()+addAll() 导致的全部 item 闪动。
      * 拖拽期间（draggedGroupId != null）跳过同步，由 onDragStopped 回调负责最终同步。
      *
      * 当 ID 集合相同但顺序不同时分两种情况：
      * - pendingDragReorder == true：拖拽异步中间态，跳过避免回滚，并消费标志
      * - pendingDragReorder == false：外部排序（如 AI 工具），正常同步顺序
      * 内容同步（depth、parentId、isGroupLast 等字段）始终执行，确保元数据及时更新。
      */
     remember(todoItems) {
         if (draggedGroupId == null) {
             val newIds = todoItems.map { it.task.id }
             val currentIds = reorderableItems.map { it.task.id }
             if (newIds != currentIds) {
                 val newIdSet = newIds.toSet()
                 val currentIdSet = currentIds.toSet()
                 if (newIdSet == currentIdSet) {
                     if (pendingDragReorder) {
                         /* 拖拽异步中间态：reorderableItems 是拖拽后顺序，todoItems 还是旧顺序。
                          * 跳过重排避免回滚，消费标志，等异步完成后 todoItems 更新为新顺序再同步。 */
                         pendingDragReorder = false
                     } else {
                         /* 外部排序（如 AI 操作工具）：todoItems 已是新顺序，同步到 reorderableItems */
                         val targetOrder = newIds.withIndex().associate { (i, id) -> id to i }
                         reorderableItems.sortBy { targetOrder[it.task.id] ?: Int.MAX_VALUE }
                     }
                 } else {
                     /* ID 集合不同（有新增或删除）—— 全量同步 */
                     reorderableItems.removeAll { it.task.id !in newIds }
                     for ((idx, item) in todoItems.withIndex()) {
                         if (item.task.id !in currentIds) {
                             reorderableItems.add(idx.coerceAtMost(reorderableItems.size), item)
                         }
                     }
                     val targetOrder = newIds.withIndex().associate { (i, id) -> id to i }
                     reorderableItems.sortBy { targetOrder[it.task.id] ?: Int.MAX_VALUE }
                 }
             }
             /* 内容同步始终执行：更新 depth、parentId、isGroupLast 等字段。
              * 即使跳过了重排，也需要将 todoItems 中已更新的元数据同步到 reorderableItems，
              * 确保 UI（间距、缩进等）及时反映最新状态。 */
             for (i in todoItems.indices) {
                 val newItem = todoItems[i]
                 val currentIdx = reorderableItems.indexOfFirst { it.task.id == newItem.task.id }
                 if (currentIdx >= 0 && reorderableItems[currentIdx] != newItem) {
                     reorderableItems[currentIdx] = newItem
                 }
             }
         }
         true
     }

    /* 动态计算 reorderable items 之前的 header item 数量，
     * 用于将 LazyColumn 绝对索引转换为 reorderableItems 相对索引。
     * 结构：top_spacer(1) + [overdue_header + overdue items] + [todo_header] */
    val headerCount = 1 +
        (if (overdueTasks.isNotEmpty()) 1 + overdueTasks.size else 0) +
        if (reorderableItems.isNotEmpty()) 1 else 0

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
        modifier = modifier
            .fillMaxWidth()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "top_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(8.dp)) }

        // ==================== 过期每日任务分区 ====================
        if (overdueTasks.isNotEmpty()) {
            item(key = "overdue_header", contentType = "header") {
                SectionHeader("过期", overdueTasks.size, isError = true)
            }
            items(
                items = overdueTasks,
                key = { "overdue_${it.task.id}_${it.overdueDate}" },
                contentType = { "overdue_task" },
            ) { overdue ->
                OverdueDailyTaskRow(
                    overdue = overdue,
                    onComplete = { onToggleOverdueDailyDone(overdue.task.id, overdue.overdueDate, false) },
                )
            }
        }

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
                    // else 分支中 parentId 已由上方 if 判定为非空
                    val mainId = itemToMainId[flatItem.task.id] ?: flatItem.parentId
                    val groupIds = groupIdsMap[mainId] ?: setOf(mainId)
                    groupIds.all { it in selectedIds }
                }

                /* 统一用 isGroupFirst 的 top=8dp 作为组间距；
                 * 不再使用 isGroupLast 的 bottom padding，避免"有子任务组"与
                 * "无子任务组"相邻时出现 16dp 双重间距 */
                val spacingModifier = when {
                    isInDraggedGroup && !subsRemoved -> Modifier
                    displayItem.isGroupFirst -> Modifier.padding(top = 8.dp)
                    else -> Modifier
                }

                /* 长按未移动时（!subsRemoved）隐藏原始子任务项，避免与分组卡片重复渲染；
                 * 开始移动后子任务已从列表移除，无需隐藏 */
                val isSubDuringDrag = !subsRemoved && draggedGroupId != null && isInDraggedGroup && flatItem.task.id != draggedGroupId

                ReorderableItem(
                    state = reorderableState,
                    key = flatItem.task.id,
                    /* 完成动画：StiffnessLow 慢速 + DampingRatioMedium（0.6）微弱回弹，
                     * 既柔和不闪烁，又不会弹过头。拖拽中的卡片跳过位移动画。 */
                    animateItemModifier = if (!isInDraggedGroup) Modifier.animateItem(
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    ) else Modifier,
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
                                            /* 用 currentTodoItems 避免闭包过期读到旧值 */
                                            val subs = currentTodoItems.filter { it.task.id in subIds }
                                            reorderableItems.addAll(mainIdx + 1, subs)
                                        }
                                        /* 标记拖拽异步中间态，防止 remember(todoItems) 在异步完成前
                                         * 将 reorderableItems 的拖拽后顺序回滚为 todoItems 的旧顺序 */
                                        pendingDragReorder = true
                                        handleDrop(reorderableItems, draggedId, reorderCallback)
                                    } else {
                                        /* 长按未拖动 → 进入选择模式 */
                                        if (preCollapseGroupIds.size > 1) {
                                            val groupIds = preCollapseGroupIds
                                            val allSelected = groupIds.all { it in selectedIds }
                                            for (id in groupIds) {
                                                if (allSelected) {
                                                    if (id in selectedIds) onToggleSelection(id)
                                                } else {
                                                    if (id !in selectedIds) onToggleSelection(id)
                                                }
                                            }
                                        } else {
                                            onToggleSelection(flatItem.task.id)
                                        }
                                        /* 用 rememberUpdatedState 包装的 currentTodoItems，
                                         * 确保闭包过期时仍能读到最新的 todoItems 值，
                                         * 避免 pointerInput 协程未重启导致强制同步回滚拖拽后的顺序 */
                                        val fresh = currentTodoItems
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
                                                        if (id in selectedIds) onToggleSelection(id)
                                                    } else {
                                                        if (id !in selectedIds) onToggleSelection(id)
                                                    }
                                                }
                                            } else {
                                                onToggleSelection(clickedTask.id)
                                            }
                                        } else {
                                            onTaskClick(clickedTask)
                                        }
                                    },
                                    isSelected = false,
                                    isGroupSelected = false,
                                    sharedElementModifier = taskSharedModifier(flatItem.task.id),
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
                                                    if (isSelectionMode) onToggleSelection(clickedTask.id)
                                                    else onTaskClick(clickedTask)
                                                },
                                                isSelected = false,
                                                isGroupSelected = false,
                                                sharedElementModifier = taskSharedModifier(subItem.task.id),
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
                                                    if (id in selectedIds) onToggleSelection(id)
                                                } else {
                                                    if (id !in selectedIds) onToggleSelection(id)
                                                }
                                            }
                                        } else {
                                            onToggleSelection(clickedTask.id)
                                        }
                                    } else {
                                        onTaskClick(clickedTask)
                                    }
                                },
                                isSelected = isSelected,
                                isGroupSelected = groupSelected,
                                sharedElementModifier = taskSharedModifier(flatItem.task.id),
                            )
                        }
                    }
                }
            }
        }

        if (doneItems.isNotEmpty()) {
            item(key = "done_header", contentType = "header") { SectionHeader("已完成", doneItems.size) }

            items(
                items = doneItems,
                key = { it.task.id },
                contentType = { if (it.depth == 0) "done_main" else "done_sub" },
            ) { flatItem ->
                val isSelected = flatItem.task.id in selectedIds
                val cardShape = remember { RoundedCornerShape(16.dp) }
                    /* 已完成区使用与待办区相同的慢速弹簧，确保从待办区滑入已完成区的动画速度一致 */
                    Box(
                        modifier = Modifier
                            .animateItem(
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow,
                                ),
                            )
                        .padding(vertical = 4.dp)
                        .then(
                            if (isSelected) Modifier.border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                cardShape,
                            ) else Modifier
                        )
                ) {
                    FlatTaskRow(
                        item = flatItem,
                        onToggleDone = onToggleDone,
                        onClick = { clickedTask ->
                            if (isSelectionMode) {
                                onToggleSelection(clickedTask.id)
                            } else {
                                onTaskClick(clickedTask)
                            }
                        },
                        isSelected = isSelected,
                        isGroupSelected = isSelected,
                        sharedElementModifier = taskSharedModifier(flatItem.task.id),
                    )
                }
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(88.dp)) }
    }
}

/**
 * 向上扫描找到指定深度的最近祖先项 ID。
 *
 * 规则3（保持原有深度）的核心辅助函数。
 * 被拖拽项保持其原始 depth，需要找到 depth-1 的最近祖先作为新父任务。
 *
 * 为防止跨子树误匹配（例如从 A 的子树扫描到 B 的子树中），
 * 搜索范围限定在当前位置所属的 depth=0 根节点之内：
 *   1. 先向后扫描找到最近的 depth=0 根节点
 *   2. 在根节点和当前位置之间搜索 targetDepth 的最近项
 *   3. 如果精确深度找不到祖先（例如拖到没有子任务的根节点后面），降级返回根节点
 *
 * @param items 扁平化的任务项列表
 * @param fromIndex 被拖拽项在列表中的当前索引
 * @param targetDepth 目标祖先的深度（通常是被拖拽项的 depth - 1）
 * @return 目标祖先的任务 ID，targetDepth < 0 时返回 null（顶级任务保持顶级）
 */
private fun findAncestorAtDepth(
    items: List<FlatTaskItem>,
    fromIndex: Int,
    targetDepth: Int,
): String? {
    if (targetDepth < 0) return null

    /* 定位当前位置所属的 depth=0 根节点索引 */
    var rootIdx = -1
    for (i in fromIndex - 1 downTo 0) {
        if (items[i].depth == 0) {
            rootIdx = i
            break
        }
    }

    /* 在根节点到当前位置之间搜索目标深度的最近祖先 */
    for (i in fromIndex - 1 downTo (rootIdx + 1)) {
        if (items[i].depth == targetDepth) {
            return items[i].task.id
        }
    }

    /* 精确深度未找到 → 降级返回根节点（被拖拽项的深度会自动调整） */
    if (rootIdx >= 0) return items[rootIdx].task.id

    return null
}

/**
 * 从被拖拽项位置向后扫描，跳过其所有后代，找到第一个非后代项。
 *
 * 拖拽释放时子任务已重新插入列表并紧跟在被拖拽项后面，
 * 直接取 currentIdx+1 会拿到自己的子任务而非上下文中的真正下方项。
 * 本函数跳过所有后代，返回"真实的下方项"供规则2判断父子边界。
 *
 * @param items 扁平化的任务项列表
 * @param draggedIdx 被拖拽项在列表中的索引
 * @param descendantIds 被拖拽项的所有后代任务 ID 集合（由 collectDescendantIds 生成）
 * @return 第一个非后代项，如果后面全是后代或到达列表末尾则返回 null
 */
private fun findFirstNonDescendantBelow(
    items: List<FlatTaskItem>,
    draggedIdx: Int,
    descendantIds: Set<String>,
): FlatTaskItem? {
    var idx = draggedIdx + 1
    while (idx < items.size && items[idx].task.id in descendantIds) {
        idx++
    }
    return items.getOrNull(idx)
}

/**
 * 递归收集扁平列表中以 parentId 为根的所有后代任务 ID。
 *
 * @param items 扁平化的任务项列表
 * @param parentId 父任务 ID
 * @return 所有后代（子、孙、曾孙……）的任务 ID 集合
 */
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
 * 拖拽释放后计算被拖拽项的新层级关系和同级排序。
 *
 * 层级判定规则（优先级从高到低）：
 *
 *   规则1 — 列表位置 0 → 晋升为顶级任务（parent_id = null）
 *   规则2 — 放在父子之间（above.depth < realBelow.depth）→ 变成上方项的子任务
 *   规则3 — 被拖入更深子树内部（两侧 depth 都 > 自身 depth）→ 自动降级加入子树
 *   规则4 — 其他所有情况 → 保持原有深度，向上查找目标深度的祖先作为新父任务
 *
 * "规则2 — 父子之间"指上方是某个父任务、下方紧跟其子任务的位置，
 * 即用户明确将任务拖入了某个子树的入口。
 *
 * "规则4 — 保持原有深度"的实现：
 *   被拖拽项保持其拖拽前的 depth，从当前位置向上扫描找到 depth-1 的最近祖先，
 *   以该项作为新父任务。扫描范围限制在当前子树（最近的 depth=0 根节点）内，
 *   防止跨子树误匹配。如果精确深度找不到祖先，降级返回根节点（深度自动调整）。
 *
 * 正确覆盖全部场景：
 *   1. "a1x(d=2) 拖到 a2(d=1) 和 B(d=0) 之间" → 规则4，保持 d=2，找 d=1 祖先 → a2 的子任务 ✓
 *   2. "a1(d=1) 拖到 B(d=0) 后面"           → 规则4，保持 d=1，找 d=0 祖先 → B 的子任务 ✓
 *   3. "a2(d=1) 拖到 A(d=0) 和 a1(d=1) 之间" → 规则2，父子边界 → A 的子任务 ✓
 *   4. "a1x(d=2) 拖到列表顶部"              → 规则1 → 顶级任务 ✓
 *   5. "a1(d=1) ↔ a2(d=1) 之间"             → 规则4，保持 d=1，祖先都是 A → 纯排序 ✓
 *   6. "a(d=0) 放到 b(d=1) 和 c(d=1) 之间"  → 规则3，两侧都 d>0 → 自动降级为子树根的子任务 ✓
 *   7. "a(d=0) 放到 c(d=1) 后面"            → 规则4，realBelow=null → 保持顶级 ✓
 *
 * 注意：拖拽开始时子任务已被收进父任务卡片，释放后才重新插入列表，
 * 所以 handleDrop 检测规则2时需要跳过这些重插入的后代，找到"真实的下方项"。
 *
 * @param reorderableItems 拖拽完成后的扁平列表（子任务已重新插入）
 * @param draggedId 被拖拽项的 ID
 * @param reorderCallback 层级排序回调，传入 (draggedId, newParentId, siblingIds)
 */
private fun handleDrop(
    reorderableItems: List<FlatTaskItem>,
    draggedId: String,
    reorderCallback: (draggedId: String, newParentId: String?, siblingIds: List<String>) -> Unit,
) {
    val currentIdx = reorderableItems.indexOfFirst { it.task.id == draggedId }
    if (currentIdx == -1) return
    val draggedItem = reorderableItems[currentIdx]

    /*
     * 收集被拖拽项的所有后代 ID。
     * 释放时子任务已重新插入列表（排在拖拽项后面），需要跳过这些后代
     * 才能找到"真实的下方项"来判断是否处于父子边界。
     */
    val descendantIds = collectDescendantIds(reorderableItems, draggedId)

    val newParentId: String? = if (currentIdx == 0) {
        /* 规则1: 拖到列表顶部 → 无条件变为顶级任务 */
        null
    } else {
        val aboveItem = reorderableItems[currentIdx - 1]
        /* 跳过被拖拽项自身的后代，找到真正的下方项 */
        val realBelowItem = findFirstNonDescendantBelow(reorderableItems, currentIdx, descendantIds)

        if (realBelowItem != null && aboveItem.depth < realBelowItem.depth) {
            /* 规则2: 放在父子之间（子树入口）→ 变成上方项的子任务 */
            aboveItem.task.id
        } else if (aboveItem.depth > draggedItem.depth
            && realBelowItem != null && realBelowItem.depth > draggedItem.depth
        ) {
            /* 规则3: 被拖入更深的子树内部（两侧 depth 都大于自身）→ 自动降级，
             * 找到同层的最近祖先（即该深层区域的父任务）作为新 parent。
             * 例：d=0 任务放到两个 d=1 之间 → 加入 d=0 根节点的子树
             * 例：d=2 任务放到两个 d=3 之间 → 加入 d=2 节点的子树 */
            findAncestorAtDepth(reorderableItems, currentIdx, draggedItem.depth)
        } else {
            /* 规则4: 保持原有深度，向上查找 depth-1 的祖先作为新父任务 */
            findAncestorAtDepth(reorderableItems, currentIdx, draggedItem.depth - 1)
        }
    }

    /*
     * 收集同级兄弟 ID 列表，用于批量更新 sort_order。
     *
     * 遍历整个扁平列表（保持拖拽后的视觉顺序），收集两类项：
     * 1. 被拖拽项本身（无论其原有的 parentId 是什么，因为 DB 尚未更新）
     * 2. 新父任务下已有的直接子任务（parentId == newParentId）
     *
     * 使用 else-if 避免拖拽项被重复收集（拖拽项的 parentId 可能也等于 newParentId
     * 如果是主任务且 newParentId==null，则 parentId==null 也匹配）。
     * 遍历全列表而非分段截断：因为拖拽后 depth 可能不反映新的层级关系。
     */
    val siblingIds = buildList {
        for (item in reorderableItems) {
            if (item.task.id == draggedId) {
                add(item.task.id)
            } else if (newParentId == null && item.parentId == null) {
                /* 主任务层级：收集所有已有顶级任务 */
                add(item.task.id)
            } else if (newParentId != null && item.parentId == newParentId) {
                /* 子任务层级：收集该父任务下已有的直接子任务 */
                add(item.task.id)
            }
        }
    }

    reorderCallback(draggedId, newParentId, siblingIds)
}

/**
 * 分区标题 —— 在 LazyColumn 中作为 sticky-header 使用。
 *
 * @param title 标题文本，如"待办""已完成""过期"
 * @param count 该分区的条目数量
 * @param isError 是否使用错误色（如"过期"分区）
 */
@Composable
fun SectionHeader(title: String, count: Int, isError: Boolean = false) {
    // 分区标题：普通分区使用 secondary（信息性），错误分区保持 error
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val onContainerColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Surface(shape = CircleShape, color = containerColor) {
            Text(
                "$count",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = onContainerColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 过期每日任务行 —— 显示任务名 + 过期日期 + 完成按钮。
 * 用于"今天"视图顶部的过期分区。
 *
 * @param overdue 过期的每日任务数据
 * @param onComplete 点击完成按钮时触发
 */
@Composable
fun OverdueDailyTaskRow(
    overdue: OverdueDailyTask,
    onComplete: () -> Unit,
) {
    // 将 "2026-05-24" 格式化为 "5月24日"
    val formattedDate = runCatching {
        val date = java.time.LocalDate.parse(overdue.overdueDate)
        "${date.monthValue}月${date.dayOfMonth}日"
    }.getOrElse { overdue.overdueDate }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = NionAlpha.BG_SUBTLE),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 完成按钮：简洁圆形，不带外圈边框，避免大环套小环
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onComplete,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = "完成",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = overdue.task.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
