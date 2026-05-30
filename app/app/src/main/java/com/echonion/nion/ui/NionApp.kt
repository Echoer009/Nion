package com.echonion.nion.ui

import android.app.Application
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echonion.nion.MainActivity
import com.echonion.nion.core
import com.echonion.nion.ui.components.DualPanelLayout
import com.echonion.nion.ui.components.DualPanelState
import com.echonion.nion.ui.companion.CompanionSidebar
import com.echonion.nion.ui.companion.GreetingOverlay
import com.echonion.nion.ui.companion.WeatherAlertOverlay
import com.echonion.nion.ui.focus.FocusScreen
import com.echonion.nion.ui.focus.FocusStatsPanel
import com.echonion.nion.ui.schedule.ScheduleScreen
import com.echonion.nion.ui.settings.SettingsScreen
import com.echonion.nion.ui.task.SidebarContent
import com.echonion.nion.ui.task.TaskScreen
import com.echonion.nion.ui.task.TaskViewModel
import com.echonion.nion.ui.task.ReminderOverlay
import com.echonion.nion.ui.task.taskViewModel
import kotlinx.coroutines.launch
import com.echonion.nion.NionApp
import com.echonion.nion.ui.companion.tools.DataType
import com.echonion.nion.ui.theme.CustomThemeEntry
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.NionTheme
import com.echonion.nion.ui.theme.ThemePalette
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler

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
                        preselectedFocusTaskId = taskId
                        preselectedFocusTaskTitle = taskTitle
                        preselectedFocusDuration = null
                        autoStartFocus = true
                        navController.navigate("pomodoro") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    activity.clearPendingIntent()
                }
                // 点击通知主体 → 展开伙伴面板
                "open_companion" -> {
                    coroutineScope.launch {
                        dualState.openRight()
                    }
                    activity.clearPendingIntent()
                }
            }
        }
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
                                onClick = {
                                    /* 点击底部导航时，先收起已打开的侧边面板，再执行跳转 */
                                    coroutineScope.launch {
                                        dualState.closePanel()
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
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
                        if (currentRoute == "pomodoro") {
                            FocusStatsPanel(
                                onSidebarDrag = onDrag,
                                onSidebarDragStopped = onDragStopped,
                                modifier = modifier,
                            )
                        } else {
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
                        /* Fade Through：旧页面先淡出，再淡入新页面，中间有短暂空白帧 */
                        enterTransition = {
                            androidx.compose.animation.fadeIn(
                                animationSpec = tween(
                                    durationMillis = 200,
                                    delayMillis = 150,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        },
                        exitTransition = {
                            androidx.compose.animation.fadeOut(
                                animationSpec = tween(
                                    durationMillis = 150,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        },
                        popEnterTransition = {
                            androidx.compose.animation.fadeIn(
                                animationSpec = tween(
                                    durationMillis = 200,
                                    delayMillis = 150,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        },
                        popExitTransition = {
                            androidx.compose.animation.fadeOut(
                                animationSpec = tween(
                                    durationMillis = 150,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        },
                    ) {
                        composable("tasks") {
                            TaskScreen(
                                dualState = dualState,
                                viewModel = viewModel,
                                /** 点击任务详情中的专注按钮：立即导航到专注页，面板关闭在后台并行 */
                                onStartFocus = { taskId, taskTitle, durationMinutes ->
                                    preselectedFocusTaskId = taskId
                                    preselectedFocusTaskTitle = taskTitle
                                    preselectedFocusDuration = durationMinutes
                                    autoStartFocus = true
                                    navController.navigate("pomodoro") {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    coroutineScope.launch {
                                        dualState.closePanel()
                                    }
                                },
                            )
                        }
                        composable("schedule") {
                            ScheduleScreen(
                                onOpenCompanion = { dualState.openRight() },
                            )
                        }
                        composable("pomodoro") {
                            FocusScreen(
                                onOpenCompanion = { dualState.openRight() },
                                preselectedTaskId = preselectedFocusTaskId,
                                preselectedTaskTitle = preselectedFocusTaskTitle,
                                preselectedDuration = preselectedFocusDuration,
                                autoStart = autoStartFocus,
                            )
                            /**
                             * 预选任务信息在传递给 FocusScreen 后消费掉，
                             * 避免导航回退时重复预选。
                             */
                            LaunchedEffect(preselectedFocusTaskId) {
                                if (preselectedFocusTaskId != null) {
                                    preselectedFocusTaskId = null
                                    preselectedFocusTaskTitle = null
                                    preselectedFocusDuration = null
                                    autoStartFocus = false
                                }
                            }
                        }
                        composable("settings") {
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
                                onThemeChange = {
                                    themePalette = it.palette()
                                    currentPresetName = it.name
                                    try {
                                        core.setSetting("theme_mode", "preset")
                                        core.setSetting("color_theme", it.name)
                                        core.setSetting("active_custom_theme_id", "")
                                    } catch (_: Exception) {}
                                },
                                onCustomThemeSelect = { entry ->
                                    themePalette = entry.palette
                                    currentPresetName = null
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
                                            themePalette = NionColorTheme.CORAL.palette()
                                            currentPresetName = "CORAL"
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
                                onOpenCompanion = { dualState.openRight() },
                            )
                        }
                    }
                }
            }

            // 全局提醒悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
            // onStartFocus 回调：用户点击右上角专注图标 → 跳转专注页面（不自动开始）
            ReminderOverlay(
                app = app,
                onStartFocus = { taskId, taskTitle ->
                    preselectedFocusTaskId = taskId
                    preselectedFocusTaskTitle = taskTitle
                    preselectedFocusDuration = null
                    autoStartFocus = false
                    navController.navigate("pomodoro") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )

            // 全局问候悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
            GreetingOverlay(app = app)

            // 全局天气预警悬浮卡片，放在 Scaffold 之后（Z 轴上层），确保浮在所有界面之上
            WeatherAlertOverlay(app = app)
        }
    }
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
