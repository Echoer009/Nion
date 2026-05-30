package com.echonion.nion.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.echonion.nion.core
import com.echonion.nion.ui.companion.weather.LocationHelper
import com.echonion.nion.ui.theme.NionAlpha
import com.echonion.nion.ui.theme.NionColorTheme
import com.echonion.nion.ui.theme.ThemePalette
import kotlinx.coroutines.launch

/**
 * 设置页面 —— 应用配置中心。
 *
 * 包含：
 * - 主题颜色选择
 * - 我的位置（GPS 定位 + 城市搜索，天气功能需要）
 * - 位置权限管理
 * - 悬浮窗提醒权限
 * - 后台提醒权限
 *
 * @param currentPresetName 当前激活的预设主题名称（null 表示使用自定义主题）
 * @param themePalette 当前主题色板，用于展示自定义主题颜色
 * @param onThemeChange 预设主题切换回调
 * @param onOpenCompanion 打开伙伴面板回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentPresetName: String?,
    themePalette: ThemePalette,
    onThemeChange: (NionColorTheme) -> Unit,
    onOpenCompanion: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val core = app.core()
    val coroutineScope = rememberCoroutineScope()

    // ── 位置权限状态 ──
    var hasLocationPermission by remember { mutableStateOf(false) }

    // ── 位置信息状态 ──
    // 当前位置显示文本（城市名 + 坐标）
    var locationDisplayText by remember { mutableStateOf("未定位") }
    // GPS 定位中 loading 状态
    var isLocating by remember { mutableStateOf(false) }
    // 城市搜索关键字
    var searchQuery by remember { mutableStateOf("") }
    // 搜索结果列表
    var searchResults by remember { mutableStateOf<List<CitiesProvider.City>>(emptyList()) }
    // 是否为手动模式（用户选过城市）
    var isManualMode by remember { mutableStateOf(false) }

    // 运行时位置权限请求 launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // 加载权限状态 + 当前位置信息
    LaunchedEffect(Unit) {
        hasLocationPermission = LocationHelper.hasLocationPermission(context)
        val info = LocationHelper.getCurrentLocationInfo(core)
        if (info != null && info.location.isNotBlank()) {
            val coords = info.location.split(",")
            val latShort = coords.getOrNull(0)?.take(6) ?: ""
            val lonShort = coords.getOrNull(1)?.take(7) ?: ""
            locationDisplayText = if (info.cityName.isNotBlank()) {
                "${info.cityName} ($latShort, $lonShort)"
            } else {
                "$latShort, $lonShort"
            }
            // 如果有手动位置，标记为手动模式
            val manualLoc = core.getSetting("user_manual_location")
            isManualMode = !manualLoc.isNullOrBlank()
        }
    }

    // 城市列表（只加载一次）
    val allCities = remember { CitiesProvider.loadCities(context) }

    // 搜索过滤（输入即搜索，限制结果数量）
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
                    isSelected = theme.name == currentPresetName,
                    onClick = { onThemeChange(theme) },
                )
            }

            // 自定义主题卡片：当 currentPresetName 为 null 时显示（AI 伴伴工具创建的自定义主题）
            if (currentPresetName == null) {
                CustomThemeCard(
                    palette = themePalette,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 我的位置区域 ──
            Text(
                "我的位置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            LocationCard(
                locationDisplayText = locationDisplayText,
                isLocating = isLocating,
                isManualMode = isManualMode,
                hasPermission = hasLocationPermission,
                searchQuery = searchQuery,
                searchResults = searchResults,
                onSearchQueryChange = { searchQuery = it },
                onLocateClick = {
                    if (!hasLocationPermission) {
                        // 无权限时先请求权限
                        locationPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    } else {
                        // 有权限时执行 GPS 定位
                        coroutineScope.launch {
                            isLocating = true
                            // 清除手动位置，恢复 GPS 模式
                            LocationHelper.clearManualLocation(core)
                            isManualMode = false
                            // 强制 GPS 刷新
                            val result = LocationHelper.forceRefreshLocation(context, core)
                            if (result != null) {
                                val coords = result.location.split(",")
                                val latShort = coords.getOrNull(0)?.take(6) ?: ""
                                val lonShort = coords.getOrNull(1)?.take(7) ?: ""
                                locationDisplayText = if (result.cityName.isNotBlank()) {
                                    "${result.cityName} ($latShort, $lonShort)"
                                } else {
                                    "$latShort, $lonShort"
                                }
                            } else {
                                locationDisplayText = "定位失败，请检查权限"
                            }
                            isLocating = false
                        }
                    }
                },
                onCitySelect = { city ->
                    // 用户选择城市 → 设置手动位置
                    LocationHelper.setManualLocation(core, "${city.lat},${city.lon}", city.name)
                    val latShort = city.lat.toString().take(6)
                    val lonShort = city.lon.toString().take(7)
                    locationDisplayText = "${city.name} ($latShort, $lonShort)"
                    isManualMode = true
                    searchQuery = ""
                    searchResults = emptyList()
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 权限设置区域 ──
            Text(
                "权限设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                // 权限状态：初始值直接查询当前权限，后续由 DisposableEffect 在 ON_RESUME 时刷新
                var overlayGranted by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(context)
                        } else {
                            true
                        }
                    )
                }
                // 监听 Activity 生命周期：每次 ON_RESUME（包括从系统设置页返回）时重新检查权限
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

            // 后台提醒（电池优化白名单）权限开关
            // 部分厂商（vivo/小米/华为等）的省电策略会杀后台 App 导致 AlarmManager 闹钟丢失，
            // 需要加入电池优化白名单 + 厂商专属后台管理白名单才能确保提醒正常触发
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                // 权限状态：查询是否已加入电池优化白名单
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
                // 监听 Activity 生命周期：每次 ON_RESUME（包括从系统设置页返回）时重新检查权限
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
                        tint = MaterialTheme.colorScheme.primary,
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
                            // 通过 VendorBatteryHelper 按厂商优先级跳转电池管理设置页：
                            // 厂商专属页 → Android 标准弹窗 → 应用详情页
                            com.echonion.nion.reminder.VendorBatteryHelper.openBatterySettings(context)
                        }) {
                            Text("去设置")
                        }
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "已设置",
                            tint = MaterialTheme.colorScheme.primary,
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
 * 我的位置卡片 —— 包含 GPS 定位按钮 + 城市搜索框。
 *
 * 交互逻辑：
 * 1. 显示当前定位/手动选择的位置（城市名 + 坐标）
 * 2. 点击"定位"按钮 → GPS 获取当前位置 → Geocoder 反解析城市名
 * 3. 下方搜索框输入即搜索城市列表，点选后设为手动位置
 * 4. 用户选过城市后为手动模式，再点"定位"会清除手动设置重新 GPS 定位
 *
 * @param locationDisplayText 当前位置显示文本
 * @param isLocating GPS 定位中 loading 状态
 * @param isManualMode 是否为手动选城市模式
 * @param hasPermission 是否有位置权限
 * @param searchQuery 城市搜索关键字
 * @param searchResults 搜索结果列表
 * @param onSearchQueryChange 搜索关键字变更回调
 * @param onLocateClick 定位按钮点击回调
 * @param onCitySelect 城市选择回调，传入选中的城市数据
 */
