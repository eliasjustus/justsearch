---
title: "308: Search Eval Rewrite — Python + ir-measures Architecture"
type: tempdoc
status: done
created: 2026-03-15
updated: 2026-03-16
---

# 308: Search Eval Rewrite — Python + ir-measures Architecture

## Purpose

Architecture specification for a Python-based search evaluation system replacing the
current PS1/MJS stack. This is the reference doc for implementing and verifying the
new eval.

## Key decisions

- **Full Python rewrite**, not incremental layering on the PS1 stack
- **CI compatibility is not a constraint** — existing `workflow_dispatch` workflows
  (`beir-eval-gate-win.yml`, `search-eval-rank-report-win.yml`,
  `mixed-corpus-gate-win.yml`) can be updated or removed
- **ir-measures for all metrics** — wraps pytrec-eval-terrier (actively maintained,
  v0.4.3 Nov 2025). No custom nDCG/MAP/MRR implementation. ranx available as
  optional richer comparison tool (pinned `pandas<3`).
- **ir-datasets for BEIR corpus loading** — no custom JSONL parsing or ZIP download
- **Golden set as a first-class dataset** alongside BEIR, same pipeline
- **Statistical testing on by default** — every multi-run comparison uses scipy
  paired t-test (deterministic, no dependency risk)
- **End-to-end via live API preserved** — queries go through
  `POST /api/knowledge/search`, not a standalone Lucene harness

## Background

The current eval is a PS1/MJS stack (~3400 lines across `beir-eval-win.ps1`,
`BeirEval.*.psm1`, `dag-runner-beir-gate.mjs`, `convert-beir-metrics-v1-to-v2.mjs`,
and supporting scripts).

What it does well: evaluates against the live JustSearch API (real end-to-end
measurement), enforces comparability (runtime gates, ANN proof, corpus identity),
handles large corpora (backpressure, queue-depth monitoring, doc-count floor).

Why it's being replaced: PowerShell is unusual for IR evaluation and Windows-only.
Metrics are hand-implemented (~30 lines of nDCG math) with a separate 300-line
cross-validation script. No statistical significance testing. No MAP metric. The
v1/v2 schema duality added 682 lines of conversion code. The eval doesn't exercise
key product features (no titled documents, no navigational queries — tempdoc 306).

## Target architecture

### Package structure

```
scripts/jseval/               # project root
  pyproject.toml              # Dependencies, entry point config
  jseval/                     # Python package
    __init__.py

    # --- Data loading ---
    corpora.py              # Unified dataset loading.
                          #   load(name) -> (queries, qrels, corpus_meta)
                          #   Sources: BEIR (via ir-datasets), golden set (local
                          #   three-file dirs), mixed-corpus (merge + namespace).
                          #   Golden set is just another dataset with the same shape.
                          #   Staleness check: verify golden set version, warn if
                          #   query-type distribution doesn't cover QueryType enum.

    materialize.py          # BEIR corpus -> .txt files for JustSearch ingestion.
    readiness.py            # wait_index_idle() + check_search_ready()
    retriever.py            # Query API, resolve doc IDs to BEIR IDs.
    ingest.py               # Corpus ingestion with backpressure.
    scoring.py              # ir-measures wrapper + context coverage.
    provenance.py           # Extract per-hit/per-run evidence.
    ann_proof.py            # Hybrid/dense evidence rate verification.
    comparability.py        # Aggregates readiness + ANN proof + errors.
    run.py                  # Single evaluation run orchestration.
    compare_runs.py         # Multi-run comparison + per-query diagnostics.
    history.py              # SQLite-backed metric history + trend detection.
    artifacts.py            # Output: metrics JSON, per-query, TREC runs.
    cli.py                  # Entry points (run, compare, ab, requery, etc.)
    types.py                # Shared dataclasses (result types).
  tests/
    conftest.py             # Shared test fixtures
    test_corpora.py         # Dataset loading, golden set, mixed-corpus
    test_readiness.py       # Gate condition logic (mock responses)
    test_retriever.py       # Response parsing, doc ID resolution
    test_provenance.py      # Evidence extraction
    test_ann_proof.py       # Threshold logic, edge cases
    test_comparability.py   # Aggregation logic
    test_scoring.py         # ir-measures integration
    test_history.py         # SQLite append, trend detection
    test_compare.py         # Verbose diff, rank change detection
    test_artifacts.py       # Output schema validation
```

### API integration pattern (ir-datasets + ir-measures)

```python
import ir_datasets, ir_measures
from ir_measures import nDCG, AP, RR, R, P

dataset = ir_datasets.load('beir/scifact/test')
qrels = dataset.qrels_iter()  # passes directly to ir-measures

run = [ir_measures.ScoredDoc(qid, doc_id, score)
       for qid, doc_id, score in retriever_results]

agg = ir_measures.calc_aggregate([nDCG@10, AP@10, RR@10, R@10, P@1], qrels, run)
# {nDCG@10: 0.731, AP@10: 0.412, ...}

for m in ir_measures.iter_calc([nDCG@10, AP@10], dataset.qrels_iter(), run):
    print(m.query_id, m.measure, m.value)  # per-query metrics
```

Note: `qrels_iter()` is a one-shot iterator — call again for reuse.
Unjudged documents are treated as not relevant (TREC convention, matches PS1).

### BEIR dataset IDs

| Dataset | ir-datasets ID | Relevance |
|---------|---------------|-----------|
| SciFact | `beir/scifact/test` | Binary (0/1) |
| NFCorpus | `beir/nfcorpus/test` | Graded (0/1/2) |
| ArguAna | `beir/arguana` | Binary (0/1) |
| FiQA | `beir/fiqa/test` | Binary (0/1) |
| Touché | `beir/webis-touche2020` | Binary (0/1) |

### Data flow

```
                    corpora.py
                    (ir-datasets / golden set / mixed)
                        |
                        v
                +--- queries + qrels ---+
                |                       |
         materialize.py            scoring.py (ir-measures)
         (corpus -> .txt files)         ^
                |                       |
         ingest.py                      |
         (backpressure, wait)      {qid: {docid: score}}
                |                       |
         readiness.py              retriever.py
         (poll until ready)        (POST /api/knowledge/search)
                |                       |
                +------ backend --------+
                                        |
                                   raw responses
                                        |
                                  provenance.py
                                  (evidence extraction)
                                        |
                                  ann_proof.py
                                  comparability.py
                                        |
                                  artifacts.py
                                  (metrics JSON, TREC runs, reports)
                                        |
                                  history.py
                                  (SQLite: append run summary, trend detection)
```

### Estimated size

| Module | Estimated | Actual |
|--------|-----------|--------|
| corpora.py | ~100 | 155 |
| materialize.py | ~80 | 82 |
| readiness.py | ~80 | 152 |
| retriever.py | ~150 | 168 |
| ingest.py | ~150 | 196 |
| scoring.py | ~60 | 62 |
| provenance.py | ~200 | 213 |
| ann_proof.py | ~50 | 60 |
| comparability.py | ~30 | 38 |
| types.py | — | 35 |
| run.py | ~140 | 152 |
| compare_runs.py | ~120 | 166 |
| history.py | ~80 | 163 |
| artifacts.py | ~80 | 117 |
| cli.py | ~100 | 159 |
| **Total (production)** | **~1420** | **1918** |
| tests/ (10 files) | ~800 | 145 tests |

Final: 1918 actual / 1420 estimated = 1.35x. Within the stated 1.3-1.5x caveat.
history.py (163 vs 80) and cli.py (159 vs 100) were the main overruns in Step 5.
Current PS1/MJS stack: ~3400 lines (no tests).

### What gets eliminated

1. **v1/v2 schema duality** — one output schema from the start
2. **Custom metric code** — ir-measures handles everything
3. **Cross-validation script** (`validate-beir-metrics-against-trec-eval.mjs`) —
   ir-measures wraps pytrec-eval-terrier directly
4. **PowerShell** — entire PS1 stack replaced
5. **bench-suite.v2 converter** (682 lines) — no conversion needed
6. **Baseline artifact selection** (v1/v2 resolution) — one format

## Dependencies and governance

### Core (required)

```
ir-measures        # Metric computation (wraps pytrec-eval-terrier)
ir-datasets        # BEIR dataset loading
httpx              # HTTP client for sequential queries (sync by default)
scipy              # Paired t-test for statistical comparison
click              # CLI framework
```

### Optional (richer output)

```
ranx               # Multi-run comparison tables, LaTeX export, fusion algorithms
                   # Pin: pandas<3 (ranx crashes on Pandas 3 — issue #79, open)
                   # Note: Fisher's test is nondeterministic (issue #70, open);
                   #   use stat_test="student" only
```

### Dependency health (verified 2026-03-15)

| Package | Latest | Released | Maintenance |
|---------|--------|----------|-------------|
| ir-measures | 0.4.3 | Nov 2025 | Active (commits Feb 2026, 3 releases in 7 months) |
| ir-datasets | 0.5.11 | Jun 2025 | Active (commits Feb 2026, all BEIR datasets supported) |
| pytrec-eval-terrier | 0.5.10 | Oct 2025 | Active (wheels for Python 3.7-3.14) |
| httpx | current | — | Active (standard HTTP library, sync and async) |
| scipy | current | — | Active (foundational scientific Python) |
| ranx | 0.3.21 | Aug 2025 | **Low activity** (zero commits since Aug 2025, 3 open bugs with no response, ~1 release/year) |

ranx is the best tool for multi-run comparison reports but its maintenance risk is
real. The architecture uses ir-measures as the primary scorer so ranx is not on the
critical path. If ranx becomes unmaintained, the only loss is LaTeX table export
and the `compare()` convenience wrapper — both replaceable with ~30 lines of custom
code over ir-measures + scipy.

### Provisioning

- `pyproject.toml` in `scripts/jseval/` with `[project.scripts]` entry point
- Developer runs `pip install -e scripts/jseval/` or uses a venv at
  `scripts/jseval/.venv/`
- **Invocation**: `python -m jseval.cli run --dataset scifact` or via the installed
  entry point `jseval run --dataset scifact`
- Package named `jseval` (not `eval` — shadows Python builtin)
- Lightweight: core deps total ~20 MB (no Numba/NumPy unless ranx is installed)

## Logic preserved from current stack

These JustSearch-specific concerns carry over with simplified implementation:

1. **Readiness gate conditions**: index IDLE, queue empty, dense/SPLADE/LambdaMART
   ready, N stable consecutive polls. Diagnostic failure reasons preserved.
2. **ANN proof**: evidence rate thresholds (>= 95% for hybrid effective rate, dense
   vector evidence rate). PASS/FAIL/NOT_APPLICABLE with reasons.
