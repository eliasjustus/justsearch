---
title: "394 — Encoder call-path batching (theory + observation)"
---

# 394 — Encoder call-path batching (theory + observation)

**Status:** Open, theory + measurement. Implementation explicitly out of scope for this doc — land follow-up tempdocs when we pick this up.
**Created:** 2026-04-20.
**Revised:** 2026-04-20 — Phase 3 experiments E1/E6/E6d/E6' complete; shrinkage identified as the dominant per-call penalty.
**Sources:** tempdoc 390 Batch E full sweep (2026-04-20); 391 scifact baseline (2026-04-19); call-path audit of `modules/worker-services/.../loop/ops` and `modules/worker-core/.../{embed,splade,ner}` (2026-04-19); scifact profile run with `EncoderProfileAccumulator` phase breakdown (2026-04-19); Phase-3 experiments 2026-04-20 — E1 (sequential-execution confirmed), E6 (shrinkage load-bearing, aborted), E6d (semaphore contributes ~13 %), E6' (shrinkage removal measured at 1.82× pipeline speedup).
**Owner:** TBD (natural home is 391 / 311-family).

---

## Executive summary (revised 2026-04-20 after item 4 disabled)

**Landed state: items 1 + 2 only. Item 4 implemented, tested, and then reverted.**

**Measured cumulative wins (commit `9c64042db`):**
- **Items 1 + 2** (NER batched to `inferBatch`, PRESPARSE batched@batch=4): **Run A = 190.6 s / 1.33×** vs 253 s baseline. NER call count 12,314 → 7; SPLADE 5,318 → 1,318; embed unchanged.

