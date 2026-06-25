---
title: "507 — Decomposing the Framework Boundary: Kernel Modules, Feature Domains, and Plugin Substrate as Three Distinct Layers"
---

# 507 — Decomposing the Framework Boundary: Kernel Modules, Feature Domains, and Plugin Substrate as Three Distinct Layers

**Date**: 2026-05-19 (rewrite; supersedes the 2026-05-17 draft of the same number)
**Status**: open
**Depends on**: 421 (framework kernel design draft)
**Supersedes-in-place**: the prior 507 draft that framed the problem as a single "Capability-Mediated Surface Architecture" with a single PluginHostApi as the universal framework boundary.

> This tempdoc is a *theorization* of the correct long-term structure. It
> intentionally disregards feasibility, migration cost, and back-compat. It
> is not a slice. Subsequent slices will pick targeted reshapings that
> conform to this structure; that conformance is the only purpose this
> document needs to serve.

---

## §1 Why this rewrite

The previous 507 draft proposed a "capability-mediated surface architecture"
whose central move was: every UI surface — core or plugin — receives the
same `PluginHostApi` object and accesses the framework exclusively through
it. Core surfaces would be refactored to consume that API instead of
importing framework internals; an expanded API would cover everything any
surface needed to do.

Three subsequent tempdocs (508, 511, 521) interacted with the design and
exposed structural strain:

- The `PluginHostApi` interface grew past 60 methods; 521 §2 had to
  decompose it into a dozen sub-interfaces just to keep it documentable
  and mockable.
- Surface-specific state — `PluginSearchState` (with `setQuery`,
  `pinSearch`, `recordSearchRun`), `PluginInspectorState`,
  `PluginLayoutState` — leaked into the universal capability surface
  because core surfaces "must use the framework boundary," and they need
  to drive their own state.
- Trust-tier attenuation accreted into an 800-line `HostApiImpl.ts`
  factory whose body is structurally "`if (isUntrusted) … else …`"
  branching at every method. 521 §2.3 tried to reframe this as
  "composition," but the composition still happens inside one factory
  for one God Object.
- 507's own status note as of 2026-05-19 declared Phases 2/4/5 unshipped.
  Investigation showed Phase 4 and Phase 5 were *largely shipped*
  (settingsSchema on PluginManifest; Rust `scan_plugins` /
  `read_plugin_source` / `get_plugin_dir`; `LayoutManifest`,
  `LayoutCatalog`, `FOCUS_LAYOUT`, Settings layout picker) and Phase 2
  was ~90% mechanical, while the *real* unfinished work — the
  structural symmetry claim that "a plugin can replicate any core
  surface" — has no enforcement, no verification, and no plan.

The strain is not random. The original 507 conflated three boundaries
that should be distinct, then asked one API surface to be all of them.
Once you separate them, the existing partial work mostly belongs to a
different boundary than the one 507 placed it in, the
sub-interface decomposition becomes unnecessary, and the trust-tier
factory disappears.

The correct rewrite is not "complete Phase 2." It is to redraw the
boundary lines.

---

## §2 The three boundaries that should be separate

### §2.1 KCS — Kernel Capability Surface

The framework's promise to *any* product code (first-party surface,
third-party plugin, ad-hoc test harness): *these are the capabilities
the running system offers, exposed as small typed modules with stable
contracts.*

What lives here:

- Operation execution and discovery (the catalog clients + invoke
  semantics, not specific operations).
- Resource observation (subscribe-by-id; SSE/long-poll abstraction;
  resume tokens; backpressure).
- Health and system status as observable.
- Intent dispatch / navigation (the verb-level routing API, not the
  list of intents).
- Notification, confirmation, clipboard.
- I18n catalog access.
- Theme tokens.
- Platform capability set + the actual platform actions gated by it
  (pickFile, revealInExplorer, openExternal).
- Settings access, scoped by owner.
- LLM/AI invocation as a generic shape-runner.
- Selection — *if* selection is genuinely cross-surface (it is, today,
  per `selectionState`).
- Inspector mount — *if* the inspector is treated as a kernel-owned
  zone rather than a feature.

What does **not** live here:

- Search-feature internals (the search query state, pinned-search list,
  search-filter spec). These belong to the search feature module.
- Inspector tab state, active tab, AI-text-for-current-selection.
  These belong to the inspector feature module.
- Layout-override mutations (`setSurfaceVisibility`, `setSurfaceOrder`,
  `clearAllLayoutOverrides`, `setActiveLayoutId`). These belong to a
  Layout feature module whose *kernel-exposed* read API is at most
  "observe active layout id."

The KCS is a graph of small typed modules, not a single object. There
is no `host`. There is `useOperations`, `useResources(id)`,
`useNotifications`, `usePlatform`, `useNavigation`, `useSettings(scope)`,
`useI18n`, `useTheme`, `useAI`, `useSelection`. Each is its own
typed entry point. Surfaces import what they actually use. The kernel
boundary is *the set of these entry points*, not a composed object.

This is not a stylistic preference; it is what makes the boundary
*small and durable*. The current Host API has 80 methods because it is
the only way to be on the boundary. With a module graph, each kernel
domain stays small, evolves independently, and is missing-by-default
from a surface that hasn't imported it.

