---
title: "261: Search Quality Program Coordination"
type: tempdoc
status: done
created: 2026-03-06
updated: 2026-03-08
---

> NOTE: Noncanonical coordination doc. May drift. Strategy claims must still be
> verified against canonical docs and code.

# 261: Search Quality Program Coordination

## Purpose

Act as the top-level coordination layer across the active search-quality
tempdocs so implementation work does not fragment across parallel streams.

This doc does not replace the underlying tempdocs. It defines:

- which tempdocs are strategic vs enabling vs inventory
- the current execution frontier
- cross-tempdoc dependency rules
- what work should happen now, next, later, or not at all

---

## Canonical Steering Order

Use the tempdocs in this order when deciding what to do:

1. **258** - program direction and prioritization
2. **251** - active evaluation execution frontier
3. **256** - pipeline activation/composability enabler
4. **252** - ingestion-quality improvement stream
5. **253** - model/retrieval experiment stream
6. **245** - completed BEIR isolation baseline and lessons learned
7. **255** - context-quality / attribution diagnostic lessons
8. **260** - opportunity inventory only

Supporting completed enablers:

- **259** - eval-control-plane hardening
- **257** - backend lifecycle / cold-start convergence
- **254** - dev MCP tooling hardening

---

## Program Snapshot (2026-03-08)

### What is already materially true

1. **Eval infrastructure is no longer the main blocker.**
   Tempdoc 259 completed the major control-plane hardening work:
   comparability policy, readiness/capacity reporting, backend-only lifecycle
   convergence, scheduled warn-on-regression CI, and the first major
   decomposition slices.

2. **Pipeline composability is no longer the main blocker.**
   Tempdoc 256 delivered composable pipeline activation across retrieval,
   reranking, and expansion. The key remaining question is not "can the system
   express the combination?" but "which combinations actually improve quality,
   for which regime?"

3. **The corrected long-doc lane now provides routing-grade evidence.**
   Tempdoc 251 closed the LoCo title-duplication audit and reran the key
   title-free `courtlistener` BM25-vs-Hybrid comparison. CE remains a clear
   failure on long docs, LambdaMART remains low-value, moderate long-doc topical
   retrieval favors BM25, and extreme-length routing remains objective-sensitive.

4. **The harder known-item lane now exists and is routing-discriminative.**
   The first TF-IDF lane saturated, but the harder title-free refinding lane
   now separates BM25 from Hybrid: BM25 keeps perfect recall, Hybrid improves
   ranking quality.

5. **The mixed-corpus lane exists in first form.**
   A shared `SciFact + FiQA` corpus now has isolated-vs-mixed lexical and
   Hybrid measurements. Small heterogeneous noise is modest; domain/task effects
   still dominate. The first alternate-sparse result now exists too:
   backend-only GPU SPLADE beat both mixed BM25 and mixed Hybrid on both
   sources in the corrected `gpu-r6` run.

6. **The context-quality lane exists in first form.**
   Tempdoc 251 now has a real `/api/knowledge/search`-based harness that
   measures whether retrieved results expose relevant supporting excerpts before
   generation. It now also accepts explicit evidence annotations and reports
   evidence-unit coverage when datasets provide them. This is enough to support
   first routing analysis, and it now includes the first small mixed-corpus
   BM25/Hybrid/SPLADE comparison. Broader expansion is still open.

### Program thesis

The program is currently blocked by:

1. remaining representative evaluation coverage
2. regime-aware routing decisions
3. ingestion quality on real long documents

It is **not** primarily blocked by missing retrieval components.

---

## Tempdoc Roles

| Tempdoc | Role | Current coordination stance |
|---|---|---|
| 245 | Baseline evidence | Treat as completed foundational evidence, not the active workstream |
| 251 | Representative eval program | **Primary active execution frontier** |
| 252 | Ingestion quality | Hold behind eval evidence, then activate |
| 253 | Model experiments | Strictly gated, no free-form experimentation |
| 254 | Dev-tool enabler | Completed unless a new blocker appears |
| 255 | Diagnostic lesson | Fold its attribution lessons into future eval/reporting |
| 256 | Activation architecture | Mostly implemented; activate deferred pieces only from 251 evidence |
| 257 | Eval/backend lifecycle enabler | Completed |
| 258 | Strategic steering doc | Top-level program policy |
| 259 | Eval hardening enabler | Completed enabling stream |
| 260 | Pipeline opportunity inventory | Do not treat as the sprint plan |

