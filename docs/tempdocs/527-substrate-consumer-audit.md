---
title: "527 — Substrate-Consumer Audit (post-510/511/517/518/519/521)"
---

# 527 — Substrate-Consumer Audit (post-510/511/517/518/519/521)

**Date**: 2026-05-19
**Status**: done
**Source**: tempdoc 512 §"Open questions for the next conversation" Q2 —
*"Is the current substrate trajectory (500s tempdocs) earning out?
Are the current 500s passing the C-018 gate (named consumer slice for
each substrate slot)?"*
**Method**: two parallel agent investigations across the substrate slots
shipped in tempdocs 510, 511, 517, 518, 519, 521. Grep + targeted reads
of consumer code. Test files excluded from consumer counts. Single-
consumer cases flagged as fragility signals.
**Out of scope**: 524 (`core-contracts` C-018) is already done with a
narrower Java-only scope; this audit complements it on the wider
frontend + lifecycle substrate. Active work on 519 (Step 9 in flight,
HEAD = `db8dc3b27`), 526 (jseval revival), and 525 (516 merge) is noted
but not interfered with.

---

## Verdict

**The substrate trajectory is broadly earning out, with three specific
hollows and one in-flight gap.**

- Tempdocs **510** (AI-Aware Shell) and **517** (search execution
  decomposition) are fully earning out — every substrate slot has at
  least one production consumer; most have 2+.
- Tempdoc **511** (aggregate surfacing) is earning out for the
  `<jf-operation>` axis (5 production surfaces), borderline for
  `<jf-resource>` (one surface only), and `normalizeOperationFromWire`
  was correctly hollowed (consolidated into strategies).
- Tempdoc **518** (inference lifecycle) is earning out for the
  TransitionRunner + InferenceRuntimeView + typed-failure axes, with
  one open compliance debt (LlamaServerOps at 1037 LOC, no ratchet
  test).
- Tempdoc **521** (plugin ecosystem) splits cleanly: the
  **orchestration substrate** (CommandRegistry, CommandPalette,
  KeybindingRegistry, EmptyStateRegistry, ShellContext, TemplateCatalog,
  StatusBarRegistry, InspectorTabRegistry) is genuinely production-
  consumed. The **plugin-facing PluginHostApi surface** is C-018-
  unnamed-pending: 9 of 14 sub-interfaces have zero callsites outside
  `HostApiImpl.ts` internal bridging. Three specific slots are hollow
  in practice despite documented wiring.
- Tempdoc **519** (head composition graph) is not yet earning out:
  status is `open`, the named typed records (SubstrateGraph,
  OrchestrationHandles, CapabilityGraph) do not exist in the codebase
  yet; AppFacadeBootstrap is larger than when 519 was written
  (2837 LOC vs 2823 cited in 512). Steps 1-3 (service extraction)
  shipped; Steps 4-9 (typed-record phase) are in flight per the recent
  commit history but not landed as the records the tempdoc names.

The strategic interpretation: the substrate that has a *core* consumer
(shell, surfaces, lifecycle) is earning out. The substrate that's
waiting for a *plugin* (PluginHostApi sub-interfaces, virtual
operations, Profiles V2 UI, host.ai streaming bridge) is structurally
correct but consumer-pending. Tempdoc 512 §F1 (speculative generality
as repeating failure mode) is not yet firing here — but it will if the
"first plugin" milestone slips indefinitely. The three named hollows
below are the load-bearing C-018 risk.

---

## Methodology

For each substrate slot:

1. Identify the slot from its tempdoc design section.
2. Grep production source for callsites (`modules/ui-web/src/` and
   `modules/app-services/` and adjacent; exclude `*.test.ts`,
   `*Test.java`, integration test directories).
3. Categorize:
   - **Production-consumed**: ≥ 1 production caller outside of the
     slot's own module / `HostApiImpl.ts` internal bridging.
   - **C-018-named-deferred**: shipped without consumer but with an
     explicit named follow-up slice on the roadmap.
   - **C-018-unnamed-pending**: shipped without consumer, no named
     slice.
   - **Hollow**: capability declared/wired but no implementation, or
     implementation present but never invoked at runtime.
4. Note single-consumer cases as fragility signals (one consumer
   means the substrate's value is uncalibrated; a regression breaks
   the only consumer).

---

## §1 Tempdoc 510 — AI-Aware Shell

