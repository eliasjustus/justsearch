---
title: "547 — ui-web static-analysis findings (bias-free deep pass)"
type: tempdoc
status: done
created: 2026-05-25
category: frontend / static analysis / correctness + security
related:
  - modules/ui-web/src/shell-v0/state/UserStateDocument.ts (state core)
  - modules/ui-web/src/shell-v0/plugin-api/ (plugin/trust spine)
  - modules/ui-web/src/api/http.ts + schemas.ts (wire boundary)
  - modules/ui-web/src/shell-v0/state/UserStateDocument.profileSwitchRebind.test.ts (F11 regression test, landed)
---

# 547 — ui-web static-analysis findings (bias-free deep pass)

**Status**: done

## Method & framing

This pass was run **without reading any project documentation** — a deliberate
constraint to surface findings from the code itself, free of the narrative bias
the docs/tempdoc corpus would impart. Intent was reconstructed from source only.
Every finding separates **fact** (cited `file:line`) / **inference** /
**judgment**, and carries a severity + confidence. The pass is **static-analysis
only**, with one exception: **F11 was converted to a runtime-proven finding**
(failing regression test → root-cause fix → green).

### Coverage

- **Deep**: plugin / extensibility / trust spine (`main.jsx`, `plugin-types.ts`,
  `HostApiImpl.ts`, `PluginLoader.ts`, `PluginCompartment.ts`,
  `PluginCapabilityBundle.ts`, `PluginSourceProvider.ts` + a Rust `lib.rs`
  cross-check); state core (`UserStateDocument.ts` end-to-end, plus
  `savedViewState.ts` / `viewerAudienceState.ts`); wire boundary (`schemas.ts`,
  `http.ts`).
- **Not covered**: `PluginRegistry.ts` install/uninstall correctness; `views/`
  hotspots (`Shell.ts` 2238 LOC, `BrainSurface.ts` 1973 LOC); `router/`;
  `streaming/streams.ts` internals (sampled only); the renderer registries.

### Topology note (corrects a first-pass assumption)

`ui-web` is **~501 files / ~111k LOC** of source (the naive 868k count included
`node_modules`). There is **no React** — it is a **Lit web-components** app
(`lit ^3.3.2`) with a custom `<jf-*>` shell + `@lumino/widgets` docking. `main.jsx`'s
`.jsx` extension is vestigial from a decommissioned React era.

## Cross-cutting theme (meta-finding)

This is careful code with a single recurring defect class: **comments and types
assert guarantees the runtime does not deliver.** Examples below: sandbox
"isolation" that leaks (F1), comments that contradict code (F5, F6, F8, F9),
invariants enforced only by prose (F11/F12), validation advertised but opt-in
(F13). In a codebase this comment-dense, the prose is load-bearing and drifts
silently. The defects are *omissions against an otherwise-rigorous baseline*,
which is precisely where confident-but-wrong comments hide them.

## Findings register

Severity tags: latent = severity contingent on the not-yet-shipped third-party
plugin ecosystem (per the product decision that plugins are "not now"). For those,
the actionable item is **F8's fence**, not hardening the sandbox.

