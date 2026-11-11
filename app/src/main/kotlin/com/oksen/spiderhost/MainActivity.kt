package com.oksen.spiderhost

import android.app.Activity
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
        val forceStd = CheckBox(this).apply { text = "强制标准 Init.init（可能被杀进程）"; isChecked = prefs.getBoolean("forceStd", false) }

        val run = Button(this).apply { text = "Run" }
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

        listOf(configUrl, siteKey, keyword, timeout, fakePkg, preload, forceStd, buttons)
            .forEach { root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        output.text = DiagLog.readAll().takeLast(60_000)
        SpiderHostApp.instance.previousRunReport?.let { append("\n!!! $it\n") }
        DiagLog.listener = { line -> main.post { append(line) } }

        run.setOnClickListener {
            if (running) return@setOnClickListener
            prefs.edit()
                .putString("configUrl", configUrl.text.toString().trim())
                .putString("siteKey", siteKey.text.toString().trim())
                .putString("keyword", keyword.text.toString())
                .putString("timeout", timeout.text.toString())
                .putBoolean("fakePkg", fakePkg.isChecked)
                .putBoolean("preload", preload.isChecked)
                .putBoolean("forceStd", forceStd.isChecked)
                .apply()
            val opts = SpiderRunner.Options(
                configUrl = configUrl.text.toString().trim(),
                siteKey = siteKey.text.toString().trim(),
                keyword = keyword.text.toString().ifBlank { "繁花" },
                timeoutSec = timeout.text.toString().toLongOrNull()?.coerceIn(5, 300) ?: 30,
                fakeTvboxPackage = fakePkg.isChecked,
                preloadSo = preload.isChecked,
                forceStandardInit = forceStd.isChecked,
            )
            running = true
            run.isEnabled = false
            Thread({
                try {
                    SpiderRunner(SpiderHostApp.instance, opts).run()
                } finally {
                    running = false
                    main.post { run.isEnabled = true }
                }
            }, "spider-run").start()
        }
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