3. **Comparability aggregation**: gates + ANN proof + query errors + pipeline
   mismatches -> comparable/non_comparable.
4. **Provenance extraction**: per-hit sparse/dense/splade/fusion/chunk-merge scores,
   per-query effective mode and fallback reasons, per-run component status counts.
   Backward-compatible with both `provenance` object and legacy `debugScores`.
5. **Doc-count floor on wait**: `max(initial_indexed, corpus_size)` to prevent
   deadlock on resume with existing data dir.
6. **Backpressure**: queue-depth high/low watermarks during batch ingest.
7. **Indexing throughput**: docs/s, MB/s, wall time measurement.
8. **Doc ID mapping**: URL-encode BEIR `_id` to filename for materialization,
   reverse-map after search. (Fragile but necessary until NDJSON ingest exists.)
9. **Mixed-corpus support**: merge multiple BEIR datasets with namespaced IDs
   (`{dataset}__{original_id}`). Per-source query sets and qrels preserved for
   per-source metric breakdown.

### Readiness gate field reference

`readiness.py` exposes two functions: `wait_index_idle()` (post-ingest, stricter)
and `check_search_ready()` (pre-query/requery, lighter). Both poll `GET /api/status`.

**`check_search_ready()` conditions** (all must pass for `StablePollsRequired=2`
consecutive polls, `PollIntervalSec=2`, `TimeoutSec=30`):

| Field (from `/api/status`) | Condition | Failure reason |
|---------------------------|-----------|----------------|
| `indexState` | `== "IDLE"` | `index_not_idle` |
| `pendingJobs` | `== 0` | `index_queue_not_quiescent` |
| `pendingJobsCount` | `== 0` | `index_queue_not_quiescent` |
| `processingJobsCount` | `== 0` | `index_queue_not_quiescent` |
| `chunkVectorsReady` (if dense) | `== true` | `dense_requested_but_chunk_vectors_not_ready` |
| `embeddingCompatState` (if dense) | `∈ {"", "OK", "NEW_INDEX_NO_FINGERPRINT"}` | `dense_requested_but_embedding_compat_blocked(<state>)` |
| `spladeDocCount` (if SPLADE) | `> 0` | `splade_requested_but_splade_features_not_ready` |
| `spladePendingCount` (if SPLADE) | `<= 0` | (same) |
| `spladeFailedCount` (if SPLADE) | `<= 0` | (same) |
| `spladeCoveragePercent` (if SPLADE) | `>= 99.9` | (same) |

LambdaMART readiness requires a separate `GET /api/debug/state` call →
`reranking.lambdamart.active == true`. This field is NOT in `/api/status`.

Note: PS1 checks `embeddingCompatState ∈ {"", "COMPATIBLE", "NEW_INDEX_NO_FINGERPRINT"}`
but the actual API may return `"OK"` instead of `"COMPATIBLE"`. Verify at runtime.

**`wait_index_idle()` additional conditions** (beyond `check_search_ready()`):

| Field | Condition |
|-------|-----------|
| `pendingReadyJobsCount` | `== 0` |
| `pendingBackoffJobsCount` | `== 0` |
| `buildingIndexedDocuments` | `== 0` |
| `indexedDocuments` | `>= expectedDocCountMin` |

### Backpressure defaults

| Parameter | Default | Description |
|-----------|---------|-------------|
| High watermark | 90,000 | Pause ingestion when `pendingJobs >= 90000` |
| Low watermark | 70,000 | Resume when `pendingJobs <= 70000` |
| Poll interval | 2s | Between backpressure checks |
| Batch size | 200 files | Per ingest POST |
| Request timeout | 25s | Per batch POST |
| Doc-count floor | `max(initialIndexedDocs, corpusDocCount)` | Prevents deadlock on resume |

## Golden set design

The golden set is a product-specific evaluation corpus that exercises what BEIR
cannot. It uses the same three-file structure as BEIR (corpus.jsonl, queries.jsonl,
qrels/test.tsv) and flows through the same evaluation pipeline.

### Construction approach

1. **Tier 1 (minimum viable)**: 30-50 representative queries across QueryType
   classifications (navigational, informational, exact-match, exploratory). For each
   query, annotate 10-20 documents with graded relevance (4-point scale). Corpus
   includes PDFs and Office docs with Tika-extracted titles.
   Quality gates: at least 2 annotators per query with adjudication for disagreements.
   Target inter-annotator kappa >= 0.6 (substantial agreement). Queries where
   annotators disagree on the top-relevant document are flagged for review.

