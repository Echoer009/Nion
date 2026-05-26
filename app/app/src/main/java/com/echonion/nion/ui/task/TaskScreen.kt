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
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import kotlinx.coroutines.launch
import com.echonion.nion.ui.components.DualPanelState
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
                        onUpdateDueDate = { date ->
                            viewModel.updateDueDate(taskId, date)
                        },
                        onUpdateRecurrence = { rule, time ->
                            viewModel.updateRecurrence(taskId, rule, time)
                        },
                        onRemoveRecurrence = {
                            viewModel.removeRecurrence(taskId)
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
                            onAdd = { title, desc, priority, dueDate, recurrenceRule, recurrenceReminderTime ->
                                val pending = pendingAttachmentData.toList()
                                viewModel.createTask(
                                    title = title,
                                    description = desc,
                                    priority = priority,
                                    dueDate = dueDate,
                                    recurrenceRule = recurrenceRule,
                                    recurrenceReminderTime = recurrenceReminderTime,
                                ) { newTaskId ->
                                    // 任务创建成功后，批量关联临时附件
                                    if (pending.isNotEmpty()) {
                                        viewModel.commitPendingAttachments(newTaskId, pending)
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        },
        floatingActionButton = fab,
    ) { innerPadding ->
        // 分组标签栏：选中清单时始终显示（即使没有分组也能新建）
        val showGroupBar = viewModel.activeChecklistId != null && viewModel.activeChecklistId != TaskViewModel.TODAY_ID
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
                TaskList(
                    viewModel = viewModel,
                    innerPadding = androidx.compose.foundation.layout.PaddingValues(),
                    onTaskClick = onTaskClick,
                    taskSharedModifier = taskSharedModifier,
                    modifier = Modifier.weight(1f),
                    listState = listState,
                    reorderableItems = reorderableItems,
                )
            }
        }
    }
}

