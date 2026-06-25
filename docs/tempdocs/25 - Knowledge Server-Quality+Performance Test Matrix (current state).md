---
title: "29 - Knowledge Server: Quality + Performance Test Matrix (current state)"
type: tempdocs
status: draft
updated: 2026-01-13
---

> NOTE: Noncanonical “working” doc. The canonical perf harness docs live under `scripts/perf/` and `docs/explanation/*`.

## Purpose

Make the Knowledge Server (Head ↔ Worker) **measurably correct, fast, and regressions-catchable**.

This doc is a checklist of:

- **What tests/harnesses already exist** in-repo (verified),
- **What tests are still missing** to make this a real “quality/perf gate” (recommended additions),
- How to run the main lanes locally on Windows.

## Scope (what we mean by “Knowledge Server”)

Primarily the local-loopback API + Worker indexing/search stack:

- **Indexing**: watched roots + ingest, queue processing, commits, migrations
- **Search**: `POST /api/knowledge/search` (lexical + hybrid)
- **Status/health/telemetry**: `GET /api/status`, `GET /api/knowledge/status`, `GET /api/health`, telemetry NDJSON
- **Inference mode**: `GET /api/inference/status`, `POST /api/inference/mode` (relevance mostly indirect; affects resource contention)

Out of scope (but related): full UI UX, offline AI pack import, and non-Knowledge APIs.

## Hard invariants (do not violate)

- **Head must not touch Lucene index files or SQLite job queue files** (delegate IO to Worker via gRPC).
- **Loopback-only posture** must remain intact (Local API is not a network service).
- **Do not reintroduce removed legacy endpoints** (e.g., `/api/search`, `/api/settings`).

## Quality dimensions (what we must test)

- **Correctness**: endpoints behave, watched roots work, indexing completes, paging cursor is stable, migrations are safe.
- **Relevance**: returned ranking is “good enough” and does not regress (nDCG/Recall + human sanity checks).
- **Performance**:
  - **Indexing throughput** (docs/sec, MB/sec) and stage-level timings
  - **Search latency** (route histograms; p50/p95)
  - **Cold start readiness**
- **Resilience**: worker restarts, mode switching, backpressure behavior.
- **Resource usage**: CPU/RAM/disk; **GPU usage** when configured (especially for embeddings).
- **Observability**: metrics and debug snapshots are present and interpretable.

## Recent findings / issues (Jan 2026)

### Indexing throughput: measured deltas (GPU vs CPU embeddings)

We ran the official perf harness **Scenario B1** (`scenario_b1_indexing_throughput`) which:

- Generates a deterministic synthetic corpus under the active `justsearch.data.dir` (`testdata/scenario_b1_indexing_throughput`)
- Calls `POST /api/knowledge/ingest`
- Measures wall time until Worker stage metrics show the corpus completed `extract/analyze/write` and at least one `post_commit`
- Writes `attachments/perf/scenario-b1-throughput.json` inside the EBv1 bundle.

**Important caveat:** the default knobs are *tiny* (`doc_count=20`, `bytes_per_doc=4096`), so `MB/s` is not representative. For meaningful absolute throughput, increase both doc count and doc size (see below).

Measured on Windows with an RTX 4070 using `nomic-embed-text-v1.5.Q4_K_M.gguf`:

- **GPU embeddings** (`JUSTSEARCH_EMBED_GPU_LAYERS=32`):
  - `ingest_wall_ms=11043`, `docs_per_s=1.81`, `mb_per_s=0.0071` (20 docs @ ~4KB)
  - Worker log confirms `Loading embedding model ... [mode=GPU (32 layers)]` and CUDA backend loaded.
- **CPU-only embeddings** (`JUSTSEARCH_EMBED_GPU_LAYERS=0`):
  - `ingest_wall_ms=55478`, `docs_per_s=0.36`, `mb_per_s=0.0014` (same corpus)
  - Worker log confirms `... [mode=CPU-only]`

Net: **~5× higher docs/s** with GPU offload vs CPU-only in the same harness run.

Repro (PowerShell):

