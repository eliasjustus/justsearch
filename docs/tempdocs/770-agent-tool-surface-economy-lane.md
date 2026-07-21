---
title: "agent tool-surface economy lane: remove the search-payload bytes that carry no content (16–31%, corpus-dependent), make the tool description true, and characterize the client truncation cliff — economy as measured waste removal, not as a new result shape"
type: tempdocs
status: "IMPLEMENTED on branch (2026-07-21), NOT merged — awaiting owner. Parts 1/2/orphan-sweep/version-bump landed + live-verified; the truncation cap is CHARACTERIZED (threshold 46.6k–52.8k chars, fixed 2,322-char notice, neither tier delivered) and the change is measured to move `limit:30` verbose queries from cliffed to delivered. Redesigned twice before implementation: a zero-spend measurement over 1,078 recovered v5 payloads invalidated premises of BOTH prior designs. `fetch` withdrawn; §A.2 routed to the engine lane; governor design still open."
created: 2026-07-21
updated: 2026-07-21
author: agent (Fable orchestration), 766 program charter; redesigned after takeover verification + offline measurement
category: agent-tool-surface / mcp
related:
  - 766-eval-content-rebuild-program   # umbrella — READ FIRST
  - 765-agent-economics-lane           # §E Results — see §A corrections
  - 763-retrieval-attribution-lane     # §F Results: B3-search evidence
  - 735-agent-surface-seam-consolidation  # the seam; owns the open governor item
  - 732-response-surface-residuals     # decided the concise-default question — see §A.6 caveat
  - 725-agent-tool-adoption-legibility # shipped response_format; first delivery-tier measurement
  - 658-retrieval-inspectability-and-diagnostic-bundle  # the projection kernel this conforms to
---

> Charter. Independent of 767/768/769; can run in parallel.

# 770 — agent tool-surface economy lane

## §0. Redesign notice — read this before anything else

This tempdoc has been redesigned twice on 2026-07-21. Both prior designs are in
git history. The reason both failed is one structural fact neither knew:

> **The Claude Code CLI delivers `structuredContent` only. When it is present,
> the entire text block is never sent to the model.**
> Measured: 1056 of 1078 v5 search deliveries were `structured-json`.

Everything in the text tier — the `[rank] title (score)` lines, the `Preview:`
line, the facet block, the coverage/rationale/degradation headers, and
`TRUNCATION_REMEDY` — is built server-side and discarded by the transport. It
contributes **0%** of the bytes the agent actually receives.

That single fact falsifies, in order:

1. **Design 1's snippet/salience program.** Reshaping the `Preview:` line, widening
   its window, or surfacing `id` in the text block changes nothing the model sees.
2. **Design 1's fetch-affordance argument.** `TRUNCATION_REMEDY` appears in
   **0 of 1078** payloads. The "the surface tells the agent to read off disk" nudge
   was never delivered. The hypothesis is dead, not weakened.
3. **Design 2's Part 1 (mine).** It proposed "keep the Preview, drop the
   `excerpts`". Exactly backwards: the Preview is undelivered, and `excerpts` is
   the **only document text the model ever receives**. Gating it would have
   removed all evidence content from the default response.
4. **The premise that `response_format` is a working economy dial.** 336 search
   calls already requested `concise` and received **no byte reduction** (medians
   identical once `limit` is controlled). It only omits the undelivered Preview
   line. The dial is inert.

What survives is smaller, duller, and actually measured. That is §E.

## §A. Evidence — as corrected by verification + measurement

### A.1 Payload weight — CONFIRMED and re-measured

Independent offline measurement (N=1081 search calls, 1078 payloads recovered
and **SHA256-verified against the campaign digest index**, 99.7% recovery, no
size bias):

| | median | p95 | max |
|---|---|---|---|
| **Measured (N=1081)** | **15,929** | **35,636** | **49,969** |
| 765 §E as cited | ~13,000 | (see below) | (see below) |