/**
 * 新建任务浮层 —— 从 FAB 位置 morph 展开为居中的任务创建表单。
 *
 * 内部使用 AnimatedContent 在「表单」和「日期选择」两个页面之间切换，
 * 共享同一个卡片容器（sharedBounds 与 FAB 做动画）。
 *
 * @param onDismiss 点击外部区域关闭回调
 * @param onAdd 确认添加回调，传入 (标题, 描述, 优先级, 截止日期, 循环规则, 提醒时间)
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
    /** 创建回调：title, description, priority, dueDate, recurrenceRule, recurrenceReminderTime */
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
    // 截止日期：ISO 格式字符串，null 表示未设置
    var dueDate by remember { mutableStateOf<String?>(null) }
    // 循环规则：null 表示不循环，"daily" 表示每日循环
    var recurrenceRule by remember { mutableStateOf<String?>(null) }
    // 每日循环提醒时间：HH:MM 格式，默认 "09:00"
    var recurrenceReminderTime by remember { mutableStateOf<String?>(null) }
    // showingDatePicker: true=显示日期选择页，false=显示表单
    var showingDatePicker by remember { mutableStateOf(false) }

    // 自动聚焦标题输入框，展开后立即呼出输入法
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
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
            // AnimatedContent 在表单与日期选择页之间切换，morph 过渡
            AnimatedContent(
                targetState = showingDatePicker,
                transitionSpec = {
                    if (targetState) {
                        (fadeIn(tween(280, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    } else {
                        (fadeIn(tween(200, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(350, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    }
                },
                label = "addTaskDatePicker",
            ) { showPicker ->
                if (showPicker) {
                    // ===== 日期选择页（嵌入卡片，自研日历）=====
                    val todayForPicker = remember { java.time.LocalDate.now() }
                    val initialLocalDate = remember(dueDate) {
                        try {
                            if (!dueDate.isNullOrBlank()) java.time.LocalDate.parse(dueDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                            else null
                        } catch (_: Exception) { null }
                    }
                    val initialYM = remember(initialLocalDate) {
                        initialLocalDate?.let { java.time.YearMonth.from(it) } ?: java.time.YearMonth.now()
                    }
                    var selectedDate by remember(initialLocalDate) { mutableStateOf(initialLocalDate) }
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 标题栏
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showingDatePicker = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "选择日期",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        NionCalendar(
                            initialYearMonth = initialYM,
                            today = todayForPicker,
                            selectedDate = selectedDate,
                            onSelect = { selectedDate = it },
                        )
                        // 底部按钮：等宽横排
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    dueDate = null
                                    showingDatePicker = false
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                ),
                                modifier = Modifier.weight(1f),
                            ) { Text("清除", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            TextButton(
                                onClick = { showingDatePicker = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            Button(
                                onClick = {
                                    dueDate = selectedDate?.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                                    showingDatePicker = false
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f),
                            ) { Text("确定", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        }
                    }
                } else {
                    // ===== 表单内容 =====
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
                // 日期选择行：点击后卡片内容 morph 为日期选择页
                Row(
                    modifier = Modifier
                        .clickable { showingDatePicker = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (dueDate != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dueDate.formatDueDate() ?: "选择日期",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (dueDate != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 循环规则 + 提醒时间选择器
                RecurrenceSelector(
                    recurrenceRule = recurrenceRule,
                    reminderTime = recurrenceReminderTime,
                    onRecurrenceChanged = { recurrenceRule = it },
                    onReminderTimeChanged = { recurrenceReminderTime = it },
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
                            if (title.isNotBlank()) onAdd(title.trim(), description.trim().ifBlank { null }, priority, dueDate, recurrenceRule, recurrenceReminderTime)
                        },
                        enabled = title.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                    ) { Text("添加", fontWeight = FontWeight.SemiBold) }
                }
                    } // end Column (form)
                } // end else
            } // end AnimatedContent
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

/**
 * 任务详情浮层内部的面板状态：
 * - DETAIL：显示任务详情（备注、工具栏）
 * - ADD_SUBTASK：显示子任务创建表单
 * - DATE_PICKER：显示日期选择页面
 * - RECURRENCE：显示循环设置页面
 */
private enum class DetailPanel { DETAIL, ADD_SUBTASK, DATE_PICKER, RECURRENCE }

/**
 * 任务详情浮层 —— 从任务卡片位置 morph 展开为居中的任务详情/子任务表单。
 *
 * 内部使用 AnimatedContent 在「任务详情」「添加子任务表单」「日期选择」「附件管理」四个面板之间切换，
 * 三种状态共享的卡片通过 sharedElementModifier 与触发卡片做 morph 动画。
 *
 * 卡片本身无边框，通过 tonalElevation 形成粗阴影边缘（与任务列表卡片风格一致）。
 *
 * @param task 要展示详情的任务对象
 * @param onDismiss 关闭回调（点击外部区域）
 * @param onDelete 删除任务回调
 * @param onCreateSubtask 创建子任务回调，传入 (标题, 优先级)
 * @param onUpdateNotes 备注内容变更时触发，自动保存到数据库
 * @param onUpdateDueDate 截止日期变更回调，传入 ISO 格式日期字符串或 null
 * @param onUpdateRecurrence 循环设置变更回调，传入 (recurrenceRule, reminderTime)
 * @param onRemoveRecurrence 移除循环回调
 * @param sharedElementModifier shared element 动画 modifier（与触发卡片共享 key）
 * @param attachments 当前任务的附件列表
 * @param onPickImage 点击添加图片附件时触发
 * @param onPickFile 点击添加文件附件时触发
 * @param onRemoveAttachment 删除附件时触发，传入附件 ID
 * @param onPreviewImage 预览图片附件时触发，传入附件 UI 模型
 */
@Composable
private fun TaskDetailOverlay(
    task: TaskItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onCreateSubtask: (String, String) -> Unit,
    onUpdateNotes: (String) -> Unit,
    onUpdateDueDate: (String?) -> Unit,
    /** 更新每日循环设置回调，传入 (recurrenceRule, reminderTime) */
    onUpdateRecurrence: (String?, String?) -> Unit = { _, _ -> },
    /** 移除每日循环回调 */
    onRemoveRecurrence: () -> Unit = {},
    sharedElementModifier: Modifier,
    /** 用户点击"开始专注"按钮时触发，传入预选的专注时长（分钟） */
    onStartFocus: (Int) -> Unit = {},
    /** 当前任务的附件列表 */
    attachments: List<AttachmentUiItem> = emptyList(),
    /** 点击添加图片附件时触发 */
    onPickImage: () -> Unit = {},
    /** 点击添加文件附件时触发 */
    onPickFile: () -> Unit = {},
    /** 删除附件时触发 */
    onRemoveAttachment: (String) -> Unit = {},
    /** 预览图片附件时触发 */
    onPreviewImage: (AttachmentUiItem) -> Unit = {},
) {
    // 备注内容：初始值为任务描述，可随时编辑，每次变更自动保存
    var notes by remember(task.id) { mutableStateOf(task.description ?: "") }
    // 当前面板状态：DETAIL=详情 / ADD_SUBTASK=添加子任务 / DATE_PICKER=选择日期
    var panel by remember { mutableStateOf(DetailPanel.DETAIL) }
    // 子任务创建表单的标题和优先级
    var subtaskTitle by remember { mutableStateOf("") }
    var subtaskPriority by remember { mutableStateOf("medium") }
    // focusExpanded: 专注行是否展开（播放按钮移到左侧，显示预选时长）
    var focusExpanded by remember { mutableStateOf(false) }
    // selectedDuration: 预选专注时长（分钟），可在 25/45/60 之间循环切换
    var selectedDuration by remember { mutableStateOf(25) }

    // 半透明遮罩 + 点击外部关闭（与 AddTaskOverlay 一致的 scrim 背景，防止交叉淡入淡出期间背景透出）
    BoxWithConstraints(
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
        // maxCardHeight：卡片允许的最大高度 = 屏幕高度的 85%，保证不超出屏幕
        val maxCardHeight = maxHeight * 0.85f

        // 卡片：通过 sharedElementModifier 与触发卡片共享 bounds 动画
        // 无边框，使用 tonalElevation=12dp 形成粗阴影边缘
        // heightIn 限制最大高度，防止内容过多时扩出屏幕
        Surface(
            modifier = sharedElementModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maxCardHeight)
                // 消费卡片上的点击事件，阻止穿透到外层遮罩触发 onDismiss
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 12.dp,
        ) {
            // 内部 AnimatedContent：在三个面板之间切换，与添加子任务使用相同的 morph 动画
            AnimatedContent(
                targetState = panel,
                transitionSpec = {
                    if (targetState != DetailPanel.DETAIL) {
                        // 展开子表单/日期选择：快速淡入新面板，缓慢淡出详情
                        (fadeIn(tween(280, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    } else {
                        // 返回详情：快速淡入详情，缓慢淡出面板
                        (fadeIn(tween(200, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(350, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    }
                },
                label = "detailPanel",
            ) { currentPanel ->
                when (currentPanel) {
                    DetailPanel.ADD_SUBTASK -> {
                        // ===== 添加子任务表单 =====
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 标题栏：图标 + "新建子任务" 文字
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.AddCircleOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Text(
                                    "新建子任务",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(onClick = { panel = DetailPanel.DETAIL }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }

                            // 提示：正在为哪个任务添加子任务
                            Text(
                                "添加到：${task.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // 子任务标题输入框
                            OutlinedTextField(
                                value = subtaskTitle,
                                onValueChange = { subtaskTitle = it },
                                label = { Text("标题") },
                                placeholder = { Text("输入子任务标题...") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                                singleLine = true,
                            )

                            // 优先级选择
                            Text(
                                "优先级",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PrioritySelector(selected = subtaskPriority, onSelect = { subtaskPriority = it })

                            // 操作按钮：取消 + 添加
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = {
                                        subtaskTitle = ""
                                        subtaskPriority = "medium"
                                        panel = DetailPanel.DETAIL
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("取消", fontWeight = FontWeight.SemiBold) }
                                Button(
                                    onClick = {
                                        if (subtaskTitle.isNotBlank()) {
                                            onCreateSubtask(subtaskTitle.trim(), subtaskPriority)
                                            subtaskTitle = ""
                                            subtaskPriority = "medium"
                                            panel = DetailPanel.DETAIL
                                        }
                                    },
                                    enabled = subtaskTitle.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                ) { Text("添加", fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }

                    DetailPanel.DATE_PICKER -> {
                        // ===== 日期选择页面 =====
                        // morph 动画与添加子任务一致，卡片内容整体切换为日历选择
                        DatePickerPage(
                            taskId = task.id,
                            initialDate = task.dueDate,
                            onConfirm = { date ->
                                onUpdateDueDate(date)
                                panel = DetailPanel.DETAIL
                            },
                            onBack = { panel = DetailPanel.DETAIL },
                        )
                    }

                    DetailPanel.RECURRENCE -> {
                        // ===== 循环设置页面 =====
                        // 使用 RecurrenceSelector，与新建任务表单中的样式一致
                        var localRule by remember(task.id) { mutableStateOf(task.recurrenceRule) }
                        var localTime by remember(task.id) { mutableStateOf(task.recurrenceReminderTime) }
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
                                IconButton(onClick = { panel = DetailPanel.DETAIL }) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            }

                            // 提示正在为哪个任务设置循环
                            Text(
                                "为「${task.title}」设置每日循环",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            // 循环选择器：不重复 / 每天 + 提醒时间
                            RecurrenceSelector(
                                recurrenceRule = localRule,
                                reminderTime = localTime,
                                onRecurrenceChanged = { localRule = it },
                                onReminderTimeChanged = { localTime = it },
                            )

                            // 操作按钮：取消 + 保存 + 移除循环
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 移除循环按钮（仅当已设置循环时显示）
                                if (task.recurrenceRule == "daily") {
                                    TextButton(
                                        onClick = {
                                            onRemoveRecurrence()
                                            panel = DetailPanel.DETAIL
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        ),
                                    ) {
                                        Text("移除循环", fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                                TextButton(
                                    onClick = { panel = DetailPanel.DETAIL },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp),
                                ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                                Button(
                                    onClick = {
                                        onUpdateRecurrence(localRule, localTime)
                                        panel = DetailPanel.DETAIL
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                ) { Text("保存", fontWeight = FontWeight.SemiBold, maxLines = 1) }
                            }
                        }
                    }

                    DetailPanel.DETAIL -> {
                        // ===== 任务详情 =====
                        // 用 Column + weight 替代 AdaptiveHeightLayout (SubcomposeLayout)，
                        // 避免多次 subcompose 导致的"先显示居中标题，后续才出现内容"闪烁。
                        Column(
                            modifier = Modifier
                                .heightIn(max = maxCardHeight)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // 顶部：任务标题 + 日历图标 + 关闭按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                // 日历/日期：点击后 morph 为日期选择页面
                                // 已设置日期时显示日期文字，未设置时显示日历图标
                                Row(
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { panel = DetailPanel.DATE_PICKER },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (task.dueDate != null) {
                                        // 已设置日期：显示格式化的日期文字
                                        Text(
                                            task.dueDate.formatDueDate() ?: "",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        // 未设置日期：显示日历图标
                                        Icon(
                                            Icons.Outlined.CalendarToday,
                                            contentDescription = "选择日期",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }

                        // 循环信息行：展示当前的每日循环设置，点击进入 RECURRENCE 面板
                        if (task.recurrenceRule == "daily") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { panel = DetailPanel.RECURRENCE },
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (task.recurrenceReminderTime != null) "每天 ${task.recurrenceReminderTime} 提醒" else "每天提醒",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            // 未设置循环：显示可点击的设置入口
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { panel = DetailPanel.RECURRENCE },
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Repeat,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "设置重复",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }

                            // 可编辑的备注输入框 —— weight(1f, fill=false) 占据 header/footer 之外的剩余空间
                            // 内容少时收缩（最低 120dp），内容多时自动扩展，超出后内部滚动
                            // 右下角内嵌附件按钮（回形针图标），clip 防止附件按钮展开时溢出框外
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .heightIn(min = 120.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                            ) {
                                OutlinedTextField(
                                    value = notes,
                                    onValueChange = {
                                        notes = it
                                        onUpdateNotes(it)
                                    },
                                    placeholder = { Text("添加备注...") },
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                )
                                // 附件按钮：定位在备注输入框的右下角
                                AttachmentButton(
                                    onPickImage = onPickImage,
                                    onPickFile = onPickFile,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 4.dp, bottom = 4.dp),
                                )
                            }

                            // 底部固定区域：专注信息 + 工具栏，始终显示
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                /**
                                 * 专注信息行 —— 两步交互：
                                 * 状态 A（默认）：[⏱ 专注时间 2h 30m ▶]，点击 ▶ 进入状态 B
                                 * 状态 B（展开）：[▶ 25分钟▲▼]，播放按钮从右侧平滑滑动到左侧，右侧显示可切换的预选时长
                                 *   - 点击 "25分钟 ▲▼" 循环切换 25→45→60→25
                                 *   - 点击 ▶ 启动计时并跳转专注页面
                                 *
                                 * 使用局部 SharedTransitionLayout + sharedBounds 让播放按钮在过渡期间保持可见，
                                 * 从右侧平滑平移到左侧，而非淡入淡出。
                                 */
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    SharedTransitionLayout {
                                        AnimatedContent(
                                            targetState = focusExpanded,
                                            transitionSpec = {
                                                // 淡入淡出 300ms，播放按钮由 sharedBounds 独立处理位移动画
                                                (fadeIn(tween(300, easing = FastOutSlowInEasing))
                                                    togetherWith fadeOut(tween(300, easing = FastOutSlowInEasing)))
                                                    .using(SizeTransform(clip = false))
                                            },
                                            label = "focusRow",
                                        ) { expanded ->
                                            // 捕获 AnimatedVisibilityScope，避免 this@AnimatedContent 标签歧义
                                            val animatedScope = this
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (!expanded) {
                                                    // 状态 A 左侧：时钟图标 + 专注时间文字
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            Icons.Default.Timer,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(20.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column {
                                                            Text(
                                                                "专注时间",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                            Text(
                                                                formatFocusTime(task.focusSeconds),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = FontWeight.Medium,
                                                            )
                                                        }
                                                    }
                                                }

                                                /**
                                                 * 状态 B 左侧播放按钮：放在 Spacer 之前，确保在最左端
                                                 * 与状态 A 右侧的播放按钮共享 sharedBounds key，
                                                 * 过渡期间从右侧平滑滑动到最左侧
                                                 */
                                                if (expanded) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        modifier = Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState(
                                                                "focusPlayBtn_${task.id}"
                                                            ),
                                                            animatedVisibilityScope = animatedScope,
                                                            boundsTransform = { _, _ ->
                                                                spring(
                                                                    dampingRatio = 0.75f,
                                                                    stiffness = Spring.StiffnessMedium,
                                                                )
                                                            },
                                                        ),
                                                        onClick = {
                                                            onStartFocus(selectedDuration)
                                                        },
                                                    ) {
                                                        Icon(
                                                            Icons.Default.PlayArrow,
                                                            contentDescription = "开始专注",
                                                            modifier = Modifier
                                                                .padding(8.dp)
                                                                .size(20.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.weight(1f))

                                                /**
                                                 * 状态 A 右侧播放按钮：点击后进入状态 B
                                                 * 与状态 B 左侧的播放按钮共享 sharedBounds key，
                                                 * 过渡期间从当前位置滑动到左侧
                                                 */
                                                if (!expanded) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        modifier = Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState(
                                                                "focusPlayBtn_${task.id}"
                                                            ),
                                                            animatedVisibilityScope = animatedScope,
                                                            boundsTransform = { _, _ ->
                                                                spring(
                                                                    dampingRatio = 0.75f,
                                                                    stiffness = Spring.StiffnessMedium,
                                                                )
                                                            },
                                                        ),
                                                        onClick = { focusExpanded = true },
                                                    ) {
                                                        Icon(
                                                            Icons.Default.PlayArrow,
                                                            contentDescription = "开始专注",
                                                            modifier = Modifier
                                                                .padding(8.dp)
                                                                .size(20.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                }

                                                if (expanded) {
                                                    // 状态 B 右侧：预选专注时长，点击循环切换 25→45→60→25
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.clickable {
                                                            selectedDuration = when (selectedDuration) {
                                                                25 -> 45
                                                                45 -> 60
                                                                else -> 25
                                                            }
                                                        },
                                                    ) {
                                                        Text(
                                                            "${selectedDuration}分钟",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Medium,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                        ) {
                                                            Icon(
                                                                Icons.Default.ArrowDropUp,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(10.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                            Icon(
                                                                Icons.Default.ArrowDropDown,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(10.dp),
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 附件区域：附件列表
                                if (attachments.isNotEmpty()) {
                                    AttachmentList(
                                        attachments = attachments,
                                        onRemove = onRemoveAttachment,
                                        onPreview = onPreviewImage,
                                    )
                                }

                                // 工具栏：添加子任务 + 删除
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 添加子任务按钮 —— 点击后卡片内容 morph 为子任务创建表单
                                        TextButton(
                                            onClick = { panel = DetailPanel.ADD_SUBTASK },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.primary,
                                            ),
                                        ) {
                                            Icon(
                                                Icons.Default.AddCircleOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("添加子任务", fontWeight = FontWeight.Medium)
                                        }
                                        // 分隔圆点
                                        Text(
                                            "·",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        )
                                        // 删除按钮
                                        TextButton(
                                            onClick = onDelete,
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            ),
                                        ) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("删除", fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 任务列表 —— LazyColumn + 拖拽排序。
 *
 * @param viewModel 任务 ViewModel
 * @param innerPadding Scaffold 传入的内边距
 * @param onTaskClick 任务点击回调
 * @param taskSharedModifier 为每个任务卡片生成 sharedElement modifier 的函数，用于详情展开 morph 动画
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TaskList(
    viewModel: TaskViewModel,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onTaskClick: (TaskItem) -> Unit,
    taskSharedModifier: @Composable (String) -> Modifier,
    modifier: Modifier = Modifier,
    /** 外部传入的列表滚动状态，提升到 AnimatedContent 外以保留跨过渡的滚动位置 */
    listState: LazyListState = rememberLazyListState(),
    /** 外部传入的可重排项列表，提升到 AnimatedContent 外以保留跨过渡的拖拽状态 */
    reorderableItems: SnapshotStateList<FlatTaskItem> = remember { mutableStateListOf() },
) {
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
        modifier = modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "top_spacer", contentType = "spacer") { Spacer(modifier = Modifier.height(8.dp)) }

        // ==================== 过期每日任务分区（仅"今天"视图显示） ====================
        val overdueTasks = viewModel.overdueDailyTasks
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
                    onComplete = { viewModel.toggleOverdueDailyDone(overdue.task.id, overdue.overdueDate, false) },
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
                    animateItemModifier = if (!isInDraggedGroup) Modifier.animateItem() else Modifier,
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
                                                    if (isSelectionMode) viewModel.toggleSelection(clickedTask.id)
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
                                sharedElementModifier = taskSharedModifier(flatItem.task.id),
                            )
                        }
                    }
                }
            }
        }

        val doneTasks = viewModel.flatDoneTasks
        if (doneTasks.isNotEmpty()) {
            item(key = "done_header", contentType = "header") { SectionHeader("已完成", doneTasks.size) }

            items(
                items = doneTasks,
                key = { it.task.id },
                contentType = { if (it.depth == 0) "done_main" else "done_sub" },
            ) { flatItem ->
                val isSelected = flatItem.task.id in selectedIds
                val cardShape = remember { RoundedCornerShape(16.dp) }
                Box(
                    modifier = Modifier
                        .animateItem()
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
                                viewModel.toggleSelection(clickedTask.id)
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
 * **位置 0（列表最顶端）**：统一晋升为主任务（parent_id = null）。
 * 子任务只有拖到此处才能晋升为主任务。
 *
 * **子任务（depth > 0）**：
 *   始终成为上方紧邻项的直接子任务（parent_id = 上方项的 id）。
 *
 * **主任务（depth == 0）**：
 *   默认保持主任务，仅当明确拖入子树时才改变层级：
 *   - 上方是主任务（depth=0）且下方紧接其子任务（depth>0）→ 进入上方主任务的子树
 *   - 上方是子任务（depth>0）→ 已在子树内部，成为上方子任务的子任务
 *   - 上方是主任务且下方也是主任务（或无下方）→ 保持主任务，只改变排序
 *
 * 正确覆盖全部场景：
 *   1. "B 放在 A 和 a1 之间" → depth(A)=0, depth(a1)=1 → B 进入 A 的子树 ✓
 *   2. "B 放在 a1 和 1 之间" → depth(a1)=1 → B 已在子树内，成为 a1 的子任务 ✓
 *   3. "a1 放在 b1 和 1 之间" → a1 是子任务，上方 b1 → 成为 b1 的子任务 ✓
 *   4. "a1 和 1 被移到顶端" → 索引 0 → a1 晋升主任务，其子任务 1 保持不变 ✓
 *   5. "b1 放在 C 下面" → b1 是子任务，上方 C → 成为 C 的子任务 ✓
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

    /*
     * 计算拖拽后的新父任务 ID。
     *
     * 位置 0 无条件晋升为主任务。
     * 其他位置按 isMainTask(被拖项) 分支：
     *   - 子任务：上方项 id 即新父 id
     *   - 主任务：仅当"明确拖入子树"时才变为子任务，否则保持 null（主任务）
     *
     * "明确拖入子树"的判据：
     *   (a) 上方是主任务、下方紧接其子任务（depth 差>0）—— 正在进入上方主任务的子树
     *   (b) 上方本身就是子任务 —— 已经处于子树内部
     * 不在以上两种情况（两个主任务之间、列表末尾）→ 保持主任务。
     */
    val newParentId: String? = if (currentIdx == 0) {
        null
    } else {
        val aboveItem = reorderableItems[currentIdx - 1]
        val belowItem = reorderableItems.getOrNull(currentIdx + 1)

        if (draggedItem.depth == 0) {
            /* 被拖拽的是主任务 —— 仅当明确拖入子树时才改变层级 */
            when {
                /* 上方是主任务（depth=0）且下方紧接其子任务（depth>0）：进入上方主任务的子树 */
                belowItem != null && aboveItem.depth == 0 && belowItem.depth > 0 -> aboveItem.task.id
                /* 上方是子任务（depth>0）：已处于子树内部，成为上方子任务的子任务 */
                aboveItem.depth > 0 -> aboveItem.task.id
                /* 两个主任务之间（或无下方项）：保持主任务，仅改变排序 */
                else -> null
            }
        } else {
            /* 被拖拽的是子任务 —— 始终成为上方项的直接子任务 */
            aboveItem.task.id
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

    viewModel.moveAndReorderTasks(draggedId, newParentId, siblingIds)
}

@Composable
private fun SectionHeader(title: String, count: Int, isError: Boolean = false) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
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
private fun OverdueDailyTaskRow(
    overdue: uniffi.nion_core.OverdueDailyTask,
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
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
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
                text = overdue.task.title,
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
        // 简单判断亮暗色：选择白色或深色文字
        chipColor?.let { c ->
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            if (r * 0.299 + g * 0.587 + b * 0.114 > 186) Color.Black else Color.White
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
