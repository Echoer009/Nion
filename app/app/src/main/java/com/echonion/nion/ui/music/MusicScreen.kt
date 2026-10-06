package com.echonion.nion.ui.music

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.echonion.nion.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 获取 Activity 作用域的 MusicViewModel。
 *
 * 与 FocusScreen 的 focusTimerViewModel 相同的手法：把 ViewModel 绑定到
 * Activity 而非 NavBackStackEntry，这样用户在底部导航各页之间切换时
 * ViewModel（连同 MediaController、播放队列、浏览状态）不会销毁，音乐不中断。
 */
@Composable
fun musicViewModel(): MusicViewModel {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    return viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = MusicViewModel.Factory(app),
    )
}

/**
 * 音乐主界面。
 *
 * 结构：
 * - Scaffold + TopAppBar（标题"音乐"、右上角打开伙伴面板）
 * - 主体：AnimatedContent 按 [MusicViewModel.source] 切换三态
 *   （本地专辑库 / 类脑娘服务器 / 自己的服务器），切换动画照抄
 *   FocusStatsPanel 日/周/月切换（水平滑动 + 淡入淡出，方向由音源顺序决定）
 * - 底部：迷你播放条（有曲目在播时显示），点击展开完整播放页
 * - 完整播放页：全屏覆盖层（AnimatedVisibility 上下滑出入），
 *   含封面、可拖动进度条、上一首/下一首、播放队列列表
 *
 * @param onOpenCompanion 点击右上角伙伴图标时触发，由上层打开右侧伙伴面板
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MusicScreen(
    onOpenCompanion: () -> Unit = {},
) {
    val vm = musicViewModel()

    // ── 消费注册深链（nion://musicauth?u=..&p=..）──
    // MainActivity 已把账密落库并暂存在 pendingMusicAuthXxx（照通知 Intent 的模式），
    // 音乐界面组合时同步 ViewModel 内存状态并自动触发连接；消费完立即清除防重复执行
    val mainActivity = LocalContext.current as? MainActivity
    LaunchedEffect(mainActivity?.pendingMusicAuthUsername) {
        val u = mainActivity?.pendingMusicAuthUsername ?: return@LaunchedEffect
        val p = mainActivity.pendingMusicAuthPassword ?: return@LaunchedEffect
        vm.applyBraingirlAuth(u, p)
        mainActivity.clearPendingMusicAuth()
    }

    // 完整播放页是否展开（纯 UI 状态，留在 Composable 内）
    var showFullPlayer by remember { mutableStateOf(false) }

    // 记录"从列表点歌"打开播放页时的曲目 id：
    // 非空时播放页大封面使用 "music_track_cover_<trackId>" 作为 sharedElement key，
    // 与被点击的列表行小封面配对，实现"行内小封面飞成大封面"的 morph；
    // 从迷你条展开时为 null，大封面改与迷你条封面配对（key "music_player_cover"）。
    // 播放页关闭时清空，回到默认的迷你条配对
    var openedFromTrackId by remember { mutableStateOf<String?>(null) }

    // 完整播放页打开时拦截系统返回：先收起播放页而不是退出音乐页
    BackHandler(enabled = showFullPlayer) { showFullPlayer = false }

    // SharedTransitionLayout：为两套 morph 提供共享容器（照 TaskScreen 任务卡↔详情浮层范式）
    // 1. 迷你播放条 ↔ 完整播放页：容器 sharedBounds("music_player_bar") +
    //    封面 sharedElement("music_player_cover")
    // 2. 曲目行小封面 ↔ 播放页大封面：sharedElement("music_track_cover_<trackId>")
    // AnimatedContent 的两个分支互斥组合，同一 sharedElement key 的源/目标
    // 只会在过渡期间短暂共存，不存在稳态冲突
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showFullPlayer,
            transitionSpec = {
                // 展开/收起均为交叉淡入淡出：位移交由 sharedElement/sharedBounds 的
                // 弹簧 boundsTransform 完成，避免容器滑动干扰封面飞行轨迹
                (
                    fadeIn(tween(200, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(250, easing = FastOutSlowInEasing))
                    ) using SizeTransform(clip = false)
            },
            label = "fullPlayer",
        ) { fullPlayerVisible ->
            if (fullPlayerVisible) {
                // ===== 完整播放页分支 =====
                FullPlayerOverlay(
                    vm = vm,
                    onClose = {
                        showFullPlayer = false
                        // 复位配对来源：下次默认与迷你条封面配对
                        openedFromTrackId = null
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    // 封面 sharedElement key：点歌打开 → 与行内小封面配对；
                    // 迷你条展开 → 与迷你条封面配对
                    coverSharedKey = openedFromTrackId?.let { "music_track_cover_$it" }
                        ?: "music_player_cover",
                )
            } else {
                // ===== 音乐主界面分支（音源内容 + 迷你播放条）=====
                val sharedScope = this@SharedTransitionLayout
                val playerAnimatedScope = this@AnimatedContent

                // 曲目行小封面的 sharedElement 工厂：key 带曲目 id（与播放页大封面配对）。
                // 只有播放页以"点歌方式"打开（大封面 key 恰好等于某行 key）时才触发
                // 配对 morph；其余行没有配对目标，修饰符不产生任何动画
                val trackCoverModifier: @Composable (String) -> Modifier = { trackId ->
                    with(sharedScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("music_track_cover_$trackId"),
                            animatedVisibilityScope = playerAnimatedScope,
                            // 阻尼比 1.0 的临界弹簧：柔和无过冲，与项目统一曲线一致
                            boundsTransform = { _, _ ->
                                spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                            },
                        )
                    }
                }

                // 从列表点歌：记录曲目 id（供封面配对）并展开播放页
                val openPlayerFromTrack: (String) -> Unit = { trackId ->
                    openedFromTrackId = trackId
                    showFullPlayer = true
                }

                Scaffold(
                    contentWindowInsets = WindowInsets.statusBars,
                    topBar = {
                        TopAppBar(
                            title = {
                                // 标题直接显示当前音源名（页面不再叠"音乐"两行标题）
                                Text(
                                    when (vm.source) {
                                        MusicSource.LOCAL -> "本地曲目"
                                        MusicSource.BRAINGIRL -> "类脑娘服务器"
                                        MusicSource.CUSTOM -> "自己的服务器"
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            actions = {
                                // 右上角伙伴入口，与专注页/日程页保持一致
                                IconButton(onClick = onOpenCompanion) {
                                    Icon(
                                        Icons.Rounded.QuestionAnswer,
                                        contentDescription = "打开伙伴",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        // ===== 音源三态切换主区域 =====
                        AnimatedContent(
                            targetState = vm.source,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            transitionSpec = {
                                // 切换方向：向顺序更后的音源切换（本地→类脑娘→自定义）
                                // 新内容从右滑入；反向切换从左滑入 —— 与专注统计日/周/月切换同一套动效
                                val direction = if (targetState.order > initialState.order) 1 else -1
                                (
                                    slideInHorizontally(
                                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                                        initialOffsetX = { fullWidth -> direction * fullWidth / 3 },
                                    ) + fadeIn(tween(200, delayMillis = 50))
                                    ) togetherWith (
                                    slideOutHorizontally(
                                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> -direction * fullWidth / 3 },
                                    ) + fadeOut(tween(150))
                                    ) using SizeTransform(clip = false)
                            },
                            label = "musicSource",
                        ) { targetSource ->
                            when (targetSource) {
                                // 本地曲目：MediaStore 全机音频（含权限申请流程）
                                MusicSource.LOCAL -> LocalMusicContent(
                                    vm = vm,
                                    trackCoverModifier = trackCoverModifier,
                                    onPlayFromList = openPlayerFromTrack,
                                )
                                // 服务器：未配置时显示引导配置表单，配置后进入曲库浏览
                                MusicSource.BRAINGIRL -> ServerMusicContent(
                                    vm = vm,
                                    isCustom = false,
                                    trackCoverModifier = trackCoverModifier,
                                    onPlayFromList = openPlayerFromTrack,
                                )
                                MusicSource.CUSTOM -> ServerMusicContent(
                                    vm = vm,
                                    isCustom = true,
                                    trackCoverModifier = trackCoverModifier,
                                    onPlayFromList = openPlayerFromTrack,
                                )
                            }
                        }

                        // ===== 迷你播放条（有正在播放的曲目时显示）=====
                        vm.currentTrack?.let { track ->
                            MiniPlayerBar(
                                vm = vm,
                                track = track,
                                // 点击播放条主体 → 展开完整播放页（封面与迷你条封面配对飞行）
                                onExpand = {
                                    openedFromTrackId = null
                                    showFullPlayer = true
                                },
                                sharedTransitionScope = sharedScope,
                                animatedVisibilityScope = playerAnimatedScope,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ============================================================
 * 本地曲目
 * ============================================================ */

