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
    @Volatile private var files: List<File> = emptyList()
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

    // Lines are written by one background thread, so request threads never wait on file I/O or each other.
    private val pending = java.util.concurrent.LinkedBlockingQueue<String>()
    private val writer = Thread({
        while (true) {
            val first = runCatching { pending.take() }.getOrNull() ?: continue
            val batch = StringBuilder(first)
            while (true) { val next = pending.poll() ?: break; batch.append(next); if (batch.length > 64_000) break }
            val text = batch.toString()
            files.forEach { runCatching { it.appendText(text) } }
            synchronized(pending) { (pending as Object).notifyAll() }
        }
    }, "diaglog-writer").apply { isDaemon = true; start() }

    fun w(msg: String) {
        Log.i(TAG, msg)
        val line = synchronized(fmt) { fmt.format(Date()) } + " [${Thread.currentThread().name}] $msg\n"
        pending.offer(line)
        listener?.invoke(line)
    }

    /** Waits (briefly) until queued lines are on disk. */
    fun flush() {
        val until = System.currentTimeMillis() + 500
        synchronized(pending) {
            while (pending.isNotEmpty() && System.currentTimeMillis() < until) (pending as Object).wait(50)
        }
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

    fun readAll(): String = flush().let { primaryFile }?.takeIf { it.exists() }?.readText().orEmpty()

    fun clear() {
        flush()
        files.forEach { runCatching { it.writeText("") } }
    }
}
