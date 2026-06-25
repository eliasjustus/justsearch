---
title: "356: Inference Pipeline Observability & Performance Verification"
type: tempdoc
status: done
created: 2026-03-25
updated: 2026-03-26
---

> NOTE: Noncanonical doc (investigation + tooling). May drift.

# 356: Inference Pipeline Observability

## Purpose

Build the observability needed to understand whether the enrichment
pipeline (tempdoc 334) is performing at expected speeds, and where
exactly time is spent within each inference call. Currently the pipeline
is a series of black boxes — we know per-batch totals for embed/SPLADE/NER
but not what happens inside each call.

This is a prerequisite for further optimization: without knowing where
time goes, we're guessing at what to optimize.

## Context

Tempdoc 334 optimized the enrichment pipeline from 837s to 204s (SciFact
5184 docs, RTX 4070 all GPU). The 204s breaks down as:

| Component | Time | Source |
|-----------|------|--------|
| GPU inference (embed+SPLADE+NER) | 155.8s | Per-batch timing from worker logs |
| Primary indexing | 25s | jseval pipeline_summary |
| Lucene writes (RMW) | 8.8s | Per-batch write_ms |
| Cleanup batches | 5.1s | Worker logs (tail batches) |
| Between-batch + startup | 9.3s | Wall minus accounted |

The GPU inference (155.8s) is the dominant cost but we don't know if
it's optimal. Roofline analysis estimates the theoretical minimum at
49s (100% efficiency) or ~81-111s (realistic 60% efficiency). The gap
between 111-155.8s is unexplained.

## What we can observe today

| Metric | Source | Resolution | How to get it |
|--------|--------|------------|---------------|
| Per-batch embed/SPLADE/NER/write/total ms | Worker JSON logs | Per batch | jseval `--pipeline --json` → `pipeline_timing.inference` |
| Stage completion timestamps | jseval timeline TSV | 2s polling | `--timeline path.tsv` |
| GPU clock/temp/power/util | gpu-monitor.ps1 | 1s | Manual script |
| Batch count per model | jseval pipeline_summary | Per run | `--pipeline --json` |
| Sub-phase timing (tokenize/ORT/extract) | Encoder INFO logs | Every N calls | `--profile --json` → `pipeline_timing.encoder_profiles` |
| ORT call count per encoder | Encoder INFO logs | Per run | `--profile --json` → `encoder_profiles.*.calls` |
| Per-call distribution (p50/p95/p99) | HdrHistogram in encoders | Per run | `--profile --json` → `encoder_profiles.*.ort_p50_us` etc. |
| SeqLen per ORT call | Encoder INFO logs | Per run | `--profile --json` → `encoder_profiles.*.seq_len` |
| Per-batch composition (embed/splade/chunks) | Combined backfill log | Per batch | `--profile` parses worker.log |
| CUDA kernel-level timing | Nsight Systems capture | Per kernel | `--nsys --json` → `pipeline_timing.gpu_profile` |
| H2D/D2H memory transfer time | Nsight Systems capture | Per transfer | `--nsys --json` → `gpu_profile.memcpy` |

## Observability gaps

### Resolved gaps (Step 1 implementation)

**Gap 1: Within-ORT-call breakdown** — RESOLVED. Sub-phase timing
(tokenize/tensor/ORT/extract) instrumented in all three encoders.
Finding: 82-92% of each encoder's time is in session.run().

**Gap 2: ORT call count verification** — RESOLVED. Cumulative call
counts logged per encoder. Finding: 900 embed, 5300 SPLADE (PRESPARSE
per-doc, not batched), 12300 NER (2.4 chunks/doc average).

**Gap 3: Per-call variance** — PARTIALLY RESOLVED. Min/max tracked
per encoder. Finding: extreme max values are first-call CUDA JIT warmup.
Remaining: no distribution shape (p50/p95/p99), no warmup curve.

### Remaining gaps and theoretically correct solutions

#### Category A: Inside ORT (the GPU black box)

**A1: Operator-level profiling.** — RESOLVED via Nsight Systems.
Top kernel: cutlass 128×128 GEMM (28% of GPU time, embed Q4 model).
Attention softmax is only 5%. Dequantize4Bits is 4%. See findings above.

