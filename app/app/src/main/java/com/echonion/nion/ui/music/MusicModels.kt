package com.echonion.nion.ui.music

/**
 * 音乐模块的数据模型与常量定义。
 *
 * 本文件包含：
 * - [MusicSource] 音源类型枚举（本地 / 类脑娘服务器 / 自己的服务器）
 * - 统一播放曲目模型 [PlayableTrack]（本地 MediaStore 曲目与 Subsonic 服务器曲目
 *   统一成同一种模型，方便 ExoPlayer 队列复用）
 * - Subsonic API 返回的 艺术家/专辑/歌曲 数据模型
 * - 音源配置在 NionCore settings 表中的键名常量
 */

/**
 * 音乐音源类型。
 *
 * @property LOCAL 本地曲目 —— 扫描整机 MediaStore 音频
 * @property BRAINGIRL 类脑娘服务器 —— 预置的 https://echonion.org/music（Navidrome，Subsonic 协议）
 * @property CUSTOM 自己的服务器 —— 用户自行填写 URL 与账号的 Subsonic/Navidrome 服务器
 */
enum class MusicSource {
    LOCAL,
    BRAINGIRL,
    CUSTOM,
    ;

    /** 序号，用于 MusicSourcePanel 三项列表与切换动画的方向计算 */
    val order: Int get() = ordinal

    /** 持久化到 settings 表的字符串值 */
    val key: String get() = when (this) {
        LOCAL -> "local"
        BRAINGIRL -> "braingirl"
        CUSTOM -> "custom"
    }

    companion object {
        /**
         * 从 settings 表存储的字符串解析音源类型。
         *
         * @param value settings 中存储的字符串（可能为 null 或非法值）
         * @return 对应音源；无法解析时回退到 LOCAL（本地曲目永远可用，作为安全默认值）
         */
        fun fromKey(value: String?): MusicSource = when (value) {
            "braingirl" -> BRAINGIRL
            "custom" -> CUSTOM
            else -> LOCAL
        }
    }
}

/**
 * 统一的播放曲目模型 —— 本地曲目与服务器曲目共用同一结构，
 * 供播放器队列、迷你播放条、完整播放页统一消费。
 *
 * @property id 唯一标识：本地曲目为 "local:<MediaStore id>"，服务器曲目为 "subsonic:<id>"
 * @property title 曲名
 * @property artist 艺术家名
 * @property album 专辑名
 * @property durationSeconds 时长（秒）；本地 MediaStore 可能返回 0 表示未知
 * @property playbackUri ExoPlayer 实际加载的 URI：
 *   - 服务器曲目 → Subsonic stream 接口 URL（认证参数已拼在 query 中，按原始音质返回不压缩）
 *   - 本地曲目 → MediaStore 的 content:// URI
 * @property coverArtUrl 封面图 URL（Subsonic getCoverArt 接口）；服务器曲目专用，本地曲目为 null
 * @property albumArtUri 本地专辑封面 URI（content://media/external/audio/albums/{albumId}），
 *   由 MediaStore 的 ALBUM_ID 拼出，可用 ContentResolver.loadThumbnail 提取内嵌封面；
 *   服务器曲目为 null
 * @property isLocal 是否本地曲目，用于 UI 上区分图标展示
 * @property albumId 本地专辑 ID（MediaStore ALBUM_ID）—— 本地专辑聚合的分组键；服务器曲目为 0
 * @property artistId 本地艺术家 ID（MediaStore ARTIST_ID）—— 模型携带、预留后续艺术家维度；服务器曲目为 0
 * @property year 发行年份（MediaStore YEAR），0 表示未知
 * @property dateAddedSeconds 添加时间（MediaStore DATE_ADDED，Unix 秒）—— 本地专辑"最新添加"排序依据；服务器曲目为 0
 */
data class PlayableTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Long,
    val playbackUri: String,
    val coverArtUrl: String? = null,
    val albumArtUri: String? = null,
    val isLocal: Boolean = false,
    // ── 本地 MediaStore 聚合专用字段（服务器曲目保持默认值，不参与播放逻辑）──
    val albumId: Long = 0,
    val artistId: Long = 0,
    val year: Int = 0,
    val dateAddedSeconds: Long = 0,
)

/**
 * Subsonic 艺术家（getArtists / search3 返回）。
 *
 * @property id 服务器端艺术家 ID
 * @property name 艺术家名
 * @property albumCount 专辑数量
 */
data class SubsonicArtist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
)

/**
 * Subsonic 专辑（getAlbumList2 / search3 / getAlbum 返回）。
 *
 * @property id 专辑 ID
 * @property name 专辑名
 * @property artist 艺术家名
 * @property artistId 艺术家 ID（可用于跳转，当前未使用）
 * @property coverArt 封面图 ID（传给 getCoverArt 接口）
 * @property songCount 歌曲数
 * @property durationSeconds 专辑总时长（秒）
 * @property year 发行年份，0 表示未知
 */