765's median is reproduced within ~22%. `justsearch_answer` is ~1/4 the weight
(median 3,536, N=547).

- **STRUCK from design 1 — was unsourced, now independently measured.** *"p95 34k,
  max 50k"* appeared nowhere in the repo when design 1 cited it. It is now
  *coincidentally* close to measurement (35,636 / 49,969) — but it was invented at
  the time of writing, and the correct citation is this lane's measurement, not
  765. Cite the measured figures.
- **UNVERIFIED — do not cite publicly.** 765's *"~15–20k tokens/cell"* remains
  methodless and arithmetically strained against a char median. Unusable until
  re-derived.
- **STALE — tombstone (§F.8).** `366:158-163` (*6,458 chars / ~1,749 tokens*) is
  ~2026-03 and ~2.5× low; it has propagated into `401:89` and `500:282`.

### A.2 Read-amplification — ROUTED OUT OF THIS LANE

Numbers confirmed verbatim (`mcp_call_share` ≈ 0.5; 157 full-file Reads at
legal-10k; max 92k chars). The *causal* question was tested directly and neither
tool-surface explanation survived:

| Hypothesis | Result |
|---|---|
| Surface nudge (`TRUNCATION_REMEDY` sends the agent to disk) | **Falsified.** 0 of 1078 payloads contained it — N=0 exposures |
| Snippet deficit (excerpt lacked the answer span) | **Weakly supported, not dominant.** Where testable (doc returned *with* an excerpt, N=196) the excerpt lacked gold 93.9% of the time — but the counterfactual undercuts it: the Read itself recovered gold only **5.8%** of the time (N=862), barely above the 4.4% the excerpt already supplied |
| **Retrieval/coverage deficit (unnamed by either design)** | **Favored.** **50.3%** of post-search Reads (N=862) targeted documents search **never returned**. Condition-B accuracy was low (12/60 legal-1k, 25/60 enron-1k) — most Reads are unproductive search-failure flailing |

**Consequence:** §A.2 indicts the engine, not the surface. Per §C's own routing
rule, it leaves this lane for the search-quality register / relevance work (769).
This removes roughly a third of the lane's original charter, deliberately.

*(Residual surface signal, kept for §F.1: the payload hands over absolute
filesystem paths twice per hit — `path` plus a duplicate `id` — and 44.7% of
post-search Reads target a path from the immediately preceding search.)*

### A.3 Returned-unopened — out of scope for response-shape work

11 cells with gold at rank 1–7 never opened; 28 more with gold already in the
answer pack (`763:125-129`). Counts confirmed, census not sample. Two reasons this
is not this lane's target:

- **Provenance:** payloads were sha256-redacted pre-persistence (`763:134-138`),
  so "rank 1–7" is a **reconstruction** — queries replayed against the same pinned
  index at a *later code revision* through **`/search`, not the MCP tool**
  (`763:62-67, 144-148`). "Never opened" is solid; the rank is not observed.
- **Class already closed:** *"Facet/hint affordances produced zero behavioral
  adoption at haiku (third consistent refutation across campaigns) — response-shape
  work is henceforth justified only by correctness and economics; the accuracy
  frontier belongs to hop absorption / model tier"* (`735:472-474`).

### A.4 F-016 / F-017 are weak priors, not design gates

- **F-016** (*"schema complexity −21pp"*): the bloated arm is **n=14, a partial
  aborted run** vs an n=50 baseline from another day (`366:471-474`); ~3 queries
  move it 21pp against a ~6–8pp acknowledged noise floor *at n=50*. Confounded with
  6a highlighting, 6d confidence calibration (incl. a sufficiency-prompt polarity
  flip), NER/facets. **366's own summary table records that row as n=50 / 86% /
  −6pp** with the verdict *"Phase 6 causes no regression"* (`366:2089`, `:533-536`).
  The register promoted a discarded intermediate. Never replicated. Haiku-only,
  Condition "C".
