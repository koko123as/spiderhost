package com.oksen.spiderhost

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Local HTTP server. Listens on all interfaces because spider jars hand out pages at the phone's
 * LAN address (e.g. http://192.168.1.54:9978/proxy?do=pan&action=config), but only answers
 * requests coming from this phone itself (loopback or one of its own addresses).
 *   GET /health  -> JSON status
 *   ANY /proxy   -> CatVod-style passthrough. Routing modelled on Kototoro's
 *                   handleProxyRequest (Apache-2.0): "do" -> spider.proxyLocal(params),
 *                   otherwise (e.g. "go") -> static com.github.catvod.spider.Proxy.proxy(params).
 * Spider result convention: Object[]{ statusCode:Int, contentType:String, body:(InputStream|byte[]|String), headers:Map? }.
 */
class LocalServer : NanoHTTPD("0.0.0.0", PORT) {

    @Volatile private var own: Set<String> = emptySet()
    @Volatile private var ownAt = 0L

    private fun isSelf(ip: String?): Boolean {
        if (ip == null) return false
        val a = ip.removePrefix("/").substringBefore('%')
        if (a == "127.0.0.1" || a == "::1" || a == "0:0:0:0:0:0:0:1" || a.startsWith("::ffff:127.")) return true
        val now = System.currentTimeMillis()
        if (now - ownAt > 30_000) {
            own = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }.map { it.hostAddress.orEmpty().substringBefore('%') }.toSet()
            }.getOrDefault(emptySet())
            ownAt = now
        }
        return a in own || a.removePrefix("::ffff:") in own
    }


    override fun serve(session: IHTTPSession): Response = try {
        if (!isSelf(session.remoteIpAddress)) {
            DiagLog.w("LocalServer: 拒绝外部请求 ${session.remoteIpAddress} ${session.uri}")
            newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "local only")
        } else when (session.uri.trimEnd('/')) {
            "/health" -> json(JSONObject()
                .put("ok", true)
                .put("port", PORT)
                .put("siteKey", SpiderSession.siteKey ?: JSONObject.NULL)
                .put("spiderLoaded", SpiderSession.spider != null)
                .put("staticProxy", SpiderSession.staticProxy != null)
                .put("homeSite", SpiderHub.homeKey)
                .put("jellyfinReady", SpiderHub.ready)
                .put("servedSites", SpiderHub.served().size))
            "/proxy" -> proxy(session)
            "/hls" -> HlsRelay.serve(session)
            // Everything else is the Jellyfin-compatible API (stage 2); it logs every request itself.
            else -> JellyfinApi.handle(session, session.uri)
        }
    } catch (t: Throwable) {
        DiagLog.e("LocalServer ${session.uri} failed", t)
        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, DiagLog.fullTrace(t))
    }

    private fun proxy(session: IHTTPSession): Response {
        val params = LinkedHashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            val files = HashMap<String, String>()
            runCatching { session.parseBody(files) }
            files["postData"]?.let { params["body"] = it }
        }
        session.parameters.forEach { (k, v) -> v.firstOrNull()?.let { params[k] = it } }
        session.headers.forEach { (k, v) -> params.putIfAbsent(k, v) }
        params["request-headers"] = JSONObject(session.headers as Map<*, *>).toString()

        val loader = SpiderSession.classLoader
        val spider = SpiderSession.spider
        val thread = Thread.currentThread()
        val prev = thread.contextClassLoader
        if (loader != null) thread.contextClassLoader = loader
        val result: Any? = try {
            // "do" goes to the spider's proxyLocal first; when that gives nothing (some jars handle
            // do=pan etc. only in their static spider.Proxy.proxy), the static proxy is tried.
            val first = if (params.containsKey("do") && spider != null) runCatching { spider.proxyLocal(params) }.getOrNull() else null
            if ((first as? Array<*>)?.isNotEmpty() == true) first
            else SpiderSession.staticProxy?.invoke(null, params) ?: first
        } finally {
            thread.contextClassLoader = prev
        }
        DiagLog.w("/proxy params=${params.keys.filterNot { it == "request-headers" }} -> ${describe(result)}")
        return toResponse(result as? Array<*>)
    }

    private fun toResponse(r: Array<*>?): Response {
        if (r == null || r.isEmpty()) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "spider proxy returned empty result")
        }
        val code = (r.getOrNull(0) as? Number)?.toInt() ?: 500
        val mime = r.getOrNull(1)?.toString().orEmpty().ifBlank { "application/octet-stream" }
        val status: Response.IStatus = Response.Status.lookup(code) ?: object : Response.IStatus {
            override fun getDescription() = "$code Spider"
            override fun getRequestStatus() = code
        }
        val resp = when (val body = r.getOrNull(2)) {
            is InputStream -> newChunkedResponse(status, mime, body)
            is ByteArray -> newFixedLengthResponse(status, mime, ByteArrayInputStream(body), body.size.toLong())
            null -> newFixedLengthResponse(status, mime, "")
            else -> newFixedLengthResponse(status, mime, body.toString())
        }
        (r.getOrNull(3) as? Map<*, *>)?.forEach { (k, v) ->
            if (k != null && v != null) resp.addHeader(k.toString(), v.toString())
        }
        return resp
    }

    private fun describe(r: Any?): String = when (r) {
        is Array<*> -> "Object[${r.size}] code=${r.getOrNull(0)} type=${r.getOrNull(1)} body=${r.getOrNull(2)?.javaClass?.simpleName}"
        null -> "null"
        else -> r.javaClass.name
    }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    companion object {
        const val PORT = 9978
    }
}
