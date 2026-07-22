---
title: "evidence-span authority + delivery governor: one canonical answer-bearing-span representation for search excerpts, RAG passages, and the MCP preview — plus graceful degradation at the delivery cap; absorbs 771's two surviving items"
type: tempdocs
status: "designed (§E, 2026-07-22) — awaiting plan/implementation. Settled: one canonical EvidenceSpan (worker-side selector, N consumer envelopes) + deterministic delivery governor; migration order delivery→RAG→CE-input (CE-input = 774 Stage 2); orphans named. Absorbs tempdoc 771 items 1b (evidence-content excerpt gap) and 4 (response-size governor). Pre-hero-campaign: surface changes here bump cohort identity, so this lands (one TOOL_SURFACE_VERSION bump, sequenced with 770) BEFORE the hero pre-registration or not at all. Unsettled: EvidenceSpan-as-new-record vs ContextCitation-generalization, entity-coverage signal, governor budget constant — all deferred to implementation with a measurement."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 762→771 measurement program's inventory (founder-directed 2026-07-22)
category: search-engine / evidence-delivery / agent-tool-surface
related:
  - 771-post-rebuild-retrieval-residue   # items 1b + 4 transfer here; measurements banked in its §E
  - 770-agent-tool-surface-economy-lane  # the delivery reshape this builds on; truncation cap characterized
  - 749-rag-chunk-retrieval-fallback-bug # F-038 union leg — the RAG passage machinery to conform to
  - 774-passage-first-retrieval-program  # the deep sibling; this lane is the delivery half, buildable first
---

> Charter. Evidence is banked (771 §E, 770 §E.3) — this lane designs and
> ships; re-measurement is only needed where the design changes what 771
> measured.
>
> **Dated addendum (2026-07-22, from tempdoc 774 §K — read before designing):**
> 774 Stages 1-2 landed on `worktree-774-passage-first` and partially
> supersede this charter's banked evidence: a default-off flag
> `search.evidence_preview.enabled` already delivers the winning chunk's
> text as `content_preview` for chunk-sourced hits (F-041: legal hybrid
> +15%, enron +5.9%), deliberately overloading the existing field as a
> TRANSITIONAL contract for this lane to replace with the canonical span
> authority (774 §I.5/§K.4 tombstones). Consequences: (a) re-measure §A.1's
> 45% evidence-carriage with that flag ON before designing against the old
> number; (b) this lane owns absorbing the transitional `content_preview`
> overload into its one-span-authority type — the proxy-path sweep (lexical
> excerpt anchoring as sole evidence path, CE preview-snippet assembly, MCP
> snippet logic) is this lane's completion criterion per 774 §L family-A
> analysis; (c) 774 §F.1-5/6 + §L carry the code-level map of all four
> existing proxies with file:line anchors.

# 775 — evidence-span authority + delivery governor

## §A. The two banked defects (from 771, transferred)

1. **Evidence-content gap (771 item 1b).** Delivered search excerpts carry
   the answer-relevant entity in 93% of successful enron retrievals but only
   **45% on legal** — long CLERC docs bury the key sentence at median offset
   ~5,000 chars, past the ~4KB preview. On the hardest domain, even
   successful retrieval often cannot seed the agent's next step. Same class
   as the deferred read-amplification item: agents re-Read full files
   because the delivered span isn't answer-bearing.
2. **Response-size cliff (771 item 4).** Raw payloads overshoot the
   46.6–52.8k truncation threshold (770 §E.3) at realistic limits: up to
   288KB at limit 30; reachable at limit 10 on legal. The truncation notice
   delivers neither content tier. Residual from 771: whether 770's gated
   delivery degrades gracefully at the cliff is unverified.

## §B. The structural problem (why one lane, not two patches)

