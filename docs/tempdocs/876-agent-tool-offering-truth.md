---
status: IMPLEMENTED
created: 2026-08-26
updated: 2026-08-26
follows: 868 §C.3/§D.1, 550 F3, 560 WS5
owner-session: f6617483
---

# 876 — Agent-tool offering truth: availability, guards, inventory

What the model is actually offered on a run, why it is offered, and whether anything in the
repo can tell the truth about it. Sibling workstreams: 875 (resolve-against-offered-set),
877 (centralisation / dead declarations).

## Findings handed over

(Independent review + live observation 2026-08-26; every line reference is to be re-verified
against this worktree before it is relied on.)

1. **CRITICAL — the model's tool list is a function of a browser polling loop.**
   `core.search-index` and `core.read-document` declare
   `Not(ConditionMatches("index.unavailable"))`. The condition is asserted/cleared ONLY by
   `LifecycleSnapshotTap.reconcileDim` (`LifecycleSnapshotTap.java:~380-407`, mappings
   `~145-167`), whose sole production caller is `StatusLifecycleHandler.buildStatusMap`
   (`~452-458`) — i.e. `GET /api/status`. The FE polls every 10 s (`statusPoll.ts:~28`) and
   stops when unsubscribed. It is a LATCH: empty store = not firing (tools offered at t=0),
   an early poll asserts `worker.starting → index.unavailable`, clearing needs another poll.
   API-only clients (jseval `backend.py:~849-874` polls until `indexAvailable`, which flips
   before INDEX_SERVING=READY; MCP runs; direct `POST /api/chat/agent`) freeze the assertion
   for the process lifetime. Observed live: an agent run offered only the five ungated tools;
   one `/api/status` fixed it. Availability is also evaluated ONCE per run
   (`AgentLoopService.java:~549`). Fails unsafe both ways. Design: reconcile on readiness
   transitions (`CapabilityHealthBridge` already listens event-driven at
   `OrchestrationPhase.java:~116-122`) or evaluate availability from the readiness envelope at
   emit time; re-evaluate per iteration or at least at recovery. The comment at
   `AgentToolsOperationCatalog.java:~159-165` claiming the condition "is reliably cleared when
   it returns to READY" is false — fix it. Also correct tempdoc 868 §C.3's last paragraph: the
   `tools` selection `NO_TOOLS` was the SAME defect (selection matched, availability dropped
   the survivor; `matchesSelection` accepts both name forms).

2. **HIGH — every guard validates the declaration, none the offering.**
   `RegistrySnapshotExporter.java:~62-63` and `LiveWitness.java:~19-20` build from
   `new CoreOperationCatalog()`/`new AgentToolsOperationCatalog()` (static bases) while
   `governance/declaration-kinds.v1.json:6` defines `AGENT_OFFERING` as "witnessed by the real
   AgentOperationEmitter… bidirectional". `ValidatorRunnerTest.java:~226-229` validates static
   bases and at `~91` registers a stub `REMEMBER` handler to make `ExecutorBindingValidator`
   pass (working around finding 4). `AgentOperationEmitterTest.java:~279-384` never combines a
   non-empty selection with a probe; the static `emitOperations`
   (`AgentOperationEmitter.java:~273-280`) backing `AgentOperationEmitterRegressionTest`
   applies `matchesSelection` but neither `filterForTarget` nor `isAvailableNow`. Point
   exporter/witness/validators at `deriveAndPartition(...).agentToolsCatalog()` and the
   filtered offering; add the test "build the substrate, do not poll status, assert
   `core_search_index` is offered".

3. **HIGH — two of nine offered tools carry raw i18n keys as descriptions.**
   `WorkflowOperationProjection.toOperation` (`~70-95`) uses keys from
   `registry-workflow.en.properties`; the emitter's resolver loads only
   `registry-operation.en.properties` (`BootstrapHelpers.java:~105-118`, miss → key returned,
   `HeadAssembly.java:~568`). The model receives
   `"description":"registry-workflow.research-brief.description"`. `core.demo-compose`
   (`CoreWorkflowCatalog.java:~107-125`) is an "add 2 and 40" demo needing an optional MCP
   server. Fold both property files into the resolver (or one file); gate demo-compose behind
   its MCP config or remove it from the agent offering; make `I18nKeyValidator` cover the
   composed set so this class of defect is caught at build.

4. **MEDIUM — `core_remember` offered but unhandled on the eager path.**
   `AgentToolHandlers.registerEager` registers 5 handlers, `registerLateBound` 6 (adds
   REMEMBER) and short-circuits on SEARCH_INDEX (`~103-105`). Collapse to ONE idempotent
   `(OperationRef → handler)` table driven by `AgentToolFactory.Output` (pattern:
   `AgentToolFactory.build→assemble`, tempdoc 832). Also the `AgentToolHandlers:~156` log
   string hand-lists tools.

5. **MEDIUM — the trust panel shows a different set than the model gets.**
   `AgentToolsController.handleListTools` (`~50-53`) reads raw `definitions()` with no
   availability/audience filter through a SECOND projection (`~224-241`) emitting raw i18n
   keys and re-implementing the virtual-op collision rule inline (`~56-83`).
   `AgentSessionController.ts:~529-533` calls this "the agent's AUTHORITY SPACE". Make the
   controller call `AgentOperationEmitter` and project the envelope; delete the duplicate. Fix
   its comment (`~233-237`) claiming the agent view includes CORE ops (they partition away,
   `OperationCatalogComposition.java:~111-117`).

6. **LOW — prompt ↔ tool-list disagreement.** `AgentPromptComposer.DEFAULT_SYSTEM_PROMPT`
   names 5 of 7 tools and neither workflow; the properties descriptions
   (`registry-operation.en.properties:~138,153,158`) say `browse_folders` not
   `core_browse_folders`; the search description promises a `mode` parameter the Interface
   withholds. Decide: derive prompt tool guidance from the catalog, or at minimum make
   `AgentToolsOperationCatalogTest` assert every parameter named in a description exists in the
   Interface and every tool name in prose is a real wire name.

7. **Coordinate:** workstream 877 (centralisation) owns deleting dead `PARAMETER_SCHEMA`s and
   structuredData key constants; workstream 875 owns resolve-against-offered-set. You own
   everything about *what is offered and why*. Keep diffs scoped so merges are clean.

**Acceptance:** regression tests for 1–5 that fail on main; the `Agent tools offered` log line
unchanged (it is the instrument); governance `check-live-witness` + `operation-surface` green;
docs/22 updated if the offering model changes.

---

## §A. Theorization (2026-08-26, before design)

### A.0 Findings re-verified against this worktree

Every load-bearing line reference in the handover was re-read. Corrections and confirmations:

| # | Claim | Verdict in this tree |
|---|---|---|
| 1 | `reconcileDim` is the only writer of `index.unavailable`; `StatusLifecycleHandler` its only production caller | **Confirmed.** `LifecycleSnapshotTap.java:380-409`; INDEX_SERVING rows `144-174`; sole `accept(...)` call site `StatusLifecycleHandler.java:452-459`. Four sibling taps (worker, index-drift, at-rest, conversation-protection) hang off the same request handler at `461-508` — the trigger defect is the substrate's, not this condition's. |
| 1 | availability evaluated once per run | **Confirmed.** `AgentLoopService.java:549-561`; `baseTools` is then passed to every iteration at `577-580`. |
| 1 | the catalog comment is false | **Confirmed.** `AgentToolsOperationCatalog.java:158-164` — "reliably cleared when it returns to READY" is true of `reconcileDim` in isolation and false of the system, because nothing calls it. |
| 2 | exporter/witness run static bases | **Confirmed with a correction.** `RegistrySnapshotExporter.agentWitnessDeliveredIds()` (`272-298`) builds `new CoreOperationCatalog()` + `new AgentToolsOperationCatalog()` and emits through a **bare** `new AgentOperationEmitter()` — no availability probe, no virtual store, and no composed workflow/MCP ops. `LiveWitness` itself takes the LIVE registry (`60-93`) and is not a static-base offender; the handover's `LiveWitness.java:~19-20` pointer lands on the javadoc that *describes* the static runtime-witness tier. The real static tier is the exporter + `scripts/governance/gates/runtime-witness/enforcer.mjs`, which consumes `witness.agentDelivered` from that snapshot. |
| 3 | workflow descriptions reach the model as raw keys | **Confirmed.** `WorkflowOperationProjection.toOperation` (`70-96`) carries `workflow.presentation()` through; `CoreWorkflowCatalog` (`84-124`) uses `registry-workflow.*` keys; the emitter's resolver is `HeadAssembly.java:566-568` over `BootstrapHelpers.loadRegistryOperationMessages()` (`105-118`), which loads `registry-operation.en.properties` only. Miss → key returned verbatim (`getProperty(key, key)`). |
| 3 | `demo-compose` needs an optional MCP server | **Confirmed.** `CoreWorkflowCatalog.demoCompose()` composes `vendor.mcphost.reference-add` + `…reference-get-image`; `SubstratePhase.java:193-216` shows the MCP host is empty by default. |
| 4 | eager path registers 5, late-bound 6 | **Confirmed**, and the mechanism is sharper than "two paths": `registerLateBound` short-circuits on `resolve(SEARCH_INDEX).isPresent()` (`AgentToolHandlers.java:103-105`) — **one ref used as a proxy for "everything is registered"**. Any eager registration of SEARCH_INDEX therefore permanently suppresses REMEMBER. |
| 5 | the trust panel shows a different set | **Confirmed with a correction.** `AgentToolsController.handleListTools` (`50-53`) does not read `definitions()` directly — it calls `agentService().availableOperations()`, which is `AgentRunQueryService.java:76-78` → `List.copyOf(operationCatalog.definitions())`, i.e. the raw agent-tools partition with no audience filter, no availability filter, no selection. Same destination, one hop further. The inline virtual-op collision rule (`56-83`) duplicates `AgentOperationEmitter.collidesWithCore` (`196-211`). The comment at `233-240` claiming the agent view is "core + agent-tools + MCP" is wrong: `OperationCatalogComposition.deriveAndPartition` (`104-121`) sends CORE-owner ops to the *other* catalog. |
| 5 | (new) `availableOperations()` has other consumers | `AgentSseWriter.java:75`, `ToolIteratingShapeRunner.java:134`, `ConversationApiAssembly.java:104` index it **by tool name to resolve a name the model already emitted**. Those want the full catalog, not the offering. Narrowing `availableOperations()` globally would be a regression — the fix must add an offering-shaped read, not re-point the existing one. |
| 6 | prompt ↔ tool-list disagreement | **Confirmed and worse than stated.** `AgentPromptComposer.DEFAULT_SYSTEM_PROMPT` names `core_browse_folders`, `core_search_index`, `core_file_operations`, `core_read_document`, `core_remember` — 5 of 7, missing `core_ingest_files` and `core_navigate_to_surface`, and neither workflow. `registry-operation.en.properties:138` tells the model "Supports keyword (text), semantic (vector), and hybrid modes. Use text for… use hybrid for… use vector for…" while `AgentToolsOperationCatalog.searchIndex()` (`143-149`) declares only `query`/`limit`/`path_prefix` and its own comment says `mode` is withheld **on purpose**. Three descriptions (`138`, `153`, `158`) say `browse_folders`, a name that does not exist on the wire. |

