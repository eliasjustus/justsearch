---
description: "TRIGGER when: running eval datasets, profiling indexing pipeline, measuring throughput, polling backend status, writing bash/node scripts to monitor /api/status, comparing run results, ingesting test corpora, waiting for enrichment readiness, checking search quality metrics, benchmarking indexing speed, or verifying pipeline changes with live data. Also TRIGGER when about to use curl to poll the backend, write a polling loop, or time a pipeline run manually. Use jseval instead of ad-hoc scripts. If jseval is missing a feature you need, propose an improvement to jseval rather than building a workaround."
user-invocable: true
---

# jseval — Search Evaluation & Pipeline Profiling

Use `python -m jseval` for ALL evaluation, profiling, and benchmarking.
Do NOT write ad-hoc bash/node scripts. If jseval can't do what you need,
**improve jseval** (`scripts/jseval/`) rather than building a workaround.

## Benchmark model-cost policy (current — 2026-06-23)

**Run agentic / utility benchmarks on cheap models only (haiku-class) for now, to save money.** Haiku is the
cheapest agent tier by a wide margin — ≈3× cheaper per query than sonnet, ≈13× cheaper than opus (tempdoc 624
§R6: ~$0.12/q haiku vs ~$0.45 sonnet vs ~$1.80 opus). Every eval command already defaults to `--model haiku`;
keep it there.

- **Higher batch on the cheap model is fine — encouraged, even.** More queries + seeds buy statistical power
  (McNemar significance, a tighter seed envelope) and haiku makes them affordable: a 100q × 3-condition ×
  5-seed run is ≈ **$200**. Scale n and seeds freely on haiku.
- **Multi-tier (sonnet / opus) sweeps require explicit user budget sign-off.** That is the expensive part — a
  full 3-tier matrix is ~$1.8–3.6k, dominated by opus (~13× haiku). Do not run sonnet/opus benchmarks without
  the user authorizing the spend.
- Rationale: developers accept a cheap-model benchmark for cost reasons; the marginal value of expensive tiers
  (cross-model generalization + an accuracy claim) is gated on budget *and* a contamination-resistant corpus
  (tempdoc 635). Lead with token-efficiency — it is contamination-robust and already significant at floor scale.

<!-- generated:start — do not edit between markers; run: node scripts/docs/skills-sync.mjs -->

<!-- source: docs/reference/jseval-pipeline-reference.md -->

# jseval Pipeline Reference

`python -m jseval` is the canonical **agent-only** tool for dataset
evaluation, pipeline profiling, and throughput benchmarking. It is not
designed for human developers — all output and progress reporting is
optimized for machine consumption. Agents should use jseval for ALL
eval/profiling work instead of ad-hoc bash/node scripts. When jseval
lacks a feature, improve it (`scripts/jseval/`) rather than building
workarounds.

## Quick Reference

### Ingest + eval (most common)

```bash
# Ingest SciFact, wait for ALL enrichments, run queries
python -m jseval run --dataset scifact --modes lexical,hybrid --pipeline

# Quick iteration (10 queries, skip ingest)
python -m jseval run --dataset scifact --modes hybrid --max-queries 10 --skip-ingest

# Full lifecycle: start backend, clean data, ingest, wait, query, stop
python -m jseval run --dataset scifact --modes lexical --pipeline \
  --start-backend --clean --timeline tmp/timeline.tsv

# Full lifecycle with LLM (Brain/llama-server) enabled
# -Pllm=true auto-detects llama-server from the dev layout
python -m jseval run --dataset multihop --modes hybrid --pipeline \
  --start-backend --llm --clean

# From YAML config file
python -m jseval run --config eval-run.yaml --start-backend
```

### Compare runs

```bash
# Compare two eval runs for regression
python -m jseval compare tmp/eval-results/run-a tmp/eval-results/run-b

# Fail CI on regression (includes pipeline timing comparison)
python -m jseval compare run-a run-b --fail-on-regression
```

### Benchmarks

```bash
# Indexing throughput (Claim B)
python -m jseval ingest-bench --corpus-dir tmp/eval-corpora/scifact

# Engine-only indexing (Claim A)
python -m jseval engine-bench --corpus <path>

# kNN latency
python -m jseval knn-bench
```

### Standing ratchets (engine-quality gates)