Three span-selecting systems exist and drift independently: search excerpt
regions (IDF-weighted, worker-side), the RAG chunk/passage machinery
(F-038's union leg, citation resolution), and the MCP preview/snippet
(770's reshaped delivery). Each answers "which part of this document is the
evidence?" with different code. The lane's job is ONE span authority —
selection that is answer-bearing (query-term AND entity coverage, not just
lead/IDF), sized for delivery, consumed by all three surfaces — plus a
governor that degrades deterministically at the cap (drop tail results,
never truncate mid-payload). Projection-vs-fork discipline applies
(execution-surfaces register; conform to F-038's machinery, don't fork a
fourth system).

## §C. Acceptance

- The 771 §E probes re-run green: legal evidence-carriage materially up from
  45% (target: parity-with-enron band, measured not asserted); no
  gold-reachability loss at the same k.
- Governor: at limit 30 on legal, delivery degrades by result-count
  reduction with an explicit notice — never a mid-payload truncation; the
  770 golden/totality guards extended to the governor path.
- Perf ratchets green; MCP contract tests + full suites green; cohort bump
  (TOOL_SURFACE_VERSION) landed once, deliberately, pre-hero.
- 771 closed: its status updated to fully-dispositioned on this lane's
  opening (done in the same PR as this charter).

## §D. Constraints

F-016-as-weak-prior (770 §A.4): schema changes arguable on merit; capability
lives in selection/shaping, not new required parameters. D-005: span
selection reacts to query + document content, never corpus identity.

## §E. Design (2026-07-22)

**Codebase reality (verified `file:line`).** Three span selectors exist and
drift, exactly as §B claims — now pinned to source:
1. **Delivery excerpt** — `HighlightingOps.computeExcerptRegions`
   (`worker-services/.../HighlightingOps.java:96`) runs MemoryIndex match-offset
   clustering + BM25/IDF/tf/position scoring over the **full document content**,
   emitting `KnowledgeSearchResponse.Hit.excerptRegions`
   (`ExcerptRegion(text,startChar,endChar,approxLine,matchSpans)`,
   `KnowledgeSearchResponse.java:101`). Sees the whole doc; selects by query-term
   IDF density, not answer/entity bearing.
2. **CE scoring input** — `SearchResultMapper.extractQueryFocusedSnippet`
   (`SearchResultMapper.java:183`), length `RERANK_SNIPPET_LENGTH=1500`, called
   at `KnowledgeSearchEngine.java:904` over the **truncated `content_preview`
   field**, not full content. On long CLERC docs the bridge sentence at median
   offset ~5005 is *past the ~4 KB preview* — so **the CE judge never scores the
   answer-bearing text** (the §J.2 CE-gate defect: the span that makes a hit rank
   ≠ the span the judge scores ≠ the span delivered).
3. **RAG passage/citation** — `RagContextOps.excerptTextFor` /
   `clampExcerptToWordBoundary` (`RagContextOps.java:1361,:144`) mint
   `DocumentService.ContextCitation(parentDocId,…,startChar,endChar,startLine,
   endLine,headingText)` (`DocumentService.java:221`), the register's sibling
   evidence record (`execution-surfaces.v1.json` `siblingRecords`). Chunk-first;
   the register already logs its mid-word-clip boundary defect.
   Chunk provenance is present: `chunk_start_char` / `chunk_start_line`
   (`SchemaFields.java:120`), so a chunk-branch winner carries parent-relative
   offsets — 774 Stage 2 ("the winning passage IS the span") is feasible today.

**Settled type — `EvidenceSpan` (one selection authority, N consumer envelopes).**
Per projection-vs-fork (execution-surfaces register) and AHA (unify only the
shared reason-to-change): the *selection* — which window of a doc is the
evidence — is the one thing that drifts across all three, so **the selector and
the span value unify; the consumer envelopes stay distinct** (delivery adds
`matchSpans`; citation adds `headingText`/line semantics; CE wants only text).
Fields: `parentDocId`, `charStart`/`charEnd`, `lineStart`/`lineEnd`,
`headingText`, `text`, and *selection provenance* — `selectingLegs` (which
retrieval legs/queries surfaced this window), `entityCoverage` (which query
distinguishing-entities the window carries, from the existing NER fields). Minted
**worker-side, once, at retrieval time** by a new `EvidenceSpanSelector` at the
locus `computeExcerptRegions` already occupies (full-content MemoryIndex pass,
post-fusion top-N). Wire: registered as a **third sibling record** in
`execution-surfaces.v1.json` (a projection substrate under both `ExcerptRegion`
and `ContextCitation.excerpt`, *not* a SearchTrace projection — no shared field,
same reasoning that keeps SearchTrace/ContextCitation siblings). No new required
MCP parameter (F-016 weak-prior); capability lives in the selection.

**Selection contract (answer-bearing, D-005-clean).** Rank candidate windows by
query-term coverage **AND** distinguishing-entity coverage — prefer the window
carrying the query's rare/entity token over the densest query-term cluster (the
45%-legal miss is exactly a densest-cluster ≠ entity-sentence divergence).
Chunk-branch winners: the winning chunk's `chunk_start_char` window **is** the
span (evidence-coherence). Chunkless/short docs: head ≈ whole doc, current
behavior. Long docs: scan the full content (not the preview) for the
entity-bearing region — this is the 45%→parity-band lever. Reacts only to
query + document content, never corpus identity.

**Governor (deterministic degradation at the cap).** Sits **after** 770's gated
`McpEvidenceProjection` delivery, on the assembled payload. Budget ≈ 46 KB
serialized (below the measured 46.6–52.8 k cliff, 770 §E.3, with CLI-drift
margin). Degradation order (770 §E.3, refined): numeric provenance → **drop whole
tail results, never truncate mid-payload or mid-span** → emit an explicit notice
naming count-dropped + budget. Replaces the 2,322-char neither-tier loss notice
on the cliff path. The residual 771 §E item-4 question ("does 770's gating
degrade gracefully at the cliff?") is this design's **verification case**: a test
at limit 30 on legal asserting result-count reduction + notice, never
neither-tier loss; the 770 golden/totality guards extend to the governor path.

**Migration order (each step independently flagged + measurable).**
1. **Delivery first.** `EvidenceSpanSelector` mints `EvidenceSpan`;
   `ExcerptRegion` projects from it. Measure: legal evidence-carriage 45% →
   parity-with-enron band (the §C acceptance target, re-run 771 §E 1b probe).
2. **RAG conformance second.** `ContextCitation.excerpt`/offsets derive from
   `EvidenceSpan` (F-038 union-leg machinery is already passage-shaped — conform,
   don't fork); closes the register's mid-word-clip boundary defect, FE never
   re-windows. Measure: citation excerpt carries the entity; no re-window.
3. **CE input third (= 774 Stage 2).** CE scores `EvidenceSpan.text`, retiring
   the preview-windowed `extractQueryFocusedSnippet` path. Designed as a
   **consumer swap** (docText = span text), not a redesign. Measure: CE-input
   entity-carriage over full content, not the truncated preview.

**Orphans (named per step; deleted/re-pointed in this tempdoc, not a later sweep).**
- `extractQueryFocusedSnippet`-as-CE-input (`SearchResultMapper.java:183`) — the
  preview-windowing selection is **retired** at step 3 (its callers move to
  `EvidenceSpan.text`).
- IDF-excerpt-**scoring** as delivery selection (`HighlightingOps.scoreCluster`,
  the IDF-only cluster ranker) — superseded by the entity+query-term contract;
  the full-content MemoryIndex plumbing is **re-pointed, not deleted** (right
  locus).
- RAG-side duplicate selection (`RagContextOps.excerptTextFor` /
  `clampExcerptToWordBoundary`) — becomes a **projection** of `EvidenceSpan`; the
  independent windowing is retired.

**Cohort + perf.** All agent-surface-visible shape changes (the delivery span
reaching the MCP preview) land in **one** `TOOL_SURFACE_VERSION` bump, sequenced
with 770's pending bump and the hero pre-registration (§C, 770 §H) — not landed
blind. Perf: the selector reuses the existing post-fusion top-N MemoryIndex pass
(no new full-doc re-scan; entity-coverage is a lookup over the same match offsets
+ NER fields); latency envelope = **no measurable search-p50 / CE-p50 /
throughput regression beyond the excerpt pass that already runs** (640 ratchets
hold; state and re-measure at implementation).

**Reach.** This is the **evidence-coherence principle** (774 §I.7) made
structural: *the span that makes a hit rank is the span the judge scores is the
span the surface delivers* — one selection authority, N consumers. It is an
instance of the 658 projection kernel that already governs `McpEvidenceProjection`
(one canonical record → governed projections → a gate forbidding re-authoring);
**conform, do not build a parallel.** Candidate scope: 777's listwise LLM judge
is a 4th consumer that reads `EvidenceSpan` unchanged. *Earns its keep* when
legal carriage rises, the CE demonstrably scores the answer-bearing text, and no
5th span selector appears. *Retire* the unification only if two consumers'
reasons-to-change genuinely diverge (a consumer needs a window the others must
*not* have) — then re-fork deliberately, with a register row, not by drift.

**Unsettled (flagged for the plan/implementation pass).** (a) Whether
`EvidenceSpan` is a *new* registered record vs. a generalization of
`ContextCitation` — both satisfy projection-vs-fork; the register author picks at
implementation (leaning new-sibling, since search excerpts have no `parentDocId`
semantics). (b) The exact entity-coverage signal (NER-field membership vs.
df-rarity threshold) — pick against the 771 §E 1b offline probe, not a priori.
(c) Governor budget constant (≈46 KB) pending a production-tool delivered-size
measurement (771 §E item-4's stated gap: dev MCP previews didn't truncate at
limit 30).
