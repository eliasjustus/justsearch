---
title: The Frontend Presentation Kernel
type: explanation
status: stable
description: "How the web frontend prevents presentation drift: single presentation authorities (tone, originator, layout, modality), a closed atom vocabulary, runtime-modality primitives, and catalog-projected gates — the Collapse > Generate > Gate prevention ladder that makes a re-authored badge/modal/transient unrepresentable rather than merely discouraged."
---

# 27 — The Frontend Presentation Kernel

The web frontend (`modules/ui-web`, the `shell-v0` tree) is built so that **a
given visual or behavioural concept has exactly one authority**, and a second,
drifting copy is *hard or impossible to write* — not merely discouraged by
review. This is the answer to the recurring **presentation-drift** failure
class: the same idea (a status colour, a badge, a modal's scroll-lock, a
single-open menu) re-authored at N sites that then diverge (tempdoc 553's
representation-drift class, hardened across 557 / 559 / 565 / 574).

This doc is the canonical map of that kernel. The design history lives in the
tempdocs; this is the as-built reference.

## The prevention ladder

Every presentation concern is pushed as far **up** this ladder as the substrate
allows. The rung determines how a drifting copy is prevented:

1. **Collapse / Unrepresentable** — the broken state cannot be expressed. A
   modal that doesn't lock background scroll, a transient that doesn't arbitrate
   single-open: *unwritable* for an adopter, because the only way to open one is
   through a primitive that bundles the whole contract.
2. **Generate** — the artifact is generated from one source, so a hand-authored
   fork is overwritten / never exists (e.g. the wire-schema types, the token
   names).
3. **Gate** — a CI check fails the build on a fork. **The gate is the floor, not
   the mechanism**: it catches what Collapse/Generate couldn't, and for the CSS
   tiers (below) it is the realistic ceiling.

A gate is *self-covering* when its coverage **projects from a catalog** (557
§5.2): adding a row to the catalog auto-extends what the gate enforces, so the
gate can't silently fall behind the code.

## The four moves (the substrate, bottom-up)

Tempdoc 574 Part II framed the kernel as four moves:

- **Move 1 — delivery substrate.** Every component extends **`JfElement`**
  (`shell-v0/primitives/JfElement.ts`), not raw `LitElement`. `JfElement` adopts
  the shared **`ambientStyles`** into every shadow root, so global presentation
  (scrollbars, selection, placeholder, spin keyframes) reaches every component
  without per-component re-declaration.
- **Move 2 — ambient authority.** Class-B ambient facets
  (`::-webkit-scrollbar`, `::selection`, `::placeholder`, `@keyframes spin`)
  live *only* in `ambientStyles`. Enforced by `check-ambient-purity.mjs`,
  projecting the ban-list + positive coverage from
  `governance/ambient-facets.v1.json`.
- **Move 3 — atom authority.** A closed vocabulary of `jf-*` atoms is the only
  way to render the concepts they own (below). Enforced by the shrinking-baseline
  `check-atom-fork-ratchet.mjs`, projecting its detection vocabulary +
  authority-exclusions from `governance/atom-facets.v1.json`.
- **Move 4 — runtime-modality authority.** Modal and transient *behaviour* (the
  rung where Collapse is achievable) is bundled into ReactiveController
  primitives, so a half-wired modal / unarbitrated transient is unrepresentable
  for adopters.

## The authorities (single sources of truth)

