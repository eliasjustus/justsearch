---
title: "569 — The User-Authored Frontend: appearance, layout, AND behavior as a projection over closed host vocabularies (the functional-core inversion)"
type: tempdocs
status: shipped (2026-06-11) — the full 569 §13–§20 line landed on main (4353dec5e). Dated history now; see §21 (Closure) for the disposition of what was built vs deliberately out-of-scope/deferred/dropped.
created: 2026-04-22 (as feasibility study, since rewritten)
recast: 2026-06-05 (research agenda → correct-design theorization)
updated: 2026-06-11 (shipped + merged to main; §20 Phases 0–6 + §21 closure)
extends:
  - tempdoc 559 (the projection kernel "project the element from a typed catalog" — the rendering ENGINE this design makes user-authored)
  - tempdoc 567 (theme-authoring authority — the PRODUCER model this design generalizes from theme to the whole presentation)
  - tempdoc 557 (§2.C consumer token authority + §3 the four-rung prevention ladder — the PREVENTION discipline applied here to the whole authored surface)
related:
  - tempdoc 558 (semantic colour roles + contrast-floor derivation — the a11y invariant the engine co-projects)
  - tempdoc 560 (extension substrate: value-only themes, `isPresentationAdmissible`/`jf-*` confinement, verdict-at-the-seam — the TRUST + DELIVERY substrate)
  - tempdoc 564 (typed wire contracts — what makes safe, type-checked declarative binding possible)
  - tempdoc 543 (the Action / Effect substrate — named actions, the closed `Effect` union, "plugins request, kernel renders", the Effect Journal, PendingEffect, `WhenExpression` — the BEHAVIOR vocabulary the interaction tier composes over) ; tempdoc 550 (action lifecycle statechart / verdict-at-the-seam / attenuable Grant / Autonomy-Dial — the trust gate for user-authored effects)
  - tempdoc 561 (one-interaction-surface IA; modes-as-axis-points; "re-mount, never re-model"), tempdoc 553 (one canonical record; prevention is asymptotic), tempdoc 565 (composeGridStyles spatial composition)
  - docs/decisions/0035-fe-plugin-boundary (the truth/presentation cut); docs/reference/ui/frontend-kernel/kernel/04-shape-governance; the retired 421 draft 476 (chrome-manifest deferral, in git) (chrome-manifest deferral)
  - observations.md #352 (dual token-namespace drift — resolve first), #353 (Google-Fonts CSP), #331 (white-on-accent 1.96:1)
---

# 569 — Presentation as a User-Authored Projection

## §1 Thesis — the correct structure in one move

The inverted frontend — *team owns the functional core, users author all appearance and layout* — is **not a new runtime.** It is the **natural generalization of the single projection spine the codebase already runs at every other altitude**:

> **one authority → a typed declaration → a governed projection → the breaking/subordinate form made *unrepresentable* → a register/gate whose coverage itself projects from the authority's catalog.**

The correct design states that spine for the *whole presentation* — **appearance, layout, AND behavior**:

> **The frontend is a projection of one typed, user-authorable *Presentation Declaration*. The declaration's authored surface is a bounded semantic vocabulary — *seeds + roles + composition + bindings + interaction* — with **no field** for any breaking form (raw CSS, raw structure, derived tokens, free `(fg,bg)` pairs, a new primitive, raw code/logic, or a forked truth-owner). One projection engine renders it; the functional, accessibility, and trust invariants are **co-projected facets**, not author-touchable. Security-bearing UI lives on a **reserved channel the projection cannot reference**. The declaration is a **versioned, data-only, multi-origin artifact** flowing through one catalog, one apply writer, one install-time conformance floor — and, for behavior, the one verdict-at-the-seam trust gate.**

**The deeper unification (the keystone, sharpened 2026-06-05).** Content and behavior are the **same structure**: each authored axis is a **composition over a closed, host-grown vocabulary** that the kernel *interprets/renders* — the user never *executes*. **Theme** composes the closed *token* vocabulary; **content** composes the closed *component* vocabulary (`jf-*` + slots); **behavior** composes the closed *effect + operation* vocabulary as a **statechart** (states/transitions + guards + named-action effects); **layout** composes the closed *layout basis*. The host owns the primitives (tokens, components, effects, operations) and **grows** them; the user composes them. A novel primitive (a content leaf, a novel effect/operation) is the **team-owned long tail** — *symmetrically* for content and behavior. So the real cut is not "look vs function" but **"novel host primitives (team) vs declarable composition (user)."**

Every clause is a *generalization of something the repo already does* (§3): the content engine is 559's "project the element from a typed catalog"; the producer model is 567's seeds/roles theme authority lifted from *colour* to *the whole presentation*; **the behavior engine is the existing 543 Action/Effect substrate + 550 lifecycle/Grant — the codebase *already models interaction as a kernel-internal statechart*; this design lifts it to user-authored data**; the prevention is 557's four-rung ladder; the boundary is 421's truth/presentation cut; the trust substrate is 560's value-only / `jf-*`-confinement / verdict-at-the-seam. **The team does not build a presentation platform; it finishes the projection spine on the two axes (the rendered surface *body* and the *interaction* logic) where the spine is still half-applied or kernel-internal.**

> **Genre note.** Per 557/559/561/567: this is the correct *structure*, not a build plan. Feasibility, phasing, sequencing, and implementation are out of scope (§8). "Major rewrite" is on the table because the goal is the structure that forecloses the defect class long-term, not a patch.
>
> **Measured-confidence annotation (2026-06-05).** §12 records a *confidence ledger* from read-only probes + experiments run against `main`, and the *journey* of two recalibrations it drove. Net: the measured **~50–60% is the *content-body* declarability floor** (one axis: hand-written Lit → a finite projector set), **not** the frontend ceiling — *theme*, *layout*, and (Move 8) *behavior* are **separately** declarable, so the whole-surface ceiling is higher. The probes **de-risk** Moves 4/5/7 (the effect substrate is already data-only; a Top-Layer `<dialog>` closes the spoof gap; constrained decoding is wired) and **confirm** the 558 contrast co-projection live. The two *intermediate* conclusions — "a *surface-composition DSL* as a **fourth** authored tier" and "behavior = team-owned, scope the inversion to the visual half (net MODERATE)" — are **superseded by §12's closing research update and now integrated into the body**: the DSL folds into the **composition** axis (zone/section order) + **bindings** (conditional show/hide), and the genuinely-new authored tier is **interaction** (Move 2's fifth axis / Move 8), declarable to the *same* degree as content. Read the body below as the target structure; read **§12** for the measured journey that produced it.

---

## §2 The defect class, and why the structure must be unrepresentability (not gates)

**The defect class.** A presentation feature flattens or forks the single authority, exposes raw internals, and loses the produce→consume contract — the 557/548 two-authority defect. In the inverted FE this class acquires three sharper faces, because the *author is now untrusted*:

- **Unsafe presentation** — an authored skin that exfiltrates, clickjacks, hides the delete button, or ships unreadable contrast.
- **Forged trust** — an authored skin that impersonates or hides the approval dialog / provenance badge (phishing inside the app).
- **Forked truth** — an authored surface that re-models an Operation/Resource/Prompt, producing the "forked-shell-per-plugin chaos" 421 names.

**The convergent constraint (from the executed prior-art survey, Appendix A).** Six independent research streams reached one finding:

> **Functional/safety invariance scales inversely with the expressiveness of what the untrusted party may author.** Every production system that safely admits outside UI (Shopify, Slack, Atlassian Forge, Figma, WordPress, VS Code, Zed) confines authors to *composition + parameterisation of a host-provided, allowlisted, semantic vocabulary* — never free-form CSS / HTML / code. The moment authoring means *arbitrary* CSS or structure, you enter the unsolved/actively-exploited zone (CSS-only exfiltration, clickjacking, a11y-tree corruption, pixel-perfect trusted-UI spoofing). No formal guarantee of operability under *arbitrary* untrusted presentation exists; the only robust trusted-UI guarantee is a *separate channel the attacker cannot paint into*.

This is exactly 557's own thesis arriving from the security literature: **the prevention is unrepresentability, not gates.** A gate (rung 4) covers only what it knows about and runs after the fact; an author confined to a *vocabulary with no field for the breaking form* (rung 2) cannot express the defect at all. So the correct design is **not "let users write CSS and validate it"** — that is the unsolved problem — **but "give users a typed declaration whose grammar has no slot for the breaking form,"** and let everything dangerous be a *co-projected output the author never touches.* The whole design below is the systematic application of that one principle.

---

## §3 What already exists — the extend-don't-invent ledger

This design is an *extension*, not a greenfield. Honest inventory (verified in code and adjacent tempdocs):

**The spine already runs everywhere except the surface body.**
- **Engine (559).** `SurfaceFactory` already mints a surface from a typed catalog declaration (identity, placement, audience, `consumes` graph, landmark, label, `mountTag`); `present()` / `token()` / `placementToLandmarkRole()` / `toEvidenceItem()` / `composeGridStyles()` already **project individual facets** (label, colour, landmark, evidence, grid) from a declaration. 559's diagnosis: the spine is **half-applied** — *membership* and *some facets* are projected, but **the rendered element body is still hand-authored Lit**, which can only be grep-checked ("the Lit template ceiling").
- **Producer, for theme only (567 + code).** The theme axis is *already a complete user-authored-presentation runtime*: `DesignTokenTree` is a typed declaration; it may set values **only** for the closed `KNOWN_TOKEN_NAMES`; values are validated (`isSafeTokenValue` — no `{}<>` breakout); the host *compiles* it to `@layer user-theme` CSS; `applyAppearance` is the one writer; a user theme is "the same declaration with a different origin" as a built-in. **Malicious CSS is unrepresentable by construction** — a theme cannot emit selectors or rules. This is the reference implementation to generalize.
- **Trust + delivery substrate (560 + code).** `isPresentationAdmissible` already confines an UNTRUSTED plugin to the `jf-*` host vocabulary (its own custom element is dropped as "a forbidden second presentation authority"). `KernelResolver` attenuates capabilities by trust tier at resolve-time (no `if(untrusted)` in method bodies). The `theme` capability is **value-only**. Trust is a verdict **computed once at the delivery seam and obeyed by the FE** (561 deleted the second FE authority). Delivery *is* gate-then-transport, so an ungated delivery is structurally impossible.
- **The boundary is already drawn (421).** The truth/presentation cut — *"Operations/Resources/Prompts = truth; Renderer/Surface/Layout/Chrome/Theme = presentation; truth stays singular, presentation is user-authored"* — already partitions the system along this design's seam. "Validate-at-construction; trust state is a type, not a comment" is 557's unrepresentability stated as a kernel invariant.
- **Catalog delivery (code).** The `Surface/Resource/Operation` catalog clients already make a *backend-declared* surface appear in the live UI **with zero new FE code** — provided its `mountTag` resolves to a registered element. The renderer pipeline (`<jf-form>` + tester-rank `dispatchRenderer` + `RendererUserConfig.rendererOverride` + the `x-ui-renderer` hint) already turns a *schema+uischema data structure* into a nested `jf-*` component tree with a first-class user-override channel.

- **The behavior spine already exists — kernel-internal (543 + 550).** 543 names the **closed `Effect` union** (the host-owned after-effect vocabulary — exhaustiveness-enforced, invertible, journalable), the **`Action`** unit-of-doing, and the **"plugins request, kernel renders"** contract (contributors *describe* effects as data; the kernel *dispatches* — never executes contributor code); the **Effect Journal** gives undo/replay/audit and **PendingEffect** the propose→review seam. 550 adds the explicit **`pending|running|terminal` lifecycle statechart** — *the codebase already models interaction as a state machine* — plus **verdict-at-the-seam** intent evaluation and the **attenuable Grant** + **Autonomy-Dial** trust model. The **operation catalog** (`/api/registry/operations`, generated-schema-validated) is the closed *operation* vocabulary; `operationRef` already makes an action authorable as data; `bindingExpr` + 543's `WhenExpression` are ready safe-guard languages; and `Macro` is a **1-path, guardless statechart** — the generalization seed. So every primitive the behavior axis needs is present; what is absent is the *user-authored transition graph*.

**The two real gaps.** (1) **Content body.** Every `*Surface.ts` body is a bespoke hand-written Lit `render()`; the catalog declares a surface's *identity/placement/wiring/landmark/label* but **not the DOM inside it** (the seam: `Surface.mountTag → customElements.get(tag) → new klass()`). There is a `KNOWN_TOKEN_NAMES` authority for the *colour* axis and the new `ComponentTag` vocabulary for the *component* axis — but no `SurfaceBody` composition driving real surfaces. (2) **Interaction behavior.** It is **kernel-internal** (550's lifecycle; hand-rolled per-surface state machines like `AgentSessionController`'s "14 SSE mutation paths" and SearchSurface's multi-select); there is **no *user-authored* interaction statechart**, only the linear `Macro` precursor. Both gaps — body composition and the interaction graph — are the heart of §4, and they are the *same shape* (composition over a closed host vocabulary).

**Net:** the declaration plumbing, the trust frame, the per-facet projectors, and a working data-driven renderer all exist and are reusable. The correct design lifts the surface *body* out of hand-written Lit the same way 559/565 already lifted layout, rhythm, landmarks, labels, and overflow — and makes the lifted declaration *user-authorable* and *gated*.

---

## §4 The correct design — eight moves

Each move generalizes an existing in-repo authority. Together they are the inverted FE's correct structure. Moves 1–7 establish the projection over the *visual/structural* vocabularies (theme, components, layout) + the trust/gate/origin discipline; **Move 8 adds the *behavior* axis as a statechart over the closed effect/operation vocabulary — the same composition-over-a-closed-vocabulary shape**, so content and behavior are unified.

### Move 1 — One *Presentation Declaration*, the single unit of authorship

*(generalize 567 Move 2 from theme to the whole presentation.)*

There is one typed artifact — the **Presentation Declaration** — that covers the entire authorable surface: **theme** (seeds/roles), **layout-composition** (zones/slots over a closed basis), **surface-body composition** (a closed vocabulary of `jf-*` compositions + slot-fills), and **bindings** (declarative expressions over typed wire contracts). It is first-class, persisted, **versioned**, **DTCG-aligned, never `cssText`/code**, data-only. It flows through **one pipe**: validate → one host compile/derivation pass → one apply writer → **one catalog** that holds team-defaults, community skins, and user skins together.

