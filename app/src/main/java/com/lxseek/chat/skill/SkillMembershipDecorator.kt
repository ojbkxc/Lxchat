package com.lxseek.chat.skill

import com.lxseek.chat.tool.ToolDescriptor
import com.lxseek.chat.tool.ToolProvider
import com.lxseek.chat.agent.GenerationContext

/**
 * Decorator that sinks the skill-level `requiresMembership` flag onto the
 * tool-level [ToolDescriptor.requiresMembership] field.
 *
 * This keeps the membership decision in one place (the [Skill] model) while still
 * letting the existing disclosure/execution gates in [GenerationToolExecutor]
 * (which only inspect [ToolDescriptor.requiresMembership]) enforce it without
 * knowing about skills. The decorator delegates every other [ToolProvider] method
 * to [delegate], so it is a transparent wrapper.
 *
 * [SkillToolProvider] already sets `requiresMembership` from the skill, so this
 * decorator is a defense-in-depth layer: it re-asserts the flag from the
 * authoritative [SkillHost] registry, which matters when a delegate is composed
 * or replaced and the upstream copy is not guaranteed.
 */
class SkillMembershipDecorator(
    private val delegate: SkillToolProvider,
) : ToolProvider by delegate {

    override fun toolDescriptors(ctx: GenerationContext): List<ToolDescriptor> {
        return delegate.toolDescriptors(ctx).map { desc ->
            val toolName = desc.definition.function.name
            val skill = delegate.skillFor(toolName)
            if (skill?.requiresMembership == true && !desc.requiresMembership) {
                desc.copy(requiresMembership = true)
            } else {
                desc
            }
        }
    }
}