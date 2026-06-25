---
title: "357: Encoder Profiles via /api/status (Eliminate Dual Data Path)"
type: tempdoc
status: done
created: 2026-03-26
updated: 2026-03-26
completed: 2026-03-26
commits:
  - b2e78d58b — initial implementation
  - 0a237eee2 — self-review fixes (hot path, API consistency, test coverage, DictWriter bug)
  - 7d2a38a22 — merge to main (5 conflicts resolved: 356→357 supersession)
merged: 2026-03-26
---

> NOTE: Noncanonical doc (refactoring). May drift.

# 357: Encoder Profiles via /api/status

## Purpose

Encoder profiling data (ORT call counts, percentiles, sub-phase timing)
currently flows through two paths:
1. **Worker -> worker.log -> jseval log parser** (fragile regex, format-coupled)
2. **Worker -> OperationalMetrics -> /api/status -> jseval timeline** (established, robust)

This tempdoc eliminates path 1 by routing encoder profiling through
path 2 — the same architecture used for `batchTiming` data. It also
extracts the copy-pasted profiling instrumentation from the three
encoders into a shared `EncoderProfileAccumulator`, which both removes
the duplication and enables a pull model where OperationalMetrics
snapshots accumulators on demand (always fresh, no interval gaps).

When complete, `profiling.py` is deleted. The `--profile` flag is
removed from the `run` command — encoder profiles are always available
via `--pipeline`.

## Context

Tempdoc 356 added per-call profiling to all three encoders
(OnnxEmbeddingEncoder, SpladeEncoder, BertNerInference). Each encoder
has its own copy of ~30 lines of identical profiling infrastructure:
6-8 AtomicLong counters, an HdrHistogram, and a periodic logging block.
jseval's `--profile` flag parses worker.log post-run via regex to
extract the final profiling values.

Problems with the current approach:
- **Duplication:** Three copies of identical accumulation logic.
- **Stale data:** Push happens every 20-100 calls (per encoder). Short
  runs or the final batch of calls may never be surfaced.
- **Lost sub-phases:** Each encoder tracks different sub-phases (embed
  has tensor+extract, SPLADE has postProcess, NER has tensor+extract),
  but the log parser only captures the common subset.
- **Two paths to maintain:** Log format changes require updating both
  the encoder log.info() and the profiling.py regex.

The existing `batchTiming` data flow is the correct pattern:
```
OperationalMetrics (worker-core)
  -> IndexStatusOps.buildEnrichment() (worker-services)
    -> PipelineBatchTiming proto (ipc-common)
      -> gRPC StatusResponse
        -> WorkerStatusMapper (app-services)
          -> BatchTimingView -> EnrichmentProgressView (app-api)
            -> /api/status JSON
              -> jseval snapshot_to_row() -> compute_pipeline_summary()
```

## Data to surface

Per encoder (embed, splade, ner):

| Field | Type | Description |
|-------|------|-------------|
| calls | long | Total ORT inference calls |
| phaseTotalUs | map | Cumulative total time per sub-phase (us) |
| ortMinUs | long | Minimum single ORT call latency (us) |
| ortMaxUs | long | Maximum single ORT call latency (us) |
| ortP50Us | long | ORT call latency p50 (us) |
| ortP95Us | long | ORT call latency p95 (us) |
| ortP99Us | long | ORT call latency p99 (us) |

Raw totals (not averages) are surfaced so consumers can compute
deltas between snapshots for time-series analysis. Averages are
derived: `avg = phaseTotalUs[key] / calls`.

The `phaseTotalUs` map keys vary per encoder:

| Encoder | Phase keys |
|---------|------------|
| embed | tokenize, tensor, ort, extract |
| splade | tokenize, ort, postProcess |
| ner | tokenize, tensor, ort, extract |

## Encoder timing topology (must inform API design)

Phase timings are **not** available at a single call site in any
encoder. The accumulator API must support incremental per-phase
recording across method boundaries.

**OnnxEmbeddingEncoder:**
- `tokenize` — accumulated in `embedBatchInternal()` (line 292) or
  `embedBatchWithChunking()` (line 459), depending on entry point