data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String? = null,
    val coverArt: String? = null,
    /** 封面完整 URL（客户端已拼好认证参数），UI 直接用于加载；无封面 ID 时为 null */
    val coverArtUrl: String? = null,
    val songCount: Int = 0,
    val durationSeconds: Long = 0,
    val year: Int = 0,
)

/**
 * 专辑详情 —— getAlbum 返回的专辑信息 + 内含歌曲列表。
 *
 * @property album 专辑元信息
 * @property songs 专辑内的歌曲（已按 discNumber/track 排序，Subsonic 服务端保证顺序）
 */
data class SubsonicAlbumDetail(
    val album: SubsonicAlbum,
    val songs: List<PlayableTrack>,
)

/**
 * search3 搜索结果 —— 艺术家 / 专辑 / 歌曲三个维度。
 *
 * @property artists 匹配的艺术家
 * @property albums 匹配的专辑
 * @property songs 匹配的歌曲（已转换为统一的 PlayableTrack）
 */
data class SubsonicSearchResult(
    val artists: List<SubsonicArtist> = emptyList(),
    val albums: List<SubsonicAlbum> = emptyList(),
    val songs: List<PlayableTrack> = emptyList(),
)

/**
 * 统一专辑模型 —— 服务器专辑（Subsonic）与本地专辑（MediaStore 聚合）共用同一结构，
 * 专辑列表 / 专辑详情 / 搜索结果等 UI 组件因此可以跨音源复用。
 *
 * @property id 唯一标识：服务器专辑为 Subsonic id；本地专辑为 "local-<MediaStore albumId>"
 *   （本地 id 自带 "local-" 前缀，拼接出的 sharedElement morph key "music_album_local-<id>"
 *   天然与服务器专辑的 key 错开，不会撞车）
 * @property name 专辑名
 * @property artist 艺术家名（本地专辑取组内出现次数最多的艺术家，合辑场景更准确）
 * @property coverArtUrl 服务器封面完整 URL（客户端已拼好认证参数），UI 直接加载；本地专辑为 null
 * @property albumArtUri 本地专辑封面 URI（content://media/external/audio/albums/{albumId}），
 *   可用 ContentResolver.loadThumbnail 提取音频内嵌封面；服务器专辑为 null
 * @property songCount 歌曲数
 * @property durationSeconds 专辑总时长（秒）
 * @property year 发行年份，0 表示未知
 * @property isLocal 是否本地专辑（打开详情时用于区分：本地直接内存过滤、服务器走 getAlbum）
 * @property dateAddedSeconds 本地专辑"最新添加"排序依据（组内 DATE_ADDED 最大值，Unix 秒）；
 *   服务器专辑为 0（服务器侧排序由服务端 type=newest 决定）
 */
data class LibraryAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val coverArtUrl: String? = null,
    val albumArtUri: String? = null,
    val songCount: Int = 0,
    val durationSeconds: Long = 0,
    val year: Int = 0,
    val isLocal: Boolean = false,
    val dateAddedSeconds: Long = 0,
)

/**
 * Subsonic 专辑 → 统一专辑模型 [LibraryAlbum]。
 * ViewModel 加载服务器专辑列表/搜索结果时统一映射，UI 层只见 LibraryAlbum。
 */
fun SubsonicAlbum.toLibraryAlbum(): LibraryAlbum = LibraryAlbum(
    id = id,
    name = name,
    artist = artist,
    coverArtUrl = coverArtUrl,
    songCount = songCount,
    durationSeconds = durationSeconds,
    year = year,
    isLocal = false,
)

/**
 * Subsonic 歌单（getPlaylists / getPlaylist / createPlaylist 返回）。
 *
 * @property id 歌单 ID（Subsonic 原始 id，直接作为统一模型 [LibraryPlaylist] 的 id）
 * @property name 歌单名称
 * @property songCount 歌单内曲目数
 * @property durationSeconds 歌单总时长（秒）
 * @property comment 策展说明 —— 对应 Navidrome updatePlaylist 的 comment 参数，
 *   用于记录歌单的创作背景/说明文案
 * @property public 是否对其他用户公开（Navidrome 多用户场景）
 * @property owner 歌单归属的用户名；部分服务端可能缺省为空串
 */
data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val songCount: Int = 0,
    val durationSeconds: Int = 0,
    val comment: String = "",
    val public: Boolean = false,
    val owner: String = "",
)