Four **relative** regression ratchets (no absolute SLO) catch silent engine regressions; the `search-engine-hint`
hook nudges them after engine/inference edits. All share `jseval/ratchet_kernel.py` (load baselines → resolve run →
compare → report) and project their floors from a canonical source (never hand-typed).

```bash
# Relevance (nDCG@10 mean) — floor projects from release.v1.json
python -m jseval relevance-gate --data-dir <dir> --dataset beir/scifact
# Performance (CE-stage p50 latency / throughput / resident footprint) — floor projects from release.v1.json
python -m jseval perf-gate      --data-dir <dir> --dataset scifact
# Recall-leak (cross-mode leak_rate — a leg's correct answer dropped before the judge; needs leg-modes run)
python -m jseval leak-gate      --data-dir <dir> --dataset beir/scifact
# LLM-generation latency/throughput (TTFT / e2e / tokens-sec) — needs a bench, not an eval run; AI must be active
python -m jseval llm-bench --base-url <api-url> --output-dir <d> && python -m jseval llm-gate --bench-file <d>/llm-bench.json
```

Re-pin after a deliberate change: `perf-gate --update-baseline` (re-pins from the run); `leak-gate-derive --datasets
<slugs>`; `llm-gate --bench-file <f> --update-baseline`. Relevance re-baselines when `release.v1.json` is recomposed
(`jseval release --latest-per-dataset`). Floor files: `scripts/jseval/{relevance,perf,llm-gen}-ratchet-baselines.v1.json`
+ `leak-gate-baselines.v1.json`. Exit codes: 0 = within band, 1 = regression, 2 = data/projection missing.

### Diagnostics

```bash
# Check backend health, models, GPU before running eval
python -m jseval preflight

# Discover worker.log path from running backend
python -m jseval log-path

# List available datasets and modes
python -m jseval datasets
python -m jseval modes
```

### Interactive development (360)

```bash
# Start eval backend and keep running until Ctrl-C (attaches if already running)
python -m jseval dev [--clean]

# Send a single search and show full pipeline execution (CE status, timing)
python -m jseval search --query "vitamin D" [--mode hybrid] [--ce] [--json]

# Tail Worker/Head logs with structured filtering
python -m jseval logs [--source worker|head] [--filter rerank] [--tail] [--level WARN]
```

### Observability (tempdoc 400 Layer 1/4/5)

Post-§23 closure, jseval is the single CLI surface for every piece of
tempdoc 400 observability. Every subcommand below writes to the
configured eval-results / cohort_baselines layout; see
`docs/explanation/08-observability.md` for the data-dir schema.

```bash
# Calibrate cross-run non-determinism envelope (LR1-b)
python -m jseval calibrate --dataset scifact --modes full --runs 5 \
  --max-queries 50 --data-dir <path>

# Capture drift baseline from N warm runs (LR4-g, Phase 6/6.2 opt-in)
# Requires >= 3 runs at stable SHA; blocks cold-start outliers
python -m jseval calibrate-drift-baseline --cohort-hash H \
  --data-dir <path> --from-runs R1 R2 R3

# Extract sigma from an existing envelope for nightly-baseline refresh
python -m jseval recalibrate-nightly-baseline --data-dir <path> \
  --cohort-hash H [--output env.txt]

# Nightly-style quality gate (Phase 6/6.13; was scripts/ci/phase3_*)
python -m jseval gate --data-dir <path> --baseline-stdev 0.00108 \
  --tolerance-pct 10 [--report-out <json>]

# Layer-5 experiment runners
python -m jseval counterfactual --dataset scifact --max-queries 50
python -m jseval shadow-eval --dataset scifact \
  --policy-a <a.json> --policy-b <b.json>
python -m jseval bench-concurrency --dataset scifact --concurrency 4 \
  --max-queries 50 [--warmup N]
python -m jseval bisect --run-a <run_dir> --run-b <run_dir> \
  [--synthesize --dataset scifact --modes full --dry-run]
```

**Operator guides:** `docs/how-to/recalibrate-phase3-baseline.md`,
`docs/how-to/calibrate-drift-baseline.md`,
`docs/how-to/interpret-bisect-output.md`,
`docs/how-to/triage-psi-drift.md`,
`docs/how-to/envelope-staleness-policy.md`.

## Available Datasets

Use `python -m jseval datasets` to list all available datasets,
including local mixed/golden corpora discovered on disk.

