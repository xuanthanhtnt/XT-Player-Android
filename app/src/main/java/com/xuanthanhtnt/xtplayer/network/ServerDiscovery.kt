package com.xuanthanhtnt.xtplayer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.xuanthanhtnt.xtplayer.core.NaturalOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/** A file server announced on the local network over Bonjour / mDNS. */
data class DiscoveredServer(val name: String, val kind: ServerKind, val serviceType: String, val info: NsdServiceInfo) {
    val id: String get() = name + kind.raw
    val secure: Boolean get() = serviceType.contains("webdavs")
}

/** Finds SMB (and WebDAV) servers on the LAN, like Infuse's "nearby shares". */
class ServerDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val byType = mutableMapOf<String, List<DiscoveredServer>>()

    private val state = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = state

    fun start() {
        if (listeners.isNotEmpty()) return
        lock = wifi?.createMulticastLock("xtplayer.discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        for ((type, kind) in listOf("_smb._tcp" to ServerKind.SMB, "_webdav._tcp" to ServerKind.WEBDAV, "_webdavs._tcp" to ServerKind.WEBDAV)) {
            val found = mutableMapOf<String, DiscoveredServer>()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(found) {
                        found[info.serviceName] = DiscoveredServer(info.serviceName, kind, type, info)
                        update(type, found.values.toList())
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(found) {
                        found.remove(info.serviceName)
                        update(type, found.values.toList())
                    }
                }
            }
            runCatching {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(listener)
            }
        }
    }

    fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        runCatching { lock?.release() }
        lock = null
    }

    @Synchronized
    private fun update(type: String, list: List<DiscoveredServer>) {
        byType[type] = list
        val seen = mutableSetOf<String>()
        state.value = byType.values.flatten().filter { seen.add(it.id) }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    /** Resolves a service to an IPv4 address (falls back to "<name>.local"). */
    @Suppress("DEPRECATION")
    suspend fun resolveHost(server: DiscoveredServer): String {
        val resolved = withTimeoutOrNull(6000) {
            suspendCancellableCoroutine<String?> { continuation ->
                runCatching {
                    nsd.resolveService(server.info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host
                            val text = when (host) {
                                is Inet4Address -> host.hostAddress
                                null -> null
                                else -> host.hostAddress?.substringBefore('%')
                            }
                            if (continuation.isActive) continuation.resume(text)
                        }
                    })
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
        return resolved?.takeIf { it.isNotEmpty() } ?: (server.name.replace(' ', '-') + ".local")
    }
}
