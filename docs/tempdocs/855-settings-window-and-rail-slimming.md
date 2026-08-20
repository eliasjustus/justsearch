---
number: 855
title: Settings window — Discord-2025 centered-modal pattern + rail slimming
status: implemented — P0-P4 + closure remediation (§12) + owner-feedback content remediation R1-R3 + final fix round (§13-§18) shipped on worktree-855-settings-window; canonical docs updated; awaiting owner on D1 (Brain) and the PR/merge go-ahead; residuals in §12 and §18 final entries
created: 2026-08-19
updated: 2026-08-19
charter: replace the in-Stage settings page with a Discord-style categorized settings window, and decide which main-window chrome items relocate into it
---

# 855 — Settings window (Discord-2025 modal) + rail slimming

## 0. Provenance

Owner direction (2026-08-19): after months of UI rewrites, the standing strategy is to
copy frontend design from proven, well-developed products rather than invent. For the
settings window the named reference is **Discord**. This tempdoc records (a) a live
measurement pass on the real Discord web app (owner's session, 2026-08-19), (b) the
research verdict on *how* to copy it, and (c) the target information architecture,
including which main-window sidebar/chrome items move into the new window.

Related precedent: 578 (window taxonomy — RAIL/DEEPLINK/COMMAND, merge mechanisms,
"one System hub"), 571 §11 (host/member composition; Settings' current tabs come from
it), 629 (moved Security & Privacy *out* of Settings into `core.security-surface`),
ADR-0032 (Lit), ADR-0035 (plugin presentation boundary).

## 1. Research verdict — copy the spec, not code

- **No liftable open-source implementation exists.** The faithful replicas are all
  copyleft: Revolt/Stoat (`stoatchat/for-web`, AGPL-3.0 — verified against LICENSE;
  the legacy Revite repo's `package.json` *falsely* says MIT), Spacebar (AGPL),
  Element (AGPL/commercial). Vencord/Vesktop (GPL) are not reimplementations at all —
  they inject tabs into Discord's real closed-source settings tree. MIT "discord-clone"
  tutorials don't implement the settings shell. None are Lit anyway.
- **Legal posture:** layout/interaction patterns are unprotectable methods of operation
  (*Lotus v. Borland*); what must not be copied is assets, the proprietary `gg sans`
  font, brand colors as identifiers, or literal source. We re-derive geometry from
  observation and render on our own tokens.
- **Therefore:** the deliverable of "copy Discord" is the measured spec in §2, applied
  through shell-v0 primitives.

## 2. Measured spec (live Discord web, 2026-08-19)

**Discord replaced the famous full-screen takeover in its 2025 redesign.** Current
settings is a **large centered modal over the dimmed app** — which maps better onto
our substrate than the old takeover (no chrome replacement needed).

Captured from the DOM (CSS px; viewport 2067×1010 at the time):

| Element | Measurement |
|---|---|
| Modal frame | ~1400×866 (≈68% vw, ≈86% vh → max-width-capped, near-full-height), 12px radius, near-black surface, dimmed backdrop, X close top-right |
| Sidebar | 253px `<aside>`; sticky header (avatar + name + "Edit Profiles"), then a **settings search field**, then scrollable grouped nav |
| Nav groups | small 14px/500 group headers + divider rules: *General* · *Billing* · *Experience* · *Games & Apps* · *Other* |
| Nav item | 32px row; active = 8px-radius pill, **neutral** translucent bg `rgba(151,151,159,0.2)` (not brand color); inactive text dimmed |
| Danger item | **Log Out** red, last item, own group ("Other", after Developer) — spatially isolated |
| Nav footer | version string (click-to-copy) + Privacy · Terms · More links |
| Content pane | current-category title strip at top; **728px column centered** in the remaining ~1146px |
| Section headings | 24px `<h2>` |
| Setting rows | ~36px tall, ~18px gaps; 16px/500 label left, control right (toggle/dropdown/Edit) ; dividers between sections; composite content in 12px-radius cards, 24px padding |

**Interaction structure (the redesign's best idea):**
- Each category is **one scrollable page**; its `<h2>` sections are mirrored as
  **indented sub-anchors** in the nav under the category.
- **Accordion:** only the active category shows its sub-anchors; others stay collapsed,
  keeping the nav short.
- **Scroll-spy, two-way:** the highlighted sub-anchor tracks the content pane's scroll
  position; clicking a sub-anchor scrolls the pane (owner independently observed and
  confirmed this live).
- **Instant-apply** for toggles/enums (no save bar on e.g. Appearance); the
  "unsaved changes" bar exists only on form-like pages (profile editing).
- **Search-within-settings now exists** (the pre-2025 design lacked it).
- ESC closes; the old gutter "ESC" circle is gone — plain X in the modal corner.

Non-goals from the reference: Discord's colors, `gg sans`, brand identifiers.
Unresearched: Discord's small-width/responsive behavior (measure later if needed).

## 3. Fit to the existing substrate

Current state (all cites from the 2026-08-19 codebase map):

- Settings today is **not a window**: `core.settings-surface` is a rail surface mounted
  in the Stage — one page of ~17 `<h3>` sections + horizontal `<jf-surface-tabs>`
  (`views/SettingsSurface.ts:2173-2273`, `plugin-api/CorePlugin.ts:136-143`,
  `BOTTOM_RAIL_IDS` at `chrome/Shell.ts:317`).
- The modal mechanics already exist: `primitives/modalController.ts` (native `<dialog>`
  + focus-restore/scroll-lock, six consumers) and `chrome/OverlayHost.ts` `slot="center"`
  (the established full-viewport modal dock — command palette lives there,
  `--z-overlay-modal:2000` in `styles/tokens.css:56-65`).
- **The one missing primitive** is a vertical grouped nav with accordion + scroll-spy;
  `SurfaceTabs` is horizontal-only and `primitives/navigation.ts` has no vertical
  categorized variant.

The mapping is nearly mechanical: today's 17 sections become sub-anchors; the Discord
structure is *category page ⊃ `<h2>` sections ⊃ nav mirrors sections*. Sections move
by re-parenting, not rewriting. In 578's terms this is the **next evolution of the
System hub** (578 §5.5): same host/member composition thesis, with the horizontal
SurfaceTabs presentation replaced by the modal + grouped scroll-spy nav.

## 4. Target information architecture

Groups (nav headers) → category pages → sub-anchors (today's sections):

| Group | Category page | Sub-anchors (source) |
|---|---|---|
| *General* | **Appearance** | Interface, Theme, Accessibility (today's §1/§3/§2) + absorbed Appearance member tabs (Skins/Editor, 571/578) |
| | **Layout** | Layout, Rail customization, Keyboard (§5/§4/§8) |
| *AI* | **Agent** | Agent autonomy (§16; chat-toolbar compact dial stays as a shortcut into it — `UnifiedChatView.ts:2599-2604`) |
| | **Runtime** | Brain's config sections, contingent on D1 (§5 below) |
| *Privacy & Trust* | **Security & Privacy** | absorbed from `core.security-surface` (encryption, backups, auto-lock) — see §5 |
| | **Plugins** | Plugins (§6), Plugin permissions (§13), Durable grants (§14), Delivered contributions (§15) |
| *App* | **Desktop** | Desktop/autostart (§9), App updates (§10) |
| | **Developer** | View tier (§7), Workspace Profiles (§17) |
| *Other* (isolated) | **Data** | Delete all local data (§12) — red, last, own group; typed-DELETE confirm unchanged |
| footer | — | app version (click-to-copy); no profile header (no accounts) — search field tops the nav instead |

Feedback capture (§11's nested `renderFeedbackCapture`) lands under **App**.
Endpoint plumbing is unchanged: `/api/settings/v2` reads/writes, `core.reset-settings`
operation, grants/witness/plugins endpoints stay as-is — this is presentation-layer
re-homing only (ADR-0034 truth stays backend-owned).

**Interaction rules adopted:** instant-apply for toggles/enums (matches both Discord
and today's behavior); an unsaved-changes bar only if a future form-like page needs it
(none does today — do not build speculatively); ESC + X close; neutral-pill active
state on our tokens.

## 5. Rail slimming — what moves off the main window chrome

Argued in 578's taxonomy (RAIL = daily product destination; DEEPLINK = routable but
not homed on the rail; demotion = `Placement` change, membership = 571 §11):

**Moves into the settings window:**

1. **Security & Privacy** (`core.security-surface`, RAIL per `CorePlugin.ts:144-153`) —
   set-once configuration, not a daily destination; fails the "window per task, not
   per facet" test (578 §3) as a rail peer of Library/Chat. Becomes a settings
   category (kept whole, its own page). *This partially reverses 629's placement but
   not its substance*: 629's point was "own home with real sections", which the
   settings window now actually provides; 629's mechanism (own surface reached by
   deep-link) was the best available before this window existed. Its route stays
   DEEPLINK-valid (D2).
2. **Token Editor** (plugin RAIL surface, `plugins/token-editor/TokenEditorPlugin.ts:459-469`) —
   a design tool, not a daily destination. Demote off the rail; reachable via
   *General → Appearance* (link/launch affordance) and command palette. ADR-0035
   caveat: it is plugin-owned presentation — settings hosts a **link**, not the
   plugin's UI as a core category.
3. **Brain — config only, contested (D1).** The Brain surface (`views/BrainSurface.ts`)
   mixes set-once config (Offline pack import :2770, AI install :2839, Runtime :2963)
   with live status/activation (workflow). Discord's analog is Voice & Video: heavy
   device config in settings, live status in main chrome. Options:
   (a) config sections → *AI → Runtime* category; Brain stays RAIL as a slim
   status/product surface; (b) full demotion — config to settings, status folded into
   the status deck (`core.inference-mode` already exists, `StatusDeck.ts:68-89`).
   **Not decided here** — implementation phases are ordered so this decision is not
   load-bearing (§6).

**Stays in main chrome (fails the set-once test — workflow controls):**

- **Library, Chat** — true product surfaces; the rail's reason to exist.
- **Help** — already a fixed rail affordance, not a RAIL surface (578 §5.6); unchanged.
- **Advisory badge, rail expand/collapse** — live chrome state.
- **Settings rail button** — stays bottom-docked, but now opens the modal (§6 / D2).
- **Topbar Simple/Detailed toggle** (`chrome/Shell.ts:1836-1853`) — per-session
  workflow control; the Microsoft settings-IA test ("commands in the typical workflow
  don't belong in settings") says keep it in chrome. Optionally *mirrored* in
  Appearance later; never moved-only.

**End-state rail:** Library · Chat · (advisory badge) · Help · Settings — plus
whatever D1 leaves of Brain. Radically quieter; everything configuration-shaped has
one home.

## 6. Implementation shape (phased so contested calls don't block the shell)

- **Phase 0 — the primitive + frame.** `<jf-settings-nav>` (vertical, grouped,
  accordion active category, IntersectionObserver scroll-spy on section headings) +
  the modal frame (`<dialog>` via `ModalController` in OverlayHost `center`;
  max-width ~1200px, ~85vh, 12px radius, our tokens). Pure structure — no IA change.
- **Phase 1 — re-parent.** Today's sections into the §4 categories 1:1; retire the
  horizontal SurfaceTabs presentation of Settings; absorb the Appearance member tabs.
  Legacy `views/SettingsLitView.ts` (dead React-coexistence path per its own header,
  `SettingsSurface.ts:14-18`) is swept in the same PR (retire-with-a-sweep).
- **Phase 2 — absorb Security & Privacy** as a category; route compatibility per D2.
- **Phase 3 — demotions.** Token Editor off the rail; execute whichever D1 option the
  owner picks for Brain.
- **Phase 4 — fast-follow: settings search.** The scroll-spy already requires a
  registry of (category, section, label); search is a query over exactly that index.
  Ship the window without it; add it when the registry exists. (Here we *exceed* the
  reference deliberately — Discord only just added theirs.)

Verification per house rules: `npm run typecheck && npm run test:unit:run`, ui-shot
steps for the modal (open/close/focus-trap/scroll-spy), axe pass on the dialog
(853's audit lens applies — this is presentation-authority work, so measured
independent UX audit before closure).

## 7. Open decisions (revised after the §9 design probes)

- **D1 — Brain's fate — reframed by evidence, still the owner's call.** Section-level
  extraction of Brain's config is **off the table**: the three config sections share
  state fields, a single `busy` dictionary, one `invokeOp` funnel, and handlers called
  from two render trees (`BrainSurface.ts:1215,1308-1497,2765-3179`) — extraction
  would be surgery, violating the "verbatim move" economics that made 629's Security
  move safe. The real options are now: **(a)** Brain stays RAIL, unchanged, with the
  settings window's *AI* group linking out; **(b)** Brain is absorbed **whole** as a
  member category (catalog `withMembers` + placement→DEEPLINK — the same one-line
  mechanism as Security), with live status remaining in the status deck
  (`core.inference-mode`, `StatusDeck.ts:68-89`). Recommendation: (b), because it is
  cheap, reversible by the same one-line mechanism, and completes the "one
  configuration home" story; but Brain is the app's flagship AI surface, so this
  stays an owner decision. Phases are ordered so it lands last either way.
- **D2 — routability — SETTLED (no new grammar; see §9.4).** The router already
  redirects a member deep-link onto its host with a member-select intent
  (`catalogResolver.ts:97-113` → `memberTabIntent.ts`); absorbed surfaces stay
  deep-linkable *unchanged* (the readiness-notice remedies that `navigate` to
  `core.security-surface` keep working). Native categories round-trip via a declared
  `stateSchema` binding. The earlier `#/settings/appearance` idea is rejected as a
  parallel-grammar fork of `justsearch://surface/<id>?state`.
- **D3 — placement — SETTLED (Placement.MODAL; see §9.2).** `Placement.MODAL` exists
  in both type systems with zero producers/consumers; Settings becomes its first
  realization, and the rail keeps a fixed bottom Settings affordance per the Help
  precedent (578 §5.6). This deletes `BOTTOM_RAIL_IDS` and removes Settings from its
  own rail-customization list by construction.
- **D4 — group naming.** §4's names (*General / AI / Privacy & Trust / App / Other*)
  are straw; bikeshed at Phase 1, not before.

## 8. Honest limits

- The measured spec is one viewport (2067×1010) of one session on one day; Discord
  A/B-tests. Treat §2 as proportions and pattern, not gospel pixels.
- Discord's responsive/small-width behavior was not measured; our modal needs its own
  small-window rule (likely: sidebar collapses to a category list → page push, but
  design when needed).
- The §4 IA re-homes ~17 sections wholesale; individual section-level judgment calls
  (e.g. whether Witness is *Plugins* or *Developer*) are Phase-1 review material.

## 9. Design (settled 2026-08-19, after three codebase probes)

Design authority for this tempdoc; where it refines §3/§6, §9 wins. All cites
verified by the probe agents against `main`.

### 9.1 The window — assembled from existing primitives, zero new overlay machinery

The settings window is a chrome-level element declared once in Shell's template with
`slot="center"` on `OverlayHost` — the exact command-palette pattern
(`Shell.ts:2351`; OverlayHost is a pure placement authority, `OverlayHost.ts:8-11`).
Its dialog behavior comes from `ModalController` in the canonical `ConfirmDialog`
idiom (`ConfirmDialog.ts:175-197`): a boolean reactive `open` property is the single
source of truth; the native `<dialog>` supplies focus-trap/inert/top-layer;
`ModalityController` supplies scroll-lock + focus-restore; `::backdrop` styled
directly, no hand-picked z-index. ESC and backdrop-click close per the same idiom.

### 9.2 Placement — first realization of the declared-but-unused MODAL tier

`Placement.MODAL` exists in the FE union (`plugin-types.ts:225-234`) and the Java
enum (`Surface.java:37-38`) with **zero producers and zero consumers** (grepped).
`core.settings-surface` flips RAIL→MODAL **in both catalogs in the same commit**
(the `check-surface-composition` LEG-2 parity gate fails a one-sided flip). The
navigation pipeline is untouched except for one new branch: `NavigationHandler`
(`navigationHandler.ts:94-135`) treats a MODAL-placement target as "open the
overlay" instead of `setActiveSurface` — the stage keeps its current surface;
closing restores the prior address. Because rail clicks, palette `navigateAction`s,
and URL boot all funnel through this one handler (`Shell.ts:1783-1792`,
`actions/index.ts:784-798`), every existing entry point acquires the new behavior
with no per-entry-point code.

The rail keeps a **fixed bottom Settings affordance** exactly like Help
(`renderHelpButton`, `Shell.ts:2641-2655` — 578 §5.6's demotion pattern).
Consequences by construction: `BOTTOM_RAIL_IDS` (`Shell.ts:317`) is deleted; the
boot-default guard that references it simplifies; Settings drops out of
`railSurfacesForCustomization()` (which filters `placement === 'RAIL'`,
`SettingsSurface.ts:2167-2171`), ending the "Settings can hide itself" oddity.

**Gate blind spot owned by this work:** `check-ui-step-coverage.mjs` regex-matches
only `placement: 'RAIL'` ids (`:65`), so the placement flip silently un-enforces the
`settings`/`security` coverage rows. Extending the gate (or its register) to cover
MODAL-placement surfaces is in-scope for the same PR as the flip — not a follow-up.

### 9.3 The section register — one declaration, four projections

The design's center of gravity is a **declared settings register**: an ordered list
of `{group, category, entry}` where an entry is either a *native section* (a
reference to one of the ~17 existing render methods) or a *member surface* (a
catalog surface id). From this one register project: (1) the nav (groups, category
items, sub-anchors), (2) the category pages, (3) deep-link targets, (4) — later —
search. This conforms to ADR-0031/0033 (affordances project from declarations) and
the projection-vs-fork discipline: nav and content can never drift because neither
is hand-maintained.

Two probe findings constrain the shape:

- **Native categories are a render-time filter, not a component split.** All
  sections are inline TemplateResults on one class with a *centralized* lifecycle —
  one `connectedCallback` doing 9 loads + 8 subscriptions + 2 lazy imports with
  matching teardown (`SettingsSurface.ts:609-782`), and real cross-section state
  sharing (`this.ui`, `userConfig`, `viewerAudience`). The window therefore keeps
  **one** content component owning all native state; the active category selects
  which sections render. Per-category custom elements would slice that lifecycle
  apart for zero user-visible gain — explicitly rejected.
- **Member categories reuse the existing composition machinery.** Members are
  Java-catalog-declared (`CoreSurfaceCatalog.java:592-594` `withMembers`) and
  mounted today by `SurfaceTabs.renderPanel` via `mountSurface` + lazy registry
  (`SurfaceTabs.ts:202-233`). The window's member-category pages reuse that mount
  path with a vertical nav instead of horizontal tabs. Absorption of a surface =
  add to `withMembers` + flip its placement to DEEPLINK (the composition gate
  *requires* members not be RAIL, `check-surface-composition.mjs:261-274`).

### 9.4 Deep-linking — existing grammar only

The URL grammar is `#justsearch://surface/<id>[?k=v]` with no sub-paths
(`parser.ts:10-22`); the design adds none. Absorbed surfaces are deep-linked by
their **own ids** via the existing member→host alias redirect + member-select
intent (`catalogResolver.ts:97-113`, `memberTabIntent.ts:34-44`,
`SettingsSurface.ts:623-627`): a link to `core.security-surface` opens the settings
window at the Security category — which keeps the two readiness-notice remedies
(`readinessNotice.ts:333-345`) and the Settings pointer buttons working with zero
churn. Native categories get URL round-trip by giving `core.settings-surface` the
`stateSchema` it currently lacks (`CoreSurfaceCatalog.java:576-589` — 7-arg
overload, no schema) with one binding (category), the same mechanism
`core.ask-surface` uses (`CoreSurfaceCatalog.java:568-575`).

### 9.5 Nav + scroll-spy — reuse the house derivation math, not IntersectionObserver

`<jf-settings-nav>` is the one genuinely new primitive: vertical grouped list,
group headers + dividers, accordion (active category shows sub-anchors), roving
tabindex, danger styling for the isolated Data group, version footer. Its
scroll-spy does **not** use IntersectionObserver: the codebase deliberately retired
its only IO in favor of derived-focus math on scroll — and the derivation functions
are already exported (`deriveFocus()`, `anchorFractions()`,
`primitives/navigation.ts:24-80`, tempdoc 565 §21). The spy is a thin consumer of
those functions over the category page's section headings; all sections live in one
shadow tree under a single scroll container (`SettingsSurface.ts:303-311,
2188-2199`), so no boundary issues. Two-way: scroll drives the highlighted anchor;
clicking an anchor scrolls (reduced-motion honored, as `jumpTo` already does).

A11y floor inherited from 853: sibling buttons never nested-interactive (F-08),
token roles valid across all 4 palettes (F-07; `check-contrast-matrix` now resolves
all four), `tabindex="0"` on scrollable regions (F-05), 24×24 hit areas,
`check-controls-a11y` scans the new markup repo-wide automatically. Closure per the
presentation-authority rule: independent measured audit (axe + contrast oracle).

### 9.6 Orphans (deleted/tombstoned in this tempdoc's own work, not a later sweep)

1. **`views/SettingsLitView.ts`** — dead by reference count (only its own test, a
   barrel re-export `shell-v0/index.ts:237-241`, and the generated vocabulary list
   reference it; nothing mounts `jf-settings-view`). Delete + regen vocabulary.
2. **Settings' use of `<jf-surface-tabs>`** — replaced by the window's nav+register.
   `SurfaceTabs` itself stays only if another consumer exists; if Settings was its
   sole consumer, it is orphaned too — the plan phase must grep and decide
   explicitly, not leave it as residue.
3. **`BOTTOM_RAIL_IDS`** (`Shell.ts:317` + its two reference sites) — replaced by
   the fixed affordance.
4. **The Settings "Security pointer" block** (`SettingsSurface.ts:2253-2262`) —
   replaced by the real Security category.
5. **Token Editor's rail placement** (`TokenEditorPlugin.ts:468`, one field) —
   RAIL→DEEPLINK + a launch link under Appearance. ADR-0035 is silent on placement;
   this is purely a `plugin-types.ts` concern (verified).
6. **ui-shot steps `settings`/`settings-light`/`security`/`security-light`** —
   re-pointed, and `_view_setup` (`ui_check.py:575-631`) gains a modal strategy
   (rail-affordance click + dialog-open wait) — currently it only knows
   rail-click/hash-route.

`check-declared-surfaces` constraint carried forward: the `core.settings.interface`
region must keep mounting the `<jf-declared-surface>` engine inside the new window
(`governance/declared-surfaces.v1.json:11`).

## 10. Reach — principles this design instantiates (recorded, not built)

- **"The placement taxonomy already contains the overlay tier — realize it, don't
  fork it."** MODAL (and DRAWER/HUD/STATUS) exist in the enum while today's chrome
  overlays are hand-wired one-offs in Shell's template (command palette, drawers,
  panels — `Shell.ts:2325-2400`). Settings-as-MODAL is the first catalog-declared
  overlay surface. *Candidate scope:* the right-drawer family (advisory inbox,
  agent activity, sources pane) could become DRAWER-placement surfaces routed by the
  same NavigationHandler branch. *Existing violation:* those drawers are currently
  invisible to the router/palette/URL entirely. *Earns its keep when:* a second
  overlay surface adopts the branch without new chrome code. *Retire when:* if no
  second consumer arrives and the MODAL branch accretes settings-specific
  conditionals, collapse it back into a Settings special case and say so.
- **"Settings entries are a register with projections, not a page."** Instance of
  ADR-0031/0033 + the projection-vs-fork discipline. Second consumer is already
  chartered (Phase-4 search queries the same register); a third candidate is
  ui-step coverage declaring per-category steps from it. *Earns its keep when:*
  search ships against the register unchanged. *Retire when:* if the register ends
  up with exactly one projection (the nav), fold it back into the nav component.
- **Gate observation (belongs to this work):** placement-conditional gates
  (`check-ui-step-coverage`'s RAIL-only regex) silently release their grip when a
  surface changes placement — a structural pattern worth checking on any future
  placement migration, fixed here for the MODAL case.

## 11. De-risk findings (2026-08-19) — all §9 mechanisms verified against source

Risk register R1–R7 (derisk plan) resolved; two real traps found and disarmed. The
MODAL-branch contract below is binding for implementation.

### 11.1 The MODAL navigation contract (R1 — was highest-risk; now precise)

`NavigationHandler.handle` is a placement-blind straight-line function
(`navigationHandler.ts:95-133`); the branch is new code with an **injected placement
lookup** (`ShellAddressNavigation` carries no placement, `router/types.ts:36-39` —
extend `NavigationHandlerConfig` with `getPlacement`, mirroring the existing
`isKnownSurface` injection pattern). For a MODAL target:

- **SKIP `setActiveSurface`** — mandatory, not stylistic: `Shell.render()`'s
  catalog-wide fallback (`Shell.ts:2205-2207`) is not placement-filtered and would
  mount the settings surface standalone into the Stage if `activeId` changed.
- **SKIP `activateProjection`** — **trap #1**: it is *not* a no-op for a schema-less
  target; it unconditionally tears down the *current* surface's URL projection before
  the schema check (`URLProjector.ts:68-76`), so calling it would silently kill the
  underlying stage surface's live URL sync. The projector's single-slot design assumes
  one URL-owning surface; MODAL surfaces stay outside that model permanently.
- **RUN `pushAddress`** — the settings URL is bookmarkable and Back is meaningful
  (`URLProjector.ts:151-158`, decoupled from mounting).
- **Gate `applyState`** for forward-safety (no-op today — settings has no schema).
- **Open** via a new handler callback that toggles the window's `open` state.
- **Close = `window.history.back()`** — the popstate listener re-parses and
  re-dispatches the prior address through the normal branch with `pushHistory:false`
  (`URLSource.ts:73-79`), restoring URL + projection in one shot, symmetric with real
  browser Back. No hand-rolled prior-address bookkeeping.
- **Boot with a settings URL works by ordering luck that is actually load-bearing
  design**: `refreshSurfaces()` sets the default `activeId` synchronously in
  `connectedCallback` (`Shell.ts:1321`) *before* the async schema-fetch boot dispatch
  (`Shell.ts:1589+`) fires the URL address — so the default surface renders under the
  modal with zero extra code.
- `resolution.ts` / `recoveryPolicy.ts` / `catalogResolver.ts` need **no changes**
  (fully placement-agnostic; verified in full).
- **Amendment (P0 implementation finding, 2026-08-19): dismiss ≠ close.** A *real*
  browser Back while the window is open flows popstate → normal branch →
  `setActiveSurface(prior)` with the dialog still open, and a later ESC would pop
  history a second time. Contract addition: the window exposes `dismiss()` (close
  WITHOUT touching history), and Shell's `setActiveSurface` callback dismisses an
  open window on any realized stage navigation; `requestClose()` (X/ESC/backdrop)
  remains the only path that calls `history.back()`.
- **P1 look-item (P0 finding):** the §11.2 persistent mount moves SettingsSurface's
  full `connectedCallback` cost (9 loads + 8 subs + chunk fetch) to shell boot —
  consider mount-on-idle-after-catalog-resolve in P1 without giving up the
  connected-before-first-open property member intents rely on.

### 11.2 Member intents reach a closed window (R2)

`onRedirect` fires **before** `navigationHandler.handle` (`intentRouter.ts:121-132`)
— load-bearing ordering, preserved as-is. `memberTabIntent` is a pure in-memory
pub/sub; "mounted" means only "connectedCallback ran and subscribed". **Trap #2
disarmed by architecture choice**: mount the settings content element *persistently
inside the `<dialog>`* (connected-but-closed elements still receive live callbacks
per DOM connectedness semantics), so member intents always hit the live-notify path
and the pending-drain race never exists. Corollary: Stage will never mount
`jf-settings-surface` again (placement filter + skipped `setActiveSurface`), so the
persistent mount site in Shell's template is *required*, not optional.

### 11.3 Chrome hosting is fully precedented (R3)

`mountSurface` is container-agnostic (`SurfaceCatalogClient.ts:198-205`), and
**`Peek` is the exact precedent**: a chrome-level `role="dialog"` element outside the
Stage, receiving `api-base=${this.apiBase} .host_=${this.hostApi_}` from Shell's
template (`Shell.ts:2403-2406`) and mounting arbitrary catalog surfaces
(`Peek.ts:145`). Member categories copy `SurfaceTabs.renderPanel`'s
mount-on-activation / no-cross-tab-cache pattern (`SurfaceTabs.ts:202-233`) — not
Stage's LRU retention. SettingsSurface needs nothing loaded before first paint (all
`connectedCallback` loads are fire-and-forget with default-state renders,
`SettingsSurface.ts:609-741,813-827`). `landmarks.ts:25-27` already has
`case 'MODAL': return null` and `check-a11y-closure` is pre-satisfied — zero a11y-gate
changes needed.

### 11.4 Phase 0 ships without stateSchema (R4 — confirmed)

`getSurfaceStateSchema` has exactly two production call sites, both early-return
gracefully on absence (`navigationHandler.ts:150-152`, `URLProjector.ts:68-76`);
nothing else is coupled to schema presence. Member deep-linking is schema-independent.
Only loss: URL round-trip for *native* categories — deferred. When wanted later:
backend binding + a `settings.category` store registered in `registerCoreStores()`
(`bootstrap.ts:75-83`, `askChatState` is the model).

### 11.5 Environment (R5–R7)

- **SurfaceTabs is NOT orphaned** — also consumed by Brain, Health, Library, System
  surfaces. Only Settings' *use* retires; §9.6 item 2 resolved.
- **Contention**: `Shell.ts` is touched by 3+ in-flight branches (incl. the active
  `822-t3code-window` chat work); `tokens.css` by 2. Consequence: dedicated worktree,
  surgical Shell.ts diff, merge each phase promptly.
- **Center-slot coexistence**: palette's `display:none`-when-closed convention +
  native dialog top-layer → no interaction issue.
- Nav labels: `present({kind:'surface'})` covers member categories
  (`SettingsSurface.ts:2183`); native categories need a small i18n-key convention
  (no `EntityRef` kind exists for them) — Phase-1 detail.
- Boot-default logic degrades safely when `BOTTOM_RAIL_IDS` is deleted (both use
  sites operate on the RAIL-filtered list that will exclude settings anyway,
  `Shell.ts:2080-2097,2658-2659`); the new fixed settings button follows
  `renderHelpButton()` (`Shell.ts:2641-2655`).

### 11.6 Confidence

**8/10** for Phases 0–2 (every mechanism source-verified, every piece precedented,
both traps have named remedies). Residuals: visual/interaction polish of the nav
primitive is inherently iterative; `history.back()` close behavior should get one
live sanity check in the Tauri webview during Phase 0; Shell.ts merge contention is
process risk, not design risk. Phase 3 (Brain absorption, if chosen): 8 — same
mechanism as Security, bigger surface. Phase 4 (search): unplanned in detail, by
design.

## 12. Implementation log (2026-08-19, worktree-855-settings-window)

- **P0 (commit 85a838ca)** — MODAL branch + window frame. Contract §11.1 held with
  one amendment discovered in implementation (dismiss ≠ close) and two more found by
  the independent refute-first review and fixed with regression tests: push-dedupe
  when the address is already current (a bookmarked settings URL re-opened on ESC),
  and pushed-aware close (history.back only when the open actually pushed; otherwise
  forward-navigate to the stage surface — which also restores the boot-case URL
  projection). Review also caught: stale Java catalog test, missing vocabulary
  regen, rail hardening (settings excluded unconditionally), i18n of dialog strings.
  Live-verified: rail/palette/URL open, boot-under-modal, one-ESC close from boot
  entry, browser-Back dismissal, member deep-link (Skins tab), single rail button,
  ui-shot settings/settings-light with 0 new axe.
- **P1 (commit 805c134f)** — register + `<jf-settings-nav>` + category pages.
  Register verified 1:1 against the old 17-section body (review: no drops, no
  parallel lists); scroll-spy on `deriveFocus()`; audience gates byte-identical to
  the old self-gates; select-name axe defect fixed at the renderer (baseline entries
  removed); SettingsLitView deleted + vocab regen. Review found zero merge-blockers;
  its one latent-trap note (member-element cache not reset on native-branch renders,
  contrary to the SurfaceTabs precedent) was fixed and live-verified (fresh instance
  on member→native→member cycle). Deviation accepted: no per-category title strip —
  single-section categories read their section h3 as the de facto title; member
  surfaces bring their own h2.
- **Exposed pre-existing defects** (observations inbox, not this tempdoc's scope):
  light-palette AA contrast failures in chat view/status deck/walkthrough card newly
  visible behind the modal (baselined for the settings-light step with a note), and
  the known controls-a11y UnifiedChatView finding.
- **Freshness trap worth naming for future live passes**: the Head serves i18n
  catalogs from `modules/ui/build/install/ui/lib/*.jar` — `:modules:ui:assemble`
  does NOT refresh installDist, so new .properties keys need
  `:modules:ui:installDist` + stack restart (plus a hard browser reload past the
  Vite module cache) before they appear. Cost one diagnostic loop in P1's live pass.

**Closure obligation (canonical docs):** before this tempdoc closes, document the
realized `Placement.MODAL` routing tier (navigate → overlay-open, skip
setActiveSurface/activateProjection, pushed-aware close) and the settings-window
composition in the canonical routing/system-overview docs — deferred mid-flight
because P2/P3 are still moving placements; the presentation-kernel doc needs no
change (the window is a registered `modals.v1.json` adopter, that doc's own
mechanism).

- **P2 (commit b14ebe23)** — Security & Privacy absorbed as a member category
  (Java `withMembers` + RAIL→DEEPLINK, FE parity, register swap, pointer renderer +
  orphaned i18n keys swept). Live-verified: `core.security-surface` deep-link boots
  into the modal at the Security category with the full encryption UI; rail no longer
  shows Security; ui-shot security/security-light re-pointed to the modal strategy
  (light baselined for the same pre-existing exposed chrome contrast as settings).
  Combined review verdict: clean; noted the 629-era delete-confirm pointer now
  switches category in-window via the member redirect instead of dismissing — judged
  an improvement, covered by the live member-tab test.
- **P3a (this commit)** — Token Editor off the rail (placement RAIL→DEEPLINK,
  one-field as designed) + a registered `token-editor` sub-anchor under Appearance
  hosting a launch LINK (ADR-0035: link, never embedded plugin UI); dead
  `hiddenInSimple` entry swept. Live-verified: rail end-state Library · Brain ·
  Chat · Settings; real click on the link dismisses the window and stage-mounts the
  editor; Back reopens the modal (meaningful). Review found one merge-blocker fixed
  in the same commit: **dismiss-vs-close focus rule** — user-initiated close (ESC/X/
  backdrop) restores focus to the invoker per WAI-ARIA; navigation-initiated
  `dismiss()` skips restore (`ModalityController.exit({skipFocusRestore})`,
  additive), because a realized stage navigation owns focus's destination. Also
  fixed: nav `.active` now uses `--surface-active` (was sharing `--surface-hover`
  with `:hover` — the double-highlight defect), stale plugin comment, and direct
  test coverage for the launch link.

- **P4 (this commit)** — settings search: the register's chartered second projection.
  `searchRegister()` lives in the register (shared `categoryLabel()` extracted so nav
  and search resolve labels through one function — no second index, reviewed clean);
  the nav input swaps grouped view for a flat result list; activation composes the
  existing selectCategory/jumpToAnchor paths. Review found one merge-blocker fixed
  in-commit: result activation dropped keyboard focus to body (the same focus class
  P3a fixed for dismissal) — activation now focuses the restored active category
  row; plus the two-stage ESC (non-empty query clears; empty query bubbles to the
  dialog's native cancel and closes — house CommandPalette convention) and roving
  tabindex tracking in results. Live-verified: filter → activate → land-on-anchor
  with correct accordion/spy state and focus on the active row; empty-input ESC
  closes the window; i18n resolved; ui-shot settings/settings-light 0 new axe.
  With P4, the window exceeds the Discord reference on search (theirs shipped only
  in the 2025 redesign; ours queries the declared register).

- **Closure (this commit)** — independent measured UX audit (auditor ≠ committer,
  live, axe + contrast + geometry, per the presentation-authority rule) initially
  returned **FAIL** with a blocker the entire unit-test pyramid could not see: the
  nav's flex chain was broken at the shadow `<nav>`, so the category list never
  scrolled — at 1280×720, Plugins/Desktop/Developer and the danger **Data** group
  were mouse-unreachable. Fixed (one rule), live-verified scrollable with Data
  reachable. Also from the audit: fixtures registry catalog now declares the
  settings members so the member redirect works under `--fixtures` and
  `jseval ui-a11y-gate` completes (final verdict: **clean, no NEW violations**,
  exit 0); baseline notes' causal story corrected (the light-palette chrome
  contrast defects reproduce on `home-light` with no modal — they are measured
  because 855 registered these steps, not exposed by the modal); missing `security`
  dark baseline entry added; `skipFocusRestore` claims softened to what the
  mechanism actually does (suppresses the controller's own restore; the platform
  dialog may still restore — benign, measured). Audit's measured PASSES: §2
  geometry to the pixel (728px column centered, 241px nav, all hit areas ≥24px,
  danger isolation), full keyboard journey incl. two-stage ESC, 68/68 contrast
  pairings across 4 palettes, 0 modal-owned axe issues in any palette. Canonical
  docs updated (frontend-kernel primitives: MODAL tier; ui-ux-design: settings
  window + register; secondary-views: stale React-era SettingsView prose swept).
  Full backend suite green; full ui-web suite green (5578).
  **Accepted spec deltas:** nav footer is version-only, no click-to-copy, omitted
  without a version source (§4 delta, deliberate); no per-category title strip
  (§12 P1 deviation). **Residuals for a human/Tauri pass:** live HC-palette
  screenshots of the window (token gate covers all 4 palettes; no HC ui-shot step
  exists), Tauri-webview behavior (esp. window height < ~976px — nav now scrolls,
  re-check), screen-reader walkthrough, reduced-motion scroll feel. Harness gap
  (clipped-interactive-elements detection) logged to observations.

## 13. Remediation round — owner visual feedback (2026-08-19, with Discord reference screenshots)

Owner findings against the shipped window, each with a Discord-reference anchor:

1. **Sub-anchors must not have background highlighting.** Discord's sub-items
   (Appearance → Theme/App Icon/…) are plain text; the active one is brighter text
   only. Our anchor rows got the pill treatment — remove it (categories keep the
   pill; anchors go text-brightness + aria-current only).
2. **Too many cards.** Most of our sections wrap plain settings in big bordered
   card boxes. Discord's default is FLAT rows on the page background: bold
   sub-heading + muted description, control below/right; cards are reserved for
   genuinely composite content (e.g. the Color Themes swatch grid). De-card the
   sections; keep cards only where content is composite.
3. **Themes must be color swatches, not text walls.** Discord's Color Themes is a
   grid of small rounded-square swatches painted with the actual theme colors;
   active gets a ring + check. Our theme cards carry long descriptions that squash
   into dense unreadable columns. Render swatches painted from each theme's own
   palette; name as a small label; long description demoted to tooltip/aria.
4. **Fewer option-card grids; use the right control shapes.** Examples (owner):
   Density → a discrete slider (3 stops for now: compact/comfortable/spacious,
   Discord's tick-marked slider idiom); Contrast → on/off switch; Motion → on/off
   switch ("Reduce motion"); the dark/light/system variant picker → the small
   square swatch trio with a check badge (Discord's "Default Themes" row idiom).
   General rule: binary → switch; small ordinal scale → discrete slider or radio;
   enum with visual identity → swatches; card grids only when options genuinely
   need explanation panels.

## 14. Theorization — the full settings-opportunity space (2026-08-19, pre-design)

Framing: §13's four findings are instances of one gap — the window's *shell* (P0-P4)
is Discord-grade, but the *content* still renders in the old page's vocabulary
(everything a card, every enum an option-card grid). Theorized directions, not design:

**T1 — One flat setting-row primitive.** Default rendering = bold label + muted
description + control (inline right for compact controls, below for radio/slider),
thin divider rhythm, on the page background. Cards demoted to composite-content
exception (swatch grids, plugin lists, witness ledger). Self-propagating: future
sections inherit the look. Open scope call: all categories in one pass vs
high-traffic first.

**T2 — A control-shape grammar** (the generalization of §13.4): binary → switch;
small ordinal → radio group or discrete slider (owner leans slider for density;
Discord-literal would be radios — either is a11y-sound with labeled stops);
enum-with-visual-identity → swatch row/grid (theme variant trio, theme palettes);
continuous → slider (none exist yet; density's 3-stop slider is forward-compatible
if the spacing scale ever goes continuous); action → button row; cross-link →
chevron "Related Settings" row (Discord idiom; we already have two organic
instances: delete-confirm → Security, Appearance → Token Editor).

**T3 — Theme swatches from the themes' own tokens.** Each theme declares a palette;
paint tiles from it (surface ground + accent mark — two-tone, since our themes are
semantic palettes, not decorative gradients). Name stays as a small label (our theme
names carry meaning); prose descriptions leave the grid (tooltip/aria at most).
A11y guard: never color-only differentiation — the name label is load-bearing.

**T4 — Deduplicate contrast.** Interface's "High contrast" toggle and
Accessibility's Contrast pair look like two homes for one concern (verify: ui
setting vs adaptation profile). One canonical home + a Related-Settings cross-link
from the other — the Discord Appearance→Accessibility link is the exact pattern.

**T5 — Register grows a row vocabulary (recognized, not built).** Today the
register declares WHERE settings live; T1/T2 push toward declaring WHAT SHAPE each
row takes (`{kind: toggle|slider|enum|swatch|action|link, settingPath, labelKey,
…}`) with a generic row renderer — the ADR-0031/0033 declaration philosophy applied
one level deeper, and the third projection candidate: **row-level search** (today's
search stops at section granularity). Tension (AHA): many sections are genuinely
bespoke (plugins, grants, witness) and the generic-renderer path has already bitten
once (the enter-action-picker select-name defect came from `jf-form`); only unify
rows that share a reason to change. Earns keep when row-level search ships against
it; retire if rows stay bespoke.

**T6 — Category-space opportunities (future, cheap to reserve):** Keybinds (a
keybinding registry already exists — `registerKeybindingEntry`; a Keyboard category
page could surface/edit it, Discord's Keybinds analog); Language (catalogs are
en-only today; the i18n plumbing exists); Notifications (advisory-inbox
preferences); D1 Brain → *AI Runtime* member category (pending owner; mechanism
proven twice). Reserving = a register entry each, nothing more.

**T7 — Interaction niceties, ranked cheap→speculative:** version footer
click-to-copy (finish the §4 spec delta); per-category reset (today only global
`core.reset-settings`); instant-apply + undo-toast for destructive-ish toggles (the
pending-effect queue exists); HC ui-shot steps (closure-audit residual — would have
caught palette issues live); small-width behavior rule for the modal (unresearched
in Discord; likely nav-collapses-to-list → page-push — design only when a real
constraint arrives).

**Non-goals reaffirmed:** no unsaved-changes bar until a form-like page exists; no
settings-window profile header (no accounts); no per-language settings levers
(ADR-0043 invariant untouched — these are UI prefs, not search analysis).

## 15. Design — content remediation (settled 2026-08-19; refines §13/§14 with probe evidence)

### 15.1 The flat row idiom (T1, settled)

One `settings-row` markup idiom inside SettingsSurface (CSS + a render helper —
NOT a new custom element; §9.3's one-component rule stands): bold label, muted
one-line description, control inline-right (switch/dropdown/small swatch trio) or
full-width below (radio/slider/swatch grid), hairline divider rhythm, page
background. `.section` card chrome (one rule-set, `SettingsSurface.ts:417-422`)
is restyled in place to the flat treatment — **all categories in one pass** (a
partial pass would ship two visual dialects). Cards survive only as the
composite-content exception (theme swatch grid, plugin list, delivered
contributions, workspace profiles), and lighter than today.
**Scope extension the probe forced:** `SecuritySurface` re-declares the identical
`.section` card CSS (`SecuritySurface.ts:579`) and now renders INSIDE the window —
it de-cards in the same pass or the window ships mixed dialects. Brain's copy
stays untouched (not window-hosted unless D1(b)).

### 15.2 Control primitives (T2, settled by inventory)

- **Switch:** the settings page hand-rolls `.switch` divs (4+ sites,
  `SettingsSurface.ts:1156-1190,1394-1443` — **missing `aria-checked`**, literal
  `white` knob) while a correct implementation exists in
  `ToggleSwitchRenderer.ts:82-100`. Consolidate: one shared switch (extract to a
  small component or reuse the renderer's element standalone — worker verifies
  which is cleaner), used by Contrast, Motion ("Reduce motion"), and the existing
  toggle rows. The a11y fix rides along by construction.
- **Discrete slider (Density):** no themed slider exists anywhere (the only
  `type="range"` is the chat replay scrubber, unstyled). Owner explicitly chose
  slider over Discord-literal radios: build a minimal themed discrete slider
  (native range + tick labels + `aria-valuetext`, tokens only) as the grammar's
  ordinal shape — deliberately small; radios remain the documented fallback if it
  fights the platform.
- **Segmented enum pickers** (Simple/Detailed, System/Dark/Light): currently
  bare `.option-btn` buttons with NO radiogroup semantics, while two proper
  `role="radiogroup"` precedents exist (`OptionButtonGroupRenderer.ts:88-107`,
  `AutonomyDial.ts:185-196`). Align to the radiogroup pattern; the theme-variant
  trio additionally becomes the small square swatch row with check badge
  (Discord "Default Themes" idiom).
- **Cross-link row:** chevron "Related settings" row shape (two organic instances
  exist already: delete-confirm→Security, Appearance→Token Editor) — formalized as
  part of the row grammar, debuting for the contrast dedupe below.

### 15.3 Theme swatches (T3, settled) — declare, don't parse

Built-ins expose NO color data (manifest entries are `{id, displayName,
description, cssPath}` — `themeManifest.ts:26-32`); deriving colors would mean
fetch-and-regex of theme CSS at runtime. Rejected. Instead the **theme manifest
gains an optional declared `swatch` (surface + accent)** per entry — presentation
metadata belongs in the artifact's own declaration (the same philosophy as
`labelKey`/`present()`): manifest.json entries get authored swatch values matching
their palettes; the validator learns the optional field; custom themes derive
swatches directly from their token tree (`tokens['surface-primary']` /
`tokens['accent-tint']`, readable without applying — `designTokenTree.ts:135-158`);
a theme without swatch data renders a neutral tile. Grid: two-tone tiles (surface
ground, accent mark), ring + check on active, name as small label (our names carry
meaning), description demoted to native `title` (the house idiom — 15 uses in
this file; no tooltip primitive exists and none is built).

### 15.4 Contrast: one authority (T4, upgraded from IA cleanup to defect fix)

Probe verdict: `UISettings.highContrast` (backend-persisted, via
`applyAppearance`) and `userConfig.accessibilityProfile.contrast` (FE-local, via
`applyAdaptationProfile`) **both toggle the same `high-contrast` root class**,
winner decided by boot order (`adaptationProfile.ts:42,76-78` acknowledges the
fight). This violates the presentation-kernel's own single-authority doctrine
(doc 27). Design: **one visible control** (Accessibility's new switch) and **one
DOM-class owner**; the Interface duplicate becomes a Related-settings cross-link.
The two backing stores must converge on one canonical field with the other
migrated/deprecated — the care point is persistence semantics (one store syncs
server-side, the other is local); the implementation round resolves the mechanics
and the review verifies no user's existing HC preference is silently lost.

### 15.5 Orphans (this work, not a later sweep)

Hand-authored `.switch` idiom + its CSS (replaced by the shared switch);
non-semantic `.option-btn` pickers (replaced by radiogroup/swatch-trio); the
`.section` card rule-sets in SettingsSurface + SecuritySurface (restyled);
theme-description text in the grid (demoted to `title`); one of the two contrast
set-site pathways (migrated).

## 16. Reach (design judgment)

- **"Control shapes are shared primitives, not per-surface hand-authoring."**
  Existing violation found in the wild: the settings page hand-rolls switches
  (with an a11y gap) while a correct switch ships in the declared-renderer stack,
  and hand-rolls segmented pickers while two radiogroup precedents exist. This
  remediation conforms to the existing pattern rather than inventing one. Earns
  keep: a third surface adopts the shared switch (Brain's toggles are candidates).
  Retire: if the shared switch grows per-surface conditionals, split it back.
- **"Presentation metadata is declared in the artifact's manifest"** (swatch =
  theme's `labelKey` analog). Candidate scope: plugin surfaces could declare
  swatches/icons the same way. Earns keep: a second consumer of declared swatches
  (e.g. a theme quick-picker outside settings). Retire: if the swatch field rots
  unauthored, drop it and render neutral tiles.
- **The contrast fix is an instance of doc 27's existing single-authority
  principle** — not a new principle; recorded as a violation-repair. The same
  probe pattern ("grep for two writers of one DOM class/token") is worth applying
  to `motion-reduced` and future profile axes at review time.

## 17. De-risk findings — §15 remediation (2026-08-19)

**R1 contrast (contracted).** Canonical store = backend `UISettings.highContrast`
(only durable one; `accessibilityProfile` is localStorage-only). The Accessibility
switch becomes the single control and **writes through** the existing
machine/save path (`patch({highContrast})` → `save-settings` narrow POST) so
persistence is not degraded; `accessibilityProfile.contrast`, if kept at all, is a
derived projection, never a second authority. One-time migration: an explicitly
set `accessibilityProfile.contrast` wins the reconciliation write (it is today's
de-facto boot winner by call order — now made deliberate), then stops projecting.
Removal surface is fully enumerated (incl. the SECOND toggle render path in the
declared presentation, `builtinPresentations.ts:58-63,85` — both go together);
must-not-delete list pinned (backend schema field, boot-restore path, tokens.css
HC blocks, the statechart's other edges). Implementation obligations from the
trace: a real end-to-end HC test (none exists today — click → class + backend
persist), a live `/api/settings/v2` narrow-PATCH check, and a live check that
per-profile contrast divergence (accessibilityProfile is per-profile; the backend
field is singular) isn't silently collapsed. Bonus finds: dead
`justsearch-high-contrast` localStorage key (vestigial, logged to observations);
zero existing e2e coverage of either HC path.

**R2/R6 controls (settled, better than designed).** Extract `jf-switch`
(plain props checked/label/disabled + change event) from ToggleSwitchRenderer's
CSS/a11y; the renderer composes it; SettingsSurface's FOUR hand-rolled `.switch`
sites consume it; register in `atom-facets` (`forkClasses: ["switch"]`) so the
ratchet forbids a fifth fork. Radiogroup: NEITHER precedent implements the
WAI-ARIA keyboard model (both click-only, gate-passing via native buttons) — the
arrow/Home/End model is added ONCE inside `OptionButtonGroupRenderer` (whose
markup already matches SettingsSurface's `.option-btn` vocabulary exactly), and
the 6+ hand-forked pickers consume `<jf-option-button-group>`; the
`.option-btn`/`.option-label`/`.selected` class contract that three test sites
pin is preserved by construction.

**R3/R4/R5 (trivial).** Manifest validator is accumulate-style/additive — optional
`swatch` field + sibling `manifest.schema.json`; authoring colors read directly
from each theme's CSS/token file. `input[type=range]` is unconditionally gate-clean
(native allowlist); slider styles on existing token roles. De-carding blast radius:
one rule-set each in SettingsSurface + SecuritySurface; no proportion-baseline or
test pins on card visuals.

**Confidence: 8/10.** Everything is contracted or precedented; residuals are the
two live checks (PATCH merge, per-profile HC) and the inherent visual-iteration
loop. Model plan: contrast migration → opus (store/statechart/persistence
judgment); switch extraction + radiogroup keyboard model → sonnet high; flat-row
de-carding + slider + swatches → sonnet high with orchestrator ui-shot review
loop; refute-first reviews per round (opus for the contrast chunk).

## 18. Remediation implementation log (2026-08-19)

- **R1 (this commit)** — control primitives. `<jf-switch>` extracted from
  ToggleSwitchRenderer (which now composes it — one visual), 4 hand-rolled
  `.switch` sites converted, atom registered with `forkClasses:["switch"]`
  (ratchet now forbids a fifth fork), and the missing `aria-checked` fixed by
  construction. WAI-ARIA radiogroup keyboard model (arrows/Home/End, wrapping,
  roving tabindex, focus-follows-selection) added ONCE in
  OptionButtonGroupRenderer + a plain-props path; 6 forked `.option-btn` pickers
  converted. Review: PASS, zero blockers (line-by-line conversion faithfulness,
  session-only gating verified at both layers); notes: option-group not
  ratchet-registered (documented scope), icon field not aria-hidden (pre-existing
  pattern). Live: structural verification on the worktree-served FE (borrowed
  read-only backend — the shared stack was warn-taken-over mid-round by another
  session, so mutating interaction checks fold into R2's live pass on an owned
  stack): 2 switches with the full role/aria-checked/tabindex triad, 6 groups
  with radiogroup role and correct roving tabindex. Unit: 437 files / 5597 green.

- **R2 (this commit)** — contrast single authority, per the §17 contract. Canonical =
  backend `UISettings.highContrast`; the Accessibility `jf-switch` is the ONE control,
  writing through the existing machine/save path; `accessibilityProfile.contrast`
  RETIRED (not derived — zero remaining readers) with a one-time, fail-safe boot
  migration (clear-last ordering: any GET/POST failure retries next boot; verified
  against read-only-409, sanitizer-strip, and both failure orderings by an
  adversarial review that could not construct a data-loss case). Both duplicate
  renders removed (hand + declared presentation). Review found 2 merge-blockers,
  fixed in-commit: the Related-settings cross-link rendered only on the fallback
  path (moved to the branch join in renderInterfaceRegion; test now pins the
  DEFAULT declared path — the wrong-gate pattern caught and corrected), and an
  ambient-purity gate red (re-authored ::placeholder removed; the JfElement ambient
  authority already covers it). Ride-alongs: the now-sole Shell `jf-set-appearance`
  listener seam pinned by a real mounted-shell test; `jf-switch` gains
  aria-disabled; four now-false canonical-doc claims corrected (HC persistence
  moved backend-side; phantom Cmd+Shift+H removed). Live: narrow-PATCH verified
  against the real backend (one field flipped, all others intact + durable; POST
  response-shape quirk logged); ui-shot set 0 NEW axe. Residual: the interactive
  browser smoke (HC toggle click round-trip, migration seed, cross-link click)
  awaits a visible browser window — pinned by unit tests meanwhile; folds into
  R3's live pass.

- **R3 (this commit)** — the visual round (§13's actual ask). Flat setting-row idiom
  across all categories (SettingsSurface + SecuritySurface — one dialect in the
  window); cards demoted to exactly the §15.1 composite whitelist; sub-anchors lose
  the pill (text brightness + aria-current only). Control shapes: Density → new
  `jf-discrete-slider` atom (3 labeled stops, aria-valuetext); Motion → "Reduce
  motion" switch; theme variant → swatch trio; theme grid → token-painted two-tone
  tiles (manifest gains a declared optional `swatch {surface, accent}` — authored
  for all 6 built-ins from their own palette files, verified byte-for-byte by
  review; custom themes derive from their token trees; prose demoted to title).
  Review found 2 merge-blockers, fixed in-commit: (1) the swatch trio was dead on
  the default declared path — the SAME class as R2's F1, one level deeper (the
  option-group schema branch had no swatch hook): fixed with a serializable
  `x-enum-swatches` extension consumed by BOTH paths (one swatch vocabulary) and a
  test that walks the real declared-render chain (red-before-fix verified); the
  gate gap enabling this class twice (mounts-the-engine vs content-parity) is
  logged to observations; (2) Security's flat rule clobbered the shared
  at-rest card and added a stray first divider — scoped to
  `.section:not(.card):not(:first-child)` with an exact-value cascade test (the
  worker caught and fixed its own vacuous-assertion trap). Live-verified: declared
  variant trio renders swatches on a production boot; Data-Protection card chrome
  restored; ui-shot settings/settings-light/security 0 NEW axe, no overflow. Full
  ui-web suite 440 files / 5641 green.

- **Final fix round (this commit)** — delta re-audit verdict was PASS-with-notes; its
  actionable items closed here: (S1) the 7th unconverted enum picker (Layout
  `.card` grid — class-only selection, no ARIA) converted to the shared option
  group, exposing and fixing a test-harness gap (layout catalog never initialized
  in unit tests, so layouts silently tested empty); (S2) cold deep-link boots no
  longer strand raw i18n keys — `resourceCatalog` gained `onCatalogUpdated` and
  the two settings label consumers re-render on catalog arrival (live-verified:
  cold boot resolves all labels, zero leaks); (M1) radiogroups carry accessible
  names in both render paths (`groupLabel` / `schema.title`); (M3) the variant
  trio's squashed visible descriptions demoted to title + composed aria-label,
  matching the theme grid; (N6) the no-pill anchor rule pinned by an exact-value
  test. Deviation accepted from the audit's wording: the "default open action"
  controls already carried real accessible names — not renamed. **§15.1 whitelist
  amendment (audit M4):** the declared Interface region is the documented FIFTH
  composite exception (it hosts the declaration engine's own body). Remaining
  audit notes (member-surface dialect, theme-grid keyboard asymmetry, nested
  plugin-row chrome, security nested-scroll, 22px switch height judgment) are
  logged to the observations inbox as follow-ups with owners' context.
  Final state: full ui-web suite 440 files / 5661 green; full backend suite
  green; ui-a11y-gate clean; all four §13 owner findings PASS measured
  acceptance; delta re-audit PASS-with-notes with every actionable note closed.
