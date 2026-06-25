---
title: "Engine performance attribution + per-stage budget allocation — PURPOSE-ONLY STUB. The 'attribute → budget' band of the performance budget that tempdoc 640 left unbuilt. 640 delivered measure → guard (a standing regression ratchet over aggregate CE-stage latency / indexing throughput / resident footprint); it did NOT turn those numbers into a real BUDGET. This stub captures that gap: (1) attribution as a STANDING instrument — decompose where each ms of query latency / MB of footprint goes, PER pipeline stage, continuously (640 §C-2 found the cross-encoder is ~82% of query latency as a one-off analysis, not a standing tool); (2) per-stage ALLOWANCES + targets you commit to and check actuals against (the literal 'budget' — e.g. 'rerank ≤ X ms of the query budget'). No design chosen, nothing implemented — records what the gap is and why it matters."
type: tempdocs
status: "PURPOSE-ONLY STUB (2026-06-25) — idea + purpose only. No design, no implementation. Spun out of tempdoc 640's scope correction as the 'attribute → budget' band of the performance budget."
created: 2026-06-25
updated: 2026-06-25
author: agent analysis — spun out of tempdoc 640 (scope correction, 2026-06-25). Filed as a purpose-only stub per the 640 convention; no design chosen.
related:
  - 640-engine-performance-budget-latency-throughput-footprint   # delivered the measure→guard band; this is its 'attribute → budget' continuation. The ratchet protects any allocations from regressing.
  - 636-retrieval-buried-signal-long-documents                   # the default-on levers (leg-arbitration + recall-complete pool) feed the CE candidate pool but were never latency-attributed — the first real budget question
  - 278-indexing-throughput                                      # the most recent dedicated throughput work (stale baseline) — the indexing-side budget precedent
---

> **PURPOSE-ONLY STUB (2026-06-25).** Captures the **"attribute → budget" band** of the engine performance budget
> that tempdoc 640 set out to build but **left unbuilt** (640 delivered only the *measure → guard* band — see 640's
> "## Scope correction"). **No design is chosen and nothing is implemented here** — this file records *what the gap
> is* and *why it matters*. The first step when it is picked up is **turning the one-off attribution into a standing
> instrument**, then **allocating per-stage budgets** against it.

## The gap

640 gave the engine numbers (latency / throughput / footprint) and a guard that fails loudly when they regress. But a
performance *budget* is more than a guard:

1. **Attribution is not yet a standing tool.** 640 §C-2 established that the **cross-encoder is ~82% of query
   latency** — but as a *one-off* analysis, not a continuous, per-stage decomposition you can read on every run.
   Without standing attribution you cannot answer "which stage moved?" when the aggregate ratchet fires, nor decide
   *where* optimization has leverage.
2. **There are no per-stage allowances.** The ratchet compares the *aggregate* against its own last-good number. It
   does not express a *budget* — a committed allocation per stage ("retrieval ≤ a ms, rerank ≤ b ms, fusion ≤ c ms"
   of the query-latency budget; an analogous split for footprint across the ONNX encoders + the LLM) — that you check
   actuals against and that makes a trade-off ("rerank got slower but retrieval got faster, net within budget")
   legible.

## Why it matters

- **Prerequisite for principled optimization (interrogate-results).** You optimize what the attribution says
  dominates. 648 (the optimize band) should be *led* by this stub's attribution, not by guesswork.
- **Trade-offs become decisions, not surprises.** A per-stage budget turns "the engine got 12% slower" into "rerank
  overran its allowance by X ms — was that a deliberate quality trade?"
- **Folds in a 640 deferral.** The **configured-stack incl-LLM footprint** (640 B-reconcile — the full ~8 GB the
  product bears, distinct from the ONNX-only resident-during-eval number) is naturally a *footprint allocation* line
  in this budget, not a separate metric.

## First step when picked up

Extend the **stage_timing** the perf-gate already reads (640 made per-stage timing a captured artifact) into a
standing per-stage decomposition + a declared per-stage allowance table; check actuals against it. Reuse 640's
canonical-record + governed-projection seam — the budget allowances project from the same release the ratchet does,
they are not a second hand-typed authority.

## Explicitly out of scope (recorded so they are not silently dropped)

- **Optimization itself** (actually reducing a stage's cost) → **tempdoc 648**. This stub only *attributes + allocates*.
- **VRAM-contention budgeting** (how embedding + reranker + LLM share one GPU) → belongs in the **inference-runtime
  register's Future Work** (the single-tenant GPU policy already exists; FW-001 arena-tuning is already filed) unless
  it grows enough to warrant its own tempdoc.
- **Query throughput under concurrent load (QPS / tail-under-load)** → recognized but **low value for a single-user
  desktop product**; revisit only if a multi-user / server mode appears.
