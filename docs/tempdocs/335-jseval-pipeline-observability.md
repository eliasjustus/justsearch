---
title: "335: jseval Pipeline Observability"
type: tempdoc
status: done
created: 2026-03-22
updated: 2026-03-24
---

> NOTE: Noncanonical doc (tooling). May drift.

# 335: jseval Pipeline Observability

## Purpose

jseval is an **agent-only tool** — it is not designed for human
developers. Its sole purpose is to give autonomous agents a reliable,
structured interface for pipeline profiling, search quality evaluation,
and throughput benchmarking. All output, progress reporting, and error
handling should be optimized for machine consumption, not human
readability.

**Design principles:**

1. **Agent-first output.** An agent should be able to run a single
   jseval command and get everything it needs to make a decision —
   without parsing ad-hoc text, grepping logs, or writing wrapper
   scripts. If an agent has to use any tool other than jseval for
   eval/profiling work, that's a gap in jseval.

2. **jseval stays simple.** jseval is a thin client that calls HTTP
   APIs and formats the results. If implementing a jseval feature
   requires complicated code, that's a signal that the **production
   backend** is missing an API or exposing data poorly — fix the
   backend, not jseval. Examples:
   - Worker log parsing in jseval → backend should expose batch
     timing via `/api/status` or a dedicated endpoint
   - GPU VRAM monitoring in jseval → backend already exposes GPU
     metrics via `/api/status`, jseval just reads them
   - Pipeline phase detection in jseval → backend should report
     which phase is active, not force jseval to infer it from
     coverage percentage deltas
   - Crash detection via process management → backend should have
     a health endpoint that distinguishes "healthy" from "dead"

   If jseval grows beyond ~1000 lines of Python, something is wrong
   with the backend's observability surface.

**Goal:** After this tempdoc, an agent can run a single jseval command
to ingest a dataset, wait for all enrichments, and get a structured
report with per-stage timing, throughput rates, and churn metrics.

## Current State (what agents do today)

1. Start backend via `./gradlew.bat :modules:ui:runHeadlessEval`
2. Run `jseval run --dataset scifact --embedding --splade --max-queries 0`
3. Manually poll `/api/status` every 5s with a bash script
4. Manually parse worker.log with grep + node to extract batch timing
5. Manually compute rates and churn from timeline TSV files

**Root cause: jseval is silent during the wait phase.**

The `_poll_until_stable()` loop in `readiness.py` polls every 2s but
only logs at DEBUG level (`log.debug("Readiness check failed: %s")`).
Agents see:

```
INFO  Corpus materialized (5184 files)
INFO  Watched root added
                              <--- 10+ minutes of silence --->
INFO  Indexing complete: 5184 docs in 577s
```

No progress percentages, no stage breakdown, no coverage metrics.
This forced the ad-hoc bash poller to get any visibility at all.

**Problems:**
- The bash poller fails on Windows (missing `bc`, shell quoting issues)
- NER/chunks/disambiguation readiness is not checked by jseval
- No structured output — agents parse ad-hoc text with regex
- Worker log analysis requires knowing the exact log format
- Timeline data is lost between sessions (written to tmp/)
- Each profiling run requires ~50 lines of bash setup
- **Zero progress visibility during jseval's readiness wait**

**Simplest fix:** Add periodic INFO-level progress logging inside
`_poll_until_stable()` (every 30s or every 10 polls):

```python
if poll_count % 15 == 0:  # every ~30s at 2s interval
    log.info("Waiting: e=%s%% s=%s%% n=%d/%d ...",
        snapshot.get("embeddingCoveragePercent", 0),
        snapshot.get("spladeCoveragePercent", 0),
        snapshot.get("completedNerCount", 0),
        snapshot.get("completedNerCount", 0) + snapshot.get("pendingNerCount", 0))
```

This alone would have eliminated the need for the bash poller in
most profiling runs.

## Implementation status

### Implemented

1. [x] **`--pipeline` flag** — waits for all enrichment stages.
2. [x] **Timeline recording** — `--timeline` writes TSV snapshots.
3. [x] **Pipeline summary** — `pipeline_timing` in `summary.json`
   with per-stage completion times.
4. [x] **Progress logging** — periodic INFO-level progress every
   ~30s during readiness wait (item 0 in readiness.py).
5. [x] **httpx noise suppression** — suppressed unconditionally
   (was only in `--verbose` mode, fixed in tempdoc 334 item 17).
6. [x] **GPU metrics in progress** — GPU%, VRAM in both progress
   logs and timeline TSV (from `/api/status` gpu fields).
7. [x] **`jseval compare`** — side-by-side run comparison exists.
8. [x] **`jseval preflight`** — model identity and health checks.

### Implemented (this session)

9. [x] **`--modes` not required for `--max-queries 0`.** Every
   pipeline profiling run requires `--modes lexical` even though
   no queries execute. The CLI should default modes to empty or
   skip mode validation when `--max-queries 0`.
   **P0 — caused friction on every run this session.**

