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
