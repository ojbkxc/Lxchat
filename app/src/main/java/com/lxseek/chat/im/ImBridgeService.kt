package com.lxseek.chat.im

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns the active [MessageChannel] for IM across the process. It watches the latest
 * [ImGatewayConfig] flow, rebuilds a [GatewayChannel] whenever the config changes, and hands
 * the channel to [com.lxseek.chat.tool.ImToolProvider] via [currentChannel]. The bridge itself
 * holds no long-lived connection: the [GatewayChannel] only activates per tool call, so the app
 * never keeps an IM socket open in the background.
 */
class ImBridgeService(
    config: Flow<ImGatewayConfig>,
    scope: CoroutineScope,
) {
    @Volatile
    private var activeChannel: MessageChannel? = null

    init {
        scope.launch {
            config.collect { cfg ->
                activeChannel = if (cfg.isConfigured) GatewayChannel(cfg) else null
                DebugLog.d("ImBridge", "channel updated: ${cfg.isConfigured} (${cfg.platform})")
            }
        }
    }

    /** The currently active [MessageChannel], or null when IM is disabled or unconfigured. */
    fun currentChannel(): MessageChannel? = activeChannel
}