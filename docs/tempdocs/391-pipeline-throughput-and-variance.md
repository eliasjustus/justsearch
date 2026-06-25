---
title: "391 — JustSearch Pipeline Throughput, Baselines & Variance Investigation"
---

# 391 — JustSearch Pipeline Throughput, Baselines & Variance Investigation

**Status:** Active (nearing closure — most in-lane items shipped).
**Created:** 2026-04-19 (split from 390-system-optimization.md).
**Updated:** 2026-04-19 evening — Experiment 1 run + analysed (cache
mechanism validated, wall-time impact null); `ner_total` "non-determinism"
resolved as a jseval measurement bug (commit `2a5d153c1`), not pipeline
non-determinism. 10 commits landed in-session.
**Owner:** JustSearch agent.
**Companion audit doc:** `docs/tempdocs/393-code-audit-pipeline-optimization-commits.md`
(critical analysis of the implemented changes; see § Handoffs → H-AUDIT-1).

> NOTE: Noncanonical doc (active investigation). May drift.

---

## Purpose

Single home for JustSearch-pipeline-specific work:

- Throughput baselines + run records (jseval scifact, lexical/hybrid).
- Root-cause investigations of run-to-run and cross-session variance.
- Theorised resolutions, organised by tier.
- JustSearch opportunity register and experiments (E-J-*).
- JustSearch-side correctness fixes (SPLADE counter drift fix landed
  in this session is recorded here).

The companion `docs/tempdocs/390-system-optimization.md` covers
whole-PC tuning (Windows / BIOS / driver / power-plan / system synthetic
benchmarks). Both docs share **Hardware Profile** and **Measurement
Methodology** — those live in 390 only; this doc cross-references.

---

## Hard Constraints

1. **The dev PC is multi-purpose.** It is used for normal work
   alongside benchmarking. Theorised resolutions that require
   environment isolation, dedicated bench hardware, or refusing
   comparisons across mismatched environments are explicitly **rejected**
   and marked as such — kept in the doc so the constraint stays part of
   the visible logic.
2. **All measurement uses `jseval` (`scripts/jseval/`).** Never write
   a parallel harness. If jseval lacks something, improve jseval.
3. **All cross-doc data references 390 for hardware + methodology.**
   Don't duplicate those rows here.

---

## Cross-References

| Doc | Why it matters here |
|---|---|
| `390-system-optimization.md` | Hardware Profile, Measurement Methodology, system tuning, system-side debunked items. |
| `311-gpu-memory-partitioning.md` | BFCArena fragmentation root cause; ORT arena shrink-API tradeoff. |
| `312-primary-indexing-throughput.md` | Primary-indexing path + bottleneck analysis. |
| `334-single-pass-enrichment.md` | Combined-enrichment pass; eliminated 249 % SPLADE churn from NER RMW. |
| `358-pipeline-model-selection.md` | Embed model decision (gte-multilingual-base). |
| `381-model-distribution-architecture.md` | **Prerequisite for Issue B follow-up fixes.** Built `ModelSessionFactory`, `VariantSelection`, install contract, composition root — the centralization that makes our Option 1 recommendation a small delta instead of a redesign. 381 § R1 + R5 documented the same GPU-cache-bypass gap we found, and explicitly chose not to fix it (see § Issue B follow-up → "Relationship to tempdoc 381"). |

---

## Correctness Fixes Applied (JustSearch-side, this session)

### SPLADE Counter Drift Fix (2026-04-18)

**Symptom**: first jseval scifact pipeline run hung with
`splade_pct = 92.3 %` at elapsed 406 s and never progressed.
`/api/status` snapshot:
```
spladeDocCount: 5189
spladeCompletedCount: 4789   (400 missing)
spladePendingCount: 0        (queue empty — no way to recover)
spladeFailedCount: 0
enrichmentCompleted.splade: 5268   (79 OVER docCount)
```
`jseval`'s `--pipeline` threshold (99.9 %) is unreachable while
`pendingCount = 0`.

**Root cause**: `WritePathOps.readModifyWrite` silently dropped the
non-stored `splade_status` doc-value during any RMW called with
`preserveSplade=true` (primarily
`NerBackfillOps.updateDocumentsBatch(..., true)`). The old guard that
would have re-injected `SPLADE_STATUS = PENDING` on missing-status was
skipped when `preserveSplade=true`, leaving the doc with no status field
at all — invisible to both the backfill query (`status=PENDING`) and the
counters (`status=COMPLETED/FAILED`).

**Fix**: `WritePathOps.readModifyWrite` now reads the existing
`splade_status` and `splade_retry_count` via doc-values (matching the
pattern in `ReadPathOps.readLongDocValueOrStoredLong`) and restores them
into the new doc when absent from the stored-fields read and not
supplied by the caller. Final safety-net injects `PENDING` if every
other recovery path missed. Added regression test
`BatchUpdateIntegrationTest.preserveSpladeTruePreservesSpladeStatusWhenNonStored`
using the production-matching schema (`stored:false`).

**Verification**: re-ran the full jseval scifact pipeline — SPLADE
reached 100 % at elapsed 205 s, full lifecycle completed exit 0.
Repeated 3× in baseline (medians below).
`splade_churn_drops = 1` in every baseline run (normal single drop, not
the stuck-forever pattern).

**Committed 2026-04-19** as `57fa91775` `fix(391): SPLADE counter drift —
preserve splade_status doc-value across RMW`. Files in the commit:
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java` — imports + RMW logic + two private doc-value helpers.
- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java` — new regression test.

Unit/integration tests green for both `adapters-lucene` and
`worker-services`.

**Companion commits landed in the same session:**
- `47b4ce3c0` — `chore(391): remove stale JUSTSEARCH_MODELS_DIR override, allow Skill(update-config)` (the env-cleanup correctness fix from this session).
- `3b19076eb` — `fix(391): jseval splade_churn_drops — raise tolerance from 0.01 pp to 0.5 pp` (E-J-N9; closes the measurement-noise contributor identified in Issue D root cause).
- `3af6773cc` — `fix(311,391): raise embed GPU arena limit from 2048 → 3072 MB for gte-multilingual-base` (E-J-N8 reframed; closes Issue D root cause — eliminates BFCArena failures completely. See Issue D resolution section + tempdoc 311 § Phase 7 for the A/B evidence).
- `2a5d153c1` — `fix(391): jseval ner_total_docs was a measurement bug, not pipeline non-determinism`. `timeline.py` locked in `ner_total_docs` at the FIRST row where `ner_pending == 0 AND ner_done > 0` — a snapshot that can be a TRANSIENT drain before ingestion finishes creating chunks. Fix: use `max(ner_done)` across all rows (monotonic by construction under `--clean` eval). Re-processing all 6 Experiment 1 timelines post-fix: every run reports `ner_total_docs = 7303` (std=0). The pipeline was always deterministic on this count — the "variance" was purely a reporting artefact.

---

## JustSearch Observability — use, don't rebuild

Everything below already exists in `scripts/jseval/`. Do not add a
parallel harness.

| Capability | Module | Lines | What it provides |
|---|---|---|---|
| Run suite with N repeats | `suite_stats.py` | 73 | `run_suite(fn, runs=5)` → list of dicts. `compute_stats` → median / min / max / stddev / 95 % CI via t-distribution. |
| Regression history | `history.py` | 166 | SQLite `eval-history.db`. Records nDCG / MAP / MRR / recall / P1 / latency / context-hit-rate per run. Regression gate: percentage (<N=8) or Student's t-test (≥N=8). |
| Ratio-based diff gate | `diff_gate.py` | 99 | `compare_ratio(baseline, candidate, lower_is_better, max_ratio, min_ratio)`. `build_gate_decision` → pass/fail JSON. |
| Run comparison | `compare_runs.py` | 232 | Two-run diff with structured per-query analysis. |
| Per-stage timing | `timeline.py` | 383 | Capture `/api/status` snapshots to TSV. |
| Provenance | `provenance.py` | 306 | Records env, git SHA, dataset, comparability. |
| CUDA trace | `nsys.py` | 231 | `start_capture` / `stop_capture` around a pipeline run; queries the resulting SQLite for summaries. |
| Comparability rules | `comparability.py` | 37 | Cross-hardware / cross-version rules. |
| Gradle engine/knn bench | `gradle_bench.py` | 232 | Already wraps `./gradlew.bat :modules:benchmarks:engineIndexBench` and `:knnBench`. Default `runs: int = 5`. |

**CLI commands** already wired (run `python -m jseval --help`):
`run`, `engine-bench`, `ingest-bench`, `knn-bench`, `llm-bench`,
`ui-perf`, `rag-eval`, `retrieval-eval`, `compare`, `diff`, `trend`,
`preflight`, `dev`, `search`, `logs`.

**Gradle-side observability** (zero code — just use the CLI flag):
- `./gradlew.bat <task> --profile` → HTML report under `build/reports/profile/`.
- `./gradlew.bat <task> --scan` → Develocity Build Scan (scans.gradle.com free tier, already configured in `settings.gradle.kts` — local auto-publish disabled, opt-in per-build; CI auto-publishes).
- `modules/benchmarks/` — real Gradle module with `engineIndexBench` and `rerankerBench` tasks, already used by CI `claim-a-report-win.yml` and `track-g-report-win.yml`, gated against baselines in `scripts/bench/baselines/`.

---

## Current Baselines

### 3-run baseline (captured 2026-04-18 evening)

**Setup**: scifact, 5 184 docs, 300 queries, modes `lexical,hybrid`,
full lifecycle (`--start-backend --clean --pipeline`), 3 back-to-back
runs, results under `tmp/jseval-baseline-3x/run{1,2,3}/`. Git sha
`58221d5fa` + this session's local SPLADE fix.

#### Throughput — 3-run table

| Metric | run1 | run2 | run3 | median | range (% of median) |
|---|---|---|---|---|---|
| Total ingest wall (s) | 257.2 | 251.0 | 253.1 | **253.1** | ±1.2 % |
| **Total docs/s** | 20.2 | 20.7 | 20.5 | **20.5** | ±1.2 % |
| Primary-indexing docs/s | 288.8 | 315.6 | 310.1 | **310.1** | ±4 % (run 1 colder) |
| Primary-indexing wall (s) | 16.6 | 14.5 | 14.5 | **14.5** | ±7 % |
| chunk 100 % at (s) | 217.3 | 217.1 | 213.1 | **217.1** | ±1 % |
| ner complete at (s) | 229.6 | 227.4 | 227.5 | **227.5** | <1 % |
| embed 100 % at (s) | 250.5 | 246.3 | 248.5 | **248.5** | ±1 % |
| splade 100 % at (s) | 252.5 | 246.3 | 244.2 | **246.3** | ±1.6 % |
| splade_churn_drops | 1 | 1 | 1 | 1 | 0 |
| GPU avg % | 59.3 | 71.5 | 59.4 | **59.4** | ±10 % |
| GPU peak % | 94 | 94 | 100 | 94 | ±3 % |
| VRAM peak (MB) | 4 816 | 4 174 | 4 074 | **4 174** | ±9 % |

