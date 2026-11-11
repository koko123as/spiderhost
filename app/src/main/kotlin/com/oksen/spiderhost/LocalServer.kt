package com.oksen.spiderhost

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 127.0.0.1-only HTTP server.
 *   GET /health  -> JSON status
 *   ANY /proxy   -> CatVod-style passthrough. Routing modelled on Kototoro's
 *                   handleProxyRequest (Apache-2.0): "do" -> spider.proxyLocal(params),
 *                   otherwise (e.g. "go") -> static com.github.catvod.spider.Proxy.proxy(params).
 * Spider result convention: Object[]{ statusCode:Int, contentType:String, body:(InputStream|byte[]|String), headers:Map? }.
 */
class LocalServer : NanoHTTPD("127.0.0.1", PORT) {

    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri.trimEnd('/')) {
            "/health" -> json(JSONObject()
                .put("ok", true)
                .put("port", PORT)
                .put("siteKey", SpiderSession.siteKey ?: JSONObject.NULL)
                .put("spiderLoaded", SpiderSession.spider != null)
                .put("staticProxy", SpiderSession.staticProxy != null)
                .put("jellyfinSite", JfBackend.siteKey)
                .put("jellyfinReady", JfBackend.spider != null)
                .put("classes", JfBackend.classes.size))
            "/proxy" -> proxy(session)
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
            when {
                params.containsKey("do") && spider != null -> spider.proxyLocal(params)
                SpiderSession.staticProxy != null -> SpiderSession.staticProxy!!.invoke(null, params)
                else -> null
            }
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
