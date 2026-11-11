package com.oksen.spiderhost

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Plays a URL that needs request headers (Referer/User-Agent/Cookie) through 127.0.0.1.
 *   - single files (mp4/mkv/flv): bytes passed through with the headers, Range forwarded;
 *   - HLS: the playlist is rewritten so every segment, sub-playlist and key URI goes back through
 *     /hls?h=<header set>&u=<base64 url>, so the headers are sent on every request, not just the first.
 */
object HlsRelay {

    private val http = OkHttpClient.Builder().dns(Net.dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val headerSets = ConcurrentHashMap<String, Map<String, String>>()

    private fun register(h: Map<String, String>): String =
        JellyfinApi.md5(h.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}:${it.value}" }).take(16)
            .also { headerSets[it] = h }

    /** GET /hls?h=..&u=.. */
    fun serve(s: IHTTPSession): Response {
        val u = s.parameters["u"]?.firstOrNull() ?: return bad("missing u")
        val url = runCatching { String(Base64.decode(u, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() ?: return bad("bad u")
        val h = headerSets[s.parameters["h"]?.firstOrNull().orEmpty()].orEmpty()
        return fetch(s, url, h, null)
    }

    fun fetch(s: IHTTPSession, url: String, headers: Map<String, String>, logLabel: String?): Response {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        s.headers["range"]?.let { b.header("Range", it) }
        val resp = http.newCall(b.build()).execute()
        val code = resp.code
        val mime = resp.header("Content-Type").orEmpty()
        val playlist = url.substringBefore('?').lowercase().endsWith(".m3u8") || mime.contains("mpegurl", true)
        if (playlist && resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            resp.close()
            val finalUrl = resp.request.url.toString()
            val hid = register(headers)
            val out = rewrite(text, finalUrl, hid)
            if (logLabel != null) DiagLog.w("$logLabel HLS 改写 ${text.lines().count { it.isNotBlank() && !it.startsWith("#") }} 条 URI，请求头 ${headers.keys}")
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", out)
        }
        if (!resp.isSuccessful) DiagLog.w("${logLabel ?: "[HLS]"} upstream $code for ${url.take(160)}")
        val body = resp.body?.byteStream() ?: return bad("empty upstream")
        val len = resp.header("Content-Length")?.toLongOrNull()
        val st = status(code)
        val out = if (len != null) NanoHTTPD.newFixedLengthResponse(st, mime.ifBlank { "application/octet-stream" }, body, len)
        else NanoHTTPD.newChunkedResponse(st, mime.ifBlank { "application/octet-stream" }, body)
        listOf("Content-Range", "Accept-Ranges").forEach { k -> resp.header(k)?.let { out.addHeader(k, it) } }
        if (logLabel != null) DiagLog.w("$logLabel passthrough upstream=$code len=$len type=$mime")
        return out
    }

    private val uriAttr = Regex("URI=\"([^\"]+)\"")

    private fun rewrite(text: String, base: String, hid: String): String {
        val baseUrl = base.toHttpUrlOrNull()
        fun local(ref: String): String {
            val abs = baseUrl?.resolve(ref.trim())?.toString() ?: ref.trim()
            val enc = Base64.encodeToString(abs.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            return "http://127.0.0.1:${LocalServer.PORT}/hls?h=$hid&u=$enc"
        }
        return text.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"" + local(m.groupValues[1]) + "\"" }
                else -> local(line)
            }
        }
    }

    private fun bad(msg: String) = NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)

    private fun status(code: Int): Response.IStatus = Response.Status.lookup(code) ?: object : Response.IStatus {
        override fun getDescription() = "$code Upstream"
        override fun getRequestStatus() = code
    }
}
