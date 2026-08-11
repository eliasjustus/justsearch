# 818 — Search v2: the from-scratch window skeleton (strangler, dev-gated)

```
status: ACTIVE
created: 2026-08-08
owner-decisions: 817 §4 #4 (rewrite planned, design-first) — sequencing REVISED here (owner, 2026-08-08)
design-inputs: 818-prototype/ (v5 structure prototype + identity layer), NOTES-for-818.md (harvest)
supersedes: nothing yet — UnifiedChatView remains the shipped window until the sunset criterion below
```

## 0. Decision and sequencing revision

817 §4 #4 recorded: *"Search-window rewrite: planned, post-0.2.0, design-first — a rewrite
tempdoc settles the interaction model before any code."* This tempdoc is that design doc.
The design phase happened as an interactive structure prototype (2026-08-07/08, preserved
verbatim under `818-prototype/` — serve the directory statically and open `index3.html`;
`?theme` adds the identity layer). The prototype settled the interaction model to the point
of twelve testable laws, a component decomposition with fixtures, and one live-proven
transition (the commit).

**Sequencing revision (owner, 2026-08-08):** the *skeleton* — a from-scratch, dev-gated
sibling window ("Search v2") — is design-phase work and starts now, pre-0.2.0. It is not
user-reachable: no rail entry, hidden route only. **User-facing cutover remains post-0.2.0**
and requires the sunset criterion in §5. Rationale for building beside rather than editing:
`UnifiedChatView.ts` (6,083 lines) has fan-in of exactly one (a side-effect registration in
`Shell.ts`) and its defects are model-level (three parallel conversation representations,
authored counts, state-gated affordances) — a copy would import the disease; a sibling
window makes the comparison empirical.

## 1. The design (settled by the prototype; authority = 818-prototype/index3.html)

One window, one input, meaning escalates. The core object is **one records array**;
transcript, session index, session name, and context ledger are all projections of it.
A live search is not a record; it lives in the deck and becomes a record only by commit.

### The twelve laws (the behavioural test list — each becomes a unit test)

1. **L1** the destination pill is a pure function of visible facts; the ⇥ flip is a
   one-shot lens that dies with the submission or Escape — never stored across turns.
   (Wording tightened 2026-08-08: the flip deliberately survives typing — the pill
   always displays the post-flip truth, so it cannot surprise; "dies with the draft"
   misread as per-keystroke was a slice-5 false alarm.)
2. **L2** a run claims the alt slot (STEER), nothing else — ASK stays reachable mid-run.
3. **L3** scope chips narrow every destination; they change none.
4. **L4** a frozen block is append-only; staleness is a labelled "re-run as new", never mutation.
5. **L5** ask-about-these-N means these N — the frozen snapshot is the retrieval scope.
6. **L6** every count on screen derives from the set it describes.
7. **L7** decisions are incompressible. Every deck occupant has a minimum honest form, and a
   region under pressure reaches it in a **declared order**: it first *compresses* (a body
   scrolls), then *evicts* (a body is replaced by its minimum honest form entirely). The order
   is authored, not emergent — the list yields before the feed, and the transcript last. A
   minimum honest form may drop elaboration and rows; it may **never** drop a fact or an escape
   hatch. Decisions never compress, never evict, and are never clipped: a held decision that
   cannot fit is the signal that a *body* must yield, not that the decision may leave the screen.
   *(Amended 2026-08-11, §6e.4; implemented and witnessed in §6g C3. The original wording — "only
   the list body is compressible" — described compression alone, so it had nothing to say about
   the case where the column cannot hold the incompressible occupants at all, which is where §6c
   finding 3 lived.)*
8. **L8** the transcript records commitments, not attention. Corollary: a session is
   *named and indexed by its first committed record*. (Deliberate asymmetry: user reading
   leaves no record; agent reading leaves a receipt.)
9. **L9** lock gates the *session*, not a button: identical refusal on every send path,
   draft never swallowed, refusal names its exits.
10. **L10** an empty draft submits nowhere; the pill previews, dimmed.
11. **L11** one fragment, many windows: every region renders from one component of
    (data, options); screens compose, never author.
12. **L12** the rail never yields item-by-item. Mode A (no committed record) = conventional
    session sidebar, identical in every pre-session state, collapses whole. Mode B
    (records exist) = session index. The flip is the L8 corollary.

### The commit choreography (the signature transition, live-proven in the prototype)

Causal order is the meaning: (1) the turn lands, (2) the search freezes into the
transcript, (3) the deck collapses, (4) the rail flips and the name appears, (5) the
answer arrives last. The periphery never moves before the record. ~700 ms; instant
under reduced-motion.

### The sidebar (rail mode A): copied convention, five protected divergences

Copied from the thread-sidebar convention (New session + search-sessions header, pinned
group, time buckets, wholesale collapse, hover actions, active highlight, never morphs).
Divergences — deliberate, do not "fix" back:
1. rows wrap, never truncate; 2. objects are SESSIONS, never "chats" (687);
3. hover = pin + lock, DELETE behind … + confirm; 4. New session emphasised, not filled
(the approval keeps the one-filled-element rule); 5. LOCKED badge exists.
The query trail is NOT a rail occupant — it is omnibox-style history in the input band.

## 2. Component decomposition (from the prototype gallery)

`jf-search-v2` (host) · `jf-sv2-session-sidebar` · `jf-sv2-session-index` ·
`jf-sv2-live-search` · `jf-sv2-frozen-search` · `jf-sv2-answer-card` ·
`jf-sv2-approval-card` · `jf-sv2-run-controls` · `jf-sv2-input-band` ·
`jf-sv2-material-rail`. Gallery fixtures in the prototype are the initial test fixtures;
gallery captions are the component contracts. `AgentSessionController` is *kept and
hosted*, not rewritten (799/audit: it is a genuine framework-agnostic seam).

## 3. Slice plan

**Slice 1 — the skeleton (this tempdoc's implementation scope):**
- New directory `modules/ui-web/src/shell-v0/views/search-v2/` — from scratch, no code
  copied from UnifiedChatView.
- The records core: a typed records array + projection functions (transcript items,
  index nodes, session name). This is the load-bearing novelty; everything else reads it.
- `route()` as a pure function + the pill (L1/L2/L10), input band with one-shot flip.
- Live search wired to the real backend (the thing the prototype cannot validate:
  real latency, real result sets, real counts) with derived count labels (L6).
- Commit: live search → frozen record with derived header + the choreography (L8 corollary
  live: name + index appear at first commit).
- Sidebar mode A (sessions may be stubbed or read from the existing conversation list —
  whichever the integration recipe makes cheap) and index mode B as projections.
- Static placeholders only: agent run, context ledger, lock, material rail.
- The laws as unit tests from day one — the from-scratch payoff: L1/L2/L3/L6/L8/L10/L12
  are directly testable in slice 1; L4/L5 partially; L7/L9/L11 as structure permits.
- Mounted at a hidden route, dev-only. **No rail entry** (578: one task, one window —
  a visible peer would recreate the exact defect 687 deleted).

**Slice 2+ (future, separate passes):** agent-run hosting (deck occupancy per L7 with the
computed floor), context ledger + meter, lock semantics end-to-end (L9 across all send
paths), material rail + citation channels, identity layer application, comparison
campaign vs UnifiedChatView, then sunset per §5.

## 4. Guardrails (repo-law compliance)

- **From-scratch components, shared authorities**: consume the same stores/APIs as the
  shipped window. If/when a search-v2 component consumes `SearchTrace`, it must be
  registered in `governance/execution-surfaces.v1.json` (the execution-surface gate fails
  the build on unregistered referencers). Slice 1 consumes the search response only;
  register at the moment a trace referencer appears.
- Custom-element prefix per `customElementPrefix.test.ts` convention.
- ui-web gate set fires on `modules/ui-web/src/**` edits — run the pushed recipe.
- `check-ui-step-coverage` triggers on new RAIL surfaces; the hidden route adds none.

## 5. Sunset criterion (written up front, per retire-with-a-sweep)

Two windows is a phase, not a state. The comparison ends when **either** Search v2 is promoted —
and **the same PR** sweeps UnifiedChatView and its fingerprints (grep names/paths across code,
config, gates, baselines, docs — label or delete every hit) — **or** the comparison falsifies the
model, search-v2 is deleted in one PR, and this tempdoc records why.
Predictable evasion, pre-named: "we'll keep both for a while" / "a follow-up PR will
sweep it." 742's corpus is follow-ups that never came.

**Promotion criterion (rewritten 2026-08-11, §6g C2).** The original criterion — "passes the full
law suite + a parity checklist + a measured UX audit" — was falsified by its own first audit: the
§6c critical pass found the law suite **green while five laws were violated**, four of them geometry
no unit test can observe. It assumed the suite is a faithful oracle for the laws. It is not, and a
criterion cannot be repaired by running it more carefully. Search v2 is promoted when, and only when:

- **(a)** every law names its **verification tier** in a per-law table — unit / rendered-geometry
  (`ui-proportion-gate` at a named ui-shot step) / measured-audit / *unverified* — and no law's only
  witness is a unit test asserting the DOM structure the implementation trivially guarantees;
- **(b)** every law whose tier is rendered-geometry is enrolled in
  `governance/ui-proportion-baseline.v1.json` with anti-vacuity companions, and green;
- **(c)** the §5b parity checklist is complete, or each gap consciously superseded;
- **(d)** an independent measured UX audit (auditor ≠ implementer, axe + contrast oracle, live)
  passes at **both** the roomy and the short camera;
- **(e)** the `governance/sandbox-coverage.v1.json` exemption row has moved to a real tier.

A law marked *unverified* does not block promotion, but it **must be listed in the promotion PR** —
the point is that the blind spot is stated, not that it is absent. The same standard applies to the
shipped window it is compared against, so the "comparison falsifies the model" branch stays live.

**Per-law verification tiers (opened 2026-08-11; completed by the promotion PR).** Recorded as the
work lands, so clause (a) is a running obligation rather than a scramble at the end:

| Law | Tier | Witness |
|---|---|---|
| L7 (short height, run controls) | **unverified** → measured-audit, one-time | Not gateable: a fixtured run cannot be held in flight (§6g C0). Live pass only. |
| L13 (rail floors/ceilings) | rendered-geometry | `search-v2-window` — `.rail` max/min, `.centre` floor |
| L13 (the axis the boundaries sit on) | unit + rendered-geometry | cascade + node-identity witnesses; `.rail maxWidthPx` |

## 5b. Comparison-campaign scaffolding (authored 2026-08-08; produces §5's evidence, does not make its decision)

**Parity checklist** — v2 must match or consciously supersede each before promotion
(checked = present in v2 as of slice 5):
- [x] live search + facets + honest counts (shared card)  ·  [x] frozen/committed searches
- [x] grounded ASK with citations + grounding stats  ·  [x] answer refusals (locked/error)
- [x] agent run hosting: feed, tool cards, approvals, steer, halt, budget
- [x] document reading pane + citation follow  ·  [x] scope chips (file + set)
- [x] sessions sidebar + session index  ·  [x] lock discipline on all paths
- [x] context meter  ·  [x] zero-result + AI-offline honesty  ·  [x] small-window (≤820px)
- [ ] conversation resume/branch/edit-retry (v2 renders history read-only; no branch/edit)
- [ ] export  ·  [ ] compaction/context floor + exclusion ledger  ·  [ ] extract tier (schema)
- [ ] workflow trigger  ·  [ ] Simple/Detailed projection toggle
- [ ] walkthroughs/empty-state registry integration, command-palette actions

**Procedure** (dev stack + `serve-worktree-fe`, model active, same corpus): run each task
on BOTH windows, record task success, count honesty (every on-screen number vs its set),
and interaction dead-ends: (1) find a named file; (2) refine with facets; (3) open + read
a result; (4) ask about a committed set, follow a citation; (5) delegate a bounded task,
approve one step, steer, halt; (6) hit a lock mid-flow; (7) zero-result query; (8) all of
it at 790px height. Measured pass: axe + the contrast oracle via the ui-shot harness
(`/ui-check`), whole-screen, both windows, auditor ≠ implementer (slice-execution
discipline). Score against §6's defect-class table: each class must be demonstrably
absent in v2 and (where applicable) still present in the shipped window.

**Output**: a dated results table appended here; §5's sunset call then needs only the
owner's judgment on top of it.

## 6. Defect classes in the shipped window this design kills by construction

| Shipped-window defect (evidence) | Killed by |
|---|---|
| count-truthfulness recurrence (597 → 690 I1 → 817 S5) | L6: derived labels + count audit |
| stale search-results-as-transcript (817 S6) | freeze model (L4/L8) |
| New chat state-gated/unreachable (obs. `UnifiedChatView.ts:2114`, seen 6×) | sidebar header: always-visible New session |
| affordance/schema desync on card fork (obs. `:3735`) | pure `route()` + one-shot flip (L1) |
| locked-path text loss (obs. `:5673`) | L9 session-level gate |
| three parallel conversation models (`mergedTimeline:3816`) | one records array (L11) |
| triplicated reset protocol (`settleTransients:1077` et al.) | projections have no state to reset |

## 6b. The presentation pass (slices 4–5, designed 2026-08-08; owner additions incorporated)

Scope: bring the in-app window to the prototype's spatial/motion discipline, plus two
owner-directed principles, formalised as laws:

13. **L13 — every movable boundary is clamped by the minimum honest forms on both
    sides.** The deck's vertical grip generalises: BOTH rails (session rail, material
    rail) gain horizontal grips. Clamps derive from content (rail floor = its collapsed
    strip / minimum readable form; the centre column never drops below the reading
    measure). Difference in memory: **rails remember** (a width is a user preference,
    persisted), **the deck resets** (a height is per-session-shape). Widths must respect
    the 816 sizing-role register; double-click returns any boundary to automatic.
