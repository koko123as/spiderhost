package com.oksen.spiderhost

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Plain-View UI on purpose: no AppCompat/Compose, fewer host deps that could clash with jar classes.
 * Four pages behind a bottom bar: 仪表 (service + config), 站点 (enabled sites), 日志, 设置.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("spike", MODE_PRIVATE) }
    @Volatile private var running = false

    private lateinit var pages: List<View>
    private lateinit var navItems: List<Pair<LinearLayout, Pair<ImageView, TextView>>>
    private var current = 0

    // 仪表
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView
    private lateinit var statusDetail: TextView
    private lateinit var configUrlView: TextView
    private lateinit var configMeta: TextView
    private lateinit var homeSiteView: TextView
    private lateinit var fab: FrameLayout
    private lateinit var fabIcon: ImageView

    // 站点
    private lateinit var siteList: LinearLayout
    private lateinit var siteCount: TextView
    private lateinit var siteSearch: EditText
    private var loadedSites: List<TvboxConfig.Site> = emptyList()

    // 日志
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private var logFilter = 0
    private var logDirty = true
    private lateinit var filterChips: List<TextView>

    private val ticker = object : Runnable {
        override fun run() { refreshStatus(); main.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(c(R.color.bg)) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = FrameLayout(this)
        pages = listOf(dashboardPage(), sitesPage(), logPage(), settingsPage())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        column.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        column.addView(navBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        SpiderHostApp.instance.previousRunReport?.let { DiagLog.w("!!! $it") }
        DiagLog.listener = { line -> main.post { onLogLine(line) } }
        show(0)
    }

    override fun onResume() { super.onResume(); main.post(ticker) }
    override fun onPause() { main.removeCallbacks(ticker); super.onPause() }

    override fun onDestroy() {
        DiagLog.listener = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current != 0) show(0) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------------------------------------------------------------- pages

    private fun dashboardPage(): View {
        val frame = FrameLayout(this)
        val col = pageColumn("仪表")

        // Service status
        val status = card()
        val head = row()
        statusDot = View(this).apply { background = round(c(R.color.on_bg_dim), dp(5)) }
        head.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        head.addView(label("本机服务", 18f, bold = true))
        status.addView(head)
        statusLine = label("未启动", 15f).apply { setPadding(0, dp(12), 0, 0) }
        statusDetail = label("", 13f, dim = true).apply { setPadding(0, dp(4), 0, 0) }
        status.addView(statusLine); status.addView(statusDetail)
        col.addView(status, cardLp())

        SpiderHostApp.instance.previousRunReport?.let { report ->
            val warn = card()
            warn.addView(label("上次运行没有正常结束", 15f, bold = true).apply { setTextColor(c(R.color.warn)) })
            warn.addView(label(report.lineSequence().firstOrNull().orEmpty(), 13f, dim = true).apply { setPadding(0, dp(6), 0, 0) })
            warn.setOnClickListener { show(2) }
            col.addView(warn, cardLp())
        }

        // Config
        val cfg = card()
        val cfgHead = row()
        cfgHead.addView(icon(R.drawable.ic_doc, c(R.color.primary)), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        cfgHead.addView(label("配置", 18f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        cfg.addView(cfgHead)
        configUrlView = label("", 15f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            background = round(c(R.color.card_inner), dp(20))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { editConfig() }
        }
        cfg.addView(configUrlView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        configMeta = label("", 13f, dim = true).apply { setPadding(dp(4), dp(10), 0, 0) }
        cfg.addView(configMeta)
        val actions = row().apply { setPadding(0, dp(14), 0, 0) }
        actions.addView(iconButton(R.drawable.ic_edit, "编辑") { editConfig() })
        actions.addView(iconButton(R.drawable.ic_refresh, "重新加载") {
            loadSites(force = true) { toast("已加载 ${it.size} 个站点"); refreshConfig(); renderSites() }
        }, gapLp())
        actions.addView(iconButton(R.drawable.ic_share, "分享地址") {
            val url = prefs.getString("configUrl", "").orEmpty()
            if (url.isNotBlank()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
            }, "分享配置地址"))
        }, gapLp())
        cfg.addView(actions)
        col.addView(cfg, cardLp())

        // Home site
        val home = card()
        val homeHead = row()
        homeHead.addView(icon(R.drawable.ic_home, c(R.color.primary)), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        homeHead.addView(label("首页站点", 18f, bold = true))
        home.addView(homeHead)
        homeSiteView = label("", 15f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            background = round(c(R.color.card_inner), dp(20))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { pickHome() }
        }
        home.addView(homeSiteView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        home.addView(label("SenPlayer 的媒体库来自首页站点的分类；服务中切换立即生效。", 13f, dim = true).apply { setPadding(dp(4), dp(10), 0, 0) })
        col.addView(home, cardLp())
        col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(96)))

        frame.addView(scroll(col), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        fab = FrameLayout(this).apply {
            background = round(c(R.color.primary_container), dp(20))
            elevation = dp(3).toFloat()
            setOnClickListener { onFab() }
        }
        fabIcon = icon(R.drawable.ic_play, c(R.color.on_primary_container))
        fab.addView(fabIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        frame.addView(fab, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(20)) })

        refreshConfig()
        return frame
    }

    private fun sitesPage(): View {
        val col = pageColumn("站点")
        siteSearch = EditText(this).apply {
            hint = "搜索站点名或 key"
            isSingleLine = true
            textSize = 15f
            setTextColor(c(R.color.on_bg)); setHintTextColor(c(R.color.on_bg_dim))
            background = round(c(R.color.card), dp(24))
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { renderSites() }
            })
        }
        col.addView(siteSearch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bar = row().apply { setPadding(dp(4), dp(14), 0, dp(6)) }
        siteCount = label("", 13f, dim = true)
        bar.addView(siteCount, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(chip("全选") { setEnabledSites(supported().map { it.key }.toSet()) })
        bar.addView(chip("清空") { setEnabledSites(emptySet()) }, gapLp(8))
        col.addView(bar)
        siteList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(siteList)
        renderSites()
        return scroll(col)
    }

    private fun logPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(12)) }
        val head = row()
        head.addView(label("日志", 30f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(iconButton(R.drawable.ic_fullscreen, "全屏", size = 44) { fullLog() })
        head.addView(iconButton(R.drawable.ic_share, "分享日志", size = 44) { shareLog() }, gapLp(8))
        head.addView(iconButton(R.drawable.ic_delete, "清空日志", size = 44) {
            AlertDialog.Builder(this).setMessage("清空全部日志？")
                .setPositiveButton("清空") { _, _ -> DiagLog.clear(); logDirty = true; renderLog() }
                .setNegativeButton("取消", null).show()
        }, gapLp(8))
        col.addView(head)
        val chips = row().apply { setPadding(0, dp(16), 0, dp(12)) }
        filterChips = listOf("全部", "[JF] 接口", "错误与超时").mapIndexed { i, t ->
            chip(t) { logFilter = i; logDirty = true; renderLog() }.also { chips.addView(it, if (i == 0) LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) else gapLp(8)) }
        }
        col.addView(chips)
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(c(R.color.on_bg))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        logScroll = ScrollView(this).apply {
            background = round(c(R.color.log_bg), dp(20))
            addView(logText)
        }
        col.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    private fun settingsPage(): View {
        val col = pageColumn("设置")

        val pan = card()
        pan.addView(label("网盘", 18f, bold = true))
        pan.addView(label("通过配置中心的网页登录夸克、UC 等网盘（扫码或密码），需先启动服务。", 13f, dim = true).apply { setPadding(0, dp(6), 0, dp(14)) })
        val login = primaryButton("网盘登录（扫码 / 密码）")
        login.setOnClickListener {
            if (!SpiderHub.ready) { toast("先启动服务，再登录网盘"); return@setOnClickListener }
            login.isEnabled = false; login.alpha = 0.5f
            DiagLog.w("正在启动配置中心…")
            Thread({
                val url = runCatching { SpiderHub.loginPageUrl() }.getOrNull()
                main.post {
                    login.isEnabled = true; login.alpha = 1f
                    if (url == null) toast("没找到配置中心登录页，请分享日志")
                    else startActivity(Intent(this, LoginActivity::class.java).putExtra("url", url))
                }
            }, "login").start()
        }
        pan.addView(login, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(pan, cardLp())

        val diag = card()
        diag.addView(label("诊断", 18f, bold = true))
        diag.addView(label("单站诊断用首页站点逐步运行 init / 首页 / 分类 / 搜索 / 详情 / 播放，结果写入日志。", 13f, dim = true).apply { setPadding(0, dp(6), 0, dp(10)) })
        val keyword = field("搜索词", prefs.getString("keyword", "繁花")!!)
        val timeout = field("单次调用超时（秒）", prefs.getString("timeout", "30")!!, InputType.TYPE_CLASS_NUMBER)
        keyword.onChange { prefs.edit().putString("keyword", it).apply() }
        timeout.onChange { prefs.edit().putString("timeout", it).apply() }
        diag.addView(fieldLabel("搜索词")); diag.addView(keyword, fieldLp())
        diag.addView(fieldLabel("单次调用超时（秒）")); diag.addView(timeout, fieldLp())
        val diagRow = row().apply { setPadding(0, dp(14), 0, 0) }
        diagRow.addView(secondaryButton("单站诊断运行").apply { setOnClickListener { start(false) } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        diagRow.addView(secondaryButton("探查配置中心").apply { setOnClickListener { probe() } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        diag.addView(diagRow)
        col.addView(diag, cardLp())

        val adv = card()
        val advHead = row()
        advHead.addView(label("高级（实验）", 18f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val toggle = label("展开", 14f).apply { setTextColor(c(R.color.primary)) }
        advHead.addView(toggle)
        adv.addView(advHead)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        listOf(
            "fakePkg" to "伪装包名为 com.github.tvbox.osc",
            "preload" to "预先 System.load 解出的 .so",
            "forceStd" to "强制标准 Init.init（可能被杀进程）",
            "batch" to "单站诊断时批量测同一 jar 的全部站点",
        ).forEach { (k, t) ->
            body.addView(CheckBox(this).apply {
                text = t; textSize = 14f; setTextColor(c(R.color.on_bg))
                buttonTintList = ColorStateList.valueOf(c(R.color.primary))
                isChecked = prefs.getBoolean(k, false)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(k, on).apply() }
            })
        }
        adv.addView(body)
        advHead.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "收起" else "展开"
        }
        col.addView(adv, cardLp())

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        col.addView(label("SpiderHost $ver · 127.0.0.1:${LocalServer.PORT}", 12f, dim = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(24))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return scroll(col)
    }

    private fun navBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(c(R.color.nav))
            setPadding(0, dp(10), 0, dp(12))
        }
        val items = listOf(R.drawable.ic_dashboard to "仪表", R.drawable.ic_list to "站点", R.drawable.ic_article to "日志", R.drawable.ic_settings to "设置")
        navItems = items.mapIndexed { i, (ic, t) ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val pill = FrameLayout(this)
            val img = icon(ic, c(R.color.on_bg_dim))
            pill.addView(img, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            item.addView(pill, LinearLayout.LayoutParams(dp(64), dp(32)))
            val tv = label(t, 12f, dim = true).apply { setPadding(0, dp(4), 0, 0) }
            item.addView(tv)
            item.setOnClickListener { show(i) }
            bar.addView(item, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            item to (img to tv)
        }
        return bar
    }

    private fun show(i: Int) {
        current = i
        pages.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { k, (item, iv) ->
            val on = k == i
            val pill = item.getChildAt(0)
            pill.background = if (on) round(c(R.color.primary_container), dp(16)) else null
            iv.first.imageTintList = ColorStateList.valueOf(c(if (on) R.color.on_primary_container else R.color.on_bg_dim))
            iv.second.setTextColor(c(if (on) R.color.on_bg else R.color.on_bg_dim))
            iv.second.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        when (i) {
            0 -> { refreshConfig(); refreshStatus() }
            1 -> { if (loadedSites.isEmpty() && prefs.getString("configUrl", "").orEmpty().isNotBlank()) loadSites { renderSites() } else renderSites() }
            2 -> renderLog()
        }
    }

    // ---------------------------------------------------------------- 仪表 logic

    private fun refreshStatus() {
        if (!::statusLine.isInitialized) return
        val serving = SpiderHub.ready
        when {
            serving -> {
                statusDot.background = round(c(R.color.ok), dp(5))
                statusLine.text = "运行中 · http://127.0.0.1:${LocalServer.PORT}"
                statusDetail.text = "首页站点 ${SpiderHub.shortName(SpiderHub.homeKey).ifBlank { "加载中" }} · 服务 ${SpiderHub.served().size} 个站点"
            }
            running -> {
                statusDot.background = round(c(R.color.primary), dp(5))
                statusLine.text = "启动中…"
                statusDetail.text = "正在下载配置和 jar，进度见日志"
            }
            else -> {
                statusDot.background = round(c(R.color.on_bg_dim), dp(5))
                statusLine.text = "未启动"
                statusDetail.text = "点右下角按钮启动，再到 SenPlayer 添加 127.0.0.1:${LocalServer.PORT}"
            }
        }
        fabIcon.setImageResource(if (serving) R.drawable.ic_stop else R.drawable.ic_play)
        fab.alpha = if (running && !serving) 0.5f else 1f
        fab.contentDescription = if (serving) "停止服务" else "启动服务"
    }

    private fun refreshConfig() {
        if (!::configUrlView.isInitialized) return
        val url = prefs.getString("configUrl", "").orEmpty()
        configUrlView.text = url.ifBlank { "点此填写配置地址（TVBox JSON）" }
        configUrlView.setTextColor(c(if (url.isBlank()) R.color.on_bg_dim else R.color.on_bg))
        val at = prefs.getLong("configLoadedAt", 0L)
        val n = prefs.getInt("configSiteCount", 0)
        configMeta.text = if (url.isBlank()) "尚未设置" else if (at == 0L) "远程 · 尚未加载" else "远程 · ${ago(at)}加载 · $n 个站点"
        val home = prefs.getString("siteKey", "").orEmpty()
        val site = (loadedSites.ifEmpty { SpiderHub.sites }).firstOrNull { it.key == home }
        homeSiteView.text = when {
            home.isBlank() -> "点此选择首页站点"
            site != null -> "${site.name}  [${site.key}]"
            else -> "[$home]"
        }
        homeSiteView.setTextColor(c(if (home.isBlank()) R.color.on_bg_dim else R.color.on_bg))
    }

    private fun onFab() {
        if (SpiderHub.ready) {
            AlertDialog.Builder(this).setTitle("停止服务？")
                .setMessage("会结束 SpiderHost 进程，SenPlayer 将无法访问 127.0.0.1:${LocalServer.PORT}，直到再次启动。")
                .setPositiveButton("停止") { _, _ ->
                    DiagLog.w("用户停止服务")
                    DiagLog.flush()
                    runCatching { startService(Intent(this, ServeService::class.java).setAction(ServeService.ACTION_STOP)) }
                    main.postDelayed({ finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
                }
                .setNegativeButton("取消", null).show()
        } else if (!running) start(true)
    }

    private fun editConfig() {
        val input = field("https://…/config.json", prefs.getString("configUrl", "")!!, InputType.TYPE_TEXT_VARIATION_URI)
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("配置地址").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                if (url != prefs.getString("configUrl", "")) {
                    prefs.edit().putString("configUrl", url).putLong("configLoadedAt", 0L).apply()
                    loadedSites = emptyList()
                    if (url.isNotBlank()) loadSites(force = true) { refreshConfig(); renderSites() }
                }
                refreshConfig()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun pickHome() {
        loadSites { all ->
            val sites = all.filter { SpiderHub.isSupported(it) }
            if (sites.isEmpty()) { toast("配置里没有可用的 type-3 站点"); return@loadSites }
            AlertDialog.Builder(this).setTitle("首页站点（${sites.size} 个）")
                .setItems(sites.map { "${it.name}  [${it.key}]" }.toTypedArray()) { _, i -> setHome(sites[i].key) }
                .show()
        }
    }

    private fun setHome(key: String) {
        prefs.edit().putString("siteKey", key).apply()
        if (SpiderHub.ready) SpiderHub.setHome(key)
        DiagLog.w("首页站点: $key")
        refreshConfig(); renderSites()
    }

    // ---------------------------------------------------------------- 站点 logic

    private fun supported() = loadedSites.filter { SpiderHub.isSupported(it) }

    private fun setEnabledSites(set: Set<String>) {
        prefs.edit().putStringSet("enabledSites", set).apply()
        SpiderHub.enabled = set
        DiagLog.w("启用站点: ${set.size} 个（首页站点始终启用）")
        renderSites()
    }

    private fun renderSites() {
        if (!::siteList.isInitialized) return
        siteList.removeAllViews()
        val url = prefs.getString("configUrl", "").orEmpty()
        if (loadedSites.isEmpty()) {
            siteCount.text = if (url.isBlank()) "先在仪表页填写配置地址" else "站点列表未加载"
            if (url.isNotBlank()) siteList.addView(primaryButton("加载站点列表").apply {
                setOnClickListener { loadSites(force = true) { renderSites() } }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            return
        }
        val sup = supported()
        val enabled = prefs.getStringSet("enabledSites", emptySet())!!
        val home = prefs.getString("siteKey", "").orEmpty()
        val q = siteSearch.text.toString().trim()
        siteCount.text = "已启用 ${sup.count { it.key in enabled }} / ${sup.size}（首页站点始终服务）"
        sup.filter { q.isBlank() || it.name.contains(q, true) || it.key.contains(q, true) }.forEach { s ->
            val item = row().apply {
                background = round(c(R.color.card), dp(18))
                setPadding(dp(8), dp(6), dp(16), dp(6))
            }
            val cb = CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(c(R.color.primary))
                isChecked = s.key in enabled || s.key == home
                isEnabled = s.key != home
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(s.name, 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val tags = buildList {
                add(s.key)
                if (s.key == home) add("首页")
                if (SpiderHub.isConfigCentre(s)) add("配置中心")
                else if (SpiderHub.isPan(s)) add("网盘")
                if (!s.searchable) add("不可搜索")
            }
            texts.addView(label(tags.joinToString(" · "), 12f, dim = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            item.addView(cb)
            item.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            cb.setOnCheckedChangeListener { _, on ->
                val cur = prefs.getStringSet("enabledSites", emptySet())!!.toMutableSet()
                if (on) cur += s.key else cur -= s.key
                prefs.edit().putStringSet("enabledSites", cur).apply()
                SpiderHub.enabled = cur.toSet()
                siteCount.text = "已启用 ${sup.count { it.key in cur }} / ${sup.size}（首页站点始终服务）"
            }
            item.setOnClickListener { if (cb.isEnabled) cb.toggle() }
            item.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("把「${s.name}」设为首页站点？")
                    .setPositiveButton("设为首页") { _, _ -> setHome(s.key) }
                    .setNegativeButton("取消", null).show()
                true
            }
            siteList.addView(item, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        val other = loadedSites.size - sup.size
        if (other > 0) siteList.addView(label("另有 $other 个站点不是 type-3 csp，不会服务。长按站点可设为首页。", 12f, dim = true).apply {
            setPadding(dp(4), dp(14), dp(4), dp(24))
        })
    }

    private var loadingSites = false

    private fun loadSites(force: Boolean = false, onOk: (List<TvboxConfig.Site>) -> Unit) {
        val url = prefs.getString("configUrl", "").orEmpty().trim()
        if (url.isBlank()) { toast("先填写配置地址"); return }
        if (!force) {
            if (loadedSites.isNotEmpty()) { onOk(loadedSites); return }
            if (SpiderHub.ready && SpiderHub.configUrl == url) { loadedSites = SpiderHub.sites; onOk(loadedSites); return }
        }
        if (loadingSites) return
        loadingSites = true
        toast("正在加载配置…")
        Thread {
            val result = runCatching {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "okhttp/3.15").build()
                val body = okhttp3.OkHttpClient().newCall(req).execute().use { it.body!!.string() }
                TvboxConfig.sites(TvboxConfig.parse(TvboxConfig.decodeBody(body)))
            }
            main.post {
                loadingSites = false
                result.onFailure { DiagLog.w("加载站点失败: ${it.message}"); toast("加载失败：${it.message}") }
                result.onSuccess {
                    loadedSites = it
                    prefs.edit().putLong("configLoadedAt", System.currentTimeMillis()).putInt("configSiteCount", it.size).apply()
                    refreshConfig()
                    onOk(it)
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 日志 logic

    private val errorMarks = listOf("FAILED", "TIMEOUT", "BUSY", "SLOW", "UNCAUGHT", "Exception", "失败", "拒绝", "!!!", "UNHANDLED")

    private fun matches(line: String) = when (logFilter) {
        1 -> line.contains("[JF]")
        2 -> errorMarks.any { line.contains(it) }
        else -> true
    }

    private fun renderLog() {
        if (!::logText.isInitialized) return
        filterChips.forEachIndexed { i, chip -> styleChip(chip, i == logFilter) }
        if (!logDirty) return
        logDirty = false
        val all = DiagLog.readAll().takeLast(400_000)
        val text = if (logFilter == 0) all else all.lineSequence().filter { matches(it) }.joinToString("\n")
        logText.text = text.takeLast(80_000)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun onLogLine(line: String) {
        if (current != 2 || !::logText.isInitialized) { logDirty = true; return }
        if (!matches(line)) return
        logText.append(line)
        if (logText.length() > 160_000) logText.text = logText.text.toString().takeLast(80_000)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shareLog() {
        val text = DiagLog.readAll().takeLast(200_000)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "分享 SpiderHost 日志"))
    }

    private fun fullLog() {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(c(R.color.on_bg)); setTextIsSelectable(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            text = DiagLog.readAll().takeLast(200_000)
        }
        val sv = ScrollView(this).apply { setBackgroundColor(c(R.color.bg)); addView(tv) }
        android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar).apply { setContentView(sv); show() }
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------- run / serve

    private fun probe() {
        if (!SpiderHub.ready) { toast("先启动服务，再探查配置中心"); return }
        loadSites { sites ->
            val cands = sites.filter { SpiderHub.isSupported(it) && (it.name.contains("配置") || it.key.contains("config", true) || it.name.contains("盘", true)) }
                .ifEmpty { sites.filter { SpiderHub.isSupported(it) } }
            AlertDialog.Builder(this).setTitle("选择要探查的站点")
                .setItems(cands.map { "${it.name}  [${it.key}]" }.toTypedArray()) { _, i ->
                    Thread({ SpiderHub.probeConfigCentre(cands[i].key) }, "probe").start()
                    toast("探查中，结果见日志"); show(2)
                }.show()
        }
    }

    private fun start(serveMode: Boolean) {
        if (running) { toast("正在运行，请稍候"); return }
        val url = prefs.getString("configUrl", "").orEmpty().trim()
        val key = prefs.getString("siteKey", "").orEmpty().trim()
        if (url.isBlank()) { toast("先填写配置地址"); show(0); return }
        if (key.isBlank()) { toast("先选择首页站点"); pickHome(); return }
        val opts = SpiderRunner.Options(
            configUrl = url,
            siteKey = key,
            keyword = prefs.getString("keyword", "繁花").orEmpty().ifBlank { "繁花" },
            timeoutSec = prefs.getString("timeout", "30")?.toLongOrNull()?.coerceIn(5, 300) ?: 30,
            fakeTvboxPackage = prefs.getBoolean("fakePkg", false),
            preloadSo = prefs.getBoolean("preload", false),
            forceStandardInit = prefs.getBoolean("forceStd", false),
            batch = prefs.getBoolean("batch", false) && !serveMode,
            serve = serveMode,
        )
        if (serveMode) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            runCatching { ServeService.start(this) }.onFailure { DiagLog.e("启动前台服务失败", it) }
        } else {
            show(2)
        }
        running = true
        refreshStatus()
        Thread({
            try {
                SpiderRunner(SpiderHostApp.instance, opts).run()
            } finally {
                running = false
                main.post { refreshStatus() }
            }
        }, if (serveMode) "spider-serve" else "spider-run").start()
    }

    // ---------------------------------------------------------------- view helpers

    private fun c(id: Int) = getColor(id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun pageColumn(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(16))
        addView(label(title, 30f, bold = true).apply { setPadding(dp(4), 0, 0, dp(20)) })
    }

    private fun scroll(v: View) = ScrollView(this).apply { isFillViewport = true; addView(v) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(c(R.color.card), dp(24))
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    private fun cardLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }
    private fun gapLp(gap: Int = 12) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(gap) }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun label(t: String, size: Float, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        setTextColor(c(if (dim) R.color.on_bg_dim else R.color.on_bg))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun icon(res: Int, tint: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
    }

    private fun iconButton(res: Int, desc: String, size: Int = 56, onClick: () -> Unit) = FrameLayout(this).apply {
        background = round(c(R.color.card_inner), dp(if (size >= 56) 18 else 14))
        contentDescription = desc
        addView(icon(res, c(R.color.on_bg)), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = ViewGroup.LayoutParams(dp(size), dp(size))
        setOnClickListener { onClick() }
        setOnLongClickListener { toast(desc); true }
    }.also { it.minimumWidth = dp(size); it.minimumHeight = dp(size) }

    private fun chip(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f
        setPadding(dp(14), dp(7), dp(14), dp(7))
        styleChip(this, false)
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.background = round(c(if (on) R.color.primary_container else R.color.card), dp(16))
        v.setTextColor(c(if (on) R.color.on_primary_container else R.color.on_bg))
    }

    private fun primaryButton(t: String) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(c(R.color.on_primary_container))
        background = round(c(R.color.primary_container), dp(22))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun secondaryButton(t: String) = TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(c(R.color.on_bg))
        background = round(c(R.color.card_inner), dp(20))
        setPadding(dp(12), dp(13), dp(12), dp(13))
    }

    private fun fieldLabel(t: String) = label(t, 12f, dim = true).apply { setPadding(dp(4), dp(10), 0, dp(4)) }
    private fun fieldLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

    private fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 15f
            setTextColor(c(R.color.on_bg)); setHintTextColor(c(R.color.on_bg_dim))
            background = round(c(R.color.card_inner), dp(16))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = if (type == InputType.TYPE_CLASS_NUMBER) type else InputType.TYPE_CLASS_TEXT or type
            isSingleLine = true
        }

    private fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString().orEmpty()) }
    })

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "$m 分钟前"
            m < 24 * 60 -> "${m / 60} 小时前"
            else -> "${m / 1440} 天前"
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
