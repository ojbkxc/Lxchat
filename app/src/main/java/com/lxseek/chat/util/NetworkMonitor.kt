package com.lxseek.chat.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Lightweight network availability monitor backed by ConnectivityManager.
 *
 * Provides a synchronous [isOnline] check and an observable [online] flow
 * so callers can pre-check before sending and react to connectivity changes.
 * The registered [NetworkCallback] is retained as a field so [unregister]
 * can remove the exact same instance that [register] installed.
 */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _online = MutableStateFlow(checkNow())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    // Retained so unregister() removes the exact callback that register() installed.
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isOnline(): Boolean = checkNow()

    /**
     * Emits [Unit] each time connectivity transitions from offline to online.
     *
     * Cold flow — collect on a caller-owned scope. Complements [online] (which reports the
     * current value) by surfacing only the restoration edge, so retry logic does not fire on
     * the initial subscription value and only reacts to an actual offline→online transition.
     */
    val onlineRestored: Flow<Unit> = online
        .drop(1)
        .filter { it }
        .map { Unit }

    private fun checkNow(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun register() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { _online.value = false }
        }
        callback = cb
        cm.registerNetworkCallback(request, cb)
    }

    fun unregister() {
        val cb = callback ?: return
        // 重复反注册或回调已失效时会抛非致命异常：记录原因便于排查，失败不影响后续流程
        try { cm.unregisterNetworkCallback(cb) } catch (e: Throwable) {
            DebugLog.w("NetworkMonitor", "unregisterNetworkCallback failed", e)
        }
        callback = null
    }
}