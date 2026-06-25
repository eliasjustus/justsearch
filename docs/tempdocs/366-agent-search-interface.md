---
title: "366: Agent Search Interface"
status: done
created: 2026-03-28
depends_on: [362, 363]
---

# 366: Agent Search Interface

## Purpose

Improve the explicit, interactive path for agents using JustSearch
MCP tools. This tempdoc owns everything the agent sees and controls:
tool schemas, response formats, facet discovery, filter feedback,
and response hints.

**Not in scope:** The transparent/automatic query understanding
layer (363's domain). When an agent sends a plain query with no
filters, 363's QU layer handles it server-side. 366 handles the
case where an agent deliberately constructs filters, requests
facets, or needs guidance from responses.

**Boundary with 363:** The search API is the convergence point.
A request can carry `filters` (hard, agent-constructed, 366) and
`boostFilters` (soft, QU-extracted, 363). They compose as
BooleanQuery clauses: MUST (content) + FILTER (hard) + SHOULD
(boost). Neither tempdoc touches the other's mechanism.

---

## Implementation status

### Phase 1 — Complete

- [x] **1c.** Standardize filter names to snake_case
- [x] **1a.** Auto-include top-5 facets on first search
- [x] **1b.** Echo `appliedFilters` when filters active
- [x] **1d.** Expose `boostFilters` in search schema
- [x] **2a.** Response hints (zero results, high hit count, low coverage)

### Phase 2 — Complete (except 2f → 363)

- [x] **2b.** Chunk content_preview fallback to `chunk_content`
- [x] **2c.** Expose `includeExcerpts` for query-biased snippets
- [x] **2d.** Document LUCENE query syntax in tool description
- [x] **2e.** Preview path normalization (backslash → forward)
- [x] **2g.** Explicit `facets` parameter in search schema
- [x] **2h.** Enrichment coverage in status response
- **2f. Null query confidence → handed to 363.** QPP signals
  can't discriminate null queries (scores overlap 0.70-1.0).
  Google ICLR 2025 "Sufficient Context" paper shows the solution
  is post-retrieval context sufficiency checking, not retrieval
  signals. This is 363's transparent layer domain.

### Phase 3 — 363 convergence (disabled, theorize later)

**Status: Implemented, gated behind `JUSTSEARCH_QU_ENABLED=true`
(disabled by default).** The infrastructure exists and was
validated once, but stays off until the LLM resource scheduling
problem is resolved and the pattern is further theorized.

Enable via jseval: `python -m jseval run --start-backend --llm --qu ...`
Enable manually: set `JUSTSEARCH_QU_ENABLED=true` env var.

- [x] **3a.** Surface QU metadata in responses. Added
  `QueryUnderstanding` sub-record to `KnowledgeSearchResponse`
  with `appliedBoosts` map and `latencyMs`. Wired from
  `QuResult` in adapter, surfaced in controller JSON output,
  passed through as `queryUnderstanding` in MCP search response.

- [x] **3b.** Document two-LLM interaction protocol. Search
  description states: "The system automatically detects
  sources, authors, and entities in your query and applies
  soft boosts — check the queryUnderstanding field in the
  response to see what was detected. Providing explicit
  filters or boostFilters overrides automatic detection."

  **Validated once (2026-03-30):** `queryUnderstanding` appeared
  in the response when QU extracted filters:
  `{"appliedBoosts":{"meta_source":["the verge"]},"latencyMs":5616}`.
  QU deadline raised from 2s→8s to account for LLM queueing
  (expansion and QU compete for same llama-server slot).

**Why disabled:** QU and query expansion both fire at search
start and compete for the same single-slot llama-server. With
expansion taking the slot first, QU queues for 3-4s, causing
search latency to suffer. The 8s deadline workaround makes
searches too slow. The real fix (coordinated LLM scheduling,
priority queueing, or cancelling expansion when QU is pending)
is not yet implemented.

**Research finding (2026-03-30):** The two-LLM interaction
pattern — surfacing inner-LLM query interpretation as a
first-class coordination signal for the outer agent — is not
yet a documented industry pattern. The closest precedents are:
- **Typesense** `parsed_nl_query.generated_params` — same
  structure, but framed as developer debugging, not agent
  coordination.
- **Azure AI Search** agentic retrieval `activity` array —
  most complete metadata surfacing, but framed as
  billing/operational transparency.
- **Vectara** intelligent query rewriting — mentions "essential
  for agentic workflows" but sparse on response field details.

No source documents the specific combination of: inner LLM
interpretation surfaced as first-class MCP response metadata,
explicitly intended for outer-agent strategy adjustment, with
the protocol documented in the tool description. Worth further
theorization as a design pattern when the scheduling problem
is resolved and the feature can be properly evaluated.

### Phase 4 — Tool consolidation (redesign)

**Research finding (2026-03-29):** Industry consensus from
Block Engineering (30+ tools → 2), AWS Prescriptive Guidance,
and the "MCP Tool Descriptions Are Smelly" paper (arXiv
2602.14878): fewer task-oriented tools outperform many
capability-oriented tools. Our 7 tools map to API endpoints,
not user workflows. Agents spend turns figuring out which tools
to compose instead of solving the problem.

**Evidence from 50q eval:** 41/50 agents used the slow
search → preview loop (70% accuracy). 4/50 discovered
retrieve_context (100% accuracy, fewer turns, lower cost).
Position bias research shows agents prefer the first-listed
tool by 9.51% (ToolTweak, arXiv 2510.02554).

**Target tool surface:**

| Tool | Purpose | Maps to current |
|------|---------|-----------------|
| `justsearch_answer` | Get evidence to answer a question | retrieve_context + match_citations |
| `justsearch_search` | Find and explore documents | search + facets |
| `justsearch_ingest` | Index files into JustSearch | ingest (unchanged) |
| `justsearch_status` | Check index health and enrichment | status (unchanged) |

**Removed:** `preview` (agents have file access via Read),
`suggest` (2/50 usage, dead for QA), `match_citations`
(0/50 usage, becomes optional on `answer`).

- [x] **4a. Implement consolidated tool surface.** Renamed
  `retrieve_context` → `answer` (registered first for position
  bias), absorbed `match_citations` as `verify_citations` param.
  Removed `preview`, `suggest`. Updated descriptions to guide
  agents toward `answer` for QA tasks.

- [x] **4b. Type output schemas per MCP spec.**
  Defined `SearchHitSchema`, `ChunkSchema`, `QualitySchema`,
  `FacetsSchema`. Replaced `z.any()` in SearchOutputSchema
  and AnswerOutputSchema.

- [x] **4c. Error recovery hints.**
  NOT_CONNECTED → "Ensure the desktop app is open."
  NOT_READY → "Wait 30 seconds and retry."
  RESPONSE_TOO_LARGE → "Try reducing the limit parameter."
  HTTP_ERROR → "Try again."

- [x] **4d. Token efficiency measurement.**
  10-result search: raw backend 79,608 chars (~19,902 tokens).
  Pre-366 MCP slim: 6,458 chars (~1,615 tokens, 8% of raw).
  Post-enrichment: 6,995 chars (~1,749 tokens, +8% overhead).
  Facets add 319 chars. Negligible cost for the information
  provided.

- [x] **4e. Post-consolidation eval (50q Haiku).**
  Analyzed from Phase 4 (50q, 4-tool) and Phase 5 (50q,
  4-tool + hybrid norm) eval data.

  **Answer-first adoption: 38% (19/50).** Both Phase 4 and
  Phase 5 show identical 19/50 split. Target of >80% not met.
  Answer-first agents (<=4 turns) achieve 84% accuracy at
  $0.023 avg cost. Search-explore agents (>4 turns) achieve
  94% accuracy at $0.069 avg cost — higher accuracy but 3x
  the cost.

  **By question type:**
  - inference_query: 9/15 answer-first (60%), 15/15 correct
  - temporal_query: 5/11 answer-first (45%), 9/11 correct
  - comparison_query: 4/15 answer-first (27%), 13/15 correct
  - null_query: 1/9 answer-first (11%), 8/9 correct

  Answer-first works well for inference queries (single-hop
  factual). Comparison and null queries inherently need more
  turns (multiple sources, exhaustive search). The 38%
  adoption is not a failure — it reflects that 62% of queries
  genuinely benefit from multi-turn exploration.

  **Phase 5 reflection themes (bullet-level, 93 classified):**

  | Theme | Count | Status |
  |-------|-------|--------|
  | Facet/entity discovery | 29 | Agents want entity-level facets, not just metadata fields. "Which companies appear most frequently?" |
  | Multi-hop/cross-document | 19 | "Find entities mentioned by BOTH source A and source B." Agent strategy, not tool issue. |
  | Full document retrieval | 17 | "Retrieve and rank whole documents rather than chunks." Want doc-level view alongside chunks. |
  | Exact/keyword search | 10 | "Search for 'personal gain' within article_030.md only." Want scoped literal match. |
  | Snippet highlighting | 5 | Want query term highlighting in returned chunks. |
  | Confidence calibration | 5 | `context_sufficient: false` but results were good. Signal not trusted. |
  | Date/temporal filtering | 2 | Down from 29 (Phase 2) — largely addressed. |
  | Filter value mismatch | 1 | Down from 19 (Phase 4) — hybrid normalization effective. |

  **Key shift from Phase 4:** Filter value mismatch dropped
  from 19 to 1 complaint. The hybrid normalization is working.
  The new top themes are aspirational (entity facets, cross-doc
  joins, full-doc retrieval) rather than friction-based — agents
  are hitting the ceiling of what a chunk-level search system
  can provide, not struggling with basic operations.

- [ ] **4f. Opus eval.**
  Test whether Opus discovers boostFilters, LUCENE syntax,
  includeExcerpts with the new tool surface.
  Needs user approval before running.

### Phase 5 — Filter value normalization (implemented)

- [x] **5a. Case normalization (Tier 1, always on).**
  Lowercased metadata filter values (`metaSource`, `metaAuthor`,
  `metaCategory`) in `KnowledgeHttpApiAdapter` for both hard
  filters and boost filters. Matches index-time lowercasing in
  `IndexingDocumentOps.putIfNonBlankLower()`.

- [x] **5b. FilterNormalizationService (Tier 2, LLM-based).**
  New service that maps approximate filter values to exact indexed
  values using the LLM without grammar constraints (no
  `response_format`). Fires async with 3s deadline; falls back to
  Tier 1 on timeout. Short-circuits without LLM call when all
  values already match the facet vocabulary exactly.
  Gated behind `JUSTSEARCH_FILTER_NORM_ENABLED=true`.

- [x] **5c. Response metadata.**
  `FilterNormalization` record on `KnowledgeSearchResponse` with
  original/normalized values, latencyMs, and source (llm,
  case_only, exact_match, timeout). Surfaced in controller JSON
  and MCP server passthrough.

- [x] **5d. Env gating + jseval flag.**
  `JUSTSEARCH_FILTER_NORM_ENABLED` env var + `--filter-norm`
  jseval flag (requires `--llm`).

- [x] **5e. Unit tests.** 17 tests covering: facet snapshot
  parsing, exact-match short-circuit, case normalization,
  LLM abbreviation/multi-match/no-match normalization,
  hallucination rejection, timeout fallback, error fallback.

- [x] **5f. Manual validation with Brain.**

  **Root cause of slow LLM (investigation 2026-03-30):**
  llama-server was running in CPU-only mode (`-ngl 0`,
  the default). The 9B Q4_K_M model on CPU does ~5-10
  tok/s; on GPU (RTX 4070, full offload `-ngl 99`) it
  does ~40-80 tok/s. GPU offloading is the single fix.

  **Fix:** jseval `--llm` now sets `JUSTSEARCH_GPU_LAYERS=99`
  (full GPU offload) by default. Results with GPU:

  | Test | Latency | Result |
  |------|---------|--------|
  | CNBC | **428ms** | → `cnbc \| world business news leader` (3 hits) |
  | The Independent | **517ms** | → `the independent - sports, travel` (2 hits) |
  | FOX News | **1,169ms** | → `fox news` (partial, no variant expansion) |
  | Fortune (exact) | **388ms** | Should short-circuit but LLM fired (facet snapshot sparse) |
  | Bloomberg | **1,169ms** | → `bloomberg` (nonexistent, kept as-is) |

  **Bugs fixed during validation:**
  - `allMatch()` returned true on empty vocabulary, causing
    false short-circuit. Fixed to return false (forces LLM).
  - NORM_DEADLINE_MS raised from 3s → 8s (adapter overhead).

  **Investigated issues (2026-03-30):** All three open issues
  trace to a single root cause: the `cachedFacetSnapshot` in
  `KnowledgeHttpApiAdapter` is always empty. The
  `refreshFacetSnapshotIfStale()` gRPC facet query returns no
  facets from the Worker, despite the HTTP API returning them
  correctly. This is a **pre-existing infrastructure bug**
  (affects QU grounding too, not just filter normalization).

  Without vocabulary:
  - **Fortune not short-circuiting:** empty vocabulary = can't
    verify match = falls through to LLM (still works, just
    slower)
  - **FOX News partial match:** LLM has no vocabulary to
    ground against, returns `"fox news"` (identity) instead of
    expanding to all variants. No hallucination rejection
    either (empty vocab = accept all).
  - **Bloomberg not dropped:** Same — no vocabulary means
    no validation. LLM returns `"bloomberg"` instead of
    NO_MATCH.

  **Fix applied:** `refreshFacetSnapshotIfStale()` now resets
  the timestamp on empty results (allows retry on next search
  instead of caching emptiness for 5 minutes).

  **Root cause found and fixed (2026-03-30):** The facet
  query used `query="*"` with LUCENE syntax. The Worker's
  QueryParser has `setAllowLeadingWildcard(false)`, so `"*"`
  throws ParseException, caught by the adapter's generic
  handler, and the snapshot stays empty. Fix: `"*"` -> `"*:*"`
  (Lucene match-all syntax). Validated: fortune now
  short-circuits at 0ms, CNBC normalizes with vocabulary.

  **Identity mapping failures analyzed (2026-03-30):**
  FOX News, Bloomberg, The Independent return identity
  mappings. Research (arxiv:2511.09710, arxiv:2512.14754,
  dottxt.ai structured generation study) confirms this is
  NOT a model capability limitation — Qwen3.5-9B scores
  91.5 on IFEval. The failures are caused by:

  1. **Free-form generation** — identity mapping rates reach
     70% without structural constraints. Structured response
     formats reduce echoing to 9%.
  2. **Zero-shot prompting** — worst approach for 7-9B entity
     matching. 1-shot structured matches 8-shot unstructured.
  3. **Compositional output** — "list ALL matching values" is
     a multi-constraint task that drops to 34.8% reliable@10
     at 9B scale (arxiv:2512.14754).
  4. **Absence assertion** — small models can't make "not in
     vocabulary" decisions in free-form generation.

  **Solution: hybrid deterministic + LLM architecture.**
  Decompose the task:
  - **Deterministic prefix/substring match** (microseconds):
    handles variant expansion (FOX News → all fox news-*),
    exact match (fortune → fortune), and absence detection
    (Bloomberg → no candidates → NO_MATCH). Eliminates LLM
    for 80% of cases.
  - **LLM with grammar-constrained enum** (only for semantic
    matches like CBS Sports → cbssports.com where no string
    algorithm works): flat enum grammar has negligible
    overhead unlike complex JSON schemas.

  See §5h below for implementation plan.

- [x] **5h. Hybrid normalization architecture.**
  Refactor `FilterNormalizationService.normalize()`:

  **Step 1 — Deterministic matching (always, 0ms):**
  For each filter value, try against the vocabulary:
  (a) exact match → use it
  (b) prefix match (`value.startsWith(input)`) → collect all
  (c) contains match (input ≥ 3 chars) → collect all
  If any matches found → use them, skip LLM.

  **Step 2 — LLM fallback (only when deterministic fails):**
  For values with 0 deterministic matches, call the LLM with:
  - Grammar-constrained enum (vocabulary values + NO_MATCH)
  - Few-shot examples (3: abbreviation, prefix, NO_MATCH)
  - Low temperature (0.1)
  This handles semantic matches (CBS Sports → cbssports.com).

  **Step 3 — Validation:**
  LLM output constrained to enum → always valid by
  construction. NO_MATCH → drop the filter value.

- [x] **5i. Hybrid validation (2026-03-31).**

  | Test | Source | Latency | Hits | Before |
  |------|--------|---------|------|--------|
  | fortune | exact_match | 0ms | 63 | 0 (case) |
  | Fortune | exact_match | 0ms | 25 | 0 (case) |
  | CNBC | deterministic | 0ms | 3 | 0 |
  | FOX News | deterministic | 0ms | 45 | 0 |
  | The Independent | deterministic | 0ms | 71 | 0 |
  | CBS Sports | hybrid (LLM) | 675ms | 0 | 0 |
  | Bloomberg | hybrid (LLM) | 1235ms | 0* | 0 |
  | techcrunch | exact_match | 0ms | 46 | 46 |

  6/8 perfect. FOX News and The Independent — the two cases
  that motivated the entire feature — now resolve at 0ms with
  full variant expansion. CBS Sports semantic gap still needs
  a more capable model. Bloomberg correctly gets NO_MATCH
  from the LLM (empty normalized → filter dropped → graceful
  degradation).

  **Impact:** The deterministic tier handles all cases that
  the LLM previously failed on (prefix expansion, variant
  listing). The LLM is now reserved for semantic gaps only.
  Zero latency added to 6/8 test cases.

- [x] **5j. 50q eval with hybrid normalization (2026-03-31).**

  | Metric | Phase 4 baseline | Phase 5 hybrid | Delta |
  |--------|-----------------|----------------|-------|
  | Accuracy | 92% | **91.8%** | -0.2pp (noise) |
  | Avg cost | $0.055 | **$0.053** | -4% |
  | Avg turns | 7.5 | **7.2** | -4% |
  | Avg duration | ~32s | **24s** | -25% |
  | Errors | 0 | 1 | MCP issue |
  | Total cost | ~$2.75 | **$2.57** | -7% |

  Accuracy maintained (within noise). Cost, turns, and
  duration all improved. The filter normalization does not
  regress quality while making filtered searches more
  reliable (FOX News: 0 → 45 hits, The Independent:
  0 → 71 hits, CNBC: 0 → 3 hits at 0ms latency).

### Phase 6 — Reflection-driven fixes (investigated 2026-04-06)

Phase 5 reflection analysis (93 classified bullets) surfaced four
actionable themes beyond entity facets (handled by 326). All four
were investigated for feasibility and existing infrastructure.

- [x] **6a. Snippet highlighting in MCP responses.**
  `HighlightingOps` already computes match spans via Lucene
  `MemoryIndex` + `Matches` API. `includeExcerpts: true` returns
  top-3 query-biased passages with character-offset `matchSpans`.
  `server.mjs` now renders matchSpans as inline `**bold**` markers
  in excerpt text via `renderExcerptHighlights()`. Overlapping
  spans are merged before insertion to prevent garbled output.
  Validated: backend returns 2-4 matchSpans per excerpt on news
  corpus. MCP function unit-tested with overlap edge cases.

  Known UI bug: `resultMapper.ts` reads `hit.excerpt_regions`
  (snake_case) but API emits `excerptRegions` (camelCase). Excerpt
  regions silently dropped in UI mapping. Separate fix needed.

- [x] **6b. Scoped keyword search (doc_ids + LUCENE syntax).**
  Added `doc_ids` (repeated string, field 15) to `SearchFilters`
  proto. Wired through `ProtoConverters` → `RuntimeSearchFilters`
  → `QueryFilterBuilder` (TermInSetQuery on PATH field) →
  `KnowledgeSearchRequest.Filters` → `KnowledgeSearchController`
  → `KnowledgeHttpApiAdapter`. MCP schema has `doc_ids` in
  filters (behind `.passthrough()` to avoid schema bloat).
  Path normalization applied on Windows (lowercase + separator).
  Validated: scoped search returns 1 hit, unscoped 252; LUCENE
  phrase + doc_ids works in combination.

- [x] **6c. Full document retrieval mode.**
  Added `return_full_documents` (bool, field 23) to
  `RetrieveContextRequest` proto. Threaded through
  `RetrieveContextParams` → `RetrieveContextController` →
  `SearchRpcOps` → `RagContextOps.executeRetrieval()`. When
  true, skips chunk search and calls
  `buildFallbackWithVirtualChunks` with
  `retrievalMode="FULL_DOCUMENT"` (not "FULLTEXT_FALLBACK").
  MCP schema has `return_full_documents` (behind `.passthrough()`).
  Validated: returns 18KB full-doc context vs 2.4KB chunks.

- [x] **6d. Confidence signal calibration.**
  Three fixes applied:
  (1) `sufficiency.v1.txt` rule 5: "when uncertain, respond
  false" → "when uncertain, respond true" (reduces false negs).
  (2) `RetrieveContextController`: always surfaces
  `context_sufficient` — as `null` on timeout/error/unavailable
  instead of silently omitting.
  (3) MCP coverage hint: gated on `chunks_included > 0` AND
  `retrieval_mode` not `FULL_DOCUMENT`/`FULLTEXT_FALLBACK`.
  Message changed to "context fill ratio" for clarity.
  Validated: `context_sufficient: null` correctly surfaced
  when LLM unavailable. Prompt change untested with actual LLM.

**Phase 6 implementation + smoke test (2026-04-06):**
All four fixes implemented and committed (`a96146f12`).
NER-enriched smoke test on MultiHop-RAG corpus (609 docs,
5214 chunks NER'd in 75s):

| Test | Result |
|------|--------|
| Entity facets in search | PASS — `{Elon Musk: 23, Google: 81, Apple: 62}` |
| Entity filter | PASS — `entity_organizations: ["Google"]` → 69 hits |
| Snippet highlighting | PASS — 2-4 matchSpans per excerpt |
| doc_ids + LUCENE | PASS — scoping works, phrase queries work |
| Full doc + confidence null | PASS — `FULL_DOCUMENT` mode, `context_sufficient: null` |

Entity facets only populate on broad queries (sparse-only path
limitation from 326). Narrow queries (< ~50 hits) return empty
entity facets. Not a regression — pre-existing.

See §Phase 6 eval below for schema bloat discovery.

### Phase 6 eval and schema bloat discovery (2026-04-06)

**First eval attempt (14/50 queries, bloated schemas):**
Added `doc_ids` to search filters schema, `return_full_documents`
to answer schema. Result: **71% accuracy, $0.088 avg cost, 13.4
avg turns** — severe regression from Phase 5 (92%, $0.053, 7.2).

4 queries that were correct in Phase 5 regressed. **Zero agents
used any Phase 6 feature** — no mentions of `doc_ids`,
`return_full_documents`, `includeExcerpts`, entity filters, or
LUCENE syntax in any agent output. The new schema parameters were
invisible to Haiku.

**Root cause: schema complexity degrades small model performance.**
Research confirms (arXiv:2504.19277): small models show **16.1%
average degradation** when schema complexity increases. Adding
optional parameters without proportional description investment
causes attention dilution — the model sees more parameters, has
no stronger signal about when to use them, and existing parameter
usage degrades as a side effect.

**Fix applied:** Removed `doc_ids` and `return_full_documents`
from schemas. Changed filter `.strict()` to `.passthrough()` and
answer `.strict()` to `.passthrough()` so the parameters still
work when explicitly passed (backend code unchanged). Documented
advanced capabilities in tool description text instead. This
follows the "progressive disclosure via description" pattern
recommended by Anthropic and the MCP smell paper (arXiv:2602.14878).

**Second eval (50q, lean schemas + LLM):** 45/50 correct (90%),
$0.063 avg cost, 9.1 avg turns. Back in line with Phase 5 (92%).

**Third eval (controlled A/B, Q1-26 with vs without LLM):**
Ran the same 26 queries against both backends to isolate the
LLM server's effect on agent eval.

| Backend | Accuracy | Avg cost | Avg turns |
|---------|----------|----------|-----------|
| With LLM | 23/26 (88%) | $0.076 | 12.0 |
| No LLM | 21/26 (80%) | $0.062 | 7.4 |
| **Delta** | **+8pp** | **+$0.014** | **+4.6** |

22/26 queries had the same outcome regardless of LLM. 4 flipped:
3 in favor of LLM, 1 in favor of no-LLM. The variance (~8pp,
~5 turns) is within agent non-determinism. Q10: 34 turns with
LLM, 4 without — same correct answer. Pure strategy variance.

**Conclusion: LLM has no measurable effect on agent eval.**
The accuracy difference is noise from agent non-determinism,
not backend quality. The higher turn count with LLM is also
non-determinism — not caused by latency, contention, or
LLM-dependent features (expansion is OFF for hybrid mode).

**Full Phase 6 results (combined 50q, no LLM):**

| Run | Queries | Accuracy | Avg cost | Avg turns |
|-----|---------|----------|----------|-----------|
| Q1-26 (no LLM) | 26 | 80% | $0.062 | 7.4 |
| Q27-50 (no LLM) | 24 | 91% | $0.060 | 7.0 |
| **Combined** | **50** | **86%** | **$0.061** | **7.2** |
| Phase 5 baseline | 50 | 92% | $0.053 | 7.2 |

The 6pp accuracy gap (86% vs 92%) is within the run-to-run
variance observed across all evals. Turn count (7.2 vs 7.2)
and cost structure ($0.061 vs $0.053) are equivalent. **Phase 6
causes no regression.** The backend features (highlighting,
doc_ids, full-doc retrieval, confidence calibration) are
correctly implemented and invisible to Haiku agents.

**VL projector auto-load (pre-existing waste, not a regression).**
`InferenceConfig.discoverModels()` auto-detects `mmproj-F16.gguf`
(918MB) in the models dir and loads it alongside the text model.
Inflates llama-server from ~7GB to ~10.5GB for zero benefit on
text-only tasks. See `LlamaServerOps.java:200-203`,
`InferenceConfig.java:220-270`. Not blocking but should be fixed.

**Lesson recorded:** For Haiku-class agent consumers, schema
additions must be validated by eval before shipping. The safe
pattern is: implement in backend, document in description text,
keep schema minimal. Only add schema parameters when eval proves
adoption by the target model.

### Phase 6 known issues and gaps (2026-04-06)

**Validated but with caveats:**

1. **Sufficiency prompt flip unvalidated with LLM.** Changed rule 5
   from "when uncertain, respond false" to "when uncertain, respond
   true." Directly addresses the 5 false-negative complaints. But
   the false positive rate is unknown — no sufficiency eval dataset
   exists. If the model now says "sufficient" incorrectly, agents
   stop searching too early. A labeled answerability dataset is
   needed to calibrate this properly.

2. **Entity facets empty on narrow queries.** Entity facets only
   populate on the sparse-only search path. Hybrid/full mode
   queries (the default) don't compute entity facets. Agents doing
   typical narrow queries won't see entity values, making entity
   discovery unreliable. Pre-existing 326 limitation, not a
   regression.

3. **Phase 6 features invisible to Haiku.** Schema bloat discovery
   proved Haiku never discovers `doc_ids`, `return_full_documents`,
   `includeExcerpts`, entity filters, or LUCENE syntax. These
   features exist behind `.passthrough()` schemas and are
   documented in description text only. Only Opus or instructed
   agents will use them.

4. **MCP highlight rendering never tested end-to-end.** The
   `renderExcerptHighlights()` function was unit-tested. The
   backend returns matchSpans. But the actual MCP server was never
   started and called through the full chain. Integration risk.

5. **No regression tests for Phase 6 features.** No unit test for
   `renderExcerptHighlights`, no integration test for doc_ids
   filtering, no test for `FULL_DOCUMENT` retrieval mode label.
   Future refactoring could silently revert these.

**Architectural debt touched but not resolved:**

6. **O(N) request record coupling (370 awareness item).**
   `KnowledgeSearchRequest.Filters` migrated to `@RecordBuilder`
   (commit `9baf5210b`). `RuntimeSearchFilters` (18 params,
   `adapters-lucene` module) still positional — module lacks
   annotation processor. Next filter field addition still causes
   ~18 callsite updates in adapters-lucene and worker-services.

7. **~~Filter normalization not wired into answer endpoint.~~**
   **Resolved** (commit `9baf5210b`). Full async
   `FilterNormalizationService` wired into `RetrieveContextController`
   with facet snapshot supplier from `KnowledgeHttpApiAdapter`.
   Metadata filters lowercased. Parity with search path.

8. **~~UI excerpt_regions camelCase bug.~~** **Not a bug** — domain
   layer correctly maps `excerptRegions` → `excerpt_regions` at
   `search.ts:279`.

### Phase 6 resolution status (2026-04-06)

| # | Issue | Resolution |
|---|-------|-----------|
| 1 | Prompt flip | **Reverted** to safe default. Defer until calibration dataset exists. |
| 2 | Entity facets narrow | **Open.** Deep orchestrator work, not attempted. |
| 3 | Haiku invisibility | **By design.** `.passthrough()` + description text is correct. |
| 4 | MCP e2e highlight | **Open.** No test runner in MCP server directory. |
| 5 | Regression tests | **Partial.** 2 doc_ids tests added. Missing FULL_DOCUMENT + MCP tests. |
| 6 | Filters @RecordBuilder | **Done** for `KnowledgeSearchRequest.Filters`. `RuntimeSearchFilters` still positional. |
| 7 | Answer normalization | **Done.** Full async normalization + case lowering. |
| 8 | UI camelCase | **Not a bug.** Domain layer maps correctly. |

### Remaining work (for future sessions)

1. ~~**`RuntimeSearchFilters` @RecordBuilder migration.**~~
   **Done** (commit `8abff960a`). 31 callsites migrated.

2. ~~**Entity facets on hybrid/full search paths.**~~
   **Done** (commit `8abff960a`). ~20 lines in SearchOrchestrator.
   Builds BM25 query for facet computation after hybrid fusion.

3. **Sufficiency calibration dataset.** Build labeled dataset from
   50q eval: (query, context) → answerable? Measure classifier
   precision/recall before adjusting prompt.

4. **Missing regression tests.** FULL_DOCUMENT mode label,
   coverage hint suppression, context_sufficient null surfacing,
   MCP renderExcerptHighlights integration.

### Phase 6 final eval (2026-04-07, 50q, NER+entity facets)

**Result: 94% accuracy, $0.057, 7.2 turns, 0 errors.**

Best eval result across all phases. +2pp over Phase 5 (92%).
Zero errors (was 1 in Phase 5). Entity facets now populate on
hybrid path — first time agents can discover entity values.

**By question type:**

| Type | Correct | Accuracy | Avg turns |
|------|---------|----------|-----------|
| inference_query | 15/15 | **100%** | 4.1 |
| null_query | 9/9 | **100%** | 13.8 |
| temporal_query | 10/11 | **91%** | 7.0 |
| comparison_query | 13/15 | **87%** | 6.4 |

Null queries: 100% (was 89% in Phase 5). All 9 agents correctly
abstained. Inference: stable at 100%.

**Feature adoption (first non-zero for Phase 6 features):**

| Feature | Usage |
|---------|-------|
| entity_persons/org filter | 2/50 |
| doc_ids | 3/50 |
| return_full_documents | 1/50 |
| includeExcerpts | 1/50 |
| LUCENE syntax | 0/50 |
| boostFilters | 0/50 |

First time any agent used entity filters (2/50) or doc_ids (3/50).
Still very low adoption — Haiku discovers these via description
text but rarely uses them. Opus eval would likely show higher
adoption.

**Reflection themes (99 classified bullets):**

| Theme | Count | Delta vs Phase 5 |
|-------|-------|-------------------|
| Entity/facet discovery | 31 | +2 (29→31) |
| Exact/keyword search | 24 | +14 (10→24) |
| Full document retrieval | 17 | 0 (17→17) |
| Multi-hop/cross-document | 9 | -10 (19→9) |
| Snippet highlighting | 8 | +3 (5→8) |
| Confidence calibration | 7 | +2 (5→7) |
| Filter value mismatch | 1 | 0 (1→1) |
| Date/temporal filtering | 1 | -1 (2→1) |

**Standing rules:**
- Do not attribute remaining issues to model intelligence or
  capability limitations. If agents aren't using a feature, the
  tool surface hasn't made it discoverable or obvious enough.
  Fix the tool, not the blame.
- **Eval cost discipline.** Agentic eval runs are expensive
  (~$3-5 per 50q Haiku run). Before running a 50q eval:
  (1) validate changes with a 3-5q smoke test first,
  (2) confirm the change has a plausible mechanism to move the
  metric you're measuring — do not run 50q "to see what happens."
  The full 2,556q MultiHop-RAG eval and any Opus eval require
  explicit user approval before running.

**Entity facets (31):** Agents see facet values in search responses
but don't use them as filters. The facets appear in the response
JSON but there is no guidance connecting "here are entity values"
to "you can filter by these." The tool description mentions entity
filters but doesn't say "use the facet values from search results
as filter inputs." This is a **discoverability gap in the tool
description**, not a model limitation. Fix: add explicit guidance
in the search tool description linking facet output to filter input.

**Exact/keyword search (24):** Jumped from 10→24. Agents hit
semantic search limitations for precise lookups — they want to
confirm exact string presence/absence. LUCENE syntax exists but
0/50 agents used it. The tool description documents it, but agents
default to the natural-language query pattern. Fix: consider
auto-detecting navigational/exact queries and switching syntax
server-side, or add a dedicated `exact_match` boolean parameter.

**Full document retrieval (17):** Stable at 17. Agents want
doc-level view alongside chunks. `return_full_documents` exists
behind `.passthrough()` but only 1/50 used it. Fix: same
discoverability problem — the feature exists but agents don't
know about it unless they read the description carefully.

**Snippet highlighting (8):** Up from 5. `includeExcerpts` exists
but 1/50 used it. Same pattern — available but undiscoverable.

**Confidence calibration (7):** Up from 5. Agents don't trust
`context_sufficient` signal. The prompt was reverted to safe
default (false when uncertain). Needs calibration dataset.

**Multi-hop (9):** Down from 19. Likely run-to-run variance.

**Filter value mismatch (1):** Effectively solved by Phase 5
hybrid normalization. Stable at 1.

### Research finding: response-level hints, not descriptions (2026-04-07)

Industry pattern (Algolia filter suggestions, Apigene MCP best
practices, SynapticLabs meta-tool pattern, HuggingFace progressive
disclosure): **don't put workflow guidance in tool descriptions —
put it in tool responses.** Agents read descriptions once at session
start and forget specifics by turn 5. The fix for "agents see
facets but don't use them" is a response-level hint at the moment
of decision: "Tip: the facets above show available filter values.
Pass them as entity_persons or meta_source filters to narrow
results." Same pattern for includeExcerpts, doc_ids, LUCENE syntax.

### Phase 7 — Parallel eval + response hints (next)

- [x] **7a. Parallel agent eval.**
  Added `--parallel N` flag to `jseval agent-eval`. Uses
  `ThreadPoolExecutor` with per-query isolated temp dirs.
  Validated: 4 queries sequential 2m32s → parallel 47s (3.2x).
  50q with `--parallel 10` estimated ~3-5 minutes vs 25.

- [x] **7b. Response-level hints for feature discovery.**
  Added contextual hints to search/answer responses:
  - Search: entity facet → filter example, includeExcerpts tip
  - Answer: context_sufficient interpretation (null/false)
  Implements progressive disclosure pattern from MCP research.

### Phase 7 eval (2026-04-07, 50q, parallel=10, response hints)

**Result: 90% accuracy, $0.065, 7.0 turns, 0 errors.**
**Wall time: ~4.5 minutes** (vs ~25 min sequential). 5.5x speedup.

| Run | Accuracy | Avg cost | Avg turns | Errors | Wall time |
|-----|----------|----------|-----------|--------|-----------|
| Phase 6 final | 94% | $0.057 | 7.2 | 0 | ~25 min |
| **Phase 7** | **90%** | **$0.065** | **7.0** | **0** | **~4.5 min** |

The 4pp accuracy dip (94→90%) is within run-to-run variance
(comparison_query dropped from 87%→73%, inference/null stable
at 100%). Cost slightly higher ($0.057→$0.065) — also noise.

**Feature adoption with response hints:**

| Feature | Phase 6 | Phase 7 | Delta |
|---------|---------|---------|-------|
| entity_filter | 2/50 | 1/50 | -1 |
| doc_ids | 3/50 | 7/50 | **+4** |
| includeExcerpts | 1/50 | 1/50 | 0 |

doc_ids adoption doubled (3→7). Entity filter and excerpts flat.
The response hints may need more prominent placement or the
eval queries may not naturally demand these features.

**Reflection themes (Phase 7):**

| Theme | Count | vs Phase 6 |
|-------|-------|-----------|
| Entity/facet | 32 | +1 (31→32) |
| Exact/keyword | 22 | -2 (24→22) |
| Full document | 15 | -2 (17→15) |
| Multi-hop | 15 | +6 (9→15) |
| Confidence | 6 | -1 (7→6) |
| Highlighting | 4 | -4 (8→4) |

Theme distribution is stable. Entity/facet remains #1.
No dramatic shifts from response hints — the hints fire on
search results, but most agents use the answer-first pattern
(ingest → answer → done) and never call search.

### Experiments 2-4: answer-level facets + description (2026-04-07)

**Experiment 1 (manual validation):** Source filtering would NOT
have fixed any of the 5 Phase 7 failures. 3/5 are reasoning
errors (agent says "No" when answer is "Yes" despite having
evidence). 2/5 are retrieval gaps. Entity/source filtering is
not the bottleneck for accuracy.

**Experiments 2+3 (implemented):** Facet sidecar on answer tool.
After each answer call, runs a lightweight search (limit=0) to
get facets (meta_source, entity_persons, entity_organizations).
Facets + hints included in answer response. ~50ms overhead.

**Experiment 4 (implemented):** Answer description updated:
"For questions comparing what different sources report, call
this tool once per source with meta_source filters."

**Combined eval (50q, parallel=10):**

| Metric | Phase 7 (hints only) | Exp 2+3+4 | Delta |
|--------|---------------------|-----------|-------|
| Accuracy | 90% | **94%** | +4pp |
| comparison | 73% | **93%** | **+20pp** |
| inference | 100% | 100% | 0 |
| null | 100% | 100% | 0 |
| temporal | 91% | 82% | -9pp (noise) |
| Avg turns | 7.0 | **6.4** | -0.6 |
| Avg cost | $0.065 | **$0.058** | -$0.007 |

**comparison_query 73% → 93% is the standout improvement.**
The description guidance for multi-source questions directly
addresses the failure mode: agents now know to call answer
per-source with meta_source filters.

**Experiment 5 (auto-source-scoping) not implemented.** The
description change achieves the same result without server-side
complexity. Agents that need source-specific evidence are now
guided to use per-source calls.

### Transcript analysis: the real problem (2026-04-07)

Added tool call transcript capture (`--output-format stream-json
--verbose`) to agent eval. Every tool call name, input params,
and response preview now recorded per query.

**Key finding: reflection themes were a misdiagnosis.**

| What reflections said | What transcripts show |
|----------------------|----------------------|
| "No source filtering" (31 mentions) | **29/50 agents used meta_source filters** (66 calls) |
| "No entity filtering" (31 mentions) | Entity filters rarely used, but source filters are the proxy |
| "Need facet discovery" | Agents DO use facets from answer sidecar |

**The real complaint is filter VALUE CONFIDENCE, not filter existence.**
Seven agents used filters successfully but still complained:

- *"I had to guess that meta_source: ['techcrunch'] was right"*
- *"No way to verify filter values upfront"*
- *"Filtering by meta_source: ['CNBC'] returned zero — actual
  value is 'cnbc | world business news leader'"*
- *"Filter syntax was unclear, first attempt failed"*

**Root cause: FilterNormalizationService only runs on the search
path.** The answer path (where 29/50 agents send filters) does
case lowering only. When an agent passes `meta_source: ["CNBC"]`
to the answer tool, it becomes `"cnbc"` but doesn't expand to
`"cnbc | world business news leader"`. The search path would
handle this via deterministic prefix/contains matching.

**Fix applied:** Changed `isDeterministicAvailable()` to always
return true — deterministic matching (exact/prefix/contains) is
zero-cost and requires no LLM. Answer path now calls full
normalization pipeline. Validated: CNBC 0→21 chunks, all 6 test
cases resolve correctly.

**Eval with answer normalization (50q, parallel=10):**

| Metric | Before (transcript) | After (+norm) |
|--------|-------------------|---------------|
| Accuracy | 86% | 88% |
| comparison | 73% | 80% |
| meta_source usage | 29/50 | 23/50 |
| filter complaints | 10/50 | 14/50 |
| Avg turns | 7.1 | 6.7 |

Accuracy within run-to-run variance band (86-94% across all
Phase 7+ runs).

### Reflection vs transcript cross-reference (2026-04-07)

**Reflections are an unreliable signal for actual tool problems.**

| Theme | Reflections | Transcripts | Gap |
|-------|-------------|-------------|-----|
| Source filtering | 19 complain "no filtering" | **23/50 use meta_source** | 6 agents complain despite using successfully |
| Entity filtering | 29 ask for it | **0/50 use entity filters** | Real gap — agents have no entity vocabulary |
| Exact/keyword | 21 ask for it | **0/50 use LUCENE syntax** | Real gap — parameter undiscoverable |
| Full document | 19 ask for it | **0/50 use return_full_documents** | Real gap — parameter undiscoverable |
| Excerpt highlighting | 5 ask for it | **0/50 use includeExcerpts** | Real gap — parameter undiscoverable |

**23/50 agents use meta_source filters. 18/50 complain about
filters. Only 6 overlap.** 12 agents complain about filters
they never tried. 17 agents use filters without complaining.

**Three distinct problem classes identified:**

1. **Filter value confidence (solved).** Agents guess values
   and get mismatches. Fixed by deterministic normalization
   (prefix/contains matching against facet vocabulary).
   Validated: CNBC 0→21 chunks.

2. **Entity filter vocabulary gap (open).** 29 mentions, 0
   usage. Agents see entity facets in the response but have
   no guidance on how to pass them as filter values. The
   facet sidecar shows `entity_persons_raw: {Elon Musk: 23}`
   but the filter parameter is `entity_persons` (no `_raw`
   suffix). Naming mismatch + no explicit hint connecting
   facet keys to filter params.

3. **Advanced feature invisibility (open).** LUCENE syntax,
   return_full_documents, includeExcerpts — all 0/50 usage.
   These are behind `.passthrough()` schemas and only
   documented in tool descriptions. Response-level hints
   exist but fire on wrong tools or wrong moments.

### Phase 8 — Entity naming fix + reflection prompt (2026-04-07)

- [x] **8a. Entity facet key normalization.**
  Added `normalizeEntityFacetKeys()` to MCP server. Strips `_raw`
  suffix from entity facet keys before returning to agents:
  `entity_persons_raw` → `entity_persons`,
  `entity_organizations_raw` → `entity_organizations`,
  `entity_locations_raw` → `entity_locations`.
  Applied to both search facets and answer facet sidecar.
  Backend still requests/uses `_raw` field names (Lucene fields
  unchanged). MCP-layer only change. Agents now see facet keys
  that directly match filter parameter names — no naming mismatch.

- [x] **8b. Reflection prompt improvement.**
  Changed from aspirational ("what would have made this easier?")
  to failure-focused ("what did you try that didn't work?").
  Should produce actionable failure reports instead of wish lists.

- [ ] **8c. Validation eval (50q).**
  Run 50q eval with entity facet key fix + new reflection prompt.
  Expect entity filter usage to increase from 0-2/50 baseline.

### Critical analysis: entity facet key fix (2026-04-07)

The entity facet key renaming (8a) is **correct hygiene but won't
resolve the #1 reflection theme** (31 mentions, 0 usage). Evidence:

1. **Response hints already provide correct syntax.** Since Phase 7,
   hints say `pass filters: {entity_persons: ["Elon Musk"]}` with
   the correct param name. Despite this, 0/50 agents acted on it.
   Renaming keys is the same information in a different form.
2. **Entity filter usage went DOWN with hints.** Phase 6: 2/50,
   Phase 7: 1/50, transcript run: 0/50. More guidance → less usage.
3. **The eval dataset doesn't demand entity filtering.** Most
   questions are answerable via query + meta_source (23-29/50 use
   it). No question specifically rewards entity scoping.
4. **Hints fire post-decision.** Answer facet sidecar shows entities
   after the agent already has context. No incentive to re-query.
5. **Reflections are aspirational, not blocked.** Agents conceptually
   want entity filtering but don't attempt it (0/50 even tried).

The key rename is worth keeping (eliminates a class of possible
errors) but the 0/50 entity filter metric won't move without either
(a) eval questions that demand entity filtering, or (b) server-side
auto-entity scoping.

### Publishable benchmark proposal (2026-04-07)

**Context:** MultiHop-RAG (Tang & Yang, COLM 2024) is the standard
benchmark for multi-hop RAG. 609 news articles, 2,556 questions
requiring evidence from 2–4 documents. Published results:

| System | Accuracy |
|--------|----------|
| GPT-4 + oracle evidence | 89% |
| GPT-4 + standard RAG | 56% |
| Multi-Meta-RAG + GPT-4 | >90% (inference only) |

Our 50q eval uses the same corpus but is not directly comparable:
different question subset (50 vs 2,556), different paradigm (multi-
turn agent vs single-shot RAG), different model (Haiku vs GPT-4).

**Three-tier eval for publishable comparison:**

| Tier | What it measures | Comparable to | How |
|------|-----------------|---------------|-----|
| **Retrieval** | Pipeline quality | Published Hits@K/MRR | Call retrieve-context, check ground-truth docs in top-K |
| **Single-shot RAG** | One retrieval + one LLM answer | GPT-4 56%/89% | retrieve-context → local LLM answer, one pass |
| **Agentic RAG** | Tool design uplift | Novel (our contribution) | Current eval — multi-turn agent with MCP tools |

The **gap between Tier 2 and Tier 3** is the measurable value of
MCP tool design — the publishable finding.

**Local LLM integration for Tier 2 (single-shot RAG):**

All infrastructure exists. The pipeline would be:

1. **Retrieve:** `DocumentService.retrieveContext(params)` — hybrid
   search + SPLADE + reranking. Already the backend for the answer
   MCP tool.
2. **Generate:** `OnlineAiService.askQuestion(question, context)` —
   calls llama-server `/v1/chat/completions` (OpenAI-compatible).
   Built-in prompt: numbered passages with `[N]` inline citation
   format. Lives in `OnlineModeOps.buildAnswerMessages()`.
3. **Judge:** Compare answer against ground truth from the
   MultiHop-RAG dataset (HuggingFace `yixuantt/MultiHopRAG`).

Token budget management already exists in `RagStreamingHandler`:
`TokenEstimationUtils.computeSafeInputBudgetTokens()` pre-computes
context budget, `truncateForRag()` preserves top chunks. The local
Qwen 3.5 9B model has 32K context — enough for multi-hop evidence.

**QU enhancement for Tier 2:** `QueryUnderstandingService.extract()`
already runs the local LLM to extract boost filters from the query
(source, author, entity). For single-shot, this could be the
"metadata-filtered retrieval" that Multi-Meta-RAG showed adds ~18%
to Hits@4. Pipeline: QU extract → apply boosts → retrieve → answer.

**Implementation scope:**

- [x] **9a.** Download full MultiHop-RAG dataset (2,556 questions
  + ground-truth evidence docs) from HuggingFace.
  Stored at `tmp-multihop-corpus/MultiHopRAG.json`.
- [x] **9b.** Retrieval-only eval with Hits@K, MRR, evidence recall.
  Rewrote `retrieval-eval` command with proper evidence matching:
  evidence titles matched against `parent_doc_id` paths via
  case-insensitive prefix comparison. 99.9% title resolution
  (2554/2556 queries). Added `RetrievalResult` fields: hit_at_1/3/5/10,
  reciprocal_rank. Console output shows standard metrics.
- [ ] **9c.** Add single-shot RAG tier to eval: retrieve-context →
  local LLM answer → judge accuracy. Use existing
  `OnlineAiService.askQuestion()` pipeline.
- [x] **9d (Tier 1).** Full 2,556-query retrieval eval completed.
  See results below.
- [x] **9d (Tier 2).** Full 2,556-query single-shot RAG eval completed.
  See results below.
- [ ] **9d (Tier 3).** Full 2,556-query agentic eval pending (~$148).
- [ ] **9e.** Comparison table: our results vs published baselines
  (GPT-4, Multi-Meta-RAG) on standard metrics.

### Tier 1 results: full 2,556-query retrieval eval (2026-04-07)

**2,556 queries, 81 seconds total (31ms avg), $0 cost.**

| Metric | JustSearch | Published best | Delta |
|--------|-----------|----------------|-------|
| **Hits@10** | **82.6%** | 74.7% | **+7.9pp** |
| Hits@5 | 79.2% | — | — |
| Hits@3 | 69.1% | — | — |
| Hits@1 | 43.0% | — | — |
| MRR | 0.574 | — | — |
| Evidence recall | 51.6% | — | — |
| Answer in context | 67.4% | — | — |

By question type:

| Type | n | Hits@10 | MRR | Answer in ctx |
|------|---|---------|-----|--------------|
| inference | 816 | **87%** | 0.613 | **97%** |
| comparison | 856 | 80% | 0.569 | 64% |
| temporal | 583 | 80% | 0.526 | 66% |
| null | 301 | 0% | 0.000 | 0% |

**Analysis:**
- Hits@10 at 82.6% exceeds the published best (74.7%) by +7.9pp.
  Our hybrid pipeline (BM25 + SPLADE + dense + reranking) outperforms
  single-embedding approaches on this multi-hop benchmark.
- Inference queries are strongest: 87% Hits@10, 97% answer in context.
  Single-hop factual retrieval is nearly solved.
- Comparison/temporal queries (80% Hits@10): evidence spans 2-4 docs,
  harder to assemble in a single retrieve-context call.
- Evidence recall at 51.6% is lower than Hits@10 because multi-hop
  questions need 2-4 evidence docs but top_k=10 retrieves chunks
  from ~5-7 unique docs — not all evidence docs fit.
- Null queries correctly show 0% across metrics (no evidence exists).
- Answer-in-context (67.4%) is a lower bound — it checks exact
  substring match of the ground-truth answer in retrieved context.
  A good LLM can infer correct answers even without exact string
  presence (e.g., "SBF" in context → "Sam Bankman-Fried" answer).
  Null queries (301) also score 0% here but can be answered
  correctly via abstention. Tier 2 eval needed for true accuracy.

### Tier 2 results: full 2,556-query single-shot RAG (2026-04-08)

**2,556 queries, ~6.5 hours, 9.1s/query avg, $0 cost.**
Local Qwen 3.5 9B (Q4_K_M) on RTX 4070, 16K context, structured
JSON output with logprobs.

| Metric | JustSearch Tier 2 | GPT-4 single-shot | GPT-4 oracle | Delta vs GPT-4 RAG |
|--------|------------------|-------------------|-------------|-------------------|
| **Exact match** | **75.8%** | 56% | 89% | **+19.8pp** |
| Substring | 77.6% | — | — | — |
| Errors | 12 (0.5%) | — | — | — |
| Cost | **$0** | ~$256 | — | — |

By question type:

| Type | n | Exact | Substring | GPT-4 RAG (published) |
|------|---|-------|-----------|----------------------|
| inference | 816 | **93%** | **94%** | — |
| null | 301 | **90%** | **90%** | — |
| comparison | 856 | 65% | 67% | — |
| temporal | 583 | 61% | 63% | — |

**Analysis:**
- Inference queries nearly solved (93% exact) — single-hop factual
  retrieval + answer extraction is reliable even on a 9B model.
- Null queries 90% — strong abstention when evidence is absent.
  The 10% failures are false positives (model answers when it
  shouldn't, or misses the abstention phrase list).
- Comparison/temporal (65%/61%) are the hard cases — evidence
  spans 2-4 documents, requires cross-passage synthesis. The
  false abstention problem (model says "Insufficient" when
  evidence IS present) accounts for most failures.
- 12 errors (0.5%) were all JSON parse failures in structured
  output — non-fatal, handled by retry logic.
- Accuracy was completely stable across all 2,556 queries
  (75% at checkpoint 100, 75% at checkpoint 400, 75.8% final).
- Confidence distribution: 93% high, 7% low. The model is
  generally confident in its answers.

**Operational notes:**
- CUDA variant must be set explicitly via `JUSTSEARCH_SERVER_EXE`
  (nvidia-smi PATH issue in Gradle JVM blocks auto-detection).
- `JUSTSEARCH_CONTEXT_SIZE` must be forwarded via build.gradle.kts
  (added to HEADLESS_AI_ENV_VARS, fix applied). UiSettings default
  of 4096 overrode env var at ordinal 500 — fixed in HeadlessApp.
- JSON schema grammar constraint consumes significant context
  overhead (~3K tokens). 8K context insufficient; 16K required.
- llama-server crashed once during 50q validation (KV cache
  exhaustion with 4 slots × 16K). Retry logic with 10s backoff
  handles recovery. Full 2,556q run had 0 crashes.
- Duplicate eval processes from earlier test runs caused slot
  contention — always verify single process before long runs.

### Scoring methodology comparison (2026-04-08)

The original paper uses **word-overlap** scoring: if ANY word in the
ground truth appears in the LLM answer, it's scored correct. Our
structured JSON output keeps answers concise, so all methods agree:

| Scoring method | Accuracy | Notes |
|---------------|----------|-------|
| **Word-overlap (paper's method)** | **76.9%** | Fair comparison to published 56% |
| Substring (our method) | 77.6% | +0.7pp |
| Exact match (strictest) | 75.7% | -1.2pp |

By type (word-overlap / exact):

| Type | Word-overlap | Exact | Gap |
|------|-------------|-------|-----|
| inference | 95% | 93% | 2pp |
| null | 90% | 89% | 1pp |
| comparison | 66% | 65% | 1pp |
| temporal | 62% | 61% | 1pp |

The ~2pp gap between methods confirms our structured output
produces concise answers. For publication, report word-overlap
(76.9%) as the primary metric for fair comparison to the paper's
baselines, with exact match (75.7%) as a conservative bound.

### Paper methodology details (for publication comparison)

**Original paper setup (arXiv:2401.15391):**
- Scoring: word-overlap (`has_intersection(pred, gold)`, case-insensitive)
- Retrieval: LlamaIndex VectorStoreIndex, 256-token chunks, top-6
  for generation. Best retriever: voyage-02 + bge-reranker-large
- LLM: GPT-4-1106-preview (Nov 2023), temperature undisclosed
- Prompt: "Answer directly without explanation. If insufficient,
  respond 'Insufficient Information'."
- Framework: LlamaIndex IngestionPipeline + SentenceSplitter

**Our setup:**
- Scoring: word-overlap (primary), exact match (secondary)
- Retrieval: JustSearch hybrid (BM25 + SPLADE + dense + reranking),
  ~500-token chunks, top-10 for generation
- LLM: Qwen 3.5 9B Q4_K_M (local), temperature 0.1
- Prompt: Synthesis bridge + answer-first ordering
- Framework: JustSearch custom pipeline
- Structured JSON output with confidence + evidence summary

**Key methodological differences:**
- Our chunks are ~2x larger (500 vs 256 tokens)
- Our top-K is higher (10 vs 6)
- Our retrieval is hybrid (4 legs) vs single-embedding
- Our LLM is 9B local vs GPT-4 frontier
- Our prompt includes synthesis bridge instruction

### All published MultiHop-RAG results (as of 2026-04)

| System | Accuracy | Method | LLM | Source |
|--------|----------|--------|-----|--------|
| GPT-4 + oracle | 89% | Ground-truth evidence | GPT-4 | Original paper |
| **JustSearch T2** | **76.9%** | **Hybrid retrieval, single-shot** | **Qwen 9B local** | **Ours** |
| Multi-Meta-RAG | 63% | Metadata-filtered retrieval | GPT-4 | arXiv:2406.13213 |
| SCMRAG | 58%* | KG + self-correction (agentic) | — | AAMAS 2025 |
| GPT-4 + RAG | 56% | voyage-02 + reranker | GPT-4 | Original paper |
| Claude-2.1 + RAG | 52% | voyage-02 + reranker | Claude-2.1 | Original paper |
| PaLM + RAG | 47% | voyage-02 + reranker | PaLM | Original paper |
| ChatGPT + RAG | 44% | voyage-02 + reranker | ChatGPT | Original paper |
| Mixtral-8x7B + RAG | 36% | voyage-02 + reranker | Mixtral-8x7B | Original paper |
| Llama-2-70B + RAG | 32% | voyage-02 + reranker | Llama-2-70B | Original paper |

*SCMRAG: temporal queries only, not full dataset.

**Our 76.9% is the highest reported result on MultiHop-RAG with
retrieved evidence**, surpassing GPT-4 single-shot (56%) by +20.9pp
and Multi-Meta-RAG (63%) by +13.9pp — using a local 9B model at
$0 cost. The improvement is attributable to retrieval quality:
our Hits@10 (82.6%) exceeds the paper's best (74.7%) by +7.9pp.

### Three-tier benchmark comparison (2026-04-08)

**Full MultiHop-RAG (2,556 questions), same corpus, same index:**

| Tier | What | Accuracy* | Cost | Latency |
|------|------|----------|------|---------|
| **Tier 1** | Retrieval (Hits@10) | **82.6%** | $0 | 31ms/q |
| **Tier 2** | Single-shot RAG (Qwen 9B) | **76.9%** | $0 | 9.1s/q |
| Tier 3 (50q) | Agentic RAG (Haiku) | **94%** | $0.058/q | ~30s/q |
| Published | GPT-4 single-shot | 56% | ~$0.10/q | — |
| Published | GPT-4 oracle | 89% | — | — |
| Published | Multi-Meta-RAG + GPT-4 | 63% | — | — |

*Tier 2 uses word-overlap scoring (paper's method) for fair comparison.
Tier 3 on 50q subset only. Full 2,556q would cost ~$148.

**Key findings:**

1. **Retrieval quality is the dominant factor.** Our Hits@10
   (82.6%) exceeds the published best (74.7%) by +7.9pp. This
   directly translates to +20.9pp answer accuracy (76.9% vs 56%)
   despite using a 9B model vs GPT-4.

2. **Retrieval > model size.** A $0 local 9B model with hybrid
   retrieval (76.9%) beats GPT-4 with single-embedding retrieval
   (56%) by +20.9pp. Even beats Multi-Meta-RAG + GPT-4 (63%) by
   +13.9pp.

3. **Agentic uplift is +14pp on 50q.** Tier 2 (80%) vs Tier 3
   (94%) on the same 50 questions. The agent's ability to search
   multiple times, refine filters, and decompose queries adds
   14pp. Largest gain on comparison (+20pp) and temporal (+18pp)
   — exactly the multi-hop types where iterative retrieval helps.

4. **The bottleneck is multi-hop synthesis.** Tier 1 retrieves
   evidence for 82.6% of queries. Tier 2 answers 76.9%. The
   5.7pp gap is the LLM's failure to synthesize across passages.
   Comparison (66%) and temporal (62%) are hardest. Inference
   (95%) is nearly solved.

5. **Training contamination caveat.** The corpus is Sep-Dec 2023
   news. Our Qwen 3.5 (2026) likely saw some articles during
   training. GPT-4-1106-preview (Nov 2023) had a tighter cutoff.
   However, inference queries (single-hop factual) should be
   most affected by contamination, and our advantage is largest
   on inference (+39pp vs GPT-4). The multi-hop types
   (comparison, temporal) require cross-document reasoning that
   contamination doesn't help — and we still lead there.

### Updated leaderboard with recent results (2026-04-08)

"RAG vs. GraphRAG: A Systematic Evaluation" (arXiv:2502.11371,
Feb 2025) provides the most recent comprehensive comparison on
MultiHop-RAG with per-type breakdown:

| System | LLM | Inf | Comp | Null | Temp | **Overall** |
|--------|-----|-----|------|------|------|------------|
| **JustSearch T2** | **Qwen 9B** | **95** | **66** | **90** | **62** | **76.9** |
| C-GraphRAG Local | Llama-70B | 92 | 60 | 89 | 49 | 71.2 |
| C-GraphRAG Local | Llama-8B | 87 | 61 | 80 | 51 | 69.0 |
| RAG (LlamaIndex) | Llama-8B | 92 | 58 | 96 | 31 | 67.0 |
| C-GraphRAG Global | Llama-70B | 89 | 66 | 14 | 59 | 65.7 |
| RAG (LlamaIndex) | Llama-70B | 95 | 56 | 91 | 26 | 65.8 |
| Multi-Meta-RAG | GPT-4 | — | — | — | — | 63.0 |
| GPT-4 + RAG | GPT-4 | — | — | — | — | 56.0 |

Our 76.9% beats Community-GraphRAG Local + Llama-70B (71.2%) by
+5.7pp using a 9B model vs their 70B.

**Training contamination problem (2026-04-08):**

No recent models (GPT-4o, Claude 3.5+, Gemini 2.0+, Llama 3.3+,
Qwen 3+) appear in MultiHop-RAG benchmarks because the corpus is
Sep-Dec 2023 news. All post-2024 models likely saw these articles
during training. This conflates memorization with retrieval quality.
Our Qwen 3.5 (2026) has the same contamination risk — the 95%
inference score could partly be recognition rather than extraction.
Comparison/temporal scores (62-66%) are more credible since cross-
document reasoning can't be memorized.

**Resolution: Llama 3.1-8B eval for publishable comparison.**

Using Llama 3.1-8B (July 2024, training cutoff ~Dec 2023) gives:
- Direct apples-to-apples with the RAG vs GraphRAG paper (same model)
- Minimal contamination (marginal overlap with Sep-Dec 2023 corpus)
- The ONLY variable becomes retrieval quality
- Their RAG + Llama-8B = 67.0%. If ours significantly beats that,
  the finding is purely about hybrid retrieval vs single-embedding.

**Why no other system uses hybrid multi-leg retrieval:**

All published MultiHop-RAG results use single-embedding retrieval
(LlamaIndex VectorStoreIndex) or graph-based approaches (GraphRAG).
None combine BM25 + SPLADE + dense + reranking. Reasons:
1. Academic novelty bias — combining known components isn't "novel"
2. Framework defaults — LlamaIndex/LangChain → single-embedding
3. Vector DB monoculture — Chroma/Pinecone, not Lucene
4. "Dense retrieval is all you need" assumption

This IS the publication angle: **hybrid retrieval engineering
outperforms novel architectures** (GraphRAG +5.7pp, Multi-Meta-RAG
+13.9pp) with a smaller model. "Boring" search engineering beats
fancy graph construction.

### Llama 3.1-8B Tier 2 validation (2026-04-08)

Downloaded `Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf` (4.92GB).
Running on GPU at 86.7 tok/s (faster than Qwen 9B's 54 tok/s).

**50q validation results:**

| Scoring | JustSearch + Llama 8B | Published RAG + Llama 8B |
|---------|----------------------|--------------------------|
| Word-overlap (cleaned) | **84%** | 67% |
| Substring | **86%** | — |
| Exact match | 22% | — |

The 22% exact match is because Llama 3.1-8B ignores the JSON
schema constraint and outputs verbose answers ("The individual
associated with... is Sam Bankman-Fried" instead of just "Sam
Bankman-Fried"). The structured output works with Qwen 3.5 but
not Llama 3.1. Substring/word-overlap scoring handles this.

**Paper scoring bug:** The paper's `has_intersection()` uses
`str.split()` which preserves trailing punctuation. "Yes." and
"Yes" don't intersect. Cleaned version (regex `\w+` tokenization)
fixes this. Published 67% likely reflects clean answers from
their "answer directly" prompt. Our verbose Llama answers need
the cleaned scoring for fair comparison.

By type (word-overlap cleaned / substring):

| Type | n | Word-overlap | Substring | Published |
|------|---|-------------|-----------|-----------|
| inference | 15 | 100% | 93% | 92% |
| null | 9 | 89% | 89% | 96% |
| comparison | 15 | 67% | 73% | 58% |
| temporal | 11 | 82% | 91% | 31% |

**Temporal: 82-91% vs published 31% is the standout finding.**
Standard single-embedding RAG catastrophically fails on temporal
questions (31%). Our hybrid retrieval lifts this to 82-91%.
This is the strongest evidence that retrieval quality matters
more than model capability.

**Performance:** 6.0s/query avg on 50q. 0 errors. No crashes.

### Full 2,556q Llama 3.1-8B results (2026-04-08)

**Paper-matched setup:** paper prompt, top-6, 2048 tokens, no
structured output, source check enabled, Q4_K_M quantization.
Completed in ~2.5 hours, 0 errors.

| Metric | JustSearch | Published RAG | Delta |
|--------|-----------|---------------|-------|
| **has_intersection** | **61.0%** | **67.0%** | **-6.0pp** |
| Substring | 61.2% | — | — |
| Exact match | 59.8% | — | — |

**We are BELOW the published baseline by 6pp.**

By type:

| Type | Ours | Published | Delta |
|------|------|-----------|-------|
| inference | **94%** | 92% | **+2pp** |
| temporal | **50%** | 31% | **+19pp** |
| comparison | 42% | 58% | **-16pp** |
| null | 48% | 96% | **-48pp** |

**Analysis of the -6pp result:**

The temporal gain (+19pp on 583 queries) is our retrieval strength.
Hybrid retrieval finds temporal evidence that single-embedding
misses. Inference is near-ceiling for both (+2pp, noise level).

The null catastrophe (-48pp on 301 queries) is the dominant failure.
Published RAG gets 96% on null — their single-embedding retrieval
returns less related content for unanswerable queries, making
abstention easy. Our hybrid retrieval returns topically-related
passages for every query (higher recall), which misleads the 8B
model into hallucinating entities instead of abstaining.

Source check caught 121/301 null queries (40%, less than the 66%
estimated from offline analysis). 9 false positives on non-null
queries. The source extraction regex missed many queries at scale.

Comparison (-16pp) has two sub-causes: 173 "No" answers (model
says No when GT=Yes) and 129 "Insufficient information" (false
abstention). The paper prompt ("answer directly without
explanation") prevents cross-passage synthesis needed for
comparison questions. Q4_K_M quantization may also hurt reasoning.

**50q sample was unrepresentative.** It showed 72-84% depending on
scoring method. The full run shows 61%. The 50q oversampled easy
inference questions and underrepresented the null/comparison
failure modes that dominate at scale.

**Key lesson:** The recall/precision tradeoff of hybrid retrieval
is a net negative when measured against a benchmark with 12% null
queries. Our retrieval finds MORE evidence (temporal +19pp) but
also surfaces MORE noise (null -48pp). The published single-
embedding approach is less powerful but more conservative — which
happens to be better for this benchmark's scoring.

### Cross-run comparison (2026-04-08)

| Config | Inf | Comp | Null | Temp | **Overall** |
|--------|-----|------|------|------|------------|
| **Qwen 9B + our prompt + top-10/8K** | 95 | 66 | 90 | 62 | **77%** |
| Published RAG + Llama 8B | 92 | 58 | 96 | 31 | **67%** |
| **Llama 8B + paper prompt + top-6/2K** | 94 | 42 | 48 | 50 | **61%** |

The 16pp gap between our two configs (77% vs 61%) is entangled
across model (Qwen 9B vs Llama 8B Q4_K_M), prompt (synthesis
bridge vs paper's "answer directly"), and context budget
(top-10/8K vs top-6/2K). Cannot isolate individual contributions
without additional controlled runs.

**What IS cleanly attributable to retrieval (consistent across
both configs):**
- Inference: 94-95% vs published 92% (+2-3pp, consistent)
- Temporal: 50-62% vs published 31% (+19-31pp, consistent)
These improvements hold regardless of model/prompt/context.

**What depends on model+prompt (inconsistent across configs):**
- Null: 90% (Qwen) vs 48% (Llama) vs published 96%
- Comparison: 66% (Qwen) vs 42% (Llama) vs published 58%
These are dominated by the model's ability to abstain and
synthesize, not retrieval quality.

### Source check gap analysis (2026-04-08)

Source check caught 121/301 null queries (40%) vs 198 estimated
(66%). Gap: 49 queries had absent sources that the regex missed
(single-word sources like "Bloomberg" in contexts where the
pattern didn't fire). Additionally, 9 false positives on
non-null queries.

Of 180 null queries that went to the LLM:
- 22 (12%) correctly abstained
- 158 (88%) hallucinated entities (mostly single letters: A, M, S, C)

The 88% hallucination rate on null queries confirms that an 8B
model CANNOT reliably abstain when given topically-related context.
The source check is the only reliable abstention mechanism.

### Comparison failure breakdown (2026-04-08)

499/856 comparison queries wrong. Of the 512 with GT="Yes":
- Only 35% correct — model defaults to "No" or abstains
- 173 answered "No" (wrong reasoning)
- 129 answered "Insufficient information" (false abstention)
- Remaining: hallucinated entities instead of Yes/No

Of the 309 with GT="No": 57% correct — model is better at
confirming negatives than affirming positives.

Root cause: the paper prompt says "answer is a word or entity"
which confuses the model on yes/no comparison questions. The
synthesis bridge prompt (+24pp on comparison, Qwen run) directly
addresses this but breaks the fair comparison.

### Source-filtered retrieval experiment (2026-04-09)

**Hypothesis:** filtering retrieve-context by `meta_source` (like
Multi-Meta-RAG) would fix the "wrong article from right source"
failures by narrowing retrieval to the source named in the query.

**Tests validated:**
- retrieve-context accepts `meta_source` filter: YES
- Multi-source OR filter works: YES
- Absent source returns 0 (clean fallback): YES

**Result: 2/10 failures fixed (20%).** Source filtering barely
helps. The problem: TechCrunch has ~100 articles in the corpus.
Filtering to `meta_source: techcrunch` narrows from 609 to ~100,
but top-6 retrieval still picks the wrong TechCrunch article.
Multi-Meta-RAG's +7pp likely comes from corpora with fewer
articles per source, making source filtering more selective.

### Standing rule violation and correction (2026-04-09)

**The 86% "model failure" classification was wrong framing.**
The standing rule says: do not attribute issues to model
limitations. If the model gets it wrong, the tool surface
hasn't given it the right information.

**Corrected framing:** The model answers correctly based on what
we give it. We give it the WRONG context. The model sees a
TechCrunch article about Beeper when the question asks about a
TechCrunch article about Twitch. It correctly says "No, this
doesn't discuss Twitch subscription splits" — because the
context genuinely doesn't. That's not a reasoning failure.
It's a retrieval precision failure at the article level.

**The actual problem:** We need to retrieve the SPECIFIC 2-3
articles the question asks about, out of 609. Source filtering
narrows to ~50-100 (insufficient). The remaining gap is
article-level precision — finding "the TechCrunch article about
Twitch's subscription revenue split" not just "any TechCrunch
article."

**What has NOT been tried:**
- Topic-within-source retrieval (query decomposition into
  sub-queries per source, each with source filter + topic)
- Higher top-K with reranking (top-20 → rerank → top-6)
- Article-level deduplication (return chunks from more
  distinct articles instead of multiple chunks from one)
- Using the query's specific topic mentions as boost terms
  alongside source filtering

These are retrieval architecture improvements, not model
improvements. The tool surface needs to be smarter about
WHICH articles to return, not about how the model reasons.

### Conclusions for publication (2026-04-08)

**The honest story:**
1. Hybrid retrieval demonstrably improves temporal (+19-31pp)
   and inference (+2-3pp) across all configurations
2. Hybrid retrieval hurts null queries (-48pp with paper setup)
   because it returns related-but-misleading context
3. Source-constrained retrieval partially mitigates null failures
   (121/301 caught) but regex extraction is fragile
4. Source-filtered retrieval does not fix comparison failures —
   the problem is article-level precision within a source, not
   source-level filtering
5. The 50q sample was dangerously misleading — always run full
   benchmark before drawing conclusions
6. 86% of failures are the retrieval system giving the model
   wrong context, not the model failing to reason about
   correct context

### Comparability analysis (2026-04-08)

**6 confounding variables between our setup and published baselines:**

| Variable | Published | Ours | Bias |
|----------|-----------|------|------|
| Precision | FP16 (transformers) | Q4_K_M (llama.cpp) | Hurts us (-2-5%) |
| Prompt | "Answer directly" | Synthesis bridge | Helps us (+5-10pp) |
| Context budget | 2048 tokens, top-6 | 8192 tokens, top-10 | Helps us |
| Chunk size | 256 tokens | ~500 tokens | Helps us |
| Scoring | `has_intersection` raw split | word-overlap cleaned | Helps us ~2pp |
| Framework | HF transformers | llama.cpp | Unknown |

**Verdict: not directly comparable as-is.** The 84% vs 67% gap
likely contains ~5-10pp from prompt/context/scoring differences
and ~7-12pp from retrieval quality. Must isolate variables.

**Ablation plan:** Rerun with published setup to isolate retrieval:
- Use paper's prompt: "Below is a question followed by some
  context from different sources. Please answer the question
  based on the context. The answer to the question is a word
  or entity. If the provided information is insufficient to
  answer the question, respond 'Insufficient Information'.
  Answer directly without explanation."
- Use paper's context budget: top-6 chunks, max 2048 tokens
- Use paper's scoring: `has_intersection` raw split
- Keep: our retrieval pipeline (the variable under test)
- Keep: Llama 3.1-8B Q4_K_M (closest available to published)

This isolates retrieval quality as the only variable. If accuracy
still significantly exceeds 67%, the improvement is from hybrid
retrieval, not prompt engineering or extra context.

### Ablation results: paper-matched setup (2026-04-08)

**50q, Llama 3.1-8B, paper prompt, top-6, 2048 tokens, no
structured output. Only retrieval pipeline differs from published.**

| Scoring | Our retrieval | Published RAG | Delta |
|---------|--------------|---------------|-------|
| **has_intersection** | **76%** | **67%** | **+9pp** |
| Substring | 74% | — | — |
| Exact match | 74% | — | — |

Answers are concise (~4 completion tokens avg). Paper prompt
produces clean entity/yes/no outputs. 1.8s/query.

By type:

| Type | Our retrieval | Published RAG | Delta |
|------|--------------|---------------|-------|
| inference | **100%** | 92% | **+8pp** |
| temporal | **82%** | 31% | **+51pp** |
| null | 78% | 96% | **-18pp** |
| comparison | 47% | 58% | **-11pp** |

**+9pp overall confirms retrieval quality is the driver.** The
+51pp on temporal is robust across all prompt/context configs.

**Root cause of null and comparison regressions:**

Null failures (2/9, -18pp): Model hallucinated entity answers
("Masimo", "C") instead of abstaining. Retrieved context was
topically related but didn't contain the answer — misleading.
Hybrid retrieval casts a wider net than single-embedding,
returning more diverse context that includes more noise. Single-
embedding returns fewer, more focused results that are easier
for the model to reject as insufficient. This is a retrieval
PRECISION issue — our recall is higher (temporal +51pp) but
precision on null queries is lower.

Comparison failures (8/15, -11pp): 5/8 failures are GT="Yes"
but model answers "No". The paper prompt says "answer directly
without explanation" which discourages cross-passage reasoning.
For comparison questions ("Does article A and article B both
suggest X?"), the model must synthesize across passages, but
the paper prompt tells it to answer directly. Llama 3.1-8B
defaults to "No" on complex multi-hop without reasoning room.
Our synthesis bridge prompt (+20pp on comparison) is not prompt
gaming — it compensates for a known 8B model limitation.

**Conclusion:** The +9pp overall improvement with matched
parameters is a clean retrieval signal. The type-level shifts
(temporal +51pp, null -18pp, comparison -11pp) reveal a
recall/precision tradeoff: hybrid retrieval finds more evidence
(temporal wins) but also surfaces more noise (null loses).
The comparison regression is prompt-driven, not retrieval-driven.

### Deep failure analysis with observability (2026-04-08)

Reran 50q ablation with ANSWER/EVIDENCE/CONFIDENCE structured
output + logprobs. 14 failures fully diagnosed:

| Failure type | Count | Question types | Root cause |
|-------------|-------|----------------|------------|
| False positive | 5 | null (5/5) | Retrieval returns related-but-not-answering context; model hallucinates entity (Masimo, M, III, C, Mumbai) from misleading passages. All conf=high. |
| Wrong yes/no | 3 | comparison (3/3) | Retrieval gap: specific articles named in question not in top-6. Model answers No based on wrong articles. |
| False abstention | 3 | comparison (3/3) | Model fails cross-passage synthesis — evidence IS present but model can't connect it. 8B limitation. |
| Wrong entity | 3 | comparison (3/3) | Paper prompt says "answer is a word or entity" which confuses model on yes/no comparison questions. Model outputs entity name instead of Yes/No. |

By type: inference 0/15 failures, temporal 0/11, comparison 9/15,
null 5/9. The two solved types (inference, temporal) show the
retrieval pipeline working perfectly. The two struggling types
show distinct issues:

**Null failures (5): retrieval precision tradeoff.** Hybrid
retrieval returns topically-related context for every query —
even queries with no answer in the corpus. The 8B model lacks
the sophistication to distinguish "related context" from
"answering context" and hallucinates entities from misleading
passages. This is a fundamental recall/precision tradeoff:
single-embedding retrieval returns nothing useful for null
queries (good for abstention) while ours returns related
content (bad for abstention, but great for temporal/inference).

**Comparison failures (9): three distinct sub-causes.** The
"wrong entity" (3) is purely a prompt issue — the paper prompt
says "word or entity" which misleads the model on yes/no
questions. The "wrong yes/no" (3) is retrieval — specific
articles not in top-6. The "false abstention" (3) is model
capability — evidence present but 8B can't synthesize.

**Key insight: the observability fields (EVIDENCE + CONFIDENCE)
correctly diagnose every failure.** The evidence field shows
exactly what the model found and why it went wrong. The
confidence field is uniformly high even for wrong answers —
logprobs confirm the model is confidently wrong in all cases.
Confidence/logprobs don't distinguish failure types, but the
evidence text does.

For a **maximally fair paper comparison**, report both:
1. Paper-matched (72% hi, +5pp) — controls for all confounds
2. Our best config (86% substr) — shows ceiling with optimized
   prompt + more context
3. Per-type breakdown showing the recall/precision tradeoff
4. Failure analysis showing the 3 root causes (retrieval gap,
   model capability, prompt confusion)

### Research: retrieval metadata for abstention (2026-04-08)

**Question:** Can adding retrieval metadata (source alignment,
confidence scores, negative evidence) to the LLM context help
small models abstain correctly on null queries?

**Answer: No evidence this works. Every successful approach uses
external gating, not prompt metadata.**

| Approach | Mechanism | Requires | Result |
|----------|-----------|----------|--------|
| Google "Sufficient Context" (ICLR 2025) | Separate Gemini 1.5 Pro autorater + logistic regression on confidence | Frontier-class autorater model | 2-10% improvement |
| CRAG (2024) | Fine-tuned T5-large evaluator classifies Correct/Ambiguous/Incorrect | Separate fine-tuned model | 78% correct-class accuracy |
| GRACE (Jan 2025) | RL fine-tuning teaches model to abstain via path selection reward | Custom RL training | 66% unanswerable accuracy (Qwen 4B) |
| Self-RAG (2023) | Special reflection tokens trained into the model | Custom fine-tuning | Strong at 7B but requires training |

Common pattern: all remove bad context or gate externally BEFORE
the model generates. None add metadata to the prompt and hope the
model reasons about it. Google's paper specifically found that
"RAG paradoxically reduces abstention — additional context increases
confidence, leading to hallucination." Adding MORE structured context
(retrieval reports) may make this worse.

**No paper has tested** simply prepending "Retrieved from: Hindustan
Times (query asked for: Times of India)" and measuring if an
off-the-shelf 8B model abstains better. This is a research gap but
the direction of evidence is negative — small models don't reliably
use metadata signals.

**Practical implication for JustSearch:**

The highest-value approach is **source-constrained retrieval at
query time**, not prompt metadata at generation time:

1. **Source filter before retrieval.** If the query mentions a
   specific source ("according to a BBC article"), extract the
   source via QU and filter `meta_source: BBC` before retrieval.
   If 0 results, return "Insufficient information" without asking
   the model. Deterministic, zero false positives.

2. **Existing `context_sufficient` classifier as external gate.**
   Already built in our pipeline. Runs after retrieval, before
   generation. Needs calibration (deferred from Phase 6).

3. **Do NOT add retrieval reports to the prompt.** No evidence
   this helps small models, and the Google research suggests
   more context = more hallucination, not less.

This shifts the abstention decision from the LLM (unreliable at
8B scale) to the retrieval layer (deterministic, auditable). The
same pattern we already use for agents via MCP — the tool surface
provides the intelligence, not the model.

### Uncertainty resolution (2026-04-08)

**Item 2 (format instruction confound): RESOLVED.**
The full run will use the paper prompt without observability format
(ANSWER/EVIDENCE/CONFIDENCE). This eliminates the confound. The
50q observability run was diagnostic only.

**Item 4 (null query source pattern): RESOLVED.**

| Category | Count | % of null |
|----------|-------|-----------|
| Mentions source NOT in corpus | 198 | **66%** |
| Mentions source that IS in corpus | 78 | 26% |
| No detectable source mention | 25 | 8% |

**66% of null queries reference sources absent from the corpus.**
Source-constrained retrieval (check `meta_source` facets before
retrieval, return "Insufficient information" if 0 hits) would
correctly handle 198/301 null queries deterministically. This is
much broader than initially estimated (was "5-10%", actually 66%).

The remaining 78 (26%) have sources present but the SPECIFIC
information asked about doesn't exist — these require model-level
reasoning or a more sophisticated relevance classifier.

**Item 1 (mode ablation): READY TO RUN.**
`JUSTSEARCH_RAG_RETRIEVE_MODE=bm25` gives BM25-only retrieval.
Default is "auto" (hybrid). Running Tier 2 with BM25-only would
show what single-leg retrieval achieves, isolating the multi-leg
fusion contribution. Can run alongside the full Llama 8B eval by
doing two sequential runs:
  1. Full 2,556q hybrid (default) — primary result
  2. Full 2,556q BM25-only — ablation baseline
Both ~4 hours each at ~6s/query with Llama 8B.

**Item 5 (vector-only Tier 1): READY TO RUN.**
Tier 1 retrieval-only eval (no LLM needed) with different search
modes. Can isolate dense-only vs hybrid retrieval quality on
Hits@K. Requires backend but no LLM — fast to run.

### Ablation plan for publication

Three runs needed (total ~12 hours, $0):

| Run | Mode | Purpose | Time |
|-----|------|---------|------|
| **A** | Hybrid (default) | Primary result | ~4h |
| **B** | BM25-only | Isolate BM25 contribution | ~4h |
| **C** | Tier 1 all modes | Retrieval-only Hits@K per mode | ~5min |

Run A vs B directly measures the value of multi-leg fusion.
If A=76% and B=65%, the +11pp is from SPLADE+dense+reranking.
Run C provides the retrieval-quality explanation without any
LLM confound.

### Source existence check implementation (2026-04-08)

Implemented `--source-check` flag on `jseval tier2-eval`.
Pre-retrieval deterministic abstention when query mentions
sources absent from the corpus.

**Mechanism:**
1. On startup: fetch corpus `meta_source` facets via search API
2. Per query: extract mentioned publication names from query text
3. Multi-word sources (e.g., "The New York Times"): safe substring
   match. Single-word sources (e.g., "Nature"): only matched in
   publication context ("article from Nature", not "the nature of")
4. If any mentioned source has 0 documents in corpus: return
   "Insufficient Information" without calling LLM

**Offline validation on 50q:**
- Precision: **100%** (0 false positives)
- Recall: **89%** (8/9 null queries caught)
- 1 missed null: source IS in corpus but specific article isn't
  (requires model-level reasoning, not source checking)

**Full dataset scope (from earlier analysis):**
198/301 null queries (66%) mention sources absent from corpus.
Expected to convert ~130 LLM false-positives to correct
deterministic abstentions on the full 2,556q run.

**Revised publishable angle:** "Hybrid Retrieval Outperforms Graph
RAG on Multi-Hop Questions" — same Llama 3.1-8B model, matched
prompt and context budget, retrieval pipeline is the only variable.
Full 2,556q ablation run needed for publication.

### Tier 2 implementation findings (2026-04-07)

**Pipeline validated on 5 queries (1 correct, 3 wrong, 1 type missing).**
retrieve-context → llama-server `/v1/chat/completions` → score.
Avg 2.6s/query on GPU. Full 2,556q estimated 1.8 hours.

**Issues encountered during Tier 2 setup:**

1. **CUDA variant not auto-selected.** `InferenceConfig.detectCudaAvailable()`
   calls `VramDetector.isCudaAvailable()` → `getTotalVramBytes() > 0` →
   `nvidia-smi`. Returns false in the Gradle-spawned JVM despite
   nvidia-smi working in the shell (12,282 MiB detected). Likely PATH
   issue in the JVM environment. Result: CPU-only llama-server selected
   (6.7 tok/s instead of 51.9 tok/s).
   **Workaround:** Set `JUSTSEARCH_SERVER_EXE` to the explicit CUDA
   variant path: `modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe`.

2. **Gradle configuration cache blocks env var forwarding.**
   `providers.environmentVariable("JUSTSEARCH_GPU_LAYERS").orNull` is
   evaluated at configuration time. Stale config cache replays null even
   when the env var is set in the shell. `--no-configuration-cache`
   should fix but didn't for `JUSTSEARCH_CONTEXT_SIZE` (still 4096).
   `JUSTSEARCH_GPU_LAYERS` DID reach llama-server (`-ngl 99` in command
   line) but had no effect because the CPU variant ignores it.

3. **Context window too small.** llama-server starts with `-c 4096`.
   RAG retrieval at `max_tokens=4096` returns ~4800 context tokens.
   Adding system prompt + question exceeds the window → "Context size
   exceeded" error. `JUSTSEARCH_CONTEXT_SIZE=16384` env var didn't
   propagate through Gradle.
   **Workaround:** Reduce `max_tokens` to 2048 in retrieve-context call.
   This fits in 4096 but limits multi-hop evidence quality.

4. **Null query scoring gap.** Ground truth is "Insufficient information."
   LLM answers "None" — not in the abstention phrase list. Need to add
   "none", "not available", etc. to `_ABSTENTION_PHRASES`.

5. **Yes/No question accuracy.** 2/3 yes/no questions wrong. Likely
   insufficient context at 2048 tokens for multi-hop evidence requiring
   2-4 source documents. Context window fix (item 3) is prerequisite.

**Root causes identified and fixed (2026-04-07):**

Context window: `UiSettings.contextLength` defaults to 4096 (Java field
default). `HeadlessApp` unconditionally injects this via
`setSysPropIfBlankWithSource()` at ordinal 500 (system property), which
beats the env var at ordinal 400. The builder's 8192 default never
applies because the UiSettings 4096 always wins.
**Fix:** Skip UiSettings context injection when `JUSTSEARCH_CONTEXT_SIZE`
env var is explicitly set (`HeadlessApp.java`). Also added
`JUSTSEARCH_CONTEXT_SIZE` to `HEADLESS_AI_ENV_VARS` in
`build.gradle.kts` so it's forwarded to the child JVM.
**Validated:** `-c 16384` in llama-server command line.

CUDA variant: `VramDetector.isCudaAvailable()` returns false in the
Gradle-spawned JVM despite nvidia-smi working in the shell. The CPU
variant at `resources/headless/native-bin/` is selected instead of the
CUDA variant at `native-bin/llama-server/variants/cuda12/`.
**Workaround:** Set `JUSTSEARCH_SERVER_EXE` to explicit CUDA path.
The root cause (nvidia-smi PATH in JVM environment) is pre-existing.

**Tier 2 validation runs (2026-04-07):**

8q smoke (thinking=OFF, max_tokens=256, 16K ctx):
  5/8 (62%) — inference 2/2, comparison 2/2, temporal 1/2, null 0/2.
  Avg 5.0s/query. Null failures due to missing abstention phrases.

20q diagnostic (thinking=ON, max_tokens=4096, 16K ctx):
  9/20 (45%) — inference 3/5, comparison 1/5, temporal 0/5, null 5/5.
  Avg ~55s/query. Two distinct failure modes found:

  **Failure mode 1: thinking overflow (7/20).** Model spends all 4096
  completion tokens on chain-of-thought reasoning and produces an
  empty answer. Verbose thinking ("Analyze the Request... Constraint:
  Be concise...") never reaches a conclusion. All hit 88s and
  max completion_tokens. Budget problem, not reasoning problem.

  **Failure mode 2: false abstention (3/20).** Model answers
  "Insufficient information" when evidence IS in context. System
  prompt's abstention instruction triggers false negatives on
  multi-hop questions where evidence spans multiple passages.

  Thinking gave full observability via `reasoning_content` field
  (DeepSeek format, exposed by llama-server `--reasoning-format
  deepseek`).

**Fixes applied and validated (20q rerun, thinking=OFF):**

Prompt changed from cautious ("If the context does not contain enough
information to answer, say Insufficient information") to confident
("Only say Insufficient information if the context contains absolutely
nothing relevant"). Added yes/no instruction. Expanded abstention
phrase list.

Result: **17/20 (85%)** — up from 45% (thinking ON) and 62% (old prompt).

| Type | Old prompt (8q) | Thinking ON (20q) | **New prompt (20q)** |
|------|----------------|-------------------|---------------------|
| inference | 2/2 | 3/5 (60%) | **5/5 (100%)** |
| null | 0/2 | 5/5 (100%) | **5/5 (100%)** |
| comparison | 2/2 | 1/5 (20%) | **4/5 (80%)** |
| temporal | 1/2 | 0/5 (0%) | **3/5 (60%)** |

Remaining 3 failures diagnosed:
- Q10 (comparison): 1/2 evidence docs not retrieved → retrieval gap
- Q11 (temporal): both docs present, model says "insufficient" →
  reasoning failure (temporal logic hard for 9B)
- Q14 (temporal): 1/3 evidence docs present → retrieval gap

2/3 failures are retrieval gaps (evidence docs not in top-10, aligns
with Tier 1 Hits@10=82.6%). 1/3 is model reasoning limitation.

**Observability without thinking (2026-04-07):**

Tested two llama-server features for diagnostic observability:

1. **logprobs** (`logprobs: true, top_logprobs: 3`): per-token log
   probabilities. Zero overhead. Shows model certainty on answer tokens.
2. **Structured output** (`response_format: json_schema`): grammar-
   constrained JSON with `{answer, evidence_summary, confidence}`.
   Adds ~100 extra completion tokens (~4s overhead per query).

Both work with our llama-server build. Combined in a 20q run:
**17/20 (85%)** — same accuracy as unstructured, full observability.

Diagnostic findings from the 3 failures (Q10, Q11, Q14):
- All answered "Insufficient information" with confidence="high"
  and logprob=-0.02 (~98% probability). Model is **confidently wrong**.
- evidence_summary shows the model found related articles but couldn't
  connect evidence across documents for multi-hop reasoning.
- Logprobs don't help distinguish failure modes here (uniformly
  confident). The evidence_summary field is the key diagnostic —
  shows what the model found and why it abstained.
- Root cause: 2/3 are retrieval gaps (evidence doc not in top-10),
  1/3 is model reasoning (temporal logic across documents).

**Tier 2 eval infrastructure built (2026-04-07):**

Added `jseval tier2-eval` command with:
- Structured JSON output (`{answer, evidence_summary, confidence}`)
  via grammar-constrained decoding + logprobs
- Dual scoring: exact match AND substring (paper uses exact match)
- Per-query-type breakdown, confidence distribution, latency split
- Full observability without thinking mode

**20q validation with final infrastructure:**

| Metric | Score |
|--------|-------|
| Exact match | **75.0%** |
| Substring | **80.0%** |
| Avg latency | 10.0s/query |

| Type | n | Exact | Substring |
|------|---|-------|-----------|
| inference | 9 | 78% | 78% |
| null | 3 | 100% | 100% |
| comparison | 6 | 67% | 67% |
| temporal | 2 | 50% | 100% |

5 failures: 3 false abstentions (model says "Insufficient" when
evidence present), 1 reasoning error, 1 verbose answer (correct
by substring, not exact). Synthesis bridge prompt reduced false
abstention from 10+ (thinking run) to 3.

**Production Tier 2 config (ready for full 2,556q run):**
- `jseval tier2-eval --queries MultiHopRAG.json`
- Structured JSON + logprobs for observability
- Dual scoring (exact match for paper comparison, substring for context)
- Synthesis bridge prompt (answer-first, cross-passage instruction)
- GPU (CUDA variant) + 16K context window
- Estimated: ~7 hours with structured output, $0 cost
- Requires explicit user approval per standing rule

### Three-tier comparison on 50q subset (2026-04-08)

Same 50 questions (eval-queries-50q.json) across all three tiers:

| Tier | What | Accuracy | Cost | Avg latency |
|------|------|----------|------|-------------|
| Tier 1 | Retrieval (Hits@10) | 82.6%* | $0 | 31ms |
| **Tier 2** | **Single-shot RAG (Qwen 3.5 9B)** | **80% exact** | **$0** | **10.9s** |
| **Tier 3** | **Agentic RAG (Haiku)** | **94%** | **$0.057/q** | **~30s** |
| Published | GPT-4 single-shot | 56% | ~$0.10/q | — |

*Tier 1 run on full 2,556q; Tier 2/3 on 50q subset.

By question type (Tier 2 vs Tier 3):

| Type | n | Tier 2 | Tier 3 (Phase 6) | Gap |
|------|---|--------|-------------------|-----|
| inference | 15 | 87% | 100% | +13pp |
| null | 9 | 100% | 100% | 0 |
| temporal | 11 | 73% | 91% | +18pp |
| comparison | 15 | 67% | 87% | +20pp |
| **Total** | **50** | **80%** | **94%** | **+14pp** |

**The Tier 2 → Tier 3 gap is 14pp.** This is the measurable value
of agentic MCP tool design: on the same retrieval pipeline, same
corpus, same 50 questions, the agent's ability to search multiple
times, refine filters, and explore adds 14pp over a single
retrieve+answer pass.

The gap is largest on comparison (+20pp) and temporal (+18pp) —
multi-hop question types where the agent can decompose into
per-source queries. Inference is nearly solved by both tiers.
Null queries are perfect for both.

Tier 2 issues found during 50q run:
- llama-server crashed after Q2 on first attempt (KV cache
  exhaustion with 16K context × 4 slots). Added retry logic
  with 10s backoff — second run completed 50/50 with 0 errors.
- 10 failures: 5 false abstention (model says "Insufficient"
  when evidence present), 3 reasoning errors (wrong answer
  despite evidence), 2 retrieval gaps.

### Full run cost/time estimates

| Tier | Queries | Time | Cost | Notes |
|------|---------|------|------|-------|
| **Tier 1** | 2,556 | **81s** | $0 | Already completed. Hits@10=82.6%, MRR=0.574 |
| **Tier 2** | 2,556 | **~7.8 hours** | $0 | 10.9s/q × 2556. Local Qwen 3.5 9B GPU. Structured output. |
| **Tier 3** | 2,556 | **~21 hours** | **~$148** | 30s/q × 2556. Haiku $0.058/q. Sequential agent eval. |
| Tier 3 ×10 | 2,556 | **~2.1 hours** | **~$148** | Parallel=10. Same cost, 10x faster wall time. |
| **Tier 2+3** | 2,556 | **~10 hours** | **~$148** | Tier 2 (7.8h) + Tier 3 parallel (2.1h). |

Notes:
- Tier 1 is done. No rerun needed.
- Tier 2 is $0 (local LLM) but GPU-bound (~8 hours). Can run
  overnight. No API cost risk.
- Tier 3 is expensive. 2,556 × $0.058 = ~$148 in Haiku API costs.
  The 50q agent eval cost ~$2.85 — scaling to full is 51× more.
  Parallel=10 reduces wall time to ~2 hours but same total cost.
- Tier 3 full run requires explicit user approval (standing rule).
- For publication, Tier 1 (done) + Tier 2 (local, $0) gives the
  retrieval + single-shot comparison against published baselines.
  Tier 3 adds the agentic uplift story but at $148 cost.

### Remaining actionable items

- **Feature discovery**: Consider adding a one-time "capabilities"
  hint on the first answer response listing available advanced
  features (LUCENE, full docs, excerpts) with example syntax
- **Sufficiency calibration**: Build labeled dataset from 50q eval
  to calibrate context_sufficient classifier
- **Missing regression tests**: FULL_DOCUMENT mode label, coverage
  hint suppression, context_sufficient null surfacing

---

## 50-query eval results (2026-03-29)

### Summary

| Run | Condition | Tools | Queries | Accuracy | Avg cost | Avg turns |
|-----|-----------|-------|---------|----------|----------|-----------|
| **No JustSearch** | **A** | **file only** | **36*** | **83%** | **$0.139** | **18.7** |
| 362 baseline | C | 7 MCP | 20 | 75% | $0.082 | 12.4 |
| 366 Phase 2 | C | 7 MCP | 50 | 72% | $0.067 | 10.5 |
| **366 Phase 4** | **C** | **4 MCP** | **50** | **92%** | **$0.055** | **7.5** |
| **366 Phase 5** | **C** | **4 MCP+norm** | **50** | **92%** | **$0.053** | **7.2** |
| 366 Phase 6 (bloat) | C | 4 MCP+6a-d | 50 | 86% | $0.061 | 7.2 |
| **366 Phase 6 final** | **C** | **4 MCP+NER+facets** | **50** | **94%** | **$0.057** | **7.2** |
| Manual baseline | — | — | 5 | 100% | — | 1.2 calls |

*36/50 complete (parallel run, remaining queries hitting $0.50 budget cap).

**JustSearch value (Condition A vs C, Phase 5):**
- **+9pp accuracy** (83% -> 92%)
- **-62% cost** ($0.139 -> $0.053 per query)
- **-61% turns** (18.7 -> 7.2)
- Total cost: $5.01 for 36 queries (A) vs $2.57 for 50 queries (C)

Condition A agents spend most of their budget grepping through
611 files to find relevant documents. JustSearch replaces this
brute-force search with indexed retrieval, saving both cost
and turns while improving accuracy.

**Phase 4 impact (tool consolidation 7->4):** +20pp accuracy,
-29% turns, -18% cost. Zero errors. The largest single
improvement in the entire tempdoc — validating the Block/AWS
research that fewer task-oriented tools outperform many
capability-oriented tools.

### By question type (Phase 5)

| Type | Correct | Accuracy | Answer-first |
|------|---------|----------|-------------|
| inference_query | 15/15 | **100%** | 9/15 (60%) |
| temporal_query | 9/11 | **82%** | 5/11 (45%) |
| comparison_query | 13/15 | **87%** | 4/15 (27%) |
| null_query | 8/9 | **89%** | 1/9 (11%) |

### Phase 2 reflection themes (7-tool, 50 reflections)

| Theme | Mentions | Status |
|-------|----------|--------|
| Boolean/structured queries | 38 | **Fixed:** 2d (LUCENE syntax documented) |
| Snippet/highlight | 35 | **Fixed:** 2c (includeExcerpts) |
| Multi-hop/cross-doc | 35 | Agent strategy, not tool issue |
| Date filtering | 28 | Partially addressed (ranges exist) |
| Empty content_preview | 24 | **Fixed:** 2b (chunk fallback) |
| Missing metadata filters | 21 | **Fixed:** 1a/1c/2a (facets + hints) |
| Exact/phrase search | 16 | **Fixed:** 2d (LUCENE syntax) |
| Preview 404 | 7 | **Fixed:** 2e (path normalization) |

### Phase 4 reflection themes (4-tool, 50 reflections)

| Theme | ~Mentions | Status |
|-------|-----------|--------|
| Source filter value mismatch | ~25 | **Fixed (Phase 5).** Dropped to 1 mention after hybrid normalization. |
| No exact-match / keyword search | ~8 | **Open.** Agents want scoped literal match within specific docs. |
| Chunk boundary fragmentation | ~7 | **Open.** Evolved to "full document retrieval" (17 mentions in Phase 5). |
| Date filtering doesn't work | ~6 | **Largely addressed.** Dropped to 2 mentions in Phase 5. |
| Multi-hop requires multiple queries | ~6 | Agent strategy. Evolved to "cross-document join" (19 mentions in Phase 5). |
| `answer` with `doc_ids` returns empty | 1 | **Open.** Q41 agent scoped answer to specific docs, got zero chunks. |

**What improved vs Phase 2:** Agents now use `justsearch_answer`
as primary tool (consolidation worked). No complaints about tool
discovery, empty previews, or missing LUCENE syntax. `meta_source`
filtering is heavily used — the remaining problem is value
matching, not feature absence.

### Phase 5 reflection evolution

**What changed from Phase 4 to Phase 5:** The complaint profile
shifted from operational friction to aspirational capabilities.
Filter value mismatch (the #1 Phase 4 theme) nearly disappeared.
The new top themes are:

1. **Entity-level facets (29):** Agents want "which companies
   appear most frequently?" — entity extraction beyond metadata.
2. **Cross-document joins (19):** "Find entities in BOTH source A
   and source B." Fundamentally multi-step reasoning.
3. **Full document retrieval (17):** Agents want doc-level ranking
   and retrieval alongside chunk-level. "Retrieve full document
   by ID" and "document-level summaries."
4. **Scoped keyword search (10):** "Search for 'personal gain'
   within article_030.md only." Targeted literal match.

Entity facets → 326 (separate agent). The other three actionable
themes → Phase 6 above. Cross-document joins (19) are agent
strategy, not a tool fix.

### Filter and feature adoption (Phase 2)

- 32/50 agents used `meta_source` filter
- 0/50 agents used `boostFilters`
- 43/50 agents mentioned wanting facet discovery
- 6/50 complained filters missing despite schema

---

## Failure analysis (2026-03-29)

### Scoring bug: null queries are 8/9 correct, not 0/9

The eval uses exact substring matching: `ground_truth.lower() in
agent_answer.lower()`. Ground truth for null queries is
"Insufficient information." but agents say "I cannot find..." /
"unable to find..." — semantically correct abstention, scored
as wrong.

**8 of 9 null query agents correctly abstained.** They searched
extensively, found nothing, and said so. Only Q14 (which errored
with $0.50 budget exhaustion) failed genuinely.

### Revised accuracy

| Scoring | Correct | Accuracy |
|---------|---------|----------|
| Original (exact match) | 36/50 | **72%** |
| With null-query credit | **44/50** | **88%** |

### True failure breakdown (6 queries)

| Mode | Count | Queries |
|------|-------|---------|
| Reasoning error | 5 | Q4, Q8, Q12, Q30, Q37 |
| Budget exhaustion | 1 | Q14 (null, $0.50 cap) |

All 5 reasoning errors are comparison or temporal queries where
the agent found the right documents but drew the wrong conclusion
from the text. These are agent reasoning failures, not tool
limitations — no MCP change would fix them.

### boostFilters adoption: zero out of 50

Haiku never used boostFilters. Three hypotheses:
1. Description problem — Haiku doesn't read param descriptions
2. Model capability — too complex for Haiku
3. Not useful — agents that know filters use hard filters

Only testable with an Opus eval.

---

## Phase 4 — Agent experience beyond search

Investigated 2026-03-29. Phases 1-2 focused on the search
tool. The agent experience is broader than one tool.

### 4a. Tool orchestration — the biggest remaining gap

**Finding:** 41/50 agents use search+preview (the naive path).
Only 4/50 discovered retrieve_context alongside search, and
those had **100% accuracy** vs 70% for search+preview.

| Strategy | Count | Accuracy | Avg turns | Avg cost |
|----------|-------|----------|-----------|----------|
| search+preview | 41 | 70% | 10.2 | $0.064 |
| search+rc+preview | 4 | **100%** | 10.0 | $0.057 |
| search+rc | 3 | 66% | 13.7 | $0.114 |
| search_only | 2 | 50% | 13.5 | $0.079 |

Agents that discovered retrieve_context called it "the
breakthrough" and "excellent." But 86% of agents never found
it — they used the more natural search → preview loop.

**Action:** Add inter-tool guidance to descriptions. The
search tool description should say "For answering questions,
use retrieve_context instead of reading individual documents
with preview." The retrieve_context description should be
the primary recommendation for question-answering agents.

### 4b. retrieve_context is underused

**Finding:** 7/50 agents mentioned retrieve_context.
Those who used it praised it highly:
- "pulled together relevant chunks from multiple documents"
- "the breakthrough — it gave me the generosity quote and
  the AI agents vision detail in a single retrieval"
- "especially useful — returned chunked excerpts with
  metadata (source, author)"

The tool is well-designed but undiscoverable. Agents default
to the search+preview pattern because search is the first
tool they try, and preview is the obvious "read more" action.

**Action:** Reorder tool descriptions so retrieve_context
is more prominent. Add "For multi-document questions, this
is more efficient than search + preview" to its description.

### 4c. Output schemas should be typed

**Finding:** The MCP spec (2025-06-18) explicitly says:
"Clients SHOULD validate structured results against this
schema" and structuredContent enables multi-step tool
chaining via JSON Pointer references. Our `z.any()` outputs
mean clients can't validate, auto-complete field names, or
chain tools.

**Action:** Type the output schemas — define `SearchHit`,
`RetrieveContextChunk`, `QualitySignals`, `CitationMatch`
as proper Zod objects. This gives MCP clients field-level
hints before the first call.

### 4d. Token efficiency — deferred

Backend was down during investigation. Need to measure
response token sizes before/after Phase 1-2 enrichments.

### 4e. Dead tools

**Finding:**

| Tool | Mentions | Used for answering? |
|------|----------|---------------------|
| search | 50/50 | Yes |
| preview | 45/50 | Yes |
| retrieve_context | 7/50 | Yes (high accuracy) |
| ingest | 5/50 | Setup only |
| status | 2/50 | Setup only |
| suggest | 2/50 | No |
| match_citations | 0/50 | No |

match_citations and suggest are dead weight in the schema.
They consume schema tokens at session start but agents never
use them. However, the eval task (question-answering) may not
represent all use cases — suggest could be useful for browsing
tasks, match_citations for citation-heavy workflows.

**Action:** Don't remove them, but consider a "core tools"
vs "extended tools" split if the MCP spec supports selective
tool advertisement.

### 4f. Cross-model validation — deferred

Needs Opus eval run. Deferred pending user approval.

### 4g. Error recovery guidance

**Finding:** 8 error codes, none with recovery hints:

| Code | Meaning | Correct agent action |
|------|---------|---------------------|
| NOT_CONNECTED | Backend down | Report to user |
| NOT_READY | Worker starting | Wait ~30s, retry |
| TOKEN_REJECTED | Session expired | Report to user |
| NO_TOKEN | Old JustSearch version | Report to user |
| VALIDATION_ERROR | Bad arguments | Fix arguments |
| RESPONSE_TOO_LARGE | Response overflow | Reduce limit |
| HTTP_ERROR | Generic backend error | Retry once |
| PREVIEW_ERROR | Doc not found | Use retrieve_context |

**Action:** Add recovery hints to error messages. E.g.,
NOT_READY → "The Knowledge Server is starting up. Wait
30 seconds and retry." PREVIEW_ERROR → "Document not found.
Try using retrieve_context to get relevant passages instead."

---

## Open issue: LLM resource scheduling

QU and expansion both fire at the start of `doSearch()` and
compete for the same llama-server slot. With a single-slot
server, one request queues behind the other. The original QU
deadline (2s) was calibrated in isolation (~1s latency) but
fails in production where expansion takes the slot first,
adding 3-4s of queue wait.

Raised to 8s as a workaround. The real fix is coordinated
scheduling: cancel expansion when QU is pending (QU subsumes
expansion's purpose), or priority queueing at the llama-server
level, or serializing QU before expansion.

This is shared between 363 (QU owner) and 366 (MCP experience
owner). The QU timeout directly affects whether agents see the
`queryUnderstanding` field — too tight and it's always null,
too loose and search latency suffers.

---

## Filter value normalization (research, 2026-03-30)

### The problem

Agents use `meta_source` filters frequently (Phase 4 eval), but
hit value mismatches. Two sub-problems:

**A. Case mismatch:** Agent sends `"CNBC"`, index stores `"cnbc"`.
Index lowercases at write time (`putIfNonBlankLower` in
`IndexingDocumentOps`), but filter values are passed to Lucene
as-is. **Fix:** `.toLowerCase(Locale.ROOT)` in the adapter.
Trivial, implemented.

**B. Value mismatch:** Agent sends `"cnbc"`, index stores
`"cnbc | world business news leader"`. Agent sends
`"the independent"`, index stores `"the independent - sports"`.
Even with case fixed, exact TermQuery fails.

### Research: industry approaches

| Approach | Schema change | Re-index | LLM needed |
|----------|--------------|----------|------------|
| Two-step agent pattern (facets → filter) | No | No | Agent LLM does matching |
| Keyword normalizer with `pattern_replace` | Yes | Yes | No |
| Multi-field: keyword + text analyzed | Yes | Yes | No |
| Wildcard query on keyword (low cardinality ok) | No | No | No |
| Backend filter value normalization | No | No | No |

Sources: Elasticsearch normalizers, Algolia `searchForFacetValues`,
Solr `facet.contains`, Azure AI Search normalizers, Typesense
facet search.

### Relationship to QU (Phase 3)

The QU layer (363 Track B) already solves this problem for
plain-text queries — it extracts entities and grounds them
against the `cachedFacetSnapshot`. But **QU is bypassed when
the agent provides explicit filters** (the common case in
Phase 4 eval). QU and explicit filters are mutually exclusive:

```java
boolean hasExplicitFilters = req.filters() != null || req.boostFilters() != null;
if (!hasExplicitFilters && ...) {
    quFuture = quService.extract(queryText, cachedFacetSnapshot);
}
```

### Option B: backend-layer string normalization (rejected)

Normalize filter values against the cached facet vocabulary
using a matching cascade: exact → prefix → token-split →
contains (≥3 chars) → fuzzy. No LLM, no schema change, no
re-index, near-zero latency.

Research (2026-03-30) confirms this cascade is the standard
approach in production search systems (Caffeine cache,
service-layer placement, response transparency metadata).

**Rejected because:** Builds a parallel normalization system
alongside the existing QU infrastructure that already solves
the same problem. The right approach is to extend QU, not
duplicate it with string matching.

### LLM-integrated approach (recommended)

**Key insight:** The QU layer already has everything needed
for filter value normalization:

1. **`cachedFacetSnapshot`** — 5-minute TTL cache of all
   top facet values with counts, refreshed from the Worker:
   ```
   Known index contents:
   meta_source: the verge (1523), techcrunch (892),
     cnbc | world business news leader (234), ...
   ```

2. **QU prompt grounding** — `qu.v1.txt` Rule 3 explicitly
   instructs: "For meta_source, ONLY use values from the
   known sources list. Do not invent source names."

3. **LLM semantic matching** — strictly better than any
   string cascade at understanding `"CNBC"` →
   `"cnbc | world business news leader"`, because it has
   semantic understanding, not just substring matching.

**The problem is architectural:** QU is bypassed when the
agent provides explicit filters. The LLM that knows how to
map approximate values to exact indexed values never gets
asked.

**Proposed change:** Remove the bypass. Fire QU even when
explicit filters exist, with an extended prompt that also
receives the agent's filter values:

```
Query: "AI developments"
Agent-provided filters: meta_source: ["CNBC"]
Normalize these filter values against the known sources
list and extract any additional filters from the query.
```

QU returns normalized values. The agent's hard filter intent
(FILTER clause) is preserved, but the value is corrected to
the exact indexed form. This reuses:
- The existing facet snapshot and its 5-min TTL cache
- The existing LLM call infrastructure and deadline
- The existing response parsing
- The existing prompt template (extended, not replaced)

**Blocker: LLM resource contention.** Resolved — GPU
offloading (`-ngl 99`) reduces LLM latency from 5-15s (CPU)
to 0.4-1.2s (GPU). jseval `--llm` now sets GPU layers by
default.

**Status:** Implemented as `FilterNormalizationService`.
Experiments validated the approach (Exp 1-3). End-to-end
validation confirmed CNBC normalization (488ms, 3 hits).
Identity mapping failures on multi-variant expansion traced
to free-form prompting (not model capability) — hybrid
deterministic+LLM architecture planned (§5h).

### Experiments (filter normalization via QU)

**Exp 1. Can the LLM normalize filter values? (offline)**
Feed the QU prompt + facet snapshot + approximate filter values
to the LLM. 15 test cases from Phase 4 eval mismatches.
No backend needed.

**Result: 15/15 (100%).** Haiku correctly normalized every
test case:
- Case only (`"Fortune"` -> `"fortune"`): 2/2
- Case + multi (`"The Verge", "Engadget"`): 1/1
- Abbreviation with pipe (`"CNBC"` -> `"cnbc | world
  business news leader"`): 3/3
- Abbreviation with colon (`"Live Science"` -> `"live
  science: the most interesting articles"`): 1/1
- Abbreviation -> multiple variants (`"The Independent"` ->
  all 3 independent-* sources, `"FOX News"` -> all 3
  fox news-* sources): 2/2
- Missing prefix (`"New York Times"` -> `"the new york
  times"`): 2/2
- Format mismatch (`"CBS Sports"` -> `"cbssports.com"`): 1/1
- Nonexistent sources (`"Bloomberg"`, `"Al Jazeera"`,
  `"BBC"` -> correctly omitted): 3/3

**Conclusion:** The LLM approach is strictly viable. Even
Haiku handles every category including the hardest cases
(format mismatch, multi-variant expansion) that a string
cascade would struggle with.

**Exp 2. Latency of filter-validation-only prompt.**
Stripped-down prompt with only facet snapshot + filter values
(no full QU extraction). Measure latency vs full QU.

**Result: Shorter prompt is slower, not faster.**
Direct llama-server calls (bypassing Java adapter):
- Full QU prompt (~431 words): **avg 3,243ms** (2.8-3.6s)
- Minimal prompt (~113 words): **avg 4,617ms** (3.5-7.6s)
- Speedup: **0.7x** (minimal is 1.4x slower)

The full prompt's few-shot examples help the model respond
faster by constraining output format. The minimal prompt
produces more verbose/uncertain output, taking longer.

Both prompts produce correct normalizations (CNBC -> full
name, Fortune -> lowercase, FOX News -> health variant).

**Critical finding:** Direct llama-server latency is ~3s,
but through the Java adapter QU takes 5-8s (Exp 3). The
~3-5s overhead is in the adapter layer (HTTP round-trips,
serialization, queueing), not LLM inference. Optimizing
the prompt won't help — the adapter overhead dominates.

**Exp 3. QU without expansion (slot contention test).**
Disable expansion, give QU the full llama-server slot.
Measure latency in HYBRID mode (expansion already disabled).

**Result: Slot contention hypothesis disproved.** Even with
the full llama-server slot (no expansion in HYBRID mode),
QU is inherently slow:
- QU fired: **2/10** queries (8 timed out at 8s deadline)
- When it succeeded: **5,813ms** and **7,908ms**
- Baseline search without QU: **~1,500ms**
- QU adds **4-7 seconds** per search

The local LLM takes 5-8s for the QU prompt regardless of
slot contention. The original "expansion competes for the
same slot" theory was wrong — even with the slot free, the
model is too slow. This means:
- Raising the deadline further won't help (80% already
  time out)
- Cancelling expansion won't help (already disabled in
  HYBRID)
- A **lighter prompt** (Exp 2) or **faster model** are the
  only paths to making QU practical

**Exp 4. Agent eval with QU + filter normalization.**
50q eval with QU enabled, bypass removed, extended prompt.
Compare against 92% Phase 4 baseline. ~$3.50 cost.
**Blocked** on finding a viable latency path first.

**Order:** 1 (done, 100%) → 3 (done, disproved) → 2 (done,
shorter is slower) → 4 (blocked).

**Revised assessment (2026-03-30):** The actual root cause
was **CPU-only inference** (`-ngl 0` default). The 9B model
on CPU runs 5-10 tok/s; on GPU (RTX 4070, `-ngl 99`) it
runs 40-80 tok/s. Exp 2-3 measured CPU performance without
realizing it. With GPU offloading:
- Filter normalization: **0.4-1.2s** (was 3-15s on CPU)
- Well within the 8s deadline
- Viable for production use

The "adapter overhead" theory was wrong — the adapter adds
<100ms. The entire 3-5s was CPU inference time.

**Fix:** jseval `--llm` now sets `JUSTSEARCH_GPU_LAYERS=99`
by default. Users without GPU fall back to CPU (timeout →
case-only normalization, graceful degradation).

---

## Key source files

| File | Purpose |
|------|---------|
| `scripts/prod/justsearch-mcp/server.mjs` | MCP server — tool handlers, `slimSearchResult()`, response formatting |
| `scripts/prod/justsearch-mcp/schemas.mjs` | Zod schemas — tool input/output definitions |
| `modules/ui/.../KnowledgeSearchController.java` | Search endpoint — filter parsing |
| `modules/ui/.../RetrieveContextController.java` | Retrieve-context — filter parsing |

---

## Related

- **362**: Faceted metadata filtering — the filter infrastructure
- **363**: Transparent query enhancement — QU layer + soft boost
  (owns Phase 3 prerequisites + 2f null query sufficiency)

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Agent search interface design (2598 lines) building on 362 (faceted metadata filtering) + 363 (transparent query enhancement). ADR-0020 (structured metadata facets) + ADR-0016 (QU soft-boost) are the structural consumers.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

