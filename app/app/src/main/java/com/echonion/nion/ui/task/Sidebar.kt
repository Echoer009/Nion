package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 侧边栏内容 —— 显示清单列表，支持切换、新建、删除、拖拽排序。
 *
 * @param checklists 用户创建的真实清单列表（不含虚拟清单）
 * @param activeChecklistId 当前选中的清单 ID（可能是虚拟 ID：TODAY_ID、INBOX_ID）
 * @param defaultCounts "今天"视图的任务/子任务计数
 * @param inboxCounts "收集箱"视图的任务/子任务计数（孤儿任务）
 * @param customCounts 各真实清单的任务/子任务计数，key 为清单 ID
 * @param onSelectChecklist 点击清单项时触发，回调传入清单 ID（虚拟或真实）
 * @param onAddChecklist 新建清单时触发，回调传入清单名称
 * @param onDeleteChecklist 删除清单时触发，回调传入清单 ID
 * @param onReorderChecklists 拖拽排序结束时触发，回调传入重排后的清单 ID 列表
 * @param onSidebarDrag 侧边栏拖拽中回调，传入水平偏移量
 * @param onSidebarDragStopped 侧边栏拖拽结束回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarContent(
    checklists: List<ChecklistItem>,
    activeChecklistId: String?,
    defaultCounts: Pair<Int, Int>,
    inboxCounts: Pair<Int, Int>,
    customCounts: Map<String, Pair<Int, Int>>,
    onSelectChecklist: (String?) -> Unit,
    onAddChecklist: (String) -> Unit,
    onDeleteChecklist: (String) -> Unit,
    onReorderChecklists: (List<String>) -> Unit,
    onSidebarDrag: (Float) -> Unit,
    onSidebarDragStopped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val reorderableChecklists = remember { mutableStateListOf<ChecklistItem>() }

    LaunchedEffect(isAdding) {
        if (isAdding) {
            kotlinx.coroutines.delay(100)
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(checklists) {
        if (reorderableChecklists.isEmpty() || reorderableChecklists.size != checklists.size ||
            reorderableChecklists.zip(checklists).any { (a, b) -> a != b }) {
            reorderableChecklists.clear()
            reorderableChecklists.addAll(checklists)
        }
    }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var wasMoved by remember { mutableStateOf(false) }

    /**
     * 固定项数量（"今天" + "收集箱"），用于计算拖拽排序的索引偏移。
     * 固定项不参与拖拽排序，位于 LazyColumn 列表头部。
     */
    val headerCount = 2

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            wasMoved = true
            val fromIdx = from.index - headerCount
            val toIdx = to.index - headerCount
            if (fromIdx in reorderableChecklists.indices && toIdx in reorderableChecklists.indices) {
                reorderableChecklists.add(toIdx, reorderableChecklists.removeAt(fromIdx))
            }
        },
    )

    Surface(
        modifier = modifier.draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> onSidebarDrag(delta) },
            onDragStopped = { onSidebarDragStopped() },
        ),
        shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp, horizontal = 16.dp),
        ) {
            Text(
                "清单",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(20.dp))

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                item(key = "today") {
                    SidebarChecklistItem(
                        name = "今天",
                        taskCount = defaultCounts.first,
                        subtaskCount = defaultCounts.second,
                        isActive = activeChecklistId == TaskViewModel.TODAY_ID,
                        onClick = { onSelectChecklist(TaskViewModel.TODAY_ID) },
                        showDelete = false,
                        onDelete = {},
                    )
                }
                /**
                 * "收集箱"固定项 —— 显示所有未分配清单的孤儿任务（category_id = null）。
                 * 不可删除，不可拖拽排序，始终排在"今天"之后。
                 */
                item(key = "inbox") {
                    SidebarChecklistItem(
                        name = "收集箱",
                        taskCount = inboxCounts.first,
                        subtaskCount = inboxCounts.second,
                        isActive = activeChecklistId == TaskViewModel.INBOX_ID,
                        onClick = { onSelectChecklist(TaskViewModel.INBOX_ID) },
                        showDelete = false,
                        onDelete = {},
                    )
                }
                items(
                    items = reorderableChecklists,
                    key = { it.id },
                ) { checklist ->
                    val counts = customCounts[checklist.id] ?: Pair(0, 0)
                    ReorderableItem(state = reorderableState, key = checklist.id) { isDragging ->
                        val cardShape = remember { RoundedCornerShape(12.dp) }
                        Box(
                            modifier = Modifier
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        wasMoved = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        if (wasMoved) {
                                            onReorderChecklists(reorderableChecklists.map { it.id })
                                        }
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
                                .zIndex(if (isDragging) 1f else 0f)
                        ) {
                            SidebarChecklistItem(
                                name = checklist.name,
                                taskCount = counts.first,
                                subtaskCount = counts.second,
                                isActive = checklist.id == activeChecklistId,
                                onClick = { onSelectChecklist(checklist.id) },
                                showDelete = true,
                                onDelete = { deleteConfirmId = checklist.id },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isAdding,
                enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(200)) + fadeIn(tween(150)),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(150)) + fadeOut(tween(100)),
            ) {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                "清单名称",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (newName.isNotBlank()) {
                                    onAddChecklist(newName.trim())
                                    newName = ""
                                }
                                isAdding = false
                            },
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (newName.isNotBlank()) {
                                        onAddChecklist(newName.trim())
                                        newName = ""
                                    }
                                    isAdding = false
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "确认",
                                    tint = if (newName.isNotBlank())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            AnimatedVisibility(
                visible = !isAdding,
                enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(200)) + fadeIn(tween(150)),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(150)) + fadeOut(tween(100)),
            ) {
                Surface(
                    onClick = { isAdding = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "新建清单",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    deleteConfirmId?.let { id ->
        val checklistName = checklists.find { it.id == id }?.name ?: ""
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除清单", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要删除「$checklistName」吗？清单下的所有任务也会被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteChecklist(id)
                        deleteConfirmId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("删除", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SidebarChecklistItem(
    name: String,
    taskCount: Int,
    subtaskCount: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    showDelete: Boolean,
    onDelete: () -> Unit,
) {
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainer
    val bgColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primaryContainer else inactiveColor,
        animationSpec = tween(200),
        label = "sidebarItemBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "sidebarItemText",
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(start = if (isActive) 10.dp else 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (taskCount > 0 || subtaskCount > 0) {
                    Text(
                        "${taskCount}任务 · ${subtaskCount}子任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.6f),
                    )
                } else {
                    Text(
                        "空清单",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.35f),
                    )
                }
            }
            if (showDelete) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "删除",
                        tint = contentColor.copy(alpha = 0.35f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
