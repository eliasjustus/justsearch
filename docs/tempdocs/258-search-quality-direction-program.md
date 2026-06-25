---
title: "258: Search Quality Direction Program"
type: tempdoc
status: done
created: 2026-03-05
updated: 2026-03-08
---

> NOTE: Noncanonical doc (strategy). May drift.

# 258: Search Quality Direction Program

## Purpose

Define the next direction for JustSearch to maximize average search quality
across real desktop use cases, using findings from:

- `245-search-quality-strategy.md`
- `250-pipeline-routing-architecture.md`
- `251-realistic-eval-framework.md`
- `252-ingestion-quality.md`
- `253-model-quality-improvements.md`
- `255-eval-pipeline-research.md`
- `256-component-activation-model.md`

This doc is the current prioritization and execution contract. It reduces
strategic drift.

---

## Current Program State (2026-03-08)

The program state changed materially after tempdoc 259, after the corrected
Phase 5f long-doc reruns, the harder Phase 2d known-item lane, the first
small mixed-corpus runs, and the first context-quality lane runs.

What is now true:

1. **Eval-control-plane hardening is largely implemented.**
   - comparability semantics, readiness/capacity reporting, backend-only
     lifecycle convergence, and warn-on-regression search-eval wiring are in
     place
   - the BEIR lane now has an explicit metric contract, strict dense / SPLADE /
     LambdaMART readiness gates, and `bench-suite.v2` as the canonical
     downstream artifact
2. **The remaining blocker is no longer "can we trust the eval stack at all?"**
   - the main remaining gap is representative lane coverage, not basic eval
     infrastructure trustworthiness
3. **The long-doc lane is operational and now has corrected routing-grade
   evidence.**
   - corrected title-free `courtlistener` favors BM25 over Hybrid on ranking
     quality (`0.889` vs `0.871` nDCG), while Hybrid still improves recall
   - `legal_case_reports` remains objective-sensitive: Hybrid improves recall
     materially, but not enough to make long-doc routing universally
     Hybrid-first
   - CE is catastrophic on long documents and GPL-trained LambdaMART has no
     useful standalone effect on the measured corpora
4. **The known-item lane is operational and routing-discriminative.**
   - the original TF-IDF lane saturated, but the harder title-free refinding
     lane now separates BM25 from Hybrid
   - on harder `courtlistener` refinding, BM25 keeps perfect recall while
     Hybrid improves ranking quality (`0.597` vs `0.548` nDCG, `0.477` vs
     `0.398` MRR, `0.180` vs `0.000` P@1)
5. **The mixed-corpus lane is operational for the first small lexical +
   Hybrid + SPLADE mix.**
   - shared `SciFact + FiQA` corpus runs now exist for lexical, Hybrid, and
     SPLADE
   - heterogeneous noise is still modest in the small mix; domain/task effects
     still matter, but the first alternate-sparse result is now clear
   - the invalid `gpu-r5` all-zero run was traced to a wait-contract bug, and
     the corrected `gpu-r6` rerun completed successfully
   - on the small shared corpus, SPLADE now beats both mixed BM25 and mixed
     Hybrid for both sources
6. **The context-quality lane is operational in a first, decision-useful
   form.**
   - a real `/api/knowledge/search`-based harness now measures whether the
     retrieved results expose relevant supporting excerpts before generation
   - on title-free `courtlistener`, Hybrid surfaces materially better evidence
     excerpts than BM25 even while BM25 wins document ranking
   - on harder refinding, BM25 keeps a small context-quality edge because it
     retrieves the target document for every query
   - when datasets provide explicit fact or phrase annotations, the harness now
     also reports evidence-unit coverage alongside the older term-coverage
     proxy

Program implication:

1. tempdoc 259 should now be treated as a completed enabling stream
2. tempdoc 251 remains strategically central, but now as the source of the
   next missing lanes rather than as an unimplemented eval-infrastructure wish
   list
3. routing is now explicitly task-dependent rather than intuition-driven
4. the main open search-quality gaps have narrowed to:
   - broader mixed-corpus coverage beyond the first small SPLADE lane
   - stronger mixed-corpus context-quality diagnostics beyond the current
     query-term proxy
   - broader context-quality diagnostics beyond the current lightweight
     excerpt/evidence-coverage family
   - ingestion-quality uplift once representative lanes are stable enough
