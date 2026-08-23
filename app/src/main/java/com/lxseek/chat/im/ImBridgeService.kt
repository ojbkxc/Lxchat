package com.lxseek.chat.im

import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Owns the active [MessageChannel] instances for IM across the process. It watches the
 * persisted [ImMultiGatewayConfig] flow, rebuilds channels whenever the config changes via
 * [ImChannelFactory], and hands them to consumers:
 *  - [currentChannel] / [channelFor] for tool providers and proactive messaging,
 *  - [channels] for the receiver loop.
 *
 * The bridge holds no long-lived connection itself: polling channels only activate per
 * call, and push channels open their connection inside [ImPollingReceiver]'s listening
 * scope, not here.
 *
 * The channel map is keyed by [ImGatewayConfig.effectiveChannelId] so multiple bots on the
 * same platform (e.g. two Telegram bots) coexist without colliding.
 *
 * Backward compatibility: the legacy single-config flow ([legacyConfig]) is combined with
 * [multiConfig] so existing settings pages that only ever saved a single [ImGatewayConfig]
 * keep working. When the multi-config is non-empty it takes precedence; otherwise the
 * legacy single config is promoted via [ImMultiGatewayConfig.fromSingle].
 */
class ImBridgeService(
    private val multiConfig: Flow<ImMultiGatewayConfig>,
    private val legacyConfig: Flow<ImGatewayConfig>,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var activeChannels: Map<String, MessageChannel> = emptyMap()

    init {
        scope.launch {
            // Multi-config is the source of truth; legacy single-config fills in when it is empty
            // so pre-multi-bot setups keep their working channel without any migration step.
            multiConfig.combine(legacyConfig) { multi, legacy ->
                if (multi.all.isEmpty()) ImMultiGatewayConfig.fromSingle(legacy) else multi
            }.collect { cfg ->
                activeChannels = buildChannels(cfg)
                DebugLog.d("ImBridge", "channels updated: ${activeChannels.size} (keys=${activeChannels.keys})")
            }
        }
    }

    /**
     * Rebuild the channel map from a resolved multi-config. Each bot is dispatched through
     * [ImChannelFactory]; entries are skipped when the factory returns null (unimplemented
     * platform) or the resulting channel reports [MessageChannel.isConfigured] false.
     */
    private fun buildChannels(cfg: ImMultiGatewayConfig): Map<String, MessageChannel> {
        val result = LinkedHashMap<String, MessageChannel>()
        for (config in cfg.all) {
            if (!config.enabled) continue
            val channel = ImChannelFactory.create(config) ?: continue
            if (!channel.isConfigured) continue
            result[config.effectiveChannelId] = channel
        }
        return result
    }

    /** All currently active channels, keyed by [ImGatewayConfig.effectiveChannelId]. */
    fun channels(): Map<String, MessageChannel> = activeChannels

    /** The primary (first) active channel, or null when IM is disabled or unconfigured. */
    fun currentChannel(): MessageChannel? = activeChannels.values.firstOrNull()

    /** Look up a specific channel by its effective channel id. */
    fun channelFor(channelId: String): MessageChannel? = activeChannels[channelId]
}
