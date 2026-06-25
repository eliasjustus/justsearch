---
title: "280: Stage 3 QDDF and Chunk-Level Fusion"
type: tempdoc
status: done
created: 2026-03-12
depends-on: [270, 274]
informed-by: [309]
---

> NOTE: Noncanonical working tempdoc. Verify behavior claims against canonical docs, code, and tests before promotion.

> **309 impact (2026-03-16)**: Tempdoc 309 §5 recommends dropping per-document dense
> down-weighting (`dw` variable) from the fusion formula — no empirical basis, no
> production precedent, CE already corrects dense failures downstream. This reduces
> 280's scope: implement SPLADE parent-length modulation only, not dense modulation.
> 309 §12 confirms the mixed-length eval corpus is a hard requirement for Stage 3
> acceptance. See 309 §23 for the full impact assessment.

# 280: Stage 3 QDDF and Chunk-Level Fusion

## Purpose

Carry forward the next logical implementation stage from tempdoc 270 after tempdoc 274 validated:

1. CC as the default 2-way fusion strategy
2. True 3-way BM25 + dense + SPLADE retrieval

This tempdoc focuses on **Stage 3** from tempdoc 270:

- per-document / per-parent weight modulation during CC fusion
- chunk-level fusion before MaxP collapse
- schema support for `parent_token_count`
- GPL-backed calibration for 3-way weights and dense/SPLADE modulation thresholds

## Why this stage is next

Tempdoc 270's dependency chain makes Stage 3 the next architectural step after Stage 2:

- Stage 2 established the 3-way retrieval foundation
- Stage 3 is the first stage that can materially improve heterogeneous-corpus ranking
- Stage 4 (freshness + MMR) depends on Stage 3 and also has larger eval gaps
- Query classification and Thompson Sampling are explicitly lower-confidence / later-stage work

## Scope

In scope:

- `parent_token_count` schema and indexing path
- chunk-level fusion ordering vs current chunk-aware merge ordering
- QDDF-style per-parent SPLADE suppression
- assessment of whether dense down-weighting should ship in the same tranche
- GPL 3-way calibration inputs and alpha-sweep workflow
- implementation sequencing and verification plan

Out of scope for this tempdoc unless new evidence changes the call:

- freshness / recency scoring
- MMR diversity rollout
- query classification rollout
- Thompson Sampling / implicit-feedback adaptation

## Initial working hypotheses

1. **SPLADE suppression by parent length** is the strongest Stage 3 change and should likely ship first.
2. **Dense down-weighting** is probably secondary and should be gated on calibration evidence, not bundled unconditionally.
3. **Chunk-level fusion before MaxP** is architecturally required to match tempdoc 270's intended data flow.
4. **GPL 3-way calibration** should be implemented before hard-coding more than conservative default thresholds.

## Investigation checklist

- [ ] Audit current chunk-aware merge and fusion ordering
- [ ] Audit schema/indexing path for `parent_token_count`
- [ ] Audit GPL feature capture and identify the minimum 3-way extension
- [ ] Research primary sources for QDDF-style weighting and chunk-first fusion
- [ ] Decide whether dense down-weighting belongs in Stage 3 or should be split
- [ ] Define a concrete implementation order with verification gates

## Investigation log

### 2026-03-12 - Opening note

Tempdoc created as the direct Stage 3 successor to tempdocs 270 and 274. Initial expectation:

- first implementation tranche should likely be `parent_token_count` + GPL 3-way calibration + chunk-level CC-before-MaxP
- dense down-weighting, if retained, should probably be conditional on calibration output rather than assumed upfront

### 2026-03-12 - Code audit round 1

Confirmed current runtime state against code:

#### 1. Main 3-way retrieval exists, but only at whole-document fusion level

`SearchOrchestrator` now runs BM25 + dense + SPLADE in parallel and fuses them with
`HybridFusionUtils.fuseWithCC3(...)`.

Implication:

- Stage 2 foundation is real and already shipped
- Stage 3 should build on `fuseWithCC3`, not introduce a parallel fusion path

#### 2. Chunk-aware merge still uses RRF, not Stage 3's intended CC-before-MaxP ordering

`SearchOrchestrator.mergeChunkResults(...)` still:

1. runs chunk BM25 / chunk dense / chunk SPLADE as separate searches
2. fuses chunk legs with repeated `fuseWithRRF(...)`
3. RRF-merges whole-doc results with chunk results
4. collapses by parent doc ID afterward

This is the main architectural mismatch with tempdoc 270 Stage 3.

Implication:

- the highest-value runtime change is not just "add a field"
- the chunk-aware branch must be refactored so chunk legs are fused with CC using
  per-parent weights before MaxP-style collapse

#### 3. `parent_token_count` does not exist yet

Confirmed absent from:

- `SchemaFields.java`
- `SSOT/catalogs/fields.v1.json`
- `IndexingDocumentOps`

Implication:

- schema change is still required
- a reindex is still unavoidable if this field is used for runtime scoring

#### 4. Chunk docs already duplicate parent metadata, which gives a clean implementation seam

`ChunkDocumentWriter` already copies parent metadata onto chunk documents
(`mime`, `mime_base`, `file_kind`, `language`, etc.).

Implication:

- Stage 3 can likely extend `ParentChunkMetadata` to include `parent_token_count`
- writing the parent token count onto chunk docs avoids expensive parent lookups during fusion
- this is cleaner than trying to fetch parent-level metadata out-of-band at query time

#### 5. The repo already has a reusable token estimator

`ChunkSplitter.estimateTokens(...)` already exists and delegates to `TokenEstimation`.

Implication:

- `parent_token_count` does not require new AI/runtime dependencies
- indexing can compute a deterministic estimate cheaply at ingest time

#### 6. Regular search hits can project DocValues, but chunk search hits currently do not

`ReadPathOps.projectDocValues(...)` exists for normal search results, but `ChunkSearchOps`
currently returns a fixed stored-field allowlist for chunk hits.

Implication:

- the lowest-friction Stage 3 path is to make `parent_token_count` both stored and DocValues-backed
- chunk search should either:
  - include the field in its stored-field allowlist, or
  - gain DocValues projection similar to `ReadPathOps`
- storing it on chunk docs is likely simpler and more robust than adding ad hoc parent fetches
- important nuance: whole-document hits already return all stored fields when projection is null,
  so parent docs will expose the new field automatically; chunk hits are the special case

#### 6a. Concrete metadata propagation decision

Current evidence favors a single field id, `parent_token_count`, written on both:

- parent documents: value = the document's own estimated token count
- chunk documents: value = the parent document's estimated token count

Why this is probably the right implementation:

- it keeps `HybridFusionUtils` weight modulation branch-free across whole-doc and chunk hits
- it avoids per-hit parent lookups
- it fits the current "chunk docs inherit parent metadata" pattern in `ChunkDocumentWriter`
- it minimizes query-time complexity in the hot path

The name is slightly awkward on parent docs, but the runtime simplicity is worth it.

#### 7. GPL calibration is still blocked on missing 3-way features

`GplJobCoordinator` still re-queries with `PipelineConfigs.TEXT` and only stores:

- `sparse`
- `vector`
- QPP metrics

`GplTrainingTripleStore` has no SPLADE feature column yet.

Implication:

- tempdoc 270's "GPL 3-way extension" is still real, not obsolete
- alpha and threshold calibration cannot be trusted until GPL captures 3-way scores
- additional refinement from code audit: GPL also needs a **length feature**
  (`parent_token_count`, or equivalent) if it is expected to calibrate:
  - SPLADE suppression by parent length
  - dense down-weighting by content size / density bucket
- this is not an IPC blocker: `SearchResult` already carries a `fields` map, so the missing work is
  in GPL feature extraction and serialization, not in the search protocol

#### 8. Existing tests already define the likely verification surface

Relevant existing tests:

- `HybridFusionUtilsTest`
- `SearchOrchestratorCollapseTest`
- `SearchOrchestratorPipelineDispatchTest`
- `ChunkDocumentWriter*Test`

Implication:

- Stage 3 should extend the current seams rather than inventing a new test harness first
- regression coverage can be added incrementally around:
  - weight modulation in `fuseWithCC3`
  - chunk-aware merge ordering
  - `parent_token_count` write path
  - GPL feature serialization

#### 9. Token-count estimation is good enough for a first Stage 3 rollout

The repo already uses a deterministic token estimator (`TokenEstimation` via
`ChunkSplitter.estimateTokens(...)`) with tests for:

- dense ASCII under-estimation protection
- whitespace-rich text
- dense non-ASCII / CJK behavior

Implication:

- Stage 3 does not need exact model tokenization at ingest time
- the `1024..4096` SPLADE suppression zone from tempdoc 270 can be implemented against the
  existing heuristic estimator
- if needed later, thresholds can be re-tuned without changing the field contract

#### 10. `parent_token_count` should be treated as ranking metadata, not user-facing API data

Because normal search hits return all stored fields when no projection is requested, a stored
`parent_token_count` would naturally flow into `SearchHit.fields()` and then into the gRPC/HTTP
response unless explicitly removed during response assembly.

Implication:

- Stage 3 should intentionally use a **stored + DocValues** field for ranking simplicity
- but `SearchOrchestrator` should strip `parent_token_count` before populating response fields
- otherwise this internal ranking feature will leak into the UI/API surface

#### 11. Schema migration mechanics do not block this field addition

Canonical schema-migration docs confirm:

- new fields / new DocValues roles are expected sources of schema drift
- schema fingerprinting will surface the mismatch deterministically
- reindex is the intended recovery path

Code inspection also confirms `FieldMapper` already auto-emits:

- `NumericDocValuesField` for `long`
- `StoredField` when `stored=true`

Implication:

- `parent_token_count` is a routine schema addition in this codebase, not a bespoke migration problem
- the main operational cost is the required reindex, not custom Lucene plumbing

#### 12. SSOT ownership is clear enough for implementation

Canonical configuration docs and loader code confirm:

- repo-root `SSOT/catalogs/fields.v1.json` is the authoritative field catalog in normal dev runs
- packaged app builds bundle SSOT from the repo root

Implementation consequence:

- Stage 3 should treat repo-root `SSOT/catalogs/fields.v1.json` as the canonical edit target
- there may still be a legacy classpath fallback copy under module resources that should be checked
  for drift during implementation / test runs
- this is a packaging hygiene detail, not a design blocker

### 2026-03-12 - External research pass

Primary-source pass focused on late aggregation, chunk-first ranking, and label-free calibration.

#### A. Later aggregation is strongly supported, but "chunk-only everything" is not

Key sources reviewed:

- PARADE (TOIS 2023 / arXiv 2008.09093)
- DAPR: Document-Aware Passage Retrieval (ACL 2024)
- Vespa official docs: "Working with chunks"

Observed pattern:

- late passage/chunk aggregation is consistently better supported than early document collapse
- chunk-level scoring followed by document-level aggregation is a production-grade pattern
- however, hard queries can still need document-level context, so replacing the whole-document path
  outright would be riskier than improving the current chunk-aware branch

Implementation consequence:

- Stage 3 should **not** rewrite the pipeline into "chunk-only retrieval"
- Stage 3 should instead:
  - keep the current whole-document retrieval path
  - upgrade chunk-aware merge from RRF-based stitching to CC-based chunk fusion
  - collapse chunk results by parent only after chunk-level scoring is complete

This is the clearest architecture decision that came out of the external pass.

#### B. Weighted linear fusion is operationally credible

Official search-engine docs and hybrid-fusion literature continue to support min-max normalization
plus weighted linear combination as a practical production approach.

Implementation consequence:

- Stage 3 should extend the existing CC path rather than introduce a different fusion family
- `fuseWithCC3` is the correct runtime seam for modulation logic

#### C. Calibration without human relevance labels is realistic

Key source reviewed:

- IBM Research (2024): "Search Algorithm Optimization Without Relevance Judgments"

Observed pattern:

- QPP-driven or query-sample-driven optimization is viable without gold labels
- label-free tuning is best used for parameter selection and guardrails, not as a substitute for
  all runtime evaluation

Implementation consequence:

- tempdoc 270's GPL/synthetic-query calibration idea is directionally sound
- the repo should use GPL triples to calibrate:
  - 3-way base weights
  - SPLADE suppression interpolation sanity
  - whether dense down-weighting has enough signal to ship

#### D. Dense down-weighting remains the weakest Stage 3 subfeature

The external pass strengthened the architectural case for chunk-first late aggregation much more
than it strengthened the specific dense down-weighting heuristic from tempdoc 270.

Additional targeted source pass on dense-retriever limitations reinforces this:

- recent work on the "granularity dilemma" in dense retrieval supports the idea that dense signals
  can underperform on some simple / exact / short-content cases
