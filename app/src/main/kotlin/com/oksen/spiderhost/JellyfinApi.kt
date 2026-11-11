package com.oksen.spiderhost

import com.github.catvod.crawler.Spider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The single spider currently served over the Jellyfin-compatible API.
 * Filled by SpiderRunner when started in serve mode.
 */
object JfBackend {
    @Volatile var runner: SpiderRunner? = null
    @Volatile var spider: Spider? = null
    @Volatile var loader: ClassLoader? = null
    @Volatile var siteKey: String = ""
    @Volatile var siteName: String = ""
    /** homeContent "class": (type_id, type_name). */
    @Volatile var classes: List<Pair<String, String>> = emptyList()

    fun <T> call(label: String, block: (Spider) -> T): T? {
        val r = runner ?: return null
        val s = spider ?: return null
        val l = loader ?: return null
        return r.call(label, l) { block(s) }
    }
}

/**
 * Minimal Jellyfin-compatible API on top of one CatVod spider (stage 2).
 *
 * Field names follow Jellyfin's public OpenAPI schema (PublicSystemInfo, AuthenticationResult, UserDto,
 * BaseItemDto, BaseItemDtoQueryResult, PlaybackInfoResponse, MediaSourceInfo, SearchHintResult).
 * Which endpoints SenPlayer actually calls is NOT assumed: every request is logged with tag [JF],
 * and anything not handled is logged as "JF UNHANDLED" and answered 404, so the next round can
 * add exactly what the real client asks for.
 *
 * Mapping (stage 2, deliberately simple):
 *   homeContent class   -> CollectionFolder (CollectionType=tvshows)
 *   vod                 -> Series
 *   vod_play_from line  -> Season (IndexNumber = line index + 1, Name = line name)
 *   episode in a line   -> Episode
 *   playerContent url   -> /Videos/{id}/stream: 302 to the url when no headers are required,
 *                          otherwise passthrough with the headers (MP4 only for now).
 */
object JellyfinApi {

    const val SERVER_ID = "5b1d8c0e6f2a4c3b9e7d1a2f3c4b5d6e"
    const val USER_ID = "8f3e2d1c0b9a48d7a6c5b4e3f2a1d0c9"
    const val TOKEN = "spiderhost0000000000000000000001"
    private const val VERSION = "10.10.7"
    private const val SERVER_NAME = "SpiderHost"
    private val base get() = "http://127.0.0.1:${LocalServer.PORT}"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------- id store: Jellyfin ids are 32-hex; map them back to spider coordinates ----------

    private val ids = ConcurrentHashMap<String, List<String>>()
    private var idFile: File? = null

