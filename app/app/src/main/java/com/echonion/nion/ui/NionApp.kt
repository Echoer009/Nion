package com.echonion.nion.ui

import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.echonion.nion.core
import com.echonion.nion.ui.components.DualPanelLayout
import com.echonion.nion.ui.components.DualPanelState
import com.echonion.nion.ui.companion.CompanionSidebar
import com.echonion.nion.ui.focus.FocusScreen
import com.echonion.nion.ui.schedule.ScheduleScreen
import com.echonion.nion.ui.settings.SettingsScreen
import com.echonion.nion.ui.task.SidebarContent
import com.echonion.nion.ui.task.TaskScreen
import com.echonion.nion.ui.task.TaskViewModel
import com.echonion.nion.ui.task.taskViewModel
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.NionTheme

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
    val core = (context.applicationContext as android.app.Application).core()
    var colorTheme by remember {
        val saved = try { core.getSetting("color_theme") } catch (_: Exception) { null }
        val initial = saved?.let { name ->
            NionColorTheme.entries.find { it.name == name }
        } ?: NionColorTheme.BURNT_ORANGE
        mutableStateOf(initial)
    }

    val dualState = remember { DualPanelState() }
    val viewModel = taskViewModel()

    NionTheme(colorTheme = colorTheme) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

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
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            val currentRoute = currentDestination?.route
            DualPanelLayout(
                leftPanelWidth = 260.dp,
                rightPanelWidth = 320.dp,
                state = dualState,
                enableLeftSwipe = currentRoute == "tasks",
                modifier = Modifier.padding(innerPadding),
                leftPanel = { onDrag, onDragStopped, modifier ->
                    val customCounts = remember(viewModel.checklistCounts) {
                        viewModel.checklistCounts.mapNotNull { (k, v) -> k?.let { it to v } }.toMap()
                    }
                    SidebarContent(
                        checklists = viewModel.checklists,
                        activeChecklistId = viewModel.activeChecklistId,
                        defaultCounts = viewModel.checklistCounts[null] ?: (0 to 0),
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
                },
                rightPanel = { onDrag, onDragStopped, modifier ->
                    CompanionSidebar(
                        onSidebarDrag = onDrag,
                        onSidebarDragStopped = onDragStopped,
                        isVisible = dualState.isRightOpen,
                        modifier = modifier,
                    )
                },
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "tasks",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = tween(300),
                        )
                    },
                    exitTransition = {
                        androidx.compose.animation.fadeOut(
                            animationSpec = tween(150),
                        )
                    },
                    popEnterTransition = {
                        androidx.compose.animation.fadeIn(
                            animationSpec = tween(300),
                        )
                    },
                    popExitTransition = {
                        androidx.compose.animation.fadeOut(
                            animationSpec = tween(150),
                        )
                    },
                ) {
                    composable("tasks") {
                        TaskScreen(
                            dualState = dualState,
                            viewModel = viewModel,
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
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            currentTheme = colorTheme,
                            onThemeChange = {
                                colorTheme = it
                                try { core.setSetting("color_theme", it.name) } catch (_: Exception) {}
                            },
                            onOpenCompanion = { dualState.openRight() },
                        )
                    }
                }
            }
        }
    }
}