| ID | Finding | Severity | Conf. | Status | Anchor |
|----|---------|----------|-------|--------|--------|
| F11 | Profile switch overwrites the target profile's `savedViews` + `viewerAudience` with the previous profile's; persisted → durable data loss | High | Proven | **Fixed + verified** | `UserStateDocument.ts:1304` |
| F9 | `parseDocument` discards unknown-version docs while the comment promises forward-compat preservation → silent data loss on build downgrade | High | Proven | **Fixed + verified** | `UserStateDocument.ts:439-442` |
| F1 | UNTRUSTED plugin endowment includes the host `document`; `document.defaultView` re-grants `window`/`fetch`/`localStorage`, defeating the sandbox | Critical (latent) | High (mechanism) | Open (deferred) | `PluginCompartment.ts:184` |
| F2 | `lockdown()` is opt-in/default-off + fire-and-forget; a Compartment shares mutable primordials without it | High (latent) | High | Open (deferred) | `main.jsx:39-58` |
| F10 | `saveDocument` swallows persistence failures while the UI shows success → silent data loss on next reload | Med-High | High | **Fixed + verified** | `UserStateDocument.ts:911-921` |
| F8 | Untrusted-plugin load path is LIVE (Settings URL loader + Tauri auto-discovery), but two comments claim it isn't shipped / not implemented | Med-High | High | **Comments fixed; behavioral gating held (product decision)** | `SettingsSurface.ts:993`, `PluginSourceProvider.ts:44` |
| F13 | Wire boundary validated opt-in only; central `request<T>()` rubber-stamps via `as T` | Medium | High | Open | `http.ts:328` |
| F4 | Production discovery omits `expectedPluginId` → all file plugins share the `'unknown'` namespace; namespaced ones fail to load | Med (latent) | High | Open (deferred) | `main.jsx:186` |
| F12 | "Flat-fields-win" round-trip silently drops active-profile mutations made via the `profiles` map (the trap that produced F11) | Medium | High | **Mitigated** — rebind-site drift closed via shared `flatSlicesFromProfile`; the flat-authoritative footgun for arbitrary future mutators remains by design | `UserStateDocument.ts:328-345` |
| F3 | No real trust verification; `StubTrustChannel` always returns UNTRUSTED; signing is "V1.5.2+" | Med (latent) | High | Open (deferred) | `PluginLoader.ts:244` |
| F5 | Contract drift: contradictory `PluginHostApi` JSDoc (flat vs nested); AI "rate-limited" documented but not implemented; `PLUGIN_CONTRACT_VERSION='1.1'` vs 1.5.2 features | Med | High | **Comments fixed; version-constant bump deferred** | `plugin-types.ts:843,896` |
| F6 | Stale header: `PluginCompartment.ts` says the loader bypasses the Compartment; it no longer does | Low-Med | High | **Fixed** | `PluginCompartment.ts:19-25` |
| F7 | `getSystemStatus` bypasses the tier-gated fetch via `globalThis.fetch` | Low | High | Open | `HostApiImpl.ts:586` |
| F14 | `deserializeArea` reset ALL split proportions to equal weight if any pane was unresolved (comment said "truncate"); surviving panes lost their saved sizing | Low-Med | Proven | **Fixed + verified** | `Shell.ts:294` |
| F15 | Plugin `resolutionAliases` leaked on uninstall — `applyContribution` merges them but `uninstall` never removed them, leaving an alias pointing at a removed surface | Med | Proven | **Fixed + verified** | `PluginRegistry.ts:404` |
| F16 | `resolutionSynonyms` is a declared plugin-contract axis but `applyContribution` never reads it (phantom/unimplemented contribution) | Low-Med | High | Open (decision) | `plugin-types.ts:440` |
| F17 | Query-param parsers used `key in out` on a `{}` bag, so params named like `Object.prototype` members (`toString`, `constructor`, `__proto__`) were mis-parsed (wrapped as bogus arrays) / corrupted the bag prototype. Input reachable from LLM + user URLs | Med | Proven | **Fixed + verified** | `parser.ts` (parseQueryParamsAsState/Args) |
| F18 | User keybindings lost their required `provenance` (and `when`) across a reload — the persisted shape omits them, an `as unknown as KeybindingEntry[]` cast hid the gap, and `loadPersistedKeybindings` left `provenance: undefined` (tempdoc-543 invariant violation) | Med | Proven | **Fixed + verified** (provenance re-stamped on load; `when` non-persistence documented) | `KeybindingRegistry.ts:183` |
| F19 | `resolutionTelemetry` has only an age cap (7d), no count cap, and does a full read-modify-write per intent dispatch → unbounded growth + O(n)/event within the window, then silent quota failure | Low | High | Open (by-design? — telemetry, documenting not fixing) | `resolutionTelemetry.ts` |
| — | Lower: projection memoization defeated by `?? []` selectors; `notify` swallows listener errors silently; `parseSinglePin` duplicates the inline pin parser; retry comment says "exponential," code is linear | Low | High | Open | various |

## Detail on the headline correctness bugs

### F11 — profile switch corrupts target profile (FIXED)

`setActiveProfileId` rebound only 4 of 6 per-profile flat slices
(`userConfig`, `activeThemeId`, `pinnedSearches`, `keybindingOverrides`),
omitting `savedViews` and `viewerAudience`. Those carried over from the
previously-active profile via `...doc`; `mutateDocument`'s round-trip
(`storageFromView`, flat-fields-win) then persisted them onto the target
profile. Switch A→B therefore overwrote B's bookmarks + view-audience with A's,
durably. `viewerAudience` is a **view preference, not an access-control
boundary** (per `viewerAudienceState.ts` header), so this is data-correctness /
UX corruption (lost bookmarks, surprise audience tier), not a security issue.

- **Fix**: `setActiveProfileId` now rebinds all six per-profile slices, with
  absent optionals explicitly cleared to `undefined` (same discipline already
  used for `keybindingOverrides`), plus a comment naming the
  rebind-every-per-profile-slice invariant to blunt F12 recurrence.
