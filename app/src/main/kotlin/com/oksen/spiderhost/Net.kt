package com.oksen.spiderhost

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS for the host's own HTTP clients (config, jar download, posters, /proxy, /hls).
 * System DNS first; when it fails (seen on device: "Unable to resolve host … No address associated with hostname"),
 * use the last good answer, then DNS-over-HTTPS at dns.alidns.com (reached by its fixed IPs, so DoH itself needs no DNS).
 * Spider jars that bring their own OkHttp client are not affected.
 */
object Net {
    private class Hit(val addrs: List<InetAddress>, val at: Long)
    private val cache = ConcurrentHashMap<String, Hit>()
    private const val TTL = 30 * 60_000L

    private val doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build())
            .url("https://dns.alidns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("223.5.5.5"), InetAddress.getByName("223.6.6.6"))
            .includeIPv6(false)
            .build()
    }

    val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val sys = try { Dns.SYSTEM.lookup(hostname) } catch (e: UnknownHostException) { null }
            if (!sys.isNullOrEmpty()) { cache[hostname] = Hit(sys, System.currentTimeMillis()); return sys }
            cache[hostname]?.takeIf { System.currentTimeMillis() - it.at < TTL }?.let {
                DiagLog.w("DNS: 系统解析 $hostname 失败，用缓存 ${it.addrs.firstOrNull()?.hostAddress}")
                return it.addrs
            }
            val viaDoh = try { doh.lookup(hostname) } catch (e: Exception) {
                DiagLog.w("DNS: 系统与 DoH 都解析不了 $hostname: ${e.message}")
                throw UnknownHostException("$hostname（系统 DNS 与 DoH 都失败）")
            }
            DiagLog.w("DNS: 系统解析 $hostname 失败，DoH 得到 ${viaDoh.firstOrNull()?.hostAddress}")
            cache[hostname] = Hit(viaDoh, System.currentTimeMillis())
            return viaDoh
        }
    }
}
