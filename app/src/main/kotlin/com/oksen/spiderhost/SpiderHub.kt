package com.oksen.spiderhost

import android.content.Context
import com.github.catvod.crawler.Spider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Stage 3: every type-3 site of the config served at once, like TVBox/影视仓 does:
 *   - one "home site" whose categories are the SenPlayer libraries (switchable in the app, like 影视仓's 首页数据源),
 *   - a "全部站点" library that lists every enabled site -> its categories -> its videos,
 *   - global search over every enabled + searchable site in parallel.
 * Sites are instantiated lazily (first use) and each jar is loaded and Init-ed once.
 */
object SpiderHub {

    class SiteRt(val site: TvboxConfig.Site, val loader: ClassLoader, val spider: Spider)

    @Volatile var runner: SpiderRunner? = null
    @Volatile var configUrl: String = ""
    @Volatile var sites: List<TvboxConfig.Site> = emptyList()
    @Volatile var homeKey: String = ""
    @Volatile private var rootSpider: String? = null
    @Volatile private var ctx: Context? = null

    /** Sites shown in 全部站点 and used by search. Empty set = all type-3 csp sites. */
    @Volatile var enabled: Set<String> = emptySet()

    private val loaders = ConcurrentHashMap<String, ClassLoader>()
    private val rts = ConcurrentHashMap<String, SiteRt>()
    private val failed = ConcurrentHashMap<String, String>()
    private val classes = ConcurrentHashMap<String, List<Pair<String, String>>>()
    private val locks = ConcurrentHashMap<String, Any>()

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "spider-hub").apply { isDaemon = true } }

    val ready get() = runner != null && sites.isNotEmpty()

    fun start(r: SpiderRunner, app: SpiderHostApp, url: String, root: JsonObject, all: List<TvboxConfig.Site>, home: String) {
        runner?.takeIf { it !== r }?.shutdownExecutor()
        if (configUrl != url) { loaders.clear(); rts.clear(); failed.clear(); classes.clear() }
        configUrl = url
        rootSpider = root.get("spider")?.takeIf { it.isJsonPrimitive }?.asString
        ctx = app
        sites = all
        homeKey = home
        enabled = app.getSharedPreferences("spike", Context.MODE_PRIVATE).getStringSet("enabledSites", emptySet())!!.toSet()
        runner = r
        val usable = served()
        DiagLog.w("HUB: 配置 ${all.size} 站，可服务 type-3 csp 站 ${usable.size} 个，启用 ${if (enabled.isEmpty()) "全部" else enabled.size.toString()}")
        val other = all.filterNot { isSupported(it) }
        if (other.isNotEmpty()) DiagLog.w("HUB: 暂不支持（非 type-3 csp）${other.size} 站: ${other.take(30).joinToString { "${it.name}(t=${it.type})" }}")
        r.step("hub: home site $home")
        val cls = classesOf(home)
        DiagLog.w("SERVE: Jellyfin 接口已就绪，首页站点=${home} 分类 ${cls.size} 个: ${cls.joinToString { it.second }}")
        DiagLog.w("SERVE: SenPlayer 添加 Jellyfin 服务器 地址 127.0.0.1 端口 ${LocalServer.PORT}，用户名任意，密码留空")
    }

    fun isSupported(s: TvboxConfig.Site) = s.type == 3 && s.api.startsWith("csp_")

    /** Sites in 全部站点, in config order. */
    fun served(): List<TvboxConfig.Site> = sites.filter { isSupported(it) && (enabled.isEmpty() || it.key in enabled || it.key == homeKey) }

    fun site(key: String) = sites.firstOrNull { it.key == key }

    fun setHome(key: String) {
        homeKey = key
        DiagLog.w("HUB: 首页站点切换为 $key（SenPlayer 里刷新媒体库即可看到）")
    }

    fun shortName(key: String): String {
        val n = site(key)?.name ?: key
        // Drop emoji / decoration so SenPlayer titles stay readable: "💥追剧┃秒播💥" -> "追剧┃秒播"
        return n.replace(Regex("[^\\p{L}\\p{N}┃|·\\- ]"), "").trim().ifBlank { key }
    }

    // ---------- lazy site runtime ----------

    fun rt(key: String): SiteRt? {
        rts[key]?.let { return it }
        failed[key]?.let { return null }
        val lock = locks.getOrPut(key) { Any() }
        synchronized(lock) {
            rts[key]?.let { return it }
            val r = runner ?: return null
            val s = site(key) ?: return null
            if (!isSupported(s)) { failed[key] = "不支持 type=${s.type}"; return null }
            val raw = s.jarRaw ?: rootSpider ?: run { failed[key] = "无 jar"; return null }
            val loader = try {
                synchronized(loaders) { loaders.getOrPut(raw) { r.prepareJar(raw).first } }
            } catch (t: Throwable) {
                DiagLog.e("HUB: $key 加载 jar 失败", t); failed[key] = "jar 失败"; return null
            }
            val className = "com.github.catvod.spider." + s.api.removePrefix("csp_")
            val sp = call("new $key", loader, 20) { loader.loadClass(className).getDeclaredConstructor().newInstance() as Spider }
                ?: run { failed[key] = "实例化失败"; return null }
            sp.siteKey = key
            val ext = runCatching { TvboxConfig.resolveExt(s.ext, configUrl) }.getOrDefault(s.ext)
            val c = ctx!!
            call("init $key", loader, 30) { try { sp.init(c, ext) } catch (e: AbstractMethodError) { sp.init(c) } }
            return SiteRt(s, loader, sp).also { rts[key] = it }
        }
    }

    fun <T> on(key: String, label: String, timeoutSec: Long = 30, block: (Spider) -> T): T? {
        val rt = rt(key) ?: return null
        return call("$key $label", rt.loader, timeoutSec) { block(rt.spider) }
    }

    /** Points /proxy at this site's spider and jar (the one whose play URL is about to be used). */
    fun activateForProxy(key: String) {
        val rt = rts[key] ?: return
        SpiderSession.spider = rt.spider
        SpiderSession.siteKey = key
        SpiderSession.classLoader = rt.loader
        SpiderSession.staticProxies[rt.loader]?.let { SpiderSession.staticProxy = it }
    }

    fun classesOf(key: String): List<Pair<String, String>> {
        classes[key]?.let { return it }
        val home = on(key, "homeContent", 30) { it.homeContent(true) }
        val list = runCatching {
            JsonParser.parseString(home).asJsonObject.getAsJsonArray("class").map {
                val o = it.asJsonObject
                o.get("type_id").asString to o.get("type_name").asString
            }
        }.getOrDefault(emptyList())
        if (list.isEmpty()) DiagLog.w("HUB: $key homeContent 无分类: ${home?.take(500)}")
        // Only cache a real answer, so a transient failure is retried on the next browse.
        if (list.isNotEmpty()) classes[key] = list
        return list
    }

    /** Runs [block] for every key in parallel, returns what finished within [timeoutSec]. */
    fun <T> fanOut(keys: List<String>, timeoutSec: Long, block: (String) -> T?): List<Pair<String, T>> {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        val futures: List<Pair<String, Future<T?>>> = keys.map { k -> k to pool.submit<T?> { block(k) } }
        val out = mutableListOf<Pair<String, T>>()
        futures.forEach { (k, f) ->
            val left = deadline - System.currentTimeMillis()
            val v = try { if (left > 0) f.get(left, TimeUnit.MILLISECONDS) else { f.cancel(true); null } }
            catch (e: TimeoutException) { f.cancel(true); null }
            catch (e: Exception) { null }
            if (v != null) out += k to v
        }
        return out
    }

    fun <T> call(label: String, loader: ClassLoader, timeoutSec: Long, block: () -> T): T? {
        val started = System.currentTimeMillis()
        val f = pool.submit<T> {
            val t = Thread.currentThread()
            val prev = t.contextClassLoader
            t.contextClassLoader = loader
            try { block() } finally { t.contextClassLoader = prev }
        }
        return try {
            f.get(timeoutSec, TimeUnit.SECONDS).also { DiagLog.w("[$label] ok in ${System.currentTimeMillis() - started} ms") }
        } catch (e: TimeoutException) {
            f.cancel(true); DiagLog.w("[$label] TIMEOUT after ${timeoutSec}s"); null
        } catch (e: ExecutionException) {
            val c = SpiderRunner.unwrap(e); DiagLog.e("[$label] FAILED: ${SpiderRunner.classify(c)}", c); null
        } catch (e: InterruptedException) {
            f.cancel(true); null
        }
    }

    /**
     * Diagnostic for netdisk login: dump what a config-centre site (e.g. key=Wexconfig) returns from
     * homeContent / categoryContent / detailContent / playerContent, so the login mechanism (QR image,
     * cookie form, local web page) is read from real output instead of guessed.
     */
    fun probeConfigCentre(key: String) {
        DiagLog.w("PROBE 配置中心 $key")
        val home = on(key, "homeContent") { it.homeContent(true) }
        DiagLog.w("PROBE home=${home?.take(3000)}")
        val cls = classesOf(key)
        cls.take(6).forEach { (tid, name) ->
            val cat = on(key, "category $name") { it.categoryContent(tid, "1", false, HashMap()) }
            DiagLog.w("PROBE category $tid/$name=${cat?.take(3000)}")
            val first = runCatching {
                JsonParser.parseString(cat).asJsonObject.getAsJsonArray("list").take(2).map { it.asJsonObject.get("vod_id").asString }
            }.getOrDefault(emptyList())
            first.forEach { vid ->
                val d = on(key, "detail $vid") { it.detailContent(listOf(vid)) }
                DiagLog.w("PROBE detail $vid=${d?.take(3000)}")
            }
        }
        DiagLog.w("PROBE 结束")
    }
}
