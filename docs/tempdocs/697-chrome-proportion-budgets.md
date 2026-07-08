---
title: "697 — Oversized persistent chrome: root-cause the bugs + a measured shrink-only ratchet so it can't regress"
type: tempdoc
status: Part A implemented + live-validated; Part B machinery implemented + unit-tested; gate activation on the two data-dependent elements is a documented follow-up (seeded fixture)
created: 2026-07-08
updated: 2026-07-08
related:
  - 696 (Simple/Detailed disclosure — where this was deferred; §Proportion/density)
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
audit-3 re-examined the same banner minutes later and **missed it** in a prose pass → tempdoc 696
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
not respecting it, the same coverage drift as 696's disclosure gap" — **does not survive source
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

**Second, broader shape (named, explicitly NOT built here):** tempdoc 696 (disclosure) and this
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
- **Part B — the ratchet (machinery done + unit-tested; activation is a follow-up).** The full
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
  superseded by this ratchet — to be marked closed-superseded in that (private) research trail.
