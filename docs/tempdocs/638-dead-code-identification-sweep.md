---
title: "Dead Code: Sweep, Removal, and Closed-World Detection Design"
status: done
created: 2026-06-23
completed: 2026-06-23
depends_on: []
methodology_from: ["325-dead-code-archunit-freeze-cleanup", "326-frontend-dead-code-knip-cleanup", "367-legacy-code-audit"]
conforms_to: ["530-class-size-ratchet-automation (discipline-gate kernel)"]
---

# 638 — Dead Code: Sweep, Removal, and Closed-World Detection Design

## Purpose

Identify and remove dead code as it stands on `main` (2026-06-23), ~3 months after the
325/326/367 dead-code campaign. Identification used the five-signal methodology from that
campaign; every finding was verified against source (the `audit-without-test` discipline: a
"this is dead" claim is a hypothesis until zero callers are proven), and a confidence-probe
pass (§Confidence probes) closed the gap between "no grep callers" and "safe to delete"
before any deletion.

## Methodology — five orthogonal detection signals

No single signal is complete; the automated gates (signals 1, 5) are CI-enforced, so fresh
dead code lives in the gaps they can't see (public members, doc/config rot, stale exclusions).

| # | Signal | Catches | Mechanism |
|---|--------|---------|-----------|
| 1 | Zero bytecode callers | private/pkg-private methods/classes/fields | ArchUnit `UnreferencedCodeTest` |
| 2 | Zero cross-module importers | public classes | manual `git grep` per public class |
| 3 | Import-from-deleted-module | stranded consumers after a refactor | `git grep <deleted-name>` |
| 4 | Doc/config references nonexistent file | rotted docs, dead config | `git ls-files` cross-check |
| 5 | Unused exports/types/files | frontend | Knip + `dead-code` governance ratchet |

## Phase 0 — tooling state (verified)

All 325/326 gates survive and hardened: `UnreferencedCodeTest` (ArchUnit, ~596-line exclusion
maps), Knip, the **new** `dead-code` governance ratchet (registry id `dead-code`, empty
baseline → frontend clean), PMD `UnusedFormalParameter`, DAGP `buildHealth`.

## Findings (verified) and disposition

