package com.lxseek.chat.data

import com.lxseek.chat.util.Constants
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = CustomEndpointProtocolSerializer::class)
enum class CustomEndpointProtocol(val wireValue: String) {
    OPENAI("openai"),
    GOOGLE("google"),
    ANTHROPIC("anthropic"),
    UNKNOWN("unknown");

    companion object {
        val selectable: List<CustomEndpointProtocol> = listOf(OPENAI, GOOGLE, ANTHROPIC)

        fun fromWireValue(value: String): CustomEndpointProtocol = when (value.trim().lowercase()) {
            "openai" -> OPENAI
            "google", "gemini" -> GOOGLE
            "anthropic", "claude" -> ANTHROPIC
            else -> UNKNOWN
        }
    }
}

private object CustomEndpointProtocolSerializer : KSerializer<CustomEndpointProtocol> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CustomEndpointProtocol", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): CustomEndpointProtocol =
        CustomEndpointProtocol.fromWireValue(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: CustomEndpointProtocol) {
        encoder.encodeString(value.wireValue)
    }
}

@Serializable
data class CustomProviderConfig(
    val name: String,
    // Configs written before protocol selection existed were OpenAI-compatible.
    val protocol: CustomEndpointProtocol = CustomEndpointProtocol.OPENAI,
)

fun isOpenAiProtocolProvider(
    providerName: String,
    customProviders: List<CustomProviderConfig>,
): Boolean =
    providerName == Constants.PROVIDER_OPENAI ||
        customProviders.any {
            it.name == providerName && it.protocol == CustomEndpointProtocol.OPENAI
        }

/**
 * Derived endpoint discovered during model sync.
 *
 * [configuredBaseUrl] remains the user's input. The effective URL is only reusable while
 * both that input and the selected protocol still match, so changing either cannot leak a
 * stale `/v1` (or any other protocol-specific path) into subsequent requests.
 */
@Serializable
data class CustomEndpointResolution(
    val protocol: CustomEndpointProtocol,
    val configuredBaseUrl: String,
    val effectiveBaseUrl: String,
)