@Composable
private fun LocationCard(
    locationDisplayText: String,
    isLocating: Boolean,
    isManualMode: Boolean,
    hasPermission: Boolean,
    searchQuery: String,
    searchResults: List<CitiesProvider.City>,
    onSearchQueryChange: (String) -> Unit,
    onLocateClick: () -> Unit,
    onCitySelect: (CitiesProvider.City) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // ── 第一行：图标 + 位置信息 + 定位按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                // 定位按钮：loading 时显示转圈，否则显示定位图标
                if (isLocating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
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
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── 第二行：城市搜索框 ──
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

            // ── 搜索结果列表（带展开/收起动画） ──
            AnimatedVisibility(
                visible = searchResults.isNotEmpty(),
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ),
                exit = androidx.compose.animation.shrinkVertically(
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
                        // 每个城市项：点击选中，带分割线
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
                            androidx.compose.material3.HorizontalDivider(
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

@Composable
private fun ThemeOptionCard(
    theme: NionColorTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val palette = theme.palette()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
                            .background(palette.primary),
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
                    "#${String.format("%06X", palette.primary.value.shr(32).toInt() and 0xFFFFFF)}",
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

/**
 * 自定义主题卡片 —— 显示当前自定义主题的主色色板预览。
 *
 * 当 AI 伴伴工具修改了颜色后，currentPresetName 变为 null，
 * 此卡片出现在预设主题卡片列表下方，展示自定义主色。
 *
 * @param palette 当前自定义主题色板
 */
@Composable
private fun CustomThemeCard(
    palette: ThemePalette,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 色板预览：三等分显示 primary / primaryContainer / secondary
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
                            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            .background(palette.primary),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(palette.primaryContainer),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(palette.secondary),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "自定义主题",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "由伙伴调整的颜色方案",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.Default.Check,
                contentDescription = "使用中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
