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
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
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
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.echonion.nion.ui.icons.LucideIcons

// ── 播放条 ↔ 播放页 morph 的统一时钟（毫秒）──
// 容器/封面 bounds 动画、播放页退场组合保持、控件淡入延迟全部以它为基准，
// 保证"矩形铺满 ⇔ 沉浸窗口结束 ⇔ 控件完全浮现"三件事严格同时收口
private const val MorphDurationMillis = 400

// 控件层入场：morph 起步 ~80ms 后开始，跨越整个 morph、铺满后再收尾 ~120ms。
// 配合"自下而上"的分组错落 —— 矩形向上扫过哪一组控件，哪一组就开始浮现，
// 封面落位与控件就位衔接成一个连续动作，而不是铺满后整页控件突然出现
private const val ControlsEnterDelayMillis = 80
private const val ControlsEnterDurationMillis = 440

// 控件入场上浮距离：从下方 24dp 处浮到原位（配合透明度，给出"落座"的方向感）
private val ControlsEnterOffset = 24.dp

/**
 * 把一条 0..1 的总进度切出 [start]..[end] 这一段并重新映射到 0..1（超出夹紧）。
 * 用于让多组控件共用一个动画时钟、但依次错落浮现。
 */
private fun staggered(progress: Float, start: Float, end: Float): Float =
    ((progress - start) / (end - start)).coerceIn(0f, 1f)

/**
 * 控件入场修饰：透明度 = 进度，同时从下方 [ControlsEnterOffset] 上浮到原位。
 * 只改 graphicsLayer（不触发重新布局），动画期间每帧开销只有一次图层属性更新。
 *
 * @param progress 本组控件的入场进度（0=隐藏在下方，1=就位）
 */
private fun Modifier.controlsEnter(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    alpha = p
    translationY = (1f - p) * ControlsEnterOffset.toPx()
}

/** 迷你播放条常态的顶部圆角（morph 中线性过渡到 0） */
private val MiniBarTopCornerRadius = 16.dp

/**
 * morph 容器背景的底边外延量（米色线根因之一）：过渡中共享元素在 overlay 里以
 * 浮点偏移绘制、尺寸按整数重测，矩形底边常落在亚像素位置 —— 最后一行像素只被
 * 半覆盖，下方迷你条进度条（橙）与 Scaffold 米色底从中透出，形成一条逐帧闪烁的
 * 亮线。两侧背景都向下多画 2dp 盖住这一行；超出内容区的部分被 DualPanelLayout
 * 主内容层的 clip 裁掉，绝不会画到底部导航栏上
 */
