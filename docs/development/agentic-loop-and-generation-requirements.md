# Agentic loop and generation requirements

Status: frozen implementation and audit baseline, 2026-08-08.

This document is the source of truth for the current agentic-loop, provider-streaming, send/queue/Stop, completion, and related chat UI work. It incorporates the mobile test requirements and supersedes implementation assumptions that conflict with it. Context Compact has its own companion specification in `context-compact-requirements.md`.

## 1. One send pipeline

A real user-message send has one behavioral pipeline regardless of whether it was triggered by the composer, queue drain, or Loop. Durable message creation, selected-path publication, bubble entrance, generation setup, errors, and completion all use that pipeline rather than source-specific imitations.

The only source-specific policies are explicit:

- A normal composer send and queue drain request the normal animated absolute-bottom scroll.
- A Loop send requests that same scroll only if the conversation was attached to the bottom when the automatic send fired. It must not steal the user's reading position.
- A delayed guidance send does not emit delayed haptics; the user's original click already acknowledged the action.
- Other real sends, including Loop and queue drain, use normal send-accepted haptics.

A newly created empty conversation does not move the drawer. Its first durably committed user message moves the drawer list to index zero exactly once. Failed sends, later sends, branch changes, Compact, and an undrained guidance draft do not trigger it. Any event is conversation-scoped and must not be applied stale after navigation.

## 2. Loop is only a timer and sender

Loop is a timer that invokes the normal send pipeline. It does not own, replace, or specialize generation.

- Stopping Loop stops only the timer. It never stops or rewrites the current generation.
- The Loop card disappears immediately when its timer becomes inactive, even if the generation it started is still running.
- During a Loop-triggered generation, the composer send button shows the normal generation Stop state.
- Input submitted during generation follows the delayed-guidance rules below.
- A Loop bridge must never wait for a busy generation slot while holding an automation/conversation lease, queue a hidden substitute, report a previous assistant message as the current result, or fall back into a lock inversion. Slot-busy is an explicit failed/deferred cycle outcome.

## 3. Stop, delayed guidance, and queue boundaries

Stop has one meaning: stop the current generation. It has no special queue-clearing behavior and must not replace assistant text with `execution stopped`.

Guidance entered while generation/tool execution is active is not yet a message:

1. The click accepts text and attachments into ordered in-memory pending guidance.
2. Before the next boundary, it is absent from Room, the message graph, selected path, render store, and `LazyColumn`.
3. The drain boundary is after the next tool call and all results are durably committed. If no next tool call occurs, generation finalization is the boundary. Stop finalization is also a generation-final boundary.
4. At the boundary, all pending guidance is sent in click order through the normal real-send pipeline. Each item remains a distinct user bubble. Publication, entrance animation, and absolute-bottom scroll happen once, not once early and once later.
5. Delayed drain is silent for haptics.

The boundary drain must serialize with the conversation lock, generation slot, provider stream, tool-round commit, and Compact. Stop must settle its coroutine and durable Run state before migration/drain. A drained batch must never be appended to an already stopped Run. Pending attachment ownership must be explicit; cancellation, ViewModel destruction, and process death must not leave orphan files or partially durable messages.

## 4. Request-history validity

Every provider request is validated before network I/O, and request construction—not validation weakening—must guarantee a legal generation boundary.

- A normal generation request ends in real user input or a complete tool-result input accepted by that provider.
- It must not end in an assistant placeholder, compact marker that was mapped as assistant output, incomplete tool call, unmatched tool result, or stale model output.
- Consecutive-role canonicalization and context trimming preserve the latest real input and complete tool protocol rounds.
- Compact summary plus its verbatim suffix is a context boundary, not a new assistant turn appended after the latest user/tool input.
- OpenAI-compatible providers (including DeepSeek), Anthropic, Gemini, and Ollama must each receive their protocol-valid equivalent. A fix for one provider must be checked against all sibling builders and validators.
- Invalid histories fail once with a diagnostic error; they must not enter a retry/snackbar loop.

Regression baseline: a DeepSeek request currently failed before sending with `history does not end in user/tool input`. This shape must have an automated regression test at the shared/request-builder boundary.

## 5. Provider stream completion and retry policy

HTTP 200 is not proof of stream success. Provider paths track protocol terminal markers, finish/stop reason, stream errors, timeouts, produced output, and in-flight tool calls.

- Anthropic parses `delta.stop_reason`, handles stream-level error payloads, and requires a terminal marker/stop reason.
- OpenAI-compatible handles bare stream error payloads, `[DONE]` or finish reason, and incomplete tool metadata.
- Gemini has equivalent EOF, timeout, stream-error, finish-reason, and terminal validation rather than setting success merely on HTTP 200.
- `max_tokens`/`length` is a visible output-truncated result, not silent success.
- Empty/null relay deltas never erase previously accumulated non-empty output. Structured tool arguments tolerate incremental deltas and growth snapshots without duplication.
- A nameless/incomplete tool call is an error, never silently discarded.

