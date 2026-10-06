package com.echonion.nion.ui.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import com.echonion.nion.core
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.nion_core.NionCore

/**
 * 配置连通性测试结果 —— 服务器配置表单里"测试连接"按钮的状态机。
 *
 * @property isTesting 是否正在测试中（按钮转圈/禁用）
 * @property success null=尚未出结果；true=ping 成功；false=失败
 * @property message 展示给用户的结果文案（成功时带服务器版本号，失败时带错误原因）
 */
data class ConfigTestState(
    val isTesting: Boolean = false,
    val success: Boolean? = null,
    val message: String = "",
)

/**
 * 音乐模块 ViewModel —— 全部业务状态的中枢。
 *
 * 职责划分：
 * 1. 音源选择与配置持久化（NionCore settings 表，键名见 [MusicSettings]）
 * 2. 本地曲目扫描（MediaStore 全机音频，过滤 <10s 的提示音）
 * 3. 服务器浏览状态（专辑列表 / 专辑详情 / search3 搜索）
 * 4. 播放控制 —— 通过 MediaController 连接 [PlaybackService]：
 *    - 队列管理（setMediaItems + 指定起始曲目）
 *    - 播放/暂停/seek/上一首/下一首
 *    - 轮询当前位置驱动进度条（500ms 一次，Compose 状态直接消费）
 *
 * 生命周期：本 ViewModel 绑定在 Activity 作用域（见 MusicScreen.musicViewModel()），
 * 用户在底部导航各页之间切换时不会被销毁，音乐播放与队列因此不中断。
 *
 * @param app Application 实例（获取 ContentResolver / 构建 MediaController）
 * @param core NionCore 单例（读写 settings 表）
 */
