package com.oksen.spiderhost

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only diagnostic log: logcat + files/logs/spiderhost.log (+ a copy under external app files when available). */
object DiagLog {
    private const val TAG = "SpiderHost"
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var files: List<File> = emptyList()
    @Volatile var listener: ((String) -> Unit)? = null

    fun init(context: Context) {
        val internal = File(context.filesDir, "logs/spiderhost.log")
        val external = context.getExternalFilesDir(null)?.let { File(it, "spiderhost.log") }
        files = listOfNotNull(internal, external)
        files.forEach { f ->
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, f.name + ".old"))
        }
    }

    val primaryFile: File? get() = files.firstOrNull()

    @Synchronized
    fun w(msg: String) {
        Log.i(TAG, msg)
        val line = "${fmt.format(Date())} [${Thread.currentThread().name}] $msg\n"
        files.forEach { runCatching { it.appendText(line) } }
        listener?.invoke(line)
    }

    fun e(msg: String, t: Throwable?) {
        w(if (t == null) msg else "$msg\n${fullTrace(t)}")
    }

    /** Full stack trace including every cause and InvocationTargetException target. */
    fun fullTrace(t: Throwable): String = Log.getStackTraceString(t).ifBlank {
        // Log.getStackTraceString hides UnknownHostException; fall back to printStackTrace.
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        sw.toString()
    }

    fun readAll(): String = primaryFile?.takeIf { it.exists() }?.readText().orEmpty()

    fun clear() {
        files.forEach { runCatching { it.writeText("") } }
    }
}
