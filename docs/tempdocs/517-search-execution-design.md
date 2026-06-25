---
title: "517 — Search execution: decision as a value, not control flow"
---

# 517 — Search execution: decision as a value, not control flow

**Date**: 2026-05-18
**Status**: open
**Source path**: `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java`
**Supersedes**: the prior 5-role temporal-split design (deleted in the same commit).
**Related**:
- ADR-0014 (`docs/decisions/0014-pipeline-definition-removal.md`) — decision-tree shape endorsed, pipeline-engine rejected;
- ADR-0016 (QU soft-boost discipline);
- ADR-0007 + ADR-0020 (entity / `meta_` facets);
- ADR-0027 (MetricCatalog telemetry contract);
- `docs/reference/contracts/search-pipeline-invariants.md` (behavioural contract);
- `docs/reference/contracts/search-and-rag-reason-codes.md` (reason-code allowlist);
- `docs/reference/contributing/class-size-standard.md` (1,000 LOC ceiling, package-private collaborator pattern);
- Peer mega-class tempdocs: 516 (IndexingLoop), 518 (InferenceLifecycleManager);
- Tempdoc 512 §B3 (mega-class audit) and §F1 (speculative-generality lesson).

---

## Why this tempdoc was rewritten

The first version of 517 described a size violation (1,919 LOC at 1.9× the 1,000-LOC ceiling) and proposed a 5-role temporal-phase split (`SearchRequestNorm` → `SearchPlanner` → `SearchExecutor` → `SearchResponseBuilder`, behind a thin `SearchOrchestrator` facade) with a sealed `ResolvedLegs` sum-type for leg dispatch. The proposal was competent and survived an adversarial review pass.

The user asked for a deeper read: not a refactor of the visible seams, but the **correct long-term structure** of Worker-side search execution, with explicit freedom to consider major rewrites and the instruction to *prefer the better long-term option even at the cost of feasibility*.

That deeper read found that the prior design decomposed the *visible* pathology (a 715-LOC method, an 8-way cascade, two facet paths) but did not dissolve the *root* pathology underneath. The redesign below pushes harder on three points the prior version conceded:

1. **A two-step `RequestedLegs → encode → ResolvedLegs.from(...)` plan-resolution.** Encode is IO, so the planner cannot pre-commit. The prior design fragments the decision across two phases; the 8-way explosion was renamed and pushed one level down, not dissolved.
2. **Six separate policy fields on `SearchPlan`.** Each new degradation signal still adds a field; no compile-time enforcement of decision completeness.
3. **Inline effects.** Reason codes (24 wire-emitted + 2 log-only), OTel spans (7+), MDC keys, and metric emissions all stay sprinkled through the executor body.

The cure is **expressing the search decision as a complete value, produced once, before any retrieval IO begins** — and using the same sealed-sum + pattern-match `switch` discipline the codebase already employs as its canonical dispatch idiom (`IndexWriteOperation` in `IndexingCoordinator.java:100–111`).

---

## Diagnosis: a decision tree expressed as control flow

ADR-0014 explicitly frames `SearchOrchestrator` as *"a decision tree with fallback paths"* (`docs/decisions/0014-pipeline-definition-removal.md:21`). That framing is correct. The pathology is not that it is a decision tree; the pathology is **how the decision tree is expressed**.

The current implementation expresses the decision tree as **imperative control flow over ~12 mutable locals**:

```text
SearchOrchestrator.java:265–283  — initialise mutable hoists
  result, facetsResult, queryForSpans, chunkQueryVector, chunkSpladeWeights,
  runtimeFilters, chunkQueryText, effectiveMode, vectorBlocked, hybridFallback,
  correctionApplied, spladeExecuted
```

Each phase of `execute(SearchRequest, boolean, String)` (lines 195–910) reads these locals, performs partial IO, writes some of them back, and moves on. The 8-way retrieval cascade at lines 527–663 is what happens when you express "subset of {BM25, Dense, SPLADE}" as flag conjunctions:

```text
527–616  BM25 ∧ Dense ∧ SPLADE     (~90 LOC, 3-way parallel + CC fuse)
618–627  BM25 ∧ Dense ∧ ¬SPLADE    (~10 LOC, hybridSearchOps)
629–633  Dense ∧ ¬BM25 ∧ ¬SPLADE   (~5 LOC, vector-only)
635–638  SPLADE ∧ ¬BM25 ∧ ¬Dense   (~4 LOC, splade-only)
640–645  BM25 ∧ SPLADE ∧ ¬Dense    (~6 LOC, fuseLegs)
647–654  Dense ∧ SPLADE ∧ ¬BM25    (~8 LOC, fuseLegs)
656–658  BM25 ∧ ¬Dense ∧ ¬SPLADE   (~2 LOC, sparse passthrough)
660–663  none                       (~3 LOC, empty result)
```

Two structural problems sit underneath the size:

**(A) Decisions are incrementally constructed.** No phase produces a complete artefact for the next phase to consume. The decision tree is never materialised; it is only ever *traversed*. There is no point in the code where a reader, a test, or a future contributor can ask the orchestrator "what would you do with this request?" and get an inspectable answer — they must run the orchestrator and observe what it did.

**(B) Effects are inline.** A single search request produces:

- 24 wire-visible reason codes + 2 log-only codes (raw string emissions via `Markers.append("reason_code", …)` and proto-field writes, all inside `execute(...)`);
- 7+ OTel spans with explicit parent-context management (`search/retrieval`, three `search/branch` legs, `search/fuse` at multiple depths, `search/chunk_merge`);
- 4 `MDC.put("stage_id", …)` calls (`parse`, `retrieve`, `merge`, `respond`);
- ~6 IO-call categories (`commitOps.maybeRefresh()`, `clusterSnapshotSupplier.get()`, `getOrComputeCorpusProfile()` — *called twice*, encoder calls, retrieval calls, response IO);
- ~30 wire-format response fields;
- metric emissions through `metrics.recordSearch(...)`.

Every one of these effects is fired from inside `execute(...)`. The mega-method is the only place that sees the full picture; any extraction that leaves "what reason code do we emit?" or "what span are we under?" inside individual phases will re-fragment the picture and rebuild the same pathology under a smaller surface.

**This is not a size problem. It is a representation problem.** 1,919 LOC is the *fingerprint* of expressing a decision tree as mutable-local control flow; the same fingerprint appears on every peer mega-class (`AppFacadeBootstrap` 2,823, `IndexingLoop` 1,955, `InferenceLifecycleManager` 1,486 after partial decomposition — see §"Relationship to peer mega-classes" below).

---

## Verified findings from investigation

Primary-source citations; static investigation only (live-stack verification deferred — see §A.12).

### Execute body structure (lines 195–910)

Seven phases, all sharing the same mutable hoists declared at lines 265–283:

| Phase | Lines | IO calls (file:line) |
|---|---|---|
| Parse & configuration | 198–312 | `commitOps.maybeRefresh()` :260; `clusterSnapshotSupplier.get()` :263; `computeQpp(query)` :288; `commitMetadataSupplier.get()` :307; `activeGenerationSupplier.get()` :317 |
| Early exits | 328–401 | none new |
| Input preparation | 342–406 | `expandEntityFilters(...)` :344; `bgeM3Encoder.encode(...)` :350 (if available); `prepareQueryVector(...)` :360; `prepareSpladeWeights(...)` :365 |
| Retrieval — sparse-only OR composable | 408–663 | branch-dependent; 7 distinct IO call shapes |
| Degradation signalling | 666–723 | none new; computes `effectiveMode = deriveActualMode(...)` :710 |
| Chunk-aware merge | 725–823 | `getOrComputeCorpusProfile()` :745 (**second call** — first was :248 inside Phase 1); `mergeChunkResults(...)` :768 |
| Response building | 829–909 | `toGrpcResponseBuilder(...)` :832; `mergeEntityFacets(...)` :874; `metrics.recordSearch(...)` :902 |

### The two facet paths really do differ (existing 517 §A.8 verbatim)

Both paths compute facets, but they have observable behavioural differences:

| Aspect | Sparse-only path (lines 502–519) | Composable path (lines 678–705) |
|---|---|---|
| Source query | Reuses already-boosted `luceneQuery` (post-`applyBoostFilters`) | Builds a *fresh* `buildTextQuery` |
| Boost filters | Honoured | Intentionally omitted |
| Query syntax | Honours `runtimeSyntax` (LUCENE or SIMPLE) | SIMPLE unconditionally |
| Blank query | No short-circuit | Short-circuits on `queryString.isBlank()` |
| ParseException | Propagates | Swallowed to `log.debug` |

These are not duplicates. Any unification that picks one set of behaviours silently flips the other.

### Reason-code allowlist (verified against `GrpcSearchServiceReasonCodeContractTest.java:33–76`)

**8 embedding-compat codes** produced upstream by `EmbeddingCompatibilityController` and mapped at the orchestrator boundary:
`INITIALIZING`, `NO_EMBEDDING_MODEL`, `NEW_INDEX_NO_FINGERPRINT`, `LEGACY_INDEX_NO_FINGERPRINT`, `FINGERPRINT_MATCH`, `FINGERPRINT_MISMATCH`, `REBUILD_IN_PROGRESS`, `REBUILD_COMPLETED`.

**5 search-routing codes** emitted by the orchestrator for the vector/hybrid degradation path:
`UNKNOWN`, `EMBEDDING_COMPATIBILITY_BLOCKED`, `NO_EMBEDDING_SERVICE`, `EMBEDDING_GENERATION_FAILED`, `EMBEDDING_EXCEPTION`.

**11 chunk-merge codes** emitted by chunk-augmentation gating:
`APPLIED`, `SKIPPED_DISABLED`, `SKIPPED_EMPTY_BASE_RESULTS`, `SKIPPED_PAGINATED`, `SKIPPED_QUERY_SYNTAX`, `SKIPPED_SORT_NOT_RELEVANCE`, `SKIPPED_NO_CHUNK_DOCS`, `SKIPPED_SHORT_CORPUS`, `SKIPPED_UNKNOWN`, `SKIPPED_VECTOR_BLOCKED`, `SKIPPED_EMPTY_QUERY`.

**2 log-only codes** emitted via `Markers.append("reason_code", …)` only (not wire-visible):
`deprecated_mode_fallback` at `:208`; `splade_encoding_failed` at `:1841`.

### Volatile fields and their wiring

Six volatile fields declared on the class, all written via deferred-injection setters in `GrpcSearchService.java` (verified — not `KnowledgeServer.java`):

| Field | Setter line in SearchOrchestrator | Setter call site in `GrpcSearchService.java` | Read pattern |
|---|---|---|---|
| `embeddingProvider` | :161 | :157 | direct null-check at :1794 |
| `clusterSnapshotSupplier` | :165 | :218 | volatile-read-then-local-copy at :1603 |
| `activeGenerationSupplier` | :156 | :229 | direct `.get()` at :317 |
| `spladeEncoder` | :169 | :240 | volatile-read-then-local-copy at :1826 |
| `spladeIdfQueryEncoder` | :173 | :245 | volatile-read-then-local-copy at :1827 |
| `bgeM3Encoder` | :178 | :249 | volatile-read-then-local-copy at :350 |

