---
title: "Inherited-constants stabilization batch: (1) measure the worker heap before trusting its 512m default, (2) pin the external llama-server build and surface its version in the runtime manifest, (3) collapse three literal-duplication constant clusters onto single authorities. Three small, independent, measurement-first items batched because they share one root: load-bearing operational constants whose provenance is invisible at the use site. Scoped from a read-only constants-provenance sweep (2026-07-06); companion defect/observation lines are in the observations inbox (session shard 0bdd87e8, commits 2875cd2 / 2ef7396)."
type: tempdocs
status: "open — scoped, not started (no design ambiguity; each item carries its acceptance test)"
created: 2026-07-06
author: agent analysis session 2026-07-06 (read-only constants-provenance sweep over modules/*/src/main + configuration defaults; ~560 operational constants classified; this batch is the act-now subset)
category: stabilization / operational-constants / provenance
related:
  - 640-performance-ratchet   # the perf ratchet measures the surfaces these constants govern; item 1's measurement should reuse its harness conventions where possible
---

> **Scope note.** This batches the three smallest-footprint, highest-certainty actions from a
> constants-provenance sweep of `main` (2026-07-06). The sweep's broader finding — most
> operational constants carry no visible provenance, with a clear temporal gradient (recent
> constants cite measurements; founding-era constants don't) — does NOT warrant a retroactive
> annotation campaign and none is proposed. These three items qualify because each is either
> measurably load-bearing (1), a structural one-time flip (2), or a live drift hazard (3).

# 682 — Inherited-constants stabilization batch

## Item 1 — Measure the worker heap; then resize or annotate `DEFAULT_WORKER_HEAP`

**Current state.** `KnowledgeServerConfig.java:43` sets `DEFAULT_WORKER_HEAP = "512m"`
(env-overridable via `JUSTSEARCH_WORKER_HEAP`); `WorkerSpawner` now also passes `-Xms=-Xmx`,
so the full amount is resident from boot. No measurement, tempdoc, or comment derives the
value, and the worker's heap-resident work has grown far past what the value was originally
sized for (Tika extraction, NER decoding, chunking, SPLADE term maps, gRPC assembly — the
ONNX/Lucene-mmap footprint is off-heap and not the concern).

**Work.** One instrumented indexing run over a heavy mixed corpus (large PDFs + office docs;
an existing eval corpus is fine) recording heap watermarks (`-Xlog:gc` or the existing RRD
metric store), plus one search-under-indexing pass. Then exactly one of:
- the measurement shows real headroom pressure → raise (or make adaptive) the default, citing
  the run; or
- the measurement shows 512m comfortably correct → keep the value and write the derivation
  next to it (the `DEFAULT_HEALTH_CHECK_RETRY_BUDGET_MS` comment style: defect/measurement/margin).

**Acceptance.** The constant's site cites a dated measurement either way. A 4× error in
either direction is no longer possible to ship silently: OOM-under-merge (too low) and ~2 GB
stolen from co-resident inference (too high) both become claims the citation answers.

## Item 2 — Pin the external llama-server build; surface its version in the runtime manifest

**Current state.** The Brain process is an external `llama-server.exe` staged by the Gradle
cuda-variant task; nothing pins its build, no startup check asserts the version, and the
runtime manifest does not carry it. Version drift therefore surfaces as behavioral bugs
(flag semantics, `/props` shape, sampling defaults) with no declared expectation to diff
against. The process boundary already contains the blast radius (HTTP/JSON, not ABI) — this
item is about making drift *visible and intentional*, not about hard-failing.

**Work.**
- Pin the staged llama-server to an explicit build/version in the staging path (the download
  already targets a release; make the version an asserted input, not an incidental one).
- Record the expected + actually-running version (from `GET /props` at adoption/startup) in
  the runtime manifest alongside the existing reason codes.
- `InferenceLifecycleManager` logs loudly (and the manifest reflects) on mismatch;
  fail-closed is NOT proposed — adoption of an externally-started server is a supported flow.

**Acceptance.** A deliberate version swap of the staged binary produces a visible
expected-vs-actual mismatch in the runtime manifest and log; the normal path records the
pinned version. One-time structural flip; ~zero standing cost.

## Item 3 — Collapse three literal-duplication constant clusters onto single authorities

**Current state (all verified on `main`, also logged to the observations inbox):**
1. The ~11.5 GB VRAM threshold literal exists three times: `VramRequirements.java:30`,
   `VramDetector.java:36`, `VramFlagsUtil.java:87` — the last carries a comment warning the
   copies are parallel.
2. A GPU-saturation window trio is duplicated across processes: `OperationalMetrics.java:433-436`
   (worker) vs `GpuSaturationMonitor.java:23-29` (head).
3. An unexplained `9000` is hand-coupled FE/BE: `FrameHistoryRingBuffer.java:30` (SSE replay
   capacity) vs `bootIntentStreamBridge.ts:44` (FE dedup LRU) — divergence on one side only
   yields replay gaps or duplicate-event storms after reconnect.

**Work.** One authority per cluster: a shared constants holder for (1) and (2) (same-language,
cross-module — ordinary shared class placement, no new mechanism), and for (3) the repo's
existing codegen-from-authority pattern (`LivenessWindows` → `liveness-constants.ts` is the
in-house template) or, minimally, a checked comment-pair the wire gate already knows how to
verify. Do not redesign the values themselves — this item changes *authority count*, not
behavior; value changes, if any, belong to their own measured work.

**Acceptance.** Each literal exists exactly once per language boundary; the FE/BE pair either
codegen'd or drift-checked. Grep for the literals finds one authority + references.

## Explicitly out of scope

- Retroactive provenance annotation of the remaining provenance-less constants (ceremony;
  the observed norm — new/touched constants citing their derivation — is already trending
  correctly without enforcement).
- Any behavior/value tuning beyond what Item 1's measurement itself justifies.
- The fusion-fallback drift defect (`HybridSearchOps` 10/0.3 vs builder defaults 3/0.25) —
  already an observations-inbox item; it is a one-line bugfix a triage pass should take
  (whoever fixes it must decide which value is the *intended* default before aligning).

## Verification map

Item 1: the measurement run itself + the citation landing at the constant site.
Item 2: manifest field present in a live run; mismatch drill logged.
Item 3: compile + affected module tests; grep-count assertion per literal; FE/BE pair covered
by codegen check or drift check. Standard pre-merge: `./gradlew.bat build -x test` + affected
module tests; no new gates proposed.
