# Conversation Runtime Refactor Baseline

This document freezes the live architecture and migration boundaries for the incremental
conversation-runtime refactor. It describes the protected product baseline at commit `e650af6`.
It is not a claim that the target single-writer runtime already exists.

The frozen product requirements remain authoritative:

- `agentic-loop-and-generation-requirements.md`
- `context-compact-requirements.md`

## 1. Baseline evidence

- Base release commit: `d05fe4c` (`v2.0.0`).
- Protected product baseline: `e650af6` on local branch
  `codex/incremental-runtime-refactor-20260808`.
- Room schema: version 22, six entities: conversations, runs, messages, embeddings,
  tasks, and loops.
- JVM tests: 697 F-Droid + 697 Play, with zero failures, errors, or skipped tests.
- Required `build.ps1`: passed.
- F-Droid release APK: 56,756,416 bytes, SHA-256
  `AD885301D808FB264776868E23188336129D7BA766F101CB6A50319CE58E7A6B`.
- APK contract: package `com.lxseek.chat`, version `2.0.0` (30), minSdk 24,
  targetSdk 36, v2 signature verified.
- No device, process-death, lifecycle, real-network, or background-execution validation is
  included in this evidence.

An exact external recovery snapshot was verified before the protection commit. Local harness,
signing, generated, and private files are intentionally outside the product commit.

## 2. Current lifecycle

```text
startup
  -> ConversationRepository.ensureRunRecovery
  -> Room closes orphaned ACTIVE/STOPPING Runs as STOPPED/PROCESS_RECOVERED
  -> generation and automation scheduling may start

foreground Send
  -> Controller prepares payload
  -> queue mutex + mailbox decide memory guidance vs direct accepted-input effect
  -> conversation-owned Job
  -> per-conversation execution lease
  -> Room creates ACTIVE Run + USER + MODEL placeholder + selections
  -> optional automatic Compact
  -> GenerationManager owns provider/tool continuation until the Run is terminal
  -> slot release and queued-guidance drain

queued guidance boundary
  -> origin Run checkpoint and terminal status commit atomically
  -> pending FIFO batch transfers to one explicit in-memory ownership lease
  -> normal mailbox Send claim with a fresh Run id
  -> Room atomically creates fresh Run + distinct USER rows + MODEL placeholder + selections
  -> successful commit transfers attachment ownership to Room; failed claim returns the exact batch

provider/tool continuation
  -> Provider validates request and owns retry/termination proof for one stream
  -> GenerationManager consumes StreamEvents and checkpoints the placeholder
  -> validated complete tool batch executes sequentially
  -> Room atomically appends request + every authoritative result
  -> next provider pass, or final message/Run transaction

Stop
  -> in-memory phase becomes STOPPING and cancellation handles are revoked
  -> coroutine-settled barrier
  -> durable-finalization barrier
  -> release only after both barriers
  -> queued guidance starts a fresh Run after the stopped Run is terminal

Task/Loop
  -> alarm/WorkManager occurrence fence
  -> Task: task reservation/lock -> automation execution gate -> conversation lease
  -> Loop: automation execution gate -> conversation lease -> revision/cycle claim
  -> foreground-open Controller bridge or headless direct-only mailbox Send
  -> shared accepted-input Run/USER/MODEL graph transaction
  -> schedule advances only if the occurrence identity is still current

Compact
  -> mailbox CompactRequested claims manual Idle or exact automatic active Run/pass
  -> identified RunCompact effect executes through the shared foreground/Task coordinator
  -> read selected graph and nearest Compact
  -> keep complete tool rounds in the verbatim suffix
  -> summarize prefix with an ephemeral final USER instruction
  -> re-read assumptions and insert a Compact boundary transaction with the effect Run id
  -> exact CompactCompleted either returns manual state to Idle or authorizes automatic continuation
  -> never delete original messages
```

## 3. Current state definitions

### 3.1 Durable Run status

