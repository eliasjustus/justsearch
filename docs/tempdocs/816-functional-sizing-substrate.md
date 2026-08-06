---
status: "in progress — §5 FIRST SLICE SHIPPED 2026-08-06 (role register + `inlineSizeRole`/`maxWidthPx` gate kinds + `--measure-*` tokens + the chat reading column binds composer/chips/banner content + the 1920 `chat-wide-docked` camera; see §8). Remaining: the §5 follow-on sweep (Settings' dormant `data-fill=\"reading\"`, the per-view literals), the `control-row`/`dense-list` roles, and the 814 width-sweep twin — none started"
created: 2026-08-06
updated: 2026-08-06
---

# 816 — Functional sizing: role-derived width bounds as a measured invariant

## 1. Origin — the defect class, and why no instrument saw it

Owner live validation (2026-08-06, wide window ~1300px+): on the unified chat/search
surface, the composer, the suggestion-chip row, the search-summary band, and the cause
banner all span the full window; the composer even runs underneath the open document
inspector. This is the surviving half of human-validation finding 12 — the vertical half
became tempdoc 814 (height budget, shipped and gated); the horizontal half shipped only as
element-level caps on *message content* (turn text 88ch, bubble 85/95% — `unifiedChatStyles.ts`)
and the surface rows were never bound by anything.

Why fourteen agent rounds and a measured review pass never flagged it — recorded here
because the mechanism is the design's justification:

- **Full-width is the CSS default**, so an unbounded element trips no predicate. Every
  visual gate in the repo (axe baseline, proportion baseline, overlap assertions) encodes a
  *written-down* failure; "this element has no reason to be this wide" was never written
  down, so instruments read the defect as "nothing broken."
