package com.echonion.nion.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echonion.nion.MainActivity
import com.echonion.nion.NionApp
import com.echonion.nion.core
import com.echonion.nion.ui.components.DualPanelLayout
import com.echonion.nion.ui.components.DualPanelState
import com.echonion.nion.ui.companion.CompanionSidebar
import com.echonion.nion.ui.companion.GreetingOverlay
import com.echonion.nion.ui.companion.WeatherAlertOverlay
import com.echonion.nion.ui.companion.tools.DataType
import com.echonion.nion.ui.focus.CompletionOverlay
import com.echonion.nion.ui.focus.FocusScreen
import com.echonion.nion.ui.focus.FocusStatsPanel
import com.echonion.nion.ui.schedule.ScheduleScreen
import com.echonion.nion.ui.settings.SettingsScreen
import com.echonion.nion.ui.task.ReminderOverlay
import com.echonion.nion.ui.task.SidebarContent
import com.echonion.nion.ui.task.TaskScreen
import com.echonion.nion.ui.task.TaskViewModel
import com.echonion.nion.ui.task.taskViewModel
import com.echonion.nion.ui.theme.CustomThemeEntry
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.NionTheme
import com.echonion.nion.ui.theme.ThemePalette
import kotlinx.coroutines.launch

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem("tasks", "任务", Icons.Default.CheckCircle),
    BottomNavItem("schedule", "日程", Icons.Default.CalendarMonth),
    BottomNavItem("pomodoro", "专注", Icons.Default.Timer),
    BottomNavItem("settings", "设置", Icons.Default.Settings),
)

/** Fade Through 过渡动画：旧页面先淡出，再淡入新页面，中间有短暂空白帧 */
private val fadeThroughEnter = androidx.compose.animation.fadeIn(
    animationSpec = tween(
        durationMillis = 200,
        delayMillis = 150,
        easing = FastOutSlowInEasing,
    ),
)

/** Fade Through 过渡动画：旧页面淡出 */
private val fadeThroughExit = androidx.compose.animation.fadeOut(
    animationSpec = tween(
        durationMillis = 150,
        easing = FastOutSlowInEasing,
    ),
)

/**
 * 应用主入口 Composable。
 *
 * 负责初始化全局状态（主题、导航、双面板）、处理通知跳转 Intent、
 * 搭建 NionTheme -> Box -> Scaffold -> DualPanelLayout + NavHost 的整体布局框架。
 * 各页面的具体内容已拆分到独立的 Route composable 中以降低复杂度。
 */
