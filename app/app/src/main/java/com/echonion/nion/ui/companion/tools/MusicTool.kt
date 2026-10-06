package com.echonion.nion.ui.companion.tools

import com.echonion.nion.ui.music.MusicControllerFacade
import com.echonion.nion.ui.music.MusicSource
import com.echonion.nion.ui.music.MusicSettings
import com.echonion.nion.ui.music.MusicViewModel
import com.echonion.nion.ui.music.PlayableTrack
import com.echonion.nion.ui.music.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.nion_core.NionCore

/**
 * 音乐工具 —— AI 伴伴通过此工具查询曲库、控制播放、管理歌单。
 *
 * 可做的事（按三组划分）：
 * 1. **查**：get_state 播放器状态 / search_music 搜歌 / list_all_songs 全量本地曲库 /
 *    list_playlists 歌单列表 / get_playlist_tracks 歌单曲目
 * 2. **播**：play_playlist 直接播整个歌单；toggle_play_pause / play / pause / next /
 *    previous / seek_to / play_index / set_shuffle / set_repeat / set_speed / stop
 * 3. **管歌单**：create_playlist / add_to_playlist / remove_from_playlist /
 *    rename_playlist / delete_playlist —— 建好歌单后可直接 play_playlist 播放
 *
 * track_id 来源：search_music / list_all_songs / get_playlist_tracks 返回的 id
 * **原样使用**即可（同一音源内自洽），不要自行增删前缀。
 *
 * 双端分发：按 NionCore settings 的 music_source 决定走本地 MediaStore + core 本地歌单表，
 * 还是走 Subsonic 服务器接口；客户端凭据从 settings 读取，**绝不出现在返回值或日志中**。
 * 本地歌单 id 形如 "local-<n>"（与 UI 一致）；服务器歌单 id 为 Subsonic 原始 id。
 *
 * 空闲态（音源未配置 / 本地无曲库）返回可操作的中文提示而非报错。
 */
object MusicTool : Tool {

    override val name = "music"

    override val description = """
查询与播放音乐、管理歌单。建好歌单后可直接 play_playlist 播放。

常用流程：
- 找歌：search_music(query) 或 list_all_songs（仅本地音源），拿到 track_id
- 播放：play_playlist 播整个歌单，或先 create_playlist 再 play_playlist
- 控制：toggle_play_pause / next / previous / seek_to / play_index / set_shuffle / set_repeat / set_speed / stop
- 状态：get_state 查当前曲目、进度、队列
- 歌单管理：list_playlists / create_playlist / add_to_playlist / remove_from_playlist / rename_playlist / delete_playlist / get_playlist_tracks

track_id 来源：search_music、list_all_songs、get_playlist_tracks 返回的 id 原样使用即可。
当前音源（本地/服务器）由用户在音乐页设置，本工具自动跟随；音源未配置或曲库为空时会返回中文提示。
    """.trimIndent()

    override val affectsData = setOf(DataType.MUSIC_DATA)

    /** list_all_songs 的返回上限 —— 超过则截断并在结果中注明，防止撑爆 LLM 上下文 */
    private const val MAX_SONGS = 500

