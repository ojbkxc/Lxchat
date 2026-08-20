package com.lxseek.chat.api.util

/**
 * Accumulates the streamed `arguments` / `input_json` of one tool call defensively.
 *
 * A compliant endpoint sends each delta as an INCREMENT the client appends. Non-compliant relays
 * are known to instead resend the whole value accumulated so far (a snapshot) in every delta, or
 * to interleave empty placeholder deltas. Naive appending turns snapshots into duplicated garbage
 * (`{"a":1}{"a":1}`), while naive replacing lets an empty delta erase a complete argument list.
 *
 * This accumulator therefore:
 *  - ignores null/empty fragments, so a blank delta can never destroy accumulated content;
 *  - recognizes a growing snapshot (the fragment starts with everything accumulated so far) and
 *    keeps the longer value instead of concatenating;
 *  - ignores a stale or repeated snapshot (the accumulated value already starts with it).
 *
 * Snapshot detection is deliberately scoped to tool arguments, which are structured JSON where the
 * relay bug is documented. It is NOT applied to answer text, where a legitimately repeated short
 * phrase would otherwise be dropped.
 */
class ToolArgumentAccumulator(initial: String = "") {
    private val builder = StringBuilder(initial)

    val isEmpty: Boolean get() = builder.isEmpty()

    fun append(fragment: String?) {
        if (fragment.isNullOrEmpty()) return
        val current = builder.toString()
        if (current.isEmpty()) {
            builder.append(fragment)
            return
        }
        // A single character is ambiguous (a lone `{` is a plausible increment), so snapshot
        // detection requires enough overlap to actually mean something.
        if (current.length >= MIN_SNAPSHOT_OVERLAP) {
            if (fragment == current) return
            if (fragment.length > current.length && fragment.startsWith(current)) {
                builder.setLength(0)
                builder.append(fragment)
                return
            }
            if (current.startsWith(fragment)) return
        }
        builder.append(fragment)
    }

    override fun toString(): String = builder.toString()

    private companion object {
        const val MIN_SNAPSHOT_OVERLAP = 2
    }
}