### §2.2 FDM — Feature Domain Modules

Each major product feature (Search, Browse, Library, Health, Inspector,
Agent, Settings, Logs) is a self-contained module with:

- Its surface(s) — the Lit/React elements that mount in zones.
- Its state stores — the imperative substrate behind those surfaces.
- Its operations clients and resource subscriptions, threaded through
  KCS.
- Its public interface — what *other* features and the kernel may
  observe of it.

The crucial property: a feature's state stores are **its own internal
API**, not the kernel's. SearchSurface and `searchState` live in the
same module and import each other directly. That is not a violation
of the framework boundary; the framework boundary lies between *the
feature* and *the kernel*, not inside the feature.

When another feature genuinely needs something from a feature it doesn't
own, the right move is one of:

1. The feature exposes a typed observable read (`onSearchHistoryChange`)
   from its public surface — sibling features import that, *not* the
   raw store.
2. The cross-cutting concept gets promoted to a KCS module
   (Selection did; SearchHistory probably should; raw query-text
   should not).
3. The dependency is wrong and one of the two features should be
   restructured.

The current "core surface receives `host_: PluginHostApi`" pattern is
the inversion of this. SearchSurface is barred from importing its
own state store, which is then re-exposed through the framework-wide
Host API so SearchSurface can reach it via the same channel a third-
party plugin would use. The third-party plugin never needed to drive
SearchSurface's query state; that affordance exists only because
SearchSurface couldn't drive its own.

### §2.3 PS — Plugin Substrate

The sandboxing, trust verification, lifecycle, signing, and isolation
machinery that exists to host *untrusted* product code. Layered *on top
of* KCS; not the boundary itself.

What lives here:

- SES Compartment construction and lockdown
- Plugin source loading (URL/disk/IPC), source size limits, signature
  verification
- Manifest validation, contract version handshake, capability
  declarations
- Trust tier resolution (CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN)
- The *module resolver* the Compartment uses to satisfy `import` calls
  from inside the sandbox
- Contribution merging into the running registries (surfaces, commands,
  status-bar items, inspector tabs, context actions, recovery overlays,
  empty states, aggregate strategies, themes, layouts)
- Hot-reload watcher
- The `PluginRegistry.install` / `uninstall` lifecycle that *applies
  and unapplies* the manifest's contribution record atomically

What does **not** live here:

- The kernel modules themselves. They have no knowledge of trust tiers
  or plugin identity. They are tier-agnostic.
- The contribution registries themselves (CommandRegistry,
  StatusBarRegistry, etc.). Those are kernel primitives — *any* product
  code (core or plugin) contributes to them. The plugin substrate is
  *one consumer* of those registries' install/uninstall verbs, applying
  a manifest atomically; first-party UI is another consumer that calls
  the registries' verbs directly without ceremony.
- The "framework boundary." That is KCS. The plugin substrate's
  contribution is not a wider boundary; it is a sandboxed projection of
  the *same* boundary plus the lifecycle machinery to apply and revoke
  contributions transactionally.

---

## §3 The structural cure: kernel modules as a graph; plugin substrate as a resolver

### §3.1 What "the framework boundary" actually is

After the split: the framework boundary is the *set of import paths*
that resolve to kernel modules. There is no runtime object that *is*
the boundary. A surface inside the `features/search/` module that
writes `import { useOperations } from '@kernel/operations'` is on the
boundary; a surface that writes `import { OperationClient } from
'../../shell-v0/operations/OperationClient'` is below the boundary
and the import is wrong.

This makes the boundary statically verifiable. eslint
`no-restricted-imports` (already present, currently warn-level for the
FSD layers in `modules/ui-web/eslint.config.js`) enforces it as part
of the build. The boundary cannot rot the way it has under the Host
API: there is no factory that quietly accreted a new method; there are
discrete kernel modules that grow visibly, each with its own changelog.

### §3.2 Trust attenuation as resolver-time substitution

The plugin substrate's Compartment uses a *module resolver* — when
sandboxed code imports `@kernel/operations`, the resolver chooses the
implementation to expose. For an UNTRUSTED plugin, that implementation
is the attenuated one (SAFE-tier operations only, no privileged
metadata). For a TRUSTED plugin, it is the full implementation. For
CORE / first-party UI there is no Compartment and no resolver — the
import resolves normally via the bundler.

This eliminates the `if (isUntrusted)` branches inside the kernel.
Trust composition lives at the resolver. Each kernel module ships one
canonical implementation and (where attenuation matters) one or more
trust-projected implementations as sibling modules. The Compartment's
resolver picks the right one per plugin at install time and freezes
it. The kernel module is tier-agnostic; the Compartment is plugin-aware.