- `tensor`, `ort`, `extract`, callCount — all in `embedPreTokenizedBatch()`
  (lines 359-384), called by both entry points

**SpladeEncoder:**
- `tokenize` — accumulated in two upstream callers:
  `encodeBatchTokenBudget()` (line 326) and `encodeBatchInternal()` (line 410)
- `ort`, `postProcess`, callCount — split between `runOnnxInference()`
  (lines 568-578, dense path) and `runSingleSparseInference()`
  (lines 692-715, sparse path)

**BertNerInference:**
- All phases converge in `infer()` (lines 246-255)
- `inferBatch()` has its own ORT call that **bypasses profiling entirely**
  for batches > 1 (pre-existing gap, out of scope for this tempdoc)

## Implementation status

Initial implementation in `b2e78d58b`, self-review fixes in `0a237eee2`.

| Step | Description | Status |
|------|-------------|--------|
| 1 | EncoderProfileAccumulator + EncoderProfileSnapshot | Done |
| 2 | Refactor 3 encoders to use accumulator | Done |
| 3 | OperationalMetrics pull model | Done |
| 4 | EncoderProfile proto message (field 11 on EnrichmentCoverage) | Done |
| 5 | IndexStatusOps packing | Done |
| 6 | EncoderProfileView + EnrichmentProgressView field | Done |
| 7 | WorkerStatusMapper unpacking | Done |
| 8 | jseval timeline reads encoderProfiles from /api/status | Done |
| 9 | --profile flag removed, profiling.py import removed | Done |
| 10 | EncoderProfileAccumulatorTest (6 test cases) | Done |
| 11 | HdrHistogram imports cleaned from encoders | Done |
| extra | StatusRecordSchemaTest baseline + constructor updated | Done |

### Self-review fixes (0a237eee2)

| # | Issue | Fix |
|---|-------|-----|
| 1 | `computeIfAbsent` lock on every hot-path call | `get()` fast path, fallback to `computeIfAbsent` for unregistered phases |
| 2 | `snapshot()` consistency undocumented | Javadoc caveat: phase totals may reflect slightly more calls than reported count |
| 3 | `addPhaseNs("ort")` redundant with `recordOrtCall` | `recordOrtCall` now internalizes `addPhaseNs("ort")`; 4 encoder call sites removed |
| 4 | Tests pollute OperationalMetrics singleton | `deregisterEncoder()` (package-private) + `@AfterEach` cleanup |
| 5 | Periodic logging race undocumented | Comment at each logging gate in all 3 encoders |
| 6 | Jackson casing assumption | Verified correct — default camelCase, no `PropertyNamingStrategy` |
| 7 | `_encoder_profiles` crashes `DictWriter` | `extrasaction="ignore"` on both TSV writers + convention comment |
| 8 | Zero jseval test coverage for encoder profiles | 5 new tests: snapshot carry, absent, summary, summary-absent, format |

### Verification results

- `spotlessApply` — clean
- `worker-core:test` — all pass (including EncoderProfileAccumulatorTest)
- `worker-services:test` — all pass
- `app-services:test` — all pass
- `app-api:test` — all pass (schema baselines regenerated)
- `jseval tests/test_timeline.py` — 20/20 pass (including 5 new)
- `jseval --help` — `--profile` removed from `run`, still present on `llm-bench`/`rag-eval`
- **Live pipeline** (SciFact, `--pipeline --start-backend --clean --json`):
  all three encoders report through `/api/status` with correct sub-phases:
  - embed (934 calls): tokenize, tensor, ort, extract
  - splade (5211 calls): tokenize, ort, postProcess
  - ner (12089 calls): tokenize, tensor, ort, extract

### Merge

Merged to `main` at `7d2a38a22`. 5 conflicts (encoder files + jseval
Python files) resolved by taking the branch version — 357 supersedes
356's profiling fields with the shared accumulator. Post-merge: compiles,
4 module test suites pass. Worktree and branch cleaned up.

### Note on profiling.py

