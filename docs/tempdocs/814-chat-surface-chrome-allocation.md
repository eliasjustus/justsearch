---
title: "Chat-surface layout & chrome allocation — the height budget gets an owner (T-B design)"
status: "in implementation 2026-08-06 — design settled, derisked (§B), autonomous implementation licensed by owner; plan in §P"
created: 2026-08-06
updated: 2026-08-06
related: [810, 809, 807, 798, 738, 734, 687, 610, 600, 577, 565, 559]
---

# 814 — Chat-surface chrome allocation (thread T-B of the human-validation campaign)

## What this document is

The design pass for tempdoc 810's charter **T-B**: 809 findings **12** (vertical space has no
owner), **13** (nested scroll regions + scrollbar-geometry reuse), **15**'s structural half (what
the run spine should be when a run genuinely segments), and **7**'s structural half
(Timeline/History/Inbox naming, empty states, one-authority-one-pointer). Design only — nothing
here is licensed for implementation until the owner says so.

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

## Owner decisions this design surfaces (resolved 2026-08-06 under granted autonomy — recorded, not re-asked)

1. **The two floor numbers** (D1: Simple-mode share target, Detailed-mode hard floor) — the
   design proposes ≥ 55% / ≥ 45% at 1366×768; the register makes them owner-tunable.
2. **Chip-vs-banner arbitration direction** (D5): banner-wins-on-owned-surfaces is proposed;
   the inverse (chip persistent, banner transient-on-change) is coherent but weakens the
   remedy affordance's discoverability. Proposal stands unless the owner prefers the inverse.
3. **Detailed-mode behaviour below the height breakpoint** (D6): interaction-gated expansion is
   proposed; the alternative (Detailed always expands, content floor drops to the 45% hard
   floor) preserves current Detailed semantics at more height cost.
