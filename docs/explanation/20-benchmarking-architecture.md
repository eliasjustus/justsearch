---
title: Benchmarking Architecture (Claims + Competitor Suite)
type: explanation
status: stable
description: "Claims vs lanes, bench harness schemas, competitor suite logic."
---

# 20. Benchmarking Architecture (Claims + Competitor Suite)

JustSearch treats performance numbers as **reviewable artifacts** (JSON + markdown), not console output. This doc explains the *code and logic* behind the benchmarking system so results stay repeatable, diffable, and interpretable.

This doc is about **benchmarks** (throughput + microbenches + cross-tool competitor suite).

The artifact/timing contract is realized by the benchmark harness and the JSON/markdown artifacts it emits (see the runners under `scripts/bench/`).

---

## 1) Core concepts and vocabulary

### Claims (JustSearch-only)

Claims are stable “what are we measuring?” buckets. They exist because end-to-end product throughput is intentionally *not* the same thing as the engine ceiling.

Implemented under `scripts/bench/`:

- **Claim A (Engine-only):** isolated Lucene runtime throughput (no Worker queue, no extraction).
- **Claim B (Pipeline):** watched root → searchable (includes queue + extraction + indexing).
- **Claim C (UX under load):** UI responsiveness while indexing (integrated into EBv1 perf).
- **Claim D (LLM inference):** inference throughput/latency for llama-server style backends.

Claim A/B results use schema `scripts/bench/schemas/bench-result.v1.schema.json`.

### Lanes (cross-tool / “competitor” harness)

Lanes are the competitor-suite equivalent of “what category of thing are we comparing?”:

- **Lane T (full-text):** ingest text corpus + keyword search latency.
- **Lane V (vector store):** ingest pre-embedded vectors + kNN search latency (+ recall gating).
- **Lane P (pipeline):** reserved for end-to-end comparisons (usually *not* apples-to-apples across unrelated apps).
- **Lane F (filename/metadata):** reserved.

Competitor results use schema `scripts/bench/schemas/competitor-bench-result.v1.schema.json` and suite summaries use `scripts/bench/schemas/competitor-suite.v1.schema.json`.

### Artifacts (where outputs live)

Bench tooling writes into `tmp/` by default:

- Claim runs: `tmp/bench/claim-{a,b,d}/<timestamp>/result.json`
- Competitor raw runs: `tmp/bench/competitors/<timestamp>/<tool>/<case>/run-*/result.json`
- Suite summaries: `tmp/bench/_summaries/*-suite-*.{json,md}`
- Repo baselines: `scripts/bench/baselines/*.json` (committed)

### Corpus Governance (v1)

Benchmark lanes now use governed multi-corpus identity with canonical registry + lane policy:

- corpus registry: `scripts/bench/corpora/corpus-profiles.v1.json`
- lane policy: `scripts/bench/corpora/lane-corpus-policy.v1.json`
- license BOM: `scripts/bench/corpora/license-bom.v1.json`

Lane wrappers resolve corpus selection before execution and stamp corpus identity fields into emitted manifests:

- `corpus_profile_id`
- `corpus_signature`
- `corpus_components`
- `corpus_tier`

For `bench-suite.v2`, corpus identity is written into `workload.*`.
For wrapper manifests, corpus identity is written at top-level and mirrored into `threshold_context.threshold_profile`.

Comparability contract:

- diff tools mark baseline/candidate corpus identity mismatch as `NON_COMPARABLE`.
- promotion tooling refuses baseline promotion for governed lanes when required corpus identity is missing or mismatched.
- `claim-d` remains corpus-policy exempt.

---

## 2) How the bench runners are structured

### Layering

The system is intentionally split so that:

- **Node DAG runners** orchestrate CI/report/gate flows and cross-lane execution graphs.
- **PowerShell wrappers** provide compatibility entrypoints and host-specific ergonomics where needed.
- **Java** provides low-noise “inner loop” benches for JustSearch engine components.
- **Node** also provides corpus/query generation, conversion, diff, and summarization utilities.

Key entry points:

- JustSearch benches (Java): `modules/benchmarks/src/main/java/io/justsearch/benchmarks/*`
- Claim suites (PS): `scripts/bench/run-claim-*-suite-win.ps1`
- Competitor suite (PS): `scripts/bench/run-competitor-suite-win.ps1`
- CI/report orchestration (Node DAG): `scripts/ci/dag-runner-*.mjs`, `scripts/bench/dag-runner-*.mjs`
- Suite diffs (Node): `scripts/bench/diff-*-suite.mjs`

### Common measurement contract: “searchable” means sentinel-validated

Across Claim A/B and competitor lanes, “indexing completed enough to measure” is defined by a **sentinel**:

- A known doc/query (typically `__bench_sentinel__`) must return hits.
- `time_to_searchable_ms` is measured as “ingest start → sentinel hit”.

This is the single most important rule for avoiding bogus throughput claims (e.g., timing that stops before the index is queryable).

---

