---
title: "585 — AgentController structural remedy: the repo's #3 class-size bump offender (1131 LOC, 7 class-size changesets) and the youngest of the three — it crossed the ceiling only recently (577 §2.12) as the agent-control endpoint family grew. 25+ handler methods spanning several sub-families in one controller. Charter to determine and execute the RIGHT structural fix — split into sub-family controllers, rewrite the handler-dispatch surface, or other — NOT a decomposition mandate. Catching it early is the point: settle the shape before it becomes a LocalApiServer."
type: tempdocs
status: closed (2026-06-15) — remedy = Hybrid C executed & merged (4c75dff3a); AgentController 1131→486 LOC, pin deleted, behavior-preserving. Split: run/control core (AgentController) + read-axis (AgentSessionController, narrowed to AgentRunQueries) + tools-axis (AgentToolsController) over one shared AgentSseWriter (the AgentEvent→SSE vocabulary keystone). Live-stack agentic smoke green on merged main. Control-axis AgentService→AgentRunner/AgentSessionControl interface segregation remains the documented future escalation (deferred, ~27 files).
created: 2026-06-15
updated: 2026-06-15
origin: tempdoc 582 §B.5 R2 (governance-critique — the class-size treadmill)
---

# 585 — AgentController: settle the shape before it becomes the next LocalApiServer

> **Purpose, stated carefully.** NOT "decompose AgentController." It is: *this controller just
> crossed the class-size ceiling and is on the same accretion trajectory that made LocalApiServer
> the repo's worst offender; choose the right structural remedy and apply it now, while the file is
> still small enough that the remedy is cheap.* The remedy may be a split into sub-family
> controllers, a **rewrite** of the handler/dispatch surface, or other. **Which is right is the
> deliverable.** The strategic value is catching the trajectory early (7 changesets, not 37).

## 1. Why this file (the evidence)

- **1131 LOC**, pinned in `gradle/class-size-exceptions.txt` (577 §2.12 grandfather row, 2026-06-14).
- **7 class-size changesets** name it — #3 after LocalApiServer (37) and AgentLoopService (15), and
  the **fastest-rising** of the three (it only just crossed the ceiling).
- **25+ handler methods in one controller**, and they cluster into clear sub-families:
  - **run/streaming** — `handleRunStream`, `handleAttachStream`, `handleResumeLastStream`,
    `handleResumeSessionStream`, `writeAgentEvent`
  - **approval/gate/steering** — `handleApprove`, `handleReject`, `dispatchGate`,
    `handleSteeringDirective`, `handleContextDecision`
  - **autonomy/budget** — `handleAutonomy`, `handleRaiseBudget`, `handleBudgetDecision`
  - **sessions/history** — `handleSessionLast`, `handleSessionEvents`, `handleListSessions`,
    `handleSessionDetail`, `handleSessionTranscript`, `handleHistory`, `handleHistoryDetail`,
    `handleCancelSession`, `handleUndo`
  - **tools/virtual-ops** — `handleListTools`, `handleVirtualOperationsPublish`,
    `handleVirtualToolResult`, `handleVirtualOperationsRead`
- The sub-family clustering is strong and visible — which is exactly why the remedy should be settled
  now, cheaply, rather than after another 30 changesets.

> **Relationship to 584 (shared accretion mechanism — confirmed, not hypothesized).** 584
> (AgentLoopService, merged 2026-06-15) diagnosed and proved the mechanism that drives BOTH files:
> `AgentLoopService` is the sole implementor of the wide `AgentService` interface, so **a single new
> agent feature ripples across four layers in lockstep** — `AgentController` (a handler) →
> `AgentService` (a method) → `AgentLoopService` (an impl) → `AgentSession` (state). 584 and 585 are
> the **controller face and service face of one horizontal-accretion mechanism**, which is why they
> re-pinned in the same tempdocs (561/577). See 584 §B.1–B.2.
>
> **Concrete gift from 584 → 585:** 584 segregated the read-time query surface into a narrow
> **`AgentRunQueries`** interface (`AgentService extends AgentRunQueries`; impl in
> `AgentRunQueryService`). 585's **sessions/history sub-family** (`handleSessionLast`,
> `handleSessionEvents`, `handleListSessions`, `handleSessionDetail`, `handleSessionTranscript`,
> `handleHistory`, `handleHistoryDetail`, `handleUndo`) consumes **exactly** that read surface
> (`lastSessionSnapshot` / `sessionEvents` / `listSessions` / `sessionSnapshot` / `operationHistory` /
> `operationDetail` / `undoOperation`) and needs **no** loop method. So a sessions/history
> sub-controller can depend on the narrow `AgentRunQueries`, **realizing the consumer-narrowing that
> 584 could not apply to `InteractionThreadController`** (which is `runAgent`-coupled via
> `BackgroundRunService`, 584 §B.5). This is a positive reason to favour **remedy A (split)** for at
> least the sessions/history cluster — it lands the read-axis insulation on the controller side that
> 584 set up on the service side.
>
> **Caveat the other way:** the run/streaming + approval/gate/steering + autonomy/budget clusters DO
> need the loop+control surface (`runAgent`, `tryApprove`, `injectSteeringDirective`, the budget/
> context-gate resolvers) — i.e. the full `AgentService`. 584 deliberately **deferred** full
> control-axis interface segregation (`AgentRunner` + `AgentSessionControl` as separate interfaces)
> because it ripples ~27 files and overlaps THIS charter. So 585 is the natural place to decide
> whether to also segregate the control surface (so the run/approval/autonomy sub-controllers depend
> on `AgentRunner`/`AgentSessionControl`, not the kitchen-sink `AgentService`) — the recurrence-proof
> escalation 584 §B.5 names. Sequence: 584 is merged, so `AgentRunQueries` is available on `main` now.

## 2. What to find out before choosing a remedy

1. **Are the sub-families truly independent, or do they share state/helpers?** Map shared fields
   (`agentService`, gate registry, audience resolution, `writeAgentEvent`, `resolveShapeRef`,
   `readAudience`) across the five clusters. Shared plumbing dictates whether a split needs a common
   base/helper or whether the clusters lift cleanly.
2. **The steering single-dispatch constraint.** `check-steering-arbitration.mjs` requires the
   per-run direction channel (`dispatchRunControl`, `.steer(`, `.cancelSession(`) to have EXACTLY ONE
   dispatch site. A split MUST NOT create a second steer/cancel dispatch site — verify the remedy
   keeps these single-authority. This is a hard gate, not advisory.