| Concept | Authority | Consumers project from it |
|---|---|---|
| lifecycle status → tone → accent | `utils/statusTone.ts` (`statusToTone` / `toneAccent` / `toneAccentSoft` / `statusGlyph`) | every status badge / dot / run-step |
| originator (who acted: agent / user / system) → accent | `utils/originatorTone.ts` (`toOriginator` / `originatorAccent` / `originatorAccentSoft`) | the `jf-status-badge` `origin` axis |
| size / depth / motion | the design tokens (`styles/tokens.css`: `--font-size-*`, `--z-overlay-*`, `--duration-*` + `--ease-*`) | enforced by `check-style-literal-ratchet.mjs` (no raw literals) |
| run rendering | the run-renderers register (`governance/run-renderers.v1.json`): one tool-call primitive, one run projection, one answer renderer, one grounding-semantics + step-presentation authority, the `toneSites` allowlist |
| a thing's displayed NAME / VALUE (the Display authority) | `display/present.ts` (the NAME half — every label is a projection of one declaration) **and** `display/facts.ts` `projectFact` (the VALUE half — tempdoc 594) | a label projects via `present()`; a chip that asserts a runtime/build FACT (dimension / accelerator / precision / capability) projects via `projectFact` from the SSOT catalog or `aiStateStore`, so a baked literal ("Embeddings 384-d", "GPU cuda12") is unrepresentable. Gate: `check-chip-fact-authority.mjs` (`governance/chip-facts.v1.json`) |
| live-connection display (is the backend reachable vs is the live-update channel attached) (tempdoc 604, refined by 649) | REACHABILITY is owned by a **positive-contact liveness authority** (`state/originContact.ts` `isOriginReachable`, registered as the `connection` domain in `inflight-liveness-projections.v1.json`): the backend is reachable iff there was POSITIVE CONTACT of **any** channel within the watchdog window — a `/api/status`/`/api/inference/status` poll success **OR** any SSE frame/heartbeat (`EnvelopeStream.handleFrame`/`handleOpen` bump it). This is kept **SEPARATE from data-FRESHNESS** (the poll-specific 15s staleness in `computeStaleness`). It **refines** 604: reachability is still NOT keyed off any SSE channel's up/down (that signal is wedge-prone), but heartbeat **freshness** now counts as positive contact — so under poll starvation (the always-on streams exhaust the browser's 6-per-host pool so the cheap polls hang, tempdoc 649) a stale poll while the streams keep heartbeating reads as the calm **"Catching up…"** (`updating` verdict), not the false "Reconnecting…"; only the absence of contact on **every** channel (window = `STREAM_WATCHDOG_STALE_MS`, the same generated constant the stream watchdog uses) raises the alarm. `aiStateStore` (`connection.reachable`) feeds the ONE 595 verdict. The status-bar pill (`StatusDeck`) and the liveness dot (`LivenessReadout`) take their TONE from the matched `aiStateStore.statusTone` — the verdict-tone sibling of `statusLabel` (both project from the one `verdictTone`) — NOT a parallel `statusTier`→tone map (tempdoc 649; `statusTier` no longer drives connection-status colour). So the calm `updating` "Catching up…" reads **`info`** (tint), the lost-contact `channel-stale` "Reconnecting…" reads **`warning`** (amber, its own 649 severity — distinct from the calm catch-up), and `unreachable` "Backend disconnected" reads **`error`** (red): ONE calm→amber→red ramp rendered identically on the status pill, the liveness dot, and the Health badge. An SSE channel (`EnvelopeStream`) is ALSO a live-UPDATE channel: when it drops it self-heals in place (FE-owned reconnect on a 5xx `readyState=CLOSED` + a heartbeat-absence watchdog, window generated by `gen-stream-liveness-constants.mjs`), and its own state is a SECONDARY, debounced "live updates paused — reconnecting…" hint | the PRIMARY System-Health surface (`HealthSurface`) already derives its connection status (`apiStatus`) from the reachability authority (`subscribeAiState` → `connection.reachable`), so a wedged SSE channel already does not read as "backend down" there; the same three-state pattern (`info` connected/grace · `warning` "paused", backend reachable · `error` unreachable) is realized in `HealthLitView.renderConnBadge` (the secondary `jf-health-view` Resource/demo component). The agent-run stream applies the same liveness law via `LivenessWatchdog` (its backend heartbeat is the positive signal) so a dropped run reaches a terminal instead of hanging. The substrate self-heal (`EnvelopeStream`) is the load-bearing fix — every SSE consumer inherits it |
| affordance availability → reason + remedy (tempdoc 596) | `state/availability.ts` — typed `Availability = available \| blocked \| unavailable{reason, transient?, remedy?} \| degraded{caveat, remedy?}` via TWO tiers: the capability-agnostic TYPE + `unavailableBecause(reason, transient?)` (any local state gate — selection / draft / refresh-in-flight, 6+ non-AI sites) and the AI-store `projectAvailability` (the `documents`/`extract`/`agent` affordances). Wording + remedy come from the ONE reason vocabulary `state/readinessNotice.ts` (`reasonFor` / `CAUSE_ROWS`, shared with the degradation banner + the 595 verdict); the `degraded` kind CONSUMES the one 595 verdict (`s.verdict.kind==='degraded'`), never re-deriving it (the `verdict-derivation` gate) | `jf-control` renders `unavailable` as `aria-disabled` + a reachable WCAG-1.4.13 reason tooltip (Popover API: dismissable + hoverable + collision-safe, short hover open-delay) with a non-silent activation that points at the remedy (one toast channel), `degraded` as OPERABLE-with-a-caveat on the same tooltip, and queues a TRANSIENT-unavailable activation to auto-run when ready; `jf-button` forwards `.availability` to its composed control. The capability map (`jf-capability-map`, in Health) projects every affordance's row from the same authority; `state/availabilityTelemetry.ts` records blocked attempts ("what blocks users"). So an unavailable affordance can't hide its "why" or its fix (the operability authority's availability half; the `controls-a11y` `disabled`+`title` ratchet is the backstop, baseline now 0) |
| affordance acknowledgement → in-flight busy (tempdoc 608) | the operability authority's **acknowledgement** half (the complement of the availability half above): `jf-control` activation is promise-aware — a `.onActivate` returning a thenable lights an **internal** `busy` overlay until it settles. `busy` is NOT a host-supplied `availability` kind; it is computed from the tracked activation promise and composed *over* the `availability` steady state, and it sits OUTSIDE the `transient` queue path so a non-idempotent command is never auto-replayed on settle | `jf-control` renders `busy` as a loader-2 spinner + **`aria-disabled`** (focus-preserving, NOT native `disabled` — same a11y pattern as the `unavailable` half) + `aria-busy`, plus a polite `role="status"` visually-hidden region (WCAG 2.1 SC 4.1.3). The overlay is **spin-delay-gated** (`showDelayMs`≈200 before show + `minVisibleMs`≈500 hold) so a sub-threshold command shows/announces nothing (no flash). `jf-button` forwards a promise-returning `.onActivate` to its composed control, so acknowledgement is opt-out (a surface gets it by returning the command promise it already holds; a `void` return is unchanged). A separate **in-flight** flag (not the visual `busy`) arms the re-entrancy guard, so a second activation — even during the pre-spinner delay window — can't re-fire. First adopter: LibrarySurface Add Folder; `ActionButton`/`OpButton.pending` is a parallel in-flight impl pending convergence onto `jf-control` |

The tone/originator authorities own the raw `var(--accent-*)` tokens for their
axis and **nowhere else** — a call site receives a token *reference*, never a
bare colour, so a badge can't carry an off-palette colour.

## The atom vocabulary

Each atom is marked `@atom` (which auto-exempts it from the fork ratchet) and is
listed in `governance/atom-facets.v1.json` against the fork-class it replaces:

- **`jf-status-badge`** — the tone-tinted pill. Two colour axes: a lifecycle
  `tone`/`status` (via `statusTone`) **or** an `origin` (via `originatorTone`,
  named `origin` not `role` to avoid the `HTMLElement.role` collision). The
  **neutral** case (status `neutral` / origin `system`) carries a `--border-strong`
  delineating border so it reads as a pill even when its fill is near-backdrop.
- **`jf-status-dot`** — the status-tone dot (the `live` attribute drives the pulse).
- **`jf-error-alert`** — the tone-projected alert box (`role="alert"`, reactive `onDismiss`).
- **`jf-filter-chip`** — the bordered filter-toggle (`active` + a `tone` for the
  active tint; renders a real `<button aria-pressed>` for operability).
- **`jf-button`** — the one button atom; composes `jf-control` for operability,
  projects a solid CTA fill from `tone`.

**AHA cut**: genuinely-distinct chips that are *not* "a badge with a tone" stay
their own components and are listed in `atom-facets.json`'s `ahaDistinct`
(e.g. `ProvenanceChip`, `SourceChipsRenderer` citation,
`DispatchSource` operational provenance). Only unify what shares a reason to change.

## The runtime-modality primitives (Move 4 — by construction)

Composing one of these + calling `open()`/`close()` is the *only* wiring an
overlay needs; the broken state can't be expressed:

- **`ModalityController`** (`primitives/modality.ts`) — the modal background
  contract: reference-counted scroll-lock + focus-restore.
- **`ModalController`** (`primitives/modalController.ts`) — composes
  `ModalityController` + owns the native `<dialog>` so `enter()` + `showModal()`
  fire atomically. Adopters in `governance/modals.v1.json`, enforced by
  `check-modal-arbitration.mjs`; `check-modality-contract.mjs` is the backstop.
- **`TransientController`** (`primitives/transientController.ts`) — single-open
  arbitration (register / closeOthers / unregister over `transientLayerArbiter`)
  + an opt-in outside-click/Escape dismiss (`managesDismiss`, with an optional
  `dismissExclude` predicate for an external opener like a rail badge). Adopters
  (menus, popovers, **and the right-drawer drawers**) in
  `governance/transients.v1.json`, enforced by `check-transient-arbitration.mjs`.

## Adding a new presentation concept

The discovery step **is** the catalog row:

1. Reuse an existing authority/atom/primitive if the concept already has one
   (check `atom-facets.json` / the authorities above first — this is the
   `explore-before-implementing` step for presentation).
2. If it's genuinely new and shared (≥2 sites, one reason to change): add the
   atom/primitive, mark it `@atom` where applicable, and **add its catalog row**
   — the gate's coverage extends automatically.
3. A true one-off that is not the existing concept: add it to the relevant
   `ahaDistinct` / exemption list with a justification (don't over-DRY).

## The honest ceiling

Move 4 (runtime behaviour) reaches **Collapse** — broken modals/transients are
unrepresentable. The atom and ambient tiers (CSS) cannot: a hand-styled badge or
a raw `::selection` can always be *written* in a Lit app, so their realistic top
is the **self-covering gate** (the shrinking ratchet), not unrepresentability.
That is by design, not an unfinished edge (tempdoc 574 §22.G). The gates are the
floor that makes the remaining drift *loud*.

## See also

- `docs/explanation/10-ui-ux-design.md` — the UX/visual design system (zones, theme, accessibility).
- `governance/{atom-facets,ambient-facets,transients,modals,run-renderers}.v1.json` — the catalogs the gates project from.
- Tempdocs 553 (drift class), 557 (presentation authority), 559 (layout/messaging/operability authorities), 565 (run + evidence rendering), 574 (the kernel completion) — the design history.
