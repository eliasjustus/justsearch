---
title: "Corpus-build throughput: increase indexing/enrichment throughput and decrease total corpus-build time — the indexing-side sibling of 648 that its stub explicitly reserved ('a separate target… spin a sibling stub'). Purpose kept deliberately GENERAL for now: make building an enrichment-complete (dense-searchable) index over a multi-thousand-file corpus fast enough that repeated agentic-utility eval runs (624/673) stop monopolizing the shared dev stack for hours. Method inherited from 647: attribution before allocation before optimization — no lever is chosen until measurement says which cost dominates."
type: tempdocs
status: "open — Phases A-E complete (2026-07-07), durable fixes SHIPPED on PR #90 (branch worktree-691-corpus-throughput). Headline: NER's missing fp16 variant silently ran the INT8 CPU model on CUDA (~10× per-call); with the model restored + arena 512→2048, battlefield corpus build 333.4s → 124.0s (2.69×). Batch-size tuning measured as a dead end (Phase E); the remaining embed lever is DUPLICATE chunk embedding (E-5, ~20% of build) — retrieval-semantics-affecting, decision point recorded, NOT implemented. See §Evidence index and §Unverified assumptions before continuing."
created: 2026-07-07
author: agent session 2026-07-07 (Fable orientation pass; three-subagent tempdoc/code survey + targeted source verification)
category: performance / indexing / eval-infrastructure
related:
  - 648-engine-latency-optimization-cross-encoder-cost   # the query-latency sibling; its stub explicitly reserved this indexing-side slot
  - 640-engine-performance-budget-latency-throughput-footprint   # shipped the primary+enrichment docs_per_s relative ratchet that will protect any win here
  - 647-engine-performance-attribution-and-budget-allocation     # the method (attribution → allocation → optimization); NOTE the tension recorded below re its D-3 decision
  - 686-real-pdf-corpus-and-tika-pressure-measurement    # the extraction leg (real Tika/PDF pressure) — coordinate, don't absorb
  - 624-agentic-retrieval-eval-rebuild                   # the consumer: eval runs need enrichment-complete multi-thousand-doc corpora, repeatedly
  - 673-agent-utility-standing-regression-ratchet        # confirms the repeat cadence (standing gate, not one-time)
  - 278-indexing-throughput                              # superseded predecessor (stale baseline, predates current stack)
---

# 691 — Corpus-build throughput / total corpus-build time

## Purpose (deliberately general)

Increase indexing throughput / decrease total corpus-build time — the wall-clock
from "corpus files on disk" to "enrichment-complete, dense-searchable index".
The motivating consumer is the agentic-retrieval eval (624): its U0 premise
requires multi-thousand-doc corpora, each run needs an enrichment-complete
index, runs repeat on a standing cadence (673), and every build holds the
shared dev stack exclusively — so build time directly blocks all other
development. But the purpose is the engine property itself, not one eval's
schedule: corpus-build throughput has never had an optimization pass
(648 confirms only query latency got one).

## Known state (orientation, 2026-07-07)

The only measured baseline — tempdoc 640 §C (enron-qa, 5,459 docs, GPU dev
box, 2026-06-24):

- Primary indexing (keyword-searchable): **97.3 docs/s** (56 s).
- Enrichment-complete (dense-searchable): **8.2 docs/s** (668 s) — a ~12× gap.
- Stage split of the 668 s: embedding 346 s, NER 124 s, SPLADE 106 s.
- Per-batch encoder profiles: embed p50 42.9 ms / p95 161.7 ms (batch≤8),
  SPLADE ≈75 ms (batch≤4), NER ≈3 ms (batch≤16).

Recorded eval-side costs consistent with this: ~30–40 min ingest/calibration
overhead per 624 run at ~390-doc scale; ~90 min projected for a full
VDU/scan-corpus pass; 666 recorded a ~19-min MIRACL-de index build.

Pipeline structure (source-verified):

- Enrichment (embed/SPLADE/NER) is deliberately **not** in the primary indexing
  path — it runs as background backfill tuned for desktop politeness
  (`BackfillScheduler.runIdleCycle`, yields on user-active / energy / GPU-claim;
  combined read-modify-write tight loop when applicable, else an
  individual-stage fallback that serializes embed → NER → SPLADE corpus-wide).
- Every throughput-relevant number is a hardcoded constant
  (`loop/ops/LoopPacingPolicy.java`): poll batch 16, embedding backfill batch
  100, commit every 10 s / 1000 docs; ORT sub-batches: embed 8
  (`OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE`), SPLADE **4** on GPU (cut from 16
  as a VRAM-fragmentation workaround, `SpladeEncoder.java` comment), NER 16.
  No config/env override exists for any of them.