- **The camera basis is 1366px** (814's pinned design-basis viewport), where full-width
  stretching is least offensive. The defect grows with monitor width — exactly the
  viewports no camera looks at.
- **Closure records substituted for looking.** 814 recorded the inline axis as already
  owned; once audit-closed, agents (correctly, usually) trust the record over re-examining
  the screen. A half-fix was thereby laundered into a done-fix.

The general lesson is the identity-kernel lesson (tempdoc 815) again: where no authority
exists, agent restraint ("don't impose taste nobody asked for") preserves the unconsidered
default indefinitely. The remedy is not more diligence; it is **making the taste an
authority so the instruments can see it**.

## 2. The principle

An element's width claim must be justified by its function, not by available space:

```
width = clamp(functional minimum, content need, functional maximum)
```

with every term derived from the element's **role**, in **content units** (`ch`/`rem`) for
content-bearing elements. Two families, two unit systems — mixing them is the rot vector:

- **Content-sized elements** (prose, inputs, notices, controls): bounds in content units.
  A monitor growing does not change what a text input is *for*; percentage maxima politely
  re-import the bug (60% of 3440px is still absurd).
- **Space-sharing panes** (inspector, rails, split regions): bounds as container
  percentages — their function genuinely *is* "share of workspace."
- **Full-bleed chrome** (banner backgrounds, status bar, band separators) is a declared
  role, not a default: the *background* may bleed edge-to-edge while the *content box*
  inside it is content-sized. (This resolves the banner question from the design
  discussion: background full-bleed, text + action aligned to the content column.)

Bounds are evaluated against the **surface container**, not the window (the repo's
breakpoint mechanism is already width-only `@container` composition —
`primitives/compositionLayout.ts`), so panes and columns compose.

### 2b. Why the two axes get different kinds of invariant

The web's layout model is axis-asymmetric by construction, and the substrate mirrors it
rather than pretending the axes are symmetric:

- **Width is given top-down** (the container hands it out; content wraps into it), so an
  element's width is a *policy choice* — and the unconsidered default is greedy 100%.
  The right invariant is therefore **per-element functional bounds in content units**
  (this tempdoc): each row answers "what is your function-derived claim?" independently,
  because rows stack — one row's width does not take from another's.
- **Height derives bottom-up** (content produces it; the document scrolls), so
  per-element height caps in content units would be nonsense for prose — you don't cap a
  conversation's height, you decide who scrolls. But height IS zero-sum inside a
  viewport: what chrome bands take, primary content loses. The right invariant is
  therefore a **budget over the sum** — which is exactly what tempdoc 814 already built
  (share-of-height floors, chrome ceilings, one-scroller discipline, viewport-fit).
  816 does not duplicate or generalize it; the two are the axis-appropriate halves of one
  invariant: *no unconsidered claims on screen space*.
- **Panes are the crossover case**: side-by-side panes make width zero-sum too, which is
  why the `pane` role deliberately uses the 814-style family (container shares, floors)
  rather than content units — the round-7 `.conversation` 384px starvation floor was
  precisely 814's "primary must not be starved" invariant occurring on the horizontal
  axis, before either tempdoc named it.
- **Wrapping couples the axes** — the one true interaction: tightening a width bound
  makes wrapped content *taller* (a notice constrained to the column grows in height),
  which can press on an 814 ceiling. Interaction rule, binding on every conforming PR:
  **a change that tightens a width bound re-measures the affected 814 height rows in the
  same PR** — the budgets are coupled through wrapping, and a width fix that silently
  busts a height ceiling (or gets absorbed by re-baselining it without saying so) would
  trade one unconsidered claim for another.

A single axis-generic role vocabulary was considered and rejected: per-element
content-unit bounds and zero-sum share budgets are different *kinds* of claim, and
merging them would blur why each number exists. Sharing one register file and one gate is
the right amount of unification.

### 2c. Platform grounding for the axis model (research pass, 2026-08-06)

The §2b asymmetry is not this design's invention — it is CSS's own formalized model, and
the register should speak the platform's vocabulary:

- **The axes are the logical `inline` / `block` axes** (flow-relative; width/height only
  in horizontal writing). 2026 design-system practice is logical-first
  (`max-inline-size` over `max-width`), with the recorded nuance that *physical is fine
  where visual meaning is absolute*. That nuance becomes a register rule: **role bounds
  are flow-relative** (a measure follows text flow — `ch` is already an inline-axis unit;
  RTL support then costs nothing, e.g. `margin-inline` centering), while **occlusion,
  viewport-fit, and overlap assertions stay physical** (screens are physical; a toast
  covering a button is a physical fact). The existing physical constraint-kind names
  (`minWidthPx`, `maxHeightPx`) are therefore NOT renamed — they belong to the physical
  family; the new role bounds get logical naming.
- **Mechanical corroboration of "inline is given, block derives":**
  `container-type: inline-size` (which the chat surface already declares) is cheap
  precisely because inline containment doesn't fix the box, while `size` containment
  requires a fixed block size — the platform encodes that block size flows from content.
  Current platform investment on the block axis is `interpolate-size`/`calc-size()`
  (animate to intrinsic size; Chromium 129+, fine for the WebView2 shell) — i.e. tooling
  for *transitioning* derived heights, not capping them, corroborating §2b's "you don't
  cap prose height, you decide who grows/scrolls." (Adjacent, not adopted: relevant to
  disclosure animations, not to bounds.)
- **Container-query units close the loop for panes**: `cqi`/`cqb` (universal browser
  support) express container-relative shares natively in CSS, so the `pane` role's
  container-% bounds can be *rendered* in the same unit the gate asserts — the
  one-authority-renders-and-judges pattern extends to the pane family without px
  translation.

## 3. The role vocabulary (initial set) and each bound's derivation

Deliberately role-granular, not per-element — a per-element register becomes maintenance
drag that agents route around. Each bound cites the *function* that produces the number;
a bound that cannot cite a function does not belong in the register.

