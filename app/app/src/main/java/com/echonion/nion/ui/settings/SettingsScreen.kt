package com.echonion.nion.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.echonion.nion.core
import com.echonion.nion.ui.companion.weather.LocationHelper
import com.echonion.nion.ui.theme.CustomThemeEntry
import com.echonion.nion.ui.theme.NionAlpha
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.ThemePalette
import kotlinx.coroutines.launch

/**
 * 设置页面 —— 应用配置中心。
 *
 * 包含：
 * - 主题颜色选择（可展开卡片：预设 + 自定义主题列表）
 * - 我的位置（GPS 定位 + 城市搜索，天气功能需要）
 * - 位置权限管理
 * - 悬浮窗提醒权限
 * - 后台提醒权限
 *
 * @param currentPresetName 当前激活的预设主题名称（null 表示使用自定义主题）
 * @param themePalette 当前主题色板，用于展示颜色
 * @param customThemes 所有自定义主题列表
 * @param onThemeChange 预设主题切换回调，用户点击预设主题卡片时触发
 * @param onCustomThemeSelect 自定义主题选中回调，用户点击自定义主题卡片时触发
 * @param onCustomThemeDelete 自定义主题删除回调，用户点击删除图标时触发
 * @param onCustomThemeRename 自定义主题重命名回调，用户编辑名称后触发
 * @param onOpenCompanion 打开伙伴面板回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentPresetName: String?,
    themePalette: ThemePalette,
    customThemes: List<CustomThemeEntry>,
    onThemeChange: (NionColorTheme) -> Unit,
    onCustomThemeSelect: (CustomThemeEntry) -> Unit,
    onCustomThemeDelete: (String) -> Unit,
    onCustomThemeRename: (String, String) -> Unit,
    onOpenCompanion: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val core = app.core()
    val coroutineScope = rememberCoroutineScope()

    // ── 位置权限状态 ──
    var hasLocationPermission by remember { mutableStateOf(false) }

    // ── 位置信息状态 ──
    var locationDisplayText by remember { mutableStateOf("未定位") }
    var isLocating by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CitiesProvider.City>>(emptyList()) }
    var isManualMode by remember { mutableStateOf(false) }
    // 搜索框是否展开（点击放大镜图标切换）
    var isSearchExpanded by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        hasLocationPermission = LocationHelper.hasLocationPermission(context)
        val info = LocationHelper.getCurrentLocationInfo(core)
        if (info != null && info.location.isNotBlank()) {
            // 只显示城市名，不暴露经纬度坐标
            locationDisplayText = if (info.cityName.isNotBlank()) {
                info.cityName
            } else {
                "已定位"
            }
            val manualLoc = core.getSetting("user_manual_location")
            isManualMode = !manualLoc.isNullOrBlank()
        }
    }

    val allCities = remember { CitiesProvider.loadCities(context) }

    LaunchedEffect(searchQuery) {
        searchResults = if (searchQuery.isBlank()) {
            emptyList()
        } else {
            CitiesProvider.search(allCities, searchQuery).take(8)
        }
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
            // ── 主题颜色区域（区域标题使用 secondary） ──
            Text(
                "主题颜色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )

            // 可展开的主题选择器卡片
            ThemeSelectorCard(
                currentPresetName = currentPresetName,
                themePalette = themePalette,
                customThemes = customThemes,
                onThemeChange = onThemeChange,
                onCustomThemeSelect = onCustomThemeSelect,
                onCustomThemeDelete = onCustomThemeDelete,
                onCustomThemeRename = onCustomThemeRename,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 我的位置区域（区域标题使用 secondary） ──
            Text(
                "我的位置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )

            // 使用 SharedTransitionLayout 包裹 LocationCard，实现搜索展开/收起的共享元素变形动画
            SharedTransitionLayout {
                LocationCard(
                    locationDisplayText = locationDisplayText,
                    isLocating = isLocating,
                    isManualMode = isManualMode,
                    hasPermission = hasLocationPermission,
                    isSearchExpanded = isSearchExpanded,
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    onSearchQueryChange = { searchQuery = it },
                    onToggleSearch = {
                        // 切换搜索展开状态；收起时清空搜索关键字和结果
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded) {
                            searchQuery = ""
                            searchResults = emptyList()
                        }
                    },
                    onLocateClick = {
                        if (!hasLocationPermission) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        } else {
                            coroutineScope.launch {
                                isLocating = true
                                LocationHelper.clearManualLocation(core)
                                isManualMode = false
                                val result = LocationHelper.forceRefreshLocation(context, core)
                                if (result != null) {
                                    // 只显示城市名，不暴露经纬度坐标
                                    locationDisplayText = if (result.cityName.isNotBlank()) {
                                        result.cityName
                                    } else {
                                        "已定位"
                                    }
                                } else {
                                    locationDisplayText = "定位失败，请检查权限"
                                }
                                isLocating = false
                            }
                        }
                    },
                    onCitySelect = { city ->
                        LocationHelper.setManualLocation(core, "${city.lat},${city.lon}", city.name)
                        // 只显示城市名，不暴露经纬度坐标
                        locationDisplayText = city.name
                        isManualMode = true
                        searchQuery = ""
                        searchResults = emptyList()
                        isSearchExpanded = false
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 权限设置区域（区域标题使用 secondary） ──
            Text(
                "权限设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )

            // 定位权限
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
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
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 悬浮窗提醒权限开关
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                var overlayGranted by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(context)
                        } else {
                            true
                        }
                    )
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Settings.canDrawOverlays(context)
                            } else {
                                true
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
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
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 后台提醒（电池优化白名单）权限开关
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                var batteryOptimized by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            !(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                                .isIgnoringBatteryOptimizations(context.packageName)
                        } else {
                            false
                        }
                    )
                }
                val lifecycleOwner2 = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner2) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            batteryOptimized = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                !(context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                                    .isIgnoringBatteryOptimizations(context.packageName)
                            } else {
                                false
                            }
                        }
                    }
                    lifecycleOwner2.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner2.lifecycle.removeObserver(observer)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "后台提醒",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (!batteryOptimized) "已设置，定时提醒闹钟可正常触发"
                            else "未设置，定时提醒可能被系统省电策略拦截",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!batteryOptimized) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (batteryOptimized) {
                        TextButton(onClick = {
                            com.echonion.nion.reminder.VendorBatteryHelper.openBatterySettings(context)
                        }) {
                            Text("去设置")
                        }
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已设置",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 可展开的主题选择器卡片。
 *
 * 折叠态：显示当前活跃主题的 3 色预览 + 名称 + 展开箭头
 * 展开态：预设主题列表 + 自定义主题列表（可编辑名称/删除）
 *
 * @param currentPresetName 当前预设名称，null 表示使用自定义主题
 * @param themePalette 当前色板
 * @param customThemes 自定义主题列表
 * @param onThemeChange 预设主题切换回调
 * @param onCustomThemeSelect 自定义主题选中回调
 * @param onCustomThemeDelete 自定义主题删除回调
 * @param onCustomThemeRename 自定义主题重命名回调
 */
