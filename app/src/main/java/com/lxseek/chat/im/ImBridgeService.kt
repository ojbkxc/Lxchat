package com.lxseek.chat.im

import com.lxseek.chat.im.weixin.WeixinChannel
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
            // P1-1: 接通 WeixinChannel.onTokenStale，-14 后暂停该渠道轮询等重新绑定
            // （参考 weixin-ClawBot-API bot.py:1511-1531 受控重登录）
            if (channel is WeixinChannel) {
                channel.onTokenStale = { channel.markTokenStale() }
            }
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

    // ── 连接测试 ──────────────────────────────────────────────

    /**
     * 测试一个 IM 网关配置是否能正常连接并发送消息。
     *
     * 流程（参考 dsh-im connection-test.mjs）：
     *  1. 用 [ImChannelFactory] 为 [config] 临时创建一个 [MessageChannel]（不影响 activeChannels）；
     *  2. 校验渠道是否 [MessageChannel.isConfigured]；
     *  3. 调用 [MessageChannel.listConversations] 取第一个可用会话作为测试目标；
     *  4. 调用 [MessageChannel.sendMessage] 发送一条测试消息；
     *  5. 根据结果返回 [ConnectionTestResult.Success] 或 [ConnectionTestResult.Failure]。
     *
     * 不会抛异常：所有异常都被捕获并转为 [ConnectionTestResult.Failure]，
     * 这样 UI 层可以直接用返回值渲染结果，无需 try/catch。
     *
     * @param config 要测试的网关配置（不必已持久化，也不必已 enabled）。
     * @return       连接测试结果。
     */
    suspend fun testConnection(config: ImGatewayConfig): ConnectionTestResult {
        DebugLog.d("ImBridge", "testConnection: platform=${config.platform} channel=${config.effectiveChannelId}")

        // 1. 创建渠道；未知平台或工厂返回 null → 失败。
        val channel = try {
            ImChannelFactory.create(config)
        } catch (e: Exception) {
            DebugLog.e("ImBridge", "testConnection: factory create failed", e)
            return ConnectionTestResult.Failure("无法创建渠道：${e.message ?: e.javaClass.simpleName}")
        } ?: return ConnectionTestResult.Failure("未支持的平台：${config.platform}")

        try {
            // 2. 校验配置完整性。
            if (!channel.isConfigured) {
                DebugLog.w("ImBridge", "testConnection: channel not configured")
                return ConnectionTestResult.Failure("渠道未配置完整（缺少必填凭证或被禁用）。")
            }

            // 3. 找一个可用的测试目标会话。
            val conversations = try {
                channel.listConversations()
            } catch (e: Exception) {
                DebugLog.e("ImBridge", "testConnection: listConversations failed", e)
                return ConnectionTestResult.Failure("拉取会话列表失败：${e.message ?: e.javaClass.simpleName}")
            }
            if (conversations.isEmpty()) {
                DebugLog.w("ImBridge", "testConnection: no conversations available")
                return ConnectionTestResult.Failure(
                    "尚未收到可用于测试的私聊消息。请先在 IM 端向该机器人发一条消息，再重试连接测试。",
                )
            }
            val target = conversations.first()
            DebugLog.d("ImBridge", "testConnection: target conversation=${target.id} title=${target.title}")

            // 4. 发送测试消息。
            val testMessage = connectionTestMessage(channel.displayName)
            val sendResult = try {
                channel.sendMessage(target.id, testMessage)
            } catch (e: Exception) {
                DebugLog.e("ImBridge", "testConnection: sendMessage failed", e)
                return ConnectionTestResult.Failure("发送测试消息失败：${e.message ?: e.javaClass.simpleName}")
            }

            return when (sendResult) {
                is ImSendResult.Success -> {
                    DebugLog.i("ImBridge", "testConnection: success, messageId=${sendResult.messageId}")
                    ConnectionTestResult.Success(
                        "已向会话「${target.title.ifBlank { target.id }}」发送测试消息。",
                    )
                }
                is ImSendResult.Failure -> {
                    DebugLog.w("ImBridge", "testConnection: send rejected: ${sendResult.reason}")
                    ConnectionTestResult.Failure("网关拒绝发送：${sendResult.reason}")
                }
                ImSendResult.NotConfigured -> {
                    DebugLog.w("ImBridge", "testConnection: channel reports NotConfigured on send")
                    ConnectionTestResult.Failure("渠道在发送时报告未配置。")
                }
            }
        } finally {
            // 清理：临时 channel 调了 listConversations（内部 notifyStart + getUpdates），
            // 需配对 notifyStop 避免污染服务器状态，影响 active channel 的后续 getUpdates。
            if (channel is WeixinChannel) {
                try { channel.stop() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 构造连接测试消息文本（参考 dsh-im connection-test.mjs 的 connectionTestMessage）。
     *
     * @param botName 机器人显示名称；为空时用通用文案。
     */
    private fun connectionTestMessage(botName: String): String {
        val name = botName.ifBlank { "Lxchat 机器人" }
        return "✅ Lxchat 连接测试成功\n这条消息由「$name」的连接测试按钮发出。"
    }
}

/**
 * 连接测试结果。
 *
 * - [Success]：测试消息已成功发送到目标会话。
 * - [Failure]：测试失败，[reason] 描述失败原因（已包含足够上下文，可直接展示给用户）。
 *
 * 由 [ImBridgeService.testConnection] 返回，UI 层据此渲染结果。
 */
sealed interface ConnectionTestResult {
    /** 测试成功，[message] 是面向用户的成功描述。 */
    data class Success(val message: String) : ConnectionTestResult

    /** 测试失败，[reason] 是面向用户的失败原因。 */
    data class Failure(val reason: String) : ConnectionTestResult
}
