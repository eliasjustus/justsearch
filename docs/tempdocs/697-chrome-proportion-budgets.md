---
title: "697 — Oversized persistent chrome: root-cause the bugs + a measured shrink-only ratchet so it can't regress"
type: tempdoc
status: COMPLETE — Part A implemented + live-validated; Part B implemented, unit-tested, and **ACTIVATED** 2026-07-15 (both target elements registered and measuring against a seeded `chat-proportion` fixture step; gate green). Pending merge
created: 2026-07-08
updated: 2026-07-15
related:
  - 738 (Simple/Detailed disclosure — where this was deferred; §Proportion/density)
  - 615 (the ui-shot `.measure.json` harness + the `ui-a11y-baseline.v1.json` baseline pattern this mirrors)
  - 565 §19 / 559 Authority VI / 569 §19 Seam 4 (the Adaptivity/density lineage this investigates and does NOT extend)
  - docs/explanation/27-frontend-presentation-kernel.md (the Collapse > Generate > Gate ladder + the honest ceiling)
  - 553 (the representation/appearance drift class)
  - "sidecar: ui-audit/_process/workflow-density-lens-proposal — the research trail that first asked for a density lens"
---

# 697 — Oversized persistent chrome

## Problem

A handful of persistent chrome elements render **much taller than the content they hold**, and there
is no guardrail — so it is caught, if at all, by whoever happens to measure pixels. That review-tier
catch has a documented failure record: audit-2 (G1) **measured** a banner as heavy chrome:content →
audit-3 re-examined the same banner minutes later and **missed it** in a prose pass → tempdoc 738
then **deferred** the whole question, arguing size would "dissolve into the disclosure fix" → a single
live look immediately **re-found** it. The lesson is not "try harder to eyeball": proportion is a
*measurement* judgment, and review (doc 27's weakest, ~70% rung) does not reliably measure.

## The class (measured, 2026-07-08, against the running stack)

Distinct components, **distinct structural causes** — a class held together by a symptom, not one bug:

| element | measured | content it holds | root cause |
|---|---|---|---|
| notice **collapsed pill** | ~76px (≈2× a slim pill) | one line + a remedy | the remedy renders as a full-size op-button (~42px) inside a one-line row, which the row then centres on |
| user **message bubble** (audit-3 D1) | ~75px | one 17px line | `.message` carries `white-space: pre-wrap` (for raw user newlines), so the Lit template's whitespace *around* the text leaf renders as ~2 phantom blank lines |
| notice **expanded banner** | ~159px | headline + 2 causes + button | mostly legitimate content; the same oversized remedy button + inter-row gaps are the compressible part |

On record from earlier audits (not re-measured here, so flagged as such): result rows ~113px each
(audit-2 G4). *(These numbers are cited to a specific live measurement run — working evidence, not
public-facing claims; nothing here should propagate into README/marketing copy.)*

The distinct causes matter: **no single spacing lever fixes all three.** That is the fact that
decides the design below.

## Investigation: is this the density authority's coverage gap? (No.)

The tempting reframe — "the app has a Compact/Comfortable/Spacious preference; the oversize is chrome
not respecting it, the same coverage drift as 738's disclosure gap" — **does not survive source
investigation**, and it is worth recording *why*, because it changes the design:

- The density **preference** (`DensityVariant`, `renderers/userConfig.ts`) exists, but
  `state/adaptationProfile.ts` states plainly it is **"not a CSS-cascade axis… not a global class."**
  Its only consumers are **resource-renderer dispatch** (`resourceRegistry.ts`, which representation
  of a Resource to render) and **run-node glyph legibility** (`primitives/adaptiveDensity.ts` /
  `DensityController`, whose enum is `minimal|compact|full` for a run-step's *measured box*, an
  entirely different concept from the user's `compact|comfortable|spacious`).
- So density is **not a general spacing scale**. The pill is ~76px and the bubble ~75px at *every*
  density; their causes (a full-size button, a `pre-wrap` on the wrong element) are structural, not
  un-scaled spacing. "Bring chrome under the density authority" would fix none of the measured cases.

A separate, real finding falls out of this and is recorded as **candidate scope** (below), not
solved here: because density scales almost nothing in the main content chrome, a user who picks
**Compact gets little tightening** — the setting promises more than it delivers.

## The design (settled)

Two parts, because the problem has two shapes: specific structural bugs (needing root-cause fixes)
and a recurrence risk (needing a guardrail).

### Part A — root-cause the structural bugs (not per-symptom tweaks)

- **Bubble.** `white-space: pre-wrap` belongs on the **raw-text leaf** (the element that actually
  holds the user's typed text, where newline-preservation is wanted), not on the `.message` wrapper
  whose Lit-template whitespace it currently renders as blank lines. Moving it there removes the
  phantom lines while keeping user newlines intact — a structure fix, not a padding tweak.
- **Notice remedy.** The `jf-system-notice` remedy should render as a **compact affordance** (a small
  / text-style action) rather than a full-size op-button, in the collapsed pill (and tighter in the
  expanded form). Because the notice shell is **shared** (the search/agent degradation banners *and*
  the advisory toasts), this one change slims the whole notice family — the systemic angle audit-3's
  D3 suspected, realized at the shared component rather than per instance.
- Result-row density (G4) is the one case that genuinely *is* about spacing; it is the natural first
  place to actually wire the density preference — tracked under the candidate scope below, not forced
  into this slice.

### Part B — the guardrail: a measured **shrink-only ratchet** (the long-term part)

Part A without a guardrail is exactly the whack-a-mole the history above records. The durable fix
**reuses three things that already exist** rather than inventing a size-budget system:

1. **The baseline pattern.** Mirror `governance/ui-a11y-baseline.v1.json` (615 §13) with a
   `governance/ui-proportion-baseline.v1.json`: for a small **registered set** of persistent-chrome
   archetypes (each keyed to a ui-shot step/state + a shadow-piercing selector), record the
   **currently-measured height**. The baseline *is* the current state — no one authors a "correct"
   number, so no taste is encoded.
2. **The measurement.** `ui_measure.py`'s geometry capture already records `getBoundingClientRect`
   heights for a key element set; extend that set to the registered selectors so their heights land
   in each step's `.measure.json` (the harness the codebase already runs — "measurement over vision",
   615, shipped and live-validated).
3. **The ratchet.** A check (mirroring the a11y baseline's "NEW-vs-known" flag) that fails the build
   when a registered element's measured height **grows** beyond its baseline. Shrinks are free and
   re-baseline downward — so as Part A lands, the baseline ratchets *down*, and a future regression
   that re-inflates the pill/bubble is a loud build failure, not a review miss.

This is doc 27's **Gate** rung for proportion, and deliberately not higher: per doc 27's *honest
ceiling*, CSS proportion cannot be made unrepresentable (a dev can always author a tall element), so
a measured gate is the realistic top — a loud floor. It is a **ratchet, not an authored budget**,
because absolute px budgets encode one person's taste and break across viewport/theme/density,
whereas "don't grow past what's already there" is objective and matches the codebase's existing
ratchet idiom (`atom-fork` / `style-literal` / `suppression` / `npm-audit`). Coverage rides on the
existing `check-ui-step-coverage` gate: a ratcheted element must appear in a ui-shot step to be
measured, which that gate already keeps honest.

> **Correction (2026-07-15, from activation).** That last sentence is **overstated** and should not be
> relied on. `check-ui-step-coverage` verifies that each step's mapped **source paths resolve on disk**
> — it does **not** verify that a step's **selectors still match anything**. A selector can rot silently
> while the gate stays green (proven this session — see §Activation, the stale-selector finding). What
> actually protects this ratchet is its own **exit 2 on a missing capture**: if a registered selector
> stops matching, the gate fails loudly rather than passing green on nothing. That was the right call by
> construction; the coverage-gate argument above was simply the wrong reason for it.

> **Correction 2 (2026-07-15, from independent review) — the tier claim was wrong.** This tempdoc calls
> the ratchet doc 27's **Gate** rung and says it "fails the build" (also §Part B.3, §Reach, §Verification,
> and the sidecar-teardown's "Gate-tier (~100%), automated"). **It is not CI-wired** — like `ui-a11y-gate`,
> it is **local-first (ADR-0026)**: runnable, real, and green/red honestly, but nothing fails a build on it.
> Read every "fails the build" in this doc as **"exits 1 when you run it."**
>
> The review's sharper point: as originally shipped, *nothing told anyone to run it*. It was absent from
> `/ui-check`'s verb table and from `ui-shot-hint` — and `views/unifiedChatStyles.ts`, **the very file
> holding the guarded CSS**, was not mapped in `ui_step_index.json` and failed `styleHints()`'s `styleish`
> test. A future agent re-inflating the pill would have received **zero** delivery. Since this tempdoc's
> whole §Problem is that humans do not remember to measure proportion, an undelivered check is nearer ~0%
> than ~100% — the machinery was mirrored from `ui_a11y_gate` while the two things that make it *fire*
> were omitted.
>
> **Closed at activation** (both verified by runtime probe, not inspection): `ui-proportion-gate` is now a
> row in `/ui-check`'s verb table, and `ui-shot-hint`'s `styleHints()` emits it on style edits. Fixing that
> required fixing the hint itself — its `styleish` test used `/\.styles\.ts$/`, but this repo's convention is
> camelCase `<name>Styles.ts` and it contains **zero** `*.styles.ts` files, so **both** of its style files
> (`unifiedChatStyles.ts`, `ambientStyles.ts`) fell through all three tests. That hint had never fired for
> either, which had been silently costing `ui-a11y-gate` its delivery too — a pre-existing bug this tempdoc
> only found by needing the seam. Now `/styles\.ts$/i`; 6/6 probe cases correct, no over-broadening.
>
> **Honest residual:** delivery is now `hook-hint` tier (~85%, the `pipe-mask-hint` precedent — tempdoc 618's
> residence→delivery conversion), **not** `gate` tier (~100%). CI-wiring it is the remaining step to earn the
> word "Gate", and is deliberately left as a candidate follow-up rather than claimed here.

## What this supersedes / orphans (teardown rides along)

- **This tempdoc's own earlier draft** proposed *authored max-height budgets* in a registry. That is
  **superseded** here by the measured baseline + shrink-only ratchet (taste-free, config-robust). The
  earlier framing survives only as an "alternative considered" note below.
- **The full-size remedy op-button inside the notice** is *replaced* by the compact affordance in
  Part A — not left alongside it. Likewise the `pre-wrap` on `.message` is *moved* to the text leaf,
  not duplicated. No dead code is left behind.
- **The sidecar `workflow-density-lens-proposal`'s review-tier process proposals** — a mandatory
  prose density lens (#1), a parallel human "density-lens" audit agent (#3), an eyeballed
  measured-vs-not ledger (#4) — are **superseded**: the ratchet realizes their intent (a standing
  density check) at Gate-tier (~100%), automated. Its keepable idea (#2, "route density checks
  through the existing measurement harness") is exactly Part B's mechanism. Action at ship time: mark
  #1/#3/#4 closed-superseded in the sidecar research trail (that is where the orphan lives; this
  tempdoc names it so the teardown is not a later sweep).

## Alternatives considered (kept on record)

- **Authored max-height budgets** (the first draft) — rejected: encodes taste, brittle across
  viewport/theme/density. The ratchet is objective.
- **"Hug your content" / content-to-chrome ratio** instead of a height check — more correct in
  principle (a 3-cause banner is legitimately taller than a 1-cause one) but needs an intrinsic
  content-height measurement the harness doesn't yet produce; the shrink-only ratchet sidesteps the
  "how tall *should* it be" question entirely, which is why it wins for now. Ratio remains the better
  target if the ratchet proves too coarse.
- **Push the clearest case to Collapse** — a single-line-pill layout primitive that structurally
  cannot exceed one row would lift the notice-pill above Gate to unrepresentable. Attractive for that
  one case; not generalizable (most chrome can't be made structurally short), so it is a possible
  *local* upgrade for the pill, not the scheme.
- **Density-authority coverage** — refuted above (density is not a spacing scale); recorded as
  candidate scope, not the fix.
- **Do only Part A, add the guardrail later if it recurs** — the honest YAGNI position. Rejected
  because the recurrence is already documented (measured→missed→deferred→re-found), and the guardrail
  is *cheap* (it reuses the a11y-baseline pattern + the existing measurement); the ratchet's own
  retirement condition (below) handles the "what if it never bites" case without pre-committing.

## Reach — is this an instance of something, and does it reveal something?

**It conforms to existing seams — it does not add a parallel one.** The guardrail is the intersection
of three patterns the codebase already runs: doc 27's Gate rung, the `.measure.json` measurement
substrate (615), and the shrinking-baseline ratchet. The design's whole point is to *reuse* them, not
build a proportion-specific mechanism.

**Principle it makes explicit (recognized, not built as generalized structure):**

> *A rendered-geometry invariant that review cannot reliably hold is enforced by a measured baseline
> ratchet, not by a human lens.* Proportion (height-doesn't-grow) is the first instance; the same
> shape fits other measurable geometry facts — overflow (already flagged by the harness), horizontal
> creep, element count, whitespace ratio.

- **Where else it applies:** any persistent-chrome geometry the harness can measure and a reviewer
  keeps missing. The `.measure.json` harness is quietly becoming a general *presentation-invariant*
  gate layer (it already backs axe + overflow; proportion would be a third consumer).
- **Existing violations:** the measured pill/bubble (and the recorded rows) are the ratchet's initial
  baseline entries — the guardrail is born already covering the known offenders.
- **Evidence it earns its keep:** the ratchet fails the build on a real re-inflation or a new
  oversize at least once before it would have shipped, and hand audits stop re-finding oversize.
- **Retirement condition:** if, after a few cycles, the ratchet has never bitten and no audit
  re-finds oversize, delete it — the recurrence it guards against did not materialize, and a ratchet
  under an impossibility is self-justifying apparatus.

**Second, broader shape (named, explicitly NOT built here):** tempdoc 738 (disclosure) and this
tempdoc (proportion) are the *same* system shape — a cross-cutting presentation dimension that ought
to be one authority with full coverage, currently expressed ad-hoc per component. doc 27's authorities
table already names several (tone, originator, display-name/fact, availability); disclosure and
proportion look like two more rows of that table, and **density** is a *third* candidate — the
"Compact does almost nothing" finding above is that dimension's coverage gap, waiting for a decision
on whether density *should* scale chrome. Motion and visual-emphasis (audit-2 B3's "emphasis
inversion") are further candidates. Recording the shape; not building the generalized "presentation
dimension → authority → gate" machinery, because the present problem needs exactly one ratchet.

## Scope discipline — what this deliberately does NOT build

- **No runtime "size authority."** Proportion is a build-time / measured concern; unlike modality and
  transients (doc 27's runtime-arbitrated Move 4), a height invariant needs no ReactiveController — a
  runtime enforcer would be the wrong altitude.
- **No general density→spacing system.** Wiring the Compact/Comfortable/Spacious preference into
  chrome spacing is a real, separate concern (candidate scope), but the measured oversize does not
  require it — the bugs are structural, not un-scaled.
- **Not a ratchet on every element** — only the small registered set of persistent-chrome archetypes
  that recur and matter. Transient/one-off layout is out.
- **No new measurement stack** — Part B extends 615's harness and mirrors the a11y-baseline; it does
  not reinvent either.

## Verification

The ratchet is partly its own proof: it baselines the current pill/bubble, and once Part A shrinks
them the baseline ratchets down, so a later re-inflation fails the build. Plus: before/after measured
height reductions for each fixed instance (cited to a run), the full ui-web unit + gate suite green,
and a live in-browser re-check that the pill and bubble hug their content in both `uiMode` states and
at narrow width. Independent review (reviewer ≠ implementer) before merge, per the standing rules.

## Implementation status (2026-07-08)

Implemented on `worktree-ui-audit-density-review` (full ui-web unit suite green — 3727; the proportion
gate's own unit tests green — 10).

- **Part A — root-cause fixes (done + live-validated).** All CSS in `views/unifiedChatStyles.ts`.
  (1) pre-wrap moved off the `.message` container onto the `.message-body` leaf, completing the
  tempdoc-565 §12.3.B fix that had handled `.message.assistant` but left the user container inflated;
  (2) the notice remedy slimmed via the `--justsearch-shell-action-button-padding` /
  `--justsearch-shell-form-control-spacing` custom properties (which cross the shadow boundary into
  `ActionButton`) plus a reduced collapsed-pill `padding-block`. **Measured against the running stack**
  (shadow-piercing `getBoundingClientRect`, the method that found the originals): the collapsed
  **degradation pill 76px → 42px**, the **user bubble 75px → 36px** (hugging its 17px line), the
  remedy affordance ~42–54px → ~29px. The expanded banner stays ~158px — content-dominated, by design.
  *(Numbers cited to a specific live run — working evidence, not public-facing claims.)* Live capture
  used a standalone Playwright script (the claude-in-chrome extension had disconnected).
- **Part B — the ratchet (machinery done + unit-tested; activation was deferred here, then DONE —
  see §Activation below, which supersedes this bullet's "Follow-up to activate" paragraph).** The full
  `ui_a11y_gate` family is mirrored: `governance/ui-proportion-baseline.v1.json` (+ schema),
  `scripts/jseval/jseval/ui_proportion_gate.py` (shrink-only: exit 1 on growth beyond
  `maxHeightPx + tolerancePx`, exit 2 on a missing capture so a silent miss is never a false pass),
  `ui_measure.py` extended to union the baseline's selectors into its geometry probe,
  `regen_proportion_baseline.py`, and `test_ui_proportion_gate.py` (10 tests proving the ratchet
  logic). **Honest activation gap:** the two target elements are *data-dependent* (the pill needs a
  degraded readiness verdict; the bubble needs a rendered turn), and the gate captures via ui-shot's
  deterministic `--fixtures` state where neither renders — and the ui-shot chat/search chain steps
  additionally failed to capture against the worktree FE this session. So `steps` ships **empty** (a
  green no-op) with the intended registrations + measured ceilings (pill ≤ 44px, bubble ≤ 38px)
  recorded in the baseline `description`. **Follow-up to activate:** seed a fixture that renders a
  degraded verdict + a user turn so `chat-mode`/`qa-response` show the pill/bubble deterministically,
  then register the two elements and re-baseline via regen. The machinery + unit tests are the interim
  guarantee; the fixes are already live-validated above.
- **Teardown (rode along).** The full-size remedy op-button is slimmed in place (no dead
  full-size-remedy styling left) and the `.message` container pre-wrap is *moved*, not duplicated; the
  now-redundant `.message.assistant` reset is kept as a documented belt-and-braces with its comment
  updated. The sidecar `workflow-density-lens-proposal`'s review-tier process proposals (#1/#3/#4) are
  marked closed-superseded by this ratchet in that (private) research trail.
- **Independent review (reviewer ≠ implementer, 2026-07-08).** Passed — every load-bearing claim
  (pre-wrap scoping to the user leaf only, the remedy custom-property/`::part` reachability, the
  shrink-only gate's no-false-pass paths, the empty-baseline no-op, type-fix safety) confirmed against
  primary source + passing tests. No blockers, no should-fix items; three cosmetic/defensive nits
  accepted as-is (a no-op `min-height:0`, an unscoped-but-single-use class selector, and `regen`'s
  shrink-only being human-supervised — matching the `ui_a11y_gate` precedent).

## Activation (2026-07-15) — the ratchet is now armed

The deferral above is closed. Both elements are registered and measuring; `jseval ui-proportion-gate`
exits 0 against real captures.

**Measured, against a deterministic fixture capture:** `.degradation-banner-collapsed` = **42px**,
`.message.user` = **36px** — matching Part A's live-validated post-fix numbers exactly (not the
pre-fix 76px / 75px). The baseline records those as the ceilings; with the 2px `tolerancePx` the
effective ceilings are 44/38, as intended. **No ceiling was widened to obtain green.**

**Why activation was worth doing rather than shipping the machinery inert.** An empty `steps` array is
a green no-op — a ratchet that cannot bite. This tempdoc's own thesis is that Part A without a
guardrail is precisely the whack-a-mole its §Problem documents (measured → missed → deferred →
re-found). Shipping the apparatus without the function would have reproduced the deferral one level up.

### Two corrections to this tempdoc's own plan (both found by doing it)

1. **`qa-response` cannot be part of this** — the §Part B follow-up said to make "`chat-mode`/`qa-response`
   show the pill/bubble deterministically." `qa-response` drives a real model through `/api/chat/agent`;
   `ui_fixtures.py`'s docstring explicitly scopes `--fixtures` to structural steps and excludes AI-chain
   steps, and no SSE-stream fixture route exists. Making it deterministic would mean net-new SSE-mocking
   harness work against a declared design boundary. Dropped from scope.
2. **`chat-mode` was not mutated either.** Its job is documenting chat-*input* mode; adding a degraded
   banner + a rendered turn would change what it documents and disturb its screenshot/a11y baselines.
   Instead a **new dedicated isolated step, `chat-proportion`**, renders both elements at once. Additive;
   ten other chain steps' baselines untouched. (Nothing `depends_on` `chat-mode` — verified — so
   isolating it was *possible*; it just wasn't *right*.)

### What activation actually required (all live-verified, none of it predicted by the plan)

The plan assumed one knob (a degraded verdict). It needed four, each proven necessary by probing:
a `DEGRADED` `readiness.composites.retrieval` (the pill), `/api/inference/status` reporting the model
online **and** a non-zero `worker.core.indexedDocuments` (else `askPinned()` blocks Ask on a
"no documents" gate, so no turn renders), and `/api/settings` `ui.mode: "simple"` — because the captured
fixture default is `"advanced"`, which force-expands the banner regardless of severity, so the
*collapsed* pill never renders under the default fixture at all. All four are gated behind the
`degraded` variant only; `fixture_body()` output for the `default`/`empty` variants was verified
**byte-identical to pre-change**, so no other step's capture moved. The new variant is deliberately
**not** added to `VARIANTS`, which is consumed solely by `ui_fuzz.py` — joining it would have silently
added a fuzzer axis cell.

### The finding that matters most: the measurement harness had silently rotted

`ui_selectors.py` still defines `TID_SEARCH_INPUT = "search-input"` and
`SEARCH_INPUT = Selector(role="searchbox", name="Search files", …)`. **Those attributes no longer exist
anywhere in the frontend** — tempdoc 687 (merged 2026-07-07) retired the standalone search box when it
consolidated search+chat onto one `<jf-composer>`. So `search-results`, and the nine steps chained off
it, cannot drive the app under `--fixtures`. Logged to the observations inbox; **not fixed here** (it is
pre-existing, cross-cutting, and out of this tempdoc's scope).

This retro-explains a line in §Implementation status above, written 2026-07-08: *"the ui-shot chat/search
chain steps additionally failed to capture against the worktree FE this session."* That was read at the
time as a worktree quirk. **It was not.** It was this bug, seen and misattributed — the same
symptom-not-cause move §Problem catalogues for the banner.

And it sharpens this tempdoc's own thesis in a way §Reach did not anticipate. The argument was: *a
rendered-geometry invariant that review cannot reliably hold is enforced by a measured baseline ratchet,
not by a human lens.* This finding says the measuring instrument needs the same treatment: `ui-shot`'s
selectors are an invariant nothing measures, so they rotted for eight days behind a green
`check-ui-step-coverage` — which checks that mapped **paths** resolve, not that **selectors** match.
"Measurement over vision" only holds while the measurement still points at the thing. **Candidate
follow-up:** a liveness check that fails when a registered selector matches nothing in any step — the
selector-level analogue of the path-level check the coverage gate already performs. Not built here; the
proportion gate's own exit-2-on-missing-capture already protects *this* ratchet, which is why activation
could proceed honestly despite the rot.

### Verification (2026-07-15, all run and green)

Every row is a command that was run and its observed result. A claim without one belongs under
§Unverified assumptions below, not here.

| check | result (evidence) |
|---|---|
| `jseval ui-proportion-gate` | exit 0 — "clean — no registered element grew beyond baseline" |
| **the ratchet BITES** (falsification, by the independent reviewer) | `min-height:120px` forced onto `.degradation-banner-collapsed` → **exit 1**: `GROWN: step=chat-proportion selector=.degradation-banner-collapsed measured=120px > baseline=42px (+2px tolerance)`; the sibling element still read `ok` (precise, not blanket). Reverted → exit 0 restored |
| **no false green on a missed capture** (falsification) | a registered `.does-not-exist-xyz` → **exit 2**, `"error": "selector not found in captured geometry"` — never exit 0 on nothing measured |
| **heights are content-hugging, not empty-element artifacts** | live DOM probe: a *two-line* `.message.user` measures **55px** vs **36px** for one line — it grows with content |
| Part A is a real fix, not a padding tweak | live computed styles: `.message-body` → `pre-wrap`, `.message.user` → `normal`; multi-line DOM text round-trips as `'line one?\nline two'` (newlines preserved) |
| `pytest tests/test_ui_proportion_gate.py` | 10 passed |
| `node scripts/ci/check-ui-step-coverage.mjs` | exit 0 |
| `jseval ui-a11y-gate` (6 view surfaces) | exit 0 — no new violations |
| `chat-proportion.measure.json` axe | **0 violations, 0 console errors** — on the exact state holding the slimmed remedy button, the pill, and the bubble |
| ui-web typecheck + unit suite | exit 0; **3731 passed / 363 files** |
| `./gradlew.bat build -x test` | `BUILD SUCCESSFUL` (exit 0 read from `PIPESTATUS`, not the harness's pipeline exit — see §Unverified/notes) |
| full `ui-web-gates` recipe | **39 checks, none skipped**; green except 3 pre-existing `main` failures in files this branch doesn't touch (`RecentsMenu.ts`, `ActionLedgerView.ts`) |
| public CI (PR #188) | 10/10 checks pass; `mergeStateStatus: CLEAN` |

**Why the falsification rows matter more than the exit-0 row.** `ui-proportion-gate` exiting 0 proves
nothing about whether it *can* fail — an armed-but-toothless ratchet is a no-op wearing a badge, and the
unit tests exercise the gate's logic in isolation, not the end-to-end capture→measure→compare chain. The
two falsification rows are what license the word "ratchet" in this tempdoc. If a future change touches the
gate, **re-run those two probes, not just the green one.**

The `ui-a11y-gate` green is real but **does not** cover this work's changed states — it captures six
*view* surfaces and never renders a degraded banner or a chat turn. The `chat-proportion` axe result is
what actually measures them, and it is clean: the remedy button's slimming (~42–54px → ~29px) did not
trade a proportion win for a WCAG target-size violation. Useful side effect: the fixture step built for
the ratchet doubles as the measured-audit vehicle for presentation-authority closure on exactly the
states the a11y gate structurally cannot reach.

### Unverified assumptions + deferred checks (read this before trusting the above)

- **The ratchet guards two elements at one viewport/theme, in one fixture state.** Nothing says the
  registered set is *sufficient* — result rows (~113px each, audit-2 G4) are on record as oversized and
  are **not** registered. The ratchet is born covering the two known offenders, not the class.
- **`regen_proportion_baseline.py` is shrink-only *by human supervision*, not by construction** — it will
  happily re-baseline upward if run after a regression. This matches the `ui_a11y_gate` precedent and was
  accepted as a nit by the independent review, but it is a real hole: a careless regen launders a
  regression into a new baseline. The gate is the guard; regen is not.
- **An empty/missing register exits 0 by design** (`test_empty_register_is_clean`). So a `steps: []`
  regression, or the baseline file being renamed away, is a **silent green** — the same inert state this
  tempdoc's §Activation exists to escape. Nothing currently detects a re-emptied register.
- **Delivery is ~85% (hook-hint), not ~100% (gate).** Not CI-wired. See §Correction 2.
- **The 42px/36px numbers are one machine, one run** (Windows, Playwright, 1× DPI, `--fixtures`). They are
  working evidence cited to a specific capture — *not* public-facing claims, and nothing here should
  propagate into README/marketing copy.
- **Harness caveat that cost a real debugging cycle:** a backgrounded `./gradlew.bat build … | tail`
  reports the **pipe's** exit code, not the build's — the run that produced this tempdoc's green was
  preceded by one that reported "exit 0" while `BUILD FAILED` (`piped-exit-masked`, `agent-lessons.md`).
  Read the real code via `PIPESTATUS`, or assert on `BUILD SUCCESSFUL`. Relatedly: ui-shot's worktree
  auto-serve Vite (`:5174`) survives the capture and locks `modules/ui-web`, making a later
  `installWebDependencies` fail with npm `-4048` (UV_EPERM) — kill it before building. Both filed to the
  observations inbox.

### Follow-ups (named, not built)

1. **CI-wire `ui-proportion-gate`** — the remaining step to honestly earn the word "Gate" (~100%) rather
   than hook-hint (~85%). Blocked on nothing; deliberately not claimed here.
2. **A selector-liveness check** — fail when a registered ui-shot selector matches nothing in any step.
   The selector-level analogue of the path-level check `check-ui-step-coverage` already performs. This is
   the generalisable fix for the §Activation stale-selector finding.
3. **Repair the stale `search-input` selectors** (`ui_selectors.py`) retired by tempdoc 687 — currently
   breaking `search-results` + the nine steps chained off it under `--fixtures`. Pre-existing and
   cross-cutting; out of scope here, filed to the inbox.
4. **Register result rows** (audit-2 G4, ~113px) once the density question is decided — the one measured
   case that genuinely *is* about spacing (§Part A).
5. **Detect a re-emptied register** so the inert state can't silently return (see §Unverified above).

### Terms used above (this doc may become public history)

- **audit-2 / audit-3** — two prior internal UI review passes over the shell-v0 surfaces; their findings
  are referenced by their original IDs (G1, G4, D1, D3). The findings themselves are restated inline
  wherever they matter, so an outside reader does not need the source documents.
- **the sidecar** (`ui-audit/_process/workflow-density-lens-proposal`) — a private research trail that
  first proposed a "density lens" review step. Not part of this repository. §Teardown records that this
  ratchet supersedes its process proposals; nothing in this tempdoc's design depends on reading it.