Transient failures use one policy across providers: initial request plus at most five retries, waiting `[5s, 5s, 5s, 30s, 30s]`. Request timeout, retryable transport/open-stream failure, eligible incomplete stream before visible output, and upstream outcome/message containing `failed to generate` are covered.

Retry UI invariants:

- Intermediate retryable failures do not emit terminal generation errors or snackbars.
- `failed to generate` does not recursively create retries at both provider and generation layers.
- Only one owner runs the retry sequence.
- Success after a retry emits no stale error.
- Exhaustion emits exactly one terminal error/snackbar and then releases/settles generation state, locks, jobs, Stop state, queued/guidance boundaries, and composer controls.
- Cancellation/Stop cancels retry delay promptly and does not surface a terminal failure snackbar.
- A request-format validation failure is not retryable.

Regression baseline: repeated `failed to generate` snackbars and a stuck UI were observed. Tests must cover intermediate retries, eventual success, exhaustion, cancellation during backoff, single terminal reporting, and state release.

## 6. Tool-call integrity and text recovery

Tool calls and results remain paired and complete in persisted history and provider replay. Cross-provider replay must not downgrade healthy tool rounds merely because opaque metadata belongs to another provider.

If damaged history must be represented as text, it uses clearly non-executable prose rather than syntax that the model can imitate as a tool invocation. OpenAI-compatible and Anthropic streams defensively recover supported JSON/XML text-form tool calls into structured `ToolCallRequest` objects. Recovered invocation syntax is never also published as ordinary assistant text.

## 7. Completion notification

A completion notification means the entire current generation Run has ended. A provider pass returning, a guidance click being accepted, a tool boundary, or queue drain beginning is not completion.

Notify at most once, only after all tool continuations and pending queued/guidance sends for the Run have been consumed and no subsequent pass remains. Stop/cancellation/error follow the existing product notification policy but cannot produce duplicate “completed” events.

## 8. Shell job tools

Foreground shell timeout may promote execution to a durable background job without killing it. `wait_for_job` is the default blocking tool; `get_shell_job` remains a bounded status peek. The advertised single-wait ceiling must match the actual tool execution ceiling (currently 295 seconds), and longer waits require another call.

Output truncation/scan warnings are not fatal command failures and preserve a successful exit code. Fatal process/transport errors remain distinguishable. Background-job persistence must not race terminal-state publication and orphan recovery.

## 9. Provider rename

Renaming a custom provider is a partial name-only update. Saving closes only the dialog, remains on the provider detail page, and never rewrites or clears API key or any unrelated provider field.

## 10. Chat UI requirements and explicit padding rollback

- Code-block copy controls remain available after streaming finishes; streaming and terminal rendering use behaviorally equivalent code-block support.
- Appearance may select thinking-segment presentation: `Card` (default for new and upgraded installs) or `Bottom sheet`.
- `Card` retains the existing inline behavior. Auto-expand is shown and active only in Card mode.
- `Bottom sheet` uses one sheet with an internally bounded lazy list. Segment detail replaces the list page within the same sheet, Back first returns to the list, and list scroll/message context survives page changes without stacked sheets.
- The current thinking-card-specific follow-bottom padding changes are incorrect and must be completely reverted. Follow/latest bottom padding must not branch on whether the last rendered element is a thinking card. This explicit rollback supersedes the earlier proposal to use smaller padding for a terminal thinking card.

## 11. Context, concurrency, and lifecycle invariants

Provider streaming, tool execution/commit, pending-guidance drain, queue drain, Compact graph mutation, and another Compact do not concurrently mutate the same selected branch. Slot and lock acquisition order must not permit a cycle. Every success, error, Stop, cancellation, validation failure, retry exhaustion, and ViewModel teardown path releases its owned slot/job/callback and settles observable UI state.

UI callbacks attached to app-lifetime conversation state are detached when their ViewModel is cleared. Conversation-scoped events verify their target before applying UI effects.

## 12. Required audit and evidence

The complete uncommitted implementation batch must be reviewed, not only files implicated by the reported regressions. Review includes:

- all changed and added production files and tests;
- OpenAI-compatible/DeepSeek, Anthropic, Gemini, and local/Ollama request construction;
- success, retry, error, cancellation, Stop, queue/guidance, tool continuation, Compact, Loop, process/lifecycle, notification, and UI transition paths;
- database/graph invariants, portable settings, migration/upgrade defaults, import/export, and attachment ownership;
- complete diff hygiene and accidental encoding/whitespace damage.

Evidence required before completion:

1. Focused regression tests for each corrected root cause.
2. Full unit-test suite.
3. Release Kotlin compile during iteration.
4. Project final build gate (`.\build.ps1`).
5. Real-device verification for interaction/state behavior when authorized; if not performed, it remains an explicit residual risk rather than being inferred from build success.
