package com.oksen.spiderhost

import android.content.Context
import android.os.Build
import com.github.catvod.Init
import com.github.catvod.Proxy
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The spider currently loaded, shared with LocalServer for /proxy. */
object SpiderSession {
    @Volatile var classLoader: ClassLoader? = null
    @Volatile var spider: Spider? = null
    @Volatile var staticProxy: Method? = null
    @Volatile var siteKey: String? = null
}

class SpiderRunner(private val app: SpiderHostApp, private val opts: Options) {

    data class Options(
        val configUrl: String,
        val siteKey: String,
        val keyword: String,
        val timeoutSec: Long = 30,
        val fakeTvboxPackage: Boolean = false,
        val preloadSo: Boolean = false,
        val forceStandardInit: Boolean = false,
        val maxFlags: Int = 3,
        val batch: Boolean = false,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // One dedicated thread per run: if a spider call hangs we abandon it and the next run gets a fresh one.
    private var executor: ExecutorService = newExecutor()

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spider-call").apply { isDaemon = true }
    }

    private val marker = SpiderHostApp.runMarker(app)

    fun run() {
        DiagLog.w("========== RUN start: site=${opts.siteKey} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()} device=${Build.MANUFACTURER} ${Build.MODEL}")
        try {
            doRun()
            DiagLog.w("========== RUN finished")
        } catch (t: Throwable) {
            DiagLog.e("========== RUN FAILED: ${classify(t)}", t)
        } finally {
            marker.delete()
            executor.shutdown()
        }
    }

    fun step(name: String) {
        DiagLog.w(">>> $name")
        runCatching { marker.writeText("$name @ ${java.util.Date()}") }
    }

    private fun doRun() {
        step("1 fetch config")
        val body = get(opts.configUrl).toString(Charsets.UTF_8)
        DiagLog.w("config bytes=${body.length}")
        val root = TvboxConfig.parse(TvboxConfig.decodeBody(body))
        val sites = TvboxConfig.sites(root)
        DiagLog.w("sites=${sites.size}; root.spider=${root.get("spider")?.toString()?.take(200)}")
        val site = sites.firstOrNull { it.key == opts.siteKey }
            ?: error("配置里找不到 key=${opts.siteKey}。前 20 个 key: ${sites.take(20).joinToString { it.key }}")
        DiagLog.w("site: key=${site.key} name=${site.name} type=${site.type} api=${site.api} jar=${site.jarRaw} ext=${site.ext.take(300)}")
        require(site.type == 3) { "site.type=${site.type}，本 spike 只测 type 3" }
        require(site.api.startsWith("csp_")) { "api=${site.api} 不是 csp_ 形式" }

        step("2 resolve spider url")
        val rawJar = site.jarRaw ?: root.get("spider")?.asString ?: error("site 和根都没有 spider/jar 字段")
        val spec = TvboxConfig.jarSpec(rawJar, opts.configUrl)
        DiagLog.w("jar url=${spec.url} md5=${spec.md5}")

        step("3 download jar")
        val jarFile = downloadJar(spec)

        step("4 inspect jar")
        val dexes = DexInspector.readDexes(jarFile)
        DiagLog.w("top-level dex: ${dexes.joinToString { "${it.first}(${it.second.size} B)" }}")
        val external = DexInspector.externalReferences(dexes.map { it.second })
        val missing = external.filter { name ->
            runCatching { Class.forName(name, false, app.classLoader) }.isFailure
        }
        DiagLog.w("jar 引用但不自带的非系统类: ${external.size} 个；宿主缺失: ${missing.size} 个")
        if (missing.isNotEmpty()) DiagLog.w("宿主缺失的类（可能导致 NoClassDefFoundError）:\n  " + missing.joinToString("\n  "))
        val packageForCheck = if (opts.fakeTvboxPackage) FAKE_PACKAGE else app.packageName
        val isProtected = DexInspector.isProtectedInit(dexes.map { it.second }, packageForCheck)
        DiagLog.w("protected Init (会在包名不在白名单时自杀进程) = $isProtected (检查包名=$packageForCheck)")

        step("5 guard native .so")
        val native = GuardNative.prepare(jarFile, app.filesDir)
        DiagLog.w("jar 内 .so: ${native.allSo.ifEmpty { listOf("无") }.joinToString()}")
        DiagLog.w("按 ABI ${native.abi} 解出: ${native.extracted.joinToString { it.name }.ifBlank { "无" }} -> ${native.libDir}")
        if (opts.preloadSo) GuardNative.preload(native).forEach { DiagLog.w("preload: $it") }

        step("6 DexClassLoader")
        enableHiddenApi()
        val ctx: Context = if (opts.fakeTvboxPackage) HostContext(app, FAKE_PACKAGE) else app
        Init.set(ctx)
        Proxy.set(LocalServer.PORT)
        val odex = File(app.codeCacheDir, "spider-odex").apply { mkdirs() }
        val loader = DexClassLoader(jarFile.absolutePath, odex.absolutePath, native.libDir.absolutePath, app.classLoader)
        // If the jar ships its own com.github.catvod.Proxy, parent-first means ours wins; set both anyway.
        runCatching { loader.loadClass("com.github.catvod.Proxy").getMethod("set", Int::class.javaPrimitiveType).invoke(null, LocalServer.PORT) }
        val staticProxy = runCatching {
            loader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java)
        }.getOrNull()
        DiagLog.w("spider.Proxy.proxy(Map) present=${staticProxy != null}")
        SpiderSession.classLoader = loader
        SpiderSession.staticProxy = staticProxy