**Inv-1 confirmation (2026-05-19)**. SES `Compartment` supports
per-import module binding via its second constructor argument:
`new Compartment(endowments, modules, options)` (see
`modules/ui-web/src/shell-v0/plugin-api/PluginCompartment.ts:133`,
and the anticipatory comment at lines 134–136 noting that "V1.5.1
adds an `import-map`-style mediated module map"). The resolver
design is feasible against the substrate already in the codebase.

**Important nuance** — today's attenuation pattern differs from the
resolver design. Plugins today are evaluated as source strings (not
ES modules) and receive `host` via *endowments* (the first
constructor arg). `buildCapabilityBundle()` attenuates by substituting
the endowed globals — a scoped `localStorage` and a namespaced
`customElements` proxy — at `PluginCapabilityBundle.ts:71-76`. The
resolver-based trust design described above is therefore a *future
shape* that depends on plugins shifting from script-eval to
module-import semantics. The decomposed-kernel-module design is the
load-bearing prerequisite for that shift; once kernel modules exist
as importable entry points, the Compartment's `modules` arg becomes
the natural attenuation site and the endowment-substitution pattern
narrows to the things that genuinely are global (DOM, timers).

### §3.3 Contribution registries are kernel primitives

`CommandRegistry`, `StatusBarRegistry`, `InspectorTabRegistry`,
`ContextActionRegistry`, `EmptyStateRegistry`, `KeybindingRegistry`,
`TemplateCatalog`, `VirtualOperationCatalog`, `aggregateRegistry`,
`LayoutCatalog`, surface-catalog, resource-catalog,
recovery-overlay-catalog — these are all *contribution registries*. They
are kernel primitives. Anyone can call their `register` verbs.

**Inv-4 partial confirmation (2026-05-19)**. 10 of 13 registries are
tier-agnostic today — their `register/unregister` verbs treat
`trustTier`, `pluginId`, and `audience` as opaque entry data (stored
into provenance) and never branch on them. Two registries
exhibit *intrinsic* tier-aware policy and should be treated as
separate from the kernel-primitive set:

- **`TemplateCatalog`** (`commands/TemplateCatalog.ts:224-235`)
  rejects UNTRUSTED templates that bind restricted ambient sources
  (`selection`, `primarySelection`, `clipboard`) at registration.
- **`RecoveryOverlayClient.mergePluginRecoveryOverlays`**
  (`api/registry/RecoveryOverlayClient.ts:52-87`) rejects UNTRUSTED
  plugins overriding CORE conditions outside their own namespace.

These are policy registries, not data registries. The corrected
framing: contribution registries are kernel primitives **and** ship
with companion policy modules where security-critical filtering
applies. The policy module is the right home for tier-conditional
filtering (it sits between caller and registry); the registry itself
stays tier-agnostic. The two existing exceptions should be refactored
to extract their policy gates into companion modules; the registries
themselves keep their tier-agnostic shape.

First-party UI registers directly at boot.

The plugin substrate is the *transactional composer* over those
registries: a plugin's `PluginContribution` record carries entries for
each registry, and `PluginRegistry.install` applies all of them
atomically (all-or-nothing) and records the inverse for `uninstall`.
That is the plugin substrate's unique contribution — atomicity and
revocation — not the registries themselves.

This dissolves the false symmetry of "core registers via PluginRegistry
as a CorePlugin." The current `CorePlugin.ts` is ceremonial: it wraps
13 direct surface-catalog `register` calls inside a manifest so they
share the same install path as third-party plugins. The lifecycle
"proof" — unregister the core manifest, the catalog empties — is true
but tells you nothing about whether a real plugin could replace those
surfaces, because the plugin's *replacement* would itself just call
the contribution registries. The symmetry that matters lives at the
registries; the symmetry that's ceremonial lives at PluginRegistry.

The corrected position: contribution registries are the symmetry
substrate. Plugin substrate is the lifecycle/sandbox substrate over
it. Core UI uses the registries directly. `CorePlugin.ts` is not
required for the symmetry claim and should be retired in favour of
direct registry calls at boot. (Hot-reload of core surfaces, which is
the only argument for keeping `CorePlugin.ts`, is better solved by
making each feature module's registration boot-time idempotent and
re-runnable.)

### §3.4 The boundary's enforcement story

For the boundary not to rot, three guards:

1. **eslint `no-restricted-imports`** on each layer:
   - Kernel modules may import from kernel modules and shared utility
     only. They may not import from `features/*` or `shell-*`.
   - Feature modules may import from `@kernel/*`, sibling files within
     their own feature, and shared utility. They may not import from
     other features or from `shell-*`.
   - The plugin substrate may import from `@kernel/*` and contribution
     registries. It may not import from feature modules.
   - Surface entry points (the Lit/React elements that mount in zones)
     may not import from anything outside their feature module and
     `@kernel/*`.

2. **Dependency-graph test** that walks the module import graph and
   asserts the layer rules above. This catches transitive violations
   eslint misses (A imports B imports something forbidden to A).

3. **Compartment-introspection test** that confirms an UNTRUSTED plugin
   resolving `@kernel/operations` receives the attenuated implementation
   and cannot reach the privileged one through any module-resolver
   path. The plugin's actual capability surface is verifiable, not
   asserted.

The existing FSD eslint layer rules (`modules/ui-web/eslint.config.js`
§70–142) are the right *shape* but the wrong layering for this domain
(FSD's app/pages/widgets/features/entities/shared model doesn't
distinguish kernel from plugin substrate). The replacement is a
layering rule keyed on `@kernel/*`, `features/<feature>/`,
`plugin-substrate/`, `contribution-registries/`, `shared/`.

---

## §4 What this means for the existing substrate

This section catalogs what exists, what to keep, what to repurpose,
and what to retire. It is descriptive, not prescriptive about
ordering.

### §4.1 PluginHostApi (and its 12 sub-interfaces)

**Status**: ~80 methods across `PluginIdentity`, `PluginDataAccess`,
`PluginNavigation`, `PluginUIControls`, `PluginDiscovery`,
`PluginSettings`, `PluginSearchState`, `PluginInspectorState`,
`PluginSelection`, `PluginThemeState`, `PluginLayoutState`,
`PluginPlatform`, `PluginUtilities`, `PluginAI`.

**Fate**:
- `PluginIdentity`, `PluginDataAccess`, `PluginNavigation`,
  `PluginUIControls`, `PluginDiscovery`, `PluginSettings`,
  `PluginPlatform`, `PluginUtilities`, `PluginAI`, `PluginSelection`,
  `PluginThemeState` — kernel module candidates. Re-export their
  methods as discrete typed modules (`@kernel/operations`,
  `@kernel/notifications`, etc.). The interface bundles vanish.
- `PluginSearchState`, `PluginInspectorState` — return to the
  features they belong to. The kernel does not expose surface-internal
  state. If cross-feature read access is genuinely needed (Inspector
  watching Search selection is a real case), expose only the *read
  shape* — never the *write shape* — through KCS or via the Selection
  module that already exists.
- `PluginLayoutState`'s write methods (`setSurfaceVisibility`,
  `setSurfaceOrder`, `setActiveLayoutId`, `clearAllLayoutOverrides`) —
  belong to a Layout feature module. The kernel exposes only "observe
  active layout id" if anything.