*Tool used:* Nsight Systems `--trace=cuda --cuda-trace-scope=system-wide`.
*Correct solution for deeper analysis:* Two complementary tools.
(1) ORT built-in profiling (`SessionOptions.EnableProfiling`) outputs
Chrome Trace Format JSON with per-operator host-side timing. However,
host-side timing is misleading for CUDA — kernel launches are async,
so host duration can be near-zero. The accurate variant requires an
ORT build with `--enable_cuda_profiling` which links CUPTI and emits
`"cat":"Kernel"` records with true GPU-side durations per operator.
(2) **Nsight Systems** (`nsys profile java ...`) captures every CUDA
kernel launch, with GPU-side start/end times, on a system-wide
timeline. It does not natively label kernels by ORT operator name,
but NVTX annotations around session.run() plus ORT's profiling JSON
can be correlated by timestamp.

**A2: H2D / D2H transfer vs compute.** — RESOLVED via Nsight Systems.
H2D: 77ms total (0.1%), D2H: 1187ms (1.2%), compute: 93.5s (98.4%).
Memory transfers are negligible. The pipeline is purely compute-bound.

*Tool used:* Nsight Systems `cuda_gpu_mem_time_sum` report.
*Correct solution for per-model breakdown:* **Nsight Systems.** It shows `MemCpy HtoD` and
`MemCpy DtoH` as explicit, separate timeline rows with durations.
This is the only tool that cleanly separates transfer from compute
within a single session.run() call. ORT's built-in profiler does
not emit separate events for memory transfers. CUDA events
(`cudaEventRecord`) could measure GPU-side time excluding CPU launch
overhead, but ORT does not expose CUDA event handles through its
public API.

**A3: ORT execution provider routing.** We don't know which ONNX
operators run on CUDA vs CPU.

*Correct solution:* Set `SessionOptions.LogSeverityLevel = 0`
(VERBOSE) at session creation. ORT logs a one-time dump of each
graph node and its assigned EP. Additionally,
`SessionOptions.OptimizedModelFilepath` writes the post-optimization
ONNX graph to disk for inspection — reveals fusion decisions (e.g.,
GELU+MatMul fused into a single kernel). Setting
`disable_cpu_ep_fallback = true` would surface any unexpected CPU
fallbacks as errors instead of silent degradation.

**A4: BFC arena fragmentation.** VRAM is a single avg/peak number.

*Correct solution:* ORT VERBOSE logging includes BFC arena allocation
messages (allocation size, address, failure + retry). For structured
analysis, CUPTI Activity API's memory allocation events
(`CUPTI_ACTIVITY_KIND_MEMORY2`) track every `cudaMalloc`/`cudaFree`
with timestamps and sizes. Nsight Systems also captures these in
its timeline.

**A5: CUDA stream utilization.** With unified stream, all ops are
serialized on one stream.

*Correct solution:* **Nsight Systems** stream timeline view. Each
CUDA stream gets its own row; kernel execution, memory copies, and
idle gaps are all visible. This directly shows whether unified stream
serializes work that could otherwise overlap on separate streams.
To test multi-stream, disable `use_ep_level_unified_stream` and
re-profile — Nsight Systems shows the difference.

**A6: ORT thread pool behavior.** Intra-op / inter-op thread pool
interaction with JVM threads is invisible.

*Correct solution:* Combination of Nsight Systems (shows OS thread
activity, CPU-GPU correlation, context switches) and JFR
`jdk.ExecutionSample` (CPU sampling per JVM thread). For ORT-specific
thread pool sizing, ORT's `SetIntraOpNumThreads` /
`SetInterOpNumThreads` can be varied experimentally with Nsight
Systems measuring the impact.

#### Category B: Variance and distribution

**B1: Distribution shape.** Only min/max, no p50/p95/p99.

