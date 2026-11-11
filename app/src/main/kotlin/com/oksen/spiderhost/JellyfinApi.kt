package com.oksen.spiderhost

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

    private fun idOf(site: String, vararg parts: String): String {
        val key = listOf(site) + parts.toList()
        val id = md5(key.joinToString("\u0001"))
        if (ids.putIfAbsent(id, key) == null) {
            runCatching { idFile?.appendText(id + "\t" + key.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') } + "\n") }
        }
        return id
    }

    /** [siteKey, kind, ...] */
    private fun lookup(id: String): List<String>? = ids[id.lowercase().replace("-", "")]

    private fun viewId(site: String, tid: String) = idOf(site, "view", tid)
    private fun seriesId(site: String, vodId: String) = idOf(site, "series", vodId)
    private fun seasonId(site: String, vodId: String, f: Int) = idOf(site, "season", vodId, f.toString())
    private fun episodeId(site: String, vodId: String, f: Int, e: Int) = idOf(site, "episode", vodId, f.toString(), e.toString())
    private val sitesRootId get() = idOf("_", "sites")
    private fun siteFolderId(site: String) = idOf(site, "site")
    private fun seriesId(v: Vod) = seriesId(v.site, v.id)

    // ---------- caches ----------

    private data class Vod(val site: String, val id: String, val name: String, val pic: String, val remarks: String, val year: String)
    private data class Detail(
        val vod: Vod, val content: String, val actor: String, val director: String,
        val flags: List<String>, val episodes: List<List<Pair<String, String>>>,
    )
    private data class Resolved(val qualities: List<Pair<String, String>>, val headers: Map<String, String>, val at: Long)

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

    /** Libraries: the home site's categories (like 影视仓's 首页), then one 全部站点 library. */
    private fun views(): JSONObject {
        val arr = JSONArray()
        val home = SpiderHub.homeKey
        SpiderHub.classesOf(home).forEach { (tid, name) -> arr.put(folderDto(viewId(home, tid), name, "CollectionFolder", "tvshows")) }
        arr.put(folderDto(sitesRootId, "全部站点（${SpiderHub.served().size}）", "CollectionFolder", "folders"))
        return queryResult(arr, 0)
    }

    private fun folderDto(id: String, name: String, type: String, collectionType: String?, parentId: String? = null) = JSONObject()
        .put("Name", name)
        .put("ServerId", SERVER_ID)
        .put("Id", id)
        .put("Type", type)
        .apply { if (collectionType != null) put("CollectionType", collectionType) }
        .apply { if (parentId != null) put("ParentId", parentId) }
        .put("IsFolder", true)
        .put("ImageTags", JSONObject())
        .put("BackdropImageTags", JSONArray())
        .put("LocationType", "FileSystem")
        .put("UserData", userData(false))

    private fun siteFolders(): JSONArray {
        val arr = JSONArray()
        SpiderHub.served().forEach { s ->
            val mark = if (s.key == SpiderHub.homeKey) "★ " else ""
            arr.put(folderDto(siteFolderId(s.key), mark + SpiderHub.shortName(s.key), "Folder", null, sitesRootId))
        }
        return arr
    }

    private fun classFolders(site: String): JSONArray {
        val arr = JSONArray()
        SpiderHub.classesOf(site).forEach { (tid, name) -> arr.put(folderDto(viewId(site, tid), name, "Folder", null, siteFolderId(site))) }
        return arr
    }

    private fun latest(q: Map<String, String>): JSONArray {
        val parent = q["parentid"]?.let { lookup(it) }
        val (site, tid) = when (parent?.getOrNull(1)) {
            "view" -> parent[0] to parent[2]
            "sites", "site" -> return JSONArray()
            else -> SpiderHub.homeKey to (SpiderHub.classesOf(SpiderHub.homeKey).firstOrNull()?.first ?: return JSONArray())
        }
        val limit = q["limit"]?.toIntOrNull() ?: 16
        val arr = JSONArray()
        category(site, tid, 1).first.take(limit).forEach { arr.put(seriesDto(it, null)) }
        return arr
    }

    private fun items(q: Map<String, String>): JSONObject {
        val start = q["startindex"]?.toIntOrNull() ?: 0
        val term = q["searchterm"].orEmpty()
        if (term.isNotBlank()) {
            val types = q["includeitemtypes"].orEmpty().lowercase()
            // Everything we return is a Series; a search for only People/Episodes/etc gets nothing instead of duplicates.
            if (types.isNotBlank() && "series" !in types) return queryResult(JSONArray(), 0)
            val arr = JSONArray()
            search(term).forEach { arr.put(seriesDto(it, null)) }
            return queryResult(arr, 0)
        }
        q["ids"]?.takeIf { it.isNotBlank() }?.let { list ->
            val arr = JSONArray()
            list.split(',').forEach { id -> dtoFor(id.trim())?.let { arr.put(it) } }
            return queryResult(arr, 0)
        }
        val parentId = q["parentid"].orEmpty()
        val parent = lookup(parentId)
        return when (parent?.getOrNull(1)) {
            "sites" -> siteFolders().let { queryResult(it, 0) }
            "site" -> classFolders(parent[0]).let { queryResult(it, 0) }
            "view" -> {
                val pageSize = q["limit"]?.toIntOrNull()?.takeIf { it > 0 }
                val (list, total) = categoryWindow(parent[0], parent[2], start, pageSize)
                val arr = JSONArray()
                list.forEach { arr.put(seriesDto(it, null)) }
                queryResult(arr, start, total)
            }
            "series" -> seasons(parentId)
            "season" -> episodes(seriesId(parent[0], parent[2]), parentId)
            else -> {
                DiagLog.w("[JF] /Items without known ParentId/SearchTerm/Ids -> empty (params=${q.keys})")
                queryResult(JSONArray(), 0)
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
            "sites" -> folderDto(sitesRootId, "全部站点", "CollectionFolder", "folders")
            "site" -> folderDto(siteFolderId(key[0]), SpiderHub.shortName(key[0]), "Folder", null, sitesRootId)
            "view" -> {
                val name = SpiderHub.classesOf(key[0]).firstOrNull { it.first == key[2] }?.second ?: key[2]
                if (key[0] == SpiderHub.homeKey) folderDto(viewId(key[0], key[2]), name, "CollectionFolder", "tvshows")
                else folderDto(viewId(key[0], key[2]), name, "Folder", null, siteFolderId(key[0]))
            }
            "series" -> detail(key[0], key[2])?.let { seriesDto(it.vod, it) } ?: vods[key[0] + "|" + key[2]]?.let { seriesDto(it, null) }
            "season" -> detail(key[0], key[2])?.let { d -> seasonDto(d, key[3].toInt()) }
            "episode" -> detail(key[0], key[2])?.let { d -> episodeDto(d, key[3].toInt(), key[4].toInt(), withSources = true) }
            else -> null
        }
    }

    private fun seasons(seriesIdStr: String): JSONObject {
        val key = lookup(seriesIdStr) ?: return queryResult(JSONArray(), 0)
        val d = detail(key[0], key[2]) ?: return queryResult(JSONArray(), 0)
        val arr = JSONArray()
        d.flags.indices.forEach { arr.put(seasonDto(d, it)) }
        return queryResult(arr, 0)
    }

    private fun episodes(seriesIdStr: String, seasonIdStr: String?): JSONObject {
        val key = lookup(seriesIdStr) ?: return queryResult(JSONArray(), 0)
        val d = detail(key[0], key[2]) ?: return queryResult(JSONArray(), 0)
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
            val id = seriesId(v)
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
        .put("Id", seriesId(v))
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
        .put("Id", seasonId(d.vod.site, d.vod.id, f))
        .put("Type", "Season")
        .put("IsFolder", true)
        .put("IndexNumber", f + 1)
        .put("SeriesId", seriesId(d.vod))
        .put("SeriesName", d.vod.name)
        .put("ParentId", seriesId(d.vod))
        .put("ChildCount", d.episodes.getOrNull(f)?.size ?: 0)
        .put("LocationType", "Remote")
        .put("UserData", userData(false))
        .put("ImageTags", imageTags(d.vod.pic))
        .apply { if (d.vod.pic.isNotBlank()) put("SeriesPrimaryImageTag", md5(d.vod.pic).take(16)) }

    private fun episodeDto(d: Detail, f: Int, e: Int, withSources: Boolean): JSONObject {
        val id = episodeId(d.vod.site, d.vod.id, f, e)
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
            .put("SeasonId", seasonId(d.vod.site, d.vod.id, f))
            .put("SeasonName", d.flags.getOrElse(f) { "线路${f + 1}" })
            .put("SeriesId", seriesId(d.vod))
            .put("SeriesName", d.vod.name)
            .put("ParentId", seasonId(d.vod.site, d.vod.id, f))
            .put("UserData", userData(false))
            .put("ImageTags", imageTags(d.vod.pic))
            .apply { if (d.vod.pic.isNotBlank()) put("SeriesPrimaryImageTag", md5(d.vod.pic).take(16)) }
            .apply { if (withSources) put("MediaSources", JSONArray().put(mediaSource(id, null, 0))) }
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

    /**
     * One MediaSource per quality when the spider returns a url array (["1080P","u1","720P","u2"]),
     * so the player's version/source menu can switch between them.
     */
    private fun mediaSource(itemId: String, r: Resolved?, index: Int): JSONObject {
        val q = r?.qualities?.getOrNull(index)
        val container = q?.second?.let { containerOf(it) } ?: "mp4"
        val msId = if (index == 0) itemId else qualityId(itemId, index)
        return JSONObject()
            .put("Protocol", "Http")
            .put("Id", msId)
            .put("Path", "$base/Videos/$itemId/stream?static=true&MediaSourceId=$msId")
            .put("Type", "Default")
            .put("Container", container)
            .put("Name", q?.first?.ifBlank { null } ?: "SpiderHost")
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

    private val qualityIds = ConcurrentHashMap<String, Pair<String, Int>>()
    private fun qualityId(itemId: String, i: Int) = md5("$itemId|q$i").also { qualityIds[it] = itemId to i }

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
        DiagLog.w("[JF] PlaybackInfo $id -> ${r?.qualities?.joinToString { "${it.first}=${it.second.take(120)}" } ?: "无地址"} headers=${r?.headers?.keys}")
        return JSONObject()
            .put("MediaSources", JSONArray().apply { r?.qualities?.indices?.forEach { put(mediaSource(id, r, it)) } })
            .put("PlaySessionId", md5("ps|$id|${System.currentTimeMillis()}"))
            .apply { if (r == null) put("ErrorCode", "NoCompatibleStream") }
    }

    private fun resolve(id: String): Resolved? {
        resolved[id]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }?.let { return it }
        val key = lookup(id)?.takeIf { it.getOrNull(1) == "episode" } ?: run {
            DiagLog.w("[JF] resolve: $id 不是剧集 id"); return null
        }
        val site = key[0]
        val d = detail(site, key[2]) ?: return null
        val f = key[3].toInt(); val e = key[4].toInt()
        val flag = d.flags.getOrNull(f) ?: return null
        val epId = d.episodes.getOrNull(f)?.getOrNull(e)?.second ?: return null
        val player = SpiderHub.on(site, "playerContent($flag)", 60) { it.playerContent(flag, epId, emptyList()) }
        DiagLog.w("[JF] playerContent=${player?.take(1000)}")
        val p = runCatching { JsonParser.parseString(player).asJsonObject }.getOrNull() ?: return null
        val parse = p.intOf("parse"); val jx = p.intOf("jx")
        if (parse == 1 || jx == 1) { DiagLog.w("[JF] 不兼容：parse/jx=1（需要网页嗅探/解析）"); return null }
        val qualities = qualitiesOf(p)
        if (qualities.isEmpty()) {
            DiagLog.w("[JF] 无可用 http 地址: url=${p.get("url")?.toString()?.take(200)} msg=${p.get("msg")}")
            return null
        }
        // A play URL that points back at 127.0.0.1:9978/proxy must reach this site's spider/jar.
        SpiderHub.activateForProxy(site)
        return Resolved(qualities, headerMap(p), System.currentTimeMillis()).also { resolved[id] = it }
    }

    /** "url" may be a string, a JSON array, or a string holding a JSON array: ["name1","url1","name2","url2",...]. */
    private fun qualitiesOf(p: JsonObject): List<Pair<String, String>> {
        val el = p.get("url") ?: return emptyList()
        if (el.isJsonNull) return emptyList()
        val arr = when {
            el.isJsonArray -> el.asJsonArray
            el.asString.trim().startsWith("[") -> runCatching { JsonParser.parseString(el.asString).asJsonArray }.getOrNull()
            else -> null
        }
        val out = mutableListOf<Pair<String, String>>()
        if (arr != null) {
            val items = arr.map { if (it.isJsonPrimitive) it.asString else it.toString() }
            var i = 0
            while (i + 1 < items.size) { out += items[i] to items[i + 1]; i += 2 }
        } else out += "" to el.asString
        return out.filter { it.second.startsWith("http") }
    }

    private fun stream(s: IHTTPSession, id: String): Response {
        val ms = s.parameters["MediaSourceId"]?.firstOrNull() ?: s.parameters["mediaSourceId"]?.firstOrNull()
        val (itemId, qi) = ms?.let { qualityIds[it.lowercase()] } ?: (id to 0)
        val r = resolve(itemId) ?: return text(Response.Status.NOT_FOUND, "no playable url")
        val url = r.qualities.getOrNull(qi)?.second ?: r.qualities[0].second
        val local = url.startsWith("http://127.0.0.1:${LocalServer.PORT}/")
        if (r.headers.isEmpty() || local) {
            DiagLog.w("[JF] stream $itemId q$qi -> 302 ${url.take(200)}")
            return redirect(url)
        }
        return HlsRelay.fetch(s, url, r.headers, "[JF] stream $itemId q$qi")
    }

    private fun image(id: String): Response {
        val key = lookup(id) ?: return text(Response.Status.NOT_FOUND, "unknown id")
        val kind = key.getOrNull(1)
        if (kind != "series" && kind != "season" && kind != "episode") return text(Response.Status.NOT_FOUND, "no image")
        val k = key[0] + "|" + key[2]
        val pic = (vods[k]?.pic ?: details[k]?.vod?.pic).orEmpty()
        return if (pic.startsWith("http")) redirect(pic) else text(Response.Status.NOT_FOUND, "no image")
    }

    // ---------- spider calls ----------

    private data class SearchHit(val at: Long, val list: List<Vod>)
    private val searches = ConcurrentHashMap<String, SearchHit>()

    /** Global search: every enabled searchable site in parallel, 15 s overall, results tagged with the site name. */
    private fun search(term: String): List<Vod> {
        searches[term]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }?.let { return it.list }
        val keys = SpiderHub.served().filter { it.searchable }.map { it.key }
        val started = System.currentTimeMillis()
        val results = SpiderHub.fanOut(keys, 15) { k ->
            parseList(k, SpiderHub.on(k, "searchContent($term)", 12) { it.searchContent(term, false) })
        }
        val byKey = results.toMap()
        // Keep config order so the user's preferred sites come first.
        val list = keys.flatMap { k -> byKey[k].orEmpty().map { it.copy(name = "${it.name} · ${SpiderHub.shortName(k)}") } }
        DiagLog.w("[JF] 搜索「$term」${keys.size} 站，${results.count { it.second.isNotEmpty() }} 站有结果，共 ${list.size} 条，用时 ${System.currentTimeMillis() - started} ms")
        if (list.isNotEmpty()) searches[term] = SearchHit(System.currentTimeMillis(), list)
        return list
    }

    private val pageSizes = ConcurrentHashMap<String, Int>()
    private val pages = ConcurrentHashMap<String, Pair<List<Vod>, Int>>()

    /** Returns (vods, pagecount). */
    private fun category(site: String, tid: String, page: Int): Pair<List<Vod>, Int> {
        val ck = "$site|$tid|$page"
        pages[ck]?.let { return it }
        val res = SpiderHub.on(site, "categoryContent($tid,$page)") { it.categoryContent(tid, page.toString(), false, HashMap()) }
        val obj = runCatching { JsonParser.parseString(res).asJsonObject }.getOrNull()
        val list = parseList(site, res)
        val pageCount = obj?.intOf("pagecount")?.takeIf { it > 0 } ?: (if (list.isEmpty()) page - 1 else page + 1)
        if (page == 1 && list.isNotEmpty()) pageSizes["$site|$tid"] = list.size
        val out = list to pageCount
        if (list.isNotEmpty()) pages[ck] = out
        return out
    }

    /** Jellyfin pages by StartIndex/Limit, CatVod by page number: map the window onto CatVod pages. */
    private fun categoryWindow(site: String, tid: String, start: Int, limit: Int?): Pair<List<Vod>, Int> {
        val first = category(site, tid, 1)
        val size = pageSizes["$site|$tid"] ?: return emptyList<Vod>() to 0
        val want = limit ?: size
        val out = mutableListOf<Vod>()
        var page = start / size + 1
        var skip = start % size
        var pageCount = first.second
        while (out.size < want) {
            val (list, pc) = category(site, tid, page)
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

    private val inflight = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Detail?>>()

    /**
     * SenPlayer opens a detail page with several requests at once (item, seasons, episodes...).
     * Without this, each one called detailContent on the spider in parallel; one slow or failed call
     * made SenPlayer show 详情加载失败. Now concurrent callers share one call, and a failure is retried once.
     */
    private fun detail(site: String, vodId: String): Detail? {
        val k = "$site|$vodId"
        details[k]?.let { return it }
        val mine = java.util.concurrent.CompletableFuture<Detail?>()
        val existing = inflight.putIfAbsent(k, mine)
        if (existing != null) return runCatching { existing.get(70, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        try {
            val d = loadDetail(site, vodId) ?: run {
                DiagLog.w("[JF] detail $k 第一次失败，重试一次")
                loadDetail(site, vodId)
            }
            mine.complete(d)
            return d
        } catch (t: Throwable) {
            mine.complete(null); throw t
        } finally {
            inflight.remove(k, mine)
        }
    }

    private fun loadDetail(site: String, vodId: String): Detail? {
        val k = "$site|$vodId"
        val res = SpiderHub.on(site, "detailContent($vodId)") { it.detailContent(listOf(vodId)) }
        val o = runCatching { JsonParser.parseString(res).asJsonObject.getAsJsonArray("list")[0].asJsonObject }.getOrNull()
            ?: run { DiagLog.w("[JF] detail 无 list[0]: ${res?.take(300)}"); return null }
        val v = toVod(site, o) ?: return null
        // Search results carry a "· 站点" suffix; keep it so the user sees which source they are in.
        val shown = vods[k]?.name?.takeIf { it.startsWith(v.name) } ?: v.name
        val flags = o.str("vod_play_from").split("$$$")
        val groups = o.str("vod_play_url").split("$$$")
        val eps = flags.indices.map { i ->
            groups.getOrNull(i).orEmpty().split('#').filter { it.isNotBlank() }.map { ep ->
                if ('$' in ep) ep.substringBefore('$') to ep.substringAfter('$') else ep to ep
            }
        }
        return Detail(v.copy(name = shown), o.str("vod_content"), o.str("vod_actor"), o.str("vod_director"), flags, eps)
            .also { details[k] = it; if (vods[k] == null) vods[k] = v }
    }

    private fun parseList(site: String, json: String?): List<Vod> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("list").mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject?.let { toVod(site, it) }
        }
    }.getOrDefault(emptyList()).onEach { vods.putIfAbsent("$site|${it.id}", it) }

    private fun toVod(site: String, o: JsonObject): Vod? {
        val id = o.str("vod_id").ifBlank { return null }
        return Vod(site, id, o.str("vod_name"), o.str("vod_pic"), o.str("vod_remarks"), o.str("vod_year"))
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