#### Per-encoder ORT latency (median across 3 runs, µs)

| Encoder | calls (run1) | p50 | p95 | p99 | total inference (ms) |
|---|---|---|---|---|---|
| Embed | 954 | 65 699 | 77 529 | **201 981** | 75 386 |
| SPLADE | 5 320 | 16 080 | 20 578 | **23 330** | 87 466 |
| NER | 12 329 | 2 611 | 3 471 | **6 053** | 43 474 |

#### Retrieval quality (dead stable across 3 runs, <0.1 % variance)

| Mode | nDCG@10 | P@1 | R@10 | Legs observed |
|---|---|---|---|---|
| lexical | 0.6617 | 0.540 | 0.7793 | bm25 |
| hybrid | **0.7540** | 0.630 | 0.8876 | cross_encoder + dense |

#### Interpretation

- **Noise floor: CV ≈ 1.2 %** on total docs/s at N=3. Any future
  JustSearch tweak needs to move the median by ≥ 3 % to be
  distinguishable at this N; for the 2 % signal threshold in 390's
  Methodology, bump to N=5.
- **Retrieval quality is deterministic** (nDCG@10 variance <0.001).
  Chunk boundaries and encoder inference are non-deterministic between
  runs (see `ner_total` below), but this does not propagate to final
  scores.
- **Hybrid 0.7540 vs canonical register Phase D 0.736 (+1.8 pp)** —
  candidate for promoting to "Best known" in
  `docs/reference/search-quality-register.md → beir/scifact`. Legs
  observed here are `cross_encoder + dense` only; the register's 0.736
  is labelled `full` (bm25+splade+dense+CE). Handoff: see H-SQ-1.

#### Known non-determinism in this pipeline

- `ner_total` across the 3 runs: 5 300 / 6 100 / 7 300 (±38 % range).
  Does NOT affect throughput (NER completion time stable at ~228 s) or
  quality, but indicates chunk-count varies between runs. Likely cause:
  file-discovery ordering → chunking boundaries. Not blocking; flagged
  as follow-up (task #26).

### 3-run re-measurement (2026-04-19)

**Setup**: identical to the 3-run baseline above — scifact 5 184 docs,
300 queries, modes `lexical,hybrid`, full lifecycle (`--start-backend
--clean --pipeline`), same git SHA `58221d5fa` + same local SPLADE fix.
Three back-to-back fresh runs from cold backend, no system changes
between runs. Result folders: `tmp/jseval-current-2026-04-19/run{1,2,3}/`.
Captured on user request to characterise day-to-day variance and
warm-system effects.

#### Throughput (3 runs vs baseline median)

| Metric | run1 | run2 | run3 | median | CV % | baseline | Δ med |
|---|---|---|---|---|---|---|---|
| Total ingest wall (s) | 225.6 | 220.5 | 207.6 | **220.5** | 4.26 | 253.1 | −12.9 % |
| Total docs/s | 23.0 | 23.5 | 25.0 | **23.5** | 4.37 | 20.5 | **+14.6 %** |
| Primary-indexing docs/s | 192.7 | 314.2 | 311.2 | **311.2** | 25.4 | 310.1 | +0.4 % |
| Primary-indexing wall (s) | 26.9 | 16.5 | 14.5 | 16.5 | 34.5 | 14.5 | +13.8 % |
| chunk 100 % at (s) | 186.3 | 182.4 | 174.0 | 182.4 | 3.47 | 217.1 | −16.0 % |
| embed 100 % at (s) | 213.0 | 209.6 | 200.9 | 209.6 | 3.00 | 248.5 | −15.7 % |
| splade 100 % at (s) | 213.0 | 209.6 | 202.9 | 209.6 | 2.46 | 246.3 | −14.9 % |
| splade_churn_drops | 4 | 5 | 2 | 4 | — | 1 | +300 % |
| GPU avg % | 54.3 | 53.4 | 53.3 | 53.4 | 1.03 | 59.4 | −10.1 % |
| VRAM peak (MB) | 4 018 | 4 647 | 3 829 | 4 018 | 10.3 | 4 174 | −3.7 % |

#### Per-encoder ORT p99 latency (µs, 3 runs vs baseline median)

| Encoder | run1 | run2 | run3 | median | CV % | baseline | Δ med |
|---|---|---|---|---|---|---|---|
| embed | 129 761 | 147 455 | 128 516 | **129 761** | 7.83 | 201 981 | **−35.8 %** |
| splade | 21 757 | 24 297 | 21 889 | 21 889 | 6.31 | 23 330 | −6.2 % |
| ner | 4 184 | 4 915 | 4 110 | **4 184** | 10.1 | 6 053 | **−30.9 %** |

#### Cold-start embed ortMax (single-call outlier per run)

| run1 | run2 | run3 |
|---|---|---|
| 16 453 528 µs | 5 744 569 µs | **965 804 µs** |

17× improvement run 1 → run 3. CUDA driver / kernel-cache warmth on
a cold backend; NOT attributable to any code change.

#### Quality (6 runs total — 3 baseline + 3 today, all within ±0.005 nDCG@10)

| Mode | run1 | run2 | run3 | median | CV % | baseline median | Δ med |
|---|---|---|---|---|---|---|---|
| lexical nDCG@10 | 0.6608 | 0.6599 | 0.6584 | **0.6599** | 0.18 | 0.6617 | −0.3 % |
| hybrid nDCG@10 | 0.7536 | 0.7528 | 0.7571 | **0.7536** | 0.31 | 0.7540 | −0.1 % |

Hybrid legs observed: `cross_encoder + dense` (matches baseline).
Per-mode P@1 / R@10 stable across all 6 runs (lexical P@1 ~0.53,
hybrid P@1 ~0.62, hybrid R@10 ~0.89).

#### Findings (2026-04-19 re-measurement)

- **Quality is dead stable across days.** All 6 runs within 0.005
  nDCG@10. Hybrid CV across today's 3 runs = 0.31 %. SPLADE
  counter-drift fix is holding cleanly; no quality regression.
- **Run 1's primary-indexing anomaly was a single outlier.** Runs 2
  and 3 returned to 314 / 311 docs/s, essentially matching the
  baseline (310 docs/s). Median across 3 runs is +0.4 % vs baseline.
  See Issue B root cause below.
- **Warm-system effect is real and monotonic** across runs 1 → 3:
  throughput 23.0 → 23.5 → 25.0 docs/s, primary-idx wall 26.9 → 16.5
  → 14.5 s, embed ORT max 16.45 → 5.74 → 0.97 s.
- **Throughput median +14.6 % vs baseline is suggestive but not
  safely confirmed.** Above the 5 % signal threshold, but day-to-day
  CV (4.37 %) is 3.6× the baseline within-session CV (1.2 %). Treat
  as tentative.
- **ORT p99 improvements are robust across 3 runs** for embed
  (−35.8 %) and ner (−30.9 %). Same git SHA + working tree as
  baseline → attributable to system state, not code.
- **SPLADE churn drops elevated** (median 4 vs baseline 1; values
  4 / 5 / 2). Pipeline correctness intact (all stages reach 100 %,
  quality unchanged). See Issue D root cause below.
- **Updates Tier 2 "Noise floor is unknown" open issue (in 390).**
  Provides empirical day-to-day CV: total docs/s CV = **4.37 %** at
  N=3 with same SHA / same data / cold restarts between runs;
  ≈ **3 %** if the cold first run is discarded. Methodology
  implication: 5 % signal threshold is at the edge of cross-session
  noise.

### Decision

These 3 runs are **not** promoted as a new baseline. The 2026-04-18
3-run baseline (median 20.5 docs/s, hybrid 0.7540) remains the anchor
for JustSearch experiments (E-J-N1…N6, Batches B / E / F / G in 390)
until either (a) a controlled re-baseline is captured at the same time
of day under the same hygiene as the original (Batch C post would
naturally do this), or (b) the variance source is identified and the
4.37 % CV explained.

Quality decision is unchanged: hybrid 0.7540 (baseline) ↔ 0.7536
(today) is well within noise; the Best-known-row promotion handoff
(H-SQ-1) remains valid.

---

## Confirmed Root Causes (2026-04-19 deep investigation)

Three issues were investigated to root cause: **(B)** run-1
primary-indexing freeze, **(D)** SPLADE churn drops elevated 4× vs
baseline, **(A+E)** day-to-day throughput variance widened 3.6×.

Method: timeline-TSV diff between today's 3 runs and baseline's 3 runs,
run-3 worker.log inspection (only the latest log survives `--clean`),
code-path tracing in `scripts/jseval/` + `modules/adapters-lucene/` +
`modules/indexer-worker/`, and cross-reference with tempdocs
311 / 312 / 334. One web-search corroboration (ORT offline-mode
optimised-graph cache mechanism). Findings below; supporting file:line
citations and worker.log timestamps inline.

### Issue B — Run-1 primary-indexing 26.9 s (vs runs 2/3 at 16.5 / 14.5 s)

**Root cause: cold OS file-cache for the 5 184-file corpus directory,
combined with the embed model's full ORT graph-optimisation pass that
runs on every cold backend start.**

Evidence chain:

1. **The embed model has no pre-optimised ORT cache.**
   `models/onnx/gte-multilingual-base/` contains `model_fp16.onnx`
   (628 MB) but **no** `model_fp16.onnx.optimized` companion. Compare
   to `models/onnx/ner/`, `models/splade/naver-splade-v3/`,
   `models/onnx/reranker/`, `models/onnx/citation-scorer/` — all four
   have `.optimized` files. `OnnxSessionCache` log lines confirm those
   four load via "Loading pre-optimized ONNX model … (skipping graph
   optimization)" in **20–517 ms**. The embed model gets no such
   message and runs `EXTENDED_OPT` from scratch.

