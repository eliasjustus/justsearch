---
title: "Chat-surface layout & chrome allocation — the height budget gets an owner (T-B design)"
status: "implemented + independently audit-closed 2026-08-06 — design (§D), derisk (§B), plan (§P), verification + audit record (§V); audit verdict CLOSE-WITH-NOTES, all notes fixed. §D8 residual-closure shipped 2026-08-06: both §V residuals (expanded activity rail, docked evidence rail) are capture-reachable and gated, plus the two §R tooling guards — see the §D8 implementation record"
created: 2026-08-06
updated: 2026-08-06
related: [810, 809, 807, 798, 738, 734, 687, 610, 600, 577, 565, 559]
---

# 814 — Chat-surface chrome allocation (thread T-B of the human-validation campaign)

## What this document is

The design pass for tempdoc 810's charter **T-B**: 809 findings **12** (vertical space has no
owner), **13** (nested scroll regions + scrollbar-geometry reuse), **15**'s structural half (what
the run spine should be when a run genuinely segments), and **7**'s structural half
(Timeline/History/Inbox naming, empty states, one-authority-one-pointer). Written as a design
pass; the owner then licensed autonomous implementation the same day — §V is the
implementation and audit record.

The problem in one line: on a ~790 px window, chrome accreted to ~60% of the chat surface's
height — each band independently justified by its own workstream (593/600 banner, 577 activity
rail, 610 context meter, 565 spine, escalation-ladder rungs), with **no owner of the sum**. This
document becomes that owner, and then institutionalizes the ownership as a gate rather than a
person (§D7).

## Baseline — what is true in code on `origin/main` (verified 2026-08-06, this worktree)

Finding 12 was written without source access and flagged its own inferences. Code confirmation:

- **The vertical axis is greenfield.** No height-based `@media` or `@container` query exists
  anywhere in the chat-surface layout. Since 798 round 8 the breakpoint mechanism is a
  `@container (min-width: …)` query composed by `primitives/compositionLayout.ts` (line 88)
  against the surface box, which declares `container-type: inline-size`
  (`unifiedChatStyles.ts:191`) — **width-only by construction**. Finding 12's "horizontal
  breakpoints exist, vertical ones do not" is confirmed literally.
- **The nested-scroller stack is real:** `.conversation` (`unifiedChatStyles.ts:324`) and
  `.evidence-rail` (`:348`) are independent `overflow-y: auto` regions inside one grid row; the
  document pane owns a third internally and carries a `min-height: 24rem` floor (`:372`).