| Role | Min (and why) | Max (and why) |
|---|---|---|
| `prose` | — | reading measure (~60–90ch): line-length legibility; the existing 88ch turn cap is this role's current instance |
| `text-entry` | a typical query unwrapped (~30–40ch; derivable from real query-length data the eval corpus already holds) | the reading measure — what you type you immediately re-read |
| `notice` | longest word + its action control unwrapped | its own text at reading measure, *and no more* (owner's phrasing; the banner's content box) |
| `control-row` / `chip-row` | ergonomic floor per control (44px target — the a11y rule that already exists) | intrinsic (`fit-content`) of its members; the row does not stretch to fill |
| `dense-list` | widest mandatory column set | scannability cap, wider than prose but bounded — eye-return across long rows fails, which is a function argument, not an aesthetic one |
| `pane` | usable minimum for its content kind | container-% (the one legitimate percentage family) |
| `full-bleed-chrome` | — | unbounded background; content box takes another role's bounds |

Honest limit, stated up front: bounds prevent the **unconsidered** class; they cannot
produce **designed**. Composition, rhythm, hierarchy remain with the design-reference +
`ui-critic` + human passes. This substrate is a floor — the relationship of axe to
accessibility.

## 4. What already exists (verified 2026-08-06; projection, not fork)

This is an extension, not new machinery — and the design pass found MORE existing
substrate than the first draft assumed:

- **`governance/ui-proportion-baseline.v1.json` + `jseval ui-proportion-gate`** are
  already a general rendered-geometry policy register, not just a height ratchet: the
  constraint-kind vocabulary holds `minWidthPx` (a width FLOOR already exists — the
  round-7 `.conversation` 384px starvation floor), `mustNotOverlapSelector`,
  `minShareOfSelector` (the pane-% family, 814 D1), required/absent selectors, and an
  explicit **anti-vacuity doctrine** (floors beside ceilings; a selector missing from the
  captured geometry is an ERROR, never a silent pass). 816 adds the missing symmetric
  kind — a width **ceiling** — plus role indirection, to the same register/gate pair. The
  file's "proportion baseline" name is historical; its description already outgrew it.
- **Per-step pinned viewports are the camera idiom** (each register step captures at its
  own declared viewport: 1250, 1280, 1366×768/900). So "add a 1920 basis" is not a global
  switch but the existing idiom applied: wide-variant step(s) at ~1920, plus the 814
  resize-sweep instrument (today a height sweep at fixed 1366 width) gaining a width-sweep
  twin. This corrects the first draft's framing.
- **`SurfaceLayout`'s reading-fill primitive** (`primitives/surfaceLayout.ts`:
  `data-fill="reading"` centers header+body in a `--surface-content-max-width` column)
  exists but is **dormant — zero live consumers**: SettingsSurface deliberately opted out
  "pending a narrower-measure / row-regroup decision" (`SettingsSurface.ts:631-636`).
  816 *is* that decision. The primitive's declaration style (`data-fill` attribute on the
  host) is the binding idiom the role attribute conforms to, and
  `--surface-content-max-width` is already in the generated token vocabulary
  (`themes/token-names.generated.ts`).
- **`primitives/compositionLayout.ts`** (`container-type: inline-size`) is the
  container-relative evaluation basis — bounds compose against the surface box, so panes
  and columns don't fight the window.
- **`design-reference.v1.json` / `ui-critic`** has a `foundation` rules block (token
  scales, 4px grid) and a rubric — a measure/width-justification rule slots into both, so
  the critic critiques against something instead of nothing.

### 4a. The settled mechanism (general shape, not implementation)

1. **One `roles` section** in the proportion register declares the vocabulary of §3, with
   bounds in **content units** (`ch`/`rem`, resolved against the element's own rendered
   font at measure time — the register never stores px for content-sized roles). Pane
   roles use the existing `minShareOfSelector`-family container-% forms.
2. **Element rows reference a role** instead of repeating numbers; role rows inherit the
   register's anti-vacuity doctrine (a width ceiling alone would pass on a failed render —
   every role binds a floor or required-presence too).
3. **The same numbers feed the styles**: role bounds surface as theme tokens (the
   `--surface-content-max-width` family), and a catalog-verbatim-style check asserts the
   CSS token value equals the register value — one authority renders AND judges, the
   repo's established dual-projection pattern (readiness reason codes, wire schemas).
   `field-sizing` (§4b) supplies the text-entry content-need term natively.
