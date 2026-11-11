package com.oksen.spiderhost

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import com.github.catvod.Init
import com.github.catvod.Proxy
import com.github.catvod.crawler.SpiderDebug
import java.io.File

class SpiderHostApp : Application() {

    lateinit var server: LocalServer
        private set

    /** Filled at startup if the previous run did not finish cleanly (e.g. the jar killed the process). */
    var previousRunReport: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagLog.init(this)
        SpiderDebug.setSink { DiagLog.w(it) }
        installCrashHandler()
        previousRunReport = buildPreviousRunReport()
        Init.set(this)
        Proxy.set(LocalServer.PORT)
        JellyfinApi.init(filesDir)
        JellyfinApi.prefs = getSharedPreferences("spike", MODE_PRIVATE)
        server = LocalServer()
        runCatching { server.start() }
            .onSuccess { DiagLog.w("LocalServer listening on 127.0.0.1:${LocalServer.PORT}") }
            .onFailure { DiagLog.e("LocalServer failed to start on ${LocalServer.PORT}", it) }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { DiagLog.e("UNCAUGHT on thread ${thread.name}", error); DiagLog.flush() }
            // Flyme's screen-recognition picker (com.meizu.internal.picker) crashes inside our process on its own
            // "ContentCapture" thread; that is not our code, so log it and keep the process (and the server) alive.
            val systemOnly = error.stackTrace.any { it.className.startsWith("com.meizu.internal.") }
            if (systemOnly) DiagLog.w("忽略系统线程异常（Flyme 识屏），进程继续运行")
            else previous?.uncaughtException(thread, error)
        }
    }

    private fun buildPreviousRunReport(): String? {
        val marker = runMarker(this)
        val sb = StringBuilder()
        if (marker.exists()) {
            sb.append("上次运行没有正常结束，最后步骤：").append(marker.readText().trim()).append('\n')
            sb.append("（进程很可能被 jar 内的 Process.killProcess / System.exit 或 native 崩溃结束）\n")
            marker.delete()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val am = getSystemService(ActivityManager::class.java)
                am.getHistoricalProcessExitReasons(packageName, 0, 5).forEach { info ->
                    sb.append("ExitInfo: reason=").append(info.reason)
                        .append(" status=").append(info.status)
                        .append(" time=").append(java.util.Date(info.timestamp))
                        .append(" desc=").append(info.description).append('\n')
                }
            }.onFailure { sb.append("ExitInfo unavailable: ").append(it).append('\n') }
        }
        return sb.toString().ifBlank { null }?.also { DiagLog.w("Previous-run report:\n$it") }
    }

    companion object {
        lateinit var instance: SpiderHostApp
            private set

        fun runMarker(app: Application): File = File(app.filesDir, "run-in-progress.txt")
    }
}