- **Test**: `UserStateDocument.profileSwitchRebind.test.ts` (4 tests incl. a
  positive control on `pinnedSearches`). Was red for the predicted reason; now
  green.
- **Verified**: 67/67 state tests pass (incl. existing 59 `UserStateDocument` +
  4 `profileSwitchIntegration`); full `tsc --noEmit` clean.

### F9 — unknown-version doc discarded despite forward-compat comment (FIXED)

`parseDocument`, unknown `version`: comment said *"forward-compat — accept
opaquely so a downgrade scenario doesn't lose data"* then `return null`
(rejection). Downstream `ensureInitialized` treated a null parse of the v2 key as
"malformed → fall through" → lands on the v1 key or a **defaults reset** that
overwrote the stored doc. A doc written by a newer build (the exact downgrade the
comment names) was silently wiped.

- **Fix**: `ensureInitialized` now peeks the raw `version`; a document with
  `version > CURRENT_SCHEMA_VERSION` (2) is **preserved on disk** and the session
  runs with defaults (logged via `stateLog.warn`), instead of being overwritten.
  Genuinely malformed blobs still fall through to legacy recovery. The misleading
  `parseDocument` comment was corrected to describe actual behavior.
- **Residual (documented in code)**: a subsequent *user* mutation in the
  downgrade session still persists over the newer doc via `saveDocument`. Boot no
  longer destroys it unprompted — the silent path is closed. A fully robust fix
  (versioned keys / refuse-to-downgrade) is a larger change, deferred.
- **Test**: `UserStateDocument.dataLoss.test.ts` — red→green (stored v3 doc +
  unknown field survives a boot).

### F10 — silent persistence failure (FIXED)

`saveDocument` wrapped `setItem` in `try{}catch{}` "best-effort persistence", but
`mutateDocument` updated memory + fired listeners regardless. On
quota-exceeded / serialization error the UI showed success; the change was gone on
next reload, with no diagnostic.

- **Fix**: `saveDocument` now returns a boolean, records the error in
  `lastPersistError`, and logs via `stateLog.error` instead of swallowing. The
  in-memory update is intentionally NOT reverted (reverting mid-mutation would be
  more surprising) — the change is that the failure is now observable.
  `__getLastPersistError()` exposes it for tests/diagnostics.
- **Note**: this is the observability fix, not a recovery mechanism. Surfacing the
  failure to the user (a toast) would be a further, larger change.
- **Test**: `UserStateDocument.dataLoss.test.ts` — forward guard (the pre-fix bug
  was *silence*, so there is no red-state to reproduce; the test asserts the new
  observability under a forced quota failure).

## Round-trip defect-class sweep

After F9/F11, the persistence round-trip (`serialize → store → parse →
reconstruct`) was treated as a *class* and swept across every independent
`localStorage`-backed store + the Shell layout serializer:

| Store | Verdict |
|---|---|
| `UserStateDocument` | F9/F11 fixed; round-trip now covered by tests |
| `Shell.ts` `SerializedLayout` (`serialize/deserializeArea`) | **F14 found + fixed** — split-proportion loss on unresolved pane |
| `NavigationJournal` | Sound — validates `entries`/`cursor` shape, clamps cursor; only gap is it trusts entry *contents* wholesale (low severity, FIFO-evicted system data) |
| `conversationListStore` (recent sessions, titles) | Robust enough — raw `JSON.parse` but catch-to-default; no field-drop. Best-effort cache, low severity |
| `router/promotedAliases`, `router/resolutionTelemetry` | Small JSON round-trips; not deep-read, no obvious asymmetry |
| etag-paired catalog caches (`*CatalogClient`, `errorCatalog`, `resourceCatalog`) | Server-refetchable caches; out of scope for user-data loss |

Net: the class produced **3 real bugs (F9, F11, F14)**, all now fixed with
round-trip tests. The remaining stores are sound or low-severity. The tests
(`UserStateDocument.dataLoss.test.ts`, `.profileSwitchRebind.test.ts`,
`Shell.layoutRoundTrip.test.ts`) are the seed of a promotable round-trip gate.

## PluginRegistry install/uninstall investigation (2026-05-25)

The predicted defect class — `uninstall` failing to invert one of the ~13
contribution axes `applyContribution` adds — was confirmed by mapping every
apply-call against every remove-call:

- **F15 (fixed)**: `resolutionAliases` had an apply (`setSurfaceAliases`, line
  991) with no matching removal in `uninstall`. Every other axis (surface,
  resource, recovery, status-bar, inspector, context-action, empty-state,
  walkthrough, aggregate-strategy, port-handler, i18n catalog) *is* removed
  symmetrically — aliases were the single gap. Fixed with a best-effort,
  target-matched removal; regression test `PluginRegistry.aliasCleanup.test.ts`
  (red→green, plus an override-not-clobbered control).