        step("7 Init")
        call("Init", loader) { initJar(loader, ctx, isProtected) }

        if (opts.batch) { runBatch(sites, site, rawJar, loader, ctx); return }

        step("8 instantiate ${site.api}")
        val className = "com.github.catvod.spider." + site.api.removePrefix("csp_")
        val spider = call("newInstance $className", loader) {
            loader.loadClass(className).getDeclaredConstructor().newInstance() as Spider
        } ?: error("实例化 $className 失败，见上方堆栈")
        spider.siteKey = site.key
        SpiderSession.spider = spider
        SpiderSession.siteKey = site.key

        step("9 spider.init")
        val ext = TvboxConfig.resolveExt(site.ext, opts.configUrl)
        call("init(ctx, ext)", loader) {
            try {
                spider.init(ctx, ext)
            } catch (e: AbstractMethodError) {
                spider.init(ctx)
            }
        }

        step("10 homeContent(true)")
        val home = call("homeContent(true)", loader) { spider.homeContent(true) }
        DiagLog.w("homeContent length=${home?.length}\n${home?.take(4000)}")

        step("11 searchContent(${opts.keyword}, false)")
        val search = call("searchContent", loader) { spider.searchContent(opts.keyword, false) }
        DiagLog.w("searchContent length=${search?.length}\n${search?.take(4000)}")

