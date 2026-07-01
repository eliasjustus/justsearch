---
title: "Engine performance attribution — completing the perf metric-family into a CLOSED per-stage/per-component decomposition (DESIGN SETTLED 2026-07-01). The 'attribute → budget' band tempdoc 640 left unbuilt. 640 shipped one metric per perf family (cross-encoder p50 / primary+enrich throughput / one resident footprint number); the LATENCY family is a decomposition modelled as a single term and FOOTPRINT is a composite modelled as a single sum. The settled design COMPLETES them, not a new subsystem: promote every captured latency stage into the canonical record so it flows/calibrates/projects like ce_p50_ms already does; add a per-stage SHARE + an explicit UNATTRIBUTED remainder so the decomposition CLOSES to the whole (~9% is dark latency today); surface the per-component footprint the derivation already computes. The literal per-stage 'allowance table' the stub asked for is DELIBERATELY NOT built — an absolute per-stage latency target is a workload guess 640 §C-6 forbids; its intent is delivered via A) the relative per-stage floors (finer ratchet) + B) the shares (reported allocation, never gated — shares are zero-sum) + C) the reranker runtime deadline already in production. Footprint is the one place an ABSOLUTE allocation is admissible (deterministic). Conforms to the canonical-record + governed-projection seam (553/559/622/623) via the metric-family registry + shared ratchet kernel; must not fork. Principle named: 'a guarded aggregate is not a guarded decomposition' (decompose-and-close-the-remainder) — already violated by the relevance + leak aggregate ratchets; recorded, not built. Secondary: 'attribution before allocation before optimization' (why 648 follows 647). Design only — NO implementation this pass."
type: tempdocs
status: "IMPLEMENTED + SHIPPED + LIVE-VALIDATED (2026-07-01). All of 647's implementable surface is done as pure `jseval` Python (no engine/UI/public-doc change), unit + live validated. See the dated sections below for the design history; the last three sections (Feature B / Deferred items / Trend / Live-activation) are the current state, and the final §Handoff consolidates open items. SHIPPED: (1) closed latency decomposition — `provenance._aggregate_stage_timing` computes the per-query `unaccounted_ms` remainder + per-stage `share` (report-only; closes to 1.0), `run.py` promotes `retrieval_p50_ms` into `aggregate_metrics` beside `ce_p50_ms`, `metric_families`+`perf_gate` gate retrieval relatively (envelope band + 1.5 fallback) + register per-component footprint, `release._measured_for_mode` projects them; (2) `jseval compare` which-stage-moved attribution (`compare_runs.compare_stage_decomposition`); (3) `jseval perf-report` — per-run latency-waterfall + footprint-allocation readout (`build_perf_report`); (4) `jseval trend` trends `unaccounted_p50_ms` (dark-latency creep) + `retrieval_p50_ms` (history columns + migration); (5) `perf_gate` component-derivation memo. VALIDATED: ~1091 jseval unit tests green (only the 2 pre-existing unrelated `test_correction_probe` failures); a live fresh-backend scifact run proved end-to-end materialization (shares 1.0000, retrieval promoted, footprint incl. NER), `perf-report` renders live, `perf-gate` stays green. REMAINING — NOT a 647 code task: activating the floors so they gate live needs a release recompose, but `release.v1.json` is STALE (anchored to a dead pre-squash commit `bef184e333` + retired corpora `courtlistener-200`/`enron-qa`; today's corpora are `legal-clerc-200`/`miracl-de-2k`/`miracl-fr-2k`), so re-anchoring is an eval-corpus/release-integrity decision (664/666 domain) that re-pins the user-accepted relevance floors — an owner/design call. The perf code activates the moment that recompose lands."
created: 2026-06-25
updated: 2026-07-01
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

---

# Investigation (2026-07-01)

> Take-over investigation per assignment: verify every load-bearing premise against `main`, read the perf substrate
> 640 actually shipped, and think critically — question assumptions and name alternatives. **No design chosen and
> nothing implemented.** This records the verified (and partly corrected) premise and the sharpened decision space
> only. Where it conflicts with the STUB above, this section wins. Claims are cited to primary source `file:line`.

## A. Verdict in one paragraph

The *motivation* is sound — the engine has a **relative regression ratchet** (640) but no **budget** (an
allocation you commit to and read actuals against), and no standing per-stage decomposition. **But the STUB's
framing repeats 640's own original error in miniature: it says "attribution is not yet a standing tool," and that
is only half true.** The raw attribution *data already stands* — every eval run persists a 5-stage latency
decomposition (`stage_timing_stats`: `retrieval_ms` / `chunk_merge_ms` / `cross_encoder_ms` / `lambdamart_ms` /
`branch_fusion_ms`, each mean/p50/p95 — `provenance.py:95-101,197-220`), and the footprint derivation *already
computes* per-component model bytes (SPLADE + embed + reranker + NER + LLM gguf) before collapsing them to one
number (`perf_gate.py:202-221`). So the residual gap is narrower and sharper than "build attribution": **(1)** only
**1 of the ~4 latency stages is guarded** — `run.py:266-276` promotes *only* `cross_encoder_ms.p50` into
`aggregate_metrics.ce_p50_ms`, so a retrieval / chunk-merge / fusion regression ships **silently** (the other
four stages are captured but un-ratcheted); **(2)** the per-stage **share** view (% of total per stage — the "CE is
82%" number) is a one-off write-up, never a derived, readable, per-run field; **(3)** the per-component footprint
is summed then discarded, so "LLM is ~75% of the stack" is likewise not a standing field; and — the sharpest point
— **(4)** the STUB's band-2 ("per-stage *allowances/targets* you commit to, e.g. rerank ≤ X ms") **directly
contradicts 640 §C-6's hard-won conclusion that an absolute target is a workload guess the no-users rule forbids**
— the reason the entire 640 ratchet is *relative*. That contradiction is unacknowledged in the STUB and is the
first thing the design phase must resolve. Net: 647 is a real gap, but it is a **"promote + ratchet the stages
already captured, and surface the share/component decompositions already computed"** problem — plus one genuine
*framing* decision about whether an absolute allowance is even admissible — not a "build attribution" problem.

## B. Claims that hold up (verified)

1. **640 shipped a *relative* ratchet, not a budget.** ✓ `perf-ratchet-baselines.v1.json` is a pointer that
   projects floors live from `current_release` and enforces **ratio bands** via `perf_gate.evaluate` →
   `diff_gate.compare_ratio` (`perf_gate.py:280-367`); the file's own note says "no absolute SLO — 640 §C-6." So
   "there are no per-stage allowances" (STUB §gap-2) is **correct** — the ratchet answers "did this build get
   worse than the last?", never "is stage X within its allocation?"
2. **Attribution is captured but not surfaced as a standing instrument.** ✓ Partially. The 5-stage
   `stage_timing_stats` is produced every run (`provenance.py:197-220`) and kept in `summary.json`
   (`run.py:456`), but no *derived share* (stage ÷ total) and no cross-run trend of the non-CE stages exists; only
   CE is promoted + calibrated + ratcheted (`metric_families.py:72-81`; `run.py:266-276`).
3. **The seam to reuse is real and is a projection, not a fork.** ✓ `perf_gate.project_release_to_perf_baselines`
   (`perf_gate.py:406+`) mirrors `relevance_gate.project_release_to_baselines`; `metric_families.py` is the single
   registry the STUB's "project from the same release" instinct should extend. A budget table must project from
   the same authority — not become a second hand-typed one.

## C. Critical findings — where the STUB must be sharpened or corrected

### C-1 — "Attribution is not yet a standing tool" is half-wrong; reframe to "the captured stages are un-promoted and un-guarded" *(most important, mirrors 640 §C-1)*
The decomposition data is standing (§A, §B-2). What is missing is three cheap *promotions*, not a new instrument:
**(a)** promote the non-CE stages' p50 into `aggregate_metrics` the way `ce_p50_ms` already is, so a
retrieval/chunk-merge/fusion regression is caught instead of shipping silently; **(b)** derive a per-stage
**share** (`stage_p50 / Σ stages`) as a readable field so "which stage dominates / which stage moved" is answerable
without re-deriving it by hand; **(c)** return the per-component footprint dict `perf_gate.py` already computes
(`:202-221`) instead of only its sum. Re-proposing "build standing attribution" as greenfield would be the exact
`explore-before-implementing` failure 640's own investigation caught — this stub is at risk of repeating it.

### C-2 — Band-2 ("per-stage allowances/targets") collides with 640 §C-6 — the unacknowledged central tension
640 concluded, and flagged to the user as *the* framing question, that an **absolute** target ("p95 < 200 ms") is a
workload guess the no-users rule forbids — which is why the ratchet is relative. The STUB's band-2 asks for exactly
an absolute per-stage target ("rerank ≤ X ms of the query budget"). These cannot both stand. Three reconciliations,
none chosen here:
- **(i) Relative-share invariant.** Express the budget as a *distribution* ("CE ≤ ~85% of query latency"), not an
  absolute ms. A share is more workload-blind (it is about cost *structure*, not magnitude) — but "what share is
  acceptable" is still a judgment, so this is a partial, not clean, escape.
- **(ii) Worst-case *cap*, not an SLO *target*.** A deadline that bounds the tail regardless of workload (see C-3)
  is rule-compatible in a way an "is it fast enough" target is not — it asserts *nothing gets pathologically slow*,
  not *this is the right speed*.
- **(iii) Footprint is the exception where an absolute allowance IS admissible.** Footprint is **deterministic**
  (config-determined, workload-independent), so "the LLM is ~75% / ≤ N bytes of the resident stack" is a real,
  checkable, workload-blind allocation — 640 §C-6's objection (workload guess) simply does not apply to it. **The
  footprint half of band-2 is clean; the latency half is the contested one.** The STUB treats the two symmetrically;
  they are not.

### C-3 — The engine ALREADY has a per-stage latency budget — the reranker deadline (a strong anchor the STUB omits)
The cross-encoder already runs under `config.deadlineBudgetMs()` — `reranker.rerank(question, chunkTexts,
config.deadlineBudgetMs())` (`RagContextOps.java:1024`; also `:172`). This is a per-stage latency **allowance
already committed to in production** — but as a *cap/deadline* (bound the worst case), which is precisely the
rule-compatible form C-2(ii) points at, not a "fast enough" SLO. Two consequences the design phase should use:
**(a)** band-2 is *not* greenfield — the first "allowance" already exists, and the honest artifact may be "surface
actuals against the deadline the engine already enforces," not "invent an allowance table"; **(b)** it is the
cleanest evidence that a per-stage *cap* can coexist with the no-absolute-SLO rule (a deadline is a safety bound,
not a workload target).

### C-4 — 647 vs 648 boundary: since the data already stands, is 647 a separate *build* or a lead-in to 648?
648 (optimize) states it "must not be picked up before 647." But 648's first experiment (the CE pool-size ↔
latency sweep) can read `stage_timing_stats` **directly today** — the attribution it needs already stands (§A). So
the part of 647 that is a genuine, separable deliverable is narrow: **promote+ratchet the un-guarded stages (C-1a)
and the share/component decompositions (C-1b/c)** — a small extension of 640's own machinery. The **allowance
table** (band-2) is the contested, framing-dependent part. Worth surfacing: 647's separable value may be small
enough to fold into a 640-followup, with the "budget/allowance" question either resolved (per C-2) or deferred —
rather than a standalone build. This is a scoping call for the user, not mine to make.