`profiling.py` was committed on `main` as part of 356 (`b1b950725`).
After the 357 merge, the import and `parse_worker_log()` call in
`cli.py` are removed, so `profiling.py` is dead code. It can be deleted
in a follow-up cleanup.

## Implementation details

### 1. EncoderProfileAccumulator — shared profiling class

**New file:** `modules/worker-core/src/main/java/io/justsearch/indexerworker/metrics/EncoderProfileAccumulator.java`

Replaces the copy-pasted profiling fields in all three encoders.
Uses a **per-phase recording API** — each `addPhaseNs()` call maps 1:1
to an existing `profileXxxNs.addAndGet()` call in the encoder, so the
refactor is mechanical. No per-call allocation (unlike a Map.of()-based
approach).

```java
public final class EncoderProfileAccumulator {
    private final ConcurrentHashMap<String, AtomicLong> phaseNs;
    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong minOrtNs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxOrtNs = new AtomicLong(0);
    private final Histogram ortHistogram;  // HdrHistogram, synchronized

    /** Pre-register phase keys to avoid ConcurrentHashMap miss on hot path. */
    public EncoderProfileAccumulator(String... phases) {
        phaseNs = new ConcurrentHashMap<>(phases.length * 2);
        for (String p : phases) phaseNs.put(p, new AtomicLong());
        ortHistogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
    }

    /**
     * Accumulate time for a single sub-phase. Called from wherever the
     * phase timing is computed — may be a different method from other
     * phases. Zero-allocation hot path (ConcurrentHashMap.get + addAndGet).
     */
    public void addPhaseNs(String phase, long ns) {
        phaseNs.computeIfAbsent(phase, k -> new AtomicLong()).addAndGet(ns);
    }

    /**
     * Record completion of one ORT inference call. Increments the call
     * counter and updates the ORT latency distribution (min/max/histogram).
     * Must be called exactly once per inference call, at the site where
     * ORT elapsed time is known.
     */
    public void recordOrtCall(long ortCallNs) {
        callCount.incrementAndGet();
        minOrtNs.updateAndGet(cur -> Math.min(cur, ortCallNs));
        maxOrtNs.updateAndGet(cur -> Math.max(cur, ortCallNs));
        synchronized (ortHistogram) {
            ortHistogram.recordValue(
                Math.min(ortCallNs, ortHistogram.getHighestTrackableValue()));
        }
    }

    /** Current call count — used by encoders for periodic log gating. */
    public long callCount() {
        return callCount.get();
    }

    /** Immutable snapshot with raw cumulative totals. Returns null if no calls recorded. */
    public EncoderProfileSnapshot snapshot() {
        long calls = callCount.get();
        if (calls == 0) return null;
        var totalUs = new LinkedHashMap<String, Long>();
        for (var e : phaseNs.entrySet()) {
            totalUs.put(e.getKey(), e.getValue().get() / 1000);
        }
        synchronized (ortHistogram) {
            return new EncoderProfileSnapshot(calls, Map.copyOf(totalUs),
                minOrtNs.get() / 1000, maxOrtNs.get() / 1000,
                ortHistogram.getValueAtPercentile(50) / 1000,
                ortHistogram.getValueAtPercentile(95) / 1000,
                ortHistogram.getValueAtPercentile(99) / 1000);
        }
    }

    /** Reset all counters. Called by OperationalMetrics.resetAll(). */
    public void reset() {
        callCount.set(0);
        phaseNs.values().forEach(a -> a.set(0));
        minOrtNs.set(Long.MAX_VALUE);
        maxOrtNs.set(0);
        synchronized (ortHistogram) {
            ortHistogram.reset();
        }
    }
}
```

**New file:** `modules/worker-core/src/main/java/io/justsearch/indexerworker/metrics/EncoderProfileSnapshot.java`

```java
public record EncoderProfileSnapshot(
    long calls,
    Map<String, Long> phaseTotalUs,
    long ortMinUs, long ortMaxUs,
    long ortP50Us, long ortP95Us, long ortP99Us) {}
```

### 2. Refactor encoders to use accumulator