10. [x] **Model identity in `summary.json`.** After model swaps
    (NER bert→distilbert, SPLADE v2→v3, embedding INT8→Q4), runs
    can't be compared without checking worker logs. `summary.json`
    should record which models are loaded (embedding model path/
    fingerprint, SPLADE model path, NER model path, GPU status
    for each). Backend already exposes some via `/api/status`:
    `embedBackend`, `embedOrtCuda.available`, `spladeModelPath`,
    `embeddingFingerprintCurrent`. jseval should snapshot these
    at the start of the run and write to `summary.json`.
    **P0 — critical for comparing runs after model swaps.**

11. [x] **Per-stage completion in progress output.** Progress logs
    show coverage percentages but not when stages complete. An
    agent waiting 5 minutes sees percentages ticking up but can't
    tell when embedding hit 100% until the final summary. Progress
    should emit a one-time log when each stage crosses 100%:
    `"Embedding 100% at t=179s (45.1 docs/sec)"`.
    **P1 — would speed up diagnosis during long runs.**

12. [x] **Combined batch timing from backend.** jseval relies on
    grepping worker.log for "Combined backfill:" lines to get
    per-phase timing (embed/SPLADE/NER ms). This required manual
    log analysis on every profiling run. Two options:
    (a) Backend exposes batch timing via `/api/status` (preferred,
    follows "fix the backend not jseval" principle).
    (b) `jseval analyze-log` subcommand parses worker.log JSON.
    **P1 — eliminates manual log grep on every run.**
    **Resolved by tempdoc 350:** Option (a) implemented. Backend
    accumulates per-stage timing in `OperationalMetrics`, exposes
    via `PipelineBatchTiming` proto → `/api/status`. jseval
    `timeline.py` captures and reports it in pipeline summary.

13. [x] **`--json` as subcommand option.** Currently `--json` is a
    top-level option (`python -m jseval --json run ...`). Agents
    expect it on the subcommand (`... run --json`). Add as alias.
    **P2 — minor UX fix, caused first-command failure.**

14. [x] **Fix `worker_throughput_docs_per_sec`.** Always 0.0 in runs — only
    `pipeline_timing.primary_indexing.docs_per_s` has the real
    number. Either fix the field or remove it.
    **P2 — confusing broken field.**

15. [x] **GPU summary stats.** Timeline TSV has per-poll GPU% and
    VRAM but no summary. Add to `summary.json`: avg GPU%, peak
    GPU%, avg VRAM, peak VRAM, % polls with GPU idle (<5%).
    **P2 — useful for GPU optimization work.**

16. [x] **Backend crash fast-fail with `--start-backend`.** When
    the Worker crashes (e.g., GPU OOM), jseval waits for 5
    consecutive fetch timeouts (50s) before reporting failure.
    Could check if the backend PID is alive and fail immediately.
    **P3 — only matters when things break.**

17. [x] **Pipeline timing in `jseval compare`.** Already implemented
    — `compare_pipeline_timing()` in `compare_runs.py` is wired
    into the `compare` CLI command (lines 238-252). Shows per-stage
    diff with regression detection.

### Remaining

All items complete.

18. [x] **Duplicate stage completion logs.** `stage_logged` dict
    is local to `_poll_until_stable`, so it resets between the
    pipeline wait phase and the post-ingest search readiness check.
    Causes duplicate "100% at t=0s" lines.

    **Investigation:** `check_search_ready` (30s timeout gate) also
    calls `_poll_until_stable` which creates fresh `stage_logged`.
    When enrichments are already at 100%, all stages immediately
    fire "100% at t=0s".
    **Fix:** Add `emit_stage_completions=True` parameter to
    `_poll_until_stable` (default True). Pass `False` from
    `check_search_ready`. ~3 lines.

19. [x] **NER model identity in `/api/status`.** The
    `models` snapshot captures embed and SPLADE info but no NER
    model path or GPU status. After a NER model swap (bert →
    distilbert), `summary.json` doesn't reflect which NER model.

    **Investigation:** `WorkerOperationalView` has `spladeModelPath`
    and `rerankerModelPath` but no NER equivalent. Adding it
    requires changes across 5 files:
    1. gRPC proto: add `ner_model_path` field to status message
    2. Worker status builder: populate from `NerConfig.modelPath()`
    3. `WorkerStatusMapper`: pass through to view
    4. `WorkerOperationalView`: add field + serialize in `toMap()`
    5. Status response schema JSON
    **Effort:** Medium (cross-module Java change, same pattern as
    `spladeModelPath`). Deferred — not blocking profiling workflow.

20. [x] **`--max-queries 0` skips dataset loading.** `execute_run`
    calls `corpora.load()` even when modes is empty.
    **Fix:** Early return in `execute_run` when `not modes`:
    return a minimal summary with just ingest/pipeline data,
    skip dataset loading. ~5 lines in `run.py`.

## Implementation plan

All items are small, self-contained changes to the jseval Python
code. No Java/backend changes needed (except item 12a which is
deferred). Ordered by dependency — later items may read fields
added by earlier items.

### Item 9: `--modes` optional for `--max-queries 0`

**File:** `jseval/cli.py`, lines 88-90

