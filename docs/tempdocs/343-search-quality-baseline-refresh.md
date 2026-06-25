---
title: "343: Post-326 Search Quality Baseline Refresh and Chunk Branch Diagnosis"
type: tempdoc
status: done
created: 2026-03-23
updated: 2026-03-28
depends-on: [309, 313, 326]
informed-by: [280, 306, 316, 334, 335]
---

> NOTE: Noncanonical working tempdoc. Verify behavior claims against canonical docs,
> code, and tests before promotion.

# 343: Post-326 Search Quality Baseline Refresh and Chunk Branch Diagnosis

## Purpose

Establish accurate baselines for the current search pipeline (post-326 entity boost +
hybrid title boost bug fix, EmbeddingGemma-300M default), then diagnose whether
chunk-aware merge helps, hurts, or is neutral on corpora where it actually fires.

## Background

All register baselines (309 §35–§43, measured 2026-03-18 to 2026-03-20) were stale:
tempdoc 326 landed after all measurements, adding entity-boosted BM25 and fixing a
production bug where the 2-leg hybrid path (`searchTextWithFilter`) bypassed ALL
multi-field boosts. EmbeddingGemma-300M replaced nomic as the default embedding
model but had zero pipeline baselines (register Q-005).

---

## Completed Work

### Phase 0: jseval Infrastructure — DONE

Enabled jseval to toggle search config and capture it in run artifacts:

- `SEARCH_CHUNK_AWARE_ENABLED` promoted from `ConfigKey` to `EnvRegistry` (env-var-overridable)
- `SearchConfig` proto message added to `StatusResponse` (10 fields, exposed via `/api/status`)
- `run_config.py` `_ENV_MAP` extended with search pipeline keys
- `summary.json` now captures `search_config` (snapshot from `/api/status`) and `env_overrides`

Canonicalized: env vars doc, API contract map, jseval reference updated.

### Phase 1: SciFact Baseline — DONE

Latest run: `tmp/eval-results/20260327T093230_scifact/` (git 68782549f)
Config: EmbeddingGemma-300M, SPLADE-v3, GTE-ModernBERT CE, BM25-dom CC

| Mode | nDCG@10 | P@1 | R@10 | Legs |
|------|---------|-----|------|------|
| lexical | 0.661 | 0.537 | 0.779 | bm25 |
| splade | 0.501 | 0.397 | 0.622 | splade |
| bm25_splade | 0.668 | 0.540 | 0.799 | bm25+splade |
| full | 0.714 | 0.587 | 0.839 | bm25+splade (dense missing, CE off) |

Findings: dense leg broken (→ F-012), SPLADE-v3 20% below BGE-M3 sparse (→ F-013).
Register updated.

**Stability verified (2026-03-27):** Post-360 reranker Worker migration (GPU-enabled,
`padAttentionMask` refactor) — all modes identical within noise across 9 comparable
runs (full mode range: 0.705–0.723). No regression.

### Phase 1: EnronQA Baseline — DONE

Latest run: `tmp/eval-results/20260327T094938_mixed_enron-qa/` (git 68782549f)

| Mode | nDCG@10 | P@1 | R@10 | Legs |
|------|---------|-----|------|------|
| lexical | 0.827 | 0.717 | 0.927 | bm25+chunk_merge |
| bm25_splade | 0.813 | 0.700 | 0.913 | bm25+splade+chunk_merge |
| full | 0.822 | 0.703 | 0.923 | bm25+splade+chunk_merge (dense missing, CE off) |

**Correction (2026-03-27):** All jseval `full` mode runs had `crossEncoderEnabled:
false` — CE never ran. The full vs bm25_splade delta is CC3 fusion overhead, not
CE impact. F-015 retracted. `--ce` flag added to jseval for future CE measurement.

Register updated.

### Phase 2: Chunk Branch Diagnosis — DONE

**2.1 — isShortCorpus() verification:** SciFact: all 300 queries `SKIPPED_SHORT_CORPUS`.
EnronQA: all 300 queries `APPLIED`. Gate is working correctly.