/**
 * 本地音源内容区 —— 权限申请 + 与服务器音源 [ServerMusicContent] 完全同构的浏览界面。
 *
 * 流程：
 * 1. 进入时检查音频读取权限（Android 13+ 用 READ_MEDIA_AUDIO，
 *    更早版本用 READ_EXTERNAL_STORAGE），无权限则弹系统授权框
 * 2. 授权成功后触发 ViewModel 扫描 MediaStore 并聚合出专辑列表
 * 3. 浏览态与云端一一对应：搜索框（内存过滤）→ 错误提示条（点击重试）
 *    → 专辑列表 ↔ 专辑详情（含 morph 动画），点专辑内歌曲以该专辑曲目为队列起播
 *
 * @param vm 共享的音乐 ViewModel
 */
@Composable
private fun LocalMusicContent(
    vm: MusicViewModel,
    // 曲目行小封面 sharedElement 工厂（由 MusicScreen 创建，key 带曲目 id）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：传入被点曲目 id，由上层展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    val context = LocalContext.current
    // 按系统版本选择权限串：13+ 细化的媒体权限，旧版通用存储权限
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // 用户是否明确拒绝过授权（拒绝后显示引导卡片而不是空白列表）
    var permissionDenied by remember { mutableStateOf(false) }

    // 系统授权框回调：授权成功立即扫描；拒绝则进入引导态
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
        if (granted) vm.loadLocalTracks(force = true)
    }

    // 首次进入：有权限直接扫描（并补一次本地搜索刷新，覆盖"从服务器带着关键词切回"场景），
    // 无权限弹授权框
    LaunchedEffect(Unit) {
        if (hasPermission) {
            vm.loadLocalTracks()
            vm.refreshLocalSearch()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    when {
        // 引导态：权限被拒，提示用户去系统设置开启
        permissionDenied -> {
            EmptyHint(
                icon = Icons.Default.Headphones,
                title = "需要音频访问权限",
                subtitle = "点击下方按钮重新授权后即可扫描本机音乐",
                actionLabel = "重新授权",
                onAction = {
                    // 再次拉起系统授权框（系统会记住拒绝，可能直接落到设置引导）
                    permissionDenied = false
                    permissionLauncher.launch(permission)
                },
            )
        }
        // 等待授权结果：系统授权框展示期间显示加载态
        !hasPermission -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        // 浏览态：与云端同构（搜索 + 专辑列表/详情 + morph）
        else -> LocalLibraryContent(
            vm = vm,
            trackCoverModifier = trackCoverModifier,
            onPlayFromList = onPlayFromList,
        )
    }
}

/**
 * 本地曲库浏览区 —— 结构与 [ServerMusicContent] 的已配置浏览态完全一致：
 * 搜索框 → 错误提示条 → 专辑列表 ↔ 专辑详情 morph；数据来自本地 MediaStore 聚合。
 * 差异仅两处：错误条重试走 [MusicViewModel.loadLocalTracks]、多一排本地专辑排序切换。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LocalLibraryContent(
    vm: MusicViewModel,
    // 曲目行小封面 sharedElement 工厂（由 MusicScreen 创建，key 带曲目 id）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：传入被点曲目 id，由上层展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    // 专辑详情打开时拦截系统返回：先回到专辑列表（与云端一致）
    BackHandler(enabled = vm.currentAlbum != null) { vm.closeAlbum() }

    // 歌单详情打开时拦截系统返回：先回到歌单列表（与专辑详情一致）
    BackHandler(enabled = vm.currentPlaylist != null) { vm.closePlaylist() }

    // 专辑|歌单视图切换状态（纯 UI 状态；搜索激活时隐藏切换入口并回落专辑视图）
    var showPlaylists by remember { mutableStateOf(false) }

    // 首次进入加载当前音源的歌单列表（loadPlaylists 内部有"已有数据不重查"保护）
    LaunchedEffect(Unit) { vm.loadPlaylists() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ===== 控件栏：排序/视图/搜索 三入口（整行四态 morph；标题在页面 TopAppBar）=====
        MusicToolbar(
            vm = vm,
            showPlaylists = showPlaylists,
            onSelectView = { showPlaylists = it },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ===== 错误提示条（扫描失败，点击强制重查；样式与云端一致）=====
        vm.localError?.let { error ->
            ErrorRetryBar(error = error) { vm.loadLocalTracks(force = true) }
        }


        // ===== 专辑视图 ↔ 歌单视图：横向滑动 + 淡入淡出切换动画 =====
        // 搜索激活时 targetState 强制回落 false（搜索结果在专辑分支内展示），
        // 由 AnimatedContent 驱动两个方向的滑动过渡，与专辑↔详情 morph 风格一致
        AnimatedContent(
            targetState = showPlaylists && vm.searchQuery.isBlank(),
            transitionSpec = {
                if (targetState) {
                    // 进入歌单视图：歌单从右滑入，专辑向左滑出
                    (
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeIn(tween(300))
                    ) togetherWith (
                        slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } +
                            fadeOut(tween(200))
                    )
                } else {
                    // 回到专辑视图：专辑从左滑入，歌单向右滑出
                    (
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } +
                            fadeIn(tween(300))
                    ) togetherWith (
                        slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeOut(tween(200))
                    )
                }
            },
            label = "libraryView",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { showPlaylistView ->
            if (showPlaylistView) {
                // ===== 歌单视图：列表/详情 morph + 右下角新建 FAB（与云端共用）=====
                PlaylistViewWithFab(
                    vm = vm,
                    trackCoverModifier = trackCoverModifier,
                    onPlayFromList = onPlayFromList,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
        // ===== 主体：专辑列表 ↔ 专辑详情 morph（与云端同一套结构与动画）=====
        // 本地专辑 id 自带 "local-" 前缀，拼出的 sharedElement key
        // （"music_album_local-<albumId>"）天然与服务器专辑 key 错开不冲突
        SharedTransitionLayout(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = vm.currentAlbum,
                transitionSpec = {
                    if (targetState != null) {
                        // 展开：详情立即出现（不淡入），仅列表淡出 —— 与 TaskScreen 一致，
                        // 避免 scrim 淡入期间透明导致底层列表透出
                        (
                            EnterTransition.None togetherWith
                                fadeOut(tween(250, easing = FastOutSlowInEasing))
                            ) using SizeTransform(clip = false)
                    } else {
                        // 收回：列表快速淡入，详情缓慢淡出
                        (
                            fadeIn(tween(250, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(400, easing = FastOutSlowInEasing))
                            ) using SizeTransform(clip = false)
                    }
                },
                label = "albumDetail",
            ) { album ->
                if (album != null) {
                    // 专辑详情分支（本地/服务器共用组件；本地曲目已在打开时同步就位）
                    AlbumDetailContent(
                        vm = vm,
                        album = album,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        trackCoverModifier = trackCoverModifier,
                        onPlayFromList = onPlayFromList,
                    )
                } else {
                    when {
                        // 搜索中：优先展示搜索结果（歌曲在前、专辑在后；与云端同组件）
                        vm.searchQuery.isNotBlank() && vm.searchResult != null -> SearchResultContent(
                            vm = vm,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                            trackCoverModifier = trackCoverModifier,
                            onPlayFromList = onPlayFromList,
                        )
                        // 空态：保留刷新按钮（强制重查 MediaStore）
                        vm.localAlbums.isEmpty() && !vm.isLoadingLocal -> EmptyHint(
                            icon = Icons.Default.MusicNote,
                            title = "本机没有找到音频",
                            subtitle = "把音乐文件复制到手机后点击刷新",
                            actionLabel = "刷新",
                            onAction = { vm.loadLocalTracks(force = true) },
                        )
                        // 专辑列表（加载中由 AlbumListContent 显示居中转圈）
                        else -> AlbumListContent(
                            albums = vm.sortedLocalAlbums,
                            isLoading = vm.isLoadingLocal,
                            emptyText = "本机没有专辑",
                            onOpenAlbum = { vm.openAlbum(it) },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                        )
                    }
                }
            }
        }
                } // 专辑视图内层 Column 结束
            } // if(showPlaylistView)/else 结束
        } // AnimatedContent(专辑↔歌单视图切换) 结束
    }
}

/* ============================================================
 * 服务器（类脑娘 / 自己的）
 * ============================================================ */

/**
 * 服务器音源内容区 —— 统一处理"未配置引导态"与"已配置浏览态"。
 *
 * 未配置（引导态）：显示配置表单（URL + 账密），保存后自动进入浏览态；
 * 已配置（浏览态）：搜索框 + 专辑列表 + 专辑详情（BackHandler 返回列表）。
 *
 * @param vm 共享的音乐 ViewModel
 * @param isCustom true=自己的服务器（表单多一个 URL 输入框）；false=类脑娘预置服务器
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ServerMusicContent(
    vm: MusicViewModel,
    isCustom: Boolean,
    // 曲目行小封面 sharedElement 工厂（由 MusicScreen 创建，key 带曲目 id）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：传入被点曲目 id，由上层展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    val configured = if (isCustom) vm.isCustomConfigured else vm.isBraingirlConfigured

    if (!configured) {
        // 引导态：配置表单
        ServerConfigForm(vm, isCustom = isCustom)
        return
    }

    // 已配置：首次进入自动加载专辑列表（loadAlbums 内部有"已有数据不重复请求"保护）
    LaunchedEffect(Unit) { vm.loadAlbums() }

    // 专辑详情打开时拦截系统返回：先回到专辑列表
    BackHandler(enabled = vm.currentAlbum != null) { vm.closeAlbum() }

    // 歌单详情打开时拦截系统返回：先回到歌单列表（与本地音源一致）
    BackHandler(enabled = vm.currentPlaylist != null) { vm.closePlaylist() }

    // 专辑|歌单视图切换状态（与本地音源同构；搜索激活时隐藏切换入口）
    var showPlaylists by remember { mutableStateOf(false) }

    // 首次进入加载当前账号的歌单列表（loadPlaylists 内部有"已有数据不重查"保护）
    LaunchedEffect(Unit) { vm.loadPlaylists() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ===== 控件栏：视图/搜索 入口（整行四态 morph；标题在页面 TopAppBar）=====
        MusicToolbar(
            vm = vm,
            showPlaylists = showPlaylists,
            onSelectView = { showPlaylists = it },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ===== 错误提示条（与本地音源共用同一组件，重试走强制刷新）=====
        vm.serverError?.let { error ->
            ErrorRetryBar(error = error) { vm.loadAlbums(force = true) }
        }


        // ===== 专辑视图 ↔ 歌单视图：横向滑动 + 淡入淡出切换动画（与本地音源同款）=====
        AnimatedContent(
            targetState = showPlaylists && vm.searchQuery.isBlank(),
            transitionSpec = {
                if (targetState) {
                    (
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeIn(tween(300))
                    ) togetherWith (
                        slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } +
                            fadeOut(tween(200))
                    )
                } else {
                    (
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } +
                            fadeIn(tween(300))
                    ) togetherWith (
                        slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } +
                            fadeOut(tween(200))
                    )
                }
            },
            label = "libraryViewServer",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { showPlaylistView ->
            if (showPlaylistView) {
                // ===== 歌单视图：列表/详情 morph + 右下角新建 FAB（与本地音源共用）=====
                PlaylistViewWithFab(
                    vm = vm,
                    trackCoverModifier = trackCoverModifier,
                    onPlayFromList = onPlayFromList,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {


        // ===== 主体：专辑列表 ↔ 专辑详情 morph（照 TaskScreen 任务卡↔详情浮层范式）=====
        // 局部 SharedTransitionLayout + AnimatedContent(vm.currentAlbum)：
        // 列表分支与详情分支互斥组合，专辑行与详情头部通过同 key 的
        // sharedElement（容器 "music_album_<id>" + 封面 "music_album_cover_<id>"）
        // 实现卡片放大/缩回的 morph 动画；搜索结果分支同属列表侧，也可触发 morph
        SharedTransitionLayout(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = vm.currentAlbum,
                transitionSpec = {
                    if (targetState != null) {
                        // 展开：详情立即出现（不淡入），仅列表淡出 —— 与 TaskScreen 一致，
                        // 避免 scrim 淡入期间透明导致底层列表透出
                        (
                            EnterTransition.None togetherWith
                                fadeOut(tween(250, easing = FastOutSlowInEasing))
                            ) using SizeTransform(clip = false)
                    } else {
                        // 收回：列表快速淡入，详情缓慢淡出
                        (
                            fadeIn(tween(250, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(tween(400, easing = FastOutSlowInEasing))
                            ) using SizeTransform(clip = false)
                    }
                },
                label = "albumDetail",
            ) { album ->
                if (album != null) {
                    // 专辑详情分支
                    AlbumDetailContent(
                        vm = vm,
                        album = album,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        trackCoverModifier = trackCoverModifier,
                        onPlayFromList = onPlayFromList,
                    )
                } else {
                    when {
                        // 搜索中：优先展示搜索结果（歌曲在前、专辑在后）
                        vm.searchQuery.isNotBlank() && vm.searchResult != null -> SearchResultContent(
                            vm = vm,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                            trackCoverModifier = trackCoverModifier,
                            onPlayFromList = onPlayFromList,
                        )
                        // 专辑列表（加载中显示居中转圈）
                        else -> AlbumListContent(
                            albums = vm.albums,
                            isLoading = vm.isLoadingServer,
                            emptyText = "服务器上还没有专辑",
                            onOpenAlbum = { vm.openAlbum(it) },
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                        )
                    }
                }
            }
        }
                } // 专辑视图内层 Column 结束
            } // if(showPlaylistView)/else 结束
        } // AnimatedContent(专辑↔歌单视图切换) 结束
    }
}

/**
 * 曲库控件栏 —— 整行单 sharedElement 容器，四态 morph（本地与服务器音源共用）：
 * NORMAL（图标收起态）/ SORT（整行=排序选项）/ VIEW（整行=视图选项）/ SEARCH（整行=搜索框）。
 * 展开某态时其余控件被挤开、行内只剩该态内容；行高恒定 48dp 不跳变；
 * 收起态与任一展开态互为同一 sharedElement（"music_toolbar_container"），
 * 切换即整行容器 morph。标题不在此栏（在页面 TopAppBar，显示音源名）。
 */
private enum class MusicToolbarMode { NORMAL, SORT, VIEW, SEARCH }

/**
 * 展开式图标选择器的一个选项（图标 + 两字标签，横排格子用）。
 */
private data class SelectorOption(
    val key: String,
    val icon: ImageVector,
    val label: String,
)

/**
 * 曲库控件栏（四态整行 morph）。收起态：右对齐 [排序图标][视图图标][搜索图标]；
 * 点排序/视图图标 → 整行 morph 成等高横排选项（其余控件挤开）；
 * 点搜索图标 → 整行 morph 成搜索输入框（同样走 sharedElement）。
 * 展开态点选某项即生效并收回；✕ 收起；退出搜索清空查询词。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MusicToolbar(
    vm: MusicViewModel,
    showPlaylists: Boolean,
    onSelectView: (Boolean) -> Unit,
) {
    var mode by remember { mutableStateOf(MusicToolbarMode.NORMAL) }

    // 排序/视图的选项集合（未来加选项 = 加一项，控件栏占位不变）
    val sortOptions = remember {
        listOf(
            SelectorOption("title", Icons.Default.SortByAlpha, "名称"),
            SelectorOption("newest", Icons.Default.Schedule, "最新"),
        )
    }
    val viewOptions = remember {
        listOf(
            SelectorOption("album", Icons.Default.Album, "专辑"),
            SelectorOption("playlist", Icons.Default.QueueMusic, "歌单"),
        )
    }

    SharedTransitionLayout(modifier = Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                // 容器 bounds 一致（恒定行高），morph 主要是内容交叉淡化
                fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false)
            },
            label = "musicToolbar",
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { m ->
            Row(
                // 收起态与全部展开态共享同一 sharedElement：整行容器 morph
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .sharedElement(
                        rememberSharedContentState("music_toolbar_container"),
                        this@AnimatedContent,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (m) {
                    MusicToolbarMode.NORMAL -> {
                        // 左侧留空把图标推到右端（标题在页面 TopAppBar）
                        Spacer(modifier = Modifier.weight(1f))
                        // 排序入口：仅本地音源 + 专辑视图 + 未搜索
                        if (vm.source == MusicSource.LOCAL && !showPlaylists && vm.searchQuery.isBlank()) {
                            val current = if (vm.localAlbumSort == LocalAlbumSort.TITLE) {
                                sortOptions[0]
                            } else {
                                sortOptions[1]
                            }
                            IconButton(onClick = { mode = MusicToolbarMode.SORT }) {
                                Icon(
                                    current.icon,
                                    contentDescription = "排序方式",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 视图入口：搜索激活时隐藏（搜索结果在专辑视图内展示）
                        if (vm.searchQuery.isBlank()) {
                            val current = if (showPlaylists) viewOptions[1] else viewOptions[0]
                            IconButton(onClick = { mode = MusicToolbarMode.VIEW }) {
                                Icon(
                                    current.icon,
                                    contentDescription = "曲库视图",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 搜索入口：进入整行搜索态
                        IconButton(onClick = { mode = MusicToolbarMode.SEARCH }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "搜索音乐",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    MusicToolbarMode.SORT -> {
                        // 整行只剩排序选项（横排等高格子），其余控件被挤开
                        sortOptions.forEach { opt ->
                            SelectorCell(
                                option = opt,
                                selected = (vm.localAlbumSort == LocalAlbumSort.TITLE) == (opt.key == "title"),
                                onClick = {
                                    vm.localAlbumSort =
                                        if (opt.key == "title") LocalAlbumSort.TITLE else LocalAlbumSort.NEWEST
                                    mode = MusicToolbarMode.NORMAL
                                },
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        CloseToolbarButton { mode = MusicToolbarMode.NORMAL }
                    }

                    MusicToolbarMode.VIEW -> {
                        // 整行只剩视图选项
                        viewOptions.forEach { opt ->
                            SelectorCell(
                                option = opt,
                                selected = showPlaylists == (opt.key == "playlist"),
                                onClick = {
                                    onSelectView(opt.key == "playlist")
                                    mode = MusicToolbarMode.NORMAL
                                },
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        CloseToolbarButton { mode = MusicToolbarMode.NORMAL }
                    }

                    MusicToolbarMode.SEARCH -> {
                        // 整行 = 搜索输入框（同样在 sharedElement 容器内 morph 出现）
                        OutlinedTextField(
                            value = vm.searchQuery,
                            onValueChange = { vm.searchQuery = it },
                            placeholder = { Text("搜索歌曲 / 专辑 / 艺术家") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        )
                        CloseToolbarButton {
                            // 退出搜索即清空查询词（视图自动回落专辑列表）
                            vm.searchQuery = ""
                            mode = MusicToolbarMode.NORMAL
                        }
                    }
                }
            }
        }
    }
}

/**
 * 展开态的横排选项格 —— 图标 + 两字标签并排、等高 40dp 胶囊按钮，
 * 当前项染主色。点击由调用方处理（选完收回 NORMAL）。
 */
@Composable
private fun SelectorCell(
    option: SelectorOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Icon(option.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            option.label,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

/**
 * 控件栏展开态右侧的 ✕ 收起按钮。
 */
@Composable
private fun CloseToolbarButton(onClose: () -> Unit) {
    IconButton(onClick = onClose) {
        Icon(
            Icons.Default.Close,
            contentDescription = "收起",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * 歌单视图（含新建 FAB）—— 歌单列表 ↔ 详情 morph 面板 + 右下角新建按钮。
 * FAB 复用任务页同款样式（圆角 16 / 主色 / Add 图标，淡入淡出显隐），
 * 仅在歌单列表态（未打开详情）显示；点击弹新建歌单对话框。
 * 本地与服务器音源共用。
 */
@Composable
private fun PlaylistViewWithFab(
    vm: MusicViewModel,
    // 曲目行小封面 sharedElement 工厂（点歌时飞成播放页大封面）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    // 新建歌单对话框开关（与 FAB 同生命周期）
    var showCreateDialog by remember { mutableStateOf(false) }
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, comment ->
                showCreateDialog = false
                vm.createPlaylist(name, comment)
            },
        )
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 歌单加载/操作错误条（点击强制重载）
            vm.playlistError?.let { error ->
                ErrorRetryBar(error = error) { vm.loadPlaylists(force = true) }
            }
            PlaylistPanel(
                vm = vm,
                trackCoverModifier = trackCoverModifier,
                onPlayFromList = onPlayFromList,
            )
        }
        // 新建歌单 FAB：任务页同款（仅歌单列表态显示，详情态淡出）
        AnimatedVisibility(
            visible = vm.currentPlaylist == null,
            enter = fadeIn(tween(250, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180, easing = FastOutSlowInEasing)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp),
        ) {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 0.dp,
                    hoveredElevation = 0.dp,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建歌单")
            }
        }
    }
}

/**
 * 错误提示条 —— 本地与服务器音源共用：错误文案 + "点击重试"，整条可点。
 *
 * @param error 展示给用户的错误信息
 * @param onRetry 点击整条时的重试回调
 */
@Composable
private fun ErrorRetryBar(error: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRetry)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 重试操作用 Refresh 图标（无障碍朗读由整条 clickable 语义兜底）
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/**
 * 专辑列表内容 —— 本地与服务器音源共用（数据由调用方注入）。
 *
 * @param albums 专辑列表（统一模型 [LibraryAlbum]）
 * @param isLoading 数据加载中（列表为空时显示居中转圈）
 * @param emptyText 列表为空时的提示文案（本地/服务器各传各的）
 * @param onOpenAlbum 点击专辑行回调（打开详情）
 * @param sharedTransitionScope 专辑卡 morph：列表行的 sharedElement 需要这两个作用域生成 key
 * @param animatedVisibilityScope 同上
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumListContent(
    albums: List<LibraryAlbum>,
    isLoading: Boolean,
    emptyText: String,
    onOpenAlbum: (LibraryAlbum) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    if (isLoading && albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(albums, key = { "album-${it.id}" }) { album ->
            AlbumRow(
                album = album,
                onClick = { onOpenAlbum(album) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

/**
 * 专辑详情内容 —— 专辑头部信息 + 曲目列表；点击曲目以该专辑全部曲目为队列起播。
 * 本地/服务器共用：曲目读 [MusicViewModel.albumSongs]（服务器=getAlbum 结果，
 * 本地=按 ALBUM_ID 过滤出的该专辑曲目），封面双通道（服务器 URL / 本地 albumArtUri）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumDetailContent(
    vm: MusicViewModel,
    // 专辑数据由外层 AnimatedContent 分支直接传入（进入本分支时必然非空）
    album: LibraryAlbum,
    // 专辑卡 morph：详情头部的 sharedElement 与列表行同 key 配对
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 曲目行小封面 sharedElement 工厂（点歌时飞成播放页大封面）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    if (vm.isLoadingServer && vm.albumSongs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 专辑头部：容器 morph（与列表专辑行配对）+ 大封面 + 名称/艺术家/年份曲目数
        item(key = "album_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("music_album_${album.id}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                // 临界弹簧：柔和无过冲的尺寸/位置过渡
                                boundsTransform = { _, _ ->
                                    spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                                },
                            )
                        },
                    )
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 大封面：与专辑列表行的封面缩略图同 key 配对，
                // 打开详情时小封面放大飞到此位置，关闭时缩回
                CoverArt(
                    coverUrl = album.coverArtUrl,
                    // 本地专辑封面走 albumArtUri 通道（loadThumbnail 提取内嵌封面）
                    albumArtUri = album.albumArtUri,
                    modifier = Modifier
                        .size(96.dp)
                        .then(
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState("music_album_cover_${album.id}"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                                    },
                                )
                            },
                        ),
                    placeHolderSize = 40.dp,
                    cornerRadius = 16.dp,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        album.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val meta = buildList {
                        if (album.year > 0) add(album.year.toString())
                        add("${album.songCount} 首")
                        if (album.durationSeconds > 0) add(formatTrackDuration(album.durationSeconds))
                    }.joinToString(" · ")
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 曲目列表：点击即以专辑全部曲目为队列，从该曲开始播放
        itemsIndexed(vm.albumSongs) { index, track ->
            TrackRow(
                track = track,
                index = index,
                isCurrent = vm.currentTrack?.id == track.id,
                isPlaying = vm.isPlaying,
                coverModifier = trackCoverModifier,
                onClick = {
                    vm.playQueue(vm.albumSongs, index)
                    // 点歌后展开播放页，该行小封面飞成播放页大封面
                    onPlayFromList(track.id)
                },
            )
        }
    }
}

/**
 * 搜索结果内容 —— 歌曲（可点播）在前、专辑（可点开详情）在后。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SearchResultContent(
    vm: MusicViewModel,
    // 专辑卡 morph：搜索结果里的专辑行与详情页配对
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 曲目行小封面 sharedElement 工厂（点歌时飞成播放页大封面）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    val result = vm.searchResult ?: return
    if (result.songs.isEmpty() && result.albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有找到「${vm.searchQuery}」相关的结果",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (result.songs.isNotEmpty()) {
            item(key = "search_songs_header") { SectionLabel("歌曲") }
            itemsIndexed(result.songs) { index, track ->
                TrackRow(
                    track = track,
                    index = index,
                    isCurrent = vm.currentTrack?.id == track.id,
                    isPlaying = vm.isPlaying,
                    coverModifier = trackCoverModifier,
                    // 搜索结果播放队列 = 搜索到的歌曲列表
                    onClick = {
                        vm.playQueue(result.songs, index)
                        // 点歌后展开播放页，该行小封面飞成播放页大封面
                        onPlayFromList(track.id)
                    },
                )
            }
        }
        if (result.albums.isNotEmpty()) {
            item(key = "search_albums_header") { SectionLabel("专辑") }
            items(result.albums, key = { "search-album-${it.id}" }) { album ->
                AlbumRow(
                    album = album,
                    onClick = { vm.openAlbum(album) },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        }
    }
}

/* ============================================================
 * 歌单（本地 / 服务器共用同一套组件，双端同构）
 * ============================================================ */

/**
 * 歌单面板 —— 歌单列表 ↔ 歌单详情 morph（结构与专辑列表 ↔ 专辑详情完全一致）。
 * 本地与服务器音源共用：数据全部来自 [MusicViewModel] 的统一歌单状态，
 * 打开/新建/删除等操作由 VM 按音源分发，本组件不感知来源。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistPanel(
    vm: MusicViewModel,
    // 曲目行小封面 sharedElement 工厂（点歌时飞成播放页大封面）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    // 局部 SharedTransitionLayout + AnimatedContent(currentPlaylist)：
    // 歌单行与详情头部通过同 key 的 sharedElement（"music_playlist_<id>"）配对，
    // 本地歌单 id 自带 "local-" 前缀，与云端歌单 key 天然错开
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = vm.currentPlaylist,
            transitionSpec = {
                if (targetState != null) {
                    (
                        EnterTransition.None togetherWith
                            fadeOut(tween(250, easing = FastOutSlowInEasing))
                        ) using SizeTransform(clip = false)
                } else {
                    (
                        fadeIn(tween(250, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(tween(400, easing = FastOutSlowInEasing))
                        ) using SizeTransform(clip = false)
                }
            },
            label = "playlistDetail",
        ) { playlist ->
            if (playlist != null) {
                // 歌单详情分支（本地/服务器共用）
                PlaylistDetailContent(
                    vm = vm,
                    playlist = playlist,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    trackCoverModifier = trackCoverModifier,
                    onPlayFromList = onPlayFromList,
                )
            } else {
                // 歌单列表分支（歌单行 + 删除；新建入口由外层 FAB 承担）
                PlaylistListContent(
                    vm = vm,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                )
            }
        }
    }
}

/**
 * 歌单列表内容 —— 歌单行（名称 + 曲数 + 说明摘要 + 删除按钮）。
 * 加载中/空态与 [AlbumListContent] 同构（新建歌单由外层 FAB 触发）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistListContent(
    vm: MusicViewModel,
    // 歌单行 morph：需这两个作用域生成 sharedElement key
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    if (vm.isLoadingPlaylists && vm.playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(vm.playlists, key = { "playlist-${it.id}" }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                onClick = { vm.openPlaylist(playlist.id) },
                onDelete = { vm.deletePlaylist(playlist.id) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
        if (vm.playlists.isEmpty()) {
            item(key = "playlist_empty") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有歌单，点右下角 + 新建一个",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 歌单行：图标 + 名称/曲数/说明摘要 + 删除按钮。本地/服务器共用（统一模型）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistRow(
    playlist: LibraryPlaylist,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    // 行容器 morph：与歌单详情头部配对（key "music_playlist_<id>"）
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .then(
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState("music_playlist_${playlist.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ ->
                            spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                        },
                    )
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.QueueMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 曲数 + 说明摘要：说明最多两行，多余省略
            Text(
                "${playlist.songCount} 首",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playlist.comment.isNotBlank()) {
                Text(
                    playlist.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 删除按钮（IconButton 自行消费点击事件，不触发整行 onClick）
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除歌单",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 歌单详情内容 —— 头部（名称 + 说明 + 曲目数/总时长 + 播放整单）+ 曲目列表（可单首移除）。
 * 点击曲目以整个歌单为队列起播。本地/服务器共用：曲目读 [MusicViewModel.playlistTracks]。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistDetailContent(
    vm: MusicViewModel,
    playlist: LibraryPlaylist,
    // 歌单行 morph：头部 sharedElement 与列表行同 key 配对
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 曲目行小封面 sharedElement 工厂（点歌时飞成播放页大封面）
    trackCoverModifier: @Composable (String) -> Modifier,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    if (vm.isLoadingPlaylists && vm.playlistTracks.isEmpty() && !playlist.isLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 歌单头部：容器 morph（与列表歌单行配对）+ 名称/说明/统计 + 播放整单按钮
        item(key = "playlist_header") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("music_playlist_${playlist.id}"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                boundsTransform = { _, _ ->
                                    spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                                },
                            )
                        },
                    )
                    .padding(top = 8.dp, bottom = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        // 统计行：曲目数 + 总时长（本地歌单头部为现算值，云端为服务端统计）
                        val duration = if (playlist.durationSeconds > 0) {
                            playlist.durationSeconds.toLong()
                        } else {
                            vm.playlistTracks.sumOf { it.durationSeconds }
                        }
                        Text(
                            listOf("${vm.playlistTracks.size} 首", formatTrackDuration(duration))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 播放整单：以歌单全部曲目为队列从第一首起播
                    FilledIconButton(
                        onClick = { vm.playPlaylist() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "播放整单",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                if (playlist.comment.isNotBlank()) {
                    Text(
                        playlist.comment,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        // 曲目列表：点击以歌单为队列起播，行尾可单首移除
        itemsIndexed(vm.playlistTracks, key = { i, t -> "pl-track-$i-${t.id}" }) { index, track ->
            TrackRow(
                track = track,
                index = index,
                isCurrent = vm.currentTrack?.id == track.id,
                isPlaying = vm.isPlaying,
                coverModifier = trackCoverModifier,
                onClick = {
                    vm.playQueue(vm.playlistTracks, index)
                    onPlayFromList(track.id)
                },
                onRemove = { vm.removePlaylistTrack(playlist.id, index) },
            )
        }
        if (vm.playlistTracks.isEmpty()) {
            item(key = "playlist_tracks_empty") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "歌单还是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 新建歌单对话框 —— 名称（必填）+ 策展说明（选填）。
 * 确认后回调 [onConfirm]，由 VM 按当前音源分发创建。
 */
@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, comment: String) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var commentInput by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = commentInput,
                    onValueChange = { commentInput = it },
                    label = { Text("说明（选填）") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(nameInput, commentInput) },
                enabled = nameInput.isNotBlank(),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 服务器配置表单（引导态）。
 *
 * 类脑娘服务器：只填用户名/密码（URL 已预置），并提示先在网页端创建账号；
 * 自己的服务器：额外填写服务器地址。
 * 提供"测试连接"（ping 校验，不落库）与"保存"（持久化到 settings 表）。
 *
 * @param vm 共享的音乐 ViewModel
 * @param isCustom true=自己的服务器表单；false=类脑娘服务器表单
 */
