---
title: "Corpus-build throughput: increase indexing/enrichment throughput and decrease total corpus-build time — the indexing-side sibling of 648 that its stub explicitly reserved ('a separate target… spin a sibling stub'). Purpose kept deliberately GENERAL for now: make building an enrichment-complete (dense-searchable) index over a multi-thousand-file corpus fast enough that repeated agentic-utility eval runs (624/673) stop monopolizing the shared dev stack for hours. Method inherited from 647: attribution before allocation before optimization — no lever is chosen until measurement says which cost dominates."
type: tempdocs
status: "open — Phases A-E complete (2026-07-07), durable fixes SHIPPED on PR #90 (branch worktree-691-corpus-throughput). Headline: NER's missing fp16 variant silently ran the INT8 CPU model on CUDA (~10× per-call); with the model restored + arena 512→2048, battlefield corpus build 333.4s → 124.0s (2.69×). Batch-size tuning measured as a dead end (Phase E); the remaining embed lever is DUPLICATE chunk embedding (E-5, ~20% of build) — retrieval-semantics-affecting, decision point recorded, NOT implemented. See §Evidence index and §Unverified assumptions before continuing. TAKEOVER 2026-07-10 (§Phase F/G): chunk-pacing cap measured a compute-bound red herring; E-5 dedup is the density-scaling lever; §Phase G settles the DESIGN (whole-doc VECTOR = projection/mean-pool of CHUNK_VECTORs; search-quality register Q-016; principle: whole-object-as-projection-of-parts). IMPLEMENTATION UNDERWAY 2026-07-10 (§Phase H/I/J/K — read §Phase K FIRST, it is the self-contained continuation state): /research (§H) found LATE CHUNKING (Jina arXiv:2409.04701) DOMINATES the mean-pool design and is the vehicle; /plan approved 'full late chunking' default-off→measure→default-on. PHASE 1 SHIPPED default-off on this branch (commits db5a83a + 42d8862): `OnnxEmbeddingEncoder.embedWithSpans` primitive + `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED` flag + additive `LateChunkingEmbedBackfillOps` pass; build + module tests green; flag-off strict no-op. TWO FINDINGS RESHAPED IT: (a) gte-multilingual-base is CLS-pooled → per-span-mean chunk vectors are OFF-distribution (the chunk half is the risky/untested part, I-2); (b) the DOMINANT win is the whole-doc VECTOR from a SINGLE long-context pass (F-030 fix, 708-measured), now REPRODUCED LIVE (§Phase J: legal-clerc vector nDCG@10 0.0597→0.3403, 5.7×) — and a naive base-context bump OOMs the batch path, so late chunking's batch-1 path is the OOM-SAFE vehicle. NEXT = Phase 2 (long-doc single-pass via late chunking at raised eligibility) then the off-vs-on A/B on legal-clerc. Still default-off; nothing merged. Older §Evidence index / §Unverified assumptions predate this and describe the PRE-late-chunking state. SECOND TAKEOVER 2026-07-10 (§Phase L — read after §K): §K verified at file:line, zero main drift, VERDICT = CONTINUE NOW but Phase 2 RE-SHAPED — the per-span chunk half is researched-AGAINST (Jina: CLS models 'aren't compatible' with late chunking; proxy evidence regresses) → get cheap OFFLINE evidence (708-harness experiment) before building it; ship path = VECTOR-only single-pass ≤8192 (needs a NEW limit config — none exists, L-1); §G projection revived as the >8192 long-doc dedup complement (L-7: at 8192 late chunking is a throughput WASH, quality lever only); prefix risk K-4#2 RETIRED (runtime prefix empty); 691's Q-016/env-var doc edits are stranded UNCOMMITTED in the main checkout (L-3)."
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
4. **Model-artifact integrity + distribution → route to tempdoc 657, not a
   new tempdoc.** Two coupled questions: (a) the distribution-policy decision
   (owner) — commit `models/onnx/ner/model_fp16.onnx` (270 MB, LFS) +
   `build.json`, or fetch-at-setup? several `models/` files are untracked
   today; (b) the class-fix this incident exposed — the `models-v1` registry
   pack shipped the CPU/INT8 variant with **no fp16 sibling** (§Addendum in
   647; §Unverified-assumptions #7), so any fresh GPU environment reproduces
   the silent INT8-on-CUDA degradation. Candidate work: pack ships both
   variants; wire the existing `scripts/models/check-integrity.py` into CI or
   startup; `build.json`-presence assertion. All of this is model-pack
   composition + install-flow, which **tempdoc 657
   (install-modes-and-model-pack-decomposition) already owns** — add it there
   as an item with F-013 + this tempdoc as the evidence trail. No new tempdoc:
   the runtime finding lives in the inference-runtime register (F-013), the
   pack/install work is 657's subject.
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
  on the dev machine despite the LFS policy — same decision, one policy. Owned
  by tempdoc 657 (see Remaining-item #4) — no separate tempdoc.
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

## Chunk-pacing investigation seed (2026-07-10, from the 686/706 session — pickup material)

A real-corpus ingest (mixed/realdocs-v1: 620 docs → 85,641 chunks, ~138 chunks/doc — 12× denser
than battlefield) exposed the chunk-embedding backlog crawling at ~50 chunks per ~3.5-min combined
cycle. Read-only investigation findings (file:line evidence verified 2026-07-10):

1. **The cap is a bare literal**: `chunkSlotsPerBatch = 50` at
   `CombinedEnrichmentBackfillOps.java:138` — no constant, no config, no derivation; parent docs
   get `EMBEDDING_BACKFILL_BATCH_SIZE = 100` (`LoopPacingPolicy.java:12`). Set once at the
   public-release squash, never revisited. Reproduces the observed cycle shape exactly
   (docs=150 = 100 parent + 50 chunk).
2. **Cadence is compute-bound, not sleep-bound**: `BackfillScheduler.runIdleCycle` runs a tight
   no-sleep loop (`BackfillScheduler.java:135-149`); ~3.5 min is one `processCombinedBackfill`
   call's own wall time, dominated by parent-doc embedding (137s observed).
3. **Drain model**: naive extrapolation ≈ 100h; but chunk-only tail cycles (after parents finish;
   chunks skip SPLADE/NER, `CombinedEnrichmentBackfillOps.java:223-238`) should be seconds each
   → tail ≈ 2-5h. UNMEASURED — measure a chunk-only cycle before concluding code must change.
4. **Risk coupling**: `OnnxEmbeddingEncoder.embedBatchWithChunking` (`:385-448`) tokenizes the
   whole caller list upfront — the SAME pattern whose SPLADE sibling caused the 686 native heap
   crash (fixed via `SpladeEncoder.TOKENIZE_GROUP_CHAR_BUDGET`). Raising the chunk cap without
   bounding this path first risks reproducing that crash class in the embed path (686
   §Unverified assumptions #2 flags the audit as open).
5. **E-5 interaction is LARGER on dense corpora**: parent-internal pooling re-embeds content that
   chunk docs re-embed again independently; at ~138 chunks/doc most of a cycle's embed time is
   plausibly discarded pooling work while net chunk progress stays capped at 50. E-5 fix (b)
   (align boundaries, reuse parent-pass chunk vectors) could complete ~138 chunks per parent as a
   side effect — a much bigger lever than raising the cap. Both E-5 options remain
   retrieval-semantics-affecting (search-quality register + nDCG ratchet + live A/B required).

**Candidate fix shapes, risk-ordered**: (1) measure the chunk-only tail first — possibly no code
change needed for the tail; (2) E-5 pool-from-chunks (removes the waste, no new heap surface,
needs the quality gate); (3) raise/decouple `chunkSlotsPerBatch` ONLY after bounding
`embedBatchWithChunking`'s upfront tokenization the way SPLADE's was bounded.

## Takeover investigation (2026-07-10) — VERDICT: CONTINUE, but measure the chunk-only tail before any code

A fresh-session takeover re-read the whole tempdoc and verified its shipped state against `main`.
**Correction to an earlier draft of this verdict:** the first pass concluded "close / dormant owner,"
formed on a worktree branched *before* PR #129 (`1a4729e`) landed — so it missed the §Chunk-pacing
investigation seed directly above. That seed reverses the conclusion. Recording the miss honestly
(base-ref staleness, `verify-worktree-base`): the corpus 691 optimized against (battlefield, ~11
chunks/doc) under-represents real chunk load by ~12×.

**Shipped state confirmed in `main` (unchanged, still solid):** PR #90 merged (`3bd9078`) + docs
PRs #92/#94/#104/#129. NER arena `2048` (`ResolvedConfigBuilder.java:1098`), degraded-variant
surfacing (`DevModeVariantProbe.java:86`), batched-path profiler (`BertNerInference.java`). The
2.69× NER headline win is real and live. Item 4 (model distribution) is owned by **657** (bundle
ships NER INT8+FP16). None of that is in question.

**What changed the verdict — the bottleneck is corpus-class-dependent, and 691 only measured the
easy class.** Two facts must be held together, not one against the other:
- For the **agentic-eval** consumer (624/673), corpus build is NOT the wall: **699** measured a
  single cell at ~90% Anthropic API time, backend 2.7–8%. On synthetic/sparse corpora the shipped
  fixes already made build ~6–7 min/1,000 docs. That half of the original charge is *satisfied*.
- For the **real-document** consumer (686/705/706), the §Chunk-pacing seed shows a *different*
  regime: `realdocs-v1` at ~138 chunks/doc drives chunk backfill to a **2–5 h (unmeasured), naive
  ~100 h** tail, throttled by a bare `chunkSlotsPerBatch = 50` literal. This is the first evidence
  on a realistic-density corpus, and it post-dates every A–E measurement. 691's "build is fast now,
  third-order" claim does **not** generalize to it (704's own meta-principle: *a claim without its
  corpus-class scope is a lie by generalization*).

**Cheapest evidence that decides whether code must change — and it does NOT yet exist.** Measure one
**chunk-only tail cycle** on a dense corpus (seed item 3, explicitly UNMEASURED). If chunk-only
cycles drain in seconds (chunks skip SPLADE/NER), the tail may be tolerable with *no* code change and
the cap stays. If they don't, the risk-ordered fix ladder in the seed applies. This is a read-only
`jseval --pipeline` measurement on a dense corpus — bounded, no code, dev-stack-exclusive. It is the
correct first pickup and it gates everything else here.

**Disposition of each item under the corrected frame:**
- **Chunk-pacing tail (seed) — the live lever; START with the measurement above.** Do not raise
  `chunkSlotsPerBatch` first: seed item 4 shows raising it without bounding
  `embedBatchWithChunking`'s upfront tokenization risks the 686 native-heap crash class.
- **Item 6 / E-5 (embed duplicate-chunk dedup) — RE-ELEVATED by the seed, not declined.** The
  earlier draft dismissed it as ~1.3 min on a sparse corpus; the seed shows that on dense corpora
  the discarded parent-pooling work dominates a cycle while net chunk progress stays capped at 50,
  so E-5 fix (b) could be *the* dense-corpus lever. Still retrieval-semantics-affecting
  (search-quality register + nDCG ratchet + live A/B) — but its expected value is now large, not
  marginal. Re-measure after the chunk-only-tail number lands.
- **Assumption #6 (primary indexing −35%) — still DECLINE.** Primary is <15% of build; suspects
  non-code; low value regardless of corpus class.
- **Item 5 (build-ner.py incremental mode) — still low priority;** repair-path subsumed by 657.
- **Assumption #2 (post-merge live-verify of shipped defaults) — fold into the dense-corpus run
  above** (it needs a live build anyway): confirm memLimit=2048, zero OOM, no NER degraded-WARN,
  batched-path profiler records ≈docs calls.
- **Item 4 (model distribution) — 657 owns it.** No 691 action.

**Relationship to newer docs:** 704 §Pillar 4 and 705 name 691 as owner of "enrichment throughput /
the cost tax" (routed, not absorbed). 706 shipped the *extraction/parse* leg (6.9× scanned PDF); the
chunk-pacing tail is the *enrichment* leg, squarely 691's. So the seed is not a duplicate of 706 —
it is the piece 691 still owns.

**Recommendation to founder — 691 STAYS OPEN with one concrete, cheap next step:** an agent-run,
read-only chunk-only-tail measurement on a dense corpus (`realdocs-v1` or equivalent), which decides
whether the tail needs any code change and re-attributes E-5 vs the cap. Everything past that
measurement (cap change, E-5 dedup) is authorization-gated and, for E-5, search-quality-gated. Do
NOT close 691. (This is a measurement recommendation, not implementation — no code or design written
this session, per the takeover contract.)

## Phase F — chunk-pacing measurement (2026-07-10, takeover, user-authorized "proceed with the cheap step")

Ran the cheap read-only measurement recommended above. `realdocs-v1` is a raw-binary 686 corpus and
is NOT on this machine, so measured on **`golden/battlefield-en-v1`** (390 docs, ~4,380 chunks,
RTX 4070) — the per-chunk embed cost is corpus-density-independent, so the chunk-tail *mechanism*
generalizes; the realdocs *total-time* figure below is arithmetic, not measured. Instrument: the
existing per-cycle INFO line `CombinedEnrichmentBackfillOps.java:479` (`docs=… chunks=… embed=…ms
… total=…ms`), no code change. Clean env-var-free run (shipped defaults). 94 cycles parsed.

**F-1. The chunk-only tail is GPU-COMPUTE-BOUND — the `chunkSlotsPerBatch=50` cap is NOT the
throughput lever.** Backfill wall 115.0 s split cleanly:
| Regime | cycles | wall | embed(GPU) | fetch+write+other | chunks drained | ms/embed-doc |
|---|---|---|---|---|---|---|
| Parent-enrichment | 6 | 78.0 s (68%) | 41.8 s (54%) | 36.9 s (SPLADE+NER+ov) | 300 (50/cyc) | **52.8** |
| **Chunk-only tail** | 82 | 36.8 s (32%) | **30.2 s (82%)** | 6.6 s (18%) | 4,080 | **7.23** |
| No-op drain | 6 | 0.2 s | — | — | 0 | — |

A chunk-only cycle is ~50 chunks in ~0.40 s, of which **82% is ORT embedding at 7.23 ms/chunk** —
i.e. the compute floor (Phase E bench: 6.75 ms/chunk isolated). Overhead is only ~18%, so raising
the cap recovers at most ~6.6 s here and *nothing* on the GPU-bound 30.2 s. **The seed's worry that
the tail is overhead/cap-throttled is REFUTED — the tail is the GPU embedding 4,080 chunks, and the
cap can't make the GPU faster.**

**F-2. The measured improvable lever is E-5 duplicate embedding, and it SCALES with chunks/doc.**
Parent cycles cost **52.8 ms per "embed-doc" vs 7.23 for chunks** because each parent re-embeds its
internal chunks (pooled → discarded), which are then re-embedded *again* as chunk docs (E-5).
Battlefield parent cycles embedded 791 doc-units for ~390 parents. On battlefield (~11 chunks/doc)
this duplication is a minority of the 115 s. On `realdocs-v1` (~138 chunks/doc) the parent-internal
embedding ≈ the chunk-doc embedding ≈ **~half of all embedding work** → the dominant eliminable
cost. So: **improvable = YES, via E-5 dedup (retrieval-semantics-affecting → search-quality register
+ nDCG ratchet + live A/B), NOT via the cap.**

**F-3. The seed's ~2-5 h / naive ~100 h realdocs drain is very likely overstated.** Chunk-only
cycles are compute-bound at ~0.40 s/50, so a realdocs chunk tail ≈ 85,641 × 7.2 ms ≈ **~10 min**,
not hours. The ~100 h figure assumed parent-heavy 3.5-min cycles persist for all ~1,700 cycles;
in fact parents drain in ~7 cycles, then the tail is fast. The real realdocs enrichment cost is
~10 min irreducible chunk-embed compute **plus a ~equal ~10 min of E-5 duplicate parent-internal
embed that dedup would remove** — the lever, again, is dedup, not the cap. (Caveat: realdocs total
time is extrapolated; a realdocs-v1 build would confirm. Also note GPU mutual-exclusion means the
cap only re-orders GPU work, it doesn't add throughput — overlapping stages is a separate, deeper
lever, not in scope here.)

**F-4. Assumption #2 (shipped defaults, live) — CLOSED.** Same clean run, worker.log session-init:
`ner: GPU session initialized — model=model_fp16.onnx, device=0, memLimit=2048MB`; **0** "falling
back to per-doc" OOM events; **0** degraded-variant WARNs (fp16 present → optimal). The PR #90
defaults work as committed on a fresh env-var-free run.

**F-5b. Complexity note for the E-5 implementer (2026-07-10) — the two chunkers are architecturally
DIFFERENT by design; "reuse one for the other" is a retrieval-semantics decision, not a mechanical
dedup.** Verified in source:
- *Encoder internal chunking* (`OnnxEmbeddingEncoder.createChunks:525`): sliding window on RAW TOKEN
  IDS, `chunkSize=min(512,maxSeqLen)`, boundary-AGNOSTIC — its only job is to pool a long doc into
  ONE parent vector.
- *Pipeline RAG chunker* (`ChunkSplitter`): `DEFAULT_CHUNK_TOKENS=500`, `OVERLAP=50`, sentence/
  paragraph boundary-AWARE on text, with 5 content-aware modes (MARKDOWN/CODE/CSV/JSON/STRUCTURED)
  + CJK handling — its job is coherent retrievable chunks.
They differ on window size (512 vs 500), overlap, boundary rule, and mode-specificity, so they emit
DIFFERENT chunk sets. Therefore E-5 (a) pool-parent-from-chunk-vectors CHANGES the parent doc vector
(pooled from boundary-aware chunks, not raw 512-windows) AND inverts backfill ordering (chunks must
embed before the parent pools); E-5 (b) align-and-reuse forces one chunker's boundaries onto the
other, changing either parent-retrieval or chunk-retrieval semantics. Both are eval-gated retrieval
changes with a silent-green failure mode (compiles + unit-green while search quietly regresses), not
bounded mechanical edits. Design-first, /search-quality-register + nDCG-ratchet + live-A/B gated.

**F-5. Revised recommendation.** 691 stays OPEN, but the next lever is now *named by measurement*:
the cap is a red herring for throughput; **E-5 duplicate-embedding elimination is the one lever that
reduces actual GPU work, and its payoff scales with corpus chunk-density.** That work is
search-quality-gated (register + nDCG ratchet + live A/B) and authorization-gated — not started this
session. Remaining genuine open measurement: a `realdocs-v1` build to confirm the F-3 extrapolation
at true density. Artifacts: session scratchpad `691-chunk-tail/` (run.ndjson, timeline.tsv,
worker.log-derived `bf.txt` per-cycle table).

## Phase G — E-5 dedup DESIGN (2026-07-10, /design; /search-quality register loaded)

Design only; no code. The measurement (F) named the lever; this settles the correct shape. Grounded
in the search-quality register (23-search-pipeline-overview, F-023/F-029/F-030, D-005, stage 13c).

**The duplication, source-exact.** The combined backfill embeds via
`EmbeddingService.embedDocumentBatch` (`:340`), which keeps only the pooled `.vector()` (`:379`) and
**discards** the per-window vectors the encoder computed internally. So a chunked parent
(content > `ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS = 2000`) is embedded **twice** over ~the same
text: (1) parent content → encoder 512-token windows → mean-pool → `VECTOR` (windows discarded);
(2) each `CHUNK_CONTENT` doc → its own embed → `CHUNK_VECTOR` (`CombinedEnrichmentBackfillOps:305`).
F-2 measured the cost: parent-cycle embed is 52.8 ms/doc-unit vs 7.23 for chunks; on ~138-chunk/doc
corpora the discarded parent-internal pass ≈ the chunk pass ≈ **~½ of all embed work**.

**Design: the whole-doc vector is a PROJECTION (mean-pool) of the document's chunk vectors, not an
independent re-derivation.** For a document that has chunk docs, compute each chunk once (its
`CHUNK_VECTOR`, via the canonical RAG chunker `ChunkSplitter` — UNCHANGED) and derive `VECTOR` as the
mean-pool of that document's `CHUNK_VECTOR`s. Drop the separate encoder-internal chunk-and-pool pass
over the parent content. Documents with no chunk docs (≤2000 chars, plus the CJK/dense-token band
that is long-in-tokens yet short-in-chars) keep today's direct/whole-doc path unchanged.

**Direction is forced (chunk→parent, never parent→chunk).** Option (b) — reuse the encoder's 512-token
windows as the chunk vectors — is rejected: it desynchronizes `CHUNK_VECTOR` from `CHUNK_CONTENT`
(different text spans) and degrades the chunk-branch dense leg, which the register shows is the
*load-bearing* dense leg on exactly the long docs this touches (stage 13c "long docs trust chunk
branch"; F-029/F-030). The RAG chunks are what chunk-dense retrieval and RAG actually serve; they stay
canonical. Only the whole-doc `VECTOR` — a derived view — changes.

**Why the retrieval risk is structurally bounded (register-grounded, not asserted).** The change
touches only `VECTOR`, only for chunked (long) docs, and three register facts align risk *against*
harm: (a) whole-doc mean-pool dilution is a KNOWN liability that grows with length (F-023; F-029/F-030
"maximal whole-doc mean-pool dilution") — the whole-doc vector is the WEAKER signal on long docs;
(b) branch fusion already de-weights the whole-doc branch on long docs (13c parent-length modulation,
`chunkMinMultiplier` 0.25); (c) the change is largest where the whole-doc dense leg matters least
(long/dense docs) and smallest where it matters most (short docs). Benefit and risk are inversely
aligned. Net *expectation*: retrieval-neutral with ~2× less embed on dense corpora — but per D-005
this is a hypothesis to MEASURE, not a claim to ship on.

**Validation (instruments already shipped; no new measurement tech).** Ship default-off → measure →
default-on (D-004 template). Gate on the recall-survival triad + relevance on the pinned corpora,
weighting long-doc corpora (legal-clerc-200, enron-qa): `jseval relevance-gate` (nDCG floor),
`jseval union-recall-gate` (leg_union_recall floor — the direct test of whether the whole-doc dense
leg still surfaces the gold; F-028, pinned legal-clerc-200 0.87 / scifact 0.96 / needle-burial 1.0),
`jseval leak-gate` (cascade-leak ceiling), plus a shared-index OFF-vs-ON A/B (build once — the method
D-004 used to avoid embedding-rebuild noise).

**What this orphans (teardown belongs to THIS tempdoc).** Nothing wholesale — a NARROWING. The
parent's use of the encoder-internal chunk-and-pool path (`OnnxEmbeddingEncoder.createChunks` +
`meanPoolChunks` reached via `embedDocumentBatch`) shrinks to the no-chunk-docs fallback.
`embedBatchWithChunking` and the `ChunkedEmbedding.chunkVectors` return stay live (consumers: the
single-doc `embed` path, `OnnxEmbeddingBackend`, `LocalIntentTranslatorV2`). At implementation time,
run a consumer scan and tombstone only what it proves unreachable — do not pre-emptively delete.

**Scheduling consequence (stated, not designed to implementation level).** `VECTOR` now depends on the
document's `CHUNK_VECTOR`s, inverting today's parent-first enrichment order for the vector field: a
chunked parent's `VECTOR` can only be pooled once its chunks are embedded (e.g. pool the parent when
its last chunk completes). The exact mechanism is an implementation choice; the invariant is the
dependency edge.

**Deliberately out of scope (named, deferred).** Whether the whole-doc `VECTOR` is worth keeping AT
ALL for chunked docs (given F-023 dilution + 13c de-weighting) is a larger retrieval-representation
question — removing the whole-doc dense branch for long docs is a /search-quality investigation, not a
throughput dedup. Matching scope to 691's actual problem: keep the branch, remove the duplicate
compute. Recorded for the register (Q-016).

### Reach & principle

**Principle (recognized, not built general): whole-object-as-projection-of-parts.** A composite
representation of a whole object should be a projection (here mean-pool) of its canonical part
representations, not a second independent derivation over the whole. This is the projection-not-fork
discipline (CLAUDE.md `explore-before-implementing`; 553 one-canonical-record; D-005
one-canonical-authority) applied to the dense-vector layer: chunk vectors are the canonical
per-passage dense representation; the whole-doc vector is a derived view. Today it is a *fork*
(independent encoder-window chunk-and-pool), which is why it both duplicates compute AND can silently
drift from the chunk representation.

**Candidate scope beyond embed (named, NOT built).** The SPLADE leg has the analogous shape: the
whole-doc SPLADE representation is a 512-token *truncation* of the doc (register: SPLADE truncates at
512 tokens, weight modulated by `parent_token_count` to compensate), while `chunk_splade` is
per-chunk. A pooled/union projection of chunk SPLADEs would conform to this principle AND fix the
whole-doc SPLADE truncation loss — but that is its own retrieval-quality change with its own A/B;
`structural-defects-no-repeat` says build the next instance only when its own evidence demands it. NER
does not apply (chunks skip NER — no duplication).

**Evidence the principle earns its keep:** the embed dedup cuts enrichment embedding compute ~½ on
dense corpora (F-2 measured the duplicate) with neutral relevance/union-recall/leak gates.
**Retirement condition:** if a measured case shows the whole-doc vector needs a global signal that no
pool of its chunks can carry (pool-of-parts ≠ whole is retrieval-meaningful), the projection identity
is false for that leg — retire the principle there and justify an independent whole-object derivation,
rather than force-fit it.

## Phase H — internet research pass (2026-07-10, /research): LATE CHUNKING dominates the §G dedup

A focused pass (the aggregation of chunk embeddings into a document representation, and efficient
chunk+doc embedding of long docs, is very actively researched 2024-2026). Verdict: **warranted, and it
found a strictly-better successor** to §G's plain dedup.

**Finding: "Late chunking" (Jina AI — Günther et al., [arXiv:2409.04701](https://arxiv.org/abs/2409.04701),
Sep 2024 / rev Jul 2025; [Jina writeup](https://jina.ai/news/late-chunking-in-long-context-embedding-models/))
is the directly-relevant SOTA and *dominates* the §G design.** It embeds the WHOLE document once into
**token-level** embeddings with a long-context model, THEN partitions the token embeddings into
chunk-spans and mean-pools each span — so every chunk vector carries full-document context (measurably
better retrieval across boundary strategies, per the paper), and **all chunk vectors + the whole-doc
vector come from ONE forward pass**. Same mean-pooling, no retraining.

**Why it dominates §G.** §G removes the duplicate but is quality-neutral-at-best (it derives the
weaker whole-doc vector from independently-embedded, context-*blind* chunks). Late chunking removes
the *same* duplication (one document pass, not a parent pass + N chunk passes) **and** upgrades the
chunk vectors from context-blind to context-aware — a throughput win *and* a quality win, on the same
axis the register already flags (F-023 whole-doc dilution; F-029/F-030 dense-death — context-aware
chunks are the field's answer to exactly that dilution). So §G is the *floor*; late chunking is the
*target*.

**Feasibility with OUR stack — verified in source, and it is favorable.** Late chunking's one hard
precondition is a model that emits token-level embeddings pooled outside the model. **We already do
this**: `OnnxEmbeddingEncoder` reads `last_hidden_state` `[batch, seqLen, dim]` (`:341`) and mean-pools
in Java (`pool()`, `:350`) — we currently pool per 512-token window; late chunking pools per
RAG-chunk-span instead. The model (`gte-multilingual-base`) supports 32k context (tokenizer
`model_max_length`). So it is feasible **without re-exporting the model**. The gates are:
1. **Operative embed `maxSeqLen`** (`EmbeddingShape.maxSequenceLength`, doc says "typically 512–2048";
   `chunkSize = min(512, maxSeqLen)`). If already high, most docs embed whole in one pass and late
   chunking is nearly free; if 512, full-document context needs raising the embedding context window
   (VRAM/latency cost) or "long late chunking" (overlapping macro-windows). **Implementer must pin
   this value first** — it decides whether late chunking is a small change or needs a context raise.
2. **Token-span pooling** needs the char→token offset map to align RAG-chunk boundaries to token
   positions (the tokenizer already produces offsets); plus macro-windowing for docs beyond the
   context window.

**Recommendation (updates §G).** Before implementing the plain §G dedup, run a **bounded feasibility
spike on late chunking**: pin the operative embed `maxSeqLen`; confirm token-span pooling via the
tokenizer offset map; estimate the VRAM/latency of the whole-doc pass at realistic doc lengths. If
feasible at acceptable cost, **late chunking supersedes §G as the E-5 target** (same dedup + a measured
retrieval gain, and it directly attacks the register's whole-doc-dilution finding). If the context
raise is too costly, §G's mean-pool-projection is the quality-neutral throughput fallback. Both are
validated by the same instruments (relevance/union-recall/leak gates + shared-index A/B) and both stay
default-off → measure → default-on.

**License/attribution.** Late chunking is a *published technique* (an inference-time algorithm), not
code to copy — an implementation would be our own, citing the paper in a code comment + here. No
Jina source is copied, so no license/notices-CI concern from adopting the method. (Adjacent-but-not-
decision-changing, noted not pursued: "Beyond Chunk-Then-Embed" chunking taxonomy
[arXiv:2602.16974](https://arxiv.org/abs/2602.16974); Landmark Pooling [arXiv:2601.21525]; Multi-Prefix
long-context embedding [arXiv:2606.23642]. Mean-pool-of-chunks as a doc representation is confirmed a
standard, sound aggregation — no red flag for the §G fallback.)

## Phase I — implementation log (2026-07-10, /plan approved: full late chunking)

Plan approved (worktree `691-takeover`): full late chunking, default-off → measure → default-on.
Plan file: user scratchpad `lovely-conjuring-dove.md`.

**I-1. Phase-1a primitive LANDED (encoder, docs ≤2048 tokens).** `OnnxEmbeddingEncoder`:
extracted `runHidden()` (the ORT forward pass, returns unpooled `last_hidden_state[0]`) so
`embedSingle` and the new `embedWithSpans(content, charSpans[])` share the identical run+pool path
→ the whole-doc vector is **bit-identical to `embed()` by construction** for ≤2048-token docs.
`embedWithSpans` returns `null` for >2048-token docs (Phase 2), checked BEFORE any char-span
materialization (686 memory-safety). `poolSpan()` = masked-mean over tokens whose `getCharTokenSpans()`
offset intersects `[startChar,endChar)`, excluding masked/null/zero-width special tokens; zero-token
fallback = isolated embed of the substring. DJL `Encoding.getCharTokenSpans()` →
`ai.djl.huggingface.tokenizers.jni.CharSpan[]` (getStart/getEnd), confirmed via `javap` on
tokenizers-0.36.0. `build -x test` green; spotless clean. Unit test
`OnnxEmbeddingEncoderLateChunkingTest` written (bit-identity, single-span==isolated, multi-span unit,
long-doc-null) but **SKIPPED in the worktree** (no `.onnx` on the worktree model path; the model
resolves from the MAIN checkout at dev-stack runtime). Live bit-identity verification deferred to the
Phase-4 build (real encoder). Diff: `OnnxEmbeddingEncoder.java` +118/-6, one new test file.

**I-2. DESIGN RISK found — gte-multilingual-base is CLS-pooled** (`models/onnx/gte-multilingual-base/
pooling_config.json` → `{"pooling_mode":"cls"}`). Consequence: `pool()` returns the CLS token for the
whole-doc `VECTOR` (so `embedWithSpans`'s doc vector stays bit-identical — CLS of the same pass), but
`poolSpan` does per-span **mean**, which is OFF the model's CLS training distribution. Late chunking is
canonically defined for mean-pool models; for a CLS model the per-span-mean **chunk** vectors are a
genuine quality unknown — today's chunk vectors are on-distribution per-chunk CLS embeds; late
chunking's are context-aware but off-distribution mean pools. This does NOT block (default-off; the
A/B decides) but it is the crux the Phase-4 A/B must settle, weighting legal-clerc-200. If per-span
chunks regress, the fallback is VECTOR-only single-pass (see I-3), which is on-distribution CLS.

**I-3. 708 coordination — late chunking is now ALSO the presumptive F-030 fix (quality-motivated,
not just throughput).** The 708 lane (`docs/tempdocs/708-encoder-domain-fit-legal-professional-text.md`,
worktree `708-encoder-investigation`; harness `scripts/jseval/experiments/encoder_bakeoff_708.py`)
measured, offline + Gate-0-validated (reproduces the production F-030 numbers within 0.005 first):
gte-multilingual-base given legal-clerc-200 verbose queries in a **single native 8192-token pass**
(instead of the production 512-token-window CLS-then-mean) goes **R@10 0.100 → 0.745** (nDCG@10 0.526,
R@100 0.955) — a ~+0.65 R@10 F-030 recovery. That single long-context pass is exactly late chunking's
first step (the whole-doc `VECTOR`), so:
- **Reframe:** late chunking's dominant win is now the whole-doc `VECTOR` via single long-context pass
  for LONG docs (>2048 today, windowed) — this is 708-MEASURED and CLS-on-distribution, i.e. MORE
  certain than the per-span chunk half (I-2). This elevates Phase 2 (long-doc / context-length) from
  "the throughput win" to "the F-030 quality fix" and is the higher-value half.
- **Validation set:** ADD `mixed/legal-clerc-200` (register signature `90d4300d…`, regenerable
  `corpus-fetch-clerc --seed 666 --n-queries 200`) as the primary A/B corpus — largest effect.
- **Context-length lever:** the 708 win needs the embed pass to see the whole long doc; production
  `justsearch.embed.context_length=2048` caps this, and >2048 docs window at 512. Phase 2 must either
  raise the context (VRAM/latency A/B) and/or macro-window at a larger width; 708 measured 8192.
- **Coordination:** 708 is PAUSED until this PR merges (founder directive) and will not touch registers
  or engine code meanwhile. Register Q-016 is ours to update here; 708 reconciles after rebase.

**I-4. Re-sequencing implication (not yet acted).** Given I-2 + I-3, the value order is now:
(1) VECTOR single-pass for long docs = 708-measured F-030 fix (CLS-safe); (2) chunk dedup via per-span
pooling = throughput + a CLS-risky chunk-quality bet. Phase-1a (≤2048, VECTOR bit-identical + per-span
chunks) is a sound, harmless default-off foundation for both. Phase 2 now carries the primary quality
win. The A/B (Phase 4) on legal-clerc-200 is the decision point for whether per-span chunks ship.

**I-5. Phase 1 COMPLETE + committed (default-off, green).** Commits `db5a83a` (1a primitive+test),
`42d8862` (1b flag + `LateChunkingEmbedBackfillOps` additive pass + env-var doc). `build -x test`
green; worker-services 802 / worker-core 254 / configuration 183 tests pass; backfill test 12/12;
flag-off strict no-op (`verifyNoInteractions`). Critical-analysis pass done on both subagent diffs
(runHidden extraction is output-preserving; the backfill pass is status-driven dedup with
tempdoc-700 failure parity, SPLADE/NER untouched; service prefixes + span-shifts correctly).

**I-6. Phase 2 decomposition insight (from 708 + CLS).** The 708-measured F-030 win
(R@10 0.100→0.745 on legal) is fundamentally a **context-length** effect: gte given the whole long
doc in ONE pass (≤8192 tokens — legal-clerc median ~7k tokens fits) yields a far better whole-doc CLS
`VECTOR` than the production mean-of-512-window-CLSs. This is **separable from and larger than** late
chunking's per-span chunk dedup, and it is **CLS-on-distribution** (single CLS of the whole doc), so
it is the lower-risk, higher-value half. Two consequences:
- **Phase 1 (≤2048) cannot be A/B-tested on legal-clerc** — those docs are all >2048 tokens, so
  `embedWithSpans` returns null and the late path no-ops there. Phase 1 is only exercisable on
  short-doc corpora (scifact, enron). To validate on the corpus where the effect is largest (708's
  legal-clerc), **Phase 2 is required.**
- **Phase 2 shape:** raise the late-chunking single-pass eligibility toward the model's real context
  (8192, gte supports 32k) so docs ≤8192 tokens go single-pass — capturing the 708 VECTOR win AND the
  per-span chunk dedup for those docs. Docs >8192 tokens (minority) macro-window or fall back. Open
  sub-question for the A/B: whether to ALSO raise the base `justsearch.embed.context_length` (affects
  all embedding, VRAM/latency) or keep the larger context scoped to the late-chunking path. The
  context-raise VECTOR win could even be measured independently of per-span chunks (a cleaner first
  A/B arm). VRAM: an 8192-token single pass on gte-base (~300M) is feasible on 12GB one-doc-at-a-time;
  measure latency/VRAM in the Phase-0-style spike.

## Phase J — context-length A/B (2026-07-10, /plan Phase 4 first arm): F-030 VECTOR win reproduced live; late chunking is the OOM-safe vehicle

Two clean-lifecycle pipeline builds on `mixed/legal-clerc-200` (198 docs / 200 queries, register
signature `90d4300d…`, reused from the 708 worktree — no re-download), late chunking OFF, isolating
the base embed-context effect. Scratchpad `691-ab/`.

| Arm | embed context | vector nDCG@10 | hybrid nDCG@10 | comparable |
|---|---|---|---|---|
| A (baseline) | 2048 (prod) | **0.0597** | 0.5216 | True |
| B | **8192** (`JUSTSEARCH_EMBED_CONTEXT_LENGTH`) | **0.3403** | 0.5344 | True |

- **J-1. Harness validated:** Arm A reproduces the register baselines exactly (vector 0.060, hybrid
  0.521) — like 708's Gate-0, our pipeline faithfully reproduces production.
- **J-2. F-030 dense revival is REAL and reproduced live:** raising the embed context so long legal
  docs embed in a SINGLE pass (instead of 512-token windows mean-pooled) lifts the whole-doc `VECTOR`
  (dense) leg **0.0597 → 0.3403 (5.7×)**. This is the 708 finding (R@10 0.100→0.745; here the stricter
  nDCG@10) reproduced in our own engine, CLS-on-distribution, late-chunking-independent. **This alone
  is a shippable F-030 lever** (a config/default change), separable from the per-span chunk dedup.
- **J-3. hybrid barely moves** (0.5216→0.5344, +0.013): on legal the fused default rides BM25, so the
  dense revival surfaces in the `vector` leg / union-recall, not (yet) in fused nDCG. The win is a
  representation-completeness gain (F-028 territory) more than a headline-hybrid gain — matches the
  register's F-029/F-030 framing.
- **J-4. KEY implementation finding — a naive base-context bump is OOM-UNSAFE; late chunking is the
  fix.** Arm B logged 17 `Batch embedding failed … BFCArena requested 7.1 GB` events: the base batch
  path put 8 × 8192-token docs in one ORT run (MAX_ORT_BATCH_SIZE=8), blowing even a 6144 MB arena.
  The backfill's per-doc fallback (batch=1, ~0.9 GB) recovered them, so the index still single-passed
  (0.34 is ~clean), but at a throughput cost. **`embedWithSpans` processes ONE parent at a time
  (batch=1) by construction — so late chunking captures the single-pass VECTOR win WITHOUT the batch
  OOM.** This is a second, independent reason late chunking (Phase 2) is the right vehicle, beyond the
  chunk dedup: it is the OOM-safe path to the F-030 fix.
- **J-5. Consequence for Phase 2:** implement the long-doc single-pass via the late-chunking path
  (batch-1 `embedWithSpans` at a raised eligibility limit), NOT a global base-context bump (which
  OOMs the batch path). The A/B to run next: late-chunking OFF vs ON at the raised limit on
  legal-clerc, measuring vector + union-recall (the direct dense-leg / completeness signal) — plus
  the per-span chunk quality question (I-2, CLS) that this VECTOR-only arm did not test.

## Phase K — continuation state (2026-07-10): read this first if you are picking this up

Self-contained snapshot so the tempdoc stands alone. Branch `worktree-691-takeover`, default-off,
nothing merged. Phases A–E (NER fp16 + arena, SHIPPED PR #90 on `main`) are DONE and unrelated to the
work below. The active work is the **E-5 late-chunking** effort (§G design → §H research → §I/J impl).

### K-1. What is IMPLEMENTED and committed on this branch (all default-OFF)
- **`OnnxEmbeddingEncoder.embedWithSpans(String content, int[][] charSpans)`** (`modules/worker-core/
  .../embed/onnx/OnnxEmbeddingEncoder.java`, commit `db5a83a`). Embeds a doc ONCE and returns
  `EmbedResult(vector, chunkVectors, chunkCount)`: `vector` = whole-doc pool (bit-identical to
  `embed()` by construction via the shared `runHidden` extraction); `chunkVectors[i]` = masked-mean of
  the tokens whose `getCharTokenSpans()` offset intersects `charSpans[i]` (`poolSpan`, excludes
  masked/null/zero-width special tokens; isolated-embed fallback for a zero-token span). Returns
  **null if the doc > `maxSeqLen` tokens** (context window; caller falls back). Unit test
  `OnnxEmbeddingEncoderLateChunkingTest` exists but SKIPS in this worktree (no `.onnx` on the worktree
  model path — models resolve from the MAIN checkout `F:\justsearch-public\models` at dev-stack
  runtime only; run it where the integration harness has a model).
- **Flag `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED`** (default `false`), commit `42d8862`, wired
  EnvRegistry → `ResolvedConfigBuilder.buildEmbedding` → `ResolvedConfig.Ai.Embedding.lateChunkingEnabled`
  → `EmbeddingConfig` → the backfill. `EmbeddingProvider.embedWithSpans` delegates to the encoder and
  **prepends `documentPrefix` ("search_document: ") and shifts spans** so the doc vector stays in the
  production embedding space (bit-identical VECTOR).
- **`LateChunkingEmbedBackfillOps`** (new, `modules/worker-services/.../loop/ops/`, commit `42d8862`):
  an ADDITIVE pass wired into `BackfillScheduler.runIdleCycle` BEFORE the combined pass. When the flag
  is on, for a chunked parent whose embedding is PENDING it fetches the parent content + its chunk
  docs' `[CHUNK_START_CHAR, CHUNK_END_CHAR)` spans (ordered by `CHUNK_INDEX`), calls `embedWithSpans`,
  writes `VECTOR` + each `CHUNK_VECTOR`, and marks ONLY the embedding statuses COMPLETED — **SPLADE/NER
  untouched** (processed normally afterward). Long docs (null) / malformed spans / count-mismatch are
  left PENDING for the existing per-doc fallback. Tempdoc-700 failure escalation preserved. Flag-off is
  a `verifyNoInteractions` strict no-op. Build + module tests green (worker-services 802 / worker-core
  254 / configuration 183; backfill test 12/12). Env var documented in `environment-variables.md`.

### K-2. What is MEASURED / validated
- **§Phase J (the decisive A/B, legal-clerc-200, late chunking OFF):** raising `embed.context_length`
  2048→8192 (long docs single-pass instead of 512-window-mean) lifts the **whole-doc dense leg
  `vector` nDCG@10 0.0597 → 0.3403 (5.7×)**; hybrid barely moves (0.5216→0.5344, rides BM25). This is
  the 708-measured F-030 dense revival, reproduced live, CLS-on-distribution, late-chunking-independent.
- **The single-pass VECTOR win is the DOMINANT, LOWER-RISK half** and is a shippable config lever on
  its own. The per-span CHUNK dedup (the throughput half) is the CLS-RISKY, UNTESTED half.

### K-3. REMAINING WORK (the plan; nothing below is started)
- **Phase 2 — long-doc single-pass via late chunking (the F-030 fix + dedup).** Make the late-chunking
  path single-pass docs up to a larger context (target ~8192; gte supports 32k). CRITICAL: do it via
  the **batch-1 `embedWithSpans` path, NOT a global `embed.context_length` bump** — the base BATCH path
  puts 8×8192-token docs in one ORT run and OOMs (§J-4: BFCArena requested 7.1 GB; per-doc fallback
  rescued it but slowly). `embedWithSpans` is batch-1 by construction → OOM-safe. Mechanics options:
  (a) give the late-chunking path its own higher seq limit (so `embedWithSpans` doesn't return null
  until e.g. 8192) while leaving the base path at 2048; (b) docs beyond the raised limit macro-window
  or stay on the existing fallback. Watch VRAM: a batch-1 8192-token pass ≈ 0.9 GB — fits; keep the
  embed arena adequate (`JUSTSEARCH_EMBED_GPU_MEM_MB`, default 2048; §J used 6144).
- **Phase 4 — the deciding A/B (needs the dev stack/GPU).** Late-chunking OFF vs ON at the raised limit
  on `mixed/legal-clerc-200` (+ a short-doc control like `beir/scifact` and `mixed/enron-qa`). Measure
  `vector` + **`union-recall-gate`** (the direct dense-leg/completeness signal — the win is here, not in
  hybrid nDCG) + `relevance-gate` + `leak-gate`. This is where the OPEN RISK gets settled: does the
  per-span-MEAN chunk vector help or hurt on a CLS model (I-2)? If chunks hurt, ship the VECTOR-single-
  pass win alone (it is separable) and drop/rethink the per-span chunk half.
- **Phase 3 — teardown (at the default-ON flip, same PR arc).** Once the A/B passes and the flag flips
  default-on, remove the now-dead duplicate path for chunked docs (the separate per-chunk embed +
  parent-window pooling) after a consumer scan — `embedBatchWithChunking`/`ChunkedEmbedding.chunkVectors`
  stay live for the single-doc `embed` API, `OnnxEmbeddingBackend`, `LocalIntentTranslatorV2`, and
  non-chunked docs (NARROW, don't delete those). Mark §G (mean-pool design) superseded by §H/§Phase K.
- **Phase 5 — docs/registers.** Move register Q-016 → Findings with the A/B result (`docs/reference/
  search-quality-register.md`, then `node scripts/docs/skills-sync.mjs`); update canonical
  `docs/explanation/23-search-pipeline-overview.md` stage 6 (Dense Embedding). No new SchemaField
  (reuse VECTOR/CHUNK_VECTOR) → no `fields.v1.json` dual-copy.

### K-4. OPEN RISKS & WATCH-ITEMS (do not lose these)
1. **CLS chunk-vector quality (the crux, UNTESTED).** gte is CLS-pooled; `poolSpan` is masked-MEAN, so
   per-span chunk vectors are off-distribution. §Phase J tested only the VECTOR (CLS-safe); the CHUNK
   half is unmeasured. The Phase-4 A/B on legal-clerc is the decision point.
2. **Chunk vectors exclude the `documentPrefix`.** In late chunking the "search_document: " prefix sits
   once at doc start (outside every chunk span), so late-chunk chunk vectors lack it, whereas today's
   separate chunk embeds include it. May affect query-doc space alignment for a CLS model — an A/B axis.
3. **hybrid vs vector.** On legal the fused default rides BM25, so the dense win surfaces in `vector` /
   union-recall, not headline hybrid nDCG. Judge the A/B on the dense leg + completeness, not hybrid.
4. **VECTOR bit-identity is by-construction, not yet live-verified** (unit test skipped for lack of a
   worktree model). A live check would confirm it; the shared `runHidden` code path makes it robust.
5. **Backfill per-parent query cost:** `LateChunkingEmbedBackfillOps` does ~3 index queries per parent
   (less batch-efficient than the combined path). Correctness-fine (DocValues are cheap); optimize only
   if measured to matter — the win is GPU passes, not index reads.

### K-5. REPRODUCTION / OPERATIONS (how to run the A/B)
- **Corpus:** `mixed/legal-clerc-200` (register signature `90d4300d…`) is gitignored. It was reused
  from the sibling `708-encoder-investigation` worktree's `datasets/mixed/legal-clerc-200/` (copied into
  this worktree — no re-download; HF hub-cached anyway). Regenerable: `python -m jseval corpus-fetch-clerc
  --name legal-clerc-200 --seed 666 --n-queries 200` (recipe `scripts/jseval/666-corpora/legal-clerc-200/`).
- **A/B command** (from `scripts/jseval`, `PYTHONUTF8=1`): `python -m jseval run --dataset
  mixed/legal-clerc-200 --modes vector,hybrid --pipeline --start-backend --clean --output-dir <out> --json`.
  Arm with the context raise: prefix `JUSTSEARCH_EMBED_CONTEXT_LENGTH=8192 JUSTSEARCH_EMBED_GPU_MEM_MB=6144`.
  For late chunking: `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED=true` (+ whatever Phase-2 raised-limit knob
  lands). Dev stack is shared/one-at-a-time; `--start-backend` auto-stops. Models resolve from the MAIN
  checkout automatically. `--clean` wipes worker.log between runs (extract per-run evidence before the
  next `--clean`).
- **Gates:** `jseval relevance-gate` / `union-recall-gate` / `leak-gate` / `perf-gate` (baselines
  `scripts/jseval/*-baselines.v1.json`); flag pattern template = tempdoc-643 `RERANK_JUDGE_BLEND_ENABLED`.
- **GPU coordination:** the 708 lane (encoder-domain / F-030 investigation) is PAUSED pending THIS PR's
  merge (founder directive); its bake-off (`encoder_bakeoff_708.py`, the source of the R@10 0.100→0.745
  evidence) has FINISHED. 708 reconciles register Q-016 after rebase; do not touch 708's worktree beyond
  read-only corpus reuse. Ship default-off → measure → default-on (D-004 template); authorization-gated
  (get founder go-ahead before flipping default-on or merging).

## Phase M — offline CLS chunk-vector experiment (2026-07-10, plan A1): per-span chunk half REGRESSES; Phase 2 ships VECTOR-only

The L-8 "cheap offline evidence" ran (`scripts/jseval/experiments/late_chunk_cls_check_691.py`,
results `tmp/691-cls-check/`; RTX 4070 fp16, 708-venv reuse, offline HF cache, 38.5s). Design:
identical 500/50-token chunk spans over the first ≤8192 tokens of each legal-clerc-200 doc
(85/198 docs truncated), CLS queries, exact-NN cosine, MaxP doc scoring — isolating ONLY the
chunk-vector derivation: **C** = per-chunk CLS embeds (production recipe) vs **LC** = span-mean
from one long-context pass (arXiv:2409.04701).

| Metric | C (per-chunk CLS) | LC (late chunking) | Δ |
|---|---|---|---|
| nDCG@10 | 0.6397 | 0.4068 | **−0.2329** |
| R@10 | 0.850 | 0.585 | **−0.265** |
| R@100 | 0.960 | 0.915 | −0.045 |

Per-query: C wins 108 / LC 25 / ties 67. LC's vectors are correlated-but-worse (R@100 nearly
holds; @10 collapses) — exactly the off-distribution failure shape the literature predicted for
mean-pooling a CLS model (§L-5). Far outside the −0.01 decision threshold.

**Verdict (per the founder-approved decision rule): the per-span chunk half is DROPPED. Phase 2
ships the single-pass whole-doc VECTOR only** — chunks keep today's per-chunk CLS path. K-4 risk
#1 is settled (was the crux; now measured). Mechanically: the late pass keeps using
`embedWithSpans` with an empty span array (same primitive, bit-identical VECTOR property intact,
batch-1 OOM-safe); it writes `VECTOR`+`EMBEDDING_STATUS` only and leaves all chunk statuses to
the existing path. Flag name kept (`JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED` — it gates the
late-chunking *mechanism*; the chunk half is disabled by this measurement, documented here and
in the env-var doc).

**Secondary observation (recorded for 708's lane, NOT acted on here):** condition C itself —
pure chunk-CLS dense, exact-NN MaxP — scores **nDCG@10 0.640 / R@10 0.850** on legal-clerc,
versus the production whole-doc `vector` leg's 0.060 and hybrid's 0.521. This sits in tension
with F-030(678)'s "encoder-domain mismatch, not granularity" framing: at chunk granularity with
exact NN and MaxP, this encoder separates legal content rather well offline. Differences from
production (exact-NN vs HNSW, MaxP vs fusion, 8192-token coverage cap, isolated leg vs fused
pipeline) mean this is NOT directly comparable — but it suggests the production chunk-dense leg
and/or its fusion path deserves attribution before concluding the encoder itself is the ceiling.
Left as evidence for 708 to reconcile post-merge.

## Phase L — second-takeover verification + verdict (2026-07-10, fresh session; three-subagent verify/survey/research pass, no code changes)

A fresh takeover re-verified §Phase K against the branch, `main`, the newer tempdocs, and the
literature. §K is accurate; two of its risks are now *settled in opposite directions* (one retired,
one upgraded), one docs discrepancy was found, and the throughput accounting needs a correction that
reshapes Phase 2's framing.

**L-1. Branch state: every §K-1 claim CONFIRMED at file:line** (independent subagent, read-only).
`embedWithSpans` + shared `runHidden` (`OnnxEmbeddingEncoder.java:552-582`, `:489-531`), null-return
before span materialization (`:556-558`), CLS-conditional `pool()` (`:761-783`), masked-mean
`poolSpan` + isolated fallback (`:593-626`, `:570-577`), flag chain EnvRegistry (`:324-327`, default
`"false"`) → `ResolvedConfigBuilder:1012-1026` → `ResolvedConfig.java:248-257` →
`EmbeddingConfig.java:96` → `BackfillScheduler.java:322`; late pass ordered before combined
(`BackfillScheduler.java:133-134`); embedding-only status writes
(`LateChunkingEmbedBackfillOps.java:172-187`); genuinely batch-1 (tensor shape `{1, seqLen}`,
`OnnxEmbeddingEncoder.java:491`; never touches `MAX_ORT_BATCH_SIZE=8`, `:218`). Branch footprint is
exactly the described 16 files (+1380/−19), clean tree. One naming nuance: the flag-off no-op test is
`CombinedEnrichmentBackfillOpsTest.lateChunking_flagOff_isStrictNoOp` (`:509-535`), not a standalone
test file. **Load-bearing for Phase 2:** there is NO separate late-chunking seq limit today —
`embedWithSpans` and `embed()` read the same `final maxSeqLen` field, sourced once at construction
from `justsearch.embed.context_length` (default 2048, `ResolvedConfigBuilder:1024`;
`JUSTSEARCH_EMBED_CONTEXT_LENGTH`, `EnvRegistry:811-813`). Phase 2's "own higher limit" therefore
needs a new config field (e.g. `lateChunkingContextLength`) threaded through the same chain, or a
per-call limit — `maxSeqLen` is per-instance and `final`.

**L-2. Main drift: ZERO; nothing supersedes this branch.** `main` HEAD (`301d9b8`) is byte-identical
in tree to the merge-base `1a4729e` (the 42 intervening commits are reconciliation merges of content
already squashed into `1a4729e`); `origin/main` is at `1a4729e`. Newer tempdocs 704/705/706/707 all
*route* enrichment-throughput ownership here; 708 exists only in its worktree, status "PAUSED for 691
coordination (founder directive 2026-07-10)". One forward-looking collision: unmerged sibling
worktree **702** (`702-dense-calibration`) also touches `ResolvedConfigBuilder.java` / `EnvRegistry.java`
— whichever of 691/702 merges second reconciles those two files.

**L-3. Docs discrepancy — 691's own register/env-doc edits are STRANDED UNCOMMITTED in the shared
MAIN checkout, not on this branch.** §K-1 says the env var is "documented in
`environment-variables.md`", but the branch footprint (L-1) contains no `docs/reference/` file. The
Q-016 register entry (`search-quality-register.md`) and the `JUSTSEARCH_EMBED_LATE_CHUNKING_ENABLED`
env-var doc edit exist only as uncommitted working-tree modifications in `F:\justsearch-public`
(they match this work verbatim — "691 §Phase I — IN IMPLEMENTATION, worktree 691-takeover"). Before
the PR, re-author (or port) both edits ON THIS BRANCH; do not commit them from main. Committed main
has no Q-016 at all.

**L-4. Register reconciliation duty for Q-016 (write it precisely).** The committed register's F-030
entry from tempdoc 678 concludes the legal dense death is an encoder-domain mismatch — "not gating,
not query length, not granularity". Context length was NOT one of 678's tested axes, so §J's 5.7×
context-length revival does not contradict it — but the Q-016/F-030 text must cite that scope
explicitly, or the register will hold two entries that read as contradicting. (Separately: the
committed register already has TWO entries numbered F-030 — 678's and 706's — a pre-existing
numbering collision; logged to the observations shard, not 691's to fix.)

**L-5. Literature check UPGRADES risk K-4#1 (CLS chunk vectors) from "untested" to
"researched-against".** Focused research pass (sources verified): the late-chunking paper
(arXiv:2409.04701 incl. the 2025 revision) is explicitly scoped to mean-pooled models — all evaluated
models are mean-pooled; Jina's own follow-up states outright *"Models using CLS or max pooling aren't
compatible with late chunking"* (jina.ai "What Late Chunking Really Is… Part II"); Weaviate's
implementation writeup repeats the mean-pooling requirement; the 2026 taxonomy replication
(arXiv:2602.16974) restricted itself to mean-pooled backbones; no published result applies late
chunking to a CLS model in either direction. Closest proxy (arXiv:2601.21525 "LMK > CLS", Landmark
Pooling): naive inference-time mean-pooling of token states on a CLS-oriented backbone UNDERPERFORMED
native CLS, worst on long docs (MLDR nDCG@10 15.7 vs 24.9) — and that paper exists precisely because
the field considers naive-mean-on-CLS a real failure mode (its landmark-token fix is not a drop-in
inference trick). gte-multilingual-base confirmed CLS-pooled, native 8192 context (RoPE base rescaled
10k→160k in stage-2 training at 8192 — trained at that length, not extrapolated; the "32k" figure in
§H is unsubstantiated for this checkpoint — treat 8192 as the ceiling). Consequence: expect the
per-span chunk half to REGRESS; plan accordingly (L-8). The VECTOR half (CLS of a single long pass)
is unaffected by all of this and is the measured, on-distribution win.

**L-6. Risk K-4#2 (chunk vectors exclude the documentPrefix) is RETIRED — the prefix is empty at
runtime for this model.** `EmbeddingService.loadPrefixes` (`:454-479`) reads `prefix_config.json`
from the model dir; the runtime model dir (main checkout, `models/onnx/gte-multilingual-base/`) has
`{"document_prefix": "", "query_prefix": ""}` — so `documentPrefix = ""`, the span shift is 0, and
today's chunk embeds don't carry a prefix either. The `"search_document: "` value in §K-1 is only the
no-config-file default. (§K-4#2 stays as a caveat for any future prefix-using checkpoint.)

**L-7. Throughput-accounting correction: at 8192, late chunking is NOT a throughput win — Phase 2 is
a QUALITY lever, and the original dense-corpus throughput charge is served by §G's projection for
docs ABOVE the limit.** Attention is O(seq²): for gte-base geometry (12 layers, h=768, FFN 4h) the
attention term ≈ the linear term at seq ≈ 4.6k tokens, so a single 8192-token pass costs ≈2.5-2.8×
per token vs 512-token windows, while the current duplicate path (windowed parent pass + separate
chunk embeds) costs ≈2.2-2.4 units/token. So at 8192 eligibility: full late chunking ≈ wash on
throughput; VECTOR-only single-pass (keeping today's chunk embeds) ≈ net throughput COST — a price
worth paying for the 5.7× dense revival, but it must be MEASURED, not assumed away (this is a
throughput tempdoc: record enrichment wall time in every A/B arm; the §J arms did not report walls).
Second consequence: `realdocs-v1`-class dense docs (~138 chunks/doc ≈ 60k+ tokens) exceed ANY
single-pass limit — late chunking never reaches the corpus class that motivated E-5 unless
macro-windowed. For docs > limit, the dedup lever remains **§G pool-VECTOR-from-chunk-vectors**
(deletes the giant windowed parent pass ≈ halves embed work at high density; distributionally
comparable — today's long-doc VECTOR is already a mean over window-CLS vectors, §G's is a mean over
chunk-CLS vectors). §G is therefore NOT superseded — it is the long-doc complement to late chunking's
≤8192 half. (All arithmetic estimates modulo fused-kernel constants — attribution-before-optimization
says measure them in the A/B, which the existing instruments already do.)

**L-8. VERDICT (takeover contract): CONTINUE, NOW — with Phase 2 re-shaped to split the safe half
from the researched-against half.**
- **Do it at all?** Yes. The motivating evidence is measured and reproduced live in our own engine
  (§J: vector nDCG@10 0.0597→0.3403), the branch is sound and default-off (L-1), nothing on main
  supersedes it (L-2), and 708 is paused *waiting on this PR* — walking away strands a founder-directed
  coordination chain.
- **Now?** Yes — it blocks 708, and the A/B instruments + corpus already exist.
- **Re-shape:** (1) **Ship path (safe, measured): VECTOR-only single-pass** for docs ≤8192 via the
  batch-1 `embedWithSpans` path with a NEW late-chunking-specific limit config (L-1) — the F-030 fix,
  CLS-on-distribution, OOM-safe (§J-4). (2) **The per-span chunk half: get the cheap evidence BEFORE
  building it.** The cheapest decisive evidence does not exist yet and needs NO engine code and NO dev
  stack: an offline experiment in the 708 bake-off harness pattern (`encoder_bakeoff_708.py`) scoring
  span-mean chunk vectors vs today's per-chunk CLS embeds on legal-clerc chunk-level retrieval.
  Literature (L-5) predicts regression; if it regresses offline, drop the per-span half without paying
  for engine plumbing + a dev-stack A/B. (3) **Long-doc throughput (the tempdoc's original charge):**
  revive §G projection for docs > limit as its own follow-on (quality-gated like everything here).
  (4) Every A/B arm records enrichment wall time (L-7).
- **Displaces/duplicates:** nothing live — 708 defers by directive and owns post-merge register
  reconciliation; §G is absorbed as the long-doc complement (its "superseded by §H" note in K-3 Phase 3
  is hereby corrected); 686/706 own extraction; 657 owns model distribution.
- **Still-not-do list stands:** chunk-cap raise (refuted, F-1), primary −35% (declined, B-6),
  realdocs-v1 total-time confirmation (open, F-3).
- Authorization posture unchanged: default-off; founder go-ahead required before Phase-2 implementation,
  default-on flip, or merge.
