package com.echonion.nion.ui.music

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页动态背景（汽水音乐式封面取色）。
 *
 * 从当前曲目封面位图提取主色，生成"上浅下深"的两色渐变；同时派生一个
 * 深色 Material 配色方案（[rememberPlayerColorScheme]），让播放页整体成为
 * 一块自包含的深色沉浸面 —— App 主体是浅色主题，播放页不跟随，文字/控件
 * 在取色背景上始终用白色系，对比度稳定。
 *
 * 提取结果按封面缓存 key 记忆（[backdropCache]），来回切歌不重复计算。
 */

/** 渐变两色；null = 尚未提取完成（先用主题背景色兜底） */
data class PlayerBackdropColors(
    val top: Color,
    val bottom: Color,
    /** 强调色（播放按钮/滑块等 primary 位）；仅提取成功后非 null */
    val accent: Color? = null,
)

/** 提取结果 LruCache：key 与 CoverArtCache 一致（[stableCoverKey]：本地 "local:<uri>" / 服务器 "web:…"） */
private val backdropCache = object : LruCache<String, PlayerBackdropColors>(32) {}

/** 封面缓存 key：与 CoverArt / CoverArtCache 完全一致（稳定 key，本地封面优先） */
private fun backdropKey(track: PlayableTrack?): String? =
    stableCoverKey(track?.coverArtUrl, track?.albumArtUri)

/**
 * 同步取色（主线程可调，不做任何 IO）：
 * 1. 取色缓存命中 → 直接返回；
 * 2. 否则若列表/迷你条已把封面位图解码进 [CoverArtCache]（点歌时被点的行
 *    封面必然已显示过）→ 当场 Palette 提取（512px 位图约数毫秒）并回写缓存；
 * 3. 都没有 → null（调用方走异步加载）。
 *
 * 用途：点歌/点迷你条的回调里拿到"本首歌的真实颜色"，让 morph 首帧起整块
 * 就是歌曲色 —— 不再先以米色兜底展开、过半秒才异步渐变成歌曲色
 */
internal fun peekBackdrop(track: PlayableTrack?): PlayerBackdropColors? {
    val key = backdropKey(track) ?: return null
    backdropCache.get(key)?.let { return it }
    // 精确尺寸（300px）常未解码过：被点的列表行/迷你条显示的是 150px 变体，取色用它即可
    val bitmap = CoverArtCache.get(key) ?: CoverArtCache.getAnyVariant(key) ?: return null
    return extractBackdrop(bitmap).also { backdropCache.put(key, it) }
}

/**
 * 提取封面背景色：上=深鲜艳色，下=主色压暗，accent=鲜艳色提亮。
 *
 * 两端都向黑色 lerp —— 渐变整体偏暗，保证白色文字与滑块在任何专辑封面
 * （哪怕是大面积白/黄封面）上都可读。
 */
private fun extractBackdrop(bitmap: Bitmap): PlayerBackdropColors {
    val palette = Palette.from(bitmap).maximumColorCount(24).generate()
    val dominant = Color(palette.getDominantColor(0xFF35313A.toInt()))
    val accentRaw = Color(
        palette.getVibrantColor(
            palette.getMutedColor(palette.getDarkVibrantColor(dominant.toArgb())),
        ),
    )
    val accent = lerp(accentRaw, Color.White, 0.15f)
    return PlayerBackdropColors(
        top = lerp(accentRaw, Color.Black, 0.30f),
        bottom = lerp(dominant, Color.Black, 0.58f),
        accent = accent,
    )
}

/**
 * 当前曲目的动态背景色。
 *
 * 封面位图复用 [CoverArtCache] / [loadCoverBitmapCached]（与列表封面同一来源与
 * 缓存）：先同步查两级缓存（[peekBackdrop]）→ 未命中异步加载封面提取 →
 * 回写两份缓存。无封面/加载失败时保持主题背景色兜底。
 *
 * MusicScreen 顶层常驻调用本函数（不止播放页打开时），切歌的同时就在后台
 * 取色入缓存，点开播放页时目标色已就绪。
 *
 * @param track 当前曲目（null = 无播放，返回兜底色）
 * @param animateChanges 切歌换色是否 600ms 渐变：播放页打开期间传 true（上下滑
 *   切歌平滑过渡）；播放页关闭时传 false —— 打开那一帧的换歌直接取新色，
 *   morph 首帧就是新歌颜色
 */