## 3) Claim A/B/C/D implementation map

### Claim A: EngineIndexBench

- Java runner: `modules/benchmarks/src/main/java/io/justsearch/benchmarks/EngineIndexBench.java`
- PS wrapper: `scripts/bench/engine-index-bench-win.ps1`
- Suite: `scripts/bench/run-claim-a-suite-win.ps1`

Input is an NDJSON file (usually from BEIR tooling) and the bench measures Lucene runtime throughput + sentinel validation.

### Claim B: pipeline-bench (HTTP)

- PS runner: `scripts/bench/pipeline-bench-win.ps1`
- Suite: `scripts/bench/run-claim-b-suite-win.ps1`

This talks to the running dev stack and measures watched-root indexing end-to-end. It waits for “idle/quiescent” and then validates sentinel searchability.

Important pitfall: “breath-holding” means tight interactive polling can alter the workload. Prefer waiting on status endpoints and validating searchability as a single check.

### Claim D: LLM inference

- PS runner: `scripts/bench/llm-bench-win.ps1`
- Suite: `scripts/bench/run-claim-d-suite-win.ps1`

### Track G: filtered kNN + quantization gates

Track G exists to keep “semantic retrieval tails” measurable on real Windows/MMAP environments:

- PS runner: `scripts/bench/run-track-g-suite-win.ps1`
- Java runners: `FilteredKnnBench`, `VectorQuantizationGate`, `RerankerDeadlineBench`

---

## 4) Competitor suite architecture (Lane T / Lane V)

### 4.1 Runner responsibilities

`scripts/bench/run-competitor-suite-win.ps1`:

1) Selects/derives the query workload (and always includes a sentinel query).
2) Optionally loads query vectors (`-QueriesVectorsNdjson`) for Lane V.
3) Optionally generates a ground-truth KNN file for Lane V recall (`-ComputeRecall`).
4) Sweeps ANN knobs (cartesian product of `m × ef_construction × ef_search`) subject to `-AnnMaxVariantsPerTool`.
5) Runs each tool adapter N times, then aggregates stats and writes a suite summary.

### 4.2 Case IDs, variants, and aggregation semantics

#### `case_id` format

Each aggregated entry in the suite summary has a `case_id`:

```text
<lane>:<tool_id>:<corpus_id>[:<case_suffix>]
```

Example:

```text
V:qdrant:synth-20k-768:ann_m16_efc200_efs400
```

#### ANN variant suffixes

When the runner performs ANN sweeps, it appends a suffix that encodes the requested knobs:

- `ann_m<M>_efc<EF_CONSTRUCTION>` and
- optionally `_efs<EF_SEARCH>` (or an “unset” baseline case when `ef_search` is not provided)

The exact meaning of “ef_search” is tool-specific (see §7.2), so the suffix is primarily for auditability/repro.

#### Aggregation rules (how the suite summary is computed)

For each case, the runner attempts `-Runs` runs. A run is discarded (and recorded as a failed run) if:

- `sentinel_validated=false`, or
- `-MinRecallAtK > 0` and `lane=V` but `recall_at_k` is missing or below the threshold.

Important details:

- A case is marked `ok=true` if **at least one** run succeeded (it does not require all runs to succeed).
- `key_stats.*` are **medians over successful runs** for each metric.
- `statistics.*` contain min/max/stddev and simple confidence bounds over successful runs.
- Query percentiles (`query_p50_ms` / `query_p95_ms`) are computed by the adapter per run, and then the suite aggregates those per-run percentiles (median of p95s, not “p95 over all raw query samples”).
- If adapters report a `timings_ms` breakdown, the suite aggregates each key into `timings_ms_statistics` (median, etc).

### 4.3 Adapter contract

Each adapter is a PowerShell script in `scripts/bench/competitors/*.ps1` and must:

- Run headlessly (no GUI) and be pinnable to a version.
- Write `result.json` into `-OutDir` using `competitor-bench-result.v1`.
- Populate at least:
  - `lane`, `tool.id`, `corpus_id`
  - `metrics.time_to_searchable_ms`, `metrics.docs_per_s`
  - `sentinel_validated` (and fail closed if sentinel validation fails)
- Preferably include:
  - `metrics.query_p50_ms` / `metrics.query_p95_ms` (query suite percentiles)
  - `metrics.index_size_bytes` (if measurable)
  - `timings_ms` stage breakdowns
  - `timing_contract` explaining what is included in `time_to_searchable_ms`
  - `knobs` (versioning, batch size, ANN knobs, metric, etc.)

Reference adapters:

- Lane T: `scripts/bench/competitors/justsearch-claim-a.ps1`, `meilisearch.ps1`, `zincsearch.ps1`, `recoll.ps1`
- Lane V: `scripts/bench/competitors/justsearch-vector.ps1`, `qdrant.ps1`, `duckdb-vss.ps1`

---

## 5) Corpus + query formats (what inputs the code expects)

### 5.1 Text corpus (Lane T / Claim B)

