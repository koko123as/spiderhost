package com.oksen.spiderhost

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 收藏与观看进度，存在本机 files/jf-user.json，供 SenPlayer 的「收藏」「继续观看」「下一集」使用。
 *
 * 只记录 SenPlayer 通过标准 Jellyfin 接口报给服务器的东西（FavoriteItems、PlayedItems、
 * Sessions/Playing*、Items/{id}/UserData）。SenPlayer 实际调用哪些，以日志里的 [JF] 和 USER 行为准。
 * 每条记录带一份快照（名字、封面、站点坐标），这样首页的继续观看/收藏不用为每一项再请求站点。
 */
object UserStore {

    class Snap(
        val id: String,
        val kind: String,          // series | group | episode
        val name: String,
        val pic: String,
        val year: String,
        // episode only
        val seriesId: String = "",
        val seriesName: String = "",
        val seasonId: String = "",
        val seasonName: String = "",
        val index: Int = 0,
        val parentIndex: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("kind", kind).put("name", name).put("pic", pic).put("year", year)
            .put("seriesId", seriesId).put("seriesName", seriesName)
            .put("seasonId", seasonId).put("seasonName", seasonName)
            .put("index", index).put("parentIndex", parentIndex)

        companion object {
            fun of(o: JSONObject) = Snap(
                o.optString("id"), o.optString("kind"), o.optString("name"), o.optString("pic"), o.optString("year"),
                o.optString("seriesId"), o.optString("seriesName"), o.optString("seasonId"), o.optString("seasonName"),
                o.optInt("index"), o.optInt("parentIndex"),
            )
        }
    }

    class Fav(val snap: Snap, val at: Long)
    class Prog(val snap: Snap, val ticks: Long, val runtime: Long, val played: Boolean, val at: Long)

    private const val MAX_PROGRESS = 300
    private val lock = Any()
    private var file: File? = null
    private val favs = LinkedHashMap<String, Fav>()
    private val progs = LinkedHashMap<String, Prog>()

    fun init(dir: File) { synchronized(lock) {
        val f = File(dir, "jf-user.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.optJSONArray("favorites")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { favs[it.getString("id")] = Fav(Snap.of(it.getJSONObject("snap")), it.optLong("at")) }
            }
            o.optJSONArray("progress")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    progs[it.getString("id")] = Prog(Snap.of(it.getJSONObject("snap")), it.optLong("ticks"), it.optLong("runtime"), it.optBoolean("played"), it.optLong("at"))
                }
            }
        }.onFailure { DiagLog.w("USER 读取 jf-user.json 失败: $it") }
    } }

    private fun save() {
        val o = JSONObject()
            .put("favorites", JSONArray().apply { favs.forEach { (id, f) -> put(JSONObject().put("id", id).put("at", f.at).put("snap", f.snap.toJson())) } })
            .put("progress", JSONArray().apply { progs.forEach { (id, p) -> put(JSONObject().put("id", id).put("ticks", p.ticks).put("runtime", p.runtime).put("played", p.played).put("at", p.at).put("snap", p.snap.toJson())) } })
        runCatching {
            val f = file ?: return
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }.onFailure { DiagLog.w("USER 写入失败: $it") }
    }

    // ---------- favorites ----------

    fun isFavorite(id: String) = synchronized(lock) { favs.containsKey(id) }

    fun setFavorite(snap: Snap, on: Boolean): Unit = synchronized(lock) {
        if (on) favs[snap.id] = Fav(snap, System.currentTimeMillis()) else favs.remove(snap.id)
        save()
        DiagLog.w("USER ${if (on) "收藏" else "取消收藏"} ${snap.kind} ${snap.name}")
    }

    /** Newest first. */
    fun favorites(): List<Fav> = synchronized(lock) { favs.values.sortedByDescending { it.at } }

    // ---------- progress ----------

    fun progress(id: String): Prog? = synchronized(lock) { progs[id] }

    fun report(snap: Snap, ticks: Long?, runtime: Long?, played: Boolean?): Unit = synchronized(lock) {
        val old = progs[snap.id]
        val rt = runtime?.takeIf { it > 0 } ?: old?.runtime ?: 0L
        val pos = ticks ?: old?.ticks ?: 0L
        // Past 90% counts as watched, like Jellyfin's default MaxResumePct.
        val done = played ?: ((rt > 0 && pos >= rt * 9 / 10) || (old?.played == true && ticks == null))
        progs.remove(snap.id)
        progs[snap.id] = Prog(snap, if (done) 0L else pos, rt, done, System.currentTimeMillis())
        while (progs.size > MAX_PROGRESS) progs.remove(progs.keys.first())
        save()
    }

    fun seriesOf(seriesId: String): List<Prog> = synchronized(lock) { progs.values.filter { it.snap.seriesId == seriesId } }

    /** Unfinished episodes, newest first, one per series. */
    fun resume(): List<Prog> = synchronized(lock) {
        progs.values.filter { !it.played && it.ticks > 0 }.sortedByDescending { it.at }.distinctBy { it.snap.seriesId }
    }

    /** Last finished episode per series, newest first: candidates for 下一集. */
    fun lastWatched(): List<Prog> = synchronized(lock) {
        progs.values.sortedByDescending { it.at }.distinctBy { it.snap.seriesId }.filter { it.played }
    }
}