**2.2 — Chunk ON vs OFF (EnronQA):**

| Mode | Chunk ON | Chunk OFF | Delta | p-value | Sig? |
|------|---------|----------|-------|---------|------|
| lexical | 0.8254 | 0.8119 | **+1.3%** | **0.040** | **Yes** |
| bm25_splade | 0.8127 | 0.8015 | +1.4% | 0.191 | No |

Chunk merge is a net positive on long-doc corpora. No weight adjustment needed.
Registered as F-014; Q-006 closed.

### Investigation: F-012 — Dense Leg Tracking Bug (CORRECTED 2026-03-27)

**Original claim:** Dense broken for non-BGE-M3 configs. **Correction:** Dense
WAS working with gte-multilingual-base all along. Two issues conflated:
1. EmbeddingGemma FP16 NaN (head_dim=256) — resolved by 358 model change
2. `buildPipelineExecution()` never emitted `dense: executed` on success — only
   reported `dense: skipped` on failure. jseval saw no `dense` component and
   reported `requested_dense_but_not_observed`.

**Fix:** Added `dense: executed` component status when `pipelineConfig.denseEnabled()`
and `!vectorBlocked && !hybridFallback`. Verified: live query shows `vector_rank`
scores, jseval reports `observed: ['dense', 'splade']`.

**Impact:** All `full` mode baselines upgraded from C to A. The full vs bm25_splade
gap is real dense contribution (+6.9% on SciFact, +1.1% on EnronQA, +14.7% on
MIRACL/de). Dense provides the largest uplift on multilingual content.

### Investigation: F-013 — SPLADE-v3 Sparse Quality

Not a regression. SPLADE-v3 sparse quality IS 0.501 on SciFact (confirmed
deterministic across 3 runs at 2 commits). BGE-M3 sparse achieves 0.627 — a 20%
model-level gap. In practical modes (bm25_splade), gap narrows to 1.6%.

### Eval Observability Fixes — DONE (2026-03-27)

Critical analysis of the verification runs revealed three observability bugs in
jseval and the search backend. All fixed and verified:

1. **Comparability false negatives:** `ann_proof` checked dense vector evidence
   on ALL modes, including `lexical` (pure BM25) and `bm25_splade` (no dense).
   Fixed: `compute_ann_proof` is now mode-aware — each mode only checks evidence
   rates for its active retrieval legs. `lexical` returns `NOT_APPLICABLE`.

2. **Comparability false positive on `full` mode:** `comparable=True` despite
   `requested_dense_but_not_observed` (F-012). Fixed: `compute_ann_proof` now
   checks `pipeline_tracking.mismatch_reasons` — missing legs fail the proof.

3. **Per-query `tookMs` always 0:** `HybridFusionUtils` fusion methods create
   `new SearchResult(..., 0)` discarding per-leg timing. `SearchOrchestrator`
   passed `result.tookMs()` to the gRPC response — always 0 after fusion.
   Fixed: `toGrpcResponseBuilder` now takes the orchestrator-level `retrievalMs`
   (computed before fusion). Verified: `latency_stats` now matches
   `stage_timing_stats.retrieval_ms` (lexical 1.5ms, bm25_splade 12.4ms,
   full 18.1ms).

Files changed: `ann_proof.py`, `run.py`, `SearchOrchestrator.java`.
Verification run: `tmp/eval-results/20260327T102824_scifact/`.

---

### Phase 1: MIRACL/de Baseline — DONE

Run: `tmp/eval-results/20260327T114635_mixed_miracl-de-2k/` (git 2681da09b)

| Mode | nDCG@10 | P@1 | R@10 | Legs |
|------|---------|-----|------|------|
| lexical | 0.513 | 0.328 | 0.749 | bm25 |
| splade | 0.485 | 0.311 | 0.684 | splade |
| bm25_splade | 0.540 | 0.354 | 0.780 | bm25+splade |
| full | 0.619 | 0.403 | 0.875 | bm25+splade (dense broken, CE off) |