**Files:**
- `modules/worker-core/.../embed/onnx/OnnxEmbeddingEncoder.java`
- `modules/worker-core/.../splade/SpladeEncoder.java`
- `modules/worker-core/.../ner/BertNerInference.java`

In each encoder:

**a) Replace profiling fields** — delete the 6-8 `AtomicLong` fields
(`profileCallCount`, `profileOrtNs`, `profileTokenizeNs`, etc.),
the `HdrHistogram ortHistogram`, and `minOrtCallNs`/`maxOrtCallNs`.
Replace with a single field:

```java
// OnnxEmbeddingEncoder
private final EncoderProfileAccumulator profiler =
    new EncoderProfileAccumulator("tokenize", "tensor", "ort", "extract");

// SpladeEncoder
private final EncoderProfileAccumulator profiler =
    new EncoderProfileAccumulator("tokenize", "ort", "postProcess");

// BertNerInference
private final EncoderProfileAccumulator profiler =
    new EncoderProfileAccumulator("tokenize", "tensor", "ort", "extract");
```

**b) Replace accumulation calls** — each existing `addAndGet()` becomes
an `addPhaseNs()` call at the same location. Each existing
callCount increment + min/max update + histogram record becomes a
single `recordOrtCall()` call. This is a 1:1 mechanical replacement
that preserves the existing call-site topology.

OnnxEmbeddingEncoder example (two methods involved):
```java
// In embedBatchInternal() — tokenize timing
profiler.addPhaseNs("tokenize", System.nanoTime() - tTokenize);
// ... delegates to embedPreTokenizedBatch()

// In embedPreTokenizedBatch() — remaining phases
profiler.addPhaseNs("tensor", tOrt - tTensor);
profiler.addPhaseNs("ort", ortElapsed);
profiler.addPhaseNs("extract", System.nanoTime() - tExtract);
profiler.recordOrtCall(ortElapsed);  // also increments callCount
```

SpladeEncoder example (three methods involved):
```java
// In encodeBatchTokenBudget() or encodeBatchInternal() — tokenize
profiler.addPhaseNs("tokenize", System.nanoTime() - tTok);

// In runOnnxInference() — dense path
profiler.addPhaseNs("ort", ortElapsed);
profiler.addPhaseNs("postProcess", t2 - t1);
profiler.recordOrtCall(ortElapsed);

// In runSingleSparseInference() — sparse path
profiler.addPhaseNs("ort", ortElapsed);
profiler.addPhaseNs("postProcess", System.nanoTime() - tPost);
profiler.recordOrtCall(ortElapsed);
```

BertNerInference example (single method):
```java
// In infer() — all phases at one site
profiler.addPhaseNs("tokenize", t1 - t0);
profiler.addPhaseNs("tensor", t2 - t1);
profiler.addPhaseNs("ort", ortElapsed);
profiler.addPhaseNs("extract", t4 - t3);
profiler.recordOrtCall(ortElapsed);
```

**c) Simplify periodic logging** — replace the inline average
computation with a snapshot call:

```java
long calls = profiler.callCount();
if (calls > 0 && calls % PROFILE_LOG_INTERVAL == 0) {
    var snap = profiler.snapshot();
    // Compute averages for human-readable log output
    var avgUs = new LinkedHashMap<String, Long>();
    snap.phaseTotalUs().forEach((k, v) -> avgUs.put(k, v / calls));
    log.info("Embed per-call profile ({}calls): phases={}, ortDist=[min={}, p50={}, p95={}, p99={}, max={}]us",
        calls, avgUs,
        snap.ortMinUs(), snap.ortP50Us(), snap.ortP95Us(),
        snap.ortP99Us(), snap.ortMaxUs());
}
```

The `PROFILE_LOG_INTERVAL` constants stay per-encoder (50/20/100).
Extra context logged per-encoder (batchSize, seqLen) stays as-is.

### 3. OperationalMetrics — pull model with registered accumulators

**File:** `modules/worker-core/src/main/java/io/justsearch/indexerworker/metrics/OperationalMetrics.java`

Add:

```java
private final ConcurrentHashMap<String, EncoderProfileAccumulator> encoderAccumulators =
    new ConcurrentHashMap<>();

/** Called once per encoder at construction time. */
public void registerEncoder(String name, EncoderProfileAccumulator accumulator) {
    encoderAccumulators.put(name, accumulator);
}

/** Polled by IndexStatusOps — always returns fresh snapshots. */
public Map<String, EncoderProfileSnapshot> getEncoderProfiles() {
    var result = new LinkedHashMap<String, EncoderProfileSnapshot>();
    encoderAccumulators.forEach((name, acc) -> {
        var snap = acc.snapshot();
        if (snap != null) result.put(name, snap);
    });
    return result;
}
```

In `resetAll()`, reset each registered accumulator's counters (do NOT
clear the registration map — encoders register once in their
constructor and never re-register):

```java
encoderAccumulators.values().forEach(EncoderProfileAccumulator::reset);
```

Each encoder registers in its constructor:

```java
OperationalMetrics.getInstance().registerEncoder("embed", profiler);
```

Key property: **no interval gating**. `getEncoderProfiles()` calls
`snapshot()` which reads the live counters/histogram. Data is always
current, even if the periodic log hasn't fired yet.

### 4. Proto — add EncoderProfile message

**File:** `modules/ipc-common/src/main/proto/indexing.proto`

```protobuf
message EncoderProfile {
    int64 calls = 1;
    map<string, int64> phase_total_us = 2;
    int64 ort_min_us = 3;
    int64 ort_max_us = 4;
    int64 ort_p50_us = 5;
    int64 ort_p95_us = 6;
    int64 ort_p99_us = 7;
}
```

Add to `EnrichmentCoverage`:
```protobuf
map<string, EncoderProfile> encoder_profiles = 11;
```

The `phase_total_us` map accommodates encoder-specific sub-phases
without proto changes when a new phase is added. Raw totals enable
delta computation between successive snapshots.

### 5. IndexStatusOps — pack proto

**File:** `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/IndexStatusOps.java`

In `buildEnrichment()`, after the existing batchTiming packing:

```java
metrics.getEncoderProfiles().forEach((name, snap) -> {
    builder.putEncoderProfiles(name, EncoderProfile.newBuilder()
        .setCalls(snap.calls())
        .putAllPhaseTotalUs(snap.phaseTotalUs())
        .setOrtMinUs(snap.ortMinUs())
        .setOrtMaxUs(snap.ortMaxUs())
        .setOrtP50Us(snap.ortP50Us())
        .setOrtP95Us(snap.ortP95Us())
        .setOrtP99Us(snap.ortP99Us())
        .build());
});
```

### 6. Status API views — new record + field

**New file:** `modules/app-api/src/main/java/io/justsearch/app/api/status/EncoderProfileView.java`

```java
public record EncoderProfileView(
    long calls,
    Map<String, Long> phaseTotalUs,
    long ortMinUs, long ortMaxUs,
    long ortP50Us, long ortP95Us, long ortP99Us) {}
```

**Modified:** `modules/app-api/.../status/EnrichmentProgressView.java`
- Add field: `Map<String, EncoderProfileView> encoderProfiles`
- Update `empty()` factory to pass `Map.of()`.

### 7. WorkerStatusMapper — unpack proto

**File:** `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java`

In `toUiStatusMap()`, unpack proto `encoder_profiles` map:

```java
var profiles = new LinkedHashMap<String, EncoderProfileView>();
enrichment.getEncoderProfilesMap().forEach((name, proto) -> {
    profiles.put(name, new EncoderProfileView(
        proto.getCalls(),
        Map.copyOf(proto.getPhaseTotalUsMap()),
        proto.getOrtMinUs(), proto.getOrtMaxUs(),
        proto.getOrtP50Us(), proto.getOrtP95Us(), proto.getOrtP99Us()));
});
```

Pass `profiles` into `EnrichmentProgressView`.

### 8. jseval — read from /api/status

**File:** `scripts/jseval/jseval/timeline.py`