14. **L14 — the resting surface shows the identifying minimum; elaboration extends on
    hover AND keyboard focus, never by default.** Applies to sidebar rows (resting:
    title + LOCKED; extended: meta + actions), index nodes (extended: record detail),
    context meter (extended: breakdown), frozen headers and receipts (extended:
    timings/mode). Hard boundary: **honesty facts never hide behind hover** — counts,
    verdicts, LOCKED, grounding stay resting-visible; only elaboration extends. Focus
    parity is mandatory (hover-only fails accessibility); the shared results card
    already follows this principle and is not forked.
    Hard boundary (amended 2026-08-11, §6e.4; implemented in §6g C5): honesty facts never hide
    behind hover, where an honesty fact is a claim **about the set this surface is currently
    describing** — its counts, its verdicts, its LOCKED state, its grounding. A fact about
    *another* object (a prior session's message tally) is meta, and meta may extend; so may a
    timing. The boundary's test is therefore **structural and universal, never a list of ids**,
    which the implementation pays for by making the class carry the role: `.count` marks a
    current-set fact and `.meta` marks elaboration, so `.ext .count` must be empty by
    construction. The original clause said "counts … stay resting-visible" while L14's own
    sidebar row designated meta as extended — a contradiction its test resolved by enumerating
    three ids that happened to rest, and passing while a count sat inside an `.ext` one selector
    away.

Slice 4 (motion + vertical space): commit choreography (record lands first, periphery
follows, ~700 ms, reduced-motion instant); deck grip with computed floor + collapse to
count line; unhappy states (zero-results with n=0 escalation labels, AI-offline pill
honesty); small-window pass at ~790 px bound to the 814 height-budget substrate.

Slice 5 (horizontal space + hover + input): L13 rail grips; L14 hover/focus extension
sweep; omnibox query-trail dropdown (pinned + recent searches — the rail never shows
them, L12); citation-follow landing (document pane at cited range, land-strong-then-
settle); keyboard pass (⌥↑/⌥↓ index nav — never while typing, Escape order); copy audit
(one verb per action, user-side naming).

Parallel tracks (not in these slices): identity directions as swappable theme files
(owner picks); comparison-campaign scaffolding (§5 evidence); matchCount-inversion
diagnosis (observations inbox item).

## 6c. Critical-pass findings (2026-08-11)

A refute-first critical pass read all of `views/search-v2/` (the 2,758-line host in full),
this tempdoc's laws, and the shared modules the window consumes, against the state of `main`
at `14211df2` (slices 1–5). Thirteen findings survived refutation. Severity order:
broken behaviour > law violation > degraded UX > hardening. Each was verified by re-reading
the implicated path end to end; the *refuted* candidates are listed after, because what did
NOT break is evidence too.

1. **The window's three regions stack vertically.** `.body` and `.win` are the same element
   (`SearchV2View.ts:1916` `<div class="body win">`); `.body` declares `flex-direction: column`
   (`:1049-1053`) and `.win` (`:1054-1059`) never declares one, so `column` wins uncontested.
   Slice 1 (`608fb330`) had `.win { display:flex }` and no `.body` override (row, correct);
   slice 4 (`14211df2`) added the `.body` rule for one-scroller-per-region and flipped the axis.
   Consequence: the rail renders as a full-width band above the centre column, both `button.vgrip`
   grips as full-width bars, the document pane below rather than beside. It also poisons L13 —
   `railBounds` (`:773-792`) reads `win.width` and `rail.width` as the same number, so
   `sessionRailCeiling` goes negative and `clampRailWidth` snaps to the 52 px floor on the first
   drag in either direction, then persists 52.
2. **Commit-during-stream orphans the first ask; a stale terminal can fill a NEW session's slot.**
   `commit()` (`:1709-1738`) has no in-flight guard; `dispatchAsk` (`:1760-1761`) overwrites
   `askAbort` without aborting it and overwrites `streaming`; `onDone`/`onError` (`:1777-1790`)
   null both regardless of which stream they belong to. Record ids are positional
   (`records.ts:237-243`, `r${n}`) and reset with the session (`clearRecords :1840`), and
   `fillSlot` (`records.ts:294-304`) matches on kind+id alone. Chain: commit Q1 (slot `r2`,
   stream A) → commit Q2 (slot `r5`, stream B) → B finishes first, nulling `askAbort` and
   orphaning A → New session (aborts `null`) → commit Q3 (slot `r2` again) → A's terminal lands
   **Q1's answer under Q3's question**. Milder, no new session needed: B's streamed text stops
   rendering the moment A terminates (`onDelta :1772-1775` guards on `this.streaming?.id`).
3. **L7 violated: run controls are clipped off screen at short heights, with no scroller.**
   `.centre { overflow: hidden }` (`:1070-1078`), `.deck { flex: 0 0 auto }` (`:1086-1089`,
   shrink 0), and `.deck.fills` (`:1091-1093`) applies only while the transcript is empty
   (`deck() :2250`). A delegate appends a user-turn (`:564-580`), so the deck stops being
   shrinkable exactly when the run controls appear. At ~790 px the centre column is ~430 px
   (`deckSizing.ts:28-34`) while the deck's short-viewport content is ~540 px — Halt is clipped.
   Same class via resize: `deckHeightPx` is clamped only at gesture time (`:697-703`, `:737-743`).
   **→ THRESHOLD CORRECTED 2026-08-11 by the C2 capture (§6g).** The mechanism above is confirmed —
   the deck is `flex-shrink: 0` and `.centre` is `overflow: hidden`, so the deck wins and the excess
   is clipped — but the arithmetic overstated when it bites, and it did so because it trusted a
   number that is itself wrong. Measured at 1366×790: the centre column is **642px**, not the
   "~430px" `deckSizing.ts:28-34` asserts, and the deck without a run is 375px (transcript 256px,
   comfortably above its 160px short floor). Adding the run's two occupants at their short caps
   (feed 10rem + controls ≈ 250px) puts the deck at ≈625px against 642px — so at 790px the failure
   is the transcript **starved toward zero**, not the controls clipped. Clipping begins below
   ≈740px of viewport height, where the column can no longer hold the deck at all. The finding
   stands and so does the eviction remedy (which is what makes the deck degrade instead of either
   starving its neighbour or overflowing); what changes is that the deck-heavy state is a
   *continuum* of the same defect rather than a cliff at one number, and that the stale ~430px
   comment is itself repaired in the boundary work.
4. **Tab is a keyboard trap in the omnibox** (WCAG 2.1.2). `onKeydown` (`:1645-1653`)
   `preventDefault()`s Tab whenever the draft is non-empty, with no `shiftKey` check — neither
   Tab nor Shift+Tab can move focus, and nothing advises the escape (empty the field).
5. **L9's "New session with this text" exit destroys the transcript and unlocks nothing.**
   `newSessionWithDraft` (`:1867-1872`) → `clearRecords` (`:1836-1864`) wipes the records and
   clears `lockRefused`, but never `sessionLocked` (`:299`, cleared only by an `unlocked` poll).
   The cause is global, not per-session (`readinessNotice.ts:271-277`). Result: transcript gone,
   both send buttons still disabled (`:2307`, `:2319`), and — because `lockRefused` was cleared —
   `lockRefusal()` (`:2691-2712`) renders nothing, so no reason remains on screen.
6. **L8: the Delegate button is live mid-run.** The button (`:2316-2324`) is gated only on
   `sessionLocked`; only the ⌘⏎ *key* path checks `runInFlight` (`:1681-1686`), and
   `directiveAvailable('initiate')` is unconditionally `true` (`runControlIntent.ts:53-55`), so
   `ctrl.send()` runs on a live run (`AgentSessionController.ts:1349-1370` resets iterations,
   budget, gates and the abort controller). `delegate()` resets `runEntryStart` and clears
   `haltRequested` → two `user-turn` records but ONE receipt, counted from the wrong origin, with
   a pressed Halt silently downgraded to `completed`, and the feed loses the first run's entries.
   The inverse: one click while a *sibling* window's run is live sets `runOwned`, so that run's
   terminal writes a receipt into this session. Copy defect on the same control: it is labelled
   "Delegate ⌘⏎" while ⌘⏎ means STEER during a run.
7. **L13: a remembered rail width is never re-clamped against the actual window.**
   `readStoredRailWidth` (`railSizing.ts:131-142`) validates only against the static floor and
   `SESSION_RAIL_CEILING_PX * 4`, never against `sessionRailCeiling(...)` — while its own docblock
   (`:117-130`) and the caller's comment (`SearchV2View.ts:396-398`) both state that it does.
   No `resize` listener exists; `subscribeShortViewport` is `(max-height: 820px)`
   (`compositionLayout.ts:62`), the block axis only. A 448 px rail remembered from a wide window
   reopens at 700 px with `.centre` (`min-width: 0`) at ~240 px, under `CENTRE_MIN_PX = 384`.
8. **L4: every frozen block renders a "Search again" button that does nothing.**
   `ResultsCard` always renders the fork affordance and emits `card-fork`
   (`ResultsCard.ts:534-556`, `:593`); the view listens for `card-open` and `card-facet-toggle`
   only (`:2198`, `:2353`, `:2355`). L4 *is* "staleness is a labelled re-run as new" — the
   affordance that performs it is the unwired one, while the view's own comment at `:1890-1893`
   states the rule it broke. `card-scope-file` (`ResultsCard.ts:328`) is dead the same way.
9. **Escape inside the query trail re-opens it.** `onTrailKeydown` (`:1019-1026`) closes the
   trail then focuses the input; because `moveTrailCursor` (`:1007-1016`) had moved focus onto a
   row, `.focus()` fires a real focus event and `onDraftFocus` (`:975-980`) — seeing the empty
   draft that opened the trail in the first place — reopens it. The first press is a no-op.
10. **The commit choreography re-plays on every previously committed record.** The keyframe
    selectors are unscoped (`:1562-1578`) and every frozen record carries `.frozen` (`:2186`), so
    the Nth commit re-runs the entrance on all N records — older blocks drop to `opacity: 0`
    (fill `both`) and fade back in.
11. **The collapsed rail hides "New session", and the collapse is persisted.** `railStrip`
    (`:1964-1987`) keeps only the expand chevron and a count; New session lives in `sidebar()`
    (`:1999-2001`). `railCollapsed` is restored at mount (`:399-401`), so §6's "always-visible
    New session" claim fails for every future session once the rail is dragged under 128 px.
12. **L14's hard-boundary test hand-picks the counts that rest.** `presentation.test.ts:748-767`
    enumerates four ids and names "grounding" without asserting it; the sidebar row's message
    count sits inside a `.ext` (`:2022-2024`). L14's own prose designates sidebar meta as
    extended while the hard boundary says counts rest — the law contradicts itself here.
13. **Hardening.** (a) `aria-describedby="sv2-query-trail"` is set whenever `historyOpen`
    (`:2265`) but `queryTrail()` returns nothing when both sections are empty (`:2375-2378`) — a
    dangling reference on first run. (b) `railBounds` (`:778-782`) subtracts grip widths but not
    `.win`'s `gap` (`:1057`) across up to five children, so both ceilings run generous.

**Refuted after genuine scrutiny** (recorded so the same ground is not re-walked): subscription
completeness (8 subscribe sites `:364-410` against 8 unsubscribe sites `:421-438`, both host
listeners removed, the choreography timer cleared on disconnect); Lit clobbering the mid-gesture
inline size (the `style=` binding value is unchanged during a drag, so the AttributePart dirty
check skips the commit and the directly-written style survives); charspan-only citations
(`onCitationSelect :944-952` requires both line numbers finite and otherwise passes `null`);
⌥↑/⌥↓ vs typing, including inside the document pane's shadow root (`typingSomewhere :910-914`
reads `composedPath()[0]`) and vs European layouts (AltGr chords produce characters with letters,
not arrows); `records.ts`'s L4/L6 core (Σ-by-construction header count, identity no-ops for
late/duplicate terminals, absent measurements rendering nothing); scope-chip duplication
(`addScopeChip` dedupes); a nested scroller in the reading pane (the pane is `height: 100%` in a
stretched flex item, so `.reading` never scrolls); `projectContextHorizon` misuse (the zeroed
fields are unread).