@Composable
private fun ServerConfigForm(vm: MusicViewModel, isCustom: Boolean) {
    // 表单本地输入状态；LaunchedEffect 在 VM 异步加载完已存配置后回填，
    // 让"返回修改账密"场景能看到旧值
    var urlInput by remember { mutableStateOf(vm.customServerUrl) }
    var userInput by remember {
        mutableStateOf(if (isCustom) vm.customUsername else vm.braingirlUsername)
    }
    var passInput by remember {
        mutableStateOf(if (isCustom) vm.customPassword else vm.braingirlPassword)
    }
    LaunchedEffect(vm.customServerUrl, vm.customUsername, vm.braingirlUsername) {
        urlInput = vm.customServerUrl
        userInput = if (isCustom) vm.customUsername else vm.braingirlUsername
        passInput = if (isCustom) vm.customPassword else vm.braingirlPassword
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "title") {
            Column {
                Text(
                    if (isCustom) "连接你的服务器" else "连接类脑娘服务器",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (isCustom) {
                        "填入 Navidrome / Subsonic 服务器地址与账号信息"
                    } else {
                        "预置音源 ${MusicViewModel.BRAINGIRL_SERVER_URL}，使用你在网页端注册的账号登录"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 仅"自己的服务器"需要填地址
        if (isCustom) {
            item(key = "url") {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        vm.clearConfigTest()
                    },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://music.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "username") {
            OutlinedTextField(
                value = userInput,
                onValueChange = {
                    userInput = it
                    vm.clearConfigTest()
                },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "password") {
            OutlinedTextField(
                value = passInput,
                onValueChange = {
                    passInput = it
                    vm.clearConfigTest()
                },
                label = { Text("密码") },
                // 密码框用密码掩码，同时允许临时明文查看防输错
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 类脑娘音源专属：注册账号入口 —— 未配置（引导态）即可见，
        // 点击用系统浏览器打开网页注册页；注册完成后网页重定向
        // nion://musicauth?u=..&p=.. 深链，App 接住后自动完成配置（见 MainActivity）
        if (!isCustom) {
            item(key = "register") {
                val context = LocalContext.current
                OutlinedButton(
                    onClick = {
                        Log.d("MusicScreen", "注册账号：发出浏览器 Intent ${MusicViewModel.BRAINGIRL_REGISTER_URL}")
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(MusicViewModel.BRAINGIRL_REGISTER_URL)),
                            )
                        }.onFailure {
                            // 设备上没有浏览器等能处理 https 链接的应用时走到这里，仅记录不崩溃
                            Log.w("MusicScreen", "打开注册页面失败", it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("注册账号")
                }
            }
        }

        item(key = "buttons") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 测试连接：用表单当前值 ping 服务器，不落库
                OutlinedButton(
                    onClick = {
                        vm.testServerConfig(
                            sourceToTest = if (isCustom) MusicSource.CUSTOM else MusicSource.BRAINGIRL,
                            url = urlInput,
                            username = userInput,
                            password = passInput,
                        )
                    },
                    enabled = !vm.configTest.isTesting,
                ) {
                    Text(if (vm.configTest.isTesting) "测试中..." else "测试连接")
                }
                // 保存：持久化配置；保存后 isXxxConfigured 变 true，UI 自动切到浏览态
                Button(
                    onClick = {
                        if (isCustom) {
                            vm.saveCustomConfig(urlInput, userInput, passInput)
                        } else {
                            vm.saveBraingirlConfig(userInput, passInput)
                        }
                    },
                    // URL + 账密任一为空禁用保存
                    enabled = userInput.isNotBlank() && passInput.isNotBlank() &&
                        (!isCustom || urlInput.isNotBlank()),
                ) {
                    Text("保存并进入")
                }
            }
        }

        // 测试结果反馈：成功绿色（primary）、失败红色（error）
        if (vm.configTest.message.isNotBlank()) {
            item(key = "test_result") {
                Text(
                    vm.configTest.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (vm.configTest.success == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

/* ============================================================
 * 播放器 UI（迷你条 + 完整播放页）
 * ============================================================ */

/**
 * 迷你播放条 —— 固定在音乐页底部。
 *
 * 结构：细进度条（当前曲进度）+ 封面缩略图 + 曲名/艺术家 + 播放暂停 + 下一首。
 * 点击条主体（按钮区域除外）展开完整播放页。
 *
 * @param vm 共享的音乐 ViewModel
 * @param track 当前播放曲目
 * @param onExpand 点击播放条主体时触发（展开完整播放页）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerBar(
    vm: MusicViewModel,
    track: PlayableTrack,
    onExpand: () -> Unit,
    // 迷你条 ↔ 播放页 morph：容器 sharedBounds + 封面 sharedElement 均需这两个作用域
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    Surface(
        // 容器 sharedBounds：迷你条整体与播放页全屏容器配对，展开时条"生长"成全屏页，
        // 收起时全屏页缩回条 —— 参照 TaskScreen FAB↔表单的 sharedBounds 模式
        modifier = with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState("music_player_bar"),
                animatedVisibilityScope = animatedVisibilityScope,
                // 临界弹簧：柔和无过冲，与项目统一曲线一致
                boundsTransform = { _, _ ->
                    spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                },
            )
        },
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.clickable(onClick = onExpand)) {
            // 细进度条：宽度按 position/duration 比例；时长未知时隐藏
            val progress = if (vm.durationMs > 0) {
                (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f)
            } else null
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面缩略图（无封面时用音符图标占位）。
                // sharedElement key "music_player_cover"：从迷你条展开播放页时，
                // 小封面飞成播放页大封面（点歌打开时播放页封面改配对行内封面，此处不参与）
                CoverArt(
                    coverUrl = track.coverArtUrl,
                    albumArtUri = track.albumArtUri,
                    modifier = Modifier
                        .size(44.dp)
                        .then(
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState("music_player_cover"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                                    },
                                )
                            },
                        ),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 播放/暂停（▶↔⏸ 带缩放+旋转+淡入淡出过渡，见 AnimatedPlayPauseIcon）
                IconButton(onClick = { vm.togglePlayPause() }) {
                    AnimatedPlayPauseIcon(
                        isPlaying = vm.isPlaying,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // 下一首
                IconButton(onClick = { vm.skipToNext() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 完整播放页 —— 全屏覆盖层。
 *
 * 从上到下：收起按钮 / 大封面 / 曲名+艺术家+专辑 / 进度条（可拖动）/ 
 * 播放控制（上一首、播放暂停、下一首）/ 播放队列列表。
 *
 * 进度条交互：拖动期间显示本地 dragPosition（避免 500ms 轮询把滑块拽回去），
 * 松手时一次性 seek 到目标位置并清空本地值。
 *
 * @param vm 共享的音乐 ViewModel
 * @param onClose 收起播放页（回到音乐列表页）时触发
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FullPlayerOverlay(
    vm: MusicViewModel,
    onClose: () -> Unit,
    // 迷你条/曲目行 ↔ 播放页 morph：容器 sharedBounds + 封面 sharedElement 均需这两个作用域
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 大封面的 sharedElement key：
    // - 点歌打开 = "music_track_cover_<trackId>"（与被点行内小封面配对）
    // - 迷你条展开 = "music_player_cover"（与迷你条封面配对）
    coverSharedKey: String,
) {
    val track = vm.currentTrack
    Surface(
        // 容器 sharedBounds：与迷你播放条配对，展开时迷你条生长为全屏页
        modifier = Modifier
            .fillMaxSize()
            .then(
                with(sharedTransitionScope) {
                    Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState("music_player_bar"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = { _, _ ->
                            spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                        },
                    )
                },
            ),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .statusBarsPadding(),
        ) {
            // ===== 顶部收起按钮 =====
            Row(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "收起播放页",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (track == null) {
                // 队列被清空的极端情况：提示并允许收起
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "没有正在播放的曲目",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Surface
            }

            // ===== 大封面 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    CoverArt(
                        coverUrl = track.coverArtUrl,
                        albumArtUri = track.albumArtUri,
                        modifier = Modifier
                            .size(280.dp)
                            .then(
                                with(sharedTransitionScope) {
                                    // 大封面 sharedElement：key 由打开方式决定 ——
                                    // 点歌打开与被点行内小封面配对，迷你条展开与迷你条封面配对
                                    Modifier.sharedElement(
                                        sharedContentState = rememberSharedContentState(coverSharedKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        boundsTransform = { _, _ ->
                                            spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                                        },
                                    )
                                },
                            ),
                        placeHolderSize = 96.dp,
                    )
                }
            }

            // ===== 曲名 / 艺术家 / 专辑 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.album.isNotBlank()) {
                    Text(
                        track.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ===== 进度条 =====
            // 拖动期间非空；null=跟随播放进度。key 绑定曲目 id，切歌重置拖动态
            var dragPosition by remember(track.id) { mutableStateOf<Float?>(null) }
            // 拖动中显示 dragPosition；总时长未知时滑块不可交互（duration=0）
            val sliderValue = dragPosition ?: vm.positionMs.toFloat()
            val sliderMax = vm.durationMs.toFloat().coerceAtLeast(1f)
            Slider(
                value = sliderValue.coerceIn(0f, sliderMax),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    // 松手：seek 到拖动位置并恢复跟随播放进度
                    dragPosition?.let { vm.seekTo(it.toLong()) }
                    dragPosition = null
                },
                valueRange = 0f..sliderMax,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                enabled = vm.durationMs > 0,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatTrackDuration((dragPosition?.toLong() ?: vm.positionMs) / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatTrackDuration(vm.durationMs / 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ===== 播放控制 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.skipToPrevious() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.width(24.dp))
                // 主按钮：实心圆形，视觉上作为播放页的核心操作
                FilledIconButton(
                    onClick = { vm.togglePlayPause() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    // ▶↔⏸ 切换过渡（缩放+旋转+淡入淡出），与迷你条共用同一组件
                    AnimatedPlayPauseIcon(
                        isPlaying = vm.isPlaying,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(modifier = Modifier.width(24.dp))
                IconButton(onClick = { vm.skipToNext() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ===== 播放队列 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "播放队列（${vm.queue.size} 首）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(vm.queue) { index, item ->
                    QueueRow(
                        track = item,
                        index = index,
                        isCurrent = index == vm.currentIndex,
                        onClick = { vm.playQueueIndex(index) },
                    )
                }
            }
        }
    }
}

/* ============================================================
 * 播放/暂停切换动画
 * ============================================================ */

/**
 * 播放/暂停图标 —— 状态切换时的过渡动画组件。
 *
 * ▶ ↔ ⏸ 切换用 AnimatedContent 交叉过渡：
 * 缩放（0.5→1）+ 淡入淡出 + 每次切换 90° 的轻微旋转，
 * 全部走 tween + FastOutSlowInEasing，与全 app 动画语言一致。
 * 播放页大按钮和迷你播放条按钮共用，保证两处手感相同。
 *
 * @param isPlaying 是否正在播放（true=⏸，false=▶）
 * @param modifier 图标额外修饰符（如指定尺寸）
 * @param tint 图标颜色
 */
@Composable
private fun AnimatedPlayPauseIcon(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    // 轻微旋转质感：每次切换累计 +360°（整圈自旋），用 animateFloatAsState 平滑转过去，
    // 落点永远是 360° 的整数倍，图标静止时始终正立，只有过渡过程能看到旋转
    var spin by remember { mutableStateOf(0) }
    LaunchedEffect(isPlaying) { spin += 360 }
    val rotation by animateFloatAsState(
        targetValue = spin.toFloat(),
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "playPauseSpin",
    )
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            // 进入：从一半大小放大 + 淡入；退出：缩小 + 淡出。
            // 全部 tween + FastOutSlowInEasing，与全 app 动画语言一致
            (
                scaleIn(
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                    initialScale = 0.5f,
                ) + fadeIn(tween(150, easing = FastOutSlowInEasing))
                ) togetherWith (
                scaleOut(
                    animationSpec = tween(150, easing = FastOutSlowInEasing),
                    targetScale = 0.5f,
                ) + fadeOut(tween(100, easing = FastOutSlowInEasing))
                )
        },
        label = "playPause",
        modifier = modifier.graphicsLayer { rotationZ = rotation },
    ) { playing ->
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            tint = tint,
        )
    }
}
/* ============================================================
 * 通用小组件
 * ============================================================ */

/**
 * 单个曲目行：封面缩略图 + 曲名/艺术家 + 时长。
 *
 * 封面来源双通道：本地曲目用专辑内嵌封面（albumArtUri），服务器曲目用 coverUrl；
 * 缩略图可挂 sharedElement（key 带曲目 id），从列表点歌时飞成播放页大封面。
 *
 * @param track 曲目数据
 * @param index 序号（仅用于点击回调时的队列下标）
 * @param isCurrent 是否为当前播放曲目（高亮标题 + 封面角标音符图标）
 * @param isPlaying 是否正在播放
 * @param coverModifier 行内小封面的 sharedElement 工厂（传入曲目 id 生成 key）
 * @param onClick 点击整行时触发（开始播放该曲）
 */
@Composable
private fun TrackRow(
    track: PlayableTrack,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    coverModifier: @Composable (String) -> Modifier = { Modifier },
    // 行尾移除按钮回调；null（默认）不显示 —— 歌单详情传入以支持单首移除
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面缩略图：本地曲目显示专辑内嵌封面，服务器曲目显示服务器封面，
        // 均无时显示专辑图标占位；当前曲叠加小音符角标标识播放位置
        Box {
            CoverArt(
                coverUrl = track.coverArtUrl,
                albumArtUri = track.albumArtUri,
                modifier = Modifier
                    .size(40.dp)
                    .then(coverModifier(track.id)),
                cornerRadius = 8.dp,
            )
            if (isCurrent) {
                // 当前曲角标：primary 色小圆底 + 音符图标，压在封面右下角
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                // 当前曲目高亮 + 加粗，一眼定位队列位置
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 时长（本地曲目未上报时长时显示为空，保持行高一致）
        Text(
            if (track.durationSeconds > 0) formatTrackDuration(track.durationSeconds) else "--:--",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 引导播放入口的视觉提示（isPlaying 参数保留给未来"跳动音符"动效用）
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPlaying && isCurrent) 1f else 0.4f),
            modifier = Modifier
                .size(20.dp)
                .padding(start = 4.dp),
        )
        // 单首移除按钮（仅歌单详情传入 onRemove 时显示）
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "从歌单移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 队列行 —— 与 TrackRow 类似但左侧固定显示序号（当前曲高亮序号替代为图标）。
 *
 * @param track 曲目数据
 * @param index 队列下标
 * @param isCurrent 是否当前播放（primary 色高亮）
 * @param onClick 点击跳播该曲
 */
@Composable
private fun QueueRow(
    track: PlayableTrack,
    index: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (track.durationSeconds > 0) formatTrackDuration(track.durationSeconds) else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 专辑行：封面缩略图 + 专辑名/艺术家 + 年份。本地/服务器共用（统一模型）。
 *
 * @param album 专辑数据（统一模型 [LibraryAlbum]，id 自带来源前缀、morph key 天然不撞）
 * @param onClick 点击打开专辑详情
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumRow(
    album: LibraryAlbum,
    onClick: () -> Unit,
    // 专辑卡 ↔ 专辑详情 morph：行容器与封面都挂 sharedElement，需这两个作用域
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            // 行容器 sharedElement：与专辑详情头部配对，打开时行 morph 成详情头部
            .then(
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState("music_album_${album.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        // 临界弹簧：柔和无过冲的尺寸/位置过渡
                        boundsTransform = { _, _ ->
                            spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                        },
                    )
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面双通道：服务器走 coverUrl，本地走 albumArtUri（loadThumbnail 内嵌封面），
        // 均无时显示专辑图标占位。
        // 与详情页大封面同 key 配对，打开详情时小封面放大飞行
        CoverArt(
            coverUrl = album.coverArtUrl,
            albumArtUri = album.albumArtUri,
            modifier = Modifier
                .size(52.dp)
                .then(
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("music_album_cover_${album.id}"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ ->
                                spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                            },
                        )
                    },
                ),
            cornerRadius = 10.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(album.artist, if (album.year > 0) album.year.toString() else null)
                    .filterNotNull()
                    .joinToString(" · ")
                    .ifBlank { "未知艺术家" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${album.songCount} 首",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 分区标题（"歌曲"/"专辑"）。
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * 空状态/引导卡片 —— 图标 + 标题 + 副标题 + 可选操作按钮。
 *
 * @param icon 大图标
 * @param title 主标题
 * @param subtitle 说明文字
 * @param actionLabel 操作按钮文字；null=不显示按钮
 * @param onAction 点击操作按钮时触发
 */
@Composable
private fun EmptyHint(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(modifier = Modifier.height(16.dp))
            // 图标优先：操作按钮只保留 Refresh 图标，语义由 contentDescription 朗读
            OutlinedIconButton(onClick = onAction) {
                Icon(Icons.Default.Refresh, contentDescription = actionLabel)
            }
        }
    }
}

/* ============================================================
 * 封面加载
 * ============================================================ */

/**
 * 进程内封面缓存 —— URL → Bitmap 的 LruCache（24 张），
 * 滚动列表时重复项直接命中内存，避免重复下载。
 */
private object CoverArtCache {
    private val cache = object : LruCache<String, Bitmap>(24) {}

    /** 查缓存；未命中返回 null */
    fun get(url: String): Bitmap? = cache.get(url)

    /** 写入缓存 */
    fun put(url: String, bitmap: Bitmap) = cache.put(url, bitmap)
}

/**
 * 封面 HTTP 加载器 —— OkHttp 同步 GET + BitmapFactory 解码。
 * 专为封面 URL 设计（不含 Subsonic 业务解析逻辑）。
 */
private object CoverArtLoader {
    private val client = OkHttpClient()

    /** 下载并解码封面；失败返回 null */
    fun load(url: String): Bitmap? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()
}


/**
 * 封面图 Composable —— 优先内存缓存 → 异步下载 → 占位图标。
 *
 * @param coverUrl 封面完整 URL（含认证参数）；空/null 显示占位图标
 * @param modifier 尺寸约束
 * @param placeHolderSize 占位图标大小（大封面用大图标）
 * @param cornerRadius 圆角半径（默认 8dp）
 */
@Composable
private fun CoverArt(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    placeHolderSize: androidx.compose.ui.unit.Dp = 24.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    // 本地专辑封面 URI（content://media/external/audio/albums/{albumId}）；
    // 非空时优先于 coverUrl：用 ContentResolver.loadThumbnail 提取内嵌封面
    albumArtUri: String? = null,
) {
    val context = LocalContext.current
    // 缓存 key：本地封面用 "local:<uri>" 前缀与服务器 URL 区分，共用同一个 LruCache
    val cacheKey = albumArtUri?.let { "local:$it" } ?: coverUrl
    // produceState：初值取内存缓存（同步命中无闪烁）；未命中则异步加载并写入缓存。
    // key 绑定 cacheKey，封面来源变化（切歌/换专辑）时自动重新加载
    val bitmap by produceState<Bitmap?>(
        initialValue = cacheKey?.let { CoverArtCache.get(it) },
        key1 = cacheKey,
    ) {
        if (value == null && cacheKey != null) {
            value = withContext(Dispatchers.IO) {
                loadCoverBitmap(context, coverUrl, albumArtUri)
                    ?.also { CoverArtCache.put(cacheKey, it) }
            }
        }
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        // 委托属性无法智能转换非空，先落到局部 val 再判空使用
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = "封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 占位：中性色专辑图标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Album,
                    contentDescription = null,
                    modifier = Modifier.size(placeHolderSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * 封面位图加载（IO 线程调用）—— 按来源分派：
 * - 本地 albumArtUri（API 29+）：ContentResolver.loadThumbnail 让系统 MediaProvider
 *   解析音频文件的内嵌封面（FLAC/MP3 等），无内嵌图时返回 null
 * - 服务器 coverUrl：OkHttp 下载 + BitmapFactory 解码
 *
 * @param context 用于获取 ContentResolver
 * @param coverUrl 服务器封面完整 URL；null=不加载网络封面
 * @param albumArtUri 本地专辑封面 URI；null=不加载本地封面
 * @return 解码成功返回 Bitmap，任何失败返回 null（UI 显示占位图标）
 */
private fun loadCoverBitmap(
    context: Context,
    coverUrl: String?,
    albumArtUri: String?,
): Bitmap? = when {
    // 本地封面优先：低于 API 29 无 loadThumbnail，直接落到占位图标
    albumArtUri != null && Build.VERSION.SDK_INT >= 29 -> runCatching {
        context.contentResolver.loadThumbnail(
            Uri.parse(albumArtUri),
            // 512px 兼顾列表缩略图与播放页大封面的清晰度
            Size(512, 512),
            null,
        )
    }.getOrNull()
    coverUrl != null -> CoverArtLoader.load(coverUrl)
    else -> null
}