/**
 * 统一歌单模型 —— 服务器歌单（Subsonic）与本地歌单（Nion 自建表）共用同一结构，
 * 歌单列表 / 歌单详情等 UI 组件因此可以跨音源复用。
 *
 * @property id 唯一标识：服务器歌单为 Subsonic 原始 id；本地歌单为 "local-<nion内自增id>"
 *   （与专辑 local- 前缀风格一致，但取值空间独立，不会与本地专辑 id 混淆）
 * @property name 歌单名称
 * @property comment 策展说明：服务器侧对应 Navidrome updatePlaylist 的 comment 参数；
 *   本地侧存于本地歌单曲的 comment 列
 * @property songCount 歌单内曲目数
 * @property durationSeconds 歌单总时长（秒）
 * @property isLocal 是否本地歌单（打开详情/增删曲目时用于区分走本地表还是服务器接口）
 */
data class LibraryPlaylist(
    val id: String,
    val name: String,
    val comment: String,
    val songCount: Int,
    val durationSeconds: Int,
    val isLocal: Boolean,
    // 歌单封面：服务器歌单 = getCoverArt?pl-<id>（Navidrome 服务端拼贴）；
    // 本地歌单无服务端封面 → null，UI 用与专辑行同款占位块
    val coverArtUrl: String? = null,
    // 归属用户名：服务器歌单 = Subsonic owner 字段（空串归一化为 null）；
    // 本地歌单恒为 null。可见性过滤与未来的公共歌单分组都以它为依据
    val owner: String? = null,
)

/**
 * Subsonic 歌单 → 统一歌单模型 [LibraryPlaylist]。
 * ViewModel 加载服务器歌单列表时统一映射，UI 层只见 LibraryPlaylist；
 * 服务器歌单 id 直接使用 Subsonic 原始 id，isLocal 恒为 false。
 */
fun SubsonicPlaylist.toLibraryPlaylist(): LibraryPlaylist = LibraryPlaylist(
    id = id,
    name = name,
    comment = comment,
    // 归属用户名透传：空串（服务端缺省）归一化为 null，配合可见性过滤的兜底规则
    owner = owner.takeIf { it.isNotBlank() },
    songCount = songCount,
    durationSeconds = durationSeconds,
    isLocal = false,
)

/**
 * 统一搜索结果 —— 歌曲在前、专辑在后，本地与服务器音源共用。
 * 与 [SubsonicSearchResult] 的区别：专辑维度已映射为统一的 [LibraryAlbum]，
 * 服务器 search3 结果经 [toLibrarySearchResult] 转换，本地内存搜索直接产出本结构。
 *
 * @property songs 匹配的歌曲（统一 PlayableTrack）
 * @property albums 匹配的专辑（统一 LibraryAlbum）
 */
data class LibrarySearchResult(
    val songs: List<PlayableTrack> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
)

/** Subsonic search3 结果 → 统一搜索结果（艺术家维度当前 UI 未消费，转换时丢弃） */
fun SubsonicSearchResult.toLibrarySearchResult(): LibrarySearchResult = LibrarySearchResult(
    songs = songs,
    albums = albums.map { it.toLibraryAlbum() },
)

/**
 * 本地专辑排序方式。
 *
 * @property label 排序切换 Chip 的展示文案
 */
enum class LocalAlbumSort(val label: String) {
    /** 默认：专辑名字典序（忽略大小写） */
    TITLE("默认排序"),

    /** 最新添加：按组内 DATE_ADDED 最大值倒序 */
    NEWEST("最新添加"),
}

/**
 * 音乐模块在 NionCore settings 表中使用的键名常量。
 *
 * 说明：账号密码以明文存储在本机 SQLite（与其他设置项一致，App 不联网上报），
 * 数据库文件位于应用私有目录，无 root 不可读。
 */
object MusicSettings {
    /** 当前选中的音源（"local" / "braingirl" / "custom"） */
    const val KEY_SOURCE = "music_source"

    /** 类脑娘服务器的用户名 */
    const val KEY_BRAINGIRL_USERNAME = "music_braingirl_username"
    /** 类脑娘服务器的密码 */
    const val KEY_BRAINGIRL_PASSWORD = "music_braingirl_password"

    /** 自己的服务器的 Base URL（如 https://music.example.com） */
    const val KEY_SERVER_URL = "music_server_url"
    /** 自己的服务器的用户名 */
    const val KEY_SERVER_USERNAME = "music_server_username"
    /** 自己的服务器的密码 */
    const val KEY_SERVER_PASSWORD = "music_server_password"
}

/**
 * 格式化曲目/专辑时长为 "m:ss" 或 "h:mm:ss" 形式。
 *
 * @param seconds 时长秒数
 * @return 如 "3:45"、"1:02:30"；seconds<=0 时返回 "0:00"
 */
fun formatTrackDuration(seconds: Long): String {
    if (seconds <= 0) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        // 小时场景补零保持列宽对齐，如 "1:02:30"
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}