- **F-017** (*"consolidation +20pp"*): real (n=50/arm) but **sequential and
  non-randomized**, changing four things at once — count, description rewrites,
  answer-first position-bias ordering (~9.51% alone per ToolTweak), typed output
  schemas. It **cannot attribute the effect to count in either direction**.
- **Uncounted drift:** F-017's win was 7→4 tools. The live surface is **6**
  (`McpProtocolHandlerTest.java:213-218`); the 4→6 drift was never re-measured.
  Design 1 proposed landing at **7** without noting it. This design stays at 6.

### A.5 The surface makes false statements to the agent — CONFIRMED, and this channel *is* delivered

Unlike response text, **tool descriptions are delivered** (via `tools/list`). Three
statements on `justsearch_search` are untrue today — see §E.2.

### A.6 OPEN — 732's decision may rest on an undelivered component

732 closed the search concise-default question on the grounds that *"the Preview
line is the mechanism behind the one demonstrated behavioral win"* (Reads-per-search
1.038 → 0.583), calling it *"a design-consistency argument… no measurement changes
it"* (`732:126-136`). But the Preview line is **not delivered** to structured-preferring
clients, and delivery-tier capture only began at campaign **V** (`725:1714-1715`,
98.9% structured-json) — *after* the D→T→U window in which the halving was measured.
The halving is real; its attribution is not established and plausibly belongs to the
`structuredContent` excerpts that shipped alongside.

**This design does not depend on the answer** (it changes neither the Preview nor
the default `concise` semantics), so it is recorded, not resolved. Logged to the
observations inbox. It is 732's to settle.

## §B. Direction (constraints)

- Shape at the projection seam (`McpEvidenceProjection`); do not fork a second
  result-shaping authority (F-037 / 658).
- Justify by **correctness and economics only** — the accuracy/behavioral frontier
  is closed for response-shape work (`735:472-474`).
- No adoption scaffolding for first use; first-search-at-turn-1 is already universal.
  The no-scaffolding rule governs **adoption**, not **truthfulness of the
  description** (§E.2).

## §C. Acceptance

- **Economy:** median + p95 delivered payload per `justsearch_search` call, before
  vs after, measured by the §D harness — **zero LLM spend**. Expected ≈31% median
  reduction; report the measured number.
- **Content loss — RECONCILED WITH WHAT SHIPPED (2026-07-21).** The original wording
  ("zero content loss … no retrieval fact leaves the default response") was written
  for §E.1 alone and was **not true of the lane as landed**. Accurate statement:
  - From `justsearch_search`'s default response, only (a) per-hit numeric provenance
    (`trace`/`legScores`, recoverable via `detail: true`) and (b) a verbatim duplicate
    identity field (`id` when it equals `path`) are removed. That half *is* zero
    content loss, and is asserted by test.
  - From `justsearch_answer`, §F.5 additionally removed the `facets` field from
    `structuredContent` — a **delivered** field with no other source, so it is now
    unreachable — and the facet block from the text tier. This is a deliberate
    deletion justified by measured non-use (`735:471-474`), not by reachability. See
    §F.5's rationale correction, which this bullet previously contradicted.
- **Reachability (hard, by construction):** retrieval, ranking, count and `limit`
  semantics are untouched. No document that is returned today stops being returned.
- **Tier equivalence preserved** (`McpTierEquivalenceTest`). The four byte-exact
  text-tier goldens are unchanged, but the claim "this design does not modify text
  rendering" is **false as shipped**: §F.5's removal deleted the answer path's facet
  text block as well as its structured field. Goldens unaffected ≠ rendering
  untouched.
- Findings that indict the engine route to the search-quality register (§A.2 already has).

## §D. Measurement harness (built, zero-spend, reusable)