5. **The mixed-corpus SPLADE lane is no longer blocked only by throughput.**
   - the current worker path is deliberately narrow (`concurrency: 1`,
     `POLL_BATCH_SIZE = 1`, single indexing thread)
   - SPLADE encoding currently runs inline per document rather than as a
     batched stage
   - backend-only GPU SPLADE is now implemented and has completed a valid small
     mixed-corpus run (`gpu-r6`)
   - the throughput question remains architectural, but it no longer blocks the
     first quality interpretation
   - the next decision is therefore:
     - whether the strong small-mix SPLADE result holds on broader
       heterogeneous mixtures
     - what throughput posture we actually want for future benchmark and
       product-oriented sparse lanes
   - the dedicated throughput architecture analysis now lives in tempdoc `266`

---

## Executive Thesis

JustSearch is no longer blocked by missing retrieval components. It is blocked
by:

1. evaluation mismatch
2. regime mismatch
3. ingestion bottlenecks
4. missing context-quality diagnostics
5. too many low-leverage experiments not gated by realistic quality metrics

The highest-value direction remains:

**Eval control plane -> adaptive routing -> ingestion quality -> targeted model bets.**

Not the other way around.

---

## Why Progress Felt Slow

### 1) Optimization target was unstable

Work optimized multiple proxies (BEIR nDCG, component scores, architecture
purity) without one stable north-star metric stack for desktop search quality.

### 2) Many local wins were globally neutral

245 showed this clearly:

- fusion tuning was often near-null
- some model swaps regressed across datasets
- synthetic-data LambdaMART consistently hurt

### 3) Real-world bottlenecks were under-measured

251 and 255 exposed the real gaps:

- long-doc quality behavior differs from short-doc benchmarks
- known-item refinding was underrepresented
- tool/search outputs can be damaged by intermediate context handling even when
  retrieval itself is good

### 4) Architecture work matured but activation lagged

250 and 256 delivered composability and provenance. The remaining task is to
operationalize those capabilities against quality outcomes.

---

## North-Star Objective Function

Use one quality objective, reported per build and per major change:

1. **Known-item lane (primary):** `MRR@10`, `P@1`
2. **Topical lane (secondary):** `nDCG@10`, `Recall@10`
3. **Context-quality lane (required):** evidence presence/coverage for expected
   facts in retrieved context
4. **Robustness lane (guardrail):** degradation/fallback rates and
   extraction/context-loss incidents

Suggested aggregate:

- 50% known-item lane
- 25% topical lane
- 15% context-quality lane
- 10% robustness lane

No major pipeline/model decision should ship unless the aggregate improves or
is neutral with clear secondary benefits.

---

## Strategic Direction

## D1. Evaluation as control plane (first priority)

Use 251 as the representative-lane expansion plan on top of the hardened
control plane delivered by 259:

- long-document benchmark lane
- known-item lane
- mixed-corpus lane
- context-quality diagnostics lane
- existing BEIR lane retained for external comparability

Rule: no quality claim without lane evidence.

Operational clarification:

1. the base search-eval governance layer is no longer the main missing piece
2. the primary missing piece is turning the representative lanes into repeatable,
   decision-grade signals
3. the known-item lane now exists and is decision-useful
4. the context-quality lane now exists in a first form and now accepts explicit
   evidence annotations when datasets provide them, but broader expansion is
   still open

## D2. Adaptive routing by regime (second priority)

Use 256 pipeline composability to stop one-size-fits-all routing:

- sparse-first when corpus/profile is long-document heavy and lexical dominance
  is proven
- Hybrid for short/medium docs where dense helps
- keep CE out of Hybrid by default until a lane shows net gain

Route selection should be explicit, measurable, and revisited by eval, not
intuition.

Context-quality diagnostics are a hard input to routing decisions:

- if retrieval context quality is low, do not treat generation failures as
  model failures
- route/query reformulation changes should be judged first by context-quality
  uplift, then final-answer uplift
- when explicit evidence annotations exist, prefer the stronger evidence-unit
  metrics over query-term-only coverage when interpreting results

### Current routing evidence (2026-03-08)

Corrected long-doc topical lane:

- title-free `courtlistener`: BM25 = `0.889`, Hybrid = `0.871`
- Hybrid still improves recall (`0.990` vs `0.962`)
- corrected `courtlistener` topical/ranking delta is in BM25's favor

Harder known-item lane:

- title-free `courtlistener` refinding no longer saturates
- BM25: Recall@10 = `1.000`, nDCG@10 = `0.548`, MRR = `0.398`, P@1 = `0.000`
- Hybrid: Recall@10 = `0.960`, nDCG@10 = `0.597`, MRR = `0.477`, P@1 = `0.180`
- routing is therefore task-dependent: BM25 wins the topical/passage long-doc
  lane, while Hybrid wins the harder refinding lane on ranking quality

