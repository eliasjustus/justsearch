---
title: "597 — Search result-count truthfulness → the retrieval-funnel cardinality model. The headline 'N results' OVERLOADS one number for what are really distinct funnel populations (corpus → matched → candidate-UNION/window → returned page). Today it binds to the candidate-UNION (`HybridFusionUtils` `scores.size()`, bounded by the retrieval windows — CONFIRMED §1-R live: 167 ≪ 597-doc corpus), while facets project the true matched set — so they contradict (593 §8/§10: facets 414–419 beside '136 results'). The correct long-term structure is NOT 'pick which number to show' but to model funnel cardinality as a first-class slice of the canonical search-execution record (`SearchTrace`, 549/553 — which already carries per-stage `ms` but NO per-stage count), and make EVERY count — the headline AND the facets — a governed PROJECTION of the matched-population cardinality, so they cannot drift by construction (553 projection-vs-fork; the `execution-surface` gate). The user-facing LABEL is itself a projection of the trace's mode facts: 'N results' when a lexical predicate ran, 'top N ranked' for pure-dense where no match-set exists. Spans the FE↔backend search-response CONTRACT, NOT the 557 presentation-authority series (594/595/596)."
type: tempdocs
status: "R-1 IMPLEMENTED & MERGED to main (2026-06-18, merge `847ac64ba`) — the reopened residual is closed. The Chat-surface Search tab now projects the SAME funnel label as the dedicated Search surface via a shared `components/searchResults/matchCountLabel` helper (SearchSurface keeps a thin delegating static, 8 tests intact); the Chat retrieve tier reads `matchCount` off the shared `searchState` instead of the window `totalHits`. Render single-authority (602 R3): the two surfaces cannot report different counts for one query. Live-verified — query 'the' → BOTH surfaces read 'Top 50 of 605 matches' (was 424 vs 405). + 2 retrieve-tier unit tests; full FE suite green; the count/render extraction has no governing gate (confidence P4). (Note: post-merge `tsc` on `main` is broken by an environmental node_modules type-resolution issue, NOT this change — the worktree typechecked clean with fresh deps; see observations.) ── REOPENED (2026-06-17, 593 second-pass regression sweep, Still-Present #5): the matchCount fix is SURFACE-SPECIFIC — the dedicated Search surface got 'Top N of M matches' (verified), but the Chat-surface Search tab STILL shows window-as-count with facets exceeding it (same query → 424 Search vs 405 Chat). Residual scope in §REOPENED below. ── IMPLEMENTED + MERGED to main (0e740766a, 2026-06-17) — see §15 as-built. Built: matchCount = facet-scan matched total (M ≥ every facet by construction), headline 'Top N of M matches', per-stage funnel cardinality on SearchTrace rendered in the explain panel, matchCount pinned as a verified projection of the trace's matched node, §8.3 mode-aware 'Top N ranked'. Live-verified in the real UI. §8.2's per-stage (input,kept) PAIR deliberately NOT built (over-engineering; the funnel is legible via single per-stage cardinalities). Original design status follows. // open — discovery step (§1) COMPLETE and CONFIRMED across two verification tiers (source read + live stack, 2026-06-16); LONG-TERM DESIGN theorized in §8 (the retrieval-funnel cardinality model, homed in the canonical SearchTrace, 549/553). Option A in §4 is the minimal tactical projection of that design, not the design itself. FRONTEND/UX design in §11 (browser-inspected): the surface shows ≤50 rows with NO load-more, the facet chips' own copy says '(N results)'/'N matches across your library', and three populations diverge (shown 50 < union ~167 < matched 605) — so the headline must read as a FUNNEL ('Top N of M matches'), not a single swapped number, and the union number moves into the `<jf-search-trace>` explain panel. The root is confirmed: `totalHits` = the BOUNDED fused-candidate-UNION size (`HybridFusionUtils` `scores.size()` / `ccScores.size()`, capped by the retrieval windows), NOT the corpus match count; facets are a FULL match-set scan (`FacetingEngine`, up to 50k, chunk-excluded). The §1.1 hypothesised MECHANISM (a Lucene `totalHitsThreshold` plateau in the sparse path) was DISPROVEN — the real mechanism is the fusion union-of-windows, which even the 'sparse-shortcut' decision hits via chunk-branch fusion. A true match count is CHEAP (`IndexSearcher.count`, already used in `IndexCountOps`), which settles Option A vs B in favour of A. Not yet implemented (design + discovery only)."
created: 2026-06-16
author: agent
category: frontend / backend / search / contract / truthfulness / ux
related:
  - tempdoc 549 / 553 (canonical SearchTrace / search-execution record — the structured trace; the home for 'what the pipeline actually retrieved/counted'). This count-semantics question is adjacent: it asks what the response's `totalHits` MEANS relative to the trace.
  - tempdoc 577 (search-and-agent-window convergence — Goal 1 `buildSearchIntent`/`runSearch` single-issuer seam in `searchState.ts`; the FE search-request authority). The headline-count binding lives in this surface.
  - tempdoc 564 (contract projection — the FE↔backend wire boundary; KnowledgeSearchResponse is one surface). This is a contract-MEANING defect on that boundary, not a projection-drift one.
  - tempdoc 593 (UX walkthrough — §8 facet totals 414–419 beside '136 results'; §10 two different queries both '136 results', facets varied 414 vs 589 → window cap, not count). CLOSED.
  - tempdoc 594 / 595 / 596 (the three FE-only presentation-authority depth rounds on 557 — Display / Observed-state / Operability). THIS IS DELIBERATELY NOT a fourth sibling: it spans the search-response contract (backend `totalHits` semantics), a different lineage. Cross-referenced so a reader doesn't mis-file it.
  - tempdoc 504 (systematic UX audit taxonomy — D8 'count-mislabel' / cross-field-inconsistency adjacency).
  - tempdoc 570 (the search-window as a projection of the canonical authorities). THE HOME for 597's forward ideas (§16): the shipped matched-count + funnel cardinalities are a canonical signal 570's search-window projects, and 570's Part II Moves D/E/A already theorize the guidance/UX (did-you-mean recovery, faceted refine, search-as-mode) those ideas describe. 597 supplies the signal; 570 owns the surface — 597 does NOT grow a guidance framework. OPEN.
  - CLAUDE.md `audit-driven-fixes-need-test` (the backend `totalHits`-semantics claim is a hypothesis until a test/source read confirms it — §1 is mandatory before §4).
  - CLAUDE.md `interrogate-results` (the live numbers are data; the CAUSE — window vs match population — is what matters and must be established, not assumed).
---

# 597 — Search result-count truthfulness: the headline counts the window, not the matches

> **What this document is.** A defect analysis + design proposal for one search-surface truthfulness
> bug: the headline **"N results"** does not report how many documents matched the query — it reports a
> bounded retrieval-window size — and it sits beside facets that DO reflect the true match set, so the
> two contradict. This is **not** a 557 presentation-authority sibling (594/595/596): the root is partly
> a backend contract-MEANING question (what `totalHits` is), so this doc **leads with a discovery step**,
> not a design.

