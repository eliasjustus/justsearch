---
title: "559 — The presentation projection, completed: the UI as a projection of a typed element catalog (every authority a facet of one engine; closing the hand-authoring seam the grep-gate ceiling can't reach)"
type: tempdocs
status: open
created: 2026-05-29
extends: tempdoc 557 (presentation = three single-authority projections — Display/Observed-state/Theme) and 558 (their depth/coverage) — from "projection of a value" to "projection of the rendered element"; tempdoc 548 (the one invariant + the ladder Collapse>Unrepresentable>Generate>Gate + §5.2 "a gate's coverage must project from a catalog")
category: frontend / ux / design-theory / single-authority / projection / presentation-engine
related:
  - tempdoc 548 (ui-web correct design — "two authorities for one concept, neither subordinate"; the prevention ladder; §5.2 coverage-as-projection). The invariant this doc finally reaches at the catalog seam.
  - tempdoc 557 (the presentation projection — Display/Observed-state/Theme single-authorities; MERGED) and tempdoc 558 (their depth — color-pair / projector-coverage / totality). THIS DOC completes them: 557 projects the *value* (a label, a token); this projects the *rendered element* (the DOM + its role/keyboard/overflow/placement).
  - tempdoc 553 / 549 (canonical search-execution record → governed projection → register gate) — the kernel, first proven. 559's evidence authority is one instance.
  - tempdoc 560 (extension substrate — single-authority projection + delivery), 561 (LLM interaction — one record → governed projection → register gate), 564 (contract projection — the FE↔backend boundary; the "kernel is half-applied, not absent" diagnosis this doc mirrors for the *rendering* boundary). Same kernel, four surfaces.
  - tempdoc 508 / 511 (aggregate render dispatch + capability-mediated surfaces — the contribution-registry spine the engine generalizes), 543 (the Action+Effect substrate — the most generalized affordance declaration; the seed the control engine projects), 526 (typed selection substrate — the selection facet's source).
  - tempdoc 530 / 531 (discipline-gate kernel + consumer-drift gate — the machinery the catalog-coverage gate reuses).
---

# 559 — The presentation projection, completed

> **What this document is.** A *theorization* of the correct long-term structure for the whole
> JustSearch frontend presentation layer. It states the correct design, **disregarding feasibility,
> migration sequencing, and effort** (those belong to a later implementation tempdoc). It deliberately
> **prefers extending the proven substrates the codebase already has over inventing new ones** — because
> the investigation found, as 564 found for the contract boundary, that the right structure is already
> *half-built*: the cure is to finish applying a kernel that is present, not to introduce a new one.

> **The meta-finding (why this rewrite exists).** 559 was originally written to *home the un-homed
> presentation-adjacent authorities one by one* — layout, accessibility, messaging, evidence
> (declarative), then operability and adaptivity (behavioral). That work was built and independently
> audited (Appendix A). Two things it surfaced collapse the per-authority framing into a single
> structural truth: **(1)** every independent audit — 557's, 558's, 559's, and the fourth that drove
> Part II — found *fresh* user-visible drift, always in whatever layer had no authority yet; **(2)**
> implementing the behavioral authorities exposed that their *enforcement* bottoms out at **grepping
> Lit template strings**, which is systemically leaky (the `controls-a11y` gate cannot see a `<jf-control>`
> nested inside a `${cond ? html`…`}` interpolation — and *every* template-scanning gate shares the
> ceiling). Recurrence and leak are not two problems. They are **two symptoms of one disease**, and the
> disease is the subject of this document.

> **Evidence base.** Two first-hand code investigations on `main` (2026-06-03 — the "Inv-1/Inv-2"
> citations); the 2026-05-29 and 2026-05-30 browser UX audits (Appendix A); the 2026-06-03 implementation
> of 559's behavioral authorities + the grep-ceiling discovery (Appendix A); and the cited design docs.
> Every thesis traces to one of these.

---

## 1. The problem, restated as a structural symptom

For three documents running, the same shape recurred. 557 homed Display / Observed-state / Theme and
merged. 558 audited the merged 557 and found fresh instances of all three. 559 audited 557+558 and
found the defect in four *more* layers; a fourth audit found it in two more. Each pass narrowed the
residue, and each residue clustered in **whatever presentation concern had not yet been given an
authority** — placement, the announced role, the channel a message takes, the evidence behind an
answer, whether a control is keyboard-operable, what a region does when space runs out.

The first reflex — the one 559's original body took — is "there are more un-homed layers; home them."
But that reflex never terminates, and the deeper observation is *which* properties drift versus which
never do. The properties that **flow through a typed value** have not drifted once across three audits:
a label is `present(ref).label`, a branded `DisplayLabel` (`shell-v0/display/present.ts:22,90-129`); a
color is `token(name)`, a codegenned `TokenName` that is a *compile error* if misspelled
(`shell-v0/themes/token.ts:25-27`); the theme is written by exactly one function, `applyAppearance`
(`shell-v0/state/themeState.ts:142`). These are **complete** — a raw string cannot reach the render
boundary where a presented value is expected.

The properties that **are hand-authored as DOM in a Lit template** are exactly the ones that keep
drifting: whether an element is a `<button>` or a `<div @click>`; whether it carries `role` /
`aria-label` / `aria-selected`; whether a status item has an accessible name at all; whether a bar
clips or overflows; where a surface's landmark sits. These have no typed value to brand — they are
*structure and attributes written by hand at each call-site* — so the strongest available check is a
**grep over the template string**, and the audit keeps finding what the grep cannot.

> **The symptom is not the disease.** "The audit keeps finding things" and "the gate is leaky" are the
> same fact seen twice: presentation properties that are **authored as template DOM** drift and can only
> be approximately checked; presentation properties that **flow through a typed projection** do not. The
> disease is *where the line falls* between those two.

---

## 2. The disease — the projection kernel is half-applied to presentation

The codebase has one recurring, proven kernel, named explicitly in 561/564: **a single-authority
declaration → a governed projection → a register-backed anti-drift gate.** Search-execution uses it
(553); the contract boundary's correct design is it (564); the LLM-interaction surface is being moved
onto it (561). The presentation layer has the kernel's **first** and **third** members but only half of
its **second**:

- **Membership is projected.** Nine contribution registries built on one `createRegistry` primitive
  (`shell-v0/primitives/registry.ts:28-130`) declare *which* elements exist — Command, StatusBar,
  Operation, Surface, and five more. A new element's *existence* fans out to consumers with no consumer
  code. This is the kernel applied to membership, and it works.
- **Some facets are projected through a typed value.** `present()` (the label facet), `token()` (the
  color facet), `landmarks.placementToLandmarkRole` (the landmark facet,
  `shell-v0/display/landmarks.ts:14-33`), `evidenceProjection.toEvidenceItem` (the evidence facet,
  `components/chat/evidenceProjection.ts:84-105`). Each returns a typed value the template must use.
  These are the **good half** — and they are *not* a coincidence: they work *because* the property is a
  value that passes a typed seam.
- **The rendered element itself is NOT projected.** The DOM structure, the keyboard wiring, *where* the
  projected name is hung, the role, the overflow behavior, the fill — these are **hand-authored in each
  component's `html` template**. `StatusDeck.renderCoreItem` is the type case: a `switch` that
  hand-writes `html\`<span role="img" aria-label="…">…\`` for every metric
  (`components/StatusDeck.ts:292-374`). The control primitive `<jf-control>` projects its *name* from a
  declaration but is *placed* in a template by hand, with a raw-`label` escape hatch always available
  (`components/Control.ts:74-95`). Whether an interactive affordance is built through the primitive *at
  all* is a per-template authoring choice — a `<div @click>` is fully representable.

Because the rendered element is hand-authored, its enforcement can only be a **grep over template
strings**, and that grep has a structural ceiling. Lit allows arbitrary nesting
(`html\`${cond ? html\`<button @click>\` : nothing}\``), so a scanner must either treat `>` literally
(false positives from `=>` and `>=`) or **erase `<`/`>` inside every `${…}`** — which the
`controls-a11y` gate does (`scripts/ci/check-controls-a11y.mjs:92-117`), and which therefore **erases
any element nested inside an interpolation**. Inv-2 confirmed this is *systemic*, not a one-off: every
template-scanning gate (`check-controls-a11y`, `check-layout-purity`, `check-presentation-purity`,
`check-message-single-model`, `check-color-tokens`, the template halves of `check-theme-token-closure`)
shares it. The discovery that drove this rewrite — that the `controls-a11y` gate silently misses
`<jf-control>`s nested in `${…}` — is not a gate bug to patch. It is the **disease made visible**: you
cannot completely check, by grepping a template, a property that the template *hand-authors*.

> **The two tiers, named.** Tier-2 (typed seam): present/token/appearance — the property is a value, the
> wrong value is a compile error, coverage is total. Tier-4 (template grep): role/keyboard/overflow/
> placement/structure — the property is hand-authored DOM, the wrong form is a *missing or malformed
> tag*, coverage is grep-approximate and leaks at every interpolation. The disease is that the second
> tier exists at all — and it exists *only* because the rendered element is authored, not projected.

---

## 3. The correct structure — the UI as a projection of a typed element catalog

The cure is already proven in one corner of the same codebase. **`SurfaceFactory`** is a projection
*engine*: a surface declares its catalog entry, and the factory *mints the element* from that
declaration — `customElements.get(tag)` + construct + attribute-set — so the shell never hand-authors a
surface's DOM; it inserts the factory's result (`api/registry/SurfaceCatalogClient.ts:76-120`;
`shell-v0/chrome/Shell.ts:2296-2354`). Surfaces are ~90% projected; everything else
— controls, bars, metrics, messages, nav items, evidence rows — is hand-authored. **The disease is
exactly that boundary: the engine model was applied to one element class and withheld from the rest.**

> **The correct end-state: every cross-cutting presentation element is a projection of a typed catalog
> entry, rendered by one engine that owns its facets.** The unit a developer authors is the
> **declaration** (a typed catalog entry: this is a control for action *A*; this is a status metric *M*;
> this is a message of class *C*; this is a region of items with priorities *P*). A **presentation
> projection engine** — `SurfaceFactory` generalized from surfaces to all element classes — renders it,
> owning the accessible name, the role, the keyboard wiring, the landmark, the overflow, the fill, the
> token. The Lit template's job **collapses to composing declarations**, not authoring DOM-with-properties.

What this dissolves:

- A **nameless control** is unrepresentable: there is no `<jf-control label="">` to author, because
  controls are not placed by hand — they are projected from an action declaration whose name *is*
  `present(action)`. A `<div @click>` is not a "control that escaped the gate"; it is *not a control* —
  it carries no declaration, so it renders no affordance.
- A **clipping region** is unrepresentable: a bar is "the region engine over an item registry"; overflow
  is a projection of the declared priorities, not a per-component `overflow:hidden` someone forgot to
  remove.
- An **un-landmarked surface**, an **unannounced selection**, a **raw-id message**, a **re-windowed
  excerpt** — each is unrepresentable for the same reason: the facet is the engine's to emit, not the
  template's to forget.

This is not a new framework. It is the **completion of 557**: 557 projected the *value* (a label, a
token); this projects the *rendered element* (the DOM and every facet hung on it). It is the same move
508/511 began (contribution registries + render dispatch) and `SurfaceFactory` finished for one class —
generalized to all.

---

## 4. The unification — authorities are facets of one engine, not homes to fill one by one

The original 559 enumerated authorities and proposed to home each: Layout, Accessibility, Messaging,
Evidence, Operability, Adaptivity — alongside 557's Display, Observed-state, Theme. The structural view
is that these are **not separate systems**. They are **facets the one engine derives from the one
declaration**:

| Authority (557/558/559) | The facet the engine projects | From which declared field |
|---|---|---|
| **Display** | accessible/visible name + icon | the element's action/operation/resource ref → `present()` |
| **Theme** | color, density | `token()` over the declared role |
| **A11y — landmark** | `role` / region membership | the surface's `placement` → `landmarks` |
| **A11y — selection** | `aria-selected` / `activedescendant` | the typed selection substrate (526) |
| **Operability** | `role=button` / focus / Enter-Space | the declared action (543) behind the affordance |
| **Layout** | region (header/body/footer) + rhythm | the surface-shell declaration |
| **Adaptivity** | overflow + fill under constrained space | the item registry's declared `priority`/policy |
| **Messaging** | channel + tone + placement | the message class's `RenderHint` × severity |
| **Evidence** | excerpt + score-meaning + disclosure | the canonical execution record (553) |
| **Observed-state** | the tri-state liveness readout | the one observed-state authority |

Read top to bottom, this is the whole presentation layer. Read as a column, it is **one engine running
N projection passes over one catalog**. "Home the un-homed authority" was the *symptom-level* program
— correct in spirit, unbounded in practice, because there is always one more layer. "Complete the one
projection engine and let every authority be a pass it runs" is the *structural* program — bounded,
because the catalog is finite and the passes are enumerable. The behavioral authorities (Operability,
Adaptivity) are not a different kind of thing either: they are facets that are a total function of
*(declaration × condition)* — the engine simply projects them over the input-modality and
available-space conditions the way Observed-state projects over the data-availability condition.

---

## 5. Why this finally reaches the guarantee the ladder demands

548 § the ladder, strongest first: **1 Collapse · 2 Unrepresentable-by-type · 3 Generate · 4 Gate**,
with §5.2's rider that *a gate's coverage must itself project from a catalog*. 557/558/559 all reached
only **1 + 4** for the hand-authored facets — collapse to one primitive, plus a tier-4 grep-gate — and
559 §15 concluded the stack *caps* the guarantee there: "inside `html`/`css` templates, slots are
`unknown` and `var()` is an unchecked string, so unrepresentable-by-type is not reachable." That
conclusion is **correct for properties authored in templates, and only for those.**

Projecting the element from the catalog moves the seam off the template:

- **Tier-2 becomes reachable.** The catalog entry is a typed value; the engine's projection is total
  over that type. The wrong form is not "a malformed tag a grep might miss" but "a catalog entry that
  fails to typecheck" or "a projection pass with no branch for this declared kind." Unrepresentability
  is recovered because the authoring unit is a *typed declaration*, not an *untyped template string*.
- **The §5.2 gate becomes complete, not approximate.** Coverage projects from the catalog — *the actual
  catalog of declarations*, enumerable and typed — instead of from a grep over templates that cannot see
  past an interpolation. A new element is covered the moment it is declared, with no template to scan.
- **The 557 §5 / 559 §15 cap lifts.** That cap was a property of *authoring presentation in Lit
  templates*, not of the stack as such. `SurfaceFactory` already escapes it for surfaces — its
  correctness is a property of the factory + the typed catalog entry, not of any template grep.
  Generalizing the engine generalizes the escape.

This is the precise sense in which the document's title says *completed*: the ladder's tier-2 rung,
declared unreachable by 559 §15 for the behavioral/structural facets, **is** reachable — just not at the
template seam. It is reachable at the catalog/engine seam, which is where the cure already works.

---

## 6. Extend, don't invent — the seeds already in the codebase

Per 564's discipline and the user's standing instruction, the correct design **extends proven
substrates**; Inv-1 confirms each piece exists:

- **The engine** = `SurfaceFactory` generalized (`SurfaceCatalogClient.ts:76-120`). It is the existing
  proof that "declare → engine mints the element" works in this codebase; the work is widening it from
  the surface class to the control/region/metric/message/evidence classes.
- **The universal affordance declaration** = the **Action+Effect substrate (543)**
  (`shell-v0/substrates/actions/index.ts:137-201`; `substrates/effect.ts:23-94`). It is already the most
  generalized declaration: a typed `Action` with a handler returning a typed `Effect` dispatched through
  one applier + journal. Its `parameters` field already carries **JSON-Schema + `x-ui-renderer` hints** —
  a latent seed of schema-driven rendering. Promote it to the declaration the control engine projects an
  operable, named affordance *from*, instead of a thing templates invoke.
- **The catalog spine** = the nine contribution registries on `createRegistry`. `registry.ts:24-26`
  already records that *contract-level consolidation of the registries into one is a deferred structural
  slice* — **this design is that slice's purpose**: not consolidation for its own sake, but so one engine
  can project from one declaration shape.
- **The facet projectors** = `present()` / `token()` / `landmarks` / `evidenceProjection` /
  `applyAppearance`. These already exist and already work; the change is that the **engine owns the
  calls**, instead of each template re-invoking them per site. The good (typed-value) half is *kept and
  reused*, not rebuilt — it becomes the engine's facet library.
- **The gates** move their coverage from template-grep to **catalog projection** — the same `--rebalance`/
  register machinery 530/531 already provide, pointed at the element catalog instead of at `html` strings.

---

## 7. Long-term prevention — why the recurrence terminates

The audit-keeps-finding-drift loop is not evidence that auditing is insufficient; it is evidence that
the audited layer is *hand-authored*. The loop **terminates structurally** when presentation is
**projected, not authored**:

- A new element is **declared in the catalog**, and the engine gives it every facet — name, role,
  keyboard, landmark, overflow, fill — *by construction*. There is no hand-authored property left to
  drift, and no template interpolation left for a gate to miss. 548 §5.2 ("covered the moment it is
  declared") is finally literally true, not grep-approximated.
- The **independent UX audit's role changes shape**, from unbounded to bounded. Today it must hunt every
  hand-authored property that might have drifted across the whole view tree — which is why each pass finds
  more. Under the engine it verifies two finite, typed things: *is the catalog complete* (every element
  declared), and *are the engine's facet-passes correct* (each pass total over its declared kinds). That
  is the structural end of the recurrence 557, 558, and 559 each re-discovered from inside.

The discipline's success signal — decreasing audit yield — stops being a slow asymptote chased one layer
at a time, and becomes a property of the architecture: the yield is bounded by the engine's correctness,
not by anyone's diligence.

---

## 8. Honest brackets

- **This is major-rewrite grade**, stated as the end-state per the doc's charter. The cost is real and is
  *not* scheduled here. 559 §15's cost-realism (Appendix A) is preserved **as the feasibility bracket**:
  pull from this design on evidence, prefer the cheapest increment that moves a facet from authored to
  projected, and keep the existing tier-4 gates as the pragmatic interim. The §15 caution against
  "widening primitives" is *consistent* with this design — the high-value move is *deepening the
  declaration and engine*, not spreading hand-authored primitives further.
- **The AHA boundary is explicit.** Not everything is a catalog element. Genuinely bespoke content — a
  chat transcript, a rich visualization, prose, a one-off form — stays as a composed template. But it
  **composes projected elements**: its buttons, its status chips, its citations, its messages are
  projected, even though their arrangement is bespoke. The engine owns the *cross-cutting element classes*
  where the authorities apply; it does not own *layout composition*. Over-projecting bespoke content into
  a catalog would manufacture the very over-DRY 559 §15 already walked back (the messaging fold, the
  merged evidence renderer).
- **The typed-value half is kept, not replaced.** present/token/appearance are the design's *exemplars*,
  not its casualties — they are what "a facet that flows through a typed seam" looks like done right. The
  whole proposal is to extend their discipline to the facets that today escape it, by giving those facets
  an engine to flow through.
- **The engine is a means, not the thesis.** The thesis is *project the rendered element from a typed
  declaration so every facet is total and unrepresentable-when-wrong*. If a better mechanism than a
  generalized `SurfaceFactory` reaches that end, the thesis is indifferent to it.

---

## Appendix A — the prior 559 (condensed; full narrative in git before the 2026-06-03 rewrite)

The original 559 (and its Part II) are the **proof-by-example** for §1's disease: each authority below is
a presentation facet that drifted *because it was hand-authored rather than projected*, and each was
homed individually — the symptom-level program §4 reframes.

**The four declarative authorities (Parts I–IV, implemented + independently audited 2026-05-30).**
- **I Layout** — surface-shell regions + an overlay-slot authority; `surfaceLayout.ts` + `OverlayHost.ts`;
  gate `check-layout-purity` (catalog-projected exemptions).
- **II Accessibility** — landmark roles project from surface `placement` (`landmarks.ts`); gate
  `check-a11y-closure`.
- **III Messaging** — the parallel `SimpleToast` collapsed onto the one advisory model + `RenderHint`;
  gate `check-message-single-model`. (Banner/pill left as observed-state projections — judged over-DRY.)
- **IV Evidence** — RAG citations registered as a sibling execution-surface record (553); shared
  `evidenceProjection.ts` with a labeled score; excerpt boundary owned at the producer.

**The two behavioral authorities (Part II, implemented 2026-06-03).**
- **V Operability** — `<jf-control>`: a native button so role/focus/keyboard are free; name projects from
  the declared action or an explicit label. Gate `check-controls-a11y`.
- **VI Adaptivity** — `adaptiveBar.ts` `OverflowController`: a bar overflows its lowest-priority items
  into "…" instead of clipping. Gate `check-adaptive-closure`.

**The 2026-06-03 declaration-deepening + the primary evidence for §2.** A follow-up pass pushed V/VI from
mechanics toward projection: status-metric names now project from a declared `StatusBarItem.accessibleLabel`
through a new `present({kind:'metric'})`; a per-item overflow policy (`pinned`) lets the connection/memory
signals survive overflow; a reading-column **fill** policy was declared on `SurfaceLayout`
(available but **currently unadopted** — full-bleed is the FE default; see the design note below); the
`adaptive-closure` gate was re-projected from a new `adaptive-regions.v1.json` register. **Critically, the
same pass discovered that the `controls-a11y` gate's `neutralizeInterp` (`check-controls-a11y.mjs:92-117`)
silently misses any `<jf-control>` nested in a `${cond ? html`…`}` interpolation — and that every
template-scanning gate shares the ceiling.** That discovery is the empirical core of §2: it is the moment
the per-authority program revealed its own floor — *you cannot completely gate a property the template
hand-authors* — which is what reframed the whole document around projecting the element instead.

**§15 cost-realism (preserved as the feasibility bracket, see §8).** The original closing pass established
that the framework is generative with no terminus, that "full implementation" silently expands to "build
the missing declarations," and that the right pacer is *the audit, not the document's completeness* — home
a facet only until the next defect it would produce is cheaper than the next increment. This design does
not overturn that; it identifies *what* the high-value increment is (move a facet from authored to
projected) and *why* it terminates the recurrence (§7).

**Design note — the reading-column tradeoff (measured live, 2026-06-03; why the fill policy ships
unadopted).** The slack/fill facet (§VI) was the *fuzziest* of the deepening, and a measured A/B on the
real Settings surface at a 2115px viewport made the reason concrete — it is a genuine *tradeoff*, not a
clean win, which is exactly why it ships as an available-but-unadopted policy rather than a default.

*The case FOR a centered reading column (strongest first):*
- **Label↔control association.** Full-bleed put the "High contrast" label and its toggle **1839px apart**
  (label far-left, control far-right) — you cannot tell the on/off state at a glance on a wide monitor.
  Centering to a 1200px column **halved** that travel to **992px**. Every label-left/control-right settings
  row has this property; for sparse *form* content the gain is real.
- **Reading measure.** Bounded line length keeps section prose and option text comfortable vs. stretching
  across ~2000px.
- **Intentional grouping.** A framed column reads as a deliberate panel; full-bleed reads as unbounded
  sprawl, which exaggerates how empty a sparse page is.

*The honest costs (why it is only partial):*
- **992px is still a large gap** — 1200px merely *softens* the very problem it is best justified by; it does
  not solve it. The cleaner fixes are a **narrower measure** (~640–760px) or **regrouping the row** so label
  and control sit together rather than justified apart.
- **~43% of the viewport becomes empty side margin** (475 + 440px on 2115px). On large monitors a meaningful
  share of users actively dislike that.
- **The card grids (Interface / Appearance / Theme) do not benefit** — they read fine, arguably better,
  full-bleed; centering only shrinks them.

*Decision (recorded).* Keep full-bleed as the FE default; keep the fill policy declared and available
(`:host([data-fill='reading'])` + `--surface-content-max-width`) with **no adopter**. This is the
evidence-paced rule (§8 / §15) applied to a single facet: the mechanism is the deepening; *adoption* is a
measured product decision, deferred here in favor of the cleaner narrower-measure / row-regroup option if
the problem is revisited. A surface re-enables it with one line — `setAttribute('data-fill','reading')`.