@Composable
private fun ThemeSelectorCard(
    currentPresetName: String?,
    themePalette: ThemePalette,
    customThemes: List<CustomThemeEntry>,
    onThemeChange: (NionColorTheme) -> Unit,
    onCustomThemeSelect: (CustomThemeEntry) -> Unit,
    onCustomThemeDelete: (String) -> Unit,
    onCustomThemeRename: (String, String) -> Unit,
) {
    // 展开/折叠状态
    var isExpanded by remember { mutableStateOf(false) }
    // 箭头旋转动画（0° 折叠 → 180° 展开）
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "themeArrowRotation",
    )

    // 当前活跃主题的显示信息
    val activeName = currentPresetName?.let {
        NionColorTheme.entries.find { t -> t.name == it }?.label ?: it
    } ?: run {
        "自定义主题"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 折叠态标题行（点击展开/折叠） ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 3 色块预览
                ColorSwatchPreview(
                    primary = themePalette.primary,
                    secondary = themePalette.secondary,
                    tertiary = themePalette.tertiary,
                    size = 40.dp,
                )

                Spacer(modifier = Modifier.width(14.dp))

                // 当前主题名称
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        activeName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (isExpanded) "点击收起" else "点击展开所有主题",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 展开箭头（带旋转动画）
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(arrowRotation),
                )
            }

            // ── 展开态内容 ──
            AnimatedVisibility(
                visible = isExpanded,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ── 预设主题区 ──
                    Text(
                        "预设主题",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    NionColorTheme.entries.forEach { theme ->
                        val palette = theme.palette()
                        val isSelected = theme.name == currentPresetName
                        PresetThemeItem(
                            name = theme.label,
                            palette = palette,
                            isSelected = isSelected,
                            onClick = { onThemeChange(theme) },
                        )
                    }

                    // ── 自定义主题区（有主题时才显示） ──
                    if (customThemes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "自定义主题",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        customThemes.forEach { entry ->
                            val isActive = currentPresetName == null &&
                                themePalette.primary == entry.palette.primary
                            CustomThemeItem(
                                entry = entry,
                                isActive = isActive,
                                onSelect = { onCustomThemeSelect(entry) },
                                onDelete = { onCustomThemeDelete(entry.id) },
                                onRename = { newName -> onCustomThemeRename(entry.id, newName) },
                            )
                        }
                    }

                    // ── 底部提示 ──
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "让伙伴帮你创建更多主题 ✨",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SUBTITLE),
                    )
                }
            }
        }
    }
}

