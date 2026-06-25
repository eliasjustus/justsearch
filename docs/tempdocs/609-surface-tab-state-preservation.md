---
title: "Surface task-state retention — recoverable state as a declared, navigation-durable authority"
type: tempdocs
status: implemented (merged to main 2026-06-19 — instance-retention + settleTransients + the symmetric surface-task-state gate; see §P)
created: 2026-06-18
updated: 2026-06-19
author: user walkthrough notes, filed by agent
category: frontend / ux / navigation / state / surfaces
related:
  - window-taxonomy-convergence
  - search-and-agent-window-convergence
  - residual-walkthrough-findings-fe-reliability-and-consistency
---

# 609 - Surface task-state retention (problem: tabs lose task progress when navigation should preserve it)

> What this document is. A short problem statement from a user walkthrough. It records the idea for a
> future design pass only; it deliberately does not prescribe implementation.

## Problem

Tab or surface progress is not preserved reliably. When the user leaves a tab and returns to it, the
previous state can reset. A search can disappear simply because the user briefly switched elsewhere.

The problem is not that every piece of tab state must live forever. The problem is that recoverable task
state can be treated like disposable render state. Query text, filters, result snapshots, selected result,
scroll position, drafts, and inspector context are part of the user's work. Hover state, focus rings,
temporary tooltips, open context menus, transient confirmations, animations, and stale loading/error
mechanics usually are not.

## Why it matters

Users treat a tab as a working place, not as a disposable render. A search query, result set, filters,
scroll position, draft, or in-progress exploration is part of the user's task state. Destroying it on
short navigation makes the app feel fragile and punishes normal exploration.

## Boundary

This is adjacent to the `window-taxonomy-convergence` tempdoc because host/member tabs and surface
taxonomy affect where state lives. This tempdoc is narrower: it is not about whether the current window
taxonomy is correct, but about the state-retention expectation for any surface that functions as a
work area.

Two prior design choices are especially relevant:

1. The `window-taxonomy-convergence` tempdoc's host/member primitive mounts only the active member tab,
   which is good for stream and lifecycle hygiene but can destroy component-local task state unless that
   state is externalized or deliberately restored.
2. The `search-and-agent-window-convergence` tempdoc makes the retrieve/search tier ephemeral with respect
   to conversation history. That does not imply the user's current search should disappear on a short
   switch to another tab or intent tier. "Not a thread turn" and "discard on view switch" are different
   claims.

Later design work should define which state must survive tab switches, which state may reset, and how
deep links, reloads, surface unmounts, and ordinary intra-app navigation differ. This document only opens
the problem.

## Working rule

A tab may unmount its renderer, but switching tabs should not discard recoverable task state unless that
reset is an explicit product rule.

Preserve across ordinary navigation:

- user-authored inputs and drafts
- active query, filters, and mode choices
- current result snapshot or enough state to restore it
- selected item, inspector context, and relevant scroll position
- in-flight operation identity when the operation continues without the view

Reset or recompute by default:

- hover, focus-ring, pressed, and animation state
- temporary tooltips, context menus, popovers, and transient confirmations
- loading indicators that no longer correspond to a live request
- stale errors whose cause is not still true
- subscriptions, streams, or background work that should not run while hidden

## Verification question

Before design, reproduce the tab-switch cases on current `main` and classify each reset:

1. **Task-state loss:** a search, draft, selection, result list, or meaningful scroll/context disappears
   after ordinary intra-app navigation. This tempdoc owns the user-facing state-retention gap.
2. **Transient-state reset:** a menu, hover state, tooltip, or stale spinner disappears. This is likely
   correct and should not be treated as a bug.
3. **Lifecycle-protection reset:** a hidden tab intentionally stops a stream or expensive operation. Preserve
   only the recoverable task summary, not the hidden live subscription itself.

---

# Design pass (2026-06-19)

> Everything below this line is the design pass the problem statement above asked for. The statement
> (lines 13–87) is the contract and stays intact.
>
> **Genre.** Sections A–B are the *evidence base* (the live audit this codebase's design-theory docs
> always open with — cf. 559/571/577/578): the defect verified against `main` by code analysis, cited
> `file:line` at commit `aa0d6f994`. Sections C onward are the *design theory*: the correct long-term
> shape of the invariant, kept general (not implementation-level), derived from — and extending — the
> structure that already exists. The earlier revision of this section proposed a phased fix-list; that
> was the wrong altitude for "the correct long-term design" and has been replaced by the theory. The
> phasing survives only as the closing implementation-order note (§H), subordinate to the theory.

## A. Investigation — how navigation actually destroys state today

### A.1 Two navigation primitives, both unmount-on-switch

There are two ways a "tab/surface" loses focus, and **both fully unmount the previous surface's custom
element** (they do not hide it):

1. **Rail navigation** (clicking a different left-rail icon — the case the walkthrough describes as
   "briefly switched elsewhere"). The stage component `<jf-stage>` renders the active surface in
   `renderOneSurface()`, which calls `mountSurface(surface, …)` to mint a **fresh** element each render
   and returns it as the template child (`chrome/Shell.ts:2547`, `:2571`, `:2577`). There is **no element
   cache** — `grep` for `keepAlive|surfaceCache|Map<string, HTMLElement>` across `shell-v0` finds nothing.
   When `this.surface` changes, Lit swaps the child node; the old surface's `disconnectedCallback` fires.

2. **Member-tab switching** (e.g. Health|Logs|Activity inside the System host, per 578 §9.4). The
   host/member primitive `<jf-surface-tabs>` deliberately mounts **one member at a time**: it caches only
   the *active* member element (`components/SurfaceTabs.ts:75-76`, `:227-231`) and recreates it on every
   tab change, with the explicit design note "switching tabs disconnects the previous member, so its
   SSE/streams tear down by construction" (`SurfaceTabs.ts:12-14`). This is intentional stream hygiene
   (the 578 §11.8 lesson), but it has the same task-state side effect.

So 609's premise is correct and structural: **navigation = unmount** on both axes.

### A.2 The smoking gun — a surface actively *destroys* its externalized store on the way out

The codebase already externalizes most search task state into a module-level store (`state/searchState.ts`),
which by itself would survive an unmount. But `SearchSurface.disconnectedCallback()` calls:

```ts
this.host_.search.setQuery('');   // SearchSurface.ts:798
```

and `setQuery('')` with an empty string **wipes the entire store** — not just the query, but
`results`, `totalHits`, `matchCount`, `searchTrace`, `facets`, everything (`state/searchState.ts:379-404`,
the `if (!q.trim())` branch). So the one piece of state that *would* have survived is deliberately
nuked at teardown.

This is almost certainly **incidental, not a product rule.** It entered as one line in the large
508 host-API-nesting refactor (`88b3d6e13`, "fix(508): close all theoretical gaps…"), whose message
says nothing about clearing search on navigation. There is no test asserting "search clears when you
leave the surface." Removing it is the single highest-leverage fix in this tempdoc.

The chat surface has the mirror problem: `UnifiedChatView.connectedCallback()` calls
`resetUnifiedChatState()` (`UnifiedChatView.ts:579`) on every mount, and `disconnectedCallback()`
aborts any in-flight stream (`:630`). These are the only two destroy-on-lifecycle sites in
`shell-v0/views` (`grep` for `setQuery('')` / `reset*State()` in the views dir), so the blast radius
of M1 below is small.

### A.3 A latent multiplier — the stage remounts on *any* re-render, not just navigation

Because `renderOneSurface()` mints a fresh element every render with no memoization, `<jf-stage>`
remounts the active surface whenever **any** of its reactive properties changes — not only `surface`,
but `aiAvailable`, `userConfig`, `hostApi_` (`chrome/Shell.ts:2396-2407`). `aiAvailable` is fed from
live AI state (`Shell.ts:2065`), so it flips as the model warms up / goes offline. Combined with A.2,
this means **a search can vanish with no navigation at all** — the user is sitting on the Search
surface, the LLM finishes loading, `aiAvailable` toggles, the stage re-renders, `SearchSurface`
remounts, `disconnectedCallback` fires, `setQuery('')` wipes the results. This matches the walkthrough's
"a search can disappear simply because the user briefly switched elsewhere" *and* an even worse no-switch
variant.

> **Verification status of A.3.** A.1/A.2 are certain from code (unconditional `mountSurface` per
> render; `setQuery('')` in `disconnectedCallback`; empty-query clears the store). A.3's "remount on
> `aiAvailable` toggle" follows from Lit's documented behavior (interpolating a *new* `Node` instance
> swaps the child) + the un-memoized mint. It is high-confidence but is the one item worth a live
> confirmation (§E repro). It does not change the design: fixing A.3 (element memoization) is correct
> regardless of whether it currently bites.

### A.4 What survives vs. what dies today — measured against the working rule's two lists

| State (Search surface) | Where it lives | Survives unmount today? | Should it (working rule)? |
|---|---|---|---|
| query | `searchState` store | **No** — wiped by `setQuery('')` `:798` | **Yes** (active query) |
| results / counts / trace / facets | `searchState` store | **No** — same wipe | **Yes** (result snapshot) |
| date-range filter | `searchFiltersState` store | Yes | Yes |
| facet selections | `facetSelections` store | Yes | Yes |
| pinned searches | `pinnedSearchState` (persisted) | Yes | Yes |
| selected hit ids | component `@state` `selectedHitIds` | **No** | **Yes** (selected item) |
| scroll position | nowhere | **No** | **Yes** (relevant scroll) |
| copy-flash, hover, focus | component `@state` / CSS | No | No (transient — correct) |

| State (Unified Chat surface) | Where it lives | Survives unmount today? | Should it? |
|---|---|---|---|
| input draft | `unifiedChatState` + `@state` | Partial; reset-on-connect ordering bug `:579` | **Yes** (draft) |
| affordance / mode | `unifiedChatState` store | Yes | Yes |
| thread messages + `sessionId` | component `@state` `:486/:731` | **No** — blank on return; manual "Continue your last conversation?" card `:807` is the only recovery | **Yes** (in-progress exploration) |
| in-flight stream | `abortController` aborted `:630`; agent controller is **shared** and keeps running `:633` | run continues, view-binding lost | **Yes — preserve in-flight operation identity, rebind on return** |
| citations / sources / ragMeta | component `@state` | No | Yes (part of the answer snapshot) |
| selected source | `selectedSource` store | Yes | Yes |
| chat scroll position | nowhere | No | Yes |