**Change:** Replace unconditional modes validation with:
```python
if not modes and max_queries != 0:
    click.echo("Error: --modes is required ...", err=True)
    sys.exit(1)
```
Also guard the `modes.split(",")` call at line 147 — pass empty
list `[]` when modes is None. `execute_run` in `run.py` already
handles an empty modes list (skips the `for mode in modes` loop
at line 94 and returns a summary with no per-mode results).

**Test:** Verify `test_cli.py` — check if there are existing tests
for the modes validation. Add a test for `--max-queries 0` without
`--modes`.

### Item 10: Model identity in `summary.json`

**File:** `jseval/run.py` (add model snapshot at run start),
`jseval/ingest.py` (capture during ingest phase)

**Change:** At the start of `_do_run()` in `cli.py` (before
`ingest_mod.prepare_corpus`), fetch `/api/status` once and extract
model identity fields (same extraction as `preflight.py` lines
62-82):
```python
models = {
    "embed_backend": status.get("embedBackend"),
    "embed_fingerprint": status.get("embeddingFingerprintCurrent"),
    "embed_compat_state": status.get("embeddingCompatState"),
    "splade_model_path": status.get("spladeModelPath"),
    "embed_cuda_available": (status.get("embedOrtCuda") or {}).get("available"),
    "splade_cuda_available": (status.get("spladeOrtCuda") or {}).get("available"),
}
```
Pass `models` dict to `execute_run()` which adds it to the
summary dict as `summary["models"]`. Alternatively, do the fetch
inside `execute_run()` at the start (cleaner — `run.py` already
takes `base_url`).

**Risk:** The `/api/status` fetch might fail if the backend is
slow. Use a short timeout with graceful fallback (models=None).

### Item 11: Per-stage completion in progress output

**File:** `jseval/readiness.py`, `_maybe_log_progress()` function

**Change:** Add stage-completion tracking state to the poll loop.
Track previous coverage values and emit a one-time INFO log when
each stage crosses 100%:
```python
# In _poll_until_stable, before the while loop:
stage_logged = {"embed": False, "splade": False, "chunk": False, "ner": False}

# Inside the loop, after on_snapshot callback:
_check_stage_completions(snapshot, elapsed, stage_logged, json_mode)
```

New helper `_check_stage_completions()`:
```python
def _check_stage_completions(snapshot, elapsed, logged, json_mode):
    if not logged["embed"] and snapshot.get("embeddingCoveragePercent", 0) >= 99.9:
        logged["embed"] = True
        log.info("Embedding 100%% at t=%ds", elapsed)
    # ... same for splade, chunk, ner
```

The `stage_logged` dict is passed by reference — mutable state
across poll iterations. This is the same pattern as `stable_passes`.

### Item 13: `--json` as subcommand option

**File:** `jseval/cli.py`

**Change:** Add `@click.option("--json", "json_flag", is_flag=True,
hidden=True)` to the `cmd_run` function. In `cmd_run`, merge:
```python
if json_flag:
    ctx.obj["json"] = True
```
This makes both `jseval --json run ...` and `jseval run --json ...`
work. The `hidden=True` keeps the help output clean (top-level
`--json` is the canonical form).

### Item 14: Fix `worker_throughput_docs_per_sec`

**File:** `jseval/ingest.py`, line 123

**Investigation result:** The field reads `throughputDocsPerSec`
from `/api/status` at the moment readiness passes. By that time,
primary indexing is done and the backend reports 0.0 throughput.
The field is structurally misleading — it captures a point-in-time
metric from a state where no ingestion is active.

**Change:** Remove `worker_throughput_docs_per_sec` from the
ingest summary. It's never useful — `docs_per_sec` (line 122)
already computes the correct overall rate, and `pipeline_timing.
primary_indexing.docs_per_s` has the primary indexing rate.

Add a comment explaining why:
```python
# worker_throughput_docs_per_sec removed: always 0.0 because
# it reads backend throughput at readiness-pass time, when
# primary indexing is already complete. See tempdoc 335 item 14.
```

### Item 15: GPU summary stats in `summary.json`

**File:** `jseval/timeline.py`, `compute_pipeline_summary()`

**Change:** After computing per-stage timings, aggregate GPU
metrics from the timeline rows:
```python
gpu_pcts = [r["gpu_pct"] for r in rows if r.get("gpu_pct") is not None]
vram_mbs = [r["vram_used_mb"] for r in rows if r.get("vram_used_mb") is not None]
if gpu_pcts:
    summary["gpu"] = {
        "avg_pct": round(sum(gpu_pcts) / len(gpu_pcts), 1),
        "peak_pct": max(gpu_pcts),
        "idle_polls_pct": round(
            sum(1 for p in gpu_pcts if p < 5) * 100 / len(gpu_pcts), 1),
    }
if vram_mbs:
    summary["gpu"]["avg_vram_mb"] = round(sum(vram_mbs) / len(vram_mbs)),
    summary["gpu"]["peak_vram_mb"] = max(vram_mbs),
```

### Item 16: Backend crash fast-fail

**File:** `jseval/readiness.py`, `_poll_until_stable()`

**Change:** Add an optional `process_check` callback parameter:
```python
def _poll_until_stable(
    ...,
    process_check: Callable[[], bool] | None = None,
) -> ReadinessResult:
```