- The composed `PluginHostApi` object — gone. Replaced by the set of
  module entry points. No `host` parameter on plugin `register()`;
  plugins `import { ... } from '@kernel/...'` and the Compartment
  resolver routes them to the trust-appropriate implementation.

### §4.2 HostApiImpl

**Status**: 831-line factory composing 12 sub-interfaces with
per-method trust branching, delegating to 11 different framework-
internal singletons.

**Fate**: dissolves. Each sub-interface becomes its own canonical
kernel module file. Trust-projected versions live as sibling modules,
chosen at Compartment resolver time. There is no factory.

### §4.3 PluginRegistry

**Status**: install/uninstall lifecycle, contract version validation,
contribution merging across 10+ contribution registries.

**Fate**: keep, narrow. PluginRegistry is the plugin substrate's
*transactional composer*. Its job: validate a manifest, resolve the
Compartment's module bindings to the trust-appropriate kernel modules,
call `register()` inside the Compartment to obtain a
`PluginContribution`, apply that contribution atomically to each
contribution registry, and remember the inverse for `uninstall`.

What it stops doing: pretending to be the registration path for
first-party UI. `CorePlugin.ts` retires (§3.3). First-party UI calls
contribution registries directly at boot.

### §4.4 Contribution registries

**Status**: well-shaped. CommandRegistry, StatusBarRegistry,
InspectorTabRegistry, ContextActionRegistry, EmptyStateRegistry,
KeybindingRegistry, TemplateCatalog, VirtualOperationCatalog,
aggregateRegistry, LayoutCatalog, surface-catalog, resource-catalog,
recovery-overlay-catalog.

**Fate**: keep, promote to kernel primitives. Their `register` /
`unregister` verbs are the canonical contribution path. First-party
UI uses them directly; plugin substrate uses them transactionally via
`PluginContribution`. Their location moves out of `shell-v0/commands`
into a kernel-primitives folder.

### §4.5 LayoutManifest / LayoutCatalog

**Status**: shipped. `DEFAULT_LAYOUT`, `FOCUS_LAYOUT`, Settings
layout picker, `userConfig.activeLayoutId`.

**Fate**: keep. The shape (JSON-only, no code-bearing) is correct;
this is exactly what D4 of the prior 507 argued and one of the few
places that argument lands cleanly. The LayoutCatalog joins the
contribution registries in §4.4.

### §4.6 PluginSourceProvider + Rust commands

**Status**: shipped. `discoverPlugins` / `readPluginSource` /
`getPluginDirectory`; Rust `scan_plugins` / `read_plugin_source` /
`get_plugin_dir` with size caps (manifest 64KB, source 1MB).

**Fate**: keep. This is plugin substrate's *source-loading* layer. No
architectural change; the abstraction is correctly placed.

### §4.7 settingsSchema on PluginManifest

**Status**: shipped as a field; SettingsSurface renders contributed
schemas via `<jf-form>`.

**Fate**: keep. This is one shape of `PluginContribution`; the
SettingsSurface reading from it is correct.

### §4.8 Trust tier attenuation table (prior 507 §3.4)

**Status**: encoded as `if (isUntrusted)` branches throughout
`HostApiImpl.ts` and `UNTRUSTED_READ_ALLOWLIST` constants.

**Fate**: relocate to the Compartment's module resolver. Each kernel
module ships {canonical, attenuated} variants where attenuation
matters; the resolver binds the appropriate one at install time. The
attenuation table moves from "runtime conditional inside one factory"
to "compile-time choice of module variant per plugin." Eight `if`
guards collapse into eight resolver bindings.

