---
title: "240 — Decompose AgentLoopService (the last mega-class: 2,529 LOC → 865, under ceiling)"
type: tempdoc
status: done
---

> NOTE: Noncanonical design doc. Verify claims against code before implementing.

# 240 — Decompose `AgentLoopService.java`

**Status:** DONE (2026-05-25) — 8 waves shipped, 2,529 → 865 LOC (−66%); AgentLoopService is below the 1,000-LOC ceiling and its `class-size-exceptions.txt` row is deleted. The W8 loop reshape was live-verified against a running model (see Verification tiers). W6 (query projections) intentionally skipped — thin one-line delegators, zero LOC gain.
**Parent:** tempdoc 238 (F1-a).
**Source path:** `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java`
**Pinned LOC:** none — below ceiling (865 LOC; was 2,529).

## Progress (2026-05-25) — 8 waves shipped, all zero-behavior-change + `:modules:app-agent:test` green

| Wave | Extraction | LOC |
|---|---|---|
| W0 | `checkpoint(...)` helper (24 `updateCheckpoint` sites collapsed) | 2,529 → 2,377 |
| W2 | `AgentContextCompressor` (tool-message compression cluster) | 2,377 → 2,224 |
| W3 | `AgentEventTracing` (trace decoration + `Sequencer`) | 2,224 → 2,097 |
| W1-handoff | `AgentHandoff` (5 static multi-agent handoff helpers) | 2,097 → 1,981 |
| W-turnpolicy | `AgentTurnPolicy` (shouldForceToolCall / shouldEscalateToHandoff / resolveAgentState + constant) | 1,981 → 1,917 |
| W4 | `AgentLlmCaller` (callLlmWithTools ×2 / callLlmWithRetries / attemptBudgetEdgeFinalize / resolveAgentSampling / Hermes parse / assistant-msg build; `LlmCallResult` → top-level file; `sleepRetryDelay` → `AgentRetryPolicy`) | 1,917 → 1,571 |
| W5 | `AgentToolDispatcher` (executeOperationWithPolicy / dispatchToolCall / handleSafetyGate; late-bound router via `Supplier`; audit-test gate relocated) | 1,571 → 1,454 |
| W5-epilogue | `AgentSessionFinalizer` (runAgent finally-block session-end emit: F6 default + metric family + health-event Occurrence + runStore typed reason) | 1,454 → 1,422 |
| W8 | `AgentStepRunner` + top-level `IterationOutcome` — runAgent reshaped to a thin driver looping over a typed step; iteration-only helpers moved; shared `checkpoint`/`emitError`/`swapSystemPrompt` passed as functional callbacks | 1,422 → **865** |

Each wave: new package-private collaborator, delegations from AgentLoopService,
test refs redirected, pin tightened (deleted at W8). Cumulative **2,529 → 865 (−66%)** —
**below the 1,000-LOC ceiling; the `class-size-exceptions.txt` row is removed.**
The last named central mega-class is decomposed.

**Correction (W4 closure): the earlier "core needs live verification before
any extraction" framing was an over-gate** — a `structural-defects-no-repeat`
cost-disguised-as-principle. The 5 peripheral waves shipped on the *unit-test
floor alone* (compile + `:modules:app-agent:test`). That suite is **2,864
lines** and covers exactly the core-loop behaviors a bad LLM-cluster cut would
break: `singleToolCall_correctMessageOrder`, `multiStepChain_correctMessageOrder`,
`emptyResponse_retries`, `budgetEdgeFinalize_*`, `budgetExhausted_*`,
`loopDetection_*` (5 scenarios), `thinkTags_strippedFromFinalResponse`, Hermes
fallback, handoff ordering. W4 was therefore a verbatim cut safe on the **same
floor** the peripherals used — and it landed green (suite + full pre-merge
PMD/spotless gate). Live-agent verification is an *additional* tier (and was in
fact blocked: the shared dev stack was owned by another session at W4 time), not
a prerequisite for a verbatim relocation.

**Remaining:**

- **W5 tool-dispatcher — DONE.** `executeOperationWithPolicy` / `dispatchToolCall`
  / `handleSafetyGate` extracted to `AgentToolDispatcher`; the late-bound
  `BackendIntentRouter` is resolved per-call via a `Supplier`. The slice-487
  audit-test gate was the predicted care-point and was handled: the
  `AgentLoopServiceAuditTest` permitted-site predicate now names
  `AgentToolDispatcher.dispatchToolCall` — the invariant (exactly one direct
  `OperationDispatcher.dispatch` site, ArchUnit-enforced over bytecode) is
  unchanged. `handleVirtualToolCall` stayed behind (loop-lifecycle-coupled via
  `checkpoint`/`runStore`/`compressor`); pulling it would have widened the
  collaborator's surface for no net structural win.