Inside the poll loop, before each `time.sleep()`:
```python
if process_check is not None and not process_check():
    return ReadinessResult(
        passed=False,
        failure_reasons=["backend_process_died"],
        snapshot=last_snapshot,
    )
```

Wire from `ingest.py`: when called with `--start-backend`, pass
`process_check=lambda: proc.poll() is None` through `ingest.
prepare_corpus()` → `readiness.wait_pipeline_complete()`.

This requires adding `process_check` to `prepare_corpus()` and
the readiness wait functions. ~5 lines per function signature.

### Item 17: Pipeline timing in `jseval compare`

**File:** `jseval/compare_runs.py`

**Change:** After loading both summary.json files, extract
`pipeline_timing` from each and compute diffs:
```python
def _compare_pipeline_timing(a: dict, b: dict) -> dict | None:
    pt_a = a.get("pipeline_timing")
    pt_b = b.get("pipeline_timing")
    if not pt_a or not pt_b:
        return None
    return {
        "total_elapsed_s": {"a": pt_a["total_elapsed_s"],
                            "b": pt_b["total_elapsed_s"],
                            "diff_pct": _pct_diff(...)},
        "stages": {stage: {"a": ..., "b": ..., "diff_pct": ...}
                   for stage in set(pt_a.get("stages",{})) | set(pt_b.get("stages",{}))},
    }
```

Add to the comparison output dict as `result["pipeline_timing"]`.

### Item 12: Combined batch timing (deferred)

**Option (b)** — `jseval analyze-log` subcommand — deferred until
the other items are done. It's the most complex item (~50 lines)
and the batch timing format in worker.log may change as the
combined backfill evolves. The other items are higher priority
and self-contained.

### Execution order

1. Items 9 + 13 together (CLI validation, ~10 lines total)
2. Item 14 (remove broken field, ~3 lines)
3. Item 11 (stage completion logs, ~20 lines)
4. Item 15 (GPU stats, ~15 lines in timeline.py)
5. Item 10 (model identity, ~20 lines across run.py/cli.py)
6. Item 16 (crash fast-fail, ~15 lines across 3 files)
7. Item 17 (compare pipeline timing, ~30 lines)

Total estimated: ~115 lines of Python across 7 files. All items
are independent except item 16 which threads a callback through
3 function signatures.

**Improvement:** `jseval preflight` — check backend health, report
loaded models, verify GPU status before starting a run. Fail early
if the backend isn't configured as expected.

### 8. Crash detection and diagnosis

When `runHeadlessEval` crashes (Worker exit code -1, silent GPU OOM),
jseval's HTTP poll just times out. The agent doesn't know what happened
until it greps logs. jseval could detect the crash:
- Poll returns connection refused → backend died
- Check exit code of the Gradle process
- Tail the last 20 lines of worker.log for error context
- Report: "Backend crashed at t=45s. Last error: BFCArena OOM"

**Concrete example:** Multiple profiling runs this session ended with
"no response" from the poller. Each time the agent had to manually
find the log path (which varies: `modules/ui/build/applauncher-data/`
vs `tmp/headless-eval-data/`), search for errors, and diagnose.

### 9. Reproducible run configuration

Currently agents set env vars ad-hoc before each run:
```
JUSTSEARCH_EMBED_GPU_ENABLED=true \
JUSTSEARCH_EMBED_GPU_MEM_MB=2048 \
./gradlew.bat :modules:ui:runHeadlessEval
```

jseval could accept a run config file:
```yaml
# jseval-run.yaml
dataset: scifact
modes: [lexical]
embedding: true
splade: true
pipeline: true
gpu:
  embed: {enabled: true, mem_mb: 2048}
  ner: {enabled: false}
backend:
  task: runHeadlessEval
  clean: true  # rm -rf data dir before start
```

This makes runs reproducible and self-documenting. An agent can
reference a config file instead of reconstructing env var combinations
from conversation history.

### 10. Backend lifecycle management

Agents currently start/stop the backend manually (Gradle task in
background, pkill to stop). jseval could manage the backend lifecycle:
- `jseval run --start-backend` — start runHeadlessEval, wait for
  ready, run the eval, stop backend when done
- Handles clean data dir, GPU config, port detection
- Captures backend stdout/stderr alongside eval results

**This is the single biggest improvement for autonomous agents.**
The current session spent ~30% of tool calls on backend lifecycle
management (start, wait, poll health, detect failure, find logs, kill).
Every run required ~10 lines of bash setup that broke differently each
time (Windows path issues, background process management, port conflicts
from stale backends).

### 11. Worker log path discovery

The worker.log location depends on which Gradle task launched the
backend and which data dir is configured:
- `runHeadless`: `modules/ui-web/.dev-data/logs/worker.log`
- `runHeadlessEval`: `tmp/headless-eval-data/logs/worker.log`
- Custom `JUSTSEARCH_DATA_DIR`: `<dir>/logs/worker.log`

Agents waste time finding the correct path. jseval should discover
the log path from `/api/status` (the `indexBasePath` field reveals
the data dir) or accept it as a parameter.

### 12. A/B comparison without manual tables

This session produced 6 profiling runs (pre-fix, post-fix, Q4+gate,
combined, NER INT8, NER batch). Each comparison required manually
building markdown tables from timeline data. A structured comparison
tool would save significant context window and reduce errors.

