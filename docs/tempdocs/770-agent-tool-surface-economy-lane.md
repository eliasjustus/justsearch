---
title: "agent tool-surface economy lane: snippet-first results + fetch(doc_id) + passage-span salience — stop search payloads from eating the synthesis budget and returned gold from going unopened"
type: tempdocs
status: "designed (2026-07-21). Founder-run implementation lane — ready to implement."
created: 2026-07-21
author: agent (Fable orchestration), 766 program charter
category: agent-tool-surface / mcp
related:
  - 766-eval-content-rebuild-program   # umbrella — READ FIRST
  - 765-agent-economics-lane           # §E Results: payload/friction evidence
  - 763-retrieval-attribution-lane     # §F Results: B3-search (returned-unopened) evidence
  - 735-agent-surface-seam-consolidation  # the MCP surface seam being modified
---

> Charter. Independent of 767/768/769; can run in parallel. Evidence is
> banked — this lane designs and ships tool-surface changes, then verifies
> them with a zero/low-spend live probe.

# 770 — agent tool-surface economy lane

## §A. The three banked findings (do not re-derive)

1. **Payload weight**: `justsearch_search` result sets median ~13k chars
   (p95 34k, max 50k); a with-tool cell spends ~15–20k tokens on payloads —
   directly competing with hop-2 synthesis budget at the small-model context
   ceiling (765 §E).
2. **Read-amplification**: `mcp_call_share` ≈ 0.5 — after searching, agents
   re-Read full files (157 full-file Reads at legal-10k, max 92k chars)
   because the returned snippet doesn't carry the answer span; the rare
   with-tool cell deaths are fallback-grep spirals (765 §E).
3. **Returned-unopened**: 11 census cells had gold at rank 1–7 in returned
   results and the agent never opened it (763 §F, B3-search).

## §B. Direction (design is the lane's — constraints only)

- Candidate shape (766 §B.5 / analysis proposals): snippet-first default
  with tight top-k + a `justsearch_fetch(doc_id)` (or equivalent) for full
  text on demand; answer-bearing passage spans in results so the follow-up
  full-file Read is unnecessary; salience presentation so top-ranked hits
  get acted on. Schema changes respect F-016/F-017 (schema complexity
  measurably degrades small-model tool use — keep schemas minimal,
  capability in descriptions/backend).
