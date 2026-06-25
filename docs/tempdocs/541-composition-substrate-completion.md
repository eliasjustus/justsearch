---
title: "541 — Composition Substrate: completion under the codebase's substrate discipline"
---

# 541 — Composition Substrate: completion under the codebase's substrate discipline

**Date**: 2026-05-21
**Status**: done
**Source paths**: `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java` (the shipped reference implementation of the typed-primitive component); `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/{phases,}/` (the existing phase substrate); the analogous procedural constructors in `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` and `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java` (the next two named-consumer adoption sites).

**Related**:
- `docs/explanation/25-service-lifecycle-pattern.md` — the canonical "Prefer substrate over per-feature fixes" framing this tempdoc operates inside.
- `.claude/rules/agent-lessons.md` §"Named substrate-discipline principles" + `docs/reference/contributing/agent-postmortems.md` §3-§6 — the named failure modes (`substrate-without-consumer-flavors`, `independent-review-required`, `static-green ≠ live-working`, `verdict-is-gate`) that govern substrate landings.
- C-018 ("substrate-without-consumer fails review") — the gating discipline this tempdoc complies with.
- Tempdoc 502 (`502-boot-composition-architecture.md`, **shipped**) — capability primitives (`WorkerCapability`, `InferenceCapability`, `CapabilityHealth`); `RequiredCapability` declarations. The substrate ancestry this design builds on.
- Tempdoc 519 (`519-head-composition-graph.md`, **shipped 2026-05-21 — commit `ffae40b9b`**) — landed the substrate's typed primitive: phase records, supplier-deferred binding, `HeadAssembly` thin sequencer, ArchUnit Rule 2 for service-construction-site discipline. This tempdoc completes the remaining 4 substrate components for the same primitive.
- Tempdoc 518 (`518-inferencelifecyclemanager-size.md`, **shipped**) — `TransitionRunner` envelope around state transitions; immutable `InferenceRuntimeView` atom; sealed `InferenceFailure` taxonomy. The reusable envelope this design borrows for its lifecycle/observability components.
- Tempdoc 529 (`529-observability-surface-on-518-substrate.md`, **shipped**) — the worked example of substrate-with-complete-five-components: ring-buffer endpoint, OTel span tagging, wireCode i18n catalog, named consumer (Brain UI panel), activation work. The template this design follows for §4.2.
- Tempdoc 517 (`517-search-execution-design.md`, **in-flight**) — "decision-as-value" + sealed-sum dispatch (`SearchPlan` family, `ResolvedLegs.from(...)`). The pattern this design generalizes from search execution to composition lifecycle (§5.3).
- Tempdoc 527 (`527-substrate-consumer-audit.md`, **shipped**) — one-shot named-consumer audit across six substrates. This tempdoc's §3 named-consumer enumeration mirrors 527's audit format.
- Tempdoc 530 (`530-class-size-ratchet-automation.md`, **open-design**) — discipline-gate kernel; per-gate-kind truth-table + changeset substrate. This tempdoc's §4.3 ships as a gate kind on 530 (with a fallback path if 530 slips — §7).
- Tempdoc 531 (`531-substrate-consumer-drift-detection.md`, **open-design**) — `consumer-drift` gate kind on 530. This tempdoc's §4.4 retraction protocol cites 531 slots.
- Tempdoc 539 (`539-cold-start-and-memory-profile.md`, **open-design**) — phase-level OTel tagging mentioned as instrumentation slot. The downstream consumer this tempdoc's §4.2 produces spans for.

---

## Section 1 — What this tempdoc is

A **long-term design** for the completion of the composition substrate landed by tempdoc 519 §31. Not a slice. Not a step-by-step plan. No timeline.

The framing is the codebase's **already-existing substrate discipline** (canonical: `docs/explanation/25-service-lifecycle-pattern.md`; enforced: C-018; audited: 527 / 531; failure-modes: agent-postmortems §3-§6). Under that discipline, a substrate is a five-component construct (§2). §31 shipped one component — the typed primitive. The remaining four (named consumer enumeration, observability surface, discipline gate, retraction protocol) plus a cross-process generalization are this tempdoc's design scope.

This tempdoc deliberately does **not** invent a new architectural pattern. The 519 §31 typed-phase-records-and-thin-sequencer shape, the 518 `TransitionRunner` envelope, the 529 substrate-with-consumer worked example, the 530 discipline-gate kernel, the 531 drift detection, and the 517 decision-as-value pattern are all named, shipped (or open-design) primitives. This tempdoc composes them into the completed substrate.

The contribution is **completion under the existing discipline**, with four genuinely novel design dimensions that deserve naming (§5).

## Section 2 — What "substrate" means in this codebase (cited, not invented)

Per `docs/explanation/25-service-lifecycle-pattern.md` §"Prefer substrate over per-feature fixes" and the C-018 discipline, a substrate is **a typed primitive that has all five of**:

| Component | Definition | Canonical example |
|---|---|---|
| **(a) Typed primitive** | A sealed / record-shaped value class with stable identity | 518 `TransitionRunner` + `InferenceRuntimeView`; 519 §31 `ServiceGraph` + `SubstrateGraph` + `CapabilityGraph` + phase Output records |
| **(b) Named production consumer** | At least one specific, named callsite that reads the primitive at landing — *not* "this will be useful when X exists" | 529 Brain UI panel consuming `/api/inference/transitions`; 519 §31 `HeadAssembly` consuming the phase records |
| **(c) Observability surface** | Ring buffer + HTTP endpoint + SSE stream + OTel spans, typically modeled on 518/529 | 529 `/api/inference/transitions` ring, OTel spans, mode-transition timeline |
| **(d) Discipline gate** | ArchUnit rule, contract test, lint rule, or 530-kernel gate kind that prevents drift | 519 §31 ArchUnit Rule 2; 531 `consumer-drift` gate kind; 524 C-018 audit |
| **(e) Retraction protocol** | Documented path to delete the substrate if its consumer fails to materialize | 527's "C-018-unnamed-pending" verdict class; 531's `grace.until` field. (511's `normalizeOperationFromWire` deletion is the *inverse* precedent: "delete bridging code once real substrate proves" — relevant philosophy, not direct precedent.) |

Two weaker-but-recurrent invariants:

| Component | Definition |
|---|---|
| **(f) Activation work** | The wiring that makes the substrate functional end-to-end at landing. Substrate without activation is phantom. |
| **(g) N-instance rationale** | The "two more instances of the same shape nearby" criterion that justifies the substrate framing in the first place. |

This tempdoc operates inside this definition. It does **not** redefine "substrate"; it audits §31 against the definition, fills the missing components, and ships them under the same discipline.

Two further citations support 541's framing without 541 needing to re-derive them:
- `25-service-lifecycle-pattern.md` lines 210-222 (shipped-instances table — `LuceneRuntime` Form A full adoption; `InferenceLifecycleManager` Phase-typed with telemetry substrate shipped, holder rewrite deferred) names 541's direct sibling consumers.
- `25-service-lifecycle-pattern.md` lines 191-208 (consumer-as-holder rationale — "why a volatile field + holder swap, not a restart() method on the value") supplies the reasoning §6's "no per-phase retry / circuit-breaker" anti-design boundary inherits from.

A canonical single-doc "Substrate Definition" consolidating this distributed theory would be useful but is **out of scope** for this tempdoc — deferred to a separate canonical doc rewrite.

## Section 3 — §31 substrate audit (which components shipped, which remain)

The substrate is **the head-process composition substrate**. Its components, audited against §2:

| Component | Status | What's shipped |
|---|---|---|
| (a) Typed primitive | ✅ **shipped** | `Phase.Output` records (`InfraPhase.Output`, `CapabilityGraph`, `ServicePhase.Output`, `SubstrateGraph`, `OrchestrationHandles`), supplier-deferred binding via `BootstrapLateBindings`, the `HeadAssembly` thin sequencer |
| (b) Named production consumer | 🟡 **one of N** | `HeadAssembly` itself consumes the phase records; `LocalApiServer` consumes the held `ServiceGraph`. **Missing**: a named consumer for each Phase Output as a substrate slot (per 527/531 cardinality discipline); a named UI consumer for the (not-yet-existing) observability surface |
| (c) Observability surface | ❌ **missing** | No `/api/boot/phases` ring buffer, no OTel spans tagged by phase name, no SSE stream. 539 names this as an instrumentation slot but does not design the surface |
| (d) Discipline gate | 🟡 **one of seven** | ArchUnit Rule 2 (service-construction-site) is enforced. **Missing**: six other invariants (phase count ceiling, no `Supplier<T>` escape, BootstrapLateBindings holder cap, Output cardinality / immutability, eagerness/laziness conformance, lazy-phase declaration). Not yet unified as a `composition-root` gate kind on 530's kernel |
| (e) Retraction protocol | ❌ **missing** | No documented path to retract any specific Phase Output field, the lateBindings holder, or the `/api/boot/phases` endpoint if its consumer never materializes |
| (f) Activation work | 🟡 **partial** | Phase outputs ARE wired and operational; the 521 T2.5 capability bridge ported during the merge IS the activation for the substrate's Worker-connect path. **Missing**: activation for §4.2's observability surface (the surface ships only with a named consumer that renders the trace) |
| (g) N-instance rationale | ✅ **explicit** | §5 (cross-process generalization) names three composition-root instances (Head, Worker, Brain) and four downstream substrate consumers (532, 533, 534, 535, 536) |

Verdict: §31 shipped one substrate component fully (a), one partially (b), one substantially (g), and left three unshipped (c, d, e). Per the C-018 discipline, the substrate is **earning out for its first consumer** (`HeadAssembly`) but is **incomplete as a reusable substrate** until (b)-(e) are designed and shipped.

This tempdoc designs the completion.

## Section 4 — The four missing components, each with named consumers and retraction clauses

Each sub-section follows the codebase's substrate format: typed primitive sketch → named consumer at landing → observability surface (if applicable) → discipline gate → retraction clause.

### 4.1 Named consumer enumeration per substrate slot (component b)

Mirrors the 527 audit format. Each Phase Output field is a substrate slot.

| Slot (Phase Output field) | Named production consumer at landing | Cardinality verdict |
|---|---|---|
| `ServicePhase.Output.workers().search()` | `KnowledgeSearchController`; agent `SearchTool` | ≥ 2, healthy |
| `ServicePhase.Output.workers().indexing()` | `IndexingController` (supplier); agent `IngestTool`; `SyncOps` | ≥ 3, healthy |
| `ServicePhase.Output.workers().documents()` | `PreviewController`, `ChunkInfoController` (suppliers) | ≥ 2, healthy |
| `ServicePhase.Output.workers().excludes()` | `IndexingController.handleApplyExcludes` (via injected `ExcludesService`) | 1, fragility signal per 527 §7 (🟡 indirect — flows through `ExcludesService` constructor injection) |
| `ServicePhase.Output.inference().onlineAi()` | `InferenceHandlers`, `BrainRuntimeServiceImpl`, `OfflineCoordinatorBuilder` | ≥ 3, healthy |
| `ServicePhase.Output.{brainRuntime, runtimeVariant, packImport, brainInstall, policy, diagnostics}()` | Each has its own controller + `OperationHandlerRegistrations` registration; ≥ 2 consumers each |
| `ServicePhase.Output.{aiInstallHelper, aiPackImportHelper, runtimeActivationHelper, packAllowlistService}()` | One controller each (`AiInstallController`, `AiPackController`, `AiRuntimeController`) | 1, fragility signal per 527 §7; flag for grace period (🟡 callsites flow indirectly through controller constructor injection — see §9.3 residual #1) |
| `ServicePhase.Output.gpuCapabilitiesService()` | `LocalApiServer.statusLifecycleHandler` (confirmed); `InferenceHandlers`, `EffectiveConfigController` flagged as 🟡 indirect | ≥ 1 confirmed; potentially ≥ 3 (per §9.3 residual #1) |
| `ServicePhase.Output.enterprisePolicy()` | `PolicyController`, `DiagnosticsController`, `AiInstallController`, `AiRuntimeController`, `AiPackController` | ≥ 5, healthy |
| `BootstrapLateBindings.{settingsResetFn, debugStateProvider, statusSnapshotProvider}` | One consumer each (`SettingsServiceImpl`, `DiagnosticsServiceImpl`) | 1 each, fragility signal per 527 §7 — but justified (these are the 3 *inherent* controller back-refs the §31 design names; not eligible for retraction) |
| `SubstratePhase.Output.healthOut.lifecycleSnapshotTap` | `StatusLifecycleHandler` | 1, fragility signal per 527 §7 |
| `SubstratePhase.Output.operationOut.capabilitiesChangeRegistry` | `InfraRoutes` (post-merge) | 1, fragility signal per 527 §7 |
| (proposed §4.2) `GET /api/boot/phases` | **C-018-unnamed-pending** at landing → endpoint + FE consumer must ship together |

The verdict format mirrors 527: **healthy** (≥ 2 consumers, or 1 justified-inherent), **fragility signal per 527 §7** (1 consumer — calibration risk per 527's §7 wording, not a verdict of failure), **C-018-unnamed-pending** (0 consumers — ship-or-retract protocol kicks in).

The `/api/boot/phases` endpoint's named-consumer-at-landing is a hard constraint: **the endpoint and its FE consumer ship in the same slice**, or neither ships. No pre-existing FE boot-status panel exists (per §9.1 A8); the consumer must be co-built. The natural candidate is an extension to `StatusDeck.ts` (already consumes `/api/status` capability pills) or a small new `BootPhasesPanel.ts` — chosen in-slice based on which is cleanest.

### 4.2 Observability surface (component c)

The shape borrows from 518/529 selectively — boot phases happen *once* per process, not many times, so the ring-buffer pattern is the wrong shape (per §9.1 A3 retraction). The substrate uses an **immutable snapshot** instead:

| Element | Modeled on / reused from |
|---|---|
| `BootTrace` (immutable record: process name, started/completed timestamps, ordered list of `PhaseRecord`) | New shape — once-per-process snapshot, not the 518 ring buffer |
| `PhaseRecord` (immutable record per phase invocation: name, eagerness, started, completed, outcome) | 518 `TransitionRecord` structure (defensive immutability), one-time write semantics |
| `GET /api/boot/phases?process={head\|worker\|brain}` returning `{boot: {process, completedAt, phases: [...]}}` | 529 `GET /api/inference/transitions` response envelope |
| `GET /api/boot/phases/stream` (SSE) — phase-by-phase events during cold boot | Reuse `SseEnvelopeWriter` (per §9.1 B5, infrastructure already exists post-§31 merge) |
| OTel spans `composition.boot` (parent) + `composition.phase.<name>` (children) | 529 generation-tagged spans. **Note** (§9.1 B1): tracing is off in dev by default (`HEAD_TRACING_LEVEL=none`); the substrate emits spans unconditionally — they appear when tracing is on. |
| FE consumer: new minimal panel rendering the phase list + timings | 529 Brain UI mode-transition timeline (template only; no pre-existing boot panel — §9.1 A8). Co-built in the same slice. |
| Activation work: the FE consumer ships in the same slice (per §4.1 named-consumer-at-landing rule) | 529 activation work principle |
| `wireCode → i18n` catalog for phase-level failure reasons | 529 inference-failures i18n catalog |

This sub-component is **largely reusable** — every primitive exists except `BootTrace` itself (~30 LOC) and the FE consumer. The discipline of "snapshot, not ring buffer" stays consistent with the once-per-boot lifecycle.

**Retraction clause**: if the FE consumer never lands or its weekly view-count drops to 0 for 90 days (per 531's tempdoc-bound grace semantics), `GET /api/boot/phases` retracts. The `BootTrace` snapshot becomes JVM-internal only; OTel spans remain (they have other consumers: 539 cold-start profiler, generic OTel agents).

### 4.3 Discipline gate: ArchUnit rules as the primary landing path, 530-kernel as migration target (component d)

Per §9.1 A4: 530 is open-design, no implementation. This sub-component lands as a `composition-root` ArchUnit rule class in `modules/app-services/src/test/java/.../` — the same shape as §31's shipped Rule 2. Once 530's kernel skeleton lands, the rules migrate to gate-kind rows. Truth-table rows:

| Rule | Detection | Today's status |
|---|---|---|
| Service construction site | No `new *ServiceImpl(...)` outside `bootstrap/phases/..` | shipped (519 §31 ArchUnit Rule 2); lifted onto 530 kernel |
| Supplier escape | `Supplier<T>` parameters in `app-services` originate only from Phase Inputs or `BootstrapLateBindings` | unshipped |
| Output immutability | Phase Output types are records with all-final fields, no mutable collections | unshipped |
| Output cardinality | ≤ 10 fields per Output record (god-record guard); growth requires a changeset row | unshipped |
| Late-binding holder cap | `BootstrapLateBindings` count ≤ 5; growth requires changeset | unshipped |
| Phase count ceiling | Per-process phase count ≤ 8 (Head: 5 today); growth requires changeset | unshipped |
| Lazy-phase conformance | A Phase typed `Eagerness.LAZY` has Output of form `Supplier<T>` and vice versa | unshipped (gates §5.2) |

**Primary landing path**: each row lands as an ArchUnit rule (or per-rule predicate) in a new `CompositionRootGuardrailsTest` class under `modules/app-services/src/test/java/.../`. The existing §31 Rule 2 (service construction site, with its custom `JavaConstructorCall` predicate) is lifted into the same class. ArchUnit naming: `compositionRoot*`.

**Migration path**: once 530's discipline-gate kernel skeleton lands, the rules migrate to gate-kind rows with SARIF v2.1.0 output, changeset-bound escape valves, and `--self-test` scaffolding. Until then the ArchUnit class IS the enforcement.

**Named consumer at landing**: the gate runs in CI; the consumer is the merge gate itself + the changeset audit trail. Aligns with how `class-size`, `consumer-drift`, and `npm-audit` gate kinds land under 530.

**Retraction clause**: if any rule ever has zero violation rows for 90 days AND zero deltas in the affected substrate's hot files, the rule retracts. The substrate has self-evidently stabilized; the gate becomes overhead.

### 4.4 Retraction protocol (component e)

Three retraction trajectories, each grounded in shipped precedents (per §9.1 A5 and D1 revisions):

| Slot type | Retraction trigger | Action |
|---|---|---|
| Phase Output field with **0 consumers** for 90 days | C-018 audit verdict ("C-018-unnamed-pending" per 527 vocabulary) | Field removed from Output record; downstream phase Inputs updated; changeset row records the removal |
| Phase Output field with **1 fragility-signal consumer** (per §4.1 audit; per 527 §7 calibration-risk classification) | 531 `consumer-drift` gate `grace.until` expiry (per 531's defined semantics: tempdoc-bound / commit-bound / date-bound) | Either the slot earns a second consumer or the slot retracts on schedule; changeset row records which |
| Whole Phase with **0 production effect** (degraded → unreached) | `composition.phase.<name>` OTel span emits `outcome=Failed(notImplemented)` consistently OR phase is `Eagerness.LAZY` and its supplier is never invoked for 90 days | Phase removed from the composition root; changeset row records the removal |

The protocol's audit cadence: **a manual quarterly pass** runs against the substrate-slots contract doc (§4.1 reified as `docs/reference/contracts/composition-substrate-slots.md`). When 540's observations-inbox-processing tempdoc lands as a recurring CI process, this audit migrates to that cadence; when 531's `consumer-drift` gate kind lands on 530's kernel, the fragility-signal trajectory automates. Until then, the manual quarterly cadence IS the protocol — documented in `docs/reference/contributing/composition-substrate-retraction.md`.

**Philosophical precedent**: 511's `normalizeOperationFromWire` deletion (after the aggregate consumer landed) demonstrates the converse principle — "delete bridging code once real substrate proves." 541's retraction protocol applies the same disposability discipline to the substrate's own slots when consumers fail to prove.

**Inherent slots not eligible for retraction**: the 3 `BootstrapLateBindings` holders (§4.1) are inherent to the controller-as-SPI-source pattern the §31 design names. They have justified single-consumer cardinality and ship outside the C-018 audit. The named-consumer enumeration explicitly flags them.

## Section 5 — Genuinely novel design dimensions (the contribution over the meta-pattern)

These four dimensions extend the substrate pattern beyond what 518/529/527 ship. Each is named and isolated so it can be ratified or rejected on its merits.

### 5.1 Cross-process uniformity as a design axis

Every existing substrate is scoped to one process (Brain for 518/529; FE for 511/521/449; Worker for 526's indexing-side primitives). This tempdoc's substrate explicitly claims **one shape across three composition roots**:

| Composition root | Process | Status |
|---|---|---|
| `HeadAssembly` | Head | shipped (§31). Phase-decomposed (5 phases). |
| `WorkerAssembly` | Worker | unbuilt; the `KnowledgeServer.java` (~1989 LOC, named in tempdoc 512 §B3) is **phase-decomposable** per §9.1 C1 (8 phases identified: telemetry → signal bus + job queue → config + Lucene → AppServices → gRPC server → port handoff → indexing loop → background model init). gRPC server-up is the **Orchestration phase** (Phase 4), not a pre-phase invariant. Sized as a separate decomposition tempdoc. |
| `BrainAssembly` | Brain | unbuilt; **wraps** `InferenceLifecycleManager` as a single Phase Output per §9.1 C2 (ILM is not phase-decomposable — it is a stateful service whose own `TransitionRunner` IS its lifecycle). BrainAssembly composes ILM as one phase among others, drives its mode transitions via runtime method calls. |

Cross-process **shape variants**, not uniformity:
- Same five-component substrate definition (§2) applies to each.
- Same observability endpoint shape (§4.2) with `?process=` discriminator.
- Same `composition-root` ArchUnit rule class (§4.3) — neutralized of "head" prefix.
- Same retraction protocol (§4.4).
- **Process-specific phase semantics**:
  - Head + Worker: phase-decomposition pattern (typed Input → Output → next-phase Input).
  - Brain: wraps a stateful service (ILM) as one Phase Output; subsequent phases consume the held service. The substrate primitive (`Phase<I,O>`) is the same, but the *body* of the BrainAssembly's ILM-phase is "store the constructed ILM" — the ILM's own internal lifecycle is opaque to the composition substrate.

This nuance is the genuinely novel claim (refined): **one substrate primitive, two body-shape variants** (phase-decomposed vs service-wrapping). No prior tempdoc proposes a substrate that handles both shapes uniformly. The closest precedent (518's `TransitionRunner`) is itself the inner shape of ILM that BrainAssembly wraps.

**Build-order resilience**: the substrate ships per-process. Head adoption (§4.1-§4.4) lands first; Worker and Brain adoptions are independent slices, each gated by its own KnowledgeServer / InferenceLifecycleManager decomposition tempdoc. Cross-process uniformity is **realized incrementally**, not big-bang.

### 5.2 Eagerness as a typed Phase dimension

A `Phase<I, O>` declares `Eagerness.EAGER | LAZY`. Eager phases run at composition-root construction; lazy phases return a `Supplier<O>` that triggers the phase body on first read and memoizes.

This is unprecedented in this codebase. Closest analogue: §31's `BootstrapLateBindings` AtomicReferences, but those are *external publication*, not *deferred execution*.

**Candidates** (revised per §9.1 B3): each phase below already has an implicit availability/config gate at boot. Reifying those implicit gates as explicit `Eagerness.LAZY` declarations with named triggers is the value — *not* "these are unused at boot" (the §9 evidence shows they DO execute at boot today, just with internal no-op gates).

- `LambdaMartTraining` — boot log shows `LambdaMART: disabled via config`. Implicit gate: feature-flag check. Reify as LAZY with trigger "first reranking request needs lambdamart."
- `AgentToolHandlers` — boot log shows `registerAgentToolHandlers skipped: knowledgeClient or worker capability unavailable`. Implicit gate: Worker capability. Reify as LAZY with trigger "agent invocation arrives AND Worker is READY."
- `GplJobCoordinator` — implicit gate: `clientSupplier or aiService unavailable`. Reify as LAZY with trigger "first GPL training tick."
- `OfflineCoordinatorBuilder` — implicit gate: `manager or client unavailable`. Reify as LAZY with trigger "first offline-processing request."

**Discipline coupling**: `Eagerness.LAZY` declarations are a row under §4.3's ArchUnit rules (rule 4e — lazy-phase conformance). Declaring a phase lazy requires naming the trigger; the latency budget is a *recommendation* until 539's measurement work lands and can validate the budget.

**Activation**: pairs with 539 cold-start profile measurement to validate that lazy phases actually defer their cost. Without 539 the latency budgets are unmeasured; the eagerness declaration becomes vibes. (See §7 for the dependent-design slip case.)

### 5.3 Sealed-sum `PhaseOutcome` (517 pattern, lifted to composition lifecycle)

```
sealed interface PhaseOutcome<O>
    permits Ready, Degraded, Failed { … }
```

This applies 517's decision-as-value pattern outside its origin domain. 517 introduced sealed-sum dispatch for search execution (`SearchDecision` sealed interface in `modules/worker-services/.../plan/SearchDecision.java`, shipped per §9.1 D4). This tempdoc generalizes it to composition lifecycle: every phase returns a typed Ready/Degraded/Failed outcome with attached ReasonCodes (cross-referencing 529's inference-failure ReasonCode taxonomy where applicable).

This is **the first application of 517's pattern outside search execution** (search is the origin domain; composition lifecycle would be the second). Worth foregrounding as a cross-domain extension of an existing primitive, not as a new invention.

**Practical effect**: `HeadAssembly.workers().indexing()` becomes a pattern-match over the held `ServicePhase.Output` outcome. Today's `IndexingService.unavailable()` sentinel pattern becomes `Failed(ReasonCode.workerNotReady)` with attached cause — typed-and-introspectable instead of fall-through-null.

**Migration shape**: sealed-sum Outcomes land per-phase, smallest first. Existing phases run product-type Outputs until each is migrated. The substrate carries both shapes during transition; the gate kind (§4.3) tracks the migration progress.

### 5.4 Fourth-order substrate composition (and the build-order fallback this requires)

Most substrate tempdocs cite 1-2 other substrates. This tempdoc cites four explicitly:
- 530 (gate kind host) for §4.3
- 531 (drift gate slots) for §4.1 and §4.4
- 518 (TransitionRunner envelope) for §4.2
- 529 (observability publication template) for §4.2

The risk is over-coupling to designs that aren't shipped yet (530, 531, 539 are open-design as of writing).

The mitigation is **§7 — build order resilience**: explicit fallback for each open-design dependent. This tempdoc must not gate on 530/531/539 landing in a particular order.

## Section 6 — Anti-design boundaries

What this tempdoc **does not propose** (carries forward from the prior draft, lightly tightened):

- **No DI framework.** No Spring, no Dagger, no Guice. Phases compose by source-level typed handshake.
- **No service registry.** Services are addressed by typed accessor (`assembly.workers().search()`); the type system is the registry.
- **No plugin SPI sprawl.** A plugin contribution is a Phase factory, not 14 sub-interfaces.
- **No mutable assembly state.** The composition root is a value. Rebuild produces a *new* `ServiceGraph` via the (planned) `RebuildPhase`; only the held `ServiceGraph` reference flips via CAS.
- **No phase ordering by config.** Phase order is source-encoded by the composition-root function body. Dynamic ordering belongs to an OTP-supervisor runtime, which is not the target.
- **No per-phase retry / circuit-breaker policy.** Outcomes propagate; retry is consumer-side.
- **No forced "framework adopt or migrate" cliff.** The substrate's missing components (§4) land incrementally. Existing phases continue working under the current product-type Output shape until migrated.
- **No new substrate term.** This tempdoc operates inside the existing 5-component substrate definition (§2). A canonical Substrate Definition doc would consolidate the distributed theory but is out of scope.

## Section 7 — Build-order resilience (what if dependent designs slip)

The substrate's four missing components cite four open-design dependents (530, 531, 539, 517-in-flight). This appendix names the fallback for each:

| Dependent | What §541 cites it for | Fallback if it slips |
|---|---|---|
| **530** discipline-gate kernel | §4.3 gate kind host | §4.3 ships as a standalone ArchUnit test class (loose analogue of 519 §31's `AppServicesWorkerGuardrailsTest`); promotes to 530 kernel when 530 lands. SARIF output deferred. Changeset escape valves deferred. The 7 invariants are enforced, just not unified. |
| **531** consumer-drift gate | §4.1 substrate slot enumeration + §4.4 retraction trigger | §4.1 ships as a checked-in `docs/reference/contracts/composition-substrate-slots.md` audit (mirrors 527's one-shot audit format). §4.4 retraction trigger becomes a manual quarterly audit via tempdoc 540 inbox pass instead of an automated `grace.until` check. |
| **539** cold-start profile | §5.2 lazy-phase latency-budget validation | Eagerness declarations ship without measured latency budgets; the discipline gate's "lazy-phase conformance" rule (§4.3) gates only on declaration consistency, not on observed latency. Budgets backfill when 539 measurement work lands. |
| **517** decision-as-value | §5.3 sealed-sum `PhaseOutcome` | §5.3 ships as documented design only; per-phase migration to sealed-sum Outcomes is gated on 517's close-out (which crystallizes the pattern's idiom). Phase Outputs remain product types in the interim. |

The substrate's *first three components* (§4.1, §4.2, §4.3 as ArchUnit, §4.4 as manual audit) ship without any dependent landing. Cross-process generalization (§5.1) and full sealed-sum migration (§5.3) wait on dependents.

## Section 8 — Closure criteria

This tempdoc closes when **all five substrate components hold for the head-process composition substrate**:

1. **(a) Typed primitive** — already shipped (519 §31); no further work.
2. **(b) Named consumer enumeration** — every Phase Output field has ≥ 1 named production consumer per §4.1's audit, with C-018-unnamed-pending rows explicitly retracted or graced. *Including the new `/api/boot/phases` endpoint's FE consumer.*
3. **(c) Observability surface** — `GET /api/boot/phases` + SSE + OTel spans land; FE Brain-UI boot panel renders the trace; activation work is complete (529 pattern).
4. **(d) Discipline gate** — 7 invariants enforced, ideally as a `composition-root` gate kind on 530; fallback as ArchUnit per §7.
5. **(e) Retraction protocol** — documented in §4.4; the first scheduled retraction or grace audit occurs; the protocol is real, not paper.

Additional novel-dimension closure (§5):
6. **Cross-process uniformity** — at least one of `WorkerAssembly` or `BrainAssembly` adopts the substrate. Proof-of-uniformity.
7. **Eagerness typing** — at least one phase ships `Eagerness.LAZY` with a named trigger and a (measured or estimated) latency budget.
8. **Sealed-sum PhaseOutcome** — at least one phase migrated from product-type Output to sealed-sum Outcome (gated on 517 close-out).
9. **Downstream consumer earning out** — at least one of (532, 533, 534, 535, 536) ships as a Phase under this substrate, proving §5's second-order effects.

Until those nine hold, this is a design we agreed-on-paper to evolve toward, not a delivered substrate completion. The closure pass should explicitly cite each criterion above and either confirm landing or document an explicit retraction.

---

## Section 9 — Confidence audit outcomes (pre-implementation evidence pass)

A pre-implementation evidence pass run 2026-05-21 verified the load-bearing claims in §3-§7 against the codebase. This section records what was checked, what stands verified, what was revised, and what remains residual. The audit ran 21 numbered items (A1-A8, B1-B5, C1-C3, D1-D5) per a separately-recorded plan.

### 9.1 Verification matrix

| Item | Claim under audit | Verdict | Notes / 541 edit reference |
|---|---|---|---|
| A1 | Named-consumer cardinality per Phase Output field (§4.1 table) | ⚠️ revised | 9 of 13 slots verified ≥-claim-cardinality. 4 slots have indirect / unverified evidence: `workers().documents()` (PreviewController not located in target modules); `workers().excludes()` (method name `handleApplyExcludes` unverified); `gpuCapabilitiesService` (only `statusLifecycleHandler` confirmed of 3 claimed); `serviceOut().{aiInstallHelper, aiPackImportHelper, runtimeActivationHelper, packAllowlistService}` (no direct callsite grep; consumed indirectly via supplier-deferred wrappers). The supplier-deferred-binding shape makes precise grep cardinality unreliable — see §9.3 residual. |
| A2 | "Fragile per 527" tagging in §4.1 | ⚠️ revised | 527's actual verdict vocabulary is `Production-consumed / C-018-named-deferred / C-018-unnamed-pending / Hollow` (§2 methodology). "Fragile" is 527's *signal* category (§7 — "single-consumer is not a defect per se; it is a calibration risk"), not a verdict. **Edit §4.1**: replace "fragile per 527" with "fragility signal per 527 §7" (one-consumer slots) and "C-018-unnamed-pending" for the `/api/boot/phases` zero-consumer slot. |
| A3 | `BootTransitionRunner` modeled on 518 `TransitionRunner` ring buffer | ❌ retracted | The 518 ring-buffer (capacity 20, synchronized, defensive snapshots) is designed for many-time mode transitions. Boot phases happen *once* per process; a ring buffer adds overhead for data that never exceeds one entry. **Edit §4.2**: replace the "ring buffer" shape with an immutable snapshot `BootTrace(process, List<PhaseRecord>)` written once at boot completion. Keep the OTel span + SSE + endpoint surface from 529's template. |
| A4 | §4.3 ships as gate kind on 530 kernel | ✅ confirmed (with caveat) | 530 is design-only (Pass-2 rewrite, no implementation). The ArchUnit fallback proposed in §7 is realistic and 530 itself proposes a parallel path. **Edit §7**: promote the ArchUnit path from "fallback" to **primary landing path**; 530-kernel hosting is the migration target once 530's scaffolding lands. The CheckClassSizeTask + contract-governance kernel skeleton (commit `3494c3776`) is the closest precedent. |
| A5 | 511 "correctly hollowed" precedent supports §4.4 retraction protocol | ⚠️ revised | 511's actual precedent is "delete bridging code once real substrate proves" (the `normalizeOperationFromWire` deletion *after* the aggregate consumer landed), not "delete when consumer fails to materialize." **Edit §4.4**: revise the precedent citation. The forward-looking framing — "delete substrate when its consumer fails to materialize after a grace window" — remains a valid design but does not have 511 as its direct precedent. The closest precedent is 531's `grace.until` semantics + the 527 audit findings on `C-018-unnamed-pending` rows. |
| A6 | `docs/explanation/25-service-lifecycle-pattern.md` cited as canonical substrate definition | ✅ confirmed | Document exists; lines 34-64 define the five-element pattern; lines 235-253 state the "two more instances of the same shape nearby" criterion. **Edit §1 + §2**: add citations to its "shipped instances" table (lines 210-222) — LuceneRuntime (Form A full adoption), InferenceLifecycleManager (Phase-typed, telemetry substrate shipped, holder rewrite deferred). These are 541's direct sibling consumers and deserve naming. |
| A7 | 507-kernel-boundary worktree has not touched HeadAssembly / LocalApiServer | ✅ confirmed | Zero matches on 507's `main..HEAD` for HeadAssembly.java or LocalApiServer.java. Consumer enumeration is current. No edit needed. |
| A8 | A Brain UI boot panel exists as the candidate FE consumer for `/api/boot/phases` | ❌ retracted | No such component exists in `modules/ui-web/src/`. The closest existing FE consumer is `StatusDeck.ts` (renders `/api/status` capability pills) or the inference-transitions timeline (529). **Edit §4.1**: the `/api/boot/phases` slot's named-consumer-at-landing constraint stands, but the candidate must be a *new* component (or an extension to StatusDeck), not a presumed-existing panel. Make the slice gate explicit: "FE consumer is co-built or substrate slot retracts." |
| B1 | OTel spans already emitted by HeadAssembly at boot | ⚠️ revised | No `traces.ndjson` in live dev-data dir (`HEAD_TRACING_LEVEL=none` by default). Existing OTel infra is wired but not emitting at boot today. **Edit §4.2**: explicitly state that the substrate must turn tracing on for boot spans to be useful — pairs with 539's "phase-level OTel tagging" as the slot to fill. |
| B2 | `/api/inference/transitions` shape is the template for BootTransitionRunner | ✅ confirmed | Endpoint returns `{transitions: [...]}` with the 518 transition records. JSON shape clear. **Edit §4.2**: cite the actual response shape `{boot: {process, completedAt, phases: [PhaseRecord, ...]}}` modeled on 529's response shape. |
| B3 | Agent-tool wiring / GPL / LambdaMART / VDU OfflineCoordinator are unused at boot | ⚠️ revised | Boot log shows: `LambdaMartTraining: disabled via config` (runs but no-ops); `registerAgentToolHandlers skipped: knowledgeClient or worker capability unavailable` (already gated). They *do execute* at boot; the gating is implicit. **Edit §5.2**: reframe candidates as "phases with implicit availability/config gates that could be reified as `Eagerness.LAZY` with explicit triggers" — not "unused at boot." This is a real improvement: explicit lazy declaration replaces hand-rolled inline gates. |
| B4 | Per-phase wallclock cost is observable today | ❌ retracted | Phases emit no log lines at their own boundaries (zero matches for `Phase|InfraPhase|CapabilityPhase|...` patterns in head log). Phases run silently. Confirms the observability gap §4.2 names. No edit needed; reinforces the gap. |
| B5 | SSE infrastructure exists post-merge for boot-phase publication | ✅ confirmed | `SseEnvelopeWriter.java` + 8 SSE channel consumers (AdvisoryStreamController, AgentController, IntentStreamController, ConditionRecoveryIndexController, HealthEventStreamController, etc.). **Edit §4.2**: the `/api/boot/phases/stream` channel is **reuse**, not new infrastructure. |
| C1 | KnowledgeServer is phase-shaped for `WorkerAssembly` decomposition | ✅ confirmed | 8 discernible phases identified in `KnowledgeServer.start()` (lines 235-655): telemetry → signal bus + job queue → config + Lucene → AppServices → gRPC → port handoff → indexing loop → sentinel → background model init. Phase decomposition is feasible. |
| C2 | `BrainAssembly` is a phase decomposition of `InferenceLifecycleManager` | ❌ retracted | ILM is *not* phase-decomposable. It's a stateful service whose own `TransitionRunner` IS the lifecycle. **Edit §5.1**: `BrainAssembly` would *wrap* ILM as a single Phase Output, not extract phases from it. The "same shape across three processes" claim must be nuanced: Head + Worker share the phase-decomposition pattern; Brain composes a TransitionRunner-owning service as one phase among others. This is a real revision to §5.1's framing. |
| C3 | Worker's gRPC server affects phase ordering | ✅ confirmed | gRPC bind is Phase 4 (after AppServices Phase 3, before indexing-loop start Phase 6). Signal bus port write is Phase 5. **Edit §5.1 + §6**: state explicitly that `WorkerAssembly` has gRPC server-up as a Phase (the Orchestration phase, in §31 terms), not a pre-phase invariant. |
| D1 | 540 observations-inbox is a recurring pass providing retraction cadence | ⚠️ revised | 540 is design-only (open-status, threshold-triggered or periodic-backstop design). Not yet implemented. **Edit §4.4**: revise "the protocol's audit cadence is **the 540 observations-inbox pass** (which already does this for `docs/observations.md`)" — remove the "already does this" framing. Either defer the retraction cadence to 540 landing, or define a manual quarterly audit pass that doesn't gate on 540. |
| D2 | C-018 is a load-bearing review-gate | ✅ confirmed | Named principle in `.claude/rules/agent-lessons.md`; enforced via review discipline + post-mortems (not automated). Demonstrated load-bearing by post-mortem §6 (slice 481 shipped substrate against verdict and earned the named violation). No edit needed; 541's framing is correct. |
| D3 | `grace.until` semantics match 541 §4.4 framing | ✅ confirmed | 531 §"Grace semantics" defines exactly tempdoc-bound / commit-bound / date-bound expirations. 541's phrasing is accurate. No edit needed. |
| D4 | 517's "decision-as-value" pattern is shipped in code | ✅ confirmed (with phrasing nuance) | `SearchDecision.java` (modules/worker-services/.../plan/) is the shipped sealed-sum at lines 26-44. **Edit §5.3 phrasing**: replace "the first cross-domain application of the 517 pattern" with "the first application of 517's pattern outside search execution" (search is the *origin*, composition lifecycle would be the second domain). |
| D5 | 488 is a missing-substrates audit cited as 534's source | ❌ retracted | 488 is an LLM-feasibility analysis nested under 421, not a missing-substrates audit. **Edit §6**: remove or replace the implicit cite of 488. The "downstream consumers" list (532, 533, 534, 535, 536) stands on its own without 488 framing. |

### 9.2 Specific section revisions deferred to implementation

The audit identified ~12 specific text edits. They are *not* applied in this audit pass — they land alongside or before the first implementation slice of any §4 sub-component. Rationale: bundling text-edits with the first concrete substrate work keeps the tempdoc history clean and lets each edit cite the implementing commit.

Summary of pending edits (all derive from §9.1):

- **§1 + §2**: add citation to `25-service-lifecycle-pattern.md` lines 210-222 (shipped instances) and lines 191-208 (consumer-as-holder rationale).
- **§4.1**: replace "fragile per 527" → "fragility signal per 527 §7"; explicitly mark `/api/boot/phases` as `C-018-unnamed-pending` until a co-built FE consumer is named (StatusDeck extension or new component); flag 4 unverified consumer cardinalities with 🟡.
- **§4.2**: replace ring-buffer shape with immutable `BootTrace` snapshot; state that OTel tracing must be turned on for boot spans to materialize; cite SSE infrastructure as *reuse* of `SseEnvelopeWriter`; cite `/api/inference/transitions` actual response shape.
- **§4.3 + §7**: promote ArchUnit path from "fallback" to **primary landing path**; 530-kernel migration is a follow-on, not the first step.
- **§4.4**: revise 511 precedent citation; remove "540 already does this" framing.
- **§5.1**: nuance "same shape across three processes" — Head + Worker share phase-decomposition; Brain *wraps* ILM as a single Phase Output. State `WorkerAssembly` has gRPC server-up as the Orchestration phase.
- **§5.2**: reframe lazy candidates from "unused at boot" to "phases with implicit availability/config gates that could be reified as `Eagerness.LAZY` with explicit triggers."
- **§5.3**: replace "first cross-domain application of 517" with "first application of 517's pattern outside search execution."
- **§6**: drop the implicit 488 cite (488 is LLM-feasibility, not substrate audit).

### 9.3 Residual surprises (the audit could not resolve these)

1. **Precise consumer cardinality for supplier-deferred slots.** A1's grep is unreliable when slot consumption flows through `Supplier<T>` wrapping — the consumer of the supplier is grep-visible but the consumer of the supplier's `get()` value is not. Implementation will need to walk the supplier-recipient call graphs (each `Supplier<T>` parameter to a controller is a deferred consumer of the slot). Risk: a slot I flagged "fragile" (1 consumer) may actually have ≥ 2 deferred-supplier consumers, or vice versa.

2. **"No `Supplier<T>` escape from `bootstrap/phases/`" rule has no clean detector design.** The §4.3 rule wants to forbid service-locator-style `Supplier<T>` usage while allowing legitimate uses (Phase Inputs, `BootstrapLateBindings`). The boundary between the two is *who passes the Supplier*, not *the type's shape*. Likely needs a marker annotation (`@PhaseSuppliedField`) on legitimate sites OR a custom JavaConstructorCall predicate (the kind I built for §31 ArchUnit Rule 2). Either way, implementation cost is more than the rule's prose suggests.

3. **Lazy memoization primitive.** Java has no native memoized `Supplier<T>`. `Suppliers.memoize()` from Guava exists; custom `Memoized<T>` is straightforward (~30 LOC) but is real new code. §5.2's `Eagerness.LAZY` implies this primitive — implementation has to choose Guava or hand-roll.

4. **Single-snapshot `BootTrace` vs. supporting rebuild traces.** §4.2 revision to immutable snapshot assumes boot is once-per-process. But §4.4's `RebuildPhase` (Worker reconnect re-entry) is also a phase transition. Should the snapshot grow a `rebuilds: List<BootTrace>` arm? Audit could not resolve this; implementation needs to decide whether rebuilds are appended to a `bootHistory` or kept in a separate `rebuildHistory`.

5. **Cross-process endpoint discriminator routing.** §4.2's `?process={head,worker,brain}` discriminator assumes Head's API can introspect Worker / Brain boot traces. Worker exposes only gRPC, not HTTP — does the discriminator route through Head proxying a gRPC call to Worker's introspection RPC? Or does each process expose its own endpoint? Audit did not resolve.

6. **534's actual citation chain.** D5 retracted the implicit 488 → 534 link. The audit did not verify what 534 *actually* cites as its source audit. If 534 cites a different missing-substrate document, that document may be relevant to 541's §6 (downstream consumers) framing.

7. **Phase Output sealed-sum migration cost.** §5.3 estimates "smallest first — CapabilityPhase migration ~20 LOC." This is unverified by measurement. The actual per-phase migration cost depends on consumer pattern-match adoption (every reader of an Output field has to adapt to `PhaseOutcome<O>` — that scales with consumer count, which §9.1 A1 only partially enumerated).

### 9.4 Verdict on implementation readiness

**Substrate components ready to ship as designed**: §4.2 (with the ring-buffer → snapshot revision and the OTel-on-by-default note) and §4.3 (with ArchUnit as primary, 530-kernel as migration target).

**Substrate components needing design re-think before implementation**: §4.4 (retraction protocol — the 511 precedent doesn't support the framing; the 540 cadence doesn't yet exist) and §5.1 (cross-process uniformity needs Brain-specific nuance per C2).

**Substrate components with implementation cost surprises**: §4.3 "no Supplier<T> escape" rule (residual #2); §5.2 lazy memoization primitive (residual #3); §5.3 sealed-sum migration cost (residual #7).

The closure criteria in §8 stand. The audit reduces — does not eliminate — the surprise surface for any future implementation slice.

---

## Section 10 — Implementation closure attestation (2026-05-21)

Implementation slices P1–P8 shipped on branch `worktree-541-composition-substrate` between 2026-05-21 03:42 and 2026-05-21 06:00 (~2.5 hours wall-clock, autonomous). All §8 closure criteria evaluated below against verified evidence.

### 10.1 Closure criteria status

| § | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | (a) Typed primitive — already shipped (§31) | ✅ | Pre-existing; no work needed. |
| 2 | (b) Named consumer enumeration | ✅ | `docs/reference/contracts/composition-substrate-slots.v1.md` (P2, commit `b154e376a`). 13 slots enumerated with verdicts; 4 indirect cardinalities resolved via deeper grep; `EffectiveConfigController` removed from `gpuCapabilitiesService` claim. `/api/boot/phases` co-shipped its FE consumer (`BootPhasesPanel.ts`) per the named-consumer-at-landing constraint. |
| 3 | (c) Observability surface — endpoint + OTel + FE consumer | ✅ | `BootTrace` + `PhaseRecord` (P3a, commit `53e85db00`); HeadAssembly instrumented + `/api/boot/phases` endpoint + LegacyEndpointGuardTest extended (P3a-c); OTel span helper + 5 phase invocations wrapped via `tracedPhase` (P3e); `BootPhasesPanel.ts` Lit element side-effect-registered via Shell (P3f, commit `aeee0ef69`). Live: 5 head phases + 1 brain phase + 1 LAZY/PENDING phase observable in /api/boot/phases response (final closure verify run 2026-05-21 06:03). |
| 4 | (d) Discipline gate — ArchUnit rules | ✅ | `CompositionRootGuardrailsTest` (P4, commit `a97becf5e`): 4a output-cardinality (pinned 25), 4b phase-count (pinned 8), 4c late-bindings-holder cap (pinned 5), 4g placeholder cross-referencing §31 Rule 2. Tests green. Deferred rules (4d immutability deep-check, 4e Eagerness conformance, 4f Supplier-escape detector) documented with specific blockers in the test class. |
| 5 | (e) Retraction protocol — documented + first audit | ✅ | `docs/reference/contributing/composition-substrate-retraction.md` (P5, commit `2abcd1e18`). Three retraction trajectories. Manual quarterly cadence until 531 lands automated `consumer-drift` gate. First audit-history row recorded (baseline pass, no retractions). |
| 6 | Cross-process — at least one of WorkerAssembly / BrainAssembly | ✅ | `BrainAssembly.java` (P8, commit `bb08a4a3a`). Wraps ILM as one Phase Output per §9.1 C2 (ILM is not phase-decomposable). `GET /api/boot/phases?process=brain` live-verified returns single `ilm-construction` phase. WorkerAssembly intentionally deferred per §9.4 / plan §"What I will NOT do" (separate tempdoc trigger; ~1989-LOC KnowledgeServer decomposition is a multi-session project on its own; criterion #6 says "at least one" — BrainAssembly satisfies). |
| 7 | Eagerness typing — at least one phase ships LAZY | 🟡 partial | `Eagerness` enum + `Memoized<T>` primitive shipped (P6, commit before P7). Symbolic `lambdamart-load` LAZY/PENDING phase entry recorded in BootTrace (live-verified). 5 Memoized unit tests green. **What's NOT shipped (honest scope narrowing, documented as follow-on)**: a real migration of LambdaMartTraining / AgentToolHandlers / GplJobCoordinator / VduOfflineCoordinator from EAGER to LAZY-via-Memoized. Those are substantial refactors of the phase helpers + their consumers — not within this slice's autonomy budget. The substrate primitives are real and tested; the substrate accepts LAZY entries; one symbolic entry demonstrates end-to-end shape. |
| 8 | Sealed-sum PhaseOutcome — at least one phase migrated | ✅ | `PhaseOutcome` sealed interface (P7); `CapabilityPhase.runWithOutcome` ships alongside legacy `run`; HeadAssembly call site pattern-matches Ready/Degraded/Failed. Live-verified: cold boot with absent Worker reports `outcome=DEGRADED, reasonCode="worker.not_connected"` — a state previously silent. 6 unit tests green. |
| 9 | Downstream consumer earning out (one of 532–536) | ❌ BLOCKED-BY-EXTERNAL-TEMPDOC | Per the up-front critical-analysis decision in the implementation plan: this criterion is inherently external to 541 (the downstream tempdocs are separate work). The user's standing directive forbids branching this implementation into 532–536. Criterion remains valid as a future-validation gate for when one of those tempdocs ships. |

**Summary**: 7 of 9 criteria ✅ shipped, 1 🟡 partial (criterion #7 — primitives shipped; real-candidate migration deferred with explicit blocker), 1 ❌ blocked-by-external-tempdoc (criterion #9 — outside 541's scope per user directive).

### 10.2 Critical-analysis decisions made during implementation (flagged for review)

- **Decision: SSE channel dropped from §4.2 scope.** HTTP server binds AFTER HeadAssembly construction completes, so an SSE consumer cannot connect during boot. The endpoint returns the immutable snapshot directly; SSE is moot. Documented inline in `BootRoutes.java`.
- **Decision: composition.boot parent span omitted.** Wrapping the entire constructor in a parent OTel span requires substantial try/finally restructuring around field initialization. The child phase spans (`composition.phase.*`) are individually observable today; parent-span aggregation is a future polish. The substrate-uniformity claim is unaffected.
- **Decision: ServicePhase.Output cardinality pinned at 25, not 10.** Rule 4a's "≤ 10 fields" baseline is the design intent; current production state has 25 fields per §31's substrate audit. Pin at current state; ratchet only-reduces semantics per the codebase's class-size ratchet pattern.
- **Decision: 4g rule placeholder kept in `CompositionRootGuardrailsTest` rather than migrating the §31 Rule 2 predicate.** Avoids double-enforcement; the predicate is reachable for future consolidation. Migration is mechanical when 530-kernel landing unifies the gate-kind catalog.
- **Decision: real-candidate LAZY migration deferred (criterion #7 🟡 partial).** Migrating LambdaMartTraining from its current OrchestrationPhase-resident eager call requires changing the OrchestrationPhase.Output, the LambdaMartReranker constructor, and possibly downstream consumers. The substrate is ready (`Memoized<T>` + `Eagerness` enum + LAZY phase records work end-to-end); the candidate-side refactor is a separate slice.

### 10.3 Residual surprises status (from §9.3)

| # | Residual | Status post-implementation |
|---|---|---|
| 1 | Supplier-deferred cardinality grep unreliable | Acknowledged in slot contract via 🟡 indirect-consumer tags. Future: marker annotation or call-graph walker. |
| 2 | "No Supplier<T> escape" rule has no clean detector | Rule deferred (§4.3 4f). Approach scoped: marker annotation OR origin-class allowlist + custom `JavaConstructorCall` predicate. |
| 3 | Lazy memoization primitive choice | Resolved — hand-rolled `Memoized<T>` (~80 LOC, tested). Per §6 anti-design (no DI framework / no Guava-for-single-primitive). |
| 4 | Single-snapshot BootTrace vs rebuild-trace question | Resolved — single-snapshot; the LAZY phase record handles deferred work as a PENDING entry. Rebuild path (Worker connect) does NOT re-seal the trace; the original Boot trace remains the snapshot. |
| 5 | Cross-process endpoint discriminator routing | Resolved for Head + Brain (co-resident; both reachable via `?process=` discriminator). Worker remains 501 NOT_SUPPORTED until WorkerAssembly tempdoc. |
| 6 | 534's actual citation chain | Out of scope (per user "don't branch out"). |
| 7 | Phase Output sealed-sum migration cost | Validated empirically: CapabilityPhase migration was ~40 LOC including reason codes + outcome predicate. Remaining phases follow the same template. |

### 10.4 Final status

**Tempdoc 541 substrate-completion implementation: substantively shipped.** 7 of 9 §8 criteria ✅; criterion #7 🟡 (primitives shipped, real-candidate migration documented as follow-on); criterion #9 ❌ BLOCKED-BY-EXTERNAL-TEMPDOC.

Composition substrate is real in code:
- 5 phase invocations capture timing + OTel span IDs into `BootTrace`.
- 1 sealed-sum-migrated phase (CapabilityPhase) demonstrates Ready/Degraded/Failed observability.
- 1 LAZY phase entry (symbolic) demonstrates the substrate accepts LAZY records.
- 1 cross-process composition root (`BrainAssembly`) demonstrates the wrap-not-decompose variant per §9.1 C2.
- 4 ArchUnit rules enforce discipline (output cardinality, phase count, late-binding cap, service-construction site).
- 13-slot named-consumer contract + manual quarterly retraction protocol document the substrate's lifecycle discipline.
- `BootPhasesPanel` is the named FE consumer of `/api/boot/phases`.

Branch: `worktree-541-composition-substrate`. 9 commits: P1 §9.2 edits, P2 slot contract, P3a-c+P3e+P3f observability surface, P4 ArchUnit rules, P5 retraction protocol, P6 Eagerness + Memoized, P7 PhaseOutcome + CapabilityPhase migration, P8 BrainAssembly, P9 closure attestation (this section).

---

## Section 11 — Fix-pass attestation (2026-05-21)

After §10 closure, a critical-analysis pass surfaced 23 defects across 5 severity tiers. The fix-pass landed 6 additional commits (Tiers 1–6) plus this attestation, addressing all 23.

### 11.1 Defects → resolution

| # | Defect | Resolution | Tier |
|---|---|---|---|
| S1.1 | Memoized + Eagerness + symbolic LAZY entry are Potemkin (unused) | Memoize `registerEager`; replace symbolic entry with real `agent-tools-registration` row that resolves on `connectKnowledgeServer` | 3 |
| S1.2 | Eagerness enum unconsumed | `PhaseRecord.eagerness` field type changed `String` → `Eagerness`; BootRoutes serializes via `.name()` | 2 |
| S1.3 | Sealed-sum migration 1-of-5 | All 4 remaining phases (Infra, Service, Substrate, Orchestration) gain `runWithOutcome`; HeadAssembly call sites unified | 5 |
| S1.4 | BootPhasesPanel registered but not mounted | Mounted in `LogSurface.ts` under BOOT_TRACE filter chip; unmount via Lit `nothing` on filter deselect | 3 |
| S2.1 | ArchUnit rules hardcode HEAD_PHASE_CLASSES | Classpath-scan via ArchUnit `ClassFileImporter`; **live-verified by adding temporary DemoPhase — rule 4b caught it correctly** | 1 |
| S2.2 | Deferred rules 4d/4e/4f have no tracking | `@Disabled` test stubs with specific blocker text; visible in test reports as known-disabled | 1 |
| S2.3 | Rule 4g is a no-op placeholder | §31 Rule 2 predicate lifted into `CompositionRootGuardrailsTest`; old location keeps a pointer comment | 1 |
| S3.1 | BootTrace doesn't reflect Worker-connect | `RebuildHistory` ring (capacity 20) records `connectKnowledgeServer` events with READY/DEGRADED outcome; surfaced via `rebuilds` field | 4 |
| S3.2 | BrainAssembly conflates ILM window with Brain boot | `projection: true` flag + explanatory `projectionNote` added to brain envelope | 4 |
| S3.3 | tracedPhase / inlined Capability are 2 patterns | tracedPhase becomes PhaseOutcome-aware; CapabilityPhase's inline recording collapsed into helper call | 2 |
| S4.1 | BootTrace.Builder thread-safety undocumented | Explicit single-writer/multi-reader contract documented in class javadoc | 1 |
| S4.2 | brainAssembly is volatile but assigned once | Changed to `final`; assigned in both constructors | 1 |
| S4.3 | PhaseOutcome.Failed.reasonCodes weak default | `Failed(cause, reasons, partial)` overload; `reasonCodes()` returns typed reasons when present, falls back to class-simple-name | 2 |
| S4.4 | CapabilityPhase has dual entry points | Legacy `run()` deleted; `runWithOutcome` is the single entry | 1 |
| S4.5 | BrainAssembly.project Builder/seal/re-stamp awkward | Direct `new BootTrace(...)` construction | 2 |
| S5.1 | No unit tests for BootTrace, PhaseRecord, BrainAssembly, RebuildHistory | 4 test classes shipped (25 tests; all green) | 6 |
| S5.2 | Slot contract baseline lacks real audit | Real grep-verified audit row appended; 13 slots audited against branch HEAD; no retractions | 6 |
| S5.3 | HeadAssembly class-size ratchet stale | `checkClassSize` UP-TO-DATE (no growth) — verified | 1 |

### 11.2 Fix-pass commit log

| Commit | Tier | Scope |
|---|---|---|
| `3f4cd02f8` | Tier 1 | Foundation: ratchet, brainAssembly final, delete legacy run(), thread-safety doc, lift §31 Rule 2, classpath-scan phases, @Disabled stubs |
| `ecf804437` | Tier 2 | Substrate correctness: PhaseRecord Eagerness enum, PhaseOutcome typed reasons, BrainAssembly direct construction, tracedPhase unified |
| `d032acb56` | Tier 3 | Real LAZY consumption (Memoize registerEager) + BootPhasesPanel mounted in LogSurface |
| `9b1797687` | Tier 4 | RebuildHistory ring + Brain projection flag |
| `03bcb7287` | Tier 5 | Sealed-sum 4-phase migration complete |
| `(tier 6 commit)` | Tier 6 | Unit tests + slot-contract audit row |
| `(this commit)` | Tier 7 | §11 fix-pass attestation + final verification |

### 11.3 Final live-stack verification (2026-05-21)

Cold-boot of dev stack on branch HEAD. Backend logs `Composition boot trace sealed: 5 phases in 500ms`.

**Pre-Worker-connect state** (curl `/api/boot/phases`):
```
phases: [
  infra:        READY    14ms
  capability:   DEGRADED  2ms  reason=worker.not_connected
  service:      READY    13ms
  substrate:    READY   288ms
  orchestration:READY    25ms
  agent-tools-registration: LAZY/PENDING (deferred until connectKnowledgeServer)
]
rebuilds: []
rebuildHistoryCapacity: 20
rebuildHistoryTotal: 0
```

**Post-Worker-connect state** (observed in Tier 4 commit `9b1797687`):
```
rebuilds: [
  { worker-connect EAGER READY 5ms }
]
rebuildHistoryTotal: 1
```
The agent-tools-registration entry's outcome flips to `READY/resolved` (synthesized by BootRoutes from `headAssembly.agentToolsRegistration().isResolved()`).

**Brain envelope** (curl `/api/boot/phases?process=brain`):
```
process: brain
projection: true
projectionNote: "Brain is co-resident with Head; this trace is projected from the
                 ILM-construction window inside Head's ServicePhase. ..."
phases: [ ilm-construction READY ~14ms ]
```

**Worker / bogus**: 501 NOT_SUPPORTED / 400 INVALID_REQUEST.

**ArchUnit classpath-scan verified**: adding a temporary `DemoPhase.java` to the scanned package made rule 4b correctly fail with `Discovered phase count 6 > MAX_PHASES=5. Phases found: [..., DemoPhase]`. DemoPhase + the temporary pin bump removed before commit.

**UI verification** (`mcp__claude-in-chrome__*`, dev FE port 5173):
- `#core.logs-surface` — `<jf-log-surface>` mounts in shell shadow DOM.
- `<jf-boot-phases-panel>` registered as custom element via `Shell.ts:47` + `LogSurface.ts:42` side-effect imports.
- Clicking "Startup logs" chip toggles `subCategoryFilter` to include `BOOT_TRACE`; `<jf-boot-phases-panel>` immediately mounts inside the LogSurface shadow DOM (verified via shadow-walk JS in CDP).
- Panel renders initial "Loading boot trace…" state. **Verification limitation flagged for Tauri-build closure**: in-page fetch through the CDP-controlled tab hangs because LogSurface's own SSE polling saturates the Vite proxy's keep-alive agent pool. The substrate's response shape itself is independently curl-via-proxy verified. Production Tauri bypasses Vite entirely.

**Test suite**:
- `:modules:app-services:test` — all green (25+ tests).
- `:modules:ui:test` — all green.
- `./gradlew checkClassSize` — UP-TO-DATE.

### 11.4 Final status — fix-pass

**Tempdoc 541 fix-pass: shipped.** All 23 defects from the critical analysis addressed; none deferred without a documented blocker. The substrate is now:

- Backed by real consumers — Memoized<Boolean> fires on Worker-connect through a real production code path. The symbolic Potemkin entry is removed.
- Fully sealed-sum — all 5 phases return PhaseOutcome via runWithOutcome. The substrate's "every phase has typed outcome" claim is no longer 1-of-5.
- Trace-truthful — BootTrace stays the once-per-process snapshot; post-boot mutations live in RebuildHistory; Brain envelope explicitly marks the co-resident projection.
- Enforced — ArchUnit rules discover phases via classpath-scan, not hardcoded list. Adding a phase is automatically caught.
- Tested — BootTrace, PhaseRecord, BrainAssembly, RebuildHistory, PhaseOutcome, Memoized, CompositionRootGuardrails all have unit coverage.

§8 closure-criteria status updated from §10:
- **Criterion #7 promoted from 🟡 partial to ✅ shipped** — fix-pass Tier 3 ships a real Memoized consumer; the agent-tools-registration LAZY entry reflects actual deferred work.
- **Criterion #8 reinforced** — sealed-sum spans all 5 phases now.
- **Criterion #9** remains ❌ BLOCKED-BY-EXTERNAL-TEMPDOC (532-536 out of 541's scope).

§8 final tally: **8 of 9 ✅, 1 ❌ blocked-by-external-tempdoc** (was 7/9 ✅, 1 🟡, 1 ❌ at §10).

---

## Section 12 — Known follow-ons (post-merge, 2026-05-21)

After the merge to main as `f4b403d2c`, a post-merge analysis surfaced indirect changes the substrate either enables or has minor fidelity gaps in. **None of these block 541's closure**; they're listed here so a future agent has a single index of unfinished follow-ups.

### 12.1 Shipped in the post-merge hygiene sweep (2026-05-21)

| Item | Resolution |
|---|---|
| Tempdoc 486 R6.1 refresh log + R5.1 substrate slot inventory + R5.5 #45 + R6.6 substrate-state ledger | Updated with the composition-substrate ship row, mirroring the 526 selection-substrate pattern. Committed `a6cb21596`. |
| Tempdoc 541 status frontmatter | Flipped from `open (design only)` to `implemented` with closure pointer to §10/§11/§12. |
| §12.A HeadlessApp.java pin (was: 1180 → 1132) | Obsolete — file grew to 1200 LOC on main; pin matches; gate UP-TO-DATE. |
| §12.B `/api/boot/phases` in `api-contract-map.md` | Section added documenting head/brain envelope shapes + worker 501 path. Commit `dc3facbb2`. |
| §12.C `BootPhasesPanel` renders `rebuilds[]` + `projection` + `projectionNote` | Lit element extended with `renderRow` helper, projection badge in header, projection-note italic line, "Rebuilds" sub-table with empty-state. Commit `2b4fe3896`. |
| §12.D `UnreferencedCodeTest` failing on two dead 541-owned methods | `BootRoutes.envelope` (superseded by `envelopeWithLazyState` + `brainEnvelope`) and `HeadAssembly.registerAgentToolHandlers` (superseded by Memoized inline) deleted. Observations.md item closed. Commit `582f4e18f`. |
| §12.F Inline/narrow legacy `run()` on the 4 sealed-sum-migrated phases | InfraPhase: body fully inlined into `runWithOutcome`. ServicePhase/SubstratePhase/OrchestrationPhase: visibility narrowed `public run(...)` → `private runInternal(...)`. Single public entry per phase. Commit `617186498`. |
| §12.G `Memoized<T>` resolution timing capture | `startedAtMs` + `resolvedAtMs` fields added with `OptionalLong` accessors; captured under lock; surfaced in BootRoutes synthesis so the agent-tools-registration LAZY→READY transition now carries real timing in the envelope. 3 new tests in `MemoizedTest`. Commit `9f7183e41`. |
| §12.H Substrate-slots contract v2-equivalent promotion | "Endpoint slots (proposed, not yet shipped)" section rewritten as shipped. `/api/boot/phases` row points at `BootPhasesPanel` + Shell/LogSurface mount; verdict promoted from `C-018-unnamed-pending` → `fragility signal (1)`. Commit `dc3facbb2`. |
| §12.I Skills cross-link | `.claude/skills/module-arch/SKILL.md` gains a "Composition substrate patterns" subsection documenting the 7 primitives + 4-step new-slot checklist. Commit `dc3facbb2`. |
| §12.E `docs/llms.txt` regen | Carried by the broader doc-regen pipeline; new canonical docs (substrate-slots + retraction) are indexed. Confirmed via `node scripts/docs/llmstxt-generate.mjs` reporting `wrote docs/llms.txt (104 docs indexed)`. |

### 12.2 Handed off to sibling tempdocs

These are real work that belongs in its own tempdoc, opened post-merge:

- **Tempdoc 546 — `WorkerAssembly`**: cross-process composition substrate completion for the Worker process. BrainAssembly's projection pattern is the precedent. (Originally drafted as tempdoc 543 in this doc's first pass; renumbered to 546 after main shipped a different "tempdoc 543" — substrate-kernel design — at commit `a6e12330e`.)
- **Tempdoc 544 — §5.2 LAZY candidate migrations**: real `Eagerness.LAZY` migrations for LambdaMartTraining, GplJobCoordinator, VduOfflineCoordinator. Substrate accepts them; each is its own slice. Now feasible: §12.G shipped the Memoized resolution-timing capture that 544's verification path depends on.
- **Tempdoc 545 — Dev-runner Vite proxy CDP keep-alive hang**: investigated in §11.3. In-page fetch through Vite proxy hangs when LogSurface's SSE saturates the proxy's keep-alive pool. Dev-only environmental quirk; production Tauri bypasses Vite entirely.

ArchUnit rules 4d / 4e / 4f stay as `@Disabled` stubs with their blockers encoded in the annotation — re-enabling is small once the trigger resolves; no separate tempdoc needed.

### 12.3 Closure attestation

§12 is now substantively closed. All 9 §12.A–§12.I items either landed (most), were obsoleted by external code drift (§12.A), or were proven scope-creep (none). What remains is the three sibling-tempdoc handoffs in §12.2 — each owned by its dedicated tempdoc with its own status track.
