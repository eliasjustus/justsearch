---
title: "697 — Chrome proportion budgets: make oversized persistent chrome a measured gate failure, not a recurring review miss"
type: tempdoc
status: exploration — problem + evidence settled; solution direction OPEN (see §Before this settles)
created: 2026-07-08
related:
  - 696 (Simple/Detailed disclosure — where this was deferred; §Proportion/density)
  - 615 (the ui-shot `.measure.json` measurement harness — "measurement over vision")
  - docs/explanation/27-frontend-presentation-kernel.md (the Collapse > Generate > Gate ladder + the honest ceiling)
  - 553 (the representation/appearance drift class this hardens against)
  - "sidecar: ui-audit/_process/workflow-density-lens-proposal — the research trail that first proposed a density lens"
---

# 697 — Chrome proportion budgets

## Problem

Persistent chrome (notices, cards, bubbles, rows, the onboarding card, status strips) is repeatedly
**oversized for the content it carries**, and there is no guardrail — so it is caught, if at all, by
whoever happens to measure pixels. That review-tier catch has a documented failure record:

- audit-2 (G1) **measured** a banner as ~2.6:1 chrome:content;
- audit-3 re-examined the same banner minutes later and **missed it** in a prose-only pass;
- tempdoc 696 then **deferred** the whole proportion question, arguing size would "dissolve into the
  disclosure fix";
- a single **live look** immediately re-found it (below).

The pattern is clear: proportion is a *measurement* judgment, and review (~70% adherence, doc 27's
weakest rung) does not reliably do measurement. This tempdoc makes oversize a **measured gate
failure** instead.

## The class (measured, 2026-07-08, against the running stack)

Distinct components, distinct root causes — a *class*, not one shared bug:

| element | measured | content it holds | root cause |
|---|---|---|---|
| notice **collapsed pill** | ~76px (≈2× a slim pill) | one line + a remedy | the remedy renders as a full-size op-button (~42px) inside a one-line row |
| user **message bubble** (D1) | ~75px | one 17px line | `.message` `white-space: pre-wrap` renders the Lit template's surrounding newlines as ~2 phantom blank lines |
| notice **expanded banner** | ~159px | headline + 2 causes + button | mostly legitimate; the same oversized button + gaps are the compressible part |

On record from earlier audits (not re-measured here, flagged as such): result rows ~113px each
(audit-2 G4), the never-dismissing onboarding card (X1), the stacked recap banner (C10).

(These numbers are cited to a specific live measurement run; they are working evidence, not
public-facing claims — nothing here should propagate into README/marketing copy.)

## Before this settles — reframings, alternatives, and the broader shape

The "size-budget gate" below is *one* direction and should not be treated as decided. Several
framings could change it materially; recording them so the design is chosen with eyes open.

### Reframe A (the strongest one): proportion is the **density authority's coverage gap**, not a new problem

The app already has a **density authority**: `state/adaptationProfile.ts` applies *"the 'one
authority, every surface' pattern to the user-selectable adaptation axes: density, contrast, and
motion"* — density is `compact | comfortable | spacious`, persisted on `userConfig.density`, threaded
to renderers via a `DensityController` (the `adaptiveDensity` primitive, tempdoc 565 §19 / 559
Authority VI). But the chat styles hardcode spacing (~200 raw `rem` literals in
`views/unifiedChatStyles.ts`) rather than projecting from it.