| State | Active slot | Legal current transitions | Terminal reason |
| --- | --- | --- | --- |
| `ACTIVE` | 1 | tool continuation → `ACTIVE`; completed or guidance boundary → `COMPLETED`; Stop → `STOPPING`; provider failure → `FAILED`; recovery → `STOPPED` | none |
| `STOPPING` | 1 | durable Stop finalization → `STOPPED`; provider failure after Stop → `STOPPED`; recovery → `STOPPED` | none |
| `COMPLETED` | null | late events ignored | `MODEL_COMPLETED` |
| `STOPPED` | null | late events ignored | `USER_STOPPED` or `PROCESS_RECOVERED` |
| `FAILED` | null | late events ignored | provider/setup error reason |

The passive `RunLifecycle.reduce` model has been removed. Durable status remains represented by
`RunStatus`; the first authoritative process slice is the reducer described below. There is no
second test-only Run reducer with competing transition semantics.

### 3.2 In-process slot

Pure `ConversationCommand`, `RunState`, `RunEffect`, `Transition`, and
`ConversationRuntimeReducer` types now own `Idle`, `Recovering`, `Preparing`, `Active`, `Compacting`,
`Finalizing`, and `Stopping` transitions, ordinary foreground Send placement/input acceptance,
Provider pass request/result acceptance, Context Compact admission/result/continuation, and both
normal-finalization and Stop coroutine/persistence barriers.
`ConversationGenerationState` calls the reducer under its
existing per-conversation monitor and is the only assignment path for this process-slot state.
The former `SlotPhase`, `stopFinalizationPending`, and `stoppedCoroutineUnwound` authorities were
deleted. Legacy UI/persistence tokens, Job ownership, streams, and the monitor remain compatibility
protection until the mailbox migration.

### 3.3 Target state vocabulary

The target reducer must be able to distinguish at least:

```text
Idle, Recovering, Preparing, Compacting, Streaming, ExecutingTools,
Continuing, Stopping, Finalizing, Terminal
```

Names may change, but provider pass, tool batch, Compact, Stop barriers, and Run terminalization
must not collapse into Boolean combinations.

## 4. State and side-effect writers

| Current owner | Writes | Current fence | Migration consequence |
| --- | --- | --- | --- |
| `ConversationRuntimeReducer` through `ConversationGenerationState` | authoritative ordinary/fresh-guidance Send placement/input acceptance, slot, Provider-pass acceptance, normal/Stop barriers, tool-batch/commit/continuation, Compact admission/result, and startup recovery transitions | conversation + owner + run id + pass + effect id | Keep external effect execution separate from transition authority. |
| Per-conversation command mailbox | serial delivery of ordinary/fresh-guidance Send, Provider request/result, normal/Stop finalization, tool batch/result/commit, and Compact request/result commands | one bounded FIFO consumer per conversation | Startup recovery is the gated pre-runtime exception: the Room transaction feeds ordered snapshots through the same pure reducer contract before any mailbox can admit generation. |
| `ConversationGenerationState` adapter | executes mailbox-approved Job/stream cancellation and projects overlay/token/queue/Compact UI plus explicit guidance ownership leases | conversation + owner token + guidance lease id; Stop and Compact cutoffs are reducer-approved | Preserve only until each remaining path moves behind reducer effects. |
| `MessageGenerationController` | executes accepted-input/Compact/finalization effects, edit/regenerate graph, and lease-backed fresh-Run guidance drain | conversation + token + run/effect/lease id | Setup failure and post-commit Stop races return exact result identities; only runtime-disposal recovery retains a direct durable repair fallback. |
| `GenerationManager` | executes mailbox-authorized Provider/tool/finalization effects, stream/checkpoint, and notification | conversation + owner + run id + durable pass + per-request effect id | It no longer owns local Provider acceptance or terminal slot release; keep effect bodies external to the pure reducer. |
| `GenerationFinalizer` | executes the mailbox-emitted durable Stop effect and returns `PersistenceSettled` to that mailbox | conversation + owner + run id + pass + effect id | Keep Room execution external; mailbox acceptance and two-barrier release are authoritative. |
| `TaskExecutionEngine` | executes mailbox-approved headless input/Compact effects and generation | conversation + owner + run id/pass/effect id | Send, Provider acceptance, finalization, and Compact contracts are shared; request-building and effect bodies remain bounded adapters. |
| `LoopManager` | occurrence claim/revision/cycle/schedule | revision + fire time + count | Preserve replay fencing; trigger normal Send contract. |
| `TaskManager`/Workers | reservation, execution conversation, occurrence retry/schedule | task + scheduled time + execution id | Preserve deterministic occurrence identity. |
| Providers + `ProviderPassRunner` | retry and semantic stream termination remain Provider-local; runner executes only a mailbox-emitted pass, normalizes it into a closed outcome, and validates completed tool metadata | conversation + owner + run id + durable pass + per-request effect id | Keep protocol validators Provider-local; only the mailbox accepts the closed outcome. |
| ToolProviders | external side effects and progress/result | validated batch effect identity | Progress is non-authoritative; only the complete batch result can request commit. |
| Room transactions | durable Run/message/selection/Compact/task/loop/recovery state | SQL preconditions vary; tool round requires ACTIVE slot + expected pass; recovery consumes ordered exact live-Run snapshots atomically | Remain durable source of truth; external transaction execution does not imply process-state authority. |