Pipeline: 71s total (3104 docs). VRAM peak: 2345 MB.

**Key finding:** SPLADE-v3 (English-only) is catastrophic on German: -27.5% vs
BGE-M3 sparse (0.485 vs 0.669). This is the strongest validation for the
multilingual SPLADE upgrade from tempdoc 358.

### Item 1.4: Register Delta Comparison — DONE

| Dataset | Mode | splade-v3+gemma | bge-m3 | Delta |
|---------|------|----------------|--------|-------|
| SciFact | lexical | 0.661 | 0.661 | +0.0% |
| SciFact | splade | 0.501 | 0.627 | **-20.1%** (F-013) |
| SciFact | bm25_splade | 0.668 | 0.679 | -1.6% |
| EnronQA | lexical | 0.827 | 0.810 | +2.1% |
| EnronQA | bm25_splade | 0.813 | 0.830 | -2.0% |
| MIRACL/de | lexical | 0.513 | 0.511 | +0.3% |
| MIRACL/de | splade | 0.485 | 0.669 | **-27.5%** |
| MIRACL/de | bm25_splade | 0.540 | 0.553 | -2.4% |

BM25 is identical across encoder configs (model-independent). SPLADE-v3
regressions are the known model quality gap (F-013), worse on German (-27.5%
vs -20.1% on English). In practical modes (bm25_splade), BM25 dominates and
the gap is <3% on all corpora.

`full` mode comparisons omitted — not comparable (different dense/CE status,
different CC weights).

### Item 3.5: CC Weight Evaluation — DONE (2026-03-27)

F-012 corrected (dense was working all along). Ran all 3 corpora with balanced
CC weights (0.34/0.33/0.33) via jseval YAML config env override.

| Dataset | Mode | BM25-dom | Balanced | Delta | Better |
|---------|------|---------|---------|-------|--------|
| SciFact | full | 0.714 | **0.734** | **+2.8%** | balanced |
| EnronQA | full | **0.822** | 0.711 | **-13.5%** | BM25-dom |
| MIRACL/de | full | 0.619 | **0.686** | **+10.8%** | balanced |

