package com.oksen.spiderhost

import com.github.catvod.crawler.Spider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Stage 1 probe: search/home result -> detailContent -> playerContent -> fetch the play URL.
 * Only records what the spider returns and what the URL answers; no assumptions about fields
 * beyond the CatVod convention (vod_play_from "$$$", vod_play_url "$$$" / "#" / "name$id").
 */
class PlayProbe(
    private val spider: Spider,
    private val loader: ClassLoader,
    private val maxFlags: Int,
    private val step: (String) -> Unit,
    private val callFn: (String, ClassLoader, () -> String?) -> String?,
) {
    private val http = OkHttpClient.Builder().dns(Net.dns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun run(search: String?, home: String?): String {
        val vodId = firstVodId(search) ?: firstVodId(home)
        if (vodId == null) { DiagLog.w("PROBE: 搜索和首页都没有 vod_id，跳过播放验证"); return "无 vod_id" }

        step("12 detailContent([$vodId])")
        val detail = callFn("detailContent", loader) { spider.detailContent(listOf(vodId)) }
        DiagLog.w("detailContent length=${detail?.length}\n${detail?.take(3000)}")
        val vod = runCatching { JsonParser.parseString(detail).asJsonObject.getAsJsonArray("list")[0].asJsonObject }.getOrNull()
        if (vod == null) { DiagLog.w("PROBE: detail 没有 list[0]"); return "detail 无 list" }
        DiagLog.w("detail 字段: ${vod.keySet().joinToString()}")

        val froms = vod.s("vod_play_from").split("$$$")
        val urls = vod.s("vod_play_url").split("$$$")
        DiagLog.w("线路 ${froms.size} 条: ${froms.joinToString(" | ")}; url 组 ${urls.size} 个")
        val summary = mutableListOf<String>()
        froms.zip(urls).take(maxFlags).forEachIndexed { i, (flag, group) ->
            val eps = group.split("#").filter { it.isNotBlank() }
            val first = eps.firstOrNull() ?: return@forEachIndexed
            val epName = first.substringBefore('$')
            val epId = if ('$' in first) first.substringAfter('$') else first
            DiagLog.w("线路[$i] $flag: ${eps.size} 集, 首集 name=$epName id=${epId.take(200)}")

            step("13.$i playerContent($flag)")
            val player = callFn("playerContent($flag)", loader) { spider.playerContent(flag, epId, emptyList()) }
            DiagLog.w("playerContent=${player?.take(2000)}")
            val p = runCatching { JsonParser.parseString(player).asJsonObject }.getOrNull()
            if (p == null) { summary += "[$i] $flag: playerContent 非 JSON"; return@forEachIndexed }
            DiagLog.w("player 字段: ${p.keySet().joinToString()}")
            val parse = p.i("parse"); val jx = p.i("jx")
            val url = p.get("url")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
            val headers = headerMap(p)
            DiagLog.w("parse=$parse jx=$jx headers=$headers url=${url.take(300)}")
            val verdict = when {
                parse == 1 || jx == 1 -> "不兼容（parse/jx=1，需要解析/嗅探）"
                url.isBlank() -> "无 url" + (p.get("msg")?.takeIf { it.isJsonPrimitive }?.asString?.let { "（$it）" } ?: "")
                url.startsWith("http") -> probe(url, headers)
                else -> "非 http url（${url.substringBefore(':').take(20)}），需另行处理"
            }
            summary += "[$i] $flag: $verdict"
        }
        DiagLog.w("PROBE 结论:\n  " + summary.joinToString("\n  "))
        return summary.joinToString("; ").ifBlank { "无线路" }
    }

    private fun probe(url: String, headers: Map<String, String>): String {
        step("14 GET 播放地址 (Range bytes=0-2047)")
        var cur = url
        repeat(5) { hop ->
            val b = Request.Builder().url(cur).header("Range", "bytes=0-2047")
            headers.forEach { (k, v) -> b.header(k, v) }
            if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", "SenPlayer/1.0.0")
            val r = runCatching { http.newCall(b.build()).execute() }.getOrElse {
                DiagLog.e("probe 失败 $cur", it); return "请求失败 ${it.javaClass.simpleName}: ${it.message}"
            }
            r.use { resp ->
                val ct = resp.header("Content-Type").orEmpty()
                DiagLog.w("hop$hop HTTP ${resp.code} ct=$ct len=${resp.header("Content-Length")} cr=${resp.header("Content-Range")} host=${resp.request.url.host}")
                if (resp.code in 300..399) {
                    cur = resp.header("Location")?.let { resp.request.url.resolve(it)?.toString() } ?: return "重定向无 Location"
                    return@repeat
                }
                val bytes = resp.body?.byteStream()?.readNBytesCompat(2048) ?: ByteArray(0)
                val head = String(bytes, Charsets.UTF_8).take(400)
                DiagLog.w("body 开头:\n$head")
                val kind = when {
                    head.startsWith("#EXTM3U") -> "HLS"
                    bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "MP4"
                    bytes.isNotEmpty() && bytes[0] == 0x47.toByte() -> "TS"
                    else -> "未知($ct)"
                }
                val need = if (headers.isEmpty()) "无需请求头" else "需请求头→走 /proxy"
                return if (resp.code in 200..299) "可直连 $kind，HTTP ${resp.code}，$need" else "HTTP ${resp.code} $kind"
            }
        }
        return "重定向过多"
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(n)
        var left = n
        while (left > 0) { val c = read(buf, 0, left); if (c <= 0) break; out.write(buf, 0, c); left -= c }
        return out.toByteArray()
    }

    private fun headerMap(p: JsonObject): Map<String, String> {
        val h = p.get("header") ?: p.get("headers") ?: return emptyMap()
        val obj = runCatching { if (h.isJsonObject) h.asJsonObject else JsonParser.parseString(h.asString).asJsonObject }.getOrNull()
            ?: return emptyMap()
        return obj.entrySet().associate { it.key to (if (it.value.isJsonPrimitive) it.value.asString else it.value.toString()) }
    }

    private fun firstVodId(json: String?): String? = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("list")
            .firstNotNullOfOrNull { it.asJsonObject.get("vod_id")?.asString?.takeIf(String::isNotBlank) }
    }.getOrNull()

    private fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.i(k: String) = get(k)?.let { runCatching { it.asInt }.getOrNull() } ?: 0
}
