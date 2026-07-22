---
title: "evidence-span authority + delivery governor: one canonical answer-bearing-span representation for search excerpts, RAG passages, and the MCP preview — plus graceful degradation at the delivery cap; absorbs 771's two surviving items"
type: tempdocs
status: "STEP 1 IMPLEMENTED (§F, 2026-07-22) — EvidenceSpan record + EvidenceSpanSelector minted at the computeExcerptRegions locus; ExcerptRegion projects from it; flag-gated (search.evidence_span.enabled, default off = today's IDF-only output byte-for-byte). Deferred items settled: (a) new sibling record (registered in execution-surfaces.v1.json); (b) entity signal = ner_membership (§F probe: 100% carriage vs df_rarity 28%). Live probe: excerpt carriage 0%→100% flag-on, gold reachability unchanged (50/50), no measurable excerpt-stage latency regression. REMAINING (out of this step): step 2 RAG/ContextCitation conformance, step 3 CE input (= 774 Stage 2), the delivery governor, and the MCP TOOL_SURFACE_VERSION bump (freeze-coordinated). Design (§E): one canonical EvidenceSpan (worker-side selector, N consumer envelopes) + deterministic delivery governor; migration order delivery→RAG→CE-input; absorbs tempdoc 771 items 1b + 4."
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

## §F. Step-1 implementation log (2026-07-22)

