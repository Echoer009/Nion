package com.echonion.nion.ui.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.content.Context
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Subsonic/Navidrome API 客户端 —— 基于 OkHttp 实现的 Subsonic 协议子集。
 *
 * 实现的接口（均以 GET 请求 + JSON 响应方式调用，路径前缀 /rest/）：
 * - ping.view              连通性 / 账密校验
 * - getArtists.view        全部艺术家目录
 * - getAlbumList2.view     专辑列表（type=newest/recent/frequent 等）
 * - getAlbum.view          专辑详情 + 曲目列表
 * - search3.view           三维度搜索（艺术家/专辑/歌曲）
 * - getCoverArt.view       封面图 URL 拼接（UI 侧 OkHttp 下载解码）
 * - stream.view            音频流（不压缩，按原始音质返回，URL 直接交给 ExoPlayer 播放）
 *
 * 认证方式：Subsonic 1.13+ 推荐的 token 认证 ——
 *   u=用户名, s=随机盐, t=md5(密码+盐), v=1.16.1, c=nion, f=json
 * 盐在客户端实例创建时生成一次并复用，避免每次请求重新计算 token。
 *
 * 所有网络调用均为 suspend 函数，内部切换到 [Dispatchers.IO] 执行阻塞 IO；
 * 响应统一走 [SubsonicClient.parseEnvelope] 校验 subsonic-response.status。
 *
 * @property baseUrl 服务器根地址（如 https://echonion.org/music），构造时会去掉末尾斜杠
 * @property username 登录用户名
 * @property password 登录密码（明文，仅用于本地计算 md5 token，不会出现在 URL 中）
 */
class SubsonicClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
) {
    // HTTP 客户端见 companion.sharedHttpClient：必须全 App 唯一 —— 每个
    // SubsonicClient 实例各建一个 OkHttpClient 的话，连接池互不相通，
    // 每次 API 调用都重新 TCP+TLS 握手（实测慢 100~300ms，之前 buildClient()
    // 每次调用都 new 客户端，正是"打开歌单/专辑偏慢"的元凶之一）

    /** 规范化后的服务器根 URL（保证以 http(s):// 开头、不以 / 结尾） */
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    /** 认证盐：12 位随机 hex 字符串，实例生命周期内固定 */
    private val salt: String = generateSalt()

    /** 认证 token：md5(password + salt) 的 hex 字符串 */
    private val token: String = md5Hex(password + salt)

    /**
     * 校验服务器连通性与账密是否正确。
     *
     * @return 成功时返回服务器 Subsonic 协议版本号（如 "1.16.1"）
     * @throws SubsonicException 网络失败、URL 非法或账密错误（status=failed）
     */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        val body = execute("ping.view")
        // ping 成功响应的 envelope 内必带 version 字段
        body.getString("version")
    }

    /**
     * 获取全部艺术家目录（当前 UI 未直接使用，保留给后续"按艺术家浏览"功能，
     * 同时也作为账密校验的手段之一）。
     *
     * @return 扁平化后的艺术家列表（跨所有首字母索引）
     */
    suspend fun getArtists(): List<SubsonicArtist> = withContext(Dispatchers.IO) {
        val body = execute("getArtists.view")
        // 结构：artists.index[]（按首字母分组）.artist[]
        val indexes = body.getJSONObject("artists").optJSONArray("index") ?: return@withContext emptyList()
        val result = mutableListOf<SubsonicArtist>()
        for (i in 0 until indexes.length()) {
            val artists = indexes.getJSONObject(i).optJSONArray("artist") ?: continue
            for (j in 0 until artists.length()) {
                val a = artists.getJSONObject(j)
                result.add(
                    SubsonicArtist(
                        id = a.getString("id"),
                        name = a.getString("name"),
                        albumCount = a.optInt("albumCount", 0),
                    ),
                )
            }
        }
        result
    }

    /**
     * 获取专辑列表（getAlbumList2）。
     *
     * @param type 排序类型：newest=最新添加, recent=最近播放, frequent=最常播放
     * @param size 返回条数上限（Subsonic 默认 10，最大一般 500）
     * @return 专辑列表（不含歌曲明细）
     */
    suspend fun getAlbumList2Raw(type: String, size: Int = 30): JSONObject =
        withContext(Dispatchers.IO) {
            execute("getAlbumList2.view", "type" to type, "size" to size.toString())
        }

    /** 解析 getAlbumList2 响应 —— 网络结果与磁盘缓存走同一解析路径 */
    internal fun parseAlbumListResponse(body: JSONObject): List<SubsonicAlbum> {
        // albumList2.album 可能缺省（无专辑时），回退空列表
        val albums = body.optJSONObject("albumList2")?.optJSONArray("album")
            ?: return emptyList()
        return parseAlbumArray(albums)
    }

    /**
     * 获取专辑详情（含曲目列表）。
     *
     * @param albumId 专辑 ID（来自 getAlbumList2 / search3）
     * @return 专辑元信息 + 已转换为 [PlayableTrack] 的歌曲列表
     */
    suspend fun getAlbumRaw(albumId: String): JSONObject = withContext(Dispatchers.IO) {
        execute("getAlbum.view", "id" to albumId)
    }

    /** 解析 getAlbum 响应（专辑元信息 + 曲目列表）—— 网络与磁盘缓存共用 */
    internal fun parseAlbumResponse(body: JSONObject): SubsonicAlbumDetail {
        val albumJson = body.getJSONObject("album")
        val album = parseAlbum(albumJson)
        // 专辑内歌曲：song 数组，服务端已按音轨号排序
        val songsJson = albumJson.optJSONArray("song") ?: return SubsonicAlbumDetail(album, emptyList())
        val songs = (0 until songsJson.length()).map { i ->
            parseSong(songsJson.getJSONObject(i))
        }
        return SubsonicAlbumDetail(album, songs)
    }

    /**
     * 三维度搜索（search3）。
     *
     * @param query 搜索关键词（歌名/歌手/专辑任一匹配）
     * @param songCount 歌曲结果数上限
     * @param albumCount 专辑结果数上限
     * @param artistCount 艺术家结果数上限
     */
    suspend fun search3(
        query: String,
        songCount: Int = 30,
        albumCount: Int = 12,
        artistCount: Int = 8,
    ): SubsonicSearchResult = withContext(Dispatchers.IO) {
        val body = execute(
            "search3.view",
            "query" to query,
            "songCount" to songCount.toString(),
            "albumCount" to albumCount.toString(),
            "artistCount" to artistCount.toString(),
        )
        val resultJson = body.optJSONObject("searchResult3")

        val artists = resultJson?.optJSONArray("artist")?.let { arr ->
            (0 until arr.length()).map {
                val a = arr.getJSONObject(it)
                SubsonicArtist(id = a.getString("id"), name = a.getString("name"))
            }
        } ?: emptyList()

        val albums = resultJson?.optJSONArray("album")?.let { parseAlbumArray(it) } ?: emptyList()

        val songs = resultJson?.optJSONArray("song")?.let { arr ->
            (0 until arr.length()).map { parseSong(arr.getJSONObject(it)) }
        } ?: emptyList()

        SubsonicSearchResult(artists = artists, albums = albums, songs = songs)
    }

    /**
     * 构造封面图 URL —— 不发请求，仅拼 URL 供封面加载器使用。
     *
     * @param coverArtId 封面 ID（专辑/歌曲记录中的 coverArt 字段）
     * @param size 目标边长（像素），服务器负责缩放，减小流量
     */
    fun coverArtUrl(coverArtId: String, size: Int = 300): String =
        buildUrl("getCoverArt.view", "id" to coverArtId, "size" to size.toString())

    /**
     * 构造音频流 URL —— ExoPlayer 直接加载该 URL 播放。
     * 不传 maxBitRate 参数，让服务器按原始音质返回（不转码、不压缩）。
     *
     * @param songId 歌曲 ID
     */
    fun streamUrl(songId: String): String =
        buildUrl("stream.view", "id" to songId)

    /**
     * 获取当前用户可见的全部歌单（getPlaylists，Subsonic API 1.0+，Navidrome 全版本支持）。
     * 返回内容包括自己创建的与其他用户公开的歌单（owner 字段区分归属）。
     *
     * @return 歌单列表（不含内含曲目明细，只有 songCount/duration 等汇总信息）
     */
    suspend fun getPlaylistsRaw(): JSONObject = withContext(Dispatchers.IO) {
        execute("getPlaylists.view")
    }

    /** 解析 getPlaylists 响应 —— 网络与磁盘缓存共用同一解析路径 */
    internal fun parsePlaylistsResponse(body: JSONObject): List<SubsonicPlaylist> {
        // 结构：playlists.playlist[]；服务器上没有歌单时 playlist 数组可能缺省，回退空列表
        val arr = body.optJSONObject("playlists")?.optJSONArray("playlist")
            ?: return emptyList()
        return (0 until arr.length()).map { parsePlaylist(arr.getJSONObject(it)) }
    }

    /**
     * 创建歌单（createPlaylist，Subsonic API 1.2+，Navidrome 全版本支持）。
     * 仅传 name + songIds：Subsonic 规范里不传 playlistId 即为"创建"语义（传 id 则是整表覆盖），
     * 这里封装成纯创建，避免调用方误触覆盖。
     *
     * Navidrome 兼容性说明：规范并未要求 createPlaylist 响应返回新歌单内容，
     * 但 Navidrome 实际会在响应中带回新建的 playlist 对象 —— 优先直接解析其 id；
     * 解析不到（如其他 Subsonic 兼容服务端只回空 envelope）时，
     * 回查 getPlaylists 取同名最新的一条兜底。
     *
     * @param name 新歌单名称
     * @param songIds 初始曲目列表（Subsonic 歌曲 id）；可为空，创建空歌单
     * @return 新歌单的 id
     */
    suspend fun createPlaylist(name: String, songIds: List<String>): String =
        withContext(Dispatchers.IO) {
            // songId 参数可重复出现，需逐个拼进 query
            val params = mutableListOf("name" to name)
            songIds.forEach { params.add("songId" to it) }
            val body = execute("createPlaylist.view", *params.toTypedArray())

            // 路径一：Navidrome 响应直接携带 playlist 对象，取其 id
            val direct = body.optJSONObject("playlist")?.optString("id")?.ifBlank { null }
            if (direct != null) return@withContext direct

            // 路径二：回查歌单列表，取同名最新的一条（服务端返回顺序中新歌单在前）兜底
            val fallback = parsePlaylistsResponse(getPlaylistsRaw())
                .filter { it.name == name && (it.owner.isBlank() || it.owner == username) }
                .firstOrNull()
                ?: throw SubsonicException("创建歌单后未能定位新歌单: $name")
            fallback.id
        }

    /**
     * 更新歌单（updatePlaylist，Subsonic API 1.7+，Navidrome 全版本支持）。
     * 只把非空参数拼进请求 —— updatePlaylist 是"缺省即不改"语义，
     * 空 name 若被传出会被部分服务端当成改名为空串处理，故调用方传 null 表示不修改。
     *
     * Navidrome 兼容性说明：comment（策展说明）参数自 Subsonic 1.14 引入，
     * Navidrome 支持该字段并持久化；旧版 Subsonic 服务端会忽略未知参数，不会报错。
     *
     * @param playlistId 歌单 id
     * @param name 新名称；null 表示不改名
     * @param comment 新的策展说明；null 表示不清除、不修改
     * @param songIdsToAdd 追加到歌单末尾的曲目 id 列表（参数名 songId，可重复出现）
     * @param songIndexesToRemove 要移除的曲目在歌单内的下标列表（参数名 songIndex，可重复出现）；
     *   Subsonic 按"原列表下标"语义处理，一次请求内多个下标互不影响
     */
    suspend fun updatePlaylist(
        playlistId: String,
        name: String?,
        comment: String?,
        songIdsToAdd: List<String>,
        songIndexesToRemove: List<Int>,
    ): Unit = withContext(Dispatchers.IO) {
        val params = mutableListOf("playlistId" to playlistId)
        // 空参不传 —— updatePlaylist 缺省的参数保持原值
        name?.takeIf { it.isNotBlank() }?.let { params.add("name" to it) }
        comment?.let { params.add("comment" to it) }
        songIdsToAdd.forEach { params.add("songId" to it) }
        songIndexesToRemove.forEach { params.add("songIndex" to it.toString()) }
        execute("updatePlaylist.view", *params.toTypedArray())
    }

    /**
     * 删除歌单（deletePlaylist，Subsonic API 1.2+，Navidrome 全版本支持）。
     * 只能删除自己拥有的歌单；对他人的公开歌单调用会被服务端拒绝。
     *
     * @param playlistId 歌单 id
     */
    suspend fun deletePlaylist(playlistId: String): Unit = withContext(Dispatchers.IO) {
        execute("deletePlaylist.view", "id" to playlistId)
    }

    /**
     * 获取歌单详情（getPlaylist，Subsonic API 1.0+，Navidrome 全版本支持）。
     * 与 getPlaylists 不同，本接口返回的 playlist 对象内嵌 entry[] —— 歌单的完整曲目列表，
     * 每一项的字段结构与歌曲完全一致，直接复用 [parseSong] 转成统一的 [PlayableTrack] 供播放队列消费。
     *
     * @param playlistId 歌单 id
     * @return 歌单元信息 + 内含曲目（服务端保证曲目按添加顺序排列）
     */
    suspend fun getPlaylistRaw(playlistId: String): JSONObject = withContext(Dispatchers.IO) {
        execute("getPlaylist.view", "id" to playlistId)
    }

    /** 解析 getPlaylist 响应（歌单元信息 + 曲目列表）—— 网络与磁盘缓存共用 */
    internal fun parsePlaylistResponse(body: JSONObject): Pair<SubsonicPlaylist, List<PlayableTrack>> {
        val playlistJson = body.getJSONObject("playlist")
        val playlist = parsePlaylist(playlistJson)
        // entry 数组可能缺省（空歌单），回退空列表
        val entries = playlistJson.optJSONArray("entry")
            ?: return playlist to emptyList()
        val songs = (0 until entries.length()).map { parseSong(entries.getJSONObject(it)) }
        return playlist to songs
    }

    // ──────────────────────────────────────────────────────
    // 内部实现
    // ──────────────────────────────────────────────────────

    /**
     * 执行一次 Subsonic GET 请求并解析 JSON envelope。
     *
     * @param endpoint 接口名（如 "ping.view"）
     * @param params 额外的业务参数（认证参数由 [buildUrl] 统一追加）
     * @return subsonic-response 的内容对象
     * @throws SubsonicException HTTP 非 2xx、JSON 解析失败或 status!=ok
     */
    private fun execute(endpoint: String, vararg params: Pair<String, String>): JSONObject {
        val request = Request.Builder().url(buildUrl(endpoint, *params)).build()
        val response = sharedHttpClient.newCall(request).execute()
        val bodyText = response.use {
            if (!it.isSuccessful) {
                // Navidrome 对错误场景可能仍返回 200，此处兜底 HTTP 层错误
                throw SubsonicException("HTTP ${it.code}: ${it.message}")
            }
            it.body?.string() ?: throw SubsonicException("空响应体")
        }
        return parseEnvelope(bodyText)
    }

    /**
     * 解析并校验 Subsonic JSON envelope。
     *
     * 结构：{"subsonic-response": {"status": "ok"|"failed", "version": "...", ...}}
     * status=failed 时读取 error.message 抛出（如"错误的用户名或密码"）。
     */
    private fun parseEnvelope(text: String): JSONObject {
        val root = JSONObject(text)
        val envelope = root.getJSONObject("subsonic-response")
        if (envelope.optString("status") != "ok") {
            val error = envelope.optJSONObject("error")
            val code = error?.optInt("code") ?: -1
            val message = error?.optString("message") ?: "未知错误"
            throw SubsonicException("Subsonic 错误(code=$code): $message")
        }
        return envelope
    }

    /**
     * 拼接完整的接口 URL：{base}/rest/{endpoint}?{认证参数}&{业务参数}。
     *
     * 认证参数固定为 u/t/s/v/c/f 六项（见类注释）。
     */
    private fun buildUrl(endpoint: String, vararg params: Pair<String, String>): String {
        val urlBuilder = ("$baseUrl/rest/$endpoint").toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("u", username)
        urlBuilder.addQueryParameter("t", token)
        urlBuilder.addQueryParameter("s", salt)
        urlBuilder.addQueryParameter("v", PROTOCOL_VERSION)
        urlBuilder.addQueryParameter("c", CLIENT_NAME)
        urlBuilder.addQueryParameter("f", "json")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        return urlBuilder.build().toString()
    }

    /** 解析专辑 JSON 数组为 [SubsonicAlbum] 列表 */
    private fun parseAlbumArray(arr: org.json.JSONArray): List<SubsonicAlbum> =
        (0 until arr.length()).map { parseAlbum(arr.getJSONObject(it)) }

    /** 解析单个专辑 JSON 对象；同时把 coverArt ID 拼成完整封面 URL，UI 可直接加载 */
    private fun parseAlbum(json: JSONObject): SubsonicAlbum {
        val coverArtId = json.optString("coverArt", "").ifBlank { null }
        return SubsonicAlbum(
            id = json.getString("id"),
            name = json.optString("name", ""),
            artist = json.optString("artist", ""),
            artistId = json.optString("artistId", "").ifBlank { null },
            coverArt = coverArtId,
            coverArtUrl = coverArtId?.let { coverArtUrl(it) },
            songCount = json.optInt("songCount", 0),
            durationSeconds = json.optLong("duration", 0),
            year = json.optInt("year", 0),
        )
    }

    /**
     * 解析单个歌曲 JSON 对象为统一的 [PlayableTrack]。
     * 播放地址走 stream 接口（原始音质），封面取歌曲自身 coverArt（缺省回退专辑封面）。
     */
    private fun parseSong(json: JSONObject): PlayableTrack {
        val songId = json.getString("id")
        val coverArtId = json.optString("coverArt", "").ifBlank { json.optString("albumId", "").ifBlank { null } }
        return PlayableTrack(
            id = "subsonic:$songId",
            title = json.optString("title", "未知曲目"),
            artist = json.optString("artist", ""),
            album = json.optString("album", ""),
            durationSeconds = json.optLong("duration", 0),
            playbackUri = streamUrl(songId),
            coverArtUrl = coverArtId?.let { coverArtUrl(it) },
        )
    }

    /**
     * 解析单个歌单 JSON 对象（getPlaylists / getPlaylist / createPlaylist 共用）。
     * duration 为歌单总时长（秒）；comment/owner/public 为 Subsonic 1.14+ 字段，
     * Navidrome 必回，其他兼容服务端可能缺省 —— 全部用 opt* 宽松解析兜底。
     */
    private fun parsePlaylist(json: JSONObject): SubsonicPlaylist = SubsonicPlaylist(
        id = json.getString("id"),
        name = json.optString("name", ""),
        songCount = json.optInt("songCount", 0),
        durationSeconds = json.optInt("duration", 0),
        comment = json.optString("comment", ""),
        public = json.optBoolean("public", false),
        owner = json.optString("owner", ""),
    )

    companion object {
        /**
         * 全 App 共享的 HTTP 客户端：所有 Subsonic API 调用与封面下载共用
         * 同一连接池（keep-alive 复用，免重复握手）。音乐接口无 Cookie，
         * 全局一个实例是 OkHttp 官方推荐用法
         */
        internal val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Subsonic 协议版本 —— 1.16.1 是当前主流服务端（Navidrome）支持的版本 */
        private const val PROTOCOL_VERSION = "1.16.1"

        /** 客户端标识 —— Subsonic 服务端用它统计调用来源 */
        private const val CLIENT_NAME = "nion"

        /**
         * 规范化服务器地址：
         * 1. 去掉首尾空白
         * 2. 无协议前缀时默认补 https://（用户常只输入域名）
         * 3. 去掉末尾多余的 /（避免拼接出 //rest/ 双斜杠）
         */
        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim()
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return withScheme.trimEnd('/')
        }

        /** 生成 12 位随机 hex 盐 —— 6 字节 SecureRandom 编码为 hex */
        private fun generateSalt(): String {
            val bytes = ByteArray(6)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** 计算字符串的 MD5 hex（小写），用于 token = md5(password + salt) */
        fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

    }
}