| Slot | Status | Consumers | Notes |
|---|---|---|---|
| Design A — Framework capability gating (`consumes.conversationShapes` → `data-ai-available` + `ai-unavailable`) | Production-consumed | Shell.ts (rail render), Stage (mount attr), SurfaceCatalogClient.ts:87 | Healthy |
| Design B — Rail activity dot + status deck label | Production-consumed (single writer) | `UnifiedChatView.ts:1086,1117,1139,1155` calls `setAiActivity()`; rail dot consumes | **Single-consumer fragility**: only UnifiedChatView reports AI activity. Other surfaces that stream (e.g. FreeChatView, AgentView) don't. Probably fine for V1 but a regression here is invisible because nobody else exercises the API |
| Design C — `askAi()` helper | Production-consumed | `BrowseSurface.ts:463,465` (summarize + ask), `SearchSurface.ts:528` ("Ask AI" button), `selectionState.ts:172` (ask-ai-about capability) | 3 distinct surfaces — broad |
| Design C — Slice 514 typed `AskAiIntent` discriminated union | Production-consumed | Both production call-sites use `{kind: 'ask'}` / `{kind: 'summarize-doc'}` form (not legacy string) | Typed-intent shape is live |
| Design D — ConversationListStore | Production-consumed | `UnifiedChatView.ts:47,376,406` (resume, exportMarkdown); `ConversationHistory.ts:210` (history dropdown); `conversationListStore.ts:314` (branchFrom) | End-to-end wired (incl. slice 513 branching) |
| Design D — `branchFrom` | Production-consumed | `UnifiedChatView.ts:866,933` (branch button) | Live |
| Design E — `<jf-capability-pills>` | Production-consumed | `Shell.ts:1350` mounts `<jf-capability-pills>` above the stage gated by `_aiDependentIds.has(this.activeId)` | Verified 2026-05-19. Initial grep missed this because the audit agent ran a narrower search; targeted read confirmed |

---

## §2 Tempdoc 511 — Aggregate Surfacing Substrate

| Slot | Status | Consumers | Notes |
|---|---|---|---|
| `<jf-operation>` (Operation, button cell) | Production-consumed | HealthSurface (6 mount points), LibrarySurface, SettingsSurface, HelpSurface, BrainSurface | 5 production surfaces — healthy. BrainSurface comment notes wider migration still in progress for some buttons |
| `<jf-resource>` (Resource, list-item cell) | Production-consumed (single consumer) | `ActivitySurface.ts:80,84` only | **Single-consumer fragility**. Older `<jf-resource-view>` still has wider adoption; the new sanctioned path has just one surface. Not a defect yet but a calibration risk |
| `<jf-health-event>` (HealthEvent, activity-row cell) | Production-consumed | `HealthSurface.ts:1206,1209` (per 511-followup-A migration) | Single-surface as expected for the aggregate |
| `queryPrimitives.operationVisibleTo` / `resourceVisibleTo` | Production-consumed | Called by canonical strategies in `operationButton.ts:109` and `resourceListItem.ts:75` | Correct path. `operationCallableBy` was removed (`queryPrimitives.ts:7` comment) — that primitive is gone |
| Lint rule (no-restricted-imports for `api/types/registry`) | Production-consumed | `eslint.config.js:222,254` — error-level with allowlist of 5 substrate-adjacent files | The structural-prevention claim of long-term guarantee #2 is now load-bearing in CI |
| `normalizeOperationFromWire` | **Hollow (correctly retracted)** | Function removed; comment at `OperationCatalogClient.ts:21` confirms consolidation into strategies | This is the *good* hollow — a piece of speculative substrate that was decomposed into strategies and removed cleanly |

---

## §3 Tempdoc 517 — Search Execution as Decision Tree

| Slot | Status | Consumers | Notes |
|---|---|---|---|
| `SearchOrchestrator` facade | Production-consumed | `GrpcSearchService.java:158` (sole instantiator) — facade 154 LOC, was 1919 | Decomposition landed cleanly |
| `SearchPlanner` (pure function) | Production-consumed | Called internally by `SearchOrchestrator`; `SearchPlanner.java:157 → selectLegSet → LegSet → SearchExecutor` | Correct internal collaborator pattern |
| `LegSet` sealed sum (7 variants) | Production-consumed | `SearchExecutor.java:272-314` exhaustive switch | All 7 arms covered |
| `ComponentTiming` contract | Production-consumed (single emitter) | `SearchResponseBuilder.java:169,170` | Correct for the role |
| `EffectScope` | Production-consumed | Sole consumer is `SearchExecutor` (package-private — correct scoping) | Centralised effect emission |

No C-018 risk: this is pure refactor of existing production code.

---

## §4 Tempdoc 518 — Inference Lifecycle Design

