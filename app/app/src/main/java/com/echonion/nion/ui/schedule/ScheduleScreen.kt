package com.echonion.nion.ui.schedule

import com.echonion.nion.ui.theme.NionAlpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.SharedTransitionLayout
import androidx.activity.compose.BackHandler
import com.echonion.nion.ui.components.SharedTaskList
import com.echonion.nion.ui.components.TaskDetailOverlay
import com.echonion.nion.ui.task.FlatTaskItem
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日程事件数据类。
 *
 * @param id 事件唯一标识
 * @param title 事件标题
 * @param timeRange 时间段描述，如 "09:00 - 10:30"
 * @param location 地点，可为 null
 * @param color 事件标记色
 * @param dayOfWeek 所属星期几
 */
val dayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 日程主屏幕 —— 显示月/周选择器 + 当日任务列表。
 *
 * 使用 SharedTransitionLayout + AnimatedContent 在两套过渡之间切换：
 * 1. 月视图日历弹窗 ↔ 主界面
 * 2. 任务列表 ↔ 任务详情浮层
 * 接入 ScheduleViewModel，从 Rust 端加载真实任务数据和日历标记。
 *
 * @param viewModel 日程页面的 ViewModel，提供任务数据和日历标记
 * @param onOpenCompanion 点击顶栏伙伴图标时的回调，用于打开右侧 AI 面板
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Suppress("LABEL_NAME_CLASH", "kt")
@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel = scheduleViewModel(),
    onOpenCompanion: () -> Unit = {},
) {
    var showCalendarPicker by remember { mutableStateOf(false) }
    /** 当前展开的任务详情 ID，null 表示显示任务列表 */
    var expandedTaskId by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val selectedDate = viewModel.selectedDate
    val dayLabel = dayLabels[selectedDate.dayOfWeek.value - 1]
    val yearMonth = YearMonth.from(selectedDate)

    // 提升到 AnimatedContent 外部，跨过渡保留拖拽状态
    val reorderableItems = remember { mutableStateListOf<FlatTaskItem>() }

    // 日历弹窗返回拦截：弹窗打开时，系统返回手势关闭日历而非退出页面
    BackHandler(enabled = showCalendarPicker) {
        showCalendarPicker = false
    }
    // 任务详情 overlay 返回拦截：详情展开时，系统返回手势关闭详情而非退出页面
    BackHandler(enabled = expandedTaskId != null) {
        expandedTaskId = null
    }

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showCalendarPicker,
            transitionSpec = {
                if (targetState) {
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "calendar",
        ) { showingCalendar ->
            // 日历 morph modifier 提取到外层 "calendar" AnimatedContent，
            // 避免内层嵌套 AnimatedContent 中 this@AnimatedContent 出现歧义。
            val calendarSharedBounds = Modifier.sharedElement(
                sharedContentState = rememberSharedContentState("calendar"),
                animatedVisibilityScope = this@AnimatedContent,
                boundsTransform = { _, _ ->
                    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                },
            )
            val calendarTextModifier = Modifier.sharedElement(
                sharedContentState = rememberSharedContentState("monthText"),
                animatedVisibilityScope = this@AnimatedContent,
                boundsTransform = { _, _ ->
                    spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                },
            )

            if (showingCalendar) {
                CalendarPickerOverlay(
                    initialYearMonth = yearMonth,
                    today = today,
                    onDismiss = { showCalendarPicker = false },
                    onSelect = { date ->
                        viewModel.selectDate(date)
                        showCalendarPicker = false
                    },
                    sharedBoundsModifier = calendarSharedBounds,
                    textSharedModifier = calendarTextModifier,
                )
            } else {
                // 第二层 AnimatedContent：任务列表 ↔ 任务详情浮层
                AnimatedContent(
                    targetState = expandedTaskId,
                    transitionSpec = {
                        if (targetState != null) {
                            (EnterTransition.None togetherWith fadeOut(tween(250, easing = FastOutSlowInEasing)))
                                .using(SizeTransform(clip = false))
                        } else {
                            (fadeIn(tween(250, easing = FastOutSlowInEasing))
                                togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                                .using(SizeTransform(clip = false))
                        }
                    },
                    label = "taskDetail",
                ) { taskId ->
                    if (taskId != null) {
                        val task = viewModel.getFullTask(taskId)
                        if (task != null) {
                            TaskDetailOverlay(
                                task = task,
                                onDismiss = { expandedTaskId = null },
                                onDelete = { expandedTaskId = null; viewModel.deleteTask(task.id) },
                                onCreateSubtask = { _, _ -> },
                                onUpdateNotes = { notes -> viewModel.updateTaskDescription(taskId, notes) },
                                onUpdateRecurrence = { rule, time -> viewModel.updateRecurrence(taskId, rule, time) },
                                onRemoveRecurrence = { viewModel.removeRecurrence(taskId) },
                                onUpdateReminder = { reminder -> viewModel.updateReminder(taskId, reminder) },
                                sharedElementModifier = Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState("task_detail_$taskId"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    boundsTransform = { _, _ ->
                                        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                                    },
                                ),
                            )
                        }
                    } else {
                        ScheduleContent(
                            selectedDate = selectedDate,
                            dayLabel = dayLabel,
                            today = today,
                            tasks = viewModel.tasks,
                            viewModel = viewModel,
                            onSelectedDateChange = { viewModel.selectDate(it) },
                            onOpenCalendar = { showCalendarPicker = true },
                            onOpenCompanion = onOpenCompanion,
                            onTaskClick = { expandedTaskId = it },
                            sharedBoundsModifier = calendarSharedBounds,
                            textSharedModifier = calendarTextModifier,
                            reorderableItems = reorderableItems,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日程主界面内容 —— 顶栏 + 月份头部 + 周选择器 + 共享任务列表。
 *
 * @param selectedDate 当前选中日期
 * @param dayLabel "一"~"日" 的中文标签
 * @param today 今天的日期
 * @param tasks 当日任务列表（用于判断空状态）
 * @param viewModel 日程 ViewModel，提供 flatTodoItems / flatDoneItems 等状态
 * @param onSelectedDateChange 日期变更回调
 * @param onOpenCalendar 点击月份头部时打开日历弹窗
 * @param onOpenCompanion 点击顶栏伙伴图标回调
 * @param onTaskClick 点击任务卡片时回调，传入 taskId 以打开详情浮层
 * @param sharedBoundsModifier shared element 动画 modifier（容器 bounds）
 * @param textSharedModifier shared element 动画 modifier（月份文字）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleContent(
    selectedDate: LocalDate,
    dayLabel: String,
    today: LocalDate,
    tasks: List<ScheduleTaskItem>,
    viewModel: ScheduleViewModel,
    onSelectedDateChange: (LocalDate) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenCompanion: () -> Unit,
    onTaskClick: (String) -> Unit,
    sharedBoundsModifier: Modifier,
    textSharedModifier: Modifier,
    reorderableItems: SnapshotStateList<FlatTaskItem>,
) {
    val yearMonth = YearMonth.from(selectedDate)
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("日程", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "周${dayLabel} · ${selectedDate.monthValue}月${selectedDate.dayOfMonth}日",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = onOpenCompanion) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "伙伴", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            MonthHeader(
                yearMonth = yearMonth,
                onPrev = { onSelectedDateChange(selectedDate.minusMonths(1)) },
                onNext = { onSelectedDateChange(selectedDate.plusMonths(1)) },
                onClick = onOpenCalendar,
                modifier = sharedBoundsModifier.padding(horizontal = 16.dp),
                textSharedModifier = textSharedModifier,
            )

            Spacer(modifier = Modifier.height(10.dp))

            // WeekDaySelector 本身 fillMaxWidth，包一层 Box 加水平内边距
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                WeekDaySelector(
                    selectedDate = selectedDate,
                    today = today,
                    onSelect = onSelectedDateChange,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                "日程安排",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(160.dp)
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无日程", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("好好休息吧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY))
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))

                SharedTaskList(
                    todoItems = viewModel.flatTodoItems,
                    doneItems = viewModel.flatDoneItems,
                    overdueTasks = emptyList(),
                    onToggleDone = { task ->
                        val scheduleItem = tasks.find { it.id == task.id }
                        if (scheduleItem != null) {
                            viewModel.toggleDone(task.id, selectedDate, scheduleItem.isDone, scheduleItem.isDaily)
                        }
                    },
                    onTaskClick = { task -> onTaskClick(task.id) },
                    onToggleOverdueDailyDone = { _, _, _ -> },
                    onToggleSelection = {},
                    reorderCallback = { draggedId, _, siblingIds ->
                        viewModel.reorderTasks(draggedId, null, siblingIds)
                    },
                    reorderableItems = reorderableItems,
                    isSelectionMode = false,
                    selectedIds = emptySet(),
                    taskSharedModifier = { Modifier },
                    innerPadding = PaddingValues(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 日历弹窗的半透明遮罩层 —— 点击背景关闭弹窗。
 *
 * @param initialYearMonth 初始显示的年月
 * @param today 今天的日期
 * @param onDismiss 关闭弹窗回调
 * @param onSelect 选中日期回调
 * @param sharedBoundsModifier shared element 动画 modifier（容器 bounds）
 * @param textSharedModifier shared element 动画 modifier（月份文字）
 */
@Composable
private fun CalendarPickerOverlay(
    initialYearMonth: YearMonth,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    sharedBoundsModifier: Modifier,
    textSharedModifier: Modifier,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = NionAlpha.OVERLAY_SCRIM))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CalendarPickerDialog(
            initialYearMonth = initialYearMonth,
            today = today,
            onDismiss = onDismiss,
            onSelect = onSelect,
            modifier = sharedBoundsModifier,
            textSharedModifier = textSharedModifier,
        )
    }
}

/**
 * 月份头部组件 —— 显示 "yyyy年 M月"，左右箭头切换月份，点击弹日历。
 *
 * @param yearMonth 当前显示的年月
 * @param onPrev 点击左箭头时回调
 * @param onNext 点击右箭头时回调
 * @param onClick 点击年月文字时回调（打开日历弹窗）
 * @param modifier 外部 shared element modifier（容器 bounds）
 * @param textSharedModifier shared element 动画 modifier（月份文字）
 */
@Composable
private fun MonthHeader(
    yearMonth: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textSharedModifier: Modifier = Modifier,
) {
    val formatter = DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINESE)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月")
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    yearMonth.format(formatter),
                    modifier = textSharedModifier,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月")
            }
        }
    }
}

/**
 * 周日期选择器 —— 显示当前周的 7 天，支持左右滑动切换周次。
 *
 * 使用 HorizontalPager 实现滑动切换，预渲染相邻两周，确保滑动时能看到前/后一周。
 * Pager 的 pageCount 设为 Int.MAX_VALUE，初始页居中，通过 pageOffset 计算实际日期。
 *
 * @param selectedDate 当前选中的日期，用于高亮显示
 * @param today 今天的日期，用于标记"今天"圆点
 * @param onSelect 用户点击某一天时触发，回调传入被点击的 LocalDate
 */
@Composable
private fun WeekDaySelector(
    selectedDate: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    // START_PAGE 居中，避免用户滑动到边界
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(
        initialPage = startPage,
        pageCount = { Int.MAX_VALUE },
    )

    HorizontalPager(
        state = pagerState,
        // 预渲染当前页两侧各 1 页，滑动时能提前看到相邻周
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        // pageOffset: 相对于初始页的页偏移量，用于计算该页对应的周一日期
        val pageOffset = page - startPage
        val startOfWeek = remember(today, pageOffset) {
            today.with(DayOfWeek.MONDAY).plusWeeks(pageOffset.toLong())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            DayOfWeek.entries.forEachIndexed { index, _ ->
                val date = startOfWeek.plusDays(index.toLong())
                val isSelected = date == selectedDate
                val isToday = date == today

                // 选中/今天的背景色过渡动画
                val bgColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        // "今天"标记使用 tertiaryContainer（装饰性区分）
                        isToday -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> Color.Transparent
                    },
                    animationSpec = tween(200),
                    label = "dowBg$index",
                )
                // 选中/今天的文字色过渡动画
                val textColor by animateColorAsState(
                    targetValue = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        // "今天"文字配合 tertiaryContainer
                        isToday -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(200),
                    label = "dowText$index",
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(date) },
                    ),
                ) {
                    Text(
                        dayLabels[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = bgColor,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                color = textColor,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日历选择对话框 —— 显示月视图日历，支持左右滑动切换月份。
 *
 * 使用 HorizontalPager 包裹月网格，预渲染相邻月份，滑动时能看到前/后一个月。
 * 顶部 header 的年月文字和箭头按钮与 pager 状态双向同步。
 * 网格高度固定为 6 行，避免不同月份行数不同导致滑动时高度跳动。
 *
 * @param initialYearMonth 对话框打开时初始显示的年月
 * @param today 今天的日期，用于高亮标记
 * @param onDismiss 用户点击背景区域关闭对话框时触发
 * @param onSelect 用户点击某一天时触发，回调传入被点击的 LocalDate
 * @param modifier 外部传入的 modifier（用于 shared element 动画容器 bounds）
 * @param textSharedModifier shared element 动画 modifier（月份文字，从 MonthHeader 飞入）
 */
@Composable
private fun CalendarPickerDialog(
    initialYearMonth: YearMonth,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    textSharedModifier: Modifier = Modifier,
) {
    // START_PAGE 居中，避免用户滑动到边界
    val startPage = Int.MAX_VALUE / 2
    val pagerState = rememberPagerState(
        initialPage = startPage,
        pageCount = { Int.MAX_VALUE },
    )
    val scope = rememberCoroutineScope()

    // monthOffset: 当前 pager settled 页相对于初始页的偏移量
    val monthOffset = pagerState.settledPage - startPage
    // header 显示的月份跟随 pager 滑动
    val displayedYearMonth = remember(initialYearMonth, monthOffset) {
        initialYearMonth.plusMonths(monthOffset.toLong())
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            // 16dp 与 ScheduleContent 的 LazyColumn padding 一致，
            // 避免收回时因宽度差异导致"日历变宽"的视觉 bug
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            // 月份 header：箭头按钮和年月文字，与 pager 双向同步
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上月")
                }
                Text(
                    displayedYearMonth.format(
                        DateTimeFormatter.ofPattern("yyyy年 M月", Locale.CHINESE)
                    ),
                    modifier = textSharedModifier,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下月")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 星期标签行，固定不变
            Row(modifier = Modifier.fillMaxWidth()) {
                dayLabels.forEach { label ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 月网格 pager：每页是一个月的日期网格，高度固定 6 行避免跳动
            HorizontalPager(
                state = pagerState,
                // 预渲染当前页两侧各 1 页，滑动时能提前看到相邻月
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(264.dp),
            ) { page ->
                // pageOffset: 相对于初始页的偏移量，用于计算该页对应的年月
                val pageOffset = page - startPage
                val month = remember(initialYearMonth, pageOffset) {
                    initialYearMonth.plusMonths(pageOffset.toLong())
                }
                CalendarMonthGrid(
                    yearMonth = month,
                    today = today,
                    onSelect = onSelect,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // "回到今天" 按钮
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(today) },
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "回到今天",
                        style = MaterialTheme.typography.labelLarge,
                        // 次要导航按钮使用 secondary
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * 单个月的日期网格 —— 渲染指定年月的日历格子。
 *
 * 固定渲染 6 行，空位补齐，避免不同月份行数不同导致高度变化。
 *
 * @param yearMonth 要渲染的年月
 * @param today 今天的日期，用于高亮标记
 * @param onSelect 用户点击某一天时触发，回调传入被点击的 LocalDate
 */
@Composable
private fun CalendarMonthGrid(
    yearMonth: YearMonth,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val firstDay = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()
    val startDow = firstDay.dayOfWeek.value - 1
    // 始终渲染 6 行，保持高度一致
    val rows = 6

    Column {
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val day = cellIndex - startDow + 1
                    val isValid = day in 1..daysInMonth
                    val date = if (isValid) yearMonth.atDay(day) else null
                    val isToday = date == today

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    // "今天"标记使用 tertiary（装饰性）
                                    isToday -> MaterialTheme.colorScheme.tertiary.copy(alpha = NionAlpha.BG_HIGHLIGHT)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isValid,
                                onClick = {
                                    if (date != null) {
                                        onSelect(date)
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isValid) {
                            Text(
                                day.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