4. **Wide cameras**: new wide-viewport step(s) plus the width-sweep twin of the 814
   resize sweep, capturing the register's steps at the widths where the defect class
   actually lives.

Adoption path (so the gate can land before every surface conforms): inventory → baseline
the pre-existing violations → ratchet down, the `check-suppression-ratchet` pattern. A
gate that requires universal conformance on day one either never lands or lands weakened.
Register closure ("every text-bearing element declares a role") is named as the eventual
end-state but NOT built now — adoption is by explicit rows, one surface at a time, the
same way the proportion register itself grew.

## 4b. Prior art (research pass, 2026-08-06 — does this already exist?)

The substrate **as a whole does not exist**: no current tool combines a role vocabulary,
functionally-derived width bounds, and rendered-geometry assertion in CI. The modern
design-QA field splits into static token/source linters (e.g. `design-lint`, which its own
docs scope to "supported static patterns" — explicitly not rendered geometry), pixel/DOM
visual regression (baseline-comparative, asserts "unchanged", not "functionally justified"),
and axe-class a11y engines (predicate-based, but with no width rules). Pieces that DO
exist, and what 816 takes from each:

- **Galen Framework** (galenframework.com; dormant, no successor found) asserted
  declarative layout specs — element sizes/positions — against rendered pages. It
  validates the *gate shape* (rendered-geometry assertion is a proven, practical idea),
  while differing in unit of authorship: per-page specs vs 816's per-role bounds. Our
  proportion gate is already the living local equivalent; 816 extends it, adopting nothing.
- **Reading-measure tokens are established design-system practice**: USWDS ships six
  "measure" tokens (its readable-example cap ≈75ch) and applies them as `max-width` via a
  `measure()` helper; VA.gov mirrors it; Tailwind's `max-w-prose` is 65ch. 816 adopts the
  industry name — the prose role's bound is called **measure** in the register — and the
  external values cluster at **65–75ch**, with WCAG 1.4.8 (AAA) capping line length at 80
  characters. This is direct evidence on the §5 open decision: every external authority
  sits *below* the current 88ch.
- **CSS `field-sizing`** became Baseline in June 2026 (Chrome/Edge 123+; the app shell is
  Tauri/WebView2 = Chromium, so it is usable today with no fallback burden): native
  intrinsic content-sizing for form fields. The `text-entry` role's content-need term can
  be native CSS rather than bespoke measurement — the role register supplies only the
  clamp bounds around it.
- **W3C DTCG design-tokens spec v1** (stable 2025-10) standardizes token *values*
  (`dimension` type) but models no constraint/role semantics — 816's register is
  complementary to the standard, not a fork of it. Optionally the chosen measure values
  can be *stored* in DTCG format for tool interop; that is cosmetic, not structural.

Nothing is copied or adapted from any of these — concepts and naming only; no license or
attribution obligations arise.

## 5. First enforced instance — the chat-surface reading column

The substrate is born with one real consumer (register discipline: no speculative rows):

- Conversation stack content, search-summary band, composer row, chip row → bound by
  `prose` / `text-entry` / `control-row`, laid out on one shared reading column (the
  SurfaceLayout primitive), centered **within the conversation region** — with the
  inspector open, the column centers in the remaining space and structurally cannot
  underlap the pane.
- Cause banner → `full-bleed-chrome` background with `notice`-bound content.
- Inspector / evidence rail → `pane` (its existing % share is simply declared).
- **Open owner decision:** the reading-measure number — ship at the existing 88ch (one
  visible change; judge narrowing live) vs commit to ~72–76ch now. The register stores
  whichever is chosen; the mechanism is indifferent. External authorities weigh toward
  the narrower option (§4b: USWDS ≈75ch, Tailwind 65ch, WCAG 1.4.8 AAA ≤80ch — all below
  88ch).

One structural nuance the design pass settled: `SurfaceLayout` classes whole surfaces as
`reading` or `full`, and the chat surface is legitimately `full` at surface level (it owns
panes). 816 therefore binds the column at **zone level** — the conversational zone inside
the surface carries the reading column; a full-bleed *surface* does not license unbounded
*elements*. The reading-fill idiom extends down one level rather than being overruled.