| Slot | Status | Consumers | Notes |
|---|---|---|---|
| `TransitionRunner` (transition envelope) | Production-consumed | `InferenceLifecycleManager.java:89,130` (constructor); called in `switchToOnlineMode:383`, `applyConfig:717,771,774` | Live, eliminating 6× duplicated envelope |
| `InferenceRuntimeView` (typed runtime view atom) | Production-consumed | `TransitionRunner.java:56`, `AppFacadeBootstrap.java:1238,1794`, `StatusLifecycleHandler.java:260,708`, `AdminInferenceReloadHandlers.java:47` | 4 consumers — broad |
| Typed-failure routing (`InferenceFailure` sealed sum) | Production-consumed | `TransitionRunner.java:326,355-362` pattern-matches; `InferenceTelemetryEvents.java:61` routes | Old 15-value flat `Reason` enum being retired |
| API contract types in `app-api` | Production-consumed | `AppFacade.java:63,64`, `StatusResponse.java:51`, `StatusLifecycleHandler.java:64,107`, `LocalApiServer.java:529` | 4+ sites in request path |
| Module-boundary ArchUnit rule (`InferenceModuleBoundaryTest`) | Production-consumed | Live ArchUnit test; 2 documented permitted exceptions | Not `@Disabled` |
| `LlamaServerOps` class-size ratchet | **C-018-unnamed-pending** | LlamaServerOps is 1037 LOC (3.7% over 1000-LOC ceiling). No `*Ratchet*` test found in app-inference/test | Ceiling violation with no structural gate — open compliance debt |

---

## §5 Tempdoc 519 — Head Composition Graph (in flight)

Status field says `open`; Steps 1-3 (service extraction) shipped per commit history; Steps 4-9 commit messages exist but the named typed records do not exist in the codebase yet.

| Slot | Status | Consumers | Notes |
|---|---|---|---|
| `SubstrateGraph` record | **Hollow** | Not found in any production file. AppFacadeBootstrap still has 15+ individual catalog accessors | Phase 4 not landed |
| `OrchestrationHandles` record | **Hollow** | Not found. Background behaviors still inline in AppFacadeBootstrap | Phase 5 not landed |
| `CapabilityGraph` record | **Hollow** | Not unified. `WorkerCapability` and `InferenceCapability` exist as separate classes; `LifecycleProjection` combines them. AppFacadeBootstrap holds them as two fields | Precursors exist; typed record grouping doesn't |
| 8 extracted service interfaces (PolicyService, BrainRuntimeService, BrainInstallService, SettingsService, PackImportService, ExcludesService, RuntimeVariantService, DiagnosticsService) | Production-consumed | All 8 interfaces in `app-api`; consumed by dedicated handlers in `app-services/registry/operations/handlers/` (AllowlistAddDigestHandler, ReloadInferenceHandler, StartAiInstallHandler etc.) | Steps 1-3 real and consumed. Composition inversion (Section 9) has NOT landed — implementations still in `modules/ui` via `LateBoundServices` late-bind setter |
| `AppFacadeBootstrap` shrinkage | **Hollow / regressing** | Currently 2837 LOC (cited as 2823 in tempdoc 512; net +14 since audit) | Bootstrap has grown, not shrunk. Phase-typed graph hasn't materialized |

This is in-flight work and the gap between tempdoc claim and shipped state is expected mid-implementation. Flag for the next implementation slice: the typed-record phase (Steps 4-9) is the contract to land before 519 can be marked done.

---

## §6 Tempdoc 521 — Plugin Ecosystem Substrate

### §6.1 Orchestration substrate (production-consumed)

| Slot | Status | Consumers |
|---|---|---|
| CommandRegistry | Production-consumed | Shell.ts:509 (`shell.toggle-palette`), Shell.ts:606 (plugin bridge), TemplateCatalog.ts:249 (templates project to commands), VirtualOperationCatalog (projects ops), CommandPalette searches |
| CommandPalette | Production-consumed | Activated via `mod+k` + `shell.toggle-palette` command; wizard mode + 2 core empty-state contributions live |
| KeybindingRegistry | Production-consumed | `Shell.ts:522` (mod+k binding), `Shell.ts:527-528` (loadPersistedKeybindings + attachKeybindingDispatcher); plugin override path wired |
| StatusBarContribution | Production-consumed | `StatusDeck.ts:60` (core items registered via contribution axis); reader at `StatusDeck.ts:200-210` |
| InspectorTabContribution | Production-consumed | `InspectorPane.ts:52` (core tabs registered); reader at `InspectorPane.ts:106,255-257` |
| EmptyStateContribution | Production-consumed | `Shell.ts:554-596` (2 core contributions: copy-query, search-from-here); `CommandPalette.ts:19` reads |
| TemplateCatalog | Production-consumed (single template) | `Shell.ts:536-548` (one core template: `core.find-related`) — thin population |
| ShellContext + WhenExpression | Production-consumed | 13 production files use; all 5 contribution registries + KeybindingRegistry consult via `evaluateWhen` |
| Layout switching (`core.focus` zone) | Production-consumed | `Shell.ts:1161-1248` (zone visibility reads); `SettingsSurface.ts:945` (single write callsite) |