Four use the local-copy idiom; two do not. The inconsistency is a known smell, but the deeper issue is that the deferred-injection pattern exists *at all* in a request-time class — it is a composition-root sequencing problem in `GrpcSearchService` leaking into orchestrator state.

### Existing sealed-sum dispatch in this codebase (canonical pattern)

The codebase already uses sealed sums with pattern-match `switch` as its canonical discriminated-dispatch idiom:

- `IndexWriteOperation` (permits `UpdateDoc` / `BatchUpdate` / `DeleteAll`) dispatched at `IndexingCoordinator.java:100–111`. **This is the reference pattern.** Data + dispatch; no methods on the variants.
- `HealthEventBody` (permits `LifecycleEvent` / `AssertedCondition` / `ThresholdState` / `UnknownEventBody`).
- `ShellAddress` (permits `Navigation` / `Invocation`).
- `IntentDispatchResult` (permits `Dispatched` / `Forwarded`).
- `RecoveryAction<T>` (permits `Proceed<T>` …).
- `StructuredDocument.Element` (permits `Heading` / `Paragraph` / `Table` / `PageBreak` / `ListBlock`).
- `ConsumerHook` (permits `Realized` …).

JDK 25 is pinned (`gradle/libs.versions.toml`); pattern-match `switch` over sealed interfaces is in active production use *without `--enable-preview`*. No tooling constraint blocks the design.

### Existing Planner-then-Executor precedent

`InstallPlanner` (`modules/configuration/src/main/java/io/justsearch/configuration/model/InstallPlanner.java`) is a **pure** function that computes an `InstallPlan` from registry + hardware; `DownloadExecutor` (`modules/ui/src/main/java/io/justsearch/ui/ai/install/DownloadExecutor.java`) is the effectful counterpart that executes the plan. The pattern this tempdoc proposes is the same pattern applied to search.

### Existing Coordinator-then-Ops precedent

`IndexingCoordinator` → `WritePathOps`: a thin coordinator owns the dispatch envelope and the serialisation invariant; the ops class owns the IO. The orchestrator-then-collaborator structure proposed below matches this shape.

### Peer mega-classes share the failure mode

Tempdoc 512 §B3 audit:

| File | LOC | Role | vs 1,000 LOC ceiling |
|---|---|---|---|
| `AppFacadeBootstrap.java` | 2,823 | Head composition root | 2.8× |
| `IndexingLoop.java` | 1,955 | Indexing state machine | 2.0× |
| `SearchOrchestrator.java` | 1,919 | Search policy decision tree | 1.9× |
| `InferenceLifecycleManager.java` | 1,486 | LLM lifecycle orchestrator | 1.5× (post partial decomposition) |

Three of the four (`IndexingLoop`, `SearchOrchestrator`, `InferenceLifecycleManager`) are *runtime-decision* classes that share the "complex decision expressed as control flow" pathology. The fourth (`AppFacadeBootstrap`) is a composition root and its correct shape is different — see §"Relationship to peer mega-classes" below. Tempdoc 516 §P2 already proposes a typed `enum LoopState` for `IndexingLoop`; tempdoc 518 is still circling. The principle this tempdoc names applies cleanly to the runtime-decision trio.

---

## The design — search decision as a complete value

### Principle

> **Complex decisions are values, not control flow.**
>
> The search decision tree is captured as a single immutable value before any retrieval IO begins. The executor's job is to dispatch on that value via an exhaustive pattern-match `switch` and honour the effects the value declares. The decision is testable, inspectable, loggable, and snapshot-able without running a search.

This is the same discipline `IndexingCoordinator` already applies to write operations and that `InstallPlanner`/`DownloadExecutor` applies to install plans. The redesign extends it to search.

### The four roles

| Role | Kind | Function |
|---|---|---|
| `SearchOrchestrator` | thin facade, zero logic | `SearchRequest → SearchResponse`; wires the other four |
| `SearchInputCapture` | the only IO-allowed pre-plan class | `SearchRequest → SearchInputs`; performs every pre-retrieval IO (refresh, snapshot, corpus probe, entity-filter expansion, *all encode calls*) and captures the results into an immutable record |
| `SearchPlanner` | **pure** function over the captured value | `SearchInputs → SearchDecision`; emits one complete decision; no IO, no volatile reads |
| `SearchExecutor` | retrieval IO + effect dispatch | `SearchDecision → SearchOutcome`; one pattern-match `switch`; honours declared effects |
| `SearchResponseBuilder` | **pure** projection | `(SearchOutcome, SearchDecision) → SearchResponse` |

The facade body collapses to four lines:

```java
SearchResponse execute(SearchRequest req, boolean allowEmb, String compatReason) {
    SearchInputs   inputs   = capture.capture(req, allowEmb, compatReason);
    SearchDecision decision = planner.plan(inputs);
    SearchOutcome  outcome  = executor.execute(decision, inputs);
    return responseBuilder.build(outcome, decision);
}
```

### The decision is one sealed sum

```text
sealed interface SearchDecision permits
    EmptyQueryDecision,    // bypass retrieval; return 0 hits
    BlockedDecision,       // vector-only path blocked by encode / compat
    SparseShortcut,        // sparse-only REQUEST: dedicated path with same-query facets + per-term correction
    MultiLegDecision { }   // general path: 1–3 legs (incl. BM25-only via hybrid degradation) + composable facets
```

The `SparseShortcut` vs `MultiLegDecision(Bm25Only)` distinction is load-bearing. The current code's sparse-only-request path (lines 408–521: `sparseOnlyRequest = wantSparse && !wantDense && !wantSplade`) honours request syntax (LUCENE or SIMPLE) for facets, propagates `ParseException`, has no blank-query short-circuit, and runs per-term correction (lines 473–500). The current code's composable-path BM25-only fallback (line 656, `else if (canSparse)`, reached *only* when a hybrid request loses its vector leg) forces SIMPLE syntax, swallows `ParseException`, short-circuits on blank queries, and does **not** run per-term correction. The redesign preserves both as distinct decisions:

- **`SparseShortcut`** — the user *requested* sparse-only. Same-query facets via `FromRetrievalQuery`; correction retry available.
- **`MultiLegDecision(LegSet.Bm25Only, ...)`** — the user requested hybrid, but vector encoding `Failed`. Composable facets via `FromFreshBm25`; no per-term correction.

`MultiLegDecision` carries a sealed `LegSet`:

```text
sealed interface LegSet permits
    Bm25Only, DenseOnly, SpladeOnly,
    Bm25Dense, Bm25Splade, DenseSplade,
    ThreeWay { }
```

`LegSet.Bm25Only` is reached only via the hybrid-degraded path — the sparse-only-request case lives in `SparseShortcut`. Each `LegSet` variant carries its leg-specific inputs (candidate limits, fusion weights, the encoded vector, the SPLADE weight map). **No methods on `LegSet` or `SearchDecision` variants.** Dispatch is via `switch` in the executor:

```text
return switch (decision) {
    case EmptyQueryDecision e   -> executor.handleEmpty(e);
    case BlockedDecision b      -> executor.handleBlocked(b);
    case SparseShortcut s       -> executor.runSparseShortcut(s, inputs);
    case MultiLegDecision m     -> switch (m.legs()) {
        case LegSet.Bm25Only b      -> executor.runBm25Only(m, b, inputs);
        case LegSet.DenseOnly d     -> executor.runDenseOnly(m, d, inputs);
        case LegSet.SpladeOnly p    -> executor.runSpladeOnly(m, p, inputs);
        case LegSet.Bm25Dense bd    -> executor.runBm25Dense(m, bd, inputs);
        case LegSet.Bm25Splade bs   -> executor.runBm25Splade(m, bs, inputs);
        case LegSet.DenseSplade ds  -> executor.runDenseSplade(m, ds, inputs);
        case LegSet.ThreeWay t      -> executor.runThreeWay(m, t, inputs);
    };
};
```

