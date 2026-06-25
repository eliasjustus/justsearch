---
title: "315: Shadow Evaluation Infrastructure"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309]
---

# 315: Shadow Evaluation Infrastructure

## Purpose

Build a continuous quality feedback loop. JustSearch's run-all-then-gate
architecture provides free counterfactual data (both gated and ungated results on
every query). Shadow evaluation logs this data and periodically uses LLM-as-judge
pairwise comparison to detect quality regressions.

## Background (from tempdoc 309 §11)

309 §11 researched counterfactual evaluation, LLM-as-judge, and production systems:

- **Architecture advantage**: Run-all-then-gate = permanent A/B test with 100%
  overlap. No propensity estimation needed.
- **LLM-as-judge**: Prometheus 2 (7B) achieves 72-85% human agreement on pairwise
  ranking. Use for comparative (gated vs ungated), not absolute relevance labels
  (Soboroff SIGIR 2024: avoids performance ceiling effect).
- **Storage**: ~55 MB/year at 100 queries/day. SQLite is natural for desktop.
- **Production precedent**: Airbnb (KDD 2025) — 50x faster than A/B tests.

## Scope

- [ ] Append-only SQLite log table with per-query schema:
  `query_id`, `query_text`, `timestamp`, `pipeline_config_hash`,
  `gated_results[]`, `ungated_results[]`, `gating_decisions[]`, `latency_ms`
- [ ] Implicit feedback capture: document-open events, query reformulation
  detection (new query within 30s session window)
- [ ] Periodic LLM-as-judge batch: sample 30 queries, pairwise comparison
  (gated vs ungated), store verdicts
- [ ] Rolling 90-day retention for detailed logs, indefinite aggregated summaries
- [ ] Calibration pipeline: after ~100 queries with implicit feedback, compute
  preference statistics

## Out of scope

- Automatic fusion weight adjustment (requires calibration pipeline maturity)
- Real-time quality alerting
- Thompson Sampling (309 §24 warns about interpretation drift with noisy labels)

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 62 days at audit time.

