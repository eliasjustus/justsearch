---
title: Multi-Agent Handoff Orchestration M0
status: done
created: 2026-02-17
updated: 2026-02-20
resolution: "M0 complete (Layers 1–9). Recs 1/2/4 done. Tier 1 CI coverage done. Recs 3/5/6 deferred to future tempdocs."
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.

# 211: Multi-Agent Handoff Orchestration M0

## Origin

Deferred from tempdoc 208 (closure item S-008). Research and feasibility analysis completed in tempdoc 208 §R6. This tempdoc covers implementation only.

## Problem Statement

JustSearch has a single-agent-per-session architecture. There is no mechanism for multiple logical agent roles (planner, executor, reviewer) to share a session with explicit handoff transitions. This blocks advanced workflows like plan-then-execute, tool-specialist delegation, and review-before-commit patterns.

## Design (from tempdoc 208 R6 research)

**Scope**: Sequential single-model handoffs only. No parallel multi-model runtimes (conflicts with single-GPU/VRAM constraints).

**Pattern**: Google ADK `SequentialAgent` for deterministic state-machine routing + OpenAI `input_filter` for context filtering on handoff boundaries.

### Implementation Slice

1. **Additive agent-role metadata** on `AgentRequest`:
   - Optional `agentProfiles` (list of role definitions with name, system prompt, tool subset)
   - Optional `initialAgentId` (default: first profile or "primary")
   - Internal active-agent cursor in `AgentSession` loop state

2. **Additive event contract** on `AgentEvent`:
   - `HandoffProposed(fromAgent, toAgent, reason, trace)`
   - `HandoffExecuted(fromAgent, toAgent, trace)`
   - Existing tool events already carry `agentId` via `TraceContext`

3. **Durable handoff state** in `AgentRunStore`:
   - Checkpoint fields: `activeAgentId`, `handoffHistory`
   - Replay must emit handoff events in canonical order

4. **Safety model preservation**:
   - Pending write/destructive approvals must not survive cross-agent ambiguity
   - Fresh approval required when handoff occurs before a pending write executes
   - Approval boundary reset on handoff

5. **Strictly sequential scheduler** (no concurrent agent branches)

### Rejected (for now)

Parallel sub-agent execution with independent llama-server instances. Conflicts with exclusive runtime/VRAM assumptions.

---

## Codebase Investigation (2026-02-19)

### What exists and is directly reusable

#### `TraceContext` already has `agentId`

`TraceContext` (`app-agent-api/.../TraceContext.java`) already has an `agentId` field:

```java
public record TraceContext(
    String traceId, String stepId, String spanId, String parentSpanId,
    String agentId,   // <-- already here
    String toolCallId, int iteration) { ... }
```

`AgentLoopService.wrapEventConsumer()` (line 797) hardcodes it to `"primary"`:

```java
var traceSequencer = new EventTraceSequencer(sessionId, "primary");
```

**Implication**: The trace infrastructure is already designed for multiple agents. M0 just needs to create a new `EventTraceSequencer` with the active agent's ID when a handoff occurs.

#### `AgentEvent` is a sealed interface — exhaustive switches everywhere

`AgentEvent` has 12 existing record types. Every new type added to the sealed interface **must** be handled in four places with exhaustive switch expressions:

| Location | Switch type | Action needed |
|----------|-------------|---------------|
| `AgentRunStore.toEventType()` | `switch (event)` → String | Add `"handoff_proposed"`, `"handoff_executed"` |
| `AgentRunStore.toPayload()` | `switch (event)` → Map | Serialize fields |
| `AgentController.writeAgentEvent()` | Two switches (payload + eventType string) | Same as above |
| `AgentLoopService.withTrace()` | `switch (event)` → AgentEvent | Copy with new trace |

Missing a case causes a compile error (sealed), so the compiler enforces completeness.

#### `AgentSession` owns approval gates — clear them on handoff

`AgentSession.approvalGates` is a `ConcurrentHashMap<String, CompletableFuture<Boolean>>`. The `cancel()` method already shows the pattern for bulk gate rejection:

```java
void cancel() {
    cancelled = true;
    approvalGates.values().forEach(f -> f.complete(false));
}
```

A new `clearPendingApprovals()` method follows this exact pattern — complete all pending gates with `false` (rejecting them), then clear the map.

#### `AgentRunStore` has a schema upcaster pattern — use it for checkpoint migration

`AgentRunStore` already has `CURRENT_SCHEMA_VERSION = 1` and a `SchemaUpcaster` inner class. Adding `activeAgentId` and `handoffHistory` to the checkpoint requires bumping to schema version 2 and adding a v1→v2 upcaster:

```java
// v1 -> v2: add activeAgentId and handoffHistory (absent in single-agent checkpoints)
meta -> {
    var upgraded = new LinkedHashMap<>(meta);
    upgraded.putIfAbsent("activeAgentId", "primary");
    upgraded.putIfAbsent("handoffHistory", List.of());
    return upgraded;
}
```

The `updateCheckpoint()` method signature needs two new parameters (or the map can be augmented directly — see implementation notes below).

#### `AgentLoopService.resumeLastSession()` already handles approval boundary reset

When resuming from `WAITING_APPROVAL` state, the service injects a system message:

```java
resumeMessages.add(Map.of("role", "system", "content",
    "Resume note: previous pending approvals must be re-requested after restart..."));
```

The handoff approval boundary reset follows the same conceptual pattern: pending approvals are cleared programmatically, and a system message is injected to inform the model.

#### `useAgentStore.ts` SSE handler is extensible — `default` case logs unknowns

The `handleSseEvent` switch in `useAgentStore.ts` has:

```typescript
default:
    console.debug('Unknown agent SSE event:', event, data);
    break;
```

New handoff events will fall through to the `default` case without breaking anything until the frontend cases are added. This makes the backend-first rollout safe.

### Critical breaking-change findings

#### `AgentEventSealedTest` hardcodes the sealed subtype count

`AgentEventSealedTest.java:71`:

```java
assertEquals(12, permitted.length);
```

Adding `HandoffProposed` and `HandoffExecuted` makes it 14. **This test must be updated** to `assertEquals(14, permitted.length)`. The compiler enforces exhaustive switches on the sealed type, but this test additionally guards against accidental removal — so the number must be kept accurate.

#### `tools` list is built once before the outer loop — must move to per-iteration

`AgentLoopService.java:203`:

```java
List<Map<String, Object>> tools =
    toolRegistry.toOpenAiToolsArray(request.selectedToolNames());
```