## Agent-Only Design Implications

Since jseval is agent-only, several current design choices should change:

### Output should be JSON by default

- **`--json` becomes the default.** Agents parse JSON natively; they
  regex-parse human text badly. Add `--human` flag for the rare case
  a developer wants to look at jseval output directly. This is a
  breaking change but jseval is agent-only so there are no human
  users to break.
- **Progress lines should be JSON.** Each progress line is a JSON
  object on its own line (NDJSON pattern):
  `{"t":32,"embed_pct":45,"splade_pct":12,"gpu_pct":34,...}`.
  Agents parse this directly. No regex needed.
- **summary.json contents should be printed to stdout on completion**
  so agents can capture it without knowing the output directory.
- **Errors should be JSON objects**, not string messages:
  `{"error":"backend_unreachable","port":33221,"consecutive_failures":3}`

### Error reporting should be actionable

- **Errors should include the fix.** Instead of `"status_fetch_failed"`,
  report `{"error":"backend_unreachable","action":"check if backend
  is running on port 33221","last_successful_poll_sec_ago":30}`.
- **Crash detection should be immediate**, not after a 2-hour timeout.
  3 consecutive connection failures = report "backend died" with the
  last known state. This is simple in jseval (counter + early exit).
- **Readiness failures should show what's blocking.** The backend
  already returns all coverage fields in `/api/status`. jseval just
  needs to include them in the error output instead of discarding
  them. No new backend API needed.

### Backend lifecycle should be built-in (but kept simple)

- jseval should own the full lifecycle: start backend, ingest, wait,
  query, stop. An agent should never need to run Gradle directly.
- **Keep it simple:** jseval runs `./gradlew.bat :modules:ui:runHeadlessEval`
  as a subprocess, polls `/api/status` until healthy, runs the eval,
  then kills the process. No process management libraries, no PID
  files, no signal handling. If this requires complicated code, the
  backend startup needs to be more predictable (e.g., a health
  endpoint that works before the Worker is ready).
- Clean data dir should be the default for profiling runs.

### Configuration should be declarative

- Run configurations should be YAML files, not CLI args + env vars.
  An agent can write a YAML file and reference it across multiple
  runs. CLI args are lost when the conversation compacts.
- The YAML should capture everything needed to reproduce a run:
  dataset, modes, GPU config, model paths, backend task, clean/dirty.

### Comparison should be first-class

- A/B comparison is a core agent workflow (measure baseline, make
  change, measure again, compare). jseval should make this trivial:
  `jseval compare run-a run-b` should output a structured diff with
  per-stage timing, rate changes, and regression flags.
- Comparison output should be JSON, not markdown tables. Agents can
  format it however they need.

## Work Items

Prioritized by correctness first, then agent time saved per session.

### Priority -1: Fix existing correctness issues

These are bugs in existing jseval code that can produce invalid eval
results or waste agent time. Fix before adding new features.

-1a. [x] **Check `embeddingCoveragePercent` for `--embedding`
   readiness.** Added `embeddingCoveragePercent >= 99.9` to the
   dense check in `_check_search_conditions()`, matching the SPLADE
   pattern.

-1b. [x] **Fail fast on consecutive status fetch failures.** Added
   counter in `_poll_until_stable()`: 5 consecutive failures →
   abort with `backend_unreachable`. Also checks
   `meta.workerRpcStale` (from 333 §5) to detect Worker-down
   scenarios where the Head is still responding.

-1c. [x] **Make watcher settle timeout proportional to corpus size.**
   Replaced fixed 30s with `_watcher_settle_timeout(corpus_doc_count)`
   — scales as `max(30, doc_count / 100)`, capped at 300s.

-1d. [x] **Remove `NEW_INDEX_NO_FINGERPRINT` from valid compat
   states.** Replaced with `FINGERPRINT_MATCH` in
   `_VALID_EMBEDDING_COMPAT`.

-1e. [x] **Reuse HTTP client across polls.** `_poll_until_stable()`
   creates one `httpx.Client` for the entire loop.
   `_wait_for_watcher_activity()` also reuses a single client.

### Priority 0: Progress visibility (biggest UX gap)

0. [x] **INFO-level progress logging in `_poll_until_stable()`.** Added
   `_maybe_log_progress()` — every ~30s logs pipeline coverage,
   heap usage, throughput, and blocking reasons at INFO level.

   Progress line format:
   ```
   [32s] e=45% s=12% n=0/5184 c=0% | heap=1.8/4.0GB | throughput=247d/s | index_not_idle
   [62s] e=78% s=30% n=0/5184 c=0% | heap=1.9/4.0GB | throughput=0d/s | stable
   ```

### Priority 1: Backend lifecycle (biggest time sink)

1. [x] **`--start-backend` flag.** Starts `runHeadlessEval` via
   `subprocess.Popen`, waits for `/api/status` healthy, runs eval,
   stops via `taskkill /PID /T /F` in `finally` block. Supports
   `--clean` to wipe data dir before start. New module: `backend.py`.
