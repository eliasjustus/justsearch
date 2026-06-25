---
title: "326: Entity-Aware Retrieval — Activating NER for Search Quality"
type: tempdoc
status: done
created: 2026-03-19
depends-on: [185, 309]
---

# 326: Entity-Aware Retrieval

## Purpose

Activate the dormant NER pipeline (tempdoc 185) and evaluate its impact on
search quality, specifically the entity-matching failures identified in
309 §42's per-query failure analysis.

## Background

### The quality gap (from 309 §42 per-query failure analysis)

EnronQA eval (300 queries, 5485 emails, single-user inbox) revealed:
- 22/300 queries (7%) have **R@10 = 0** — complete retrieval failure
- These failures are **entity-matching failures**: queries ask about specific
  people ("James Hoecker"), organizations ("General Electric", "FERC"),
  dates, and email addresses that don't appear verbatim in the target email
- BM25 can't match when the query uses a different name form than the email
  ("Jim" vs "James", "GE" vs "General Electric")

Additionally, 45/300 queries are hurt by the cross-encoder (CE degrades nDCG
by reordering). Per-query analysis showed CE over-weights semantic similarity,
promoting topically-related but wrong emails over exact-match targets. Entity
awareness would provide a stronger relevance signal than generic semantic
similarity for these person/org-specific queries.

### The existing infrastructure (tempdoc 185)

Tempdoc 185 implemented a complete entity extraction pipeline, merged to main
on 2026-02-12. The pipeline is **fully built but dormant** — gated on NER
model file presence.

**What's implemented (all on main):**

| Component | Status | Key files |
|-----------|--------|-----------|
| BERT-base-NER ONNX inference | Built, needs model | `BertNerInference.java` |
| NER backfill loop | Built | `NerBackfillOps.java` |
| Entity disambiguation (SoftTFIDF) | Built | `DisambiguationService.java`, `SoftTFIDF.java` |
| SQLite cluster store | Built | `EntityClusterStore.java` |
| Query-time filter expansion | Built | `SearchOrchestrator.java` (facet filters only) |
| Entity facets (PER/ORG/LOC) | Built | `FacetingEngine.java`, `SearchFiltersBar.tsx` |
| Variant provenance | Built | `entity_facet_variants` proto field |
| Coverage gating (≥10%) | Built | Frontend gates facets on NER coverage |
| SSOT schema fields (keyword) | Built | `entity_*_raw` fields for filter/facet |
| **Entity-boosted BM25** | **Built (326)** | `entity_*_text` fields + `TextQueryOps.combineMultiField()` |

**What's NOT built (pre-326):**
- Date/time entity extraction (only PER/ORG/LOC)
- Email address entity extraction
- ~~Entity-boosted BM25 queries (entity matches ranked higher)~~ → **built in 326**
- Per-document entity offsets (needed for SPLIT override UI)

### Why this matters now

The 22 R@10 failures on EnronQA include queries about:
- **People**: "James Hoecker" (FERC chairman), "Neil Stein", "Katie Kaplan"
- **Organizations**: "General Electric", "FERC", "CPUC", "Mirant"
- **Specific references**: "VentureWire", "EnronOnline", "PROVANTAGE"

These are exactly PER/ORG entities that the NER pipeline extracts. When NER
is active with entity-boosted BM25 (Phase 2), documents containing
NER-extracted entities matching query terms get a ranking boost via
dedicated entity text fields. The disambiguation pipeline also clusters
variant name forms ("Jim Hoecker" ↔ "James Hoecker") for facet filter
expansion, though this only helps when the user clicks entity facets — it
does not affect free-text ranking (see Critical Analysis below).

## Critical Analysis (session 80e11c82, 2026-03-19)

A deep code review of all four pipeline layers revealed a **fundamental gap**
in the original tempdoc's reasoning:

**The existing query-time "cluster expansion" is filter-only.** It expands
user-selected facet filter values to include cluster variants. It does NOT:
- Detect entity mentions in free-text query text
- Add entity field boosts to BM25 ranking
- Affect free-text retrieval scoring in any way

`TextQueryOps.buildTextQuery()` queries only `CONTENT` and `TITLE` fields.
The entity fields (`entity_persons_raw`, etc.) are never queried during
free-text search. A user typing "James Hoecker" gets BM25 on content only.

