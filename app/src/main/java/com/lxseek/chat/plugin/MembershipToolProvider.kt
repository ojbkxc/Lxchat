package com.lxseek.chat.plugin

import com.lxseek.chat.api.ToolDefinition
import com.lxseek.chat.tool.RiskLevel
import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolPresentationMetadata
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.viewmodel.GenerationContext

/**
 * Decorator that marks all tools from the wrapped provider as requiring membership.
 *
 * Sinks the plugin-level [com.lxseek.chat.plugin.PluginManifest.requiresMembership] flag down
 * to the tool-level [ToolDescriptor.requiresMembership], so the disclosure layer
 * (filterByMembership) and execution layer (membershipCheck) in GenerationToolExecutor can
 * gate them uniformly without each provider having to know about membership semantics.
 */
class MembershipToolProvider(
    private val delegate: ToolProvider,
) : ToolProvider by delegate {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> =
        delegate.toolDescriptors(ctx).map { it.copy(requiresMembership = true) }

    // Explicitly delegate methods that have default implementations in ToolProvider to ensure
    // the `by` keyword forwards correctly for interface defaults.
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        delegate.definitions(ctx)

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        delegate.execute(name, arguments, ctx)

    override fun handles(name: String): Boolean = delegate.handles(name)

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        delegate.presentationMetadata(name)

    override fun riskLevel(name: String): RiskLevel = delegate.riskLevel(name)

    override fun requiresApprovalByDefault(name: String): Boolean =
        delegate.requiresApprovalByDefault(name)
}