bm25_splade mode unaffected (2-way fusion doesn't use 3-way CC weights).

**Conclusion:** Same pattern as BGE-M3 (register Key Comparisons). Balanced wins
on academic/multilingual, BM25-dom wins on email. No single default is correct.
**Do NOT change `ResolvedConfigBuilder` defaults.** Implement corpus-adaptive
weight selection (FW-001) via `CorpusProfile` regime classification.

Runs: `20260327T121226_scifact`, `20260327T125206_mixed_enron-qa`,
`20260327T125454_mixed_miracl-de-2k`.

### Reranker Validation: Phase A.1 — CE Baseline (current reranker)

SciFact CE baseline with gte-reranker-modernbert-base (INT8, CPU-only):

| Mode | CE off | CE on | Delta | CE latency |
|------|--------|-------|-------|-----------|
| bm25_splade | 0.668 | 0.668 | 0.0% | ~1.6s/query |
| full | 0.714 | 0.716 | +0.3% | ~2.4s/query |

**CE is neutral on SciFact** — consistent with F-006. The 2.4s/query latency is
the main issue: the INT8 CPU-only reranker is far too slow for interactive search
(20 docs × ~120ms/pair on CPU). The GPU FP16 variant (gte-multilingual-reranker-base,
already downloaded) should reduce this to ~5-20ms/query.

EnronQA and MIRACL/de CE baselines deferred — SciFact shows CE is neutral with
current models, and the reranker swap is needed regardless for latency.

Run: `tmp/eval-results/20260327T142928_scifact/`

### Reranker Validation: Phase A.1 — New Reranker (gte-multilingual-reranker-base)

Swapped model: `models/onnx/reranker/` → gte-multilingual-reranker-base (FP16 GPU,
306M, 12L/768d, head_dim=64, 250K vocab, 70+ langs). Old model backed up to
`models/onnx/reranker-modernbert-backup/`.

| Dataset | Mode | No CE | New CE | Delta | CE latency |
|---------|------|-------|--------|-------|-----------|
| SciFact | bm25_splade | 0.668 | **0.684** | **+2.4%** | ~200ms |
| SciFact | full | 0.714 | **0.731** | **+2.4%** | ~200ms |
| EnronQA | bm25_splade | 0.813 | 0.788 | **-3.1%** | ~175ms |
| EnronQA | full | 0.822 | 0.782 | **-4.9%** | ~170ms |
| MIRACL/de | bm25_splade | 0.540 | **0.574** | **+6.3%** | ~170ms |
| MIRACL/de | full | 0.619 | **0.657** | **+6.1%** | ~170ms |

**Verdict: Ship.** Wins on SciFact (+2.4%) and MIRACL/de (+6.3%), hurts on
EnronQA (-3.1 to -4.9%). Latency 12x better than old INT8 CPU model
(175ms vs 2400ms). VRAM lower during enrichment (2.5GB vs 3.5GB).

EnronQA regression reinforces FW-001 (corpus-adaptive CE gating): disable CE
on email-type corpora, enable on academic/multilingual.

Runs: `20260327T144023_scifact`, `20260327T151359_mixed_enron-qa`,
`20260327T151750_mixed_miracl-de-2k`.

## Remaining Model Swaps

| Model | Impact on search nDCG? | Effort | Status |
|-------|----------------------|--------|--------|
| ~~Reranker~~ | Yes (CE) | Drop-in | **DONE — validated** |
| ~~Citation scorer~~ | No (RAG citations) | Drop-in | **DONE — verified** |
| ~~NER~~ | Potentially (entity boost still neutral) | Drop-in | **DONE — validated** |
| ~~Embedding~~ | Yes (dense leg) | Already wired | **Auto-discovered** (gte-multilingual-base) |
| ~~SPLADE~~ | Yes (sparse leg) | **Breaking** — re-index | **DONE — validated** (PRESPARSE conversion, FP16 GPU via build-splade.py) |

### NER Validation: Phase A.2

Swapped: distilbert-NER (EN) → distilbert-base-multilingual-cased-ner-hrl
(10 langs). Old model backed up to `models/onnx/ner-distilbert-en-backup/`.

**NER quality (CoNLL-2003 test):**

| Type | Old (EN) | New (multilingual) | Delta |
|------|---------|-------------------|-------|
| PER | 0.927 | **0.942** | +1.5% |
| ORG | 0.813 | **0.849** | +3.6% |
| LOC | 0.898 | **0.911** | +1.3% |
| Overall (no MISC) | 0.879 | **0.900** | +2.1% |

**Entity boost test (MIRACL/de, entity_boost=1.0):**

| Mode | No boost | Boost=1.0 | Delta |
|------|---------|-----------|-------|
| bm25_splade | 0.540 | 0.543 | +0.6% (noise) |
| full | 0.619 | 0.621 | +0.3% (noise) |

**Verdict: Ship for facets/multilingual, but entity boost remains neutral.**
Same root cause as F-010: entity text fields contain the same tokens as
content. Entity boost via DMQ only helps when entity fields contain VARIANT
tokens not in content (326 Phase 4 cluster expansion). Keep entity_boost=0.0.

### Citation Scorer Validation: Phase A.3

Swapped: ms-marco-MiniLM-L-2-v2 (16MB INT8) → ms-marco-MiniLM-L-6-v2
(22MB INT8). Old model backed up to `models/onnx/citation-scorer-l2-backup/`.

Verified on 6 (query, passage) pairs (3 relevant, 3 irrelevant):
- **Score separation +27%** (L6: 15.57 vs L2: 12.24)
- L-6 fixes L-2's misscoring on the climate change relevant pair
  (-7.5 → +2.7)
- Published: +4.16 MRR@10 (34.85 → 39.01 on MS MARCO dev)

**Verdict: Ship.** Strictly better, negligible size increase (16→22MB).

### SPLADE Validation: Phase C — DONE

Swapped: opensearch-neural-sparse-encoding-doc-v3-distill (6L DistilBERT, 30K
vocab, double_log1p) → opensearch-neural-sparse-encoding-multilingual-v1 (12L
BERT-multilingual, 105K vocab, log1p). Old model backed up to
`models/splade/naver-splade-v3-backup/`.

**ORT closeSession crash — root cause and fix:**
MLM_LOGITS format models with 105K vocab crash Java ORT 1.24.3 in
`OrtSession.closeSession()` (EXCEPTION_ACCESS_VIOLATION in native
`ReleaseSession`). Crash occurs regardless of opset, IR version,
quantization, or export method. Python ORT is unaffected.

**Fix: PRESPARSE conversion.** Append SPLADE post-processing nodes (ReLU →
log1p → ReduceMax → TopK(k=256)) to the ONNX graph, changing output from
`logits [batch, seq, 105879]` to `output_weights/output_idx [batch, 256]`.
The SpladeEncoder already supports this format (`OutputFormat.PRESPARSE`).
Conversion script: `tmp/splade-multilingual-export/convert_presparse.py`.

Verified: 3-cycle CPU + GPU closeSession test passes. Full pipeline runs
(SciFact, MIRACL/de, EnronQA) start, enrich, query, and shut down without
crash.

**A/B validation (2026-03-28):** Reverted `setMemoryPatternOptimization(false)`
and re-ran MIRACL/de pipeline — no crash, but peak VRAM +518 MB (+15.5%):

| Metric | Without fix | With fix | Delta |
|--------|------------|---------|-------|
| Peak VRAM | 3347 MB | 2829 MB | **-518 MB (-15.5%)** |
| Avg VRAM | 2452 MB | 2345 MB | -107 MB (-4.4%) |
| SPLADE p50 | 8396us | 8650us | noise |
| SPLADE p95 | 15507us | 15474us | noise |
| Pipeline total | 93.4s | 93.5s | noise |

Crash is non-deterministic (depends on memory layout at `closeSession()`).
Fix saves ~500MB peak VRAM with zero performance cost — justified on a
12GB GPU running 4 concurrent ORT sessions.

**FP16 GPU model rebuild (2026-03-28):** The original FP16 model was built
with `keep_io_types=True` (wrong order: PRESPARSE first → FP16 after),
producing a tangled Cast chain. Rebuilt using `build-splade.py` canonical
pipeline:
1. `torch.onnx.export` BertForMaskedLM (opset 18, legacy TorchScript)
2. ORT transformer optimize → FP16 convert (`keep_io_types=False`)
3. Append PRESPARSE ops in FP16 → Cast output_weights FP16→FP32

**Root cause:** Old process appended PRESPARSE ops (FP32) *before* FP16
conversion with `keep_io_types=True`, creating type boundary mismatches
and a chain of unnecessary Cast nodes (FP16→FP32→FP16 transitions).
Correct order: ORT optimize → FP16 convert (`keep_io_types=False`) →
append PRESPARSE ops in FP16 → single Cast at output.

**Clean GPU comparison (2026-03-28):**

| Metric | Old FP16 | New FP16 | Delta |
|--------|---------|---------|-------|
| SPLADE p50 | 8,650us | **6,279us** | **-27.4%** |
| SPLADE ORT total | 29,089ms | **22,368ms** | **-23.1%** |
| SPLADE batch avg | 815.9ms | **655.3ms** | **-19.7%** |
| Pipeline total | 93.5s | **83.3s** | **-10.9%** |
| Peak VRAM | 2,829 MB | 3,498 MB | +669 MB |
| Overall docs/sec | 32.3 | **36.0** | **+11.5%** |

Speedup from two factors: (1) ORT transformer optimizer fusing
attention/layer-norm patterns before FP16 conversion (old model
skipped this), (2) clean FP16 graph without redundant Cast nodes
that prevented CUDA kernel fusion.

VRAM increase acceptable (3.5GB peak of 12GB budget). Disk increase:
FP16 474 MB (was 335 MB), FP32 949 MB (was 670 MB) — TorchScript
legacy exporter less compact than dynamo. `build-splade.py` export
function fixed (torch.onnx.export instead of optimum CLI, which
misclassifies SPLADE models as SentenceTransformer).

**Activation correction:** Model card specifies `log1p`, not `relu`. Initial
tests with relu produced extremely peaky weights (max 34 vs 1) and poor
nDCG. With log1p baked into PRESPARSE graph, weight scale matches baseline.

**Search quality (all 300 queries, comparable=True):**

| Dataset | Mode | Old (v3-distill) | New (multilingual) | Delta |
|---------|------|-------------------|--------------------|-------|
| SciFact | bm25_splade | 0.668 | **0.669** | +0.1% |
| SciFact | full | 0.714 | **0.722** | **+1.1%** |
| MIRACL/de | bm25_splade | 0.540 | **0.565** | **+4.6%** |
| MIRACL/de | full | 0.619 | **0.664** | **+7.3%** |
| EnronQA | bm25_splade | 0.813 | **0.814** | +0.1% |
| EnronQA | full | 0.822 | **0.820** | -0.2% |

**Verdict: Ship.** English quality maintained, multilingual quality
significantly improved (+4.6 to +7.3% on German). PRESPARSE conversion
avoids the Java ORT closeSession crash entirely.

Runs: `20260327T191406_scifact`, `20260327T191633_mixed_miracl-de-2k`,
`20260327T203028_mixed_enron-qa`.

**jseval scoring bug fixed:** `--max-queries N` was computing aggregate
nDCG over all qrels (300) instead of only the N evaluated queries. Fixed
by filtering qrels to evaluated query set before calling ir_measures.

---

## Reusable Runs

| Run path | Dataset | Git | Config | Chunk merge |
|----------|---------|-----|--------|-------------|
| `tmp/eval-results/20260327T114635_mixed_miracl-de-2k/` | MIRACL/de | 2681da09b | splade-v3+gemma, bm25-dom, all fixes | OFF (gated) |
| `tmp/eval-results/20260327T102824_scifact/` | SciFact | 68782549f | splade-v3+gemma, bm25-dom, observability fixes | OFF (gated) |
| `tmp/eval-results/20260327T093230_scifact/` | SciFact | 68782549f | splade-v3+gemma, bm25-dom, reranker-on-worker | OFF (gated) |
| `tmp/eval-results/20260327T094938_mixed_enron-qa/` | EnronQA | 68782549f | splade-v3+gemma, bm25-dom, reranker-on-worker | ON |
| `tmp/eval-results/20260324T131341_mixed_enron-qa/` | EnronQA | 1d186cb | splade-v3+gemma, bm25-dom | ON |
| `tmp/eval-results/20260324T140216_mixed_enron-qa/` | EnronQA | 1d186cb | splade-v3+gemma, bm25-dom, chunk_aware=false | OFF (forced) |

A run is reusable when: (a) `git_sha` includes all pipeline-relevant commits,
(b) same models (check `models` section), (c) same config (CC weights, chunk_aware),
(d) `comparable: true`.

## Strategic Direction (post-343)

Model research is complete (tempdoc 358). The pipeline's components are
individually strong — the remaining quality headroom is in **wiring decided
model swaps, fixing infrastructure, and making the pipeline adaptive**.

### ~~Priority 1: Wire decided model swaps (tempdoc 358)~~ — DONE

All 5 model upgrades validated and shipped. Phase D re-baseline complete.
SciFact +3.2%, MIRACL/de +12.4% (full mode with CE). EnronQA quality-neutral
(model swaps); CE hurts email by 3-5% (FW-001). SPLADE FP16 GPU rebuilt
via build-splade.py (−27% per-call latency).

### ~~Priority 2: Fix broken infrastructure~~ — DONE

**F-012 was a tracking bug, not functional.** Dense leg was working all along
with gte-multilingual-base — `buildPipelineExecution()` never reported
`dense: executed`. Tracking fix applied. Confirmed by `full` mode results
consistently outperforming `bm25_splade` (3-way fusion working).

### Priority 3: Corpus-adaptive pipeline

Static CC weights and CE gating leave 2-15% nDCG on the table depending on
corpus type (F-004). `CorpusProfile` already classifies `isShortCorpus()` /
`isLongCorpus()`. Extending it to select CC weights and gate CE per regime
captures these gaps. The register has all the data needed.

| Corpus regime | Optimal CC | Optimal CE | Gap vs wrong config |
|---------------|-----------|------------|---------------------|
| Short academic | balanced | ON | +1.9% |
| Long legal | BM25-dom | ON | +13.4% |
| Email | BM25-dom | **OFF** (343 D: −5.4%) | +5.4% |
| Multilingual | balanced | ON (343 D: +4.8%) | +4.8% |

### Priority 4: Query understanding (pre-retrieval)

Three independent improvements to BM25, the strongest retrieval leg:

- **Locale-aware BM25 routing (Q-004):** `content_de`/`content_en` already
  indexed, never queried. Zero reindexing cost.
- **Entity cluster expansion (326 Phase 4):** Addresses 22 R@10=0 failures
  on EnronQA. Clusters and `expandCanonical()` already implemented for facets.
- **Spell correction (FW-002):** ~100 lines via Lucene `DirectSpellChecker`.

### Priority 5: Content quality (separate track)

F-009: extraction noise is the #1 bottleneck (16% nDCG on PDFs). Decision
settled in tempdoc 252 Phase 6 (2026-03-23): VDU pipeline with Qwen 3.5
vision (the existing chat model) replaces Docling. 76% word overlap vs
Docling's 66% on the same sample, zero new dependencies (reuses
`mmproj-F16.gguf` projector + existing VDU pipeline). Plain-text prompt,
100 DPI, temp=0, JPEG, single pass. Docling integration cancelled.