## D. The decision space (named, NOT chosen — for a future design phase)

| Gap | Cheapest rule-compatible response (not chosen) | Reuses | Rule-fit | Note |
|---|---|---|---|---|
| Only CE stage is guarded (C-1a) | promote the other captured stages' p50 into `aggregate_metrics` + ratchet relatively | `run.py:266-276`, `metric_families.py`, `perf_gate` | high (relative) | closes a real silent-regression gap |
| No standing share view (C-1b) | derive `stage_share = stage_p50 / Σ` as a per-run field | `stage_timing_stats` | high | pure derivation, no new capture |
| Per-component footprint discarded (C-1c) | return the component dict `perf_gate.py:202-221` already sums | `derive_resident_model_bytes` | high | ~free; enables the footprint allocation |
| Latency **allowance** table (band-2) | **framing decision first** (C-2): relative-share vs worst-case cap vs drop | `deadlineBudgetMs` (C-3) as the existing cap | **contested** | absolute ms collides with 640 §C-6 |
| Footprint **allowance** (band-2) | an absolute allocation IS admissible (deterministic — C-2iii) | per-component footprint (C-1c) | high | the clean half of band-2 |

## E. Questions the design phase must answer first (not answered here)

1. **Is an absolute per-stage latency allowance admissible at all,** given 640 §C-6? (C-2) — the framing decision;
   the rule-compatible forms are a *relative share* or a *worst-case cap* (the reranker deadline already is one).
   **This is a user call before any build**, exactly as 640's relative-vs-absolute question was.
2. **Is 647 a separate build or a 640-followup + lead-in to 648?** (C-4) — given the attribution data already
   stands, the separable deliverable is small.
3. **Which stages get promoted + ratcheted** beyond CE, and does the share decomposition ratchet or only report?
4. **Does the footprint allocation gate** (an absolute per-component band, admissible per C-2iii) **or only
   report**?

## F. Honest limit

I read the shipped perf substrate on `main` but did **not** run a fresh eval this pass (the STUB is pre-design and
the numbers in 640 §C are still the working baseline). The verification owed before any build is: confirm the
non-CE stages carry usable signal (are `retrieval_ms` / `chunk_merge_ms` non-trivial and stable enough to ratchet,
or is CE so dominant that guarding it suffices?) — a `stage_timing_stats` read across the corpus cohort, not run
here. No design or implementation is done in this pass, per the assignment.

---

# Theorization (2026-07-01)

> Broad exploration of the solution space *before* any design is settled — framings, tradeoffs, hidden
> assumptions, risks, and whether the problem points at a wider principle. **Nothing here is a chosen design.**
> The point is to widen the option set and name the decisions the design phase inherits, so later work is picking
> among understood alternatives rather than re-discovering them.

## 1. "Budget" is (at least) four different artifacts — the design must pick one deliberately

The stub uses "budget" loosely. It actually spans four distinct artifacts with different costs, audiences, and
rule-fit. Conflating them is the biggest latent scope risk.

| Reading of "budget" | What it is | Enforced where | Rule-fit (640 §C-6) |
|---|---|---|---|
| **A. Finer ratchet** | per-stage relative floors, projected from the release | governance-time (`jseval`/hook) | high — same as 640 |
| **B. Allocation of a total** | a share/split of a whole ("CE ≤ ~85% of latency; LLM ≤ ~75% of footprint") | governance-time, reported | medium (latency) / high (footprint, deterministic) |
| **C. Runtime cap** | a deadline that triggers graceful degradation when exceeded | **runtime** (in the engine) | high — a worst-case bound, not a target |
| **D. Cost model** | a *function* predicting cost from inputs (e.g. CE ≈ k·candidates) | design-time, predictive | n/a — a model, not a gate |

These are not refinements of one idea; they are different products. A is a small extension of 640. C already
partly exists (the reranker `deadlineBudgetMs`, §Investigation C-3) and is a *behavioral* feature, not a
measurement one. D is the most powerful and the most speculative. **The stub's band-2 reads as B but is written in
the vocabulary of A ("allowances you check actuals against").** Naming which one is being built is prerequisite to
any design.

## 2. The allocation framing (B) buys one thing the ratchet cannot: legible compensating trades

A per-metric ratchet answers "did stage X get worse?" It is **blind to a trade** — if rerank slows by 20 ms while
retrieval speeds up by 20 ms, every per-stage floor and the (excluded, noisy) total all look flat, yet the engine's
cost *structure* has shifted toward the most expensive stage. Only an allocation/share view surfaces that. This is
the concrete capability behind the stub's "trade-offs become decisions, not surprises" — and it is genuinely
*additional* to 640, not just finer resolution.