**Consequence:** Phase 1 as originally written ("activate and measure via
jseval requery") would show **zero improvement** on the 22 R@10=0 queries.
The eval uses free-text search — no facet filters. NER activation alone
gives entity facets in the UI but does not change retrieval quality.

### Additional findings from code review

**Inference layer (solid):**
- ONNX tensor lifecycle is correct (TWR blocks, no leak risk)
- Chunk-boundary entity fragmentation possible (50-token overlap may split
  multi-word entities across chunks; dedup is exact-match only)
- Lazy init + fail-once pattern matches reranker

**Disambiguation layer (functional, scaling concerns):**
- `loadAll()` does full-table scan per entity type (3x per batch + 1 for
  snapshot) — no `WHERE entity_type=?`, no index
- No transaction batching for upserts (100 entities = 100 WAL commits)
- Greedy one-pass with no canonical elevation (first-seen form = canonical)
- `MAX_CLUSTER_SIZE = 50` with no overflow remerge

**Backfill wiring (clean):**
- Strict sequencing: embeddings → NER → disambiguation
- Disambiguation gate is whole-corpus (`pendingNer == 0` globally) —
  disambiguation won't run until ALL documents finish NER
- Batch of 100 docs/NER cycle, 500 docs/disambiguation cycle

**Query-time (correct but incomplete):**
- `expandEntityFilters`: correct, caps at 500 terms
- `mergeEntityFacets`: correct, groups by canonical
- UI coverage gate (≥10%): implemented
- **Entity-boosted BM25: not implemented** — this is the missing piece

### Pipeline interop analysis (post-185 changes)

Since tempdoc 185 was merged (Feb 2026), the search pipeline gained:
- BGE-M3 unified encoder (322) — 3-way retrieval (BM25 + dense + SPLADE)
- Hybrid fusion with CC3 weights (274) — configurable per-leg weights
- Query classification signals (306) — QPP computed but not yet gating
- Chunk-aware merge (280) — whole-doc + chunk branch fusion
- Title boost via DisjunctionMaxQuery (306-B1)

**Critical integration gap found: `searchTextWithFilter` bypassed all
multi-field boosts.** The 2-leg hybrid path (sparse+dense, the production
default when SPLADE is unavailable) called `HybridSearchOps.searchHybridFiltered()`
→ `textQueryOps.searchTextWithFilter()` → `buildSimpleContentQuery()`.
This built a content-only query — **no title boost (306-B1), no entity
boost (326)**. The 3-way path and debug path correctly used `searchText()`
→ `buildTextQuery()` → `combineMultiField()`.

| Retrieval path | Before 326 fix | After 326 fix |
|---------------|---------------|--------------|
| Sparse-only | content + title(×3) | content + title(×3) + entity(×2) |
| 3-way (BM25+dense+SPLADE) | content + title(×3) | content + title(×3) + entity(×2) |
| **2-leg hybrid (non-debug)** | **content only** | **content + title(×3) + entity(×2)** |
| 2-leg hybrid (debug) | content + title(×3) | content + title(×3) + entity(×2) |
| Sparse+SPLADE | content + title(×3) | content + title(×3) + entity(×2) |

The `searchTextWithFilter` fix is a **correctness improvement beyond
entity boost** — it also restores the 306-B1 title boost that was
missing on the production 2-leg hybrid path since 306 was implemented.

**Other interop findings:**
- Entity filters are correctly excluded from chunk search (chunks don't
  store entity fields) — `buildChunkFilterQuery()` skips them
- Entity facets are only computed on the sparse-only path; multi-leg
  paths don't compute facets (pre-existing limitation, not a regression)
- `entity_*_text` fields are `stored: false` — lost on read-modify-write,
  repopulated by NER backfill. This is by design (same as SPLADE field)
- SSOT canonical catalog (`SSOT/catalogs/fields.v1.json`) is out of sync
  with the runtime copy (`modules/adapters-lucene/.../fields.v1.json`) —
  pre-existing drift, not caused by 326

## Scope (revised)

### Phase 1: Activate + verify pipeline health — DONE (session 80e11c82)

1. **[x] Obtain BERT-base-NER ONNX model** — exported via
   `python -m optimum.exporters.onnx --model dslim/bert-base-NER models/onnx/ner/`.
   Model at `models/onnx/ner/model.onnx` (431 MB, fp32) + `tokenizer.json`.
   Verified: output shape `[batch, seq, 9]` matches `BertNerInference`
   expectations. Auto-discovery path (`<modelsDir>/onnx/ner/`) confirmed.

2. **[x] Verify NER pipeline activates** — confirmed via jseval `--pipeline`
   run (2026-04-06): NER backfill completed on 5189 docs (7303 including
   chunks), entity facets appeared with real values after ≥10% coverage.

3. **No eval expected from Phase 1 alone.** NER activation does not affect
   free-text retrieval without the entity-boosted BM25 (Phase 2, done).

### Phase 2: Entity-boosted BM25 — DONE (session 80e11c82)

Entity-boosted BM25 implemented. The original plan proposed TermQuery on
keyword fields, but analysis showed keyword fields store full multi-word
values as single terms ("James Hoecker" = one term), so individual token
matching ("Hoecker") wouldn't work. Revised approach: **new ICU-analyzed
text fields** alongside the existing keyword fields.

**What was built (8 files, 116 insertions):**

- [x] 3 new SSOT fields: `entity_persons_text`, `entity_organizations_text`,
  `entity_locations_text` — `"text"` type, `"analyzer": "icu"`, `stored: false`
- [x] `NerBackfillOps` writes text fields alongside keyword fields
  (space-joined entity values → ICU tokenization at index time)
- [x] `TextQueryOps.buildTextQuery()` extended: `combineWithTitle()` →
  `combineMultiField()` — `DisjunctionMaxQuery` now includes up to 6
  disjuncts: content + title(×3.0) + 3 entity fields(×2.0)
- [x] Configurable `ENTITY_BOOST = 2.0` via `ResolvedConfig.Search.entityBoost()`
- [x] `JUSTSEARCH_SEARCH_ENTITY_BOOST` env var / `justsearch.search.entity_boost`
  system property (0 to disable)
- [x] `EnvRegistry.SEARCH_ENTITY_BOOST` registered

**Key design decision:** ICU-analyzed text fields over keyword TermQuery.
Keyword fields can only exact-match full values. Text fields enable BM25
token matching: query term "Hoecker" matches entity "James Hoecker" via
ICU tokenization. The text fields are not stored (no disk overhead) — they
only contribute to the inverted index for BM25 scoring.

**How entity boost changes ranking:** The `DisjunctionMaxQuery` uses the
best field's score + 10% tie-breaker from other fields. When a query term
like "Mirant" matches both the content field (diluted across the full
document text) and the entity field (concentrated — just entity names),
the entity field's higher BM25 score (shorter field → higher TF) becomes
a strong ranking signal. Documents with NER-extracted entities matching
query terms get promoted.

**Bug fix (bonus):** Fixed `searchTextWithFilter` (used by 2-leg hybrid
production path) to use `combineMultiField` instead of content-only
`buildSimpleContentQuery`. This restores both the 306-B1 title boost AND
adds the new entity boost to the production hybrid path. Previously, the
2-leg hybrid scored differently from all other paths because it missed
the title boost entirely.

**Verification:**
- Compilation: passes
- SSOT fingerprint tests: pass
- adapters-lucene tests: pass
- configuration/app-search tests: pass
- No new test failures (8 pre-existing Office/PDF fixture failures on main)

### Phase 3: Eval with entity-boosted BM25 — NEXT

Requires: Phase 1 (NER model on disk) + Phase 2 (done). Run eval with
entity-boosted BM25 active. Compare R@10 on the 22 failing queries:

```bash
jseval requery --dataset mixed/enron-qa --modes lexical,bm25_splade,full
```

**Success criterion:** ≥5 of the 22 R@10=0 queries recover.

**Important:** Entity boost helps ranking (promoting docs with entity
matches), not variant matching. If a query says "James Hoecker" but the
email only contains "Jim Hoecker", entity boost alone won't bridge the
gap — that requires disambiguation cluster expansion at query time (not
yet implemented for free-text search, only for facet filters). The eval
will show whether the ranking improvement alone is sufficient.

### Phase 4: Cluster-expanded free-text search — NEXT

Phase 2-7 proved that entity boost via DMQ is a dead end when entity
fields contain the same tokens as content. The only way entity fields
add value is when they contain tokens NOT in the content field —
variant forms from disambiguation clusters.

**Literature validation (2026-03-23):** This is the standard approach
in production search systems. Elasticsearch uses `synonym_graph` for
multi-word query-time expansion. Vespa uses `EQUIV` operator. Amazon
uses QEEW (Query Expansion and Entity Weighting). Microsoft Research
MFAR (ICLR 2025) confirmed that static field boosting without
query-conditioned expansion produces minimal gain.

Dense retrieval does NOT solve name variant matching — multiple sources
confirm bi-encoder embeddings inconsistently capture "Jim" ↔ "James".
Our EnronQA evidence (dense adds nothing) is consistent.

**Architecture: pre-retrieval query rewrite**

1. **Entity detection in query**: Match query n-grams against
   `EntityClusterSnapshot.rawToCanonical`. This is a dictionary
   lookup, not NER inference — fast and deterministic.
2. **Cluster expansion**: For each detected entity, retrieve all
   cluster variants via `expandCanonical()`. E.g., "Jim Hoecker" →
   {"James Hoecker", "J. Hoecker", "Jim Hoecker"}.
3. **Query expansion**: Add variant terms as SHOULD clauses on entity
   text fields. Use `BooleanQuery.SHOULD` with the entity text fields.
   Expanded terms add recall without hurting precision (SHOULD = optional).
4. **Scoring**: Consider replacing DMQ with `CombinedFieldQuery` (BM25F,
   Lucene 9+ sandbox) — BM25F combines weighted TF across fields,
   which is mathematically correct for the "same tokens + variant
   tokens" case. DMQ's max-score approach underweights co-occurrence.

**Existing infrastructure reused:**
- `EntityClusterSnapshot` — already loaded in `SearchOrchestrator`
- `expandCanonical()` — already implemented for facet filter expansion
- Entity text fields — already indexed (stored: true, ICU-analyzed)
- `expandEntityFilters()` in `SearchOrchestrator` — reference impl

**New code needed:**
- Query-time entity detection (n-gram matching against cluster snapshot)
- Query expansion in `TextQueryOps` (SHOULD clauses for variants)
- Integration point in `SearchOrchestrator` (before dispatching to legs)

**Why this should fix the R@10=0 failures:** The 21 remaining failures
are queries like "James Hoecker" where the email says "Jim Hoecker".
With cluster expansion, the query becomes "James Hoecker" OR "Jim
Hoecker" — BM25 can now match the document. The entity text field
contains "Jim Hoecker" (from NER extraction), and the expanded query
term "Jim Hoecker" matches it directly.

### Phase 5: Date/email entity extraction (if needed)

The current NER pipeline only covers PER/ORG/LOC. The EnronQA failures also
include date queries ("What is the date of...") and email address queries
("What is the email address..."). These need different extraction:
- **Dates**: Regex or SUTime (Stanford NLP) — rule-based, no model needed
- **Email addresses**: Simple regex extraction at ingest time
- Both would be stored as additional facet fields with the same
  disambiguation + cluster infrastructure

## Eval methodology

**No new eval infrastructure needed.** Use existing jseval workflow:

```bash
# With NER model present, index EnronQA, wait for NER backfill
jseval requery --dataset mixed/enron-qa --modes lexical,bm25_splade,full

# Compare to existing baseline (already on disk):
# tmp/eval-results/enron-qa/20260319T024405_mixed_enron-qa/
```

**Key metrics to compare:**
- R@10 on the 22 previously-failing queries (current: 0/22)
- P@1 overall (current: 216/300 for bm25_splade)
- nDCG@10 overall (current: 0.830 for bm25_splade)

**Success criterion:** ≥5 of the 22 R@10=0 queries recover (relevant email
enters top-10). Any improvement validates entity-aware retrieval for personal
content.

## Environment notes

- Backend: `./gradlew.bat --no-configuration-cache :modules:ui:runHeadlessEval`
- EnronQA corpus: already materialized at `tmp/eval-enron-corpus/`
- EnronQA dataset: `datasets/mixed/enron-qa/` (300 queries, 5485 docs)
- Baseline results: `tmp/eval-results/enron-qa/` (MiniLM CE) and
  `tmp/eval-results/enron-qa-gte/` (GTE CE)
- Per-query analysis: 309 §42 identifies the 22 failing query IDs
- NER model: `models/onnx/ner/model.onnx` + `tokenizer.json` (431 MB fp32)

**GPU env vars for eval** (corrected from session 80e11c82):
```bash
JUSTSEARCH_MODELS_DIR=D:/code/JustSearch/models
JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH=D:/code/JustSearch/tmp/ort-variant-test/cuda-12.4
JUSTSEARCH_EMBED_GPU_LAYERS=99        # GPU embedding (NOT EMBED_BACKEND=GPU!)
JUSTSEARCH_SPLADE_GPU_ENABLED=true    # GPU SPLADE
```
**Do NOT set** `JUSTSEARCH_EMBED_BACKEND=GPU` — that controls model format
(auto/onnx/llama), not compute device. `GPU` is not a valid value and
causes ONNX model discovery to be skipped entirely.

## Relationship to other work

- **309 §42**: Per-query failure analysis that identified the entity-matching
  gap. This tempdoc is the actionable follow-up.
- **185**: The complete entity extraction implementation. This tempdoc
  activates it for search quality measurement.
- **Search quality register F-008**: CE is corpus-dependent — entity awareness
  may provide a better signal than CE for personal email.
- **Search quality register Q-001**: Why does CE hurt on email? Entity-matching
  failures may be part of the answer — CE promotes semantically similar but
  entity-wrong emails.

## The 22 failing queries (from 309 §42 analysis)

These queries have R@10=0 in `bm25_splade` mode on EnronQA. The agent should
check each one for entity-matching potential:

Query IDs: q_114, q_126, q_144, q_145, q_159, q_174, q_189, q_190, q_205,
q_210, q_220, q_221, q_227, q_230, q_231, q_240, q_241, q_254, q_256, q_269,
q_276, q_290.

Sample failing queries:
- "What is the date of the email with the subject 'IEP News 7/16' sent by
  jmunoz@mcnallytemple.com?" → email address entity
- "On what date will the Senate committee meet to vote on contempt citations
  against generators Mirant..." → ORG entity (Mirant)
- "What is mentioned as being available for free in the email from PROVANTAGE
  Corporation?" → ORG entity (PROVANTAGE)
- "What are the phone numbers of Neil Stein and Bryan Sifert?" → PER entities

## Current Status (session 80e11c82, merged 2026-03-20)

### Done
- [x] Deep code review of all 4 NER pipeline layers
- [x] Identified that cluster expansion is filter-only (not free-text)
- [x] Entity-boosted BM25 (Phase 2): 3 analyzed text fields + DMQ integration
- [x] Fixed `searchTextWithFilter` missing title+entity boost on 2-leg hybrid
- [x] Configurable `ENTITY_BOOST` with env var support
- [x] All tests passing (no regressions)
- [x] Phase 1: BERT-base-NER ONNX model exported and placed at
  `models/onnx/ner/` (431 MB fp32, output shape verified)
- [x] Fixed NER backfill deadlock when no embedding model available
- [x] Fixed `BertNerInference` missing `token_type_ids` input
- [x] Fixed `EMBED_BACKEND=GPU` silent failure (286 Phase 7: validation warning)
- [x] Extracted `Ai.Embedding` sub-record + removed dead GGUF code (286 Phases 8-9)
- [x] Fixed reranker `max_seq_len` default divergence 512→8192 (286 Phase 6)
- [x] Disabled breath-holding in `runHeadlessEval` (jseval polling throttle fix)
- [x] Merged to main (`49deb740d`, 11 commits, 30 files)

### Phase 3 results (2026-03-22)

EnronQA eval completed. Run: `20260322T162200_mixed_enron-qa` at
`tmp/eval-results/enron-qa-entity/`. Git: `93b0309d1`. 5486 docs
indexed in ~17 min (throughput improvements applied).

**Headline: 15/22 previously-failing queries recovered (R@10=0 → R@10>0).**

| Mode | nDCG@10 | P@1 | R@10 | R@10=0 count |
|------|---------|-----|------|-------------|
| lexical (baseline 309) | 0.810 | 0.697 | 0.910 | 22 |
| **lexical (326)** | **0.825** | **0.713** | **0.923** | **23** |
| bm25_splade (baseline 309) | 0.830 | 0.720 | 0.927 | 22 |
| bm25_splade (326) | 0.801 | 0.673 | 0.913 | — |

**Of the original 22 R@10=0 queries:**
- 15 recovered (R@10=1.0), 8 with nDCG@10=1.0 (perfect ranking)
- 7 still failing: q_126, q_159, q_189, q_190, q_205, q_210, q_230

**But 16 new R@10=0 queries appeared** that weren't failing before
(q_8, q_9, q_51, q_75, q_92, q_99, q_122, q_180, q_223, q_228,
q_261, q_272, q_274, q_285, q_286, q_288). Net R@10=0 count went
from 22 → 23 (+1).

**Caveat: not a clean A/B comparison.** The pipeline configuration
differs from the 309 baseline:
- `chunk_merge` and `branch_fusion` are now active (weren't in 309)
- `searchTextWithFilter` title boost fix is active (restores 306-B1)
- Entity boost is active (but NER may not have completed for all docs —
  NER was at 12187/13237 when queries ran, not 100%)

The improvements are likely from a combination of:
1. Title boost restoration on the BM25 leg (`searchTextWithFilter` fix)
2. Chunk-aware merge (finding relevant content in specific chunks)
3. Entity boost (marginal — NER coverage was partial)

The 16 new failures may be chunk merge regressions (chunk-level scoring
can demote whole-doc matches). Needs per-query analysis to attribute.

**The `bm25_splade` regression (-2.9% nDCG)** is likely from chunk merge
interacting differently with the SPLADE fusion. The SPLADE leg in the
309 baseline didn't have chunk merge active.

### Isolation run (2026-03-22, Option C: restart with different config)

A/B comparison on the same index (same data dir, backend restart only):
- Run A: `JUSTSEARCH_SEARCH_ENTITY_BOOST=0` + `jseval run --pipeline`
- Run B: `JUSTSEARCH_SEARCH_ENTITY_BOOST=2.0` + `jseval requery`

| Mode | ENTITY_BOOST=0 | ENTITY_BOOST=2.0 | Delta |
|------|---------------|-----------------|-------|
| lexical nDCG@10 | 0.8259 | 0.8259 | **0.0000** |
| lexical P@1 | 0.7167 | 0.7167 | **0.0000** |
| lexical R@10 | 0.9233 | 0.9233 | **0.0000** |

**Entity boost has zero measurable effect.** The 15/22 query recovery
in the earlier run was entirely from the `searchTextWithFilter` title
boost fix (306-B1 restoration) and chunk merge activation — not entity
boost. This confirms the revised expectation: entity text fields contain
the same tokens as the content field, so the DMQ tie-breaker contribution
is too small to change any document's rank.

Note: bm25_splade comparison was invalid — `requery` didn't pass the
SPLADE pipeline flag, so both modes ran the same BM25-only pipeline.

### Root cause: NER output is garbage (2026-03-22)

Infrastructure verification (ENTITY_BOOST=100, same data) still showed
zero effect. Direct inspection of entity facets and stored fields revealed
**the NER extraction produces fragmented entity names**:

| Expected | Actual `entity_persons_raw` | Actual `entity_organizations_raw` |
|----------|---------------------------|----------------------------------|
| "Jeff Dasovich" | "nt" | "t Sound Energy" |
| (full names) | (1-2 char fragments) | (truncated, first chars missing) |

Entity facet top values: `"D"`, `"Mira"`, `"P"`, `"C"`, `"En"` — all
fragments, not real entity names. The entity text fields contain these
fragments, which don't match any real query terms. This is why entity
boost has zero effect: **the NER output is unusable**.

**Root cause (confirmed):** Subword prediction inconsistency — the
model assigns B-/I- tags per subword token, producing fragments when
continuation subwords get independent predictions. See "NER decoding
fix" below for the actual fix.

**Correction (2026-03-23):** The model is DistilBERT-NER
(`dslim/distilbert-NER`), which is the correct and intended model. An
earlier attempt to re-export as BERT-base-NER was reverted. The
fragment output was caused by the subword aggregation bug in
`BioTagDecoder`, not the model choice.

**Verification (after decoder fix):** Entity facets show real names:
"Jeff Dasovich", "Gray Davis", "Southern California Edison", "Enron".
Some single-letter noise remains (confidence threshold issue).

**Re-run A/B isolation (correct model, 2026-03-22):**

| Mode | ENTITY_BOOST=0 | ENTITY_BOOST=2.0 | Delta |
|------|---------------|-----------------|-------|
| lexical nDCG@10 | 0.8258 | 0.8258 | **0.0000** |

**Entity boost still has zero effect with the correct model.** The NER
output is now valid (real entity names), but the entity text fields
contain the same tokens as the content field. The DMQ tie-breaker (10%
of entity field score) is too small to change any document's ranking.

**Impact:** All NER data in the index is garbage. Entity facets, entity
filters, entity boost, and disambiguation clusters are all operating on
truncated fragments. The entire NER pipeline is non-functional despite
showing `ner_status=COMPLETED`.

### NER decoding fix — DONE (5e55feec8)

Root cause was **subword prediction inconsistency** — the model assigns
B-/I- tags per subword token, but BERT models often predict B-ORG on
continuation `##` subwords (e.g., B-ORG on `##ER` in F-##ER-##C).
`BioTagDecoder` treated each subword independently, producing fragments.

**Fix:** WordId-based aggregation — only the first subword of each word
determines the BIO tag; continuation subwords inherit. This matches
HuggingFace's `aggregation_strategy="simple"`.

**Verified live:** "Jeff Dasovich", "FERC", "Federal Energy Regulatory
Commission", "Puget Sound Energy", "Mirant Corporation", "Katie Kaplan",
"Gray Davis" all extracted correctly. No more fragments.

**Minor remaining issues:**
- Single-token names ("Jeff", "Davis", "Bush") — correct but noisy,
  could raise confidence threshold
- Punctuation spacing ("Dow Jones & Company , Inc") — WordPiece
  detokenization artifact, cosmetic

### Root cause of zero entity boost effect (2026-03-23)

The entity text fields (`entity_*_text`) are `stored: false`. When NER
backfill writes them, they enter the Lucene inverted index but are not
stored as retrievable field values. Later, when SPLADE backfill (or
embedding/chunk backfill) runs `readModifyWrite()` on the same document:

1. `WritePathOps.readModifyWrite()` loads only **stored fields** (line 259)
2. `entity_*_text` is not among them (not stored)
3. `IndexWriter.updateDocument()` replaces the entire doc — entity text
   fields are silently dropped from the inverted index

The code already handles this for SPLADE (`WritePathOps.java:287-291`
resets `splade_status=PENDING` when SPLADE data isn't in the update map).
Entity text fields have no equivalent protection.

**By the time all backfills complete, every document's entity text fields
have been wiped.** Entity boost queries match nothing → zero effect.

**UPDATE (2026-03-23):** The readModifyWrite hypothesis was wrong.
Further investigation showed:
1. The combined backfill path (`CombinedEnrichmentBackfillOps`) runs
   when 3 providers are available (embed + SPLADE + NER) — confirmed
   in worker log: `ner_ok=5486` docs through combined path
2. Combined path writes entity text fields in the same RMW as SPLADE
   — no field loss from subsequent RMW calls
3. The `stored: false` issue is real but not the active blocker

**Actual root cause: SSOT catalog drift.** The entity text fields
(`entity_persons_text`, etc.) were added only to the adapters-lucene
copy (`modules/adapters-lucene/.../SSOT/catalogs/fields.v1.json`).
The Worker loads the **canonical** copy at `SSOT/catalogs/fields.v1.json`
which does NOT have them. `FieldMapper` logs:
```
WARN: Unknown field 'entity_persons_text' ignored — not defined in SSOT field catalog.
```
The fields are silently dropped and never enter the Lucene index.

**Fix:** Add entity text fields to the canonical SSOT catalog at
`SSOT/catalogs/fields.v1.json`.

### SSOT catalog fix — DONE (f77f874cc)

Added entity text fields to canonical `SSOT/catalogs/fields.v1.json`
and changed `stored: false` → `stored: true` on both catalogs. Also
added NER status guard in `readModifyWrite` (parity with SPLADE guard),
but removed it immediately (133c26731) because it caused an infinite
NER↔SPLADE reset loop: NER RMW resets SPLADE → SPLADE re-runs → SPLADE
RMW resets NER → repeat. With `stored: true`, entity text fields survive
RMW without a guard.

### A/B isolation with working entity fields (2026-03-23)

First run where entity text fields are **actually in the Lucene index**
(verified: no `FieldMapper` "Unknown field" warnings, entity facets
show real names). Clean A/B on same data dir.

| Mode | ENTITY_BOOST=0 | ENTITY_BOOST=2.0 | Delta |
|------|---------------|-----------------|-------|
| lexical nDCG@10 | 0.8258 | 0.7905 | **-0.0353 (-4.3%)** |
| lexical P@1 | 0.7167 | 0.6433 | **-0.0734 (-10.2%)** |
| lexical R@10 | 0.9233 | 0.9200 | **-0.0033 (-0.4%)** |

**Entity boost actively hurts search quality.** The infrastructure is
proven to work (non-zero delta confirms entity text fields are indexed
and scored), but the entity signal is harmful at ENTITY_BOOST=2.0:
- Entity text fields have very short content (just entity names) →
  extremely high TF for matching terms → disproportionate BM25 scores
- DistilBERT NER produces noisy entities (single-char "E", "P", "&",
  ".") that match common query terms and promote irrelevant documents
- The DMQ tie-breaker (10%) amplifies this noise across all queries

**Conclusion:** Entity-boosted BM25 via DMQ is the wrong architecture
for this signal. The entity field's BM25 score is too noisy and too
strong relative to content. Possible alternatives:
1. **Lower boost** (0.5 or 0.1) — reduce entity signal to a mild hint
2. **Filter out single-char/noisy entities** — raise confidence
   threshold or add a minimum entity length
3. **Use entity as a FILTER, not a boost** — match entity fields as a
   hard constraint (TermQuery SHOULD clause) rather than BM25 scoring
4. **Phase 4 (cluster expansion)** remains the high-value path — entity
   fields would contain *variant* tokens not in the content field

### Phase 6: Standalone NER quality eval — DONE (session 80e11c82)

Verify that our NER pipeline (ONNX model + BioTagDecoder subword
aggregation) reproduces published benchmark quality.

**Approach:** Extended jseval with `ner-eval` subcommand:
1. Loads CoNLL-2003 test set from local JSON (tner/conll2003 format)
2. Runs ONNX model + tokenizer with pre-tokenized input + subword agg
3. Computes entity-level F1 (custom implementation — seqeval incompatible
   with Python 3.14)
4. Compares against published F1=92.2%

**Results (2026-03-23):**

| Type | F1 | Precision | Recall | Support |
|------|-----|-----------|--------|---------|
| PER | 0.927 | 0.929 | 0.925 | 1617 |
| ORG | 0.813 | 0.805 | 0.821 | 1661 |
| LOC | 0.898 | 0.874 | 0.923 | 1668 |
| MISC | 0.754 | 0.725 | 0.785 | 702 |
| **Overall** | **0.863** | — | — | 5648 |
| **No MISC** | **0.879** | — | — | 4946 |

Delta vs published F1=0.922: **-0.059** (6 points).

**Validation set results (for comparison):**

| Type | F1 | Support |
|------|-----|---------|
| PER | 0.953 | 1842 |
| ORG | 0.839 | 1341 |
| LOC | 0.942 | 1837 |
| MISC | 0.854 | 922 |
| **Overall** | **0.908** | 5942 |
| **No MISC** | **0.918** | 5020 |

Delta vs published on validation: **-0.014** (1.4 points).

**Analysis:** The published F1=0.922 was almost certainly evaluated on
the **validation set**, not the test set. Our validation F1=0.908 is
within 1.4 points — the remaining gap is attributable to minor ONNX
export precision differences and aggregation strategy details.

The test set is harder (F1=0.863 vs 0.908 on validation), which is
typical — CoNLL-2003 test has more out-of-distribution entities. ORG
is the weakest type on both splits (0.813 test, 0.839 val).

**Conclusion:** NER model quality is sound. Our ONNX model + subword
aggregation reproduces published quality within 1.4 points on the same
split. The entity boost hurting search quality (-4.3% nDCG) is a
**boost architecture problem**, not a model quality problem.

**Bug found during eval:** Initial run showed F1=0.55, caused by
`_predict_sentence()` joining pre-tokenized words with spaces and
re-tokenizing. CoNLL-2003 requires `is_pretokenized=True` for correct
word→subword mapping. This was an eval-only bug — production code
processes free-form text, not pre-tokenized input.

**Implementation notes:**
- Data: local JSON files at `scripts/jseval/data/conll2003/` (not
  committed; download URLs in docstring). Avoids HuggingFace `datasets`
  library which dropped loading script support in 4.x.
- No external deps: removed `datasets` from `[ner]` optional group.
- F1 computation: ~60 lines, BIO span extraction + micro-averaged P/R/F1
  with per-type breakdown.

### Phase 7: Entity filtering + boost tuning — DONE (session 80e11c82)

**Entity filtering (BioTagDecoder):**
- Added `MIN_ENTITY_LENGTH = 2` — filters single-char noise ("E", "P")
- Added punctuation-only filter — entities with no letters/digits discarded
- Applied in `buildEntity()`, the single exit point for all decoded entities

**Boost tuning results (filtered entities, clean A/B on same index):**

| Mode | Boost=0 | Boost=0.5 | Boost=2.0 (pre-filter) |
|------|---------|-----------|----------------------|
| lexical nDCG@10 | 0.8250 | 0.8250 (0%) | 0.7905 (-4.3%) |
| lexical P@1 | 0.7133 | 0.7133 (0%) | 0.6433 (-10.2%) |
| bm25_splade nDCG@10 | 0.8250 | 0.8065 (-2.2%) | — |

**Conclusion:** Entity filtering eliminated the catastrophic regression
(from -4.3% to 0% on lexical), but entity boost provides no measurable
benefit at any tested level. Boost=0.5 is neutral on lexical, still
harmful on bm25_splade. **Default set to 0.0** (disabled).

**Why entity boost doesn't help:** Entity text fields contain the same
tokens as the content field — they're populated from NER-extracted names
that already appear in the document text. The DMQ tie-breaker (10% of
secondary field score) is too small to reorder documents. For entity
boost to add value, the entity fields need to contain tokens NOT in the
content field — this is what Phase 4 (cluster expansion) provides by
adding variant forms ("Jim" → "James", "GE" → "General Electric").

**Pipeline timing (full run with filtered entities):**
- Primary indexing: 48s (115 docs/sec)
- Embedding 100%: t=660s
- SPLADE 100%: t=1021s
- NER complete: t=1217s
- Chunk vectors 100%: t=1590s
- Total pipeline: 26.5 min

### Per-query analysis — DONE (session 80e11c82)

Compared current run (entity filtering + boost=0, chunk merge active)
against 309 baseline (no entity boost, no chunk merge).

**R@10=0 changes:**
- 309 baseline: 27 failures (lexical mode)
- Current: 23 failures (net -4)
- Recovered: 6 (q_37, q_80, q_114, q_174, q_268, q_274)
- New failures: 2 (q_8, q_180)
- Still failing: 21

**Root cause of all variance: chunk merge.**
- 309 baseline: chunkMerge=False on all 300 queries
- Current: chunkMerge=True on all 300 queries
- Entity boost is 0 — has zero contribution to any change

**New failures (chunk merge regressions):**
- q_8 ("FERC judge's findings"): hits 1089→67, relevant doc reranked
  out by chunk-level scoring. Was at nDCG=0.315 (rank ~5-6).
- q_180 ("Dabhol Power Co."): hits 1179→53, same pattern. Was at
  nDCG=0.356 (rank ~4-5).

Both queries had the relevant doc borderline in the top-10 via whole-doc
BM25. Chunk-level scoring is more selective (narrower hit count) and
reranked them out. These are chunk merge quality issues, not entity
boost issues.

**Per-query summary:**
- 27 queries improved (mostly chunk merge helping precision)
- 12 queries regressed (chunk merge hurting recall for broad queries)
- 261 unchanged
- All 12 regressions have chunkMerge=True, none are entity-related

**Conclusion:** The R@10=0 variance is entirely from chunk merge
activation, not entity boost. Chunk merge is net positive (27 improved
vs 12 regressed) but introduces 2 new retrieval failures. These should
be investigated under chunk merge quality work, not entity retrieval.

### Search quality register updated — DONE

Added F-010 (entity boost doesn't help), F-011 (NER model quality sound),
Q-006 (chunk merge quality question), updated FW-003 (per-query analysis).

### Corpus analysis: the 21 failures are NOT entity-variant issues (2026-03-23)

Deep investigation of all 21 remaining R@10=0 queries revealed that
**every key entity in every failing query appears verbatim in the
relevant document**. Zero are entity-variant mismatches.

Common pattern: entity names appear in email metadata format
("jeff.dasovich@enron.com" in Sender/Recipients) but the query uses
the human form ("Jeff Dasovich"). BM25 on the `content` field can
partially match ("Jeff", "Dasovich" as separate tokens from the email
address), but long verbose queries (20-40 words) dilute precision.

**Root causes of the 21 failures (not entity variants):**
1. **Verbose query dilution** — long QA questions contain many common
   terms that match many documents, pushing the relevant doc below rank 10
2. **Metadata not in content** — entity names in Sender/Recipients
   headers appear as email addresses, not as searchable name tokens
3. **Document length** — some relevant docs are 50KB+ news digests
   where the answer is buried in one paragraph among many articles

**Consequence: Phase 4 (cluster expansion) would be a NO-OP** on all
21 failures. The disambiguation pipeline also has no useful clusters —
cluster store is empty (disambiguation doesn't complete in eval runs
because pipeline readiness doesn't wait for it).

**What would actually help these 21 queries:**
- Metadata field searching (query "Jeff Dasovich" matches sender field)
- Query reduction / key-term extraction (strip verbose QA framing)
- Document sectioning (break 50KB digests into individual articles)
- SPLADE (already helps 6 queries recover in bm25_splade mode)

### Phase D per-query reanalysis (2026-03-28, session 343)

Reran per-query analysis with the full multilingual model stack (all 5
model swaps, SPLADE FP16 GPU rebuild). Git 5d19ff2c1.

**Original 22 R@10=0 failures — CE-off lexical mode:**
- 14 recovered (same as Phase 3 finding — title boost fix + chunk merge)
- 8 still failing: q_126, q_145, q_159, q_189, q_190, q_205, q_210, q_230
- 14 new failures (all chunk merge regressions, `chunkMergeApplied=True`)
- Net R@10=0 count: 22 (unchanged from 309 baseline)

**CE damage analysis (lexical mode, CE-on vs CE-off):**
- 16 queries killed by CE (R@10>0 → R@10=0), including 7 with nDCG=1.0
- 4 queries recovered by CE
- Net: +12 R@10=0 failures from CE alone
- Per-query: 23 improved, 16 degraded, 261 unchanged (mean: −0.028 nDCG)

**Dense rescue:** Only 3/22 total failures rescued by full mode (q_261,
q_272, q_9). Dense adds minimal value on email.

**Model swap impact on EnronQA:** Zero. CE-off post-swap matches pre-swap
exactly (0.827/0.813/0.822). The failure landscape is unchanged — same
root causes (verbose query dilution, chunk merge regressions).

**Conclusion:** Model swaps don't change the EnronQA failure landscape.
The three actionable items for EnronQA quality are:
1. **FW-001: Corpus-adaptive CE gating** — CE kills 16 queries on email,
   confirmed by Phase D isolation (−5.4% nDCG full mode)
2. **Chunk merge tuning** — 14 new R@10=0 regressions, all from chunk
   merge reranking borderline docs out of top-10
3. **Query reduction** — 8 persistent failures are verbose-query-dilution
   (50-70 BM25 hits, relevant doc below rank 10). Key-term extraction
   would help.

Runs: `20260328T135826_mixed_enron-qa` (CE-off),
`20260328T133319_mixed_enron-qa` (CE-on).

### Done (session 2026-04-06)
- [x] Verify entity facet quality via API after MIN_ENTITY_LENGTH filter
- [x] Expose entity facets through MCP agent interface
- [x] Complete 358 NER model swap in code (javadocs, comments, build script, dev fallback path)

**Entity facet quality verification (2026-04-06):**
SciFact dataset (5189 docs), NER completed on all 7303 docs (including chunks).
Entity facets return real values via API:
- Organizations: "World Health Organization" (20), "American Cancer Society" (7),
  "Cochrane Library" (14), "PubMed" (10) — all correct
- Variant grouping works: "EMBASE" (16) + "Embase" (15) → canonical "Embase" (31)
- MIN_ENTITY_LENGTH filter active: no single-char entities
- Expected noise on scientific text: section headers ("METHODS", "BACKGROUND")
  misclassified as ORG, gene names ("FoxA") as PER — model domain mismatch,
  not a pipeline bug

**MCP entity facet exposure (2026-04-06):**
Added `entity_persons_raw`, `entity_organizations_raw`, `entity_locations_raw`
to both the `facets: true` shorthand and the auto-include default in
`server.mjs`. Also forwarding `entityFacetVariants` (canonical→raw variant
breakdown) in the MCP response. Entity facets now appear in MCP search
responses on first page. Same constraint as metadata facets: only computed
in TEXT mode (pre-existing Lucene limitation, not entity-specific).

**NER post-processing quality fixes (2026-04-06):**
Three fixes implemented to reduce entity facet noise:

1. **Confidence threshold raised 0.5→0.7** (`ResolvedConfigBuilder.java:1008`).
   Eliminates low-confidence predictions. On SciFact: "FoxA", "Müller" dropped
   from PER facets; some counts decreased (WHO 14→12, Spemann 3→2).

2. **Section header blocklist** (28 terms in `BioTagDecoder.SECTION_HEADER_BLOCKLIST`).
   Eliminates ALL-CAPS section headers misclassified as ORG: METHODS (44),
   BACKGROUND (10), DESIGN (8), OBJECTIVE (9), AIM (9), AIMS (10), AUTHORS (5).
   Case-insensitive matching in `buildEntity()`.

3. **Minimum alphabetic character filter** (`MIN_ALPHA_CHARS = 3`).
   Eliminates initial fragments: "R ." (2), "D . J" (2), "K ." (2), "J ." (2),
   "S ." (4). Requires ≥3 alphabetic characters (not counting spaces/dots).

**Remaining noise (model domain mismatch, not fixable with post-processing):**
- Biological organisms as LOC: "Escherichia", "Drosophila", "Saccharomyces"
- Gene/protein names as PER: "Noggin", "Tenectin", "Scrib"
- Disease eponyms as PER: "Crohn", "Hodgkin", "Kaposi" (technically correct)
- Scientific acronyms as ORG: "NSCLC", "CSC", "CAF", "BDNF"
These are inherent to running a news/Wikipedia-trained NER model on biomedical
text. On the actual target domain (email/business documents), these issues
largely disappear.

**Multi-valued stored field fix (2026-04-06):**
`SearchResultFormatter.extractFromStoredFields()` and `extractFromDocument()`
used `HashMap.put()` which overwrites previous values for multi-valued fields.
Fixed to use `Map.merge()` with ` | ` separator. Pre-existing bug — affected
all multi-valued stored fields, not just entity fields. Facets/filters were
unaffected (DocValues-based). Validated: neonatal mortality doc now shows
6 locations in `entity_locations_raw` where before only 1 was visible.

**Word-level section header filter (2026-04-06):**
Replaced exact-match blocklist with `stripLeadingHeaderWords()`: splits entity
into words, strips leading blocklisted words + stopwords + punctuation-only
tokens. Handles three cases:
- Pure headers: "METHODS AND FINDINGS" → all words blocklisted → rejected
- Contaminated spans: "DATA SOURCES Medline Embase Cochrane Library" →
  header prefix stripped → "Medline Embase Cochrane Library"
- Punctuation-prefixed: "& AIMS" → `&` skipped (punctuation), `AIMS`
  blocklisted → rejected

Added `DATA`, `SOURCES`, `FUNDING` to the header word set. Added stopwords
set (AND, OF, THE, etc.) for compound header recognition.

Validated on SciFact: zero section header words in org facets. "DATA SOURCES
Medline Embase Cochrane Library" correctly stripped to "Medline Embase
Cochrane Library" with all 3 values visible in `_raw` field.

**358 NER model swap — code alignment (2026-04-06):**
Updated 7 files to reflect the Davlan multilingual model already on disk:
`BertNerInference.java` (javadoc), `BioTagDecoder.java` (javadoc, comments),
`NerService.java` (javadoc, log message), `NerModelDiscovery.java` (dev
fallback path), `build-ner.py` (default --hf-model, docstring),
`BioTagDecoderTest.java` (comment), `NerModelDiscoveryTest.java` (dev paths).
All worker-core + configuration tests pass. No behavioral changes.

### Revised expectations for eval

On reflection, entity boost provides a **marginal ranking signal**, not
a retrieval expansion. The entity text fields contain the same tokens as
the content field — just in a shorter field (higher TF). For the 22
R@10=0 queries, the relevant document already fails to reach top-10 via
content BM25. The entity field adds a small DMQ tie-breaker boost, but
the content field dominates scoring. This is unlikely to swing a document
from rank 50+ into top-10.

**Entity boost is more likely to help:**
- The 45 queries where CE hurts (rank swaps within top-10) — entity
  signal may counteract CE's semantic-similarity bias
- Overall nDCG improvement from concentrated entity matching
- P@1 on queries where the correct doc is #2-3 and entity match pushes
  it to #1

**For the 22 complete failures, the real fix is:**
- Phase 4: cluster-expanded free-text search (variant matching)
- Phase 5: date/email entity extraction
- Or: entity as a hard SHOULD clause (not just DMQ tie-breaker)

Revised estimate: **0–3 of 22 R@10=0 recover** from entity boost alone.
The `searchTextWithFilter` title boost fix may have more impact on
overall nDCG than entity boost.

### Impact of `searchTextWithFilter` fix on existing baselines

The fix to `searchTextWithFilter` restores 306-B1 title boost to the
2-leg hybrid path. This changes scoring for **all** 2-leg hybrid queries
(not just entity queries). The existing EnronQA baselines used
`bm25_splade` (3-way) and `lexical` (sparse-only), both of which already
had title boost. The `full` mode baseline used `searchHybridFiltered`
for the BM25 leg — so the `full` mode baseline numbers may change after
this fix. Re-running the baselines after merge is recommended to quantify
the delta.

---

## Context update (2026-04-06, added by tempdoc 366 session)

### Why this matters now — agent reflection data

Tempdoc 366 ran 100+ agent eval queries (50q Phase 4, 50q Phase 5) with
the 4-tool MCP surface. Bullet-level reflection analysis (93 classified
bullets) found **entity-level facets are the #1 agent request** (29/93).

Concrete agent quotes:
- "Entity extraction / faceted search — filter by entities rather than
  just keyword matching"
- "A `listFacets(field='meta_source')` call returning all unique values
  with counts"
- "Which companies appear most frequently across documents matching
  this query?"
- "Count-by-entity mode — surface entity co-occurrences across docs"

The complaint profile shifted from operational friction (filter mismatches,
tool confusion — now fixed) to aspirational capabilities. Entity facets
are the top unmet need. The infrastructure is built (185) but dormant.

### Model status — multilingual model already on disk

The model at `models/onnx/ner/` is **already the 358 upgrade target**:
`Davlan/distilbert-base-multilingual-cased-ner-hrl` (~134M params,
mDistilBERT, 10 languages). Confirmed via `config.json`:

```json
"_name_or_path": "Davlan/distilbert-base-multilingual-cased-ner-hrl",
"id2label": {"0":"O","1":"B-DATE","2":"I-DATE","3":"B-PER","4":"I-PER",
             "5":"B-ORG","6":"I-ORG","7":"B-LOC","8":"I-LOC"}
```

**Label compatibility:** `BioTagDecoder.entityType()` maps PER→person,
ORG→organization, LOC→location, and returns null for everything else.
The DATE labels are filtered out (same as MISC was). If date entity
extraction is wanted later (Phase 5), it's a one-line change in
`entityType()` + new SSOT fields.

**Label mapping is dynamic:** `NerService.loadLabelMapping()` reads
`config.json` id2label at model init time, so the label index ordering
difference (old: 1=B-MISC, new: 1=B-DATE) is handled automatically.

**What 358 still needs in code:** The tempdoc 358 item
`[ ] Swap NER to distilbert-base-multilingual-cased-ner-hrl` may refer
to code-level changes beyond just the model file. Check if
`BertNerInference` javadoc or `NerService` hardcodes the old model name.
The model file swap itself is already done.

### MCP wiring — partially exists

The MCP schemas already accept entity filters:
- `schemas.mjs`: `entity_persons`, `entity_organizations` in both
  `filters` and `boostFilters` for search and answer tools
- `server.mjs` description: `'Example: {meta_source: ["the verge"],
  entity_persons: ["Elon Musk"]}'`
- `SearchOrchestrator`: `expandEntityFilters()` expands filter values
  via disambiguation clusters

**What's missing for agents:**
1. Entity facets are NOT returned in MCP search responses (only metadata
   facets like `meta_source` are surfaced)
2. Entity facet values are not in the `cachedFacetSnapshot` used by
   filter normalization (366) and QU (363)
3. Without seeing entity facet values in search results, agents have no
   way to discover valid `entity_persons` filter values

### Facet snapshot bug fix (from 366)

`KnowledgeHttpApiAdapter.refreshFacetSnapshotIfStale()` was using
`query="*"` which the Worker's QueryParser rejected (leading wildcard
restriction). **Fixed to `"*:*"`** (Lucene match-all syntax). This
directly affects entity facet discovery — the same gRPC facet query
retrieves both metadata and entity facets.

### Settled findings from this tempdoc

These are proven conclusions the new agent should NOT re-investigate:

1. **Entity boost via DMQ is a dead end.** Entity text fields contain
   the same tokens as the content field → zero ranking improvement.
   Proven across 5 A/B isolation runs with boost values 0, 0.5, 2.0,
   100. Default is 0 (disabled). Only becomes useful when entity fields
   contain variant tokens NOT in the content field (Phase 4).

2. **NER model quality is sound.** CoNLL-2003 F1=0.863 (test),
   0.908 (validation), within 1.4pp of published benchmark. The boost
   failure is architectural, not model quality.

3. **The 21 remaining R@10=0 failures are NOT entity-variant issues.**
   Every key entity appears verbatim in the relevant document. Root
   causes are verbose query dilution, chunk merge regressions, and
   metadata not in content field.

4. **Phase 4 (cluster expansion) would be a NO-OP** on all 21 failures.
   Disambiguation clusters are empty in eval runs (pipeline readiness
   doesn't wait for disambiguation).

5. **Entity filtering fixed the catastrophic regression.** MIN_ENTITY_LENGTH=2
   + punctuation filter eliminates single-char noise. Boost=0.5 is
   neutral (not harmful) with filtering.

### Remaining value of entity activation

Given that boost is a dead end and cluster expansion is a no-op on
EnronQA, the remaining value of NER activation is:

1. **Entity facets for agents (this is the big one).** 29/93 agent
   reflection bullets request entity-level filtering. The infrastructure
   is built — it just needs activation and MCP surfacing.
2. **Entity facets in the desktop UI.** Already wired (SearchFiltersBar.tsx),
   gated on 10% NER coverage. Activating NER populates them.
3. **Entity filtering in search.** Agents can already pass
   `entity_persons: ["Elon Musk"]` in filters — it just doesn't match
   anything because no documents have entity data.

The path to value is: **activate NER → surface entity facets in MCP
responses → agents discover and use entity filters.** No boost tuning
or cluster expansion needed for the primary use case.

---

## Pre-implementation investigation (2026-04-06, new session)

### Technology currency audit

All NER pipeline dependencies are current and correctly used:

| Component | Version/State | Verdict |
|-----------|--------------|---------|
| ONNX Runtime Java | 1.24.3 (latest Maven artifact) | Current, no deprecations |
| ORT API patterns | `OrtSession`/`OnnxTensor.createTensor(LongBuffer)` | Correct, stable since 1.11 |
| HuggingFace Optimum export | `optimum-onnx` is new package name | N/A — pre-built model, no runtime export |
| BioTagDecoder subword aggregation | wordId-based (first subword owns tag) | Correct — implements `aggregation_strategy="first"` (not "simple" as prior docs stated). "First" is the better production choice per HF community |
| SoftTFIDF disambiguation | Cohen et al. KDD 2003 | Appropriate for KB-free name clustering |
| DisjunctionMaxQuery for multi-field | Core Lucene, standard "best_fields" | Correct construct for heterogeneous fields |
| NER model (`Davlan/distilbert-multilingual-ner-hrl`) | ~134M params, 10 langs | Adequate; `xlm-roberta-base-ner-hrl` is upgrade path if quality matters |

**Entity boost dead-end confirmed by literature:**
- Balog, *Entity-Oriented Search* (Springer 2018): multi-field entity variants "could not really improve over single-field" when content overlaps
- mFAR (Microsoft Research, ICLR 2025): "vast majority of gains come from query conditioning; without it, multiple fields alone do not amount to much"
- Blanco & Zaragoza BM25MF (ECIR 2012): gains collapse when attribute content overlaps with main text field

### Item analysis: Verify entity facet quality in UI

**Runtime prerequisites confirmed:**
- NER model files present at `models/onnx/ner/`: `model.onnx` (INT8), `model_fp16.onnx` (FP16), `tokenizer.json`, `config.json` (Davlan id2label)
- NER auto-enables when model is found (`NerConfig.isReady()` checks `enabled && modelPath != null`; enabled defaults to auto-discover)
- Frontend gates entity facets on `completedNerCount / indexedDocuments >= 0.10` in `SearchFiltersBar.tsx:530`
- Status API exposes `pendingNerCount` and `completedNerCount` via `WorkerOperationalView.EnrichmentProgressView`

**Procedure:** Start dev stack → index corpus → wait for NER backfill (10%+ coverage) → check entity facets appear in `SearchFiltersBar` → verify facet values are real names (not fragments).

### Item analysis: Expose entity facets through MCP agent interface

**Full pipeline traced — bottleneck identified:**

| Layer | Entity facet support |
|-------|---------------------|
| Proto/gRPC (`FacetSpec`) | Fully supported, field-agnostic |
| `FacetingEngine` | Fully supported, DocValues-based |
| `SearchOrchestrator` | Computed only in TEXT (sparse-only) path; merged via `mergeEntityFacets` |
| `DelegatingSearchService` | Pass-through |
| `KnowledgeHttpApiAdapter` | Pass-through — entity facets appear in HTTP JSON if requested |
| **MCP `server.mjs`** | **Never requests entity fields** in `facets: true` or auto-include defaults |
| MCP `SearchOutputSchema` | Generic `z.record()` — entity facets would validate if present |
| MCP response handler | `entityFacetVariants` not forwarded (no schema field) |

**Root cause:** `server.mjs` lines 544–563 hardcode `meta_source`, `meta_category`, `meta_author` as the default/auto-include facet fields. Entity fields are never requested.

**Fix (minimal, ~10 lines in `server.mjs`):**
1. Add `entity_persons_raw`, `entity_organizations_raw`, `entity_locations_raw` to `facets: true` shorthand (line 546–550)
2. Add same to auto-include block (lines 558–563)
3. Optionally: forward `entityFacetVariants` in response handler

**Facets-in-hybrid-mode constraint:** Facets are only computed in the sparse-only path (`SearchOrchestrator` line 463). This affects ALL facets (metadata + entity), not just entity facets. The existing MCP auto-include for `meta_source` etc. has the same constraint. Research confirms this is a known Lucene limitation — ES/OpenSearch/Vespa compute facets on merged hybrid results natively, but Lucene-based systems restrict to the sparse path. For agents, the recommended pattern is separate facet requests if needed, but since the constraint is pre-existing and affects all facets equally, no special handling is needed for entity facets.

**`cachedFacetSnapshot` already includes entity facets:** The `refreshFacetSnapshotIfStale()` method (line 1203) requests `entity_persons_raw` (top 30) and `entity_organizations_raw` (top 30) via a `*:*` TEXT query. So QU/filter normalization already sees entity values. Note: `entity_locations_raw` is NOT in the snapshot.

### Item analysis: Complete 358 NER model swap in code

**Tempdoc 358 (`358-pipeline-model-selection.md`) specifies:**
- `[ ] Swap NER to distilbert-base-multilingual-cased-ner-hrl (drop-in)` (line 742)
- Rated "Easy — same DistilBERT arch, same label format" (line 724)

**Model is already on disk.** `config.json` confirms `Davlan/distilbert-base-multilingual-cased-ner-hrl` with `id2label: {0:O, 1:B-DATE, 2:I-DATE, 3:B-PER, 4:I-PER, 5:B-ORG, 6:I-ORG, 7:B-LOC, 8:I-LOC}`.

**Runtime label mapping works correctly.** `NerService.loadLabelMapping()` reads `config.json` id2label → `LabelMapping.fromId2Label()`. The hardcoded fallback `bertBaseNer()` is never reached when config.json exists. Even if it were, PER/ORG/LOC indices (3/5/7) coincidentally align between old and new schemas — only DATE (indices 1-2) differs, and DATE is discarded by `entityType()` returning null.

**Code changes needed (all cosmetic/documentation):**

| File | Line(s) | Change |
|------|---------|--------|
| `BertNerInference.java` | 28 | Update javadoc: remove `dslim/bert-base-NER` and `dslim/distilbert-NER` references |
| `BioTagDecoder.java` | 11, 23, 38 | Update javadoc/comments: remove dslim references, rename `bertBaseNer()` method |
| `NerService.java` | 260, 265 | Update javadoc and log message: `bert-base-NER` → generic or Davlan reference |
| `NerModelDiscovery.java` | 21 | Update dev fallback path: `"ner/bert-base-NER-onnx"` → `"ner/distilbert-multilingual-ner-hrl"` |
| `build-ner.py` | 5, 17, 51 | Update default `--hf-model` and docstring examples to Davlan/Xenova repos |
| `BioTagDecoderTest.java` | 14 | Update comment: "Label indices for dslim/bert-base-NER" |
| `NerModelDiscoveryTest.java` | 111, 136 | Update dev-fallback path in test assertions |

**No test breakage expected.** All decode tests use synthetic logits with explicit mappings. The integration test `nerMultilingualCloseSession()` already uses the multilingual model path.

**SSOT catalog drift found (pre-existing, not caused by 326):** `ner_status` has `stored: true` in root catalog but `stored: false` in classpath copy. `ner_retry_count` has `roles: ["filter", "sort"]` in root but `roles: ["filter"]` in classpath. These should be synced but are outside 326 scope.
