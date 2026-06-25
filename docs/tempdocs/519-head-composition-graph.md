---
title: "519 — Head composition root: declarative phase-typed wiring graph"
---

# 519 — Head composition root: declarative phase-typed wiring graph

**Date**: 2026-05-18
**Status**: open
**Source paths**: `modules/app-services/src/main/java/io/justsearch/app/services/AppFacadeBootstrap.java` (the subject); `modules/app-services/src/main/java/io/justsearch/app/services/{bootstrap,lifecycle}/` (the existing partial substrate)
**Related**:
- Replaces a deleted `515-appfacadebootstrap-size.md` (issue-description-only).
  515 was later reassigned to `515-branching-correctness-fixes.md` during the same
  session.
- Tempdoc 502 (`502-boot-composition-architecture.md`, status: Complete) shipped the
  behavioral half of the inversion this design completes.
- Tempdoc 512 §B3 (`512-codebase-investigation-and-critique.md`) is the audit that
  surfaced the mega-class concentration.
- Tempdocs 517 (`517-searchorchestrator-size.md`) and 518
  (`518-inferencelifecyclemanager-size.md`) are the parallel issue-descriptions for
  the two remaining mega-classes. Section 14 below sketches how the principle here
  generalizes to them.
- `docs/reference/contributing/class-size-standard.md` — the 1000-LOC ceiling and the
  table of eleven prior successful decompositions.
- ADR-0017 (`docs/decisions/0017-ai-bridge-module-decomposition.md`) — the
  `InferenceLifecycleManager` decomposition precedent in the same module.

---

## Section 1 — What this tempdoc is

A **long-term design** for the Head process's composition root. Not a slice. Not a
step-by-step decomposition plan. No timeline. The framing here is "correct structure"
as the user requested — feasibility, short-term fixes, and intermediate states are
explicitly out of scope.

The audit context (tempdoc 512 §B3) and the prior structural inversion (tempdoc 502)
together establish that `AppFacadeBootstrap.java` is the largest single concentration
of complexity in the codebase (2,823 LOC, 2.8× the 1,000-LOC ceiling) **and** that
the codebase has already proven the decomposition pattern works — eleven prior
decompositions landed under the ceiling using the package-private collaborator
pattern. What is missing is the design for a composition root specifically, because
it has structural properties (every service has to be wired somewhere; the wiring
order is load-bearing; lifecycle teardown is reverse-order) that the prior eleven
decompositions did not face.

## Section 2 — Diagnosis: four conflated concern-classes

Post-502, `AppFacadeBootstrap` is doing four kinds of work that the type system has
not yet separated. Each is a different shape of problem. Treating the class as
"too long" hides the actual structural defect, which is conflation.

| Concern | Examples in the file | Right home |
|---|---|---|
| **Composition** — constructing and chaining services | constructor body L316–L649; `createInferenceManager`, `createDocumentService`, `createOfflineCoordinator`; the 8-service `LateBoundServices` capture | Phase-typed composition graph (Section 4) |
| **Lifecycle** — start, connect-when-worker-ready, close | `connectKnowledgeServer`, `close`, `tryStartOnlineMode`; the implicit construction-order / reverse-teardown contract | Typed lifecycle stack (Section 8) |
| **Domain orchestration** — kick off long-running background behavior | `startGplAutoTrigger`, `startLambdaMartTrainingAsync`, `wireGpuStatusBroadcast`, `captureGplEvalSnapshot`; the async watcher facet fetch | Dedicated orchestration startup phase (Section 7) |
| **Substrate hosting** — Resource catalogs, advisory registries, intent routers | `operationCatalog`, `agentToolsCatalog`, `resourceCatalog`, `runtimeContextResourceCatalog`, `serverCapabilitiesResourceCatalog`, `operationHistoryResourceCatalog`, `intentSourceCatalog`, `backendIntentRouter`, `indexingJobsResourceCatalog`; Slice 494 advisory class/change/log registries | Substrate graph record (Section 6) |

The composition concern is irreducibly central — every service has to be wired
somewhere — but it is roughly 30–40% of the file. The other three concern-classes have
leaked in and should not be there.

## Section 3 — Principle: declarative structure as the long-term shape

The recurring shape that produced every mega-class in this codebase
(`AppFacadeBootstrap` 2,823; `IndexingLoop` 1,955; `SearchOrchestrator` 1,919;
`InferenceLifecycleManager` was 2,333 before its decomposition) is the same:
**a class became big because its work was a procedural sequence inside one method or
file.** The class is the only place that knows the whole sequence. New work joins the
sequence.

The correct long-term shape moves the sequence into **typed data**:

- For a composition root: **phase records** chained left-to-right, each phase a
  static function from its inputs to its output record.
- For a state machine (`IndexingLoop`): **state classes** that own their transitions,
  with a thin driver that sequences them.
- For a policy tree (`SearchOrchestrator`): **strategy classes** per mode, with a thin
  dispatcher that selects.

In all three the principle is identical: the procedural sequence becomes a graph of
typed values, and the original class becomes a thin orchestrator over the graph. The
graph is what changes when new work is added; the orchestrator stays small.