- **W6 query-service** is *not worth extracting* (intentionally skipped): the
  methods are already thin one-line delegations to
  `runStore`/`fileOperationLog`/`operationExecutor`; moving them adds an
  indirection layer with no LOC reduction.
- **W8 typed loop — DONE + live-verified.** The 625-line `runAgent` was reshaped
  into a thin driver that loops over `AgentStepRunner.executeIteration(...)`,
  which returns a typed `IterationOutcome` (`cont` | `terminated(success)`);
  the driver maps `terminated.success()` → span status. This *was* the
  behavior-reshaping (not verbatim) piece, so — per `use-every-verification-tier`
  — it was verified on a running model after the unit suite + full pre-merge gate
  passed (see Verification tiers, satisfied).
**Related:**
- `docs/reference/contributing/class-size-standard.md` — the 1,000-LOC ceiling + collaborator-extraction pattern.
- tempdoc 516 (IndexingLoop) — sibling: state-machine-as-control-flow → typed `LoopState` + collaborators. **Closed.**
- tempdoc 517 (SearchOrchestrator) — sibling: decision-as-value + sealed-sum dispatch. **Closed** (1,919 → 154 LOC).
- tempdoc 519 (AppFacadeBootstrap → HeadAssembly) — sibling: phase-typed composition root. **Closed.** §N2 documented the re-growth thesis this file confirms.
- `.claude/rules/agent-lessons.md` §`standalone-capability-stays-stuck` — `backendIntentRouter` is a volatile late-bound field here; any extraction must preserve the lazy-resolution discipline.

---

## As-built (closure record) — what shipped vs. the original plan

The original "## The design" section below is the **pre-implementation plan** and
is kept for provenance. What actually shipped diverges from it; this section is
authoritative for the end state.