    fun init(dir: File) {
        val f = File(dir, "jf-ids.tsv")
        idFile = f
        if (f.exists()) f.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size >= 2) ids[parts[0]] = parts.drop(1)
        }
    }

    private fun idOf(vararg parts: String): String {
        val key = listOf(JfBackend.siteKey) + parts.toList()
        val id = md5(key.joinToString("\u0001"))
        if (ids.putIfAbsent(id, key) == null) {
            runCatching { idFile?.appendText(id + "\t" + key.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n") }
        }
        return id
    }

    /** [siteKey, kind, ...] */
    private fun lookup(id: String): List<String>? = ids[id.lowercase().replace("-", "")]

    private fun viewId(tid: String) = idOf("view", tid)
    private fun seriesId(vodId: String) = idOf("series", vodId)
    private fun seasonId(vodId: String, f: Int) = idOf("season", vodId, f.toString())
    private fun episodeId(vodId: String, f: Int, e: Int) = idOf("episode", vodId, f.toString(), e.toString())

    // ---------- caches ----------

    private data class Vod(val id: String, val name: String, val pic: String, val remarks: String, val year: String)
    private data class Detail(
        val vod: Vod, val content: String, val actor: String, val director: String,
        val flags: List<String>, val episodes: List<List<Pair<String, String>>>,
    )
    private data class Resolved(val url: String, val headers: Map<String, String>, val at: Long)

    private val vods = ConcurrentHashMap<String, Vod>()
    private val details = ConcurrentHashMap<String, Detail>()
    private val resolved = ConcurrentHashMap<String, Resolved>()

    // ---------- entry ----------

    fun handle(session: IHTTPSession, rawPath: String): Response {
        val started = System.currentTimeMillis()
        val q = session.parameters.entries.associate { it.key.lowercase() to (it.value.firstOrNull() ?: "") }
        var body = ""
        if (session.method == NanoHTTPD.Method.POST || session.method == NanoHTTPD.Method.PUT) {
            val files = HashMap<String, String>()
            runCatching { session.parseBody(files) }
            body = files["postData"].orEmpty()
        }
        val h = session.headers
        DiagLog.w("[JF] ${session.method} $rawPath?${session.queryParameterString.orEmpty().take(400)}" +
            " ua=${h["user-agent"]} auth=${(h["x-emby-authorization"] ?: h["authorization"])?.take(200)}" +
            " token=${h["x-emby-token"] ?: h["x-mediabrowser-token"] ?: q["api_key"]}" +
            (if (body.isNotEmpty()) " body=${body.take(500)}" else ""))

        val path = rawPath.replace(Regex("^/(emby|jellyfin)(?=/|$)", RegexOption.IGNORE_CASE), "")
        val seg = path.trim('/').split('/').filter { it.isNotEmpty() }
        val low = seg.map { it.lowercase() }

        val resp = try {
            route(session, low, seg, q, body)
        } catch (t: Throwable) {
            DiagLog.e("[JF] $rawPath failed", t)
            text(Response.Status.INTERNAL_ERROR, DiagLog.fullTrace(t))
        }
        DiagLog.w("[JF] -> ${resp.status.requestStatus} ${resp.mimeType} in ${System.currentTimeMillis() - started} ms")
        return resp
    }

    private fun route(s: IHTTPSession, low: List<String>, seg: List<String>, q: Map<String, String>, body: String): Response {
        val n = low.size
        fun at(i: Int) = low.getOrNull(i)
        return when {
            n == 0 -> json(systemInfo(false))
            at(0) == "system" && at(1) == "info" && at(2) == "public" -> json(systemInfo(false))
            at(0) == "system" && at(1) == "info" -> json(systemInfo(true))
            at(0) == "system" && at(1) == "ping" -> text(Response.Status.OK, "\"$SERVER_NAME\"")
            at(0) == "users" && at(1) == "authenticatebyname" -> json(authResult(body))
            at(0) == "users" && at(1) == "public" && n == 2 -> json(JSONArray())
            at(0) == "users" && n == 2 -> json(user())
            at(0) == "users" && at(2) == "views" -> json(views())
            at(0) == "userviews" -> json(views())
            at(0) == "users" && at(2) == "groupingoptions" -> json(JSONArray())
            at(0) == "users" && at(2) == "items" && at(3) == "resume" -> json(queryResult(JSONArray(), 0))
            at(0) == "useritems" && at(1) == "resume" -> json(queryResult(JSONArray(), 0))
            at(0) == "users" && at(2) == "items" && at(3) == "latest" -> json(latest(q))
            at(0) == "items" && at(1) == "latest" -> json(latest(q))
            at(0) == "users" && at(2) == "items" && n == 4 -> item(seg[3])
            at(0) == "users" && at(2) == "items" && n == 3 -> json(items(q))
            at(0) == "items" && n == 1 -> json(items(q))
            at(0) == "items" && n == 2 && at(1)!!.length == 32 -> item(seg[1])
            at(0) == "items" && at(2) == "playbackinfo" -> json(playbackInfo(seg[1]))
            at(0) == "items" && at(2) == "images" -> image(seg[1])
            at(0) == "items" && (at(2) == "similar" || at(2) == "specialfeatures" || at(2) == "localtrailers" || at(2) == "thememedia") -> json(queryResult(JSONArray(), 0))
            at(0) == "shows" && at(1) == "nextup" -> json(queryResult(JSONArray(), 0))
            at(0) == "shows" && at(2) == "seasons" -> json(seasons(seg[1]))
            at(0) == "shows" && at(2) == "episodes" -> json(episodes(seg[1], q["seasonid"]))
            at(0) == "search" && at(1) == "hints" -> json(searchHints(q))
            (at(0) == "videos" || at(0) == "audio") && n >= 3 -> stream(s, seg[1])
            at(0) == "genres" || at(0) == "studios" || at(0) == "persons" || at(0) == "artists" -> json(queryResult(JSONArray(), 0))
            at(0) == "sessions" -> noContent()
            at(0) == "users" && (at(2) == "playeditems" || at(2) == "playingitems" || at(2) == "favoriteitems") -> json(userData(false))
            at(0) == "userplayeditems" || at(0) == "userfavoriteitems" -> json(userData(false))
            at(0) == "branding" && at(1) == "configuration" -> json(JSONObject().put("LoginDisclaimer", "").put("CustomCss", "").put("SplashscreenEnabled", false))
            at(0) == "branding" -> text(Response.Status.OK, "")
            at(0) == "quickconnect" && at(1) == "enabled" -> json(false)
            else -> {
                DiagLog.w("JF UNHANDLED ${s.method} /${seg.joinToString("/")}")
                text(Response.Status.NOT_FOUND, "not implemented")
            }
        }
    }

    // ---------- system / auth ----------

    private fun systemInfo(full: Boolean) = JSONObject()
        .put("LocalAddress", base)
        .put("ServerName", SERVER_NAME)
        .put("Version", VERSION)
        .put("ProductName", "Jellyfin Server")
        .put("OperatingSystem", "Android")
        .put("Id", SERVER_ID)
        .put("StartupWizardCompleted", true)
        .apply { if (full) put("OperatingSystemDisplayName", "Android").put("HasPendingRestart", false).put("IsShuttingDown", false) }

    private fun user() = JSONObject()
        .put("Name", "spiderhost")
        .put("ServerId", SERVER_ID)
        .put("ServerName", SERVER_NAME)
        .put("Id", USER_ID)
        .put("HasPassword", false)
        .put("HasConfiguredPassword", false)
        .put("HasConfiguredEasyPassword", false)
        .put("EnableAutoLogin", false)
        .put("Configuration", JSONObject()
            .put("PlayDefaultAudioTrack", true)
            .put("SubtitleLanguagePreference", "")
            .put("DisplayMissingEpisodes", false)
            .put("GroupedFolders", JSONArray())
            .put("SubtitleMode", "Default")
            .put("DisplayCollectionsView", false)
            .put("EnableLocalPassword", false)
            .put("OrderedViews", JSONArray())
            .put("LatestItemsExcludes", JSONArray())
            .put("MyMediaExcludes", JSONArray())
            .put("HidePlayedInLatest", false)
            .put("RememberAudioSelections", true)
            .put("RememberSubtitleSelections", true)
            .put("EnableNextEpisodeAutoPlay", true))
        .put("Policy", JSONObject()
            .put("IsAdministrator", false)
            .put("IsHidden", false)
            .put("IsDisabled", false)
            .put("EnableMediaPlayback", true)
            .put("EnableAudioPlaybackTranscoding", false)
            .put("EnableVideoPlaybackTranscoding", false)
            .put("EnablePlaybackRemuxing", false)
            .put("EnableContentDownloading", false)
            .put("EnableAllFolders", true)
            .put("AuthenticationProviderId", "Jellyfin.Server.Implementations.Users.DefaultAuthenticationProvider")
            .put("PasswordResetProviderId", "Jellyfin.Server.Implementations.Users.DefaultPasswordResetProvider"))

    private fun authResult(body: String): JSONObject {
        DiagLog.w("[JF] login accepted (any user/password), body keys=${runCatching { JSONObject(body).keys().asSequence().toList() }.getOrNull()}")
        return JSONObject()
            .put("User", user())
            .put("SessionInfo", JSONObject()
                .put("Id", md5("session"))
                .put("UserId", USER_ID)
                .put("UserName", "spiderhost")
                .put("ServerId", SERVER_ID)
                .put("IsActive", true)
                .put("SupportsMediaControl", false)
                .put("SupportsRemoteControl", false)
                .put("PlayableMediaTypes", JSONArray())
                .put("SupportedCommands", JSONArray()))
            .put("AccessToken", TOKEN)
            .put("ServerId", SERVER_ID)
    }

    private fun userData(played: Boolean) = JSONObject()
        .put("PlaybackPositionTicks", 0)
        .put("PlayCount", 0)
        .put("IsFavorite", false)
        .put("Played", played)
        .put("Key", "")

    // ---------- library ----------

    private fun views(): JSONObject {
        val arr = JSONArray()
        JfBackend.classes.forEach { (tid, name) ->
            arr.put(JSONObject()
                .put("Name", name)
                .put("ServerId", SERVER_ID)
                .put("Id", viewId(tid))
                .put("Type", "CollectionFolder")
                .put("CollectionType", "tvshows")
                .put("IsFolder", true)
                .put("ImageTags", JSONObject())
                .put("BackdropImageTags", JSONArray())
                .put("LocationType", "FileSystem")
                .put("UserData", userData(false)))
        }
        return queryResult(arr, 0)
    }

    private fun latest(q: Map<String, String>): JSONArray {
        val parent = q["parentid"]?.let { lookup(it) }
        val tid = if (parent?.getOrNull(1) == "view") parent[2] else JfBackend.classes.firstOrNull()?.first ?: return JSONArray()
        val limit = q["limit"]?.toIntOrNull() ?: 16
        val list = category(tid, 1).first
        val arr = JSONArray()
        list.take(limit).forEach { arr.put(seriesDto(it, null)) }
        return arr
    }

    private fun items(q: Map<String, String>): JSONObject {
        val start = q["startindex"]?.toIntOrNull() ?: 0
        val term = q["searchterm"].orEmpty()
        if (term.isNotBlank()) {
            val arr = JSONArray()
            search(term).forEach { arr.put(seriesDto(it, null)) }
            return queryResult(arr, start)
        }
        q["ids"]?.takeIf { it.isNotBlank() }?.let { list ->
            val arr = JSONArray()
            list.split(',').forEach { id -> dtoFor(id.trim())?.let { arr.put(it) } }
            return queryResult(arr, 0)
        }
        val parentId = q["parentid"].orEmpty()
        val parent = lookup(parentId)
        return when (parent?.getOrNull(1)) {
            "view" -> {
                val pageSize = q["limit"]?.toIntOrNull()?.takeIf { it > 0 }
                val (list, total) = categoryWindow(parent[2], start, pageSize)
                val arr = JSONArray()
                list.forEach { arr.put(seriesDto(it, null)) }
                queryResult(arr, start, total)
            }
            "series" -> seasons(parentId)
            "season" -> episodes(seriesId(parent[2]), parentId)
            else -> {
                DiagLog.w("[JF] /Items without ParentId/SearchTerm/Ids -> first class page 1 (params=${q.keys})")
                val tid = JfBackend.classes.firstOrNull()?.first
                val arr = JSONArray()
                if (tid != null) category(tid, 1).first.forEach { arr.put(seriesDto(it, null)) }
                queryResult(arr, 0)
            }
        }
    }

    private fun item(id: String): Response {
        val dto = dtoFor(id) ?: return text(Response.Status.NOT_FOUND, "unknown id $id")
        return json(dto)
    }

    private fun dtoFor(id: String): JSONObject? {
        val key = lookup(id) ?: return null
        return when (key.getOrNull(1)) {
            "view" -> views().getJSONArray("Items").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.getString("Id") == viewId(key[2]) }
            }
            "series" -> detail(key[2])?.let { seriesDto(it.vod, it) }
            "season" -> detail(key[2])?.let { d -> seasonDto(d, key[3].toInt()) }
            "episode" -> detail(key[2])?.let { d -> episodeDto(d, key[3].toInt(), key[4].toInt(), withSources = true) }
            else -> null
        }
    }

    private fun seasons(seriesIdStr: String): JSONObject {
        val key = lookup(seriesIdStr) ?: return queryResult(JSONArray(), 0)
        val d = detail(key[2]) ?: return queryResult(JSONArray(), 0)
        val arr = JSONArray()
        d.flags.indices.forEach { arr.put(seasonDto(d, it)) }
        return queryResult(arr, 0)
    }

    private fun episodes(seriesIdStr: String, seasonIdStr: String?): JSONObject {
        val key = lookup(seriesIdStr) ?: return queryResult(JSONArray(), 0)
        val d = detail(key[2]) ?: return queryResult(JSONArray(), 0)
        val season = seasonIdStr?.let { lookup(it) }?.takeIf { it.getOrNull(1) == "season" }?.get(3)?.toIntOrNull()
        val arr = JSONArray()
        val flagIdx = if (season != null) listOf(season) else d.flags.indices.toList()
        flagIdx.forEach { f -> d.episodes.getOrNull(f)?.indices?.forEach { e -> arr.put(episodeDto(d, f, e, withSources = false)) } }
        return queryResult(arr, 0)
    }

    private fun searchHints(q: Map<String, String>): JSONObject {
        val term = q["searchterm"].orEmpty()
        val arr = JSONArray()
        if (term.isNotBlank()) search(term).forEach { v ->
            val id = seriesId(v.id)
            arr.put(JSONObject()
                .put("ItemId", id)
                .put("Id", id)
                .put("Name", v.name)
                .put("Type", "Series")
                .put("IsFolder", true)
                .put("MediaType", "Unknown")
                .apply { if (v.pic.isNotBlank()) put("PrimaryImageTag", md5(v.pic).take(16)) }
                .apply { v.year.toIntOrNull()?.let { put("ProductionYear", it) } })
        }
        return JSONObject().put("SearchHints", arr).put("TotalRecordCount", arr.length())
    }

    // ---------- DTOs ----------

    private fun seriesDto(v: Vod, d: Detail?): JSONObject = JSONObject()
        .put("Name", v.name)
        .put("ServerId", SERVER_ID)
        .put("Id", seriesId(v.id))
        .put("Type", "Series")
        .put("IsFolder", true)
        .put("MediaType", "Unknown")
        .put("LocationType", "Remote")
        .put("UserData", userData(false))
        .put("ImageTags", imageTags(v.pic))
        .put("BackdropImageTags", JSONArray())
        .apply { v.year.toIntOrNull()?.let { put("ProductionYear", it) } }
        .apply { if (d != null) {
            put("Overview", d.content)
            put("ChildCount", d.flags.size)
            put("People", people(d))
        } }

    private fun seasonDto(d: Detail, f: Int): JSONObject = JSONObject()
        .put("Name", d.flags.getOrElse(f) { "线路${f + 1}" })
        .put("ServerId", SERVER_ID)
        .put("Id", seasonId(d.vod.id, f))
        .put("Type", "Season")
        .put("IsFolder", true)
        .put("IndexNumber", f + 1)
        .put("SeriesId", seriesId(d.vod.id))
        .put("SeriesName", d.vod.name)
        .put("ParentId", seriesId(d.vod.id))
        .put("ChildCount", d.episodes.getOrNull(f)?.size ?: 0)
        .put("LocationType", "Remote")
        .put("UserData", userData(false))
        .put("ImageTags", imageTags(d.vod.pic))
        .apply { if (d.vod.pic.isNotBlank()) put("SeriesPrimaryImageTag", md5(d.vod.pic).take(16)) }

    private fun episodeDto(d: Detail, f: Int, e: Int, withSources: Boolean): JSONObject {
        val id = episodeId(d.vod.id, f, e)
        return JSONObject()
            .put("Name", d.episodes.getOrNull(f)?.getOrNull(e)?.first ?: "第${e + 1}集")
            .put("ServerId", SERVER_ID)
            .put("Id", id)
            .put("Type", "Episode")
            .put("IsFolder", false)
            .put("MediaType", "Video")
            .put("VideoType", "VideoFile")
            .put("LocationType", "Remote")
            .put("IndexNumber", e + 1)
            .put("ParentIndexNumber", f + 1)
            .put("SeasonId", seasonId(d.vod.id, f))
            .put("SeasonName", d.flags.getOrElse(f) { "线路${f + 1}" })
            .put("SeriesId", seriesId(d.vod.id))
            .put("SeriesName", d.vod.name)
            .put("ParentId", seasonId(d.vod.id, f))
            .put("UserData", userData(false))
            .put("ImageTags", imageTags(d.vod.pic))
            .apply { if (d.vod.pic.isNotBlank()) put("SeriesPrimaryImageTag", md5(d.vod.pic).take(16)) }
            .apply { if (withSources) put("MediaSources", JSONArray().put(mediaSource(id, null))) }
    }

    private fun people(d: Detail): JSONArray {
        val arr = JSONArray()
        d.director.split(',', '，', '/', ' ').filter { it.isNotBlank() }.take(5).forEach {
            arr.put(JSONObject().put("Name", it.trim()).put("Type", "Director").put("Id", md5("p|$it")))
        }
        d.actor.split(',', '，', '/', ' ').filter { it.isNotBlank() }.take(15).forEach {
            arr.put(JSONObject().put("Name", it.trim()).put("Type", "Actor").put("Id", md5("p|$it")))
        }
        return arr
    }

    private fun imageTags(pic: String) = JSONObject().apply { if (pic.isNotBlank()) put("Primary", md5(pic).take(16)) }

    private fun queryResult(items: JSONArray, start: Int, total: Int = start + items.length()) = JSONObject()
        .put("Items", items)
        .put("TotalRecordCount", total)
        .put("StartIndex", start)

    // ---------- playback ----------

    private fun mediaSource(itemId: String, r: Resolved?): JSONObject {
        val container = r?.url?.let { containerOf(it) } ?: "mp4"
        return JSONObject()
            .put("Protocol", "Http")
            .put("Id", itemId)
            .put("Path", "$base/Videos/$itemId/stream?static=true&MediaSourceId=$itemId")
            .put("Type", "Default")
            .put("Container", container)
            .put("Name", "SpiderHost")
            .put("IsRemote", true)
            .put("ReadAtNativeFramerate", false)
            .put("IgnoreDts", false)
            .put("IgnoreIndex", false)
            .put("GenPtsInput", false)
            .put("SupportsTranscoding", false)
            .put("SupportsDirectStream", true)
            .put("SupportsDirectPlay", true)
            .put("IsInfiniteStream", false)
            .put("RequiresOpening", false)
            .put("RequiresClosing", false)
            .put("RequiresLooping", false)
            .put("SupportsProbing", false)
            .put("MediaStreams", JSONArray())
            .put("MediaAttachments", JSONArray())
            .put("Formats", JSONArray())
            .put("RequiredHttpHeaders", JSONObject())
    }

    private fun containerOf(url: String): String {
        val p = url.substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "hls"
            p.endsWith(".flv") -> "flv"
            p.endsWith(".mkv") -> "mkv"
            p.endsWith(".ts") -> "ts"
            else -> "mp4"
        }
    }

    private fun playbackInfo(id: String): JSONObject {
        val r = resolve(id)
        DiagLog.w("[JF] PlaybackInfo $id -> ${r?.url?.take(200) ?: "无地址"} headers=${r?.headers?.keys}")
        return JSONObject()
            .put("MediaSources", JSONArray().apply { if (r != null) put(mediaSource(id, r)) })
            .put("PlaySessionId", md5("ps|$id|${System.currentTimeMillis()}"))
            .apply { if (r == null) put("ErrorCode", "NoCompatibleStream") }
    }

    private fun resolve(id: String): Resolved? {
        resolved[id]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }?.let { return it }
        val key = lookup(id)?.takeIf { it.getOrNull(1) == "episode" } ?: run {
            DiagLog.w("[JF] resolve: $id 不是剧集 id"); return null
        }
        val d = detail(key[2]) ?: return null
        val f = key[3].toInt(); val e = key[4].toInt()
        val flag = d.flags.getOrNull(f) ?: return null
        val epId = d.episodes.getOrNull(f)?.getOrNull(e)?.second ?: return null
        val player = JfBackend.call("playerContent($flag)") { it.playerContent(flag, epId, emptyList()) }
        DiagLog.w("[JF] playerContent=${player?.take(1000)}")
        val p = runCatching { JsonParser.parseString(player).asJsonObject }.getOrNull() ?: return null
        val parse = p.intOf("parse"); val jx = p.intOf("jx")
        if (parse == 1 || jx == 1) { DiagLog.w("[JF] 不兼容：parse/jx=1"); return null }
        val urlEl = p.get("url")
        var url = when {
            urlEl == null || urlEl.isJsonNull -> ""
            urlEl.isJsonArray -> urlEl.asJsonArray.let { a -> if (a.size() >= 2) a[1].asString else "" } // ["清晰度名", "url", ...]
            else -> urlEl.asString
        }
        if (url.startsWith("[")) url = runCatching { JsonParser.parseString(url).asJsonArray[1].asString }.getOrDefault(url)
        if (!url.startsWith("http")) {
            DiagLog.w("[JF] 无可用 http 地址: ${url.take(100)} msg=${p.get("msg")}")
            return null
        }
        return Resolved(url, headerMap(p), System.currentTimeMillis()).also { resolved[id] = it }
    }

    private fun stream(s: IHTTPSession, id: String): Response {
        val r = resolve(id) ?: return text(Response.Status.NOT_FOUND, "no playable url")
        if (r.headers.isEmpty()) {
            DiagLog.w("[JF] stream $id -> 302 ${r.url.take(200)}")
            return redirect(r.url)
        }
        // Headers required: pass the bytes through with the spider's headers (works for single-file MP4/MKV/FLV).
        if (containerOf(r.url) == "hls") DiagLog.w("[JF] WARNING: HLS 且需要请求头，分片不会带头，可能播放失败（待做 m3u8 改写）")
        val b = Request.Builder().url(r.url)
        r.headers.forEach { (k, v) -> b.header(k, v) }
        s.headers["range"]?.let { b.header("Range", it) }
        val resp = http.newCall(b.build()).execute()
        val code = resp.code
        val mime = resp.header("Content-Type") ?: "application/octet-stream"
        val bodyStream = resp.body?.byteStream() ?: return text(Response.Status.INTERNAL_ERROR, "empty upstream")
        val len = resp.header("Content-Length")?.toLongOrNull()
        val out = if (len != null) NanoHTTPD.newFixedLengthResponse(status(code), mime, bodyStream, len)
        else NanoHTTPD.newChunkedResponse(status(code), mime, bodyStream)
        listOf("Content-Range", "Accept-Ranges").forEach { k -> resp.header(k)?.let { out.addHeader(k, it) } }
        DiagLog.w("[JF] stream $id passthrough upstream=$code len=$len")
        return out
    }

    private fun image(id: String): Response {
        val key = lookup(id) ?: return text(Response.Status.NOT_FOUND, "unknown id")
        val vodId = key.getOrNull(2) ?: return text(Response.Status.NOT_FOUND, "no image")
        val pic = if (key[1] == "view") "" else (vods[vodId]?.pic ?: details[vodId]?.vod?.pic).orEmpty()
        return if (pic.startsWith("http")) redirect(pic) else text(Response.Status.NOT_FOUND, "no image")
    }

    // ---------- spider calls ----------

    private fun search(term: String): List<Vod> {
        val res = JfBackend.call("searchContent($term)") { it.searchContent(term, false) }
        DiagLog.w("[JF] searchContent len=${res?.length}")
        return parseList(res)
    }

    private val pageSizes = ConcurrentHashMap<String, Int>()
    private val pages = ConcurrentHashMap<String, Pair<List<Vod>, Int>>()

    /** Returns (vods, pagecount). */
    private fun category(tid: String, page: Int): Pair<List<Vod>, Int> {
        pages["$tid|$page"]?.let { return it }
        val res = JfBackend.call("categoryContent($tid,$page)") { it.categoryContent(tid, page.toString(), false, HashMap()) }
        val obj = runCatching { JsonParser.parseString(res).asJsonObject }.getOrNull()
        val list = parseList(res)
        val pageCount = obj?.intOf("pagecount")?.takeIf { it > 0 } ?: (if (list.isEmpty()) page - 1 else page + 1)
        if (page == 1 && list.isNotEmpty()) pageSizes[tid] = list.size
        return (list to pageCount).also { pages["$tid|$page"] = it }
    }

    /** Jellyfin pages by StartIndex/Limit, CatVod by page number: map the window onto CatVod pages. */
    private fun categoryWindow(tid: String, start: Int, limit: Int?): Pair<List<Vod>, Int> {
        val first = category(tid, 1)
        val size = pageSizes[tid] ?: return emptyList<Vod>() to 0
        val want = limit ?: size
        val out = mutableListOf<Vod>()
        var page = start / size + 1
        var skip = start % size
        var pageCount = first.second
        while (out.size < want) {
            val (list, pc) = category(tid, page)
            pageCount = pc
            if (list.isEmpty()) break
            out += list.drop(skip)
            skip = 0
            if (page >= pc) break
            page++
        }
        val total = if (pageCount > 0) pageCount * size else start + out.size
        return out.take(want) to maxOf(total, start + out.size)
    }

    private fun detail(vodId: String): Detail? {
        details[vodId]?.let { return it }
        val res = JfBackend.call("detailContent($vodId)") { it.detailContent(listOf(vodId)) }
        val o = runCatching { JsonParser.parseString(res).asJsonObject.getAsJsonArray("list")[0].asJsonObject }.getOrNull()
            ?: run { DiagLog.w("[JF] detail 无 list[0]: ${res?.take(300)}"); return null }
        val v = toVod(o) ?: return null
        val flags = o.str("vod_play_from").split("$$$")
        val groups = o.str("vod_play_url").split("$$$")
        val eps = flags.indices.map { i ->
            groups.getOrNull(i).orEmpty().split('#').filter { it.isNotBlank() }.map { ep ->
                if ('$' in ep) ep.substringBefore('$') to ep.substringAfter('$') else ep to ep
            }
        }
        return Detail(v, o.str("vod_content"), o.str("vod_actor"), o.str("vod_director"), flags, eps)
            .also { details[vodId] = it; vods[vodId] = v }
    }

    private fun parseList(json: String?): List<Vod> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("list").mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject?.let { toVod(it) }
        }
    }.getOrDefault(emptyList()).onEach { vods[it.id] = it }

    private fun toVod(o: JsonObject): Vod? {
        val id = o.str("vod_id").ifBlank { return null }
        return Vod(id, o.str("vod_name"), o.str("vod_pic"), o.str("vod_remarks"), o.str("vod_year"))
    }

    private fun headerMap(p: JsonObject): Map<String, String> {
        val h = p.get("header") ?: p.get("headers") ?: return emptyMap()
        if (h.isJsonNull) return emptyMap()
        val obj = runCatching { if (h.isJsonObject) h.asJsonObject else JsonParser.parseString(h.asString).asJsonObject }.getOrNull()
            ?: return emptyMap()
        return obj.entrySet().associate { it.key to (if (it.value.isJsonPrimitive) it.value.asString else it.value.toString()) }
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.intOf(k: String) = get(k)?.let { runCatching { it.asInt }.getOrNull() } ?: 0

    // ---------- responses ----------

    private fun json(o: Any): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(st: Response.Status, s: String): Response =
        NanoHTTPD.newFixedLengthResponse(st, "text/plain; charset=utf-8", s)

    private fun noContent(): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")

    private fun redirect(url: String): Response =
        NanoHTTPD.newFixedLengthResponse(status(302), "text/plain", "").apply { addHeader("Location", url) }

    private fun status(code: Int): Response.IStatus = Response.Status.lookup(code) ?: object : Response.IStatus {
        override fun getDescription() = "$code " + if (code == 302) "Found" else "Upstream"
        override fun getRequestStatus() = code
    }

    fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
