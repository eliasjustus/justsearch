---
title: "Frontend kernel — declarative presentation"
type: reference
status: stable
description: "The as-built presentation kernel: the DeclaredSurface engine, x-ui-renderer hint dispatch, the frontend Effect vocabulary, behaviour-as-statechart, the four declaration-default surfaces under CORE_DECLARED, the co-projected accessibility facets, and the truth/presentation cut."
date: 2026-06-11
---

# Frontend kernel — declarative presentation

> **As-built reference for tempdoc 569 (§13–§16).** The team owns a functional *core*; users author a
> surface's *appearance, layout, and behaviour* as a **declaration the kernel interprets — never code it
> executes**. Four real surfaces (Settings, Library, Help, Health) are **declaration-default** today.
> The design rationale lives in tempdoc 569 (functional-core user-authored presentation); this
> doc records the shipped mechanism. Sibling kernel docs are in this folder; the primitives a
> declaration references (Operation/Resource/Prompt) are in `00-primitives.md`.

## The cut: novel host primitives (team) vs declarable composition (user)

The kernel's organising principle is **not "look vs function"** but **"novel host primitives vs
declarable composition."** Each authored axis is a *composition over a closed, host-grown vocabulary*:

| Axis | Closed vocabulary the user composes | Owner of the vocabulary |
|---|---|---|
| Theme | semantic tokens | team |
| Content | `jf-*` components + slots (the `x-ui-renderer` set) | team |
| Layout | the layout/zone basis | team |
| Behaviour | the `Effect` + operation vocabulary, as a statechart | team |

A *novel* primitive (a new content leaf, a new effect/operation) is the **team-owned long tail**,
symmetrically for content and behaviour. The user composes the primitives; the host grows them. A
declaration **structurally cannot reach truth** — it has no field for a handler body or a truth owner.

## The engine — `DeclaredSurface`

`modules/ui-web/src/shell-v0/components/DeclaredSurface.ts` (custom element `<jf-declared-surface>`,
`display:contents`) is the one renderer that turns a declaration into DOM. Given a region id it resolves
the active `SurfaceBodyDeclaration` via `activeBodyFor(regionId)` from the presentation runtime
(`state/presentationRuntime.ts`) and renders it through the JSON Forms binding. A surface is
**declaration-default** iff it imports `activeBodyFor` and mounts `<jf-declared-surface>` for its
region(s) instead of hand-painting Lit.

A `SurfaceBodyDeclaration` carries:

- **`schema` / `uischema`** — the JSON Forms data schema + UI schema for the region body.
- **`heading`**, **`placement`** — presentation chrome.
- **co-projected facets** — **`liveness`** and **`overflow`** (see below). These are *structural*: the
  author selects from a closed facet set; they cannot author an inaccessible or unbounded surface.

**Degrade-never-fail.** If a body fails the conformance gate (below), the engine quarantines *that
surface* to the built-in default render — never the whole app. A declaration-default surface therefore
keeps a fallback render path; the allowed fallback is indistinguishable from a forbidden hand-rolled
fork by source scan, which is why the anti-drift gate is *positive coverage* (below).

## Content — `x-ui-renderer` hint dispatch

A region body composes content from the closed component vocabulary. A schema property carrying
`'x-ui-renderer': '<hint>'` dispatches to a first-party renderer. Renderers self-register on module load
via `registerXUiRenderer('<hint>', '<jf-tag>')` (`renderers/controls/XUiRendererControl.ts`) and are
resolved by `getXUiRendererTag('<hint>')`. The renderer barrel `renderers/registry.ts` side-effect-imports
the full set so registration is order-independent; the x-ui-renderer dispatcher wins on rank regardless of
import order.

The shipped hint renderers under `renderers/controls/` include: `folder-card`, `shortcuts-table`,
`list-items`, `option-button-group`, `toggle-switch`, `metric-card` (plus `search-results`/`source-chips`
content kinds proven at bespoke quality). Each hint resolves to **exactly one** registered renderer — a
second renderer for a declared hint is a fork the `check-declared-surfaces` gate refuses.

## Behaviour — the frontend `Effect` vocabulary and interaction statecharts

Behaviour is declarable *to the same degree as content*: a user-authored **interaction statechart**
(states / transitions + guards + **named-action effects**). The closed frontend `Effect` union
(`substrates/effect.ts`) is the vocabulary; the statechart references effects by name, it does not author
logic. The shipped presentation effects are:

| Effect kind | Meaning |
|---|---|
| `set-appearance` | apply `{ theme?, highContrast? }` |
| `set-ui-mode` | switch `{ mode }` (e.g. simple/advanced) |
| `apply-presentation` | apply a presentation declaration by `{ presentationId }` |
| `save-settings` | persist `{ settings }` |
| `set-search-query` | set `{ query }` |
| `set-search-filter` | set `{ fromMs?, toMs? }` |