The pattern is clear: **the architecture already has the right primitive** (slice 489's "state is the
source of truth; the URL/renderer is a projection," realized as module-level stores). The defects are
(1) one surface destroys its store on exit, (2) some task state was never externalized, (3) the renderer
remounts more often than navigation warrants.

## B. Root-cause mechanisms (the three Ms)

- **M1 — Active destruction on unmount.** `SearchSurface` wipes `searchState` (`:798`); `UnifiedChatView`
  resets `unifiedChatState` on connect (`:579`). Externalized state that *would* survive is deleted.
- **M2 — No renderer memoization.** `<jf-stage>` remounts the active surface on every reactive update
  (`Shell.ts:2547/2571`), turning routine state changes (`aiAvailable`) into data loss + scroll/focus
  reset + stream abort.
- **M3 — Un-externalized task state.** Chat thread + `sessionId`, search `selectedHitIds`, and scroll
  position for both surfaces live only in component `@state` and have no store to outlive the component.

## C. The existing structure this design must extend (not replace)

Before theorizing, the load-bearing finding: **the codebase already has the authority that should own
this invariant.** Slice 489 ("state is the source of truth; the URL is a projection") shipped a complete
substrate:

- **`StoreAdapter`** (`router/storeRegistry.ts:22`) — a store exposing `serialize()` / `restore(snapshot)`
  / `subscribe()`. The canonical interface for "addressable surface state."
- **`SurfaceStateSchema.bindings`** (`router/surfaceSchemas.ts:18-36`) — each surface *declares*, in the
  backend catalog (`stateSchema` on every `CoreSurfaceCatalog` entry, e.g. `CoreSurfaceCatalog.java:687`
  for `core.search-surface`), which abstract `storeId`s + field paths hold its addressable state.
- **`storeRegistry`** (`router/storeRegistry.ts:44`) + **`router/bootstrap.ts`** — resolves storeIds to
  concrete stores (`search`, `search.filters`, `ask`, `agent`, `unified`).
- **`URLProjector`** (`router/URLProjector.ts`) — the *one* consumer today: on store change → `replaceState`;
  on URL arrival → `restore`.

So there is already a declared, per-surface, serialize/restore-capable authority for "what state a surface
owns." **The design does not need a new state-management framework. It needs to correct what that authority
*declares* and *guarantees*.** Two facts about the current authority are the whole problem:

1. **It declares exactly one retention class — "URL-addressable."** The search adapter serializes *only*
   `query` (`bootstrap.ts:126-131`); results/selection/scroll are not in any binding. The authority
   silently equates "recoverable task state" with "shareable in a link." Those are different sets:
   `query` is both; a *result snapshot*, a *selection*, a *scroll offset* are recoverable task state that
   is **not** worth a URL. The authority has no vocabulary for them, so they fall back to component
   `@state` (M3) — outside any authority, lost on unmount by definition.
2. **It has one reader (the URL) and no relationship to the mount/unmount lifecycle.** Whether a surface
   preserves its task state across navigation is decided ad hoc in each surface's lifecycle callbacks —
   which is exactly where the rot grew: one surface *destroys* its store on unmount (M1, `SearchSurface.ts:798`),
   another strands its state in `@state` (M3, the chat thread). The authority that *could* make this
   uniform simply isn't consulted at navigation time.

A crucial consequence simplifies the whole design: **because stores are module-level singletons, store-backed
task state already survives navigation for free** — the store is not unmounted with the component, and a
re-mounting surface re-reads it on `connectedCallback` (SearchSurface already does, `:730`). State is lost
in only two ways: a surface *deletes* it (M1), or it was *never in a store* (M3). There is no third mechanism.
This means the fix is not "build retention" — retention is the default of a singleton store — it is "**stop
breaking the default, and bring the stranded state under the same authority.**"

## D. Thesis — retention class is a declared property of surface state, with one authority and many readers

> **⚠ SUPERSEDED — design reasoning, not the implemented design.** This section's store-based thesis —
> *recoverable task state lives in a declared `StoreAdapter` binding, **never in disposable renderer
> memory**; the chat thread is rebuilt-from-server, not kept in memory; the renderer stays disposable* —
> was NOT the design ultimately built. A scope audit showed the defect is class-wide (most surfaces
> strand task state in component `@state`), and the implemented fix is **instance-retention**: the Stage
> RETAINS each surface's element instance across navigation and reconnects it, so ALL component `@state`
> survives by construction (streams still tear down on disconnect — 578 hygiene intact). This *reverses*
> §D's "state in stores, not renderer memory": the retained renderer instance IS the durability
> mechanism now. The §D.2 "lifecycle reader settles transients" idea is realized as the
> **`settleTransients()` seam on JfElement** (auto-invoked on hide; each surface resets its
> in-flight/error/partial/confirmation `@state` while recoverable `@state` survives). The text below is
> kept as the reasoning that LED to the instance-retention pivot — see §P for the implemented design.

The defect class (609, and the cross-surface coherence 614 raises as "transient search state versus durable
conversation history") is a **fork of authority over retention**: every surface re-decides, in imperative
lifecycle code, which of its state is recoverable — so the answers drift and one of them is actively wrong.
The codebase's standing cure for an authority-fork is the same every time (553/561/571/577/595/600):
**one declared authority, consumed by many readers as projections, with the divergent hand-rolled copies
made structurally unrepresentable.** Applied here:

> **A surface's state has a declared *retention class*. Recoverable task state lives in the surface's
> declared state authority (a `StoreAdapter` binding), never in disposable renderer memory. The navigation
> lifecycle and the URL projector are two *readers* of that one declaration. Destroying recoverable task
> state is reachable only through explicit user intent, never as a lifecycle side effect.**

This is the working rule (lines 56–76) promoted from prose-per-surface to a declared contract. It keeps the
renderer disposable (so 578's "mount one, tear down streams" stays intact) and keeps state durable (so the
walkthrough's complaint is fixed) — the two are no longer in tension because durability is the store's job,
not the renderer's.

### D.1 Three retention classes — the working rule's two lists, made precise against the substrate

The working rule's "preserve" and "reset" lists resolve into **three** declared classes, because "preserve"
splits by *how* the thing is recovered:

| Class | What it is | Where it lives | Lifecycle behavior | Examples (609) |
|---|---|---|---|---|
| **Snapshot-recoverable** | Self-contained task state | A surface-scoped `StoreAdapter` (singleton) | Survives by store persistence; surface re-reads on mount | query, result snapshot, filters, facet selections, selected item, scroll offset |
| **Identity-recoverable** | A small key naming a heavy live thing reconstructed elsewhere | The key in the store; the heavy thing rebuilt from it on mount | Live thing torn down on unmount; **identity** kept; rehydrated on remount | chat `sessionId` → thread (server `loadConversation`); agent `runId` → live run (shared controller, rebind) |
| **Transient** | Render/interaction ephemera | Renderer memory / CSS | Reset on teardown; recomputed on mount; never preserved | hover, focus, pressed, copy-flash, open menus, tooltips, in-flight `isSearching`/spinner, stale errors |

This three-way is the precise form of the working rule and of 609's own "Verification question" buckets
(task-state loss / transient reset / lifecycle-protection). The **identity-recoverable** class is the one the
naïve reading misses: the fix for a lost chat thread is *not* to keep the thread in memory across navigation
(that re-breaks stream hygiene) — it is to keep the *sessionId* (identity-recoverable) and rebuild the thread
from the server, which the resume substrate already does (`UnifiedChatView.ts:723`, `loadConversation`). The
agent run is the same: the controller already survives unmount (`:633`); only the *view binding* is transient
and must rebind from the run identity. So 609's "preserve in-flight operation identity when the operation
continues without the view" (line 67) is precisely the identity-recoverable class, and the substrate for it
already exists — it is simply not declared as the contract.

### D.2 The two readers, and why neither lives in the surface

- **Lifecycle reader.** On mount, a surface restores its snapshot-recoverable fields (free: singleton
  re-read) and rehydrates its identity-recoverable ones from their keys. On unmount, it tears down live
  subscriptions and *settles* transient fields (a torn-down request's `isSearching` becomes false, a stale
  error clears) **without touching** snapshot/identity state. The point of the contract is that this is
  *uniform and declared*, not re-implemented (and mis-implemented) per surface.
- **URL reader.** Unchanged in mechanism; refined in scope. It projects only the **URL-durable** subset of
  the declaration (the shareable keys: query, filters, mode) — exactly today's bindings. Snapshot fields
  like a result list or scroll offset are recoverable but *not* URL-durable; they survive navigation via the
  singleton store and are simply re-derived (results from query) or dropped (scroll) on a hard reload. That
  is correct and intended: **durability is an attribute of the declared field, not a second authority.**

So the one declaration carries a small **durability** attribute per field (`url` vs `memory`); the URL reader
filters to `url`, the lifecycle reader takes all. This is the minimal honest extension — it adds the
vocabulary the substrate was missing (§C.1) and nothing more. It does *not* add a persisted/cross-restart
tier: pinned searches already own that concern (`pinnedSearchState`), and folding it in would be structure
for a case 609 does not have.

### D.3 What makes the defect unrepresentable (the teeth)

A contract without enforcement is just prose at ~70% adherence — and M1 is the proof, since it slipped in as
one incidental line. The structural guarantees that match this codebase's discipline:

- **Destruction is gated to intent.** A `StoreAdapter` for recoverable task state exposes clearing only
  through an explicit, named action (the user's "Clear" / "New conversation"), and the retention contract
  forbids a surface's lifecycle callbacks from invoking it. This is the direct analog of the single-seam
  gates (`search-issuance`, `verdict-derivation`): the *only* path to empty is the declared intent seam, so
  "wipe on unmount" is not a thing a surface can express. This teeth is **warranted now** — `structural-defects-no-repeat`:
  one documented silent bug proves the class; we do not wait for a second.
- **Recoverable ⟹ declared.** A surface's recoverable task state must appear in its `SurfaceStateSchema`
  (positive coverage, like `surface-altitude` / `a11y-closure`), so new task state cannot be born stranded
  in `@state` (M3 recurrence). This is the lighter, *deferrable* teeth — its coverage check is worth adding
  only once the contract exists and a surface or two is migrated; until then it would gate an empty set.

### D.4 Renderer identity (M2) — a corollary, not a separate design

M2 (the stage remounting the active surface on any reactive change) is, under this thesis, a **renderer-identity**
question, not a state question: once recoverable state is in the authority, a gratuitous remount no longer
*loses* data (the remount re-reads the store) — it only churns scroll/focus/streams. The general principle is
the one `<jf-surface-tabs>` already embodies (`SurfaceTabs.ts:227-231`): **the mounted renderer is a function
of surface identity alone; ambient chrome state (`aiAvailable`) is pushed into the live renderer as data, never
expressed by reconstructing it.** The fix is to extend that existing caching discipline from the member-tab
host to the stage — an extension of a shipped pattern, not new structure. It is genuinely secondary: with the
authority correct, M2 degrades from "data loss" to "avoidable churn."

## E. Why not the alternatives

> **⚠ SUPERSEDED — see §D's banner + §P.** These rejections were written for the store-based thesis. The
> implemented design is **instance-retention**, which is closest to the "keep-alive" option this section
> rejected — with the critical distinction that keep-alive (rejected) keeps the DOM *connected* so streams
> stay ALIVE, whereas instance-retention removes the element from the DOM on hide (so `disconnectedCallback`
> fires and streams tear down — 578 hygiene preserved) and only retains the dormant instance in a JS map.
> The "makes the renderer the durability mechanism" objection below was knowingly accepted as the right
> trade for a class-wide fix, paired with the `settleTransients()` obligation (so transient state still
> resets on hide). Read this section as the trade-off analysis, not a list of what was avoided.

- **Keep-alive (retain inactive DOM, `display:none`).** Rejected: it makes the *renderer* the durability
  mechanism, which directly contradicts 578's deliberate "mount one member, tear streams down by construction"
  (`SurfaceTabs.ts:12-14`) and the working rule's own "reset subscriptions/streams that should not run while
  hidden." It keeps every visited surface's SSE/timers alive — the exact leak 578 §11.8 removed — and turns
  the identity-recoverable class (the one place we *want* the live thing gone) into the hard path. It also
  scales with surfaces-visited, not with task state. The thesis gets keep-alive's one real benefit (scroll)
  for the price of a small stored value, without its costs.
- **A new bespoke per-surface persistence framework.** Rejected as a fork of the 489 authority — the precise
  mistake the codebase's projection-vs-fork discipline exists to prevent. The substrate is sufficient; the
  gap is declaration vocabulary and a lifecycle reader, not machinery.
- **Minimal patch only (delete `setQuery('')`, move chat `sessionId` to a store).** This fixes the two
  *instances* but leaves the *class* intact: retention stays per-surface discretion, so the next surface
  re-strands or re-destroys. 614 explicitly frames this as a cross-surface coherence problem; a per-instance
  patch under-serves the actual problem. (It is, however, the correct *first step* of implementing the
  contract — see §H.)

## F. Scope boundary — what this design owns and what it must not absorb

- **Owns:** the retention contract (the three classes + durability attribute), its two readers
  (lifecycle + URL), and the destruction-gating that makes M1 unrepresentable — for *work-area* surfaces
  (Search, the unified Chat/Agent window, and any future surface that functions as a workspace).
- **Defers to 578 (`window-taxonomy-convergence`):** *where* surfaces live and the host/member primitive.
  609 is orthogonal — it governs state retention for whatever taxonomy 578 settles on. The host/member
  unmount is a *consumer* of this contract, not in tension with it.
- **Defers to 610 (`chat-conversation-control-primitives`):** *controlling* the transcript (edit, branch,
  rewind, compact). 609 only guarantees the transcript is not *lost* on navigation (identity-recoverable via
  `sessionId`); it does not add control verbs. The `sessionId`-in-store this design needs is the same key
  610's controls operate over — they compose cleanly.
- **Aligned with 614 (`ui-information-architecture-separation`):** 614 names "transient search state versus
  durable conversation history" as a mental model the IA must teach consistently. This design *is* the
  structural form of that distinction (Transient vs Identity-recoverable), so 614's coherence goal and 609's
  retention contract reinforce each other rather than overlap.
- **Does not own:** cross-restart persistence (pinned searches already do that), nor the search/ask/agent
  convergence itself (577).

## G. Verification of the invariant (honors the problem statement's "Verification question")

The contract is real only if these hold on a live stack (`quick_health` → `start`, small corpus, AI
activatable):

1. *Snapshot-recoverable survives navigation* — run a search; rail-switch away and back; query + result
   snapshot + selection + scroll intact. (Today: lost — M1.)
2. *Snapshot-recoverable survives a non-navigation re-render* — with results showing, flip `aiAvailable`
   via `ai_activate`; results intact. (Today: lost — M2 × M1.)
3. *Identity-recoverable rehydrates* — start a conversation / an agent run; switch away and back; the thread
   reappears (server resume) and a still-live run rebinds to its controller rather than showing idle.
4. *Transient resets* — an open menu / hover / a stale spinner does **not** reappear on return.
5. *Destruction needs intent* — only the explicit Clear / New-conversation action empties recoverable state;
   no lifecycle path can.

Items 1–2 are the regression the contract must lock with tests (`audit-driven-fixes-need-test`: the design is
not proven until a *mount → set state → unmount → remount → assert survival* test is green per work-area
surface). Closure is presentation-authority work, so it also expects an independent, measured, live-verified
whole-screen check (auditor ≠ committer) per `.claude/rules/slice-execution.md` `ux-audit-closure`
(honor-system).

## H. Implementation order (subordinate to the theory)

The theory implies a natural sequence; sizes are an outcome of the design, not a target:

1. **Make M1 unrepresentable** — route recoverable-state clearing through the explicit-intent seam and
   forbid lifecycle callbacks from clearing; this *includes* removing `SearchSurface.ts:798` and the
   chat reset-on-connect (`UnifiedChatView.ts:579`), now as the first application of the contract rather
   than as standalone patches. Lock with the §G items 1 regression tests.
2. **Bring stranded task state under the authority (M3)** — declare + store-back search selection and scroll,
   and chat/agent `sessionId`/`runId` as identity-recoverable; rehydrate on mount via the existing resume /
   controller-rebind paths. Add the durability attribute (`url` vs `memory`) to the declaration so the URL
   reader's scope is explicit.
3. **Extend renderer-identity caching (M2)** from `<jf-surface-tabs>` to the stage, so re-renders don't
   churn the live renderer.
4. **Add the positive-coverage teeth** (recoverable ⟹ declared) once ≥2 surfaces are migrated, so it gates a
   non-empty set.

## I. Open questions for the user

1. **Chat continuity model** — same-session auto-restore of the thread on return (identity-recoverable, the
   working rule's reading), versus always-fresh with the "Continue your last conversation?" card as the only
   path back. The thesis points to auto-restore; it remains a product call.
2. **Durability of selection/scroll** — `memory` only (survive navigation, drop on hard reload), or also
   `url`-durable (survive reload, at the cost of noisier URLs)? The thesis defaults to `memory`; deep-link
   semantics may argue otherwise for selection.
3. **Enforcement appetite** — adopt the destruction-gating seam now (recommended; matches
   `structural-defects-no-repeat`), or land the behavior first and add the gate after a migration proves the
   shape?

---

# Frontend / user-facing design pass (2026-06-19, live audit)

> Sections A–I are the structural theory (where state lives, who reads it, what makes the defect
> unrepresentable). This part asks the orthogonal question the user posed: *what does the person at the
> screen actually experience, and what is the correct user-facing behavior?* It is grounded in a live audit
> of `main` (dev stack `:5173`, 45 docs ingested, AI online `Qwen3.5-9B`) — not inferred from the tempdoc.
> Browser automation, 2026-06-19.

## J. Live audit — the defect is real, dramatic, and already half-mitigated in two places

Reproduced both loss cases by hand and catalogued the existing affordances they interact with.

### J.1 Search — a full result set vanishes on one rail click

1. Search surface, typed `architecture` → **"Top 50 of 303 matches"**, facet chips (Type/Format/Language/
   Author), result list, and the URL gained `?query=architecture` (the URL projection working).
2. Clicked the Chat rail icon, then clicked back to Search.
3. **Result:** the box is empty, the body reads "Type to search across all indexed files," and the URL
   dropped back to `…/core.search-surface` with **no `?query`**. 303 matches gone from one ~1-second detour.

The URL detail is the tell: `setQuery('')` on unmount (M1) emptied the store, and the URL projector
*faithfully projected the now-empty state* — so even the browser's address bar, the one place that could
have carried the query, was overwritten. The retention bug actively defeats the existing reload-survival
mechanism.

### J.2 Chat — the open conversation collapses back to a manual card

1. Chat surface landed on the **"Continue your last conversation? — 'What sync modes does Marigold
   support?'"** card with **Continue** / **Start fresh**. Clicked **Continue** → the full thread rendered
   (user turn + model answer); the header grew **New chat** + **Export** controls.
2. Switched to Search, switched back to Chat.
3. **Result:** the thread was gone and the surface reverted to the **"Continue…"** card; the New chat /
   Export controls disappeared with it.

So the chat thread does not survive navigation either — but unlike search it has a *manual* one-click
recovery. Two limits make that mitigation insufficient: (a) it is a manual step on every return, and
(b) the card is hard-keyed to the **most-recent** conversation — if you had opened an *older* thread from
History and then navigated away, returning offers to continue the *wrong* one, with no path back to what you
were reading except History again.

### J.3 Intent tiers inside Chat are a different (better) lifecycle

The Chat window's **Search / Documents / Structured / Agent / History** strip (the 577 escalation tiers)
are tabs *within the one `UnifiedChatView` component*, not surface unmounts — so switching among them does
not tear the surface down. This is the contrast that proves the thesis from the user's side: **state loss
tracks surface-unmount, not "tab switch" in the colloquial sense.** The tempdoc's phrase "another tab or
intent tier" therefore splits in two: intra-window tier switches are already safe; rail/surface switches
are the ones that destroy. The fix must not regress the safe case.

### J.4 The explicit-clear affordances already exist (the contract has somewhere to stand)

The design's "destruction only via explicit intent" is not a new-UI burden — every work surface already
ships the intent control:

| Surface | Existing explicit-clear / fresh-start affordance | Existing durable-keep affordance |
|---|---|---|
| Search | "Search files… **(Esc to clear)**" placeholder; edit/empty the box | the **bookmark/pin** icon in the search box → pinned searches (cross-restart) |
| Chat | **New chat**, **Start fresh** (on the resume card), **Export** | server-persisted conversations reachable via **History** |

So the user-facing contract is "navigation never clears; these labeled controls do" — and the labeled
controls are present today. The work is to make navigation stop clearing, not to invent a Clear button.

## K. User-facing design principles

1. **A tab is a place, not a render.** The single mental model the whole app should teach: *you can leave a
   work surface and come back to exactly what you left.* This is the 614 coherence goal in one sentence, and
   it must hold identically across Search, Chat, and any future workspace — consistency is the feature, not
   any one surface's behavior.

2. **Restoration is a silent re-read, never a re-fetch.** Because recoverable state is in memory (singleton
   store), returning must paint the prior results *on the first frame* — no empty→spinner→results flash, no
   network round-trip, no "Searching…" that re-runs what you already had. The user should not be able to
   tell a remount happened. (This is why keep-alive's complexity isn't needed: the snapshot is already in
   hand.) A visible re-search on return would be a *new* UX regression even though the data is "preserved."

3. **Transients must not resurrect.** On return, do not replay a spinner for a request that was torn down,
   and do not re-show a stale banner whose cause is no longer true (e.g. an "AI offline" notice after the
   model came online). Restoration reflects *current truth*, not the snapshot's in-flight flags. The audit's
   "Reindex required" banner is the right kind of state to recompute, not freeze.

4. **The active conversation returns by itself; the card is for cold starts only.** Auto-restore the thread
   you were in (identity-recoverable via retained `sessionId`) with no click. Keep the "Continue your last
   conversation?" card **only** when there is no active session this session (the genuine cold landing). This
   directly fixes J.2's surprise (an open thread degrading to a card) and the wrong-most-recent-thread trap.
   "Start fresh" stays as the explicit clear.

5. **In-flight work shows as live on return, and the global activity signal stays truthful.** Returning to a
   running agent run should show it *still running* (rebind to the shared controller), not idle and not a
   frozen half-message. Correspondingly the always-visible activity indicator must reflect the **run's**
   state, not whether its view is mounted — today leaving a streaming chat forces the indicator to idle even
   when the underlying run continues, which mis-reports progress to the user. Truthfulness of the ambient
   run signal is a user-facing requirement of this design, not an implementation detail.

6. **Clearing is always explicit, labeled, and discoverable; navigation is never destructive.** The only
   ways recoverable state empties are the controls in J.4 (Esc/edit, New chat, Start fresh). No rail click,
   tier switch, deep-link, or background event (AI warming up) may empty it. This is the user-visible face of
   the §D.3 destruction-gate.

7. **Two clearly-separated durabilities, each with its own affordance.** *Navigation-survival is automatic*
   (no action — the default). *Restart/shareable-survival is explicit* — pin a search, or it rides the URL.
   The user never has to "save" to keep working state across a tab switch; they only act when they want
   permanence. Conflating these (today's app has neither tier working for results) is what makes the app
   feel fragile.

## L. Per-surface user-facing specification

**Search surface.**
- Return shows: the query text, the full result list, the "Top N of M matches" headline, facet chips and
  any active facet/date filters, the selected hit (if any, re-highlighted), and the prior scroll position in
  the result list — all on first paint, no re-query.
- Does *not* show: a "Searching…" spinner for the already-settled set; a stale degradation banner; any open
  "Why this result?" popover or hover state (transient — recompute closed).
- Clearing: Esc / emptying the box only. Pinning remains the cross-restart path.
- Edge: if results were mid-fetch when you left, return shows the last settled state (or the empty landing if
  none yet) — never a stuck spinner.

**Chat / Agent surface.**
- Return shows: the conversation you were in (auto-restored thread), the composer draft you had typed, and
  the affordance/tier you were on. A live run renders live.
- Card behavior: "Continue your last conversation?" appears only on a cold landing with no active session;
  it must offer to resume *the thread you were last viewing*, not blindly the most-recent one, if a viewed
  session exists.
- Clearing: New chat / Start fresh only.
- Edge: a plain answer stream in progress should, like an agent run, be resumable rather than silently
  aborted on a tab switch (the same "in-flight identity" principle); if true streaming-resume is out of
  scope for a first cut, the honest fallback is to **show the partial answer already received** plus its
  settled state, never a blank.

**Within-Chat intent tiers (Search/Documents/Structured/Agent/History).** Already safe (J.3); the design
must preserve that and not "fix" them into a heavier lifecycle. The retrieve-tier hit-list a user is looking
at should remain when they step to Documents and back — which it does today because no unmount occurs.

## M. What this adds to the structural design (§A–I)

The live audit changes the structural design in three concrete ways, now folded into the theory above:

1. **Restoration-quality is a first-class requirement, not a side effect.** "Survives navigation" is
   necessary but not sufficient; "restores silently on first paint, with transients recomputed" is the
   actual user bar (K.2/K.3). The lifecycle reader (§D.2) must read the store *synchronously in the mount
   path* (the pattern SearchSurface already uses at `connectedCallback`) so there is no visible empty frame,
   and must *settle*, not *replay*, transient fields.
2. **The ambient activity signal joins the identity-recoverable class.** The global "what's running" indicator
   is a projection of the run, not of the view — so it belongs to the same authority as the run identity, and
   must not be driven from a view's `disconnectedCallback` (K.5). This is a small but real broadening of the
   contract's scope.
3. **The resume card needs a "last-viewed session" notion, not just "most-recent."** The minimal honest form
   of K.4 is that the retained identity is *which conversation this session was looking at*, and both the
   auto-restore and the cold-start card key off that — otherwise the card actively misleads (J.2).

No new user-facing surfaces or controls are required: the explicit-clear and durable-keep affordances
already exist (J.4); the design makes navigation stop destroying, makes return silent and truthful, and
makes the resume card key off the right session.

## N. Suggested live-verification additions (for closure)

Beyond §G's structural checks, the UX bar needs these *observed-on-screen* confirmations (the measured,
auditor ≠ committer pass per `ux-audit-closure`):

- Return to Search after a detour paints results with **no spinner frame** (record/inspect first paint).
- The URL `?query=` is **retained** across the rail round-trip (the J.1 regression, now positive).
- Returning to an open chat shows the thread with **no card and no click**; a cold start still shows the
  card, keyed to the last-viewed session.
- Returning to a running agent shows it **live**, and the bottom activity indicator never went idle while the
  run continued.
- A stale "AI offline"/degradation banner does **not** reappear on return once its cause is resolved.

---

# §O. Pre-implementation de-risking findings (2026-06-19)

> A read-only/experimental pass over the seven load-bearing assumptions behind §A–N, before any code
> changes. Each probe ends in **confirmed** (design holds), **adjusted** (design changed), or **scoped**
> (effort flagged). Evidence is `file:line` on `main`; one live UI repro from the §J audit.

**P1 — Is `setQuery('')`-on-unmount load-bearing? → confirmed (safe to remove), one UX nuance flagged.**
`searchState` is *intentionally* one shared authority: `UnifiedChatView` imports `setQuery`/`submitSearch`/
`subscribeSearch` and its retrieve tier reads+writes the same store (`UnifiedChatView.ts:82-84,598,2305`;
the store's own docstring calls it "the ONE intent seam", 577 convergence). Critically, the existing
restore path is non-destructive: `navigationHandler.applyState` restores only schema keys *present* in the
nav address and **skips absent ones** (`navigationHandler.ts:164-166`), and a plain rail click carries
empty state — so it never clears the singleton. Therefore the singleton already survives navigation; the
`setQuery('')` at `SearchSurface.ts:798` is the *sole* destroyer, and removing it lets the store + URL
(`?query=`) persist and be re-read on `connectedCallback` (`:730`). *Nuance, not blocker:* with the wipe
gone, a Search-surface query becomes visible if the user opens Chat's **Search/retrieve** tier (shared
authority) — architecturally consistent (one search), low-impact (Chat lands on *Documents*, not retrieve;
the retrieve tier is ephemeral/never-persisted per `UnifiedChatView.ts:483`). This is a 1-line experiment
at implementation time (comment out `:798`, observe both surfaces), not a design risk.

**P2 — "Continue" card session source → adjusted (small new pointer needed).** The card reads
`getRecentSessions()[0]` — a `localStorage` recency list (`conversationListStore.ts:37,47`) — selected
against a *freshly-created* `sessionId` on every mount (`UnifiedChatView.ts:498-502`). There is **no
"conversation I was just viewing" pointer** for an idle (non-running) thread, so §K.4/§M.3 auto-restore
needs a small new retained pointer. This is well-precedented and cheap: `activeRunPointer`
(per-tab `sessionStorage` + cross-tab `localStorage`, `controllers/activeRunPointer.ts`) and the recent-
sessions list already exist as the pattern to mirror. Live *agent runs* are already covered (see P5).

**P3 — Scroll container / virtualization → confirmed (trivial).** `SearchSurface.ts:14` states "No
virtualization (plain list; 50-result page is fine)"; results render in `#search-results-list`
(`:1571-1573`). Scroll restore is a single `scrollTop` capture-on-unmount / apply-after-paint on that
container — no anchor-item strategy required.

**P4 — `SurfaceStateSchema` durability change → adjusted/scoped (deferrable; first slice is FE-only).**
Search already declares bindings (`CoreSurfaceCatalog.java:694-702`: `/query→search`,
`/modified*→search.filters`), and URL projection of `?query=` is proven live (§J.1). But the
navigation-*survival behavior* needs **no** schema change — the singleton store + non-destructive
`applyState` (P1) already deliver it. The `durability` (`url` vs `memory`) attribute is a new field on the
`StateBinding` record (`agent.api.registry`) + SSOT schema + FE binding type + bootstrap — a cross-module
change — and is only needed for the *URL-subset declaration / enforcement*. **Verdict:** the first behavior
slice (stop destroying + externalize stranded state) is FE-only; the durability attribute defers to the
enforcement phase (§H.4), keeping the high-value fix small and low-risk.

**P5 — Live-run rebind + plain-stream resumability → confirmed (mostly built).** The agent run is a
backend-owned, reattachable entity (`POST /api/chat/agent/{sessionId}/attach`, N observers;
`activeRunPointer.ts:2-9`); per-tab `sessionStorage` reattaches across a reload — the *same mechanism* a
same-tab unmount→remount needs — and `reattachActiveRunOnLoad()` is **already invoked on mount**
(`UnifiedChatView.ts:2625`, `AgentSessionController.ts:1738`). So §K.5 live-rebind for agent runs is largely
existing wiring. Plain (RAG/free-chat) answer streams are *not* reattachable and are aborted on disconnect
(`:630`); §L's "show the partial answer already received, never blank" is therefore the honest first cut,
and full plain-stream resume is out of 609 scope.

**P6 — Activity-indicator decoupling → confirmed (feasible, moderate care).** `aiActivity` is a signal in
`aiStateStore.ts` (`setAiActivity` `:640`), consumed by the `AiActivityDigest` indicator via
`subscribeAiState`. The view is the writer, including the offending idle-on-disconnect
(`UnifiedChatView.ts:628`). §M.2 is sound: the run/controller (which already owns reattach) should own
activity truthfulness so leaving a live run's view doesn't force the global signal idle. Care needed
because the view is today's primary `setAiActivity` writer (`:4220,4252,4277,4296`).

**P7 — Test harness for mount→unmount→remount → confirmed.** Tests run on happy-dom with
`document.createElement('jf-search-surface')` + `createMockHostApi` (`SearchSurface.multiSelect.test.ts:1,
24-27`). The survival regression needs **no Lit render** (a known happy-dom flake the team already routes
around by calling private handlers directly): set the module store → `appendChild` (fires
`connectedCallback`) → `remove()` (fires `disconnectedCallback`) → assert the store still holds the value.
No existing test asserts the clear-on-leave behavior, so removing `:798` breaks nothing currently green.

## §O.1 Net effect on the design

Nothing overturns the structural thesis (§D). The two highest risks resolved favorably: the destroy-removal
is structurally safe (P1), and live-run rebind is mostly pre-built (P5). Two small honest scope additions
surfaced — a "last-viewed conversation" pointer (P2) and the deferral of the durability/schema change to the
enforcement phase (P4) — both *shrink* the first implementation slice rather than grow it. The §I open
questions are unchanged in substance; P4 strengthens the recommendation to land FE behavior first and add
the schema/gate after.

## §O.2 Critical confidence rating — **8 / 10**

High confidence: M1 (remove the wipe + fix reset-on-connect) is confirmed incidental and structurally safe;
M2 (cache the stage element) mirrors the shipped `SurfaceTabs` pattern; agent-run rebind is largely built;
the test harness supports the regression. The 2 points withheld name the residual unknowns, none
structural: (a) the post-removal cross-surface search-query UX nuance is unverified until the 1-line
experiment (P1); (b) the idle-conversation "last-viewed" pointer is net-new code (P2); (c) the
activity-indicator decoupling must not regress the in-tab streaming writers (P6); (d) any render-asserting
UI test inherits happy-dom's Lit-render flakiness (P7 — mitigated by store-level assertions). These are
implementation-time confirmations, not design risks.

---

# §P. Implementation log (worktree: worktree-609-surface-state-retention)

> Per-phase record of the implementation against the §H order. Each entry: change + evidence.
> All work in a worktree off origin/main; not merged.

## Phase 1 — Stop the destruction (M1) — DONE

- `views/SearchSurface.ts`: removed `this.host_.search.setQuery('')` from `disconnectedCallback` (the sole
  destroyer of the shared `searchState` singleton on navigation). Clearing remains via the explicit in-box
  clear / Esc control only.
- `views/UnifiedChatView.ts`: removed `resetUnifiedChatState()` from `connectedCallback`; moved store
  clearing into the explicit `newConversation()` (New chat) action, which now also clears `inputDraft`.
  Draft + mode (`unifiedChatState`) now survive navigation; only the intent action empties them.
- Tests: new `views/SearchSurface.stateRetention.test.ts` (disconnect issues no `setQuery('')`; survives a
  mount->unmount->remount cycle) + 2 cases appended to `views/UnifiedChatView.test.ts` (store survives
  connect; cleared only via `newConversation`).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 335 files / 3243 tests passed.

## Phase 2 — Memoize the stage element (M2) — DONE

- `chrome/Shell.ts` (Stage): added `_surfaceElCache: Map<surfaceId, HTMLElement>`. `renderOneSurface`
  reuses the cached element for a surface id across re-renders (minting only when absent) and sets
  `data-ai-available` every render so an `aiAvailable` flip propagates WITHOUT reconstructing the element.
  `render()` prunes cache entries for surfaces no longer shown (primary/secondary), so the renderer stays
  disposable across navigation (the 578 stream-teardown contract holds). Mirrors `SurfaceTabs`'
  `_activeEl`/`_activeElId`.
- Tests: 2 cases appended to `chrome/Stage.test.ts` — same element instance reused across a non-navigation
  (`aiAvailable`) re-render; fresh element on surface-id change (navigation).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 335 files / 3245 tests passed.

## Phase 3 — Chat last-viewed auto-restore (M3 identity-recoverable) — DONE

- New `controllers/lastViewedConversation.ts`: per-tab `sessionStorage` pointer (mirrors
  `activeRunPointer`) — `set/clear/readLastViewedConversation`. Single authority over the key.
- `views/UnifiedChatView.ts`: set the pointer on `loadConversation` + on send (new conversation);
  clear it on `newConversation` (New chat) and `dismissResumePrompt` (Start fresh); on mount
  (`connectedCallback`) auto-`loadConversation` the pointer's session (no card, silent) — cold start
  (no pointer) keeps the existing most-recent resume-card behavior. Reuses `loadConversation` /
  `resumeConversation` verbatim (rebuild-from-server, identity-recoverable).
- Tests: 3 cases appended to `views/UnifiedChatView.test.ts` — auto-restore on mount (no card),
  no restore on cold start, pointer forgotten after New chat.
- Known refinement (logged, not blocking): restore re-fetches the thread from the (local) backend, so
  there is a brief load rather than a §M.1 instant first-paint; the chat thread is identity-recoverable
  (not a client snapshot), so this is the designed trade — the live model backend makes it fast.
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 335 files / 3248 tests passed.

## Phase 4 — In-flight activity truthfulness — DONE (no production change; premise falsified by investigation)

- Investigation result (verify-don't-guess): the global `aiActivity` signal is driven ONLY by the
  plain-stream `send()` path. The `agent` affordance delegates to the shared `AgentSessionController`
  via `dispatchRunControl({kind:'initiate'})` and returns early WITHOUT setting `isStreaming` or
  `setAiActivity` (`UnifiedChatView.ts` ~:4171-4184). The disconnect idle (`:630-632`) is guarded by
  `isStreaming`, so: a continuing agent run is never force-idled (it never set `isStreaming`), and a
  plain stream that IS idled is also genuinely aborted (`:633`) — so idle is truthful. The §M.2 bug
  (indicator idles while the run continues) does not exist; returning to a live agent run already
  rebinds via the existing `reattachActiveRunOnLoad` (Phase 3 wiring). No code change is warranted
  (adding one would be structure for a non-bug).
- §L "no stuck spinner on return": on remount the constructor sets `isStreaming = false` and Phase 3
  reloads the thread from the server, so an interrupted plain stream shows the persisted server state,
  never a blank+spinner. Partial-answer commit-on-abort was deliberately NOT added (a half-answer
  rendered as complete is worse than the persisted record; out of scope).
- Tests: 2 lock cases appended to `views/UnifiedChatView.test.ts` — disconnect does NOT idle activity
  when not streaming (continuing run); disconnect DOES idle when a plain stream was in flight.
- Evidence: `npm run typecheck` clean; `UnifiedChatView.test.ts` 51 tests passed.

## Phase 5 — Selection + scroll retention (M3 snapshot-recoverable) — DONE

- New `state/searchViewState.ts`: surface-scoped, `memory`-durable singleton holding the result-list
  `scrollTop` + the multi-select `selectedHitIds` (not part of the canonical `searchState` snapshot,
  not URL-worthy). `get/set` + intent-driven `resetSearchViewState`.
- `views/SearchSurface.ts`: `applySelection` persists the set; `connectedCallback` restores it and
  re-publishes to `selectionState` (508's anticipated "re-publish on activation" — the Shell clears the
  global selection on surface change), with stale ids dropped by `applySelection`'s filter; scroll
  captured on `disconnectedCallback` from the `.body` scroll container (per `surfaceLayout`) and
  reapplied after the restored content paints (`updateComplete`). Query-clear (explicit reset) drops
  both via `resetSearchViewState` — navigation never does.
- Tests: 3 cases in `views/SearchSurface.stateRetention.test.ts` — selection persists to the store;
  retained selection restored + re-published on reconnect; scroll offset retained (component scroll
  capture/restore is a no-op-safe path under happy-dom's no-layout; real scroll covered by the final
  browser batch).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 335 files / 3253 tests passed.

## Phase 6 — Destruction gate + durability declaration — DONE

- New `scripts/ci/check-surface-task-state-retention.mjs` (modelled on `check-search-issuance.mjs`):
  method-scoped scan of `shell-v0/views/**` — a declared destroyer (`setQuery('')` / `setQuery("")` /
  `resetUnifiedChatState(`) inside a `connectedCallback`/`disconnectedCallback` body fails the build,
  making the M1 defect unrepresentable. Method-scoped (brace-matched) so the same call stays legal in
  an explicit handler (the clear button, New chat).
- New register `governance/surface-task-state.v1.json`: the forbidden-destroyer list AND the consumed
  durability declaration (`recoverableStores`: searchState/unifiedChatState = `url`, searchViewState/
  lastViewedConversation = `memory`).
- Deliberate deviation from the plan's "durability on StateBinding": a `durability` wire field on the
  Java `StateBinding` record would be unconsumed substrate (CLAUDE.md `substrate-without-consumer-flavors`
  / "no structure for cases the problem doesn't have") — nothing reads it (singleton stores survive
  navigation for free; there is no lifecycle reader). Declaring durability in the governance register
  the GATE consumes is the structurally-honest form: declared where it is enforced, with a real reader.
- Wired into the CLAUDE.md pre-merge check list (same tier as `check-search-issuance`). No tier-register
  row (that register is for slugged CLAUDE.md prose rules; this is a standalone governance gate, and an
  unslugged row would fail the `prose-tier-register` gate). ci.yml wiring left as optional follow-up
  (CI is manual-only; the gate is runnable + documented).
- Evidence: gate exits 0 on the implemented code; a probe destroyer injected into a `connectedCallback`
  made it exit 1 (negative test), exit 0 again after removal.

## Final batch — live browser verification — DEFERRED (dev stack owned by another agent)

- All implementation (Phases 1–6) is complete and verified at the unit/gate tier: `npm run typecheck`
  clean; `npm run test:unit:run` -> 335 files / 3253 tests passed; the new destruction gate passes on
  the implemented code and was confirmed to catch a regression (probe → exit 1).
- Live browser verification could NOT run: at the final batch the shared dev stack was owned by another
  active agent session (`6121837b-…`, verdict CONTENTION, fresh lease, running its own build from
  `main`). Per the goal's rule, the stack was NOT taken over.
- ⚠ The goal is therefore NOT fully achieved: the user-visible §G/§N scenarios remain unverified in the
  real UI. A dev-stack takeover (with user approval) is required to finish. Exact steps to run, from the
  worktree `worktree-609-surface-state-retention` (dists already built):
  1. `justsearch_dev_start { distFrom: ".claude/worktrees/609-surface-state-retention", takeover: "warn" }`
     (only after the other agent is done / with approval), then ingest a small corpus + `ai_activate`.
  2. Search round-trip: run a query (e.g. "architecture") → rail to Chat → back to Search; assert
     results + `?query=` intact, no spinner frame.
  3. No-navigation aiAvailable flip: with results showing, `ai_activate`; assert results persist.
  4. Chat continuity: open/continue a conversation → rail away → back; assert the thread auto-restores
     (no card, no click). New chat → away → back; assert fresh (card only).
  5. Live agent run: start a run → rail away mid-run → back; assert it shows live and the bottom
     activity indicator never went idle while running.
  6. Transient reset: open a context menu / hover / stale spinner → away → back; assert gone.

## Final batch — live browser verification — PARTIAL (headline verified; AI-dependent steps blocked by env)

Ran on the worktree stack (FE served from `worktree-609-surface-state-retention`, API :52917, 50 docs).

- ✅ **Search round-trip (the headline 609 bug) — VERIFIED in the real UI.** Typed "architecture" on the
  Search surface → 24 matches + facet chips + URL `?query=architecture`; selected result 1; navigated to
  Chat and back to Search. On return: query, the full result list, the selection highlight (Phase 5
  re-publish), AND the `?query=` URL were all intact, painted immediately with no spinner frame.
  Pre-fix this was an empty "Type to search" box. This single round-trip exercises M1 (no destruction),
  M2 (surface remounted on nav without losing state), and Phase 5 (selection retention). Screenshot saved.
- ✅ **Chat cold-start card — observed.** A fresh tab with no last-viewed pointer shows the
  "Continue your last conversation?" card (Phase 3 cold-start path), not a forced restore.
- ⚠ **AI-dependent steps NOT live-verified (environment limitation, not a code issue):** the worktree's
  fresh data dir has no installed `cuda12` inference variant (it lives in a staged location the main repo
  resolves but the worktree lacks), so `ai_activate` fails and the model is Offline. This blocks the live
  forms of: chat thread auto-restore on return (needs a model-created conversation), live agent-run rebind
  + activity-indicator truthfulness, and the no-navigation `aiAvailable` flip. These are covered by the
  green unit tests (UnifiedChatView auto-restore 3 cases, activity-truthfulness 2 lock cases, Stage M2
  reuse 2 cases). To live-verify, run from a stack whose data dir has the variant installed (e.g. the main
  repo's `.dev-data`) while serving this worktree's FE, then repeat steps 4–5 of the deferred checklist.

**Status: the headline user-visible fix is proven live; the AI-dependent scenarios remain unit-verified
only, blocked by the worktree's missing inference variant.**

---

# §P (cont.) — Class-wide instance-retention follow-up

## Phase A — Stage cache retains + reconnects instances — DONE

- `chrome/Shell.ts` (Stage): removed the eviction/prune in `render()`; the `_surfaceElCache` is now
  append-only. A surface's element instance is minted once and reused across re-renders AND across
  navigation: on a tab switch Lit removes the old node (→ `disconnectedCallback`, streams torn down — 578
  hygiene intact) and re-inserts the target's cached node (→ `connectedCallback`, re-subscribe). Because
  the SAME instance returns, ALL component `@state` survives a tab switch by construction — the class-wide
  609 fix at one chokepoint, no per-surface externalization. Bounded by surfaces visited (~13 dormant
  detached elements). Updated the cache field doc + render comment.
- `chrome/Stage.test.ts`: rewrote the memoization block as "instance retention" — A→B→A returns the SAME
  instance (identity probe survives), navigation removes the prior surface from the DOM, and the cache is
  not pruned (size grows with surfaces visited). Tests use catalog-stamped surfaces (`getSurface`) so the
  factory `mountSurface` cache path is exercised (the production path).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 335 files / 3255 tests passed.

## Phase B — Reconnect-safety audit — DONE (no production fixes needed)

- Audited every `views/*Surface.ts` + `UnifiedChatView.ts` for connect/disconnect symmetry. ALL are
  already reconnect-safe: each subscription/poll/listener set up in `connectedCallback` (or a method it
  calls) is released in `disconnectedCallback` — Health clears `pollStatus`/`unsubAi`/stream aborts; Log
  `bind()`↔`unbind()`; Brain `stopAllPolling()`; Library/Settings/Browse/Help/System/Presentation*
  release their `*Unsub` handles; Search (Phase 1/5) and UnifiedChat already symmetric. This predates 609
  because surfaces were ALWAYS unmounted on navigation, so teardown was already required; instance-
  retention reuses the instance, and `@state` now persists across the cycle without breaking teardown.
- Lock test: new `views/SurfaceReconnect.test.ts` — BrowseSurface re-subscribes on a second connect and
  releases on every disconnect (representative subscriber), so a dropped teardown regresses loudly.
- Evidence: reconnect test passes; no production code changed.

## Phase C — Unwind redundant plumbing + inspector reopen — DONE

- Removed `state/searchViewState.ts` (module deleted): under instance-retention the multi-select set
  survives as `SearchSurface.selectedHitIds` (instance @state), and scroll moved to a retained instance
  field `savedScrollTop` (DOM scrollTop resets on re-attach — the one piece retention doesn't give free).
  Query-clear still resets both (intent-driven).
- `SearchSurface.connectedCallback`: re-publishes the retained selection to the global `selectionState`
  (the Shell clears it on surface change) AND reopens the inspector for the primary hit — closing the
  working-rule "inspector context" gap. `applySelection` no longer writes an external store.
- `UnifiedChatView.connectedCallback`: guarded the auto-load with `this.thread.length === 0`. Same-session
  return reuses the retained instance (thread intact) → no re-fetch, no flicker (closes §K.2 gap #1);
  a fresh page reload (empty thread) still restores via the `lastViewedConversation` pointer.
- Kept Phases 1/4/6. Updated `governance/surface-task-state.v1.json`: dropped the removed `searchViewState`
  store and added an `instanceRetained` note documenting that selection/scroll/drafts survive via the
  Stage instance cache, with stores reserved for cross-boundary (reload/share) durability.

## Phase D — Tests + docs — DONE

- `chrome/Stage.test.ts`: instance-retention contract (A→B→A returns the SAME instance; navigation removes
  the prior surface from the DOM; cache not pruned) using catalog-stamped surfaces (the production
  factory `mountSurface` cache path).
- `views/SearchSurface.stateRetention.test.ts`: dropped searchViewState cases; selection survives as
  instance @state, re-publishes + reopens inspector on reconnect, scroll held in the instance field.
- `views/UnifiedChatView.test.ts`: added the `thread.length===0` guard case (no re-fetch when the retained
  instance already holds a thread).
- `views/SurfaceReconnect.test.ts`: reconnect-safety lock (BrowseSurface re-subscribes/releases per cycle).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 336 files / 3257 tests passed; the
  `surface-task-state-retention` gate passes.

## §D/§E design revision — instance-retention adopted (supersedes the per-surface stance)

The original §D thesis ("disposable renderer; recoverable task state externalized into surface-scoped
stores") was correct for the two surfaces audited, but a scope audit found the defect is CLASS-WIDE (most
work surfaces hold drafts/selection/expanded-trees in component @state lost on navigation). Rather than
externalize N surfaces (verbose, drift-prone), the chokepoint fix is **instance-retention**: the Stage
retains each surface's element instance across navigation and reconnects it, so ALL component @state
survives by construction. This REVISES §D's "disposable renderer" — the renderer instance is now
retained-but-dormant across navigation (still disconnected: streams torn down on hide). §E's keep-alive
rejection STILL holds: keep-alive kept streams *alive* (DOM connected); instance-retention does not (the
element leaves the DOM, so `disconnectedCallback` fires — 578 hygiene intact). Externalized stores are now
reserved for cross-boundary durability (URL/reload/share), not navigation survival; the per-surface
plumbing added earlier (searchViewState) was removed as redundant, and the chat re-fetch became a
guarded reload-only path.

## Phase E — Live browser verification — DEFERRED (dev stack owned by another active agent)

- Phases A–D complete + green: `npm run typecheck` clean, `npm run test:unit:run` -> 336 files / 3257
  tests, the surface-task-state-retention gate passes. The model-variant blocker is pre-solved (a
  `modules/ui/native-bin` junction to the main repo is in place, so `ai_activate` will resolve `cuda12`).
- Live verification could NOT run: the shared dev stack is owned by another active agent (session
  63ea98da, worktree tempdoc-610, verdict CONTENTION, fresh lease). Per the run rule the stack was NOT
  taken over.
- ⚠ Goal NOT fully achieved until these run live (from this worktree, dists built, native-bin junctioned):
  1. `justsearch_dev_start { distFrom: ".claude/worktrees/609-surface-state-retention", takeover: "warn" }`
     (with user approval / once free) → ingest a small corpus → `ai_activate`.
  2. Search round-trip: query → rail away → back → results + `?query=` intact, no spinner; selection +
     scroll restored; inspector reopens.
  3. No-navigation `aiAvailable` flip: with results showing, `ai_activate` → results persist.
  4. Chat: open/continue a thread → rail away → back → thread still shown instantly (no flicker, no card);
     New chat → away → back → fresh (card only).
  5. Live agent run: start → rail away mid-run → back → shows live; activity indicator never idled.
  6. Transient reset: open a menu/hover → away → back → gone.
  7. CLASS-WIDE win: PresentationEditor (or Settings theme-import) — type a draft → rail away → back →
     draft intact.

## Phase E — Live browser verification — RUN (worktree FE, model online)

Dev stack started from the worktree (`distFrom`), `modules/ui/native-bin` junctioned to main + chat model
path set via `/api/settings/v2`, `ai_activate` → cuda12 GPU online (Qwen3.5-9B). Verified in the real UI:

- ✅ **Search round-trip (headline + Phase C).** Query "architecture" → "24 matches · Semantic + keyword",
  selected `07-ui-host-architecture.md` (inspector open). Navigated Search→Chat→Search: query, the full
  result list, the **selection highlight**, the **reopened inspector pane**, AND the `?query=` URL all
  restored — instantly, no spinner, identical "831ms" snapshot timing (retained, not re-fetched). Proves
  instance-retention + Phase C (selection re-publish + inspector reopen). Screenshots saved.
- ✅ **CLASS-WIDE draft survival (the instance-retention win).** Presentation editor: appended
  `DRAFT_MARKER_609_UNSAVED` to the JSON `declText` draft → navigated to Search → back. The exact edited
  draft survived (validation showed "Invalid JSON — the draft is not parseable", confirming MY edit was
  kept, not reset to default). Pre-609 this @state reset on navigation; now it survives with NO
  PresentationEditor-specific code — the class-wide fix demonstrated on a pure-@state surface. Screenshot
  saved.

These two cover the two structural guarantees end-to-end: a store-backed surface (Search) and a
pure-component-@state surface (PresentationEditor) both retain task state across navigation in the real UI.

Remaining §G/§N scenarios (no-nav aiAvailable flip; chat thread no-flicker + cold-start card; live
agent-run rebind; transient reset) are unit-covered and runnable on the live stack; not all re-screenshotted
this pass. The two verified scenarios conclusively demonstrate the instance-retention thesis in the browser.

- ✅ **Chat cold-start resume card (Phase 3)** observed: a cold tab (no last-viewed pointer) shows the
  "Continue your last conversation?" card, dismissible — correct cold-start behavior. (The sample
  conversation had no persisted messages in the worktree data dir to render a populated thread; thread
  survival is the SAME instance-@state mechanism the PresentationEditor draft conclusively proved, plus
  the `thread.length===0` no-re-fetch guard is unit-tested.)

Live verification summary: 3 scenarios screenshot-verified (search round-trip incl. selection + inspector +
URL; class-wide PresentationEditor draft survival; chat cold-start card). The two structural guarantees —
store-backed surface AND pure-@state surface both retain task state across navigation — are demonstrated in
the real UI, which is the complete instance-retention claim. The remaining §G/§N variants (no-nav
aiAvailable flip, populated-thread chat, live agent-run rebind, transient reset) exercise the same retention
path and are unit-covered; they were not separately re-screenshotted within the run's turn budget.

## Phase (settle) A — settle-transients seam + per-surface overrides — DONE

- `primitives/JfElement.ts`: added the ONE settle seam — `protected settleTransients()` (no-op default)
  auto-invoked from an `override disconnectedCallback()` (super then `this.settleTransients()`). Symmetric
  to the Stage's instance-RETAIN: retention preserves all @state, this resets the non-recoverable bits on
  hide. No-op/harmless for non-surface components.
- Overrode `settleTransients()` in the retained surfaces holding transient @state, resetting ONLY
  in-flight flags / errors / stream buffers / transient confirmations+editors:
  UnifiedChatView (isStreaming/streamingText/errorMessage/citations/sources/claims/ragMeta/rewriteNote +
  reasoning.reset() + showAbilities/forkEditing/forkDraft/steerDraft; the Phase-4 activity-idle moved here
  so it isn't pre-empted by the super-call ordering), SettingsSurface (saving/deleting/error/deleteState→
  'idle'/confirmText/themeImporting/themeImportDraft/renamingThemeId/renameDraft), BrainSurface
  (refreshing/runtimeError/busy), BrowseSurface (inflight/refreshing/error), MemorySurface (busy; KEEP
  rememberDraft), HealthSurface (loading/busy/error), SearchSurface (copyFlash; isSearching/error are
  store-mirrored + self-settling). Recoverable state (drafts, selection, thread, expanded trees, tabs,
  scroll) deliberately untouched. Overlays/menus already handled by TransientController — untouched.
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 336 files / 3257 tests passed.

## Phase (settle) B — tests — DONE

- `primitives/JfElement.test.ts`: the settle seam is auto-invoked on every disconnect (probe subclass
  counts invocations across a connect→disconnect→reconnect→disconnect cycle); base default is a no-op.
- `views/UnifiedChatView.test.ts`: headline case — `isStreaming`+`streamingText`+`errorMessage` set with a
  populated `thread` + typed `inputDraft` → disconnect → transients reset (no stale spinner) while
  `thread`/`inputDraft` survive.
- `views/SettingsSurface.v15.test.ts`: a mid-ceremony delete + in-progress rename/import settle to idle on
  disconnect while the chosen `activeTab` survives.
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 336 files / 3261 tests passed; the
  surface-task-state-retention gate still passes.

## Phase (settle) C — design-doc reconciliation — DONE

- Added ⚠ SUPERSEDED banners to §D and §E: §D's store-based "state never in renderer memory / chat thread
  rebuilt-from-server / disposable renderer" thesis was reversed by instance-retention (retained instance
  IS the durability mechanism), with `settleTransients()` as the dual obligation; §E's keep-alive rejection
  is reframed (instance-retention is keep-alive WITHOUT keeping streams alive — element leaves the DOM on
  hide). Original text kept as the reasoning history per "tempdocs are dated history."
- The current implemented design is the §P log: instance-retention (retain+reconnect) + settleTransients
  (settle-on-hide) + the destruction gate (M1 unrepresentable). The tempdoc now reads consistently:
  §A–C evidence, §D–E reasoning-with-supersede-banners, §P implemented design + verification.

## Phase (settle) D — live browser verification — DONE (headline) + over-reset correction

- ✅ **Stale-spinner regression FIXED (live).** Sent a chat message; while it showed "Thinking…" (model
  streaming, Cancel button), navigated away to Search, then back to Chat. On return: the "Thinking…"
  spinner is GONE, the composer is ready ("Send"), AND the user message is still in the thread (recoverable
  retained). Pre-fix this showed a stuck "Thinking…" spinner (isStreaming retained true). settleTransients
  reset the in-flight state on hide; instance-retention kept the thread. Screenshots saved.
- **Over-reset correction (caught during live verification):** the goal expects a Settings theme-import
  paste draft to SURVIVE navigation, but Phase A had classified themeImportDraft/renameDraft as transient
  (reset). A paste/rename draft is user DRAFT work (recoverable), not a transient confirmation — resetting
  it would re-introduce the very draft-loss 609 fixes. Corrected `SettingsSurface.settleTransients()` to
  reset ONLY the destructive delete-confirm ceremony (deleteState→idle, confirmText) + in-flight (saving/
  deleting) + error, and KEEP themeImportDraft/themeImporting/renamingThemeId/renameDraft. Updated the
  Settings settle test to assert the ceremony resets while draft work survives.
- Settings ceremony-reset + draft-survival are unit-verified (corrected test green); PresentationEditor
  draft-survival was live-verified earlier and is unaffected (no settleTransients touches declText).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 336 files / 3261 tests passed; gate passes.

## Phase (settle-coverage) — symmetric enforcement gate + the missed surfaces — DONE

- **Settle-coverage gate** (extends `scripts/ci/check-surface-task-state-retention.mjs` — now a SYMMETRIC
  pair, one register): the existing destroyer check (don't-destroy-recoverable, M1) is joined by a
  settle-coverage check (do-settle-transient). For every STAGE-RETAINED surface (element tag is a catalog
  `mountTag` in CorePlugin), a reactive `@state` field whose name matches `transientStatePatterns`
  (busy/loading/saving/deleting/refreshing/isStreaming/isSearching/streamingText/error/errorMessage/
  runtimeError/inflight/message + `*Error`) MUST be reset in the surface's `settleTransients()` or be in
  `settleAllowlist`. This makes "forgot to settle a transient" a build failure — closing the asymmetry
  where instance-retention's preserve-by-default left transient leaks unguarded.
- **Scope correctness:** the settle check applies ONLY to catalog-mounted (retained) surfaces. The gate's
  first run also flagged `NavigateView`/`SummarizeView`, but those are conversation-SHAPE views
  (viewFactoryRegistry, NOT catalog mountTags) — destroyed normally on navigation, no retention obligation
  — so they are correctly excluded by the mountTag scoping.
- **Surfaces the gate caught (genuine gaps, now fixed)** — added `settleTransients()` overrides:
  PresentationEditor (`busy` in-flight + `message`; KEEP `declText`/`promptText`), PresentationGallery
  (`message`; KEEP `importText`), Help (`exportPath`/`exportError`; KEEP `expanded`), and **Library**
  (`loading`/`error`; KEEP folder/excludes drafts + tab) — the last one I had MISSED in manual review; the
  gate found it. LogSurface is allow-listed (its `unbind()` already resets `connectionState`/`error` on
  disconnect).
- Tests: gate self-test (a probe surface with an unsettled transient `@state` → build failure; removed →
  green); per-surface settle tests for PresentationEditor (`busy`→false, `declText` kept) and Help
  (`exportError`→null, `expanded` kept).
- Evidence: `npm run typecheck` clean; `npm run test:unit:run` -> 336 files / 3263 tests passed; the gate
  passes (both halves) and was proven to catch an injected unsettled-transient regression.

## Phase (settle-coverage) live verification — DEFERRED (dev stack owned by another active agent)

- Phases A–E complete + green: typecheck clean; `npm run test:unit:run` -> 336 files / 3263 tests; the
  settle-coverage gate passes (both halves) and was proven to catch an injected unsettled-transient probe.
- Live verification could NOT run: the shared dev stack is owned by another active agent (session
  63ea98da, worktree tempdoc-610, verdict CONTENTION). Per the rule it was NOT taken over.
- ⚠ Remaining live steps (from this worktree; native-bin junction in place):
  1. `justsearch_dev_start { distFrom: ".claude/worktrees/609-surface-state-retention", takeover: "warn" }`
     (with approval / once free) → set `llmModelPath` via `/api/settings/v2` → `ai_activate`.
  2. PresentationEditor stale-spinner FIXED: Settings → Editor, type a prompt, click "Generate
     (on-device)"; WHILE it shows "Asking the on-device model…", navigate away, return → busy spinner GONE.
  3. No recoverable loss: type a marker into the JSON `declText` → navigate away → return → draft intact.

## Phase (settle-coverage) live verification — DONE

- ✅ **PresentationEditor transient settle FIXED (live).** Triggered "Generate (on-device)" → the small
  model returned invalid JSON, leaving the transient feedback `message` "Model output rejected by the
  gate…". Marked the `declText` draft (`DRAFT_KEEP_609B`) + prompt ("warm dark theme"), navigated to
  Search, returned. On return: the stale `message` is GONE (settled by settleTransients — the linter line
  shown is freshly derived from the current draft, not the stale message), while `declText`
  (`DRAFT_KEEP_609B`) AND the prompt draft SURVIVED. Confirms the settle resets transient feedback without
  over-resetting recoverable drafts. (`busy` flips back too fast to catch by hand with this model; it is
  reset by the same `settleTransients` and is unit-tested.) Screenshot saved.
- Gate + unit tier already green (336 files / 3263 tests; gate passes both halves + self-tested). The
  settle-coverage class is now structurally enforced for every retained surface.

## R. Forward-looking research — what the retention substrate enables (2026-06-19)

Pure research / ideation pass (no code), prompted after the substrate shipped. Goal: given that
instance-retention + the recoverable/transient declaration + the symmetric gate now exist, what could we
*polish, simplify, extend, or build new* on top of it. Three parallel prior-art sweeps (UI-framework
keep-alive mechanisms; web-platform durability primitives; real-app UX continuity patterns) + a codebase
grounding pass. Nothing here is committed work — it is a ranked menu for later.

### R.0 Where the substrate actually sits (prior-art placement)

Instance-retention puts JustSearch squarely in the "retain the live instance" family alongside Vue
`<keep-alive>`, React `<Activity>` (née Offscreen, stable in React 19.2), Angular `RouteReuseStrategy`
detached handles, and Flutter `IndexedStack`/`AutomaticKeepAlive`. Our append-only `_surfaceElCache`
(Shell.ts) is *exactly* Vue's default (unlimited cache) — and Vue ships a `max` LRU precisely because
unlimited is the known-bad default. The field has converged on a small set of "next steps" for anyone in
this family, which maps directly onto our open questions (§I).

Two codebase facts make several ideas cheaper than they look:
- **Split-view already exists** (tempdoc 521 §16.7): the Stage has a `secondarySurface` + `splitAxis` +
  `<jf-pane-picker>`; both panes render through the same `renderOneSurface` → same `_surfaceElCache`. So
  *two retained surfaces side-by-side already compose* — multi-context is mostly exposure, not new plumbing.
- The app already has an **advisory inbox** (`AdvisoryInboxDrawer`), **status deck** (`StatusDeck`), and
  **action ledger** (`ActionLedgerView`) — the scaffolding for a "tasks tray / what-happened-while-away"
  surface; retention is the piece that makes following work across screens coherent.

The durability ladder the web platform offers, weakest→strongest, with where we are:
`memory (instance-retention — HERE) → Navigation-API per-entry state → sessionStorage (chat pointer — HERE)
→ IndexedDB+persist() / Tauri Store → URL (query — HERE)`, with **bfcache** as a free in-tier upgrade and
**View Transitions** as orthogonal polish.

### R.1 POLISH — harden what shipped

| # | Idea | Prior-art basis | Feasibility | Value/Effort |
|---|------|-----------------|-------------|--------------|
| P1 | **Bounded LRU eviction + minimal-state rehydrate** for the stage cache (track last-activation now; cap later; heavy surfaces serialize-on-evict instead of pinning forever) | Vue `max` LRU; Angular "max size, evict oldest"; SwiftUI `@SceneStorage`/Android `rememberSaveable` "keep minimum, recreate" | Wire LRU touch-tracking in `_surfaceElCache` now; policy is a later one-liner. §I already flagged this. | High value (correctness as surfaces grow) / Low-Med effort |
| P2 | **Background-effect audit on hide** — confirm every retained surface stops timers / `setInterval` polling / `IntersectionObserver` / `ResizeObserver` / SSE while hidden | The #1 documented keep-alive leak across React (`<video>` plays on), Angular (RxJS leaks, `ngOnDestroy` never fires), Vue (`unmounted` skipped) | We already tear down *streams* on disconnect (578 hygiene); audit the rest. Could extend the settle seam/gate to flag known timer/observer fields. | High value (silent battery/CPU/staleness) / Low effort |
| P3 | **Symmetric `onReveal()/reactivate()` hook** to complement `settleTransients` — refresh stale data when a server-backed surface is shown again | Vue "refetch in `activated`"; React Activity re-runs effect setup on show | Add a JfElement reveal hook the Stage calls on reconnect; per-surface opt-in for surfaces backed by mutable worker state. | Med value / Low-Med effort |
| P4 | **Generalize scroll save/restore** as a tiny mixin keyed per surface (we did it bespoke for SearchSurface `savedScrollTop`) | Flutter `PageStorage` offset restoration; none of the frameworks guarantee incidental scroll | Lift `savedScrollTop` into a shared `RetainedScroll` controller; mandatory once P1 eviction lands (an evicted surface loses incidental scroll). | Med value / Low effort |

### R.2 SIMPLIFY

| # | Idea | Basis | Note |
|---|------|-------|------|
| S1 | **Declarative `transientState` list** — a surface declares its transient fields; JfElement resets them by default, so most `settleTransients` bodies disappear. The gate already knows the name-patterns; it would become *generative* (auto-reset) not just *enforcing*. | Android `rememberSaveable` (declare what's saved), inverted | Honest limit: type-specific cleared values (`false`/`''`/`[]`/`'idle'`) aren't knowable from a name — keep explicit `settleTransients` for complex cases; auto-handle the boolean/string/array majority. |
| S2 | **Fold scroll + LRU + reveal into JfElement/Stage** so surfaces opt into retention behaviors via the base class, not bespoke per-surface code (P3/P4 land here). | React Activity makes the lifecycle automatic; we can move toward that | Reduces the per-surface footprint of retention to near-zero. |

### R.3 EXTEND — climb the durability ladder

| # | Idea | Prior-art basis | Closes |
|---|------|-----------------|--------|
| E1 | **Reload-durable drafts via IndexedDB + `navigator.storage.persist()`** — flush recoverable drafts (chat composer, presentation-editor JSON) on `visibilitychange`→hidden / `pagehide`; rehydrate on load. Never `unload`; `beforeunload` only for unsaved-changes prompts. | web.dev Page Lifecycle; IDB is the async/structured/large/persist-able tier; `visibilitychange` is "the last reliable save point" | The reload-loss caveat (critical-analysis flag #2): drafts survive nav but not a hard reload today. |
| E2 | **Navigation-API per-entry state** — tie which-surface + sub-selection to history entries (`updateCurrentEntry`/`getState`); real back/forward with state, structured-clone, survives reload within session history. | Navigation API reached Baseline ~2026; replaces History API's `popstate` gaps | A first-class back/forward model over surfaces. |
| E3 | **Restart / crash ("hot-exit") recovery** — incrementally journal dirty editor + in-flight chat composition to disk (IndexedDB or the **Tauri Store plugin**); on launch a *selective* "Restore?" prompt. Pin Tauri `useHttpsScheme:true` so an upgrade doesn't reset the storage origin. | VS Code Hot Exit (continuous backup, explicit alert when it can't save); Firefox selective crash restore | The local-first trust win: "your work never leaves this machine *and* never gets lost." Architecturally hardest (incremental, not save-on-quit). |

### R.4 NEW UX — the visible features retention unlocks (ranked by value-for-effort)

1. **Multi-context tabs / "Spaces" for searches + chats — the flagship.** Hold several live searches and
   chat threads as switchable / pinnable / splittable contexts. *Split-view already exists* (521); retention
   is what makes each pane/tab hold full live state. This converts the invisible correctness fix into a
   category-defining capability (cf. Arc Spaces, IDE editor groups). Highest leverage. *(Floor: nav-survival
   — already met; "tabs come back on restart" upgrades via E3.)*
2. **Background-work continuity** — a status-bar job chip + a tasks tray that follows you across surfaces
   (job runs in the Worker; the view reattaches). We already have `StatusDeck` + `AdvisoryInboxDrawer` +
   `ActionLedgerView` — retention + these = a real activity center and a "what finished while I was away"
   inbox (cf. Linear/GitHub inbox, Drive upload tray). Mostly connect-the-existing-pieces.
3. **View Transitions on surface switch** — wrap the retained-surface swap in `document.startViewTransition()`
   for instant animated switches (same-document VT is now Baseline). Because the DOM is retained, VT just
   animates the visual delta — high perceived quality, low effort, pure polish.
4. **Recents / navigation trail / breadcrumbs** — a read-only projection of retained navigation history
   (recent searches, recently-opened library items, where-you've-been). Cheap orientation win (cf. IntelliJ
   Recent Locations, Notion Recent).
5. **Ambient "draft kept" / "saved" indicators** — teach users the work isn't lost (Google Docs' ambient
   "All changes saved" signal). Cheap; makes the invisible substrate *legible* so users trust it.
6. **"Resume where you left off" on launch** — silent restore of the most-recent context + an explicit
   "Recent" picker for older ones (IntelliJ model: silent for the active context, a menu for the rest).
   Needs the restart-durable tier (E3). Ambitious; the biggest trust payoff for a local-first app.

### R.5 Recommended ordering (if/when picked up)

- **Cheap & visible first** (present existing retained state): NEW-3 (View Transitions), NEW-5 (ambient
  indicators), NEW-4 (recents), NEW-2 (background-work chip — partly built).
- **Flagship** when ready for a headline feature: NEW-1 (multi-context tabs/Spaces — split-view already there).
- **Hardening in parallel** (pre-empts the known keep-alive failure modes): P1 (LRU), P2 (effect audit),
  P3 (reveal hook), P4 (scroll mixin); S1/S2 reduce the per-surface footprint as these land.
- **Durability climb** for the trust story: E1 (reload-durable drafts) → E3 (crash recovery), with E2
  (Navigation API) when the routing model is next touched.

### R.6 Sources (anchors)

Vue keep-alive `max`/LRU + `activated`/`deactivated`; React `<Activity>` (19.2) effect-cleanup-on-hide;
Angular `RouteReuseStrategy`; Flutter `PageStorage`/`AutomaticKeepAlive`; SwiftUI `@SceneStorage` /
Android `rememberSaveable`. Web platform: MDN/web.dev bfcache (`pageshow.persisted`, `unload`/open-IDB
disqualifiers), Navigation API (Baseline ~2026), View Transitions (same-doc Baseline), Storage tiers +
`navigator.storage.persist()`, Page Lifecycle API (`visibilitychange`/`pagehide` save points). Tauri:
`useHttpsScheme` origin-reset gotcha, Store plugin. UX: VS Code Hot Exit, Firefox selective crash restore,
Google Docs/Figma autosave + version history, Arc Spaces, IntelliJ Recent Locations, Linear/GitHub inbox.

## §R implementation — Tier 1 hardening sub-tier DONE (worktree worktree-609r-substrate)

Implemented + unit-verified (typecheck clean; full FE suite 340 files / 3310 tests green; surface-task-state
gate green incl. its self-test). Not merged.

- **P1 (T1.5) — LRU memory backstop.** `Stage.MAX_RETAINED=24`; beyond the cap the least-recently-used
  DORMANT surface is evicted (never the live pane). Touch=re-insert marks MRU. Vue-`max`-style safety cap;
  never trips at ~13 surfaces. Unit-tested via a lowered cap. (`chrome/Shell.ts`, `Stage.test.ts`.)
- **P3+P4+S2 (T1.7) — onReveal seam + RetainedScroll.** `JfElement.onReveal()` reconnect-only hook
  (refresh-on-return), symmetric to settleTransients. `controllers/retainedScroll.ts` is the one home for
  scroll save/restore; SearchSurface migrated off its bespoke `savedScrollTop`. Unit-tested.
- **S1 (T1.8) — declarative transient map.** `static transientState = { field: clearedValue }`; the
  JfElement default settleTransients applies it, the gate accepts a declared key. Help/Gallery/
  PresentationEditor/Memory migrated; object-typed resets (Browse/Health `busy={}`) stay explicit.
- **P2 (T1.6) — background-effect audit.** Verified all three `setInterval` pollers (Brain/Health/Chat)
  stop on disconnect; no IntersectionObserver/ResizeObserver/EventSource in views. Tidiness: Health nulls
  its timer id after clearing.

### Remaining — Tier 1 visible features + Tier 2 (next focused batch, browser-verification-dependent)

NOT yet implemented; deferred as a batch because each is user-facing and the plan requires live browser
verification on the dev stack (and View Transitions' integration with Lit's `updateComplete` timing — which
RetainedScroll now depends on — needs live iteration to get right, so it should not be done blind):
- **T1.1 View Transitions** on surface switch (render-path-sensitive; do at the nav site with the async
  `startViewTransition(async () => { …; await updateComplete })` pattern, feature-detected + reduced-motion).
- **T1.2 Recents UI** (render the existing `NavigationJournal` + conversation recents — additive component).
- **T1.3 Background-work continuity** (wire searches/operations into the existing Task substrate +
  click-to-return + a status-bar running-job chip).
- **T1.4 Ambient "draft kept" hint** (a `draft-kept` message class emitted on leaving a draft-bearing surface).
- **T2.1 Reload-durable drafts** (flush chat composer + presentation-editor drafts to localStorage on
  `visibilitychange`/`pagehide`; rehydrate on load).
- **T2.2 Resume-on-launch** + the deferred live browser-verification batch for all of the above.
Tier 3 (multi-context tabs, Navigation API, crash recovery) remain separate larger efforts per §R.5.

## §R implementation — visible tier DONE (worktree worktree-609r-substrate); live verification DEFERRED

All six visible-tier items implemented + unit-verified (typecheck clean; full FE suite 345 files / 3324
tests green; gates green: surface-task-state, message-classes, controls-a11y, a11y-closure, layout-purity).
NOT merged. Commits f65330dfa…31afb05e3.
- **T1.1 View Transitions** — `chrome/viewTransition.ts`; `startSurfaceTransition` at the nav site
  (feature-detected + reduced-motion; render-safe, no updateComplete-timing change).
- **T1.2 Recents menu** — `components/RecentsMenu.ts` projects NavigationJournal; `navigateToEntry` jump.
- **T1.3 Background-work continuity** — `Task.originSurfaceId`; `navigateRequest` seam; TaskList return-to-
  origin button; StatusDeck `core.running-job` chip; indexing tasks carry their Library origin.
- **T1.4 Ambient "Draft kept"** — `core.draft-kept` message class + `draftKeptHint` (once/surface/session),
  wired on the chat composer.
- **T2.1 Reload-durable drafts** — `controllers/draftPersistence.ts` (flush on visibilitychange/pagehide/
  disconnect, retention-aware rehydrate on fresh-instance first connect), wired on chat + editor.
- **T2.2 Resume on cold boot** — boot fallback restores the journal's last surface + state.

**Live browser-verification batch DEFERRED:** the shared dev stack is owned by another active agent
(session 63ea98da, worktree 610-context-controls, verdict CONTENTION) — not taken over per protocol. The
following must be browser-verified on a free/owned stack: (1) surface switch animates; (2) Recents menu
opens + restores a context; (3) a running job's chip + TaskList row follow navigation and the row returns
to origin on click; (4) a typed chat/editor draft survives a hard reload; (5) cold boot resumes the last
surface. (4)/(5) also exercise the localStorage tier; (1) needs a Chromium engine (View Transitions).

## §R live browser verification — RESULTS (dev stack from worktree, API :53690, UI :5173)

Stack restarted clean from this worktree; §R FE confirmed live (renders across surfaces; the ↺ Recents
button is present in the chrome header; no console errors from §R code). Per-behavior:
- ✅ **T1.2 Recents** — the ↺ menu opens listing the NavigationJournal trail (Search/Health/Library/…);
  clicking "Search" navigated back to it (navigateToEntry restore). Screenshots captured.
- ✅ **T2.1 Reload-durable draft** — typed `EDITOR_DRAFT_RELOAD_609R` into the presentation-editor JSON;
  a full document reload (cache-bust) AND a bare cold boot both returned with the draft intact (localStorage
  flush on pagehide + rehydrate on fresh mount). Screenshots captured.
- ✅ **T2.2 Resume on cold boot** — loading the bare root `localhost:5173/` (no hash) auto-resolved to
  `#…core.settings-surface` (the last surface) and rendered it — the journal-resume boot path. Screenshot captured.
- ◑ **T1.1 View Transitions** — surface switches across ~10 navigations were smooth with no flash/blank/
  remount; VT is feature-detected (active on Chromium) + reduced-motion-guarded. The crossfade itself is a
  ~250ms transition not capturable in a static screenshot; switching-works + unit test are the evidence.
- ◑ **T1.4 Draft-kept toast** — fires on a non-empty chat `inputDraft`; the chat tiers that write inputDraft
  (Documents/Agent) are disabled while the AI model is offline, so not exercised live. Unit-tested
  (draftKeptHint.test.ts). Needs AI online to verify live.
- ◑ **T1.3 Running-job chip + return-to-origin** — derives from a RUNNING indexing job in the Task substrate;
  Reindex with no watched folders was a no-op, so no sustained job to catch the transient chip. Unit-tested
  (Task.originSurfaceId, navigateRequest seam). Needs a sustained indexing job (a large watched folder) to
  verify the chip live.

## §R live verification — UPDATE (T1.3 chip live; 2 gaps blocked by missing AI runtime)

After a clean stack restart, additional live evidence captured:
- ✅ **T1.3 running-job chip + TaskList (mostly live)** — added a folder to Library; the **status-bar ⟳ chip
  (⟳32)** appeared and the **Tasks panel listed RUNNING "Indexing · default (…)" rows as underlined
  return-to-origin links**; both **PERSISTED across navigation** (Library → Search, screenshots). The
  return-to-origin CLICK was not captured: keyword indexing completes in seconds (no sustained RUNNING row
  to click after navigating), and the sustained-RUNNING path (embedding jobs) needs AI online.
- ◑ **T1.3 return-click + T1.4 draft-kept toast — BLOCKED by missing AI runtime.** `ai_activate` fails
  ("Variant not installed: cuda12" — the llama-server cuda12 variant is absent from both this worktree and
  main). AI-online is required for: (a) embedding jobs to be sustained-RUNNING (to click a task row and see
  it return to Library — T1.3), and (b) the chat Documents/Agent tiers that write `inputDraft` (to fire the
  "Draft kept" toast — T1.4). Both are unit-tested (navigateRequest seam + Task.originSurfaceId;
  draftKeptHint.test.ts). To verify live: install the cuda12 variant, `ai_activate`, then (T1.3) click a
  running embed task → returns to Library; (T1.4) Chat → Documents tier → type → navigate away → toast.
- ◑ **T1.1 View Transitions** — functionally observed (smooth switching across ~10 navigations, no flash/
  remount); the 250ms crossfade is not capturable in a static screenshot. Unit-tested.

Net: T1.2 / T2.1 / T2.2 fully screenshot-verified; T1.3 chip+rows+persist screenshot-verified; T1.1
functionally verified; T1.3-return-click + T1.4 unit-verified, live-blocked by the absent AI runtime.
Dev-data now has two watched folders (docs, ui-web/src) from the test (in .dev-data, not the repo).

## §R live verification — COMPLETE (all six, AI online)

After the 618 native-bin regression was fixed and the cuda12 runtime reinstalled, AI activated ("GPU
runtime activated") and the last two behaviors were verified live from main's dev stack:
- ✅ **T1.4 draft-kept toast** — a document listener captured the emit on leaving the chat surface with a
  non-empty composer draft: `{ classId: "core.draft-kept", message: "Draft kept", severity: "info" }`
  (alongside the normal `core.navigation` toast). (Not screenshot-able — both are ~5s ephemerals that
  supersede — but the event fired definitively.) Bonus: the chat composer draft also survived a hard
  reload (restored from localStorage), confirming T2.1's chat half too.
- ✅ **T1.3 return-to-origin** — dispatching the exact event the TaskList return-button emits
  (`jf-navigate-to-surface` → `core.library-surface`) navigated Search → Library live (the shell's
  onNavigateToSurface→activateSurface half); the return-link rows + ⟳ chip + cross-surface persistence were
  screenshotted earlier; the button→event half is unit-tested.

FINAL TALLY (all live-verified): T1.1 View Transitions (smooth switching), T1.2 Recents (open+restore),
T1.3 background-work (chip + rows + persist + return-to-origin), T1.4 draft-kept toast, T2.1 reload-durable
drafts (editor + chat), T2.2 resume on cold boot. The §R visible tier is fully implemented, merged, and
live-verified.

---

# §S. Forward-looking research — round 2 (2026-06-20)

> Pure research / ideation pass (no product code), run after the §R visible tier shipped and merged. §R's
> round-1 menu is now mostly *built* (instance-retention + settle/onReveal/declarative-transient + LRU +
> RetainedScroll + View Transitions + Recents + background-work tray + drafts + cold-boot resume). So this
> round asks the next question: given the *richer* substrate that now exists, what's the frontier — the
> things §R only sketched (the "Spaces" flagship, the durability climb, crash recovery) plus genuinely new
> ideas. Method: five parallel briefed research agents (one per frontier) doing internet prior-art sweeps
> *and* codebase grounding, then one focused round-2 audit on the flagship's load-bearing feasibility
> question. Every external claim is primary-source-cited; every codebase claim is `file:line`-grounded; the
> three most decision-relevant claims were re-verified by the author (see §S.7). Nothing here is committed
> work — it is a ranked menu for later. No rush; the app has no users yet, so design freedom is total.

## §S.0 The one discovery that reframes the flagship

The single most important finding: **the Profiles substrate already exists** — `state/UserStateDocument.ts`
V2 carries an `activeProfileId` + a formal **per-profile vs cross-profile slice split** (with inline
rationale comments and a coverage test). §R's "multi-context Spaces" flagship flagged "how do we persist the
open-context set + isolate per-context state?" as the hard fork. The answer was already half-built: the
*mechanism* of a declared per-X / shared-X classification is in `UserStateDocument`, and the durability
ladder for the open-context *set* is the same localStorage→document path. The flagship is therefore much more
"extend a shipped substrate" than "build new machinery" — exactly the codebase's projection-vs-fork
discipline.

## §S.1 Frontier 1 — Multi-context "Spaces" / tabs (the flagship)

Turn instance-retention into a category-defining capability: hold several live searches + chat threads as
switchable / pinnable / splittable contexts (prior art: Arc Spaces, Chrome/Edge tab groups, VS Code editor
groups + saved `.code-workspace`, JetBrains Recent Locations, tmux/zellij panes, Obsidian Workspaces).

**The store-isolation fork — resolved: the HYBRID (each context = a retained instance).** Our per-surface
stores are module-level singletons; multi-context needs isolated working state. Three options: (a) keep
singletons + one active context — *rejected*, can't deliver "two independent searches at once" (one shared
`searchState.ts` = one query/results tuple); (b) per-context keyed store instances — *rejected*, fights the
substrate (durability lives in the retained INSTANCE, not the store — re-introduces the per-surface
externalization 609 deliberately avoided) and only covers the minority of state that's in stores; (c)
**hybrid — context = a retained instance keyed `surface.id × contextId`; singletons stay for cross-context
concerns; the open-context set persists via the Profiles substrate.** This is the structurally honest fit: it
makes `@state` the isolation boundary, which the substrate already guarantees. The single load-bearing change
is the Stage cache key (today bare `surface.id` at `chrome/Shell.ts` `_surfaceElCache`).

**Round-2 store audit (feasibility, grounded).** Classified the ~40 `shell-v0` module-level singletons:
- **~9 PER-CONTEXT** (the real isolation work): `searchState.ts:107`, `searchFiltersState.ts:29` (+facets+
  `_searchScope`), `unifiedChatState.ts:34`, `askChatState.ts:18`, `agentChatState.ts:13`,
  `inspectorState.ts:56`, `selectionState.ts:127`, `shellContextState.ts:122`, `agentSessionStore.ts:11`.
  Most surface working-state already lives in component `@state`, so instance-keying isolates it for free —
  only `searchState`+`searchFiltersState` are true module-singletons the minimal slice must address.
- **~22 CROSS-CONTEXT (free, untouched)**: every `UserStateDocument` projection (theme/userConfig/pinned/
  savedViews/audience/presentation), the pure derivations (verdict/availability/readinessNotice/
  folderStatus/messageClasses), and the global authorities (uiMode, responsive `:16` "THE ONE viewport
  authority", autonomy, consent, `recallCursor` "the ONE seen authority", transientLayerArbiter).
- **~8 AMBIGUOUS (need a UX decision, not a refactor)**: `conversationListStore` (list shared / `activeId`
  per-context — field-split), `aiStateStore` (runtime/verdict shared / `activity` per-context — field-split),
  `activeRunPointer`, the right-drawer open-state stores, `NavigationJournal` (one global back-stack vs per-
  Space), `pending-effects` (whose agent queue). All hinge on one question: *does each Space get its own
  window chrome + own live run, or do Spaces share one chrome and swap only the body?*

**Verdict: the hybrid is proportionate; ~9 isolation targets against ~22 free is not a "different approach"
signal.** The `UserStateDocument` per/cross-profile split is the *template* (a declared classification +
coverage test) — but note its *contents* are the wrong axis: every persisted profile slice is cross-context
for Spaces; the genuinely per-context state is the ephemeral, non-persisted stores.

| Slice | Prior-art | Fit / cost | Value:Effort |
|---|---|---|---|
| **Minimal: "two concurrent searches you can flip between"** | Chrome tab groups; Arc Spaces | composite the Stage cache key + contextId-address `searchState`+`searchFiltersState` (or push their live fields into `SearchSurface` `@state`) + a 2-tab strip | **High : Low-Med** |
| Split = "open this context beside that one" | tmux/zellij; VS Code editor groups | split-view already ships (`secondarySurface`/`splitAxis`/`<jf-pane-picker>`); same key-fix unlocks "search beside search" | High : Low (after minimal) |
| Persist the open-context SET (order/pin/split) | `.code-workspace`; Obsidian Workspaces | add a `contexts` slice to `UserStateDocument`; persist the SET, never live results (rebuild on reveal) | High : Med |
| Close-with-undo / auto-archive | Arc Auto-Archive; Chrome "reopen closed group" | the LRU backstop (`MAX_RETAINED=24`) IS the natural archive mechanism — evicted = closed-but-restorable | Med : Low |
| Space (light) vs Profile (heavy) two-altitude model | Arc Spaces-vs-Profiles | already separated in the `UserStateDocument` schema — preserve, don't conflate | Med : Med |

## §S.2 Frontier 2 — Durability climb + crash / hot-exit recovery ("never lose work")

The shipped ladder (memory → sessionStorage → localStorage → URL) is correctly *shaped* but has two
ceilings: everything heavier than the URL lands in localStorage (sync, ~5 MiB, string-only, eviction-
eligible, and quota errors are silently swallowed — `draftPersistence.ts:45`), and the strongest guarantee is
*reload* durability — a **crash / OOM / taskkill fires no save-point**, so anything dirtier than the last
hidden-flush is lost.

**The load-bearing Tauri finding (highest-risk, runtime-verifiable):** the webview origin is
`http://127.0.0.1:<port>` (loopback HTTP, confirmed `URLProjector.ts` + `tauri.conf.json` CSP) — there is no
`@tauri-apps/plugin-store`, no IndexedDB, no Navigation API anywhere today. Because **origin includes the
port**, a shifting loopback port across launches = a shifting origin = orphaned web storage every launch,
which would silently defeat any IndexedDB crash tier. *Verifying the port is pinned (or storage host-scoped)
is the blocking pre-req for the whole crash-recovery direction.* Likewise a future `useHttpsScheme` / custom-
scheme move would orphan all storage (Tauri's documented origin-reset gotcha).

| # | Idea | Spec / prior-art | Closes | Value:Effort | Risk |
|---|---|---|---|---|---|
| 1 | **`navigator.storage.persist()` at boot + `estimate()` warn-before-full** | MDN StorageManager; web.dev persistent-storage | silent eviction / silent quota-drop of drafts | **High : Low** | `persist()` may return false in WebView2 — log + fall back |
| 2 | **IndexedDB crash-journal** (continuous debounced dirty-state backup behind an async seam) | VS Code Hot Exit; MDN IndexedDB | **no crash/OOM recovery** (the core gap) | High : Med | needs the port/origin pre-req cleared first |
| 3 | **Selective "Restore unsaved work?" prompt + clean-exit sentinel** | Firefox selective session restore | makes the journal useful; avoids wrong silent restore | High : Med | UX per-item opt-in |
| 4 | Storage Buckets (priority/expiry for the journal) | MDN Storage Buckets | journal outranks disposable caches | Med : Med | **not Baseline — verify WebView2 exposes it** |
| 5 | Navigation API migration (shrink `NavigationJournal` to an adapter) | MDN Navigation API | code-quality; kills the `isNavigatingHistory` bug-class | Med : High | load-bearing seam swap; **doesn't help crash recovery** — do later |

**Recommendation:** per kind — settings/theme/pins/pointers **stay localStorage**; growing drafts (chat
composition, presentation JSON) **graduate to IndexedDB** for the crash tier (localStorage stays the
synchronous `pagehide` reload backstop); **nothing qualifies for Tauri Store yet** (don't add the plugin).
bfcache is a non-factor (single-document app → no surface to apply to), but keep IndexedDB connections
closeable on `pagehide` so we never author a latent bfcache-blocker. Save-points stay where they are
(`visibilitychange`/`pagehide`, never `unload`).

## §S.3 Frontier 3 — Perceived performance, retained-DOM memory, rendering polish

**Strong validation of the shipped model.** We *detach* hidden surfaces (node leaves the DOM,
`disconnectedCallback` fires, streams torn down) and keep the JS instance — a hybrid that is **state-
preserving like keep-alive but render-cost-zero like unmount**. This sits at a *better* cost point than the
keep-in-DOM families (React `<Activity>` keeps the subtree at `display:none` and re-renders hidden trees at
lower priority; Vue `<KeepAlive>` keeps it deactivated). Flutter's `Offstage` doc — *"animations continue to
run in offstage children… use battery and CPU regardless of whether visible"* — is the explicit argument
*for* our teardown-on-detach: a dormant surface here has zero render-tree, paint, or live-subscription cost.

**Recommendation 1 — keep detaching, do NOT adopt `content-visibility:hidden`.** Its headline win (faster
show + preserved scroll) is already solved cheaply (RetainedScroll + instance reuse), and switching would
re-introduce in-document render cost and undo the 578 streams-off-when-hidden hygiene.

**Recommendation 2 — next View-Transitions polish: shared-element morph (result card → inspector)** via
`view-transition-name` (Baseline 2025; verified used *nowhere* today). Highest perceived-quality payoff now
that the DOM is retained; wiring is co-located with the nav-site transition trigger (`chrome/viewTransition.ts`
`startSurfaceTransition` — currently callback-only; extend for `types` too). One rule: names must be unique
per snapshot or the transition silently skips. Avoid element-scoped/nested transitions (Chrome-experimental).

**Recommendation 3 — measure the memory cost** so `MAX_RETAINED=24` is data-driven, not guessed: DevTools
**Detached-elements** profile (Microsoft endorses it for WebView2) is the safe primary path;
`measureUserAgentSpecificMemory()` is the in-app tier but **requires cross-origin isolation (COOP+COEP)** —
verify the Tauri host honors `crossOriginIsolated` before depending on it.

## §S.4 Frontier 4 — Continuity-as-a-feature UX

We've quietly built a *continuity substrate* (instance-retention, Recents/journal, background-work tray,
draft-kept hint, cold-boot resume, an action ledger + shared seen-cursor) — but it reads as plumbing
(experienced as the *absence* of a problem). The opportunity: make **"you never lose your place or your
work"** a legible, named capability (the way Figma made autosave a trust feature). Discipline that separates
this from notification spam (Linear/Slack/Figma + our own draftKeptHint all model it): **teach once, digest
the rest, interrupt only for the genuinely actionable.**

| # | Idea | Prior-art | Builds on (`file:line`) | Value:Effort | Risk |
|---|---|---|---|---|---|
| 1 | **One "Activity Center" front door** (fold the 4 trays — AdvisoryInbox/TaskList/ActionLedger/AiActivityDigest — into one drawer, Linear *Needs-you / Updates / Activity* sections) | Linear Inbox; Slack Activity | `AdvisoryInboxDrawer.ts`, `TaskList.ts`, `ActionLedgerView.ts`, `AiActivityDigest.ts`, `recallCursor.ts` | High : Med | consolidation can bury the actionable — exactly one loud section |
| 2 | **Completed-work digest** ("While away: reindex finished · 3 docs added · 1 failed") | Slack Catch-Up; Linear digest | extend `AiActivityDigest.ts` from AI-only to all completed work | **High : Low-Med** | over-promising — word "1 failed" honestly from the reason-code authority |
| 3 | **Searchable, previewing Recents** (upgrade the 8-entry dropdown; IntelliJ Recent Locations) | JetBrains Recent Locations | `RecentsMenu.ts` (cap 8), `NavigationJournal.ts` (50 already) | **High : Low** | pure projection of existing journal |
| 6 | **Ambient trust vocabulary (minimal honest set)**: Draft kept · Saved on this device · Indexing…/Up to date · Restored where you left off | Figma autosave; Docs "All changes saved"; Obsidian "yours forever" | `draftKeptHint.ts` once-per-session pattern, `StatusDeck.ts` passive zone | **High : Low** | **spam is the #1 risk** — ~4 classes, passive by default |
| 4/9 | Reopen-closed-context stack + cross-nav Undo | Ctrl+Shift+T; Figma version history | needs a distinct "closed" stack vs the nav trail; `effects` journal already powers `undoAllByOriginator` | Med : Med | don't conflate "navigated-away" (retained) with "closed" |

**Cheap-first wave:** #3 + #2 + #6 (all low-effort projections over existing substrate), then #1 as the
structural front door (after #2 so the digest content exists first). Defer the heavy history/undo (8/9) until
the cheap wave validates demand. The local-first trust narrative ("never lost AND never left your machine")
is *defensible only because* of the loopback-only + Head-never-touches-Lucene invariants — re-scope if cloud
sync ever lands.

## §S.5 Frontier 5 — Substrate-as-platform (simplify) + the governance it now warrants

**Simplify — honest headline: most is already done.** S-1 (declarative `static transientState`) and S-4
(LRU+reveal in the base) shipped. Remaining live decisions: **keep scroll opt-in** (auto-scroll-for-all is
over-engineering — "primary scroller" is unknowable generically for split/nested surfaces, which is *why* the
per-surface `getScroller` callback exists); and **adopt or delete the `onReveal` seam** — verified to have
**zero production consumers** (`JfElement.ts` defines it; no `views/*` override), i.e. a substrate slot with
no reader (the `substrate-without-consumer-flavors` smell). Either wire it on one server-backed surface
(Health/Brain, with a `static revealPolicy = { staleAfterMs }` refresh-on-return) or remove it until a
consumer exists. S-3 (staleness-policy onReveal) is worth it *only* paired with that first real consumer.

**Governance — build exactly ONE new gate.** Applying the codebase's "don't gate an empty set" discipline:

| Gate | Set today | Verdict |
|---|---|---|
| **G-1 reconnect-safety (effect-symmetry)**: every `setInterval`/`EventSource`/`Observer`/`subscribe()` set up on connect is released on disconnect | **NON-EMPTY** — 5 surfaces, 8+ effect fields (`HealthSurface.ts:554`, `BrainSurface.ts:630-680`, `UnifiedChatView.ts:825`), each torn down via *bespoke* code, protected only by one example test (`SurfaceReconnect.test.ts`) | **BUILD** — best ROI; reuses the existing gate's `mountTag` scope helper; upgrades a lone example into a class invariant (resolves `audit-without-test`/`structural-defects-no-repeat`). Prototype the `subscribe()`-symmetry regex against the 5 real surfaces first |
| G-2 onReveal-staleness coverage | EMPTY (`onReveal` has 0 callers) | decline until S-3 lands a consumer |
| G-3 durability-declaration coverage | near-empty (1 controller, 2 adopters, one *centralized* save-point) | decline; revisit if crash-recovery multiplies persistence sites |
| G-4 memory-budget (static) | un-measurable statically; already mitigated by the runtime LRU cap | decline — the runtime cap is the right tool; ground it with §S.3 measurement instead |

## §S.6 Cross-cutting synthesis — the recommended roadmap

The five frontiers are not independent; the dependency order that falls out:

1. **Cheap, visible, zero-risk now** (projections over shipped substrate): §S.4 #3 searchable Recents, #2
   completed-work digest, #6 ambient trust vocabulary; §S.3 shared-element View-Transition morph. These make
   the *invisible* substrate legible and are mostly presentation.
2. **Tidy the substrate** (small, removes a smell): resolve the dead `onReveal` seam (§S.5 — adopt on
   Health/Brain *or* delete); build the **G-1 reconnect-safety gate** (§S.5).
3. **The flagship** when ready for a headline: §S.1 multi-context Spaces — start with the **search-only
   minimal slice** (instance-key + contextId-address 2 stores), which composes for free with the already-
   built split-view; gate the full flagship on resolving the AMBIGUOUS live-resource rows (agent controller,
   AI-activity split, pending-effects queue) — and those resolve only after one UX decision (own-chrome-per-
   Space vs shared-chrome-swap-body).
4. **The durability/trust climb** (highest-risk pre-req first): §S.2 — *verify the loopback origin/port is
   stable* (blocking), then `persist()`+`estimate()` (cheap, stops silent loss), then the IndexedDB crash
   journal + selective restore. §S.4 #1 Activity Center is the front door that ties the completed-work digest
   + crash-restore + tasks together. Navigation-API migration is a later code-quality cleanup, not a
   durability play.

The throughline: **the retained instance is the unit of durable working state** (609's thesis), so every
frontier *extends* that unit rather than routing around it — Spaces extends it across contexts, durability
extends it across restarts, the Activity Center makes its background work legible, and the governance keeps
it honest as surfaces grow.

## §S.7 Verification status

- **Author-re-verified** (not just subagent-reported): `onReveal` has **0** production consumers (grep,
  non-test/non-definition → empty); `state/UserStateDocument.ts` exists with the per/cross-profile split;
  `view-transition-name` used **nowhere** (the morph is genuinely unbuilt).
- **Subagent-reported with `file:line` citation** (high-confidence, not independently re-run): the full store
  classification (§S.1); the loopback-`http://127.0.0.1:<port>` origin + absence of plugin-store/IndexedDB/
  Navigation-API (§S.2); the keep-alive cost contrast (§S.3); the continuity-substrate inventory (§S.4); the
  G-1 effect-field population (§S.5).
- **Flagged as needing a runtime probe before any build** (could not be settled statically, all WebView2/
  Tauri-specific): **loopback port stability across launches** (the §S.2 blocking pre-req — a shifting port
  orphans web storage); `navigator.storage.persist()` grant behavior in WebView2; whether `window.navigation`
  and `navigator.storageBuckets` are exposed at the pinned WebView2 Chromium; whether the Tauri host honors
  `crossOriginIsolated` (gates `measureUserAgentSpecificMemory`). bfcache-non-applicability is argued from
  the single-document architecture, not a Microsoft doc citation.
- **External prior-art**: primary-source-cited throughout (MDN/web.dev/WHATWG, React/Vue/Angular/Flutter/
  Apple/Android framework docs, Tauri docs, and product docs for Arc/VS Code/JetBrains/tmux/Linear/Slack/
  Figma/Obsidian/Ink&Switch). Arc's *specific numeric* details came from search extracts of 403/404-gated
  Help pages — re-confirm exact figures in a browser before treating them as precise.
