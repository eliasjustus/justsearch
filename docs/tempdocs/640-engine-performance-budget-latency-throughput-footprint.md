---
title: "Engine performance budget — SETTLED long-term design: make PERF a FIRST-CLASS metric family IN the canonical measurement record (today it is a side-channel: latency_stats/stage_timing/ingest/manifest, bypassing the `aggregate_metrics` map quality flows through). That one structural move makes the regression ratchet, the release-PROJECTION (closing the per-run fork), the calibrate-envelope (noise-robust median floor), the history trend, and the published scorecard all FOLLOW through the path quality already uses — the performance sibling of the relevance ratchet. A standing RELATIVE ratchet over query latency / indexing throughput / resident footprint, conforming to the canonical-record + governed-projection seam (553/559/622/623) and 625's projection-vs-fork principle — NOT a new measurement subsystem, NOT an absolute SLO. REACH (recognized, not built): every engine-quality axis (relevance/perf/leak/LLM-gen) is a first-class family + a governed-projection ratchet; the shared ratchet KERNEL + combined scorecard stay deferred (625-adjacent), not 640's scope."
type: tempdocs
status: "designed + SHIPPED v1 (jseval perf-gate) + LONG-TERM DESIGN SETTLED 2026-06-24. The settled crux: perf is currently a SIDE-CHANNEL (latency_stats/stage_timing/ingest/manifest), not a first-class family in the canonical record's `aggregate_metrics` map — which is why the shipped baseline is a per-run FORK (two corpora pinned from two commits). The scope-matched long-term design is ONE structural move: promote perf into a first-class metric family of the canonical record (per-mode latency / per-run throughput / per-run full footprint incl. LLM); then release-projection (closes the fork), the calibrate-envelope median floor, the history trend, and the published scorecard all follow through the path quality already uses. Out of 640's scope (REACH): the shared ratchet kernel + combined family scorecard + 625 generalized enforcement — recognized, NOT built (625-adjacent; the fork has now bitten a 2nd time = 625's trigger). Conforms to the canonical-record + governed-projection seam (623) + 625; extends, does not replace. General design only — NO further implementation this pass."
created: 2026-06-24
updated: 2026-06-24
author: agent analysis — originated as a STUB (idea + purpose only), filed from the tempdoc 636 coverage-gap analysis; investigated + premise-corrected + measured 2026-06-24 (see §Investigation). No design chosen, no implementation — per assignment.
related:
  - 636-retrieval-buried-signal-long-documents   # added per-query work (leg-arbitration + recall-complete pool) that was never latency-profiled; flagged but never measured the CE pool-size↔latency dial
  - 630-os-sleep-resume-robustness               # the energy-aware throttle touched resource pacing — the nearest recent contact with this axis, but not a holistic performance budget
  - 607-vdu-ocr-extraction-logic-analysis        # OCR / VDU enrichment adds extraction cost not budgeted against indexing throughput
  - 278-indexing-throughput                       # the most recent dedicated throughput work — predates the current stack (stale baseline)
---

> **RESCOPED (2026-06-25) — read this first.** This tempdoc set out as a performance *budget* but **delivered the
> measure → guard band**: a standing **regression ratchet** (CE-stage latency / indexing throughput / resident
> footprint) + the LLM-gen axis (TTFT / e2e / tokens-sec) + the shared ratchet kernel, the engine-quality scorecard,
> and the release-projection that closed the per-run fork — all SHIPPED + merged. The *larger half* of a budget —
> **attribution as a standing tool, per-stage allocation, and optimization (actually reducing cost)** — is **out of
> 640's delivered scope and spun out**: **647** (performance attribution + per-stage budget allocation) → **648**
> (engine latency optimization, the cross-encoder cost). The full lifecycle map is in "## Scope correction" at the
> foot. Everything below this banner predates it (dated history).

> **Purpose-only STUB (2026-06-24).** Captures the engine's performance dimension — latency, throughput,
> footprint — which a coverage analysis of recent work (tempdoc 636) confirmed has no recent owner.
> **No design is chosen and nothing is implemented here** — this file records *what the gap is* and *why it
> matters*. The first step when it is picked up is **establishing measurement** (a baseline against the
> current stack), not an optimization. Everything about *what* to profile or optimize is out of scope.

# 640 — Engine performance budget: latency, throughput, footprint

## The idea

Recent engine work has optimized two axes: **ranking quality** (nDCG — the tempdoc 636 fusion/rerank levers)
and **reliability/correctness** (the 626–630 freshness/durability/resume program). The **performance** axis —
how *fast*, how *cheap*, and how *small* the engine is — has no recent owner:

- **Query latency** — end-to-end time from query to results, across the multi-stage path (legs → fusion →
  chunk collapse → cross-encoder → optional RAG).
- **Indexing / embedding throughput** — time-to-searchable after a folder is added, including the cost of
  extraction (now with OCR/VDU enrichment, tempdoc 607), embedding, sparse encoding, and HNSW build.
- **Steady-state resource footprint** — RAM, VRAM, and disk consumed at rest and per query by the index, the
  HNSW graph, and the several models (embed / SPLADE / reranker / LLM) competing for the user's machine.

The dedicated performance tempdocs on record (e.g. 278 indexing-throughput, and the older startup-performance
work) **predate** the current model stack, the chunk-branch fusion, the cross-encoder, OCR extraction, and
the 636 levers — so even the baseline is stale.

## Why it matters

- **It is a desktop product sharing the user's machine.** Unlike a server engine, JustSearch competes for
  CPU/GPU/RAM with everything else the user is doing. Latency and footprint are not secondary metrics here;
  they are co-equal with relevance and reliability as product quality.
- **Ranking gains can be quality-negative once cost is counted.** A lever that wins nDCG but adds latency or
  VRAM is "better" only in a quality-only frame. The 636 levers (leg-arbitration + the recall-complete
  rerank pool, both now default-on) each add per-query work; 636 itself named the cross-encoder
  pool-size↔latency dial but never measured it. There is currently **no standing measurement that would
  catch a latency or footprint regression** introduced by a ranking change.
- **Time-to-searchable is the first thing a new user experiences.** Indexing/embedding throughput governs how
  long after pointing JustSearch at a folder before it is usable — and extraction enrichment (OCR/VDU) adds
  cost that has not been budgeted against throughput.
- **It is a regime-blind capability.** Latency, throughput, and footprint are properties of the engine that
  hold for any workload — consistent with the engine-wide direction recorded at the close of 636 (improve
  fixed, regime-blind capability). Measuring and bounding them requires no assumption about who the user is.

## Scope boundary (purpose only — design deferred)

