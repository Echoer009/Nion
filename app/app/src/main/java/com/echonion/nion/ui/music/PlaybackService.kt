package com.echonion.nion.ui.music

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

/**
 * 音乐播放前台服务 —— 基于 Media3 MediaSessionService 实现。
 *
 * 职责：
 * 1. 持有全局唯一的 ExoPlayer + MediaSession 实例（后台播放、通知栏控制的载体）
 * 2. 注册音频属性 —— 告诉系统这是音乐播放，接管音频焦点（打断策略由系统调度）
 * 3. 通知栏播放控制：MediaSessionService 配合默认的 DefaultMediaNotificationProvider
 *    自动生成 MediaStyle 通知（播放/暂停/上一首/下一首 + 封面），无需手写通知代码
 *
 * 生命周期：
 * - UI 通过 MediaController 连接本服务（见 MusicViewModel），controller 首次连接时
 *   系统自动 startForegroundService 拉起服务；播放暂停后自动停止前台
 * - onTaskRemoved（用户划掉任务）时暂停播放，避免后台空转
 *
 * 参照项目 reminder 包前台服务的注释风格；媒体通知部分交给 Media3 默认实现。
 */
class PlaybackService : MediaSessionService() {

    /** 全局媒体会话；onCreate 创建、onDestroy 释放，中间不为 null */
    private var mediaSession: MediaSession? = null

    companion object {
        /** 音频磁盘缓存上限：1GB LRU —— 约可容纳 30~50 张无损专辑/数百首有损曲 */
        private const val AUDIO_CACHE_BYTES = 1024L * 1024 * 1024

        /**
         * 全进程唯一的音频磁盘缓存（cacheDir/audio）。必须单例：SimpleCache 对
         * 同一目录重复实例化会直接抛异常。进程内静态持有、不主动 release
         * （索引由 StandaloneDatabaseProvider 持久化，进程死亡磁盘数据仍在）。
         * 预取下一首（MusicViewModel.prefetchNextTrack）也写同一个缓存
         */
        @Volatile
        private var audioCache: SimpleCache? = null

        fun audioCache(context: Context): SimpleCache = audioCache ?: synchronized(this) {
            audioCache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "audio"),
                LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { audioCache = it }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 音频属性：CONTENT_TYPE_MUSIC + USAGE_MEDIA 让系统按"音乐"处理音频焦点
        // （如来电时自动压低/暂停，与其他音乐 App 竞争焦点时行为正确）
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 音频磁盘缓存：CacheDataSource 包装上游网络源 —— 听过的曲段直接读本地，
        // 未命中的部分回源下载并落盘。FLAG_IGNORE_CACHE_ON_ERROR：缓存读写出
        // 任何问题（磁盘满/索引损坏）时静默回退直连，绝不断播
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(audioCache(this))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))
            // 只落盘网络曲目：本地 content:// 文件再复制一份进缓存纯属浪费空间
            //（返回 null = 该 DataSpec 不写缓存，读取照常走上游）
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            // 本地曲目（content://）不经缓存直连上游：既避免无意义的磁盘读放大，
            // 更避免把本地文件整首复制进缓存白占 1GB（按 scheme 路由见下方类）
            .let { SchemeRoutingDataSource.Factory(it, DefaultDataSource.Factory(this)) }

        val player = ExoPlayer.Builder(this)
            // 缓存必须挂在 MediaSourceFactory 上（MediaItem 的 customCacheKey
            // 由 CacheDataSource 用作分片键，见 setCustomCacheKey 注释）
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            // 耳机拔出自动暂停，避免外放尴尬
            .setHandleAudioBecomingNoisy(true)
            // 网络流播放期间持有 WakeLock（TYPE_PARTIAL），锁屏继续播放
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    /**
     * 返回当前 MediaSession —— MediaController 连接的入口。
     * 返回 null 表示服务不可用（onDestroy 后）。
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 用户从最近任务划掉 App 时调用：仅暂停不 release，
     * MediaSessionService 会在无活跃播放时自动停止服务。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            // 没在播放 → 直接停服；在播放则交由系统在播放结束/暂停后自动停
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 必须先 release player 再 release session，顺序不可反（session 持有 player 引用）
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * 按 URI scheme 分发的 DataSource：http(s) 走缓存通道（[cached]，
 * CacheDataSource，读缓存/回源落盘），其余（本地 content://、file://）
 * 直连上游（[plain]）。DataSource 在 open 之前不知道 URI，工厂创建时
 * 只能把两个实例都备好、open 时再选定活跃通道。
 */
private class SchemeRoutingDataSource(
    private val cached: androidx.media3.datasource.DataSource,
    private val plain: androidx.media3.datasource.DataSource,
) : androidx.media3.datasource.DataSource {

    /** 当前活跃通道：open 时按 scheme 选定，close 后置空 */
    private var active: androidx.media3.datasource.DataSource? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        val useCache = dataSpec.uri.scheme?.startsWith("http") == true
        val ds = if (useCache) cached else plain
        active = ds
        return ds.open(dataSpec)
    }

    /** 当前打开资源的 URI（未打开/已关闭为 null），透传活跃通道 */
    override fun getUri(): android.net.Uri? = active?.uri

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun close() {
        active?.close()
        active = null
    }

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        cached.addTransferListener(transferListener)
        plain.addTransferListener(transferListener)
    }

    /** 工厂：两条通道各自建实例，包进路由器 */
    class Factory(
        private val cached: androidx.media3.datasource.DataSource.Factory,
        private val plain: androidx.media3.datasource.DataSource.Factory,
    ) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource =
            SchemeRoutingDataSource(cached.createDataSource(), plain.createDataSource())
    }
}
