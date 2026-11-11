/*
 * Static DEX inspection for downloaded spider jars.
 *
 * isProtectedInit() is ADAPTED from Kototoro's TVBoxProtectedInitDetector
 * (https://github.com/kototoro-app/Kototoro, Apache License 2.0, Copyright the Kototoro authors).
 * Changes: restructured into a shared reader, android.util.Base64 instead of java.util.Base64
 * (minSdk 24), plus a new host-reference scan (externalReferences) written for this spike.
 *
 * Heuristic (from Kototoro): a jar is "protected" when com.github.catvod.spider.Init* code
 * invokes Process.killProcess or System.exit, and Init.init(...) does NOT carry our package
 * name in its whitelist strings (plain or AES/CBC "1234123412341234"-encrypted base64).
 * For such jars, calling Init.init(context) directly is expected to kill the process.
 */
package com.oksen.spiderhost

import android.util.Base64
import java.io.File
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DexInspector {

    private const val INIT_DESCRIPTOR = "Lcom/github/catvod/spider/Init;"
    private const val INIT_PREFIX = "Lcom/github/catvod/spider/Init"
    private const val PROCESS = "Landroid/os/Process;"
    private const val SYSTEM = "Ljava/lang/System;"
    private const val AES_KEY = "1234123412341234"
    private const val HEADER_SIZE = 112

    /** Reads every top-level classes*.dex in the jar (or the file itself if it is a raw dex). */
    fun readDexes(file: File): List<Pair<String, ByteArray>> {
        val head = file.inputStream().use { s -> ByteArray(3).also { s.read(it) } }
        if (head.decodeToString() == "dex") return listOf(file.name to file.readBytes())
        return ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".dex", true) && !it.name.contains('/') }
                .map { it.name to zip.getInputStream(it).use { s -> s.readBytes() } }
                .toList()
        }
    }

    fun isProtectedInit(dexes: List<ByteArray>, packageName: String): Boolean =
        dexes.any { runCatching { Reader(it).isProtected(packageName) }.getOrDefault(false) }

    /**
     * Class descriptors the dex references but does not define, minus platform packages.
     * These must come from the host (parent class loader) or the jar fails with NoClassDefFoundError.
     */
    fun externalReferences(dexes: List<ByteArray>): Set<String> {
        val referenced = HashSet<String>()
        val defined = HashSet<String>()
        dexes.forEach { data ->
            runCatching {
                val r = Reader(data)
                referenced += r.allTypes()
                defined += r.definedTypes()
            }
        }
        return referenced.asSequence()
            .map { it.trimStart('[') }
            .filter { it.startsWith("L") && it.endsWith(";") }
            .filter { it !in defined }
            .map { it.substring(1, it.length - 1).replace('/', '.') }
            .filterNot { name -> PLATFORM_PREFIXES.any { name.startsWith(it) } }
            .toSortedSet()
    }

    private val PLATFORM_PREFIXES = listOf(
        "java.", "javax.", "android.", "dalvik.", "kotlin.", "org.json.", "org.w3c.", "org.xml.",
        "org.xmlpull.", "org.apache.http.", "sun.", "libcore.", "com.android.", "junit.",
    )

    private class Reader(private val data: ByteArray) {
        init {
            require(data.size >= HEADER_SIZE && data.decodeToString(0, 3) == "dex")
        }

        private val stringsSize = u4(0x38); private val stringsOff = u4(0x3c)
        private val typesSize = u4(0x40); private val typesOff = u4(0x44)
        private val methodsSize = u4(0x58); private val methodsOff = u4(0x5c)
        private val classesSize = u4(0x60); private val classesOff = u4(0x64)

        init {
            listOf(stringsSize to stringsOff * 1L + stringsSize * 4L, typesSize to typesOff * 1L + typesSize * 4L,
                methodsSize to methodsOff * 1L + methodsSize * 8L, classesSize to classesOff * 1L + classesSize * 32L)
                .forEach { (size, end) -> require(size >= 0 && end <= data.size) }
        }

        fun allTypes(): List<String> = (0 until typesSize).map { type(it) }

        fun definedTypes(): List<String> = (0 until classesSize).map { type(u4(classesOff + it * 32)) }

        fun isProtected(packageName: String): Boolean {
            var hasInit = false
            var kills = false
            var whitelisted = false
            for (i in 0 until classesSize) {
                val classOff = classesOff + i * 32
                val desc = type(u4(classOff))
                if (!desc.startsWith(INIT_PREFIX)) continue
                val isInit = desc == INIT_DESCRIPTOR
                hasInit = hasInit || isInit
                val dataOff = u4(classOff + 24)
                if (dataOff == 0) continue
                val methods = classMethods(dataOff)
                if (isInit) {
                    whitelisted = methods.filter { methodName(it.first) == "init" && it.second != 0 }
                        .any { codeHasPackage(it.second, packageName) }
                }
                kills = kills || methods.any { it.second != 0 && codeKills(it.second) }
            }
            return hasInit && kills && !whitelisted
        }

        /** (methodIndex, codeOffset) for direct + virtual methods. */
        private fun classMethods(off: Int): List<Pair<Int, Int>> {
            val c = intArrayOf(off)
            val sf = uleb(c); val inf = uleb(c); val dm = uleb(c); val vm = uleb(c)
            repeat(sf + inf) { uleb(c); uleb(c) }
            val out = ArrayList<Pair<Int, Int>>(dm + vm)
            for (count in intArrayOf(dm, vm)) {
                var idx = 0
                repeat(count) {
                    idx += uleb(c); uleb(c); val code = uleb(c)
                    if (idx in 0 until methodsSize) out += idx to code
                }
            }
            return out
        }

        private fun codeHasPackage(codeOff: Int, pkg: String) = scan(codeOff) { at, op ->
            when (op) {
                0x1a -> whitelistContains(string(u2(at + 2)), pkg)   // const-string
                0x1b -> whitelistContains(string(u4(at + 2)), pkg)   // const-string/jumbo
                else -> false
            }
        }

        private fun codeKills(codeOff: Int) = scan(codeOff) { at, op ->
            if (op !in 0x6e..0x72 && op !in 0x74..0x78) return@scan false
            val m = u2(at + 2)
            val owner = methodOwner(m); val name = methodName(m)
            (owner == PROCESS && name == "killProcess") || (owner == SYSTEM && name == "exit")
        }

        private inline fun scan(codeOff: Int, pred: (Int, Int) -> Boolean): Boolean {
            val insns = u4(codeOff + 12)
            val start = codeOff + 16
            val end = Math.addExact(start, Math.multiplyExact(insns, 2))
            require(end <= data.size)
            var at = start
            while (at + 1 < end) {
                if (pred(at, data[at].toInt() and 0xff)) return true
                at += 2
            }
            return false
        }

        private fun methodOwner(m: Int) = if (m in 0 until methodsSize) type(u2(methodsOff + m * 8)) else ""
        private fun methodName(m: Int) = if (m in 0 until methodsSize) string(u4(methodsOff + m * 8 + 4)) else ""
        private fun type(t: Int) = if (t in 0 until typesSize) string(u4(typesOff + t * 4)) else ""

        private fun string(s: Int): String {
            if (s !in 0 until stringsSize) return ""
            val c = intArrayOf(u4(stringsOff + s * 4))
            uleb(c)
            val start = c[0]
            var end = start
            while (end < data.size && data[end].toInt() != 0) end++
            require(end < data.size)
            return data.decodeToString(start, end)
        }

        private fun uleb(c: IntArray): Int {
            var result = 0; var shift = 0
            repeat(5) {
                require(c[0] < data.size)
                val v = data[c[0]++].toInt() and 0xff
                result = result or ((v and 0x7f) shl shift)
                if (v and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("bad uleb128")
        }

        private fun u2(o: Int): Int {
            require(o >= 0 && o + 2 <= data.size)
            return (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8)
        }

        private fun u4(o: Int): Int {
            require(o >= 0 && o + 4 <= data.size)
            return (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or
                ((data[o + 2].toInt() and 0xff) shl 16) or ((data[o + 3].toInt() and 0xff) shl 24)
        }
    }

    private fun whitelistContains(value: String, pkg: String): Boolean {
        if (pkg.isBlank() || value.isBlank()) return false
        fun has(v: String) = v.split(',').any { it.trim() == pkg }
        return has(value) || decrypt(value)?.let(::has) == true
    }

    private fun decrypt(value: String): String? = runCatching {
        if (value.length < 24 || value.length % 4 != 0) return null
        val key = AES_KEY.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        cipher.doFinal(Base64.decode(value, Base64.DEFAULT)).toString(Charsets.UTF_8)
    }.getOrNull()
}
