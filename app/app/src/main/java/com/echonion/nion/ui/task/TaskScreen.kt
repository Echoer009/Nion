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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
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
) {
    var showAddSheet by remember { mutableStateOf(false) }
    var expandedTaskId by remember { mutableStateOf<String?>(null) }

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
                    // 展开：详情浮层快速淡入，任务列表缓慢淡出
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
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
                            onAdd = { title, desc, priority ->
                                viewModel.createTask(title, desc, priority)
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
        if (viewModel.tasks.isEmpty()) {
            EmptyTaskView(modifier = Modifier.padding(innerPadding))
        } else {
            TaskList(
                viewModel = viewModel,
                innerPadding = innerPadding,
                onTaskClick = onTaskClick,
                taskSharedModifier = taskSharedModifier,
            )
        }
    }
}

/**
 * 添加任务浮层 —— 从 FAB 位置展开为居中的任务创建表单。
 *
 * 米白色卡片 + 半透明遮罩，高斯模糊背景。
 *
 * @param onDismiss 点击外部区域关闭回调
 * @param onAdd 确认添加回调，传入 (标题, 描述, 优先级)
 * @param sharedBoundsModifier shared element 动画 modifier（与 FAB 共享 bounds）
 */
@Composable
private fun AddTaskOverlay(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit,
    sharedBoundsModifier: Modifier,
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("medium") }

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
                // 描述输入框：粗线边框
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
                // 优先级选择
                Text(
                    "优先级",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrioritySelector(selected = priority, onSelect = { priority = it })
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
                            if (title.isNotBlank()) onAdd(title.trim(), description.trim(), priority)
                        },
                        enabled = title.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                    ) { Text("添加", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * 任务详情浮层 —— 从任务卡片位置 morph 展开为居中的任务详情/子任务表单。
 *
 * 内部使用 AnimatedContent 在「任务详情」和「添加子任务表单」之间切换，
 * 两种状态共享的卡片通过 sharedElementModifier 与触发卡片做 morph 动画。
 *
 * 卡片本身无边框，通过 tonalElevation 形成粗阴影边缘（与任务列表卡片风格一致）。
 *
 * @param task 要展示详情的任务对象
 * @param onDismiss 关闭回调（点击外部区域）
 * @param onDelete 删除任务回调
 * @param onCreateSubtask 创建子任务回调，传入 (标题, 优先级)
 * @param onUpdateNotes 备注内容变更时触发，自动保存到数据库
 * @param sharedElementModifier shared element 动画 modifier（与触发卡片共享 key）
 */
@Composable
private fun TaskDetailOverlay(
    task: TaskItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onCreateSubtask: (String, String) -> Unit,
    onUpdateNotes: (String) -> Unit,
    sharedElementModifier: Modifier,
) {
    // 备注内容：初始值为任务描述，可随时编辑，每次变更自动保存
    var notes by remember(task.id) { mutableStateOf(task.description ?: "") }
    // showAddSubtask: true = 显示子任务创建表单，false = 显示任务详情
    var showAddSubtask by remember { mutableStateOf(false) }
    // 子任务创建表单的标题和优先级
    var subtaskTitle by remember { mutableStateOf("") }
    var subtaskPriority by remember { mutableStateOf("medium") }

    // 全屏透明点击区域（无遮罩）+ 居中卡片
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 卡片：通过 sharedElementModifier 与触发卡片共享 bounds 动画
        // 无边框，使用 tonalElevation=12dp 形成粗阴影边缘
        Surface(
            modifier = sharedElementModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 12.dp,
        ) {
            // 内部 AnimatedContent：在「任务详情」和「添加子任务表单」之间切换
            AnimatedContent(
                targetState = showAddSubtask,
                transitionSpec = {
                    if (targetState) {
                        // 展开子任务表单：快速淡入表单，缓慢淡出详情
                        (fadeIn(tween(280, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    } else {
                        // 返回详情：快速淡入详情，缓慢淡出表单
                        (fadeIn(tween(200, easing = FastOutSlowInEasing))
                            togetherWith fadeOut(tween(350, easing = FastOutSlowInEasing)))
                            .using(SizeTransform(clip = false))
                    }
                },
                label = "subtaskForm",
            ) { adding ->
                if (adding) {
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
                            IconButton(onClick = { showAddSubtask = false }) {
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
                                    showAddSubtask = false
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
                                        showAddSubtask = false
                                    }
                                },
                                enabled = subtaskTitle.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f),
                            ) { Text("添加", fontWeight = FontWeight.SemiBold) }
                        }
                    }
                } else {
                    // ===== 任务详情 =====
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // 顶部：任务标题 + 关闭按钮
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
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }

                        // 可编辑的备注输入框
                        OutlinedTextField(
                            value = notes,
                            onValueChange = {
                                notes = it
                                onUpdateNotes(it)
                            },
                            placeholder = { Text("添加备注...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        )

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
                                    onClick = { showAddSubtask = true },
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
                        sharedElementModifier = taskSharedModifier(task.id),
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