Library rows, Health cards, and the remaining surfaces are the follow-on sweep *after*
the owner validates the feel on the first instance — conforming migrations, not bespoke
fixes. That sweep includes **re-enabling Settings' dormant `data-fill="reading"`**
(`SettingsSurface.ts:636` names re-enablement explicitly, waiting on exactly this
decision) and converging the scattered per-view literals (`ApiExplorerView` 60ch,
`GovernanceView` 60/70ch, `BrainSurface` 32rem) onto role tokens.

## 6. What this design orphans

- The ad-hoc per-element `max-width` literals in `unifiedChatStyles.ts` (the chat
  surface's 88ch turn cap, 60ch, and the other text-content caps — NOT the chip/menu
  intrinsic sizes, which are `control-row` behavior already): the *numbers* survive as
  role instances, but their **authority** moves to the register — the literals become
  consumers of the role token, or are deleted where the column supersedes them. The
  chat-surface migration happens in the first-instance PR; the other views' literals
  (§5) migrate in the sweep their surfaces belong to, each named there so none is
  silently left as a second authority.
- The one-off "chat reading column" fix designed earlier this session (an implementation
  agent was briefed and stopped before writing code): absorbed as §5. Nothing else in
  813/814 is displaced — 814's height budget and this width substrate are the two axes of
  the same "no unconsidered claims on screen space" invariant.

## 6b. Derisk record (2026-08-06, pre-implementation; no feature work done)

Six risks investigated (R1–R6); every one retired or reduced to a named, bounded task.
Probe evidence from the live FE (read-only page loads, 1366 + 1920 viewports, landing and
docked states via the class-driven CSS distinction):

- **R1 — where the unbound elements live: fully characterized.** The conversation column
  is ALREADY bound and centered in every state (800px = the `minmax(24rem, 50rem)` track
  from `CONVERSATION_ZONES`, `unifiedChatRequest.ts:157`; measured x=574 w=800 at 1920).
  The defect is precisely: the **docked** `.composer` (measured w=1836, textarea
  **w=1748 ≈ 218ch** at 1920), the escalation strips (w=1836), and the banner (w=1836).
  The landing state already centers (w=672) — `.landing-dock` is the sanctioned pure-CSS
  precedent. Two structural constraints bind the implementation: the composer is a
  **stable DOM slot that must never re-parent** (documented keystroke-drop race,
  `UnifiedChatView.ts:2643-2649`) — so the fix is CSS-only on the docked state; and
  alignment with the conversation column must come from the **same generated frame**
  (`composeGridStyles(CONVERSATION_ZONES)`) applied to the composer container, not a
  hand-copied track template (one zones authority, two consumers). Landing centering is
  ~12px off the grid column center (spine/gutter asymmetry) — the generated-frame route
  fixes that as a side effect.
- **R2 — content-unit resolution: already half-built.** `ui_measure.py:353` already
  captures per-element `fontSize` into the measure companion; a `chPx` probe field slots
  into the same computed-style block. Bounds-in-`ch` is implementable as designed.
- **R3 — gate extensibility: clean.** `ui_proportion_gate.py` evaluates per-kind in
  explicit blocks (~:275-345) with ERROR-on-no-constraint; a role-resolved inline-size
  ceiling is one more block + a `roles` lookup + schema update, pattern-following.
- **R4 — token plumbing: exists, with one conflation to avoid.** Token values live in
  `styles/tokens.css` (`--surface-content-max-width: 72rem` at :342 — the dormant
  primitive already has a value). NOTE: 72rem (1152px) is the SURFACE-page column
  (Settings-class), NOT the prose measure — the role register holds per-role values and
  must not collapse the two. Value-equality check follows the catalog-verbatim pattern.
- **R5 — wide camera: per-step viewport pinning is the idiom** (steps declare 1250/1280/
  1366 today); 1920 stays under the 2000px screenshot cap at 1× DPI; fixtures-served
  determinism carries over.