- The MCP evidence path has one canonical retrieval (F-037's fix) — do not
  fork a second result-shaping authority; shape at the projection seam
  (`McpEvidenceProjection`).
- No adoption scaffolding: first-search-at-turn-1 is already universal
  (765 §E) — do not add "use the tool" prompt engineering.

## §C. Acceptance

- Token-economy measurement: on a cached-index live probe (haiku, ~10
  queries, zero/low spend), per-cell payload tokens drop materially vs the
  765 baseline WITHOUT gold-reachability loss (replay-verify gold still
  reachable through the new surface at the same k).
- The 11 B3-search reproductions: under the new presentation, an agent probe
  opens/uses the top-ranked gold (spot-check subset acceptable; report
  honestly).
- MCP surface contract tests + full suite green; `check-intent-tier-coverage`
  and the wire gate run if their subjects are touched.
- Findings that indict the engine (not the surface) route to the
  search-quality register, not this lane.

## §D. Theorize (2026-07-21)

**The three findings pull in two directions, and that is the whole design
problem.** §A.1 (payload weight) wants the search tool to return *less*; §A.2
(read-amplification) and §A.3 (returned-unopened) want each returned hit to
carry *more* — the answer span, so the follow-up full-file Read is unnecessary
and the gold gets acted on. A naive "return less" (e.g. flipping the existing
`concise` tier — tempdoc 725 W2c, `RESPONSE_FORMAT_SCHEMA`
`McpToolSurface.java:262` — to default) is exactly wrong: `concise` *omits the
Preview line* (`renderSearchText` :894), stripping the very span §A.2/§A.3
need. So the resolution is compositional, not a single verbosity dial: **fewer
/ depth-graduated results + a stronger, de-duplicated snippet + demotion of the
non-gold structured bulk that rides every response today.**

**Which surface each banked finding indicts (the precision this lane needs).**
All three §A findings indict `justsearch_search`, not `justsearch_answer`:
- §A.1 payload is `callSearch` returning 10 hits, each with a preview *plus*
  an always-on per-hit `trace`+`legScores` block in `structuredContent`
  (`McpEvidenceProjection.projectHitExcerptsAndTrace` :176–186), *plus* the
  region excerpt text duplicated into both the text Preview line and
  `structuredContent.excerpts` (:164–174), *plus* a 6-field facet sidecar
  fetched on every call (`fetchFacets` :1435).
- §A.2/§A.3 are the search snippet not carrying the span, so the agent
  re-Reads the whole file off disk — a behavior the current
  `TRUNCATION_REMEDY` string actively *invites*: it points at the filesystem
  path ("full text at the path above", `McpSearchResultFormatter.java:36`).
- **763 §F's "28 gold already in the answer pack" indicts nothing on the
  surface.** The `justsearch_answer` pack already *contains* the gold; that is
  a synthesis/relevance gap (769's lane), not a tool-shape gap. So 770 changes
  the answer path only cosmetically (apply the same economy discipline —
  dedup, keep the concise passage cap) and explicitly does **not** try to fix
  answer gold-usage here.

**The fetch tension (F-016 vs F-017), resolved by shape not by count.** F-016's
−21pp landmine was *optional params on an existing tool* (`doc_ids` +
`return_full_documents` made the schema branchy). F-017's +20pp was
*task-orientation*, with tool-count a secondary cost. A **single-required-param
`justsearch_fetch(id)`** is the shape that dodges F-016 entirely (no optional
branch on `search`/`answer`) while nudging the count F-017 cares about — and
2025–26 MCP practice has converged on exactly this: OpenAI's deep-research
connector spec mandates two read-only tools, `search` (returns top-k ids) and
`fetch` (id → full resource), with the discipline "search shouldn't dump full
documents, and fetch shouldn't be guessed at by URL string." That external
convergence is evidence the pointer/body split is the natural shape, not a
local invention.

**Hidden assumptions worth stating.** (a) Agents read the *text* block, not
`structuredContent` — so the doc `id` must appear in the text for fetch to be
actionable (today `renderSearchText` :883–889 prints title/path/score but not
`id`). (b) `fetch(id)` on a 92k-char doc costs ~as many tokens as the
filesystem Read it replaces — fetch is **not a compressor**; its value is
staying in-channel (telemetry, works for non-filesystem/extracted sources), and
the *economy* win comes from the snippet reshape making fetch rarely needed. Say
this honestly rather than overclaiming fetch as a token saver. (c) Cutting
default `top_k` risks dropping gold the acceptance forbids — so the economy lever
must be per-hit verbosity + tail-compaction, with any count reduction held at or
above the measured rank-1–7 gold envelope (§A.3).

**Principle sighting (developed in §E).** This lane is another instance of the
"one canonical retrieval, projected in tiers by consumer altitude" shape already
governing the MCP evidence path (F-037 / tempdoc 658 / the execution-surface
register): the token-scarce agent tier simply gets a *thinner* projection of the
same `SearchTrace`/hit records. Conform to it; do not fork a second shaping
authority.

## §E. Design (2026-07-21)

**Settled tool-surface contract.** Net surface after 770: 7 tools
(`answer`, `search`, `browse`, `ingest`, `status`, `runtime_manifest`, **+
`fetch`**). No new *required parameters* on any existing tool (F-016); all new
capability lives in result shaping, descriptions, and the reused backend read
path (F-037 — no second retrieval authority).

**1. `justsearch_search` — reshaped snippet-first default (schema shape
unchanged).**
- *Depth-graduated results.* Keep retrieval breadth; graduate *presentation*:
  ranks 1–N get the full answer-anchored snippet; the tail (N+1…) collapses to
  a compact line (`id` + title + score + one-line match basis, no preview).
  This cuts per-response bulk while keeping every returned `id` — so gold at any
  returned rank stays reachable. Default `limit` drops from 10
  (`SEARCH_SCHEMA` :283, `callSearch` :756) toward a smaller value **held at or
  above the measured gold-rank envelope** (proposed 7; the exact N/limit is a
  probe-tuned parameter, never set below the §A.3 rank-1–7 envelope).
- *Snippet.* Stays answer-span-anchored via the existing excerpt-region
  windowing (tempdoc 725, `buildHitPreview` :967; `REGION_WINDOW_CHARS=300`).
  Allow the top rank(s) a modestly wider window so the span is carried in full;
  keep the descriptive (never imperative) match-basis line.
- *Surface `id` in the text block* (:883–889) so `fetch` is actionable for
  agents that read text, not `structuredContent`.
- *Fetch affordance replaces the filesystem pointer.* Retire
  `TRUNCATION_REMEDY`'s "full text at the path above"
  (`McpSearchResultFormatter.java:36`) in favor of a fetch-pointing remedy
  ("preview — call `justsearch_fetch` with id <id> for full text"). This turns
  the §A.2 re-Read-off-disk nudge into an in-channel one.
- *Demote non-gold structured bulk to the existing `detail` opt-in.* Today
  `detail` gates only the numeric detail tier; extend it to gate the whole
  per-hit `trace`+`legScores` block and the duplicated
  `structuredContent.excerpts` (both unconditional today,
  `projectHitExcerptsAndTrace` :164–186). Default responses stop shipping
  ranking provenance and a second copy of the excerpt text.
- *Facets first-call/opt-in.* The description already claims facets come on the
  *first* search, but `fetchFacets` runs every call — gate the 6-field sidecar
  to the session's first search (or an opt-in), removing a repeated block from
  every subsequent query.

**2. `justsearch_fetch(id)` — NEW, minimal schema.** Required `id: string`,
nothing else. Returns the indexed full-document body for that id, reusing
`DocumentService.fetch(docId)` (`DocumentService.java:39`) — no new retrieval
authority. Task-oriented (F-017), minimal-schema (F-016). Honest scope: the
escape hatch that keeps agents in-channel and serves non-filesystem/extracted
sources; not itself a token compressor.

**3. `justsearch_answer` — economy discipline only.** 763 §F shows gold is
already *in* the pack, so 770 adds no fetch/doc-scoping here and does **not**
touch answer gold-usage (that is 769/relevance). It keeps the `concise`
3-passage cap available and removes any passage duplication between the text and
`structuredContent` channels — nothing more.

**Orphans this tempdoc retires (not a later sweep).**
- The path-pointing `TRUNCATION_REMEDY` string → fetch-pointing remedy.
- Unconditional per-hit `trace`/`legScores` emission → gated by `detail`. Test
  implication: `McpEvidenceProjectionTest`'s reflective-totality guard runs
  against the single-arg `searchEvidence` overload — keep that overload's full
  projection for the guard; gate only the production content-model overload. The
  projection stays registered in the execution-surface register (tier/gating
  change, not an authorship change).
- `structuredContent.excerpts` duplication → gated by `detail`.
- Facets-every-call → first-call/opt-in.

**Verification probe (zero/low spend, cached index, haiku, ~10 census queries
from 765/763).**
- *Economy metric:* median + p95 total response size (text + serialized
  `structuredContent`) per `justsearch_search` call, before vs after. Target: a
  material reduction off the ~13k-char median (design intent ≈40–50%, i.e.
  ~6–7k) — report the measured number; no public claim precedes a citable run.
- *Reachability guard (hard, = acceptance):* for every query with known gold,
  the gold docId still appears in returned `results` at the same rank/k as the
  765 baseline. Any dropped gold means the count/compaction was too aggressive —
  fail closed and raise N.
- *Salience spot-check (directional):* replay the 11 §A.3 B3-search cells; under
  graduated results + `id`-in-text + fetch affordance, does the probe agent open
  /use the top-ranked gold? Report honestly on a subset; not a hard gate.
- *Contract tests:* `McpSearchResponseFormatTest`, `McpTierEquivalenceGoldenTest`
  (its golden text is *intentionally* re-baselined by the reshape — an intended
  golden change, confirm with owner, not a regression), `McpEvidenceProjectionTest`
  (totality guard), plus a new `fetch` contract test. Run
  `check-intent-tier-coverage` / the wire gate if their subjects are touched.

**Reach — principle + retirement conditions.**
- *Conform (already exists):* "one canonical retrieval, projected in tiers by
  consumer altitude" (F-037 / tempdoc 658 / execution-surface register). 770 is
  a new instance — the agent tier gets a thinner projection of the same records.
  *Earns its keep* when the thin default drops payload without gold loss.
  *Retire/revisit* if a future consumer needs a defaulted-off field that
  `detail` cannot recover — then the tiering boundary is drawn wrong.
- *Candidate (names the split):* "search returns pointers + answer-bearing
  spans; fetch returns bodies." Applies to any future list/browse surface
  (return ids + spans, not bodies). *Earns its keep* when full-file re-Reads and
  per-call payload both fall without gold-reachability loss. *Retire* if the
  probe shows fetch is essentially never called (agents keep re-Reading off disk)
  or payload does not drop — then collapse `fetch` back out and rely on the
  snippet reshape alone (this is also the F-017 count-cost falsifier for the new
  tool).

**Unsettled decisions (flagged for the owner / implementer).**
1. Exact default `limit` and graduation N — probe-tuned; proposed 7, must be
   proven ≥ the measured gold-rank envelope before it lands.
2. Whether `fetch`'s marginal value clears its F-017 count cost — the probe must
   show fetch is actually used and reduces whole-file Reads; if not, drop it.
3. Facets gating mechanism — first-call needs a per-session hook; if none is
   clean, an opt-in flag is a schema addition to weigh against F-016.
4. `McpTierEquivalenceGoldenTest` re-baseline — confirm the reshaped default is
   an accepted golden change, not treated as a break.