STEP 1 only (delivery excerpt): the `EvidenceSpan` record + `EvidenceSpanSelector`
minted worker-side at the `computeExcerptRegions` locus; `ExcerptRegion` projects
from the span; flag-gated (default = today's IDF-only output byte-for-byte). RAG
(step 2), CE-input (step 3 = 774 Stage 2), the MCP TOOL_SURFACE_VERSION bump, and
the governor are OUT of scope and untouched.

**Implementation (`file:line`).**
- `EvidenceSpan` record — `modules/worker-services/.../services/evidence/EvidenceSpan.java`
  (fields per §E: parentDocId, charStart/charEnd, lineStart/lineEnd, headingText, text,
  selectingLegs, entityCoverage).
- `EvidenceSpanSelector` — `.../services/evidence/EvidenceSpanSelector.java:35`. Reuses the
  existing candidate windows (`HighlightingOps.buildClusters`/`projectWindow`/`windowMatchSpans`,
  `collectMatchOffsets`/`scoreCluster` — all extracted/made-public, behavior-preserving); the ONE
  change is ranking: `(entityScore desc, base desc)` so the entity-bearing window beats the densest
  query-term cluster. `toExcerptRegion(...)` is the delivery projection (adds matchSpans; drops the
  step-2/3 + provenance fields).
- `HighlightingOps.computeExcerptRegions` refactor (`.../services/HighlightingOps.java`) — extracted
  the clustering + window geometry into shared helpers, computeExcerptRegions output UNCHANGED
  (guarded by the golden test).
- Flag wiring — `SearchResponseBuilder.java:410` (`buildEvidenceSelector`) + `:526` (project via
  `EvidenceSpanSelector.toExcerptRegion`) + `:530` (unchanged IDF-only fallback); config threaded via
  `SearchOrchestrator` `lifecycle::resolvedConfig`.
- Flag (D-004 template) — `ConfigKey.java`, `EnvRegistry.java`
  (`JUSTSEARCH_SEARCH_EVIDENCE_SPAN_ENABLED`, `..._ENTITY_SIGNAL`), `ResolvedConfigBuilder.java`,
  `ResolvedConfig.Search` (evidenceSpanEnabled / evidenceSpanEntitySignal). Worker reads it from the
  Head→Worker config snapshot (worker is ArchUnit-forbidden from env/sysprop reads).
- Register — `governance/execution-surfaces.v1.json`: `EvidenceSpan` added as the THIRD sibling
  record + `EvidenceSpanSelector` producer (guardKind `reflective`, recordId `EvidenceSpan`) +
  `SearchResponseBuilder` delivery consumer + import pattern. `execution-surface` gate GREEN.
- Tests — `EvidenceSpanSelectorTest` (selection-law unit test), `EvidenceSpanProjectionConformanceTest`
  (reflective totality guard), `ExcerptRegionDefaultsByteEquivalenceTest` (flag-off byte-golden).

**Deferred-item decisions.**
- (a) new-record vs ContextCitation-generalization → **new sibling record** (§E lean confirmed): search
  excerpts have no `parentDocId` semantics for a chunkless doc; registered as the third sibling in the
  execution-surfaces register (not a SearchTrace projection — no shared field).
- (b) entity-coverage signal (NER-membership vs df-rarity) → **NER_MEMBERSHIP wins** on the §F probe and
  is the shipped default; df-rarity is the measured loser (kept selectable via the config key):

| config (flag) | excerpt carriage | gold reachability (ranks == flag-off) | latency p50 limit30 |
|---|---|---|---|
| OFF (IDF-only, pre-change) | **0 / 50 (0%)** | — (baseline) | 230 ms |
| ON — df_rarity | **14 / 50 (28%)** | 50/50 identical | 218 ms |
| ON — ner_membership | **50 / 50 (100%)** | 50/50 identical | 205 ms |

**Acceptance vs targets.** Carriage rises materially from 0% (flag-off) to 100% (flag-on ner_membership,
reaching the enron parity band) on the buried-entity stratum; spot-checked genuine (delivered region at
the ~5 KB buried offset contains the actual bridge name). **No gold-reachability change** — ranks are
byte-identical flag-on vs flag-off for all 50 (the flag only changes excerpt text, never ranking; empirical
+ by-construction). **Perf**: flag-on excerpt-stage adds no measurable latency (p50 205–218 ms vs 230 ms
off — within noise, well under the 20% budget); the selector reuses the existing post-fusion MemoryIndex
pass (df-rarity adds one batched df lookup, ner-membership a stored-field tokenize — both negligible).

**Probe method + deviations (honest).** Live boot from THIS worktree's dist (df/ner/off each a
rebuild-flip of the default + restart on the same index; the worker cannot read env/sysprop, so the flag
is toggled via the compile-time default and verified present in the worker config snapshot). The certified
1001-doc cell could **not** be re-materialized (767 §B2 HARD STOP: `datasets/` gitignored + gone,
`corpus-inject-real` needs a network CLERC fetch, exact rebuild command unrecorded). So the burial
condition was **faithfully reconstructed** from the committed 707 material: 50 long gold docs (median ~9 KB,
matching 771's 9191) each carrying 4 dense decoy query-term clusters + the committed fabricated bridge
sentence (with the df=1 bridge names) buried at ~offset 5000 (matching 771's 5005) — the densest-cluster ≠
entity-sentence divergence, so flag-off's top-3 excerpts are decoys (0% carriage). Deviations: a
reconstruction, not the certified cell (absolute baseline differs from 771's 45% preview-carriage — this
probe measures **excerptRegions** carriage, the surface this lane changes, whose flag-off baseline is 0%);
the probe uses the content-word (keyword) query form because the full natural question yields a single
excerpt region (a query-parse artifact worth a follow-up look). Two findings logged for later steps: (i)
on CHUNKED long docs the delivery excerpt is bounded to the winning chunk (design's "winning passage IS the
span") — cross-chunk delivery is a step-2 (RAG passage) concern, measured here with `chunk_aware` off to
isolate the full-content lever; (ii) the pure-paraphrase case where the answer window shares NO query term
with the query is not reachable by any query-match-window selector (incl. this one) — it needs the "scan
full content for entity regions even without a query match" extension, out of step-1 scope. Artifacts +
scripts: `tmp/analysis-624/775/step1/` (`prep.py`, `probe.py`, `results_{off,on_df,on_ner}.json`).

**Suites.** `./gradlew.bat build -x test` GREEN (PMD/spotbugs incl.); worker-services + configuration
module tests GREEN; full unit suite GREEN; `spotlessApply` clean; `execution-surface` gate GREEN.

## §G. Steps 2-3 re-framed as the unification pass (2026-07-22, post #286/#288)

Step 1 shipped (#288: EvidenceSpan + selector + interactive delivery;
carriage 0→100% on the buried-entity probe, ranking untouched). 774 Stage 2
landed in parallel (#286: `search.evidence_preview.enabled` — the winning
chunk's text as content_preview = CE input + preview coherence; F-041's
+15% legal / CE-flip on email). Two complementary flag-gated levers now
express the same evidence-coherence principle through different fields.
Steps 2-3 therefore become the UNIFICATION pass per this design's
one-authority thesis: the preview lever becomes an EvidenceSpan consumer
(chunk-sourced case = the span selects within/around the winning chunk),
RAG/ContextCitation conforms, and the governor lands — sequenced AFTER the
founder's F-041/775 default-flip decision so all evidence-surface changes
share one cohort bump with the hero timing.

## §H. Flip decision received (2026-07-22)

766 §G.1 approves both evidence flags default-ON in one bump. The §G
unification pass is therefore UNBLOCKED and becomes the carrier: one PR =
unification (preview lever consumes EvidenceSpan; RAG conformance; governor)
+ both default flips + baseline re-pins + the pub-cme flake-fix ride-along.
Sequenced next among engineering lanes.

## §I. Default-flip carrier + unification-pass status (2026-07-22)

**Shipped in this pass (the founder-authorized default flip — F-041 register
FLIP DECISION / 766 §F pre-hero surface work):** both evidence flags flipped
default-ON in one cohort bump.

- `search.evidence_preview.enabled` false→**true** and
  `search.evidence_span.enabled` false→**true**, at both declaration sites:
  `ResolvedConfigBuilder.contributeYamlSearch` (`putDefault`) and
  `ResolvedConfigBuilder.buildSearch` (the `resolveBoolean` fallback). No YAML
  resource pins either key (grep clean), and production wires a real config
  supplier (`SearchOrchestrator:92` → `lifecycle::resolvedConfig`), so the
  `putDefault` is the effective production default — the flip takes effect
  end-to-end (Head builds the snapshot; the Worker reads both keys from it).
- Stale "default false / byte-equivalent-default" prose swept in
  `ResolvedConfig.Search` javadoc/field comments,
  `docs/reference/configuration/environment-variables.md`, the
  `SearchResponseBuilder` null-supplier comment, and
  `SearchResponseBuilderEvidencePreviewTest`'s class doc (its cases set the flag
  explicitly, so the assertions are default-independent and needed no logic
  change).
- **Test pins:** the only value that read the old defaults through a *tested*
  path is `SearchResponseBuilderEvidencePreviewTest` (parameterized on the flag
  explicitly — robust). `SearchPlannerApprovalCorpusTest.newPlanner` hardcodes
  `evidencePreviewEnabled=false, evidenceSpanEnabled=false` in a fixture, but
  `SearchPlanner` never reads either field (they are `SearchResponseBuilder`
  concerns) — inert fixture slots, left unchanged to keep the diff scoped.
  `ExcerptRegionDefaultsByteEquivalenceTest` calls
  `HighlightingOps.computeExcerptRegions` directly (flag-independent) — unaffected.

**Rationale the flip is safe on measured evidence, not assertion:** each lever
is individually measured — `evidence_preview` ON = +15% legal / +5.9% enron
(F-041, register rows at legal-clerc/enron-qa), `evidence_span` ON = 0→100%
buried-entity carriage with byte-identical ranking (§F). Neither measurement
touched the CE-input / RAG / governor paths, so flipping both to default-ON
ships two independently-measured-good levers without those unmeasured changes.

**Deferred from this pass (unification steps 2-3), with primary-source blockers
— these need the eval "baseline re-pins" the founder decision itself commits to,
which cannot run in a code-only, in-turn worker pass:**

1. **Preview→span CE-input unification (§E step 3 "CE scores EvidenceSpan.text").**
   There is no byte-preserving form: `content_preview` (whole winning chunk,
   `capEvidencePreview`, ≤4096) and the excerpt window (`EvidenceSpanSelector`,
   answer-bearing) are *distinct-size envelopes* — §E's own AHA clause keeps
   distinct consumer envelopes distinct. The Head CE already windows
   `content_preview` to a query-focused snippet (`SearchResultMapper.extractQueryFocusedSnippet`,
   `SearchResultMapper.java:183`, len `RERANK_SNIPPET_LENGTH`) before scoring, so
   swapping its `docText` to the narrow span is a real quality change to the exact
   configuration F-041 measured (+15%). Must be A/B-measured before landing
   default-ON. `capEvidencePreview` is a size cap, not a competing *selection*, so
   there is no large parallel-selection orphan to delete under the current
   arrangement — for chunk hits the span already selects *within* the winning
   chunk (§G's stated unification), and both levers already read the one winning
   chunk.
2. **RAG/ContextCitation conformance (§E step 2).** Separate worker surface
   (`RagContextOps.excerptTextFor`/`clampExcerptToWordBoundary`,
   `RagContextOps.java`, minting `DocumentService.ContextCitation`). Acceptance
   ("citation excerpt carries the entity; no re-window") is a live-verification
   claim, and it changes RAG answer citations — quality-affecting, eval-gated.
3. **Delivery governor (§E "deterministic degradation at the cap"; §C acceptance).**
   §E flags the governor budget constant (≈46 KB) as **unsettled item (c):
   "pending a production-tool delivered-size measurement"** (771 §E item-4: dev
   MCP previews didn't truncate at limit 30), and §C's acceptance requires a live
   "limit 30 on legal → result-count reduction + notice, never mid-payload"
   delivery test. The mechanism is implementable on the `McpEvidenceProjection` /
   `McpToolSurface` delivery path (drop whole tail results, emit an explicit
   notice replacing the neither-tier loss notice), but landing it to spec needs
   the live delivered-size measurement + integration with 770's existing
   truncation cap (not a fork) — neither available in a code-only in-turn pass.

**Cohort-bump coupling (surfaced for the orchestrator/founder):** the founder
decision wanted the flip executed *together with* the unification pass in one
cohort bump. This pass ships the flip alone (the unification is eval-gated per
above), so if the unification later lands eval-ready it is a *second* cohort
bump. The orchestrator should decide whether to hold this flip branch until the
unification is eval-ready (one bump) or ship the flip now (accepting a later
second bump). Register baseline re-pins (the F-041 rows' "default-off flag"
annotations) are left to the orchestrator's eval pass — updating them here with
unmeasured "flipped" claims would be premature.


### §I.1 Baseline re-pins at flips-ON defaults (2026-07-22, orchestrator eval pass)

**Coupling resolved:** ship the flip now. §E designed steps 2-3 + governor as
independently-measured migration steps (each carries its own `Measure:` clause,
and the governor budget is §E-unsettled (c) pending a live measurement), so
holding the approved product flip hostage to those open questions inverts the
decision's intent. The remaining §E steps land later as measure-then-land lanes;
if one changes agent-surface-visible shape it sequences with the next
TOOL_SURFACE_VERSION bump.

**Re-pin campaign** — 5 corpora, hybrid at shipped defaults, git `be7fef6b`
(this branch), GPU CE confirmed (see ORT-pack note below), full enrichment
(`--pipeline`), fresh `--clean` builds; register ablation rows added under each
corpus (`src: 775 §I`):

| corpus | 715-era pin (hybrid) | flips-ON (be7fef6b) | delta |
|---|---|---|---|
| mixed/legal-clerc-200 | 0.5557–0.5609 (711/774 defaults band) | **0.6362** | **+~14% vs defaults band**; reproduces the F-041 flag-on 0.6388 within noise |
| mixed/enron-qa | 0.7445 (774 §K.2 OFF arm) | **0.7845** | **+5.4%**; reproduces F-041 flag-on 0.7882 within noise |
| beir/scifact | 0.758 (580) | 0.7604 | flat (control: short docs, preview flip is a no-op) |
| mixed/miracl-de-2k | 0.852 (666, post-regen) | 0.8591 | flat/+0.8% (within cross-run band) |
| mixed/miracl-fr-2k | 0.866 (666, post-regen) | 0.8726 | flat/+0.8% (within cross-run band) |

The gain pattern matches the F-041 mechanism exactly: material gains only where
long documents made the CE score doc-heads (legal, email); flat on short-doc
and factoid corpora. **Attribution caveat:** deltas vs the *715 release
scorecard* additionally include #286's CE `DOCS_TOO_LONG` gate fix (default
16000→0), which landed after the 715 rebaseline; the flag-on/flag-off rows from
774 §K.2 at `5f45022b` are the controlled comparison, and this campaign's
numbers reproduce their ON arms at defaults.

**Not updated here:** the public Release Scorecard (generated from
`release.v1.json`, 623 pipeline — founder-gated; decision 3 defers public
numbers pre-hero). This section + the register ablation rows are the internal
re-pin.

**ORT-pack incident (fixed machine-locally, observation logged):** the first
runs silently realized the reranker on CPU — 772 §J's new pack-completeness
check refuses the dev machine's pre-772 `tmp/ort-variant-test/cuda-12.4-v1.24.3`
layout (providers-only). Completed the pack (core `onnxruntime.dll` +
`onnxruntime4j_jni.dll` from the upstream 1.24.3 jar + `ort-native-version.txt`)
before any counted run; all five re-pin runs show GPU-band CE p50 (143–190 ms).

*Process note (P-C inline exception): the register/tempdoc edits of this pass
were done in the main loop — the numbers and row formats were already in
orchestrator context, putting the pass below the delegation break-even.*
