package com.echonion.nion.ui.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * - stream.view            音频流（maxBitRate=192kbps，URL 直接交给 ExoPlayer 播放）
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
    /** 单例共享的 HTTP 客户端：音乐接口无需 Cookie，全App共用连接池即可 */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
    suspend fun getAlbumList2(type: String, size: Int = 30): List<SubsonicAlbum> =
        withContext(Dispatchers.IO) {
            val body = execute("getAlbumList2.view", "type" to type, "size" to size.toString())
            // albumList2.album 可能缺省（无专辑时），回退空列表
            val albums = body.optJSONObject("albumList2")?.optJSONArray("album")
                ?: return@withContext emptyList()
            parseAlbumArray(albums)
        }

    /**
     * 获取专辑详情（含曲目列表）。
     *
     * @param albumId 专辑 ID（来自 getAlbumList2 / search3）
     * @return 专辑元信息 + 已转换为 [PlayableTrack] 的歌曲列表
     */
    suspend fun getAlbum(albumId: String): SubsonicAlbumDetail = withContext(Dispatchers.IO) {
        val body = execute("getAlbum.view", "id" to albumId)
        val albumJson = body.getJSONObject("album")
        val album = parseAlbum(albumJson)
        // 专辑内歌曲：song 数组，服务端已按音轨号排序
        val songsJson = albumJson.optJSONArray("song") ?: return@withContext SubsonicAlbumDetail(album, emptyList())
        val songs = (0 until songsJson.length()).map { i ->
            parseSong(songsJson.getJSONObject(i))
        }
        SubsonicAlbumDetail(album, songs)
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
     * 固定 maxBitRate=192（192kbps 对移动端音质/流量平衡较好）。
     *
     * @param songId 歌曲 ID
     */
    fun streamUrl(songId: String): String =
        buildUrl("stream.view", "id" to songId, "maxBitRate" to "192")


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
        val response = http.newCall(request).execute()
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
     * 播放地址走 stream 接口（maxBitRate=192），封面取歌曲自身 coverArt（缺省回退专辑封面）。
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

    companion object {
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