@kotlinx.coroutines.FlowPreview
class MusicViewModel(
    private val app: Application,
    private val core: NionCore,
) : ViewModel() {

    // ──────────────────────────────────────────────────────
    // 音源与配置状态
    // ──────────────────────────────────────────────────────

    /** 当前选中的音源；init 时从 settings 读取，切换后立即持久化 */
    var source by mutableStateOf(MusicSource.LOCAL)
        private set

    /** 类脑娘服务器用户名（双向绑定到配置表单） */
    var braingirlUsername by mutableStateOf("")
    /** 类脑娘服务器密码（双向绑定到配置表单） */
    var braingirlPassword by mutableStateOf("")
    /** 自己的服务器 URL（双向绑定到配置表单） */
    var customServerUrl by mutableStateOf("")
    /** 自己的服务器用户名 */
    var customUsername by mutableStateOf("")
    /** 自己的服务器密码 */
    var customPassword by mutableStateOf("")

    /** "测试连接"按钮状态（对应当前打开的配置表单） */
    var configTest by mutableStateOf(ConfigTestState())
        private set

    // ──────────────────────────────────────────────────────
    // 本地曲目状态
    // ──────────────────────────────────────────────────────

    /** MediaStore 扫描出的本地曲目列表（按曲名排序） */
    var localTracks by mutableStateOf<List<PlayableTrack>>(emptyList())
        private set

    /** 是否正在扫描本地曲目 */
    var isLoadingLocal by mutableStateOf(false)
        private set

    // ──────────────────────────────────────────────────────
    // 服务器浏览状态
    // ──────────────────────────────────────────────────────

    /** 专辑列表（getAlbumList2 newest） */
    var albums by mutableStateOf<List<SubsonicAlbum>>(emptyList())
        private set

    /** 当前打开的专辑（null=专辑列表视图；非 null=专辑详情视图） */
    var currentAlbum by mutableStateOf<SubsonicAlbum?>(null)
        private set

    /** 当前专辑的曲目列表 */
    var albumSongs by mutableStateOf<List<PlayableTrack>>(emptyList())
        private set

    /** 搜索关键词 —— UI 输入框直接写入，VM 内部 debounce 后触发 search3 */
    var searchQuery by mutableStateOf("")

    /** 最近一次搜索结果（null=无搜索） */
    var searchResult by mutableStateOf<SubsonicSearchResult?>(null)
        private set

    /** 服务器数据是否加载中（专辑列表/专辑详情共用一个标志） */
    var isLoadingServer by mutableStateOf(false)
        private set

    /** 服务器请求错误信息；非空时 UI 显示错误条，点击重试可清除 */
    var serverError by mutableStateOf<String?>(null)
        private set

    // ──────────────────────────────────────────────────────
    // 播放器状态（来自 MediaController 回调 + 轮询）
    // ──────────────────────────────────────────────────────

    /** 已连接的 MediaController；构建是异步的，连接完成前为 null（此时播放操作静默忽略） */
    var controller by mutableStateOf<MediaController?>(null)
        private set

    /** 当前播放队列（即 setMediaItems 传入的曲目列表副本） */
    var queue by mutableStateOf<List<PlayableTrack>>(emptyList())
        private set

    /** 当前播放曲目的下标（-1=空队列）；由 onMediaItemTransition 回调同步 */
    var currentIndex by mutableIntStateOf(-1)
        private set

    /**
     * 是否处于"播放中"的视觉状态（含缓冲）。
     * 不直接采用 player.isPlaying：seek 时 ExoPlayer 会短暂进入 BUFFERING，
     * isPlaying 瞬间 false→true，会造成播放/暂停图标闪一次切换动画。
     * 这里用稳定映射 playWhenReady && state in {READY, BUFFERING}，
     * 缓冲期间保持播放态视觉，seek/网络缓冲不再触发图标翻转。
     */
    var isPlaying by mutableStateOf(false)
        private set

    /** 当前播放位置（毫秒），500ms 轮询更新，驱动进度条 */
    var positionMs by mutableLongStateOf(0L)
        private set

    /** 当前曲目总时长（毫秒），0=未知；由轮询 + onPlaybackStateChanged 同步 */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** MediaController 构建 Future —— onCleared 时取消，避免泄漏未完成的连接 */
    private var controllerFuture: java.util.concurrent.Future<MediaController>? = null

    /**
     * Player 事件监听器 —— 把 ExoPlayer 内部状态变化映射到 Compose 状态。
     * 注意：所有回调都在主线程执行（MediaController 保证），可直接写状态。
     */
    private val playerListener = object : Player.Listener {
        /** 任一前置条件变化时重算稳定的播放态视觉（见 isPlaying 属性注释） */
        private fun refreshPlayingState() {
            val c = controller ?: return
            isPlaying = c.playWhenReady &&
                (c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING)
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            // 不直接透传 playing（BUFFERING 期间它会变 false），改走稳定映射
            refreshPlayingState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 用户点播放/暂停只改 playWhenReady，这里同步视觉状态
            refreshPlayingState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 自动切歌（播完/下一首）时同步当前下标；队列未配置时重置为 -1
            val c = controller ?: return
            currentIndex = if (mediaItem != null) c.currentMediaItemIndex else -1
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // STATE_READY 后 duration 才有效；TIME_UNSET 转为 0 供 UI 判断
            val c = controller ?: return
            val d = c.duration
            durationMs = if (d == C.TIME_UNSET) 0L else d
            // 播放状态变化也影响稳定映射（如 BUFFERING→READY、进入 ENDED），同步重算
            refreshPlayingState()
        }
    }

    init {
        // 1. 从 settings 表加载音源与账密配置（同步 DB 操作切到 IO 线程，
        //    读取完成后回主线程一次性写入 Compose 状态）
        viewModelScope.launch {
            val config = withContext(Dispatchers.IO) { StoredConfig.load(core) }
            source = config.source
            braingirlUsername = config.braingirlUsername
            braingirlPassword = config.braingirlPassword
            customServerUrl = config.customServerUrl
            customUsername = config.customUsername
            customPassword = config.customPassword
        }

        // 2. 异步连接 PlaybackService，成功后注册监听器
        connectController()

        // 3. 位置轮询循环：驱动进度条与时间显示；500ms 粒度对滑块足够平滑
        viewModelScope.launch {
            while (isActive) {
                controller?.let { c ->
                    positionMs = c.currentPosition.coerceAtLeast(0L)
                    val d = c.duration
                    durationMs = if (d == C.TIME_UNSET) 0L else d
                }
                delay(500)
            }
        }

        // 4. 搜索 debounce：输入停顿 400ms 后自动触发 search3，输入清空则清结果
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { searchQuery }
                .debounce(400)
                .collect { q ->
                    if (q.isBlank()) {
                        searchResult = null
                    } else {
                        performSearch(q)
                    }
                }
        }
    }

    // ──────────────────────────────────────────────────────
    // 音源与配置
    // ──────────────────────────────────────────────────────

    /**
     * 切换音源 —— 更新内存状态并立即持久化到 settings 表。
     *
     * @param newSource 用户在 MusicSourcePanel 中点选的音源
     */
    fun selectSource(newSource: MusicSource) {
        if (newSource == source) return
        source = newSource
        // 切换音源后清空旧音源的浏览数据：两个服务器的专辑/详情互不可见，
        // 新音源的 ServerMusicContent 会通过 LaunchedEffect 重新加载专辑列表
        resetServerState()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { core.setSetting(MusicSettings.KEY_SOURCE, newSource.key) }
        }
    }

    /**
     * 类脑娘服务器是否已完成账密配置（决定显示引导态还是曲库）。
     * 用户名与密码均非空即视为已配置。
     */
    val isBraingirlConfigured: Boolean
        get() = braingirlUsername.isNotBlank() && braingirlPassword.isNotBlank()

    /**
     * 自己的服务器是否已完成配置（URL + 用户名 + 密码均非空）。
     */
    val isCustomConfigured: Boolean
        get() = customServerUrl.isNotBlank() && customUsername.isNotBlank() && customPassword.isNotBlank()

    /**
     * 保存类脑娘服务器账密并持久化；保存后清空浏览数据与服务端错误，
     * 让 UI 重新走"加载专辑列表"流程。
     */
    fun saveBraingirlConfig(username: String, password: String) {
        braingirlUsername = username.trim()
        braingirlPassword = password
        resetServerState()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                core.setSetting(MusicSettings.KEY_BRAINGIRL_USERNAME, braingirlUsername)
                core.setSetting(MusicSettings.KEY_BRAINGIRL_PASSWORD, braingirlPassword)
            }
        }
    }

    /**
     * 应用注册深链回传的类脑娘账密（nion://musicauth?u=..&p=..，
     * 由 MainActivity 解析后经 pendingMusicAuthXxx 传入音乐界面）。
     *
     * 等价于"表单填好账密 + 保存并进入"：更新状态并持久化（MainActivity 已先落库，
     * 此处再写一次保证内存与 DB 一致），把音源切到类脑娘服务器，
     * 并立即触发连接拉取专辑列表，不等用户手动刷新。
     */
    fun applyBraingirlAuth(username: String, password: String) {
        saveBraingirlConfig(username, password)
        if (source != MusicSource.BRAINGIRL) {
            selectSource(MusicSource.BRAINGIRL)
        }
        // 自动触发连接：buildClient 读取的是刚同步的账密状态，立即发起请求
        loadAlbums()
    }

    /**
     * 保存自己的服务器配置（URL + 账密）并持久化。
     * URL 会先规范化（补协议前缀、去尾斜杠）再存储。
     */
    fun saveCustomConfig(url: String, username: String, password: String) {
        customServerUrl = SubsonicClient.normalizeBaseUrl(url)
        customUsername = username.trim()
        customPassword = password
        resetServerState()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                core.setSetting(MusicSettings.KEY_SERVER_URL, customServerUrl)
                core.setSetting(MusicSettings.KEY_SERVER_USERNAME, customUsername)
                core.setSetting(MusicSettings.KEY_SERVER_PASSWORD, customPassword)
            }
        }
    }

    /**
     * 测试当前表单填写的服务器配置是否可用（ping.view）。
     * 不落库 —— 用表单里的临时值构建客户端，成功后由"保存"负责持久化。
     *
     * @param sourceToTest 要测试的音源（BRAINGIRL 用预置 URL；CUSTOM 用表单 URL）
     * @param url 表单中的 URL（仅 CUSTOM 使用）
     * @param username 表单用户名
     * @param password 表单密码
     */
    fun testServerConfig(sourceToTest: MusicSource, url: String, username: String, password: String) {
        if (configTest.isTesting) return
        // 先在本地挡一道：字段不全直接提示，不发网络请求
        if (sourceToTest == MusicSource.CUSTOM && url.isBlank()) {
            configTest = ConfigTestState(success = false, message = "请先填写服务器地址")
            return
        }
        if (username.isBlank() || password.isBlank()) {
            configTest = ConfigTestState(success = false, message = "请填写用户名和密码")
            return
        }
        configTest = ConfigTestState(isTesting = true)
        viewModelScope.launch {
            val result = runCatching {
                val base = if (sourceToTest == MusicSource.BRAINGIRL) BRAINGIRL_SERVER_URL else url
                val client = SubsonicClient(base, username.trim(), password)
                val version = client.ping()
                ConfigTestState(success = true, message = "连接成功（Subsonic v$version）")
            }.getOrElse { e ->
                ConfigTestState(success = false, message = e.message ?: "连接失败")
            }
            configTest = result
        }
    }

    /** 清空配置测试状态（表单输入变化时调用，避免残留旧结果误导用户） */
    fun clearConfigTest() {
        configTest = ConfigTestState()
    }

    /** 清空服务器浏览状态与错误信息（配置变更 / 切换音源后调用） */
    private fun resetServerState() {
        albums = emptyList()
        currentAlbum = null
        albumSongs = emptyList()
        searchResult = null
        serverError = null
    }

    // ──────────────────────────────────────────────────────
    // 本地曲目
    // ──────────────────────────────────────────────────────

    /**
     * 扫描全机音频（MediaStore）。
     * 过滤规则：时长 >= 10 秒，排除通知音/提示音等碎片音频；
     * 本地权限被拒时返回空列表，UI 显示权限引导。
     */
    fun loadLocalTracks() {
        if (isLoadingLocal) return
        isLoadingLocal = true
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) { queryLocalTracks(app) }
            localTracks = tracks
            isLoadingLocal = false
        }
    }

    // ──────────────────────────────────────────────────────
    // 服务器浏览
    // ──────────────────────────────────────────────────────

    /**
     * 按当前音源构建 Subsonic 客户端；未配置完成返回 null。
     * 每次调用新建实例（盐随之刷新），客户端本身无状态开销极小。
     */
    private fun buildClient(): SubsonicClient? = when (source) {
        MusicSource.LOCAL -> null
        MusicSource.BRAINGIRL ->
            if (isBraingirlConfigured) {
                SubsonicClient(BRAINGIRL_SERVER_URL, braingirlUsername.trim(), braingirlPassword)
            } else null
        MusicSource.CUSTOM ->
            if (isCustomConfigured) {
                SubsonicClient(customServerUrl, customUsername.trim(), customPassword)
            } else null
    }

    /**
     * 加载专辑列表（仅在列表为空或强制刷新时请求）。
     * 由 MusicScreen 的 LaunchedEffect 在"服务器已配置"时调用。
     *
     * @param force true=忽略缓存强制刷新（下拉/重试场景）
     */
    fun loadAlbums(force: Boolean = false) {
        val client = buildClient() ?: return
        if (!force && albums.isNotEmpty()) return
        isLoadingServer = true
        serverError = null
        viewModelScope.launch {
            runCatching { client.getAlbumList2(type = "newest", size = 30) }
                .onSuccess { albums = it }
                .onFailure { serverError = it.message ?: "加载失败" }
            isLoadingServer = false
        }
    }

    /**
     * 打开专辑详情（getAlbum 拉取曲目）。
     *
     * @param album 被点击的专辑（来自专辑列表或搜索结果）
     */
    fun openAlbum(album: SubsonicAlbum) {
        val client = buildClient() ?: return
        currentAlbum = album
        isLoadingServer = true
        serverError = null
        viewModelScope.launch {
            runCatching { client.getAlbum(album.id) }
                .onSuccess { albumSongs = it.songs }
                .onFailure { serverError = it.message ?: "加载失败" }
            isLoadingServer = false
        }
    }

    /** 关闭专辑详情回到专辑列表（由 BackHandler / 返回按钮调用） */
    fun closeAlbum() {
        currentAlbum = null
        albumSongs = emptyList()
    }

    /** 执行一次 search3 搜索并记录结果（内部由 debounce collect 调用） */
    private fun performSearch(query: String) {
        val client = buildClient() ?: return
        viewModelScope.launch {
            runCatching { client.search3(query) }
                .onSuccess { searchResult = it }
                .onFailure { /* 搜索失败静默：保留旧结果，不打断输入 */ }
        }
    }

    // ──────────────────────────────────────────────────────
    // 播放控制
    // ──────────────────────────────────────────────────────

    /**
     * 以给定曲目列表为队列开始播放。
     *
     * 构建 MediaItem 时把标题/艺术家/封面 URL 写进 MediaMetadata ——
     * 通知栏 MediaStyle 通知与锁屏控件直接读取这些元数据展示，
     * 封面由 Media3 内置的 SimpleBitmapLoader 按 artworkUri 异步加载。
     *
     * @param tracks 完整队列
     * @param startIndex 点击的曲目下标（从该曲开始播放）
     */
    fun playQueue(tracks: List<PlayableTrack>, startIndex: Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map { track ->
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.playbackUri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist.ifBlank { null })
                        .setAlbumTitle(track.album.ifBlank { null })
                        .setArtworkUri(track.coverArtUrl?.let(Uri::parse))
                        .build(),
                )
                .build()
        }
        queue = tracks
        currentIndex = startIndex
        c.setMediaItems(items, startIndex, 0L)
        c.prepare()
        c.play()
    }

    /** 播放/暂停切换；队列未加载时静默忽略 */
    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** 上一首：已播超过 3 秒先回到本曲开头（常见播放器行为），否则切到上一曲 */
    fun skipToPrevious() {
        val c = controller ?: return
        if (c.currentPosition > 3000L) {
            c.seekTo(0L)
        } else {
            c.seekToPreviousMediaItem()
        }
    }

    /** 下一首 */
    fun skipToNext() {
        controller?.seekToNextMediaItem()
    }

    /**
     * 进度条拖动结束后的 seek。
     *
     * @param positionMs 目标位置（毫秒）
     */
    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
        // 立即回写一次位置，避免 500ms 轮询窗口内进度条回跳
        this.positionMs = positionMs
    }

    /** 队列列表点击某曲 → 跳转到该曲并播放 */
    fun playQueueIndex(index: Int) {
        controller?.run {
            seekTo(index, 0L)
            play()
        }
        currentIndex = index
    }

    /** 当前正在播放的曲目；空队列时为 null（UI 据此隐藏迷你播放条） */
    val currentTrack: PlayableTrack?
        get() = queue.getOrNull(currentIndex)

    /**
     * 异步构建 MediaController 并连接到 [PlaybackService]。
     * SessionToken 通过显式 ComponentName 指向服务（同进程内绑定）。
     */
    private fun connectController() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        // 主线程 Executor 回调：Compose 状态必须主线程写
        future.addListener({
            try {
                val c = future.get()
                c.addListener(playerListener)
                controller = c
            } catch (_: Exception) {
                // 服务未注册（Manifest 配置缺失）等异常：保持 controller=null，
                // 播放功能静默降级，浏览功能不受影响
            }
        }, ContextCompat.getMainExecutor(app))
    }

    override fun onCleared() {
        // 释放顺序：先移除监听 → release controller → 取消未完成的构建 Future
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture?.cancel(false)
        super.onCleared()
    }

    companion object {
        /** 类脑娘服务器预置地址 —— Navidrome（Subsonic 协议）挂载在主站 /music 路径下 */
        const val BRAINGIRL_SERVER_URL = "https://echonion.org/music"

        /** 类脑娘服务器网页注册入口 —— 配置引导表单「注册账号」按钮用 ACTION_VIEW 打开；
         *  注册完成后网页重定向 nion://musicauth 深链回传账密，App 自动完成配置 */
        const val BRAINGIRL_REGISTER_URL = "https://echonion.org/music/reg/start"

        /**
         * 查询 MediaStore 全部音频，转换为统一的 [PlayableTrack] 列表。
         *
         * 过滤：时长 >= 10s（排除提示音/录音碎片）；
         * 排序：曲名字典序（COLLATE NOCASE 忽略大小写）。
         * MediaStore 的 "<unknown>" 占位统一替换为中文"未知艺术家/未知专辑"。
         */
        private fun queryLocalTracks(app: Application): List<PlayableTrack> {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                // 专辑 ID：用于拼出专辑封面 URI（content://media/external/audio/albums/{id}），
                // 供 CoverArt 通过 loadThumbnail 提取 FLAC/MP3 内嵌封面
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
            )
            val result = mutableListOf<PlayableTrack>()
            // query 可能抛异常（权限刚回收等），捕获后返回已收集部分
            runCatching {
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
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id,
                        )
                        // 专辑封面 URI：albums 表的 content URI + albumId，
                        // loadThumbnail 会解析音频文件的内嵌封面（FLAC metadata block 等）
                        val albumId = cursor.getLong(albumIdCol)
                        val albumArtUri =
                            if (albumId > 0) android.content.ContentUris.withAppendedId(
                                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                albumId,
                            ).toString() else null
                        result.add(
                            PlayableTrack(
                                id = "local:$id",
                                title = cursor.getString(titleCol)?.ifBlank { "未知曲目" } ?: "未知曲目",
                                artist = cursor.getString(artistCol)
                                    ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                                    ?: "未知艺术家",
                                album = cursor.getString(albumCol)
                                    ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                                    ?: "未知专辑",
                                durationSeconds = cursor.getLong(durationCol) / 1000,
                                playbackUri = uri.toString(),
                                albumArtUri = albumArtUri,
                                isLocal = true,
                            ),
                        )
                    }
                }
            }
            return result
        }
    }

    /** ViewModel 工厂 —— 注入 Application 与 NionCore 单例 */
    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MusicViewModel(app, app.core()) as T
        }
    }
}

/**
 * settings 表中音乐模块持久化配置的快照 —— init 时一次性读出，
 * 避免在主线程逐条调用同步 DB 接口，也避免嵌套 Pair 解构。
 */
private data class StoredConfig(
    val source: MusicSource,
    val braingirlUsername: String,
    val braingirlPassword: String,
    val customServerUrl: String,
    val customUsername: String,
    val customPassword: String,
) {
    companion object {
        /** 从 NionCore settings 表读取全部音乐配置；缺省键回退安全默认值 */
        fun load(core: NionCore): StoredConfig = StoredConfig(
            source = MusicSource.fromKey(core.getSetting(MusicSettings.KEY_SOURCE)),
            braingirlUsername = core.getSetting(MusicSettings.KEY_BRAINGIRL_USERNAME) ?: "",
            braingirlPassword = core.getSetting(MusicSettings.KEY_BRAINGIRL_PASSWORD) ?: "",
            customServerUrl = core.getSetting(MusicSettings.KEY_SERVER_URL) ?: "",
            customUsername = core.getSetting(MusicSettings.KEY_SERVER_USERNAME) ?: "",
            customPassword = core.getSetting(MusicSettings.KEY_SERVER_PASSWORD) ?: "",
        )
    }
}