This inventory now has one process-state assignment path: every accepted transition is produced by
`ConversationRuntimeReducer`, and `ConversationGenerationState` is the only holder that applies it
during live execution. The execution coordinator serializes the broader generation lease; Send
(including fresh-Run guidance and headless automation), Stop, Provider/tool/finalization, and
Compact state enter the per-conversation mailbox. Startup recovery runs before admission, maps
ordered Room snapshots through the same reducer contract, and never reconstructs live state or a
coroutine. In-memory guidance leases and external/Room graph-effect bodies remain bounded effect
executors, not alternative Run-state writers.

## 5. Identity and stale-result policy

Every new asynchronous effect and result must carry:

```text
conversationId, runId, pass, effectId
```

The conversation runtime is the only component allowed to accept a result. It rejects:

- a different conversation or Run;
- an older or unexpected pass;
- an effect id that is not currently expected;
- a duplicate effect completion;
- a command that is illegal in the current state;
- any event after a terminal transition.

Existing UI/persistence tokens and Provider-local parser identity are not substitutes for this
contract. They may coexist only during a bounded adapter migration.

## 6. Resource acquisition order

The live orders that constrain migration are:

1. Direct foreground Send: queue mutex → mailbox `SendRequested` → pure slot claim; release mutex;
   Job → conversation lease → Room transaction → mailbox `InputPersisted`/failure command.
2. Guidance placement while a Send is preparing or active: queue mutex → mailbox
   `AcceptGuidance` decision → memory-only FIFO append; no Room/message-graph write occurs.
3. Guidance drain: origin Run terminal transaction → process-slot release → queue mutex → explicit
   FIFO ownership lease → mailbox `SendRequested`/`PersistAcceptedInput`; release mutex; Job →
   conversation lease → fresh-Run Room transaction → mailbox `InputPersisted`. Failure before Room
   ownership returns the exact lease batch to the front; disposal deletes only non-durable files.
4. Automation bridge: automation execution gate → automation conversation lease → Controller
   direct-only Send → join the
   exact Job. Busy must reject; waiting or queueing here can deadlock with a foreground slot owner
   waiting for the same lease.
5. Task execution: task reservation/task lock → automation execution gate → conversation lease →
   mailbox direct-only admission → shared accepted-input transaction → provider/tool work.
6. Loop execution: automation execution gate → conversation automation lease → short state
   mutex/Room occurrence claim → mailbox direct-only admission → generation. The held-guard engine
   entry never re-enters either non-reentrant guard.
7. Stop: mailbox `StopRequested` → token/overlay cutoff → stream/Job cancellation. Only the
   installed Job's completion hook can report coroutine settlement; durable Room finalization
   independently returns to the same mailbox. Neither result alone may release the slot, and
   accepted cutoff/result delivery is non-cancellable.
8. Exclusive import: close automation admission → cancel/quiesce Workers → wait for active
   executions → import transaction.
9. Tool execution is nested inside the conversation lease: mailbox `ToolBatchRequested` → external
   batch → mailbox `ToolBatchCompleted` → conditional Room transaction → mailbox
   `ToolRoundCommitted` → continuation authorization. Remote Shell jobs can outlive one bounded
   wait, so timeout is not synonymous with process termination.