2. **Embed session creation took 5 954 ms on run 3** (the warmest
   run). Worker.log markers:
   - 12:45:05.978  `embed: creating session — file=model_fp16.onnx, precision=FP16, ep=CUDA, optLevel=EXTENDED_OPT, gpu=true`
   - 12:45:11.932  `Embedding service ready (dimension=768)`
   - That is **~12× the load time** of the next-slowest model with a
     cache (reranker at 517 ms). This 6 s cost is paid on every cold
     backend start. ORT documents this as the offline-mode
     `optimized_model_filepath` SessionOption: persisting the optimised
     graph to disk lets subsequent loads skip the optimisation pass.

3. **The "freeze" on run 1 is the file walker enumerating cold-cache
   files, not the embedding session.** Worker.log (run 3) shows two
   indexing batches: "5 indexed in 204 ms" at 12:45:07, then "5 184
   indexed in 14 628 ms" at 12:45:23. Embedding is **deferred to
   backfill** for the bulk batch (per `IndexingDocumentOps`); primary
   indexing is text-only + Lucene write. Run 1's timeline shows
   `indexed = 5` frozen for the first ~10 s — the seed 5 docs were
   indexed instantly, then the walker spent ~10 s enumerating + stat()-
   ing 5 184 NTFS files on a cold OS page cache, before the bulk batch
   could start.

4. **Why only run 1?** Runs 2 and 3 ran 4 minutes after run 1 with the
   same 5 184 files; OS page cache held them warm. The corpus is at
   `scripts/jseval/tmp/eval-corpora/scifact/` and is **not** wiped by
   `--clean` (only the data dir is). So runs 2/3 inherit the warm
   cache from run 1.

The two costs (embed graph-optimisation + cold-cache file enumeration)
are **parallel** — embed init runs in `ForkJoinPool.commonPool-worker-1`
while text indexing runs on `indexing-loop`. Total wall ≈ max(embed init,
walker) on the cold path; ≈ walker only on warm runs. This is why run
1's delta over runs 2/3 (~10 s) is smaller than the embed init time
alone (~6 s warm, more cold).

### Issue B follow-up — E-J-N7 investigation (2026-04-19)

The Issue B evidence chain above named "embed has no pre-optimised ORT
cache" as the headline cold-start cost driver. **A code-reading
investigation completed 2026-04-19 confirms the root cause and reframes
the scope** — the gap is not unique to embed.

**Root cause: `.optimized` is a side effect of CPU session creation.**
`OnnxSessionCache.createCachedSession` is called only from
`OrtSessionManager.createCpuSession`
(`modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionManager.java:442`).
When the CPU session is created at startup, the cache is written as a
byproduct. When deferred, the cache is never written.

| Encoder | `deferCpuSession` | CPU session at startup | `.optimized` written |
|---|---|---|---|
| NER, SPLADE, reranker, citation | `false` (default) | Eager creation in `OrtSessionManager` constructor (line 99–101) → `OnnxSessionCache` writes cache | **Yes** |
| **embed** (gte-multilingual-base) | `true` when `gpuEnabled` (`OnnxEmbeddingEncoder.java:197`; also `KnowledgeServer.java:620`) | Skipped at startup; `OnnxSessionCache` never called | **No** |

The deferred-CPU pattern was added in tempdoc 358 to fix a 20 GB RAM
crash: XLM-R 250K vocab triggers a memory multiplication chain in ORT
CPU graph optimisation. Disabling the CPU session at startup avoids
that. The side effect is no cache write for embed.

**The GPU session doesn't help — for *any* model.**
`OrtSessionFactory.createGpuSession`
(`modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionFactory.java:62-72`)
calls `env.createSession(modelPath, opts)` directly, **without setting
`optimizedModelFilePath`**. So the GPU code path bypasses the cache
entirely. Other models *appear* to benefit from caching only because
their CPU sessions write the cache as a byproduct, which the *CPU*
session reads back fast on later cold starts. Their GPU sessions still
re-run full graph optimisation on every cold start — that timing just
isn't logged separately, so we never noticed.

**Reframed implication for Issue B:** the cold-start optimisation cost
is **not unique to embed**. *All* GPU sessions pay full graph
optimisation on every cold backend start. Embed's contribution
dominates because (a) its model is the largest (628 MB FP16 vs
50–280 MB for the others) and (b) its 12-layer XLM-R + RoPE
architecture has the most optimisation work to do. The "embed has no
cache" framing was correct but incomplete — the broader truth is "no
GPU session is ever cached, for any model".

**Three conceptual fix paths (none implemented this session):**

| # | Approach | Wins | Caveats |
|---|---|---|---|
| **1** | **Persist GPU-session optimisation to disk.** Modify `OrtSessionFactory.createGpuSession` to set `opts.setOptimizedModelFilePath(...)`; extend `OnnxSessionCache` with an EP-tagged read path (e.g., `model_fp16.onnx.cuda.optimized`). | Eliminates the per-cold-start optimisation cost for *all* GPU sessions, not just embed. Fixes the root design gap. | Largest change: modify `OrtSessionFactory`, extend `OnnxSessionCache` to recognise EP-tagged caches, encode EP/driver version in the sidecar so the cache invalidates across upgrades. |
| 2 | **Build-time `.optimized` generation for embed.** A Gradle task creates a one-shot CPU session for embed (with `BASIC_OPT` to dodge the EXTENDED_OPT cast catastrophe), lets `OnnxSessionCache` write the cache, closes the session. Ships `.optimized` as a build artefact (LFS-tracked). | Pre-built; no first-run cost paid by users on the rare CPU-fallback path. | Helps only when GPU fails. GPU session still re-optimises. Doesn't address the broader gap (#1). |
| 3 | **Eager embed CPU session at `BASIC_OPT`.** Flip `deferCpuSession(false)` for embed and set `cpuOptLevel(BASIC_OPT)`. Existing cache code path runs; no new infrastructure. | Smallest code change. | Risk: must validate `BASIC_OPT` doesn't trigger the 20 GB RAM blowup on XLM-R 250K vocab. Same scope limitation as #2 (CPU fallback only). |

**Recommendation: Option 1.** It addresses the root issue (GPU sessions
paying optimisation cost on every cold start) for the entire pipeline,
not just embed. Options 2 and 3 are scoped to embed and only benefit
the rare CPU-fallback path. **No implementation done this session;
recommendation is for a future session under explicit user direction.**

**This subsection resolves**:
- The Tier 2 open issue "Embed `.optimized` cache mystery" (cause now
  known; fix paths identified).
- The "What was NOT investigated" item about cache rebuild cost — the
  rebuild is the standard ORT EXTENDED_OPT graph-optimisation runtime
  per cold start, ~6 s observed for embed on warm CUDA; we don't need
  to measure it separately because we now know what it is.
- Handoff H-INV-2 payload (was: "investigate why embed has no cache";
  now: "implement Option 1, 2, or 3").

#### Relationship to tempdoc 381 (Model Distribution Architecture)

This investigation does **not** discover new ground — it extends work
already done in tempdoc 381. The findings and the fix recommendation
both sit on top of 381's foundation; reading the two docs together
shows what 381 built, what 381 explicitly left unfixed, and where our
Option 1 slots in.

**What 381 already knew.** 381 § R1 ("FP32 Optimization Time") tabulated
the exact same observation we re-derived from worker.log:

> | Embedding | FP16 | 628 MB | N/A (optimized on GPU, not CPU) |
> | Embedding | FP32 | 1.26 GB | Never optimized (no .opt-meta exists) |

381 § R5 ("FP16 Sniffing Call Sites") confirmed the mechanism:
> "GPU→CPU fallback in `OrtSessionFactory` — uses CUDA provider, not
> CPU EP, doesn't go through `OnnxSessionCache`. Not applicable."

**What 381 explicitly chose not to fix.** 381 § R1 concluded:
> "The pathological case (30-60+ min) is FP16-specific. FP32
> optimization, even if it takes 60-90 s for a 1.26 GB model, is
> acceptable (one-time cost, cached thereafter). ... The optimization
> lifecycle (section D) simplifies: keep the existing `OnnxSessionCache`
> infrastructure as-is, just ensure correct precision reaches it."

381 closed the optimization-cost discussion by deciding only
FP16-on-CPU was pathological. **The blind spot**: "cached thereafter"
holds only for the CPU path; the GPU path isn't cached. On a
benchmarking dev box where `--start-backend --clean` runs multiple
times per session, the per-cold-start GPU optimisation cost accumulates
— this is what we measured in the 3-run re-measurement (embed ortMax
16.5 → 5.7 → 1.0 s across the warm-up curve).

381 also observed our Issue D failure mode and deferred it. Layer 8
critical analysis section:
> "BFCArena OOM errors during concurrent GPU warmup are pre-existing
> (verified: same errors on pre-merge baseline commit `b1d356bd1`)."

So 381 **saw** the ten BFCArena failures we counted today; it just
wasn't in scope for 381 to fix (deferred to 311).

**How 381's infrastructure enables our Option 1.** 381 centralized
session creation through `ModelSessionFactory` (Layer 8a, commit
`0310e8bc6` final form), made `OptLevel` an explicit parameter on
`OnnxSessionCache` (commit `8617914e2`), and introduced `VariantSelection`
+ the install contract so that precision/EP is declared, not inferred.
Our Option 1 ("persist GPU-session optimisation with EP-tagged cache")
is a delta on top of that foundation:

| Option 1 change | Where it goes in 381's architecture |
|---|---|
| Modify GPU session creation to set `opts.setOptimizedModelFilePath(...)` | `OrtSessionFactory.createGpuSession` — a single callsite, reached through 381's factory |
| Extend `OnnxSessionCache` with an EP-tagged read path (`model_fp16.onnx.cuda.optimized`) | `OnnxSessionCache` already takes an explicit `OptLevel` after 381's work — adding an EP tag is the next dimension |
| Encode EP/driver version in the sidecar | Extend 381's existing `mtime:size:ort` sidecar format with an `ep:` field |
| Resolve the correct cache per (model, precision, EP) at session creation | `VariantSelection` already carries precision + EP; the factory already consumes it |

Without 381's Layer 8 work, Option 1 would require untangling
per-encoder session creation first — a much larger scope. With 381
landed, the work is primarily edits to three files (`OrtSessionFactory`,
`OnnxSessionCache`, and a small extension to the sidecar format). This
is why the tempdoc recommends Option 1: it's architecturally right
*and* small because 381 already paid the big structural cost.

**Option 2 also leverages 381.** Build-time `.optimized` generation
would use 381's install contract (`InstallContractIO`) + `VariantSelection`
to know which variants to pre-optimise. The Gradle task would slot into
381's install pipeline (Layer 4) cleanly.

