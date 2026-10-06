package com.echonion.nion.ui.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Future

/**
 * 音乐播放器控制门面（Facade）—— 供 AI 伴伴的 [com.echonion.nion.ui.companion.tools.MusicTool]
 * 使用的第二条 MediaController 连接。
 *
 * 与 MusicViewModel 持有的控制器关系：
 * - 两者各自独立通过 SessionToken 绑定同一个 [PlaybackService]（进程内 Media3 Session
 *   支持多客户端连接，操作最终都作用在同一个播放器实例上，互不冲突）
 * - MusicViewModel 的控制器归 UI 状态展示所有，本门面的控制器归 Agent 工具所有，
 *   生命周期互不感知、互不释放
 *
 * 生命周期：[init] 在 NionApp.onCreate（core 初始化之后）调用一次；连接建立后
 * 与应用进程同生命周期，**不主动断开**（应用退出时随进程一起回收，无需手动 release）。
 *
 * 线程约定：所有播放操作内部统一切换到主线程执行 —— MediaController 要求在
 * 构建它的线程（主线程）上调用；工具层在 IO 线程调用本门面的挂起函数即可。
 */
object MusicControllerFacade {

    /** 应用实例 —— init 时持有，同时供工具层查询本地曲库（ContentResolver） */
    @Volatile
    private var appRef: Application? = null

    /** 已连接的第二条 MediaController；构建异步完成前为 null（此时操作抛中文异常给上层转 JSON） */
    @Volatile
    private var controllerRef: MediaController? = null

    /** 构建中的 Future —— 仅作持有引用防止被 GC，应用生命周期内不取消不断开 */
    @SuppressWarnings("unused")
    private var controllerFuture: Future<MediaController>? = null

    /**
     * 初始化 —— 建立第二条到 [PlaybackService] 的控制器连接。
     * 重复调用安全（已初始化直接返回）。必须在主线程调用（NionApp.onCreate 天然满足）。
     */
    fun init(application: Application) {
        if (appRef != null) return
        appRef = application
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    controllerRef = future.get()
                } catch (_: Exception) {
                    // 服务未就绪等异常：保持 null，后续操作会返回"播放服务尚未连接"的中文错误
                }
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    /** 应用实例；init 未调用时为 null（供工具层扫描本地曲库用） */
    fun application(): Application? = appRef

    /** 取已连接的控制器；未就绪时抛出中文异常，由工具层转成错误 JSON 返回 LLM */
    private fun requireController(): MediaController =
        controllerRef ?: throw IllegalStateException("播放服务尚未连接完成，请稍后再试")

    // ──────────────────────────────────────────────────────
    // 基础播放控制（全部在主线程执行）
    // ──────────────────────────────────────────────────────

    /** 播放/暂停切换 */
    suspend fun togglePlayPause() = onMain { c ->
        if (c.isPlaying) c.pause() else c.play()
    }

    /** 继续播放 */
    suspend fun play() = onMain { it.play() }

    /** 暂停 */
    suspend fun pause() = onMain { it.pause() }

    /** 下一首 */
    suspend fun next() = onMain { it.seekToNextMediaItem() }

    /** 上一首（与 UI 一致：已播超过 3 秒先回到本曲开头） */
    suspend fun previous() = onMain { c ->
        if (c.currentPosition > 3000L) c.seekTo(0L) else c.seekToPreviousMediaItem()
    }

    /** 跳转到指定位置（毫秒） */
    suspend fun seekTo(positionMs: Long) = onMain { it.seekTo(positionMs.coerceAtLeast(0L)) }

    /** 跳到队列中指定下标的曲目并播放 */
    suspend fun playIndex(index: Int) = onMain { c ->
        if (index < 0 || index >= c.mediaItemCount) {
            throw IllegalArgumentException("下标越界: $index（队列共 ${c.mediaItemCount} 首）")
        }
        c.seekTo(index, 0L)
        c.play()
    }

    /** 设置随机播放开关 */
    suspend fun setShuffle(enabled: Boolean) = onMain { it.shuffleModeEnabled = enabled }

    /**
     * 设置循环模式。
     *
     * @param mode "off"=不循环 / "one"=单曲循环 / "all"=列表循环；其他值抛中文异常
     */
    suspend fun setRepeatMode(mode: String) = onMain { c ->
        c.repeatMode = when (mode) {
            "off" -> Player.REPEAT_MODE_OFF
            "one" -> Player.REPEAT_MODE_ONE
            "all" -> Player.REPEAT_MODE_ALL
            else -> throw IllegalArgumentException("未知循环模式: $mode（可选 off/one/all）")
        }
    }

    /** 设置播放速度（1.0=正常） */
    suspend fun setSpeed(speed: Float) = onMain { c ->
        if (speed <= 0f || speed > 4f) {
            throw IllegalArgumentException("播放速度需在 (0, 4] 范围内，收到: $speed")
        }
        c.setPlaybackSpeed(speed)
    }

    /** 停止播放并清空队列 */
    suspend fun stopAndClear() = onMain { c ->
        c.stop()
        c.clearMediaItems()
    }

