# 871 — The search tool card as the chat↔search seam: three levels of one row

```
status:  DESIGNED + DERISKED (2026-08-26, §2a) — owner-approved interaction prototype
         (session scratchpad `tool-card-prototype.html`; shape settled in-chat
         2026-08-25). NOT IMPLEMENTED. Depends on: 865 grounding-delta stamp
         (PR-1/PR-3 merged), 859 feed projection (merged). Sequenced against
         852 S5-S11 (retrieve tier + window cutover, gate red 2026-09-30).
created: 2026-08-26
```

## 1. The problem and the idea

Research (2026-08-25 session, three-lens web survey) found the chat→search
bridge is an unsolved seam across the product field: no shipping product lets a
user step from an agent's search *record* into a browsable result list
(Cursor/Zed carry open feature requests for exactly this). JustSearch already
has both halves; the tool card that records an agent search is the natural
seam between them.

**The design: three levels of one thing, built from one row component.**

- **Level 1 — the row.** Every tool card (search or not) is a one-line record:
  `status glyph · verb · target · muted accessory · disclosure chevron`.
  Default for every card; a card the user opened stays open until the user
  closes it (no accordion, no auto-collapse of user-opened cards).
- **Level 2 — the summary, expanded in place.** One muted scope line (roots ·
  type filters · pipeline preset actually used), then **only the rows that
  became run evidence** — one line each: dot · filename · dim path · locator.
  No scores, no snippets, no repeated query, no monospace. A footer counts the
  rest honestly ("9 more retrieved, not in evidence") and carries the one
  bordered control: **Open in Search ⤴**.