**Option 3 is orthogonal to 381** — it's a one-line flip of
`deferCpuSession(false)` in `OnnxEmbeddingEncoder`, then relies on
381's existing `BASIC_OPT` path derivation to keep the 20 GB RAM
catastrophe at bay. It doesn't touch 381's architecture, but its
benefit is also narrowest (CPU fallback only).

**Bottom line**: our findings are fully compatible with 381 — they
extend it with empirical cold-start cost measurements and an
additional fix path for the ongoing work. The natural handoff is
"Follow-up to 381 Layer 8: GPU-session optimisation persistence" (see
H-INV-2 below).

### Issue D — SPLADE churn drops 4 vs baseline 1

**Root cause: GPU memory fragmentation (BFCArena) causing intermittent
embedding batch failures, amplified by jseval's overly-tight
drop-detection tolerance.**

Evidence chain:

1. **10 BFCArena allocation failures in run 3 worker.log alone.** Each
   reads:
   `Batch embedding failed: ONNX batch embedding failed: Error code -
   ORT_RUNTIME_EXCEPTION - message: Non-zero status code returned while
   running MatMul/Mul node … bfc_arena.cc:358 BFCArena::AllocateRawInternal
   Available memory of <X> is smaller than requested bytes of <Y>`.
   Observed values: available 36–81 MB, requested 73–165 MB.
   **Available is 2-4× smaller than needed → fragmentation, not
   exhaustion.** A cluster of 8 failures in 11 seconds at end of run
   (12:48:16–12:48:27) suggests a GPU-load burst pushed the embed arena
   into an unrecoverable state.

2. **Each failure cascades to SPLADE state.** Combined-enrichment
   backfill processes embed + SPLADE + NER together. When `embed=fail`
   for a 150-doc batch, those docs go to retry; subsequent RMW
   interleavings can momentarily delay SPLADE-status updates relative
   to denominator changes.

3. **jseval `timeline.py:189-198` counts any decrease > 0.01 pp as a
   "drop"**. Coverage is `completed_docs / total_non_chunk_docs`. Both
   counters are computed from a Lucene query (`IndexCountOps:181-225`);
   the denominator grows monotonically as the file walker enqueues new
   docs; the numerator can lag by one snapshot interval if the polling
   lands between the numerator update and the next encode batch. This
   makes small drops nearly inevitable under any non-trivial enrichment
   timing.

4. **Worker-side safety nets prevent actual data loss.**
   `WritePathOps.readModifyWrite` lines 300–345 restore `splade_status`
   from doc-values when a stored-fields read drops it (the SPLADE
   counter-drift fix landed earlier this session) and have a final
   safety-net assigning PENDING when all other paths miss. The drops
   are measurement blips, not coverage regressions.

5. **Run-to-run variance in failure count is non-deterministic.**
   BFCArena fragmentation depends on the exact sequence of allocations;
   GC pauses, Windows scheduler jitter, and concurrent SPLADE arena
   pressure all contribute. 1 / 1 / 1 (baseline) vs 4 / 5 / 2 (today)
   is consistent with non-deterministic GPU-load bursts.

This was initially attributed to ORT arenas being statically sized
without shrinkage (tempdoc 311 open issue). **A subsequent
investigation on 2026-04-19 found all 311 Phase 1-6 fixes are live in
the current codebase** (arena shrinkage enabled, use_device_allocator
on, CUDA graph disabled, per-session semaphore serialising concurrent
`run()` calls). The real cause was different: the tempdoc 358 embed
model swap (Q4 EmbeddingGemma 131 MB → gte-multilingual-base 628 MB)
pushed the arena:model ratio from 15× to 3.3× — tight enough that MLP
intermediate activations fragment the 2048 MB arena during concurrent
enrichment.

**Resolution (2026-04-19)**:

- **E-J-N9** (commit `3b19076eb`) — jseval drop-tolerance raised
  0.01 → 0.5 pp. Eliminates sub-pp measurement-noise drops. Confirmed
  by re-processing today's 2048 MB timelines: 4 → 2, 5 → 2, 2 → 1.
- **E-J-N8** (commit `3af6773cc`) — `DEFAULT_GPU_MEM_MB` raised
  2048 → 3072 MB (both encoder fallback and config-layer default).
  A/B validated via 3-run jseval scifact re-measurement same session,
  same day:

  | Metric | Pre (2048) | Post (3072) | Δ |
  |---|---|---|---|
  | Total wall (s) | 220.5 | **197.1** | **−10.6 %** |
  | Total docs/s | 23.5 | **26.3** | **+11.9 %** |
  | 3-run CV | 4.3 % | **1.6 %** | stability improved |
  | BFCArena failures/run | 10 | **0 / 0 / 0** | **eliminated** |
  | `splade_churn_drops` | 4 / 5 / 2 | **0 / 0 / 0** | **eliminated** |
  | hybrid nDCG@10 | 0.7536 | **0.7540** | matches baseline exactly |

