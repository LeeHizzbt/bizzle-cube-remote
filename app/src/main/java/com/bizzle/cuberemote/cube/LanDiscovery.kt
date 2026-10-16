package com.bizzle.cuberemote.cube

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

data class FoundTv(val host: String, val port: Int, val label: String)

/** Finds Fire TVs on the LAN: mDNS `_adb._tcp`, then a /24 sweep of port 5555. */
class LanDiscovery(private val context: Context) {

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun localIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }?.firstOrNull { !it.isLoopbackAddress }?.hostAddress?.let { return it }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()
    }

    fun sameSubnet(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.substringBeforeLast('.') == b.substringBeforeLast('.')
    }

    suspend fun probe(host: String, port: Int, timeoutMs: Int = 300): Boolean = withContext(Dispatchers.IO) {
        try { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } } catch (_: Exception) { false }
    }

    suspend fun discover(onProgress: (String) -> Unit = {}): List<FoundTv> = coroutineScope {
        val found = Collections.synchronizedList(mutableListOf<FoundTv>())
        onProgress("Listening for Fire TVs (mDNS)…")
        withTimeoutOrNull(3000) { nsd("_adb._tcp.") }?.forEach { s ->
            val h = s.host?.hostAddress ?: return@forEach
            if (h.contains('.')) found += FoundTv(h, if (s.port > 0) s.port else 5555, s.serviceName ?: "Fire TV")
        }
        val me = localIpv4()
        if (me != null && me.contains('.')) {
            val prefix = me.substringBeforeLast('.')
            onProgress("Scanning $prefix.1-254 for port 5555…")
            val sem = Semaphore(48)
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    val h = "$prefix.$i"
                    if (h != me && sem.withPermit { probe(h, 5555, 350) }) found += FoundTv(h, 5555, "Fire TV at $h")
                }
            }.awaitAll()
        }
        found.distinctBy { it.host + ":" + it.port }
    }

    private suspend fun nsd(type: String): List<NsdServiceInfo> = withContext(Dispatchers.Main) {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("bizzle_mdns").apply { setReferenceCounted(false); acquire() }
        val results = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(info: NsdServiceInfo?) {
                if (info == null) return
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(r: NsdServiceInfo?) { if (r != null) results += r }
                    })
                }
            }
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(2500)
        } catch (_: Exception) {
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
        results.toList()
    }
}
