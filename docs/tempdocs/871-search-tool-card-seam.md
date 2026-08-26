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
- **Level 2 — the ranked list with used-markers, expanded in place.** One muted
  scope line (folder restriction or "all folders" · resolved preset · explicit
  limit), then **the model's full ranked list**, capped at 6 — each row two
  lines: dot · title (+ a small `used` tag) / dim path · locator. No scores, no
  snippets, no repeated query, no monospace. Rows the run drew on carry a subtle
  accent tint and a filled dot; the rest stay plain and dimmed, and both are
  clickable. A footer counts what the cap hid honestly ("2 more used · 5 more
  retrieved, not used") and carries the one bordered control: **Open in Search ⤴**.

  > **Owner revision, 2026-08-26 (§3b).** This REVERSES the original "only the
  > rows that became run evidence" summary. Filtering to the accepted subset hid
  > the thing a reader is actually judging — whether the model's *search* was any
  > good, which is a fact about the whole ranked list, not about its accepted
  > tail. "Used" became a MARK on a row rather than a filter over rows.
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

**Owner decision, 2026-08-26: the visible copy is `used`.** The accessory reads
`9 results · 1 used`; the footer reads `K more used` / `J more retrieved, not
used`; the row tag reads `used`. This settles §5 open decision 3 against this
section's original ruling (that "used" overclaimed and the card should say "in
evidence") — recorded here rather than quietly changed, so the register trail is
honest.

What did NOT change is the authority. "Used" still means *minted by the ONE
grounding authority* (865 §7.1) — the card computes nothing new, and the code
keeps the register's names (`inEvidence`, `evidenceCount`, `evidencePaths`), so
this is a copy decision at the presentation edge, not a second axis. Any per-row
inclusion badge still comes from 849's vocabulary; if 865's inclusion computation
(§4.6 derisk) later lands, the same rows inherit `partial/dropped` badges without
a card redesign.

## 3a. Live verification (2026-08-26, worktree FE on the shared dev stack)

Delegate run dispatched in SV3 (compact profile, 661-doc corpus): flattened L1
rows render with `N results · M in evidence` accessories; L2 expands in place
with evidence rows (dot · title · dim path · `Line N`/heading locator) and the
`Open in Search` pill; run-set join live (`3 in evidence` matched the run's
minted sources). One live-caught defect fixed in the same branch: the chevron
inherited the retired ▼-base rotation rule and rendered collapsed as ▲ — now
collapsed ▶ / expanded ▼. Follow-up logged (observations): `composeToolLabel`
still renders raw tool names ("Search Index") where the design wants verb copy
("Searched …") — a shared-authority (565 §12.3.B) copy pass, not card work.

Two more live findings caught on the merged build (PR #570), fixed in a follow-up
branch: (1) a `10 results · 10 in evidence` search rendered all 10 rows at L2,
collapsing the summary back into a list — capped at 5 (`ToolCallCard.ts`'s
`EVIDENCE_ROW_CAP`), footer now composed from `evidenceCount`/`resultCount`
honestly ("N more in evidence" / "N more retrieved, not in evidence"); (2) SV3
mounted the tool card with no `card-open` listener, so an evidence-row click was
dead in that window — wired in `Sv3Main.ts` (`onToolCardOpen`), routed through
the existing `SV3_CITATION_OPEN` document-open seam rather than a new pane.

### 3b. Owner-feedback batch (2026-08-26) — what changed, and the chronology finding

Five owner-directed changes on the merged card (PRs #570/#574). All five are
implemented; the first four are FE-only, the fifth turned out to be a Java
record-plane defect.

1. **Header verb copy.** `composeToolLabel` (`display/toolLabeling.ts`, the ONE
   565 §12.3.B authority) gains `verbLabel` — the tool as an ACT. The card header
   renders `Searched “taxes”` with the accessory `9 results · 1 used`, and wraps
   instead of ellipsizing the query mid-word. Map: search/find/query/grep →
   `Searched`, read/open → `Read`, browse/list/ls/dir → `Listed`,
   write/edit/save/create → `Write`; anything else keeps its humanized label
   rather than getting a guessed verb. **Tense is deliberately not uniform:** the
   read-only verbs only ever render as the record of something that already ran
   (auto-approved LOW), so they take the past form; a WRITE routinely renders
   *before* it happened — on the approval card — so "Wrote" would be a lie on the
   very card the reader is deciding on. Quoting is search-only: a query is a
   literal, a filename is a name.
2. **Copy: "in evidence" → "used"** (§3, recorded there as the owner decision).
3. **L2 is the full ranked list with used-markers** (§1, recorded there as the
   owner revision). Cap 5 → 6; two-line rows; used rows tinted + filled dot + a
   `used` tag; unused rows plain, dimmed, and still clickable. The footer counts
   only what the cap hid, split from OBSERVED hits — and when the run's evidence
   set was never wired to the card (`UnifiedChatView`'s record-hydrated items) it
   says `N more results`, dropping the old footer's "not in evidence" claim that
   an unwired card could never actually have known.
4. **Scope/filters line** under the header at L2: folder restriction or
   `all folders`, the resolved `searchMode` when the record carries one, and the
   `limit` the call explicitly asked for. The effective *default* limit is a
   config fact (`SearchTool.DEFAULT_LIMIT` ← `ConfigStore`) the record does not
   carry, so it is omitted — the same honesty rule as §2a's `mode` gap.

**A measured defect the SV3 contrast oracle caught while doing this.**
`Sv3Main.imports.test.ts` computes every text/surface pair from the token values.
The used-row tint lightens the ground just enough that `--text-secondary` lands
at **4.31:1** on the dark window — below AA. Fixed at the source, not by
relaxing the oracle: a used row's second line is not dimmed. The dimming axis is
used-vs-unused; inside a used row the hierarchy is weight and the locator's
italic. (This is the `ux-audit-closure` discipline paying off as a build-time
oracle rather than an eyeballed pass.)

**5. The chronology defect — root cause, and the fix.**

Owner observation: a search tool card rendered AFTER the reasoning block that
analysed its results (`Thought(plan) → Thought(analyze) → [card]`).

*It is the RECORD plane, and it fires on every healthy stamped delegate run —
not, as the code claimed, only on a truncated one.* Chain, verified at source:

- `AgentInteractionMapper.fromRunEvents` attaches a trailing reasoning block to
  the next event that projects (848 §2.4). For a run's last iteration that is the
  terminal `done` → the `ASSISTANT_MESSAGE`. Correct.
- `AgentRunQueryService.withoutTerminalAnswer` (863 A-2) DROPS that answer for a
  stamped run whose answer the conversation record already holds — deleting the
  block's carrier — and re-homed the block onto `kept.get(size-1)`, which on a
  normal run is the run's final `TOOL_ACTIVITY`: **an event that happened before
  the thinking.** 863 pinned this as a "KNOWN INVERSION"
  (`AgentRunQueryServiceThreadEventsTest`) and routed the question to its §8.4,
  on the belief that only a truncated run could reach the shape.
- `sv3-record.ts` emits a carrier's reasoning items BEFORE the carrier's own
  item, so the inverted carrier renders the thought above the tool.
- The LIVE plane was ruled out: `AgentSessionController.handleToolCallEntry`
  commits the open region at `pending`, and `onToolExecStarted` does the same for
  a call that was never grouped — so the auto-approved-LOW path *is* covered, and
  `AgentStepRunner` emits `ToolExecutionStarted` unconditionally.

**Fix, at the root:** the block's true carrier is the ANSWER plane's copy of the
same answer, and only `InteractionThreadController` sees both planes — which 863
§4.A.5 already names as where cross-plane facts belong. So
`withoutTerminalAnswer` now *hands the orphaned blocks to its caller* (new
`ThreadProjection` record: events + `trailingReasoningByRun`) instead of guessing
a carrier, and the controller merges them onto the store-plane
`ASSISTANT_MESSAGE` for that run, whose timestamp is after the tool. No wire
shape changes, no FE change, no new field, and it self-heals records already on
disk. A run whose answer row projects no turn keeps the pre-871 projection gap:
the blocks stay on disk unrendered rather than landing somewhere untrue.

Bonus closure: 863 §8.4's other half — a delegate run that called NO tool had no
surviving carrier at all and dropped its final block entirely — now lands on the
store answer too.

Rejected (recorded so they are not re-proposed): a `trailing: true` marker on
persisted blocks (wire-visible, touches both windows' readers, and only
re-encodes the same positional convention); persisting the run's reasoning on the
store row at write time (a second fold site, helps no existing record); patching
the order in `sv3-record.ts` (presentation patching a record defect).

Tests: `AgentRunQueryServiceThreadEventsTest` (the inversion pin REWRITTEN — the
run plane must carry nothing it did not already carry; confirmed to fail when the
old re-home is restored), `InteractionThreadControllerTest` (the block lands on
the answer and NOT on the tool; an unmatched run's block lands nowhere), and
`sv3-timeline-parity.test.ts` (the reader-visible order for a healthy stamped
run). F4 stays — a truncated run really is the one remaining shape, and its
rationale now carries the 863→871 excursion.

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
3. ~~Exact register copy for "in evidence"~~ — **SETTLED 2026-08-26: `used`**
   (§3). The authority is unchanged; only the visible word is.

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