- **Level 3 — a navigation, not an expansion.** Open in Search leaves the
  transcript for the product's search surface, seeded with the model's query,
  scope and pinned result set; a breadcrumb ("← Back to chat · turn N ·
  search k of m") returns to the turn with the card still at level 2. A pill
  ("as the model saw it") marks the list as the run record; the first user
  edit clears it and the search becomes an ordinary user search, decoupled
  from the record. Which window hosts level 3 is whatever 852's cutover
  promotes — this design binds to "the one search surface", not to a window.

**Affordances — two, one meaning each.** The chevron (trailing; whole row is
the hit target) toggles 1↔2 on every card. Open in Search exists only on
search cards, only at level 2. Nothing cycles. Keyboard: card is a focus stop;
Enter/Space toggles; ↑/↓ between cards; the evidence rows and the pill are tab
stops. Status is the *glyph* (running/done/degraded); the verb carries the
tool; risk appears only when it is not LOW.

Rejected in design (recorded so they are not re-proposed): a three-state
cycling chevron; the card ballooning full-screen inside the transcript; a
fixed-height "miniature search window" at level 2 (three delegated design
prototypes independently produced a dense embedded result list from the same
brief — the owner judged all three too raw for a transcript; level 2 is a
summary, not a list); auto-collapse of sibling cards; editing the model's
query inside the card.

## 2. What exists (survey, `main` 2026-08-26)

- `ToolCallCard.ts` renders every tool call in BOTH windows
  (`UnifiedChatView.renderToolActivity`, `Sv3Main.ts` via
  `projectSv3RunFeed`'s `{kind:'tool'}` items). One component serves both —
  the redesign lands once.
- The card today: bordered box, header (label + target, run-step glyph,
  `RISK · STATUS`, chevron), raw JSON args block, lineage header, then a
  nested `<jf-results-card variant="excerpt">` with its **own second
  disclosure** (Search Thread S7). Two chevrons for one record; the args dump
  says nothing the scope line wouldn't; `RISK` renders even at LOW; terminal
  cards auto-collapse (userToggled pins).
- `toolSearchCard.ts` is already the ONE projection from a search call's
  `structuredData` to the shared card shape — it evolves, it is not replaced.
- 859 (merged) gives the ordered run feed and the run-step glyph authority
  (`RunNode`, `statusTone`, `runStepPresentation`).
- 865 (PR-1/PR-3 merged) stamps the Java-minted **grounding delta** — the
  sources a call newly established — onto that same call's
  `ToolExecutionCompleted.structuredData`, the OutputLineage carrier pattern.
  865 §7.1 states the consequence this design consumes: *"evidence attaches to
  the tool card that already exists."* The level-2 evidence rows are a
  projection of that stamp joined against the call's own `searchResults[]`.
- 849 built the inclusion vocabulary (`ContextInclusion`, `inclusionBadge`,
  "Retrieved · never sent to the model"); 865 §4.6 says reusing it on the
  delegate plane is projection, not fork.
- 865 §5.2 (measured prior-art finding): mid-run presentation should stay
  minimal; what converts a trail into value is an **action** on it. Level 2's
  used-only summary and level 3's seeded navigation are that action; this
  design adds no new panel and no per-call timeline rows.

### 2a. Derisk findings (2026-08-26, read-only pass; all verified at source)

- **The grounding stamp is directly consumable.** Key `grounding`
  (`OperationResult.GROUNDING_KEY`), eight fields per item incl. `path`,
  `startLine`/`endLine`, `headingText`, `excerpt`. Both planes already read
  it: live `AgentSessionController.accumulateGroundingDelta` (run-wide
  accumulator, run-identity-keyed) and record `sv3-record.groundingDeltaOf`.
  Join key `path` matches `searchResults[].path` (= row id). L2 needs no new
  data — only a projection joining the two.
- **Accessory semantics settled:** the stamp is a *delta* (cross-call dedup),
  so a duplicate search honestly carries none. Row dots therefore mark
  **membership in the run's accumulated evidence set** (the accumulator both
  planes can derive), not the per-call delta — stable under duplicate
  searches. Copy: "N in evidence (this run)".
- **The spine must host the whole feed.** `projectSv3RunFeed` interleaves
  text/reasoning/note items between tool items in strict order (859 §A). The
  run container is therefore a *styling of the existing feed region* hosting
  every item kind in order — never a regrouping of tool cards, which would
  re-create 859's lost-chronology defect.
- **Scope line: honest data exists, one gap.** Per-call args carry `query`,
  `limit`, `mode`, `pipeline`, and a folder restriction. Gap: when `mode` is
  absent the *effective* preset comes from config and is recorded nowhere on
  the call — the scope line renders only what the record carries, and a
  one-field backend stamp (resolved mode into `structuredData`, beside the
  existing stamps in `SearchTool`) is in this tempdoc's implementation scope.
  Type filters are not a per-call fact; dropped from the scope line.
- **Locators are line/heading, not pages.** `searchResults[]` carries `line`;
  the grounding items carry `startLine`/`headingText`. The prototype's
  "p. 3"/sheet locators were fixture fiction; L2 renders heading or line.
- **Breakage census (R5):** ui-shot's 58 steps pin nothing inside the tool
  card. The unit pins to rewrite are enumerable and local:
  `ToolCallCard.test.ts` (risk-word button, `awaiting-approval`,
  `tool-output-lineage`, `tool-search-card` + `card-excerpt` toggle,
  `evidence-lineage`) plus `Sv3Main`'s card CSS reach-ins. `jf-tool-call-card`
  stays in the component vocabulary (no regen unless new tags are minted).
  Baseline green confirmed: ToolCallCard/toolSearchCard/sv3-run suites,
  60/60.
- **L3 seeding seam exists:** `searchState` exposes `setQuery` /
  `setSearchScope` / `buildSearchIntent`; 852 S5-S7 still pending, still the
  host.

## 3. Vocabulary (binding)

The prototype's word "used" overclaims. The card adopts the 849/865 register
instead: the accessory reads `12 results · 3 in evidence` (or the register's
final copy), where "in evidence" = minted by the ONE grounding authority
(865 §7.1), and any per-row inclusion badge comes from 849's vocabulary — the
card never invents a third axis. A row that was retrieved but not minted is
counted, not listed. If 865's inclusion computation (§4.6 derisk) later lands,
the same rows inherit `partial/dropped` badges without a card redesign.

## 4. What this orphans (deleted/tombstoned in this tempdoc's work)

- The nested second disclosure: `ToolCallCard`'s `variant="excerpt"` mount and
  its expand-in-place path. (`ResultsCard`'s `excerpt` variant itself survives —
  `UnifiedChatView` still renders restored SEARCH thread events with it.)
- The raw JSON args block for search cards (replaced by the scope line; other
  tools keep a body only where they have one worth showing).
- `RISK ·` on LOW cards, and the `STATUS` word (folded into glyph + row
  treatment; medium/high risk keeps its tint + because-line + approval row).
- The card-level auto-collapse policy (terminal collapses, active expands)
  narrows to: system may open the RUNNING card and settle it back; a card the
  user touched is never system-toggled (extends the existing userToggled pin).
- The card's own bordered-box-per-call framing, where the run container/spine
  (owner-approved prototype) replaces it in the SV3 feed. The unified window
  keeps working through the same component; its framing migrates or the
  difference is declared, not left accidental.

## 5. Open decisions (owner)

1. Does level-2 expansion state survive reload? Lean: no — per-conversation,
   in-session FE state only; records stay clean.
2. Running search auto-shows at level 2 while streaming, settling to level 1?
   865 §5.2's restraint finding argues default-off; prototype ships the
   affordance behind a single flag either way.
3. Exact register copy for "in evidence" (843/849 vocabulary owners).

## 6. Sequencing

Level 1+2 (the card flattening) is independent of 852 and can land first — it
touches one component + one projection, both windows benefit, and it consumes
only merged substrate (859 feed, 865 stamp). Level 3 needs a seedable search
surface: it is 852 S5-S7 work by construction, and its "as the model saw it"
pill belongs to the retrieve tier's spec. Do not build a second search surface
for level 3.

## 7. Reach

**Principle: a record's card is a projection of the record at reading density —
detail lives where the user can act on it.** Instance of the existing
projection-not-fork discipline (execution-surfaces register) and 865 §5.2's
act-on-it finding, applied to presentation: the transcript shows what happened
and what it established; ranking detail (scores, snippets, the unminted tail)
lives only on the surface where the user can re-run, filter and open. Candidate
wider scope: the read/browse/write cards (same L1/L2 grammar, no L3), and the
delegate-run *sources* pane vs the evidence reader (849) — the pane is the
summary, the reader is the acting surface. Existing violation: today's card
embedding a result list with its own disclosure is exactly the fork this names.
**Earning its keep:** the L2 summary is judged sufficient in live use (no
regression to embedding lists in cards) while Open in Search sees actual use.
**Retire when:** if live use shows readers systematically needing ranking
detail *in the transcript* (repeated L3 round-trips for a single glance), the
principle is wrong for this surface and L2 should carry more — measure, then
widen, rather than pre-widening.
