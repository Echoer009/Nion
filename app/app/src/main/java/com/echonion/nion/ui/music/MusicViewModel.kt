package com.echonion.nion.ui.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import com.echonion.nion.core
import com.echonion.nion.dataEvents
import com.echonion.nion.ui.companion.tools.DataType
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
 * 3. 曲库浏览状态（专辑列表 / 专辑详情 / 搜索）—— 本地 MediaStore 聚合与服务器
 *    Subsonic 统一成 [LibraryAlbum] 模型，UI 组件跨音源复用
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

    /** 本地扫描错误信息；非空时 UI 显示错误条，点击重试可清除并强制重查 */
    var localError by mutableStateOf<String?>(null)
        private set

    /** 本地曲目按 ALBUM_ID 聚合出的专辑列表（统一模型，默认按专辑名排序） */
    var localAlbums by mutableStateOf<List<LibraryAlbum>>(emptyList())
        private set

    /** 本地专辑排序方式（默认名称序；仅本地音源展示排序切换；公开 setter 供 UI 排序 Chip 直接赋值） */
    var localAlbumSort by mutableStateOf(LocalAlbumSort.TITLE)

    /** 当前排序下的本地专辑列表 —— 读取聚合结果并按 [localAlbumSort] 现算，排序切换即触发重组 */
    val sortedLocalAlbums: List<LibraryAlbum>
        get() = when (localAlbumSort) {
            LocalAlbumSort.TITLE -> localAlbums
            LocalAlbumSort.NEWEST -> localAlbums.sortedByDescending { it.dateAddedSeconds }
        }

    // ──────────────────────────────────────────────────────
    // 服务器浏览状态
    // ──────────────────────────────────────────────────────

    /** 专辑列表（getAlbumList2 newest；已映射为统一模型 [LibraryAlbum]） */
    var albums by mutableStateOf<List<LibraryAlbum>>(emptyList())
        private set

    /** 当前打开的专辑（null=专辑列表视图；非 null=专辑详情视图；本地/服务器共用） */
    var currentAlbum by mutableStateOf<LibraryAlbum?>(null)
        private set

    /** 当前专辑的曲目列表 */
    var albumSongs by mutableStateOf<List<PlayableTrack>>(emptyList())
        private set

    /** 搜索关键词 —— UI 输入框直接写入，VM 内部 debounce 后分发：服务器走 search3，本地走内存过滤 */
    var searchQuery by mutableStateOf("")

    /** 最近一次搜索结果（null=无搜索；统一模型，本地/服务器共用） */
    var searchResult by mutableStateOf<LibrarySearchResult?>(null)
        private set

    /** 服务器数据是否加载中（专辑列表/专辑详情共用一个标志） */
    var isLoadingServer by mutableStateOf(false)
        private set

    /** 服务器请求错误信息；非空时 UI 显示错误条，点击重试可清除 */
    var serverError by mutableStateOf<String?>(null)

    // ──────────────────────────────────────────────────────
    // 歌单状态（本地 / 服务器统一模型，跨音源复用）
    // ──────────────────────────────────────────────────────

    /**
     * 当前音源的歌单列表（统一模型 [LibraryPlaylist]）。
     * 云端 = getPlaylists 映射；本地 = core.list_local_playlists 拼上 "local-" 前缀。
     */
    var playlists by mutableStateOf<List<LibraryPlaylist>>(emptyList())
        private set

    /** 当前打开的歌单（null=歌单列表视图；非 null=歌单详情视图；本地/服务器共用） */
    var currentPlaylist by mutableStateOf<LibraryPlaylist?>(null)
        private set

    /** 当前歌单的曲目列表（云端=getPlaylist 返回；本地=按 core 条目在 localTracks 里回查） */
    var playlistTracks by mutableStateOf<List<PlayableTrack>>(emptyList())
        private set

    /** 歌单数据是否加载中（列表/详情共用一个标志） */
    var isLoadingPlaylists by mutableStateOf(false)
        private set

    /** 歌单操作错误信息；非空时 UI 显示错误条，点击重试可清除 */
    var playlistError by mutableStateOf<String?>(null)
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

    /** 完整播放页是否展开（跨组件共享：MusicScreen 读写，NionApp 据此隐藏底部导航） */
    var isFullPlayerOpen by mutableStateOf(false)

    /** 播放页当前沉浸背景色（封面取色结果）；NionApp 据此给底部导航栏染色。播放页关闭时置 null */
    var playerBackdrop by mutableStateOf<PlayerBackdropColors?>(null)

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
        // 5. 订阅全局数据变更事件：伙伴侧（MusicTool）增删歌单曲目后发出 MUSIC_DATA，
        //    这里强制重载歌单列表，保证 UI 实时刷新
        viewModelScope.launch {
            app.dataEvents().collect { event ->
                if (DataType.MUSIC_DATA in event.types) {
                    loadPlaylists(force = true)
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
        // 音源切换后歌单列表对新音源重载（loadPlaylists 有"已有数据不重查"保护，force 绕过）
        loadPlaylists(force = true)
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
        // 账密变更意味着另一个账号的歌单，强制重载
        loadPlaylists(force = true)
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
        // 服务器配置变更后强制重载歌单列表
        loadPlaylists(force = true)
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

    /**
     * 清空服务器浏览状态与错误信息（配置变更 / 切换音源后调用）。
     * 歌单状态一并清空：两个音源的歌单互不可见，清空后重新 loadPlaylists。
     */
    private fun resetServerState() {
        albums = emptyList()
        currentAlbum = null
        albumSongs = emptyList()
        searchResult = null
        serverError = null
        playlists = emptyList()
        currentPlaylist = null
        playlistTracks = emptyList()
        playlistError = null
    }

    // ──────────────────────────────────────────────────────
    // 本地曲目
    // ──────────────────────────────────────────────────────

    /**
     * 扫描全机音频（MediaStore）并聚合成专辑列表。
     * 过滤规则：时长 >= 10 秒，排除通知音/提示音等碎片音频；
     * 扫描抛异常（权限被回收等）时写入 [localError]，UI 显示错误条供点击重试。
     *
     * @param force true=忽略"已有数据不重查"保护强制重查（刷新/重试场景）
     */
    fun loadLocalTracks(force: Boolean = false) {
        if (isLoadingLocal) return
        // 与 loadAlbums 一致的缓存保护：已有数据且非强制时不重查
        if (!force && localTracks.isNotEmpty()) return
        isLoadingLocal = true
        localError = null
        viewModelScope.launch {
            // queryLocalTracks 不吞异常：失败向上抛到这里统一捕获，
            // 成功则同时刷新曲目列表与聚合出的专辑列表
            runCatching { withContext(Dispatchers.IO) { queryLocalTracks(app) } }
                .onSuccess { tracks ->
                    localTracks = tracks
                    localAlbums = buildLocalAlbums(tracks)
                    // 曲目库更新后，若正处于本地搜索态则同步重算结果
                    if (source == MusicSource.LOCAL && searchQuery.isNotBlank()) {
                        performLocalSearch(searchQuery)
                    }
                }
                .onFailure { localError = it.message ?: "扫描本地音乐失败" }
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
                // 服务器专辑统一映射成 LibraryAlbum，UI 层不感知 Subsonic 类型
                .onSuccess { albums = it.map { album -> album.toLibraryAlbum() } }
                .onFailure { serverError = it.message ?: "加载失败" }
            isLoadingServer = false
        }
    }

    /**
     * 打开专辑详情 —— 按专辑来源分发：
     * - 本地专辑：曲目已在内存（聚合来源即 localTracks），直接按 ALBUM_ID
     *   过滤出该专辑的曲目作为详情列表与播放队列，同步完成无网络请求
     * - 服务器专辑：getAlbum 拉取曲目（服务端已按音轨号排序）
     *
     * @param album 被点击的专辑（来自专辑列表或搜索结果，统一模型）
     */
    fun openAlbum(album: LibraryAlbum) {
        if (album.isLocal) {
            currentAlbum = album
            val albumId = album.id.removePrefix(LOCAL_ALBUM_ID_PREFIX).toLongOrNull()
            albumSongs = if (albumId != null) {
                localTracks.filter { it.albumId == albumId }
            } else {
                emptyList()
            }
            return
        }
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

    /**
     * 执行一次搜索并记录结果（内部由 debounce collect 调用）。
     * 按音源分发：本地走内存过滤，服务器走 search3。
     */
    private fun performSearch(query: String) {
        if (source == MusicSource.LOCAL) {
            performLocalSearch(query)
            return
        }
        val client = buildClient() ?: return
        viewModelScope.launch {
            runCatching { client.search3(query) }
                // 统一映射：专辑维度转 LibraryAlbum，艺术家维度当前 UI 未消费
                .onSuccess { searchResult = it.toLibrarySearchResult() }
                .onFailure { /* 搜索失败静默：保留旧结果，不打断输入 */ }
        }
    }

    /**
     * 本地内存搜索：不发起任何请求，直接在 [localTracks]/[localAlbums] 上做包含匹配 ——
     * 曲目按 标题/艺术家/专辑名，专辑按 专辑名/艺术家；结果歌在前、专辑在后，
     * 与服务器搜索走同一个 SearchResultContent 展示。
     */
    private fun performLocalSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchResult = null
            return
        }
        val songs = localTracks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
        val albums = localAlbums.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true)
        }
        searchResult = LibrarySearchResult(songs = songs, albums = albums)
    }

    /**
     * 进入本地音源时补一次本地搜索刷新：searchQuery 为三音源共享状态，
     * 从服务器切回本地时关键词未变化、debounce 不会重发，这里手动重算
     * 保证搜索框内容与结果一致。
     */
    fun refreshLocalSearch() {
        if (source == MusicSource.LOCAL && searchQuery.isNotBlank()) {
            performLocalSearch(searchQuery)
        }
    }


    // ──────────────────────────────────────────────────────
    // 歌单管理（按当前音源分发：云端走 Subsonic 接口，本地走 NionCore 歌单曲）
    // ──────────────────────────────────────────────────────

    /** 从统一歌单 id 解析本地歌单的库内自增 id（"local-<id>" → id）；非本地 id 返回 null */
    private fun localPlaylistDbId(id: String): Long? = id.removePrefix(LOCAL_PLAYLIST_ID_PREFIX).toLongOrNull()

    /**
     * 加载当前音源的歌单列表。
     * - 云端：getPlaylists → 统一映射为 [LibraryPlaylist]
     * - 本地：core.list_local_playlists，id 拼 "local-" 前缀，时长未知记 0
     *
     * @param force true=忽略"已有数据不重查"保护强制刷新
     */
    fun loadPlaylists(force: Boolean = false) {
        if (isLoadingPlaylists) return
        if (!force && playlists.isNotEmpty()) return
        if (source == MusicSource.LOCAL) {
            isLoadingPlaylists = true
            playlistError = null
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { core.listLocalPlaylists() } }
                    .onSuccess { records ->
                        playlists = records.map { rec ->
                            LibraryPlaylist(
                                id = "$LOCAL_PLAYLIST_ID_PREFIX${rec.id}",
                                name = rec.name,
                                comment = rec.comment,
                                songCount = rec.songCount.toInt(),
                                durationSeconds = 0,
                                isLocal = true,
                            )
                        }
                    }
                    .onFailure { playlistError = it.message ?: "加载本地歌单失败" }
                isLoadingPlaylists = false
            }
            return
        }
        val client = buildClient() ?: return
        isLoadingPlaylists = true
        playlistError = null
        viewModelScope.launch {
            runCatching { client.getPlaylists() }
                .onSuccess { playlists = it.map { p -> p.toLibraryPlaylist() } }
                .onFailure { playlistError = it.message ?: "加载歌单失败" }
            isLoadingPlaylists = false
        }
    }

    /**
     * 打开歌单详情 —— 按歌单来源分发：
     * - 本地：localPlaylistTracks 拿到 (position, trackId) 条目，trackId 为 MediaStore _ID，
     *   在内存 [localTracks] 里按 "local:<id>" 匹配出 [PlayableTrack]，保持 position 序；
     *   本地表不存时长，详情头部的曲目数/总时长按匹配结果现算
     * - 服务器：getPlaylist 直接返回歌单元信息 + 完整曲目（服务端保证添加顺序）
     *
     * @param id 统一歌单 id（云端=Subsonic id；本地="local-<库内id>"）
     */
    fun openPlaylist(id: String) {
        val localDbId = localPlaylistDbId(id)
        if (localDbId != null) {
            // 先从已加载的列表里找到歌单元信息（详情头部展示用）
            currentPlaylist = playlists.firstOrNull { it.id == id }
            playlistTracks = emptyList()
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { core.localPlaylistTracks(localDbId) } }
                    .onSuccess { items ->
                        // MediaStore _ID → 内存曲目映射；查不到的条目（文件已删除）静默跳过
                        val byMediaId = localTracks.associateBy { it.id.removePrefix("local:").toLongOrNull() }
                        val tracks = items
                            .sortedBy { it.position }
                            .mapNotNull { byMediaId[it.trackId] }
                        playlistTracks = tracks
                        // 本地记录不带头部统计，按匹配结果补齐曲目数与总时长
                        val base = currentPlaylist ?: LibraryPlaylist(
                            id = id,
                            name = "本地歌单",
                            comment = "",
                            songCount = tracks.size,
                            durationSeconds = 0,
                            isLocal = true,
                        )
                        currentPlaylist = base.copy(
                            songCount = tracks.size,
                            durationSeconds = tracks.sumOf { it.durationSeconds }.toInt(),
                        )
                    }
                    .onFailure { playlistError = it.message ?: "加载歌单曲目失败" }
            }
            return
        }
        val client = buildClient() ?: return
        currentPlaylist = playlists.firstOrNull { it.id == id }
        playlistTracks = emptyList()
        isLoadingPlaylists = true
        playlistError = null
        viewModelScope.launch {
            runCatching { client.getPlaylist(id) }
                .onSuccess { (meta, songs) ->
                    currentPlaylist = meta.toLibraryPlaylist()
                    playlistTracks = songs
                }
                .onFailure { playlistError = it.message ?: "加载歌单失败" }
            isLoadingPlaylists = false
        }
    }

    /** 关闭歌单详情回到歌单列表（由 BackHandler / 返回按钮调用） */
    fun closePlaylist() {
        currentPlaylist = null
        playlistTracks = emptyList()
    }

    /**
     * 新建空歌单 —— 云端 createPlaylist(name, 空曲目) 先建空单；本地 create_local_playlist。
     * 完成后强制重载歌单列表。
     *
     * @param name 歌单名称
     * @param comment 策展说明（云端 createPlaylist 不支持 comment，建单后补一次
     *   updatePlaylist 写入；本地建单时直接落库）
     */
    fun createPlaylist(name: String, comment: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                if (source == MusicSource.LOCAL) {
                    withContext(Dispatchers.IO) { core.createLocalPlaylist(name.trim(), comment) }
                } else {
                    val client = buildClient() ?: return@launch
                    val playlistId = client.createPlaylist(name.trim(), emptyList())
                    // Subsonic createPlaylist 不带 comment 参数，建单后补一次更新写入说明
                    if (comment.isNotBlank()) {
                        client.updatePlaylist(playlistId, null, comment, emptyList(), emptyList())
                    }
                }
            }.onSuccess {
                loadPlaylists(force = true)
            }.onFailure { playlistError = it.message ?: "新建歌单失败" }
        }
    }

    /**
     * 删除歌单 —— 云端 deletePlaylist；本地 delete_local_playlist。完成后重载列表，
     * 若删除的正是当前打开的歌单则同时回到列表视图。
     *
     * @param id 统一歌单 id
     */
    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            runCatching {
                val localDbId = localPlaylistDbId(id)
                if (localDbId != null) {
                    withContext(Dispatchers.IO) { core.deleteLocalPlaylist(localDbId) }
                } else {
                    val client = buildClient() ?: return@launch
                    client.deletePlaylist(id)
                }
            }.onSuccess {
                if (currentPlaylist?.id == id) closePlaylist()
                loadPlaylists(force = true)
            }.onFailure { playlistError = it.message ?: "删除歌单失败" }
        }
    }

    /**
     * 重命名/修改歌单说明 —— 云端 updatePlaylist（缺省参数保持原值语义）；
     * 本地 rename_local_playlist（本地接口 name 与 comment 必传，未提供的字段
     * 以列表/详情里的现值兜底）。完成后重载列表并同步当前打开歌单的元信息。
     *
     * @param id 统一歌单 id
     * @param name 新名称；云端传 null 表示不改名
     * @param comment 新说明；云端传 null 表示不修改
     */
    fun renamePlaylist(id: String, name: String?, comment: String?) {
        viewModelScope.launch {
            runCatching {
                val localDbId = localPlaylistDbId(id)
                if (localDbId != null) {
                    // 本地接口 name/comment 必传：未提供的字段以列表/详情里的现值兜底
                    val base = playlists.firstOrNull { it.id == id } ?: currentPlaylist
                    val newName = name?.trim()?.takeIf { it.isNotEmpty() } ?: base?.name ?: ""
                    val newComment = comment ?: base?.comment ?: ""
                    withContext(Dispatchers.IO) {
                        core.renameLocalPlaylist(localDbId, newName, newComment)
                    }
                } else {
                    val client = buildClient() ?: return@launch
                    client.updatePlaylist(id, name, comment, emptyList(), emptyList())
                }
            }.onSuccess {
                loadPlaylists(force = true)
                // 立即同步当前打开歌单的头部元信息（loadPlaylists 异步完成，避免头部滞后）
                currentPlaylist = currentPlaylist?.let { open ->
                    open.copy(
                        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: open.name,
                        comment = comment ?: open.comment,
                    )
                }
            }.onFailure { playlistError = it.message ?: "修改歌单失败" }
        }
    }

    /**
     * 追加曲目到歌单末尾 —— 云端 updatePlaylist(songIdsToAdd)；
     * 本地逐个 add_track_to_local_playlist（保持传入顺序即添加顺序）。
     * 完成后重载列表；若目标是当前打开的歌单则同步刷新详情曲目。
     *
     * @param id 统一歌单 id
     * @param trackIds 要追加的曲目 id 列表（[PlayableTrack.id]：云端 "subsonic:<songId>"、
     *   本地 "local:<MediaStore _ID>"，分发前在此剥掉来源前缀）
     */
    fun addToPlaylist(id: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val localDbId = localPlaylistDbId(id)
                if (localDbId != null) {
                    withContext(Dispatchers.IO) {
                        trackIds.forEach { trackId ->
                            val mediaId = trackId.removePrefix("local:").toLongOrNull() ?: return@forEach
                            core.addTrackToLocalPlaylist(localDbId, mediaId)
                        }
                    }
                } else {
                    val client = buildClient() ?: return@launch
                    client.updatePlaylist(
                        id,
                        null,
                        null,
                        trackIds.map { it.removePrefix("subsonic:") },
                        emptyList(),
                    )
                }
            }.onSuccess {
                loadPlaylists(force = true)
                if (currentPlaylist?.id == id) openPlaylist(id)
            }.onFailure { playlistError = it.message ?: "添加曲目失败" }
        }
    }

    /**
     * 从歌单移除指定下标的曲目 —— 云端 updatePlaylist(songIndexesToRemove)（0 基下标）；
     * 本地 remove_track_from_local_playlist（position 从 1 起，故传 index+1）。
     * 完成后刷新列表与详情。
     *
     * @param id 统一歌单 id
     * @param index 曲目在歌单内的 0 基下标（对应 [playlistTracks] 的下标）
     */
    fun removePlaylistTrack(id: String, index: Int) {
        viewModelScope.launch {
            runCatching {
                val localDbId = localPlaylistDbId(id)
                if (localDbId != null) {
                    withContext(Dispatchers.IO) { core.removeTrackFromLocalPlaylist(localDbId, index.toLong() + 1) }
                } else {
                    val client = buildClient() ?: return@launch
                    client.updatePlaylist(id, null, null, emptyList(), listOf(index))
                }
            }.onSuccess {
                loadPlaylists(force = true)
                if (currentPlaylist?.id == id) openPlaylist(id)
            }.onFailure { playlistError = it.message ?: "移除曲目失败" }
        }
    }

    /** 播放整个当前歌单：以 [playlistTracks] 为队列从第一首开始 */
    fun playPlaylist() {
        playQueue(playlistTracks, 0)
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


        /** 本地歌单统一 id 前缀：id 形如 "local-<库内自增id>"，morph key 因此与云端歌单错开 */
        const val LOCAL_PLAYLIST_ID_PREFIX = "local-"
        /** 本地专辑统一 id 前缀：id 形如 "local-<albumId>"，morph key 因此与服务器专辑错开 */
        const val LOCAL_ALBUM_ID_PREFIX = "local-"

        /**
         * 查询 MediaStore 全部音频，转换为统一的 [PlayableTrack] 列表。
         *
         * 过滤：时长 >= 10s（排除提示音/录音碎片）；
         * 排序：曲名字典序（COLLATE NOCASE 忽略大小写）。
         * MediaStore 的 "<unknown>" 占位统一替换为中文"未知艺术家/未知专辑"。
         * 抛出的异常（权限刚回收等）向上传给 [loadLocalTracks] 统一捕获，
         * 由 UI 显示错误条 + 点击重试，不再静默吞掉。
         */
        private fun queryLocalTracks(app: Application): List<PlayableTrack> {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                // 专辑 ID：用于拼出专辑封面 URI（content://media/external/audio/albums/{id}），
                // 供 CoverArt 通过 loadThumbnail 提取 FLAC/MP3 内嵌封面；
                // 同时是本地专辑聚合的分组键
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                // 发行年份：聚合进专辑模型，详情头部展示 "年份 · N 首 · 总时长"
                MediaStore.Audio.Media.YEAR,
                // 艺术家 ID：随模型携带，预留给后续艺术家维度浏览
                MediaStore.Audio.Media.ARTIST_ID,
                // 添加时间（Unix 秒）：本地专辑"最新添加"排序依据（组内取最大值）
                MediaStore.Audio.Media.DATE_ADDED,
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
                val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val artistIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
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
                            // ── 本地聚合专用字段 ──
                            albumId = albumId,
                            artistId = cursor.getLong(artistIdCol),
                            year = cursor.getInt(yearCol),
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                        ),
                    )
                }
            }
            return result
        }

        /**
         * 本地曲目按 ALBUM_ID 聚合为统一专辑列表：
         * - name：组内第一首的专辑名（同一 ALBUM_ID 的专辑名一致）
         * - artist：组内出现次数最多的艺术家（众数，合辑场景更准确）
         * - year：组内第一个非零年份（0=未知）
         * - 封面：组内第一个非空 albumArtUri
         * - songCount / durationSeconds：组内计数 / 求和
         * - dateAddedSeconds：组内最大值，"最新添加"排序依据
         * 输出按专辑名排序（忽略大小写），即默认 TITLE 序。
         */
        private fun buildLocalAlbums(tracks: List<PlayableTrack>): List<LibraryAlbum> =
            tracks.groupBy { it.albumId }.map { (albumId, group) ->
                LibraryAlbum(
                    id = "$LOCAL_ALBUM_ID_PREFIX$albumId",
                    name = group.first().album,
                    artist = group.groupingBy { it.artist }.eachCount()
                        .maxByOrNull { it.value }?.key ?: "未知艺术家",
                    albumArtUri = group.firstOrNull { it.albumArtUri != null }?.albumArtUri,
                    songCount = group.size,
                    durationSeconds = group.sumOf { it.durationSeconds },
                    year = group.firstOrNull { it.year > 0 }?.year ?: 0,
                    isLocal = true,
                    dateAddedSeconds = group.maxOf { it.dateAddedSeconds },
                )
            }.sortedBy { it.name.lowercase() }
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