private val MorphBottomOverhang = 2.dp

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

    // 完整播放页是否展开：挂在 VM 上，NionApp 据此把底部导航栏染成沉浸色；
    // 跨路由记忆（回音乐页仍在播放页）
    var showFullPlayer by vm::isFullPlayerOpen

    // 本次展开的来源曲目：只决定入场起点，不决定收起终点。
    // 播放页在整次展开/收起期间保留同一个封面 key，收起时由播放栏接手配对；
    // 不在收起首帧更换大封面 key，避免丢失共享元素已经记录的当前位置。
    // 退场组合真正销毁后清空，使下一次点歌前各行封面已重新注册好入场 key。
    var openedFromTrackId by remember { mutableStateOf<String?>(null) }
    // 本次飞行的身份与正在播放哪一首分离：切歌后仍由当前大封面飞回播放栏。
    val playerCoverKey = remember(openedFromTrackId) {
        openedFromTrackId?.let { "music_track_cover_$it" } ?: "music_player_cover"
    }

    // 完整播放页打开时拦截系统返回：先收起播放页而不是退出音乐页
    BackHandler(enabled = showFullPlayer) { showFullPlayer = false }

    // 本页常驻取色（切歌即在后台按稳定 key 取封面、提取入缓存，不等点开播放页）：
    // 一个色源三处消费 —— 迷你条常态一体色 / morph 两侧的底锚定整屏渐变 /
    // NionApp 导航栏染色。展开瞬间即为歌曲色，不会先落米色兜底再慢半拍变色
    val backdrop = rememberPlayerBackdrop(vm.currentTrack, animateChanges = vm.isFullPlayerOpen)

    // 点击展开的两个同步入口：在点击回调里（重组之前）直接写 playerBackdrop，
    // 保证 morph 首帧导航栏就已是歌曲色。点歌时 playQueue 已同步切好
    // currentTrack，peekBackdrop 用被点行已解码的封面位图当场取色 —— 首帧即
    // 本首歌的真实颜色，而不是上一首/米色兜底（旧实现首次点歌整块米色展开）
    val openFromMini: () -> Unit = {
        vm.playerBackdrop = peekBackdrop(vm.currentTrack) ?: backdrop
        openedFromTrackId = null
        showFullPlayer = true
    }
    val openFromTrack: (String) -> Unit = { trackId ->
        vm.playerBackdrop = peekBackdrop(vm.currentTrack) ?: backdrop
        openedFromTrackId = trackId
        showFullPlayer = true
    }

    // ── 播放条 ↔ 播放页的唯一几何机制（照 TaskScreen FAB↔浮层范式）──
    // 结构：
    // SharedTransitionLayout
    //  └ Box（根，高度 = morph 目标高度；底边 = 内容区底边 = 导航栏顶边）
    //     ├ 音乐主界面 —— 常驻组合、永不被淡出（旧实现用 AnimatedContent
    //     │   交叉淡入，半透明帧会把窗口米色底透出来，这是米色线的温床）
    //     │   └ … Scaffold（TopAppBar + 音源内容）
    //     │       └ 迷你播放条 AnimatedVisibility(!showFullPlayer)（自持退场作用域）
    //     └ AnimatedVisibility(showFullPlayer) —— 最后一个孩子，盖在最上层
    //         └ FullPlayerOverlay
    // 容器配对：迷你条背景层 ↔ 播放页背景层同 key（"music_player_bar"），
    // bounds 条形↔全屏走同一条 tween 时钟；两端底边同为一根线（根底边），
    // morph 全程底边零位移 —— 底部米色缝被结构性排除
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        val sharedScope = this@SharedTransitionLayout

        // 根高度（px）：morph 目标高度，也是两侧共用的"整屏渐变"总高。
        // 迷你条端以 size.height - rootH 为 startY 画渐变 ⇒ 无论矩形多高，
        // 显示的都是最终整屏渐变的对应切片：无压扁、无纯色→渐变跳变，
        // 且切片最底像素 = backdrop.bottom = 导航栏色，交界天然无缝
        var rootHeightPx by remember { mutableStateOf(0f) }
        // 迷你条高度（px）：容器 morph 顶角圆角 16dp→0 插值的条形端端点
        var barHeightPx by remember { mutableStateOf(0f) }

        // 注：沉浸色（vm.playerBackdrop）的发布在 FullPlayerOverlay 内完成 ——
        // 跟随播放页自身的组合生命周期（AnimatedVisibility 把退场组合保持到
        // morph 结束），且随取色实时更新；此处只保留点击回调里的首帧预写

        // 容器 morph 的顶角圆角裁剪：条形端 16dp → 全屏端 0，按动画中实时
        // 高度线性插值。展开首帧矩形带着与迷你条常态一致的圆角（不会瞬间
        // 用直角盖掉行内容），收回末帧圆角平滑归位；两端共用同一 clip，
        // 展开/收回像素对称。clipPath 坐标系 = SharedTransitionScope 坐标系
        val containerCornerClip = remember {
            object : SharedTransitionScope.OverlayClip {
                private val clipPath = Path()
                override fun getClipPath(
                    sharedContentState: SharedTransitionScope.SharedContentState,
                    bounds: Rect,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    density: androidx.compose.ui.unit.Density,
                ): Path? {
                    val root = rootHeightPx
                    val bar = barHeightPx
                    if (root <= 0f || bar <= 0f || bounds.height >= root) return null
                    // 高度从条形(BarH)到全屏(RootH)的进度 → 圆角 16dp→0 的进度
                    val progress = ((root - bounds.height) / (root - bar)).coerceIn(0f, 1f)
                    val radius = with(density) { MiniBarTopCornerRadius.toPx() } * progress
                    if (radius < 0.5f) return null
                    clipPath.reset()
                    clipPath.addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = bounds.width,
                            // 底边随背景一起外延 MorphBottomOverhang，否则裁剪会把
                            // 盖亚像素缝的那 2dp 又切掉
                            bottom = bounds.height + with(density) { MorphBottomOverhang.toPx() },
                            topLeftCornerRadius = CornerRadius(radius, radius),
                            topRightCornerRadius = CornerRadius(radius, radius),
                        )
                    )
                    clipPath.translate(bounds.topLeft)
                    return clipPath
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { rootHeightPx = it.size.height.toFloat() },
        ) {
            // ===== 第一层：音乐主界面（常驻组合，永不淡出）=====
            // 曲目行封面槽：固定 40dp 槽位 + 每行自己的 AnimatedVisibility。
            // - 槽位固定：morph 期间行内不留洞、不回流（封面只是"被借走"飞行）
            // - 行作用域：被点行打开播放页时退场成为大封面 morph 的起点；
            //   收起时行封面只在原位淡入，不再接住大封面（目的地固定为播放栏）。
            //   入场 key 在点击前常驻注册，避免配对方迟到导致动画跳终态。
            val trackCoverSlot: @Composable (
                    trackId: String,
                    coverContent: @Composable () -> Unit,
                ) -> Unit = { trackId, coverContent ->
                    Box(modifier = Modifier.size(40.dp)) {
                        AnimatedVisibility(
                            visible = !(showFullPlayer && openedFromTrackId == trackId),
                            enter = fadeIn(tween(120, easing = FastOutSlowInEasing)),
                            exit = fadeOut(tween(120, easing = FastOutSlowInEasing)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .then(
                                        // 收起时先释放来源行的配对身份，只让播放栏成为进入方；
                                        // 即使入场尚未结束就收起，也不会有两个小封面争抢终点。
                                        if (!showFullPlayer && openedFromTrackId == trackId) {
                                            Modifier
                                        } else with(sharedScope) {
                                            Modifier.sharedElement(
                                                sharedContentState = rememberSharedContentState("music_track_cover_$trackId"),
                                                animatedVisibilityScope = this@AnimatedVisibility,
                                                boundsTransform = { _, _ ->
                                                    tween(MorphDurationMillis, easing = FastOutSlowInEasing)
                                                },
                                            )
                                        },
                                    ),
                            ) {
                                coverContent()
                            }
                        }
                    }
                }

                // 专辑|歌单视图切换状态（纯 UI 状态）：提升到页面级，由 TopAppBar 标题行
                // 的单入口图标 + 下方展开式选择器读写；本地与服务器两种音源共用同一状态，
                // 搜索态入口隐藏
                var showPlaylists by remember { mutableStateOf(false) }
                // 选择器展开态：TopAppBar 下方揭示行（专辑|歌单 两格 + 飞行图标）
                var viewExpanded by remember { mutableStateOf(false) }

                // ── 歌单列表操作态（长按行进入）——状态提升到此处 ──
                // 既供下方歌单行渲染（高亮/行内按钮/多选判定），也供 TopAppBar 在
                // 多选(>1)时就地换成选择栏（✕ 已选 N 项 🗑）。切换音源时自动重置，
                // 避免跨音源残留选中。selectedPlaylistIds：选中歌单 id 集合（空=普通模式，
                // 点行开详情；非空=操作模式，点行切换选中）；actionRowId：单选时挂行内
                // 「编辑/删除」ghost 钮的行 id（多选后置 null、按钮整体收回）
                var selectedPlaylistIds by remember(vm.source) { mutableStateOf(setOf<String>()) }
                var actionRowId by remember(vm.source) { mutableStateOf<String?>(null) }
                // 多选批量删除确认框开关（由选择栏右上角垃圾桶触发）
                var pendingPlaylistBatchDelete by remember { mutableStateOf(false) }
                // 离开歌单列表视图（切到专辑视图 / 打开某歌单详情）时清空操作态选中：
                // 选中态只在歌单列表有意义；hoist 到页面级后若不清，会把顶部选择栏/批量删除/
                // 返回拦截泄漏到专辑视图或播放页（本次实测：残留选中致播放页第一次返回被
                // "清选中"吃掉、看起来没反应）。showPlaylists=false 或打开了某歌单详情即清
                LaunchedEffect(showPlaylists, vm.currentPlaylist) {
                    if (!showPlaylists || vm.currentPlaylist != null) {
                        selectedPlaylistIds = emptySet()
                        actionRowId = null
                    }
                }
                // 操作模式下的系统返回：清空选中退出操作态（而非退出页面）。
                // && !showFullPlayer：播放页盖在上面时返回优先收播放页 —— 本 handler 组合更晚、
                // 优先级高于播放页 handler，不门控会先悄悄清选中、看起来"第一次返回失效"
                BackHandler(enabled = selectedPlaylistIds.isNotEmpty() && !showFullPlayer) {
                    selectedPlaylistIds = emptySet()
                    actionRowId = null
                }
                // 多选批量删除确认框：选择栏右上角垃圾桶触发（本地/服务器文案一致：
                // 都不可恢复；云端还会连带删服务器歌单，用计数话术统一提示）
                if (pendingPlaylistBatchDelete) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingPlaylistBatchDelete = false },
                        title = { Text("删除歌单") },
                        text = { Text("确定删除选中的 ${selectedPlaylistIds.size} 个歌单？该操作不可恢复。") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    // 逐个删除（VM 内各自分发本地/云端）；删完清空选中退出操作态
                                    selectedPlaylistIds.forEach { vm.deletePlaylist(it) }
                                    selectedPlaylistIds = emptySet()
                                    actionRowId = null
                                    pendingPlaylistBatchDelete = false
                                },
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { pendingPlaylistBatchDelete = false },
                            ) { Text("取消") }
                        },
                    )
                }

                Scaffold(
                    contentWindowInsets = WindowInsets.statusBars,
                    topBar = {
                        // 歌单多选(>1)时：音源标题行就地换成选择栏（✕ 已选 N 项 🗑），
                        // 与任务页 SelectionTopBar 同一语言；单选/常态显示普通音源标题行。
                        // AnimatedContent 做淡入淡出，避免硬切。
                        AnimatedContent(
                            targetState = selectedPlaylistIds.size > 1,
                            transitionSpec = {
                                fadeIn(tween(160, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(tween(140, easing = FastOutSlowInEasing))
                            },
                            label = "musicTopBar",
                        ) { selecting ->
                        if (selecting) {
                            MusicSelectionTopBar(
                                selectedCount = selectedPlaylistIds.size,
                                // ✕：清空选中退出操作态
                                onClear = {
                                    selectedPlaylistIds = emptySet()
                                    actionRowId = null
                                },
                                // 垃圾桶：弹批量删除确认框
                                onDelete = { pendingPlaylistBatchDelete = true },
                            )
                        } else {
                        TopAppBar(
                            title = {
                                // 标题行：音源名 + 视图切换单入口图标（搜索态自动隐藏）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (vm.source) {
                                            MusicSource.LOCAL -> "本地曲目"
                                            MusicSource.BRAINGIRL -> "类脑娘服务器"
                                            MusicSource.CUSTOM -> "自己的服务器"
                                        },
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (vm.searchQuery.isBlank()) {
                                        Spacer(modifier = Modifier.width(12.dp))
                                        IconButton(
                                            onClick = { viewExpanded = !viewExpanded },
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            // 展开时旋转 90°（> 变 ∨），收起复原
                                            val chevronRotation by animateFloatAsState(
                                                targetValue = if (viewExpanded) 90f else 0f,
                                                animationSpec = tween(180),
                                                label = "viewChevron",
                                            )
                                            Icon(
                                                LucideIcons.ChevronRight,
                                                contentDescription = "切换专辑/歌单视图",
                                                tint = if (viewExpanded) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.graphicsLayer {
                                                    rotationZ = chevronRotation
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                            actions = {
                                // 右上角伙伴入口，与专注页/日程页保持一致
                                IconButton(onClick = onOpenCompanion) {
                                    Icon(
                                        LucideIcons.MessageCircle,
                                        contentDescription = "打开伙伴",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        )
                        } // else（普通音源标题行）
                        } // AnimatedContent(musicTopBar)
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        // ===== 视图切换展开行：高度 0→40dp 揭示，ToolbarZone 宽度
                        // 0→全宽镜像展开，图标飞行入选中格；收起完全镜像 =====
                        val viewRowHeight by animateDpAsState(
                            targetValue = if (viewExpanded) 40.dp else 0.dp,
                            animationSpec = tween(180),
                            label = "viewRowH",
                        )
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(viewRowHeight)
                                .clipToBounds(),
                        ) {
                            val rowMaxWidth = maxWidth
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                ToolbarZone(
                                    expandedWidth = rowMaxWidth,
                                    collapsedWidth = 0.dp,
                                    expanded = viewExpanded,
                                    icon = if (showPlaylists) LucideIcons.ListMusic else LucideIcons.Disc3,
                                    contentDescription = "切换专辑/歌单视图",
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    iconTargetLeft = flightTarget(if (showPlaylists) 1 else 0),
                                    visible = viewExpanded,
                                    onIconClick = { viewExpanded = false },
                                    onClose = { viewExpanded = false },
                                    showClose = false,
                                ) {
                                    SelectorCell(
                                        option = SelectorOption("album", LucideIcons.Disc3, "专辑"),
                                        selected = !showPlaylists,
                                        showIcon = showPlaylists,
                                        onClick = {
                                            showPlaylists = false
                                            viewExpanded = false
                                        },
                                    )
                                    SelectorCell(
                                        option = SelectorOption("playlist", LucideIcons.ListMusic, "歌单"),
                                        selected = showPlaylists,
                                        showIcon = !showPlaylists,
                                        onClick = {
                                            showPlaylists = true
                                            viewExpanded = false
                                        },
                                    )
                                }
                            }
                        }
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
                                    showPlaylists = showPlaylists,
                                    trackCoverSlot = trackCoverSlot,
                                    onPlayFromList = openFromTrack,
                                    // 歌单操作态（长按进入）：状态在 MusicScreen 持有，
                                    // 穿透到 PlaylistViewWithFab 驱动行高亮/行内按钮/多选
                                    playlistSelectedIds = selectedPlaylistIds,
                                    playlistActionRowId = actionRowId,
                                    onPlaylistSelectedIdsChange = { selectedPlaylistIds = it },
                                    onPlaylistActionRowIdChange = { actionRowId = it },
                                )
                                // 服务器：未配置时显示引导配置表单，配置后进入曲库浏览
                                MusicSource.BRAINGIRL -> ServerMusicContent(
                                    vm = vm,
                                    showPlaylists = showPlaylists,
                                    isCustom = false,
                                    trackCoverSlot = trackCoverSlot,
                                    onPlayFromList = openFromTrack,
                                    playlistSelectedIds = selectedPlaylistIds,
                                    playlistActionRowId = actionRowId,
                                    onPlaylistSelectedIdsChange = { selectedPlaylistIds = it },
                                    onPlaylistActionRowIdChange = { actionRowId = it },
                                )
                                MusicSource.CUSTOM -> ServerMusicContent(
                                    vm = vm,
                                    showPlaylists = showPlaylists,
                                    isCustom = true,
                                    trackCoverSlot = trackCoverSlot,
                                    onPlayFromList = openFromTrack,
                                    playlistSelectedIds = selectedPlaylistIds,
                                    playlistActionRowId = actionRowId,
                                    onPlaylistSelectedIdsChange = { selectedPlaylistIds = it },
                                    onPlaylistActionRowIdChange = { actionRowId = it },
                                )
                            }
                        }

                        // ===== 迷你播放条（有正在播放的曲目时显示）=====
                        // 包在自己的 AnimatedVisibility(!showFullPlayer) 里：
                        // 展开时它退场、播放页进场，容器/封面配对的 morph 由这两个
                        // 作用域驱动；收回时反向。退场 fade 只负责"组合计时"与
                        // 未配对内容的淡出，视觉几何全部在共享 overlay 里完成
                        vm.currentTrack?.let { track ->
                            AnimatedVisibility(
                                visible = !showFullPlayer,
                                enter = fadeIn(tween(120, easing = FastOutSlowInEasing)),
                                exit = fadeOut(tween(120, easing = FastOutSlowInEasing)),
                                // 顺带量出条形高度：容器 morph 顶角圆角插值的端点
                                modifier = Modifier.onGloballyPositioned {
                                    barHeightPx = it.size.height.toFloat()
                                },
                            ) {
                                MiniPlayerBar(
                                    vm = vm,
                                    track = track,
                                    // 点歌入场时播放栏不与来源行争抢起点；收起时接手
                                    // 大封面的现有 key，终点始终是播放栏的小封面。
                                    coverSharedKey = if (showFullPlayer) "music_player_cover" else playerCoverKey,
                                    backdrop = backdrop,
                                    rootHeightPx = { rootHeightPx },
                                    containerCornerClip = containerCornerClip,
                                    // 点击播放条主体 → 展开完整播放页（封面与迷你条封面配对飞行）
                                    onExpand = openFromMini,
                                    sharedTransitionScope = sharedScope,
                                    animatedVisibilityScope = this@AnimatedVisibility,
                                )
                            }
                        }
                    }
                }

            // ===== 最后一层：完整播放页浮层 =====
            // 进退场 = 自底边（导航栏顶边）垂直展开/收回，与容器 morph 同一 tween：
            // - 有迷你条可配对（已有曲目在播）：背景层/封面是 sharedElement，在共享
            //   overlay 里渲染、不受此处裁剪，几何完全由 bounds morph 承担；
            // - 无迷你条可配对（首次点歌，点击前没有迷你条）：背景层无配对方，
            //   退化为本层的"自下而上铺开"—— 依旧贴着导航栏生长，绝不整屏淡入；
            // - 收起时它同时是"组合计时器"：把播放页组合保持到 morph 结束才移除，
            //   vm.playerBackdrop 随之置空 ⇒ 播放条与导航栏落定后才同步褪回。
            // align(BottomCenter)：展开中的裁剪区域贴根底边，而非贴顶部
            AnimatedVisibility(
                visible = showFullPlayer,
                enter = expandVertically(
                    animationSpec = tween(MorphDurationMillis, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Bottom,
                ),
                exit = shrinkVertically(
                    animationSpec = tween(MorphDurationMillis, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Bottom,
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                // AnimatedVisibility 保留内容直到退场完成；此时再释放来源，
                // 不会在飞行途中换 key，且下一次列表入场之前来源行已恢复注册。
                DisposableEffect(Unit) {
                    onDispose { openedFromTrackId = null }
                }
                FullPlayerOverlay(
                    vm = vm,
                    onClose = { showFullPlayer = false },
                    coverSharedKey = playerCoverKey,
                    backdrop = backdrop,
                    rootHeightPx = { rootHeightPx },
                    barHeightPx = { barHeightPx },
                    containerCornerClip = containerCornerClip,
                    sharedTransitionScope = sharedScope,
                    animatedVisibilityScope = this@AnimatedVisibility,
                )
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
    // 专辑|歌单视图切换（状态提升至 MusicScreen，由 TopAppBar 分段控件驱动）
    showPlaylists: Boolean,
    // 曲目行封面槽（由 MusicScreen 创建：固定槽位 + 每行自己的退场作用域）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：传入被点曲目 id，由上层展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
    // ── 歌单操作态（长按进入）：状态由 MusicScreen 持有，穿透转发到歌单视图 ──
    // 选中的歌单 id 集合（空 = 普通模式）
    playlistSelectedIds: Set<String>,
    // 单选时挂行内「编辑/删除」按钮的行 id
    playlistActionRowId: String?,
    // 选中集合回写（点行切换选中/长按改选/删除后清理）
    onPlaylistSelectedIdsChange: (Set<String>) -> Unit,
    // 操作行回写（切换单选操作行/清空）
    onPlaylistActionRowIdChange: (String?) -> Unit,
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
                icon = LucideIcons.Headphones,
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
        // 浏览态：与云端同构（搜索 + 专辑列表/详情 + morph）；
        // 歌单操作态四参原样下穿给本地曲库浏览区
        else -> LocalLibraryContent(
            vm = vm,
            showPlaylists = showPlaylists,
            trackCoverSlot = trackCoverSlot,
            onPlayFromList = onPlayFromList,
            playlistSelectedIds = playlistSelectedIds,
            playlistActionRowId = playlistActionRowId,
            onPlaylistSelectedIdsChange = onPlaylistSelectedIdsChange,
            onPlaylistActionRowIdChange = onPlaylistActionRowIdChange,
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
    // 专辑|歌单视图切换（状态提升至 MusicScreen，由 TopAppBar 分段控件驱动）
    showPlaylists: Boolean,
    // 曲目行封面槽（由 MusicScreen 创建：固定槽位 + 每行自己的退场作用域）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：传入被点曲目 id，由上层展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
    // ── 歌单操作态（长按进入）：状态由 MusicScreen 持有，转发给 PlaylistViewWithFab ──
    // 选中的歌单 id 集合（空 = 普通模式）
    playlistSelectedIds: Set<String>,
    // 单选时挂行内「编辑/删除」按钮的行 id
    playlistActionRowId: String?,
    // 选中集合回写（点行切换选中/长按改选/删除后清理）
    onPlaylistSelectedIdsChange: (Set<String>) -> Unit,
    // 操作行回写（切换单选操作行/清空）
    onPlaylistActionRowIdChange: (String?) -> Unit,
) {
    // 专辑详情打开时拦截系统返回：先回到专辑列表（与云端一致）。
    // && !isFullPlayerOpen：播放页盖在详情之上时，返回必须先收播放页 —— 否则本
    // handler（组合更晚、优先级更高）会在播放页背后悄悄 closeAlbum，屏幕被播放页
    // 盖着看起来"第一次返回失效"（根因见修复记录）
    BackHandler(enabled = vm.currentAlbum != null && !vm.isFullPlayerOpen) { vm.closeAlbum() }

    // 歌单详情打开时拦截系统返回：先回到歌单列表（与专辑详情一致）。同理门控播放页
    BackHandler(enabled = vm.currentPlaylist != null && !vm.isFullPlayerOpen) { vm.closePlaylist() }

    // 首次进入加载当前音源的歌单列表（loadPlaylists 内部有"已有数据不重查"保护）
    LaunchedEffect(Unit) { vm.loadPlaylists() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
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
                    trackCoverSlot = trackCoverSlot,
                    onPlayFromList = onPlayFromList,
                    // 歌单操作态（长按进入）：状态在 MusicScreen 持有，映射到组件形参
                    selectedIds = playlistSelectedIds,
                    actionRowId = playlistActionRowId,
                    onSelectedIdsChange = onPlaylistSelectedIdsChange,
                    onActionRowIdChange = onPlaylistActionRowIdChange,
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
                        trackCoverSlot = trackCoverSlot,
                        onPlayFromList = onPlayFromList,
                    )
                } else {
                    when {
                        // 搜索中：优先展示搜索结果（歌曲在前、专辑在后；与云端同组件）
                        vm.searchQuery.isNotBlank() && vm.searchResult != null -> SearchResultContent(
                            vm = vm,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                            trackCoverSlot = trackCoverSlot,
                            onPlayFromList = onPlayFromList,
                        )
                        // 空态：保留刷新按钮（强制重查 MediaStore）
                        vm.localAlbums.isEmpty() && !vm.isLoadingLocal -> EmptyHint(
                            icon = LucideIcons.Music,
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
                            onPrefetch = { vm.prefetchAlbum(it) },
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
    // 专辑|歌单视图切换（状态提升至 MusicScreen，由 TopAppBar 分段控件驱动）
    showPlaylists: Boolean,
    // 曲目行封面槽（由 MusicScreen 创建：固定槽位 + 每行自己的退场作用域）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：传入被点曲目 id，由上层展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
    // ── 歌单操作态（长按进入）：状态由 MusicScreen 持有，转发给 PlaylistViewWithFab ──
    // 选中的歌单 id 集合（空 = 普通模式）
    playlistSelectedIds: Set<String>,
    // 单选时挂行内「编辑/删除」按钮的行 id
    playlistActionRowId: String?,
    // 选中集合回写（点行切换选中/长按改选/删除后清理）
    onPlaylistSelectedIdsChange: (Set<String>) -> Unit,
    // 操作行回写（切换单选操作行/清空）
    onPlaylistActionRowIdChange: (String?) -> Unit,
) {
    val configured = if (isCustom) vm.isCustomConfigured else vm.isBraingirlConfigured

    if (!configured) {
        // 引导态：配置表单
        ServerConfigForm(vm, isCustom = isCustom)
        return
    }

    // 已配置：首次进入自动加载专辑列表（loadAlbums 内部有"已有数据不重复请求"保护）
    LaunchedEffect(Unit) { vm.loadAlbums() }

    // 专辑详情打开时拦截系统返回：先回到专辑列表。
    // && !isFullPlayerOpen：播放页盖在详情之上时，返回先收播放页（根因同本地音源）
    BackHandler(enabled = vm.currentAlbum != null && !vm.isFullPlayerOpen) { vm.closeAlbum() }

    // 歌单详情打开时拦截系统返回：先回到歌单列表（与本地音源一致）。同理门控播放页
    BackHandler(enabled = vm.currentPlaylist != null && !vm.isFullPlayerOpen) { vm.closePlaylist() }

    // 首次进入加载当前账号的歌单列表（loadPlaylists 内部有"已有数据不重查"保护）
    LaunchedEffect(Unit) { vm.loadPlaylists() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
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
                    trackCoverSlot = trackCoverSlot,
                    onPlayFromList = onPlayFromList,
                    // 歌单操作态（长按进入）：状态在 MusicScreen 持有，映射到组件形参
                    selectedIds = playlistSelectedIds,
                    actionRowId = playlistActionRowId,
                    onSelectedIdsChange = onPlaylistSelectedIdsChange,
                    onActionRowIdChange = onPlaylistActionRowIdChange,
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
                        trackCoverSlot = trackCoverSlot,
                        onPlayFromList = onPlayFromList,
                    )
                } else {
                    when {
                        // 搜索中：优先展示搜索结果（歌曲在前、专辑在后）
                        vm.searchQuery.isNotBlank() && vm.searchResult != null -> SearchResultContent(
                            vm = vm,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = this@AnimatedContent,
                            trackCoverSlot = trackCoverSlot,
                            onPlayFromList = onPlayFromList,
                        )
                        // 专辑列表（加载中显示居中转圈）
                        else -> AlbumListContent(
                            albums = vm.albums,
                            isLoading = vm.isLoadingServer,
                            emptyText = "服务器上还没有专辑",
                            onOpenAlbum = { vm.openAlbum(it) },
                            onPrefetch = { vm.prefetchAlbum(it) },
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
 * 展开式图标选择器的一个选项（图标 + 两字标签，横排格子用）。
 */
private data class SelectorOption(
    val key: String,
    val icon: ImageVector,
    val label: String,
)


/** 控件栏单颗图标的占位宽度 / 选项格定宽 */
private val ToolbarIconWidth = 40.dp
private val ToolbarCellWidth = 96.dp

/**
 * 飞行终点（条坐标下 IconButton 左缘）：格子左排在条首，格 i 左缘 = i×80，
 * 格内图标左缘 = +14（水平内距），图标在 40dp 按钮内居中差 8 → 按钮左缘 = +6。
 */
private fun flightTarget(cellIndex: Int): Dp = ToolbarCellWidth * cellIndex + 6.dp

/**
 * 控件栏区域 —— "揭示窗口 + 角落槽 + 飞行图标"统一模型（搜索区为钉槽变体）：
 *
 * - 区 = [揭示窗口][角落槽]（Row，宽度动画 + 裁切）。收起态窗口=0：条内容
 *   物理不存在，常态只有图标，不可能有条内容残漏；
 * - 条内容左排（选项靠左）、骑窗口左缘从角落滑出/滑回：展开时本区图标锁步
 *   领先自家格子 6dp —— 图标全程钉在格子图标位上"领格子出场"，不横穿文字；
 *   收起时条内容 120ms 淡出，避免文字滑过图标产生残影闪烁；
 * - 角落槽恒 40dp 在区右端：✕ 在此淡入淡出（图标飞走后浮现，最右侧）；
 *   本区图标同一节点贯穿全程，展开时向左飞入条内选中格图标位（与窗口
 *   同参弹簧锁步），颜色 灰↔主色 渐变；iconTargetLeft=null 则钉槽（搜索区）；
 * - 让位（他区展开）：本区整体被推走，图标 150ms 淡出；回位淡入。
 */
@Composable
private fun ToolbarZone(
    expandedWidth: Dp,
    collapsedWidth: Dp,
    expanded: Boolean,
    icon: ImageVector,
    contentDescription: String,
    iconTint: androidx.compose.ui.graphics.Color,
    iconTargetLeft: Dp?,
    visible: Boolean,
    onIconClick: () -> Unit,
    onClose: () -> Unit,
    showClose: Boolean = true,
    // 条内容是否随窗口填充（搜索框=true；选项格=false 用无界测量防钳制换行）
    stripFillsWindow: Boolean = false,
    stripContent: @Composable RowScope.() -> Unit,
) {
    // 区宽、图标偏移共用同一弹簧：同帧起步、同曲线行进（与格子滑出锁步）
    val zoneSpring = spring<Dp>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
    val zoneWidth by animateDpAsState(
        targetValue = if (expanded) expandedWidth else collapsedWidth,
        animationSpec = zoneSpring,
        label = "zoneWidth",
    )
    // 图标自角落槽的左移量：收起=0（钉槽）；展开=飞入条内选中格图标位
    val iconPullLeft by animateDpAsState(
        targetValue = if (expanded && iconTargetLeft != null) {
            (expandedWidth - ToolbarIconWidth) - iconTargetLeft
        } else {
            0.dp
        },
        animationSpec = zoneSpring,
        label = "iconPullLeft",
    )
    // 条内容淡入淡出：收起时快速淡出（文字不滑过图标露残影）
    val stripAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(120),
        label = "stripAlpha",
    )
    // ✕ 淡入淡出：图标飞离角落后浮现（最右侧收起钮）
    val closeAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(120),
        label = "closeAlpha",
    )
    // 让位淡出：他区展开时本区图标随推动淡出，回位淡入
    val iconAlpha by animateFloatAsState(
        targetValue = if (expanded || collapsedWidth > 0.dp) 1f else 0f,
        animationSpec = tween(150),
        label = "iconAlpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(zoneWidth)
            .height(40.dp)
            .clipToBounds(),
    ) {
        // 揭示窗口：收起=0（条不存在）；展开=区宽-角落槽。
        // 条内容左排、骑窗口左缘（起始=角落）滑出：图标在前领路，格子紧随
        Box(
            modifier = Modifier
                .width((zoneWidth - ToolbarIconWidth).coerceAtLeast(0.dp))
                .height(40.dp)
                .clipToBounds(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(
                        if (stripFillsWindow) {
                            Modifier.matchParentSize()
                        } else {
                            // 无界测量：选项格按天然宽度布局，不受窗口动画宽度
                            // 钳制（软约束会被压到 0 → 文字竖排闪烁）；窗口只裁切
                            Modifier.wrapContentSize(Alignment.CenterStart, unbounded = true)
                        },
                    )
                    .graphicsLayer { alpha = stripAlpha },
                content = stripContent,
            )
        }
        // 角落槽：✕（淡入）垫底，恒定图标（飞行）覆盖其上
        Box(modifier = Modifier.requiredSize(ToolbarIconWidth)) {
            if (showClose) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = closeAlpha },
                    contentAlignment = Alignment.Center,
                ) {
                    CloseToolbarButton(onClose = onClose)
                }
            }
            if (visible) {
                IconButton(
                    onClick = onIconClick,
                    modifier = Modifier
                        .offset(x = -iconPullLeft)
                        .requiredSize(ToolbarIconWidth)
                        .graphicsLayer { alpha = iconAlpha },
                ) {
                    Icon(
                        icon,
                        contentDescription = contentDescription,
                        tint = iconTint,
                    )
                }
            }
        }
    }
}

/**
 * 控件栏展开态条尾的 ✕ 收起按钮（紧挨区图标左侧）。
 */
@Composable
private fun CloseToolbarButton(onClose: () -> Unit) {
    IconButton(onClick = onClose) {
        Icon(
            LucideIcons.X,
            contentDescription = "收起",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 展开态的横排选项格 —— 定宽矩形（小圆角 6dp，非胶囊）。未选中格 = 灰图标 +
 * 常规文字；选中格图标位留空（由控件栏的恒定飞行图标落在此处）+ 主色半粗文字。
 * 定宽保证飞行图标的落点坐标可静态计算。
 */
@Composable
private fun SelectorCell(
    option: SelectorOption,
    selected: Boolean,
    // 选中格不渲染自己的图标：飞行图标覆盖在此格的图标位上
    showIcon: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(ToolbarCellWidth)
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        if (showIcon) {
            Icon(
                option.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Spacer(modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            option.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}


/**
 * 歌单多选(>1)时的选择栏 —— 就地替换音源标题行（与任务页 SelectionTopBar 同一语言）。
 * 左 ✕ 清空选中退出操作态，中间显示已选计数，右垃圾桶弹批量删除确认框。
 * 容器透明：音乐页 TopAppBar 本就透明覆在米色列表上，保持无缝。
 *
 * @param selectedCount 当前选中的歌单数量
 * @param onClear 点 ✕ 时触发（清空选中、退出操作态）
 * @param onDelete 点垃圾桶时触发（弹批量删除确认框）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSelectionTopBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(LucideIcons.X, contentDescription = "取消选择")
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
            IconButton(onClick = onDelete) {
                Icon(LucideIcons.Trash2, contentDescription = "删除选中", tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
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
    // 曲目行封面槽（点歌时飞成播放页大封面）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
    // ── 歌单操作态（长按进入）——状态由 MusicScreen 持有 ──
    // 选中集合（空=普通模式）/ 单选操作行 id；批量删除由 MusicScreen 顶部选择栏接管，
    // 本组件只负责读状态渲染 + 回写（点行/长按），单删/重命名对话框仍本地弹
    selectedIds: Set<String>,
    actionRowId: String?,
    onSelectedIdsChange: (Set<String>) -> Unit,
    onActionRowIdChange: (String?) -> Unit,
) {
    // 操作模式判定：选中集合非空（点行改为切换选中；FAB 隐藏）
    val playlistActionMode = selectedIds.isNotEmpty()
    // 新建歌单对话框开关（与 FAB 同生命周期）
    var showCreateDialog by remember { mutableStateOf(false) }
    if (showCreateDialog) {
        // 新建场景的元信息对话框：空初始值、确认即建单（受 VM 客户端限流保护）
        PlaylistMetaDialog(
            title = "新建歌单",
            confirmLabel = "创建",
            initialName = "",
            initialComment = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, comment ->
                showCreateDialog = false
                vm.createPlaylist(name, comment)
            },
        )
    }
    // 行内「编辑」的目标歌单：非空弹重命名对话框（与详情页铅笔同一对话框）
    var renameTarget by remember { mutableStateOf<LibraryPlaylist?>(null) }
    renameTarget?.let { target ->
        PlaylistMetaDialog(
            title = "重命名歌单",
            confirmLabel = "保存",
            initialName = target.name,
            initialComment = target.comment,
            onDismiss = { renameTarget = null },
            onConfirm = { name, comment ->
                renameTarget = null
                vm.renamePlaylist(target.id, name, comment)
            },
        )
    }
    // 行内「删除」的目标歌单：非空弹单删确认框
    var pendingDelete by remember { mutableStateOf<LibraryPlaylist?>(null) }
    pendingDelete?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除歌单") },
            text = {
                Text(
                    "确定删除「${target.name}」？" +
                        if (target.isLocal) "该操作不可恢复。"
                        else "服务器上的歌单及其曲目列表会被删除，该操作不可恢复。"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.deletePlaylist(target.id)
                        pendingDelete = null
                        // 该行退出选中；若操作态因此清空也随之退出（回写上层状态）
                        onSelectedIdsChange(selectedIds - target.id)
                        if (actionRowId == target.id) onActionRowIdChange(null)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
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
                trackCoverSlot = trackCoverSlot,
                onPlayFromList = onPlayFromList,
                // 操作态下穿给列表：行高亮/行内按钮/多选判定均由它驱动
                selectedIds = selectedIds,
                actionRowId = actionRowId,
                // 点行：普通模式开详情；操作模式切换选中（退到单选时按钮随行落位，选空退出）
                onRowClick = { id ->
                    if (playlistActionMode) {
                        // 操作模式：点行切换选中；退回单选时该行成为操作行，选空退出
                        val next = if (id in selectedIds) selectedIds - id else selectedIds + id
                        onSelectedIdsChange(next)
                        onActionRowIdChange(next.singleOrNull())
                    } else {
                        vm.openPlaylist(id)
                    }
                },
                // 长按行：进入/切换操作行（按钮经 sharedElement 从旧行飞到新行）
                onRowLongPress = { id ->
                    // 长按进入/切换操作行（按钮经 sharedElement 从旧行飞到新行）
                    onSelectedIdsChange(setOf(id))
                    onActionRowIdChange(id)
                },
                onRowEdit = { renameTarget = it },
                onRowDelete = { pendingDelete = it },
            )
        }
        // （多选批量删除已上移：由 MusicScreen 顶部 MusicSelectionTopBar 的垃圾桶接管）
        // 新建歌单 FAB：任务页同款（歌单列表态且非操作模式显示，详情态/多选态淡出）
        AnimatedVisibility(
            visible = vm.currentPlaylist == null && !playlistActionMode,
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
                Icon(LucideIcons.Plus, contentDescription = "新建歌单")
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
                LucideIcons.RefreshCw,
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
    // 行进入视口时触发，传入专辑 id —— 由调用方接 VM 的视口预取
    onPrefetch: (String) -> Unit,
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
            // 视口预取：行进入组合即后台拉详情 JSON 与首批封面，点开秒显
            //（与歌单列表同款策略；本地专辑在 VM 内跳过）
            LaunchedEffect(album.id) { onPrefetch(album.id) }
            AlbumRow(
                album = album,
                onClick = { onOpenAlbum(album) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

/** 详情页曲目排序模式：原序 / 名称 / 时长（sortedBy 稳定，同名保持原序） */
private enum class DetailSortMode {
    ORIGINAL,
    TITLE,
    DURATION,
}

/**
 * 构建详情页展示列表：按过滤词（标题/艺术家包含，忽略大小写）过滤当前曲目，
 * 再按排序模式稳定排序；携带原始下标，歌单移除/队列起播均用原始下标定位。
 */
private fun filterSortDetailTracks(
    tracks: List<PlayableTrack>,
    query: String,
    mode: DetailSortMode,
): List<IndexedValue<PlayableTrack>> {
    val q = query.trim()
    val filtered = tracks.withIndex().filter { ( _, track) ->
        q.isEmpty() || track.title.contains(q, ignoreCase = true) ||
            track.artist.contains(q, ignoreCase = true)
    }
    return when (mode) {
        DetailSortMode.ORIGINAL -> filtered
        DetailSortMode.TITLE -> filtered.sortedBy { it.value.title.lowercase() }
        DetailSortMode.DURATION -> filtered.sortedBy { it.value.durationSeconds }
    }
}

/**
 * 详情页工具行 —— 头部与曲目列表之间的过滤/排序控件。
 * 复用控件栏两区飞行模型（右对齐 40dp 恒高）：排序区展开揭示
 * 原序/名称/时长三选项格（图标飞入当前模式格），搜索区为钉槽变体
 * （展开揭示本地过滤输入框）；两区互斥，任一展开时兄弟区让位收起。
 */
@Composable
private fun DetailToolsBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searchOpen: Boolean,
    onSearchToggle: () -> Unit,
    sortMode: DetailSortMode,
    // 选中即收起：调用方直接落定新模式，不再循环切换
    onSortSelect: (DetailSortMode) -> Unit,
) {
    // 排序条展开态（本行内部）：选中选项或 ✕ 后收起
    var sortOpen by remember { mutableStateOf(false) }

    // 选项按 DetailSortMode.entries 顺序（原序/名称/时长），定宽保证飞行落点可静态计算
    val sortOptions = remember {
        listOf(
            SelectorOption("original", LucideIcons.ListMusic, "原序"),
            SelectorOption("title", LucideIcons.ArrowDownAZ, "名称"),
            SelectorOption("duration", LucideIcons.Timer, "时长"),
        )
    }

    val grayColor = MaterialTheme.colorScheme.onSurfaceVariant
    val priColor = MaterialTheme.colorScheme.primary
    // 排序图标颜色：展开 灰→主色（延迟与条揭示同步），收起渐回灰
    val sortIconTint by animateColorAsState(
        targetValue = if (sortOpen) priColor else grayColor,
        animationSpec = tween(180, delayMillis = if (sortOpen) 80 else 0),
        label = "detailSortIconTint",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
    ) {
        val rowWidth = maxWidth
        // 收起态宽度：全收起时本区图标占位 40dp；任一区展开时兄弟区让位为 0
        val sortRest = if (searchOpen) 0.dp else ToolbarIconWidth
        val searchRest = if (sortOpen) 0.dp else ToolbarIconWidth

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // —— 排序区：三模式选项格，图标飞入当前模式格图标位 ——
            ToolbarZone(
                expandedWidth = rowWidth,
                collapsedWidth = sortRest,
                expanded = sortOpen,
                icon = LucideIcons.ArrowDownAZ,
                contentDescription = "排序方式",
                iconTint = sortIconTint,
                iconTargetLeft = flightTarget(sortMode.ordinal),
                visible = !searchOpen,
                onIconClick = { sortOpen = true },
                onClose = { sortOpen = false },
            ) {
                sortOptions.forEachIndexed { index, opt ->
                    val sel = index == sortMode.ordinal
                    SelectorCell(
                        option = opt,
                        selected = sel,
                        showIcon = !sel,
                        onClick = {
                            onSortSelect(DetailSortMode.entries[index])
                            sortOpen = false
                        },
                    )
                }
            }

            // —— 搜索区（钉槽变体）：图标恒钉槽位当开关，展开揭示过滤输入框 ——
            ToolbarZone(
                expandedWidth = rowWidth,
                collapsedWidth = searchRest,
                expanded = searchOpen,
                icon = LucideIcons.Search,
                contentDescription = "过滤当前列表",
                iconTint = grayColor,
                iconTargetLeft = null,
                visible = !sortOpen,
                onIconClick = onSearchToggle,
                onClose = onSearchToggle,
                showClose = false,
                stripFillsWindow = true,
            ) {
                DetailFilterField(
                    query = query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 详情页过滤输入框（搜索区展开态的整行内容主体）—— 仿搜索框矩形样式：
 * Surface + BasicTextField，头部留空图标位（搜索图标钉在区右端角落槽）；
 * 仅读写调用方本地过滤词，不碰 vm.searchQuery。
 */
@Composable
private fun DetailFilterField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier.height(40.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            // 图标位留空：搜索图标钉在区右端角落槽（钉槽变体，不飞入）
            Spacer(modifier = Modifier.width(28.dp))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.weight(1f),
            ) {
                // 空文案时显示占位提示
                if (query.isBlank()) {
                    Text(
                        "过滤当前列表",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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
    // 曲目行封面槽（点歌时飞成播放页大封面）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    if (vm.isLoadingServer && vm.albumSongs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // 详情页局部过滤/排序状态：仅作用于当前列表，切换专辑即重置；
    // 刻意不写 vm.searchQuery（那是顶部全局搜索，语义不同）
    var filterQuery by remember(album.id) { mutableStateOf("") }
    var searchOpen by remember(album.id) { mutableStateOf(false) }
    var sortMode by remember(album.id) { mutableStateOf(DetailSortMode.ORIGINAL) }
    val displaySongs = remember(vm.albumSongs, filterQuery, sortMode) {
        filterSortDetailTracks(vm.albumSongs, filterQuery, sortMode)
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
        // 工具行：过滤输入 + 排序切换（仅作用于下方展示列表）
        item(key = "detail_tools") {
            DetailToolsBar(
                query = filterQuery,
                onQueryChange = { filterQuery = it },
                searchOpen = searchOpen,
                onSearchToggle = { searchOpen = !searchOpen },
                sortMode = sortMode,
                onSortSelect = { sortMode = it },
            )
        }
        // 曲目列表：点击即以专辑全部曲目为队列，从该曲开始播放；
        // 展示列表携带原始下标，起播仍按原始队列位置定位
        if (displaySongs.isEmpty()) {
            item(key = "album_no_match") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "无匹配曲目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(displaySongs, key = { _, entry -> "al-track-${entry.index}-${entry.value.id}" }) { _, entry ->
                TrackRow(
                    track = entry.value,
                    index = entry.index,
                    isCurrent = vm.currentTrack?.id == entry.value.id,
                    coverSlot = trackCoverSlot,
                    onClick = {
                        vm.playQueue(vm.albumSongs, entry.index)
                        // 点歌后展开播放页，该行小封面飞成播放页大封面
                        onPlayFromList(entry.value.id)
                    },
                )
            }
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
    // 曲目行封面槽（点歌时飞成播放页大封面）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
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
                    coverSlot = trackCoverSlot,
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
    // 曲目行封面槽（点歌时飞成播放页大封面）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
    // ── 歌单列表操作态（由 PlaylistViewWithFab 提升持有）──
    // 选中的歌单 id 集合（空 = 普通模式）
    selectedIds: Set<String>,
    // 单选时挂行内「编辑/删除」按钮的行 id
    actionRowId: String?,
    // 点行：普通模式由上层开详情；操作模式由上层切换选中
    onRowClick: (String) -> Unit,
    // 长按行：进入/切换操作行
    onRowLongPress: (String) -> Unit,
    // 行内「编辑」按钮回调
    onRowEdit: (LibraryPlaylist) -> Unit,
    // 行内「删除」按钮回调（上层弹确认框）
    onRowDelete: (LibraryPlaylist) -> Unit,
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
                    trackCoverSlot = trackCoverSlot,
                    onPlayFromList = onPlayFromList,
                )
            } else {
                // 歌单列表分支（长按行进入操作态：行内编辑/删除，多选由顶部选择栏接管；
                // 新建入口由外层 FAB 承担）
                PlaylistListContent(
                    vm = vm,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    selectedIds = selectedIds,
                    actionRowId = actionRowId,
                    onRowClick = onRowClick,
                    onRowLongPress = onRowLongPress,
                    onRowEdit = onRowEdit,
                    onRowDelete = onRowDelete,
                )
            }
        }
    }
}

/**
 * 歌单列表内容 —— 歌单行（名称 + 曲数 + 说明摘要）。
 * 长按行进入操作态：单选时行尾 sharedElement 展开「编辑/删除」；
 * 多选时按钮收回、行描边高亮，批量删除由 MusicScreen 顶部选择栏承担。
 * 加载中/空态与 [AlbumListContent] 同构（新建歌单由外层 FAB 触发）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistListContent(
    vm: MusicViewModel,
    // 歌单行 morph：需这两个作用域生成 sharedElement key
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 选中的歌单 id 集合（空 = 普通模式；非空行高亮、点行切换选中）
    selectedIds: Set<String>,
    // 单选时挂行内编辑/删除按钮的行 id（selectedIds.size==1 时该行显示按钮）
    actionRowId: String?,
    // 点行回调（普通模式开详情 / 操作模式切换选中，由上层分发）
    onRowClick: (String) -> Unit,
    // 长按行回调（进入/切换操作行）
    onRowLongPress: (String) -> Unit,
    // 行内「编辑」按钮回调
    onRowEdit: (LibraryPlaylist) -> Unit,
    // 行内「删除」按钮回调（上层弹确认框）
    onRowDelete: (LibraryPlaylist) -> Unit,
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
            // 视口预取：行进入组合（可见或即将可见）即后台拉取详情与首批封面，
            // 点进来歌曲和封面直接呈现、无加载等待。本地歌单在 VM 内部跳过
            LaunchedEffect(playlist.id) { vm.prefetchPlaylist(playlist.id) }
            PlaylistRow(
                playlist = playlist,
                selected = playlist.id in selectedIds,
                // 行内按钮只在"单选且正是操作行"时展开；多选时全部收回
                showActions = selectedIds.size == 1 && actionRowId == playlist.id,
                onClick = { onRowClick(playlist.id) },
                onLongClick = { onRowLongPress(playlist.id) },
                onEdit = { onRowEdit(playlist) },
                onDelete = { onRowDelete(playlist) },
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
 * 歌单行：与专辑行同构 —— 52dp 封面块（服务器歌单=服务端拼贴封面，
 * 本地/空=与专辑行同款灰底占位）+ 名称/曲数/说明摘要。
 * 交互：普通模式点行开详情；长按进入操作态（行高亮），单选时行尾经
 * sharedElement 展开「编辑/删除」两个按钮（切换操作行时按钮飞行迁移），
 * 多选时按钮收回、由 MusicScreen 顶部选择栏的垃圾桶批量删除接管。本地/服务器共用。
 *
 * @param playlist 行内展示的歌单元数据
 * @param selected 本行是否处于选中态（操作模式下高亮）
 * @param showActions 是否展开行内「编辑/删除」按钮（仅单选且本行为操作行）
 * @param onClick 点击行时触发（普通模式开详情 / 操作模式切换选中，由上层分发）
 * @param onLongClick 长按行时触发（进入/切换操作行）
 * @param onEdit 点击行内「编辑」按钮时触发（上层弹重命名对话框）
 * @param onDelete 点击行内「删除」按钮时触发（上层弹确认框，此处不直接删除）
 * @param sharedTransitionScope 行容器 morph + 行内按钮飞行的作用域
 * @param animatedVisibilityScope 与歌单详情头部配对的过渡作用域
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistRow(
    playlist: LibraryPlaylist,
    selected: Boolean,
    showActions: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    // 行容器 morph：与歌单详情头部配对（key "music_playlist_<id>"）
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 选中态高亮：1.5dp 主色描边（替掉 secondaryContainer 平铺），透明底、克制精致
            .then(
                if (selected) Modifier.border(
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                    RoundedCornerShape(12.dp),
                ) else Modifier,
            )
            // combinedClickable：单击走 onClick（上层按模式分发），
            // 长按进入操作态 —— 选中/展开全部由长按起步，点按绝不误触发
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
        CoverArt(
            // 52dp 小图：请求 150px 变体（字节约 300px 一半以下），缓存 key 自动区分
            coverUrl = coverUrlAtSize(playlist.coverArtUrl, 150),
            modifier = Modifier.size(52.dp),
            cornerRadius = 10.dp,
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
        // 行尾操作区：仅"单选且本行为操作行"时展开。AnimatedVisibility 自持
        // 过渡作用域，内层容器挂 sharedElement（key 全行共用 "playlist_action_bar"）——
        // 长按另一行切换操作行时，旧行退出/新行进场同帧发生，两个小按钮经
        // shared overlay 从旧行飞到新行；进入多选时全部退出（按钮收回），
        // 批量删除交给 MusicScreen 顶部选择栏
        AnimatedVisibility(
            visible = showActions,
            enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                expandHorizontally(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                shrinkHorizontally(tween(180, easing = FastOutSlowInEasing)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(
                        with(sharedTransitionScope) {
                            Modifier.sharedElement(
                                sharedContentState = rememberSharedContentState("playlist_action_bar"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMediumLow)
                                },
                            )
                        },
                    ),
            ) {
                // 编辑：无底 ghost 图标钮，点击由上层弹重命名对话框
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(
                        LucideIcons.Pencil,
                        contentDescription = "编辑歌单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(2.dp))
                // 删除：无底 ghost 图标钮，error 色提示破坏性，点击由上层弹确认框
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        LucideIcons.Trash2,
                        contentDescription = "删除歌单",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 歌单详情内容 —— 头部（封面块 + 名称 + 说明 + 曲目数/总时长 + 重命名按钮）+
 * 曲目列表（可单首移除）。点击曲目以整个歌单为队列起播。
 * 本地/服务器共用：曲目读 [MusicViewModel.playlistTracks]。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistDetailContent(
    vm: MusicViewModel,
    playlist: LibraryPlaylist,
    // 歌单行 morph：头部 sharedElement 与列表行同 key 配对
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    // 曲目行封面槽（点歌时飞成播放页大封面）
    trackCoverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit,
    // 从列表点歌回调：展开播放页并配对封面 morph
    onPlayFromList: (String) -> Unit,
) {
    if (vm.isLoadingPlaylists && vm.playlistTracks.isEmpty() && !playlist.isLocal) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // 详情页局部过滤/排序状态：仅作用于当前列表，切换歌单即重置；
    // 刻意不写 vm.searchQuery（那是顶部全局搜索，语义不同）
    var filterQuery by remember(playlist.id) { mutableStateOf("") }
    var searchOpen by remember(playlist.id) { mutableStateOf(false) }
    var sortMode by remember(playlist.id) { mutableStateOf(DetailSortMode.ORIGINAL) }
    // 重命名对话框开关：头部行尾铅笔按钮打开，确认后调 VM.renamePlaylist
    var showRenameDialog by remember(playlist.id) { mutableStateOf(false) }
    val displayTracks = remember(vm.playlistTracks, filterQuery, sortMode) {
        filterSortDetailTracks(vm.playlistTracks, filterQuery, sortMode)
    }
    // Box 包一层：LazyColumn 之上还要挂重命名对话框（对话框不能进 LazyColumn 作用域）
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        // 歌单头部：容器 morph（与列表歌单行配对）+ 96dp 封面块 + 名称/说明/统计
        item(key = "playlist_header") {
            // 封面固定取第一首曲目（Navidrome 无封面写入 API，不再提供切换交互）
            Row(
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 96dp 封面块：固定显示第一首曲目封面；空歌单用占位图标
                val firstTrack = vm.playlistTracks.firstOrNull()
                if (firstTrack == null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(96.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                LucideIcons.ListMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        CoverArt(
                            coverUrl = firstTrack.coverArtUrl,
                            albumArtUri = firstTrack.albumArtUri,
                            modifier = Modifier.size(96.dp),
                            placeHolderSize = 40.dp,
                            cornerRadius = 16.dp,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    // 副行：策展说明（无则不显示）
                    if (playlist.comment.isNotBlank()) {
                        Text(
                            playlist.comment,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                // 重命名入口：头部行尾铅笔按钮。IconButton 自行消费点击，
                // 不会触发头部容器的 morph 语义；VM 改名成功后会同步刷新当前歌单元信息
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(
                        LucideIcons.Pencil,
                        contentDescription = "重命名歌单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 工具行：过滤输入 + 排序切换（仅作用于下方展示列表）
        item(key = "detail_tools") {
            DetailToolsBar(
                query = filterQuery,
                onQueryChange = { filterQuery = it },
                searchOpen = searchOpen,
                onSearchToggle = { searchOpen = !searchOpen },
                sortMode = sortMode,
                onSortSelect = { sortMode = it },
            )
        }
        // 曲目列表：点击以歌单为队列起播，行尾可单首移除；
        // 展示列表携带原始下标，移除/起播仍按原始下标定位，删的永远是列表里那首
        if (displayTracks.isEmpty() && vm.playlistTracks.isNotEmpty()) {
            item(key = "playlist_no_match") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "无匹配曲目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(displayTracks, key = { _, entry -> "pl-track-${entry.index}-${entry.value.id}" }) { _, entry ->
                TrackRow(
                    track = entry.value,
                    index = entry.index,
                    isCurrent = vm.currentTrack?.id == entry.value.id,
                    coverSlot = trackCoverSlot,
                    onClick = {
                        vm.playQueue(vm.playlistTracks, entry.index)
                        onPlayFromList(entry.value.id)
                    },
                    onRemove = { vm.removePlaylistTrack(playlist.id, entry.index) },
                )
            }
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
        // 重命名对话框：预填现名/现说明，确认后调 VM（云端 updatePlaylist /
        // 本地 rename_local_playlist，均由 VM 分发）
        if (showRenameDialog) {
            PlaylistMetaDialog(
                title = "重命名歌单",
                confirmLabel = "保存",
                initialName = playlist.name,
                initialComment = playlist.comment,
                onDismiss = { showRenameDialog = false },
                onConfirm = { name, comment ->
                    showRenameDialog = false
                    vm.renamePlaylist(playlist.id, name, comment)
                },
            )
        }
    }
}

/**
 * 歌单元信息对话框 —— 新建/重命名共用：名称（必填）+ 策展说明（选填）。
 * 确认后回调 [onConfirm]（name, comment），由调用方分发新建（VM.createPlaylist）
 * 或改名（VM.renamePlaylist）。
 *
 * @param title 对话框标题（"新建歌单" / "重命名歌单"）
 * @param confirmLabel 确认按钮文案（"创建" / "保存"）
 * @param initialName 打开时预填的名称（重命名场景传现名；新建传空串）
 * @param initialComment 打开时预填的说明（重命名场景传现说明；新建传空串）
 * @param onDismiss 取消/点对话框外关闭时触发
 * @param onConfirm 点击确认时触发，回传输入框内的名称与说明
 */
@Composable
private fun PlaylistMetaDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialComment: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, comment: String) -> Unit,
) {
    // remember 以 initial 为 key：同一对话框实例在初始值变化时重置输入
    var nameInput by remember(initialName) { mutableStateOf(initialName) }
    var commentInput by remember(initialComment) { mutableStateOf(initialComment) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
                Text(confirmLabel)
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
                        "预置音源（地址已内置），使用你在网页端注册的账号登录"
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
 * morph 参与方式（与 FullPlayerOverlay 对称）：
 * - 背景层挂容器 sharedElement（key "music_player_bar"），与播放页背景层配对；
 * - 封面挂 sharedElement：从迷你条展开时用自身 key；点歌入场时不参与配对，
 *   随条整体淡出；收起时接手播放页现有的 key，始终作为大封面的落点；
 * - 内容行抬进共享 overlay 渲染（zIndex 高于 morph 矩形），由显式 alpha 淡出
 *   —— 不会被渐变矩形瞬间盖掉（无"啪"地消失）。
 *
 * @param vm 共享的音乐 ViewModel
 * @param track 当前播放曲目
 * @param coverSharedKey 当前封面配对身份：收起时与播放页一致，点歌入场时与来源行分离
 * @param backdrop 本页常驻取色结果（一体色与渐变切片的色源）
 * @param rootHeightPx 根高度（morph 目标高度）：底锚定整屏渐变的总高
 * @param containerCornerClip 容器 morph 顶角圆角 16dp→0 的插值裁剪（两端共用）
 * @param onExpand 点击播放条主体时触发（展开完整播放页）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MiniPlayerBar(
    vm: MusicViewModel,
    track: PlayableTrack,
    coverSharedKey: String,
    backdrop: PlayerBackdropColors,
    rootHeightPx: () -> Float,
    containerCornerClip: SharedTransitionScope.OverlayClip,
    onExpand: () -> Unit,
    // 迷你条 ↔ 播放页 morph：容器 sharedElement + 封面 sharedElement 均需这两个作用域
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    // 条内容（文字/按钮）淡入淡出：overlay 渲染的内容不受父层（AnimatedVisibility
    // 的 fade）变换，故由同一 transition 显式驱动 alpha —— 展开时条内容平滑淡出，
    // 收回时平滑淡入；封面是 sharedElement，配对期间不在此层绘制、不受影响
    val barContentAlpha by animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(120, easing = FastOutSlowInEasing) },
        label = "barContentAlpha",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // 常态底色：与导航栏同一来源同一曲线的一体色（immersiveUnitColor：
    // 沉浸激活瞬时取歌曲色、退出同步淡回主题色），仅非沉浸时实际可见
    val restingColor = immersiveUnitColor(
        active = vm.playerBackdrop != null,
        song = backdrop.bottom,
        fallback = MaterialTheme.colorScheme.surfaceContainer,
    )

    Box {
        Box(
            // 背景 = 容器配对方（条形端）。两种画法：
            // - 沉浸激活（打开/收回 morph 中）：绘制"底锚定整屏渐变"的底部切片
            //   （startY = size.height - rootH 为负值）⇒ morph 中任何中间高度显示的
            //   都是最终整屏渐变的对应切片，与播放页端像素同源、无压扁无跳变；
            //   切片最底像素 = backdrop.bottom = 导航栏色，交界无缝
            // - 常态：一体色纯色 + 顶部 16dp 圆角（morph 中的圆角由 overlay clip 插值）
            modifier = Modifier
                .matchParentSize()
                .then(
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("music_player_bar"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ ->
                                tween(MorphDurationMillis, easing = FastOutSlowInEasing)
                            },
                            clipInOverlayDuringTransition = containerCornerClip,
                        )
                    },
                )
                .drawBehind {
                    if (vm.playerBackdrop != null) {
                        // 底边外延 MorphBottomOverhang：盖住过渡中亚像素底边漏出的
                        // 最后一行（渐变 clamp 平铺，外延部分即 backdrop.bottom）
                        drawRect(
                            Brush.verticalGradient(
                                listOf(backdrop.top, backdrop.bottom),
                                startY = size.height - rootHeightPx(),
                                endY = size.height,
                            ),
                            size = size.copy(height = size.height + MorphBottomOverhang.toPx()),
                        )
                    } else {
                        val radius = MiniBarTopCornerRadius.toPx()
                        val cornerPath = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 0f,
                                    top = 0f,
                                    right = size.width,
                                    bottom = size.height,
                                    topLeftCornerRadius = CornerRadius(radius, radius),
                                    topRightCornerRadius = CornerRadius(radius, radius),
                                )
                            )
                        }
                        drawPath(cornerPath, restingColor)
                    }
                },
        )
        Column(modifier = Modifier.clickable(onClick = onExpand)) {
            // 底边进度：宽度按 position/duration 比例；时长未知时透明占位保持行高稳定
            val progress = if (vm.durationMs > 0) {
                (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f)
            } else null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    // 过渡期间整行抬进共享 overlay（zIndex 高于 morph 矩形与飞行封面）：
                    // 条内容不被矩形瞬间盖掉，随 barContentAlpha 平滑淡出；
                    // 常态（无过渡）原位渲染，视觉不变。alpha 放在 renderIn 内侧，
                    // 确保被录进 overlay 图层
                    .then(
                        with(sharedTransitionScope) {
                            Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                        },
                    )
                    .graphicsLayer { alpha = barContentAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面缩略图（无封面时用音符图标占位）。
                // 入场按来源选择是否参与配对；收起统一接住当前大封面，
                // key 由上层传入，避免把“从哪打开”误当成“收回哪里”。
                CoverArt(
                    // 44dp 迷你条小图：150px 变体
                    coverUrl = coverUrlAtSize(track.coverArtUrl, 150),
                    albumArtUri = track.albumArtUri,
                    modifier = Modifier
                        .size(44.dp)
                        .then(
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState(coverSharedKey),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    // 封面在背景(0)之上、控件(1)之下；不能依赖配对方的注册顺序，
                                    // 收起时先注册的播放栏封面否则会被后注册的退出背景遮住。
                                    zIndexInOverlay = 0.5f,
                                    boundsTransform = { _, _ ->
                                        tween(MorphDurationMillis, easing = FastOutSlowInEasing)
                                    },
                                )
                            },
                        ),
                    cornerRadius = 12.dp,
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
                        LucideIcons.SkipForward,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 自绘底边进度条：直角贴底全宽，不会被顶部圆角裁切。
            // 与条内容同一 barContentAlpha 淡出/淡入：它贴在迷你条最底边（= 导航栏
            // 顶边），过渡中若不淡出，会在 morph 矩形底部留下一条 2dp 的橙/亮色线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .graphicsLayer { alpha = barContentAlpha },
            ) {
                if (progress != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
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
 * morph 参与方式（与 MiniPlayerBar 对称）：背景层挂容器 sharedElement
 * （key "music_player_bar"），与迷你条背景层配对 —— 过渡期间由"进入方"在
 * 共享 overlay 里渲染动画矩形；它画的是底锚定整屏渐变的当前切片，与迷你条端
 * 像素同源 ⇒ 展开与收回严格互为镜像。控件层不参与几何，只做透明度。
 *
 * @param vm 共享的音乐 ViewModel
 * @param onClose 收起播放页（回到音乐列表页）时触发
 * @param coverSharedKey 本次封面飞行的固定身份；入场对接来源，收起对接播放栏，切歌不改身份
 * @param backdrop 本页常驻取色结果（渐变与深色配色的唯一色源，由 MusicScreen 传入）
 * @param rootHeightPx 根高度（morph 目标高度）：底锚定整屏渐变的总高
 * @param barHeightPx 迷你条高度（morph 起点高度）：控件列按矩形实时顶边裁剪时用
 * @param containerCornerClip 容器 morph 顶角圆角 16dp→0 的插值裁剪（两端共用）
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FullPlayerOverlay(
    vm: MusicViewModel,
    onClose: () -> Unit,
    // 当前页封面的配对身份在整次展开/收起期间保持不变，避免切换目的地时跳帧。
    coverSharedKey: String,
    backdrop: PlayerBackdropColors,
    rootHeightPx: () -> Float,
    barHeightPx: () -> Float,
    containerCornerClip: SharedTransitionScope.OverlayClip,
    // 迷你条/曲目行 ↔ 播放页 morph：容器 + 封面 sharedElement 均需这两个作用域
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val track = vm.currentTrack
    // 播放页专用深色配色（嵌套 MaterialTheme 生效），色源同 backdrop，
    // 切歌时颜色 600ms 平滑过渡 —— 汽水音乐式沉浸播放面
    val playerScheme = rememberPlayerColorScheme(backdrop)

    // 沉浸色发布（NionApp 导航栏 + 迷你条一体色的唯一来源）：
    // - LaunchedEffect(backdrop)：取色完成/切歌渐变时实时同步 —— 不能只在打开
    //   瞬间写一次，否则首次点歌（取色尚未完成）会把兜底色永久冻结在导航栏上；
    // - onDispose 置空：本组合由 AnimatedVisibility(showFullPlayer) 承载，收起时
    //   组合一直保持到退场 morph 结束才销毁 ⇒ 收回全程保持歌曲色、不出现
    //   "收起首帧过渡尚未激活就被置空"的整屏米色闪帧；落定后播放条与导航栏
    //   同一帧开始同步褪回主题色
    LaunchedEffect(backdrop) { vm.playerBackdrop = backdrop }
    DisposableEffect(Unit) { onDispose { vm.playerBackdrop = null } }

    // 播放队列默认收起：顶部按钮呼出（常驻列表会挤占沉浸滑切区）
    var queueVisible by remember { mutableStateOf(false) }

    // 控件层入场/退场进度（0=隐藏 1=就位）。几何全部由容器/封面 morph 承担，
    // 控件只做"透明度 + 轻微上浮"，绝不缩放：
    // - 展开：morph 起步不久即开始浮现，跨越整个 morph 并在铺满后再收尾一小段
    //   （旧实现 150ms 延迟 250ms，真机上只占 2~3 帧 —— 控件在铺满那一刻"啪"地
    //   整块冒出来，这是入场突兀的主因）；
    // - 收起：起步即快速淡出（矩形还盖着大半屏时控件就已退场）
    // 用 State 对象而非 by 委托：只在 graphicsLayer 绘制阶段读取，动画每帧只
    // 触发图层更新，不会让整页播放页（含 VerticalPager）每帧重组
    val controlsProgress = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = ControlsEnterDurationMillis,
                    delayMillis = ControlsEnterDelayMillis,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(120, easing = FastOutSlowInEasing)
            }
        },
        label = "playerControlsProgress",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // morph 几何进度镜像（0=条形 1=铺满）：与容器 boundsTransform 同一 transition、
    // 同一 tween 规格 ⇒ 与背景矩形逐帧同步（不是第二套几何，只是读出同一时钟）。
    // 用途：① 控件列按矩形当前顶边裁剪 —— 控件抬进 overlay 后不再画到矩形外的
    // 米色列表上；② 矩形顶边触到状态栏下沿的那一帧，状态栏条接上歌曲色
    val morphProgress = animatedVisibilityScope.transition.animateFloat(
        transitionSpec = { tween(MorphDurationMillis, easing = FastOutSlowInEasing) },
        label = "playerMorphProgress",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // morph 时钟发布给 NionApp 的状态栏条：NionApp 在"绘制阶段"直接读这个 State，
    // 与容器矩形同一帧判定"是否铺到顶"。不能走 LaunchedEffect → VM → 重组 的链路：
    // 那条链路要晚 2~3 帧，收起起步时矩形顶边已下移几个像素、状态栏条却还是歌曲色，
    // 两者之间露出一条几像素的米色缝（逐帧实测）
    DisposableEffect(morphProgress) {
        vm.playerMorphProgress = morphProgress
        onDispose { vm.playerMorphProgress = null }
    }

    // 状态栏图标深浅用的"已铺满"标志（只在翻转时重组；图标切换本身由系统栏
    // 绘制，晚一两帧肉眼不可辨，所以走普通状态即可）
    val morphReachedTop = remember { derivedStateOf { morphProgress.value >= 1f } }
    val settledOpen = morphReachedTop.value
    LaunchedEffect(settledOpen) { vm.isFullPlayerSettled = settledOpen }
    DisposableEffect(Unit) { onDispose { vm.isFullPlayerSettled = false } }

    // 分组错落（自下而上，与矩形自底边向上生长同向）：进度条与播放按钮 → 曲目信息
    // → 顶部按钮行。矩形扫过哪一组、哪一组就开始浮现，顶部按钮行在铺满时最后落定。
    // 收起时三组同步（progress 整体快速归零）。均为 lambda：延迟到绘制阶段读取
    val transportProgress = { staggered(controlsProgress.value, start = 0f, end = 0.55f) }
    val infoProgress = { staggered(controlsProgress.value, start = 0.15f, end = 0.75f) }
    val topRowProgress = { staggered(controlsProgress.value, start = 0.45f, end = 1f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            // 背景 = 容器配对方（全屏端）：与迷你条背景层同 key 配对。
            // 始终画"底锚定整屏渐变"的当前切片（常态下 size == rootH，即整屏渐变；
            // morph 中被重测为动画中的中间尺寸，切片随高度连续取值）——
            // 与迷你条端同一公式，展开/收回像素镜像；底边 = 根底边 = 导航栏顶边，
            // morph 全程底边零位移
            modifier = Modifier
                .fillMaxSize()
                .then(
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("music_player_bar"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ ->
                                tween(MorphDurationMillis, easing = FastOutSlowInEasing)
                            },
                            clipInOverlayDuringTransition = containerCornerClip,
                        )
                    },
                )
                .drawBehind {
                    // 底边外延 MorphBottomOverhang（原因见常量注释）：morph 中矩形底边
                    // 落在亚像素位置时，最后一行由外延部分盖住，不再透出下层亮色
                    drawRect(
                        Brush.verticalGradient(
                            listOf(backdrop.top, backdrop.bottom),
                            startY = size.height - rootHeightPx(),
                            endY = size.height,
                        ),
                        size = size.copy(height = size.height + MorphBottomOverhang.toPx()),
                    )
                },
        )
        MaterialTheme(colorScheme = playerScheme) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 控件列在过渡期间抬进共享 overlay（zIndex 高于 morph 背景矩形与飞行
                    // 封面）：背景 sharedElement 过渡中在 overlay 里绘制、盖在普通内容之上，
                    // 不抬上去的话控件的淡入/上浮全程被渐变矩形挡住，直到 morph 结束背景
                    // 落回原层，控件才"啪"地整块露出 —— 这正是入场突兀的根因。
                    // 非过渡时原位渲染，常态零开销
                    .then(
                        with(sharedTransitionScope) {
                            Modifier.renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f)
                        },
                    )
                    // 按矩形当前顶边裁剪（矩形底锚定，顶边 = 高度 × (1 - morph 进度) 处的
                    // 条形→全屏插值）：控件抬进 overlay 后只在渐变矩形内部可见，绝不画到
                    // 上方米色列表上；铺满后零裁剪
                    .drawWithContent {
                        val p = morphProgress.value
                        if (p >= 1f) {
                            drawContent()
                        } else {
                            val barH = barHeightPx()
                            val top = (size.height - barH) * (1f - p)
                            clipRect(top = top) { this@drawWithContent.drawContent() }
                        }
                    }
                    // 控件入场不再整列统一淡入：顶部按钮行/曲目信息/进度与按钮三组各自
                    // 经 controlsEnter 错落浮现（见上方 topRow/info/transportProgress）。
                    // 状态栏 padding：真 morph 后本页根（NionApp 内容区）已上延至屏幕顶、含
                    // 状态栏区域 —— 渐变背景要铺满状态栏，但控件（收起钮等）必须避让状态栏，
                    // 故在此补状态栏内边距。底部无需补：根底边 = 底部导航栏顶边，本就在导航栏之上
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp),
            ) {
                    // ===== 顶部收起按钮 =====
                    // 入场第一组：最先浮现（封面还在飞时它已半透明出现，提示可收起）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .controlsEnter(topRowProgress),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(
                                LucideIcons.ChevronDown,
                                contentDescription = "收起播放页",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        // 队列入口：点开底部队列面板
                        IconButton(onClick = { queueVisible = !queueVisible }) {
                            Icon(
                                LucideIcons.ListMusic,
                                contentDescription = "播放队列",
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
                    } else {
                        // ===== 沉浸式上下滑切歌（原生 VerticalPager） =====
                        // key 绑定队列实例：换专辑/歌单重新开播时重建 pager 并
                        // 落位新 currentIndex；原队列内切歌不重建（保住滚动状态）
                        key(vm.queue) {
                            val pagerState = rememberPagerState(
                                initialPage = vm.currentIndex.coerceAtLeast(0),
                            ) { vm.queue.size }

                            // 用户滑动落定 → 播放落定页曲目
                            LaunchedEffect(pagerState) {
                                snapshotFlow { pagerState.settledPage }.collect { page ->
                                    if (page != vm.currentIndex) vm.playQueueIndex(page)
                                }
                            }
                            // 外部切歌（迷你条/队列行/按钮）→ 滚到对应页。
                            // 仅在 pager 完全落定后接管，避免拽回用户拖拽
                            LaunchedEffect(pagerState) {
                                snapshotFlow { vm.currentIndex }.collect { idx ->
                                    if (idx >= 0 &&
                                        idx != pagerState.settledPage &&
                                        !pagerState.isScrollInProgress
                                    ) {
                                        pagerState.animateScrollToPage(idx)
                                    }
                                }
                            }

                            // 一次 fling 最多翻一页：使劲甩也只前进/后退一首，
                            // 不会从第一首直接飞到最后
                            val flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
                                pagerState,
                                androidx.compose.foundation.pager.PagerSnapDistance.atMost(1),
                            )


                            VerticalPager(
                                state = pagerState,
                                flingBehavior = flingBehavior,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) { page ->
                                val pageTrack = vm.queue.getOrNull(page)
                                    ?: return@VerticalPager
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    // 仅当前页参与封面 morph：入场配对来源，收起配对播放栏。
                                    // 切歌只更换封面内容，不把 key 改成新歌的列表行；
                                    // 相邻预加载页不注册共享元素，避免出现额外配对方。
                                    // 大封面即 morph 目标本身：不再包外层底座，
                                    // 圆角由 CoverArt 的 cornerRadius 统一裁剪
                                    CoverArt(
                                        // 280dp 大封面：600px 变体（300px 拉伸发软）；
                                        // 当前/下一首的 600 变体随播放预热，展开即命中
                                        coverUrl = coverUrlAtSize(pageTrack.coverArtUrl, 600),
                                        albumArtUri = pageTrack.albumArtUri,
                                        modifier = Modifier
                                            .size(280.dp)
                                            .then(
                                                if (page == pagerState.currentPage) with(sharedTransitionScope) {
                                                    Modifier.sharedElement(
                                                        sharedContentState = rememberSharedContentState(coverSharedKey),
                                                        animatedVisibilityScope = animatedVisibilityScope,
                                                        // 封面在背景(0)之上、控件(1)之下；不能依赖配对方的注册顺序，
                                                        // 收起时先注册的播放栏封面否则会被后注册的退出背景遮住。
                                                        zIndexInOverlay = 0.5f,
                                                        boundsTransform = { _, _ ->
                                                            tween(MorphDurationMillis, easing = FastOutSlowInEasing)
                                                        },
                                                    )
                                                } else Modifier,
                                            ),
                                        placeHolderSize = 96.dp,
                                        cornerRadius = 12.dp,
                                    )

                                    // ===== 曲名 / 艺术家 / 专辑（跟随页面曲目） =====
                                    // 入场第二组：封面落定前后浮现在封面下方
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 16.dp)
                                            .controlsEnter(infoProgress),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            pageTrack.title,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            // 显式取播放页深色配色的 onSurface：此处不在 Surface 内，
                                            // 不设颜色会继承外层浅色主题的深色 LocalContentColor，
                                            // 曲名在深色渐变上几乎不可见（艺术家行显式设色所以正常）
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            pageTrack.artist,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (pageTrack.album.isNotBlank()) {
                                            Text(
                                                pageTrack.album,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // ===== 进度条 =====
                        // 拖动期间非空；null=跟随播放进度。key 绑定曲目 id，切歌重置拖动态
                        var dragPosition by remember(track.id) { mutableStateOf<Float?>(null) }
                        // 进度平滑：轮询每 500ms 一个点，两点之间线性补间 —— 播放中
                        // 滑块与时间连续爬行而非半秒跳一格。补间只驱动本行重组
                        //（滑块+时间文本），整页零开销。回退或大幅前跳（seek/切歌）
                        // 直接落位不补间，避免滑块"扫"过整条轨道
                        val progressAnim = remember(track.id) {
                            androidx.compose.animation.core.Animatable(vm.positionMs.toFloat())
                        }
                        LaunchedEffect(vm.positionMs) {
                            val target = vm.positionMs.toFloat()
                            val delta = target - progressAnim.value
                            if (delta < 0f || delta > 2500f) {
                                progressAnim.snapTo(target)
                            } else {
                                progressAnim.animateTo(
                                    target,
                                    tween(500, easing = androidx.compose.animation.core.LinearEasing),
                                )
                            }
                        }
                        // 拖动中显示 dragPosition；总时长未知时滑块不可交互（duration=0）
                        val sliderValue = dragPosition ?: progressAnim.value
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
                                .padding(horizontal = 8.dp)
                                // 入场第三组（进度条 + 时间 + 播放按钮）：最后浮现
                                .controlsEnter(transportProgress),
                            enabled = vm.durationMs > 0,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .controlsEnter(transportProgress),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatTrackDuration((dragPosition?.toLong() ?: progressAnim.value.toLong()) / 1000),
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
                                .padding(vertical = 12.dp)
                                .controlsEnter(transportProgress),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { vm.skipToPrevious() }) {
                                Icon(
                                    LucideIcons.SkipBack,
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
                                    LucideIcons.SkipForward,
                                    contentDescription = "下一首",
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }

                    }
                }
                    // ===== 播放队列面板（入口：右上角按钮；底部滑入） =====
                    AnimatedVisibility(
                        visible = queueVisible,
                        enter = fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 3 },
                        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.62f),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 同控件层：根已排除系统栏，不再叠 navigationBarsPadding
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            LucideIcons.Headphones,
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
                                        Spacer(modifier = Modifier.weight(1f))
                                        IconButton(onClick = { queueVisible = false }) {
                                            Icon(
                                                LucideIcons.X,
                                                contentDescription = "关闭队列",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
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
            if (playing) LucideIcons.Pause else LucideIcons.Play,
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
 * 封面装在 coverSlot 里（固定槽位 + 每行自己的退场作用域），从列表点歌时
 * 行内小封面飞成播放页大封面，morph 期间槽位不塌、行不回流。
 *
 * @param track 曲目数据
 * @param index 序号（仅用于点击回调时的队列下标）
 * @param isCurrent 是否为当前播放曲目（高亮标题标识当前曲）
 * @param coverSlot 行内封面槽（传入曲目 id 与封面内容，由 MusicScreen 注入 morph 结构）
 * @param onClick 点击整行时触发（开始播放该曲）
 */
@Composable
private fun TrackRow(
    track: PlayableTrack,
    index: Int,
    isCurrent: Boolean,
    coverSlot: @Composable (trackId: String, coverContent: @Composable () -> Unit) -> Unit = { _, content -> content() },
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
        // 均无时显示专辑图标占位（当前曲仅由标题高亮标识，封面不加角标）。
        // 经 coverSlot 包裹：槽位固定 40dp，morph 时封面“被借走”飞行、行不回流
        coverSlot(track.id) {
            CoverArt(
                // 40dp 行小图：150px 变体
                coverUrl = coverUrlAtSize(track.coverArtUrl, 150),
                albumArtUri = track.albumArtUri,
                modifier = Modifier.size(40.dp),
                cornerRadius = 12.dp,
            )
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
        // 单首移除按钮（仅歌单详情传入 onRemove 时显示）
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    LucideIcons.X,
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
                    LucideIcons.Headphones,
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
            // 52dp 专辑行小图：150px 变体
            coverUrl = coverUrlAtSize(album.coverArtUrl, 150),
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
                Icon(LucideIcons.RefreshCw, contentDescription = actionLabel)
            }
        }
    }
}

/* ============================================================
 * 封面加载
 * ============================================================ */

/**
 * 封面缓存的稳定 key —— 同一封面跨启动保持不变，三级缓存（内存/磁盘/取色）共用：
 * - 本地封面：`local:<uri>`（content URI 本身稳定）
 * - 服务器封面：`web:<服务器>|<id>|<size>`。不能直接拿整条 URL 当 key ——
 *   Subsonic 认证的 t(令牌)/s(盐) 随 SubsonicClient 实例随机生成，URL 每次都
 *   不同，拿 URL 当 key 缓存永远命不中（这正是"封面好像从不缓存"的根因）
 *   · 服务器段 = host[:port] + path（如 `echonion.org/music/rest/getCoverArt.view`）：
 *     不同服务器的封面 id 可能撞串（gonic/Airsonic 一类服务端用自增整数 id），
 *     与 JSON 缓存的 cacheNs() 同理按服务器隔离；不含用户名 —— 同一服务器上
 *     不同账号看到的同一封面本就是同一张图
 *   · id = 服务端下发的 coverArt 令牌。Navidrome 的令牌自带内容哈希
 *     （`al-<id>_<hash>`），图一变令牌就变，所以磁盘缓存可以永不过期而不陈旧 ——
 *     前提是客户端原样使用服务端令牌、不自己拼（见 SubsonicClient.parsePlaylist）
 * - 参数提取失败（非本客户端拼出的 URL）：兜底原始 URL（只进内存缓存）
 */
internal fun stableCoverKey(coverUrl: String?, albumArtUri: String?): String? {
    if (albumArtUri != null) return "local:$albumArtUri"
    if (coverUrl == null) return null
    val uri = runCatching { Uri.parse(coverUrl) }.getOrNull()
    val id = uri?.let { runCatching { it.getQueryParameter("id") }.getOrNull() }
    if (uri == null || id == null) return "url:$coverUrl"
    val size = runCatching { uri.getQueryParameter("size") }.getOrNull() ?: "300"
    // 端口仅在显式指定时拼入（默认端口 Uri.port = -1）
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "web:${uri.host}$port${uri.path}|$id|$size"
}

/**
 * 封面 URL 的 `size` 参数（目标边长 px），解码时据此降采样；缺省 300，
 * 与 SubsonicClient.coverArtUrl 的默认值一致。0 表示原图（不降采样）
 */
private fun coverTargetPx(coverUrl: String): Int =
    runCatching { Uri.parse(coverUrl).getQueryParameter("size")?.toIntOrNull() }.getOrNull() ?: 300

/**
 * 把服务器封面 URL 的 `size` 查询参数改写成指定值 —— 按显示位要尺寸：
 * 列表小行 150px（字节约 300px 的 1/2 以下）、播放页大封面 600px（更清晰）。
 * 服务器按 size 现缩再传输，等效"传输即压缩"（JPEG 本身已压缩，gzip 无益）。
 * 仅改写 http(s) URL；其余（null / 本地 URI）原样返回。
 * 缓存 key 天然含 size（见 [stableCoverKey]），各尺寸变体在三级缓存互不串扰。
 */
internal fun coverUrlAtSize(coverUrl: String?, size: Int): String? {
    if (coverUrl == null || !coverUrl.startsWith("http")) return coverUrl
    return runCatching {
        val uri = Uri.parse(coverUrl)
        val builder = uri.buildUpon().clearQuery()
        // 保存认证（u/t/s）等全部查询参数，仅把 size 换成目标值；无 size 则补上
        var hasSize = false
        uri.queryParameterNames.forEach { name ->
            uri.getQueryParameters(name).forEach { value ->
                if (name == "size") {
                    if (!hasSize) { // size 可能重复出现，只保留一个目标值
                        builder.appendQueryParameter(name, size.toString())
                        hasSize = true
                    }
                } else {
                    builder.appendQueryParameter(name, value)
                }
            }
        }
        if (!hasSize) builder.appendQueryParameter("size", size.toString())
        builder.build().toString()
    }.getOrDefault(coverUrl)
}

/**
 * 进程内封面缓存 —— key → Bitmap 的 LruCache，按内存字节限容（maxMemory/8，
 * 典型设备 16~48MB）。旧实现按"张数"限 24 张：一屏专辑行 + 详情大封面就互相
 * 挤掉，滚动回看必然重新加载；按字节限容后常用列表可整体驻留内存。
 */
internal object CoverArtCache {
    // maxSize 单位与 sizeOf 一致（KB）：maxMemory/8，下限 12MB 防小内存设备过度缩水
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8 / 1024).toInt().coerceAtLeast(12 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 查缓存；未命中返回 null */
    fun get(url: String): Bitmap? = cache.get(url)

    /** 写入缓存 */
    fun put(url: String, bitmap: Bitmap) = cache.put(url, bitmap)
}

/**
 * 封面磁盘缓存 —— 仅服务 web 封面（`web:` key）：按稳定 key 的 md5 落文件
 * （cacheDir/covers-v2/），跨启动有效，二次进入列表零网络请求。本地封面不走
 * 磁盘（本身就是本地数据，再存一份只浪费空间，靠扩容后的内存缓存即可）。
 *
 * - 存服务器返回的**原始字节**（JPEG/PNG/WebP 原样），读时再按目标尺寸解码：
 *   不做有损重压缩（旧实现 JPEG 88 二次压缩既掉画质，又会把 PNG 透明底压成黑底）
 * - **原子写入**：先写同目录临时文件再 rename 覆盖。直接写目标文件的话，进程被杀/
 *   磁盘写满会留下半截文件 —— BitmapFactory 对截断的 JPEG 照样返回"下半截灰"的
 *   位图而不是 null，坏图从此被当成正常缓存；并发读也可能读到写了一半的文件
 * - **LRU**：读命中刷新 lastModified，超限按 lastModified 从旧到新淘汰
 *   （旧实现读不刷新，实际是按写入时间的 FIFO，常听的封面照样被挤掉）
 * - 总量在内存里增量记账（首次访问扫描一次目录），超限才整目录扫描并一次裁到 90%：
 *   旧实现每写一张都 listFiles + 逐个 stat，缓存上万张后每次写入都是一次全目录扫描
 *
 * 总量上限 256MB（约八千至一万余张 300px JPEG，重度曲库也够用）；目录用 cacheDir
 * （系统磁盘紧张时可整体回收，无需权限，也不进应用备份）。
 */
private object CoverDiskCache {
    private const val MAX_BYTES = 256L * 1024 * 1024
    // 超限后一次裁到上限的 90%：留出余量，避免此后每写一张都触发整目录扫描
    private const val TRIM_TO_BYTES = MAX_BYTES / 10 * 9
    // v2：key 加了服务器命名空间、文件改存原始字节，与旧目录互不兼容，换目录整体切换
    private const val DIR_NAME = "covers-v2"
    // 旧版目录：旧 key 已全部失效，且混有被永久缓存的服务器占位图，初始化时后台删除
    private const val LEGACY_DIR_NAME = "covers"
    private const val TMP_SUFFIX = ".tmp"

    /** 目录初始化、总量记账、淘汰共用一把锁（只在写入/删除时持有，读不加锁） */
    private val lock = Any()

    /** 缓存目录：首次使用时创建并统计总量，进程内复用 */
    @Volatile
    private var dir: File? = null

    /** 目录内缓存文件总字节数：初始化扫描一次，之后随写入/删除/淘汰增量维护（受 [lock] 保护） */
    private var totalBytes = 0L

    private fun dirOf(context: Context): File = dir ?: synchronized(lock) {
        dir ?: run {
            val root = context.applicationContext.cacheDir
            val legacy = File(root, LEGACY_DIR_NAME)
            if (legacy.exists()) {
                // 后台线程删：旧目录可能有上万个文件，不能卡住首张封面的加载
                thread(isDaemon = true, name = "cover-cache-legacy-purge") { legacy.deleteRecursively() }
            }
            val created = File(root, DIR_NAME).apply { mkdirs() }
            var total = 0L
            created.listFiles()?.forEach { f ->
                // 上次进程在写入中途被杀留下的临时文件：直接清掉，不计入总量
                if (f.name.endsWith(TMP_SUFFIX)) f.delete() else total += f.length()
            }
            totalBytes = total
            created.also { dir = it }
        }
    }

    /** 稳定 key → 文件名：md5 hex，避免 key 里的非法字符进入文件系统 */
    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".img"
    }

    /**
     * 读磁盘缓存并按 [targetPx] 解码；文件不存在返回 null，解不出图（损坏）时删除并返回 null。
     * 命中即刷新 lastModified，让淘汰保活常用封面
     */
    fun read(context: Context, key: String, targetPx: Int): Bitmap? {
        val file = File(dirOf(context), fileName(key))
        if (!file.isFile) return null
        val bitmap = runCatching { file.readBytes() }.getOrNull()?.let { decodeCoverBytes(it, targetPx) }
        if (bitmap == null) {
            remove(file)
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    /** 原子写入原始图片字节（临时文件 → rename 覆盖）+ 超限淘汰；IO 失败静默放弃（只是少一份缓存） */
    fun write(context: Context, key: String, bytes: ByteArray) {
        runCatching {
            val d = dirOf(context)
            val target = File(d, fileName(key))
            val tmp = File.createTempFile("cover-", TMP_SUFFIX, d)
            try {
                tmp.writeBytes(bytes)
                synchronized(lock) {
                    // 覆盖已有文件时扣掉旧长度，记账与磁盘保持一致
                    val replaced = if (target.isFile) target.length() else 0L
                    if (tmp.renameTo(target)) {
                        totalBytes += bytes.size - replaced
                        if (totalBytes > MAX_BYTES) trimLocked(d)
                    }
                }
            } finally {
                // rename 成功后临时文件已不存在（delete 无副作用）；失败时清掉残留
                tmp.delete()
            }
        }
    }

    /** 删除单个缓存文件并同步扣减记账 */
    private fun remove(file: File) {
        synchronized(lock) {
            val len = file.length()
            if (file.delete()) totalBytes -= len
        }
    }

    /**
     * 淘汰（须持有 [lock]）：按 lastModified 从旧到新删除，直到降到 [TRIM_TO_BYTES]；
     * 顺带用实际扫描结果校正记账，抵消系统回收 cacheDir 等外部变动造成的偏差
     */
    private fun trimLocked(d: File) {
        // (文件, 长度, 修改时间) 一次取齐：排序比较器里反复 stat 会放大成 O(n log n) 次系统调用
        val entries = d.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
            ?.map { Triple(it, it.length(), it.lastModified()) }
            ?: return
        var total = entries.sumOf { it.second }
        for ((file, len, _) in entries.sortedBy { it.third }) {
            if (total <= TRIM_TO_BYTES) break
            if (file.delete()) total -= len
        }
        totalBytes = total
    }
}

/**
 * 在途加载表：同一稳定 key 同时只做一次"磁盘 → 网络/本地"加载，后到者阻塞等待领头者的结果。
 * 同一封面常被多处同时请求（列表行 + 歌单/专辑预取 + 播放页取色 + 大封面预热），
 * 不去重就会并发下载同一张图、并发写同一个缓存文件
 */
private val coverLoadsInFlight = ConcurrentHashMap<String, CompletableFuture<Bitmap?>>()

/**
 * 封面统一取载漏斗（IO 线程调用，阻塞）：内存 → 磁盘（仅 web）→ 原始加载，逐级回填。
 * CoverArt 列表/大封面、PlayerBackdrop 取色、ViewModel 预热共用此入口，保证三级缓存 key 一致。
 *
 * @return 解码成功返回 Bitmap（已回填沿途各级缓存），失败返回 null
 */
internal fun loadCoverBitmapCached(context: Context, coverUrl: String?, albumArtUri: String?): Bitmap? {
    val key = stableCoverKey(coverUrl, albumArtUri) ?: return null
    // ① 内存：同步命中直接返回，不进在途表
    CoverArtCache.get(key)?.let { return it }
    // 抢领头：已有同 key 的在途加载 → 等它的结果（领头者在 finally 里必定 complete，不会永久阻塞）
    val mine = CompletableFuture<Bitmap?>()
    coverLoadsInFlight.putIfAbsent(key, mine)?.let { leader ->
        return runCatching { leader.get() }.getOrNull()
    }
    var result: Bitmap? = null
    try {
        // 领头者再查一次内存：上一个领头者可能恰好在"查内存"与"抢领头"之间完成并出表
        result = CoverArtCache.get(key) ?: loadCoverUncached(context, key, coverUrl, albumArtUri)
    } finally {
        // 先交付再出表：出表前到达的等待者直接拿到结果，不会再发起一次重复加载
        mine.complete(result)
        coverLoadsInFlight.remove(key, mine)
    }
    return result
}

/**
 * 漏斗的未命中路径（仅由 [loadCoverBitmapCached] 的领头者调用），成功后回填内存缓存：
 * - 本地封面：loadThumbnail（不落盘）
 * - web 封面：② 磁盘命中直接解码；否则 ③ 下载原始字节 → 按 size 解码 → 服务器允许时原样落盘
 * - 非本客户端 URL（`url:` key）：下载解码，只进内存
 */
private fun loadCoverUncached(
    context: Context,
    key: String,
    coverUrl: String?,
    albumArtUri: String?,
): Bitmap? {
    val bitmap = when {
        albumArtUri != null -> loadLocalCoverThumbnail(context, albumArtUri)
        coverUrl == null -> null
        else -> {
            val targetPx = coverTargetPx(coverUrl)
            val persistable = key.startsWith("web:")
            (if (persistable) CoverDiskCache.read(context, key, targetPx) else null)
                ?: CoverArtLoader.fetch(coverUrl)?.let { fetched ->
                    // 先解码成功再落盘：Subsonic 出错时常以 HTTP 200 回 JSON 错误体，不能当图存
                    decodeCoverBytes(fetched.bytes, targetPx)?.also {
                        // 服务器声明 no-store 的响应（Navidrome 的占位图：封面缺失/尚未解析出来时
                        // 的临时替身）只进内存不落盘 —— 落盘会把占位图永久钉在这个 key 上，
                        // 服务端后来补上封面也再看不到
                        if (persistable && fetched.storable) CoverDiskCache.write(context, key, fetched.bytes)
                    }
                }
        }
    } ?: return null
    CoverArtCache.put(key, bitmap)
    return bitmap
}

/**
 * 按目标边长解码封面字节：先只读尺寸，再取 2 的幂 inSampleSize，保证解码后短边仍 ≥ [targetPx]。
 * 服务器按 size 缩好图时 inSampleSize=1 原样解码；防的是服务器不缩图的情况 ——
 * Navidrome 的占位图恒为 1024px 原图（不按 size 缩），全尺寸解码一张就是 4MB，
 * 几张就能把按字节限容的内存缓存挤空。[targetPx] ≤ 0 表示要原图。
 *
 * @return 解不出图（非图片字节/数据损坏）返回 null
 */
private fun decodeCoverBytes(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    if (targetPx > 0) {
        val shortSide = minOf(bounds.outWidth, bounds.outHeight)
        while (shortSide / (sample * 2) >= targetPx) sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
}

/**
 * 封面 HTTP 加载器 —— OkHttp 同步 GET，只负责取回原始字节与缓存许可；
 * 解码与是否落盘由漏斗决定。专为封面 URL 设计（不含 Subsonic 业务解析逻辑）。
 */
private object CoverArtLoader {
    // 与 Subsonic API 共享同一 OkHttp 单例：封面与接口同主机，keep-alive 连接复用
    private val client = SubsonicClient.sharedHttpClient

    /**
     * 一次下载的结果。
     *
     * @property bytes 响应体原始字节（JPEG/PNG/WebP），原样落盘
     * @property storable 服务器是否允许缓存：响应头带 `Cache-Control: no-store` 时为 false
     */
    class Fetched(val bytes: ByteArray, val storable: Boolean)

    /** 下载封面；HTTP 非 2xx / 网络失败返回 null */
    fun fetch(url: String): Fetched? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            Fetched(bytes, storable = !response.cacheControl.noStore)
        }
    }.getOrNull()
}


/**
 * 封面图 Composable —— 同步取内存缓存 → 未命中异步走统一漏斗 → 占位图标。
 *
 * @param coverUrl 封面完整 URL（含认证参数）；空/null 显示占位图标
 * @param modifier 尺寸约束
 * @param placeHolderSize 占位图标大小（大封面用大图标）
 * @param cornerRadius 圆角半径（默认 8dp）
 * @param albumArtUri 本地专辑封面 URI；非空时优先于 coverUrl
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
    // 缓存 key：与取色/磁盘缓存共用的稳定 key（剥掉 URL 里随实例随机的认证参数）
    val cacheKey = stableCoverKey(coverUrl, albumArtUri)
    // 当前封面位图。remember(cacheKey)：同一个 CoverArt 实例换了封面来源时（迷你条切歌、
    // 无 key 的搜索结果行被复用、歌单头部首曲变化），当帧丢掉上一张图、改取新 key 的内存缓存。
    // 不能用 produceState —— 它内部的 remember 不带 key，换 key 后旧位图原样保留，再叠加
    // "为空才加载"的守卫，新封面根本不会去加载：即"切歌后迷你条还是上一首封面/搜索结果封面错位"
    var bitmap by remember(cacheKey) { mutableStateOf(cacheKey?.let { CoverArtCache.get(it) }) }
    // 淡入进度（同样按 key 重建）：内存同步命中 → 直接 1，无多余动画；
    // 需要异步加载 → 从 0 起，图到手后淡入
    val fadeIn = remember(cacheKey) { androidx.compose.animation.core.Animatable(if (bitmap != null) 1f else 0f) }
    // 淡入期间占位图是否继续垫在封面下面（占位 → 封面交叉过渡）；淡入结束即撤掉，常态只画一层。
    // 单独用状态位而非在组合里读 fadeIn.value，避免淡入的每一帧都触发重组
    var placeholderUnder by remember(cacheKey) { mutableStateOf(bitmap == null) }
    LaunchedEffect(cacheKey) {
        if (bitmap != null || cacheKey == null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            loadCoverBitmapCached(context, coverUrl, albumArtUri)
        } ?: return@LaunchedEffect
        bitmap = loaded
        // 150ms FastOutSlowIn：前段快速显形、尾段柔和收住，替代硬切的"弹出感"
        fadeIn.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
        placeholderUnder = false
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        // 委托属性无法智能转换非空，先落到局部 val 再判空使用
        val loaded = bitmap
        if (loaded == null || placeholderUnder) {
            // 占位：中性色专辑图标
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LucideIcons.Disc3,
                    contentDescription = null,
                    modifier = Modifier.size(placeHolderSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        if (loaded != null) {
            // ImageBitmap 包装按位图记住：重组时不重复包装，Image 内部的 painter 也随之复用
            val image = remember(loaded) { loaded.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "封面",
                modifier = Modifier
                    .fillMaxSize()
                    // alpha 只在绘制阶段读取：淡入动画逐帧重绘，不逐帧重组
                    .graphicsLayer { alpha = fadeIn.value },
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 本地封面（IO 线程调用）：ContentResolver.loadThumbnail（API 29+）让系统 MediaProvider
 * 解析音频文件的内嵌封面（FLAC/MP3 等）；无内嵌图或低于 API 29 时返回 null（UI 显示占位图标）
 */
private fun loadLocalCoverThumbnail(context: Context, albumArtUri: String): Bitmap? {
    if (Build.VERSION.SDK_INT < 29) return null
    return runCatching {
        context.contentResolver.loadThumbnail(
            Uri.parse(albumArtUri),
            // 512px 兼顾列表缩略图与播放页大封面的清晰度
            Size(512, 512),
            null,
        )
    }.getOrNull()
}