### ~~Model Swap Validation Protocol~~ — FULLY EXECUTED

All phases (A through D) complete. See per-model validation sections
above and Phase D baseline below. CC fusion weight re-tuning deferred
to Priority 3 (corpus-adaptive pipeline).

## Phase D: Post-Swap Baseline (2026-03-28, git 5d19ff2c1)

All 5 model swaps complete. Models: gte-multilingual-base (embedding),
opensearch-neural-sparse-encoding-multilingual-v1 (SPLADE, FP16 GPU via
build-splade.py), distilbert-multilingual-ner-hrl (NER),
gte-multilingual-reranker-base (CE, FP16 GPU), ms-marco-MiniLM-L-6-v2
(citation scorer).

### Search Quality (CE on)

| Dataset | Mode | nDCG@10 | P@1 | R@10 | comparable |
|---------|------|---------|-----|------|-----------|
| SciFact | lexical | 0.680 | 0.537 | 0.819 | True |
| SciFact | splade | 0.510 | 0.390 | 0.645 | False |
| SciFact | bm25_splade | 0.681 | 0.533 | 0.819 | True |
| SciFact | full | 0.736 | 0.600 | 0.878 | True |
| EnronQA | lexical | 0.799 | 0.697 | 0.887 | True |
| EnronQA | bm25_splade | 0.787 | 0.680 | 0.880 | True |
| EnronQA | full | 0.777 | 0.667 | 0.863 | True |
| MIRACL/de | lexical | 0.559 | 0.367 | 0.797 | True |
| MIRACL/de | splade | 0.733 | 0.530 | 0.910 | False |
| MIRACL/de | bm25_splade | 0.582 | 0.384 | 0.816 | True |
| MIRACL/de | full | 0.696 | 0.469 | 0.908 | True |

