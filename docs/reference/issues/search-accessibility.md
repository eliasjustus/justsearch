---
title: Search Accessibility Issues
type: reference
status: stable
updated: 2026-02-02
description: "Result list ARIA, keyboard access, match pills."
---

# Search Accessibility Issues

Accessibility issues in the search result list and related components.

**Key Files:**
- `modules/ui-web/src/components/search/ResultRow.tsx`
- `modules/ui-web/src/components/search/VirtualResultList.tsx`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/util/TextAnalysisUtils.java`

---

## Open Issues

### ACC-003: Match pills use substring heuristic, not actual Lucene field matching
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-31
- **Component:** `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/util/TextAnalysisUtils.java`

**Description:** The `computeMatchedFields()` method determines which fields to show in match reason pills. It uses case-insensitive substring matching (`containsAny()`) against stored field values to check whether query terms appear in `title` and `path`. However, Lucene only searches the `content` field — title and path are never part of the actual search query. The pills report "your query terms appear in this field" not "Lucene matched this field."

For the current informational use case (cosmetic pills in rich density mode), this is acceptable — users find it useful to know their search terms appear in the filename. But it's technically a heuristic, not ground truth about search relevance.

**Impact:** Low. The pills are informational and the heuristic correlates well with user expectations. Could mislead users into thinking title/path matches affect ranking (they don't). No functional impact.

**Code Evidence:**

```java
// TextAnalysisUtils.java — substring check on unsearched fields
String title = fields.get(SchemaFields.TITLE);
if (containsAny(title, queryTerms)) {
    matched.add(SchemaFields.TITLE);  // title was never searched by Lucene
}
```

```java
// ReadPathOps (formerly LuceneIndexRuntime, line ~2037) — Lucene only searches content
new QueryParser(SchemaFields.CONTENT, indexAnalyzer);
```

**Recommendation:** Accept as-is for match pills. If search quality improvements are pursued later (multi-field search with `MultiFieldQueryParser` or `BooleanQuery` with per-field clauses), replace the heuristic with actual per-field hit attribution from Lucene. This would also change ranking behavior (title matches scoring higher) which is a separate product decision.