In `snapshot_to_row()`: read `snapshot.get("encoderProfiles", {})`.
For each encoder, store the structured profile dict directly in the
row under `encoder_profiles` (not flattened into columns — the
sub-phase keys vary per encoder, so flattening creates sparse/unstable
TSV columns). The timeline TSV continues to use the existing fixed
columns; encoder profiles are carried in the row dict for
`compute_pipeline_summary()` but not written to TSV.

In `compute_pipeline_summary()`: extract `encoder_profiles` from
the **last** timeline row (values are cumulative, so the final
snapshot has the complete picture). Structure:

```python
"encoder_profiles": {
    "embed": {"calls": N, "phase_total_us": {"tokenize": N, ...},
              "ort_min_us": N, "ort_p50_us": N, ...},
    ...
}
```

Consumers compute averages: `phase_total_us[key] / calls`. Delta
between two snapshots: `(total_B - total_A) / (calls_B - calls_A)`.

### 9. jseval — remove log parser, simplify --profile

**File:** `scripts/jseval/jseval/cli.py`

- Remove the `--profile` flag from `cmd_run`. Encoder profiles are
  now always available when `--pipeline` is used (they come from
  /api/status). The `--profile` flags on `llm-bench` and `rag-eval`
  commands are unrelated and stay as-is.
- Remove the `from . import profiling as prof_mod` import and the
  `profiling.parse_worker_log()` call from `_do_run`.

**File:** `scripts/jseval/jseval/profiling.py`

- Delete the file. One data path, no fallback.

### 10. Test — EncoderProfileAccumulator unit test

**New file:** `modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EncoderProfileAccumulatorTest.java`

OperationalMetrics has no existing tests. Add a focused test for the
accumulator covering:
- `addPhaseNs` + `recordOrtCall` + `snapshot()` produces correct cumulative totals
- `snapshot()` returns null when no calls recorded
- `reset()` zeros all counters and histogram
- Registration with OperationalMetrics: `registerEncoder()` +
  `getEncoderProfiles()` returns fresh snapshots
- Multiple encoders registered simultaneously

### 11. Cleanup — remove HdrHistogram imports from encoders

**Files:** the three encoder files from step 2.

After the refactor, the three encoder classes no longer import
HdrHistogram directly — only `EncoderProfileAccumulator` does.
Remove the stale imports. The HdrHistogram dependency stays in
`worker-core/build.gradle.kts` (the accumulator lives there).

## Verification

1. `./gradlew.bat spotlessApply && ./gradlew.bat build -x test` — compile
2. `./gradlew.bat :modules:worker-core:test` — accumulator unit test
3. `./gradlew.bat test` — full unit test suite
4. Manual: `python -m jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean --json` — verify `encoder_profiles` appears in pipeline output with per-encoder sub-phases (embed should have tokenize+tensor+ort+extract, splade should have tokenize+ort+postProcess)
5. Verify short-run correctness: profiles should appear even for small datasets (no interval gating)
6. Verify `--profile` flag is removed without breaking `jseval --help` or the unrelated `--profile` flags on `llm-bench`/`rag-eval`

## Design decisions

**Per-phase recording API over all-at-once.** Phase timings are
computed in different methods across each encoder (tokenize in one
caller, ORT+extract in a downstream method; SPLADE splits ORT across
dense and sparse paths). A single `recordCall(Map<phases>)` would
require restructuring the encoder call chains to gather all timings at
one site, or creating a per-call allocation with `Map.of()`. The
per-phase `addPhaseNs()` + `recordOrtCall()` API maps 1:1 to the
existing `profileXxxNs.addAndGet()` calls, making the refactor
mechanical with zero allocation overhead.

**Pull model over push-at-interval.** The original design pushed data
to OperationalMetrics inside the periodic logging block (every 20-100
calls). This means short runs might surface zero data, and the final
batch of calls is always missed. The pull model calls `snapshot()` on
live accumulators when IndexStatusOps builds the status response,
so data is always current.

**Raw cumulative totals over pre-computed averages.** The snapshot and
proto carry `phaseTotalUs` (cumulative microseconds) and `calls`,
not averages. This enables delta computation between successive
snapshots for time-series analysis (`(total_B - total_A) /
(calls_B - calls_A)` gives the average for the interval). Pre-computed
averages are lossy (integer division) and non-composable.
Consumers derive averages trivially: `phaseTotalUs[key] / calls`.

