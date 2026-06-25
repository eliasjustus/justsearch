---
title: "318: Corpus Drift Detection and Regime Alerting"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309]
---

# 318: Corpus Drift Detection and Regime Alerting

## Purpose

Detect when corpus composition changes enough to invalidate routing decisions
(e.g., a SHORT corpus becoming MIXED after a batch PDF import). `CorpusProfile`
(from 309 §26) computes the stats; this tempdoc adds automated drift detection
and recalibration triggers.

## Background (from tempdoc 309 §19)

309 §19 researched corpus evolution:

- **Organic growth is slow** — <1% per day at typical personal file rates.
  Regime classification (SHORT/MIXED/LONG) is stable over weeks/months.
- **Batch imports are the real threat** — importing a code repo or PDF archive
  can shift composition instantly.
- **PSI (Population Stability Index)** is the simplest drift metric:
  PSI < 0.1 → no action, 0.1-0.25 → monitor, > 0.25 → recalibrate.
- **Bimodality coefficient is noisy** — don't recompute on every commit.

## Current state

- `CorpusProfile` exists with bucket histogram (309 §26)
- `GplRevalidationTrigger` fires on 2x corpus size or new MIME types
- No document length distribution tracking or PSI-based drift detection

## Scope

- [ ] Store baseline length histogram in `CorpusProfile` at calibration time
- [ ] Extend `GplRevalidationTrigger` to compute PSI between baseline and
  current histogram on each trigger evaluation
- [ ] Fire recalibration when PSI > 0.25
- [ ] Threshold-triggered recomputation: >10% corpus size change OR weekly
- [ ] Log regime transitions (SHORT→MIXED, MIXED→LONG) at INFO level
- [ ] Persist baseline histogram alongside `GplEvalSnapshot`

## PSI calibration experiment (2026-03-17)

Simulated batch imports on a SciFact-like baseline (5189 short docs):

| Scenario | PSI | Trigger (>0.25)? |
|----------|-----|-----------------|
| +50 long PDFs | 0.088 | No |
| +100 long PDFs | 0.187 | No (monitor) |
| +500 long PDFs | 1.009 | **YES** |
| +100 medium docs | 0.015 | No |
| +500 medium docs | 0.171 | No (monitor) |
| +500 short emails | 0.008 | No |

**PSI > 0.25 is appropriate.** Small additions (50-100 docs) don't trigger; large
batch imports (500+ different-length docs) do. Regime classification (SHORT/MIXED/
LONG) only changes when the median actually shifts — PSI detects distribution change
while the routing decision correctly reflects the aggregate state. No threshold
adjustment needed.

## Out of scope

- Automatic CC weight recalibration (requires shadow eval maturity)
- Per-document-type regime classification
- Real-time drift monitoring (batch check is sufficient)

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 62 days at audit time.