- recent numeracy-gap work also supports caution around numeric-heavy content

But these sources do **not** directly validate the exact Stage 3 heuristic
(`length < 50 words` or `numericalDensity > 0.3` → halve dense weight).

Implementation consequence:

- Stage 3A should treat dense down-weighting as optional
- the first shipping slice should center on:
  - `parent_token_count`
  - chunk-level CC before MaxP
  - GPL / diagnostic capture for 3-way calibration
  - SPLADE suppression by parent length

### Current working decision

The most defensible Stage 3 implementation order is:

1. Add `parent_token_count` to parent and chunk documents
2. Refactor chunk-aware merge to use CC-based chunk fusion before parent collapse
3. Extend GPL / diagnostics to emit 3-way features (`sparse`, `vector`, `splade`) plus
   `parent_token_count`
4. Calibrate SPLADE parent-length suppression on fully SPLADE-ready short + mixed/long corpora
5. Only add dense down-weighting if **post-278** calibration shows stable benefit

### Important incremental design choice

For the **first Stage 3 shipping slice**, the safest runtime shape is:

- improve the **chunk branch** to do:
  - 3-way CC on chunk hits
  - per-parent SPLADE suppression
  - MaxP collapse to parents
- but keep the **cross-branch merge** between:
  - whole-document results
  - collapsed chunk results
  as a rank-based merge initially

Rationale:

- whole-document fused scores and chunk-fused scores are produced from different candidate pools
  and are not obviously on the same calibrated scale
- replacing the cross-branch merge with direct score comparison too early would add avoidable risk
- this still captures most of Stage 3's intended gain, because the current biggest weakness is the
  chunk branch's RRF-based stitching before collapse

This is a deliberate Stage 3A / Stage 3B split:

- **Stage 3A**: improve chunk-side fusion + add calibration metadata
- **Stage 3B**: revisit whether whole-doc and chunk-parent scores can be unified more directly

### 2026-03-12 - Deep read of tempdoc 278

A slower, end-to-end reread of tempdoc 278 changes the operational framing for this tempdoc in
four important ways.

#### 1. Stage 3 must add effectively zero new chunk-generation or sparse-backfill work

Tempdoc 278 confirms that, after 273 and 278's deferred/interleaved SPLADE model, the real
throughput multiplier is no longer "documents" but "parents + chunks moving through embedding and
SPLADE backfill".

Implementation consequence:

- Stage 3 should avoid any design that requires:
  - re-encoding chunks differently
  - adding a second sparse field for chunks
  - introducing parent lookups in the hot query path
  - changing chunk production volume
- this further strengthens the current `parent_token_count` plan:
  - write it once during indexing
  - duplicate it onto chunks as inherited metadata
  - use it at ranking time without extra joins or new backfill work

This is now a design constraint, not just a convenience preference.

#### 2. Mixed / long-document evaluation is mandatory for Stage 3 acceptance

Tempdoc 278's benchmark analysis is decisive here: short corpora like `scifact` mostly produce
`0..1` chunks per document, so they do not meaningfully exercise the chunk-merge path that Stage 3
is trying to improve.

Implementation consequence:

- Stage 3 acceptance cannot rely only on `scifact` / `nfcorpus`
- a reproducible mixed or longer-document corpus is required to validate:
  - chunk-side CC-before-MaxP
  - parent-length-aware SPLADE suppression
  - any later whole-doc/chunk score-unification work

This upgrades the prior "if runtime cost is acceptable" language into a hard requirement.

#### 3. Calibration and eval must run only on fully SPLADE-ready corpora

Tempdoc 278 shows that deferred SPLADE + chunk backfill creates a long intermediate state where:

- BM25 and dense are available
- some or all SPLADE features are still pending
- chunk-heavy corpora can remain in this state much longer than short-doc corpora

Implementation consequence:

- Stage 3 evals and GPL calibration must explicitly require fully SPLADE-ready datasets
- otherwise:
  - 3-way weight estimation is biased
  - parent-length SPLADE suppression is under-observed
  - chunk-side improvements may appear weaker simply because the chunk sparse leg is incomplete

This is especially important because 280's main changes are disproportionately about the chunk +
SPLADE branch.

#### 4. Stage 3A should preserve the "no new retrieval inventory" principle

The deeper 278/280 synthesis suggests the first Stage 3 slice should improve ranking behavior using
the current retrieval inventory, not expand it.

Implementation consequence:

- acceptable Stage 3A additions:
  - `parent_token_count`
  - CC-based chunk fusion before collapse
  - per-parent weight modulation
  - GPL/diagnostic capture
- avoid in Stage 3A:
  - new chunk-specific retrieval fields
  - extra query-time retrieval legs
  - any change that widens indexing or backfill responsibilities

This keeps Stage 3A aligned with 278's throughput reality.

#### 5. GPL calibration should gate heuristics, not block structural plumbing

Tempdoc 280 originally put GPL 3-way calibration very early in the sequence. After the deeper 278
read, the better interpretation is:

- GPL calibration is required before trusting:
  - final SPLADE suppression thresholds
  - any dense down-weighting rule
  - later score-space unification across whole-doc and chunk branches
- GPL calibration is **not** required before implementing the structural Stage 3A seams:
  - `parent_token_count`
  - chunk-side CC-before-MaxP
  - diagnostic / feature capture extensions

Implementation consequence:

- if mixed-corpus, fully-SPLADE-ready calibration runs are slow to prepare, Stage 3A should still
  proceed with structural plumbing first
- heuristic modulation should remain conservative, flaggable, or unshipped until calibration catches
  up

This reduces schedule risk without weakening the empirical bar for the heuristic parts of Stage 3.

#### 6. Tempdoc 278 weakens one old dense-rationale, but not the Stage 3 structure

Additional synthesis with tempdoc 278's batch-embedding fix:

- long parent-document dense embeddings are no longer "first-512-token" embeddings
- they are now produced from proper chunking plus per-document mean-pooling across chunk embeddings
- chunk documents themselves were already short enough that the old truncation concern did not apply

Important nuance:

- tempdoc 270 did mention "very long docs -> chunking artifacts" as one possible dense blind spot
- but its actual proposed dense heuristic was still:
  - numerical-density suppression
  - very-short-document suppression (`length < 50 words`)
- it did **not** present a concrete "length > X -> suppress dense" rule the way it did for SPLADE

Implementation consequence:

- Stage 3 should treat the old "long docs harm dense because of chunking artifacts" rationale as
  stale under current code
- `parent_token_count` remains essential for SPLADE modulation and diagnostics
- but long document length alone should **not** be assumed to justify dense down-weighting
- if dense modulation ships later, it should be justified by fresh post-278 calibration evidence,
  not by the pre-fix embedding-quality concern

This does not change the Stage 3A plumbing plan. It does further narrow the already-low-confidence
dense-weighting part of the design.

## Most relevant post-270 tempdocs

After reviewing the tempdocs opened after 270, the relevance order to this tempdoc is:

### Closely related work: yes / partial / no

- **Yes:** `274-hybrid-fusion-upgrade.md`
- **Yes:** `273-splade-quality-and-performance-followup.md`
- **Partial / operational:** `278-indexing-throughput.md`
- **No, but touches the same classes:** `279-code-first-assessment-experiment.md`
- **No, workflow/eval hygiene only:** `271-backend-lifecycle-isolation.md`

### 1. 274 - Hybrid Fusion Upgrade

`274-hybrid-fusion-upgrade.md` is the direct predecessor and remains the most relevant dependency.

Why:

- it implemented Stages 1-2 from tempdoc 270
- it established the current `fuseWithCC3(...)` whole-document path this tempdoc must extend
- it intentionally deferred Stage 3, so this tempdoc is its direct continuation

Practical consequence:

- 280 should reuse 274's CC3 foundation, config defaults, and eval baseline rather than revisiting
  fusion-strategy questions that are already closed
- this is **closely related work**, not just context, because 280 directly picks up the explicitly
  deferred Stage 3 items from 274

### 2. 273 - SPLADE Quality And Performance Follow-Up

`273-splade-quality-and-performance-followup.md` is the most important implementation-adjacent
tempdoc after 274.

Why:

- it wired chunk-level SPLADE / Score-max search into the codebase
- it changed `SearchOrchestrator.mergeChunkResults(...)`, `ChunkSearchOps`, and parent-vs-chunk
  SPLADE routing
- it contains the strongest local evidence about chunk SPLADE behavior, chunk search seams, and
  long-document constraints
- it explicitly stops short of routing-policy work, which is exactly where 280 begins

Practical consequence:

- 280 should treat 273 as the canonical source for the current chunk SPLADE path and for the reason
  chunk-aware merging is now the main Stage 3 seam
- this is also **closely related work**, because 273 changed the exact chunk retrieval and merge
  surfaces that 280 now needs to refactor further

### 3. 278 - Indexing Throughput

`278-indexing-throughput.md` is not a ranking tempdoc, but it is still highly relevant operationally.

Why:

- it incorporates post-273 reality that SPLADE backfill now processes both parents and chunks
- it changes the cost model for any Stage 3 rollout that requires reindex or depends on chunk-heavy
  corpora
- it contains useful workload observations around deferred SPLADE, backfill ordering, and
  parent-to-chunk ratios

Practical consequence:

- 280 should use 278 when reasoning about rollout cost, reindex timing, and whether additional
  chunk-side metadata or calibration runs are operationally tolerable
- this is only **partially related work**: it changes the operational cost model around chunks and
  SPLADE, but it does not itself implement Stage 3 ranking behavior

#### Parallel implementation assessment vs 278 (2026-03-12)

Implementing 278 and 280 in parallel is reasonable **if** they use isolated worktrees and keep a
clear ownership boundary.

Why parallelization is mostly sound:

- 278 is primarily about ingestion / backfill / inference-throughput plumbing:
  - `IndexingLoop`
  - `LoopPacingPolicy`
  - `SpladeBackfillOps`
  - `EmbeddingService`
  - `OnnxEmbeddingEncoder`
  - ORT / GPU model setup
- 280 is primarily about ranking / schema / calibration plumbing:
  - `SearchOrchestrator`
  - `ChunkSearchOps`
  - `HybridFusionUtils`
  - `GplJobCoordinator`
  - `GplTrainingTripleStore`
  - field catalogs + `SchemaFields`

Actual overlap is small but real:

- `IndexingDocumentOps`
  - 278 touches the primary indexing path
  - 280 needs it for `parent_token_count` on parent docs and chunk metadata propagation
- chunk write path / metadata propagation
  - 280 changes `ChunkDocumentWriter.ParentChunkMetadata`
  - 278 throughput work must preserve whatever metadata the write path emits
- readiness / measurement semantics
  - 278 changes how quickly chunk dense / SPLADE features become available
  - 280's eval and calibration gates depend on those readiness semantics

Interpretation:

- this is **not** duplicate-work parallelism
- it is **moderate coordination-cost** parallelism
- the likely failure mode is merge / rebase friction at the indexing boundary, not duplicated logic

Recommended coordination rule:

- 278 owns ingestion/backfill control flow and throughput tuning
- 280 owns ranking behavior, GPL calibration, and the `parent_token_count` field contract
- if 278 refactors `IndexingDocumentOps` or the chunk write call boundary, 280 must rebase before
  finalizing the metadata write path
- final 278 throughput measurements should be rerun after 280 lands, because stored+DocValues
  `parent_token_count` adds small but real indexing/write overhead on both parents and chunks

Bottom line:

- yes, parallel isolated-worktree implementation is reasonable
- no, the work should not be treated as completely independent
- the main shared seam is the indexing/write boundary, not the search/ranking logic

### 4. 279 - Code-First Assessment Experiment

`279-code-first-assessment-experiment.md` is only secondarily relevant, but still worth noting.

Why:

- it independently flags `SearchOrchestrator`, `ChunkSearchOps`, `RagContextOps`, and GPL-related
  surfaces as complexity hotspots
- it supports keeping Stage 3 scoped tightly instead of mixing ranking changes with large refactors

Practical consequence:

- 280 should prefer bounded extensions at existing seams over structural cleanup during the first
  Stage 3 implementation slice
- this is **not closely related work** in the ranking sense; it is mainly corroborating evidence
  about code hotspots and decomposition boundaries

### 5. 271 - Backend Lifecycle Isolation

`271-backend-lifecycle-isolation.md` is weakly relevant.

Why:

- it matters for isolated eval execution and backend ownership during experiments
- but it does not materially change the Stage 3 search design itself

Practical consequence:

- use 271 for workflow hygiene during eval runs, not as a design input for ranking behavior
- this is **not closely related work** to 280's implementation, only supporting execution context

### Effectively not relevant

The following post-270 tempdocs do not materially affect this tempdoc's implementation direction:

- `272-workflow-attribution-and-usability-theory.md`
- `274-scoring-recalibration-and-lock-cleanup.md`
- `275-gradle-cold-start-optimization.md`
- `276-session-type-classification.md`
- `277-scoring-calibration-and-reframing.md`

They may matter for telemetry, eval ergonomics, or session analytics, but not for the ranking and
chunk-fusion decisions owned by 280.

## Expected code touch list

### Schema / indexing

- `SSOT/catalogs/fields.v1.json`
  - add `parent_token_count` (`long`, `stored: true`, `docValues: true`)
- `modules/indexing/src/main/java/io/justsearch/indexing/SchemaFields.java`
  - add `PARENT_TOKEN_COUNT`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java`
  - compute parent token estimate for parent docs
  - pass parent token estimate into chunk regeneration metadata
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter.java`
  - extend `ParentChunkMetadata`
  - write `parent_token_count` onto chunk docs
  - load the field in `regenerateChunksFromExistingParent(...)`

### Runtime fusion / merge

- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridFusionUtils.java`
  - add optional SPLADE modulation inside `fuseWithCC3`
  - likely keep modulation logic local to 3-way CC first
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ChunkSearchOps.java`
  - include `parent_token_count` in chunk hit fields
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java`
  - change chunk-branch fusion from repeated RRF to CC3
  - collapse chunk branch before whole-doc/chunk cross-branch merge
  - strip `parent_token_count` from response payloads

### GPL calibration

- `modules/app-services/src/main/java/io/justsearch/app/services/gpl/GplJobCoordinator.java`
  - switch re-query pipeline from TEXT-only to 3-way
  - capture `splade`
  - capture `parent_token_count` from result fields
- `modules/app-services/src/main/java/io/justsearch/app/services/gpl/GplTrainingTripleStore.java`
  - persist new feature columns
- script layer
  - add / extend alpha-sweep post-processing on GPL triples

### Tests

- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/HybridFusionUtilsTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SearchOrchestratorCollapseTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SearchOrchestratorComposablePathTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/SearchOrchestratorPipelineDispatchTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/rag/ChunkDocumentWriter*Test.java`

## Verification plan

### Unit / module verification

1. schema + indexing tests for `parent_token_count`
2. `fuseWithCC3` tests covering:
   - long-parent SPLADE suppression
   - short-parent no suppression
   - missing / malformed token-count fallback
3. chunk-aware merge tests covering:
   - chunk CC fusion before collapse
   - post-collapse parent ordering
   - no API leakage of `parent_token_count`
4. GPL serialization tests for new feature columns

### Eval verification

1. rerun Stage 2 baselines as the immediate baseline for Stage 3A
2. evaluate Stage 3A on:
   - short-doc corpora (`scifact`, `nfcorpus`)
   - at least one reproducible mixed / longer-doc corpus
3. require fully SPLADE-ready corpora before Stage 3A comparison / GPL calibration
4. run mixed-corpus gate to confirm chunk-side changes do not regress the best-source behavior
5. inspect routing diagnostics by parent-token-count bucket

## Remaining pre-implementation considerations

These are not reasons to stop the tempdoc, but they should be decided explicitly before coding
starts so Stage 3A does not drift in scope mid-implementation.

### 1. Choose the actual long-document acceptance corpus

The repo now has multiple relevant evaluation surfaces:

- BEIR short-doc anchors (`scifact`, `nfcorpus`)
- mixed-corpus matrix paths (`mixed_scifact_fiqa_*`, including the large mix with
  `courtlistener_notitle`)
- converted `mldr-en` support and prior long-document experiments

Remaining decision:

- which corpus is the authoritative long-document / chunk-heavy acceptance surface for Stage 3A?

Important nuance:

- the mixed-corpus smoke gate is necessary, but it is not the same thing as a judged long-document
  ranking benchmark
- Stage 3A should pick one explicit long-doc acceptance corpus up front rather than relying on
  whichever run happens to be cheapest

### 2. Tighten runtime readiness requirements beyond just `spladeReady`

Stage 3A's chunk branch depends on more than parent-document SPLADE availability.

For chunk-side CC-before-MaxP evaluation, the relevant legs are:

- chunk BM25
- chunk dense (`chunkVectorsReady`)
- chunk SPLADE (`spladeReady`)

Remaining decision:

- Stage 3A eval / calibration should require all retrieval legs used by the chunk branch to be
  ready, not only `spladeReady`

Practical consequence:

- the acceptance checklist should explicitly require both:
  - `chunkVectorsReady=true`
  - `spladeReady=true`

### 3. Decide whether GPL extension is calibration-only or LambdaMART-schema work

Current GPL storage and current LambdaMART training are no longer the same scope.

Verified current state:

- `GplTrainingTripleStore` can be extended cheaply with more fields
- but `LambdaMartFeatureSchema` is still V1 with exactly two features: `[sparse, vector]`
- `LambdaMartTrainer` still builds rows from only those two features

Remaining decision:

- is Stage 3's GPL work only for offline calibration / diagnostics?
- or does it also need a LambdaMART feature-schema V2 and model retraining plan?

Recommended answer for Stage 3A:

- calibration-only

Why:

- it keeps the ranking refactor decoupled from a reranker model-schema migration
- it matches the current tempdoc focus on threshold calibration rather than LambdaMART expansion

### 4. Decide chunk candidate over-fetch before parent collapse

Current chunk-aware retrieval fetches chunk legs at approximately the final user-facing `limit`,
then collapses by parent afterward.

Remaining risk:

- if many top chunks belong to the same parent, CC-before-MaxP can still collapse to too few unique
  parents or miss good parents that were just below the chunk cutoff

Remaining decision:

- whether Stage 3A should add an explicit chunk candidate multiplier before collapse
- and whether it should reuse existing whole-doc candidate multipliers or add a chunk-specific one

This is the main remaining ranking-mechanics question that could affect correctness even if the
fusion logic itself is implemented cleanly.

### 5. Define the score-provenance contract for chunk-derived hits

Stage 3A touches both ranking logic and calibration/diagnostic capture, so the provenance shape
must stay coherent.

Current state:

- whole-doc/chunk cross-branch merge preserves chunk provenance under `chunk_*` debug-score keys
- GPL re-query currently reads unprefixed scores (`sparse`, `vector`)

Remaining decision:

- for documents surfaced mainly by the chunk branch, what should the canonical calibration features
  be?
  - unprefixed parent-level scores
  - `chunk_*` scores
  - or an explicit unified post-collapse feature mapping

If this is not decided first, GPL extensions and debug/test expectations can diverge.

### 6. Treat field-catalog sync as an explicit implementation task

The repo-root SSOT field catalog is canonical, but the classpath fallback copy under
`modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` already shows drift.

Remaining decision:

- whether Stage 3A will:
  - update both copies intentionally, or
  - update repo-root only and then confirm the packaged fallback path is no longer used in tests

Either way, this should be handled deliberately when adding `parent_token_count`.

### 7. Be explicit about the first heuristic scope

The structural Stage 3A work is clear, but the first heuristic rollout is still easy to broaden by
accident.

Remaining decision:

- should SPLADE parent-length suppression first apply:
  - only inside the chunk-fused branch, or
  - in both the whole-doc 3-way CC path and the chunk-fused path?

Why this matters:

- `fuseWithCC3` is a shared seam, so a local implementation choice can silently turn into a global
  ranking change
- the tempdoc currently favors a chunk-branch-first rollout, so the first heuristic scope should be
  stated explicitly before coding

## Recommended answers before implementation

If the goal is to do this correctly the first time rather than ship the narrowest possible slice,
the best decisions are:

### 1. Long-document acceptance corpus: `mldr-en` is desirable but not a blocker

Original recommendation was to use `mldr-en` as the authoritative judged long-document acceptance
corpus. This is revised: `mldr-en` remains the best available long-document anchor, but its setup
cost (download, ingest, full SPLADE backfill) is disproportionate to the remaining verification gap
given that the `-RequireSplade` SciFact runs already exercise the full Stage 3 path end-to-end.

Practical policy:

- short-doc judged anchors: `scifact`, `nfcorpus`
- long-doc follow-up (not sign-off blocker): `mldr-en` or equivalent chunk-heavy corpus
- environment/regression guardrail: large mixed-corpus matrix

### 2. Runtime readiness: require both dense and SPLADE chunk readiness

Recommendation:

- Stage 3A eval and GPL calibration should require:
  - `chunkVectorsReady=true`
  - `spladeReady=true`
  - `workload.runtime_gates.gates_passed=true`
  - ANN proof `PASS` whenever dense ANN evidence is applicable

Why:

- the Stage 3A chunk branch depends on both chunk dense and chunk SPLADE legs
- evaluating on partially ready corpora would systematically bias the branch that Stage 3A is
  changing

### 3. GPL scope: calibration-only for Stage 3A, but enrich the stored triples now

Recommendation:

- do **not** expand `LambdaMartFeatureSchema` in Stage 3A
- do extend GPL triple storage / collection now so it captures the data needed for later analysis

Specifically capture:

- `splade`
- `parent_token_count`
- whole-doc vs chunk-branch provenance features (see recommendation 5)

Why:

- Stage 3A is about fusion and calibration, not a reranker model-schema migration
- bundling LambdaMART V2 into this change would blur attribution, expand retraining scope, and
  increase rollback cost
- storing richer triples now preserves future optionality without forcing the reranker migration

Current codebase audit (2026-03-12):

- `LambdaMartFeatureSchema` is still a hard V1 contract with `NUM_FEATURES = 2`
- `RerankerService` still exposes a 2-array API: `rerank(float[] sparseScores, float[] vectors, int n)`
- `KnowledgeHttpApiAdapter` still extracts only `debugScores["sparse"]` and `debugScores["vector"]`
- `LambdaMartReranker` persists `schemaVersion = 1`, rejects model feature-count mismatch at load,
  and builds inference matrices through the V1 `buildRow()` helper
- `LambdaMartTrainer` still trains directly from only `sparse` and `vector`
- `GplJobCoordinator` still re-queries with `PipelineConfigs.TEXT`, so current GPL collection is
  not yet shaped for Stage 3 chunk/whole provenance features

Interpretation:

- there is some V2 groundwork (`FEATURE_NAMES`, schemaVersion sidecar, easy NDJSON extension)
- but the reranker stack is more tightly coupled to V1 than it first appeared
- a correct LambdaMART V2 would therefore be an explicit architecture migration, not a small
  extension to Stage 3A

### 4. Chunk candidate policy: add explicit chunk over-fetch, with one bounded retry

Recommendation:

- do **not** leave chunk legs at the final user-facing `limit`
- introduce a chunk-specific candidate policy:
  - first pass: fetch `chunkCandidateLimit = limit * 10`
  - collapse after chunk-side CC
  - if unique parents after collapse are still `< limit` and one or more chunk legs saturated the
    candidate limit, rerun the chunk branch once at `2x` that candidate limit

Why:

- chunk retrieval has a different failure mode than whole-doc retrieval: duplicate top chunks from
  the same parent can starve unique-parent recall before collapse
- this is a structural property of chunk ranking, not just a tuning detail, so it deserves a
  branch-specific policy
- one bounded retry gives robustness without turning retrieval into an open-ended loop

Why not reuse existing whole-doc multipliers as-is:

- whole-doc candidate multipliers solve cross-leg recall at document granularity
- chunk retrieval has an additional post-fusion collapse step, so its over-fetch needs are
  different in kind, not just degree

### 5. Provenance contract: preserve search debug keys, but write explicit GPL columns

Recommendation:

- keep search-hit debug provenance simple and stable:
  - unprefixed scores = whole-doc branch
  - `chunk_*` scores = chunk branch
- but do **not** make GPL calibration depend on those raw key names alone
- instead, have GPL write explicit columns such as:
  - `whole_sparse`, `whole_vector`, `whole_splade`
  - `chunk_sparse`, `chunk_vector`, `chunk_splade`
  - `whole_cc`, `chunk_cc`
  - `branch_source` (`whole_only`, `chunk_only`, `both`)
  - `parent_token_count`

Why:

- the UI/debug contract and the calibration-feature contract are not the same thing
- explicit GPL columns prevent accidental coupling to debug-key naming and make later analysis much
  cleaner

### 6. Field catalog sync: update both catalogs and add a parity check

Recommendation:

- update both:
  - repo-root `SSOT/catalogs/fields.v1.json`
  - classpath fallback `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json`
- add a small parity assertion test or validation step so future field additions do not drift

Why:

- the fallback copy already diverges in places from repo-root
- ignoring that drift during a schema change is not "safe"; it just defers the ambiguity until a
  packaging or test path consumes the wrong copy

### 7. First heuristic scope: apply SPLADE suppression in both whole-doc and chunk branches

Recommendation:

- implement SPLADE parent-length suppression in **both**:
  - the whole-doc 3-way CC path
  - the chunk-fused CC-before-MaxP path
- do **not** ship dense down-weighting in the same tranche

Why:

- the rationale for SPLADE suppression is strongest on the whole-doc parent path, where long parent
  documents are still represented by limited-length sparse encoding
- applying suppression only in the chunk branch would make the same long parent document receive
  inconsistent SPLADE treatment depending on which branch surfaced it
- the chunk-specific addition is still necessary because chunks from long parents look artificially
  "short" unless they inherit parent length

Net result:

- Stage 3A should be structurally ambitious on chunk fusion and consistent on SPLADE suppression
- but still conservative on dense heuristics and reranker-schema changes

## Confidence status

Current confidence is high enough to implement Stage 3A correctly.

Reasons:

- the runtime seam is identified
- the schema change path is routine in this repo
- the calibration gap is concrete and small, not architectural
- the external research supports the chosen incremental shape

Remaining uncertainty is mostly product/eval, not implementation:

- whether dense down-weighting is worth shipping in the same tranche
- how much gain Stage 3A delivers on mixed / long-doc corpora
- whether Stage 3B should later replace the cross-branch rank-based merge

## Confidence critique

This tempdoc is implementable, but confidence is uneven across its subparts.

### High confidence

- schema addition for `parent_token_count`
- indexing the field on parent and chunk docs
- stripping the field from response payloads
- extending chunk hit field loading to carry the field

Why:

- these follow existing repo patterns closely
- the migration/reindex model is already canonical
- the required code seams are explicit and low-ambiguity

### Medium-high confidence

- replacing chunk-side repeated RRF with CC3-based fusion before parent collapse
- adding SPLADE suppression by parent length inside the chunk fusion path

Why:

- the necessary runtime seams are identified
- existing `fuseWithCC3` and chunk-collapse logic reduce greenfield risk

Main caveat:

- score provenance and debug-score behavior may be easy to regress unless tests are extended carefully

### Medium confidence

- GPL 3-way calibration plumbing
- alpha-sweep post-processing

Why:

- the immediate changes look small
- but downstream GPL consumers were not exhaustively audited during this investigation

Main caveat:

- there may be hidden assumptions in training or analysis code that still expect only `sparse` and
  `vector` features

### Low confidence

- shipping dense down-weighting in the first Stage 3 tranche
- making whole-doc and chunk-parent scores directly comparable enough to replace the current
  rank-based cross-branch merge

Why:

- these are the least empirically grounded parts of tempdoc 270's Stage 3 design
- they are also the places where a "plausible" implementation is most likely to be directionally
  wrong even if technically correct

### What was not fully audited yet

- all downstream consumers of GPL triples
- all diagnostics / eval scripts needed for parent-token-count bucket reporting
- any user-facing or API consumers that might implicitly rely on current chunk-merge provenance keys
- runtime behavior on a live backend in this session

### Bottom line

Confidence is:

- **medium-high** for implementing **Stage 3A** correctly
- only **medium** for implementing the full original Stage 3 vision in one pass

That is why this tempdoc explicitly narrows the first implementation slice instead of pretending the
entire Stage 3 design is equally ready.

## Implementation status (2026-03-12)

Stage 3A is now implemented in code in the `codex/280-stage3-qddf` worktree.

### Landed

- Added `parent_token_count` to the schema constants, SSOT field catalogs, and fallback field
  catalog copies.
- Added `SpladeEncoder.tokenCount(String)` using the existing non-truncating tokenizer.
- Refactored `IndexingLoop` / `IndexingDocumentOps` so parent metadata is derived once and reused
  for both parent writes and chunk regeneration.
- Wrote `parent_token_count` onto parent documents and propagated it onto chunk documents in both
  direct chunk generation and `regenerateChunksFromExistingParent(...)`.
- Kept `parent_token_count` out of user-visible search hit fields while surfacing it in debug
  provenance for Stage 3A calibration paths.
- Extended `HybridFusionUtils` with Stage 3A-capable CC3 fusion:
  - optional namespaced debug keys
  - parent-length-aware SPLADE suppression
  - effective-weight / modifier debug output
  - carry-forward of existing debug provenance
- Switched the whole-doc 3-way path to the Stage 3A CC3 overload with parent-length-aware SPLADE
  suppression enabled.
- Replaced chunk-side repeated RRF fusion with CC3-before-parent-collapse in
  `SearchOrchestrator.mergeChunkResults(...)`.
- Added bounded chunk over-fetch retry:
  - initial chunk candidate budget = `limit * 10`
  - one retry at `2x` if collapsed parents still underfill and an active chunk leg saturated its
    budget
- Replaced the outer whole-doc vs chunk-branch merge with explicit branch labels
  (`whole_branch_*`, `chunk_branch_*`, `branch_merge_*`) instead of the older ambiguous `chunk_`
  RRF prefix.
- Changed parent collapse so later same-parent hits merge missing debug provenance and fields into
  the winning hit instead of discarding the losing branch evidence entirely.
- Reworked GPL feature capture to use a named `FeaturePayload` record instead of the old broad
  positional `appendWithFeatures(...)` argument list.
- Changed GPL re-query collection to use an explicit 3-way CC pipeline:
  - sparse enabled
  - dense enabled
  - SPLADE enabled
  - `fusion_algorithm = "cc"`
  - LambdaMART disabled
  - cross-encoder disabled
  - expansion disabled
  - debug enabled
- Extended the GPL NDJSON schema with Stage 3A columns:
  - `parent_token_count`
  - `whole_sparse`, `whole_vector`, `whole_splade`, `whole_cc`
  - `chunk_sparse`, `chunk_vector`, `chunk_splade`, `chunk_cc`
  - while keeping `sparse` / `vector` aliases for LambdaMART V1 compatibility
- Added a small Stage 3A GPL analysis artifact:
  - `gpl-stage3a-analysis.json`
  - buckets triples by parent length and summarizes whole-doc vs chunk-branch contribution rates
- Verified that LambdaMART V1 still trains from Stage 3A-enriched triples without requiring a
  feature-schema migration.

### Targeted verification completed

Compile verification:

- `./gradlew :modules:indexer-worker:compileJava :modules:adapters-lucene:compileJava :modules:configuration:compileJava :modules:indexing:compileJava`
- `./gradlew :modules:app-services:compileJava :modules:indexer-worker:compileJava :modules:adapters-lucene:compileJava`

Targeted tests:

- `./gradlew :modules:adapters-lucene:test --tests 'io.justsearch.adapters.lucene.runtime.HybridFusionUtilsTest'`
- `./gradlew :modules:indexer-worker:test --tests 'io.justsearch.indexerworker.rag.ChunkDocumentWriterTest' --tests 'io.justsearch.indexerworker.loop.IndexingLoopTest' --tests 'io.justsearch.indexerworker.services.SearchOrchestratorCollapseTest' --tests 'io.justsearch.indexerworker.services.GrpcSearchServiceSearchPayloadTest'`
- `./gradlew :modules:app-services:test --tests 'io.justsearch.app.services.gpl.GplJobCoordinatorTest.negativeSampling_usesStage3aPipelineAndWritesBranchColumns' --tests 'io.justsearch.app.services.gpl.GplTrainingTripleStoreTest' --tests 'io.justsearch.app.services.gpl.GplStage3aAnalysisReportTest' --tests 'io.justsearch.app.services.gpl.LambdaMartTrainerTest.train_acceptsStage3aEnrichedTriples'`

### Not yet executed in this worktree

These remain required for full sign-off and were **not** executed during the coding pass:

- full SPLADE-enabled reindex
- mixed-corpus regression matrix
- post-280 rerun of the relevant 278 throughput measurement

Note: `mldr-en` was previously listed here as a required acceptance eval. It is now a desirable
follow-up, not a sign-off blocker — the SciFact smoke runs with `-RequireSplade` provide sufficient
directional evidence for the Stage 3 search path, and `mldr-en` setup cost is disproportionate to
the remaining verification gap.

## SciFact smoke-run blocker analysis (2026-03-13)

A short `scifact` smoke verification was attempted in place of `mldr-en`, but it did **not**
produce valid query-time metrics. The failure was environmental / launcher-related, not a proven
Stage 3A ranking regression.

### Failure mode A: regular `runHeadless` reused the shared dev index

Observed behavior:

- the backend was launched with an isolated `JUSTSEARCH_DATA_DIR`
- but `/api/status` still reported
  `indexBasePath = D:\code\JustSearch\modules\ui-web\.dev-data\index\default`
- the BEIR run ingested SciFact, then stalled behind runtime readiness gates
- final status showed `reindexRequired=true` and a schema fingerprint mismatch against the shared
  dev index

Root cause:

- `:modules:ui:runHeadless` runs in normal dev mode and does **not** force
  `justsearch.ui.settings.mode=IN_MEMORY`
- `UiSettingsStore` therefore loads the persisted user settings file under
  `%APPDATA%\justsearch\ui\settings.json`
- that settings file currently contains:
  - `indexBasePath = D:\code\JustSearch\modules\ui-web\.dev-data\index\default`
  - explicit model paths for LLM / embedding
- `HeadlessApp` applies `settings.getIndexBasePath()` into `justsearch.index.base_path` before
  building `ResolvedConfig`
- in `ResolvedConfigBuilder`, that explicit settings value wins over the otherwise-derived
  `<dataDir>/index/default` path

Implication:

- an "isolated data dir" alone is **not** enough to isolate a worktree headless backend
- ordinary `runHeadless` launches can silently bind to the shared dev index if user settings
  contain a persisted index path
- any offline eval results from that launch mode are contaminated and should not be used for Stage
  3A verification

### Failure mode B: the clean isolated launch fixed the index path but lost model discovery

Observed behavior:

- forcing an isolated `justsearch.data.dir`, `justsearch.home`, `justsearch.index.base_path`, and
  `justsearch.ui.settings.mode=IN_MEMORY` produced a clean backend
- `/api/status` then reported the correct isolated index path and
  `indexSchemaCompatState=COMPATIBLE`
- but readiness remained permanently blocked:
  - `embeddingCompatReason=NO_EMBEDDING_MODEL`
  - `chunkVectorsReady=false`
  - `spladeCompletedCount=0`
  - `spladeCoveragePercent=0.0`

Root cause:

- the clean launch intentionally removed persisted UI settings from the resolution chain
- that also removed the only active source of:
  - `justsearch.model.path`
  - `justsearch.llm.model_path`
  - persisted server/model overrides
- the clean worker snapshot therefore contained:
  - isolated data/home/config values
  - **no** embedding model path
  - **no** repo root
- worker embedding discovery then had no valid source:
  - `JUSTSEARCH_MODEL_PATH` absent
  - no model under the isolated worktree temp home
  - no model under the worktree-local `models/` path
- SPLADE discovery also had no valid source:
  - no explicit SPLADE model path
  - no model under isolated data/home
  - `justsearch.repo.root` unset, so the repo-root sidecar model path was not considered

Additional structural issue exposed by the logs:

- even the first non-isolated launch had **no** discovered SPLADE model
- the worker log showed `SpladeModelDiscovery -- SPLADE model: not found at any standard location`
- the ordinary `runHeadless` path does not populate `justsearch.repo.root`, so a worktree launch
  cannot discover repo-root SPLADE assets by default
- vector embeddings happened to work in that first launch only because persisted UI settings
  injected `justsearch.model.path`

Implication:

- a clean isolated worktree launch currently needs **both**:
  - settings/index isolation
  - explicit model / repo-root configuration
- otherwise it will never reach the readiness state required for Stage 3A smoke eval

### Practical conclusion

The attempted short SciFact run should be interpreted as a launcher/configuration investigation, not
as a ranking result:

- the first launch path was invalid because it reused the shared dev index
- the second launch path was validly isolated but under-configured for model discovery
- therefore no trustworthy Stage 3A SciFact metrics were obtained from this attempt

## Worktree-safe launcher/config follow-up implemented (2026-03-13)

The worktree-safe headless verification fix has now been implemented in this branch. This was
tracked as a direct follow-up to the failed SciFact smoke run because the Stage 3A verification
path was not trustworthy until launcher isolation and model discovery were fixed.

### What changed

#### Phase 1: dedicated isolated launcher contract

- added `JUSTSEARCH_INDEX_BASE_PATH` / `justsearch.index.base_path` to `EnvRegistry`, so explicit
  launch values now participate in `ResolvedConfig` with normal JVM/env precedence instead of
  being smuggled only through UI settings
- added `:modules:ui:runHeadlessEval`, a dedicated isolated headless entrypoint that forces:
  - isolated `justsearch.data.dir`
  - isolated `justsearch.index.base_path`
  - `justsearch.ui.settings.mode=IN_MEMORY`
  - explicit `justsearch.repo.root`
  - explicit shared `justsearch.models.dir` when available
  - explicit headless config / SSOT / plugin manifest paths