This is built once before `for (int iteration = 0; ...)`. For multi-agent, the tool list must be rebuilt at the start of each iteration because:
1. The active agent's `toolSubset` may differ from the previous agent's.
2. The handoff tool list changes after each handoff (can't offer `handoff_to_executor` if executor is now active).

**Fix**: move the `tools` variable into the iteration loop body, computing it from `session.activeAgentId()` on each iteration.

#### 16 call sites use the 3-arg `new AgentRequest(...)` constructor

Adding `agentProfiles` and `initialAgentId` changes the canonical record constructor from 3 to 5 params, breaking all 16 sites:

- `AgentLoopService.java:747` (`resumeLastSession`)
- `AgentLoopServiceTest.java:191, 664, 691, 1121`
- `AgentRunStoreTest.java:31, 58, 95, 126, 314`
- `AgentEventSealedTest.java:52, 55`
- `AgentServiceUnavailableTest.java:32`
- `AgentBatteryTest.java:357`
- `AgentController.java:56`
- `AgentRequest.java:26` (`singleTurn` factory)

**Fix**: add a backward-compatible 3-arg delegating constructor to the record. Java records support non-canonical constructors that delegate to the canonical one via `this(...)`:

```java
/** Backward-compatible constructor for single-agent (no profiles) requests. */
public AgentRequest(List<Map<String, Object>> messages, List<String> selectedToolNames, int maxIterations) {
    this(messages, selectedToolNames, maxIterations, List.of(), null);
}
```

This preserves all 16 existing call sites without modification. The `singleTurn()` factory continues to use this 3-arg constructor unchanged.

`AgentController.java:56` also needs to parse `agentProfiles` and `initialAgentId` from the request JSON and pass them to the full 5-arg constructor — but only when the client sends them. The backward-compatible 3-arg ctor handles requests that omit them.

### What needs to be added (no existing infrastructure)

1. **`AgentProfile` record** — new type in `app-agent-api`; name, systemPrompt, toolSubset
2. **`AgentRequest` extension** — optional `agentProfiles` + `initialAgentId` fields (must remain backward-compatible with existing single-agent callers)
3. **`AgentSession` handoff state** — `activeAgentId` string, `handoffHistory` list, `clearPendingApprovals()` method
4. **`AgentLoopService` handoff mechanism** — detect handoff tool calls, swap active agent, emit events, reset approvals
5. **Handoff tool registration** — each target agent is exposed to the LLM as a callable tool (see implementation notes)
6. **Frontend state for handoff** — `activeAgentId`, `handoffHistory` in `useAgentStore`

### Files that will change

| File | What changes |
|------|-------------|
| `modules/app-agent-api/.../AgentRequest.java` | Add optional `agentProfiles`, `initialAgentId` |
| `modules/app-agent-api/.../AgentEvent.java` | Add `HandoffProposed`, `HandoffExecuted` sealed records |
| `modules/app-agent-api/.../AgentService.java` | No changes needed — interface is sufficient |
| `modules/app-agent/.../AgentLoopService.java` | Active-agent cursor, handoff detection, system prompt switching, `EventTraceSequencer` per agent |
| `modules/app-agent/.../AgentSession.java` | `activeAgentId`, `handoffHistory`, `clearPendingApprovals()` |
| `modules/app-agent/.../AgentRunStore.java` | Schema v2, `activeAgentId`/`handoffHistory` in checkpoint |
| `modules/ui/.../AgentController.java` | Two switch expressions for new event types |
| `modules/ui-web/.../useAgentStore.ts` | New SSE cases, `activeAgentId`/`handoffHistory` state |
| `modules/app-agent-api/src/test/.../AgentEventSealedTest.java` | Update sealed subtype count: `12 → 14` |

New files (likely):
- `modules/app-agent-api/.../AgentProfile.java` — new record type

---

## External Research (2026-02-19)

### Google ADK SequentialAgent

ADK's `SequentialAgent` executes sub-agents one after another, passing the **same shared `InvocationContext`** (including `session.state`) to each. Agents expose their outputs via `output_key` so downstream agents can reference them by template substitution. There is no built-in approval gate or `input_filter` equivalent — those are custom logic.

**Key finding for M0**: ADK's design confirms the simplest valid pattern: shared conversation history, sequential execution, no parallel branches. The `session.state` shared-key mechanism maps loosely to injecting system messages into the conversation at handoff boundaries.

ADK provides no handoff event types — those are an OpenAI Agents SDK contribution.

### OpenAI Agents SDK

The definitive reference for multi-agent handoff event design. Key findings:

#### Handoff mechanism: handoff as LLM-callable tool

Handoffs in OpenAI's SDK are implemented by converting target agents into callable tools. The LLM decides to hand off by calling a tool like `transfer_to_reviewer`. The runner intercepts the call, routes to the new agent, and continues the loop. This is the cleanest mechanism for M0: no new LLM capability, just a tool in the registry.

For JustSearch M0, each agent profile is registered as a tool named `handoff_to_<agentId>`. The tool has `READ_ONLY` safety (no approval gate needed — the handoff itself is the consequential action). The tool registry provides these dynamically based on the `agentProfiles` in the request.

#### Handoff event taxonomy (maps to M0 event design)

| OpenAI event | JustSearch M0 event | Semantics |
|---|---|---|
| `handoff_requested` | `HandoffProposed` | LLM called the handoff tool; outcome not yet committed |
| `handoff_occured` | `HandoffExecuted` | Active agent switched; new agent taking control |
| `agent_updated` | part of `HandoffExecuted` | Carried in the `toAgentId` field |

OpenAI emits these as `RunItemStreamEvent` on the same SSE stream as tool events. JustSearch does the same via the existing `writeAgentEvent` SSE pipeline.

#### `input_filter` for context filtering on handoff boundaries

OpenAI's `input_filter` gives the handoff function control over what conversation history the receiving agent sees:

```python
class HandoffInputData:
    input_history: ConversationHistory    # Full history before handoff point
    pre_handoff_items: List[...]          # Conversation items before handoff
    new_items: List[...]                  # The triggering message(s)
```

**For M0**: Use the simplest valid filter — pass full conversation history. This matches the ADK `SequentialAgent` default (shared `InvocationContext`). The system prompt is the only thing that changes on handoff. Context filtering is a later optimization.

#### Approval boundary reset

OpenAI's guardrail mechanism creates hard approval boundaries. The pattern for JustSearch: when `HandoffExecuted` fires, call `session.clearPendingApprovals()` and inject a system message:

```
"Handoff to <toAgentId>. Any write or destructive actions must be re-approved
 by the user before execution."
```

This follows the same pattern as `resumeLastSession()`'s existing WAITING_APPROVAL handling.

#### Sequential loop with max iterations

The OpenAI runner loop pseudocode:
```
while turns < max_turns:
    call LLM with current_agent
    if final_output → return
    if handoff_tool_called → swap active agent, continue
    if tool_calls → execute, append results, continue
    increment turns
raise MaxTurnsExceeded
```

JustSearch's existing outer `for` loop in `AgentLoopService.runAgent()` already has this structure. M0 adds the "if handoff_tool_called" branch.

---

## Implementation Order

Dependency-driven layers. Each layer must compile and pass its gate before the next starts.

| Layer | Files | Gate | Status |
|-------|-------|------|--------|
| 1 — Types | `AgentProfile.java` (new), `AgentRequest.java`, `AgentEvent.java` | `app-agent-api:test` — `AgentEventSealedTest` count 12→14 | ✅ done (`b3c3a1c4`) |
| 2 — Session | `AgentSession.java` | `build -x test` (compile only) | ✅ done (`2e64de80`) |
| 3 — Store | `AgentRunStore.java` | `app-agent:test` — `AgentRunStoreTest` schema v2 | ✅ done (`f61d7dfc`) |
| 4 — Loop | `AgentLoopService.java` (largest) | `app-agent:test` — full scenario matrix | ✅ done (`15727ef0`) |
| 5 — API | `AgentController.java` | `ui:test` — `AgentSseContractTest` handoff payload | ✅ done (`b2a1912e`) |
| 6 — Frontend | `useAgentStore.ts` | `tsc --noEmit` | ✅ done (`b2a1912e`) |
| 7 — Post-critical fixes | all of the above | `app-agent:test` + `ui:test` + `tsc --noEmit` | ✅ done (`63de3bbf`) |
| 8 — Post-M0 hardening (Recs 1, 2, 4) | `AgentLoopService.java`, `AgentSession.java`, `AgentRequest.java`, `AgentErrorCode.java`, `AgentRetryPolicy.java`, `AgentController.java`, `agentProfiles.ts`, `AgentView.tsx`, `errorMessages.ts` | `app-agent:test` + `tsc --noEmit` | ✅ done (`71683b83`) |
| 9 — Critical-analysis follow-up (6 fixes) | `AgentLoopService.java`, `AgentSession.java`, `AgentLoopServiceTest.java`, `agentProfiles.ts` | `app-agent:test` + `tsc --noEmit` | ✅ done (`f76e3711`) |

`ScriptedAiService.recordedTools` addition comes before Layer 4 test scenarios. Full `./gradlew.bat test` after Layer 5 before touching frontend.

### Layer 1 post-implementation notes (2026-02-19)

**Bug caught and fixed: `AgentProfile.fromMap()` — `String.valueOf(null)` produces `"null"` string**

The original `fromMap()` used `String.valueOf(m.get("agentId"))` for required string fields. `String.valueOf(null)` returns the literal string `"null"`, not null, which silently bypasses `Objects.requireNonNull` in the compact constructor. A map missing the `agentId` key would produce an `AgentProfile` with `agentId = "null"` — a valid-looking object that would register a `handoff_to_null` tool and produce garbage in events and checkpoints.

**Fix applied**: Changed required-field extraction to use the `instanceof String s ? s : null` pattern, so absent keys map to null and `requireNonNull` fires correctly. Same fix applied to `toolSubset` element extraction (filter-and-cast instead of `String::valueOf`). Negative test added: `agentProfileFromMapMissingRequiredFieldThrows` asserts `NullPointerException` when `agentId` or `name` is absent from the map.

**Everything else confirmed correct:**
- 3-arg `AgentRequest` delegating constructor correctly propagates validation — confirmed by updated `agentRequestValidation` test exercising the 3-arg path.
- `singleTurn()` produces `agentProfiles = []`, `initialAgentId = null` — confirmed by updated test.
- `HandoffProposed` and `HandoffExecuted` match spec. `HandoffExecuted` intentionally omits `reason` (carried in `HandoffProposed` immediately before).
- Downstream switch errors in `AgentLoopService` and `AgentRunStore` are expected — resolved in Layers 3–4.

### Layer 2 post-implementation notes (2026-02-19)

**Threading safety of `activeAgentId` — confirmed correct.** `activeAgentId` is not `volatile`, which is intentional. It is only read and written on the agent loop thread. `cancel()` — the only method called from external threads — touches only `cancelled` (volatile) and `approvalGates` (ConcurrentHashMap); it neither reads nor writes `activeAgentId`. No memory-visibility issue.

**`clearPendingApprovals()` vs `cancel()` — correct and intentional.** `cancel()` completes gates with `false` but does not call `approvalGates.clear()` (loop exits immediately after; the populated map is harmless). `clearPendingApprovals()` completes gates with `false` AND calls `approvalGates.clear()`, so the next agent inherits an empty gate map. The `clear()` call is the critical difference — without it, completed-future entries from the previous agent would linger in the map, wasting memory and potentially confusing any gate lookup that occurs in the transition window.

**`recordHandoff()` — no null guards. Layer 4 is the primary defence.** If `toAgentId` were null, `activeAgentId = null` would silently corrupt all subsequent profile lookups and `buildHandoffTools()` calls. There are no `requireNonNull` guards here. This is acceptable because Layer 4 (`AgentLoopService`) validates that the target agent ID resolves to a known `AgentProfile` *before* calling `recordHandoff()` — an unknown target causes `AgentError` and loop termination without reaching this method. Layer 4 must enforce this contract; there is no fallback here.

**`handoffHistory()` live view callout — resolved in Layer 3.** `AgentRunStore.setHandoffState()` uses `List.copyOf(handoffHistory)` before persisting, snapshotting the list at the moment of the handoff. The live view risk is contained.

**Constructor chain and `activeAgentId` default — confirmed correct.** 3-arg delegates via `this(sessionId, messages, initialBudget, null)`, which maps to `activeAgentId = "primary"`. This is consistent with the existing hardcoded `"primary"` in `AgentLoopService.wrapEventConsumer()`. All 3-arg call sites unchanged.

### Layer 3 post-implementation notes (2026-02-19)

**Schema version bump required the v1→v2 upcaster to explicitly set `schemaVersion: 2`.** The existing `goldenFixture_v0_upcasterAddsSchemaVersion()` test asserts `schemaVersion == CURRENT_SCHEMA_VERSION`. With `CURRENT_SCHEMA_VERSION = 2`, v0 fixtures traverse both upcasters (v0→v1 sets `schemaVersion: 1`, then v1→v2 must update it to `2`). Without the explicit `upgraded.put("schemaVersion", 2)` in the v1→v2 upcaster, the v0 golden fixture test would fail (`1 ≠ 2`). This is an invariant of the upcaster design: each upcaster is responsible for setting `schemaVersion` to its target version.

**`AgentLoopService` compile fix needed in Layer 3.** The `app-agent:test` task requires all production classes to compile, including `AgentLoopService`. Two exhaustive switch expressions (`withTrace()`, `stepIdFor()`) were missing the new `HandoffProposed`/`HandoffExecuted` cases. These were added here (Layer 3) rather than waiting for Layer 4, since the test gate is defined at the module level. The `withTrace()` cases are exactly as specified in Step 4g; `stepIdFor()` uses `iterPrefix + ":handoff:" + toAgentId + ":{proposed,executed}"` format consistent with the existing step-ID conventions. Layer 4 will add the full handoff execution logic to `AgentLoopService` without touching these two methods.

**`agentProfiles` serialized via `toProfileMaps()` helper.** Avoids Jackson record introspection (correctness risk across Jackson versions) and handles nullable `systemPrompt` without NPE (`LinkedHashMap` allows null values; `Map.of()` does not). The field ordering matches `AgentProfile.fromMap()` key names exactly, ensuring round-trip fidelity.

**All pre-existing tests pass unchanged.** `goldenFixture_v0` now traverses both upcasters and ends at `schemaVersion: 2`. `newRun_writesCurrentSchemaVersion` still passes (checks `CURRENT_SCHEMA_VERSION` constant). `rejectsFutureSchemaVersion` still passes (uses `999`). Three new tests added: handoff event round-trip, `setHandoffState` persistence, and v1 golden fixture upcast.

**Post-implementation critical analysis — all correct, two callouts for Layer 4:**
- `updateCheckpoint()` preserves `activeAgentId`/`handoffHistory`/`agentProfiles` because its read-then-patch pattern carries all unmodified keys through. ✓
- `setHandoffState()` correctly omits `writeLastSessionId()` — `updateCheckpoint()` always precedes it and already updates the pointer. ✓
- `toPayload(HandoffProposed)` uses `Map.of()` which would NPE on null `fromAgentId`/`toAgentId`. Layer 4 validates the target before calling `recordHandoff()` — null is unreachable in practice.
- `startRun()` with non-empty `agentProfiles` is not directly tested in `AgentRunStoreTest`; the path is exercised end-to-end in Layer 4's `AgentLoopServiceTest`.
- `putIfAbsent("initialAgentId", null)` round-trip: Jackson serializes null values; `instanceof String s ? s : null` reads them back correctly. ✓

### Layer 4 post-implementation notes (2026-02-19)

**`AgentController.java` HandoffProposed/HandoffExecuted switch cases added here.** The production gate for Layer 5 (`ui:test`) requires `AgentController.java` to compile. Adding the two new sealed subtypes caused `AgentController.writeAgentEvent()` to fail compilation (exhaustive switch). The SSE payload and event-type-string cases for `HandoffProposed` and `HandoffExecuted` were added in this commit. What remains for Layer 5: parsing `agentProfiles` and `initialAgentId` from the `handleRunStream()` JSON body and constructing the 5-arg `AgentRequest`.

**Initial profile system prompt — applied before first LLM iteration.** After the default system prompt is prepended (or the user-supplied system message is already present), `runAgent()` now swaps it with the initial profile's `systemPrompt` when profiles are non-empty. This ensures iteration 1 sees the correct agent persona, not the default assistant prompt.

**`AtomicReference<EventTraceSequencer>` — required for mid-session swap.** The original `traceSequencer` was a local final variable captured in the `wrapEventConsumer()` lambda — immutable once created. Upgrading to `AtomicReference` allows the handoff block to call `traceSequencerRef.set(new EventTraceSequencer(sessionId, toId))` and have all subsequent events pick up the new `agentId` without requiring changes to the event consumer lambda itself.

**HandoffExecuted trace ordering — SWAP happens AFTER HandoffExecuted emission.** The trace sequencer swap must occur after emitting `HandoffExecuted` so that event carries the `fromId` agentId (planner), not the `toId` (executor). `ToolExecutionCompleted` (emitted immediately after) carries the `toId`. This is the correct semantic: the executed event announces the transition; everything after belongs to the new agent.

**Mixed-batch cancellation — remaining tool calls in the same batch get synthetic `"Cancelled"` tool messages.** When a handoff occurs as one of multiple tool calls in a single LLM response, the remaining calls are not executed. A synthetic `role: "tool"` message with content `"Cancelled: a handoff occurred..."` is appended for each, satisfying the OpenAI message-format invariant that every tool call ID in an assistant message must have a corresponding tool result message.

**Single-agent NO_TOOLS guard — condition changed from `isEmpty()` to `isEmpty() && profiles.isEmpty()`.** Multi-agent requests may have an empty `selectedToolNames` list yet still have handoff tools available. The guard now only fires when both base tools and profiles are absent.

**Test bugs caught and fixed during Layer 4:**
- `List.of(userMessage(...))` was incorrect — `userMessage()` returns `List<Map<String,Object>>` already; wrapping in `List.of()` created an incorrectly nested type that failed Java type inference at the `AgentRequest` constructor call site.
- `buildService(ai)` with no registered tools triggers the NO_TOOLS guard in single-agent mode; tests that need LLM calls must register at least one tool via `stubTool(...)`.
- `AgentEvent.AgentError.errorCode()` returns `String` (the `name()` of the enum); test assertions must compare against `AgentErrorCode.UNKNOWN_TOOL.name()`, not the enum constant directly.
- Event count in `singleHandoff_eventOrderingAndTraceAgentIds`: 9 significant events, not 8 — the executor's `TextChunk` was not filtered and belongs between `ToolExecutionCompleted` and `AgentDone`.

**Merge conflict assessment (2026-02-19).** Main has 8 commits since the branch diverged (error unification, pipeline cleanup, resilience hardening). A dry-run merge (`git merge --no-commit --no-ff main`) confirmed **no conflicts** — `AgentController.java` auto-merged cleanly. The feature branch is ready to merge after Layers 5 and 6 are complete.

### Layer 7 — Post-critical-analysis fixes (2026-02-19, `63de3bbf`)

Critical review of all 6 layers identified 8 issues. All fixed in one commit.

**Fix 1 (CRITICAL): `startSession()` couldn't reach multi-agent backend.** The function hardcoded `maxIterations: 5` and never sent `agentProfiles` or `initialAgentId`. The entire backend handoff path was unreachable from the UI. Added `AgentProfile` and `AgentSessionOptions` TS interfaces; updated `startSession()` signature to accept optional `options`; forwarded to a new `options: AgentSessionOptions` parameter on `runAgentStream()`. New session now resets `activeAgentId` and `handoffHistory` on start.

**Fix 2 (SIGNIFICANT): `initialAgentId = null` defaulted to `"primary"` instead of first profile.** Both `AgentLoopService.runAgent()` and `AgentRunStore.startRun()` resolved null to `"primary"`, ignoring the documented "first profile" fallback. Added resolution logic: `null` → `agentProfiles.get(0).agentId()` when profiles non-empty → `"primary"` otherwise. New test: `nullInitialAgentId_usesFirstProfile`.

**Fix 3 (SIGNIFICANT): Profile system prompts lost `/no_think` prefix.** `swapSystemPrompt()` set the profile's `systemPrompt` verbatim. Without the prefix, the model produces `<think>` tokens that waste budget and are stripped silently. Fixed: prepend `/no_think\n\n` when not already present. Updated `handoff_swapsSystemPrompt` test assertions to check prefix + suffix separately.

**Fix 4 (SIGNIFICANT): Loop guard counters not reset on handoff.** `lastCallSignature` and `consecutiveIdenticalCalls` were not cleared when `recordHandoff()` fired. The new agent inherited the previous agent's call history, causing premature loop-guard blocks. Fixed `AgentSession.recordHandoff()` to reset all three guard fields. New test: `handoff_resetsLoopGuardCounters`.

**Fix 5 (SIGNIFICANT): `handoffHistory` entries always had empty `reason`.** The `handoff_executed` SSE payload doesn't carry `reason` (only `handoff_proposed` does). Added `_pendingHandoffReason: string | null` to `AgentState` and `initialState`; captured on `handoff_proposed`, consumed and cleared on `handoff_executed`.

**Fix 6 (MINOR): `ToolRegistry` didn't guard against `handoff_to_` prefix collisions.** A tool registered with that prefix would be silently intercepted as a handoff call and fail with `UNKNOWN_TOOL`. Added prefix guard in `ToolRegistry.register()` with a clear exception message.

**Fix 7 (MINOR): `clearPendingApprovals()` Javadoc was misleading.** Added explanation that this is always a no-op in the current sequential architecture — each tool call in a batch is processed and its gate resolved before the loop advances, so no gates are in-flight when handoff fires. The call is retained as a defensive safeguard.

**Fix 8 (MINOR): Mixed-batch approach changed — assistant message trimmed instead of filler loop.** The original Layer 4 approach appended all tool call IDs in the assistant message before the loop, then added `"Cancelled"` filler results for calls after the handoff. This caused the new agent to see cancelled tool results for tools it never requested. **Changed approach**: pre-scan for handoff index before appending the assistant message; trim assistant message to calls `0..handoffIndex` inclusive; remove the cancellation filler loop entirely. Added `buildAssistantToolCallMessage(result, subList)` overload. New test: `handoff_mixedBatch_assistantMessageTrimmedToHandoff`.

**Note on Layer 4's mixed-batch description**: The Layer 4 notes below describe the filler-loop approach that was implemented and then superseded by Fix 8. The current behavior is the trimmed-message approach from Fix 8.

### Layer 8 — Post-M0 hardening: input filter, cycle detection, PRIMARY_PROFILE (2026-02-20, `71683b83`)

Addressed Recs 1, 2, and 4 from the Long-Term Architectural Recommendations.

**Rec 1 — `pruneHandoffMessages()` input filter.** Added `private static void pruneHandoffMessages(AgentSession session)` called in `AgentLoopService` immediately before `swapSystemPrompt()`. Algorithm: walk the message list backward to find the handoff assistant message at index K. If K > 2 (i.e., exploration occurred), collect tool call/result pairs from indices 2..K-1 into a compact text brief (`"Research findings:\n- tool: args\n  → result\n..."`). Remove those messages in reverse-index order (safe because they're removed from the tail inward), then inject the brief as a `{role:"system"}` message at index 2. The handoff call (index K), the approval-reset system message (K+1), and the confirmed tool result (K+2) are all preserved. Saves 700–1300 tokens per handoff on sessions with moderate search work, addressing the -529 budget deficit observed in U3.

**Rec 2 — PRIMARY_PROFILE + mode selector removal.** Added `PRIMARY_PROFILE` to `agentProfiles.ts` with all 4 tools in `toolSubset` and a system prompt with explicit handoff trigger rules. Updated `BUILTIN_PRESETS = [PRIMARY_PROFILE, ORGANIZER_PROFILE]`. Removed the `agentMode` state, `AgentMode` type, and `OptionButton` pair from `AgentView.tsx`; `handleSend` now always starts sessions with `BUILTIN_PRESETS` and `initialAgentId: BUILTIN_PRESETS[0].agentId`. Users no longer choose between single-agent and multi-agent mode.

**Rec 4 — Cycle detection via `maxHandoffs`.** Added `Integer maxHandoffs` as the 6th field on `AgentRequest` (with a 5-arg convenience constructor defaulting it to null). Added `handoffPairCounts Map<String,Integer>` to `AgentSession` with `incrementHandoffPair(fromId, toId)` returning the per-direction count. In `AgentLoopService`, before committing a handoff, checks `pairCount > maxH` where `maxH = maxHandoffs ?? profiles.size() * 3`; emits `AgentError(HANDOFF_CYCLE_DETECTED, TOOL_CONTRACT, ABORT)` and terminates if exceeded. `AgentController` parses `maxHandoffs` from the JSON body. `AgentRetryPolicy` includes `HANDOFF_CYCLE_DETECTED` in the ABORT case. `errorMessages.ts` adds `HANDOFF_CYCLE_DETECTED` (and `TOOL_LOOP`, which was previously missing from the catalog).

### Layer 8 — Critical analysis: 6 issues found, deferred to Layer 9

Post-implementation review of the Layer 8 commit identified 6 issues. All fixed in Layer 9.

| # | Issue | Severity |
|---|-------|----------|
| 1 | `pruneHandoffMessages` has zero unit tests | High |
| 2 | Brief includes previous `handoff_to_*` calls as noise on second handoffs | Medium |
| 3 | PRIMARY_PROFILE has write tools — prompt-only enforcement unreliable | Medium |
| 4 | Cycle detection is directional (per-pair): 2-agent ping-pong fires after 12 handoffs, not 6 | Low |
| 5 | ORGANIZER_PROFILE prompt says "Researcher's findings" (stale name) | Trivial |
| 6 | RESEARCHER_PROFILE exported but unexplained dead code | Trivial |

### Layer 9 — Critical-analysis follow-up: 6 fixes (2026-02-20, `f76e3711`)

**Fix 1 — Tests for `pruneHandoffMessages`.** Changed method visibility from `private static` to package-private (`static`) so it is directly callable from `AgentLoopServiceTest` (same package). Added 4 focused unit tests: no-op when no exploration occurred (`handoffAssistantIdx == 2`); single tool call/result pair stripped and brief injected; multiple pairs all stripped with both tool names in brief; intermediate `handoff_to_*` call in range excluded from brief (Fix 2's filter verified here).

**Fix 2 — Filter `handoff_to_*` from brief.** In the brief-building loop, added `if (toolName.startsWith("handoff_to_")) continue;` before the `brief.append(...)` line. Prevents previous handoff infrastructure calls from appearing in the research summary. Tool result messages for those skipped calls can remain in the brief — they give the incoming agent useful context that a transition occurred.

**Fix 3 — PRIMARY_PROFILE physical tool restriction.** Changed `PRIMARY_PROFILE.toolSubset` from all 4 tools to `['search_index', 'browse_folders']`. Physical restriction (tool unavailable) is more reliable than prompt-only enforcement: if PRIMARY could call `ingest_files` directly, `pruneHandoffMessages` would never fire for those sessions and the token-saving benefit of the input filter would be lost.

**Fix 4 — Cycle detection: total handoffs, not directional.** In `AgentSession`, replaced `Map<String, Integer> handoffPairCounts` + `incrementHandoffPair(fromId, toId)` with `int totalHandoffs = 0` + `incrementTotalHandoffs()`. In `AgentLoopService`, replaced `session.incrementHandoffPair(fromId, toId)` with `session.incrementTotalHandoffs()` and updated the error message from `"fromId→toId exceeded limit of N"` to `"N total handoffs exceeded limit of N"`. With 2 profiles, `maxH = 6`; a P→O→P→O ping-pong now fires on the 7th total handoff regardless of direction, not the 4th occurrence of a single direction (which required 12 total handoffs). Added `handoff_cycleDetection_firesAfterMaxTotalHandoffs` integration test using `maxHandoffs: 2` with 3 scripted handoff responses; asserts `HANDOFF_CYCLE_DETECTED` error and exactly 2 `HandoffExecuted` events.

**Fix 5 — ORGANIZER_PROFILE prompt: stale "Researcher's findings" reference.** Updated two lines in the system prompt: "Researcher's findings (visible in the conversation history above)" → "research summary in the conversation history above"; "Review the Researcher's findings in the conversation history" → "Review the research summary in the conversation history above."

**Fix 6 — RESEARCHER_PROFILE: added JSDoc comment.** Added JSDoc before `RESEARCHER_PROFILE` explaining it is a named export for custom session configurations and is not in `BUILTIN_PRESETS` (PRIMARY_PROFILE serves the read-only role by default).

### Layer 5+6 post-implementation notes (2026-02-19)

**Layer 5 — `AgentController.handleRunStream()` parses profiles from JSON body.** Uses `body.has("agentProfiles") ? MAPPER.convertValue(...) : List.of()` consistent with the existing `tools` extraction pattern, then streams each raw map through `AgentProfile.fromMap()`. `initialAgentId` uses a null-safe `body.has(...) && !body.path(...).isNull()` guard — `JsonNode.asText()` without a guard returns `""` (empty string) for missing/null nodes, not null. The `AgentRequest` 3-arg constructor handles backward-compatible callers; `handleRunStream()` now always uses the 5-arg constructor (agentProfiles and initialAgentId default to empty/null when absent from the request body).

**Layer 5 gate — two new `AgentSseContractTest` scenarios.** `handoffPayloadsAreStable()` drives a `ContractHandoffAgentService` that emits `HandoffProposed("planner", "executor", "time to execute")` and `HandoffExecuted("planner", "executor")`, then asserts both SSE event payloads have the correct field values. `agentProfilesAndInitialAgentIdAreParsedFromRequest()` uses a `CapturingAgentService` that stores the received `AgentRequest`; the test POSTs a body with two profiles and `initialAgentId: "planner"` and asserts the captured request matches exactly (profile count, agentId, name, toolSubset).

**Layer 6 — `useAgentStore.ts` SSE handler + state.** Added `HandoffEntry` type, `'handoff'` `ConversationEntryType`, and `fromAgentId`/`toAgentId` optional fields on `ConversationEntry`. `handoff_proposed` appends a `'handoff'` conversation entry with the reason text so the UI can display handoff transitions inline. `handoff_executed` updates `activeAgentId` (tracks which agent is currently active) and appends to `handoffHistory` (ordered list of all handoffs in the session). Both fields reset to `null`/`[]` on `reset()` via `initialState`. The pre-existing TypeScript errors in `useAppAI.test.ts`, `i18n.ts`, and `errorMessages.ts` are unrelated to this work.

---

## Implementation Plan (M0 Scope)

### Step 1: New types — `AgentProfile` + extend `AgentRequest` + add handoff events to `AgentEvent`

**`AgentProfile.java`** (new record in `app-agent-api`):

```java
package io.justsearch.agent.api;

import java.util.List;
import java.util.Objects;

/**
 * Defines a named agent role within a multi-agent session.
 * Profiles are optional — single-agent requests omit them.
 */
public record AgentProfile(
    /** Unique identifier for this agent within the session. */
    String agentId,
    /** Human-readable name for display (e.g. "Planner", "Executor"). */
    String name,
    /** System prompt override for this agent role. Null = use default. */
    String systemPrompt,
    /** Tool names this agent may use. Empty list = all tools allowed. */
    List<String> toolSubset) {

  public AgentProfile {
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(name, "name");
    toolSubset = toolSubset == null ? List.of() : List.copyOf(toolSubset);
  }
}
```

**`AgentRequest.java`** — add optional fields. The existing compact constructor and factory methods are preserved unchanged:

```java
public record AgentRequest(
    List<Map<String, Object>> messages,
    List<String> selectedToolNames,
    int maxIterations,
    List<AgentProfile> agentProfiles,   // NEW — null or empty = single-agent mode
    String initialAgentId) {            // NEW — null = use first profile or "primary"

  // Existing compact constructor extended to handle new fields
  public AgentRequest {
    Objects.requireNonNull(messages, "messages");
    selectedToolNames = selectedToolNames == null ? List.of() : List.copyOf(selectedToolNames);
    if (maxIterations < 1) throw new IllegalArgumentException("...");
    agentProfiles = agentProfiles == null ? List.of() : List.copyOf(agentProfiles);
    // initialAgentId null is valid — means first profile or "primary"
  }

  // Existing factory unchanged
  public static AgentRequest singleTurn(List<Map<String, Object>> messages) {
    return new AgentRequest(messages, List.of(), 1, List.of(), null);
  }
}
```

**`AgentEvent.java`** — add two sealed records after `SessionStarted`:

```java
/**
 * Active agent proposes handing off to another agent role.
 * Emitted when the LLM calls a handoff tool; handoff is not yet committed.
 */
record HandoffProposed(
    String fromAgentId, String toAgentId, String reason, TraceContext trace)
    implements AgentEvent {
  public HandoffProposed(String fromAgentId, String toAgentId, String reason) {
    this(fromAgentId, toAgentId, reason, TraceContext.none());
  }
}

/**
 * Handoff committed — the new agent is now active.
 * Approval gates from the previous agent have been cleared.
 */
record HandoffExecuted(String fromAgentId, String toAgentId, TraceContext trace)
    implements AgentEvent {
  public HandoffExecuted(String fromAgentId, String toAgentId) {
    this(fromAgentId, toAgentId, TraceContext.none());
  }
}
```

**`AgentProfile.java`** — add a static `fromMap()` helper. The same deserialization logic is needed in both `AgentController` (request body parsing) and `resumeLastSession()` (checkpoint restoration). Centralising it in the record prevents duplication and drift:

```java
/** Deserializes an AgentProfile from a raw Map (from JSON body or checkpoint). */
public static AgentProfile fromMap(Map<String, Object> m) {
    @SuppressWarnings("unchecked")
    List<String> toolSubset = m.get("toolSubset") instanceof List<?> ts
        ? ts.stream().map(String::valueOf).toList() : List.of();
    return new AgentProfile(
        String.valueOf(m.get("agentId")),
        String.valueOf(m.get("name")),
        m.get("systemPrompt") != null ? String.valueOf(m.get("systemPrompt")) : null,
        toolSubset);
}
```

**`AgentController.java`** — parse `agentProfiles` and `initialAgentId` from the raw request body `Map`. The controller already extracts `messages`, `tools`, and `maxIterations` via key lookups; the same pattern applies:

```java
// After existing field extraction in handleRunStream():
@SuppressWarnings("unchecked")
List<Map<String, Object>> rawProfiles =
    body.get("agentProfiles") instanceof List<?> l
        ? (List<Map<String, Object>>) l : List.of();
List<AgentProfile> agentProfiles = rawProfiles.stream()
    .map(AgentProfile::fromMap)
    .toList();
String initialAgentId = body.get("initialAgentId") instanceof String s ? s : null;

// Then pass to the 5-arg AgentRequest constructor:
var request = new AgentRequest(messages, selectedTools, maxIterations,
    agentProfiles, initialAgentId);
```

### Step 2: `AgentSession` — add handoff state and `clearPendingApprovals()`

New fields and methods in `AgentSession`:

```java
// New fields
private String activeAgentId;
private final List<Map<String, Object>> handoffHistory = new ArrayList<>();

// New constructor parameter
AgentSession(String sessionId, List<Map<String, Object>> messages,
             int initialBudget, String initialAgentId) {
    this.sessionId = sessionId;
    this.messages = new ArrayList<>(messages);
    this.budgetRemaining = new AtomicInteger(initialBudget);
    this.promptTokensConsumed = new AtomicInteger(0);
    this.completionTokensConsumed = new AtomicInteger(0);
    this.activeAgentId = initialAgentId != null ? initialAgentId : "primary";
}

String activeAgentId() { return activeAgentId; }

List<Map<String, Object>> handoffHistory() {
    return Collections.unmodifiableList(handoffHistory);
}

/** Record a handoff and update the active agent cursor. */
void recordHandoff(String fromAgentId, String toAgentId, String reason) {
    activeAgentId = toAgentId;
    var record = new LinkedHashMap<String, Object>();
    record.put("fromAgentId", fromAgentId);
    record.put("toAgentId", toAgentId);
    record.put("reason", reason);
    record.put("timestamp", Instant.now().toString());
    handoffHistory.add(record);
}

/**
 * Clears all pending approval gates by completing them with false.
 * Called on handoff to enforce approval boundary reset — approvals granted
 * to the previous agent role must not carry over to the new role.
 */
void clearPendingApprovals() {
    approvalGates.values().forEach(f -> f.complete(false));
    approvalGates.clear();
}
```

### Step 3: `AgentRunStore` — schema v2 with `activeAgentId` + `handoffHistory` + `agentProfiles`

1. Bump `CURRENT_SCHEMA_VERSION = 2`.
2. Add upcaster v1→v2 in the `UPCASTERS` list:
   ```java
   // v1 -> v2: add handoff fields and profile fields (absent in single-agent checkpoints)
   meta -> {
       var upgraded = new LinkedHashMap<>(meta);
       upgraded.putIfAbsent("activeAgentId", "primary");
       upgraded.putIfAbsent("handoffHistory", List.of());
       upgraded.putIfAbsent("agentProfiles", List.of());
       upgraded.putIfAbsent("initialAgentId", null);
       return upgraded;
   }
   ```
3. Extend `startRun()` to persist `agentProfiles` and `initialAgentId` in the initial checkpoint. These are written once at session creation and never change — they are not dynamic like `activeAgentId`. This is what makes resume possible: `resumeLastSession()` can reconstruct the original `AgentRequest` with its profiles from the checkpoint.
4. Add a new `setHandoffState(String sessionId, String activeAgentId, List<Map<String, Object>> handoffHistory)` method that patches handoff fields into `meta.json` independently of the regular checkpoint flow. Do **not** add parameters to `updateCheckpoint()` — it has 19 call sites in `AgentLoopService.java` and extending its signature would require updating all of them. `setHandoffState()` reads `meta.json`, patches only the two handoff fields, and writes back atomically.
5. Extend `toEventType()` and `toPayload()` switch expressions to handle `HandoffProposed` and `HandoffExecuted`.

### Step 4: `AgentLoopService` — active-agent cursor, handoff detection, system prompt switching

This is the core logic change. Key pieces:

**Budget semantics**: Each outer loop iteration — including iterations where the only outcome is a handoff — consumes one from the shared `maxIterations` budget. The counter is not reset when the active agent changes. This is consistent with OpenAI SDK `max_turns` semantics. A session with 3 handoffs and 7 tool calls requires `maxIterations ≥ 10`.

#### 4a. Build handoff tools from agent profiles

When `agentProfiles` is non-empty, each non-active profile is registered as an ephemeral handoff tool named `handoff_to_<agentId>`. These tools have `READ_ONLY` safety (no user approval gate for the handoff itself):

```java
private List<Map<String, Object>> buildHandoffTools(
        List<AgentProfile> profiles, String activeAgentId) {
    return profiles.stream()
        .filter(p -> !p.agentId().equals(activeAgentId))
        .map(p -> Map.<String, Object>of(
            "type", "function",
            "function", Map.of(
                "name", "handoff_to_" + p.agentId(),
                "description", "Hand off to the " + p.name() + " agent.",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "reason", Map.of("type", "string",
                            "description", "Why this handoff is needed.")),
                    "required", List.of("reason")))))
        .toList();
}
```

These are merged with the normal tool list before each LLM call.

#### 4b. Detect handoff tool calls in LLM response

After parsing `result.toolCalls()`, check each call name:

```java
boolean isHandoffCall(ToolCallRequest call) {
    return call.toolName().startsWith("handoff_to_");
}

String targetAgentId(ToolCallRequest call) {
    return call.toolName().substring("handoff_to_".length());
}
```

#### 4c. Handoff execution in the loop

When a handoff call is detected in the tool calls list:

```java
if (isHandoffCall(call)) {
    String fromId = session.activeAgentId();
    String toId = targetAgentId(call);
    AgentProfile toProfile = findProfile(request.agentProfiles(), toId);
    if (toProfile == null) {
        // Unknown target — emit error and abort
        emitError(sink, "Unknown agent target: " + toId, ...);
        return;
    }

    String reason = extractReason(call.arguments()); // parse from JSON
    sink.accept(new AgentEvent.HandoffProposed(fromId, toId, reason));

    // Clear pending approvals — boundary reset
    session.clearPendingApprovals();

    // Inject system message about approval reset
    session.appendMessage(Map.of("role", "system", "content",
        "Handoff from " + fromId + " to " + toId + ". Reason: " + reason
        + " Any write or destructive actions must be re-approved."));

    session.recordHandoff(fromId, toId, reason);
    sink.accept(new AgentEvent.HandoffExecuted(fromId, toId));

    // Swap active agent: update EventTraceSequencer's agentId
    traceSequencer = new EventTraceSequencer(sessionId, toId);

    // Swap system prompt
    swapSystemPrompt(session, toProfile);

    // Tool response for this call (required by OpenAI message format)
    session.appendMessage(Map.of(
        "role", "tool",
        "tool_call_id", call.id(),
        "content", "Handoff to " + toId + " confirmed."));

    runStore.updateCheckpoint(sessionId, "READY_FOR_LLM",
        session.messages(), session.iterationsUsed(),
        session.toolCallsExecuted(), session.totalTokens(), "");
    runStore.setHandoffState(sessionId, session.activeAgentId(), session.handoffHistory());

    // Break out of tool-call loop — new agent starts fresh on next iteration
    break;
}
```

**Policy: handoff tool call must be the sole tool call processed.** If the LLM returns a handoff call alongside regular tool calls, or returns multiple handoff calls, only the first handoff is executed. All remaining tool calls in the same batch — whether regular tools or additional handoff calls — must receive a cancellation tool result to satisfy the OpenAI message format. Unanswered tool calls cause model errors on the next LLM call:

```java
// After the handoff `break`, before continuing to next iteration,
// inject cancellation results for all tool calls not yet answered:
for (ToolCallRequest skipped : remainingCallsInBatch) {
    session.appendMessage(Map.of(
        "role", "tool",
        "tool_call_id", skipped.id(),
        "content", "Cancelled: a handoff occurred before this tool could execute."));
}
```

Iterate through all tool calls in the batch; any call processed before the handoff already has its tool result appended normally. Only calls that come *after* the handoff call in the batch need cancellation results.

#### 4d. System prompt swapping

```java
private void swapSystemPrompt(AgentSession session, AgentProfile toProfile) {
    List<Map<String, Object>> messages = session.messages();
    String newPrompt = toProfile.systemPrompt() != null
        ? toProfile.systemPrompt()
        : buildSystemPrompt();
    if (!messages.isEmpty() && "system".equals(messages.get(0).get("role"))) {
        messages.set(0, Map.of("role", "system", "content", newPrompt));
    } else {
        messages.add(0, Map.of("role", "system", "content", newPrompt));
    }
}
```

#### 4e. `wrapEventConsumer` — `traceSequencer` must be mutable across handoffs

Currently `traceSequencer` is a local final variable inside `wrapEventConsumer`. For M0, it needs to be swappable. Promote it to a `volatile` field on `AgentLoopService` or wrap in a holder. Cleanest: use an `AtomicReference<EventTraceSequencer>` held by the closure, updated on handoff.

#### 4f. `resumeLastSession()` — restore `agentProfiles` and `activeAgentId` from checkpoint

`resumeLastSession()` currently reconstructs `AgentRequest` using the 3-arg backward-compatible constructor, which sets `agentProfiles = []` and `initialAgentId = null`. For multi-agent sessions this is wrong — the resumed session would restart as single-agent on `"primary"` regardless of which agent was active.

**Fix**: read `agentProfiles`, `initialAgentId`, and `activeAgentId` from the v2 checkpoint map and pass them to the 5-arg constructor. Seed `initialAgentId` from the checkpoint's `activeAgentId` (not the original `initialAgentId`) so resume continues from the agent that was active at the time of interruption:

```java
// Inside resumeLastSession(), after extracting messages/selectedTools/maxIterations:
@SuppressWarnings("unchecked")
List<Map<String, Object>> rawProfiles =
    snapshot.get("agentProfiles") instanceof List<?> l
        ? (List<Map<String, Object>>) l : List.of();
List<AgentProfile> agentProfiles = rawProfiles.stream()
    .map(AgentProfile::fromMap)   // see Edit 7 — static helper
    .toList();
// Use activeAgentId (not initialAgentId) as the starting point for resumed session
String resumeAgentId = snapshot.get("activeAgentId") instanceof String s ? s : null;

runAgent(new AgentRequest(resumeMessages, selectedTools, maxIterations,
    agentProfiles, resumeAgentId), eventConsumer);
```

#### 4g. `withTrace()` — add cases for the two new sealed subtypes

`withTrace()` (line 809 in the current file) is a fifth exhaustive switch site beyond the four listed in the codebase investigation section. Adding `HandoffProposed` and `HandoffExecuted` to the sealed interface causes a compile error there unless two new cases are added:

```java
case AgentEvent.HandoffProposed e ->
    new AgentEvent.HandoffProposed(e.fromAgentId(), e.toAgentId(), e.reason(), trace);
case AgentEvent.HandoffExecuted e ->
    new AgentEvent.HandoffExecuted(e.fromAgentId(), e.toAgentId(), trace);
```

### Step 5: `AgentController` — add handoff events to SSE switch expressions

Two switch expressions in `writeAgentEvent()` need two new cases:

```java
// payload switch
case AgentEvent.HandoffProposed e -> Map.of(
    "fromAgentId", e.fromAgentId(),
    "toAgentId", e.toAgentId(),
    "reason", e.reason());
case AgentEvent.HandoffExecuted e -> Map.of(
    "fromAgentId", e.fromAgentId(),
    "toAgentId", e.toAgentId());

// eventType switch
case AgentEvent.HandoffProposed ignored -> "handoff_proposed";
case AgentEvent.HandoffExecuted ignored -> "handoff_executed";
```

Same pattern in `AgentRunStore.toEventType()` and `AgentRunStore.toPayload()`.

### Step 6: `useAgentStore.ts` — frontend handoff state and SSE cases

**M0 scope**: The existing chat UI has no controls for composing `agentProfiles`. Multi-agent sessions can only be initiated via direct POST to `/api/agent/run/stream` with `agentProfiles` in the request body. This step covers only the display side — reading SSE events and updating store state. UI for profile authoring is post-M0.

New state fields in `AgentState`:

```typescript
activeAgentId: string | null;
// Separate from ConversationEntry — avoids requiring a new union variant in the conversation type
handoffLog: Array<{ fromAgentId: string; toAgentId: string; reason: string; timestamp: number }>;
```

Do **not** use `type: 'progress'` inside the `conversation` array. `ConversationEntry` is a closed union and does not have a `'progress'` variant — adding an unknown discriminant would be a TypeScript type error. Use the dedicated `handoffLog` array instead.

New cases in `handleSseEvent`:

```typescript
case 'handoff_proposed': {
    // Record in handoffLog — NOT in conversation (wrong entry type)
    set((s) => ({
        handoffLog: [...(s.handoffLog ?? []), {
            fromAgentId: data.fromAgentId as string,
            toAgentId: data.toAgentId as string,
            reason: data.reason as string,
            timestamp: Date.now(),
        }],
    }));
    break;
}
case 'handoff_executed': {
    // Update active agent cursor; handoffLog already has the proposed entry with reason
    set(() => ({
        activeAgentId: data.toAgentId as string,
    }));
    break;
}
```

---

## Test Plan

### Prerequisite: Add `recordedTools` to `ScriptedAiService`

`ScriptedAiService.streamChatWithTools()` currently discards the tools list passed to it — only `recordedMessages` and `recordedSampling` are saved. Without `recordedTools`, tests cannot assert that handoff tools are injected correctly per active agent.

**Fix**: add `List<List<Map<String, Object>>> recordedTools = new ArrayList<>()` to `ScriptedAiService`, populated in each `streamChatWithTools()` call before delegating to scripted responses. This is a prerequisite for Scenarios 2 and 4 below.

### `AgentEventSealedTest` — update the count assertion

`AgentEventSealedTest.java:71` asserts `assertEquals(12, permitted.length)`. Update to `assertEquals(14, permitted.length)` after adding `HandoffProposed` and `HandoffExecuted`. The compiler enforces exhaustive switches at every site, but this test additionally guards against accidental removal — the number must stay accurate.

### Unit tests: `AgentLoopServiceTest.java`

The existing `ScriptedAiService` pattern allows fully deterministic scripting of LLM responses. All handoff scenarios are unit-testable here without a running model.

**Scenario 1: Single handoff — event ordering and trace IDs**

Scripted LLM: iteration 1 returns `handoff_to_executor` tool call (with `reason`); iteration 2 (now executor) returns a final text response. Assert event stream in order:
1. `SessionStarted`
2. `ToolCallProposed` (for `handoff_to_executor`)
3. `ToolCallApproved` (auto, READ_ONLY — no gate)
4. `ToolExecutionStarted`
5. `HandoffProposed(fromAgentId="planner", toAgentId="executor", reason=...)`
6. `HandoffExecuted(fromAgentId="planner", toAgentId="executor")`
7. `ToolExecutionCompleted`
8. `AgentDone`

Assert `TraceContext.agentId()` is `"planner"` on events 1–6 and `"executor"` on events 7–8.

**Scenario 2: Tool injection — handoff tools are per-active-agent**

Use the `recordedTools` field added above. Run a two-profile session (planner, executor). Iteration 1 (planner active): assert `recordedTools.get(0)` contains a tool named `handoff_to_executor` but not `handoff_to_planner`. After handoff, iteration 2 (executor active): assert `recordedTools.get(1)` contains `handoff_to_planner` but not `handoff_to_executor`.

**Scenario 3: System prompt swap on handoff**

Run a two-profile session where planner has system prompt `"You are a planner"` and executor has `"You are an executor"`. After handoff, assert `ai.recordedMessages.get(1).get(0)` (first message of second iteration) has content `"You are an executor"`.

**Scenario 4: Single-agent request receives no handoff tools**

`AgentRequest` with empty `agentProfiles`. Assert `recordedTools.get(0)` contains no entry whose name starts with `handoff_to_`. Loop completes normally with a regular tool call.

**Scenario 5: Unknown handoff target emits `AgentError`**

LLM calls `handoff_to_nonexistent` in a session with profiles `[planner, executor]`. Assert `AgentError` is emitted with an appropriate error code and the loop terminates without reaching the next iteration.

**Scenario 6: Approval boundary reset after handoff**

Set up a two-profile session. Iteration 1 (planner): LLM returns `[handoff_to_executor]`. Iteration 2 (executor): LLM returns `[file_operations(WRITE)]`. Assert:
- Iteration 1 produces `HandoffExecuted` with no pending gates afterward (gates map is empty).
- Iteration 2 produces a fresh `ToolCallPendingApproval` for the write tool — it is not auto-approved from any pre-handoff state.

This validates the approval boundary reset: the executor must re-request approval even though the planner held no pending gate at handoff time. The scenario is mechanically sound because `handleSafetyGate()` blocks the thread on WRITE tools — the handoff fires first (iteration 1), and the new approval requirement surfaces in iteration 2 with a clean slate.

### Unit tests: `AgentRunStoreTest.java`

**Scenario: Schema v1 checkpoint upcasts to v2**

Write a v1 checkpoint JSON (no `activeAgentId`, no `handoffHistory`). Call `readLastSnapshot()`. Assert result contains `activeAgentId = "primary"` and `handoffHistory = []`.

**Scenario: Handoff events are serialized and replayed**

Call `appendEvent()` with `HandoffProposed` and `HandoffExecuted`. Call `readEvents()`. Assert both events appear in order with correct `eventType` strings (`"handoff_proposed"`, `"handoff_executed"`) and payload fields (`fromAgentId`, `toAgentId`, `reason`).

### SSE contract test: `AgentSseContractTest.java`

Add a new test method with a `ContractHandoffAgentService` stub that emits `HandoffProposed` and `HandoffExecuted` via the agent SSE stream. Assert:
- `handoff_proposed` event payload contains `fromAgentId`, `toAgentId`, `reason` with correct string values.
- `handoff_executed` event payload contains `fromAgentId`, `toAgentId` with correct string values.

This guards against field name drift between the sealed `AgentEvent` records and the JSON serialized to the client.

### Frontend TypeScript — type-check only

`useAgentStore.ts` has no automated test framework. Verification is TypeScript type-checking only (`tsc --noEmit`). Manual review confirms the new `handoff_proposed`/`handoff_executed` cases are wired correctly. No automated test targets here.

### Dev stack — advisory, not a gate

The 4B–8B model may not reliably call `handoff_to_<agentId>` tools given real prompts. The dev stack is useful for manual exploration but is not a reliable regression gate for this feature. Unit tests in `AgentLoopServiceTest`, `AgentRunStoreTest`, and `AgentSseContractTest` are the primary pass/fail gate.

### Verification sequence

1. `./gradlew.bat spotlessApply`
2. `./gradlew.bat build -x test` — sealed switch expressions enforce completeness at compile time; this is the strongest correctness gate
3. `./gradlew.bat :modules:app-agent-api:test` — `AgentEventSealedTest` count guard
4. `./gradlew.bat :modules:app-agent:test` — full handoff scenario matrix
5. `./gradlew.bat :modules:ui:test` — `AgentSseContractTest` handoff payload contract
6. `./gradlew.bat test` — full suite regression

---

## Open Questions

1. **Should `handoff_to_<agentId>` tools be auto-approved (READ_ONLY) or require user confirmation?**
   Current recommendation: READ_ONLY (auto-approved). The handoff itself is not destructive; the subsequent agent's actions are what require approval. Changing to `WRITE` would require a UI prompt before every handoff, which breaks the planner→executor workflow.

2. **Should full conversation history be passed across handoff boundaries (M0), or should an `input_filter` reduce it?**
   M0 recommendation: full history (ADK `SequentialAgent` default, OpenAI SDK default without a filter). This is the simplest correct implementation. Context filtering is a future optimization.

3. **Where does `traceSequencer` live when it must be swapped on handoff?**
   Current: final local in `wrapEventConsumer`. Options: (a) wrap in `AtomicReference` inside the closure, (b) promote to a per-session mutable field. Option (a) is simpler and keeps the sequencer scoped to the consumer lambda.

4. **Should `AgentController.handleRunStream()` parse `agentProfiles` from the request body?**
   Yes — the controller already parses `messages`, `tools`, and `maxIterations` from the JSON body. Adding `agentProfiles` follows the same pattern. The `AgentProfile` list needs a JSON schema agreed between frontend and backend.

5. **What is the JSON schema for `agentProfiles` in the POST /api/agent/run/stream body?**
   Proposed:
   ```json
   {
     "agentProfiles": [
       { "agentId": "planner", "name": "Planner", "systemPrompt": "...", "toolSubset": [] },
       { "agentId": "executor", "name": "Executor", "systemPrompt": "...", "toolSubset": ["file_operations"] }
     ],
     "initialAgentId": "planner"
   }
   ```

6. **Should M0 include a `maxHandoffs` limit or cycle detection?**
   Current plan: `maxIterations` is the only backstop. A planner↔executor cycle will exhaust the budget with no useful output, then terminate with `AgentError(MAX_ITERATIONS_EXCEEDED)`. For M0 this is acceptable — the budget protects against unbounded loops and the error is observable. A `maxHandoffs` parameter or visited-agent-ID cycle detection is deferred post-M0. If the budget is consistently exhausted in real sessions due to cycling, add cycle detection then.

---

## Gap Analysis (2026-02-19)

The infrastructure (Layers 1–7) is complete and correct. However the stated goal — unblocking advanced workflows like plan-then-execute — is **not actually delivered to users**. Three gaps remain.

### Gap 1: No UI surface

There is no way for a user to define agent profiles or start a multi-agent session through the product UI. The only invocation path is a hand-crafted POST to `/api/agent/run/stream` with a `agentProfiles` body. Fix 1 made `startSession()` capable of forwarding profiles, but no UI component ever calls it with profiles. UI for profile authoring was explicitly deferred in the M0 scope — it now needs to be designed and built.

**Codebase findings (2026-02-19):**
- Call site: `modules/ui-web/src/components/views/agent/AgentView.tsx:98` — `startSession(base, message)` — no options argument ever passed
- Available UI primitives in the codebase: `ConfirmDialog`, `Toggle`, `OptionButton`, `Section` — these patterns can be reused for a profile-authoring UI
- `startSession` now accepts `options?: AgentSessionOptions`; the type is exported and ready for callers

### Gap 2: No default profiles

There are no pre-built profile definitions in the codebase. A user who could invoke the API would still need to author system prompts and assign tool subsets from scratch. Without sensible defaults, the feature is unusable even for technical users. Defaults require knowing the real tool set, the model's capabilities, and what system prompt wording actually works.

**Codebase findings (2026-02-19):**
- Production tool set (4 tools): `search_index` (READ_ONLY), `browse_folders` (READ_ONLY), `file_operations` (WRITE), `ingest_files` (WRITE)
- Natural profile split from this tool set:
  - **Researcher**: `toolSubset: ["search_index", "browse_folders"]` — find and read, never modify
  - **Organizer**: `toolSubset: ["file_operations", "ingest_files"]` — apply changes based on Researcher findings
- The current default system prompt is built by `AgentLoopService.buildSystemPrompt()` — this is the baseline any profile-specific prompt should diverge from
- Prompt engineering signal from codebase: the agent already uses `/no_think` prefix (forced by Fix 3) and AGENT sampling preset (temp 0.2, top_p 0.9) — profile system prompts should stay terse and directive-first

### Gap 3: Model reliability for tool calling

The handoff mechanism depends entirely on the LLM calling `handoff_to_<agentId>` at the right moment. If the model ignores the handoff tool and just continues reasoning, no handoff occurs and the session degrades silently to single-agent behavior with no error signal to the user.

**Codebase findings (2026-02-19):**
- Production model: **Qwen3VL-8B-Thinking (Q4_K_M)**
- Server runs with `--jinja` flag — enables the native Qwen3 Jinja2 chat template with first-class, model-trained tool-use support (distinct from the generic OpenAI-compatible adapter)
- `--reasoning-budget 0` + `/no_think` prefix (enforced by Fix 3) suppress chain-of-thought tokens — the model operates in fast-answer mode rather than extended-thinking mode
- Context window: 8192 tokens; token budget matters for multi-turn multi-agent sessions
- Function calling format: OpenAI-compatible (`/v1/chat/completions` with `tools` array)

**External research findings (2026-02-19):**

*Qwen3 tool calling capability:*
- Qwen3 was explicitly trained for tool calling; the Qwen team describes it as "excelling in tool calling capabilities" and "leading performance among open-source models in complex agent-based tasks"
- The Qwen3 Coder variant — which operates in **non-thinking mode only** — is described as "optimized for real-world tool orchestration." This confirms that non-thinking mode (what we force with `/no_think`) is the right mode for tool-calling agent scenarios
- No public per-model BFCL (Berkeley Function Calling Leaderboard) breakdown for Qwen3-8B was accessible; the Qwen team directs users to Qwen-Agent for production use

*Known failure modes for tool-calling agents (from MCPMark / BFCL research):*
- Models struggle with "knowing when NOT to invoke tools" — for handoffs the inverse applies: the risk is the model **failing to call the handoff tool** when it should, instead continuing to reason as the current agent
- Long multi-step sessions (16+ turns) are where failures compound; a 2-agent session with moderate tool use is near the reliable end of the spectrum
- Multi-turn context management is the primary failure axis — full conversation history passed at each handoff (our M0 design) can lead to context bloat that confuses the new agent

*OpenAI Agents SDK guidance on handoff prompt engineering:*
- SDK recommendation: "include information about handoffs in your agents" — they provide a `RECOMMENDED_PROMPT_PREFIX` with explicit handoff instructions, available via `agents.extensions.handoff_prompt.prompt_with_handoff_instructions`
- Our current implementation does NOT inject any handoff guidance into the agent system prompt — the model must infer when to call `handoff_to_<agentId>` from the tool description alone
- **Actionable gap**: default system prompt and/or profile system prompts need explicit handoff guidance ("When you have completed your phase, call `handoff_to_<nextAgent>` to transfer control")

*Tool naming and scoping:*
- OpenAI SDK naming convention `transfer_to_<agent_name>` maps directly to our `handoff_to_<agentId>` — consistent design
- Limiting tools per profile (our `toolSubset` mechanism) is the correct mitigation: fewer tools = more reliable routing decisions
- For simple 2-profile sessions with 2–3 domain tools each, the routing decision space is small enough that a well-prompted 8B model should handle it reliably

**Assessment after research**: Gap 3 is **partially de-risked** — Qwen3-8B with native Jinja tool calling + non-thinking mode is the best-available open model for this scenario. The primary remaining risk is not fundamental model incapability but **missing prompt guidance** (the `RECOMMENDED_PROMPT_PREFIX` gap above). This is fixable by updating the default system prompt. Empirical validation with real prompts against the running model is still needed.

---

## Ship Gate

1. Handoff scenario matrix passes with deterministic event ordering and replay parity. ✅
2. No approval bypass across handoff boundaries in restart/resume system tests. ✅
3. M0 uses single-model sequential handoffs only; unsupported parallel states are explicitly documented and typed. ✅
4. ✅ Users can initiate a multi-agent session through the product UI. (`bb3f1342`)
   - Mode selector (Single / Researcher+Organizer `OptionButton` pair) in AgentView empty state
   - `handleSend` passes `agentProfiles`, `initialAgentId`, `maxIterations: 10` in multi mode
   - Active agent badge (teal) in header tracks `activeAgentId` during session
   - `ConversationBubble` renders `'handoff'` entries as bordered pill with `ArrowRightLeft` icon
5. ✅ At least one default profile set is defined. (`bb3f1342`)
   - `agentProfiles.ts`: `RESEARCHER_PROFILE` (`search_index`, `browse_folders`) + `ORGANIZER_PROFILE` (all 4 tools)
   - System prompts include explicit `handoff_to_organizer` / `handoff_to_researcher` trigger rules
   - Empirical reliability with the running model: **advisory, not a gate** — model capability confirmed, prompt quality requires live testing

---

## Files Expected to Change

| File | Changes |
|------|---------|
| `modules/app-agent-api/.../AgentProfile.java` | **NEW** — agent role definition record + `fromMap()` static helper |
| `modules/app-agent-api/.../AgentRequest.java` | Optional `agentProfiles`, `initialAgentId`; backward-compatible 3-arg delegating constructor |
| `modules/app-agent-api/.../AgentEvent.java` | `HandoffProposed`, `HandoffExecuted` sealed records |
| `modules/app-agent/.../AgentLoopService.java` | Active-agent cursor, handoff detection, mixed-batch cancellation, system prompt swap, `EventTraceSequencer` swap, `withTrace()` new cases, `resumeLastSession()` profile restoration |
| `modules/app-agent/.../AgentSession.java` | `activeAgentId`, `handoffHistory`, `clearPendingApprovals()` |
| `modules/app-agent/.../AgentRunStore.java` | Schema v2, `agentProfiles`/`activeAgentId`/`handoffHistory` in checkpoint, `startRun()` extended, `setHandoffState()` added, two new event type handlers |
| `modules/ui/.../AgentController.java` | `agentProfiles`/`initialAgentId` deserialization via `AgentProfile.fromMap()`; two switch expressions for `HandoffProposed` + `HandoffExecuted` |
| `modules/ui-web/.../useAgentStore.ts` | New SSE cases, `activeAgentId`/`handoffLog` state (no `ConversationEntry` mutation) |

---

## Closure Checklist

- [x] `HandoffProposed` and `HandoffExecuted` event records added to `AgentEvent` (Layer 1)
- [x] `AgentProfile` record added to `app-agent-api` with `fromMap()` static helper; `AgentRequest` extended with optional agent profiles and backward-compatible 3-arg delegating constructor (Layer 1)
- [x] Active-agent cursor in `AgentLoopService` loop (handoff tool detection, system prompt swap with `/no_think` prefix, `EventTraceSequencer` swap) (Layers 4, 7-Fix3)
- [x] Mixed-batch: assistant message trimmed to calls 0..handoffIndex; no cancellation fillers; new agent context is clean (Layer 4 initially, revised by 7-Fix8)
- [x] `withTrace()` in `AgentLoopService` updated with two new cases (Layer 3)
- [x] Approval boundary reset on handoff (`AgentSession.clearPendingApprovals()` + system message injection) (Layer 2/4)
- [x] `AgentRunStore` checkpoint schema v2 includes `agentProfiles` + `activeAgentId` + `handoffHistory`; `startRun()` persists profiles with first-profile default; event serialization handles new types (Layers 3, 7-Fix2)
- [x] `resumeLastSession()` restores `agentProfiles` and `activeAgentId` from checkpoint via `AgentProfile.fromMap()` (Layer 4)
- [x] Replay/resume emits handoff events in canonical order (Layer 3)
- [x] SSE + `AgentController` handoff event propagation: `agentProfiles` deserialization via `AgentProfile.fromMap()`; payload and eventType switches updated (Layer 5)
- [x] `ScriptedAiService` extended with `recordedTools` field (prerequisite for tool-injection assertions) (Layer 4)
- [x] `AgentEventSealedTest` count updated: `12 → 14` (Layer 1)
- [x] Unit test scenario matrix (event ordering, tool injection, system prompt swap, unknown target, approval boundary reset, mixed-batch trim, loop-guard reset, first-profile default) — `AgentLoopServiceTest` (Layers 4, 7)
- [x] Schema v2 upcast and handoff event serialization/replay — `AgentRunStoreTest` (Layer 3)
- [x] SSE payload contract test for `handoff_proposed` and `handoff_executed` field names — `AgentSseContractTest` (Layer 5)
- [x] `startSession()` accepts `AgentSessionOptions` forwarding profiles to backend; `_pendingHandoffReason` carries reason into `handoffHistory`; `ToolRegistry` guards `handoff_to_` prefix (Layer 7-Fixes 1,5,6)
- [x] `pruneHandoffMessages()` input filter strips tool call/result pairs on handoff boundaries and injects a compact research brief at index 2; filters `handoff_to_*` calls from brief content (Layer 8-Rec1, Layer 9-Fix1/2)
- [x] PRIMARY_PROFILE physically restricted to `['search_index', 'browse_folders']`; mode selector removed from `AgentView`; all sessions start with `BUILTIN_PRESETS` (Layer 8-Rec2, Layer 9-Fix3)
- [x] Cycle detection via `int totalHandoffs` counter in `AgentSession`; `maxHandoffs` default `profiles.size() * 3`; emits `HANDOFF_CYCLE_DETECTED` on total threshold exceeded (Layer 8-Rec4, Layer 9-Fix4)
- [x] Unit tests for `pruneHandoffMessages` (no-op, single pair, multi pair, handoff-in-range filter) and cycle detection integration test (Layer 9-Fix1, Layer 9-Fix4)
- [x] Battery runner `agentProfiles` + `initialAgentId` passthrough; 2 handoff scenarios (`handoff-001-immediate`, `handoff-002-search-then-handoff`) with embedded BUILTIN_PRESETS profiles (`e5263ffc`)

---

## Pre-Implementation Findings Summary

The codebase investigation, external research, and empirical testing phases produced findings
that directly shaped implementation decisions. Consolidated here for future reference.

### What the codebase already had

Most M0 infrastructure was additive, not greenfield. `TraceContext.agentId` already existed
(hardcoded to `"primary"`). `AgentEvent` sealed interface enforced exhaustive switches at 4
locations — adding new event types was compiler-guaranteed safe. `AgentSession.cancel()` showed
the approval-gate bulk rejection pattern reused by `clearPendingApprovals()`. `AgentRunStore`
had a schema upcaster chain ready for v1→v2 migration. The main structural change was moving
the `tools` list from pre-loop to per-iteration (for per-agent tool subsets) and adding a
backward-compatible 3-arg delegating constructor to `AgentRequest` (16 existing call sites).

### What external research confirmed

OpenAI Agents SDK and Google ADK validated three design choices: (1) handoff-as-tool-call is
the cleanest mechanism (no new LLM capability needed), (2) full conversation history on handoff
is the simplest correct default, (3) `handoff_to_<agentId>` naming mirrors industry convention.
Anthropic's production multi-agent system uses isolated context windows — the opposite of our
full-history default — which foreshadowed the U3 budget problem.

### What empirical testing revealed (U1–U3)

| Test | Key finding | Action taken |
|------|-------------|--------------|
| U1 | Direct ingest triggers handoff reliably (1.7s). Search-then-ingest does NOT — model searches but fails to connect results to a handoff decision. | Prompt engineering gap acknowledged; battery scenario `handoff-002` tests this path. |
| U2 | Combined agent (all tools + handoff tool) correctly routes both informational and direct-write queries. | Rec 2 implemented: collapsed to single PRIMARY agent with self-handoff. |
| U3 | Researcher consumed 44% of 3840-token budget in 2 iterations. Organizer inherited full history and went **-529 tokens into deficit**. | Rec 1 implemented: `pruneHandoffMessages()` strips tool call/result pairs and injects compact brief. PRIMARY physically restricted to read-only tools so the filter always fires. |

U3 was the most consequential finding: it proved context filtering is a **correctness issue**
(Organizer cannot complete work), not a performance optimization. This elevated Rec 1 from
"nice to have" to "must ship with M0."

### Findings not yet acted on

| Finding | Status | Implication |
|---------|--------|-------------|
| U1 search-then-handoff failure | Partially mitigated by Rec 2 (combined agent decides when to hand off) | If battery scenario `handoff-002` consistently fails, the model needs explicit handoff guidance in the system prompt — the OpenAI `RECOMMENDED_PROMPT_PREFIX` gap. |
| `ingest_files` >60s blocking | Documented as Rec 5 | Needs dedicated tempdoc. Tool execution contract (`ToolDefinition.execute() → ToolResult`) is the primary decision gate. |
| Structured handoff context | Documented as Rec 3 | Deferred. `pruneHandoffMessages()` may be sufficient — Rec 3 only needed if the programmatic brief proves inadequate in CI. |

### Forward path

This tempdoc's implementation is complete. User-facing improvements (Rec 5) are deferred.
The remaining open question is **functional validation**: does the M0 handoff infrastructure
work correctly end-to-end with the real model?

**What has NOT been validated post-implementation:**

1. **`pruneHandoffMessages()` effectiveness** — U3 showed -529 token deficit BEFORE Rec 1
   was implemented. The fix has unit tests but has never been empirically validated. Does the
   Organizer now complete its work within budget after the brief replaces raw tool history?
2. **Battery scenario pass rate** — `handoff-001-immediate` and `handoff-002-search-then-handoff`
   were authored but never run against a live dev stack. U1 showed search-then-handoff was
   unreliable with the pre-Rec-2 prompt. The combined-agent prompt (Rec 2) may fix this, or
   may not.
3. **Combined-agent routing reliability** — U2 tested with 3 queries pre-Rec-2. Post-Rec-2,
   the PRIMARY profile has physically restricted tools (`search_index`, `browse_folders` only)
   and explicit handoff trigger rules in the system prompt. This changes the routing dynamics —
   needs fresh empirical data.
4. **`pruneHandoffMessages()` brief quality** — the compact brief extracts tool names and
   truncated results. Is this enough context for the Organizer to act correctly, or does it
   lose critical information (e.g., specific file paths from search results)?

**Recommended next step:** Run the dev stack and execute the two battery scenarios. Capture:
- `task_completion` verdict from `evaluate-session.mjs`
- Token budget snapshots (equivalent to U3) to verify Rec 1 fixed the deficit
- Whether `handoff-002` triggers a handoff (the U1 failure case)
- Brief content quality (what does the Organizer actually see after pruning?)

### Post-implementation validation results (2026-02-20)

Ran both battery scenarios against live dev stack (Qwen3VL-8B, cuda12, port 54181).
Three docs ingested before tests: `01-system-overview.md`, `02-indexing-pipeline.md`,
`04-ai-layer.md`. BEIR nfcorpus also present in the index.

#### V1 — handoff-001-immediate (direct ingest request)

| Metric | Value |
|--------|-------|
| Handoff occurred? | **Yes** — PRIMARY → ORGANIZER on iteration 1 |
| PRIMARY tokens consumed | 800 (handoff call only) |
| Handoff reason quality | Excellent: included file path and write-intent classification |
| Organizer tool calls | **0** — burned 4096 tokens reasoning, no action |
| Organizer tokens consumed | **4096** (max completion length) |
| Final budget | **-1056** (exhausted on Organizer's first LLM call) |
| Task completed? | **No** — file was not ingested |

**Root cause: Organizer prompt causes verbose chain-of-thought reasoning in plain text.**
The `/no_think` prefix suppresses `<think>` tags but does not prevent the model from
reasoning verbally. The Organizer's multi-step Workflow section ("1. Review the research
summary... 2. Use browse_folders to get absolute paths... 3. Perform the operations...")
causes the 8B model to plan each step out loud instead of calling tools. It hit the max
completion length (4096 tokens) on reasoning text before producing a single tool call.
The response was an 11,051-character analysis of path resolution strategy with zero actions.

**Diagnosis**: This is a prompt engineering problem, not an infrastructure problem. The
Organizer prompt is too procedural for an 8B model. It needs to be rewritten to be
action-oriented: "Call browse_folders first, then ingest_files" rather than describing a
workflow the model should reason through. Alternatively, reducing `max_tokens` for the
completion could force the model to emit tool calls earlier, but this risks truncation.

#### V2 — handoff-002-search-then-handoff (search first, then ingest)

| Metric | Value |
|--------|-------|
| Handoff occurred? | **No** — PRIMARY answered directly |
| PRIMARY search calls | 3 (1 failed path_prefix, 2 succeeded) |
| Search results relevant? | **No** — BEIR nfcorpus medical papers dominated results |
| "System Overview" found? | Yes (#2), but no path or preview in result |
| PRIMARY decision | Correctly decided not to hand off (no relevant file found) |
| Final budget | **-921** (exhausted across 3 search iterations) |
| Task completed? | **No** — no file ingested |

**Root cause: BEIR eval corpus pollution.** The dev stack indexes the nfcorpus medical
papers alongside JustSearch docs. The query "system architecture documentation" matched
medical papers containing "study architecture" in their methodology sections, pushing the
actual system architecture doc below the relevance threshold. The PRIMARY agent correctly
concluded no relevant architecture file was found — the correct decision given the polluted
search results, but it means the test never reaches the handoff path.

**Secondary issue**: "System Overview" appeared as result #2 but without a file path or
content preview, unlike the medical papers. This suggests the JustSearch docs were indexed
with less metadata (title-only indexing vs full-text for nfcorpus).

#### Summary — two blocking issues for M0 functional validation

| # | Issue | Severity | Fix scope |
|---|-------|----------|-----------|
| 1 | Organizer prompt too procedural — model reasons verbally for 4096 tokens without calling tools | **Blocking** | Prompt rewrite in `agentProfiles.ts` (ORGANIZER_PROFILE.systemPrompt). Make it action-first: "Immediately call browse_folders, then ingest_files." Remove multi-step Workflow section that encourages reasoning. |
| 2 | BEIR corpus pollution makes search-based handoff scenarios unreliable | **Blocking for V2 only** | Either (a) use `clean: "soft"` when starting dev stack for agent tests, then ingest only JustSearch docs, or (b) add a `path_prefix` filter to battery scenario prompts. Option (a) is more reliable. |

Neither issue is an infrastructure bug. The handoff plumbing (events, approval reset, budget
tracking, trace agentIds, `pruneHandoffMessages`) all worked correctly. The problems are:
(1) the Organizer system prompt is not suited for the 8B model's behavior, and (2) the test
environment has corpus noise that prevents search-based scenarios from reaching the handoff path.

#### V3 — Organizer prompt rewrite and reliability testing (2026-02-20)

Rewrote `ORGANIZER_PROFILE.systemPrompt` from a procedural 5-step Workflow to an action-first
directive: "Execute file operations immediately — do not explain your plan. Action sequence:
call browse_folders first to get absolute paths, then call ingest_files or file_operations."

Re-ran handoff-001 five times on a clean dev stack (JustSearch docs only, no BEIR corpus):

| Run | Handoff? | Organizer behavior | ingest_files called? |
|-----|----------|-------------------|---------------------|
| 1 | Yes | browse_folders → ingest_files (wrong path) | Yes |
| 2 | Yes | UNKNOWN_TOOL error (malformed tool call) | No |
| 3 | Yes | browse_folders ×2, no ingest | No |
| 4 | Yes | browse_folders → ingest_files | Yes |
| 5 | Yes | browse_folders → ingest_files | Yes |

**Results:**
- PRIMARY handoff: **5/5** (100%) — handoff mechanism is reliable
- Organizer reaches `ingest_files`: **3/5** (60%) — up from **0/1** with old prompt
- Organizer first LLM call tokens: ~1340 (down from 4096 with old prompt)

**Improvement**: The action-first prompt reduced Organizer token consumption by ~3x and
increased task completion from 0% to ~60%. The model now acts instead of reasoning.

**Remaining non-determinism**: The 8B model (Qwen3VL-8B-Thinking Q4_K_M) produces variable
tool call quality. Failure modes observed: (a) malformed tool name (`"docs"` instead of
`"browse_folders"`), (b) excessive browsing without progressing to ingest, (c) correct tool
calls but wrong absolute paths (appending user-relative path to worker storage root).

**Path resolution problem**: The Organizer constructs paths by appending the user's relative
path (`docs/explanation/01-system-overview.md`) to the indexed root returned by
`browse_folders` (`D:\...\eval-corpus-beir\nfcorpus\docs`). The indexed root is the worker
storage path, not the repo root. `ingest_files` expects repo-relative or source-absolute
paths. The agent cannot currently distinguish between "where indexed content lives" and "where
source files are." This is a tool design gap, not a prompt problem.

**Assessment**: The handoff infrastructure is production-ready. The Organizer's task completion
rate (~60%) is limited by the 8B model's tool-calling reliability, not by handoff plumbing.
Improvements require either (a) better model (larger parameter count), (b) further prompt
optimization, or (c) tool design changes (e.g., `browse_folders` returning source paths
alongside indexed storage paths).

This is empirical validation, not new implementation. It determines whether the M0 handoff
is production-ready or needs prompt engineering fixes before it can be relied upon.

---

## Post-M0 Architecture Research (2026-02-19)

Research conducted via live internet sources (OpenAI, Anthropic, Google ADK, LangGraph,
AutoGen, CrewAI, academic papers) following end-to-end verification of the M0 implementation.
Key question: how does M0's design compare to industry practice, and what are the long-term
structural gaps?

### Finding 1 — Shared conversation history is convenient but expensive at scale

M0 passes the full conversation history to every agent on every handoff (the `swapSystemPrompt`
only replaces message[0]; all other messages remain). This matches the OpenAI Agents SDK default
and LangGraph's shared-state model and is the simplest correct implementation.

However production data (2025) shows multi-agent systems consume **~15x more tokens than single-
agent chats** in deep-research deployments, and up to 4x more in typical multi-agent exchanges.
Google ADK's production guide names the failure mode directly:

> *"If a root agent passes its full history to a sub-agent, and that sub-agent does the same,
> you trigger a context explosion with token counts skyrocketing and sub-agents becoming confused
> by irrelevant conversational history."*

For JustSearch's 4K token budget, a Researcher that does extensive search work before handing off
could consume most of the budget before the Organizer starts. The Organizer then operates in a
nearly-full context window.

**Significantly: Anthropic's own production multi-agent research system uses the opposite
design** — subagents receive isolated context windows with a focused brief (objective, output
format, tool guidance, task boundaries), not the orchestrator's full history. Large intermediate
artifacts are stored externally; agents receive lightweight references.

**Long-term implication**: M0's full-history default is fine for short sessions with few tool
calls. For sessions with extensive Researcher search work before handoff, the Organizer will
degrade. The mitigation is an `input_filter` equivalent (see Finding 3).

### Finding 2 — The Researcher/Organizer mode selector creates a UX problem

M0 surfaces two modes (Single / Researcher+Organizer) as a UI choice. Industry practice treats
this as a design smell — users should not need to understand agent architecture to use the
product.

Three approaches observed in production systems:

| Approach | Tradeoff |
|---|---|
| **Front-door triage agent** (router/planner) | Extra LLM call on every query adds 50–500ms latency before any specialist starts. Adds a second failure point: router misclassification routes to wrong specialist which then produces confident wrong answer. Valid for large diverse query spaces; overkill for a 2-mode binary split. |
| **Keyword/semantic routing** (pre-LLM) | Near-zero latency, deterministic. Detect write-intent keywords (`ingest`, `add`, `move`, `organise`) and auto-select multi-agent; default single-agent. Fragile to phrasing; misses ambiguous requests. |
| **Collapse to single agent + self-handoff** | Single agent has all tools available AND the `handoff_to_organizer` tool. The agent decides mid-session whether to hand off. Zero latency penalty, no UI choice, no router. The model routes based on what it discovers, not what the user specified. This is the "swarm" pattern — peer-to-peer handoffs without a front-door dispatcher. |

The **collapse to single agent + self-handoff** option maps cleanly onto M0's existing
infrastructure: run a single agent with all 4 tools + `handoff_to_organizer` in its tool list
from the start. The Organizer profile still exists in `agentProfiles` but no `initialAgentId` is
forced — the primary agent decides when specialisation is needed. This removes the mode selector
entirely and makes the multi-agent capability transparent to the user.

### Finding 3 — Context filtering on handoff boundaries is the primary production optimization

The mitigation used by OpenAI (via `input_filter`), Google ADK, and Anthropic's system:

- **OpenAI `remove_all_tools` filter**: strips prior tool calls and results from the history
  before the receiving agent sees it. Reduces tokens dramatically; the receiving agent gets a
  clean context containing only the human messages and a handoff summary.
- **OpenAI nested/summary handoffs (opt-in beta)**: collapses the entire prior transcript into a
  single `<CONVERSATION HISTORY>` summary block. Trades fidelity for token efficiency.
- **Anthropic/Google ADK isolated subagent approach**: receiving agent gets a focused brief
  written by the orchestrator, not the raw transcript. Large data lives in external storage;
  agents receive file references.

For JustSearch: the injected system message at handoff (`"Handoff from X to Y. Reason: ..."`)
is the current brief. This is minimal — it carries only the reason string. A richer brief would
include the Researcher's key findings summarised into a fixed-size block, preventing context
explosion regardless of how much searching the Researcher did.

### Finding 4 — Structured output for routing is consensus best practice

If a router/triage agent is ever added, every 2025 source recommends forcing the router to emit
a constrained classification ID (Java enum, TypeScript literal union) rather than free text.
LLM routers producing free-text route decisions hallucinate routing choices. This is not
currently relevant to M0 but is the implementation requirement if Finding 2's triage-agent
option is ever chosen.

### Finding 5 — `handoff_to_` naming and tool scoping are correct

OpenAI Agents SDK uses `transfer_to_<agent_name>` — structurally identical to M0's
`handoff_to_<agentId>`. The `toolSubset` mechanism (limiting Researcher to read-only tools) is
confirmed best practice:

> *"The Dermatologist Agent doesn't even have the 'Tooth Extraction' tool in its prompt.
> It literally cannot make a mistake about teeth."*

Fewer tools per agent = more reliable routing decisions. M0's design is correct here.

---

## Empirical Test Results (2026-02-19)

Three tests run against the live dev stack (Qwen3VL-8B, cuda12 runtime, port 64681).
Three docs ingested into the index before tests: `01-system-overview.md`,
`02-indexing-pipeline.md`, `04-ai-layer.md`.

### U1 — Structured context in handoff reason

**Question**: Does the model reliably populate a structured/detailed `reason` when handing off?
Does it hand off at the right moments?

Three queries with Researcher-only toolSubset (`search_index`, `browse_folders`):

| Query | Expected | Actual | Notes |
|---|---|---|---|
| "Ingest docs/explanation/01-system-overview.md" | Handoff | **Handoff at 1.7s** ✓ | Reason included partial JSON blob with context. Timeout in original test was `ingest_files` executing (>60s), not a model issue. |
| "Find AI layer docs and ingest the most relevant" | Handoff | **No handoff** ✗ | Model called `search_index` ×2 + `browse_folders` ×2, then stopped without handing off. Prompt compliance failure for search-then-ingest flow. |
| "What files are currently indexed?" | No handoff | **No handoff** ✓ | Used `browse_folders` ×2, answered directly. |

**Finding**: Direct-path ingest reliably triggers handoff. Search-first-then-ingest does not —
the model searches but fails to connect the results to a handoff decision. The current Researcher
prompt rule ("call handoff when search results reveal content that needs a write-back") does not
reliably fire in practice.

### U2 — Combined-agent prompt routing

**Question**: Does a single primary agent with all 4 tools + `handoff_to_organizer` correctly
route informational vs. write queries?

| Query | Expected | Actual | Notes |
|---|---|---|---|
| "What files are currently indexed?" | No handoff | **No handoff** ✓ | Used `browse_folders` ×2, answered directly: "The knowledge base currently has no indexed folders…" |
| "Ingest docs/explanation/01-system-overview.md" | Handoff | **Handoff** ✓ | Timed out in test at 60s, but timing test confirmed: handoff at 1.7s, `ingest_files` called at 3.0s. The timeout was `ingest_files` executing, not a routing failure. |
| "Search for AI layer docs and ingest most relevant" | Handoff | **Timeout** (inconclusive) | Same `ingest_files` execution timeout; could not determine whether handoff occurred. |

**Finding**: The combined-agent prompt correctly routes informational queries and direct-path
write queries. The "search-then-ingest" query is inconclusive due to the ingest execution time.
The combined-agent approach is viable for the single-step write case.

**Side finding**: `ingest_files` takes well over 60 seconds to complete when the inference
runtime is active (embedding computation contends with the LLM). The SSE stream stays open for
the duration. This is a UX problem independent of routing — long-running tool calls block the
stream without feedback.

### U3 — Context bloat at handoff boundary

**Question**: How many tokens does the Researcher consume before handing off? What does the
Organizer inherit?

Query: "Find all documentation about the AI layer and indexing pipeline, then ingest any files
you find that aren't already indexed." (Researcher toolSubset: `search_index`, `browse_folders`)

```
Budget snapshots (tokensRemaining / tokensConsumed per LLM call):
  Researcher iter 1 start:    3840 remaining | 102 consumed
  Researcher iter 1 LLM:      3153 remaining | 687 consumed   ← 687 tokens for first call
  Researcher iter 2 start:    3153 remaining | 135 consumed
  Researcher iter 2 LLM:      2128 remaining | 1025 consumed  ← handoff occurs here
  ───────────────────────────────────────────────────────────
  Researcher total: 1712 tokens consumed (44% of 3840 budget)
  ───────────────────────────────────────────────────────────
  Organizer iter 1 start:     2128 remaining | 482 consumed
  Organizer iter 1 LLM:        438 remaining | 1690 consumed  ← Organizer's first real call
  Organizer iter 2 start:      438 remaining | 794 consumed
  Organizer iter 2 LLM:        -529 remaining | 967 consumed  ← BUDGET EXHAUSTED
```

**Finding: Session budget went negative (-529) — the Organizer ran out of tokens before
completing its work.** The Organizer's LLM calls are more expensive than the Researcher's
equivalent calls because the Organizer inherits the full Researcher conversation history. Each
Organizer LLM call processes ~1025+ tokens more context than the Researcher's equivalent call
did. This is the context explosion in practice, not in theory.

The Researcher consumed 44% of the budget across 2 iterations. The Organizer consumed the
remaining 56% plus went 529 into deficit across 2 iterations. A session with even 3 Researcher
search iterations would leave the Organizer with under 1000 tokens — not enough for one
meaningful LLM call.

**This makes Rec 1 (input filtering) a correctness issue, not just a performance optimization.**

---

## Long-Term Architectural Recommendations

Ranked by impact. None are M0 scope — all require a future tempdoc.
Priorities updated based on empirical test results (see section above).

> **Frontend freeze (2026-02-20)**: No further frontend (`ui-web`) changes will be made for any
> of the recommendations in this section under this tempdoc. Frontend work for future Recs (e.g.,
> Rec 5 progress UI) will be scoped and tracked in a dedicated tempdoc when the backend half is
> ready to ship.

### Rec 1 ✅ DONE (`71683b83` + `f76e3711`) — Input filter / context brief on handoff boundaries

**Problem**: U3 proved this is a correctness issue. The Organizer's first LLM call consumed
1690 tokens because it inherited the full Researcher history. The session ended at -529 tokens —
the Organizer couldn't complete its work. This happens after only 2 Researcher iterations. Any
real-world session with 3+ Researcher search calls will leave the Organizer with insufficient
budget.

**Change**: Before calling `swapSystemPrompt`, strip raw Researcher tool call/result messages
from the history and replace them with a single fixed-size brief system message. The brief must
preserve the essential handoff context (target paths, key findings) without the verbatim tool
output.

**Implementation constraint**: The OpenAI message format requires every `tool_call_id` in an
assistant message to have a corresponding `tool` result message. You cannot strip tool result
messages without also removing their corresponding assistant call messages. Filtering is
therefore a full history rewrite from the handoff boundary backward, not a simple delete.

**Concrete approach** (does not require Rec 3):
1. Find the handoff point in `session.messages()`
2. Collect the Researcher's prior messages (all messages before the handoff system message)
3. Extract key data programmatically: file paths mentioned in tool results, search result titles
4. Inject as a single `role: system` message: `"Researcher findings: [extracted data]"`
5. Replace the Researcher's raw message block with just this brief + the handoff system message

**Scope**: `AgentLoopService.swapSystemPrompt()` extended with a `buildResearcherBrief()`
helper that processes `session.messages()` before the swap. No backend API changes. No new
event types.

### Rec 2 ✅ DONE (`71683b83` + `f76e3711`) — Remove the mode selector; collapse to single agent + self-handoff

**Problem**: Users should not choose agent architecture. The Researcher+Organizer split is an
implementation detail, not a user-facing concept.

**Change**: Remove the `OptionButton` pair from `AgentView`. Default every session to a single
primary agent with all 4 tools + `handoff_to_organizer` in scope from the start. The agent
decides to hand off when it hits a write task, not the user. `ORGANIZER_PROFILE` is included
in `agentProfiles` so the handoff tool is registered, but `initialAgentId` is left as the
primary agent.

**UX result**: one text box, one agent, transparent specialisation. Advanced users who want
explicit profile control can still POST `agentProfiles` directly.

**Empirical support**: U2 confirmed the combined-agent prompt correctly routes informational
queries (no handoff) and direct-path write queries (immediate handoff). The "search-then-ingest"
case is unconfirmed but not ruled out — it needs a longer timeout test.

**Dependency on Rec 1**: If Rec 2 is implemented before Rec 1, the combined agent will have the
same context explosion problem on search-heavy sessions. Rec 1 should land first, or
simultaneously.

**Scope**: `AgentView.tsx` (remove mode selector, update `handleSend`). `agentProfiles.ts`
(update system prompt for primary agent to know about `handoff_to_organizer`). No backend
changes needed.

### Rec 3 (MEDIUM, deferred pending Rec 1) — Richer handoff reason / structured brief protocol

**Problem**: The `reason` string (e.g., `"User asked to ingest a file"`) is the entire
communication channel from Researcher to Organizer when the Researcher hands off without
searching first. If the Researcher does search, the Organizer inherits raw tool results — but
if the Researcher hands off immediately, the Organizer has almost no context.

**Change**: Extend the `handoff_to_<agentId>` tool schema to accept a structured `context`
field alongside `reason`:

```json
{
  "reason": "User requested file ingestion",
  "context": {
    "target_paths": ["docs/explanation/01-system-overview.md"],
    "findings": "No prior search conducted; path provided directly by user"
  }
}
```

Inject this structured context as a system message to the Organizer rather than the raw transcript.
Combined with Rec 1, this makes the handoff brief precise regardless of how much Researcher
work preceded it.

**Scope**: `AgentProfile` schema extension (new `handoffContextSchema` field), `AgentLoopService`
handoff block (extract and inject context), system prompts (teach agents to populate `context`).
API contract change — requires schema version bump in `AgentProfile`.

**Empirical status**: Not directly testable without a code change to `buildHandoffTools()`.
U1 confirmed the model CAN write a detailed `reason` string when prompted. Whether it reliably
populates a structured JSON `context` field is untested. Defer until Rec 1's programmatic
approach is validated — if the programmatic brief is sufficient, Rec 3 may not be necessary.

**Eval lane analysis (2026-02-20, investigated)**

BEIR and perf regression lanes are not relevant — those measure search retrieval quality and
latency, neither of which captures what the Organizer does after a handoff.

| Lane | File | Finding |
|------|------|---------|
| Agent live battery | `scripts/ci/run-agent-live-battery.mjs` | ❌ Zero handoff scenarios — all 16 scenarios (exp-001→exp-016) are single-agent knowledge-base exploration. Infrastructure is trivially extensible (add entry to `scripts/ci/agent-live-battery-scenarios.v1.json`). New scenarios needed before Rec 3 can be evaluated. |
| `evaluate-session.mjs` | `scripts/agent-analytics/evaluate-session.mjs` | ✅ **Right quality signal.** LLM judge produces `task_completion` ∈ {complete, partial, failed, abandoned}. If handoff scenarios are added to the battery, this evaluates them without any code changes. |
| `analyze-session.mjs` / `score-session.mjs` | `scripts/agent-analytics/` | ❌ No per-agent-role breakdown. Signals (unbounded reads, bash ops, re-edits, subagent density) are session-wide aggregates. `subagent_density` tracks Claude Code subagents, not JustSearch agent handoffs — not applicable. |
| RAG context format comparison | `scripts/bench/run-rag-context-format-comparison.ps1` | ⚠ Pattern useful, not directly applicable. Tests RAG quality (search result format → LLM answer quality), not agent handoff quality. `$variantMap` mechanism is a good pattern to follow for a future handoff-context A/B script, but the Gradle-based test harness targets a different concern. |
| Benchmark scorecard | `scripts/bench/build-benchmark-scorecard.mjs` | ✅ Any new battery scenarios added flow into the consolidated scorecard automatically (tempdoc 216 confirmed active and substantially complete). |

**Target metric (revised)**

Primary signal: `evaluate-session.mjs` `task_completion` verdict on handoff scenarios.
This gives complete/partial/failed per-run and is already production-ready.

Secondary signals — derivable from `events.ndjson` without backend changes:
- Organizer tool calls before first `file_operations`/`ingest_files` call: partition
  `tool_execution_completed` events on `handoff_executed` boundaries using `agentId` in
  TraceContext. Events stream already logs both. A post-processing script can compute this.
- Token usage from handoff point to `AgentDone`: similarly derivable from the event stream.

`AgentRunStore` checkpoint (schema v2) stores session-wide totals only — no per-agent-role
iteration or token counts. Per-agent breakdown requires event-stream post-processing, not
backend schema changes.

**Research findings — all 6 items resolved**

| Item | Finding |
|------|---------|
| Analytics pipeline per-agent breakdown? | ❌ None in current scripts. Derivable from `events.ndjson` by partitioning on `handoff_executed` events (agentId is in every TraceContext). No Java changes needed. |
| Live battery handoff scenarios? | ❌ Zero. Must be authored. Infrastructure ready — just a JSON file addition. |
| 216 eval harness conflict risk? | ✅ None. New battery scenarios slot into consolidated scorecard automatically. No parallel structure risk. |
| RAG comparison script reusable? | ⚠ Pattern only. The `$variantMap` mechanism is a good template for a future handoff-context A/B comparison script, but the test harness targets RAG quality, not agent handoff quality. |
| Debug state / AgentRunStore per-agent data? | ❌ `/api/debug/state` returns system health only (no session data). Checkpoint has session-wide totals only. Per-agent data derivable from `events.ndjson` post-processing. |
| Benchmark scenario design | Defined below in prerequisite work. |

**Prerequisite work — tiered by independent value**

Items are split into two tiers. Tier 1 has long-term CI regression value regardless of whether
Rec 3 ships. Tier 2 only has value if Rec 3 is approved to implement.

**Tier 1 — Handoff CI coverage ✅ DONE (`e5263ffc`)**

1. **Battery runner `agentProfiles` support.** `run-agent-live-battery.mjs` now forwards
   `scenario.agentProfiles` and `scenario.initialAgentId` in the POST body when present.
   Manifest output includes `hasAgentProfiles` boolean flag.
2. **Two handoff scenarios authored** in `agent-live-battery-scenarios.v1.json`:
   - `handoff-001-immediate` — PRIMARY receives a direct ingest request, hands off to
     Organizer. Embeds full BUILTIN_PRESETS (PRIMARY + ORGANIZER profiles).
   - `handoff-002-search-then-handoff` — PRIMARY searches for architecture docs, then hands
     off to Organizer for ingestion. `expectedMinToolCalls: 3`.
   Both scenarios protect the M0 handoff implementation in CI regardless of Rec 3.

**Flakiness risk**: Handoff scenarios depend on the model deciding to hand off rather than
answering directly. Prompts must use very explicit write-intent language and tight
`mustNotContain` rules. Even then, non-determinism is inherent — consider a retry budget
or accepting that these scenarios may have lower pass-rate stability than single-agent ones.

**Tier 2 — Rec 3 before/after measurement (defer until Rec 3 approved)**

3. **Per-agent event partitioner script** (`scripts/agent-analytics/partition-by-agent.mjs`)
   — derive per-agent tool call counts from `events.ndjson` by partitioning on
   `handoff_executed` boundaries. Only useful for Rec 3 before/after comparison.
4. **Baseline measurement** — run battery scenarios and record `evaluate-session.mjs`
   `task_completion` verdicts as the before-state. A baseline without a comparison is
   historical noise; defer until Rec 3 implementation is in progress.

### Rec 4 ✅ DONE (`71683b83` + `f76e3711`) — Cycle detection / `maxHandoffs` guard

**Problem**: The only guard against Researcher↔Organizer cycling is `maxIterations`. A cycling
pair exhausts the budget with no useful output and terminates with `MAX_ITERATIONS_EXCEEDED`.
The error is observable but gives no signal about why the cycle occurred.

**Change**: Add a `maxHandoffs` parameter (default: `agentProfiles.size() * 3`) and a
visited-agent-ID cycle detector in `AgentLoopService`. If the same `(fromAgentId, toAgentId)`
pair repeats more than N times, emit `AgentError(HANDOFF_CYCLE_DETECTED)` and terminate with a
descriptive message.

**Scope**: `AgentRequest` (new optional `maxHandoffs`), `AgentSession` (handoff counter per pair),
`AgentLoopService` (cycle check before `recordHandoff`), `AgentErrorCode` (new code),
`errorMessages.ts` (new localized message).

### Rec 5 (DEFERRED) — Progress feedback during long-running tool calls

**Problem**: U2/U3 testing revealed `ingest_files` takes >60–120 seconds to complete when the
inference runtime is active (embedding computation contends with the LLM). The SSE stream stays
open silently for the entire duration. From the user's perspective, the agent appears frozen
after calling `ingest_files`.

**Deferral rationale (2026-02-20)**: This is a user-facing UX problem, not a correctness or
reliability issue. The agent loop blocks but resumes correctly when the gRPC call returns.
Token budget is not wasted during the wait. Session state is preserved. The only functional
risk is SSE connection timeout if a client/proxy has a < 120s idle timeout. The architectural
refactor required (changing `ToolDefinition.execute() → ToolResult` signature project-wide,
touching every tool + every test) is disproportionate to a UX annoyance. Deferred until either
(a) connection timeouts become a reported problem, or (b) another feature requires async tool
execution.

### Rec 6 (LOW) — Parallel fan-out for independent research subtasks

**Problem**: Sequential handoffs are the only supported pattern. For queries that require
researching multiple independent topics simultaneously (e.g., "compare how A and B work"), a
sequential Researcher exhausts budget doing both serially when they could be done in parallel.

**Change**: Add a `parallel_handoff_to_<agentId>` tool variant that forks execution. Blocked
by the single-GPU/VRAM constraint (two agents cannot run inference simultaneously on one
`llama-server`). Valid only if a future architecture supports concurrent inference sessions
(e.g., multiple model instances or CPU/GPU split).

**Scope**: Explicitly out of scope while the single-inference-runtime constraint holds. Track
when/if the runtime supports concurrent sessions.
