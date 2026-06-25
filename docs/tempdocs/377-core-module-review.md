---
title: "377: Core Module Review — Frozen Foundational Types"
status: done
created: 2026-04-06
depends_on: []
---

# 377: Core Module Review

## Context

The `modules/core` module contains the innermost hexagonal ports and search
DTOs. All 12 files have been frozen since Nov 2025 (5 months). The 367
audit confirmed every file is live — but a critical code review found
several quality and consistency issues.

This tempdoc documents the findings, the investigation results, and the
revised assessment.

## Current Contents (9 production files, 3 test files)

### Hexagonal ports (3 files)
- `AnalyzerDescriptor` — 8-line record (id, description)
- `AnalyzerRegistry` — 10-line interface, single method
- `SearchPort` — 13-line interface: `Result search(Query intent)`

### Search DTOs (3 files)
- `Query` — 57 lines. 8-field record: limit, offset, highlight, filters,
  sort, clauses, cursor, context. Nested: Filters, TimeRange, Clause.
- `Result` — 26 lines. 4-field record: hits, facets, cursor, metadata.
  Nested: Hit (doc_id, score, highlights).
- `Cursor` — 32 lines. 4-field record: mode, token, expiresAtEpochMs,
  extras.

### Utilities (2 files)
- `DocumentTypeDetector` — 157 lines. Classifies docs by MIME type +
  filename patterns into 8 categories (PDF, CODE, OFFICE, etc.).
- `TokenEstimation` — 239 lines. Token counting heuristics, LLM context
  budget calculation, content truncation strategies, RAG section formatting.

### Tests (3 files)
- `AnalyzerDescriptorTest`, `CursorTest`, `ResultTest`

## Findings

### F1: Inconsistent defensive copying — LOW RISK (investigated)

Maps are copied, Lists are not:

| Type | Field | Copied? |
|------|-------|---------|
| Cursor | `extras` (Map) | Yes — `Map.copyOf()` |
| Query | `context` (Map) | Yes — `Map.copyOf()` |
| Result | `metadata` (Map) | Yes — `Map.copyOf()` |
| Query | `sort` (List) | **No** |
| Query | `clauses` (List) | **No** |
| Result | `hits` (List) | **No** |
| Result | `facets` (Map\<String, Map\>) | **No** (nested maps also unprotected) |

**Investigation (2026-04-06):** Verified all production callers. Zero
callers mutate the returned lists. All constructors receive already-
immutable lists (`List.of()`, `.stream()...toList()`, `List.copyOf()`).
The invariant holds by convention, not enforcement. Adding `List.copyOf()`
would make it structural with zero behavioral change.

### F2: Inconsistent validation — COSMETIC

`Cursor` eagerly validates mode/token as non-blank. `Query`, `Result`,
and all nested records (`Filters`, `TimeRange`, `Clause`) perform zero
validation. `Clause(null, null, null, null)` is valid.

Inconsistent but harmless — validation happens at the REST boundary
(`SearchRequest` in app-api) before translation to core types.

### ~~F3: TokenEstimation allocates unnecessarily~~ — RETRACTED

**Investigation (2026-04-06):** All callers are on cold paths:

| Caller | Input size | Path |
|--------|-----------|------|
| `SummaryController.streamStandardSummary` | ≤6,000 chars (pre-capped) | Cold — user-triggered summarize |
| `SummaryController` synthesis step | 1,000–10,000 tokens of LLM intermediate | Cold — after all LLM calls |
| `RagStreamingHandler` fallback branch | Already token-bounded by Worker | Hot-ish but fallback only; early-returns in common case |
| `MapReducePipeline.streamSynthesis` | ~500–3,000 tokens | Cold — end of hierarchical summarize |

The `split("\\s+")` fires on at most 6,000 chars. The binary search
fires on at most ~12,000 chars and only when the budget is exceeded
(rare). Both are completely dominated by the LLM inference calls they
precede. **No performance concern is real in any call site.**

### ~~F4: DocumentTypeDetector relies on fragile ordering~~ — RETRACTED

**Investigation (2026-04-06):** The ordering dependencies are correctly
handled and documented:

- Markdown before code: `text/x-web-markdown` (Tika's MIME for `.md`)
  would match `text/x-` code check, but the markdown `contains("markdown")`
  check runs first. Comment documents this intentionally.
- Office before code: `officedocument` MIME does not start with `text/x-`,
  so no actual conflict.
- `mime.contains("c-")` is dead code: no Tika MIME type for C/C++ source
  contains `"c-"`. C source is `text/x-csrc`, C++ is `text/x-c++src` —
  both caught by `startsWith("text/x-")` before the `"c-"` check. The
  only Tika MIME containing `"c-"` is `text/rtp-enc-aescm128` (RTP
  encryption, never appears in desktop files).

The ordering is correct for all real Tika MIME types. The only actionable
item is removing the dead `"c-"` check (1 line).

### F5: Mixed concerns — NOT WORTH FIXING

`TokenEstimation.formatRagSection()` is a 3-line method called only by
`TokenEstimationUtils.truncateForRag()` in the `ui` module. Moving it
would require adding a dependency or inlining it at the call site. Not
worth the churn for a single 3-line method.

### F6: Vestigial naming — PARTIALLY RETRACTED

- `SearchPort.search(Query intent)` — parameter `intent` is vestigial
  from deleted NLU pipeline. Trivial rename to `query`.
- ~~`Result.Hit.doc_id` — snake_case in camelCase codebase.~~
  **Investigated:** `doc_id` is deliberate wire-format compatibility.
  The JSON API (`SearchResponse`), Lucene schema (`SchemaFields.DOC_ID`),
  and frontend (`search.ts: fields?.doc_id`) all use `doc_id`. Renaming
  would break the API contract. Not a bug.

### F7: Near-duplicate types across layers — INTENTIONAL

`core.dto.Query` and `app-api.SearchRequest` are structurally identical:
same 8 fields, same nested records (Filters, TimeRange, Clause, Cursor),
same defensive-copy pattern. `core.dto.Result` and `app-api.SearchResponse`
similarly overlap. Translation code in `DefaultAppFacade` and
`RemoteKnowledgeClient` maps between them field-by-field.

This is intentional layering (internal gRPC contract vs external REST
contract) — not a code quality issue.

## Dependency Analysis

### Who imports from core (production only)

| Module | Types used | Import count |
|--------|-----------|-------------|
| app-services | Query, Result, Cursor, SearchPort | 10 |
| adapters-lucene | AnalyzerDescriptor, AnalyzerRegistry | 2 |
| app-search | Cursor | 2 |
| ui | DocumentTypeDetector, TokenEstimation | 4 |
| indexing | (none — core types flow through interfaces) | 0 |

**Total: 21 production imports across 5 modules.** Low coupling.

## Revised Assessment

After investigation, 4 of the 7 original findings were retracted or
downgraded. The remaining actionable items are:

| Finding | Status | Action | Effort |
|---------|--------|--------|--------|
| F1: Missing list copies | Low risk, convention holds | Add `List.copyOf()` in compact constructors | 10 min |
| F2: Validation gaps | Cosmetic | No action — validated at REST boundary |  |
| F3: Allocation waste | **Retracted** — cold paths, small inputs | No action |  |
| F4: Fragile MIME ordering | **Retracted** — correct for all real MIMEs | Remove dead `"c-"` check (1 line) | 1 min |
| F5: Mixed concerns | Not worth fixing | No action |  |
| F6: Vestigial naming | Partially retracted (`doc_id` is deliberate) | Rename `intent` → `query` in SearchPort | 5 min |
| F7: Type duplication | Intentional layering | No action |  |

**Total actionable work: ~15 minutes.** Add `List.copyOf()` to 3 record
compact constructors, remove 1 dead MIME check, rename 1 parameter.

This is not enough to warrant a dedicated implementation session. These
can be picked up opportunistically when a future task touches these files.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Core-module review. Body explicitly says "Total actionable work: ~15 minutes. This is not enough to warrant a dedicated implementation session. These can be picked up opportunistically." Self-declared closure with deferred-to-opportunistic-fix language.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

