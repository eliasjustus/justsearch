---
title: "Second-hop compensation for weak agents: adjudicate gap-statement affordance vs answer-side hop absorption vs honest expectation-setting for the model-level entity-chain failure"
type: tempdocs
status: "open — planned + stop-gate probes GREEN (P0: planted codes NER-tagged as persons; P1: entity-code query ranks hop-2 doc #1 in 7/7) — hop-absorption feasibility live-confirmed; option (c) docs paragraph SHIPPED (a86d05b); flag-gated build + pre-registered A/B (~$40-45) awaits owner authorization"
created: 2026-07-14
author: "agent (area agent, tempdoc 725 remediation program)"
related:
  - 725   # parent program; owns the evidence (campaign D/T forensics), the descriptive-grammar rule, and the self-describing-results principle
  - 624   # measurement authority; Step-2 model-tier sweep is the owner-gated discriminator this area ties to
  - 707   # corpus substrate; the 2-hop task-shape question routes to its stratum taxonomy
---

# 733 — Second-hop compensation for weak agents

## The issue (inventory #1 from tempdoc 725's consolidated post-A/B inventory)

Weak (haiku-class) agents fail entity-chain lookups (facility→engineer→value) by answering with
the intermediate entity: **11/20 with-tool B-cells in BOTH campaigns** (D at surface 0.2.0, T at
0.3.0 — the level-2 response-shape improvements did not move it), and — decisive — **7/20
NO-TOOL A-cells fail the same way via grep** (Campaign T deep analysis, tempdoc 725). The failure
is model-level and tool-independent. Constraints inherited from 725:

- **Protocol neutrality** — the product cannot prompt the evaluated agent; the levers are what
  the product *is*.
- **Model-agnosticism axiom** (owner, 2026-07-14) — nothing may depend on one vendor's model
  behavior to work.