| Slug | Source | Notes |
|------|--------|-------|
| `scifact` | BEIR | 5183 docs, 300 queries, academic |
| `nfcorpus` | BEIR | nutrition/health |
| `arguana` | BEIR | argumentation |
| `fiqa` | BEIR | financial QA |
| `webis-touche2020` | BEIR | controversial topics |
| `mixed/<name>` | local | Scanned from `datasets/mixed/` |
| `mixed/ohr-bench-clean` | local | OHR-Bench ground-truth text (7 domains, 1000 docs, 962 queries) |
| `mixed/ohr-bench-tika-pdf` | local | OHR-Bench original PDFs through Tika StructuredContentExtractor |
| `mixed/ohr-bench-got-moderate` | local | OHR-Bench GOT OCR extraction (moderate noise) |
| `mixed/ohr-bench-mineru-moderate` | local | OHR-Bench MinerU extraction (moderate noise) |
| `golden/<name>` | local | Scanned from `datasets/golden/` |

## Available Modes

Use `python -m jseval modes` to list all modes with their components.

| Mode | Resolution | Components |
|------|-----------|------------|
| `lexical` | client | sparse (BM25) |
| `vector` | client | dense |
| `splade` | client | SPLADE |
| `bm25_splade` | client | sparse + SPLADE |
| `dense_splade` | client | dense + SPLADE |
| `full` | client | sparse + dense + SPLADE |
| `hybrid` | server | sparse + dense + RRF + LambdaMART |

Client-resolved modes send an explicit pipeline config. Server-resolved
modes (like `hybrid`) send a mode string for backend resolution.

## What jseval Handles

- **Corpus materialization**: Downloads and converts datasets to .txt
- **Ingestion**: Adds watched root, waits for file watcher to start
- **Readiness wait**: Polls `/api/status` with progress logging every
  30s (embedding %, SPLADE %, NER count, chunk %, GPU %, VRAM, heap)
- **Pipeline wait**: `--pipeline` waits for ALL enrichment stages
  (embedding, SPLADE, chunks, NER) to reach completion
- **Timeline recording**: `--timeline out.tsv` captures status snapshots
  with GPU/VRAM/enrichment counters per row
- **Pipeline summary**: Per-stage completion times written to
  `summary.json → pipeline_timing`
- **Query execution**: Runs queries, computes nDCG@10/P@1/R@10
- **Result comparison**: A/B diff with per-query rank analysis and
  pipeline timing comparison
- **Backend lifecycle**: `--start-backend` starts runHeadlessEval,
  `--clean` wipes data dir, auto-stops via taskkill on completion.
  `--llm` enables Brain/llama-server with autostart and extended
  health timeout (waits for model load + inference readiness).
  Auto-detects llama-server from the dev layout; override with
  `JUSTSEARCH_SERVER_EXE` if needed. **Cold starts may fail once**
  (Worker port discovery races with GGUF disk read) — retry resolves it
- **Index reset**: `--reset` calls `POST /api/debug/reset-index` before
  ingestion — wipes index without process restart (requires running
  backend in eval mode). Mutually exclusive with `--start-backend`.
- **Preflight checks**: `jseval preflight` reports loaded models, GPU
  status, enrichment coverage, and `embedding_model_sha256` from
  commit metadata
- **YAML config**: `--config run.yaml` for reproducible runs with GPU
  settings, dataset, modes in a single file
- **Crash detection**: Fails fast after 5 consecutive status fetch
  failures; checks `meta.workerRpcStale` for Worker-down detection
- **NDJSON progress**: `--json` emits structured progress objects to
  stderr and the final result to stdout
- **Output**: Structured `summary.json` with metrics, git SHA,
  pipeline timing, comparability tracking

## Key Flags