10. Manual Compact: queue mutex checks there is no pending guidance → mailbox idle claim → release
    queue mutex → conversation lease → provider summary → Compact Room transaction → exact mailbox
    result. It never activates generation or overtakes a guidance lease. Automatic Compact runs
    inside the already-installed generation Job/lease: mailbox exact Run/pass claim → effect →
    result → continuation authorization. Stop may replace the automatic Compact state and cancel
    that Job; a late result cannot resume it. A normal Send waits only for Compact settlement and
    then re-enters the mailbox as an Idle Send or Active memory guidance; direct-only automation
    reports busy.

Target reducer transitions never suspend. The intended order is:

```text
mailbox command -> pure transition -> one effect -> external/Room work
-> identity-bearing result command -> same mailbox
```

Global gates must not call back into a conversation mailbox while holding an order-inverting lock.

## 7. Durable transaction boundaries

The live DAO already provides useful starting points:

- `createConversationRunWithMessages`
- `createRunWithMessages`
- provider/message checkpoint update
- `appendToolRoundToRun` (ACTIVE slot + expected-pass predicate; exact complete replay is idempotent)
- `finishGeneration`
- `finishStoppedGeneration`
- `insertContextCompactBeforeSuffix`
- `removeContextCompact`
- `deleteMessageSubtree`
- `recoverOrphanedRuns`

Each migrated transaction must document its precondition, durable postcondition, duplicate
behavior, stale-Run conditional update, failure atomicity, selection changes, and attachment
ownership. No Room schema rewrite is planned.

## 8. Graph and protocol invariants

1. Room is the durable source of truth; streaming is an overlay.
2. At most one non-terminal Run exists per conversation.
3. Messages belong to exactly one conversation and Run and have stable Run sequence order.
4. Message and Run selections must reference existing nodes on a valid ancestry.
5. Provider pass, tool round, Run, visible assistant aggregate, and overlay are different bounds.
6. A provider pass ending is not a Run ending.
7. Incomplete, unnamed, duplicate, unsafe, or malformed tool calls never execute.
8. A tool request and all authoritative results form one complete atomic protocol round.
9. Provider success requires semantic termination validation.
10. A continuation may end in a complete tool result; strict Compact requests end in an
    ephemeral USER instruction.
11. Stop preserves generated body and queued guidance.
12. Queue guidance stays memory-only until a legal durable boundary.
13. Every drained guidance batch enters a new child Run; it never appends to the origin or a
    terminal Run.
14. Compact never deletes original messages and never cuts a complete tool round.
15. The nearest Compact boundary wins; deleting it reveals the previous boundary naturally.
16. Overlay and Room projection never render the same assistant message twice.
17. Attachment files are removed only after every message/draft reference is gone.
18. Completion notification is idempotent per Run.
19. Delayed automatic title work cannot overwrite a manual title.
20. No private request content, key, host secret, or tool result enters trace/log fixtures.

## 9. Acceptance matrix

| Requirement | Protected baseline | Required migration proof |
| --- | --- | --- |
| Durable source of truth | Room v22 and recovery barrier | Real in-memory Room integration tests. |
| One live durable Run | unique active-slot index and Run invariants | Concurrent transaction/conditional-update tests. |
| One process writer | reducer is the sole transition authority and `ConversationGenerationState` is the only live holder; startup recovery is gated before live runtime admission | retain focused ordering tests and add lifecycle/device stress coverage. |
| Cross-conversation parallelism | coordinator supports it | Runtime tests with two conversations. |
| Stale/duplicate rejection | Provider, Stop, state-backed tool, Compact, normal-finalization, and recovery effects/results carry exact identity and have reducer rejection | add device/process-death stress coverage. |
| Stop two-barrier release | Stop and both settlement results use the mailbox; reducer owns both orders and exact release effect | add real Room failure/process-lifecycle coverage without adding a second state writer. |
| Tool atomicity | validated outcome → mailbox batch effect → complete result command → expected-pass Room transaction → commit result → continuation; partial/conflicting replay fails closed | add real Room failure/reorder integration tests when the Room test harness is introduced. |
| Queue FIFO and memory ownership | explicit lease; exact front requeue; normal fresh-Run Send identity; disposal/durable file-owner tests; obsolete durable/same-Run queue APIs removed | end-to-end real Room, Stop/error, process-death, and attachment-reference tests. |
| Compact graph safety | identified mailbox effect/result; manual/automatic serialization; exact automatic continuation; graph re-read and unit tests | real Room selected-ancestry, Stop-vs-transaction, and process-death tests. |
| Recovery | ordered ACTIVE/STOPPING snapshots become `Recover`/identified effect/result transitions inside one atomic Room transaction; broad orphan-update authority removed | real in-memory Room rollback/process-death tests remain unavailable in this repository. |
| Notification/title idempotence | partial application guards | explicit delayed/duplicate effect tests. |
| Privacy-safe trace | 256-entry reducer trace with digested conversation id and metadata-only fields | expose/merge trace through the final mailbox without adding content. |

