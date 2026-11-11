package com.oksen.spiderhost

import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.StringReader

/** Just enough TVBox config handling for the spike. */
object TvboxConfig {

    data class Site(val key: String, val name: String, val type: Int, val api: String, val ext: String, val jarRaw: String?, val searchable: Boolean = true)
    data class JarSpec(val raw: String, val url: String, val md5: String?)

    /**
     * Turns the fetched body into JSON text. Handles plain JSON and the common
     * "image-wrapped" form where base64 JSON follows a "**" marker (inferred from
     * FongMi/TVBox behaviour). AES-encrypted configs ("2423..." hex) are NOT handled.
     */
    fun decodeBody(body: String): String {
        val trimmed = body.trim().removePrefix("\uFEFF")
        if (trimmed.startsWith("{")) return trimmed
        val marker = trimmed.indexOf("**")
        if (marker >= 0) {
            val b64 = trimmed.substring(marker + 2).trim()
            val decoded = runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            if (decoded != null && decoded.trim().startsWith("{")) return decoded.trim()
        }
        if (trimmed.startsWith("2423")) {
            throw IllegalStateException("配置看起来是 AES 加密格式（2423 开头），本 spike 未实现解密")
        }
        throw IllegalStateException("无法识别的配置格式，前 120 字符: ${trimmed.take(120)}")
    }

    /** Lenient parse: TVBox configs often contain // comments and trailing commas. */
    fun parse(json: String): JsonObject {
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        return JsonParser.parseReader(reader).asJsonObject
    }

    fun sites(root: JsonObject): List<Site> = root.getAsJsonArray("sites")?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        Site(
            key = o.str("key"),
            name = o.str("name"),
            type = o.get("type")?.let { runCatching { it.asInt }.getOrNull() } ?: -1,
            api = o.str("api"),
            ext = o.get("ext")?.let { extToString(it) }.orEmpty(),
            jarRaw = o.str("jar").ifBlank { null },
            // TVBox convention: searchable 0 means "skip in global search"; missing means searchable.
            searchable = o.get("searchable")?.let { runCatching { it.asInt }.getOrNull() } != 0,
        )
    }.orEmpty()

    private fun extToString(el: JsonElement): String =
        if (el.isJsonPrimitive) el.asString else el.toString()

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /**
     * Parses "url;md5;xxxx" (and ";pk;" suffixes) and resolves relative paths against the
     * config URL. Logic modelled on Kototoro's resolveJarSpec (Apache-2.0).
     */
    fun jarSpec(raw: String, configUrl: String): JarSpec {
        val t = raw.trim()
        val md5Idx = t.indexOf(";md5;", ignoreCase = true)
        val pkIdx = t.indexOf(";pk;", ignoreCase = true)
        val cut = listOf(md5Idx, pkIdx).filter { it >= 0 }.minOrNull() ?: -1
        var urlPart = if (cut >= 0) t.substring(0, cut).trim() else t
        val md5 = if (md5Idx >= 0) t.substring(md5Idx + 5).substringBefore(';').trim().ifBlank { null } else null
        if (urlPart.startsWith("img+", ignoreCase = true)) {
            throw IllegalStateException("spider 使用 img+ 形式（jar 藏在图片里），本 spike 未实现: $urlPart")
        }
        urlPart = when {
            urlPart.startsWith("http://", true) || urlPart.startsWith("https://", true) -> urlPart
            urlPart.startsWith("//") -> "https:$urlPart"
            else -> configUrl.toHttpUrlOrNull()?.resolve(urlPart)?.toString()
                ?: throw IllegalStateException("无法把相对路径 $urlPart 解析到配置地址上")
        }
        return JarSpec(raw, urlPart, md5)
    }

    fun resolveExt(ext: String, configUrl: String): String {
        val v = ext.trim()
        val relative = v.startsWith("./") || v.startsWith("../")
        return if (relative) configUrl.toHttpUrlOrNull()?.resolve(v)?.toString() ?: ext else ext
    }
}
