package com.oksen.spiderhost

import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Native-library handling for "Guard" spider jars.
 *
 * What Kototoro actually does (TVBoxJarSpiderRuntime / TVBoxProtectedInitCompat, Apache-2.0):
 * it does NOT unpack .so files itself. For protected jars it calls Init.get(), injects a
 * Context into the instance's Context field, then calls the jar's own exeLibStub(), which
 * extracts and loads the jar's native code. That is the default path here too.
 *
 * This class adds spike-only diagnostics on top: it lists every .so inside the jar, and
 * (inferred, not from Kototoro) copies the ones matching the device ABI into
 * files/so/<abi>/ so that directory can be the DexClassLoader librarySearchPath.
 * Optional preloading via System.load is off by default because loading a Guard .so
 * outside the jar's expected order may itself trigger its anti-tamper checks.
 */
object GuardNative {

    data class Result(
        val allSo: List<String>,
        val extracted: List<File>,
        val libDir: File,
        val abi: String,
    )

    fun prepare(jar: File, filesDir: File): Result {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libDir = File(filesDir, "so/$abi").apply { mkdirs() }
        val all = ArrayList<String>()
        val extracted = ArrayList<File>()
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".so", true) }.forEach { e ->
                all += "${e.name} (${e.size} B)"
                if (matchesAbi(e.name, abi)) {
                    val out = File(libDir, e.name.substringAfterLast('/'))
                    if (out.exists()) out.setWritable(true)
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                    out.setReadable(true, false)
                    out.setWritable(false, false)
                    extracted += out
                }
            }
        }
        return Result(all, extracted, libDir, abi)
    }

    /** Preload extracted libraries (spike toggle). Returns per-file outcome lines. */
    fun preload(result: Result): List<String> = result.extracted.map { f ->
        runCatching { System.load(f.absolutePath); "loaded ${f.name}" }
            .getOrElse { "FAILED ${f.name}: ${it.javaClass.name}: ${it.message}" }
    }

    private fun matchesAbi(path: String, abi: String): Boolean {
        val p = path.lowercase()
        val arm64 = listOf("arm64-v8a", "arm64", "aarch64", "v8a")
        val arm32 = listOf("armeabi-v7a", "armeabi", "armv7", "v7a")
        val wanted = if (abi.startsWith("arm64")) arm64 else arm32
        val other = if (abi.startsWith("arm64")) arm32 else arm64
        return when {
            wanted.any { p.contains(it) } -> !(abi.startsWith("arm64") && p.contains("armeabi"))
            other.any { p.contains(it) } -> false
            p.contains("x86") -> false
            else -> true // ABI not encoded in the path: take it and let the loader decide.
        }
    }
}