| Flag | Effect |
|------|--------|
| `--pipeline` | Wait for ALL enrichments (implies `--embedding --splade`) |
| `--embedding` | Wait for embedding coverage ≥ 99.9% |
| `--splade` | Wait for SPLADE coverage ≥ 99.9% |
| `--start-backend` | Start runHeadlessEval, stop when done |
| `--llm` | Enable LLM/llama-server in backend (requires `--start-backend`) |
| `--clean` | Clean data dir before start (requires `--start-backend`) |
| `--reset` | Reset index via API before ingestion (eval mode, no restart) |
| `--timeline PATH` | Record status snapshots to TSV during wait |
| `--config PATH` | Load YAML run configuration file |
| `--max-queries 0` | Ingest only, no queries (pipeline profiling). When 0, `--modes` is not required. Scoring uses filtered qrel count (only evaluated queries), not full corpus query count (353). |
| `--skip-ingest` | Query only, skip materialization and ingestion |
| `--corpus-dir PATH` | Use existing corpus dir as-is (no materialization) |
| `--allow-errors` | Continue on query errors (don't abort run) |
| `--lambdamart` | Enable LambdaMART reranking check |
| `--json` | NDJSON progress to stderr, JSON result to stdout |
| `-v` / `--verbose` | DEBUG logging (httpcore/httpx suppressed) |
| `--history-db PATH` | Shared history database for trend tracking |

## Output Structure

```text
tmp/eval-results/<timestamp>_<dataset>/
  summary.json            # Metrics, config, git SHA, pipeline timing
  <mode>_per_query.json   # Per-query scores and ranks
  <mode>_run.trec         # TREC-format run file
```

`summary.json` fields agents typically need:
- `per_mode.<mode>.aggregate_metrics["nDCG@10"]` — headline quality
- `per_mode.<mode>.pipeline_tracking.observed` — which retrieval legs ran
- `per_mode.<mode>.comparable` — whether metrics are trustworthy
- `pipeline_timing.stages` — per-stage completion times (when `--pipeline`)
- `pipeline_timing.inference.<stage>.total_ms` — cumulative ORT inference wall time (350)
- `pipeline_timing.inference.<stage>.batches` — batch count for this stage (350)
- `pipeline_timing.inference.<stage>.avg_ms_per_batch` — average per-batch time (350)
- `pipeline_timing.encoder_profiles.<encoder>.calls` — total ORT inference calls (357)
- `pipeline_timing.encoder_profiles.<encoder>.ort_p50_us` — ORT call latency p50 in microseconds (357)
- `pipeline_timing.encoder_profiles.<encoder>.ort_p95_us` — ORT call latency p95 (357)
- `pipeline_timing.encoder_profiles.<encoder>.ort_p99_us` — ORT call latency p99 (357)
- `pipeline_timing.encoder_profiles.<encoder>.phases` — per-phase cumulative time map (357)
- `pipeline_timing.encoder_profiles.<encoder>.seq_len` — sequence length stats (357)
- `pipeline_timing.primary_indexing.docs_per_s` — indexing rate
- `ingest.worker_throughput_docs_per_sec` — primary indexing throughput
- `search_config` — active search pipeline config snapshot from `/api/status` (343)
- `env_overrides` — env vars applied by jseval config that differed from defaults (343)
- `git_sha` — for reproducibility

## YAML Run Config

```yaml
dataset: scifact
modes: [lexical, hybrid]
embedding: true
splade: true
pipeline: true
max_queries: 0
output_dir: tmp/eval-results

gpu:
  embed:
    enabled: true
    layers: 32
    mem_mb: 2048
  splade:
    enabled: true

backend:
  clean: true
  llm: true

# Passthrough env vars (arbitrary)
env:
  JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY: REINDEX
```

## Source Code

jseval lives at `scripts/jseval/`. Key files:
- `jseval/_paths.py` — Canonical path constants (`REPO_ROOT`, default output dirs)
- `jseval/types.py` — Shared types (`IngestConfig` dataclass for parameter threading)
- `jseval/cli.py` — Click CLI, subcommand registration
- `jseval/run.py` — Eval run orchestration, summary building
- `jseval/ingest.py` — Corpus ingestion, readiness wait
- `jseval/readiness.py` — Status polling, readiness conditions, progress logging
- `jseval/retriever.py` — Query execution, doc ID resolution
- `jseval/scoring.py` — ir-measures wrapper for nDCG/AP/RR
- `jseval/corpora.py` — Dataset registry, BEIR + local loading
- `jseval/timeline.py` — Timeline recording, pipeline summary computation
- `jseval/preflight.py` — Backend health and model identity checks
- `jseval/backend.py` — Backend lifecycle (start/stop)
- `jseval/run_config.py` — YAML config loading and env mapping
- `jseval/compare_runs.py` — Statistical comparison with pipeline timing
- `jseval/provenance.py` — Per-hit and per-run evidence extraction
- `jseval/artifacts.py` — Output file writing (JSON, TREC)

When improving jseval, follow existing patterns in these files.

<!-- generated:end -->