- Tika extraction is fully serial across files (`TimeboxedContentExtractor`'s
  single-thread executor is a timeout guard, not parallelism). Irrelevant for
  synthetic text corpora; the wall for real-PDF/scan corpora (686's territory).

## The open attribution question (why experiments come first)

The arithmetic doesn't close: embed p50 ~43 ms per batch-of-8 is ~5.4 ms of
ORT time per chunk, which for enron-scale chunk counts projects to tens of
seconds of GPU work — against a 346 s embedding-stage wall. If that holds, the
GPU is idle most of the wall and the dominant cost is orchestration *around*
inference (per-cycle pending-ID index queries, per-doc read-modify-write,
tokenization, commit/NRT policy) — a completely different lever than "bigger
batches". Also unconfirmed: whether the 640 baseline even ran embeddings on
GPU (`JUSTSEARCH_EMBED_GPU_ENABLED`), and which backfill path (combined tight
loop vs individual fallback) actually executed.

Tension recorded (not a fork): 647 §D-3 decided indexing throughput needs no
*metric-record* stage decomposition. This tempdoc does not reverse that — the
finer attribution here is working measurement for optimization, not new
promoted metrics. Promoting any per-stage indexing metric would be an explicit
follow-up decision against 647's record.

## Plan

**Phase A — attribution experiments (next; agent-run, dev-stack-exclusive).**
Reproduce the 640 ingest baseline and instrument the question above:
GPU-busy fraction during enrichment (nvidia-smi sampling + encoder-profile
batch-count × latency arithmetic vs stage wall), backfill path taken, chunk
counts, GPU-vs-CPU embed confirmation, per-stage walls on (a) enron-qa for
640-comparability and (b) a battlefield-style large-doc corpus for
624-representativeness. No code changes; artifacts + numbers only.

## Phase A results (2026-07-07)

Two instrumented `jseval run --pipeline` runs (GPU trace via `gpu-monitor.ps1`,
status snapshots, worker.log analysis). E1 = `mixed/enron-qa` (5,486 docs,
regenerated via `convert-enronqa-to-beir.py`; dataset was absent locally) —
**aborted at 538 s** per a 30-min projection cap (full completion projected
~2–3 h). E2 = `golden/battlefield-en-v1` (391 docs, ~4,290 chunks) —
**completed, 333 s**, enrichment throughput **1.18 docs/s**. Artifacts:
session scratchpad `691-experiments/` (timelines, GPU traces, status
snapshots, pipeline summaries).

**A-1. Orchestration hypothesis REFUTED.** The worker's own batch-timing
decomposition closes to the backfill wall within 1.2–1.4% in both runs;
content-fetch + Lucene write are 0.6–1.2%. Essentially all backfill wall-time
is inside the three encoders. Embed per-call p50 (42–46 ms) matches the 640
baseline exactly — embed is healthy.

**A-2. The dominant cost is NER: ~75% of backfill wall in both runs** (E1
335 s of 448 s recorded; E2 same share), a ~10–12× per-item blowup vs the
baseline's ~3 ms. **Root cause (verified live + in source):** NER's ORT GPU
arena defaults to **512 MB** (`justsearch.ner.gpu_mem_mb`,
`configuration/.../ResolvedConfigBuilder.java:1092`; env override
`JUSTSEARCH_NER_GPU_MEM_MB`, `EnvRegistry.java:523`) vs embed 3072 / SPLADE
4096 / reranker 2048. Batched NER at batch=16 × seq=512 needs ~200 MB for
attention intermediates alone (observed BFCArena failure: 156 MB requested,
105 MB available, at `/distilbert/transformer/layer.0/attention/Where`), so
batched inference OOMs continuously ("Batched NER inference failed, falling
back to per-doc": 141× in E1-partial, 488× in E2) and NER runs at batch=1.
The sizing comment at `BertNerInference.java:278` ("288 KB at batch=16 …
not VRAM-limited like SPLADE") counted only the output tensor and missed the
O(batch × heads × seq²) attention intermediates — the premise that made
512 MB look safe.

**A-3. Current main is regressed vs the 640 baseline** (same corpus family,
same box): primary indexing 63.2 docs/s vs 97.3 (-35%, and primary hadn't
fully drained at abort), NER per-item ~10×. The 640-era numbers do not
describe today's engine. Open question: what changed since 2026-06-24
(NER model swap? — untracked `models/onnx/ner/model.onnx` present; VRAM
residency mix; chunk/seq distribution) — the 640 run either didn't hit the
OOM or hit it less.

**A-4. Secondary findings.** (a) Combined tight-loop backfill path confirmed
(embed/splade/ner counters advance in lockstep; batch counts equal). (b) Embed
runs on CUDA with `model_fp16.onnx` — GPU-vs-CPU is not a lever. (c) GPU util
during enrichment is low (mean 36–42%, >50% only ~20–23% of the time) —
consistent with per-doc NER fallback serializing small kernels, additional
headroom even after NER is fixed. (d) Doc-level coverage percentages are a
misleading progress proxy on multi-chunk corpora: chunk-vector backfill (the
real `--pipeline` completion gate) lags doc-level embed% by design
(`BackfillScheduler.runIndividualBackfills` orders chunks after parents;
battlefield ≈11 chunks/doc showed 2.3% chunk vs 51% doc at t≈290s) — 640's
"embedding 100% at 516 s" likely understates true full-chunk completion.

**A-5. Harness friction recorded** (input to any recurring-benchmark tooling
decision): absent `mixed/enron-qa` dataset with no regeneration pointer in the
jseval skill; no supported abort-keep-partial path for `--pipeline` runs; no
ETA-safe progress signal (chunk_pct buried next to fast-moving doc-level
percentages); no status field for backfill mode (combined vs individual);
`batchTiming` "batches" vs `encoder_profiles` "calls" granularities
undocumented; GPU trace capture not integrated into `jseval run` (wish:
`--gpu-trace`); no top-level total-chunks field in summaries; native ORT
stderr NUL-interleaves worker.log, breaking grep on exactly the OOM lines
(logged to observations shard, along with an uncached per-request
`SsotAnalyzerRegistry` catalog reload seen during backfill).

## Phase B results (2026-07-07, B-series A/Bs — env-var only, no code changes)

**B-1. NER arena fix verified but insufficient.** `JUSTSEARCH_NER_GPU_MEM_MB=2048`
eliminates the OOM→per-doc-fallback entirely (488 → **0** events, override
live-confirmed in worker.log) but total battlefield wall improved only
333.4 s → 305.0 s (~9%), enrichment 1.18 → 1.33 docs/s. Reason (source-verified):
the combined backfill path never batches NER across documents by design —
`CombinedEnrichmentBackfillOps.java:335-353` calls
`nerService.extractEntitiesBatch(List.of(content))` one doc at a time, per an
explicit comment ("batching tested in item 22, regressed due to padding waste…
Per-doc at 2.0ms/call is near-optimal"). The arena OOM was real but occurred
within a single doc's internal chunk batch; fixing it doesn't touch the per-doc
dispatch. The dominant open anomaly is now **NER per-call cost: measured
ortP50 28.8–33.9 ms vs the 2.0 ms/call the comment assumes** (and vs 640's
cited ~3 ms) — a ~15× discrepancy that is either a real regression (model swap /
lost optimization) or a metrics-units artifact; NOT yet interrogated.
VRAM peak with the raised arena: 7.2 GB of 12 GB — headroom OK.

**B-2. 682 heap change RULED OUT for the primary regression.** Constant:
`KnowledgeServerConfig.DEFAULT_WORKER_HEAP` (`app-services/.../KnowledgeServerConfig.java:54`),
512m → 1g in `48eaf5a` (682 item 1), override `JUSTSEARCH_WORKER_HEAP`. Clean
enron-qa primary-phase A/B: 1g = 68.0 docs/s, 512m = 65.9 docs/s (~3%, noise).
Both far below the 640 baseline's 97.3 — the -35% primary regression has
another cause. Candidate post-06-24 commits (not investigated): `e96c94d`
"Agent-utility eval hardening" (IndexingDocumentOps, IngestionOutcomeJournal,
IngestionSuccessClassifier, PolicyDrivenTikaExtractor,
ExtractionOutcomeClassifier) and `9c29839` "CI wall-clock attribution
instrument" (WritePathOps, SyncDirectoryOps, PathNormalizer).

**B-3. NER model provenance suspicious, unresolved.** No post-06-24 NER
*code* changes (single squash commit only). But `models/onnx/ner/model.onnx`
(135 MB) is untracked in git and its mtime is **2026-07-01** — six days after
checkout and after the 640 baseline — with no committed version to diff.
`config.json` says `Davlan/distilbert-base-multilingual-cased-ner-hrl`
(matches what `BertNerInference` expects), so any swap was same-family.

**B-4. New friction:** the pipeline-run abort procedure (taskkill the head
PID) orphans the spawned worker java child — it kept the GPU + index locks and
contaminated the first B2b attempt (discarded, redone clean). Logged to the
observations shard; a benchmark abort helper must kill the worker child too.
Also: `encoder_profiles.calls` for NER doesn't reconcile with per-doc
invocation counts — the metric's unit ("call") needs a definition before it's
used for NER attribution again.

Artifacts: session scratchpad `691-experiments/b-series/`.

## Phase B investigations (2026-07-07, read-only)

**B-5. NER per-call cost: REAL regression, root cause = missing fp16 model
variant → INT8-on-CUDA silent degradation.** `models/onnx/ner/` contains only
`model.onnx` (135,115,720 B ≈ 1.00 byte/param for the 134.3M-param
DistilBERT-multilingual → INT8 dynamic quantization, the **CPU** variant per
`scripts/models/build-ner.py:74-75`) — no `model_fp16.onnx`, no `build.json`
(written only after both variants download; its absence + the 07-01 mtime fit
an interrupted build-ner.py run). With no InstallContract, dev-mode variant
selection (`DevModeVariantProbe.probe`, `ort-common/.../DevModeVariantProbe.java:48-85`)
falls to the CPU file and returns it as `optimal` on the CUDA EP with no
warning (the contract path, `VariantSelector.java:99-107`, would have logged
`degraded("FP16 variant not installed…")`). Dynamic-INT8 QOperator nodes lack
CUDA kernels → per-node CPU-fallback partitions/host round-trips → the
~10-15× per-call blowup. FLOPs sanity: batch=1 seq≈400 on a 42.5M-param body
is ~35 GFLOPs — low-single-digit ms on this GPU when healthy, matching 640's
3-4 ms. Stale-`.optimized`-cache hypothesis ruled out (cache mtimes match the
model's). Units check passed: `encoder_profiles` NER `ortP50` records the
same unit then and now (`EncoderProfileAccumulator.recordOrtCall` called only
from the batch=1 `infer()` path, `BertNerInference.java:249`) — apples-to-apples.
**Separate metric blind spot found:** the genuinely-batched `inferBatch()`
sub-batch loop (ORT run at `BertNerInference.java:391`) never records into
encoder_profiles at all — multi-chunk docs are invisible to `ortP50`.
Cross-doc NER batching (the item-22 decision) should NOT be re-litigated until
the fp16 fix lands and the batched path is instrumented.

**B-6. Primary-indexing -35%: both candidate commits CLEARED; no code suspect
in the surveyed path.** `e96c94d` adds only O(1) branches / commit-amortized
classifier work / OCR-branch-only code a text corpus never enters; `9c29839`
adds µs-scale string work outside the fresh-ingest path. The entire primary
loop (IndexingLoop/JobBatchExtractor/JobBatchWriter/IndexingCoordinator/
CommitOps/LoopPacingPolicy) is untouched since the initial-public-release
squash. Backfill-interleave contamination ruled out by queue-depth arithmetic.
Remaining suspects are non-code: the enron corpus was **regenerated** (not
byte-identical to the 640 run's), and the environment/checkout differs.
Decisive-if-needed experiment (deliberately deferred — see priority note):
run 640-era code (`29579e5`) in a worktree against the same regenerated corpus.
**Priority note:** primary phase is <15% of corpus-build total (85 s of ~750 s
enron; 10.7 s of 333 s battlefield) — the -35% costs ~30 s per enron build,
so this ranks well below the NER fix for the tempdoc's purpose.

## Phase C results (2026-07-07) — fp16 fix verified, 2.69× corpus build

C-series on battlefield-en-v1 (391 docs / 4,681 NER chunk-units, RTX 4070
12GB, git ab606e0):

| Run | Config | Total wall | Enrich docs/s | NER share | OOM | NER ortP50 | VRAM peak |
|---|---|---|---|---|---|---|---|
| E2 | int8-on-CUDA + 512MB | 333.4 s | 1.18 | ~74% | 488 | 28.8 ms | — |
| B1 | int8 + 2048MB | 305.0 s | 1.33 | ~69% | 0 | 33.9 ms | 7.2 GB |
| C1 | **fp16** + 512MB | 140.7 s | 2.78 | 31.0% | 449 | **3.16 ms** | 5.9 GB |
| C2 | **fp16 + 2048MB** | **124.0 s** | **3.15** | 20.5% | 0 | (n/m¹) | 7.7 GB |

¹ C2's ortP50 (3.45 ms) is from only 14 stray batch=1 calls — the B-5 blind
spot demonstrated live (5,851 recorded calls in C1's fallback-heavy run vs 14
in the batched-healthy run). Use batch-timing shares for NER attribution.

- **Verdicts:** fp16 restored ~3 ms per-call NER (~9×); the model variant was
  the dominant defect (fp16 alone: −192.7 s; arena-on-int8: −28.4 s; arena on
  top of fp16: −16.7 s more). fp16 still OOMs at 512 MB (activations larger
  than int8's: failed 34.6 MB alloc at `layer.0/ffn/lin1/MatMul`), so the
  arena raise is required alongside it. VRAM peak 7.7/12 GB — headroom OK.
- **New attribution:** embedding is now dominant — 62.0% of batch-timing
  (embed ORT 54.0 s, p50 45.4 ms/batch-of-8, 1,235 calls), NER 20.5%, SPLADE
  10.6%; GPU util avg only 51.7% → batching/pipelining headroom remains.
- **fp16 file provenance:** `models/onnx/ner/model_fp16.onnx`, 269,622,486 B,
  sha256 `8121A42877ED482925C73FB2002948F7BDE65FDCCA16989847255C71EB54DA49`,
  from HF `Xenova/distilbert-base-multilingual-cased-ner-hrl` @
  `c2a4dbf593c57f47004c5bc2d3770d311aee9c43` (`onnx/model_fp16.onnx`);
  `build.json` written in build-ner.py's schema (deviation annotated: fetched
  fp16-only via a scratchpad script because build-ner.py unconditionally
  re-downloads model.onnx; existing model.onnx sha256 verified unchanged,
  `92977e4b…3b06`). Note build.json `tool_versions` reflects the ambient env,
  not `scripts/models/requirements.txt`.
- **New friction:** jseval `--clean` wipes the previous run's worker.log
  (cross-run log evidence must be extracted between runs); `build-ner.py` has
  no fp16-only/incremental mode — its own interrupted-run failure mode (the
  cause of this whole regression) can't be repaired with the script itself.

## Phase D (2026-07-07): durable-fix implementation (items 1-3 — DONE)

Implemented on `worktree-691-corpus-throughput` (full unit suite green):

1. **Arena default 512 → 2048** — `ResolvedConfigBuilder.buildNer()` (with
   rationale comment); `EnvRegistry` + `environment-variables.md` default text
   updated; new default pinned in `ResolvedConfigBuilderTest`; consequential
   updates: `ModelSessionPolicyResolverTest` (both arena assertions; the
   all-distinct-defaults tripwire retired — NER == reranker == 2048 is now an
   intentional pairing) and the `policy-snapshot.json` golden fixture. The
   wrong sizing comment on `BertNerInference.MAX_NER_BATCH_SIZE`
   (output-tensor-only math) rewritten with the attention-intermediates
   reality.
2. **Silent degradation surfaced** — `DevModeVariantProbe` returns
   `VariantSelection.degraded(...)` for CPU-file-on-CUDA (mirrors
   `VariantSelector`'s contract branch; verified the flag has no behavioral
   consumers — reporting only); `InferenceCompositionRoot.resolveVariant`
   (the single resolution choke-point for contract + probe paths) WARN-logs
   every degraded selection with package id + reason. Probe tests extended
   (degraded + reason asserted; optimal pairings asserted non-degraded).
3. **encoder_profiles blind spot closed** — `BertNerInference.inferBatch`'s
   sub-batch ORT run now records into `EncoderProfileAccumulator` like the
   single-doc path (same measurement window: run-start → run-return).
4. **Register updated** — inference-runtime register: new F-013 (incident,
   fixes, metric caveat, F-008 frame correction) + F-010 VRAM-budget note
   (NER cap 0.5 → 2 GB; caps are per-session budgets, not allocations; LLM
   coexistence unaffected via GPU mutual exclusion). llms.txt + skills
   re-synced.

NOT in this change: committing `model_fp16.onnx`/`build.json` (item 4 below —
owner distribution-policy decision; files are on disk in the main checkout
with provenance recorded above); `build-ner.py` incremental mode (item 5);
the embed lever (item 6).

## Phase E (2026-07-07): embed/SPLADE batch-sweep micro-bench — batch size is a DEAD END; the live lever is batch FILL

`EncoderBatchSweepBench` (new, `modules/benchmarks`; run command in its
header; results `tmp/bench/encoder-batch-sweep/`) drove the real fp16
sessions directly at seq=512, sweeping batch × arena. No dev stack.

**E-1. Embed batch size: compute-saturated at 8.** chunks/s is flat from
batch 8 → 64 (148 → 158, +6.7% max; per-chunk 6.75 → 6.32 ms); batch 64
OOMs the 3072 arena but fits 4096. F-005's batch=8 conclusion HOLDS under
the new model/arena — but for a different reason (GEMM saturation, not VRAM
safety). Batch=16 fits the current arena on paper, but a historical comment
records 51 fragmentation OOMs at batch=16 over a real 5,184-doc run — a
13-call constant-shape bench cannot falsify that; do not raise without a
soak test. Not worth it for ≤7%.

**E-2. SPLADE batch: architectural wall.** batch=8 needs a 6144 MB arena for
+2.4% chunks/s; batch=16 needs ≈10.5 GB arena alone (measured request
6,938,886,144 B — byte-identical to the historical "6.94 GB" citation).
Keep 4/4096.

**E-3. The real finding — production embed runs HALF-EMPTY batches.** The
bench does 6.75 ms/chunk isolated; production C2 did 12.6 ms/chunk (54 s ORT
/ 4,290 chunks) at 1,235 recorded calls = **avg batch ≈3.5**, and per-call
cost is launch-bound at small batches (53 ms @ 8 vs ~45 ms @ 3.5). Filling
batches → ~536 calls ≈ 28 s: **~1.9× on the dominant stage** (~21% off the
enrichment wall) with no batch-size/arena change at all. Dispatch-site reads:
the combined loop DOES batch across docs (`CombinedEnrichmentBackfillOps:279`
`embedDocumentBatch(embedContents)`, cycles of ≤100 parents + ≤50 chunk
docs), so the ragged calls originate BELOW the loop — prime suspect: parent
documents being internally chunked-and-pooled per doc inside the embedding
provider (391 parents × ragged 8+3 ≈ the observed call count). Next step:
verify `embedDocumentBatch`'s chain, then flatten chunk inference across
documents into full sub-batches (pool per doc afterwards) inside the
provider/encoder layer — contained, no RMW-loop changes.

**E-4. Velocity framing (handoff note).** Post-NER-fix, a 1,000-doc build is
~6-7 min; E-3 would take it to ~5 min. The dominant stack-holder in an eval
cycle is now the agent-cell matrix (~3 h, tempdocs 624/675), not corpus
build — after E-3, further build optimization is third-order vs 675's
in-process executor.

## Phase E-5 (2026-07-07): E-3 CORRECTED — batches are nearly full; the 2× is DUPLICATE WORK

A verify-first implementation pass (required to reproduce E-3's arithmetic
before coding) refuted E-3: `OnnxEmbeddingEncoder.embedBatchWithChunking`
(385-448) already flattens ALL documents' chunks into one stream and
`embedPreTokenizedBatch` (269-280) sub-batches the whole stream at 8 —
cross-document batch fill exists exactly where E-3 proposed adding it.

The arithmetic that actually closes: parents' internal chunks (391 × ~11 ≈
4,300, embedded for pooling then **discarded** — `EmbeddingService.
embedDocumentBatch:340-395` keeps only the pooled `vector()`) + ~4,290
chunk-doc embeddings recomputed independently from `CHUNK_CONTENT`
(`CombinedEnrichmentBackfillOps.java:210-226`) ≈ 8,600 chunk inferences ÷
1,235 calls = **avg batch ~7 of 8**, and 54 s / 8,600 ≈ 6.3 ms/chunk ≈ the
bench's isolated 6.75 ms. Embed is dispatching efficiently — **long
documents are chunk-embedded twice** (with different window boundaries).

**The remaining embed lever is therefore deduplication** (~46% of embed
inferences ≈ ~20% of total build): either (a) pool the parent vector from
its chunk-doc vectors (skip the parent-pass internal chunking), or (b) align
chunk-doc boundaries with encoder chunking and reuse the parent-pass chunk
vectors. Both are **retrieval-semantics-affecting** (the parent doc vector
and/or chunk vectors change) — unlike Phases A-E this crosses into
/search-quality territory: requires the search-quality register, a relevance
ratchet check (nDCG on the pinned corpora), and a live A/B before shipping.
Not implemented; decision point recorded. (The duplicate-embedding
observation is also logged in the observations shard.)

## Remaining implementation items (Phase B/C follow-through)

1. **Durable arena default:** `justsearch.ner.gpu_mem_mb` 512 → 2048
   (`ResolvedConfigBuilder.java:1092`) — fp16 needs it; measured, zero OOM,
   VRAM headroom confirmed. Correct the wrong sizing comment at
   `BertNerInference.java:278` in the same change.
2. **Silent-degradation warning in dev-mode:** `DevModeVariantProbe.probe`
   returns `optimal` when serving the CPU variant on CUDA; production's
   `VariantSelector` logs `degraded(…)` for the same case. Mirror that (and
   the mislabeled FP32 precision for quantized files).
3. **encoder_profiles batched-path blind spot:** `BertNerInference.inferBatch`
   (ORT run ~line 391) records nothing into `EncoderProfileAccumulator` —
   healthy runs report meaningless NER ortP50 from stray batch=1 calls.
4. **Model-file distribution decision (owner):** commit
   `models/onnx/ner/model_fp16.onnx` (270 MB, LFS) + `build.json`? — several
   model files across `models/` are currently untracked; whether they belong
   in LFS or in a fetch-at-setup flow is a distribution-policy question
   (tempdoc 657 territory).
5. **build-ner.py incremental/fp16-only mode** (friction item; also the
   repair path for its interrupted-run failure mode).
6. **Next throughput lever: embedding** (62% share, GPU 52% util) — candidate
   space in the original Phase B list below (batch size 8→N, cross-doc chunk
   batching, pipelining); re-attribute after items 1-3 land.

## Plan (updated after Phase A)

**Phase B — lever selection (next).** Attribution now names the levers in
order: (1) fix NER batched inference — right-size the arena (config default
and/or `JUSTSEARCH_NER_GPU_MEM_MB`; a cheap A/B re-run of E2 with a raised
limit verifies the ~4× backfill win before any code change) and correct the
`BertNerInference` sizing math; (2) explain + fix the primary-indexing -35%
regression; (3) then re-attribute — embed (~17–19%) becomes the next
candidate, with low GPU utilization suggesting batching/pipelining headroom.
Original candidate space below retained for after re-attribution.

**Phase B (original candidate space, pre-attribution).** Rank levers by what attribution says
dominates. Candidate space (none chosen): backfill orchestration tightening;
ORT batch sizes / fp16 / SPLADE batch restoration with a real arena fix;
overlapping enrichment with primary indexing (CPU-extract ∥ GPU-embed);
a foreground "bulk build" mode that drops desktop-politeness pacing for
dedicated eval builds; config-exposing the pacing constants; parallel Tika
extraction (coordinate with 686). Load `/inference-runtime` +
`/search-quality` registers before implementation; 640's ratchet protects
any win.

**Phase C — re-measure, ratchet, close.** Before/after on the same corpora;
update the registers; record the new floor via the existing 640 perf-gate
path.

## Evidence index (for continuation without the originating session)

Raw experiment artifacts (jseval stdout JSON, GPU traces, /api/status
snapshots, worker.log extracts) lived in the originating agent session's
local temp scratchpad and are **not preserved** — every load-bearing number
is inline in the phase sections above. Durable evidence pointers:

- **Corpus-build A/B numbers (E2/B1/C1/C2 tables)** — reproduce with:
  `cd scripts/jseval && python -m jseval run --dataset golden/battlefield-en-v1
  --max-queries 0 --pipeline --start-backend --clean --json` (env overrides
  `JUSTSEARCH_NER_GPU_MEM_MB` / `JUSTSEARCH_WORKER_HEAP` per run). The
  pre-fix rows additionally require removing/renaming
  `models/onnx/ner/model_fp16.onnx` (that absence *is* the bug).
- **Variant-selection + arena evidence** — worker.log session-init lines
  quoted verbatim in §Phase A/B-5 (`ner: GPU session initialized —
  model=…, memLimit=…MB`); the OOM signature is the BFCArena "Available
  memory of X is smaller than requested bytes of Y" + "Batched NER inference
  failed, falling back to per-doc".
- **fp16 model provenance** — sha256 + HF source commit in §Phase C;
  `models/onnx/ner/build.json` (on the dev machine, untracked pending the
  distribution decision) records both variants.
- **Batch-sweep results (§Phase E)** — re-runnable:
  `./gradlew.bat :modules:benchmarks:encoderBatchSweepBench` with `-Pbench*`
  flags documented in `modules/benchmarks/src/main/java/io/justsearch/benchmarks/EncoderBatchSweepBench.java`
  (committed on this branch); it writes result.json/summary.md/cells.jsonl.
- **Phase D code verification** — unit claims map to:
  `ResolvedConfigBuilderTest` (NER default 2048 pinned),
  `DevModeVariantProbeTest` (degraded + reason asserted),
  `ModelSessionPolicyResolverTest` (arena caps; distinctness tripwire
  retirement), `policy-snapshot.json` golden. Full suite:
  `./gradlew.bat test -PskipWebBuild=true` → BUILD SUCCESSFUL (exit 0) on
  branch commit 1283508; `build -x test` green; spotless applied;
  `preview-squash-message --pr 90` → 0 warnings.

## Unverified assumptions & deferred checks (read before building on this)

1. **E-5's duplicate-embedding arithmetic is static-read only.** The ~8,600
   chunk-inference reconciliation closes numerically, but per-cycle
   `embedDocIds.size()` distribution was never read from a live run — the
   existing log line at `CombinedEnrichmentBackfillOps.java:421-444` answers
   it in one build run. Do that before implementing the dedup lever.
2. **The shipped defaults have not been live-verified as-committed.** All
   live A/Bs used env-var overrides on pre-PR code. One post-merge
   battlefield run (no env vars) should confirm: memLimit=2048 in the init
   line, zero OOM fallbacks, ~124 s total — and additionally that the new
   batched-path NER profiler records (expect call counts ≈ docs, p50 in the
   tens-of-ms at batch≈11, NOT the old 14-stray-calls artifact) and that no
   degraded-WARN fires for NER (fp16 present → optimal). The degraded WARN
   itself has unit-tier coverage only; it has never been observed live.
3. **1,000-doc projection (~5.3-6 min) is linear extrapolation** from the
   391-doc C2 run (justified by the closed encoder-dominated decomposition;
   expect ±15%; unmeasured at that scale).
4. **Embed batch=16 fragmentation risk is historical, not re-tested** — a
   code comment records 51 OOMs over a real 5,184-doc run at the current
   arena; the 13-call constant-shape bench cannot falsify it. Do not raise
   `MAX_ORT_BATCH_SIZE` without a soak test.
5. **NER-arena-2048 coexistence with the online LLM is argued by design**
   (GPU mutual exclusion + arena shrinkage; measured 7.7 GB peak was
   LLM-offline). Never measured with llama-server resident.
6. **Primary indexing remains ~35% below the 640 baseline, unexplained.**
   Both candidate commits cleared by code review (B-6); heap ruled out by A/B
   (B-2); remaining suspects are non-code (the enron corpus was regenerated —
   5,486 docs vs the baseline's 5,459 — and/or machine/disk state). Decisive
   experiment (deliberately deferred, low value: primary is <15% of build):
   run 640-era code (`29579e5`) in a worktree against the same regenerated
   corpus.
7. **The 07-01 interrupted-download provenance story for `model.onnx`** is
   inferred from mtimes + the missing `build.json`/fp16 sibling; the file was
   never committed, so no prior version exists to diff.
   **RESOLVED (2026-07-07, via 647's "Local-environment note"):** the file was
   downloaded 2026-07-01 from the model registry (`model-registry.v2.json` →
   `models-v1` pack, sha-verified) by the 647 session — not by an interrupted
   `build-ner.py` run. The registry pack supplied only the CPU/INT8 variant
   with no `model_fp16.onnx`, which is what created the silent INT8-on-CUDA
   state. Consequence for the model-artifact-integrity follow-up: this is an
   install-flow gap, not a one-off local accident — any fresh GPU environment
   provisioned from that pack would reproduce the degradation (dev-mode
   silently; contract-mode as a visible-but-easy-to-miss `degraded` status).
   Verify the pack contents before scoping the fix.
8. **Pre-fix NER `ortP50` values from batched-healthy runs are meaningless**
   (14 stray batch=1 calls in C2) — use batch-timing shares for any pre-691
   NER attribution.

## Follow-up ledger (beyond the Remaining-items list above)

- Post-merge: fold observation shards (`node scripts/agent-analytics/fold-observations.mjs --apply`)
  — this session's shard holds: worker.log NUL-corruption from ORT stderr;
  uncached per-request `SsotAnalyzerRegistry` reload during backfill;
  pipeline-abort orphans the worker child process; SPLADE gpu_mem_mb doc
  drift (env-vars doc says 2048, code default 4096); worktree model-path
  scaffold dirs are empty (binaries only in the main checkout); duplicate
  chunk embedding (E-5).
- Model distribution decision covers more than NER: `model.onnx`/`model_fp16.onnx`
  for gte-multilingual-base, reranker, and naver-splade-v3 are ALSO untracked
  on the dev machine despite the LFS policy — same decision, one policy.
- jseval tooling wishlist accumulated across §A-5/§B-4/§C/§E friction lists
  (abort-keep-partial, chunk-aware ETA, `--gpu-trace`, batches-vs-calls
  metric units, dataset regeneration pointers) — route to the jseval owner
  (tempdoc 645 lineage) as one batch.
- NER cross-doc batching (the "item 22" per-doc decision) should be
  re-examined ONLY after the now-shipped batched-path profiling produces real
  per-call data (B-5 caveat).
- 640's baseline tables (97.3/8.2 docs/s and per-stage seconds) describe a
  machine state that no longer exists (different NER model file state,
  regenerated corpus); treat 691's C2 as the current reference until the
  perf-gate recompose refreshes floors.