        PlayProbe(spider, loader, opts.maxFlags, { step(it) }, { l, c, b -> call(l, c, b) }).run(search, home)
    }

    /** Stage 1b: every type-3 site that uses the same jar -> init, search, detail, player, probe. One summary line per site. */
    private fun runBatch(sites: List<TvboxConfig.Site>, first: TvboxConfig.Site, rawJar: String, loader: ClassLoader, ctx: Context) {
        val sameJar = sites.filter { it.type == 3 && it.api.startsWith("csp_") && (it.jarRaw ?: rawJar) == rawJar }
        DiagLog.w("BATCH: 同一 jar 的 type-3 站点 ${sameJar.size} 个（总 ${sites.size}）")
        sites.filter { it.name.contains("配置") || it.name.contains("设置") || it.key.contains("config", true) }
            .forEach { DiagLog.w("可能的配置中心: key=${it.key} name=${it.name} api=${it.api} ext=${it.ext.take(200)}") }
        val lines = mutableListOf<String>()
        sameJar.forEachIndexed { idx, s ->
            step("B$idx ${s.key}")
            val className = "com.github.catvod.spider." + s.api.removePrefix("csp_")
            val sp = call("new ${s.key}", loader) { loader.loadClass(className).getDeclaredConstructor().newInstance() as Spider }
            if (sp == null) { lines += "${s.key} | ${s.name} | 实例化失败"; return@forEachIndexed }
            sp.siteKey = s.key
            SpiderSession.spider = sp; SpiderSession.siteKey = s.key
            val ext = runCatching { TvboxConfig.resolveExt(s.ext, opts.configUrl) }.getOrDefault(s.ext)
            call("init ${s.key}", loader) { try { sp.init(ctx, ext) } catch (e: AbstractMethodError) { sp.init(ctx) } }
            val search = call("search ${s.key}", loader) { sp.searchContent(opts.keyword, false) }
            val home = if (firstId(search) == null) call("home ${s.key}", loader) { sp.homeContent(true) } else null
            val verdict = PlayProbe(sp, loader, opts.maxFlags, { step(it) }, { l, c, b -> call(l, c, b) }).run(search, home)
            lines += "${s.key} | ${s.name} | search=${if (firstId(search) != null) "有" else "无"} | $verdict"
        }
        DiagLog.w("BATCH 结论（key | name | 搜索 | 各线路）:\n" + lines.joinToString("\n"))
    }

    private fun firstId(json: String?): String? = runCatching {
        com.google.gson.JsonParser.parseString(json).asJsonObject.getAsJsonArray("list")[0].asJsonObject.get("vod_id").asString
    }.getOrNull()

    /** Init strategy adapted from Kototoro TVBoxJarSpiderRuntime.initializeJar (Apache-2.0). */
    private fun initJar(loader: ClassLoader, ctx: Context, isProtected: Boolean) {
        val initClass = runCatching { loader.loadClass("com.github.catvod.spider.Init") }.getOrElse {
            DiagLog.w("jar 没有 com.github.catvod.spider.Init，跳过")
            return
        }
        if (isProtected && !opts.forceStandardInit) {
            DiagLog.w("使用 protected Init 策略：Init.get() + 注入 Context + exeLibStub()，不调用 Init.init")
            val instance = runCatching { initClass.getMethod("get").invoke(null) }
                .onFailure { DiagLog.e("Init.get() 失败", it) }.getOrNull()
            if (instance != null) {
                runCatching { seedContext(instance, ctx) }.onFailure { DiagLog.e("注入 Context 失败", it) }
                val stub = instance.javaClass.methods.firstOrNull { it.name == "exeLibStub" && it.parameterTypes.isEmpty() }
                if (stub == null) DiagLog.w("没有 exeLibStub()") else {
                    runCatching { stub.invoke(instance) }
                        .onSuccess { DiagLog.w("exeLibStub() 返回正常") }
                        .onFailure { DiagLog.e("exeLibStub() 失败: ${classify(it)}", it) }
                }
            }
            runCatching { initClass.getMethod("replaceCloudDiskNames").invoke(null) }
                .onSuccess { DiagLog.w("replaceCloudDiskNames() ok") }
            runCatching { initClass.getMethod("startGoProxy", Context::class.java).invoke(null, ctx) }
                .onSuccess { DiagLog.w("startGoProxy(ctx) ok") }
                .onFailure { if (it !is NoSuchMethodException) DiagLog.e("startGoProxy 失败", it) }
        } else {
            DiagLog.w("使用标准 Init 策略：Init.init(Context)")
            initClass.getMethod("init", Context::class.java).invoke(null, ctx)
        }
    }

    /** From Kototoro TVBoxProtectedInitCompat.seedContext (Apache-2.0): first non-static Context field, prefer "c". */
    private fun seedContext(instance: Any, ctx: Context) {
        val fields = generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { Context::class.java.isAssignableFrom(it.type) && it.type.isAssignableFrom(ctx.javaClass) }
            .sortedByDescending { it.name == "c" }
            .toList()
        val field = fields.firstOrNull() ?: throw NoSuchFieldException("Init 里没有可写入 Context 的字段")
        field.isAccessible = true
        field.set(instance, ctx)
        DiagLog.w("Context 写入字段 ${instance.javaClass.name}.${field.name}")
    }

    fun <T> call(label: String, loader: ClassLoader, block: () -> T): T? {
        val started = System.currentTimeMillis()
        val future = executor.submit<T> {
            val thread = Thread.currentThread()
            val prev = thread.contextClassLoader
            thread.contextClassLoader = loader
            try { block() } finally { thread.contextClassLoader = prev }
        }
        return try {
            future.get(opts.timeoutSec, TimeUnit.SECONDS).also {
                DiagLog.w("[$label] ok in ${System.currentTimeMillis() - started} ms")
            }
        } catch (e: TimeoutException) {
            future.cancel(true)
            // A hung spider thread would block every later call; abandon it and start a fresh one.
            executor.shutdownNow(); executor = newExecutor()
            DiagLog.w("[$label] TIMEOUT after ${opts.timeoutSec}s")
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = unwrap(e)
            DiagLog.e("[$label] FAILED: ${classify(cause)}", cause)
            null
        }
    }

    private fun downloadJar(spec: TvboxConfig.JarSpec): File {
        val dir = File(app.cacheDir, "jar").apply { mkdirs() }
        val file = File(dir, md5Hex(spec.url.toByteArray()) + ".jar")
        if (file.exists() && spec.md5 != null && md5Hex(file.readBytes()).equals(spec.md5, true)) {
            DiagLog.w("缓存 jar md5 匹配，复用 ${file.name}")
            return file
        }
        val bytes = get(spec.url)
        if (file.exists()) file.setWritable(true)
        file.writeBytes(bytes)
        val actual = md5Hex(bytes)
        DiagLog.w("jar 下载完成 ${bytes.size} B, md5=$actual")
        if (spec.md5 != null) {
            if (actual.equals(spec.md5, true)) DiagLog.w("md5 校验通过")
            else DiagLog.w("WARNING md5 不匹配: 期望 ${spec.md5}，实际 $actual（与 FongMi/Kototoro 一致，仍继续尝试加载）")
        }
        // Android 14 (targetSdk 34) refuses to DexClassLoader a writable file.
        file.setReadable(true, true)
        if (!file.setWritable(false, false)) file.setReadOnly()
        return file
    }

    private fun get(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", "okhttp/3.15").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body?.bytes() ?: error("空响应 $url")
        }
    }

    private fun enableHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;", "Landroid/app/ActivityThread\$ActivityClientRecord;") }
            .onSuccess { DiagLog.w("hidden-api exemptions (ActivityThread) = $it") }
            .onFailure { DiagLog.e("hidden-api exemption failed", it) }
    }

    companion object {
        const val FAKE_PACKAGE = "com.github.tvbox.osc"

        fun md5Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        fun unwrap(t: Throwable): Throwable {
            var cur = t
            repeat(8) {
                val next = when (cur) {
                    is java.util.concurrent.ExecutionException -> cur.cause
                    is InvocationTargetException -> (cur as InvocationTargetException).targetException
                    else -> null
                } ?: return cur
                cur = next
            }
            return cur
        }

        /** One-line category for the failure kinds this spike is looking for. */
        fun classify(t: Throwable): String {
            val root = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.toList()
            val kind = when {
                root.any { it is NoClassDefFoundError || it is ClassNotFoundException } -> "缺类 (NoClassDefFound/ClassNotFound)"
                root.any { it is UnsatisfiedLinkError } -> "native 库问题 (UnsatisfiedLinkError)"
                root.any { it is NoSuchMethodError || it is AbstractMethodError } -> "宿主 API 签名不匹配"
                root.any { it is SecurityException } -> "SecurityException"
                else -> t.javaClass.simpleName
            }
            return "$kind: ${t.javaClass.name}: ${t.message}"
        }
    }
}
