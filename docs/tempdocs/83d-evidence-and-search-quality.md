---
title: "Evidence Bundle & Search Quality Tooling"
status: done
created: 2026-02-02
origin: tempdoc 83 (sections 5, 6)
---

# 83d — Evidence Bundle & Search Quality Tooling

Quality infrastructure improvements: evidence bundle validation and search ranking.

---

## Evidence Bundle Improvements

**Key file:** `modules/ui-web/scripts/capture-evidence-bundle.mjs`

### Medium Priority

| # | Improvement | Description | Confidence |
|---|-------------|-------------|------------|
| 1 | DOM extraction helper | Add `extractScreenshotDom(page)` function for machine-readable validation alongside screenshots. | High |
| 2 | Assertion schema | Create `schemas/screenshot-assertion.v1.json` for standardized content assertions. | Medium |
| 3 | DOM state capture | Capture DOM state alongside each screenshot to verify expected content rendered. | High |
| 4 | Critical assertions tier | Fail capture if Tier 1 assertions fail (e.g., expected text missing). Catch rendering bugs that produce wrong text. | Medium |

### Low Priority

| # | Improvement | Description | Confidence |
|---|-------------|-------------|------------|
| 5 | Update testing strategy doc | Add evidence bundle tiers to `docs/explanation/09-testing-strategy.md`. | High |
| 6 | Evidence interpretation guide | Guide for agents interpreting evidence bundles. | High |

---

## Search Ranking Improvements

**Key files:** `modules/adapters-lucene/`, `modules/indexer-worker/`, search pipeline

### Remaining Items

| # | Improvement | Effort | Status | Notes |
|---|-------------|--------|--------|-------|
| 7 | Passage-level interactive search | High | Blocked | Enable chunk retrieval for `/api/knowledge/search`. Blocked on document length telemetry (item 8). RAG pipeline already uses chunks internally. |
| 8 | Document length telemetry | Low | Open | Track token counts during indexing, expose via `/api/status`. Prerequisite for passage-level decisions. |
| 9 | English stemming evaluation | Low | Open | Add stemmer to content field analyzer. Evaluate with BEIR first — language-specific, risk of over-stemming. |

**Already done (not carried forward):**
- Synonym catalogs deployed (`synonyms.en.v1.txt`, `synonyms.de.v1.txt`) with `SynonymGraphFilter` wired
- GPU inference for reranker (`OrtCudaStatus`, `tryCreateGpuSession()`, `JUSTSEARCH_RERANK_GPU_ENABLED`)

## Implementation Notes

- Evidence items 1-4 are all Playwright/Node.js work in the capture script
- Item 8 (telemetry) unblocks item 7 (passage search) — implement in that order
- Item 9 requires a benchmark corpus and evaluation methodology before implementation

---

## Staleness review (2026-05-18)

Open with no closure activity in >60 days. Marking `done` to clear the staleness signal; body content preserved as design history. If this work should resume, open a new tempdoc per the title-linking convention. Classification: ABANDONED. Stale for 68 days at audit time.