    override fun parametersSchema(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().apply {
                    // 查询
                    put("get_state")
                    put("search_music")
                    put("list_all_songs")
                    put("list_playlists")
                    put("get_playlist_tracks")
                    // 播放控制
                    put("toggle_play_pause")
                    put("play")
                    put("pause")
                    put("next")
                    put("previous")
                    put("seek_to")
                    put("play_index")
                    put("set_shuffle")
                    put("set_repeat")
                    put("set_speed")
                    put("stop")
                    put("play_playlist")
                    // 歌单管理
                    put("create_playlist")
                    put("add_to_playlist")
                    put("remove_from_playlist")
                    put("rename_playlist")
                    put("delete_playlist")
                })
                put(
                    "description",
                    "操作类型：查询(get_state/search_music/list_all_songs/list_playlists/" +
                        "get_playlist_tracks)、播放控制(toggle_play_pause/play/pause/next/" +
                        "previous/seek_to/play_index/set_shuffle/set_repeat/set_speed/stop/" +
                        "play_playlist)、歌单管理(create_playlist/add_to_playlist/" +
                        "remove_from_playlist/rename_playlist/delete_playlist)"
                )
            })
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "搜索关键词（search_music 必填），匹配曲名/艺术家/专辑")
            })
            put("count", JSONObject().apply {
                put("type", "integer")
                put("description", "search_music 返回条数上限，默认 20")
            })
            put("position_ms", JSONObject().apply {
                put("type", "integer")
                put("description", "seek_to 目标位置（毫秒）；与 percent 二选一")
            })
            put("percent", JSONObject().apply {
                put("type", "number")
                put("description", "seek_to 目标位置百分比 0-100（duration 已知时可用）；与 position_ms 二选一")
            })
            put("index", JSONObject().apply {
                put("type", "integer")
                put("description", "play_index 的队列下标（从 0 开始，可用 get_state 查 queue_count）")
            })
            put("enabled", JSONObject().apply {
                put("type", "boolean")
                put("description", "set_shuffle 的随机播放开关")
            })
            put("mode", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().apply {
                    put("off")
                    put("one")
                    put("all")
                })
                put("description", "set_repeat 循环模式：off=不循环, one=单曲循环, all=列表循环")
            })
            put("speed", JSONObject().apply {
                put("type", "number")
                put("description", "set_speed 播放速度（1.0=正常，范围 (0,4]）")
            })
            put("playlist_id", JSONObject().apply {
                put("type", "string")
                put("description", "歌单 id（list_playlists / get_playlist_tracks 返回的 id 原样使用；本地歌单形如 \"local-<n>\"）")
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "歌单名称（create_playlist 必填；rename_playlist 为新名称）")
            })
            put("comment", JSONObject().apply {
                put("type", "string")
                put("description", "歌单策展说明（create_playlist / rename_playlist 可选）")
            })
            put("track_ids", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "曲目 id 列表（create_playlist / add_to_playlist 使用，id 来自 search_music / list_all_songs / get_playlist_tracks）")
            })
            put("positions", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "integer") })
                put("description", "remove_from_playlist 要移除曲目的歌单内序号列表（1 开始，即 get_playlist_tracks 返回的 position 字段）")
            })
        })
        put("required", JSONArray().apply {
            put("action")
        })
    }

    override suspend fun execute(params: JSONObject, core: NionCore): String {
        // 整体兜底：任何异常都转成中文错误 JSON 返回 LLM，绝不让工具抛崩会话
        return runCatching { dispatch(params.getString("action"), params, core) }
            .getOrElse { e ->
                JSONObject()
                    .put("error", e.message ?: "音乐工具执行失败")
                    .toString()
            }
    }

    /** action 路由 —— 与 SettingsTool 同构的集中分发 */
    private suspend fun dispatch(action: String, params: JSONObject, core: NionCore): String =
        when (action) {
            // ── 查询 ──
            "get_state" -> getState(core)
            "search_music" -> searchMusic(params, core)
            "list_all_songs" -> listAllSongs(core)
            "list_playlists" -> listPlaylists(core)
            "get_playlist_tracks" -> getPlaylistTracks(params, core)
            // ── 播放控制（无需音源，直接转发门面）──
            "toggle_play_pause" -> ok("已切换播放/暂停").also { MusicControllerFacade.togglePlayPause() }
            "play" -> ok("已继续播放").also { MusicControllerFacade.play() }
            "pause" -> ok("已暂停").also { MusicControllerFacade.pause() }
            "next" -> ok("已切到下一首").also { MusicControllerFacade.next() }
            "previous" -> ok("已切到上一首").also { MusicControllerFacade.previous() }
            "seek_to" -> seekTo(params)
            "play_index" -> playIndex(params)
            "set_shuffle" -> setShuffle(params)
            "set_repeat" -> setRepeat(params)
            "set_speed" -> setSpeed(params)
            "stop" -> ok("已停止并清空队列").also { MusicControllerFacade.stopAndClear() }
            "play_playlist" -> playPlaylist(params, core)
            // ── 歌单管理 ──
            "create_playlist" -> createPlaylist(params, core)
            "add_to_playlist" -> addToPlaylist(params, core)
            "remove_from_playlist" -> removeFromPlaylist(params, core)
            "rename_playlist" -> renamePlaylist(params, core)
            "delete_playlist" -> deletePlaylist(params, core)
            else -> """{"error":"未知的 action: $action"}"""
        }

    // ──────────────────────────────────────────────────────
    // 音源与凭据
    // ──────────────────────────────────────────────────────

    /** 当前音源中文名（快照展示用） */
    private fun sourceName(source: MusicSource): String = when (source) {
        MusicSource.LOCAL -> "本地音乐"
        MusicSource.BRAINGIRL -> "类脑娘服务器"
        MusicSource.CUSTOM -> "自定义服务器"
    }

    /**
     * 按当前音源构建 Subsonic 客户端。
     * 凭据从 core settings 现场读取 —— 绝不写入工具返回值或日志。
     * 音源为本地或云端未配置完成时返回 null，由调用方给出可操作的中文提示。
     */
    private suspend fun buildClient(core: NionCore): Pair<MusicSource, SubsonicClient?> =
        withContext(Dispatchers.IO) {
            val source = MusicSource.fromKey(core.getSetting(MusicSettings.KEY_SOURCE))
            val client = when (source) {
                MusicSource.LOCAL -> null
                MusicSource.BRAINGIRL -> {
                    val u = core.getSetting(MusicSettings.KEY_BRAINGIRL_USERNAME) ?: ""
                    val p = core.getSetting(MusicSettings.KEY_BRAINGIRL_PASSWORD) ?: ""
                    if (u.isNotBlank() && p.isNotBlank()) {
                        SubsonicClient(MusicViewModel.BRAINGIRL_SERVER_URL, u.trim(), p)
                    } else null
                }
                MusicSource.CUSTOM -> {
                    val url = core.getSetting(MusicSettings.KEY_SERVER_URL) ?: ""
                    val u = core.getSetting(MusicSettings.KEY_SERVER_USERNAME) ?: ""
                    val p = core.getSetting(MusicSettings.KEY_SERVER_PASSWORD) ?: ""
                    if (url.isNotBlank() && u.isNotBlank() && p.isNotBlank()) {
                        SubsonicClient(url, u.trim(), p)
                    } else null
                }
            }
            source to client
        }

    /** 云端未配置时的统一中文提示（告诉 LLM 该引导用户去哪配置，而不是干报错） */
    private fun cloudUnconfiguredHint(source: MusicSource): String =
        """{"error":"当前音源为${sourceName(source)}但账号未配置完成，请引导用户在应用的「音乐」页面选择音源并填写服务器账号信息后重试"}"""

    /** 本地歌单 id 解析：形如 "local-<n>"，解析失败返回 null（视为云端歌单 id） */
    private fun localPlaylistId(playlistId: String): Long? =
        playlistId.takeIf { it.startsWith("local-") }
            ?.removePrefix("local-")?.toLongOrNull()

    /**
     * 归一化曲目 id 为本地 MediaStore _ID：兼容裸数字 id 与历史 "local:<id>" 前缀写法，
     * 解析不出数字则抛中文异常。
     */
    private fun localTrackId(trackId: String): Long =
        trackId.removePrefix("local:").toLongOrNull()
            ?: throw IllegalArgumentException("无效的本地曲目 id: $trackId（请使用 search_music / list_all_songs 返回的原始 id）")

    /** 归一化曲目 id 为云端 Subsonic songId：兼容裸 id 与 "subsonic:" 前缀写法 */
    private fun cloudTrackId(trackId: String): String = trackId.removePrefix("subsonic:")

    // ──────────────────────────────────────────────────────
    // 查询类 action
    // ──────────────────────────────────────────────────────

    /** 播放器状态快照（门面现场读控制器；附带当前音源名） */
    private suspend fun getState(core: NionCore): String {
        val (source, _) = buildClient(core)
        val snapshot = MusicControllerFacade.getSnapshot(sourceName(source))
        return JSONObject(snapshot).toString()
    }

    /** 搜索：本地走 MediaStore 内存过滤，云端走 search3 */
    private suspend fun searchMusic(params: JSONObject, core: NionCore): String {
        val query = params.optString("query").trim()
        if (query.isEmpty()) return """{"error":"search_music 需要非空 query 参数"}"""
        val count = params.optInt("count", 20).coerceIn(1, 100)
        val (source, client) = buildClient(core)
        val songs: List<PlayableTrack> = if (source == MusicSource.LOCAL) {
            val all = withContext(Dispatchers.IO) { MusicControllerFacade.queryLocalTracksForAgent() }
            if (all.isEmpty()) {
                return """{"message":"本地曲库为空。请引导用户在应用的「音乐」页面授予媒体权限（或确认设备上有音频文件）后重试"}"""
            }
            all.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true) ||
                    it.album.contains(query, ignoreCase = true)
            }
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            c.search3(query, songCount = count, albumCount = 0, artistCount = 0).songs
        }
        return JSONObject()
            .put("source", sourceName(source))
            .put("query", query)
            .put("total", songs.size)
            .put("songs", songs.take(count).toJSONArray())
            .toString()
    }

    /** 全量曲库 —— 仅本地音源；云端曲目数大，提示改用 search_music */
    private suspend fun listAllSongs(core: NionCore): String {
        val (source, client) = buildClient(core)
        if (source != MusicSource.LOCAL) {
            return """{"error":"当前音源为${sourceName(source)}，不支持全量列表（曲目可能上万），请改用 search_music 按关键词搜索"}"""
        }
        val all = withContext(Dispatchers.IO) { MusicControllerFacade.queryLocalTracksForAgent() }
        if (all.isEmpty()) {
            return """{"message":"本地曲库为空。请引导用户在应用的「音乐」页面授予媒体权限（或确认设备上有音频文件）后重试"}"""
        }
        val truncated = all.size > MAX_SONGS
        return JSONObject()
            .put("source", sourceName(source))
            .put("total", all.size)
            .apply { if (truncated) put("note", "曲目数超过 $MAX_SONGS，仅返回前 $MAX_SONGS 首；请用 search_music 定位其余曲目") }
            .put("songs", all.take(MAX_SONGS).toJSONArray())
            .toString()
    }

    /** 歌单列表：本地 core 表 / 云端 getPlaylists，统一 LibraryPlaylist 形状输出 */
    private suspend fun listPlaylists(core: NionCore): String {
        val (source, client) = buildClient(core)
        val playlists = if (source == MusicSource.LOCAL) {
            withContext(Dispatchers.IO) {
                core.listLocalPlaylists().map {
                    JSONObject()
                        .put("playlist_id", "local-${it.id}")
                        .put("name", it.name)
                        .put("comment", it.comment)
                        .put("song_count", it.songCount)
                        .put("is_local", true)
                }
            }
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            c.getPlaylists().map {
                JSONObject()
                    .put("playlist_id", it.id)
                    .put("name", it.name)
                    .put("comment", it.comment)
                    .put("song_count", it.songCount)
                    .put("is_local", false)
            }
        }
        return JSONObject()
            .put("source", sourceName(source))
            .put("total", playlists.size)
            .put("playlists", JSONArray(playlists))
            .toString()
    }

    /**
     * 歌单曲目：本地先取 core 表的 (position, trackId)，再对照 MediaStore 曲库
     * 补齐标题等元数据（曲库已被删的曲目只保留 position/track_id 标注已失效）；
     * 云端 getPlaylist 直接返回完整曲目。
     */
    private suspend fun getPlaylistTracks(params: JSONObject, core: NionCore): String {
        val playlistId = params.optString("playlist_id")
        if (playlistId.isBlank()) return """{"error":"playlist_id 不能为空"}"""
        val (source, client) = buildClient(core)
        val localId = if (source == MusicSource.LOCAL) localPlaylistId(playlistId) else null
        return if (localId != null) {
            val items = withContext(Dispatchers.IO) { core.localPlaylistTracks(localId) }
            val lib = withContext(Dispatchers.IO) { MusicControllerFacade.queryLocalTracksForAgent() }
                .associateBy { it.id }
            val arr = JSONArray()
            items.forEach { item ->
                val track = lib[item.trackId.toString()]
                val obj = JSONObject()
                    .put("position", item.position)
                    .put("track_id", item.trackId.toString())
                if (track != null) {
                    obj.put("title", track.title)
                        .put("artist", track.artist)
                        .put("album", track.album)
                        .put("duration_seconds", track.durationSeconds)
                } else {
                    obj.put("title", "（曲目已从设备移除）")
                }
                arr.put(obj)
            }
            JSONObject().put("playlist_id", playlistId).put("total", items.size).put("tracks", arr).toString()
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            val (_, songs) = c.getPlaylist(playlistId)
            val arr = JSONArray()
            songs.forEachIndexed { i, track ->
                arr.put(track.toJSON().put("position", i + 1))
            }
            JSONObject().put("playlist_id", playlistId).put("total", songs.size).put("tracks", arr).toString()
        }
    }

    // ──────────────────────────────────────────────────────
    // 播放类 action
    // ──────────────────────────────────────────────────────

    /** seek：position_ms 与 percent 二选一（percent 依赖 duration 已知） */
    private suspend fun seekTo(params: JSONObject): String {
        val positionMs = params.optLong("position_ms", -1L)
        val target = if (positionMs >= 0) {
            positionMs
        } else if (params.has("percent")) {
            val percent = params.optDouble("percent", -1.0)
            if (percent < 0 || percent > 100) {
                return """{"error":"percent 需在 0-100 范围内，或改传 position_ms"}"""
            }
            val duration = MusicControllerFacade.getSnapshot("")["duration_ms"] as? Long ?: 0L
            if (duration <= 0L) {
                return """{"error":"当前曲目时长未知，无法按百分比 seek，请改传 position_ms"}"""
            }
            (duration * percent / 100).toLong()
        } else {
            return """{"error":"seek_to 需要 position_ms 或 percent 参数"}"""
        }
        MusicControllerFacade.seekTo(target)
        return ok("已跳转到 ${target}ms 处")
    }

    private suspend fun playIndex(params: JSONObject): String {
        val index = params.optInt("index", -1)
        if (index < 0) return """{"error":"play_index 需要非负 index 参数"}"""
        MusicControllerFacade.playIndex(index)
        return ok("已跳到队列第 ${index + 1} 首")
    }

    private suspend fun setShuffle(params: JSONObject): String {
        if (!params.has("enabled")) return """{"error":"set_shuffle 需要 enabled 参数"}"""
        val enabled = params.getBoolean("enabled")
        MusicControllerFacade.setShuffle(enabled)
        return ok(if (enabled) "已开启随机播放" else "已关闭随机播放")
    }

    private suspend fun setRepeat(params: JSONObject): String {
        val mode = params.optString("mode")
        if (mode !in setOf("off", "one", "all")) {
            return """{"error":"mode 需为 off/one/all"}"""
        }
        MusicControllerFacade.setRepeatMode(mode)
        return ok(
            when (mode) {
                "one" -> "已切换为单曲循环"
                "all" -> "已切换为列表循环"
                else -> "已关闭循环"
            }
        )
    }

    private suspend fun setSpeed(params: JSONObject): String {
        if (!params.has("speed")) return """{"error":"set_speed 需要 speed 参数"}"""
        val speed = params.optDouble("speed", 1.0).toFloat()
        MusicControllerFacade.setSpeed(speed)
        return ok("播放速度已设为 $speed")
    }

    /** 播整个歌单：取歌单曲目 → 门面 setQueueAndPlay */
    private suspend fun playPlaylist(params: JSONObject, core: NionCore): String {
        val playlistId = params.optString("playlist_id")
        if (playlistId.isBlank()) return """{"error":"playlist_id 不能为空"}"""
        val (source, client) = buildClient(core)
        val localId = if (source == MusicSource.LOCAL) localPlaylistId(playlistId) else null
        val tracks: List<PlayableTrack> = if (localId != null) {
            val items = withContext(Dispatchers.IO) { core.localPlaylistTracks(localId) }
            if (items.isEmpty()) return """{"error":"歌单为空，先用 add_to_playlist 添加曲目"}"""
            val lib = withContext(Dispatchers.IO) { MusicControllerFacade.queryLocalTracksForAgent() }
                .associateBy { it.id }
            items.mapNotNull { lib[it.trackId.toString()] }
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            c.getPlaylist(playlistId).second
        }
        if (tracks.isEmpty()) return """{"error":"歌单内没有可播放的曲目（本地曲目可能已从设备移除）"}"""
        MusicControllerFacade.setQueueAndPlay(tracks, 0)
        return JSONObject()
            .put("success", true)
            .put("message", "已开始播放歌单（共 ${tracks.size} 首）")
            .toString()
    }

    // ──────────────────────────────────────────────────────
    // 歌单管理类 action
    // ──────────────────────────────────────────────────────

    private suspend fun createPlaylist(params: JSONObject, core: NionCore): String {
        val name = params.optString("name").trim()
        if (name.isBlank()) return """{"error":"create_playlist 需要 name 参数"}"""
        val comment = params.optString("comment", "")
        val trackIds = params.optStringList("track_ids")
        val (source, client) = buildClient(core)
        return if (source == MusicSource.LOCAL) {
            val playlistId = withContext(Dispatchers.IO) {
                val id = core.createLocalPlaylist(name, comment)
                // 逐曲追加（core 表按加入顺序记录 position）
                trackIds.forEach { core.addTrackToLocalPlaylist(id, localTrackId(it)) }
                id
            }
            JSONObject()
                .put("success", true)
                .put("message", "已创建本地歌单「$name」${if (trackIds.isEmpty()) "" else "并加入 ${trackIds.size} 首曲目"}")
                .put("playlist_id", "local-$playlistId")
                .toString()
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            val songIds = trackIds.map { cloudTrackId(it) }
            val id = c.createPlaylist(name, songIds)
            JSONObject()
                .put("success", true)
                .put("message", "已在${sourceName(source)}创建歌单「$name」${if (songIds.isEmpty()) "" else "并加入 ${songIds.size} 首曲目"}")
                .put("playlist_id", id)
                .toString()
        }
    }

    private suspend fun addToPlaylist(params: JSONObject, core: NionCore): String {
        val playlistId = params.optString("playlist_id")
        if (playlistId.isBlank()) return """{"error":"playlist_id 不能为空"}"""
        val trackIds = params.optStringList("track_ids")
        if (trackIds.isEmpty()) return """{"error":"track_ids 不能为空"}"""
        val (source, client) = buildClient(core)
        val localId = if (source == MusicSource.LOCAL) localPlaylistId(playlistId) else null
        return if (localId != null) {
            withContext(Dispatchers.IO) {
                trackIds.forEach { core.addTrackToLocalPlaylist(localId, localTrackId(it)) }
            }
            ok("已向歌单添加 ${trackIds.size} 首曲目")
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            c.updatePlaylist(
                playlistId = playlistId,
                name = null,
                comment = null,
                songIdsToAdd = trackIds.map { cloudTrackId(it) },
                songIndexesToRemove = emptyList(),
            )
            ok("已向歌单添加 ${trackIds.size} 首曲目")
        }
    }

    private suspend fun removeFromPlaylist(params: JSONObject, core: NionCore): String {
        val playlistId = params.optString("playlist_id")
        if (playlistId.isBlank()) return """{"error":"playlist_id 不能为空"}"""
        val positions = params.optIntList("positions")
        if (positions.isEmpty()) return """{"error":"positions 不能为空"}"""
        val (source, client) = buildClient(core)
        val localId = if (source == MusicSource.LOCAL) localPlaylistId(playlistId) else null
        return if (localId != null) {
            withContext(Dispatchers.IO) {
                // core 按"歌单内 1 起始序号"逐个移除；从大到小删避免连续序号被位移波及
                positions.sortedDescending().forEach { pos ->
                    core.removeTrackFromLocalPlaylist(localId, pos.toLong())
                }
            }
            ok("已从歌单移除 ${positions.size} 首曲目")
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            // Subsonic songIndex 为 0 起始下标，positions 是 1 起始序号
            c.updatePlaylist(
                playlistId = playlistId,
                name = null,
                comment = null,
                songIdsToAdd = emptyList(),
                songIndexesToRemove = positions.map { it - 1 },
            )
            ok("已从歌单移除 ${positions.size} 首曲目")
        }
    }

    private suspend fun renamePlaylist(params: JSONObject, core: NionCore): String {
        val playlistId = params.optString("playlist_id")
        if (playlistId.isBlank()) return """{"error":"playlist_id 不能为空"}"""
        val name = params.optString("name").trim()
        val hasName = params.has("name") && name.isNotBlank()
        val hasComment = params.has("comment")
        if (!hasName && !hasComment) {
            return """{"error":"rename_playlist 至少需要 name 或 comment 之一"}"""
        }
        val comment = params.optString("comment", "")
        val (source, client) = buildClient(core)
        val localId = if (source == MusicSource.LOCAL) localPlaylistId(playlistId) else null
        return if (localId != null) {
            // core 的 rename 是整字段覆盖：未提供的字段需保持原值，先回读再写
            withContext(Dispatchers.IO) {
                val old = core.listLocalPlaylists().find { it.id == localId }
                    ?: throw IllegalArgumentException("未找到本地歌单: $playlistId")
                core.renameLocalPlaylist(
                    localId,
                    if (hasName) name else old.name,
                    if (hasComment) comment else old.comment,
                )
            }
            ok("已更新歌单信息")
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            c.updatePlaylist(
                playlistId = playlistId,
                name = if (hasName) name else null,
                comment = if (hasComment) comment else null,
                songIdsToAdd = emptyList(),
                songIndexesToRemove = emptyList(),
            )
            ok("已更新歌单信息")
        }
    }

    private suspend fun deletePlaylist(params: JSONObject, core: NionCore): String {
        val playlistId = params.optString("playlist_id")
        if (playlistId.isBlank()) return """{"error":"playlist_id 不能为空"}"""
        val (source, client) = buildClient(core)
        val localId = if (source == MusicSource.LOCAL) localPlaylistId(playlistId) else null
        return if (localId != null) {
            withContext(Dispatchers.IO) { core.deleteLocalPlaylist(localId) }
            ok("已删除歌单")
        } else {
            val c = client ?: return cloudUnconfiguredHint(source)
            c.deletePlaylist(playlistId)
            ok("已删除歌单")
        }
    }

    // ──────────────────────────────────────────────────────
    // JSON 辅助
    // ──────────────────────────────────────────────────────

    /** 统一的成功消息返回 */
    private fun ok(message: String): String =
        JSONObject().put("success", true).put("message", message).toString()

    /** PlayableTrack → 曲目 JSON（搜索结果 / 歌单曲目共用；不含播放 URL 等敏感拼接信息） */
    private fun PlayableTrack.toJSON(): JSONObject = JSONObject()
        .put("track_id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("duration_seconds", durationSeconds)

    private fun List<PlayableTrack>.toJSONArray(): JSONArray =
        JSONArray().also { arr -> forEach { arr.put(it.toJSON()) } }

    /** 读字符串数组参数（容忍 null/类型不符，逐项 toString 兜底） */
    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.get(it).toString() }
    }

    /** 读整数数组参数 */
    private fun JSONObject.optIntList(key: String): List<Int> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optInt(it, -1) }.filter { it >= 0 }
    }
}