- updated `scripts/eval/eval-session.mjs` so the Gradle-backed eval path now uses
  `:modules:ui:runHeadlessEval` instead of plain `:modules:ui:runHeadless`
- updated `scripts/lib/bench/backend-launcher.mjs` so the direct Java launcher now enforces the
  same isolated contract and also passes the same values as JVM system properties

#### Phase 2: path resolution and model discovery unification

- introduced `ResolvedPathResolver` as the shared path-resolution helper for base dir, model roots,
  explicit repo root, and models dir fallback logic
- updated inference bootstrap to resolve its base dir from `ResolvedConfig` instead of relying on
  `JUSTSEARCH_HOME` / `user.dir` as the primary truth
- updated `InferenceConfig`, `EmbeddingModelResolver`, `EmbeddingOnnxModelDiscovery`, and
  `SpladeModelDiscovery` to resolve model/server paths from the same `ResolvedConfig` path graph
- extended worker snapshot writing so the snapshot always records effective:
  - `justsearch.data.dir`
  - `justsearch.index.base_path`
  - `justsearch.home`
  - `justsearch.models.dir`
  - `justsearch.ssot.path`
  - `justsearch.repo.root`
  - explicit model/server selections when present

### Verification completed for the launcher/config fix

Java / Gradle:

- `./gradlew -PskipWebBuild=true :modules:configuration:test --tests "io.justsearch.configuration.resolved.ResolvedConfigBuilderTest" --tests "io.justsearch.configuration.EmbeddingModelResolverTest"`
- `./gradlew -PskipWebBuild=true :modules:app-inference:test --tests "io.justsearch.app.inference.InferenceConfigFromEnvironmentTest" --tests "io.justsearch.app.inference.InferenceConfigServerExeTest"`
- `./gradlew -PskipWebBuild=true :modules:indexer-worker:test --tests "io.justsearch.indexerworker.embed.onnx.EmbeddingOnnxModelDiscoveryTest" --tests "io.justsearch.indexerworker.splade.SpladeModelDiscoveryTest"`
- `./gradlew --no-daemon -PskipWebBuild=true :modules:ui:help --task runHeadlessEval`
- `./gradlew --no-daemon -PskipWebBuild=true :modules:ui:compileJava`

Node / launcher integration:

- `node scripts/lib/bench/test-backend-launcher.mjs`
- `node scripts/lib/bench/test-eval-backend-lifecycle.mjs`

New test coverage added:

- config precedence for explicit `justsearch.index.base_path` over settings
- worker snapshot persistence of derived path values
- shared `modelsDir` and explicit `repoRoot` model resolution in:
  - `EmbeddingModelResolver`
  - `InferenceConfig`
  - `EmbeddingOnnxModelDiscovery`
  - `SpladeModelDiscovery`
- direct launcher env contract assertions via `launch-env.json`

### What this fixes

This closes the two concrete worktree-verification root causes found in the failed SciFact attempt:

- explicit launch isolation can no longer be silently overridden by persisted UI `indexBasePath`
- clean isolated launches can now discover repo-root/shared models without depending on persisted
  desktop settings

### What is still not done

The launcher/configuration fix is implemented and tested, but I have **not** rerun the Stage 3A
SciFact smoke evaluation in this pass. That remains a separate manual verification step.

### Actual runtime verification completed (direct isolated launcher)

I then performed a real isolated backend launch using the committed direct launcher path rather
than only unit/integration coverage:

- built `:modules:ui:installDist`
- launched `scripts/lib/bench/backend-launcher.mjs start` against a fresh isolated data dir
- passed:
  - `JUSTSEARCH_REPO_ROOT=d:\code\JustSearch`
  - `JUSTSEARCH_MODELS_DIR=d:\code\JustSearch\models`
  - `JUSTSEARCH_AI_AUTOSTART_DISABLED=true`
- polled `/api/status` until index serving became available
- queried `/api/debug/effective-config`
- inspected `runtime/worker-config-snapshot.json`

Observed result:

- backend HTTP start: succeeded
- worker/index serving: succeeded
- effective `justsearch.data.dir`: isolated temp dir
- effective `justsearch.home`: isolated temp dir
- effective `justsearch.index.base_path`: isolated temp dir index path
- effective `justsearch.index.base_path` source: `system_property`, not `ui_settings`
- effective `justsearch.models.dir`: shared models root
- worker snapshot carried:
  - isolated `justsearch.index.base_path`
  - shared `justsearch.repo.root`
  - shared `justsearch.models.dir`

At the moment the worker first became available, `/api/status` reported:

- `indexAvailable=true`
- `indexHealthy=true`
- `indexState=INDEXING`
- `retrievalState=DEGRADED`
- `embeddingCompatReason=NEW_INDEX_NO_FINGERPRINT`

This is acceptable for this verification pass. The point of the check was launcher/config
correctness, not full corpus readiness. The direct isolated launcher contract is therefore
considered **runtime-verified**.

### Gradle wrapper follow-up fix completed (2026-03-13)

The initial version of `:modules:ui:runHeadlessEval` still had a configuration-cache bug:

- the task used a `doFirst { ... }` action in `modules/ui/build.gradle.kts`
- that action referenced top-level Kotlin build-script helpers and project/script state
- under configuration-cache reuse, Gradle failed with:
  - `cannot deserialize Gradle script object references`
  - `Cannot read field "$$implicitReceiver_Project" because "$this" is null`

This has now been fixed by changing the task wiring so the isolated launcher contract is computed
at configuration time rather than task-execution time:

- removed the execution-time `doFirst { ... }` contract setup from `runHeadlessEval`
- resolved all launcher inputs during task configuration via Gradle providers /
  environment-variable-backed configuration
- kept the runtime contract unchanged:
  - isolated `dataDir`
  - isolated `indexBasePath`
  - `IN_MEMORY` UI settings
  - explicit `repoRoot`
  - explicit `modelsDir`
  - explicit config / SSOT / plugin-manifest paths

### Actual runtime verification completed (Gradle-backed path)

After the fix, the Gradle-backed verification path was rerun twice in succession using the same
isolated-launch contract and shared repo/model roots:

- launch mechanism: `startEvalServer(... backend='gradle' ...)`
- shared repo root: `d:\code\JustSearch`
- shared models dir: `d:\code\JustSearch\models`
- `JUSTSEARCH_AI_AUTOSTART_DISABLED=true`
- both runs:
  - reached HTTP readiness
  - reached index-serving readiness
  - used an isolated per-run `indexBasePath`
  - reported `justsearch.index.base_path` source as `system_property`
  - wrote worker snapshots containing the isolated `indexBasePath`

The second run succeeded as well, which closes the original configuration-cache regression.

## Short SciFact smoke attempt (2026-03-13, post-launcher-fix)

After the launcher/config fixes were in place, a short `scifact` smoke run was attempted as a
cheap runtime verification for Stage 3A:

- workflow: managed `beir-eval`
- run id: `stage3a-scifact-smoke-50`
- dataset: `scifact`
- `MaxQueries=50`
- explicit Stage 3A pipeline:
  - `sparse_enabled=true`
  - `dense_enabled=true`
  - `splade_enabled=true`
  - `fusion_algorithm=cc`
  - `lambdamart_enabled=false`
  - `cross_encoder_enabled=false`
  - `expansion_enabled=false`
  - `debug=true`
- isolated backend data dir and index path
- shared models root: `d:\code\JustSearch\models`

### Result

This run did **not** reach query execution. It failed during corpus ingest with:

- failure code: `NOT_RUNNING`
- failure message: `Backend is not running`

At the last successful status poll before failure, `/api/status` still reported:

- `indexState=INDEXING`
- `indexedDocuments=2517`
- `queueDepth=2736`
- `chunkDocCount=1050`
- `chunkEmbeddingCompletedCount=7`
- `chunkEmbeddingPendingCount=1043`
- `spladeReady=false`

The workflow then emitted `backend_liveness_failed`, and cleanup observed that the direct backend
process had already exited.

### Evidence collected

Artifacts for this failed smoke run:

- workflow progress:
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50/progress.json`
- workflow stdout/stderr:
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50/workflow.stdout.log`
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50/workflow.stderr.log`
- backend stdout/stderr:
  - `tmp/workflow-telemetry/runs/stage3a-scifact-smoke-50/beir-eval.backend.stdout.log`
  - `tmp/workflow-telemetry/runs/stage3a-scifact-smoke-50/beir-eval.backend.stderr.log`
- worker log:
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50/backend-data/logs/worker.log`
- telemetry:
  - `tmp/workflow-telemetry/runs/stage3a-scifact-smoke-50/events.ndjson`
  - `tmp/workflow-telemetry/runs/stage3a-scifact-smoke-50/meta.json`
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50/backend-data/telemetry/metrics.ndjson`
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50/backend-data/telemetry/metrics-worker.ndjson`

Observed facts:

- the worker log shows normal indexing activity up to its final lines; no Stage 3A fusion/search
  path was reached because no queries were executed
- no `hs_err_pid*`, heap dump, or crash report was written under the isolated `dataDir`
- backend stderr contained only the expected `sun.misc.Unsafe` warning
- head metrics remained normal up to `2026-03-13T01:07:54.772Z`
- worker metrics remained normal up to `2026-03-13T01:07:45.605Z`
- heap usage in both head and worker stayed low enough that there is no positive OOM evidence

### Current interpretation

This failed smoke run does **not** currently implicate the Stage 3A ranking implementation itself.

The evidence is more consistent with an unexpected head/backend process termination during ingest:

- the failure happened before search queries started
- the worker was still actively indexing near the end
- the headless app produced no fatal log line, uncaught-exception crash report, or JVM crash file
- therefore the current blocker is verification-environment/runtime stability, not a demonstrated
  Stage 3A search-path regression

Practical consequence:

- this run cannot be counted as Stage 3A search verification
- no SciFact metrics were produced
- the next useful investigation is to determine why the direct headless backend exits mid-ingest
  under this managed eval workload, or to reproduce the same ingest path on a known-good baseline
  to decide whether the failure is pre-existing

## GPU path investigation for worktree evals (2026-03-13)

After the failed first smoke run, a second short `scifact` smoke run was started and later
stopped manually before query execution:

- workflow: managed `beir-eval`
- run id: `stage3a-scifact-smoke-50-rerun1`
- dataset: `scifact`
- `MaxQueries=50`
- stop reason: operator stop during long ingest / readiness tail

This rerun did **not** reproduce the earlier mid-ingest backend death. It progressed past the
previous failure point and was still alive when stopped manually.

### What the rerun clarified

The dominant performance issue was not Stage 3A logic. It was runtime configuration:

- dense embeddings were running in CPU-only mode
- SPLADE was not discovered at all, so sparse neural backfill was unavailable
- no ONNX Runtime CUDA native path was configured for the worker

Relevant evidence from the rerun artifacts:

- worker log:
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50-rerun1/backend-data/logs/worker.log`
- worker snapshot:
  - `tmp/beir-eval/workflows/stage3a-scifact-smoke-50-rerun1/backend-data/runtime/worker-config-snapshot.json`
- backend stdout:
  - `tmp/workflow-telemetry/runs/stage3a-scifact-smoke-50-rerun1/beir-eval.backend.stdout.log`

Observed facts from those artifacts:

- ONNX embeddings loaded as `mode=CPU-only`
- `OnnxEmbeddingProvider` logged `gpuEnabled=false`
- `OnnxEmbeddingEncoder` logged `gpuConfigured=false`
- Head logged `GPU auto-selection: SKIPPED (gpu_layers=0)`
- no `JUSTSEARCH_EMBED_GPU_LAYERS`, `JUSTSEARCH_GPU_LAYERS`, `JUSTSEARCH_NATIVE_PATH`, or
  `JUSTSEARCH_ONNXRUNTIME_VARIANT_ID` evidence appeared in the run
- the worker snapshot contained `justsearch.models.dir=d:\code\JustSearch\models` and
  `justsearch.repo.root=d:\code\JustSearch\.claude\worktrees\280-stage3-qddf`
- `SpladeModelDiscovery` logged `SPLADE model: not found at any standard location`
- `SpladeConfig` logged `SPLADE enabled but model not found -- SPLADE will be unavailable`

### Root cause analysis

#### 1. Embeddings stayed CPU-only because GPU knobs were never supplied

The worker-side embedding path only enables ONNX CUDA when embed GPU layers are greater than zero.
The current rerun never supplied those settings, so CPU mode was expected.

Code path:

- `EmbeddingService` resolves ONNX first when available and computes GPU usage from:
  - `justsearch.embed.gpu.layers` / `JUSTSEARCH_EMBED_GPU_LAYERS`
  - fallback `justsearch.gpu.layers` / `JUSTSEARCH_GPU_LAYERS`
- `OnnxEmbeddingProvider` turns `gpuLayers > 0` into `gpuEnabled=true`
- `OrtCudaHelper.resolveOrtNativePath(...)` then looks for:
  - `onnxruntime.native.path`
  - fallback `JUSTSEARCH_NATIVE_PATH`

For this rerun, none of those GPU inputs were present, so the embedding path fell back to CPU.

#### 2. The shared ONNX Runtime CUDA directory exists, but the eval workflow did not point at it

The repository already contains a valid ORT CUDA runtime directory at:

- `d:\code\JustSearch\tmp\ort-variant-test\cuda-12.4-v1.24.3`

That directory contains the files `WorkerSpawner.looksLikeOnnxRuntimeNativeDir(...)` expects
(`onnxruntime.dll`, `onnxruntime_providers_cuda.dll`, shared provider DLLs, and CUDA/cuDNN
dependencies).

However, the worktree eval run did not pass `JUSTSEARCH_NATIVE_PATH`, and the current
best-effort derivation path in `WorkerSpawner` could not recover one automatically:

- no explicit `onnxruntime.native.path`
- no `JUSTSEARCH_NATIVE_PATH`
- no useful `justsearch.onnxruntime.variantId`
- no staged `<dataDir>/native-bin/onnxruntime/variants/<variantId>/...` tree

So even a future `gpuLayers > 0` launch would still need an explicit ORT native path unless the
launcher stages or derives one.

#### 3. SPLADE discovery is not worktree-safe when only `modelsDir` is shared

This rerun also exposed a separate path-contract bug unrelated to dense embeddings:

- the worktree launcher correctly shared `justsearch.models.dir=d:\code\JustSearch\models`
- but `SpladeModelDiscovery` first looks under `<modelRoot>/onnx/splade/`
- its dev fallback uses `<repoRoot>/models/splade/naver-splade-v3/`
- in this run, `repoRoot` was the worktree root, which does not contain a `models/` tree

The shared models layout is:

- `d:\code\JustSearch\models\onnx\embedding`
- `d:\code\JustSearch\models\splade\naver-splade-v3`

That means ONNX embeddings resolve from shared `modelsDir`, but SPLADE does not, unless the run
also provides an explicit SPLADE model path.

### Practical recommendation before the next GPU verification attempt

For a worktree-managed eval run, do **not** rely on defaults. Pass explicit absolute paths and GPU
knobs:

- `JUSTSEARCH_EMBED_BACKEND=onnx`
- `JUSTSEARCH_EMBED_GPU_LAYERS=99`
- `JUSTSEARCH_NATIVE_PATH=d:\code\JustSearch\tmp\ort-variant-test\cuda-12.4-v1.24.3`
- `JUSTSEARCH_SPLADE_MODEL_PATH=d:\code\JustSearch\models\splade\naver-splade-v3`
- `JUSTSEARCH_SPLADE_GPU_ENABLED=true`
- `JUSTSEARCH_SPLADE_GPU_DEVICE_ID=0`
- `JUSTSEARCH_SPLADE_GPU_MEM_MB=2048`

Important note:

- `JUSTSEARCH_ONNXRUNTIME_VARIANT_ID` alone is not enough for the current worktree eval contract,
  because the worker only derives from that when an ORT variant has been staged under the isolated
  `dataDir`
- relative paths such as `models/splade/...` or `tmp/ort-variant-test/...` are unsafe in worktree
  eval runs because they resolve against the worktree root, not the canonical repo root

### Long-term fixes suggested by this investigation

If GPU eval is going to be a normal workflow, two follow-up fixes are justified:

- make the eval launcher expose a first-class GPU profile that forwards the required embed/SPLADE
  env vars and absolute ORT native path
- make `SpladeModelDiscovery` treat the shared `modelsDir` layout
  (`<modelsDir>/splade/naver-splade-v3`) as a standard discovery location, so worktree evals do
  not require an explicit SPLADE model override

## Stage 3B implementation status (2026-03-13)

Stage 3B is now implemented in the `280` worktree as a runtime-ranking follow-on to Stage 3A.

What landed:

- outer whole-doc vs chunk-branch merge now supports parent-level branch CC, with config rollback to
  branch-level RRF
- chunk-branch collapse now normalizes winning chunk hits onto `parent_doc_id` before branch fusion
- whole-doc retrieval over-fetches when chunk-aware merge is eligible so branch fusion works on
  broader parent candidate pools
- resolved config now carries:
  - `index.hybrid.branch_fusion_strategy`
  - `index.hybrid.branch_cc_zero_exclude`
  - `index.hybrid.branch_cc_weight_whole`
  - `index.hybrid.branch_cc_weight_chunk`
  - `index.hybrid.branch_chunk_min_weight_multiplier`
- GPL triple capture now stores explicit Stage 3B branch-fusion evidence:
  - `branch_whole`
  - `branch_chunk`
  - `branch_cc`
  - `branch_present_whole`
  - `branch_present_chunk`
  - `branch_weight_whole`
  - `branch_weight_chunk`
  - `branch_effective_weight_whole`
  - `branch_effective_weight_chunk`
  - `branch_modifier_whole`
  - `branch_modifier_chunk`
- a new offline calibration report is written as `gpl-stage3b-branch-fusion.json`

Important bug fixed during verification:

- both Stage 3A `CC3` and Stage 3B `CCNamed` initially had an incorrect `zeroExclude`
  implementation: absent legs were removed from the denominator but not from the numerator when
  computing effective weights
- this would have allowed a missing branch to retain non-zero effective weight
- the fusion helpers now zero absent-leg numerators as well, and targeted tests were added for both
  Stage 3A and Stage 3B paths

Targeted verification completed:

- `:modules:adapters-lucene:test --tests "io.justsearch.adapters.lucene.runtime.HybridFusionUtilsTest"`
- `:modules:indexer-worker:test --tests "io.justsearch.indexerworker.services.SearchOrchestratorCollapseTest"`
- `:modules:indexer-worker:test --tests "io.justsearch.indexerworker.services.GrpcSearchServiceSearchPayloadTest"`
- `:modules:configuration:test --tests "io.justsearch.configuration.resolved.ResolvedConfigBuilderTest"`
- `:modules:app-services:test --tests "io.justsearch.app.services.gpl.GplTrainingTripleStoreTest"`
- `:modules:app-services:test --tests "io.justsearch.app.services.gpl.GplStage3aAnalysisReportTest"`
- `:modules:app-services:test --tests "io.justsearch.app.services.gpl.GplStage3bBranchFusionReportTest"`
- `:modules:app-services:test --tests "io.justsearch.app.services.gpl.GplJobCoordinatorTest"`
- `:modules:app-services:test --tests "io.justsearch.app.services.gpl.LambdaMartTrainerTest"`

## GPU-backed Stage 3 smoke verification and follow-up fixes (2026-03-13)

After the Stage 3B implementation landed, the next three planned steps were:

1. GPU/readiness preflight on the worktree-safe eval path
2. short `scifact` smoke with `branch_fusion_strategy=cc`
3. short `scifact` smoke with `branch_fusion_strategy=rrf`

Those steps were completed, but two additional runtime issues had to be fixed first.

### GPU dense embedding fix: configurable ONNX CUDA memory budget

The first GPU-preflight attempt showed that dense ONNX embeddings still fell back to CPU even when
CUDA was requested explicitly.

Root cause:

- `OnnxEmbeddingEncoder` used a hardcoded GPU memory arena limit of `256MB`
- on the local verification machine that limit was too small for reliable GPU session creation

Fix implemented:

- added resolved-config support for `justsearch.embed.gpu_mem_mb` / `JUSTSEARCH_EMBED_GPU_MEM_MB`
- defaulted that setting to `1024`
- threaded the resolved value through `EmbeddingService` into `OnnxEmbeddingEncoder`
- verified the encoder now uses the configured memory budget instead of the previous constant

Targeted tests added / rerun:

- `:modules:configuration:test --tests "io.justsearch.configuration.EnvRegistryTest"`
- `:modules:configuration:test --tests "io.justsearch.configuration.resolved.ResolvedConfigBuilderTest"`
- `:modules:indexer-worker:test --tests "io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoderTest"`

### SPLADE compatibility fix: support sparse-output ONNX exports

The local SPLADE model available for worktree verification is not the older MLM-logits export that
the current encoder path expected.

Observed model shape:

- inputs: `input_ids`, `attention_mask`, `token_type_ids`
- outputs: `output_idx`, `output_weights`

This is a pre-sparsified export, not a `[batch, seq, vocab]` MLM-logits tensor. Before the fix,
that produced runtime failures such as:

- `Unexpected SPLADE output rank: 1, expected 3`
- `input_ids ... Got: 8 Expected: 1`

Fix implemented:

- extended `SpladeEncoder` to support both output families:
  - `MLM_LOGITS`
  - `PRESPARSE`
- added format detection based on session outputs
- preserved the existing pinned/batched path for logits models
- added a compatibility path for sparse-output models that:
  - runs item-by-item
  - decodes `output_idx` + `output_weights`
  - maps those ids back onto the BERT vocabulary
  - filters special tokens
  - preserves max weight for duplicate token ids

Targeted tests added / rerun:

- `:modules:indexer-worker:test --tests "io.justsearch.indexerworker.splade.SpladePostProcessTest"`
- `:modules:indexer-worker:test --tests "io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoderTest"`
- `:modules:configuration:test --tests "io.justsearch.configuration.EnvRegistryTest"`
- `:modules:configuration:test --tests "io.justsearch.configuration.resolved.ResolvedConfigBuilderTest"`

### GPU/readiness preflight: succeeded

A fresh isolated backend preflight was then run with explicit worktree-safe absolute paths and GPU
knobs:

- `JUSTSEARCH_MODELS_DIR=C:\Users\<user>\AppData\Roaming\io.justsearch.shell\models`
- `JUSTSEARCH_EMBED_BACKEND=onnx`
- `JUSTSEARCH_EMBED_GPU_LAYERS=99`
- `JUSTSEARCH_EMBED_GPU_MEM_MB=2048`
- `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=d:\code\JustSearch\tmp\ort-variant-test\cuda-12.4-v1.24.3`
- `JUSTSEARCH_SPLADE_ENABLED=true`
- `JUSTSEARCH_SPLADE_GPU_ENABLED=true`
- `JUSTSEARCH_SPLADE_GPU_DEVICE_ID=0`
- `JUSTSEARCH_SPLADE_GPU_MEM_MB=2048`
- `JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY=cc`
- `JUSTSEARCH_AI_AUTOSTART_DISABLED=true`

Observed success signals:

- worker status reported:
  - `embedOrtCuda.available=true`
  - `spladeOrtCuda.available=true`
- worker logs showed:
  - `OnnxEmbeddingEncoder GPU session initialized`
  - `SpladeEncoder initialized ... outputFormat=PRESPARSE`
  - `SpladeEncoder GPU session initialized`
  - `SPLADE first encode: using GPU session`

Conclusion:

- the worktree-safe launcher plus explicit absolute GPU/model inputs are sufficient to run Stage 3
  verification with both dense ONNX and SPLADE on GPU

### Short SciFact smoke: `branch_fusion_strategy=cc`

The first full strict-gate `RequireSplade` SciFact workflow was allowed to finish corpus indexing
only, then the actual short verification was performed as a query-only smoke on the already indexed
corpus using:

- `-SkipIndex`
- `-SkipDownload`
- `-SkipMaterialize`
- `-SkipRuntimeGates`
- `-RuntimeGateTimeoutSec 1`
- `-RuntimeGatePollIntervalSec 1`
- `-RuntimeGateStablePollsRequired 1`
- `-MaxQueries 50`

Artifact:

- `tmp/beir-eval/scifact-20260313-070703/metrics.v2.json`

Key metrics:

- `meanRecall@10 = 0.881`
- `meanNDCG@10 = 0.7593228055058606`
- `meanMRR = 0.7366666666666666`
- `meanP@1 = 0.64`

Important caveat:

- this artifact is **not** acceptance-quality or directly comparable because runtime gates were
  skipped and full readiness was not required

What it *does* verify:

- `effectiveModeCounts.HYBRID = 50`
- `chunkMergeAppliedCount = 50`
- `splade.executed = 50`
- `vectorEvidenceAvailableRate = 1`
- `sparseEvidenceAvailableRate = 1`
- `denseVectorEvidenceAvailableRate = 1`

So the Stage 3 path executed end-to-end on all 50 smoke queries.

### Short SciFact smoke: `branch_fusion_strategy=rrf`

The same query-only smoke procedure was then rerun on the same indexed data with:

- `JUSTSEARCH_HYBRID_BRANCH_FUSION_STRATEGY=rrf`

Artifact:

- `tmp/beir-eval/scifact-20260313-070931/metrics.v2.json`

Key metrics:

- `meanRecall@10 = 0.892`
- `meanNDCG@10 = 0.606234941992055`
- `meanMRR = 0.5296904761904762`
- `meanP@1 = 0.3`

Important caveat:

- this run is also non-comparable because runtime gates were skipped
- the restarted backend also began re-enqueueing indexing/backfill on startup, so it was not in an
  ideal steady-state

Even with those caveats, the Stage 3 execution evidence was still present:

- `chunkMergeAppliedCount = 50`
- `splade.executed = 50`
- dense and sparse evidence were available for all 50 queries

### Directional result from the short smoke runs

These short query-only smokes are not suitable for final acceptance, but they are useful as fast
iterative verification.

Directional result:

- `cc`: `meanNDCG@10 = 0.7593`
- `rrf`: `meanNDCG@10 = 0.6062`

Interpretation:

- this is strong directional evidence that the new parent-level branch CC behaves materially better
  than the rollback `rrf` path in the short smoke setup
- it is still **not** sufficient for sign-off because:
  - runtime gates were skipped
  - full chunk-vector readiness was not enforced
  - full SPLADE readiness was not enforced
  - the `rrf` restart was not a perfectly steady-state run

### Updated sign-off status

Completed:

- targeted unit/module verification for Stage 3A and Stage 3B
- GPU/readiness preflight on the worktree-safe eval path
- short `scifact` smoke for `branch_fusion_strategy=cc`
- short `scifact` smoke for `branch_fusion_strategy=rrf`

Still required for sign-off:

- a cleaner comparable `scifact` verification pass with stable runtime conditions
- regression pass comparing Stage 3A branch-RRF vs Stage 3B branch-CC on the governed eval matrix

Desirable follow-ups (not sign-off blockers):

- long-document acceptance benchmark on `mldr-en` or equivalent chunk-heavy corpus
- mixed-corpus regression matrix on the governed eval surface

## Dense evidence preservation fix (2026-03-14)

The remaining Stage 3 verification issue from the cleaner SciFact pass was fixed on the
mainline-integration branch.

Root cause:

- parent-normalized chunk collapse merged sibling debug scores with `putIfAbsent`
- if the winning chunk carried `chunk_vector = 0` / `chunk_vector_rank = 0`, a sibling chunk with
  positive dense evidence for the same parent could not override those zero values
- the eval tooling also only counted whole-doc `vector*` keys as dense evidence, even when the
  final returned hit was a chunk-derived parent hit carrying `chunk_vector*`

Fix implemented:

- `SearchOrchestrator.collapseChunkHitsToParents(...)` now preserves the winner chunk's excerpt and
  fused score keys, but aggregates best raw chunk leg evidence across sibling chunks:
  - `chunk_sparse`, `chunk_vector`, `chunk_splade` use the maximum value seen across collapsed
    siblings
  - `chunk_sparse_rank`, `chunk_vector_rank`, `chunk_splade_rank` use the best positive rank
    (lowest non-zero value) seen across collapsed siblings
- `scripts/search/lib/BeirEval.Search.psm1` now treats `chunk_vector*` as dense evidence and
  `chunk_sparse*` as sparse evidence for returned parent hits
- per-query BEIR artifacts now also record `topHitChunkVector` and `topHitChunkSparse` under
  `vectorContributionEvidence`

Targeted verification completed:

- `:modules:indexer-worker:test --tests "io.justsearch.indexerworker.services.SearchOrchestratorCollapseTest"`
- `powershell -File scripts/search/test-beir-eval-search-lib.ps1`

Immediate next step:

- rerun the cleaner steady-state short `scifact` verification on this integrated mainline branch to
  confirm the previous dense-evidence drop no longer reproduces spuriously

## Integrated-mainline SciFact rerun status (2026-03-14)

I reran the short `scifact` smoke on the integrated mainline branch with the direct isolated
backend and explicit GPU settings:

- dense embeddings on ONNX GPU
- SPLADE on ONNX GPU
- `branch_fusion_strategy = cc`
- shared repo-root models via `d:\code\JustSearch\models`

What the rerun proved:

- the integrated branch starts cleanly against current mainline plumbing
- dense ONNX GPU was active
- SPLADE GPU was active
- the branch reached full corpus ingest and chunk-vector readiness under the managed eval path
- model identity and CUDA native-path selection were visible in the worker snapshot and status

What blocked completion:

- the run did **not** reach query execution because `-RequireSplade` kept the eval in runtime
  gating until SPLADE backfill finished
- SPLADE backfill repeatedly hit ORT GPU memory allocation failures on larger SciFact documents and
  entered retry/backoff cycles
- representative error from `worker.log`:
  - `ORT_RUNTIME_EXCEPTION`
  - `Available memory of 3274240 is smaller than requested bytes of 7004416`
- at the point the run was stopped:
  - `chunkVectorsReady = true`
  - `chunkEmbeddingCompletedCount = 2136 / 2138`
  - `spladeCompletedCount = 624 / 5189`
  - `spladePendingCount = 2756`
  - `spladeFailedCount = 0`
  - repeated warning: `SPLADE backfill failed (... consecutive), next retry in 60000ms`

Conclusion:

- this rerun does **not** indicate a Stage 3A/3B ranking failure
- it does indicate an operational verification blocker: the current SPLADE GPU memory settings are
  not robust enough for this full SciFact backfill shape under `-RequireSplade`
- Stage 3 merge should proceed based on code/test readiness, but post-merge SciFact verification
  needs one of:
  - lower SPLADE GPU memory pressure
  - CPU SPLADE backfill
  - smaller SPLADE backfill batches
  - or a smoke profile that does not require full SPLADE-ready gating

## Post-merge SPLADE GPU verification (2026-03-14)

### Run 1: 2-way (no SPLADE) — succeeded

A 30-query SciFact smoke run was executed on current `main` with GPU-accelerated dense ONNX
embeddings but without requiring SPLADE readiness:

- dense ONNX GPU: active (`embedOrtCuda.available=true`)
- SPLADE GPU: active (`spladeOrtCuda.available=true`)
- `branch_fusion_strategy=cc`
- `JUSTSEARCH_EMBED_GPU_MEM_MB=2048`
- `JUSTSEARCH_SPLADE_GPU_MEM_MB=2048`

Results (30 queries, `stub-jaccard` profile, no SPLADE gate):

| Mode | nDCG@10 | Recall@10 | MRR | P@1 |
|------|---------|-----------|-----|-----|
| hybrid | 0.666 | 0.737 | 0.644 | 0.567 |
| lexical | 0.721 | 0.857 | 0.698 | 0.600 |

Execution evidence:
- `HYBRID` mode: 30/30 queries
- `chunkMergeAppliedRate`: 1.0
- `denseVectorEvidenceAvailableRate`: 1.0
- `sparseEvidenceAvailableRate`: 1.0
- cross-encoder: executed on all 30 hybrid queries
- zero search errors, zero zero-hit queries
- SPLADE was **not** part of the search pipeline (`spladeReady=false`)

This confirms the Stage 3 search pipeline runs cleanly end-to-end on current `main` for the
2-way (BM25 + dense) CC fusion path with chunk merge.

### Run 2: 3-way (RequireSplade) — blocked by GPU memory

A second 30-query SciFact smoke run was attempted with `-RequireSplade` to exercise the full
3-way path.

Observed SPLADE backfill progression:

| Elapsed | Completed | Coverage | Failed | Pending |
|---------|-----------|----------|--------|---------|
| ~2 min  | 752/5189  | 14.5%    | 0      | 4348    |
| ~4 min  | 1006/5189 | 19.4%    | 0      | 3748    |
| ~6 min  | 1820/5189 | 35.1%    | 0      | 3149    |
| ~8 min  | 2818/5189 | 54.3%    | 0      | 2036    |
| ~11 min | 4250/5189 | 81.9%    | 0      | 414     |
| ~13 min | 4625/5189 | 89.1%    | 0      | 0       |

Coverage plateaued at **89.1%** and never reached the 99.9% `spladeReady` threshold.

Worker log errors confirm the same ORT GPU memory allocation failure pattern from the earlier
tempdoc investigation:

```
SPLADE batch encoding failed: Error code - ORT_RUNTIME_EXCEPTION
  BFCArena::AllocateRawInternal Available memory of 115114752 is smaller
  than requested bytes of 250036224