@Composable
fun NionApp() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val core = app.core()
    // 当前主题色板：预设主题通过 palette() 获取，自定义主题从 JSON 反序列化
    var themePalette by remember {
        mutableStateOf(loadThemePalette(core))
    }
    // 当前激活的预设名称（null 表示使用自定义主题），用于 SettingsScreen 高亮预设卡片
    var currentPresetName by remember {
        mutableStateOf(loadPresetName(core))
    }
    val dualState = remember { DualPanelState() }
    val viewModel = taskViewModel()
    val coroutineScope = rememberCoroutineScope()
    val navController = rememberNavController()

    /** 从任务详情/通知跳转专注页面时，携带的预选任务信息 */
    var preselectedFocusTaskId by remember { mutableStateOf<String?>(null) }
    var preselectedFocusTaskTitle by remember { mutableStateOf<String?>(null) }
    /** 从任务详情跳转时携带的预选专注时长（分钟），null 表示未设置 */
    var preselectedFocusDuration by remember { mutableStateOf<Int?>(null) }
    /** 从任务详情跳转时是否自动启动计时器 */
    var autoStartFocus by remember { mutableStateOf(false) }

    // 监听 SETTINGS 数据变更事件（AI 伴伴工具修改主题时触发），实时刷新色板
    LaunchedEffect(Unit) {
        (app as NionApp).dataEvents.collect { event ->
            if (DataType.SETTINGS in event.types) {
                themePalette = loadThemePalette(core)
                currentPresetName = loadPresetName(core)
            }
        }
    }

    // ── 处理来自通知栏的 Intent ──
    val activity = context as? MainActivity
    LaunchedEffect(activity?.pendingIntentAction) {
        activity?.pendingIntentAction?.let { action ->
            when (action) {
                // 「开始做了」→ 跳转专注页面
                "start_focus" -> {
                    val taskId = activity.pendingTaskId
                    val taskTitle = activity.pendingTaskTitle ?: ""
                    if (taskId != null) {
                        navigateToFocus(
                            navController = navController,
                            taskId = taskId,
                            taskTitle = taskTitle,
                            autoStart = true,
                            onSetPreselected = { id, title, dur, auto ->
                                preselectedFocusTaskId = id
                                preselectedFocusTaskTitle = title
                                preselectedFocusDuration = dur
                                autoStartFocus = auto
                            },
                        )
                    }
                    activity.clearPendingIntent()
                }
                // 点击通知主体 → 展开伙伴面板
                "open_companion" -> {
                    coroutineScope.launch { dualState.openRight() }
                    activity.clearPendingIntent()
                }
            }
        }
    }

    /**
     * 导航到专注页面的通用逻辑：设置预选参数后跳转。
     * 提取为局部函数避免在多处重复 preselected 赋值 + navigate 组合。
     */
    fun doNavigateToFocus(
        taskId: String,
        taskTitle: String,
        durationMinutes: Int? = null,
        autoStart: Boolean = true,
    ) {
        preselectedFocusTaskId = taskId
        preselectedFocusTaskTitle = taskTitle
        preselectedFocusDuration = durationMinutes
        autoStartFocus = autoStart
        navigateToRoute(navController, "pomodoro")
    }

    NionTheme(palette = themePalette) {
        // 用 Box 包裹，让悬浮卡片能覆盖在 Scaffold 上方
        Box {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            // 侧边栏返回拦截：任一侧边栏打开时，系统返回手势关闭侧边栏而非退出页面
            BackHandler(enabled = dualState.isOpen) {
                coroutineScope.launch { dualState.closePanel() }
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NionBottomNavBar(
                        currentDestination = currentDestination,
                        onItemClick = { item ->
                            /* 点击底部导航时，先收起已打开的侧边面板，再执行跳转 */
                            coroutineScope.launch {
                                dualState.closePanel()
                                navigateToRoute(navController, item.route)
                            }
                        },
                    )
                },
            ) { innerPadding ->
                val currentRoute = currentDestination?.route
                DualPanelLayout(
                    leftPanelWidth = if (currentRoute == "pomodoro") 300.dp else 260.dp,
                    rightPanelWidth = 320.dp,
                    state = dualState,
                    enableLeftSwipe = currentRoute == "tasks" || currentRoute == "pomodoro",
                    modifier = Modifier.padding(innerPadding),
                    leftPanel = { onDrag, onDragStopped, modifier ->
                        NionLeftPanel(
                            currentRoute = currentRoute,
                            viewModel = viewModel,
                            dualState = dualState,
                            onDrag = onDrag,
                            onDragStopped = onDragStopped,
                            modifier = modifier,
                        )
                    },
                    rightPanel = { onDrag, onDragStopped, modifier ->
                        CompanionSidebar(
                            onSidebarDrag = onDrag,
                            onSidebarDragStopped = onDragStopped,
                            isVisible = dualState.isRightOpen,
                            currentRoute = currentRoute,
                            modifier = modifier,
                        )
                    },
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "tasks",
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { fadeThroughEnter },
                        exitTransition = { fadeThroughExit },
                        popEnterTransition = { fadeThroughEnter },
                        popExitTransition = { fadeThroughExit },
                    ) {
                        composable("tasks") {
                            TasksRoute(
                                dualState = dualState,
                                viewModel = viewModel,
                                onStartFocus = { taskId, taskTitle, durationMinutes ->
                                    doNavigateToFocus(taskId, taskTitle, durationMinutes, autoStart = true)
                                    coroutineScope.launch { dualState.closePanel() }
                                },
                            )
                        }
                        composable("schedule") {
                            ScheduleRoute(
                                onOpenCompanion = { dualState.openRight() },
                            )
                        }
                        composable("pomodoro") {
                            FocusRoute(
                                onOpenCompanion = { dualState.openRight() },
                                preselectedTaskId = preselectedFocusTaskId,
                                preselectedTaskTitle = preselectedFocusTaskTitle,
                                preselectedDuration = preselectedFocusDuration,
                                autoStart = autoStartFocus,
                                onPreselectedConsumed = {
                                    preselectedFocusTaskId = null
                                    preselectedFocusTaskTitle = null
                                    preselectedFocusDuration = null
                                    autoStartFocus = false
                                },
                            )
                        }
                        composable("settings") {
                            SettingsRoute(
                                core = core,
                                app = app,
                                currentPresetName = currentPresetName,
                                themePalette = themePalette,
                                onThemePaletteChange = { palette, presetName ->
                                    themePalette = palette
                                    currentPresetName = presetName
                                },
                                onOpenCompanion = { dualState.openRight() },
                            )
                        }
                    }
                }
            }

            // 全局悬浮层
            NionGlobalOverlays(
                app = app,
                onStartFocus = { taskId, taskTitle ->
                    doNavigateToFocus(taskId, taskTitle, autoStart = false)
                },
            )
        }
    }
}