This design specifies the composition-root variant. The state-machine and policy-tree
variants belong in a future design tempdoc that picks up where 517 / 518 currently sit
as issue-descriptions. Their structure shares the principle but not the details.

## Section 4 — Design: phase-typed composition graph

The composition root becomes a sequence of typed phases, each phase a static function
in its own file, each returning an immutable result record. The bootstrap class
becomes a small **sequencer** that:

1. calls phase functions left-to-right,
2. holds the chain of result records,
3. exposes accessors that delegate to the records,
4. tears down in LIFO order via the records' `AutoCloseable` contracts.

The phase chain (the design endpoint, not the order of any decomposition slice):

```
Phase 0 — ConfigPhase        : (env, args)                          → ConfigContext
Phase 1 — InfraPhase         : ConfigContext                        → InfraContext
Phase 2 — CapabilityPhase    : InfraContext                         → CapabilityGraph
Phase 3 — ServicePhase       : (Config, Infra, Capability)          → ServiceGraph
Phase 4 — SubstratePhase     : (Service, Infra)                     → SubstrateGraph
Phase 5 — OrchestrationPhase : (Service, Substrate, Infra)          → OrchestrationHandles
Phase 6 — ApiPhase           : (Service, Substrate, Orchestration)  → ApiServer
Phase 7 — ConnectPhase       : ApiServer                            → terminal (async)
```

### What already exists to build on

- `modules/app-services/.../lifecycle/ConfigContext.java` — Phase 0 output record.
- `modules/app-services/.../lifecycle/InfraContext.java` — Phase 1 output record.
- `modules/app-services/.../lifecycle/{WorkerCapability,InferenceCapability,LifecycleProjection}.java`
  — Phase 2 substrate (the capability model is the primary state source per 502).
- `modules/app-services/.../bootstrap/{BootstrapCapabilitiesFactory,BootstrapFlagResolver,BootstrapInferenceFactory}.java`
  — partial factory extraction. Precedent for per-phase factories.
- `modules/app-api/.../LateBoundServices.java` — 8-service typed record. Precedent
  for the typed-service-graph pattern that replaces the `AppFacade` locator.
- `modules/ui/.../HeadlessApp.java` — already a phase orchestrator for Phases 0–3 of
  the *main entry point*. This design extends the same pattern *inside*
  `AppFacadeBootstrap`.

Each phase is a static method (or a small final class with one method) in its own
file under `modules/app-services/.../bootstrap/phases/`. Phase functions take their
input records and return their output record. Phase functions never see the bootstrap
class. They are independently testable by constructing input records (mocked where
useful) and asserting on outputs.

## Section 5 — Design: typed service graphs replace AppFacade

The `AppFacade` interface (`modules/app-api/.../AppFacade.java`, 67 LOC, 6 methods
after 502 D4) is the last residue of the service-locator. Five of its six methods
(`indexing`, `documents`, `onlineAi`, `agent`, `inferenceSnapshot`) still default to
sentinel `.unavailable()` factories. The sixth (`search(SearchRequest)`) is real
business logic that has been hosted on the locator because there was no other home.

The correct long-term shape eliminates the interface entirely:

```java
record CoreServices(SettingsService settings, PolicyService policy,
                    DiagnosticsService diagnostics, AgentService agent) {}

record WorkerServices(IndexingService indexing, DocumentService documents,
                      ExcludesService excludes, WorkerService worker,
                      SearchService search) {}

record InferenceServices(OnlineAiService onlineAi,
                         BrainRuntimeService brainRuntime,
                         RuntimeVariantService runtimeVariant,
                         PackImportService packImport,
                         BrainInstallService brainInstall) {}
```

The `search(SearchRequest) → SearchResponse` method moves to a new `SearchService`
class in `modules/app-services` that wraps `SearchPort` and owns the `toCoreQuery` /
`toApiResponse` mapping currently inline at L1726 / L1758. `SearchService` lives in
`WorkerServices` because it requires `SearchPort` to be reachable.

Consumers (controllers, operation handlers, SSE channels) receive the specific record
they need at construction time — not a locator. The five remaining `.unavailable()`
sentinel factories (per 502 §6.5: `IndexingService`, `DocumentService`,
`OnlineAiService`, `WorkerService`, `AgentService`) are deleted; the capability gates
already prevent unavailable-state dispatch.

`AppFacade` and `LateBoundServices` are both subsumed by these three records.
`LateBoundServices` was a transitional record (502 C1 / C3); under this design it has
no distinct identity — its eight services are split across `CoreServices` and
`InferenceServices` above. The bootstrap class exposes the three records as accessors
(`core()`, `workers()`, `inference()`), and that is the entire shape it exposes.

## Section 6 — Design: substrate graph

The 10+ Resource catalogs, advisory registries, intent routers, and operation
catalogs hosted on `AppFacadeBootstrap` accessors (`operationCatalog`,
`agentToolsCatalog`, `resourceCatalog`, the four `*ResourceCatalog` variants,
`intentSourceCatalog`, `backendIntentRouter`, `indexingJobsResourceCatalog`; plus the
Slice 494 advisory registries) are not composition concerns. They are **substrate** —
the catalog / registry infrastructure that the composition root happens to construct.

