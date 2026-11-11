package com.oksen.spiderhost

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Netdisk login inside the app. The config-centre jar cannot show its own popup in this shell
 * ("当前壳子无法弹窗"), and instead serves a local web page (port 19988, /config) with QR and
 * password login for each netdisk. This activity simply shows that page.
 */
class LoginActivity : Activity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) { title = view.title ?: u }
        }
        web.webChromeClient = WebChromeClient()
        setContentView(web)
        title = "网盘登录"
        DiagLog.w("LOGIN: 打开配置中心 $url")
        web.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val web = window.decorView.findViewById<android.view.View>(android.R.id.content)
            ?.let { (it as? android.view.ViewGroup)?.getChildAt(0) as? WebView }
        if (web != null && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