- **R6 — 814 coupling rows, concretely:** `.degradation-banner-collapsed` maxHeight 42
  (chat-proportion/chat-bands), `.message.user` maxHeight 36 (a narrowed measure can wrap
  the fixture turn — check its length; an explicit re-baseline is permitted by §2b's rule
  but must be declared), the Detailed banner ceiling (chat-bands-detailed), and the
  docked-composer step's `maxBottomPx` rows (textarea wrap adds height). These are the
  rows the first-instance PR re-measures.

## 7. Reach judgment, falsifier, retirement

**Instance of an existing principle**, not a new one: "make taste an authority so
instruments can see it" — the same move as the a11y baseline, the proportion gate, the
identity kernel (815), and 814's height budget. This tempdoc extends that principle to the
inline axis with a role vocabulary; it deliberately builds only what the present defect
requires.

**Named, not built** (candidate future instances of the same shape, each waiting for a
real defect to force it): a type-scale register (font sizes justified by role), a spacing
rhythm register. Do not build these on 816's licence.

**Earning its keep looks like:** the gate catching a width violation in a real PR within
a few months of landing, and/or the round-N sandbox and owner validation passes no longer
surfacing "why is this element so wide" findings.

**Retirement condition:** if after adoption the width constraints never fire while human
or critic passes *still* find width/measure complaints, the bounds are wrong or the
mechanism is theater — delete the constraint kind rather than tuning it forever. Likewise
if the register drifts toward per-element rows, that is the maintenance-drag failure mode
this design explicitly rejects; collapse back to roles or retire.

## 8. Implementation record — the §5 first slice (2026-08-06)

### 8.1 What shipped

- **Role register.** `governance/ui-proportion-baseline.v1.json` gains a top-level `roles` block
  (+ `rolesNote`, + schema) declaring `prose` (max 88ch), `text-entry` (30–88ch), `notice`
  (max 88ch) and `pane` (`documentational: true` — the existing `minWidthPx`/`minShareOfSelector`
  rows already cover panes physically, and §4a's adoption path forbids force-migrating them).
  Every non-documentational role names the `token` that renders it.
- **Two new constraint kinds.** `inlineSizeRole` (resolve a role's `ch` bounds against the
  element's own rendered font) and `maxWidthPx` (`minWidthPx`'s symmetric physical ceiling).
  The second was NOT in the brief; it exists because §3's `control-row` max is `fit-content`,
  which no `ch` number expresses — inventing a ch figure for the chip row would have been a bound
  that cannot cite a function, which §3 forbids. See §8.5.
- **Measurer.** `ui_measure.py`'s computed-style capture adds `chPx` per element — canvas
  `measureText('0')` over a font string composed from the computed longhands. Chosen over a DOM
  probe span because `ch` IS the advance measure of the "0" glyph (definition, not approximation)
  and because a probe element cannot be appended inside a `<textarea>`.
- **Gate.** `ui_proportion_gate.py` gains the `inlineSizeRole` block (`OVER_MEASURE` /
  `UNDER_MEASURE`, with a `fontSize * 0.5` fallback that annotates itself when `chPx` is absent),
  the `maxWidthPx` block (`SPRAWLED`), and a pre-capture `roleTokenEquality` loop
  (`TOKEN_DRIFT`) that reads `tokens.css` and requires each role's token to equal its registered
  `maxInlineSizeCh` — §4a.3's one-authority-renders-and-judges loop. The anti-vacuity doctrine is
  enforced, not merely documented: a role-bound row whose role declares no `minInlineSizeCh` and
  which carries no `minWidthPx` of its own is an ERROR.
- **Tokens.** `--measure-prose` / `--measure-text-entry` / `--measure-notice`, all `88ch`, beside
  (and explicitly distinguished from) `--surface-content-max-width: 72rem` — the R4 trap held; the
  page column was left alone.
- **FE.** The docked composer, its escalation strip and the degradation banner's content box are
  bound to the reading column; `.message.assistant jf-markdown-block`'s literal `88ch` became
  `var(--measure-prose)` (§6: the number survives, its authority moves).
- **Camera.** `chat-wide-docked` — 1920x900, `--fixtures` (`degraded`), the same rail-click →
  search → `?`-draft → Enter path `chat-proportion` uses, with seven register rows.
- **design-reference.** `foundation.measureNote` + `foundation.measureScale` +
  `foundation.paneWidthFamily`, and a `measure-discipline` rubric row that tells the critic to
  judge the CONTENT box (full-bleed background over a bounded row is correct; a full-bleed line of
  text is a finding) and to exempt panes.

### 8.2 Route taken on the composer-frame question: the GENERATED FRAME, not the fallback

`alignToZoneStyles(CONVERSATION_ZONES, …)` in `primitives/compositionLayout.ts` lays the composer
container on the same generated frame as the conversation zone and places its children in the
zone's own declared column (read from the `ZoneDecl`, never passed as a number; it throws if the
named zone has no `col`). `composeGridStyles` and the new generator share one `frameStyles` half,
so tracks, gap and breakpoint cannot diverge. The composer never re-parents — only its CSS
changed, and the stable-slot invariant is untouched.