/**
 * 3 色块预览组件 —— 显示 primary / secondary / tertiary 三个颜色。
 *
 * @param primary 主色
 * @param secondary 辅色
 * @param tertiary 第三色
 * @param size 整体尺寸（正方形）
 */
@Composable
private fun ColorSwatchPreview(
    primary: Color,
    secondary: Color,
    tertiary: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(size),
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
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(primary),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(secondary),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                    .background(tertiary),
            )
        }
    }
}

/**
 * 预设主题列表项 —— 显示主题名 + 3 色预览 + 选中勾。
 *
 * @param name 主题名称（如"珊瑚"）
 * @param palette 主题色板
 * @param isSelected 是否为当前活跃主题
 * @param onClick 点击选中回调
 */
@Composable
private fun PresetThemeItem(
    name: String,
    palette: ThemePalette,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        // 选中主题使用 secondaryContainer 背景
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = NionAlpha.BG_TAB_ACTIVE)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 3 色预览
            ColorSwatchPreview(
                primary = palette.primary,
                secondary = palette.secondary,
                tertiary = palette.tertiary,
                size = 32.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 主题名称
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            // 选中勾
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 自定义主题列表项 —— 显示主题名（可编辑）+ 3 色预览 + 选中勾 + 删除图标。
 *
 * 编辑模式行为：
 * - 点击名称进入编辑模式，自动弹出键盘
 * - 键盘 Done 键或点击其他区域（失焦）自动保存新名称
 * - 删除前弹出确认弹窗，防止误删
 *
 * @param entry 自定义主题条目
 * @param isActive 是否为当前活跃主题
 * @param onSelect 点击选中回调
 * @param onDelete 删除回调
 * @param onRename 重命名回调，传入新名称
 */
@Composable
private fun CustomThemeItem(
    entry: CustomThemeEntry,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    // 编辑状态：点击名称进入编辑模式
    var isEditing by remember { mutableStateOf(false) }
    // 编辑中的名称文本
    var editName by remember { mutableStateOf(entry.name) }
    // 删除确认弹窗显示状态
    var showDeleteDialog by remember { mutableStateOf(false) }
    // 焦点请求器，用于进入编辑模式时自动聚焦并弹出键盘
    val focusRequester = remember { FocusRequester() }
    // 标记 TextField 是否曾获得过焦点，防止首次挂载时的失焦事件误触发保存
    var hasBeenFocused by remember { mutableStateOf(false) }

    // 进入编辑模式时自动请求焦点，弹出软键盘
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }

    /**
     * 提交重命名：如果名称有效且发生了变化则回调 onRename，然后退出编辑模式。
     * 提取为独立函数避免 onDone 和 onFocusChanged 重复逻辑。
     */
    fun submitRename() {
        if (!isEditing) return
        if (editName.isNotBlank() && editName != entry.name) {
            onRename(editName.trim())
        }
        isEditing = false
        hasBeenFocused = false
    }

    Surface(
        // Surface 整体可点击：非编辑态时选中主题，编辑态时不做处理（让失焦保存逻辑接管）
        onClick = {
            if (!isEditing) onSelect()
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        // 活跃自定义主题使用 secondaryContainer 背景
        color = if (isActive) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = NionAlpha.BG_TAB_ACTIVE)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 3 色预览
            ColorSwatchPreview(
                primary = entry.palette.primary,
                secondary = entry.palette.secondary,
                tertiary = entry.palette.tertiary,
                size = 32.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 主题名称（点击可编辑）
            if (isEditing) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                // 首次获得焦点，标记为已聚焦
                                hasBeenFocused = true
                            } else if (hasBeenFocused) {
                                // 从聚焦变为失焦（用户点击了其他区域），自动保存并退出编辑
                                submitRename()
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    singleLine = true,
                    // 确保 IME 显示"完成"按钮
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        // 键盘 Done 按钮提交重命名
                        onDone = {
                            submitRename()
                        },
                    ),
                )
            } else {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            editName = entry.name
                            // 重置焦点标记，防止上一次编辑会话的残留值误触发保存
                            hasBeenFocused = false
                            isEditing = true
                        },
                )
            }

            // 选中勾
            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "使用中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            // 删除图标 —— 点击弹出确认弹窗而非直接删除
            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除主题",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NionAlpha.TEXT_SECONDARY),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    // 删除确认弹窗：防止误触删除
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    "删除主题",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text("确定要删除主题「${entry.name}」吗？此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text(
                        "删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 位置信息卡片 —— 显示当前城市名 + 定位按钮 + 可展开的城市搜索框。
 *
 * 默认折叠状态只显示城市名、定位/GPS 模式标签、定位按钮、放大镜搜索按钮；
 * 点击放大镜后向下展开搜索框和搜索结果列表，动画参考伙伴界面的 ExpandablePromptCard：
 * - 容器使用 sharedElement + spring 弹簧变形
 * - 内容使用 AnimatedContent + fadeIn/fadeOut + SizeTransform(clip=false)
 *
 * @param locationDisplayText 当前位置显示文本（仅城市名，不含经纬度）
 * @param isLocating GPS 定位中 loading 状态
 * @param isManualMode 是否为手动选择城市模式
 * @param hasPermission 是否有位置权限
 * @param isSearchExpanded 搜索框是否展开
 * @param searchQuery 城市搜索关键字
 * @param searchResults 搜索结果列表
 * @param onSearchQueryChange 搜索关键字变更回调
 * @param onToggleSearch 切换搜索展开/收起状态的回调
 * @param onLocateClick 定位按钮点击回调
 * @param onCitySelect 城市选择回调，传入选中的城市数据
 * @param sharedTransitionScope SharedTransitionLayout 提供的作用域，用于 sharedElement 动画
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LocationCard(
    locationDisplayText: String,
    isLocating: Boolean,
    isManualMode: Boolean,
    hasPermission: Boolean,
    isSearchExpanded: Boolean,
    searchQuery: String,
    searchResults: List<CitiesProvider.City>,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onLocateClick: () -> Unit,
    onCitySelect: (CitiesProvider.City) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
) {
    with(sharedTransitionScope) {
        // 使用 AnimatedContent 实现折叠/展开之间的内容切换动画
        AnimatedContent(
            targetState = isSearchExpanded,
            transitionSpec = {
                if (targetState) {
                    // 展开动画：新内容淡入 + 旧内容淡出 + 容器尺寸平滑过渡（不裁剪）
                    (fadeIn(tween(300, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(180, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                } else {
                    // 收起动画：新内容淡入 + 旧内容淡出 + 容器尺寸平滑过渡（不裁剪）
                    (fadeIn(tween(250, easing = FastOutSlowInEasing))
                        togetherWith fadeOut(tween(400, easing = FastOutSlowInEasing)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "location_card",
        ) { expanded ->
            // 卡片容器：使用 Box + background 代替 Surface，避免 Surface 内部 clip(shape)
            // 在弹簧回弹过程中裁剪内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .sharedElement(
                        sharedContentState = rememberSharedContentState("location_container"),
                        animatedVisibilityScope = this@AnimatedContent,
                        // 弹簧变形：阻尼比 1.0（无过冲），中等低刚度 → 柔和的尺寸过渡
                        boundsTransform = { _, _ ->
                            spring(
                                dampingRatio = 1.0f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        },
                    )
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLowest,
                        RoundedCornerShape(16.dp),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    // ── 第一行：位置图标 + 城市名 + 定位按钮 + 搜索按钮 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        // 城市名 + 模式标签
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (isManualMode) "手动选择" else "GPS 定位",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                locationDisplayText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // 定位按钮：loading 时显示转圈进度，否则显示定位图标
                        if (isLocating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        } else {
                            IconButton(
                                onClick = onLocateClick,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = "定位",
                                    tint = if (hasPermission) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // 搜索按钮：放大镜图标，点击展开/收起搜索框
                        IconButton(
                            onClick = onToggleSearch,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = if (expanded) "收起搜索" else "展开搜索",
                                // 使用 sharedElement 让放大镜图标在展开/收起过程中平滑过渡
                                modifier = Modifier
                                    .size(22.dp)
                                    .sharedElement(
                                        sharedContentState = rememberSharedContentState("location_search_icon"),
                                        animatedVisibilityScope = this@AnimatedContent,
                                    ),
                                tint = if (expanded) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // ── 展开的搜索区域（仅 expanded 状态下组合） ──
                    if (expanded) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(10.dp))

                            // 城市搜索输入框
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = onSearchQueryChange,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        "搜索城市...",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                shape = RoundedCornerShape(12.dp),
                            )

                            // 搜索结果列表（带垂直展开/收起动画）
                            AnimatedVisibility(
                                visible = searchResults.isNotEmpty(),
                                enter = expandVertically(
                                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                                    expandFrom = Alignment.Top,
                                ),
                                exit = shrinkVertically(
                                    animationSpec = tween(150, easing = FastOutSlowInEasing),
                                    shrinkTowards = Alignment.Top,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                ) {
                                    searchResults.forEachIndexed { index, city ->
                                        // 每个城市项：点击选中，显示城市名 + 省份
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onCitySelect(city) }
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    city.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                                Text(
                                                    city.province,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        // 分割线（最后一项不画）
                                        if (index < searchResults.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 14.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = NionAlpha.BG_SUBTLE),
                                                thickness = 0.5.dp,
                                            )
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