- **F16 (open, decision)**: `resolutionSynonyms` is declared on
  `PluginContribution` and documented as a real feature, but
  `applyContribution` never references it — a plugin declaring synonyms gets a
  silent no-op. Decision: implement synonym application (needs the resolver's
  synonym path) or remove the field from the contract. Not a bug-fix; deferred.

`customElements.define` remains the one genuinely un-removable axis (browser
limitation, already documented in the code) — out of scope.

## View lifecycle probe (2026-05-25)

Hypothesis: the large view classes would leak subscriptions / listeners /
timers (open in `connectedCallback`, never released in `disconnectedCallback`).
Probed the highest-risk view — `UnifiedChatView` (SSE chat, 4 teardownables).
**Result: exemplary.** `disconnectedCallback` aborts the fetch controller,
stops the render-tick interval, calls all three store unsubscribes (with
null-out), removes all three `cite-ref` listeners, and disposes the hover card.

This is a negative result that updates the prior: the views appear to have
**strong lifecycle discipline**, so a full lifecycle-symmetry sweep is likely
low-yield. Deferred unless a specific leak surfaces (e.g. via the live tier).

## Round-2 sweep: streaming, XSS, URL parsing (2026-05-25)

After the state + plugin work, swept three more distinct concerns:

- **Streaming substrate — clean.** `EnvelopeStreamPool` ref-counting is sound
  (traced the ABA hazard: a subscriber's `+1` keeps its entry alive until its
  own `released`-guarded unsub fires, so an unsub can't hit a replaced entry).
  `EnvelopeStream` has symmetric `start`/`stop` (removes all 3 listeners +
  `es.close()`), defensive frame parsing, and isolated listener errors. Minor
  notes only: a throwing `start()` leaves a zombie pool entry; `seq` isn't
  monotonic-guarded (relies on reducer idempotency).
- **XSS surface — closed.** The only `unsafeHTML` (`MarkdownBlock.ts:206`) is
  fed `DOMPurify.sanitize(...)`. No raw dynamic `innerHTML` writes in production
  (shell-demo is a static template; `behavioralPass8` only *reads* innerHTML).
  Plugin `render: () => string` results flow through Lit's auto-escaping
  `` html`` ``, not a sink — safe by default.
- **URL parsing — F17 found + fixed.** `key in {}` true-for-inherited bug in the
  query-param parsers; fixed with `Object.prototype.hasOwnProperty.call`.

Pattern after two rounds: the **infrastructure / plumbing** layers (streaming,
view lifecycle, XSS handling) are robust; real defects cluster in **state
semantics** (F9/F11/F14), **plugin-contribution symmetry** (F15/F16), and
**parsing** (F17). Five consecutive clean infra probes vs. repeated hits in
semantics/parsing — a useful map for where future attention pays off.

`commands/KeybindingRegistry` probed next (the map predicted yield): key
parsing/matching is clean, but its **persistence path had F18** (a round-trip
field-drop — the state-semantics cluster striking again, exactly as the map
predicted). The map keeps holding: parsing/matching infra clean, round-trip
persistence buggy.

**Persistence-class sweep CLOSED (2026-05-25).** The last two stores
(`router/promotedAliases`, `router/resolutionTelemetry`) are sound — flat
key-value caches with catch-to-default. `promotedAliases` is not F17-vulnerable
(computed-key writes create own props, never hit the proto setter). One Low note
only: F19 (telemetry has no count cap). **Refined map:** round-trip bugs occur in
stores with **rich/structured shapes** (profiles, keybindings — multiple fields +
optionals get dropped on the schema round-trip); **flat key-value caches are
sound** (nothing structured to drop). This predicts where future round-trip risk
lives: any store that persists a multi-field record through a hand-written
validate/parse step.

Static high-yield surface is now **exhausted**. Remaining unexplored is
infra-adjacent and low-yield by the map (`Shell` pane lifecycle, `components/`);
the next qualitatively-new findings require the **live-stack tier**.

## Recommended next (priority order)

Remaining items each need either a product decision or larger-scope design work
(the cheap, unambiguous fixes are done):

1. **F8 behavioral gating — DEFERRED (user decision, 2026-05-25)**. The live
   untrusted-load path stays as-is for now (plugins are "not now"); the false
   comments are corrected so the posture is at least honest. Revisit when the
   plugin ecosystem becomes a near-term goal (alongside F1–F4).
2. **F5 version-constant** — bump `PLUGIN_CONTRACT_VERSION` to match the
   implemented surface, or document the intended deprecation policy.
3. **F12 — DONE (mitigated 2026-05-25)**. Extracted `flatSlicesFromProfile` as
   the single source of truth for the per-profile field set, shared by
   `viewFromStorage` + `setActiveProfileId`, so the two rebind sites can no
   longer drift (the F11 vector). The broader flat-authoritative footgun (a
   mutator writing `profiles[activeId]` directly is still clobbered) remains by
   design — accept unless it bites.
4. **F5 version-constant — DECIDED: leave** (2026-05-25). Bumping a compatibility
   constant blind could change contract semantics; safe default is no change.
5. **F16 synonyms — DECIDED: leave as documented phantom** (2026-05-25).
   Implementing is speculative; deleting unprompted is wrong (may be intentional
   forward-compat). Surfaced for a future owner decision.
6. **F13 / F1–F4** — wire-boundary enforcement (large / direction-shaping) and
   the deferred-latent plugin sandbox items; revisit when warranted.
7. Live-stack verification tier (run the app) — the only remaining source of NEW
   findings; needs the shared dev stack.

## Session log

- 2026-05-25: register compiled; **F11, F9, F10 fixed + verified**. New tests:
  `UserStateDocument.profileSwitchRebind.test.ts` (F11, red→green),
  `UserStateDocument.dataLoss.test.ts` (F9 red→green; F10 forward guard). Full
  state suite 250/250 green; `tsc --noEmit` clean.
- 2026-05-25: **F5, F6, F8 comment corrections landed** — removed the
  contradictory `PluginHostApi` JSDoc (kept nested-access form), corrected the
  false "rate-limited" / "no Install UI" / "scan_plugins not implemented" /
  Compartment-bypass comments; each now points to this tempdoc's F-id. Plugin-api
  suite 131/131 green; `tsc --noEmit` clean. F8 behavioral gating + F5
  version-constant bump deferred as product decisions.
- 2026-05-25: **round-trip defect-class sweep** across all independent
  persisted stores; **F14 found + fixed** (Shell split-proportion loss) with
  `Shell.layoutRoundTrip.test.ts` (red→green). `serializeArea`/`deserializeArea`
  exported for direct testing. Remaining stores (NavigationJournal,
  conversationListStore, router) verified sound / low-severity. Shell suite
  18/18 green; `tsc --noEmit` clean.
- 2026-05-25: **PluginRegistry + view investigation** (user: "investigate
  remaining work"). Found **F15** (alias leak on uninstall — fixed +
  `PluginRegistry.aliasCleanup.test.ts` red→green) and **F16** (resolutionSynonyms
  phantom axis — documented, decision deferred). View-lifecycle probe of
  `UnifiedChatView` = exemplary teardown; full sweep deferred as low-yield.
  Plugin-api suite 133/133 green; `tsc --noEmit` clean. F8 plugin-loading
  gating marked DEFERRED per user.
- 2026-05-25: **F12 mitigated** — extracted `flatSlicesFromProfile` shared by
  `viewFromStorage` + `setActiveProfileId` so the rebind sites can't drift
  (closes the F11 recurrence vector). State suite 250/250 green; `tsc` clean.
  Defaults recorded: F5 (leave), F16 (leave as phantom). Adopted a standing
  autonomy model — act on reversible/contained work, heads-up for
  large/direction-shaping, stop only for outward/irreversible (push, live stack).
- 2026-05-25: **round-2 sweep** (streaming / XSS / URL parsing). Streaming
  substrate + XSS surface verified clean; **F17 found + fixed** (parser
  `key in {}` prototype-chain bug) with `parser.protoKeys.test.ts` red→green.
  Router suite 168/168 green; `tsc` clean. Map: infra is robust, defects cluster
  in state-semantics / plugin-symmetry / parsing.
- 2026-05-25: **KeybindingRegistry probe** (map-directed). Key parsing/matching
  clean; **F18 found + fixed** — user keybindings lost required `provenance` on
  reload (round-trip field-drop). Fix re-stamps a CORE-tier provenance fallback
  on load; `KeybindingRegistry.provenance.test.ts` red→green. Commands suite
  141/141 green; `tsc` + eslint clean. The map held: persistence round-trip
  buggy, matching infra clean.
- 2026-05-25: **persistence-class sweep CLOSED**. Last two stores
  (promotedAliases, resolutionTelemetry) sound; one Low note (F19, telemetry no
  count cap — not fixed). No correctness bug → no code change. Refined map:
  round-trip bugs live in rich/structured stores, not flat caches. Static
  high-yield surface exhausted; frontier is the live-stack tier.