**Tempdoc 311 § Phase 7** captures the full A/B and documents a
discarded sysmem-fallback hypothesis (a first 3072 MB triple with
sysmem-fallback disabled was slow; re-tested with Windows-default
fallback enabled, ran fast — at this arena size on a 12 GB card,
real VRAM is never exhausted so the WDDM sysmem-backed-memory failure
mode doesn't apply).

**Issue D is resolved.** BFCArena fragmentation failures + churn-drop
measurement noise are both gone. The per-session semaphore relaxation
discussed in 311 Phase 6 remains open but the incentive to fix it is
reduced, since concurrent inference no longer causes fragmentation at
the new arena size.

### Issue A+E — Within-session CV 4.37 % (vs baseline's 1.2 %); cross-session median +14.6 %

**Root cause: cold-start dominates run 1; BFCArena failures contribute
non-deterministic per-run variance; cross-day median delta is
environmental and not attributable from in-codebase evidence alone.**

Evidence chain:

1. **Today's CV is dominated by the run-1 cold-start penalty.**
   Today: 23.0 / 23.5 / 25.0 docs/s; CV 4.37 %. Excluding run 1:
   range = 1.5, CV ≈ 3 % — much closer to baseline's 1.2 %. The bulk
   of today's high CV is the run-1 warm-up cost (Issue B).
2. **Yesterday's runs were "uniformly slow"** (251–257 s wall),
   today's are "uniformly fast with one slow outlier" (208–226 s).
   Yesterday's uniformity may reflect a hotter starting state from the
   active workspace (multi-agent session described in 390 Hardware
   Profile); today's spread reflects a true cold start at run 1.
3. **The cross-day median delta (today's 23.5 vs baseline's 20.5) is
   environmental and not pinpointed.** Plausible drivers, in
   decreasing order of suspect:
   a. Windows services state — `UsoSvc` and `wuauserv` were
      Disabled+Stopped last session and still are (per 390 Tier 1
      dirty-state warning); the services that ran during yesterday's
      baseline may now be quiet.
   b. NVIDIA driver kernel/PTX cache state — persists across JVM
      lifetimes; yesterday's repeated runs may have warmed it for
      today (the `embed.ortMax` 16.5 → 5.7 → 1.0 s progression
      confirms within-day NVIDIA-driver warmth, plausibly carrying
      across days).
   c. Other agents' workload absent today — the 390 Hardware Profile
      explicitly notes parallel sessions; today is solo.
   d. NVMe + Defender scheduling state.

   Without a controlled "rerun yesterday's exact conditions"
   experiment, the cross-day delta cannot be definitively attributed.

4. **Empirical day-to-day CV at N=3 with one cold run ≈ 4.4 %**;
   without the cold run ≈ 3 %. This **answers the Tier 2 "Noise floor
   is unknown" open issue (390)**. Methodology implication: the **5 %
   signal threshold is at the edge** of cross-session noise; raise to
   9 % for single-cold-run comparisons or run N ≥ 5 with the first run
   discarded as warmup (matching the methodology's Gradle warm-up
   rule).

### Meta-lesson: three "wrong hypothesis" diagnoses this session

Three separate investigations landed on diagnoses that were correct at
the surface level but missed the underlying cause. Documented here so
future investigations don't repeat the pattern:

1. **Issue D (BFCArena fragmentation) initial framing** — first diagnosed
   as "ORT arenas statically sized without shrinkage" per 311 open issue.
   A code read found 311 Phase 1-6 fixes were already live. Real cause:
   the tempdoc 358 model swap moved embed from Q4 EmbeddingGemma (131 MB)
   to gte-multilingual-base (628 MB FP16), pushing the arena:model
   ratio below the fragmentation threshold. Fix: arena sizing, not a
   new shrinkage mechanism.

2. **Issue B (cold-start penalty) fix Option 1** — diagnosed as "embed
   model lacks `.optimized` cache; add GPU-session caching". Mechanism
   confirmed and shipped (commit `4fd8e7fb1`). But Experiment 1's
   controlled A/B showed wall-time delta is null: session init runs
   in parallel with text indexing on a non-critical-path thread. The
   cache is architecturally correct but does not move aggregate
   throughput on this pipeline shape.

3. **`ner_total` non-determinism** — two research agents (ORT CUDA
   determinism research + codebase trace) produced plausible-looking
   candidates (SkipLayerNorm strict mode; `signalBus.isUserActive()`
   interrupt). Both hypotheses were wrong. The real bug was in
   jseval's timeline reporting: `ner_total_docs` locked in at the
   first transient `pending=0` moment. Re-processing existing
   timelines showed every run actually reached `ner_done = 7303`.
   The "variance" was purely a measurement artefact.

**Common pattern in all three**: a surface diagnosis that aligned with
the apparent symptom was adopted without checking whether the measurement
itself, or a prior fix's post-condition, was already accounted for. The
correct discipline (per CLAUDE.md "Interrogate Results") is to verify
that the result *means what you think it means* before hypothesising
about the underlying system. Concretely:

- **Before** hypothesising pipeline non-determinism, confirm the metric
  is measured consistently (e.g., max vs first-observed).
- **Before** hypothesising a missing fix, grep for the env var / system
  property / config flag that a prior tempdoc may have already wired in.
- **Before** shipping a "correct" architectural fix, confirm the thing
  being optimised is actually on the critical path.

### What was NOT investigated (would need new experiments)

- Re-running today's experiment after a fresh reboot to isolate
  driver-cache warmth.
- ~~Forcing a rebuild of the embed model `.optimized` cache to measure
  its one-shot creation cost~~ — **resolved 2026-04-19, see Issue B
  follow-up.** The rebuild cost is the standard ORT `EXTENDED_OPT`
  graph-optimisation runtime; ~6 s observed for embed on warm CUDA.
  Fix landed: commit `4fd8e7fb1`.
- Direct BFCArena instrumentation to confirm fragmentation pattern vs
  exhaustion pattern (currently inferred from "Available < Requested"
  but Available being 30-50 % of Requested fits fragmentation, not
  exhaustion). Incentive reduced — Issue D resolved via arena
  right-sizing (commit `3af6773cc`).
- ~~Validate that `BASIC_OPT` on the embed CPU session does NOT trigger
  the 20 GB RAM blowup.~~ **No longer needed.** Option 1 (shipped) uses
  GPU-session caching, which bypasses the CPU-session-creation path
  entirely. Option 3 (which would have needed this validation) is not
  being pursued.
- **New (2026-04-19 after Issue B fix landed)**: driver-version-aware
  cache invalidation. Current implementation encodes `mtime/size/ort/ep`
  in the sidecar but not the CUDA driver version. On a driver upgrade,
  the `.cuda.optimized` cache may become stale in ways not detectable
  by the current sidecar. v2 would add `driver:<version>` to the
  sidecar via `nvidia-smi --query-gpu=driver_version`. Acceptable v1
  trade-off: manual cache delete on driver upgrade.

---

## Theorisation: Potential Resolutions

Disregarding feasibility / implementation order, except for the
**hard constraint** that this PC stays multi-purpose. Items requiring
environment isolation, dedicated bench hardware, or refusing
mismatched-environment comparisons are explicitly **rejected** and kept
in the doc so the constraint stays visible.

Three layers per issue: **targeted fix → architectural change →
ambitious rethink**.

### Resolutions for Issue B (Cold-start penalty)

| Tier | Theoretical change |
|---|---|
| Targeted | Generate the embed `.optimized` cache once and check it in (or LFS-track it) alongside `model_fp16.onnx`. Investigate why `OnnxSessionCache` writes the cache for NER/SPLADE/reranker/citation but not embed — fix the write path so all model families produce the cache. |
| Architectural | Reframe the model artefact: the unit of distribution is not "model.onnx" but "an ORT-EP-specific optimised bundle". Build pipeline runs ORT's offline optimisation pass per (model, EP, opt-level) tuple and ships the optimised graph as the canonical artefact. The non-optimised `.onnx` becomes the source, not the runtime. |
| Architectural | **Explicit warmup protocol.** The worker's `/api/health` returns "process up", a new `/api/ready/warm` returns true only after a single self-test inference per ORT session has succeeded. Benchmarks gate on `warm`, not `up`. Also addresses the CUDA-cold layer (the self-test forces kernel JIT). |
| Ambitious | **Eliminate the cold path from measurement entirely.** The cold start is a one-time production cost (app launch); benchmarks are about steady-state pipeline efficiency. Stop conflating them: jseval grows a `--warmup-runs N` flag that runs and discards N iterations before timed runs. The methodology already does this for Gradle (`--no-daemon` warmup discard) — extend the rule to pipeline. |
| Ambitious | **GPU-resident corpus.** mmap the benchmark corpus into pinned memory at session start; bypass the file system for all subsequent measurements. Removes OS-page-cache as a variance source for any throughput metric. Real production wouldn't do this; benchmarks would. |

### Resolutions for Issue D (BFCArena fragmentation + tight drop-detection tolerance)

| Tier | Theoretical change |
|---|---|
| Targeted | Enable `memory.enable_memory_arena_shrinkage` on the embed CUDA EP (already documented in 311). Trades per-call alloc overhead for elimination of fragmentation failures. Raise jseval `splade_churn_drops` tolerance from 0.01 pp to ~0.5–1 pp (or use absolute counts) to suppress measurement noise. |
| Architectural | Replace independent per-encoder ORT arenas with a **shared CUDA mempool** across embed/SPLADE/NER sessions. ORT's CUDA Mempool Arena (PR #26535) is the upstream path; until Java bindings catch up, route all GPU sessions through a single allocator. Eliminates cross-session fragmentation by definition. |
| Architectural | **Serialise GPU access entirely.** One ORT session active at a time, scheduled by a central GPU coordinator. Trades latency (no parallelism) for determinism (no contention). Combined with the combined-enrichment-pass already landed (tempdoc 334), this might cost less than expected. |
| Ambitious | **Single multi-task encoder.** Replace embed + SPLADE + NER with one transformer pass that emits all three heads simultaneously. Joint dense+sparse encoders exist (BGE-M3 already integrated for combined dense/sparse; some research models add NER heads). Eliminates inter-session contention by making "inter-session" not exist. |
| Ambitious | **Replace the SPLADE-coverage-percentage metric entirely.** The metric conflates "real coverage regression" (correctness bug) with "snapshot timing artefact" (noise). Replace with: (a) a *stuck-pipeline detector* (coverage hasn't progressed for N seconds → alert) and (b) a *RMW conflict counter* sourced from worker-side event logs (counts actual SPLADE_STATUS transitions PENDING→COMPLETED→PENDING, no jseval polling). |

### Resolutions for Issue A+E (Cross-session variance)

| Tier | Theoretical change |
|---|---|
| Targeted | Enforce N ≥ 5 with first-run discarded as warmup for cross-session comparisons. Raise the 390 methodology's 5 % signal threshold to ~9 % until variance source is characterised. (Methodology already says this for Gradle; extend to pipeline.) |
| Targeted | **Capture environment fingerprint per run (informational only).** Every run records: services running/stopped, NVIDIA driver SHA, current power plan, top-N processes by CPU/RAM/GPU, NVMe temperature. Used as **context for explaining variance** when reading runs back, **NOT as a gate on comparability**. |
| Architectural | **Switch from absolute throughput to relative throughput** for cross-commit comparisons. Stop reporting "20.5 docs/s" as if it were a property of the code. Report "this commit is X % faster/slower than its parent commit, run within the same N-minute window, p < 0.05". The history DB already does this for retrieval metrics; extend to throughput. Robust to environment shift because compared runs are always close in time. |
| Architectural | Bake hypothesis testing into comparison logic. `jseval compare` already has Student's t-test for N ≥ 8 (`history.py`); enforce it for N < 8 by requiring more samples instead of degrading to ratio comparison. Refuse to call a delta a "real signal" without a p-value. |
| ~~Ambitious~~ | ~~**Move benchmarks off dev hardware**~~ — **REJECTED** per hard constraint. The PC stays multi-purpose; dedicated bench hardware is out of scope. |
| ~~Ambitious~~ | ~~**Reproducibility-as-a-feature**~~ (refuse to compare runs with mismatched environment fingerprints) — **REJECTED** per hard constraint. The PC's environment cannot be pinned because it is used for normal work. Fingerprints are captured for **context only** (see "informational only" row above), never as gates. |
| Ambitious | **Eliminate hidden non-determinism upstream.** The `ner_total` varies 5 300 → 7 298 across runs because file discovery order isn't deterministic, which changes chunk boundaries, which changes work distribution. Enforce deterministic file enumeration (alphabetical or by path hash). Removes a hidden state-variance source that propagates through the entire pipeline. |
| Ambitious | **Co-located warm-up + warm-down runs.** A single jseval invocation runs (warmup, sample₁, sample₂, …, sampleₙ) back-to-back in the same N-minute window. Cross-window comparisons happen only against the parent commit (relative throughput). This is the practical version of relative-throughput on a multi-purpose PC: the close-in-time samples cancel most environmental variance because the environment didn't have time to drift. |

### Cross-cutting patterns

After eliminating the rejected items, three changes resolve multiple
issues simultaneously:

1. **Explicit warmup protocol** (B + A+E). Bench-mode `--warmup N`
   discards N runs; production-mode `/api/ready/warm` gates on real
   readiness. Eliminates the run-1 outlier as a class.

2. **GPU-resource overhaul** (D + A+E). Shared mempool or serialised
   access removes BFCArena failures, which removes a major
   non-deterministic variance source. Knock-on effect: cross-session
   CV drops because the failure-count distribution narrows.

3. **Co-located warm-up + sample window** (A+E). The practical version
   of relative-throughput when environment isolation is off the table:
   compare close-in-time samples within a single jseval invocation,
   never claim absolute throughput across days.

### The deepest reframing (within the hard constraint)

Pipeline throughput on a multi-purpose dev box is **a measurement of
the dev box's mood as much as the code's behaviour**. The current
methodology treats throughput as a code property and the variance as
noise to suppress. A more honest model: throughput is a joint property
of (code, hardware, OS state, time-of-day, parallel-load); the *trend*
under close-in-time conditions is what's actually a code property.

Two things follow, both compatible with the multi-purpose constraint:

- **What we should measure** is "did this commit move the trend?" not
  "what's the absolute throughput?". Cross-day absolute comparisons are
  not a code claim, only a context note.
- **How we should sample** is "warm-up + N timed in the same window",
  with the parent commit measured in an adjacent same-day window for
  comparison. Days don't compare to days; commits compare to parents.

Quality (nDCG@10) is the inverse: it's deterministic-by-construction
(same docs + same encoder = same scores), so 0.001 variance over 6 runs
is real and any cross-session shift > 0.005 deserves investigation. The
two metric classes need separate methodologies, even though they're
captured by the same jseval run.

---

## JustSearch Opportunity Register

| # | Opportunity | Status | Expected impact | Effort |
|---|---|---|---|---|
| E-J-N1 | **Vite `@vitejs/plugin-react-swc`** (replaces Babel plugin in `modules/ui-web/vite.config.js`) | PENDING | 2–5× frontend transform during `npm run build` and dev HMR | dep swap + `test:unit` |
| E-J-N2 | **llama-server `-fa 1` + `-t 8` + `--mlock`** surfaced through `InferenceConfig` + `LlamaServerOps.java` | PENDING | 20–40 % token/s on Ada; faster cold start with mlock | config key + wire-through + validate first via raw `llama-bench` |
| E-J-N3 | **sccache for Rust/Tauri shell** (`modules/shell/src-tauri`) | PENDING | Big on incremental installer rebuilds (user rebuilds shell multi-times/day) | `winget install mozilla.sccache` + 3-line `.cargo/config.toml` |
| E-J-N4 | **Test parallelism 2 → 3** (TestGateService.maxParallelUsages) | PENDING | up to +33 % `./gradlew test` wall | config flag + RAM-headroom verify |
| E-J-N5 | **Commit `-Xmx4g` gradle.properties change and remove `-XX:+UseLargePages`** | PENDING | Prevents silent-no-op regressions; confirms JVM flag reduction is a null result | 2-line edit + single E-J-0a run |
| E-J-N6 | **Promote hybrid 0.7540 to search-quality register** (if legs + CE match canonical methodology) | PENDING | Register accuracy | handoff H-SQ-1 |
| E-J-N7 | ~~**Resolve embed cold-start optimisation cost** (Issue B follow-up)~~ **MECHANISM SHIPPED, WALL-TIME IMPACT NULL** — Option 1 landed in commit `4fd8e7fb1`. `OrtSessionFactory.createGpuSession` routes through `OnnxSessionCache.createCachedGpuSession`; GPU sessions write `<model>.cuda.optimized` + `.cuda.opt-meta` sidecar via `setOptimizedModelFilePath`. Subsequent cold starts load with `NO_OPT`. | **MECHANISM COMPLETED; AGGREGATE NULL** | Per-model cold-start init: 909-2692 ms → 338-1732 ms (~50% reduction, validated in worker.log). **But Experiment 1 controlled within-session A/B (warm vs cold cache triples) shows wall-time delta of −0.2 s — null.** Reason: GPU session creation runs in parallel with text indexing; the ~4 s saving is not on the critical path (wall time is bottlenecked by ~165 s of GPU inference + ~30 s indexing overhead). The cache fix is architecturally correct but does not move aggregate throughput on this pipeline shape. Quality preserved within ±0.003 nDCG@10. | 3 files (`OrtSessionFactory`, `OnnxSessionCache`, `OnnxSessionCacheTest`). |
| E-J-N8 | ~~**Enable `memory.enable_memory_arena_shrinkage`**~~ **REFRAMED + DONE 2026-04-19, commit `3af6773cc`** — shrinkage was already enabled (311 Phase 1); the real gap was arena sizing post-358 model swap. Fix: `DEFAULT_GPU_MEM_MB` raised 2048 → 3072 MB | **COMPLETED** | A/B: BFCArena failures 10/run → 0; churn_drops 4 → 0; wall −10.6 %; throughput +11.9 %; quality matches baseline exactly (hybrid 0.7540) | See 311 § Phase 7 for the full A/B |
| E-J-N9 | ~~**Raise jseval `splade_churn_drops` tolerance** from 0.01 pp to 0.5–1 pp (Issue D targeted fix)~~ — **DONE 2026-04-19, commit `3b19076eb`** | **COMPLETED** | Suppresses measurement noise from sub-pp lag between numerator/denominator updates; existing test_splade_churn (10 pp drop) preserved + 2 new regression tests added | `CHURN_DROP_TOLERANCE_PP = 0.5` constant added at `scripts/jseval/jseval/timeline.py` |
| E-J-N10 | ~~**Add `jseval --warmup N` flag**~~ **DONE 2026-04-19, commit `565e27664`** — each warmup iteration gets its own backend lifecycle (true cold path exercise); artifacts land in `<out>/_warmup_<N>/`; stdout summary/JSON suppressed for warmups so consumers see only the timed run. | **COMPLETED** | Enables the "co-located warmup + sample window" pattern for methodology-compliant cross-commit comparison on the multi-purpose PC. Unused on this session yet — first use in Validation Workflow Batch F. | `scripts/jseval/jseval/cli.py` (+ 2 tests in `test_cli.py`). |
| E-J-N11 | ~~**Capture environment fingerprint per run**~~ **DONE 2026-04-19, commit `565e27664`** — probes NVIDIA driver/temp/util/mem/clocks (nvidia-smi), active power plan (powercfg), curated Windows services (sc query), top-5 CPU-consuming processes (powershell Get-Process). All probes best-effort; missing tools → null fields. | **COMPLETED** | Per-run machine state in `summary.json → env_fingerprint`. **Informational only** — never a comparability gate. Provides post-hoc context for variance investigation. | `scripts/jseval/jseval/env_fingerprint.py` (new, 193 LOC) + wire-through in `run.py` + 6 tests in `test_env_fingerprint.py`. |
| E-J-N12 | ~~**Deterministic file enumeration**~~ **PARTIAL FIX, HYPOTHESIS NOT FULLY VALIDATED** (commit `e8ec00295`) — `SyncDirectoryOps.walkAndEnqueueMissingFiles` collects + sorts + enqueues. Validated against Experiment 1 (6 runs): `ner_total` = [7303, 7303, 6300, 7303, 7303, 5500]. 4 of 6 runs hit exactly 7303 (clear improvement vs every-run-different baseline), but 2 outliers remain. **Decision rule (spread ≤ 50) FAILS** — file-discovery ordering is one source of `ner_total` variance but not the only one. | **PARTIAL** | File-ordering source eliminated; remaining variance source unidentified. Candidates for follow-up: concurrent NER batch processing, CUDA kernel non-determinism, chunking-batch boundary interactions. | `SyncDirectoryOps.java` (+31/−12 LOC). Existing tests green. |

---

## JustSearch Experiments

Every experiment uses `jseval` primitives (`suite_stats.run_suite`,
`diff_gate`, `history.append_run`, `compare`, `trend`). Quiescence +
fingerprint per the 390 methodology section. No custom harness.

| # | Experiment | Primitive | Decision rule |
|---|---|---|---|
| **E-J-0a** | Gradle cold clean-build wall, N=5 (worst case, anchor for OS/JVM changes) | `./gradlew.bat --stop && ./gradlew.bat --no-daemon --no-build-cache --no-configuration-cache clean build -x test --profile --scan` × 6 (1 warmup + 5 timed) | Median + IQR. Signal: Δmedian > 5 % with non-overlapping IQRs. Complete `build/reports/profile/` retained per run. Build-scan URL captured. |
| **E-J-0b** | Warm-daemon incremental build, N=5 (typical inner loop) | After E-J-0a warmup, touch `modules/ui/src/main/java/.../HeadlessApp.java`, then `./gradlew.bat build -x test --profile` × 5 | Same signal rule. Used as the anchor for JVM flag tweaks (E-J-N5) and compile-toolchain changes. |
| **E-J-0c** | Config-cache on, N=5 (typical real-world default) | `./gradlew.bat --stop && ./gradlew.bat build -x test --profile` (1 warmup to populate cache) × 5 | Used as the anchor for configuration-phase tweaks. |
| **E-J-1** | jseval pipeline throughput (captured as baseline above) | `python -m jseval run --dataset scifact --modes lexical,hybrid --pipeline --start-backend --clean --timeline … --history-db …` × 3 | **Already captured.** Median 20.5 docs/s, nDCG@10 hybrid 0.754. Re-run on any JustSearch-side change that touches the pipeline. |
| **E-J-N1** | Vite SWC plugin swap | `cd modules/ui-web && npm run build` × 5 before/after + `npm run test:unit:run` + `npm run dev:mock` first-HMR sample | Land if `npm run build` Δ > 20 % AND `test:unit` green AND no visible HMR regression. Scope: `modules/ui-web` only — skip B1. |
| **E-J-N2** | llama-server `-fa` / `-t` / `--mlock` | (1) Raw `llama-bench -fa 0 -t 8 -r 5` vs `-fa 1 -t 8 -r 5` on `Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf`. (2) Implement flags in `InferenceConfig`. (3) `python -m jseval llm-bench` × 3 before/after. | Land if raw llama-bench tg Δ > 10 % AND JustSearch end-to-end tg is within ±5 % of raw (flag actually propagated). |
| **E-J-N3** | sccache for Rust/Tauri | `cargo clean ; cargo build --release` × 3 cache-cold + × 3 cache-warm + `sccache --show-stats` | Land if cache-warm Δ > 30 % vs cache-cold AND rebuild frequency ≥ 1/day (already confirmed by user). |
| **E-J-N4** | Test parallelism 2 → 3 | `./gradlew.bat test -PtestParallelism=3 --profile` × 6 (1 warmup + 5 timed) | Land if `test` wall Δ > 10 % AND peak committed RAM < 28 GB AND no new flakes. |
| **E-J-N5** | `-XX:+UseLargePages` null experiment | E-J-0a with flag present vs absent | Expected Δ < 2 % → land the flag removal as cleanup. If Δ ≥ 2 % (unexpected), investigate. |
| **E-J-N6** | ORT inference perf baseline | `onnxruntime_perf_test.exe -e cuda -I -r 100 -t 30` against `models/onnx/bge-m3/model.onnx`, `models/splade/model.onnx`, `models/onnx/reranker/model.onnx` | Establish curve before any ORT-session or execution-provider tweak from tempdoc 311 follow-ups. |
| **E-J-N7** | Embed `.optimized` cache one-shot generation | Run with cache absent → measure cold init wall; generate cache; re-run × 3 → measure warm init wall | Expected: warm init drops from ~6 s to <500 ms. If confirmed, land the cache. |
| **E-J-N8** | BFCArena shrinkage A/B | `python -m jseval run … --pipeline` × 3 with shrinkage off vs × 3 with shrinkage on | Land if BFCArena failure count drops to ≤ baseline (1 in 3 runs) AND total wall Δ > −5 % (i.e., shrinkage cost is acceptable). |
| **E-J-N10** | jseval `--warmup N` flag effect on CV | × 6 runs with no warmup vs × 6 runs with `--warmup 1` | Expected: CV drops from ~4.4 % to ≤ 1.5 %. Land the flag if confirmed. |

### JustSearch-relevant Validation Workflow batches (from 390)

The Validation Workflow in 390 sequences these batches; the JustSearch
agent owns batches B / E / F / G:

| # | Batch | Contents | Pre-baseline | Post-baseline |
|---|---|---|---|---|
| **B** | JVM flag cleanup — null experiment | E-J-N5: remove `-XX:+UseLargePages` from `gradle.properties` | E-J-0a × 5 | E-J-0a × 5 |
| **E** | ORT inference diagnostic curve | E-J-N6: Python probe against production ONNX models | — | (capture only, no A/B) |
| **F** | Project-scoped build pipeline — independent changes | E-J-N1 Vite SWC • E-J-N3 sccache • E-J-N4 Test parallelism | micro-bench per item | same |
| **G** | LLM inference flags | E-J-N2: llama-server `-fa 1 -t 8 --mlock` | raw `llama-bench -fa 0 -t 8 -r 5` | raw `llama-bench -fa 1 -t 8 -r 5` + JustSearch `jseval llm-bench` × 3 |

(Whole-PC batches A / C / H / I remain in 390.)

---

## Open Issues (JustSearch-specific subset)

### Tier 1 — load-bearing

- **Correctness fixes — mostly resolved 2026-04-19.** Of the 4
  patches:
  - SPLADE counter-drift fix + regression test → **committed
    `57fa91775`**.
  - `JUSTSEARCH_MODELS_DIR` removal → **committed `47b4ce3c0`**.
  - `.git/config` hookspath unset → not tracked by git (local config
    only); not subject to commit.
  - `gradle.properties` UseLargePages removal → **still uncommitted,
    pending E-J-N5 null experiment** (validate that it's a no-op
    before landing the cleanup).

  Today's tempdoc edits (`docs/tempdocs/390-system-optimization.md`
  modifications + `docs/tempdocs/391-pipeline-throughput-and-variance.md`
  creation + this update) are also still untracked. Same crash-loss
  risk as the SPLADE fix had before its commit. Awaiting user
  authorisation for a `docs(390,391)` commit.

### Tier 2 — significant, affects result quality

- **Noise floor characterisation now partially answered.** Today's
  3-run re-measurement gave: same-SHA day-to-day CV ≈ **4.4 %** at N=3
  with one cold run, ≈ **3 %** without it. The 5 % signal threshold in
  the 390 methodology should be raised to ~9 %, or N≥5 with cold-run
  discard should be enforced for cross-session comparisons. Quality CV
  (0.18–0.31 %) remains tight enough that the existing thresholds are
  fine for nDCG@10 / P@1 / R@10.

- ~~**Embed `.optimized` cache mystery.**~~ **RESOLVED 2026-04-19**
  via E-J-N7 investigation — see Confirmed Root Causes § Issue B
  follow-up. Root cause: `OnnxSessionCache.createCachedSession` is
  called only from `OrtSessionManager.createCpuSession`; the embed
  model's `deferCpuSession(gpuEnabled)` (a deliberate fix from 358 to
  prevent a 20 GB RAM blowup) skips that call. The GPU code path
  (`OrtSessionFactory.createGpuSession`) bypasses the cache for *all*
  models. Three fix paths identified; **Option 1 (persist GPU-session
  optimisation graph)** recommended as the architecturally correct
  solution. Fix implementation pending; investigation closed.