| ID | Finding | Verified disposition | Commit |
|----|---------|----------------------|--------|
| **F1** | 6 dead public classes: `FeatureSnapshotResourceCatalog`, `ResultDispositionResourceCatalog`, `UnavailableDocumentService` (app-services); `FatalError`, `EmbeddingBackendConfig`, `StubLocalLlmTranslator` (ai-backend) | **Deleted** (+ orphan `abi/README.md` that documented a nonexistent `NativeLlamaBinding.auditAbi` throwing FatalError) | c8f42e0d3 |
| **F2** | head-side dead classes: `UserIndexConfig` (app-indexing); `LuceneSearchClient`/`IndexSearcherProvider`/`PagingCursorManager` trio (app-search); `ComparingPlanner`/`SearchDecisionDiff` shadow-diff harness (worker-services); cascade `FeedbackHistoryResources` (app-services) | **Deleted class-level** (modules survive for live testFixtures/catalog); dropped 3 now-unused `app-indexing` deps; reduced app-indexing to testFixtures-only | 1eb454044 |
| **F2-backout** | `RequiredFieldsCommitMetadataValidator` | **NOT deleted** — `CommitMetadataIntegrationTest` uses it to exercise the real commit-failure path; test-support, not dead (probe falsified the hypothesis) | — |
| **F2-drop** | `app-services registry/validator/*` + `registry/proposal/*` (MED lead) | **Dropped from scope** — `ValidatorRunnerTest:256-259` wires them into a live registry-shape validation harness; not dead (like ArchUnit rules, no production caller by design) | — |
| **F3** | `IndexingLoop.getBackfillScheduler` (dead 516-W6 accessor) + 2 stale `UnreferencedCodeTest` exclusion entries (`getBackfillScheduler`, `SearchExecutor.hitCount`) | **Deleted** | d77b456c1 |
| **F4** | config/env keys | **CLEAN** — all 251 `EnvRegistry` constants live (read by property-string via `ResolvedConfigBuilder`) | — |
| **F5** | dead tooling `build-mixed-context-annotations.mjs` (+ test, imported deleted `./lib/`); broken `capture:evidence` package.json script + knip entry | **Deleted/fixed** (code+config scope) | 362147d74 |
| **F5-doc** | ~17 canonical-doc references to deleted subsystems (evidence-bundle harness, workflow-telemetry pipeline, bench suite, 367-era eval tooling) | **Logged for a dedicated `/doc-audit` pass** — pre-existing subsystem-deletion drift, not this dead-code work; bulk canonical-doc rewrite is out of scope + cross-agent-conflict-prone | obs shard |
| **F6** | base `228f425a4` inherited a RED `UnreferencedCodeTest` — 8 unreferenced methods from recent 607/626 merges (no exclusion registered) | **Triaged**: classified each by caller analysis, added invisible-caller exclusions (most are non-dead — worker-services/reflection/overload callers); 4 suspected-genuinely-dead (`SyncOps.getScheduler`, `AgentController.shutdown`, `ExcludeMatcher.isExcluded`, `OcrConfidenceExtractor.extractPlainText`) flagged for their owners, **not deleted** (other agents' just-merged code) | d77b456c1 |

## Confidence probes (pre-removal, P1–P7)

- **P1** F1 indirect reachability: no SPI/string/Jackson/native ref; FatalError never thrown
  in Java and `NativeLlamaBinding`/`auditAbi` don't exist → HIGH.
- **P2/P3** F2 module-dep map: **corrected "delete module" → class-level** — `app-indexing`
  survives for live `TestTelemetry` (imported by `PagingCursorManagerTest`); `app-search`
  survives for live `SearchPagingMetricCatalog` (ui reads `DEFINITIONS`). Tests delete cleanly
  except the shadow-diff test (asserts only on the diff mechanism, no unique live coverage).
- **P4** `RequiredFieldsCommitMetadataValidator`: no registry — **but** a second consumer
  (`CommitMetadataIntegrationTest`) surfaced at compile time → backed out (see F2-backout).
- **P5** F3 shape+ordering confirmed (plain accessor, not `@Override`; entry+method same change).
- **P6** F5 resolved to delete-not-fix; doc targets deleted-not-relocated.
- **P7** cascade forecast: predicted the `app-indexing` dep-hygiene fallout (realized — lockfile
  regen for app-indexing/app-search/app-services/ui), and the `FeedbackHistoryResources` cascade
  (realized — deleted after F1 removed its only referencers; the ArchUnit dead-class rule caught it).

## Outcome

| Metric | Value |
|--------|-------|
| Dead public classes removed (F1) | 6 (+1 orphan README) |
| Dead head-side classes removed (F2) | 7 (incl. cascade `FeedbackHistoryResources`) |
| Dead methods removed (F3) | 1 + 2 stale exclusion entries |
| Dead tooling scripts removed (F5) | 2 + 2 broken config entries |
| Net lines removed | ~1,300 (Java + scripts) |
| Build-graph deps cleaned | 3 `app-indexing` `project(...)` deps + app-indexing reduced to testFixtures-only; 4 lockfiles regenerated |
| Commits | d77b456c1 (F3+F6), c8f42e0d3 (F1), 1eb454044 (F2), 362147d74 (F5) |

## Verification

- `./gradlew.bat build -x test` — green (compile).
- `:modules:app-launcher:test --tests UnreferencedCodeTest` — green (dead-code gate; cascade
  terminated; F6 triage consistent).
- `node scripts/governance/run.mjs --gate dead-code --mode gate` — pass (frontend ratchet).
- Affected-module tests (app-indexing, app-search, adapters-lucene, worker-services, ai-backend)
  — green for all changes.
- Lockfile drift: only the 4 intended modules changed; no surprise drift.

**Pre-existing failures NOT caused by this work** (base `228f425a4` is partially red from
607/626-era merges; verified none reference deleted classes; logged to the observation shard):
`ValidatorRunnerTest` (`core.reconcile-root` handler not registered), `UIOperationViewConformanceTest`
(operation-wire golden drift), `VduEligibilityPdfFixturesTest` (PDF OCR reflection — recurring
325 Issue 1). `buildHealth` could not run (pre-existing DAGP/kotlin-metadata 2.4.0-vs-2.3.0
incompatibility on `:modules:ui:explodeJarMain`) — dep hygiene reasoned manually instead.

## Investigate-further round (2026-06-23)

A second sweep (public *methods* + non-code artifacts + modules the first pass didn't cover),
via parallel subagents whose findings were independently re-verified.

**Removed (commits 08b6a4452, 943e9e1a3):**
- **Whole module `modules/app-indexing`** — a cascade of this tempdoc's own F2 work: Commit
  1eb454044 deleted `PagingCursorManagerTest`, the sole consumer of app-indexing's last artifact
  (`TestTelemetry`). With zero consumers the module was fully dead. Removed module + settings +
  app-search testFixtures dep + module-filter; regenerated `module-deps.md`.
- **Whole module `modules/search`** — only `SearchHitMetadata` (zero consumers) + 17 orphaned
  test fixtures; zero module dependents. Removed module + all coupled CI references (CODEOWNERS,
  verify-codeowners + its test, module-filter, LayeringEnforcement layer, module-deps).
- **4 dead public classes** (+2 tests): `CommitMetadataController` (orphaned route duplicate),
  `DocumentFetcher` (ui), `ConfigParsingUtils` (configuration), `SummaryRejectionMetadata` (ipc-common).

**Investigated, deferred (documented, NOT deleted) — low-value/high-care long tail:**
- **~14 dead public *methods*** (0-caller in verification): `SqliteJobQueue.getDbPath`/
  `existedBeforeOpen`, `IndexRootLock.lockFile`, `TempFileManager.getTempRoot`/`getTrackedCount`,
  `TokenEstimation.truncatePrefixToTokenBudget`/`formatRagSection`, `PromptTemplateLoader.loadRaw`,
  `InferenceLifecycleManager.isInVduMode`, `ChunkIds.isChunkDocId`, `GrpcCircuitBreaker.isTransientStatus`,
  `TokenAwareBudgeter.isUsingTokenCounter`, `AppInstanceLock.lockPath`, `EmbeddingService.embedDocumentWithChunks`,
  `RepoPaths.findRepoRootOrNull`. **Deferred** because each needs a class-qualified `@Override`/cascade
  check before deletion — neither the subagent list NOR a bare name-grep is reliable here:
  the subagent wrongly flagged `RepoPaths.findRepoRootOrNull` as the only finding I doubted, while
  a bare grep over-matched same-named methods on `RepoRootLocator`/`SsotAnalyzerRegistry`. This is
  the `audit-without-test` failure mode at method granularity — a careful dedicated pass, not a batch.
- **MEDIUM proto**: `ipc/v1/health.proto` (`HealthService`/`HealthStatus`/`VersionInfo`) superseded by
  the live `indexing.proto` HealthService; deletion needs two test edits. `severity.proto`
  `SeverityMetadata`/`SeverityCatalog` read as deliberate forward-compat (LOW).
- **Vestigial metric**: after the `PagingCursorManager` deletion, `SearchPagingMetricCatalog`'s
  `pagingFaultsTotal`/`pitAcquireMs` are registered (ui/launcher read DEFINITIONS) but emitted by
  nothing in production — a candidate for removal-with-registration-cleanup.
- **`AgentController.shutdown`** — constructed at `ConversationApiAssembly:348` but its `shutdown()`
  (stops the heartbeat scheduler) is never wired into `LocalApiServer`'s `module.shutdown()` loop →
  a latent resource leak (a fix, not a deletion).

## Long-term design — closed-world dead-code detection

### The problem this tempdoc actually has

The deferred long-tail is not "14 specific methods." Across 325 → 367 → 638, the *same*
categories of dead code (public/protected members, cross-module/cross-process orphans,
whole-module husks, doc rot) recur every few months because **they live in exactly the gap
the continuous detectors cannot see**, so they are only ever found by a periodic *manual
campaign*. Worse, the manual campaign's verification is itself unreliable: this sweep's
name-greps both over-matched (`SseEvent.of` → every `.of(`) and falsely matched same-named
symbols on other classes (`RepoRootLocator`/`SsotAnalyzerRegistry` vs `RepoPaths`), and a
subagent's "dead" claims were hypotheses that compile-time and grep repeatedly falsified
(`RequiredFieldsCommitMetadataValidator`). The real problem is therefore: **the public /
cross-process dead-code class has no continuous, authoritative detector — only a recurring,
error-prone manual sweep.**

### Why the existing Java gate misses it (root cause, not symptom)

`UnreferencedCodeTest` (ArchUnit) is the existing detector. Two design choices, both correct
for a *library* but wrong for this *closed application*, create the blind spot:

1. **Process-scoped classpath.** `@AnalyzeClasses(packages = "io.justsearch")` imports only
   what is on **app-launcher's** classpath — the *Head* process. JustSearch is a 3-process app
   (Head / Worker / Brain); worker-side modules run on a separate classpath and are invisible,
   which is exactly why the exclusion map is littered with "(worker-services) caller invisible"
   entries. The gate sees one process, not the whole program.
2. **Visibility heuristic as a proxy for reachability roots.** The rule explicitly excludes
   `public`/`protected` members (on the library assumption "an external caller might exist").
   In a closed app there is no external caller *except a small, enumerable set of entry roots*,
   so visibility is the wrong proxy — it both lets dead public code through and forces the manual
   sweep to do the gate's job by hand.

### The design (general, not implementation-level)

A **whole-program, closed-world dead-code gate**. The original framing below was "relocate
`UnreferencedCodeTest` to whole-program, not new machinery." **As-built correction (2026-06-24):**
the feasibility probes (§P1) showed you cannot simply relocate it — its whole-program form for
*methods* is infeasible (4,073 noise, §G1), and it has no all-module host. So the realized shape is
a **new, complementary gate** (`modules/dead-code-audit` + the `dead-code-jvm` ratchet) that REUSES
the existing dead-class *rule logic* and the 530-kernel *substrate*, running ALONGSIDE the unchanged
`UnreferencedCodeTest`. Consequence to be honest about: the root-cause flaws this tempdoc diagnoses
in `UnreferencedCodeTest` (process-scoped classpath, visibility heuristic) are NOT remediated in that
gate — the new gate demonstrates the fix for *classes* beside the still-process-scoped method gate;
public/cross-process dead *methods* remain covered only by one-shot verification, not continuously.
The design properties below describe the new gate:

- **Whole-program analysis boundary.** One analysis with visibility into the *union* of every
  production module's bytecode (Head + Worker + any future process), so a caller in any module
  is seen. The seam already exists: `modules/system-tests` already aggregates ~19 modules across
  both processes — the union classpath is a solved problem to reuse, not invent.
- **Uniform across visibility.** With whole-program visibility, a symbol with zero callers
  anywhere is dead *regardless of visibility* — drop the public/protected exclusion. This is
  sound here precisely because the app is closed-world (not a published library).
- **Declared reachability roots, projected not forked.** Carve out the genuine entry boundary —
  the places callers come from *outside* the analyzed bytecode. The root *categories* are not ad
  hoc: they match GraalVM native-image's **reachability-metadata** taxonomy (see "External
  grounding" below), which is the mature industrial enumeration of "reachability invisible to
  static analysis." Mapped to JustSearch:
  - **reflection / SPI / `META-INF/services`** (ServiceLoader, framework-dispatched gRPC `*ImplBase`
    and Javalin route handlers — all reflective invocation);
  - **serialization** (Jackson-deserialized DTOs/records);
  - **JNI / native callbacks** — native code (`ort-common` ONNX runtime, llama bridge) invoking
    Java methods by name. *This category was missing from the first draft of this design and is a
    real surface here* (cf. the FatalError JNI check earlier in this tempdoc) — its omission is
    exactly the costly "false-positive deletion" failure mode the gate must avoid;
  - **dynamic JDK proxies / runtime-generated classes** (interface methods reached only via a proxy
    invocation handler);
  - plus two roots GraalVM does *not* need but JustSearch does: **process `main` entry points**
    (3-process app) and **published-module public API** (`app-api`,
    `api-contract-projection-java` carry a `publishing` block → external/codegen consumers).
  - *future*: the plugin/extension SPI, once `extension-substrate` gains external plugin loading.

  Per the codebase's projection-vs-fork rule, *derive* roots from their existing declaration sites
  wherever structurally possible (`publishing` block, `*ImplBase`, `META-INF/services`,
  `application`/`mainClass`, `@JsonCreator`), and keep an explicit allowlist only for the
  underivable residue (a specific reflective call, a JNI callback) — reusing the existing
  baseline/changeset exemption mechanism, *not* a new register. (Original plan said the
  `KNOWN_UNREFERENCED` map would *seed* this roots list; **as-built**, it was not — that map is
  method-level and the shipped gate is class-level, so the class gate has its own
  `gates/dead-code-jvm/baseline.txt`.) The underivable residue's last-resort fallback is *runtime
  observation* — GraalVM's tracing agent records what is actually invoked; JustSearch's equivalent
  is JaCoCo production-run coverage (325 already named this tier) — used only to discover roots the
  static derivation cannot.

  **As-built roots vs the taxonomy (2026-06-24):** the shipped gate implements published-API, SPI
  (`META-INF/services`), gRPC `*ImplBase`, process `main`, native-boundary, and the two build-forced
  roots (constant-holder, nested-type-holder). It does NOT implement a general reflection root
  (`Class.forName`-by-name), a JNI-*callback*-target root (only the native-boundary class), or a
  serialization root for non-`@JsonCreator` DTOs. This is acceptable because the gate is
  report-only→baseline-ratchet: a false positive lands in the baseline, never an auto-deletion — so
  the taxonomy's "costly false-deletion" risk is held by the *ratchet model*, not by root
  completeness. Those residual root categories are added only if a real class is mis-flagged.

### External grounding (research pass, 2026-06-23)

A bounded web pass confirmed this design is an instance of established external practice, and
caught the JNI gap above:
- **The design is the GraalVM native-image model, scoped down.** native-image performs exactly
  closed-world reachability from declared roots; its **reachability-metadata** (unified into one
  `reachability-metadata.json` as of GraalVM for JDK 23, Sept 2024) enumerates reflection / JNI /
  resources / proxies / serialization / predefined-classes — the canonical roots taxonomy this
  design now mirrors. We deliberately take only the *zero-direct-caller* slice, not native-image's
  full transitive analysis (over-scoped for the recurring problem).
- **Extending ArchUnit is the idiomatic, community-validated path** — the `archunit-unreferenced`
  ruleset (timtebeek) and ArchUnit issue #583 ("unnecessarily-public top-level classes") do exactly
  this; the well-known "Spring endpoints/listeners are never called directly" caveat *is* the roots
  problem, and roots/allowlisting is its standard mitigation. So "extend, don't replace" is mainstream.
- **ProGuard stays out:** JDK 25 support remains an open ProGuard issue (June 2025), so the 325
  Phase 11 blocker persists — reinforcing the choice to avoid full-reachability tooling here.

The result converts the campaign-driven manual sweep for this class into continuous enforcement,
and makes per-candidate verification *authoritative* (the gate's bytecode analysis replaces the
unreliable name-grep).

**Scope correction (measured at implementation — see §Implementation outcome and §Review-gap
resolution):** "uniform across visibility" holds for *classes*, where the shipped gate is
continuous. It does NOT extend to a continuous *method* gate: whole-program dead-method detection
measured at ~4,073 findings even with strong roots (serialized accessors / builders / fluent APIs),
which is not baselineable. Method-level dead code therefore stays covered by the legacy
`UnreferencedCodeTest` (private/pkg-private) PLUS the whole-program analysis used as *one-shot
authoritative verification* for curated candidates — which is how the deferred ~15-method long-tail
was resolved (verified dead, then deleted), not via a standing method gate.

### Scope discipline — what the problem does NOT require

- **Not full transitive reachability** (ProGuard `-printusage` from entry roots, attempted and
  blocked in 325 Phase 11). That finds deeper *unreachable subgraphs*, which are rare after a
  cleanup and beyond what the recurring problem (zero-caller members) needs. Over-scoped.
- **Not a generalized "closed-world liveness substrate."** Build only the dead-code instance.
- **Not a fold-in of doc-rot (F5) or the "pre-merge gate runs no tests" policy.** Adjacent, but
  distinct concerns with their own homes (`/doc-audit`; CI policy). Named below, not designed here.

### How it conforms to existing structure

This is a new **Layer-2 gate-class entry on the 530 discipline-gate kernel** — the Java
complement to the frontend `dead-code` Knip ratchet (which is already such an entry). It reuses
the Layer-1 substrate (runner, SARIF, baseline, changeset-loader, truth-table) and the
established baseline+changeset exemption pattern (`class-size-exceptions.txt`,
`gates/dead-code/baseline.txt`, `consumer-presence/exemptions.json`). It does not create a
parallel mechanism.

### Gate feasibility probes (2026-06-23) — corrections before implementation

A read-only pass turned the design's load-bearing *assumptions* into facts; three were wrong as
written and are corrected here.

| Probe | Finding | Effect on design |
|---|---|---|
| **P1 host classpath** | `system-tests` covers only **15 of 34** modules (19 blind, incl. app-search/core/telemetry/worker-core). ArchUnit *does* import `io.justsearch.*` from dependency **JARs** by default (`resolveMissingDependenciesFromClassPath=true`; opt-out is `DO_NOT_INCLUDE_JARS`). app-launcher genuinely doesn't depend on worker-services (1 ref) — that's the real blind-spot cause. | **Correction:** the host is NOT system-tests. Need a thin **new aggregator** module/sourceset declaring deps on all 34 production modules, with JAR import enabled. Mechanism exists; moderate, well-understood work. |
| **P2 method-ref visibility** | Framework dispatch is **method-reference-based**: 98 route handlers registered as `controller::handler` in `*Routes`, 794 `::` refs in production. ArchUnit's tracking of `::` (invokedynamic) is **unresolved** by docs and issue #131 (open, "help wanted", no maintainer answer). | **Biggest residual risk.** Must assume `::`-dispatched handlers are invisible → all ~98 route handlers (and listeners) must be **roots**. They ARE projectable (grep `::handler` in `*Routes`), so projection-vs-fork holds — but the allowlist is ~98+, not "small residue", and **completeness is safety-critical** (a missed handler = false-deleted live endpoint). |
| **P3 blast radius** | **1,329 public top-level classes, ~4,641 public methods** in production. | Day-one is NOT a clean empty baseline like frontend Knip. The gate must roll out **report-only first → measure → baseline → ratchet**, not enforce immediately. This is a multi-step program, not a one-shot extension. |
| **P4 roots counts** | gRPC `*ImplBase`: 7 · `META-INF/services`: 3 · `@JsonCreator`: 15 · **JNI/native: 17 files** (confirms the research-added category is real) · `Proxy.newProxyInstance`: **0** (drop dynamic-proxy root entirely) · mainClass/application: 10. | Most roots small + structurally derivable. The dynamic-proxy category is unnecessary here. The large root surface is the P2 route handlers, not these. |
| **P5 kernel conformance** | The frontend `dead-code` gate **does not run Knip** — its Node enforcer *reads* a pre-produced `tmp/knip-report.json` against a baseline. ~10 governance enforcers already reference `.java`/ArchUnit. | **Correction (good):** the gate is "**ArchUnit test (Gradle) emits a JSON/SARIF report → thin Node kernel enforcer reads it vs. baseline**" — an exact mirror of the Knip gate, not "the Node kernel runs ArchUnit". Precise precedent exists. |
| **P6 migration + test policy** | 55 method exclusions: 11 are "(worker-services) invisible" (become **moot** under the union host), 12 reflection (stay as roots), ~58 test-only mentions. Gate uses `DoNotIncludeTests`. | Migration is ~50 entries, tractable. **New design decision surfaced:** whether "called only by tests" = alive or dead. Cleanest = include test bytecode for *reachability* (so test-only ≠ false-dead) but separately flag "production type reachable only from tests" as the softer F2 signal. |

**Net:** the design *direction* is validated and every mechanism exists, but the realistic shape is
larger than first written — a new aggregator module, a ~98+ route-handler roots allowlist derived
from `*Routes`, a report-only→baseline→ratchet rollout over a 4.6k-method surface, a test-usage
policy, and ~50 exclusion migrations. One technical fact (ArchUnit `::` visibility) stays unresolved
and is safety-critical; it is the single thing a tiny throwaway ArchUnit probe (deferred — it is
implementation) would settle definitively.

### Implementation outcome (2026-06-23) — built

The gate was built and is green. What the report-only build taught (the design refined under contact):

- **New `modules/dead-code-audit`** (test-only sink, depends on all 33 production modules) hosts
  `WholeProgramDeadCodeTest`: ArchUnit over the union classpath, JARs included. **New `dead-code-jvm`
  governance gate** (set-ratchet on `gates/dead-code-jvm/baseline.txt`) mirrors the frontend Knip
  gate — thin Node enforcer reads the report; registry entry + truth-table + positive/negative
  self-tests all green.
- **Scope corrected by the measure step: class granularity, not methods.** The naive whole-program
  *method* report was **~6,429 findings** (reflectively-serialized accessors, builders, fluent APIs —
  the GraalVM-metadata problem) — not actionable. The *class* report was **37 → 27 → 17** after the
  roots below. So the shipped gate covers **dead public/cross-module classes** (the exact gap
  `UnreferencedCodeTest` leaves); whole-program public-*method* detection is confirmed over-scoped
  and stays out (it was the low-value long tail anyway).
- **Two roots the build forced** (beyond the predicted reflection/JNI/serialization/gRPC/published):
  (1) **constant-holders** — javac inlines `static final` constants, erasing the bytecode reference,
  so a constants class false-flags as dead (`BatchTimingKeys`, `IngestionReasonCodes`, …); (2)
  **nested-type holders** — a class whose nested type is referenced is a live namespace shell whose
  outer name has no direct incoming dependency (`LuceneRuntimeTypes`, ~20 used nested records).
- **The gate vindicated itself over name-grep.** It flagged `app.services.lifecycle.InfraContext` as
  dead; a name-grep showed "4 production refs" and looked like a false positive — but those refs were
  to a *distinct* `io.justsearch.indexerworker.server.InfraContext` (worker-core). The FQN-precise
  bytecode analysis was right where name-grep was fooled — the same `RepoPaths`/`RepoRootLocator`
  collision that misled manual verification throughout this tempdoc. **This is the gate's core value:
  authoritative verification that the manual sweep could not provide.**
- **Removed 6 gate-validated dead classes** (the Phase-4 ratchet): `BusyException`, `WriteOutcome`,
  `EffectScope`, `QueryAfterCorrection` (javadoc-only refs), `app.services.lifecycle.InfraContext`,
  and the cascade `ConfigContext`. Baseline ratcheted 27 → 17 (the residue = ~16 test-wired
  validation/contract infra + the `RequiredFields` test-support validator).
- **`UnreferencedCodeTest` is retained, not superseded** — it covers private/package-private
  *methods* (Head classpath); the new gate adds public/cross-module *class* coverage. Complementary.

### Review-gap resolution (G1–G3, 2026-06-24)

A conceptual review of the shipped gate found three alignment gaps; confidence-building experiments
settled each, and all three are now closed:

- **G1 — method long-tail / self-contradiction.** The design implied member-level coverage and a
  follow-up claimed the gate would resolve the ~15 deferred methods, but the gate is class-only.
  Measured: a method gate is infeasible (~4,073 findings even with strong roots), so it stays
  class-only — but the whole-program analysis authoritatively confirmed all 15 candidates dead, and
  they were **deleted** (commit `689de213b`). The contradiction is reconciled above (§Implementation
  outcome, Follow-ups #2): one-shot verification, not a standing method gate.
- **G2 — missing SPI root.** The roots taxonomy named `META-INF/services` but the gate had no SPI
  root, so a `ServiceLoader` impl (`DeterministicBackendProvider`) was mis-baselined as "test-wired".
  Added an SPI root that reads the production service files and exempts the listed impls (commit
  `4b27855b2`); baseline 20→19.
- **G3 — test-wired baseline.** The design preferred counting test references so test-wired classes
  aren't flagged; the implementation baselines them instead. Measured that the "include test
  bytecode" path is disproportionately costly (a `dependsOn(compileTestJava)` fan-out across 33
  modules + brittle paths + slower CI, to remove ~16 *stable* entries). Kept baselining; the F5
  documentation in `gates/dead-code-jvm/baseline.txt` is the deliberate, measured resolution.

A recurring lesson, reinforced a fourth time here: **whole-program FQN bytecode analysis is the
authoritative dead-code oracle where name-grep is not** — across this tempdoc, name-grep produced
false positives via `RepoPaths`/`RepoRootLocator`, `SseEvent.of`, the two `InfraContext` classes,
and a local variable named `lockFile`. The gate exists precisely to replace that unreliable signal.

## Design reach (principle, not new structure)

**The principle.** *A liveness / dead-code / consumer check is sound only when its analysis
boundary equals the artifact's real reachability boundary — neither narrower (false "dead") nor
wider (false "alive") — with the legitimate entry boundary declared as explicit roots rather
than inferred from a heuristic.* Call it **closed-world liveness with declared roots.**

**It is already an instance of an existing, correct seam.** The frontend `dead-code` Knip
ratchet *embodies* this principle today: Knip analyzes the whole `ui-web` project as a closed
world with explicit `entry` roots. The Java side is simply the violator that should conform to
what the frontend already does. So this design is "make Java match the frontend," not a new idea.

**Where else the principle applies (candidate scope — recognized, not built):**
- **Every single-module ArchUnit rule** inherits the same process-scoped-classpath blind spot,
  not just dead-code — e.g. `LayeringEnforcementTest` (edited in this tempdoc) and the egress /
  layering rules each see only their host module's classpath. Any rule whose *subject* spans
  processes is under-scoped from one module.
- **`consumer-presence`** is the same shape in the registry dimension ("does a registered surface
  have a consumer") and already uses a snapshot + exemptions — a sibling instance.
- **Doc↔code rot (F5)** is the same shape in the doc dimension (a reference's target must exist in
  the whole repo); the doc-drift lints are its partial instance.

**Existing violations of the principle:** `UnreferencedCodeTest` (process-scoped + visibility
heuristic), and by extension the family of one-module ArchUnit rules whose subject is whole-program.

**Deliberately NOT generalizing now.** Per "recognize the principle, don't build the general
structure prematurely": the present problem requires only the dead-code instance. The shared
"whole-program ArchUnit classpath" could one day be a reusable fixture for the rule family above,
but that generalization is warranted only when a *second* rule concretely needs it — record the
candidate scope here; build it then.

## Follow-ups — RESOLVED (2026-06-24, commits PD/PB/PC/PE/PA)

All logged follow-ups were taken over and completed after a read-only confidence pass (PA–PF probes)
settled each fix's shape:

1. **PA — `/doc-audit` doc-rot pass: DONE.** Cleared the canonical-doc references to deleted
   subsystems across **21 docs** (`597b9327b`): renames (`model-registry.v1`→`.v2`), re-points at live
   replacements (`jseval`, `sandbox-launch.py`, `check-*-reason-codes.mjs`), and Removed/Superseded
   banners for the entirely-deleted workflow-telemetry + `scripts/perf/` subsystems (ADRs noted, not
   gutted). `docs/future-features/*` (non-canonical) left for a future `/doc-audit`.
2. **PB — MEDIUM `ipc/v1/health.proto`: DONE** (`d504823f2`). Zero production consumers; deleted +
   migrated the one test to `com.google.protobuf.Empty`; wire gate green.
3. **PC — vestigial paging metric: DONE** (`e44e36646`). Confirmed no downstream consumer; removing it
   emptied `app-search`, so it collapsed to a clean **whole-module removal**.
4. **PD — F6 suspected-dead methods: DONE** (`b3b3ebdc8`). The whole-program engine confirmed all three
   production-dead FQN-precise (correcting name-grep collisions); deleted + cascade.
5. **PE — `AgentController.shutdown` leak: DONE** (`1c908950b`). It was registered inline (not an
   `ApiModule`) so its `shutdown()` reached no teardown path; wired into `LocalApiServer.shutdown()`
   with a regression test + a `declared-growth` class-size changeset.

**Method-level detection — deferred by design, now empirically confirmed.** A pattern analysis of the
deleted code (89% public, mostly tempdoc-phase scaffolding / substrate-ahead-of-consumer) and a
method-level probe re-confirmed the §G1 judgment: production-unreferenced methods number ~7.5k,
**45% protobuf-generated serialization surface + accessors + interface-dispatch noise**, leaving no
trustworthy dead list without an *interface-impl-resolution* root. That root is the one high-leverage
upgrade if a concrete dead-method need ever arises; until then method-level stays covered by the legacy
`UnreferencedCodeTest` + one-shot whole-program verification for curated candidates. **Not built (YAGNI).**

**Still out of scope (separate concern, not 638):** ~5–6 pre-existing `main` test reds
(`ValidatorRunnerTest`, `UIOperationViewConformanceTest`, `VduEligibilityPdfFixturesTest`,
`LocalApiServerThinComposerTest`, `RegistryControllerTest`) — predate this work, belong to other
concerns; logged, not fixed here.

**Status: COMPLETE.** Class-level whole-program closed-world dead-code gate built + CI-wired; sweep +
all follow-ups landed; ~4.6k LOC of dead code removed (incl. 3 whole modules) against +1k LOC of gate
machinery that now prevents recurrence of the public/cross-module class. Branch merged to `main`.