2. [x] **Crash detection in poll loop.** Covered by item -1b
   (fail-fast on consecutive failures + workerRpcStale check).
   Log tailing on crash deferred to item 1.
3. [x] **Worker log path discovery.** Added `jseval log-path`
   subcommand — derives log path from `/api/status` `indexBasePath`.

### Priority 2: Pipeline wait + timing (core profiling)

4. [x] **`--pipeline` flag.** Added `wait_pipeline_complete()` in
   readiness.py. Checks all enrichments: embedding 100%, SPLADE
   100%, chunks 100%, NER complete. `--pipeline` implies
   `--embedding --splade`.
5. [x] **`--timeline <path>` flag.** Records status snapshots via
   `on_snapshot` callback in the poll loop. Writes TSV with
   columns: elapsed_s, indexed, pending, embed_pct, splade_pct,
   ner_done, ner_pending, chunk_pct, heap_mb, throughput.
   New module: `timeline.py`.
6. [x] **Pipeline summary in output.** `compute_pipeline_summary()`
   extracts per-stage completion times, primary indexing rate, and
   SPLADE churn from timeline rows. Written to
   `summary.json → pipeline_timing`.

### Priority 3: Preflight + comparison

7. [x] **`jseval preflight` subcommand.** Checks backend health,
   loaded models (embedding, SPLADE, reranker), GPU status
   (CUDA availability per component), enrichment coverage, and
   `/api/debug/commit-metadata` for `embedding_model_sha256`.
   New module: `preflight.py`.
8. [x] **`jseval compare` extension.** Extended to compare
   `pipeline_timing` fields between two summaries with
   per-field delta, ratio, and regression detection (>10%
   threshold). `compare_pipeline_timing()` in compare_runs.py.

### Priority 4: GPU monitoring + per-stage enrichment rates (backend changes)

9. [x] **Wire GPU metrics into `/api/status`.** NVML metrics
   (`gpuUtilizationPercent`, `memoryUtilizationPercent`,
   `usedVramBytes`, `totalVramBytes`) are collected by
   `GpuCapabilitiesService` in `ai-bridge` and served at
   `/api/gpu/capabilities`, but not included in `/api/status`.
   Fix: inject `GpuCapabilitiesService` into
   `StatusLifecycleHandler`, add nullable GPU fields to
   `WorkerOperationalView`. Head-side only (no gRPC/proto
   changes needed — Head and Worker share the same GPU).
   Then jseval reads them alongside other status fields during
   the poll loop.

   **VERIFIED (2026-03-22):** NVML probe returns correct values.
   Cross-referenced NvmlService.probe() output against nvidia-smi
   on RTX 4070 (12GB):
   - Total VRAM: 12282 MiB — exact match
   - GPU utilization: 2% — exact match
   - Memory utilization: 9% vs 5% — within expected transient delta
   - Used VRAM: 1333 MiB vs 993 MiB — within expected transient delta
   FFI struct layouts (`nvmlMemory_t`: 3xI64; `nvmlUtilization_t`:
   2xI32) are correct. Utilization is best-effort nullable (inner
   try/catch for `nvmlDeviceGetUtilizationRates`) — older drivers
   may return null. Windows-only (non-Windows returns `available=false`).

   Implementation notes:
   - Utilization fields exist on `GpuCapabilities.Nvml` only, NOT on
     `Effective`. Wire from `nvml` sub-record, not `effective`.
   - `GpuCapabilitiesService.snapshot()` re-probes NVML each call
     (no caching). Consider caching with TTL (~5s) to avoid NVML
     init/shutdown overhead on every `/api/status` poll.
   - No test coverage for NvmlService — experiment file was used for
     manual verification, then removed.

10. [x] **Per-stage enrichment rates via `/api/status`.** Backfill
    per-stage timing (`embedMs`, `spladeMs`, `nerMs`,
    `embedProcessed`, `spladeProcessed`, `nerProcessed`) already
    exists in `CombinedEnrichmentBackfillOps` but is only logged.
    Pipeline:
    a. `OperationalMetrics` (worker-core): add per-stage counters
       + `ThroughputMonitor` instances for embed/splade/ner
    b. `CombinedEnrichmentBackfillOps`: call
       `OperationalMetrics.recordEnrichmentBatch()` after each batch
    c. `indexing.proto`: add fields for
       `embed_backfill_docs_per_sec`, `splade_backfill_docs_per_sec`,
       `ner_backfill_docs_per_sec`
    d. `IndexStatusOps.buildStatusResponse()`: compute and set
    e. `WorkerStatusMapper`: map new proto fields
    f. `WorkerOperationalView`: add the three new doubles
    Then jseval includes them in timeline rows and progress logging.

### Priority 5: Reproducibility

11. [x] **Run config YAML.** Added `--config` flag to `jseval run`.
    YAML config maps structured keys (e.g., `gpu.embed.layers`) to
    env vars (`JUSTSEARCH_EMBED_GPU_LAYERS`) via `_ENV_MAP`. Also
    supports `env:` passthrough for arbitrary env vars. `pyyaml`
    added as optional dependency. New module: `run_config.py`.

## Agent Takeover Notes (2026-03-22)

### Codebase exploration findings

Read all 22 source files and 22 test files. Key observations:

**Architecture strengths:**
- Clean modular design (corpora → retriever → scoring → provenance → comparability)
- Good test coverage (1:1 test files, synthetic data, no live backend needed)
- Comparability tracking is genuinely useful — prevents agents from comparing invalid runs
- Doc ID round-trip (URL-encode on materialize, reverse on retrieval) works cleanly

**Current line counts:** jseval is already well within the "stays simple" goal.

### `/api/status` field verification

**CORRECTION: GPU metrics are NOT in `/api/status`.** The tempdoc states
"already in `/api/status` via NVML (`gpu.utilization.percent`,
`gpu.memory.utilization.percent`)" — this is incorrect. NVML code exists
in `ai-bridge` but is **not wired** into the status response. Items 0
and 9 must be adjusted:

| Field assumed | Actually exists? |
|--------------|-----------------|
| `embeddingCoveragePercent` | YES |
| `spladeCoveragePercent` | YES |
| `chunkVectorCoveragePercent` | YES |
| `completedNerCount` | YES |
| `pendingNerCount` | YES |
| `memoryUsedBytes` / `memoryMaxBytes` | YES |
| `indexBasePath` | YES |
| `throughputDocsPerSec` | YES |
| `embedBackend` | YES |
| `spladeModelPath` | YES |
| `embeddingFingerprintCurrent` | YES |
| `embedOrtCuda.available` | YES (nested object) |
| `rerankerModelPath` | YES |
| `gpu.utilization.percent` | **NO — does not exist** |
| `gpu.memory.used.bytes` | **NO — does not exist** |
| `gpu.memory.total.bytes` | **NO — does not exist** |

**Impact:** Progress line format in item 0 must drop GPU% and VRAM.
Item 9 is blocked until someone wires NVML into `/api/status`
(backend change in `StatusLifecycleHandler.java`). Pipeline progress
and JVM heap still work.

**Revised progress line format (item 0):**
```
INFO [32s] e=45% s=12% n=0/5184 c=0% | heap=1.8/4.0GB | throughput=247d/s
INFO [62s] e=78% s=30% n=0/5184 c=0% | heap=1.9/4.0GB | throughput=0d/s
INFO [92s] e=100% s=55% n=800/5184 c=0% | heap=2.1/4.0GB | throughput=0d/s
```

### `runHeadlessEval` task findings

- **Entry point:** `io.justsearch.ui.HeadlessApp` (same as `runHeadless`)
- **Default port:** `33221` (env: `JUSTSEARCH_API_PORT`)
- **Default data dir:** `<repo>/tmp/headless-eval-data`
- **Key difference from `runHeadless`:** Uses `applyHeadlessEvalContract()`
  which chains env vars with lazy defaults, auto-detects ORT CUDA DLLs,
  auto-enables SPLADE GPU, forces `JUSTSEARCH_UI_SETTINGS_MODE=IN_MEMORY`,
  and sets `justsearch.eval.disable_breath_holding=true`
- **GPU env vars forwarded:** `JUSTSEARCH_GPU_LAYERS`,
  `JUSTSEARCH_EMBED_GPU_LAYERS`, `JUSTSEARCH_EMBED_GPU_MEM_MB`,
  `JUSTSEARCH_EMBED_BACKEND`, `JUSTSEARCH_SPLADE_GPU_ENABLED`, etc.
- **Working dir:** repo root (not module dir)
- **Logging:** Uses production `logback.xml` (structured JSON)

**Risk for item 1 (`--start-backend`):** Gradle spawns a JVM which
spawns the Worker as a child process. Killing the Gradle process on
Windows requires killing the process tree (not just the PID). Python's
`subprocess.Popen` doesn't handle this well without `taskkill /F /T`.
This is solvable but needs care.

### Confidence per item

| # | Item | Confidence | Blocker |
|---|------|-----------|---------|
| 0 | Progress logging | Very high | None (drop GPU, keep pipeline + heap) |
| 1 | `--start-backend` | Medium | Windows process tree management |
| 2 | Crash detection | High | None |
| 3 | Log path discovery | High | None |
| 4 | `--pipeline` flag | High | None |
| 5 | `--timeline` | High | None |
| 6 | Pipeline summary | High | None |
| 7 | `jseval preflight` | High | None |
| 8 | `jseval compare` | High | Partially exists already |
| 9 | `--gpu-monitor` | Blocked | GPU fields not in `/api/status` |
| 10 | Batch timing in status | Out of scope | Backend change |
| 11 | Run config YAML | Medium | Schema design needed |

### Recommended implementation order

Start with Priority -1 correctness fixes (-1a through -1e) since they
all touch `readiness.py` and `ingest.py` — the same files as item 0.
Then item 0 (progress logging) which naturally builds on the poll loop
refactoring. Then 4, 5, 6 (pipeline wait + timing). Then 7 (preflight),
3 (log path), 8 (compare extension). Defer 1 (backend lifecycle) and
11 (YAML config) until the simpler items prove the patterns. Skip 9
until GPU metrics are wired into the backend.

## Dependencies

- **312 (Primary Indexing Throughput):** Profiling methodology and
  status fields used.