That is the **same drift shape as 696**: an authority exists, and chrome doesn't project from it
(incomplete coverage — the tempdoc-553 class). So the durable fix for the *spacing/gap* dimension of
oversize may be "**bring chrome under the density authority**" (replace hardcoded spacing with
density-scaled tokens so the `DensityController` actually drives it, and a Compact user gets compact
chrome), enforced like the existing `style-literal-ratchet` (no raw spacing literals) rather than by
capping rendered heights. **Caveat:** density-scaling addresses padding/gaps, *not* the two specific
structural bugs (the bubble's `pre-wrap` phantom lines, the full-size remedy button) — those are not
"too much spacing," they are wrong structure, and would remain component-level fixes. So this reframe
splits the problem into (i) density-coverage (systemic, authority-shaped) and (ii) two structural
bugs, which the current single "size-budget" framing conflates.

### Reframe B: "**hug your content**" vs "cap your height"

A `max-height` budget is a crude proxy. The real invariant is *chrome ≈ its content's intrinsic
size*. A "hug" rule is more correct and content-aware (a 3-cause banner is *legitimately* taller than
a 1-cause one, which an absolute cap can't tell apart) but is harder to measure (you need the
intrinsic content height, not just the rendered box). Audit-2's **content-to-chrome ratio** is a
middle option — content-aware without needing a per-state absolute number.

### Enforcement alternatives (if a guardrail is wanted at all)

- **Ratchet, not authored budgets.** Authoring absolute px budgets encodes one person's taste and is
  brittle across viewport/theme/density. A **proportion ratchet** — baseline the current measured
  heights, then fail only on *growth* (the shrinking-baseline idiom of `atom-fork-ratchet` /
  `suppression-ratchet`) — avoids taste-encoding and matches an existing codebase pattern.
- **Enforce projection, not pixels** (pairs with Reframe A): gate "chrome uses density tokens, not
  raw spacing" — closer to the cause than the symptom.
- **Push the clear cases to Collapse.** A *single-line-pill layout primitive* that is structurally
  unable to exceed one row would lift the notice-pill case above Gate to Unrepresentable (doc 27's
  top rung), where the honest ceiling says most CSS can't go — but a narrow, well-chosen primitive
  can. Worth asking which few proportion invariants are structurally enforceable vs. which only a
  gate can hold.

### Hidden assumptions & risks in the current (budget-gate) direction

- **Per-state blindness:** a budget is per rendered state; a state not in a ui-shot step is
  unbudgeted — proportion coverage silently inherits ui-shot's coverage gaps.
- **Content-blindness of absolute caps** (see Reframe B).
- **Config dependence:** a height that passes at 1× desktop/dark/comfortable may fail at
  narrow/compact — one measured config is not the whole space.
- **Taste as a gate:** "oversized" is sometimes intentional (touch targets, emphasis, breathing
  room). A gate can fight a legitimate future design; who sets the budget matters.
- **YAGNI / maintenance:** a registry + measure-extension + gate is infrastructure. If Part A (plus
  Reframe A's density coverage) makes oversize rare, the gate may never bite — the honest test is the
  observed regression rate, and building the gate *before* that evidence risks over-engineering the
  very thing 696 was criticized for under-doing.

### The broader shape this points to

696 (disclosure) and 697 (density/proportion) are **the same system shape**: a *cross-cutting
presentation dimension* that ought to be one authority with full coverage, currently expressed
ad-hoc per component; the fix is coverage + a self-covering gate. doc 27's authorities table already
names several of these (tone, originator, display-name/fact, availability). **Disclosure** and
**density/proportion** look like two more rows of the same table — which suggests the recurring
invariant worth naming (not building): *every cross-cutting presentation dimension is an authority;
chrome projects from it; incomplete coverage is the drift.* Motion and visual-hierarchy/emphasis
(audit-2 B3's "emphasis inversion") are plausible further instances to watch.

Two enforcement substrates also recur and could generalize beyond this tempdoc: the **`.measure.json`
harness** (615) is becoming a general *presentation-invariant* gate layer (it already backs axe +
overflow; proportion would be a third consumer), and the **shrinking-baseline ratchet** is becoming
the codebase's default answer to "prevent a metric from regressing." Naming these, not building the
generalized versions now.

## A provisional approach — two parts (see §Before this settles for what could change it)

### Part A — root-cause the current instances (the immediate fixes)

Each at its own component (leverage the *shared* ones — one fix covers a family):

- **Notice remedy → a compact affordance.** The `jf-system-notice` remedy should render as a compact
  action (small / text-style) in the collapsed pill (and tighter in the expanded form). Because the
  notice shell is shared (banner + advisory toasts), this one change slims the whole notice family —
  the systemic angle audit-3's D3 suspected.
- **Bubble → contain the pre-wrap.** `white-space: pre-wrap` belongs on the raw-text *leaf*, not the
  whole `.message` whose Lit-template whitespace it currently renders as blank lines.
- **Rows → a compact-density option** (audit-2 G4).
- Onboarding-card dismissal (X1) and recap stacking (C10) are **behavioural**, cross-referenced from
  696 §6 — not folded in here.

### Part B — the guardrail: a measured size-budget gate (the long-term part)

Part A without Part B is exactly the whack-a-mole history above. The durable fix reuses tempdoc
615's **`.measure.json`** harness (which already captures shadow-piercing geometry next to every
ui-shot capture — the "measurement over vision" thesis, already shipped and live-validated):

1. **A registry** `governance/chrome-proportion.v1.json` — one row per persistent-chrome archetype:
   `{ ui-shot step/state, shadow-piercing selector, budget (a max-height px, or a "hug" rule:
   height ≤ content-lines·line-height + 2·padding + ε), rationale }`. e.g. *notice-pill in the
   search-degraded step, collapsed: ≤ ~44px*; *message bubble in qa-response: hug*.
2. **A measure extension** — `ui_measure.py` captures the registered elements' geometry into the
   step's `.measure.json` (it already captures landmark/stage/rail geometry; extend it to the
   registry's selectors).
3. **A gate** `check-chrome-proportion.mjs`, registered in `governance/registry.v1.json`: for each
   registry row, read the element's measured height from the relevant step's `.measure.json`; fail
   the build when it exceeds its budget. **Self-covering** (doc 27 §5.2): adding a registry row
   auto-extends what the gate enforces.

This is doc 27's **Gate** rung applied to proportion, and deliberately not higher: per doc 27's
*honest ceiling*, CSS-tier concerns cannot reach Collapse (a dev can always author a tall element),
so a measured gate is the realistic top — a *loud floor*, not unrepresentability. Coverage rides on
the existing `check-ui-step-coverage` gate: a budgeted element must appear in a ui-shot step to be
measured, which that gate already keeps honest.

## What this supersedes / orphans (teardown rides along)

The sidecar `workflow-density-lens-proposal`'s original process proposals — a **mandatory prose
density lens** (#1), a **parallel human "density-lens" audit agent** (#3), and an **eyeballed
measured-vs-not ledger** (#4) — are all review-tier (~70%). This design realizes their *intent* (a
standing density check) at **Gate-tier (~100%)**, automated, so those three are **superseded** — the
proposal's own §5 pointed at "a size budget baked into the shared component" as the real durable fix;
this is that, generalized and enforced. The proposal's one keepable idea (#2, "route density checks
through the existing measurement harness rather than eyeballing") is exactly Part B's mechanism.
Action: mark #1/#3/#4 closed-superseded in the sidecar research trail when this ships.

No production code is orphaned by Part B; Part A's per-component changes replace oversized renders
in place (no dead code left behind — e.g. the full-size remedy op-button in the notice is *replaced*
by the compact affordance, not left alongside it).

## Reach — principle, scope, evidence, retirement

**Principle made explicit:** *persistent chrome carries a proportion budget, enforced by measurement,
not review.* It is the proportion sibling of doc 27's authorities table (which already gates tone,
originator, display-name, display-fact, availability) — the one axis that table doesn't yet cover.

- **Where else it applies:** every persistent-chrome archetype — notices, cards, result rows, chat
  bubbles, chips/badges, the onboarding card, the status strip.
- **Existing violations:** the measured ones (pill, bubble) plus the recorded ones (rows, onboarding,
  recap) — the registry's initial rows.
- **Evidence it earns its keep:** the gate fails the build on a real oversize or regression at least
  once before it would have shipped, and UI audits stop re-finding oversize by hand. If, a few
  cycles in, the gate has never bitten and no audit re-finds oversize, the guardrail is unnecessary —
  delete it.
- **Retirement condition:** if the frontend later adopts a layout system where proportion is
  intrinsically content-hugging (oversize structurally unrepresentable), the measured gate becomes
  redundant — retire it then rather than keep a floor under an impossibility.

## Scope discipline — what this deliberately does NOT build

- **No runtime "size authority."** Proportion is a build-time / measured concern; unlike modality and
  transients (which *are* runtime-arbitrated in doc 27's Move 4), a size budget needs no
  ReactiveController. A runtime enforcer would be the wrong altitude and premature abstraction.
- **Not a budget on every element** — only registered persistent-chrome archetypes that recur and
  matter. Transient/one-off layout is out.
- **No new measurement stack** — Part B extends 615's `.measure.json`, it does not reinvent it.

## Verification

The gate is its own proof (it fails on the current pill/bubble until Part A lands, then passes). Plus:
before/after measured height reductions for each fixed instance (cited to a run), the full ui-web
unit + gate suite green, and a live re-check in-browser that the pill and bubble hug their content.
Independent review (reviewer ≠ implementer) before merge, per the standing rules.