Two things the route forced, both of which are the point of taking it:

1. **`justify-content: center` + `width: 100%` moved out of `.conversation-zone`'s hand-authored
   wide block and into the generator.** That pair is what actually decides where the column falls
   (the tracks sum to ~1176px inside an 1836px surface; the group is centred, not left-packed), so
   leaving it hand-authored would have made the "aligned" composer a coincidence maintained by
   hand. The old rule is deleted, not duplicated.
2. **The container selector is `.answer-plane > .composer`, not `.composer`.** `<jf-composer>`
   renders its own `div.composer` into the same shadow root (`createRenderRoot` returns `this`),
   so the bare class would have turned the input row itself into a six-track grid.

The `max-inline-size` + `margin-inline: auto` fallback was not needed and is not used.

### 8.3 Measured before/after (live FE, worktree Vite, `--fixtures` degraded, fresh context per state)

| Element | 1366 before | 1366 after | 1920 before | 1920 after |
|---|---|---|---|---|
| `jf-composer textarea` (docked) | x=68 **w=1206** (172ch) | x=**297** **w=617** (88ch) | x=68 **w=1760** (251ch) | x=**574** **w=617** (88ch) |
| `.escalation-strip-docked` | x=68 w=1282 (216ch) | x=297 w=676 (114ch) | x=68 w=1836 (310ch) | x=574 w=676 (114ch) |
| `.degradation-banner .notice-row` | x=85 w=1250 (145ch) | x=331 w=759 (88ch) | x=85 w=1804 (209ch) | x=608 w=759 (88ch) |
| `.degradation-banner` (background) | w=1282 | w=1282 | w=1836 | w=1836 |
| `.conversation` (anchor) | x=297 w=800 | x=297 w=800 | x=574 w=800 | x=574 w=800 |
| landing composer content | x=373 w=672 | x=**361** w=672 | x=650 w=672 | x=**638** w=672 |

The docked composer's textarea and chip row now start at **exactly** `.conversation`'s x (297 /
574 — 0px, not "within 16px"), and the banner's background still bleeds the full surface while its
content box is 88ch. Landing is unchanged in size (672px) and its ~12px drift is gone: its
children now centre on the reading column's centre (638 + 336 = 974 = 574 + 400), which is the
side effect §6b R1 predicted for this route. Document overflowX stayed false at both widths.

Two corrections the live measurement forced (neither was visible from source):

- **`box-sizing`.** The textarea is content-box, so an 88ch `max-inline-size` rendered a **635px**
  box — an 18px disagreement between what CSS was told to bound and what the camera measures.
  Fixed at the element (`box-sizing: border-box`), so the gate and the stylesheet judge the same
  number.
- **The block-axis side effect of that fix.** border-box moved composerStyles' `min-height: 3rem`
  inside the padding, shortening the input 66px → 52px. Caught by re-measuring rather than by any
  test, and restored explicitly (`min-block-size: calc(3rem + 1.125rem)` — the same 3rem of
  CONTENT). This is §2b's axis coupling arriving in the first slice, in the direction the tempdoc
  did not name: not wrapping, but the box model.