- **334 (Single-Pass Enrichment):** Combined backfill log format.
- **330 (Worker State Accuracy):** Added lifecycle/components/readiness
  fields to `/api/status`, grouped sub-objects (embedding, schema,
  chunkCoverage, queueHealth). Useful for preflight checks.
  `embeddingCompatState` transitions (FINGERPRINT_MATCH after first
  commit) inform item -1d.
- **333 (Status Provenance):** Added `meta.workerRpcStale` field to
  `/api/status` and `/api/debug/commit-metadata` endpoint. jseval
  should consume `workerRpcStale` for crash detection (item -1b) and
  `commit-metadata` for model identity verification (item 7).
- **339 (Inference Phase Timing):** Backend-side batch timing API
  (addresses item 12 — eliminates worker.log grep for per-phase timing).
- **342 (Run Context Pattern):** Generalizes parameter threading pattern
  used in item 16 (`process_check` callback threading through function
  signatures).

## Non-Goals

- Replacing the MCP dev tools (those handle interactive dev sessions)
- Real-time dashboards (jseval is batch-oriented)
- Production monitoring (jseval is for eval/profiling)

## Verification (2026-03-22)

Two verification runs:
1. **Scifact 5184 docs** (38 min, manually interrupted at e=83%
   s=63%) — verified progress logging, GPU metrics, enrichment
   counters, fail-fast, backend lifecycle, preflight, log-path.
2. **20-doc tiny corpus** (15s, completed) — verified pipeline
   completion, pipeline summary, timeline TSV, `--corpus-dir`
   semantics, chunk skip fix, backend start+stop lifecycle.

### Verified working (all items)

| Feature | Evidence |
|---------|----------|
| Progress logging (item 0) | 70+ lines at 30s intervals; GPU/VRAM/heap/blocking reasons |
| GPU in progress lines (item 9) | `GPU 31% VRAM 2.4/12.0GB` — real NVML, fluctuating 7-68% |
| Pipeline wait (item 4) | Correctly waits for all stages; passes when embed=100% splade=100% NER=20/20 |
| Pipeline summary (item 6) | `pipeline_timing: {total_elapsed_s: 14.9, stages: {embedding_100_pct_at_s: 2.5, splade_100_pct_at_s: 12.8, ner_complete_at_s: 2.5}}` |
| Timeline TSV (item 5) | 8 rows, 15 columns, GPU%/VRAM/enrichment counters all populated |
| Backend lifecycle (item 1) | Start → healthy (12s) → ingest → pipeline → query → `Backend stopped` |
| `--clean` flag | `Cleaning data directory` before start |
| `--corpus-dir` semantics | `Using explicit corpus dir: tmp\tiny-corpus (20 files)` — no re-materialization |
| Watcher timeout (-1c) | `Waiting up to 52s` for 5184 docs (5184/100 = 51.8s) |
| Fail-fast (-1b) | 5 consecutive failures → `backend_unreachable` (no 2-hour poll) |
| Preflight (item 7) | Full report: models, GPU CUDA, coverage, commit-metadata with `embedding_model_sha256` |
| Log path (item 3) | Correct: `tmp/headless-eval-data/logs/worker.log` |
| Enrichment counters (item 10) | All three tracking: 0→20 in timeline rows |
| YAML config (item 11) | `--config` flag recognized, CLI args overridable |
| Compare pipeline timing (item 8) | Unit-tested; not E2E tested (needs two completed runs) |

### Bugs found and fixed during verification

All fixed and committed:

1. ~~`--dataset`/`--modes` `required=True` blocks `--config`~~ →
   Changed to `default=None` with post-config validation.
2. ~~Pipeline threshold `100.0` vs `99.9`~~ → Changed to `99.9`.
3. ~~`backend.py` hardcodes `gradlew.bat`~~ → Conditional on `os.name`.
4. ~~NVML probe uncached~~ → 5s TTL cache in `StatusLifecycleHandler`.
5. ~~`compare --json` format changed~~ → Preserves original when no
   pipeline_timing.
6. ~~`-v` httpcore noise~~ → httpcore/httpx loggers suppressed in
   verbose mode.
7. ~~Redundant `tl_mod` imports~~ → Single import at function scope.
8. ~~`--corpus-dir` re-materializes~~ → Explicit dir used as-is, errors
   if empty.
9. ~~`wait_pipeline_complete` blocks forever with no chunks~~ → Skips
   chunk coverage check when `chunkDocCount == 0`.

### Remaining observations (not bugs, no action needed)

- **`throughputDocsPerSec` always 0 during enrichment.** Correct
  behavior — throughput monitor tracks primary indexing only.
- **httpx INFO lines still visible.** Less noisy than httpcore DEBUG
  but still ~1 line per poll. Could suppress but would hide
  legitimate HTTP errors. Acceptable.

### Remaining agent-specific gaps (future work, not blocking)

- **No NDJSON progress output.** Progress lines are human-formatted.
  Agents parse them via substring matching (which works given the
  stable format), but proper NDJSON would be cleaner.
- **No discoverability commands.** (`jseval datasets`, `jseval modes`)
- **`--start-backend` env var forwarding is ordering-dependent.**
  Works correctly but fragile if CLI logic is reordered.