Small mixed-corpus lane:

- shared `SciFact + FiQA` corpus does not show a major BM25 or Hybrid collapse
- the first mixed-corpus result is not "heterogeneous noise breaks retrieval";
  it is "mixed-corpus effects are modest, and domain/task effects still
  dominate"
- the first mixed-corpus alternate-sparse result is now available:
  - the `gpu-r5` all-zero run was an eval wait bug, not a CUDA failure
  - the corrected `gpu-r6` rerun shows SPLADE beating both mixed BM25 and mixed
    Hybrid on both sources
  - SciFact mixed:
    - BM25 `0.657`
    - Hybrid `0.650`
    - SPLADE `0.709`
  - FiQA mixed:
    - BM25 `0.231`
    - Hybrid `0.257`
    - SPLADE `0.360`
- interpretation:
  - the small mixed-corpus lane is no longer just "BM25 and Hybrid survive
    noise reasonably well"
  - it now says the first alternate sparse model is materially stronger than
    either baseline on the shared corpus
  - this is the first result in the mixed-corpus stream that points toward a
    genuinely better retrieval family rather than just modest tradeoffs

Context-quality lane:

- on title-free `courtlistener`, Hybrid materially improves relevant excerpt
  availability and excerpt coverage even while BM25 wins document ranking
- on the harder refinding lane, BM25 keeps a small context-quality edge because
  it retrieves the target document for every query
- on the shared `SciFact + FiQA` corpus, the first mixed-corpus context-quality
  run now exists for BM25, Hybrid, and SPLADE
  - SciFact:
    - SPLADE gives the strongest relevant-doc hit rate and the best
      thresholded context-hit behavior
    - Hybrid still keeps the best mean best relevant excerpt coverage
  - FiQA:
    - SPLADE gives the strongest relevant-doc hit rate and the best thresholded
      context-hit behavior
    - Hybrid and even BM25 keep higher average excerpt coverage than SPLADE
- interpretation:
  - the small mixed-corpus context story now supports the ranking result, but
    only partially
  - SPLADE looks strongest at retrieving a relevant result with usable excerpt
    evidence somewhere in top-K
  - SPLADE does **not** yet look uniformly best at maximizing excerpt-depth
    coverage inside the relevant document
  - the SciFact side is no longer proxy-only:
    - a native-annotation follow-up now exists for the same small mix
    - SPLADE keeps the strongest evidence-unit hit thresholds
    - Hybrid keeps a tiny edge on mean evidence-unit coverage
  - so the proxy story is now directionally confirmed for SciFact, but still
    remains source-asymmetric because FiQA is proxy-only
  - this is stronger than the earlier proxy-only reading, but it is still not
    enough for a universal mixed-corpus `SPLADE-first` policy

Medium mixed-corpus lane (4 datasets, 2026-03-09):

- SciFact + FiQA + NFCorpus + ArguAna (~67K docs), 12/12 retrieval cells
- SPLADE leads on all 4 datasets (nDCG: 0.692, 0.359, 0.300, 0.375)
- cross-corpus dilution is modest: worst case NFCorpus -0.051 vs isolated
- context-quality (proxy + annotated): SPLADE > hybrid > lexical consistently
- annotation confirms proxy on SciFact (evidenceUnitHit≥0.25: SPLADE 0.503
  vs hybrid 0.477 vs lexical 0.473)

Large mixed-corpus lane (6 datasets, 2026-03-09):

- adds courtlistener_notitle (6K-token legal opinions) and stackoverflow
  (materialized LoCoV1), total ~85K docs, 18/18 retrieval cells
- SPLADE leads on 5/6 sources: SciFact 0.692, FiQA 0.359, NFCorpus 0.300,
  ArguAna 0.374, stackoverflow 0.584
- **SPLADE catastrophically fails on courtlistener (0.083)**: 512-token
  tokenizer truncation loses most of 6K-token documents. This is the
  strongest evidence for document-length-aware routing.
- Hybrid wins courtlistener (0.827 vs lexical 0.734): chunk-merge RRF
  captures content BM25 term saturation misses
- context-quality (proxy, SciFact+FiQA): SPLADE > hybrid > lexical,
  consistent across all corpus sizes
- context-quality (annotated, SciFact): annotation confirms proxy pattern
  again (evidenceUnitHit≥0.25: SPLADE 0.503 vs hybrid 0.483 vs lexical 0.477)