    // ──────────────────────────────────────────────────────
    // 队列与快照
    // ──────────────────────────────────────────────────────

    /**
     * 以给定曲目列表为队列开始播放。
     *
     * MediaItem 构建与 MusicViewModel.playQueue 同构但独立实现（不改 VM）：
     * mediaId 用曲目 id，标题/艺术家/专辑写入 MediaMetadata 供通知栏展示，
     * 封面双通道 —— 云端 [PlayableTrack.coverArtUrl] 与本地 [PlayableTrack.albumArtUri]
     * 均尝试写入 artworkUri，由 Media3 的 SimpleBitmapLoader 选择可用的一路加载。
     *
     * @param tracks 完整队列
     * @param startIndex 起始曲目下标（越界时按 0 处理）
     */
    suspend fun setQueueAndPlay(tracks: List<PlayableTrack>, startIndex: Int) {
        if (tracks.isEmpty()) throw IllegalArgumentException("播放队列为空")
        val items = tracks.map { track -> buildMediaItem(track) }
        val start = startIndex.coerceIn(0, items.lastIndex)
        onMain { c ->
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** 构建单条 MediaItem —— id/URI/元数据/封面双通道（见 [setQueueAndPlay] 注释） */
    private fun buildMediaItem(track: PlayableTrack): MediaItem {
        // 封面双通道：云端拼好认证参数的 getCoverArt URL 优先，本地取 albums 表 content URI
        val artwork = track.coverArtUrl ?: track.albumArtUri
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.playbackUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifBlank { null })
                    .setAlbumTitle(track.album.ifBlank { null })
                    .setArtworkUri(artwork?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    /**
     * 播放器当前状态快照 —— 现场从控制器读取（不依赖任何缓存/回调状态）。
     *
     * @param sourceName 当前音源中文名（由调用方从 core settings 读取后传入，
     *   门面自身不触碰 settings 表）
     * @return 快照键值：current_title/current_artist/current_album（当前曲，无则 null）、
     *   position_ms/duration_ms、queue_count、current_index、is_playing、
     *   shuffle_enabled、repeat_mode(off/one/all)、speed、queue_titles（前 30 首曲名）、
     *   source_name
     */
    suspend fun getSnapshot(sourceName: String): Map<String, Any?> = onMain { c ->
        val current = c.currentMediaItem
        mapOf(
            "current_title" to current?.mediaMetadata?.title?.toString(),
            "current_artist" to current?.mediaMetadata?.artist?.toString(),
            "current_album" to current?.mediaMetadata?.albumTitle?.toString(),
            "position_ms" to c.currentPosition.coerceAtLeast(0L),
            "duration_ms" to (if (c.duration == C.TIME_UNSET) 0L else c.duration),
            "queue_count" to c.mediaItemCount,
            "current_index" to c.currentMediaItemIndex,
            "is_playing" to c.isPlaying,
            "shuffle_enabled" to c.shuffleModeEnabled,
            "repeat_mode" to when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            },
            "speed" to c.playbackParameters.speed,
            // 队列曲名最多带前 30 首，避免超长队列把上下文撑爆
            "queue_titles" to (0 until c.mediaItemCount)
                .take(30)
                .map { c.getMediaItemAt(it).mediaMetadata.title?.toString() ?: "" },
            "source_name" to sourceName,
        )
    }

    /** 在主线程拿到控制器并执行操作，返回操作结果 */
    private suspend fun <T> onMain(block: (MediaController) -> T): T =
        withContext(Dispatchers.Main) { block(requireController()) }

    // ──────────────────────────────────────────────────────
    // 本地曲库查询（供工具层 list_all_songs / 歌单曲目解析使用）
    // ──────────────────────────────────────────────────────

    /**
     * 查询 MediaStore 全部音频为统一的 [PlayableTrack] 列表（与 MusicViewModel 的扫描
     * 规则一致但独立实现：时长 >= 10s 过滤、曲名排序、unknown 占位中文化）。
     *
     * 本地曲目 id 直接使用 MediaStore _ID 字符串（可 parseLong 存入 core 本地歌单表）。
     * 抛出的异常由调用方（工具层）统一转中文错误 JSON。
     */
    fun queryLocalTracksForAgent(): List<PlayableTrack> {
        val app = appRef ?: throw IllegalStateException("门面尚未初始化")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val result = mutableListOf<PlayableTrack>()
        app.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.DURATION} >= ?",
            arrayOf("10000"),
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val albumArtUri =
                    if (albumId > 0) android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                        albumId,
                    ).toString() else null
                result.add(
                    PlayableTrack(
                        id = id.toString(),
                        title = cursor.getString(titleCol)?.ifBlank { "未知曲目" } ?: "未知曲目",
                        artist = cursor.getString(artistCol)
                            ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                            ?: "未知艺术家",
                        album = cursor.getString(albumCol)
                            ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                            ?: "未知专辑",
                        durationSeconds = cursor.getLong(durationCol) / 1000,
                        playbackUri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        ).toString(),
                        albumArtUri = albumArtUri,
                        isLocal = true,
                        albumId = albumId,
                    ),
                )
            }
        }
        return result
    }
}