### 8.4 814 coupling: no height row moved, no re-baseline

`jseval ui-proportion-gate` (all 12 registered steps, `--fixtures`) is **exit 0, zero non-ok
rows**, including every 814 height row: `.degradation-banner` 110 ≤ 176 and ≥ 64
(`chat-bands-detailed`), `.degradation-banner-collapsed` 34 ≤ 42, `.composer` 152 ≤ 220,
`.message.user` 36 ≤ 36, `.composer` bottom 724 ≤ 768 (`chat-composer-small`), and the 0.55 /
0.45 share floors.

That is a pass, not a proof that nothing moved, so the coupling was measured directly: an in-page
A/B (same DOM, same fonts, same viewport; the new caps removed via element style) at 1366x900
`degraded-detailed` and 1366x768 `degraded`. **Every height is identical with and without the
caps** — expanded banner 110/110, collapsed 34/34, composer 128/128, textarea 66/66, strip 22/22,
`.conversation-zone` 449/449. The wrapping coupling did not fire because the banner's causes and
the chip labels both fit inside the new 759px/676px boxes. **No re-baseline was taken and none was
needed.**

### 8.5 Deviations from the brief, with reasons

1. **The chip row is bound by `maxWidthPx`, not by an `inlineSizeRole`.** The brief asked for role
   bindings on composer/textarea/strips/banner-content. The strip's role is §3's `control-row`,
   whose max is `fit-content` — not a `ch` number. Binding it to `text-entry`/`notice` would have
   been a category error, and inventing a `control-row` ch figure would have been a bound that
   cannot cite a function (§3's own rule). So: the CSS implements the §3 behaviour literally
   (`inline-size: fit-content`), and the register asserts the column binding physically on a
   pinned-viewport step — `maxWidthPx` is the symmetric completion of the `minWidthPx` this
   register already had. `control-row` and `dense-list` stay unbuilt, named here as the next
   roles, waiting for a bound that can cite a function.
2. **The composer's bar container is registered as `jf-composer`, not `.composer-row`.** Measured:
   the docked bar has no `.composer-row` element (that class belongs to other chat views); the
   grid child that carries the column is `<jf-composer>` itself.
3. **The `prose` role has no element ROW yet** — only its token-equality check. Under `--fixtures`
   the stubbed SSE never produces an assistant answer, so `.message.assistant jf-markdown-block`
   is not on any deterministic camera. Honest half-coverage, not a silent gap.

### 8.6 Honest residuals

- **The `auto` spine track.** The composer's frame has no `.run-spine` child, so that track is 0
  there while the zone's is ~14px when the spine is mounted (agent mode, wide, ≥2 turns). With the
  group centred, the two columns then differ by ~7px. Not reproducible on any current camera
  (`chat-spine-multi` is 1366 and does not dock a submitted turn); closing it would need the
  composer to reserve the same track, which is a bigger change than the drift.
- **The banner content is centred on the SURFACE, not on the reading column** (987 vs 974 at 1920,
  a 13px offset). That is what "a centered `--measure-notice` cap" asks for and it reads correctly
  on camera, but a later pass may prefer column alignment for the same reason the composer got it.
- **`.grounding-why` (60ch) and `.source-chip` (24ch) keep their literals.** They are deliberately
  TIGHTER than the measure, and a role max is a ceiling — an element choosing narrower conforms.
  Collapsing them onto `--measure-prose` would have widened them, which is not what §6 asks for.
- **The register's `prose`/`notice` roles carry no `minInlineSizeCh`**, so rows binding them must
  supply their own floor. That is enforced, but it means the floor's *function* (§3: "longest word
  plus its action control") lives in prose rather than in a number.
- **The docked bar leaves ~180px of trailing space** inside the 800px column (617px textarea +
  gap + Send). That is the honest cost of a measure bound at composer type: 800px is ~93ch of
  16px chrome but ~114ch of the textarea's 13px, so the column is legitimately wider than the
  entry's measure. Named rather than papered over — narrowing the column itself is §5's open
  owner decision.
