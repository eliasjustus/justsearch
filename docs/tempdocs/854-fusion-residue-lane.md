---
title: "854 — The fusion residue lane: depth-bounded cascade leak, the shared-constant wrong-gate, and the pool-aware arbitration follow-up (owner-approved 2026-08-19)"
type: tempdocs
status: "CHARTER (2026-08-19) — owner-approved lane ('yes' to the fusion go/no-go, decision 1 of the 2026-08-19 checkpoint); not started. Scope corrected during chartering: 636 'Design v3' as named in D-004's successor note is NOT the work — its core (the recall-complete rerank pool) shipped default-on 2026-06-24 (F-024), and the leaks below were measured WITH it active. This lane owns what remains."
created: 2026-08-19
author: agent session 5acf8350 (Fable orchestration, brain arc) — chartered from three independent measured signals converging in one week
category: search-quality / fusion / engine
related:
  - docs/reference/search-quality-register.md (F-051, F-052, F-024, F-036 §K, D-004, D-005)
  - 636-retrieval-buried-signal-long-documents (the lineage: staged-recall instrument, D-004, recall-complete pool)
  - 748 §G.3 (the campaign that measured the 1k leak)
  - 783 / 784 (fusion-adaptivity territory; the §K attribution study that found the shared constant)
---

# 854 — The fusion residue lane

## Why now (three independent measured arrows, one week)

1. **F-051 (2026-08-19):** on de-miracl-1k-verbose, the legs deliver union@100 **0.70**
   while hybrid surfaces **0.36@100 / 0.12@10** — `leak_rate 0.34`, 17 CASCADE_LEAK + 15
   JUDGE_RANK_LOW of 50 queries. Fusion/judge drops half of what retrieval found. Measured
   WITH the recall-complete pool default-on. (Caveat carried from F-051: `claim_eligible:
   false` synthetic stratum — a capability probe, not a production regression.)
