---
title: "SummaryController Numerics & Cleanup"
status: done
created: 2026-02-02
completed: 2026-02-02
origin: tempdoc 83 (section 3)
---

# 83b — SummaryController Numerics & Cleanup

Numeric accuracy and minor cleanup in `SummaryController`'s text analysis methods.

**Key file:** `modules/ui/src/main/java/io/justsearch/ui/api/SummaryController.java`
**Test file:** `modules/ui/src/test/java/io/justsearch/ui/api/SummaryControllerSectionSplittingTest.java`

---

## Medium Priority

| # | Improvement | Status | Notes |
|---|-------------|--------|-------|
| 1 | Linear token interpolation | **DONE** | Replaced binary threshold with `LATIN + (CJK - LATIN) * cjkRatio`. Removed `CJK_THRESHOLD` constant. |
| 2 | Sample-based CJK detection | **DONE** | Loop now samples first 2000 code points via `Math.min(content.length(), 2000)`. |

## Low Priority

| # | Improvement | Status | Notes |
|---|-------------|--------|-------|
| 3 | ThreadLocal BreakIterator | **SKIPPED** | `findSectionBoundary()` is called infrequently (once per section split). Allocation cost is negligible. Not worth ThreadLocal complexity. |
| 4 | Exact-value unit tests | **SKIPPED** | Existing tests already use `assertEquals` with EPSILON. Section splitting tests use ranges because BreakIterator locale behavior varies across JDK versions. |
| 5 | Fix misleading comment | **DONE** | Removed parenthetical claim that BreakIterator handles abbreviations, URLs, decimals. |