/**
 * Subsonic 协议层异常 —— 网络失败、HTTP 错误或服务端返回 status=failed。
 * UI 层捕获后展示 message 引导用户检查地址/账密。
 */
class SubsonicException(message: String) : Exception(message)

/**
 * Subsonic JSON 响应磁盘缓存 —— 歌单详情/专辑详情/列表的"秒开"层。
 *
 * 存储内容是服务端原始响应体（subsonic-response 内层 JSON 字符串），读取后经
 * [SubsonicClient] 的各 parse*Response 复用同一套解析器，不引入序列化依赖。
 * key 由调用方拼（如 "playlist:<id>" / "albums:newest:30"），文件名取 md5。
 * 目录 cacheDir/subsonic-json：系统磁盘紧张时可整体回收，不进备份。
 *
 * 使用姿势（stale-while-revalidate）：打开时先读缓存立即上屏，再发网络请求
 * 刷新并回写；数据变更（增删曲目/改名/删歌单）时精确 remove 对应 key。
 */
internal object SubsonicJsonCache {
    private const val DIR_NAME = "subsonic-json"
    // 容量上限：8MB。JSON 响应单条很小（KB 级），8MB 足够放下数百个专辑/歌单/列表；
    // 超限按 lastModified 从旧到新淘汰（与封面磁盘缓存同款 LRU 近似）。
    // 不设上限的话每开一个专辑/歌单就留一个文件、只增不删，长期慢性膨胀
    private const val MAX_BYTES = 8L * 1024 * 1024
    private var dir: File? = null

    private fun dirOf(context: Context): File =
        dir ?: File(context.applicationContext.cacheDir, DIR_NAME).apply { mkdirs() }
            .also { dir = it }

    private fun fileName(key: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".json"
    }

    /** 读缓存；无缓存返回 null。命中即更新 lastModified，让 LRU 淘汰保活热点 */
    fun read(context: Context, key: String): String? {
        val file = File(dirOf(context), fileName(key))
        return runCatching {
            file.readText().also { file.setLastModified(System.currentTimeMillis()) }
        }.getOrNull()
    }

    /** 写缓存（整文件覆盖）+ 超限淘汰 */
    fun write(context: Context, key: String, json: String) {
        runCatching {
            File(dirOf(context), fileName(key)).writeText(json)
            trim(context)
        }
    }

    /** 精确失效：数据变更时删除对应缓存 */
    fun remove(context: Context, key: String) {
        runCatching { File(dirOf(context), fileName(key)).delete() }
    }

    /** 总量超限时按 lastModified 从旧到新删除，直到回到上限内（与封面磁盘缓存同款） */
    private fun trim(context: Context) {
        val files = dirOf(context).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_BYTES) return
            total -= f.length()
            f.delete()
        }
    }
}