### §6.2 PluginHostApi sub-interfaces

| Sub-interface | Status | Notes |
|---|---|---|
| `registration` | Production-consumed | Shell.ts wires plugin command/keybinding registration |
| `ui.scrollSurfaceTo` | Production-consumed (single) | `AgentView.ts:100` |
| `layout.setActiveLayoutId` | Production-consumed (single) | `SettingsSurface.ts:945` |
| `navigation` | Production-consumed (partial) | `navigateForward` dep is a no-op stub — sub-slot hollow |
| `data` | Production-consumed via HostApiImpl bridge | Only HostApiImpl internal; no plugin yet |
| `discovery`, `settings`, `search`, `inspector`, `selection`, `theme`, `platform`, `utilities` | **C-018-unnamed-pending** | All 8 fully implemented; zero production callsites outside HostApiImpl. Awaiting first plugin |

### §6.3 Three named hollows (highest C-018 risk)

These are the slots where the design promises consumption but consumption is absent or stated-wired-but-bypassed.

#### Hollow 1 — `VirtualOperationCatalog` end-to-end

- `bootVirtualOperationCatalog()` is never called from production code (Shell.ts or main.jsx).
- `resolveAgentToolCall` wiring exists at `VirtualToolDispatcher.ts:48`.
- `dispatchVirtualToolCall` callsite at `AgentSessionController.ts:303-305`.
- Runtime effect: catalog is never populated → `resolveAgentToolCall` always returns null → `dispatchVirtualToolCall` always falls through to non-virtual dispatch.
- Tempdoc 521 §14.β2 live-verification claim ("agent-visible command, `vop_<name>` entry in tool list") is therefore a *test-only* result. In production, the catalog is empty.

#### Hollow 2 — `host.ai` streaming bridge

- `host.ai.streamShape`, `invokeShape`, `openSession`: zero production callsites.
- `host.ai.getSessionTranscript` / `getSessionMetadata`: `AgentView.ts:47-50` *comment* says routed through `host.ai`, but the actual code in `AgentSessionController.ts` still uses direct fetch.
- `host.ai.scrollSurfaceTo` is the only `host.ai.*` method with a production callsite — single consumer at `AgentView.ts:100`.
- Tempdoc 521 §14.ε1 marks `host.ai` as 1.0 (stable contract) — but the contract's consumption surface is essentially empty.

#### Hollow 3 — `UserStateDocument` Profiles V2

- Full data model implemented in `UserStateDocument.ts:210` (`activeProfileId` field, per-profile state isolation in themeState/userConfigState/searchFiltersState).
- `setActiveProfileId` / `createProfile` / `deleteProfile`: zero production UI callsites.
- `subscribeProfileSwitch`: zero production consumers (only test files).
- `SettingsSurface.ts` contains no profile-management section.
- The substrate is real and isolated; no surface exposes profile-switching to the user.

### §6.4 Other 521 observations

| Slot | Status | Notes |
|---|---|---|
| ContextActionContribution | C-018-unnamed-pending | Registry reader exists at `ContextMenu.ts:311`; zero registrars. `BrowseSurface.ts:455` calls `openContextMenu` with a static action list, not from the registry |
| EnvelopeStreamPool | C-018-unnamed-pending | Wired correctly via `HostApiImpl.ts:402-422`; zero production traffic (no plugin uses `host.data.subscribeResource/subscribeHealth`) |
| `setActiveSurfaceCommands` (CommandRegistry sub-slot) | C-018-unnamed-pending | Zero callers; surface-scoped commands axis is hollow |

---

## §7 Fragility signals

Six slots have exactly one production consumer:

- `setAiActivity` ← only `UnifiedChatView` (510 Design B)
- `<jf-resource>` ← only `ActivitySurface` (511)
- `ComponentTiming` ← only `SearchResponseBuilder` (517)
- `ui.scrollSurfaceTo` ← only `AgentView.ts:100` (521)
- `layout.setActiveLayoutId` ← only `SettingsSurface.ts:945` (521)
- `TemplateCatalog` registrations ← only 1 core template (521 §14.γ)

