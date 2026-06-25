---
title: "574 — The frontend presentation kernel: the per-instance / false-global defect class, the ATOM + AMBIENT + modality tiers that make hand-authored presentation unrepresentable, and (§25) 574 as ONE ALTITUDE of the codebase's single projection spine — BUILT + LANDED on main (Part I/II + §25 Phases 1–5: contrast-roles, output oracle, two-scale consolidation, gate-fold into the 530 kernel, modality spike); the Edge-5 controller-shrink is WIP, and the DesignTokenTree-as-base-source / contrast-color() / 569 user-authoring edges are re-scoped or deferred"
type: tempdocs
status: open
created: 2026-06-10
updated: 2026-06-11
category: frontend / architecture / single-authority / presentation / design-theory / projection-kernel
related:
  - tempdoc 559 (the presentation projection, COMPLETED — the UI as a projection of a typed element catalog; "typed-value facets never drift, hand-authored template DOM/CSS always drifts"; the kernel is half-applied). **The direct parent.** 559 §8/§14 explicitly drew its boundary at the per-element FACET and stated bespoke content "composes projected elements — its buttons, its status chips … are projected"; 574 Part II builds the ATOM tier that boundary presupposes but was never built, and adds the AMBIENT tier + delivery substrate 559 never named.
  - tempdoc 557 (presentation = three single-authority projections — Display/Observed-state/Theme; the prevention ladder Collapse>Unrepresentable>Generate>Gate; §5.2 coverage-projects-from-catalog). The kernel grammar Part II speaks.
  - tempdoc 567 (theme-authoring authority — DesignTokenTree / KNOWN_TOKEN_NAMES / isSafeTokenValue / applyAppearance one-writer / @layer). The token authority Move 2 extends to the ambient value domains (motion / z / typography / scrollbar) it lacks.
  - tempdoc 569 (user-authored presentation projection; Move 3's "closed, generated presentation-component vocabulary"). Move 3 (atom authority) builds 569 Move 3; the `gen-component-vocabulary.mjs` codegen is its seed.
  - tempdoc 565 (agent-window content/evidence — statusTone, evidenceProjection; the COMPOSITION/spatial tier that picked up part of 559 §8's deferred layout composition). The atom co-projects statusTone; composition is its sibling deferred tier.
  - tempdoc 560 (extension substrate — value-only / jf-* confinement / verdict-at-the-seam) + 561 (one interaction window; interaction-surface gate) + 521 (split-stage — the second instance made real) + 548 (the two-authorities invariant + the ladder). The trust/IA/scope context.
---

# 574 — Completing the presentation projection kernel

> **Document shape (two parts).** **Part I (§1–§18)** — the *empirical diagnosis*: the per-instance /
> false-global defect class measured across the whole FE (and backend), from the scrollbar specimen to the
> shadow-DOM false-global family (S0–S11) to the §18 intensity assessment. It is the proof-by-example that
> grounds the design — the role 557's Appendix and 559's Appendix A play for theirs. **Part II (§19) — the
> correct design** (the center of gravity): the correct long-term *structure* that makes this class
> unrepresentable, in the house design-theory genre (feasibility disregarded; major rewrites in scope;
> extend the existing kernel, never fork it). **Part II supersedes Part I's §8 short-term sequencing** —
> §8 is kept only as a tactical reading of the design, not the recommendation.

## Implementation status (2026-06-11) — read this first

- **LANDED on `main`** (merge `1271ccd4e`): Part I/II — the kernel itself (atom + ambient + delivery +
  modality/transient tiers and their gates) — and **§25 Phases 1–5**: contrast-role legibility · the output
  oracle · the two-scale font consolidation · the gate-fold into the 530 discipline-gate kernel · the modality
  spike — plus the critical-review fixes (§25.E/F).
- **WIP (committed checkpoint on this branch, NOT merged):** the §25 **Edge-5 controller-shrink** —
  `TransientController` popover-mode is built + 1 of 4 transient overlays migrated; **resume from §25.G**.
- **Resolved-as-deferred (decided, NOT remaining work):** Edge 1 (`DesignTokenTree`-as-base-source) — re-scoped
  away as the wrong lever (§25.B item 1); Edge 2 (`contrast-color()`) — blocked: unsupported on the Windows-only
  WebView2/Chromium target, the JS derivation ships instead; Edge 3 (user-authored composition) — belongs to the
  separate active tempdoc **569**.

The design narrative (§24, §25.A–D) is dated *history* — feasibility-disregarded theory at its writing date. The
**as-built truth** is **§25.E / §25.F / §25.G + this banner**; trust those over the narrative where they differ.

# Part I — The empirical diagnosis (the defect class, measured)

## §1 Thesis

JustSearch's frontend is **single-window / multi-surface**: one `Shell` chrome, one
active surface at a time, navigated by re-mounting Lit custom elements. Because there
is "usually one of everything," a recurring shortcut is to wire a **globally-singular
concern** (the viewport, the API base, an inference poll, a registry) into a
**per-instance lifecycle** (`connectedCallback`, a constructor field, a per-row
component) — and it *looks* correct as long as exactly one instance ever exists.

The defect is latent until a **second instance** appears. Three scenarios make the
second instance real, not hypothetical:

1. **Split-stage** (521) — a primary + optional secondary docked surface. Two surface
   instances are live at once; every per-mount resource doubles.
2. **Per-row components** — `OpButton` / `RowActions` render once per result row, so a
   results list instantiates N copies of a thing that should be one.
3. **Surface remount on navigation** — every navigation tears down and re-wires a
   surface's `connectedCallback` resources (listener churn; state lost on remount).

This is the **548 two-authorities class** and the **561 two-windows class**, one tier
down: instead of two *windows* or two *record authorities*, it is **two listeners, two
clients, two pollers, two breakpoint queries** for a concern that is conceptually one.
The correct structure is the same the codebase already proves elsewhere: **one shared
authority, subscribed-to, with the per-instance copy made unnecessary** — the existing
`inferencePoll` / `statusPoll` / `EnvelopeStreamPool` singletons are the reference
implementations.

## §2 The test (when a per-instance resource is a defect)

A per-instance resource is a hoisting candidate iff **all three** hold:

- **(a) Globally singular** — the thing it observes/owns is one per app (the viewport,
  the API base, a backend status channel), not one per surface's own DOM/UX state.
- **(b) Duplicated under a second instance** — split-stage, per-row, or a remount race
  produces ≥2 live copies doing redundant work (duplicate fetch, duplicate listener,
  divergent state).
- **(c) No instance-local reason** — it does not track *this element's* transient UI
  state (a copy-flash, a streaming tick, this element's own size).

If (c) fails, it is **correctly** per-instance (e.g. a `ResizeObserver` watching the
element's own box, a copy-flash `setTimeout`). The point of the test is to separate
*globally-singular-but-mis-scoped* from *legitimately-instance-local*.

## §3 Verified findings (ranked) — 2026-06-10

Each verified against source; two audit over-claims discarded (§5).

### Finding 1 — Viewport breakpoint `matchMedia`, per chat-view instance → one global responsive store. (MEDIUM-HIGH)
- `views/UnifiedChatView.ts:371` — `this.wideRailMql = window.matchMedia('(min-width: 64rem)')`,
  created per instance in the field/`connectedCallback`.
- The viewport is inherently **one** (test (a) ✓). The code already reasons about the
  split-stage second instance at `UnifiedChatView.ts:1482` ("active viewport, not a
  dormant duplicate") — i.e. test (b) is already known-live. No instance-local reason
  (test (c) ✓): the breakpoint is identical for every surface.
- **Correct structure:** one `responsiveState` module (`subscribeBreakpoint(...)`),
  mirroring `utils/inferencePoll.ts` / `utils/statusPoll.ts`. It becomes the single home
  for any future breakpoint logic and is a down-payment on 569's co-projected adaptivity.
- **Cleanest win** — textbook per-instance-of-a-global-thing, ready-made pattern to copy,
  matters precisely in the split-stage case.

### Finding 2 — `OperationClient`, 6 production instantiation sites, cached per component → one shared client. (MEDIUM)
- Sites: `components/OpButton.ts:148` (per row), `components/RowActions.ts:116` (per row),
  `views/BrainSurface.ts:589`, `components/IndexingOverlay.ts:323`, `hooks/resolvePathLazy.ts:89`,
  and `plugin-api/capabilities/data.ts:51` (boot-time per plugin tier — **correct as-is**).
- Stateless, so the argument is **single `apiBase` authority** (if the base ever changes,
  per-row clients diverge — test (a)/(b)) not performance. Low impact, genuine.
- **Correct structure:** a module-level `getOperationClient(apiBase)` accessor (re-mints
  only on base change). Leave the plugin-tier client alone (boot-time, trust-scoped).

### Finding 3 — `BrainSurface` per-mount pollers; the inference poller duplicates the global one. (MEDIUM)
- `views/BrainSurface.ts:653/669/685/700` — four `setInterval` pollers in the surface
  lifecycle. The 5 s inference poller (`:700`) overlaps the already-global
  `inferencePoll` + `aiStateStore` — test (a)/(b) ✓. The install/pack/runtime pollers are
  legitimately diagnostic-surface-scoped (test (c) — they back this surface's own panels).
- **Correct structure:** the inference poller subscribes to the shared store; the other
  three stay surface-local (but should be audited for split-stage doubling if BrainSurface
  can ever be a secondary stage).

### Finding 4 — Forked answer/evidence *authorities* (the AUTHORITY-level instance; already in-flight under 565). (HIGH relevance, governed elsewhere)
- The inspector's own AI Q&A streaming state (`state/inspectorState.ts:34-52`, the `ai`
  field), `CitationsPanel` (mounted only in `SummarizeView`), `searchEvidence` (agent path),
  and `CitationHoverCard`'s own strong/moderate/weak scorer are **three+ divergent renderers
  of one "grounded answer + evidence" concept** — per-surface forks of a global authority.
- 565 §3.A unifies these onto `evidenceProjection`. **574's contribution:** flag that the
  inspector's per-document `ai` Q&A is a *separate* fork that should be confirmed as folded
  into the one grounded-answer authority (it is not obviously in 565's named scope).

## §4 Already correctly global / correctly per-instance (the boundary, for reuse)

**Correctly global (reference implementations — copy these):**
- `utils/inferencePoll.ts`, `utils/statusPoll.ts` — one fetch/interval fanned out via a
  listener `Set`. The pattern Finding 1/3 should adopt.
- `streaming/EnvelopeStreamPool.ts` — SSE pooled by URL, not per-surface.
- `state/inspectorState.ts` — one global state, re-targeted per selection (NOT per-document
  instances; the audit's "per-document RAG instance" hypothesis was wrong — §5).
- `CommandRegistry` / `KeybindingRegistry` / `applyAppearance` (one theme writer).

**Correctly per-instance (do NOT hoist — test (c) fails):**
- `UnifiedChatView` render-tick timer (`:506`), `SearchSurface` copy-flash `setTimeout`
  (`:686`) — transient, this-element UX state.
- `primitives/adaptiveDensity.ts:130`, `primitives/adaptiveBar.ts:104` `ResizeObserver`s —
  each watches its **own** element's box (the adaptive primitive's whole point).

## §5 Corrections (verify-don't-guess)

The first-pass subagent audit made two claims that source did **not** support; recorded so
they are not re-propagated:
- ❌ "`OperationClient` instantiated 50+ times per navigation" / "`Shell.ts:908/964` creates
  clients" — there is **no** `new OperationClient` in `Shell.ts`, and each component caches
  its client in a field. Real count: 6 production sites (Finding 2).
- ❌ "per-document RAG answer is a per-instance duplication" — `inspectorState` is a single
  global singleton re-targeted per `setSelected()`; the `ai` state is reset, not duplicated.
  (It IS a *forked authority* vs the unified-chat answer — Finding 4 — but not a per-instance
  runtime duplication.)

## §6 Anti-drift — does this earn a gate, or stay discipline?

Open question (named, not defaulted, per the §6 house rule). Candidate framings:
- A **lint/grep ratchet**: forbid `window.matchMedia(` / `new OperationClient(` outside a
  small allowlist of singleton modules (the `check-message-single-model` baseline pattern).
  Cheap, catches the exact regression, but enumerated-allowlist-flavored (the project avoids
  those).
- **Discipline only**: the reference singletons (`inferencePoll`/`statusPoll`/`EnvelopeStreamPool`)
  + this catalogue are the authority; a new per-instance copy of a global concern is caught
  at review. Same `prose-only` tier as most presentation rules.
- The decision likely depends on whether §7's "similar work" pass finds this is a *recurring*
  class (→ ratchet earns its keep) or a *handful of one-offs* (→ discipline). **Deferred to
  the §7 outcome.**

## §7 Similar work — the generalization pass (2026-06-10)

A whole-FE sweep (event listeners, `localStorage`/`sessionStorage`, `navigator.*`,
`matchMedia`, `setInterval/Timeout`, `document.documentElement`, clients/caches) shows the
§3 findings are the *narrow* face of a wider class. **The generalization:**

> **Singular concern, plural access.** A concern that is conceptually **one per app** —
> a *runtime resource* (poller, listener, client, observer) **or** a *capability/derivation*
> (locale, clipboard, storage, theme-root, breakpoint) — is reached at **N independent
> sites**, each creating or deriving its **own** copy, instead of through **one authority**
> the sites subscribe to or call.

§1's "second-instance defect" is the **runtime-resource** face (two pollers, two listeners).
The sweep surfaces its twin — the **Nth-call-site defect**, the **capability** face: a single
named authority *already exists*, yet many sites bypass it and re-derive the singular thing
inline. Same root (no funnel through one authority), same fix, same prevention question.

### §7.A Runtime-resource face (the §3 findings) — recap
`matchMedia` breakpoint (F1), `OperationClient` (F2), `BrainSurface` inference poller (F3).
Verdict: real but case-by-case; "is this globally singular?" is **judgment** → discipline.

### §7.B Capability face — bypassed single authorities (NEW; the higher-yield half)

The §2 test applied to each; all have an *existing or obvious* one-authority home:

| # | Singular capability | The one authority | Bypass sites (raw access) | Test a/b/c | Confidence |
|---|---|---|---|---|---|
| B1 | **Locale** (`navigator.language`) | *none yet* — `i18n.ts` is the de-facto resolver | `utils/intlFormat.ts` (4×, each `?? navigator.language`), `i18n.ts:43` | ✓ / ✓ (user-override would diverge all) / ✓ | MEDIUM-HIGH |
| B2 | **Clipboard** | `utils/clipboardCopy.ts` (uniform success/failure + advisory) | `views/UnifiedChatView.ts:557,614`, `components/chat/ReasoningBlock.ts:142`, `substrates/actions/index.ts:547` | ✓ / ✓ (4 sites, no uniform failure UX) / ✓ | HIGH |
| B3 | **Web storage** | `primitives/storage.ts` | **39 files** touch `localStorage`/`sessionStorage` directly (quota / key-namespace / profile-scope / SSR-guard re-handled or skipped per site) | ✓ / ✓ / ◧ (some catalog caches are legit) | MEDIUM (large, partly legit) |
| B4 | **Theme root** (`getComputedStyle(documentElement)` / `data-theme`) | `state/themeState.ts` + `applyAppearance` (one writer) | reads in `themes/tokenIntrospection.ts:64`, `plugins/token-editor/TokenEditorPlugin.ts:76,226,310` | ✓ / ◧ (reads, not writes) / ✓ | LOW-MEDIUM |

B1 is the **i18n analog of F1** (a globally-singular value re-derived per call); B2 is the
**cleanest** capability finding (a real authority with a real UX contract, bypassed 4×).

### §7.C Ad-hoc global event channels (sibling class; relates to 559 messaging authority)

Each overlay host invents its **own** `document`-level custom-event channel + global listener:
`AuthorizationHost` (`jf-consent-request`), `ElicitHost` (`jf-elicit-request`), `Peek`
(`jf-peek-request`/`-dismiss`), `MacroDryRun` (`jf-open-macro-dry-run`), `PluginErrorOverlay`,
`AdvisoryStore` (`EPHEMERAL_TOAST_EVENT`), `HoverPreviewHost`. Not a *per-instance* defect
(each host is a single mount), but it **is** "many ad-hoc global buses" rather than one
dispatch authority — the same single-authority question 559's `check-message-single-model`
already asks of the *visible* messaging surface, here at the *event-channel* layer. Flag as
adjacent; likely governed by extending 559's messaging-authority thinking, not by 574.

### §7.D Correctly singular — the reference set (what the above should adopt)
`utils/inferencePoll.ts`, `utils/statusPoll.ts` (one fetch/interval, listener-`Set` fan-out);
`streaming/EnvelopeStreamPool.ts` (SSE pooled by URL); `commands/KeybindingRegistry.ts:167`
(one global `keydown`); `chrome/Shell.ts` resize (one); `applyAppearance` (one theme writer);
`state/inspectorState.ts` (one re-targeted state). These already prove the pattern.

## §6′ Anti-drift decision — RESOLVED by the §7 sweep (supersedes §6's defer)

The sweep settles §6: this is a **recurring class** (matchMedia; `navigator.language` ×5; raw
`navigator.clipboard` ×4 *despite a wrapper*; `localStorage` in 39 files *despite a storage
primitive*), not a handful of one-offs — so for the **capability face (§7.B), a ratchet lint
earns its keep**, while the **runtime-resource face (§7.A) stays discipline** (singular-ness is
judgment). The capability ratchet is *narrow and clean*: it allowlists **one authority module
per capability** (not an enumerated value list — so it sidesteps the project's enumerated-
allowlist aversion), in the proven `check-message-single-model` baseline shape (the baseline
shrinks as bypasses fold in):

- forbid raw `navigator.clipboard.` outside `utils/clipboardCopy.ts`;
- forbid raw `localStorage`/`sessionStorage` outside `primitives/storage.ts`;
- forbid raw `navigator.language` outside a (new) locale module;
- forbid `window.matchMedia(` outside a (new) `responsiveState` module.

Each rule is a *single-authority* statement with a *named* home — the same shape the codebase
already enforces for SSOT catalogs and wire types. **Recommendation:** land the authorities
(B2 first — it already exists; then F1/B1 as small new singleton modules), then ratchet. Hold
B3/B4 (large / mostly-legit) and §7.C (defer to 559) out of the first ratchet.

## §9 Bigger elements — architectural-scale forks (2026-06-10)

The §3/§7 findings are *resource/capability* scale. Pushing the same lens up to whole
*patterns* and *concepts* surfaces three larger instances of "single authority, plural
implementation." Two are genuine consolidation opportunities; one is now mostly solved
(recorded so the ledger is honest). Three parallel read-only censuses; load-bearing
claims verified against source.

### §9.A The hand-rolled store micro-substrate — 13 forks of one pub/sub pattern (HIGH)

`state/` holds **13 hand-rolled pub/sub stores** (`searchState`, `unifiedChatState`,
`inspectorState`, `selectionState`, `shellContextState`, `searchFiltersState`, `uiModeState`,
`agentChatState`, `askChatState`, `conversationListStore`, `presentationRuntime`,
`NavigationJournal`, `retrospectiveDrawer`), each re-implementing the *same*
`let state / Set<listener> / get / set / subscribe / emit` boilerplate by hand. (Plus 5
*projection* stores over `UserStateDocument` — already on a better signal substrate — and
`aiStateStore`, the one signal-based store. Those are NOT in scope.)

- **There is no `createStore<T>` factory** — but the team **already proved this exact move**:
  `primitives/registry.ts` `createRegistry<T>()` consolidates **8** registries onto one
  primitive (`CommandRegistry`, `ContextActionRegistry`, `WalkthroughRegistry`,
  `TemplateCatalog`, `StatusBarRegistry`, `SelectionActionRegistry`, `InspectorTabRegistry`,
  `EmptyStateRegistry` — all `createRegistry<T>()`, tempdoc 543 §28.W7). A `createStore<T>` is
  the **same consolidation, one module over**, with `primitives/notify.ts` (`notifyAll`) and
  `primitives/storage.ts` (`safeLocalStorage`) already present as the building blocks.
- **The fork is a latent-bug surface, not just boilerplate.** The hand-rolled copies have
  *diverged* in notification semantics: `state/searchState.ts:80-82` `emit()` has **no
  try-catch** (one throwing listener aborts the rest of the fan-out) while it **does** notify
  synchronously on subscribe (`:86`); other stores wrap each listener in try-catch and/or skip
  the sync-on-subscribe call. A single factory makes the subscriber contract (sync-on-subscribe?
  per-listener isolation? diff-before-notify?) **one decision in one place** instead of 13
  silently-different ones.
- This is the **substrate-level sibling** of §7.B: a globally-uniform *pattern* implemented N
  times instead of funneled through one authority. **Prereq:** fix the `searchState` no-isolation
  bug as part of the migration (don't bake it into the factory).

### §9.B Surface scaffolding — layout is shared, but empty/error/loading are 7× bespoke (HIGH)

A 10-surface census shows **`SurfaceLayout` is genuinely shared** (every `*Surface.ts` composes
`surfaceLayoutStyles`/`surfaceScrollLayoutStyles` — 559's layout authority is working). But the
*content-state scaffolding* is hand-rolled per surface:

| Scaffold | Shared? | Bespoke count | Evidence |
|---|---|---|---|
| Layout wrapper | ✅ `SurfaceLayout` | 0 | all 10 surfaces compose it |
| Empty/zero state | ❌ | **7** | `.empty`/`.empty-state` re-styled in BrainSurface/BrowseSurface/HealthSurface/LibrarySurface/LogSurface/MemorySurface/SearchSurface |
| Error state | ❌ | **7** | **7 different class names** (`.alert`, `.alert.error`, `.error`, `.error-banner`); each re-implements dismiss |
| Loading state | ◧ | 5 | all use the shared `icon()` spinner but no shared `<jf-loading>` wrapper |
| Header/toolbar | ◧ | 10 | bespoke *content* (correct — domain-specific) over the shared `.header` region |

- **The empty/error duplication is the §9.A pattern at the render tier:** each surface (instance)
  re-authors a globally-uniform UX element. The fix is the render analog of `SurfaceLayout` —
  `jf-empty-state` + `jf-error-alert` primitives — which is exactly the *cross-cutting element
  class* 559's projection engine is meant to own (this is *not* the bespoke-leaf composition 559
  §8 deliberately left hand-authored; an empty state and an error banner are uniform shells).
- **Caveat (don't conflate):** an `EmptyStateRegistry` exists (`commands/EmptyStateRegistry.ts`)
  but it is a *contribution* registry (plugins add zero-state CTAs to the palette), **not** a
  shared render component — so the 7 bespoke surface empty-states are a real render-tier gap, not
  a bypass of that registry.
- **One contract violation logged:** `BrowseSurface` renders a `.scroll` div instead of the
  `SurfaceLayout` `.body` region (BrowseSurface.ts:585) — bypasses the region contract. → an
  `observations.md` line (out of scope to fix here).

### §9.C The grounded-answer authority — mostly solved; one concrete residual fork (MEDIUM, sharpens F4)

Good news that updates Finding 4's worry: **evidence *scoring* is now genuinely one authority.**
`components/chat/evidenceProjection.ts` privately owns the thresholds (`TIER_HIGH=0.6`,
`TIER_MEDIUM=0.5`) and is the sole mint of the branded tier; `CitationsPanel`, `CitationHoverCard`,
`MarkdownBlock` (inline `[n]`), and `SourcesPane` all derive from it — no forked scorer survives
(the `groundingSemantics` register-gate enforces it). The agent answer renders through one
`jf-markdown-block`. 565's content-tier unification largely *landed*.

**The one concrete residual fork:** `components/InspectorPane.ts:587` `renderAnswer()` — the
per-document "Answer" tab — is a **separate, ungrounded answer authority**: `sendQuestion()`
(`:341-402`) calls `/api/chat/agent` directly (not shape-routed) and stores **only the final text**
(`:395`), discarding sources/citations, so the inspector answer carries no evidence. It is the
last per-surface fork of "an answer," outside the one grounded-answer authority 565 built around
`UnifiedChatView`. → Either fold it into the grounded-answer pipeline, or explicitly mark it
out-of-scope (a deliberate lightweight per-doc quick-ask). **Named, not defaulted** — this is a
565-adjacent decision, flagged here for that doc's owner.

## §10 Synthesis — one defect class at three altitudes

Every finding in this doc is the **same shape at a different altitude**: a concern that is
conceptually *one per app* is implemented *many times* instead of owned by one authority.

| Altitude | Singular concern | Plural implementation | The one-authority fix | Status |
|---|---|---|---|---|
| **Resource** (§3) | viewport, api-base, inference poll | per-mount listener/client/poller | a subscribe-to singleton | open |
| **Capability** (§7.B) | clipboard, locale, storage | raw `navigator.*`/`localStorage` at N sites | route through the named authority | open (ratchet, §6′) |
| **Substrate** (§9.A) | the pub/sub store pattern | 13 hand-rolled stores | `createStore<T>` (mirror `createRegistry`) | open |
| **Render** (§9.B) | empty / error / loading shells | 7× bespoke per surface | `jf-empty-state`/`jf-error-alert` primitives | open |
| **Concept** (§9.C) | "a grounded answer" | UnifiedChat + Inspector forks | the one grounded-answer authority (565) | mostly solved; 1 residual |

The codebase has **already won this fight repeatedly** — `createRegistry` (8 registries),
`SurfaceLayout` (10 surfaces), `evidenceProjection` (4 evidence renderers), `inferencePoll`/
`statusPoll`, the canonical `SearchTrace` (553), the one window (561). The findings here are the
**still-forked remainder** of a thesis the project is ~80% through applying. That framing is the
value of this doc: not "new defects," but a *map of where the single-authority spine has not yet
reached*, ranked by how cleanly the existing proof-of-pattern transfers.

## §11 Meta-analysis — the generalization-mechanism taxonomy + the un-swept axes (2026-06-10)

§1–§10 catalogued *where* the single-authority spine is still forked, organised by **altitude**
(§10). This section steps back to the two questions the altitude table doesn't answer: **(A)** for
the types found, *how* does one generalize, *how strongly*, and *when is generalizing wrong*; and
**(B)** which *types/areas have not been swept at all*.

### §11.A The five generalization mechanisms — a power ladder, with the AHA brake

The §10 altitudes don't each get a bespoke fix; they map onto **five generalization mechanisms**,
which form a *power ladder* (each binds the single-authority guarantee more tightly than the last).
Naming the mechanism matters because picking the wrong one over- or under-binds.

| # | Mechanism | What it produces | Binding strength | Findings that use it | In-repo proof |
|---|---|---|---|---|---|
| 1 | **Singleton hoist** | one shared instance; sites *subscribe* | weak (a site can still re-create) | F1 responsive, F2 client, F3 poller (§3) | `inferencePoll`/`statusPoll` |
| 2 | **Authority-funnel + ratchet** | one named module is the *sole caller*; a lint forbids raw access | medium (enforced API, concept unchanged) | clipboard/locale/storage/**HTTP**/**format** (§7.B, §11.B) | `clipboardCopy` / the §6′ ratchet |
| 3 | **Factory / primitive extraction** | one generic `create*<T>()`; N typed instances adopt | medium-strong (one mechanism impl) | §9.A `createStore`; **dismiss-controller** (§11.B) | `createRegistry` (8 registries); `OverflowController`/`DensityController` |
| 4 | **Projection / render authority** | an *engine projects* the element from a declaration; the breaking form is **unrepresentable** | strong (559 thesis) | §9.B `jf-empty-state`/`jf-error-alert`; §9.C answer | `SurfaceLayout`, `evidenceProjection` |
| 5 | **Reserved channel** | a channel others *structurally cannot reference* | strongest (for *security/trust*) | (none open here) | 569 Move 4 trusted-UI layer |

**The AHA brake (the reason NOT to generalize).** Per CLAUDE.md's anti-over-DRY rule, a mechanism
is only warranted when the copies **share a reason to change**. The test discriminates cleanly:

- **Warranted** — *stores* (the subscriber contract is one decision → §9.A); *empty/error shells*
  (a uniform, theme-driven shell → §9.B); *dismiss/focus triad* (one a11y contract → §11.B);
  *formatting rules* (locale/units change together → §11.B). These share a reason to change.
- **NOT warranted** — *surface headers* (domain-specific content; only the `.header` *region* is
  shared, correctly — §9.B); *the search-evidence vs RAG-citation split* (deliberately different
  granularities, 565 §10); *the 5 `UserStateDocument` projections* (already a better substrate).
  Unifying these would couple things that change for *different* reasons — the over-DRY failure.

So the full decision is **2-D: (mechanism ladder) × (shared-reason-to-change)**. The altitude tells
you it's forked; the mechanism tells you the strongest available fix; AHA tells you whether to apply
it at all. *This is the reusable framework — it generalizes the whole doc, and it is the lens for the
un-swept axes below.*

### §11.B Un-swept axes — types/areas not yet considered

Three are **new FE element-types** I probed concretely this round; the rest are **deliberate scope
frontiers** named honestly (not yet swept), each with its likely mechanism and a concrete first probe.

**New FE types (probed, real):**

- **B-i · Behavioral micro-patterns (the interaction analog of §9.B; mechanism 3).** Every overlay
  hand-rolls the **same dismiss/focus triad** — `document` `mousedown`(outside-click) + `keydown`(Esc)
  in `connectedCallback`, plus focus return — across `ContextMenu.ts:141-151`, `BookmarksPopover`,
  `SelectionActionsMenu`, `ConfirmDialog`, `AdvisoryInboxDrawer` (≥5 copies). `ContextMenu` also
  hand-rolls **viewport-edge clamping** (`:153-168`) — a second forkable behavior (anchored
  positioning). **Why it ranks high:** (a) the team *already* uses the Lit `ReactiveController`
  mechanism for cross-cutting behavior (`OverflowController`, `DensityController`) — a
  `DismissController`/`AnchorController` is the *same move*; (b) it is **correctness/a11y-bearing**
  (focus-return is 559's operability authority), so it passes AHA cleanly and a fix should land
  *once*, not 5×. This is a genuinely new type — §9.B was render scaffolding; this is *behavioral*
  scaffolding. Ties to **569 Move 8** (the interaction statechart).

- **B-ii · Pure derivations / formatters (mechanism 2, funnel).** ~40 files inline numeric/size/
  percent/relative-time formatting (`toFixed`, `/1024`, `KB`, `toLocale`, `Math.round(*100)`); a
  would-be authority exists (`utils/intlFormat.ts`) but is widely bypassed. Same shape as the
  capability bypass (§7.B): the *format rule* (locale, unit thresholds, precision) is globally
  uniform and changes together (AHA ✓), even though *what* is formatted is local — so **funnel the
  rule, don't unify the call sites**. Note a *spectrum* exists: `evidenceProjection`/`budgetProjection`/
  `retrievalSignals`/`searchTraceExplain` are already named projections; the raw inline `toFixed`/
  `/1024` in surfaces is the forked tail. Also includes **status→glyph** and **status→tone**
  selection (565 §3.B `statusTone` is the intended authority — confirm no inline `var(--accent-*)`
  status coloring survives).

- **B-iii · Network/HTTP access (mechanism 2, funnel — a B-row sibling).** 52 `fetch(` sites across
  23 files; a shared `api/http.ts` exists but `AgentSessionController` (8×), `conversationListStore`
  (8×), and others bypass it with raw `fetch` + bespoke error/abort handling. The HTTP analog of
  clipboard/storage — add it as **B5** to the §6′ ratchet (forbid raw `fetch(` outside `api/http.ts`
  + the SSE pool), once `api/http.ts` covers abort + error + parse-boundary uniformly.

**Deliberate scope frontiers (named, not yet swept — this whole doc has been *frontend-only*):**

- **B-iv · Backend Java (the largest un-swept area).** The same defect class near-certainly lives in:
  *config resolution* (scattered `System.getenv`/`-D` reads vs the `EnvRegistry`/`ResolvedConfig`
  authority); *DTO projection vs fork* (553's entire thesis — every record that re-describes
  `SearchTrace`); *hand-rolled lifecycle state machines* (`AgentSessionController`'s "14 SSE paths",
  `InferenceLifecycleManager`, worker-restart — 569 Move 8 names these kernel-internal forks); *gRPC
  forwarding* (`DelegatingIngestService`). **Crucially, the backend has already run this exact move
  once:** the **530 discipline-gate kernel** consolidated N gates onto one kernel — the *server-side
  `createRegistry`*. First probe: `grep -rn 'System.getenv' modules/*/src/main/java` and compare to
  `EnvRegistry`'s coverage. *Mechanism likely 2 (funnel) for config, 4 (projection) for DTOs.*

- **B-v · Cross-process / wire / SSOT.** Mostly *already generalized* (564 record→schema→{TS,Zod};
  553 `SearchTrace`) — but the **`fields.v1.json` dual-copy** is a deliberately-tolerated fork held
  by the `ssot-catalog-sync` gate (mechanism 2, gate-enforced). Worth a census of *other* dual-copies.

- **B-vi · Tests & fixtures.** Forked mocks/fixtures/builders across test files (e.g. how many tests
  hand-build a `KnowledgeSearchResponse` vs a shared builder). Lower priority; mechanism 3.

**An orthogonal generalization axis the doc has under-weighted:** every finding so far is
*generalization-as-cleanup* (collapse N forks → 1). The higher-value sibling is
**generalization-as-enablement** — lifting a one-off into a *capability* that unlocks new behavior.
**569 is the exemplar** (generalize the projection spine → user-authored presentation); **530** likewise
(generalize gates → a kernel that makes *new* gates cheap). The un-swept question worth its own pass:
*which current one-offs, if generalized, would unlock a capability* (not just delete duplication)? —
e.g. a `DismissController` (B-i) is cleanup, but a general **anchored-overlay primitive** would unlock
consistent popovers for *future* surfaces, the 559-style payoff.

### §11.C What this implies for the doc's recommendation
The framework (§11.A) says: lead with mechanism-4 wins where copies share a reason to change
(§9.B render shells, §9.C), because unrepresentability is the strongest guarantee; take mechanism-3
where a proven primitive already exists (§9.A `createStore` ⟂ `createRegistry`; B-i `DismissController`
⟂ `OverflowController`); and reserve mechanism-2 ratchets for the capability/funnel rows (§6′ + B-ii
format + B-iii HTTP). The single most under-valued *new* finding is **B-i (behavioral micro-patterns)**:
it is a11y-bearing, has a direct in-repo proof-of-pattern, and is a *type* the doc had entirely missed.

## §12 Backend sweep (B-iv) — the defect crosses the process boundary (2026-06-10)

The first sweep of the **Java backend** (the §11.B-iv frontier; everything prior was frontend-only).
Two parallel censuses + direct grep, read against the §11.A *mechanism × AHA* framework. The headline:
**the same "single concern, plural implementation" class is present server-side — but the codebase's
strongest in-repo proofs that it can be governed are *also* server-side** (the 530 discipline-gate
kernel; ArchUnit). Findings are graded by the AHA brake, not raw duplication count.

### §12.A Retry / backoff / poll-until-ready — the standout (mechanism 3; HIGH)

**Census: ≥4 *named* retry/backoff policy classes + ≥6 distinct retry/poll *loops*, zero shared
mechanism.** Named: `worker-services/.../loop/BackoffPolicy.java`, `worker-core/.../ingest/IngestionRetryPolicy.java`,
`app-agent/.../AgentRetryPolicy.java`, `app-agent-api/.../registry/RetryPolicy.java`,
`ipc-common/.../grpc/GrpcRetryServiceConfig.java`. Loops: `WorkerSpawner` restart (exp backoff
1s→2s→4s, max 3), `LlamaServerOps.waitForServerHealth` (deadline poll @500ms) + `schedulePeriodicHealthCheck`
(30s, 3-fail→restart), `AgentRetryPolicy.sleepRetryDelay` (`Thread.sleep` + interrupt-restore + ±20% jitter),
`RemoteKnowledgeClient.reconnect`, `GrpcCircuitBreaker`.

**Mechanism × AHA verdict:** the **policies** legitimately differ (jitter for LLM transients; exponential
for process restart; fixed-interval for health) and must stay per-domain — but the **mechanism** (the
loop, deadline arithmetic, `Thread.sleep`-with-interrupt-restoration, jitter application, attempt
counting) is uniform and re-implemented ≥6×. So the correct move is **mechanism 3**: one `Backoff`/
`PollUntilReady<T>` primitive *parameterised by* the policy — the **backend analog of `createStore` ⟂
`createRegistry`** (one mechanism, N typed policies), NOT a unification of the policies (that would fail
AHA). **Two extra reasons this ranks HIGH, beyond dedup:** (a) `Thread.sleep` + interrupt-restoration is
a classic per-copy subtle-bug surface; one tested primitive removes it everywhere; (b) it directly serves
the CLAUDE.md *flaky-IPC* pitfall ("use state polling `awaitPort`, not `Thread.sleep`") — a shared
`PollUntilReady` is the canonical home for that rule. Shared reason to change: ✓ (the *mechanism* changes
together; the policies don't — so parameterise).

### §12.B Config read authority — partially forked, but the fix is an ArchUnit fence (mechanism 2; MEDIUM)

**Census: 164 `System.getenv`/`getProperty` calls across 59 files.** The authority is real and broad
(`configuration` module: `EnvRegistry` ~200 keys, `ConfigKey`, `ResolvedConfigBuilder`→`ResolvedConfig`,
plus `SystemAccess`/`ConfigPrecedence` helpers). Of the 164: ~13 legitimate (inside `configuration`),
the large majority **platform/JVM/operational toggles** (`os.name`, `user.dir`, `java.*`, worker-spawn
env, test hooks) that *correctly* do not belong in `ResolvedConfig`, and a **smaller true-fork set**
reading keys `EnvRegistry` already owns (telemetry exporters' size/retention; `LlamaServerOps` policy
flags + the `SYS-PROP-LEGACY-COMPAT` inference timeout).

**Critical correction to the raw census:** most of the "26 forks" the sub-agent flagged read config
directly **because the config authority isn't built yet at that point in bootstrap** (telemetry exporter
constructors; `LlamaServerOps` static init) — that is a *bootstrap-ordering* constraint, not a lazy fork,
and the fix is lazy-init/deferred-read, not "route through `ResolvedConfig`." The genuinely-avoidable
forks are the **policy-flag reads** (`LlamaServerOps` reads `policy.*` sysprops while `ResolvedConfig.policy()`
exists). **Mechanism × AHA verdict:** mechanism 2 (funnel) — and the high-leverage, in-idiom fix is an
**ArchUnit namespace fence**: forbid `System.getenv/getProperty` of a `justsearch.*`/`JUSTSEARCH_*` key
outside the `configuration` module (today ArchUnit only fences the `core` module). That is the **backend
analog of the §6′ capability ratchet** — same "one authority, enforced" move, different substrate.

### §12.C State machines & process supervision — AHA says LEAVE MOSTLY BESPOKE (LOW–MEDIUM)

- **State machines (2):** `ModeStateMachine` (inference OFFLINE/TRANSITIONING/ONLINE…) and
  `GrpcCircuitBreaker` (CLOSED/OPEN/HALF_OPEN). Different concurrency models (caller-sync vs lock-free
  atomics), different trigger models (command vs event). **AHA: do not unify** — they change for
  different reasons; a shared FSM base would be abstraction-for-its-own-sake. (The agent loop is a third,
  kernel-internal machine — 569 Move 8's territory, not 574's.)
- **Process supervision (2):** `LlamaServerOps` and `WorkerSpawner` share the *skeleton* (spawn →
  probe-until-ready → periodic monitor → N-fails→restart-with-backoff → max-restarts→give-up) but differ
  in the health probe (HTTP vs signal-bus port) and recovery policy (crash-count vs stability-window).
  **AHA nuance — the disciplined call:** *don't* extract a generic `Supervisor` (only 2 instances, real
  domain differences, safety-critical), **but** the **inner backoff/poll mechanism inside both should
  adopt §12.A's primitive** — consolidate the mechanism, leave the supervisor bespoke. This is the
  framework working: same concern at two scales, generalize only the layer that shares a reason to change.

### §12.D The enforcement-substrate refinement to §11.A

The backend sweep refines the §11.A ladder with a finding the FE-only view missed: **the *enforcement
substrate* for a given mechanism differs by language/tier.** Mechanism 2 (authority-funnel) is enforced
by a **lint ratchet** on the FE (`check-message-single-model`) but by **ArchUnit** on the backend
(`coreMustNotReadEnvOrSystemProperties` → the proposed `justsearch.*`-namespace fence). Mechanism 3
(factory) is `createRegistry`/`OverflowController` (TS) and would be a `Backoff`/`PollUntilReady` package
(Java). The *mechanism ladder is language-agnostic; the enforcement tool is not.* When recommending a
fix, name **both** the mechanism (what to build) and the substrate (what enforces it: hook/lint/ArchUnit/gate).

### §12.E B-v / enablement — quick notes (not yet swept deeply)
- **B-v dual-copies:** `fields.v1.json` is the known one (gate-held). A full census of *other* tolerated
  classpath/SSOT dual-copies is owed but out of this round's scope.
- **Generalization-as-enablement (the §11.B orthogonal axis):** the backend already holds the two biggest
  exemplars — **530** (gates → a kernel that makes new gates cheap) and the **registry/projection** spine
  (553/564). The retry primitive (§12.A) is cleanup; the enablement-grade sibling would be a **declared
  resilience policy** (per-call-site policy as data the primitive reads) — flagged, not designed here.

## §13 Where the defect class stands, end-to-end (FE + BE)

| Altitude / area | Best finding | Mechanism | AHA | Enforcement substrate | Grade |
|---|---|---|---|---|---|
| FE resource | responsive store (F1) | 1 singleton | ✓ | — | clean small win |
| FE capability | clipboard/locale/storage/HTTP/format | 2 funnel | ✓ | lint ratchet | open |
| FE substrate | `createStore` (§9.A) | 3 factory | ✓ | (mirror createRegistry) | high leverage |
| FE behavioral | `DismissController` (B-i) | 3 factory | ✓ (a11y) | (mirror OverflowController) | **under-valued** |
| FE render | `jf-empty-state`/`jf-error-alert` (§9.B) | 4 projection | ✓ | (extend SurfaceLayout) | high leverage |
| **FE visible atoms** | `jf-badge`/`jf-chip`/`jf-status-dot`/`jf-button` (§14) | 4 projection | ✓ (atoms only) | shared primitive + 569 vocab | **high + enablement** |
| FE concept | inspector-answer fork (§9.C) | 4 projection | ✓ | 565 register | mostly solved |
| **BE resilience** | `Backoff`/`PollUntilReady` (§12.A) | 3 factory | ✓ (parameterise) | new Java package | **standout** |
| **BE config** | `justsearch.*` ArchUnit fence (§12.B) | 2 funnel | ◧ | **ArchUnit** | medium |
| BE lifecycle | state machines / supervisors (§12.C) | 3 (inner only) | ✗ outer / ✓ inner | — | leave bespoke |

**The end-to-end thesis:** "single concern, plural implementation" is a *whole-system* class, not a FE
quirk. It is governed wherever the project has pointed its single-authority engine (`createRegistry`,
`SurfaceLayout`, `evidenceProjection`, 530-kernel, ArchUnit) and still-forked wherever it hasn't. The
two highest-leverage *new* targets this whole investigation surfaced are **FE B-i (`DismissController`,
a11y-bearing, proven pattern)** and **BE §12.A (`Backoff`/`PollUntilReady`, bug-and-flake-reducing,
proven pattern)** — both mechanism-3 extractions with a direct in-repo precedent, both passing AHA by
*parameterising the policy and sharing only the mechanism*.

## §14 Frontend *visible* elements — the atom gap (2026-06-10)

A census of the **rendered UI vocabulary** (what the user actually sees — buttons, badges, chips,
status dots, cards, banners, charts) through the §11.A framework. The shared vocabulary is **~95
`jf-*` custom elements**. The finding is not "no vocabulary" — it is a specific *shape* of gap.

### §14.A The diagnosis — a rich MOLECULE tier over a thin ATOM tier

The `jf-*` vocabulary is mature at two tiers and thin at a third:
- **Operability tier — solved.** `jf-control` (559 Authority V) is the one interactive-affordance
  primitive: native `<button>` + role/keyboard/accessible-name projected from a declaration; the
  `controls-a11y` gate bans handlers off it. **But — verified in source — it *deliberately delegates
  appearance to the consumer*** ("Appearance is the consumer's, not the primitive's… exposed as
  `part="control"`"). So it guarantees *operability*, explicitly **not** *visual consistency*.
- **Molecule tier — rich (and correctly bespoke).** Many *specialized* visible components:
  `jf-provenance-badge`, `jf-provenance-chip`, `jf-capability-pills`, `jf-advisory-rail-badge`,
  `jf-source-chips`, `jf-tool-call-card`, `jf-citations-panel`, `jf-status-card`, `jf-status-deck`,
  the whole JSON-forms control set (`jf-text-control`…). These are role-specific leaves — correctly
  hand-authored per 559 §8's "bespoke-leaf composition stays hand-authored."
- **Generic ATOM tier — MISSING.** There is **no** `jf-badge`, `jf-chip`, `jf-status-dot`,
  `jf-button` (a *skinned* button), or `jf-banner` — the uniform visual shells beneath the molecules.

**Because the atoms don't exist, every surface re-authors them as inline CSS.** Measured:
`class="…badge|chip|pill|tag|dot|btn|card|banner|alert"` appears **172×** across 37 files
(HealthSurface **48**, SettingsSurface **24**, BrainSurface 9, SearchSurface 9); raw `<button>` +
button-CSS appears **215×** across 53 files (SettingsSurface 26, EffectAuditLog 18, BrainSurface 17).
This is the **design-system maturity gap: molecules without atoms** — and it's the *visible* analog
of §9.A/§9.B (a uniform shell re-authored per instance), concentrated in the atom layer.

### §14.B Findings (framework-graded)

- **V1 · Generic badge / chip / status-dot atoms (mechanism 4; HIGH).** The 172 inline
  `.badge`/`.chip`/`.pill`/`.dot` re-rolls are one uniform atom re-authored per surface. Build
  `jf-badge`/`jf-chip`/`jf-status-dot` and have the *specialized* molecules (`jf-provenance-badge`
  etc.) **compose** them. The status-dot specifically should **project `statusTone` (565 §3.B)** →
  a dot whose colour is the semantic tone, so "a status dot with an off-palette colour" becomes
  unrepresentable. AHA ✓ (a badge is a uniform shell; its appearance changes together).

- **V2 · Button *visual* authority (mechanism 4; HIGH, soft enforcement).** 215 bespoke button
  styles exist because `jf-control` solves operability but — *by design* — not appearance. A skinned
  `jf-button` atom that **composes `jf-control`** (operability for free) + the theme tokens would give
  one button look. AHA ✓ for *appearance*. **Enforcement nuance:** native `<button>` is legitimately
  a11y-valid (the `controls-a11y` gate permits it), so this can't be a hard ban like the capability
  ratchets — it's a soft adoption nudge, not a fail-the-build rule. Name the substrate honestly: lint
  *hint*, not gate.

- **V3 · Two overlapping sparklines (mechanism 3; LOW-MEDIUM).** *Corrected from a first read of
  "3 chart forks."* `jf-timeseries-polyline` (Layer 4a, pure SVG) + `jf-timeseries-sparkline`
  (Layer 4b, consumes `TimeseriesSnapshot`, delegates to the polyline) are a **clean, deliberate
  2-layer split — not a fork.** The genuine overlap is the *older standalone* `jf-sparkline` (508),
  which the polyline's own doc notes supersedes a retiring React `Sparkline.tsx`. Fold `jf-sparkline`
  into the layered pair. AHA ✓ (both draw a polyline sparkline); modest scope.

- **V4 · Banner / notice / alert visible rendering (mechanism 4; governed-adjacent).** `jf-system-notice`
  is the intended notice primitive and 559's `message-single-model` gate governs the *channel* — but
  surfaces still inline `.alert`/`.banner` markup (overlaps §9.B's 7 bespoke error banners). The
  *visible* render should compose `jf-system-notice` / a `jf-banner` atom, not re-style `.alert` per
  surface. Folds into the §9.B `jf-error-alert` work.

### §14.C The framework insight specific to visible elements

The AHA cut lands at a **tier boundary**, cleanly: *generalize the ATOMS, leave the MOLECULES bespoke.*
The atoms (badge, chip, dot, button-skin, banner) are uniform shells that change together (✓ generalize,
mechanism 4); the molecules (provenance badge, tool-call card, citations panel) are role-specific leaves
that 559 §8 *deliberately* leaves hand-authored (✗ don't unify). The visible-element duplication is
real but it is **not** an argument against the molecule bespoke-ness — it is an argument for the
**missing atom layer the molecules and surfaces should both compose.**

**This is also the §11.B *enablement* axis, not just cleanup.** 569 Move 3 calls for a "closed,
generated presentation-component vocabulary" the user-authored Presentation Declaration can reference.
That vocabulary *requires the atom tier to exist as enumerable primitives.* So building `jf-badge`/
`jf-chip`/`jf-status-dot`/`jf-button`/`jf-banner` (V1/V2/V4) is simultaneously the dedup fix **and** the
prerequisite for 569's user-authored-frontend enablement — the highest-value kind of generalization
(collapses duplication today *and* unlocks a capability tomorrow).

## §15 Worked example — the scrollbar: a *false global* that invites the per-window fork (2026-06-10)

This is the concrete case that motivated the whole doc, and it is the **sharpest single instance** —
it shows a failure mode subtler than "forked from the start": a concern *defined* globally that is
**not global at runtime**, which then *invites* the per-window fork.

**Verified state (current branch):**
- Scrollbar styling exists in exactly **one** place — `styles/tokens.css:674-700` — using
  `::-webkit-scrollbar*` pseudo-elements only. No `scrollbar-width`/`scrollbar-color` anywhere.
- `tokens.css` is a **light-DOM document stylesheet** (`main.jsx:60` `import './styles/tokens.css'`).
- Every `shell-v0` surface/panel is a `LitElement` with its **own shadow root**; there is **no
  `adoptedStyleSheets`** injecting the global sheet into shadow roots; `surfaceLayout.ts` sets
  `overflow-y:auto` (`:93,:104`) but **does not style the scrollbar**.

**The trap — why "global" isn't global here.** `::-webkit-scrollbar` is a pseudo-element whose rules
are **shadow-scoped**: a rule in the document sheet styles only **light-DOM** scrollers; it does **not**
cross into a component's shadow root. Since essentially every scroll container (the surfaces' `.body`,
drawers, the conversation, lists) renders **inside a shadow root**, the tokens.css rule reaches **almost
none of them** — they fall back to the **default browser scrollbar**. The one declaration *looks*
centralized in code but is a **false global**: it applies where the app barely scrolls and misses where
it actually does. (Contrast: the colour tokens reach shadow DOM fine — they're CSS **custom properties**,
which are *inherited* and cross shadow boundaries. `::-webkit-scrollbar` is a pseudo-element, which is not.)

**Why agents then code it per-window (the forking dynamic).** An agent opens a surface, sees an ugly
default scrollbar (the global rule didn't pierce its shadow root), and "fixes" it by adding
`::-webkit-scrollbar` to *that surface's* `static styles`. It works locally — and is now: (a) duplicated
per surface (the §9/§14 fork at its purest); (b) value-drifted (6px here, 8px there; hardcoded `rgba`
instead of tokens — bypassing the theme/567); (c) a forever-tax (every new surface must remember it).
This is the exact behaviour you observed. *The per-window code is the wrong fix to a real problem the
false-global created.*

**The correct generalization (mechanism 4 — a co-projected theme facet, not a per-surface style).**
Use the **standard inherited properties**, token-driven, set once at the root:
```css
:root { scrollbar-width: thin; scrollbar-color: var(--scrollbar-thumb) var(--scrollbar-track); }
```
`scrollbar-width`/`scrollbar-color` are **inherited** properties, so they **cross shadow boundaries by
the platform's inheritance** — one declaration reaches every shadow-DOM scroller automatically, with no
per-component opt-in. This is *structurally* global (platform inheritance), unlike a shared `css`
fragment every component must import (convention-global — the "remember to register" anti-pattern 557
warns against) or `adoptedStyleSheets` on a base class (centralized but still opt-in). **JustSearch ships
in Tauri → WebView2/Chromium (a single known engine)**, so the historical reason to prefer
`::-webkit-scrollbar` (Safari/legacy) is moot and standard properties are fully supported — the cleanest
path is available. Drive the two values from theme tokens so the scrollbar is a co-projected facet of the
567 theme authority, dark/light-aware for free.

**Enforcement substrate (§12.D framing).** A lint that **forbids `::-webkit-scrollbar` (and bare
`scrollbar-color`/`scrollbar-width`) inside component `static styles`** — it belongs only in the root
token sheet — is the same ratchet shape as the §6′ capability rules. That converts "remember not to
style scrollbars per-surface" from prose to a near-100% guard, and makes the per-window fork
*unrepresentable*.

**What I think (the verdict).** Your instinct is exactly right, and the scrollbar is the *canonical*
specimen of this whole investigation: it is a **visible atom** (§14) + a **theme facet** (567) — globally
uniform, clearly shares-a-reason-to-change (AHA ✓) — that was defined with the one CSS mechanism shadow
DOM scopes out, producing a false global that *manufactures* per-window forks. The fix is small, strictly
better (consistent scrollbars everywhere, theme-driven, dark/light-aware), and removes the temptation
structurally. Priority: **HIGH for how cheap it is** — a ~3-line root declaration + token pair + one lint,
deleting an entire latent fork-class and fixing a real visible inconsistency. It is the most clear-cut
"do this now" item the doc surfaced.

## §16 Theory — the shadow-DOM false-global family (the scrollbar's siblings) (2026-06-10)

The scrollbar (§15) is not a one-off; it is the visible tip of a **family**, and the lens it
crystallised is *predictive*: **a concern is a false-global candidate iff it is (1) globally uniform
AND (2) expressed through a CSS mechanism that does not cross the shadow-DOM boundary.** Since this app
is ~95 shadow-rooted Lit components with one light-DOM document sheet (`tokens.css`) and **no
`adoptedStyleSheets`**, the boundary is the silent global-breaker. The boundary partitions every
globally-uniform visual/coordination concern into three classes:

### §16.A The three classes (the framework the scrollbar revealed)

- **Class A — crosses by inheritance → a global is trivial and usually already correct.** CSS *custom
  properties* (`--color-*`, `--density-*`), `color`, `font`, `line-height`, `cursor`, and — the
  scrollbar's fix — `scrollbar-width`/`scrollbar-color`. These flow through the flattened tree, so one
  `:root` declaration reaches every shadow root. *The fix for most Class-B false-globals is "find the
  Class-A inherited-property equivalent," exactly as the scrollbar's fix did.*
- **Class B — does NOT cross; a document-sheet rule is a FALSE GLOBAL.** Pseudo-elements
  (`::-webkit-scrollbar`, `::selection`, `::placeholder`), `@keyframes`, and selector-based rules
  (`:focus-visible`/`outline`, utility classes, `.visually-hidden`, resets/`box-sizing`). Looks
  centralized in code, applies to almost nothing at runtime, and *manufactures* per-component forks.
- **Class C — not a cascade concern at all; inherently global COORDINATION → needs a runtime authority.**
  z-index/stacking order, scroll-locking on overlay open, "only one transient open" arbitration, focus
  ownership/restoration across overlays, the browser Top Layer / reserved trusted channel. CSS cannot
  express these; the only correct global is a *runtime layer/coordination authority*.

### §16.B The specimens (spot-checked 2026-06-10; ✓confirmed / ⚠predicted)

| # | Specimen | Class | Status | Evidence | Confidence |
|---|---|---|---|---|---|
| S0 | **Scrollbar** | B→A | ✓ false-global | `tokens.css:674-700` webkit-only, light-DOM | HIGH (§15) |
| S1 | **`::selection`** | B | ✓ false-global, **same file as scrollbar** | `tokens.css:670,703` — light-DOM only; shadow text gets UA default | HIGH |
| S2 | **Spinner/pulse `@keyframes`** | B | ✓ **already forked ×13** | `spin`/`jf-spin` ≥5 copies (IndexingOverlay/BrowseSurface/HelpSurface/LibrarySurface/BrainSurface), `pulse` ≥5 (Shell/HealthSurface/BrainSurface/PulseDots/RunNode) | HIGH — *worse than the scrollbar* |
| S3 | **`::placeholder`** | B | ✓ already per-component | `CommandPalette.ts:195` styles it locally (a global wouldn't reach it) | MEDIUM-HIGH |
| S4 | **z-index scale** | C | ✓ authority exists but bypassed | `OverlayHost` owns a 900–2000 scale; yet ContextMenu/Peek/BookmarksPopover `9500`, ConfirmDialog/SelectionActionsMenu/HoverPreviewHost `9999`, ResolutionToast `10000` | HIGH |
| S5 | **Focus ring (`:focus-visible`/`outline`)** | B | ✓ scattered, a11y-bearing | `outline:`/`:focus-visible` across 10 files; a global ring rule can't reach shadow focusables | MEDIUM-HIGH |
| S6 | **`.visually-hidden` / sr-only** | B | ⚠ likely re-inlined | a11y clip utility cannot be a global class across shadow roots | MEDIUM |
| S7 | **Reset / `box-sizing` / `:host` defaults** | B | ⚠ likely per-component | `*{box-sizing}` in document doesn't reach shadow DOM; each component re-sets | MEDIUM (low visible) |
| S8 | **Text-truncate / flex-center CSS utilities** | B | ✓ re-inlined | `overflow:hidden;text-overflow:ellipsis` repeated (SearchSurface ×5) — utilities can't be shared classes across shadow DOM | MEDIUM (pervasive, low-severity-each) |
| S9 | **Scroll-lock on overlay open** | C | ⚠ predicted forked | each modal likely toggles body/host scroll itself; no shared scroll-lock authority | MEDIUM |
| S10 | **"Only one transient open" arbitration** | C | ◧ partial | `rightDrawerArbiter` covers drawers; menus/popovers/tooltips/Peek likely uncoordinated (two can open at once) | MEDIUM |
| S11 | **Motion timing/easing** | A-if-tokenised | ⚠ value-fork | `transition: …ms ease` hardcoded per component; 569 names "motion" as a seed — should be a token | MEDIUM |

### §16.C What the family teaches (beyond the individual fixes)

1. **The scrollbar was a *symptom of a seam*, not a bug.** `tokens.css` holds the app's "global" visual
   intent, but every Class-B rule in it (scrollbar, selection) is a false-global, and every Class-B
   concept *not* in it (keyframes, focus ring) is forced into per-component forks. The seam — *one
   light-DOM sheet + 95 sealed shadow roots + no adopted stylesheet* — is the root cause of an entire
   duplication family. **The highest-leverage move is to fix the seam, not the symptoms:** introduce a
   **single shared constructable stylesheet adopted into every component** (one base class or a Lit
   mixin doing `adoptedStyleSheets`), so Class-B globals (keyframes, focus ring, utilities, selection,
   placeholder) have *one* real home — and prefer the Class-A inherited-property form (scrollbar) wherever
   one exists.
2. **It spans all three altitudes of this doc at once:** S1/S2/S3 are *visible atoms* (§14), S5/S6 are
   *a11y/operability* (559), S11 is a *theme facet* (567), S4/S9/S10 are *runtime coordination* (the
   OverlayHost / 569 Move 4 reserved layer). The scrollbar is the rare specimen that makes the whole
   taxonomy legible in one concrete artifact.
3. **Enforcement (§12.D framing):** a lint forbidding Class-B constructs (`::-webkit-scrollbar`,
   `::selection`, `@keyframes`, raw `z-index` magic numbers outside the OverlayHost scale) inside
   component `static styles` — pushing them to the shared sheet / token scale — is the ratchet that makes
   the per-window fork *unrepresentable*. The `@keyframes`-×13 and z-index-war rows are the strongest
   evidence the ratchet earns its keep.
4. **Predictive power = the deliverable.** The lens ("globally uniform × shadow-non-crossing mechanism")
   lets a reviewer *predict* the next false-global before it forks — the same way the register-gates in
   553/559 make a drift class unrepresentable. That predictive test, not the individual fixes, is §16's
   contribution.

## §17 Per-specimen analysis (verified 2026-06-10) — state · class · correct mechanism · enforcement

Each §16 specimen given the §15 (scrollbar) treatment. The actionable cut is the **correct mechanism**,
which is one of three: **A** = a Class-A inherited/custom-property form (structurally global by platform
inheritance — *preferred wherever one exists*); **SHEET** = a single shared constructable stylesheet
adopted into every component (the §16.C seam fix — for Class-B concepts with no Class-A form); **RUNTIME**
= a runtime coordination authority (Class C). Verification refuted one prediction (S9) — recorded.

### Group 1 — has a Class-A form → use it (structurally global, strongest)

- **S0 Scrollbar.** State: `tokens.css` webkit-only, light-DOM (false-global); shadow scrollers = UA default.
  **Mechanism A:** `:root{scrollbar-width:thin;scrollbar-color:var(--scrollbar-thumb) var(--scrollbar-track)}`
  — inherited, crosses boundaries. Enforcement: lint bans `::-webkit-scrollbar` in component `static styles`. (§15.)
- **S4 z-index.** State: `OverlayHost` (chrome/OverlayHost.ts) is a **correct authority** — a documented
  scale (900/1000/1100/1500/2000/3000), slot-docking that drops the overlay's own positioning, Top-Layer
  for popovers (`AuthorizationHost`/`CommandPalette`/`ProvenanceBadge`). The defect is **un-migrated
  overlays** still hardcoding magic numbers: `ContextMenu`/`Peek`/`BookmarksPopover` `9500`,
  `ConfirmDialog`/`SelectionActionsMenu`/`HoverPreviewHost` `9999`, `ResolutionToast` `10000`,
  `CitationHoverCard`/`ConversationHistory` `100`. **Mechanism — two parts:** (a) **RUNTIME** — migrate
  those overlays into OverlayHost slots or the Top Layer (the authority already exists; this is adoption,
  not new design); (b) **A** — expose the z-scale as `--z-float/-chrome/-transient/-modal/-top` custom
  properties (cross shadow DOM) so any legitimate in-shadow stack references a token, never a literal.
  Enforcement: lint bans raw `z-index:` integer literals outside OverlayHost + the `--z-*` tokens (the
  §16.B z-index-war is the evidence this earns the ratchet). AHA ✓ (stacking order is one global ordering).
- **S11 Motion (timing/easing).** State: 62 `transition:`/`animation:` across 27 files, mostly hardcoded
  `…ms ease`; a few `--motion/--ease` tokens exist (`token-names.generated.ts`) but are largely unused →
  value-fork. **Mechanism A:** route all transitions through `--motion-fast/-base/-slow` + `--ease-*`
  custom-property tokens (569's "motion" seed; crosses shadow DOM). Enforcement: lint flags bare
  `transition`/`animation` durations outside the token set. AHA ✓ (motion is a theme facet). MEDIUM.

### Group 2 — no Class-A form → the shared adopted stylesheet (the seam fix)

These are Class-B pseudo-elements / selectors / keyframes with **no inherited-property equivalent**, so
the one correct global is a **single shared constructable stylesheet adopted into every component** (one
base class or Lit mixin doing `static styles = [sharedSheet, …]` / `adoptedStyleSheets`). Building that
sheet once *simultaneously* fixes all of S1/S2/S3/S5/S6/S7/S8 — which is why §16.C calls the **seam** the
highest-leverage target.

- **S1 `::selection`.** State: ✓ false-global (`tokens.css:670,703`, light-DOM only) — shadow text shows
  the UA selection colour; no component re-styles it, so the app's selection highlight is silently absent
  in most of the UI. No Class-A form (selection styling is a pseudo-element). **Mechanism SHEET:** put the
  `::selection` rule (token-driven) in the shared sheet. HIGH.
- **S2 `@keyframes`.** State: ✓ **forked ×13.** `spin`/`jf-spin` (rotation) is a **verbatim** fork ≥5×
  (IndexingOverlay/BrowseSurface/HelpSurface/LibrarySurface/BrainSurface) — consolidate confidently;
  `pulse` is ≥5× but **diverged** (e.g. `PulseDots` pulses *opacity* 0.3→1; others pulse scale/glow) — so
  fold into a **small named motion-keyframes set** in the shared sheet (`spin`, `pulse`, `cursor-blink`,
  `toast-in/out`), *verifying* the pulse variants are meant to be one before merging (AHA: don't merge
  divergence). **Mechanism SHEET.** HIGH — the strongest dedup count in the whole doc.
- **S3 `::placeholder`.** State: ✓ styled per-component (`CommandPalette:195`); other inputs (search,
  composer, settings) leave the default → inconsistent placeholder colour. No Class-A form.
  **Mechanism SHEET** (or style once in a shared input atom, §14). MEDIUM-HIGH.
- **S5 Focus ring (`:focus-visible`/`outline`).** State: scattered across ~10 files; a11y-bearing — a
  document `:focus-visible` rule can't reach shadow focusables, so the ring is inconsistent/absent inside
  shadow DOM. **Mechanism: SHEET + A** — a shared `:focus-visible{outline:var(--focus-ring)}` rule in the
  adopted sheet (selector must live in each root) with the *value* as a `--focus-ring` token (Class-A).
  `jf-control` should own it for controls (559 operability); the sheet covers non-control focusables.
  Enforcement: extend the `controls-a11y` gate. MEDIUM-HIGH (a11y).
- **S6 `.visually-hidden` / sr-only.** State: ⚠ the multi-prop clip utility cannot be a shared class
  across shadow roots → re-inlined where used. **Mechanism SHEET** (one `.visually-hidden` in the sheet).
  a11y-bearing. MEDIUM.
- **S7 Reset / `box-sizing`.** State: ⚠ `*{box-sizing}` in the document doesn't reach shadow DOM; each
  `:host` re-sets it. **Mechanism SHEET** (`:host{box-sizing:border-box}` once). MEDIUM (low visible).
- **S8 Text-truncate / flex-center utilities.** State: ✓ `overflow:hidden;text-overflow:ellipsis`
  re-inlined (SearchSurface ×5, …). **Mechanism SHEET** (shared `.truncate`/`.row-center` utilities) or a
  `::part`-exposed atom. MEDIUM (pervasive, low-severity-each).

### Group 3 — Class-C runtime coordination (not CSS at all)

- **S4(a)** overlay-slot migration — see Group 1 (RUNTIME half; authority = OverlayHost, exists).
- **S9 Scroll-lock on overlay open. CORRECTED — not a fork; an ABSENCE.** Verification: the only
  `document.body.style` writes are in **demo harnesses**; there is **no production scroll-lock and no
  `inert`** anywhere. So background scroll under a modal is likely **not locked** and the background is
  **not marked `inert`** (focus can escape behind a modal — an a11y gap). This is a *missing* global
  concern, not a duplicated one. **Mechanism RUNTIME:** if/when addressed, add **one** `scrollLock()` +
  `inert`-the-background service invoked by the modal broker (`AuthorizationHost`/`ConfirmDialog` path) —
  added once, never per-modal. Supersedes the §16.B S9 "predicted forked" row. MEDIUM (latent UX/a11y gap).
- **S10 "Only one transient open" arbitration.** State: ◧ partial — `rightDrawerArbiter` coordinates the
  right-drawer panels, but menus/popovers/tooltips (`ContextMenu`/`BookmarksPopover`/`SelectionActionsMenu`/
  `Peek`/`HoverPreviewHost`) each hand-roll their own outside-click dismiss (the B-i triad) with **no
  shared arbiter** — so two can be open at once. **Mechanism RUNTIME:** generalize `rightDrawerArbiter`
  into a **transient-layer arbiter** the `DismissController` (B-i) registers with (opening one closes the
  others). This is where §16 (false-global), B-i (dismiss triad), and S10 converge on one primitive.
  MEDIUM.

### §17.A The synthesis — three fixes cover eleven specimens
The per-specimen analysis collapses to **three pieces of work**, ordered by leverage:
1. **The shared adopted stylesheet (SHEET)** — one base class / mixin; lands S1, S2, S3, S5, S6, S7, S8
   at once. *The single highest-leverage FE item in the whole doc* (one seam fix, seven specimens).
2. **Class-A token forms (A)** — `--scrollbar-*` (S0), `--z-*` (S4b), `--focus-ring` (S5 value),
   `--motion-*`/`--ease-*` (S11); each a few `:root` lines, structurally global by inheritance.
3. **Runtime authorities (RUNTIME)** — adopt the *existing* OverlayHost for the magic-number overlays
   (S4a), generalize the *existing* `rightDrawerArbiter` into the transient-layer arbiter (S10), and add
   the *missing* `scrollLock`/`inert` service (S9). Mostly adoption of authorities that already exist.

Plus **one ratchet** (the §12.D enforcement substrate): a lint banning Class-B constructs
(`::-webkit-scrollbar`, `::selection`, `@keyframes`, raw `z-index`/`transition` literals) inside component
`static styles`, pushing them to the shared sheet / token scale — which makes the per-window fork
*unrepresentable* and is the durable guarantee the scrollbar incident asked for.

## §18 Intensity assessment — the issue class measured against the whole FE (2026-06-10)

How *intense* is this area of issues across the FE? Measured, then calibrated (separating "contains the
construct" from "is a genuine defect"), then a verdict that resists both catastrophising and minimising.

### §18.A The denominators (whole-FE size)
- **429** non-test, non-generated `.ts/.tsx` files; **88,261 LOC** in `modules/ui-web/src`.
- **327** files under `shell-v0` (the live shell); **~94** components author their own CSS-in-JS
  (`static styles =`) — *this is the population the visible/shadow-DOM family lives in*; 22 surfaces, 69
  components, plus chat/renderer/overlay components.

### §18.B The footprint (blast radius) per domain
| Issue domain (this doc) | Footprint | Of denominator | Reading |
|---|---|---|---|
| **Visible / shadow-DOM family** (§14–§17: badge/chip/button CSS, `@keyframes`, `z-index`, `::selection`/`::placeholder`, scrollbar, transition) | **71 files** carry ≥1 construct | **71 / 94 self-styling components ≈ 76%** | **HIGH footprint** |
| Capability bypass (§7.B) | storage 39, fetch 23, format ~40, clipboard 6 files | ~10–12% of 429 (logic layer) | MEDIUM |
| Store micro-substrate (§9.A) | 13 hand-rolled stores | concentrated (architectural) | LOW footprint / HIGH leverage |
| Render scaffolding (§9.B) | empty 7, error 7, loading 5 surfaces | 7 / 22 surfaces ≈ 32% | MEDIUM |
| Behavioral triad (B-i) | ≥5 overlay components | ~7% of components | MEDIUM (a11y) |

### §18.C Calibration — construct prevalence ≠ defect prevalence
The 76% is the raw "touches the family" rate. It **overstates** genuine defect intensity, for reasons I
state honestly:
- Of the 71, **~6 are the *authorities*, not forks** (`OverlayHost`, `surfaceLayout`, `Control`,
  `SystemNotice`, the demos) — they legitimately contain the constructs.
- `transition:` and `<button>` are caught by the union but are **often legitimate** (a token-driven
  transition; a native button is a11y-valid per `controls-a11y`).
- Discounting both, the **genuine per-window-fork prevalence is roughly half-to-two-thirds of
  self-styling components (~50–65%)** — still a *clear majority-or-near-majority*, but not 76%.

Counterweight — the *multiplicity* is high where it bites: `@keyframes` `spin`/`pulse` ×13, inline
badge/chip `.css` 172× across 37 files, button CSS 215× across 53, z-index magic numbers ×9. So the
*concepts* are few but each is forked 5–50×.

### §18.D Severity distribution — broad and shallow, not deep and dangerous
| Severity | Share | Examples |
|---|---|---|
| HIGH (correctness / data) | **~0%** | none — this class produces *inconsistency*, not crashes/data-loss |
| MEDIUM (visible inconsistency / a11y) | ~20% | scrollbar (S0), selection (S1), placeholder (S3), focus ring (S5, a11y), z-index war (S4), missing scroll-lock/`inert` (S9, a11y) |
| LOW (cosmetic / maintenance tax) | ~80% | keyframes dup, badge/chip/button styling, truncate/box-sizing utilities, transition values |

This is the defining shape: **a pervasive *low-grade consistency tax*, not a cluster of severe bugs.**
Its harm is cumulative (scrollbars/selection/focus-rings/badges visibly differ window-to-window) and
*generative* (every new component re-pays it and may drift — and agents actively add to it, the scrollbar
incident).

### §18.E The decisive mitigant — single root cause + existing antibodies
Two facts cut the *effective* intensity far below the 76% footprint:
1. **One root cause.** The visible family (the 76%) traces to a *single architectural seam* — shadow DOM
   + one light-DOM `tokens.css` + **no adopted stylesheet**. 71 symptomatic files, **~3 fixes** (§17.A:
   the shared sheet + Class-A tokens + runtime-authority adoption). Footprint ≠ fix-count.
2. **The codebase is already mid-remediation, trending down.** `SurfaceLayout` (10/10 surfaces),
   `createRegistry` (8 registries), `OverlayHost` (z-scale authority), `evidenceProjection`, `jf-control`,
   `statusTone`, `rightDrawerArbiter` are *existing* single-authorities. The forks are the **un-migrated
   remainder of an in-progress consolidation**, not a worsening sprawl. The system has antibodies; what it
   lacks is the *ratchet* that stops new forks (no lint bans the Class-B constructs — so adherence is
   ~70% prose-tier, which is exactly why agents keep re-adding them).

### §18.F Verdict — intensity rating
**Moderate-to-high intensity, by footprint; low, by danger; and *declining*, by trend — a high-prevalence,
low-severity, structurally-concentrated, self-reproducing consistency tax.**

- **As a footprint:** **HIGH** — ~50–65% of self-styling components carry a genuine instance; it is the
  single most *widespread* issue area the investigation found (the capability/store/backend classes are
  each narrower).
- **As a risk:** **LOW** — ~0% correctness, ~20% visible/a11y, ~80% cosmetic. No fire; a tax.
- **As a trajectory:** **self-reproducing but cheaply arrestable** — one un-built guardrail (the Class-B
  lint + the shared sheet) converts it from "70%-adherence prose" to "unrepresentable," and the existing
  authorities mean the *correct* targets already exist to migrate onto.

**One-line characterization for the area:** *not a hotspot of severe defects, but the FE's most pervasive
low-grade drift surface — broad (most styled components), shallow (cosmetic/consistency, little
correctness), single-rooted (the shadow-DOM seam), and currently un-ratcheted, which is why it keeps
regenerating.* The intensity justifies the **seam fix + ratchet** (high prevalence × cheap single-rooted
fix = strong ROI), but **not** an emergency (low severity, already trending down).

## §8 Suggested sequencing (smallest correct steps first)
1. **F1 — `responsiveState` singleton** (cleanest win; copy `inferencePoll` shape) + migrate
   `UnifiedChatView.ts:371` and any other `matchMedia` site.
2. **B2 — route the 4 raw-clipboard sites through `clipboardCopy.ts`** (authority already exists).
3. **F2 — `getOperationClient(apiBase)` accessor**; migrate the 5 non-plugin sites.
4. **F3 — `BrainSurface` inference poller → subscribe to `aiStateStore`**.
5. **B1 — locale module** (`resolveLocale()` / a locale store); migrate `intlFormat`/`i18n`.
6. **Ratchet** the four capability rules (§6′) once their authorities exist.
7. **Out of first pass:** B3 (storage scatter — large), B4 (theme reads), §7.C (event buses → 559).

**Bigger elements (§9), independently shippable, higher leverage:**
8. **§9.A `createStore<T>`** — mirror `createRegistry`; migrate the independent hand-rolled
   stores incrementally (pilot: `uiModeState`, `searchFiltersState`), fixing the `searchState`
   no-isolation bug in the factory. Leave the 5 `UserStateDocument` projections + `aiStateStore`.
9. **§9.B `jf-empty-state` + `jf-error-alert`** render primitives — the content-state analog of
   `SurfaceLayout`; migrate the 7 bespoke empty-states and 7 bespoke error-banners.
10. **§9.C** — hand the Inspector-answer fork decision to the 565 owner (fold vs explicitly scope-out).

**Priority read:** the single cleanest *small* win is **#1** (responsive store); the highest
*leverage* is **#8/#9** (they delete the most duplication and have the clearest in-repo precedent).

> **Part II supersedes this section.** §8 is a tactical reading of the design below; it is not the
> recommendation. The recommendation is the *structure* in Part II — §8's items are what adopting that
> structure looks like at the call-site, not a substitute for building it.

---

# Part II — The correct design (the center of gravity)

## §19 Completing the half-applied presentation kernel

> **Genre.** Design theory, per 557/559/569: the correct long-term *structure*, feasibility / sequencing /
> effort disregarded, major rewrites in scope. It **extends the proven kernel the codebase already runs**;
> it does not fork a new one. Part I is its proof-by-example.

### §19.1 The diagnosis, in the kernel's own terms
The codebase runs one kernel (548/557/559/561/564): **a single-authority declaration → a governed
projection → a register-backed anti-drift gate**, with the prevention ladder **Collapse > Unrepresentable >
Generate > Gate** (548 §2). 559 states the presentation-specific law precisely and it **is Part I's finding
generalized**: *properties that flow through a typed value never drift; properties hand-authored as DOM/CSS
in a Lit template always drift, and a template-grep is the strongest check they admit* — "the kernel is
**half-applied** to presentation." The kernel today projects the **per-element FACET** (`present()` label,
`token()` colour, `landmarks` role, `jf-control` operability, `OverflowController` adaptivity). Part I's
entire defect class lives in the three places the kernel does **not** reach:

1. **The element ATOM (the body).** 559 §8/§14 *deliberately* bounded the engine at the facet — "the engine
   owns the *cross-cutting element classes* … it does **not** own *layout composition*" — and assumed
   bespoke content "**composes projected elements** — its buttons, its status chips … are projected." Part I
   §14 found the catch: **those atoms were never built.** There is no generic `jf-badge`/`jf-chip`/
   `jf-status-dot`/`jf-button`-skin, so the "projected element" a molecule should compose does not exist, and
   the body is re-authored inline (172 badge/chip, 215 button CSS). *The kernel's own stated end-state
   presupposes an atom tier that is missing.*
2. **The tree-wide AMBIENT.** Scrollbar, selection, focus-ring, keyframes, motion, stacking, modality are
   **not per-element facets at all** — they are properties of the *whole tree*. The per-element authorities
   never named this category, so it has no owner; §16's false-global family (S0–S11) is its symptom.
3. **DELIVERY into shadow DOM.** Projections reach a component's shadow root **only** via custom-property
   inheritance; there is **no adopted stylesheet** (verified, §15). So any ambient concept without a Class-A
   inherited form (keyframes, `::selection`, focus ring) *cannot physically be made global* — the
   false-global, structurally.

### §19.2 The thesis (one move)
**Complete the kernel across the ATOM and AMBIENT tiers, and give it a real delivery substrate into every
shadow root — so that "presentation authored by hand at a call-site" (the per-window fork, the false-global,
the atom re-roll) is unrepresentable by construction at every tier.** This is *finishing the kernel 559
named half-applied*, not a parallel framework. The same shape the codebase has proven repeatedly — *single
authority + an unrepresentable subordinate form* — extended from the facet to the element and the ambient.

### §19.3 The four structural moves

**Move 1 — The delivery substrate (the enabling root).** One **projection-owned ambient stylesheet** (a
constructable `CSSStyleSheet`) **adopted into every component's shadow root by the mint path** — the
`customElements.get(tag) → construct` membership seam 559 already owns, via a base `JfElement` / Lit mixin
the engine controls. The ambient authority (Move 2) is its sole writer, exactly as `applyAppearance`
(`themeState.ts:142`) is the sole writer of `:root`. **Result:** "a component that does not receive the
projected ambient presentation" is *unrepresentable* — you cannot define a `jf-*` element outside the mint,
so you cannot escape the sheet. This is the genuinely-new substrate (and the structural root-cause fix for
§15/§16); everything else rides it.

**Move 2 — The ambient-presentation authority (the new facet category).** A typed `AmbientDeclaration`
naming the tree-wide facets (scrollbar, selection, placeholder, focus-ring, keyframes/motion, stacking-order,
modality, reset/box-model, the sr-only + truncate utilities). Each facet is **projected to its strongest
form**: the **Class-A inherited/custom-property form where the platform provides one** (extend 567's
`KNOWN_TOKEN_NAMES` + the `gen-token-names.mjs` codegen to the value domains it lacks — **motion**, **z-index**,
**typography**, **scrollbar** — so they inherit across shadow boundaries from `:root`), **else** the Move-1
adopted sheet (keyframes, `::selection`, `::placeholder`, `:focus-visible`, sr-only, reset — no inherited
form exists). **Result:** the only way to express an ambient concern is the declaration; a raw
`::-webkit-scrollbar`/`@keyframes`/`z-index` literal in a component is banned by the gate (the §16 Class-B
lint), coverage projecting from the ambient-facet catalog (557 §5.2). This is the genuinely-new *category*.

**Move 3 — The atom authority (build the tier 559 §14 presupposed / 569 Move 3).** A **closed, generated
vocabulary of visual primitives** — the atom tier of `jf-*` (badge, chip, status-dot, button-skin, banner,
…) — produced the way `KNOWN_TOKEN_NAMES` is generated for tokens and `component-vocabulary.generated.ts`
(`gen-component-vocabulary.mjs`, the seed) is for components. An atom **co-projects its invariant facets** —
operability via `jf-control`, tone via `statusTone` (565), value via `token()`, a11y by construction — so
they are not hand-authorable on it. **Molecules and surfaces COMPOSE atoms** (559 §14's "its status chips
are projected"); a raw styled visual element (`.badge`, a `<button>` with bespoke CSS) outside an atom is
unrepresentable (gate scans, coverage from the atom register). This *completes the element tier the facet
authorities stopped short of* — extension, not invention.

**Move 4 — The runtime-modality authority (the Class-C coordination the cascade can't express).** Generalize
the existing `OverlayHost` (z-scale + slots) + `rightDrawerArbiter` (single-drawer) into **one layer /
modality / exclusivity authority** owning: **stacking** (z as Move-2 tokens + slot docking — mostly built),
**modality as one contract** (scroll-lock + background-`inert` + focus-trap + focus-restore *bundled*, so
"an overlay that traps stacking but leaks focus / doesn't lock scroll / doesn't `inert` the background" is
unrepresentable — closes S9 + the focus gaps), and **single-transient-exclusivity** (the B-i
`DismissController` registers with it, so two popovers/menus/tooltips open at once is unrepresentable —
closes S10). Extends two existing authorities into the one they were each a fragment of.

### §19.4 The extend-don't-invent ledger
| Move | Extends (exists) | Genuinely new? |
|---|---|---|
| 1 Delivery substrate | the `customElements.get→construct` mint (559 §2); `applyAppearance` one-writer pattern (567) | **NEW substrate** (the adopted sheet into shadow DOM) — the one structural addition |
| 2 Ambient authority | the 567 token system + `gen-token-names.mjs` codegen + `@layer` + `isSafeTokenValue` | **NEW facet category**; the *mechanism* is reused, the *category* (ambient) is new |
| 3 Atom authority | `gen-component-vocabulary.mjs` / `ComponentTag` (569 Move 3 seed); `jf-control`; `statusTone`; `token()` | builds the **named-but-unbuilt** 569 Move 3 / 559 §14 tier — not new design |
| 4 Modality authority | `OverlayHost` (z+slots), `rightDrawerArbiter`, the B-i `DismissController` | **merge** of existing fragments into their common authority |

Only **Move 1 (delivery)** and **the *category* in Move 2 (ambient)** are structurally new; Moves 3–4 are
the build-out / merge of things the codebase already named or fragmented. This is the "finish the kernel"
claim made literal.

### §19.5 Prevention — the 548 ladder, per tier
For every breaking form, climb to the strongest rung:
- **Collapse / Unrepresentable (preferred):** atoms (Move 3) make a hand-styled badge/button inexpressible;
  ambient tokens + the adopted sheet (Moves 1–2) make a per-component scrollbar/keyframe/focus-ring
  inexpressible; the modality contract (Move 4) makes a half-wired modal inexpressible. The fork is *not a
  thing you can write*, not a thing a gate catches after the fact.
- **Generate:** the atom and ambient-token vocabularies are codegenned (extend the existing generators), so a
  new atom/facet joins the closed set mechanically.
- **Gate (backstop only):** the §16 Class-B lint (no `::-webkit-scrollbar`/`::selection`/`@keyframes`/raw
  `z-index`/`transition` literal in component `static styles`) and the atom-register scan, **with coverage
  projecting from the atom/ambient catalogs** (557 §5.2) so *future* atoms/facets are covered without anyone
  remembering to register. Gate is the floor, not the mechanism.

### §19.6 The AHA guard — what must NOT change
559 §14's boundary is load-bearing and **preserved**: *genuinely bespoke content — a chat transcript, a rich
visualization, a one-off form — stays a composed template; only its **arrangement** is bespoke.* The
generalization stops at the **atom and ambient tiers** (uniform, single-reason-to-change). The **molecule**
(provenance badge, tool-call card, citations panel) and its **layout composition** stay hand-authored — the
atom/molecule line is the precise AHA cut (Part I §14.C). Over-projecting bespoke content into a catalog
would manufacture the over-DRY 559 §15 already walked back. *Generalize the shells; never the arrangements.*

### §19.7 Specimen → move resolution (the design subsumes the whole class)
| Part I finding | Resolving move |
|---|---|
| S0 scrollbar, S1 selection, S2 keyframes, S3 placeholder, S5 focus-ring, S6 sr-only, S7 reset, S8 truncate, S11 motion | **Move 2** (ambient: Class-A token where one exists, else the **Move 1** sheet) |
| S4 z-index (war + un-migrated overlays) | **Move 4** stacking (z-tokens via Move 2) + slot migration |
| S9 scroll-lock / `inert` absence, focus-restore | **Move 4** modality contract |
| S10 transient-open arbitration, B-i dismiss triad | **Move 4** exclusivity (DismissController registers) |
| §14 V1 badge/chip/dot, V2 button-skin, V4 banner | **Move 3** atom authority |
| §9.B empty/error/loading shells | **Move 3** atom authority (the `jf-empty-state`/`jf-error-alert` atoms) |
| §9.A 13 hand-rolled stores | adjacent (`createStore` ⟂ `createRegistry`) — *not* presentation; noted, out of Part II's scope |
| §7.B capability bypass, §12 backend retry/config | adjacent (funnel / `Backoff` primitive / ArchUnit) — *not* presentation; the kernel-shape recurs but the substrate differs (§12.D) |

Every *visible/presentation* specimen resolves into Moves 1–4. The non-presentation findings (stores,
capabilities, backend) are the *same kernel shape in other substrates* and keep their Part I treatment.

### §19.8 Why the class cannot recur
The per-window fork, the false-global, and the atom re-roll are three faces of one thing: **presentation
expressed by hand at a call-site instead of projected from one authority.** After Moves 1–4 there is no
call-site form to express it — the atom is the only badge, the ambient declaration is the only scrollbar, the
adopted sheet is the only delivery, the modality contract is the only modal — so the subordinate form is
*unrepresentable*, the kernel's strongest rung, now reaching the element and the ambient as well as the
facet. This is 557→559→569's arc completed: 559 made the facet unrepresentable-when-wrong; 574 makes the
**element** and the **ambient** unrepresentable-when-hand-authored, on a substrate that finally reaches every
shadow root. Same kernel, next tiers down.

## §20 Interaction with plugin / user customizability — the same design from the other side

The four moves *lock presentation down* (no party may hand-author it). The plugin/theme substrate
(560/567) and the user-authored-frontend thesis (569) want the opposite-sounding thing: let *untrusted*
parties **author** presentation safely. The deep finding is that **these are not in tension — they are one
design seen from two threat models.**

### §20.1 The two threat models are one
- **Anti-drift (Part I's lens):** the danger is a *careless internal author* (a dev, an agent) emitting
  presentation the host didn't project → forks, false-globals, the scrollbar incident.
- **Safe-customization (560/569's lens):** the danger is an *untrusted external author* (a plugin, a user
  skin, an LLM-emitted theme) emitting presentation that exfiltrates, clickjacks, spoofs the approval
  dialog, or ships unreadable contrast.

Both are the **same threat**: *a party emitting presentation the host did not project.* 569 §2's security
finding — "functional/safety invariance scales inversely with author expressiveness; the only robust
guarantee is confining authors to composition of a host-provided, allowlisted vocabulary, never free-form
CSS/HTML" — **is Part I's anti-drift law** ("typed projection never drifts; hand-authored always does")
restated for an adversary. So the four moves and 569's moves converge on identical structure, and **building
the anti-drift substrate builds the safe-customization substrate** (and vice versa). The mapping is near 1:1:

| 574 move | 569 / 560 / 567 counterpart |
|---|---|
| Move 1 delivery substrate (host-owned adopted sheet) | 569 §2 "only the host owning every CSS byte is safe"; 560 host *generates* the `@layer user-theme` `<style>` (plugin never supplies raw CSS) |
| Move 2 ambient authority (value-only tokens + host-only structure) | 567 value-only `DesignTokenTree` / `KNOWN_TOKEN_NAMES` / `isSafeTokenValue`; 569 Move 2 "seeds/roles authored, structure co-projected" |
| Move 3 atom authority (closed generated vocabulary, a11y/tone co-projected) | 569 Move 3 "the closed, generated presentation-component vocabulary the declaration may reference"; 560 `isPresentationAdmissible` ("untrusted plugin mounts only `jf-*`") |
| Move 4 modality/layer authority (reserved trusted layer) | 569 Move 4 "security-bearing UI is a reserved, non-projectable channel" |

### §20.2 What customizability *adds* to the four moves (concrete obligations)
The interaction is not free — admitting untrusted authors imposes three obligations the anti-drift framing
alone would not surface:

1. **The Class-A/Class-B split must align with the trust split.** Move 2's *value* facets (a token: scrollbar
   hue, motion speed) are the **authorable** surface (value-only, per 567/560); Move 1/2's *structural*
   facets (the keyframe shapes, the `::selection`/reset rules, the adopted sheet itself) are **host-owned**,
   outside any authorable surface. The same Class-A (inherited/token) vs Class-B (sheet) line that solved the
   shadow-DOM delivery problem (§16) is *also* the authorable-vs-reserved line — a user themes the scrollbar
   *colour* (a token) but cannot rewrite the scrollbar *rule* (the sheet).
2. **a11y-bearing ambient tokens must DERIVE to a floor, not author raw.** A user/plugin setting a
   focus-ring or selection *colour* could destroy contrast or hide the focus ring. So those tokens are
   **seeds the host derives to a 558 contrast floor** (the existing `deriveRoleForegrounds` discipline,
   extended to the new ambient domains) — authored as intent, co-projected as a safe value. Move 2 must mark
   each ambient facet *authorable-raw* (motion speed, scrollbar hue) vs *derived-to-floor* (focus/selection
   contrast). This also makes the ambient authority the one place to honour `prefers-reduced-motion` and
   system a11y prefs — a customization *input*, co-projected once.
3. **Move 4 is a SECURITY boundary, not just UX.** Under untrusted authoring, the reserved trusted layer
   (approval dialog, consent prompt, provenance/risk badge — 550/560 verdict-at-the-seam) must be a channel
   **no authored presentation can reference, cover, or occlude** (569 Move 4). This is why **S4's z-index
   consolidation graduates from tidiness to an anti-clickjacking invariant**: the trusted layer lives in the
   browser Top Layer (as `AuthorizationHost` already does), unreachable by any author-set `z-index`, so a
   skin with `z-index:99999` *cannot* paint over the approval dialog. The modality authority owns that layer;
   the authorable surface has no field for it.

### §20.3 The trust-tier asymmetry the design must preserve
The **substrate is shared across all authors** (everyone composes the same atoms, inherits the same ambient
sheet); the **authoring surface attenuates by trust tier** (560 `KernelResolver`, verdict-at-the-seam):
- **Core / internal dev:** may write a bespoke Lit template that *composes* atoms (559 §14 — arrangement is
  bespoke). The widest surface.
- **TRUSTED plugin:** may define its own custom element (which the Move-1 mint hands the ambient sheet, so it
  *inherits* consistency and cannot break it) and compose atoms.
- **UNTRUSTED plugin / user skin:** **no own element** (dropped, 560 `isPresentationAdmissible`); only
  *declared composition* over the closed layout basis + *value-only* tokens (569 Move 2 / 567).

The four moves must hold this asymmetry: same atoms/ambient/sheet, different *authoring grammar* by tier.
The lockdown does not collapse the tiers — it gives them a *common, safe substrate* over which each tier's
expressiveness is attenuated.

### §20.4 The reinforcing payoff (and the one real cost)
- **LLM authoring falls out for free (569 Move 7).** The closed, *generated* atom and ambient-token catalogs
  (Moves 2–3) **are the grammar** an LLM-emitted skin decodes against — "the gate is the grammar; reason
  unconstrained, emit constrained." Building the catalogs as typed generated vocabularies directly enables
  safe generative customization.
- **The irreducible cost (569 §7), stated honestly.** The tighter the lockdown, the richer the closed
  vocabulary must be so authors (internal *and* plugin) are not starved — "the team's perpetual work is
  growing the vocabulary; users compose it." The anti-drift framing must **not over-tighten** into blocking
  legitimate novel UI; the AHA release valve (atoms closed, *arrangement* bespoke — §19.6) is what keeps the
  design expressive, and it must stay available (as a bespoke template for core/trusted, as declared
  composition for untrusted). An author who needs a primitive that does not exist is blocked until the team
  grows the vocabulary — that is the accepted price of safety-and-consistency-by-construction.

### §20.5 Net
Customizability does not bend the design; it **confirms and completes** it. The same structure that makes a
careless agent's per-window scrollbar unrepresentable makes a malicious skin's spoofed approval dialog
unrepresentable — because both are "presentation the host didn't project," and the four moves remove the
call-site form for it. The customization lens adds three obligations (token/structure = authorable/reserved;
a11y tokens derive-to-floor; Move 4 = the reserved security channel) and one standing cost (the vocabulary
must be grown, not frozen). **The anti-drift completion of the kernel (Part II) and the safe-authoring
substrate (560/567/569) are the same build — done once, it serves both.**

## §21 De-risk pass — resolved confidence ledger (2026-06-10)

A pre-implementation confidence pass (the 565 §10 / 561 §8 pattern): a critical audit named the load-bearing
uncertainties, then read-only probes (P1–P6) + planned dev-server experiments (E1–E3) resolved them. **Where
a probe corrected a §19 claim, the correction is authoritative** (dated-history discipline). Net: no move is
blocked; **one real design correction** (atom granularity, P3) and **one enforcement correction** (P6) were
caught *before* implementation — exactly the pass's purpose.

### §21.A Read-only probes (executed)

- **P1 — mint chokepoint (Move 1). CONFIRMED + cost quantified.** No shared base class: **83/86 components
  `extends LitElement` directly**, 2 use `SignalWatcher(LitElement)`; each calls `customElements.define`
  independently; the only existing base (`JsonFormsRendererBase`) covers just the 13 renderer controls. So
  Move 1 = introduce a `JfElement extends LitElement` (carrying the adopted ambient sheet) + migrate ~83
  files (`SignalWatcher(JfElement)` for the 2). **Mechanism HIGH; cost MEDIUM (≈83-file mechanical
  migration)** — the base class is the correct hook (a registration wrapper adds indirection with no gain).
- **P2 — codegen + validation (Move 2). CONFIRMED ~free.** `gen-token-names.mjs` derives the closed vocab by
  regex (`/(--[a-zA-Z0-9-]+)\s*:/`) over `tokens.css` + palette JSON — so adding `--motion-*`/`--z-*`/
  `--font-*`/`--scrollbar-*` to `tokens.css` *auto-extends* `TokenName`. `isSafeTokenValue` only blocks
  `}{<>` (`designTokenTree.ts:173`), so `cubic-bezier(…)`, `thin`, two-color `scrollbar-color`, `150ms`, and
  z-integers all validate. **Move 2's token extension is essentially free and validation-safe.**
- **P3 — atom-variant census (Move 3). CORRECTED — the most important finding.** The §14/§19 framing
  ("a generic `jf-badge`") was **too coarse**: badges/chips are **4–5 semantically distinct things** (status
  indicator, attribution chip, filter chip, capability pill) sharing only "rounded container + text," with
  varying padding/font-size/radius and *different tone-drivers* (status vs identity vs capability). A single
  parametrised `jf-badge` would be prop-heavy with dead paths — **it fails the AHA test** (the very §19.6
  trap). **Revision (authoritative over §14/§19):** the atom tier is a **small set of genuinely-uniform
  atoms**, derived empirically — the clear wins are **`jf-status-dot`** (a tone-colored dot — uniform,
  projects `statusTone`) and a **shared button base** (the `display:inline-flex;…;background:var(--surface-
  primary)` block is re-declared **8×+ identically**); "badge" **splits** into ~2 (a status-badge + an
  attribution-chip) rather than collapsing to one. The Move-3 *thesis holds* (close the hand-authored atom
  tier) but the *granularity is finer and must be measured, not assumed* — generalize the truly-uniform
  shells, leave the semantically-distinct ones distinct (which is §19.6's own rule, now applied to itself).
- **P4 — `jf-control` composability (Move 3 buttons). CONFIRMED, with an in-repo template.**
  `ProvenanceBadge` **already** composes `jf-control` and skins it via `::part(control)` — the proven pattern
  for "atom = operability(`jf-control`) + skin + tone." A generic button-skin atom is sound; the residual is
  that `ActionButton`/`OpButton`/`CapabilityPills`/`ConfirmDialog` hand-roll buttons today and the button
  base is re-declared 8×, so the first step is *extract the shared button base*, then compose `jf-control`.
- **P5 — modality path (Move 4). CONFIRMED feasible + strongly motivated.** Scroll-lock and `inert` are
  **absent everywhere** (0 `document.body` scroll-lock, 0 `inert`) → adding them is **non-breaking** (nothing
  depends on their absence). Focus-restore is **copy-pasted in 3/6 modals** (`ElicitHost`/`EffectAuditLog`/
  `MacroDryRun`, each commented *"residue #5"*) and **missing in 3** (`AuthorizationHost`/`ConfirmDialog`/
  `CommandPalette`) — literal proof the modality contract is a real fork to fold. `AuthorizationHost` already
  uses native `<dialog>.showModal()` → **browser Top Layer** (confirming §20.2's anti-spoof claim);
  `ConfirmDialog` uses a custom `z-index:9999` backdrop (the inconsistency). Only **6 modal hosts** need
  touching; `rightDrawerArbiter`'s shape generalizes to a transient-layer arbiter. **Move 4 HIGH.**
- **P6 — enforcement feasibility. CORRECTED.** A blanket *ban*-lint on `.badge`/`.chip`/`<button>` is **not
  feasible** — the classes are too varied and often dynamically bound (`class="badge ${x}"`) → heavy false
  positives. **Revision (authoritative over §19.5):** enforcement is a **mix**: (a) **register-coverage**
  (557 §5.2) for the atom classes — only registered atoms are admissible, coverage projecting from the atom
  catalog (not a raw-class ban); (b) a **token-scale lint** for `z-index`/`transition`/motion (low false-
  positive — values already cluster to ~5 token-like numbers + the OverlayHost scale); (c) a **clean ban**
  for `::-webkit-scrollbar`/`::selection`/`@keyframes` in component `static styles` (few sites — though note
  the authoritative count is **13 `@keyframes` across 13 files** from the §16 systematic grep, not the "2" a
  sampled sub-probe reported; the ban is still clean because they consolidate to `spin`/`pulse`).

### §21.B Dev-server experiments (E1–E3) — not run live; resolved by platform knowledge + a robustness argument
The dev stack was **owned by another session** (fresh lease) — not taken over (branch-safety); and
Claude-in-Chrome drives *Chrome*, not the Tauri *WebView2*, so it cannot fully resolve the one
version-sensitive item anyway. Resolution:
- **E1 (LINCHPIN — adopted sheet styles shadow content, incl. `::-webkit-scrollbar`/`::selection`/
  `@keyframes`). HIGH by platform fact.** Adopted/constructable stylesheets are part of a shadow root's style
  scope (Chrome 73+); pseudo-elements and `@keyframes` defined there apply within that root. Move 1's premise
  is sound. *Owed: a one-line Chrome-console confirmation when the stack is free (belt-and-suspenders).*
- **E2 (Class-A `scrollbar-width`/`scrollbar-color` inherit into shadow DOM). HIGH, version-gated.** Standard
  scrollbar properties are Chromium **121+** (Jan 2024). **Robustness:** even if the target WebView2 predates
  121, **Move 1's adopted-sheet `::-webkit-scrollbar` path covers it** — so the scrollbar fix works either
  way; E2 only decides *which* form (Class-A preferred, Class-B fallback). *Owed: confirm the build's WebView2
  Chromium version.*
- **E3 (`inert` + Top Layer). HIGH.** `inert` is Chrome 102+, `<dialog>`/Top Layer already in use
  (`AuthorizationHost`). Supported; SES sandbox does not touch DOM layering. Low risk.

### §21.C Net confidence after the pass
| Move | Pre | Post | What changed |
|---|---|---|---|
| 1 Delivery substrate | mechanism HIGH / cost unknown | **HIGH / cost MEDIUM (≈83-file base-class migration)** | P1 quantified the migration; the base class is the right hook |
| 2 Ambient authority | MEDIUM (codegen/validation unknown) | **HIGH** | P2: token extension is free + validation-safe; E2 fallback via Move 1 |
| 3 Atom authority | HIGH (assumed uniform) | **HIGH on thesis / REVISED on granularity** | P3: atoms are finer & fewer-uniform than assumed (AHA); P4: `jf-control` composition proven |
| 4 Modality authority | MEDIUM | **HIGH** | P5: scroll-lock/inert absent (non-breaking), focus-restore already forked 3× (proof), 6 hosts only |
| Enforcement (§19.5) | "Class-B lint" | **register-coverage + token-lint + narrow ban** | P6: a blanket ban false-positives; mix per facet |

**Bottom line.** The *design* survived the audit intact; the pass changed **how**, not **whether**: Move 3's
atom set is finer and empirically-derived (not one mega-badge), enforcement is register-coverage-plus-token-
lint (not a blanket ban), Move 1 is a bounded ≈83-file base-class migration, and Moves 2/4 are de-risked to
HIGH. The only owed live checks are two one-liners (E1 console confirm; WebView2 version) whose worst case is
already covered by Move 1. Confidence to proceed to implementation: **HIGH**, with the corrections above
folded into the plan before the first line of code.

## §22 As-built (2026-06-10, `worktree-574-presentation-kernel`)

Part II implemented over 8 commits; verified throughout with `npm run typecheck`,
`npm run test:unit:run` (**2661 unit tests green**), and the presentation `check-*.mjs` gates. The §21
corrections were applied as planned. Live browser verification is the one remaining batch (deferred per
the goal's defer-to-end discipline).

**Phase 1 — ambient Class-A tokens.** `tokens.css`: `scrollbar-width`/`scrollbar-color` on `:root`
(inherited → reaches every shadow root, the §15 fix in its strongest form), token-driven dark/light;
the `::-webkit-scrollbar`/`::selection` block rewired to consume `--scrollbar-*`/`--selection-bg`. Added
`--z-overlay-*` (the OverlayHost scale as tokens), `--ease-*`, `--font-size-*`. `OverlayHost` z-literals
→ tokens. token-names regenerated.

**Phase 2 — delivery substrate (Move 1).** `primitives/ambientStyles.ts` (`::selection`, `:focus-visible`
ring, `.visually-hidden`, consolidated `@keyframes spin/jf-spin`) + `primitives/JfElement.ts` (overrides
Lit `finalizeStyles` to prepend the ambient sheet → every component receives it with no `static styles`
edit). **84 components migrated** `extends LitElement → extends JfElement` (2 via `SignalWatcher(JfElement)`).
The 5 identical local `spin`/`jf-spin` removed.

**Phase 3 — atom tier (Move 3), at the §21/P3 granularity.** `jf-status-dot`, `jf-button` (composes
`jf-control` via `::part`), `jf-status-badge` — projecting `statusTone` (+`toneAccentSoft`). `ProvenanceChip`/
`CapabilityPills` kept distinct (AHA). `ATOM_TAGS` register emitted by `gen-component-vocabulary` (`@atom`
marker; freshness-gated). **Per-surface site migration (HealthSurface dots, button clusters) is owed in the
live batch** (§7/FINAL — it carries visual diffs only confirmable in-browser).

**Phase 4 — modality authority (Move 4).** `primitives/modality.ts` (`ModalityController`: ref-counted
scroll-lock + focus-restore — the missing scroll-lock + the residue-#5 fix) wired into 5 modal hosts
(Elicit/EffectAuditLog/MacroDryRun/Authorization/Confirm). `state/transientLayerArbiter.ts` generalizes
`rightDrawerArbiter` (now a back-compat wrapper). **Owed in the live batch:** dismiss-triad adoption of the
arbiter + ConfirmDialog's z-literal/native-`<dialog>` reconcile (both need live stacking/UX validation, so
not shuffled blind).

**Phase 5 — enforcement (prevention tier).** `scripts/ci/check-ambient-purity.mjs` (wired into `ci.yml`):
the §21/P6 clean ban (`::-webkit-scrollbar`/`::selection`/consolidated `@keyframes` outside `ambientStyles`)
+ the JfElement-base requirement. Verified to pass clean and **bite** a deliberate violation. Token-scale
freshness rides the existing `theme-token-closure` + `gen-*-vocabulary/token --check` gates.

**Phase 0 — §17.A micro-wins.** `state/responsiveState.ts` (F1 — one shared viewport breakpoint; UnifiedChatView
migrated); the 4 raw clipboard sites routed through `copyToClipboard` (B2); `getOperationClient` accessor +
4 per-component sites migrated (F2), which surfaced + fixed an `OperationClient` construction-time `fetch`
binding (now lazy — strictly more correct).

**Deferred to the one live batch (by design, not descope):** (a) atom site-migration + its visual confirmation;
(b) dismiss-triad arbiter adoption; (c) ConfirmDialog native-`<dialog>`/z reconcile; (d) the live browser pass
itself (scrollbars/selection/focus/atoms/modality/agent-run with the model loaded). These all require seeing
the result in the real UI, which the goal sequences as the final batch.

### §22.A Live verification (2026-06-10) — the final batch, confirmed in the real browser

Ran against the live taken-over stack: the real backend (`:64778`, real doc index + loaded
`Meta-Llama-3.1-8B` via `ai_activate`) with **this worktree's frontend** served by its own Vite
(`:5175`, `VITE_JUSTSEARCH_API_PORT` pinned to the backend), driven with Claude-in-Chrome. Evidence
captured in-transcript:

- **Model + real agent run.** `ai_activate` → `completed`; `search_query "kernel"` → 7 real hits;
  `agent_chat` → the model drove a `core_search_index` tool call and returned a **grounded answer with
  citation [1]** — and that run renders in the Health *Recent events* list ("Agent session completed
  successfully — disposition: COMPLETED · head").
- **Frontend renders real data, zero breakage.** The Search surface shows "8 results" with real doc
  rows + fusion signals; the Health surface shows real GPU (`DETECTED 8.27/11.99 GB`), AI engine
  (`ONLINE`), and live events — proving the **84-component `JfElement` migration + ambient substrate**
  render correctly against the real backend.
- **Delivery substrate live.** The Health surface's shadow root carries **3 adopted stylesheets**
  including `ambientStyles` (both the `::selection` and `scrollbar-width` rules present), and
  `HealthSurface extends JfElement` — Move 1 confirmed in the running app.
- **Atoms render + project tone.** **3 `jf-status-dot`** atoms in the Health cards, computed colours
  `success → oklch(0.8 0.22 145)` (green) and `neutral → rgb(220,220,230)` — `statusTone` projection live.
- **Scrollbars — §15 fix complete, AND a real gap caught + fixed live.** `:root` carries
  `scrollbar-width: thin` + token `scrollbar-color`; a probe found **`scrollbar-color` inherits into
  shadow DOM but `scrollbar-width` does NOT** (it is non-inherited per CSS Scrollbars L1), so shadow
  scrollers were still default-width. Fixed by setting `scrollbar-width` on `:host, *` in the adopted
  `ambientStyles` (the §21 "adopted-sheet covers the Class-A fallback" path); re-probe on a fresh load
  confirmed a shadow-DOM scroller now computes **`scrollbar-width: thin`** with the token thumb colour.
- **Modality contract live.** Opening a `ModalityController` host (MacroDryRun) set
  `documentElement.style.overflow = hidden` (background scroll **locked**), and closing it released the
  lock back to empty — `enter()`/`exit()` working in the running app (focus-restore covered by the unit
  test). `:focus-visible` ring rule confirmed present in the adopted sheet.

**Net:** conditions A, B, and C are satisfied — the design is implemented, all local gates/tests are
green, and the user-visible outcomes are confirmed in the real browser with the model loaded. The one
live finding (scrollbar-width non-inheritance) was fixed in-batch, exactly the value a live pass exists
to surface.

## §22.B Remediation as-built (2026-06-10, `worktree-574-presentation-kernel`)

§22/§22.A above record the original Part II (authorities built, substrate + gate landed). A critical
self-review then found the dominant flaw: **the authorities were built but the forks under-adopted** —
the per-window-fork class was *preventable* (the `check-ambient-purity` gate + the atoms existed) but
not *resolved* (the §18 ~76% fork footprint was essentially unchanged, plus two a11y gaps). This
remediation closes that adoption gap. Phases A + C are **complete**; Phase B is complete for every
user-facing product **view** and the token-literal classes, with a remaining component-CTA tail
(enumerated below). All work is on this worktree; not merged.

**Phase A — correctness / a11y / atom-hardening (complete).**
- **A1** `ConfirmDialog` → native `<dialog>.showModal()` (browser `inert` + focus-trap + Top Layer;
  the §16 S4 z-literal removed). `ModalityController` enter/exit retained.
- **A2** the dismiss-triad — `ContextMenu` / `Peek` / `BookmarksPopover` / `SelectionActionsMenu` —
  wired into `transientLayerArbiter` (register + `closeOthersInLayer` on open; unregister on close), so
  only one transient overlay is open at a time (S10 closed at the call sites).
- **A3** `jf-status-dot` gained a `live` pulse variant (dot-local `@keyframes jf-dot-pulse`,
  `prefers-reduced-motion`-aware); migrated every bespoke dot — HealthSurface `.card-status-dot`,
  BrainSurface `.status-dot` (×2 sites) + ONNX rows, HealthLitView `.conn-dot`, AdvisoryRailBadge
  `.disconnected-dot` — and deleted the dead dot CSS + `@keyframes pulse`.
- **A4/A5** `jf-button` gained `size="icon"` (square) and later `size="sm"` (compact); ambient
  scrollbar rule tightened to `scrollbar-width` only (`scrollbar-color` inherits from `:root`).

**Phase B — fork migration.**
- **B3 (z-war, complete + locked):** all 9 magic overlay z-indexes → `--z-overlay-*` / local
  `--z-modal`. Ratchet floor **z-index = 0**.
- **B4 (motion, complete + locked):** all 37 raw transition durations across 20 files →
  `--duration-*` + `--ease-*`. Ratchet floor **transition = 0**.
- **B5 (typography, complete to the legitimate floor):** a one-shot codemod
  (`scripts/codegen/migrate-font-size-tokens.mjs`) folded **403** inline `font-size` rem/px literals
  across ~70 files onto the nearest `--font-size-*` token. Ratchet floor **font-size = 20** — the
  remainder is NOT the inline-absolute-size fork class: `em` relative units (contextual) +
  `var(--justsearch-shell-*-size, <rem>)` host-theme-var fallbacks (the embeddable-host theming layer).
- **B1 (buttons → `jf-button`):** migrated every action button in all **eight product views** —
  LibrarySurface, BrainSurface (13), SettingsSurface (incl. `size="sm"` + the rail-arrow/custom-theme
  icon overlays), EffectAuditLog (15, incl. the confirm bars), HealthSurface, MemorySurface,
  BrowseSurface — and confirmed HelpSurface/SearchSurface have **no** generic-button-base fork (only
  disclosure / copy-with-flash / pin / chip affordances). Per-surface `button{}`/`.primary`/`.danger`/
  `.icon-btn` forks deleted.
- **B2 (status pills → `jf-status-badge`):** LogSurface, HealthSurface, SettingsSurface (8 pills).

**Phase C — completeness ratchet (complete).** `scripts/ci/check-style-literal-ratchet.mjs` — a
per-file shrinking baseline (`style-literal-ratchet-baseline.v1.json`) forbidding NEW raw
`z-index` / `transition` / `font-size` literals; wired into `ci.yml` + the CLAUDE.md pre-merge list.
Verified it catches a regression (injected `z-index:77` → FAIL) and passes clean. This is what turns
"preventable" into "resolved": even the un-migrated tail cannot regrow, and `--rebalance` shrinks the
baseline as migration lands.

**AHA cuts (kept bespoke — by design, not under-adoption):** segmented controls (BrainSurface
mode-toggle, SettingsSurface audience selector, `.option-btn`/`.card` pickers); disclosure toggles
(accordion / turn-head / faq-q / chevrons); trace/copy/pin/chip affordances; count/provenance/capability
chips. These are distinct affordances, not the action-button base the atoms own.

**Test pattern established:** a migrated `jf-button` activates from the native `<button>` inside its
composed `<jf-control>` (two shadow roots deep) — a host `.click()` does NOT activate it. The
`activateJfButton(el)` helper (awaits both render passes, clicks the inner control) is the canonical way
to drive a migrated button in a test; selector probes move to `jf-button` and read its reflected
`disabled` / host `title` / light-DOM slot `textContent`.

**Verification:** `npm run typecheck`, the full FE unit suite (**2663 pass**), and every presentation
gate (`ambient-purity`, `controls-a11y`, `theme-token-closure`, `color-tokens`, `layout-purity`,
`presentation-purity`, the new `style-literal-ratchet`) are green after each commit.

### §22.B.1 The CTA-tone decision — RESOLVED (jf-button tone variant)

The one open structural question (how to fold the **solid** success/risk dialog CTAs — ElicitHost's
solid-green submit, ConfirmDialog's solid risk-tone confirm — onto the atom, when `jf-button`'s
UI-intent `variant` set's `danger` is only a *soft* tint) is resolved the long-term way: `jf-button`
gained a `tone` attribute (`success`/`warning`/`error`/`info`) projecting a SOLID high-emphasis CTA
fill **from the same 565 `statusTone` authority** that `jf-status-dot` / `jf-status-badge` read — so the
whole atom tier shares ONE tone→accent mapping, not per-dialog hand-picked `var(--accent-*)`. `tone`
wins over `variant`; `jf-button.focus()` delegates to the composed native button. This was the keystone
that unblocked the entire CTA tail.

### §22.B.2 Component-CTA tail — DONE (every generic-action fork migrated)

Every generic action button across the dialog / overlay / panel / activity tier is now `jf-button`:
ConfirmDialog (confirm = `tone`, cancel, close), ElicitHost (submit = `tone="success"`, cancel),
MacroDryRun, PluginErrorOverlay, WalkthroughCard, PendingEffectQueue (accept = `tone="success"`,
reject = `danger`), TaskList, AuthorizationHost (approve/allow-always = `primary`, deny = `danger`),
SourcesPane, RetrospectivePanel, InspectorPane (send = `primary`, close), AiActivityDigest,
AgentActivityPanel (accept = `tone="success"`), AdvisoryInboxDrawer (mark-all-read, close). Each
surface's bespoke `button{}`/`.primary`/`.danger`/`.accept`/`.reject`/`.close`/`.icon` fork is deleted;
`activateJfButton` is the canonical test driver (action fires from the inner `<jf-control>` button).

**The AHA-cut remainder (provably distinct affordances — NOT the action-button base fork, kept bespoke
by design and named per the §19.6 guard):**
- **Segmented controls / tabs:** BrainSurface mode-toggle, SettingsSurface audience selector +
  `.option-btn`/`.card` pickers, Retrospective/Inspector `role=tab` strips, AdvisoryInboxDrawer
  `.chip` filters (aria-pressed toggles).
- **Disclosures:** accordion / turn-head / faq-q / `.panel-header` / ConversationHistory `.trigger` /
  chevrons (aria-expanded toggles).
- **List-rows (navigation):** CitationsPanel `.source`, ConversationHistory `.item`, Retrospective
  session-rows (`role=button` rows, not action buttons).
- **Chips:** provenance / capability / pinned-query chips.
- **Form-renderer controls** (`ArrayControl` add/remove, toggle renderer, …): these live on the
  **`--justsearch-shell-form-*` host-theming layer** (the embeddable-form theming authority, like
  HealthLitView), a deliberately-separate theming contract — not the app `--accent` button tier.
- **Event-coupled imperative-feedback:** AdvisoryInboxDrawer `.action-btn` (reads the native click
  event's `currentTarget` to show "Running…" — outside `onActivate`'s model).
- **State-dependent chrome cluster:** the Shell topbar `.nav-btn` family (bookmark-toggle `.active`,
  copy-url `.copied` feedback) — host-class state visuals the inner control would occlude, a cohesive
  bespoke cluster; and the Composer's **light-DOM** send/stop (rendered into the parent view's shadow,
  not a jf-element shadow).
- **Decorative-in-row:** the Retrospective session-row "Resume" label (visual, the row is the control).

### §22.B.3 Verification + remaining

`npm run typecheck`, the full FE unit suite (**2664 pass**), `./gradlew.bat build -x test`
(**BUILD SUCCESSFUL** — the inherited class-size drift was reconciled via the LocalApiServer pin bump +
slack rebalance + changeset), and all eight presentation gates (`ambient-purity`,
`style-literal-ratchet` [z=0, transition=0, font-size at floor], `presentation-purity`,
`theme-token-closure`, `color-tokens`, `layout-purity`, `controls-a11y`, `a11y-closure`) are green.

### §22.B.4 Live verification (2026-06-10) — the final batch, confirmed in the real browser

Ran against the live taken-over stack (run `d38f8807`, backend `:61980`, real index + `Meta-Llama-3.1-8B`
via `ai_activate` → `completed`) with **this worktree's frontend** served by its own Vite (`:5175`,
`VITE_JUSTSEARCH_API_PORT=61980`), driven with Claude-in-Chrome. Evidence captured in-transcript:

- **Atoms render across surfaces.** Health + Unified Chat each mount **26 `jf-button`** + `jf-status-dot`
  + `jf-status-badge` atoms; deep shadow walk = **0 surface-owned raw `<button>`** beyond the atoms'
  composed `<jf-control>` natives + the named AHA-cut affordances. The chat surface mounts cleanly
  (composer present, no breakage).
- **The new solid `tone` CTA (the keystone) is live.** A constructed `jf-button tone="success"` computes
  `background: oklch(0.8 0.22 145)` (= `--accent-success`) + on-accent text; `tone="error"` =
  `oklch(0.75 0.25 25)` (= `--accent-danger`) — the SOLID statusTone fill the dialogs needed. In a real
  `jf-confirm-dialog` (variant=danger) the confirm button is `tone="error"` with that solid fill.
- **A1 native-dialog modality.** Opening the confirm dialog: `dialog.open === true` (showModal → browser
  focus-trap + Top Layer), `documentElement` overflow → **`hidden`** (ModalityController scroll-lock);
  closing → overflow **`""`** (lock released → focus-restore path). Confirmed in the running app.
- **jf-status-badge tone projection.** The Health connection pill computes tinted bg
  `oklch(0.85 0.22 70 / 0.16)` + solid fg `oklch(0.85 0.22 70)` (warning tone) — `toneAccentSoft`+`toneAccent`.
- **Ambient facets — §15/§16 live.** The status-dot's adopted sheet carries `:focus-visible`,
  `scrollbar-width`, and `::selection`; **127 shadow roots** carry the ambient sheet; a shadow-DOM
  scroller computes **`scrollbar-width: thin`**.
- **Real agent run.** `agent_chat` drove a `core_search_index` tool call (2 real hits) and returned a
  **grounded answer with citations [1][2]** (2 iterations, 3.6 s) — model + tool + grounding end-to-end.
- **A2 single-open menus** is substrate-wired (the four dismiss-triad components register into
  `transientLayerArbiter`) and unit-covered; the arbiter + wiring are green (live-driving two simultaneous
  menus needs bespoke UI triggers, the same scope the §22.A batch held).

**Net:** the remediation is complete and conditions A, B, and C are satisfied — every generic-action fork
is migrated onto the authorities, the token-literal classes are at their floors (z=0, transition=0,
font-size at the em/host-theme-var floor) and ratchet-locked, the backend build + 2664 FE tests + all
eight presentation gates are green, and the user-visible outcomes (atoms with parity, the solid tone
CTAs, native-dialog focus-trap + scroll-lock + restore, ambient scrollbars/selection/focus, a grounded
agent run) are confirmed in the real browser with the model loaded.

### §22.B.5 Critical-analysis follow-up fixes (2026-06-10)

A post-implementation critical-analysis pass found four issues the gates/unit-tests structurally can't
catch (host-CSS leakage, WCAG 2.5.3, visual size, dead CSS):

- **F2 — leftover `.close` host-skin (fixed).** `SourcesPane` + `RetrospectivePanel` kept a *class-only*
  `.close { background/border/padding }` after migrating their close to `<jf-button class="close">` —
  it leaked onto the atom HOST (a border around the atom's own control). Tag selectors (`button.icon`,
  `button.send`) are inert against `<jf-button>` and don't leak; only class-only selectors do. Deleted
  the two `.close` skins.
- **F3 — over-labeling broke WCAG 2.5.3 (fixed).** `jf-control` sets `aria-label=${label}`, which
  *overrides* slot text as the accessible name. Where a migrated button's `label` dropped part of the
  visible text (a count/shortcut suffix), the name no longer contained the visible text — SettingsSurface
  `"Generate"` vs `"Generate (on-device)"`, EffectAuditLog export/save/undo-all vs their `(N)`, InspectorPane
  send `"Ask"` vs `"Ask (Ctrl+Enter)"`. Removed `label` on these (the slot text is the name). **Rule
  (now in `Button.ts`): `label` is for icon-only buttons; text buttons take their name from the slot.**
  Live-confirmed: a text button without `label` yields `aria-label: null` on the inner control → name =
  visible slot text; the old short-label pattern set the truncated `aria-label` (the failure).
- **F4 — dead cruft (fixed).** Removed inert `button.danger`/`button.icon`/`button.send` tag-rules
  (elements are now jf-button); **preserved** the send button's `margin-top` as a class-only `.send` host
  rule (the `button.send` tag selector had silently dropped it post-migration — a real layout loss);
  dropped a throwaway synthetic `new Event` in SettingsSurface's Load handler.
- **F1 — font-size codemod tie-break (re-graded LOW; policy fixed, sites left).** The codemod snapped the
  two dominant sizes (12px ×111, 14px ×31) *down* on a tie (12→xs/11, 14→sm/13). Investigation: this is a
  **valid** nearest-tie resolution (11 and 13 are equidistant from 12) and the app renders/reads correctly
  (§22.B.4) — a consolidation *preference* (12 should group with its nearer-larger 13), not a defect. Fixed
  `nearest()` to round-half-up so any future tokenization consolidates correctly; the already-committed
  valid-snap sites were **deliberately left** (re-churning ~40 files — many with later font-size-CSS
  deletions that break a clean revert — for a 1px move on a valid choice was higher risk than reward).

All four fixes: typecheck + the full FE suite (2664) + the eight presentation gates + `./gradlew.bat
build -x test` (BUILD SUCCESSFUL) green; F3 additionally confirmed in the real browser.

## §22.C Design-goal conformance — what the as-built does NOT yet achieve (theoretical, vs Part II)

§22/§22.A/§22.B record *what was built and that it works*. This section is the honest measure of the
as-built against **Part II's actual thesis** (§19.2/§19.8): *make hand-authored presentation
**unrepresentable by construction** at every tier* via **generated, self-covering single-authorities**,
where the prevention ladder is **Collapse/Unrepresentable > Generate > Gate** and **"Gate is the floor,
not the mechanism"** (§19.5), and §947–949's caution that the call-site migration (§8) is *"what adopting
the structure looks like, not a substitute for building it."*

**Verdict:** what shipped is **(1) the Move-1 delivery substrate, (2) thorough call-site ADOPTION (the §8
tactical layer), and (3) ONE genuinely-new prevention gate** (the `style-literal-ratchet` for
z-index/transition/font-size). The ratchet is the **only** tier where the fork class is now *structurally
prevented from regrowing*. Elsewhere the forks are **migrated/cleaned today but not made unrepresentable**
— i.e. the work delivered the part §19 calls *necessary but explicitly not sufficient*, and left most of
the *sufficient* structure (generated authorities + catalog-projected coverage) unbuilt.

### Per-move conformance

- **Move 1 — delivery substrate: ACHIEVED (realistic ceiling).** `JfElement` adopts `ambientStyles` into
  every shadow root; the `ambient-purity` gate requires extending it (raw `LitElement` banned). True
  "cannot define a `jf-*` outside the mint" is impossible in JS, so the gate is the realistic ceiling. The
  one move that lands as designed.

- **Move 2 — ambient authority: SUBSTANCE, NOT STRUCTURE.** The *values* exist (scrollbar/selection/focus/
  spin-keyframes in the adopted sheet; `--z-overlay-*` / `--duration-*`+`--ease-*` / `--font-size-*` /
  `--scrollbar-*` tokens) and two gates guard them. **NOT built:** the single typed `AmbientDeclaration`;
  the extension of the `gen-token-names.mjs` **codegen** to the new value domains (tokens were hand-added to
  `tokens.css`, so the **Generate** rung is absent); **coverage projecting from an ambient-facet catalog**
  (557 §5.2) — the gates hard-code the banned constructs + a shrinking baseline, so a *new* ambient facet
  is **not** auto-covered. **Facets never added** that §19.3 names: `::placeholder`, a reset/box-model
  facet, the truncate utility. → *substance for the main facets; not the generated self-covering authority.*

- **Move 3 — atom authority: ATOMS + ADOPTION, NO UNREPRESENTABILITY.** The atoms (`jf-status-dot` /
  `jf-button` / `jf-status-badge`) are real and **do co-project their invariant facets** (statusTone tone,
  `jf-control` operability, token colour) — that part is correct. **NOT achieved:** (a) the vocabulary is
  **incomplete** — `jf-empty-state` / `jf-error-alert` (§9.B) and a generic `jf-chip` (§14) were never
  built, so those shells stay bespoke; (b) there is **no atom-register gate** making "a raw styled visual
  element outside an atom" unrepresentable — the existing forks were migrated *by hand*, but a NEW
  hand-rolled `.badge` / status dot / banner is freely addable and nothing catches it. (Action buttons are
  *incidentally* protected by the pre-existing `controls-a11y` gate; non-interactive atoms are not.) This is
  §19's headline claim ("the atom is the only badge") and is the **largest miss** — adoption without the
  closed-generated-vocabulary-with-register that makes it stick.

- **Move 4 — modality authority: CONTROLLERS WIRED, NOT UNIFIED, NOT ENFORCED.** `ModalityController`
  (scroll-lock + focus-restore) + native `<dialog>` (browser `inert` + focus-trap + Top Layer) +
  `transientLayerArbiter` (single-transient) exist and are wired into the *existing* hosts. **NOT achieved:**
  the **merge into one** layer/modality/exclusivity authority (they remain separate pieces;
  `rightDrawerArbiter` is a back-compat wrapper, not absorbed); and **no gate** makes a *half-wired* modal
  (traps stacking but leaks focus / doesn't lock scroll / doesn't `inert`) or an *unarbitrated* second
  popover **unrepresentable** — §19.3/§19.7's S9+S10 closure is achieved only for the hosts touched, not by
  construction. A new modal can still be built wrong.

### The structural remainder (to actually finish the design, not just adopt it)
1. **Atom-register gate** (Move 3): coverage projecting from `gen-component-vocabulary.mjs`'s atom set so a
   raw `.badge`/`.chip`/status-dot/banner outside an atom fails the build — the rung that turns today's
   migration into "unrepresentable."
2. **Missing atoms** (Move 3 / §9.B): `jf-empty-state`, `jf-error-alert`, generic `jf-chip` — build + migrate
   the ~7 empty-states + ~7 error-banners.
3. **Typed `AmbientDeclaration` + codegen** (Move 2): the single typed facet object + `gen-token-names.mjs`
   extended to motion/z/typography/scrollbar, with gate coverage projecting from the ambient catalog (so the
   ladder reaches **Generate**, not just **Gate**); add the missing `::placeholder`/reset/truncate facets.
4. **Unified modality contract + gate** (Move 4): merge `ModalityController`/`transientLayerArbiter`/
   `OverlayHost` into one authority; gate that every modal binds the *bundled* contract and every transient
   registers for exclusivity.

**Net (plain terms):** the house was tidied and almost everything moved onto the shared parts, and one lock
now stops stray size/spacing/z-index literals. But the design's real goal — a self-running system where
hand-rolling a badge / modal / ambient rule is *impossible to write* (generated, self-extending,
gate-enforced per tier) — was built only for Move 1 and the literal-ratchet; for the atoms, most ambient
facets, and modality it remains **adopted, not unrepresentable**. The fork class is **cleaned today, not yet
prevented tomorrow** outside the token-literal tier.

## §22.D Landing + de-risk pass on the §22.C remainder (2026-06-11)

**LANDED.** Part II + the §22.B remediation + the §22.B.5 fixes merged into `main` at `ff675e591`
(a `--no-ff` merge commit, pushed to `origin/main`). Reconciliation path: the `gate-fixes-main-green`
staging branch (since retired) was merged in, then `origin/main` synced after main became the trunk again;
the final land was a clean fast-forwardable `--no-ff` merge (0 conflicts — main was already an ancestor).
Verified from `main`: `./gradlew.bat build -x test` SUCCESSFUL (class-size gate passes), 2696 FE unit tests,
all 15 presentation gates. (A pre-existing NUL-byte-as-separator in `UnifiedChatView.renderLiveOverlay`
`:2252,2258` was logged to `observations.md`, not fixed — out of scope.)

**DE-RISK PASS on the §22.C structural remainder** (read-only investigation + 3 Explore sweeps + the
gate/codegen scripts; **no code written** — the goal was to reduce surprises before implementing). The
findings *revise* the §22.C remainder's scope:

1. **`jf-empty-state` — DROP.** The 11 empty-state sites are DIVERGENT (text-only / icon / CTA / conditional);
   one atom violates the §19.6 AHA guard. §9.B's "build `jf-empty-state`" assumed a uniformity that does not
   hold on inspection.
2. **`jf-chip` — DROP as a generic atom.** Reading the actual CSS (not just structure), the "chip" sites
   fragment into THREE affordances: **toned status pills** (EffectAuditLog `.chip`, StatusDeck `.pill`,
   AdvisoryInboxDrawer `.outcome-tag` — these are `jf-status-badge` cases) + **clickable filter toggles**
   (Advisory filter `.chip`, LogSurface `.chip`, `CapabilityPills` — already the §22.B aria-pressed AHA-cut)
   + the already-custom `ProvenanceChip`. A generic `jf-chip` would conflate them into a config-grab-bag (the
   over-DRY the design warns against). **Revised action: migrate the toned chips onto the existing
   `jf-status-badge`; build no `jf-chip`.**
3. **`jf-error-alert` — BUILD (promoted, higher confidence).** The `.alert`/`.warning` CSS is near-byte-identical
   across **~7 sites** (Settings/Search/Inspector/Browse/Brain/Health/MacroDryRun): tinted bg + 1px border +
   tone colour + `border-radius:0.375rem`, varying only by **severity** (a `tone` prop, projecting the 565
   `statusTone` the other atoms read) and **font-size** (a token). One atom (`tone` + slot + optional icon)
   covers all; `PluginErrorOverlay`'s rich card stays bespoke.
4. **Atom-register gate — a shrinking-baseline RATCHET, not a clean grep.** A raw CSS-pattern scan is ≈60/40
   true/false (cannot separate a fork from a legit molecule by pattern alone); the proven
   `check-style-literal-ratchet` shape baselines the existing matches and fails only NEW raw badge/dot/chip
   CSS. `gen-component-vocabulary`'s `ATOM_TAGS` (the positive register, `@atom`-marked) names the atoms; the
   ratchet baseline is the negative list. (The codegen itself states its model is "register-coverage … NOT a
   blanket ban on raw badge/button markup" — confirming the ratchet, not a collapse.)
5. **Move 4 — a CONTRACT GATE, not a code-merge.** `ModalityController` (scroll-lock/restore) and
   `transientLayerArbiter` (single-open) are ORTHOGONAL — merging them violates the AHA guard (a transient
   needs no scroll-lock; a modal needs no arbiter); `rightDrawerArbiter` already wraps the arbiter. The
   **modal half is clean**: key the gate on `.showModal()` — all 5 true modals (Confirm/Elicit/MacroDryRun/
   EffectAuditLog/Authorization) call it AND compose `ModalityController`, so the gate passes 5/5 with ZERO
   false positives and NO exemption list (whereas `slot="center"` is noisy — it includes the command palette
   + the presentational Indexing/Drag backdrops). The **transient half** ("what *should* register") is a fuzzy
   signal → likely a ratchet, not a clean gate.
6. **Move 2 — codegen mostly already exists.** `gen-token-names` already projects every `tokens.css --xxx`
   into the closed `TokenName` union — the `--duration/--ease/--z-overlay/--font-size/--scrollbar` tokens are
   ALREADY generated. Remaining = small: add the named-but-absent facets (`::placeholder` / reset-box-model /
   truncate) to `ambientStyles` + their bans. A typed `AmbientDeclaration` + catalog-projected gate coverage
   is LOW value for an ~8-facet closed set (hand-maintaining the ban list is cheap) — optional, decide at
   implementation time.

**Confidence after the de-risk pass (0–10):** `jf-error-alert` build+migrate **9**; toned-chip → `jf-status-badge`
migration **8**; drop `jf-empty-state` **9**; atom-fork ratchet **8**; Move 4 modal-contract gate (`showModal`)
**9**, transient-contract gate **5**; Move 2 facets+bans **8** (catalog-projection **5**). **Overall ≈8**, up
from ≈4–5 before the investigation — the lift is from *re-scoping* (drop the over-DRY atom + the over-unified
merge; promote the genuinely-uniform alert; clean the modal signal), not new code. **Honest ceiling unchanged:**
every gate inherits the run-renderers "import-invisible slips" limit — it makes a fork *harder to write, not
impossible*; implemented, Moves 3/4 reach gate-enforced-unrepresentable, not pure collapse.

## §22.E As-built — the §22.D remainder implemented (2026-06-11, `worktree-574-presentation-kernel`)

The §22.D-revised remainder shipped over the five items, each reusing the proven pattern (no new framework).
Verified throughout with `npm run typecheck`, the full FE unit suite, the `check-*.mjs` gates, and
`./gradlew.bat build -x test`; the user-visible items were live-verified in the real browser (the final batch).

- **Item 1 — Move 2 ambient facets (done).** `ambientStyles.ts` gained `::placeholder { color: var(--text-muted) }`
  (the one missing shadow-scoped pseudo; the single existing fork in `CommandPalette` removed) and a
  `.jf-truncate` utility. `check-ambient-purity` now HARD-BANS `::placeholder` (per 570 §14's correct insight
  that grep-visible pseudos admit a hard ban). The box-model reset facet was deliberately SKIPPED (high
  blast-radius, no fork driving it).
- **Item 2 — `jf-error-alert` atom + migration (done).** New `components/ErrorAlert.ts` (mirrors `StatusBadge`:
  `@atom`, `extends JfElement`, projects `statusTone` `toneAccent`/`toneAccentSoft`; `role="alert"`; an icon slot
  + message slot + a reactive `onDismiss` that composes `jf-button size="icon"`). **11 `.alert`/`.warning` sites
  across 7 surfaces** (Search/Settings/Browse/Brain ×6/Health/Inspector ×2/MacroDryRun) migrated to
  `<jf-error-alert tone=…>`; every per-surface `.alert`/`.warning` CSS deleted; `PluginErrorOverlay`'s rich card
  kept bespoke. Registered in `run-renderers` `toneSites` (it imports `statusTone`) + `ATOM_TAGS` (via `@atom`).
- **Item 3 — status-chip → `jf-status-badge` (done, source-narrowed).** The pre-implementation pass (slice-exec
  bidirectional discipline) verified each "toned chip" against source: only `AdvisoryInboxDrawer.outcome-tag`
  is a genuine binary status outcome — migrated to `<jf-status-badge>` with an explicit `tone` (success→success,
  failure→**warning**, preserving the host's deliberate amber-for-failure semantic; the host owns the tone, the
  atom owns the look). `StatusDeck.pill` (online=status but **cpu/gpu**=mode) and the `EffectAuditLog` originator
  chips (user/agent/**system=purple**, a non-status palette) + the aria-pressed filter chips stay bespoke (the
  §19.6 AHA cut). **No `jf-chip`** (the §22.D verdict held under source inspection).
- **Item 4 — atom-fork ratchet (done).** `scripts/ci/check-atom-fork-ratchet.mjs` (clones the style-literal
  ratchet): a per-file shrinking baseline (`atom-fork-ratchet-baseline.v1.json`, 9 files / 10 rules) counting raw
  `.badge`/`.pill`/`.chip`/`.tag`/`.status-dot`/`.outcome-tag` BASE rules outside the `@atom` components (derived)
  + the AHA-distinct `ProvenanceChip`/`CapabilityPills`. Fails NEW forks; bite-tested (a planted `.badge {}` → FAIL).
- **Item 5 — modal-contract gate (done).** `scripts/ci/check-modality-contract.mjs`: every file calling
  `.showModal()` MUST compose a `ModalityController` (the scroll-lock + focus-restore the native dialog lacks).
  Self-contained, whole-tree, NO allowlist (the §19.5 ideal — `showModal()` is the precise modal signal, so the 5
  modals pass 5/5 and the presentational center-slot backdrops need no exemption). Bite-tested.

Both new gates wired into `ci.yml` + the CLAUDE.md pre-merge list.

**Scope refinements surfaced during implementation** (recorded, not silently absorbed): InspectorPane's alert
font-size standardised `xs`→`sm` (one step, validated live); the outcome-tag is `advisoryClassChrome`-driven
(verified binary success/failure before migrating); the `jf-error-alert` `onDismiss` was made a **reactive**
property after the live batch showed a non-reactive field only rendered the dismiss on first-render timing — the
robustness fix the live tier exists to catch.

**Live verification (the final batch, real browser, Vite-served worktree FE against a running backend):**
- `jf-error-alert` renders both tones correctly — error = `rgb(191,97,106)` (danger) border + `--accent-danger-16`
  tint + `role="alert"`; warning = amber + `--accent-warning-16`. End-to-end dismiss on the **real SettingsSurface**:
  setting `error` renders the alert → its dismiss (accessible name "Dismiss", WCAG 2.5.3) → `onDismiss` fires →
  error cleared → alert gone.
- `::placeholder` is delivered through the adopted ambient sheet (the rule is present in a shadow root's
  `adoptedStyleSheets`); a real input's placeholder computes `rgba(216,222,233,0.55)` = `--text-muted`.
- The migrated outcome badge renders as a `9999px` tinted pill from the tone.

**Verification totals:** typecheck clean; **2698** FE unit tests (incl. new `jf-error-alert` atom tests); all 15
pre-existing presentation gates + the 2 new gates + both codegen `--check`s green; `./gradlew.bat build -x test`
SUCCESSFUL. **Honest ceiling unchanged:** the two new gates are ratchet/contract gates with the run-renderers
"import-invisible slips" ceiling — harder-to-write, not impossible. Move 2's ambient bans alone reach the
hard-ban (grep-visible pseudos) form. Not merged — that is the user's.

## §22.F As-built — lifting Moves 2/3/4 from GATE to GENERATE/COLLAPSE (2026-06-11, `worktree-574-presentation-kernel`)

§22.E shipped the prevention *floor* (hard-coded gates). The critical-analysis pass showed that is the
**Gate** rung — the design's *"floor, not the mechanism."* This pass lifts each tier toward the design's
top rungs (*self-covering / by-construction*), reusing the codebase's proven catalog-projection pattern
(`check-composition-surfaces` / `check-a11y-closure` — *"adding a catalog row is the discovery step;
generation, not grep, is the anti-drift"*).

- **Item 1 — Move 2: self-covering ambient authority.** New `governance/ambient-facets.v1.json` (the typed
  `AmbientDeclaration` catalog: the Class-B facets `::-webkit-scrollbar`/`::selection`/`::placeholder`/
  `@keyframes spin|jf-spin`, the utilities, the keyframes). `check-ambient-purity` now PROJECTS its ban-list
  from `classB` AND asserts POSITIVE coverage (each cataloged facet is defined in `ambientStyles.ts`) —
  adding a facet to the catalog auto-bans its re-authoring *and* requires the sheet to provide it. The
  hard-coded `CLASS_B` array is gone.
- **Item 2 — Move 3: self-covering atom-fork ratchet.** New `governance/atom-facets.v1.json` (each atom ↔ its
  fork-class signature + authority file + the AHA-distinct chips). `check-atom-fork-ratchet` PROJECTS its
  detection vocabulary + authority-exclusion set from the catalog — a new atom row + its fork-classes
  auto-extends coverage. The hard-coded vocab/exclusion literals are gone (same baseline, 10 rules / 9 files).
- **Item 3 — Move 4: by-construction transient arbitration (COLLAPSE).** New `primitives/transientController.ts`
  (`TransientController`, the transient sibling of `ModalityController`): it bundles the
  `registerTransient`+`closeOthersInLayer`+`unregisterTransient` triad into the host lifecycle, so composing
  it + calling `open()`/`close()` is the ONLY wiring a transient needs. **Migrated all 4 transients**
  (`ContextMenu` — per-menu controller on the factory-created host; `Peek`/`BookmarksPopover`/
  `SelectionActionsMenu` — a controller field), deleting their hand-rolled manual triads. New
  `governance/transients.v1.json` + `check-transient-arbitration.mjs` (positive coverage: every cataloged
  transient composes the primitive; `HoverPreviewHost` deliberately absent — timer-driven). `TransientController`
  unit-tested (4 tests) + the gate bite-tested.

**Honest per-move grade (vs the ladder):** Items 1/2 reach **self-covering gate** — coverage now *projects
from a catalog*, so the "remember to update the gate" failure mode is gone and a new facet/atom is auto-governed
by adding a row (the *Generate-adjacent* rung; the gate is still the import/grep-visible enforcement). Item 3
reaches true **Collapse** — `TransientController` makes "a transient that does not arbitrate single-open"
*unwritable by construction* (the manual triad it replaced no longer exists to forget), the genuine top rung.

**Verification:** typecheck clean; **2702** FE unit tests (incl. 4 new `TransientController` tests); all 16
presentation gates (3 now catalog-driven) + both codegen `--check`s green; `./gradlew.bat build -x test`
SUCCESSFUL. **Live (real browser, Vite worktree FE):** the ambient facets render via the adopted sheet
(placeholder computes `--text-muted` on the chat composer; `::selection`/`:focus-visible`/`scrollbar-width`
present in adopted sheets), and transient **single-open holds by construction** — opening Peek closed an
open BookmarksPopover through the migrated controllers. Not merged — that is the user's.

## §22.G As-built — completing Move 4 by-construction on BOTH halves (2026-06-11, `worktree-574-presentation-kernel`)

§22.F gave **transients** single-open *by construction* but left Move 4 asymmetric: the **modal** contract
was only *gate-caught* (§22.D's `check-modality-contract` checks a modal *composes* `ModalityController`,
not that `enter()/exit()` are CALLED — so a modal could pass yet be half-wired), and the outside-click/Esc
dismiss was still hand-rolled per-transient. This completes both — the tier where a runtime *behaviour* CAN
collapse into a primitive (unlike CSS forks, which the §22.G analysis confirmed are at their substrate
ceiling — **atoms/ambient get no further structural work**).

- **Item 1 — `ModalController` (modal contract by construction).** New `primitives/modalController.ts`
  (mirrors `TransientController`): composes a private `ModalityController`; `open()` fires
  `modality.enter()` + `dialog.showModal()` (or `show()` when `nonBlocking`) + `onOpened` ATOMICALLY;
  `close()` fires `dialog.close()` + `modality.exit()`. A `captureFocus()` method preserves ElicitHost's
  pre-render focus-capture (residue #5); a per-call `open({ nonBlocking })` preserves AuthorizationHost's
  lightweight low/medium path on one dialog. **All 5 modals migrated** (ConfirmDialog/ElicitHost/MacroDryRun/
  EffectAuditLog/AuthorizationHost), replacing their manual `enter+showModal` / `close+exit` pairs.
  New `governance/modals.v1.json` + `scripts/ci/check-modal-arbitration.mjs` (positive coverage — every
  modal composes `ModalController`); `check-modality-contract` stays as the backstop. Closes the
  compose-vs-call hole: a half-wired modal is now unrepresentable for adopters.
- **Item 2 — `TransientController` full dismiss-triad.** Extended with `managesDismiss`: `open()` installs
  ONE shared capture-phase outside-`pointerdown` + `Escape` → `close()` handler (`composedPath` excludes the
  host); `close()`/`hostDisconnected` removes it. **Peek** (gained the dismiss it lacked) + **SelectionActionsMenu**
  (deleted its hand-rolled `boundOutsideClick`/`boundEsc`) adopt it; **BookmarksPopover** keeps its native
  Popover-API light-dismiss; **ContextMenu** keeps its per-instance listeners. Arbitration + dismiss are now
  the controller's, by construction.

**Honest grade:** Move 4 now reaches **Collapse (by construction) on both halves** — the broken states
(half-wired modal, unarbitrated/undismissable transient) are unwritable for adopters. Combined with the
self-covering gates of §22.F (atoms/ambient — the CSS-fork tiers' realistic top) and the Move-1 delivery
substrate, the design's *"unrepresentable at every tier"* is now achieved **everywhere the Lit substrate
permits it**. There is no further structural work on the 574 thesis.

**Verification:** typecheck clean; **2709** FE unit tests (incl. 4 `ModalController` + 3 new dismiss tests);
all 17 presentation gates + both codegen `--check`s green; `./gradlew.bat build -x test` SUCCESSFUL.
**Live (real browser, Vite worktree FE):** the modal contract round-trips (a ConfirmDialog locks
`documentElement.overflow` on open, releases on close, and **restores focus to the opener**, dialog modal),
and an adopting transient (Peek) **dismisses on outside-click + Escape** while an inside click does not. Not
merged — that is the user's.

## §23 Landing of §22.C–G + the indirect follow-on backlog (2026-06-11, `worktree-574-presentation-kernel`)

### §23.A The §22.C–G landing (second landing of this worktree)
The §22.B increment landed earlier (`ff675e591`). This is the second landing — the §22.C–G body:
committed (`70ac190ab`) → synced `origin/main` (conflict-free vs the 4 published commits, merge `07599b772`)
→ verified green (typecheck, 2709 FE tests, 17 presentation gates, `gradlew build -x test`).

- **ui-bundle hard-cap raise (deliberate policy edit, user-approved).** §22.C–G added ~2,198 B of eager core
  (the `jf-error-alert` atom + `ModalController`/`TransientController` + registrations, net of deleted forks),
  crossing the `app_main_bytes` hard cap the prior 569/574 changeset flagged with a ~69 B HEADROOM WARNING.
  Per the changeset author's named options (Shell-chrome decomposition *or* override/raise), and since the
  decomposition is 560's `bundle-shrink` scope on shared code, the cap was raised **1,010,000 → 1,020,000** in
  `scripts/ci/ui-bundle-budget.v1.json` (commit `89ce2fe76`) with a `declared-growth` changeset. Modest (~0.8 %
  headroom) so the cap stays a live forcing-function for the 560 decomposition.
- **Merged into local `main` (`7c9b6b9fe`), NOT pushed** — per the standing constraint that the final push is
  the user's. Local main carried 22 *unpushed* other-agent commits (560 §28 / 565 / observations); merging
  surfaced one real conflict in `BrowseSurface.ts`, resolved by **combining both sides** — the §22.D
  `jf-error-alert` migration + HEAD's obs-#375 `.body`-region rename (no stale `.scroll`). `SettingsSurface.ts`
  auto-merged.
- **Live-validated on the *merged* tree** (Vite on the merged source + shared backend, deep-shadow probes):
  BrowseSurface renders `jf-error-alert` (`role="alert"`) in the `.body[role=tree]` region; SettingsSurface
  mounts (351 nodes, no crash); all 8 §22.C–G custom elements load; `ModalController` round-trips scroll-lock
  (inline `documentElement.style.overflow` `hidden`→baseline) + `showModal()` top-layer.
- **One pre-existing red, NOT this work:** `check-theme-token-closure` fails on a `--text-warning` ghost token
  at `SettingsSurface.ts:1459`, introduced by 560 §28 `119cb23db` (already red on pre-merge main). The 560 §28
  owner's to fix before pushing.

### §23.B Indirect follow-on backlog (surfaced by 574's completion — NOT yet scoped/started)
574's thesis has no further *structural* work (§22.G). These are the ripples its completion enables/necessitates,
ordered by directness. **Recorded for the user to pick from — none started.**

- **(a) Atom-fork drawdown** *(Tier 1 — the ratchet's purpose).* `atom-fork-ratchet-baseline.v1.json` still
  carries **10 forked rules / 9 files** (badge/pill/chip/tag/status-dot/outcome-tag). Migrate onto
  `jf-status-badge`/`jf-status-dot`/`jf-error-alert`; `--rebalance` shrinks the baseline. The literal
  "as forks migrate" intent of Move 3.
- **(b) Style-literal drawdown** *(Tier 1).* `style-literal-ratchet-baseline.v1.json` carries raw `font-size`
  (+ a few z-index/transition) literals across **16 files**. Migrate to `--font-size-*` / `--z-overlay-*` /
  `--duration-*`+`--ease-*`; shrink the baseline.
- **(c) Fold the right-drawer drawers onto `TransientController`** *(Tier 2 — extend Move 4's reach).* The
  right-drawer transients (`AdvisoryInboxDrawer`, `AgentActivityPanel`, `RetrospectivePanel`, `SourcesPane`)
  still hand-wire `registerRightDrawer`/`closeOtherRightDrawers` via `rightDrawerArbiter` — arbitrated but NOT
  by-construction, and absent from `transients.v1.json`. `TransientController` already supports the
  `right-drawer` layer. Caveat: `rightDrawerArbiter`'s wrapper is the *intentional* 565/574 design — this is
  "extend the primitive," a judgment call, not a defect.
- **(d) Refresh stale ui-bundle vendor matchers** *(Tier 3 — observed during this landing, mine).* `vendor_react`
  / `vendor_motion` match no chunk under vite 7 (only `vendor-zod`/`lumino`/`ses` exist) — two `missing-metric`
  warnings the changesets flag as a non-blocking follow-up. Refresh the policy metrics to real chunks (or drop
  the dead ones).
- **(e) Canonical docs for the as-built kernel** *(Tier 3).* The four moves / prevention ladder
  (Collapse > Generate > Gate) / primitives live in tempdoc history + CLAUDE.md bullets, not in canonical
  `docs/explanation`. Document the kernel there + regen `llms.txt`/`skills-sync`.
- **Out-of-scope (flag, don't pull in):** (f) `chrome/Shell.ts` decomposition (560 `bundle-shrink` — 574 raised
  the pressure); (g) the `--text-warning` ghost token (560 §28); (h) `worktree-558-presentation-pairs` overlap.
- **Explicitly NOT to do:** re-open the atom/ambient "make CSS forks unrepresentable" question — design-resolved
  (§22.G): the self-covering gate is the substrate ceiling there.

## §23.C As-built — the §23.B backlog implemented (2026-06-11, `worktree-574-presentation-kernel`)

All four items implemented the structural way; static-green + live-verified. **Not merged — landing is the user's.**

- **(d) vendor matchers** — removed the dead `vendor_react` / `vendor_motion` matchers + `*_bytes` metrics from
  `scripts/ci/ui-bundle-budget.v1.json` (react/framer-motion absent; real chunks zod/ses/lumino; `max_js_chunk_bytes`
  still guards oversize). The two `missing-metric` warnings are gone; ui-bundle gate green.
- **(a) atom-fork — baseline now `{}` (all 10 cleared the STRUCTURAL way):**
  - **3 status composes** → `jf-status-badge`: `StatusDeck` (`.pill.*` → tone success/error/warning/neutral; the
    dead `.pill.indexing` removed — `StatusTier` has no `indexing`), `BootPhasesPanel` ("projection" label →
    tone="info"), `AgentEmitterDemo` (demo chip → tone="warning").
  - **1 new `@atom` `jf-filter-chip`** (`components/FilterChip.ts`) for the AHA filter-toggle family (LogSurface
    severity/sub-category + AdvisoryInboxDrawer class/transport/outcome) — active tint projects from `statusTone`;
    both consumers compose it (their `.chip` base rules deleted).
  - **1 new `originatorTone` authority** (`utils/originatorTone.ts`, mirrors `statusTone.ts`) projected through a
    new `jf-status-badge` `origin` attr — unifies EffectAuditLog's origin chips (user/agent/system) +
    RetrospectivePanel's originator badges (which were a latent bug: `--accent-primary === --accent-tint` made
    purple/teal identical). Now agent=purple / user=teal / system=neutral, DISTINCT. (`accepted` added to
    `statusToTone` success for the outcome chips.) `origin` (not `role`) to dodge `HTMLElement.role`.
  - **2 `ahaDistinct` exemptions** (genuine one-offs, no 2nd instance, not "a badge with a tone"):
    `SourceChipsRenderer` (numbered citation) + `DispatchSource` (operational provenance — sibling to
    `ProvenanceChip`). Documented in `atom-facets.v1.json`'s description.
- **(b) style-literal — baseline shrank 20 → 12:** the **8 genuine `em` literals** (MarkdownBlock, RunNode,
  DispatchSource×3, UnifiedChatView×3) → `--font-size-*` tokens. The **12 `var(--justsearch-shell-*, <rem>)`
  fallbacks were NOT tokenized**: they mirror the SEPARATE `--jf-text-*` form-typography scale and the
  theme-coverage **I2 contract** requires the fallback equal the catalog default — tokenizing breaks I2 or
  needs a broad `--jf-text-*` → `--font-size-*` re-scale (a follow-up, out of scope). They stay baselined.
- **(c) right-drawer fold** — `TransientController` extended with a backward-compatible `dismissExclude` predicate;
  all 4 drawers (`AdvisoryInboxDrawer` w/ rail-badge exclude + dismiss, `AgentActivityPanel` no-dismiss + leak
  fixed, `RetrospectivePanel` + `SourcesPane` w/ registration lifted from their now-pure stores) compose it;
  `state/rightDrawerArbiter.ts` **deleted**; the 4 added to `governance/transients.v1.json` (8 adopters); gate green.

**Verification.** typecheck; **2714** FE unit tests (incl. new `originatorTone` (4) + `dismissExclude` (1); 9 query
tests updated to the atoms, intent preserved, none weakened); full presentation gate suite + ui-bundle +
run-renderers (FilterChip registered as a toneSite) green; `./gradlew.bat build -x test` SUCCESSFUL.
**Live (real browser, Vite on this worktree):** originator colors render DISTINCT (agent purple / user teal /
system neutral — the bug fix confirmed); `jf-filter-chip` active tints distinct (error red / info teal);
StatusDeck badge renders in-situ (`tone="warning"`, no leftover `.pill`); right-drawer **single-open by
construction** confirmed (opening AgentActivity closed the open Advisory drawer). (Renderer instability forced
bounded/construction probes over full-tree walks; the dismiss + EffectAuditLog-with-data + font-size paths are
covered by the unit suite + the arbitration gate + the primitive's §22.G live validation.)

**§23.C.1 Critical-review follow-up (2026-06-11).** A post-landing critical review raised two suspected logic
issues that BOTH dissolved under investigation: (#1) RetrospectivePanel's `originator` is typed
`EffectOriginator = user|agent|system` (`substrates/effects/index.ts:58`) — fully covered by `originatorTone`,
no unmapped-value regression (the new mapping is strictly more correct than the old buggy `agent?purple:teal`
binary that conflated user+system); (#3) the `TransientController` reconnect-while-open registration gap is
unreachable — the 4 drawers are statically/unconditionally mounted in `chrome/Shell.ts`, never
disconnect/reconnect while open. The ONE real residual was a *validation* gap: an in-situ screenshot revealed
the **neutral badge** (originator `system` / status `neutral`) had a near-backdrop `--surface-2` fill and read
as **plain text, not a pill** (the vivid tinted tones were unaffected). Fix (single-authority, in `StatusBadge`):
the neutral case now carries a `--border-strong` delineating border (`--badge-border`), so a neutral badge reads
as a pill regardless of fill contrast — improving status-`neutral` (e.g. StatusDeck "offline") too. Re-validated
in the real browser: `system` + `offline` now render as bordered pills, distinct from agent (purple) / user
(accent) and the success/error tones. typecheck + 2714 unit tests + the atom/run-renderers/style/theme gates +
ui-bundle + `gradlew build -x test` all green.

**§23.D Merge to current `origin/main` (2026-06-11).** §23.B was committed (`322e0f151`) and `origin/main`
(advanced 40 commits / 86 files past the base — 565 §26/§27, 575 §13–15, 560 §28) merged into the branch
(`c9dddf457`). Git auto-resolved the 5 overlapping files by region — including the ui-bundle policy (my
vendor-matcher removal + their `app_main` 967534→1010616 rebaseline combined cleanly) and `run-renderers`
(my FilterChip toneSite + their `runSegments` authority). The ONE semantic gap auto-merge left: origin/main's
565 §26.D Inbox tab added a NEW `<span class="badge purple">${p.state}</span>` (background-run state) that
relied on the `.badge` fork CSS my migration deleted — resolved by migrating it onto `jf-status-badge status=`
(tone-coded by run state, the structural choice), folded into the merge commit. Verified on the merged tree:
typecheck + **2731** FE unit tests + the presentation gate suite (atom-fork/run-renderers/style/transient/
modal/ambient/theme/color/liveness) + ui-bundle (app_main 1,018,520 < 1,020,000 cap) + `gradlew build -x test`;
live boot smoke (merged bundle mounts, all atoms defined, the `p.state` badge tone-codes running→info /
completed→success, originator + neutral-border render). Branch is 2 ahead / 0 behind `origin/main`. **Not
pushed — the final land is the user's** (local `main` carries its own unpushed commits).

## §24 Theoretical extensions — research on what the kernel makes possible (2026-06-11)

Pure forward-research pass (no code), 3 rounds: internal framing + ground; 3 parallel external-research
agents (declarative web-platform modality / design-token architecture / correctness-by-construction +
DS-testing); synthesis. Sources at the end. Nothing here is committed work — it is an idea ledger.

### §24.A The through-line
574 makes drift unrepresentable in the **source** (single authority + gate-on-source). The honest ceiling
(§22.G) — a hand-rolled CSS fork with a differently-named class is grep-invisible and slips the ratchet —
exists because the gates inspect *source text*, not rendered pixels. The strongest extensions all move
authority from the source to the **rendered output**: **generate** the CSS from one source (output correct by
construction), **validate** the rendered output (an oracle catches what source-grep cannot), and let users
**remix** the output safely (theming the system guarantees stays legible). The thesis of the next phase:
*from source-authority to output-authority.*

A relevant context note: the app is Tauri-shelled and pre-production, so adopting very-recent web-platform
features is far lower-risk than for a typical site — **except** that the macOS shell uses WKWebView (Safari
engine), so Safari gaps below are real for that target.

### §24.B Push the ladder higher (Gate → Generate / Collapse)
1. **Token GENERATE tier (highest leverage).** A small `generate-tokens.mjs` emits ONE
   `tokens.generated.css` (`:root { --x: v; }`) from a single source (a DTCG-lite JSON over the existing
   `governance/` token data), tier-validated (primitive → semantic → component; no component may reference a
   primitive). Consequences: the **"inline fallback == catalog default" invariant becomes by-construction**
   (the generator writes the only copy — `check-theme-token-closure` / `strip-token-fallbacks` shrink from
   *audit* to *verify-the-generator*); and it resolves the **two-font-size-scales debt** by forward-aliasing
   `--jf-text-* : var(--font-size-*)` in the generated file (zero-breakage), lint-banning *new* `--jf-text-*`
   authoring, migrating component tokens opportunistically, and deleting the aliases when usage hits zero.
   Full Style-Dictionary + DTCG is **not** worth it for a single web target; a lightweight in-repo generator
   captures the win without the dependency weight (upgrade path stays open).
2. **Lean the modality primitives on the platform.** `popover=auto` (Baseline 2024) natively gives the
   single-open auto-stack + light-dismiss (Esc/outside-click) + focus-return that `TransientController`
   hand-rolls; Invoker Commands `command`/`commandfor` (Baseline Dec 2025) wire open/close with zero JS; CSS
   Anchor Positioning (all majors early 2026) replaces JS positioning; `@starting-style` +
   `transition-behavior: allow-discrete` give declarative enter/exit. **`<dialog>.showModal()` already owns
   scroll-lock + focus-restore natively** — so `ModalController`'s value is the *Collapse guarantee* (can't
   half-wire), not those mechanics. Net: the controllers can SHRINK to the residue the platform still does not
   give — `dismissExclude` (no declarative equivalent), scroll-lock for non-modal drawers, cross-type
   close-all, and the property-driven focus bridge. Caveats: `dialog closedby="any"` (modal light-dismiss) and
   the `overlay` exit-animation are **not in Safari/WKWebView** yet → keep the JS fallback for the macOS shell.

### §24.C Close the honest ceiling (the grep-invisible CSS fork)
3. **Rendered-style oracle gate (high leverage; technique already proven here).** A browser-rendered check
   (Playwright / Web Test Runner — **not jsdom/happy-dom, which cannot resolve CSS custom properties**) mounts
   each `@atom` + asserts its *computed* output equals the authority's resolved token (e.g. a `jf-status-badge`
   `tone="success"` background must compute to `--accent-success-16`), and runs `@axe-core/playwright`
   `color-contrast` to catch "a fork rendered an inaccessible colour." This catches the differently-named fork
   the source-grep ratchet admits it misses — it asserts *output*, not source. The §23.C.1 neutral-border fix
   was validated exactly this way (a computed-style probe in the real browser), so the technique is known-good
   in this codebase; the new infra is a browser test-runner tier (today's unit tests use happy-dom). Honest
   limit: an oracle tests one resolved state per case (hover/focus/forced-colors need their own), and the
   *full* Collapse of class-name forks needs a typed styling model (vanilla-extract/Sprinkles), a large
   styling-model change not worth it for the gain.

### §24.D Simplify
4. **Catalog-driven gate runner.** The ~10 bespoke `check-*.mjs` are textbook *architecture fitness functions*;
   replace the N-place `package.json`/CI discovery with one manifest/dir-discovered runner that emits a
   **run-record** (which gates ran / passed / skipped) — so a *missing* gate-run becomes itself detectable
   (the meta-guard the prose-tier-register already gestures at). Keep per-concern isolation (do not merge the
   concerns into one coupled engine — a silent skip-all is worse than a missed single check).
5. **`contrast-color()` to retire the `--accent-on-*` token family.** Baseline ~April 2026; returns the
   higher-contrast of black/white at runtime, so `color: contrast-color(var(--accent-tint))` removes the
   hand-maintained on-colour tokens. Pair with a build-time APCA gate (`culori` + `apca-w3`) over a declared
   `contrast-pairs` catalog for the fixed structural pairs. WKWebView-recency caveat applies.

### §24.E New UX the substrate de-risks
6. **A "can't-make-it-unreadable" live theme editor.** Because all colour routes through the tone authorities
   over oklch primitive hues (`--h-teal` …), a user could remix hues and the **contrast oracle/gate guarantees
   legibility by construction** — locking lightness per semantic role (`oklch(75% … var(--h-*))`) keeps
   contrast fixed across any hue swap. The kernel turns "user theming" from a footgun into a safe feature.
7. **Density modes (compact / comfortable).** Once sizing routes through ONE token scale (depends on #1), a
   single token-set swap rescales the whole UI safely — no per-component work, because every size is a token.
8. **A motion authority + reduced-motion by construction.** The `--duration-*` / `--ease-*` tokens already
   exist; a thin motion authority that every animation routes through (respecting `prefers-reduced-motion`)
   plus the declarative `@starting-style` transitions makes "an animation that ignores reduced-motion"
   unrepresentable — the §16-style cut applied to motion.

### §24.F Honest priority read
Highest leverage + most aligned with the through-line: **#1 (token generate)** and **#3 (rendered-style
oracle)** — they convert two *gates* into stronger rungs and resolve the two open debts (two-scale, the
ceiling). **#6 (safe theme editor)** is the most compelling *new* capability and is *unlocked* by #1+#3. #2/#5
are real but platform-recency-gated (WKWebView). #4 is cheap hygiene. None is urgent (no users); all are viable.

### Sources (key)
Popover API / Invoker Commands / Anchor Positioning / `@starting-style` (MDN, web.dev, caniuse, Open UI;
Popover Baseline 2024, Invoker Commands Baseline Dec 2025, Anchor Positioning all-majors early 2026).
DTCG format 2025.10 + Style Dictionary v4; 3-tier token model (Salesforce/Material/Supernova/Rangle);
`contrast-color()` Baseline ~2026; oklch contrast (Evil Martians); `apca-w3` + `culori`. "Parse don't
validate" (King) + impossible-states (Elm/KCD); architecture fitness functions (ThoughtWorks);
dependency-cruiser / `@eslint/css`; Lit testing (browser, not jsdom — jsdom cannot resolve CSS vars);
`@axe-core/playwright` computed-style + contrast oracle; vanilla-extract/Sprinkles (typed styling = the only
true Collapse for token values, not class names).

## §25 The correct long-term design — 574 as one altitude of the single projection spine (2026-06-11)

This is the design-theory section (house genre: feasibility disregarded; major rewrites in scope; **extend the
kernel, never fork**). It supersedes §24's idea-ledger with the correct long-term *structure*. The governing
finding of the investigation — across the codebase and the closely-adjacent tempdocs (530, 557/558/559,
564, 565, 567, 569, 575) — is that **every open edge of 574's kernel is correctly resolved by CONVERGING onto
a design the codebase already runs or has theorized, not by building a parallel mechanism.** The §24
"source-authority → output-authority" through-line sharpens to its real end-state below.

### §25.A The thesis in one move
The codebase runs **one projection spine at every altitude**: a single authority *declares*, typed
*projections* derive from it, a register-backed *gate* guards drift, and the second authority is made
*unrepresentable* (557/559 at the presentation tier; 564 at the wire tier; 575 at the observability tier; 530
is the gate engine). 574's **ATOM + AMBIENT tiers are the presentation-leaf altitude of that one spine.** The
correct long-term design is therefore **not "574 + more 574 features" — it is 574's pieces ceasing to be a
parallel thing and becoming first-class citizens of the spine**, so the kernel's open edges dissolve into
machinery that already exists. Stated fully: **the team authors a closed vocabulary; the system GENERATES the
type-safe token NAMES + the typed projections, VALIDATES the rendered output, and lets users COMPOSE
presentation over that vocabulary — with token-validity, contrast, and single-open all true by construction.**
_(Scope honesty, per the Edge-1 re-scope in §25.B item 1: "GENERATES" means the token NAMES (`gen-token-names`
→ the `TokenName` branded type) + the typed projections — NOT the base token **values**, which stay
hand-authored + closure-gated, because ~45% of `tokens.css` is non-generatable structural CSS. And "VALIDATES
the rendered output" is the LOCAL/on-demand oracle, with the `atom-fork-ratchet` source-grep gate as the
automated CI floor — see §25.B item 3 / §25.F. The "users COMPOSE" pillar is tempdoc 569's work, not 574's.)_

### §25.B Per-edge correct structure (each edge = converge onto an existing design)
1. **The two type scales → consolidate onto the single global `--font-size-*` scale (resolves obs #352).**
   _(Re-scoped 2026-06-11 after the §25 implementation confidence pass + as-built — see the correction note
   below; the original framing over-claimed.)_ Today token *names* are generated (`gen-token-names` → the
   `TokenName` branded type → a misspelled token is a compile error), but token *values* are hand-authored in
   `tokens.css`, and two type scales (`--jf-text-{xs,sm,md,base,lg}` in `themes/primitives.css` vs the 574
   `--font-size-{xs..xl}` in `styles/tokens.css`) coexisted (obs #352). **Correct long-term (as-built §25
   Phase 3):** the 574 `--font-size-*` scale is the single global type-primitive authority; the theme
   system's semantic type tokens (`--jf-text-*-size`, default.css) **project from it directly**, and the
   legacy `--jf-text-{xs..lg}` duplicate scale is deleted. The theme-coverage catalog teaches the
   `--font-size-*` block as a primitive layer so the `component → semantic → primitive` chain still bottoms
   out (I5). One type scale, no divergent rems.

   > **Correction (2026-06-11) — two over-claims in the original framing:**
   > 1. **`DesignTokenTree` is NOT the base source.** 567's `DesignTokenTree` is the *theme-OVERLAY* format
   >    (`@layer user-theme`, DTCG value-tree). It cannot become "the one authored source that the host
   >    compiles to the base CSS," because ~45% of `tokens.css` is **structural CSS** — `@layer` declarations,
   >    selectors, `@keyframes`, `:root`/`[data-theme]` blocks — that a value-only tree cannot express. A
   >    "generate the base CSS from the DTT" move would need a *parallel structural-CSS authority*, i.e. a new
   >    fork — the wrong lever. The right altitude: base token **values** stay hand-authored in `tokens.css`,
   >    with **names** generated + closure-gated (`gen-token-names` + `theme-token-closure`). The two-scale
   >    consolidation above does NOT need a generator — it is a direct re-point + delete.
   > 2. **The "inline fallback == catalog default" invariant STAYS load-bearing — it is NOT retired.** The
   >    theme-coverage I-test model *requires* the catalog-mirror literal to be present: `extractRendererTokens`
   >    only matches `var(--x, <fallback>)` *with* a fallback, so I3 counts a component token as referenced
   >    only through a fallback-bearing ref, and I2 validates that the fallback equals the resolved catalog
   >    default. **Stripping the fallback** (the `strip-token-fallbacks` policy, which seemed cleaner) would
   >    orphan the component token under I3 **and** drop the I2 check — so the 12 §23.B catalog-mirror
   >    fallbacks **remain**, now mirroring the `--font-size-*` resolution (`form-label-size→sm 0.8125rem`,
   >    `section-title→lg 1.125rem`, `status-header/table→md 0.9375rem`), kept honest by I2. The
   >    style-literal-ratchet font-size tail stays at 12 (count unchanged; the literals are test-required, not
   >    drift). Clearing them would mean *changing the I-test contract* (weakening I3's orphan check), which is
   >    out of scope for "consolidate the scales" and is not warranted.
2. **The tone authorities (`statusTone`/`originatorTone`) → semantic ROLES with contrast-floor derivation
   (converge onto 558 §2).** Today they map a status/originator to a *fixed* accent + a hand-tuned soft-bg /
   solid-text pair; the §23.C.1 neutral-border fix and the contrast question are patches on a model that does
   not *derive* legibility. Correct long-term: a tone is a 558 **semantic role whose foreground is derived
   from its background to a contrast floor**, so "an unreadable badge / an off-palette pair" is
   *unrepresentable*, not gate-caught. `statusTone`/`originatorTone` become roles in that one role authority;
   the neutral case, the on-colours (CSS `contrast-color()` retires the `--accent-on-*` family), and the
   safe-theme-editor's legibility guarantee all fall out of the derivation. The §24 "build-time contrast gate"
   becomes a *backstop*, not the mechanism — the mechanism is the role derivation (the 558 model already in
   the codebase's design corpus; obs #331 is the live bug it closes).
3. **The honest ceiling (the grep-invisible CSS fork) → authored-presentation-IS-a-projection-over-a-closed-
   vocabulary (converge onto 569 + 560), with an output oracle as the transitional backstop.** §24 framed the
   close as "add a rendered-style oracle." The deeper correct structure (569): a hand-rolled CSS fork is
   *unrepresentable* when the only way to author presentation is to compose the **closed `jf-*` vocabulary +
   `TokenName`s** (560's `isPresentationAdmissible` / `jf-*` confinement) — i.e. raw-CSS authoring is *not on
   the path*. The atom vocabulary 574 Move 3 built is the seed of exactly that closed vocabulary. So the
   long-term close is 569's closed-vocabulary projection; the **rendered-style + contrast oracle (extend the
   EXISTING Playwright `e2e/` + `accessibility-audit.spec.ts` harness — not new infra; the §23.C.1 fix was
   validated this exact way)** is the *bridge* that catches output-drift while the vocabulary closes, and its
   job shrinks as 569 lands. (Honest limit, unchanged: full Collapse of class-*name* forks needs the
   closed-vocabulary path — 569 — not a typed-CSS rewrite, which is the wrong lever.)
   **Enforcement-tier honesty (as-built):** the oracle is a Playwright e2e spec, and the e2e suite is **not run
   in CI** (no workflow invokes `playwright test`; ADR-0026 local-first + all 48 e2e specs are local). So the
   oracle is a **local / on-demand** stronger check (run in the browser batch / `npx playwright test
   e2e/atom-render-oracle.spec.ts`), **NOT an automated CI gate**. The automated CI floor for the
   grep-invisible-fork class stays the **`atom-fork-ratchet` source-grep gate** (folded into the 530 kernel,
   §25 Phase 4) — the oracle catches what the ratchet's source-grep cannot (a wrong RENDERED colour under a
   differently-named class) only when someone runs it. "Catches output-drift" is therefore on-demand, not
   continuous; do not read it as a standing gate.
4. **The presentation gate family → fold into the 530 discipline-gate kernel.** Today the presentation
   `check-*.mjs` (ambient-purity / atom-fork-ratchet / modality / transient / style-literal) ride *outside*
   the 530 kernel as standalone `scripts/ci` scripts. Correct long-term: they become registry rows in the 530
   kernel — reusing its SARIF + changeset-justification + baseline + auto-rebalance machinery and gaining a
   **run-record** so a *missing* gate-run is itself detectable (the meta-guard the prose-tier-register already
   gestures at). 530 is explicitly built to absorb new gate-kinds; this is "extend the kernel, never fork"
   applied to 574's own gates. (Keep per-concern isolation *within* the kernel — one coupled engine risks a
   silent skip-all.)
5. **The runtime-modality primitives → keep the by-construction contract; lean the IMPLEMENTATION on the
   platform.** `ModalController`/`TransientController` are already at the **Collapse** rung — a half-wired
   modal / unarbitrated transient is unrepresentable for an adopter — and *that invariant is the long-term
   keeper.* The implementation should shrink onto the maturing platform (Popover auto-stack, Invoker Commands,
   anchor positioning, `<dialog>` native scroll-lock/focus-restore) as it covers single-open / dismiss /
   positioning, leaving the controller owning only the residue (the `dismissExclude` predicate, drawer
   scroll-lock, the property-driven-open focus bridge). **The Tauri shell is Windows-only WebView2/Chromium**
   (`tauri.conf.json`), so Popover `auto`, Invoker Commands, and CSS anchor positioning are *all* Baseline-
   supported on the only engine that ships — the WKWebView/Safari recency caveat that earlier sections (§24)
   raised is **moot** (there is no macOS/WKWebView target). This is implementation evolution under a stable
   contract, not a redesign.

### §25.C The unifying principle
574's endgame is to **disappear as a separate thing**: its atom vocabulary, tone authorities, ambient
delivery, and runtime-modality contract become the presentation-leaf altitude of the one spine —
*declaration* (`DesignTokenTree` + the `jf-*` vocabulary + the role authority) → *projection* (the atoms /
ambient render it) → *output* (oracle-validated) → *governed* (the 530 kernel) → *user-authored* (569's
inversion makes it remixable). The "honest ceiling" the kernel admits is **not closed by a cleverer gate — it
is closed by removing raw-CSS authoring as a path (569)**, with the output oracle as the bridge. None of this
is new infrastructure: it is convergence onto designs the codebase already runs (530), has theorized
(558 / 567 / 569), or partly built (`DesignTokenTree`, `TokenEditorPlugin`, `gen-token-names`, the Playwright /
axe harness, `migrate-font-size-tokens`).

### §25.D Dependency map (an ordering, NOT an urgency claim — there are no users)
`#1` (one token source) is the keystone: it unblocks `#2` (roles derive from real token values) and the
density/theme capabilities. `#2` (contrast-roles) turns the §24 safe-theme-editor from "guarded by a gate"
into "true by construction." `#3`'s output oracle can land independently (it extends the existing Playwright
harness) and is the bridge until 569 closes the vocabulary. `#4` (gate fold) is orthogonal hygiene. `#5`
(modality on the platform) is platform-gated. Highest-leverage convergence: **`#1` + `#2`** — they convert two
gates and a patch into by-construction correctness and unlock the safe theme editor. The whole is the
completion of the move 557 began and 569 generalizes: *the frontend's appearance is a governed projection of
one authored vocabulary, correct from the declaration to the rendered pixel.*

### §25.E As-built — the §25 implementation (2026-06-11; Phases 1–4 landed, Phase 5 + browser batch pending)

The §25 design was implemented as five independently-landable phases on `worktree-574-presentation-kernel`.
What actually shipped, with the corrections the implementation surfaced (each is a deliberate deviation from
the §25.B prose, recorded here so the prose is not read as as-built):

- **Phase 1 — Edge 2 (contrast-roles), DONE.** `themes/roleForegrounds.ts` `COLOR_ROLES` now PROJECTS from the
  single `themeRoles.ts ROLE_CATALOG` (all six roles, each carrying its WCAG `floor`), so the deriver and the
  catalog cannot drift and every on-colour derives to its floor. **Correction vs §25.B:** the static
  `--accent-on-*` values are NOT removed — the docstring + the `check-theme-token-closure` gate prove they are
  the intentional FALLBACK (the derived `user-override` layer wins at runtime); removing them creates ghost
  tokens. The structural fix is unifying the deriver onto the catalog, not deleting the fallback.
- **Phase 2 — Edge 3 (output oracle), DONE.** `e2e/atom-render-oracle.spec.ts` mounts each `@atom` and asserts
  the COMPUTED colour equals the resolved authority token + a **deterministic in-page WCAG-AA ratio per atom**
  (canvas read-back resolves the oklch tokens — axe is NOT usable here: the app freezes intrinsics, which blocks
  axe-core's instrumentation with `Cannot assign to read only property 'get' of WeakMap`). It navigates light
  (goto domcontentloaded + `waitForFunction(customElements.get('jf-status-badge'))`, NOT `networkidle`, which
  never settles without a backend). **Enforcement tier:** this is a **local / on-demand** check — the e2e suite
  is not run in CI (ADR-0026), so the oracle is the stronger check run in the browser batch, while the
  **`atom-fork-ratchet` source-grep gate is the automated CI floor** (see §25.B item 3). It is the
  grep-invisible-fork bridge, on-demand — not a standing gate.
- **Phase 3 — Edge 6 + Edge-1 re-scope, DONE.** The two type scales are consolidated: `--jf-text-{xs..lg}` is
  deleted; the theme's semantic type tokens project from `--font-size-*` directly. See §25.B item 1's
  correction note for the two over-claims dropped (DTT-as-base-source; the I2 invariant is NOT retired — the
  catalog-mirror literal is test-REQUIRED, so the consolidation is value-level, kept honest by I2).
- **Phase 4 — Edge 4 (gate fold), DONE.** All six presentation gates (`style-literal-ratchet`,
  `atom-fork-ratchet` [ratchets]; `transient-arbitration`, `modal-arbitration`, `modality-contract`,
  `ambient-purity` [positive-coverage / scan]) are folded into the 530 kernel as registry rows + per-gate
  enforcer/truth-table/(classifications)/rule-descriptions, via two shared adapter factories
  (`lib/ratchet-gate.mjs`, `lib/scan-gate.mjs`). **Detection stays ONE authority** — each `scripts/ci/check-*.mjs`
  is refactored to export a pure rooted `detect()` that BOTH the CLI back-compat guard and the kernel enforcer
  call; the shared JSON ratchet baselines are read in place, not moved/forked. Each gate ships positive/negative
  self-test fixtures (the kernel's `--self-test` now covers them — a regression in the gate machinery is caught).
  `ci.yml` + the CLAUDE.md pre-merge list re-point to `run.mjs --gate <id> --mode gate`; the standalone CLIs
  still work. Kernel gate count 27 → 33; full `run.mjs --self-test` exits 0.
- **Phase 5 — Edge 5 (modality-on-platform spike), BUILT (browser-measurement in the final batch).** The spike
  is `src/spike/NativePopoverSpike.ts` (flag-gated on `?spike=native-popover`, so production never mounts it;
  it voluntarily `extends JfElement` but lives OUTSIDE `shell-v0` so it doesn't entangle the atom/ambient
  gates) + the measurement spec `e2e/native-popover-spike.spec.ts`. **What the platform absorbs (measured by
  the spec on Chromium):** `popover="auto"` gives **single-open** (opening one auto-popover light-dismisses
  every other — exactly the `registerTransient`+`closeOthersInLayer` triad) AND **light-dismiss** (Escape +
  outside-click — the `managesDismiss` capture pair), both for free; **CSS anchor positioning**
  (`anchor-name`/`position-anchor`/`anchor()`) absorbs the measure-and-place JS; **Invoker Commands**
  (`command`/`commandfor`) give a declarative no-JS-handler toggle (its engine support is itself measured;
  `popovertarget` is the Baseline-2024 fallback). **Residue `TransientController` would still own** (the
  spike's finding, the input to a later controller-SHRINK — not a migration): (1) **cross-LAYER** coordination
  (`popover=auto` arbitrates only the auto-popover set; the `transient`-vs-`right-drawer` layer distinction is
  not a native concept — `transientLayerArbiter` stays the authority); (2) **`dismissExclude`** (suppressing
  dismiss for a click on an external opener — no native equivalent); (3) **drawer scroll-lock + the
  property-driven focus bridge** (orthogonal to popover). So a future shrink replaces the dismiss + same-layer
  single-open triad with `popover=auto`, leaving the controller a thin cross-layer + exclude shim — under the
  SAME by-construction contract (the `check-transient-arbitration` gate is unaffected). Static-green: typecheck
  clean; the spike file is born-clean under the style-literal ratchet; ambient/atom gates unaffected.
  **Scope honesty (as-built):** the e2e spec measures the **platform primitives** directly — raw
  `popover`/`anchor`/`command` markup injected via `setContent` (an isolated page, so outside-click dismiss has
  clean space and there is zero app interference) — NOT the `NativePopoverSpike` element. `NativePopoverSpike.ts`
  is therefore a documented **reference** artifact: it is *defined* but never mounted or exercised
  (`maybeMountNativePopoverSpike` is unwired; production never imports it), so its `render()` is illustrative,
  not under test. The measurement (what the platform absorbs) is the deliverable; the component shows the shape
  a later controller-shrink would take.
- **Final browser batch — DONE for Phases 1/2/5; Phase 3 closed by the critical-review fix.** On Chromium: the
  Phase-2 oracle passed (every atom text/fill pair clears WCAG AA incl. the #331 class; success badge computes
  `--accent-success`) and the Phase-5 spike passed (popover=auto single-open + Esc + outside-click dismiss +
  anchor positioning + Invoker Commands all confirmed). Three real harness bugs were fixed to get there (axe→
  in-page WCAG canvas; networkidle→light nav; the spike's `margin:0` top-left anchor→lower-right click). The
  `search` surface screenshot confirmed form controls + the teal `jf-button` CTA legible at the new sizes; the
  Phase-3 form-label/section re-scale on the heavier `renderers/controls` form surfaces is validated by the
  critical-review follow-up (see §25.F).

### §25.F Critical-review follow-up (2026-06-11) — two verification-completeness gaps closed

An independent critical pass over the landed §25 work (Phases 1–5) found **no logic errors, failed gates, or
broken behaviour** — the code is correct. It surfaced two *verification*-completeness gaps (the implementation
worked, but its validation didn't match the tempdoc's framing / the "browser-validation is the bar" intent),
now closed:

- **Fix 1 — enforcement-tier framing corrected (docs-only).** The Phase-2 oracle + Phase-5 spike e2e specs are
  **not run in CI** (no workflow invokes `playwright test`; ADR-0026 local-first, like all 48 e2e specs). §25
  Edge 3 / §25.E Phase 2 had read as if the oracle were an automated drift-catching gate. Corrected (§25.B
  item 3 + §25.E Phase 2/5): the oracle is a **local / on-demand** stronger check; the **automated CI floor for
  the grep-invisible-fork class is the `atom-fork-ratchet` source-grep gate** (§25 Phase 4). Also corrected a
  stale claim (the oracle uses an **in-page WCAG canvas check**, not axe — the app freezes intrinsics, blocking
  axe instrumentation) and recorded that `NativePopoverSpike.ts` is a **reference** artifact (the spec measures
  the platform primitives via `setContent`, not the component). _User decision: keep e2e local, fix the claim —
  no CI wiring._
- **Fix 2 — Phase-3 form re-scale browser-validated on its real consumers (validation, no edit).** The
  `renderers/controls/*` + `GroupLayout` forms consume `--justsearch-shell-form-{section-title,label}-size`,
  which `ui-shot` couldn't reach (stale `activity-*` nav test-ids — logged to observations). Validated instead
  by injecting the real tokens on a live demo page (isolated mount): computed sizes resolve exactly as intended
  — **section-title 18px (`--font-size-lg`), label 13px (`--font-size-sm`), status-header 15px
  (`--font-size-md`)** — and the rendered hierarchy reads cleanly + legibly (section > status-header >
  label/body). The section-title's +12.5% (1.0→1.125rem) is an appropriate settings-form header size, **not**
  too large → the contingent `--font-size-lg`→`--font-size-md` remap was **not** needed. Gap closed by
  confirmation; the durable guard remains the theme-coverage I2 invariant.

### §25.G Controller-shrink (Edge 5) — IN-PROGRESS implementation status (resume marker, 2026-06-11)

The Edge-5 controller-shrink (lean `TransientController` onto `popover=auto`) is **partially implemented**,
committed as a labeled WIP checkpoint on `worktree-574-presentation-kernel` (**NOT merged** — a partial
migration is a cross-overlay single-open hybrid). State for whoever resumes:

**Built + browser-validated:**
- `TransientController` gained a **popover-backed mode** (`popoverEl?: () => HTMLElement | null`): when supplied,
  `open()` calls `showPopover()` + binds a `toggle`-event listener that syncs `opts.close` on native dismiss
  (re-bound per element) + focuses the popover; `close()` calls `hidePopover()`. The 4 `right-drawer` drawers
  stay on the arbiter path (unchanged). Additive + behavior-neutral until adopted; the 8 arbiter unit tests stay
  green; the `check-transient-arbitration` gate stays green (all 8 still compose the controller).
- **Peek migrated** (1 of 4 transient overlays), validated with a single instance (show / center / Escape /
  outside-click / toggle-sync / re-open all work).

**Three non-obvious requirements discovered (recorded so resumption does not re-learn them):**
1. **Panels must be ALWAYS-rendered** — the Popover API requires the element to persist; toggle it via
   show/hidePopover, never conditional-render it away (conditionally removing the panel on close breaks
   re-open). Each overlay's render restructures so state drives CONTENT, not the panel's existence (Peek done).
2. **Focus must move into the popover on open** — a programmatically-shown popover isn't auto-focused, so
   Escape light-dismiss won't fire. The controller focuses the popover (auto `tabindex=-1`) as a baseline (also
   correct a11y); an overlay wanting item-focus overrides after `open()`.
3. **Validation friction — the demo shell mounts its OWN instance of each overlay.** Throwaway specs fight
   duplicate-instance native single-open (one popover closes the other → its state reverts), so they read as
   spurious re-open failures. Validate in the real shell (exactly one of each), driven through real UI — not a
   manual `createElement`.

**Remaining (the proven pattern applies):** migrate **SelectionActionsMenu** (selection-rect positioning),
**BookmarksPopover** (unwind its arbiter + own-doc-listener + popover TRIPLE-hybrid into clean popover-mode),
**ContextMenu** (dynamic per-instance controller, cursor positioning) — keep each overlay's bespoke positioning
JS (cursor / rect / corner have no trigger ELEMENT for CSS anchor). Then the comprehensive browser batch (all 4
overlays: single-open + Esc + outside-click + positioning + focus-restore; + the 4 drawers still work). Migrate
all 4 together (a half-migration is the cross-overlay single-open hybrid).