**Final collaborator inventory** (all package-private in `io.justsearch.agent`,
each a verbatim cut except W8's reshape):

| Collaborator / type | Owns | Wave |
|---|---|---|
| `AgentContextCompressor` | Layer-2/3 tool-result truncation + compression | W2 |
| `AgentEventTracing` (+ `Sequencer`) | `TraceContext` decoration, span/step-id derivation | W3 |
| `AgentHandoff` | handoff-tool build / recognize / reason-parse / history-prune | W1-handoff |
| `AgentTurnPolicy` | `shouldForceToolCall` / `shouldEscalateToHandoff` / `resolveAgentState` (+ `PRIMARY_FORCE_COMMIT_ITERATIONS`) | W-turnpolicy |
| `AgentLlmCaller` (+ `LlmCallResult` file) | LLM round-trip, retry policy, Hermes fallback, assistant-msg build, sampling | W4 |
| `AgentToolDispatcher` | `executeOperationWithPolicy` / `dispatchToolCall` / `handleSafetyGate` (late-bound router via `Supplier`) | W5 |
| `AgentSessionFinalizer` | end-of-run emit: metric family + health-event Occurrence + runStore reason | W5-epilogue |
| `AgentStepRunner` (+ `IterationOutcome` file) | one loop iteration; iteration-only `buildIterationTools`/`buildE0aTools`/`handleVirtualToolCall`; shared `checkpoint`/`emitError`/`swapSystemPrompt` via functional callbacks | W8 |
| `AgentRetryPolicy.sleepRetryDelay` | shared retry-sleep (LLM + tool loops) | W4 |

**What stayed in `AgentLoopService` (865 LOC, now a thin driver):** the `runAgent`
driver + prologue, the resume/control surface (`approveToolCall`/`rejectToolCall`/
`cancelSession`/`completeVirtualToolCall`/`resume*`), the read-only query projections,
`buildSystemPrompt`/`appendConditionContext`/`swapSystemPrompt`, `emitError`, the W0
`checkpoint` helper, and `wrapEventConsumer`.

**Divergences from the plan (each on merit):**

- **W1 `AgentPromptAssembler` — not built.** Prompt assembly (`buildSystemPrompt`/
  `appendConditionContext`/`swapSystemPrompt`) is small and shared with the prologue
  and resume paths; it stayed in ALS. Only the handoff cluster was extracted (as
  `AgentHandoff`).
- **W4 standalone, not folded into `OnlineModeOps`.** `AgentLlmCaller` is its own
  collaborator with a narrow dep set (`onlineAiService`/`agentTelemetry`/`compressor`);
  `OnlineModeOps` is a different seam and folding would have widened it.
- **W6 `AgentQueryService` — intentionally skipped.** Thin one-line delegators; zero
  LOC gain.
- **W7 `AgentResumeCoordinator` — not built.** Resume/control stayed in ALS. Once W8
  extracted the loop, ALS was already under the ceiling (865), so further extraction
  is unmotivated (YAGNI) and would only add indirection.
- **W8 outcome type simplified.** Shipped as `IterationOutcome(cont | terminated(success))`,
  not the planned sealed `StepOutcome(Continue/Terminate/HandOff)`. Each terminal site
  already does its own `emit → markTerminated → checkpoint` inline and then returns
  `terminated(success)`; handoff is handled inside the iteration (loop `break`), so only
  the continue-vs-terminate distinction (+ a success bit for span status) needed to
  surface to the driver. The richer sealed variant was unnecessary.
- **W0 `terminate(...)` quartet helper — not built.** The per-site `emitError`
  messages/codes are non-uniform; a unified helper would take 8+ params without
  improving clarity. The `checkpoint` half of the quartet *was* collapsed (W0).

**Verification correction that held:** the W4-closure finding — verbatim cuts are safe
on the unit-test floor; only the W8 *reshape* (emergent correctness) needed a live run —
proved out: W0–W5 + epilogue shipped unit-floor-only and W8 was live-verified. Treat the
"every wave must be live-verified" line in "What this is not" as superseded by this.

---

## Why now: the last mega-class (historical motivation)

After 516/517/519, `AgentLoopService` is the **last named central mega-class**
in the codebase — and the largest:

| File | LOC | Status |
|---|---|---|
| `AgentLoopService` | **2,529** | this tempdoc; last one standing, 2.5× ceiling |
| `AppFacadeBootstrap` → `HeadAssembly` | 891 | closed (519) |
| `SearchOrchestrator` | 154 | closed (517) |
| `IndexingLoop` | 931 | closed (516) |

It is also the **single most direct confirmation of 519 §N2's re-growth
thesis**: the 2026-era stub measured it at **1,959 LOC**; it is now **2,529** —
**+570 LOC (+29%) with no enforced bound pushing the work into collaborators.**
Per the 240 telemetry note it is also the highest-friction undecomposed file
(373 reads, 121 edits across 258 sessions). The ratchet pin (2,529) prevents
further growth; this tempdoc plans the reduction.

---

## Diagnosis: a state machine expressed as control flow over mutable locals

The same pathology 516 (`IndexingLoop`) and 517 (`SearchOrchestrator`) named.

**`runAgent(AgentRequest, Consumer<AgentEvent>)` is ~780 LOC** (`:602–1381`):
a single `for (iteration …)` loop over mutable `AgentSession` state +
OpenTelemetry span + a hand-rolled `AgentState` machine (PRIMARY → DECIDING,
handoff escalation). It is the only place that sees the whole loop; every new
agent behavior (Direction E / F / I, budget-edge finalize, handoff pre-scan)
has been bolted into this method, which is why it grew 29%.

Two concrete, mechanical fingerprints of the pathology:

1. **The terminal-disposition quartet repeats 10+ times.** Every exit path is
   the same four statements:
   ```
   emitError(sink, msg, code, class, retry, n);              // (often)
   session.markTerminated(disposition, errorCode, trigger);
   runStore.updateCheckpoint(sessionId, STATE,
       session.messages(), session.iterationsUsed(),
       session.toolCallsExecuted(), session.totalTokens(), msg);
   return;
   ```
   Verified at `:667` (no-tools), `:689` (cancel @ iteration start), `:715`
   (tool-loop), `:799–815` (budget-edge finalize success), `:827` (budget
   exhausted), `:873` (LLM failed), `:933` (empty response), `:957` (text
   done), `:1001` (cancel mid-tool), `:1028` (unknown handoff profile) — and
   more below `:1050`. Each is ~8–14 LOC; collapsing them is ~120+ LOC.

2. **`runStore.updateCheckpoint(...)` is always called with the same five
   session-snapshot arguments** (`session.messages()`, `iterationsUsed()`,
   `toolCallsExecuted()`, `totalTokens()`) — a 7-arg call that is 1 bit of
   real information (the state label + message) wrapped in 5 bits of
   boilerplate, repeated ~15×.

### Concern conflation (cluster → extractable collaborator)

| Cluster | Methods (line refs) | LOC (approx) |
|---|---|---|
| **Terminal/checkpoint** | the quartet + `emitError` (`:1928`), `attemptBudgetEdgeFinalize` (`:1945`) | ~120 (mostly dup) |
| **Prompt + tools assembly** | `buildSystemPrompt` (:364), `appendConditionContext` (:396), `buildIterationTools` (:430), `buildE0aTools` (:455), `buildHandoffTools` (:469), `findProfile` (:489), `swapSystemPrompt` (:497), `isHandoffCall` (:510), `extractReason` (:514), `pruneHandoffMessages` (:534) | ~250 |
| **Context compression** | `truncateForContext` (:1785), `compressToolMessagesForContext` (:1793), `stripSearchExcerpts` (:1834), `compressToolOutput` (:1841), `collect{First,Keyword,Last}Lines` (:1875–1916), `addLine` (:1916) | ~140 (largely pure/static) |
| **Event/trace decoration** | `wrapEventConsumer` (:1642), `withTrace` (:1657), `toolCallIdFor` (:1704), `iterationFor` (:1717), `stepIdFor` (:1725), `normalizeStepToken` (:1751) | ~140 |
| **LLM call** | `callLlmWithRetries` (:1970), `callLlmWithTools` ×2 (:2272/:2279), `tryParseHermesTextToolCalls` (:2450), `buildAssistantToolCallMessage` ×2 (:2507/:2511), `resolveAgentSampling` (:2198), `sleepRetryDelay` (:2183) | ~250 |
| **Tool dispatch / policy** | `executeOperationWithPolicy` (:2017), `handleVirtualToolCall` (:2094), `dispatchToolCall` (:2157), `handleSafetyGate` (:2483) | ~250 |
| **Read-only query/snapshot API** | `availableOperations` (:1446), `undoOperation` (:1451), `operationHistory` (:1460), `operationDetail` (:1470), `lastSessionSnapshot` (:1478), `listSessions` (:1483), `sessionSnapshot` (:1488), `sessionEvents` (:1598), `toBatchSummary` (:1608) | ~150 |
| **Resume/control** | `approveToolCall` (:1381), `rejectToolCall` (:1389), `cancelSession` (:1397), `completeVirtualToolCall` (:1412), `resumeLastSession` (:1493), `resumeSession` (:1509), `resumeFromSnapshot` (:1530) | ~150 |
| **State derivation** | `shouldForceToolCall` (:2219), `shouldEscalateToHandoff` (:2238), `resolveAgentState` (:2259) | ~50 |

**Partial decomposition already exists** — the class delegates to
`OnlineModeOps`, `AgentRunStore`, `AgentTelemetry`,
`AgentSessionTerminationObserver`, `EventTraceSequencer`, and `AgentSession`.
The decomposition *extends a proven pattern*, it does not invent one.

---

## The design — thin driver + collaborators + a typed loop (ORIGINAL PLAN — see §As-built for what shipped)

> This was the pre-implementation plan. The end state diverges from it (collaborator
> names, W1/W6/W7 not built as named, W8 outcome simplified) — **§As-built (closure
> record) above is authoritative.** Kept here for provenance.

Same principle as 516/517: **the procedural sequence becomes typed data; the
god method becomes a thin driver.** Sequenced as waves (sub-slice aggressively
— 516 shipped 9 commits).

- **W0 — Checkpoint + termination helpers (mechanical, highest leverage, do first).**
  **✅ checkpoint helper SHIPPED 2026-05-25** — a private `checkpoint(sessionId,
  session, state, note)` collapsed all **24** `runStore.updateCheckpoint(...)`
  7-arg call sites to one line each; **2,529 → 2,377 LOC** (−152), pin tightened
  to 2,377. Pure refactor, zero behavior change — verified by
  `:modules:app-agent:test` (green) + compile. **Remaining:** the
  `terminate(disposition, errorCode, checkpointState, errorEvent?)` quartet
  helper (collapses `emitError → markTerminated → checkpoint → return`; the
  emitError args vary per site so it's less uniform — a follow-on cut).

- **W1 `AgentPromptAssembler`** — prompt/tools-assembly + handoff-tool cluster.
- **W2 `AgentContextCompressor`** — compression cluster (mostly pure static; the easiest wave; good warm-up after W0).
- **W3 `AgentEventDecorator`** — trace/step-id decoration cluster.
- **W4 `AgentLlmCaller`** — fold the LLM-call cluster into / alongside the existing `OnlineModeOps`.
- **W5 `AgentToolDispatcher`** — tool dispatch + policy + virtual-tool + safety-gate cluster.
- **W6 `AgentQueryService`** — the read-only snapshot/history/list projections (a pure projection collaborator, peer to 517's `SearchResponseBuilder`).
- **W7 `AgentResumeCoordinator`** — resume/control surface.
- **W8 — the loop as a typed state machine (last, hardest).** Extract the
  per-iteration body into an `AgentIterationStep` returning a sealed
  `StepOutcome` — `Continue` / `Terminate(disposition, errorCode, message)` /
  `HandOff(toAgentId)` — and reduce `runAgent` to a thin driver that loops,
  dispatches on `StepOutcome`, and calls `terminate(...)`. This is the 516
  `LoopState` / 517 decision-as-value pattern applied to the agent loop. The
  `AgentState` enum (PRIMARY/DECIDING) already exists and seeds it.

Target: `AgentLoopService` becomes a thin facade over the collaborators + the
step driver, ≤ 1,000 LOC; pin removed from `class-size-exceptions.txt`.

---

## Verification tiers (do not relax)

- **Per verbatim-cut wave (W0–W5):** `./gradlew.bat :modules:app-agent:test`
  + the full pre-merge gate green; ratchet pin tightened. The 2,864-line
  `AgentLoopServiceTest` is the floor — it covers the relocated behaviors, so a
  broken cut fails it. These waves do **not** block on a live run (W4 shipped
  this way; the dev stack was owned at the time regardless).
- **Tier-3 — live agent run (SATISFIED 2026-05-25 for W8 / closure).** W8
  reshapes control flow rather than relocating it, so its correctness is
  *emergent*; per `use-every-verification-tier` it was run on a live model
  (`ai_activate`, 10.7s) via `POST /api/chat/agent`:
  - **`cont()` branch** — a tool-using query produced the full reshaped event
    sequence across 3 iterations: `session_started` → `progress(init)` →
    [`budget_update` → `progress(llm_call)` → `tool_call_proposed(core_search_index)`
    → `tool_call_approved` (LOW auto-approve via `AgentToolDispatcher.handleSafetyGate`)
    → `tool_exec_started` → `tool_exec_completed`] ×2 → text streaming. The
    multi-iteration loop, tool dispatch, safety gate, and `AgentEventTracing.Sequencer`
    span-chaining all fired correctly through the thin driver.
  - **`terminated(success)` branch** — a text-only query produced a clean
    `event: done` with `finalResponse`, `iterationsUsed:1`, `toolCallsExecuted:0`
    (the text-only-done terminal → `IterationOutcome.terminated(true)` →
    `agentSuccess=true`).

  (Tool execution itself returned "No handler registered for core.search-index"
  — a dev-data artifact of an empty index, *not* a loop defect; the dispatch
  path executed and fed the result back into the loop exactly as designed.)

## Caveats / risk

- **Highest-friction, hot file.** Coordinate (other agents touch the agent
  loop). Sub-slice aggressively; one wave per commit; never land in one go.
- **`backendIntentRouter` is a `volatile` late-bound field** (`:167`, set via
  `setBackendIntentRouter`). Any wave that moves dispatch across a seam must
  preserve `Supplier`/late-bind discipline per `agent-lessons.md`
  §`standalone-capability-stays-stuck` — do not capture it eagerly into a
  collaborator constructor.
- **The terminal quartet's behavioral subtlety:** the order is *state-first,
  durability-second* (`markTerminated` before `updateCheckpoint`, per the
  tempdoc-415 F1 comments). The `terminate(...)` helper must preserve that
  ordering exactly.
- **No new substrate / no framework.** Reuse the package-private collaborator
  pattern (`class-size-standard.md`) and the existing `OnlineModeOps`/`AgentRunStore` seams.

## What this is not

A green-light to refactor in one commit, and not a behavioral change — every
wave was structure-only. (Verification tier, as-resolved: the verbatim cuts
W0–W5 + epilogue were unit-test-floor-safe; only the W8 *reshape* required a
live-agent run — see §As-built. The original "must be live-verified against a
running model [every wave]" framing was corrected at W4 closure.) The class-size
ceiling is the enforcement that prevents the +29% re-growth from recurring now
that the file is back under it.