Single-consumer is not a defect per se; it is a calibration risk. The
substrate's design assumptions are validated by exactly one usage
shape. A second consumer with subtly different needs would either fit
cleanly (good design) or require widening the API (uncalibrated
generality). Until the second consumer appears, the design is
unfalsified.

---

## §8 Recommendations — concrete next slices

These are the C-018 closure or retraction decisions the audit
surfaces. Each is a defined unit of work.

### §8.1 Decide the three 521 hollows

**Hollow 1 — `VirtualOperationCatalog`**: two options.
- **A.** Boot from `main.jsx` and populate with the existing core
  operations (slice ~30 LOC). Validates the agent-tool dispatch path
  end-to-end. Closes the C-018.
- **B.** Delete `VirtualOperationCatalog` and `VirtualToolDispatcher`
  if no agent-tool plugin is planned in the next 4 weeks. Removes
  speculative substrate per the 512 §F1 discipline.

**Hollow 2 — `host.ai` streaming bridge**: two options.
- **A.** Migrate `AgentSessionController`'s streaming path to
  `host.ai.streamShape`. Closes the contract-vs-reality gap noted in
  `AgentView.ts:47-50`. Slice size: ~50 LOC + integration test.
- **B.** Remove the unused `host.ai.streamShape`/`invokeShape`/`openSession`
  methods. Mark `host.ai` contract version honestly (`0.9` not `1.0`).

**Hollow 3 — `UserStateDocument` Profiles V2**: two options.
- **A.** Add a SettingsSurface profile-management section
  (create/rename/delete/switch). Slice size: ~200 LOC. Closes the
  C-018 and gives users access to a feature already paid for in
  storage.
- **B.** Annotate as "framework-for-plugin-ecosystem" with named
  slice (e.g., "527-followup-profile-ui-when-multi-account-lands").
  Acceptable per the §X.11 refinement of C-018, but the named-slice
  must be concrete (calendar date or trigger).

Recommend **option A for all three** if open-alpha is on the calendar:
the user-visible value is real and the substrate is paid-for.
Recommend **option B / delete** if the plugin ecosystem rollout has
moved out by > 4 weeks.

### §8.2 Add `LlamaServerOps` class-size ratchet test

The class is 1037 LOC vs 1000 ceiling. Tempdoc 518 named the ratchet
but no `*Ratchet*` test exists in `modules/app-inference/src/test/`.
Add the standard ratchet test pattern (existing examples in
`gradle/class-size-exceptions.txt`). Slice size: ~30 LOC test +
exception-file entry.

### §8.3 519 — name the gap honestly

Tempdoc 519 status is `open`; commit messages claim Steps 4-9 work
landed. Reconcile: either the typed records (SubstrateGraph,
OrchestrationHandles, CapabilityGraph) are imminent (state `in-flight`
+ named target slice), or they're deferred and 519 should be split into
"Steps 1-3 shipped" + "Phase-typed graph (deferred)". Currently the
tempdoc status doesn't match the codebase state. Note: AppFacadeBootstrap
grew 14 LOC since 512 cited it; the growth is the diagnostic.

### §8.4 511 — second `<jf-resource>` consumer

The new aggregate component has only ActivitySurface as a production
consumer. Either migrate one of the existing `<jf-resource-view>`
callsites to `<jf-resource>` (calibration via a second consumer), or
acknowledge that `<jf-resource>` is structurally pre-positioned for
plugin Resource rendering and that core surfaces will continue to use
the older path.

### §8.5 PluginHostApi readiness

The 8 unconsumed sub-interfaces (discovery, settings, search,
inspector, selection, theme, platform, utilities) are correctly
implemented but waiting for a plugin. Recommend: open a tracking
item naming when the first plugin slice is planned. If no plugin slice
is planned, the C-018 risk on these slots rises with each week of
delay.

---

## §9 Open verification items

- Whether `bootVirtualOperationCatalog()` is called anywhere I missed
  (the grep was confident; verify with a wider AST search if pursuing
  Hollow 1 option A)
- Whether 519's typed-record phase is genuinely in-flight in a
  worktree I didn't audit, or genuinely deferred

---

## Cross-reference for the next conversation

- 524 (`core-contracts` C-018 audit) — narrower Java-only scope; complementary
- 525 (516-foundation merge plan) — ready to execute when prioritized
- 526 (jseval revival) — active bug-fix iteration on pre-existing eval blockers
- 519 (head-composition-graph) — Step 9 in flight as of HEAD `db8dc3b27`; this audit's §5 findings should be revisited after Steps 4-9 land
- 512 — the originating critical analysis; this audit answers Q2 of its open questions