### Search Quality (CE off, EnronQA isolation)

| Dataset | Mode | nDCG@10 | P@1 | R@10 | comparable |
|---------|------|---------|-----|------|-----------|
| EnronQA | lexical | 0.827 | 0.717 | 0.927 | True |
| EnronQA | bm25_splade | 0.813 | 0.700 | 0.913 | True |
| EnronQA | full | 0.822 | 0.703 | 0.930 | True |

### Model swap impact (CE off, vs pre-swap baseline)

| Dataset | Mode | Pre-swap | Post-swap | Delta |
|---------|------|---------|----------|-------|
| EnronQA | lexical | 0.827 | 0.827 | 0.0% |
| EnronQA | bm25_splade | 0.813 | 0.813 | 0.0% |
| EnronQA | full | 0.821 | 0.822 | +0.1% |

**Model swaps are quality-neutral on English email.** EnronQA CE-on
regression (−3 to −5%) is 100% CE-induced, confirming FW-001.

### CE impact (on vs off)

| Dataset | Mode | CE off | CE on | Delta |
|---------|------|--------|-------|-------|
| SciFact | full | ~0.714 | 0.736 | **+3.1%** |
| EnronQA | lexical | 0.827 | 0.799 | **−3.4%** |
| EnronQA | full | 0.822 | 0.777 | **−5.4%** |
| MIRACL/de | full | ~0.664 | 0.696 | **+4.8%** |

