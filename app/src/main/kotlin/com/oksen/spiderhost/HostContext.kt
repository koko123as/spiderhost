package com.oksen.spiderhost

import android.content.Context
import android.content.ContextWrapper

/**
 * Optional context handed to the jar that reports a different package name.
 * Kototoro does a much fuller identity bridge (package name, signature, ApplicationInfo,
 * PackageManager) inside its App class; this spike only fakes getPackageName(), which is
 * enough to see whether a whitelist check is what kills us. Off by default.
 */
class HostContext(base: Context, private val fakePackage: String) : ContextWrapper(base) {
    override fun getPackageName(): String = fakePackage
    override fun getApplicationContext(): Context = this
}