**Flexible sub-phase map over fixed fields.** The three encoders track
different sub-phases (tensor/extract vs postProcess). A
`map<string, int64> phase_total_us` in the proto accommodates this
without schema changes when phases are added or renamed. The ORT
distribution fields (min/max/percentiles) are fixed because they're
always present and always the same structure.

**Reset accumulator counters, not registrations.** `resetAll()` calls
`accumulator.reset()` on each registered accumulator rather than
clearing the registration map. Encoders register once in their
constructor and never re-register, so clearing the map would leave
them permanently unregistered after a reset.

**Delete profiling.py over fallback.** Maintaining two format-coupled
paths is a net negative. The API path is strictly superior: always
fresh, includes all sub-phases, no regex coupling. If the backend
isn't running, there's nothing to profile. `profiling.py` has no
tests, making deletion clean.

**Carry encoder profiles in row dict, not TSV columns.** The sub-phase
keys vary per encoder (embed has 4, splade has 3). Flattening into
TSV columns creates sparse, unstable column sets that break consumers
expecting fixed schemas. Instead, the structured profile dict is
carried in the row for `compute_pipeline_summary()` but not serialized
to the TSV file.

## Retrospective

### What was achieved

**Dual data path eliminated.** Encoder profiling flows through
`/api/status` exclusively. Live-verified on SciFact: all three
encoders report with correct sub-phases and ORT distributions.
The jseval consumer no longer imports or calls `profiling.py`.

**Accumulation infrastructure consolidated.** The 6-8 AtomicLong
fields, HdrHistogram, and updateAtomicMin/Max helpers that were
copy-pasted across three encoders now live in one class.

### What was not fully achieved

**Periodic logging still duplicated.** Each encoder has ~12 lines
of identical snapshot → compute avgUs → log.info structure. Only the
message prefix and extra context args differ. This is the same
category of duplication the tempdoc claimed to eliminate — reduced
but not removed.

**`profiling.py` not deleted.** Step 9 says "Delete the file." It
was committed on main as part of 356 before the merge. After 357,
it's dead code (import severed in `cli.py`). Should be deleted in a
follow-up.

### Regressions introduced

**Log readability degraded.** Old format: `tokenize=5223us, tensor=38us,
ort=81244us` (grep-friendly key=value pairs). New format:
`phases={tokenize=5223, tensor=38, ort=81244}` (Java Map.toString).
Harder to grep for individual phases, less human-scannable.

**Lost `total` per-call field from log.** Old code computed and logged
`total=Nus/call` (sum of all phases). New code doesn't. Anyone using
logs to spot total per-call overhead lost that signal.

### Unused infrastructure

**Raw totals for delta computation.** The stated justification for raw
totals over averages was delta computation between successive snapshots
for time-series analysis. However, jseval's `compute_pipeline_summary()`
only reads the last snapshot — no delta computation is performed.
The capability exists but has no consumer yet. Correct long-term
choice, but the immediate benefit is zero.

## Known gaps (out of scope)

**BertNerInference.inferBatch() bypasses profiling.** For batches > 1,
`inferBatch()` runs its own batched ORT session (line 392) that does
not call `profiler.recordOrtCall()`. Only the single-element fast path
(line 320) goes through `infer()` and accumulates profiling. This is a
pre-existing gap from tempdoc 356 — fixing it requires wiring profiler
calls into `inferBatch()`, which is a separate concern.

## Follow-up items

- Delete `scripts/jseval/jseval/profiling.py` (dead code after 357)
- Consider extracting periodic logging into the accumulator (eliminates
  remaining duplication across 3 encoders)
- Restore log readability: format phases as `key=Nus` pairs instead
  of Map.toString(), re-add `total=Nus/call`

## Dependencies

- **356 (Inference Observability):** Encoder profiling instrumentation (refactored here).
- **354 (Map-Based Metrics):** Existing batchTiming infrastructure (pattern followed).