**Item 4 status: implemented, measured, reverted.** Per-session `disableArenaShrinkage` plumbing was added through `OrtSessionFactory` → `OrtSessionManager.Builder` and wired on the embed session (both the `buildSessionManager` path and the `KnowledgeServer` production path). Default embed arena was raised 3072 → 6144 MB. Measured effect (Run D'''): **+1.046× over Run A (−8.4 s pipeline)** at a cost of **~5 GB extra held VRAM continuously**. Decision 2026-04-20: **revert** — the per-call shrinkage saving (~8 ms × 945 embed calls = ~7.6 s) is real but too marginal relative to the VRAM cost. The "original" 1.82× E6' projection was a cross-product artefact — items 1+2 already captured most of the NER+SPLADE shrinkage-off savings via call-count collapse, leaving only embed as a beneficiary, and embed's contribution alone is ~8 s.

**Remaining ceiling from the Run A landed state:**
- SPLADE batch > 4 (blocked by absolute `ps_rmax` memory requirement — path B adaptive sub-batching is the route, not pursued yet).
- Embed batch > 8 (BFCArena fragmentation under `kSameAsRequested`; even item 4's 6 GB arena only delayed thrashing, Run E proved it doesn't eliminate it).
- Residual 3–4× embed/SPLADE overhead vs raw CUDA (not reachable from Java without IoBinding).

### Current landed state (updated 2026-04-20 after item 4 reverted)

| Item | State | Note |
|---|---|---|
| 1 — NER wire-up | **Landed on `main`** (commit `9c64042db`) | `NerBackfillOps.java:88` + `CombinedEnrichmentBackfillOps.java:344` swapped to `extractEntitiesBatch(List.of(content))`. NER call count dropped 12,314 → 7 (Run A). |
| 2 — batched PRESPARSE | **Landed on `main` at `MAX_SPLADE_BATCH_SIZE_GPU = 4`** (commit `9c64042db`) | Rewrite of `SpladeEncoder.runSparseOutputInference` live. Higher batch sizes blocked by absolute `ps_rmax` memory request; see path B adaptive sub-batching. |
| 3 — embed `MAX_ORT_BATCH_SIZE` raise | **Reverted twice** | Attempted 8 → 16 initially; 51 OOMs. Re-tested under item 4's 6 GB arena + shrinkage-off (Run E): progressive fragmentation — started at 0 OOMs, grew to 33 by 195 s with CPU-fallback thrashing. Back at 8. |
| 4 — embed shrinkage-off + 6 GB arena | **Implemented, measured, reverted 2026-04-20** | Run D''' measured +1.046× (−8.4 s pipeline) at ~5 GB extra held VRAM. Reverted because effort/VRAM cost disproportionate to marginal gain. Per-call saving decomposition (below) explains why stacking E6' onto items 1+2 substantially under-performs the isolated E6' measurement. All code reverted to commit `9c64042db` state. Investigation preserved in this tempdoc for future reference. |

### Measured baseline after items 1 + 2 (Run A, 2026-04-20)

Clean jseval scifact with items 1+2 landed, item 3 reverted, default `kSameAsRequested` + shrinkage-on:

| Metric | Baseline (pre-394) | Run A (items 1+2) | Delta |
|---|---|---|---|
| Pipeline wall | 252.9 s | **190.6 s** | **−62.3 s (1.33× speedup)** |
| docs/sec | 19.5 | 26.9 | +38 % |
| embed calls / ortP50 | 952 / 52.9 ms | 945 / 45.4 ms | unchanged / −14 % |
| SPLADE calls / ortP50 | 5 318 / 17.5 ms | **1 318** / 50.3 ms | **−75 % / +187 %** (batch=4) |
| NER calls / ortP50 | 12 314 / 3.17 ms | **7** / 1.91 ms | **−99.94 % / −40 %** |
| embed ortMax | 19 731 ms | 936.8 ms | −95 % (pathological outlier gone) |

Items 1 + 2 together capture **1.33× pipeline speedup** — the clean-measurement gap is now closed. The prior 1.06× observation was entirely caused by item 3's fallback thrashing; the underlying landed work is substantially stronger than that polluted number suggested.

### `kNextPowerOfTwo` experiment outcome — RULED OUT as standalone fix (Runs B + C, 2026-04-20)

**Run B** — flip `arena_extend_strategy=kSameAsRequested` → `kNextPowerOfTwo` at current (batch=4, batch=8) caps:

| Metric | Run A (kSameAsRequested) | Run B (kNextPowerOfTwo) | Delta |
|---|---|---|---|
| Pipeline wall | 190.6 s | **215.2 s** | **+24.6 s (1.13× slower — NET REGRESSION)** |
| embed ortP50 | 45.4 ms | 48.4 ms | +7 % |
| **SPLADE ortP50** | **50.3 ms** | **82.8 ms** | **+65 %** |
| NER ortP50 | 1.91 ms | 1.74 ms | −9 % |
| VRAM peak | ~4.2 GB | ~4.8 GB | +14 % |
| OOMs | 0 | 0 | clean |

`kNextPowerOfTwo` alone regresses SPLADE per-call cost by 65 % at batch=4 with zero OOM benefit. Strategy-only is a pure regression at current caps.

**Run C** — `kNextPowerOfTwo` + raise `MAX_SPLADE_BATCH_SIZE_GPU = 16` and `MAX_ORT_BATCH_SIZE = 16`:
Run terminated at ~160 s (GPU util collapsed 92 % → 2 % within 60 s from CPU-fallback thrashing). Partial worker.log grep:

- **33 SPLADE OOMs on `ps_rmax` / decoder Add nodes** at batch=16, seqLen ≥ 236. Requested sizes: 3.2–5.4 GB contiguous; arena cap 4 GB. **The request exceeds the cap absolutely**, not by fragmentation — an empty `kNextPowerOfTwo` arena could not satisfy a 5 GB ask against a 4 GB cap.
- **0 embed OOMs.** Embed batch=16 ran clean under `kNextPowerOfTwo` — meaning embed's prior 51-OOM crash was genuine fragmentation that `kNextPowerOfTwo` does resolve. **Item 3 is unlockable under this strategy in isolation.** But the SPLADE regression from Run B outweighs embed's ~8 s projected gain.

**Net decision**: `kNextPowerOfTwo` as a global setting is not worth adopting. Reverted. Items-2-follow-up path A (arena strategy change) is **ruled out as a standalone fix**. Path B (adaptive sub-batching inside `runSparseOutputInference`) and path C (pinned-output redesign extending 386's contract) remain.

**Secondary finding worth recording**: the fact that embed batch=16 is OOM-free under `kNextPowerOfTwo` but SPLADE batch=16 is not suggests **per-session arena strategy** (embed uses `kNextPowerOfTwo`, others keep `kSameAsRequested`) is a viable narrower experiment. Would unlock item 3 in isolation for ~8 s saving. Not pursued today — effort-to-payoff ratio is worse than item 4's 114 s.

### Strategic pivot (2026-04-20, post-Run C)

Item 4 (E6' config: shrinkage-off + embed 6 GB cap) is now unambiguously the next highest-leverage action. Projected delta stacking on Run A's 190.6 s baseline: **~105 s** (E6' measured 1.82× on a 253 s baseline applied proportionally to 190.6 s items-1+2 baseline). Ships as a single unconditional config change — delivery machinery for coexisting with a loaded LLM is tracked separately in tempdoc 395 A7 and is not a prerequisite.

The `kNextPowerOfTwo` question is settled; the item-2-follow-up attention moves to path B (adaptive sub-batching) when/if reclaiming SPLADE batch > 4 becomes the remaining bottleneck.

---

## The observation

**scifact profile run (2026-04-19, 5 184 docs, 253 s pipeline wall, 19.5 docs/s):**

| Encoder | ORT calls | tokenize phase | tensor phase | extract / postProc | **ort phase** | **ORT share of total** | ort p50 / call |
|---|---|---|---|---|---|---|---|
| embed | 952 | 8.54 s | 0.05 s | 2.53 s | **74.68 s** | **87 %** | 52.9 ms |
| SPLADE | 5 318 | 2.78 s | — (not instrumented) | 0.39 s | **93.98 s** | **97 %** | 17.5 ms |
| NER | 12 314 | 7.05 s | 0.46 s | 0.26 s | **40.71 s** | **84 %** | 3.17 ms |

**Batch E raw CUDA FP16 measurements (390, standalone probe, seq_len = 128):**

| Encoder | batch = 1 p50 | batch = 8 p50 (interpolated) | batch = 16 p50 | Raw-vs-production ratio |
|---|---|---|---|---|
| embed | 2.93 ms | ~9 ms | 14.44 ms | **~8.7×** (production 78.4 ms/call at batch=8 vs raw ~9 ms) |
| SPLADE | 2.13 ms | — | 1.69 ms | **~8.3×** (production 17.5 ms vs raw ~2 ms at effective batch=1) |
| NER | 1.13 ms | — | 4.76 ms | **~3×** (production 3.17 ms vs raw 1.1 ms at batch=1) |

Encoders run **almost fully serialized** (`OrtSessionManager.gpuInferenceSemaphore = Semaphore(1)`): sum of encoder walls = 231 s, pipeline wall = 253 s. Any reduction in per-encoder wall maps ~1:1 to pipeline-wall reduction. **Sequential execution confirmed at implementation level** — all 407 per-call profiler logs (embed + SPLADE + NER) share `thread_name="indexing-loop"` in worker.log (E1, 2026-04-20).

## Arena configuration in production (correction)

Earlier revisions of this doc framed our arenas as "per-session silos that hold their peak forever." **That framing was wrong.** Measured 2026-04-20:

- **Shrinkage is already enabled** on every GPU session. `OrtSessionFactory.createGpuRunOptions()` (line 231-234) adds `memory.enable_memory_arena_shrinkage=gpu:0` to RunOptions; all four GPU sessions log `arenaShrinkage=enabled` at init. Between each `session.run()` call, BFCArena returns unused memory to the CUDA heap.
- **Arena caps**: embed 3072 MB, splade 4096 MB, NER 512 MB, reranker 2048 MB. Sum = 9728 MB.
- **Actual VRAM profile** (scifact baseline): peak **4811 MB**, avg **4195 MB**. Only 49 % of sum-of-caps. Oscillates in a ~650 MB band (4165 ↔ 4811 MB) as the active encoder's arena grows and shrinks between runs.

So steady-state VRAM is governed by `max(active-encoder-peak) + sum(other-encoders-shrunk-baselines)`, not `sum(per-encoder-peak)`. **Shrinkage is functionally equivalent to the P3 shared-allocator mechanism for VRAM purposes** — it's a different means to the same end.

**Consequence for item 4**: the shared-allocator P3 argument (now in tempdoc 397) is not a VRAM-saving argument. Item 4's value is *latency*, not memory. The E6 / E6d / E6' experiments below decomposed that latency question.

## Phase 3 experiments — condensed record

Three experiments decomposed the `ort`-phase penalty candidates. Each was a
scifact jseval run with a single config variable changed from the pre-394
baseline (252.9 s, shrinkage-on, all encoders active).

| Experiment | Config change | Result | Takeaway |
|---|---|---|---|
| **E6** (aborted) | shrinkage-off at current arena caps | 544.5 s (2.15× slower); 10+ embed OOMs; VRAM peak 9.6 GB | Arena caps assume shrinkage coalesces between calls — load-bearing. Shrinkage-off alone is infeasible; needs a bigger embed cap first (→ E6'). |
| **E6d** | embed-alone, shrinkage-on (SPLADE/NER env-disabled) | embed p50 52.9 → 45.9 ms (−13 %); embed ortMax 19.7 s → 658 ms (−97 %) | Semaphore wait is ~13 % of per-call `ort` — modest, not dominant. ortMax outlier was cross-encoder initialisation noise, not a content-bound stall. |
| **E6'** | shrinkage-off + embed arena 3072 → 6144 MB | **138.7 s (1.82×)**; embed p50 −26 %, SPLADE p50 −60 %, NER p50 −54 %; 0 OOMs; VRAM peak 10.5 GB | Shrinkage is the dominant per-call penalty. NER reaches the raw-CUDA floor (~1.3× residual). Embed/SPLADE still carry a ~3–4× residual (CUDA stream sync / kernel launch / Java binding overhead, not reachable from Java without IoBinding). |

E6' is the measurement that later motivated item 4 and its revert (see the "Item 4 — implementation record" section below). The 10.5 GB VRAM peak exceeds the 12 GB card when a concurrent LLM is loaded — coexistence is out of scope here and lives in tempdoc 395 A7.

## Pre-394 call-path state (historical)

Before items 1 + 2 landed, each encoder's call pattern was:

| Encoder | Behaviour | ORT calls / 5 184 docs |
|---|---|---|
| embed | Batched at `MAX_ORT_BATCH_SIZE = 8` from the start | 952 (~5.4 docs/call) |
| SPLADE | Batch API existed but `runOnnxInference` short-circuited to `runSparseOutputInference` for PRESPARSE exports, which looped per-doc. Item 2 rewrote this method to batch. | 5 318 (effective batch ≈ 1) |
| NER | True per-doc: `NerBackfillOps:88` called single-doc `extractEntities`, looping `infer()` per chunk. Item 1 re-routed through `extractEntitiesBatch`. | 12 314 (one per chunk) |

### SPLADE root cause — detailed record

The PRESPARSE batch=1 behaviour was not a regression; it was a *documented compatibility branch* for sparse-output ONNX exports, commented as *"typically batch-1, so each item is run separately."* Our `naver-splade-v3` export does batch cleanly — the 2026-04-19 ONNX inspection confirmed `output_idx` and `output_weights` are both `['batch', 256]` on the FP16 GPU model and the CPU FP32 model, and `session.run` returns correctly-shaped outputs at batch=3/5. The per-doc loop was defensive for a class of exports we don't use.

Item 2's fix: rewrite `runSparseOutputInference` to batch natively. The implementation was hard-constrained by tempdoc 386 (SpladeEncoder pinned-output race) to stay **heap-only per batched call** — fresh `OnnxTensor.createTensor(...)`, no `pinnedOutputTensor` / `pinnedOutputBuffer` field access. The batched-heap path is accidentally safe from 386's race because it never touches the pinned state. Pinned-output reuse for PRESPARSE would reintroduce the race and requires a 386-equivalent concurrency-contract extension before it can land.

### Why SPLADE landed at batch=4 (not batch=16)

After the initial item 2 batched-PRESPARSE implementation, SPLADE OOMed on the `ps_rmax` ReduceMax node at batch=16 with seqLen ≥ 300. Each call requested up to 6.94 GB contiguous arena memory — exceeding the 4 GB cap. Raising the SPLADE arena to 6 GB did not help: `kSameAsRequested` fragmentation reduces usable contiguous memory to ~37 % of nominal. Every OOM triggered a CPU fallback that pushed SPLADE p50 to ~3.2 s/call, so net pipeline went 3.3× *slower* than baseline.

`MAX_SPLADE_BATCH_SIZE_GPU` was dropped to **4**, where the worst-case request is ~1.75 GB and fits comfortably. This captures ~4× the per-doc throughput of batch=1 (Batch E probe) — item 2's measured payoff in Run A — but leaves the theoretical batch=16 ceiling on the table.

Two paths remain for reclaiming batch > 4 on SPLADE. **Path A (arena strategy change)** was tested in Runs B + C and ruled out: the global `kNextPowerOfTwo` switch regresses SPLADE p50 by 65 %, and the batch=16 OOM is an *absolute* memory-request problem (5+ GB vs 4 GB arena), not fragmentation that a different allocator strategy can fix. **Path B (adaptive sub-batching inside `runSparseOutputInference`)** — try batch=16, on OOM split and retry — is the only viable route; ~1 day of work with careful exception-boundary design. **Path C (extending 386's pinned-output contract to the PRESPARSE batched path)** remains blocked by the concurrency-contract redesign it would require.

### Adjacent areas — audited 2026-04-20

- **Fast-path + defensive-fallback pattern** (the "fallback became mainline" shape that caused items 1 and 2): **not** fleet-wide. Only SPLADE and NER had it; all other encoders (`OnnxEmbeddingEncoder`, `CrossEncoderReranker`, `CitationScorer`, `BgeM3Encoder`) are clean on this axis.
- **Thread-entry audit**: `SpladeEncoder`'s 386 fix holds. One latent finding on `OnnxEmbeddingEncoder.java:87` — `volatile int embeddingDimension` is detected via non-atomic read-then-write on first invocation; two concurrent first-call `encode()` could race. Idempotent write → low practical impact, violates post-386 "no unsynchronised shared mutation" contract. Fix-when-editing, not blocking.
- **WritePathOps RMW race** (tempdoc 393 § 1.4): 24 call sites across 9 threads. `SpladeBackfillOps` participates. Fix lives in `WritePathOps`, owned by 393, orthogonal to everything in this tempdoc.
- **Production SPLADE call sites**: `CombinedEnrichmentBackfillOps.java:311` (primary, 100 docs/cycle) + `SpladeBackfillOps.java:125` (secondary). Both converge on `runSparseOutputInference`. One fix (item 2) covered both.

### Where the overhead lived — Phase 3 summary

Pre-394 per-call `ort` was 3–10× the raw CUDA kernel from Batch E's probe. Phase 3 (table above) attributed the penalty: shrinkage dominant (E6' −26 %/−60 %/−54 % across embed/SPLADE/NER), semaphore wait modest (E6d ~13 % on embed), other candidates (`kSameAsRequested` fragmentation, CUDA stream sync, first-call JIT) minor or untestable from Java. After E6': NER hit the raw CUDA floor (~1.3× residual); embed and SPLADE still carry ~3–4× residual that requires IoBinding / CUDA Graphs — not exposed in ORT's Java binding.

## Projected payoff — per-item breakdown

Revised with E6' measurement. **Headline now ~3.6× at realistic ceiling** (stacking E6' + items 1 + 2 + 3), well above the original 2.5× target. E6' alone delivers 1.82× for a one-line toggle — the single biggest lever of the whole tempdoc.

| # | Item | Prereqs | Projected saving | Risk |
|---|---|---|---|---|
| 1 | **Wire NER backfill to `inferBatch(16)`** via `NerService.extractEntitiesBatch` (already implemented, just unused) | None. Infrastructure exists. | **~30 s (~12 % pipeline)** — revised down from 40 s because NER's ort penalty is only ~3× vs ~8× for others | Low. NER CPU-bound via `BertNerInference`; no GPU arena interaction. |
| 2 | **Implement batched `runSparseOutputInference` for PRESPARSE format** (root cause identified — see section above) | ONNX output-shape inspection confirming sparse outputs have a batch dimension. | **~35–45 s at batch=4** (revised 2026-04-20 after PRESPARSE-ReduceMax-OOM discovery; reclaiming the ~80 s theoretical ceiling requires a follow-up — see item 2 follow-up section) | Low-to-medium at batch=4. Higher batch sizes blocked by BFCArena fragmentation under `kSameAsRequested`. |
| 3 | **Raise embed `MAX_ORT_BATCH_SIZE` past 8** | Blocked on BFCArena fragmentation at default 3 GB embed arena under `kSameAsRequested`. Attempted 2026-04-20 with `= 16`; caused 51 MatMul/BiasSoftmax OOMs → pipeline regression. **Reverted to `= 8`.** Run C confirmed `kNextPowerOfTwo` resolves the embed fragmentation but globally regresses SPLADE. | **Likely unblocked as a side effect of item 4's 6 GB embed arena** — re-measure after item 4 lands before concluding additional change is needed. | Low-medium once item 4 is in place. |
| 4 | **Disable shrinkage + raise embed arena cap to 6 GB** — ship E6' unconditionally. Measured 1.82× pipeline speedup (E6', 2026-04-20). Shrinkage was the dominant penalty source (E6d ruled out semaphore; E6' confirmed shrinkage). | None — one-line config change plus env-var default. | **~114 s saved** (253 s → 139 s) in isolation; **~85 s saved stacking on Run A** (190.6 s → ~105 s). | Low. VRAM peak rises to 10.5 GB on a 12 GB card with no concurrent LLM. Coexistence with `llama-server` is out of scope for this tempdoc (→ tempdoc 395 A7). |

Items are independent in principle. Item 4 is the highest-leverage remaining lever and the next action. Item 3's follow-up is conditional on item 4's outcome. Item 2's batch-ceiling reclaim (path B) is the lowest-priority remaining work.

### Revised stacked payoff projection (post-E6')

Pipeline-wall estimates with combinations applied. E6' is measured; other numbers scaled proportionally from the E6' baseline.

| Config | Estimated wall | Speedup vs current baseline |
|---|---|---|
| Current baseline (shrinkage on, all encoders active) | 253 s | 1.0× |
| E6' alone (measured pre-items-1+2, global shrinkage-off) | 139 s | 1.82× |
| Items 1 + 2 at batch=4 — Run A (measured) | 190.6 s | 1.33× |
| Items 1+2 + `kNextPowerOfTwo` only — Run B (reverted, regression) | 215.2 s | 1.18× |
| Items 1+2 + `kNextPowerOfTwo` + batch=16 — Run C (aborted) | ~160 s (CPU-fallback thrashing) | — |
| Items 1+2 + global shrinkage-off — Run D (aborted) | ~60 s into run (27 SPLADE OOMs on `ps_rmax`) | — |
| Items 1 + 2 + item 4 (embed-only shrinkage-off + 6 GB arena) — Run D''' (measured, since **reverted**) | 182.2 s | 1.39× |
| Items 1+2 + item 4 + embed batch=16 — Run E (aborted) | 33 OOMs by 195 s, CPU thrashing | — |

**Landed cumulative speedup (post item 4 revert): 1.33× (253 s → 190.6 s).** Item 4's incremental win (1.046× over Run A) was real but too marginal versus the ~5 GB extra held VRAM. See the per-call decomposition below for why.

### Item 4 — implementation record and post-mortem (reverted 2026-04-20)

**What was implemented and measured** (all code subsequently reverted):

1. `OrtSessionFactory.createGpuRunOptions(boolean disableArenaShrinkage)` — signature extended with a flag. When `true`, omits the `memory.enable_memory_arena_shrinkage=gpu:0` entry.
2. `OrtSessionManager.Builder.disableArenaShrinkage(boolean)` — builder setter propagating the flag to the session-manager instance; used when building `runOptions`. Init log line reported `arenaShrinkage=enabled/disabled`.
3. `OnnxEmbeddingEncoder.buildSessionManager` — set `.disableArenaShrinkage(true)` (for the provider/test path).
4. `KnowledgeServer` embed factory customizer — added `.disableArenaShrinkage(true)` (for the production ingest path routing through `ModelSessionFactory.create`). **This was the bug that cost Run D'' — the initial wire-up only set the flag via `buildSessionManager`, so the production path ran with shrinkage enabled. Run D''' measured the intended speedup only after the second wiring landed.** See "Per-session flag plumbing gotcha" below.
5. `OnnxEmbeddingEncoder.DEFAULT_GPU_MEM_MB` 3072 → 6144. `ResolvedConfigBuilder.buildEmbedding` default + `EnvRegistry.EMBED_GPU_MEM_MB` doc updated to match.

**Run D''' validation** (the working wiring): pipeline wall 182.2 s; 0 OOMs; embed init log confirmed `arenaShrinkage=disabled`, `memLimit=6144MB`; VRAM peak ~10 GB.

**Run F validation** (embed alone + item 4, SPLADE/NER disabled): embed ortP50 = 37.09 ms, essentially identical to Run D'''s 37.19 ms. Confirmed that under items 1+2, there is **zero cross-encoder contention** on embed — item 4 is at its natural per-call ceiling for batch=8.

### Why item 4's stacked win was only 1.046× — per-call decomposition

Four measured embed ortP50 data points, same model and batch=8:

| Configuration | embed ortP50 |
|---|---|
| E6d — embed alone, shrinkage on, pre-items-1+2 | 45.9 ms |
| Run A — all encoders, shrinkage on (items 1+2 landed) | 45.4 ms |
| Run D''' — all encoders, item 4 on (embed-only shrinkage off) | 37.2 ms |
| Run F — embed alone, item 4 on | 37.1 ms |

Derived effects:
- **Items 1+2 net effect on embed p50**: −0.5 ms (basically nil). Items 1+2 did NOT save embed time through cross-encoder contention reduction — Run A ≈ E6d.
- **Item 4 net effect on embed p50**: −8.2 ms per call. 945 embed calls × 8.2 ms = **~7.8 s pipeline saving**, matching measured 8.4 s wall delta.
- **Cross-encoder contention under item 4**: 37.2 vs 37.1 = 0.1 ms. Nothing left to extract — item 4 already at ceiling.

Per-call cost breakdown of the 37 ms embed ORT phase in Run D''' (~46 ms total including CPU tokenize/extract):

| Component | ms/call | Source |
|---|---|---|
| Raw CUDA kernel (batch=8) | ~9 | Batch E probe (tempdoc 390) |
| Semaphore wait | ~7 | E6d |
| Shrinkage overhead | 0 | item 4 removed it |
| Residual Java/ORT/CUDA driver | ~21 | not reachable from Java without IoBinding |
| **ORT phase subtotal** | **~37** | matches measured p50 |
| Tokenize (Java CPU) | 7.1 | per-call log |
| Extract (Java CPU) | 2.2 | per-call log |
| Tensor build | 0.05 | per-call log |
| **Full per-call total** | **~46** | matches `total/call` average |

**Why the E6'-projected 1.82× didn't stack onto Run A's 1.33×**: E6' measured shrinkage-off applied globally from the pre-items-1+2 baseline. That 114 s pool broke down as:

| Encoder | E6' pre-items-1+2 saving | Post items 1+2 available |
|---|---|---|
| NER | 21 s (12,314 calls × 1.7 ms) | **0.01 s** — item 1 collapsed NER to 7 calls; the per-call saving remained but the call-count went to zero. |
| SPLADE | 56 s (5,318 calls × 10.6 ms) | **blocked** — item 2's batch=4 fragments the 4 GB SPLADE arena under shrinkage-off (Run D: 27+ `ps_rmax` OOMs with SPLADE 4 GB; Run D' even with 6 GB still OOMed). Can't apply without OOMing. |
| embed | 13 s (952 × 13.5 ms) | **7.7 s** (945 × 8.2 ms — slight drop from the E6' per-call because the baseline is already warm). |
| Cascade (orchestration) | 24 s | ~0 s — items 1+2 already captured via call-count collapse. |
| **Total** | **114 s** | **~8 s** |

**Items 1+2 and item 4 attack the same cost — per-call overhead — via different mechanisms (fewer calls vs cheaper calls).** Items 1+2 already exhausted most of the opportunity by reducing NER+SPLADE call counts 99.94 % / 75 %. Item 4 can only work on embed (the one encoder whose call count items 1+2 didn't touch), and even there the per-call saving is a first-principles lower bound: ~8 ms × 945 calls ≈ 7.6 s.

### Why item 4 was reverted

1. **8 s of pipeline wall is a poor trade for ~5 GB of continuously-held VRAM**, especially since the same VRAM is needed for any concurrent LLM (tempdoc 395 A7) or future model swap.
2. **The saving mechanism is at its natural ceiling.** Run F proved there's no hidden contention eating item 4's potential — the 8 ms/call is literally what cudaFree+cudaMalloc costs per embed inference. No further tuning can widen this channel.
3. **Remaining leverage exists elsewhere** (path B SPLADE adaptive sub-batching ≈ 15 s projected; IoBinding / CUDA Graphs would eliminate the 21 ms residual but requires a JNI shim). These don't compete for VRAM.
4. **If VRAM becomes abundant** (24 GB+ card, or dedicated inference box), item 4 can be reintroduced cleanly — the plumbing design (per-session Builder flag) is recorded above and was validated to work. The 395 A7 consideration documents the coexistence design space.

### Per-session flag plumbing gotcha (discovered during item 4 wiring)

A cautionary note for future work on encoder sessions: `OnnxEmbeddingEncoder` is constructed via two different code paths depending on caller, and **both must be updated** when applying a session-wide policy change.

- **Provider / test path**: `OnnxEmbeddingEncoder(Path modelDir, int maxSeqLen, BooleanSupplier shouldUseGpu, boolean gpuEnabled, int gpuDeviceId, long gpuMemLimitBytes)` internally calls the private static `buildSessionManager(...)` which constructs an `OrtSessionManager` inline. This is what `OnnxEmbeddingProvider` (service-loader-discovered) and most tests use.
- **Production ingest path**: `KnowledgeServer` constructs an `OrtSessionManager` via `ModelSessionFactory.create("embed", ...)` and passes it to the **other** constructor `OnnxEmbeddingEncoder(OrtSessionManager, Path, int)`. This is what the live backend actually uses.

Item 4's first attempt (Run D'') only wired the flag on `buildSessionManager`. The backend ran with shrinkage still on — silently regressed. `jseval` showed `arenaShrinkage=enabled` in the init log; only after grepping worker.log did the bug surface. Run D''' after adding the same `.disableArenaShrinkage(true)` to the `KnowledgeServer` customizer produced the expected effect.

This is a code smell: per-encoder session policy lives in two places that must stay in sync. The encoder class's `buildSessionManager` and the central `ModelSessionFactory` (with caller-supplied customizers) should probably converge. Recorded as a cross-cutting concern — the "theorized correct design" section's P3 (single InferenceScheduler owning all sessions) is one way to eliminate the duality.

## What still needs measuring

- [x] **Overhead localization** (was item 0). Done. ORT phase dominates 84-97 %; tokenization is not the bottleneck.
- [x] **SPLADE batch=1 root cause.** Confirmed 2026-04-19: PRESPARSE output format short-circuits batching at `SpladeEncoder.java:493-495`. See dedicated section above.
- [x] **PRESPARSE output shape.** Confirmed 2026-04-19: `output_idx` and `output_weights` are `['batch', 256]` on both CPU and FP16 models. Option A is viable.
- [x] **Primary-indexing SPLADE call site.** Confirmed 2026-04-19: `CombinedEnrichmentBackfillOps.java:311`. Both backfill paths converge on `runSparseOutputInference`; one fix covers both.
- [x] **Sequential execution of encoders** (E1). Confirmed 2026-04-20: all 407 per-call logs on `indexing-loop`.
- [x] **Arena shrinkage status in production.** Confirmed 2026-04-20: enabled on every GPU session. Hard-coded in `OrtSessionFactory.createGpuRunOptions()`.
- [x] **Shrinkage is load-bearing** (E6, aborted). Confirmed 2026-04-20: disabling shrinkage at current arena caps → embed OOMs repeatedly → pipeline 2.15× slower.
- [x] **E6d — semaphore-contribution to `ort` phase.** Done 2026-04-20. Semaphore wait contributes only ~13 % to embed per-call `ort`; ruled out as dominant penalty.
- [x] **E6' — shrinkage cost in isolation.** Done 2026-04-20. Shrinkage is the dominant penalty: disabling it at 6 GB embed cap yields 1.82× pipeline speedup, per-call `ort` drops 26–60 % across encoders, zero OOMs. See dedicated section above.
- [x] **E5 — arena strategy A/B (`kNextPowerOfTwo` vs `kSameAsRequested`).** Runs B + C 2026-04-20. Globally applied `kNextPowerOfTwo` is a net regression: +24.6 s pipeline at current caps (SPLADE ortP50 +65 %), and does not unblock SPLADE batch=16 (which is an absolute 5+ GB request, not fragmentation). Reverted. Narrower follow-up worth recording: embed-only `kNextPowerOfTwo` via per-session provider options would unlock item 3 at ~8 s saving, not pursued now because item 4 is a larger lever.
- [ ] **Embed `ortMax = 19.7 s` outlier.** One pathological call ate ~25 % of embed's wall. Is it recurring (a stall we should fix) or cold-start (noise)? Compare a second run.
- [ ] **Seq-len distribution** on real scifact inputs. Still open. Needed for sizing padding-tax on items 2 and 3.
- [ ] **Error isolation** for batched calls. Retry-with-singleton-fallback pattern for items 1 and 2.

## What's explicitly NOT in scope for this tempdoc

- Concrete Java code, class names, interface design, build changes.
- Specific batch-size or timeout choices.
- Whether to implement per encoder or via a shared abstraction.
- Query-path (non-ingest) implications — scope is ingest only.

## Related

- **Tempdoc 390** — Batch E probe data (`docs/tempdocs/390-results/2026-04-20-batch-E-sweep/*.json`). Raw CUDA floor used for ratio comparisons.
- **Tempdoc 391** — scifact 3-run baseline (253 s wall, ±1.2 % run-to-run variance).
- **Tempdoc 311** — ORT session architecture, GPU arena sizing. **Strongly related to item 4.** Our `arena_extend_strategy=kSameAsRequested` + per-session arenas is the configuration ORT's own docs + github issue #14474 + discussion #21577 flag as bad for concurrent CUDA sessions. Item 4 may be a cheaper path to part of 311's goal than the full partitioning rewrite 311 contemplates.
- **Tempdoc 334** — Phase 8 work that set `MAX_ORT_BATCH_SIZE = 8`.

## Decision marker (revised 2026-04-20 after item 4 reverted)

**Landed**: items 1 + 2 at batch=4 → 1.33× (190.6 s vs 252.9 s), commit `9c64042db`.

**Runs B + C**: `arena_extend_strategy=kNextPowerOfTwo` ruled out — +24.6 s pipeline at current caps (SPLADE ortP50 +65 %), does not unblock SPLADE batch=16 because `ps_rmax` exceeds the 4 GB cap absolutely.

**Runs D / D' / D''**: global shrinkage-off fails post-items-1+2 (SPLADE batch=4 fragments the 4 GB arena regardless of cap raise).

**Run D'''**: embed-only shrinkage-off + 6 GB embed arena via per-session Builder flag — measured 182.2 s / +1.046× over Run A. **Reverted 2026-04-20**: the 8 s saving is a poor trade for ~5 GB continuously-held VRAM. Per-call decomposition (above) shows the saving is at its natural ceiling (no further leverage via this mechanism). Plumbing and design preserved in this tempdoc for future reference; 395 A7 owns the adaptation-design space if/when coexistence trade-offs change.

**Run E**: embed batch=16 under item 4's arena — progressive fragmentation (0 → 33 OOMs over 195 s). Item 3 stays reverted.

**Run F**: embed alone + item 4 — embed ortP50 37.1 ms, essentially identical to Run D''' 37.2 ms. Confirmed no cross-encoder contention remains in the items-1+2 state.

### What NOT to do

- **Don't reintroduce item 4 as a default config without reconsidering VRAM trade.** The 5 GB cost was the deciding factor; changes to the VRAM envelope (larger card, no concurrent LLM) would reopen that decision.
- **Don't assume future refactors get item 4 "for free".** Any change that moves embed to a different per-session policy needs to repeat both wiring paths (see "Per-session flag plumbing gotcha" above).
- **Don't revisit `kNextPowerOfTwo` as a global setting.** Runs B+C are decisive.
- **Don't raise embed `MAX_ORT_BATCH_SIZE` past 8.** Run E showed the 6 GB arena only delays thrashing; it doesn't eliminate fragmentation at batch=16.
- **Don't re-expand shrinkage-off to SPLADE/NER/reranker.** Runs D / D' proved this OOMs under items 1+2's batch configuration.

### Ordered recommendation

1. ~~Commit items 1+2 (Run A state)~~ — done (commit `9c64042db`, 2026-04-20).
2. ~~Attempt item 4~~ — implemented, measured (Run D''' 1.046× for ~5 GB VRAM), reverted as not worth the trade.
3. **Path B adaptive sub-batching for SPLADE** — the largest remaining lever, ~15 s projected at batch=4 → 8. ~1 day of work; requires careful OOM retry-boundary design. Next candidate action.
4. **Per-session `kNextPowerOfTwo` for embed** — ~8 s marginal win; not prioritized.
5. **IoBinding / CUDA Graphs / JNI shim** — eliminates the 21 ms/call residual (could save ~15 s on embed). Large engineering surface; out of scope for this tempdoc. See P3 in "Theorized correct design" below.

Landed speedup: **1.33× (190.6 s)**. The remaining measured ceiling with path B SPLADE alone is ~175 s (1.44×). Everything beyond ~165 s requires architectural work.

---

## Theorized correct design (full version in tempdoc 397)

The original `394` included ~140 lines describing an 8-principle
architecture (P1 batched-only API, P2 stateless encoders, P3 single
inference scheduler, P4 thread-safety via types, P5 model-format
transparency, P6 write path, P7 single pipeline primitive, P8 startup
invariants) with a shape diagram, prior-art calibration against
Elasticsearch/Solr/Flink/Triton, and a symptom-to-principle mapping.

That content has been **moved to tempdoc 397**
(`docs/tempdocs/397-session-policy-centralization.md`), which promoted
it to a design doc with the additional "closure property" thesis,
`RuntimePolicy` / `ModelSessionPolicy` / `CallPolicy` stratification,
and the explicit root diagnosis that session configuration has no
type-system representation in the current code.

### Why the theorised-design relevance narrowed

As the Phase 3 experiments landed, the theorised design's value
proposition shifted:

- **P3 (single `InferenceScheduler`)** — originally framed as a
  VRAM-saving mechanism. Shrinkage-on already captures the VRAM
  saving (steady-state 4.8 GB of a 9.7 GB cap sum), so P3's residual
  value for latency is ~13 % per-call (E6d) — real but not
  transformative. P3 becomes relevant if tempdoc 395 A7 decides
  LLM-coexistence needs a shared allocator; otherwise it's a
  latency-only win not worth the JNI cost.
- **P5 (model-format transparency)** — would have prevented the
  PRESPARSE batch=1 compatibility branch that caused item 2's
  effective-batch-1 bug. Relevant future-proofing on model swap;
  no immediate action.
- **Session-policy duality (spawned 397)** — the item 4 wiring
  gotcha made this concrete and actionable. 397 owns it.

The incremental delivery path ran ahead of the full design:
items 1+2 landed (1.33×), item 4 attempted+reverted, readiness-skip
fix landed, 397 opened. Future work should cross-reference 397 for
structural claims and 395 for adaptation-layer claims; 394 from
here on is a closed record of the encoder-batching investigation
that motivated both.