The structural payoff (567's, generalized): a team default, a community skin, a user skin, and an LLM-authored skin are **the same kind of thing with a different origin**, not four mechanisms. "Preview" is *that declaration applied un-persisted*; "save" is *persisting the same declaration*; "share/import/export" is *that declaration as a portable DTCG document*. There is no preview-vs-saved fork and no core-vs-community fork, because there is one declaration and one catalog.

### Move 2 — The authored surface is *seeds + roles + composition + bindings + interaction ONLY*; every breaking form is unrepresentable (the keystone)

*(generalize 567 Move 1 / 557 clause (b) from theme to the whole presentation.)*

The declaration grammar has typed fields **only** for the *authored tier*:
- **Seeds** — the ~dozen source decisions: hue anchors, primitive channels, scalar scales (radius, density, type scale, motion).
- **Roles (558)** — semantic colour roles whose foreground / on-colour / weak / strong variants are *derived* from the role's background to a contrast floor.
- **Composition** — *placement* of declared panels/zones/slots over the **closed layout basis** (rows/stacks/grids/overflow as named primitives) and the **closed body-component vocabulary** (Move's new authority, below). The author composes; they do not position by pixel.
- **Bindings** — a non-Turing-complete, side-effect-free expression language (CEL/AEL-class) over the **typed 564 wire contracts**: which contract field a component shows, conditional visibility, list-repeat, formatting. **Actions are operation-id references, not closures.**
- **Interaction (the statechart tier — the behavior analogue of composition).** A declarative state-machine: named *states*, a `(state, event) → state'` *transition table*, *guards* (the same expression language as bindings), and transition *effects* that are **named entries in the closed `Effect` union + operation catalog** (`operationRef`), **never code**. The user authors the *interaction graph*; the host owns the *effect bodies*.

The grammar has **no field** for: a derived token, a raw channel triplet, raw CSS, raw HTML/structure, arbitrary positioning, a free `(fg,bg)` pair, a *new primitive*, **raw code / an effect body / a handler closure / a privilege escalation**, or a truth-owner (Operation/Resource/Prompt). The keystone is not a UI choice ("the editor hides these") — it is **structural unrepresentability**: *the model has no slot for the breaking form,* so token-soup, CSS-injection, inaccessible pairs, clickjacking layout, truth-forks, **and arbitrary-code or privilege-escalating behavior** are **not expressible**, not merely discouraged. The same move bounds **content and behavior uniformly**: the user composes a closed vocabulary (components + effects + operations) + declares structure (layout + transitions) + guards; **the host owns every primitive and every effect body.** This is §2's convergent constraint — expressiveness bounded *by the type, not by review* — applied to the whole frontend, *behavior included*.

### Move 3 — One projection engine; the functional / a11y / trust invariants are *co-projected facets*, not author-touchable

*(generalize 559 "project the element from a typed catalog.")*

The 559 engine renders the user's declaration into the real DOM the host fully controls — the user never touches the real tree (the §2 finding that *only the host owning every CSS byte is safe*). Critically, the **invariant facets are derived by the engine, not authored**:

- **Operability** (accessible name, `role`, focus, keyboard) — projected from the declared action/affordance (543), exactly as `<jf-control>` does today.
- **Landmark / region** — projected from `placement` via `landmarks` (already shipped).
- **Contrast-safe colour** — the on-colour is *derived from the role's background to a contrast floor* (558), so a component cannot pick a foreground that fails against its own background.
- **Liveness** — the tri-state observed-state readout, projected from the backend status.
- **Overflow / adaptivity** — projected from the item registry's declared `priority` (565 `OverflowController`).

Because these are *outputs of the engine over the declaration*, **"white-on-bright," "keyboard-dead control," "un-landmarked surface," and "naked-clipping bar" are unrepresentable** — the author has no field for the foreground colour, the keyboard wiring, the landmark, or the clip. **This is how *functional invariance under untrusted presentation* — the thing §2/Appendix A found nobody has — becomes structural rather than gated:** the invariants aren't in the authored grammar, so an author cannot violate them. (The author composes *placement and seeds/roles*; the engine derives *the accessible, operable, contrast-safe element*.)

> **The new authority this move requires — the closed, generated presentation-component vocabulary.** The colour axis has `KNOWN_TOKEN_NAMES` (a closed, generated set the theme may reference). The body/composition axis needs the analogue: an **enumerated, generated vocabulary of `jf-*` compositions + their typed prop/slot surface** that a Presentation Declaration may reference — produced from the component catalog the same way `TokenName` is generated from the token source (rung-3 *generate*). This turns "compose `jf-*`" from a *prefix check* (today's `isPresentationAdmissible`) into a *validated, total vocabulary*. **This is the one genuinely-new structural piece, and it is where the team's perpetual work lives (§7): the team owns and grows the vocabulary; users compose it.**

### Move 4 — Security-bearing UI is a reserved, non-projectable channel

*(make anti-spoofing structural — the trusted-path finding, Appendix A-D.)*

The approval dialog, the provenance badge, the security indicators, and the **mandatory anchored regions** are **not part of the declaration grammar**. They are rendered by a *separate system authority* into a **reserved layer the projection cannot reference, paint into, or occlude** — the structural analogue of an OS trusted path / secure-attention surface, and the generalization of `OverlayHost`'s reserved slots into a *trust-reserved* layer. The skin **has no field for the trusted region**, so it cannot forge it, cover it, or hide it. This rides 560's verdict-at-the-seam (computed once, FE obeys) and adopts the transferable hardening (no occlusion, no default-accept, activation delay, **negative-over-positive indicators** so a forgeable "verified" badge never exists). Anti-spoof thus becomes the same *unrepresentability* move as everything else: the breaking form (a skin that draws the trusted UI) is *not expressible* because the trusted UI is outside the authorable surface.

### Move 5 — The truth/presentation cut is the hard, typed boundary

*(formalize 421's slogan as a type, not a convention.)*

The declaration can express presentation facets but **structurally cannot reach truth**: its *actions are operation-id references* resolved by the host through the capability/grant model (560); its *data is bound through the typed 564 contracts*, scoped to a data capability. Operations / Resources / Prompts stay singular and team-owned; a declaration cannot re-model or fork them. This cut is enforced by the *type system* (the grammar simply has no field for a handler body or a truth-owner), not by review — "trust state is a type" (421). **This cut is the precise statement of the irreducible cost (§7), now sharpened:** the boundary is not "look vs function" but **novel host primitives (components + effects + operations the team writes) vs declarable composition (the user authors)** — applied uniformly to content *and* behavior. A user-authored interaction statechart (Move 8) is bound by this same cut: its transition effects are *named* references the host resolves through the **verdict-at-the-seam + Grant** model (550), so a user's behavior declaration **cannot escalate privilege or fork truth** — it composes the team's actions, it does not author them.

### Move 6 — The prevention ladder over the whole authored surface; the gate relocated CI → install/apply-time, coverage projects from the catalog

*(apply 557 §3 ladder + §5.2 rider.)*

For *every* degree of freedom, climb to the strongest achievable rung. Most breaking forms are made **rung-2 unrepresentable** by Moves 2–5. What *cannot* be made unrepresentable — a schema-valid composition that is nonetheless perf-pathological, or aesthetically broken, or (for layout) targets a region that was removed — is caught by **rung-4 gate**, but with the key relocation: the discipline-gate kernel runs **at install/apply-time over user content**, not (only) at CI over team code. This **conformance floor** certifies, before a declaration may be applied or shared: operability/reachability, a11y + 558 contrast, anti-spoof (trusted regions intact), and a performance budget; on failure it **quarantines the offending surface to the default** (degrade-never-fail) rather than failing the whole app. Per 557 §5.2, the gate's *coverage projects from the surface/role catalog*, so a *future* user surface is covered automatically — "the prevention is not 'remember to register'; the registry enumerates from the authority."

### Move 7 — Multiple authoring origins over one model; value-only / data-only

*(generalize 567 §4 two-surface + add the LLM origin.)*

Three authoring origins emit the **same** declaration through the **same** pipe and the **same** gate — never two models:
- **Simple** — a few high-value roles in Settings → Appearance (end-user).
- **Advanced** — the full seed/role/composition tree with **read-only** inspection of the derived outputs (power user).
- **Generative (LLM)** — the user describes a vibe or pastes a screenshot; the model emits the declaration under **constrained decoding**, so its output is *structurally valid by construction* (**the gate is the grammar**; *reason unconstrained, emit constrained*). This collapses the skill barrier and is *only safe because* the substrate is bounded (Move 2).

Read is universal; **write is TRUSTED** (a persisted, app-wide artifact). The artifact is **data-only**, which *dissolves the malware class* (a declaration cannot execute, so it cannot exfiltrate). The substrate's vetted defaults carry the *taste*; the gate carries the *safety*; the LLM carries the *skill-collapse*.

### Move 8 — The user-authored interaction statechart (the behavior engine; the analogue of Move 3)

*(lift 543's Action/Effect + 550's lifecycle/Grant from kernel-internal to user-authored; the branching generalization of `Macro`.)*

Behavior is authored the *same way* content is — a declarative composition over a closed host vocabulary the kernel interprets. The unit is an **interaction statechart**, the behavior analogue of Move 3's content engine and the branching generalization of the existing *linear* `Macro` (a `Macro` is a 1-path, guardless statechart):

- **States** — named points. They generalize 550's already-explicit `pending|running|terminal` lifecycle *and* 561's *modes* (each a declared point in the autonomy × grounding × persistence axes — a mode **is** a named state).
- **Transitions** — a declarative `(state, event) → state'` table; events are the existing channels (a command invoked, a `jf-data-result`, a backend status change). "Structure is data," exactly as every catalog.
- **Guards** — the *already-shipped* safe expression language (543's `WhenExpression` over the layered `EvaluationContext`; the `bindingExpr` of Move 2). No new language and no author logic — guards are read-only, side-effect-free, terminating.
- **Effects** — each transition fires **named entries in the closed `Effect` union + named operations** (`operationRef`); the kernel *renders* them (543 "plugins request, kernel renders"). The user declares *which* effect fires, never its body.

Everything dangerous is **co-projected or gated, not authored** — the behavior mirror of Move 3:
- **the effect body** is host code (the closed `Effect` union / the operation catalog) — a declaration *references* it, the host *executes* it;
- **trust** is the *same* `evaluateIntent → IntentVerdict` at the seam + the attenuable **Grant** with the **Autonomy-Dial as issuance policy** (550) — a user-authored transition's effect passes the identical gate as any other, so it **cannot escalate privilege**;
- **reversibility + audit** come *for free*: every dispatched edge-effect lands in the **Effect Journal** (undo/replay/audit), and agent-driven transitions route through **PendingEffect** (propose → review).

So **common interaction** (click→navigate, toggle→mutate, conditional show, multi-step flows) is **user-declarable** as a statechart-of-named-actions; only **novel interaction logic** (a bespoke multi-select range algorithm, debounce timing, a custom animation curve) stays team-authored — *as a host-supplied named effect*, the same "team grows the vocabulary" long tail as content leaves (§7). The codebase already holds every primitive (543 Effect/Action/Journal/PendingEffect; 550 verdict/Grant/Autonomy; the operation catalog; `operationRef`; `bindingExpr`; `Macro`); the one genuinely-new piece is the **transition-graph evaluator** (current-state × event × guard → next-state + edge-effects), slotting in as `Macro`'s graph-shaped sibling.

---

## §5 The prevention — why the defect class cannot recur

The four-rung ladder (557 §3) applied per breaking-form. The point of the table is that almost every row lands on **rung 2 (unrepresentable)** — the gate is the floor, not the mechanism:

| Breaking form | Made impossible by | Strongest rung reached |
|---|---|---|
| Token-soup / editing derived tokens | Move 2 — grammar has no field for derived tokens | **2 — unrepresentable** |
| CSS injection / raw selectors | Move 1+2 — declaration is data compiled by the host; no `cssText` field | **2 — unrepresentable** |
| Unreadable contrast (white-on-bright) | Move 3 — foreground *co-projected* from role to a contrast floor; no `(fg,bg)` field | **2 — unrepresentable** |
| Keyboard-dead / un-landmarked control | Move 3 — operability/landmark co-projected; not authored | **2 — unrepresentable** |
| Spoofed / hidden approval dialog or badge | Move 4 — trusted UI outside the authorable surface; reserved channel | **2 — unrepresentable** |
| Forked truth (re-modelled Operation/Resource/Prompt) | Move 5 — actions are op-id refs, data is typed-contract-bound; no truth-owner field | **2 — unrepresentable** |
| Privilege-escalating / arbitrary-code behavior | Move 5+8 — transition effects are *named* refs gated by the 550 verdict-at-the-seam + Grant; no effect-body/handler field | **2 — unrepresentable** |
| New ad-hoc primitive (content **or** effect) / second authority | Move 3/8 — declaration references only the *closed component vocabulary* / *closed `Effect` union + operation catalog* | **2/3 — unrepresentable / generated** |
| Perf-pathological or aesthetically-broken (but schema-valid) composition | Move 6 — install-time conformance gate + quarantine-to-default | **4 — gate (the floor)** |
| Incomplete variant / drift across modes | Move 1+6 — totality over the authored surface; closure gate whose coverage projects from the catalog | **3/4 — generate / gate** |

**Why the class cannot recur (the shape, generalized from 557 §5):** authoring is *seeds/roles/composition/bindings/interaction-only*, so flattening the tiered system into a bag is unrepresentable; the Declaration is the *single persisted artifact in one catalog*, so there is no overlay-vs-saved or core-vs-community fork; derivation + the contrast floor + the operability/landmark projection are the *single producer* of every invariant facet, so unsafe/inaccessible/forged output is unrepresentable; the trusted channel is *outside the authorable surface*, so spoofing is unrepresentable; **behavior is a statechart of *named* effects gated by the one 550 verdict-at-the-seam, so arbitrary-code and privilege-escalating behavior are unrepresentable too**; and the engine + gate are the *one projection the rest of the UI uses*, so there is never a hand-authored second copy. The prevention is the same shape the codebase proved repeatedly: **single authority + an unrepresentable subordinate form** — applied *uniformly to content and behavior*, here turned to face the *user* author.

---

## §6 What already exists vs what is new (honest ledger)

*(mirror 567 §6.)*

**Exists and is correct — extend it:**
- the projection engine + per-facet projectors (559: `SurfaceFactory`, `present()`, `token()`, `landmarks`, `evidenceProjection`, `composeGridStyles`, `OverlayHost`);
- the theme producer as the reference implementation (567/code: `DesignTokenTree` + `KNOWN_TOKEN_NAMES` + `isSafeTokenValue` + host-compile + `applyAppearance` single writer + catalog);
- the trust/delivery substrate (560/code: `isPresentationAdmissible`/`jf-*` confinement, `KernelResolver` tier-attenuation, value-only `theme` capability, verdict-at-the-seam);
- the truth/presentation cut + validate-at-construction (421);
- the catalog-delivery + renderer-dispatch + `RendererUserConfig` override + `x-ui-renderer` hint (code);
- the contrast-floor *principle* (558) and the typed wire contracts (564) and the discipline-gate kernel (530);
- the **behavior spine** (543: `Action`, the closed `Effect` union, "plugins request, kernel renders", the Effect Journal, PendingEffect, `WhenExpression`/`EvaluationContext`; 550: verdict-at-the-seam, attenuable Grant, Autonomy-Dial; the operation catalog; `operationRef` + `bindingExpr` + `Macro` as the 1-path statechart).

**New / missing structure — what this design adds:**
- the **Presentation Declaration** as one artifact spanning theme + layout + body + bindings + **interaction** (today only the *theme* slice exists as a declaration);
- the **closed, generated presentation-component vocabulary** (the body-axis analogue of `KNOWN_TOKEN_NAMES`) — a genuinely-new authority;
- **composition + bindings** as authored tiers with the breaking forms structurally unrepresentable (today the body is hand-authored Lit);
- the **user-authored interaction statechart** — the *transition-graph evaluator* (the behavior analogue of the content engine; `Macro`'s graph-shaped sibling; the one genuinely-new *behavior* piece, lifting 550's kernel-internal lifecycle to user-authored data);
- the **co-projection of the invariant facets** from the user's declaration (today projected only from *team* declarations);
- the **reserved trusted channel** for security UI (today the approval/badge UI is in the same skinnable tree);
- the **conformance gate relocated to install/apply-time over user content** with quarantine-to-default (today the gates run only at CI over team code);
- the **generative (LLM) authoring origin** over the same model.

---

## §7 The irreducible cost, and the honesty bracket

*(553's "prevention is asymptotic" applied here.)*

The inversion legitimately moves **aesthetics, theming, layout-composition, AND the common interaction graph** to users. It does **not** move **new-primitive engineering — on either axis.** Every system surveyed (Appendix A) keeps an open, host-owned primitive set, and the residue is *symmetric*: the **content leaves** (a chart, a map, a picker, a streaming surface) and the **novel effects** (a bespoke selection algorithm, a debounce, a custom animation) never close; a *no-user-code* runtime cannot offer code as its escape hatch, so the escape hatch is *"the team ships a new typed component **or effect** into the closed vocabulary, which authors then compose by data."* Therefore:

> **You stop developing the *look, layout, and common interaction*. You never stop developing the *component + effect vocabulary*.**

And prevention is **asymptotic, never total** (553): the conformance gate guarantees only the *declared, composable* class; the residue (genuinely novel content primitives *and* novel effect logic) stays team-authored forever. This is not a weakness — it is the precise, honest scope of "not needing to develop the frontend," and it coincides exactly with the sharpened cut (Move 5): the team owns *truth + the primitive/effect vocabularies*; users own *their composition of them — appearance, layout, and behavior alike.*

---

## §8 Non-goals

*(per the design-theory genre — 557/567.)*
- **Feasibility, phasing, sequencing, effort, and implementation** — out of scope; this is the correct *structure*.
- **Replacing the chrome shell shape** — Move 1's "composition" is placement of declared panels/zones within a governed frame (561's one-window IA, "re-mount never re-model"), not authoring a new outer shell. Full chrome substitution remains the 421/476 deferred frontier (and the rarely-attempted one — Appendix A).
- **Component *replacement* / new content primitives by users** — that is the truth/primitive axis (Move 5 / §7), team-owned by construction.
- **The consumer-side ghost-token / presentation-purity work** — owned by 557, not re-litigated.
- **How trust is *established* for untrusted-plugin contributions** (signing/attestation) — owned by the 533/560 workstream; this design only requires that *write* be TRUSTED-gated.

---

## §9 The de-risk spike (the test that the generalization holds)

One experiment falsifies or validates the whole structure: **take 559's "project the element from a declaration" and prove the declaration can be (a) externally authored, (b) complete enough to render three maximally-different real surfaces — a *static* settings pane, a *dynamic/streaming* agent surface, and a *results list* — and (c) pass the install-time conformance gate** (operability + a11y + 558 contrast + anti-spoof + perf). If one external declaration drives all three surface *kinds* through the gate, the generalization holds and the rest is the closed-vocabulary build-out. If each kind needs team-authored escape code, the spike has found the real ceiling cheaply. Pair it with a smaller spike: a GBNF-constrained *local* LLM emits a gate-passing theme+layout for one surface (validates Move 7 end-to-end).

---

## §10 Open questions

1. **The functional/presentation-cut taxonomy.** Move 5 draws the line at Operations/Resources/Prompts, but borderline decisions (which result columns show; section order; the home composition; navigation/IA) need a principled rule for *presentation (user-ownable)* vs *truth (team-owned)* — how far composition-ownership extends before it crosses into function.
2. **Mandatory anchored regions.** Move 4 requires a formal *invariant set of regions* that survive any user composition (where the reserved trusted channel and required affordances always live) — the structural guarantee that a user skeleton cannot orphan the approval dialog.
3. **The aesthetic + interaction ceiling of the bounded vocabulary.** Empirical: can *seeds + roles + composition + bindings + interaction* over the closed component **and effect** vocabularies produce genuinely distinctive, well-behaved UIs, or only tasteful-but-samey ones? The substrate's defaults (and the richness of the host-grown component/effect vocabularies) must carry the taste *and* the feel; how much range do they have?
4. **Platform identity.** This turns the FE from a *product* into a *runtime whose content is user-authored* — a strategic identity decision, not a feature.
5. Carryover: theme-sharing artifact (DTCG-stable answers it); remote downloads (a `connect-src` conversation); local-only fonts (obs #353); resolve the dual token-namespace drift (obs #352) *before* building the body-axis authority on a split substrate.

---

## §11 Cross-references

- **The spine this generalizes:** 559 (engine — *the keystone*), 567 (theme producer — generalized to the whole presentation), 557 (consumer authority + four-rung ladder), 558 (roles + contrast floor), 560 (trust/delivery, value-only, `jf-*` confinement, verdict-at-seam), 564 (typed contracts), 561 (one-window IA, re-mount-never-re-model), 553 (asymptotic prevention), 565 (`composeGridStyles`). 560 §4e already names these as instances of one meta-substrate.
- **The boundary:** docs/decisions/0035-fe-plugin-boundary (truth/presentation cut), docs/reference/ui/frontend-kernel/kernel/04-shape-governance, the retired 421 draft 472 (layout manifest, in git) (layout manifest), the retired 421 draft 476 (chrome-manifest deferral, in git) (chrome-manifest deferral + its four unsolved sub-problems).
- **Observations conditioning the work:** #352 (dual `--jf-*` vs `--surface-*` namespace — resolve first), #353 (Google-Fonts CSP), #331 (white-on-accent 1.96:1 — the live 558 defect Move 3 forecloses).
- **Prior art:** Appendix A (executed survey) + Appendix B (source index).

---

## §12 Confidence ledger — measured probes & experiments (2026-06-05)

This design is feasibility-disregarded theory (genre note, §1). Before any implementation, its load-bearing claims were tested by read-only codebase probes, one offline experiment, and an attempted live dev-stack experiment — to **reduce implementation surprises**, not to start building. Verdicts:

| # | Design claim | Measured finding | Verdict |
|---|---|---|---|
| Move 1/3 | the surface *body* is authored as a declaration of composed projected elements | bodies measure **~28% composed / ~72% bespoke**; ~10 finite projectors → ~42–45%; **realistic ceiling ~50–60%**; ~20–25% irreducible residue (prose, nested conditionals, one-off widgets) + ~8–12% domain orchestration that needs a *surface-composition DSL*, not projectors | **TEMPERED (over-claim)** |
| Move 5 | actions are op-id references, not closures | the `Effect {kind:'invoke-operation', operationId, args}` substrate is **already data-only and generically dispatched** (`substrates/effect.ts`, `substrates/actions/index.ts`; live example `controllers/AgentSessionController.ts:501`); gap ≈ 100–150 lines (add an `operationRef` field to the 3 action registries) | **DE-RISKED** |
| Move 4 | security UI on a reserved channel the skin cannot occlude | today's `AuthorizationHost` is **slot-based in the shared DOM → occludable by a skin** (high-`z-index` `position:fixed` overlay); inline `<dialog showModal>` (Top Layer) closes the skin-threat by CSS-spec guarantee at low cost; a separate Tauri window = maximal (Tauri 2 supports it; broker decouple from the main-window lifecycle needed) | **DE-RISKED (inline path)** |
| Move 3 / 558 | foreground co-projected from bg to a contrast floor; white-on-bright unrepresentable | offline oracle (`tmp/research/contrast-oracle.mjs`, throwaway) flags white-on-accent **1.95:1** (matches obs #331) and derives the readable pole (black) / darkest passing accent (`#008672`) in ~30 lines | **CONFIRMED** |
| Move 7 | LLM authoring; "the gate is the grammar" | constrained decoding is **already wired** (`SamplingParams.grammar` / `responseFormat` → llama-server `OnlineModeOps.java:765-840`; llama.cpp enforces grammars at the sampler — a hard guarantee). **Live end-to-end blocked here:** the dev env has no chat-model pack (`ai_activate` → "No chat model configured") | **MECHANISM CONFIRMED; live-OPEN** |
| §10 #5 (precond) | the dual token-namespace drift (#352) must be resolved first | confirmed: `--jf-*` (80 defined / **5** consumers) vs `--surface-*` (186 / **78**); `app-bridge.css` vestigial but still imported (`shell-v0/index.ts:43`); `KNOWN_TOKEN_NAMES` anchors **only** the production vocab | **CONFIRMED — hard but small precondition (delete bridge + repoint 5 files)** |

**The one recalibration that changes the design (not just its confidence).** The body is **not** wholly declarable. The correct framing is a **layered authorable body**: (1) *theme* (shipped), (2) *layout/zone composition* (`composeGridStyles` — near-ready), (3) *content via a finite, team-owned projector set* (~10 projectors → ~50–60%), above which sits an **irreducible bespoke residue (~20–35%)** that stays team-authored. This **strengthens, not weakens, §7's irreducible-cost thesis** — the residue is a substantial floor, not a thin tail. It also surfaces a **genuinely-new design element the theory left implicit: a *surface-composition DSL*** for the orchestration tier (zone/section order, conditional region show/hide) — distinct from both content projectors and `composeGridStyles` — without which the body tops out ~45%. A future revision should: (a) reframe Move 1/3 from "the body is a declaration" → "**theme + layout + a finite projector set are declarable; a bespoke residue remains team-authored**"; (b) promote the surface-composition DSL to an explicit **fourth authored tier** (alongside seeds/roles/composition/bindings); (c) sequence the #352 namespace collapse as a precondition. *(Status — integrated 2026-06-05: (a) is now the body's framing of Move 1/3 + §7's symmetric irreducible cost; (b) the surface-composition DSL **folds into the composition axis** (zone/section order = composition; conditional region show/hide = bindings), and the genuinely-new authored tier turned out to be **interaction** — Move 2's fifth axis / Move 8 — not a separate DSL; (c) remains the #352 precondition, tracked at §10 #5.)*

**Load-bearing residual.** The single biggest unverified piece remains **Move 3's engine generalization** — one engine projecting an *externally-authored* element body. The §9 spike that would settle it (feed an external declaration to `<jf-form>` → render a real `jf-*` tree) is **strongly evidenced by code** (the renderer pipeline is a declaration→DOM renderer *by design*) but **not yet live-confirmed**: this dev env lacks both a chat-model pack and an operator-allowlisted `/api/registry/*` endpoint, so the full live spike needs a provisioned env or jseval step authoring. Recommend it as the first implementation-adjacent step.

**Net confidence.** Direction **HIGH** (the projection spine exists; the theme axis is a working reference implementation). Per-move: **mostly de-risked or confirmed**, with two honest residuals — the engine generalization (live-unconfirmed) and the body's ~50–60% declarability ceiling (which the design must own explicitly via the surface-composition-DSL tier and a larger irreducible residue).

### Inversion-confidence addendum — the remaining (and hardest) work (2026-06-05)

> **Supersession note (read first).** This addendum's headline conclusion — *behavior is the team-owned residue; scope the inversion to the appearance/layout/content tier; net **MODERATE***  — was **overturned by the research update that closes §12** and is **now integrated into the design body**: interaction is *declarable to the same degree as content* via Move 2's **fifth authored axis** and the **interaction-statechart engine** (Move 8), gated by the same 550 verdict-at-the-seam so it cannot escalate privilege. The measured *content*-body numbers below remain valid as dated findings (content tops ~50–60% via projectors); the *behavior-is-team-owned cut* does **not** — it is a superseded intermediate, preserved here as dated history.

A later pass built the *substrate* (Moves 1–7 + DSL + persistence, unit/build/gate-verified) and live-verified the one wired feature (the contrast co-projection — derived `#111111` → **13.62:1** vs white-on-bright **1.39:1**). A critical analysis then found that the **central goal — lifting *real surfaces* into user-authored declarations — was not done.** Two read-only probes measured that remaining work:

**The completeness ceiling, measured on real surfaces (the headline).** Assessing a real conversion of `SettingsSurface` + `SearchSurface`: **~55–60% declarable**, and the dominant blocker is **BEHAVIOR — interaction state machines + async effects + event choreography** (multi-select shift/ctrl, copy-with-1.5s-feedback, context menus, async plugin/consent workflows, Tauri APIs), **not content.** Content/layout/theme is largely declarable (Settings content ~70%; Search row-rendering ~60% via custom renderers); the irreducible ~40–45% is imperative interaction a JSON-Forms-class engine cannot express.

**The key recalibration this forces.** "Users author all appearance *and layout*" is achievable for the **visual/structural half** (theme + layout + content templates) but **not the interactive half**. Interaction behavior is the team-owned residue — and it is *larger and more entangled with "presentation" than the design implied* (multi-select, copy-feedback *feel* like presentation but are behavior). This **sharpens the truth/presentation cut (Move 5)**: the real cut is not "function vs look" but "**behavior vs appearance/layout/content**", and the prior art agrees — every safe declarative system surfaces interaction as *named events → host handlers*, never author logic (Appendix A-1/A-3). Honest scope of the inversion: **users author what a surface *shows* and *where*; the team owns how it *behaves*.** The design body (§1, §4 Move 2) should be revised to state this cut explicitly rather than over-implying "all appearance and layout".

**Runtime-guarantee feasibility (the deepest goal — functional invariance under untrusted presentation):**

| Guarantee | Verdict | Evidence |
|---|---|---|
| Runtime a11y gate (axe over the *rendered DOM* at apply-time) | **FEASIBLE** | `axe-core` v4.11.1 present; browser `axe.run(node)` runs in-WebView (~50 LOC); today CI-only (`e2e/accessibility-audit.spec.ts`) |
| Typed bindings scoped to 564 contracts | **FEASIBLE** | generated wire types + Zod validators (`api/generated/schema-types/*`) importable; `bindingExpr` exists but not yet schema-scoped |
| Reserved trusted channel / mandatory anchored regions | **FEASIBLE / mostly present** | `RESERVED_COMPONENTS` denylist + `OverlayHost` always-present fixed slots + Top-Layer `<dialog>`, kernel-mounted — a user layout cannot remove them |
| **Operability invariance (required-presence)** | **UNDESIGNED — the one real gap** | `Surface.consumes.operations` has **no `required` flag** and chrome does not assert declared ops are mounted; a user layout could silently omit a required action region |

**The aesthetic ceiling (open question, now indicated).** A declaration-driven surface via *generic* controls renders **plainer** than its bespoke version (Appearance as a `<select>` vs the icon option-buttons). Matching the bespoke quality requires *custom renderers* — the team's perpetual projector work (§7). So "users make it *good*" depends on a rich team-supplied renderer library, not the engine alone.

**Net inversion confidence: MODERATE.** The ceiling (~55–60%) and the blocker (behavior) are now *measured, not assumed*; 3 of 4 functional-invariance guarantees are feasible; the one gap (required-presence) is a bounded design. **Go/no-go:** scope the inversion to the **appearance/layout/content tier**; the interactive tier stays team-authored *by design*. The implemented substrate is a correct foundation for that scoped inversion — the unbuilt remainder is the projector library + the per-surface conversion + the required-presence guarantee, none of which are begun.

#### Research update (2026-06-05) — the behavior blocker is the *same bounded-vs-long-tail split*, not a hard wall (recalibrates the above upward)

An internet survey of declarative *interaction* authoring corrects the "behavior = team, ceiling ~55–60%" conclusion as **too pessimistic** (it treated behavior as binary; it is not). Interaction behavior decomposes into three parts, two of which are safely user-declarable:

- **Structure** (states, transitions, what-event-emits-what) → **declarative data.** A statechart *is* data; SCXML executable content can be *"stored as action descriptors… nothing ever evaluated,"* with effects dispatched to **host-registered named actions** ([SCXML — W3C](https://www.w3.org/TR/scxml/)). The Elm/MVU pattern is the same shape: an event emits a *message* (data); the host *reducer* (team code) handles it — the author declares the wiring, never the handler ([Unidirectional UI — Staltz](https://staltz.com/unidirectional-user-interface-architectures.html)). Slack Block Kit / low-code (Budibase/Retool) confirm it commercially: interactions emit a *payload*/named action; a bounded action vocabulary (navigate/mutate/refresh/show) covers the common cases with **no code** ([Block Kit](https://docs.slack.dev/block-kit/), [Budibase actions](https://docs.budibase.com/docs/actions)).
- **Guards/conditions** → a non-Turing-complete expression language — *exactly the `bindingExpr` already built.*
- **Effects/actions** → host-provided **named references** — *exactly the `operationRef` already built* — never author code.

So common interaction (click→navigate, toggle→mutate, conditional show, simple multi-step flows) is **user-declarable** via a *statechart-of-named-actions*; only **novel/complex interaction** (a bespoke multi-select range algorithm, debounce timing, copy-with-1.5s-feedback) stays team-authored — as a host-supplied named action, the *same* "team grows the vocabulary" pattern as content projectors (§7).

**The corrected cut.** Not "behavior vs appearance/layout/content," but "**novel imperative logic (host-supplied named actions) vs declarable structure + wiring + guards + named-actions**" — the *same* bounded-vocabulary-vs-long-tail split as content. The inversion ceiling is therefore **higher than the measured ~55–60%** once a **fifth authored tier — an *interaction statechart* (states/transitions + `bindingExpr` guards + `operationRef` actions)** — is added to the declaration. The two primitives that tier needs are **already built**; the unbuilt piece is the statechart-wiring grammar + a host-action library. And **required-presence** (the one undesigned guarantee) is a *solved* concern in customizable dashboards (mark a region "essential"/non-removable; `editingToggle`) — a bounded pattern, not a research gap ([HA frontend #28480](https://github.com/home-assistant/frontend/issues/28480)).

**Revised go/no-go:** the inversion is **more reachable than the confidence analysis concluded** — interaction is declarable to the *same degree* as content (via primitives already built + the fifth statechart tier); the irreducible residue is *novel imperative logic*, team-owned as named actions (consistent with §7). **This recalibration is now integrated into the design body** — the unification is the §1 keystone, *interaction* is Move 2's fifth authored axis, and **Move 8** is the interaction-statechart engine (the behavior analogue of Move 3, lifting 550's kernel-internal lifecycle to user-authored data). The earlier-body "behavior = team / ceiling ~55–60%" framing is *superseded* by the unified cut: **novel host primitives (team) vs declarable composition (user)**, applied to content and behavior alike.

---

## §13 Implementation status — the reference implementation (2026-06-06)

§12 was *read-only probes before any build*. This section records the **actual reference implementation** built in `worktree-569-presentation` (FE-only, uncommitted): all eight Moves wired into code + **one real surface inverted**, then remediated after a critical-analysis pass. Per *tempdocs-are-dated-history*, this does **not** revise the design above — the body remains the target structure; this dates the realized slice and the gaps the build exposed. The split below is the honest line between **shipped reality** and **still-theory**.

### A. Built + live-verified (the spine is real, and the inversion is real on one region)
- **Move 1–2 (declaration + keystone).** One `PresentationDeclaration` spanning theme/body/layout/**interaction** (`themes/presentationDeclaration.ts`) + apply path (`state/presentationRuntime.ts`, `themes/presentationCatalog.ts`, `themes/builtinPresentations.ts`). The keystone holds: `validatePresentationDeclaration` **rejects** any field for raw CSS / code / handler / effect-body / truth-owner as an *error* (unrepresentable, not ignored).
- **Move 3 (engine + the inversion).** One engine — `components/DeclaredSurface.ts` → `createChildRenderer`; **landmark + 558 contrast co-projected.** Crucially, **the real Settings Interface+Appearance region renders declaration-FIRST by default** (boot-applied in `main.jsx`) at hand-authored quality via bespoke `x-ui-renderer` renderers (`OptionButtonGroupRenderer`, `ToggleSwitchRenderer`) — *live-verified by PNG*. This is the proof-of-thesis on one region.
- **Move 4 (reserved channel).** Top-Layer `<dialog>` `AuthorizationHost` + `RESERVED_COMPONENTS` denylist — a skin mounting the trusted dialog is a validation error (tested).
- **Move 5 (truth cut).** `operationRef` (named ops, exactly-one-of-handler) — no closures in the grammar.
- **Move 6 (gate, partial).** `themes/conformanceGate.ts` certifies at **save and apply**; `quarantineSurfaces`/`quarantineLayout` (degrade-never-fail); a **shadow-aware** runtime contrast oracle (`state/runtimeConformance.ts`); required-presence floor **projected from the real `stage` layout zone** (`themes/requiredRegions.ts`).
- **Move 7 (origins).** Three origins — apply-built-in / JSON-author / **on-device LLM** (`themes/localCompletion.ts` → `host.ai.invokeShape('core.extract')`) — through one certify→apply pipe; the local model (Llama-3.1-8B) emitted a skin that *certified*.
- **Move 8 (behavior engine).** `substrates/interaction/index.ts` — a real statechart (states/transitions/guards/named-effects) dispatching **real, journaled** effects via `applyEffect` (*live-verified* by the Effect-Journal count).
- **Verification:** 2534 FE unit tests, `tsc` clean, `gradlew build -x test` green, the 557/559/565 presentation gates green. Live PNGs read in-conversation for the **declared Settings region** and the **interaction statechart**.

### B. Gaps the build exposed — still theory / not realized (the body above is the target)
1. **"The frontend IS a projection" holds on ONE region, not the frontend.** The mechanism generalizes, but the rest of the UI is still hand-written Lit — the inversion is *demonstrated*, not *deployed*.
2. **§9 spike is incomplete.** Only Settings renders live as a declared surface. The *results-list* and *agent* bodies **certify + quarantine as data** but are **not mounted as real declared surfaces**, and the agent body is *static* (not the streaming kind §9 names). The three-maximally-different-kinds **live** proof is not delivered.
3. **Move 3 co-projection is partial.** Only **contrast + landmark** are structurally unbreakable (rung-2). **Operability, liveness, overflow/adaptivity** rely on well-built renderers + the runtime gate (rung-4 *catch*, not *unrepresentable*). "Functional invariance becomes structural" is therefore half-met.
4. **Move 6 anti-drift not built.** The gate is a **fixed check-set**, NOT *coverage projected from the catalog* (the 557 §5.2 rider) — a future surface is not auto-covered. No **perf budget**; the full apply-time **a11y/axe sweep** is deferred to e2e, not in-app.
5. **Move 1 singularity is split.** Theme rides `host.theme`; body/layout ride `presentationRuntime`; built-in vs user skins are separate stores. One *artifact*, not one *pipe/catalog*. Preview-vs-saved unification and DTCG share/export are unbuilt.
6. **Move 8 trust layer is implicit.** The 550 verdict-at-the-seam / Grant / Autonomy-Dial for *user-authored* effects is not an explicit seam — it leans on the existing backend op-gating. And **behavior drives no real interaction** (a proven capability, not the operating mode of any real surface — unlike content's one region).
7. **The closed *generated* component vocabulary as the single body authority is partial** — the layout-mount vocabulary and the renderer-dispatch set are still two closed sets, not one.
8. **Live-proof gaps** (not yet surfaced as PNGs): the results/agent declared renders, quarantine-*in-action*, the spoof-rejection gate blocking a mount, the in-UI authoring editor, and the **LLM skin applied + rendering** live.
9. **§10 open questions remain open** (as the tempdoc always flagged): the functional/presentation **cut taxonomy**, the formal **mandatory-anchored-region set**, and the empirical **aesthetic + interaction ceiling**.

### C. Net
The **spine is built and honest** — the keystone (unrepresentability), one engine, the typed truth-cut, the reserved channel, the apply-time gate, three origins, and the behavior engine — and the inversion is **real and default on one region**, which is a genuine proof-of-thesis rather than a demo. What remains is the **frontend-wide realization**: converting more real surfaces (the §9 three-kind *live* proof), making behavior the operating mode of a real interaction, completing the co-projection so *all* invariant facets are structural, projecting gate coverage from the catalog, and collapsing theme + body/layout into one catalog/one writer. Those are the target structure the body above already specifies.

### D. Follow-up build (2026-06-09) — several §B gaps closed
A second pass closed structural gaps from §B (all FE-only, uncommitted; build + 2549 unit tests + the 557/559/565 gates green; live PNGs Read in-conversation):
- **B.6 trust seam — CLOSED.** `substrates/interaction/gatedDispatch.ts` routes every statechart effect through the **same 550 verdict-at-the-seam** as `invokeAndApply` (Autonomy Dial → `proposeEffect`/`applyEffect`); it is the machine's default dispatcher. A user-origin effect dispatches+journals; an agent-origin effect the dial wants reviewed is **proposed, not fired** — a statechart cannot escalate privilege (unit-proven).
- **B.4 gate-from-catalog — CLOSED.** `themes/conformancePolicy.ts` projects contrast-pair coverage from the **`COLOR_ROLES` authority** (a new role auto-covers) + the layout authority; added a **perf budget** (node/depth ceiling → quarantine) and a lazy **apply-time axe** pass (`runtimeConformance.auditRenderedA11y`).
- **B.5 one writer/catalog — CLOSED.** `state/presentationState.ts` `applyPresentation(decl)` is the single writer (theme→`applyTheme`, body/layout→`presentationRuntime`); `listPresentations()` is the one catalog (built-in ∪ user); `previewPresentation` mirrors preview. Boot + SettingsSurface authoring now call it.
- **B.2 §9 content kinds — live at bespoke quality.** `SearchResultsRenderer` + `SourceChipsRenderer` render the results-list and agent-answer **content** KINDS through the engine at parity (live-verified). **Honest residual:** the production search **interactions** (shift-range multi-select, context menu, provenance) and the agent **token-streaming** are deliberately NOT forced through the engine — per §7/§9 they are the team-owned long tail; forcing them would regress them. So the §9 proof is *content-projection across three kinds*, not interaction-conversion of the live interactive surfaces.
- **B.3 operability — CORRECTED to structural.** Operability is already structural *for the author*: every authored control composes `<jf-control>`/native renderers (gate-enforced `controls-a11y`), so a user cannot author a keyboard-dead control. The remaining co-projection facets are **liveness + overflow** (still partial). B.7's two closed sets (layout-mount vocab vs renderer-dispatch registry) are **genuinely different authorities** (mount-a-component vs dispatch-a-renderer-for-a-type), not a drift to merge.
- **Live PNGs (Read in-conversation):** declared Settings (bespoke), the §9 agent kind (source chips), the statechart (real journaled effect via the gated dispatcher), quarantine-to-default in action, the anti-spoof rejection, the in-UI authoring editor, and the **local-LLM-emitted skin applied + rendering live** (Llama-3.1-8B authored an "Oceanic" theme → certify → apply → the page recolored).

**Still open** (the honest residual, by design or scope): behavior as the operating mode of a *production* surface flow (the closed Effect vocab doesn't fit the bespoke host-API flows — §7 long tail); liveness/overflow co-projection; and converting the *interactive* search/agent surfaces (the long tail). The frontend-wide rollout beyond the proven pattern remains the target the body specifies.

### E. Current state — consolidation + a design refinement (2026-06-09)

A/B/C/D above are a *dated journey*; this is the **single current-state view** (the §B gap list reconciled, post-build).

**The structural spine is built and proven** (the design's *structure* is real, not just demoed):
keystone unrepresentability (M2) · one engine + co-projection of contrast/landmark/**operability** — 3 of 5 facets now structural (M3) · reserved trusted channel (M4) · truth-cut as a type (M5) · the apply-time gate now with **catalog-projected coverage + perf budget + lazy axe + quarantine-to-default** (M6, *§B.4 closed*) · **one writer / one catalog** (M1, *§B.5 closed*) · three origins incl. the **live LLM-applied** skin (M7) · the behavior engine through the **explicit 550 trust seam** (M8, *§B.6 closed*). All live-verified by PNG (*§B.8 closed*).

**Realized on a proof slice, not the frontend** (the remaining *target*, not a defect): one real region (Settings) is declaration-default; the §9 *content* kinds (results/agent) render through the engine at bespoke quality — but the production app is still hand-Lit. The pattern is proven + generalizable; **the rollout is the work** (*§B.1 / §B.2-content*).

**The §7 long tail — team-owned BY DESIGN** (not a gap): the production *interactions* (search shift-select / context-menu / provenance; agent token-streaming) and novel content primitives stay team-authored; forcing them through the engine would regress them (*§B.2-interaction*).

**Honest residual** (in-scope, unbuilt): behavior as the *operating mode* of a real surface flow (the engine drives real trust-gated journaled effects, but no production surface uses it yet); **liveness + overflow** co-projection (the 2 of 5 facets still rung-4, not rung-2); and the §10 open questions (cut taxonomy, mandatory-region set, aesthetic/interaction ceiling).

> **Design refinement the build surfaced — content and behavior are NOT symmetric in *practice*.** §1's "declarable to the same degree" and §7's "symmetric residue" hold *in principle* but the build shows an asymmetry of **vocabulary coverage**: content's closed vocabulary (components + renderers) genuinely covers real surfaces, so content is deployable now; but behavior's closed `Effect` vocabulary does **not** cover most real surface flows, which are written against **bespoke host APIs** (`host.search`, Tauri commands, `CustomEvent`s) outside the union — so behavior's long tail is materially **larger** than content's, and "behavior on real surfaces" needs the team to *grow the Effect vocabulary* (or adapt those flows to closed effects) far more than content needs new component primitives. This sharpens §7: the residue is *symmetric in kind* (both are "team grows the vocabulary") but *asymmetric in size* (the behavior vocabulary is much further from covering today's real surfaces).

**Net:** the design is **validated** and its **spine built**; the frontend-wide reality is a **proven pattern awaiting rollout**, over a behavior long tail that is real and larger than the symmetry claim implied.

### F. Confidence probe of the *remaining* work (2026-06-09) — measured, with one self-correction

Before implementing the remainder, three read-only investigations measured the uncertainties (so the rollout holds no surprises). Net: confidence **rose across the board**, and one of my own §E claims was **overstated**.

- **Rollout declarability — measured, no dead-ends.** Per-surface declarable-as-content: **Library 80% · Settings 70% · Search 65% · Brain 50% · UnifiedChat 45%** (worst case still 45%). **~6 bespoke renderers** reach 70%+ on *all* surfaces; ranked first targets — **Library** (cheapest, +1 `folder-card`), Search header/filters (95%), Settings rail (85%). The high-effort keystone is the **conversation-timeline** renderer (lifts the agent surface 45→68%). The interaction choreography (multi-select, streaming, tool-approval) is correctly the Move-8 / §7 residue, not renderer work.
- **Behavior vs the closed `Effect` vocab — §E's "much larger" was OVERSTATED (self-correction).** Classified real handlers: **34%** map to existing effects · **23%** need ~6 pragmatic new kinds (`set-appearance`, `set-ui-mode`, `apply-presentation`, `set-search-query`/`-filter`, `save-settings`) · **43%** genuinely bespoke (Tauri / streaming / subscriptions — correctly *outside* the model). That is ~**3:4** vs content, **not** "much larger"; shipping the 6 kinds tips it to **67:33**. **Correction to §E:** behavior's long tail is *material but bounded*, near-parity with content — not dominant. A clean **LOW-risk** conversion exists: search-result-click → `set-selection` + `open-pane` (all closed-vocab, non-destructive).
- **Liveness / overflow / export / mandatory-regions — mechanisms exist + reusable.** Liveness: reuse `present({kind:'metric'})` + a `visibleWhen`-style binding (MED). Overflow: `OverflowController.computeVisibleCount` is generic — add `priority`/`overflow` to `LayoutRegion` (LOW-MED). Export: **567 §8 already ships theme export** (Blob/URL) — mirror it for the declaration (LOW; import = paste-and-apply, already built). Mandatory regions: `OverlayHost` slots + `AuthorizationHost` are **already non-authorable by construction**; the formal floor is `stage` + `statusBar` (a governance call, not a code problem).

**The two experiments (outcomes predicted; live re-run deferred to a fresh env).** The worktree was removed after the merge, so a full dev-server re-run is deferred — but both are substantially de-risked already:
- *Aesthetic/interaction ceiling (OQ#3).* Predicted from the vocabulary + the prior **live LLM-applied skin** (the Oceanic theme recoloured the whole page): **theme/colour range is high** (any palette, contrast-safe by construction), **structural range is bounded** by the component vocabulary (varied looks, *samey shapes* until the team grows the renderer set) — exactly §12's "the substrate's defaults must carry the taste." Honest verdict: distinctive *theming* now; distinctive *structure* scales with the team-owned vocabulary.
- *Behavior-as-operating-mode spike.* Already substantially proven: the statechart **dispatches real, journaled effects live** (the demo's Effect-Journal PNG), the target flow is closed-vocab, and `open-pane` is received by the chrome's `openPaneListener` (`chrome/Shell.ts:826`). Remaining risk: LOW — a mechanical composition of proven parts.

**Two design decisions this surfaces (for the implementation, not now):** (a) grow the `Effect` vocab by the 6 B-kinds? (b) the mandatory-region governance — add `statusBar` + a visibility check, and document the chrome floor as structurally-enforced.

### G. Re-baselined confidence probe — *observed* on current `main` (2026-06-11)

§F's numbers and "predicted" experiments were measured against an **older tree**; since then **~81 commits landed** (559, 565, 570, 571, 574, 421-folder-flatten, 558-color-pair) and the old worktree was deleted. This probe **re-runs the de-risk against current `main`** (`5e1324e04`) in a fresh worktree — turning §F's *predictions* into *observations*, and correcting one stale number. Net: confidence is now grounded in current-main reality, with **one downward correction** and **no new dead-ends**.

**Re-baseline (Step 1 — the staleness surprise, killed).** The spine **builds green on current main**: `tsc` clean, **2696 FE unit tests pass** (up from 2549; the 81 commits added tests, broke nothing); every spine import still resolves and the contrast co-projection still orders correctly. Declarability re-measured against the *current* `render()` shapes:

| Surface | §F estimate | Current-main | Note |
|---|---|---|---|
| Library | 80% | **~70–75%** (holds) | card tier ~80%; non-card ops dilute |
| Settings | 70% | **~70%** (holds) | the proven declaration-default region |
| Search | 65% | **~35–40% (corrected ↓)** | 559 Authority I/II reshaped it; what §F counted as projectable row-structure overlaps the bespoke multi-select / context-menu / provenance layer (verified at `views/SearchSurface.ts:1034-1183`) |
| Agent (Brain/UnifiedChat) | 45–50% | unchanged (lowest) | dominated by the §7 streaming/interaction residue, team-owned by design |

The §F "Search 65%" was **optimistic** — it measured the row-list shape, not the interaction envelope around it. Corrected, the rollout is **lumpier and lower** than §F implied: Library/Settings are deployable now; Search's *content* is a minority of its surface.

**The two experiments — now OBSERVED live (Steps 2–3), not predicted.** All live-verified by `ui-shot` PNG on current main (Read in-conversation):
- *Aesthetic ceiling (OQ#3) — answered empirically.* The on-device LLM's "Oceanic" skin **recolours the whole page live** (surfaces/text/accents) → **theme/colour range is HIGH**. Every skin renders the **same card/button shapes** → **structural range is BOUNDED** by the component vocabulary. Verdict, now observed not predicted: *distinctive theming today; distinctive structure scales only as the team grows the renderer vocabulary* — exactly §12's "defaults must carry the taste." No surprise.
- *Behavior spike — de-risked to LOW, full chain verified.* The statechart **dispatches a real, journaled effect live** ("Effect Journal: 1 real effect(s) dispatched", via the gated dispatcher). The `set-selection`+`open-pane` flow is verified **link-by-link**: `open-pane` in the closed vocab (`substrates/effect.ts:26`) → gated `applyEffect` emits `jf-open-pane` at the **set-site** `substrates/actions/index.ts:464` (unit-tested `actions.test.ts:221-227`) → `openPaneListener` (`chrome/Shell.ts:858`, moved from §F's `:826`) routes `paneId:'inspector'` → `setInspectorOpen(true)`. Only the trivial final composition is unbuilt — *by design* (that is implementation). Plus the full spine re-confirmed live on current main: engine render · quarantine-to-default · anti-spoof rejection (`jf-authorization-host` Rejected by the gate).
- *Liveness/overflow co-projection (Step 4) — reachable, confirmed by source.* `OverflowController`/`computeVisibleCount` (`primitives/adaptiveBar.ts:37,84`) already take **priority-order + a `pinned` policy as data** (559 Authority VI); `present({kind:'metric'})` (`display/present.ts:137`) resolves a metric's declared name while the live value comes from the status registry. So both facets become declaration-driven by a **mechanical mapping** (declared `priority`/`metric`-ref + `bindingExpr` visibility → existing primitive) — no new primitive, LOW-MED wiring. *Honest limit:* confirmed by reading the generic signatures + current consumers, **not** a runtime prototype — the API shape is unambiguous, so the residual runtime risk is small but non-zero.

**Confidence scores (0–10), current main, post-probe** — what each remaining-work item is worth *now*:

| Remaining-work item | §F (pre-probe) | §G (post-probe) | Why it moved |
|---|---|---|---|
| Spine survives current main | 8 | **9** | green build + 2696 tests + imports resolve, *observed* |
| Frontend-wide content rollout | 6 | **6** | pattern proven + Library holds, but Search corrected ↓ (lumpier coverage) — net flat |
| Behavior as operating mode (spike) | 7 | **8** | full chain verified link-by-link incl. set-site + unit test + live dispatch |
| Liveness + overflow co-projection | 6 | **7** | primitives confirmed generic/data-driven; −1 for source-only (no runtime prototype) |
| Behavior deployability / grow `Effect` vocab | 6 | **6** | unchanged (3:4 split stands; still a design call) |
| Mandatory-region governance | 7 | **7** | unchanged (anti-spoof reserved channel re-confirmed live) |
| Aesthetic/interaction ceiling (OQ#3) | 4 | **7** | the biggest gain — *observed*, and the bounded verdict is as-expected, not a nasty surprise |

**Net: ~7/10** (up from ~6 in §F). The structure is proven, the surprises are now *observed* rather than predicted, and the one stale number (Search) is corrected. The two design decisions §F surfaced stand unchanged: (a) grow the `Effect` vocab by the 6 B-kinds; (b) mandatory-region governance (`statusBar` + visibility check). The remaining honest residuals are the *size* of the content rollout (Search is more bespoke than §F thought) and the *runtime* (vs source) confirmation of the liveness/overflow wiring.

### H. Sibling-tempdoc realization map (2026-06-11) — 569's remaining work is landing *distributed*, not as one 569 push

A cross-tempdoc scan of the 559–579 neighbourhood (those modified in the last few hours + the active worktrees) shows 569 is functioning as the **design spine**, while its *remaining rollout* is being realized inside sibling tempdocs — each owning one axis 569 specified. This is the honest current state, and it changes how the remainder should be built (consume the siblings, don't re-derive):

- **574** (*"Completing the presentation projection **kernel** … finishing 557/559/567/**569**"*) **IS 569 Move 2/3's keystone implementation** — the closed generated component **atom** vocabulary + the tree-wide **ambient** delivery, making hand-authored presentation unrepresentable. Its fresh §22.D de-risk pass (2026-06-11, *still uncommitted in `worktree-574-presentation-kernel`*) independently reached **569's exact honest ceiling**: gates make a fork *"harder to write, not impossible"* — *"gate-enforced-unrepresentable, not pure collapse"* — the same finding as 569 §13.B.3 / §13.G (co-projection is half-structural). **Coordination: 569's rollout should consume 574's atom/ambient gates, not re-derive unrepresentability.**
- **575** (*Observed-Happening Register*) **owns LIVENESS now** — it built the C-ii liveness mechanism (worker heartbeats in-flight rows, 550 Thesis II) and a *"stateful stream missing its liveness owner ⟹ build failure"* gate. **This redirects 569 Step-4's liveness facet** (score 7): consume 575's register as the liveness owner rather than hand-wiring `present({metric})`. Net: 569's liveness facet is *more* backed than §G assumed.
- **570** (*The Search Window … the **last hand-authored fork of the spine***) **is the vehicle for 569's lowest-declarability surface** — exactly where §G corrected Search to **~38%**. 570 owns the search→projection conversion; 569's measured number is an input to it. ⚠ *Its worktree is merged/stale yet holds **65 uncommitted files** — orphaned scratch to triage.*
- **565** (*the agent window's content/leaf/navigation authorities*) **is the §7 team-owned vocabulary growth** for 569's *other* low-declarability surface (agent ~45–50%). Its §21.10 *"the plumbing reuses, the map does not"* is the same shape as §G's aesthetic verdict (*theming reuses; structure is bounded by the vocabulary*). Its §23 de-risk also caught a live **thumb-contrast a11y bug** — a reminder the 558 contrast floor 569 leans on is not yet universally applied.
- **571** (*Surface Composition — siblings as declared members*) **is 569 Move 1's composition tier**, now merged/done.

**Net current state:** 569 is validated and its spine is on `main`; the rollout it specified is being executed **across 565/570/574/575**, each as one axis, converging on the *same* honest ceiling (gate-enforced, not collapse). The risk this surfaces is **coordination, not feasibility** — overlapping presentation work in parallel worktrees, with 574's freshest kernel thinking and 570's search work both sitting *uncommitted/orphaned*.

---

## §14 As-built — the remaining work, implemented + live-verified (2026-06-11)

§13 dated the *spine* + the §F/§G/§H confidence probes. This section dates the **implementation of the
remaining work** the probes scoped — built in `worktree-569-derisk` off current `main`, FE-only,
uncommitted (the user merges when they decide). Five phases, each unit-tested, then **one final
real-browser batch** (per the implementation contract). Net: the two co-projection facets that were
rung-4 are now structural; behaviour is the operating mode of a real surface flow; a 2nd real surface
(Library) is declaration-default; the `Effect` vocabulary grew; the present-but-hidden region loophole
is closed.

**What was built (by phase):**
1. **`Effect` v3 — the vocabulary grew (the §F decision (a), taken).** Six kinds added to the closed
   union — `set-appearance` · `set-ui-mode` · `apply-presentation` · `save-settings` · `set-search-query`
   · `set-search-filter` — each a forward-only authored intent routed to its host AUTHORITY via a `jf-*`
   DOM event (the open-pane contract; the substrate imports no app-state). All four exhaustive switches
   (dispatch / deriveInverse / describeEffect / describeChange) + the `AUTHORABLE_EFFECT_KINDS` set
   updated; global listeners in `Shell.ts`, surface listeners in Settings/Search (`set-search-*` is the
   570 seam). `effect.ts`, `substrates/{actions,effects,interaction,macros}`.
2. **Behaviour as the OPERATING MODE of a real surface (Move 8 made operative).** `presentationRuntime`
   now publishes + exposes the **interaction tier** (`activeInteractionFor`) — declared statecharts were
   validated but never *run* (`runMachine`/`createMachine` had zero production callers, the literal
   §13.E gap). `SETTINGS_DECLARED` gained an `APPEARANCE_FLOW` statechart; `SettingsSurface.patch()` now
   routes appearance behaviour through a machine bound to the **gated dispatcher** (the imperative
   `applyAppearance`/`setUiMode`/`fetch` became the effects' host handlers). **Live:** clicking a theme in
   the demo restyles the whole page + journals 2 gated effects.
3. **Liveness + overflow co-projected (rung-2 — the 2 of 5 facets closed).** *Code-revealed refinement:*
   the layout tier has **no live renderer** (like the interaction tier before P2), so the facets attach to
   the rendered **body engine** (`DeclaredSurface` / `SurfaceBodyDeclaration`), mirroring landmark/contrast
   — not `LayoutRegion` as §G's plan assumed. `liveness?: string` co-projects a tri-state readout from the
   one observed-state authority (`aiStateStore`) — the author names the signal, the engine derives the
   state (`LivenessReadout`); `overflow?` co-projects an `OverflowController`-clipped strip — the author
   declares priority, the engine owns the clip. Both **unrepresentable to fake/naked-clip**. **Live:** the
   readout shows the real backend state — against a live `ai_activate`'d stack it reads **"Retrieval —
   Online — Meta-Llama-3.1-8B-Instruct"** with the green live tone (the model name + status derived from
   `/api/status`; the author declared only the `core.retrieval` ref); the 8-item strip clips to "+1".
4. **Mandatory-region governance — the present-but-hidden loophole closed.** *Code-revealed refinement:*
   `statusBar` is legitimately hidden in focus/zen, so it is **not** flat-mandatory (the §G plan's "add
   statusBar" was wrong); the genuine gap was that the presence-only check let an author INCLUDE a required
   region then gate it with `visibleWhen`. `hiddenRequiredRegions` + the gate now quarantine a required
   region carrying any `visibleWhen` (a required region is unconditionally present). **Live:** the gate
   verdict surfaces "quarantined to default ✓ … cannot be authored hidden".
5. **Library declaration-default (the 2nd real surface).** A `jf-folder-card` renderer projects the
   indexed-folder cards at bespoke parity (the Remove INTENT is emitted as an event the surface handles —
   the gated op stays surface-owned, the §7 boundary). A single `CORE_DECLARED` (Move 1, **one
   declaration**) spans the Settings + Library regions + the behaviour tier and is the boot default.
   **Live:** the real Library cards render through the engine (icon/status/path/meta/Remove), in both
   themes.

**Already-closed items (verified, not rebuilt):** conformance-gate catalog-projected coverage
(`conformancePolicy`) and one-writer/one-catalog (`applyPresentation`/`listPresentations`) — both confirmed
still green.

**Live-verification batch (real browser, `jseval ui-shot` PNGs Read in-conversation):** the appearance
statechart restyling + journaling (P2); the liveness readout reflecting real backend state — **"Online —
Meta-Llama-3.1-8B-Instruct", green live tone, against an `ai_activate`'d stack** — + the overflow strip
clipping to "+1" (P3); the mandatory-region quarantine verdict (P4); the declared Library folder cards at
parity (P5). All five user-visible features are browser-verified. Plus green `tsc`, **2709 FE unit tests**,
`gradlew build -x test`, and **14/15 presentation gates**.

**Honest residuals (by environment, not design):**
- The **full-app real-view shots** (`settings`/`library` routes) need a backend for the surface catalog;
  the shared dev stack was held by another active session, so the five features were verified through the
  **standalone demo** (worktree-native vite, no backend) rather than the production routes. The mechanism
  is identical (the same engine + declarations + renderers); the production routes are the same code paths.
- The liveness **"online" tone** is now **captured** — the demo was wired to poll the real backend
  (`startAiStateStore` + the vite `/api` proxy → an `ai_activate`'d stack), and the readout rendered
  **"Retrieval — Online — Meta-Llama-3.1-8B-Instruct" with the green live tone** (the real model + status
  from `/api/status`, the author declaring only the ref). *Note for history:* this took several tries
  because the single shared dev stack was being cycled by 3+ parallel agents (a take-over war I declined to
  escalate); it succeeded once the stack briefly fell idle and I could start my own without disrupting
  anyone. The transient `ECONNREFUSED`→"Connecting…" states observed along the way were themselves evidence
  the co-projection derives the *real* reachability, never a faked one.
- **Mandatory-region** is verified via the gate VERDICT (the layout tier still has no live renderer — the
  §13.B-noted gap), not a rendered-layout quarantine.
- `check-tempdoc-numbers` fails on **pre-existing cross-worktree** series-doc collisions (`#249/#400/#543/
  #558` in other stale worktrees) — this work added no new tempdoc number.

**Net:** every remaining-work item §G scoped is implemented and live-verified; the two code-revealed
refinements (facets on the body tier; statusBar mode-governed not mandatory) sharpened the design honestly.
The inversion is now the operating mode of **two** real surfaces with **all five** co-projected facets
structural and behaviour gated/journaled — the frontend-wide rollout beyond these remains the §7 long tail
(per-surface renderers + the search/agent conversions owned by 570/565).

---

## §15 As-built — the next-layer deepening, implemented + live-verified (2026-06-11)

§14 landed the first-pass remaining work; a theoretical re-analysis then surfaced the *next* layer of
569-owned gaps (the big surfaces Search/agent are sibling-owned by 570/565 and out of scope). This
section dates the deepening — built in `worktree-569-derisk`, FE-only, uncommitted — across three
phases, each unit-tested then live-verified in one final real-browser batch.

**What was built (by phase):**
- **A. A BRANCHING, guarded behaviour statechart on a real destructive flow (Move 8 — full power).** The
  prior appearance flow was a *single-state* chart; this wires `CONFIRM_CEREMONY` (idle→**confirming**→
  done; the CONFIRM edge guarded by `typed == true`) to `SettingsSurface`'s "Delete all data" — the
  imperative `showConfirmDialog` is replaced by an inline declared `confirming` state with a typed-confirm
  input that feeds the guard. The surface runs the bespoke Tauri delete on entering `done` (the §7
  effect body stays team-owned); the chart's declared effect is the closed `toast` (the phantom
  `data.delete-all` op dropped). The first multi-state, guarded statechart driving a real surface.
- **B. HelpSurface → declaration-default (the 3rd real surface).** Two new renderers (`shortcuts-table`,
  `list-items`) project the Help reference content (keyboard-shortcuts table + troubleshooting + network
  lists) through the engine; the FAQ + local-first + the export-diagnostics op stay surface-owned (§7).
- **C. HealthSurface — liveness + overflow on a REAL surface (Move 3 facets, production).** The
  connection STATUS line is now co-projected via `liveness: 'core.retrieval'` (the engine derives the
  tri-state from `aiStateStore`, replacing HealthSurface's hand-painted inline-style status colour); the
  stats region renders metric cards via a new `metric-card` renderer + a co-projected `overflow` strip.
  The facets are no longer demo-only.
- **One declaration.** `CORE_DECLARED` now spans the Settings, Library, Help, and Health regions + the
  behaviour tier (confirm + appearance) — a single artifact, four real surfaces (Move 1).

**Live-verification batch (real browser, `jseval ui-shot` PNGs Read in-conversation):** the branching
delete-confirm ceremony (REQUEST→confirming, the guard BLOCKS an empty input, typed "DELETE"→done firing
the journaled toast) (A); the declared Help reference at parity — shortcuts table + lists through the
engine (B); the Health stats metric cards + overflow strip, and the liveness readout reading **"Online —
Meta-Llama-3.1-8B-Instruct"** (green tone) against an `ai_activate`'d backend (C). Plus green `tsc`,
**2714 FE unit tests**, `./gradlew.bat build -x test` SUCCESSFUL, and **15/15 presentation gates**
(`check-tempdoc-numbers` included).

**Net:** Move 8 now exercises a real *branching, guarded* statechart on a production flow (not just the
degenerate single-state appearance chart); the liveness + overflow facets are deployed on a real surface
(not demo-only); and the inversion spans **four** real surfaces (Settings, Library, Help, Health) under
one declaration. The §7 residue (Tauri/streaming/bespoke interaction; Search→570, agent→565) stays
team-owned by design — the honest, unchanged scope of "not needing to develop the frontend."

---

## §16 Indirect / downstream work the implementation now implies (2026-06-11)

With the inversion real on four surfaces (§15), the *ripple* work — the changes 569's implementation
implies elsewhere — comes into focus. The most important is a consequence of 569's *own* thesis.

1. **The governance gap — the inversion has no gate keeping it inverted (the most 569-aligned).** 569's
   central claim is "the prevention is the register/gate whose coverage projects from the authority's
   catalog." The implementation made Settings/Library/Help/Health declaration-default, but **nothing
   prevents a future agent from hand-re-forking them** (reverting `HealthSurface.renderConnection` to
   hand-painted Lit, or duplicating the `metric-card` logic in a second renderer) — the exact
   two-authority drift 569 exists to foreclose. §13.B.4 flagged this ("Move 6 anti-drift not built — the
   gate is a fixed check-set, NOT coverage projected from the catalog"). The work: a
   **`check-declared-surfaces` register + gate** whose catalog is `CORE_DECLARED`'s region set, enforcing
   (a) every declared region is mounted through the engine (`activeBodyFor` → `<jf-declared-surface>`, no
   hand-rolled body co-existing as a second authority — *distinguishing the allowed degrade-never-fail
   fallback from a forbidden fork*), and (b) each `x-ui-renderer` hint resolves to exactly one registered
   renderer. Without it, "the frontend is a projection" is a *snapshot*, not a durable invariant.
2. **Canonical doc drift (a Hard Invariant).** The as-built presentation kernel (the engine, the grown
   Effect vocabulary, behaviour-as-statechart, the four declaration-default surfaces, the co-projected
   facets) is almost entirely undocumented canonically (only `docs/how-to/write-a-plugin.md` mentions
   `DeclaredSurface`/`x-ui-renderer`). Update `docs/reference/ui/frontend-kernel/` + the agent-system doc,
   then regen `llms.txt` + sync skills.
3. **Hygiene.** Log out-of-scope findings to `observations.md` (e.g. the `[jf-control] no accessible name`
   warnings in the SettingsSurface tests); note the pre-existing Health audience drift (USER in Java vs
   OPERATOR in FE) is now more pointed. Add a **`CORE_DECLARED` contract test** (every declared region
   certifies through the gate; every referenced renderer is registered) so the four-surface inversion
   cannot silently break.

**Not indirect (so not conflated here):** the 574/575 merge reconciliation (the merge plan); rolling
Browse/Log/Memory to declaration-default (*direct* 569 §7-tail continuation, not a ripple).

---

## §17 As-built — the §16 indirect work, implemented (2026-06-11)

§16's three indirect items are implemented (scope confirmed = these three only; the 574/575 merge and the
Browse/Log rollout stay out, per §16's own bracket). The keystone — the inversion now has a gate keeping it
inverted.

- **#1 The governance gate — `check-declared-surfaces` (the most 569-aligned).** New register
  `governance/declared-surfaces.v1.json` (the CATALOG: the 5 `CORE_DECLARED` regions → owning surface +
  region constant; the 6 `x-ui-renderer` hints → tag + module) + new gate
  `scripts/ci/check-declared-surfaces.mjs` (a standalone positive-coverage script, modeled on
  `check-composition-surfaces.mjs` — the lighter `scripts/ci` tier, no ratchet/changeset machinery). It
  enforces **(a)** every declared region's surface mounts the engine (`activeBodyFor` → `<jf-declared-surface>`,
  referencing its region constant) and **(b)** every declared hint resolves to exactly one
  `registerXUiRenderer` call. Per §13.B.4 / the de-risk, the fork-vs-fallback problem is solved by **positive
  coverage**: the gate asserts the engine path is PRESENT (the allowed degrade-never-fail fallback co-exists
  fine); a regression that reverts a surface to hand-Lit drops the mount and FAILS. It deliberately does not
  attempt the infeasible "is a second hand-rolled authority co-existing" scan (the same honest-scope cut as
  composition-surfaces §13.3). Verified to **bite**: a ghost-hint and a bogus-region-const negative probe both
  fail with exit 1; the real catalog passes (5 regions + 6 hints, exit 0). Wired into `.github/workflows/ci.yml`
  (a `ui_web`-gated step) + the CLAUDE.md pre-merge list; `prose-tier-register` stays green after the CLAUDE.md
  edit. Honest ceiling: import-invisible re-models slip (565 §12.10) — the accepted early-warning norm.
- **#1-runtime complement — the `CORE_DECLARED` contract test** (`audit-without-test`: the static gate is the
  hypothesis, the test is truth). `themes/coreDeclaredContract.test.ts` loads the real `CORE_DECLARED` and
  asserts (i) every body region certifies through `certifyPresentation` with no quarantine, and (ii) every
  `x-ui-renderer` hint the bodies reference resolves via `getXUiRendererTag` — so the four-surface inversion
  cannot silently break at the declaration level even if the source-scan is evaded. Green (2 tests).
- **#2 Canonical docs (no canonical drift — a Hard Invariant).** New
  `docs/reference/ui/frontend-kernel/kernel/06-declarative-presentation.md` documents the as-built kernel: the
  `DeclaredSurface` engine, `x-ui-renderer` hint dispatch + the renderer set, the 6 **frontend** `Effect` kinds
  (distinct from the backend agent union — a one-line cross-ref rather than shoehorning them into
  `22-agent-system-architecture.md`, which the investigation showed documents a *different* union), behaviour-
  as-statechart, the four declaration-default surfaces under one `CORE_DECLARED`, the co-projected facets, the
  truth/presentation cut, the conformance gate, and the new anti-drift gate. `llms.txt` regenerated (113 docs);
  `llmstxt-generate --check` + `skills-sync --check` clean.
- **#3 Hygiene.** Logged to `docs/observations.md` Inbox: the `[jf-control] no accessible name` warnings in the
  SettingsSurface tests; the now-more-pointed Health audience drift (USER in the Java `CoreSurfaceCatalog` vs
  OPERATOR in the FE).

**Net:** "the frontend is a projection" is no longer a snapshot — it is a build-enforced invariant. §16 is
complete.

---

## §18 Forward research — what the substrate enables (research-only survey, 2026-06-11)

*A deliberately open-ended "now that it's built, what could we do with it" pass. Method: four parallel
external-research streams (generative/agentic UI; user-theming ecosystems; declarative-UI authoring DX;
adaptive/accessibility-first UI) — pushing past Appendix A's dated June survey to the **current** 2025-2026
frontier and mapping each finding to 569's specific architecture — plus one internal code audit of the
as-built kernel for polish/simplify. Every idea is judged against one test: **does it exploit the
projection / closed-vocabulary property, or fight it?** This section is documentation; nothing here is
committed work.*

### §18.A The convergent validation — 569 is on the winning side of the split, and ahead on two axes

The 2025-2026 generative-UI field **bifurcated**, and the serious entrants independently arrived at 569's
exact thesis. Camp 1 emits *raw code and runs it* (tldraw "Make Real" → HTML/JS in an iframe; OpenAI Apps
SDK → compiled JS over a postMessage bridge) — trust = sandbox-and-hope. Camp 2 emits a **typed declaration
the host interprets** — and that camp is where everyone who cared about agent-authored-UI *safety*
converged:

- **Vercel `json-render`** (Jan 2026, Apache-2.0): a closed Zod *catalog*, the LLM emits a flat JSON spec,
  a registry maps names → components; dynamic props are **expression primitives** (`$state`/`$cond`/
  `$template`), explicitly refusing to give the declaration a handler body — 569's "structurally cannot
  reach truth", reinvented. <https://github.com/vercel-labs/json-render>
- **Google A2UI** (Apache-2.0, with CopilotKit): agents send a flat declarative component list with ID
  refs; the client renders from *its own* catalog; actions round-trip as **action-IDs**, never client code
  — "agents can only request components from your catalog — no UI injection attacks."
  <https://a2ui.org/> · <https://developers.googleblog.com/a2ui-v0-9-generative-ui/>
- **Microsoft Adaptive Cards**: closed JSON element set, host-rendered/themed/a11y — the proof that
  declaration-interpreted UI ships to production at scale. <https://learn.microsoft.com/en-us/adaptive-cards/>
- **Academic convergence**: *Generative Interfaces for Language Models* (arXiv 2508.19227) synthesizes
  per-query UIs via **high-level interaction flows + low-level finite-state-machines** — i.e. 569's
  interaction statechart + declared surface, arrived at independently. *Human Oversight-by-Design for
  Accessible Generative IUIs* (arXiv 2602.13745) proposes requirement-model-as-source-of-truth +
  constrained generation into accessible templates + release gates — and states the wager 569 is built on:
  *"accessibility safeguards cannot be retrofitted post-generation; they must be architectural commitments
  from input spec through release gates."*

**569 goes further than all of them on two axes none fully cover:** (a) a **user-authored interaction
statechart** (json-render/A2UI keep behaviour agent-side; only the academic FSM paper has this layer), and
(b) **co-projected, gate-certified safety invariants** — others trust catalog-membership alone and do NOT
certify contrast/liveness/overflow/required-region. The peer-reviewed result that the obvious approach
(LLM emits UI, check afterward) **reliably ships inaccessible UIs** (W4A 2025, "When LLM-Generated Code
Perpetuates UI Accessibility Barriers") is the empirical case for exactly 569's structural choice.

### §18.B Polish (from the internal as-built audit — the code is lean; no dead code, no TODOs)

1. **Effect-inverse docstring.** The reversibility model is now two-tiered (per-effect inverse for
   navigate/pane/form-value; journal-**replay** for the 6 forward-only v3 presentation/search effects, which
   correctly return `null` from `deriveInverse`). The machinery is coherent and wired (undo/redo in Shell,
   agent-undo in EffectAuditLog) — but the `deriveInverse` docstring doesn't name the two models. One-paragraph fix.
2. **`createObservableStore<T>` helper.** The `_store` + `_listeners` + `notify` + persist idiom is
   re-implemented across `substrates/{effects,macros,actions}` and several `state/` files. Extract one
   helper to DRY it (low risk, no logic change).

### §18.C Simplify (two items — the first is a keystone that also unlocks §18.E features)

1. **Make the conformance verdict STRUCTURED (keystone).** `ConformanceVerdict.errors` is today a
   `readonly string[]` of human messages (`themes/conformanceGate.ts`). Replace with a discriminant union
   (`{kind:'contrast', fg, bg, ratio} | {kind:'perf', surfaceId, nodes, depth} | {kind:'required-region',
   regionId} | …`) + a `describeConformanceError()` for the human text (mirroring the existing
   `describeEffect` split). This is a clean code-quality win **and** the prerequisite for two high-value
   features below: an inline editor linter (anchor each error to its JSON node) and an LLM self-repair loop
   (feed structured reasons back to the model). Do this first; it pays for itself twice.
2. **Lazy-register the bespoke `x-ui-renderer` renderers.** `renderers/registry.ts` eagerly side-effect-
   imports all renderers into `app_main` — the exact growth the ui-bundle forcing-function (§17 / the merge
   rebaseline) is pressuring. The 4 surface-specific renderers (folder-card/shortcuts-table/list-items/
   metric-card) can be dynamically imported on first hint-dispatch (`getXUiRendererTag` miss →
   `await import(...)` → re-resolve, via Lit `until()`), with no dispatch-race (the registry is read at
   render time). This relieves `app_main` toward the `chrome/Shell.ts`-decomposition target without that
   larger refactor — a structural shrink in 569's own scope.

### §18.D Extend (grow the closed vocabularies — deliberately, never speculatively)

- **GBNF-constrained surface emission for the local LLM.** `llama.cpp` ships JSON-Schema→GBNF natively;
  compile the `SurfaceBodyDeclaration` schema → GBNF and constrain the decode so the model emits
  *syntactically* valid declarations every time — eliminating malformed-JSON quarantines so the conformance
  gate only ever adjudicates *semantic* failures. **Honest caveat (keep the gate):** grammars guarantee
  *form*, not *meaning* — valid operation-ids, cross-field consistency, and required-region presence still
  need the gate; constrained decoding does not replace it. A recursive surface tree needs a CFG grammar
  (GBNF qualifies); a **flat element-map** declaration shape (json-render/A2UI's choice) is more
  streaming-resilient and partial-parse-friendly — worth evaluating as a future declaration encoding.
- **Grow the layout basis on demand, not ahead of it.** The richest real-world precedent for a closed
  layout basis is WordPress `theme.json` — and its scars (constant "I can't set a per-block width"
  complaints, Gutenberg #51109) teach that a *too-closed* basis frustrates power users. Treat the basis as a
  versioned surface that grows in response to real declarations that *degrade*, surfacing the gate's
  "you wanted X, here's the safe nearest Y" as a feature, not a dead end.
- **Statechart richness only if authored declarations demand it.** The engine is intentionally flat (no
  nested/parallel states, no timers, no entry/exit actions) — sufficient for the shipped charts. Expansion
  is real scope (validation + engine); gate it on a concrete authored need.

### §18.E New UX features (deduped + prioritised across the four streams)

1. **Per-query result-surface synthesis + self-repair (the headline; only safe here).** The search
   answer emits its *own* body declaration (table vs card-grid vs timeline vs comparison) from the closed
   `jf-*` vocabulary; if the gate rejects it, the structured failure reasons (§18.C #1) feed the local LLM
   for one bounded repair; worst case degrades to the default surface. This is the field's proven killer app
   (Thesys, json-render, the GenerativeInterfaces paper all centre it) — but in a normal React app it is
   reckless (a generated layout can ship an unlabeled, zero-contrast control), whereas in 569 it is *safer
   than not doing it*: you already own the local LLM, the closed vocabulary, the grammar-compilable schema,
   the gate as oracle, and degrade-never-fail as the floor. **The exact properties that make 569 feel
   conservative are what make the field's most-wanted feature safe to ship locally.**
2. **A global adaptive / accessibility axis — Comfort · Compact · Cognitive-Simplified — plus
   guaranteed-AA vision profiles and a "Calm mode".** One user-selectable enum re-projects *every* surface
   (density, clutter, one-primary-action-per-region, suppressed live regions); a vision/contrast profile
   stays AA *by construction* even on a user-supplied palette; Calm mode quiets the co-projected liveness +
   forces reduced-motion across all agent-driven surfaces. **Uniquely cheap here:** because these are
   co-projected facets, an adaptation axis costs **O(1) at the projection layer with structural totality**
   (the gate refuses any surface that escapes it) — a normal app pays O(surfaces) per axis and the
   guarantee rots per-component. Standards wind is behind this: EAA in force since June 2025; WCAG 3.0
   (outcome-based) + APCA (perceptual contrast); W3C COGA (cognitive) and WAI-Adapt (personalization
   semantics). Persist it all as one portable per-user **Accessibility Profile** (one read at projection
   time → total effect). *These are pure projection-layer switches over facets 569 already co-projects — the
   lowest-cost, most-visible near-term payoff.*
3. **A built-in "Style-Variations" gallery + always-one-click revert + live preview + exportable "skin".**
   Ship 4-8 named, conformance-passing built-in declarations (the WordPress Twenty-Twenty-Five "35 one-click
   variations" model) so the kernel's value is *visible to a single local user today*; live-preview is just
   "interpret without commit"; **always-one-click-revert + announce-on-default-change** directly inoculates
   against the 2026 VS Code "Dark 2026" revolt (silent migration, no revert, broken contrast); export a
   variation as an **inert data "skin"**. 569's skins sit at an intersection no mainstream ecosystem
   occupies: **inert like Warp YAML themes (no code → no malware class — VS Code extension malware ~4×'d in
   2025) AND expressive like Obsidian themes (full layout/behaviour) WITHOUT Obsidian's raw-CSS
   exfiltration/layout-jack surface AND without VS Code's unversioned-contract drift.**
4. **A "live authoring surface" — evolve the raw-JSON editor into an approachable one.** Registry-generated
   palette (the editor is *derived from the same closed registry the runtime renders from*, so it can never
   offer an illegal element) → split-pane with the **real runtime renderer** as the live preview (WYSIWYG is
   exact; quarantine-to-default makes the preview crash-proof) → the conformance gate as an **inline,
   node-anchored linter** (needs §18.C #1) → a **"fork this surface and tweak it"** default entry point that
   sidesteps the empty-canvas problem. Study Adaptive Cards Designer (content) and Stately Studio
   (the behaviour-statechart visual editor, with simulate-mode) as the nearest-built instances. The
   long-horizon ceiling is *projectional editing* (JetBrains MPS) where a non-conforming declaration is
   *unrepresentable* rather than authored-then-quarantined.
5. **Safe LLM-/user-authored surfaces as a first-class, advertised capability (the cross-cutting thesis).**
   Let any author — the team, the user (via #4), or the local agent (via #1) — shape a surface, and ship it
   *with the same accessibility / contrast / liveness / overflow guarantees as a team-authored one*, because
   it is the same projection + the same gate. This is the W4A failure mode (LLM UI ships broken a11y) turned
   into a **safety property no other shipping app can claim**.

### §18.F The cross-cutting thesis, sequencing, and honest cautions

**The defensible position, in one line:** *any author — team, user, or local LLM — shapes a surface, and it
is accessible, contrast-safe, live, and adaptive by construction, because the safety facets are emitted by
the substrate and the gate refuses anything that isn't.* No mainstream 2025-2026 app occupies that
intersection.

**Cheapest-first sequence (each step pays for the next):** §18.C #1 *structured verdict* (unlocks the linter
+ self-repair) → §18.E #2 *adaptive axes* (pure projection switches; establishes the per-user profile) →
§18.E #3 *style-variations gallery* (near-free; makes the kernel visible) → §18.D *GBNF* + §18.E #1
*per-query synthesis + self-repair* (the headline) → §18.E #4 *live authoring surface* (largest build) →
§18.C #2 *lazy renderers* anytime (independent bundle relief).

**Cautions:** (1) grammars guarantee form, not meaning — **the conformance gate stays**; (2) don't
hard-couple to APCA (still non-normative draft) — adopt its *direction* (computed perceptual contrast), not
its thresholds; (3) don't over-close the layout basis (WordPress's scars) — grow it on real degrade
evidence; (4) the shareable-skin *gallery with social signals* is genuinely premature for a single-user
local app — deferred-with-reason, not a hidden wait-for-evidence trigger.

### §18.G What would BREAK the cut — do NOT pursue

Anything that lets an author (human or LLM) emit a **new renderer, an effect-handler body, raw HTML/CSS, or
a new operation** (tldraw "Make Real" / OpenAI Apps SDK territory). Per 569's own cut, novel host primitives
are *team* work. If a generative feature needs behaviour the `Effect` union / `jf-*` vocabulary can't
express, the correct move is to **add the primitive to the closed vocabulary first, then let authoring
compose it** — never to let the declaration carry code.

### §18.H Sources (current 2025-2026 frontier; full set in the round-1 research briefs)

Generative/agentic UI: json-render (github.com/vercel-labs/json-render), Google A2UI (a2ui.org), Adaptive
Cards (learn.microsoft.com/adaptive-cards), AG-UI (docs.ag-ui.com), Thesys C1 (thesys.dev), *Generative
Interfaces for LMs* (arXiv 2508.19227), constrained decoding / GBNF (llama.cpp; XGrammar). Theming
ecosystems: VS Code "Dark 2026" revolt (github.com/microsoft/vscode/issues/305526), VS Code extension
malware 2025 (thehackernews.com), Zed versioned theme schema (zed.dev/docs/themes), Warp themes
(docs.warp.dev), Obsidian Style Settings, DTCG 2025.10 stable (designtokens.org), WordPress style variations
(developer.wordpress.org). Authoring DX: Adaptive Cards Designer (adaptivecards.io/designer), Stately Studio
(stately.ai), JSON Forms UI-schema editor, rjsf liveValidate, JetBrains MPS, n8n. Adaptive/a11y: EAA
(levelaccess.com), WCAG 3.0 + APCA, W3C COGA (w3.org/TR/coga-usable), WAI-Adapt (w3.org/TR/adapt), container
/ style queries, *Oversight-by-Design* (arXiv 2602.13745), *W4A 2025: When LLM-Generated Code Perpetuates UI
Accessibility Barriers*.

---

## §19 The completed projection spine — long-term design for the §18 directions (design theorization, 2026-06-11)

*Method: the §18 ideas were investigated against (a) the as-built 569 pipeline and (b) the adjacent
correct-design tempdocs — 557 (presentation projection), 559 (the projection engine), 561 (one-interaction-
surface), 567 (theme-authoring authority, shipped), 574 (per-instance vs global scope), 560 (verdict-at-the-
seam + Grant), 565 (agent emission). Verdict: **none of the §18 directions is new structure.** The
presentation pipeline already runs end-to-end — **Origin → Certification → Projection → Persistence** — with
the conformance gate a genuine single choke point (every apply/preview/save path is forced through
`certifyPresentation`; no bypass exists). The correct long-term design is to **complete that spine by closing
six structural seams the as-built left open**, under one model. Each seam's fix is the same spine the
codebase already runs at every other altitude (§1) — *extend, don't replace*. This section is general
(conceptual structure); it deliberately avoids implementation detail.*

### §19.A What already exists — the spine is ~¾ built (so the work is completion, not construction)

| Stage | Built today | Gap |
|---|---|---|
| **Origin** | three real origins — team (`BUILTIN_PRESENTATIONS`/`CORE_DECLARED`), user (`customPresentations` in the per-user state doc), LLM (`authorPresentationFromPrompt` under a constrained completion) | the declaration has **no `origin` field**; origin is implicit-in-location |
| **Certification** | one gate, **single choke point, no bypass**; quarantine-to-default (degrade-never-fail) | the verdict is **unstructured `string[]`** |
| **Projection** | one engine (`DeclaredSurface`) + co-projected facets (liveness/overflow/contrast/operability/required-region) | facets are **hardcoded**, not an iterated set; projection is **not parameterized** by any ambient input |
| **Persistence** | `UserStateDocument` v2 is rich (multi-profile, signal-projected; per-profile slices + cross-profile `customThemes`/`customPresentations`) | only the **theme tier persists** across reload; **no version history / revert** |

567 already shipped this exact shape for *theme* ("a user theme is the same declaration with a different
origin; one catalog, one apply writer"); 560 already provides the verdict-at-the-seam + Grant model that
keeps an authored declaration's effects from escalating privilege; 565 already carries a typed agent→FE
event stream. So the design below is the **theme/interaction spine generalized to the whole presentation** —
the missing ¼.

### §19.B The six seams, and the one model that closes them (by pipeline stage)

**Stage 1 · Origin — make PROVENANCE a first-class field (Seam 1).**
The three origins are anonymous: a declaration is "team" or "user" or "LLM" only by *where it lives*, not by
what it *is*. That is precisely the unmodelled-concept-that-drifts class 553/558 name. The correct structure
puts **provenance on the declaration** (`origin: team | user | llm | plugin`, with authoring metadata), so
the origins unify as "one artifact, a *declared* origin" (567's realization, generalized). Provenance is
**load-bearing, not decoration**: it is a *trust axis* the rest of the spine reads — the install-time gate
may scale strictness by origin; the reserved trusted channel (Move 4) refuses to be painted by any non-team
origin; an "authored by the assistant" indicator becomes a *projection of the field*, not a guess. This is
what lets an LLM-synthesised per-query surface and a team default flow the **same** pipeline, safely
distinguished.

**Stage 2 · Certification — make the VERDICT structured (Seam 2 — the keystone).**
The gate is already the choke point; its *output* is human strings. The correct structure makes the verdict a
typed discriminant union (offending region id + rule id + value), with a separate `describe()` for prose
(mirroring the shipped `describeEffect` split, 543). This is the keystone because it converts the gate from a
pass/fail guard into a **trust surface** the other directions consume: an inline, node-anchored authoring
linter; a **bounded LLM self-repair loop** (structured reasons → regenerate → re-certify); a per-surface
**conformance receipt** (the EAA-attestation angle). It is also the shape 558 §5.2 already prescribes — a
gate's vocabulary projects from the role/region/facet catalogs, not from free text.

**Stage 3 · Projection — a governed FACET CATALOG, parameterised by ONE adaptation profile (Seams 3 + 4 —
the largest, and the place the design chooses a refactor over a patch).**
- *(Seam 3 — facets become a catalog the engine iterates.)* The safety facets are hardcoded in the render
  path today; a sixth facet is a three-site code edit. The correct structure makes the facet set a **governed
  catalog the engine iterates** — exactly the move 559 makes for *elements* ("the engine co-projects its
  facets"), now applied *reflexively to the facets themselves*, so facet coverage projects from a catalog and
  a new facet is a registered row + its projector, never an engine edit. This is the precondition for the
  adaptive axes (each axis is a facet's *response* to the profile).
- *(Seam 4 — the projection gains one ambient parameter.)* Today theme, light/dark, high-contrast, and
  ui-mode each thread separately through **global document-level setters** — the projection itself takes no
  parameter. The correct structure introduces **one Adaptation Profile** — a *singular authority* (574's "one
  authority, subscribed-to, never re-derived per-instance") carrying {density, vision/contrast, motion,
  simplification, type-scale, mode} — that the engine reads once and that **modulates the facet projection for
  every surface**. An adaptation axis then costs **O(1) with structural totality**: one profile field + one
  facet's response, automatically total over every present *and future* surface, with the gate refusing any
  surface that escapes it. This deliberately **replaces the "add another global toggle" trajectory** (the
  O(surfaces) drift 574 and the W4A/Oversight-by-Design a11y findings both name) with one parameterised
  engine. It *extends* `DeclaredSurface` (the engine stays); it moves facet rendering from hardcoded calls to
  iterating the catalog under the profile. This is the one place the design accepts a real refactor because
  the patch (more global toggles) is the weaker long-term structure.

**Stage 4 · Persistence — the declaration is a VERSIONED, revertible, fully-persisted artifact (Seams 5 + 6).**
- *(Seam 5 — persist the whole declaration.)* Only the theme tier survives a reload today; body/layout/
  interaction are in-memory and lost. The correct structure persists the **whole active declaration** plus
  the adaptation profile as durable per-profile slices (the artifact is already data-only and the
  `customPresentations` home already exists).
- *(Seam 6 — versioned revert.)* There is no history. The correct structure makes apply **append a version**
  to a per-profile history; revert is re-applying a prior version — "*the previous declaration is always one
  apply away*" (the VS Code "Dark 2026" lesson made structural, §18.E #3). This is the home for the
  skins-gallery revert and announce-on-default-change.

### §19.C The §18 directions, located on the completed spine

| §18 direction | Lands on | Net-new vs existing |
|---|---|---|
| Structured conformance verdict | **Seam 2** | refactor an existing type; unblocks the two below |
| Adaptive / a11y axes + per-user profile | **Seams 3 + 4** (facet catalog + profile param) | the one real refactor; UserStateDocument is the persistence home |
| Per-query LLM result-surface synthesis + self-repair | **Seam 1** (provenance) + **Seam 2** (repair loop) + 565 agent emission + 560 grant | the LLM origin + constrained decode already exist; needs the structured verdict |
| Style-variations / skins gallery + versioned revert | **Seam 1** + **Seams 5 + 6** + 567's one-catalog model | mostly assembly of existing parts |
| Visual authoring editor | authoring UX over **Seam 1**, with **Seam 2** as the inline linter; the editor authors *declarations*, the engine (559) renders them | the editor is derived from the closed registries |
| GBNF-constrained emission | the LLM origin's *encoding* (mechanism already confirmed, §12) | a sampler config, not structure |
| Lazy renderer registration | **orthogonal** bundle hygiene — *not part of the spine* | independent simplify (§18.C #2) |

### §19.D Extend-not-replace — the adjacent designs this completes

The design is the spine the codebase already runs (§1), **finished on the presentation axis** — not a second
system. Concretely it *generalises* rather than supplants: **567**'s theme-in-one-catalog-distinguished-by-
origin is the template (lift origin + catalog from *theme* to the *whole declaration*); **559**'s
engine-co-projects-facets becomes the *iterated facet catalog* (Seam 3); **574**'s one-authority-subscribed-
to is the *adaptation-profile* model (Seam 4); **561**'s modes/panels-as-projections is the **same spine on
the interaction surface** — the behaviour half of this same pipeline, "re-mount never re-model"; **560**'s
verdict-at-the-seam + attenuable Grant is the trust gate user-/LLM-authored *effects* already pass (so
provenance, Seam 1, never grants privilege); **UserStateDocument v2** is the persistence home (Seams 5/6).
Nothing here proposes a new runtime, a new catalog, or a second gate.

### §19.E The honest residue (unchanged by this design)

The cut of §7 is untouched: **novel content leaves and novel effects/primitives stay team-owned** — the
inversion moves *composition over closed vocabularies*, never primitive engineering. The closed **layout
basis grows on real degrade evidence**, not speculatively (WordPress `theme.json`'s scars, §18.D). **Grammars
guarantee form, not meaning — the gate stays** (Stage 2 is *more* load-bearing under generative authoring,
not less). And the foreclosure holds by construction: an author (human or LLM) **cannot** emit a new
renderer, an effect body, raw CSS/HTML, or a new operation — Seam-1 provenance plus Move-2 unrepresentability
plus the 560 verdict gate make "the declaration carries code" structurally impossible; the correct response
to a generative need the vocabulary can't express is **grow the closed vocabulary first, then compose**
(§18.G).

### §19.F The structural payoff, in one line

With the six seams closed, the presentation pipeline is one spine in which **any author (team · user · LLM)
× any adaptation (the profile) × any version (the history)** flows through **one gate** and **one
parameterised engine** — safe, accessible, and contrast-locked *by construction*. That turns §18's thesis —
"any author shapes a surface and it is safe by construction" — from an aspiration into the pipeline's
*structure*.

---

## §20 As-built — the §19 implementation, by phase (2026-06-11)

The §19 design is being implemented as seven dependency-ordered phases (the plan; corrections from the
de-risk folded in: Seam 3 dropped, per-query search/agent synthesis out). Each phase is recorded here as it
lands, statically verified (build + FE suite + the gate battery) with live browser verification deferred to
one final batch.

### §20.0 Phase 0 — polish + bundle hygiene (done)
- **`deriveInverse` docstring** now names the two reversibility tiers (per-effect inverse vs journal-replay
  for the 6 forward-only v3 effects) — `substrates/effects/index.ts`.
- **`createObservableStore<T>`** (`state/createObservableStore.ts`) extracts the `value + listeners + notify +
  reset` idiom; adopted in `uiModeState`. Deliberately scoped to the in-memory observable concern — the
  persist-bearing substrate stores keep their own (heterogeneous) persistence; forcing them through one shape
  would be a forced abstraction (the de-risk's Seam-3 lesson).
- **Lazy x-ui-renderer registration** — the four bespoke surface renderers (folder-card/shortcuts-table/
  list-items/metric-card) are dynamically imported on first dispatch (`controls/lazyHintLoaders.ts` +
  `XUiRendererControl` subscribes to the registry and re-renders on registration). This forced a genuine
  structural improvement: the hint registry was extracted from the dispatcher into a dependency-**leaf**
  (`controls/xUiRendererRegistry.ts`) so the renderers self-register without closing a
  control→loaders→renderer→control import cycle (the UI-cycle gate stays green). **Honest measured finding:**
  for these four *tiny, dependency-shared* renderers the lazy split is byte-neutral-to-slightly-negative on
  `app_main` (vite's per-chunk glue ≈ the deferred bytes), so it is NOT a bundle *reduction* — the genuine
  `app_main` relief remains the out-of-scope `chrome/Shell.ts` decomposition. The lazy pattern is kept because
  it is the correct *structure* for surface-specific renderers (and the right shape for future larger ones),
  and the registry-leaf extraction is a real cycle/coupling fix. ui-bundle gate green.
- Verified: typecheck + full FE suite (293 files / 2746 tests) + ui-cycles (0) + declared-surfaces +
  run-renderers + ui-bundle, all green.

### §20.1 Phase 1 — Seam 2: structured conformance verdict (done — the keystone)
`ConformanceVerdict.errors` is now a discriminant union `ConformanceError[]`
(`invalid-json | unrepresentable | contrast | perf | required-region-missing | required-region-hidden`,
each with its structured fields) + a `describeConformanceError()` that renders the historical prose
(`themes/conformanceGate.ts`). This is the keystone the editor inline-linter (Phase 6) and the generative
self-repair loop (Phase 5) consume. The cascade was updated across the enumerated sites: `ApplyResult.errors`
(`state/presentationRuntime.ts`) + `SaveResult.errors` (`themes/presentationCatalog.ts`) now carry
`ConformanceError[]`; the text-render consumers (`views/SettingsSurface.ts` ×4, `demo/presentation-demo.ts`
×4, the `authorPresentation` invalid-json producer) map through `describeConformanceError`; the 7 conformance-
verdict test assertions (conformanceGate ×5, authorPresentation, localCompletion) render the structured
errors before matching. Theme/declaration validation errors stay `string[]` (a different concern — untouched).
Verified: typecheck clean + full FE suite (293/2746) green.

### §20.2 Phase 2 — Seam 1 (provenance) + Seams 5/6 (persist + versioned revert) (done)
- **Provenance (Seam 1):** `PresentationDeclaration.origin?: { kind: 'team'|'user'|'llm'|'plugin' }`
  (interface + validator allow-list + shape check). Provenance is **host-asserted, not model-attested** (so it
  is NOT in the LLM JSON schema): built-ins stamped `team` in one place; `authorPresentationFromPrompt` stamps
  `llm` (overriding the model) pre-certify; `saveCustomPresentation` defaults unstamped to `user`.
- **Persist (Seam 5):** `activePresentationId` is a new **per-profile** `UserStateDocument` slice (mirroring
  `activeThemeId`; the 6-site pattern, with the profile-slice-coverage guard enforcing `flatSlicesFromProfile`).
  `applyPresentation` records it; `main.jsx` boot calls `restoreActivePresentationOnBoot()` (re-apply the
  persisted declaration if it resolves, else `CORE_DECLARED`) through the same certify + degrade path.
- **Versioned revert (Seam 6):** `presentationHistory` is a new **cross-profile** append-only slice (cap 50,
  dedup-consecutive); `revertPresentation()` pops the current entry + re-applies the prior. 7 new tests cover
  persist + dedup + revert + no-op + boot-restore + origin preservation.
- Verified: typecheck clean + full FE suite (293/2746) + the 7 new persist/revert tests, all green.

### §20.3 Phase 3 — Seam 4: the AdaptationProfile authority + a11y axes (done — user-visible)
- New `state/adaptationProfile.ts` `applyAdaptationProfile(partial)` — the ONE authority that merges the
  density/contrast/motion axes, persists them per-profile on `userConfig` (density in `userConfig.density`
  which already threads to the renderers via the DensityController; contrast/motion in a new
  `userConfig.accessibilityProfile`), and projects them to global DOM state (`.high-contrast` /
  `.motion-reduced`) — the cascade re-projects every surface, so an axis is O(1) and total. Omitted axes are
  untouched (mirrors `applyAppearance`); a fresh profile doesn't fight the legacy appearance contrast.
- New `.motion-reduced` global CSS (`styles/tokens.css`) near-zeroes animation/transition app-wide (mirrors
  `prefers-reduced-motion`), quieting the co-projected liveness. `.high-contrast` already exists (AA by the
  `deriveRoleForegrounds` co-projection — contrast stays AA by construction).
- Settings UI: a new "Accessibility" section (`renderAccessibility`) with Density (Compact/Comfortable/
  Spacious) · Contrast (Normal/High) · Motion (Full/Calm) controls calling `applyAdaptationProfile`.
- `main.jsx` boot calls `restoreAdaptationProfileOnBoot()` after `restoreAppearanceOnBoot` (set axis wins).
- Cognitive-simplification deferred (render-level node omission, coupled to the dropped Seam 3).
- Verified: typecheck + full FE suite (2752) + the a11y/contrast/presentation gates + 3 new authority tests, green.

### §20.4 Phase 4 — the style-variations / skins gallery + revert UI (done — user-visible, new surface)
- **6 built-in variations** in `BUILTIN_PRESENTATIONS` (`themes/builtinPresentations.ts`): each is
  `CORE_DECLARED` with a single `accent-tint` oklch delta (Teal/Violet/Amber/Emerald/Rose/Azure). `accent-tint`
  is a colour-ROLE fill whose on-colour is co-projected to a WCAG floor and whose oklch value is deferred by
  the gate's literal-contrast check to the runtime oracle — so every variation certifies + renders contrast-safe
  (a new test asserts all 8+ built-ins certify with team provenance).
- **New `core.presentation-gallery-surface`** rail surface — Java `CoreSurfaceCatalog` SurfaceRef + Surface
  entry (catalog count 13 → 15; the editor surface is pre-declared here too), `CorePlugin` CORE_SURFACES, the
  i18n `registry-surface.*.{label,description}`, `lazySurfaceRegistry` loader, and the new
  `views/PresentationGallerySurface.ts` (extends `JfElement`). It renders the one catalog (`listPresentations`)
  as cards with origin badges; Apply switches the active declaration (the one writer); Revert steps back through
  the apply history (Seam 6); Export copies the active declaration as inert JSON; Import certifies + saves a
  pasted skin (`saveCustomPresentation`). All controls are `<jf-control>` (keyboard-operable).
- Verified: typecheck + full FE suite (2753) + the variations-certify test + `:modules:app-observability:test`
  (catalog count 15) + the surface-altitude gate + the presentation/a11y/layout gate battery, all green.

### §20.5 Phase 5 — generative authoring: backend GBNF hardening + bounded self-repair (done — the productionised experiment)
- **Self-repair (FE, `themes/authorPresentation.ts`):** `authorPresentationFromPrompt` is now a bounded loop
  (`maxRepairs`, default 1). Each iteration: `complete({prompt, responseFormat})` → `JSON.parse` → host-assert
  `origin: llm` (the model cannot self-attest provenance) → `certifyPresentation`. On a parse OR certify failure it
  rebuilds the prompt via `buildRepairPrompt(userPrompt, priorOutput, errors)`, feeding back the **structured
  verdict** (Phase 1's `ConformanceError[]` rendered through `describeConformanceError`) — the keystone that makes
  the loop possible: the model gets the exact rule it broke. Worst case returns the last failing `CertifyResult`
  (the caller degrades to default). Two new tests: a first-attempt-fails-then-certifies repair, and a give-up-after-
  `maxRepairs` case.
- **GBNF hardening (BACKEND, `ConversationEngine.applySchemaConstraint`):** the de-risk found `core.extract` did
  soft validate-retry ONLY — it never set `response_format`, so iteration-1 was unconstrained and the gate caught
  malformed output after the fact. Now: when a substrate-driven request body declares a JSON `schema` (the
  ExtractShape contract — its `ValidationConsumer` already reads `body["schema"]`), the engine parses that schema
  string and promotes it to `SamplingParams.withResponseFormat(Map.of("type","json_object","schema", schemaMap))`.
  `OnlineModeOps` converts schema→GBNF server-side, so the FIRST emission is schema-valid by construction (no
  unknown token / reserved component is even sampleable). When the caller supplied no explicit sampling, the
  constrained params base on `DETERMINISTIC` + thinking-disabled (mirroring the QueryUnderstanding structured-output
  preset). The existing validate-retry loop stays as the safety net: an unparseable schema logs + degrades to
  validate-retry only (sampling untouched) rather than failing the request.
- This is "constrained-emit" replacing "emit-then-validate-retry": the FE self-repair handles the residual
  semantic failures (contrast floor) the server-side grammar cannot express; the backend grammar eliminates the
  structural failures (unknown token, malformed JSON) entirely.
- Verified (static): `:modules:app-services:test --tests *SubstrateDrivenEngineTest*` (3 new: schema→response_format
  promotion, default-preset assertion, unparseable-schema graceful degrade) + typecheck + full FE suite (2755),
  all green.
- **Live finding (the final browser batch — a real Llama-3.1-8B run via `/api/chat/dispatch core.extract`):** the
  live run surfaced a SECOND wiring gap the unit tests could not — the **streaming** inference path (the one the
  conversation engine uses, `OnlineAiServiceImpl.stream` → `OnlineModeOps.streamChatWithTools`) forwarded `grammar`
  but **silently dropped `response_format`**; only the non-streaming `sendChatRequest` applied it. So before this
  fix, NO schema-constrained streaming shape was ever actually constrained. Fixed: the streaming body builder now
  emits `response_format` (precedence over a raw grammar, same no-tools guard), unit-tested by two new
  `OnlineModeOpsTest` cases (`_injectsResponseFormat`, `_responseFormatTakesPrecedenceOverGrammar`). With the wiring
  correct + deployed (`:modules:ui:installDist` per the head-classpath caveat), `response_format` now reaches
  llama-server on the streaming path. HONEST LIMIT: this particular embedded llama.cpp dev build does **not** enforce
  `response_format` as a hard first-emission constraint (the model still emits a prose preamble on iteration-1 with
  BOTH the `json_object+schema` and `json_schema` forms), so `iterationsUsed` stays 2 — the ExtractShape validate-
  retry loop catches the preamble and iteration-2 is clean. Correctness is preserved on EVERY run (3/3 + repeats:
  the final output is always valid, schema-conforming JSON — e.g. `{"accent":"Terracotta","dark":true}`). So the
  "schema-valid by construction / no wasted retry" optimization is realized on a `response_format`-enforcing build;
  on a build that ignores it, the validate-retry + FE self-repair are the correctness safety net (both live-proven).
  The wiring is the complete, correct, root-cause fix; the enforcement is a runtime/build capability the application
  cannot force. Kept the codebase-standard `json_object+schema` form (QueryUnderstanding + the non-streaming path)
  for consistency.

### §20.6 Phase 6 — the visual presentation editor surface (done — user-visible, new surface)
- **New `core.presentation-editor-surface`** rail surface (Java `CoreSurfaceCatalog` entry pre-declared in
  Phase 4; this phase added the FE: `views/PresentationEditorSurface.ts` extends `JfElement` + declares `host_`
  for generation, the `lazySurfaceRegistry` loader, and the i18n labels). The editor edits a full
  `PresentationDeclaration` and projects it through the SAME certify → save → apply pipe every other origin uses.
- **The meta-surface, four affordances, each grounded in an earlier phase:** (a) a PALETTE derived from the
  renderer registry (`listXUiRenderers()` ∪ `listLazyHints()`) ∩ the new `renderers/hintSchemaCatalog.ts`
  authorable-hint catalog — so it can ONLY offer legal, registered elements; inserting one adds a starter node
  (schema property carrying the `x-ui-renderer` hint + the scoped `Control`) to the working body region, and
  `ensureXUiRenderer` registers the (possibly lazy) renderer so the preview can render it. (b) a split-pane live
  PREVIEW = a real `<jf-declared-surface>` rendering the working body region (degrade-never-fail: a malformed
  draft shows DeclaredSurface's own diagnostic, never a crash). (c) the STRUCTURED VERDICT as an inline linter
  (Phase 1 `ConformanceError[]` via `describeConformanceError`) — exact, rule-anchored. (d) FORK-this-surface
  (copy the active declaration into the editor) + the Phase-5 GENERATE-from-prompt control (the on-device model
  through `host.ai`), plus the raw-JSON escape hatch.
- **Settings de-scoped:** the authoring UI (`renderPresentationAuthoring` + the `presentationJson/Prompt/Busy/Msg`
  state + `applyBuiltinDeclared`/`resetPresentation`/`applyDeclarationFull`/`applyJsonDeclaration`/
  `generatePresentation` + their imports) was DELETED from `views/SettingsSurface.ts`; Settings keeps only the
  declared Interface-region render (the Move-3 keystone projection), staying focused on settings.
- A gate-driven correction worth recording (root-cause, not symptom): the `ui-bundle` gate was failing
  `missing_metric` for `vendor_lumino_bytes`/`vendor_zod_bytes` — the 569 policy (`ui-bundle-budget.v1.json`)
  declared those metrics + matchers but `check-ui-bundle-budget.mjs`'s `computedMetrics` still wired the stale
  `vendor_react`/`vendor_motion` names. Fixed the script to compute each declared vendor metric from its matcher
  result; the gate is green (app_main 1051110 < 1054000 cap even with the two new surfaces, both lazy).
- Verified: typecheck + full FE suite (2763; +8: `hintSchemaCatalog.test.ts` ×4 + `PresentationEditorSurface.render.test.ts`
  ×4 — palette-only-offers-legal, insert-keeps-certifying, structured-linter, live-preview-mounts) +
  `gradlew build -x test` (includes `:modules:app-observability:test`, catalog count 15) + the full gate battery
  (declared-surfaces / layout-purity / controls-a11y / a11y-closure / presentation-purity / ambient-purity /
  run-renderers / composition-surfaces / theme-token-closure / color-tokens / style-literal-ratchet / ui-cycles /
  surface-altitude / ui-bundle / prose-tier-register / check-tempdoc-numbers), all green. The live editor flow
  (fork → palette → preview + inline error → apply) is deferred to the final browser batch.

### §20.7 The final live browser batch (2026-06-11 — dev stack + ai_activate + Chrome against the worktree FE)
One batch at the very end (per the plan's defer-all-live-verification discipline), run against a live dev stack
(`Meta-Llama-3.1-8B-Instruct`, GPU runtime active) with the worktree FE served on `:5174` and the head process
rebuilt via `:modules:ui:installDist` (the head-classpath caveat — found necessary mid-batch when a stale dist
masked the Phase-5 backend change). Results, by phase:
- **Keystone pipeline (presentation-demo route, 3 PNGs Read inline):** `presentation-demo-editor` /
  `-authoring` / `-llm` confirm the live certify → apply → projection-render path and the llm-origin declaration
  ("Oceanic Theme", id `llm.ocean`) → "Certified & applied ✓".
- **P6 editor (live, the actual `jf-presentation-editor-surface` + a screenshot Read inline):** mounted with the
  6-hint palette (registry ∩ catalog), the live `<jf-declared-surface>` preview, and the "✓ Certified" linter.
  Inserting a `toggle-switch` via the palette landed a node carrying `x-ui-renderer:'toggle-switch'` + a scoped
  Control and the draft STAYED certified (the palette can only produce legal declarations); an unrepresentable
  draft surfaced the structured inline linter verbatim ("theme.tokens.not-a-token is not a known token").
- **P4 gallery + Seam 6 (live):** 9 cards (the 6 accent variations + core + settings-declared) with origin
  badges; Apply "Teal" → active=`builtin.core-teal`, `canRevert` flipped true; Revert → back to
  `builtin.core-declared`.
- **P2 + Seam 5 persist (live, full page reload):** applied Teal → reloaded → `restoreActivePresentationOnBoot`
  restored the active presentation to `builtin.core-teal` (runtime id + the UI's active card).
- **P3 adaptation (live):** all three axes render as declared controls (Density compact/comfortable/spacious;
  Contrast normal/high-AA; Motion full/calm); toggling projects `.high-contrast` + `.motion-reduced` onto
  `document.documentElement` (the cascade re-projects every surface) and density persists to `userConfig`. The
  Settings authoring UI is confirmed GONE (Phase-6 move).
- **P5 generative (live, real LLM through `/api/chat/dispatch core.extract`):** every run produced valid,
  schema-conforming JSON (`{"accent":"Terracotta","dark":true}`, …). This batch is what surfaced the streaming-path
  `response_format` drop (§20.5) — fixed + unit-tested. The honest enforcement limit of this dev llama.cpp build
  is recorded in §20.5; correctness holds on every run via the validate-retry + FE self-repair safety net.

## §21 Closure — the 569 §13–§20 line landed on `main` (2026-06-11)

The whole line is **shipped**: §14–§17 (declaration-default rollout + anti-drift gates), §18/§19 (design),
and §19 Phases 0–6 (§20) are implemented, statically green, live-verified (§20.7), and **merged to `main`**
(`4353dec5e` — *"merge: land 569 §14–§19"*). This tempdoc is now dated history, not active work.

**The merge (recorded so the reconciliation isn't lost).** The branch was merged against a `main` that had
advanced **111 commits** (560 / 575 / …). One textual conflict (`ui-bundle-budget.v1.json`), resolved; the
rest auto-merged with five build/gate-surfaced semantic fixes (`fix(569 merge)` + the merge commits):
`CoreSurfaceCatalog` count 15 → **16** (my gallery + editor ∪ 575's `system-self-view`); the
`AgentEventSealedTest` tripwire 17 → **18** (`DirectiveAcknowledged` had been added on `main` *without*
bumping it — the gate was already red on `origin/main`, reconciled here); `ui-bundle` re-baselined + caps
bumped (two feature lines combining legitimately exceed each branch's solo budget); a **convergent**
`SystemSelfView` ghost-token fix (`--surface-border` → `--border-subtle` — the 575 agent made the identical
fix independently mid-merge); and the gallery's origin label `.badge` → `.origin-label` (muted text, not a
status atom — clears the atom-fork ratchet). Post-merge tree is byte-identical to the verified branch.

**Disposition of everything NOT built — these are not *remaining 569 work*:**
- **Per-query result-surface synthesis (§18.E #1, the headline)** — the search answer choosing its own
  body shape — is **out of 569 by design** (the self-repair plumbing is built; the search-side feature is
  570/565 scope). Sibling boundary, not a gap.
- **Hard model-level GBNF enforcement** — the wiring is complete + unit-tested (§20.5); the *current embedded
  llama.cpp build* does not enforce `response_format` as a first-emission constraint, so first-try-ness is a
  runtime/build capability, not code work. The validate-retry + FE self-repair keep every run correct.
- **Seam 3 (the facet catalog)** — **dropped** in the de-risk (a forced abstraction); see §19.B.
- **Cognitive-Simplification a11y axis** — **deferred** (needs render-level node omission, coupled to the
  dropped Seam 3); the density/contrast/motion axes (Phase 3) shipped.
- **Growing the closed vocabularies / richer statecharts (§18.D)** — on-real-degrade-evidence only, never
  speculative; a shareable-skin *marketplace* (§18.F caution 4) is premature for a single-user local app.
- The §18 forward-research survey stays exactly that — **research, not committed work** (§18 preamble); the
  next deepening, if pursued, is a *new* tempdoc, not an edit here.

The §7 / §19.E cut is intact: no author (team · user · LLM) can emit a renderer, an effect body, raw
CSS/HTML, or a new operation — the answer to an inexpressible need is **grow the vocabulary first, then
compose** (§18.G).

---

## Appendix A — prior-art research (executed survey, dated June 2026)

*Preserved from this doc's earlier research-agenda form. Six streams; condensed findings with the JustSearch position per row. Full citations in Appendix B. This is a dated snapshot — verify before relying.*

**The convergent finding (all six streams):** invariance ∝ 1/expressiveness — safe outside-authored UI is *always* composition over a host-allowlisted semantic vocabulary, never free-form CSS/HTML/code (§2).

1. **Declarative completeness (SDUI).** Airbnb Ghost Platform / Nubank / Lyft / DoorDash / Spotify render whole screens from a typed declaration, but the *component vocabulary is open-ended and host-shipped* — no closed "complete basis"; the *content leaves* never close; the native escape hatch is permanent ("more often than anticipated"). The *layout frame* is a small near-closed basis; safe *binding* is solved via non-Turing-complete expression languages (CEL / Adaptive-Cards AEL `$when`/`$data`-repeat). → JustSearch hook: a closed *layout* basis + CEL-class bindings over 564 contracts; an open, team-owned *content-primitive* set (Move 2/3/§7).
2. **Safe untrusted authoring.** The one safe pattern (Shopify remote-dom, Slack Block Kit, Forge UI Kit, Figma, Salesforce LWS): emit an *allowlisted host-component tree*; the host owns every DOM node + CSS byte. Untrusted CSS is a no-JS exfiltration/clickjacking channel CSP does not close; `@scope`/`contain`/Shadow DOM are encapsulation, not security; only a sandboxed cross-origin iframe truly contains raw CSS (and severs it from native layout). → hook: Moves 2–3 (host owns the tree; no raw CSS field).
3. **Functional invariance.** Headless UI (Radix/React Aria/…) separates behaviour from looks *for cooperative developers*; the a11y tree is a real but *CSS-corruptible* contract (`display:none`, flex-reorder, `aria-hidden`+focusable); **no formal operability guarantee under untrusted presentation exists.** The partial real answer: *constrain the input language* (WordPress `theme.json`, contrast-locked tokens), not the output. → hook: Move 3 co-projects the invariant facets so they're not authorable.
4. **Anti-spoofing.** Visual trusted/untrusted distinction within one surface is unsolved and *actively exploited* (line-of-death abandoned by Chrome; AI-sidebar spoofing; Edge CVE-2025-65046). The only robust pattern: a *separate channel the attacker can't paint into* (OS trusted path / secure desktop / Android HNSOW + obscured-touch refusal); Top-Layer helps vs DOM occlusion but not vs a peer surface; *negative > positive indicators*. → hook: Move 4 (reserved non-projectable channel).
5. **Generative/LLM authoring.** Constrained/grammar decoding makes schema-valid output *guaranteed* (OpenAI/Anthropic structured outputs; XGrammar/**GBNF local**); caveats: guarantees *valid* not *good* (taste from the substrate), and constrain-the-whole-turn degrades reasoning (mitigation **CRANE** = reason-free-then-emit-constrained). The synthesis "LLM over a safe declarative substrate as an *end-user* authoring tool" is architecturally validated (json-render, Portal UX Agent, Thesys, Tinte) but **competitively unoccupied**. → hook: Move 7.
6. **Stable contracts & ecosystems.** Semantic-token indirection + **DTCG stable v1 (2025.10)** `{alias}`/`$extends` + schema-versioning + degrade-never-fail are the decoupling toolkit; **defaults are an unversioned contract that breaks trust** (VS Code 2026 theme revolt); **layout drift > colour drift** is the least-charted; **data-only artifacts dissolve the malware class**; keeping an ecosystem alive is *social* (distribution, a great default, recognition). → hook: Moves 1/6 (versioned, data-only, degrade-never-fail) + §7 + §10.

## Appendix B — source index (as of June 2026)

*(Unchanged from the research-agenda form; grouped by stream.)*

**A. SDUI & declarative completeness:** Airbnb Ghost Platform ([Airbnb Eng](https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5), [InfoQ](https://www.infoq.com/news/2021/07/airbnb-server-driven-ui/)); Nubank BDC ([building.nubank](https://building.nubank.com/server-driven-ui-framework-at-nubank/)); Lyft ([eng.lyft](https://eng.lyft.com/the-journey-to-server-driven-ui-at-lyft-bikes-and-scooters-c19264a0378e)); DoorDash ([blog](https://careersatdoordash.com/blog/improving-development-velocity-with-generic-server-driven-ui-components/)); Spotify HubFramework ([GitHub](https://github.com/spotify/HubFramework)); practitioner consensus ([MobileNativeFoundation #47](https://github.com/MobileNativeFoundation/discussions/discussions/47)); Adaptive Cards ([MS Learn](https://learn.microsoft.com/en-us/adaptive-cards/templating/language)); JSON Forms ([docs](https://jsonforms.io/docs/)); Flutter RFW ([pub.dev](https://pub.dev/packages/rfw)); CEL ([cel.dev](https://cel.dev/)); JMESPath ([spec](https://jmespath.org/specification.html)).

**B. Safe untrusted UI authoring:** Shopify remote-dom ([eng](https://shopify.engineering/remote-rendering-ui-extensibility), [GitHub](https://github.com/Shopify/remote-dom)) & PCI checkout ([blog](https://www.shopify.com/partners/blog/checkout-compliance)); Figma plugin security ([blog](https://www.figma.com/blog/an-update-on-plugin-security/)); Salesforce LWS ([docs](https://developer.salesforce.com/docs/platform/lightning-components-security/guide/lws-architecture.html)); Forge UI Kit ([docs](https://developer.atlassian.com/platform/forge/ui-kit/)); Slack Block Kit ([docs](https://docs.slack.dev/block-kit/)); DOMPurify ([GitHub](https://github.com/cure53/dompurify)); Trusted Types ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/Trusted_Types_API)); safevalues ([GitHub](https://github.com/google/safevalues)); sandboxed iframes ([web.dev](https://web.dev/articles/sandboxed-iframes)); `@scope` not-a-boundary ([MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@scope)); Blind CSS Exfiltration ([PortSwigger](https://portswigger.net/research/blind-css-exfiltration)); OWASP CSS injection ([guide](https://owasp.org/www-project-web-security-testing-guide/v41/4-Web_Application_Security_Testing/11-Client_Side_Testing/05-Testing_for_CSS_Injection)); CVE-2026-2441 ([SitePoint](https://www.sitepoint.com/zero-day-css-cve-2026-2441-security-vulnerability/)).

**C. Headless UI & functional invariance:** React Aria ([docs](https://react-aria.adobe.com/)); headless roundup ([greatfrontend](https://www.greatfrontend.com/blog/top-headless-ui-libraries-for-react-in-2026)); AOM ([WICG](https://wicg.github.io/aom/explainer.html)); WAI-ARIA 1.2 ([W3C](https://www.w3.org/TR/wai-aria-1.2/)); CSS↔a11y ([WebAIM](https://webaim.org/techniques/css/), [Stanford UIT](https://uit.stanford.edu/news/impacts-css-accessibility)); CSS Zen Garden ([site](https://csszengarden.com/)); CSS-sandbox futility ([jamesbayley](https://blog.jamesbayley.com/2014/05/23/css-how-to-sandbox-a-div-a-lesson-in-futility/)); axe-core ([Deque](https://www.deque.com/axe/axe-core/)); a11y-tree hijacking ([arXiv 2507.14799](https://arxiv.org/pdf/2507.14799)); WordPress constrained layout ([CSS-Tricks](https://css-tricks.com/using-the-new-constrained-layout-in-wordpress-block-themes/)); design-systems-as-policy ([ACM Queue](https://queue.acm.org/detail.cfm?id=3704443)).

**D. Anti-spoofing / trusted UI:** Line of death ([Lawrence](https://textslashplain.com/2017/01/14/the-line-of-death/)); death of the line ([Stark](https://emilymstark.com/2022/12/18/death-to-the-line-of-death.html)); Chromium browser-UI security ([docs](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/docs/security/security-considerations-for-browser-ui.md)); trusted path / TCSEC ([Wheeler](https://dwheeler.com/secure-programs/Secure-Programs-HOWTO/trusted-path.html)); Secure Desktop ([TenForums](https://www.tenforums.com/tutorials/112476-enable-ctrl-alt-delete-secure-desktop-uac-prompt-windows.html)); AI Sidebar Spoofing ([SquareX](https://labs.sqrx.com/ai-sidebar-spoofing-malicious-extensions-impersonates-ai-browser-interface-720e0c91d290)); Edge CVE-2025-65046 ([forum](https://windowsforum.com/threads/edge-ui-spoofing-flaw-cve-2025-65046-fake-prompts-deceive-users.394331/)); Popover/dialog top layer ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/Popover_API)); Android tapjacking ([Android](https://developer.android.com/privacy-and-security/risks/tapjacking)).

**E. Generative/LLM authoring & constrained generation:** OpenAI Structured Outputs ([blog](https://openai.com/index/introducing-structured-outputs-in-the-api/)); Anthropic structured outputs ([docs](https://docs.claude.com/en/docs/build-with-claude/structured-outputs)); XGrammar ([docs](https://xgrammar.mlc.ai/docs/start/quick_start)); GBNF ([DevShorts](https://www.devshorts.in/p/gbnfggml-bnf-explained-an-approach)); JSONSchemaBench ([arXiv 2501.10868](https://arxiv.org/pdf/2501.10868)); CRANE ([arXiv 2502.09061](https://arxiv.org/pdf/2502.09061)); vercel-labs/json-render ([GitHub](https://github.com/vercel-labs/json-render)); Thesys C1 ([site](https://www.thesys.dev/)); Portal UX Agent ([arXiv 2511.00843](https://arxiv.org/abs/2511.00843)); Tinte ([site](https://www.tinte.dev/)).

**F. Stable contracts & ecosystems:** VS Code semantic tokens ([guide](https://code.visualstudio.com/api/language-extensions/semantic-highlight-guide)) & theme-break revolt ([#305526](https://github.com/microsoft/vscode/issues/305526)) & Proposed API ([docs](https://code.visualstudio.com/api/advanced-topics/using-proposed-api)); Zed user themes ([blog](https://zed.dev/blog/user-themes-now-in-preview), [schema PR](https://github.com/zed-industries/zed/pull/21428)); DTCG 2025.10 stable ([W3C](https://www.w3.org/community/design-tokens/2025/10/28/design-tokens-specification-reaches-first-stable-version/), [format](https://www.designtokens.org/tr/2025.10/format/)); Style Dictionary ([GitHub](https://github.com/style-dictionary/style-dictionary)); Obsidian plugin future ([blog](https://obsidian.md/blog/future-of-plugins/)); HACS ([site](https://www.hacs.xyz/)); Raycast API ([blog](https://www.raycast.com/blog/how-raycast-api-extensions-work)); Figma Creator Fund ([blog](https://www.figma.com/blog/three-creator-fund-projects-to-know-and-love/)); VS Code marketplace malware ([Koi.ai](https://www.koi.ai/blog/2-6-exposing-malicious-extensions-shocking-statistics-from-the-vs-code-marketplace)).