// ──────────────────────────────────────────────────────
// 提取的子 Composable
// ──────────────────────────────────────────────────────

/**
 * 底部导航栏。
 *
 * 显示任务、日程、专注、设置四个导航项，根据当前路由高亮选中项。
 *
 * @param currentDestination 当前导航目标，用于判断哪个 tab 被选中
 * @param onItemClick 用户点击导航项时触发，回调传入被点击的 BottomNavItem；
 *   调用方负责关闭侧边面板并执行路由跳转
 */
@Composable
private fun NionBottomNavBar(
    currentDestination: NavDestination?,
    onItemClick: (BottomNavItem) -> Unit,
) {
    NavigationBar(
        tonalElevation = 3.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        bottomNavItems.forEach { item ->
            val isSelected = currentDestination?.hierarchy?.any { it.route == item.route } == true
            NavigationBarItem(
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                    )
                },
                selected = isSelected,
                onClick = { onItemClick(item) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/**
 * DualPanelLayout 的左侧面板内容。
 *
 * 根据当前路由显示不同内容：
 * - pomodoro 路由 → 显示专注统计面板 FocusStatsPanel
 * - 其他路由 → 显示清单侧边栏 SidebarContent
 *
 * @param currentRoute 当前导航路由名称
 * @param viewModel 任务 ViewModel，提供清单数据和操作方法
 * @param dualState 双面板状态，用于选中清单后关闭左侧面板
 * @param onDrag 侧边栏拖拽回调，由 DualPanelLayout 传入，传递拖拽偏移量
 * @param onDragStopped 侧边栏拖拽结束回调，由 DualPanelLayout 传入
 * @param modifier 由 DualPanelLayout 传入的布局修饰符（含宽度约束）
 */
@Composable
private fun NionLeftPanel(
    currentRoute: String?,
    viewModel: TaskViewModel,
    dualState: DualPanelState,
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    modifier: Modifier,
) {
    if (currentRoute == "pomodoro") {
        FocusStatsPanel(
            onSidebarDrag = onDrag,
            onSidebarDragStopped = onDragStopped,
            modifier = modifier,
        )
    } else {
        // 将 nullable key 转为 non-null map，供 SidebarContent 使用
        val customCounts = remember(viewModel.checklistCounts) {
            viewModel.checklistCounts.mapNotNull { (k, v) -> k?.let { it to v } }.toMap()
        }
        SidebarContent(
            checklists = viewModel.checklists,
            activeChecklistId = viewModel.activeChecklistId,
            defaultCounts = viewModel.checklistCounts[TaskViewModel.TODAY_ID] ?: (0 to 0),
            inboxCounts = viewModel.checklistCounts[null] ?: (0 to 0),
            customCounts = customCounts,
            onSelectChecklist = {
                dualState.closeLeft()
                viewModel.setActiveChecklist(it)
            },
            onAddChecklist = { name ->
                viewModel.createChecklist(name)
            },
            onDeleteChecklist = { viewModel.deleteChecklist(it) },
            onReorderChecklists = { viewModel.reorderChecklists(it) },
            onSidebarDrag = onDrag,
            onSidebarDragStopped = onDragStopped,
            modifier = modifier,
        )
    }
}

/**
 * 任务页面路由。
 *
 * 渲染 TaskScreen，将专注按钮点击事件通过回调传递给上层处理导航。
 *
 * @param dualState 双面板状态，传递给 TaskScreen 用于面板联动
 * @param viewModel 任务 ViewModel，提供任务数据和操作方法
 * @param onStartFocus 用户点击任务详情中的专注按钮时触发，
 *   回调传入 (taskId, taskTitle, durationMinutes)；
 *   调用方负责设置预选参数并导航到专注页
 */
@Composable
private fun TasksRoute(
    dualState: DualPanelState,
    viewModel: TaskViewModel,
    onStartFocus: (String, String, Int?) -> Unit,
) {
    TaskScreen(
        dualState = dualState,
        viewModel = viewModel,
        /** 点击任务详情中的专注按钮：立即导航到专注页，面板关闭在后台并行 */
        onStartFocus = onStartFocus,
    )
}

/**
 * 日程页面路由。
 *
 * @param onOpenCompanion 用户请求打开伙伴侧边栏时触发
 */
@Composable
private fun ScheduleRoute(
    onOpenCompanion: () -> Unit,
) {
    ScheduleScreen(onOpenCompanion = onOpenCompanion)
}

/**
 * 专注页面路由。
 *
 * 渲染 FocusScreen 并在预选任务信息被消费后通过回调通知上层清除，
 * 避免导航回退时重复预选。
 *
 * @param onOpenCompanion 用户请求打开伙伴侧边栏时触发
 * @param preselectedTaskId 预选任务 ID，从任务详情或通知跳转时携带
 * @param preselectedTaskTitle 预选任务标题
 * @param preselectedDuration 预选专注时长（分钟），null 表示未设置
 * @param autoStart 是否自动启动计时器
 * @param onPreselectedConsumed 预选任务信息被 FocusScreen 消费后触发，
 *   调用方应清除 preselectedTaskId 等状态，防止回退时重复预选
 */
@Composable
private fun FocusRoute(
    onOpenCompanion: () -> Unit,
    preselectedTaskId: String?,
    preselectedTaskTitle: String?,
    preselectedDuration: Int?,
    autoStart: Boolean,
    onPreselectedConsumed: () -> Unit,
) {
    FocusScreen(
        onOpenCompanion = onOpenCompanion,
        preselectedTaskId = preselectedTaskId,
        preselectedTaskTitle = preselectedTaskTitle,
        preselectedDuration = preselectedDuration,
        autoStart = autoStart,
    )
    // 预选任务信息在传递给 FocusScreen 后消费掉，避免导航回退时重复预选
    LaunchedEffect(preselectedTaskId) {
        if (preselectedTaskId != null) {
            onPreselectedConsumed()
        }
    }
}

/**
 * 设置页面路由。
 *
 * 内部管理自定义主题列表状态，处理预设主题切换、自定义主题的
 * 选择/删除/重命名等操作，并通过回调将主题变更同步给父级。
 *
 * @param core NionCore 单例，用于读写持久化设置
 * @param app Application 实例（实际为 NionApp），用于收集 SETTINGS 数据变更事件
 * @param currentPresetName 当前激活的预设主题名称（null 表示自定义主题）
 * @param themePalette 当前主题色板
 * @param onThemePaletteChange 主题色板变更时触发，回调传入 (新色板, 新预设名称或 null)；
 *   调用方负责更新 themePalette 和 currentPresetName 状态
 * @param onOpenCompanion 用户请求打开伙伴侧边栏时触发
 */
@Composable
private fun SettingsRoute(
    core: uniffi.nion_core.NionCore,
    app: Application,
    currentPresetName: String?,
    themePalette: ThemePalette,
    onThemePaletteChange: (ThemePalette, String?) -> Unit,
    onOpenCompanion: () -> Unit,
) {
    // 将 customThemes 提升为 remember state，
    // 使得重命名/删除等操作可以直接更新 state 触发 UI 刷新
    var customThemes by remember {
        mutableStateOf(loadCustomThemesList(core))
    }

    // 监听 AI 工具修改主题后发出的 SETTINGS 事件，刷新自定义主题列表
    LaunchedEffect(Unit) {
        (app as NionApp).dataEvents.collect { event ->
            if (DataType.SETTINGS in event.types) {
                customThemes = loadCustomThemesList(core)
            }
        }
    }

    SettingsScreen(
        currentPresetName = currentPresetName,
        themePalette = themePalette,
        customThemes = customThemes,
        onThemeChange = { theme ->
            onThemePaletteChange(theme.palette(), theme.name)
            try {
                core.setSetting("theme_mode", "preset")
                core.setSetting("color_theme", theme.name)
                core.setSetting("active_custom_theme_id", "")
            } catch (_: Exception) {}
        },
        onCustomThemeSelect = { entry ->
            onThemePaletteChange(entry.palette, null)
            try {
                core.setSetting("theme_mode", "custom")
                core.setSetting("active_custom_theme_id", entry.id)
            } catch (_: Exception) {}
        },
        onCustomThemeDelete = { id ->
            try {
                val themes = customThemes.toMutableList()
                themes.removeAll { it.id == id }
                core.setSetting("custom_themes_list", CustomThemeEntry.listToJson(themes))
                // 如果删除的是当前活跃主题，切换到 CORAL
                val activeId = core.getSetting("active_custom_theme_id") ?: ""
                if (activeId == id) {
                    core.setSetting("theme_mode", "preset")
                    core.setSetting("color_theme", "CORAL")
                    core.setSetting("active_custom_theme_id", "")
                    onThemePaletteChange(NionColorTheme.CORAL.palette(), "CORAL")
                }
                customThemes = themes
            } catch (_: Exception) {}
        },
        onCustomThemeRename = { id, newName ->
            try {
                val themes = customThemes.toMutableList()
                val idx = themes.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    themes[idx] = themes[idx].copy(name = newName)
                    core.setSetting("custom_themes_list", CustomThemeEntry.listToJson(themes))
                    customThemes = themes
                }
            } catch (_: Exception) {}
        },
        onOpenCompanion = onOpenCompanion,
    )
}

/**
 * 全局悬浮层容器。
 *
 * 包含提醒、问候、天气预警、专注完成鼓励等悬浮卡片，
 * 放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上。
 *
 * @param app Application 实例（实际为 NionApp），各悬浮卡片用于读取数据/事件
 * @param onStartFocus 用户点击提醒卡片右上角专注图标时触发，
 *   回调传入 (taskId, taskTitle)；不自动开始计时
 */
@Composable
private fun NionGlobalOverlays(
    app: Application,
    onStartFocus: (String, String) -> Unit,
) {
    // 全局提醒悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
    ReminderOverlay(app = app, onStartFocus = onStartFocus)
    // 全局问候悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
    GreetingOverlay(app = app)
    // 全局天气预警悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
    WeatherAlertOverlay(app = app)
    // 全局专注完成鼓励悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
    CompletionOverlay(app = app)
}

// ──────────────────────────────────────────────────────
// 辅助函数
// ──────────────────────────────────────────────────────

/**
 * 导航到指定路由，使用标准的 saveState/restoreState 配置。
 *
 * 统一封装 navController.navigate() 的样板参数，避免在多处重复
 * popUpTo / launchSingleTop / restoreState 组合。
 *
 * @param navController 导航控制器
 * @param route 目标路由名称
 */
private fun navigateToRoute(
    navController: NavController,
    route: String,
) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * 从通知 Intent 跳转专注页面时使用的导航辅助函数。
 *
 * 设置预选参数后通过 navigateToRoute 跳转，避免在 LaunchedEffect 内
 * 重复 preselected 赋值 + navigate 组合。
 *
 * @param navController 导航控制器
 * @param taskId 预选任务 ID
 * @param taskTitle 预选任务标题
 * @param autoStart 是否自动开始计时
 * @param onSetPreselected 设置预选状态的回调，传入 (id, title, duration, autoStart)
 */
private fun navigateToFocus(
    navController: NavController,
    taskId: String,
    taskTitle: String,
    autoStart: Boolean,
    onSetPreselected: (String, String, Int?, Boolean) -> Unit,
) {
    onSetPreselected(taskId, taskTitle, null, autoStart)
    navigateToRoute(navController, "pomodoro")
}

/**
 * 从 NionCore 设置中加载当前主题色板。
 *
 * 读取顺序：
 * 1. `theme_mode` — "preset" 或 "custom"
 * 2. 如果是 preset：读取 `color_theme` 获取预设名称，通过枚举的 palette() 返回
 * 3. 如果是 custom：从 `custom_themes_list` 中按 `active_custom_theme_id` 查找对应条目
 *
 * @param core NionCore 单例
 * @return 当前主题色板，读取失败时回退到 CORAL 预设
 */
private fun loadThemePalette(core: uniffi.nion_core.NionCore): ThemePalette {
    return try {
        val mode = core.getSetting("theme_mode") ?: "preset"
        when (mode) {
            "custom" -> {
                val activeId = core.getSetting("active_custom_theme_id") ?: ""
                if (activeId.isBlank()) return NionColorTheme.CORAL.palette()
                val themes = loadCustomThemesList(core)
                val entry = themes.find { it.id == activeId }
                    ?: return NionColorTheme.CORAL.palette()
                entry.palette
            }
            else -> {
                val name = core.getSetting("color_theme") ?: "CORAL"
                val preset = NionColorTheme.entries.find { it.name == name } ?: NionColorTheme.CORAL
                preset.palette()
            }
        }
    } catch (_: Exception) {
        NionColorTheme.CORAL.palette()
    }
}

/**
 * 从 NionCore 设置中读取当前预设主题名称。
 *
 * @param core NionCore 单例
 * @return 预设名称（如 "CORAL"），使用自定义主题时返回 null
 */
private fun loadPresetName(core: uniffi.nion_core.NionCore): String? {
    return try {
        val mode = core.getSetting("theme_mode") ?: "preset"
        if (mode == "custom") null
        else core.getSetting("color_theme") ?: "CORAL"
    } catch (_: Exception) {
        "CORAL"
    }
}

/**
 * 从设置中加载自定义主题列表。
 */
private fun loadCustomThemesList(core: uniffi.nion_core.NionCore): List<CustomThemeEntry> {
    return try {
        val json = core.getSetting("custom_themes_list") ?: return emptyList()
        if (json.isBlank()) return emptyList()
        CustomThemeEntry.listFromJson(json)
    } catch (_: Exception) {
        emptyList()
    }
}
