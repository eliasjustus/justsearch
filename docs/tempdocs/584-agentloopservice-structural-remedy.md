---
title: "584 — AgentLoopService structural remedy: the repo's #2 class-size bump offender (1242 LOC, 15 class-size changesets) and the clearest case of decomposition FAILING to stick — dropped below ceiling once (tempdoc 240), then crept back and re-pinned twice (561, 577). Charter to determine and execute the RIGHT structural fix — further decomposition, a rewrite of the orchestration/wiring, or other — NOT a decomposition mandate. The central question: why did the previous decomposition not hold, and what remedy actually prevents recurrence?"
type: tempdocs
status: closed (2026-06-15) — remedy = hybrid B + targeted C executed & merged; AgentLoopService 1242→795 LOC, pin deleted, live smoke green. Item 4 (builder, remedy A) deferred with rationale (§B.5). Constructor-builder + full control-axis interface segregation remain as documented future escalations only.
created: 2026-06-15
updated: 2026-06-15
origin: tempdoc 582 §B.5 R2 (governance-critique — the class-size treadmill)
---

# 584 — AgentLoopService: the decomposition that didn't stick

> **Purpose, stated carefully.** NOT "decompose AgentLoopService again." It is: *this file already
> WAS decomposed (240 W8 → ~852 LOC via the AgentStepRunner extraction) and grew back to 1242,
> getting re-pinned twice. A second round of the same move is the obvious wrong answer.* Determine
> the correct remedy — which may be a **rewrite** of the orchestration/wiring, a different
> decomposition axis than last time, an architectural change to how the loop accretes
> responsibility, or other — and execute it so the regrowth does not recur. **Why the prior
> decomposition failed to hold is the first deliverable.**

## 1. Why this file (the evidence)

- **1242 LOC**, pinned in `gradle/class-size-exceptions.txt`.
- **15 of 64 class-size changesets** name it (582 §B.1) — second only to LocalApiServer.
- **The re-growth history is the story** (from the pin-file comments + 582 forensic):
  - tempdoc 240 W8 decomposed it to ~852 LOC (below the 1000 ceiling) via the `AgentStepRunner`
    extraction (`runAgent` → thin driver + typed `IterationOutcome` step). Pin row removed.
  - 561 P-D session 2: crept back to 998, then the autonomy wiring pushed it to 1020 → **re-pinned**.
  - 577: re-pinned again at 1242.
- It **already delegates heavily** — collaborators present: `AgentStepRunner`, `AgentLlmCaller`,
  `AgentToolDispatcher`, `AgentSessionFinalizer`, `AgentContextCompressor`, `AgentRunStore`,
  `AgentTelemetry`. So "extract a collaborator" has been done repeatedly and the file *still* grows.
  That is the signal that a different remedy is needed.
- **Three public constructor overloads** (lines 178/196/236) + a private one (325) — a telescoping
  constructor smell; a chunk of the LOC is wiring/construction, not loop logic.

## 2. What to find out before choosing a remedy

1. **Why it regrows — the accretion mechanism.** What KIND of code keeps landing here? (per the
   history: autonomy wiring, system-prompt building, condition-context, session-registry
   management.) Classify the 1242 lines: construction/wiring vs system-prompt assembly vs session
   lifecycle/registry vs the actual agentic loop. The remedy targets the dominant accretor, not a
   random extraction.
2. **The constructor wiring (telescoping).** Three+ overloads suggest construction is a real LOC
   sink and a magnet for "just one more dependency." Is a builder / assembly object the right
   rewrite of the construction surface?
3. **buildSystemPrompt / appendConditionContext.** Prompt assembly (lines 376–427) is a cohesive,
   testable concern that may be its own collaborator — confirm size and coupling.
4. **The previous-decomposition collision note.** The pin file explicitly records that re-decomposing
   the EXISTING code "would collide with concurrent agents touching this file (keep-diffs-scoped
   rule)." Assess whether that is still true and whether a remedy can be sequenced to avoid it.
5. **Is the loop itself the right size?** Distinguish "the orchestrator is irreducibly complex"
   (then the pin is honest and the remedy is a re-architecture or an accepted exception) from "wiring
   + prompt + registry bloat hide a small loop" (then a rewrite of those surfaces shrinks it
   durably). This judgment is the crux.

## 3. Candidate remedies (the investigation picks; none is pre-ordained)