2. **Tier 2 (scaled)**: LLM-assisted judgment generation using rubric-based
   prompting (TRUE framework — Dewan et al., WSDM 2026, DOI 10.1145/3773966.3779397;
   generates label-specific rubrics covering intent alignment, coverage, specificity,
   accuracy, usefulness; more reproducible and auditable than direct "rate this
   document" prompting). Validate a sample against human labels. Expected agreement:
   Cohen's kappa 0.3-0.5 on graded scales, 56-72% exact match on 3-level scales
   (Upadhyay et al. 2025, TREC RAG Track). LLMs systematically over-label as
   relevant — use human labels as ground truth, LLM labels for coverage expansion.

3. **Tier 3 (synthetic)**: LLMs generate both queries and judgments from the corpus.
   Risks: training data bias, hallucinated relevance. Use for coverage expansion,
   not as the primary judgment source.

### What the golden set validates

- Title boost (`TITLE_BOOST = 3.0` vs. `0.0`) on titled documents
- CE/expansion gating for NAVIGATIONAL and EXACT_MATCH queries
- Query classifier routing accuracy
- Known-item refinding (MRR, P@1) on realistic file names and paths
- Latency delta per query type (CE skip saves 60-100ms)

### Data portability

The golden set is just data files. It works with the current PS1 stack or the new
Python stack. Construction can begin before any eval code is written.

## Production readiness: quality management beyond ranking metrics

The architecture above is a measurement tool. For long-term use through production,
it must also be a quality management system. These five capabilities are designed into
the architecture from the start.

### 1. Context quality as a first-class metric

`scoring.py` computes two additional metric families alongside ranking:
- **Latency**: mean/p50/p95 `tookMs` per mode, per query type
- **Context coverage**: for golden set queries with `expectedTerms` annotations,
  check whether those terms appear in `excerptRegions` of retrieved relevant
  documents. Report `contextHitRate` (fraction of queries where at least one
  relevant excerpt contains expected terms). BEIR datasets without annotations
  skip this metric.

`expectedTerms` is a companion JSON alongside `qrels/test.tsv`. Implementation
ports the proven logic from `context-quality-eval.mjs`: normalize (NFKD, lowercase),
tokenize, check substring presence. Aggregation: `contextHitRate` at configurable
thresholds, `meanBestRelevantExcerptCoverage`, `relevantExcerptAvailableRate`.

Retrieval quality and context quality are distinct dimensions (if retrieval is good
but context is poor, the problem is excerpt selection, not ranking).

**Tiered context quality measurement:**

| Tier | Method | When to adopt |
|------|--------|---------------|
| 1 (default) | Term overlap | Always. Fast, deterministic, no model. |
| 2 | Embedding cosine (query vs. excerpt, using JustSearch's model) | When `contextHitRate` < 0.5 on golden set queries with `expectedTerms`. |
| 3 | Cross-encoder score (already computed by JustSearch) | Always expose in output. High CE score + low excerpt coverage = excerpt *selection* problem. |
| 4 | LLM-as-judge nuggets (TRUE framework or nuggetizer) | Monthly or pre-release deep eval, not per-commit. |

### 2. Metric history and trend detection

`compare_runs.py` compares two runs. `history.py` tracks quality over time.

**Design:** Every `jseval run` appends a row to the SQLite history database at
`tmp/eval-history/history.db`.

`jseval compare --history --dataset scifact --mode hybrid` loads the history and:
- Reports the last N runs as a table
- **N < 8**: percentage threshold (warn if latest value > 5% worse than mean of
  last 5 comparable runs). Formal statistical tests lack power below N=8.
- **N >= 8**: Student's t-test against the rolling window (Bencher's recommendation
  for small samples; validated by Urbano et al. SIGIR 2019 as the most robust
  test for IR metric comparison).
- Auto-promotion: suggest promoting the baseline when a run is better by > 1
  standard deviation of historical variance, sustained over 2+ consecutive runs.
  Never auto-promote on a single run (avoids the ratcheting problem where the
  baseline creeps up and normal variance triggers false regressions).

**Storage**: SQLite (indexed queries, ACID writes, single file). Schema:

```sql
CREATE TABLE runs (
  id INTEGER PRIMARY KEY,
  timestamp TEXT NOT NULL,
  git_sha TEXT,
  dataset TEXT NOT NULL,
  mode TEXT NOT NULL,
  ndcg_10 REAL, map_10 REAL, mrr_10 REAL, recall_10 REAL, p1 REAL,
  mean_latency_ms REAL, context_hit_rate REAL,
  comparable INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX idx_runs_dataset_mode ON runs(dataset, mode, timestamp);
```

**Alternative considered**: Bencher (purpose-built continuous benchmarking tool,
SQLite-backed, Student's t-test built in, branch-scoped baselines, web UI). If
`history.py` proves insufficient, Bencher is the natural upgrade — the eval script
would output Bencher Metric Format JSON and `bencher run` handles storage, testing,
and alerting. This avoids reimplementing what Bencher already does.

### 3. Golden set staleness detection

The golden set is versioned data. `corpora.py` validates it on load:

- **Version check**: golden set metadata includes `version`, `created_date`, and
  `query_type_distribution` (counts per QueryType). If `QueryType` enum has types
  not represented in the distribution, warn: "golden set does not cover
  NAVIGATIONAL queries (0 of 30 queries)."
- **Corpus integrity**: verify that all doc IDs in qrels exist in corpus.jsonl.
  Flag missing documents.
- **Age warning**: if `created_date` is older than 90 days, warn: "golden set is
  N days old — consider refreshing judgments."

This is ~20 lines in `corpora.py`, not a separate tool.

### 4. A/B testing as a first-class workflow

The primary use case for the eval after initial setup is comparing pipeline
configurations. `jseval ab` makes this a single command:

```
jseval ab --a pipeline-a.json --b pipeline-b.json --dataset golden/desktop-v1
```

**Design:** `cli.py` `ab` subcommand:
1. Loads dataset once
2. Runs `retriever.py` with pipeline A → Run A
3. Runs `retriever.py` with pipeline B → Run B (same backend state, same corpus)
4. Calls `scoring.py` on both
5. Calls `compare_runs.py` with paired t-test
6. Outputs: per-metric comparison table with p-values, Cohen's d_z effect size,
   bootstrap 95% confidence intervals, per-query diff of rank changes for
   relevant docs
7. **Per-query-type stratification**: if queries have QueryType annotations,
   also reports per-stratum comparison (e.g., "Config B is +12% nDCG for
   NAVIGATIONAL queries but -3% for INFORMATIONAL")

This is ~40 lines in `cli.py` on top of existing `run.py` + `compare_runs.py`.
The key guarantee: same corpus state for both runs (no indexing between A and B).

**Statistical approach** (validated by Urbano et al. SIGIR 2019):
- Paired t-test as primary (most robust, simple, recommended by definitive study)
- Bootstrap 95% confidence intervals alongside p-values (in `compare_runs.py`:
  resample per-query metric deltas 10k times, report 2.5th/97.5th percentiles)
- Cohen's d_z for effect size in `compare_runs.py` (distinguishes "statistically
  significant but trivially small" from meaningful improvements)
- Interleaving and multi-armed bandits are not applicable (require online traffic)
- Grid search over parameters (fusion weights, BM25 k1/b) is a separate concern
  from architectural A/B comparison — can be added later using the same eval
  infrastructure

### 5. Per-query regression diagnostics

When metrics drop, the eval must explain why. `compare_runs.py --verbose` produces
a per-query diff:

```
Query Q42 (nDCG: 0.85 → 0.42, Δ=-0.43):
  doc D17: rank 2 → rank 8 (relevant, rel=2)
  doc D99: absent → rank 3 (irrelevant, rel=0)
  doc D05: rank 5 → rank 4 (relevant, rel=1)
```

**Design:** For each query where the metric degraded beyond a threshold:
- Load `predictedDocIds` from both per-query outputs
- For each relevant doc (from qrels), show rank in run A vs. run B
- For each doc that entered/left the top K, show relevance label
- Sort queries by metric delta (worst regressions first)

This is ~60 lines in `compare_runs.py`, operating on the per-query JSON that
`artifacts.py` already writes.

**Note:** No standard tool provides document-rank-diff out of the box. PyTerrier
and ranx both store full ranked lists per query but neither produces "doc X moved
from rank 2 to rank 8." This is a custom analysis step — align documents across
runs by ID, compute `rank_delta = new_rank - old_rank`, sort by `abs(rank_delta)`,
filter to judged-relevant documents.

## Execution plan

### Step 1: Golden set construction

Build the evaluation corpus data files (corpus.jsonl, queries.jsonl, qrels/test.tsv).
This is portable data, not tooling. No dependency on PS1 or Python eval code.

- Select 30-50 queries across QueryType classifications
- Assemble corpus with PDFs, Office docs, and plain text (Tika-extractable titles)
- Generate relevance judgments (Tier 1: manual, Tier 2: LLM-assisted)
- Validate judgment quality on a human-labeled subset

This is the highest-leverage step (tempdoc 258: blocker is representative lane
coverage, not scorer infrastructure).

### Step 2: Python eval core (corpora + retriever + scoring) — DONE

Implemented at `scripts/jseval/jseval/`. Nested layout: `scripts/jseval/` is the
project root (pyproject.toml), `scripts/jseval/jseval/` is the Python package.

- `corpora.py` (155 lines) — loads BEIR (via ir-datasets), golden set, and
  mixed-corpus datasets. Staleness detection for golden sets (age, query type
  coverage). Returns `(queries, qrels, meta)` as plain dicts + CorpusMeta.
- `retriever.py` (168 lines) — sequential queries with retry and exponential
  backoff. Doc ID resolution via 3-source priority chain (filename → path →
  hit.id). Mode-to-PipelineConfig mapping (lexical sends explicit pipeline JSON).
- `scoring.py` (62 lines) — ir-measures wrapper. `evaluate()` for aggregates,
  `evaluate_per_query()` for per-query metrics. Default: nDCG@10, AP@10, RR@10,
  R@10, P@1.
- `types.py` (35 lines) — shared dataclasses: CorpusMeta, ReadinessResult,
  AnnProofResult, ComparabilityResult.
- 41 unit tests (all passing, 0.49s). Doc ID resolution edge cases, request
  building, BEIR/golden/mixed loading, qrels TSV parsing, ir-measures integration.

**Note**: Python 3.14 on this system. `zlib-state` (ir-datasets dep) doesn't have
pre-built wheels for 3.14; installed ir-datasets without it. Only affects WARC/ClueWeb
dataset formats, not BEIR.

### Step 3: Readiness + provenance + comparability — DONE

- `readiness.py` (152 lines) — `check_search_ready()` (pre-query gate) and
  `wait_index_idle()` (post-ingest wait) with shared polling loop. All gate
  conditions from the field reference table. LambdaMART checked via separate
  `/api/debug/state` endpoint.
- `provenance.py` (213 lines) — dual-path extraction: prefers structured
  `hit.provenance`, falls back to `hit.debugScores`. Per-query evidence
  (effectiveMode, component statuses with legacy fallbacks). Per-run
  aggregation (evidence rates, mode counts, error counts).
- `ann_proof.py` (60 lines) — threshold checks (>= 0.95) on hybrid/vector/dense
  rates. ANN-disabled detection from hybrid fallback reasons. NOT_APPLICABLE
  when embedding disabled.
- `comparability.py` (38 lines) — aggregates readiness + ANN proof + error
  rate into comparable/non_comparable with diagnostic reasons.
- 51 new tests (92 total, all passing, 0.53s).

**Estimate accuracy**: actual 463 lines vs estimated 360 (1.29x). readiness.py
was the outlier (152 vs 80 estimated) due to dual-mode functions, polling loop,
and LambdaMART separate-endpoint handling.

### Step 4: Ingest + materialize — DONE

- `materialize.py` (82 lines) — writes corpus as `.txt` files with URL-encoded
  filenames. Supports both ir-datasets namedtuples and BEIR JSONL dicts. Sentinel
  document for verification. Skip-existing mode for incremental materialization.
- `ingest.py` (196 lines) — `ingest_and_wait()` adds watched root + waits via
  `readiness.wait_index_idle()`. `ingest_batches()` for controlled batch submission
  with backpressure (high/low watermarks 90k/70k). Doc-count floor via
  `max(initial, corpus)`. Throughput measurement (docs/s).
- 21 new tests (113 total, all passing, 0.47s).

**Implementation note**: Python `urllib.parse.quote()` does not encode dots (unlike
PS1 `Uri.EscapeDataString`). This means `5.1234` materializes as `5.1234.txt` not
`5%2E1234.txt`. The roundtrip works because `retriever._filename_to_doc_id` uses
`.stem` which strips only the last extension. This only matters if Python-materialized
and PS1-materialized corpora share a directory — acceptable since PS1 is being replaced.

### Step 5: CLI + artifacts + compare + history — DONE

- `run.py` (152 lines) — orchestrates load → readiness → retrieve → score →
  provenance → ANN proof → comparability → artifacts → history. Supports
  skip_readiness for requery mode.
- `artifacts.py` (117 lines) — writes summary.json, per-mode per_query.json,
  TREC-format run files. Per-query entries merge metrics with response fields.
- `compare_runs.py` (166 lines) — paired t-test, Cohen's d_z, bootstrap 95%
  CI (deterministic seed=42). Per-query rank diff diagnostics showing relevant
  doc movements sorted by worst regression first.
- `history.py` (163 lines) — SQLite-backed metric history. Trend detection:
  percentage threshold for N<8, Student's t-test for N>=8. Non-comparable runs
  excluded from analysis.
- `cli.py` (159 lines) — click-based CLI with 5 subcommands: `run`, `requery`,
  `compare`, `trend`, `materialize`. Entry point: `jseval`.
- 32 new tests (145 total, all passing, 7.67s).

### Step 5b: Legacy parity gaps — DONE

Nine features from the PS1 stack added to existing modules. Changes across
`retriever.py`, `provenance.py`, `run.py`, `cli.py`. 160 tests (was 145).

1. `--max-queries N` CLI flag on `run` and `requery` commands
2. Qrels-only query filtering before API calls
3. `identity_resolution_error_count` in provenance run evidence
4. `zero_hit_query_count/rate` in provenance run evidence
5. `metric_contract` static metadata in summary.json
6. `qrels_summary` (relevance_mode, max_relevance, etc.) in summary.json
7. `pipeline_tracking` (requested vs. observed components, mismatch_reasons)
8. `corpus_identity` from env vars in summary.json
9. `chunk_merge_applied_count/rate` in provenance run evidence

**Not implemented** (justified):
- Evidence warnings — redundant with comparability.reasons
- BaseUrl auto-discovery — CI not a constraint, callers know the port
- Indexing throughput artifact — included in summary.json, not separate file
- NDJSON corpus output — no consumer, ir-datasets provides access
- Corpus stats artifact — doc_count already in summary.json

**Live verification note** (2026-03-16): ran `jseval requery --dataset scifact
--modes lexical` against live backend (1010 indexed docs). 300 queries in ~9s,
nDCG@10=0.2281, artifacts written correctly. Memory: ir-datasets import = 21 MB,
full SciFact load = 21 MB. No leaks. A prior hung test (infinite backpressure
loop, now fixed) caused a 22GB process — root cause was the loop bug, not
ir-datasets or jseval.

### Step 6: Retire PS1/MJS stack

Once the Python eval reproduces the current stack's results on all datasets, remove
the PS1 modules, the v1/v2 converter, the cross-validation script, and the CI
workflows (or update them to call Python).

**Prerequisites** (require running backend):
- Metric parity on SciFact (binary) + NFCorpus (graded) — < 1e-6 tolerance
- Readiness gate parity — same pass/fail decisions as PS1
- Provenance parity — same extracted evidence fields
- Regression detection parity — same flags as diff-search-eval-suite.mjs

## Test strategy

### Metric parity

Run both the Python eval and the current PS1 eval on the same dataset. Compare
per-query nDCG, Recall, MRR, AP values. Tolerance: < 1e-6 difference (floating
point only). This is the primary acceptance criterion for Step 2.

Must verify on at least two datasets:
- **SciFact** (binary relevance) — baseline parity check
- **NFCorpus** (graded relevance: 0/1/2) — verifies nDCG gain formula agreement.
  PS1 uses linear gain (`rel / log2(rank+1)`); ir-measures wraps pytrec_eval which
  uses the same formula by default. Graded datasets expose any formula mismatch
  that binary datasets hide (since `2^1-1 = 1 = 1`).

### Readiness gate parity

Mock `/api/status` responses that represent various failure conditions (index not
idle, SPLADE not ready, embedding incompatible). Verify the Python readiness module
produces the same pass/fail decisions and failure reason strings as the PS1
`Test-BeirRuntimeGates`.

### Provenance parity

Feed the same raw search response JSON to both PS1 `Invoke-BeirEvaluation` and
Python `provenance.py`. Compare extracted evidence fields (effective mode, fallback
reasons, component statuses, evidence rates).

### Regression detection

Create two synthetic runs with known metric differences. Verify `compare_runs.py`
flags the same regressions as the current `diff-search-eval-suite.mjs` script at
the 0.98x threshold.

### Unit tests — DONE

145 tests across 10 test files, all passing (7.67s). Each module has its own test
file with mock inputs. No live backend required. Coverage: doc ID resolution edge
cases, readiness gate conditions (parametrized), provenance dual-path extraction,
ANN proof thresholds, comparability aggregation, ir-measures integration, SQLite
history CRUD, bootstrap CI determinism, per-query rank diff, CLI help commands.

Integration tests (against a running backend) are manual — see Step 6 prerequisites.

## API contract

### Search request (`POST /api/knowledge/search`)

```json
{
  "query": "string (required)",
  "limit": 10,
  "mode": "text|lexical|vector|hybrid|splade",
  "pipeline": {
    "sparseEnabled": true, "denseEnabled": false, "spladeEnabled": false,
    "fusionAlgorithm": "none|rrf", "lambdamartEnabled": false,
    "crossEncoderEnabled": false, "crossEncoderWindow": 0, "expansionEnabled": false
  },
  "debug": false
}
```

`mode` and `pipeline` are mutually exclusive. When `pipeline` is present, `mode` is
ignored. For `"lexical"` mode, the eval sends an explicit `pipeline` object (all
flags false except `sparseEnabled`). For other modes (`"hybrid"`, `"vector"`,
`"splade"`), the plain `mode` string is sent and the server expands it via
`expandPreset()`.

### Search response (relevant fields for eval)

**Top-level:**
- `totalHits`, `tookMs`, `results[]`
- `effectiveMode` — what the server actually ran (`"TEXT"`, `"HYBRID"`, etc.)
- `vectorBlocked`, `vectorBlockedReason`
- `hybridFallback`, `hybridFallbackReason`
- `chunkMergeApplied`, `chunkMergeReason`
- `maxIdf`, `avgIctf`, `queryScope` — QPP signals
- `pipelineExecution` — structured timing + component statuses

**Per hit (`results[]`):**
- `id` — URI-percent-encoded filename (e.g., `5%2E1234.txt`)
- `score` — final ranking score
- `fields` — stored doc fields including `filename`, `path`, `title`,
  `content_preview`, `mime`, `collection`, `is_chunk`, `parent_doc_id`
- `debugScores` — only when `debug=true`; keys include `sparse`, `vector`,
  `rrf`, `cc`, `chunk_*`, `branch_merge_*`
- `provenance` — structured breakdown: `bm25`, `splade`, `dense` (each with
  `rank`, `rawScore`), `fusion` (`score`, `method`), `chunkMerge`, `branchFusion`,
  `crossEncoder`

### Doc ID resolution (critical path)

`retriever.py` must reverse-map search hit IDs to BEIR doc IDs. The resolution
priority (from current `Resolve-BeirHitDocumentIdentity`):

1. `hit.fields.filename` → strip `.txt` extension → URL-decode → BEIR doc ID
2. `hit.fields.path` or `hit.provenance.path` → take leaf → same decode
3. `hit.id` → if file-backed (contains `/`, `\`, or ends `.txt`) → leaf → decode

The mapping is: BEIR `_id` → `EscapeDataString(_id) + ".txt"` at materialization
time, reversed by `UnescapeDataString(stem)` at query time. Edge cases: special
characters, numeric IDs with dots (`5.1234`), chunk documents (have `parent_doc_id`
instead of matching the BEIR ID directly).

### Mode-to-PipelineConfig mapping

| Mode | Sends | Server behavior |
|------|-------|-----------------|
| `lexical` | Explicit `pipeline` (sparse only) | BM25 only, no reranking, no expansion |
| `hybrid` | `mode: "hybrid"` | BM25 + dense, CC fusion, LambdaMART eligible, CE eligible |
| `vector` | `mode: "vector"` | Dense only, no fusion |
| `splade` | `mode: "splade"` | SPLADE only, expansion eligible |
| `pipeline` | Explicit `pipeline` JSON | Caller controls all flags |

## Embedding profiles

Two profiles exist, controlling readiness gates and ANN proof:

| Profile | Embedding | Readiness requires | ANN proof |
|---------|-----------|-------------------|-----------|
| `stub-jaccard` | Disabled | Index IDLE only | `NOT_APPLICABLE` |
| `embedding-nomic-q4` | Enabled | Index IDLE + `chunk_vectors_ready` + `embedding_compat` | Checks `hybridEffectiveRate >= 0.95` |

`retriever.py` must pass `debug=true` when the profile requires ANN proof evidence.
`readiness.py` must check `chunk_vectors_ready` and `embedding_compat_state` only
when embedding is enabled. `ann_proof.py` returns `NOT_APPLICABLE` for stub-jaccard.

## Per-query output schema

Each entry in the per-query JSON array (one file per mode):

```
qid, mode, requestedMode,
recallAtK, ndcgAtK, apAtK, mrrAtK, p1AtK,  # metrics (null if no relevant docs)
predictedDocIds[],                           # ordered doc IDs (up to K)
totalRelevant,                               # count of positive qrels
tookMs, totalHits,                           # server-side timing
maxIdf, avgIctf, queryScope,                 # QPP signals
effectiveMode, vectorBlocked, vectorBlockedReason,
hybridFallback, hybridFallbackReason,
chunkMergeApplied, chunkMergeReason,
corpusProfileId, corpusSignature,
vectorContributionEvidence: {                # per-hit provenance summary
  debugScoresPresent, sparseEvidenceAvailable, denseVectorEvidenceAvailable,
  topHitVector, topHitSparse, topHitChunkVector, topHitChunkSparse, topHitRrf
},
error                                        # null on success, message on failure
```

This is the integration point for downstream analysis. The new eval should produce
a compatible per-query format (possibly with additional fields like MAP contribution).

## Scope of the new eval

### In scope (same pipeline)

- BEIR datasets (scifact, nfcorpus, arguana, fiqa, webis-touche2020)
- Long-doc datasets (MLDR-EN, courtlistener via LoCoV1 converter) — these are
  BEIR-format datasets that flow through the same pipeline
- Mixed-corpus datasets (merged via namespaced IDs)
- Golden set (same three-file format)

### Out of scope (standalone tools, unchanged)

- `rank-eval-win.ps1` — score-stability gate, not qrels-based. Separate concern.
- `correction-eval-win.ps1` — did-you-mean quality. Separate concern.
- `context-quality-eval.mjs` — standalone MJS tool. Its core logic (term overlap,
  evidence unit coverage) is absorbed into `scoring.py`; the standalone tool is not
  ported separately.
- Agent battery / live gate — separate evaluation stream.

### Dataset converters (keep as Node, run before eval)

- `convert-mldr-en-to-beir.mjs` — downloads MLDR from HuggingFace, outputs BEIR format
- `convert-locov1-to-beir.mjs` — fetches LoCoV1 from HuggingFace API, outputs BEIR format
- `build-mixed-beir-dataset.mjs` — merges BEIR datasets with namespaced IDs
- `build-hard-known-item-dataset.mjs` — constructs hard refinding queries

These are dataset construction tools, not evaluation tools. They produce BEIR-format
directories that `corpora.py` loads. No reason to port them.

## Backend lifecycle contract

The new eval **assumes the backend is already running** (same as `beir-eval-win.ps1`).
It takes a `--base-url` parameter and proceeds directly to indexing and querying.

Starting and stopping the backend is the caller's responsibility. For automated
multi-dataset runs, a wrapper script or the existing `eval-backend-lifecycle.mjs`
handles lifecycle. This keeps the eval tool focused on evaluation, not process
management.

For `jseval requery` mode: assumes backend is running AND corpus is already indexed.
A lightweight health check (single `GET /api/status` succeeds) is sufficient — no
full readiness gate.

## Query execution model

Queries execute **sequentially** with retry. No concurrency by default.

- **Per-query timeout**: 90 seconds
- **Retry**: up to 5 attempts with exponential backoff (500ms → 5000ms cap)
- **On failure**: if `--allow-errors` is set, record error and continue; otherwise
  abort the run
- **Concurrency**: sequential is intentional for a single-user desktop backend.
  Optional `--concurrency N` flag can be added later if needed, but the backend
  is not designed for parallel query load.

Typical per-query latency: 15-100ms (varies by corpus size and mode).

## Resolved open questions

- **Mixed-corpus construction**: keep `build-mixed-beir-dataset.mjs` as a Node tool.
  It's a dataset constructor, not an evaluation tool. `corpora.py` loads its output.
- **bench-suite.v2 output**: not produced. The new eval has its own schema. The v2
  converter and its consumers are retired with the PS1 stack.
- **Legacy `debugScores` compatibility**: `provenance.py` supports both the newer
  `provenance` response object (preferred) and legacy `debugScores` (fallback).
  The server currently returns both when `debug=true`.
- **Re-query readiness**: lightweight health check only (single `GET /api/status`
  succeeds), not the full readiness gate.

## Related tempdocs

| Tempdoc | Relationship |
|---------|-------------|
| 251 | Central eval doc. New eval replaces the BEIR harness it describes. 251's lane definitions (long-doc, known-item, mixed-corpus, context-quality) inform corpora.py dataset support. |
| 258 | Strategic steering. Drives Step 1 priority (lane coverage is the blocker). |
| 259 | Eval infrastructure hardening. Superseded by this rewrite for the BEIR lane. |
| 261 | Search quality coordination. References eval lanes that the new eval must support. |
| 267 | Root-cause synthesis of eval campaign failures. Informs readiness.py and ingest.py error handling. |
| 274 | CC fusion upgrade. Contains BEIR eval results that should be reproducible with the new eval. |
| 280 | Stage 3 QDDF. Plans BEIR validation that will use the new eval. |
| 305 | Hot-reload. Enables the `jseval requery` fast iteration loop. |
| 306 | Query classification. Identifies eval coverage gaps that the golden set addresses. |

---

## Phase 2: Evolving jseval — lessons from the legacy eval ecosystem

Phase 1 (Steps 2–6) replaced the BEIR eval pipeline. Phase 2 evolves jseval based on
patterns and capabilities discovered in the broader legacy eval tooling. The legacy
scripts (~87k lines across PS1/MJS) are treated as dead code to learn from, not as
targets for compatibility.

### What the legacy tools got right

Three legacy eval tools reveal capabilities that jseval should adopt natively:

**1. Developer spot-check without qrels** (`rank-eval-win.ps1`)

BEIR evaluation requires downloading a dataset, loading qrels, running 300+ queries,
and computing IR metrics. This takes minutes and measures academic retrieval quality —
not "does my search work on what I've indexed right now?"

rank-eval's insight: a small curated suite of 10 queries across 7 intent categories
(exact, entity, acronym, paraphrase, navigation, negative) run against the developer's
live corpus gives immediate, human-readable feedback in seconds. No qrels, no nDCG — just
"here's what came back, here's the score distribution." The categories encode query
diversity: acronym queries stress normalization, paraphrase queries stress semantic
retrieval, negative queries catch over-retrieval regressions.

The per-hit console output (score, filename, path) is designed for human eyes, not
automated gates. This is a fundamentally different evaluation mode from BEIR.

**2. Excerpt/context quality for RAG** (`context-quality-eval.mjs`)

nDCG measures whether the right documents are retrieved at the right ranks. But for RAG,
the excerpts matter as much as the rankings — retrieving the right document with a
useless excerpt is a RAG failure that nDCG can't see.

context-quality-eval's insight: measure token-level coverage of expected evidence in
returned excerpts. Two levels:
- **Term coverage**: what fraction of expected content tokens appear anywhere in the
  best excerpt of a relevant hit. Available for all queries (falls back to tokenized
  query text when no annotations exist).
- **Evidence unit coverage**: what fraction of multi-word facts/phrases appear in
  excerpts, using both exact substring match and token-set containment (catches
  paraphrased mentions). Only available for queries with explicit annotations
  (`expectedTerms`, `requiredFacts`).

The evidence extraction waterfall (explicit annotations → required facts → tokenized
query fallback) means context coverage works on any dataset but produces richer results
with annotated queries. The two matching strategies (substring for precision, token-set
for paraphrase tolerance) reflect real excerpt quality.

**3. Feature-specific probes** (`correction-eval-win.ps1`)

Not every evaluation is about retrieval ranking. Spell correction is a user-visible
feature that needs its own quality signal. correction-eval's insight: a manifest of
54 queries (44 intentionally misspelled + 10 correctly-spelled controls) with expected
corrections enables precision/recall/FPR measurement that triangulates correction
quality. Three distinct failure modes are tracked separately:
- **Wrong correction**: system corrected but to the wrong text (dictionary/algorithm
  error)
- **False positive**: system corrected text that was already correct (over-aggressive
  triggering)
- **Miss (FN)**: system failed to correct known misspelling (insufficient coverage)

Any change to correction thresholds, dictionaries, or heuristics immediately shifts
these numbers. The manifest also separates error types (typo, transposition,
missing_letter, extra_letter) for diagnostic breakdown.

### What jseval needs to change

#### Cross-cutting: enriched query metadata

`corpora.py` currently returns `dict[str, str]` for queries — just `{qid: text}`. All
JSONL metadata (expectedTerms, requiredFacts, categories, type annotations) is discarded
at load time. Context coverage and feature probes both need query metadata.

Change: `load()` returns an additional `query_metadata: dict[str, dict]` containing the
full JSONL row for each query. The existing `queries: dict[str, str]` return stays
unchanged for backward compatibility. Downstream modules access metadata when available,
ignore when absent (BEIR datasets have none).

#### Cross-cutting: excerpt support in retriever

`retriever.py` never sends `includeExcerpts: true` in the request body. The raw API
responses are preserved in `raw_responses` (via `**response` spread), so excerpt data
IS captured — but only incidentally, and only when context coverage needs it.

Change: add `include_excerpts: bool = False` parameter to `retrieve()`. When True, add
`"includeExcerpts": true` to the request body. No other changes — excerpt data flows
through `raw_responses` automatically.

#### New capability: context coverage (`jseval run --context-coverage`)

Not a separate subcommand — context coverage runs on the same queries, against the same
qrels, alongside retrieval metrics. It's additional evaluation, not a different
evaluation.

New module `context_coverage.py`:
- `STOPWORDS` — English closed-class words (same set as legacy)
- `tokenize_evidence(text) -> list[str]` — NFKD normalization, lowercase, stopword
  removal, min-length filter (3+ chars for words, 2+ for numbers)
- `extract_expected_evidence(row: dict) -> EvidenceSpec` — priority waterfall:
  explicit terms → requiredFacts → tokenized query text. Returns terms, units, source.
- `compute_hit_coverage(hit: dict, evidence: EvidenceSpec) -> HitCoverage` — iterate
  `excerptRegions[].text`, fall back to `fields.content_preview` / `title` / `filename`
- `compute_query_coverage(response: dict, evidence: EvidenceSpec, qrels_entry: dict) -> QueryCoverage`
  — find best coverage across relevant hits within top-K
- `summarize(per_query: list[QueryCoverage], thresholds: list[float]) -> dict` —
  aggregate rates, means, threshold hit rates

Integration into `run.py`:
- When `context_coverage=True`: pass `include_excerpts=True` to retriever, compute
  per-query coverage from raw_responses + query_metadata, include in per_mode summary
- Context coverage metrics appear in `summary.json` alongside retrieval metrics
- Per-query coverage appears in `<mode>_per_query.json`

CLI: `jseval run --context-coverage --thresholds 0.25,0.5`
Also on `jseval requery --context-coverage`

Estimated: ~200 lines module + ~30 lines integration + ~120 lines tests.

#### New capability: developer spot-check (`jseval spot-check`)

Separate subcommand — different input (query suite, not dataset), different metrics
(score distributions, not IR metrics), different output (human-readable, not JSON-first).

New module `spot_check.py`:
- `QueryEntry` dataclass: `id, text, category`
- `DEFAULT_SUITE` — the 10 curated queries from rank-eval, embedded in code
- `load_suite(path: Path | None) -> list[QueryEntry]` — load custom suite JSON or
  fall back to DEFAULT_SUITE
- `execute_spot_check(suite, base_url, modes, top_k) -> SpotCheckResult` — uses
  `retriever._execute_query` for HTTP, computes per-query score metrics:
  `top1_score, mean_score, total_hits, took_ms`
- `summarize(results) -> dict` — per-mode aggregates

Console output format (developer-facing):
```
=== "testing strategy" [exact] mode=lexical ===
totalHits=47  tookMs=12  results=5
  0.87  testing-guide.md          docs/testing-guide.md
  0.62  test-strategy-overview.md docs/test-strategy-overview.md
```

JSON output: `spot-check.v1` schema with per-query and per-mode aggregates.

CLI: `jseval spot-check [--suite path.json] [--modes lexical,hybrid] [--top-k 5]
      [--base-url ...] [--output-dir ...]`

Estimated: ~120 lines module + ~40 lines CLI + ~80 lines tests.

#### New capability: correction probe (`jseval correction-probe`)

Separate subcommand — feature-specific evaluation with its own manifest, classification
logic, and metrics.

New module `correction_probe.py`:
- `CorrectionQuery` dataclass: `query, expected_correction, error_type`
- `load_manifest(path: Path) -> list[CorrectionQuery]` — reads
  `correction-eval-queries.v1.json`
- `classify(query: CorrectionQuery, response: dict) -> str` — returns
  TP / FP / FN / WRONG_CORRECTION / TN based on `correctionApplied` and
  `correctedQuery` response fields
- `compute_metrics(classifications: list) -> dict` — precision, recall, FPR, raw counts
- `execute_probe(manifest, base_url) -> ProbeResult` — uses
  `retriever._execute_query`, classifies each response

CLI: `jseval correction-probe [--manifest path.json] [--base-url ...]
      [--output-dir ...]`

Estimated: ~80 lines module + ~30 lines CLI + ~60 lines tests.

### Module changes summary

| File | Change | Lines |
|------|--------|-------|
| `corpora.py` | Return `query_metadata` from `load()` | +15 |
| `retriever.py` | Add `include_excerpts` parameter | +5 |
| `run.py` | Wire `context_coverage` option | +25 |
| `cli.py` | `--context-coverage` flag, `spot-check` and `correction-probe` subcommands | +70 |
| `artifacts.py` | Include coverage metrics in per_query output | +10 |
| `context_coverage.py` (NEW) | Evidence extraction, tokenization, coverage | ~200 |
| `spot_check.py` (NEW) | Query suite, score distribution | ~120 |
| `correction_probe.py` (NEW) | Manifest loader, classifier, metrics | ~80 |
| Tests (3 new files) | | ~260 |
| **Total** | | **~785** |

### What this does NOT include

- **No compatibility shims** for legacy callers. The 6 scripts that called
  `beir-eval-win.ps1` are dead code. They'll either be rewritten independently or
  remain broken.
- **No server lifecycle management**. Starting/stopping backends is orchestration, not
  evaluation. `EvalSession.psm1` and `eval-backend-lifecycle.mjs` serve their callers.
- **No diff/gate framework**. `policy-engine.mjs` and `suite-loader.mjs` serve the
  benchmark diff tools. jseval's `compare_runs.py` serves jseval.
- **No v2 dual-write**. jseval has its own output schema. The bench-suite.v2 format
  serves the legacy ecosystem.
- **No changes to DAG runners, overnight orchestration, competitor benchmarks, Track G,
  Claim suites, corpus governance, perf suite, or CI workflows.** These are separate
  systems with different owners and concerns.

### Implementation plan

**Step 7a** — Cross-cutting changes: enrich `corpora.py` query metadata, add
`include_excerpts` to `retriever.py`. These are prerequisites for 7b and 7c.
**DONE** — `QueryRecord` dataclass replaces `str` in query dicts. JSONL annotations
preserved. `execute_query` promoted to public API. `include_excerpts` wired through
retriever → run → cli.

**Step 7b** — Context coverage: implement `context_coverage.py`, wire into `run.py`,
add `--context-coverage` to CLI. **DONE** — 220 lines. STOPWORDS, tokenizer (with
NFKD + combining-mark stripping), evidence extraction waterfall, per-term and
per-unit coverage, summarization with configurable thresholds.

**Step 7c** — Spot-check and correction probe: implement `spot_check.py` and
`correction_probe.py` as standalone subcommands. **DONE** — 145 + 140 lines.
10-query default suite with 7 intent categories. Correction manifest (54 queries)
copied to `jseval/data/`. TP/FN/WC/FP/TN classification with precision/recall/FPR.

**Step 7d** — Verification: run all three new capabilities against the live dev stack.
**DONE** — all three verified against 1000-doc index on port 63733:
- spot-check: 20 queries (10×2 modes), human-readable output with scores/filenames
- correction-probe: 50 queries, precision=0.875, recall=0.368, FPR=0.000
- context-coverage: 10 SciFact queries, 50% relevant doc hit rate, 26.9% mean term coverage

### Implementation actuals

| File | Lines | New/Modified |
|------|-------|-------------|
| `jseval/types.py` | +30 | QueryRecord, EvidenceSpec, QueryCoverage |
| `jseval/corpora.py` | +12 | QueryRecord return type, annotation preservation |
| `jseval/retriever.py` | +6 | public execute_query, include_excerpts param |
| `jseval/run.py` | +25 | context_coverage wiring, QueryRecord handling |
| `jseval/cli.py` | +65 | --context-coverage flag, spot-check & correction-probe commands |
| `jseval/context_coverage.py` | 220 | **NEW** — evidence extraction, tokenization, coverage |
| `jseval/spot_check.py` | 145 | **NEW** — query suite, score distribution metrics |
| `jseval/correction_probe.py` | 140 | **NEW** — manifest loader, classifier, metrics |
| `jseval/data/correction-eval-queries.v1.json` | 55 | **COPIED** from scripts/search/ |
| `tests/test_context_coverage.py` | 135 | **NEW** — 18 tests |
| `tests/test_spot_check.py` | 100 | **NEW** — 10 tests |
| `tests/test_correction_probe.py` | 85 | **NEW** — 12 tests |
| `tests/test_corpora.py` | +10 | QueryRecord assertion fixes + annotation test |
| `tests/test_run.py` | +8 | QueryRecord in mocks |
| **Total new** | **~1,036** | **214 tests pass (161 existing + 53 new)** |

### Step 8 — Dead code deletion

**Deleted (7 files, ~2,118 lines):**

| File | Lines | Reason |
|------|-------|--------|
| `scripts/search/correction-eval-win.ps1` | 163 | Superseded by `jseval correction-probe`. No callers. |
| `scripts/search/correction-eval-queries.v1.json` | 55 | Only read by above. Copy in `jseval/data/`. |
| `scripts/search/test-beir-eval-search-lib.ps1` | 648 | Tests deleted `BeirEval.Search.psm1`. |
| `scripts/search/test-beir-eval-indexing-lib.ps1` | 544 | Tests deleted `BeirEval.Indexing.psm1`. |
| `scripts/search/test-beir-eval-metrics-lib.ps1` | 85 | Tests deleted `BeirEval.Metrics.psm1`. |
| `scripts/search/test-context-quality-eval.mjs` | ~80 | Tests retiring `context-quality-eval.mjs`. Not in CI. |
| `scripts/eval/eval-session.mjs` | 543 | Zero importers. Never adopted JS mirror. |

Also fixed `scripts/ci/run-corpus-governance-quickcheck.ps1` — removed 4 invocations
of test scripts whose modules no longer exist.

### Step 9 — Full legacy eval removal plan

Dependency analysis reveals that most remaining legacy eval code in `scripts/search/`
is already broken by the Phase 1 deletion of `beir-eval-win.ps1`. The central broken
node is `run-search-workflow.mjs` — it dispatches to `beir-eval-win.ps1` and
`run-ranking-experiments.ps1` (which also calls `beir-eval-win.ps1`). This cascades
to `run-mixed-corpus-matrix.mjs` and `run-phase4-mixed-splade-suite.mjs`.

#### Deletion targets — scripts/search/ eval tooling

**Already broken (call deleted `beir-eval-win.ps1` directly or transitively):**

| File | Lines | Broken via |
|------|-------|-----------|
| `run-ranking-experiments.ps1` | 1,268 | Direct call to `beir-eval-win.ps1` in `Run-BeirEval` |
| `run-search-workflow.mjs` | 1,590 | `workflowScripts['beir-eval']` resolves to deleted file |
| `run-mixed-corpus-matrix.mjs` | 650 | Calls `run-search-workflow.mjs` for each cell |
| `run-phase4-mixed-splade-suite.mjs` | 232 | Calls `run-search-workflow.mjs` |
| `test-search-workflow-runner.mjs` | 1,544 | Tests `run-search-workflow.mjs` |
| `test-run-mixed-corpus-matrix.mjs` | 458 | Tests `run-mixed-corpus-matrix.mjs` |
| `test-run-phase4-mixed-splade-suite.mjs` | 58 | Tests broken orchestrator |
| `test-ranking-experiments-contract.mjs` | 79 | Tests `run-ranking-experiments.ps1` |

**Functional but superseded by jseval (no CI callers):**

| File | Lines | jseval replacement |
|------|-------|-------------------|
| `rank-eval-win.ps1` | 249 | `jseval spot-check` |
| `context-quality-eval.mjs` | 636 | `jseval run --context-coverage` |
| `run-phase4-mixed-context-quality-suite.mjs` | 442 | Developer-only, not in CI |
| `summarize-mixed-corpus-findings.mjs` | 257 | Developer-only, not in CI |
| `test-run-phase4-mixed-context-quality-suite.mjs` | 52 | Tests above |
| `test-summarize-mixed-corpus-findings.mjs` | 151 | Tests above |
| `lib/mixed-corpus-config.mjs` | 115 | Config consumed only by above scripts |

**Total: ~7,781 lines across 17 files.**

#### CI scripts/helpers to update

| File | Lines | Change |
|------|-------|--------|
| `scripts/ci/helpers/rank-eval-with-retry.mjs` | 106 | **DELETE** — wraps `rank-eval-win.ps1` |
| `scripts/ci/dag-runner-search-eval-rank-report.mjs` | 751 | Remove `judged-beir` step + update `rank-eval` step |
| `scripts/ci/dag-runner-search-eval-rank-report.test.mjs` | 635 | Update assertions for removed steps |
| `scripts/ci/dag-runner-mixed-corpus-gate.mjs` | 380 | **DELETE** — entire gate's eval steps are broken |
| `scripts/ci/dag-runner-mixed-corpus-gate.test.mjs` | ~300 | **DELETE** — tests above |

#### CI workflows affected

| Workflow | Status | Action |
|----------|--------|--------|
| `beir-eval-gate-win.yml` | Already deleted (Phase 1) | Done |
| `mixed-corpus-gate-win.yml` | Broken (calls deleted scripts) | Delete |
| `search-eval-rank-report-win.yml` | Partially broken (judged-BEIR step) | Update |

#### What STAYS in scripts/search/ (not eval tools)

Dataset construction and conversion — these are data tools, not eval:
- `build-mixed-beir-dataset.mjs`, `build-hard-known-item-dataset.mjs`
- `build-mixed-context-annotations.mjs`
- `convert-mldr-en-to-beir.mjs`, `convert-locov1-to-beir.mjs`
- `extract-known-item-terms.mjs`, `compose-known-item-queries.mjs`
- Tests for all of the above
- `test-gpu-splade-worker-classpath.mjs` (runtime test, not eval)

#### What STAYS in scripts/eval/ (used by other subsystems)

- `EvalSession.psm1` — still used by Claim B, perf suite, benchmark-ci-common
- `common/eval-common.psm1` — imported by EvalSession
- `embed_reference.py` — standalone embedding tool

#### Implementation approach

**Step 9a**: Delete all 17 files from `scripts/search/` listed above.

**Step 9b**: Delete `rank-eval-with-retry.mjs`, `dag-runner-mixed-corpus-gate.mjs`
and its test from `scripts/ci/`. Update `dag-runner-search-eval-rank-report.mjs` to
remove the judged-BEIR step and `rank-eval-with-retry.mjs` reference.

**Step 9c**: Delete `mixed-corpus-gate-win.yml`. Update
`search-eval-rank-report-win.yml` if needed.

**Step 9d**: Update docs that reference deleted scripts.

### Step 10 — Embedded latency and throughput metrics

Latency and indexing throughput metrics embedded in every eval run — not separate
benchmark commands. The raw data already exists; this step surfaces it.

#### Query latency profiling

Every `retriever.retrieve()` call already captures `tookMs` per query in
`raw_responses`. The per-query JSON already includes it. What's missing:
aggregate latency stats in the per-mode summary.

**Change in `run.py`** — after retrieval, compute latency distribution:

```python
latency_stats = _compute_latency_stats(raw_responses)
mode_results[mode]["latency_stats"] = latency_stats
```

`_compute_latency_stats(raw_responses) -> dict`:
- Collect all `tookMs` values from non-error responses
- Compute: `p50`, `p95`, `p99`, `mean`, `max`, `min`, `query_count`
- Use `sorted()` + index math for percentiles (no numpy needed)

Output in `summary.json` per mode:
```json
{
  "latency_stats": {
    "query_count": 300,
    "mean_ms": 23.4,
    "p50_ms": 18,
    "p95_ms": 52,
    "p99_ms": 89,
    "max_ms": 142,
    "min_ms": 3
  }
}
```

This is always computed — no flag needed. ~20 lines in run.py.

#### Indexing throughput (when applicable)

`ingest.py::ingest_and_wait()` already returns `elapsed_sec` and `docs_per_sec`.
This data is available when the caller performs ingestion before evaluation, but
it doesn't flow into the eval summary.

**Change in `run.py`** — accept optional ingest summary:

```python
def execute_run(..., ingest_summary: dict | None = None, ...) -> dict:
```

When provided, include in the top-level summary:
```json
{
  "ingest": {
    "docs_indexed": 5183,
    "elapsed_sec": 42.3,
    "docs_per_sec": 122.5,
    "initial_doc_count": 0,
    "final_doc_count": 5183
  }
}
```

The caller (CLI or external script) is responsible for calling `ingest_and_wait()`
and passing the result. `execute_run` just includes it in the summary if present.
~5 lines in run.py, ~3 lines in cli.py.

#### Console output

`_print_summary()` in cli.py shows latency stats after metrics:
```
  lexical:
    nDCG@10: 0.6820
    AP@10: 0.5430
    ...
    latency: p50=18ms  p95=52ms  p99=89ms  mean=23ms
    comparable: True
```

#### Worker-side metrics

`ingest_and_wait()` now also captures from the final `/api/status` snapshot:
- `worker_throughput_docs_per_sec` — Worker's own rolling-window throughput estimate
- `index_size_bytes` — Lucene index size on disk

These complement the client-side `docs_per_sec` (wall-clock timing) with the
server's internal measurement and storage efficiency data.

#### Implementation status

**DONE.** Latency stats: `_compute_latency_stats()` in run.py (p50/p95/p99/mean/
min/max), always computed, shown in console output. 4 tests. Ingest throughput:
`ingest_summary` pass-through in `execute_run()`, Worker-side metrics captured
in `ingest_and_wait()`. 218 tests pass.

| File | Change | Lines |
|------|--------|-------|
| `run.py` | `_compute_latency_stats()`, `ingest_summary` param, summary wiring | +30 |
| `cli.py` | latency line in `_print_summary()` | +5 |
| `ingest.py` | `worker_throughput_docs_per_sec`, `index_size_bytes` capture | +2 |
| `tests/test_run.py` | 4 latency stats tests | +20 |
| **Total** | | **~57** |

### Step 11 — Pipeline stage timing and score distribution

Two additional metric categories worth embedding in eval runs. Both use data
already present in every API response.

#### Pipeline stage timing

The API returns per-query timing for each pipeline stage in `pipelineExecution`:
`retrievalMs`, `chunkMergeMs`, `crossEncoderMs`, `lambdaMartMs`, `branchFusionMs`,
`chunkBm25Ms`, `chunkKnnMs`, `chunkSpladeMs`. jseval currently ignores all of these.

These answer "where is latency spent?" — if overall p95 is 89ms, knowing it's
5ms retrieval + 2ms chunk merge + 82ms cross-encoder is immediately actionable
for optimization.

**Change in `provenance.py`** — extract timing fields from `pipelineExecution`:
```python
timing = response.get("pipelineExecution", {})
qe["stage_timing"] = {
    "retrieval_ms": timing.get("retrievalMs"),
    "chunk_merge_ms": timing.get("chunkMergeMs"),
    "cross_encoder_ms": timing.get("crossEncoderMs"),
    "lambdamart_ms": timing.get("lambdaMartMs"),
    "branch_fusion_ms": timing.get("branchFusionMs"),
}
```

**Aggregation in `provenance.py`** — `aggregate_run_evidence()` computes per-stage
p50/mean across all queries (same pattern as `_compute_latency_stats`).

**Output in summary** — per-mode `stage_timing_stats`:
```json
{
  "stage_timing_stats": {
    "retrieval_ms": {"mean": 5.2, "p50": 4, "p95": 12},
    "cross_encoder_ms": {"mean": 42.1, "p50": 38, "p95": 82}
  }
}
```

Estimated: ~30 lines in provenance.py, ~10 lines in run.py, ~20 lines tests.

#### Score distribution per mode

`hit.score` (the final ranking score after all reranking) flows through
`raw_responses` but isn't aggregated. Computing mean top-1 score and mean top-K
score per mode catches scoring regressions invisible to rank-based metrics
(e.g., scores drop 50% but ranking order is unchanged).

**Change in `run.py`** — extend `_compute_latency_stats` or add a sibling:
```python
def _compute_score_stats(raw_responses, top_k):
    top1_scores = []
    topk_means = []
    for r in raw_responses:
        if r.get("error"):
            continue
        hits = r.get("results", [])[:top_k]
        scores = [h["score"] for h in hits if "score" in h]
        if scores:
            top1_scores.append(scores[0])
            topk_means.append(sum(scores) / len(scores))
    return {
        "mean_top1_score": round(sum(top1_scores) / len(top1_scores), 4) if top1_scores else None,
        "mean_topk_score": round(sum(topk_means) / len(topk_means), 4) if topk_means else None,
    }
```

Estimated: ~15 lines in run.py, ~10 lines tests.

**Steps 10–11 DONE.** Both implemented: latency stats, score stats, stage timing,
ingest throughput capture. 225 tests pass.

---

## Phase 4: Benchmark absorption into jseval

The benchmark suite (`scripts/bench/`, ~35k lines PS1/MJS) and perf suite
(`scripts/perf/`, ~5.5k lines) contain five distinct measurement capabilities
that jseval can absorb. Investigation of each revealed that the actual
measurement logic is small — most code is orchestration, artifact management,
and v1/v2 format handling.

### What the bench suite measures and how

| Benchmark | Measurement surface | Lines of measurement logic | Lines of orchestration |
|-----------|--------------------|--------------------------|-----------------------|
| Claim A (engine index) | Gradle `engineIndexBench` → `result.json` | 0 in PS1 (Java owns timing) | ~200 PS1 (loop + stats) |
| Claim B (pipeline index) | HTTP `POST /api/roots` → poll `/api/status` | ~80 PS1 (timer + poll) | ~170 PS1 (EvalSession) |
| Claim D (LLM inference) | HTTP SSE `/api/summarize/batch/stream` | ~300 PS1 (SSE parse, TTFT, token rate) | ~260 PS1 (suite loop) |
| Track G (kNN latency) | Gradle `filteredKnnBench` → `result.json` | 0 in PS1 (Java owns timing) | ~750 PS1 (cases, repeats, aggregation) |
| RAG eval | Gradle `RagQualityEvalTest` → artifact | 0 in PS1 (Java owns eval) | ~376 MJS (diff/gate only) |
| Competitor bench | HTTP to external engines (Meilisearch, Qdrant, etc.) | ~200 per adapter (HTTP + Stopwatch) | ~615 PS1 (suite orchestration) |
| Perf suite (non-UI) | HTTP to JustSearch API | ~200 MJS (inside capture-evidence-bundle) | ~930 PS1 (scenarios, dev-runner) |
| Perf suite (UI) | Playwright `page.evaluate()` + RAF timing | ~300 MJS (Playwright harness) | same orchestration |

### Absorption plan — jseval subcommands

#### Step 12 — `jseval llm-bench` (Claim D replacement)

Highest-value port. All measurement logic is in PowerShell (`llm-bench-win.ps1`,
562 lines) — SSE parsing, TTFT measurement, token rate calculation. Python with
httpx can handle SSE natively.

What it does:
- Discovers indexed doc IDs via `POST /api/knowledge/search` (`*:*` query)
- Sends `POST /api/summarize/batch/stream` with `Accept: text/event-stream`
- Parses SSE events: `chunk` (count + first-chunk TTFT), `done` (extract
  `usage.completionTokens`), `error`
- Computes: e2e_latency p50/p95/p99, TTFT p50/p95/p99, token_rate_actual_tps
- Sampling profiles: smoke (5 docs, 2 timed), regression (10 docs, 5 timed),
  full (20 docs, 10 timed)

Prereqs: running dev stack + AI runtime active. Checks `/api/inference/status`.

New module: `llm_bench.py` (~200 lines). CLI: `jseval llm-bench [--profile
smoke|regression|full] [--runs 3]`.

Replaces: `llm-bench-win.ps1` (562 lines) + `run-claim-d-suite-win.ps1`
(365 lines) + `core/claim-d-suite-common.ps1` (197 lines) = **1,124 lines**.

#### Step 13 — `jseval ingest-bench` (Claim B replacement)

jseval's `ingest_and_wait()` already does the core measurement — add watched
root, poll until idle, compute docs/s. What's missing: multi-run iteration with
suite statistics (median/stddev/CI).

New module: `ingest_bench.py` (~80 lines). CLI: `jseval ingest-bench --corpus-dir
<path> [--runs 5] [--clean hard]`.

Replaces: `pipeline-bench-win.ps1` (279 lines) + `run-claim-b-suite-win.ps1`
(172 lines) = **451 lines**.

#### Step 14 — `jseval engine-bench` + `jseval knn-bench` (Claim A / Track G)

Shell out to Gradle tasks, parse `result.json`. No measurement reimplementation
— the Java benchmarks own all timing. jseval orchestrates (run N iterations,
compute median/CI, write summary).

`engine-bench`: calls `./gradlew.bat :modules:benchmarks:engineIndexBench
-PbenchCorpus=<path> -PbenchOutDir=<dir>`, reads `result.json` with
`time_to_searchable_ms`, `docs_per_s`, `mb_per_s`.

`knn-bench`: calls `./gradlew.bat :modules:benchmarks:filteredKnnBench` with
configurable doc counts, vector dim, filter sizes. Handles multiple cases
(doc-mode 20K/200K, chunk-mode 200K/500K) + quantization gate. Repeat
aggregation via median selection.

New module: `gradle_bench.py` (~150 lines, shared Gradle subprocess + JSON
parsing). CLI: `jseval engine-bench [--corpus <ndjson>] [--runs 5]` and
`jseval knn-bench [--doc-counts 20000,200000]`.

Replaces: `engine-index-bench-win.ps1` (102 lines) + `run-claim-a-suite-win.ps1`
(200 lines) + `run-track-g-suite-win.ps1` (388 lines) +
`core/track-g-suite-common.ps1` (362 lines) + `core/claim-suite-common.ps1`
(113 lines) + `filtered-knn-bench-win.ps1` (163 lines) +
`vector-quantization-gate-win.ps1` (105 lines) = **1,433 lines**.

#### Step 15 — `jseval rag-eval` (RAG eval orchestration)

The actual RAG evaluation runs as a Gradle integration test
(`RagQualityEvalTest`). jseval would orchestrate the test run + compare the
7 metrics against a baseline using ratio-based gates.

New module: `rag_eval.py` (~100 lines). CLI: `jseval rag-eval [--baseline
<path>] [--profile stub-jaccard|embedding]`.

Replaces: `diff-rag-eval-suite.mjs` (376 lines) + Gradle invocation logic
from `overnight-rag-ai-queue.mjs`.

#### Step 16 — Suite statistics + diff/gate framework

Shared infrastructure needed by all benchmarks above.

`suite_stats.py` (~40 lines): median, stddev, 95% CI (t-distribution for
small N, z=1.96 for N>11). Ports `Get-Stats` from `benchmark-utils.ps1`.

`diff_gate.py` (~60 lines): ratio-based regression detection with configurable
thresholds. Ports `policy-engine.mjs`. `jseval diff --baseline <a> --candidate
<b> [--thresholds <json>]`.

Replaces: `benchmark-utils.ps1` stats (~50 lines used) + `policy-engine.mjs`
(119 lines) + per-lane diff scripts.

#### Step 17 — Competitor benchmarks (optional, lower priority)

Fully replicable — each adapter is HTTP requests + subprocess management.
But 5 adapters × ~400 lines each = ~2,000 lines of integration code for
external engines. Only worth doing if competitor comparison is an active need.

Defer unless explicitly requested.

#### Deferred — Perf suite UI scenarios

Playwright browser automation for keystroke-to-paint and click-to-preview
latency. Python has `playwright-python` bindings, but the full EvidenceBundle
v1 pipeline (capture, validate, determinism budget) is deeply integrated with
the Node.js evidence infrastructure. The HTTP-only perf scenarios (API latency,
worker metrics) are trivially portable; the UI scenarios are a larger port.

Defer — different domain (UI quality, not search/retrieval quality).

### Implementation status

**Steps 12–16 DONE.** All five benchmark capabilities implemented as jseval
subcommands. 269 tests pass (225 + 44 new). 6 new modules, 6 new test files.

| Module | Lines | Subcommand |
|--------|-------|-----------|
| `suite_stats.py` | 75 | (shared infrastructure) |
| `diff_gate.py` | 95 | `jseval diff` |
| `llm_bench.py` | 170 | `jseval llm-bench` |
| `ingest_bench.py` | 65 | `jseval ingest-bench` |
| `gradle_bench.py` | 210 | `jseval engine-bench`, `jseval knn-bench` |
| `rag_eval.py` | 110 | `jseval rag-eval` |
| CLI additions | 105 | 6 new subcommands in cli.py |
| Tests (6 files) | 330 | 44 new tests |
| **Total** | **~1,160** | |

### Legacy deletion — DONE

Deleted 23 files (~6,261 lines) + 4 CI scripts. `policy-engine.mjs` retained
(still imported by perf diff, competitor diff, and search-eval-rank-semantics).
README updated to point at jseval subcommands.

---

## Cumulative impact (all phases)

| Phase | New Python | Legacy deleted | Net |
|-------|-----------|---------------|-----|
| Phase 1 (BEIR eval) | +1,918 | -7,054 | -5,136 |
| Phase 2 (capabilities) | +1,036 | -2,118 | -1,082 |
| Phase 3 (search eval removal) | 0 | -7,781 | -7,781 |
| Steps 10-11 (metrics) | +57 | 0 | +57 |
| Phase 4 (benchmarks) | +1,354 | -6,261 | -4,907 |
| Cleanup (bench refs) | 0 | -529 | -529 |
| **Total** | **~4,365** | **~23,743** | **-19,378** |

jseval: ~4,400 lines, 23 modules, 269 tests, 13 CLI subcommands.

### Final mass deletion

Deleted `scripts/bench/` (239 files, ~87,700 lines), `scripts/eval/` (3 files),
`scripts/lib/` (all shared bench/orchestration infrastructure), and associated
CI scripts. Only `scripts/perf/` retained (UI perf via Playwright — different
domain).

### What remains

**`scripts/jseval/`** — the Python eval toolkit. ~4,400 lines, 23 modules,
269 tests, 13 CLI subcommands. Covers: BEIR/golden set retrieval evaluation,
context coverage, spot-check, correction probe, LLM inference bench, ingest
throughput bench, engine indexing bench, kNN latency bench, RAG quality gate,
regression comparison, metric history, statistical comparison.

**`scripts/perf/`** (~5,500 lines) — UI performance testing via Playwright.
Keystroke-to-paint, click-to-preview, worker restart timing. Different domain
from search evaluation — requires browser automation. Retained as-is.

**`scripts/search/`** — dataset construction tools only (build-mixed-beir-dataset,
converters, known-item builders + tests). No eval code.

**`scripts/ci/`** — CI scripts for agent battery, local agent gate, workflow
quality gate, build/package/sign, UI checks, JNI shims, linting, release
governance. No bench/eval orchestration remains.

---

## Phase 5: Agent usability improvements

Critical audit of jseval's current state identified 12 issues that hurt agent
automation. Sorted by impact and implementation effort:

### Tier 1 — High impact, trivial to implement

**1. Add `__main__.py`** — enables `python -m jseval`. Currently agents must use
`python -c "from jseval.cli import main; main([...])"`. Fix: create
`jseval/__main__.py` with `from .cli import main; main()`. 2 lines.

**2. Add `--json` flag to all commands** — emit structured JSON to stdout instead
of human-readable text. Currently agents must set `--output-dir`, run the
command, then read the JSON file from disk. Fix: when `--json` is set, suppress
human formatting and `json.dumps(result)` to stdout. ~30 lines across cli.py.

**3. Populate history latency/coverage columns** — `mean_latency_ms` and
`context_hit_rate` are defined in the SQLite schema but always stored as NULL.
The data exists in the summary. Fix: extract from `latency_stats.mean_ms` and
`context_coverage.mean_best_term_coverage` in `history.append_run()`. ~5 lines
in history.py.

**4. Gate exit codes on `trend` and `compare`** — both always exit 0. An agent
can't use them as pipeline gates. Fix: `--fail-on-regression` flag that exits 1
when regression detected. ~10 lines each.

### Tier 2 — Medium impact, small effort

**5. Add query text to per-query JSON** — currently only `qid` is stored. Agents
debugging regressions must join back to the original dataset. Fix: include
`query_text` in `_build_per_query_entries()`. ~3 lines in artifacts.py, requires
passing query_records through.

**6. Expose `--lambdamart` flag in CLI** — `execute_run()` accepts it but the CLI
doesn't expose it. ~2 lines in cli.py.

**7. Fix `rag-eval --profile` dead code** — the profile parameter is accepted but
never forwarded to Gradle. Fix: pass as env var or Gradle property. ~3 lines.

**8. Add more `diff` lanes** — only `claim-a` and `claim-b` are hardcoded. Fix:
add `llm-bench`, `track-g`, accept `--thresholds` JSON for custom configs.
~15 lines.

### Tier 3 — Lower impact or larger effort

**9. Multi-run ingest bench index reset** — the code documents that callers must
reset the index between runs. Fix: call a lifecycle endpoint or restart the
backend subprocess. Requires understanding the dev-runner API. ~30 lines but
needs investigation.

**10. Context coverage on BEIR** — falls back to tokenized query text, making
coverage circular. This is a fundamental limitation, not a bug. BEIR queries
don't have evidence annotations. Could add a note in the output when source is
`query_terms` to flag this.

**11. Shared history store** — history is local to `--output-dir`. Different dirs
have different SQLite databases. Fix: use a shared default path (e.g.,
`~/.jseval/history.db`). ~10 lines but changes behavior.

**12. `requery` missing `--splade`** — inconsistency with `run`. ~2 lines.

### Implementation estimate

| Tier | Items | Lines | Confidence |
|------|-------|-------|-----------|
| Tier 1 (1-4) | 4 items | ~50 lines | High — all are straightforward |
| Tier 2 (5-8) | 4 items | ~25 lines | High — simple parameter wiring |
| Tier 3 (9-12) | 4 items | ~50 lines | Medium — items 9 and 11 need design decisions |
| **Total** | **12** | **~125 lines** | |

---

## Phase 6: Issues found during live eval session (2026-03-17)

A hands-on jseval testing session (309 §31 Phase 1 prep) validated the full
workflow: `materialize` → start backend → add watched root → wait IDLE →
`requery`. SciFact lexical nDCG@10=0.6613, matching the §30 baseline (0.6619).
Several issues were found.

### Bug 1 (blocking): `ingest.py` uses wrong API route

`ingest.py:99` POSTs to `/api/knowledge/roots`. The actual backend route is
`/api/indexing/roots` (`IndexingRoutes.java:12`). The POST returns HTTP 200 with
an empty body (Javalin default for unmatched routes), silently failing to register
the watched root. No error is raised.

This means `jseval run` (which calls `ingest_and_wait → add_watched_root`) will
never trigger indexing. `jseval requery` works because it skips ingest entirely.

**Desired behavior**: `jseval run` should be a single command that handles
everything end-to-end: materialize the corpus to disk, add the watched root via
the correct API endpoint, wait for indexing + backfill to complete, then run
queries. The agent should not need to manually `curl` to add roots. Specifically:

1. Fix the API route to `/api/indexing/roots`.
2. `jseval run` should call `materialize` automatically if the corpus directory
   doesn't already exist at `--corpus-dir` (or a sensible default like
   `tmp/eval-corpora/<dataset>/`). Currently `materialize` is a separate
   subcommand that the agent must invoke first.
3. After adding the watched root, `jseval run` should poll `/api/status` until
   `indexState=IDLE` AND `indexedDocCount >= expected_corpus_size`. The
   doc-count floor prevents the race where IDLE is reached before the watcher
   enumerates files.
4. For modes that need SPLADE (`splade`, `bm25_splade`, `full`), also wait for
   `spladeCoveragePercent >= 99.9`.
5. For modes that need dense (`hybrid`, `dense`, `full`), also wait for
   `chunkVectorsReady=true`.

The goal: `jseval run --dataset scifact --modes lexical,full` should be fully
self-contained — no manual curl, no separate materialize step, no manual
readiness polling.

### Bug 2 (cosmetic): Pipeline tracking false mismatch on lexical mode

`lexical` mode reports `"requested_sparse_but_not_observed"` in
`pipeline_tracking.mismatch_reasons`. The query executes correctly via BM25
(sparse retrieval) and returns correct results, but the provenance tracking
doesn't see a `"sparse"` tag in the response debug info.

**Desired behavior**: `lexical` mode should report clean pipeline tracking
with no mismatch warnings. Either:
- The backend should tag BM25 results with `"sparse"` in debug metadata, or
- jseval's provenance check should recognize that `lexical` mode uses BM25
  (which IS sparse retrieval) and not flag its absence.

### Operational finding: per-corpus isolation requires backend restarts

Each corpus eval needs a clean index. The current workflow requires stopping
the backend, cleaning the data directory, restarting, re-indexing. This is
~2-3 minutes of overhead per corpus swap.

**Desired behavior**: `jseval run` should handle corpus isolation automatically.
Options (in order of preference):

1. **Use `--data-dir` to point the backend at a per-corpus data directory.**
   If jseval could start/stop the backend itself (via Gradle `runHeadlessEval`
   subprocess or a lifecycle API), it could manage isolation end-to-end.
2. **Expose an index-reset API** (`DELETE /api/indexing/index` or similar) that
   drops the current index and allows re-indexing without a backend restart.
3. **At minimum**, document the manual workflow clearly in `jseval --help` or
   a README so agents know the steps.

Option 1 is ideal — `jseval run --dataset scifact --modes lexical,full` would
start a fresh backend, materialize, index, query, score, stop the backend, and
leave artifacts. A subsequent `jseval run --dataset mixed/courtlistener-200`
would do the same with a separate clean backend instance. No manual intervention.

### Issue 3 (minor): `--base-url` default is wrong

The CLI defaults to `http://127.0.0.1:8080` but the dev stack uses port 33221
(`JUSTSEARCH_API_PORT`). Every invocation requires `--base-url http://127.0.0.1:33221`.

**Desired behavior**: Default to `http://127.0.0.1:33221` (the dev stack port),
or read from `JUSTSEARCH_API_PORT` environment variable.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 61 days at audit time.

