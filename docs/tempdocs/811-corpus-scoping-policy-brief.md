---
title: "811 — Corpus scoping & internal-documents policy (T-C): decision brief"
status: "investigation complete 2026-08-06; OWNER DECISIONS PENDING (§Decisions); two independent defects routed to the correctness lane regardless of policy"
created: 2026-08-06
updated: 2026-08-06
related: [809, 810, 585, 629, 553]
---

# 811 — Corpus scoping & internal-documents policy (T-C)

Thread T-C of the human-validation campaign (finding 5 in tempdoc 809; charter in 810).
Ground truth below was gathered by a read-only source investigation on 2026-08-06 against
main @ 3d3ee489; every claim carries a citation. This brief exists so the owner can decide
policy from facts rather than from the finding's surface reading — which the investigation
partly corrects.

## What the investigation established (corrections to finding 5's surface reading)

1. **Help docs are deliberate and already carry a marker.** `KnowledgeServerBootstrap.tryIngestHelpFiles`
   (`app-services .../worker/KnowledgeServerBootstrap.java:570-624`) ingests
   `SSOT/docs/help/*.md` under `collection="justsearch-help"`, marker-guarded, skipped only in
   eval mode. Deliberate per `docs/reference/issues/decisions.md:49` (UIX-015: "not excluded
   from search"). The collection value reaches the FE inside `Hit.fields`.
   **The missing label is a shipped-doc lie, not a missing decision**:
   `docs/reference/search-ui-behavior.md:257,647` documents a teal "Help" pill on
   `collection === 'justsearch-help'` results — **no such code exists** (grep clean in
   `modules/ui-web/src`). The canonical doc describes an unimplemented feature.
2. **Out-of-root MCP ingest is unconsidered, not designed.** `justsearch_ingest` →
   `KnowledgeSearchController.handleIngest` (`:685-750`) indexes with **collection literal
   `null`** (`:722` scanRoot, `:732` single-file), performs no containment check against
   watched roots, never registers a root, and no prune path can ever reach the documents
   (`PruneOps.pruneByPathPrefix` is watched-root-prefix-driven). Null collection also means
   these docs **pass every collection clause**, including the agent-history `MUST_NOT`.
   No ADR/tempdoc sanctions this behavior.
3. **Agent-history indexing is a deliberate feature with a deliberate default-exclusion —
   and two live bypass defects.** `AgentHistoryIndexer` (tempdoc 585 §D4a) indexes
   transcripts under `collection="agent-history"`; `QueryFilterBuilder.addCollectionScope`
   (`adapters-lucene .../QueryFilterBuilder.java:96-108`) default-excludes them (§D4b).
   But **both filter builders return before the exclusion when `filters == null`**
   (`:155-157`, `:212-221`) — any null-filters call site searches agent history. This is the
   confirmed candidate mechanism for finding 4's "unreproduced" agent-history hit.
   Separately, `RagContextOps.withIncludeChunks` (`:1612-1631`) rebuilds the filter record
   **omitting `.collection()` and `.docIds()`**, silently dropping an explicit scope on the
   RAG path (re-opens tempdoc 629 §Open issue #1's class).
4. **Collection-as-a-filter is NOT missing plumbing.** `collection` is declared
   `roles: [filter, facet]` (`SSOT/catalogs/fields.v1.json:124-131`), parsed
   (`KnowledgeSearchController.parseFilters:546,563`) and applied (`QueryFilterBuilder:168,239`).
   The gaps are: the FE only emits a binary documents/agent-history toggle
   (`searchState.ts:358-362`); no facet is ever requested; the two null-collection ingest
   sites above; and the two bypass defects. Finding 4's "collection is not a member of the
   search filter set" was wrong at the schema level and right at the product level.
5. **The doc count is index-wide minus chunks, nothing else.** "Searching N documents"
   (`UnifiedChatView.ts:2125`, also `:2857`, `HealthSurface.ts:894`) ←
   `IndexStatusOps.buildStatusResponse` (`worker-services .../IndexStatusOps.java:244-251`):
   `docCount = totalDocs - chunkDocs`. It counts help docs, agent-history, and out-of-root
   ingests — including documents the default search scope *hides*. No corpus-enumeration
   surface exists anywhere.
6. **Harness dependency confirmed**: `gen_golden_parity.check_help_docs_indexed`
   (`gen_golden_parity.py:136-169,249-251`) fails closed unless help docs are searchable;
   four unit tests cover it. Also dependent: `KnowledgeServerBootstrapEvalModeTest`,
   three `QueryFilterBuilderTest` cases, UIX-015's empty-state rationale.

## Routed to the correctness lane NOW (policy-independent defects)

These are defects under every policy option and are queued as item #6 of the 810 Lane-1
sequence — they do not wait for the decisions below:

- **D-1**: close the `filters == null` early-returns so `addCollectionScope` always applies
  (`QueryFilterBuilder.java:155-157, 212-221`), with a regression test per bypass path.
- **D-2**: `RagContextOps.withIncludeChunks` copies `collection` and `docIds` when
  rebuilding the filter record (`:1612-1631`), with a test that an explicit agent-history
  scope survives the RAG path.

## Decisions (owner)

**Decision C-1 — internal help docs: label or exclude?**
- **(a) RECOMMENDED: implement the already-documented label.** Render the Help pill
  `search-ui-behavior.md` promises, add a scope affordance (the plumbing exists), keep them
  searchable. Preserves UIX-015's intent and the parity harness unchanged; the canonical doc
  becomes true instead of being rewritten. Cost: FE-only.
- (b) Default-exclude like agent-history. Breaks the parity generator fail-closed
  (`gen_golden_parity.py:249-251`) + 4 tests + UIX-015's first-run rationale; the harness
  would need a new comparability anchor. Only worth it if the owner considers internal docs
  in results categorically wrong.

**Decision C-2 — out-of-root (MCP) ingest: tag, or reject?**
- **(a) RECOMMENDED: tag and surface.** Assign a real collection at the two null sites
  (default e.g. `mcp-ingest`; optionally a `collection` param on the MCP tool), list
  non-root sources in a Library "Other sources" section, and define a removal route
  (prune-by-collection or per-source forget). Rejecting instead would break the MCP product
  story (agents ingesting arbitrary paths is the point) and five existing tests.
- (b) Containment-check and reject out-of-root paths. Safer-looking, but it removes a
  shipped capability and still leaves already-indexed orphans unaddressable.
- Either way: **already-indexed null-collection documents need a one-time disposition**
  (backfill a collection value, or document them as pre-policy residue) — silence here
  repeats the data-orphan pattern 798 D3.4 warned about.

**Decision C-3 — agent transcripts in the corpus: confirm the 585 design or revisit?**
The feature is deliberate (indexed, default-excluded, opt-in scope). With D-1/D-2 fixed, the
observed leak class closes. The residual owner question is narrower than finding 5 framed
it: *is default-excluded-but-indexed the right posture for AI transcripts*, given they
appear in the doc count and in any explicit all-collections scope? Options: keep (recommended
— it is a designed, useful feature once the bypasses are fixed), or make indexing itself
opt-in. No code change proposed until answered.

**Decision C-4 — count truthfulness.** "Searching N documents" should describe the
population the default scope actually searches (exclude default-excluded collections), or
be broken down ("N yours · M app docs"). Recommended: count the default-scope population,
with the breakdown available on the Health/Library surface once C-1/C-2 labels exist. This
is the fix for finding 5's sharpest user-facing consequence and slots into the T-A progress
design's vocabulary.

## After the decisions

Implementation slices (each delegable once its decision lands): FE label + scope affordance
(C-1a), ingest tagging + Other-sources listing + removal route (C-2a), count re-derivation
(C-4), plus the canonical-doc reconciliation (`search-ui-behavior.md` pill section — becomes
true under C-1a, must be rewritten under C-1b). A corpus-enumeration surface (browse by
collection, backed by the declared-but-unused `facet` role) is the natural follow-on and
should be designed with T-A's Library work, not bolted on here.
