package org.forestear.node.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.forestear.node.util.FLog

/**
 * Watches connectivity so the offline queue is flushed the moment a network
 * comes back, instead of waiting for the next event or heartbeat.
 *
 * Works on Wi-Fi-only tablets and on phones; there is no assumption that
 * telephony exists. `registerDefaultNetworkCallback` is used on API 24+, which
 * is below our minSdk, so no reflection is needed.
 */
class ConnectivityMonitor(
    context: Context,
    private val onAvailable: () -> Unit,
) {
    private val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            FLog.i("network available: $network")
            runCatching { onAvailable() }
        }

        override fun onLost(network: Network) {
            FLog.w("network lost: $network")
        }
    }

    fun start() {
        if (registered) return
        val m = manager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { m.registerNetworkCallback(request, callback) }
            .onSuccess { registered = true }
            .onFailure { FLog.w("connectivity callback failed: ${it.message}") }
    }

    fun stop() {
        if (!registered) return
        val m = manager ?: return
        runCatching { m.unregisterNetworkCallback(callback) }
        registered = false
    }

    /** Current transport label for the heartbeat, or "offline". */
    fun transport(): String {
        val m = manager ?: return "unknown"
        val caps = runCatching { m.getNetworkCapabilities(m.activeNetwork) }.getOrNull()
            ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    fun hasInternet(): Boolean {
        val m = manager ?: return false
        val caps = runCatching { m.getNetworkCapabilities(m.activeNetwork) }.getOrNull()
            ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
