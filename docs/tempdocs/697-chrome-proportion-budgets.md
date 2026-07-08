---
title: "697 — Chrome proportion budgets: make oversized persistent chrome a measured gate failure, not a recurring review miss"
type: tempdoc
status: design (proposed)
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

## Approach — two parts

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
