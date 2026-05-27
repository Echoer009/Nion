package com.echonion.nion.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echonion.nion.core
import com.echonion.nion.reminder.GreetingScheduler
import com.echonion.nion.reminder.WeatherAlertScheduler
import com.echonion.nion.ui.companion.weather.LocationHelper
import com.echonion.nion.ui.task.WheelSpinner
import com.echonion.nion.ui.theme.NionColorTheme

/**
 * 设置页面 —— 应用配置中心。
 *
 * 包含：
 * - 主题颜色选择
 * - 伙伴问候配置（早安/午间/晚间）
 *
 * @param currentTheme 当前主题
 * @param onThemeChange 主题切换回调
 * @param onOpenCompanion 打开伙伴面板回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: NionColorTheme,
    onThemeChange: (NionColorTheme) -> Unit,
    onOpenCompanion: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val core = app.core()

    // ── 问候设置状态 ──
    // 三种问候独立开关
    var morningEnabled by remember { mutableStateOf(true) }
    var morningTime by remember { mutableStateOf("08:00") }
    var noonEnabled by remember { mutableStateOf(true) }
    var noonTime by remember { mutableStateOf("12:00") }
    var eveningEnabled by remember { mutableStateOf(true) }
    var eveningTime by remember { mutableStateOf("21:00") }
    // 当前展开的问候卡片，null 表示全部折叠
    var expandedGreeting by remember { mutableStateOf<String?>(null) }

    // ── 天气预警设置状态 ──
    var weatherAlertEnabled by remember { mutableStateOf(true) }
    var hasLocationPermission by remember { mutableStateOf(false) }

    // 运行时位置权限请求 launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // 从 settings 表加载问候配置
    LaunchedEffect(Unit) {
        try {
            morningEnabled = core.getSetting("greeting_morning_enabled") != "false"
            morningTime = core.getSetting("greeting_morning_time") ?: "08:00"
            noonEnabled = core.getSetting("greeting_noon_enabled") != "false"
            noonTime = core.getSetting("greeting_noon_time") ?: "12:00"
            eveningEnabled = core.getSetting("greeting_evening_enabled") != "false"
            eveningTime = core.getSetting("greeting_evening_time") ?: "21:00"
        } catch (_: Exception) {}
    }

    // 加载天气设置和权限状态
    LaunchedEffect(Unit) {
        try {
            weatherAlertEnabled = core.getSetting("weather_alert_enabled") != "false"
        } catch (_: Exception) {}
        hasLocationPermission = LocationHelper.hasLocationPermission(context)
    }

    // 保存设置并重新调度问候闹钟
    fun saveGreetingSettings() {
        try {
            core.setSetting("greeting_morning_enabled", if (morningEnabled) "true" else "false")
            core.setSetting("greeting_morning_time", morningTime)
            core.setSetting("greeting_noon_enabled", if (noonEnabled) "true" else "false")
            core.setSetting("greeting_noon_time", noonTime)
            core.setSetting("greeting_evening_enabled", if (eveningEnabled) "true" else "false")
            core.setSetting("greeting_evening_time", eveningTime)
            // 设置变更后重新调度问候闹钟
            GreetingScheduler.rescheduleAll(context, core)
        } catch (_: Exception) {}
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
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
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 主题颜色区域 ──
            Text(
                "主题颜色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            NionColorTheme.entries.forEach { theme ->
                ThemeOptionCard(
                    theme = theme,
                    isSelected = theme == currentTheme,
                    onClick = { onThemeChange(theme) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 伙伴问候区域 ──
            Text(
                "伙伴问候",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // 早安问候 —— 可展开卡片
            ExpandableGreetingCard(
                title = "早安问候",
                description = if (morningEnabled) "每天 $morningTime 汇总今日待办" else "已关闭",
                time = morningTime,
                enabled = morningEnabled,
                isExpanded = expandedGreeting == "morning",
                onToggleExpand = {
                    // 点击标题行：切换展开/折叠，同一时间只展开一个
                    expandedGreeting = if (expandedGreeting == "morning") null else "morning"
                },
                onEnabledChange = {
                    morningEnabled = it
                    saveGreetingSettings()
                },
                onTimeChange = {
                    morningTime = it
                    saveGreetingSettings()
                },
            )

            // 午间检查 —— 可展开卡片
            ExpandableGreetingCard(
                title = "午间检查",
                description = if (noonEnabled) "每天 $noonTime 检查上午完成情况" else "已关闭",
                time = noonTime,
                enabled = noonEnabled,
                isExpanded = expandedGreeting == "noon",
                onToggleExpand = {
                    expandedGreeting = if (expandedGreeting == "noon") null else "noon"
                },
                onEnabledChange = {
                    noonEnabled = it
                    saveGreetingSettings()
                },
                onTimeChange = {
                    noonTime = it
                    saveGreetingSettings()
                },
            )

            // 晚间总结 —— 可展开卡片
            ExpandableGreetingCard(
                title = "晚间总结",
                description = if (eveningEnabled) "每天 $eveningTime 总结今日成就" else "已关闭",
                time = eveningTime,
                enabled = eveningEnabled,
                isExpanded = expandedGreeting == "evening",
                onToggleExpand = {
                    expandedGreeting = if (expandedGreeting == "evening") null else "evening"
                },
                onEnabledChange = {
                    eveningEnabled = it
                    saveGreetingSettings()
                },
                onTimeChange = {
                    eveningTime = it
                    saveGreetingSettings()
                },
            )

            // ── 悬浮窗权限区域 ──
            Text(
                "提醒设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // 天气预警开关
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.WbSunny,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "天气预警",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "下雨、降温、大风等天气变化时 Nion 会提醒你",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = weatherAlertEnabled,
                        onCheckedChange = {
                            weatherAlertEnabled = it
                            core.setSetting("weather_alert_enabled", if (it) "true" else "false")
                            if (it) {
                                WeatherAlertScheduler.start(context)
                            } else {
                                WeatherAlertScheduler.stop(context)
                            }
                        },
                    )
                }
            }

            // 定位权限（天气功能需要）
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "位置权限",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (hasLocationPermission) "已授权，天气功能可正常使用"
                            else "未授权，天气功能需要位置权限获取所在城市",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasLocationPermission) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!hasLocationPermission) {
                        TextButton(onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        }) {
                            Text("授权")
                        }
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已授权",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 悬浮窗提醒权限开关
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                val hasOverlayPermission = remember {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(context)
                    } else {
                        true
                    }
                }
                // 权限状态可能在用户跳转设置后变化，用 LaunchedEffect 检测
                var overlayGranted by remember { mutableStateOf(hasOverlayPermission) }
                LaunchedEffect(Unit) {
                    // 每次页面重新显示时检查权限状态
                    overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Settings.canDrawOverlays(context)
                    } else {
                        true
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "悬浮窗提醒",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (overlayGranted) "已授权，App 后台时可弹出悬浮卡片"
                            else "未授权，App 后台时仅显示通知栏提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overlayGranted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!overlayGranted) {
                        TextButton(onClick = {
                            // 跳转系统悬浮窗权限设置页
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                context.startActivity(intent)
                            }
                        }) {
                            Text("去设置")
                        }
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已授权",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 早安时间选择器弹窗已移除，改用可展开卡片内的 WheelSpinner
}

/**
 * 可展开的问候设置卡片。
 *
 * 折叠时显示标题行（名称 + 描述 + 当前时间 + 展开箭头），
 * 点击标题行向下展开/收起，展示 Switch 开关和双滚轮时间选择器。
 *
 * @param title 问候名称（如"早安问候"）
 * @param description 折叠时显示的副标题描述
 * @param time 当前时间字符串 "HH:MM"
 * @param enabled 当前开关状态
 * @param isExpanded 是否处于展开状态
 * @param onToggleExpand 点击标题行切换展开/折叠的回调
 * @param onEnabledChange 开关状态变更回调
 * @param onTimeChange 时间变更回调，传入新的 "HH:MM" 字符串
 */
