package com.lxseek.chat.viewmodel

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.data.local.MessageEntity
import com.lxseek.chat.data.repository.ConversationRepository
import com.lxseek.chat.data.repository.SettingsRepository
import com.lxseek.chat.model.ChatMessage
import com.lxseek.chat.model.MessageStatus
import com.lxseek.chat.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Complete immutable input for launching one already-bound generation execution. */
internal data class BoundRunGenerationRequest(
    val conversationId: String,
    val modelMessageId: String,
    val startTime: Long,
    val isRegenerate: Boolean,
    val replaceMessageId: String?,
    val providerName: String,
    val modelId: String,
    val activeKey: String,
    val uiToken: Long,
    val persistId: Long,
    val runId: String,
    val pass: Int,
    val callerTag: String,
)

/**
 * Executes the shared generation tail after the caller has durably created and bound the Run.
 *
 * This component owns no Run state, Job, scope, or continuation decision. Provider/tool outcomes
 * still return through the identified callbacks supplied by the conversation runtime host.
 */
internal class BoundRunGenerationLauncher(
    private val requestBuilder: GenerationRequestBuilder,
    private val settings: SettingsRepository,
    private val conversations: ConversationRepository,
    private val generationManagerProvider: () -> GenerationManager,
    private val compactController: ConversationCompactController,
    private val terminalSettlement: GenerationTerminalSettlementController,
    private val toUiMessage: (MessageEntity) -> ChatMessage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun launch(
        request: BoundRunGenerationRequest,
        state: ConversationGenerationState,
    ) {
        val requestTrace = HttpClient.RequestTrace(
            requestId = request.modelMessageId,
            origin = request.callerTag,
        )
        requestTrace.mark(
            "prepare_start",
            "acceptedDelayMs=${(clock() - request.startTime).coerceAtLeast(0L)}",
        )
        val resolved = requestBuilder.buildEffectiveSystemPrompt(
            request.conversationId,
            request.modelId,
        )
        val effectiveSettings = requestBuilder.buildEffectiveConversationSettings(
            request.conversationId,
        )
        // The already-loaded key is authoritative on the normal path. Only await DataStore during
        // the startup race where the eager StateFlow still exposes its empty default.
        val freshKey = request.activeKey.takeIf { it.isNotBlank() }
            ?: settings.awaitActiveKey(request.providerName)?.takeIf { it.isNotBlank() }
            .orEmpty()
        try {
            val (config, generationContext) = requestBuilder.buildGenerationPair(
                request.providerName,
                request.modelId,
                freshKey,
                resolved.systemPrompt,
                resolved.userPrepend,
                resolved.userPostpend,
                effectiveSettings,
                request.conversationId,
            )
            requestTrace.mark("request_config_ready")
            generationManagerProvider().generate(
                conversationId = request.conversationId,
                modelMessageId = request.modelMessageId,
                startTime = request.startTime,
                isRegenerate = request.isRegenerate,
                replaceMessageId = request.replaceMessageId,
                modelName = request.modelId,
                runId = request.runId,
                pass = request.pass,
                ownerToken = request.uiToken,
                config = config,
                ctx = generationContext,
                generationJob = currentCoroutineContext()[Job],
                callbacks = state.callbacksFor(request.uiToken, request.persistId).copy(
                    onToolRoundPersisted = {
                        compactController.automaticBeforeBoundary(
                            request.conversationId,
                            request.modelId,
                            effectiveSettings.contextWindow
                                ?: settings.maxContextWindow.value,
                            state,
                        )
                    },
                ),
                streamScope = state.streamScope,
                requestTrace = requestTrace,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(
                "LxChatVM",
                "Generation failed in ${request.callerTag} " +
                    "errorType=${e.javaClass.simpleName}",
            )
            // A pre-stream failure would otherwise strand the SENDING placeholder and overlay.
            runCatching {
                val existing = conversations
                    .getMessagesForConversationSnapshot(request.conversationId)
                    .find { it.id == request.modelMessageId }
                if (existing != null && existing.status == MessageStatus.SENDING) {
                    terminalSettlement.finalizeBoundFailure(
                        conversationId = request.conversationId,
                        runId = request.runId,
                        pass = request.pass,
                        uiToken = request.uiToken,
                        state = state,
                        failedMessage = toUiMessage(existing).copy(
                            text = "Error: ${e.localizedMessage ?: "Failed to build the request."}",
                            status = MessageStatus.ERROR,
                        ),
                        effectId = "request-finalize-${request.runId}-${request.pass}",
                    )
                }
            }
        }
    }
}