Interpretation across all three corpus sizes:

- SPLADE superiority on short-doc collections is robust across small → medium
  → large corpus growth (no degradation with noise)
- Long-document collections are a hard SPLADE failure mode, not just "modest
  gap" — 0.083 vs 0.734 is an order of magnitude
- Hybrid is the best mode for long-doc heterogeneous corpora
- The routing signal is now clear: document length is the primary routing
  dimension, not corpus size or domain

### Current routing policy

1. CE must be gated by document length; disable when median doc length is
   greater than roughly 4K tokens.
2. Do **not** promote a universal BM25-first long-doc rule.
3. For courtlistener-like / moderate long-doc corpora:
   - BM25-first remains the better default for topical / passage-style
     retrieval
   - Hybrid is now the better default candidate for harder refinding-style
     known-item queries when ranking quality matters more than absolute recall
4. For extreme-length corpora, routing remains objective-sensitive.
5. Mixed-corpus routing is now settled for the short-doc regime: SPLADE-first
   is the clear winner across 3 corpus sizes (small/medium/large), 5 short-doc
   datasets, and both proxy and annotated context-quality measures. For
   long-doc collections (>4K tokens), SPLADE must be disabled — catastrophic
   failure observed on courtlistener (nDCG 0.083 vs lexical 0.734).
6. Context-quality should be treated as a first-class routing signal, not a
   post hoc explanation layer.
7. LambdaMART should not ship until trained on non-synthetic data.

### Throughput design note for the mixed SPLADE lane

The current mixed SPLADE execution path should still be interpreted as
responsiveness-first by product default, even though the benchmark lane now has
one valid backend-only GPU result:

- one claimed job at a time
- one indexing-loop thread
- synchronous inline SPLADE encoding
- benchmark-oriented GPU SPLADE enabled only for the dedicated backend-only run

That means low apparent hardware usage is currently compatible with "working as
designed." Any move toward GPU SPLADE, wider queue claims, or batched document
encoding should be treated as a deliberate architecture choice and measured as
such, not assumed to be an obvious free win.

Implementation-ready recommendation order (2026-03-08):

1. **Use backend-only GPU SPLADE as the benchmark posture for this lane.**
   - This is no longer theoretical; it produced the first valid mixed-corpus
     SPLADE result in `gpu-r6`.
   - The lane should keep an explicit SPLADE GPU memory budget instead of
     relying on the worker default (`256 MB`), which is conservative relative
     to the current ~532 MB model artifact.

2. **If GPU SPLADE is still too slow, batch SPLADE before widening the whole
   indexing loop.**
   - `SpladeEncoder.encodeBatch(...)` already exists.
   - The best first implementation seam is SPLADE-specific batching
     (`SpladeBackfillOps` or a deferred sparse-encoding phase), not immediate
     general loop parallelization.
   - This is compatible with the current eval control plane because the
     hardened harness already fails SPLADE runs unless `splade_ready` is true;
     a deferred batched sparse-encoding path still fits that contract.
   - This keeps the change localized to sparse encoding rather than rewriting
     the worker's core pacing model.

3. **Treat wider indexing-loop width / claim batch size as an explicit
   architecture experiment, not the default next step.**
   - The current worker is deliberately single-width (`concurrency: 1`,
     `POLL_BATCH_SIZE = 1`, one indexing thread) because the product favors
     responsiveness and breath-holding.
   - ONNX Runtime's CPU threading model already parallelizes intra-op work by
     default, so increasing outer loop width can easily become
     oversubscription rather than free throughput.
   - This option should therefore stay behind a stronger justification than
     "the benchmark run looked too idle."

4. **Keep the current behavior as the default product posture unless the
   benchmark lane proves otherwise.**
   - The right split is:
     - product default: responsiveness-first worker behavior
     - benchmark lane: optional backend-only GPU SPLADE, and only then
       carefully scoped batching if needed

## D3. Ingestion quality uplift (third priority)

252 quick wins remain high leverage because they improve every retriever:

- header/footer deduplication
- structure-aware chunk boundaries
- table-aware serialization where feasible

Only then consider heavier sidecar paths behind measured gain gates.

## D4. Model experiments as bounded bets (fourth priority)

253 findings still imply strict discipline:

- do not promote model swaps from single-dataset wins
- require multi-lane, multi-dataset improvement
- keep failed paths explicitly closed to avoid repeated churn

---

## Stop-Doing List

Until D1-D3 are stable, deprioritize:

1. fusion micro-tuning as a primary quality lever
2. broad model swaps without lane-level acceptance criteria
3. architecture refactors not tied to measurable quality movement
4. BEIR-only decision making for desktop-quality goals

---

## Decision Gates

Apply these gates to all major quality proposals:

1. measurement gate: measured on at least known-item + long-doc lanes
2. net-quality gate: aggregate objective improves by a meaningful margin
3. regression gate: no severe regressions on any mandatory lane
4. operational gate: cost/latency/reliability impact acceptable
5. rollback gate: fast disable path exists

If any gate fails, do not promote.

---

## Program Order (Recommended)

## Phase A1 (implemented): harden the eval control plane

- search-eval comparability hardening
- strict/advisory non-comparable policy wiring
- readiness/capacity reporting
- backend-only lifecycle convergence for search eval
- warn-on-regression search-eval CI maturity
- shared test safety nets + first decomposition slices

## Phase A2 (in progress): add representative evaluation lanes

- finalize known-item, long-doc, mixed-corpus, and context-quality lane
  definitions
- wire repeatable reporting for those lanes on top of the hardened control
  plane
- add context-quality reporting to every relevant benchmark run and regression
  report
- freeze non-gated model/pipeline experiments until those lanes exist

Status (2026-03-08):

- **Long-doc lane: operational.** 7-config factorial complete on 2 datasets.
  Corrected title-free reruns now exist for the key `courtlistener`
  BM25-vs-Hybrid comparison.
- **Known-item lane: operational and decision-grade.** The harder title-free
  refinding lane now separates BM25 from Hybrid cleanly.
- **Mixed-corpus lane: fully executed across three corpus sizes.**
  - Small (SciFact + FiQA, ~52K docs): 6/6 retrieval cells
  - Medium (+ NFCorpus + ArguAna, ~67K docs): 12/12 retrieval cells
  - Large (+ courtlistener_notitle + stackoverflow, ~85K docs): 18/18 cells
  - SPLADE leads on all short-doc sources across all sizes. courtlistener
    SPLADE catastrophically fails (0.083 nDCG) — 512-token truncation.
  - Interpretation summary classifies 5/6 sources as SPLADE-led, 1 as
    hybrid-led (courtlistener), with annotation confirmation on SciFact.
- **Context-quality lane: fully executed across medium and large corpora.**
  - Proxy context-quality on SciFact + FiQA for all three modes
  - Annotated context-quality on SciFact (187 queries) with evidence-unit
    metrics
  - Pattern is robust: SPLADE > hybrid > lexical for hit-rate and
    thresholded context-hit; hybrid keeps a marginal edge on excerpt depth
  - Annotation confirms proxy reading on SciFact across both medium and
    large corpora.
- **Mixed-corpus tooling is complete and fully exercised.**
  - `scripts/search/run-mixed-corpus-matrix.mjs`: retrieval execution
  - `scripts/search/run-phase4-mixed-context-quality-suite.mjs`: context eval
  - `scripts/search/summarize-mixed-corpus-findings.mjs`: interpretation
  - All three tools have been validated across real multi-hour runs

## Phase B: activate adaptive routing

- implement initial routing policy using available signals
- evaluate across all lanes
- iterate only with measured uplift

## Phase C: ingestion quick wins

- ship 252 Phase 2 items
- rerun lanes, especially long-doc and known-item

## Phase D: selective model bets

- reopen model experiments only if Phase A-C plateaus
- run candidates behind strict multi-lane gates

---

## Success Criteria

Direction is working when:

1. average scorecard trend improves for 3 consecutive evaluation cycles
2. known-item metrics improve without long-doc collapse
3. context-quality lane improves or stays stable when final-answer quality
   changes
4. major quality decisions are explained by lane evidence, not one-off
   experiments

---

## Notes for Active Tempdocs

- 250 and 256 are enabling infrastructure, not endpoint goals
- 251 is still the strategic center of gravity for decision quality, but 259
  has already implemented much of the shared eval-control-plane hardening
- 252 is likely the highest medium-term quality multiplier
- 253 should remain strictly gate-driven
- 259 should be treated as completed enabling work, not the active frontier
- 260 is a search-pipeline opportunity inventory, not the current execution
  contract
- 255-style attribution checks should be integrated where relevant to avoid
  false diagnosis loops

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Coordination/synthesis doc that explicitly assesses other tempdocs (252, 253, 259, 260) and gives forward direction. The synthesis IS the closure — the doc completed its role of programming the next phase.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