Group them into a `SubstrateGraph` record produced by Phase 4:

```java
record SubstrateGraph(
    OperationCatalog operations,
    OperationCatalog agentTools,
    ResourceCatalog resources,
    ResourceCatalog runtimeContext,
    ResourceCatalog serverCapabilities,
    ResourceCatalog operationHistory,
    ResourceCatalog indexingJobs,
    ResourceCatalog advisories,
    IntentSourceCatalog intentSources,
    BackendIntentRouter intentRouter,
    AdvisoryClassRegistry advisoryClasses,
    AdvisoryChangeRegistry advisoryChanges,
    AdvisoryLogRegistry advisoryLogs) {}
```

Handlers and controllers that need substrate (operation dispatch, Resource SSE
channels, advisory projection) receive the `SubstrateGraph` or specific catalogs from
it. The bootstrap accessor surface drops from 15+ catalog methods to one `substrate()`
accessor.

The catalog *contents* (which operations, which Resources, which advisory classes)
are unchanged — they are constructor inputs to `SubstratePhase`. Only the *plumbing*
moves.

## Section 7 — Design: orchestration startup phase

The bootstrap currently kicks off six pieces of background behavior:

- `startGplAutoTrigger` — periodic GPL revalidation
- `startLambdaMartTrainingAsync` — initial LM training
- `tryStartOnlineMode` — attempt to start llama-server
- `wireGpuStatusBroadcast` — install inference→GPU-status listener
- `captureGplEvalSnapshot` — initial GPL eval snapshot
- async watcher facet fetch — populate initial mime facets

These are **behavior**, not composition. They depend on `ServiceGraph` +
`SubstrateGraph` + `InfraContext`. They produce teardown handles (background threads,
listener registrations).

Group them in a Phase 5 `OrchestrationStartup` function that returns an
`OrchestrationHandles` record:

```java
record OrchestrationHandles(
    AutoCloseable gplAutoTrigger,
    AutoCloseable lambdaMartTraining,
    AutoCloseable inferenceModeListener,
    AutoCloseable initialFacetFetch) implements AutoCloseable { ... }
```

Each handle is independently testable. Each can be disabled per environment (tests
start without auto-trigger or training). The bootstrap's `close()` no longer has to
know any of this — it closes the `OrchestrationHandles` and the rest is structural.

## Section 8 — Design: lifecycle as a typed stack

Today, `close()` (L1422) is implicit — services are torn down in a specific order
encoded in the method body. There is no compile-time guarantee that construction
order matches teardown reverse-order. New services added at the bottom of the
constructor must remember to be added to the top of `close()`.

The correct shape: each phase result record implements `AutoCloseable`. The bootstrap
holds the phase results in a list (or chain). `close()` is one line:

```java
@Override
public void close() {
  for (int i = phases.size() - 1; i >= 0; i--) phases.get(i).close();
}
```

LIFO order is structural — the list order is the construction order, and the loop
reverses it. The bootstrap cannot get teardown order wrong.

A phase whose record holds five resources owns its own teardown ordering inside its
record. This is the same pattern as the per-class `LuceneIndexRuntime` collaborators
managing their own resources.

## Section 9 — Design: module-boundary inversion

502 §6.3 documented that the controller-as-service circular dependency is a
**module-graph defect**, not code coupling: the 8 services (`SettingsService`,
`ExcludesService`, `BrainInstallService`, `BrainRuntimeService`, `PackImportService`,
`PolicyService`, `DiagnosticsService`, `RuntimeVariantService`) have implementations
that live in `modules/ui` because the controllers in `ui` implement them.
`app-services` cannot construct them because that would invert the module dependency.
The `LateBoundServices` record (502 C1) papered over this — services are passed
*back* from `ui` to `app-services` after `ui` constructs them.

The correct long-term shape inverts this:

- Service **implementations** (zero HTTP coupling per 502 §2 Layer 2 investigation)
  move from `modules/ui` to `modules/app-services`.
- Controllers in `modules/ui` become HTTP-only — receive a service via constructor,
  delegate HTTP requests, format responses.
- `AppFacadeBootstrap`'s Phase 3 (`ServicePhase`) constructs all services directly.
- The late-bind setter pattern disappears entirely. `LateBoundServices` as a
  transitional record disappears with it.

This is the work 502 deferred. It is structural (module-graph reshape) and unblocks
the cleanest version of the phase-typed graph above.

## Section 10 — Bootstrap class shape under this design

`AppFacadeBootstrap.java` becomes a small sequencer. Approximate shape:

```java
public final class AppFacadeBootstrap implements AutoCloseable {
  private final ConfigContext        configContext;
  private final InfraContext         infraContext;
  private final CapabilityGraph      capabilities;
  private final ServiceGraph         services;
  private final SubstrateGraph       substrate;
  private final OrchestrationHandles orchestration;

  public AppFacadeBootstrap(Telemetry telemetry,
                            ConfigManagerBootstrap cfg,
                            KnowledgeServerBootstrap ks) {
    this.configContext = ConfigPhase.run(cfg);
    this.infraContext  = InfraPhase.run(configContext, telemetry);
    this.capabilities  = CapabilityPhase.run(infraContext, ks);
    this.services      = ServicePhase.run(configContext, infraContext, capabilities);
    this.substrate     = SubstratePhase.run(services, infraContext);
    this.orchestration = OrchestrationPhase.run(services, substrate, infraContext);
  }

  public CoreServices       core()         { return services.core(); }
  public WorkerServices     workers()      { return services.worker(); }
  public InferenceServices  inference()    { return services.inference(); }
  public SubstrateGraph     substrate()    { return substrate; }
  public CapabilityGraph    capabilities() { return capabilities; }
  public InfraContext       infra()        { return infraContext; }

  @Override public void close() { /* LIFO close of held phase records */ }
}
```

No business methods. No domain helpers. No catalog construction. No async kickoff.
No sentinels. No setter methods. No `AppFacade` interface. Estimated size: ~150–250
LOC.

The 2,823 LOC moves into:

- `bootstrap/phases/ConfigPhase.java` (already exists in spirit as `ConfigContext` +
  HeadlessApp phase 0)
- `bootstrap/phases/InfraPhase.java` (HeadlessApp phase 1)
- `bootstrap/phases/CapabilityPhase.java`
- `bootstrap/phases/ServicePhase.java`
- `bootstrap/phases/SubstratePhase.java`
- `bootstrap/phases/OrchestrationPhase.java`
- The existing
  `bootstrap/{BootstrapCapabilitiesFactory,BootstrapFlagResolver,BootstrapInferenceFactory}.java`
  fold into the appropriate phase, or remain as the phase's collaborators.
- `app-services/.../search/SearchService.java` (extracts `toCoreQuery` +
  `toApiResponse`)
- The 8 controller-services move from `modules/ui` to `modules/app-services` (per
  Section 9).

Each new file is well under the 1,000-LOC ceiling.

## Section 11 — Class-size enforcement as the structural feedback loop

The class-size standard (`docs/reference/contributing/class-size-standard.md`) is
documented but not enforced. Tempdoc 512 §B5 identified this gap. The reason no
ArchUnit rule exists today is that adding one would fail the build immediately on
the four mega-classes.

Under this design, an ArchUnit (or Gradle source-set) rule lands with a **burn-down
allowlist**:

```
@Test void noClassExceedsCeiling() {
  // 1000 LOC hard ceiling. Allowlist below is irreversible-burn-down:
  // a file removed from this list cannot be added back. New violators fail build.
  classes()
    .that().areNotIn(ALLOWLIST)
    .should().notExceedLineCount(1000);
}

ALLOWLIST = {
  "AppFacadeBootstrap.java",         // pending decomposition (this tempdoc)
  "SearchOrchestrator.java",         // pending decomposition (tempdoc 517)
  "InferenceLifecycleManager.java",  // pending re-decomposition (tempdoc 518)
};
```

This is the same pattern as `AppServicesWorkerGuardrailsTest`'s env-read allowlist: a
documented intent-to-migrate, with the rule enforcing direction-of-travel. Each
decomposition slice removes one row. Adding a line that pushes a removed file back
over 1,000 fails the build — irreversible.

The rule should land **before** any decomposition begins. Pre-shipped, it codifies
the ceiling and the known violators in one artifact. Post-shipped after a
decomposition, it would just be one more thing to remember.

## Section 12 — Substrate-without-consumer (C-018) integration

The C-018 rule from `.claude/rules/agent-lessons.md` and `docs/agent-postmortems.md`
("substrate must name a consumer slice or don't ship the slot") applies inside this
design:

- Each phase output record must have ≥1 consumer (the next phase, plus accessors on
  the bootstrap, plus its phase function's test).
- Each typed service record must have ≥1 consumer (a controller, handler, or
  substrate reader).
- A phase function whose output isn't read by anything downstream is C-018-flagged
  before the phase is added.

The slice-447 §X.11.2 refinement applies: this is a type-system refactor
(relabeling existing fields) rather than a new-substrate slot, so C-018 does not
block it. Every field that today lives on `AppFacadeBootstrap` already has a
consumer; this design relocates the fields without inventing new ones — with one
exception: `SearchService` is genuinely new, and its consumer is the search HTTP
route that today calls `appFacade.search(req)` inline.

## Section 13 — Verification design

This is what "correct" looks like in tests, not what the implementation steps are:

- **Per-phase unit tests.** Each phase function is testable in isolation by
  constructing its input record. `ConfigPhase`, `InfraPhase`, etc. each get a test
  class. No need to spin up the entire facade.
- **Composition smoke test.** One test that runs all phases end-to-end with
  realistic inputs and asserts on the final `AppFacadeBootstrap` accessor surface.
  Replaces the current integration-only smoke.
- **Lifecycle test.** Construct, assert all `AutoCloseable` handles are reachable,
  call `close()`, assert all handles are closed. The LIFO contract is checked by
  mock ordering.
- **Module-graph test.** ArchUnit rule: `app-services` does not depend on `ui`.
  After Section 9's inversion, this lands and stays green.
- **Class-size test.** The ArchUnit rule from Section 11.
- **Live-stack verification (slice 447 Tier 3).** Run `jseval --start-backend` and
  verify all API surfaces work end-to-end. The bootstrap shape is user-visible only
  through behavior, but the behavior must not regress.

## Section 14 — Generalization to the other mega-classes

The principle in Section 3 applies to the two remaining mega-class
issue-descriptions:

- **`SearchOrchestrator`** (1,919 LOC, tempdoc 517) — policy decision tree.
  Decomposition target: per-mode strategy classes (`TextSearchStrategy`,
  `HybridSearchStrategy`, `ChunkSearchStrategy`) plus a thin `SearchPolicyDispatcher`
  that selects based on query mode + capabilities.
- **`InferenceLifecycleManager`** (1,486 LOC, tempdoc 518) — already decomposed once
  under ADR-0017 (2,333 → 915 LOC with 4 collaborators) but has since grown back over
  the ceiling. The same lifecycle/state-class pattern from ADR-0017 applies; the
  decomposition has the precedent built in.
- **`IndexingLoop`** (1,955 LOC) was the fourth mega-class in tempdoc 512 §B3. It no
  longer has a dedicated issue-description tempdoc as of this session's renumbering.
  Its decomposition target — state-class machine driven by a thin loop — is sketched
  in Section 3 above for reference, even though no tempdoc currently holds it.

The class-size allowlist in Section 11 holds the three remaining offenders. As each
is decomposed, its row drops out.

## Section 15 — What this tempdoc is *not*

- **Not a slice.** No timeline, no phase ordering, no acceptance criteria. The user
  framed this as a design exercise.
- **Not a commitment to do the work.** It is a design that can be picked up by a
  future slice when the user decides to.
- **Not a verdict on whether 502 should have done this work.** 502 stopped at the
  behavioral inversion. That was a reasonable scope. This design completes the
  structural half.
- **Not feasibility-constrained.** Module-graph inversion (Section 9) is real work,
  but the user explicitly said to disregard feasibility and prefer the correct
  long-term shape. The same caveat applies to the `AppFacade` dissolution (Section
  5) and the class-size enforcement-with-allowlist (Section 11).
- **Not a step-by-step plan.** A future slice picking up this design would need to
  sequence the work: Section 11's allowlist rule can land first (cheap, codifies
  ceiling); Section 9's module inversion is the prerequisite for the cleanest
  `ServicePhase`; Sections 4–8 land per-phase. The order is a slice concern, not a
  design concern.

## Section 16 — Open design questions for the next conversation

1. **`SearchService`'s home.** It needs `SearchPort`. Today `SearchPort` is in
   `core` and `RemoteKnowledgeClient` (which implements `SearchPort` via gRPC) is
   in `app-services`. `SearchService` can live in `app-services` and receive
   `SearchPort` by construction. Confirm.
2. **`InferenceServices.onlineAi` vs `CoreServices.agent`.** `AgentService` depends
   on `OnlineAiService` for inference; `OnlineAiService` is in `InferenceServices`.
   Either `AgentService` moves to `InferenceServices`, or it accepts a
   `Supplier<OnlineAiService>` to keep the record split clean. Pick.
3. **`SubstrateGraph` granularity.** A single record with 13 catalogs vs three
   sub-records (Operations / Resources / Advisory). Smaller records compose better
   but add one indirection on every read. Decide.
4. **Constructor parameter surface.**
   `AppFacadeBootstrap(Telemetry, ConfigManagerBootstrap, KnowledgeServerBootstrap)`
   is the current shape. Under this design, should the constructor take only
   `(Config, Telemetry)` and resolve `ConfigManagerBootstrap` +
   `KnowledgeServerBootstrap` inside phases? Or keep the current shape and have
   phases consume them via input records? Affects how `HeadlessApp` calls
   `new AppFacadeBootstrap`.
5. **Test constructor (`AppFacadeBootstrap(SearchPort, Telemetry)` at L1082).**
   Under the new shape this becomes "construct a `ServiceGraph` with only
   `SearchService` wired and a `NoopSearchPort` everywhere else, skip Phases 4–6."
   Confirm this is the right test seam, or design a different test factory.
6. **`AppFacade` interface stability.** It is marked `Stability: stable` in its
   javadoc. Removing it is a binary-incompatible change to consumers outside this
   repo (if any). Confirm the interface has no external consumers — `app-launcher`
   and `ui` are the only known callers, both in-repo.

## Section 18 — Investigation findings (2026-05-18, post-design)

A pre-implementation confidence-increasing investigation pass was run against this
design after it was first drafted. Eleven targeted investigations (I1–I11) examined
the high- and medium-risk claims. Findings below are tagged with their investigation
ID and update specific sections without rewriting the design framing.

Methodology: Sections 1–17 above are the design. This appendix is the empirical
audit. A future agent reads §1–§17 as the intent and §18 as the evidence-anchored
corrections. Any conflict between the two: §18 wins (it has primary-source citations).