3. **Route wiring coupling with 583.** These handlers are registered as routes (some via
   LocalApiServer / agent routes). Coordinate with 583 so the two remedies compose rather than
   conflict (e.g., if 583 introduces a declarative route table, 585's sub-controllers slot into it).
4. **Is "controller" even the right unit?** Distinguish "five cohesive controllers wearing one class"
   (→ split) from "thin handlers over a fat dispatch surface that should be rewritten" (→ rewrite the
   dispatch, keep one slim controller). The handler-body size breakdown decides this.

## 3. Candidate remedies (the investigation picks; none is pre-ordained)

- **A — Split into sub-family controllers** along the five clusters (e.g., `AgentRunController`,
  `AgentApprovalController`, `AgentSessionController`, `AgentToolsController`), with shared plumbing
  in a thin base/helper. Most direct given the visible clustering.
- **B — Rewrite the dispatch surface.** If handlers are thin wrappers over repetitive
  parse→authorize→dispatch→stream boilerplate, factor the boilerplate into a small dispatch helper
  and keep one slim controller. A rewrite, not a split.
- **C — Hybrid** — extract the largest cluster (sessions/history is 9+ handlers) and rewrite the
  dispatch spine for the rest.

**Selection criteria:** keep the steering/cancel single-dispatch invariant, compose cleanly with 583,
reuse existing controller patterns (`KnowledgeSearchController` et al.), and stop the accretion
trajectory — prefer the remedy that makes *adding the next agent-control endpoint* not grow a
1000-LOC file.

## 4. Constraints & invariants (do not violate)

- **Steering single-dispatch** — `check-steering-arbitration.mjs` (per-run direction channel) must
  stay green; no second `.steer(` / `.cancelSession(` site.
- **Loopback-only (Inv #2)** — handlers stay on the 127.0.0.1 server; no new bind.
- **Behavior-preserving** — all 25+ endpoints respond identically (request/response contracts, SSE
  framing, audience/authorization) after the change.
- **Compose with 583** — do not duplicate route-registration logic; coordinate the two remedies.

## 5. Verification plan

- Result ≤ ceiling (delete pin row) or a justified reduced pin.
- `./gradlew.bat :modules:ui:test` green.
- `node scripts/ci/check-steering-arbitration.mjs` green (single-dispatch preserved).
- **Live-stack smoke (per `use-every-verification-tier`)**: `ai_activate` + a real agent run
  exercising each sub-family — stream, approve/reject a gate, set autonomy/budget, list sessions +
  resume, list tools — confirming every handler still works post-split.
- `./gradlew.bat build -x test` from main before merge.

## 6. Success criteria

`AgentController.java` is ≤ ceiling (pin row deleted) or materially reduced via a remedy that the §2
analysis justified, with the steering single-dispatch invariant intact, every endpoint
behavior-preserved, and the agent-control endpoint family no longer on a path to become a second
LocalApiServer.

## §B — Findings (populated during execution)

> Investigation pass 1 (2026-06-15, agent). Source-verbatim against `main` after 583 + 584 merged.
> Like 583's §B, the headline **sharpens the charter's framing**: the five sub-families are real, but
> the single biggest extractable mass is **not** a handler family at all — it is the ~250-LOC
> `AgentEvent → SSE-wire` translation that every streaming handler shares. The charter invited
> questioning its premises (§3 "none is pre-ordained"); this is that.

### §B.1 — The accretion-mechanism classification (§2.1 + §2.4, the first deliverable)

Exact measured line budget of all 1131 LOC (cluster spans over the method boundaries, `main`):

| Cluster | LOC | % | What it is | Is it an accretor? |
|---|---:|---:|---|---|
| **SSE event-vocabulary** | **247** | **22%** | `writeAgentEvent` (158, the two 21-case `AgentEvent`→`{eventType, payload}` switches) + `writeOrEvict`/`evictIfGone`/`SseObserverGoneException` (39) + `toolCompletedPayload`/`withTracePayload`/`toTraceMap` (50). **Not a handler** — a pure translation shared by all four streaming handlers | **YES** — 577-r3-context-gate (+2 cases), -context-meter (+2 fields), -zero-observer-eviction (+42 the eviction seam) |
| **sessions/history (read)** | ~200 | 18% | `handleSessionLast`/`SessionEvents`/`ListSessions`/`SessionDetail`/`SessionTranscript`/`History`/`HistoryDetail`/`Undo` (8 pure-read handlers) **+** `handleResumeLast`/`ResumeSession` (stream) **+** `handleCancelSession` (control) | mild (415 C20) |
| **tools/virtual-ops** | 166 | 15% | `handleListTools` + `handleVirtualOperationsPublish`/`Read` + `handleVirtualToolResult` + `hasAgentAudience` — the 508 FE-published virtual-operations sidecar; touches `VirtualOperationStore`, not the loop | 508 (one-time) |
| **approval/gate/steering** | 123 | 11% | `handleApprove`/`Reject`/`resolveApprovalGate`/`dispatchGate`/`setWorkflowGateRegistry` (72) + `handleContextDecision` (24) + `handleSteeringDirective` (27) | **YES** — 560 (gate reg), 565 (steer), 577 (context) |
| **header/fields/ctor** | 118 | 10% | 3 ctor overloads + 7 fields + the `ALLOWED_SHAPE_IDS` whitelist | low |
| **autonomy/budget** | 70 | 6% | `handleAutonomy` + `handleRaiseBudget` + `handleBudgetDecision` | **YES** — 561 (autonomy), 577 (budget×2) |
| **run/stream + attach** | 78 | 7% | `handleRunStream` (48) + `handleAttachStream` (30) | 577 (attach) |
| **projection helpers** | 65 | 6% | `projectBatchCalls` (needs `availableOperations` + `intentGateEvaluator`) + `operationToToolMap` + `toolKind` | low |
| **request-parse helpers** | 51 | 5% | `resolveShapeRef`/`UnknownShapeException` (33) + `readAudience` (18) | low |

**The decisive finding — there are TWO accretors, not one, and they differ in kind:**

1. **Control endpoints** (autonomy/steer/budget/budget-decision/context-decision) — each a ~15-25-LOC
   `parse body → call agentService().method() → 200/404` handler. This is the family the pin comment
   (577 §2.12) names as the thing that "crossed 1000," and it is the **controller face of 584's proven
   horizontal-accretion mechanism**: one agent feature = a handler here + a method on `AgentService` +
   an impl on `AgentSessionRegistry` (584) + state on `AgentSession`.
2. **The SSE event-vocabulary** (`writeAgentEvent`) — grows one `case` per new `AgentEvent` type. At
   247 LOC (22%, the single biggest cluster) it is *already* the largest mass, and `AgentEvent` is the
   **same horizontal type that grows in app-agent** (577 added `BudgetGatePending`/`ContextGatePending`/
   `ContextCompacted`). The 577-zero-observer-eviction changeset **explicitly records the obligation**:
   *"further growth should extract the SSE observer-lifecycle helpers into a dedicated collaborator."*

This is the §2.4 distinction the charter asked for ("five cohesive controllers wearing one class" vs
"thin handlers over a fat dispatch surface"): the truth is a **third** shape — *thin handlers + one fat
shared translator*. The handlers ARE thin (parse→delegate); the mass is the shared `writeAgentEvent`.

### §B.2 — The steering single-dispatch "hard gate" is FE-only — the Java split is gate-safe by construction

The charter (§2.2, §4) flags `check-steering-arbitration.mjs`'s single-dispatch invariant as a "hard
gate, not advisory" that "a split MUST NOT" break. **Verified against the gate source:** its
`SRC_ROOT = 'modules/ui-web/src'` — it scans **only the FE TypeScript** (the `dispatchRunControl` seam,
the FE `.steer(`/`.cancelSession(` call sites). It **does not inspect `modules/ui/src/main/java`** at
all. So a Java controller split *cannot* trip this gate. The underlying spirit (one backend call site
each for `injectSteeringDirective` / `cancelSession`) is preserved **by construction** as long as the
remedy *moves* handlers (never duplicates them) — which any clean split does. This materially de-risks
remedy A vs. the charter's framing.

### §B.3 — 583/584 coordination + the `AgentRunQueries` narrowing (the §1 gift, verified)

- **583 is merged.** `AgentController` is constructed in `ConversationApiAssembly.assemble(...)` and
  returned in its `Result` record; `LocalApiServer` reads it once as `convApi.agentController()` and
  passes it to `AgentRoutes.register(app, controller)` (a **register-only** seam — it binds routes over
  an already-constructed controller). `setWorkflowGateRegistry(...)` is the only other touch. So a
  split's blast radius is small and known: `ConversationApiAssembly` (construct N, add to `Result`),
  `AgentRoutes` (register N), the 4 unit tests that `new AgentController(...)` or call its static seams.
- **584 is merged → `io.justsearch.agent.api.AgentRunQueries` exists on `main`** (`AgentService extends
  AgentRunQueries`). Verified its surface covers **exactly** the 8 pure-read handlers: `availableOperations`,
  `undoOperation`, `operationHistory`/`operationDetail`, `lastSessionSnapshot`, `listSessions`,
  `sessionSnapshot`, `sessionEvents`. So a **sessions/history sub-controller can depend on the narrow
  `AgentRunQueries`** — realizing on the controller side the consumer-narrowing 584 set up on the service
  side (and could *not* apply to `InteractionThreadController`, §584 B.3 correction).
- **Boundary wrinkle (verified):** `isAvailable()`, `cancelSession()`, `completeVirtualToolCall()` and all
  control methods are on `AgentService`, **not** `AgentRunQueries`. So the two **resume-stream** handlers
  (`resumeLastSession`/`resumeSession` are read methods but the handlers also call `isAvailable()` and
  stream via the SSE writer) and `handleCancelSession` do **not** narrow — they belong with the
  run/control surface, not the read sub-controller. Only the **8 pure-read** handlers narrow cleanly.

### §B.4 — Candidate-remedy evaluation (the deliverable: which, and why)

- **B (rewrite the dispatch surface) alone is insufficient** — same verdict 583 reached about its own
  Candidate B. The control handlers are repetitive but not identical (different field names, different
  return semantics: some `void→200`, some `boolean→404`, some return a result object), so a dispatch
  table saves ~5 LOC each and still needs a per-handler lambda. Worse, B does **not touch the 247-LOC SSE
  vocabulary** — the biggest mass. B addresses the smallest accretor and ignores the largest.
- **The keystone is extracting the SSE event-vocabulary** into an `AgentSseWriter` collaborator. It is
  (a) the single biggest cut (247 LOC, 22%); (b) a near-pure translation (the two switches are
  `Context`-free; only the final write touches `Context`); (c) the **documented obligation** (577
  changeset); (d) **recurrence-proof for the SSE axis by construction** — a new `AgentEvent` case lands in
  the collaborator, never in a controller. This is the part of the charter's Candidate C/B that genuinely
  fits (cf. 583 §B.7.3: "use the data-driven idea only where it genuinely fits").
- **The SSE extraction alone is not durable** (→ ~884 LOC, only ~116 margin). 583 §B.11 is the cautionary
  precedent: a thin margin re-breaches within 1-2 slices and re-starts the treadmill. A durable remedy
  needs at least one more cut. The natural second cut is the **`AgentRunQueries`-narrowed sessions/history
  read sub-controller** (the 584 gift, §B.3) — it is the cleanest consumer-narrowing available and removes
  ~150 LOC of pure-read handlers.

### §B.5 — Selected remedy: **Hybrid (Candidate C)** — `AgentSseWriter` keystone + a three-way controller split (user-confirmed 2026-06-15)

Reuse the repo's proven decomposition tool (package-private collaborator extraction + per-domain
controllers; 583 §B.4 / 584 §B.4), **not** a DI-by-table framework. The user selected the **fuller
three-way split** over the minimal two-cut and the SSE-only options. The shape:

1. **`AgentSseWriter`** (shared collaborator, ~250-290 LOC) — owns `writeAgentEvent` (the event
   vocabulary), `writeOrEvict`/`evictIfGone`/`SseObserverGoneException`, the payload/trace helpers, and
   `projectBatchCalls` (constructor-injected `Supplier<AgentService>` for `availableOperations` + the
   `IntentGateEvaluator`). Injected into every streaming controller. **The keystone** — kills the biggest
   accretor by construction; the `evictIfGone`/`SseObserverGoneException` unit test repoints here.
2. **`AgentSessionController`** (depends on the narrow `Supplier<AgentRunQueries>` + telemetry, ~150 LOC)
   — the 8 pure-read session/history handlers. Realizes 584's controller-side consumer-narrowing.
3. **`AgentToolsController`** (full `AgentService` supplier + `VirtualOperationStore`, ~166 LOC) — the
   tools/virtual-ops sidecar (`hasAgentAudience`/`operationToToolMap`/`toolKind` move here). It touches
   `VirtualOperationStore`, not the loop, so it lifts cleanly.
4. The **live-run + control core** keeps `AgentController`'s name and the run/stream + attach + resume×2 +
   approval/gate + autonomy/budget + steer + context + cancel handlers (full `AgentService` +
   `AgentSseWriter`). After the three cuts it is ~560 LOC.

**Why this ends the treadmill (the §6 bar):** the SSE axis becomes recurrence-proof *by construction*
(new event → collaborator). The read axis becomes recurrence-proof *by construction* (new read endpoint
→ `AgentSessionController` on the narrow interface). The tools axis is its own file. The control axis is
relieved by ~440 LOC of headroom — the same "~10× slower regrowth, not yet recurrence-proof-by-
construction" bar 584 accepted for its control cluster (full `AgentService` → `AgentRunner`/
`AgentSessionControl` interface segregation remains the documented future escalation, ~27 files,
deferred jointly by 584 §B.5 and out of 585's controller-only scope). This clears "no longer on a path
to become a second LocalApiServer."

### §B.6 — Execution record

**Outcome: `AgentController.java` 1131 → 486 LOC (−645, −57%); class-size pin row deleted, gate passes.**
The run/control core is now ~half the ceiling (514 LOC headroom). Three cohesive collaborators landed
(all package-private in `io.justsearch.ui.api`, the repo's proven decomposition pattern):

| New file | LOC | Absorbs |
|---|---:|---|
| `AgentSseWriter` | 337 | the `AgentEvent → SSE-wire` vocabulary (`writeAgentEvent`'s two 21-case switches), the hub-observer eviction seam (`writeOrEvict`/`evictIfGone`/`SseObserverGoneException`), the payload/trace helpers, and `projectBatchCalls`. Deps: one `SseWriter` + `Supplier<AgentService>` + the `IntentGateEvaluator`. **The keystone** — the biggest mass + the documented 577 obligation; a new event case lands here, not on a controller. |
| `AgentSessionController` | 194 | the 8 pure-read session/history handlers (`handleSessionLast`/`SessionEvents`/`ListSessions`/`SessionDetail`/`SessionTranscript`/`History`/`HistoryDetail`/`Undo`). Depends on the **narrow `Supplier<AgentRunQueries>`** — the 584 gift realized on the controller side. |
| `AgentToolsController` | 257 | the tool inventory + 508 FE virtual-operations sidecar (`handleListTools`/`VirtualOperationsPublish`/`Read`/`VirtualToolResult` + `hasAgentAudience`/`operationToToolMap`/`toolKind`). Deps: `Supplier<AgentService>` + `VirtualOperationStore`. |

**Behaviour preservation.** Every handler body moved **verbatim** (only the SSE-method calls re-pointed
to the injected `AgentSseWriter`, and `agentService()`→`queries()` in the read controller). The route
URLs are unchanged — `AgentRoutes.register` now takes the three controllers and binds each path to its
owner. `LegacyEndpointGuardTest` (the behaviour-preservation oracle — it snapshots the live Javalin
route set) stays green, so the registered route set is byte-identical. `ConversationApiAssembly`
constructs the four objects and returns them in its `Result`; `LocalApiServer` reads them via accessors
(matching 583 §B.11's single-field-`Result` discipline — a new agent controller touches only the
assembly). The `AgentController` constructor collapsed from 3 overloads to 1 (the `VirtualOperationStore`/
`IntentGateEvaluator` params moved to the sub-controller / writer that actually use them).

**Constructor collapse — verified safe.** The 4-arg test callsite `new AgentController(() -> svc, null,
null, null)` (`AgentControllerApprovalDispatchTest`) compiles unchanged against the new single ctor
`(Supplier<AgentService>, ConversationEngine, AgentSseWriter, Telemetry)` and `resolveApprovalGate` is
untouched (it never referenced engine/sseWriter/telemetry).

**Test repointing (static seams followed their methods):** `AgentControllerSseEvictionTest` →
`AgentSseWriter.evictIfGone`/`SseObserverGoneException`; `AgentControllerAudienceTest` →
`AgentToolsController.hasAgentAudience`; `LegacyEndpointGuardTest` → the 3-arg `AgentRoutes.register`;
one cross-module prose comment in `AgentLoopServiceTest` retargeted. `resolveShapeRef`/`UnknownShapeException`
stayed on `AgentController` (`AgentControllerShapeDispatchTest` unchanged).

**Gate-safety (§B.2 confirmed live):** `node scripts/ci/check-steering-arbitration.mjs` green — the
split moved (never duplicated) `injectSteeringDirective`/`cancelSession`, and the gate is FE-only
anyway, so single-dispatch is intact.

**Verification status:**
- [x] `./gradlew.bat :modules:ui:spotlessApply :modules:ui:compileJava :modules:ui:compileTestJava` green.
- [x] `./gradlew.bat build -x test` green (full project; **`class-size` gate passes with the pin row deleted**).
- [x] `./gradlew.bat :modules:ui:test` green (incl. `AgentControllerSseEvictionTest`, `AgentControllerApprovalDispatchTest`, `AgentControllerAudienceTest`, `AgentControllerShapeDispatchTest`, `AgentWireProjectionTest`, **`LegacyEndpointGuardTest`** — the route-set oracle).
- [x] `:modules:app-agent:test --tests *AgentLoopServiceTest*` green (the retargeted comment).
- [x] `node scripts/ci/check-steering-arbitration.mjs` green (single-dispatch preserved).
- [x] **Live-stack agentic smoke (charter §5) — GREEN, against MERGED `main`** (the dev-runner is
  hardwired to the main checkout, cf. 584 §B.5; merged first after green pre-/post-merge `build -x test`).
  `ai_activate` (cuda12, GPU, 19s) + a real `agent_chat` run exercised the **run/control core +
  `AgentSseWriter` keystone end-to-end**: `handleRunStream` → engine streamed the full event vocabulary
  (`tool_call_proposed` · `tool_exec_completed` · `budget_update` · `done` + per-event `trace`
  decoration — all translated by the extracted `AgentSseWriter.writeAgentEvent`), the approval gate fired
  (`core_search_index` `approved:true`, 12 results, 2 iterations, coherent answer), budget tracked down
  to 310 tokens. Then every split route probed live on loopback (the MCP harness allowlist excludes
  `/api/chat/*`, so curl direct):
  - **`AgentSessionController`** (read-axis, `AgentRunQueries`-narrowed): `GET /api/chat/sessions` → 200
    (listed the run, `AgentSessionSummary` projection), `/sessions/last` → 200, `/sessions/{id}` → 200,
    `/agent/history` → 200, `/sessions/{id}/events` → 404 (unknown), `/agent/undo` → 400 (validation).
  - **`AgentToolsController`**: `GET /agent/tools` → 200 (`available:true`, `core_search_index` inventory
    via `operationToToolMap`), `/agent/virtual-operations` → 200 `{tools:[]}`, publish → 400
    (`hasAgentAudience` rejection), `tool-result` → 404 (no pending call).
  - **`AgentController` core** (control): `/approve` + `/reject` → 404 (unified `resolveApprovalGate`),
    `/budget-decision` + `/context-decision` → 404 (held-gate checks), `/steer` → 404, `/budget` → 400,
    `/autonomy` → 200. Every endpoint behaved exactly as its (moved-verbatim) handler dictates.

**Merged to `main`** (2026-06-15, `4c75dff3a` `--no-ff` merge commit) after green pre-merge + post-merge
`build -x test` from the main checkout (the concurrent `f0e5a72bd` 584-follow-up merge auto-merged clean
in `AgentLoopServiceTest`). **Tempdoc 585 complete.**

### §C — Independent review (reviewer ≠ implementer; slice-execution honor-system)

A second agent reviewed the merged split (2026-06-15) — verbatim diff of every moved handler against the
pre-split original (`250624919^`), route coverage, the `AgentSseWriter` switches, the `AgentRunQueries`
narrowing, the wiring, and test relocations. **VERDICT: APPROVE, zero blockers.** Confirmed behavior-
preserving across all six focus areas: the only non-whitespace diffs were the intended SSE re-pointing +
`agentService()`→`queries()` (read controller) + the `operationToToolMap` static-ref rename + two
`private→package` visibility widenings on the moved SSE methods + one `{@link}` retarget; 24 routes before
and after, each handler in exactly one class; no assertion weakened.

Two **nits**, both **pre-existing** (carried verbatim from the original, not introduced here), logged to
`docs/observations.md` rather than fixed (per `log-pre-existing-issues`): (1) ~13 handler javadocs use the
retired `/api/agent/*` URL prefix where the real routes are `/api/chat/*` (491 migration); (2) the
relocation of `writeAgentEvent`/session-reads/tools means open observations items that cited the old
`AgentController` file/line now point at the new collaborators — a relocation note was appended so they
stay navigable.

### §C.1 — Exception-ledger followup

The 582 `exception-count` meta-ratchet (logged red on the 583 branch base, 55 > 48) is **GREEN on main**
post-merge (baseline 56, `--gate exception-count --mode gate` → pass) — the 583/584/585 pin retirements
brought the live bounded-exception count back under ceiling, exactly the ratchet-down 583's observations
note anticipated. No baseline edit needed (the gate passes; a future tightening is 582's domain).

## §D — Theoretical extensions & research (2026-06-15, autonomous research round)

> Pure research — no code changed. The question posed: *now that 585 + the followup are in, what does this
> design cheaply enable?* Method: for each seam 585 created, ask "what was expensive before and is cheap
> now?", then ground each idea against the codebase **and** external prior art (the AG-UI agent-UI protocol,
> LangGraph time-travel, the SSE `Last-Event-ID` spec). This is an idea catalog, not a commitment — per the
> charter spirit, the SHAPE of any follow-up is its own investigation.

### §D.1 — What 585 actually created (the three seams)

585 + the followup turned **the agent's observable behaviour into a single, well-typed, durable, replayable
event stream with one authority.** Concretely, three reusable seams:

- **S1 — one canonical event translator** (`AgentEventSseTranslator`): the *only* place internal `AgentEvent`
  domain types become the external wire vocabulary (21 events). A "narrow waist."
- **S2 — durable, replayable, multi-observer runs**: `RunEventHub` (N observers, ~1000-event replay buffer,
  zero-observer park) + `RunEventStore` (full `events.ndjson` history) + snapshot resume (3 resumable states)
  + `BackgroundRunService`/`presenceSince`. **A run is a first-class, persisted, replayable entity.**
- **S3 — narrow read interface + projections** (`AgentRunQueries`: `lifecycles`/`threadEvents`/`presenceSince`).

**Grounding finding (important):** the event vocabulary is *already* a "generate-tier" win — the backend
translator and the FE handler types both derive from one schema (`AgentRunShape.EVENT_SCHEMA` →
`gen-shape-handlers.mjs` → `core-agent-run.ts`), with `AgentEvent{Schema,Payload}ConformanceTest` binding
declared⇔produced. 585 completed the *backend-authority* half (one translator instead of two). So drift is
impossible by construction across the whole chain — a strong base to build on.

### §D.2 — External grounding (prior art the design aligns with)

- **AG-UI protocol** (the emerging agent↔UI streaming standard, 17 event types): our 21-event vocabulary is
  essentially a **superset**. Their RunStarted/Finished/Error ↔ our SessionStarted/AgentDone/AgentError;
  TextMessage\* ↔ our `chunk`; ToolCall{Start,Args,End,Result} ↔ our `tool_*`; our approval/budget/context/
  handoff/steer events map to their generic `CustomEvent`. **Two asymmetries worth noting:** AG-UI has
  *state snapshot/delta sync* we lack; we have *far richer human-in-the-loop gating* (approval, budget,
  context, autonomy dial) AG-UI does not standardise. Takeaway: our vocabulary is industry-aligned (a
  design-soundness signal) and S1 makes an AG-UI adapter a one-file add.
- **LangGraph time-travel** (checkpoint tree → rewind/edit/fork, like git commits): validates an extension
  ladder. We already have the *replay* substrate (`events.ndjson`) and a *coarse* resume (snapshot-level, 3
  states); the LangGraph-style "fork from any step with edited state" is the ambitious upgrade.
- **SSE `Last-Event-ID`** (the WHATWG-standard precise-reconnect mechanism): our attach replays the *whole*
  hub buffer on reconnect (the FE guards with a one-shot flag); per-event `id:` + resume-from-id is the
  standard, simpler pattern.

### §D.3 — Idea catalog (categorised, each mapped to a seam, with an honest effort/value read)

**A · Polish (code health, low effort)**
- **A1 · Per-event observability at the S1 chokepoint** — every event already carries `TraceContext`, and
  `AgentMetricCatalog`/`GenAiMetricCatalog`/`AgentTelemetry` already exist; emitting an OTel span / a
  per-event-type counter from the *one* `translate()` call gives uniform agent observability (tool-call
  rate, gate-fire rate, budget-overrun frequency) for ~free. **Seam S1. Low effort, real value. Best polish.**
- **A2 · Control-endpoint dispatch helper** — the autonomy/budget/steer/context handlers are repetitive
  `parse→delegate→200/404`; a tiny helper DRYs them. Minor (585 §B.4 judged a dispatch rewrite insufficient
  *alone*, but as a polish on the now-small core it's clean). Seam: the control core.

**B · Simplify (reduce surface — incl. one deliberate *non*-recommendation)**
- **B1 · Precise SSE reconnect (`Last-Event-ID`)** — stamp each event with an id; on reattach resume from the
  last id instead of replaying the buffer. Removes the FE one-shot-reattach hack, kills duplicate replay.
  **Seam S2. Moderate effort, robustness value.**
- **B2 · (CONSIDERED, NOT RECOMMENDED) generate the translator from `EventDescriptor`.** The FE types are
  generated from the schema; one *could* generate the backend translator's field-mapping too. Rejected:
  the translator carries real logic (conditional keys, the `ProposedBatchProjection`), full generation is
  hard, and the conformance test *already* makes drift impossible — so this is over-engineering (AHA). Noted
  to record the reasoning, not to do it.

**C · Extend (new capability on an existing seam)**
- **C1 · Run replay / "run inspector"** ⭐ — step through a finished run's `events.ndjson` *through the
  existing FE renderer* at controllable speed (scrub a timeline, pause on any event). The data (full history)
  and the renderer (the 21-event FE dispatcher) both already exist; this is a read-only replay driver + a
  view. **Seam S2. Moderate effort, high dev-tooling value** (debugging agent behaviour — especially valuable
  pre-users). **Standout (developer).**
- **C2 · Time-travel fork (LangGraph-style)** — rewind to a step, edit the messages, fork a new run branch.
  Our `resumeSession` is the primitive; the upgrade needs finer per-step checkpoints + a branching model.
  Seam S2/S3. **High effort, high power** — flag as ambitious, its own slice.
- **C3 · AG-UI protocol adapter** ⭐ — a sibling to `AgentEventSseTranslator` mapping `AgentEvent` → AG-UI
  events, making JustSearch's agent consumable by any AG-UI client (e.g. CopilotKit). Product value is low
  for a local-first single-user app, but it's the cleanest demonstration of S1's leverage and proves the
  vocabulary is standard-aligned. **Seam S1. Moderate effort. Standout (theoretical reach).**
- **C4 · `StateSnapshot` event (AG-UI-inspired)** — emit current run-state in one event so a late attacher
  reconstructs state without replaying ~1000 events; complements B1 and multi-device attach. Seam S1/S2.

**D · New UX (user-facing, grounded in data that already exists)**
- **D1 · Background-run inbox / "while you were away"** ⭐ — `presenceSince` + `background=true` runs already
  exist and persist; only the rich surface is a stub. Build the inbox: completion badges, grouped summaries,
  a "catch up on what the agent did" view. **Backend is DONE; this is pure FE.** **Seam S3. Moderate effort,
  high product value.** **Standout (UX) — the most shippable.**
- **D2 · Multi-agent handoff visualisation** — handoff events render as plain text today; a "who's running /
  team timeline" view. Events exist (S1); moderate FE effort.
- **D3 · Shareable / replayable run transcripts** — `handleSessionTranscript` already exports a run as JSON;
  paired with C1's replay engine, "view this run as a replay" becomes a shareable artifact. Seam S2.
- **D4 · Search-your-own-agent-history** ⭐⭐ — index each agent run (its final answer, the tools it ran, what
  it found) into the **same Lucene corpus the app already searches**, so the user searches their agent
  history with the same hybrid BM25+SPLADE+dense search ("what did the agent find about X last week?").
  Uniquely enabled by *search-engine ⨯ durable-runs* — neither AG-UI nor LangGraph gives you this; it's the
  product searching its own assistant's memory. Higher effort (the indexer is file-discovery-based, so runs
  need a synthetic document source feeding `IndexingDocumentOps`), but on-brand and genuinely novel.
  **Seam S2 ⨯ the product's search core. The most distinctive idea.**

### §D.4 — Synthesis & recommended first steps

The deep point: **585 made "what the agent did" a first-class, queryable, replayable object with one
authority.** That unlocks three families — *adapt/observe the stream in one place* (A1, C3), *do things WITH a
run* (C1 replay, D3 share, D4 search), and *surface runs the user missed* (D1 inbox).

If picking, the honest ranking by leverage × groundedness × effort:
1. **D1 (background-run inbox)** — highest shippable value, backend already done (pure FE).
2. **C1 (run replay inspector)** — highest dev-tooling value pre-users; data + renderer already exist.
3. **A1 (per-event observability)** — best effort:value ratio; one chokepoint, metric homes exist.
4. **D4 (search-your-agent-history)** — most novel/on-brand; bigger lift, deserves its own investigation.
5. **C3 (AG-UI adapter)** — best "theoretical reach"; validates the design and demonstrates S1's payoff.

None is urgent (no users, not in production). D1 and C1 are the two that turn 585's structural win into
something a person can *see*; A1 turns it into something a developer can *measure*.

**External sources:** AG-UI protocol ([copilotkit.ai 17 event types](https://www.copilotkit.ai/blog/master-the-17-ag-ui-event-types-for-building-agents-the-right-way),
[ag-ui-protocol/ag-ui](https://github.com/ag-ui-protocol/ag-ui)); LangGraph time-travel
([docs.langchain.com](https://docs.langchain.com/oss/python/langgraph/use-time-travel)); SSE `Last-Event-ID`
([WHATWG HTML spec §9.2](https://html.spec.whatwg.org/multipage/server-sent-events.html)).

## §E — §D implementation (execution record)

Implementation of the §D plan (`.claude/plans/nested-moseying-fern.md`, user-approved). Phases 0 + 1
landed and merged to `main`.

### §E.0 — Phase 0: one event→payload authority (merged)
Extracted `AgentEventPayloads` (app-agent-api) — `name()`/`base()`/`withTrace()` — the single mapping
both the wire translator (`AgentEventSseTranslator`) and the persistence path (`AgentRunStore.toPayload`)
delegate to. **Closed the persistence drift**: the durable record previously dropped `AgentProgress.severity`
+ the error `i18nKey`; it now carries them. `AgentEventPayloadConformanceTest` extended to pin the base
(persisted) mapping against `AgentRunShape.EVENT_SCHEMA` too, so all three representations are one
authority. **Live-verified**: a real agent run's persisted `progress` event now contains `severity`
(read back from `events.ndjson` via `/api/chat/sessions/{id}/events`).

### §E.1 — Phase 1 (merged)
- **A1 (observability)** — `agent.event.emit_total{event_type}` counter at the one publish chokepoint
  (`AgentLoopService.wrapEventConsumer`); metric smoke/wire-format tests green.
- **C1 (run-replay inspector)** — `replayMode` on `AgentSessionController` suppressing the four live-only
  side-effects (auto-approve/ceremony, Effect Journal, virtual-tool dispatch, cross-tab pointer); the
  persisted events feed the existing `dispatchEvent` + projection; a scrubber in `UnifiedChatView`; the
  finished-session rows in `RetrospectivePanel` (was a dead stub) now offer **Replay**.
- **D1 (background-run inbox)** — `loadPresence` diffs the prior snapshot and fires the one
  `emitEphemeralToast` on a background run's DONE/ERROR transition (only transitions observed this
  session); a bounded poll detects completion; inbox rows + the toast "View" open the run in the C1
  replay inspector.

**Verification.** typecheck + **2996 FE unit tests green**, incl. `RetrospectivePanel.test.ts` (renders the
drawer + the new Replay affordance) and a **new loop-isolation test** driving the real
open→`loadActive`→`loadSessions`→render path (notify-bounded; a loop would hang/explode it). All Java
conformance/metric/persistence tests green; a11y/presentation/style/run-renderer/steering gates green;
`build -x test` green.

**Live browser validation (the §D bar) — partial, with a documented environment limit.** A reproducible
renderer **freeze on opening the retrospective drawer** during validation was **root-caused as
environmental, not a code defect**: (a) it reproduced across a concurrently-thrashed shared dev stack AND
a hand-rolled worktree stack, with a flaky Chrome extension repeatedly disconnecting; (b) the new headless
loop-isolation test proves the open/load path does not loop; (c) on a clean, freshly-restarted **isolated
worktree stack the drawer renders fine** (screenshots: open drawer with Sessions/Timeline/History/Inbox
tabs; a finished session row with the **Replay** button), and the replay state machine drives correctly
(`replayMode=true`, cursor `7/7` via the injected run). **What stayed un-pixel-verified live:** the replay
**scrubber + replayed thread** and the **D1 completion toast** render inside the AI-gated agent-conversation
view, and the isolated worktree stack has **no model pack (AI Offline)**, so that view is locked. Those
renders are covered by typecheck + the headless test + the unit suite + the earlier (585-followup) live
attach smoke, where the SAME `dispatchEvent` rendered 9 event types live with AI active. The dev-stack
Vite-proxy also pointed at a stale/empty backend in the isolated run (a hand-rolled-stack plumbing quirk),
which is why the run was injected directly rather than fetched.

### §E.2 — Phase 2 (worktree `585d-phase2`)
The five "small wins", all source-verified before coding (B1's design collapsed once the per-run monotonic
`spanId` was found — it IS the SSE sequence, so no new hub field/signature churn):
- **B1 (Last-Event-ID)** — `RunEventHub.subscribe(observer, fromSeq)` replays only events newer than the
  reconnect cursor; `TraceContext.seq()` parses the monotonic `span-NNNNNN`; `AgentSseWriter.writeOrEvict`
  stamps the `id:` line from the payload trace (BOTH live + attach streams, no live-path signature change);
  `handleAttachStream` reads the `Last-Event-ID` header. `RunEventHubTest` +3 replay-from-seq cases.
- **C4 (StateSnapshot)** — new sealed permit (all 5 switch sites: `AgentEventPayloads.name/base`,
  `AgentEventTracing.withTrace/stepIdFor`, + the `AgentRunStoreTest` helper the compiler forced), emitted
  on attach as a state primer BEFORE replay (pairs with B1), FE consumer seeds the active agent. Conformance
  + FE codegen `--check` green.
- **D2 (handoff viz)** — `<jf-handoff-card>` composing the `jf-status-badge` atom (`origin="agent"`); the
  projection now carries `fromAgentId`/`toAgentId`/`reason` (was dropped to empty attributes).
- **D3 (shareable replay)** — `AgentSessionController.loadReplayFromExport` (strips the exported transcript
  to the replay shape) + per-finished-session **Export** + a **Load-a-shared-replay** file input in the
  retrospective drawer.
- **A2 (dispatch helper)** — `handleControl`/`okOr404` DRY the 5 control handlers (behaviour preserved
  verbatim).

**Verification.** `build -x test` green; app-agent/app-services/ui suites green; FE typecheck + **3005/3005**
unit tests (real-DOM happy-dom renders of `HandoffCard` + the panel affordances + `loadReplayFromExport`);
all shell-v0 governance gates pass (one pre-existing `strip-token-fallbacks` failure on an untouched
`GovernanceView.ts` logged to observations). **Live browser pixel-validation (the §D bar) — DONE** on a
worktree Vite dev server (after a Chrome/extension restart cleared a network fault that had 503'd all
Vite-dev sub-resources): the **D2 handoff card** renders "Handoff `researcher` → `writer` · reason" with the
two agent-toned badges; the **D3** retrospective drawer shows per-finished-session **Replay + Export**, the
**Resume** lifecycle projection, and the **Load-a-shared-replay** file input.

### §E.3 — Phase 3: C3 AG-UI protocol adapter (worktree `585d-phase2`, stacked)
The cleanest proof of seam S1: a SECOND projection of the agent stream is one self-contained file.
- `AgUiEventTranslator` (app-services) — sibling to `AgentEventSseTranslator`, maps each `AgentEvent`
  to an AG-UI event: lifecycle→`RUN_*`, text→`TEXT_MESSAGE_CONTENT`, reasoning→`THINKING_*`,
  tool→`TOOL_CALL_{START,ARGS,RESULT}`, the C4 `StateSnapshot`→`STATE_SNAPSHOT`, and the richer
  gating/handoff/budget events (no AG-UI analogue) → the `CUSTOM` catch-all (`default→CUSTOM` is
  deliberate-graceful, not an exhaustiveness gap).
- `AgentController.handleAgUiAttachStream` + route `POST /api/chat/agent/{sessionId}/ag-ui` — a thin
  reuse of `attachToRun` (the B1 `fromSeq` cursor + the disconnect-eviction) that writes through the
  sibling translator. Under `/api/chat/*` — the removed `/api/agent/*` namespace is NOT resurrected
  (Hard Invariant #3; `docs-api-drift-check` green).

**Verification.** `AgUiEventTranslatorConformanceTest` pins every `AgentEvent` permit → a valid AG-UI
type + the `payload.type == wire-name` envelope invariant + the specific lifecycle/state mappings
(this is what makes `default→CUSTOM` safe). `ui:compileJava` + the AgentController suite green;
`docs-api-drift-check` green. **Live `api_call` deferred** as a documented smoke: the endpoint is a
thin wrapper whose only novel behaviour (translation) is exhaustively conformance-tested, and it
reuses the Phase-2-verified attach path — spinning a second worktree backend to curl one AG-UI stream
is disproportionate for a low-value demonstrator while the shared dev stack is held by another agent.
*Smoke:* with a worktree backend, start a run, then `POST /api/chat/agent/{sessionId}/ag-ui` and assert
the SSE frames carry AG-UI `type`s (`RUN_STARTED` … `RUN_FINISHED`).

### §E.4 — Phase 4: D4 search-your-agent-history (worktree `585d-phase2`, stacked)
The product searching its own assistant's memory — durable-runs × the search core. Two committed halves.
- **D4a (ingestion):** `AgentHistoryIndexer` — a terminal-run listener (wired in `HeadAssembly` via a
  one-line `register`) that, on a `done`/`error` record, synthesises a markdown transcript (the final
  answer + the grounding sources the agent found) and indexes it into the dedicated `agent-history`
  collection via the explicit-collection ingest API (`submitBatch(.., "agent-history")`) — sidestepping
  the YAML-only watched-collection config. Off the hot path (daemon executor) + fail-soft.
- **D4b (search-side scope):** a `collection` filter following the exact `fileKind` pattern across
  proto (`SearchFilters.collection` #16) → `KnowledgeSearchRequest.Filters` → `RuntimeSearchFilters` →
  `ProtoConverters` → `SearchRequestMapper` → the FE `buildSearchIntent` seam (Head→Worker via gRPC,
  head-never-touches-lucene preserved). `QueryFilterBuilder` gains the scope: an explicit scope includes
  ONLY the requested collections; the DEFAULT excludes the reserved `agent-history` (a `MUST_NOT`,
  anchored with `MatchAllDocs`), so transcripts never pollute normal document search. FE: a
  "Documents | Agent history" scope selector in `SearchSurface`, re-issuing through the one seam.

**Verification.** `QueryFilterBuilderTest` pins default-exclude + explicit-include; `AgentHistoryIndexerTest`
pins the transcript; `searchState` test pins the FE scope→filter mapping; all affected Java suites + FE
**3006** unit tests green; `class-size` (HeadAssembly slimmed back under 1000), `search-issuance`,
`controls-a11y`, `color-tokens`, `accent-as-text`, `contrast` gates green. **Live browser-validated:** the
scope selector renders + toggles in the search surface. (The end-to-end filter against a live
agent-history index needs a worktree backend + a real run — the QueryFilterBuilderTest is the unit proof
of the exclude/include logic.)

**Decisions as built:** default search EXCLUDES agent-history (no pollution); transcripts indexed via the
explicit-collection ingest API (no new watched-folder config); the exclusion is one reserved-collection
rule in `QueryFilterBuilder` (`SchemaFields.AGENT_HISTORY_COLLECTION`), not a parallel exclude-filter.

### §E.5 — Phase 5: C2 time-travel fork (worktree `585d-phase2`, stacked)
LangGraph-style time-travel: branch a NEW run from a finished one by rewinding to its last user turn.
- **Backend:** `AgentRunQueries.forkSession` + `AgentRunQueryService.forkSession` reads the persisted
  snapshot, truncates messages at the last-user boundary (`forkMessages` — a clean cut ending at the
  user turn, dropping the prior response), optionally replaces the question, and constructs a fresh
  `AgentRequest` reusing the snapshot's tools/iterations/profiles — **no resume-state gate** (a fork is
  meaningful for a DONE/ERRORED run, unlike resume). It REUSES `runAgent` (the same entry resume uses).
  Endpoint `POST /api/chat/sessions/{id}/fork` (mirrors resume; under `/api/chat/*`).
- **FE:** `AgentSessionController.forkRun` (mirrors `resumeSession` — leaves replay + streams the fresh
  run) + a "Fork & edit" affordance with an inline editor in the C1 replay bar.

**Verification.** `AgentRunQueryServiceForkTest` pins the truncation boundary (edit / blank-re-roll /
no-user-turn → empty); `build -x test` + app-agent + ui suites + FE **3006** unit tests green;
`controls-a11y` / `presentation-purity` / `color-tokens` / `run-renderers` gates green. **Live pixel
validation env-limited:** the Fork affordance lives in the AI-gated agent-conversation view's replay
bar, which needs a live AI-active backend (unavailable at close — the shared dev stack went down); the
replay bar it extends was live-validated in Phase 1 (C1), and the fork logic is unit-proven.

## §F — §D execution complete (all 8 items)
All §D Phases 0–5 are implemented + committed: Phase 0 (event authority) + Phase 1 (C1/D1/A1) merged on
`main`; **Phases 2–5 (B1, C4, D2, D3, A2, C3, D4, C2) committed on `worktree-585d-phase2`, NOT yet
merged.** Six stacked commits (`70ea475e3` … `ccec03107`).

> **Merge note (worktree `585d-phase2`):** the branch is NOT merged — at the time of writing `main` was
> held by another agent's uncommitted work-in-progress (overlapping `docs/observations.md`), so merging
> would risk their work. **Merge deferred to when `main` is clean / coordinated.** Pre-merge: from a clean
> `main`, `./gradlew.bat build -x test`, resolve any `observations.md` append-conflict, merge, re-verify.