The repository has no `androidTest` source tree. Existing repository tests mock `ChatDao`; real
Room integration coverage is a required addition, not an inferred property of the current green
JVM suite.

## 10. Migration and rollback sequence

Each row is an independent semantic commit and rollback boundary:

1. Pure runtime vocabulary, reducer tests, Stop identity envelope, and bounded redacted trace — implemented for the authoritative slot/Stop slice.
2. Ordinary foreground Send enters a real per-conversation mailbox — implemented for placement and the accepted-input Room result; Provider execution remains an adapter.
3. One Provider pass becomes an isolated runner and closed outcome — implemented; live events are
   UI/checkpoint input, while only an exact, validated `CompletedToolCalls` outcome can request tool
   execution.
4. Stop and both settlement barriers become mailbox commands — implemented; cutoff precedes
   cancellation, only actual Job completion reports coroutine settlement, both result orders are
   covered, stale/duplicate identities are rejected, and accepted delivery survives submitter
   cancellation.
5. Tool batch execution/commit/continuation becomes effects and result commands — implemented for
   state-backed foreground and production Task/Loop execution; the registry-less headless sentinel
   and permissive callback mode were removed with automation admission in step 7.
6. Queued guidance and attachment ownership move through the normal Send contract — implemented:
   guidance stays memory-only until a legal boundary, the origin Run closes, one explicit lease
   enters a fresh `SendRequested`/accepted-input transaction, distinct USER rows remain ordered,
   and failed/disposed ownership is deterministic. Same-Run/durable pending-queue authorities were
   deleted without changing Room v22.
7. Loop and Task reuse the same runtime contract — implemented for direct-only admission, exact
   accepted-input result identity, installed external-Job settlement, and the shared ordinary
   USER/MODEL/Run graph transaction. Busy is a typed no-input/no-Run result, foreground bridge
   fallback is forbidden, and Task/Loop use one gate → conversation-lease order. Headless request
   construction, Provider execution, and finalization remain bounded adapters for their later
   dedicated migrations rather than a second slot/Run-placement authority.
8. Manual/automatic Compact become serialized runtime effects — implemented: manual claims Idle
   without generation UI ownership and cannot overtake pending guidance; automatic retains the
   exact active Run/pass; Stop invalidates
   late results; foreground and Task paths share one effect coordinator and effect-supplied Compact
   Run id; ordinary Send waits only for Compact settlement and re-enters the mailbox, while
   direct-only automation reports busy.
9. Provider acceptance and normal Run finalization become runtime effects — implemented for the
   live generation path: every network pass requires `StartProviderPass`, every closed outcome must
   be accepted before consumption, and normal terminal Room work uses explicit coroutine/durable
   barriers. A bound Run cannot release merely because its Job ended; bounded Room failure remains
   occupied and permits explicit Stop recovery.
10. Recovery and Room domain transactions become deterministic/idempotent — implemented for
    startup recovery: ordered `RunEntity` snapshots produce exact `Recover` effects/results in one
    transaction, interrupted model/tool UI state becomes STOPPED, no coroutine is resumed, and the
    superseded broad orphan-id/update writers were removed. Late Run binding after Stop and bound
    setup failures also use mailbox-authorized terminal effects; direct durable repair remains only
    for runtime-disposal edges where no process writer survives.

Old guards are not removed merely because new types compile. They are removed only after the new
runtime owns that path, focused tests pass, the complete unit gate passes at major milestones, and
the diff has been re-reviewed. No push, deployment, publication, or Room compatibility rewrite is
part of this migration.