**Inv-1 (2026-05-19) — feasibility confirmed, transitional shape
named.** SES Compartment's `modules` arg supports this binding. The
transitional shape between today's endowment-bundle attenuation
(`PluginCapabilityBundle.ts:71-76`) and the resolver-binding target
is to keep the `buildCapabilityBundle` site as the *single mint
point* for trust-projected modules while migrating plugins from
script-eval-with-`host`-endowment to module-import-with-`@kernel/*`-
binding. The resolver bindings reuse `buildCapabilityBundle`'s
tier-keyed branching shape; the `if` guards move from `HostApiImpl`
into a single tier-keyed module-map builder, then disappear once
every kernel module has dedicated trust-variant siblings.

### §4.9 CorePlugin.ts

**Status**: 162-line manifest registering 13 core surfaces as a
`CORE`-tier plugin.

**Fate**: retire. The "structural proof" it provides (unregister core
plugin, surfaces gone) is ceremonial — it proves that the catalog
respects unregister, which is testable directly without dressing
first-party UI as a plugin. First-party UI calls
`surface-catalog.register` directly at boot. The symmetry claim with
real plugins is preserved by the contribution-registry symmetry, not
the manifest symmetry.

**Inv-5 confirmation (2026-05-19)**. Only two consumers of CorePlugin-
as-manifest exist:
1. The boot install path at `main.jsx:139, 161-162` (`if
   (!registry.has('core')) registry.install(createCorePluginManifest())`).
2. The hot-reload skip-guard at `main.jsx:222` (`if
   (installed.manifest.id === 'core') continue`).

There are zero `getPlugin('core')` lookups, zero `manifest.id ===
'core'` branches elsewhere, and no consumer-side `tier === 'CORE'`
logic that depends on a `CORE`-tier *manifest entry* existing in the
registry. Retirement is safe: replace (1) with direct surface-catalog
register calls at boot from each feature module's entry point;
replace (2) with a guard against any first-party plugin id (or
eliminate the discovery-loop guard altogether by ensuring discovery
runs over the third-party plugin directory, which is structurally
disjoint from first-party UI).

**Inv-8 confirmation (2026-05-19)**. Hot-reload of first-party UI is
not a reason to keep CorePlugin.ts. `PluginHotReload.ts` watches only
`~/.justsearch/plugins/` and `~/.justsearch/themes/` — third-party
directories. The skip-guard at `main.jsx:222` explicitly excludes
core from hot-reload. First-party UI hot-reload is the bundler's
responsibility (Vite HMR), not PluginRegistry's. §7 Q5 resolves:
retire CorePlugin.ts without preserving a hot-reload codepath; Vite
HMR continues to work because it operates below the registry
abstraction.

### §4.10 "Core surfaces using Host API" (prior 507 Phase 2)

**Status**: ~90% mechanical; some stragglers (`BrainSurface`,
`HealthSurface`, `HealthLitView`).

**Inv-7 precise depth (2026-05-19)**. Confirmed. Exactly 7 straggler
import lines across 3 files:
- `BrainSurface.ts:17-22` — `OperationClient`, `isTauriRuntime`,
  `pickFolder`, `confirmAsync` (4 imports retained as fallback
  paths inside method bodies).
- `HealthSurface.ts:20, 25` — `parseSseBuffer`, `getOverlayRecovery`.
- `HealthLitView.ts:36` — `getOverlayRecovery`.

Every other migrated surface (Search, Browse, Library, Settings,
TokenEditor, Agent, Activity, Help, Logs) imports nothing from
`api/`, `utils/`, or `operations/` at the surface level — only
`@kernel/*`-shaped paths (today: `plugin-api/plugin-types`), Lit,
and intra-`shell-v0/components`/`shell-v0/views` siblings. The
"mechanical" framing holds.

**Fate**: the framing is wrong. The right framing is not "swap
direct imports for `host_.*` calls" — that just substitutes one
boundary-violation pattern (importing framework state) for another
(routing through a god-object so the violation is hidden). The right
work is module decomposition:

- Move each feature's surface, its state stores, its operations
  clients, and its tests into a `features/<feature>/` directory.
- Replace state-store imports across features with kernel-promoted
  reads or, where the cross-feature dependency is wrong,
  restructure.
- Stop passing `PluginHostApi` to surface elements. Surfaces import
  what they need from `@kernel/*` and from sibling files in their
  own feature module.

The stragglers (`BrainSurface`'s `OperationClient` /
`pickFolder` / `confirmAsync` fallbacks; `HealthSurface`'s
`parseSseBuffer` + `RecoveryOverlayClient`; `HealthLitView`'s
`RecoveryOverlayClient`) become trivial once the feature modules
own those imports natively.

### §4.11 FSD eslint layer rules

**Status**: written, `warn`-level, no migration started (the
`src/{app,pages,widgets,features,entities,shared}/` directories don't
exist yet).

**Fate**: retire the FSD framing; replace with the layer rules in
§3.4. FSD's app/pages/widgets/features/entities/shared layering
doesn't model the kernel-vs-plugin-substrate distinction that this
codebase actually needs.