Out of scope for this stub, to be decided in a future design phase:
- *What* to profile, *what* budgets to set, or *what* benchmark/harness to use.
- *Any* specific optimization (caching, quantization, batching, model swaps, parallelism, etc.).
- *Whether* a given trade is worth making — that judgment follows the measurement, not this stub.
- The reliability-pacing mechanisms already shipped (e.g. 630's energy-aware throttle) beyond noting they are
  the nearest recent contact with this axis, not a holistic performance budget.

This file records only the **purpose and why it matters**. The first concrete artifact, when the work is
prioritized, is a **performance baseline against the current stack** (latency / throughput / footprint) so
that regressions and cost/quality trades become visible — the performance analog of the relevance ratchet and
the 626–630 standing reliability guards.

---

# Investigation (2026-06-24)

> Take-over investigation per assignment: verify every load-bearing premise against `main`, capture a real
> baseline from existing tooling, scan the 2024–2026 external landscape, and think critically — question
> assumptions and name alternatives. **No design chosen and no implementation** — this records the verified
> (and partly corrected) premise and the sharpened decision space only. Where it conflicts with the STUB
> above, this section wins — most importantly the STUB's "no standing measurement exists," which is **wrong**.
> Internal claims marked ✓ were re-verified directly this pass; others come from a read-only code audit and
> are attributed as such. Measured numbers are my own reads of a same-day jseval run (cited inline).

## A. Verdict in one paragraph

The *motivation* is sound — the performance axis genuinely has no standing **guard**, the dedicated perf
tempdocs are months old (278/312/286/302 are all 2026‑03…04 ✓, predating the June 636 levers and OCR 607), and
there is real cost to budget. **But the STUB's framing is materially out of date with the codebase: measurement
is not missing — it is rich and current.** jseval already computes and persists per-stage query latency
(retrieval / chunk-merge / cross-encoder / branch-fusion, p50/p95/p99), total-latency stats, indexing
throughput (primary *and* enrichment-complete docs/s), per-encoder ORT latency profiles, and the engine
detects VRAM and enforces a hard per-session `gpu_mem_limit` cap; a ratio-based regression primitive
(`diff_gate.compare_ratio`, supporting both lower-is-better and higher-is-better) even exists. A normal
same-day run measured all of it (§C). So the residual gap is **narrower and sharper than "no measurement"**:
(1) **nothing pins a perf baseline that fails the build on regression** — the FE bundle-size budget is the only
blocking budget gate, the relevance ratchet is advisory and nDCG-only, and `diff_gate` sits unwired (no
latency/throughput baseline file exists ✓); (2) the two default-on 636 levers ✓ add real cost that is **not
attributed per-lever**; (3) footprint is **detected and capped but not budgeted** (no proactive admission, no
RAM/disk budget, no resident-vs-peak distinction, no idle-unload policy); (4) under 636's "no users → don't
privilege a workload" rule, **an absolute SLO target is itself a workload guess** — so the rule-compatible
artifact is a *relative regression ratchet*, not a target. Net: 640 is a real gap, but it is a
**standing-guard + cost-attribution + footprint-budget** problem on an already-well-instrumented engine, not a
"build measurement" problem — and the cheapest credible step reuses parts that already exist.

## B. Claims that hold up (verified)

1. **No standing backend perf gate exists.** ✓ The governance registry (`governance/registry.v1.json`) has
   zero latency/throughput/perf/footprint gate ids; the only blocking budget gate is FE `ui-bundle`
   (`scripts/ci/ui-bundle-budget.v1.json`); the relevance ratchet
   (`scripts/jseval/relevance-ratchet-baselines.v1.json`) is advisory (nightly `continue-on-error`) and
   nDCG-only. No `*-latency/throughput-baseline.json` exists ✓.
2. **The dedicated perf tempdocs are stale relative to the current stack.** ✓ 278/312 (indexing throughput)
   and 286/302 (startup) are 2026‑03‑12…04‑09 — before OCR (607, June) and the 636 levers (June). The
   throughput *reference* doc (`docs/reference/performance/indexing-throughput.md`) is maintained, but there is
   **no latency or footprint reference doc** at all.
3. **Both 636 levers ship default-on.** ✓ `index.hybrid.leg_arbitration_enabled=true`
   (`ResolvedConfigBuilder.java:1497`), `index.hybrid.leg_recall_complete_enabled=true` (`:1513`) — so their
   cost is paid on every production query today.
4. **It's a desktop product carrying a heavy resident footprint.** ✓ `models/` is **30 GB** on disk; the active
   runtime model weights total ≈ **7.5 GB** — LLM Qwen3.5‑9B Q4_K_M **5.6 GB** (~75%) + embed/SPLADE/reranker/
   NER fp16 ≈ 1.9 GB (+ 876 MB mmproj if VDU). The LLM dominates footprint, exactly as the external desktop
   norms predict (§E).

Per-stage timing instrumentation is real (audit, corroborated by my direct read of the run's `summary.json`
stage-timing block): `SearchTrace` carries a nullable per-stage `ms` (`SearchTrace.java:138`), populated from
`System.nanoTime()` in `SearchExecutor` (worker) and `SearchTraceMapper`/`KnowledgeSearchEngine` (head,
`crossEncoderMs` from the reranker's elapsed). The CE is hard-deadline-bounded (`RerankerConfig`
`topK`≈20 / `deadlineBudgetMs`≈200, audit-reported) and its elapsed is on the trace.

## C. The measured baseline — existing tooling, no new harness (enron-qa, hybrid, 5,459 docs, 2026-06-24)

Read directly from `scripts/jseval/tmp/eval-results/20260624T081044_mixed_enron-qa/summary.json` — i.e. the
"baseline against the current stack" the STUB calls for *is already produced by a normal eval run*; it is
simply not pinned or guarded.

**Query latency (hybrid):** mean **172.5 ms**, p50 **176**, p95 **196**, p99 **210**, max **843** (cold first
query), min 25. Per-stage p50/p95:

| Stage | p50 ms | p95 ms | share of p50 |
|---|---|---|---|
| retrieval (BM25 + dense + SPLADE) | 10 | 20 | ~6% |
| chunk-merge | 6 | 8 | ~3% |
| **cross-encoder rerank** | **145** | **154** | **~82%** |
| branch-fusion | 0 | 0 | ~0% |

**The cross-encoder is ~82% of query latency; first-stage retrieval is ~6%.** This independently reproduces the
external landscape's "cost is back-loaded into the CE" finding (§E) on JustSearch's own stack and hardware.

**Indexing throughput:** primary indexing **97.3 docs/s** (56 s for 5,459 docs) — but **enrichment-complete
8.2 docs/s** (total 668 s ≈ **11 min**), a ~12× gap dominated by embedding (346 s; SPLADE 106 s; NER 124 s;
embedding reaches 100% coverage only at 516 s). Per-batch ORT latency: embed p50 42.9 ms / p95 161.7 ms;
SPLADE ≈ 75/78 ms; NER ≈ 3/4 ms (from the run's `status_snapshot.worker.enrichment.encoderProfiles`).

**Footprint:** ≈ 7.5 GB resident model weights (LLM-dominated), 30 GB on disk (incl. backups + a second LLM).

## C-findings — where the STUB is wrong or must be sharpened

### C-1 — "No standing measurement exists" is FALSE; reframe to "no standing budget/gate" *(most important)*
The STUB's load-bearing line ("There is currently no standing measurement that would catch a latency or
footprint regression") is wrong on *measurement* and right only on *guard*. jseval persists `latency_stats` +
`stage_timing_stats` + `ingest.pipeline_summary` + per-encoder ORT profiles every run; the wire `SearchTrace`
carries per-stage `ms`; VRAM is detected (`gpu-bridge`) and capped (`ort-common` `gpu_mem_limit`). What is
absent is the **gate**: no baseline is pinned, nothing fails on regression, and the ready-made `diff_gate`
ratio primitive is unwired. This is the `explore-before-implementing` correction — a design phase must start
from "**wire a guard over metrics that already exist**," not "build measurement." Re-proposing the existing
instrumentation as greenfield would be the exact failure mode 636's own investigation caught.

### C-2 — The latency budget *is* the cross-encoder pool — and the 636 recall-complete lever (default-on) widens exactly that
Measured: the CE is 145 ms p50 ≈ **82%** of query latency. The recall-complete pool (636, **default-on**)
explicitly *widens the candidate set entering the CE window* (`HybridFusionUtils.spliceRecallComplete` + a
`SearchExecutor` branch-fusion splice, audit), and CE cost is ~**linear in candidate count** (external §E:
≈3× for 60 vs 20 candidates). So the STUB's flag ("636 levers add latency never profiled") is **not
hypothetical** — the lever feeds the single dominant cost, yet its added candidates are folded into the
aggregate `crossEncoderMs` with **no per-lever attribution**. This is the sharpest, most concrete instance in
the whole tempdoc and the natural first measurement (a flag-on/off A/B on the same index — the method 636
already established — would isolate it).

### C-3 — "Time-to-searchable" is two numbers, not one (primary 97/s vs enrichment-complete 8/s)
A document is **keyword-searchable** within the primary-indexing pass (97 docs/s) but only **dense/semantically
searchable** once embedding completes (here, 100% coverage at 516 s — a ~12× slower enrichment-complete rate of
8.2 docs/s). The STUB's "time-to-searchable" conflates these. Any budget must distinguish *basic-searchable*
from *semantically-searchable*; **embedding throughput is the lever for the latter** (consistent with 580
naming embeddings low-priority and the embedder's FP16 history). This also names a real UX risk the budget
should bound: results can look "ready" before semantic coverage lands — the same coverage-gating hazard 636/637
documented (`BLOCKED_LEGACY` / chunk-coverage).

### C-4 — Footprint is detected + capped but not budgeted; the "good desktop citizen" question is unasked
VRAM is detected (`gpu-bridge` `VramDetector`) and bounded by a hard per-session `gpu_mem_limit` with CPU
fallback on BFC-arena exhaustion (audit) — but this is **reactive, not a budget**: there is no proactive
admission (nothing refuses to load a model when free VRAM < model size), no RAM or disk budget, no
resident-vs-peak distinction, and — an open question — **no idle-unload policy** (the Ollama `keep_alive`
analog; 630's energy throttle pauses *work* but does not unload *models*). Since the LLM is ~75% of footprint,
the desktop-citizen lever is **LLM residency**, which is currently unaddressed.

### C-5 — A naive single-run threshold gate would drown in noise (max 843 ms cold vs 176 ms p50)
The measured max (843 ms, the cold first query) is ~5× the p50 — perf data here is noisy and cold-start-
contaminated. The external record (§E) is blunt: single-run threshold alerts both miss real regressions and
false-positive on jitter; the proven approach is a **deterministic harness** (fixed corpus/index/threads) +
**change-point detection** (E-divisive / Hunter-style) + a **sticky baseline**. So "pin p95 < X" naively would
flap. This is a *constraint the design phase must reckon with*, named here — not solved here.

### C-6 — Under the "no users" rule, an absolute SLO is itself a workload guess — prefer a *relative* ratchet
636's takeover set two standing rules (no users yet → don't speculate about corpus/queries; improve fixed,
regime-blind capability). Latency/throughput/footprint **are** regime-blind properties — a good fit. But an
*absolute* target ("p95 must be < 200 ms") silently encodes a workload + UX assumption the rule forbids
privileging. The rule-compatible form is a **relative regression ratchet** (this build vs the last, across a
*cohort* of corpora) — guard against *getting worse*, do not assert what "good" is. This mirrors how the
relevance ratchet (Q-010) is relative, and is the **framing question worth putting to the user** before any
design.

## D. The decision space (named, NOT chosen — for a future design phase)

| Gap | Cheapest credible response (not chosen) | Reuses what exists | Rule-fit | Note |
|---|---|---|---|---|
| No latency regression guard | wire `diff_gate` over the `summary.json` `latency_stats`/`stage_timing_stats` across the corpus cohort | `diff_gate.compare_ratio`, jseval `summary.json`, the `search-engine-hint` hook | high (relative) | must handle cold-start noise (C-5) |
| No per-lever (636) cost attribution | flag-on/off shared-index A/B measuring CE candidate count + `crossEncoderMs` | the 636 shared-index A/B method, `SearchTrace` | high | measurement, not a fix |
| Throughput ambiguity (C-3) | report + guard **primary** vs **enrichment-complete** separately | `ingest.pipeline_summary` | high | embedding is the enrichment lever |
| Footprint not budgeted (C-4) | decide resident-vs-peak + an idle-unload (LLM-residency) policy | `gpu-bridge`, `gpu_mem_limit`, the 630 energy seam | medium | needs a desktop-citizen decision |
| Absolute SLO targets | **do not** (workload guess) — prefer the relative ratchet | — | forbidden by the rule | record, don't build |

## E. External landscape (2024–2026) — what a perf-budget effort must know

- **Cost is back-loaded into the cross-encoder; first-stage retrieval is cheap.** CE cost is ~linear in
  candidate count (one ≤512-token forward pass per pair); ~100–150 ms for ~30 candidates on CPU, >300 ms past
  ~100. Interactive anchors: 100 ms feels instant, 1 s acceptable; for RAG, ~300 ms TTFT (p99) is the
  perception threshold. → the budget levers are **rerank pool size** and (with the LLM on) **TTFT**, not
  first-stage retrieval. Matches the measured §C (CE ≈82%).
  [ZeroEntropy reranking 2025](https://zeroentropy.dev/articles/ultimate-guide-to-choosing-the-best-reranking-model-in-2025/) ·
  [Spheron LLM SLO guide 2026](https://www.spheron.network/blog/llm-inference-slo-ttft-itl-latency-budget-guide-2026/)
- **HNSW memory & quantization (the footprint lever 580 parked as "efficiency only").** int7/int8 scalar
  quant ≈ **4× memory** at near-zero recall cost and is production-safe in Lucene 10; BBQ/2-bit ≈ **95%**
  reduction but is a *quantize-then-rerank* architecture whose rescoring pass is its own latency line. Recall
  cost must be measured on the actual `gte-multilingual-base` corpus.
  [Lucene scalar quantization (Elastic)](https://www.elastic.co/search-labs/blog/scalar-quantization-in-lucene) ·
  [Elastic BBQ](https://www.elastic.co/search-labs/blog/better-binary-quantization-lucene-elasticsearch)
- **The ONNX-CUDA reranker is the highest-risk measurement.** A naively exported XLM-RoBERTa reranker ran
  **5.7×** slower than PyTorch on GPU (FlagEmbedding #987 / ORT #23282) — an export/optimization problem
  (unfused attention, dynamic axes), not an intrinsic limit; fused+fp16 ONNX or int8-CPU may beat naive CUDA.
  Must be benchmarked head-to-head before any throughput assumption. (Note: register F-005 already records the
  5.7× slowdown — corroborated.)
  [FlagEmbedding #987](https://github.com/FlagOpen/FlagEmbedding/issues/987) ·
  [ORT transformers optimizer](https://onnxruntime.ai/docs/performance/transformers-optimization.html)
- **Desktop-citizen norms.** A Q4_K_M 8B LLM is ~5–6 GB weights + a context-linear KV cache (matches §C's
  5.6 GB); the LLM dominates footprint. Idle-unload is the standard politeness lever — Ollama defaults to
  `keep_alive=5m`. Distinguish **resident** vs **peak** footprint and treat idle-unload + KV-vs-context growth
  as explicit budget knobs.
  [Ollama FAQ (keep_alive)](https://docs.ollama.com/faq) ·
  [llama.cpp VRAM guide](https://localllm.in/blog/llamacpp-vram-requirements-for-local-llms)
- **Regression detection that works.** Lucene's own nightly history shows single benchmarks + threshold alerts
  miss real regressions; the reliable recipe is a **deterministic harness** + **change-point detection**
  (E-divisive / Hunter; MongoDB's perf CI) + a **ratchet** with sticky baselines. Directly informs C-5.
  [luceneutil](https://github.com/mikemccand/luceneutil) ·
  [Hunter (arXiv 2301.03034)](https://arxiv.org/pdf/2301.03034)

## F. Questions the design phase must answer first (not answered here)

1. **Relative ratchet vs absolute target?** (C-6) — the framing decision; rule-compatibility says relative.
   Worth a user call before anything is built.
2. **Which metrics get pinned, over which corpus cohort,** to stay workload-blind? (latency total + CE stage;
   primary vs enrichment-complete throughput; resident vs peak footprint.)
3. **How is cold-start / noise handled** so the guard doesn't flap? (deterministic harness? warmup discard?
   change-point detection? — C-5.)
4. **Does the footprint budget include an idle-unload / LLM-residency policy,** or only a cap? (C-4 —
   a desktop-citizen decision, not a measurement.)
5. **Is per-lever (636) cost attribution the first measurement,** or a separate track? (C-2.)
6. **Quantization (int7/int8 — the ~4× memory lever 580 parked):** measured against the footprint budget, but
   adopting it is an *optimization* (design/implementation), explicitly out of scope for this investigation.

## Honest limit

Every number in §C is one corpus on one machine (this GPU dev box). They are illustrative of the *shape* —
CE-dominated latency, embedding-dominated enrichment, LLM-dominated footprint — not a portable target. Under
the no-users rule the durable artifact is a **relative guard + the corpus cohort**, and the first build step
(when prioritized) is **wiring a guard over metrics that already exist** — not building measurement, and not
setting an absolute SLO. No design or implementation is done in this pass, per the assignment.

---

# Design (theorized 2026-06-24)

> Long-term design theorization per assignment. **General, not implementation-level.** Goal: name the
> structure the *actual* problem requires — no more, no less — reusing what exists. Grounded on the
> §Investigation facts + a primary-source read of the existing measurement/ratchet substrate
> (`scripts/jseval/jseval/{calibrate,diff_gate,relevance_gate}.py`) + the canonical-record seam tempdoc 623
> designed. Still **no implementation**; this settles the shape, the reuse, and the reach.

## Thesis (one paragraph)

The engine does not need a new performance-measurement subsystem, and it must not grow an absolute
performance SLO (a workload guess the no-users rule forbids — §C-6). What the problem actually requires is a
**standing *relative* regression ratchet for a performance metric-family**, built as the **sibling of the
relevance ratchet** the engine already has — i.e. an instance of the **canonical-record + governed-projection
seam** (tempdoc 623; 553/559/622), not a parallel performance pipeline. The decisive finding of the design
pass is that the substrate is **~95% already present and was, in part, built *for exactly this shape***:
`calibrate.py` already computes a within-machine envelope and **already carries a `CALIBRATED_LATENCY_METRICS`
tuple** beside the quality one, deliberately calibrating only the **low-noise latency subset (mean/p50)** and
**excluding p95/p99/max because their per-run CV was measured ≥ 64%** (`calibrate.py:25-30,51-59`) — i.e. the
noise problem §C-5 raised is *already characterized and handled*; `diff_gate.compare_ratio` already does
**lower-is-better (latency) and higher-is-better (throughput)** ratio bands (`diff_gate.py:12-34`); and
`relevance_gate.project_release_to_baselines` already **projects a release's *measured* metrics into a ratchet
baseline and already knows the metric family is pluggable** (it skips a "non-nDCG metric family …
extraction-style release", `relevance_gate.py:27-63`). So the correct long-term design is to **turn on the
performance metric-family that this substrate was shaped to accept** — project the release's calibrated
latency/throughput/footprint metrics into a perf-baseline sibling of `relevance-ratchet-baselines`, check a run
against it via `diff_gate` *within the envelope band*, and fire it from the **same advisory
`search-engine-hint` regeneration trigger** that already drives the relevance ratchet. The change is the size
of one sibling, because that is the one place the present problem is actually stuck.

## D-1 — Reuse, do not replace: what already exists and is the right home

Verified present and sound on `main` — the design **extends**, it does not rebuild:

- **The canonical measurement record + projection seam** (tempdoc 623; partly realized): a run/release is a
  metric-family-parametric record; `relevance_gate.project_release_to_baselines` already turns a release's
  `measured.metrics` into a pinned baseline. This is the *authority* a perf baseline must project from — not
  fork.
- **The within-machine envelope** (`calibrate.py`): the ±2σ reproducibility band, and — critically — it
  **already enumerates the perf metrics worth calibrating** (`CALIBRATED_LATENCY_METRICS`, latency mean/p50),
  with the high-CV tail deliberately excluded. This *is* the noise discipline §C-5 demanded, already tuned.
- **The ratio-comparison primitive** (`diff_gate.compare_ratio` / `diff_files`): already typed for
  lower-is-better and higher-is-better metrics with per-metric bands. This is the relative-regression check.
- **The metrics themselves** (a normal eval run's `summary.json`): `latency_stats` + per-stage
  `stage_timing_stats` (incl. the CE stage that §C-2 shows dominates) + `ingest.pipeline_summary`
  (`primary_indexing.docs_per_s` *and* enrichment-complete `docs_per_sec`) — all already produced (§C).
- **The advisory trigger** (`search-engine-hint` hook): already nudges a retrieval-engine edit to re-run the
  eval + relevance ratchet; the natural place to also nudge the perf ratchet.
- **The relevance ratchet itself** (`relevance_gate.py`, 580 Track A+B): the *sibling* to mirror — same
  baseline-file shape, same project-from-release discipline, same enforcement tier.

None of these is the defect. The defect is that **the performance family is measured and calibrated but never
projected into a standing baseline anyone checks.**

## D-2 — The structural gap (the one thing the problem actually requires)

There is a `CALIBRATED_LATENCY_METRICS` tuple but **no perf ratchet** — `relevance_gate.py` projects and
checks only `nDCG@10`. The comparison primitive (`diff_gate`) and the calibrated perf metrics both exist but
are **not connected into a standing guard**; no perf-baseline file exists (✓ §Investigation); and the
`search-engine-hint` trigger nudges only the quality ratchet. So a latency/throughput/footprint regression
ships into an **ungated surface** — the §C-1 finding, stated as a missing seam: *the projection from the
canonical record to a pinned perf-baseline, and the check of a run against it, do not exist.* That single
missing connection is the degree of freedom the problem needs.

## D-3 — The design (general statement)

Introduce a **performance metric-family ratchet** as a sibling of the relevance ratchet, at the seam that
already exists:

> `project_release_to_baselines(release, family="perf")` → a `perf-ratchet-baselines` projection; a run is
> **regressed** iff a calibrated perf metric falls outside its `diff_gate` band **and** outside the
> `calibrate` envelope for that metric.

- **Relative, not absolute.** The baseline is *the last green release's own measured value*, not a target
  number — so the gate answers "did this build get worse than the last one?" (workload-blind, rule-compatible,
  §C-6), never "is this fast enough?" (a workload guess).
- **Projected, not forked.** The perf-baseline is a governed view of the canonical record (623), regenerated
  from the same release the relevance baseline is — so it cannot drift into a hand-typed fork (the exact defect
  623 exists to remove).
- **Envelope-gated for noise.** A metric only ratchets if it is in the calibrated set (`calibrate` already
  picks the low-CV subset and discards p95/p99/max); the regression test is "outside the ±2σ band," not a raw
  threshold — so the guard does not flap on cold-start/thermal jitter (the max-843 ms vs p50-176 ms problem).
- **Same enforcement tier as its sibling.** Fired by the `search-engine-hint` advisory hook + a manual
  `jseval` gate, under ADR-0026 manual-CI / local-first discipline — *not* a new CI path. (The honest limit
  that this tier is advisory, shared with the relevance ratchet, is §Reach, not this design's to fix.)
- **Legible.** The per-stage decomposition is already on `SearchTrace`; the ratchet records *which* metric of
  *which* family regressed, so a CE-stage latency regression (the §C-2 dominant cost, which the default-on 636
  recall-complete lever feeds) is attributable, not a blob.

## D-4 — The metric families behind the seam (what the present problem includes — and what it does not)

| Family | Metric(s) to pin | Noise | Already calibrated? | In v1? |
|---|---|---|---|---|
| **Latency** | total p50 + **cross-encoder-stage p50** (the §C-2 dominant cost) | low at p50; high at p95/p99 | **Yes** (`CALIBRATED_LATENCY_METRICS`, mean/p50; tail excluded) | **Yes** |
| **Throughput** | `primary_indexing.docs_per_s` **and** enrichment-complete `docs_per_sec` (two numbers, §C-3) | medium | metrics emitted, **not yet in the calibrated set** | **Yes** (small extension) |
| **Resident footprint** | configured-stack resident model bytes (≈7.5 GB, §C/§B-4) | ~zero (deterministic per config) | emitted in `status_snapshot`, **not as a release metric** | **Yes** (cheapest — deterministic) |

The structure the problem *adds* beyond what the quality ratchet needed is therefore small and precise:
**(a)** extend the calibrated metric set to the throughput family the way latency already is; **(b)** surface
resident footprint as a release metric (it is deterministic, so it barely needs an envelope — a footprint
regression is "a config change enlarged the resident stack"); **(c)** the perf-baseline projection + the
`search-engine-hint` nudge (D-3). Nothing else.

## D-5 — Explicitly out of scope (do not build for cases the problem does not yet have)

- **No absolute SLO / latency target.** A workload guess the no-users rule forbids (§C-6). Record-only.
- **No new measurement subsystem, no new harness.** It all exists (§Investigation, §D-1).
- **No change-point detection / deterministic-harness rebuild.** The external record (§E) names these as the
  *mature* form, but the present problem is "no guard at all"; the existing within-machine envelope + the
  deliberate high-CV exclusion is the noise mechanism v1 needs. Change-point detection is the richer future
  form — named (§E), not built.
- **No idle-unload / LLM-residency POLICY.** The ratchet bounds the *configured* resident footprint; whether
  models unload when idle (the Ollama `keep_alive` analog, §C-4/§E) is a separate **desktop-citizen behavioral
  decision**, not a measurement — a distinct problem, named, not folded in.
- **No cross-hardware reproduction band.** 623 (F-α) scoped the envelope to within-machine / equivalent-setup;
  the perf ratchet inherits that scope. Cross-hardware perf is a stranger's *own* baseline, not a regression of
  ours.
- **No per-lever (636) cost-attribution framework.** A flag-on/off shared-index A/B (the 636 method) already
  isolates a lever's CE-pool cost on demand (§C-2); a standing per-lever attribution surface is a separate
  measurement track, recorded (§Reach candidate scope), not built.

## D-6 — Verification shape (when it is eventually built — not now)

The ratchet's own correctness is checkable without a live stack: project a synthetic release → assert the
baseline shape; feed a within-envelope run → assert PASS; feed an outside-band run → assert REGRESSED; feed a
high-CV metric (p99) → assert it is *not* gated. The live half (does a real regression trip it?) reuses the
relevance ratchet's own pattern. This is recorded so the build is eval-first, not to be run in this pass.

---

# Reach & principle (recognized, not built)

## The principle

**A continuously-measured quality axis without a standing ratchet is a silent-regression surface.** Stated
positively: *every axis the engine measures deserves a **relative** ratchet **projected from the one canonical
measurement record**; the axis that is measured but un-ratcheted drifts down unnoticed (the boiling-frog).*
This is the **temporal-drift sibling of tempdoc 637's seam** ("a silent stale state must surface as a reasoned
observable at its owning layer") — same family ("no silent degradation"), different axis: 637 guards *infra
staleness* (dead binding, stale jar, cold index), this guards *metric regression over commits*. It is also the
generalization of the Q-010 / 580 §4c **asymmetry argument** (presentation gated on every edit; retrieval — and
now performance — gated only by an opt-in run a human must remember).

## It conforms to an existing seam — do not fork it

This design is **not a new principle and must not be a new mechanism.** It is an instance of the
**canonical-record + governed-projection seam** (553 SearchTrace / 559 sibling-record / 622 telemetry /
**623** the benchmark release), itself an instance of the recurring system shape *"single canonical authority +
governed consumers + fork-prevention gate"* the reliability cluster also embodies (626/627/628). The perf
ratchet must therefore live **as a metric-family sibling of the relevance ratchet** — same release object, same
`project_release_to_baselines` projection, same `calibrate` envelope, same `diff_gate` check, same
`search-engine-hint` trigger — **not** a parallel "performance pipeline." Building a separate perf-measurement
authority would be the exact anti-pattern (a fork of the record), the same one 623 exists to prevent.

## Candidate scope beyond this problem (named, deliberately not built now)

The principle is **already violated in several places**, which is the evidence it is real, not speculative:

- **Relevance axis** — *has* a ratchet, but it is **not CI-wired / advisory-only** (623 §C-6): the guard
  itself silently rots if nobody regenerates it. A *partial* violation — the deeper one (below).
- **Performance axis** (this tempdoc) — measured + even calibrated (`CALIBRATED_LATENCY_METRICS`) but **no
  ratchet**. Full violation; the one the present problem fixes.
- **Throughput + resident footprint** — measured, not in any calibrated set or ratchet. Full violation.
- **Extraction quality** (623's named sibling; register F-009, the largest measured bottleneck) — no
  record/ratchet yet.
- **Agent-utility** (624) — building the record (LLM-judge, cohort identity), no ratchet.

So **multiple measured axes lack a guard** — strong evidence the shape generalizes. The **sharper, deeper
insight**: the relevance ratchet proves the principle is *incompletely honored even where a ratchet exists* —
because it is advisory, the **guard** is itself a silent-regression surface (the boiling-frog applies to the
guard, not just the metric). The honest teeth of the principle: *a ratchet that does not run unprompted is a
ratchet in name only.* Under ADR-0026 (manual CI) + local-first discipline the realistic enforcement tier is
the advisory `search-engine-hint` hook (~85%, the `hook-hint` tier) — so the perf ratchet should **join the
relevance ratchet at that one trigger**, and closing the "advisory ≠ enforced" gap for *both* ratchets at once
is the separate, larger concern below.

## Do not build the generalized structure now (the 625 boundary)

The generalization — *"every asserted measurement is a projection of a reproducible run, and every projection
is regenerated unprompted"* — is **already named and deliberately deferred as tempdoc 625**
(asserted-measurement-provenance, "structure deferred until the fork bites again"), built on the
metric-family-parametric record of 623. Per the AHA / premature-abstraction discipline, **640 builds only the
one sibling the present problem requires** (the performance ratchet), and **records** the principle + its
candidate scope so the next axis (extraction, agent-utility) is built *as* a step toward 625, not against it.
A unified "every-axis ratchet + auto-regeneration" framework is warranted only when a second un-guarded axis is
actually prioritized — at which point this section is the warrant. Recognizing the shape and building the
general structure are kept separate on purpose.

## Research-currency note (2026-06-24)

A deliberate check on whether the design rests on fast-moving external ground. It does **not**, and that is a
property worth recording: the three most actively-researched frontiers this work *touches* —
**vector quantization** (BBQ / RaBitQ / 2-bit, scalar tiers), **LLM-inference optimization** (TTFT/ITL,
continuous batching, speculative decoding), and **cross-encoder / ONNX-CUDA reranker speedups** — are exactly
the ones the design (D-5) **does not depend on**. The ratchet *measures* the axes those frontiers move
(latency / throughput / footprint) without implementing their internals, so it is **insulated by construction**
from that churn: whichever 2026 technique shrinks footprint or cuts CE latency, the guard observes the number
move and gates it identically. The one external dependency the design *does* have — **perf-regression
methodology under noise** (change-point detection, deterministic harness, ratchets) — is mature and was already
scanned live at current-date freshness (§E; incl. 2025–26 sources). So **no further broad research pass is
warranted** for this design.

**Reaffirmed for the SETTLED long-term design (2026-06-24):** the settled crux collapses to a single *internal
data-model move* — promote perf into a first-class family of JustSearch's own canonical record (conform to the
623/625 seam). That has **no external research analog** (there is no global frontier for "how should this
codebase's measurement record carry a metric family"); the relevant external precedents for a *multi-family*
record — MLPerf's "accuracy-as-constraint / perf-as-score" split and MTEB's co-located quality+cost metadata —
were already imported last round (§Theorization C). So the no-fresh-pass conclusion holds, only stronger: the
settled design is *more* internal-architecture than the v1 ratchet was, hence *more* insulated from external churn.

The single genuinely-new 2025–26 intersection worth a *targeted* pass — **performance-regression discipline for
LLM-in-the-loop (RAG) pipelines**, where the stochastic *generation* step dominates and is far noisier than
retrieval (the IR-perf-CI and LLM-inference-SLO worlds are converging) — informs the **deferred LLM-generation
latency sibling axis** (TTFT / tokens-sec), which lives in the inference-runtime subsystem + its own register,
**not** 640's scoped v1 (retrieval-stage latency + indexing throughput + footprint). Per the scope discipline,
it is **recorded here as the research trigger** for when that sibling is prioritized — not pursued now.

---

# As-built (2026-06-24) — `jseval perf-gate` shipped + validated

The performance ratchet was implemented as the **perf-metric-family sibling of the relevance ratchet**,
reusing the existing primitives end-to-end. Validated at all available tiers (no UI surface → no browser
validation applies; this is a dev/CI tool).

## Two corrections to the confidence pass (now resolved)
- **The canonical release object IS built** (the confidence pass said unbuilt — a truncated grep). `jseval
  release` (`cli.py` `@main.command("release")` → `release.compose`) exists and `project_release_to_baselines`
  is wired into `cmd_relevance_gate`. So the seam is real. v1 still **pins inline** (projected from a green run)
  because `release.compose`'s `measured.metrics` carries *quality* metrics only; a `current_release` projection
  for perf opens up once 623 grows the release's perf metric family.
- **CE-stage p50 needs no warmup machinery.** Warmup queries are *not* excluded from stats (`run.py`), but the
  measured CE-stage **p50** CV is 1–10% regardless — a median absorbs the single cold query. Gate on it as-is.

## What was built
- **`scripts/jseval/jseval/perf_gate.py`** — `evaluate(baselines, summary, dataset, manifest=None)` (exit
  0/1/2, mirroring `relevance_gate`), `project_run_to_perf_baselines(...)` (regenerate floors from a green run —
  measured, never hand-typed), and `derive_resident_model_bytes(manifest)` (deterministic retrieval-ONNX
  footprint from `model_fingerprints` + the models-dir layout; **LLM excluded**; SKIPs gracefully if
  unresolvable). Comparison reuses **`diff_gate.compare_ratio`** (lower/higher-is-better ratio bands).
- **`cli.py` `cmd_perf_gate`** — the `perf-gate` subcommand (mirrors `cmd_relevance_gate`; reads the latest run's
  `summary.json` + `manifest.json`).
- **`scripts/jseval/perf-ratchet-baselines.v1.json`** — the baseline file (schema + default bands), with
  `mixed/enron-qa` pinned from a real green run (CE-p50 145 ms, primary 97.3 docs/s, enrich 8.2 docs/s, footprint
  2.02 GB).
- **`scripts/jseval/tests/test_perf_gate.py`** — 10 unit tests (within-band, CE-latency regression, throughput
  drop, band-edge, missing-metric data error, unpinned skip, footprint best-effort skip, footprint-derivation
  None paths, projection round-trip).
- **Wiring (advisory tier):** `search-engine-hint.mjs` now nudges `perf-gate` alongside `relevance-gate`;
  register **Q-012** + `hooks-reference.md` updated; `check-release-baseline-sync.mjs` gained a perf-pointer
  well-formedness check.

## Final gate-able metric set + relative bands (from the §confidence-pass CVs)
`ce_p50_ms` (CE-stage p50, lower-better, max_ratio **1.25**) · `primary_docs_s` (higher-better, min_ratio
**0.65**) · `enrich_docs_s` (higher-better, min_ratio **0.75**) · `resident_bytes` (retrieval ONNX footprint,
lower-better, max_ratio **1.05**, best-effort). **Excluded** (too noisy): total `p50_ms`, `index_size_bytes`.

## Validation (all green)
- **Unit:** `pytest tests/test_perf_gate.py` → 10 passed.
- **Live end-to-end on real run data** (`mixed/enron-qa`, this machine): gate vs the green run → **exit 0** (all
  4 metrics ok); an induced regression (CE-p50 baseline tightened, footprint baseline halved) → **exit 1** with
  `ce_p50_ms` + `resident_bytes` = fail while throughput stayed ok (the guard *bites* — `audit-without-test`
  satisfied); re-run → **exit 0** (idempotent). A live-data probe also caught + fixed a real bug
  (`splade_model_path` is a *dir*, not a file → models-dir resolution made robust to dir-or-file).
- **Repo:** `check-release-baseline-sync.mjs` → OK; full `jseval` pytest suite green.

## Residual / deferred (scope-matched, not gaps)
- **Advisory tier** (nudge, not CI-blocking) — inherited from the relevance ratchet; closing the advisory≠
  enforced gap for *both* ratchets is the separate 625-adjacent concern (§Reach).
- **`current_release` projection** for perf — opens when `release.compose` carries the perf family (623).
- **Footprint is best-effort** (SKIPs if model paths unresolvable) and **excludes the LLM** (the deferred
  inference-runtime sibling axis).
- **No absolute SLO**, **no `calibrate.py` change**, **no backend emission** — as scoped.

## Review fix (2026-06-24) — four issues from a critical review

A post-merge critical review (against the design + the relevance-ratchet pattern) found the gate *logic*
sound but the **intended workflow didn't exercise it** — and my earlier validation had used a *pre-existing*
full `mixed/enron-qa` run, which masked it. Fixes:

1. **(critical) The nudged workflow now actually gates.** The `search-engine-hint` nudge previously suggested a
   *bare* `jseval run … --modes hybrid` on `beir/scifact` — but `--ce` **defaults off** (`cli.py:52` → no
   `cross_encoder_ms`) and throughput needs `--pipeline` (`ingest.py:132`), and only `mixed/enron-qa` was
   pinned (so `beir/scifact` → silent exit-0 skip). The hook now nudges a **full** measurement run
   (`--clean --pipeline --ce --embedding --splade`), and **`scifact` is pinned** under the raw slug the run
   manifest carries (`manifest.py:269`; relevance keeps its canonical `beir/scifact` and doesn't manifest-check).
2. **(moderate) File-level `default_bands` honored.** `evaluate` now reads `baselines["default_bands"]` as the
   per-metric fallback before the module constant (it was inert — mirrors relevance reading `tolerance_default_abs`).
3. **(moderate) `perf-gate --update-baseline`** re-pins a corpus's floor from a green run via
   `project_run_to_perf_baselines` — a documented command (was ad-hoc Python).
4. **(moderate) Dataset-match guard** (`perf_gate.run_dataset_ok`): the gate exits 2 if the run's
   `manifest.dataset` ≠ `--dataset`, preventing a silent cross-corpus comparison (throughput is corpus-size-
   dependent).

**Validation — the full corrected workflow run live end-to-end (no pre-existing-data shortcut this time):**
ran the exact corrected hook command for `scifact` (fresh `--clean --pipeline --ce` run → ce_p50 157, primary
94.9 docs/s, enrich 20.6 docs/s, footprint 2.02 GB), then `--update-baseline` pinned it, the gate → **exit 0**
(4/4 ok), an induced regression → **exit 1** (`ce_p50_ms`), and a cross-corpus run → **exit 2**. Unit tests
**12/12**; full jseval suite green. The baseline now pins `scifact` + `mixed/enron-qa`.

---

# Pre-implementation confidence pass (2026-06-24)

> Read-only de-risking before any implementation — **no feature code, and no dev-stack run was needed**: every
> uncertainty was resolved from existing on-disk evidence (an `envelope.json`, ~60 repeated eval runs across
> commits, + code reads). Findings **re-weight the design's confidence**: the *direction* holds, but several
> "trivial reuse / noise already handled" claims were **corrected**. The design sections above stand but should
> be read through this lens. Numbers are my own reads/compute; code claims cite `file:line`.

## What was checked, and what it returned

| # | Uncertainty | Finding (evidence) | Effect on confidence |
|---|---|---|---|
| **A2** | Does calibrate's latency band tame noise? | **Partly refuted, then recovered.** The one on-disk envelope has `p50_ms` mean 74.7 / **stdev 83.4 → CV ≈ 112%** (`cohort_baselines/620c30d6…/envelope.json`); across today's runs **total p50 is bimodal — warm CV 1–10%, cold-contaminated 35–112%**. BUT the **cross-encoder STAGE p50 is uniformly CV 1–10%** even when total is 101% (cold-start hits warmup/total, not steady CE compute). | **Biggest re-weight.** The gate-able latency metric is the **CE-stage p50 (warm)** — *not* total p50, which is the metric `calibrate` currently calibrates (`CALIBRATED_LATENCY_METRICS`, `calibrate.py:59`). |
| **A1/A7** | Does the canonical record (release object) exist? | **No.** No `jseval release` subcommand / no `release.v1` producer (`cli.py` grep); `relevance_gate.project_release_to_baselines` has no producer. `cmd_relevance_gate` reads a **run dir / `summary.json`** (`cli.py:2434-2447`). | The "conform to a *built* 623 seam" framing was **aspirational**; v1 must project from `summary.json` directly — which is exactly what relevance-gate does today (conformant in spirit). |
| **A3** | Is throughput gate-able? | **Coarsely.** primary `docs_per_s` CV 0–29%; enrichment `docs_per_sec` CV 5–12% within clean config groups (157% in a mixed-config group — confound). | Gate-able with a **wide band** (catches a 2× regression, not subtle drift). |
| **A4** | Is footprint a clean, stable metric? | **Refuted as assumed.** `index_size_bytes` CV **11–62%** (Lucene segment-merge non-determinism); `usedVramBytes` is volatile; **no clean resident-model number is emitted** — only `model_fingerprints` (stable identities, no bytes). | Footprint is the **weakest** family: needs a *derivation* (model_fingerprints → known sizes, deterministic), not a direct read; `index_size` must be excluded. |
| **A6** | Relevance ratchet CI-wired or advisory? | **Advisory only** — no `.github` workflow invokes `relevance-gate`; runs via the `search-engine-hint` hook + manual gate (confirms 623 §C-6 on main). | The perf ratchet inherits the **~85% advisory (`hook-hint`) tier** — the reach-section limitation, confirmed. |
| **A8** | One run → all three families? | **Mostly.** `summary.json` carries latency + throughput + `index_size`; footprint (model residency) needs derivation from the manifest's `model_fingerprints`. | Minor: footprint is a projection, not a co-located read. |
| **A5** | relative vs absolute framing | **User decision** — not investigation-resolvable. | Flagged, unchanged (§F-1). |

## The decisive measurement — cross-run CV (today's runs, grouped by commit; CE-stage vs total)

| Metric | Cross-run CV (range) | Verdict for a v1 ratchet |
|---|---|---|
| **cross-encoder stage p50** (the dominant ~82% cost, §C-2) | **1–10%** (stable even amid cold runs) | **GATE-ABLE — the right latency metric** |
| total `p50_ms` / `mean_ms` | 1–10% warm, **35–112% cold-contaminated** | **EXCLUDE unless warmup-discarded** (and it is the metric calibrate currently picks) |
| primary indexing `docs_per_s` | 0–29% | gate-able, **wide band** only |
| enrichment `docs_per_sec` | 5–12% (clean cohort) | gate-able, wide band |
| `index_size_bytes` | **11–62%** | **EXCLUDE** (segment-merge non-determinism) |
| resident model footprint | deterministic per config | gate-able, but **needs a projection** (not emitted) |

**Interrogated (cause, not just number):** the high-CV cases are cold-start — a run whose first query includes
warmup shows total mean 709 ms / sd 714 ms while its CE-stage p50 stays CV 4%. So the variance is warmup, not
the steady pipeline; the stage metric is the noise-robust one. Also: the **mixed-config group** (enrich CV
157%, index 44%) confirms a perf comparison across **unpinned configs is garbage** — pinning the cohort is
*more* load-bearing for perf than for quality, which **strengthens** the project-from-the-canonical-cohort design.

## What this does to the design (honest re-weight)

- **Direction holds and its hardest question is resolved favorably:** a relative ratchet over the **CE-stage
  latency** (the dominant, stable cost) + coarse throughput + derived footprint is viable; "is perf gate-able
  under noise?" is answered **yes, for the metric that matters.**
- **Three "trivial reuse" claims corrected (down-weight):** (1) the 623 release object is **unbuilt** → project
  from `summary.json` (like relevance-gate); (2) `calibrate` calibrates the **wrong** latency metrics (noisy
  total mean/p50) — v1 needs a **small calibrate extension to the CE-stage metric + a warmup discard**, more
  than "turn on the family"; (3) footprint needs a **derivation**, not a read, and `index_size` must be dropped.
- **Enforcement tier is advisory** (confirmed) — an inherited limit, not this work's to fix (the 625-adjacent
  concern in §Reach).

## Residual (only further work settles)

- The minimal `calibrate` change (CE-stage metric + warmup discard) and the resident-footprint projection from
  `model_fingerprints` — both small but real, not yet built.
- **A5** (relative ratchet vs absolute target) — a **user decision**, gating the framing (§F-1).
- Whether to wait on 623's release object or ship now projecting from `summary.json` — recommend the latter
  (it is what the relevance ratchet already does; the release object is a future consolidation).

## Confidence rating for the remaining work: **6 / 10**

The single highest-impact uncertainty — *is performance gate-able under real noise?* — is **resolved favorably**
for the dominant cost (CE-stage p50, CV 1–10%), and every primitive the design leans on exists, so feasibility
is high and the direction is validated. Held to **6** (not 7–8) because **three reuse assumptions were refuted**
(the calibrated metric is the noisy one; the release authority object is unbuilt; footprint isn't a clean
emitted metric), so v1 carries genuine metric-selection + a small `calibrate` extension + a footprint
projection — plus the inherited **advisory-tier** limit and the open **user framing decision (A5)**. Not a naive
high, not low: the work is feasible and de-risked on its hardest question, but it is **more than the "turn on
the family" the design implied**, and the framing call is the user's to make before build.

---

# Theorization — what we could build on the perf ratchet (2026-06-24)

> Open-ended "what next" pass (research-only, no implementation; the deliverable is this section). Two parallel
> codebase explorations + an external-landscape scan (continuous-benchmarking tooling/UX 2024–2026). Goal: ideas
> to **polish / simplify / extend** the shipped `jseval perf-gate`, or build **new UX** on it. No-users /
> manual-CI / no-rush context assumed throughout. Each idea names what it reuses, its rule-fit (no-users,
> regime-blind, conform-to-seam, AHA/don't-over-DRY), and rough cost. Nothing here is committed to build.

## The framing realization — perf is now one of *three* sibling ratchets

The decisive context: since 640 shipped, the engine has **three advisory ratchets** — **relevance** (nDCG@10),
**performance** (latency/throughput/footprint), and **leak** (recall-survival, tempdoc 636/D-005) — all nudged
by the *same* `search-engine-hint` hook, all "project floors from a green run → compare → exit 0/1/2." A
duplication audit confirms the orchestration is **near-identical** across `relevance_gate.py`, `perf_gate.py`,
`leak_gate.py` + their three `cmd_*` (`cli.py`); they differ only in the **metric readers**, the **comparison
primitive** (abs-tolerance / `diff_gate` ratio band / ceiling), and the **baseline schema**. So 640's own Reach
prediction has come true: *"a unified ratchet framework is warranted only when a second un-guarded axis is
actually prioritized."* **Perf + leak are that second and third axis — the 625 generalization trigger has
fired.** Most of the ideas below ride that realization, and several also resolve the three deviations the
post-merge review flagged (fork / envelope-vs-bands / footprint-excludes-LLM).

## Ideas, by bucket

### A. Simplify — extract a shared *ratchet kernel* (the 625 generalization, now warranted)
- **What:** lift the identical orchestration (load baselines → locate latest run → dataset-match guard → read
  summary/manifest → compare → 0/1/2 → report shape → `--update`/derive) into one `ratchet_kernel`, parameterized
  by a per-family spec (`read_current_value` + `compare` + baseline accessor). The three `cmd_*` stay (their
  option shapes differ — perf has `--mode`, leak is cross-mode), but all call one `evaluate_gate(family, …)`.
  Optionally a single `jseval ratchet --family relevance|perf|leak|all` runner that emits one combined verdict.
- **Why / rule-fit:** AHA-correct — the orchestration genuinely shares a *reason to change*; keep the three
  baseline files + comparators distinct (they have different *data* reasons to change — release vs run vs
  projection origin), so **don't** over-unify the schema. This is the principled "recognize the shape → now
  build the shared structure" step 640/625 deferred, with the trigger now met.
- **Reuses:** `gate._latest_run_dir`, the three existing `evaluate`/`project` fns. **Cost:** medium, behavior-
  preserving refactor.

### B. Extend — make perf a *projection of the canonical release* (closes review deviation #1, "the fork")
- **What:** add `perf_gate.project_release_to_perf_baselines()` (mirror of `relevance_gate.project_release_to_baselines`,
  `relevance_gate.py:27-64`) + a `--current-release` flag on `perf-gate` (mirror `cli.py:2470-2488`). **The
  release schema is *already* metric-family-parametric** (`release.v1.schema.json` `metrics.additionalProperties`
  open; `release.compose` copies `aggregate_metrics` verbatim, `release.py:222-245`) — so the only gap is a
  perf-carrying run populating `aggregate_metrics` with perf keys + the ~50-line projection fn. No schema change.
- **Why / rule-fit:** the shipped baseline currently staples two corpora pinned from *two different commits*
  (`enron@65821feeb` + `scifact@fa17617b`) — structurally the heterogeneous-commit **fork** 623 exists to
  prevent. This makes the perf baseline a *governed projection of the one release*, realizing 640's headline
  design property. Highest value-for-cost idea here.

### C. New UX — the **engine-quality scorecard** (the field-converged surfacing win)
- **What:** a single generated **Markdown/HTML scorecard** (per run/release) combining all three ratchets in a
  **delta-against-base table** — `axis | metric | floor/budget | base | this-run | Δ | status` — direction-aware
  (red/green), with **relevance and cost co-located per row** so a regression reads as a *trade-off* ("relevance
  +2% but CE-latency +30% — worth it?"), not three siloed pass/fails. Frame **relevance as a constraint, perf +
  footprint as the tracked score** (the MLPerf split — avoids a bogus weighted "quality number"). With manual CI
  + no users, *the committed report file is the dashboard* — no service needed.
- **Plus (cheap, auto):** extend `gen-public-benchmark.mjs` + `register-headline-sync.mjs` (which already project
  the release into `docs/reference/benchmarks/methodology.md` + the register "Release Scorecard") with a perf
  section — it *auto-renders the moment perf is in the release* (idea B), ~15 lines.
- **Why / rule-fit:** the external scan found Bencher/CodSpeed/size-limit/MTEB all converge on "a per-PR/per-run
  delta table + a one-click trend," and MTEB specifically **co-locates quality + cost metadata** for a Pareto
  reading. A generated report is a *projection of the canonical record* — conforms to the seam, adds no new
  authority. This is the clearest "new UX feature based on it." **Cost:** low–medium (a report generator).
  Sources: [Bencher thresholds](https://bencher.dev/docs/explanation/thresholds/) ·
  [size-limit delta-comment UX](https://github.com/andresz1/size-limit-action) ·
  [MTEB quality+cost metadata](https://arxiv.org/html/2506.21182v1) ·
  [MLPerf constraint-vs-score](https://www.hpcwire.com/off-the-wire/mlcommons-releases-new-mlperf-inference-v5-1-benchmark-results/)

### D. Extend — a perf **trend report** + **localize-without-bisection**
- **What:** (1) `eval-history.db` already stores per-run nDCG + `mean_latency_ms` + envelope σ (`history.py:47-66`);
  extend its schema *additively* (+`primary_docs_s`/`enrich_docs_s`/`resident_bytes`) and generalize
  `check_trend` (`cli.py:~970`) to any metric → a per-metric **trend/sparkline** ("CE-latency over the last N
  releases"). (2) When any ratchet trips, emit a **per-pipeline-stage delta table** (we already capture
  `stage_timing_stats` — retrieval/chunk-merge/CE/fusion) — the cheap analog of CodSpeed's *differential
  flamegraph*, localizing to "the CE stage regressed," not "something regressed," with zero bisection cost.
  (3) Stamp an **environment fingerprint** on every baseline so env drift (kernel/glibc/GPU/driver) can't
  masquerade as a code regression (the CodSpeed glibc + Nyrkiö kernel-bump findings).
- **Why / rule-fit:** all reuse existing infra (`history.py`, stage timings, `env_fingerprint`); a trend view is
  the "one-click-from-the-spike" the field calls essential; per-stage attribution beats full git-bisection for a
  manual-CI engine. (`cmd_bisect` exists but is best-effort/cached-run-only — not worth auto-wiring yet.)
  Source: [CodSpeed differential profiling](https://codspeed.io/blog/pinpoint-performance-regressions-with-ci-integrated-differential-profiling)

### E. Polish/robustness — two cheap change-point ideas now; layer real CPD later
- **Now (no algorithm, high value):** **median/IQR floor-pinning** when projecting a baseline (so one noisy
  green run can't poison the floor — directly hardens the §C-5 cold-start fragility and the "pinned-from-one-run"
  weakness) + **sticky/remembered regressions** (a tripped ratchet stays flagged in the report until explicitly
  acknowledged — manual runs are intermittent, so a regression mustn't flicker away between them).
- **Later:** layer **change-point detection** (Apache Otava / E-divisive — now ~O(1), a maintained vendorable
  Python lib) as a *second advisory tier* over `eval-history.db`, once ~20–30 green runs accumulate (CPD needs
  history; below that the relative ratchet is the correct cold-start floor). This is the "mature form" §E named,
  now with a concrete library.
- **Rule-fit:** improves the relative ratchet without replacing it; CPD is opt-in/later. Sources:
  [Apache Otava](https://github.com/apache/otava) · [Hunter (E-divisive, t-test) arXiv 2301.03034](https://arxiv.org/pdf/2301.03034) ·
  [MongoDB sticky change-points](https://www.researchgate.net/publication/358869635_Change_Point_Detection_for_MongoDB_Time_Series_Performance_Regression)

### F. Extend coverage — close the footprint-LLM gap + the LLM-gen-latency sibling
- **What:** (1) include the LLM in resident footprint (today `derive_resident_model_bytes` tracks only the
  retrieval ONNX ≈2 GB — ~27%; the LLM is ~75% and currently invisible — review deviation #3). (2) add the
  deferred **LLM-generation latency** axis (TTFT / tokens-sec) as a *fourth* ratchet on the same kernel (idea A)
  — it lives in the inference-runtime subsystem + its own register, and is the genuinely-new IR-perf-CI × LLM-SLO
  frontier the §Research-currency note flagged.
- **Rule-fit:** coverage; the 4th sibling validates the kernel (A). **Cost:** low (footprint) / medium (LLM-gen,
  needs an AI-loaded run).

## Recommended order (leverage × low-cost × rule-fit)

1. **B — perf projects from the release** (closes the fork; ~50 lines; realizes the design's headline property).
2. **C — the engine-quality scorecard report** (the converged UX win; turns 3 siloed gates into a legible,
   trade-off-aware surface; auto-publishes via the existing release projectors).
3. **A — the ratchet kernel** (the 625 trigger has fired; removes real 3× duplication; the principled unification).
4. **E — median floor-pinning + sticky regressions** (cheap robustness; hardens the noise/fork fragility).
5. **D — trend report + per-stage delta attribution** (reuse `history.db` + stage timings + env fingerprint).
6. **F — footprint-includes-LLM + the LLM-gen sibling** (coverage; the LLM-gen axis is the research frontier).

Note these are *complementary*, and **B + E + F together close all three review deviations** (fork / noise-floor /
footprint-LLM), while **A + C** are the structural step up from "three gates" to "one engine-quality scorecard."
None is urgent (no users); all conform to the canonical-record + governed-projection seam; none introduces an
absolute SLO or a per-corpus router. Build remains gated on the user's framing call (§F-1).

---

# Long-term design (SETTLED, 2026-06-24) — perf as a first-class metric family in the canonical record

> Design-settling pass over the §Theorization menu, per the design discipline: find the ONE scope-matched
> structure 640's problem actually requires (not bundle all six ideas), conform to an existing seam rather than
> fork it, and separate the design from its reach. Grounded on the canonical-record substrate (623; release
> schema already family-parametric) + the run-record build site (`run.py:257,418`) + tempdoc 625 (the
> projection-vs-fork owner). **General, not implementation-level.**

## The one structural move the problem requires

640 already *claimed* the right design — "a governed projection of the canonical record, not a fork" — but the
shipped ratchet does not realize it, and the root reason is now precise: **perf metrics are a side-channel.**
Quality (nDCG) flows through the canonical per-mode metric map **`aggregate_metrics`** (`run.py:257,418`), which
`release.compose` carries verbatim (`release.py:222-245`), `calibrate` envelopes, the history DB stores, and the
relevance ratchet projects from. **Perf metrics do not** — they live in *separate* keys (`latency_stats`,
`stage_timing_stats`, `ingest.pipeline_summary`) and the manifest (`model_fingerprints`), bypassing that path.
So the perf floor *cannot* project from the release (its `measured.metrics` carries only `aggregate_metrics`) →
it was pinned per-run → the two-commit fork.

**The correct long-term design is one structural move: promote the gate-able perf metrics into a first-class
metric family of the canonical measurement record**, at their natural granularity (per-mode CE-stage latency;
per-run throughput; per-run *full* resident footprint incl. the LLM). The release schema is *already*
family-parametric (`release.v1.schema.json` `metrics.additionalProperties` open — 623's T-3 intent), so this
**extends the existing record; it does not fork or replace it.**

## What follows for free once perf is a first-class family

The §Theorization's separate ideas B / D / E / F-footprint collapse into *consequences* of that one move, because
they already work off the canonical record for quality:

- **Projection, not fork (idea B).** The perf floor projects from the canonical release via a
  `project_release_to_perf_baselines` mirror of relevance's — closing review deviation #1. The gate logic is
  unchanged (still `diff_gate` ratio bands); only its *input* changes: side-channel read → first-class family read.
- **Noise-robust floor (idea E).** The floor becomes the cohort's **median ± `calibrate` envelope band** — the same
  machinery quality uses — once `calibrate` calibrates the **CE-stage** metric (stable) with warmup-discard, not
  the noisy total p50. Median-over-cohort removes the single-noisy-run fragility by construction.
- **Trend + published visibility (idea D + the published half of C).** Perf trends through the history DB and
  renders in the public benchmark table + register "Release Scorecard" with no new plumbing, because those already
  consume the canonical record. The STUB's own goal ("make regressions visible") is satisfied via the *existing*
  projection consumers — not a bespoke dashboard.
- **Complete footprint (review deviation #3).** The record carries the *full* resident stack (incl. the ~75% LLM),
  so footprint stops measuring only the 27% ONNX subset.

This is scope-matched: it is exactly the structure the "conform to the canonical-record seam" claim requires — no
more. The ratchet, envelope, projection, trend, and published scorecard all already exist for quality; perf becomes
another family in the same machinery. The change's size is the outcome of that judgment, not a target.

## What the problem does NOT require (out of 640's scope — see §Reach)

- **A shared ratchet kernel** unifying the three gate *orchestrations* — the perf ratchet functions without it; the
  3× duplication is a *family-level* problem 640 reveals but does not own.
- **A combined family scorecard as a new UX** beyond perf rendering in the existing release surfaces.
- **The LLM-generation-latency sibling axis** — a *fourth* family (inference-runtime subsystem + its register);
  named, not built here.

---

# Reach & principle (recognized, not built)

## It conforms to an existing seam — do not fork it
This design is an instance of the **canonical-record + governed-projection seam** (623/553/559/622) and of tempdoc
**625's projection-vs-fork principle** ("every asserted measurement traces to a cohort-identified reproducible run;
a hand-typed table is a fork"). Idea B *is* a 625 instance — and the shipped perf baseline (two corpora pinned from
two commits) is precisely the **"fork bites a second time"** that 625's own *Trigger* names as the condition to act
(the relevance-ratchet file was the first). So 640 fixing *its own* instance (project perf from the release) is the
correct, in-scope move; **625's *generalized* enforcement stays deferred** — recognizing the principle is not
building the generalized structure.

## The recurring shape this reveals (named plainly)
**Every engine-quality axis should be a first-class metric family in the canonical measurement record, guarded by a
governed-projection regression ratchet over it.** The shape = the metric-family-parametric record (623) + a
per-family ratchet. It now has **three instances** (relevance, perf, leak) and a **fourth coming** (LLM-gen).

**Where it applies / existing violations:**
- **Relevance** — conforms (nDCG is a first-class family; projects from the release).
- **Perf** — brought into compliance by *this* design.
- **Leak** — **violates it**: it reads a *separate* projection artifact (`staged_recall_accounting.json`) via its own
  `derive_baselines`, not the canonical metric map — a side-channel, exactly as perf was. (Candidate to promote next.)
- **Agent-utility (624)** — a measured axis not yet a canonical family or a ratchet.
- The **three forked gate implementations** (`relevance_gate`/`perf_gate`/`leak_gate` + their `cmd_*`) duplicate one
  orchestration — the DRY half of the shape's violation (the §A "ratchet kernel").

## Do not build the generalized structure now
Per AHA + 625's explicit deferral, the **shared ratchet kernel + combined scorecard + generalized
projection-provenance enforcement** are *not* built here. The trigger is closer than when 625 was filed (three
instances now exist, and the fork has bitten a second time), so this section is the standing **warrant**: when the
family unification is prioritized (or a fourth axis lands and the duplication cost bites), the kernel should be
extracted *onto* the canonical-record seam — not as a parallel mechanism — and its natural home is a family-level
doc / 625-adjacent / a search-quality-register decision, **not 640** (the perf axis). Recognizing the shape and
building the general structure are kept separate on purpose.

---

# Pre-implementation confidence pass — the SETTLED design (2026-06-24)

> Read-only de-risking of the settled "promote perf into a first-class metric family of the canonical record"
> design — no feature code, no dev stack. Per-consequence test: is the canonical-record machinery genuinely
> metric-family-**generic** (the design's "for free" holds), or quality/per-mode-**shaped** (the work is larger)?
> The direction holds, but the headline **"one move, everything follows for free" is refuted.** Cites `file:line`.

## What was checked, and what it returned

| # | Uncertainty | Finding | Verdict |
|---|---|---|---|
| **U1** | record/release granularity | release `measured.<ds>.metrics` is **per-mode + quality-only** (`{nDCG,P@1,R@10,RR@10,AP@10}` — confirmed on the on-disk `release.v1.json`); `compose`/`_measured_for_mode` read only per-mode `aggregate_metrics` (`release.py:222-245,260-369`); there is **NO per-run slot** for throughput/footprint in the release object or schema (the metrics map is *open* but *per-mode*, `release.v1.schema.json:48-51`). | **NEEDS WORK** — per-mode CE-latency must be promoted *into* `aggregate_metrics` (today it's in `stage_timing_stats`, `run.py:426`); per-run throughput+footprint need a **NEW per-run metric-family slot** (release object + schema + `compose`). "Add keys" is false for 2/3 families. |
| **U2** | published renderers generic? | both `gen-public-benchmark.mjs` (`:47-48,74,88`) and `register-headline-sync.mjs` (`:77,84`) **hardcode `nDCG@10`** as the column → a perf metric in the release is **silently ignored**. | **NEEDS WORK** (~25–50 lines each). "Renders for free" false. |
| **U3** | calibrate reaches CE-stage? | reads `aggregate_metrics`+`latency_stats` only via hardcoded tuples (`calibrate.py:187-207`); **cannot reach `stage_timing_stats.cross_encoder_ms.p50`**; **no warmup-discard**. | **NEEDS WORK** — unless CE-latency is first promoted into `aggregate_metrics` (then a tuple addition reaches it). "Median floor for free" false as-is. |
| **U4** | history DB generic? | `runs` is **fixed columns** (`history.py:47-58`); `check_trend` hardcoded to `ndcg_10` (`:281-355`). (`envelope_metrics` IS a generic kv table but under-populated.) | **NEEDS WORK** (~100–120 lines: schema migration + populate + trend parametrization). "Trend for free" false. |
| **U5** | full footprint w/o backend change | LLM **gguf file bytes** derivable via a **1-line manifest-capture add** (`/api/debug/effective-config` exposes the model path, `manifest.py:85-92`) then stat like ONNX; KV-cache only **estimable** from `configuredContextTokens`; **actual llama-server RSS/VRAM is NOT emitted** (needs backend). | **PARTIAL** — "configured model bytes incl. LLM" is reachable; precise resident RSS needs backend emission (out of scope). A definitional choice, acceptable. |

## The meta re-weight (the headline correction)

**The canonical-record machinery is quality/per-mode-shaped, not metric-family-generic.** The open release
*schema* is the only generic part; everything around it — `compose`'s per-mode-only extraction, both published
renderers, calibrate's hardcoded tuples, history's fixed columns — is wired to nDCG + per-mode latency. So the
settled design's elegance ("promote perf once → projection/envelope/trend/scorecard all follow *for free*") is
**over-optimistic** — the same "~95% reuse" optimism the v1 confidence pass corrected. The 623 seam is
**aspirational, not realized**: 623 opened the schema slot, but the pipeline around it stayed single-family.

## Corrected scope (what the work actually is)

Not "one move + free," but a **coordinated, bounded multi-file extension** mirroring the quality path:
1. promote per-mode **CE-stage latency into `aggregate_metrics`** (`run.py`) so it flows into release/calibrate/history like nDCG;
2. add a **per-run metric-family slot** to the release object + schema + `compose` (`release.py`) for throughput+footprint;
3. teach the **two published renderers** to render the perf family (parametrize the hardcoded nDCG column);
4. extend **calibrate** (tuple/extraction) + **history** (schema migration + `check_trend` parametrization) for the perf metrics;
5. add **`/api/debug/effective-config` to the manifest capture** so footprint can include the LLM gguf bytes.
Each step is mechanical and reachable (no hidden machinery), but it is ~5–6 files, not a one-liner.

## The design call this surfaces (recorded, not resolved here)

Doing (1)–(4) **perf-specifically** ADDS a second hardcoded family to a quality-shaped pipeline — the very
pattern that created the 3-gate duplication. The alternative — **genericize the record machinery** (so `compose`,
renderers, calibrate, history all iterate the metric map and any family is *actually* free) — is larger and is
**623/625's reach** (finishing the metric-family-generic record 623 only schema-opened), not 640's perf-axis
scope. So 640 should take the bounded perf-specific path and **record that the generic-record work is the
623/625-adjacent home** for the family unification — consistent with §Reach "don't build the generalized structure now."

### RESOLVED (user decision, 2026-06-24) — genericize the record; perf is the first family
The user chose to **genericize the record machinery** rather than the bounded perf-specific path — pulling the
623/625 generalization **forward into scope**, because the three-family trigger has fired (relevance/perf/leak)
and the no-users / no-rush context makes the larger, duplication-removing investment worthwhile. So the design is
now: **make `compose` / the two published renderers / `calibrate` / `history` iterate the metric map generically
(a metric-family-parametric record end-to-end, finishing what 623 only schema-opened), with performance as the
*first* family proving it and `leak` as the obvious second** (folding leak's side-channel into the same generic
machinery is the real test of the generalization). The bounded perf-specific path is **not** taken. This shifts
the §Reach boundary: the generalization is no longer "recognized, not built" — it is **chosen to build**, at its
proper record-level home (623/625-adjacent), with 640's perf family as the driving consumer. Footprint definition:
**configured model bytes incl. the LLM gguf** (deterministic, no backend emission), per the §confidence-pass U5.

## Confidence rating for the remaining work: **6 / 10**

Direction is sound (conform to the canonical-record seam; perf as a family) and the work is now **fully mapped** —
every consumer's genericity is known, so there are no hidden machinery surprises left. Held to **6** because the
design's central **"one move, everything for free" thesis is refuted**: the machinery is quality-shaped, so the
real work is a coordinated ~5–6-file extension, plus an unresolved scope/design call (perf-specific extension vs
genericize-the-record = 623/625 reach) and a footprint-definition choice (model-bytes vs actual-RSS). Not low
(feasible, fully de-risked, conforms to the seam); not high (the headline elegance was wrong, and a design call
remains before build).

### Update (post-decision, 2026-06-24) — rating holds ~6, but the limiter has MOVED
Two things changed after the rating above: (1) a read-only **collateral-damage check** confirmed promoting a perf
metric into `aggregate_metrics` is **non-breaking** — every reader key-accesses a named metric
(`relevance_gate.py:70`, `history.py:109-113`, `corpus_fidelity.py:122`), copies the whole map (`release.py:231`,
`run.py:257`), or iterates a fixed tuple (`calibrate.py:196`); none mis-iterate — so that mechanical residual is
settled and the footprint definition is decided (configured model bytes incl. LLM). (2) The dominant limiter —
the design-shape call — is **resolved: genericize the record** (user decision). But that selected the *larger*
path, which **re-opens a feasibility question not yet fully mapped**: can `compose` / the two renderers /
`calibrate` / `history` each cleanly become metric-map-generic, and does **`leak` fold into the same generic
machinery** (the real test of the generalization)? So the rating **stays ~6**: direction is now fixed and the
mechanics are de-risked, but the chosen genericize scope is bigger and its feasibility is un-verified — the
limiter moved from *"which design?"* to *"is the generic-record refactor cleanly achievable across all consumers
+ leak?"*. **Highest-value next confidence step: a focused feasibility de-risk on the generic-record shapes** —
the metric-family registry, renderer/calibrate/history genericization, and folding `leak` in — before building.

---

# As-built v2 — genericized record (2026-06-24)

The generic-record design (the user's chosen path) was implemented. **Structural core done + the full jseval
unit suite is green; the live end-to-end proof is the one remaining step** (honest status).

## Shipped + unit-tested (full `pytest tests/` green)
- **Metric-family registry** — NEW `jseval/metric_families.py`: the single source of truth, with the **three
  source-classes the leak-fold test forced** (`per_mode` | `per_run` | `projection`). Families: quality (per-mode),
  perf-latency (per-mode CE-stage), perf-throughput + perf-footprint (per-run), leak (projection). 8 tests.
- **Generic per-mode/per-run record + release + schema** — `run.py` promotes CE-stage p50 into `aggregate_metrics`
  (non-breaking: every reader key-accesses/whole-copies) + adds a per-run `run_metrics` map (throughput);
  `release._measured_for_mode` carries `measured.<ds>.run_metrics` (throughput + derived footprint);
  `release.v1.schema.json` gains optional `run_metrics`.
- **Perf projects from the canonical release (closes the fork, review deviation #1)** —
  `perf_gate.project_release_to_perf_baselines` + a `current_release` projection in `cmd_perf_gate` (mirrors
  relevance). Readers read the canonical locations (aggregate_metrics / run_metrics) with legacy fallback.
- **Calibrate is registry-aware** (also calibrates the CE-stage p50 the registry declares).
- **Leak registered** — `leak_gate` sources its tolerance from the registry; its **projection-sourced gate is
  unchanged** (the leak-fold refinement: registered, not migrated).
- **Published renderers genericized** — `gen-public-benchmark.mjs` + `register-headline-sync.mjs` emit an
  "Engine performance" table from the perf families a release carries (quality-only releases unchanged).

## The key finding (the leak-fold test) — a design refinement, not a reversal
Perf folds in fully, but **leak does NOT** — `leak_rate` is a *cross-mode projection* metric, not a
per-mode/per-run scalar; forcing it into the record would bifurcate it, and its projection-sourced gate is the
*correct* architecture. So the registry recognizes **three source-classes**; leak is registered (unifying the
family concept) but stays projection-sourced. "Genericize" delivers a generic record + registry **perf rides
fully**, and **leak joins the registry, not the record**.

## Live end-to-end validation — PASSED (2026-06-24)
A fresh full run (`jseval run --start-backend --clean --pipeline --ce --embedding --splade --dataset scifact
--modes hybrid`) carried the perf families (`aggregate_metrics.ce_p50_ms=338`, `run_metrics={primary_docs_s:86.7,
enrich_docs_s:13.6}`); `jseval release` composed it into `release.measured["beir/scifact"]` carrying
`metrics.ce_p50_ms` + `run_metrics` **including the derived footprint (2.02 GB)**; `perf-gate` with a
`current_release` pointer **projected the floor from that release** and **exited 0** (all 4 metrics ok); a
tightened release floor → **exit 1** (`ce_p50_ms` fail). The per-run fork is closed end-to-end. The live run also
caught + fixed a real integration bug: the release keys on the canonical slug (`beir/scifact`) while the run's
manifest is the raw slug (`scifact`), so `run_dataset_ok` now canonicalizes (via `release.canonical_dataset_slug`)
— exactly the kind of mismatch live validation exists to surface. No user-visible UI → no browser validation applies.

---

# As-built v3 — remaining work completed (2026-06-24)

A read-only confidence pass de-risked the four remaining items, then they were implemented. The pass **refuted
the deferrals' premises** as much as it confirmed feasibility:

- **R1 — footprint now includes the LLM.** The deferral's reason (adding an endpoint would change the cohort
  `manifest_hash`) was **wrong**: the captured `status`/`debug-state`/`inference-status` snapshots are all in
  `manifest._VOLATILE_FIELDS` (excluded from the hash). And **no backend change was needed at all** — a live
  AI-online probe showed `/api/inference/status` already carries **`activeModelId`** (e.g.
  `Qwen_Qwen3.5-9B-Q4_K_M.gguf`), captured into the non-hashed `inference_status_snapshot`.
  `perf_gate.derive_resident_model_bytes` now reads it, stats the gguf (+ mmproj if `hasVisionCapability`) from the
  models-dir, and SKIPs on AI-offline runs (the LLM isn't resident then). Live-verified against the real models
  dir + live field: **ONNX-only 2.02 GB → incl. LLM 7.91 GB** (LLM = 5.89 GB), matching the §D-4 ~7.5 GB estimate.
  *(KV-cache estimate intentionally omitted — arch-dependent, needs a gguf-header parse; the gguf file bytes are
  the deterministic configured-weight footprint.)*
- **R2 — noise floor is now envelope-aware, gracefully.** `perf_gate.evaluate` derives a data-driven band
  (`1 ± k·CV`, k=2) from the cohort `non_determinism_envelope` when it carries the metric, **falling back to the
  fixed `diff_gate` ratio band otherwise** — so the gate is never flappier and is unchanged until a `calibrate`
  envelope exists. `calibrate` already calibrates `ce_p50_ms`. **Warmup-discard was evaluated and NOT built**: the
  CE-stage p50 CV is 1–10% *with* the cold query (the median absorbs it), so a discard only helps total-latency,
  which is not gated — it would add code/risk for zero gate benefit.
- **R3 — history trends perf.** Additive `runs` columns (`ce_p50_ms`/`primary_docs_s`/`enrich_docs_s`/
  `resident_bytes`) via the same idempotent ALTER pattern as `manifest_hash`; `append_run` populates them;
  `check_trend(metric=…)` + `jseval trend --metric` are now direction-aware (latency/footprint lower-is-better).
  Honest limit: only ~3 runs exist, so a perf trend reports `insufficient_data` until history accrues — the
  plumbing completes the outcome; the data accrues over time.
- **R4 — the registry is now the genuine single source** for the Python consumers: `perf_gate` derives its bands +
  directions from `metric_families.perf_families()` (verified byte-identical, so behavior-preserving), joining
  `calibrate` + `leak_gate`. The JS renderers re-declare their perf columns — a documented language boundary (JS
  cannot import the Python registry), not a fork.

**Validation:** full `pytest tests/` green; new unit tests for each item; R1 live-verified (real gguf stat + live
`activeModelId`); R2 asserts both the envelope path and the graceful fallback; R3 asserts populate + direction-aware
regression + the legacy-DB migration. No user-visible UI → no browser validation applies.

## As-built v4 — the fork is closed for real (2026-06-24)

A confidence pass caught that the headline "perf floor is a governed projection, not a fork" was
**capability-complete but artifact-complete only after this step**: the committed
`perf-ratchet-baselines.v1.json` still stapled `enron-qa@65821feeb` (+19 commits) + `scifact@fa17617b` (+13),
6 commits apart and **spanning the 636 ranking levers** — a real fork. Per the user's choice, closed via the
**perf-scoped re-pin** (not the shared-release recompose, which would re-baseline relevance — a cross-owner
side-effect): re-ran both corpora at HEAD and re-pinned via `perf-gate --update-baseline`
(`project_run_to_perf_baselines` — measured, never hand-typed). Result: both pins are now a **HEAD-adjacent,
engine-coherent cohort** — `enron@88518aaf22` + `scifact@d80927341a`, only **2 docs-only commits apart**
(verified: the intervening commits touched solely `docs/tempdocs/635-*.md`, zero engine/eval files), so the
perf metrics are directly comparable. The multi-agent churn (HEAD moved 3× during the runs) made a *byte-identical*
sha impractical, but engine-coherence (the property that matters) holds. Validated: both gate at **exit 0** against
their re-pinned floors; an induced regression → **exit 1**. The shared-release `current_release` projection stays
BUILT and is the deferred coordinated step (it re-baselines relevance, so it is owned jointly — 623/625).

## As-built v5 — the former REACH, implemented (2026-06-24, in a worktree)

Per user prioritization (the AHA/625 "wait for the trigger" deferral overridden — the user is the trigger), the four
former-REACH items were implemented in a git worktree **frozen at `bef184e333`** (which gave the single-cohort sha #1
needs, immune to main's churn). All validated; no app UI → no browser validation.

- **#1 — the shared-release projection is now ACTIVE (the per-run fork is fully closed).** Recomposed
  `release.v1.json` from a clean **5-corpus cohort** (scifact + courtlistener-200 + enron-qa + miracl-de-2k +
  miracl-fr-2k) at one commit, carrying the perf family (CE-stage latency + throughput + footprint) alongside
  quality. Flipped `perf-ratchet-baselines.v1.json` to `current_release: release.v1.json` + empty
  `fallback_baselines` (mirrors relevance) — perf floors now **project from the canonical release**, not inline
  pins. The relevance ratchet **re-baselined in the same recompose** to the **user-accepted** post-636 levels (enron
  −1.8%, courtlistener −2.4%). Validated: perf-gate + relevance-gate project from the release → exit 0; induced
  regression → exit 1. *(interrogate-results: enron's first run had CPU-contaminated throughput from a concurrent
  pytest, 8→96 docs/s — re-run clean before recompose; courtlistener's ~13 docs/s is its real rate, large legal docs.)*
- **K — shared ratchet kernel.** NEW `jseval/ratchet_kernel.py` (`load_baselines_doc` + the `current_release`
  projection, `resolve_run_dir`, `build_summary`/`finalize_report`, `run_gate`); `relevance`/`perf`/`leak` `cmd_*`
  refactored onto it, and the new `llm-gate` rides it too (no 4th fork). Behavior-preserving — live-verified via all
  three gates + the full suite.
- **L — LLM-generation-latency ratchet.** A `bench` source-class **`llm-gen`** family (TTFT + e2e p50,
  median-gate-able at CV 3%/9%), `jseval/llm_gate.py` + `jseval llm-gate`, projecting from `llm-bench.json`.
  Live-validated with a real LLM (pinned TTFT 103 ms / e2e 6.3 s → exit 0; induced → exit 1). **tokens/sec is now
  CAPTURED too** (640 D, below) — `llm_bench` derives `completion = totalTokens − promptTokens` from the chat
  done-event (which emits both flat, `ConversationEngine:357-361`); live-confirmed (token_rate 25.5 t/s on a real
  summarization). It is a 3rd `llm-gen` metric (higher-is-better); the committed baseline pins TTFT+e2e and adds
  tokens/sec on its next bench re-pin.
- **S — engine-quality scorecard.** NEW `scripts/docs/gen-scorecard.mjs` → `docs/reference/benchmarks/scorecard.md`:
  a delta-vs-guard table co-locating quality + perf + leak per corpus (MLPerf split — relevance constraint, perf the
  tracked score), a projection of the release + baselines. The public benchmark / register renderers also render the
  perf family now.

## As-built v6 — residual items closed + two design reconciliations (2026-06-24)

A post-implementation review found five residuals; a confidence pass de-risked them and the user chose B-reconcile.

- **D — tokens/sec (DONE, not a backend change).** The confidence pass disproved the "needs a backend SSE emit"
  assumption: the chat done-event already emits `promptTokens` + `totalTokens` flat (`ConversationEngine:357-361`);
  `llm_bench` only read the wrong nested key. Fixed `llm_bench` to read the flat fields + derive `completion = total
  − prompt`; added `token_rate_median_tps` (higher-is-better) to the `llm-gen` family. Live-confirmed (25.5 t/s).
- **E — `llm-gate` is now nudged.** `search-engine-hint.mjs` gained an INFERENCE-path branch (`app-inference` /
  `prompt-support` / conversation+inference services) that nudges `jseval llm-bench` + `llm-gate` — distinct subject
  from the retrieval ratchets. Tested: inference edit → llm nudge; retrieval edit → 3-ratchet nudge; else nothing.
- **B — footprint reconciled to *resident-during-eval* (user decision).** The perf footprint measures what is
  actually resident during the retrieval eval — **ONNX only**, because the LLM is genuinely not loaded then (AI
  offline). R1's incl-LLM derivation still fires when the LLM *is* resident. The design's "complete footprint incl.
  the ~75% LLM" is a **distinct configured-stack metric** (config-derived via `/api/effective-config`, which exists +
  carries the path) — **deferred** as a dedicated AI-online/inference footprint measure, not the per-run eval footprint.
- **C — noise floor reconciled to the *fixed band + envelope fallback* (NOT "median ± envelope").** The settled
  design's "floor becomes median ± calibrate envelope" is **superseded by the measured evidence**: the CE-stage CV is
  1–10%, so the fixed `diff_gate` ratio band already handles noise and a ±2σ envelope would be *tighter/flappier*. The
  shipped floor (fixed band, with the calibrate envelope as a graceful data-driven fallback when one exists) is the
  correct realized design — this is a *better-evidence supersession*, not deferred work.

## Genuinely remaining (deferred-with-reason)
- **The configured-stack incl-LLM footprint** as a dedicated metric (B-reconcile) — a config-derived AI-online/
  inference footprint, distinct from the per-run resident-during-eval footprint.
- The **625** generalized projection-provenance framework stays its own tempdoc.
- (See the scope correction below — the larger open half of the *budget* is attribution-as-a-tool, allocation, and
  optimization, not just these two items.)

## Scope correction — the "budget" is broader than measure-and-guard (2026-06-25)

A walkthrough of this tempdoc collapsed its purpose to "**measure + guard**" — the regression-ratchet slice that
dominates the shipped work. The title is performance **budget**, which is broader. Recording the fuller scope so the
delivered work is honestly *located* within it, and the rest stays legible as open (not silently out of mind).

**Two ways to slice "engine performance," each broader than what was gated.**

**Slice 1 — the resource axes (what the engine spends).** Each is more than the one number this tempdoc ratcheted:
- **Latency:** query/search latency · **LLM generation** (TTFT, tokens/sec) · per-document indexing latency ·
  cold-start / first-query latency.
- **Throughput:** indexing (docs/sec) · **query throughput under concurrent load (QPS)** — never measured here ·
  enrichment / backfill throughput.
- **Footprint:** RAM · **VRAM + the contention budget** (embedding + reranker + LLM sharing one ~12 GB GPU — itself a
  performance problem on a desktop) · disk / index size · model size.

**Slice 2 — the lifecycle (what you *do* about each axis).** The shipped work is the middle band only:

| Stage | Meaning | Status in 640 |
|---|---|---|
| **Measure** | reliable, de-noised numbers | ✅ delivered |
| **Attribute** | decompose *where* the cost goes (the cross-encoder is ~82% of query latency; which stage dominates each axis) | *analyzed* in §C-2, but **not a standing instrument** |
| **Guard** | catch regressions (the relative ratchet) | ✅ delivered |
| **Budget / allocate** | the literal word: per-stage *allowances* + targets ("rerank ≤150 ms of the query budget"), and which trade-offs are acceptable | **not built** |
| **Optimize** | actually *reduce* the cost — batch sizing, arena tuning, stage reordering, caching | **not built** — the action half |

**Honest location of the shipped work.** 640 delivered the **measure → guard** band over three gated metrics
(CE-stage latency, indexing throughput, resident footprint) plus the LLM-gen axis (TTFT / e2e / tokens-sec). It did
**not** deliver: attribution as a *standing* instrument, per-stage **budgeting / allocation**, **optimization** (the
action half), or the broader sub-axes — **query throughput under load**, **VRAM-contention budgeting**, and
generation-side latency breadth. So the one-line purpose is fuller than "guard the numbers": *give the engine a
performance budget across latency / throughput / footprint — **measure it, understand it, hold the line on it,
allocate it, and improve it***. The engine can now **hold the line**; **allocating** and **improving** the budget are
the unbuilt larger half — candidate scope for a follow-up tempdoc, not closed here.