---

## Current Frontier

The active search-quality frontier is no longer "finish the basic eval stack."
It is:

1. fill the last decision-relevant lane gaps in 251
2. turn those results into explicit routing decisions in 258 / 256
3. only then activate ingestion-quality work in 252

The practical implications of the current frontier are:

1. the LoCo fidelity audit is closed for the key `courtlistener` routing
   comparison
2. the harder known-item lane is no longer missing
3. the first small mixed-corpus lane is no longer missing for lexical,
   Hybrid, and the first SPLADE result
4. the first context-quality lane is no longer missing
5. the next highest-value open work is now:
   - broader mixed-corpus coverage beyond the first small SPLADE lane
   - stronger mixed-corpus context-quality diagnostics beyond the current
     query-term proxy
   - broader context-quality expansion beyond the current lightweight
     term/evidence-unit coverage family
   - ingestion-quality activation once the search-direction picture stabilizes
6. the mixed-corpus SPLADE lane now has an execution-design subproblem:
   - current throughput is governed by a deliberately narrow worker path
   - before promoting any conclusion from wall-clock behavior, the program
     should explicitly decide whether it wants:
     - GPU SPLADE for this lane
     - wider indexing claims / loop width
     - batched SPLADE document encoding
     - or to keep the current responsiveness-first posture
7. backend-only GPU SPLADE is now proven viable in code and on a real run
   - the accepted design is worker embedding disable +
     `onnxruntime.native.path` +
     narrow CUDA/cuDNN dependency preloading inside `SpladeEncoder`
   - a process-wide `PATH` mutation was explicitly rejected because it broke
     DJL tokenizer native loading in the same worker process
   - CUDA activation is no longer the blocker
   - the first fresh GPU suite (`gpu-r5`) exposed a concrete fidelity bug:
     the BEIR wait path accepted a transient early `IDLE` state after the
     built-in help collection, before the mixed corpus had actually enqueued
   - the dedicated throughput architecture analysis now lives in tempdoc `266`
   - the wait contract is now patched to require a corpus-derived indexed-doc
     floor on fresh ingest runs
   - the corrected `gpu-r6` rerun completed successfully and now provides the
     first valid mixed-corpus SPLADE result:
     - SciFact mixed SPLADE `0.709` nDCG vs mixed BM25 `0.657` and mixed
       Hybrid `0.650`
     - FiQA mixed SPLADE `0.360` nDCG vs mixed BM25 `0.231` and mixed
       Hybrid `0.257`

---

## Execution Board

### Now

1. **Mixed-corpus routing picture is now settled for the short-doc regime.**
   (Completed 2026-03-09.) The small → medium → large matrix (36 total cells
   across 3 corpus sizes) consistently shows SPLADE leading on all 5 short-doc
   sources. courtlistener is the sole exception: SPLADE catastrophically fails
   (0.083 nDCG) due to 512-token truncation on 6K-token docs. Hybrid wins
   courtlistener (0.827).

   Remaining open questions:
   - throughput posture for SPLADE in production (still single-width
     backend-only GPU by default)
   - whether SPLADE routing should auto-activate based on corpus profile or
     remain opt-in

2. **Context-quality lane is now fully exercised across all three corpus sizes.**
   (Completed 2026-03-09.) Proxy context-quality on SciFact + FiQA for all
   modes; annotated context-quality on SciFact (187 queries) with
   evidence-unit metrics. SPLADE leads hit-rate and context-hit thresholds;
   hybrid keeps a marginal edge on excerpt depth. Annotation confirms proxy
   on SciFact across both medium and large corpora.

   FiQA remains proxy-only — this is a known limitation, not a blocker.

3. **Keep routing activation evidence-driven.**
   Safe current decisions:
   - CE off for long-doc collections (> ~4K tokens)
   - LambdaMART low-priority
   - moderate long-doc topical routing can default BM25-first
   - harder refinding can legitimately prefer Hybrid
   - short-doc mixed-corpus: SPLADE-first is evidence-supported
   Not safe yet:
   - one universal long-doc default (Hybrid vs BM25 is objective-sensitive)
   - SPLADE auto-activation without length gating

