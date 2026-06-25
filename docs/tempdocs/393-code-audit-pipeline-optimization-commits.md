---
title: "393 — Code Audit: Pipeline-Optimization Commits from 391"
---

# 393 — Code Audit: Pipeline-Optimization Commits from 391

**Status:** In progress (2026-04-19 evening — Pass 2 + implementation:
4 Tier 1 items closed (1.1 no-issue, 1.2 debug-log applied, 1.3 tests
landed, 1.5 accepted-as-designed), 1.6 demoted to 3.6 after
blast-radius check. Only Tier 1 item remaining: 1.4 concurrent-writer
race (reproducer confirms 50/50 lost-update — fix decision pending).
**Created:** 2026-04-19 evening.
**Updated:** 2026-04-19 evening — Pass 2 rewrite after deeper
codebase verification corrected a first-pass mistake and validated
the other claims.
**Owner:** JustSearch agent (took over 2026-04-19).
**Scope:** Retrospective code-quality + correctness audit of the 10
commits landed in the 2026-04-19 pipeline-optimization session
(tempdoc 391).

> NOTE: Noncanonical doc. An audit tempdoc, not a design doc. Close
> each item with evidence; do not leave items in indefinite limbo.

---

## Purpose

After 391's 10 commits landed, three "wrong hypothesis" incidents
surfaced (see 391 § Meta-lesson). The mechanisms shipped are broadly
correct, but the speed of the session left several aspects under-examined:

- Is the SPLADE counter-drift bug class present for other fields?
- Does the new RMW safety net misfire on deployment against existing
  indices?
- Is the GPU cache's sidecar sufficient to guard against driver upgrades?
- Are the new code paths tested across the full state matrix?
- Do the new error-swallowing catches hide real bugs?

This doc enumerates these and related aspects, ranked by risk, with
concrete action items per concern. **It does not re-implement anything.**
Each action item either (a) closes with "no issue found" evidence,
(b) produces a separate fix commit, or (c) is accepted-as-designed
with explicit rationale.

---

## Scope — commits under audit

All 10 commits from the 2026-04-19 session. Listed in chronological
order with primary files:

| # | SHA | Subject | Primary files |
|---|---|---|---|
| 1 | `57fa91775` | SPLADE counter drift RMW fix | `WritePathOps.java` (+87 LOC), `BatchUpdateIntegrationTest.java` |
| 2 | `47b4ce3c0` | Env cleanup (stale JUSTSEARCH_MODELS_DIR) | `.claude/settings.local.json` |
| 3 | `3b19076eb` | jseval splade_churn_drops tolerance | `timeline.py`, `test_timeline.py` |
| 4 | `3af6773cc` | Arena bump 2048→3072 MB | `OnnxEmbeddingEncoder.java`, `ResolvedConfigBuilder.java`, `EnvRegistry.java` + tests |
| 5 | `cf38da21b` | Register promotion | `search-quality-register.md` |
| 6 | `02a2545d2` | Tempdocs landed | `docs/tempdocs/{311,390,391}.md` |
| 7 | `4fd8e7fb1` | GPU session cache (Issue B Option 1) | `OrtSessionFactory.java`, `OnnxSessionCache.java` (+147 LOC), `OnnxSessionCacheTest.java` |
| 8 | `565e27664` | jseval --warmup + env_fingerprint | `cli.py` (+147 LOC), `run.py`, `env_fingerprint.py` (new 193 LOC), tests |
| 9 | `e8ec00295` | Deterministic file enumeration | `SyncDirectoryOps.java` (+31/-12 LOC) |
| 10 | `2a5d153c1` | ner_total measurement bug | `timeline.py`, `test_timeline.py` |

**Out of scope** (covered by other tempdocs, not this audit):
- Pre-2026-04-19 commits in the pipeline-optimization area.
- Tempdoc 390 (whole-PC scope: BIOS / services / drivers).
- Tempdoc 358 (model selection / quantization choices).
- Tempdoc 311 (BFCArena root-cause history — only Phase 7 landed
  this session; earlier phases are not under audit here).

---

## Methodology update (2026-04-19 evening, two passes)

### Pass 1 (initial takeover rewrite)

A takeover agent ran a codebase investigation against each audit item
before any fix work. Pass 1 closed items where evidence made a clean
call, reordered by actual risk, and added a new item.

Net changes from pass 1:

- **1.1 closed** — full grep of `SSOT/catalogs/fields.v1.json` yields
  exactly two vulnerable fields (`splade_status`, `splade_retry_count`).
  No sweep needed.
- **1.2 reframed** (later reverted in pass 2) — pass 1 replaced the
  original mass-unfreeze / legit-exclusion concerns with a claimed
  silent behavior change on chunk documents.
- **1.4 promoted from former 2.6** — the concurrent-writer race is the
  highest remaining correctness question (real race window between
  gRPC handlers and the backfill loop).
- **1.5 accepted-as-designed** — former 1.4 (GPU cache driver-version).
  ORT graph-level optimizations are not driver-sensitive the way
  TensorRT engine caches are; runbook-only follow-up.
- **1.6 added** — latent duplicate `ner_status` entries in the
  classpath field catalog, surfaced during the 1.1 grep.

### Pass 2 (verification pass)

A self-critique flagged three uncertainties in pass 1. A second
codebase read + web research phase was run before any implementation.
Findings corrected pass 1:

- **1.2 pass-1 reframing was WRONG — reverted.**
  `ChunkDocumentWriter.java:175-179` explicitly sets
  `CHUNK_EMBEDDING_STATUS=PENDING` and `SPLADE_STATUS=PENDING` at chunk
  creation. Subsequent chunk RMW finds `splade_status` in doc-values
  → safety net does not fire on chunks. Chunks are part of SPLADE
  backfill by design (via `SpladeBackfillOps.java:84-89` `CHUNK_CONTENT`
  fallback), not by safety-net accident. The pass-1 claim that chunks
  were being silently pulled into SPLADE encoding is wrong. 1.2 now
  closes as "no realistic misfire found."
- **1.6 blast radius is zero today — demoted to Tier 3.**
  `modules/shell/src-tauri/src/lib.rs:583` launches Java with
  `-Djustsearch.ssot.path=<headless_dir>/SSOT` (on-disk SSOT inside
  the installer bundle, verified present by
  `scripts/ci/verify-installer-nsis-win.ps1:409`).
  `JustSearchConfigurationLoader.java:54-70` prefers explicit path →
  repo layout → classpath; the classpath fallback is dead code in
  every current deployment. The duplicate is still a pre-existing
  defect worth fixing (future standalone-JAR use, dead-code rot), but
  not a production risk. See item 3.6.
- **1.4 race framing refined.** Web confirmation: Lucene
  `SearcherManager.acquire()` returns the same snapshot within an
  NRT epoch, so the concurrent-write race window is real. But VDU
  gRPC handlers (`updateVduResult`, `markVduProcessing`,
  `resetVduStatusToPending`) require LLM + image content — neither is
  present in scifact eval runs. The race is a real-user-workload
  correctness concern, not a dev-eval-measurement issue. This changes
  urgency (ship-blocking for real users; non-blocking for the
  benchmarking PC) but not the tier.
- **1.5 acceptance confirmed with stronger citations.** cuDNN 8↔9 ABI
  compatibility is a build-time constraint, not a runtime driver
  upgrade issue. NVIDIA's "compute-capability-specific engines"
  language refers to TensorRT engines, not ORT CUDA EP graph caches.
  Runtime CUDA provider options (`tunable_op_enable`,
  `cudnn_conv_use_max_workspace`) are session-creation options, not
  baked into the optimized graph file. Acceptance stands.
- **Soft-delete does not interact with the safety net.**
  `WritePathOps.java:85-94, 119-125` uses `softUpdateDocument`
  directly and bypasses `readModifyWrite` entirely. The pass-1
  self-eval flagged "check soft-delete cell" — this now resolves to
  "not applicable."

Tier 2 remains unchanged. Tier 3 gains the demoted 1.6 as item 3.6.

---

## Tier 1 — High risk (load-bearing; likely to hide bugs)

### 1.1 [CLOSED 2026-04-19] — Scope of the non-stored-docvalue RMW drop pattern

**Disposition.** CLOSED — no issue found in the canonical root catalog.

**Evidence.** Full scan of `SSOT/catalogs/fields.v1.json` for fields
with `stored:false, docValues:true` yields exactly:

- `splade_status` — keyword, roles `[filter]`
- `splade_retry_count` — long

All other lifecycle status and retry-count fields are `stored:true,
docValues:true` and survive RMW through `storedFields().document()`:
`embedding_status`, `embedding_retry_count`, `chunk_embedding_status`,
`chunk_embedding_retry_count`, `vdu_status`, `vdu_retry_count`,
`ner_status`, `ner_retry_count`.

The hand-coded doc-values-read pattern in `WritePathOps.java:306-321`
is appropriately scoped to SPLADE under the root catalog.

**Follow-up.** See new item **1.6** — the classpath copy of the
catalog has a duplicate `ner_status` entry with conflicting `stored`
value. Surfaced during this grep; not caused by 391.

---

### 1.2 [CLOSED 2026-04-19 pass 2] — Safety-net misfire risk

**Disposition.** CLOSED — no realistic misfire found in any code
path exercised today. A debug counter is still worth adding as cheap
observability (see action below); no behavior fix is indicated.

**Pass-1 error retained for traceability.** Pass 1 claimed the safety
net silently pulled chunk docs into SPLADE encoding. This was
**wrong.** `ChunkDocumentWriter.java:175-179` explicitly initializes
both `CHUNK_EMBEDDING_STATUS` and `SPLADE_STATUS` to `PENDING` at
chunk creation. The pass-1 author stopped reading chunk field init
at line 127 and missed lines 175-179. On any subsequent chunk RMW,
the doc-values read at `WritePathOps.java:306-313` returns the chunk's
existing `splade_status` → the safety net at lines 340-345 does not
fire. Chunk-level SPLADE encoding is intentional (via
`SpladeBackfillOps.java:84-89` `CHUNK_CONTENT` fallback), not an
accidental consequence of the safety net.

**Original concerns — evidence clearing them.**

- **Mass unfreeze on deployment.** Requires an existing index that
  was damaged by the pre-fix bug. JustSearch's dev-only context runs
  predominantly with `--clean` (`jseval run --start-backend --clean`)
  which wipes the index each run — no persistent corrupted-doc
  population exists. Post-launch the concern would reappear, but any
  corrupted docs then present would be healed by the same safety net
  — a one-time burst, not an ongoing misfire. Accept as expected
  self-healing behavior.
- **Legitimately status-less docs.** `IndexingDocumentOps.java:225`
  unconditionally sets `SPLADE_STATUS=PENDING` on every new parent
  doc; `ChunkDocumentWriter.java:179` does the same for chunks. There
  is no exclusion path in the codebase that creates a doc without
  `SPLADE_STATUS`. The scenario is hypothetical.
- **Soft-deleted docs.** Verified: `WritePathOps.java:85-94, 119-125`
  uses `softUpdateDocument` directly and bypasses
  `readModifyWrite` — safety net is unreachable via the soft-delete
  path.

**Remaining action (lightweight observability only).** Add a
`log.debug("splade_status safety-net fired for doc {}", docId)` at
`WritePathOps.java:340-345` so any future misfire is visible to
future agents without code spelunking. No behavior change.

**Effort.** 10 min.
**Owner.** Unclaimed.
**Status.** CLOSED — no issue found; debug-log nice-to-have not a fix.

---

### 1.3 [CLOSED 2026-04-19 pass 2] — Test coverage adequacy for SPLADE RMW matrix

**Disposition.** CLOSED — 5 new integration tests added, all pass.
Fix behaviour verified across the critical cells of the state matrix.

**Tests landed** (in
`modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java`):

| Test | Cell | Outcome |
|---|---|---|
| `preserveSpladeTruePreservesFailedStatusWhenNonStored` | caller=N, doc=FAILED, preserveSplade=T | FAILED survives (no resurrection-as-PENDING) |
| `callerProvidedStatusOverridesDocValuesRestore` | caller=Y, doc=COMPLETED, preserveSplade=T | caller-supplied value wins |
| `preserveSpladeFalseResetsCompletedToPending` | caller=N, doc=COMPLETED, preserveSplade=F | reset branch fires |
| `safetyNetInjectsPendingWhenStatusMissingEverywhere` | caller=N, doc=none, preserveSplade=T | safety-net heals to PENDING |
| `preserveSpladeTruePreservesCompletedStatusWhenNonStored` | caller=N, doc=COMPLETED, preserveSplade=T | COMPLETED survives NER backfill RMW |

All tests exercise the production-matching schema via
`createRuntimeWithNonStoredSpladeStatus()` helper (schema has
`splade_status` as `stored:false, docValues:true`).

**Result.** Verified behaviour of all three branches of the fix:
doc-values restore (preserves PENDING/COMPLETED/FAILED), reset-to-
PENDING (non-preserve path), safety-net heal (missing-everywhere).
Caller-override contract pinned. FAILED preservation confirmed —
no resurrection bug.

**Status.** CLOSED — fix SHA to be assigned when committed.

---

### 1.4 [CLOSED 2026-04-22 via 402] Concurrent-writer race audit (PROMOTED from 2.6)

**Concern.** `WritePathOps.readModifyWrite` reads document state via
`IndexSearcher` (through `SearcherBridge.withSearcher`) and writes via
`IndexWriter.updateDocument(Term, Document)`. The sequence is
**not atomic**. Two concurrent RMW calls on the same docId can
interleave as `{read₁ → read₂ → write₁ → write₂}`, silently losing
write₁'s update. Lucene's `updateDocument` is atomic per-Term for the
internal delete+add, but the read→modify→write envelope is not.

**Evidence of concurrent callers.**
- `IndexingLoop.java:455` creates a single `indexing-loop` thread.
  All backfill ops (`SpladeBackfillOps`, `NerBackfillOps`,
  `EmbeddingBackfillOps`, `BgeM3BackfillOps`,
  `CombinedEnrichmentBackfillOps`) run on this single thread and
  cannot race against each other.
- `KnowledgeServerGrpcWiring.java:28` builds the Netty gRPC server
  with the default shared executor (no `.directExecutor()`, no
  custom). gRPC handlers run concurrently on a cached thread pool.
- `GrpcIngestService.java:645` (`updateVduResult`) and lines 1059,
  1069, 1090 (chunk regenerate paths) call
  `writePathOps().updateDocument(...)` on arbitrary docIds,
  concurrent with the indexing-loop backfill.
- `SearcherBridge.java:63-79` is an acquire/release wrapper with no
  per-docId or global RMW lock.

**Concrete race.** `updateVduResult` RPC arrives for parent doc X
while the combined-enrichment pass (on the indexing-loop thread) is
mid-RMW on doc X. Both read-phase reads see state S₀ (web
confirmation: Lucene `SearcherManager.acquire()` within the same NRT
epoch returns the same snapshot — see
https://blog.mikemccandless.com/2011/11/near-real-time-readers-with-lucenes.html).
RPC's write lands first and persists NER/embedding status updates;
the backfill write lands second and re-writes the doc with S₀
merged, silently reverting RPC's updates. Because `splade_status` is
now preserved post-fix, the observable would shift from "SPLADE
counter drift" to "NER or VDU status intermittently reverting."

**When this fires — urgency qualifier (pass 2).** The race is real
but **not observable during current dev-eval runs.** VDU-related
gRPC handlers (`updateVduResult`, `markVduProcessing`,
`resetVduStatusToPending`) require LLM + image content. Scifact eval
runs are text-only and run without `--llm`, so no VDU RPCs fire.
This is a correctness concern for real user workloads (live
filesystem watcher + image PDFs triggering VDU batch processing
from Main/Head), not a reason dev measurements are off.

**Risk.** Lost-update on status fields. Stronger than "race window
exists" — see reproducer result below.

**Reproducer result (2026-04-19 pass 2).**
`BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdRaceReproducer_393_1_4`
races two threads on the same docId with orthogonal field updates
(`field_a="A"` vs `field_b="B"`, both with `preserveSplade=true`).
Across 50 iterations, **50/50 lost an update** — only one field
survives every time. The race is effectively deterministic under
contention because `IndexWriter.updateDocument(Term, Document)`
deletes+adds on the term, so the second write fully supersedes the
first. The first thread's pre-read snapshot plus the second thread's
pre-read-same-snapshot means both writes carry forward the original
doc state (with their respective field added), and whichever writer
commits second wins outright.

This moves the race from "theoretical" to "guaranteed under overlap."

**Action.**
1. Enumerate every entry point that can reach
   `WritePathOps.readModifyWrite` from a non-indexing-loop thread —
   `GrpcIngestService` is the known caller; confirm there are no
   others (e.g. admin endpoints, watchdog resets).
2. Decide: (a) serialize RMW at `WritePathOps` via a per-docId
   lock (coarse but correct), (b) serialize at the docId level with
   a lock-sharding helper, or (c) document the invariant that only
   one RMW-capable thread runs at a time and enforce it in callers.
3. Add a javadoc block on `readModifyWrite` stating the concurrency
   contract whatever option wins.
4. When a fix lands, flip the reproducer's assertion from
   `assertEquals(0, writeFailures)` (current sanity check) to
   `assertEquals(0, lostUpdates)` — turning the diagnostic into a
   regression guard.

**Effort.** 1 hr for caller trace + decision; 1-2 hr for per-docId
locking implementation if chosen.
**Owner.** Unclaimed.
**Status.** OPEN.

---

### 1.5 [ACCEPTED-AS-DESIGNED 2026-04-19] — GPU cache driver-version strategy

**Disposition.** ACCEPTED-AS-DESIGNED with a low-effort runbook
follow-up (tracked as item 3.5).

**Original concern.** The GPU cache sidecar (`OnnxSessionCache.java:333-344`)
writes `mtime:X size:Y ort:Z ep:cuda` but does not capture CUDA
driver version. The draft worried this could silently produce stale
kernels / numerical drift after a driver upgrade.

**Rationale for acceptance (strengthened pass 2).** The concern rests
on an analogy to TensorRT engine caches (which are compiled kernels
and are driver/compute-capability sensitive). ORT's
`setOptimizedModelFilePath` does not produce compiled kernels:

1. The `.cuda.optimized` artifact is the result of ORT graph-level
   transformations — constant folding, operator fusion, layout
   optimization — producing an optimized ONNX graph. See
   https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html.
2. CUDA kernel selection (cuDNN/cuBLAS heuristics) happens at
   runtime on every session creation, keyed on the compute
   capability / driver state at that moment. A driver upgrade does
   not reuse stale kernels — they are not cached on disk.
3. CUDA provider options that affect runtime behaviour
   (`tunable_op_enable`, `cudnn_conv_use_max_workspace`,
   `arena_extend_strategy` — see `OrtSessionFactory.java:84-105`)
   are session-creation options applied at every session open,
   **not** baked into the optimized graph file.
4. ORT cuDNN 8↔9 ABI compatibility is a build-time constraint of
   the ORT native library (per ORT CUDA EP docs) — not a runtime
   driver upgrade concern.
5. NVIDIA's "generated engines specific to compute capability"
   quote refers to TensorRT engines, not ORT CUDA EP graph caches.
   Different EPs, different cache semantics.
6. ORT documentation calls out EP-specificity and hardware
   compatibility (AVX2 / layout) but does not document driver-
   version sensitivity for the CUDA EP optimized graph cache.
7. If a driver upgrade broke cache compatibility in edge cases, the
   failure mode is a loud `createSession` exception, not silent
   numerical drift — detectable.

Runtime v2 sidecar work is over-engineering for the current scale
and risk profile. The canonical mitigation is operator-visible:
delete the `.cuda.optimized` and `.cuda.opt-meta` next to the model
if a driver upgrade triggers session creation failures.

**Follow-up.** See item **3.5** — add a runbook paragraph
describing the symptom (post-driver-upgrade session creation
failure) and the one-line remediation (`rm models/**/*.cuda.optimized
models/**/*.cuda.opt-meta`, restart worker). Low effort, in canonical
troubleshooting docs.

---

### 1.6 [DEMOTED to 3.6 pass 2] — Classpath field catalog duplicate `ner_status` entries

**Disposition.** DEMOTED to Tier 3 hygiene after pass 2 blast-radius
verification. The duplicate is real, but the classpath code path is
effectively dead in every current deployment:

- `modules/shell/src-tauri/src/lib.rs:583` launches Java with
  `-Djustsearch.ssot.path=<headless_dir>/SSOT` — an on-disk SSOT
  inside the installer bundle.
- `scripts/ci/verify-installer-nsis-win.ps1:409` enforces that SSOT
  is present on disk in the bundle.
- `JustSearchConfigurationLoader.java:54-70` prefers explicit path
  → repo layout → classpath. In every current deployment (dev or
  packaged prod) one of the first two wins, so the classpath
  fallback is never exercised.
- No test exercises `loadFieldCatalogFromClasspath()`.

Blast radius today: zero. Still a pre-existing defect worth cleaning
up (dead-code rot, hypothetical future standalone-JAR use). See
item **3.6** for the detailed action list.

---

## Tier 2 — Medium risk (correctness gotchas; worth auditing)

### 2.1 [CLOSED 2026-04-22] Error swallowing in `readKeywordDocValue` / `readNumericDocValue`

**Concern.** Both helpers in `WritePathOps.java` (lines 615-651,
commit `57fa91775`) catch `IllegalStateException` with the comment
"Field has no SortedDocValues in this segment (not indexed /
sparse)." But this exception can also signal:
- Reader-state corruption (segment closed, reader leaked).
- Schema drift (field flipped to docValues=false without index rebuild).
- Concurrent segment merge invalidating the reader.

Swallowing all three the same way makes real bugs invisible.

**Action.**
1. Add debug-level log on catch: `log.debug("docvalues read failed
   for {} on doc {}: {}", field, docId, e.getMessage())`.
2. Consider narrowing: only catch the specific "no doc-values" signal
   if Lucene exposes one; re-throw other `IllegalStateException`.

**Effort.** 15-30 min.
**Owner.** Unclaimed.
**Status.** OPEN.

---

### 2.2 Observability gap in the GPU cache path

**Concern.** The GPU cache (commit `4fd8e7fb1`) logs cache hit/miss
per-session-creation in worker.log. But:
- No aggregate metric in `/api/status` or similar.
- No easy way to answer "is the cache helping in production?" without
  grepping logs.
- `worker.log` is overwritten on `--clean` (391 Tier 3 open issue).

**Action.**
1. Expose cumulative counters: `cacheHitsTotal`, `cacheMissesTotal`,
   in the worker's status endpoint or metrics registry.
2. Consider: session creation time histogram (p50/p95/p99) exposed per
   encoder type, so we can validate the cache saving actually shows
   up in the numbers, not just in log messages.

**Effort.** 1-2 hours.
**Owner.** Unclaimed.
**Status.** OPEN.

---

### 2.3 [CLOSED 2026-04-22 via 402] Performance on RMW hot path

**Concern.** The SPLADE fix (commit `57fa91775`) adds two doc-values
reads per RMW call. Per call, the cost is:
- `searcher.getIndexReader().leaves()` — list access.
- `ReaderUtil.subIndex` — O(log segments) binary search.
- `DocValues.getSorted` / `DocValues.getNumeric` — segment-level open.
- `advanceExact` — O(1) for single-doc seek.

Per-call cost is tiny, but scales linearly with RMW volume and with
segment count. At 5184 docs × ~10 RMW cycles during enrichment,
that's ~100K extra doc-values reads per run. Heavily-fragmented
indices (100+ segments) compound the binary-search cost.

**Evidence.** No microbenchmark in the commit. Observed
enrichment-phase throughput in post-fix runs: 20-26 docs/sec — fine
in absolute terms but no isolation of the overhead.

**Action.**
1. Microbench on an index with 1 segment, 10 segments, 100 segments.
   Expected: <1% overhead at 1 segment; <5% at 100.
2. If overhead >5% at production-realistic segment counts, consider
   caching the doc-values reader on the RMW context.

**Effort.** 45-60 min.
**Owner.** Unclaimed.
**Status.** OPEN.

---

### 2.4 [ACCEPTED-AS-DESIGNED 2026-04-19 pass 2] — Arena sizing vs. dynamic GPU capacity

**Disposition.** ACCEPTED-AS-DESIGNED (fixed 3072 MB) for now. The
long-term adaptive work is deferred to a new tempdoc because it
requires a major design pass before any implementation.

**Rationale.** The `DEFAULT_GPU_MEM_MB = 3072` constant in
`OnnxEmbeddingEncoder.java:61` is empirically validated by tempdoc
311 Phase 7 A/B (2048 MB fragments, 4096 MB causes cross-arena VRAM
contention and 5× regression on 12 GB dev GPU). Upstream ORT does
not publish a sizing heuristic, percentage-of-VRAM guidance, or
multi-session recommendations — any adaptive logic would be
JustSearch-original and would need its own A/B validation per
hardware tier. Replacing a validated constant with a synthesized
heuristic without that validation is exactly the "change the number
and declare victory" pattern this audit was created to prevent.

**Long-term.** Adaptive arena sizing *is* necessary as the hardware
envelope widens (8 GB vs 12 GB vs 24 GB) and the model stack
evolves (358 successor). This is a major design question, not a
tuning tweak. Opened as tempdoc 395 § A1
(`docs/tempdocs/395-adaptive-pipeline-considerations.md`) — a
considerations register for adaptive pipeline parameters. Item A1
there is the direct successor to this concern.

**Follow-up action (optional, low-priority).** Add a bootstrap log
line at encoder init reporting detected total VRAM, chosen arena
size, and the ratio. Gives future tuners data without committing to
adaptive logic. ~20 min. Not blocking this tempdoc close-out.
Tracked as item 3.7 below.

**Owner.** Unclaimed (395 A1 needs a design pass before work).
**Status.** ACCEPTED-AS-DESIGNED (→ 395 A1 for long-term design).

---

### 2.5 [PARTIALLY CLOSED 2026-04-19 pass 2] — `jseval --warmup` flag CV validation

**Concern.** Commit `565e27664` added `--warmup N` with a claim
("removes run-1 outlier; expected CV 4.4% → ≤1.5%"). The flag works
mechanically (help parses; negative values reject), but no measurement
validated the CV reduction. Tempdoc 391 punted this to "Experiment 2"
and never executed it.

**What was measured (pass 2, 15-min time-boxed).**

Ran 3 consecutive `jseval run --dataset scifact --pipeline
--start-backend --clean --max-queries 0` (no `--warmup`, no queries)
on the dev box at ~warm state. Output in
`F:/JustSearch/tmp/jseval-warmup-cv-2026-04-19/`.

| Run | Ingest wall | Chunk 100% | Embed 100% | SPLADE 100% | NER complete |
|---|---|---|---|---|---|
| 1 | 216.9 s | 185 s | 212 s | 212 s | 212 s |
| 2 | 217.0 s | 189 s | 212 s | 212 s | 212 s |
| 3 | 232.3 s | 198 s | 225 s | 227 s | 225 s |

Mean 222.07 s, stddev 8.86 s, **CV = 4.0%**.

**Findings.**

1. **The ~4% baseline CV reproduces.** Measured 4.0% vs. the 4.4%
   claimed in commit `565e27664` — consistent. The baseline
   assumption is intact.
2. **The variance pattern doesn't match the `--warmup` theory.**
   Runs 1 & 2 were nearly identical (0.05% CV between them); run 3
   was the outlier, slower by 7%. The `--warmup 1` flag was added
   on the assumption that **run 1 is the cold outlier**. If outliers
   land on arbitrary runs (as observed here), a warmup pre-run
   changes run 1 without fixing the random-noise problem.
3. **Likely cause of the run-3 slowdown**: ambient dev-box activity
   during that ~4 min window (Defender scan, Windows Update probe,
   background apps). This is the "dirty dev box" noise already
   flagged in tempdoc 390.

**What this does and does NOT validate.**

- ✅ Reproduces the ~4% baseline CV.
- ❌ Does NOT validate the 4.4% → 1.5% improvement claim. That
  requires N=5+ runs WITH `--warmup 1` plus N=5+ matched runs
  WITHOUT warmup — ~60+ min of measurement which did not fit
  within the 15-min time box.
- ❓ Suggests `--warmup 1` may be solving a specific pattern
  (always-run-1-is-cold) that doesn't reliably reproduce under
  real dev-box noise. The flag may still help in specific cold-boot
  conditions (post-reboot, post-Defender-full-scan) but its general
  value as a default is unproven.

**Remaining action (DEFERRED — not done this pass).**

To fully close 2.5, a future measurement pass needs:

1. N=5 runs with `--warmup 1` on a freshly rebooted or idle box.
2. N=5 matched runs without `--warmup` under the same conditions.
3. Compare CVs; report whether the 4.4% → ≤1.5% claim holds.
4. If it holds: propose defaulting `--warmup 1` for benchmark mode.
   If it doesn't: update 390/391 methodology to reflect real-world
   variance sources (ambient load, not just cold-start).

Estimated effort for the remaining validation: 60-90 min of
measurement on a reasonably idle dev box, plus ~10 min analysis.

**Owner.** Unclaimed for the remaining measurement.
**Status.** PARTIALLY CLOSED — baseline reproduced (4.0% ≈ 4.4%);
improvement claim (→ 1.5% with warmup) remains unvalidated and
deferred to a future 60-90 min measurement pass.

---

## Tier 3 — Lower priority (style, maintainability)

### 3.1 [CLOSED 2026-04-22 via 402] Code duplication between `readKeywordDocValue` and `readNumericDocValue`

`WritePathOps.java` helpers (lines 615-651) share ~70% structure
(`getIndexReader().leaves()` → `subIndex` → segment access →
`advanceExact`). Could extract:

```java
private static <T> T readDocValueAt(
    IndexSearcher searcher, int globalDocId, String field,
    DocValuesReader<T> reader) throws IOException { ... }
```

**Effort.** 30 min. Style-only.
**Status.** OPEN.

### 3.2 [CLOSED 2026-04-22 via 402] Comment semantics drift in `WritePathOps.readModifyWrite`

The pre-existing comment block around the SPLADE reset-to-PENDING
branch was rewritten in commit `57fa91775`. The three branches
(doc-values restore at 306-321; reset to PENDING at 329-334;
safety-net PENDING at 340-345) coexist with overlapping conditions.
Worth re-reading for any inputs where two branches fire or none fire
when one should. Tightly related to 1.3 test coverage.

**Effort.** 30 min of careful reading.
**Status.** OPEN.

### 3.3 [CLOSED 2026-04-22 via 396] `env_fingerprint.py` PowerShell dependency

Commit `565e27664` shells out to `powershell -NoProfile
-NonInteractive -Command ...` for top-N processes. Works on Windows
only. On non-Windows, the probe safely returns `[]`. But: if
PowerShell is blocked by policy (common in enterprise environments),
the probe hangs until timeout. `_PROBE_TIMEOUT_S = 5` caps this at
5 seconds — acceptable, but per-run overhead of 5s on a
PowerShell-disabled box adds up over a triple.

**Action.** Consider falling back to `wmic` or bypassing the probe
entirely if PowerShell isn't on PATH (adds ~20 LOC detection logic).

**Effort.** 30 min.
**Status.** OPEN.

### 3.4 [CLOSED 2026-04-22] `SyncDirectoryOps` sort timing vs. enqueue batching

Commit `e8ec00295` collects the full file list, sorts, then enqueues
in batches. For very large corpora (100K+ files), this delays the
start of enqueue by the walk-complete time, losing pipelining with
the indexing loop. 391 commit message explicitly accepts this
trade-off, but it's worth an explicit scalability note for future
maintainers.

**Action.** Add a comment noting the memory / pipelining trade-off
and the scale point at which streaming sort would be needed.

**Effort.** 10 min.
**Status.** OPEN.

### 3.5 [CLOSED 2026-04-22] [NEW] GPU cache driver-upgrade operator runbook entry

Follow-up to item 1.5 (accepted-as-designed). Add a short paragraph
in `docs/how-to/` (or canonical troubleshooting doc) describing:

- Symptom: ORT session creation fails after an NVIDIA driver upgrade
  (loud error, not silent drift).
- Remediation: delete `.cuda.optimized` + `.cuda.opt-meta` sidecars
  next to affected `.onnx` models in `models/`; restart worker.
- Prevention (optional): document that cache files are safe to
  delete at any time — they are rebuilt on next startup.

**Effort.** 15 min.
**Status.** OPEN.

### 3.6 [CLOSED 2026-04-22] [DEMOTED from 1.6 pass 2] — Classpath field catalog duplicate `ner_status`/`ner_retry_count`

**Concern.** `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json`
contains duplicate entries:

- `ner_status` at lines **387-391** (`stored:true, docValues:true`)
- `ner_status` at lines **449-453** (`stored:false, docValues:true`)
- `ner_retry_count` at lines **394-398** (`stored:true, docValues:true`)
- `ner_retry_count` at lines **455-459** (`stored:true, docValues:true`)

The root canonical `SSOT/catalogs/fields.v1.json` has each field
defined once (with `stored:true`).

**Why this is latent and NOT a production blocker (pass 2
verification).**

- `FieldCatalogDef.java:34` builds `byId` via
  `Collectors.toUnmodifiableMap(FieldDef::id, f -> f)`, which throws
  `IllegalStateException` on duplicate keys — confirmed from code.
- But `JustSearchConfigurationLoader.java:54-70` prefers explicit
  path → repo layout → classpath. The shell
  (`modules/shell/src-tauri/src/lib.rs:583`) passes
  `-Djustsearch.ssot.path=<headless_dir>/SSOT`, and
  `scripts/ci/verify-installer-nsis-win.ps1:409` enforces the
  on-disk SSOT is present in the bundle. Classpath fallback is dead
  code in current deployments.
- No test exercises `loadFieldCatalogFromClasspath()`. A future
  standalone-JAR deployment (no `justsearch.ssot.path`, no repo
  layout) would crash at startup.

**Git blame.** Last touched by `d2bb27cfe feat(326): entity boost
eval + NER quality validation + author field`. Pre-dates the 391
session — pre-existing defect, surfaced by this audit.

**Action.**
**Two implementation options** (author's choice):

**Option A — Dedupe + guard (preserve classpath fallback).**
1. Delete the duplicate entries at lines 449-459 of the classpath
   copy. Keep the first `ner_status`/`ner_retry_count` definitions
   at lines 387-398 (match the root canonical catalog).
2. Add a unit test that loads `FieldCatalogDef` from the classpath
   resource and asserts successful parse — currently would fail, a
   regression guard.
3. Add an SSOT validation check (`SsotValidator` /
   `modules/ssot-tools/`) that asserts no duplicate `id` in either
   catalog.
4. Update `/ssot-catalog` skill with a duplicate-detection
   checklist item (see § C.2).

Effort: 15 min (dedupe + unit test) + 30 min (SSOT validator + skill).

**Option B — Delete classpath fallback entirely (pass-2 finding).**
Investigation confirmed `loadFieldCatalogFromClasspath()` has zero
external callers; `isClasspathMode()` has zero callers anywhere.
Classpath is dead code in every current deployment. Deleting it
makes "SSOT must be on disk" a hard invariant:
1. Remove `loadFieldCatalogFromClasspath()`, `isClasspathMode()`,
   `CLASSPATH_FIELD_CATALOG`, `CLASSPATH_FIELD_CATALOG_ALT`, and the
   classpath branch in `loadFieldCatalog()` in
   `JustSearchConfigurationLoader.java`.
2. Delete the resource file
   `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json`
   (which is the file with the duplicate anyway).
3. If anything depends on the resource existing on classpath (no
   hits found in grep, but verify at build time), add a test that
   fails if the resource is re-introduced.
4. The SSOT validator + skill update from option A is still worth
   doing as a belt-and-braces against future catalog divergence.

Effort: 20 min (removal + verify no downstream breaks) + 30 min
(validator + skill).

**Preferred.** Option B — simpler invariant, less dead code, avoids
a duplicate-detection regression guard for a code path that
shouldn't exist in the first place.
**Owner.** Unclaimed.
**Status.** OPEN.

### 3.7 [PARTIAL 2026-04-22] [NEW from 2.4 close-out] — Bootstrap log for detected VRAM vs. chosen arena size

Follow-up to item 2.4 (ACCEPTED-AS-DESIGNED). Emit a single log line
at encoder construction recording:

- Detected total VRAM (`VramDetector.getTotalVramBytes()`).
- Chosen arena size (`DEFAULT_GPU_MEM_MB` or env override).
- Ratio of arena to total VRAM.
- Number of concurrent encoder sessions sharing this GPU (embed,
  SPLADE, NER, reranker).

Gives future tuners (and the tempdoc 395 A1 design pass) the data to
decide whether the constant needs revisiting per hardware tier,
without committing to adaptive logic today.

**Effort.** 20 min.
**Owner.** Unclaimed.
**Status.** OPEN.

---

## Cross-cutting concerns (not commit-scoped)

### C.1 No production telemetry for any of the new paths

None of the 10 commits add metrics that a production operator could
monitor. The GPU cache, the RMW safety net, and the env-fingerprint
probe all run silently. For a benchmarking PC this is fine; for a
shipped product it's a gap.

**Recommendation.** Once items 2.2 (cache observability), 1.2
(safety-net observability) are done, consider a unified metrics doc.
Not urgent — not blocking for dev-mode use.

### C.2 [CLOSED 2026-04-22] [NEW] SSOT catalog integrity: duplicate-id detection

Tied to item 1.6. The dual-copy sync requirement is already
documented in the `/ssot-catalog` skill + CLAUDE.md "Common
Pitfalls" table. This audit found that the existing documentation
does not prevent a second class of failure: duplicate `id` entries
within a single catalog. Warrants a `SsotValidator` check + skill
update.

### C.3 Tempdoc 393 (this doc) itself

This audit should close out. "Closing out" means: every Tier 1 and
Tier 2 item has a status of `CLOSED` with:
- A commit reference (if fixed),
- A "no issue found" evidence citation (if investigated and cleared),
- An explicit `ACCEPTED-AS-DESIGNED` with rationale.

Tier 3 can remain OPEN as a "future cleanup" list.

---

## Action items summary (ordered by risk)

| # | Priority | Effort | Scope | Status |
|---|---|---|---|---|
| 1.1 | HIGH | — | Scope of non-stored-docvalue RMW drop pattern | **CLOSED (no issue in root catalog)** |
| 1.2 | HIGH | 10 min | Safety-net misfire risk | **CLOSED (pass 2)** — debug-log applied |
| 1.3 | HIGH | — | RMW state-matrix tests (5 tests, all passing) | **CLOSED (pass 2)** |
| 1.4 | HIGH | 1-3 hrs | Concurrent-writer race — reproducer shows 50/50 lost updates | OPEN |
| 1.5 | HIGH | — | GPU cache driver-version strategy | **ACCEPTED-AS-DESIGNED** (→ 3.5) |
| 1.6 | — | — | Classpath catalog duplicate — **demoted to 3.6** | DEMOTED |
| 2.1 | MED | — | Log on catch(IllegalStateException) | **CLOSED (pass 2)** — debug-log applied |
| 2.2 | MED | 1-2 hrs | GPU cache hit/miss metrics | OPEN |
| 2.3 | MED | 45-60 min | RMW doc-values microbench | OPEN |
| 2.4 | MED | — | Arena sizing (fixed 3072 MB) | **ACCEPTED-AS-DESIGNED** (→ 395 A1, 3.7) |
| 2.5 | MED | 60-90 min | `--warmup 1` CV validation | **PARTIAL (pass 2)** — baseline reproduced (4.0% ≈ 4.4%); improvement claim deferred |
| 3.1 | LOW | 30 min | Extract doc-values read helper | OPEN |
| 3.2 | LOW | 30 min | Comment semantics review | OPEN |
| 3.3 | LOW | — | PowerShell dep fallback (silent-skip) | **CLOSED (pass 2)** — full psutil migration → tempdoc 396 |
| 3.4 | LOW | — | Sort-timing scalability note | **CLOSED (pass 2)** — comment expanded |
| 3.5 | LOW | — | GPU driver-upgrade runbook entry | **CLOSED (pass 2)** — added to `docs/how-to/test-gpu-locally.md` |
| 3.6 | LOW | 20-50 min | Classpath catalog fallback — delete vs dedupe | OPEN |
| 3.7 | LOW | — | VRAM-vs-arena startup log + fix gpuFull(0) placeholder | **CLOSED (pass 2)** — Option C applied |

**Tier 1 remaining: 1.4 only.** 1-3 hrs depending on chosen fix option.
**Tier 2 total:** 3-6 hrs (2.4 accepted → 395).
**Tier 3 total:** ~2.75 hrs (+ 20 min from 3.7).

---

## Exit criteria

This tempdoc is complete when:

1. **Every Tier 1 item has status `CLOSED` or `ACCEPTED-AS-DESIGNED`**
   with one of:
   - Commit SHA of the fix.
   - "No issue found" — with the evidence that cleared it (a link
     to a grep result, a test output, a log excerpt).
   - `ACCEPTED-AS-DESIGNED` — with a one-paragraph rationale.
2. **Every Tier 2 item has status `CLOSED` or explicit `DEFERRED`**
   with a pointer to where it's deferred (another tempdoc, an
   issue, a canonical doc).
3. **Tier 3 items can remain `OPEN`** as future-cleanup items.
4. **Cross-cutting concerns (§ C.1, C.2, C.3) are either closed or
   explicitly carried forward** to a new tempdoc.

After exit, this doc moves from `Status: In progress` to
`Status: Closed` in the header. The action items summary table
reflects final state of every item.

---

## Sources

- All 10 commits in scope (see § Scope table).
- Tempdoc 391 `§ Meta-lesson: three "wrong hypothesis" diagnoses` —
  the direct motivator for this audit.
- Tempdoc 391 `§ Handoffs → H-AUDIT-1` — formal handoff.
- `SSOT/catalogs/fields.v1.json` + classpath copy at
  `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json`
  — schema reference for items 1.1 and 1.6.
- `WritePathOps.java`, `OnnxSessionCache.java`, `OrtSessionFactory.java`,
  `SyncDirectoryOps.java`, `cli.py`, `env_fingerprint.py`,
  `timeline.py` — code under audit.
- `FieldCatalogDef.java`, `JustSearchConfigurationLoader.java`,
  `ChunkDocumentWriter.java`, `IndexingLoop.java`,
  `KnowledgeServerGrpcWiring.java`, `SearcherBridge.java`,
  `GrpcIngestService.java`, `IndexingDocumentOps.java`,
  `SpladeBackfillOps.java`, `EmbeddingBackfillOps.java` — additional
  files consulted during the 2026-04-19 takeover investigation
  (items 1.1-1.6).
- ONNX Runtime graph optimizations doc:
  https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html
  — used to reject the driver-version-sensitivity premise in 1.5.
- ONNX Runtime CUDA Execution Provider docs:
  https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html
  — pass-2 cuDNN 8↔9 ABI distinction for 1.5.
- NVIDIA Technical Blog — CUDA and TensorRT EP in ORT:
  https://developer.nvidia.com/blog/end-to-end-ai-for-nvidia-based-pcs-cuda-and-tensorrt-execution-providers-in-onnx-runtime/
  — distinguishes TensorRT engine caches (driver/compute-capability
  sensitive) from CUDA EP graph caches (not) for 1.5.
- Elasticsearch Labs "Concurrency bugs in Lucene" + Apache Lucene
  `IndexWriter` javadoc — used to frame the RMW atomicity
  contract in 1.4.
- Mike McCandless "Near-real-time readers with Lucene's SearcherManager":
  https://blog.mikemccandless.com/2011/11/near-real-time-readers-with-lucenes.html
  — pass-2 confirmation that concurrent `acquire()` within an NRT
  epoch returns the same snapshot (the race-window premise for 1.4).
- `modules/shell/src-tauri/src/lib.rs`,
  `scripts/ci/verify-installer-nsis-win.ps1` — pass-2 evidence for
  the 1.6 → 3.6 demotion (classpath catalog not loaded in
  packaged deployments).
- `docs/tempdocs/395-adaptive-pipeline-considerations.md` — spawned
  from item 2.4 close-out. Long-term adaptive arena sizing (and
  related considerations) tracked there pending a design pass. Item
  3.7's fix (pulling `HardwareProfile` from `InstallContract`) seeds
  395 § A1's detection pathway.
- `docs/tempdocs/396-env-fingerprint-psutil-migration.md` — spawned
  from item 3.3 close-out. Strategic follow-up to migrate
  `env_fingerprint.py` fully to psutil.
- ONNX Runtime CUDA EP docs + issue #14474 + discussion #21577 — 2.4
  research trail, reused in 395 § A1.