@Composable
private fun ExpandableGreetingCard(
    title: String,
    description: String,
    time: String,
    enabled: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (String) -> Unit,
) {
    // 解析当前时间为小时/分钟索引，用于 WheelSpinner 初始定位
    val parts = time.split(":")
    val currentHour = parts.getOrElse(0) { "0" }.toIntOrNull() ?: 0
    val currentMinute = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0

    // 滚轮选择器的选中值，初始化为当前时间
    var selectedHour by remember(time) { mutableStateOf(currentHour) }
    var selectedMinute by remember(time) { mutableStateOf(currentMinute) }

    // 箭头旋转动画：展开时旋转 180°，折叠时回到 0°
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(),
        label = "arrowRotation",
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            // ── 标题行：点击展开/折叠 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题和描述
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        // 已关闭时用更淡的颜色提示
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                // 展开/折叠箭头图标
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(arrowRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 展开内容：开关 + 时间滚轮选择器 ──
            // 使用 expandVertically 动画从顶部向下展开卡片
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(250)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(250, easing = FastOutLinearInEasing),
                ) + fadeOut(tween(200)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                ) {
                    // 分隔线
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    ) {}

                    Spacer(modifier = Modifier.height(12.dp))

                    // 开关行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "启用",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = enabled,
                            onCheckedChange = onEnabledChange,
                        )
                    }

                    // 仅在启用时显示时间选择器
                    if (enabled) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "提醒时间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 双滚轮时间选择器：小时 (0-23) + 分钟 (0-59)
                        // 与 ReminderTimePicker 使用相同的参数和布局
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 小时滚轮 (0-23)，循环滚动
                            WheelSpinner(
                                items = (0..23).map { "%02d".format(it) },
                                initialIndex = selectedHour,
                                visibleItemCount = 5,
                                itemHeight = 48.dp,
                                onSelected = { index ->
                                    selectedHour = index
                                    // 实时更新时间并保存
                                    val newTime = String.format("%02d:%02d", index, selectedMinute)
                                    onTimeChange(newTime)
                                },
                                modifier = Modifier.weight(1f),
                                circular = true,
                            )

                            // 冒号分隔符
                            Text(
                                ":",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )

                            // 分钟滚轮 (0-59)，循环滚动
                            WheelSpinner(
                                items = (0..59).map { "%02d".format(it) },
                                initialIndex = selectedMinute,
                                visibleItemCount = 5,
                                itemHeight = 48.dp,
                                onSelected = { index ->
                                    selectedMinute = index
                                    // 实时更新时间并保存
                                    val newTime = String.format("%02d:%02d", selectedHour, index)
                                    onTimeChange(newTime)
                                },
                                modifier = Modifier.weight(1f),
                                circular = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    theme: NionColorTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(52.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(theme.primary),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    theme.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "#${String.format("%06X", theme.primary.value.shr(32).toInt() and 0xFFFFFF)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