4. **Keep workflow discipline high for long-running eval campaigns.**
   Use the wrapper-first paths, progress files, and duplicate-run guards that
   were added in the workflow-quality workstream.

### Next

1. **Update routing activation in 256 from the current evidence.**
   The mixed-corpus results now provide strong enough evidence for
   document-length-aware routing policy: SPLADE-first for short docs,
   Hybrid or BM25 for long docs (task-dependent).

2. **Start 252 only after the remaining lane gaps are narrow enough.**
   Mixed-corpus coverage is now broad. Context-quality diagnostics are
   operational. The dominant unknowns are now ingestion quality and
   throughput, not retrieval mode ranking.

3. **Decide throughput posture for SPLADE.**
   The quality question is answered (SPLADE wins on short docs). The
   execution question remains: what throughput posture is acceptable for
   production? Keep this separated from routing decisions.

### Later

1. **252 Phase 1: ingestion-quality baseline**
2. **252 Phase 2 quick wins**
   - header/footer deduplication
   - structure-aware chunk boundaries
   - table-aware serialization where feasible
3. **253 deferred model bets**
   Only after representative lanes and routing stabilize.

### Not now

1. Fusion micro-tuning as a primary quality lever
2. Broad model swaps without multi-lane gates
3. Re-opening superseded ideas from 260 unless new evidence justifies it
4. Architecture cleanups that are not tied to measured quality movement

---

## Dependency Rules

### 251 -> 256

Use lane evidence from 251 to decide:

- routing defaults
- component activation by regime
- where Hybrid is allowed or preferred
- where CE stays disabled

Do not promote routing behavior in 256 without lane evidence from 251.

### 251 -> 252

Use 251 to decide whether ingestion work in 252 is worth doing now.

Activation rule:

- if long-doc and context-quality lanes show stable, meaningful room for
  ingestion-driven improvement, start 252
- otherwise keep 252 queued, not active

### 251 -> 253

Use 251 to decide whether 253 model bets are allowed.

Rule:

- no new model bet becomes active unless it addresses a lane gap or quality
  bottleneck that 251 exposed

### 255 -> 251 / 256 / 253

Treat 255 as a standing warning:

- do not diagnose routing or model failures from final answers alone if the
  actual issue is retrieval-context loss
- context-quality checks belong upstream of generation judgment

### 260 -> everything else

Treat 260 as an opportunity inventory only.

Rule:

- nothing from 260 becomes active work unless 258/251 evidence pulls it in

---

## Immediate Decision Gates

Before moving to the next stream, require:

1. a representative-lane result, not just a one-off run
2. a documented interpretation in 251 and 258
3. no unresolved comparability/fidelity question that invalidates the result
4. a clear statement of whether the result changes routing, ingestion priority,
   model priority, or none of the above

---

## Coordination Protocol

When deciding what to do next:

1. check 258 for the current strategic priority
2. check 251 for the current execution frontier and lane gaps
3. use 256 only for activations that 251 evidence now justifies
4. do not jump to 252 or 253 unless the above gates pass
5. use 260 only as a source of candidate ideas after the active frontier is
   exhausted

Expected update triggers:

- **251** results change routing or experiment priorities
- **252** becomes active after eval lanes are stable enough
- **253** re-opens only after explicit gate passage
- **256** deferred activation items become evidence-backed and implementable

---

## Current Coordination Verdict

As of 2026-03-09:

- **Main coordination focus:** tempdoc 251 (eval framework now has full
  retrieval + context-quality coverage across 3 corpus sizes)
- **Main finished enabler:** tempdoc 259
- **Main architecture enabler:** tempdoc 256 (routing activation now has
  strong evidence from mixed-corpus matrix)
- **Main medium-term quality multiplier:** tempdoc 252
- **Main discipline constraint:** tempdoc 253 must remain gate-driven
- **Main anti-drift warning:** do not let tempdoc 260 become the de facto plan

The mixed-corpus and context-quality gaps are now closed for the short-doc
regime. SPLADE-first routing for short-doc collections is evidence-supported
across 5 datasets and 3 corpus sizes. The remaining routing gap is
long-doc routing (BM25-vs-Hybrid is task-dependent, SPLADE must be gated
by document length). The next correct move is to translate this evidence
into routing activation policy (256) and begin ingestion quality work (252).

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 70 days at audit time.