Offline decomposition over banked v5 transcripts. Raw payloads are **not** in the
campaign logs (digest-only by design, `agent_utility_inspect.py:766-800`); they were
recovered from CLI session transcripts and **SHA256-verified against the campaign
digest index**, so every decomposed payload is provably a v5 payload.

Component shares of the delivered payload (N=1056 structured deliveries):

| Component | median share | note |
|---|---|---|
| text tier (rank lines, `Preview:`, facets, headers) | **0%** | built, discarded by transport |
| per-hit `trace` + `legScores` | **19.9%** | numeric provenance |
| `excerpts` | 40.2% | **the only document text delivered — not waste** |
| `hit.id` | 11.5% | **verbatim duplicate of `hit.path` in 14,617/14,617 hits** |
| `hit.path` | 11.1% | |
| top-level `searchTrace` | 5.3% | |
| remainder | ~12% | |

`results[]` is 91.1% of aggregate bytes. Median 10 hits/call (mean 15, max 50).

**Re-run this harness as the before/after gate.** It requires no dev stack, no
model, and no spend.

## §E. Design

**Net tool surface: 6 tools — unchanged. No new tool.** Every change is either
removing bytes that carry no content, or making an existing statement true. *(Amended
2026-07-21: "no new parameter, no schema change" no longer holds — §E.2's `querySyntax`
row is resolved by **declaring** the parameter rather than deleting the sentence that
advertised it, because the underlying engine capability is real. One optional schema
property added; no tool added, no required shape changed.)*

### 1. Remove the ~31% that carries no content (default response)

Two removals from the default `structuredContent`, both content-free:

- **Per-hit `trace` + `legScores` (19.9%)** — numeric ranking provenance. Recoverable
  via the existing `detail` parameter, which is what `detail` already means
  (`McpToolSurface.java:289-290`). Note `detail` was passed in **0 of 1081** calls, so
  nothing in the measured cohort loses anything.
- **`hit.id` (11.5%)** — byte-for-byte identical to `hit.path` in **all 14,617 hits
  measured**. Emit one field. This is a duplicate, not a tier decision.

  **Which one survives — corrected 2026-07-21 (the implementation initially kept the
  wrong one).** Keep **`path`**; emit `id` only when it differs from `path`, or when
  there is no path. The byte saving is identical either way, so the tie-break is
  affordance: this lane's own §A.2 measurement is that **44.7% of post-search `Read`s
  target a path from the preceding search**, and nothing in the *delivered* channel
  tells a model that an opaque `id` happens to be a filesystem path it can open.
  Non-filesystem sources are unaffected — `id` still ships whenever it is not the
  path.

**This must be a default change.** The opt-in path is proven inert: 336 calls
requested `concise` and received no reduction. Changing only an opt-in mode moves
nothing.

**Explicitly NOT removed:** `excerpts`. It is the sole document text the model
receives; design 2 proposed gating it and was wrong.

**Contract handling.** The MCP curated tools are public-contract tier, and removing a
default field is a removal under the stability policy — permitted by the pre-1.0
clause, but deliberately: `TOOL_SURFACE_VERSION` minor bump + a changelog entry +
`docs/reference/mcp-production-server.md` §"Structured retrieval evidence" updated to
the new default field set. The bump changes the `tools/list` hash, which is
**measurement-cohort identity** — so it must be sequenced against the 766 campaign
program, not landed blind (§H).

**Guard shape.** `McpEvidenceProjectionTest`'s reflective totality guard must assert
every canonical field is projected in **some declared tier**, with the default tier a
declared subset — not be left asserting a test-only overload that no longer describes
what ships (§G).

### 2. Make the description true

Tool descriptions **are** delivered. Three statements are false today:

| Statement | Reality | Cost |
|---|---|---|
| *"set `querySyntax: \"LUCENE\"` with `mode: \"text\"`"* (`SEARCH_DESC:68`) | not in `SEARCH_SCHEMA`; validator sets no `additionalProperties: false`, so it is **silently ignored** | agent believes it enabled exact-phrase search, silently gets fuzzy hybrid, concludes the tool is unreliable |
| *"The first search returns top facet values"* (`SEARCH_DESC:71`) | emitted every call (`renderSearchText:926-941`) | claim is false; the block is also undelivered, so it is pure server-side waste |
| *"`concise` returns substantially fewer tokens per call"* (`RESPONSE_FORMAT_SCHEMA:266`) | omits only the undelivered Preview line — **measured zero reduction** across 336 opt-ins | agents opt into a promise that does not hold |

Fix (as landed, after the 2026-07-21 correction):

- **`querySyntax`** — *not* deleted. Deleting the sentence made the description true
  by removing a capability the engine actually has, which is the wrong direction for
  §G's principle. Declared as an optional `SEARCH_SCHEMA` property (`simple` /
  `lucene` / `advanced`, mirroring `parseQuerySyntaxOrDefault`) and threaded through
  `callSearch` into `KnowledgeSearchRequest.querySyntax`; an accurate sentence is
  restored to `SEARCH_DESC`.
- **Facets** — corrected, but conditionally, not to "every search": `renderSearchText`
  guards on a non-empty facet map, so a zero-hit query or a corpus without those
  fields returns none. The replacement sentence says "when the matching documents
  carry them", which is the checkable claim.
- **`concise`** — corrected to state what it actually does for `search`. If 732
  reopens §A.6, let 732 decide whether `concise` should gate structured content
  instead.

### 3. Response-size governor — CAP CHARACTERIZED (2026-07-21), design still open

**The probe ran. 735's three unknowns (`735:501-504`) are now measured**, live through
the real Claude Code CLI via `experiments/delivery_tier_probe_735.py --mode sdk`
(extended this lane with opt-in `--search-limit` / `--search-query` overrides, both
refusing `--write-fixtures` so a size-swept capture can never be mistaken for the
canonical recorded fixture). Corpus: this repo's `docs/tempdocs` (524 docs, verbose
markdown) on a live dev stack.

| delivered payload | `delivered_tier` | `delivered_fields` |
|---|---|---|
| 42,980 chars | `structured-json` | full |
| 46,617 chars | `structured-json` | full |
| **52,825 chars** | **`prose`, 2,322 chars** | **`null`** |
| **61,634 chars** | **`prose`, 2,322 chars** | **`null`** |

- **Threshold: between 46,617 and 52,825 chars** of serialized `structuredContent`.
  A round **50,000** sits in the middle and is the natural guess — *not measured, do
  not state as fact.*
- **Notice: a fixed 2,322 chars**, identical at both cliff points. Reproduces 725's
  observation (`content_len` 2286, `limit: 50`) — the ~36-char delta is most likely a
  CLI version difference between the two captures.
- **Confirmed: neither tier arrives.** `delivered_fields: null` — this is evidence
  *loss*, not degradation. The correctness framing in §0.3 holds exactly.

**This change already moves a band of queries across the cliff.** Reconstructing the
pre-770 default exactly (take the detail tier, strip the numeric per-stage `detail`
maps that only ever shipped on `detail: true`, re-add the duplicated `path`):

| `limit` | pre-770 default | post-770 default | reduction | pre-770 outcome | post-770 outcome |
|---|---|---|---|---|---|
| 20 | 39,154 | 32,730 | 16.4% | delivered | delivered |
| **30** | **53,756** | **44,603** | **17.0%** | **CLIFFED** | **delivers** |
| 40 | 64,223 | 52,454 | 18.3% | cliffed | cliffed |
| 50 | 79,543 | 64,482 | 18.9% | cliffed | cliffed |

At `limit: 30` on a verbose corpus the old surface lost the entire payload and the new
one delivers it. That is a **correctness** win, independent of the economy argument —
and it is the strongest result this lane produced.

**Honest reconciliation of the reduction figures.** Three numbers appear in this
tempdoc's history and only two are right:
- **31%** — the v5 measurement (N=1081, legal/enron), §D. Authoritative for the eval
  corpora.