**But shares are a zero-sum simplex — do not ratchet them.** If CE gets faster, every other stage's *share* rises
without any absolute change. A naive "retrieval share regressed" gate would fire on a CE *improvement*. The
sound shape is therefore asymmetric: **ratchet the absolute per-stage values (A); *report* the shares (B) as a
diagnostic.** The share view explains; the absolute floors guard. (This mirrors 640's own "measure richly, gate
narrowly" discipline — gate only the low-CV metric, keep the rest for drill-down.)

## 3. Hidden assumptions worth testing before committing

- **"Stages are additive and sum to the whole."** They do not, today: 640 §C measured retrieval ~6% + chunk-merge
  ~3% + CE ~82% + fusion ~0% ≈ **91%** — roughly **9% is unattributed** (serialization, the head↔worker gRPC hop,
  queueing, un-instrumented glue). A budget that only sums *named* stages has a **dark-latency gap**: optimize the
  82% stage and the unowned 9% silently becomes the new dominant cost with nobody watching it. → **Design
  invariant candidate: the decomposition must close to 100% with an explicit `unattributed`/`other` line.** This
  is the single most actionable idea in this section — it is cheap, and its absence is exactly how attribution
  quietly lies later.
- **"p50 is the right statistic for a *budget*."** 640 gates p50 because the tail's CV is too high to *ratchet*.
  But a budget is a claim about *user experience*, and the user feels p95/p99. The resolution is the same
  gate/report split: **gate on p50, attribute the tail** — so the instrument does not go blind to the number the
  user actually perceives just because it is too noisy to gate.
- **"One eval corpus + one machine predicts the user's profile."** CE-at-82% is a property of this corpus and this
  GPU (640's honest limit). On CPU-only hardware, or a tiny corpus, the *shape* differs. This reinforces
  relative-not-absolute for any gate — but it also implies attribution is most truthful **on the user's own
  machine**, which is a different audience than the eval cohort (see §5-D).
- **"Footprint is one number."** Resident bytes conflates **RAM, VRAM, and disk** — but the scarce resource
  differs by machine (VRAM on a GPU box, RAM on a CPU box, disk for the index). A single `resident_bytes`
  allocation hides *which* resource is contended. A per-resource × per-model view is the input a VRAM-contention
  policy (punted to the inference-runtime register) would actually need — a clean attribution/policy handoff.

## 4. Risks

- **Allowance-as-theater.** A hand-typed allowance table looks rigorous but, if the numbers are arbitrary, it
  either never fires or flaps — and the reflex becomes tuning the table to green rather than fixing the code. The
  antidote is already law here: **any allowance must *project* from a measured release, never be hand-typed**
  (the anti-fork discipline 640/623 established). An absolute allowance that cannot be projected (because it is a
  workload guess) is precisely the one 640 §C-6 forbids — so the projection test doubles as the admissibility
  test.
- **Observer effect / over-instrumentation.** Finer attribution costs measurement (more `nanoTime`, larger
  traces). Stage-level is already paid; pushing *sub-stage* (inside CE: tokenize vs forward vs sort) is where the
  instrumentation starts to perturb what it measures. Granularity is a budget of its own.
- **Scope creep into a "performance subsystem."** "Attribution + budget" *sounds* like it wants an APM stack. The
  discipline that kept 640 small must hold: this is a **metric-family sibling of the existing ratchet**, not a
  parallel pipeline. If a design starts introducing a new authority for perf data, it has forked the record.
- **Coupling the easy half to the contested half.** Band-1 (attribution) is rule-clean and immediately useful;
  band-2's *latency* allowance is framing-blocked (§Investigation C-2). Binding them stalls the value on the
  debate. **Decoupling — ship attribution, defer latency-allocation — is the main de-risking move available.**

## 5. Alternative directions (named, not chosen)

- **A — Attribution-only v1.** Promote + ratchet the un-guarded stages, derive the share view + the `unattributed`
  remainder, surface per-component footprint. Frame attribution primarily as **the explainer for the ratchet that
  already exists** ("when the aggregate fires, which stage moved?"), not a new set of gates. Lowest risk, unblocks
  648, defers the allowance debate. The conservative reading.
- **B — Allocation-as-runtime-cap (C above), not a CI table.** Lean into the reranker deadline: express latency
  budgets as runtime caps that trigger graceful degradation (drop candidates / skip a stage), and let the ratchet
  guard the *caps' effectiveness* (is the cap firing more often than last release?). This turns "budget" into
  product behavior a user benefits from, not governance paperwork — a materially different, and arguably more
  valuable, product than a gate.
- **C — Cost-model / predictive budget (D above).** Fit CE ≈ k·candidates (the pool-size↔latency dial 640 flagged
  and 648 targets). Lets a config change's cost be *predicted* before it ships — pre-emptive rather than reactive.
  Powerful; probably over-scope now; recorded as a reach that 648's first experiment would produce the data for
  anyway.
- **D — Per-install attribution (user-facing diagnostic).** Attribution computed on the *user's* machine as a
  "why is search slow here?" diagnostic, distinct from the dev-cohort eval view. Different audience, likely a
  different tempdoc; noted because §3 shows the eval profile is not portable.
- **E — Per-resource × per-model footprint matrix.** Attribute RAM/VRAM/disk separately per model, so the
  scarce-resource question is answerable and hands cleanly to the (separately-owned) VRAM-contention policy.

## 6. Granularity: the useful attribution axis is "per lever," not only "per stage"

The stub says "per stage." But the decisions attribution should inform live *inside* stages: 648's dial is CE
**candidate count** (inside cross-encoder); 636's default-on levers are **leg-arbitration + recall-complete**
(inside retrieval/fusion). So the actionable granularity is a spectrum — **stage → lever → sub-op** — and the
*lever* rung is where optimization choices actually get made. The flag-on/off shared-index A/B (the 640 §C-2 /
636 method) already attributes *per lever* on demand; the open question is whether that becomes a **standing**
per-lever line or stays an on-demand probe. Framing attribution as "per lever" (not just "per stage") is what makes
it a real prerequisite for 648 rather than a prettier version of data 648 could already read.

## 7. Does this point at a broader principle?

Two candidate invariants, offered for scrutiny — not yet elevated to canonical:

1. **"A guarded aggregate is not a guarded decomposition."** Guarding a total (or its dominant term) leaves the
   *composition* unguarded: compensating trades and non-dominant regressions hide inside a flat aggregate, and
   whatever is *unattributed* hides in the gap. The positive form — **decompose-and-close-the-remainder** — is the
   natural companion to 640's **measure-and-ratchet**: every aggregate worth guarding is worth decomposing, and the
   decomposition should sum to the whole so nothing hides. It generalizes past perf: nDCG@10 vs per-facet
   relevance (a facet can rot while the mean holds), `leak_rate` vs per-leg leak, footprint vs per-model/
   per-resource, extraction quality vs per-format. That breadth is the evidence it is a real shape, not a
   perf-only trick.
2. **"Attribution before allocation before optimization."** The 640→647→648 chain is not arbitrary sequencing; it
   is the *interrogate-results* discipline made structural: **you may not allocate a budget you have not
   attributed, nor optimize a cost you have not allocated.** This is exactly why 648 declares it "must not precede
   647" — the ordering is the anti-guesswork invariant, and stating it as an invariant (rather than a per-tempdoc
   note) is what makes it reusable for the next optimize/measure pair.

Both conform to — and must not fork — the **canonical-record + governed-projection seam** (553/559/622/623) that
640 already sits on: a decomposition is another *governed projection* of the one measurement record, and an
allowance is another metric *projected from the release*, never a second hand-typed authority. Whatever the design
chooses, the record stays single and the views stay derived.

---

# Design (settled 2026-07-01)

> Long-term design per assignment. **General, not implementation-level** — it settles the *shape*, the *reuse*,
> and the *scope*, grounded on a primary-source read of the shipped substrate (`metric_families.py`,
> `ratchet_kernel.py`, `perf_gate.py`, `release.py` `_measured_for_mode`/`compose`, `provenance.py`
> `_aggregate_stage_timing`) and the §Investigation / §Theorization above. No implementation this pass.

## Thesis (one paragraph)

**The design is: the performance metric-family, *completed* — not a new "budget subsystem."** 640 shipped one
metric of each perf family (cross-encoder p50 for latency, primary/enrich for throughput, one resident number for
footprint). The **latency family is a decomposition realized as a single term**, and the **footprint family is a
composite realized as a single sum** — both are stubs of the fuller record the problem needs. The design completes
them: promote **every captured latency stage** into the canonical record so it flows / calibrates / projects
exactly like `ce_p50_ms` already does; add a derived per-stage **share** and an explicit **`unattributed`
remainder** so the decomposition **closes to the whole**; and surface the **per-component footprint** the
derivation already computes. The literal "per-stage *allowance table*" the STUB asked for is **deliberately not
built** — an absolute per-stage latency target is a workload guess 640 §C-6 forbids. Its legitimate intent is
delivered through three homes that already exist: the **relative per-stage floors *are* the budget** (finer
ratchet), the **shares *are* the allocation** (reported, not gated), and the **reranker runtime deadline is the one
admissible absolute latency bound** (already in production — `RagContextOps.java:1024`). Footprint is the one place
an **absolute** allocation *is* admissible, because it is deterministic (config-determined, workload-independent).
No new authority, no new pipeline: every added number is a governed projection of the one measurement record.

## D-1 — Reuse, do not replace (verified present on `main`; the design extends these)

- **The metric-family registry** (`metric_families.py`) — the single source of truth the design extends by adding
  latency-stage + footprint-component metric keys; it is *the* place a family is declared (calibrate/history/
  gates/renderers all read it). Nothing else should re-declare them.
- **The shared ratchet kernel** (`ratchet_kernel.py`) — load-baselines-with-projection, cohort-engine homogeneity
  guard, finalize. New metrics inherit all of it for free.
- **The perf gate + its projectors** (`perf_gate.py` `evaluate` / `project_release_to_perf_baselines`) — the
  readers extend to the new keys; the projection path is unchanged.
- **The canonical release** (`release.py` `_measured_for_mode` → `compose`) — already carries `metrics`
  (per-mode) + `run_metrics` (per-run). Latency stages ride `metrics`; footprint components ride `run_metrics`.
- **The within-machine envelope** (`calibrate.py`) — decides, *from measured CV*, which new stages are stable
  enough to gate vs report-only. The gate/report split is data-driven, not guessed.
- **The stage-timing capture** (`provenance.py` `_aggregate_stage_timing`, 5 stages) — the raw decomposition; the
  design adds the *share + remainder* derivation on top, no new capture.
- **The runtime deadline** (`RagContextOps.java:1024`, `config.deadlineBudgetMs()`) — the existing per-stage cap;
  the design *relates actuals to it*, it does not invent a new one.

## D-2 — The structural gap (the one thing the problem actually requires)

The latency family is a **decomposition modelled as a scalar**. That is the whole defect, and it has exactly three
completions — all small, all on the existing seam:

- **(a) Promote every stage, not just CE.** `run.py:266-276` lifts only `cross_encoder_ms.p50` into
  `aggregate_metrics`; the other captured stages (`retrieval_ms` / `chunk_merge_ms` / `branch_fusion_ms`, and
  `lambdamart_ms` when present) stay in `stage_timing_stats` drill-down, un-promoted and un-guarded. Promote each
  stage's p50 into `aggregate_metrics` so it becomes a first-class, projectable, calibratable metric like CE.
- **(b) Close the decomposition to 100%.** Derive `stage_share = stage_p50 / total_p50` and an explicit
  **`unattributed_ms` / `unattributed_share`** = `total − Σ stages` (today ≈ 9% is unowned — §Theorization 3).
  This is the load-bearing addition: without the remainder, attribution silently lies as stages are optimized.
- **(c) Return the footprint components.** `derive_resident_model_bytes` (`perf_gate.py:202-221`) already sums
  per-file model bytes; surface the per-component dict (embed / SPLADE / reranker / NER / LLM) as `run_metrics`
  keys instead of only the total.

## D-3 — The design (general statement)

- **Latency → a closed per-stage decomposition.** N first-class per-stage p50 metrics + an `unattributed` term
  that makes them sum to the total. **Gate narrowly, report richly:** `calibrate` selects the low-CV stages
  (CE qualifies today; others are admitted only if the measured envelope says they are stable) and those ratchet
  **relatively** via `diff_gate`; the high-CV stages and *all* shares are **reported, never gated**. Shares are a
  **zero-sum simplex** — a stage's share rises when *another* stage speeds up, so a share can never be a gate
  (§Theorization 2); only the absolute per-stage floors gate.
- **Footprint → a per-component allocation.** Per-model resident bytes as first-class `run_metrics`, plus their
  shares (LLM ≈ 75%). Because footprint is **deterministic**, an **absolute** per-component allocation check is
  admissible — a regression means "a config change enlarged this component," not a workload guess. This is the
  one place the STUB's "allowance you check actuals against" survives literally.
- **Throughput → unchanged.** It is already two first-class metrics (primary vs enrichment-complete); it is not a
  stage decomposition and needs none.
- **"Budget" = A + B + C, not an SLO table.** The relative per-stage floors (A, finer ratchet) + the reported
  shares/allocation (B) + the existing runtime deadline (C) together *are* the budget the STUB wanted. No absolute
  per-stage **latency** target is introduced (forbidden — 640 §C-6).
- **Everything projects.** Every new metric flows run → `summary` → `release.measured` →
  `project_release_to_perf_baselines` → floors, regenerated from the same release the relevance ratchet uses.
  Nothing is hand-typed; the anti-fork discipline (623/640) is the admissibility test (a number that cannot be
  projected because it is a workload guess is exactly the one the rule rejects).

## D-4 — What the problem does NOT require (scoped out deliberately, named so they are not silently dropped)

- **No absolute per-stage latency SLO/allowance table.** The core scope decision (640 §C-6). Its intent is
  redirected to A+B+C (D-3), not dropped.
- **No cost model / predictive budget** (the CE ≈ k·candidates function, §Theorization 5-C). A reach that **648**
  will generate the data for; not built here.
- **No new runtime cap or degradation policy.** The design *reads* actuals against the existing deadline (C);
  adding new deadlines or a drop-a-stage-on-overrun behavior is a distinct *runtime* problem, not this
  measurement one.
- **No per-install / user-facing attribution surface.** A different audience (the user's own machine) and a
  different delivery (a diagnostic surface), noted in §Theorization 5-D; not folded in here.
- **No per-resource (RAM/VRAM/disk) footprint matrix, no VRAM-contention policy.** Footprint here is per-*model*
  resident bytes; splitting by scarce *resource* and deciding a sharing policy stays with the inference-runtime
  register (its single-tenant GPU policy already owns that).
- **No sub-stage / per-lever standing attribution.** The flag-on/off A/B (640 §C-2 / 636 method) already
  attributes per lever *on demand*; making it a *standing* line is where **648**'s pool-size work lives, not a
  641-style capture change here.

## D-5 — Verification shape (when built — not now)

The decomposition's correctness is checkable as a pure function, no live stack: promote a synthetic run → assert
the per-stage metrics **plus `unattributed` sum to the total** (the closure invariant); feed a within-envelope run
→ PASS; move a low-CV stage outside its band → REGRESSED; move a high-CV stage → **report-only, not gated**; shrink
a footprint component → the absolute allocation check catches it; raise a *share* while all absolutes hold → **no
gate fires** (proves shares are report-only). The live half reuses the relevance/perf ratchet's own pattern. The
one measurement owed **before** the build (the §F honest limit): read `stage_timing_stats` CV across the corpus
cohort to decide which non-CE stages are gate-worthy vs report-only — the design is eval-first by construction.

## D-6 — Second-pass verification & refinements (2026-07-01)

An adversarial re-read of the settled design against `main` confirmed the shape but corrected one soundness detail
and hardened two anchors:

- **Closure must be computed per-query, then aggregated — not by differencing aggregate p50s (soundness fix).**
  My first statement of D-2(b) (`unattributed = total_p50 − Σ stage_p50`) is **statistically unsound**: the median
  is not additive, so `p50(total) ≠ Σ p50(stage)` and the difference can even go negative. The correct — and
  computable — form: each response already carries a per-query total (`raw_responses[i]["tookMs"]`,
  `run.py:494`) **and** its per-query stage times (same response's `searchTrace`, `provenance.py:105-112`), so the
  remainder is **per-query** `unattributed_q = tookMs_q − Σ stage_ms_q`, and only *then* is a median/mean taken
  over the per-query remainders. The raw material exists in one record; no new capture is needed for the closure,
  only the per-query derivation.
- **"Unattributed" is scoped to the engine's own reported time (`tookMs`), not the harness wall-clock.** Because
  `tookMs` is the worker-reported query duration, the remainder it closes is *engine-internal* dark latency
  (serialization, intra-worker glue) — the harness's dispatch/transport overhead is correctly excluded as a
  non-engine cost. This sharpens what the closure means: it guards the *engine's* accounting, not the benchmark's.
- **The runtime-cap anchor (C) holds on the *search* path, not only RAG.** The search-path cross-encoder is
  deadline-bounded at `GrpcSearchService.java:459` (`deadlineMs > 0 ? deadlineMs : 200`), independent of the
  RAG-context reranker (`RagContextOps.java:1024`). So "the reranker runtime deadline is the one admissible
  absolute latency bound" is true of the live query path, not merely the RAG sub-path — the anchor is real.

None of these changes the design; they make the closure invariant sound and the C-anchor concrete. The
decomposition-closes-to-`tookMs` invariant (D-5) should be stated over per-query remainders accordingly.

## D-7 — External practice (2026-07-01): the closure invariant is Critical Path Tracing

A **narrow** external pass (deliberately *not* the reranker-optimization / LLM-latency literature — that is 648's,
and 640 §E already scanned the perf axis) was run on one question the design left open: **is per-stage latency
attribution that "closes to the whole" an established practice, and is it sound when stages overlap?** It is —
and the design independently arrived at the standard shape:

- **The `unattributed` remainder is Google's "unaccounted" node.** *Critical Path Tracing* (CPT) — the
  industry-standard latency-attribution technique — attaches to each component a **"self" node** (time incurred
  internally, not in a child) and, crucially, an explicit **"unaccounted" node** for the wall-clock the
  instrumented spans do not cover (serialization, scheduling, transport) — because "the reported critical path is
  *less than* observed wall time." That is exactly this design's per-stage value + `unattributed = tookMs − Σ
  stages`. The vocabulary to adopt in the build is therefore the established one: **per-stage *self time* + an
  *unaccounted* remainder**. CPT also names the failure this closure prevents — a **"blind spot": a large block of
  time attributed to a single subcomponent** — which is precisely the CE-at-82% dark-latency risk (§Theorization
  3). Source: Google, *Distributed Latency Profiling through Critical Path Tracing*, ACM Queue 20(1), 2022
  ([queue.acm.org/detail.cfm?id=3526967](https://queue.acm.org/detail.cfm?id=3526967)). (Concept + terminology
  only — **no code or text copied**; nothing enters the repo, so the license-and-notices lane is not implicated.)
- **Parallel stages must be closed by critical path, not summation — a concrete guardrail for the deferred
  per-leg work.** CPT sums only along the *critical (longest) dependency chain*; parallel siblings contribute
  only their longest member to wall-clock. This bears directly on granularity: at the **stage** level (this
  design's scope) the stages are *sequential* and `retrieval_ms` is already captured as the **wall-clock of the
  whole retrieval phase, which itself fans the legs out concurrently** (`HybridSearchOps.java:405-416` —
  text/vector legs run on a virtual-thread executor via `supplyAsync … join`, so `retrieval_ms ≈ max(legs)`, not
  `Σ legs`). So `tookMs − Σ stages` is **sound at stage granularity**. But a finer **per-leg** decomposition (BM25
  vs dense vs SPLADE — deferred to the reach / 648) **must not sum the legs**; it must take the critical path (the
  leg that gated the phase), or the parts will exceed the whole. Recording this now converts a latent trap into a
  named constraint for whoever picks up sub-stage attribution.

Net: the external pass **confirmed** the design and supplied its canonical name and one guardrail; it surfaced no
reason to change the shape. This is the right amount of research for a *measurement/governance* design over an
already-instrumented engine — the actively-churning literature (reranker efficiency, quantization, LLM-latency
reduction) governs the *optimize* band (648), not this one.

---

# Reach & principle (recognized, not built)

## The principle this is an instance of

**A guarded aggregate is not a guarded decomposition.** Guarding a total — or its single dominant term — leaves the
*composition* unguarded: a non-dominant stage can regress, a compensating trade can shift the cost structure, and
whatever is *unattributed* can grow, all while the guarded aggregate reads flat. The positive form is
**decompose-and-close-the-remainder**: the companion to 640's *measure-and-ratchet* — *every aggregate worth
guarding is worth decomposing into parts that **sum to the whole**, so nothing hides in a non-dominant term or an
unowned gap.* The "close the remainder" clause is the sharp part: a decomposition that does not account for 100% of
the whole simply relocates the blind spot instead of removing it.

## It conforms to an existing seam — do not fork it

This is **not a new mechanism.** It is another instance of the **canonical-record + governed-projection seam**
(553 SearchTrace / 559 sibling-record / 622 telemetry / 623 benchmark release), realized through the **metric-family
registry + shared ratchet kernel** 640 already built. The completed latency/footprint decomposition is *more
governed projections of the one record*; the per-stage floors are *more metrics projected from the release*. Any
design that grew a parallel "attribution store" or a hand-maintained allowance table would be the exact fork
(623/640) the seam exists to prevent — and, for latency allowances, would also smuggle back the absolute SLO the
no-users rule forbids.

The decompose-and-close principle is, in fact, **already instantiated once in the codebase** — which is the
strongest evidence it is a real seam and not a fresh abstraction: tempdoc 636's **Staged Recall Accounting**
partitions *every* recall failure into exactly one of leg-miss / cascade-leak / judge-rank (the projection
`staged_recall_accounting.json` the leak gate reads), a decomposition that closes to the whole by construction.
The perf decomposition is the **same shape on the latency/footprint axis** — so the design should mirror 636's
partition-the-whole discipline, not invent a parallel one. (The same "single canonical authority, governed
consumers, no fork" shape also recurs at the FE state layer — the 595 single-`SystemHealthVerdict` kernel that
tempdoc 663 extends to the brain surface — evidence the seam is system-wide, not eval-only.)

## Candidate scope beyond this problem (named, deliberately not built now)

The principle is **already violated elsewhere**, which is the evidence it is real, not speculative:

- **Relevance** — the ratchet gates the **nDCG@10 aggregate** only; there is no per-facet / per-intent
  decomposition, so a single query-class can rot while the mean holds. A live violation.
- **Recall-leak** — the decomposition *artifact* exists (636's Staged Recall Accounting, above), **but the gate
  guards only the rolled-up `leak_rate`** — the closed partition is recorded, not ratcheted per-bucket. A *partial*
  violation, and the sharpest one: the parts are already measured, only the guard is aggregate.
- **Performance** — the instance this tempdoc fixes (latency decomposed to one term; footprint to one sum).
- **Extraction quality** (623's named sibling; the largest measured bottleneck) — no record yet, so no
  decomposition either; the principle says when it gets one, it should be **per-format**, closing to the whole.
- **LLM-generation** — TTFT / e2e / tokens-sec are already separate terms (a partial decomposition), but there is
  no "total generation cost closes to the sum of its phases" remainder.

So **≥2 shipped ratchets guard an aggregate without a closed decomposition** — strong evidence the shape
generalizes. The discipline (per assignment): **record the principle and its candidate scope; do not build the
generalized structure now.** The present problem requires the *performance* decomposition; it does not require a
generic decomposition framework, and building one before a second family needs it would be the premature
abstraction the AHA/YAGNI rules warn against.

## The secondary principle (sequencing)

**Attribution before allocation before optimization.** The 640→647→648 chain is the *interrogate-results*
discipline made structural: *you may not allocate a budget you have not attributed, nor optimize a cost you have
not allocated.* It is why 648 declares it "must not precede 647." Stated as an invariant rather than a
per-tempdoc note, it is reusable for the next measure→optimize pair (e.g. indexing throughput, extraction cost):
the standing decomposition is the prerequisite artifact, and optimizing ahead of it is the guesswork the
discipline forbids.

---

# Pre-implementation confidence pass (2026-07-01)

> A read-only investigation against `main` + three real on-disk eval runs
> (`scripts/jseval/tmp/eval-results/20260630T*_scifact/`) to reduce surprises **before** implementation — no
> feature code. It resolved the design's load-bearing assumptions and surfaced two concrete implementation
> touchpoints. Findings by uncertainty:

- **U1 — closure soundness — RESOLVED (structural guarantee, not just empirical).** The per-query total the eval
  reads (`tookMs`) is `KnowledgeSearchEngine.totalSearchMs = nanoTime() − doSearchStartNs`
  (`KnowledgeSearchEngine.java:754`), whose own comment states it is *"Total Head-side search time: Worker RPC +
  CE RPC + all overhead — the true client-visible search latency."* Every stage (worker-side retrieval/fusion/
  chunk-merge, **and the head-side cross-encoder RPC**) is a **sequential sub-interval of that outer window**, so
  `Σ stages ≤ tookMs` **by construction** and the remainder is non-negative — it *is* CPT's "unaccounted" node
  (gRPC transport + head glue). This is a stronger guarantee than the p50-difference I first wrote (which was
  unsound — §D-6). Real data agrees directionally: scifact p50 total 179 ms vs retrieval 4 + CE 153 = 157 ms →
  ~22 ms (~12%) positive remainder.
- **U1 caveat — a capture touchpoint.** The per-query files persist `tookMs` **but not** per-query stage timing
  (only the aggregated `stage_timing_stats`), so a *per-query* remainder is not computable from today's artifacts.
  It **is** computable at aggregation time: each API response carries both `tookMs` and its `searchTrace` stages,
  so `provenance.aggregate_run_evidence` (which already holds both) can compute the per-query
  `unaccounted_q = tookMs_q − Σ stage_ms_q` and fold it into `stage_timing_stats` — no new capture, just the
  per-query derivation done where both values already coexist.
- **U2 — non-CE stage signal — CLARIFIED.** In 300 scifact queries, `chunkMergeApplied = 0` and `hybridFallback =
  0`; only `retrieval_ms` + `cross_encoder_ms` populate. The other stages are **query/config-conditional, not
  dead** (chunk-merge fires only on chunk collapse, branch-fusion only on a recall-complete splice, lambdamart
  only when loaded — all absent for scifact's short docs). Consequence for the design: the stage set is **dynamic
  per query**, so only **always-present stages (retrieval + CE) are gate candidates**; conditional stages are
  **report-only-when-present**, and the remainder absorbs the rest. Honest limit: a clean **gate-vs-report CV**
  ranking could **not** be settled from these three runs — they are not a calibration cohort (two were run
  **CE-off**, and their configs differ: primary-throughput 76→112 docs/s). That decision correctly belongs to a
  `calibrate` cohort during implementation, exactly where the design already routes it — so it is a *delegated*
  decision, not an unknown.
- **U3 — generic `aggregate_metrics` consumers — RESOLVED GREEN.** Every consumer reads by **named key**
  (`relevance_gate.py:70` → `nDCG@10`; `history.py:125-129` → the five quality keys; `perf_gate.py:72` →
  `ce_p50_ms`; `release.py:231` carries the whole map through a projection). None iterates keys generically as
  "quality," and `ce_p50_ms` **already** lives in `aggregate_metrics` as a non-quality key with nothing breaking —
  the existence proof that adding `retrieval_p50_ms` &c. is safe.
- **U4 — calibrate admits new per-mode metrics — RESOLVED GREEN.** `calibrate.py:208-215` reads the
  metric-families registry (`calibrated_per_mode_metrics()`) on top of its legacy tuples — "the registry is the
  single source of truth; this loop adds whatever it declares." So a new stage metric marked `calibrate=True` in
  the registry is calibrated **with no `calibrate.py` change**. The one production touchpoint is `run.py`
  promoting the stage p50s into `aggregate_metrics`, mirroring the existing CE promotion (`run.py:266-276`).
- **U5 — footprint per-component — RESOLVED (low risk).** `derive_resident_model_bytes` (`perf_gate.py:202-221`)
  already resolves and sums per-file model bytes; returning the per-component dict is a trivial refactor. Known
  conditionality (not a blocker): the LLM component is `0` on AI-offline eval runs (the scifact runs carry no
  `resident_bytes`), so per-component **shares** are only meaningful on AI-online runs — as the footprint design
  already states.

**The W5 fallback (a fresh live eval) was deliberately not run.** U1 is resolved *structurally* (stronger than any
single run), and U2's residual (gate-vs-report CV) needs a **calibration cohort** (repeated cohort-identical runs),
which a single ad-hoc run would not produce either — that is `calibrate`'s job in the build phase. A live run now
would add no confidence the structural + artifact evidence does not already give.

## Net implementation touchpoints this pass pinned

1. `run.py` (`:266-276`) — promote each **present** stage's p50 into `aggregate_metrics` (mirror the CE promotion).
2. `provenance.py` (`aggregate_run_evidence` / `_aggregate_stage_timing`) — compute the per-query
   `unaccounted = tookMs − Σ stages` and fold an `unaccounted_ms` stat + per-stage shares into `stage_timing_stats`.
3. `metric_families.py` — register the stage-latency metrics (`calibrate=True` for retrieval + CE; others
   report-only) and the per-component footprint keys; `calibrate`/`perf_gate`/`release` then inherit them.
4. `perf_gate.py` (`derive_resident_model_bytes`, `_READERS`, `project_*`) — return the footprint component dict and
   read the new keys.
5. Reader-vs-contract check: the existing `scripts/jseval/tests/{test_perf_gate,test_provenance,test_metric_families,test_calibrate}.py`
   already assert the behaviors relied on here — extend them, don't fork.

## Confidence rating

**8 / 10.** The design's central risk (closure soundness) turned from an assumption into a *structural guarantee*
grounded in the code; the reuse path (registry → calibrate → release → gate) is confirmed to admit the new metrics
with minimal, well-located production edits; and no generic-consumer landmine exists. The residual −2: (a) the
**gate-vs-report CV** for individual stages is genuinely unsettleable until a calibration cohort is run in the
build phase (delegated, but still open); and (b) the per-query `unaccounted` derivation, while clearly feasible in
`provenance`, has not yet been exercised end-to-end, so the exact fold-in shape (and any negative-remainder edge
from clock-granularity rounding at sub-ms stages) is unproven until coded.

---

# Implementation (2026-07-01) — SHIPPED

The design is implemented as **pure `jseval` Python** (no engine/Java, no wire-contract regen, no UI, and no
public benchmark/README column — attribution stays internal, so the public-claims lane is untouched). All edits
mirror the CE-metric precedent 640 established.

**What was built** (files under `scripts/jseval/jseval/`):
- **`provenance.py` `_aggregate_stage_timing`** — closes the decomposition: per-query `unaccounted = took_ms − Σ
  present stage ms` (sub-millisecond rounding negatives clamped to 0 and counted via `clamped_negative_count`),
  folded into `stage_timing_stats` as an `unaccounted_ms` stat, plus a per-entry `share` (from summed totals, so
  the parts close to 1 by construction). The remainder + shares are **report-only** — never gated (a share is a
  zero-sum simplex). Backward-compatible: absent `took_ms` ⇒ neither field is emitted.
- **`run.py`** — promotes the two always-present latency stages (`cross_encoder_ms` → `ce_p50_ms`, `retrieval_ms`
  → `retrieval_p50_ms`) into `aggregate_metrics` so they flow / calibrate / project like a quality metric.
- **`metric_families.py`** — `PERF_LATENCY` extended with `retrieval_p50_ms` (`calibrate=True`, band `1.5` — wider
  than CE's `1.25` because the ~4 ms stage is relatively noisier; the calibrate envelope ±2σ self-adapts on top);
  `PERF_FOOTPRINT` extended with the per-component keys (`embed_/splade_/reranker_/ner_/llm_bytes`, band `1.05`,
  deterministic → an absolute per-component allocation check is admissible).
- **`perf_gate.py`** — `_promoted_stage_p50` helper + `retrieval_p50_ms` reader; `derive_resident_component_bytes`
  returns the per-model dict (`derive_resident_model_bytes` now sums it — contract unchanged); `_current_value`
  serves component keys; `_BEST_EFFORT`/component-key sets are sourced from the registry (single source of truth).
- **`release.py` `_measured_for_mode`** — merges the per-component footprint into `run_metrics` so it projects
  like `resident_bytes`. (Stage-latency metrics need no `release.py` change — `metrics` copies `aggregate_metrics`
  verbatim.)

**Final gate-vs-report outcome** (the confidence pass's open item): retrieval **gates** (relatively, via the
envelope band with a deliberately wide `1.5` fixed fallback — the cross-run values 4/4/5 ms sit well inside, so it
will not flap; if a future calibration cohort shows otherwise, dropping its band to report-only is a one-line
registry change). `unaccounted_ms` + shares + the query-conditional stages (chunk-merge/branch-fusion/lambdamart)
stay **report-only** in `stage_timing_stats`. Per-component footprint **gates** relatively, best-effort (skips
when a model path is unresolvable; `llm_bytes` absent on AI-offline runs).

**Validation** (tiers appropriate to eval-infra with no user-visible surface — browser validation is N/A):
- **Unit — green:** 1075 passed across the full `jseval` suite; 12 new tests cover the closure invariant
  (remainder non-negative, shares sum ≈ 1, rounding-clamp, backward-compat), retrieval promotion + relative
  gating, and the per-component footprint split/sum/best-effort/bloat-gate. (2 pre-existing failures in
  `test_correction_probe` are an unrelated missing-data-file issue — reproduces on `main`, logged to the inbox.)
- **Real wire-data — green:** running the real `extract_query_evidence → aggregate_run_evidence` path against the
  committed live search-response fixture produced a closing decomposition (`chunk_merge 0.119 + cross_encoder
  0.1905 + unaccounted 0.6905 = 1.0` exactly), proving the trace-parsing + closure works on real trace shapes
  (incl. the case where a stage's `ms` is absent → absorbed into the remainder, not double-counted).
- **Projection/gate wiring — green:** a synthesized release carrying `retrieval_p50_ms` + component metrics
  projects them all into gate-able floors; `_BEST_EFFORT` covers every footprint key so unresolved ones skip.

**Deliberately not run: a full live backend cohort.** It would add the per-stage within-cohort CV (to *re-tune*
retrieval's band) but cannot be produced by a single run — it needs a repeated cohort-identical `calibrate` pass,
a build-phase step that also recomposes the release baseline so the new metrics begin to gate. The current design
is safe without it (envelope + wide fallback), and the `justsearch-dev` coordination surface was not available in
this session. Recomposing the release + a `calibrate` cohort is the one follow-up before the new floors gate live.

---

# Future directions & practicality (2026-07-01) — research pass

> A pure research/ideation pass (doc-only, **nothing implemented**) on what the attribution substrate *enables*,
> grounded in a scan of three adjacent landscapes. Public alpha, **no real users yet** — so ideas are ranked by
> value × cost and honestly caveated, not committed. The goal is to bank the options, not pick one.

## What the outside world does — and where JustSearch differs

- **RAG/LLM observability** (LangSmith, Laminar, Galileo, Arize Phoenix, Braintrust) converges on three UX moves:
  a **waterfall span timeline**, **side-by-side experiment diffs**, and **per-engine retrieval latency** (vector /
  keyword / hybrid), at negligible overhead. But they are **cloud SaaS** — you ship traces to a service. JustSearch
  already owns the same raw material locally (the `SearchTrace` + now a *closed* decomposition), so its distinctive
  play is a **local-first** version of that legibility that never leaves the machine.
  ([RAG observability 2025](https://www.getmaxim.ai/articles/top-5-rag-observability-platforms-in-2025/) ·
  [Galileo 2026](https://galileo.ai/blog/best-rag-observability-tools))
- **Perf-regression root-cause** (Dynatrace RCA, RCAEval @ WWW'25, MRCA) names the pain precisely: *"by the time a
  regression is detected, multiple changes have landed and isolating the responsible component is expensive."* The
  fix is **component-level attribution** — which this tempdoc's decomposition now supplies as a standing artifact.
  ([RCAEval, WWW'25](https://arxiv.org/pdf/2412.17015))
- **Local-LLM resource UX** (Ollama, LM Studio) shows the user-actionable fact is **fit-vs-spill**, not raw bytes:
  a model that fits VRAM runs ~10× faster than one spilling to system RAM (up to ~30× worse). So the practical
  end-user framing of footprint is *"do your models fit?"*, and the per-component split is its input.
  ([LM Studio VRAM](https://localllm.in/blog/lm-studio-vram-requirements-for-local-llms))

## Ideas the substrate unlocks (none committed; ranked value × cost)

| # | Idea | Audience | Reuses (already exists) | Value | Cost |
|---|---|---|---|---|---|
| 1 | **"Which stage moved" regression attribution** — when the aggregate perf-gate fires, name the stage whose value/share shifted most ("engine +12% *because* CE p50 +18 ms; unaccounted flat"). | dev | `compare_runs.compare_pipeline_timing` (already diffs per-stage timing) + the new decomposition | **high** | med |
| 2 | **Revive the `jseval search` per-query pipeline display** — `ops.py:159` reads the **retired** `pipelineExecution`, so the timing panel never renders; re-point it at the unified trace + show the share split. | dev | the unified `SearchTrace` stages; `_aggregate_stage_timing` shares | med | **low** (also fixes dead code) |
| 3 | **Local perf-attribution report** — a per-run ASCII "waterfall" / table (`CE 82% ▏ retrieval 6% ▏ unaccounted 12%`), the RAG-observability waterfall done offline. Realizes the "legibility" the design captured but only stores as JSON. | dev | `stage_timing_stats` + shares | med | low |
| 4 | **Fit-vs-spill footprint diagnostic** — combine the per-component footprint with `gpu-bridge` VRAM detection to answer *"your models fit VRAM ✓"* vs *"the LLM is spilling → that's why chat is slow."* | **user** (future) | per-component footprint + `gpu-bridge` `VramDetector` | **high** (user-perceptible) | high |
| 5 | **Trend the decomposition over commits** — not just per-run: track each stage's share + the unaccounted remainder across history, to catch *composition* drift (dark-latency creep) the aggregate ratchet misses. | dev | `history.py` | med | med |
| 6 | **Tail-latency decomposition (report-only)** — decompose p95/p99, not just the gated p50; the RAG-SLO world cares about the tail (TTFT/p99) the user actually feels. | dev | per-query stage data | med | med |
| 7 | **Gate the unaccounted remainder** once its CV is characterized — so dark-latency creep is *caught*, not merely visible. (The design's own deferred follow-up.) | dev | the ratchet + calibrate | med | low |
| 8 | **MCP resource: search-performance breakdown** — expose the decomposition so an external agent (Claude Code / Cursor) can reason about search cost, matching JustSearch's MCP-first direction. | agent | the MCP server + the decomposition | med | high |
| 9 | **Per-leg retrieval attribution** (BM25 / dense / SPLADE) — the "per-engine latency" the RAG tools show; the first input 648 needs. **Must use critical-path (max of the parallel legs), not a sum** (§D-7). | dev | the leg executors; the trace | med | high (feeds 648) |

## Practicality — the honest read

- **Where the ROI actually is today: developer regression-diagnosis (#1) and guiding 648.** For a single-user
  desktop app at ~180 ms/query, shaving latency has limited *user-perceptible* payoff — so attribution's real
  value is **preventing/diagnosing regressions** and **deciding what to optimize**, not chasing milliseconds.
- **The unaccounted remainder earns its keep in two ways:** it tells you when *not* to chase a cost (the ~22 ms of
  gRPC/glue is not worth a rewrite) and it catches *invisible* creep — both are "avoid wasted effort" wins, which
  is exactly what a pre-users project should optimize for.
- **The one axis a user *would* feel is memory, and it's addressable now-ish (#4).** Footprint is deterministic and
  the fit-vs-spill question maps to a real ~10× user-visible speed cliff. This is the strongest *user-facing*
  evolution — but it belongs to the per-install/diagnostic-surface track (out of this tempdoc's D-4 scope), so it
  is recorded as a hand-off, not folded in.
- **Cheapest credible next steps** if any are ever picked up: #2 (dead-code fix + instant legibility) and #3/#7
  (small, reuse the record). #1 is the highest-value single feature. Everything else is genuinely deferrable.

## Simplify / polish (internal, minor)

- `perf_gate._current_value` re-derives the component dict once **per** pinned component metric (≤5× redundant file
  `stat`s per gate run) — a one-line memo would remove it. Negligible today; noted for hygiene.
- The five footprint component bands are all `1.05` — could collapse to one shared constant if a sixth is ever
  added. Not worth churning now.

**Net:** the substrate is small but genuinely generative — it turns "the engine got slower/heavier" from a blob
into an attributable, diffable, trend-able signal, and it is the prerequisite the 648 optimization band was waiting
on. No idea here is urgent; all are viable; #1, #2, #4 are the ones most worth a future session.

---

# Long-term design for the remaining work (2026-07-01)

> The tempdoc's core (measure → attribute) is shipped. This settles the *right long-term shape* for the
> **remaining work** — the Future-Directions surfaces above + the ops follow-up — scope-matched, general (not
> implementation-level), **nothing built**. The deliberate conclusion is that the correct design is mostly a
> *handoff + one small extension*, not a new subsystem.

## The remaining work has one latent design question, not nine

The nine ideas mostly reduce to a single question: **how should the captured attribution be *consumed*** —
displayed, diffed, exported — across audiences (dev CLI, the app UI, MCP, a bug-report bundle)? The failure mode
if each surface answers it alone is **representation drift**: one surface recomputes "share" from p50s, another
from sums; one defines "unaccounted" as `took − Σstages`, another forgets the clamp — the exact fork class the
codebase fights (tempdoc 553). So the design question is not "build nine features," it is "**what is the one seam
every consumer reads, so the decomposition is defined once.**"

## The design: attribution is a materialized projection; consumers read it, they never re-derive

- **The substrate already exists (647 put it there).** The per-query decomposition is *materialized* into the
  canonical record — `stage_timing_stats` carries each stage's value **and** its `share` **and** the
  `unaccounted_ms` remainder; the per-component footprint comes from the single
  `perf_gate.derive_resident_component_bytes`. So a consumer *reads* the decomposition; it does not recompute it.
- **The one thing still inlined** is the per-query decomposition *arithmetic* (`unaccounted = took − Σ stages`;
  `share = stage / took`), which lives inside `provenance._aggregate_stage_timing` because today there is exactly
  one consumer (the eval aggregation). It is the single authority. **When a second consumer needs it per-live-query
  (below), the right move is to extract that arithmetic into one shared pure function — not re-inline it.** That
  extraction is warranted at consumer #2, not now (rule-of-two; building it now would be structure for a case the
  problem does not yet have).

## Ownership split — most of the remaining surfaces are *not* 647's to build

This is the scope-matching decision, and it is decisive:

- **Live-run inspectability** (the `jseval search` CLI panel #2, the app UI, an MCP resource #8, REST diagnostics,
  a redacted bug bundle) belongs to **tempdoc 658** ("retrieval inspectability and diagnostic bundle"), which
  explicitly owns *"operational inspectability for one local run"* across *UI, MCP responses, REST diagnostics, and
  bug-report bundles*, and explicitly mandates *"do not invent a second authority… project from existing
  surfaces"* — and already lists performance attribution as a prior owner to read. **So 647 hands the materialized
  decomposition to 658 as an input; it must not grow a parallel presentation layer.** The performance breakdown is
  one panel in 658's inspector, projected from the record, next to the score/fusion-leg, reason-code, and
  readiness panels 658 already scopes.
- **Eval-gate regression attribution** (#1 "which stage moved") is the one consumer that *is* 647/eval-domain (it
  answers a question about an eval *run pair*, not a live query). Its correct home is an extension of the existing
  `compare_runs` seam — a query-latency-decomposition comparison mirroring `compare_pipeline_timing`, reusing
  `_compare_field`, reading the **materialized** `stage_timing_stats` shares/values. Not a new differ.
- **Measurement extensions** (#5 trend, #6 tail, #7 gate-unaccounted, #9 per-leg) stay in the 647/648 measurement
  domain — incremental additions to the same record + ratchet, not new surfaces.
- **The user fit-vs-spill diagnostic** (#4) is a per-install *product* surface (does the model stack fit VRAM); it
  consumes the per-component footprint but belongs to the app/658-adjacent inspectability track, not here.

## What the present problem requires — and what it does not

- **Requires now: nothing new built.** The materialized decomposition is the substrate; exactly one consumer (the
  gate) exists; the ops follow-up (recompose the release so the floors gate live) is operational, not structural.
- **Does not require: a 647-owned "attribution presentation / inspector" subsystem.** Building it now would be
  *both* premature (one consumer) *and* a fork of 658's inspectability ownership. The correct long-term design is
  therefore small by outcome, not by target: **materialize once (done) + hand off to 658 + one `compare_runs`
  extension for the eval gate**, with the shared-arithmetic extraction deferred to the moment consumer #2 lands.

---

# Reach & principle (of the remaining-work design)

## The principle: *materialize the projection once; consumers read, never re-derive*

This is the **consumption-side face** of the canonical-record + governed-projection seam (553/559/623/640) the rest
of the tempdoc already conforms to. Where the earlier design principle was *"a guarded aggregate is not a guarded
decomposition"* (produce the decomposition), this is its dual on the read side: *once a decomposition is
materialized into the canonical record, every surface that shows it is a **governed reader** of that one
definition; recomputing it in a second place is the representation-drift fork.* 658 states the same rule in its own
words ("don't invent a second authority; project from existing surfaces"), and 663 is the same shape at the FE
state layer (one `SystemHealthVerdict`, consumers don't re-derive) — three independent arrivals at one seam, which
is the evidence it is real.

## Candidate scope + where it is (not yet) violated

- **Already conformed:** shares/unaccounted are materialized; footprint components come from one `derive` function;
  the gate reads, it does not recompute.
- **The one live violation *risk*, named not built:** the first live-run inspector (658) that re-implements
  `unaccounted`/`share` per query instead of calling the shared arithmetic. The remedy is the deferred extraction
  above — cheap, and it should be a precondition of 658 wiring in the performance panel, not an afterthought.
- **Where else the shape applies:** the leak decomposition (636 staged-recall-accounting is materialized but its
  *consumers* should read it, not re-bucket), and any future relevance per-facet decomposition. Recorded as
  candidate scope; **not** generalized now — only the performance case has a concrete second consumer on the
  horizon (658).

---

# Confidence pass — remaining work (2026-07-01)

> A read-only validation pass over the **remaining** work — (A) activate the shipped floors (recompose + calibrate),
> (B) the "which-stage-moved" `jseval compare` extension — before it is implemented. No feature code. It validated
> the shipped code against **real** on-disk artifacts and pinned B's shape + the activation recipe.

- **Footprint resolution — GREEN on real data.** `perf_gate.derive_resident_component_bytes` run against a **real**
  `manifest.json` + this machine's real `models/` dir returned `{splade, embed, reranker}` bytes summing exactly to
  `derive_resident_model_bytes` (1673 MB). `ner_bytes` was correctly **skipped** (the real manifest's
  `ner_model_path` is empty and there is no `models/onnx/ner`) — proving the best-effort skip works on real data
  (no crash, no data-error); `llm_bytes` absent on the AI-offline run, as designed.
- **Promotion path — GREEN on real data.** Simulating the `run.py` promotion loop over the **real**
  `stage_timing_stats` of all three on-disk hybrid runs set `retrieval_p50_ms` (5/4/4 ms) every time (and
  `ce_p50_ms` when CE ran). **Retrieval is present in every hybrid run**, so the "missing → gate data-error (exit
  2)" edge the confidence pass flagged is **unreachable in practice** — retrieval is the core search phase, always
  traced.
- **Feature B shape — pinned (low risk).** `jseval compare` (`analysis.py:320 cmd_compare`) already invokes +
  prints + JSON-emits `compare_pipeline_timing`; B is a patterned extension: a `compare_stage_decomposition` that
  reads `per_mode.<mode>.stage_timing_stats.<stage>.p50` through the existing `_compare_field`
  (`compare_runs.py:185` → `{a,b,delta,ratio,regressed}`), with "which moved" = the stage of largest `|delta|`,
  printed beside the pipeline diff. No new differ, no new authority.
- **Activation mechanics — a known recipe, not a discovery.** `perf-gate` carries `--update-baseline`
  (`gates.py:118`); activation is the standard **re-pin-from-a-green-release** flow 640 already documents (fresh
  run with the new code → recompose `release.v1.json` → `calibrate` the cohort → `perf-gate` green).
- **Deliberately not run: a live end-to-end eval.** The status ports (33221–33230) were free, but a single run
  would only touch the *mechanical* end-to-end write (low-risk, unit-covered) — it could **not** resolve the one
  genuine residual, retrieval's **within-cohort CV**, which needs a *repeated* `calibrate` cohort by definition
  (an activation-time step). Against that low marginal value sits real cost (worktree Java-backend build + ~10 min
  + un-coordinated contention, the `justsearch-dev` MCP being unavailable this session). So the end-to-end write +
  the CV re-tune are recorded as the residual, both resolved at activation.

**Confidence for the remaining work: 8.5 / 10.** Both feature-B (patterned, low-risk) and the shipped code's
real-data behavior (footprint + promotion GREEN) are well understood; the activation recipe is standard. The −1.5:
the fully-fresh end-to-end summary write and the within-cohort CV re-tune are unproven until an activation run
happens on a coordinated dev stack — inherent to the ops step, not a code risk.

---

# Feature B SHIPPED (2026-07-01) — which-stage-moved regression attribution

The one 647-owned remaining feature is implemented (pure `jseval` Python; a **developer CLI** surface — `jseval
compare` — so no UI/browser, consistent with the tempdoc's scope). It reads the **materialized** decomposition and
never re-derives it, conforming to the "consumers read, never re-derive" principle.

**What was built:**
- `compare_runs.compare_stage_decomposition(summary_a, summary_b, mode)` — diffs each stage's p50 from
  `per_mode.<mode>.stage_timing_stats` (retrieval / cross-encoder / the `unaccounted_ms` remainder / any present
  stage) via the existing `_compare_field`, and attributes the change to the **primary mover** (the stage of
  largest `|Δp50|`). The overall latency delta is read from `latency_stats.p50_ms` and reported **separately** —
  the stage deltas are *not* claimed to sum to it (a median is not additive), so the attribution is an honest
  "where to look," not a false additive split. Returns `{}` when the mode is unresolved or neither run has stage
  data (mirrors `compare_pipeline_timing`).
- `commands/analysis.py` — wired into `cmd_compare` at the same three sites as the pipeline diff (compute + JSON
  `stage_decomposition` key + text via a new `_print_stage_decomposition_comparison`). ASCII-only output (Windows
  console safe).

**Validation** (no browser — developer CLI):
- **Unit — green:** 7 new tests in `tests/test_compare.py` (`TestStageDecomposition`) cover the diff, the
  primary-mover attribution, the regression flag at the band, missing-stage-as-zero, empty/unresolved-mode → `{}`,
  old-format tolerance (no `unaccounted_ms`), and identical-runs → no mover. Full `jseval` suite otherwise green
  (the 2 pre-existing unrelated `test_correction_probe` failures persist).
- **Real-data CLI — green:** `jseval compare` on two on-disk scifact runs rendered both text and `--json`
  correctly. It surfaced a nice real-world confirmation of the honest framing: comparing a CE-off run to a CE-on
  run showed the cross-encoder as the primary mover (+153 ms) while the *total* p50 moved −59 ms (the runs differ
  in more than CE) — the tool correctly reports both **without** implying they reconcile.

**Scope note:** this closes the sole 647-owned implementable item. Live-run inspectability surfaces (CLI `search`
panel, UI, MCP, bug bundle) remain **658's**; floor **activation** (recompose + `calibrate`) remains an ops step
needing a coordinated dev stack; the decomposition-arithmetic extraction stays deferred to consumer #2 (658).

---

# Deferred items taken over (2026-07-01) — `jseval perf-report` + a cleanup

Picking the deferred items back up, the split is by **validatability**: with no dev stack, no browser, and the
`justsearch-dev` MCP unavailable, the user-visible / agent-facing / live-backend items **cannot be
built-and-validated** now (UI #4, MCP #8, live `search` display #2, floor activation, gate-unaccounted #7 — all
need a running app or a `calibrate` cohort). What **is** cleanly buildable + validatable — the flagship
*legibility* deliverable — was built:

- **`jseval perf-report <run>` (new command)** — a per-run performance-attribution readout: the **query-latency
  waterfall** (each stage's p50 + **p95 tail** + the *materialized* `share`, plus the `unaccounted_ms` remainder,
  sorted by p50) and the **resident-footprint per-component allocation** (embed / SPLADE / reranker / NER / LLM, MB
  + display shares + ASCII bars). Built as a pure `build_perf_report(summary, manifest, mode)` + a thin printer
  (`commands/analysis.py`), reusing `perf_gate.derive_resident_component_bytes` (single authority) and reading the
  materialized decomposition — **it never re-derives** the `share`/`unaccounted` (conforms to the "consumers read,
  never re-derive" seam). This is dev **eval-tooling** (not 658's *live-run* inspectability), so it does not fork
  658. Subsumes the deferred **#6 (tail)** by showing p95 beside p50. ASCII-only (Windows-console safe).
- **`perf_gate._current_value` cleanup** — the per-component footprint dict is now derived **once** per gate run
  (in `evaluate` / `project_run_to_perf_baselines`) and threaded in, instead of re-derived per footprint metric.
  Behavior-preserving.

**Validation** (no browser — developer CLI):
- **Unit — green:** 6 new tests in `tests/test_perf_report.py` cover the latency waterfall (p50-desc sort, p95
  tail, shares **read** verbatim not recomputed), old-format grace (no share), unresolved-mode → no latency, and
  the footprint allocation (display shares close to 1, total == Σ components, LLM-online-only, none-without-manifest).
- **Command-surface lock:** adding a `jseval` command requires regenerating the committed inventory
  (`python -m jseval.commands.inventory --write`; tempdoc 645's projection-not-fork lock) — the
  `test_command_surface` suite fails loudly until it is. Done: `perf-report` is registered under group `analysis`.
- **Real-data CLI — green:** `jseval perf-report <on-disk scifact run>` rendered correctly in **text and `--json`**
  — latency (CE 153 ms / p95 169, retrieval 4 ms / p95 5) and the footprint allocation (reranker 600 MB 35.9% /
  embed 599 MB 35.8% / SPLADE 475 MB 28.4%, summing to 1673 MB; NER + LLM correctly skipped on this AI-offline
  run). The old-format run's latency shares show `--` (the graceful pre-materialization path); new-format shares
  are covered by the unit test.

**Still deferred (infra/ownership-blocked, not code-blocked):** UI #4 / MCP #8 / live-`search` #2 (need app +
browser; 658's inspectability domain); floor activation + gate-unaccounted #7 (need a `calibrate` cohort on a
coordinated dev stack); per-leg #9 (backend trace + critical-path; feeds 648); the arithmetic extraction (no live
per-query consumer yet — `perf-report` reads the aggregate record, so it is not one); trend #5 (needs a SQLite
`runs`-table column migration for low-value retrieval trending). These reactivate the moment a dev stack / 658's
inspector lands.

---

# Trend #5 taken over (2026-07-01) — dark-latency creep on the trend path + perf-report polish

Re-picking the deferred set: the SQLite `runs`-column migration turned out to be a **clean, idempotent, already-
established pattern** (`_migrate_runs_perf_columns`, tempdoc 640 R3), so **trend #5 is buildable + validatable
without a dev stack** — and, reframed, its headline value is not retrieval trending but **trending the
`unaccounted_ms` remainder = the dark-latency-creep detection the tempdoc's core motivation named**, delivered on
the *trend* path (no `calibrate` cohort, unlike *gating* it — #7 stays deferred).

**What was built** (mirrors the 640-R3 perf-column pattern exactly):
- `history.py` — added `retrieval_p50_ms` + `unaccounted_p50_ms` to `_RUNS_TABLE_SCHEMA`, `_PERF_HISTORY_COLUMNS`
  (so the idempotent `ALTER TABLE` migration adds them to pre-existing DBs), `_TREND_METRIC`
  (both **lower-is-better**), the `append_run` INSERT, and the `get_history` SELECT.
- `run.py` — extended the `perf_metrics` dict the caller passes: `retrieval_p50_ms` from `aggregate_metrics`
  (promoted), `unaccounted_p50_ms` from `stage_timing_stats.unaccounted_ms.p50` — **trended without being
  promoted/gated**, so the report-only design of the remainder is preserved.
- `cmd_trend --metric` help lists the two new families.
- **Perf-report polish** (from the self-review): `cmd_perf_report` now raises a friendly `ClickException` on a
  missing `summary.json` (was a raw traceback); `--mode` default kept as `_first_mode` (the codebase convention).

**Validation** (no browser — developer CLI + local SQLite):
- **Unit — green:** `test_history.py` extended (new columns persist, the idempotent migration adds them to a
  legacy DB with legacy rows NULL, and a rising `unaccounted_p50_ms` is flagged as a lower-is-better **regression**
  while flat CE stays `ok` — the creep signal, metric-scoped); `test_perf_report.py` gained a `CliRunner` smoke
  (text + `--json`) + the friendly-error test. Full `jseval` suite otherwise green (the 2 pre-existing unrelated
  `test_correction_probe` failures persist).
- **Real-data CLI — green:** `jseval perf-report` still renders (no polish regression); `jseval trend --help`
  lists `unaccounted_p50_ms` / `retrieval_p50_ms` (live trending accrues as evals record runs).

**Now genuinely remaining (all infra/ownership-blocked, none code-blocked):** UI #4 / MCP #8 / live-`search` #2
(app + browser; 658); floor activation + **gating** the unaccounted remainder #7 (a `calibrate` cohort on a dev
stack — the *trend* above already gives the observability without it); per-leg #9 (backend + critical-path; 648);
the arithmetic extraction (no live per-query consumer; 658). The `jseval` eval-tooling surface of tempdoc 647 is
now complete.

---

# Live activation validation (2026-07-01) — end-to-end materialization proven; recompose is a reviewed ops step

Proceeding on activation: a **fresh-worktree backend build + a real scifact eval** (`jseval run --start-backend
--clean --pipeline --ce --embedding --splade --dataset scifact --modes hybrid`, AI-offline) succeeded and
**closes the confidence-pass "live materialization" residual (U1)** — the shipped code produces the new-format
record on a real backend:
- `aggregate_metrics` carries both `ce_p50_ms` (182) **and** `retrieval_p50_ms` (5) — the promotion fires live.
- `stage_timing_stats` carries the `unaccounted_ms` remainder (p50 23, p95 30, share 0.113); **the shares sum to
  exactly 1.0000** (CE 0.8637 + unaccounted 0.113 + retrieval 0.0233) — the closure holds on real live data.
- **`jseval perf-report`** on the run renders the full decomposition with shares + bars for the first time on live
  data: `cross_encoder_ms 86.4% | unaccounted_ms 11.3% | retrieval_ms 2.3%` + the footprint allocation.
- **`jseval perf-gate`** against the current release baseline returns **exit 0** (`ce_p50_ms`/throughput/footprint
  all `ok`) — the new-format summary causes **no spurious regression**. (`retrieval_p50_ms` + footprint components
  are not gated *yet* because the current `release.v1.json` predates this code.)

**Full activation (the release recompose) is deliberately NOT done here — it is a reviewed ops decision, not an
autonomous one.** Recomposing `release.v1.json` re-pins **every** family across the **whole 5-corpus cohort**,
including the **user-accepted post-636 relevance (nDCG) floors** — so a partial/worktree recompose would (a)
overwrite those user-accepted quality floors with worktree-run values, (b) shrink the cohort if run on one corpus,
and (c) capture **ONNX-only footprint** (this env has no `native-bin`/llama-server, so AI-online `llm_bytes` cannot
be measured). The correct activation is a coordinated full-cohort + AI-online recompose + `calibrate`, reviewed
before it replaces the shared baseline. This pass **de-risked it fully** (the pipeline works, materialization is
correct, the gate stays green); the recompose itself remains the owner-reviewed follow-up.

(One incidental observation logged: `jseval perf-gate`'s **default** `--baselines` path resolves one level too deep
— `jseval/jseval/perf-ratchet-baselines.v1.json` — so it needs an explicit `--baselines`; pre-existing, unrelated
to 647.)

## Recompose HALTED — this environment's model stack is incomplete (would degrade the shared baseline)

Cleared to recompose, due-diligence on the target found a **concrete reason not to**: this worktree's model stack
is **not faithful to production**, so recomposing the shared `release.v1.json` from runs here would *degrade* it:
- **No NER model** (`models/onnx/ner/` has only tokenizer configs, no `model.onnx`) → the run realizes
  `[dense, reranker, splade]` with `ner_model_path: ""`, and its footprint is **1.67 GB vs the release's 2.02 GB**.
  Pinning the lower, NER-missing footprint would make a future **full-stack** run **false-regress** (2.02/1.67 =
  1.21 > the 1.05 band), and the reduced engine set would trip the tempdoc-644 engine-homogeneity guard.
- **No llama-server** (`native-bin/` empty) → AI-offline only → no `llm_bytes`, so the ~75%-of-footprint LLM
  component still cannot be pinned.
- Recompose also re-pins the **user-accepted relevance floors** onto this reduced-stack cohort (scifact nDCG
  0.7572 → 0.7535 — within tolerance, but on a different cohort).

So a recompose here would replace a faithful, user-accepted production baseline with a **reduced-stack, incomplete-
footprint** one — a net-negative change that would cause future false-regressions. Per "look at the target before
overwriting; surface contradictions," the recompose was **not executed**. A correct activation needs a **faithful
full-stack environment** (NER model + `llama-server` present) — a real dev/prod machine, not this worktree. What
this pass *did* achieve stands: live materialization proven, `perf-report` renders the full decomposition on live
data, and `perf-gate` stays green — the recompose is fully de-risked and awaits a faithful-stack run.

## Recompose diagnosis (2026-07-01, follow-up) — the release baseline is STALE (dead commit + evolved corpora)

Cleared to fetch the missing model, the NER `model.onnx` was downloaded **version-correct from the repo's own
registry** (`eliasjustus/justsearch-releases` `models-v1`, sha-256 verified) — *not* the archive, *not* a rebase
(the models are Install-AI download artifacts, not git-tracked). A re-run confirmed it fixed the stack faithfully:
NER now loads (`ner_bytes` 129 MB → footprint 1802 MB; nDCG 0.7535 → **0.7584**, within 0.0012 of the release), and
the run is **`embed_gpu: true`** (full fp16/GPU stack — the earlier "reduced stack" fear was wrong).

But comparing cohort keys surfaced the **real** blocker, bigger than 647: the current `release.v1.json` is anchored
to a **dead commit** — its `cohort.git_sha` is `bef184e333…`, a **pre-public-squash commit absent from this repo's
history**; today's HEAD is `25cdd035…`. And its corpus set has **evolved**: the release names
`mixed/courtlistener-200` + `mixed/enron-qa`, but the corpora present today are `legal-clerc-200` + `miracl-de-2k`
+ `miracl-fr-2k` (courtlistener/enron are gone/renamed). So **no run at today's HEAD can ever be cohort-identical to
the shipped release** — the baseline needs re-anchoring regardless of 647.

**Therefore the recompose is NOT a 647 perf-activation step — it is an eval-corpus / release-integrity decision.**
Properly recomposing requires (a) deciding the **current canonical benchmark corpus cohort** (the set changed —
this is tempdocs 664/666's domain, "eval-corpus integrity"), (b) a full run at the current HEAD, (c) re-pinning
**every** family's floors incl. the user-accepted relevance floors. That is an owner/design decision, not an
autonomous act. 647's contribution is complete and de-risked: the perf metrics materialize correctly and will
gate the moment the release is re-anchored on the current cohort — which should happen as part of the corpus-
integrity work, not here. (Logged to the inbox: the shipped ratchet baseline references a dead commit + retired
corpora — a latent staleness affecting the relevance/perf gates broadly, beyond 647.)

---

# Handoff — open items for a continuing agent (2026-07-01)

**One-line state:** 647's `jseval` surface is shipped + live-validated; the only remaining step — *activating* the
new floors via a release recompose — is blocked on a stale baseline and belongs to the eval-corpus-integrity work
(664/666), not 647. Files touched (all `scripts/jseval/**` + this tempdoc): `provenance.py`, `run.py`,
`metric_families.py`, `perf_gate.py`, `release.py`, `commands/analysis.py`, `compare_runs.py`, `history.py`,
`commands/inventory.generated.json`, and their tests (`test_{provenance,perf_gate,metric_families,compare,history,perf_report}.py`).

## Unverified assumptions / deferred checks
1. **Retrieval's within-cohort CV was never formally measured via a `calibrate` cohort.** Retrieval gates on the
   envelope band with a wide `1.5` fixed fallback; cross-run p50 was stable (4–5 ms) but a calibrate cohort should
   confirm it will not flap once it actually gates. Do this at activation; if noisy, drop its band to report-only
   (one-line `metric_families` change).
2. **`llm_bytes` (the ~75% LLM footprint component) is unit-tested but never live-validated** — this environment
   has no `llama-server`. Validate on an AI-online run.
3. **Live footprint (1802 MB, GPU/fp16 + NER) is not reconciled byte-for-byte to the stale release's 1930 MB.** The
   gap is attributed to the dead-commit / evolved-corpus difference, not investigated further (moot once recomposed).
4. **Per-query `unaccounted` closure is proven at STAGE granularity (sequential stages).** A finer PER-LEG
   decomposition must use critical-path (max), not summation (§D-7) — a constraint recorded for the deferred 648
   work, not built here.

## Stale docs / pre-existing issues discovered (all in the observations inbox)
- `release.v1.json` (the relevance+perf ratchet baseline) is anchored to a dead commit + retired corpora — affects
  the gates broadly, beyond 647.
- `jseval perf-gate`'s default `--baselines` path resolves one level too deep (needs an explicit `--baselines`).
- `compare_runs.compare_pipeline_timing` has no unit test.
- Two pre-existing `test_correction_probe` failures (missing `correction-eval-queries.v1.json`, reproduces on main).

## Follow-up work (owned elsewhere per the settled design)
- **Release re-anchor/recompose** on the current canonical cohort → activates 647's floors (664/666 domain).
- **Gate the `unaccounted` remainder** (#7) — needs the calibrate cohort.
- **UI fit-vs-spill** (#4), **MCP resource** (#8), **live-`search` display revival** (#2), **per-leg attribution**
  (#9) → tempdocs 658 / 648.
- **Extract the per-query decomposition arithmetic** into a shared function when 658's live inspector becomes the
  second consumer of the decomposition (until then it stays inline in `provenance`, one consumer).

## Local-environment note (intentionally NOT committed)
The NER `model.onnx` (135 MB) was downloaded from the model registry
(`modules/ui/src/main/resources/ai/model-registry.v2.json` → `eliasjustus/justsearch-releases` `models-v1`,
sha-256 verified) into `models/onnx/ner/`. Like the other model `.onnx`, it is an **Install-AI download artifact,
not git-tracked** — a fresh environment obtains models via the Install-AI flow / registry, not the repo.
