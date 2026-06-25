---
title: "Engine latency optimization — the cross-encoder cost — PURPOSE-ONLY STUB. The 'optimize' band (the ACTION half) of the performance budget that tempdoc 640 left unbuilt. 640 measured + guarded; 647 attributes + allocates; THIS actually REDUCES the dominant cost the attribution names — the cross-encoder, ~82% of query latency (640 §C-2). Candidate levers (none chosen): the CE candidate pool-size / top-k dial (the 636 leg-arbitration + recall-complete levers feed the pool but were never latency-profiled), batching, early-exit / cascade, quantization, caching. Evidence-led by 647's attribution; any win is protected from regressing by 640's ratchet. No design chosen, nothing implemented — records the gap + why it matters."
type: tempdocs
status: "PURPOSE-ONLY STUB (2026-06-25) — idea + purpose only. No design, no implementation. Spun out of tempdoc 640's scope correction as the 'optimize' (action-half) band; should be led by 647's attribution before any lever is chosen."
created: 2026-06-25
updated: 2026-06-25
author: agent analysis — spun out of tempdoc 640 (scope correction, 2026-06-25). Filed as a purpose-only stub per the 640 convention; no design chosen.
related:
  - 640-engine-performance-budget-latency-throughput-footprint   # the ratchet that protects any optimization win from silently regressing
  - 647-engine-performance-attribution-and-budget-allocation     # MUST lead this — optimize what the attribution says dominates (interrogate-results), not by guesswork
  - 636-retrieval-buried-signal-long-documents                   # the default-on levers feed the CE candidate pool; the pool-size↔latency dial 640 flagged but never measured is the first optimization target
---

> **PURPOSE-ONLY STUB (2026-06-25).** Captures the **"optimize" band — the action half** — of the engine performance
> budget that tempdoc 640 set out to build but **left unbuilt** (640 delivered *measure → guard*; 647 covers
> *attribute → budget*). **No design is chosen and nothing is implemented here** — this file records *what the gap is*
> and *why it matters*. **It must not be picked up before 647**: optimize what the attribution says dominates, not by
> guesswork (interrogate-results).

## The gap

640 can now *hold the line* on engine latency, but it never *moves the line down*. The single highest-leverage target
is already known: the **cross-encoder reranker is ~82% of query latency** (640 §C-2). Reducing it is the "improve it"
half of the budget that no recent tempdoc owns.

## Why it matters

- **Highest leverage by far.** At ~82% of query latency, a modest CE-stage reduction dwarfs any gain elsewhere.
- **Now safe to attempt.** Before 640 a latency optimization could silently regress quality or another perf axis;
  640's ratchet (relevance + perf + leak + llm-gen) is the guard that catches a bad trade, so optimization can be
  attempted *aggressively* with a safety net.
- **A known un-profiled dial exists.** The 636 default-on levers (leg-arbitration + recall-complete pool) enlarge the
  CE candidate pool; 640 flagged but never measured the **pool-size ↔ CE-latency** relationship. That dial is the
  obvious first experiment.

## Candidate levers (NONE chosen — for the future agent to evaluate against 647's attribution)

- **CE candidate pool-size / top-k dial** — fewer candidates → less CE work; trade against recall (leak-gate guards it).
- **Batching / sequence-length** — already tuned for VRAM (inference-runtime F-005/F-007); revisit for latency.
- **Early-exit / cascade** — cheap first-pass prune before the full CE.
- **Quantization / a smaller CE** — quality trade, relevance-gate guards it.
- **Caching** — repeated query/candidate pairs.

## First step when picked up

Run **647** first (standing per-stage attribution). Then run the single cleanest experiment — the **pool-size ↔
CE-latency** sweep — measuring *all four* ratchets each run (relevance / perf / leak / llm-gen) so a latency win that
costs recall or relevance is caught immediately. Pick the lever the data endorses; do not pre-commit a design here.

## Explicitly out of scope

- **Attribution + budgeting** (knowing *where* the cost is) → **tempdoc 647** (its prerequisite).
- **Indexing-throughput or footprint optimization** — separate targets; this stub is scoped to the *dominant query-
  latency* cost. Spin a sibling stub if/when the attribution elevates one of those.