> **The meta-finding (why this doc exists, and why it's separate).** 594/595/596 were FE-only — a fact
> the FE couldn't show, a verdict it computed twice, a reason it couldn't deliver. This one is different
> in kind: the FE **faithfully renders** a backend number under a **misleading label**. The number
> (`totalHits`) and the facets measure **two different populations**, and presenting them side-by-side as
> if commensurable is the defect. The fix may be backend (what to count), FE (what to call it), or both —
> which is why it gets its own lineage tag (search-response contract), not the presentation-authority one.

---

## 1. Discovery step (MANDATORY — do this before §4)

Per `audit-driven-fixes-need-test`, the following are **hypotheses** grounded in the FE binding + backend
tests + the live 593 numbers, NOT yet confirmed end-to-end. Confirm each against source before designing:

1. **What is `totalHits` in the sparse-shortcut (BM25-only) path the 593 walkthrough was in?** Trace
   `ReadPathOps.java:394` (`new SearchResult(hits, topDocs.totalHits.value(), tookMs, nextCursor)`) and
   whether the searcher uses a `totalHitsThreshold` (which makes `topDocs.totalHits` a **lower bound**
   that plateaus at the threshold — the likely explanation for "two queries, both 136"). Confirm whether
   136 is a threshold/candidate-pool constant or a coincidence.
2. **What is `totalHits` in the fused/hybrid path?** `HybridFusionUtilsTest.java:143–144` asserts
   *"totalHits reflects union size, not limit"* and `KnowledgeHttpApiAdapterPerSourceTest:235`
   (`totalHitsSummedAcrossResponses`) sums it across per-source responses. Confirm `totalHits` = size of
   the fused candidate union (retrieval depth), not the corpus match count.
3. **What population do the facets count?** Confirm the worker facet collector
   (`FacetRegressionMatrixTest` neighborhood) counts the **full matching set**, not the returned window —
   i.e. facets and `totalHits` count different populations. (The live numbers already imply this: facets
   414 > totalHits 136.)

If §1 disproves the hypothesis (e.g. `totalHits` IS an exact match count and 136 was a fluke), this doc
collapses to a much smaller facet-population question. Do not skip §1.

---

## 1-R. Discovery RESOLUTION (2026-06-16 — source + live, CONFIRMED)

§1 is done. All three hypotheses are confirmed, with one important **mechanism correction** to §1.1 and
one **sub-hypothesis refuted** (chunk-inflated facets). Verified across two tiers per
`use-every-verification-tier`: source read of the worker search path, and a live read-only query against
the running dev stack (corpus = **597 documents**, `embeddingCoverage=100%`, `chunkEmbeddingReady=true`).

### 1-R.1 — `totalHits` is the bounded fusion-union, NOT the match count (§1.1 + §1.2 CONFIRMED; mechanism corrected)

The root is **proven in source**, and the mechanism is **not** the one §1.1 guessed:

- **The actual mechanism** is `HybridFusionUtils.fuseWithRRF` (`HybridFusionUtils.java:237`:
  `new SearchResult(fusedHits, scores.size(), 0)`) and the CC variants (`:326/:496/:700`:
  `ccScores.size()`). `scores`/`ccScores` is the **union map of every distinct docId across the input
  hit-lists**; `fusedHits` is separately trimmed to `limit` (`:201` `.limit(limit)`). So
  `totalHits = |union of the input hit-lists|` — and those lists are bounded by the **retrieval windows**
  (`SearchPlanner.computeRetrievalLimit:198–202` → `limit*2` when chunks present; chunk-branch
  `candidateBudget = limit*10`), **never** by the corpus match count. This is exactly what
  `HybridFusionUtilsTest:143–144` ("union size, not limit") asserts at unit scale.
- **Even the "sparse-shortcut" path produces a union number.** `SparseShortcut` still calls
  `maybeApplyChunkMerge` (`SearchExecutor.java:237`), whose `mergeChunkResults` fuses the whole-doc
  branch with the chunk-parent branch via `fuseWithRRFNamed`/`fuseWithCCNamed`
  (`SearchExecutor.java:711–746`) → again `scores.size()`. So the 593 "sparse-shortcut" trace label does
  **not** mean `totalHits` is a raw Lucene count; the chunk-branch fusion still windows it.
- **§1.1's `totalHitsThreshold` plateau theory is DISPROVEN.** Lucene's `searcher.search(query, n, sort,
  true)` (`ReadPathOps.java:330`) yields `topDocs.totalHits.value()` (`:394`) that is exact up to a 1000
  threshold — a *true* count for sub-1000 match sets. That raw count only survives when **chunk-merge is
  SKIPPED** (`trimSearchResult`, `SearchExecutor.java:975–976`, preserves `result.totalHits()`). When
  chunk-merge applies (the default for a chunk-bearing corpus), the fusion-union clobbers it. So the
  defect is **conditional on chunk-merge running**, and the "two queries both 136" is **retrieval-window
  saturation of the fusion union**, not a fixed Lucene threshold constant.

**Live proof (the decisive datum).** Corpus = **597 docs**. A 10-stopword, near-match-all query
(`"the and to of a in is for with that"`) returns `totalHits = 167` — i.e. a query that matches almost
the whole corpus reports **167, far below 597**. `totalHits` is structurally capped below the true match
count. Two other queries: `"knowledge server body"` → **397**, `"configuration environment variable"` →
**126**. The number varies per query (it is the per-query fusion-union size) and is **neither** the
corpus size **nor** constant — consistent with "bounded union of retrieval windows," and inconsistent
with both "true match count" and "fixed cap." (593's *constant* 136 was that session's corpus saturating
both legs; this session's corpus saturates at different per-query unions.)

### 1-R.2 — Facets are a FULL match-set scan over the chunk-EXCLUDED source-doc population (§1.3 CONFIRMED; sub-hypothesis refuted)

- `FacetingEngine.computeFacets` (`FacetingEngine.java:69–203`) iterates **every document matching the
  query** via `weight.scorer(leaf)`, up to `maxDocsScanned` (**default 50,000**, `:40/:75`), tallying
  DocValues per matching doc (`:132–168`). So a facet value's count = number of **matching documents**
  with that value — the true match population, not the returned window. Facets > `totalHits` is therefore
  **structural**, not a bug in either number.
- **Refuted sub-hypothesis: "facets over-count by including chunk docs."** The 593 datum `en-US 598 >
  597 corpus` *looked* like chunk inflation. It is not: the retrieval/facet query is built by
  `TextQueryOps.buildTextQuery` → `QueryFilterBuilder.applyRuntimeFilters` (`QueryFilterBuilder.java:138–151`),
  which **excludes chunks by default** (`IS_CHUNK` `MUST_NOT`). So facets count chunk-EXCLUDED source
  docs. The `598 > 597` was simply a **larger corpus in that earlier session** (corpus size is
  per-session; this session's is 597). Checked rather than assumed, per `interrogate-results` — and it
  *simplifies* the fix: both numbers live on the same chunk-excluded source-doc population, differing only
  by full-scan (facets) vs windowed-union (`totalHits`).

### 1-R.3 — The §1.1 cost question, answered: a true match count is CHEAP

§1.1 asked whether an exact match total needs an expensive extra pass. **It does not.**
`IndexSearcher.count(query)` is an exact, score-free, O(matches) count and is **already a first-class
primitive in this codebase** — `IndexCountOps.java:71/88/97/139/277` use `searcher.count(...)` throughout.
Two even-cheaper sources already exist on the hot path:

1. **The facet scan already computes it.** `FacetingEngine`'s `scanned` counter (`:96/:136`) is precisely
   the matched-doc total for the query (chunk-excluded), capped at 50k with a `truncated` flag. When
   facets are requested (the refined search pass always requests them, `searchState.ts:317`), the true
   total is **already being produced and thrown away**.
2. **The pre-fusion whole-doc leg already has the Lucene count.** `wholeDocResult.totalHits()` (the raw
   `ReadPathOps` count, exact ≤1000) is in hand inside `mergeChunkResults` before the fusion-union
   overwrites it — though this excludes chunk-only-matching parents, so it under-counts vs option (1).

This settles **Option A vs B in favour of A**: the truthful number is cheap, often free.

### 1-R.4 — Provenance of the live numbers (per `interrogate-results`)

The live query ran against another session's shared dev stack (read-only; no takeover, no mutation). The
597-doc figure is `/api/knowledge/status.indexedDocuments`. The `totalHits` values are the worker's
fused-union numbers surfaced verbatim. The *cause* (bounded fusion union) is established from source, not
inferred from the numbers — the numbers only **corroborate** that `totalHits` ≠ match count (167 ≪ 597).

---

## 2. The defect (what the user sees, traced to the binding)

### 2.1 The headline binding (PROVABLE in code)

`SearchSurface.ts:1339–1340` renders the headline from `totalHits`:

```ts
this.s.totalHits > 0
  ? html`${this.s.totalHits.toLocaleString()} results …`
  : html`<span style="opacity:0.6">0 results</span>`
```

`searchState.ts:507` populates it from the wire: `totalHits: data.totalHits ?? hits.length`. The FE
request hardcodes `limit: 50` (`searchState.ts:311`), so the displayed 136 is **not** the returned-hit
count (≤50) and **not** an FE display cap — it is the backend's `data.totalHits` field, shown verbatim.
Facets are a **separate** wire field (`data.facets`, `searchState.ts:518`), rendered independently
(`SearchSurface.ts:1111` → `renderFacetChips`).

### 2.2 The live contradiction (593 §8/§10)

- Query "knowledge server body": **"136 results"**, facets Type(markdown **414**), Language(en-US **419**).
  → facets **exceed** the headline by ~3×.
- A second, different query: **"136 results"** again, facets markdown **589**, en-US **598**.
  → headline **constant** while facets **varied** → 136 is a **window/cap**, not a per-query count.

Two numbers, two populations, presented as one quantity. The headline under-reports the true match total
and visibly contradicts the facets on the same screen.

---

## 3. Root, stated once

> The search response exposes **two count populations** — a **bounded retrieval-window** `totalHits`
> (fused candidate union / Lucene window, possibly threshold-capped) and **corpus-match facet counts** —
> with **no shared definition of "how many results."** The FE binds its headline **"N results"** to the
> window number and renders the facet (true-match) numbers beside it. So "N results" means "size of the
> ranked window," but reads as "documents that matched" — and when facets exceed it, the UI contradicts
> itself.

Neither number is internally wrong; the defect is **labeling a window as a match count** and **placing
two non-commensurable populations side by side** with no reconciliation.

> **The structural root, named once (see §8).** The deeper defect is not "wrong label" — it is that a
> retrieval pipeline produces a **funnel of distinct populations** (corpus → matched → candidate-union /
> window → returned page → facet-population) and the contract exposes **one overloaded number**
> (`totalHits`) that silently means a *different* funnel stage depending on whether chunk-merge/fusion
> ran. Two consumers (headline, facets) then read two different populations as if commensurable. This is
> the **representation-drift defect class** that 553 governs for search-execution — applied to
> *cardinalities*. The correct long-term fix is therefore not "pick a number," but to give each funnel
> population a **single canonical home** and make every count a **governed projection** of it (§8).

---

## 4. Design options (the tactical ladder — subordinate to §8)

> **Read §8 first.** These three options answer the *narrow* question "what should the headline show?".
> They are the **tactical projections** of the long-term design in §8 (the funnel-cardinality model).
> Option A is the correct minimal projection (bind the headline to the *matched* population); §8 is the
> structure that makes A correct-by-construction and kills the defect *class*, not just this instance.
> Keep §4 for the incremental shipping path; treat §8 as the target structure.

The honest question is **"what should 'N results' mean?"** Three coherent answers — they are not mutually
exclusive, but pick the primary:

### Option A — report the TRUE match total as "results" (rung-1, Collapse to one population) — RECOMMENDED

Compute the count of documents matching the query, and bind the headline to **that**. Rename the window
number to what it is — **"ranked candidates"** / "scored N" — shown in the pipeline detail, not the
headline. Now "N results" and the facets count the same population and can't contradict. **Cost
(resolved in §1-R.3): cheap, often free.** `IndexSearcher.count(query)` is an exact, score-free primitive
already used across `IndexCountOps`; and the facet scan's `scanned` counter *already* produces this exact
total whenever facets are requested. The §1.1 worry that `totalHitsThreshold` makes a true count
expensive does not apply — `count()` is O(matches) without scoring/storage, and 50k-cap truncation only
bites on corpora far larger than typical personal indexes (label "50,000+" if `truncated`).

**Two concrete shapes for A (prefer A′):**

- **A′ (free when facets are on) — bind the headline to the facet scan's matched-doc total.** Surface
  `FacetingEngine`'s `scanned` total (add a `matchedDocs` + `truncated` to `FacetsResult`) and bind "N
  results" to it. Because the headline and every facet then come from **the same scan over the same
  chunk-excluded query**, `facet-value-count ≤ N results` becomes an **invariant by construction** — the
  contradiction is structurally unrepresentable, not merely patched. For the quick (facet-less) pass,
  fall back to a `searcher.count(query)` or keep the union number under an honest label.
- **A (always-on count) — one `searcher.count(retrievalQuery)` pass.** Slightly more work than A′ on the
  facet pass (a second scan) but uniform across facet/non-facet requests.

**Contract shape.** Prefer **adding** a new response field (e.g. `matchCount`) and binding the FE headline
to it, rather than silently re-defining `totalHits`' meaning — other consumers (trace, tests) read
`totalHits` as the candidate-union today, and `totalHits` is **not** load-bearing for pagination (the FE
paginates on `nextCursor`, not `totalHits`; `searchState.ts`). Adding a field is the api-record +
schema-regen workflow (§6.4); the relabel of the union number to "ranked candidates" is FE-only.

### Option B — relabel the headline to name the window honestly (rung-0→honest, cheapest)

Keep `totalHits` as-is but stop calling it "results." "**Top 136 ranked**" / "showing best 136" / "136
ranked candidates." The number stops claiming to be a match count; the facets stop contradicting it
(they're explicitly a different, broader lens). Cheapest, FE-only, no backend change — but leaves the
user without a true match total at all.

### Option C — reconcile the facets to the headline's population (rung-1, the other direction)

Make facets count only over the returned/ranked window, so facet sums ≤ headline. **Rejected on its
face:** this throws away the genuinely useful corpus-wide facet signal (the whole point of facets is to
show the shape of the FULL match set for refinement). Listed for completeness; A or B is the real choice.

### Recommendation

**Settled by §1-R: Option A, in its A′ shape.** §1.1's cost question is answered — a true match count is
cheap and, when facets are requested, already computed (`FacetingEngine.scanned`). So the
cost-contingency that would have justified the B-now / A-later staging does not exist. Bind "N results" to
the facet scan's matched-doc total (A′) so headline and facets are the same scan and **cannot** contradict
by construction; add a `searcher.count` fallback for the facet-less quick pass; relabel the existing union
number as "ranked candidates" wherever a UI still shows it. Option B (relabel-only) remains a valid
*cheaper-still* fallback if the contract change is deferred, but it leaves the user without a true total
and is strictly weaker than A′, which is nearly as cheap.

---

## 5. Scope boundary (what this is NOT)

**IN (same root — the result-count's meaning + its consistency with facets):** the "N results" ↔ facets
contradiction, the constant-136 window-as-count mislabel, and the missing shared definition of "result."

**OUT (different roots — do NOT bundle):**
- **594 / 595 / 596** — the FE-only presentation-authority gaps (chip fact / health verdict / affordance
  reason). This is the search-response contract; cross-referenced only so it isn't mis-filed as a fourth
  sibling.
- **"sparse-shortcut" trace interpretability** (593 §8a/§G) — `Dense (vector) skipped (sparse-shortcut)`
  reads like an optimization but means `wantDense=false`; that's search **routing/trace** legibility, a
  separate trace (549/553 territory).
- **The 50-vs-136 pagination question** — the FE requests `limit: 50` but shows 136 as the total; whether
  the result LIST should paginate to the full window is a list-UX question, not the count-meaning one.
  Adjacent; its own item if pursued.
- **Force-Rebuild GPU catch-22 / mode-exclusivity** (593 §F/§H) — backend runtime.

---

## 6. Open questions / decisions (before impl)

1. **§1 outcome gates everything — RESOLVED (§1-R).** Confirmed: `totalHits` = bounded fusion-union (not
   match count); facet population = full chunk-excluded match set. No re-scope needed; the mechanism is
   fusion union-of-windows (not the hypothesised Lucene threshold plateau), and the defect is conditional
   on chunk-merge running.
2. **Option A vs B — RESOLVED (§1-R.3 / §4): Option A (A′ shape).** A true count is cheap/free; the
   B-now-A-later staging is moot. C still rejected.
3. **Implementation seam (new, post-discovery).** A′ touches `FacetsResult` (add `matchedDocs` +
   `truncated`), `FacetingEngine` (return its `scanned` total), `SearchResponseBuilder` (carry it onto the
   response), the worker→head `SearchResponse`/`KnowledgeSearchResponse` wire (new `matchCount` field),
   and the FE headline binding (`SearchSurface.ts` + `searchState.ts:507`). The facet-less quick pass
   needs a `searcher.count` fallback (or honest relabel). The structural target (§8) is that this
   `matchCount` is a **projection of the MATCHED funnel node on `SearchTrace`**, not a free-standing
   field. Per `audit-driven-fixes-need-test`, ship a worker test pinning the invariant the gate can't
   (§8.6): `matchCount ≥ Σ(facet value counts)` for a predicate query, AND the mode-aware label
   (lexical-leg → "N results"; pure-dense → "top N ranked", §8.3).
4. **Home/lineage — RESOLVED by §8.** This is search-response-contract truthfulness (549/553/577/564),
   NOT a 557 presentation-authority sibling. The canonical home for the count is the **funnel-cardinality
   on `SearchTrace`** (§8.2): the MATCHED node is the one source; the response's user-facing count field
   and the facet denominator are **projections** of it (553 projection-vs-fork), governed by the existing
   `execution-surface` register/gate. (Earlier draft said "response field is the primary home, trace
   projects from it" — §8 inverts that: the *trace* is the source, the response field the projection, so
   the count joins the record that already owns "what the pipeline did.")
5. **Doc maintenance.** If Option A changes the wire meaning of `totalHits` or adds a field, that's an
   api-record + schema-regen workflow (KnowledgeSearchResponse → `updateSchemas` → gen-wire-schema-types)
   and a `docs/reference/api-contract-map.md` touch. If Option B (FE label only), no wire/doc change.

---

## 8. The correct long-term design — the retrieval-funnel cardinality model

> **What this section is.** The *correct long-term structure*, kept general (no code, no phasing). It
> reframes 597 from "a count-label bug" to "a missing slice of the canonical search-execution record." It
> extends an existing design (`SearchTrace`, 549/553) rather than greenfielding, and it eliminates the
> defect **class** (count overloading), not just the `136`-vs-`414` instance. Major refactor is on the
> table; the framing follows 549/553's own "theorize the structure, defer the steps" posture.

### 8.1 The thesis: counts are a funnel, and the funnel is execution-record territory

A retrieval pipeline does not have *a* count. It has a **monotone funnel of populations**, each a distinct
cardinality:

```
corpus(N≈597)  ─predicate→  MATCHED  ─window/retrieve→  CANDIDATE-UNION  ─fuse/rerank→  RANKED  ─paginate→  RETURNED(≤limit)
                              │                              │                                                  │
                       "N results"                  today's totalHits                                    results.length
                    (what the user means)         (HybridFusionUtils.scores.size())                     (the page shown)
                              │
                          FACETS project this same MATCHED population (full scan, chunk-excluded)
```

The bug is that the contract exposes **one** number (`totalHits`) that lands on the **CANDIDATE-UNION**
node, the FE labels it as if it were the **MATCHED** node, and the facets correctly project **MATCHED** —
so headline and facets read two different funnel stages. **The funnel stages are exactly "what the
pipeline did/counted,"** which 549/553 already established as the property of the **canonical
search-execution record** (`SearchTrace`). So the counts are not a new concept needing a new home — they
are a **missing field on an existing canonical record**. Today every `TraceStage` carries
`status / reason / ms / detail` but **no cardinality** (`SearchTrace.java` `TraceStage`; proto
`indexing.proto` `message TraceStage`). The trace records *how long* each stage took and *whether* it ran,
but not *how many documents flowed through it*. That omission is the structural gap 597 exposes.

### 8.2 The structure: one canonical funnel cardinality, every count a governed projection

Two moves, both *extensions* of shipped design:

1. **Add per-stage cardinality to the canonical record.** Give `TraceStage` a cardinality companion to
   its existing `ms` (general shape: an *input* count and a *kept/output* count per stage — the only
   honest way to represent a funnel is the pair, since "how many entered" ≠ "how many survived"). This is
   the **single source** for every population number. It is the *same additive pattern* as per-stage `ms`
   (549's per-stage timing), governed by the **same** `execution-surface` register + gate that already
   fails the build on an unregistered referencer of `SearchTrace` (553 §0/§3). No new authority — the
   count joins the record that already owns "what the pipeline did."

2. **Make every surfaced count a projection of a named funnel node — never an independent number.** The
   user-facing headline, the facets' implied denominator, the observability spans, eval's "depth"
   metrics, and any AI narration of "how many results" all read **the same** matched-population
   cardinality from the trace. This is 553's projection-vs-fork discipline applied to counts: with one
   source, **the headline cannot exceed-or-undershoot the facets by construction** — the contradiction
   becomes *unrepresentable*, not merely corrected. `totalHits`-as-candidate-union stops being a
   top-level headline and becomes what it always was: the **FUSION/CHUNK_MERGE stage's output
   cardinality**, surfaced in the pipeline-detail/trace as "ranked candidates."

### 8.3 The label is itself a projection of the trace's mode facts

The truthful *word* depends on whether a **lexical predicate** stage ran — and the trace already knows
(`effectiveMode`, per-stage `status`):

- **A lexical/predicate leg executed** (sparse / SPLADE, the common case): MATCHED is well-defined =
  |documents satisfying the query predicate|. Headline = that. Label = **"N results."**
- **Pure dense / ANN, no predicate** (vector-only): there is **no match-set** — every document is a
  "match" at some distance; the only honest cardinality is the *window* (top-K by similarity). Headline =
  the RANKED/RETURNED window. Label = **"top N ranked,"** never "N matched."

So even the *label* is a projection of trace facts, not a hard-coded string. This dissolves a latent
second bug the §4 options would have papered over: "N results" is a *category error* for vector-only
search, and only a mode-aware projection states it correctly.

### 8.4 Why MATCHED is the right user-facing node (and why it is cheap)

- **It is what "results" means.** A user reading "N results" means "N documents matched my search," i.e.
  the predicate population — not "N documents the ranker chose to score" (an internal capacity).
- **Facets already live here.** `FacetingEngine` scans the full chunk-excluded predicate set (§1-R.2), so
  binding the headline to MATCHED makes headline and facets *the same scan over the same population* →
  `Σ(facet value) ≤ headline` is an invariant, not a coincidence.
- **It is cheap (§1-R.3).** `IndexSearcher.count(predicateQuery)` is exact + score-free and already a
  first-class primitive (`IndexCountOps`); the facet scan already computes the matched total whenever
  facets are requested. The MATCHED node is therefore *free on the facet path* and one cheap count
  elsewhere. The trace's per-stage cardinality is populated from numbers the pipeline already holds (each
  leg's match count; the fused union size; the page size) — recording, not recomputing.

### 8.5 Scope of the unification (AHA discipline — what stays separate)

Per 553 §6 and CLAUDE.md `explore-before-implementing` (projection vs fork), unify only what shares a
reason to change:

- **IN the trace (one source):** every funnel cardinality (matched / candidate-union / ranked / returned,
  per leg + merged). These change *together* when a pipeline stage changes — they are one concept.
- **A projection, not a copy:** the response's user-facing count field and the facets' denominator. They
  *derive* from the trace's MATCHED node.
- **Stays a sibling, not merged:** the facet *value breakdown* (per-value counts) is its own structure
  (it answers "shape of the match set," not "how big"); it merely shares the MATCHED *denominator*. The
  559 `ContextCitation` RAG-evidence record stays a sibling too (553 §6). Do **not** fold facets into the
  trace — only their denominator is shared.

### 8.6 What this buys, and the honest limit

- **Eliminates the class.** Any *future* count surface (a "scanned N of M" progress line, an eval depth
  metric, an agent's "I found N docs") reads the funnel from one place; a new pipeline stage adds one
  cardinality pair to the trace and every consumer stays consistent. The next overloaded-count bug
  becomes unrepresentable rather than re-litigated.
- **Governed.** The count is a registered `SearchTrace` projection; the `execution-surface` gate already
  fails the build on an unregistered fork (553) — so "someone re-derives a count locally" is caught
  mechanically, the same way the trace itself is.
- **Honest limit (per 553 §5).** A register/gate guarantees *no unregistered fork*, not *semantic
  correctness of the projection*. Binding the headline to the wrong trace node (e.g. RANKED instead of
  MATCHED) is still a human judgment the gate can't catch — which is exactly why §6.3's regression test
  (`headline ≥ Σ facets`, mode-aware label) is mandatory: the gate prevents drift, the test pins meaning.

### 8.7 Relationship to the §4 ladder

§4 Option A′ (bind the headline to the facet scan's matched total) **is** the first projection of this
design — it is correct *because* it reads the MATCHED node. The difference is scope: §4-A′ ships the one
projection that fixes the visible contradiction; §8 says the MATCHED node should be a **named cardinality
on the canonical record** so that A′, the trace's "ranked candidates" detail, the spans, and eval all
project from one source. Ship A′ as the increment; land the funnel-cardinality field as the structural
completion. Option B (relabel-only) is explicitly **rejected as a terminal state** here — it leaves the
funnel un-modelled and the user without a true total; it is acceptable only as a same-day stopgap if the
trace extension is staged behind it.

---

## 9. As-built so far

- **Discovery COMPLETE (§1-R) + long-term design theorized (§8); nothing implemented yet.** The §1 discovery step is done and confirmed
  across source + live tiers (2026-06-16). The design recommendation is settled (Option A, A′ shape). No
  production code touched by this doc.
- **Confirmed in source:** the FE binding (`searchState.ts:311` `limit:50`, `:317` facet fields, `:507`
  `totalHits ?? hits.length`, `:518` facets); `totalHits` = fusion-union size (`HybridFusionUtils.java:237`
  `scores.size()`, `:326/:496/:700` `ccScores.size()`; trimmed hits at `:201`); the raw Lucene count
  origin (`ReadPathOps.java:330/394`) preserved only when chunk-merge is skipped
  (`SearchExecutor.java:975–976`), overwritten by chunk-branch fusion otherwise (`:237/:711–746`); facets
  full-scan up to 50k over chunk-excluded source docs (`FacetingEngine.java:69–203`,
  `QueryFilterBuilder.java:138–151`); a cheap exact count primitive already exists
  (`IndexCountOps.java:71` `searcher.count`).
- **Confirmed live (read-only, shared dev stack, corpus 597 docs):** a near-match-all 10-stopword query
  returns `totalHits=167 ≪ 597`; `"knowledge server body"`→397, `"configuration environment variable"`→126
  (per-query union, neither corpus-size nor constant). This corroborates `totalHits ≠ match count`.
- **Corrections vs the original hypotheses:** (a) the §1.1 Lucene-`totalHitsThreshold`-plateau mechanism
  was **disproven** — the real mechanism is the fusion union-of-windows; (b) the "facets count chunk docs"
  sub-hypothesis was **refuted** (chunks are excluded by default), so `598 > 597` in 593 was a
  larger-then corpus, not inflation; (c) the §1.1 "true count may be expensive" worry was **resolved** —
  it is cheap/already-computed, which is what flips the recommendation firmly to Option A.

## 10. Pre-implementation de-risk pass (2026-06-16, read-only — confidence 7/10)

A read-only confidence pass (3 Explore audits + live dev queries; no feature code) walked the
implementation surface before committing. Findings that change *how* §8/§4 should be sequenced:

1. **The §8 funnel data is NOT available to the trace projector today (the main cost).** `SearchOutcome`
   retains **only the final fused `result`**; each leg's match count, the fused-union size, and the
   chunk-branch sizes are computed as locals in `SearchExecutor` (`runMultiLeg` switch / `runThreeWay` /
   `fuseLegs` / `executeChunkBranchFusion`) and **discarded** — only ns-timings + an `anyLegSaturated`
   boolean survive. So §8.2's per-stage cardinality needs new fields **plumbed through `SearchOutcome`**
   and captured in each executor case. Moderate, well-scoped, **not free**. The §4 A′ headline count does
   **not** need this plumbing — confirming the increment/structure split is real, not cosmetic.
2. **`totalHits` is pagination-safe to leave as the union.** FE + agent pagination is **100%
   `nextCursor`-driven**; nothing uses `totalHits` for page math. Its only consumers are display (FE
   headline, pinned-search chip), telemetry (`metrics.recordSearch`, `SearchResponseBuilder.java:139`),
   and a diagnostic (`RagContextOps`). Two tests pin union semantics (`HybridFusionUtilsTest.java:143`;
   `KnowledgeHttpApiAdapterPerSourceTest` `totalHitsSummedAcrossResponses`). → **Add a new `matchCount`,
   keep `totalHits`=union** (relabelled "ranked candidates"); do not repoint. *New caveat:* per-source
   merge **sums** `totalHits`, so `matchCount` needs its own cross-source merge rule.
3. **Wire-regen + gates fully inventoried, no blocker.** Mirrored protos (`ipc-common/indexing.proto` +
   `contracts/wire/knowledge.proto`) + `@RecordBuilder` app-api record + `updateSchemas` +
   `gen-wire-schema-types.mjs`; gates `KnowledgeWireContractConformanceTest`, `contract-projection`,
   `wire-type-single-authority` always; `execution-surface` **only if** the field derives from
   `SearchTrace`; `stage-completeness` **only if** a new *stage* (N/A for a field).
4. **The one decision to make before coding — projection vs independent count.** `matchCount` as a
   **projection of the trace MATCHED node** (structurally pure per 553, `execution-surface`-gated, but
   needs finding #1's plumbing first) vs an **independent `IndexCountOps.count`** in
   `SearchResponseBuilder` (simpler, but a second source / mini-fork until §8 unifies). The non-forky
   answer wants the trace node → do **Phase 1 (trace MATCHED node) before Phase 2 (headline projection)**.
5. **Label edge case is mostly theoretical** — the UI always sends text, so a BM25 matched denominator
   always exists; predicate-less ("top N ranked") is API-only.
6. **Residual:** facet-sum > headline not reproduced *live in this corpus* (MCP search omits facets;
   `/api/knowledge/search` not allowlisted for `api_call`). Source-proven + `167 ≪ 597` corroborates;
   closeable trivially at impl time or via the dev UI.

**Sequencing (de-risked):** Phase 1 — thread the funnel cardinalities (already-computed-then-discarded)
into `SearchOutcome` and project a per-stage count onto `TraceStage` (companion to `ms`). Phase 2 — add
`matchCount` to `KnowledgeSearchResponse` as a projection of the trace MATCHED node, bind the FE headline,
relabel the union number. Mandatory test: `matchCount ≥ Σ(facet value counts)` + the mode-aware label.
**Confidence: 7/10** (high on direction + contract mechanics; moderate drag from the executor→outcome
plumbing surface and the projection-vs-count decision). Full plan: session plan file
`sprightly-dancing-gadget.md`.

## 11. Frontend / user-facing design (browser-inspected 2026-06-16 — the funnel must be *legible*, not just *correct*)

> **Why this section exists.** This bug is user-facing by definition — the headline number IS the
> product surface. I inspected the live search UI (dev stack, corpus now **605 docs**) rather than reason
> from the tempdoc. The inspection found user-visible consequences the backend-only framing (§4/§8)
> **missed**, and they change the FE design from "swap the number" to "make the retrieval funnel legible."

### 11.1 The user-visible count surfaces (verified in code + live)

| Surface | Where | Today shows | Population |
|---|---|---|---|
| **Headline meta line** | `SearchSurface.ts:1339–1349` | `"{totalHits} results · {latency} · quick results/refining…"` | the **union** (wrong) |
| **Facet chips** | `facetChips.ts:54–63` | `{value} {count}`, aria **"(N results)"**, title **"N matches across your library"** | the **full matched** set |
| **Pinned-search chip** | `SearchSurface.ts:1211–1213` | `"N runs · last {totalHits} hits · {time}"` | the **union**, labelled "hits" |
| **Explain panel** | `<jf-search-trace>` (549/577), two altitudes: `.search-explain-user` + Advanced-gated `.search-explain-diagnostics` | per-stage status/timing, no counts | — (the natural home for the funnel) |
| **Two-pass labels** | `SearchSurface.ts:1343–1349` | `"refining…"` / `"quick results"` | count shown on both passes |
| **Degraded banner** | observed live | "Semantic search degraded. Showing keyword results…" | — (the sparse path still fuses → union) |

### 11.2 What inspecting the UI revealed that the tempdoc-alone view missed

1. **There is NO "load more."** Neither `searchState.ts` nor `SearchSurface.ts` consumes `nextCursor` /
   `hasMore` — the surface renders a **single page of ≤50 rows**. So the headline number is disconnected
   from what's shown in *both* directions: the user sees ~50 rows under a headline claiming ~167, with
   **no affordance to reach the rest**. (Backend corroborant: the fused/chunk-merge result is built via
   `HybridFusionUtils` `new SearchResult(fusedHits, scores.size(), 0)` — the 3-arg ctor sets
   `nextCursor=null`, so the common path is *un-pageable by construction*; the union is not a browsable
   quantity even in principle.)
2. **Three numbers diverge, not two.** `shown (≤50)  <  union / today's headline (~167)  <  matched /
   facets (605)`. The §4/§8 plan ("bind the headline to the matched count") would replace one over-promise
   (167 with 50 shown) with a *bigger* one (605 with 50 shown) — a bare **"605 results"** beside 50
   un-expandable rows is a NEW lie, not a fix. The honest headline must name **both** the shown slice and
   the matched total.
3. **The contradiction is in the COPY, not just the math.** Facet chips literally say **"(N results)"**
   (aria) and **"N matches across your library"** (title) — the *same words* the headline uses, for the
   broader population. A screen-reader user hears "167 results," then "Filter by Type: markdown, 414
   results" — a flat contradiction spoken aloud. Any fix that only changes the backend number leaves this
   copy collision intact.
4. **The degraded state is the common state.** Live, the surface sat in "Semantic search degraded —
   showing keyword results" (LambdaMART not configured). The truthful funnel copy must hold **without**
   dense/rerank — which it does, since the matched total is a *lexical* count (`searcher.count`),
   available whenever a text predicate ran.

### 11.3 The correct frontend design — make the funnel legible (not a single swapped number)

The user-facing model must surface the retrieval funnel honestly, with **one count vocabulary** shared by
every surface:

- **Headline = the funnel, not a number.** `"Top {shown} of {matched} matches"` (e.g. *"Top 50 of 605
  matches"*), where `matched` is the §8 MATCHED node and `shown` is the rendered page. This is truthful in
  every direction: it never implies browsability the surface lacks, and `matched ≥ every facet count` by
  construction (§8), killing the contradiction. When `shown == matched` (small result sets) collapse to
  `"{matched} matches"`.
- **The union / "ranked candidates" number leaves the headline entirely.** It is a pipeline-internal
  quantity the user cannot act on (un-pageable, ≠ matched); it belongs ONLY in the existing
  `<jf-search-trace>` explain panel — ideally as the middle funnel rung (*"matched 605 → ranked 167 →
  shown 50"*), in the diagnostics altitude. No new surface; reuse the 549/577 panel.
- **Facets are the matched→shown BRIDGE, and must use the matched vocabulary.** They describe the full
  matched set (the *superset* signal — that's their value), and a chip click narrows the query so more of
  that slice enters the shown top-N. Re-word the chip aria/title off **"results"** onto **"matches"** /
  **"N of {matched}"** so the chip count reads as a *subset of the headline's matched total*, not a rival
  "results" number. (`renderFacetChips` is the ONE shared chip primitive — fix it once, both the
  standalone surface and the in-window retrieve tier inherit it.)
- **One vocabulary, enforced across surfaces:** *matches* = matched total · *showing/top N* = the rendered
  slice · *ranked candidates* = internal, explain-panel only. Retire the pinned-chip "hits" wording onto
  "matches" too (it currently shows the union — re-point to the matched total like the headline).
- **Mode-aware label (projection of the trace, §8.3).** Predicate present (the UI default — it always
  sends text) → *"Top N of M matches."* Pure-dense / no predicate (API-only) → *"Top N ranked"* with no
  matched total, because no match-set exists.
- **Two-pass behaviour preserved.** The quick (facet-less) pass shows a *provisional* matched total from
  the cheap `searcher.count` fallback; the refined pass confirms it and adds the facet bridge. Keep the
  "quick results / refining…" affordance — it already sets the expectation that the number firms up.

### 11.4 A11y + copy consequences (the spoken contradiction)

The screen-reader experience is currently self-contradictory (§11.2.3). The fix is the same single
vocabulary: the headline announces the funnel (*"Showing top 50 of 605 matches"*), and each facet chip
announces a **subset** (*"markdown, 414 of 605 matches — activate to filter"*). Once both numbers are the
matched population and the words don't collide, the aural and visual readings agree. This is in-scope for
this tempdoc (it is the *same* truthfulness defect at the copy/ARIA layer), not a separate a11y item.

### 11.5 Relationship to §8 / §4

§11 **refines** §8, it does not replace it: §8's MATCHED node is still the one canonical source, and the
headline still projects from it. The FE refinement is that the projection is a **funnel phrase ("top N of
M")**, not a bare M — because the surface shows only N and offers no load-more — and that the union number
is relocated to the explain panel rather than merely relabelled in place. The §4 ladder's "relabel the
union as ranked candidates" lands specifically as the explain-panel funnel rung, not as headline text.
**Open product question for the user:** should the no-load-more limitation be *fixed* (page through to the
matched set) or *embraced* (the funnel phrase + facet-refinement as the intended interaction)? §11 assumes
the latter (cheaper, and matches the un-pageable fused path); pagination-to-matched is a larger,
separate UX track.

## 12. Implementation sizing (calibrated against repo git history, 2026-06-16)

Sized against comparable shipped tempdocs (commit-span analysis: 572/588/581 = single-session same-day;
585/580 = medium ~1.5–2 days / 25–40 bursty commits; 564/559/577/549 = large 4–11 days):

- **Tactical A′ only** (headline truthfulness: independent `searcher.count` in `SearchResponseBuilder` +
  bind FE headline + fix the facet "results"→"matches" copy + one invariant test): **~1 focused session,
  ~half a day, single PR** — an 588-class fix. Does NOT touch the trace funnel or §11's full UX.
- **Full §8 + §11** (per-stage trace cardinality + funnel UX + explain-panel relocation + mode-aware label
  + two-pass + a11y): **MEDIUM tier — ~3–5 focused sessions / ~2–4 days bursty wall-clock / ~25–40
  commits**, closest to 585/580/559. Three cost centers: (a) executor→outcome cardinality plumbing across
  the ~6 `SearchExecutor` cases + `executeChunkBranchFusion` (de-risk #1); (b) the 2-proto + schema-regen
  + conformance + `execution-surface`/`contract-projection` gate dance (iterative); (c) the FE funnel UX
  needing visual iteration (`ui-shot`).
- **Risk of escalation to LARGE:** only if the §11.5 no-load-more product question resolves toward
  *actually paginating to the matched set* — a separate UX track that could add days. Embraced (the
  recommended funnel-phrase path), 597 stays medium. Consistent with the §10 7/10 implementation
  confidence: well-understood (not a 549/577-style machinery invention), but real surface across three
  processes (not a same-day slice).

## 13. Adjacent work & interference scan (range 577–617, 2026-06-16)

Checked the ±20 tempdoc neighbourhood + worktrees for work that could collide with 597's remaining
implementation:

- **No active worktrees.** `git worktree list` shows only `main`; the `.claude/worktrees/{565,569,587,591}`
  dirs are stale/orphaned (no `worktree-*` branches). Nothing is being implemented concurrently right now.
- **The live neighbours are the 593→596 design docs**, all "open — design-theory, do NOT implement yet"
  (user-held). 593 (UX walkthrough) is CLOSED and is the parent observation for all four.
- **Scope/lineage: NO interference — by explicit mutual agreement.** 594/595/596 each carve 597's
  "facet count > result count / 136 cap" OUT of their own scope and assign it here (594 §13.3 / lines
  215-218: "DIFFERENT lineage … no interference with the chip strip"; 595 §342; 596 §195). The four are
  partitioned into distinct canonical authorities: **594** Display/Fact-catalog (chip *values*), **595**
  Observed-state/Stability (provisional vs settled), **596** Operability/availability (affordance reasons),
  **597** search-response-contract (count truthfulness / `SearchTrace`). 594 explicitly exempts search
  counts from its Fact authority → sibling-not-merged (553 pattern), no collision.
- **REAL interference = physical co-location on `SearchSurface.ts`'s results-header/banner region.**
  596 edits the "Ask AI" affordance (`SearchSurface.ts:1359-1363`, `:822-829`); 595 rewords the
  "Semantic search degraded" banner; 597 edits the headline meta (`:1339-1349`) + facet row (`~:1110`).
  The headline meta and the Ask-AI button sit in the **same ~30-line render block** (`renderMeta`/
  copy-actions, ~`:1333-1366`). If 595/596/597 are implemented in parallel worktrees they WILL
  merge-conflict on this file → **sequence them, or coordinate the header region as one edit.**
- **One factual dependency on 595.** 595 §10 found the "Semantic search degraded / keyword results"
  banner is *factually wrong* (semantic search is fine; only the OPTIONAL LambdaMART re-ranker is down →
  over-alarming). 597 §11.2.4 currently treats "degraded is the common state." If 595 lands first and
  fixes the banner, 597's degraded-state *narrative* shifts (the banner may stop showing for the
  reranker-only case). 597's matched-count logic is unaffected (it is a lexical count regardless), but
  §11 should defer to 595 on the banner wording. **Net: coordinate, not block.**
- **Possible future gate from 594/595.** If 594's Fact-projection authority or 595's verdict
  single-derivation gate become build-enforced later, 597's count must stay projected-from-source (it is —
  from `SearchTrace`), and both explicitly exempt the search count's surface. Low risk, aligned in spirit.

## 14. De-risk round 2 — live-grounded (2026-06-16, dev stack up)

Four probes run (code + live browser, corpus now **605 docs**). The standing residuals are closed and one
mid-analysis claim was corrected by live data.

- **Probe A (MATCHED obtainability) — RESOLVED, and it corrects an earlier claim.** Code path:
  `TextQueryOps.searchText` → `readPathOps.search(q, limit+1, …)` → `topDocs.totalHits.value()`; the
  `limit+1` caps hits *kept*, not hits *counted*, so I first concluded the retrieval `totalHits` is a true
  match count (exact ≤ Lucene's 1000 threshold). **Probe C's live data REFUTES that:** the query ran the
  `sparse_shortcut` path with **Fusion + Chunk-merge both SKIPPED** (no union step at all), yet the
  headline read **411** while the `en-US` facet alone was **428** — i.e. the *raw retrieval* `totalHits`
  already under-counts the true match set, by a mechanism I can't fully pin from here (threshold theory
  predicts they'd agree; they don't — likely an expansion/query-divergence between the retrieval count and
  the facet scan). **Robust conclusion (interrogate-results):** do NOT source the headline from the
  retrieval/leg `totalHits` — it under-counts even with no fusion. MATCHED must be a dedicated
  **`IndexSearcher.count(predicateQuery)`** (exact, unbounded) computed in `SearchResponseBuilder` over the
  **same chunk-excluded query the facets scan**, which guarantees `headline ≥ Σ facets` by construction.
  **De-risk upside:** the user-facing headline needs **NO executor→outcome plumbing** — just one cheap
  count() where the facet scan already runs. The executor plumbing (round-1 finding #1) is needed ONLY for
  the explain-panel per-stage funnel (the union/ranked numbers), i.e. the diagnostic tier, not the fix.
- **Probe C (live contradiction) — CONFIRMED, first end-to-end reproduction.** Query "document":
  **headline "411 results"** beside facet chips **Type · markdown 426**, **Language · en-US 428** — both
  *exceed* 411. The §2.2 / §11 contradiction is real in the running UI (prior sessions the backend was
  wedged; this is the first live capture). Funnel confirmed: shown (≤50) < headline 411 < matched (≥428).
- **Probe B (explain-panel home) — CONFIRMED live.** The `<jf-search-trace>` panel renders exactly the
  two-altitude shape (`searchTraceExplain.ts`): a worded user line ("Keyword search · AI-expanded query")
  + a **"Pipeline details"** disclosure listing every stage with `status · reason · ms`
  (e.g. `Fusion skipped (single-leg-or-no-retrieval) · 16ms`). A per-stage **cardinality** slots beside
  `ms` — so the relocated "ranked candidates"/union number has a real, visible home (it requires the §8
  `TraceStage` cardinality field).
- **Probe D (secondary) — RESOLVED.** `matchCount` cross-source merge = **SUM** (per
  `SearchPerSourceExecutor.java:95` `totalHits += r.getTotalHits()`; distinct corpora → disjoint matches →
  sum is correct). `searcher.count` latency is negligible (already used across `IndexCountOps`); the
  quick-pass fallback is fine.
- **Bonus — 595 dependency confirmed live.** The "Semantic search degraded — keyword results" banner
  showed **while the AI was Online and only LambdaMART (optional reranker) was unconfigured** — exactly
  595 §10's over-alarming finding. 597 should defer the banner wording to 595.

### 14.1 Revised confidence: **8/10** (core fix) — up from 7

Probe A swung favorably for the part that fixes the visible bug: the truthful headline is a **dedicated
`searcher.count` in `SearchResponseBuilder` + the facet-copy fix + the "Top N of M matches" phrasing** —
no executor plumbing, cheap, contract-chain mapped, merge rule known, and now **live-reproduced**. That
core/A′ fix is ~8/10. The **full §8 explain-panel funnel** (per-stage cardinality on `TraceStage` +
executor→outcome plumbing) remains ~6.5/10 (the diagnostic tier), but it is decoupled from the headline
fix and can follow. Net: the user-facing truthfulness fix is smaller and safer than the §12 "full" sizing
implied — closer to a **single-session A′** for the headline+facets, with the funnel detail as an
optional medium follow-on.

## 15. As-built & closure (IMPLEMENTED + MERGED, 2026-06-17)

Shipped on branch `worktree-597-result-count` (3 commits) and **merged to `main`** (`0e740766a`), built
green + live-verified in the real UI (corpus 605; "Top 50 of 451 matches", every facet chip ≤ 451,
funnel rung in Pipeline details).

**Built (satisfies the core + most of §8/§11):**
- `IndexCountOps.countQuery(Query)` — exact unbounded `searcher.count` (the facet-less fallback).
- `SearchResponseBuilder` computes the true **matchCount** = the facet engine's own `scanned`
  matched-doc total (`FacetsResult.matchedDocs`) when facets ran, else `countQuery`. **Live-discovered fix:**
  a dedicated `searcher.count` (432) disagreed with the facet scan (451) on the prefix-expanded query, so
  binding to the scan's own total makes `matchCount ≥ every facet` an **invariant by construction** (not the
  §14 plan's separate count). Threaded worker→head→FE (`matchCount` wire field; per-source SUM merge).
- FE headline = the funnel phrase **"Top N of M matches"** (`SearchSurface.matchCountLabel`); facet-chip
  copy "results"→"matches"; the union number relocated out of the headline.
- Per-stage **cardinality** on `SearchTrace.TraceStage` (matched on the lexical stage, ranked-union on the
  first executed fusion/merge stage) rendered in the explain panel — the §8.2/§11 funnel rung.
- **§8 governance**: `matchCount` pinned as a *verified projection* of the canonical trace's matched
  funnel-node (`FacetRegressionMatrixTest`: `response.matchCount == trace matched-node cardinality ∧ ≥ facets`).
- **§8.3 mode-aware label**: pure-dense / ANN (`effectiveMode VECTOR`) → "Top N ranked" (no match-set);
  UI-unreachable (always sends text) so unit-tested, not browser-triggerable.

**Deliberately NOT built (over-engineering — AHA / "don't create structures unnecessarily"):**
- §8.2's per-stage **(input, kept) PAIR**. On implementing it, the input side needs threading through ~15
  carrier-record construction sites (`ChunkMergeResult`/`ChunkRunOutcome`/`SearchOutcome` + their tests)
  plus a wire change and reflective-conformance updates — for a single diagnostic "entered N" number on one
  Advanced-panel stage, while the funnel (matched → ranked → shown) is already legible via the single
  per-stage cardinalities + the headline. Judged not worth churning working code. The trace carries the
  *output* cardinality per stage, not the entered/kept pair.

**Honest residual (the §8.6 limit, by design):** the matched count is intrinsically a response-stage fact
(the facet scan), so it lives in `SearchResponseBuilder`, not on the `SearchOutcome` value-model root; its
single-source guarantee is the projection-pin test, not the reflective value-model guard. A future count
surface that re-derives a count from scratch is not gate-prevented (the gate governs `SearchTrace`
referencers, not arbitrary counts) — the same honest limit §8.6 already states.

## 16. Design theory — where the forward ideas belong (2026-06-17)

> **What this section is.** A design-theory pass over the forward ideas (web prior-art + codebase + adjacent
> tempdocs) for the correct long-term *structure*, scoped to the problem 597 actually has. The disciplined
> conclusion: **597's count-truthfulness problem is structurally complete, and the forward ideas are mostly
> NOT 597 structure to build.** They split into three lineages, and 597 owns only the smallest. The
> supporting idea-catalog (§16.1–§16.5) is unchanged; read it under the routing in §16.0.

### 16.0 The thesis — three lineages; 597 owns the smallest (the count is a signal the search window projects)

597's job was the truthful *count*, and it shipped a new **canonical signal**: the matched count + the
per-stage funnel cardinalities, on `SearchTrace`/`KnowledgeSearchResponse`, governed by the
`execution-surface` register. That signal is *one of the canonical authorities the search window projects*.
So the forward ideas are not a "597 guidance feature" — they are:

1. **597's own — the only genuine 597 remaining structure: finish the count's honest display.** The
   `truncated` lower-bound rendered as **"M+"** (§16.2). It belongs to 597 because it is the *count's*
   presentation, and it is a trivial extension of the shipped `matchCountLabel`, not new structure.
2. **570's — the guidance/UX ideas, whose correct structure is *already written*.** Tempdoc 570 ("the
   search window as a projection of the canonical authorities") has exactly this scope (zero/empty states,
   the facet model, result-state), and its Part II Moves already name our ideas:
   - **did-you-mean / zero-result recovery → 570 Move D** (the query region projects the `SearchTrace`
     `correction` facet — 570: "the data is on the correction stage today, read only by the agent tool");
   - **too-many → refine-to-narrow → 570 Move E** (dual filtering as a projection of the emitted `facets`)
     **+ Move A** (search-as-mode: "refine in place / now only PDFs" is the *same* surface changing mode);
   - **count-as-result-state** (empty/sparse/broad/good): the matched count is the *new* authority those
     "never-dead-end" states (570 §3.7/§13) project from.
   570's discipline is decisive: the window must **project** these through the 569/559 spine, **never
   hand-author** them per-state in `SearchSurface.ts` — that hand-authored fork *is* the root defect 570
   diagnoses. So the correct design for the guidance ideas is **already theorized in 570**; 597 must not
   duplicate it as bespoke FE, and must not grow a parallel "result-state guidance" framework. 597's
   structural contribution is *being a clean canonical signal 570's spine can subscribe to* — which it
   already is.
3. **Independent polish — own small home, no unifying structure.** **Disjunctive facet counts** (a
   facet-engine semantics detail that 570 Move E's facet model absorbs) and a **visual funnel** (an
   explain-panel render detail over the shipped `TraceStage.cardinality`).

**Why 597 adds no new structure.** 597's problem — count truthfulness — is solved, with the count already a
registered, projectable authority. A "result-state guidance" structure would be both scope-creep into 570's
problem and the exact anti-pattern 570 names. The right-sized move is the *non-expansion*: ship the **M+**
finish, leave the count as the signal it is, and let 570's spine be the home that subscribes to it. The
size of the change is small — an outcome of the scoping judgment, not a target.

---

### 16.1 Validation — 597 is squarely on the IR/UX mainline

- **The matched-vs-window problem is industry-standard, and so is our fix.** Elasticsearch's
  `track_total_hits` is the *same* defect 597 addresses: ES counts exactly up to a threshold (default
  10,000) then returns `total.relation: "gte"` + a *lower-bound* `value` — i.e. Lucene's
  `TotalHits.Relation` (`EQUAL_TO` vs `GREATER_THAN_OR_EQUAL`). Count accuracy is a known accuracy/latency
  trade-off; presenting it truthfully (exact when cheap, bounded otherwise) is the established practice.
  Our facet-scan matched count (exact up to `maxDocsScanned`=50k, then `truncated`) is the same shape.
- **In faceted/personal search the count's value is as the facet denominator + refinement signal** — not
  a vanity number. (Web search de-emphasizes the raw count: Google's "About N results" is an *estimate*
  and Google has been *removing* it. We are not web search; our count anchors the facets, so it earns its
  place — but we should treat it as a refinement tool, not a headline trophy.)
- The funnel (matched → ranked → shown) is **"explainable retrieval"** with progressive disclosure (a
  worded user line + a collapsed "Pipeline details" diagnostic tier) — aligned with search-UX guidance.

### 16.2 Polish — finish the truthfulness story (small, high-confidence) — ✅ DONE (2026-06-17)

> **As-built (branch `worktree-597-mplus`, commit `f94a5220b`).** Implemented exactly as scoped: FE-only,
> threading the already-shipped `facetsTruncated` wire signal through the shell-v0 search path
> (`searchState` local `SearchResponse` → `SearchState` → `SearchSnapshot` → headline, mirroring
> `matchCount`) and extending `SearchSurface.matchCountLabel(matched, shown, rankedOnly, ranked, truncated)`
> to append `+` to the matched total when truncated. No backend change. **Verified to every tier:**
> `npm run typecheck` clean; full FE suite **3075/3075** incl. 3 new `matchCountLabel` truncated cases;
> `search-issuance` gate green. **Live-verified** on the dev stack (2026-06-17) by temporarily lowering the
> facet `maxDocsScanned` to 200 (reverted after): the SearchSurface headline rendered **"Top 50 of 201+
> matches"** with every facet chip ≤ 201 (markdown 199 / en-US 200) — the funnel intact, the `+` marking the
> lower bound. The non-truncated path also confirmed live: **"Top 50 of 582 matches"** (≥ every facet, the
> §1 invariant). This closes 597's only remaining owned item; nothing else in §16 is 597's to build.

- **Surface the `truncated` lower-bound as "M+" (the ES `gte` pattern).** Today `FacetsResult.matchedDocs`
  caps at `maxDocsScanned` (50k) and sets `truncated`, but the FE renders a bare number. When truncated,
  show **"Top 50 of 50,000+ matches"** (and `aria` "50,000 or more matches"). Tiny change (thread
  `facetsTruncated` is already on the wire; the headline reads it) that closes the last honesty gap —
  exactly what ES/Lucene/Google do. *Hook:* `KnowledgeSearchResponse.facetsTruncated` (exists) +
  `SearchSurface.matchCountLabel`.

### 16.3 Extend — turn the count into a productive flow (medium; reuses existing hooks)

- **Zero-result recovery (the biggest gap, all parts already exist).** "0 matches" is a dead-end today;
  research says *never* "check spelling", always offer a concrete path (did-you-mean, AI assist, related).
  We have every piece, just unwired on the empty state:
  - the **correction "searched for X instead"** (emitted on the trace's correction stage, today only in
    the explain panel) → render a *"Search for ‘X’ instead?"* link on zero results;
  - the **`handleAskAi` button** (exists, shown only when `results>0`) → also show on zero results
    ("No matches — ask the AI across your library");
  - the **unused `EmptyStateRegistry`** (`commands/EmptyStateRegistry.ts`, built for exactly Raycast-style
    "Ask AI about X" cards, context `search-no-results`) → have `SearchSurface` call `listEmptyStates`.
- **"Too many — refine to surface more" (the §11.5 answer, made active).** When `matched ≫ shown` the
  funnel headline already states it; add a gentle affordance pointing at the *facets* as the narrowing
  mechanism (prior art: post-query refinement suggestions; users narrow rather than page). This is the
  principled resolution of the no-load-more limitation: the matched set is reachable by *refining*, not
  scrolling. *Hook:* facet chips already re-run the filtered search (`handleFacetToggle → submitQuery`);
  add a threshold-gated hint ("451 matches — narrow by Type or Language to see the most relevant").
- **Disjunctive facet counts.** Today selecting a facet value re-runs a filtered search, so other values
  in the *same* group collapse toward 0 (you can't compare "markdown 448 vs pdf 1" after selecting one).
  The e-commerce-standard fix (Algolia/Meilisearch "disjunctive faceting") keeps each value's count
  *independent within its own group* so users can multi-select and compare. A facet-UX upgrade the
  matched-count denominator makes coherent.

### 16.4 New features — novel directions the count/funnel unlock (larger)

- **The matched count as a query-quality signal → adaptive search.** `M` is a free broadness gauge:
  very large `M` ⇒ "broad query, refine"; `M==0` ⇒ recovery; a healthy `M` ⇒ quiet. One signal driving
  zero-result recovery, too-many hints, and (later) auto-suggested narrowings.
- **A visual retrieval funnel.** The per-stage cardinality is rendered as text ("· N docs"); a tiny
  visual funnel/bar (matched 451 → ranked 124 → shown 50) in "Pipeline details" would make the
  retrieval pipeline legible at a glance — "explainable search" for power users, building directly on the
  shipped `TraceStage.cardinality`.
- **Faceted drill-down as a first-class browse mode.** The infra is complete (live re-run on facet
  toggle; matched count as the denominator); promoting faceted navigation from a quiet chip row to a
  prominent browse affordance turns the truthful count + facets into a genuine exploration surface.

### 16.5 Priority read, by owner (per the §16.0 routing — no rush, app pre-production)

- **597's only follow-on:** the **"M+" truncation** display (§16.2) — trivial, closes the honesty story,
  the one item that is genuinely 597's. Could ship as a tiny 597 addendum.
- **Routed to 570** (do NOT build as 597 bespoke FE): zero-result recovery (→ Move D), too-many
  refine-to-narrow (→ Moves E + A), count-as-result-state (the matched count as the new signal those
  Moves project). These land *only* when 570's projection spine is built — as projections, not hand-Lit.
- **Independent polish:** disjunctive facet counts (facet-engine / 570 Move E facet model); the visual
  funnel (an explain-panel render detail).

None is required: the shipped 597 already meets its truthfulness goal, and its count is already the clean,
governed signal everything above subscribes to. The correct long-term design is the *non-expansion* —
finish M+, and let the search-window design (570) own the guidance as projections of this signal.

---

## REOPENED — second pass (2026-06-17, 593 regression sweep)

> The 593 walkthrough was re-run against `main` (HEAD `cc293577b`) after this doc merged. The
> count fix verified on the **dedicated Search surface** but the re-run found it does **not**
> extend to the **Chat-surface Search tab**. This section reopens 597 for that residual only.

### R-1 — the matchCount contract is surface-specific (Still-Present #5)

- **Observed (regression sweep, Still-Present #5):** the **Chat-surface Search tab** still
  renders the old "N results" (the window-as-count) with **facets exceeding it**, and shows
  **no** export chips / timing / pipeline. The same query returned **424** on the dedicated
  Search surface vs **405** on the Chat surface — i.e. the two surfaces report a *different
  count for the same query*, and only one is the truthful matchCount.
- **Why this is in 597's scope, not 602:** 597's thesis is that *every* count is a governed
  projection of the matched-population cardinality "so they cannot drift by construction." A
  second renderer that re-derives the count from the bounded fused-union is exactly the
  drift the thesis forbids — the fix landed on one consumer, not on the contract's reach.
  (602 R3 catalogs the *renderer divergence* generally; the **count** half is 597's.)

### Residual scope (design only — not yet implemented)

- Make the Chat-surface Search tab consume the same `matchCount` projection + "Top N of M
  matches" label as the dedicated Search surface, so the two surfaces cannot report different
  counts for one query.
- Confirm whether the Chat-surface card is a genuinely separate renderer (602 R3) or the same
  component fed a different response shape — that determines whether this is a one-line
  consumer fix or part of the render-single-authority work in 602 R3 / 570.

### Disposition

- **Reopened for R-1 only.** The §1–§15 design + the dedicated-Search-surface implementation
  stand as merged history. This is a reach/coverage completion, not a redesign.
- Converges with **602 R3** (two divergent result renderers): if R3 is taken up as a render
  single-authority pass, the count half (R-1) should land with it rather than as a second
  bespoke patch. Cross-referenced both ways.