- **Band gating (corrections to finding 12's attribution):**
  - The degradation banner is *conditional* and already collapses to a one-line pill by default
    in Simple disclosure (tempdoc 738); Detailed mode or an `error` verdict force-expands it.
    The owner's ~790 px capture was taken in **Detailed** mode — the 60% figure is the Detailed
    worst case, but 738's own live measurement found even the Simple pill is **~76 px** (~2× a
    slim pill, an oversized-button artifact 738 recorded and left).
  - The activity rail ("telemetry band") is a native `<details>` (`UnifiedChatView.ts:3332`);
    its always-visible summary line is what carries "Over budget +N tokens" (tempdoc 577, not
    565). The context meter is tempdoc 610 §E.4, non-agent-mode only, mutually exclusive with
    the rail's headroom meter.
  - The run spine renders only in agent mode at wide widths — width-gated, never height- or
    content-gated (the unconditional-for-unsegmented-runs half is Lane 2's).
  - The status bar is **Shell chrome** (`chrome/Shell.ts`), not part of `UnifiedChatView` — the
    "Reduced capability" duplication spans two components, which is exactly why no single
    band-owner ever saw it.
  - Timeline/History/Inbox are **not** rail peers: they are tabs (plus a fourth, Sessions) of
    the `RetrospectivePanel` right-drawer docked via OverlayHost. They consume no surface
    height; finding 7's half of T-B is naming/authority work, orthogonal to the budget.
- **Assumed baseline, not designed here:** the 810 Lane-2 quick wins (telemetry band collapsed
  by default, "Over budget" de-alarmed on success, spine suppressed for unsegmented runs,
  info-only causes out of the banner tier, finding-14 header-invariance investigation). None had
  landed on `origin/main` at design time; this design treats them as landed and designs the
  allocation policy they don't provide.

## The design

### D1 — One owner of the sum: a declared height budget

Generalize 798 D6.2's invariant — **"primary content must not be starved by secondary
chrome"** — from the inline axis (B6-7 gave `.conversation` the strongest *width* claim) to the
block axis. The conversation region is the primary claimant of surface height; every chrome band
is secondary and yields.

Concretely, at the campaign's pinned design-basis viewport (**1366×768**):

- the conversation zone holds a **stated minimum share of surface height** — target ≥ 55% with
  every conditional band present in Simple disclosure, hard floor ≥ 45% in Detailed (Detailed
  legitimately spends more, but bounded — today it is unbounded);
- every persistent band carries a **declared maximum height** registered in the same place
  (§D7), so a future workstream adding chrome must either fit the budget or take the on-demand
  path (§D2) — the budget is what makes "nothing was ever removed" impossible to repeat
  silently.

The exact percentages are owner-tunable numbers, not the design; the design is that the numbers
exist, are registered, and are asserted at a pinned viewport.

### D2 — In-flow chrome is summary-height; detail is on-demand

The rule that caps the sum structurally: **a chrome band may hold one summary line in flow;
its expanded form must not push the conversation below its floor.** Expansion is either bounded
and user-initiated (a `<details>` the user opened is their choice, and collapsible), or docks as
an overlay through OverlayHost (559's existing seam — no new floating mechanics).

Per band:

- **Degradation banner** — stays the 738 pill by default; restyle the pill to its intended slim
  height (738 measured the current ~76 px as an oversized-button artifact; ~half that is the
  honest size). Detailed mode still expands, but below the height breakpoint (§D6) Detailed
  renders the pill first and expands on interaction. The 600 invariant is **wording, not
  height** — every worded cause remains one click away and the pill itself keeps the worded
  headline + remedy affordance; nothing regresses `check-search-degradation-reason-codes`.
- **Activity rail** — collapsed `<details>` summary (Lane 2's default), de-alarmed on success.
  Exception, stated explicitly: 577 Move 2's **held over-budget gate is content, not chrome** —
  when a run is actually paused awaiting "Add tokens / Stop run", that decision row may expand
  unbidden, because it is the primary thing on screen at that moment. A terminal "over budget"
  on a successful DONE run is history, and stays in the collapsed summary.
- **Composer block** — untouched structurally (687 R5a's stable-slot invariant: never
  re-parented, one DOM slot in every state). The toolbar rows above it (autonomy dial,
  Abilities, rung row) consolidate toward one row at constrained heights; the rung buttons stay
  self-describing (owner's explicit credit — preserved).
- **Status bar** — Shell-owned, outside this surface's DOM but inside the window math and the
  duplication rule (§D5).

### D3 — One scroll region per surface

`.conversation` becomes the **single** scrolling region of the chat surface. The evidence rail
stops being an independent scroller: it renders a bounded, non-scrolling source index (top
sources + total count + "open all" affordance), and full source browsing happens in the surface
that already exists for deep reading — the document pane / drawer, on demand. The same rule
applied to the Search surface removes its banner-compressed nested results scroller (finding
13's "reproduces on a second surface").

This is deliberately the cheap consequence of D1: finding 13 is causally downstream — each
nested scrollbar marks a place the layout ran out of room and solved it locally — so most
scrollers disappear because the budget restores the room, and the rest are converted, not
patched.

### D4 — Scrollbar geometry is reserved for scrolling

Two non-scroll elements stop wearing track/thumb clothes:

- **Run spine** (finding 15's structural half — what it should be when a run genuinely
  segments, i.e. ≥ 2 `RunSegmentRef` spans or any workflow/background origin):
  - solid hairline track (today track and marks are both dotted and compete);
  - markers aligned to their segment's first block's top edge (today ~a third down, which
    kills the gutter-index function);
  - colour from the statusTone token vocabulary but **outside** the two meanings already
    spoken for on this screen — not grounded-green, not user-bubble purple;
  - a **declared aggregation rule**: markers within a minimum spacing cluster into one counted
    badge (expandable), so density is bounded by segment count, not event count, and cannot
    degrade monotonically with conversation length;
  - hover/focus labels so the three glyph types are decodable without a legend hunt.
  The 565 model is untouched: flat `UnifiedTurnItem[]`, branded `RunSegmentRef`, single
  projector `assignRunSegments` — this is a restyle of the one projection, not a fork.
- **Meters** (610 context meter; 577 headroom meter) — no full-bleed, 100%-pegged thin
  horizontal tracks. Compact fixed-width meters with unit + ceiling stated inline (577's own
  requirement), toned by consequence: neutral on success, warning only when actionable.

### D5 — One authority, one pointer (the duplication rule)

A small register of **status facts** — capability degradation, budget state, source count,
context occupancy — each naming exactly one persistent authority surface. Every other
appearance is a pointer to the authority or absent. Gateable phrasing: *no registered status
fact renders in more than one persistent surface simultaneously at the pinned viewport.*

Applied to the three measured duplications:

- **"Reduced capability"** (banner + status-bar chip, ~660 px apart): the surface banner/pill is
  the authority on surfaces that render one (it carries the remedy and the 600 wording); the
  Shell chip yields there and remains the global indicator on surfaces without their own
  banner. This is precisely the cross-component arbitration 738 declined to build "until a real
  collision" — finding 12 is the collision, so the deferral is spent, not overridden.
- **"Over budget +N"** (twice within ~40 px): the rail's summary line is the authority; the
  expanded body's escalation row is the *actionable* form (577), visible only when the
  user opens it or the held-gate exception (§D2) fires — not simultaneously rendered
  persistent chrome.
- **Source count** (three renders in ~250 px): the evidence-rail header is the authority when
  the rail is mounted; the in-answer "Sources · N" disclosure exists only where the rail is not
  (narrow mode); the footer line keeps its honest disclaimer (owner's credit — preserved
  verbatim) but drops the redundant count.

### D6 — The height axis, on the existing seam

Introduce block-axis responsiveness through 798's composition layer, not beside it:
`compositionLayout.ts` gains block-size breakpoints. Implementation note recorded, not decided
here: the surface container is `container-type: inline-size` (a box cannot query its own
block size under that containment), so the block axis needs either `container-type: size` on a
height-determined ancestor or viewport `@media (max-height)` — the surface fills the viewport
minus Shell chrome, so both are viable; whichever the implementer picks, it lives in
`compositionLayout` as the one breakpoint authority.

First consumers of the height breakpoint:

- Detailed-mode banner expansion becomes interaction-gated below the breakpoint (§D2);
- the document pane's `min-height: 24rem` floor yields below the breakpoint — this is the
  structural close of **round 8's F5** (734: clearing results with the preview open clips the
  composer below the viewport; the 24 rem floor + fixed chrome exceeds a short window, and the
  composer, bottom of the flex column, pays);
- toolbar-row consolidation (§D2).

### D7 — Enforcement: the owner of the sum is a gate, not a person

Conform to existing instruments; no new harness invented:

1. **`ui-proportion-gate`** (`governance/ui-proportion-baseline.v1.json`) — register every
   persistent chat-surface band with its D1 maximum. A band growing past its registered height
   already fails this gate; extend it with the one assertion shape it lacks: a **share**
   assertion (conversation-zone height ÷ surface height ≥ floor) at the pinned viewport.
2. **New ui-shot steps** (registered in `ui_step_index.json`, covered by
   `check-ui-step-coverage`):
   - a pinned-1366×768 agent-run-done step exercising every band at once (the finding-12
     screen), with `.measure.json` assertions on band heights and content share;
   - a **small-viewport docked-composer step** with results cleared and the preview open —
     closing the coverage gap 807 flagged ("no ui-shot covers the docked composer at a small
     viewport, which is where round 8's F5 layout defect lived") with F5's exact recipe;
   - a segmented-run spine step asserting marker count == segment count (finding 15's
     regression home) and a single-turn step asserting no spine (Lane 2's home, kept here so
     the pair travels together);
   - a scroll-region assertion from `.measure.json` geometry/overflow facts: exactly one
     scrollable region on the surface (finding 13's regression home).
3. **Status-fact singleton check** — the D5 register plus a measure-time assertion that each
   registered fact phrase appears at most once in persistent chrome. Small register + check in
   the existing governance shape; the copy-lint the charter asked for.
4. **`ui-a11y-gate`** — the scroller consolidation must not regress keyboard reachability;
   the axe baseline covers it, run locally (ADR-0026).
5. **Measured audit at closure** — implementation closure requires the honor-system
   `ux-audit-closure` discipline: an independent auditor (≠ implementer), measured (axe +
   geometry facts, not eyeballed), live-verified at the pinned viewport. 810 §d.5 records that
   the owner's manual pass was this audit's first de-facto execution and found this defect
   class on the first try; the closure audit is the second execution.

**Pre-registered baseline sweep** (before any implementation lands, so before/after is
measurable): a vertical resize sweep at fixed 1366 width, window heights 1050 → 900 → 768 →
700, in both disclosure modes, recording per-band heights and content share from
`.measure.json` geometry — finding 12's own discriminating test. Pre-registering it here is the
campaign's own measurement lesson (a measurement probe without pre-registered validity
rules can bless its own leak): the sweep's validity rule is that band identities and the
content-region selector are fixed in advance, not chosen after seeing results.

## Finding 7's structural half — naming and authority in the retrospective drawer

Same D5 principle, different surface. The drawer's four tabs are named by the question they
answer, with scope descriptors and empty states that name their filling condition:

- **Inbox → "Background runs"** with a count badge (empty is legibly zero: "Runs you launch in
  the background appear here"); the attention-queue semantics (565 §26.D) become visible
  instead of tempdoc-only.
- **History → "This conversation"** scope descriptor (it is `/api/thread`, one conversation's
  record); **Timeline → "System activity"** (machine-wide, `/api/action-ledger`) — the two
  near-synonyms stop competing. Sessions keeps its name; it was never part of the collision but
  gets the same scope-descriptor treatment.
- **One authority, one pointer for the designed overlap**: a background run launched with a
  `conversationId` renders in the thread as a *reference* back to its inbox item (a marker of
  identity and authority), not as an unmarked peer copy. The inbox item is the authority for
  the run; the thread segment points at it.
- Regression home: a ui-shot step asserting each tab renders its scope descriptor, plus an
  empty-state assertion on Background runs (charter's named home, unchanged).

Wording is presentation-tier; T-D (action-ledger projection) owns what Timeline's *content*
becomes — this design deliberately renames and scopes without prejudging T-D's projection.

## What this design orphans (deleted or re-styled in the implementing PR, not later)

- The **dotted spine track + unaligned marker styling** and the unbounded marker-per-event
  density behaviour — superseded by D4's segmented form (Lane 2 owns the suppression half).
- The **full-bleed meter tracks** (610 §E.4 row and the 577 rail meters as page-wide thin
  bars) — replaced by compact bounded meters; semantics unchanged.
- The **redundant source-count renders** (footer count and in-answer disclosure while the rail
  is mounted) — deleted; the disclaimer text itself is preserved.
- The **banner pill's oversized-button geometry** — 738 measured it (~76 px) and left it; D2
  finishes it.
- The **status-bar chip's unconditional render** on surfaces that show their own banner —
  gated by D5. 738's "cross-component arbitration not built — revisit on a real collision"
  note is hereby spent (738 stays as written; tempdocs are dated history).
- The **document pane's unconditional 24 rem floor** at short viewports — yields via D6
  (this is the F5 close; the floor survives at normal heights).
- Nothing in 565's model, 600's wording invariant, 687 R5a's composer slot, or 577's held-gate
  semantics is orphaned — those are load-bearing and preserved by construction (§D2, §D4).

## Reach — the principle behind the design

**Principle: every surface has one owner of its space budget; primary content owns the
remainder; chrome claims a summary in flow and detail on demand.** 798 B6-7/B6-8 established
it horizontally and for toast stacking; 559's OverlayHost is the same idea for z-order; this
design is the block-axis instance. It is an allocation-policy principle, not a component rule —
which is why no band-owning workstream could have fixed it (810 §d.1's lens argument: every
band was individually truthful).

Where else it already applies (recorded, **not built now**):

- **T-A's queue/progress box** — the charter's own words ("the box also blocks sidebar
  elements") are this principle violated on the Library/Tasks surface; T-A's design should
  consume the same register/gate rather than inventing a second budget.
- **Search surface** — finding 13's second reproduction; D3 reaches it through the shared
  banner and scroll rule, and its results list belongs in the same proportion register.
- **Health / any future band-bearing surface** — any workstream adding persistent chrome
  anywhere meets the proportion baseline the moment its band is registered.

**Evidence it earns its keep:** future chrome-adding PRs either fit the registered budget or
ship on-demand forms — observable as the proportion/share gate staying green while band count
or feature count grows, and the nested-scroller count staying at one per surface across
releases. The gate firing occasionally is the mechanism working; it firing never while chrome
visibly re-accretes would mean the registered set has rotted — re-audit it.

**Retirement condition:** if a future shell consolidates per-band chrome into a single owned
status ledger (one band, one owner by construction), the per-band budget rows retire with the
bands; if the pinned-viewport share assertion is subsumed by a stronger whole-screen audit tier
becoming mechanical (810 §d.5's human tier turning into a standing gate), the prose principle
folds into that tier's register and this document becomes history. A principle that outlives
the accretion mechanism it polices is apparatus; this one is scoped to "multiple independent
workstreams add bands to shared surfaces," and retires when that stops being how chrome is
made.

## §B — Derisk corrections (2026-08-06, source-verified before implementation)

Two read-only audits (harness + FE code) against this worktree's base (`origin/main` +
PR #370's `worktree-hv-fe` branch, merged here as the Lane-2 baseline the charter promised).
Corrections to the design's premises, each verified at source:

1. **The spine track is already a solid 2px hairline** (`unifiedChatStyles.ts:227-237`; zero
   `dotted` rules in the file). 809's "dotted track" described the 0.2.0 build. D4's track item
   is done-by-baseline.
2. **SearchSurface.ts no longer exists** — its degradation banner was folded into the one
   `chat-degradation` banner rendering in every affordance tier (`UnifiedChatView.test.ts:257-263`).
   "Change both surfaces consistently" is satisfied by construction; finding 13's "second
   surface" reproduction now lives in `renderRetrieveTier()`/`ResultsCard` inside the same
   `.conversation` scroller and is covered by D3's one-scroller rule directly.
3. **The pill's registered ceiling is already 42px** (`governance/ui-proportion-baseline.v1.json`,
   step `chat-proportion`) — 738's ~76px measurement predates the current fixture state; the
   shrink-only ratchet makes further slimming free.
4. **The spine is the column's scroll control by design** (565 §21, restated at
   `UnifiedChatView.ts:2464-2470`): when mounted, the native scrollbar is hidden
   (`scrollbar-gutter: stable`) and the spine is a position-proportional minimap with
   scroll-spy + click-jump and a `role="scrollbar"` viewport thumb. **D4 is revised**: commit
   to the minimap (make it a competent scroll control — draggable thumb, honest affordance)
   rather than de-scrollbarizing it. One scroll affordance per column is thereby preserved.
5. **Marker positions are measured DOM geometry** (`primitives/navigation.ts:266-290`),
   deliberately midpoint-anchored; top-edge alignment is a one-line swap because
   `landmarks[].extent.topFrac` is already published (`navigation.ts:287`). The midpoint
   choice is superseded knowingly (the owner's finding: alignment too loose for navigation).
6. **Clustering is genuinely new**: `computeSpacedPositions` documents aggregation as "a
   future extension" (`primitives/adaptiveSpacing.ts:35-37`) — D4 implements that extension
   (group ideal positions within min spacing → one counted, keyboard-operable badge).
7. **Chip arbitration needs no new signal**: the chip (`components/StatusDeck.ts:522-545`) and
   the banner are two projections of the same `aiState.verdict`
   (`aiStateStore.ts:756-772`); Shell already writes `activeSurface` into `shellContextState`
   on every navigation (`Shell.ts:1443`). Post-#370, banner presence ≠ verdict-degraded
   (info-tier causes are bannerless by finding 9's fix), so the yield rule evaluates the same
   authority predicate the banner uses — `warrantsSearchDegradationBanner(verdict)` in
   `readinessNotice.ts` — from StatusDeck: the chip yields exactly when the active surface is
   the chat surface AND that predicate says the surface shows a banner. One authority
   predicate, two consumers, no state fork.
8. **The "open all sources" affordance already exists end-to-end**: the identical
   `<jf-sources-pane>` is mounted in Shell's OverlayHost right-drawer
   (`Shell.ts:2346-2352`), opened by `toggleSources()`, which the "Sources · N" toolbar
   button already calls — it is merely `display:none`'d at wide widths
   (`unifiedChatStyles.ts:899-903`). D3's rail conversion is a top-N slice + dropping two
   `overflow-y:auto` rules + un-hiding/adding the open-all row.
9. **`check-intent-tier-coverage` cannot be tripped by this work** (it regex-parses
   `presetByShape` and requires a `submitSearch` token; it knows nothing of DOM/toolbars).
   The 687 R5a protected element is the `.composer` div itself (comment at
   `UnifiedChatView.ts:2540-2549`); row consolidation *inside* `renderComposerBlock()` is
   safe.
10. **Disclosure tests pin Detailed force-expansion** (`UnifiedChatView.test.ts:335-408`,
    esp. `:364`): the height gate must default to "above breakpoint" when layout/matchMedia
    is unavailable (jsdom), mirroring `wideZone`'s test-friendly default, and add a
    below-breakpoint case rather than rewriting the five existing ones.
11. **Enforcement wiring is nearly pre-built**: `chat-proportion`/`chat-occlusion` fixture
    steps already exist (deterministic `--fixtures`, no dev stack) with two chat selectors
    registered; the share assertion is a ~15-line fourth constraint kind in
    `ui_proportion_gate.py`; the single-scroller enumeration already exists as
    `ui_check.py:186-212`'s scroll-walk and needs porting into `ui_measure.py`'s geometry;
    the status-fact phrase probe is the only net-new capture. The F5 step extends
    `chat-occlusion`'s exact setup (search → open pane) with a clear-results action.
    `core.unified-chat-surface` is already exempt in the step-coverage register, so new
    steps ride the existing exemption; the a11y baseline needs chat-step rows added
    (`knownRules: []`).
12. **Mechanism decision (research pass)**: block-axis breakpoints use viewport
    `@media (max-height: 820px)` routed through `compositionLayout` as the one breakpoint
    authority — `container-type: size` collapse semantics make it strictly riskier here for
    no benefit (the surface fills the viewport minus fixed Shell chrome).

13. **The retrospective drawer's History tab is NOT conversation-scoped** (W3 source check,
    correcting this document's own §Finding-7 assumption "it is `/api/thread`, one conversation's
    record"). `AgentSessionController.loadHistory` (~:1820-1845) fetches
    `/api/action-ledger?originator=agent&correlationId=<this.sessionId>` and keeps
    `kind === 'operation'` — the filter key is the ACTIVE AGENT SESSION id, which
    `loadForkedConversation` resets (~:1955); `/api/thread` is not involved. So the honest rename is
    **History → "This run"**, not "This conversation". Timeline's assumption held verbatim
    (`ActionLedgerClient.unifiedActivity` = unfiltered `/api/action-ledger` + the FE Effect Journal,
    machine-wide) → **"System activity"**; Inbox is `/api/presence`, cross-conversation →
    **"Background runs"**; Sessions is `/api/chat/sessions` (the run roster) and keeps its name.
14. **The chip's yield is scoped to `degraded`, not to every banner-warranting verdict** (W3
    implementation decision). `warrantsSearchDegradationBanner` also returns true for `unreachable`,
    but that is a CONNECTION fact, not the capability-degradation register row — yielding it would
    make the status bar report the AI engine's "Online" while the backend is gone, i.e. the
    truthfulness regression the rule exists to prevent. Pinned by a fourth unit test alongside
    finding 9's info-tier case.

Settled parameters (autonomy granted 2026-08-06): content floor ≥ 55% Simple / ≥ 45%
Detailed at 1366×768; height breakpoint 820px; rail index top-3 + "Open all · N"; cluster
spacing 14px.

## §P — Implementation plan (contract)

Four bounded chunks; W1+W4 parallel (disjoint files), then W2, then W3, in this worktree.

- **W1 — Height axis + bands** (`compositionLayout` max-height authority; Detailed
  interaction-gating below breakpoint with jsdom-safe default + test updates; document-pane
  floor yield below breakpoint = the F5 close; activity-rail expanded-body meters and the
  610 context meter restyled to compact fixed-width with unit + ceiling; pill button slim).
- **W2 — Spine minimap + evidence rail** (top-edge anchoring; clustering extension in
  `adaptiveSpacing` + counted badge; draggable thumb if absent; marker treatment
  de-collision via outline-vs-filled within the statusTone authority; rail → top-3
  non-scrolling index + open-all row; source-count single authority).
- **W3 — Chip arbitration + retro drawer** (StatusDeck yield rule via the
  `warrantsSearchDegradationBanner` authority + `shellContextState`; RetrospectivePanel tab
  renames/scope descriptors/empty states; thread-side background-run reference marker).
- **W4 — Measurement + gates** (`ui_measure.py` scrollableRegions + status-fact probe;
  `ui_proportion_gate.py` share constraint; baseline rows for all bands at a new pinned
  1366×768 all-bands fixture step; F5 step; spine steps; a11y rows; `ui_step_index.json`).

Verification per chunk: `npm run typecheck` + affected unit tests; whole-arc: full FE unit
suite, `./gradlew.bat build -x test`, the fixture-backed gates (`ui-proportion-gate`,
`ui-a11y-gate`), and the pre-registered resize sweep + independent measured audit before
closure (§D7.5).

## §V — Implementation & verification record (2026-08-06)

Implemented in four delegated chunks (W1 opus, W2 opus, W3 opus, W4 sonnet) + one closure
chunk (opus), each verified green before the next; orchestrator judged every report against
this document. `feat(814 W1..W4)` + `feat(814 closure)` commits on this branch.

**What shipped, against the design:** D1 (budget registered + share-floor gates at 1366×768
Simple and 1366×900 Detailed), D2 (pill slim to 34px; rail collapsed-by-default bound + the
held-budget-gate auto-expand exception; compact fixed-width meters), D3 (rail → top-3
non-scrolling index + "Open all · N" into the existing OverlayHost drawer; `.conversation` is
the one scroller, witnessed by the gate), D4-as-revised (top-edge anchoring superseding the
565 midpoint choice; clustering as `adaptiveSpacing`'s documented future extension —
landmark-immune counted badges; drag already existed and was verified, not rebuilt;
outline-vs-filled de-collision + diamond steer marker within the statusTone authority), D5
(chip yields to the banner via the shared `warrantsSearchDegradationBanner` predicate —
scoped to `kind === 'degraded'`, §B.14; source-count single authority incl. the toolbar chip
render-gate; `status-facts.v1.json` + measure-time singleton probe), D6 (`@media (max-height:
820px)` authority in `compositionLayout`; Detailed interaction-gated below it; document-pane
floor 24rem → 14rem short = the 734 F5 close), D7 (share/`maxBottomPx`/`absentSelectors`/
`min`+`maxScrollableRegions`/statusFacts constraint kinds in `ui_proportion_gate.py`; steps
`chat-bands`, `chat-bands-detailed`, `chat-composer-small`, `chat-spine-single`; a11y rows).
Finding-7 half: drawer tabs renamed by verified scope — Inbox → **Background runs** (+count
badge), Timeline → **System activity**, History → **This run** (scope CORRECTION: it is
agent-session-scoped `action-ledger?originator=agent&correlationId=<session>`, not
`/api/thread` — the design's "This conversation" would have been the third wrong name on that
tab), Sessions kept; empty states name their filling condition; thread-side background
segments render an operable `background run ↗` pointer to the inbox authority.

**Independent measured audit (auditor ≠ implementers): CLOSE-WITH-NOTES → notes fixed.**
The pre-registered resize sweep (1366 wide; 1050/900/768/700; both disclosure modes;
selectors fixed in advance) measured chrome as a **constant 308px** at every point (pill 34,
rail 25, composer 152, header 32) with `.conversation-zone` absorbing **100% of
compression** — shares 0.686/0.630/0.560/0.513 Simple, 0.609/0.539/0.560/0.513 Detailed
(below the 820 breakpoint Detailed renders the pill, as designed). All spot-checks passed
(composer bottom 724 ≤ 768; status-fact singletons; no spine single-turn; scrollableCount ≤ 1
everywhere). The audit's three findings were fixed before closure: the §D2 held-gate
auto-expand (delivered by no W-chunk — an inherited Lane-2 collapse the design specified a
correction to), the Detailed floor + expanded-banner ceiling (now gated: 110 ≤ 176, share
0.5385 ≥ 0.45 at 900), and the one-scroller assertion's vacuity (`minScrollableRegions: 1`
now witnesses a real scroller — and immediately exposed a real axe `scrollable-region-
focusable` defect, fixed at source with `tabindex="0"`, not baselined).

**Final state:** FE 384 files / 4061 tests green; `ui-proportion-gate` exit 0 (27 rows /
6 steps); `ui-a11y-gate` exit 0 (12 steps, 0 new); `check-ui-step-coverage` green; ui-web
presentation/kernel gate subset green; typecheck clean. *(Counts stale — see the review-pass
correction (e) below for the re-measured before/after.)*

**§B corrections discovered during implementation:**
- **§B.10 correction (W1):** the unit-test env is happy-dom (not jsdom) and *implements*
  matchMedia with a default 1024×768 viewport — already below the 820 breakpoint. The suite
  now declares its viewport explicitly instead of inheriting the incidental default.
- **§B.14 (W3):** the chip yield is scoped to `verdict.kind === 'degraded'` — yielding
  `unreachable` would report the AI engine's own state while the backend is gone, the exact
  truthfulness regression D5 exists to prevent.

**Residuals (recorded, not silently capped):**
1. The activity rail's **expanded body** and the **evidence rail** are fixture-unreachable
   (both need a real agent SSE `done` payload through the typed stream protocol; the rail
   additionally an affordance round-trip). Their obligations are covered indirectly
   (`maxScrollableRegions: 1` catches a rail scroller the moment it mounts; the held-gate
   exception is unit-tested at the store seam) — a fixtures extension for the agent stream is
   the natural follow-up if a future finding lands there.
2. The share guarantee is pinned, not scale-invariant: chrome is a constant 308px, so Simple
   mode crosses 45% between 650 and 600px window height. Below the pinned viewport the
   guarantee is "chrome stops growing", not "share holds".
3. ~~Three of five status-fact phrases measure 0 in current fixtures (the duplication they
   guard doesn't exist to witness); "Service degraded" measures 1 with the chip yield
   active — the register's positive case.~~ **WRONG on both halves — superseded by the
   review-pass correction below** (the register held four phrases, not five; and the
   measured 1 was the invisible aria-live announcer, not a positive case).
4. D2's "toolbar rows consolidate toward one row" was **not needed**: with the trims above,
   both share floors gate green with the toolbar untouched — recorded here per
   tempdoc-is-contract rather than silently dropped; the proportion register now owns the
   question (composer ceiling 220px).

### Review-pass correction (2026-08-06, reviewer ≠ implementers)

A refute-first review of the shipped D5/D7 instruments found five defects. All five are fixed
on top of the record above; this subsection corrects what §V claimed, it does not rewrite it.

**(a) Residual 3 was wrong, and the measurement under it was vacuous.** The `statusFacts`
probe concatenated every leaf's text with **no visibility filter**, so StatusDeck's 1×1
`.visually-hidden` aria-live announcer (`data-testid="verdict-announcer"`,
`StatusDeck.ts:696-703`) counted as a "persistent render". That is what "Service degraded
measures 1 … the register's positive case" actually measured: an invisible node that mirrors
the verdict headline **by design**. Two consequences, both bad — the positive case witnessed
nothing, and any surface that *visibly* showed the headline once would have counted 2 and
FALSE-FAILED a rule it does not break. The probe now skips `display:none`,
`visibility:hidden`, and effectively-zero render boxes (`ui_measure.py` `_JS_STATUS_FACTS`,
8 browser-backed unit tests in `tests/test_ui_measure.py`).

Deeper: the phrase-singleton register **structurally cannot** witness the chip-vs-banner
duplication at all, because that duplication is **cross-worded** — the banner says "Semantic
search degraded." (`readinessNotice.ts:662`) and the chip says "Service degraded"
(`verdict.ts:283`), so counting either string yields 1 whether the yield works or not. The
register is now scoped honestly to the same-string class it *can* witness ("Over budget",
"Sources ·" — the two finding 12 measured); "Reduced capability" and "Service degraded" are
removed as rows. The fact-level rule keeps two homes instead: `StatusDeck.test.ts`'s
yield-on/yield-off unit tests, and a new capture witness (c). **Standing residual, stated
plainly:** both surviving phrases measure 0 in every current fixture — the duplication they
guard is not reachable under `--fixtures` (the rail's expanded body and the evidence rail,
residual 1), so the phrase-singleton check is a *live tripwire with no positive case yet*, not
a passing assertion. What replaced residual 3's false positive case is (c)'s
`forbiddenVisibleText` witness, which has one (negative control run).

**(b) The Detailed banner's ceiling had no floor.** `maxHeightPx: 176` passes at the 34px
collapsed pill — i.e. exactly when Detailed expansion has regressed and the row is measuring
the wrong element. `ui_proportion_gate.py` gains `minHeightPx` (violation
`UNDER_MIN_HEIGHT`, 6 unit tests, CLI echo, schema), and `chat-bands-detailed` registers
`minHeightPx: 64`. Measured: 110px, inside 64…176.

**(c) The chip yield now has a capture-level witness.** It had none: under the degraded
fixtures `chat-bands` submits an ask, the stubbed SSE never drains, `aiState.activity` stays
`thinking`, and the chip reads "Thinking…" — measured "Thinking" 1 / "Service degraded" 0,
green for the wrong reason. New isolated step **`chat-chip-yield`** reaches the same degraded
chat surface with no activity overlay (it simply does not submit — the banner is chrome, not a
function of the thread), so the chip's label *is* the verdict projection. The register's new
step-scoped `forbiddenVisibleText: ["Service degraded"]` (violation `FORBIDDEN_TEXT_VISIBLE`,
counted by the same visibility-filtered probe) then discriminates, with
`requiredSelectors: [".degradation-banner-collapsed"]` as the non-vacuity companion.
**Negative control run** (not inferred): with `yieldsDegradationToBanner()` forced to
`false`, the same capture measures "Service degraded" **1** and the gate goes red; restored,
**0**.

**(d) §D7.2's segmented-spine step shipped** as **`chat-spine-multi`** — the pair's positive
half, which W4 had deferred in a code comment. The prior attempt's diagnosis (rapid submits
race the stubbed-SSE drain) was true but not the blocker: `spineItems()` reads
`mergedTimeline()`, built from the canonical **record** (`/api/thread`) plus the live agent
overlay, and never from `this.thread`, the array ask-submits push into. Measured two rendered
user bubbles, `affordance: 'agent'`, `wideZone: true` — and still zero `.run-spine`, because
the spine's own input was empty. The step therefore uses a new `degraded-thread` fixtures
variant (a two-user-turn thread record + the seeded per-tab `lastViewedConversation` pointer),
promotes the affordance first (the `retrieve` tier renders the hit-list and owns no thread
history), and asserts `.run-spine` PRESENT via the gate's new step-level `requiredSelectors`
(violation `MISSING_REQUIRED`). **Division of labour:** the capture witnesses spine
*presence*; "marker count == segment count" stays unit-tier (`adaptiveSpacing` /
`UnifiedChatView` tests) because segmented `nodeId`s need a real agent SSE run.
- Making that step render a **record-path** user turn for the first time immediately exposed a
  real axe `color-contrast` defect the fixtures had never reached: `.turn-time` (the ambient
  turn-boundary timestamp) used the surface-calibrated `--text-secondary` while sitting inside
  `.message.user`, which paints `--accent-tint` — measured **1.27:1** (#9bd8d0 on #00ccb2).
  Fixed at source (`unifiedChatStyles.ts`: the bubble's own `--accent-on-tint`, recession by
  size not by fading into the tint), **not baselined** — the same call §C's finding C made.

**(e) Stale counts in "Final state" above.** Re-measured at this review pass: the
`ui-proportion-gate` line's "27 rows / 6 steps" was already stale when written — the register
produced **29 rows / 7 steps** before these fixes and **34 rows / 9 steps** after; the
`ui-a11y-gate` line's "12 steps" was **13** before and is **15** now; and "three of five
status-fact phrases" miscounted a **four**-row register. Post-fix verification: FE **386 files
/ 4190 tests** green, typecheck clean, `jseval ui-proportion-gate` exit 0 (34 rows / 9 steps),
`jseval ui-a11y-gate` exit 0 (15 steps, 0 new), `check-ui-step-coverage` green, the full jseval
pytest suite **2733 passed / 2 skipped**.

## Owner decisions this design surfaced (resolved 2026-08-06 under granted autonomy — recorded, not re-asked)

1. **The two floor numbers** (D1: Simple-mode share target, Detailed-mode hard floor) — the
   design proposes ≥ 55% / ≥ 45% at 1366×768; the register makes them owner-tunable.
2. **Chip-vs-banner arbitration direction** (D5): banner-wins-on-owned-surfaces is proposed;
   the inverse (chip persistent, banner transient-on-change) is coherent but weakens the
   remedy affordance's discoverability. Proposal stands unless the owner prefers the inverse.
3. **Detailed-mode behaviour below the height breakpoint** (D6): interaction-gated expansion is
   proposed; the alternative (Detailed always expands, content floor drops to the 45% hard
   floor) preserves current Detailed semantics at more height cost.

## §C — Closure-audit remediation (2026-08-06; implementer ≠ auditor)

The independent measured audit returned CLOSE-WITH-NOTES with three findings. All three are fixed,
each with a runnable check rather than a passing read.

- **B (user-visible) — the §D2 held-gate exception was never implemented.** `activityRailExpanded`
  had only its two reset sites and the user's own `@toggle`, so the budget-gate decision row ("Add
  tokens / Finish with what it has / Stop") rendered inside a collapsed `<details>` — the one state
  where §D2 says that row *is* the primary thing on screen. A `willUpdate` hook now keys on the
  TRANSITION into `agentCtrl.budgetGate != null` (the same predicate the summary's "Paused —
  awaiting budget" chip renders on) and opens the rail once (`UnifiedChatView.ts`,
  `budgetGateWasHeld` + `willUpdate`). Three tests: the transition opens it; a user re-collapse
  while still parked sticks across further re-renders; a DONE transition does not auto-expand.
- **A — the Detailed-mode floor was prose-only.** No capture existed in Detailed disclosure, so the
  expanded banner had no registered ceiling and the ≥ 0.45 Detailed floor asserted nothing. New
  isolated step `chat-bands-detailed` at **1366×900** (above the 820px block-axis breakpoint, so
  W1's own gate lets Detailed expand) via a new `degraded-detailed` fixtures variant — identical to
  `degraded` except it does NOT flip `ui.mode` to simple, so the app boots into Advanced from the
  same `/api/settings/v2` seed the product uses. Measured: expanded `.degradation-banner` **110px**
  (ceiling 176), `.conversation-zone` share **0.5385** (floor 0.45), rail 25 (40), composer 152 (220).
- **C — the one-scroller assertion was vacuous.** Every captured state had `scrollableCount` 0, so
  `maxScrollableRegions: 1` never witnessed a scroller. `ui_proportion_gate.py` gains the paired
  floor `minScrollableRegions` (violation `NO_SCROLLER`; five unit tests, including the
  zero-scroller vacuity case), and `chat-bands-detailed` submits one overflowing turn and declares
  **min 1 / max 1** — the capture witnesses `div#run-conversation.conversation`, scrollDelta 398.
  - Making a capture actually overflow immediately surfaced a real a11y defect the vacuity had
    hidden: axe `scrollable-region-focusable` — the surface's only scroll region was not
    keyboard-focusable. Fixed at the source (`tabindex="0"` on `#run-conversation`), not baselined;
    `ui-a11y-gate` is clean with `knownRules: []` for the new step.
  - **Evidence rail: NOT reachable under `--fixtures`** (recorded, not faked).
    `evidenceRailMounted()` needs `agentCtrl.answerSources.length > 0`, and only two writers exist —
    a real agent SSE `done` payload (the typed protocol `install_fixtures` stubs empty, W4's own
    finding) or `hydrateAnswerEvidenceFromRecord` off a `/api/thread` record, which is reached only
    from `refreshUnifiedThread` and no-ops while `agentCtrl` is still null. The latter would
    additionally need an affordance round-trip (retrieve → agent → retrieve → submit → agent) plus a
    hand-authored thread fixture. Its no-scroll obligation stays covered by `maxScrollableRegions: 1`
    the moment it does mount.

Verification: `npm run typecheck` clean; full FE unit suite 384 files / 4061 tests green;
`jseval ui-proportion-gate` exit 0 over 27 rows / 6 steps; `jseval ui-a11y-gate` exit 0 over 12
steps; `check-ui-step-coverage` green; the jseval gate pytests 37 passed. *(Gate counts stale
as of the 2026-08-06 review pass — see §V's review-pass correction (e).)*

Note on C.3 above (evidence-rail reachability): the "hand-authored thread fixture" it prices as
part of that cost now **exists** for a different obligation — `ui_fixtures._thread_body`, the
`degraded-thread` variant added for `chat-spine-multi`. That does not make the evidence rail
reachable (it carries no sources, and the rail additionally needs the affordance round-trip),
but a future attempt starts from a working `/api/thread` fixture rather than from nothing.

## §R — Session retrospective (2026-08-06, closing the T-B arc)

The arc ran design → research → derisk → four delegated implementation chunks → independent
measured audit → closure fixes → merge ([PR #376](https://github.com/eliasjustus/justsearch/pull/376))
→ post-merge fold ([PR #378](https://github.com/eliasjustus/justsearch/pull/378)) → refute-first
review → review fixes ([PR #387](https://github.com/eliasjustus/justsearch/pull/387)), all in one
day. Lessons worth keeping, written to stand without the session transcript.

### What worked and should be preserved

1. **Adversarial verification tiers paid for themselves in found defects, every time they ran.**
   The pre-merge audit (auditor ≠ implementers) found three gaps the implementers' own green
   runs could not see; the post-merge refute-first pass found four more, including a
   measurement instrument counting an invisible `aria-live` node as chrome. Every finding
   converted into a merged fix. The pattern that made this cheap: each verifier re-derives
   evidence itself (re-runs gates, re-measures) instead of auditing reports.
2. **Negative controls on new instruments.** The chip-yield capture witness was accepted only
   after forcing the yield off and watching the gate turn red. An instrument that has never
   been seen to fail has not been shown to measure anything — this should be standard for
   every new gate/probe.
3. **Pre-registered measurement validity** (selectors and band identities fixed before results
   are seen) let the resize sweep be re-run by three different agents with directly comparable
   numbers (chrome constant at 308 px in all three).
4. **Conforming to existing seams** (`ui-proportion-gate`, `compositionLayout`, OverlayHost)
   meant the enforcement layer shipped as register rows + small constraint kinds instead of a
   new harness.

### Frictions and near-misses (with the change each argues for)

1. **Stale-base fold near-miss (the arc's riskiest moment).** `fold-observations.mjs --apply`
   was run in a checkout whose `main` was ~26 commits behind `origin/main` (blocked from
   pulling by unrelated in-progress work in the shared checkout — see "known unrelated dirty
   work" below). The fold silently produced an `observations.md` that would have rolled back
   newer upstream conditions and it consumed an untracked shard; only a diff review before
   publication caught it. Recovery: restore, re-fold in a fresh `origin/main` worktree,
   reconstruct the two entries that existed nowhere else. **Change:** run the post-merge fold
   only in an origin-fresh checkout, and the tool should refuse `--apply` when HEAD is behind
   `origin/main` on `docs/observations.md` (filed in the observations inbox).
2. **ui-shot auto-serve can silently measure stale code.** A Vite server surviving from an
   earlier commit kept serving while later commits landed; intermediate gate runs measured
   W1-era code until the audit checked the server's recorded `head` provenance
   (`tmp/ui-shot-server.json`) and restarted it. The harness records provenance but does not
   act on it. **Change candidate:** auto-serve should compare the recorded `head` to the
   current HEAD and restart on mismatch. It also caused an `EPERM` npm failure in a concurrent
   Gradle web build (file lock on a native module).
3. **Merge races against a busy `main`.** Both PRs went `BEHIND` during their own CI and needed
   `gh pr update-branch` + a full re-run; additionally, `gh pr checks --watch` started right
   after an update can bind to the *previous* run's check set and exit green while the new
   head's checks are still pending — one merge attempt was made on that stale green (harmless:
   the merge API refused). **Habit:** after `update-branch`, verify the watched run belongs to
   the new head SHA before trusting its exit.
4. **Tempdoc number churn under parallel threads.** This document was born 811, renumbered to
   813 and then 814 within hours as sibling threads (T-A/T-C/T-D) claimed numbers.
   `check-tempdoc-numbers` caught every collision (working as designed); the lesson is only to
   re-run it immediately before each push, not once at creation.
5. **Session-id aliasing in delegated work.** A subagent's `note-observation.mjs` call wrote to
   the *orchestrating session's* shard (subagents share the session id), which later collided
   with the orchestrator's own copy of that shard. Harmless here (append-only merge), but
   shard tooling should not assume one writer per session id.

### Verification commands (for re-running this work's gates)

From a checkout of current `main` (jseval invoked from a worktree needs the printed
`PYTHONPATH` remedy):

```
jseval ui-proportion-gate     # exit 0; chat-surface rows incl. shares, floors, scroller counts
jseval ui-a11y-gate           # exit 0; chat steps present, 0 new violations
node scripts/ci/check-ui-step-coverage.mjs
cd modules/ui-web && npm run typecheck && npm run test:unit:run
```

### Known unrelated dirty work / non-canonical context

- At the time of this arc, the shared main checkout carried unrelated uncommitted work
  (a tempdoc-617 edit belonging to a concurrent session) that blocked `git pull` there; this
  document's work was therefore done entirely in worktrees branched from `origin/main`.
- The measured numbers quoted in §V/§R (band heights, shares, 308 px chrome) come from the
  deterministic `--fixtures` capture states at pinned viewports, not from a live indexed
  corpus; the owner-observed 0.2.0 numbers in §Baseline predate this work. Neither set should
  be quoted as current without re-running the gates above.
- Session-private artifacts (capture PNGs, sweep JSON, audit scripts) lived in a session
  scratch directory and are not archived; the gates re-derive all of them.

## §D8 — Residual-closure design: making the last two obligations capture-reachable (2026-08-06)

§V residual 1 left the activity rail's expanded body and the evidence rail fixture-unreachable,
so §D2's bounded-expansion rule and §D3's rail no-scroll rule rest on unit tests and indirect
assertions. This section is the settled design for closing that, plus the two §R tooling
guards. Design only; source-verified against current `main`.

**Mechanism correction to §C/§V:** the agent stream was never "stubbed as an empty closed
stream". The browser path goes through `streamViaHost` → POST `/api/chat/dispatch`
(`plugin-api/capabilities/ai.ts`), which the fixtures' route predicate does not match — the
request falls through to the unmapped-JSON default `{}`, both stream parsers see no terminal
frame, and the run ends as a `STREAM_INCOMPLETE` error entry. That is also the origin of the
"Connection lost — the response was interrupted" row visible in the existing `chat-bands`
capture. Any fixture must intercept `/api/chat/dispatch` (attach/resume paths use
`consumeShapeStream` directly).

### D8.1 — Record-path first: take what the thread fixture can already carry

Two of the unreachable obligations do not need a stream at all, because the view hydrates them
from the `/api/thread/{id}` record: `answerSources` also populates via
`hydrateAnswerEvidenceFromRecord` (an `ASSISTANT_MESSAGE` event with non-empty
`attributes.sources`), and the rail's lifecycle row reads `lifecycles[]` from the same record.
Extending the existing `degraded-thread` fixture body with those two fields mounts the
**evidence rail** and renders the **lifecycle row** with no new transport. This is the cheap
half and lands first.

### D8.2 — One deterministic SSE fixture for the three stream-only facts

Only `budgetUpdates`, `budgetGate`, and `contextGate` have no source but the stream. Design:

- A single static SSE body (Playwright fulfills complete bodies only; both parsers are
  buffer-based frame-splitters, so a whole multi-frame body yields every event in order —
  byte-stable, which is what a capture wants).
- Frame grammar mirrors the FE test's `sseChunk` helper; **payload shapes are validated at
  build time against `scripts/codegen/shapes.fixture.json`'s `core.agent-run` `eventSchema`**
  — the machine-readable projection of the Java conversation-shape catalog (which is off-wire,
  so the `wire` gate cannot cover this drift). ~30 lines of validation, no new schema: the
  fixture fails loudly the day the catalog changes instead of capture rows passing on stale
  shapes.
- The DONE sequence: `session_started` → `budget_update` (carrying `promptTokens` +
  `contextWindow` so the horizon meter renders) → `chunk` → `done{sources[]}` — after which
  the expanded rail shows the budget row + bar, the context meter, and the DONE-neutral
  summary, all becoming gateable: a capture step opens the `<details>` and registers the
  **§D2 bounded-expansion assertion** (content share floor with the rail open — the half the
  closure audit recorded as untested), and the mounted evidence rail must NOT appear in
  `scrollableRegions` (§D3's direct witness) while the "Sources · N" toolbar chip is absent
  (§D5's structural gate, on camera).

### D8.3 — Honest limits (recorded, not designed around)

- **A PAUSED-awaiting-budget capture is a priced design fork, deferred.** A static body always
  terminates, so a stream ending on `budget_gate` trips the parsers' `STREAM_INCOMPLETE`
  guard. The fork is **THREE-way**, not two-way (amended 2026-08-06 during the §D8
  implementation, once the `agent-run` variant made the transport concrete):
  1. **Accept a trailing error entry in the capture** — cheapest. The screenshot then shows a
     PAUSED rail beside a "Connection lost" row no real paused run produces. It misrepresents
     the state *on camera*, which is the one thing a capture must not do.
  2. **Reset the gate out-of-band after a terminal frame** — reaches a clean paused rail, but
     the state photographed was assembled by the harness rather than reached by the app, so the
     assertion stops witnessing the transition it names.
  3. **A held-open SSE stub** — a real local endpoint (NOT `route.fulfill`, which by
     construction serves only a complete body) that emits `session_started` → `budget_update` →
     `budget_gate` and then holds the connection open. This is the HONEST option: the run
     genuinely is parked mid-stream, which is what PAUSED means, so both the screenshot and the
     assertions are about the real state. It is also the expensive one — a socket whose lifetime
     the step owns, a teardown path on every failure route, and a step bounded by something other
     than "the body ended": new harness machinery of a kind `install_fixtures` has none of today
     (it is a pure Playwright route-mock).
  **Deferred to (3) when triggered, and not re-priced in the meantime.** Owner-agreed trigger:
  the next human validation pass flagging the PAUSED state, or the first shipped-looking-wrong
  regression in the held-gate surface. Absent either, the held-gate auto-expand keeps its
  unit-tier home (it is transition-triggered, and `budget_update`/`done` clear the gate — a
  static-body capture would race its own fixture).
- **The over-budget remedy buttons are capture-unreachable by construction**: they additionally
  require `runInFlight`, which is only true while the stream's abort controller is live —
  never after a completed static body. Unit-tier home stands.

### D8.4 — The two §R tooling guards (slot-in points verified)

- **`fold-observations.mjs` base-freshness guard:** a precondition inside `foldShards`, beside
  the existing malformed-store refusal — refuse `--apply` when `origin/main` is not an
  ancestor of HEAD for the store's checkout (one `git merge-base --is-ancestor` shell-out; an
  explicit override flag for deliberate offline use). Failing there composes with the existing
  crash-safety ordering (store is written before shards are deleted), so a refusal leaves
  every shard intact.
- **ui-shot auto-serve provenance clause:** `_is_server_alive` already reads everything except
  the `provenance` it records; add a final clause returning stale when the recorded `head`
  differs from the current one, so reuse falls through to a fresh server. Closes the
  measured-stale-code hazard §R.2 describes; no new state, reads a field already written.

**Orphans:** the unmapped-`{}` fallthrough for `/api/chat/dispatch` (and the accidental
"Connection lost" row it paints into agent-mode captures) is superseded for the new fixture
variant; §C/§V's "empty closed stream" wording is corrected by this section. Nothing else is
displaced — both guards are additive clauses at existing decision points.

**Principle (named, not built beyond need): a fixture's schema authority is the generated
catalog projection, enforced at fixture build time.** `shapes.fixture.json` already exists as
the drift-gated projection; the fixture validates against it rather than hand-shaping payloads
(projection, not fork). Candidate wider scope: `_thread_body()` could be validated against the
FE's `ThreadLifecycle` zod schema the same way. Earning its keep looks like: a catalog change
breaks the fixture build loudly instead of capture assertions passing against stale shapes.
Retirement: if a record-and-replay mechanism (captured real-run SSE bytes replayed verbatim)
lands, hand-authored frame lists and their validator retire together.

### §D8 — Implementation record (2026-08-06, worker `814-d8`)

**Both §V residuals are now capture-reachable and gated.** The activity rail's expanded body and
the docked evidence rail have registered rows in `ui-proportion-baseline.v1.json`; the "Connection
lost" row is gone from agent-mode captures and its absence is asserted rather than assumed.

**What landed**

- **`scripts/jseval/jseval/agent_stream_fixture.py`** (new) — `sse_frame(event, payload)` +
  `DONE_RUN` (`session_started` → `budget_update` → `chunk` → `done`) + `DONE_RUN_BODY`, every
  payload validated at IMPORT time against `scripts/codegen/shapes.fixture.json`'s `core.agent-run`
  `eventSchema` (required-field presence, declared type, ENUM membership, ARRAY element kind,
  undeclared-field rejection). Projection, not fork: the schema is read from the generated catalog,
  never restated.
- **`ui_fixtures.py`** — new `agent-run` variant: the thread record gains `attributes.sources`
  (3 full `AgentSource` rows on the newest assistant message) + a DONE `lifecycles[]` entry;
  `/api/chat/dispatch` is fulfilled with `DONE_RUN_BODY` as `text/event-stream`;
  `/api/chat/agent/tools` reports `{available: true}`. That last one was the LIVE-FOUND blocker —
  without it `ctrl.available` never becomes `true` and `UnifiedChatView.send()` drops the submit
  silently. `degraded-thread` is byte-for-byte unchanged (asserted by a test), so `chat-spine-multi`
  is untouched.
- **`ui_check.py`** — `_drive_agent_run_to_done` + two steps, `chat-evidence-rail` and
  `chat-activity-rail-open`, both 1366×768 on the `agent-run` variant. Order is load-bearing:
  Delegate FIRST (the `escalateAsk` path re-derives the affordance and would demote agent mode, so
  the agent branch of `send()` is reachable only from an already-agent affordance), then submit,
  then the record round-trip.
- **`ui_measure.py` / `ui_proportion_gate.py` / the register + its schema** — new step-level
  `nonScrollableSelectors` constraint (§D3's direct witness on a NAMED element), judged off a new
  per-element `scrollable` flag computed with the SAME predicate that builds `scrollableRegions`.
- **The two §R tooling guards** — `fold-observations.mjs` gains `isBaseFresh()` + a refusal inside
  `foldShards` beside the malformed-store check, with `--allow-stale`; `ui_shot._is_server_alive`
  gains the provenance-head clause, kept last so the cheap gates still short-circuit.

**Measured (1366×768, `--fixtures`)**

| Fact | `chat-evidence-rail` | `chat-activity-rail-open` |
|---|---|---|
| `.evidence-rail` | 320×392, scrollDelta 2, **not a scroller** | 320×331, not a scroller |
| `.activity-rail` | 25px (collapsed summary) | **86px** (expanded body) |
| `.conversation-zone` share | **0.5600** (floor 0.55) | **0.4729** (floor 0.45) |
| `.composer` | 152px | 152px |
| `scrollableCount` | 1 (`.conversation`, delta 107) | 1 (`.conversation`, delta 168) |
| "Connection lost" | **0** | **0** |

Gate state: `jseval ui-proportion-gate` exit 0, **53 rows / 11 steps, 0 non-ok** (was 34 rows /
9 steps); `jseval ui-a11y-gate` exit 0 (12 surfaces, 0 NEW); `check-ui-step-coverage` green;
`check-tempdoc-numbers` OK; the jseval ui-\* pytest subset 152 passed / 8 skipped;
`agent-analytics` 31/31 test files. No `modules/ui-web` source was changed by this work.

**Negative controls — every new assertion was observed RED before being trusted**

| # | Break | Observed red |
|---|---|---|
| A | record's `attributes.sources` emptied | step FAILS on the `.evidence-rail` wait; gate rows `MISSING_REQUIRED` + `IS_SCROLLER` ("selector not found … asserts nothing") |
| B | `.evidence-rail` CSS `overflow: hidden` → `overflow-y: auto` (the exact §D3 regression) | `IS_SCROLLER` scrollDelta 2, **and** `MULTI_SCROLL` count 2 — the new row NAMES the element, the count row only says "too many" |
| C | `/api/chat/dispatch` interception disabled | `FORBIDDEN_TEXT_VISIBLE: "Connection lost" count=1` (the rail still mounted, so the delta is exactly the stream) |
| D | `done.iterationsUsed` renamed / `budget_update.tokensRemaining` typed as a string | import-time `AgentStreamFixtureError: … event 'done': required field 'iterationsUsed' (NUMBER) is missing from the fixture payload` / `… field 'tokensRemaining' is declared NUMBER but the fixture supplies str` |
| E | `budget_update` stripped of `promptTokens`/`contextWindow` | `.activity-context` `MISSING_REQUIRED` (and the step's own wait times out) |
| F | rail left COLLAPSED (summary click removed) | `.activity-rail` `UNDER_MIN_HEIGHT` 25 < 64 |
| G | the D5 chip-suppression gate removed from `UnifiedChatView` | `.sources-affordance` `PRESENT_BUT_SHOULD_BE_ABSENT` (the capture summary also flagged `status-fact-dup:Sources ·`) |
| H | `fold-observations --apply` from a checkout behind `origin/main` | exit 1 + the remedy-naming refusal; the shard survived intact; `--allow-stale` then folded it |
| I | `tmp/ui-shot-server.json` `provenance.head` tampered | `_is_server_alive` → False, and end-to-end a NEW vite pid (22604 → 34216) with the head re-recorded |
| J | share floor raised 0.45 → 0.55 on the expanded capture | `UNDER_SHARE 0.4729 < 0.55` — the row discriminates, it is not passing on slack |

**Two findings the controls produced, recorded because they change how a row should be read**

1. **Presence rows do not witness expansion.** With the `<details>` closed, all three body
   `requiredSelectors` rows still report ok — `ui_measure`'s `deepQuery` finds a collapsed
   `<details>`' children in the DOM. Only `minHeightPx` went red. So on that step the presence rows
   mean "the run reported these facts" and the FLOOR means "the rail is actually open". Both are
   needed; neither substitutes for the other.
2. **A real axe defect the new capture exposed.** `chat-evidence-rail` measures
   `nested-interactive` (serious, 3 nodes): the docked rail's `.source[role="button"]` cards have
   focusable descendants. Pre-existing in the sources pane, newly VISIBLE because nothing had ever
   captured the mounted rail. The two new steps were deliberately **NOT** added to
   `ui-a11y-baseline.v1.json` — a row with `knownRules: []` would fail the gate, and a row with
   `knownRules: ["nested-interactive"]` would baseline a real defect, which the §V closure audit
   explicitly declined to do for its own `scrollable-region-focusable` find. Logged to the
   observations inbox; fixing it at source is FE work outside this worker's brief.

**Deviation from the §D8.2 sketch, deliberate.** `done` carries **no** `sources`/`citations` (both
optional in the schema). §D8.1 gives the grounding to the RECORD, so letting the stream carry it too
would over-determine the rail and make control (A) un-runnable — stripping the record's sources
would leave the rail mounted and the row green. One provider per fact is what makes each row
falsifiable, and the split mirrors §D8's own division: record → sources + lifecycle, stream →
budget + context.

**Real-world validation of guard §D8.4a, from an accident during this session.** A negative
control's repository clone failed on a Windows long-path error, breaking the shell's `&&` chain, so
the follow-on `node scripts/agent-analytics/fold-observations.mjs --apply` ran in the MAIN checkout
— which was 3 commits behind `origin/main` — using main's own (unguarded) copy of the tool. It
folded 13 entries from 6 shards and deleted them. Recovery was complete only because every shard
was tracked, so a per-path restore brought back all six plus `observations.md`. The new guard
refuses exactly this: `origin/main` was not an ancestor of main's HEAD, so `isBaseFresh` returns
false and nothing is written. The incident is the guard's motivating case, observed rather than
argued.

**Residuals after §D8**

1. The PAUSED-awaiting-budget capture stays deferred to §D8.3 option (3) on its stated trigger.
2. The over-budget remedy buttons stay unit-tier (`runInFlight` is false after any completed body).
3. `chat-bands` still reports `maxScrollableRegions ok` at `scrollableCount: 0` — pre-existing
   vacuity on that step (the closure audit's finding C added the floor only to
   `chat-bands-detailed`); noted, not touched, because it is outside this brief.
4. The two new steps carry no a11y-baseline row (see finding 2) — they are proportion-gated only.
5. The jseval pytest suite has 56 pre-existing collection errors and 43 pre-existing failures in
   this checkout's interpreter (missing third-party deps: `httpx`, and the corpus/utility stack).
   Every `ui_*` test module collects and passes; the gap predates this work and is unrelated to it.