CE helps on academic/multilingual, hurts on email. Confirms FW-001:
corpus-adaptive CE gating needed.

### Query Latency (ms, includes CE)

| Dataset | Mode | total mean | retrieval | CE | chunk_merge |
|---------|------|-----------|-----------|-----|------------|
| SciFact | lexical | 161 | 1.6 | 153 | — |
| SciFact | bm25_splade | 164 | 8.4 | 152 | — |
| SciFact | full | 177 | 20.6 | 153 | — |
| EnronQA | lexical | 164 | 5.0 | 145 | 5.7 |
| EnronQA | bm25_splade | 166 | 11.1 | 144 | 5.6 |
| EnronQA | full | 178 | 25.8 | 145 | 5.7 |

CE dominates total latency (~150ms). Retrieval is fast (1.6–26ms).

### Pipeline Throughput

| Dataset | Docs | Pipeline total | Embed | SPLADE | NER | Chunks |
|---------|------|---------------|-------|--------|-----|--------|
| SciFact | 5184 | 241s | 234s | 239s | 234s | 205s |
| EnronQA | 5486 | 1022s | 683s | 838s | 683s | 1022s |

### Hardware Usage

| Metric | SciFact | EnronQA |
|--------|---------|---------|
| VRAM peak | 4,347 MB | 4,432 MB |
| VRAM avg | 3,109 MB | 3,113 MB |
| GPU avg util | 64.5% | 48.8% |

### Encoder ORT Latency (microseconds, SciFact)

| Encoder | p50 | p95 | p99 |
|---------|-----|-----|-----|
| embed | 72,613 | 137,494 | 239,992 |
| splade | 15,466 | 21,790 | 24,297 |
| ner | 2,287 | 3,710 | 4,747 |

Runs: `20260328T131217_scifact`, `20260328T133319_mixed_enron-qa`,
`20260328T133840_mixed_miracl-de-2k`, `20260328T145755_mixed_enron-qa` (CE off).

## Out of Scope

- Model switches (settled — current models are final)
- GPL 3-way calibration (280 owns)
- New eval datasets or navigational query sets
- Dense down-weighting (dropped by 309)

## Critical Files

| File | Item | Role |
|------|------|------|
| `docs/reference/search-quality-register.md` | 1.4, 4.1 | Register to update with baselines |
| `modules/configuration/.../ResolvedConfigBuilder.java` | 3.5 | CC weight defaults |

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Baseline refresh plan with critical-files table pointing at search-quality-register.md updates. The baseline refresh as a planning artifact reached its conclusion; ongoing baseline maintenance is per the search-quality register, not this tempdoc.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