- ~~**BFCArena fragmentation persisting.**~~ **RESOLVED 2026-04-19**
  (commit `3af6773cc`, E-J-N8). Root cause was post-358 model-swap
  arena undersizing, not the (already-enabled) shrinkage gap.
  A/B-validated elimination: 10 failures/run → 0 across 3 runs;
  +11.9 % throughput; quality unchanged. See Confirmed Root Causes
  § Issue D resolution and tempdoc 311 § Phase 7.

### Tier 3 — operational hygiene

- ~~**`ner_total` non-determinism**~~ **RESOLVED 2026-04-19** —
  it was never pipeline non-determinism; it was a **jseval measurement
  bug**. Commit `2a5d153c1` fixed `timeline.py` to use `max(ner_done)`
  across all rows instead of the first-pending-zero snapshot.
  Re-processing all 6 Experiment 1 timelines with the fix: every run
  reports `ner_total_docs = 7303` (std=0). The pipeline was always
  deterministic. Root cause was that NER backfill can transiently drain
  all currently-pending chunks before ingestion finishes creating more
  chunks, producing a `pending=0` window at a partial `ner_done` value;
  jseval's "first row where pending==0" logic locked in that transient
  value. E-J-N12 (file-ordering sort) remains a correct change but was
  not the right lever for this symptom. ORT CUDA determinism research
  (Agent A) is banked for future threshold-sensitive metrics.