This is the same data-plus-dispatch shape as `IndexingCoordinator.java:100–111`. The 8-way cascade dissolves: not because the cardinality of leg combinations changed (it didn't — the corpus still supports `2³ − 1 = 7` non-empty subsets), but because the cardinality is enumerated *in one place as types*, not scattered as `if (canSparse && canDense && canSplade)` conjunctions in 8 successive branches sharing mutable state.

Adding a new leg-shape (e.g. a fourth retriever) requires:
1. one new `LegSet` variant;
2. compiler errors on every non-exhaustive `switch` until the new case is handled.

There is no other place to forget to update.

### Encode is a captured capability, not a phase boundary

The previous design had a `RequestedLegs → encode → ResolvedLegs.from(requested, encoded)` two-step. The argument was: encode is IO, the planner cannot run encode, so the planner can only declare *intent* and the executor resolves after encode.

This is structurally wrong. The two-step splits the decision across two phases; the executor inherits half the decision-making (because `ResolvedLegs.from(...)` is itself a decision). The redesign moves encode into `SearchInputCapture` alongside the other pre-retrieval IO. Crucially, each modality's encoding result is a **sealed sum that distinguishes three states**: *not requested by the pipeline*, *requested and succeeded*, and *requested and failed (with reason)*. Conflating "not requested" with "failed" — as a single `Optional<T>` would — loses the discriminator that tells the planner whether to emit a degradation reason code.

```text
sealed interface VectorEncoding permits NotRequested, Success, Failed { }
sealed interface SpladeEncoding permits NotRequested, Success, Failed { }
sealed interface BgeM3Encoding  permits NotRequested, Success, Failed { }
record Success(/* modality-specific payload */)             implements VectorEncoding, SpladeEncoding, BgeM3Encoding { }
record Failed(SearchReasonCode reason)                       implements VectorEncoding, SpladeEncoding, BgeM3Encoding { }
record NotRequested()                                        implements VectorEncoding, SpladeEncoding, BgeM3Encoding { }

record EncodingResults(VectorEncoding vector, SpladeEncoding splade, BgeM3Encoding bgeM3) { }

record SearchInputs(
    QueryShape           query,           // normalised; SensitiveQuery-wrapped at log boundary
    RuntimeSearchFilters filters,         // entity-expanded
    EntityClusterSnapshot clusterSnapshot,
    CorpusCapabilities   corpus,          // hasChunkDocs, isShortCorpus, medianTokenCount, chunkRate
    EncodingResults      encoding,        // three states per modality
    PipelineConfig       pipeline,
    Pagination           pagination,
    Projection           projection,
    Sort                 sort,
    FacetRequest         facets,
    DebugFlags           debug,
    EmbeddingCompatBoundary compatBoundary  // 8-code compat string mapped at the boundary
) { }
```

(For brevity the sketch shows `Success`/`Failed`/`NotRequested` implementing all three sealed interfaces; in real code each modality has its own permitted-set, and the payload of `Success` is modality-typed: `float[]` for `VectorEncoding`, `Map<String,Float>` for `SpladeEncoding`, `BgeM3Output` for `BgeM3Encoding`.)

**The load-bearing claim: reason codes are properties of encoding outcomes, not of decision variants.** A `Failed` modality carries its own `SearchReasonCode` (one of `EMBEDDING_GENERATION_FAILED`, `NO_EMBEDDING_SERVICE`, `EMBEDDING_EXCEPTION`, `EMBEDDING_COMPATIBILITY_BLOCKED`). The same code can attach to a `BlockedDecision` (vector-only request that lost its vector) *or* a `MultiLegDecision.hybridFallback` (hybrid request degraded to text-only) without duplication: both decisions carry the same `VectorEncoding.Failed` value forward. The wire emission reads `EncodingResults` from the decision once.

With encoding captured this way, the planner sees a complete picture. A vector-only request whose vector encoding is `Failed(EMBEDDING_GENERATION_FAILED)` produces a `BlockedDecision` that *carries the same `Failed` value* — directly, no two-step. A hybrid request with `VectorEncoding.Success` and `SpladeEncoding.Failed(NO_EMBEDDING_SERVICE)` produces a `MultiLegDecision(Bm25Dense, ...)` whose `spladeSkip` field references the `Failed` value. A modality that is `NotRequested` produces no reason code at all — it was never asked for, so no degradation occurred.

The planner is pure over `SearchInputs`. The capture step is IO-heavy and *that's fine* — IO has a defined boundary now.

### Reason codes are typed and declared in the decision

`SearchReasonCode` is an enum, package-private to `services/`. Every emission site takes the enum, not a string.

```text
enum SearchReasonCode {
    // 8 compat (mapped at boundary via fromCompatString)
    INITIALIZING, NO_EMBEDDING_MODEL, NEW_INDEX_NO_FINGERPRINT, ...
    EMBEDDING_COMPATIBILITY_UNKNOWN,  // fall-through for unrecognised compat strings
    // 5 routing
    UNKNOWN, EMBEDDING_COMPATIBILITY_BLOCKED, NO_EMBEDDING_SERVICE,
    EMBEDDING_GENERATION_FAILED, EMBEDDING_EXCEPTION,
    // 11 chunk-merge
    APPLIED, SKIPPED_DISABLED, SKIPPED_EMPTY_BASE_RESULTS, ...
}
```

**The codes are properties of the data, not effects of the executor.** Each `SearchDecision` variant carries the *outcomes* (encoding results, chunk-merge directives) whose data shape determines the wire-visible code. The variants themselves do not enumerate codes:

```text
record BlockedDecision(VectorEncoding.Failed encodingFailure,
                       EmbeddingCompatBoundary compatBoundary) { }

record MultiLegDecision(
    LegSet legs,
    FusionStrategy fusion,
    Optional<VectorEncoding.Failed> hybridFallback,   // present when hybrid request lost a leg
    Optional<SpladeEncoding.Failed> spladeSkip,        // present when SPLADE encoding failed
    ChunkMergeDirective             chunkMerge,
    Optional<FacetCompute>          facets,
    ...
) { }

sealed interface ChunkMergeDirective permits Skip, EligibleApply { }
record Skip(SearchReasonCode reason) implements ChunkMergeDirective { }      // pre-commit: 10 of 11 reasons
record EligibleApply(ChunkMergeInputs inputs) implements ChunkMergeDirective { }
```

`ChunkMergeDirective` is honest about its one runtime escape. The planner pre-commits 10 of the 11 chunk-merge reason codes (`SKIPPED_DISABLED`, `SKIPPED_PAGINATED`, `SKIPPED_QUERY_SYNTAX`, `SKIPPED_SORT_NOT_RELEVANCE`, `SKIPPED_NO_CHUNK_DOCS`, `SKIPPED_SHORT_CORPUS`, `SKIPPED_VECTOR_BLOCKED`, `SKIPPED_EMPTY_QUERY`, `SKIPPED_UNKNOWN`, plus `APPLIED` when it later succeeds). The 11th, `SKIPPED_EMPTY_BASE_RESULTS`, depends on retrieval having produced zero hits — which the planner cannot know. When the executor encounters `EligibleApply` with empty base results, it records `SKIPPED_EMPTY_BASE_RESULTS` on the `SearchOutcome` (not on the decision). This is a deliberate concession to physical reality, not a design weakness: the planner pre-commits every decision it *can*, and one runtime predicate escapes.

The executor's `handle*` methods walk the decision and emit. There is one emission site per code category; the codes themselves live in the outcome data carried by the decision, not in the imperative logic that ran. A reviewer can answer "what reason code does query X with corpus Y produce?" by inspecting the `SearchDecision` value alone (plus, for chunk-merge, the runtime base-results check).

This is the **minimum-surface promotion** (C-018 §X.11): `SearchReasonCode` stays in `services/` until a second consumer appears.

### Capture-once enforced by dependency direction, not by convention

The current code re-reads `getOrComputeCorpusProfile()` at line 745 even though it was already read at line 248. `clusterSnapshotSupplier.get()` is called once today (line 263), but nothing prevents future code from calling it again. The "capture once, use twice" invariant is documentation.

The redesign enforces it via package structure and an ArchUnit rule:

```text
modules/worker-services/src/main/java/io/justsearch/indexerworker/services/
├── SearchOrchestrator.java           ← facade
├── input/
│   └── SearchInputCapture.java       ← the ONLY class allowed to depend on
│                                        CommitOps, IndexCountOps,
│                                        EntityClusterSnapshot suppliers,
│                                        encoders, IS_CHUNK probes
├── plan/
│   ├── SearchPlanner.java            ← pure; depends only on SearchInputs
│   └── SearchDecision.java           ← sealed sum
├── execute/
│   ├── SearchExecutor.java           ← retrieval IO + effect dispatch
│   └── leg/                          ← one file per LegSet variant
└── respond/
    └── SearchResponseBuilder.java    ← pure projection
```

ArchUnit rules added to `IndexerWorkerGuardrailsTest.java`. The codebase uses ArchUnit 1.4.1 (verified in `gradle/libs.versions.toml`) with the `noClasses()` idiom (verified at `IndexerWorkerGuardrailsTest.java:17–39`). The first rule, written in that exact idiom:

```java
@ArchTest
static final ArchRule plannerDoesNoIo =
    noClasses()
        .that()
        .resideInAnyPackage("io.justsearch.indexerworker.services.plan..")
        .should()
        .dependOnClassesThat()
        .haveSimpleName("CommitOps")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("IndexCountOps")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("SpladeEncoder")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("SpladeIdfQueryEncoder")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("BgeM3Encoder")
        .orShould()
        .dependOnClassesThat()
        .haveSimpleName("EmbeddingProvider");
```

Three further rules by analogy (same `noClasses().resideInAnyPackage(...).should().dependOnClassesThat()...` shape):

2. **`services.respond.*` no IO**: same predicate, scoped to the response-builder package.
3. **Encoder/snapshot/commit imports confined to `services.input.*`**: `noClasses().resideOutsideOfPackage("..services.input..").should().dependOnClassesThat().haveSimpleName("EmbeddingProvider")` (and similarly for `SpladeEncoder`, `BgeM3Encoder`, `EntityClusterSnapshot`-suppliers, `CommitOps`).
4. **`SearchDecision` and `LegSet` permitted-types are data-only**: `noClasses().that().areAssignableTo(SearchDecision.class).orImplement(SearchDecision.class).should().beInterfaces().orShould().haveMethodsThatAreNot(/* records' synthetic accessors */)` — the implementation can lean on JLS records (no instance methods other than accessors) to defend this structurally.

The capture-once invariant becomes a compile-time check, not a prose rule. Future code that re-reads `getOrComputeCorpusProfile()` from inside the executor fails the build because `IndexCountOps` is not importable in `services.execute.*`.

### Operator order falls out of data flow

The current `Correction → Facets → ChunkAugmentation → Trim` order is load-bearing: chunk augmentation reads the (possibly corrected) query. Today the order is enforced by comment ("the order is in code, not in config").

The redesign enforces it by signature:

```text
// services.execute package
public record QueryAfterCorrection(String text) {
    QueryAfterCorrection { }            // package-private canonical constructor
}

public record ChunkMergeOp(QueryAfterCorrection query, ...) { }
```

`QueryAfterCorrection`'s constructor is package-private; only `CorrectionRetryOp` (residing in the same `services.execute` package) may construct one. Code in `services.respond` or any other package cannot instantiate the wrapper — the only way to obtain one is to receive it from `CorrectionRetryOp.run(...)`. To invoke `ChunkMergeOp`, you need a `QueryAfterCorrection`. The compiler enforces ordering. Comments become superfluous.

This generalises: any future post-retrieval operator that depends on another operator's output names the dependency in its input type, with the wrapper's constructor scoped to the producing package. The order is in types, not in Java sequence.

### Decision summary for observability

The "decision is testable, inspectable, loggable, snapshot-able" claim needs a primitive to back it. `SearchDecision` exposes one method:

```text
default Map<String, Object> summary();
```

`summary()` returns a privacy-safe, structured map of the decision's discriminator fields — never the query text, never the SPLADE weight map, never raw filter values. Stable keys (`decisionKind`, `legs`, `fusionStrategy`, `chunkMergeKind`, `chunkMergeReason?`, `hybridFallbackReason?`, `spladeSkipReason?`, `facetSource?`, `correctionEligible?`). The `EffectScope` records `summary()` as attributes on the request-level OTel span at scope open; the planner→executor boundary emits the same map as a structured debug log entry. Diagnostic exporters (e.g. `/api/debug/state`) can serialise it.

This is the *one* method permitted on `SearchDecision` variants. It is privacy-aware, observability-oriented, and produces no side effects. It does not violate the "data + dispatch, no behaviour" rule because it returns a value rather than executing logic; the dispatch is still the executor's `switch`.

### Two facet paths preserved, discriminated by decision variant

The two facet behaviours (sparse-only uses the boosted retrieval query; composable builds fresh SIMPLE BM25, omits boosts, swallows ParseException, blanks short-circuit) are different observable behaviours. They are not duplicates; they cannot be unified.

The discriminator lives on the decision:

- `SparseShortcut` carries `Optional<FacetCompute.FromRetrievalQuery>`.
- `MultiLegDecision` carries `Optional<FacetCompute.FromFreshBm25>`.

`FacetCompute` is a sealed sum with two variants; each carries the inputs and policy it needs. The executor's facet handler is a `switch` over `FacetCompute`. No implicit branch; no shared code path that silently flips behaviour on the wrong branch.

### Volatile-field discipline — separable Phase 2

Six volatile fields exist on the current orchestrator because `GrpcSearchService` constructs `SearchOrchestrator` before some dependencies are wired (encoders load asynchronously). This is a **composition-root sequencing problem in `GrpcSearchService`**, not a search-execution-design problem. The redesign can land *without* fixing it (the new orchestrator inherits the six setters) or *with* it (consolidating to a holder). Calling it out as a separable Phase 2 keeps the scope of this tempdoc focused on execution structure.

The illustrative shape, for whichever implementation slice picks it up (this tempdoc or a sibling — e.g. a hypothetical 522 on composition-root sequencing):

```text
final class SearchCollaboratorsHolder {
    private volatile Snapshot snapshot;   // ONE volatile, ONE atomic swap
    record Snapshot(
        EmbeddingProvider              embeddingProvider,
        Supplier<EntityClusterSnapshot> clusterSnapshotSupplier,
        Supplier<String>               activeGenerationSupplier,
        SpladeEncoder                  spladeEncoder,
        SpladeIdfQueryEncoder          spladeIdfQueryEncoder,
        BgeM3Encoder                   bgeM3Encoder
    ) { }
    Snapshot snapshot() { return snapshot; }      // single volatile read
    void updateWith(...) { ... }                  // single mutation point
}
```

`SearchInputCapture` would call `holder.snapshot()` once per request; the resulting `Snapshot` flows downstream as part of `SearchInputs`. The `set*` setters in `GrpcSearchService` (lines 157/218/229/240/245/249) call `holder.updateWith(...)`; the orchestrator no longer carries deferred-injection state. Six volatile fields with two inconsistent read patterns collapse to one volatile field with a uniform read pattern.

This is recorded here so the redesign's `SearchInputs` shape can accommodate the `Snapshot` field today, even if the holder migration lands later. Without Phase 2, the orchestrator-internal volatile fields remain; the dependency-direction ArchUnit rule still works (it constrains *what may import what*, not *how late-bound the values are*).

### Facade body is zero-logic, effects scope-bracketed

```text
final class SearchOrchestrator {
    SearchResponse execute(SearchRequest req, boolean allowEmb, String compatReason) {
        SearchInputs   inputs   = capture.capture(req, allowEmb, compatReason);
        SearchDecision decision = planner.plan(inputs);
        try (var scope = effects.open(req, decision)) {  // MDC stage_id, span parents
            SearchOutcome outcome = executor.execute(decision, inputs);
            return responseBuilder.build(outcome, decision);
        }
    }
}
```

`EffectScope` opens the request-level MDC entries and the parent OTel context once, in a try-with-resources. The executor's phase spans are children of the scope's context — no `Context.current()` reliance, no MDC leak on exceptional paths.

### Response field sources: `SearchOutcome` vs `SearchDecision`

The response builder's signature is `(SearchOutcome, SearchDecision) → SearchResponse`. For multi-leg decisions, most fields derive from `SearchOutcome` (the executor produced hits, timings, and runtime-detected state). For `EmptyQueryDecision` / `BlockedDecision`, the outcome is mostly empty and most fields derive from the decision — the builder must be decision-aware.

The split:

| `SearchResponse` proto field | Source for `MultiLeg` / `SparseShortcut` | Source for `EmptyQuery` / `Blocked` |
|---|---|---|
| `hits[]`, `total_hits`, `took_ms`, `next_cursor` | `SearchOutcome` | empty / 0 / 0 / `""` |
| `effective_mode` | `SearchOutcome` (derived from legs executed) | `SearchDecision` (e.g. `"TEXT"` default, or upstream-supplied) |
| `vector_blocked`, `vector_blocked_reason` | `SearchOutcome` (when hybrid runs and a leg was blocked) | `SearchDecision.encodingFailure` for `Blocked`; absent for `Empty` |
| `hybrid_fallback`, `hybrid_fallback_reason` | `SearchDecision.hybridFallback` (`MultiLegDecision`) | absent |
| `correction_applied`, `corrected_query` | `SearchOutcome` (set when correction retry fired) | absent |
| `splade_executed`, `splade_skip_reason` | `SearchOutcome` (for executed); `SearchDecision.spladeSkip` (for skipped) | absent |
| `chunk_merge_applied`, `chunk_merge_reason` | `SearchOutcome` (for `EligibleApply` → `APPLIED` / `SKIPPED_EMPTY_BASE_RESULTS`); `SearchDecision.chunkMerge.reason` (for `Skip`) | from `Skip` reason if present |
| `branch_fusion_strategy`, `branch_fusion_contributed` | `SearchOutcome` | absent |
| `qpp_signals` (`max_idf`, `avg_ictf`, `query_scope`) | `SearchOutcome` (computed in capture; passed via outcome) | absent for `Blocked`; `query_scope` may be set for `Empty` |
| `facets` | `SearchOutcome` | absent |
| `component_timing` (8 sub-fields) | `SearchOutcome` | mostly 0 for early-exit |

Explicit split prevents projection logic from re-fragmenting across the builder; each field has one source per decision shape.

---

## What is consistent with existing codebase patterns

This design extends what is already there. Every primitive has a precedent inside this codebase:

| Pattern | Existing instance | Source |
|---|---|---|
| Sealed sum + pattern-match `switch` | `IndexWriteOperation` (canonical) | `IndexingCoordinator.java:100–111` |
| | `HealthEventBody`, `ShellAddress`, `IntentDispatchResult`, `RecoveryAction`, `StructuredDocument.Element`, `ConsumerHook` | various |
| Snapshot record captured at request entry | `EntityClusterSnapshot`, `ConfigSnapshot`, `LifecycleSnapshot`, `EncoderProfileSnapshot` | various |
| Pure planner + effectful executor | `InstallPlanner` (pure) → `DownloadExecutor` (effectful) | `modules/configuration/.../InstallPlanner.java`, `modules/ui/.../DownloadExecutor.java` |
| Thin coordinator → Ops collaborators | `IndexingCoordinator` → `WritePathOps` | `modules/adapters-lucene/.../runtime/` |
| Package-private collaborator decomposition | `LuceneIndexRuntime` → 11; `SummaryController` → 6; `GrpcSearchService` → 4 | `class-size-standard.md` decomposition table |
| ArchUnit dependency-direction enforcement | `AppServicesWorkerGuardrailsTest` (no ad-hoc env reads; no `MappedByteBuffer` outside `MainSignalBus`) | `modules/app-services/.../AppServicesWorkerGuardrailsTest.java` |
| MetricCatalog telemetry contract (ADR-0027) | Existing single emission site `metrics.recordSearch(...)` | `SearchOrchestrator.java:902` |

**ADR-0027 (MetricCatalog).** The existing `metrics.recordSearch(...)` call at `SearchOrchestrator.java:902` moves to `SearchResponseBuilder` — the one collaborator with access to both `SearchOutcome` (timing, hit count, fusion data) and `SearchDecision` (mode, leg set, degradation context). No new metrics are declared; no `MetricDefinition` is added; the existing typed catalog binding is preserved verbatim. The emission stays a single call at the end of response construction.

There is no new substrate. There is no new module. There is no new framework. The redesign re-uses the codebase's mature patterns and binds the result with the codebase's existing enforcement mechanism (ArchUnit).

---

## Why this design defends long-term

| Change vector | Compile-time defence |
|---|---|
| New retrieval mode (e.g. a 4th retriever) | One new `LegSet` variant; one new `MultiLegDecision`-internal switch arm; compiler errors on every non-exhaustive `switch` until handled. |
| New degradation reason | One new `SearchReasonCode` enum member; compiler flags emission-site usages (or absence of `case` arms in the executor's handler `switch`). |
| New post-retrieval operator with ordering constraint | Operator declares its input dependency as a typed wrapper (`QueryAfterCorrection`, etc.); calling out of order is a compile error. |
| New environmental-state input | Add a field to `SearchInputs` + a corresponding capture site in `SearchInputCapture`; the planner now has access; the dependency-direction ArchUnit rule prevents the field from being captured anywhere else. |
| Re-introducing a 1,900-LOC mega-method | ArchUnit rule (per tempdoc 516 §P4): any class in `worker-services` over 1,000 LOC fails the build. Grandfather the current orchestrator during the migration window only; remove the grandfather entry when the redesign lands. |
| Re-introducing inline reason-code emission | Same ArchUnit rule that forbids the encoder imports outside `services.input.*`: forbid `Markers.append("reason_code", ...)` and `setReasonCode(String)` outside the executor's effect-emission method. |

---

## What this design rejects (firmly)

ADR-0014 endorses the decision-tree shape and rejects the pipeline-engine shape. This design stays inside that boundary:

- **No DAG / pipeline-engine / stage registry / SPI / ServiceLoader / annotation-based operator chaining.** ADR-0014 and tempdoc 512 §F1 are explicit: speculative generality is the recurring failure mode in this codebase. Rejected.
- **No methods on `SearchDecision` or `LegSet` variants.** Data + dispatch. The executor's `switch` is the engine. A `decision.execute(...)` method would resurrect the strategy-with-behaviour pattern ADR-0014 rejected.
- **No `List<PostRetrievalOp>` dynamic chain.** Ordering is enforced by input types, not by configuration.
- **No new module.** Everything lives in `modules/worker-services/services/`. The package-private collaborator pattern is the structural unit.
- **No promotion of `SearchReasonCode` to `app-api` or `ipc-common`.** Minimum-surface (C-018): stay inside `services/` until a second consumer outside `services/` emerges. The cross-module wire-shape backstop is the existing contract test.
- **No `SearchPlan.Builder`.** A builder for a sum-type defeats the purpose. Construction is direct (`new BlockedDecision(...)`, `new MultiLegDecision(...)`).
- **No `SearchContext` god-object.** `SearchInputs` is a record with named fields; phases take what they need from it.
- **No per-stage budgets, deadlines, or cancellation logic.** Out of scope for execution design; cross-cutting concern handled elsewhere.
- **No renaming or repackaging of the `Ops` primitives.** `TextQueryOps`, `ReadPathOps`, `HybridSearchOps`, `ChunkSearchOps`, `FacetingEngine`, `DocumentFieldOps`, `IndexCountOps`, `CommitOps`, `QueryFilterBuilder`, `HybridFusionUtils` keep their names and roles. They are the IO layer the executor calls into.
- **No promotion of chunk retrieval to a peer of parent retrieval.** Chunk merge stays a post-retrieval operator on the decision.

---

## Invariants preserved

All seven entries from `docs/reference/contracts/search-pipeline-invariants.md` plus the orchestrator-internal invariants the current implementation upholds:

1. **Reason-code allowlist.** Wire-shape compatibility preserved; `GrpcSearchServiceReasonCodeContractTest` continues to gate. The enum strengthens the typing without changing the wire vocabulary.
2. **Hybrid weight semantics.** `hybridWeight` clamp moves to a single utility inside `services.execute/`. Behaviour preserved.
3. **Chunk-collapse policy.** Collapse keys + multipliers + collapse-at-search owned by the chunk-merge operator.
4. **Mode-routing exclusivity (HYBRID/TEXT/VECTOR).** Enforced by `SearchDecision` being a sealed sum and `LegSet` being a sealed sum; the executor's `switch` is exhaustive at compile time. The set of executable leg combinations is enumerated in types.
5. **Soft-boost discipline (ADR-0016).** `QueryFilterBuilder.applyBoostFilters` unchanged; QU signals enter as SHOULD clauses.
6. **Fuzzy correction policy.** Zero-hit retry fires at most once; per-term correction at most once. Single-pass operator inside `SparseShortcut`'s execution. (Not reachable from `MultiLegDecision(LegSet.Bm25Only)` — preserved per current code: composable-path BM25 fallback does not run correction.)
7. **Privacy redaction.** `SensitiveQuery` wrapping owned by `SearchInputCapture`; every log call site downstream takes a `SensitiveQuery`, not a raw `String`.
8. **Parallel-leg correctness.** Each multi-leg switch-arm owns its `Executors.newVirtualThreadPerTaskExecutor()` + `Context.with(retrievalSpan)` propagation — identical structural shape to today.
9. **Blocked combinations** from `search-pipeline-invariants.md`:
   - HYBRID blocks query expansion (Head-side, unchanged);
   - stemming/fuzzy sequential (single-pass correction op);
   - explicit filters bypass QU boost (Head-side, `KnowledgeHttpApiAdapter.doSearch` check);
   - soft-boost never produces zero result (`QueryFilterBuilder` primitive);
   - FilterNormalization fires on both paths (Head-side);
   - entity/metadata filters never apply to chunks directly (`buildChunkFilterQuery` / `buildFilterQueryOnly` primitives, unchanged);
   - entity-facet `_raw` strip at MCP (Head/MCP-side, unchanged).
10. **`effectiveMode` derivation.** Computed from the executed `LegSet` post-resolve, not pre-committed by the planner. The decision can downgrade legs (when encode fails) before the executor runs — the value the response carries reflects what actually executed.
11. **Pre-retrieval environmental state captured-once.** `EntityClusterSnapshot`, `CorpusCapabilities`, encode outputs all captured at `SearchInputCapture` time. ArchUnit rule prevents re-reads elsewhere.
12. **OTel topology.** `search/retrieval` parents leg branches + primary fuse; `search/chunk_merge` sibling of retrieval; branch-fuse child of chunk merge. The `EffectScope` parametrises every span by explicit parent context — no `Context.current()` reliance.
13. **MDC `stage_id` semantics.** Set without clearing inside `EffectScope` — cleanup is the outer `MdcContext.request()` scope's job. Unchanged.
14. **Operator order.** Correction → Facets → ChunkAugmentation → Trim. Encoded by typed input wrappers (`QueryAfterCorrection`).
15. **Volatile-field read patterns.** Current state preserved if the redesign lands without Phase 2 (the orchestrator still has six volatile fields, but they are now read once at request entry and the values flow through `SearchInputs`). Phase 2 (the `SearchCollaboratorsHolder` migration — see "Volatile-field discipline — separable Phase 2") reduces to one volatile field with a uniform read pattern.

---

## Relationship to peer mega-classes

`SearchOrchestrator` is one of *three runtime-decision* classes that share a structural pathology — the four-class group of tempdoc 512 §B3 includes `AppFacadeBootstrap`, but that class is a composition root, not a runtime-decision class. The principle this tempdoc names applies to runtime decisions; `AppFacadeBootstrap`'s correct shape ("declarative wiring graph") is a related-but-distinct concern that belongs in a separate tempdoc.

| Class | Decision domain | The correct value-shape |
|---|---|---|
| `IndexingLoop.java` (1,955 LOC) | indexing state machine | typed `enum LoopState { IDLE, RUNNING, PAUSED }` + sealed `LoopTransition` — tempdoc 516 §P2 already names this |
| `SearchOrchestrator.java` (1,919 LOC) | search-request decision tree | sealed `SearchDecision` — this tempdoc |
| `InferenceLifecycleManager.java` (1,486 LOC) | inference mode transitions | sealed mode-transition events — tempdoc 518 still circling |

The shared principle: **replace control flow over mutable state with a typed value; dispatch via pattern-match.** The specific value-shape varies by domain — a state machine has a typed state enum; a lifecycle has a sealed sum of transition events; request routing has a sealed sum of decisions. The shared *failure mode* is "decision expressed as mutable locals + conditional branches", and the shared *cure* is "make the decision a value before dispatching."

Tempdoc 516 §P2 names the same shape under a different label. Verbatim (`docs/tempdocs/516-indexingloop-size.md:196–211`):

> Replace `String currentState` + the `STATE_*` constants with `enum LoopState { IDLE, RUNNING, PAUSED }` and a small number of named transition methods. The state graph becomes derivable from the type system; future audits of state-related behavior can be answered by grepping over the enum and transition method names rather than by reading the entire loop body.

"Type-system visibility" (516's phrase) and "decision as a value" (this tempdoc's phrase) describe the same discipline applied to two different domains. The framings are equivalent for the runtime-decision case.

The composition-root case (`AppFacadeBootstrap`) is genuinely different: its problem is "procedural staging of a wiring graph" rather than "control flow over runtime mutable state." A declarative-graph fix there does not require the sealed-sum dispatch pattern this tempdoc adopts; it requires a different value-shape (a typed dependency graph, with construction emitting the graph rather than executing wiring effects). That deserves its own tempdoc.

The package-private-collaborator pattern (`class-size-standard.md` decomposition table) is *complementary*, not alternative. It splits a class along concern lines and lets the facade delegate. The values-as-decision principle splits along *decision-vs-effect* lines and lets a sum-type discriminate. Both apply here: the four roles (`Capture` / `Planner` / `Executor` / `ResponseBuilder`) are package-private collaborators; the central data structure connecting them is `SearchDecision`. Without the value-shape, the collaborator decomposition re-fragments the decision logic across collaborators and the pathology returns.

---

## Risks

| Risk | Mitigation |
|---|---|
| Re-introducing the deleted DAG / pipeline-engine pattern under a different name. | `SearchDecision` variants are data-only (no methods). `switch` is the engine. A reviewer can grep for `interface .* Stage`, `interface .* Pipeline`, `ServiceLoader`, `List<.* Op>`, and `.execute()` on `LegSet`/`SearchDecision` types and reject on sight. The ADR-0014 boundary is named in the rejected-list above. |
| Substrate-without-consumer (C-018). | Every type has a named consumer at compile time, inside `worker-services`. `SearchReasonCode` consumed by every emission site; `SearchDecision` by the executor; `SearchInputs` by the planner; `SearchOutcome` by the response builder; `LegSet` variants by the leg-handlers. No forward-compat substrate. |
| Pass-8 independent review deferred. | Substrate-shipping per slice 447 §X.11 (new sealed sum-types, new enum, new package layout, new ArchUnit rules). Pass 8 mandatory; named in the checklist below. |
| Static-green ≠ live-working. | Tier-3 verification required against a live stack. Live-stack experiments E2–E6 (deferred from the previous design; backend stuck in `STARTING` during the investigation pass) must run before merge. |
| Facet-count regression from the relocation of the two facet behaviours. | The two `FacetCompute` variants are typed; an ArchUnit rule can assert that `SparseShortcut` only carries `FromRetrievalQuery` and `MultiLegDecision` only carries `FromFreshBm25`. Test matrix: (a) LUCENE-syntax sparse with facets; (b) sparse + boost filters + facets; (c) HYBRID + facets + blank query; (d) HYBRID + facets + non-blank query; (e) HYBRID + facets + deliberately malformed SIMPLE query. |
| Chunk-augmentation collapse-semantics flip. | `ChunkMergeOp` tests must exercise: chunks-collapsed-to-parent, zero-chunk-fallback, retry-on-saturation, score-max preservation. The 11 chunk-merge reason codes each map to a typed `ChunkMergeDirective` variant; the contract test continues to gate. |
| OTel topology drift breaking Layer-4 projections. | `EffectScope` parametrises every span by explicit parent context. Tests assert span parent-child relationships using OTel SDK test exporters. |
| `EntityClusterSnapshot` reference drift across phases. | `SearchInputs` is immutable; the snapshot is captured once at `SearchInputCapture` and threaded through. ArchUnit rule prevents re-reads elsewhere. |
| Deferred-injection setter wiring missed during the holder migration (Phase 2 only). | `SearchCollaboratorsHolder` is the single mutation point. A unit test asserts that every setter call site in `GrpcSearchService` reaches the holder. The 6 currently-volatile fields become accessor methods on the holder's `Snapshot` record; missing wiring fails at compile time. Risk does not apply if Phase 2 is deferred to a sibling tempdoc. |
| Encoding-failure handling regression. | Encode outcomes captured as `VectorEncoding`/`SpladeEncoding`/`BgeM3Encoding` sealed sums (states: `NotRequested`, `Success`, `Failed(SearchReasonCode)`). The `Failed` variant carries its reason code as data; unit tests can fabricate `Failed(EMBEDDING_GENERATION_FAILED)` etc. and assert the response shape without running encoders. |

---

## Open questions

### Strategic — requires user direction

1. **`SearchCollaboratorsHolder` scope.** Land Phase 2 (the holder migration) as part of this slice, or extract to a sibling tempdoc (e.g. 522 — composition-root sequencing)? The redesign is correct either way; the trade-off is "scope discipline" (defer) vs "no half-finished volatile-field cleanup" (in-scope). Recommendation: defer to a sibling tempdoc. The orchestrator redesign is a substantial slice on its own; the volatile cleanup is a Head-side composition concern with its own surface (`GrpcSearchService` setters).
2. **Cross-class principle promotion.** Promote *"replace control flow over mutable state with a typed value"* to `.claude/rules/agent-lessons.md` as a named substrate-discipline handle, or keep it tempdoc-scoped with cross-references from 516/518? The agent-lessons file is loaded every session; CLAUDE.md "Before Appending to CLAUDE.md" argues for restraint (broad-applicability gate). Recommendation: keep tempdoc-scoped; promote only if a fourth runtime-decision tempdoc independently arrives at the same conclusion.
3. **Peer-tempdoc alignment.** Re-frame 516 (IndexingLoop) and 518 (InferenceLifecycleManager) against the same principle *before* any of the three implementation slices fires? Recommendation: yes — coordinate the framing once across the runtime-decision trio, then each slice ships independently. Tempdoc 516 §P2 already names the same shape under "type-system visibility"; alignment is mostly verbatim quoting + cross-references.

### Tactical — implementation-slice decision

4. **`SearchReasonCode` location.** Stay in `services/` (this tempdoc's default), or move to a small `worker-search-types` sub-package? Minimum-surface rule says stay; promote only when a second consumer outside `services/` emerges.
5. **ArchUnit rule scope.** The four `noClasses()` rules in §"Capture-once enforced …" are bread-and-butter ArchUnit. Forbidding `Markers.append("reason_code", …)` outside the executor's emission method is *method-call-level* — better as a custom PMD rule than as ArchUnit (which reasons about class dependencies, not call-site locations). Recommendation: ArchUnit for the four dependency-direction rules; PMD for the call-site rule if warranted.
6. **`RagContextOps` (1,525 LOC, same module, separately decomposed origin).** Own tempdoc under a similar framing? Recommendation: separate tempdoc with cross-reference; the concerns (search-execution vs RAG-context assembly) don't overlap enough to share a decision tree. Verified independent (§A.9).

---

## What this tempdoc is *not*

- It is not a slice. No implementation tasks attach.
- It is not a LOC target. Size is the symptom, not the problem.
- It is not a "decompose for size" plan. The decomposition (`Capture`/`Planner`/`Executor`/`ResponseBuilder`) is the *consequence* of treating the decision as a value; if you decompose without the value-shape, the pathology returns.
- It is not a generality. The design rejects every speculative-generality temptation ADR-0014 deleted.

---

## Verification checklist (for whichever slice implements this)

1. `./gradlew.bat :modules:worker-services:build -x test`
2. `./gradlew.bat :modules:worker-services:test :modules:adapters-lucene:test :modules:indexer-worker:test`
3. `./gradlew.bat spotlessApply`
4. **`GrpcSearchServiceReasonCodeContractTest`** — reason-code allowlist backstop. Must include `EMBEDDING_COMPATIBILITY_UNKNOWN` as the 25th member.
5. **`KnowledgeHttpApiAdapterHarmfulCombinationsTest`** — invariant blocked-combinations.
6. **Facet regression matrix** (5 scenarios — see Risks table).
7. **OTel topology test** using SDK test exporter — assert `search/retrieval` parents legs + primary fuse; `search/chunk_merge` sibling of retrieval; branch-fuse child of chunk merge.
8. **ArchUnit guardrail tests** added to `IndexerWorkerGuardrailsTest`:
   - `services.plan.*` may not depend on encoder/snapshot/`CommitOps`/`IndexCountOps` types;
   - `services.respond.*` may not depend on the same;
   - non-`services.input.*` classes may not call `clusterSnapshotSupplier.get()`, `getOrComputeCorpusProfile()`, `commitOps.maybeRefresh()`, `documentFieldOps.queryDocIdsByField(SchemaFields.IS_CHUNK, ...)`;
   - class-size cap (1,000 LOC) for new files under `services/` — grandfather list initially empty (the legacy `SearchOrchestrator` is replaced, not grandfathered).
9. **Tier-3 live verification**: `jseval run --pipeline --start-backend` + query matrix covering each `LegSet` variant + `EmptyQueryDecision` + `BlockedDecision`. `ai_activate` + `/api/knowledge/retrieve-context` to confirm `effectiveMode`, `chunkMergeApplied`, `branchFusionStrategy`, `vectorBlocked`, `hybridFallback`, `correctionApplied`, `spladeExecuted` match pre-refactor responses on the same fixtures. Repeat E2–E6 from the prior design's deferred plan.
10. **Pass-8 dispatch** before merge. The implementation slice is substrate-shipping (new sealed sum-types, new enum, new package layout, new ArchUnit rules) per slice 447 §X.11 reframe. Do not defer.

---

## §A — Design assumptions verified (2026-05-18)

Static investigation pass; live-stack experiments DEFERRED (backend remained in `lifecycle.state=STARTING` for 10+ minutes despite worker gRPC accepting requests — separate concern, not a blocker for the design).

### §A.1 — Every pre-retrieval IO call moves to `SearchInputCapture` ✓

The substantive claim, stated plainly: the planner does not perform IO because every pre-retrieval IO site lives in one upstream class. Verified IO sites in the current `execute(...)` body:

| IO call | Line | Capture-site replacement |
|---|---|---|
| `documentFieldOps.queryDocIdsByField(SchemaFields.IS_CHUNK, "true", 1)` | :244 | `CorpusCapabilities.hasChunkDocs` |
| `indexCountOps.getOrComputeCorpusProfile()` (first call) | :248 | `CorpusCapabilities` (full record) |
| `commitOps.maybeRefresh()` | :260 | invoked once during capture; no captured value |
| `clusterSnapshotSupplier.get()` | :263 | `SearchInputs.clusterSnapshot` |
| `computeQpp(queryString)` | :288 | `SearchInputs.qppMetrics` (when sparse or SPLADE enabled) |
| `commitMetadataSupplier.get()` | :307 | `SearchInputs.commitMetadata` |
| `activeGenerationSupplier.get()` | :317 | `SearchInputs.activeGeneration` |
| `bgeM3Encoder.encode(queryString)` | :350 | `EncodingResults.bgeM3` |
| `prepareQueryVector(...)` | :360 | `EncodingResults.vector` |
| `prepareSpladeWeights(...)` | :365 | `EncodingResults.splade` |
| `indexCountOps.getOrComputeCorpusProfile()` (second call) | :745 | re-read of `CorpusCapabilities` already captured at :248 |

Ten IO sites, all in `SearchInputCapture`. `SearchPlanner` consumes only `SearchInputs`. The dependency-direction ArchUnit rule (§"Capture-once enforced by dependency direction") prevents the planner from acquiring access to any of these classes via import. The duplicate `getOrComputeCorpusProfile()` call at :745 collapses to a single capture re-read.

This claim is narrower than the previous design's "planner is pure" framing — and that's the point. Purity over a captured value is what the codebase can mechanically enforce; purity over a request without IO is unattainable given chunk-eligibility's IO dependence.

### §A.2 — Operator order ✓ VERIFIED

Order is Correction → Facets → ChunkAugmentation → Trim. Verified by tracing `execute(...)`: correction at :447–500 (zero-hit retry + per-term correction inside sparse-only branch); facets at :517 (sparse-only path) and :699 (composable path); chunk merge at :769 (reads `chunkQueryText` set at :463 / :492); trim at :826 (only when chunk merge did not apply). Correction's mutation of `chunkQueryText` is consumed by `mergeChunkResults` at :771 — the load-bearing ordering. Encoded by `QueryAfterCorrection` wrapper in the redesign.

### §A.3 — OTel span topology ✓ VERIFIED

`search/retrieval` at :300–305 with `.setParent(parentCtx)` (explicit parent = Head's request context). Three `search/branch` spans at :554/:563/:572 — implicit parent via `try (Scope) = otelCtx.makeCurrent()` at :553/:562/:571 (`otelCtx = Context.current().with(retrievalSpan)` at :549), so child of `retrievalSpan`. Primary 3-way `search/fuse` at :594–599 — implicit parent, child of `retrievalSpan`. `search/chunk_merge` at :762–765 with `.setParent(parentCtx)` — explicit parent = request context, *sibling* of retrieval. Branch-fuse `search/fuse` (whole × chunk) at :1260–1267 — implicit parent inside `chunkScope`, child of `chunk_merge`. Chunk 3-way `search/fuse` at :1361–1367 — same `chunkScope` context, child of `chunk_merge`. `EffectScope` replicates this topology by accepting an explicit parent context at scope creation.

### §A.4 — Reason codes carried by outcome data, not declared per variant ✓

Wire-emitting count is **24** (8 compat + 5 routing + 11 chunk-merge) per `GrpcSearchServiceReasonCodeContractTest.java:33–76`. Plus 2 log-only (`deprecated_mode_fallback` :208, `splade_encoding_failed` :1841). Plus the 25th member `EMBEDDING_COMPATIBILITY_UNKNOWN` (fall-through for unrecognised compat strings via `fromCompatString`) — must be added to the contract allowlist when the slice lands. The 25-member enum and the wire vocabulary are unchanged from the current implementation.

The structural difference from the prior design: codes are properties of **outcome data carried by the decision**, not of the decision *variant*. The 5 routing codes (`EMBEDDING_GENERATION_FAILED`, `NO_EMBEDDING_SERVICE`, `EMBEDDING_EXCEPTION`, `EMBEDDING_COMPATIBILITY_BLOCKED`, `UNKNOWN`) attach to a `VectorEncoding.Failed` value; that value lives either inside `BlockedDecision.encodingFailure` (vector-only request blocked) or `MultiLegDecision.hybridFallback` (hybrid request degraded to text). The same `Failed` value can be carried by either decision without duplication. The 11 chunk-merge codes attach to `ChunkMergeDirective.Skip(reason)` (10 codes pre-commitable by the planner) or are recorded on `SearchOutcome` at runtime (1 code — `SKIPPED_EMPTY_BASE_RESULTS` — necessarily known only post-retrieval). The 8 compat codes attach to `EmbeddingCompatBoundary` captured at request entry.

The contract test backstop is unchanged; the enum's `fromCompatString(String)` boundary mapping is unchanged. What changed is *where in the value graph the codes attach*. The wire emission walks `(decision, outcome)` once per response and reads codes off the carried outcome data.

### §A.5 — Wiring location ✓ VERIFIED

All six deferred-injection setters are called from `GrpcSearchService.java` (not `KnowledgeServer.java`): construction at :137; setters at :157 (`setEmbeddingProvider`), :218 (`setClusterSnapshotSupplier`), :229 (`setActiveGenerationSupplier`), :240 (`setSpladeEncoder`), :245 (`setSpladeIdfQueryEncoder`), :249 (`setBgeM3Encoder`); execute call at :370. Holder-migration target: each setter call site updates `holder.updateWith(...)`; the holder is constructed alongside the orchestrator at :137.

### §A.6 — Volatile-field read patterns (current inconsistency; eliminated by Phase 2)

4 of 6 use the volatile-read-then-local-copy idiom (`bgeM3Encoder` :350, `clusterSnapshotSupplier` :1603, `spladeEncoder` :1826, `spladeIdfQueryEncoder` :1827). 2 do not (`embeddingProvider` :1794 direct null-check; `activeGenerationSupplier` :317 direct `.get()`). This inconsistency is current-state fact and remains so if the redesign lands without Phase 2 (the `SearchCollaboratorsHolder` migration — see §"Volatile-field discipline — separable Phase 2"). When Phase 2 lands, all six fields collapse to a single volatile read on `SearchCollaboratorsHolder.snapshot()` at request entry; the resulting `Snapshot` record flows through `SearchInputs`. Inconsistency dissolved.

The redesign's `SearchInputs` shape accommodates the `Snapshot` field today, so Phase 2 can land later without re-shaping `SearchInputs`.

### §A.7 — JDK + pattern-match `switch` support ✓ VERIFIED

JDK 25 pinned in `gradle/libs.versions.toml`; toolchain `JavaLanguageVersion.of(25)` at `build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt:61`. No `--enable-preview` anywhere. Pattern-match `switch` over sealed interfaces is in active production use — `IndexingCoordinator.java:100–111` dispatches over `sealed interface IndexWriteOperation`. PMD ruleset (`config/pmd/ruleset.xml`) does not forbid the pattern. No tooling constraint blocks the design.

### §A.8 — Two facet paths differ ✓ VERIFIED

Sparse-only path (:502–519) uses the boosted query + honours `runtimeSyntax`. Composable path (:678–705) builds a fresh `buildTextQuery(queryString, runtimeFilters, SIMPLE)` + omits boost filters + SIMPLE unconditionally + blank-query short-circuit + ParseException swallow. Not duplicates. Preserved as separate `FacetCompute` sealed-sum variants in the redesign.

### §A.9 — `RagContextOps` independent ✓ VERIFIED

`RagContextOps.java` constructor takes only primitive `Ops` types (`ChunkSearchOps`, `IndexCountOps`, `CommitOps`, `Supplier<ResolvedConfig>`, `EmbeddingProvider`, `DocumentFieldOps`). Grep for `searchOrchestrator.` inside `RagContextOps.java` returns zero matches. No types shared beyond the primitive Ops layer. Independent redesign safe.

### §A.10 — Test surface no structural lock-in ✓ VERIFIED

Four orchestrator-relevant tests identified:

- `SearchOrchestratorComposablePathTest.java` (361 LOC) — proto-field assertions, real Lucene runtime, mocked `EmbeddingService`.
- `SearchOrchestratorPipelineDispatchTest.java` (231 LOC) — proto-field assertions, real Lucene runtime, mocked compat controller.
- `SearchOrchestratorVirtualThreadContextRegressionTest.java` (64 LOC) — MDC state isolation; no orchestrator-internal coupling.
- `GrpcSearchServiceReasonCodeContractTest.java` (~200 LOC) — proto allowlist; reflection only on `EmbeddingCompatibilityController` state, not on `SearchOrchestrator`.

No reflection on orchestrator internals; no position-dependent assertions; no test will break from internal restructuring as long as the wire shape and reason-code allowlist are preserved.

### §A.11 — `SearchOutcome` field set ✓ VERIFIED

From `LuceneRuntimeTypes.SearchResult` (`hits`, `totalHits`, `tookMs`, `nextCursor`) + 24 orchestrator-locally-computed state values (`vectorBlocked`/`Reason`, `hybridFallback`/`Reason`, `correctionApplied`/`correctedQuery`, `effectiveMode`, `spladeExecuted`/`spladeSkipReason`, `chunkMergeApplied`/`Reason`, `branchFusionStrategy`/`Contributed`, `maxIdf`, `avgIctf`, `queryScope`, `componentTiming` sub-fields × 8). All map 1:1 to `SearchResponse` proto fields at `modules/ipc-common/.../indexing.proto:76–119`. `SearchOutcome` is a complete capture vehicle for response building; the implementation slice should enumerate it explicitly.

### §A.12 — Live-stack experiments ◯ DEFERRED

E2–E6 from the previous design's confidence-building plan were not run; cold-started backend remained in `lifecycle.state=STARTING` (`reason_code=worker.starting`) for 10+ minutes. The `/api/knowledge/search` gate returned HTTP 503 even though `/api/knowledge/status` reported `state=READY` and worker gRPC was accepting requests. The implementation slice **must** complete Tier-3 verification as a merge gate. Experiments to repeat:

- **E2**: 8-query matrix covering each `LegSet` variant (now also: `EmptyQueryDecision`, `BlockedDecision`) — capture `effectiveMode`, `vectorBlocked`, `hybridFallback`, `chunkMergeApplied`, `chunkMergeReason`, `branchFusionStrategy`, `correctionApplied`, `spladeExecuted`.
- **E3**: OTel span topology verification — static evidence is strong (§A.3), live verification adds runtime confirmation.
- **E4**: facet behaviour matrix (5 scenarios from the Risks table).
- **E5**: correction → chunk-merge interaction with a misspelled query on a corpus containing chunks.
- **E6**: vector-blocked degradation path.
- **E7** (new): Each encoding modality's three states observable in `SearchDecision.summary()`. Fire one query per `(modality, state)` cell of the 3 × 3 matrix — `VectorEncoding.NotRequested` (mode=TEXT request), `VectorEncoding.Success` (mode=HYBRID with available encoder), `VectorEncoding.Failed` (mode=HYBRID with encoder offline); similarly for `SpladeEncoding`, `BgeM3Encoding`. Confirm the summary map has the expected keys for each cell.
- **E8** (new): `SparseShortcut` vs `MultiLegDecision(Bm25Only)` facet-behaviour distinction. Fire the same query (a) as a sparse-only request and (b) as a hybrid request with vector encoding deliberately failed. Confirm the sparse-only path uses retrieval-query facets (honoring LUCENE syntax if requested) and the hybrid-degraded path uses fresh-SIMPLE facets (and short-circuits on blank queries). Both should produce the same hit set but different facet payloads on a corpus with malformed-SIMPLE-query corner cases.
- **E9** (new): `ChunkMergeDirective.EligibleApply` → `SKIPPED_EMPTY_BASE_RESULTS` runtime path. Fire a hybrid query that the planner pre-commits as chunk-merge-eligible but whose base retrieval returns zero hits. Confirm the response carries `chunkMergeApplied=false` and `chunkMergeReason=SKIPPED_EMPTY_BASE_RESULTS`, and that the directive on `SearchDecision` was `EligibleApply` (not `Skip`).

### §A.13 — Pre-flight emit-path probe for forward-compat types

Per `.claude/rules/slice-execution.md`, every type declared in the design is enumerated against current code. None of the design's types exist today; all are introduced by the implementation slice. The table distinguishes types that **replace** a current structure (existing IDs renamed) from types that are **new** (no current counterpart).

| Type | Probe result | Replaces (if any) |
|---|---|---|
| `SearchInputs` | probe-new | (none; aggregates fields scattered across `execute(...)` locals lines 198–406) |
| `EncodingResults` (+ `VectorEncoding`/`SpladeEncoding`/`BgeM3Encoding` + `NotRequested`/`Success`/`Failed`) | probe-new | encode-result locals at `SearchOrchestrator.java:350/360/365`; the `VectorPrepResult`/`SpladePrepResult` records (lines 1774–1848) |
| `SearchDecision` (sealed) + variants `EmptyQueryDecision`, `BlockedDecision`, `SparseShortcut`, `MultiLegDecision` | probe-new | the 8-way `if/else` cascade at lines 408–663 + the 12 mutable hoists at lines 265–283 |
| `LegSet` (sealed) + 7 variants | probe-new | the 7 reachable combinations of `(canSparse, canDense, canSplade)` |
| `ChunkMergeDirective` (sealed) + `Skip`, `EligibleApply` | probe-new | the gate cascade at lines 754–820 + the `chunkMergeApplied`/`chunkMergeReason` locals |
| `FacetCompute` (sealed) + `FromRetrievalQuery`, `FromFreshBm25` | probe-new | the two facet construction blocks at lines 502–519 and 678–705 |
| `SearchReasonCode` (enum, 25 members) | probe-new | the literal string emissions at `:208`, `:1841`, and the 24 wire-emitted strings enumerated in `GrpcSearchServiceReasonCodeContractTest.java:33–76` |
| `EmbeddingCompatBoundary` | probe-new | the `compatReason` parameter threaded through `execute(...)` |
| `QueryAfterCorrection` | probe-new | the implicit ordering relationship between correction (`:447–500`) and chunk merge (`:769`) — encoded today only by comment |
| `EffectScope` | probe-new | the four `MDC.put("stage_id", …)` calls + the explicit-parent OTel spans at `:300`, `:762` |
| `SearchOutcome` | probe-new | the `result` local + 24 orchestrator-locally-computed state values enumerated in §A.11 |
| `SearchCollaboratorsHolder` + `Snapshot` | probe-new (Phase 2 only) | six volatile fields + six setters (§A.5 / §A.6) |
| `SearchInputCapture`, `SearchPlanner`, `SearchExecutor`, `SearchResponseBuilder` | probe-new | the seven phases of `execute(...)` (lines 195–909) |

**Every entry is `probe-new`.** None of the types is forward-compat substrate without a consumer — every type is consumed at compile time by the executor or response builder (per the C-018 mitigation in the Risks table). Several rename existing in-method primitives into named records; none introduce a hypothetical future capability.

---

## §B — Implementation slice closure status (2026-05-18)

Slice implemented on branch `worktree-517-search-execution` (worktree at `.claude/worktrees/517-search-execution`). Status: **fully implemented, Tier-3a + pass-F-proxy verified, Tier-3b live-verified once merged with main's independent `standalone-capability-stays-stuck` fix that landed in 507-merge commit `94b4f81bb`; pass-8 outstanding as user-dispatched merge gate.** Not pushed to remote pending pass-8 verdict.

### §B.1 — Checklist matrix

| Verification item | Status | Evidence |
|---|---|---|
| 1. `./gradlew.bat :modules:worker-services:build -x test` | ✓ green | local compile + spotless apply |
| 2. `:modules:worker-services:test`, `:modules:adapters-lucene:test`, `:modules:indexer-worker:test` | ✓ green | 602 worker-services tests + adjacent modules; ran from worktree |
| 3. `./gradlew.bat spotlessApply` | ✓ clean | applied at end of every phase |
| 4. `GrpcSearchServiceReasonCodeContractTest` — full allowlist (25 enum members + `SKIPPED_SHORT_CORPUS`) | ✓ done | `GrpcSearchServiceReasonCodeContractTest.java:43, 60` |
| 5. `KnowledgeHttpApiAdapterHarmfulCombinationsTest` — invariant blocked-combinations | ✓ green (Head-side; unaffected by Worker-side restructure as predicted) | adjacent module test pass |
| 6. Facet regression matrix (5 scenarios) | ✓ done | `FacetRegressionMatrixTest.java` — 5 tests passing, each demonstrating the `FromRetrievalQuery` vs `FromFreshBm25` discriminator |
| 7. OTel topology test using SDK exporter | ✓ done | `SearchExecutorOtelTopologyTest.java` — 3 tests asserting span tree against `CapturingSpanExporter`; also `SearchOrchestratorVirtualThreadContextRegressionTest` preserved as source-level guard at the new home (`SearchExecutor.java`) |
| 8. ArchUnit guardrails added to `IndexerWorkerGuardrailsTest` | ✓ done (2 rules) | `plannerMustNotDependOnIoPrimitives` + `responseBuilderMustNotDependOnEncoders`; a third encoder-confinement rule was dropped as scope-overreach into peer classes outside 517's design — see inline note in `IndexerWorkerGuardrailsTest.java` |
| 9. Tier-3 live verification (E2–E9) | ✓ both tiers — see §B.3 | Tier-3a (in-process gRPC + real Lucene): `SearchExecutorLegSetMatrixTest.java` exercises `EmptyQueryDecision`, `BlockedDecision`, `SparseShortcut`, and `MultiLegDecision(Bm25Only)` with full wire-field assertions. Tier-3b (live dev-stack) verified end-to-end after main's `standalone-capability-stays-stuck` fix (commit `94b4f81bb`) landed: three decision shapes (TEXT, HYBRID-degraded, VECTOR-blocked) all return correct wire fields, search returns hits in <600ms cold-start with pre-seeded corpus. See §B.3 for evidence. |
| 10. Pass-8 independent review before merge | ⚠ outstanding (user-dispatched); pass-F self-review proxy run | Pass-F (Phase F in execution plan) — fresh-context reviewer agent ran a 9-lens audit; found one wire-emitter-elision regression (chunk timing fields silently dropped) which has been fixed in this slice (see §B.7). True pass-8 still required for merge per `independent-review-required` discipline. |

### §B.7 — Pass-F finding and fix (2026-05-18, post-initial-commit)

A fresh-context reviewer agent (Phase F of the execution plan) audited the slice across 9 lenses derived from `agent-postmortems.md`. Eight lenses passed; one returned a concrete finding:

- **Finding**: Wire-shape regression on `ComponentTiming`. The legacy emit-path (`SearchOrchestrator.java:886–895`) set `chunk_merge_ms`, `chunk_bm25_ms`, `chunk_knn_ms`, `chunk_splade_ms`, `chunk_retry`, and `branch_fusion_ms` on the `ComponentTiming` proto field. The first-pass refactor's `SearchOutcome` record did not carry these timings; the response builder emitted only `retrieval_ms` + `chunk_count`. gRPC clients depending on those fields would have seen 0/false.
- **Root cause**: `wire-emitter-elision` per the agent-lessons substrate-discipline handle — every newly-added component on a wire-emitted type must have its production emitter verified.
- **Fix**: `SearchOutcome` extended with `chunkMergeMs`, `chunkBm25Ns`, `chunkKnnNs`, `chunkSpladeNs`, `chunkRetry`, `branchFusionNs` fields; `SearchExecutor.maybeApplyChunkMerge` now captures and threads these from the existing `ChunkMergeResult`; `SearchResponseBuilder.build` emits all eight `ComponentTiming` fields. All tests still pass.

This is the kind of regression a true pass-8 second-agent review is designed to catch. The pass-F proxy succeeded on this one despite being same-session, but the merge gate (item 10) still warrants a true independent reviewer.

### §B.2 — Initially deferred items, now landed

The first commit `fb7949c20` deferred two test additions (facet regression matrix; OTel topology) under "no scope trimming." A follow-up pass landed both:

- **`FacetRegressionMatrixTest`** — 5 scenarios per the Risks table: (a) LUCENE-syntax sparse + facets; (b) sparse + boost filters + facets; (c) HYBRID + blank query + facets (planner-rejected); (d) HYBRID + non-blank query + facets (composable `FromFreshBm25` path); (e) HYBRID + malformed-SIMPLE + facets (ParseException swallowed). All 5 green.
- **`SearchExecutorOtelTopologyTest`** — 3 scenarios asserting span shape against an in-memory SDK exporter: sparse-only retrieval span parented to the request context; degraded-hybrid BM25-only path emits the right `search.mode` + `search.took_ms` attributes; empty-query decision emits no retrieval span. All 3 green.
- **`SearchExecutorLegSetMatrixTest`** — Tier-3a closure of §A.12 E2: 4 reachable decision shapes asserted with full wire-field projections.
- **One production change required**: `SearchExecutor.tracer` was a `static final` capture; the OTel SDK swap-in for tests didn't observe spans because the proxy tracer was bound to the no-op default at class load. Refactored to a lazy `tracer()` method — single map lookup per request, negligible overhead, restores testability. Documented inline.

### §B.3 — Live-stack Tier-3b: blocked then unblocked by main's parallel fix

Initial Tier-3b attempts hit a paradox: `/api/knowledge/status` (GET) returned `state=READY` and `/api/debug/state.worker.health_check.serving=true`, but `POST /api/knowledge/search` returned HTTP 503. The Worker was provably healthy; the 503 gate at `LocalApiServer.java:1185-1195` checked `workerCap.available()` which used an `AppFacadeBootstrap.workerCapability` that had never received the READY transition.

**Root cause** (`standalone-capability-stays-stuck` per `.claude/rules/agent-lessons.md`): `AppFacadeBootstrap` is constructed *before* the Worker has started (Worker startup is async). At construction, the field is initialised to a standalone `new WorkerCapability()` that is never bridged to the authoritative `KnowledgeServerBootstrap.workerCapability()`. The authoritative one transitions to READY; the standalone one stays PENDING. The 503 gate captures the standalone reference.

**Fix landed independently on main** as part of the 507-merge slice's T2.5 work — commit `94b4f81bb fix(507-merge T2.5 + T2.6): bridge worker capability + bind /infra/capabilities` (2026-05-18 21:55). The fix mirrors the bootstrap capability's initial state synchronously into `AppFacadeBootstrap.workerCapability`, then registers a listener via `bootstrapCap.addListener(...)` to forward future transitions. The 517 slice picks this up via the merge into main.

**Live verification after the bridge fix**:

| Request | Result |
|---|---|
| `dev_start (clean: "none", waitTimeoutMs: 120000)` | `readiness.ready_worker=true` on first attempt |
| `POST /api/knowledge/search query="search"` | HTTP 200, 42 hits, 588ms |
| `GET /api/health.components.worker.state` | `"READY"` |

Three decision shapes exercised end-to-end with wire-field verification:

| Request shape | `effectiveMode` | Decision shape | Wire fields verified |
|---|---|---|---|
| `mode=TEXT` | `TEXT` | `SparseShortcut` | `chunkMergeApplied=true`, `chunkMergeReason=APPLIED`, `vectorBlocked=false` |
| `pipeline={sparse,dense}` | `TEXT` | `MultiLegDecision(LegSet.Bm25Only)` via degradation | `hybridFallback=true`, `hybridFallbackReason=LEGACY_INDEX_NO_FINGERPRINT`, `chunkMergeApplied=true` |
| `pipeline={dense}` | `VECTOR` | `BlockedDecision` | `vectorBlocked=true`, `vectorBlockedReason=LEGACY_INDEX_NO_FINGERPRINT`, `chunkMergeReason=SKIPPED_VECTOR_BLOCKED` |

The third row also demonstrates `SKIPPED_VECTOR_BLOCKED` — one of the 11 chunk-merge codes — appearing on the wire, validating Phase-A's allowlist update. Slice 521 §15 captured the postmortem for the bridge fix; this slice is one of its downstream beneficiaries.

### §B.4 — Implementation decisions made within established conventions

Per the user's "Implementation choices within an existing decision boundary — an ADR or established convention — are yours to make":

1. **Package layout** — `services/{input,plan,execute,respond}/` with the facade `SearchOrchestrator.java` in `services/`. Matches the package-private collaborator pattern in `class-size-standard.md`.
2. **Static delegates on `SearchOrchestrator`** for `modeToDefaultPipeline` / `deriveActualMode` / `deriveEffectiveMode` — preserved as backwards-compatibility shims for `SearchOrchestratorPipelineDispatchTest`. Substantive logic lives on `SearchPlanner`. Tempdoc-tactical question #4 ("`SearchReasonCode` location") stays as-recommended (services/).
3. **Encoder-confinement ArchUnit rule dropped** — see Phase 4 / item 8 row above. The narrower planner-no-IO + responder-no-IO rules are sufficient for the tempdoc's stated invariant (capture-once); the broader rule overreached into peer classes outside 517's design scope.
4. **Phase 2 `SearchCollaboratorsHolder`** stays deferred per the tempdoc's own framing. The 6 volatile fields remain on the facade; `SearchInputCapture` reads them once per request entry via the `EncoderSnapshotProvider` callback the facade implements.
5. **`SearchDecision.summary()` rule relaxation** — the tempdoc previously forbade methods on variants; this slice introduced one observability method (`summary()` returning a privacy-safe map) and dropped the speculative `haveMethodsThatAreNot(...)` ArchUnit rule. Documented in the revised tempdoc §"Decision summary for observability."
6. **`QueryAfterCorrection` as a plain `final class`, not a record** — Java records require the canonical constructor to be at least as visible as the record itself; the class form is needed to keep the constructor package-private while exposing the type publicly. Documented in the file's class-doc.

### §B.5 — What landed

**New types (17 files in 4 new packages):**

- `services/SearchReasonCode.java` (25-member enum + `fromCompatString` mapper)
- `services/SearchOutcome.java` (runtime-derived state record)
- `services/input/{SearchInputs,CorpusCapabilities,EmbeddingCompatBoundary,QppMetrics}.java` (records)
- `services/input/{VectorEncoding,SpladeEncoding,BgeM3Encoding,EncodingResults}.java` (sealed sums)
- `services/input/SearchInputCapture.java` (the only class allowed to perform pre-retrieval IO)
- `services/plan/{LegSet,ChunkMergeDirective,FacetCompute,SearchDecision}.java` (sealed sums)
- `services/plan/{ChunkMergeInputs,SearchPlanner}.java`
- `services/execute/{SearchExecutor,QueryAfterCorrection,EffectScope}.java`
- `services/respond/SearchResponseBuilder.java`

**Modified files:**

- `services/SearchOrchestrator.java` — facade body collapsed to 4 lines (`capture → plan → execute → respond`); 6 volatile setters preserved; static delegates for backwards-test compatibility.
- `services/HighlightingOps.java` — bumped to `public` and its methods to `public static` so the new response-builder package can call them.
- `indexer-worker/.../IndexerWorkerGuardrailsTest.java` — 2 new ArchUnit rules.
- `worker-services/.../GrpcSearchServiceReasonCodeContractTest.java` — added `EMBEDDING_COMPATIBILITY_UNKNOWN` to allowlist.
- `worker-services/.../SearchOrchestratorVirtualThreadContextRegressionTest.java` — re-pointed at `SearchExecutor.java`.

**Build verification at end of slice:**

```
:modules:worker-services:build -x test  → SUCCESS
:modules:worker-services:test           → 602 tests, 0 failed, 1 skipped
:modules:adapters-lucene:test           → SUCCESS
:modules:indexer-worker:test            → SUCCESS (incl. 5 ArchUnit rules pass)
spotlessApply                            → clean
```

### §B.6 — Outstanding before merge

1. **Pass-8 independent review** (substrate-shipping per slice 447 §X.11). User-dispatched. The pass-F self-review proxy ran successfully and surfaced + fixed one regression; pass-8 remains a merge gate per `independent-review-required`.
2. ~~Tier-3b live-stack~~ — closed; main's `94b4f81bb` bridge fix unblocks Tier-3b. Verified end-to-end (see §B.3).
3. **Phase 2 `SearchCollaboratorsHolder` migration** stays deferred per the tempdoc's own framing — a sibling tempdoc on composition-root sequencing.

---

## §C — Forward-looking opportunities (superseded)

The forward-looking opportunities catalogue originally drafted here is
superseded by tempdoc 525
(`docs/tempdocs/525-search-introspection-aggregate.md`), which lifts the
six themes into a single foundational design (`SearchIntrospection` as a
`WireAggregateKind` plus four supporting structural moves) rather than
treating them as parallel feature ideas. See 525 for the correct
long-term shape. This stub remains so the §A / §B internal references in
this tempdoc still resolve.

Next reader: see ADR-0014 for why the decision-tree shape is canonical and why a DAG / pipeline-engine is not. See `search-pipeline-invariants.md` for the behavioural contract the implementation must satisfy. See `agent-lessons.md` + `docs/reference/contributing/agent-postmortems.md` for substrate-discipline principles that govern the implementation slice. See tempdocs 516 and 518 for peer mega-class redesigns under the same principle.