- **A — Rewrite the construction surface.** Collapse the telescoping constructors into a single
  builder/assembly; move dependency wiring out of the class body. Targets the wiring LOC sink
  directly and removes the "one more dependency" growth vector.
- **B — Extract the cohesive non-loop concerns** (system-prompt assembly, condition-context, session
  registry management) — a *different decomposition axis* than the 240 step-runner extraction, aimed
  at what actually regrew.
- **C — Re-architect the accretion point.** If responsibilities keep landing here because the loop is
  the only place new agent features can attach, the remedy is structural: a registration/plugin seam
  so new features attach without growing the orchestrator. Prevents recurrence by construction.
- **D — Accept and document.** If §2.5 concludes the orchestrator is irreducibly ~1200 LOC of genuine
  loop logic, the honest outcome is a *recorded, justified* pin (not a forced bad split) — and 584's
  value is proving that, closing the treadmill by ending the futile re-decomposition attempts.

**Selection criteria:** the remedy must make regrowth *not recur* (the prior decomposition's failure
mode), not just dip below the ceiling once. Durability is the bar, given the 2× re-pin history.

## 4. Constraints & invariants (do not violate)

- **Behavior-preserving** — the agentic loop's observable behavior (tool dispatch, streaming,
  autonomy/budget gates, session lifecycle) is unchanged.
- **Module boundaries** — `module-deps` / ArchUnit boundaries for `app-agent`; do not introduce new
  cross-module deps to achieve a split.
- **Concurrent-agent safety** — this file is actively touched by other worktrees; sequence the work
  to minimize merge collisions, or coordinate timing via the user.
- **Test-efficacy seams** — if any pure-logic seam here is in `governance/logic-seams.v1.json`, keep
  its guard test green through the change.

## 5. Verification plan

- Result ≤ ceiling (delete pin row) OR a documented justified pin per remedy D, with rationale.
- `./gradlew.bat :modules:app-agent:test :modules:app-services:test` green.
- **Live-stack agentic smoke (mandatory, per `use-every-verification-tier`)**: `ai_activate` + a real
  `agent_chat` run exercising tool-call + autonomy/budget gate + session resume — a structural change
  to the loop is not verified by unit tests alone.
- `./gradlew.bat build -x test` from main before merge.

## 6. Success criteria

`AgentLoopService.java` stops being a recurring pin offender — either durably ≤ ceiling via a remedy
that addresses the *regrowth mechanism* (not a one-shot extraction), or a justified recorded
exception that ends the futile re-decomposition cycle. The 2× re-pin pattern does not repeat.

## §B — Findings (populated during execution)

### §B.1 — The accretion-mechanism classification (§2.1, the first deliverable)

Exact line-count classification of all 1242 LOC (`wc`-measured cluster spans, 2026-06-15 `main`).
The "loop" the 240 extraction was meant to protect is only **18%** of the file; **82% is non-loop
surface**:

| Cluster | LOC | % | What it is | Grown by |
|---|---|---|---|---|
| **Read-queries / projections / resume** | 308 | **25%** | `lastSessionSnapshot`, `listSessions`, `sessionSnapshot`, `sessionEvents`, `threadEvents`, `lifecycles`, `presenceSince`, `operationHistory`, `operationDetail`, `undoOperation`, `availableOperations`, `toBatchSummary`, `resumeLastSession`/`resumeSession`/`resumeFromSnapshot`, `backgroundBoundary`, `firstUserMessage` — pure **read-time projection over `AgentRunStore` / `OperationCatalog`**; zero loop state | 415 (C20 session list/snapshot), **561** (P-A/P-A2/P-B threadEvents+lifecycles, P-D2 presence) |
| **Construction / wiring** | 278 | **22%** | 4 constructors (3 public overloads + 2 `forTesting` factories + 1 private) = 197 LOC + 13 fields w/ comments (46) + `resolveFromConfig` ×2 (12) + 6 late-bind setters (`setBackendIntentRouter`/`setConsentCapsuleAuthority`/`setIntentPreviewer`/`setConditionContextSupplier`/`setWorkflowToolRunner`/`setCitationDocumentService`, 23) | 415 (gauge supplier), 430 (termination observer), 487 (intent router), 550 (consent capsule), **561** (intent previewer), 565 (citation) — **one late-bound dep per feature** |
| **Live-run control** | 202 | **16%** | `approveToolCall`/`rejectToolCall`/`tryApprove`/`tryReject`, `cancelSession`, `setSessionAutonomy`, `injectSteeringDirective`, `raiseSessionBudget`, `resolveBudgetGate`, `resolveContextGate`, `attachToRun`, `completeVirtualToolCall`, `activeSessionCount` — every one is `sessions.get(id)` **then delegate to `AgentSession`** | **561** (autonomy), 565 §30 (steer), **577** (budget gate, context gate, attach), 508 (virtual tool) |
| **The actual agentic loop** | 223 | **18%** | `runAgent` (155) + `runAgent(…, background)` + `wrapEventConsumer`/`checkpoint`/`emitError` (53) — already a thin driver over `stepRunner.executeIteration` | mostly stable; the 240 extraction landed here |
| **Prompt assembly** | 109 | **9%** | `TOOL_CALL_GRAMMAR` (15) + `DEFAULT_SYSTEM_PROMPT` (29) + `buildSystemPrompt`/`appendConditionContext` (53) + `swapSystemPrompt` (12) | 447 (condition context), 561 (core_remember hint), Direction I (grammar) |