- ~~**jseval `splade_churn_drops` tolerance** is too tight (0.01 pp).~~
  **RESOLVED 2026-04-19** via E-J-N9 (commit `3b19076eb`). New
  tolerance `CHURN_DROP_TOLERANCE_PP = 0.5`; 2 regression tests added.

- **Worker.log overwrite on `--clean`.** Each new run overwrites the
  previous `worker.log` because the data dir is wiped, but logs live
  in the data dir. Consequence: investigating run-N anomalies after
  run-N+1 has happened requires re-running, or moving logs out of the
  data dir before the next `--clean`. Operational workaround: copy
  `worker.log` to a result subdir before each new run; structural fix
  would be moving logs outside the data dir.

---

## Handoffs

| Handoff | From → To | Payload | Closure criterion |
|---|---|---|---|
| **H-SQ-1** | JustSearch → search-quality register | scifact hybrid nDCG@10 = 0.7540 on 3 runs (git 58221d5fa + SPLADE fix); confirmed stable at 0.7536 across 3 follow-up runs (6 runs total, all within ±0.005). Legs observed: `cross_encoder + dense`. Comparable=true per summary. | Register agent verifies legs + CE state vs current Phase D methodology; either promotes to Best-known row or rejects with reason. |
| **H-JS-1** | JustSearch → tempdoc 311 | **CLOSED 2026-04-19.** 311 § Phase 7 landed, documenting the post-358 arena undersizing regression + fix. Commit `3af6773cc` raised `DEFAULT_GPU_MEM_MB` 2048 → 3072 MB. Observed 10 BFCArena failures/run at 2048 MB (run 3 of 2026-04-19); post-fix 3-run A/B shows 0/0/0 failures, −10.6 % wall, +11.9 % throughput, quality matching baseline. | — (closed) |
| **H-JS-2** | JustSearch → inference-runtime register | llama-server `-fa` / `-t` / `--mlock` A/B result (E-J-N2). | If it lands, register's F-/D- sections get the tok/s delta + config. |
| **H-INV-1** | JustSearch → tempdoc 311 | **CLOSED 2026-04-19.** Observation merged into 311 § Phase 7 — the 10 failures/run were the symptom; arena right-sizing post-358 was the fix. Not "static arena + no shrinkage" (both already addressed in 311 Phase 1-6) but "arena sized for pre-358 model, under-provisioned for gte-multilingual-base". | — (closed) |
| **H-INV-2** | JustSearch → tempdoc 381 follow-up (+ inference-runtime register) | **CLOSED 2026-04-19** — Option 1 implemented in commit `4fd8e7fb1`. `OrtSessionFactory.createGpuSession` routes through `OnnxSessionCache.createCachedGpuSession` (new method); GPU session uses `opts.setOptimizedModelFilePath(<model>.cuda.optimized)` on first cold start, then loads the pre-optimised graph with `NO_OPT` on subsequent starts. Sidecar `<model>.cuda.opt-meta` records `mtime:X size:Y ort:Z ep:cuda` for invalidation. CUDA cache is distinct from the existing CPU `.optimized` cache (EP-aware graph optimisation). Driver-version invalidation intentionally deferred to v2 (manual cache delete on driver upgrade is acceptable). Validated via jseval 3-run triple: all 4 GPU models show "Loading pre-optimized CUDA-EP" in worker.log with 0.3-1.7 s loads (vs pre-fix 0.9-2.7 s optimisations); quality matches 0.7540 baseline within ±0.005; cumulative session init 7.7 s → 3.8 s per cold start. | — (closed) |
| **H-INV-3** | JustSearch → jseval improvements | **CLOSED 2026-04-19** — all three items landed. (1) `splade_churn_drops` tolerance 0.01 → 0.5 pp (commit `3b19076eb`). (2) `--warmup N` flag with per-iteration backend lifecycle and stdout suppression for warmups (commit `565e27664`). (3) Per-run environment fingerprint (nvidia-smi + powercfg + services + top-N processes) captured into `summary.json → env_fingerprint`; informational only, never a comparability gate (commit `565e27664`). | — (closed) |
| **H-SHARED-1** | shared (carried in 390) | **CLOSED 2026-04-19** — the premise was wrong. `ner_total` variance was a jseval measurement bug, not pipeline non-determinism (see commit `2a5d153c1` and § Correctness Fixes Applied). E-J-N12 (file-ordering sort) remains a correct change but was not the right lever for this specific symptom. Re-processing all 6 Experiment 1 timelines post-fix: every run reports 7303 (std=0). | — (closed) |
| **H-AUDIT-1** | JustSearch → code audit agent | 10 commits landed this session span non-trivial changes to `WritePathOps`, `OrtSessionFactory`, `OnnxSessionCache`, `SyncDirectoryOps`, and jseval. Several items flagged for critical analysis: scope generalization of the counter-drift bug pattern, safety-net misfire risk in `WritePathOps.readModifyWrite`, schema-brittleness of the GPU cache sidecar, test coverage adequacy, and error-swallowing in the new doc-values read path. Documented in `docs/tempdocs/393-code-audit-pipeline-optimization-commits.md`. | Audit agent works through the tiered action items in 393; each item closes with either (a) no-issue-found evidence, (b) a separate fix commit, or (c) explicit accepted-as-designed rationale. |

---

## Next Actions (JustSearch-tagged, ordered)

**Tier A/B/C landed 2026-04-19.** All fix-tier items identified in the
root-cause investigation are shipped. Items below are what remains.

1. ~~**Commit the correctness fixes**~~ — **DONE.** SPLADE counter-drift
   (`57fa91775`), env cleanup (`47b4ce3c0`), jseval tolerance
   (`3b19076eb`), arena right-size (`3af6773cc`), register promotion
   (`cf38da21b`), tempdoc landing (`02a2545d2`).
2. ~~**H-SQ-1**~~ — **DONE** (commit `cf38da21b` promoted scifact hybrid
   0.7540 to `search-quality-register.md → beir/scifact`).
3. ~~**Issue B targeted fix (E-J-N7)**~~ — **DONE 2026-04-19** (commit
   `4fd8e7fb1`, Option 1). `OrtSessionFactory.createGpuSession` routes
   through `OnnxSessionCache.createCachedGpuSession`; GPU sessions write
   `<model>.cuda.optimized` + `.cuda.opt-meta` on first cold start and
   load from cache with `NO_OPT` on subsequent starts. Per-model init
   909-2692 ms → 338-1732 ms. Quality unchanged (hybrid nDCG@10 within
   ±0.005 of the 0.7540 baseline).
4. ~~**Issue D targeted fixes**~~ — **DONE 2026-04-19**
   (commits `3b19076eb`, `3af6773cc`). See 311 § Phase 7.
5. ~~**Methodology refinement**~~ — **DONE 2026-04-19**
   (commit `565e27664`). `jseval --warmup N` + per-run
   `env_fingerprint` in `summary.json`. Enables the "co-located warmup +
   sample window" pattern for cross-commit comparison under the
   multi-purpose-PC constraint.
6. ~~**E-J-N12 deterministic file enumeration**~~ — **DONE 2026-04-19**
   (commit `e8ec00295`). `SyncDirectoryOps.walkAndEnqueueMissingFiles`
   sorts the collected file list before enqueue. Validation pending:
   `ner_total` stability on the next 3-run scifact triple.