- **16–19%** — this live spot-check on `docs/tempdocs`. Also correct; the corpus has
  large markdown excerpts that dilute the provenance share, and shorter relative paths.
- **~40–49% — WRONG, retracted.** An in-session estimate that computed the provenance
  block as `detail:true − default`. That difference *includes* the numeric detail maps,
  which never shipped by default, so it overstated the removable bytes roughly
  two-fold. Caught by the exact reconstruction above. Recorded because the method error
  is the reusable lesson: **do not size a removal by differencing against a tier that
  contains more than the thing being removed.**

Reduction is corpus-dependent in the range **16–31%**. Any public claim must cite a
specific corpus and run.

**Still open — governor design.** The cap is now characterized, so a governor can be
designed against a real number (budget below ~46k, with margin for CLI drift). Its
degradation order remains: numeric provenance → tail-rank compaction (never dropping a
returned `id`) → state what was elided (725's self-describing-results pattern). Not
built in this lane.

### 3b. Original probe framing (superseded by 3, kept for the record)

At `limit: 50` the client replaces the payload with a notice and the agent receives
**neither tier** — total evidence loss (`735:477-479`). Now better motivated:
`limit: 50` was used in **37** measured calls and payloads reach **49,969** chars.
§E.1 buys ~31% headroom, which may push the cliff out of reach at common limits but
does not remove it at 50.

735 flags the cap's threshold, notice format and responsible client layer as
**unknown and not to be treated as fact** (`735:501-504`). Characterize first; design
after. If a governor is built, its degradation order is: numeric provenance → tail-rank
compaction (never dropping a returned `id`) → state what was elided (725's
"self-describing results by projection" pattern, not new machinery).

### 4. `justsearch_fetch` — WITHDRAWN

Design 1 proposed it; design 2 deferred it; the measurement withdraws it.

**`hit.id` is the filesystem path** (identical in all 14,617 hits). So `fetch(id)` is
`fetch(path)` — a tool that returns a file the agent can already Read, in a channel the
agent already has. It has no distinct capability to offer.

Also standing against it: it would need a third content model + renderer (735 G3),
projection through `McpEvidenceProjection` (658), and consumption of
`SearchPipelinePresets.defaultHybridProtoConfig()` (F-037) — the `DocumentService`
call is the easy 10%; and it would land the surface at 7 tools (§A.4).

**Reopen only if** a non-filesystem/extracted source class enters the corpora, giving
`fetch` a capability `Read` does not have. Today's corpora are filesystem files, so a
probe could only ever observe it going unused.

**Correction to design 1's external framing:** the search/fetch split is **OpenAI's
deep-research connector requirement**, not an MCP spec rule (modelcontextprotocol.io
and Anthropic's MCP docs do not mandate it), and OpenAI's stated motive is
substantially **prompt-injection containment**, not token economy. Design 1's
*"2025–26 MCP practice has converged"* overstated it.

## §F. Orphans this tempdoc retires (in-lane, not a later sweep)

1. **The duplicate identity field** (§E.1) — `id` is elided when it equals `path`;
   `path` is the field kept (see §E.1's 2026-07-21 correction — the first
   implementation reversed this). With it goes design 1's "surface `id` in the text
   block so fetch is actionable" item, which is moot twice over.
2. The `querySyntax: "LUCENE"` sentence in `SEARCH_DESC` (§E.2) — **superseded
   2026-07-21.** Deleting the sentence removed exact-phrase and boolean search from
   the agent surface entirely, even though `querySyntax` is a live engine capability
   (`KnowledgeSearchRequest.java:22`, `SearchPipelinePresets#parseQuerySyntaxOrDefault`).
   The defect was the description/schema mismatch, not the capability. Resolution:
   `querySyntax` is now a declared `SEARCH_SCHEMA` parameter threaded through
   `callSearch` to the request, and an accurate sentence is restored — the statement
   is true because the parameter exists, which is exactly what §G's principle asks
   for.
3. The "first search returns top facet values" claim, or the every-call behavior (§E.2).
4. Unconditional per-hit `trace`/`legScores` in the default projection (§E.1).
5. **The answer-path facet round-trip.** `fetchFacets` fires a *second full hybrid
   search* (`limit 0`) on every `justsearch_answer` call for a 3-field sidecar,
   unconditionally, with a swallowed exception (`McpToolSurface.java:580, :1435`). It is
   the only genuine extra backend round-trip on the surface. Design 1 attributed this
   cost to the *search* path and scoped the answer path out on that basis — wrong in
   both directions.

   **Rationale correction (post-implementation, 2026-07-21).** An earlier draft of this
   entry justified the removal on the grounds that the facet block "renders into the
   undelivered text tier". That is **wrong as a sole justification**: `answerEvidence`
   also put `facets` on `justsearch_answer`'s `structuredContent`, which **is**
   delivered. The answer path has no other facet source, so removing the round-trip
   necessarily removes a *delivered* field. The load-bearing argument is therefore not
   delivery but measured non-use: *"Facet/hint affordances produced zero behavioral
   adoption at haiku (third consistent refutation across campaigns) — response-shape
   work is henceforth justified only by correctness and economics"* (`735:471-474`). A
   delivered field with three consecutive refutations of adoption, costing a full extra
   hybrid search per call, is an economics removal — and that is the argument. Recorded
   in the `0.5.0` changelog and `mcp-production-server.md` as a default-shape removal.
   *(Caught by the implementing worker, not by the design pass — the design's own
   delivery insight was applied to the text tier and not carried through to the
   structured one.)*
6. **`TRUNCATION_REMEDY`** — *not* retired here. It is undelivered, so it is inert
   rather than harmful, and it still serves prose clients. Design 1 retired it on a
   falsified premise. Revisit only with §E.3.
7. Stale tool counts: the `:185` comment ("5 curated tools"); `api-contract-map.md:384`
   ("5-tool curated surface") vs `mcp-production-server.md:134` ("6"). Fix both to 6.
8. Stale payload figures: `401-mcp-alternatives-considerations.md:89` and
   `500-mcp-protocol-surface.md:282` propagate `366:158-163`'s ~1,749-token claim.
9. **This tempdoc's own prior errors** (public repo): design 1's invented *"p95 34k,
   max 50k"*, the phantom *"766 §B.5"* citation (actual origin: `762:284` §L row 5), the
   `fetchFacets` misattribution, the "converged MCP practice" framing; and design 2's
   excerpts-gating proposal.

**Kept unchanged:** `response_format` and `detail` (no schema change); the
match-anchored preview windowing; text rendering; `McpEvidenceProjection` registration.

## §G. Reach

### Principle (new): *on an agent-facing surface, the description is the behavior*

A tool description is part of the executable contract, not documentation about it. A
description naming a parameter the schema will not accept, or promising an effect the
implementation does not produce, is a **silent defect with behavioral cost**.

- **Evidence, both directions.** Positive: a description change alone moved organic
  `concise` adoption 5/20 → 19/20 and erased a +15% token regression at zero payload
  cost (`725:1585-1602`). Negative: three false statements are live today (§E.2), and
  agents acted on one of them — 336 calls requested a `concise` mode that returns
  nothing.
- **Sharpened by this lane:** descriptions are delivered; response *text* may not be.
  So the description channel is strictly more reliable than the text tier for anything
  that must reach the model.
- **Where else:** every MCP tool description; the operation catalog's advertised
  capabilities; `/infra/capabilities`.
- **Mechanically checkable** (recorded, not built): every parameter named in a
  description must exist in that tool's schema; every behavioral claim must have a test
  that fails when it stops being true.
- **Earns its keep** when the check finds real drift at authoring time and
  description edits keep moving measured behavior. **Retire** when it stays empty
  across several releases *and* description edits stop moving measured adoption.

### Principle (new, and the expensive one): *measure the delivered artifact, not the produced one*

Three designs' worth of reasoning targeted a text tier the model never receives, and
two independent tempdocs (725's preview enrichment, 732's default decision) may have
attributed a behavioral win to an undelivered component (§A.6). The producing code was
read correctly every time; the transport was assumed.

- **Where else:** any surface with a negotiated or client-chosen representation —
  MCP tier delivery, HTTP content negotiation, SSE vs poll, the FE capability handshake.
- **Existing violation:** the entire text-rendering path for `justsearch_search` is
  built on every call and discarded for the dominant client — including a backend
  round-trip (§F.5) whose only consumer is undelivered text.
- **The instrument already exists** — `delivered_tier`/`delivered_fields` (735 W2).
  It was added late and its implications were never propagated backwards through the
  conclusions it invalidates. That propagation is the work, not more instrumentation.
- **Earns its keep** when a design is corrected pre-implementation by checking
  delivery (this lane: three times). **Retire** when delivery is single-tier by
  construction, or when the capture is universal enough that "was it delivered" stops
  being a question anyone can get wrong.

### Conform (already exists): the projection kernel

658's kernel — one canonical record → many governed projections → a gate forbidding
re-authoring — governs this seam; 735's tier equivalence is the product contract over
it. This lane is another instance. **Conform; do not build a parallel.**

- **Refinement (generalizes):** *a totality guard must assert totality over the union
  of tiers, not a test-only path.* Design 1 proposed keeping full projection on the
  test-only single-arg overload while gating production — leaving the guard that
  `execution-surfaces.v1.json:389` names as *the* guarantee no longer describing what
  ships. Applies wherever a registered projection grows a tier. **Earns its keep** when
  it catches a field that silently stops shipping; **retire** when projections stop
  being tiered.

### Withdrawn: "search returns pointers + spans; fetch returns bodies"

Design 1 nominated this. Withdrawn — it is one vendor's connector requirement with a
security motive (§E.4), and here the pointer *is* the body's path, so the split has no
local content.

## §H. Sequencing

| Part | Gate | Risk | Ready? |
|---|---|---|---|
| 2 — description truth | none | none | **DONE** (d5048033) |
| §F.5 — answer facet round-trip | none | removes a *delivered* field — see §F.5 rationale correction | **DONE** (d5048033) |
| §F.7/8 — stale counts + figures | none | none | **DONE** (d5048033) |
| 1 — remove trace/legScores + duplicate identity field | owner approved bump-now | none to content; `0.5.0` bump landed | **DONE + live-verified** (d5048033); identity direction corrected to keep `path` (independent review) |
| Review fixes — runtime-contract bump `0.1.0` → `0.2.0`, `querySyntax` wired, §C/§F.5 reconciled, guard fixtures made genuinely maximal | owner-decided (bump now; wire don't delete) | none | **DONE** |
| 5 — cap-characterization probe | none | none | **DONE** — threshold 46.6k–52.8k, notice 2,322 chars (§E.3) |
| 3 — response-size governor **design** | cap now known | medium | **no — not this lane** |
| 4 — `fetch` | withdrawn | — | **no** |
| §A.2 read-amplification | routed to engine/relevance lane | — | **not this lane** |
| §A.6 — 732 attribution | 732's to settle; logged to inbox | — | **not this lane** |

**Cohort note.** §E.1 bumps `TOOL_SURFACE_VERSION`, changing the `tools/list` hash,
which is measurement-cohort identity (`735:102-106`). Campaigns cannot compare across
the bump. Land Parts 1+2 on **one** bump, and choose the point deliberately relative
to the 766 hero campaign — 766 §"Hero campaign" wants the campaign to measure the
improved product, which argues for bumping *before* it, not during.