- **Descriptive grammar** (research pass #2) — in-result text states facts about the result and
  its limits, never commands about the next call (Anthropic injection-skepticism guidance).
- **Literature status at handoff**: in-result follow-up suggestions are UNTESTED in both
  directions (benefit and over-steering); IRCoT-style interleaving works but is harness-side;
  "capability absorption" is 725's theorization name for the deepest model-agnostic fix.

Options on the table from the handoff: **(a)** L4d descriptive gap-statement in results;
**(b)** answer-side hop absorption (LLM-free entity-neighbor expansion of the evidence pack);
**(c)** no product fix — route to 624 Step-2 and set expectations honestly in public docs.
They are not mutually exclusive. This tempdoc adjudicates them through
theorize → research → design → derisk → plan and STOPS at plan (orchestrator reviews before any
implementation wave).

---

# Phase 1 — THEORIZE

## Restating the failure precisely

The agent retrieves hop-1, *sees* the bridge entity, and stops — the completion quotes the
bridge entity (or its digits) as the answer. It is not a retrieval failure (hop-1 is found), not
a tool-legibility failure (the same behavior occurs through grep), and not an error ejection
(zero blocked calls in the shortcut bucket). It is **satisficing**: the bridge entity is
answer-shaped (a name/code where a name/code was asked for), and a weak model's stopping
criterion accepts it. Any compensation must either (i) change what the result *contains* so the
true answer is present at hop-1 time, (ii) change what the result *says* so the stopping
criterion is disturbed, or (iii) change what the *user expects* so the failure is priced in.
Options (b), (a), (c) are exactly these three mechanisms.

## Framings beyond the three options

1. **Affordance-present-but-unused (new evidence, this pass).** The surface ALREADY offers a
   manual second-hop affordance: `justsearch_search` facets `entity_persons_raw` /
   `entity_organizations_raw` / `entity_locations_raw` on every response
   (`McpToolSurface.java:675-677`) and its description says "Use these facet values as filters
   to scope retrieval: `filters: {… entity_persons: [\"…\"]}`" (`McpToolSurface.java:56-57`);
   `justsearch_answer` accepts the same entity filters (`McpToolSurface.java:442-444`). The
   failing cohort does not use them. This is direct in-house evidence that *information-only*
   affordances have a ceiling at this model tier — which weighs against expecting (a) to work
   and toward (b)'s "put the content in the pack" mechanism.
2. **Task-shape skepticism (Goodhart guard, inherited from 725).** The 20 queries are planted
   synthetic designer-code chains (`Cavby8`, `druker7`) inside a legal corpus, mislabeled
   `question_type: 1_hop` (inventory issue 14, routed to 731/707). Whether 2-hop entity chains
   represent real user tasks is unmeasured. Consequence: any lever must be justified by a
   task-generic mechanism (evidence completeness, honesty), never by fitting this eval's idioms;
   and the A/B's external validity is bounded until 707's stratum taxonomy answers the
   prevalence question.
3. **Client-side fix exists but is not ours.** IRCoT-style interleaving (harness-side) is the
   literature-proven fix for weak models. Protocol neutrality forbids the product from injecting
   it. Worth *documenting* for client authors (a docs sentence in (c)'s scope: "multi-hop
   questions benefit from iterative retrieval by the calling agent"), phrased descriptively —
   never worth building into results.
4. **Absorption has two strengths, not one.** Full absorption = the answer tool internally
   resolves the chain (multi-hop decomposition — LLM-shaped query planning). Partial absorption
   = ONE LLM-free entity-neighbor hop that makes the pack *contain* the hop-2 document. Only the
   partial form is currently buildable model-free; the full form re-introduces an inference
   dependency (latency, VRAM, and the very capability gradient we're compensating). This
   tempdoc's (b) is the partial form only.

## Hidden assumptions surfaced

1. **"Presence ⇒ use."** The strongest external caution (research phase, arXiv:2603.11513):
   sub-8B models fail to extract answers from ORACLE context 85-100% of the time in that
   benchmark's setting, and added context overturns 42-64% of previously-correct answers. Even
   a perfectly-expanded pack may be ignored by the weakest cohort. (b)'s benefit is therefore an
   empirical question, not a logical consequence of feasibility — the A/B is mandatory, and the
   expansion block must be short, clearly separated, and attributed.
2. **"The bridge entity is identifiable LLM-free."** NER gives all entities in a doc; salience
   (which one is the bridge) is the model-shaped part. Cheap non-LLM gates exist (query-novelty,
   frequency, hub pruning — research phase), but their sufficiency on real corpora is unproven.
3. **"NER tags the planted entities at all."** The probe corpus's bridge entities are synthetic
   codes (`Cavby8`). Whether the NER lane extracts them into `entity_persons_raw` is unverified —
   if it doesn't, the free probe needs a different mechanism (phrase search on the entity string)
   and the eval's expansion path diverges from the real-corpus path. Derisk U1.
4. **"Expansion is precision-neutral."** Refuted by the literature: every LLM-free graph system
   that measures it reports a real noise cost (entity ambiguity, high-degree hubs). And packs on
   legal text are ALREADY weak (bestChunkScore ~0.03, coverage ~0.60 — 725 derisk U3; area 731's
   territory). Expansion must have its own budget and its own quality reporting, and 731's
   curation work is a declared dependency, not something this area solves.
5. **"Model-agnostic ⇒ benefit-uniform."** No: (b)'s *mechanism* is universal (more true
   information in the pack, usable or ignorable by any model), but its *benefit* is
   cohort-relative (strong models don't need it; per the curb-cut frame it must not COST them).
   The axiom requires mechanism universality, which (b) satisfies; benefit asymmetry is fine and
   is what the negative controls price.

## Principle candidates (with retirement conditions)

- **P1 — "Absorb capability, don't induce behavior."** When a failure class is demonstrated to
  be model-level and tool-independent (the T-A grep evidence is the template for such a
  demonstration), prefer levers that change what the result CONTAINS (capability absorption,
  LLM-free) over levers that shape how the result is WORDED to induce the missing behavior
  (steering). Steering levers stay hypothesis-tier until multi-cohort evidence exists.
  *Retirement:* if 624 Step-2 shows the gap closes at the cheapest commodity tier clients
  actually use, compensation loses its constituency and this principle's application here
  retires to the docs sentence; or if the A/B shows absorption's noise cost exceeds its hit-rate
  gain, the principle survives but this instance retires.
- **P2 — "Intent classifies the lever, grammar only legalizes it."** A gap statement ("no value
  information appears in this document") is descriptive in grammar — legal per the 725 rules —
  but behavioral in intent: it exists to disturb the stopping criterion. Classification for
  evidence purposes must follow intent (Tier-3 / hypothesis, per 725's theorization tiers), not
  grammar. This blocks the quiet promotion of steering levers to Tier 1 by rewording.
  *Retirement:* if a multi-cohort pre-registered A/B shows descriptive gap statements improve
  hop-2 completion without over-steering on negative controls, reclassify that specific shape to
  Tier 1 and retire P2's application to it.

---

# Phase 2 — RESEARCH (2026-07-14; cited summaries only, one level deeper than the 725 pass)

> Warranted per the handoff: (i) LLM-free graph/entity expansion in production RAG beyond
> HopRAG; (ii) new result-side-affordance literature since the last pass. Executed as a focused
> web pass (sonnet lane); findings below are cited summaries, no code or text copied.

## Lane A — LLM-free entity/graph expansion is an established 2026 pattern

- **NoLLMRAG** (OpenReview KIUOtEKzzN) — three-layer heterogeneous graph index, zero LLM calls
  at indexing AND query time (graph-statistics keyword extraction + co-occurrence clustering);
  reports +41% avg QA over strongest baseline, 300x indexing / 15x QA speedups, framed around
  *reducing* noise vs LLM-graph baselines. Strongest direct precedent that LLM-free expansion
  can be a quality win, not just a cost win.
- **SPRIG / "Democratizing GraphRAG"** (arXiv:2602.23372) — CPU-only, token-free: lightweight
  NER co-occurrence graph + Personalized PageRank traversal. Directly analogous to our shape
  (NER → neighbor lookup, no LLM). Honestly documents the noise sources: no full entity
  linking/coreference, so **high-degree "hub" entities and residual ambiguity inject noise**;
  mitigates with title-alias disambiguation + hub pruning at negligible Recall@10 cost.
- **LinearRAG** (arXiv:2510.10114) and **LiteSemRAG** (arXiv:2604.16350) — same family;
  lightweight entity extraction + semantic linking, no LLM-built relations. Confirms the
  pattern is a trend, not one paper.
- **Query-Aware Spreading Activation** (arXiv:2606.30133) — a single cosine-similarity gate
  (candidate-entity description vs query) keeps graph traversal on-topic with no LLM per hop:
  +3.6 to +7.4 F1 over query-blind traversal, 1.5-4.9x latency cut. Concrete mechanism our
  salience gate can borrow (we have dense embeddings in-product).
- **BridgeRAG** (arXiv:2604.03384) — the closest architectural analogue: **dual-entity ANN
  expansion (LLM-free) broadens the hop-2 candidate pool; a separate downstream judge selects.**
  SOTA training-free R@5 on MuSiQue/2Wiki/HotpotQA. Validates our exact split: the product does
  LLM-free *expansion/coverage*; the calling agent does *selection* — which is what an evidence
  pack is for.
- **HELP** (arXiv:2602.20926) and **HippoRAG 2** (emergentmind topic page) — corroborate from
  the other side: LLM-in-the-loop graph steps are a *precision* liability (HELP), and where
  HippoRAG 2 keeps an LLM it is for filtering, with an 18% fail-closed fallback rate — i.e. the
  field puts LLMs in the precision seat, never the traversal seat. Since our design has no LLM
  in either seat, the missing precision safety net must be replaced by a cheap gate + honest
  quality reporting.

**Pattern across all systems that measure it:** automatic expansion's noise cost is real but
boundable, and is bounded by cheap non-LLM gates (cosine threshold, hub pruning, alias
disambiguation) — never reported as catastrophic, never left ungated.

## Lane B — does added context help weak models? The decisive caution

- **"Can Small Language Models Use What They Retrieve?"** (arXiv:2603.11513) — 360M-8B models
  under no-retrieval/BM25/dense/ORACLE conditions: even with the answer-bearing passage
  guaranteed present, small models fail extraction 85-100% of the time in that setting; adding
  retrieval context overturns 42-64% of previously-correct answers; robust context utilization
  "needs substantially more than 7B parameters". **This is the ceiling on option (b)**: putting
  hop-2 in the pack raises the *chance* of use, provably not the *certainty*. It also predicts a
  concrete negative-control failure mode for us: expansion passages overturning answers that
  hop-1 alone got right.
- **"Retrieval Helps or Hurts?"** (arXiv:2402.13492) — retrieval presence helps smaller models
  MORE than larger ones (gap narrows/flips for large models). Directionally favorable for (b)'s
  constituency being real.
- Aggregate distraction findings (no single canonical paper): below ~7B, oracle-but-unnecessary
  context distracts about as much as noise; attention dilution + early-position bias are the
  mechanisms. **Design consequence: the expansion block must be short, clearly labeled, and
  separated — never interleaved into the hop-1 passages.**
- **Gap stated honestly:** no source isolates our exact configuration (one attributed
  entity-neighbor hop added to an evidence pack) as a controlled variable for weak models. The
  analogy is suggestive, not proven. Our A/B would be a genuinely novel measurement.

## Lane C — result-side affordances since the last pass: still untested

- No peer-reviewed or arXiv study of "suggested follow-up query" / "next-hop hint" embedded in
  retrieval results for LLM agents, with benefit AND over-steering quantified, was found (as of
  2026-07-14). The idea exists only as practitioner convention (e.g. "next_actions" arrays in
  agent-aware-MCP blog posts, anecdotal, no over-steering measurement). Anthropic's "Writing
  effective tools for AI agents" remains the closest guidance and does not evaluate this shape.
- **Consequence: option (a) retains exactly the evidence status 725 assigned it** — hypothesis
  tier, own pre-registered A/B with over-steering negative controls, or nothing.

## Lane D — negative-control practice for expansion features

- **AbstRAG** (arXiv:2606.09459) — nearest applied precedent: an ablate-the-control negative
  control isolates over-expansion (removing their compression control moved over-expansion
  false-positives 0% → 73.7%). Transferable pattern: *ablate the expansion mechanism and measure
  the false-positive/over-trigger delta*; plus a cohort of queries where expansion should NOT
  fire.
- **Trigger-intensity A/B methodology** (arXiv:2411.03530) — define triggered vs untriggered
  cohorts around the condition (cells where expansion actually fired) rather than naive
  whole-population contrast. Adopted in the pre-registration below.
- No named standard exists; the pre-registration defines its own explicit controls.

## Net effect on the option space

Research strengthens (b) (validated pattern, known noise gates, closest analogue BridgeRAG
splits expansion/selection exactly as we would) while capping its expected benefit
(arXiv:2603.11513); leaves (a) exactly where it was (untested, hypothesis-tier); and makes (c)
mandatory-in-any-case (the utilization ceiling means even a successful (b) does not make weak
agents reliable — expectations must be set regardless).

---

# Phase 3 — DESIGN

## Feasibility evidence for option (b) against the real pipeline (file:line; verified this pass)

**What exists (the retrieval half of expansion is already production code):**

- **Entity fields, document-level**: `entity_persons_raw` / `entity_organizations_raw` /
  `entity_locations_raw` — keyword, stored, docValues, multiValued, roles filter+facet — plus
  `_text` variants (`modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json:380-426`).
- **NER lane populates them per parent document**: `NerBackfillOps.processNerBackfill` reads
  whole-document content (`modules/worker-services/.../loop/ops/NerBackfillOps.java:81`), runs
  `nerService.extractEntitiesBatch` (`:96`), and writes the entity fields onto the parent doc via
  `applyEntityFieldUpdates` (`:202-219`) / `updateDocumentsBatch` (`:118`). **No per-chunk entity
  field exists anywhere** (no write site under `modules/indexing/` chunk code; catalog has no
  chunk-scoped entity field).
- **Entities are queryable end-to-end**: `QueryFilterBuilder` term-filters on
  `ENTITY_PERSONS_RAW` at 3 sites (`modules/adapters-lucene/.../QueryFilterBuilder.java:171,242,351`);
  `KnowledgeSearchRequest.Filters.entityPersons` (`modules/app-api/.../knowledge/KnowledgeSearchRequest.java:41,56`)
  and `RetrieveContextParams.entityPersons` (`modules/app-api/.../RetrieveContextParams.java:20`)
  carry them; the worker threads them into chunk retrieval via `RagContextOps.buildRagFilters`
  (`modules/worker-services/.../RagContextOps.java:665-712`, entity threading `:703-705`) with a
  **two-stage pre-filter** that resolves doc-level entity filters to parent doc IDs Lucene-internally
  (`RagContextOps.java:255-276`, `findMatchingParentDocIds` at `:263`).
- **Both MCP tools already expose the filters**: `justsearch_answer` maps
  `filters.entity_persons/…` (`modules/ui/.../mcp/McpToolSurface.java:442-444`);
  `justsearch_search` declares them in `FILTERS_SCHEMA` (`:244-245`), requests entity facets
  (`:675-677`), and its description carries the facet→filter usage fact (`:56-57`).
- **Answer-pipeline shape**: `callAnswer` (`McpToolSurface.java:425-539`) →
  `RemoteDocumentService.retrieveContext` (`modules/app-services/.../worker/RemoteDocumentService.java:263-305`),
  which with empty `docIds` issues a pre-search gRPC call (`preSearchForDocIds`, `:311-385`) then
  the `retrieveContext` gRPC call (`:297`) — **2 gRPC round-trips per answer call today**, fully
  blocking with a 15s ceiling (`RETRIEVE_TIMEOUT_MS`, `McpToolSurface.java:46,471`). Worker-side:
  BM25/hybrid chunk search (over-retrieve `min(topK*factor,30)`, `RagContextOps.java:427-428`) →
  rerank (deadline-bounded, `:1024`) → MMR diversify → token/char budget with per-article cap
  (`:572-573`, budgets `:579-632`).
- **Noise controls + quality signals in place**: `maxChunksPerArticle`, budgets, and
  `computeQualitySignals` (bestChunkScore, scoreGap, retrievalCoverage, chunksConsidered/Included —
  `RagContextOps.java:636-659`) already projected to the agent.

**What does NOT exist (the new work):**

- No code reads entity facets off a *returned* pack to plan a second hop; `ContextCitation`
  carries no entity field (`modules/app-api/.../DocumentService.java:221-233`).
- No hop-provenance field on `ContextSection`/`ContextCitation` — "this passage arrived via an
  entity-neighbor hop" is unrepresentable today.
- No merge of two retrieval passes into one pack; each `retrieveContext` call budgets
  independently.

## Projection-vs-fork check (execution-surfaces register)

- The answer path's canonical evidence record is **ContextCitation** (sibling of SearchTrace;
  the RAG flow emits NO SearchTrace — `governance/execution-surfaces.v1.json:4` and
  `ContextResult` has no trace field, `DocumentService.java:149-159`). Expansion provenance is
  therefore an **extension of the ContextCitation/ContextResult record and of the registered
  `mcp-evidence-projection`** (`execution-surfaces.v1.json:89`, guard `McpEvidenceProjectionTest`)
  — a projection extension of the one canonical source, NOT a new representation. No SearchTrace
  referencer is created; if implementation ever reads SearchTrace it must register or fail the
  `execution-surface` gate.
- The self-describing expansion block (text half) follows the same sibling pattern as
  `mcp-search-text-degradation` (`execution-surfaces.v1.json:90`): text rendering derives from
  the same in-hand record the structuredContent projection uses.

## Design sketch for (b) — worker-side, one gRPC call, gated, attributed

**Placement: inside the worker (`RagContextOps`), not head-side orchestration.** Two reasons:
the Head-never-touches-Lucene invariant makes the worker the natural owner of a second Lucene
pass (the existing two-stage pre-filter at `RagContextOps.java:255-276` is precedent — doc-level
resolution feeding chunk retrieval is already this module's idiom); and a naive head-side loop
would cost 2 extra gRPC round-trips (4 total per answer call), while worker-side expansion is
one additional *internal* chunk search inside the existing single `retrieveContext` RPC.

**Mechanism (all LLM-free):**
1. After the primary pack is selected, read the **doc-level entity facets of the top-N hop-1
   parent docs** (stored fields, in-hand at the worker).
2. **Salience gate** (the model-shaped part, made structural): rank candidate entities by
   (i) *query novelty* — entity string does NOT appear in the query (bridge entities are by
   definition new information), (ii) frequency across hop-1 top docs, (iii) **hub pruning** —
   drop entities whose corpus doc-frequency exceeds a cap (SPRIG's mitigation), (iv) optional
   embedding cosine gate vs the query (arXiv:2606.30133's mechanism; we have dense vectors).
   Cap at k=1-2 candidate entities.
3. For each surviving candidate: entity-filtered chunk search (existing
   `RuntimeSearchFilters` path; term-filter on `entity_*_raw`, with a phrase-query fallback on
   content when the entity term misses — covers the NER-misses-synthetic-codes case, derisk U1),
   take ≤2 passages, **excluding hop-1 parent docs**.
4. **Separate expansion budget** (never competes with the primary pack's `maxContextTokens`;
   small, e.g. ≤800 chars) and a distinct, attributed, descriptive block. Candidate string
   (descriptive grammar; entity quoted as corpus text, echo-injection guard):
   `Additional passages from documents that mention "<entity>" (a name appearing in the passages above); included because name-bearing documents often hold related details:`
   — every clause is a fact about the result; no instruction. (Final wording is main-loop work
   at implementation time; the pre-registration freezes it before any cell runs.)
5. **Self-description of the expansion in structuredContent**: `expansion: {entity, basis:
   "entity-neighbor", passages: [...], docFrequency}` as a new key in
   `McpEvidenceProjection.answerEvidence` (attaches beside `citations`/`quality`,
   `McpEvidenceProjection.java:116-153`), with hop-provenance on the carried sections.
6. **Gating**: a wire-level request flag (default OFF), NOT an MCP schema parameter — so the
   `tools/list` hash is unchanged and the A/B contrasts one cohort (no
   new-measurement-cohort-by-construction); the flag is declared in the run's source-identity
   instead (honest, not silent — the W4 guard keys on surface hash, which this deliberately
   leaves fixed; the run config carries the arm identity).

**Known structural limit (stated, not hidden):** entities are document-level, so the expansion
can say "this document mentions X", not "this chunk is about X" (`NerBackfillOps.java:81`;
no chunk-scoped entity field in `fields.v1.json`). Chunk-level attribution would need an
indexing-lane change — explicitly out of scope; the doc-level form is sufficient for the
pack-hit-rate question, and the phrase-fallback search naturally lands on the mentioning chunk.

## Adjudication — ranked recommendation

1. **(c) ship now, unconditionally.** One honest paragraph in
   `docs/reference/mcp-production-server.md` (and the public benchmark docs when 719 next
   publishes): evidence packs are retrieval, not synthesis; multi-hop questions require the
   calling agent to issue follow-up retrievals; measured completion of 2-hop chains varies
   strongly with agent model tier. Free, honest, required regardless of (a)/(b) outcomes — the
   utilization ceiling (arXiv:2603.11513) guarantees no product fix makes weak agents reliable.
2. **(b) build flag-gated, ship behind pre-registered evidence.** Feasibility is
   probe-verified at the source level (the retrieval half exists end-to-end; the new work is
   bounded glue in one worker module + one projection extension); the literature validates the
   pattern and supplies the noise gates; it is the only option that changes what the result
   CONTAINS (principle P1) and the only one that converts the 9-11-cell shortcut bucket even in
   principle. Two free probes (derisk P0/P1) precede implementation; the A/B (pre-registered
   below) precedes any default-on decision. Its expected effect size is capped by the
   utilization ceiling — priced into the signal bars.
3. **(a) L4d gap-statement: do NOT build standalone.** Three reasons: (i) still
   literature-untested in both directions (research Lane C re-confirmed); (ii) intent-classified
   Tier-3 regardless of descriptive grammar (principle P2); (iii) **largely subsumed by (b)** —
   the expansion block's attributed header IS a descriptive statement of the hop-1/hop-2
   relationship, *plus* it delivers the hop-2 content, so (a)-without-(b) is strictly weaker and
   (a)-on-top-of-(b) adds only wording. Disposition: fold as an OPTIONAL third arm of the same
   pre-registered A/B if the owner funds it (marginal cost ~$14; isolates the wording effect
   from the content effect); otherwise leave in the hypothesis register.
   The affordance-present-but-unused finding (the facet-filter hint already on the surface,
   unused by the failing cohort) is additional in-house evidence against information-only fixes
   at this tier.

## Orphans

1. 725's theorization item "capability absorption … the probe belongs here" — this design is
   that probe's owner; 725's forward reference to 733 resolves here.
2. The L4d backlog entry in 725 — superseded by this adjudication (folded into the A/B as an
   optional arm or retired to the hypothesis register; not an independent work item).
3. `hop-docs.v1.json` — the briefed gold path
   (`scripts/jseval/tmp/725-ab/725-forensics/hop-docs.v1.json`, main checkout) does not exist at
   this pass's check (`F:/JustSearch/scripts/jseval/tmp/725-ab/` absent; worktree
   `scripts/jseval/tmp/` holds only `725-response-ab/`). Evidence relocation/reconstruction is
   plan increment 0 — an orchestrator ask, and if lost, the pairs are reconstructible from the
   committed 20 queries + corpus member (do not silently reconstruct; say so).
4. If implementation lands, the dead assumption "expansion requires new query machinery" in any
   future doc — the filters already exist; docs must not describe expansion as new retrieval
   capability.

## Reach judgment

The pattern generalizing here is **capability absorption for demonstrated model-level failure
classes** (principle P1), with the T-A/T-B tool-independence comparison as the demonstration
template. Candidate scope beyond this instance (recorded, not built): the agent-api HTTP
retrieval endpoints (same pack, same absorption question) and any future `justsearch_answer`
synthesis mode (full absorption — owner/roadmap territory, re-introduces inference dependency).
The expansion self-description extends the 725-adopted **self-describing results** principle to
a new result *section* — same retirement condition (protocol-level provenance metadata, if MCP
ever standardizes it).

---

# Phase 4 — DERISK

## Uncertainties (static-retirable vs live asks; probes are FREE under orchestrator lease)

| # | Uncertainty | Kind | Resolution |
|---|---|---|---|
| U1 | Does the NER lane tag the planted synthetic bridge entities (`Cavby8` etc.) into `entity_persons_raw` on the CLERC member? If not, facet-driven expansion can't be probed on this corpus as-is. | **Live ask** (lease): ingest state inspection — facet query / stored-field read for the 6 hop-1 docs. | Go/no-go for the facet path of P1; the phrase-fallback path (step 3 of the sketch) is probe-able regardless, and is part of the design precisely for this case. |
| U2 | Gold file (`hop-docs.v1.json`) missing at the briefed path — probe needs the 6 hop-doc pairs. | **Static + ask**: orchestrator locates relocated evidence; else reconstruct from committed queries + corpus (declared, not silent). | Plan increment 0. |
| U3 | Salience-gate sufficiency: is the bridge entity top-k among candidates under the query-novelty + frequency + hub-pruning ranking, on hop-1 packs? | **Live probe P1b** (free): compute candidates from hop-1 docs' entity facets (or phrase scan), check bridge rank. Static design review can't answer this. | Primary probe question #2. |
| U4 | Expansion pack hit-rate ceiling: with an ORACLE bridge entity, does entity-filtered (or phrase-fallback) retrieval put the hop-2 doc into the pack top-k on the 6 known pairs? | **Live probe P1a** (free): existing surface only — `retrieveContext(question)` → gold entity → filtered second call → check hop-2 doc presence. No code change needed. | Primary probe question #1; this is the design's upper bound. |
| U5 | Latency: worker-side design bounds added cost to ~1 internal chunk search (+optional rerank) inside the existing RPC; the naive head-side alternative doubles round-trips (4 gRPC, 2×15s ceilings). | **Static: RETIRED** by the placement decision (evidence: `RemoteDocumentService.java:263-305` round-trip structure; `RagContextOps` internal-search precedent `:255-276`). Live timing measured incidentally in P1. | Design already reflects it. |
| U6 | Over-expansion on already-noisy legal packs (bestChunkScore ~0.03 — 731's territory): expansion could add a *wrong* neighbor doc and overturn correct answers. | **Partially static** (separate budget, ≤2 passages, hub pruning, quality-block reporting), **finally only the A/B** (negative-control cohort + overturn metric). Declared dependency on 731; not solved here. | Pre-registration controls (iii)/(iv). |
| U7 | Weak-agent utilization: even a perfect pack may be ignored (arXiv:2603.11513). | **Only the A/B answers.** This is the residual that caps benefit-confidence; the probes measure pack content, not agent behavior. | Signal bars priced accordingly. |
| U8 | A/B instrument: tool RESULT content is not captured in Inspect logs (725 inventory issue 9, 729's territory) — "did the agent read the expansion block" is unmeasurable; only behavior (answer contains hop-2 value; tool-call args) is. Single-seed churn (issue 15) invalidates accuracy deltas at n=20×1. | **Static: RETIRED into the pre-registration** — behavioral primary metric + seeds ≥3; capture-gap noted as a 729 dependency, not blocking. | Pre-registration metrics section. |

## Confidence (0-10) and staffing

- **Implementability of (b): 8/10.** The retrieval half is verified production code end-to-end
  (file:line above); the new work is bounded (one worker module, one record extension, one
  projection extension, one flag). Held back from 9+ by the salience gate being
  judgment-quality heuristic work and by U1's corpus-specific unknown.
- **Benefit of (b): 5/10.** The utilization ceiling (U7) is a documented, quantified external
  caution; the probes can only establish the ceiling (pack contains hop-2), not the conversion
  (agent uses it). Honest statement: (b) is worth building *flag-gated* because it is cheap
  relative to its option value and evidence-gated before any default; it is not a promised fix.
- **Option (c): 10/10** — a docs paragraph; no uncertainty.
- **Staffing** (725 conventions): probes P0/P1 are main-loop under orchestrator stack lease
  (they are evidence judgment, not delegable per the routing rules). Implementation increments:
  sonnet (medium-high effort) workers on the bounded chunks below, briefs main-loop; one opus
  (high effort) refute-first reviewer before any commit. Pre-registration wording, the salience
  heuristic's final form, and all agent-visible strings stay main-loop (descriptive-grammar
  review is judgment).

---

# Phase 5 — PLAN

## Increments (in order; each independently verifiable; STOP-gates marked)

- **0. Evidence relocation ask (orchestrator).** Locate the relocated 725 forensics evidence
  (`hop-docs.v1.json` + `725-forensics-T/`); if lost, reconstruct the 6 hop-doc pairs from the
  committed queries + corpus member and record the reconstruction as such. *Verification: the 6
  (question, hop-1 doc, bridge entity, hop-2 doc, gold value) tuples exist in a committed note.*
- **1. Probe P0 (free; lease).** Entity-facet ground truth on the ingested CLERC member: are the
  planted bridge entities present in `entity_persons_raw` (U1)? Also capture 2-3 real (non-planted)
  legal docs' facet profiles for hub-pruning calibration. *Verification: a small JSON evidence
  file under the 725 evidence convention; U1 marked resolved either way.*
- **2. Probe P1 (free; lease; no code change).** Through the agents' own path (`POST /mcp`):
  (a) oracle-expansion ceiling — for each of the 6 pairs, hop-1 `retrieveContext(question)`,
  then a second call filtered by the gold bridge entity (facet filter if U1-yes, phrase query
  fallback if U1-no): is the hop-2 doc in the pack? (b) salience simulation — rank candidate
  entities from hop-1 results under the P1 gate (query-novelty + frequency + hub cap): is the
  bridge top-2? *Verification: 6×2 result table committed as evidence.*
  **STOP-GATE: if P1a hit-rate <4/6 or P1b top-2 rate <4/6, report to orchestrator before any
  implementation — the design's mechanism is refuted or needs rework (interrogate the misses
  first; they may be U1- or 731-shaped rather than design-shaped).**
- **3. Option (c) docs increment (ship-ready regardless of gates).** One descriptive paragraph
  in `docs/reference/mcp-production-server.md` (evidence-pack nature; multi-hop questions need
  follow-up retrieval by the calling agent; completion varies with agent model tier per measured
  campaigns). Rides along with the next 725-program PR (docs-ride-along rule). Ties to 624
  Step-2: the docs claim cites the T-A/T-B evidence and names the model-tier sweep as the
  measurement that would sharpen it.
- **4. Implementation of (b) (gated on increment 2 + orchestrator plan approval).** Bounded
  chunks, each a sonnet worker brief with file:line anchors from Phase 3:
  - 4a. Worker: expansion step in `RagContextOps` (salience gate + entity/phrase second search +
    separate budget + hop-1-doc exclusion), behind the wire flag; unit tests incl. the noisy-span
    and hub cases as fixtures.
  - 4b. Records: hop-provenance on `ContextSection`/pack carry; `ContextResult.expansion`;
    schema regen for app-api records (`updateSchemas`).
  - 4c. Head/MCP: flag plumbing; the attributed descriptive block (final wording main-loop);
    `McpEvidenceProjection.answerEvidence` extension + `McpEvidenceProjectionTest` coverage
    (register guard); shape tests (MCP text shapes arrive with tests — 725 U5 lesson).
  - 4d. Measurement: run-config/source-identity declaration of the expansion flag (arm identity
    without surface-hash change); jseval-side check that the contrast tooling reads it.
  - Opus refute-first review before commit; full suites + execution-surface gate green;
    live-validate both flag states on the lease.
- **5. Response-expansion A/B (owner-gated; pre-registration below).** No cell runs before
  owner authorization and bar-setting.
- **6. Close-out.** Update 725's inventory row for issue 1 with the outcome; fold observations;
  register updates per the skills rule if search-quality surfaces were touched (4a is
  retrieval-adjacent — load `/search-quality` before 4a and update its register at close).

## Subagent split summary

Main-loop: briefs, probes, pre-registration, wording, evidence judgment, commits, lease.
Sonnet workers: 4a-4d (bounded, verifiable, file:line-anchored briefs). Opus: one refute-first
review. No delegation of: git, dev-stack lifecycle, the salience heuristic's final judgment.

## Pre-registration draft — expansion A/B (frozen before any cell runs; run is owner-gated)

- **Arms:** control = surface 0.3.x lineage, expansion flag OFF; treatment = identical build,
  expansion flag ON (default `detailed` shapes both arms). Optional arm 3 (owner-funded only):
  expansion OFF + L4d gap-statement line ON — isolates wording-effect from content-effect.
  Same corpus (`mixed/en-legal-clerc-1k-verbose`), same 20 committed queries, B-condition,
  same limits as Campaign T. `tools/list` hash identical across arms by construction (wire
  flag, not schema); arm identity declared in run config + source-identity sidecar (the W4
  surface-guard is deliberately not the discriminator here; the declared flag is).
- **Cohorts:** haiku (primary); + sonnet if the owner funds dual-cohort (model-agnostic axiom:
  single-cohort evidence cannot support any Tier-3 conclusion, and weakens Tier-1/2 ones).
- **Seeds:** ≥3 per arm (725 issue-15 lesson: single-seed accuracy deltas on this corpus are
  churn-dominated; 5/20 cells flipped between identical-intent campaigns).
- **Primary metric:** hop-2 completion rate — fraction of completed cells whose final answer
  contains the gold hop-2 value (substring-EM against gold), per-cell forensically classified
  (hop-1-stop / hop-2-executed-by-agent / hop-2-present-in-pack) from tool-call args + answers
  (result-content capture is absent — 729 dependency, declared).
- **Secondary:** completed-cell accuracy, tokens/cell (cost + usage counters), median turns,
  Reads-per-search, funnel (discovery/invocation/reinforced), pack sizes, expansion-fired rate.
- **Negative controls:** (i) **spurious-expansion cohort** — 10 single-hop queries (answerable
  from one doc) per arm: expansion-fired rate on these + accuracy invariance (AbstRAG-pattern:
  the expansion mechanism must not fire, or firing must not move answers); (ii) **overturn
  check** — queries the control arm answers correctly must not flip incorrect under treatment
  beyond seed churn (the 42-64% overturn risk, arXiv:2603.11513); (iii) **over-steering** — MCP
  call share and entity-filter usage must not balloon without accuracy movement; (iv) A-condition
  arm (no tools) invariance across arms (nothing about the flag can affect A; any drift flags a
  substrate defect).
- **Trigger-intensity analysis** (arXiv:2411.03530 pattern): primary contrast computed on the
  triggered cohort (cells where expansion actually fired) beside the intent-to-treat contrast.
- **Budget** (from the measured ~$0.22-0.25/cell): core = 2 arms × 20 queries × 3 seeds =
  120 B-cells ≈ **$26-30**; + negative-control cohort 2 × 10 × 3 = 60 cells ≈ **$13-15**;
  + one A-arm campaign (20 × 3 ≈ $25-31, or reuse Campaign T's A-arm descriptively if the owner
  declines). Hard cap **USD 50** (haiku, incl. controls); optional arm 3 +~$14; sonnet cohort
  ~2× on top — each separately owner-authorized. **Stop conditions:** cap reached (abort, report
  partial as descriptive-only); any substrate capture defect (fix-and-rerun, per 725 precedent);
  probe stop-gate (increment 2) failed — the A/B is not run at all.
- **Signal bars** (interpretation guide; exact thresholds set WITH the owner at authorization,
  before any result is seen): expansion earns default-ON if hop-2 completion improves materially
  with (a) no spurious-expansion regression, (b) no overturn beyond churn, (c) token cost
  bounded (this is a capability lever, not an economics lever — bounded token increase is
  acceptable against real accuracy gain, unlike the 725 preview case). A null on hop-2
  completion with pack-hit-rate confirmed high routes the finding to 624 Step-2 (utilization is
  then proven model-bound, sharpening (c)'s docs claim and the model-tier sweep's motivation) —
  a null is informative, not wasted spend.

## Ties to 624 Step-2 (explicit)

Whatever (b)'s A/B shows, the model-tier sweep remains the sharpest discriminator for the
*residual*: if expansion lifts hop-2 completion partially, Step-2 measures how much of the rest
is tier-bound; if it lifts nothing despite pack-hits, Step-2 becomes the ONLY remaining lever
and (c)'s expectations text becomes the product's honest position. Either way this area's
output feeds Step-2's interpretation, and both stay owner-gated on spend.