7. **Validation Workflow batches** (B / E / F / G — see the 390
   sequence) — **REMAINING.** These are independent optimization
   experiments (E-J-N1 Vite SWC, E-J-N2 llama-server flags, E-J-N3
   sccache, E-J-N4 test parallelism, E-J-N5 UseLargePages null,
   E-J-N6 ORT baseline curve). Not blockers for any identified issue.
   Best tackled in a dedicated session.
8. **Follow-up validation** — mostly superseded by Experiment 1
   findings. Remaining value: validate `--warmup 1` actually reduces
   within-triple CV as claimed (Experiment 2 in the earlier session
   plan). Not urgent; run when convenient. `ner_total` stability item
   removed: post-fix re-processing shows it was always deterministic.
9. **Code audit of the 10 landed commits** — H-AUDIT-1 handoff,
   tempdoc 393. Scope-generalization of the counter-drift pattern
   (HIGH priority: are other `stored:false,docValues:true` fields at
   risk?) + safety-net misfire risk + test coverage expansion.

---

## Session Artifacts

- `tmp/jseval-baseline-3x/run{1,2,3}/` — 2026-04-18 evening 3-run
  baseline; result.json, timeline.tsv, progress.log; shared
  `history.db`.
- `tmp/jseval-current-2026-04-19/run{1,2,3}/` — 2026-04-19 follow-up
  3-run re-measurement; same shape.
- `tmp/jseval-baseline/` — earlier single-shot pre-3x baseline (initial
  diagnostic + post-fix verification). Can be deleted if history.db is
  retained.
- `tmp/headless-eval-data/logs/worker.log` — latest run only (run 3 of
  2026-04-19 at the time this doc was written); overwritten on each
  `--clean`.
- **Commits landed 2026-04-19** (Tier A + B + C):
  - `57fa91775` `fix(391): SPLADE counter drift — preserve splade_status
    doc-value across RMW` — covers `WritePathOps.java` +
    `BatchUpdateIntegrationTest.java`.
  - `47b4ce3c0` `chore(391): remove stale JUSTSEARCH_MODELS_DIR
    override, allow Skill(update-config)` — covers
    `.claude/settings.local.json`.
  - `3b19076eb` `fix(391): jseval splade_churn_drops — raise tolerance
    from 0.01 pp to 0.5 pp` — covers
    `scripts/jseval/jseval/timeline.py` +
    `scripts/jseval/tests/test_timeline.py` (1 constant added, 2
    regression tests added).
  - `3af6773cc` `fix(311,391): raise embed GPU arena limit from 2048
    → 3072 MB for gte-multilingual-base` — covers
    `OnnxEmbeddingEncoder.java`, `ResolvedConfigBuilder.java`,
    `EnvRegistry.java`, `OnnxEmbeddingEncoderTest.java`,
    `ResolvedConfigBuilderTest.java`. Closes Issue D.
  - `cf38da21b` `docs(391): promote scifact hybrid 0.7540 to
    search-quality register (H-SQ-1)` — covers the register +
    regenerated skills.
  - `02a2545d2` `docs(311,390,391): land pipeline-optimization
    tempdocs from 2026-04-19 session` — landed the tempdoc trio.
  - `4fd8e7fb1` `fix(391): persist CUDA-EP graph optimization across
    cold backend starts (Issue B Option 1)` — covers
    `OrtSessionFactory.java`, `OnnxSessionCache.java` (+147 LOC for
    the new GPU cache path), and `OnnxSessionCacheTest.java` (new,
    8 tests for path derivation + sidecar round-trip).
  - `565e27664` `feat(391): jseval --warmup N flag + per-run
    environment fingerprint (E-J-N10, E-J-N11)` — covers
    `scripts/jseval/jseval/cli.py` (+147 LOC for warmup loop +
    per-iteration helper), `scripts/jseval/jseval/run.py` (+wire
    fingerprint into summary), new `env_fingerprint.py` (193 LOC),
    and 8 new tests across `test_cli.py` + `test_env_fingerprint.py`.
  - `e8ec00295` `fix(391): deterministic file enumeration in
    syncDirectory walk (E-J-N12)` — covers `SyncDirectoryOps.java`
    (+31/−12 LOC). Existing indexer-worker + worker-services tests
    green.
- **Validation runs for Issue B Option 1** (2026-04-19):
  - `tmp/jseval-gpu-cache-probe/` — cold probe run; confirmed all 4
    GPU models write `.cuda.optimized` + `.cuda.opt-meta` under
    `models/onnx/` + `models/splade/`.
  - `tmp/jseval-gpu-cache-warm/run{1,2,3}/` — first warm-cache triple
    (mid-session); worker.log shows 4 cache hits per run (0 misses).
    Per-model load 0.3-1.7 s (vs pre-fix 0.9-2.7 s optimisation).
    Cumulative GPU session init 7.7 s → 3.8 s per cold start.
    Hybrid nDCG@10: 0.7495 / 0.7537 / 0.7499 (median 0.7499, within
    ±0.005 of 0.7540 baseline). Wall-time 251/250/287 s — noisy
    cross-session vs 391's arena-3072 triple.
  - **Experiment 1 (controlled within-session A/B, 2026-04-19 evening):**
    - `tmp/jseval-exp1-warm/run{1,2,3}/` — warm cache triple, 199.2 /
      199.2 / 203.2 s (median **199.2 s**, CV 1.12%).
    - `tmp/jseval-exp1-cold/run{1,2,3}/` — cold cache triple (caches
      deleted before EACH run, so all 3 paid the optimise+write cost),
      199.7 / 197.0 / 199.1 s (median **199.1 s**, CV 0.70%).
    - **Cache contribution to wall time: −0.2 s (null).** Mechanism
      verified (cold-run-3 worker.log: 4 "Optimizing CUDA-EP" → 4
      "complete in Nms"; no "Loading pre-optimized"). The ~4 s
      per-cold-start session-init saving does NOT surface in wall
      time because session creation runs in parallel
      (`ForkJoinPool.commonPool`) with text indexing
      (`indexing-loop`) and is not on the critical path. Encoder
      profiling shows wall time is bottlenecked by GPU inference
      (~165 s) + indexing overhead (~30 s), not session init.
    - Quality (across all 6 Experiment 1 runs): hybrid nDCG@10
      0.7523–0.7544 (std 0.0008, within ±0.003). Lexical 0.6610
      perfectly deterministic. Even better than the documented
      0.31% CV.
    - `ner_total` across 6 runs: [7303, 7303, 6300, 7303, 7303, 5500].
      4 of 6 identical at 7303 (E-J-N12 partial validation), 2
      outliers at 6300 and 5500. **Decision rule (spread ≤ 50)
      FAILS** — file-discovery ordering accounts for some variance
      but is not the only source. Other candidates: concurrent NER
      batch processing, ML kernel non-determinism (CUDA atomics in
      attention), chunking-batch boundary interactions.
    - All 6 `summary.json` files contain `env_fingerprint`
      (E-J-N11 validated in production).
- **A/B measurement runs (2026-04-19)**:
  - `tmp/jseval-arena-3072/run{1,2,3}/` — first 3072 MB triple with
    sysmem-fallback **disabled** (user-applied OS setting). Slow
    median 255.5 s; investigation hypothesis later discarded in favour
    of the fallback-enabled configuration.
  - `tmp/jseval-arena-3072-no-sysmem/run1/` — misnamed folder
    (actually fallback-enabled); single validation run, 192.8 s.
  - `tmp/jseval-arena-3072-sysmem-on/run{1,2,3}/` — canonical
    post-fix triple: 193 / 199 / 197 s, 0 BFCArena failures across
    all three, hybrid nDCG@10 median 0.7540. This is the baseline
    for future comparisons.
- `docs/tempdocs/391-pipeline-throughput-and-variance.md` — this file
  (still uncommitted; awaiting `docs(390,391,311)` authorisation).
- `docs/tempdocs/390-system-optimization.md` — companion doc (whole-PC
  scope; modifications also still uncommitted).
- `docs/tempdocs/311-gpu-memory-partitioning.md` — § Phase 7 added
  (also uncommitted).

---

## Sources

- SPLADE counter drift code trail: `WritePathOps.java:254-326` (RMW
  logic), `IndexCountOps.java:181-225` (count query that filters
  non-chunk + requires known status), `IndexingDocumentOps.java:225`
  (primary-indexing SPLADE_STATUS=PENDING),
  `SpladeBackfillOps.java:49-55` (PENDING-queue query),
  `fields.v1.json:456-462` (splade_status stored:false, docValues:true).
- Pre-existing baselines from prior tempdocs: 311 (ORT session
  lifecycle + BFCArena), 312 (primary indexing throughput), 322 (BGE-M3
  VRAM), 334 (single-pass enrichment + Phase 15 baseline 296.3 s),
  343 Phase D (current canonical search-quality baseline).
- ORT optimised-graph cache mechanism: ORT docs on
  `optimized_model_filepath` SessionOption + offline-mode graph
  optimisations (corroborated externally during 2026-04-19
  investigation).
- Existing `jseval` framework: `scripts/jseval/jseval/{suite_stats,
  history, diff_gate, compare_runs, provenance, nsys, comparability,
  timeline, gradle_bench}.py`, plus `python -m jseval --help`.
- jseval drop-detection: `scripts/jseval/jseval/timeline.py:189-198`
  (now uses `CHURN_DROP_TOLERANCE_PP = 0.5` constant after commit
  `3b19076eb`).
- Worker.log evidence (run 3, 2026-04-19, lifecycle window
  12:45:04 – 12:48:30): embed session creation 5 954 ms, 10 BFCArena
  failures, splade encode warmup 4 413 ms first / 158 ms steady-state.
- **E-J-N7 investigation code paths (2026-04-19)**:
  - `modules/ort-common/src/main/java/io/justsearch/ort/OnnxSessionCache.java`
    — graph-optimisation cache mechanism (read/write paths).
  - `modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionManager.java:99-101,442`
    — eager vs deferred CPU session creation; the only call site of
    `OnnxSessionCache.createCachedSession`.
  - `modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionFactory.java:62-72`
    — GPU session creation; bypasses `OnnxSessionCache` entirely.
  - `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java:197`
    — `.deferCpuSession(gpuEnabled)` (the 358 fix that triggers the
    cache gap).
  - `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:620`
    — second `deferCpuSession` callsite for embed.
