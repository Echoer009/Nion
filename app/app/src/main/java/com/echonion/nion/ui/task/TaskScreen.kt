package com.echonion.nion.ui.task

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import com.echonion.nion.ui.components.DualPanelState
import com.echonion.nion.ui.components.TaskDetailOverlay
import com.echonion.nion.ui.components.SharedTaskList
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 任务主屏幕 —— 显示任务列表 + FAB。
 *
 * 使用 SharedTransitionLayout 实现两套动画系统：
 * 1. FAB ↔ 添加任务表单（sharedBounds，FAD 展开/收回动画）
 * 2. 任务卡片 ↔ 任务详情浮层（sharedElement + AnimatedContent，卡片 morph 展开动画）
 *
 * 两者互斥：同一时间只能展开一个。
 *
 * @param dualState 双面板状态，控制左右侧边栏
 * @param viewModel 任务 ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TaskScreen(
    dualState: DualPanelState,
    viewModel: TaskViewModel,
    /** 用户从任务详情点击"开始专注"时触发，传入 (任务ID, 任务标题, 预选时长分钟) */
    onStartFocus: (taskId: String, taskTitle: String, durationMinutes: Int) -> Unit = { _, _, _ -> },
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var expandedTaskId by remember { mutableStateOf<String?>(null) }

    // ===== 附件相关状态 =====
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 新建任务时的临时附件列表（任务创建前暂存在内存中）
    var pendingAttachments by remember { mutableStateOf<List<AttachmentUiItem>>(emptyList()) }
    // 记录临时附件的完整信息（用于创建任务后批量写入数据库）
    var pendingAttachmentData by remember { mutableStateOf<List<PickedFileInfo>>(emptyList()) }
    // 当前查看详情的任务的附件列表
    var detailAttachments by remember { mutableStateOf<List<AttachmentUiItem>>(emptyList()) }
    // 图片预览状态：非 null 时显示全屏预览
    var previewAttachment by remember { mutableStateOf<AttachmentUiItem?>(null) }

    // 新建任务用的附件选择器
    val addAttachmentPicker = rememberAttachmentPicker { fileInfo ->
        val id = java.util.UUID.randomUUID().toString()
        val item = AttachmentUiItem(
            id = id,
            fileName = fileInfo.fileName,
            filePath = fileInfo.filePath,
            mimeType = fileInfo.mimeType,
            fileSize = fileInfo.fileSize,
            isImage = fileInfo.mimeType.startsWith("image/"),
        )
        pendingAttachments = pendingAttachments + item
        pendingAttachmentData = pendingAttachmentData + fileInfo
    }

    // 任务详情用的附件选择器
    val detailAttachmentPicker = rememberAttachmentPicker { fileInfo ->
        expandedTaskId?.let { taskId ->
            viewModel.addAttachment(taskId, fileInfo.filePath, fileInfo.fileName, fileInfo.mimeType, fileInfo.fileSize)
            // 刷新附件列表
            scope.launch {
                detailAttachments = viewModel.getAttachments(taskId)
            }
        }
    }

    // 当任务详情打开时，加载该任务的附件列表
    LaunchedEffect(expandedTaskId) {
        if (expandedTaskId != null) {
            detailAttachments = viewModel.getAttachments(expandedTaskId!!)
        } else {
            detailAttachments = emptyList()
        }
    }

    // 当新建任务浮层关闭时，清空临时附件
    LaunchedEffect(showAddSheet) {
        if (!showAddSheet) {
            pendingAttachments = emptyList()
            pendingAttachmentData = emptyList()
        }
    }

    // 提升到 AnimatedContent 外部，跨过渡保留滚动位置和拖拽状态
    val listState = rememberLazyListState()
    val reorderableItems = remember { mutableStateListOf<FlatTaskItem>() }

    // backdropBlur: 展开时模糊任务列表，收回时恢复清晰
    // 同时感知 FAD 展开和任务详情展开
    val blurPx by animateFloatAsState(
        targetValue = if (showAddSheet || expandedTaskId != null) 25f else 0f,
        animationSpec = tween(300),
        label = "backdropBlur",
    )

    /**
     * 递归从任务树中按 ID 查找任务。
     * 用于在 AnimatedContent 的 detail 分支中获取被展开任务的完整数据。
     */
    fun findTaskById(tasks: List<TaskItem>, id: String): TaskItem? {
        for (task in tasks) {
            if (task.id == id) return task
            val found = findTaskById(task.subtasks, id)
            if (found != null) return found
        }
        return null
    }

    // 任务详情 overlay 返回拦截：expandedTaskId 非空时，系统返回手势关闭详情而非退出页面
    BackHandler(enabled = expandedTaskId != null) {
        expandedTaskId = null
    }
    // 添加任务 overlay 返回拦截：showAddSheet 为 true 时，系统返回手势关闭表单而非退出页面
    BackHandler(enabled = showAddSheet) {
        showAddSheet = false
    }

    // SharedTransitionLayout：为 sharedBounds（FAB）+ sharedElement（任务卡片）两套动画提供容器
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        // AnimatedContent：任务列表 ↔ 任务详情浮层的 morph 过渡
        AnimatedContent(
            targetState = expandedTaskId,
            transitionSpec = {
                if (targetState != null) {
                    // 展开：详情浮层立即出现（不淡入），只让任务列表淡出
                    // 避免 detail 分支的 scrim 也在淡入期间透明，导致底层白色列表透出
                    (EnterTransition.None togetherWith fadeOut(tween(250, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    // 收回：任务列表快速淡入，详情浮层缓慢淡出
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "taskDetail",
        ) { taskId ->
            if (taskId != null) {
                // 任务详情浮层：从任务卡片位置 morph 展开
                val task = findTaskById(viewModel.tasks, taskId)
                if (task != null) {
                    TaskDetailOverlay(
                        task = task,
                        onDismiss = { expandedTaskId = null },
                        onDelete = { expandedTaskId = null; viewModel.deleteTask(task.id) },
                        onCreateSubtask = { title, priority -> viewModel.createSubtask(taskId, title, priority); expandedTaskId = null },
                        onUpdateNotes = { notes ->
                            viewModel.updateTask(taskId, description = notes)
                        },
                        onUpdateRecurrence = { rule, time ->
                            viewModel.updateRecurrence(taskId, rule, time)
                        },
                        onRemoveRecurrence = {
                            viewModel.removeRecurrence(taskId)
                        },
                        onUpdateReminder = { reminder ->
                            viewModel.updateReminder(taskId, reminder)
                        },
                        /** 点击开始专注：直接导航到专注页，不关闭详情浮层，避免任务列表闪现 */
                        onStartFocus = { durationMinutes ->
                            onStartFocus(task.id, task.title, durationMinutes)
                        },
                        sharedElementModifier = Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("task_detail_$taskId"),
                            animatedVisibilityScope = this@AnimatedContent,
                            boundsTransform = { _, _ ->
                                spring(
                                    dampingRatio = 0.8f,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                            },
                        ),
                        /** 附件相关参数 */
                        attachments = detailAttachments,
                        onPickImage = { detailAttachmentPicker.pickImage() },
                        onPickFile = { detailAttachmentPicker.pickFile() },
                        onRemoveAttachment = { attachmentId ->
                            viewModel.removeAttachment(attachmentId)
                            // 刷新附件列表
                            scope.launch {
                                detailAttachments = viewModel.getAttachments(taskId)
                            }
                        },
                        onPreviewImage = { previewAttachment = it },
                    )
                }
            } else {
                // 任务列表 + FAB + 添加任务浮层（原有逻辑，FAD 动画）
                Box(modifier = Modifier.fillMaxSize()) {
                    // 任务列表始终渲染，展开时施加高斯模糊
                    Box(modifier = Modifier.graphicsLayer {
                        if (blurPx > 0f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            renderEffect = android.graphics.RenderEffect
                                .createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                                .asComposeRenderEffect()
                        }
                    }) {
                        val detailAnimatedScope = this@AnimatedContent
                        TaskScreenContent(
                            dualState = dualState,
                            viewModel = viewModel,
                            onTaskClick = { expandedTaskId = it.id },
                            /**
                             * 为每个任务卡片生成 sharedElement modifier。
                             * key 为 "task_detail_${taskId}"，与 TaskDetailOverlay 中的 key 匹配，
                             * 使得 AnimatedContent 过渡时该卡片能 morph 展开为详情浮层。
                             */
                            taskSharedModifier = { targetId ->
                                Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState("task_detail_$targetId"),
                                    animatedVisibilityScope = detailAnimatedScope,
                                    boundsTransform = { _, _ ->
                                        spring(
                                            dampingRatio = 0.8f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        )
                                    },
                                )
                            },
                            fab = {
                                // FAB 通过 AnimatedVisibility 参与 shared bounds 动画
                                AnimatedVisibility(
                                    visible = !showAddSheet,
                                    enter = fadeIn(tween(250, easing = FastOutSlowInEasing)),
                                    exit = fadeOut(tween(180, easing = FastOutSlowInEasing)),
                                ) {
                                    FloatingActionButton(
                                        onClick = { showAddSheet = true },
                                        modifier = Modifier.sharedBounds(
                                            sharedContentState = rememberSharedContentState("fab"),
                                            animatedVisibilityScope = this@AnimatedVisibility,
                                            boundsTransform = { _, _ ->
                                                spring(
                                                    dampingRatio = 0.8f,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                )
                                            },
                                        ),
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, hoveredElevation = 0.dp),
                                        shape = RoundedCornerShape(16.dp),
                                    ) { Icon(Icons.Default.Add, contentDescription = "添加任务") }
                                }
                            },
                            listState = listState,
                            reorderableItems = reorderableItems,
                        )
                    } // end blur Box

                    // 添加任务浮层，叠加在任务列表之上
                    AnimatedVisibility(
                        visible = showAddSheet,
                        enter = fadeIn(tween(300, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(400, easing = FastOutSlowInEasing)),
                    ) {
                        AddTaskOverlay(
                            onDismiss = { showAddSheet = false },
                            onAdd = { title, desc, priority, recurrenceRule, recurrenceReminderTime, reminder ->
                                val pending = pendingAttachmentData.toList()
                                viewModel.createTask(
                                    title = title,
                                    description = desc,
                                    priority = priority,
                                    recurrenceRule = recurrenceRule,
                                    recurrenceReminderTime = recurrenceReminderTime,
                                ) { newTaskId ->
                                    // 任务创建成功后，批量关联临时附件
                                    if (pending.isNotEmpty()) {
                                        viewModel.commitPendingAttachments(newTaskId, pending)
                                    }
                                    // 设置一次性提醒（如果有）
                                    if (reminder != null) {
                                        viewModel.updateReminder(newTaskId, reminder)
                                    }
                                }
                                showAddSheet = false
                            },
                            sharedBoundsModifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState("fab"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    )
                                },
                            ),
                            pendingAttachments = pendingAttachments,
                            onPickImage = { addAttachmentPicker.pickImage() },
                            onPickFile = { addAttachmentPicker.pickFile() },
                            onRemoveAttachment = { id ->
                                // 从临时列表中移除
                                val idx = pendingAttachments.indexOfFirst { it.id == id }
                                if (idx >= 0) {
                                    pendingAttachments = pendingAttachments.toMutableList().also { it.removeAt(idx) }
                                    pendingAttachmentData = pendingAttachmentData.toMutableList().also { it.removeAt(idx) }
                                }
                            },
                            onPreviewImage = { previewAttachment = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 任务主界面内容 —— 顶栏 + 任务列表 + FAB。
 *
 * FAB 通过 fab 参数传入，由上层 TaskScreen 用 AnimatedVisibility + sharedBounds 包裹，
 * 任务列表始终渲染，不受 FAB 展开/收回动画影响。
 *
 * @param dualState 双面板状态
 * @param viewModel 任务 ViewModel
 * @param onTaskClick 任务点击回调，打开任务详情
 * @param taskSharedModifier 为每个任务卡片生成 sharedElement modifier 的函数，用于详情展开 morph 动画
 * @param fab FAB 组件，由上层提供（包含 sharedBounds 动画逻辑）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskScreenContent(
    dualState: DualPanelState,
    viewModel: TaskViewModel,
    onTaskClick: (TaskItem) -> Unit,
    taskSharedModifier: @Composable (String) -> Modifier,
    fab: @Composable () -> Unit,
    /** 外部传入，跨 AnimatedContent 过渡保留 */
    listState: LazyListState,
    reorderableItems: SnapshotStateList<FlatTaskItem>,
) {
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        floatingActionButton = fab,
    ) { innerPadding ->
        // 分组标签栏：选中真实清单时显示（"今天"和"收集箱"是虚拟视图，无分组）
        val showGroupBar = viewModel.activeChecklistId != null
                && viewModel.activeChecklistId != TaskViewModel.TODAY_ID
                && viewModel.activeChecklistId != TaskViewModel.INBOX_ID
        Column(modifier = Modifier.padding(innerPadding)) {
            if (showGroupBar) {
                /**
                 * 分组标签栏 —— 水平滚动的分组选择器。
                 * 显示"全部" + 各分组名称，点击切换筛选。
                 * 最右侧有"+"按钮用于新建分组。
                 */
                GroupTabBar(
                    groups = viewModel.groups,
                    activeGroupId = viewModel.activeGroupId,
                    onGroupSelected = { viewModel.setActiveGroup(it) },
                    onCreateGroup = { viewModel.createGroup(it) },
                    onDeleteGroup = { viewModel.deleteGroup(it) },
                )
            }
            if (viewModel.tasks.isEmpty()) {
                EmptyTaskView(modifier = Modifier.weight(1f))
            } else {
                SharedTaskList(
                    todoItems = viewModel.flatTodoTasks,
                    doneItems = viewModel.flatDoneTasks,
                    overdueTasks = viewModel.overdueDailyTasks,
                    onToggleDone = { viewModel.toggleDone(it) },
                    onTaskClick = onTaskClick,
                    onToggleOverdueDailyDone = { taskId, date, done -> viewModel.toggleOverdueDailyDone(taskId, date, done) },
                    onToggleSelection = { viewModel.toggleSelection(it) },
                    reorderCallback = { draggedId, newParentId, siblingIds -> viewModel.moveAndReorderTasks(draggedId, newParentId, siblingIds) },
                    isSelectionMode = viewModel.isSelectionMode,
                    selectedIds = viewModel.selectedTaskIds,
                    taskSharedModifier = taskSharedModifier,
                    reorderableItems = reorderableItems,
                    listState = listState,
                    innerPadding = androidx.compose.foundation.layout.PaddingValues(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 提醒入口行 —— 显示当前提醒时间，点击后导航到全屏 REMINDER 面板。
 *
 * 轻量级组件：仅显示 Alarm 图标 + "提醒" 文字 + 当前值（"设置提醒" / "6月15日 14:30"），
 * 点击行为由外部 onClick 回调处理（通常切换到 REMINDER 面板）。
 *
 * @param reminder 当前提醒时间，格式 "YYYY-MM-DDTHH:MM"，null 表示未设置
 * @param onClick 点击整行时触发的回调
 */
@Composable
private fun ReminderEntryRow(
    reminder: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Alarm 图标，已设置提醒时使用 primary 色
        Icon(
            Icons.Outlined.Alarm,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (reminder != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        // "提醒" 标签
        Text(
            "提醒",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        // 当前值文字：未设置显示"设置提醒"，已设置显示格式化后的时间
        Text(
            text = reminder?.let { r ->
                try {
                    val ldt = java.time.LocalDateTime.parse(
                        r, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
                    )
                    "%d月%d日 %02d:%02d".format(ldt.monthValue, ldt.dayOfMonth, ldt.hour, ldt.minute)
                } catch (_: Exception) { "已设置提醒" }
            } ?: "设置提醒",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (reminder != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 新建任务浮层的面板状态枚举：
 * - FORM：主表单（标题、描述、优先级、提醒/重复入口行）
 * - REMINDER：提醒设置面板（日期 + 时间选择）
 * - RECURRENCE：重复设置面板（不重复/每天 + 时间滚轮）
 */
private enum class AddPanel { FORM, REMINDER, RECURRENCE }

/**
 * 新建任务浮层 —— 从 FAB 位置 morph 展开为居中的任务创建表单。
 *
 * 内部使用 AnimatedContent 在三个面板之间切换（FORM / REMINDER / RECURRENCE），
 * 左右滑动动画实现面板间的视觉过渡。
 *
 * @param onDismiss 点击外部区域关闭回调
 * @param onAdd 确认添加回调，传入 (标题, 描述, 优先级, 循环规则, 提醒时间, 一次性提醒)
 * @param sharedBoundsModifier shared element 动画 modifier（与 FAB 共享 bounds）
 * @param pendingAttachments 当前暂存的附件列表
 * @param onPickImage 点击添加图片附件时触发
 * @param onPickFile 点击添加文件附件时触发
 * @param onRemoveAttachment 移除暂存附件时触发
 * @param onPreviewImage 预览图片附件时触发
 */
@Composable
private fun AddTaskOverlay(
    onDismiss: () -> Unit,
    /** 创建回调：title, description, priority, recurrenceRule, recurrenceReminderTime, reminder */
    onAdd: (String, String?, String, String?, String?, String?) -> Unit,
    sharedBoundsModifier: Modifier,
    /** 当前暂存的附件列表 */
    pendingAttachments: List<AttachmentUiItem> = emptyList(),
    /** 点击添加图片附件时触发 */
    onPickImage: () -> Unit = {},
    /** 点击添加文件附件时触发 */
    onPickFile: () -> Unit = {},
    /** 移除暂存附件时触发 */
    onRemoveAttachment: (String) -> Unit = {},
    /** 预览图片附件时触发 */
    onPreviewImage: (AttachmentUiItem) -> Unit = {},
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("medium") }
    // 循环规则：null 表示不循环，"daily" 表示每日循环
    var recurrenceRule by remember { mutableStateOf<String?>(null) }
    // 每日循环提醒时间：HH:MM 格式，默认 "09:00"
    var recurrenceReminderTime by remember { mutableStateOf<String?>(null) }
    // 一次性提醒时间：YYYY-MM-DDTHH:MM 格式，null 表示未设置
    var taskReminder by remember { mutableStateOf<String?>(null) }
    // 当前面板状态，默认为 FORM 主表单
    var panel by remember { mutableStateOf(AddPanel.FORM) }

    // 自动聚焦标题输入框，展开后立即呼出输入法
    val focusRequester = remember { FocusRequester() }
    // FORM 面板显示时才聚焦标题，切换到其他面板时不重新聚焦
    LaunchedEffect(panel == AddPanel.FORM) {
        if (panel == AddPanel.FORM) focusRequester.requestFocus()
    }

    // 半透明背景 + 点击外部关闭
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 表单卡片：米白色背景
        Surface(
            modifier = sharedBoundsModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            // AnimatedContent：三个面板之间左右滑动切换
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    // 根据面板切换方向决定滑动方向
                    if (targetState == AddPanel.FORM) {
                        // 从子面板返回 FORM：子面板向右滑出，FORM 从左滑入
                        slideInHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> -fullWidth } + fadeIn(tween(250)) togetherWith
                        slideOutHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> fullWidth } + fadeOut(tween(200))
                    } else {
                        // 从 FORM 进入子面板：FORM 向左滑出，子面板从右滑入
                        slideInHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> fullWidth } + fadeIn(tween(250)) togetherWith
                        slideOutHorizontally(
                            animationSpec = tween(350, easing = FastOutSlowInEasing),
                        ) { fullWidth -> -fullWidth } + fadeOut(tween(200))
                    } using SizeTransform(clip = false)
                },
                label = "addPanelTransition",
            ) { currentPanel ->
                when (currentPanel) {
                    AddPanel.FORM -> {
                        // ===== 主表单面板 =====
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题：纯文字，无图标
                            Text(
                                "新建任务",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            // 标题输入框：粗线边框，自动聚焦呼出输入法
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                placeholder = { Text("标题") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                                    .focusRequester(focusRequester),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                singleLine = true,
                            )
                            // 描述输入框：粗线边框，右下角内嵌附件按钮，clip 防止附件按钮展开时溢出框外
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp)),
                            ) {
                                OutlinedTextField(
                                    value = description,
                                    onValueChange = { description = it },
                                    placeholder = { Text("描述") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(3.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    maxLines = 3,
                                )
                                // 附件按钮：定位在描述输入框的右下角
                                AttachmentButton(
                                    onPickImage = onPickImage,
                                    onPickFile = onPickFile,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 4.dp, bottom = 4.dp),
                                )
                            }
                            // 优先级选择
                            Text(
                                "优先级",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PrioritySelector(selected = priority, onSelect = { priority = it })

                            // 提醒入口行：显示当前提醒时间，点击切换到 REMINDER 面板
                            ReminderEntryRow(
                                reminder = taskReminder,
                                onClick = { panel = AddPanel.REMINDER },
                            )

                            // 重复入口行：显示当前循环设置，点击切换到 RECURRENCE 面板
                            RecurrenceEntryRow(
                                recurrenceRule = recurrenceRule,
                                reminderTime = recurrenceReminderTime,
                                onClick = { panel = AddPanel.RECURRENCE },
                            )

                            // 已暂存的附件缩略图列表
                            if (pendingAttachments.isNotEmpty()) {
                                AttachmentList(
                                    attachments = pendingAttachments,
                                    onRemove = onRemoveAttachment,
                                    onPreview = onPreviewImage,
                                )
                            }

                            // 操作按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("取消", fontWeight = FontWeight.SemiBold) }
                                Button(
                                    onClick = {
                                        if (title.isNotBlank()) onAdd(title.trim(), description.trim().ifBlank { null }, priority, recurrenceRule, recurrenceReminderTime, taskReminder)
                                    },
                                    enabled = title.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                ) { Text("添加", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }

                    AddPanel.REMINDER -> {
                        // ===== 提醒设置面板 =====
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题栏：图标 + "提醒设置" + 关闭按钮（返回 FORM 面板）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Alarm,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    "提醒设置",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(onClick = { panel = AddPanel.FORM }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }

                            // 使用 ReminderTimePicker（startExpanded=true：直接显示日历，隐藏标题行）
                            // onReminderChanged 只管数据，onDismiss 负责返回 FORM 面板
                            ReminderTimePicker(
                                reminder = taskReminder,
                                onReminderChanged = { taskReminder = it },
                                startExpanded = true,
                                onDismiss = { panel = AddPanel.FORM },
                            )
                        }
                    }

                    AddPanel.RECURRENCE -> {
                        // ===== 重复设置面板 =====
                        // 本地编辑状态，确定后写回外层
                        var localRule by remember { mutableStateOf(recurrenceRule) }
                        var localTime by remember { mutableStateOf(recurrenceReminderTime) }

                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题栏：图标 + "重复设置" + 关闭按钮
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Repeat,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    "重复设置",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    // 取消时恢复原值
                                    localRule = recurrenceRule
                                    localTime = recurrenceReminderTime
                                    panel = AddPanel.FORM
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }

                            // 使用 RecurrenceSelector（不重复/每天 + 内联时间滚轮）
                            RecurrenceSelector(
                                recurrenceRule = localRule,
                                reminderTime = localTime,
                                onRecurrenceChanged = { localRule = it },
                                onReminderTimeChanged = { localTime = it },
                            )

                            // 底部操作按钮：取消 + 保存
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        // 取消时恢复原值
                                        localRule = recurrenceRule
                                        localTime = recurrenceReminderTime
                                        panel = AddPanel.FORM
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("取消", fontWeight = FontWeight.SemiBold) }
                                Button(
                                    onClick = {
                                        // 保存时将本地编辑值写回外层状态
                                        recurrenceRule = localRule
                                        recurrenceReminderTime = localTime
                                        panel = AddPanel.FORM
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                ) { Text("保存", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                }
            }
        } // end Surface
    } // end Box
}

/**
 * 自适应高度布局 —— 头部固定、中间自适应、底部固定。
 *
 * 使用 SubcomposeLayout 分两轮测量：
 * 1. 第一轮：测量 header 和 footer 的实际高度
 * 2. 第二轮：根据剩余空间计算 body 的最大高度，再测量 body
 *
 * 整体高度跟随内容自适应，不超过 maxHeight。
 * 当内容总高度超过 maxHeight 时，body（备注输入框）被压缩并内部滚动，
 * header 和 footer 始终完整显示。
 *
 * @param maxHeight 布局的最大高度约束（通常是屏幕高度的 85%）
 * @param contentPadding 内容区域内边距
 * @param spacing 子元素之间的间距
 * @param header 头部内容（标题行），自然高度
 * @param body 中间自适应内容（备注输入框），接收可用的最大高度参数
 * @param footer 底部固定内容（专注信息 + 工具栏），自然高度
 */
@Composable
private fun AdaptiveHeightLayout(
    maxHeight: Dp,
    contentPadding: Dp = 20.dp,
    spacing: Dp = 16.dp,
    header: @Composable () -> Unit,
    body: @Composable (maxBodyHeight: Dp) -> Unit,
    footer: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.roundToPx() }
    val paddingPx = with(density) { contentPadding.roundToPx() }
    // body 最小高度：备注输入框至少 120dp
    val minBodyPx = with(density) { 120.dp.roundToPx() }
    val maxHeightPx = with(density) { maxHeight.roundToPx() }

    SubcomposeLayout(
        modifier = Modifier.fillMaxWidth(),
    ) { constraints ->
        val width = constraints.maxWidth
        // 实际最大高度约束：取父约束和 maxHeight 中的较小值
        val effectiveMaxHeight = minOf(constraints.maxHeight, maxHeightPx)

        // 内容区域的约束（减去左右 padding）
        val contentConstraints = constraints.copy(
            minWidth = (width - paddingPx * 2).coerceAtLeast(0),
            maxWidth = (width - paddingPx * 2).coerceAtLeast(0),
        )

        // ===== 第一轮：测量头部和底部，获取实际高度 =====
        val headerPlaceable = subcompose("header", header).first()
            .measure(contentConstraints)
        val footerPlaceable = subcompose("footer", footer).first()
            .measure(contentConstraints)

        // ===== 计算中间区域的最大可用高度 =====
        // = 总最大高度 - 上下padding - 头部 - 底部 - 2个间距(header↔body, body↔footer)
        val maxBodyPx = (
            effectiveMaxHeight
                - paddingPx * 2
                - headerPlaceable.height
                - footerPlaceable.height
                - spacingPx * 2
        ).coerceAtLeast(minBodyPx)

        // ===== 第二轮：用计算出的最大高度测量中间内容 =====
        val bodyConstraints = contentConstraints.copy(
            minHeight = minBodyPx,
            maxHeight = maxBodyPx,
        )
        val bodyPlaceable = subcompose("body") {
            body(with(density) { maxBodyPx.toDp() })
        }.first().measure(bodyConstraints)

        // ===== 计算实际总高度并布局 =====
        val totalHeight = (
            paddingPx
                + headerPlaceable.height + spacingPx
                + bodyPlaceable.height + spacingPx
                + footerPlaceable.height
                + paddingPx
        ).coerceIn(0, effectiveMaxHeight)

        layout(width, totalHeight) {
            var y = paddingPx
            headerPlaceable.place(paddingPx, y)
            y += headerPlaceable.height + spacingPx
            bodyPlaceable.place(paddingPx, y)
            y += bodyPlaceable.height + spacingPx
            footerPlaceable.place(paddingPx, y)
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

/**
 * 分组标签栏 —— 在选中清单后，显示该清单下的所有分组标签。
 * 水平滚动，支持切换分组筛选、新建分组、长按删除分组。
 *
 * @param groups 当前清单下的分组列表
 * @param activeGroupId 当前选中的分组 ID，null 表示"全部"
 * @param onGroupSelected 点击分组标签时触发，传入分组 ID 或 null
 * @param onCreateGroup 点击"+"按钮时触发，传入新分组名称
 * @param onDeleteGroup 长按分组标签时触发，传入要删除的分组 ID
 */
@Composable
private fun GroupTabBar(
    groups: List<GroupItem>,
    activeGroupId: String?,
    onGroupSelected: (String?) -> Unit,
    onCreateGroup: (String) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    // 新建分组的输入状态：true=显示输入框，false=隐藏
    var showCreateInput by remember { mutableStateOf(false) }
    // 新建分组的名称文本
    var newGroupName by remember { mutableStateOf("") }
    // 输入框焦点控制
    val focusRequester = remember { FocusRequester() }
    // 长按触发的删除确认对话框
    var showDeleteDialogForId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "全部"标签 —— activeGroupId = null 时选中
            GroupTabChip(
                label = "全部",
                isSelected = activeGroupId == null,
                onClick = { onGroupSelected(null) },
                onLongClick = null,
            )
            // 各分组标签
            for (group in groups) {
                GroupTabChip(
                    label = group.name,
                    isSelected = activeGroupId == group.id,
                    onClick = { onGroupSelected(group.id) },
                    onLongClick = { showDeleteDialogForId = group.id },
                    color = group.color,
                )
            }
            // "+"按钮 —— 点击后展开输入框
            if (!showCreateInput) {
                IconButton(
                    onClick = { showCreateInput = true; newGroupName = "" },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.AddCircleOutline,
                        contentDescription = "新建分组",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 新建分组输入框 —— 展开在标签栏下方
        if (showCreateInput) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    placeholder = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            onCreateGroup(newGroupName.trim())
                            newGroupName = ""
                            showCreateInput = false
                        }
                    },
                ) {
                    Text("确定")
                }
                TextButton(
                    onClick = { showCreateInput = false; newGroupName = "" },
                ) {
                    Text("取消")
                }
            }
            // 自动聚焦输入框
            LaunchedEffect(showCreateInput) {
                if (showCreateInput) {
                    focusRequester.requestFocus()
                }
            }
        }
    }

    // 删除分组确认对话框
    showDeleteDialogForId?.let { groupId ->
        val groupName = groups.find { it.id == groupId }?.name ?: ""
        AlertDialog(
            onDismissRequest = { showDeleteDialogForId = null },
            title = { Text("删除分组") },
            text = { Text("确定删除分组「$groupName」吗？组内任务不会被删除，将移至未分组。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteGroup(groupId)
                        showDeleteDialogForId = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogForId = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 分组标签胶囊 —— 单个可点击的分组标签。
 *
 * @param label 显示文本
 * @param isSelected 是否选中（高亮样式）
 * @param onClick 点击回调
 * @param onLongClick 长按回调（用于触发删除），null 表示不支持长按
 * @param color 可选的颜色标识（如"#FF5722"）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupTabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    color: String? = null,
) {
    // 尝试解析分组颜色，失败则使用主题色
    val chipColor = remember(color) {
        try {
            if (color != null) android.graphics.Color.parseColor(color)
            else null
        } catch (_: Exception) { null }
    }
    val backgroundColor = if (isSelected) {
        chipColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isSelected) {
        chipColor?.let { c ->
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            if (r * 0.299 + g * 0.587 + b * 0.114 > 186) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onPrimary
        } ?: MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .then(
                if (onLongClick != null) Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ) else Modifier.clickable(onClick = onClick)
            ),
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