```powershell
# Common: force the embed model path (so results are comparable run-to-run).
$env:JUSTSEARCH_MODEL_PATH = 'D:\code\JustSearch\models\nomic-embed-text-v1.5.Q4_K_M.gguf'

# GPU run (end-to-end indexing with GPU embeddings)
$env:JUSTSEARCH_LLM_ACCEL = 'cuda'
$env:JUSTSEARCH_EMBED_GPU_LAYERS = '32'
powershell -ExecutionPolicy Bypass -File scripts/perf/run-perf-suite-win.ps1 -SkipUiScenario -ScenariosCsv scenario_b1_indexing_throughput

# CPU-only run (end-to-end indexing with CPU embeddings)
$env:JUSTSEARCH_LLM_ACCEL = 'auto'
$env:JUSTSEARCH_EMBED_GPU_LAYERS = '0'
powershell -ExecutionPolicy Bypass -File scripts/perf/run-perf-suite-win.ps1 -SkipUiScenario -ScenariosCsv scenario_b1_indexing_throughput
```

To make `MB/s` meaningful:

```powershell
$env:JUSTSEARCH_B1_DOC_COUNT = '200'
$env:JUSTSEARCH_B1_BYTES_PER_DOC = '262144' # 256 KB
```

### Benchmarking vs “standards” / competitors (and confidence)

There is no single universal “indexing speed” number that’s comparable across products unless we define a lane.

- **Lane A (lexical-only indexing)**: raw text → Lucene (BM25). High confidence we can measure JustSearch repeatably (disable embeddings), and medium confidence we can compare fairly if we run competitors on the **same machine** + same corpus (e.g., Elasticsearch/OpenSearch via Rally).
- **Lane B (vector insertion throughput)**: precomputed vectors → ANN index. Medium confidence today because JustSearch currently benchmarks end-to-end (text→embed→index); a clean Lane B requires either an ingest path for **precomputed vectors** or a deterministic “fast embedder” that still writes vectors.
- **Lane C (end-to-end ingest)**: raw text → embeddings + lexical + vector index. High confidence for JustSearch numbers; medium confidence for competitor comparisons only if we standardize the embedding step (same model, same GPU/CPU, same batching) across systems.

### “Old tests” audit: some were doing real work, some were environment-dependent

We audited tests/harness code that hadn’t changed in >7 days and actually ran the suites. Findings:

- **Guardrail tests can still catch regressions**: `modules/app-services` ArchUnit guardrail failed until we removed direct `System.getProperty` usage from a non-allowlisted class (routed through `modules/configuration/SystemAccess`).
- **Environment-dependent tests must skip cleanly** (or they become noise):
  - `modules/system-tests` HTTP tests require a running server; VDU tests require an external `llama-server` with a VLM.
  - These now **skip via JUnit assumptions** when prerequisites aren’t present (instead of failing).
- **Native/model-dependent integration tests should be gated**:
  - `modules/ai-bridge` native integration tests require `JUSTSEARCH_LLM_MODEL_PATH` pointing at a real `.gguf`.
  - These are now disabled when the model path is missing (so `gradlew integrationTest` is runnable on a cold dev machine).

Net: `./gradlew test` and `./gradlew integrationTest` are now **safe to run locally** without external services, while the “full” lanes still exist when you deliberately bring up the required dependencies.

## What exists today (verified inventory)

### 1) Perf regression harness (EvidenceBundle v1) — the “apples-to-apples” lane

This is the closest thing to “search reranking eval” but for overall system perf.

- **Suite runner (Windows)**: `scripts/perf/run-perf-suite-win.ps1`
- **Capture harness**: `modules/ui-web/scripts/capture-evidence-bundle.mjs`
- **Report generator**: `modules/ui-web/scripts/lib/perf-report.mjs`
- **Diff + thresholds**: `scripts/perf/diff-perf-suite.mjs` + `scripts/perf/regression-thresholds.v1.json`

Key scenarios you can treat as “the standard”:

- **Scenario A**: cold start readiness (time to ready + first search/preview ok)
- **Scenario B1**: indexing throughput (synthetic corpus; docs/sec + MB/sec)
- **Scenario B2**: foreground responsiveness (verifies PAUSED observed; protects UX while indexing)
- **Phase 1**: backend route latency histograms
- **Phase 2**: UI-perceived latency (Playwright; optional)
- **Phase 3**: worker stage timings (`pipeline.stage_ms`)
- **Scenario E**: streaming TTFT
- **Scenario F**: mode switching (best-effort)
- **Scenario G**: worker restart → time-to-ready

Docs: `scripts/perf/README.md`

Notes / caveats:

- Scenario B1 measures “ingest wall time” from `POST /api/knowledge/ingest` until Worker stage metrics indicate the corpus completed. It is excellent for regressions, but absolute `MB/s` is only meaningful when you increase the corpus size.
- To tune Scenario B1 without changing scripts, set:
  - `JUSTSEARCH_B1_DOC_COUNT`
  - `JUSTSEARCH_B1_BYTES_PER_DOC`

If you care about “competitor comparisons”, agree on a lane (lexical-only vs vector-only vs end-to-end) before treating any number as meaningful.

### 2) HTTP contract/workflow integration tests (require a running server)

These live under `modules/system-tests` and validate the loopback API surface.

Important: these tests **do not start the server**. They now **skip** when the server is not reachable (so CI/dev runs don’t fail spuriously). To actually exercise them, start the server and pass `-Djustsearch.api.port=...` as documented in the test headers.

Examples (non-exhaustive):

- **Smoke**: `modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/api/HttpSmokeTest.java`
  - `/api/status`, `/api/knowledge/status`, `/api/knowledge/search`, `/api/health`, `/api/summarize`
- **Indexing root management**: `.../HttpIndexingWorkflowTest.java`
  - `/api/indexing/roots` (GET/POST/DELETE), `/api/indexing/reindex`
- **Cursor paging correctness**: `.../HttpPagingCursorE2ETest.java`
  - verifies `nextCursor` round-trip advances results with stable sort
- **Mode transitions**: `.../HttpModeTransitionTest.java`
  - `/api/inference/status` + `/api/inference/mode` behavior
- **Watched root/file watcher**: `.../HttpFileWatcherE2ETest.java`
- **Indexing E2E**: `.../HttpIndexingE2ETest.java`

### 3) Indexing correctness + migration/resilience system tests

Also under `modules/system-tests` (systemTest + process integration):

- `.../process/CompleteIndexingWorkflowE2ETest.java`
- `.../process/SyncDirectoryIntegrationTest.java`
- `.../process/IndexBasePathLockE2ETest.java`
- Migration controls:
  - `MigrationControlE2ETest`, `RollbackE2ETest`, `PauseResumeMigrationE2ETest`, `SwitchingFenceBufferingE2ETest`

### 4) Search/ranking algorithm correctness (unit-level)

- RRF math harness: `modules/system-tests/src/test/java/io/justsearch/systemtests/RrfFusionHarnessTest.java`
- Lucene adapter runtime tests (many): `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/*Test.java`
  - includes hybrid fusion behavior, indexing behavior, soft deletes, config wiring, etc.

### 5) Offline “quality eval” scripts (relevance + now indexing throughput on official corpora)

These are local/dev tools, not CI gates by default.

- **Manual ranking sanity suite (docs/ corpus)**: `scripts/search/rank-eval-win.ps1`
- **BEIR relevance evaluation**: `scripts/search/beir-eval-win.ps1`
  - computes Recall@K and nDCG@K (lexical vs hybrid) for datasets like SciFact/NFCorpus/Arguana
- **BEIR indexing throughput (index-only)**: `scripts/search/beir-eval-win.ps1 -IndexBenchOnly`
  - writes `indexing-throughput.json` alongside other artifacts

### 6) Stress / soak / chaos tests

Under `modules/system-tests`:

- Soak: `.../SoakSuiteTest.java`
- Chaos: `.../ChaosSuiteTest.java`
- Windows torture + concurrent read/write: `.../torture/WindowsTortureTest.java`, `.../torture/ReadWhileWriteTest.java`
- “Nasty corpus” handling: `.../NastyCorpusTest.java`

## What’s missing (tests we should add to make this a *general* quality/perf gate)