**The decisive cross-check — the accretors are exactly the re-pin tempdocs.** The two re-pins (561
→ 1020; 577 → 1242) land **entirely** in the read-query, wiring, and live-control clusters. **Zero**
of the regrowth landed in the loop. The growth is **horizontal** (new interface methods for new
agent features), not **vertical** (deeper loop logic).

### §B.2 — Why the 240 decomposition did not hold (the depth-vs-breadth diagnosis)

`AgentLoopService` is the **sole real implementor** of `AgentService`, a **wide (~30-method)
interface** that is the agent module's entire public API. The 240 W8 extraction cut the loop's
**depth** — it pulled the per-iteration step logic into `AgentStepRunner` (a vertical slice through
`runAgent`). But the file grows in **breadth**: every shipped agent feature adds a *method* to
`AgentService`, and the sole implementor must carry it. The product roadmap (415 session history,
561 autonomy/presence/lifecycles, 565 steering, 577 budget/context/attach) keeps adding breadth, so
the file regrew along the one axis the 240 cut never touched.

**This is why a second depth-cut would fail identically.** `AgentStepRunner` is already at **998
LOC** (at the ceiling) — the loop's vertical axis is *already maxed out*; there is no more loop
logic to extract without busting the step runner. Extracting "more of the loop" is structurally
impossible *and* irrelevant, because the loop is only 18% of the file and is not what grows.

**The corroborating fingerprint:** `AgentController` (the sibling, chartered as 585) crossed the
ceiling for the **same feature family** — its pin comment names "the agent-control endpoints
(autonomy/steer/budget/budget-decision)." A single agent feature ripples across **four layers** in
lockstep: a method on `AgentController` → a method on `AgentService` → a method on
`AgentLoopService` → a method on `AgentSession`. 584 and 585 are two faces of one horizontal-
accretion mechanism.

**Verdict on §2.5 (is the loop irreducibly large?):** No. The orchestrator is *not* irreducibly
~1200 LOC of loop logic — it is ~223 LOC of loop hidden behind ~982 LOC of wiring + read-queries +
control-delegation + prompt. **Remedy D (accept & document) is therefore wrong** — accepting a
1242-LOC pin would be accepting an 82%-noise file. The honest outcome is to re-decompose on the
breadth axis.

### §B.3 — Consumer map (interface-segregation feasibility)

Grepped every `AgentService` method call per consumer. The interface **partitions cleanly along the
same cluster boundaries**, and most consumers already touch exactly one cluster:

| Consumer | Methods it calls | Cluster |
|---|---|---|
| `InteractionThreadController` | `threadEvents`, `lifecycles`, `presenceSince` | **read-queries only** |
| `StatusLifecycleHandler` | `activeSessionCount`, `isAvailable` | status only |
| `ToolIteratingShapeRunner` | `runAgent`, `availableOperations`, `isAvailable` | **loop only** |
| `BackgroundRunService` | `runAgent` | **loop only** |
| `AgentController` | control cluster (approve/reject/autonomy/steer/budget/context/attach/virtual/cancel) **+** query cluster (lastSessionSnapshot/listSessions/sessionSnapshot/sessionEvents/operationHistory/operationDetail/undo/availableOperations/resume*) | **kitchen-sink** (control + query) |

