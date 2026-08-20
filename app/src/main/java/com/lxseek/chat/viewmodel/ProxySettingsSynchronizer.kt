package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Projects proxy settings into the process-scoped HTTP client configuration. */
internal class ProxySettingsSynchronizer(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val apply: (HttpClient.ProxyConfig?) -> Unit,
) {
    fun start() {
        scope.launch {
            val changeSignals = listOf(
                settings.proxyEnabled.map(Boolean::toString),
                settings.proxyType,
                settings.proxyHost,
                settings.proxyPort,
                settings.proxyUsername,
                settings.proxyPassword,
                settings.proxyBypass,
            )
            combine(changeSignals) { it }.collect {
                apply(currentProxyConfig(settings))
            }
        }
    }
}

internal fun currentProxyConfig(settings: SettingsRepository): HttpClient.ProxyConfig? {
    val host = settings.proxyHost.value.trim()
    if (!settings.proxyEnabled.value || host.isEmpty()) return null
    return HttpClient.ProxyConfig(
        type = if (settings.proxyType.value.equals("socks5", ignoreCase = true)) {
            HttpClient.ProxyType.SOCKS
        } else {
            HttpClient.ProxyType.HTTP
        },
        host = host,
        port = settings.proxyPort.value.trim().toIntOrNull() ?: 0,
        username = settings.proxyUsername.value,
        password = settings.proxyPassword.value,
        bypass = settings.proxyBypass.value
            .split('\n', ',')
            .map(String::trim)
            .filter(String::isNotEmpty),
    )
}