### §4.12 521 §11 future-work compatibility (Inv-6, 2026-05-19)

Tempdoc 521 §11 future-work items index against `host.X.Y`
sub-interface call shapes throughout. Spot-checked against the
decomposition:

- **§11.2 Selection-as-first-class** — already shipped as
  `host.selection.current` / `host.selection.subscribe`. Under the
  decomposition the names become `@kernel/selection`'s `current()` /
  `subscribe()`. Mechanical reframe.
- **§11.4 `host.ai`** — already shipped as `PluginAI` sub-interface.
  Becomes `@kernel/ai`. Mechanical reframe.
- **§11.8 Per-sub-API contract versioning** — proposes
  `manifest.contractVersions: { "host.search": "1.1", "host.ai":
  "0.9" }`. The version-handshake mechanism is string-keyed and
  agnostic to whether the consumer is a flat-host method call or a
  module import. Under the decomposition: `contractVersions:
  { "@kernel/search": "1.1", "@kernel/ai": "0.9" }`. Mechanical
  reframe; the underlying handshake machinery survives intact.
- **§11.1 ShellContext + WhenExpression**, **§11.3 Profiles /
  Spaces**, **§11.5 Command ↔ Operation bridge**, **§11.6
  EmptyStateRegistry**, **§11.7 TemplateCatalog**, **§11.9-11 polish
  + cross-cutting invariants** — these target registries, state
  shapes, and substrates beneath the Host API; they are decomposition-
  neutral.

No §11 future-work item requires the god-object shape. Every
sub-interface-keyed proposal reframes one-for-one against the
module-graph naming. §11.8's per-sub-API versioning becomes per-
kernel-module versioning without changing the handshake protocol.

### §4.13 State-store tangles (Inv-2 + Inv-3, 2026-05-19)

**Inv-2 outcome — clean.** Every writer to the surface-internal
sub-interfaces (`host_.search.*`, `host_.layout.*`, `host_.theme.*`,
`host_.selection.*`) is the owning feature's own surface. SearchSurface
is the sole writer to `host_.search.*` (six methods, callsites at
SearchSurface.ts:477, 486, 498, 512, 529, 542, 560, 568, 764, 768,
846). SettingsSurface is the sole writer to `host_.layout.*` and
`host_.theme.*` (SettingsSurface.ts:856, 875, 945, 1053, 1055, 1094).
`host_.inspector.*` has no production write callers. §4.1's demotion
of `PluginSearchState` / `PluginInspectorState` / `PluginLayoutState`
write surfaces to feature-internal API stands without exception.

**Inv-3 outcome — three tangles to resolve before feature-module
decomposition lands.**

1. **inspectorState ↔ searchState ↔ selectionState tangle.**
   `inspectorState.ts` wraps `selectionState`; `searchState.ts`
   imports `SelectedItem` type from `inspectorState`; `SearchSurface`
   imports `selectionState` directly. This is the load-bearing
   cross-feature coupling: the inspector reads the search-feature's
   selected item; the search feature emits selection updates to the
   inspector; both touch the kernel-cross-cutting selection store.
   Resolution shape: promote `SelectedItem` to a kernel type next to
   `selectionState` (it is already cross-cutting), refactor
   `searchState` and `inspectorState` to import it from there, then
   the two features become structurally independent with selection as
   their shared kernel observable. Selection itself stays kernel
   (already correct).

2. **aiStateStore cross-feature use.** Imported by both
   `BrainSurface.ts` (brain feature) and `UnifiedChatView.ts` (chat
   feature) plus several chrome substrates (`Shell.ts`, `StatusDeck`,
   `CapabilityPills`, `IndexingOverlay`, `askAi`). Resolution: AI is
   genuinely cross-cutting — it is the shared LLM state across every
   feature that calls the model. `aiStateStore` belongs in the
   kernel alongside `useAI` (the existing `host.ai` sub-interface
   becomes one of its consumers, not its owner). The brain "feature"
   may itself be misnamed: it is the kernel-AI-runtime *configuration*
   surface, not a distinct feature.

3. **searchFiltersState ↔ pinnedSearchState ↔ savedViewState ↔
   UserStateDocument projection chain.** Internally search-owned;
   externally read by `themeState.ts` (type only) and by aggregate-
   substrate `AdvisoryStore` / `JfResource`. Resolution: this is not
   a feature-decomposition blocker — `UserStateDocument` is the
   persistence substrate (kernel), and the search feature's filter /
   pin / saved-view stores are its projections. Aggregate-substrate
   consumers are kernel-substrate, not foreign-feature, so they don't
   break the boundary.

**Stores that can move feature-internal immediately**: `userConfigState`,
`themeState`, `viewerAudienceState` — all settings-owned with only
substrate / settings consumers.

**Stores that stay kernel**: `selectionState`, `UserStateDocument`,
`aiStateStore` (after the §4.13.2 reshape).

**Stores that move feature-internal after tangle resolution**:
`searchState`, `searchFiltersState`, `pinnedSearchState`,
`savedViewState`, `inspectorState`.