Each effect is dispatched as a `jf-*` DOM event by `substrates/actions/index.ts` and handled centrally in
`chrome/Shell.ts` (e.g. `jf-set-appearance` → `applyAppearance`, `jf-apply-presentation` →
`listPresentations` + `applyPresentation`). Because effects are *named references the host resolves*, a
user's behaviour declaration **cannot escalate privilege or fork truth** — transition effects route
through the same verdict-at-the-seam + Grant model (tempdoc 550) as every operation. This is the
behaviour analogue of the content/theme cut.

> **Scope note.** This is the **frontend presentation** Effect union (UI-side, kernel-interpreted). It is a
> distinct vocabulary from the **backend agent** `Effect` union (the Action lifecycle in tempdoc 543/550,
> documented in `docs/explanation/22-agent-system-architecture.md` §Action Lifecycle).

The built-in interaction charts ship in `themes/builtinPresentations.ts`: `core.confirm-ceremony` (a
destructive-op confirm with a typed guard + a closed `toast` effect) and `core.appearance-flow`
(theme/mode/contrast switching with optimistic apply + persist). The engine is `createMachine` /
`InteractionMachine` (`substrates/interaction/`).

## The four declaration-default surfaces — one `CORE_DECLARED`

There is **one** built-in default declaration, `CORE_DECLARED` (`themes/builtinPresentations.ts`),
boot-applied in `main.jsx` so the real surfaces render declaration-default. A single artifact spans every
default-declared region plus the behaviour tier:

| Region id | Owning surface |
|---|---|
| `core.settings.interface` | `views/SettingsSurface.ts` |
| `core.library.cards` | `views/LibrarySurface.ts` |
| `core.help.reference` | `views/HelpSurface.ts` |
| `core.health.status` | `views/HealthSurface.ts` |
| `core.health.stats` | `views/HealthSurface.ts` |

plus the interaction tier (`core.confirm-ceremony`, `core.appearance-flow`).

The **§7 residue stays team-owned by design**: the production search interactions (Search → tempdoc 570)
and the agent surfaces (Brain/UnifiedChat → tempdoc 565) are *not* forced through the engine — their
bespoke interaction (multi-select, token-streaming) would regress if declared. The honest cut is "users
author what a surface *shows* and *where*; the team owns novel imperative *behaviour*."

## Co-projected accessibility facets

The invariants a hostile or careless author could otherwise corrupt are **co-projected**, not authorable:

- **liveness** — a `liveness` facet mounts `LivenessReadout`, which derives its tone from the
  `aiStateStore` status tier (a semantic token), never an author colour.
- **overflow** — an `overflow` facet composes the `OverflowController` adaptive primitive; the author
  cannot naked-clip content.
- **operability / landmark / contrast** — enforced by the conformance gate (contrast floor, required-region
  presence/visibility, perf budget) and the standing presentation a11y gates (`check-controls-a11y`,
  `check-a11y-closure`, `check-layout-purity`, the 558 contrast co-projection).

## The conformance gate (apply-time)

`themes/conformanceGate.ts` `certifyPresentation(candidate)` certifies a declaration before apply: hard
validation (the closed-vocabulary unrepresentability checks), the statically-checkable contrast floor on
literal token pairs, a per-body perf budget, and required-region presence/visibility. On failure it
quarantines the offending surface (or layout) to the default — degrade-never-fail. The full runtime
axe/contrast oracle over the applied DOM is the final live verification, not this static gate.

## The anti-drift gate — `check-declared-surfaces`

569's thesis is "the prevention is the register/gate whose coverage projects from the authority's
catalog." `scripts/ci/check-declared-surfaces.mjs` + `governance/declared-surfaces.v1.json` keep the
inversion *inverted* by **positive coverage**:

- **(a)** every declared region's surface mounts the engine — imports `activeBodyFor`, references its
  region constant, mounts `<jf-declared-surface>`. A surface reverted to hand-Lit drops the mount and
  fails.
- **(b)** every declared `x-ui-renderer` hint resolves to **exactly one** registered renderer (no fork).

It deliberately does **not** scan for "a second hand-rolled authority co-existing alongside the mount" —
the degrade-never-fail fallback is syntactically indistinguishable from a fork, so the teeth are positive
(rip out the mount and the gate bites). Honest ceiling: an import-invisible re-model slips — early-warning
register, the accepted norm for every presentation gate (per 565 §12.10). A new declaration-default
region/renderer is a discovery step (add the row + route it through the engine). The runtime complement is
the `CORE_DECLARED` contract test (`themes/coreDeclaredContract.test.ts`): every body region certifies and
every referenced hint resolves.

## See also

- tempdoc 569 (functional-core user-authored presentation) — the design rationale (the 8 Moves,
  the keystone unification, the confidence ledger).
- `docs/how-to/write-a-plugin.md` — how a plugin contributes a surface.
- `00-primitives.md` — the Operation/Resource/Prompt primitives a declaration references.
- `docs/explanation/22-agent-system-architecture.md` — the backend agent Effect union (distinct from the
  frontend presentation Effect vocabulary above).