@Composable
internal fun rememberPlayerBackdrop(track: PlayableTrack?, animateChanges: Boolean): PlayerBackdropColors {
    val context = LocalContext.current
    val fallback = PlayerBackdropColors(
        top = MaterialTheme.colorScheme.surfaceContainerHigh,
        bottom = MaterialTheme.colorScheme.background,
    )
    // 缓存 key 与 CoverArt 完全一致：本地封面优先
    val cacheKey = backdropKey(track)

    // target：本曲目标色。remember(cacheKey) —— 切歌的同一次组合里就同步换成
    // 新曲目的颜色（peekBackdrop 命中取色缓存或封面位图缓存时零延迟），不会有
    // "新歌第一帧还是旧色"的帧。两级缓存都未命中时沿用上一首的已定颜色
    // （lastResolved）等异步结果 —— 不退回米色兜底，否则"下一首/自动连播"时
    // 播放页背景会先朝米色渐变再变回歌曲色；只有无封面曲目才用兜底色
    val lastResolved = remember { Ref<PlayerBackdropColors?>(null) }
    var target by remember(cacheKey) {
        mutableStateOf(
            if (cacheKey == null) fallback else peekBackdrop(track) ?: lastResolved.value ?: fallback,
        )
    }
    SideEffect { lastResolved.value = target }
    LaunchedEffect(cacheKey) {
        val key = cacheKey ?: return@LaunchedEffect
        if (backdropCache.get(key) != null) return@LaunchedEffect
        // 走统一取载漏斗（内存→磁盘→网络）：取色与列表封面共用三级缓存，
        // 命中时零网络请求，且与 CoverArt 的 key 严格一致
        val bitmap = withContext(Dispatchers.IO) {
            loadCoverBitmapCached(context, track?.coverArtUrl, track?.albumArtUri)
        }
        val extracted = bitmap?.let(::extractBackdrop) ?: fallback
        backdropCache.put(key, extracted)
        target = extracted
    }

    // 本次换色是否渐变：取"切歌前一帧"的 animateChanges（非快照的普通标志位，
    // 读写不触发重组）。播放页内上下滑切歌 → 600ms 渐变；点歌/点迷你条打开
    // 的那一帧换歌（前一帧播放页还关着）→ 当帧直接取新色，morph 首帧即新歌
    // 颜色 —— 否则会从上一首/米色兜底慢慢渐变，整块容器带着灰米色铺开
    val lastAnimateChanges = remember { Ref(false) }
    val animateThisKey = remember(cacheKey) { lastAnimateChanges.value }
    SideEffect { lastAnimateChanges.value = animateChanges }

    // 渐变动画状态始终跟踪 target（非渐变时 snap，保证下次渐变从正确颜色起步）
    val spec: AnimationSpec<Color> = if (animateThisKey) tween(600) else snap()
    val top by animateColorAsState(target.top, spec, label = "backdropTop")
    val bottom by animateColorAsState(target.bottom, spec, label = "backdropBottom")
    // 非渐变时直接返回 target（animateColorAsState 的 snap 要晚一帧才生效）
    return if (animateThisKey) PlayerBackdropColors(top, bottom, target.accent) else target
}

/** 组合间传递的普通可变引用（刻意不用 MutableState：只做记录，读写不驱动重组） */
private class Ref<T>(var value: T)

/**
 * 播放条/导航栏共用的"一体色"：唯一颜色源 [rememberPlayerBackdrop] 的底端色。
 * 沉浸激活瞬间直接取歌曲色（定义：t=0 播放条与导航栏作为整体变歌曲色），
 * 退出沉浸时从当前值 250ms 淡回调用方的主题色 —— 两处调用同一状态同一曲线，
 * 淡回严格同步。
 */
@Composable
internal fun immersiveUnitColor(active: Boolean, song: Color, fallback: Color): Color {
    val faded by animateColorAsState(
        targetValue = if (active) song else fallback,
        animationSpec = tween(250),
        label = "immersiveUnit",
    )
    return if (active) song else faded
}

/**
 * 播放页专用深色配色：accent（取色）作 primary，背景容器色贴近渐变两端，
 * 其余沿用 Material 深色默认值。播放页内部所有 MaterialTheme.colorScheme
 * 引用（滑块/图标/队列行…）经嵌套 MaterialTheme 自动切换，无需逐处改色。
 *
 * 容器分层对照米色主题的层级（主页面最沉 / 侧边栏略亮 / 底部导航再亮一档）：
 * 窗口底色 = 渐变底端，面板容器提亮 5%，导航容器提亮 10% —— 沉浸态依旧
 * 有层级，不是一张平板色。
 */
@Composable
internal fun rememberPlayerColorScheme(backdrop: PlayerBackdropColors) = darkColorScheme(
    primary = backdrop.accent ?: Color(0xFFD0BCFF),
    background = backdrop.top,
    surface = backdrop.top,
    surfaceContainer = lerp(backdrop.bottom, Color.White, 0.05f),
    surfaceContainerHigh = lerp(backdrop.bottom, Color.White, 0.10f),
    surfaceContainerHighest = lerp(backdrop.bottom, Color.White, 0.10f),
)