2. **F-052 (2026-08-19):** the CE's delivered-rank ordering term is large and real
   (contaminated-run side-evidence corroborated 802's −0.0418 on legal) — the judge stage
   is worth feeding correctly, and its input window is exactly what the leak starves.
3. **Standing register debt:** the open inbox item "residual hybrid-vs-lexical gap on
   legal-clerc post-F-032 (hybrid 0.5592/0.5609 vs lexical 0.6891) is fusion territory —
   needs its own owner" (2026-07-11, unowned since); F-024's open follow-up ("leg-arbitration
   over-fires when the recall-complete pool is active — pool-aware trigger tightening,
   router Item-1"); and F-036 §K's named-but-unmade one-line fix.

## Scope correction made at chartering (read before designing)

D-004's successor note ("Design v3: feed the CE the union of each leg's top-N") reads as
unbuilt, but F-024 records the recall-complete pool **shipped default-on 2026-06-24**
(`index.hybrid.leg_recall_complete_enabled=true`, per-leg top-N=10). The F-051 leak
happened with it active. The mechanism gap is therefore **depth**, not existence: the pool
guarantees each leg's top-10 into the CE window; gold sitting at leg rank 11–100 never
reaches the judge (F-051's k100 run: leg_miss 0.30 but leak 0.34 — a third of all queries
had gold retrieved by a leg and dropped before/at the judge). Do not re-charter "build the
pool"; charter the levers below.

## Chartered work (each item independently landable; D-005 regime-blind throughout)

1. **W1 — the shared-constant wrong-gate (smallest, do first). DONE 2026-08-19** (commit
   `8c4ae246`, rides with this charter): the Stage-3B branch ramp now has its own bounds
   (`index.hybrid.branch_ramp.{full_weight_max,zero_weight_min}_tokens`, defaults 1024/4096,
   wired EnvRegistry -> ResolvedConfig -> SearchExecutor; adapters-lucene still performs zero
   env reads via bounds-aware overloads). Byte-identical-at-defaults pinned across the token
   grid, plus the divergence pin that would have caught 784 §K (raising the SPLADE bound no
   longer moves the branch ramp). Config-surface changeset declared. Bonus finding, logged to
   the inbox not fixed: the legacy SPLADE constants are raw `Long.getLong` reads that evade
   BOTH the ArchUnit env-read rule and `checkNoDirectJustsearchSysProp` — a gate-loophole
   class worth its own sweep. F-036 §K (2026-07-29,
   analysis-only) proved `justsearch.splade.zero_weight_min_tokens` is ONE constant read by
   TWO levers: the SPLADE parent-length fade AND the whole-vs-chunk branch ramp
   (`HybridFusionUtils.java:24-27` at the time; re-cite at HEAD). Raising it for a SPLADE
   experiment silently retunes branch balance ~4x. Fix = the one-line separation §K already
   specified (own bounds for the Stage-3B ramp, defaulting to today's 1024/4096 so shipped
   behavior is byte-identical), + a regression test pinning byte-identical fusion at
   defaults. No baseline can move (defaults unchanged); the point is that the next
   experiment can turn one knob without turning two.
2. **W2 — depth levers, measured before designed.** The candidate levers for the F-051
   leak class, each a config-only A/B on the corrected harness (shared-index method, F-052
   discipline incl. ce_coverage): (a) recall-complete `top_n` 10 → deeper (cost: CE window
   growth — the CE is ~82% of query latency, so pair every quality delta with the perf-gate
   ratchet); (b) CE window size itself; (c) F-024's pool-aware arbitration tightening
   (router Item-1) — the leg-arbitration trigger consuming pool state so the two shipped
   levers stop interacting super-additively on email (the recorded −3.22% combined enron
   cost). Decision rule, pre-registered: a lever ships default-on only if it improves the
   leak/nDCG on the target class with NO regression outside the cohort envelope on
   scifact + enron + legal (the D-004 template: default-off → measure → flip).
3. **W3 — own the legal hybrid-vs-lexical gap.** Adopt the 2026-07-11 inbox item. First
   step is attribution, not a lever: decompose the current legal gap (hybrid 0.5780 vs
   lexical ~0.689) with staged-recall + the 784 §K attribution method against a fresh run —
   how much is judge-window starvation (W2's territory), how much branch-weight policy,
   how much representation floor (not fusion's to fix, F-030(678)/F-034). Route each share
   to its owner; only the fusion share stays here.
4. **W4 — close the loop in the register.** Every W2/W3 measurement lands as register
   rows/findings before the lane closes (register rule); the F-024 follow-up and the
   inbox item get resolution notes.

## Non-goals (named so they cannot creep back in)

- No corpus/query-type router (D-005 forbids it; F-021/D-004 lessons: intelligence in the
  judge and the legs, never a router).
- No further curve-fitting of D-004's arbitration gate (its own entry forbids it; W2(c) is
  pool-awareness — a structural input, not threshold tuning).
- No chunk-SPLADE re-litigation (F-036 verdict stands until fusion can exploit a
  mid-quality leg — which is exactly what W2's measurements will say something about; if
  they do, F-036's reopen is a *finding*, not a scope change here).
- No new fusion algorithm. Every lever above is a parameter or input-shape change to the
  shipped CC/branch fusion + CE cascade.

## Acceptance

- W1: separated constants, byte-identical defaults pinned by test.
- W2: each lever has a shared-index A/B with ce_coverage-clean runs, cohort-envelope
  no-regression on the three sentinels, and a ship/park decision recorded per the D-004
  template.
- W3: the legal gap decomposed with shares routed; the fusion share either measurably
  reduced or explicitly bounded ("representation floor, not fusion's").
- Register updated before close (W4). Prose-tier discipline: this lane produces findings
  and default decisions, not machinery — if a measurement motivates real machinery, that
  gets its own chartered design, per `structural-defects-no-repeat`'s converse.