*Correct solution:* **HdrHistogram** (Gil Tene's library). Records
per-call ORT nanoTime values with 3-6ns overhead per `recordValue()`
call — negligible in a 2-80ms hot path. Fixed ~80KB memory footprint.
Reset after each batch to get per-batch distributions, or keep a
cumulative histogram for the full run. Compute p50/p95/p99/p99.9
at any point. No allocation on the recording path (no GC pressure).
This is the standard approach for latency distribution in
high-throughput JVM applications.

**B2: Warmup curve.** First call is 15x avg but we don't know the
trajectory.

*Correct solution:* Log individual ORT call durations for the first
N calls (e.g., first 20) at INFO level, then switch to aggregate
profiling. This is a code change: add an `if (profileCallCount < 20)`
branch that logs each call individually. Alternatively, the
HdrHistogram per-batch approach from B1 naturally captures this —
the first batch's histogram shows the warmup distribution.

**B3: Sequence length per ORT call.** Embed and SPLADE don't log
seqLen per call.

*Correct solution:* Log `batchSize` and `maxLen` (padded sequence
length) alongside each ORT call's timing. Both values are already
local variables in `embedPreTokenizedBatch` and `runOnnxInference`.
Adding them to the profiling accumulator (e.g., max/sum seqLen) or
the periodic INFO log is trivial instrumentation. Correlating seqLen
with ORT call time distinguishes data-driven variance from
system-driven.

**B4: JVM GC interference.** nanoTime includes GC pauses.

*Correct solution:* **JFR with custom events.** Define a custom JFR
event (`@Name("io.justsearch.OrtSessionRun")`) wrapping each
session.run() call. JFR records GC pauses (`jdk.GCPhasePause`) on
the same timeline with nanosecond resolution. Overlap analysis:
filter GCPhasePause events whose `[startTime, startTime+duration]`
intersects an ORT event's interval. JFR and System.nanoTime() use
the same clock source, so correlation is exact.

For the specific question "is NER's 39ms max a GC pause?": enable
JFR (`-XX:StartFlightRecording=...`) during a jseval run, then
query the recording for GCPhasePause events overlapping the NER
timing window.

GC logging (`-Xlog:gc*:file=gc.log:time,timenanos`) provides the
same data in a simpler format for quick checks. Safepoint logging
(`-Xlog:safepoint=trace`) adds TTSP (time-to-safepoint) which
shows how long other threads delayed a safepoint — though threads
in native (session.run()) are not blocked by safepoints.

#### Category C: Between and around ORT calls

**C1: Between-batch gap decomposition.** ~39ms/batch unaccounted.

*Correct solution:* More granular nanoTime instrumentation inside
the backfill loop. The combined backfill already has per-phase
timing (fetch, embed, splade, ner, write). The unaccounted gap is
the sum of: loop overhead, map construction, status filtering, commit
checks, signal bus checks. Adding nanoTime around each of these
(5-6 measurement points) inside `processCombinedBackfill` directly
measures the gap. Overhead: ~6 × System.nanoTime() = ~150ns per
batch, negligible vs the 39ms gap.

**C2: Thread preemption.** Lucene merges, NRT refresh, file watcher,
or GC preempting the indexing-loop thread.

*Correct solution:* **JFR + async-profiler wall-clock mode.** JFR
captures `jdk.JavaMonitorEnter` (lock contention), `jdk.ThreadPark`
(LockSupport.park), and `jdk.ExecuteVMOperation` (safepoints) with
stack traces. async-profiler in `-e wall` mode samples all threads
regardless of state — distinguishing "in native ORT" from "blocked
at safepoint" from "waiting on lock." On Linux, async-profiler also
captures native frames below JNI. On Windows, ETW context-switch
events via WPA provide OS-level preemption data.

The key insight: threads in `_thread_in_native` state (inside
session.run()) are NOT blocked by GC safepoints — they continue
in native until they re-enter the JVM. So a GC safepoint does not
interrupt the ORT call itself, but does block the JVM code between
ORT calls (map assembly, status checks). This means GC interference
affects between-call overhead, not within-call timing.

**C3: Lucene merge activity during inference.** Background merge
threads compete for CPU/IO.

*Correct solution:* Lucene's `InfoStream` on components `"IW"` and
`"MS"` logs merge start/end with wall time, bytes written, effective
IO rate (MB/s), and pause/throttle times. Subclassing
`ConcurrentMergeScheduler` allows polling `mergeThreads` at intervals
to sample live throughput via `rateLimiter.getTotalBytesWritten()`.
`mergeThreadCount()` gives the live in-flight merge count.

To measure impact on inference: correlate merge timing from InfoStream
with per-batch inference timing from the combined backfill log. If
slow inference batches cluster during active merges, the merge IO
contention is measurable. Merge thread CPU time can be measured via
`ThreadMXBean.getThreadCpuTime(mergeThread.getId())`.

For stall detection: CMS blocks the indexing thread when merge
backlog exceeds `maxMergeCount`. Subclassing `doStall()` in CMS
records cumulative stall duration directly.

#### Category D: GPU hardware utilization

**D1: Sub-second GPU utilization.** gpu-monitor polls at 1s.

*Correct solution:* **Nsight Systems** GPU metrics sampling at
microsecond intervals. This gives SM utilization, tensor core
activity, and memory bandwidth at far finer resolution than
nvidia-smi. For a lighter-weight approach, **DCGM** provides
metrics at 100ms (10Hz) resolution including SM_ACTIVE fraction,
SM_OCCUPANCY, tensor core utilization, and memory bandwidth — 10x
finer than nvidia-smi with ~5% overhead.

**D2: GPU clock during each ORT call.** Boost clocks can throttle
mid-batch.

*Correct solution:* **Nsight Systems** captures SM clock alongside
kernel executions. Alternatively, DCGM at 100ms resolution gives
clock samples that can be correlated with ORT call timestamps. For
precise per-call attribution, CUPTI Activity API provides kernel
timestamps that can be joined with DCGM clock samples.

**D3: Achieved FLOPs/s per model.** Roofline is theoretical.

*Correct solution:* **Nsight Compute** (`ncu`) profiles individual
CUDA kernels with hardware performance counters: achieved FLOPs/s,
memory bandwidth utilization, warp occupancy, and a built-in
roofline chart showing compute vs memory boundedness. This
definitively answers "is this kernel at the GPU's achievable limit?"
Note: Nsight Compute replays kernels and locks clocks, so its
timings are not representative of production — use it only for
per-kernel efficiency analysis, not end-to-end latency.

#### Category E: Data and document level

**E1: Input characteristics per batch.** Doc lengths, token counts,
chunk counts per batch are unknown.

*Correct solution:* Log per-batch statistics in the combined backfill:
total tokens (sum of seqLens across all docs in the batch), max
seqLen, number of chunked documents, and number of ORT sub-batches.
The tokenization phase already computes these values — logging them
is zero-overhead. This data, joined with per-batch timing, directly
explains timing variance.

**E2: Per-document lifecycle.** No per-document latency tracking.

*Correct solution:* Timestamp each document's key lifecycle events:
(a) queue entry (when added to pending IDs), (b) batch selection
(when popped from ArrayDeque), (c) enrichment completion (when all
three phases finish). Store as a per-doc log line or structured
event. Queue wait time = (b) - (a). Processing time = (c) - (b).
This reveals whether documents sit in the queue for many batches or
are processed quickly.

**E3: Cross-phase correlation.** Does a slow embed batch predict a
slow SPLADE batch?

*Correct solution:* The combined backfill already processes all three
phases for the same document set per batch. The doc IDs are known.
Logging embed_ms, splade_ms, ner_ms per batch (already done) plus
the input statistics from E1 allows correlation: scatter-plot
embed_ms vs total_tokens per batch to see if longer batches explain
timing. SPLADE and embed process different doc subsets within a
batch (SPLADE uses chunk content, embed uses parent content), so
doc-level correlation requires tracking which specific docs went
to which phase — available from the embedDocIds/spladeDocIds lists.

### Out of scope

**Search quality verification** — handled separately.

## Roofline analysis

RTX 4070: 29.15 TF FP16, 504 GB/s bandwidth, ridge point 57.8 FLOPs/byte.

All three models are compute-bound at their batch sizes (arithmetic
intensity >> ridge point). Theoretical minimum per ORT call:

| Model | Params | Precision | Batch | Theoretical/call | Actual/call | Efficiency |
|-------|--------|-----------|-------|-----------------|-------------|------------|
| Embed | 300M | Q4 | 8 | 42ms | ~100ms | 42% |
| SPLADE | 67M | FP16 | 16 | 19ms | ~36ms | 53% |
| NER | 67M | FP16 | 1 | 1.2ms | 6.8ms | 18% |

The 42-53% efficiency for embed/SPLADE is normal (research says 50-70%
is typical for well-optimized ORT on GPU). The 18% NER efficiency is
anomalous — 82% of each call is fixed per-call overhead (5.6ms) that
dominates the 1.2ms of actual GPU compute.

Theoretical corpus total: 49s (100% efficiency), 81-111s (60% realistic).
Actual: 155.8s. Gap analysis:
- NER per-call overhead: ~28s (5000 × 5.6ms)
- ORT scheduling/kernel launch across all models: ~15-25s
- Remaining: measurement uncertainty

## Findings already established

- **GPU thermal throttling:** Disproved. GPU runs at 2600-2800 MHz,
  temp 63C max, power 168W. Run-to-run variance is from ORT tunable
  op state or CUDA kernel cache, not thermal.
- **ORT tunable op tuning:** Hurts by ~11s per run. Disabled. Runtime
  profiling overhead exceeds benefit since results don't persist across
  JVM restarts.
- **Between-batch gap:** Essentially zero. Tight loop has no idle time.
  The 39ms/batch internal gap is map assembly + status checks.
- **First-batch warmup:** Embed +1021ms, NER +634ms on batch 1 (CUDA
  kernel JIT compilation). Subsequent batches are steady-state.
- **NER FP16 GPU batching:** Never tested. Phase 2 (item 14) only
  tested CPU INT8 batch (regressed) and GPU INT8 batch (regressed from
  dequantization). GPU FP16 batch has different cost-benefit due to
  12x larger per-call overhead on GPU vs CPU. See tempdoc 334 for
  implementation priority.

## Implementation plan

### Step 1: Sub-phase timing inside encoders (Gap 1 + Gap 2 + Gap 3) — DONE

Each encoder logs its own profiling independently at INFO level.
The combined backfill log line is unchanged — sub-phase detail lives
in each encoder's own periodic log messages.

**Investigation findings:**
- NER already had full 4-phase profiling (tokenize/tensor/ORT/extract),
  logged every 100 calls at INFO. Only needed min/max ORT call time
  for variance analysis (Gap 3).
- SPLADE had ORT+postProcess timing at DEBUG level in `runOnnxInference`.
  Missing: tokenize timing, aggregate profiling at INFO, min/max.
- Embed had no sub-phase timing. Needed full instrumentation.
- Surfacing sub-phase timing through the EmbeddingProvider → EmbeddingService
  → AiBackend → OnnxEmbeddingEncoder chain into the combined backfill log
  is too invasive. Each encoder logs independently instead.
- **Critical path correction:** The combined backfill calls
  `embedBatchWithChunking()` (via OnnxEmbeddingBackend), NOT `embedBatch()`.
  Tokenize timing added to both paths.
- **SPLADE PRESPARSE path:** Current model uses PRESPARSE output format,
  which routes through `runSingleSparseInference` (per-doc ORT calls),
  not the MLM_LOGITS `runOnnxInference` path. Profiling added to both.

**OnnxEmbeddingEncoder** (implemented):
- Added accumulators: `profileCallCount`, `profileTokenizeNs`, `profileTensorNs`,
  `profileOrtNs`, `profileExtractNs`, `minOrtCallNs`, `maxOrtCallNs`
- `embedBatchInternal()`: tokenization loop timed
- `embedBatchWithChunking()`: tokenization loop timed (this is the actual
  combined backfill path via OnnxEmbeddingBackend)
- `embedPreTokenizedBatch()`: tensor creation, session.run(), pool+normalize timed
- Logs every 50 ORT calls at INFO

**SpladeEncoder** (implemented):
- Added accumulators: `profileCallCount`, `profileTokenizeNs`, `profileOrtNs`,
  `profilePostProcessNs`, `minOrtCallNs`, `maxOrtCallNs`
- `encodeBatchTokenBudget()`: `batchEncode()` tokenization timed
- `encodeBatchInternal()`: per-text tokenization timed
- `runOnnxInference()` (MLM_LOGITS path): ORT + postProcess accumulated + INFO log
- `runSingleSparseInference()` (PRESPARSE path): ORT + postProcess accumulated + INFO log
- Logs every 20 ORT calls at INFO

**BertNerInference** (implemented):
- Added `minOrtCallNs`, `maxOrtCallNs` tracking alongside existing accumulators
- Existing 100-call INFO logging extended with `(min=Xus, max=Yus)`

All three encoders include cumulative ORT call count in their profiling
log line, fulfilling Gap 2 (call count verification). Min/max ORT call
time fulfills Gap 3 (variance analysis).

**Log line formats:**
```
Embed per-call profile (50calls): tokenize=Xus, tensor=Xus, ort=Xus (min=Xus, max=Xus), extract=Xus, total=Xus/call, batch=8
SPLADE per-call profile (20calls): tokenize=Xus, ort=Xus (min=Xus, max=Xus), postProcess=Xus, total=Xus/call
NER per-call profile (100calls): tokenize=Xus, tensor=Xus, ort=Xus (min=Xus, max=Xus), extract=Xus, total=Xus/call, seqLen=X
```

### Step 2: Nsight Systems GPU profiling (A1 + A2) — DONE

Installed Nsight Systems 2026.2.1. Captured system-wide CUDA activity
during a full jseval pipeline run. Resolved A1 (operator-level profiling)
and A2 (H2D/D2H transfer vs compute). See "Nsight Systems findings" above.

**Capture method:** Start nsys in background with dummy target
(`cmd.exe /c ping`), `--cuda-trace-scope=system-wide`, then run jseval
normally. nsys captures all CUDA from the Worker process.

### Step 3: Java-side instrumentation (B1 + B3 + E1) — DONE

**B1: HdrHistogram for per-call distribution** — wired into all three
encoders. Each profiling log now includes p50/p95/p99 percentiles.
Finding: embed p95=152ms (1.9× p50=81ms), SPLADE p95=8.7ms (1.4× p50),
NER p95=4.6ms (1.6× p50). All distributions are tight — no bimodal
patterns. Long-tail is sequence-length driven.

**B3: SeqLen per ORT call** — each encoder now logs `seqLen=X` or
`batch=X, seqLen=Y` alongside timing. Embed seqLen=393 (padded max in
sub-batch), SPLADE seqLen=259, NER seqLen varies per chunk.

**E1: Per-batch composition** — combined backfill log now includes
`docs=150 (embed=150,splade=100,chunks=50)` showing how many docs go
to each enrichment phase and how many are chunk docs.

### Step 4: Automation gap — profiling data not surfaced to agents

The observability instrumentation works but is not automated. Every
tool requires manual, ad-hoc steps that a new agent cannot reproduce
without reading this tempdoc and reverse-engineering the workflow.

**Problem 1: Nsight Systems requires manual orchestration.**
No script or jseval integration. An agent must: start nsys with a dummy
target + `--cuda-trace-scope=system-wide` in background, then run
jseval separately, then manually stop the session, then run
`nsys stats --report cuda_gpu_kern_sum` etc. The correct flag
combination (`--trace=cuda --cuda-trace-scope=system-wide`) was
discovered through trial and error in this session.

**Problem 2: Encoder profiling data is invisible to jseval.**
HdrHistogram percentiles, call counts, sub-phase timing, and seqLen
are logged to worker.log as periodic INFO lines. jseval's `--json`
output and `pipeline_summary` don't extract them. An agent must grep
worker.log and manually parse the format strings.

**Problem 3: Per-batch composition stats not in pipeline_summary.**
The extended combined backfill log has embed/splade/chunk counts per
batch, but jseval doesn't surface this in structured output.

**Problem 4: No documented end-to-end workflow.**
A new agent has no single command or documented procedure to run a
full profiling capture (Nsight Systems + encoder profiling + pipeline
timing) and get structured results.

**Resolution (implemented and verified end-to-end):** Two new jseval flags:

```bash
# Encoder profiling only (parses worker.log after pipeline):
python -m jseval run --dataset scifact --max-queries 0 --pipeline \
  --start-backend --clean --profile --json

# Full profiling (encoder + Nsight Systems GPU kernels):
python -m jseval run --dataset scifact --max-queries 0 --pipeline \
  --start-backend --clean --profile --nsys --json
```

Data appears in JSON output under `pipeline_timing.encoder_profiles`
and `pipeline_timing.gpu_profile`. New modules:
- `scripts/jseval/jseval/profiling.py` — worker.log parser
- `scripts/jseval/jseval/nsys.py` — Nsight Systems lifecycle + SQLite query

Backend return type changed: `start_backend()` returns `BackendInfo(proc, data_dir)`
instead of bare `Popen`, so jseval knows where worker.log is.

**Verified:** `--profile` confirmed working with real pipeline run.
JSON output includes `encoder_profiles` with calls, percentiles, seqLen
for all three encoders. `--nsys` SQLite query verified against real
Nsight Systems capture. Console formatting added to `timeline.py`.

### Step 5: Remaining low-priority items

**C1: Between-batch gap decomposition** — low value. Distributions
show no interference; gap is likely map construction overhead.

**B4: JFR GC correlation** — low value. Distributions are tight and
unimodal, suggesting negligible GC interference.

**D3: Nsight Compute per-kernel roofline** — informational only. Would
confirm hardware limits but not yield actionable optimizations.

**A3: ORT VERBOSE EP routing** — code added but Nsight Systems data
already shows all compute-heavy ops are CUDA kernels.

### Step 4: ORT built-in profiling (Gap 4, lower priority)

ORT's EnableProfiling outputs Chrome Trace Format JSON but only host-side
timing for CUDA (misleading per Issue #20398). Nsight Systems already
provides accurate GPU-side kernel timing, making ORT profiling lower
priority. Still useful for operator ordering and graph structure.

Blocked by jseval force-kill. Options remain:
- Modify jseval `stop_backend()` for graceful shutdown
- Add shutdown hook calling `session.endProfiling()`
- Manual backend start/stop

## Known issues and fix status

**1. nsys session management** — FIXED. `start_capture()` now uses
`--session-new=jseval-<timestamp>` and returns the session name.
`stop_capture()` uses the known name directly instead of parsing
`nsys sessions list`.

**2. nsys dummy process** — DOCUMENTED. `ping` keepalive retained
(nsys profile requires a target process). `--session-new` on
`nsys profile` is the correct approach since `nsys start` doesn't
accept `--trace` flags.

**3. `--nsys` e2e test** — PENDING. Requires a successful pipeline
run. The "watcher bug" was misdiagnosed (0 added is expected after
initial walk). Next step: run `jseval run --nsys --profile` e2e.

**4. Empty profile warning** — FIXED. `parse_worker_log()` now
accepts `expected_encoders` set and warns for missing encoders.
Also warns if no profiling lines found at all.

**5. Partial nsys capture on error** — FIXED. Each profiling
collection step (nsys + log parser) has its own `try/except` in
`_do_run`. One failure doesn't prevent the other or the JSON output.

**6. Thread-safe accumulators** — FIXED. All three encoders now use
`AtomicLong` for accumulators and CAS-loop `updateAtomicMin`/`Max`.
`Histogram` kept as single-threaded (correct — only indexing-loop
thread accesses it). Initial `Recorder` approach was reverted because
`getIntervalHistogram()` returns per-interval percentiles, not
cumulative — a silent semantic change from the original `Histogram`
behavior.

**7. Dual data path (log parser vs API)** — DEFERRED to tempdoc 357.
Correct fix is pushing encoder profiles through OperationalMetrics →
/api/status → jseval (same path as batchTiming). Current log parser
works as an interim solution.

## Tooling installed

- **Nsight Systems 2026.2.1** — installed at `D:/tools/NsightSystems/`.
  CLI: `D:/tools/NsightSystems/target-windows-x64/nsys.exe`.
  Verified: captures system-wide CUDA kernel and memory activity during
  jseval pipeline runs using `--cuda-trace-scope=system-wide`.
  Usage: start nsys with dummy target in background, then run jseval
  normally. nsys captures all CUDA from any process on the system.
- **HdrHistogram 2.2.2** — added to worker-core dependencies. Available
  for per-call distribution tracking (B1).

## Nsight Systems findings (first capture)

SciFact 5184 docs pipeline captured with `--trace=cuda --cuda-trace-scope=system-wide`.

**GPU is 98.4% compute, 1.6% memory ops.** H2D/D2H transfer overhead
is negligible — the pipeline is purely compute-bound. Gap A2 is resolved:
PCIe transfer is NOT a bottleneck for any model, including NER at batch=1.

| Category | Time | % of GPU |
|----------|------|----------|
| GPU kernel compute | 93.5s | 98.4% |
| Device-to-Host memcpy | 1.19s | 1.2% |
| Device-to-Device memcpy | 0.25s | 0.3% |
| Host-to-Device memcpy | 0.08s | 0.1% |

**Top kernels by time (3.1M kernel instances total):**

| Kernel | Time | % | Instances | Avg | What it does |
|--------|------|---|-----------|-----|-------------|
| cutlass tensorop_s1688gemm 128×128 | 26.6s | 28% | 107K | 248us | Main embed GEMM (Q4 dequant → FP16 matmul) |
| cutlass tensorop_s1688gemm 256×64 (tn) | 4.6s | 5% | 17K | 270us | Embed FFN matmul variant |
| attention_softmax (1024) | 4.5s | 5% | 8K | 548us | Embed attention softmax |
| cuApplyLayerNorm | 3.8s | 4% | 138K | 27us | LayerNorm across all models |
| cutlass tensorop_s1688gemm 256×64 (nn) | 3.7s | 4% | 17K | 215us | Another matmul variant |
| Dequantize4BitsKernel | 3.5s | 4% | 161K | 21us | Q4 weight dequantization (embed model) |
| ampere_fp16 s16816gemm 128×64 | 3.5s | 4% | 187K | 19us | FP16 GEMM (SPLADE/NER) |

**Key insights:**
- The embed model (Q4) dominates: its 128×128 GEMM alone is 28% of all
  GPU time. Dequantize4BitsKernel adds another 4%.
- SPLADE and NER use FP16 GEMMs (ampere_fp16 kernels) which are smaller
  per-call but numerous.
- Attention softmax is 5% — not the bottleneck.
- LayerNorm is 4% (138K calls at 27us each) — small per-call but frequent.
- 3.1 million kernel instances in ~93s = ~33K kernel launches per second.

## Verification results (first instrumented run)

SciFact 5184 docs, RTX 4070, all GPU. Pipeline wall time 192.3s.

### ORT call counts (Gap 2 verified)

| Encoder | Actual ORT calls | Expected estimate | Match? |
|---------|-----------------|-------------------|--------|
| Embed | 900 | ~1000 (53 × ceil(150/8)) | Close — fewer docs per batch than expected |
| SPLADE | 5300 | ~350 (MLM_LOGITS estimate) | PRESPARSE runs per-doc, not batched |
| NER | 12300 | ~5000 (1 per doc) | 2.4× expected — NerService chunks each doc (~2.4 chunks avg) |

**Key finding:** SPLADE uses PRESPARSE format which runs one ORT call per
document (not batched sub-batches). The 350-call estimate assumed MLM_LOGITS
batching. Actual: 5300 calls at 5.4ms/call = 28.6s total ORT. This is
higher than the 31s batch-level timing because the per-call measurement
excludes tokenization and sub-batch assembly overhead.

**NER call count:** 12300 calls for 5184 docs = 2.37 chunks per doc average.
NerService splits content with ChunkSplitter(400 chars, 50 overlap), so
longer SciFact abstracts produce 2-3 chunks.

### Sub-phase breakdown (Gap 1 verified)

| Encoder | Tokenize | Tensor | ORT (GPU) | Extract/Post | Total |
|---------|----------|--------|-----------|-------------|-------|
| Embed | 5.2ms (5.9%) | 0.04ms | 81.2ms (91.5%) | 2.3ms (2.6%) | 88.8ms |
| SPLADE | 0.4ms (7.5%) | — | 5.4ms (91.7%) | 0.04ms (0.7%) | 5.9ms |
| NER | 0.4ms (16.3%) | 0.04ms | 2.2ms (81.7%) | 0.01ms | 2.6ms |

**Conclusion:** All three encoders are GPU-dominated (82-92% in session.run()).
Tokenization is <6% for embed/SPLADE, 16% for NER. CPU pipelining of
tokenization would save at most 5-16% per encoder — modest.

The 81.2ms/call for embed at batch=8 confirms the roofline estimate of
~100ms/call (42% efficiency). SPLADE at 5.4ms/call (PRESPARSE per-doc)
matches expectations. NER at 2.2ms/call is lower than the old 6.8ms
estimate — efficiency improved from 18% to ~55%.

### Per-call variance and distribution (Gap 3 verified)

| Encoder | Min | p50 | p95 | p99 | Max | p95/p50 |
|---------|-----|-----|-----|-----|-----|---------|
| Embed | 12.9ms | 81.5ms | 152.7ms | 237.5ms | 1288ms | 1.87× |
| SPLADE | 2.6ms | 6.3ms | 8.7ms | 9.8ms | 140ms | 1.38× |
| NER | 1.4ms | 2.8ms | 4.6ms | 6.1ms | 46ms | 1.64× |

All distributions are unimodal and tight. The p95/p50 ratios (1.4-1.9×)
show moderate spread from sequence length variation, not bimodal patterns
or system interference. Max values are first-call CUDA JIT warmup
(15-50× avg), not representative of steady-state.

SPLADE is the tightest (p95 only 38% above p50) because PRESPARSE
per-doc calls have consistent sequence lengths. Embed has the most
spread (p95 87% above p50) from variable document lengths in sub-batches
of 8.

## Dependencies

- **334 (Single-Pass Enrichment):** The pipeline being observed.
- **354 (Map-Based Metrics):** Existing per-batch timing infrastructure.
- **357 (Encoder Profiles API Path):** Follow-up to eliminate the
  worker.log parsing path in favor of /api/status.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Inference observability tempdoc (681 lines) with dependencies on 334, 354, 357. ADR-0027 (MetricCatalog as telemetry contract) is the structural consumer of this work — observability infrastructure now flows through MetricCatalog.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