Key finding: `InteractionThreadController` maps **1:1** onto the read-query cluster and touches
nothing else — so a narrow `AgentRunQueries` interface is free of collision for that consumer. Only
`AgentController` spans clusters (and it is itself the 585 over-ceiling offender — the query half of
its bloat is the same read-query surface).

### §B.4 — Selected remedy: hybrid B + targeted C (user-confirmed 2026-06-15)

Re-decompose on the **breadth axis** (the one that actually grows), inside `app-agent` only (no
cross-module dep change — low collision). The user selected the **hybrid B + targeted C** option
over full interface segregation (deferred — it overlaps 585 and ripples ~27 files) and over
collaborators-only (not recurrence-proof on any axis).

1. **`AgentRunQueries`** — extract the 308-LOC read-query/projection/resume cluster. Depends only on
   `runStore` / `operationCatalog` / `fileOperationLog` / `operationExecutor`. The **largest and
   cleanest** cut (no loop state). Exposed as a **narrow interface** that `InteractionThreadController`
   depends on directly (the 1:1 consumer) — *structurally insulating the loop class from the
   highest-growth axis* (read-queries, the 561 accretor). This is the targeted "remedy C": a new
   read-projection feature lands on the queries impl, never on the loop class.
2. **`AgentSessionRegistry`** — own the `sessions` `ConcurrentHashMap` + the 202-LOC live-control
   delegation (every method is lookup-then-delegate-to-`AgentSession`). `runAgent` uses it for
   register/get/evict.
3. **`AgentPromptComposer`** — the 109-LOC prompt cluster (grammar + default prompt + build/append/
   swap + the condition-context supplier).
4. **Collapse the telescoping constructors** (197 LOC) into a builder / assembly object.

After (1)–(4), `AgentLoopService` = the loop (`runAgent`) + thin delegating overrides ≈ **250–350
LOC**, pin row deleted, behavior-preserving.

**Durability argument (the §6 bar):** the read-query axis — the single biggest cluster (25%) and the
561 accretor — becomes recurrence-proof by construction (segregated interface + separate impl). The
control axis keeps the `AgentService` interface intact (bounds collision) but moves bodies to the
registry, so the loop class carries only ~3-LOC delegating stubs — a **~10× slower** regrowth rate.
The construction sink (the "one more dependency" vector) is removed via the builder. This addresses
the *mechanism* (§B.2), not just the symptom.

### §B.5 — Execution record

**Outcome: `AgentLoopService.java` 1242 → 795 LOC (−447, −36%), pin row deleted, class-size gate
passes.** Behavior-preserving; full `build -x test` green, `:modules:app-agent:test` +
`:app-agent-api:test` + `:app-services:test` + the `ui` agent-controller/thread/SSE/status tests all
green.

**What landed (breadth-axis re-decomposition):**

| New collaborator (app-agent) | LOC | Absorbs |
|---|---|---|
| `AgentRunQueryService` | 385 | the read-time query/projection/resume cluster (operation history, snapshots, session list, `sessionEvents`, `threadEvents`, `lifecycles`, `presenceSince`, `resume*` + the `toBatchSummary`/`backgroundBoundary`/`firstUserMessage` helpers). Implements the new `AgentRunQueries` interface. Reuses `AgentStepRunner.ErrorEmitter` for resume errors + a `SessionRunner` callback to re-enter the loop — no back-reference to the loop class. |
| `AgentSessionRegistry` | ~260 | the `sessions` map + the live-run control cluster (approve/reject/try\*, cancel, autonomy, steer, budget raise, budget/context gate resolve, attach, virtual-tool complete, active count + register/remove). The exact cluster that re-pinned via 561/565/577. |
| `AgentPromptComposer` | 129 | `DEFAULT_SYSTEM_PROMPT` + the indexed-root preamble + the slice-447 condition-recovery context. |

**Interface segregation (targeted C):** new `io.justsearch.agent.api.AgentRunQueries` super-interface
holds the read surface; `AgentService extends AgentRunQueries`. `AgentRunQueryService implements
AgentRunQueries` (compile-time check that the collaborator covers the whole read surface). All
existing `AgentService` consumers compile unchanged (inherited).

