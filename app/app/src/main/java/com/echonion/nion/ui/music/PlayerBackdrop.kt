package com.echonion.nion.ui.music

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** 提取结果 LruCache：key 与 CoverArtCache 一致（"local:<uri>" 或封面 URL） */
private val backdropCache = object : LruCache<String, PlayerBackdropColors>(32) {}

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
 * 当前曲目的动态背景色（带 600ms 颜色过渡动画）。
 *
 * 封面位图复用 [CoverArtCache] / [loadCoverBitmap]（与列表封面同一来源与
 * 缓存）：先查取色缓存 → 未命中异步加载封面提取 → 回写两份缓存。
 * 无封面/加载失败时保持主题背景色兜底。
 */
@Composable
internal fun rememberPlayerBackdrop(track: PlayableTrack?): PlayerBackdropColors {
    val context = LocalContext.current
    val fallback = PlayerBackdropColors(
        top = MaterialTheme.colorScheme.surfaceContainerHigh,
        bottom = MaterialTheme.colorScheme.background,
    )
    // 缓存 key 与 CoverArt 完全一致：本地封面优先
    val cacheKey = track?.albumArtUri?.let { "local:$it" } ?: track?.coverArtUrl

    var target by remember {
        mutableStateOf(cacheKey?.let { backdropCache.get(it) } ?: fallback)
    }
    LaunchedEffect(cacheKey) {
        val key = cacheKey ?: run {
            target = fallback
            return@LaunchedEffect
        }
        backdropCache.get(key)?.let {
            target = it
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) {
            loadCoverBitmap(context, track?.coverArtUrl, track?.albumArtUri)
        }?.also { CoverArtCache.put(key, it) }
        val extracted = bitmap?.let(::extractBackdrop) ?: fallback
        backdropCache.put(key, extracted)
        target = extracted
    }

    // 切歌时背景色平滑过渡，避免硬切
    val top by animateColorAsState(target.top, tween(600), label = "backdropTop")
    val bottom by animateColorAsState(target.bottom, tween(600), label = "backdropBottom")
    return PlayerBackdropColors(top, bottom, target.accent)
}

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
 * 取色预热：开始播放/切歌时（迷你条在场即调用）提前提取封面色入缓存，
 * 使"点开播放页"时目标色即时可用——铺开动画一起步就是歌曲色，
 * 不会先落在米色兜底再慢半拍变绿。无封面/加载失败不写缓存（保持兜底路径）。
 */
internal suspend fun prewarmBackdrop(context: android.content.Context, track: PlayableTrack?) {
    val key = track?.albumArtUri?.let { "local:$it" } ?: track?.coverArtUrl ?: return
    if (backdropCache.get(key) != null) return
    val bitmap = withContext(Dispatchers.IO) {
        loadCoverBitmap(context, track?.coverArtUrl, track?.albumArtUri)
    } ?: return
    backdropCache.put(key, withContext(Dispatchers.Default) { extractBackdrop(bitmap) })
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