Directory of `.txt` files with a sentinel file named `__bench_sentinel__.txt`.

### 5.2 NDJSON corpus (Claim A)

Claim A expects an NDJSON file from the BEIR tooling (example fields vary by dataset/tooling), plus a sentinel doc id in corpus stats.

### 5.3 Vector corpus (Lane V)

Lane V uses a **pre-embedded vector corpus** in NDJSON. Canonical generators:

- `scripts/bench/make-embedded-corpus.mjs` (embed text → vectors)
- `scripts/bench/make-synthetic-vector-corpus.mjs` (synthetic unique vectors)

Expected per-line shape:

```json
{"doc_id":"__bench_sentinel__","vector":[0.1,0.2,...]}
```

Adapters may allow additional fields, but `doc_id` and `vector` are the interoperability contract.

### 5.4 Query vectors (Lane V latency + recall)

For Lane V latency, prefer supplying query vectors:

- Generator: `scripts/bench/make-embedded-queries.mjs`
- Runner flag: `-QueriesVectorsNdjson tmp/bench/_corpora/<...>.queries.vectors.ndjson`

Expected per-line shape:

```json
{"query_id":"q-000001","text":"example query","vector":[0.1,0.2,...]}
```

The runner can derive a keyword query workload (`queries.txt`) from `text` fields when needed.

---

## 6) Lane V correctness: truth generation + recall@k

Lane V numbers are only meaningful when **accuracy is reported**.

### 6.1 Ground truth artifact

The competitor suite can generate an exact top-k truth file:

- Qdrant exact (medium corpora): `scripts/bench/make-truth-knn-qdrant-win.ps1` (collection uses `exact: true`)

The resulting truth file is passed to adapters as `-TruthKnnNdjson`, and adapters report:

- `metrics.recall_k`
- `metrics.recall_at_k` (mean recall@k across evaluated queries)
- `metrics.recall_query_count`

The suite runner can enforce a correctness gate with `-MinRecallAtK`.

### 6.2 Distance metric alignment (why normalization exists)

Vector backends vary in default similarity:

- Lucene defaults to EUCLIDEAN if not explicitly set.
- Qdrant frequently uses Cosine.
- DuckDB-vss supports cosine.

For cross-tool comparability, we typically **L2 normalize vectors** (doc and query vectors). With normalized vectors, Euclidean vs cosine vs dot-product induce the same ranking, making recall@k comparisons meaningful.

---

## 7) ANN knob reporting, sweeps, and semantics

### 7.1 What the runner sweeps

The competitor runner can sweep:

- `HnswM` (graph degree)
- `HnswEfConstruction` (build-time breadth)
- `HnswEfSearch` (query-time breadth; semantics differ per tool)

The runner records the sweep in the suite as `ann_sweep` and expects adapters to record effective knobs in `knobs`.

### 7.2 “ef_search” is not a single universal knob

The suite treats `ef_search` as “the query-time accuracy/speed knob”, but implementations differ:

| Tool | What `ef_search` means in our harness |
|------|----------------------------------------|
| JustSearch (Lucene vectors) | Implemented as **oversample-k**: run kNN with `k=max(limit, ef_search)` and then return only `limit` hits (Lucene sizes its candidate queue to query `k` and does not expose a separate efSearch knob). |
| Qdrant | Maps to `hnsw_ef` (search breadth). |
| DuckDB-vss | Maps to HNSW search parameter (`ef_search`). |

Implication: “same ef_search number” is not the comparison. The comparable artifact is the **recall@k vs latency curve**, produced via sweeps.

---

## 8) Interpreting results (what is safe to claim)

Recommended interpretation rules:

1) **Always report recall with latency** for Lane V.
2) Prefer reporting a curve or a Pareto frontier over a single number.
3) Treat `index_size_bytes` as a within-tool regression signal unless/until a size contract is defined (storage layers and preallocation differ).
4) Prefer medians over single runs; record run counts and variance.
5) Be explicit about timing boundaries via `timing_contract` (especially for server startup vs ingestion vs indexing vs sentinel validation).

For the current Lane V philosophy and "true numbers" selection, see the competitor-suite runners under `scripts/bench/`.

---

## 9) How to add a new competitor adapter (design checklist)

1) Implement `scripts/bench/competitors/<tool>.ps1` with the common parameter surface (see existing adapters).
2) Make it headless + pinnable:
   - Download a pinned binary into `tmp/bench/_tools/<tool>/<version>/` (or require a user-provided install path, but record it in `knobs`).
   - Write the observed version into `tool.version`.
3) Define a clear timing contract:
   - Start time and end time must match the lane contract (“ingest start → sentinel hit”).
   - Exclude startup only if you document it via `timing_contract`.
4) Emit a `competitor-bench-result.v1` JSON payload and include useful `knobs` + `timings_ms`.
5) If the tool supports vectors:
   - Accept query vectors and use them for latency percentiles.
   - Support truth KNN → recall reporting (or opt out explicitly and fail if `-MinRecallAtK` is requested).