### 1) A deterministic relevance regression gate (small, committed corpus)

Current state:

- We can evaluate BEIR, but it’s download-heavy and not a stable CI gate.
- We can run a docs-suite, but it’s manual judgment, not an automated regression signal.

Recommended test to add:

- **A tiny, in-repo “relevance microbenchmark”**:
  - small corpus (e.g., 100–500 short docs; small enough to commit)
  - a fixed query set + qrels (or expected top-k doc IDs)
  - emits a stable metric (e.g., nDCG@10 + Recall@10) for `mode=lexical` and `mode=hybrid`
  - gates on “no big drop” + optionally “hybrid ≥ lexical on semantic queries”

This becomes the equivalent of “unit tests for ranking quality” (fast, deterministic, reviewable).

### 2) Official-corpus indexing throughput integrated into the perf harness

Current state:

- Perf Scenario B1 measures throughput on a **synthetic** corpus (great for regression stability).
- We now can measure throughput on **BEIR** via `beir-eval-win.ps1 -IndexBenchOnly`, but it’s not integrated into EBv1/perf diffs.

Recommended test to add:

- **A new perf scenario** (or extension of B1) that indexes an “official” dataset:
  - SciFact (small) as the default “official corpus lane”
  - optional NFCorpus lane (larger; more representative)
  - outputs `scenario-b1-throughput.json` in the same schema so the diff tool works unchanged

This gives you “compare to standards” in a way that’s review-friendly.

### 3) GPU acceleration verification as a test (not a manual observation)

Current state:

- We can make GPU work (CUDA DLL preload fix), but verification is mostly via logs / Task Manager / `nvidia-smi`.

Recommended tests to add:

- **A scripted GPU smoke**:
  - start stack with `JUSTSEARCH_LLM_ACCEL=cuda` + `JUSTSEARCH_EMBED_GPU_LAYERS>0`
  - run a small indexing workload
  - assert **at least one** machine-verifiable signal:
    - worker log contains “Preloaded CUDA backend … ggml-cuda.dll”, or
    - `/api/gpu/capabilities` reports CUDA OK and the worker reports GPU layers enabled (if we expose it), or
    - a worker telemetry field indicating “embedding backend = cuda”

Goal: catch “GPU silently disabled” regressions automatically.

### 4) Backpressure + error-contract tests for indexing/search

Current state:

- Many workflow tests exist, but we don’t have strong coverage for “unhappy path contracts” under load:
  - queue full
  - worker unavailable
  - deadline exceeded

Recommended tests to add:

- Contract tests asserting stable `errorCode` + HTTP status mapping for:
  - ingest while worker down
  - indexing roots add/remove while worker down
  - search while embedding backend unavailable
  - queue saturation / rejection behavior

### 5) Unicode + encoding robustness at the HTTP boundary

Current state:

- PowerShell 5.1 default encoding caused real-world JSON issues (fixed in scripts).

Recommended tests to add:

- An HTTP integration test that posts a query containing Unicode punctuation (smart quotes / non-ASCII) and asserts 200 + valid JSON response.
  - This mostly protects clients and proxy layers from accidental regressions in content-type/charset handling.

### 6) “Head must not touch index/queue” enforcement tests (architecture rule)

Current state:

- This is a hard invariant, but it’s easy to regress accidentally.

Recommended test to add:

- An **ArchUnit** (or similar) rule that forbids Head codepaths from importing/using Lucene index/SQLite queue internals or touching the on-disk index path directly.

## Practical “standard suite” recommendations

If you want a single routine you can run before/after changes:

- **Perf regression suite** (stable): `scripts/perf/run-perf-suite-win.ps1 -SamplingProfile regression`
- **Relevance eval** (meaningful): `scripts/search/beir-eval-win.ps1 -Dataset scifact` (optionally cap queries)
- **Indexing throughput (official corpus)**: `scripts/search/beir-eval-win.ps1 -Dataset scifact -IndexBenchOnly`

Then compare:

- Perf: `scripts/perf/diff-perf-suite.mjs`
- Relevance: compare BEIR artifacts under `tmp/beir-eval/<run>/`