### A.1 What the problem actually is

There is exactly one fact — *the JSON array of tool definitions this run put on the wire* — and
**six** artifacts in the tree that claim to describe it:

1. `AgentOperationEmitter.emit(catalog, selection)` at `AgentLoopService.java:549` — the fact itself.
2. `GET /api/chat/agent/tools` — what the trust panel calls the agent's authority space.
3. `RegistrySnapshotExporter.agentWitnessDeliveredIds()` — the build-time "runtime witness".
4. `LiveWitness.orphanedDeliveries` — the runtime consumer-presence witness.
5. `AgentPromptComposer.DEFAULT_SYSTEM_PROMPT` — prose the model reads about its own tools.
6. the handler registry — what can actually *execute* when the model calls one.

Five of the six disagree with the first, in five different directions. That is not five bugs; it
is the **representation-drift class** (tempdoc 553) applied to capability rather than to search
execution — the same shape the `execution-surfaces.v1.json` register exists to prevent for
`SearchTrace`. The candidate general rule:

> **A capability list shown to a model is a wire artifact. Every other view of it is a
> projection and must derive from the emit path — never re-derive from the declarations.**

That single rule subsumes findings 2, 3 and 5 and most of 6.

### A.2 Framings of the latch (finding 1)

The mechanism is settled; the *framing* determines the fix. Four are on the table.

**(F-i) It is a trigger defect.** `ConditionStore` looks like state and is a cache: it is only as
fresh as the last `/api/status`. The fix is to reconcile on something other than a request. The
precedent is already in the tree and is event-driven **and self-seeding**:
`CapabilityHealthBridge.wireListeners` (`48-97`) subscribes to `WorkerCapability` /
`InferenceCapability` transitions, pushes `worker.capability` into the same `ConditionStore`, and
replays current state at wire time so a transition that happened before the listener existed is
not lost. Everything the tap needs already exists one layer down — it just is not wired to it.
Attraction: fixes all five taps at once. Risk: the envelope is built inside `buildStatusMap`
behind a Worker gRPC call (`StatusLifecycleHandler.java:412-431`); driving that from a capability
listener runs it on the transition thread. Any such wiring must be off-thread and fail-soft, and
that is precisely the kind of change whose failure mode is a boot deadlock.

