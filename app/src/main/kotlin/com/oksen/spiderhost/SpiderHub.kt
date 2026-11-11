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
        DiagLog.w("HUB: 配置 ${all.size} 站，可服务 type-3 csp 站 ${usable.size} 个，启用 ${enabled.size}（另加首页站点）")
        val other = all.filterNot { isSupported(it) }
        if (other.isNotEmpty()) DiagLog.w("HUB: 暂不支持（非 type-3 csp）${other.size} 站: ${other.take(30).joinToString { "${it.name}(t=${it.type})" }}")
        r.step("hub: home site $home")
        val cls = classesOf(home)
        DiagLog.w("SERVE: Jellyfin 接口已就绪，首页站点=${home} 分类 ${cls.size} 个: ${cls.joinToString { it.second }}")
        DiagLog.w("SERVE: SenPlayer 添加 Jellyfin 服务器 地址 127.0.0.1 端口 ${LocalServer.PORT}，用户名任意，密码留空")
    }

    fun isSupported(s: TvboxConfig.Site) = s.type == 3 && s.api.startsWith("csp_")

    /** Sites in 全部站点, in config order. */
    /** Sites in 全部站点 and search, in config order: the ones the user ticked, plus the home site. Nothing is ticked by default. */
    fun served(): List<TvboxConfig.Site> = sites.filter { isSupported(it) && (it.key in enabled || it.key == homeKey) }

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

    // ---------- per-site load control ----------
    // A site gets at most SITE_SLOTS spider calls at once. A slot is released when the call really ends,
    // not when we stop waiting for it, so calls that hang past their timeout still count against the site.
    private const val SITE_SLOTS = 4
    private val slots = ConcurrentHashMap<String, java.util.concurrent.Semaphore>()
    private val foreground = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /** Number of user-facing (non-backfill) spider calls running for [key] right now. */
    fun foregroundBusy(key: String): Int = foreground[key]?.get() ?: 0

    fun <T> on(key: String, label: String, timeoutSec: Long = 30, background: Boolean = false, block: (Spider) -> T): T? {
        val rt = rt(key) ?: return null
        val sem = slots.getOrPut(key) { java.util.concurrent.Semaphore(SITE_SLOTS, true) }
        val fg = foreground.getOrPut(key) { java.util.concurrent.atomic.AtomicInteger() }
        val waitStart = System.currentTimeMillis()
        if (!sem.tryAcquire(timeoutSec, TimeUnit.SECONDS)) {
            DiagLog.w("[$key $label] BUSY: 站点 $SITE_SLOTS 个调用都未结束，等待 ${timeoutSec}s 后放弃")
            return null
        }
        val waited = System.currentTimeMillis() - waitStart
        if (waited > 1000) DiagLog.w("[$key $label] 排队 $waited ms")
        val left = (timeoutSec * 1000 - waited).coerceAtLeast(1000)
        if (!background) fg.incrementAndGet()
        return call("$key $label", rt.loader, left, TimeUnit.MILLISECONDS, onEnd = {
            sem.release()
            if (!background) fg.decrementAndGet()
        }) { block(rt.spider) }
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
        val home = on(key, "homeContent", 15) { it.homeContent(true) }
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

    fun <T> call(label: String, loader: ClassLoader, timeoutSec: Long, block: () -> T): T? =
        call(label, loader, timeoutSec, TimeUnit.SECONDS, null, block)

    fun <T> call(label: String, loader: ClassLoader, timeout: Long, unit: TimeUnit, onEnd: (() -> Unit)?, block: () -> T): T? {
        val started = System.currentTimeMillis()
        val f = try {
            pool.submit<T> {
                val t = Thread.currentThread()
                val prev = t.contextClassLoader
                t.contextClassLoader = loader
                try { block() } finally { t.contextClassLoader = prev; onEnd?.invoke() }
            }
        } catch (t: Throwable) { onEnd?.invoke(); throw t }
        return try {
            f.get(timeout, unit).also {
                val ms = System.currentTimeMillis() - started
                DiagLog.w("[$label] ok in $ms ms" + if (ms > 5000) " SLOW" else "")
            }
        } catch (e: TimeoutException) {
            // cancel(true) only interrupts; the spider thread may keep running until it returns (onEnd fires then).
            f.cancel(true); DiagLog.w("[$label] TIMEOUT after ${unit.toMillis(timeout) / 1000}s"); null
        } catch (e: ExecutionException) {
            val c = SpiderRunner.unwrap(e); DiagLog.e("[$label] FAILED: ${SpiderRunner.classify(c)}", c); null
        } catch (e: InterruptedException) {
            f.cancel(true); null
        }
    }

    private val PAN = Regex("盘|夸克|迅雷|百度|天翼|光鸭|阿里|移动云|115|123|UC|(?i:quark|thunder|baidu|aliyun|alipan|pikpak|pan|cloud189)")

    /** Netdisk sites by name/key/api (a guess the user confirms in the picker); the config centre itself is excluded. */
    fun isPan(s: TvboxConfig.Site): Boolean =
        isSupported(s) && !isConfigCentre(s) && (PAN.containsMatchIn(s.name) || PAN.containsMatchIn(s.key) || PAN.containsMatchIn(s.api))

    fun isConfigCentre(s: TvboxConfig.Site) = s.name.contains("配置") || s.key.contains("config", true)

    /**
     * Starts the config-centre spider (which serves its own login page) and returns the page URL.
     * The URL is read from the spider's own detail text ("请打开网页版配置中心：http://x:19988/config");
     * 127.0.0.1 with the same port is tried first since the page runs on this phone.
     */
    fun loginPageUrl(): String? {
        val cc = sites.firstOrNull { isSupported(it) && isConfigCentre(it) } ?: run { DiagLog.w("LOGIN: 配置里没有配置中心站点"); return null }
        val cls = classesOf(cc.key)
        val re = Regex("https?://[0-9A-Za-z.\\-]+:\\d+/[^\\s\"'<，。]*")
        var found: String? = null
        for ((tid, _) in cls.filter { it.first != "diy" }.take(4)) {
            val cat = on(cc.key, "login category $tid") { it.categoryContent(tid, "1", false, HashMap()) } ?: continue
            found = re.find(cat)?.value
            if (found != null) break
            val vid = runCatching { JsonParser.parseString(cat).asJsonObject.getAsJsonArray("list")[0].asJsonObject.get("vod_id").asString }.getOrNull() ?: continue
            val d = on(cc.key, "login detail $vid") { it.detailContent(listOf(vid)) } ?: continue
            found = re.find(d)?.value
            if (found != null) break
        }
        DiagLog.w("LOGIN: 配置中心 ${cc.key} 给出的地址 $found")
        // The page may be served through our own /proxy (do=pan&action=config): route it to this jar.
        activateForProxy(cc.key)
        val uri = found?.let { runCatching { java.net.URI(it) }.getOrNull() }
        val port = uri?.port?.takeIf { it > 0 } ?: 19988
        val path = (uri?.rawPath?.ifBlank { null } ?: "/config") + (uri?.rawQuery?.let { "?$it" } ?: "")
        val local = "http://127.0.0.1:$port$path"
        if (port == LocalServer.PORT) { DiagLog.w("LOGIN: 使用本机 $local"); return local }
        val ok = runCatching {
            okhttp3.OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
                .newCall(okhttp3.Request.Builder().url(local).build()).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
        DiagLog.w("LOGIN: $local ${if (ok) "可访问" else "不可访问"}")
        return if (ok || found == null) local else found
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