This refines §2.2's "feature modules can be self-contained" — they
can, but two cross-feature edges (selection routing,
aiStateStore ownership) must be settled first, and `SelectedItem`
must be promoted out of `inspectorState` into the selection-store's
public type.

### §4.14 aggregate-substrate

**Status**: shipped. Cross-shape contextual surfacing through
`aggregateRegistry`, kind/context typed enums, strategy contributions
via `PluginContribution.aggregateStrategies`.

**Fate**: keep; it joins the contribution registries. Its existence
is *evidence for* the §3.3 position: it's already proof that
first-party features and plugins should share contribution registries,
not share a god-object capability surface.

---

## §5 Why this structure prevents the long-term problem

The original 507 produced its strain not by mistake at any particular
step but by trying to make one surface serve three different demands.
Once split:

- The kernel boundary stays *small* because nothing surface-specific
  has anywhere to land on it. The boundary's natural pressure is
  outward toward more methods; the structural answer is "that method
  doesn't belong on the kernel; it belongs in the feature whose
  surface needs it." With no god-object, there is no place to put it
  by accident.

- Trust attenuation stops accreting because each kernel module's
  trust-projected variants are explicit sibling modules visible in
  the source tree, not branches inside a factory. Adding a new
  attenuation is editing the variant module; adding a new method is
  adding it to *one* domain whose trust shape is local.

- Cross-feature dependencies become visible. Today,
  `PluginSearchState` lets any code route through the host to drive
  search state, with no signal that it crossed a feature boundary.
  After: an inspector importing search state has to either go
  through a kernel-exposed observable (visible, designed) or break
  the import rule (caught by eslint). The boundary cannot be hidden
  inside a method named neutrally enough to feel safe.

- "Core as plugin" is no longer a claim that has to be defended; it
  reduces to "core uses the same contribution registries plugins use,"
  which is structurally true at the registry level and trivially
  testable.

- The plugin ecosystem premise becomes *optional* for the
  restructuring's value. The kernel-module decomposition pays for
  itself purely as feature-module hygiene; plugin substrate sits on
  top whether or not anyone ever ships a third-party plugin.

---

## §6 Non-goals

- **Phasing, slice ordering, or migration plans.** This tempdoc is
  the destination; how to get there is the next conversation, and the
  right shape of that conversation is "what is the cheapest sequence
  of edits that ends with the boundary checked-in §3.4?" — answered
  by a separate slicing tempdoc.

- **Chrome replacement.** The prior 507 deferred this to slice 476
  and that deferral remains correct. None of the structure here
  changes the chrome-replacement problem.

- **Plugin marketplace, signing infrastructure (Sigstore), or
  micro-frontend module federation.** Independent of this design.

- **A position on whether to build a plugin ecosystem.** The structure
  above is the right structure whether or not plugins are real,
  because it equally describes "feature modules with a small kernel"
  for an app that never ships a third-party plugin. The structure
  *enables* a plugin ecosystem if one is desired later, but does not
  argue for one.

---

## §7 Open questions

These remain genuinely open and should not be resolved in this
tempdoc:

1. **Where is the kernel package actually rooted?** Today everything
   under `modules/ui-web/src/` is a flat namespace. A `@kernel/*`
   alias requires either a TS path mapping or an actual workspace
   package split. The right answer depends on whether the kernel is
   ever going to be consumed outside this app (its design draft 421
   suggests yes — "the operating substrate underneath any future
   JustSearch UI").

2. **What is the contribution-registries' module home?** Today they
   live in `shell-v0/commands/` and `shell-v0/aggregate-substrate/`.
   The "shell-v0" prefix telegraphs "we know this will be replaced."
   In the target structure, contribution registries are kernel
   primitives — they probably live in a kernel-primitives folder
   alongside the kernel modules themselves.

3. **What does the Compartment module resolver look like in
   practice?** SES Compartment supports a module-map; the question
   is whether trust-projected kernel modules live as sibling files
   the resolver picks between (clean, more files), or as a single
   module with a tier-keyed export shape (fewer files, more branching
   inside each module). The first is what §4.8 assumes; the second is
   the cheaper transitional shape and might be acceptable.

4. **What happens to the inspector?** It's currently treated as
   cross-cutting (`PluginInspectorState`, `host_.ui.showInspector`,
   `setSelected`). The right place may be a kernel-owned zone (like
   Notifications), a feature module (like Search), or
   genuinely-split (selection is kernel, tab state is feature).
   Settling this resolves whether `useInspector` is a kernel module
   or whether the inspector is a feature with kernel-exposed selection
   awareness.

5. ~~**Is hot-reload of first-party UI worth supporting?**~~
   **Resolved (Inv-8, 2026-05-19)**: hot-reload of first-party UI does
   not currently go through `PluginRegistry`. `PluginHotReload.ts`
   watches only third-party directories and `main.jsx:222` excludes
   `core` from hot-reload. First-party hot-reload is Vite HMR
   territory, which operates below the registry abstraction. Retiring
   `CorePlugin.ts` does not regress dev-loop behavior. See §4.9.

6. **What capability modules should KCS expose?** §2.1 names ten — `useOperations`, `useResources(id)`, `useNotifications`, `usePlatform`, `useNavigation`, `useSettings(scope)`, `useI18n`, `useTheme`, `useAI`, `useSelection`. Tempdoc [543 — three-axes-of-contribution](./543-three-axes-of-contribution.md) proposes **eight additional KCS capability modules** that complete the contribution-system substrate (Provenance, Scope, Action, HoverPreview, EvaluationContext, Effect Journal, Contribution Manifest, Workspace Profiles). 543 §19 (added 2026-05-21) maps each substrate to its KCS capability-module shape under this tempdoc's three-layer model. The eight substrates have a 1764-test-validated prototype implementation on the `worktree-507-kernel-boundary` branch (against an older four-layer 507 draft); the design holds under three-layer with mount-point adaptation only. Independent-review verdict: APPROVE-WITH-FOLLOWUPS. See 543 §19 for the equivalence map.

---

## §8 Codebase facts captured here so a future agent does not re-investigate

- `modules/ui-web/src/shell-v0/plugin-api/plugin-types.ts` — 937 lines,
  defines `PluginHostApi` and its 12 sub-interfaces, plus
  `PluginManifest` (with `settingsSchema`), `PluginContribution`
  (with `customElements`, `surfacePorts`, `translations`,
  `surfaceContributions`, `resourceContributions`,
  `recoveryOverlays`, `statusBarItems`, `inspectorTabs`,
  `contextActions`, `emptyStateContributions`, `resolutionAliases`,
  `resolutionSynonyms`, `aggregateStrategies`).

- `modules/ui-web/src/shell-v0/plugin-api/HostApiImpl.ts` — 831 lines,
  the trust-branching factory.

- `modules/ui-web/src/shell-v0/plugin-api/PluginRegistry.ts` — 902
  lines, install/uninstall + contribution dispatch to: surface
  catalog, resource catalog, recovery overlays, status bar, inspector
  tabs, context actions, empty states, surface aliases, aggregate
  strategies, i18n catalog.

- `modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts` — 162 lines,
  13 core surfaces registered through `PluginRegistry.install` as a
  `CORE`-tier manifest.

- `modules/ui-web/src/shell-v0/plugin-api/PluginSourceProvider.ts`
  +  `modules/shell/src-tauri/src/lib.rs:899-971` (scan_plugins /
  read_plugin_source / get_plugin_dir).

- `modules/ui-web/src/shell-v0/layout/LayoutManifest.ts` —
  `DEFAULT_LAYOUT`, `FOCUS_LAYOUT`, in-memory catalog with
  contribution registration.

- Contribution registries (`modules/ui-web/src/shell-v0/commands/`):
  `CommandRegistry`, `StatusBarRegistry`, `InspectorTabRegistry`,
  `ContextActionRegistry`, `EmptyStateRegistry`, `KeybindingRegistry`,
  `TemplateCatalog`, `VirtualOperationCatalog`, plus
  `whenExpression`, `CommandPalette`, `VirtualToolDispatcher`.

- Contribution registries (`modules/ui-web/src/shell-v0/aggregate-substrate/`):
  `aggregateRegistry`, kind+context typed enums, `bootstrap`,
  `strategies/`, `components/`, `queryPrimitives`.

- Surfaces with `PluginHostApi` already wired:
  `SearchSurface`, `BrowseSurface`, `LibrarySurface`,
  `SettingsSurface`, `TokenEditorSurface`, `HelpSurface`,
  `ActivitySurface`, `AgentSurface` (via `host_` property).

- Surfaces with straggler direct imports:
  - `BrainSurface.ts` — `OperationClient`, `isTauriRuntime`,
    `pickFolder`, `confirmAsync` as fallback paths.
  - `HealthSurface.ts` — `parseSseBuffer`, `getOverlayRecovery`.
  - `HealthLitView.ts` — `getOverlayRecovery`.

- `modules/ui-web/eslint.config.js` defines FSD layer rules at
  `warn`-level, plus an `error`-level barrel-enforcement rule on
  `api/generated/wire-types` and `api/types/registry`. The
  `src/{app,pages,widgets,features,entities,shared}/` directories do
  not yet exist; FSD migration is unstarted.

- `modules/ui-web/src/shell-v0/plugin-api/substrate-lockdown.test.ts`
  runs the plugin substrate under `lockdown()` to confirm it works
  in the locked-down realm.

- Tempdoc 421 (`the retired 421 FE-rewrite draft `)
  is the broader framework-kernel draft this rewrite aligns with;
  its `00-orientation/00-purpose-boundaries.md` explicitly distinguishes
  kernel responsibilities (capability negotiation, operation
  discovery, resource observation, plugin loading) from product
  responsibilities (search/browse/agent composition, visual identity,
  workflow copy).

- Cross-tempdoc references that cite the old 507 by section anchor
  will become stale: 521's "Depends on: 507" remains valid (the file
  number is unchanged); 521 §1 status-note references to
  "§3.1 / §3.4 absorbed by slice 521" / "§6 Phase 1 — Expand
  PluginHostApi — shipped" no longer point at meaningful anchors,
  but the underlying claims (sub-interfaces shipped, contribution
  axes shipped) remain factually correct and can be re-anchored to
  this document's §4 if reread.