### 18.1 What was confirmed

| Investigation | Section | Claim | Verdict |
|---|---|---|---|
| I1 | §4 phase chain | Phase 3 `ServicePhase` can construct Worker-dependent services before the Worker is reachable | **Confirmed.** The supplier pattern (`() -> this.fieldName`) at L698–L749 `registerWorkerHandlers` decouples registration from Worker availability. Worker-dependent services are constructed with lazy-supplier refs; the suppliers resolve at handler invocation, not registration. Phase 3 outputs supplier-backed `ServiceGraph`; Phase 7 (`ConnectPhase`) resolves the suppliers. Stands |
| I2 | §5 dissolution | `AppFacade` removal is a single-sweep mechanical migration | **Confirmed.** 9 production callsites across 2 modules (`ui`: 7 in `LocalApiServer` + `McpToolSurface`; `app-launcher`: 2 in `LauncherCommands`). `.onlineAi()` has zero production callers — its dissolution is a pure delete. §16 #6 closed |
| I3 | §6 SubstrateGraph | Catalogs are leaf consumers of services; no cycles | **Confirmed.** All 13 catalogs constructed in `AppFacadeBootstrap` are leaf consumers. Cross-catalog reference exists only via composition (Core + AgentTools `OperationCatalog` are merged into `BackendIntentRouter`). Phase 4 after Phase 3 is correct |
| I7 | §4 outer chain | HeadlessApp's Phase 0–3 integrates cleanly with §4's Phase 0–7 | **Confirmed.** `HeadlessApp.main()` already runs Phase 0 (resolveConfig) → Phase 1 (setupInfra → `InfraPhaseResult`) → Phase 2 (`buildApi` → `ApiPhaseResult`, contains `AppFacadeBootstrap`) → Phase 3 (`connectWorker` → `WorkerConnectionResult`, calls `bootstrap.connectKnowledgeServer`). §4's Phases 2–5 (Capability/Service/Substrate/Orchestration) live INSIDE the `new AppFacadeBootstrap(...)` constructor that HeadlessApp's `buildApi` invokes. §4's Phase 7 = HeadlessApp's `connectWorker`. Clean ownership split |
| I11 | §2 taxonomy | Four-concern taxonomy is exhaustive | **Confirmed.** `/api/debug/state` on the running stack exposes exactly the state shapes the taxonomy predicts (config, lifecycle/health, orchestration via `lambdamart.training.status`, plus the Worker subprocess state which lives in a separate JVM per ADR-0001 and is not an `AppFacadeBootstrap` concern). No surprise concern-class |
| I12 (implicit) | §11 motivation | Decomposed classes re-grow without enforcement | **Confirmed.** I9 below |

### 18.2 What was corrected

#### §7 — `OrchestrationHandles` is undercount (I8)

§7's 6-item set is INCOMPLETE. `AppFacadeBootstrap.java` starts at least these
additional background behaviors:

1. **`JobQueueDepthMetricProducer.start()`** (L884)
2. **`DocumentsIndexedRateMetricProducer.start()`** (L901)
3. **`GpuUtilizationMetricProducer.start()`** (L917)
4. **`GpuMemoryUtilizationMetricProducer.start()`** (L934)
5. **`RemoteIndexingJobsBridge.start()`** (L1270, inside `connectKnowledgeServer` —
   so this is *part of Phase 7*, not Phase 5)
6. **`InfraHealthGrpcServer.start()`** (L1570)
7. **`OfflineCoordinator` enrollment** (L355, subscribes to inference mode changes)

The `OrchestrationHandles` record grows to ~10 entries. The 4 metric producers all
follow a clean `.start()`/`.stop()` lifecycle (closed at L1481/1489/1496/1503).
`InfraHealthGrpcServer` lives at its own gRPC port (loopback) and is shut down at
L1510–1519. All teardowns are accounted for in `close()`.

Additionally: **Phase 5 vs Phase 7 split**. The bridge start is conditionally tied
to Worker availability (started inside `connectKnowledgeServer`). Some
orchestration startup is genuinely Phase 5 (metric producers, coordinator
enrollment — no Worker dep); some is Phase 7 (bridge start, deferred agent-tool
handler registration). The `OrchestrationHandles` design must accommodate this
split or be split into `OrchestrationPhase` + `ConnectOrchestrationPhase`.

#### §9 — Module-graph inversion is much harder than claimed (I4)

§9 claimed "8 mechanical moves." That is wrong. The per-service audit:

| Service | Impl file | Verdict | Blocker |
|---|---|---|---|
| `SettingsService` | `SettingsController.java` | needs-interface-extract | 6 ui-internal types (UiSettings/V2, UiSettingsStore, LlmSettingsV2, ConfigStoreRebuilder) |
| `ExcludesService` | `IndexingController.java` | **mechanical-clean** | Only `ExcludeGlobs` (1 type) |
| `BrainInstallService` | `AiInstallController.java` | needs-prior-work | `EnterprisePolicyService`, `AiInstallService` (ui-internal), `UiSettingsStore` |
| `BrainRuntimeService` | `InferenceHandlers.java` | needs-prior-work | `EnterprisePolicyService`, `UiSettingsStore` |
| `PackImportService` | `AiPackController.java` | needs-interface-extract | `AiPackImportService` (ui-internal) |
| `PolicyService` | `PolicyController.java` | needs-prior-work | `EnterprisePolicyService`, `EffectivePolicy`, `UserPolicyWriter` |
| `DiagnosticsService` | `DiagnosticsController.java` | needs-prior-work | `EnterprisePolicyService` |
| `RuntimeVariantService` | `AiRuntimeController.java` | needs-prior-work | `EnterprisePolicyService`, `RuntimeActivationService`, `EffectivePolicy` |

**The actual headline**: only 1 of 8 services is mechanical-clean. **5 of 8** depend
on `EnterprisePolicyService` — meaning §9 has a single prerequisite that unblocks
the majority: either move `EnterprisePolicyService` to `app-services` first, or
extract its interface to `app-api` (`app-services` already depends on `app-api`).
3 of the 8 also need their own sub-interface extractions (`AiInstallService`,
`AiPackImportService`, `RuntimeActivationService` are ui-internal helpers the
services compose).

All 8 also import `io.javalin.http.Context` — the HTTP framework — but this is
expected and is exactly what stays in `ui` after the controller / service split
separates business logic from HTTP wrapping.

**§9 is not a single slice. It's a 3-step sequence:** (a) extract or move
`EnterprisePolicyService` interface to `app-api` (unblocks 5/8); (b) extract
sub-interfaces for the 3 helpers (`AiInstallService`, `AiPackImportService`,
`RuntimeActivationService`); (c) move the 8 services. Once (a)+(b) are done, all
8 moves are mechanical.

This significantly raises the cost estimate for §9 from "8 moves" to "3 phases of
sub-work + 8 moves." A future slice picking this up needs to budget accordingly.

#### §14 — `SearchOrchestrator` is fork-and-fuse, not strategy (I10)

§14 claimed `SearchOrchestrator` decomposes into per-mode strategy classes
(`TextSearchStrategy`, `HybridSearchStrategy`, `ChunkSearchStrategy`). That is the
**wrong pattern**.

The actual architecture (from `SearchOrchestrator.java` method skeleton):
- Mode is converted to `PipelineConfig` (which legs to enable) at L1857
  (`modeToDefaultPipeline`).
- Retrieval is **fork-and-fuse**: BM25, Dense, SPLADE, Chunk legs run in parallel
  via virtual-thread fan-out (`branchSpan` at L1908, per leg).
- Results are fused via RRF/CC (`fuseLegs` at L1708).
- Chunk branch is special-cased (`executeChunkBranchFusion` at L1318,
  `mergeChunkResults` at L1146).

The correct decomposition target is per-leg `RetrievalLeg` classes (`BM25Leg`,
`DenseLeg`, `SpladeLeg`, `ChunkLeg`) + a `RetrievalFusion` collaborator (RRF / CC
fusion logic) + a `PipelineConfigResolver` (mode → enabled-legs) + a thin
`SearchOrchestrator` that fans out enabled legs and calls fusion.

§14's principle ("procedural sequence → typed-data graph + thin orchestrator")
stands; only the data-shape changes — from per-mode strategy to per-leg fork-and-
fuse. This was a category error in the original §14, not a wrong principle.

#### §6 — Slice 494 advisory infrastructure is 5 components, not 3 registries (I3)

§6's draft listed `AdvisoryClassRegistry`, `AdvisoryChangeRegistry`,
`AdvisoryLogRegistry`. The actual Slice 494 inventory is:

- `OperationCompletionProjector` (projector — 1 of 2)
- `HealthRecoveryProjector` (projector — 2 of 2)
- `AdvisoryClassRegistry` (1:1 class-id → projector mapping, 2 entries at launch)
- `AdvisoryChangeRegistry` (broadcast + dedup state, holds reference to class registry)
- `AdvisoryResourceCatalog` (exposes advisory classes as discoverable Resources)
- `AdvisoryLogs` — an in-memory `Map<AdvisoryClassId, AdvisoryLog>`, not a
  Registry class

The `SubstrateGraph` record needs these names corrected. The shape is otherwise
unchanged.

### 18.3 Open §16 questions answered

- **§16 #4 Constructor parameter surface.** Production already passes
  `(telemetry, new ConfigManagerBootstrap(), null)` for `KnowledgeServerBootstrap`
  (see `HeadlessApp.java` L375, `LauncherEnvironment.java` L95). The "null Worker
  is the normal case" pattern is the design's intended shape. Keep it.
- **§16 #5 Test constructor.** Both constructors are used in tests:
  - `(searchPort, telemetry)` — `DefaultAppFacadeTest`, `AppFacadeBootstrapTest`
    L68 — narrow `search()`-only tests.
  - `(telemetry, ConfigManagerBootstrap, null)` — `AppFacadeBootstrapTest`
    L80/101/115/129/161 — full-stack tests with Worker null.
  - Under the new design, both seams remain: the narrow seam becomes "construct
    a `ServiceGraph` with only `SearchService` populated"; the full seam becomes
    "run all phases with `KnowledgeServerBootstrap = null`."
