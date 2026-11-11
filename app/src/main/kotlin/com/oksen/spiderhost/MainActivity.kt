package com.oksen.spiderhost

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Plain-View UI on purpose: no AppCompat/Compose, fewer host deps that could clash with jar classes. */
class MainActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("spike", MODE_PRIVATE) }
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val configUrl = field("配置 URL（TVBox JSON）", prefs.getString("configUrl", "")!!, InputType.TYPE_TEXT_VARIATION_URI)
        val siteKey = field("站点 key", prefs.getString("siteKey", "")!!)
        val keyword = field("搜索词", prefs.getString("keyword", "繁花")!!)
        val timeout = field("单次调用超时（秒）", prefs.getString("timeout", "30")!!, InputType.TYPE_CLASS_NUMBER)
        val fakePkg = CheckBox(this).apply { text = "伪装包名为 com.github.tvbox.osc（实验）"; isChecked = prefs.getBoolean("fakePkg", false) }
        val preload = CheckBox(this).apply { text = "预先 System.load 解出的 .so（实验）"; isChecked = prefs.getBoolean("preload", false) }
        val batch = CheckBox(this).apply { text = "批量：测同一 jar 的全部站点（忽略站点 key 以外的单站流程）"; isChecked = prefs.getBoolean("batch", false) }
        val forceStd = CheckBox(this).apply { text = "强制标准 Init.init（可能被杀进程）"; isChecked = prefs.getBoolean("forceStd", false) }

        val pick = Button(this).apply { text = "选择首页站点（服务中可直接切换）" }
        val pickEnabled = Button(this).apply { text = "选择启用站点（搜索 / 全部站点）" }
        val probe = Button(this).apply { text = "探查配置中心（网盘登录）" }

        fun loadSites(btn: Button, onOk: (List<TvboxConfig.Site>) -> Unit) {
            val url = configUrl.text.toString().trim()
            if (url.isBlank()) return
            if (SpiderHub.ready && SpiderHub.configUrl == url) { onOk(SpiderHub.sites); return }
            btn.isEnabled = false
            Thread {
                val result = runCatching {
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "okhttp/3.15").build()
                    val body = okhttp3.OkHttpClient().newCall(req).execute().use { it.body!!.string() }
                    TvboxConfig.sites(TvboxConfig.parse(TvboxConfig.decodeBody(body)))
                }
                main.post {
                    btn.isEnabled = true
                    result.onFailure { append("\n加载站点失败: ${it.message}\n") }
                    result.onSuccess(onOk)
                }
            }.start()
        }

        pick.setOnClickListener {
            loadSites(pick) { sites ->
                val labels = sites.map { "${it.name}  [${it.key}]  t=${it.type} ${it.api}" }.toTypedArray()
                android.app.AlertDialog.Builder(this).setTitle("${sites.size} 个站点")
                    .setItems(labels) { _, i ->
                        siteKey.setText(sites[i].key)
                        prefs.edit().putString("siteKey", sites[i].key).apply()
                        if (SpiderHub.ready) SpiderHub.setHome(sites[i].key)
                    }
                    .show()
            }
        }
        pickEnabled.setOnClickListener {
            loadSites(pickEnabled) { all ->
                val sites = all.filter { SpiderHub.isSupported(it) }
                val saved = prefs.getStringSet("enabledSites", emptySet())!!
                val checked = BooleanArray(sites.size) { saved.isEmpty() || sites[it].key in saved }
                android.app.AlertDialog.Builder(this).setTitle("启用的站点（${sites.size} 个 type-3）")
                    .setMultiChoiceItems(sites.map { "${it.name}  [${it.key}]" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                    .setPositiveButton("保存") { _, _ ->
                        val set = sites.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
                        val store = if (set.size == sites.size) emptySet() else set
                        prefs.edit().putStringSet("enabledSites", store).apply()
                        SpiderHub.enabled = store
                        append("\n启用站点: ${if (store.isEmpty()) "全部 ${sites.size}" else set.size.toString()} 个\n")
                    }
                    .setNeutralButton("全选") { _, _ ->
                        prefs.edit().putStringSet("enabledSites", emptySet()).apply()
                        SpiderHub.enabled = emptySet()
                        append("\n启用站点: 全部 ${sites.size} 个\n")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        probe.setOnClickListener {
            if (!SpiderHub.ready) { append("\n先启动 Jellyfin 服务，再探查配置中心\n"); return@setOnClickListener }
            loadSites(probe) { sites ->
                val cands = sites.filter { SpiderHub.isSupported(it) && (it.name.contains("配置") || it.key.contains("config", true) || it.name.contains("盘", true)) }
                    .ifEmpty { sites.filter { SpiderHub.isSupported(it) } }
                android.app.AlertDialog.Builder(this).setTitle("选择要探查的站点")
                    .setItems(cands.map { "${it.name}  [${it.key}]" }.toTypedArray()) { _, i ->
                        Thread({ SpiderHub.probeConfigCentre(cands[i].key) }, "probe").start()
                    }
                    .show()
            }
        }
        val run = Button(this).apply { text = "Run" }
        val serve = Button(this).apply { text = "启动 Jellyfin 服务（全部站点，首页=当前站点）" }
        val share = Button(this).apply { text = "分享日志" }
        val clear = Button(this).apply { text = "清空日志" }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(run, share, clear).forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(output) }

        // Controls scroll on their own so the log keeps half the screen on a phone.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(configUrl, pick, siteKey, pickEnabled, serve, probe, buttons, keyword, timeout, fakePkg, preload, forceStd, batch)
            .forEach { controls.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        root.addView(ScrollView(this).apply { addView(controls) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        output.text = DiagLog.readAll().takeLast(60_000)
        SpiderHostApp.instance.previousRunReport?.let { append("\n!!! $it\n") }
        DiagLog.listener = { line -> main.post { append(line) } }

        fun start(serveMode: Boolean) {
            if (running) return
            prefs.edit()
                .putString("configUrl", configUrl.text.toString().trim())
                .putString("siteKey", siteKey.text.toString().trim())
                .putString("keyword", keyword.text.toString())
                .putString("timeout", timeout.text.toString())
                .putBoolean("fakePkg", fakePkg.isChecked)
                .putBoolean("preload", preload.isChecked)
                .putBoolean("forceStd", forceStd.isChecked)
                .putBoolean("batch", batch.isChecked)
                .apply()
            val opts = SpiderRunner.Options(
                configUrl = configUrl.text.toString().trim(),
                siteKey = siteKey.text.toString().trim(),
                keyword = keyword.text.toString().ifBlank { "繁花" },
                timeoutSec = timeout.text.toString().toLongOrNull()?.coerceIn(5, 300) ?: 30,
                fakeTvboxPackage = fakePkg.isChecked,
                preloadSo = preload.isChecked,
                forceStandardInit = forceStd.isChecked,
                batch = batch.isChecked && !serveMode,
                serve = serveMode,
            )
            if (serveMode) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                runCatching { ServeService.start(this) }.onFailure { DiagLog.e("启动前台服务失败", it) }
            }
            running = true
            run.isEnabled = false
            serve.isEnabled = false
            Thread({
                try {
                    SpiderRunner(SpiderHostApp.instance, opts).run()
                } finally {
                    running = false
                    main.post { run.isEnabled = true; serve.isEnabled = true }
                }
            }, if (serveMode) "spider-serve" else "spider-run").start()
        }
        run.setOnClickListener { start(false) }
        serve.setOnClickListener { start(true) }
        share.setOnClickListener {
            val text = DiagLog.readAll().takeLast(200_000)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "分享 SpiderHost 日志"))
        }
        clear.setOnClickListener {
            DiagLog.clear()
            output.text = ""
        }
    }

    override fun onDestroy() {
        DiagLog.listener = null
        super.onDestroy()
    }

    private fun append(s: String) {
        output.append(s)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = if (type == InputType.TYPE_CLASS_NUMBER) type else InputType.TYPE_CLASS_TEXT or type
            isSingleLine = true
        }
}
