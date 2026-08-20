# Context compact requirements

## Settings

Context owns the default estimated-token budget, rollout visualization, automatic compact switch, compact model, editable compact prompt, and a configurable count of recent logical messages to preserve verbatim during compact. An unset compact model inherits the active conversation model. These settings participate in portable settings backup and restore. Legacy message-window values are migrated to useful token budgets rather than being interpreted literally as a handful of tokens.

The budget, automatic threshold, provider context truncation, rollout visualization, and composer indicator all use one deterministic cross-provider token-cost estimator. Exact tokenization remains model-specific, so the UI labels this value as an estimate. The estimator accounts for multilingual text, message framing, images, tool names/arguments/results, and preserves complete tool protocol rounds. Stored provider output usage is not reused as input context cost because it is incomplete for historical input, tools, images, system prompts, and cross-provider replay.

A logical message is counted after the same canonical role merge used by provider context construction. Consecutive user messages count as one logical message, and consecutive assistant messages count as one logical message. Tool calls and tool results consume no logical-message slots and remain atomically attached to their surrounding assistant continuation. Neither context-window truncation nor compact splitting may divide a tool protocol round.

## Boundaries and lifecycle

Compact is non-destructive. It persists a visible compact entity in the conversation graph while preserving original messages. The nearest compact entity on the selected branch is the sole API context boundary. Deleting it restores the preceding compact boundary naturally and must not delete original conversation content.

A compact eligibility check runs only at two continuation boundaries: after a user message is durably accepted, before its provider generation starts; and after a tool call finishes, before the next provider pass starts. A normal model response ending does not trigger a compact check.

If either boundary triggers compaction, the pending generation continuation must wait for compact to finish and then start automatically. The user must not need to press Send again, and a tool loop must resume its next model pass automatically against the newly compacted context. The check must not race an active provider stream, tool execution, queued intervention, or another compact. If compaction starts, the composer draft remains intact, sending waits, and an active Loop is stopped before the compact request.

For compact construction, LxChat resolves the current selected branch upward and canonicalizes it with exactly the same logical-message and tool-round rules used for provider context. It removes the configured last N logical messages from the summary input. Only the older prefix is sent to the compact model. After summary generation succeeds, LxChat constructs the new effective context as the compact summary followed by the untouched N-message suffix. The suffix is not copied into the summary text, rewritten, or regenerated. It stays attached after the compact boundary in its original order and with complete tool rounds preserved. The resulting provider context is therefore `compact summary + verbatim recent suffix`.

The compact boundary must be anchored immediately before the preserved suffix, not after the latest message. Branch selection and graph persistence must retain the suffix as descendants of that boundary so future sends, branch changes, and deletion of the compact entity keep deterministic ancestry. If the older prefix is empty, compact does not run.

## Manual compact from chat

The three-dot dropdown menu inside the `ChatBottomBar` capsule contains a `Compact` item. Selecting it opens a confirmation dialog instead of starting immediately. The dialog lets the user choose the compact parameters for this invocation, including the compact model, compact prompt, and number of recent logical messages to preserve verbatim. Its initial values come from the saved Context settings, but invocation-specific edits do not silently overwrite those defaults.

Confirming the dialog runs compact on the current selected branch with those parameters even when automatic compact is disabled and without requiring the automatic threshold to be reached. Manual compact uses the same path resolution, consecutive-role merging, zero-slot tool handling, atomic tool-round preservation, summary-prefix and verbatim-suffix construction, persistence model, and concurrency guard as automatic compact. It is disabled while generation, tool execution, or another compact makes the branch unsafe to mutate. The dialog remains open and shows an actionable error when parameters are invalid or the selected model is unavailable. During execution, the normal compacting state is visible and duplicate confirmation is blocked.

A successful manual compact does not itself start a new generation because no pending user or tool continuation exists. It closes the dialog and refreshes the displayed selected path. Automatic compact at a user-message or tool-call boundary still resumes the already pending generation automatically after success.

## Composer context indicator

The composer places a clickable circular context progress indicator immediately to the right of the model-selection capsule. It has normal ripple feedback and uses the same anchor and popup behavior as existing dropdown controls.

The indicator visualizes current estimated context occupancy. Its popup is intentionally small and quiet: one compact usage graph, estimated current tokens, the configured token budget, the current logical-message count, and compact-boundary status. It must avoid detailed provider billing/output-token accounting or unrelated controls. Colors, radius, elevation, typography, spacing, and dismissal behavior follow the existing composer dropdown language.

The indicator updates when messages, branch selection, streaming state, compact boundaries, or the configured token budget change. Its primary percentage is estimated provider-visible conversation tokens divided by that budget. The recent-N Compact suffix remains logical-message based and is not converted to a token quota.