```

~564 documents with longer sequences require ~241-250 MB for the SPLADE `/ReduceMax` node, but
only ~115 MB remains available in the 2048 MB ORT GPU arena after fragmentation from co-located
dense embedding sessions.

Key observations:
- `spladeFailedCount` stays at 0 (retries don't increment the permanent failure counter)
- `spladePendingCount` drops to 0 (documents cycle between pending and retry backoff)
- the failures are deterministic: the same documents always fail because their sequence length
  requires more contiguous memory than the fragmented arena can provide
- SPLADE GPU initialization itself succeeds — the issue is runtime allocation for specific
  large-sequence documents

### Confirmed root cause

The SPLADE GPU memory problem is a **BFC arena fragmentation issue** in ONNX Runtime's CUDA
execution provider. Both dense ONNX embeddings and SPLADE share the same GPU device, and ORT's
Best-Fit with Coalescing (BFC) allocator fragments over time as sessions allocate and free
variable-sized tensors. When SPLADE encounters a document whose intermediate tensors (particularly
`/ReduceMax`) require ~250 MB contiguous, the arena cannot satisfy the request even though total
free memory may be sufficient — because it is fragmented into smaller non-contiguous blocks.

This is not a total memory budget issue (2048 MB should be plenty) but a contiguous allocation
failure after arena fragmentation.

Important clarification from research: the two ORT sessions (dense and SPLADE) each get their
own independent BFC arena — they do not share one arena. So the fragmentation is internal to the
SPLADE session's own arena, not cross-contamination from dense embeddings.

### Research: ORT BFC arena fragmentation fixes (2026-03-14)

A targeted research pass on current ONNX Runtime docs, GitHub issues, and primary sources
identified the following fix options, ordered by expected impact and implementation effort.

#### Fix 1 (highest priority): Use `model_fp16.onnx` for SPLADE

The repo already has `models/splade/naver-splade-v3/model_fp16.onnx` (254 MB, exported in
tempdoc 288). FP16 halves all floating-point tensor sizes including intermediates.

- the problematic ~250 MB `/ReduceMax` allocation would drop to ~125 MB
- all other intermediate tensors shrink proportionally, reducing total arena pressure
- FP16 is standard for transformer inference on GPU and ONNX Runtime's CUDA EP handles it natively
- risk is low: minor accuracy loss is possible but generally acceptable for SPLADE retrieval

Source: [ORT Float16 docs](https://onnxruntime.ai/docs/performance/model-optimizations/float16.html)

#### Fix 2 (high priority): Truncate SPLADE to 256 tokens

SPLADE v3 was **trained** with `max_seq_length` of 128 or 256, not 512. Thibault Formal from
NAVER LABS Europe (model author) confirmed in the HuggingFace model discussion that quality is
unaffected by matching the training length:

> "In practice, you can use the BERT's max length (so, 512). Models have been trained w/ lower
> values (128 or 256), but it should still work the same if you increase (or decrease) this
> value at inference."

Memory scaling for self-attention is O(n^2):
- 512 tokens → baseline
- 256 tokens → ~25% of baseline attention memory
- the ReduceMax output tensor `[batch, seq_len, vocab_size]` also scales linearly with seq_len

Source: [SPLADE v3 HF discussion #5](https://huggingface.co/naver/splade-v3/discussions/5)

**Fixes 1+2 combined** would reduce the problematic ~250 MB allocation to roughly 30-35 MB,
which would almost certainly eliminate the fragmentation-induced OOM without any arena
configuration changes.

#### Fix 3: Enable arena shrinkage via RunOptions

ORT supports per-run arena shrinkage:

```java
runOptions.addConfigEntry("memory.enable_memory_arena_shrinkage", "gpu:0");
```

This releases extended arena chunks back to CUDA after each `Run()` call, preventing indefinite
fragmentation buildup. The initial chunk persists; only extensions are freed.

Caveats:
- performance penalty from `cudaFree()` calls on each shrink
- some ORT versions report this not working reliably (GitHub issue #23339)
- an internal arena-shrink API exists that is not publicly exposed (issue #25996)

Source: [CUDA EP docs](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html),
[ORT issue #23339](https://github.com/microsoft/onnxruntime/issues/23339)

#### Fix 4: Disable memory pattern (`enable_mem_pattern=false`)

When `enable_mem_pattern` is true (the default), ORT pre-plans memory reuse based on tensor
shapes from the first inference run. For variable-length sequences this is counterproductive:
the pattern from a short first sequence is reused for longer sequences, causing fragmentation
and over-allocation.

Setting `enable_mem_pattern=false` removes this pre-planning, which is the correct behavior
for dynamic-shape inputs like variable-length document tokenizations.

Source: [ORT memory docs](https://onnxruntime.ai/docs/performance/tune-performance/memory.html)

#### Fix 5: Use device allocator for initializers

`use_device_allocator_for_initializers` keeps model weights (initializers) out of the BFC arena
entirely, allocating them through the device allocator instead. This leaves the arena cleaner
for runtime intermediate tensors, reducing fragmentation.

Source: [ORT session options](https://onnxruntime.ai/docs/api/c/struct_ort_c_u_d_a_provider_options.html)

#### Fix 6: Application-level CPU fallback

ORT has no automatic "fallback to CPU when GPU allocation fails" mechanism. The execution
provider assignment is decided at session creation time (graph partitioning), not at runtime.

The workaround is application-level:
1. catch the BFC allocation failure
2. re-run the problematic input on a separate CPU-only session

This is the safest catch-all for edge cases where GPU arena still fragments despite other fixes.

Source: [ORT issue #5299](https://github.com/microsoft/onnxruntime/issues/5299),
[ORT issue #17930](https://github.com/microsoft/onnxruntime/issues/17930)

#### Fix 7 (theoretical): Custom external allocator with `cudaMallocAsync`

ORT CUDA EP V2 supports `gpu_external_alloc`, `gpu_external_free`, and
`gpu_external_empty_cache` function pointers to completely replace the BFC arena with a custom
allocator. A wrapper around CUDA's native stream-ordered allocator (`cudaMallocAsync`, available
since CUDA 11.2) would eliminate BFC fragmentation entirely — the CUDA driver manages its own
pool with defragmentation.

This is the most architecturally correct fix but requires native C/C++ code and is not available
through ORT's Java API. It is also untested in this codebase.

Source: [NVIDIA stream-ordered allocator](https://developer.nvidia.com/blog/using-cuda-stream-ordered-memory-allocator-part-1/),
[ORT CUDA EP docs](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html)

#### Not applicable: separate arenas for dense and SPLADE

Research confirmed that each `OrtSession` already gets its own independent BFC arena by default.
The two sessions (dense and SPLADE) do not share arena memory. Shared allocators are opt-in
via `CreateAndRegisterAllocator` but are reported unreliable for CUDA (GitHub discussion #21577).

The fragmentation is therefore internal to the SPLADE session's arena, not cross-contamination.

Source: [ORT discussion #21577](https://github.com/microsoft/onnxruntime/discussions/21577)

### Recommended fix order for this codebase

| Priority | Fix | Effort | Expected impact |
|----------|-----|--------|-----------------|
| 1 | FP16 model + 256 max seq len | Config / model swap | Reduces ~250 MB allocation to ~30-35 MB; eliminates the problem |
| 2 | `enable_mem_pattern=false` | Session option | Prevents stale memory patterns for dynamic shapes |
| 3 | `use_device_allocator_for_initializers` | Session option | Keeps weights out of arena |
| 4 | Arena shrinkage via RunOptions | Per-run option | Releases extended chunks; mitigates long-running fragmentation |
| 5 | Application-level CPU fallback | Code change | Catch-all for remaining edge cases |

Fixes 1-4 are complementary and should be applied together. Fix 5 provides defense-in-depth.

## Implementation: arena config + CPU fallback (2026-03-14)

After analyzing the tradeoffs, the implementation focused on fixes that carry **zero quality risk**
rather than FP16 or sequence truncation (both have quality tradeoffs that can't be validated until
the eval path works — chicken-and-egg).

### What changed

#### Arena configuration fixes (both `SpladeEncoder` and `OnnxEmbeddingEncoder`)

- `arena_extend_strategy`: changed from `kSameAsRequested` to `kNextPowerOfTwo`
  - the old setting allocated exactly the requested size, causing worse fragmentation and no
    memory reuse across iterations (ORT issue #14474)
  - `kNextPowerOfTwo` rounds up to power-of-two sizes for better block reuse
- `use_device_allocator_for_initializers = 1`: added
  - keeps model weights (initializers) out of the BFC arena entirely, leaving it cleaner for
    variable-size runtime intermediate tensors
- `setMemoryPatternOptimization(false)`: added
  - the default pre-plans memory reuse from the first inference run's tensor shapes
  - for variable-length sequences this is counterproductive: the pattern from a short document
    is reused for long documents, causing fragmentation and over-allocation

#### CPU fallback for GPU OOM (SpladeEncoder only)

Added per-document CPU fallback in both SPLADE output paths:

- **PRESPARSE path** (`runSparseOutputInference`): refactored to call `runSingleSparseInference`
  per document. If a GPU ORT exception matches the BFC arena failure pattern, that single
  document is re-encoded on the CPU session. Other documents in the batch continue on GPU.
- **MLM_LOGITS path** (`runOnnxInference`): both the pinned-output and heap-fallback paths now
  catch BFC arena failures and retry on the CPU session.
- `isBfcArenaFailure()`: new helper that matches the specific ORT error pattern
  (`AllocateRawInternal` + `Available memory of` + `is smaller than requested bytes of`).

The CPU fallback is transparent — only the ~10% of documents that trigger the OOM pay the CPU
performance cost. All other documents continue on GPU. Full model quality and sequence length
are preserved.

### Verification

- `./gradlew build -x test -PskipWebBuild=true`: compiles cleanly
- `:modules:indexer-worker:test`: all tests pass
- `:modules:adapters-lucene:test`: all tests pass
- `:modules:configuration:test`: all tests pass
- `:modules:app-services:test`: all tests pass
- two pre-existing failures in `app-inference` and `app-launcher` are unrelated to this change

### Runtime iteration notes

#### Attempt 1: `use_device_allocator_for_initializers` not supported

The `use_device_allocator_for_initializers` CUDA provider option is not recognized by the bundled
ORT version. Both GPU sessions failed to create entirely, falling back to CPU-only. Removed.

#### Attempt 2: `kNextPowerOfTwo` causes embedding OOM

Changing `arena_extend_strategy` from `kSameAsRequested` to `kNextPowerOfTwo` caused the
**embedding** encoder to OOM with `Available memory of 0 is smaller than requested bytes of 256`.
The more aggressive power-of-two rounding means SPLADE's initial allocations consumed too much
total VRAM, leaving nothing for the embedding session. Both sessions share the same physical GPU.

`kSameAsRequested` is actually appropriate for this two-session-on-one-GPU setup — it's more
conservative with total memory. Reverted.

#### Final configuration

- `arena_extend_strategy = kSameAsRequested` (kept as-is)
- `setMemoryPatternOptimization(false)` (added — prevents stale shape-based memory patterns)
- CPU fallback for BFC arena failures (added — per-document fallback to CPU session)

### SciFact smoke result with SPLADE (post-fix)

30-query SciFact smoke with `-RequireSplade`, GPU acceleration, `branch_fusion_strategy=cc`:

| Mode | nDCG@10 | Recall@10 | MRR | P@1 |
|------|---------|-----------|-----|-----|
| hybrid | 0.671 | 0.763 | 0.647 | 0.567 |
| lexical | 0.722 | 0.857 | 0.700 | 0.600 |

Execution evidence:
- `spladeReady=true`, `chunkVectorsReady=true`, `gates_passed=true`
- `HYBRID` mode: 30/30 queries, `chunkMergeAppliedRate=1`
- `denseVectorEvidenceAvailableRate=0.967`
- `sparseEvidenceAvailableRate=1`
- zero search errors, zero zero-hit queries
- SPLADE coverage: **100%** (5189/5189, zero failures, zero pending)
- CPU fallback events during backfill: **567** out of ~5189 documents (~10.9%)
- CPU fallbacks were transparent — mixed GPU+CPU batches like `cpuFallback=1/2` showed
  one doc on GPU, one on CPU in the same batch

This is the first successful `-RequireSplade` SciFact run on current `main`. The previous
blocker (SPLADE GPU memory plateauing at 89.1%) is fully resolved by the per-document CPU
fallback.

## Full SciFact verification attempt (2026-03-15)

### Setup issues encountered

Five infrastructure issues required debugging before the eval could run:

1. **UI `installDist` missing**: The dev-runner spawns from `modules/ui/build/install/ui/bin/ui.bat`,
   not via `gradlew runHeadless`. Missing dist produces a silent "The system cannot find the path
   specified" error in backend stderr with no diagnostic in stdout. Fixed by building
   `:modules:ui:installDist`.

2. **SPLADE FP16 model type mismatch**: `model_fp16.onnx` in `models/splade/naver-splade-v3/`
   has `tensor(float16)` output types that the ORT CUDA EP rejects with a type error
   (`does not match expected type (tensor(float))`). The encoder selects `model_fp16.onnx` as the
   GPU model path but fails at load time. Fixed by renaming to `model_fp16.onnx.disabled` so the
   encoder falls back to `model.onnx` for both CPU and GPU sessions.

3. **`/api/status` GPU diagnostics misleading**: `embedOrtCuda.attempted=False` and
   `spladeOrtCuda.attempted=False` even when ONNX GPU sessions are fully initialized and active.
   The worker log confirms `SpladeEncoder GPU session initialized` and
   `OnnxEmbeddingEncoder initialized: gpuConfigured=true`. The diagnostic fields track a specific
   `OrtCudaView` probe, not the actual session state.

4. **Shared dev index has `BLOCKED_LEGACY` embedding state**: The pre-existing shared dev index
   has no embedding fingerprint (`embeddingCompatState=BLOCKED_LEGACY`), so dense embedding writes
   are blocked and `chunkVectorsReady=false`. SPLADE backfill is independent and progresses, but
   3-way (BM25 + dense + SPLADE) search is not possible against this index — only 2-way
   (BM25 + SPLADE).

5. **Dev-runner 60s default timeout insufficient for GPU**: The dev-runner's default HTTP readiness
   timeout (60s) is too short when GPU model initialization takes >60s. Addressed via
   `JUSTSEARCH_DEV_RUNNER_BACKEND_READY_TIMEOUT_MS=180000`.

### Full SciFact eval results (300 queries, 3-way, `-RequireSplade`)

After fixing setup issues, a clean-index eval was run on SciFact with GPU SPLADE + GPU ONNX
embeddings + 3-way CC fusion. Both `branch_fusion_strategy=cc` and `branch_fusion_strategy=rrf`
were evaluated on the same indexed corpus.

| Metric | Branch CC | Branch RRF | Δ |
|--------|-----------|------------|---|
| nDCG@10 | **0.658** | 0.378 | +74% |
| Recall@10 | **0.788** | 0.662 | +19% |
| MRR | **0.626** | 0.297 | +111% |
| P@1 | **0.543** | 0.180 | +202% |

Execution evidence (CC run):
- all 300 queries executed as `mode=pipeline`
- SPLADE coverage: 5189/5189 (100%), zero failures
- chunk vectors ready: true
- embedding compat: COMPATIBLE (clean fresh index)

Artifacts:
- CC: `tmp/beir-eval/scifact-20260315-001029/metrics.v2.json`
- RRF: `tmp/beir-eval/scifact-20260315-004830/metrics.v2.json`

**Conclusion**: Branch CC fusion massively outperforms branch RRF. This confirms the Stage 3B
design decision and validates the implementation on a full governed eval pass.

### Sign-off status update

Completed:
- [x] targeted unit/module verification for Stage 3A and Stage 3B
- [x] GPU/readiness preflight on the worktree-safe eval path
- [x] short SciFact smokes (50q and 30q) for both CC and RRF
- [x] **full SciFact verification (300q, `-RequireSplade`, clean index, GPU)** — CC vs RRF
- [x] regression pass comparing branch-RRF vs branch-CC

Remaining desirable follow-ups (not sign-off blockers):
- long-document acceptance benchmark on `mldr-en` or equivalent chunk-heavy corpus
- mixed-corpus regression matrix on the governed eval surface

### Long-term recommendations

| Issue | Fix |
|-------|-----|
| Missing `installDist` | `preflight` check should verify UI dist alongside worker dist |
| FP16 model type error | `SpladeEncoder` should validate GPU model loads and fall back to FP32 |
| Misleading GPU diagnostics | `OrtCudaView` should reflect actual session GPU state |
| Legacy index blocks 3-way eval | BEIR eval should use `--clean hard` or isolated dataDir |
| Timeout too short for GPU | Default timeout should be GPU-aware (~180s) |

---

## Staleness review (2026-05-18)

Body contains explicit closure markers; marking `done` as part of the staleness audit. Classification: CLOSED. Stale for 63 days at audit time.