**Why the existing tiers did not see these.** Five findings (3, 4, 5, 9, 12) have a covering test
that passes; four (1, 3, 7, 13b) are geometry that the unit substrate cannot observe at all
(happy-dom reports every rect as 0×0) and that this surface's ui-shot exemption removes from the
measured tier. The slice-5 log records the moment it bit — *"the screenshot path was down"* — and
finding 1 is precisely what one screenshot would have caught.

## 6d. Theorization on remediation (2026-08-11) — directions, not design

Written before design is settled. The purpose is to name the forks, the generative causes, and
the sequencing constraints, so the design pass argues about the right things.

### The findings are five causes, not thirteen bugs

Grouping by *generative cause* rather than symptom is what makes this tractable, and it changes
the unit of work from thirteen edits to roughly three bundles.

**(A) Geometry has no oracle here.** Findings 1, 3, 7, 13b. Five slices of explicitly *spatial*
work — L13 is entirely spatial, L7's incompressibility is spatial — shipped with the spatial
verification tier switched off by design: `railSizing.ts:28-31` and `SearchV2View.ts:59-66` both
state the ui-shot/proportion-register exemption and defer it *to the §5 cutover*. But the cutover
is gated on the comparison campaign, which is gated on the window being spatially correct — a
circular deferral, and these four findings are its price. Three directions, not exclusive:
*(i) adopt the tier now* — a deterministic ui-shot step for search-v2, which makes the proportion
rows authorable and retires the `sandbox-coverage` `tier: exempt` early; *(ii) bring a cheap
oracle into the unit tier* — the pure modules already take `measure` as a parameter so the walk is
testable (`deckSizing.ts:12-14`), but nothing tests the *composition*; asserting on the
stylesheet text for a few load-bearing invariants ("the element carrying `.win` resolves
`flex-direction: row`"; "`.deck` is shrinkable or `.centre` scrolls") is lint-shaped, runs in the
unit tier, and catches findings 1 and 3; *(iii) make the collision unrepresentable* — finding 1
exists only because a scroll-policy role (`.body`) and a track-layout role (`.win`) were merged
onto one node, so a rule like "a surface's `.body` is a policy wrapper, never also a track" costs
nothing and removes the class. (iii)+(ii) look like the cheap now; (i) is the honest eventual.

**(B) Identity is positional.** Finding 2. `records.ts:26-27` justifies length-derived ids by
determinism — determinism bought at the cost of *global* uniqueness. Three conditions compose:
ids recur across sessions, `fillSlot` matches on kind+id alone, and an async terminal outlives the
array it was minted against. The forks:
- *Patch* — abort the previous ask in `commit()` and guard each terminal on `streaming?.id`.
  Closes the observed path, leaves the class alive for the next async tier (extract, workflow).
- *Session epoch* — carry an injected epoch in the id or alongside every continuation, so a stale
  terminal structurally cannot match. Determinism survives if the epoch is a parameter, the same
  discipline `sessionBuckets` uses for `now`.
- *Opaque slot handle* — the commit returns a token the terminal must present; `clearRecords`
  invalidates it. This is the epoch with the mechanism hidden, and arguably the honest shape: an
  id's only job is "which slot", and a caller should not be able to name a slot it does not own.
- *The reframe worth taking seriously*: `streaming: StreamingAnswer | null` and
  `askAbort: AbortController | null` are **singular fields modelling something the UI permits to
  be plural**. Every symptom — the delta guard, the nulled abort, `contextPromptTokens` from the
  wrong turn — falls out of that one mismatch. So the real fork is **serialise vs pluralise**:
  enforce one ask at a time (smaller, and arguably truer to L4/L8 — committing again while the
  last commitment is unanswered is incoherent), or accept concurrency and make the state per-slot
  (truer to L2's spirit that escalation stays reachable). The serialise branch carries a trap:
  it must *refuse legibly*, never silently disable, or it recreates §6 row 3's state-gated
  affordance defect — which is finding 5's shape exactly.

**(C) An affordance and its guard are computed in different places.** Findings 5, 6, 8, and part
of 4 and 11. The window already owns the antidote and applies it in exactly one region:
`directiveAvailable` is described as "the affordance's visibility and the dispatch share ONE
predicate" (`runControlIntent.ts:42-47`) and `runControls` consults it (`:2561-2563`). The
direction is to extend that discipline outward — one `sendAvailability(rung)` consulted by both
the button's disabled/aria state and the dispatch, and the same treatment for a refusal's *exits*
(finding 5 is an exit whose precondition nobody evaluates). Two caveats: `initiate` returning
unconditional `true` is the shared seam declining to model the one lifecycle fact that mattered,
so part of this is upstream and the shipped window may depend on today's answer; and finding 8 is
a different animal — an unwired event on a *shared* component, which raises the reusable-component
question of whether a card should require its host to declare which affordances it supports
(`.affordances=${[...]}`) rather than emitting into the void. That has reach beyond 818: any host
adopting `jf-results-card` inherits every affordance it renders, wired or not.

**(D) Tests assert a proxy for the property instead of the property.** Findings 3, 4, 5, 9, 12 all
have the identical shape: the test asserts the DOM fact that *would* be true if the behaviour
held — and that fact is exactly what the implementation already guarantees. Tab-reachability is
tested by querying tabbable elements instead of pressing Tab; off-screen-ness by `closest()`
instead of visibility; "the user can send again" by the refusal being *gone*; Escape by a test
that never presses Escape. Two transferable moves: ask, per law, *what is the smallest observation
a user could make that distinguishes held from broken* — and where that is not reachable in the
unit tier, mark the law unit-unverifiable rather than let a proxy stand in; and prefer **quantified
over enumerated** assertions, because `querySelectorAll('.ext .count').length === 0` cannot be
outgrown by a new region while a four-id list can. If this survives design it is a candidate
postmortem handle (working name: `proxy-assertion`) — a sibling of `audit-without-test`.

**(E) Memory outlives the context that justified it.** Findings 7, 10, 11. L13's "rails remember"
is right; the missing half is that *a preference is re-validated against present reality, not
replayed*. Note the branch hiding inside finding 7: `readStoredRailWidth` intends to **discard**
an out-of-range memory, which is the worst of the three options — it loses the preference
permanently even though the window may widen again. **Clamp-on-apply** (keep the memory, apply the
clamp) is the honest one; **clamp-and-rewrite** silently edits the user's choice. This generalises
to any persisted UI geometry, including the not-yet-built material rail.

### The specific forks, called out

- **Finding 3's answer.** Three options and they are not equivalent. A *shrinking deck* is the
  smallest and preserves the transcript's priority, but it lets the list body vanish before the
  controls do unless the shrink order is authored. A *last-resort scroller on `.centre`* rescues
  visibility but directly contradicts 814 §D3 (one scroller per region) and L7's whole point —
  it makes a decision scrollable, which is what L7 forbids. A *rethink of deck occupancy at short
  heights* is the most interesting: at 790 px the deck is trying to hold an input band, a results
  list, a run feed AND a controls row, and L7 already says only the list body is compressible —
  which is a statement about *compression*, not about *eviction*. The question the design should
  answer is whether a short window should compress the feed too, or evict a body entirely into its
  minimum honest form (the feed's count line), leaving band + controls. That is a law refinement,
  not a CSS fix.
- **Finding 7's answer.** The re-clamp mechanism for the deck (finding 3's resize half) and for
  both rails is **one mechanism**: a ResizeObserver reconciling all three boundaries against
  freshly measured bounds. Fixing them separately builds it twice. This is the strongest argument
  in the whole pass for bundling rather than itemising.
- **Finding 4's answer.** This is a law question, not a bug question — Tab-as-flip came from the
  prototype. Options: release the lens after one flip; guard `shiftKey`; move to a modifier chord;
  or **make the pill itself the affordance** — it is already on screen, and L1 says the pill is
  the truth-teller, so clicking the thing that tells you the truth is arguably the most honest
  binding and converts a keyboard-only invisible affordance into a visible one. Whatever is
  chosen must not quietly re-narrow the L1 wording that slice 5 deliberately widened ("the flip
  survives typing"). Note that guarding `shiftKey` alone does not clear the trap.
- **Finding 12's answer.** Three resolutions: *settle the law* — declare that the hard boundary
  covers counts *about the set this surface describes* (L6's own framing) and not counts about
  another object entirely, which is a defensible and clean distinction that would legitimise the
  sidebar's message tally; *settle the surface* — move the count out of `.ext` and let only the
  bucket name extend; or *settle the test* — quantify it and let it fail, forcing one of the
  first two. The law and the test are not really alternatives: quantifying the test is what makes
  either settlement stick, so the choice is really about which of the first two.
- **Finding 8's scope.** Wiring `card-fork` and `card-scope-file` in the view is a two-handler
  fix. The broader direction — hosts declaring supported affordances — is a shared-component
  change and should be weighed on its own evidence, not carried in on 818's back.

### Sequencing

Finding 1 does more than "gate visual reasoning": it gates the **evidence** for 3, 7, 10, 11 and
13b. While the axis is wrong, the rail clamps compute against a full-width rail, so finding 7's
severity cannot even be measured; and finding 3's magnitude (does the deck really overflow at
790 px?) is only observable once the row axis is restored. The natural shape is therefore a
**Phase 0 — finding 1 plus a geometry oracle** — landed and *visually* verified before any other
spatial work is designed, then the spatial bundle (3, 7, 13b, and 10/11's persistence half), with
the state/lifecycle bundle (2, 5, 6) and the law/test bundle (4, 9, 12) independent of the axis
and free to run in parallel. Corollary worth stating plainly: **finding 3 should be designed after
finding 1 lands, not before** — designing a clamp against measurements taken in a broken axis is
how the second round of findings gets made.

### What this points at beyond 818

- **§5's sunset criterion is falsified as written.** It ends the two-window phase when v2 "passes
  the full law suite + a feature-parity checklist + a measured UX audit". This pass found the law
  suite *passing* while five laws were violated in ways the suite structurally cannot see. The
  criterion assumes the law suite is a faithful oracle for the laws. The direction — not a
  decision here — is that **each law should name its verification tier**, and a law whose only
  oracle is a unit test asserting DOM structure should be marked as such, so the cutover decision
  does not inherit this pass's blind spot.
- **The "minimum honest form" pattern has an unstated obligation.** L7 (the list yields to a count
  line) and L13 (the rail yields to a strip) both say a region at its floor renders its minimum
  honest form. Finding 11 shows a minimum that dropped an affordance §6 promised is always
  present. The refinement worth considering: *a minimum honest form may drop elaboration and rows;
  it may not drop a fact or an escape hatch.* The rail strip keeps the count (fact ✓) and the undo
  (escape from the collapse ✓) but drops the escape from the *session*.
- **A prose invariant beside code reads as verified.** This window is unusually heavily commented,
  and that is mostly why this review could move fast — but findings 1, 3, 5 and 7 each have a
  docblock asserting the invariant the code breaks, twice in two files for finding 7. A cheap
  discipline worth weighing: where a docblock states an invariant, either a test is named for it
  or the docblock says it is unverified.
- **Possible follow-up tempdoc (sketch only, not claimed).** "Geometry verification for dev-gated
  surfaces" — the ui-shot/proportion-register exemption for DEEPLINK/DEVELOPER surfaces, its cost
  as measured by findings 1/3/7/13b, and whether the exemption should carry an expiry or a cheaper
  substitute tier. It has reach past 818 (any such surface inherits it). The next free tempdoc
  number at the time of writing was **#820**; whether this is a separate tempdoc or a §6c-driven
  recommendation feeding the §5 cutover is a design-pass call, not a theorization call.

## 6e. Remediation design (2026-08-11) — Phase 0 + three streams

Designed against §6c's findings and §6d's five causes. The investigation pass changed one of the
theorized directions materially; that correction is stated first because the rest depends on it.

### 6e.0 The correction: the geometry oracle already exists, and this window opted out of it

§6d proposed a "lint-shaped stylesheet assertion" as the missing geometry oracle. Investigation
found that a **rendered-geometry oracle for exactly this defect class already exists and is already
gated** — `governance/ui-proportion-baseline.v1.json` + `jseval ui-proportion-gate`, built by 814 D7
and extended by 816 §4. Its constraint vocabulary already contains every kind these findings need,
and the gate implements each with a named failure code (verified in
`scripts/jseval/jseval/ui_proportion_gate.py:317-333`):

| §6c finding | Existing constraint kind | Gate failure code |
|---|---|---|
| 1 — axis flip (rail spans the window) | `maxWidthPx` on `.rail` | `SPRAWLED` |
| 1 / 7 — centre column starved | `minWidthPx` on `.centre` | `STARVED` |
| 3 — run controls clipped below the fold | `maxBottomPx` on `.run-controls` | `CLIPPED` |
| 3 (L7's direct claim) | `nonScrollableSelectors: [".run-controls"]` | `IS_SCROLLER` |
| one-scroller discipline | `maxScrollableRegions` + `minScrollableRegions` | `NO_SCROLLER` |

`maxBottomPx` is not an analogy — 814 D6/D7.2 added it to close round 8's F5, *the composer clipped
below a short viewport*, which is finding 3 with a different element. The register also carries an
explicit **anti-vacuity doctrine**: "an element with no constraint, or a selector missing from the
captured geometry, is an ERROR, never a silent pass."

So the design is **enrollment, not invention**, and the deferral is the thing that must go. Note the
circularity the findings exposed: `railSizing.ts:28-31` and `SearchV2View.ts:59-66` defer enrollment
to the §5 cutover, but the cutover is gated on the comparison campaign, which is gated on the window
being spatially correct. The clamps' own numbers (`CENTRE_MIN_PX`, `DOCUMENT_RAIL_FLOOR_PX`) were
*borrowed from this register by prose comment* while the surface declared no rows — the numbers came
in, the judgment stayed out.

A unit-tier check still earns a place, but as a **fast witness of the cause**, explicitly not a
second authority (the repo's own one-authority doctrine, 814 D5 / 816 §4a.3).

### 6e.1 Phase 0 — the axis, then the oracle, visually verified

**P0.1 — separate the two layout roles (finding 1).** The cause is one node carrying two roles:
`surfaceLayout` owns `.body` as a *scroll-policy* region, and 818 hand-overrode it into a *track*
(`<div class="body win">`). Verified unique — no other surface co-classes `.body`. `surfaceLayout.ts`
already expresses policy as **declared variants** (`surfaceLayoutStyles`, `surfaceScrollLayoutStyles`,
`data-fill="reading"` on the host), so the conforming fix is a third declared variant — *the body does
not scroll; the surface's own regions do* — with `.win` becoming a **child** of `.body`. Two nodes,
two roles, the collision unrepresentable.
Tradeoff recorded honestly: a variant for one consumer is more structure than nesting the div locally.
It is chosen because the local hand-override *is* the hazard and it is available to every surface;
`surfaceLayout`'s own docblock exists to kill "two authorities for one concept". If derisk judges one
consumer too thin, nesting the div is the fallback and the rule then lives only in the P0.3 test.

**P0.2 — enrol in the rendered tier.** Two ui-shot steps (`ui_step_index.json`, covered by
`check-ui-step-coverage`) and their register rows:
- a roomy step (rail + centre + document pane open) carrying `.rail maxWidthPx` (derived from the
  code's own `SESSION_RAIL_CEILING_PX` — a rail wider than that is not periphery), `.centre
  minWidthPx: 384`, `.document-pane minWidthPx: 384`, and the scroller floor/ceiling pair;
- a short step (~790 px block, a run live) carrying `.run-controls maxBottomPx` +
  `nonScrollableSelectors`, plus `requiredSelectors` for the controls so a failed render cannot pass
  vacuously.
Every row cites the function that produces its number, per 816 §3. `governance/sandbox-coverage.v1.json`'s
`tier: exempt` row moves here — the row's own text says it must.

**P0.3 — the fast structural witness (unit tier).** Both halves use idioms that already exist, one of
them in this window's own test file:
- **axis**: assert the cascade resolves `flex-direction: row` on the track element
  (`getComputedStyle` on a shadow-root element for a *cascade* property is established —
  `UnifiedChatView.test.ts:2098` does it for `fontStyle`; happy-dom resolves cascade, not geometry).
  **Derisk item**: happy-dom's cascade support for `adoptedStyleSheets` + class selectors is unverified.
  Fallback if it does not resolve: the regex-block idiom (`compositionLayout.test.ts:209-225`) asserting
  the surface's own CSS declares no `display`/`flex-direction` on `.body` at all.
- **catalog-verbatim**: the clamp constants must *equal* the register rows they cite. Today the link is
  a prose comment; this makes it a compile-time-adjacent fact, which is 816 §4a.3's "one authority
  renders AND judges" and the repo's named `catalog-verbatim` failure handle. This would be the first
  `modules/ui-web` test to read the register — a small precedent, set deliberately.

**P0 acceptance is a screenshot.** The whole class shipped because none was taken; P0.2 is what makes
that repeatable rather than a one-off.

### 6e.2 Stream A — Boundaries: one reconciliation seam (findings 3, 7, 13b, 10's persistence half)

**A.1 One entry point, three callers.** All boundary policy moves into a single **pure** function
taking the measured box, the current chosen values and the deck's occupancy, and returning the
reconciled state for *all three* boundaries at once. It is called from exactly three places — **mount
(restore), resize (observe), gesture end (adopt)** — which is what makes "the clamp was only evaluated
at gesture time" structurally impossible rather than merely fixed. This extends, not replaces,
`deckSizing`/`railSizing`: their pure-math-plus-caller-supplied-measurement split is already the right
shape (`deckSizing.ts:12-14` says why), and it is the scattered *invocation* that dies, not the math.

**A.2 The observer conforms to the existing controller shape.** A thin Lit `ReactiveController` owns
the `ResizeObserver`, modelled on the two sibling controllers that already do this
(`adaptiveBar.ts:85-177`, `adaptiveDensity.ts:105-173`): create in `hostConnected`, `disconnect()` in
`hostDisconnected`, rAF-coalesced recompute, guarded by `typeof ResizeObserver === 'undefined'`.
Consequence to state plainly: **happy-dom has no `ResizeObserver`**, so the controller is inert in unit
tests — which is precisely why all policy lives in the pure function, where it is fully testable, and
the controller is a trigger with no judgment in it.

**A.3 Clamp-on-apply, never discard.** Storage returns the remembered value verbatim; reconciliation
clamps it against measured bounds at apply time; **the memory is never rewritten by a clamp**. Widening
the window restores the preference; only an explicit reset forgets. This supersedes
`readStoredRailWidth`'s discard branch, which loses a preference permanently for being briefly
un-honourable — the worst of the three options §6d identified.

**A.4 Short-height behaviour is decided by the amended L7** (§6e.4): the deck **evicts** rather than
clips. Under pressure the list body yields first (already true), then the run feed yields to its own
count line, then the transcript yields; the controls never yield. The deck therefore keeps its
incompressible floor and eviction absorbs the shortfall — clipping stops being reachable.
`.win`'s inter-region gaps enter the available-width term (finding 13b), in the one place that computes it.

### 6e.3 Stream B — Send-path arbitration (findings 2, 5, 6, 8)

**B.1 One predicate for every send path.** `runControlIntent`'s stated principle — "the affordance's
visibility and the dispatch share ONE predicate" (`runControlIntent.ts:42-47`) — is today honoured only
by the run controls. It extends to a single window-level send predicate consulted by **both** the
rendered state of a send affordance and its dispatch. Refusal reasons: the session lock (L9), a run in
flight (finding 6), an ask in flight (finding 2).

**B.2 No send affordance is ever `?disabled`.** This is the crux binding findings 5, 6 and 2 together.
A disabled button gives no reason and no exit — §6 row 3's defect class, arriving by a different door.
The window already argues this itself for the AI-unavailable case ("stay OPERABLE while the model is
down… the reason is a VISIBLE line below, referenced by `aria-describedby`, never a `title`",
`SearchV2View.ts:2298-2303`); the lock is the one case that violated the window's own rule. So
`?disabled=${this.sessionLocked}` is removed and the lock routes through the legible-refusal path that
already exists. Finding 5's exit is then re-designed to L9's standard: an exit that cannot change the
outcome is not offered, and the reason survives the exit rather than being cleared with it.

**B.3 Serialise the ask, and harden identity anyway (finding 2).** One ask at a time; a second commit
mid-stream is refused legibly, naming why and what to do. The covering test asserts **the refusal is
visible**, not merely that a second dispatch did not happen.
Serialising alone is insufficient and the design says so: it does not stop `clearRecords` from resetting
positional ids while a terminal is still in flight. A monotonic **session epoch**, minted where the
records array resets and captured by each dispatch, is checked at every terminal before it touches
records. Injectable, therefore testable — the discipline `sessionBuckets` already uses for `now`. This
closes the class rather than the observed path, which is the difference §6d flagged between patching and
fixing. Under serialisation the singular `streaming`/`askAbort` fields become *correct* rather than a
latent plural; `refuseLocked` must abort rather than merely drop the handle.

**B.4 Wire the shared card's remaining events (finding 8).** `card-fork` is L4's "re-run as new" act;
`card-scope-file` is L3's narrowing. Two handlers. The broader idea — a shared card requiring its host
to *declare* which affordances it supports — is recorded as reach (§6e.5) and deliberately not built:
one consumer, and it changes a component other surfaces depend on.

### 6e.4 Stream C — Laws and their witnesses (findings 4, 9, 10, 11, 12)

**L7, amended.** Supersedes §1's L7 wording:

> **L7** — decisions are incompressible. Every deck occupant has a minimum honest form, and a region
> under pressure reaches it in a **declared order**: it first *compresses* (a body scrolls), then
> *evicts* (a body is replaced by its minimum honest form entirely). The order is authored, not
> emergent. A minimum honest form may drop elaboration and rows; it may **never** drop a fact or an
> escape hatch. Decisions never compress, never evict, and are never clipped — a held decision that
> cannot fit is the signal that a *body* must yield, not that the decision may leave the screen.

One amendment, three findings: it decides finding 3 (evict, don't clip), finding 11 (the collapsed
rail's strip must keep New session — an escape hatch), and it states the obligation the "minimum honest
form" pattern was carrying implicitly.

**L14's hard boundary, amended.** Supersedes §6b's L14 clause:

> Hard boundary: honesty facts never hide behind hover. An honesty fact is a claim **about the set this
> surface is currently describing** — its counts, its verdicts, its LOCKED state, its grounding. A fact
> about *another* object (a prior session's message tally) is meta, and meta may extend. The boundary's
> test is therefore structural and universal, never a list of ids.

This settles finding 12's contradiction on L6's own framing ("every count derives from the set it
describes"). It has a concrete implementable consequence: **the class name must carry the role**, so a
universal selector can distinguish them — meta stops borrowing the `count` class, and the test becomes
"no element carrying a current-set count/verdict role renders inside an `.ext`", which cannot be
outgrown by a new region.

**Finding 4 — the flip moves to the pill, and Tab is released.** The pill becomes a native `<button>`:
focus, Enter/Space activation and `aria-pressed` come free, which is the same reasoning the two grips
already use (`SearchV2View.ts:1100-1101`, `:1137-1139`). Keyboard story: the pill sits in the tab order
between the input and the send affordances, `aria-pressed` announces the lens state, Escape still clears
it. This also makes a keyboard-only invisible affordance visible, which is more consistent with L1's own
claim that the pill is the truth-teller. L1's slice-5 widening ("the flip survives typing") is preserved.

**Finding 9 — Escape closes the trail.** The cause is that `onDraftFocus` cannot distinguish a user's
focus gesture from focus returned programmatically after closing. The fix belongs where that is known:
`closeHistory()` owns the refocus and therefore owns suppressing the reopen — one place that knows,
rather than a flag read at a distance.

**Finding 10 — the choreography is scoped to what was just committed**, keyed by record identity rather
than by element type, so settled records stop re-animating.

**The witnesses are rewritten, not supplemented.** Each law's test asserts the smallest observation that
distinguishes held from broken — a real Tab keypress, a real Escape, refusal *visibility*, and universal
rather than enumerated selectors. The superseded assertions are deleted in the same work (§6e.6): a
proxy assertion left beside a real one is a false green that still reads as coverage.

### 6e.5 §5's sunset criterion — replacement

§5's criterion is falsified as written: it ends the two-window phase when v2 "passes the full law suite",
and this pass found the law suite passing while five laws were violated in ways it structurally cannot
see. The criterion assumed the suite is a faithful oracle for the laws. Replacement:

> Search v2 is promoted when, and only when: **(a)** every law names its verification tier in a per-law
> table — unit / rendered-geometry (`ui-proportion-gate` at a named ui-shot step) / measured-audit /
> *unverified* — and no law's only witness is a unit test asserting the DOM structure the implementation
> trivially guarantees; **(b)** every law whose tier is rendered-geometry is enrolled in
> `governance/ui-proportion-baseline.v1.json` with anti-vacuity companions, and green; **(c)** the §5b
> parity checklist is complete or each gap consciously superseded; **(d)** an independent measured UX
> audit (auditor ≠ implementer, axe + contrast oracle, live) passes at **both** the roomy and the short
> camera; and **(e)** the `governance/sandbox-coverage.v1.json` exemption row has moved to a real tier.
> A law marked *unverified* does not block promotion, but it must be listed in the promotion PR — the
> point is that the blind spot is **stated**, not that it is absent.

The falsification cuts both ways and the criterion should say so: it is equally the standard the
*shipped* window is compared against, so §5's "or the comparison falsifies the model" branch stays live.

### 6e.6 What this design orphans (deleted in this same work, not by a later sweep)

1. `readStoredRailWidth`'s range-check-and-discard branch, **and** the two prose comments asserting that
   today's clamps are enforced on restore (`railSizing.ts:117-142`, `SearchV2View.ts:396-398`).
2. The scattered gesture-time clamp call sites (`railBounds` at gesture time plus the inline clamp calls
   in the deck's pointer/key handlers) — subsumed by the one entry point. The pure math survives.
3. `?disabled=${this.sessionLocked}` on both send affordances (`:2307`, `:2319`).
4. The Tab branch in `onKeydown` (`:1646-1653`).
5. The unscoped choreography type-selectors (`:1562-1578`).
6. Five proxy assertions — `presentation.test.ts:923-936` (Tab), `:748-767` (hard boundary),
   `:831-851` (the Escape test that never presses Escape), `agentRun.test.ts:402-425` (gate ancestry),
   `answerProjection.test.ts:429-442` (the exit that certifies a dead end). Rewritten, never left beside.
7. `governance/sandbox-coverage.v1.json`'s `tier: exempt` row for `core.search-v2-surface`.
8. The two prose deferrals of register enrollment (`railSizing.ts:28-31`, `SearchV2View.ts:59-66`).
9. `SearchV2View.ts`'s hand-written `.body` override (`:1049-1053`).
10. §4's citation of "814 §D3" as warrant for five region scrollers. D3 says one scroller per **surface**;
    this window's claim is one per **region**. The spirit is compatible — D3 attacks *nested* scrollers
    and these are side-by-side — but a citation that does not say what the citer thinks is the drift these
    registers exist to prevent, so the claim is restated in its own terms and measured by the
    `maxScrollableRegions`/`minScrollableRegions` pair.

### 6e.7 Reach — principles, evidence, retirement

- **`proxy-assertion`** *(new, transferable)* — a test that asserts the DOM fact the implementation
  trivially guarantees, standing in for the property the law is actually about. Five instances here, all
  green. Where else it applies: any law-named or rule-named suite whose subject is behaviour rather than
  structure. Evidence it earns its keep: a rewritten witness catches a regression its predecessor could
  not, and the next law-suite review finds fewer green-but-broken laws. Retire when two consecutive
  reviews find no instance. Candidate home: the `agent-postmortems.md` handle list — proposed, not
  claimed here.
- **An exemption without an expiry is a deferral that compounds** *(new, transferable)* — three
  individually reasonable deferrals (a `tier: exempt` row and two prose "at the cutover" notes) jointly
  removed the entire spatial verification tier for five slices of spatial work. Candidate rule: a
  coverage exemption states the condition that ends it *and* names what it removes. This is the core of
  the follow-up sketch below. Retire if a review finds exemptions are already self-limiting in practice.
- **A boundary is reconciled, not clamped-at-gesture** *(818-local instance of an existing principle)* —
  a persisted size is a preference re-validated against measured reality at every apply point, and
  clamping never rewrites the memory. This is 816's `clamp(min, need, max)` model plus a lifecycle;
  `responsiveState.ts`'s `reportLayoutWidth` already embodies the measure-and-reconcile half. Known
  violation: only the subject. Retire if persisted geometry is abandoned entirely.
- **A surface's scroll-policy region and its layout track are different elements** *(narrow)* — the
  instance of "one node, one reason to change". Verified: no other surface co-classes `.body`, so this is
  a rule to hold, not a sweep to run.
- **Not built, deliberately**: a shared `ResizeObserver` controller base (three near-identical hand-copies
  would exist after this work — the extraction becomes arguable then, and the *policies* differ entirely,
  so only ~15 lines of boilerplate are shared); and the shared-card "host declares supported affordances"
  contract. Both are recognised, neither is required by the present problem.
- **Follow-up sketch, still unclaimed**: "geometry verification for dev-gated surfaces" — whether a
  DEEPLINK/DEVELOPER exemption should carry an expiry and an obligation to name the tier it removes.
  Reach beyond 818: every such surface inherits the same hole. Next free number at the time of writing
  was **#820**; whether it is a tempdoc or a rule folded into the existing coverage register is a call
  for whoever picks it up, not for this design.

## 6f. Derisk record (2026-08-11; pre-implementation, no feature work done)

Six named uncertainties, investigated empirically rather than reasoned about. Three forced design
revisions; one raised a risk the design had not seen. Probes were run in a throwaway test file,
deleted after — the results, not the file, are the artefact.

### (a) happy-dom cascade through `adoptedStyleSheets` — RESOLVED, and it reproduces the defect

Probe result, verbatim:
`{"flexDirection":"column","display":"flex","overflow":"hidden","adoptedSheets":1,"hasStyleTag":false}`

happy-dom **does** resolve cascade properties through Lit's adopted stylesheets, and the `.body win`
shape resolves to `column` — i.e. **finding 1 reproduces in the unit tier**. The designed fallback
(regex block isolation) is not needed. But a second probe caught a trap the design would have walked
into:

| structure | `getComputedStyle(track).flexDirection` |
|---|---|
| broken (`.body win` one node) | `"column"` |
| fixed by declaring row on the same node | `"row"` |
| **fixed by nesting `.win` inside `.body`** | **`""`** (empty) |

happy-dom returns computed values only for **declared** properties — it does not resolve initial
values. So `expect(dir).toBe('row')` would be a **false negative against the design's own preferred
structure**. Revision: the witness asserts `not.toBe('column')` (the precise negation of the defect —
`row` is the initial value, so an unset property IS correct), paired with the structural companion
`querySelector('.body') !== querySelector('.win')`, which probes cleanly (`true` broken / `false`
fixed). Both witnesses verified discriminating.

### (b) The third `surfaceLayout` variant — REVISED AWAY

`scripts/ci/check-layout-purity.mjs:70-72` requires a surface to compose
`surfaceLayoutStyles || surfaceScrollLayoutStyles` — a **substring test on the variant names**. A third
variant under a new name would therefore fail that gate for any surface adopting it, so the variant is
not additive: it edits a CI gate. Weighed against: only one surface has ever co-classed `.body`
(verified), and the P0.3 witnesses above enforce the rule anyway at zero shared-primitive cost.
**Revision: drop the variant; nest `.win` as a child of `.body` locally**, keeping `overflow: hidden`
(which was never the broken part) and removing only `display`/`flex-direction` from `.body`. The
variant becomes justified the moment a *second* surface needs the policy — recorded, not built.
(Out-of-scope finding logged to the observations shard: that gate keys on `jf-*-surface`, so
`jf-search-v2` is outside its coverage entirely.)

### (c) Cost of the two ui-shot steps — CHEAPER than the deferral assumed, except in one place

Confident negatives first, all verified: **no live backend is required** (`--fixtures` route-mocks
`/api/*`); `.measure.json` capture is **automatic per step**, not opt-in; and
`check-ui-step-coverage.mjs` iterates only `placement: 'RAIL'` surfaces, so a DEEPLINK surface is
structurally outside it — adding steps trips no gate. The **deeplink navigation precedent is exact**:
`health` and `help` are `DEEPLINK` surfaces with no rail entry whose step setup falls back to
`location.hash = 'justsearch://surface/<id>'` (`scripts/jseval/jseval/ui_check.py:514-523`) — the same
placement tier as `core.search-v2-surface`, and for them that fallback is the live path, not a
theoretical one.

The real cost is the per-step `setup()` (20-90 lines of Playwright plus the dense WHY-comments this
harness treats as mandatory), and **the risk is concentrated in one place**: the short step needs a
**live run** for `.run-controls maxBottomPx` to witness finding 3. The chat family's own record notes
that SSE-driven agent-run rows were **fixture-unreachable** and were deliberately left uncaptured
rather than faked. An `agent-run` fixtures variant does exist (`chat-evidence-rail` uses it) and
search-v2 hosts runs through the *same* shared controller, so it is plausible — but unverified.
**Revision: split P0.2 into P0.2a (roomy step — low risk, clear precedent) and P0.2b (short step with a
run — carries the fixtures risk).** If 2b proves unreachable, finding 3's rendered witness degrades to
the live-stack tier or to a run-less approximation of the deck's own bottom, and §6e.5 then requires
that law to be **listed as unverified in the promotion PR** — the replacement criterion doing its job
on its first real case rather than absorbing the gap silently.

### (d) Session-epoch vs positional ids — NO CHURN, and the design gets simpler

Only 8 positional-id literals exist across the suite. Seven are in `records.test.ts`, where the id is
passed **as an argument** (`freezeSearch('r0', …)`) and is therefore epoch-independent; the eighth
(`presentation.test.ts:885`) asserts a projected `data-record-id`. No snapshot tests exist in the
directory (confirmed: zero `toMatchSnapshot`). Revision: **the epoch is a closure-captured guard, never
part of the id** — `dispatchAsk` captures it and each terminal checks `epoch === this.epoch` before
touching records. Ids stay positional, projections stay clean, all 8 literals stay valid. This is
strictly simpler than §6d's "epoch in the id" option and it was the empirics, not taste, that chose it.

### (e) "No send affordance is ever `?disabled`" — the gates WANT it, and the design gets smaller

No gate objects; the opposite. `scripts/ci/check-controls-a11y.mjs:250-303` ratchets against
`disabled`+`title` co-occurrence precisely because a browser suppresses a `title` on a disabled control
("596 face 1.1"), and `availability.ts:13-22` already defines the four-kind taxonomy the design needs:
`blocked` = a HARD **intent** gate (unconfirmed input, mid-operation) staying natively disabled, versus
`unavailable{reason}` = a SOFT block rendering `aria-disabled`, staying focusable, and **surfacing the
reason on an activation attempt** — which is L9's "identical refusal on every send path, draft never
swallowed", already built.

The decisive argument is the taxonomy's own wording: a session lock is **not** an intent gate — the
user's intent is complete, the *capability* is gone. By `availability.ts`'s definitions it is
`unavailable`, not `blocked`. And the capability-agnostic tier already exists for exactly this:
`unavailableBecause(reason, transient)` (`availability.ts:240`), described as the home of 6+ non-AI
local gates. Revision: **the lock, a run in flight, and an ask in flight all become `Availability`
values through `unavailableBecause`**, and the send controls render through the availability-aware path
(`jf-control`, or `Button.ts:182`'s `av` mode) — materially less bespoke code than §6e implied.
One thing to reconcile rather than ignore: `SearchV2View.ts:2298-2303` carries an explicit authored
rationale for treating the lock as the one hard gate. That comment is an *argument*, not an oversight;
it must be rewritten, not silently deleted.

### (f) What only the rendered tier can assert — settled by probe

`ResizeObserver` **exists** in happy-dom but **never fires** (`{"fired":0,"lastRect":null}` after
observe + 50 ms). This is a worse failure mode than absence: the `typeof ResizeObserver === 'undefined'`
guard used by the sibling controllers would *not* trip, so an observer would be constructed that never
delivers. It confirms the pure-function seam is **necessary, not tidy** — unit tests must drive
reconciliation directly and may never wait on the observer. `getBoundingClientRect` stubbing works
(`natural: 0 → stubbed: 700`), so all reconciliation *policy* is unit-testable, as is the L14 quantified
boundary (`.ext .count` probe: 1 of 3 counts correctly identified as extended).

**Unit tier can assert**: the axis (both witnesses), all clamp/eviction policy against synthetic
measurements, the L14 universal boundary, Tab/Escape/refusal behaviour, and send-predicate refusal
*visibility*.
**Only the rendered tier can assert**: real widths/heights (every rect is 0×0), `maxBottomPx`/`CLIPPED`
— finding 3's core, whether an element is genuinely a scroller (`IS_SCROLLER`, scroller counts), that
the observer fires on a real resize, and that the axis yields a genuinely side-by-side layout.
This split is the per-law tier table §6e.5 now requires; it should be written as one.

### Baseline the implementer inherits

Full `modules/ui-web` suite re-run at this worktree's base: **398 files / 4447 tests, 0 failures, 53 s.**
The slice-5 log's "4 known pre-existing failures in other files" is **stale** — the baseline is clean, so
any red during implementation belongs to the change.

### One trap recorded for the implementer (Stream C)

The destination pills are `<span>`s carrying `title=${unavailableReason(…) ?? …}`
(`SearchV2View.ts:2274-2297`). Converting a pill to a native `<button>` (finding 4) while keeping the
`title` **and** giving it any disabled state would create a new `disabled`+`title` co-occurrence, which
the 596 ratchet fails — and its baseline file is empty (`{}`), so the allowance is zero. The pill's
reason must ride `aria-describedby`, consistent with Stream B's rule.

### Confidence ratings (0-10) and recommended model/effort

| Stream | Confidence | Why | Recommended |
|---|---|---|---|
| **P0.1** axis fix + witnesses | **9** | Defect and both witnesses probe-verified; variant dropped, so the change is ~3 CSS lines + one template nesting | sonnet, low effort |
| **P0.2a** roomy ui-shot step | **8** | Exact DEEPLINK precedent, no gate, no backend, automatic measure companion | sonnet, medium |
| **P0.2b** short step with a live run | **5** | `agent-run` variant exists but SSE run state is documented fixture-unreachable for the chat family; plausible, unverified | opus, or spike first |
| **Stream A** boundaries | **7** | Reconciliation composes the existing clamps at `delta 0` → zero churn in the 22 pure tests; RO-never-fires is now known; residual is that eviction's real behaviour needs P0.2b or live | opus, medium-high |
| **Stream B** send arbitration | **8** | Much smaller after (e): existing `Availability` primitives; epoch needs no id change; one authored comment to reconcile | opus, medium |
| **Stream C** laws + witnesses | **8** | L14 selector and key-event paths probe-verified; the 596 pill trap is now named | sonnet (opus for the L7 eviction ordering) |

**Overall: 7.5.** The two things that would move it: verifying P0.2b's fixture reachability (a
~30-minute spike that converts the single biggest unknown), and confirming the eviction order reads
correctly at a real 790 px — which is the same spike. Sequencing already puts P0 first, so the unknown
resolves before the streams that depend on it.

## 6g. Implementation plan (2026-08-11)

Scope: every §6c finding, via the §6e design **as revised by §6f**. Teardown rides with the work that
makes each thing dead — nothing is deferred to a sweep. The plan ends at a green PR; **merging is
outside it** (owner gate).

### Derisk revisions are binding

The three §6f revisions supersede the §6d/§6e text they contradict. Pointers, not silent divergence:
- §6e.1's P0.3 axis witness → **`not.toBe('column')` + a node-identity companion** (§6f(a): happy-dom
  returns `""` for undeclared properties, so `toBe('row')` false-negatives the fix).
- §6e.1's third `surfaceLayout` variant → **dropped; nest `.win` inside `.body` locally** (§6f(b):
  `check-layout-purity.mjs:70-72` substring-tests the variant names, so a new one edits a CI gate).
- §6e.3 / §6d(B)'s epoch → **closure-captured terminal guard, never part of the id** (§6f(d): zero
  churn across the 8 positional-id literals; no snapshots exist).

### Sequencing: one serial spine, one parallel track

`SearchV2View.ts` is one 2,758-line file and the streams do not partition it. Measured collisions:
`onKeydown` (`:1645`) is a **single method** that Stream B (Enter → commit/delegate routing) and
Stream C (the Tab branch, the Escape order) must both edit; `deck()` (`:2239`) is a **single ~120-line
render function** that A (style binding + `sized` class), B (send buttons) and C (pill → button) all
touch; `railStrip` (`:1964`) is A + C. **Parallel worktrees would three-way-conflict inside single
functions, so the streams run SERIAL.** The genuinely parallel work is what never opens that file:
the ui-shot steps and register rows (`scripts/jseval/`, `governance/`).

```
C0 spike ──► C1 axis+witnesses ──► C2 enrollment (= the visual verification) ──► C3 A ──► C4 B ──► C5 C ──► C6 integrate+PR
                                        └── parallel track: register/step work only ──┘
```

Constraint honoured: **P0 lands and is visually verified before Stream A implementation begins** —
and C2 *is* that verification, because the roomy ui-shot step is the repeatable form of the screenshot
whose absence let finding 1 ship.

### C0 — Spike: is P0.2b's live run fixture-reachable? *(first act; branches the plan)*

The single biggest unknown (§6f(c), confidence 5). Determine whether the existing `agent-run` fixtures
variant drives a live run on `core.search-v2-surface` — it hosts runs through the same shared
`AgentSessionController` the chat family does, so the same route mocks may suffice.
- **Reachable →** C2 lands the short step **with** the `.run-controls maxBottomPx` +
  `nonScrollableSelectors` rows; finding 3 gets a rendered witness.
- **Unreachable →** C2 ships the short step **without** the run rows, asserting the deck's own bottom
  instead; **L7-at-short-height is listed as `unverified` per §6e.5(a)**, and that listing is written
  into the promotion checklist in the same chunk. This is the replacement criterion working on its
  first real case rather than absorbing the gap silently.
- Timebox ~30 min. Output: a one-paragraph verdict + the fixtures variant name, appended here.
- **Worker: sonnet, low effort.** Read-only investigation + one `jseval ui-shot` invocation.

#### C0 VERDICT (2026-08-11): **UNREACHABLE for a gate — branch B taken.** No run was needed to decide it.

The variant is `fixtures_variant="agent-run"`, and it *does* reach a completed run under `--fixtures`
via `_drive_agent_run_to_done` (`scripts/jseval/jseval/ui_check.py:1197-1235`) — but the limit that
matters here is documented by 814 itself, in the fixture's own header
(`scripts/jseval/jseval/agent_stream_fixture.py:11-17`):

> "Playwright's `route.fulfill` can only serve a COMPLETE body … The cost is recorded in §D8.3: a
> stream that never terminates (the PAUSED-awaiting-budget state) is **NOT reachable this way**."

Every fixtured run therefore drains to its terminal essentially instantly. For this window that is
decisive, because its run region is owned, not ambient: `runRegion()` renders only while `runOwned`
(`SearchV2View.ts:2497-2499`) and `concludeRun` clears `runOwned` at the terminal (`:517-519`). A
fixtured run thus never yields a stable frame in which `.run-controls` exists, so
`maxBottomPx` + `nonScrollableSelectors` on that selector are not capturable. This is a **harness**
limit that 814 already hit and deliberately left uncaptured rather than faked — not a search-v2 defect
and not a failed experiment, which is why the plan's pre-registered branch is taken on evidence rather
than on a timeout.

Two consequences, and the second is better than the plan assumed:
1. **The short step still gets a non-vacuous clipping witness.** Finding 3's *mechanism* is the deck
   overflowing the centre column, which does not require the run: the step asserts `maxBottomPx` on
   `.deck` at the most-occupied fixture-reachable state. That catches the clipping class; it cannot
   catch the controls-specific claim.
2. **The run-controls case gets a tier, not a shrug.** A real in-flight run IS reachable on the live
   stack with an active model, so L7-at-short-height-with-a-run is assigned tier
   **measured-audit (one-time, C6)** rather than rendered-geometry (gated), and is listed `unverified`
   in the §6e.5(a) promotion table — precisely the distinction that table exists to record.

### C1 — P0.1 axis fix + P0.3 unit witnesses

- Remove `display` / `flex-direction` from `.body` (`:1049-1053`), keeping `overflow: hidden` (never the
  broken part); nest `<div class="win">` as a **child** of `<div class="body">` (`:1916`).
- Add the two witnesses to `SearchV2View.presentation.test.ts`: the track element never resolves
  `flex-direction: column`, and the `.body` node is not the `.win` node.
- Add the catalog-verbatim test: `railSizing.ts`'s `CENTRE_MIN_PX` / `DOCUMENT_RAIL_FLOOR_PX` **equal**
  the `governance/ui-proportion-baseline.v1.json` rows they cite in prose (first `modules/ui-web` test
  to read that register — a deliberate precedent).
- **Teardown here:** the hand-written `.body` override (orphan 9).
- **Acceptance:** both witnesses fail on `HEAD~` and pass after (state this explicitly in the PR body —
  a witness that never saw red is not a witness); `npm run typecheck`; full `npm run test:unit:run`
  green against the **398 files / 4447 tests / 0 failures** baseline §6f measured.
- **Worker: sonnet, low effort.**

### C2 — Enrollment: the rendered tier (the parallel track, and P0's visual verification)

- Author two ui-shot steps following the `health`/`help` DEEPLINK precedent
  (`scripts/jseval/jseval/ui_check.py:514-523` — `location.hash = 'justsearch://surface/<id>'`): a roomy
  step and a short (~790 px block) step.
- Register rows in `governance/ui-proportion-baseline.v1.json`, each citing the function that produces
  its number (816 §3): `.rail maxWidthPx` (from `SESSION_RAIL_CEILING_PX`), `.centre minWidthPx: 384`,
  `.document-pane minWidthPx: 384`, the `maxScrollableRegions`/`minScrollableRegions` anti-vacuity pair,
  `requiredSelectors` for the controls, and — **conditional on C0** — `.run-controls maxBottomPx` +
  `nonScrollableSelectors`.
- Move `governance/sandbox-coverage.v1.json`'s `tier: exempt` row for `core.search-v2-surface` to a real
  tier (the row's own text says it must).
- Land the **§5 replacement** (§6e.5) as the operative criterion: clauses (b) and (e) are literally this
  chunk's work, so the wording lands with the enrollment it describes.
- **Teardown here:** the two prose deferrals (`railSizing.ts:28-31`, `SearchV2View.ts:59-66`, orphan 8);
  the exemption row (orphan 7); the **814 §D3 citation correction** (orphan 10) — D3 says one scroller
  per *surface*; this window's claim is one per *region*, restated in its own terms and measured by the
  scroller pair.
- **Acceptance:** `jseval ui-proportion-gate` green on both steps; captured `.measure.json` shows the
  rail beside the centre, not above it — **the screenshot is attached to the PR** (the slice-4/5 lesson);
  `node scripts/ci/check-ui-step-coverage.mjs`.
- **Worker: opus, medium** (carries the conditional 2b half and register judgment).

### C3 — Stream A: boundaries (findings 3, 7, 13b)

- One **pure** reconciliation function returning all three boundaries' state, composing the existing
  `clampRailWidth` / `clampDeckHeight` **at `deltaPx: 0`** — mathematically identical to a value-shaped
  clamp, so **no signature change and no churn across the 22 pure-module tests** (§6f).
- A thin Lit `ReactiveController` owning the `ResizeObserver`, modelled on `adaptiveBar.ts:85-177` /
  `adaptiveDensity.ts:105-173`. **It must be directly drivable**: happy-dom's `ResizeObserver` exists but
  never fires (§6f(f)), so tests call the reconciler, never wait on the observer.
- Three call sites only: mount (restore), resize (observe), gesture end (adopt).
- **Clamp-on-apply replaces discard** in `readStoredRailWidth`; the memory is never rewritten by a clamp.
- Eviction order per the **amended L7**: list body → run feed → transcript; controls never yield.
- **Law text:** the L7 amendment (§6e.4) is folded into §1's L7 as its operative statement in this chunk.
- **Teardown here:** `readStoredRailWidth`'s discard branch **and both prose comments claiming today's
  clamps are enforced** (`railSizing.ts:117-142`, `SearchV2View.ts:396-398` — orphan 1); the scattered
  gesture-time clamp call sites (orphan 2); the **proxy assertion at `agentRun.test.ts:402-425`**
  rewritten in place to assert the controls' rendered bottom, not DOM ancestry.
- **Acceptance:** reconciliation unit tests using stubbed rects (§6f verified `getBoundingClientRect`
  stubbing works); the C2 short step green; full suite + full ui-web gate recipe.
- **Worker: opus, medium-high.**

### C4 — Stream B: send arbitration (findings 2, 5, 6, 8)

- `sendAvailability(rung)` composes `projectAvailability` (AI half, already used) with
  **`unavailableBecause(reason)`** (`availability.ts:240`) for the lock, a run in flight, and an ask in
  flight. Send controls render through the availability-aware path (`jf-control` / `Button.ts:182`'s `av`
  mode). **No send affordance is ever `?disabled`** — §6f(e) confirmed a lock is `unavailable`
  (capability gone) and not `blocked` (an *intent* gate) by `availability.ts`'s own taxonomy.
- Serialise the ask; a second commit mid-stream refuses **visibly**. The covering test asserts the
  refusal is *on screen*, not merely that a second dispatch did not happen.
- **Epoch as a closure-captured guard**: `dispatchAsk` captures it, every terminal checks it before
  touching records. Ids stay positional.
- `clearRecords` aborts; `refuseLocked` aborts rather than dropping the handle (`:1820`).
- Finding 5's exit re-designed: an exit that cannot change the outcome is not offered, and the reason
  survives the exit rather than being cleared with it.
- Wire `card-fork` (L4's re-run-as-new) and `card-scope-file` (L3's narrowing).
- **Teardown here:** `?disabled=${this.sessionLocked}` on both send controls (`:2307`, `:2319`, orphan 3);
  the **authored rationale at `:2298-2303` rewritten, not deleted** — it is an argument, and it now
  reaches the opposite conclusion; the **proxy assertion at `answerProjection.test.ts:429-442`**
  rewritten to assert the user can act, not that the refusal vanished.
- **Acceptance:** `check-controls-a11y`, `check-capability-availability`, `check-realized-capability`;
  full suite + full gate recipe.
- **Worker: opus, medium.**

### C5 — Stream C: laws, keyboard, witnesses (findings 4, 9, 10, 11, 12)

- Pill → native `<button>` with `aria-pressed`; Tab released entirely. **Trap (§6f):** the pill must not
  combine `disabled` + `title` — the 596 ratchet's baseline is empty (`{}`), so the allowance is zero;
  the reason rides `aria-describedby`.
- `closeHistory()` owns the refocus and therefore owns suppressing the reopen (finding 9).
- Choreography scoped by record identity, not element type (finding 10).
- The collapsed rail strip keeps **New session** — the amended L7's "never an escape hatch" (finding 11).
- Meta stops borrowing the `count` class so the **quantified** L14 boundary works (§6f verified the
  `.ext .count` selector discriminates 1 of 3).
- **Law text:** the L14 amendment (§6e.4) folded into §6b's L14 as its operative statement here.
- **Teardown here:** the Tab branch (`:1646-1653`, orphan 4); the unscoped choreography selectors
  (`:1562-1578`, orphan 5); the **three remaining proxy assertions rewritten in place** —
  `presentation.test.ts:923-936` (press a real Tab), `:748-767` (quantified, not four ids),
  `:831-851` (actually press Escape, from inside the trail).
- **Acceptance:** each rewritten witness demonstrated red-before/green-after; `check-a11y-closure`,
  `check-controls-a11y`; full suite + full gate recipe.
- **Worker: sonnet for the mechanical halves; opus if the L7 eviction ordering needs revisiting from C3.**

### C6 — Integration, live pass, PR to green — then STOP

- Full `./gradlew.bat build -x test`; `cd modules/ui-web && npm run typecheck && npm run test:unit:run`;
  the **complete** ui-web gate recipe (`governance/consult-register.v1.json` → `ui-web-gates`), not a
  subset — `subset-isnt-the-suite`.
- **Live pass** (stack + active GPU model): mount, commit, real cited answer, delegate + halt, lock
  refusal, the trail, both grips, and the short-window run state. **The dev-stack lease stays with the
  orchestrator** — lease acquisition and contention are main-loop decisions, so this pass is
  orchestrator-held and supervised, never fire-and-forget delegated.
- Independent measured UX audit (auditor ≠ implementer, axe + contrast oracle) at **both** cameras, per
  §6e.5(d) and the honor-system `ux-audit-closure` discipline.
- Open the PR, take it to green, **stop**. Merging is the owner's call and is not part of this plan.

### Post-PR, owner's call only (not in scope)

The `proxy-assertion` postmortem handle and the unclaimed **#820** sketch ("geometry verification for
dev-gated surfaces") remain **proposals**. Neither is implemented by this plan.

### Residual open questions

1. **C0's outcome** — the one genuine branch. Everything else is determined.
2. **Eviction order at a real 790 px** — the amended L7 fixes the *policy*; whether feed-before-transcript
   reads correctly to a person is a judgment only the C6 live pass can make.
3. **`check-layout-purity` coverage** — `jf-search-v2` does not match its `jf-*-surface` key, so this
   window is outside that gate entirely. Logged to the observations shard; **not fixed here** (it is a
   gate-scope question with reach past this window).

## 7. Log

- 2026-08-08 — tempdoc created from the prototype harvest; worktree `818-search-v2`;
  prototype v5 (+identity layer) preserved under `818-prototype/`. Slice 1 delegated.
- 2026-08-08 — **Slice 1 implemented** (opus worker + independent orchestrator review).
  `views/search-v2/`: `records.ts` (the one records array + projections), `route.ts`
  (pure routing + flip lens), `SearchV2View.ts` (host `jf-search-v2`), 27 law-named
  tests (route 8 / records 11 / view 8). Mounted DEEPLINK/DEVELOPER at
  `#justsearch://surface/core.search-v2-surface`; registration = lazySurfaceRegistry +
  CorePlugin + registry-surface properties (label catalog); component-vocabulary
  regenerated. Full ui-web gate recipe green (one root-cause fix: the destination pill's
  class renamed `.rung-pill` — it is not a status-badge atom, per the atom-fork ratchet).
  Typecheck clean, 4329 unit tests green, `gradlew build -x test` green.
  Accepted worker deviations: strict L10 (empty-draft commit is a no-op, stricter than
  the prototype's default-text fallback); no `setConversationApiBase`/`setQuery('')`
  writes into shared singletons (correct — shared-state hygiene).
  **Live-verified** against the real dev stack via `serve-worktree-fe`: mount, no rail
  peer, real search (backend hits), commit → derived name + index flip + frozen block +
  pending answer, all on screen. Live finding logged to the observations shard:
  backend returned 5 result rows with `matchCount=4` (597-class count inversion at the
  API level — pre-existing, not a search-v2 defect; the frozen header derived honestly
  from its captured set).
- 2026-08-08 — **Slices 2+3 implemented** (two sequential opus workers + orchestrator
  review + full live verification). Slice 2: `jf-results-card` reuse for live AND frozen
  lists (the inline rows were a fork of the product's one results projection — deleted,
  along with search-v2's own count-label fork, in favour of the card's `matchCountLabel`
  authority); the real ASK tier (`askClient.ts` — the ONE dispatch site by construction,
  `core.rag-ask` via `buildRequestBody` + `consumeShapeStream`, typed handlers);
  `AnswerRecord`/`refused-answer` slot terminals (filling the slot is the record's own
  lifecycle; everything else carried by identity — L4 tested); `jf-citations-panel` +
  `citationResolve` reuse; `jf-document-pane` on card-open + file scope chip (shared
  `searchState` chip authority — L3 real); L5 literal (ask docIds === frozen set);
  L9: one `refuseLocked` path, 423-caught, draft never swallowed. Slice 3: agent hosting
  via the shared `AgentSessionController` (all directives through the
  `dispatchRunControl` seam — `check-steering-arbitration` green); live run feed with
  `jf-tool-call-card` reuse; ONE `agent-run` receipt record at run end with derived
  counts; L7 deck (controls outside every scroll container, DOM-asserted); L2 live
  (`runInFlight` claims the alt slot only, releases on end); `refuseIfLocked` pre-gate
  shared by commit AND delegate (the shipped agent-branch bypass class, killed);
  time-bucketed sessions sidebar (pure, `now`-injected). Registers: +3
  execution-surfaces, +1 live-channels, run-renderers mountSites, steering-surfaces
  adopters. 82 law tests in search-v2 (suite 4384 green), typecheck clean, full gate
  recipe green (3 worker-caught root-cause fixes: `groundedSentencesLabel` rename,
  `.pin` atom rename, live-channel registration; 4 known pre-existing failures in
  other files unchanged). **Live-verified with an ACTIVE GPU model**: real streamed
  cited answer (correct content from the docs corpus, 5 sources in the citations
  panel, context meter at real 43%/4096), real agent run (feed + tool card + budget
  37%/3840 + STEER claiming the alt slot), halt through the seam → derived receipt
  "Run halted by you · 2 tool calls", index/entries projections consistent at 5,
  alt slot released. Known residuals recorded: no chat-issuance gate exists
  (askClient is single-site by review, not by gate — candidate for a register if the
  two-window phase persists); mid-run lock (a lock taken between status polls) is not
  refused on the agent path — needs a typed lock terminal on the agent stream
  (controller work, out of this window's scope).
- 2026-08-08 — **Slices 4+5 implemented** (presentation pass; two sequential opus
  workers + orchestrator review + live verification). Slice 4: commit choreography
  (prototype timing ported, last-animation-owns-teardown, reduced-motion doubly
  disabled); deck grip with drag-time computed floor (`deckSizing.ts` pure math,
  keyboard-operable native button, run controls raise the floor); zero-result honest
  empty + n=0 affordance labels; AI-offline via the `projectAvailability` authority
  with a REACHABLE reason (a11y gate caught title-on-disabled — root-caused, not
  suppressed); 814 binding by consuming the `shortViewportMax` breakpoint authority +
  one-scroller-per-region (registration deferred with evidence: the proportion register
  is keyed by ui-shot step; obligation written where the §5 cutover will read it).
  Slice 5: rail grips per L13 (`railSizing.ts`; floors/ceilings cited to tokens.css +
  ui-proportion-baseline minWidthPx rows; rails remember via localStorage, forget on
  automatic); L14 hover/focus extension as ONE mechanism (`.ext` + clip-path, AT-visible;
  hard boundary structurally tested — counts/verdicts never inside `.ext`); omnibox
  query trail (pinned via `pinnedSearchState`, recents = session frozen queries +
  capped submitted-trail; fills-never-commits); citation landing via the shared panel's
  own `citation-select` → `jf-document-pane` at the cited range (land-strong-settle
  already lives in the pane — nothing forked); keyboard pass (⌥↑⌥↓ never-while-typing,
  Escape order trail→pane→flip, full Tab traversal); copy audit (verb set unified on
  ASK, user-side naming, ~20 strings — table in the slice-5 worker report).
  145 search-v2 tests (suite 4447), typecheck + full gate recipe + eslint green; the 4
  known pre-existing failures unchanged in other files. **Live-verified** (stack +
  worktree FE + active GPU model): mount, both grips (document grip appears with the
  pane), commit choreography fires and tears down, real cited answer, citations panel,
  citation click landed the pane at `629-data-at-rest-encryption.md` lines 2360-2381.
  Live-inconclusive (unit-covered): omnibox open-on-focus — synthetic focus events are
  untrusted in Chrome and the screenshot path was down, so no real-click check.
  L1 wording tightened (above) — the slice-5 "flip survives typing" observation was
  the designed behavior, not a defect.
  Parallel tracks: §5b campaign scaffolding authored; matchCount inversion ROOT-CAUSED
  to lexical-only `matchCount` vs 3-leg fusion window admitting dense/SPLADE-only rows
  (full chain in the observations shard; needs its own count-semantics tempdoc);
  identity directions authored as three swappable themes: **Registrar's Ledger**
  (light — sage paper, iron-gall ink, "green is evidence", wax is risk),
  **Night Registry** (dark — reading-room green-black, lamp-green evidence, ember risk),
  **Cyanotype** (mid-dark chromatic Prussian ground — "cyan means exposure"; its one
  declared risk). All under `modules/ui-web/public/themes/` + manifest entries; default
  UNCHANGED — owner picks. Every text/surface pair measured (body ≥4.5 everywhere;
  matrices in the worker report), plus a CVD simulation (protan/deutan/tritan ΔE) that
  drove three value corrections; validation + manifest schema green. Owner notes before
  adopting one: the chosen theme's filename must join `builtinPaletteContrast.test.ts`
  `BUILT_IN_PALETTES` and ideally `themesCatalog.ts` `BUILT_IN_THEMES`; the ledger's
  intended IBM Plex faces need local bundling (three related out-of-scope findings in
  the observations shard, incl. sepia-focus's 1.39:1 text-grade gaps).
- 2026-08-11 — **Critical pass on slices 1–5** (independent reviewer ≠ implementer, refute-first,
  read-only against `main` at `14211df2`). Thirteen findings survived refutation and are recorded
  verbatim in **§6c**, with the refuted candidates and the tier analysis that explains why the
  green suite did not see them. Headline: a layout-axis regression introduced by slice 4 (`.body`'s
  `flex-direction: column` landing on the same element as `.win`) has the window rendering its three
  regions stacked vertically; a positional-id + singular-stream model lets a stale ask terminal fill
  a different session's answer slot; L7's incompressible-decision guarantee fails by clipping at
  short heights; and Tab is a keyboard trap in the omnibox. Five findings have a covering test that
  passes for the wrong reason. Worktree `818-critical-fixes`. **Theorization pass** (§6d) written
  the same day, before design: the thirteen resolve to five generative causes (no geometry oracle;
  positional identity; affordance and guard computed apart; tests asserting a proxy for the
  property; memory replayed without re-validation), which bundles the work into roughly three
  streams plus a Phase 0. Open forks left for design, deliberately undecided here: patch vs
  session-epoch vs opaque-handle vs serialise-or-pluralise for finding 2; shrinking deck vs
  last-resort scroller vs a short-height occupancy law for finding 3; whether one ResizeObserver
  reconciles all three boundaries at once (findings 3+7+13b are one mechanism); whether the ⇥ flip
  moves off Tab and onto the pill; and whether L14's sidebar clause or its test yields for finding
  12. Sequencing constraint recorded: finding 1 gates the *evidence* for the spatial findings, so
  it lands and is visually verified first and finding 3 is designed after it, not before.
- 2026-08-11 — **Remediation design** (§6e), structured as Phase 0 + three streams per the accepted
  five-cause reframe. One theorized direction was **corrected by the investigation pass and the
  correction is load-bearing**: the "geometry oracle" does not need inventing —
  `governance/ui-proportion-baseline.v1.json` + `jseval ui-proportion-gate` already carries every
  constraint kind these findings need, with named failure codes (`SPRAWLED`, `STARVED`, `CLIPPED`,
  `IS_SCROLLER`, `NO_SCROLLER`) verified in `ui_proportion_gate.py:317-333`; `maxBottomPx` was added
  by 814 D6/D7.2 to close round 8's F5, which is finding 3 with a different element. The design is
  therefore **enrollment**, and it retires the deferral that removed the tier — a deferral whose
  circularity the findings exposed (enrollment deferred to the cutover; the cutover gated on the
  window being spatially correct). The window had already borrowed this register's numbers by prose
  comment while declaring no rows: the numbers came in, the judgment stayed out. Other decisions:
  a third declared `surfaceLayout` variant so a surface's scroll-policy region and its layout track
  cannot be the same node (verified unique to this window); ONE pure reconciliation function with
  three callers (mount / resize / gesture-end) behind a `ReactiveController` modelled on
  `adaptiveBar`/`adaptiveDensity`, with clamp-on-apply replacing discard; one send predicate shared
  by affordance and dispatch, with **no send affordance ever `?disabled`** (the window already argues
  this for the AI-offline case and violated it only for the lock); serialised ask **plus** an injected
  session epoch, because serialising the commit does not stop a records reset from recycling
  positional ids mid-terminal. Two laws amended in place with pointers left at their original
  statements (L7 — compression vs eviction, and "never a fact or an escape hatch"; L14 — an honesty
  fact is a claim about the set THIS surface describes), and §5's falsified sunset criterion replaced
  with one that requires every law to name its verification tier. Ten orphans named for deletion in
  the same work, including the five proxy assertions (rewritten, never left beside) and a 814 §D3
  citation that does not say what it was cited for. Reach: `proxy-assertion` proposed as a postmortem
  handle; "an exemption without an expiry is a deferral that compounds" as the core of the
  still-unclaimed #820 sketch; a shared ResizeObserver base and a shared-card affordance contract
  recognised but deliberately not built.
- 2026-08-11 — **Derisk pass** (§6f), pre-implementation, no feature work. Six uncertainties probed
  empirically; **three forced design revisions and one surfaced a risk the design had not seen**.
  (a) happy-dom DOES resolve the cascade through `adoptedStyleSheets` and **reproduces finding 1 in the
  unit tier** — but returns `""` for undeclared properties, so the witness must assert `not.toBe('column')`
  rather than `toBe('row')`, which would have been a false negative against the design's own preferred
  structure. (b) The third `surfaceLayout` variant is **dropped**: `check-layout-purity.mjs:70-72`
  substring-tests the variant names, so a new one edits a CI gate — local nesting plus the P0.3 witnesses
  carry the rule at zero shared cost. (c) ui-shot steps are **cheaper than the deferral assumed** (exact
  DEEPLINK precedent in the `health`/`help` steps, no backend needed under `--fixtures`, automatic
  `.measure.json`, no gate applies to a DEEPLINK surface) **except** for the short step's live run, whose
  SSE state the chat family documented as fixture-unreachable — P0.2 splits into 2a (low risk) and 2b
  (the one real unknown). (d) The epoch becomes a **closure-captured guard, never part of the id**: only
  8 positional-id literals exist, 7 pass the id as an argument, and there are no snapshots — zero churn.
  (e) The gates **want** never-disabled: `availability.ts`'s own taxonomy classes a lock as
  `unavailable` (capability gone), not `blocked` (intent gate), and `unavailableBecause` already exists
  for local gates — so Stream B shrinks to composing existing primitives, with one authored rationale
  comment to reconcile rather than delete. (f) `ResizeObserver` **exists but never fires** under
  happy-dom — a worse failure mode than absence, since the sibling controllers' `typeof === 'undefined'`
  guard would not trip; this makes the pure-function seam necessary rather than tidy, and fixes the
  per-law tier split §6e.5 requires. Baseline re-measured: **398 files / 4447 tests, 0 failures** — the
  slice-5 log's "4 known pre-existing failures" is stale, so any red during implementation is the
  change's. Ratings: P0.1 9 · P0.2a 8 · P0.2b 5 · A 7 · B 8 · C 8, **overall 7.5**; the single
  highest-value next action is a ~30-minute spike on P0.2b's fixture reachability, which also answers
  whether the eviction order reads correctly at a real 790 px.
- 2026-08-11 — **Implementation plan** (§6g): seven chunks, **serial on a single spine**. The
  parallel-vs-serial question was settled by measurement, not preference: `onKeydown` (`:1645`) is one
  method that Streams B and C must both edit, `deck()` (`:2239`) is one render function that A, B and C
  all touch, and `railStrip` (`:1964`) is A + C — parallel worktrees would three-way-conflict *inside
  single functions*. The only genuinely parallel work is what never opens `SearchV2View.ts`: the ui-shot
  steps and register rows. Order: **C0 spike → C1 axis + witnesses → C2 enrollment → C3 Stream A →
  C4 Stream B → C5 Stream C → C6 integrate**. C2 doubles as P0's visual verification, because the roomy
  ui-shot step is the repeatable form of the screenshot whose absence let finding 1 ship. C0 is the plan's
  first act and its only real branch: if the `agent-run` fixtures variant cannot drive a live run on this
  surface, the short step ships without the run rows and **L7-at-short-height is listed `unverified` per
  the new §5 wording** — the replacement criterion working on its first real case instead of absorbing
  the gap silently. All ten §6e.6 orphans are assigned to the chunk that makes each dead, including the
  five proxy assertions **rewritten in place** (never left beside a new one) and the availability
  rationale at `:2298-2303` **rewritten rather than deleted** — it is an argument that now reaches the
  opposite conclusion. Each chunk carries self-verifying acceptance criteria naming its tests, its gates
  and its tier, and every new witness must be shown red-before/green-after. The plan ends at a green PR;
  merging is explicitly the owner's gate. Models: sonnet for C0/C1 and C5's mechanical halves, opus for
  C2 (conditional 2b + register judgment), C3 and C4.