- **§16 #6 `AppFacade` external consumers.** 2 in-repo modules (`ui`,
  `app-launcher`). No external consumers. Confirmed.

### 18.4 Open §16 questions still open

- **§16 #1 `SearchService`'s home.** Decision deferred to slice. `app-services`
  is the natural home; `SearchPort` resolution works either way.
- **§16 #2 `AgentService` placement.** Decision deferred to slice.
- **§16 #3 `SubstrateGraph` granularity.** Decision deferred. I3's finding
  (catalogs are leaf consumers, no cycles) means either granularity works.

### 18.5 Newly-surfaced concerns

#### N1. Class-size enforcement uses PMD, not ArchUnit (I5)

§11 drafted as "ArchUnit (or Gradle source-set) rule." Actual codebase uses PMD
7.22.0 (`build.gradle.kts` L7 + L18) with `@SuppressWarnings("PMD.XYZ")`
suppression already used elsewhere. The enforcement mechanism in §11 changes from
"new ArchUnit rule" to "**enable PMD `ExcessiveClassLength` (default 1000) +
suppression burn-down via `@SuppressWarnings("PMD.ExcessiveClassLength")`** on
the three remaining mega-classes." The pattern is identical to the
`AppServicesWorkerGuardrailsTest` env-read allowlist that §11 cites: an
allowlist that's irreversible-burn-down.

No PMD ruleset XML found by `find` — the ruleset is either inline in
`build.gradle.kts` (didn't see it in my read of L1–80) or uses defaults. A
future slice needs to locate the active ruleset and either enable
`ExcessiveClassLength` or confirm it's already on with `AppFacadeBootstrap`,
`SearchOrchestrator`, `InferenceLifecycleManager` already in `// NOPMD` / suppression
state.

#### N2. ILM re-growth was concentrated in a single feature addition (I9)

The empirical case for §11 is stronger than the design described. Of the 571 LOC
that re-grew `InferenceLifecycleManager` from 915 (post-ADR-0017) to 1486 (current):

- `8f08ed3dd feat(inference): tempdoc 412 — observability adoption + Path C
  empirical verification`: **+395 / −37 = +358 net (~63% of re-growth)**
- `c0d4153fc fix(346): VDU server mode, CUDA detection`: +64
- `533e1acd7 fix(374): alpha.20-27 — VramDetector NVML migration`: +25
- `679e3ae44 feat(499): multi-channel streaming substrate`: +16

~85% of re-growth (≈463 lines) came from a small number of feature additions
that absorbed new responsibility (observability hooks, VDU mode, lifecycle
hardening, streaming substrate) into the existing class **because there was no
enforced bound to push the work into collaborators**. §11's burn-down rule
would have failed each of these commits at the build, forcing the contributor
to extract a collaborator before merging.

This is a direct empirical argument that §11 must land **before** any further
decomposition slice: without enforcement, decomposed classes re-grow within
months.

### 18.6 Updated confidence

| Section | Pre-investigation | Post-investigation |
|---|---|---|
| §2 | High | High (I11 confirms) |
| §4 | Low | **High** (I1 + I7 confirm; pattern needs documenting) |
| §5 | Medium | **High** (I2 confirms tractability) |
| §6 | Low–Medium | **High** (I3 confirms; minor naming correction) |
| §7 | Medium | **Medium** (I8 corrected; design grows but stays valid) |
| §9 | Low | **Low** (I4 shows §9 is 3-step, not 1-step — design stands but slice cost ↑↑) |
| §10 | Medium | **High** (I6 + I7 confirm) |
| §11 | Medium | **High** (I5 grounds mechanism; I9 grounds urgency) |
| §14 | Low | **Medium** (I10 corrects pattern: fork-and-fuse not strategy) |

Bottom line: the design's structure is sound. The corrections are concrete and
named with evidence. The work to be done in a future slice is now better-scoped.

---

## Section 17 — Reading order for the next agent

A future agent picking this up should read in this order:

1. **§18 first** — the evidence-anchored corrections from the 2026-05-18
   investigation pass. Any conflict between §1–§17 and §18: §18 wins.
2. This tempdoc §1–§17 end-to-end. The sections are sequenced from diagnosis
   to design to open questions.
3. Tempdoc 502, all sections — the prior structural inversion this design
   completes. Especially §1.2 (controller-as-service), §2 (the five layers),
   §5.6 (two-path locator), §6.3 (module-boundary blocker).
4. Tempdoc 512 §B3 — the mega-class audit.
5. The current `AppFacadeBootstrap.java` (2,823 LOC). The skeleton in Section 2
   above is a map; the file itself is the territory.
6. `docs/reference/contributing/class-size-standard.md` — the published ceiling
   and the eleven prior decompositions that prove the pattern.
7. Tempdocs 517 and 518 for the parallel mega-class issue-descriptions, if the
   future slice is considering bundling them with this work.
