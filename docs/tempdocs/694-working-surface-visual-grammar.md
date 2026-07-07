---
title: "Working-surface visual grammar (STUB — awaiting the independent visual audit)"
type: tempdoc
status: "open — STUB: skeleton and known failure patterns only; the evidence base (an independent, screenshot-driven visual audit) has not run yet. Do not design or implement from this stub alone."
created: 2026-07-07
related: [687, 690]
---

# 694 — Working-surface visual grammar (STUB)

## Context

687/690 redesigned the search surface's MODEL; the element-level visual grammar is still
an accretion (chat-era bubbles, inspector-era metadata rows, eval-era gauges, landing-tier
paddings) that was never sized as one system. A session-closing review (687 F6) named five
recurring failure patterns; an independent audit will supply measured evidence before any
design is fixed here.

## Skeleton (the five failure patterns any fix must answer)

1. **Attention inversion** — the loudest elements carry the least information (the user's
   own bubble, oversized submit, always-on gauges).
2. **Row-per-scrap stratification** — full-width bands for single chips/labels/meters.
3. **Metadata-before-content with duplication** — mode and surface names stated multiple
   times per viewport; pane headers spending ~120px before content.
4. **Implementation leakage** — extractor names, raw paths, raw fetch errors, token
   arithmetic surfaced to users.
5. **No alignment spine** — adjacent rows anchor left/right/center without a shared rule.

Scope note: trust-surface metadata presentation (dueling duration authorities, unlabeled
"quality" percentages, extractor names) folds into this doc unless the audit narrows it
to pure geometry — in which case that becomes its own small doc.

## Next step

Ingest the independent audit's findings as the evidence section, then design: a spacing/
emphasis tier system for working (vs landing) density, the merged bar strip, and the grid
surplus-allocation rule — naming every superseded style block as an orphan to delete in
the same work.