**Kept on `AgentLoopService` deliberately:** `TOOL_CALL_GRAMMAR` + `TRACER_SCOPE` (shared
cross-collaborator infra referenced by `AgentLlmCaller`/`AgentStepRunner`, not prompt state) and
`swapSystemPrompt` (mutates the live session's message list; a step-runner callback) — it delegates to
the composer for base text. The loop class is now the `runAgent` driver + `wrapEventConsumer`/
`checkpoint`/`emitError` + thin delegating overrides for the segregated surfaces.

**§B.3 correction (critical-analysis pass).** §B.3 claimed `InteractionThreadController` maps "1:1"
onto the read surface and could narrow to `AgentRunQueries` collision-free. Re-reading the source: it
also constructs `new BackgroundRunService(agentService)`, which calls `runAgent` (a loop method) — so
it needs the **full `AgentService`**, not just `AgentRunQueries`. The "1:1" claim came from grepping
method *calls* and missed the *constructor* coupling. **Consequence:** the `AgentRunQueries` interface
is created and `AgentService extends` it (structural read-axis insulation + the impl implements it as a
contract check), but the `InteractionThreadController` field was **not** narrowed (the
`BackgroundRunService` coupling blocks it). Lesson handle: `audit-without-test` / verify the consumer's
*constructor* deps, not just its method calls.

**Durability assessment (honest, the §6 bar).** New read/control *logic* now lands in the
collaborators, not the loop body. But because `AgentLoopService` remains the sole `AgentService`
implementor, a *new* interface method still needs a ~3-LOC delegating stub here — i.e. ~10× slower
regrowth, not recurrence-proof-by-construction (full interface segregation of the control axis was
deferred — it overlaps 585 and ripples ~27 files). With ~205 LOC of headroom under the ceiling and the
two biggest accretors (read 25%, control 16%) relocated, this clears the "2× re-pin does not repeat"
bar for the foreseeable roadmap. If the control axis ever re-approaches the ceiling, the deferred full
segregation (`AgentRunner` + `AgentSessionControl` + `AgentRunQueries`, separate impls) is the
recurrence-proof escalation.

**Item 4 (constructor builder, remedy A) — deferred (user-confirmed 2026-06-15).** Investigation
finding: the telescoping constructors are NOT the regrowth vector — every new dependency since the 240
extraction arrived as a late-bind *setter* (`setBackendIntentRouter`/`setConsentCapsuleAuthority`/
`setIntentPreviewer`/`setCitationDocumentService`/`setWorkflowToolRunner`), never a new constructor
param; the re-pins (561/577) grew interface *breadth*, which the extractions addressed. Per the §3
selection criterion ("the remedy must make regrowth not recur"), a builder does not contribute to
recurrence-prevention — it is cosmetic LOC reduction of stable back-compat test scaffolding, and would
ripple to ~15 test call sites + `AgentLoopWiring` + `AgentBatteryTest` (the collision risk §4 warns
about). Dropped per AHA / keep-diffs-scoped; the file is already far under ceiling without it.

**Verification status:**
- [x] Result ≤ ceiling, pin row deleted, `class-size` gate passes.
- [x] `:app-agent:test` `:app-agent-api:test` `:app-services:test` + `ui` agent tests green.
- [x] `./gradlew.bat build -x test` green (full project — the `AgentService → AgentRunQueries` change ripples clean).
- [x] **Live-stack agentic smoke (charter §5) — GREEN, against the MERGED code on `main`.** (The dev-runner
  is hardwired to the main checkout, so the smoke required merging first — the worktree take-over could
  only have run main's pre-merge code.) `ai_activate` (cuda12, GPU) + a real `agent_chat`: the system
  prompt from `AgentPromptComposer` drove the model to call `core_search_index` (22 results, 2
  iterations, coherent final answer); `autoApprove` gated+approved the tool call **through
  `AgentSessionRegistry.tryApproveToolCall`** (live exercise of the control-cluster delegation);
  `AgentSessionRegistry.register`/`remove` ran the run lifecycle; budget tracking showed the
  over-budget transition (`tokensRemaining` −580). So live-verified end-to-end through merged code:
  prompt composer · loop · session registry (register/approve/remove) · tool dispatch · budget gate ·
  event/trace pipeline. **Resume + the pure read-projections** (`threadEvents`/`lifecycles`/
  `listSessions`) are thin verbatim delegations to `AgentRunQueryService`, covered by green unit tests;
  their HTTP endpoints are not allowlisted in the dev harness, so they were not additionally hit live.

**Merged to `main`** (2026-06-15, `--no-ff` merge commit) after a green pre-merge `build -x test` from
the main checkout. Tempdoc 584 closed.