**(F-ii) It is a read-side honesty defect.** `ConditionAvailabilityProbe` (`39-52`) answers
"is X firing?" from a snapshot with no freshness stamp, and `AvailabilityEvaluator` has no
representation for "unknown". A store that was never reconciled and a store that was reconciled
and found healthy are indistinguishable — the classic tri-state collapse
(`slice-execution.md`: *don't conflate unknown with healthy*). Note the two directions fail
differently: never-reconciled reads as *healthy* (tools offered at t=0 even though the worker is
still starting) and stale-asserted reads as *unhealthy forever*.

**(F-iii) It is an asymmetry defect — and this is the most interesting one.** For a tool
*offering*, the two errors are not symmetric:

- **False positive** (offer a tool that then fails): the model sees an error string, and adapts.
  868 §C.4 shows exactly this recovery in practice.
- **False negative** (hide a tool that works): the model cannot see what it was not shown. 868
  §C.3 recorded the consequence — asked to read a document with `core_read_document` absent, the
  model invented `core_file_operations {"operations":[{"operation":"read"}]}` and its own
  reasoning trace said it wanted `core_read_document`. An absent capability is not experienced as
  an absent capability; it is experienced as a *reason to improvise*.

So: **offering should fail OPEN; execution should fail CLOSED.** Tempdoc 550 F3's motive —
"the model is not offered a tool it provably cannot run right now" — survives this intact,
because *provably* is the load-bearing word and a never-reconciled latch proves nothing.

**(F-iv) Dissolve it: annotate instead of subtract.** Keep the tool in the list always and put
its current unavailability in the description ("currently unavailable: the index is not
serving"). A stale annotation is then a cosmetic bug rather than a capability loss, and the
model gets a *legible* reason instead of silence — which is the same principle the system prompt
already asserts for refusals ("say precisely which capability is missing"). Cost: a description
that changes between runs weakens prompt caching, and it is a bigger behavioural change than the
defect requires. Recorded as the radical option; F-iii is its bounded cousin and gets most of
the value.

These are not exclusive. F-i + F-iii together are the honest combination: fix the trigger so the
store tells the truth, *and* make the offering's failure direction the safe one so a future
trigger regression cannot silently amputate the model again.

### A.3 The once-per-run axis

Even a perfectly reconciled store is sampled once, at `AgentLoopService.java:549`. A 12-iteration
run can outlive a worker restart in both directions. Options and their tensions:

- **Re-emit every iteration.** Cheapest correct thing (the emit is a few object allocations over
  ~10 operations). But a tool list that changes mid-conversation is a *context* change: it
  invalidates prompt caching, and a tool vanishing after the model has already called it in the
  same transcript is incoherent from the model's point of view.
- **Re-emit only on change**, i.e. recompute and compare, replacing the list only when it grew.
  Monotone-growth is the coherent variant: a tool may APPEAR mid-run (recovery) but never
  DISAPPEAR. That is exactly F-iii's asymmetry applied on the time axis, and it is the direction
  design should probably take.
- **Leave the list alone; make dispatch honest.** That is workstream 875's lane
  (resolve-against-offered-set), and the two must not both edit the dispatch seam.

### A.4 Descriptions: two catalogs, one model (finding 3)

The interesting part is *why* the split exists rather than that it does. The FE resolves keys
because it needs a locale it picks at runtime; the model cannot resolve anything. So the rule is
not "always resolve" but:

> **Anything projected to a MODEL must be resolved server-side. Anything projected to the FE
> may stay keyed.**

That justifies `AgentToolsController` emitting keys (the FE resolves via
`/api/messages/registry-operation/{locale}`) *and* condemns the emitter emitting them. It also
tells us the guard shape: `I18nKeyValidator` today validates one catalog against one properties
file (`ValidatorRunnerTest.java:224-231` loads only the registry-operation keys and constructs
contexts over the two **static** catalogs). The class of defect becomes impossible only if the
validator runs over the **composed** catalog against the **union** of files the production
resolver loads. Guard the composed set, not the base declarations — the same sentence as
finding 2.

### A.5 A projection should inherit what it projects (findings 3b + 5)

`WorkflowOperationProjection.toOperation` hardcodes `OperationAvailability.empty()` (`87`). A
workflow that composes `vendor.mcphost.reference-add` is therefore declared unconditionally
available while the thing it runs may not exist at all — which is exactly why `core.demo-compose`
("say you will now add 2 and 40") is offered to every model on every install. The special-case fix
is to drop demo-compose from the agent offering. The *general* fix is a rule:

> **A projected operation inherits the availability of what it composes** — a workflow's
> availability is the conjunction of its `ToolStep` operations' availability.

That makes demo-compose vanish from the offering on stacks without the reference server with no
special-casing, and it generalizes to every future projected workflow. Sequencing caveat found
while reading: `SubstratePhase.java:202-205` installs workflow ops **before**
`mcpHostService.connect()` (`212`), so the referenced ops are not in the registry at projection
time — any derivation must run at `deriveAndPartition` (`219-221`), alongside the capability
derivation that already happens there. There is even a matching primitive:
`CapabilityAvailability.withCapabilityDerivedAvailability` already derives availability once over
the merged set.

### A.6 Offered ⟺ executable (finding 4)

"Offered but unhandled" (`core_remember` on the eager path) and "declared but not offered" (what
the runtime-witness gate checks) are the same invariant read in two directions. One assertion
covers both:

> the emitter's output ⊆ (handler registry ∪ workflow-runner routes ∪ virtual store)

This is strictly stronger than `ExecutorBindingValidator`, which passes today only because
`ValidatorRunnerTest.java:90-93, 208-222` hands it stub handlers for the very refs production may
never register.

On the mechanism: the minimal root-cause fix is smaller than the handover's "collapse to ONE
table". The bug is `if (resolve(SEARCH_INDEX).isPresent()) return false;` — **one ref standing
proxy for all of them**. Per-ref idempotent registration makes the two paths *compose* instead of
*exclude*, which is what they were always meant to do (868 already patched READ_DOCUMENT onto both
paths by hand, treating the symptom). Whether the larger single-table collapse is also worth doing
is a design call; the proxy-sentinel is the defect.

### A.7 The instrument, and where the panel should point

The `Agent tools offered` log line is currently the only record of the one fact, and it is a log
line: not queryable, not asserted, gone after rotation. A natural extension — **record the
offered set on the run** (`AgentRunStore` meta), so that (a) the trust panel can show *what this
run was offered* instead of a global catalog guess, and (b) any later question about a run is
answerable from durable evidence rather than a grep. That reframes finding 5 from "make the
endpoint match the emitter" to "the panel should be showing the run's offering at all". It is
also the surface most likely to collide with workstream 875, so it is recorded here as a
direction, not a commitment.

### A.8 Risks, hidden assumptions, and what would falsify the framings

- **Assumption:** `index.unavailable` is the only availability expression on agent tools today.
  Verified for the static catalogs (only `searchIndex()` and `readDocument()` declare one), but
  `CapabilityAvailability.withCapabilityDerivedAvailability` can mint more over the merged set, and
  MCP contributions carry `RequiredCapability`. Any fix must be about the *evaluation seam*, not
  about this one condition id.
- **Assumption:** filtering the tool list by availability is desirable at all. F-iv doubts it.
  Design should state which of F-iii / F-iv it is choosing and why.
- **Regression risk:** narrowing `availableOperations()` would break three name-resolution
  consumers (§A.0 row). Add a read; do not re-point the existing one.
- **Regression risk:** wiring reconciliation onto a capability listener touches boot ordering and
  a gRPC call. Off-thread, fail-soft, and self-seeding (the `CapabilityHealthBridge` replay
  pattern) are the three properties any such wiring must have.
- **Coordination risk:** 875 owns resolve-against-offered-set, 877 owns dead
  `PARAMETER_SCHEMA`s / structuredData constants. Anything this workstream does at the *dispatch*
  seam or in the *tool-schema constants* belongs to them.
- **Falsifier for the whole "fail open" framing:** if a tool offered while its backing subsystem
  is down produces a *harmful* action rather than an error, the asymmetry argument collapses for
  that tool. Check per risk tier: it holds for LOW/read-only tools (search, read, browse), and
  should be re-argued before extending to MEDIUM/HIGH (ingest, file-operations) — those are
  confirm-gated anyway, which is a second, independent brake.

### A.9 Does this point at a broader system shape?

Four candidate invariants, in decreasing confidence:

1. **One wire fact, N projections.** Whatever a model is *sent* is the truth; panels, snapshots,
   witnesses and gates project from it. (Generalizes 553's representation-drift rule from search
   execution to capability.)
2. **Reconciliation triggered by a request handler is a cache, not a state.** Any consumer that
   cannot guarantee the request happened is reading stale data with no way to know. This is
   bigger than agent tools: five taps in `StatusLifecycleHandler.java:452-508` share the property,
   and the FE's 10 s poll is the only reason it has never been visible to a user. Worth its own
   tempdoc (next free number at the time of writing: 879) if the fix here ends up scoped to the
   agent-offering path rather than to the trigger.
3. **Fail open on offering, fail closed on execution.** With the corollary that a model's
   experience of an absent capability is improvisation, not refusal.
4. **A projection inherits the availability of what it projects.** Applies to workflow→operation
   today; would apply to any future composed/aliased operation.

### A.10 What theorizing did *not* settle (for design)

- F-i vs F-iii vs F-iv, and whether the trigger fix belongs in this tempdoc or a sibling.
- Whether to re-emit the tool list per iteration, monotonically, or not at all (§A.3).
- Whether to delete `core.demo-compose` from the agent offering or derive its availability (§A.5).
- Whether the trust panel should project the emitter's *current* offering or the *run's recorded*
  offering (§A.7).
- Whether the handler-coverage invariant lands as a unit test, a governance gate, or both.

---

## §B. Design (2026-08-26 — status THEORIZING → DESIGNED)

### B.0 The design in one sentence

**The offering is a named thing with one producer** — the set of Operations the emitter puts in
front of the model — and every other artifact that describes the agent's capability (the trust
panel, the build-time witness, the handler registry's coverage claim, the prompt) either derives
from that producer or is asserted against it by a test.

Six changes implement it. They are one defect family, not six defects: each is a place where a
*second* answer to "what is offered" was written down and then drifted.

### B.1 `offer(...)` — the set, separated from its wire shape (findings 2, 5)

`AgentOperationEmitter.emit` today fuses three things: the filter chain (executor → audience →
selection → availability), the OpenAI serialization, and the virtual-store merge. Only the first
is "the offering"; the other two are formats. Split it:

- `AgentToolEmitter.offer(catalog, selectedNames) -> List<Operation>` — the filter chain, and the
  **one** authority on membership.
- `emit(...)` = `offer(...)` projected to the OpenAI shape, plus the virtual merge — unchanged
  output, byte-for-byte (the `AgentOperationEmitterRegressionTest` baseline must not move).

`AgentToolEmitter` stops being `@FunctionalInterface` (it gains a second method); that annotation
is the only thing this orphans, and no lambda implements it today.

Then every consumer that wants *the set* asks for the set:

- `AgentRunQueries` gains `offeredOperations()`, implemented by `AgentLoopService` as
  `emitter.offer(catalog, List.of())`. It is a **new read**, deliberately not a re-pointing of
  `availableOperations()` — three consumers (`AgentSseWriter`, `ToolIteratingShapeRunner`,
  `ConversationApiAssembly`) index that one by name to resolve a tool the model *already named*,
  and they need the full catalog. Narrowing it would be a silent regression (§A.0).
- `AgentToolsController.handleListTools` projects `offeredOperations()` through its existing rich
  projection (risk / undo / parameterSchema / tier / provenance / kind — all still wanted). The
  panel then shows the set the model gets, with the attribution the panel exists for.
- The inline virtual-collision loop in the controller is **deleted** and replaced by one shared
  helper on `VirtualOperationStore` that both the controller and `AgentOperationEmitter` call.
  Two implementations of "drop a virtual tool whose wire name collides with a core one" become
  one.

The controller keeps emitting **i18n keys** in its `description`, and that is correct, not a bug:
the FE resolves them per-locale via `/api/messages/registry-operation/{locale}`. The rule the
design commits to is directional, not absolute —

> **Anything projected to a MODEL is resolved server-side; anything projected to the FE may stay
> keyed.**

— which is exactly why the same key passing through the *emitter* unresolved (§B.3) is a defect
and passing through the *controller* unresolved is not.

### B.2 Availability truth without a poller (finding 1)

Two independent moves. Neither alone is sufficient: the first makes the store tell the truth, the
second makes a future failure of the first non-catastrophic.

**B.2a — reconciliation gets a trigger that is not a request.** The root cause is that every
health tap in the head is driven from inside `StatusLifecycleHandler.buildStatusMap`
(`452-508`): the lifecycle tap, the worker tap, the index-drift tap, the at-rest tap and the
conversation-protection tap all reconcile only when someone asks for `/api/status`. The FE's 10 s
poll has been hiding this from users since the taps were built; an API-only client inherits
whatever the last request left behind.

The fix is the pattern the tree already uses one layer down. `CapabilityHealthBridge.wireListeners`
(`48-97`) subscribes to `WorkerCapability` / `InferenceCapability` transitions, writes into the
same `ConditionStore`, and — importantly — **replays current state at wire time** so a transition
that happened before the listener existed is not lost. Introduce a small
`ReadinessReconciliationTrigger` in `app-services` that owns exactly that shape for the snapshot
reconciliation:

- it holds a `Runnable` "recompute the readiness snapshot" thunk, supplied by the ui side as
  `statusLifecycleHandler::buildStatusSnapshot` (the existing `StatusSnapshotProvider` SPI — no
  new cross-module coupling, and no second envelope authority);
- it subscribes that thunk to worker + inference capability transitions;
- it runs the thunk once at wire time (the self-seed);
- it executes off the transition thread on a single daemon thread, coalescing (a transition
  arriving while one is in flight schedules at most one more), and swallows failures — the thunk
  performs a Worker gRPC call, and a health reconciliation must never be able to stall or crash a
  capability transition.

This keeps **one** authority for the readiness envelope (`buildReadinessEnvelope`, unchanged) and
adds a second *trigger*. It reaches all five taps, not just `index.unavailable` — but "reaches" is
the honest verb, not "fixes": the trigger fires on worker + inference **capability** transitions, so
a condition whose only input is the Worker-reported operational view still moves only when a
snapshot is taken. `index.unavailable` itself has both kinds of arm (`LifecycleSnapshotTap`'s
MAPPING_TABLE: the `worker.starting` rows follow the capability, the
`INDEX_SERVING NOT_READY / index.not_healthy` row follows the Worker's operational view). §B.2b is
what makes the residue survivable rather than silent. Corrected 2026-08-26 after the independent
review; the over-claim was also live in docs/22 and in `AgentToolsOperationCatalog`'s comment, both
fixed in the same pass.

**B.2b — the offering re-evaluates within a run, monotonically.** `AgentLoopService:549`
evaluates once and hands the same `baseTools` to every iteration (`577-580`), so a run that
outlives a worker recovery can never see the recovered tool. Re-evaluate before each iteration
and adopt the new list **only when its name set is a strict superset** of the current one — a
tool may appear mid-run, never disappear.

This is not a convenience; it is the asymmetry from §A.2 (F-iii) expressed on the time axis:

> **Offering fails open; execution fails closed.**

A tool offered while its backend is down returns an error the model reads and adapts to. A tool
withheld is not experienced as an absent capability at all — it is experienced as a reason to
improvise, which is precisely what 868 §C.3 recorded (asked to read a document with
`core_read_document` absent, the model invented
`core_file_operations {"operations":[{"operation":"read"}]}` while its own reasoning said it
wanted the read tool). Monotone growth also keeps the transcript coherent: nothing the model has
already been shown, and possibly already called, vanishes underneath it. Wholesale replacement
(not appending) preserves catalog ordering, which the emitter's javadoc already calls
load-bearing.

Tempdoc 550 F3's motive survives intact — "the model is not offered a tool it *provably* cannot
run right now". *Provably* is the load-bearing word, and a latch nobody reconciles proves nothing.

**Deliberately not doing**, with reasons recorded so they are not re-litigated as new ideas:

- *A staleness TTL on the condition probe.* With B.2a the store is event-fresh by construction; a
  TTL would add a magic number and a flapping mode to defend against a failure B.2b already makes
  survivable.
- *Annotating instead of subtracting* (keep every tool, put "currently unavailable" in the
  description — §A.2 F-iv). It dissolves the problem, but it rewrites the model's tool list
  contents on every state change, which weakens prompt caching for a defect the two moves above
  already close.

**B.2c** — the false claim at `AgentToolsOperationCatalog.java:158-164` ("reliably cleared when it
returns to READY") is replaced by what is actually true after B.2a, naming the trigger the
guarantee now depends on.

### B.3 One resolver for everything the model reads (finding 3)

`BootstrapHelpers.loadRegistryOperationMessages()` loads `registry-operation.en.properties` only,
so the two projected workflow tools reach the model with `registry-workflow.research-brief.
description` as their literal description. Fold both files into the one resolver the emitter uses
(the key namespaces are disjoint, so this is a union, not a merge conflict). The old
single-file method is **removed**, not kept as a shim — the whole point is that there is one
resolver.

The guard is placed where the defect actually lives, at the offering seam rather than in a
validator's fixture: a test that composes the production catalog set, emits through the production
resolver, and asserts **every offered tool's description differs from its i18n key**. A raw key
reaching the model becomes a red test, whatever future catalog contributes it.

### B.4 A projection inherits the availability of what it projects (finding 3b)

`WorkflowOperationProjection.toOperation` hardcodes `OperationAvailability.empty()` (`87`), so
`core.demo-compose` — a "say you will now add 2 and 40" demonstration whose two `ToolStep`s call
`vendor.mcphost.reference-add` / `…reference-get-image` — is declared unconditionally available
and offered to every model on every install, including the overwhelming majority that have no MCP
reference server (`SubstratePhase:193-196`: empty config by default).

The special-case fix is to delete it from the projection. The design instead adopts the rule:

> **A projected operation inherits the availability of what it composes.**

Concretely: a projected workflow whose `ToolStep` refs are all resolvable in the composed registry
is offered with the conjunction of those operations' availability expressions; a workflow with an
unresolvable `ToolStep` ref is **not projected at all** (an absence is not expressible as an
availability expression, and "offered but unrunnable" is the very thing this tempdoc exists to
remove).

This requires one sequencing change: `SubstratePhase` installs workflow ops at `202-205`, *before*
`mcpHostService.connect()` at `212`, so the referenced operations are not in the registry when the
projection runs. Move the workflow install to after the MCP connect and before
`deriveAndPartition` (`219`). Ref-uniqueness is unaffected (`core.workflow-*` cannot collide with
`vendor.mcphost.*`).

Outcome: on a default install `core.demo-compose` disappears from the *agent* offering by
construction, with no special-casing and no list of exceptions to maintain. It remains a
first-class entry in the `WorkflowCatalog` and the FE workflow picker — a demo a human runs
deliberately is a different thing from a tool a model may pick, and this is the seam where those
two were conflated.

### B.5 Offered ⟺ executable (finding 4)

`core_remember` is offered by the catalog unconditionally and registered by only one of the two
registration paths. The mechanism is sharper than "two paths": `registerLateBound` short-circuits
on `resolve(SEARCH_INDEX).isPresent()` (`AgentToolHandlers:103-105`) — **one ref standing proxy
for all of them**. Any eager registration of SEARCH_INDEX therefore permanently suppresses
REMEMBER. Tempdoc 868 already patched `READ_DOCUMENT` onto both paths by hand, which treated the
symptom and left the proxy in place.

Fix the proxy: register **per ref, idempotently** (skip a ref already present) so the eager and
late-bound paths *compose* instead of *exclude*. `HandlerRegistry.register` keeps its
throw-on-duplicate contract everywhere else — the two agent paths are the only pair designed to
both run, and the skip belongs at that call site, not in the registry's contract. The hand-listed
tool names in the `AgentToolHandlers:156` log string are deleted in favour of the refs actually
registered.

The invariant this closes is one statement read in two directions — "declared but not offered" is
what the `runtime-witness` gate already checks; "offered but not executable" is its unguarded
mirror:

> **offered ⊆ (handler registry ∪ workflow-runner routes ∪ virtual store)**

It lands as a test over the composed production substrate. That is strictly stronger than
`ExecutorBindingValidator`, which passes today only because `ValidatorRunnerTest:90-93, 208-222`
hands it stub handlers for exactly the refs production may never register — a fixture that was
compensating for this defect rather than exposing it.

### B.6 The guards look at the offering, not at the declarations (finding 2)

`RegistrySnapshotExporter.agentWitnessDeliveredIds()` (`272-298`) instantiates the two **static**
base catalogs and runs a **bare** `new AgentOperationEmitter()` — no availability probe, no
composed workflow ops, no MCP ops. So the artifact the `runtime-witness` gate calls "what the real
emitter delivers" is a third thing, agreeing with neither the run nor the panel.

- Build it from the same composition production uses (base catalogs + projected workflows), so the
  build-tier witness covers the projected ops it is currently blind to.
- Pass an **explicit** `conditionId -> false` probe instead of relying on `null` meaning "no
  filtering". Same result today; the difference is that the healthy-stack assumption becomes a
  stated contract rather than an accident of a null check, and a future default-firing condition
  cannot silently change what the witness reports.
- `buildOperationEntries()` must gain the same composed ops, or the enforcer's PHANTOM rule
  (delivered but not declared) fires on the newly-covered workflow ops. This is the one place the
  change has to land on both sides at once.
- `ValidatorRunnerTest` gains a context over the composed catalog, so the shape validators run
  over what production actually assembles.

`LiveWitness` itself is unchanged — it already reads the live registry, and the handover's pointer
at it was to the javadoc *describing* the static tier. `governance/live-witness.v1.json` names
`LiveWitness.orphanedDeliveries` and `RegistrySnapshotExporter.operationConsumerIds` by path and
symbol; neither moves, so `check-live-witness` stays green by construction.

### B.7 Prose that disagrees is a test failure, not a rewrite (finding 6)

Three concrete lies get fixed in the message catalog: `ops.search-index.description` tells the
model to "use text / use hybrid / use vector" when `mode` is deliberately *not* in the declared
Interface (`AgentToolsOperationCatalog:141-142` says so explicitly), and three descriptions name
`browse_folders`, which is not a wire name.

The prompt itself stays prose. Deriving `DEFAULT_SYSTEM_PROMPT`'s tool guidance from the catalog
was considered and rejected: the prompt's value is *when to prefer which tool* (search returns
excerpts, read returns pages, don't page a whole document for a summary), which is knowledge the
catalog does not have and should not be asked to hold. What the catalog *can* do is make drift
loud. Two assertions:

1. every `core_*` wire name mentioned in `DEFAULT_SYSTEM_PROMPT` is a real offered wire name;
2. every parameter name mentioned in an offered tool's **resolved** description exists in that
   tool's declared `Interface`.

Both would fail on `main` today. Neither constrains what the prose says — only that it cannot
name things that do not exist.

### B.8 What this design orphans

Deleted or rewritten in this tempdoc's own work, not left for a sweep:

| Orphan | Where | Disposition |
|---|---|---|
| inline virtual-collision loop | `AgentToolsController:56-83` | deleted; one shared helper on `VirtualOperationStore` |
| SEARCH_INDEX sentinel short-circuit | `AgentToolHandlers:103-105` | deleted; per-ref idempotent registration |
| hand-listed tool names in the log string | `AgentToolHandlers:156` | deleted; log the refs actually registered |
| `loadRegistryOperationMessages()` (single-file) | `BootstrapHelpers:105-118` | removed and replaced by the two-file loader; no shim |
| `OperationAvailability.empty()` hardcode | `WorkflowOperationProjection:87` | replaced by composed-availability derivation |
| `@FunctionalInterface` on the emitter SPI | `AgentToolEmitter` | removed (two methods now); no lambda implements it |
| "reliably cleared when it returns to READY" | `AgentToolsOperationCatalog:158-164` | rewritten to name the trigger the claim depends on |
| "core + agent-tools + MCP" agent-view claim | `AgentToolsController:233-240` | corrected — CORE ops partition away |
| the stub-handler workaround's role | `ValidatorRunnerTest:90-93` | kept as a fixture, but its comment stops implying production parity |
| docs/22's 5-tool table | `docs/explanation/22-agent-system-architecture.md` | updated: it omits `core_remember`, `core_navigate_to_surface` and the projected workflows, and says nothing about availability filtering |

### B.9 Reach — the principles this is an instance of, and when to retire them

**P1. One wire fact, N projections.** *Whatever is sent to a model is the truth; panels,
snapshots, witnesses and gates project from it and never re-derive it from the declarations.*
This is tempdoc 553's representation-drift rule (the `execution-surfaces.v1.json` register for
`SearchTrace`) applied to *capability* instead of *search execution* — the same shape, one axis
over. Where else it applies today: `McpToolSurface` (the outward MCP tool list is a second
projection of the same catalog), and the FE's own virtual-operation serializer. Existing
violations found here: the trust-panel endpoint, the build-time witness, and the prompt.
*Evidence it earns its keep:* the next capability question ("was tool X offered on run Y?")
answerable from one place. *Retire it when:* the projections are unified structurally rather than
by convention — at that point the rule has no work left to do.

**P2. Reconciliation triggered by a request handler is a cache, not a state.** Any consumer that
cannot guarantee the request happened is reading stale data with no way to detect it. Scope:
`StatusLifecycleHandler:452-508` — five taps share the property, and the FE's 10 s poll is the
only reason it has never surfaced to a user. B.2a fixes the trigger for all five, but the general
statement is worth holding because the next tap added to that method will inherit the same
assumption silently. *Evidence:* a non-polling consumer of any condition getting a correct answer
without a request. *Retire it when:* condition reconciliation is structurally event-sourced, so a
request-driven tap is unrepresentable.

**P3. Offering fails open; execution fails closed.** A model's experience of a withheld capability
is not refusal — it is improvisation (868 §C.3 is the recorded case). So subtract a tool from the
list only on a *fresh, positive* statement of unavailability, and never subtract one mid-run.
Falsifier, stated so this does not become self-justifying: if a tool offered while its backing
subsystem is down can produce a *harmful* action rather than an error, the asymmetry collapses for
that tool. It holds for the LOW/read-only tools that declare availability today (search, read);
it must be re-argued before extending to MEDIUM/HIGH tools, which carry an independent
confirm-gate brake anyway. *Retire it when:* an availability-subtracted tool is shown to prevent a
harm that its error path would not have.

**P4. A projection inherits the availability of what it projects.** Applies to workflow →
operation today (B.4). It would apply to any future aliased, wrapped, or composed operation —
including the parameterless `core.rebuild-index` wrapper referenced from
`LifecycleSnapshotTap:164-174`, which is the same shape and is not checked. *Evidence:* a composed
operation never appearing in a tool list while what it composes is absent. *Retire it when:*
availability derivation happens once over the composed set for every kind of contribution, at
which point the rule is the code.

No generalized structure is being built for P1, P2 or P4 beyond what this tempdoc's own problem
requires: no offering register file, no event-sourced condition substrate, no generic composition
derivation. They are named here so the next instance is recognized rather than re-discovered.

---

## Plan

Written into the tempdoc rather than plan mode (subagents have no plan mode). Work packages run
**sequentially** in this worktree — the file sets overlap enough (`AgentOperationEmitter`,
`SubstratePhase`, the emitter tests) that parallel workers in one tree would break each other's
compiles, and Gradle is single-lane across agents anyway. Each package ends with a compile before
the next starts.

### Pre-flight facts the plan depends on (verified)

- `HealthSubstrateInit.Output` has 7 components, `SubstrateGraph.HealthSubstrate` 9 — both have
  room for one more; `HealthSubstrateInitTest` asserts on the record and must be updated.
- `BootstrapLateBindings` already carries a `StatusSnapshotProvider` late-binding, and
  `CoreApiAssembly:436-441` is where `statusLifecycleHandler` is handed over — the same block that
  wires the five taps (`241-260`). That is the attach point; no new cross-module coupling.
- `HeadAssembly.close()` already tears down a substrate-owned thread
  (`scanRollupLedger().close()`, `1333-1341`) — the precedent and the place for the trigger's
  shutdown.
- `HandlerRegistry.register` throws on duplicate by design. The skip goes at the
  `AgentToolHandlers` call site, not into the registry contract.
- `GET /api/chat/agent/tools` keeps its wire shape, so `AgentSessionController.ts`
  (`tools: AgentToolInfo[]`) needs no change and the ui-web gate set is not triggered. Only the
  *set* narrows.
- `governance/live-witness.v1.json` names `LiveWitness.orphanedDeliveries` and
  `RegistrySnapshotExporter.operationConsumerIds` by path+symbol; neither moves.

### W1 — `offer(...)`: one authority for the set  *(B.1)*

1. `AgentToolEmitter` (app-agent-api): add
   `List<Operation> offer(OperationCatalog, Collection<String>)`; drop `@FunctionalInterface`.
2. `AgentOperationEmitter`: extract the filter chain into `offer`; `emit` = `offer` → OpenAI shape
   → virtual merge. Output must stay byte-identical
   (`AgentOperationEmitterRegressionTest` baseline is the check).
3. `VirtualOperationStore`: add the shared collision-drop helper; `AgentOperationEmitter` and
   `AgentToolsController` both call it. **Delete** the controller's inline loop (`56-83`) and the
   emitter's private `collidesWithCore`.
4. `AgentRunQueries`: add `offeredOperations()`; implement via `AgentLoopService`, which already
   holds `agentToolEmitter`. `UnavailableAgentService` returns empty. Every test double that
   implements the interface needs the new method (7 known sites, all in tests).
5. `AgentToolsController.handleListTools` projects `offeredOperations()`. Fix the `233-240`
   comment.
6. Tests: an emitter test that a non-empty selection **and** an availability probe compose
   (the gap at `AgentOperationEmitterTest:279-384`); a controller test that an availability-hidden
   tool is absent from `/api/chat/agent/tools`.

### W2 — reconciliation gets a non-request trigger  *(B.2a)*

1. New `ReadinessReconciliationTrigger` (app-services `observability/health`): late-bound
   `Runnable`, single daemon thread, coalescing, fail-soft, `attach()` self-seeds, `close()`.
2. `HealthSubstrateInit` constructs it → `Output` + `SubstrateGraph.HealthSubstrate` +
   `SubstrateGraphAssembler`; `HealthSubstrateInitTest` updated.
3. `OrchestrationPhase.runInternal` wires it to worker + inference capability transitions, right
   next to `CapabilityHealthBridge.wireListeners`.
4. `CoreApiAssembly` attaches `statusLifecycleHandler::buildStatusSnapshot` in the tap block.
5. `HeadAssembly.close()` closes it.
6. Tests: unit — a capability transition fires the thunk, a burst coalesces, a throwing thunk does
   not propagate, `attach` self-seeds, `close` is idempotent. Integration — assert
   `index.unavailable` clears on a worker READY transition **with no `/api/status` call**; this is
   the test that fails on `main`.

### W3 — the offering re-evaluates within a run, monotonically  *(B.2b)*

1. `AgentLoopService`: recompute the emit before each iteration; adopt the new list only when its
   name set is a strict superset. The log line at `555-561` stays exactly as-is (it is the
   instrument); an adoption logs one additional line naming what appeared.
2. Test: a probe that flips from firing to clear between iterations makes the tool appear; the
   reverse flip does **not** remove it.

### W4 — one resolver, and prose that cannot name what does not exist  *(B.3 + B.7)*

1. `BootstrapHelpers`: replace `loadRegistryOperationMessages()` with a loader that unions
   `registry-operation.en.properties` + `registry-workflow.en.properties`. Old method removed;
   update `HeadAssembly:566-568`.
2. `registry-operation.en.properties`: `ops.search-index.description` stops promising the
   undeclared `mode`; `browse_folders` → `core_browse_folders` in the three descriptions
   (`138`, `153`, `158`).
3. Tests: (a) every offered tool's resolved description differs from its i18n key; (b) every
   `core_*` name in `DEFAULT_SYSTEM_PROMPT` is a real offered wire name; (c) every parameter name a
   resolved description mentions exists in that tool's `Interface`.

### W5 — a projection inherits what it composes  *(B.4)*

1. `WorkflowOperationProjection.project(catalog, knownOps)`: skip a workflow whose `ToolStep` refs
   are unresolvable; otherwise conjoin the referenced operations' availability expressions.
2. `SubstratePhase`: move `installWorkflowOps` to after `mcpHostService.connect()` and before
   `deriveAndPartition`.
3. Tests: with no MCP server, `core.workflow-demo-compose` is not offered and
   `core.workflow-research-brief` is; with a registry containing the referenced ops, both are, and
   the demo carries the conjoined availability.

### W6 — offered ⟺ executable  *(B.5)*

1. `AgentToolHandlers`: delete the SEARCH_INDEX sentinel; register per ref, skipping refs already
   present. Replace the hand-listed log string with the refs actually registered.
2. Test: the eager path then the late-bound path leaves **all six** handlers registered — fails on
   `main`.
3. Test: the composed production offering ⊆ (handler registry ∪ workflow routes ∪ virtual store).

### W7 — the guards look at the offering  *(B.6)*

1. `RegistrySnapshotExporter`: build `agentWitnessDeliveredIds()` **and** `buildOperationEntries()`
   from the same composition production uses (base catalogs + projected workflows), with an
   explicit `conditionId -> false` probe.
2. `ValidatorRunnerTest`: add a composed-catalog context; correct the stub-handler comment so it
   stops implying production parity.
3. Run `--gate runtime-witness`, `--gate operation-surface`, `check-live-witness`. The enforcer's
   PHANTOM rule is what bites if step 1 lands on only one side.

### W8 — docs  *(B.8 last row)*

`docs/explanation/22-agent-system-architecture.md`: the built-in tool table gains `core_remember`
and `core_navigate_to_surface` and the projected workflow tools, and the Operation-Substrate
section gains a short paragraph on the offering — availability filtering, monotone re-evaluation
within a run, and the emitter as the one authority the panel and the witness project from.

### Verification (in order, all Gradle through `gradle-locked.sh`)

1. `spotlessApply` → `build -x test`
2. per-module tests as each package lands (`app-services`, `app-agent`, `app-agent-api`, `ui`)
3. full `test` at the end (`VduEligibilityPdfFixturesTest` is a known local red)
4. `node scripts/governance/run.mjs --gate runtime-witness --mode gate`,
   `--gate operation-surface`, `node scripts/ci/check-live-witness.mjs`,
   `node scripts/ci/check-tempdoc-numbers.mjs`
5. no ui-web change is planned; if one becomes necessary the `ui-web-gates` recipe runs too
6. `git diff | grep -P '^\+.*[^\x00-\x7F]'` — no unintended non-ASCII from any worker

### Needs a live run (explicitly not covered here)

The dev stack is the orchestrator's phase (common brief rule 3). Two claims stay unverified until
then: that a real agent run started with **no** `/api/status` poll offers `core_search_index` (the
868 §C.3 reproduction, with the `Agent tools offered` log as the instrument), and that the trust
panel and that log agree on a live stack.

### Delegation

Each package goes to a pinned worker (opus for W1/W2/W3/W5/W7, sonnet for W4/W6/W8), sequentially,
with the common brief's rules 1-4 inlined and a compile as the acceptance gate. Brief-writing,
evidence judgment and the merge stay in the main loop.

---

## §C. Implementation record (2026-08-26)

### C.0 Session handover

The implementing session was killed by an account session limit mid-verification (W3/W4/W5/W7
were written but unverified). A successor session salvaged the worktree: all 43 changed/added
files were present as uncommitted WIP with no stashes and no surviving sub-worker branches
(`git worktree list` + an ancestry sweep over every `worktree-agent-*` branch found no descendant
of the predecessor's `27004dbe`, so nothing was stranded elsewhere). The WIP is committed as
`50fee453`; `origin/main` was then merged clean (no conflicts, `3f00f98b`).

The session's `docs/observations.d/` shard was **deleted, not folded** — tempdoc 872 retired the
observations store on `main` while this branch was in flight, and `check-no-observations-shards`
now fails on any shard. Its three entries were routed per the new CLAUDE.md rule; see §C.3.

### C.1 What shipped

All eight plan packages. Nothing was deliberately skipped.

| Pkg | Design | Landed |
|---|---|---|
| W1 | §B.1 | `AgentToolEmitter.offer(...)` is the one membership authority; `emit` is its wire projection; `@FunctionalInterface` dropped. `AgentRunQueries.offeredOperations()` added as a NEW read (`availableOperations()` deliberately unchanged — its three name-resolution consumers need the full catalog). `AgentToolsController.handleListTools` projects the offering; its inline virtual-collision loop and the emitter's private `collidesWithCore` are both replaced by `VirtualOperationStore.withoutCollisions(...)`. |
| W2 | §B.2a | `ReadinessReconciliationTrigger` — single daemon thread, coalescing, fail-soft, self-seeding on `attach`. Wired to worker + inference transitions in `OrchestrationPhase`, thunk attached in `CoreApiAssembly` (`statusLifecycleHandler::buildStatusSnapshot`), closed in `HeadAssembly.close()`. Threaded through `HealthSubstrateInit.Output` / `SubstrateGraph.HealthSubstrate` / `SubstrateGraphAssembler`. |
| W3 | §B.2b | `AgentLoopService.adoptGrownOffering` — re-emit per iteration, adopt only on a strict-superset name set. |
| W4 | §B.3 + §B.7 | `BootstrapHelpers.loadRegistryMessages()` unions the operation + workflow catalogs (single-file method removed, not shimmed). `registry-operation.en.properties`: `mode` no longer promised, `browse_folders` → `core_browse_folders`. |
| W5 | §B.4 | `WorkflowOperationProjection.project(catalog, knownOperations)` — unresolvable ToolStep ⇒ not projected; otherwise availability is the conjunction of what it composes. `SubstratePhase` moves `installWorkflowOps` after `mcpHostService.connect()`, before `deriveAndPartition`. |
| W6 | §B.5 | The SEARCH_INDEX sentinel short-circuit is gone; `registerIfAbsent` makes the eager and late-bound paths compose. The hand-listed log string logs the refs actually registered. |
| W7 | §B.6 | `RegistrySnapshotExporter` builds both `agentWitnessDeliveredIds()` and `buildOperationEntries()` from the production composition with an explicit `conditionId -> false` probe; `ValidatorRunnerTest` gains a composed-catalog context. |
| W8 | §B.8 | `docs/explanation/22-agent-system-architecture.md` offering section + tool table; `docs/reference/api-contract-map.md`'s `/api/chat/agent/tools` line. |

### C.2 Verification

Run from the worktree, Gradle single-lane.

- `spotlessApply` — `BUILD SUCCESSFUL in 3s`
- `build -x test` — `BUILD SUCCESSFUL in 9m 24s`, exit 0 (includes `:modules:app-services:integrationTest` and `:modules:ui:integrationTest`)
- `test` (full suite) — see the PR body for the verbatim line
- `node scripts/ci/check-live-witness.mjs` — `OK — authority + enforcing test + reused build-tier merge all present and wired`, exit 0
- `--gate operation-surface` — pass, 0 findings
- `--gate runtime-witness` — pass; its one note now reads *"the agent offering channel delivers exactly the 17 declared agent-consumable, agent-audience-eligible operation(s)"* — the composed set, which is the W7 point
- `--gate execution-surface`, `--gate register-guard-resolution` — pass
- no `modules/ui-web` change, so the ui-web gate recipe is not triggered

**Still needs a live run** (dev stack is the orchestrator's phase): that a real agent run started
with **no** `/api/status` poll offers `core_search_index` (the 868 §C.3 reproduction, instrument =
the `Agent tools offered` log line), and that the trust panel and that log agree on a live stack.

### C.3 Open items and routed findings

Not fixed here; recorded where they are acted on rather than in a retired inbox.

1. **The workflow→agent-tool projection is a boot-time snapshot.** `McpHostService`'s
   `tools/list_changed` listener re-installs a server's operations at runtime
   (`modules/app-services/src/main/java/io/justsearch/app/services/mcphost/McpHostService.java:99`),
   but `WorkflowOperationProjection` runs once in `SubstratePhase`
   (`.../bootstrap/phases/SubstratePhase.java:221`). After §B.4 a workflow dropped — or
   availability-conjoined — at boot never re-derives when its composed operations later appear.
   This is the same "reconciliation has one trigger" shape as §B.2a, one layer over, and it is
   this tempdoc's own open item: the fix is to re-run the projection from the `list_changed`
   listener. Not done here because it needs the projection to be re-entrant against a live
   registry, which is a larger change than the availability derivation itself.
2. **Vacuous guard in `SubstratePhase`.** The MCP shutdown hook is installed under
   `!mcpHostService.operations().isEmpty()`
   (`.../bootstrap/phases/SubstratePhase.java:208`), but `McpHostService.operations()` returns the
   SHARED `ContributionRegistry` — core + agent-tools are already installed into it
   (`.../mcphost/McpHostService.java:141`), so the condition is always true and the hook is
   installed even with zero MCP servers configured. Harmless today; the guard means nothing.
   Pre-existing, unrelated to the offering, left for the MCP-host owner.
3. **`tmp/agent-orchestration/gradle-locked.sh` could never release its lock — fixed mid-session,
   with a lesson.** (Orchestration scratch, not repo code.) v1 `mkdir`ed the lock dir, wrote
   `$LOCK/owner` *inside* it, then released with `rmdir`, which fails on a non-empty directory with
   the error swallowed by `2>/dev/null` — so the first build to acquire wedged the shared Gradle
   lane for every agent permanently. Observed live 2026-08-26: the lane sat dead from 14:35 to
   15:17 with six agents queued. A v2 landed at 15:20 moving the owner file to the sibling path
   `gradle.lock.owner` and releasing with `rm -rf`; that is correct and is what this session's
   later runs used. **The lesson is the mixed-version window, not the original bug:** this session
   had been running a private corrected copy that still read `$LOCK/owner`, so against a v2 holder
   it saw an ownerless lock dir and blocked for the full 45-minute steal window — roughly 15
   wasted minutes. A private fix to shared coordination scaffolding buys a local unblock at the
   cost of a protocol fork; the shared script is the one to fix, and every agent should then use
   it.

### C.4 Independent review — findings and dispositions

Refute-first, read-only, opus, reviewer ≠ implementer (`independent-reviewer-required`). Verdict:
**no blockers**; four should-fixes and seven nits. Every should-fix was verified against source
before acting on it, not taken on the reviewer's word.

| # | Finding | Disposition |
|---|---|---|
| 1 | docs/22 claimed the new trigger closes the staleness for `index.unavailable`, but the `INDEX_SERVING NOT_READY / index.not_healthy` row comes from the Worker-reported operational view, not a capability transition (`LifecycleSnapshotTap` MAPPING_TABLE; the reason code is computed in `StatusLifecycleHandler`) | **Fixed.** Verified the mapping row first. docs/22, `AgentToolsOperationCatalog`'s comment and §B.2a above now say "a second trigger, not total coverage" and name the arm that is still snapshot-driven. |
| 2 | `ReadinessReconciledWithoutRequestTest` attaches a hand-authored lambda, so "no `/api/status` call" is true by fixture construction; the production line `CoreApiAssembly.attach(statusLifecycleHandler::buildStatusSnapshot)` is asserted by nothing (`audit-without-test`) | **Fixed, with one residual gap named.** `ReadinessTriggerCompositionTest` (modules/ui) binds the real `StatusLifecycleHandler` + `LifecycleSnapshotTap` + `WorkerCapability` + trigger, attaching the identical `handler::buildStatusSnapshot` method reference `CoreApiAssembly` uses; no Javalin `Context` or HTTP path exists in the graph. Teeth verified by two fail-probes: attaching nothing leaves `index.unavailable` absent after the self-seed, and disabling `wireTo`'s worker subscription leaves it asserted after the transition. **Residual:** the literal `attach(...)` line at `CoreApiAssembly.java:453` is still executed by no test — nothing in the repo constructs `CoreApiAssembly` (it needs a full `HeadAssembly` graph), so deleting that one line would not turn anything red. What is now guarded is the composition that line creates. The app-services test's naming was narrowed to what its fixture proves. |
| 3 | W5 folds only *explicit* availability. A composed op declaring `RequiredCapability` but no expression contributed nothing, and `withCapabilityDerivedAvailability` fills only ops with NO expression — so a workflow carrying one op's explicit gate could never receive another's capability gate | **Fixed.** `WorkflowOperationProjection` now resolves each composed op as `expression().or(() -> CapabilityAvailability.derive(policy().requiredCapabilities()))` before conjoining. Latent today (no such workflow exists) but it is §B.4's own rule one level down, and `structural-defects-no-repeat` says a known structural defect does not wait for an incident. |
| 4 | Retire-with-a-sweep residue: three sites still described the deleted behaviour as current — `HeadAssembly`'s "skips if SEARCH_INDEX present" comment, `LiveWitness`'s javadoc + `governance/live-witness.v1.json` still calling the static tiers blind to `core.workflow-*` (W7 changed that), and a properties comment naming a test that never carried the assertions | **All three fixed.** |
| 5 | Deleting the sentinel made `AgentToolFactory.assemble` run on every late-bound call, and `assemble` constructs a `FileOperationLog` whose constructor runs a diagnostic retention prune | **Fixed** with `allLateBoundRefsPresent(...)` — a nothing-to-do check over the WHOLE set, which is categorically different from the sentinel it replaced (that one let one ref stand proxy for the rest). The eager/late-bound composition, and its regression test, are unaffected. |
| 6 (nit) | `toolNames` mapped a nameless envelope to the sentinel `"?"`, collapsing two into one set element and mis-counting the strict-superset comparison | **Fixed** — nameless envelopes now contribute nothing. |
| 8, 10 (nits) | A false javadoc claim that `core.navigate-to-surface` is declared by both base catalogs (only the ref constant remains in `CoreOperationCatalog`, tempdoc 560 WS4), and a controller javadoc naming only the `registry-operation` message route | **Both fixed**, after verifying the WS4 comment in `CoreOperationCatalog`. |
| 7 (nit) | `registerEager` lost its throw-on-duplicate assertion | **Accepted as designed.** Symmetric idempotence across the two paths is §B.5's point; a per-path loud failure would re-introduce an ordering dependence between them. |
| 9, 11 (nits) | A pre-existing regression guard that passes identically on `main`; a test not using try-with-resources on `McpHostService` | **Accepted.** The first is a guard, not evidence, and is labelled as such; the second leaks nothing a test JVM cares about. |
| 12 (nit) | `HandlerRegistry` is a plain `LinkedHashMap`, so `registerIfAbsent` is read-then-act rather than atomic; the eager path runs on the boot thread and the late-bound path on the capability-listener thread | **Open item**, below. Pre-existing (the sentinel it replaced was the same read-then-act shape) and marginally widened, not introduced here. |

Additional open item from the review, recorded here rather than fixed:

4. **`HandlerRegistry` is not thread-safe and both agent-tool registration paths write to it.**
   `modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/HandlerRegistry.java:24`
   backs the registry with a plain `LinkedHashMap`; `AgentToolHandlers.registerIfAbsent` resolves
   then registers, and the two callers run on different threads (boot vs the worker-capability
   listener). Pre-existing — `register` itself was already a non-atomic put behind a duplicate
   check — and unobserved, but the correct fix is a `ConcurrentHashMap` plus a real `putIfAbsent`
   in the registry, which is `HandlerRegistry`'s owner's call, not this tempdoc's.

5. **`InferenceLifecycleManagerExternalServerTest` is order-dependent** — tracked here because
   this session's full-suite run surfaced it and a pin without a tracked fix is just a pile.
   `startLlamaServerCanAdoptHealthOnlyWhenExplicitlyEnabled` fails with
   `IllegalStateException: ConfigStore not initialized — call setGlobal() at startup` under a full
   `./gradlew test`, and passes when the class is run alone: a sibling test in the same module JVM
   leaves the `ConfigStore` global unset. Not 876's code — `modules/app-inference` depends only on
   gpu-bridge / app-api / configuration / telemetry / core-contracts, none of whose *code* this
   tempdoc touches — so it is pinned as `app-inference-external-server-config-store-flaky` in
   `scripts/agent-analytics/expected-state.v1.json` with an `exitProbe`, per CLAUDE.md's
   route-out-of-scope-findings rule. The fix is the test's own: whichever
   `modules/app-inference/src/test/**` case clears or fails to set the `ConfigStore` global should
   restore it in an `@AfterEach`, or the class should set it in `@BeforeEach` rather than inheriting
   another test's. The pin is a dated exception and must be deleted by the PR that fixes it.

### C.5 The W7 coverage boundary moved, and one test encoded the old one

Found by the post-merge full suite (2026-08-26, after merging PR #576 / tempdoc 878).
`LiveWitnessTest.liveRegistryCoversRuntimeComposedOpsTheStaticSnapshotMisses` asserted that every
projected `core.workflow-*` op is **absent** from `RegistrySnapshotExporter.buildOperationEntries()`
— it used those ops as its worked example of the DR-D blind spot. §B.6 deliberately made the
exporter compose what production composes, which pulls exactly those ops into the static tier, so
the assertion and the shipped design were in direct contradiction.

Two things are worth recording about how this surfaced and how it was resolved.

**It was not a merge conflict, and the earlier "full suite" had not actually run it.** Main never
touched this file (`git log 12291828..origin/main -- LiveWitnessTest.java` is empty); the predecessor
touched only the `composed()` helper, to match the projection's new signature. The failure has been
latent since W7 landed. The first full-suite run reported only `:modules:app-inference:test FAILED`
and stopped there — Gradle halts at the first failing task without `--continue`, so
`:modules:app-services:test` never ran. A red module can therefore mask an arbitrary number of
later ones, and "the full suite was green except the known pin" is not a safe reading of a run that
stopped early. That is `subset-isnt-the-suite` arriving through a side door: the command was the
full suite, but the *execution* was a prefix of it.

**The invariant was kept; only the witness changed.** The test's intent — the live tier catches what
no static tier can see — is still correct and still valuable. What changed is which contribution
instantiates it. Projected workflow ops are derivable from a compile-time catalog, so a build *can*
see them and after §B.6 does. What no build can derive is what a running install actually connects:
MCP tools and plugin contributions arriving through `ContributionRegistry.install(Installation)`.
The test now witnesses that, and additionally pins the §B.6 win in the positive direction (those
workflow ops **must** now be in the static snapshot), so an exporter regression to the static base
catalogs turns it red. The rewrite therefore has strictly more teeth than the original, and its
teeth are not assumed: the original assertion is the exact logical inverse of the new one and was
observed failing, which is the evidence the new assertion tests a real, load-bearing fact rather
than a tautology.

This is the one place this tempdoc changed a pre-existing test's assertion rather than its naming.
Recorded explicitly because "the test is probably right and your code is wrong" is the default, and
departing from it needs a reason in writing: here the test encoded a coverage boundary that the
tempdoc's own reviewed design moved on purpose, and §B.6 named that move before any test ran.

### C.6 Second independent review (post-merge delta)

Refute-first, read-only, opus, reviewer ≠ implementer, over the delta the first reviewer never saw
(the review-response commit, the `origin/main` merge carrying #576/878, the `LiveWitnessTest`
rewrite, and the new tests). Verdict: **safe to merge, one should-fix.** It explicitly could not
refute the two calls most at risk — the `LiveWitnessTest` rewrite and the exporter change behind it
— and found **no semantic merge damage from 878**.

| # | Finding | Disposition |
|---|---|---|
| 1 | **The monotonicity invariant is a no-op on the multi-agent path, and three prose sites claimed it universally.** `AgentStepRunner.buildIterationTools` returns `baseTools` only when `request.agentProfiles().isEmpty()`; with profiles present it discards it and re-emits per active profile, so `adoptGrownOffering` paid an emit per iteration for a list nobody read, and availability could still subtract there. | **Fixed, and scoped honestly.** Verified at `AgentStepRunner.java:1020-1032` before acting. The guard now runs only when `agentProfiles()` is empty (the wasted emit is gone), and docs/22, the loop comment and `AgentToolsOperationCatalog`'s §B.2c block all say *single-agent* run. The residual — per-profile monotonicity — is open item 6 below, named rather than papered over. |
| 2 | `LiveWitnessTest` assertion (b) was a tautology: `staticOpIds` was captured before the test minted its own literal ref, so no production change could fail it. | **Fixed.** The snapshot is now recomputed *after* the install, which asserts the structural property (a build-time composition cannot see a runtime install) instead of a fixture. |
| 6 | `allLateBoundRefsPresent`'s javadoc stated the inverse of what the code does. | **Fixed** — the real reason is that the check runs *before* `assemble()`, so it cannot know whether the factory will return a null read tool. |
| 7 | `HeadAssembly`'s caller comment still described the pre-876 boolean ("registration ran / was skipped"). | **Fixed** — it now means "prerequisites met", including the all-refs-present case. Reviewer confirmed the flip is harmless for both consumers. |
| 11 | Five `StatusLifecycleHandler` tap log strings say "failed during /api/status"; since §B.2a `buildStatusMap` also runs on the `readiness-reconcile` daemon thread with no request in flight. | **Fixed** — all six now say "during a readiness snapshot". |
| 8 | `ReadinessTriggerCompositionTest`'s javadoc claims it covers `CoreApiAssembly`'s `attach(...)` line; deleting that line leaves it green. | **Already recorded** as the residual gap in C.4 finding 2, in these words. No further change. |
| 10 | `WitnessController` now runs the full composition per request. | **Accepted.** Reviewer measured the cost: an empty `McpHostService` allocates a registry, connects over an empty list, closes zero clients — no threads, no files. Worth memoising only if that endpoint is ever polled. |
| 12 | `AgentOfferingIsExecutableTest` decides "is a workflow" by id prefix rather than consulting `WorkflowToolRunner`, so the workflow-routes half of *offered ⊆ executable* is asserted by naming convention. | **Open item 7** below. Pre-existing shape, but it is the gap in the invariant that test exists to close. |

Additional open items:

6. **Per-profile monotonicity on the multi-agent path.** `AgentStepRunner.buildIterationTools`
   (`modules/app-agent/src/main/java/io/justsearch/agent/AgentStepRunner.java:1020-1032`) re-emits
   the offering for the active profile each iteration, so a tool can still vanish mid-run there when
   its availability flips — the amputation §B.2b exists to prevent. It is deliberately *not* fixed
   by extending `adoptGrownOffering`: a handoff is *meant* to change the tool set, so monotonicity
   there has to be per-profile ("never shrink within one profile's tenure"), and that raises design
   questions this tempdoc has not answered — whether a handoff back to a profile resets its floor,
   and how that interacts with `AgentState.DECIDING` and E0a, which narrow the list on purpose. A
   design question, not an implementation detail, which is why it is named rather than guessed at.
7. **The workflow-routes half of *offered ⊆ executable* is unasserted.**
   `modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/AgentOfferingIsExecutableTest.java:161-166`
   classifies a projected workflow by its `core.workflow-` prefix instead of checking that
   `WorkflowToolRunner` has a route for it, so that arm of the §B.5 invariant passes by naming
   convention. The same test injects a mock `DocumentService`, so the one configuration where
   `core.read-document` could be offered with no handler on either path is never exercised.

### C.7 Reconciling with 877 and 879

PRs #583 (877, agent-tool centralisation) and #582 (879, declared-but-inert policy axes) merged on
top of #576 while this branch was open. Three conflicts; one of them was the genuinely dangerous
kind and is worth recording as a worked example of the class.

**`AgentToolHandlers` — two correct fixes that are broken together.** 877 §2.10 independently found
the same defect §B.5 did on one axis — the completion log hand-listed tool names, a second authority
that drifts the moment a conditional registration is skipped — and fixed it with a
`register(handlers, registered, ref, handler)` helper that calls `HandlerRegistry.register`
directly, recording each ref as it goes. That helper **throws on a duplicate**, and it is safe in
877's tree for exactly one reason: 877 kept the `resolve(SEARCH_INDEX).isPresent()` sentinel that
returns early from `registerLateBound`. That sentinel is precisely what §B.5 deletes, because one
ref standing proxy for all of them is what left `core.remember` permanently unhandled.

Every naive resolution regresses something:

| Resolution | Outcome |
|---|---|
| Take 877's side | The sentinel returns; `core_remember` is offered and unhandled again — 876's defect, restored. |
| Take 876's side | The derived log line is lost; the hand-listed names come back — 877's defect, restored. |
| Take 877's helper onto 876's sentinel-free path | Both paths run, `register` throws on the first ref the eager path already claimed — a **boot failure** neither side would have produced alone. |

Converged instead: 877's call sites and log formatting, with §B.5's skip-if-present folded *into*
the helper, so a ref already present is a no-op and is not recorded (this call did not add it).
Both facts survive — the two registration paths compose, and the log still names exactly what this
call registered. 877's duplicate helper at the file tail, which auto-merged in cleanly, is removed.

This is the `subset-isnt-the-suite` hook's warning arriving for real: the third variant produces a
clean auto-merge with no marker anywhere, and only running the thing catches it. Note also that git
*did* flag this hunk — the semantic trap was in how one resolved it, not in whether one noticed it.

**`WorkflowOperationProjection`** — 879 deleted the inert `rateLimit` axis from `OperationPolicy`.
Kept §B.4's Optional-returning projection and composed availability with 879's shorter constructor;
the same argument drop applied to four `OperationPolicy` constructions across three test fixtures.

**`docs/22`** — both sides added `core_remember` / `core_navigate_to_surface` rows. Took 877's
phrasing (it owns the catalog census; its `core_navigate_to_surface` row is also more accurate, naming
the UI executor) and kept §B.8's paragraph on what else the offering composes.

Verification after reconciliation: `build -x test` green; full suite **8476 tests across 33 modules,
0 failures, 0 errors**; `check-live-witness`, `runtime-witness`, `operation-surface`,
`execution-surface`, `register-guard-resolution`, `check-tempdoc-numbers`,
`expected-state-probe --gate` and `agent-analytics 49/49` all green.

### C.8 The offering was hidden by a degraded-but-serving index (found by CI, not by the local suite)

CI's system-tests tier failed `ConsentCapsuleRecoveryE2ETest` — *"core.search-index declares
availability that evaluates live (available when index ready)"*. The local `test` task never runs
that tier, so this is the first defect in this tempdoc that only a live backend could surface.

**Diagnosed by probing a live isolated backend, not by reading.** `indexServing` settles at
`DEGRADED` / `index.dense_unavailable` — the dense/semantic leg is down (no embedding model in the
fixture) while the index still SERVES: `StatusLifecycleHandler.denseUnavailableReason`'s own javadoc
says AUTO has degraded to keyword. That reason had **no row** in `LifecycleSnapshotTap`'s
`MAPPING_TABLE`, so `reconcileDim` took its unmapped-unhealthy branch and *preserved* the boot-time
`index.unavailable` assertion. That branch is correct in itself — unknown is not healthy — but the
consequence was that `core.search-index`, gated on `Not(index.unavailable)`, stayed hidden from the
model for the life of the process. The amputation of 868 §C.3, reached by a new road: §B.2a made
reconciliation actually happen, and the state it reconciled *to* had no mapping.

Two fixes, both necessary:

1. **Map the known reason.** `(INDEX_SERVING, DEGRADED, index.dense_unavailable)` →
   `index.dense-unavailable`, WARNING. Being *mapped* is the whole point: `reconcileDim` step 1 then
   swaps a differing prior instead of preserving it, so the stale gate clears while the degradation
   stays visible under its own id. Without this the gate never clears — not even with a status poll.
2. **Reconcile on the worker-health poll.** `KnowledgeServerHealthMonitor.onTick` (a new callback in
   the same idiom as its existing `onRecoveryConnected`) requests a reconcile after every tick. The
   trigger's capability-transition arm structurally cannot see a dimension that settles *without* a
   transition, which is what left the gate shut until a browser called `/api/status`. This reuses
   the poll the head already runs rather than adding a timer of ours: the head keeps its health
   honest at the same cadence whether or not anyone is watching.

**The E2E now awaits convergence rather than sampling once, and that strengthens it.** The old
instantaneous assertion passed because nothing reconciled the store without a `/api/status` call —
which this test never makes — so it was asserting against an EMPTY store. It would have passed with
the worker on fire. Reconciliation is asynchronous by construction, so the honest claim is that the
offering *converges* with no request, which is exactly §B.2a's thesis. Fail-probed: removing the
mapping row makes it never converge.

**Considered and reverted:** splitting `worker.starting` onto its own condition so the boot window
also fails open. Correct by §B.9's P3, and it would narrow the window further — but unnecessary once
the system converges, and a late, unreviewed change to a health vocabulary other work owns. Recorded
because the narrower diff was a deliberate choice, not an oversight.

### C.9 Reconciling with 875

PR #581 (875, the consent boundary) merged while this branch was open and composes directly: 875
makes *resolution* run against the offered set, so 876's availability work now decides what is
dispatchable as well as what is visible.

- **The authorities compose rather than fork.** 875's `AgentToolEmitter.offeredWireNames` derives
  from `emit()`, and §B.1 made `emit()` derive from `offer()`. One authority, two derived views.
- **One real compile-level interaction.** 875 added stub emitters as *lambdas*; §B.1's second
  abstract method makes `AgentToolEmitter` non-functional. Converted both to anonymous classes, with
  `filteringEmitter` applying ONE filter chain to both faces — a stub that disagreed with itself
  would quietly invalidate every test built on it.
- **Mid-run availability flips yield a typed refusal, not a resolution miss.**
  `AgentStepRunner.isAuthorizedThisIteration` is a UNION: the emitter's *current* offering, or the
  list the model was actually handed this turn. §B.2b makes those two arms disagree mid-run, and arm
  2 is what must win — offering fails open, execution fails closed. Extended 875's
  `AgentToolAuthorityBoundaryTest` (rather than duplicating it) with the case its steering-list test
  does not reach: a tool offered at t=0 whose availability flips before the model calls it. The flip
  is driven off the emitter itself, so there is no timer. Fail-probed by disabling arm 2.
  §B.2b's monotonicity strengthens 875 here — arm 2 never shrinks within a run.
- **Three of this tempdoc's own assertions counted `emit` calls** as the witness for
  "re-evaluated per iteration". 875 legitimately adds an emit at the dispatch-authorization site, so
  an exact count now measures another workstream's behaviour. Converted to a lower bound (1 still
  fails, which is the invariant), and the wholesale-replacement check now asserts what it actually
  means — every entry in the adopted list carries the SAME emit ordinal, later than the previous
  list's — instead of a fixed number.
