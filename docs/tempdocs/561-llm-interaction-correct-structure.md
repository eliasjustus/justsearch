---
title: "561 — The user-facing LLM interaction: the correct long-term structure, both tiers (one record per plane AND one interaction surface; modes & panels as governed projections of a typed surface declaration; the data-register AND the surface-register+gate that make fragmentation — of data AND of surface — unrepresentable; one window true by construction)"
type: tempdocs
status: active
created: 2026-05-30
category: frontend / llm-interaction / chat / agent / ux / design-theory / single-authority / surface / lifecycle / governance
related:
  - tempdoc 559 (the UI as a projection of a typed element catalog — the projection-ENGINE generalization this surface tier rides on; `SurfaceFactory` + `createRegistry<T>()` + the `adaptive-regions.v1.json` catalog-coverage pattern are the substrate the one interaction surface extends). The single most load-bearing sibling for THIS rewrite.
  - tempdoc 553 / 549 (canonical search-execution record → governed read-views → the `execution-surface` register+gate). The DATA-tier precedent + the PROVEN KERNEL; the gate template the surface gate mirrors.
  - tempdoc 550 (the operation & action lifecycle — ONE canonical record → governed projections). The action authority the data tier completed; History/Timeline are its projections, now to be re-mounted as panels of the one surface.
  - tempdoc 564 (contract projection — the FE↔backend boundary's correct design is the same kernel; the "kernel half-applied, not absent" diagnosis this doc mirrors for the *surface* boundary).
  - tempdoc 530 / 531 / 547 (the discipline-gate kernel + consumer-drift gate + the no-parallel-enforcement rule — the machinery the interaction-surface gate reuses).
  - tempdoc 481 (unified-substrate theory — the RegistryEntry single-authority meta-frame). The {481 × 530} binding is **P-F**; this rewrite discharges it at the surface tier.
  - slices/491-chat-substrate.md (the ConversationShape catalog + the autonomy × grounding × persistence axes — the modes are a projection of these) + 497-unified-chat-surface.md (the un-converged agent — the 4→2 fork this finally closes *structurally*, not just by composition) + 495 (AgentSessionController/AgentView — the surface-agnostic controller + the standalone surface to retire).
  - tempdoc 557 / 558 (the presentation single-authority lineage — Display / Observed-state / Theme / Layout / A11y / Messaging / Evidence / Operability / Adaptivity). The one surface composes these authorities; the panels adapt via `SurfaceLayout`/`OverflowController`.
  - tempdoc 487 / 490 / 494 (intent trust lattice + proactive emission). The autonomy axis the modes project; the presence channel a future mode/panel.
---

# 561 — The user-facing LLM interaction: the correct long-term structure, both tiers

> **What this document is.** The *correct long-term structure* for the surface where a person
> interacts with the local LLM — RAG-ask, free chat, structured extract, and the autonomous agent
> loop, treated as **one continuum**. It states the target architecture that fixes the issue *class*
> and the structural mechanism that prevents it recurring. **Feasibility is disregarded; major
> rewrites are in scope.** Its one thesis: the correct structure is **not a new framework** — it is
> the codebase's already-proven *single-authority → governed-projection → register-backed-gate*
> kernel applied to the **whole** interaction, at **two tiers**: the **data** (one record per plane —
> shipped) and the **surface** (one interaction window — the structure this rewrite centers).
>
> **What this document is not.** It makes no exact product-UX decisions (composer layout, panel
> chrome) and no implementation/phasing plan. It is the *target*, evidence-paced — a map, not a
> roadmap. The actual surface-tier build (the register, the gate, the mode/panel catalog, folding the
> tabs in, retiring `core.agent-surface`) is the work this doc *specifies*, executed in a later pass.

---

## 0. The reframe — the kernel at two tiers; the body unified but the registration didn't

The codebase has one recurring, proven kernel, named across 553/559/564/this doc's own data tier:

> **one single-authority declaration → a governed (total, lossy-downward) projection → a
> register-backed anti-drift gate whose coverage projects from the authority's own catalog** — so a
> *second* authority for the same concept is a **build failure**, not a review comment.

561's original work applied this to the interaction's **data**: there is one canonical record per
plane (the action/lifecycle record, 550; the evidence record, 553/549), every facet (thread, History,
Timeline, citations, budget) is a governed projection of it, and the `operation-surface` register+gate
makes a second *data* authority unrepresentable. **That tier shipped and is merged** (§7 + Appendix A).
It killed the original audit's defect — the agent surface reading four disagreeing data sources.

But there is a **second tier the original doc only asserted as a product decision (P-B): the
surface.** "One window for all direct LLM interaction" was ratified, and the *body* was unified — the
agent run renders inline in `UnifiedChatView` (`core.unified-chat-surface`), no plane-swap, no embedded
`<jf-agent-view>` — and C-2 made the chrome *grade* across the autonomy posture. **But the body unified
while the surface *registration* did not.** The standalone `core.agent-surface` (`AgentView`, with its
Chat / Sessions / Timeline / History tabs) is **still a registered RAIL surface**; `core.ask-surface`
and `core.browse-surface` are too. The catalog (`CorePlugin.ts` `CORE_SURFACES`) declares them as flat
peers, and **nothing in the structure forbids it** — the FE has no governance analogue of
`operation-surfaces.v1.json`. So the interaction's *surface* is exactly where the data tier was before
561: **multiple authorities for one concept, none subordinate** — 548's defect class, one altitude up.

> **The thesis (this rewrite).** "One window" must be **true by construction**, not by assertion. The
> correct structure is **one canonical interaction *surface*** whose **modes** (ask/chat/extract/agent)
> and **panels** (conversation, evidence, approvals, budget, AND the retrospective sessions/timeline/
> history) are **governed projections of a typed surface declaration**, with an **interaction-surface
> register+gate** making a *second* interaction surface a **build failure**. The data tier proved the
> kernel; the surface tier completes it — and discharges the deferred **P-F** (481×530) here.

The two surfaces are the proof the kernel has not yet been applied at the surface tier. The standalone
`core.agent-surface` is not a feature — it is the surface-tier fork.

---

## 1. The two tiers of one kernel (the thing to extend)

| Tier | Single authority | Governed projection | Register + gate | State |
|---|---|---|---|---|
| **Data** | one record per plane (action 550, evidence 553/549) | thread / History / Timeline / citations / budget | `operation-surfaces.v1.json` + the `operation-surface` gate (import-scan + positive durable-store coverage + forbidden-reintroduction) | **SHIPPED + merged** (§7, App. A) |
| **Surface** | one **interaction surface** | its **modes** + **panels** (each a projection of the data tier's records) | `interaction-surfaces.v1.json` + an `interaction-surface` gate (catalog audience×consumes scan + positive coverage + forbidden-reintroduction) | **the correct structure — unbuilt** (§2–§3) |

These are the **same kernel at two altitudes.** The data tier governs *what the interaction is made
of*; the surface tier governs *that there is exactly one place it happens.* 559 names the surface-tier
engine that already exists — **`SurfaceFactory`**, the projection engine that mints a surface's element
from its typed declaration (the shell never hand-authors `<jf-…-surface>`). 559's finding is that this
projection kernel is **half-applied** in the FE: element *membership* and *typed-value* facets are
projected from declarations, but the *rendered composition* (which modes, which panels, where) is
hand-authored. **The surface tier of the interaction is exactly that un-projected half.** Finishing it
is extending a kernel that is present, not introducing a new one.

---

## 2. The correct surface-tier structure — interaction as ONE surface, modes & panels as projections

### 2.1 One canonical interaction surface

There is exactly **one** `Surface` (`modules/ui-web/src/api/types/surface.ts`) of `audience: USER`
that hosts direct LLM interaction — call it `core.interaction-surface` (the promoted
`core.unified-chat-surface`). The `Surface` declaration is already typed and catalog-driven
(`CorePlugin.ts` `CORE_SURFACES`; minted into a `SurfaceFactory` by `SurfaceCatalogClient`); its
`consumes` graph already carries `conversationShapes`. The correct structure makes that ONE surface
the declared single authority for the interaction-shape set (`core.free-chat`, `core.rag-ask`,
`core.extract`, `core.agent-run`) — today that set is a hand-authored `ShapeId` union *inside*
`UnifiedChatView`'s source; it should be a field of the typed declaration.

### 2.2 Modes are a projection of the interaction's axes (not four surfaces, not a hardcoded union)

The continuum ask→chat→extract→agent is **one axis-cross-product, not four reasons to change.** 491's
axes — **autonomy × grounding × persistence** — are the real variables; a "mode" is a point in that
space. The system already treats them as one: C-2's `agencyPosture(affordance × dial)` + `postureChrome`
*grade* chrome across the continuum rather than *forking* by mode, and `POST /api/chat/dispatch` routes
all four by `shapeId`. The correct structure lifts the mode set out of one view's source into a
**declared mode catalog** — each entry `{ id, shapeId, axes:{autonomy,grounding,persistence}, label,
dispatch }` — and the projection engine *selects and renders the mode from the posture.* Grounding
(Documents → rag-ask, Schema → extract) and autonomy (the dial) become the *coordinates* of a single
declared space, not buttons that swap surfaces.

### 2.3 Panels/regions are declared catalog entries that re-mount governed projections

The one surface's contents — the conversation body, the evidence panel, the approvals ceremony, the
budget/lifecycle rail, **and the retrospective Sessions / Timeline / History** — become **declared
panels**, entries in an interaction-panel catalog modeled exactly on `governance/adaptive-regions.v1.json`
(which already declares `{id, registryFile, registryType, priorityField, rendererFile}` and whose gate
auto-covers each region). Each panel declares:

- `id`, `region` (body | evidence | approvals | budget | **retrospective**), placement-within-surface;
- **`consumesProjection`** — the governed data source it re-mounts. This is the load-bearing point: the
  retrospective panels are **re-mounts of already-governed projections, not new data.** Sessions ←
  `/api/chat/sessions`; **Timeline ← `ActionLedgerClient.unifiedActivity` (the data tier's P-B2 ledger
  projection)**; **History ← `/api/action-ledger` filtered by `correlationId` (P-B1)** — all three
  already flow through the **surface-agnostic** `AgentSessionController` (`loadSessions` / `loadTimeline`
  / `loadHistory`). The standalone surface's `activeTab` switch becomes the `retrospective` region of the
  one surface, and **`core.agent-surface` is retired.**

Panels register through the existing `createRegistry<T>()` primitive (the same spine backing the nine
contribution registries, e.g. `StatusBarRegistry` with its declared `priority`/`overflow`/`accessibleLabel`);
they adapt under constrained space via the `SurfaceLayout`/`OverlayHost`/`OverflowController` authorities
(557/559). **No data is re-modeled — only re-mounted.**

### 2.4 The projection engine (generalize `SurfaceFactory`)

The declaration owns *what exists, its data lineage, and its placement*; the **engine owns mounting**.
559's exact thesis is to generalize `SurfaceFactory` (today it mints *surface* elements and stamps
`shape-id`/`api-base`/`host_` at mint time, with brand/WeakSet dispatch safety) from "mint a surface
element" to "mint a **mode/panel** element." The one interaction surface is then a **total projection**:
its modes and panels are facets *of it*, dispatched by the engine — there is literally one `Surface`
entry of interaction audience, so "one window" is structural, not asserted.

---

## 3. The prevention — a second interaction surface is unrepresentable

This is the FE analogue of `operation-surfaces.v1.json`, and it is the **discharge of P-F** (the 481
single-authority frame × the 530 gate kernel) at the surface tier. Add a register
`governance/interaction-surfaces.v1.json` + an `interaction-surface` gate on the 530 kernel, mirroring
the `operation-surface` enforcer's three checks (547-clean — one gate over the surface authority, not a
duplicate enforcer):

1. **Catalog scan (the import-scan analogue).** Parse the FE surface catalog (`CorePlugin.ts`
   `CORE_SURFACES` + merged plugin contributions). The invariant: **any `audience: USER` surface whose
   `consumes` intersects the declared core interaction-shape/operation set MUST BE the canonical surface,
   or a declared mode/panel of it.** A surface like `core.agent-surface` (which consumes the agent shape)
   that is *not* the canonical surface and *not* declared as a mode/panel **fails the build** — it is, by
   definition, a second interaction authority. The gate catches it the moment it is registered.
2. **Positive coverage (the durable-store-scan analogue).** Every registered surface consuming a
   chat/agent shape must be *classified* — the canonical surface, a declared mode, a declared panel, or on
   an explicit `unrelatedInteractionSurfaces` allowlist (`AGENT`-audience headless tools, `DEVELOPER`
   debug surfaces). So a new interaction surface cannot be added without a human deciding *which* it is.
3. **Forbidden reintroduction (the named-fork backstop).** Named patterns for the exact fork vocabularies
   — a new standalone `*ChatView`/`*AgentView`/`*InteractionSurface` registered as a RAIL surface — the
   surface-tier mirror of the data tier's `(InteractionLog|ThreadStore|…)` denylist.

The **one genuinely new mechanism** (named honestly): unlike the data gate's *import grep*, this gate
must **analyze the surface catalog's `audience × consumes` graph** — a catalog-shape analyzer over a
statically-declared TS object literal, not a text scan. That is the only new capability; everything else
is the proven enforcer retargeted.

---

## 4. Extend, don't invent — the substrate already exists

| Need | Existing piece (reuse / extend) |
|---|---|
| The typed surface declaration | `Surface` + `SurfaceConsumes.conversationShapes` (`api/types/surface.ts`) — extend with `modes` + `panels` (or a sibling `interaction-panels.v1.json`). |
| The projection engine | `SurfaceFactory` + `mintFactory`/`mountSurface` + brand/WeakSet (`api/registry/SurfaceCatalogClient.ts`) — generalize from surface-element to mode/panel-element (559's exact move). |
| The catalog spine | `createRegistry<T>()` (`shell-v0/primitives/registry.ts`) — `InteractionModeRegistry` / `InteractionPanelRegistry` are one-liners, identical to `StatusBarRegistry`. |
| The coverage-from-catalog pattern | `governance/adaptive-regions.v1.json` + `check-adaptive-closure.mjs` (declare a region → the gate auto-covers it) — the structural template for `interaction-panels.v1.json`. |
| The gate template | `scripts/governance/gates/operation-surface/enforcer.mjs` + `operation-surfaces.v1.json` — the three-check skeleton the interaction-surface gate clones. |
| The retrospective data (re-mount, no re-model) | `AgentSessionController` (surface-agnostic `loadSessions`/`loadTimeline`/`loadHistory`), already projecting the 550/561 ledger. |
| The mode-grading signal | C-2's `agencyPosture`/`postureChrome` + the autonomy dial (already shipped). |

**The kernel is the design. The rest is "apply it to the surface."**

---

## 5. Honest ceilings and out-of-scope

- **The §6 undeclared-fork residue is *identical* to the data tier — no stronger.** A surface that
  re-implements interaction with entirely new component names, its own SSE client, and *no* consumption
  of declared interaction shapes is **catalog-invisible** to checks 1–2; only the named denylist (check 3)
  + review catch it. Single-authority×gate raises the cost of forking to "deliberately evade three checks
  + the denylist," it does not make forking metaphysically impossible (mirrors 553 §5 / the data tier's
  honest limit). State it plainly; don't claim the title literally where the mechanism can't reach.
- **`extract` is the weakest member of the continuum.** Structured extraction has a distinct output
  contract (JSON-Schema, preview/validation lifecycle) and could plausibly evolve independently. The
  theory holds because extract is a **mode** (a declared facet with its own panel), not a **surface** —
  unify at the *surface* tier, keep modes independently evolvable at the *mode/panel* tier. That is the
  correct altitude (AHA: the surfaces share a reason to change; the modes may diverge).
- **Plugin-owned novel interaction must NOT be blocked.** The gate fences the **core** interaction-shape
  set only. A plugin that defines its *own* namespaced shapes (`vendor.x.voice-chat`) is the plugin's own
  single-authority concern and is allowed; a plugin that *consumes core* chat/agent shapes outside the one
  surface is correctly blocked (it should contribute a mode/panel). The line the gate draws — core-shape
  consumption — is principled but is not a fence around *all* conceivable LLM interaction.
- **The retrospective console is a panel/placement choice, not a surface.** A power-user "tabbed agent
  console" is a real want — but it is a `retrospective` region (optionally `splitPairing`-paired into a
  STAGE split), not a second surface. Retiring `core.agent-surface` must **preserve its keyboard/region
  navigation** (the `Alt+1..4` tab affordances) as region-navigation within the one surface, or the
  structural win ships a UX regression.
- **Out of scope by design (named, not gaps):** P-E *device-level* modality (voice / spatial / AV render
  targets — new mode/panel render targets, no new surface); P-D scheduled/presence as a *future mode*;
  the *graded continuum within one continuous body* (C-2 grades chrome; the deeper "evidence physically
  morphs into approvals" is a mode-render refinement, not a surface-structure question).

---

## 6. Relationship to the kernel siblings

| Authority / doc | Relationship |
|---|---|
| **559** UI-as-projection-of-a-typed-element-catalog | The projection-ENGINE generalization this surface tier rides on. `SurfaceFactory` + the registries + the catalog-coverage gate are the substrate the one interaction surface extends. |
| **553 / 549** execution-surface register/gate | The data-tier precedent + the gate template the surface gate mirrors. |
| **550** action/lifecycle record | The record whose History/Timeline projections become the retrospective panels. |
| **564** contract projection | The same kernel at the FE↔backend contract boundary; co-diagnosis ("half-applied, not absent"). |
| **530 / 531 / 547** discipline-gate kernel | The machinery the interaction-surface gate reuses; one gate over the surface authority (no parallel enforcement). |
| **481** single-authority frame | **P-F**: the {481 × 530} binding this rewrite discharges at the surface tier. |
| **491 / 497 / 495** chat substrate + the un-converged agent | The modes are a projection of 491's axes; 497's 4→2 fork is finally closed *structurally* (one surface), not just by composition; 495's `AgentSessionController` is the surface-agnostic controller the panels re-mount, and `AgentView`/`core.agent-surface` is the surface to retire. |

---

## 7. The data tier — the proven foundation (condensed; full record in Appendix A)

The original 561 (2026-05-30 → 2026-06-03) applied the kernel to the interaction's **data** and shipped
it to `main`: one canonical record per plane (the agent loop's `AgentRunStore`; the answer plane's
`ConversationStore`/`ContextCitation`; the cross-domain `ActionEvent` ledger), every facet a governed
projection joined by `conversationId`/`correlationId`, the agent-plane double-write eliminated (the
ledger's agent rows project the one `AgentRunStore`), and the **`operation-surface` register+gate**
extended to make a second *data* authority a build failure (incl. a positive durable-store gate and an
import-scan over the canonical types). Autonomy collapsed to one backend issuance authority
(`IntentGateEvaluator.agentGate`), with the C-4 floor (irreversible MEDIUM writes confirm even in Auto);
learning/memory got one `MemoryStore` + producers; evidence is on the record (live == reload). All of it
is unit-tested and **live-verified end-to-end in the browser with the GPU model** (the build log is
Appendix A). **This tier is done.** What it did *not* do is govern the *surface* — which is §0–§6.

---

## 8. De-risk pass (2026-06-03) — firsthand verification of the surface-tier claims; corrections + confidence

Before any surface-tier build, the §0–§6 claims (which rested on subagent exploration — hypotheses, per
`audit-without-test`) were verified firsthand: direct code reads + a **live probe of the served surface
registry** (`GET /api/registry/surfaces` on a running stack). **Two claims were over-stated and are
corrected; one new structural finding emerged. Net: the *lighter* path is more feasible than §4's
"extend" framing implied, the *prevention* is writable today, and the fragmentation to collapse is
*broader* than the standalone agent surface.**

- **U1 — the projection ENGINE is BUILD, not EXTEND (corrects §4).** §4 implied 559's element-catalog
  kernel is reusable substrate. Firsthand: **559 is unbuilt design theory**, and there is **no
  intra-surface composition engine** — `SurfaceFactory` mints *top-level surfaces only*; every surface's
  interior is hand-authored Lit (`AgentView`'s 4-tab `activeTab` switch; `InspectorPane`'s tabs are a
  per-entry hand-authored `render()` callback, not engine-projected; the `Shell.ts` split-pane is
  hand-authored). So "extend the engine" would mean *building 559's whole thesis* (large, structural).
  **BUT** the records/projections and the **surface-agnostic `AgentSessionController`** are genuinely
  reusable, and the retrospective renders (`renderSessionsTab/Timeline/History`) are **small,
  self-contained functions** reading the shared controller + shared utils, coupled only to a few scoped
  CSS classes. → **Fork resolved: one window is achievable by a *bounded composition refactor*** (extract
  the panel renders + a shared style module, mount them in `UnifiedChatView` via the shared controller);
  **the full 559 engine is NOT a prerequisite — it is a separate, larger 559 concern, deferred.** §4's
  "extend, don't invent" is *half-true*: extend the data/projections/controller; the *composition* is a
  bounded build, not an extension.

- **U2 — the prevention gate IS writable today (resolves §3's open question).** Firsthand + the live
  probe: **the served `/api/registry/surfaces` carries `audience` + `consumes.conversationShapes` for
  every surface** (sourced from the *statically-declared* Java `CoreSurfaceCatalog`), e.g.
  `core.agent-surface {USER, [core.agent-run]}`, `core.unified-chat-surface {USER, [free-chat,rag-ask,
  extract]}`. → **The gate targets the backend catalog (static Java) / the served registry — no
  FE-declaration-sync prerequisite for core surfaces** (the FE `CorePlugin.ts` omitting
  `conversationShapes` is irrelevant; the backend is the authority). **Ceiling (= the §5 residue):**
  `PluginSurfaceContribution` cannot declare `conversationShapes`, so a *plugin* interaction surface is
  gate-invisible — the same undeclared-fork limit the data tier has.

- **NEW — the fragmentation is BROADER than `core.agent-surface` (the probe's surprise).** The served
  registry shows the interaction shapes are **multi-hosted**: the unified RAIL surface hosts
  `[free-chat,rag-ask,extract]`, `core.agent-surface` hosts `[agent-run]`, **and** separate **DEEPLINK**
  per-shape surfaces exist (`core.ask-surface` [rag-ask], `core.free-chat-surface` [free-chat],
  `core.extract-surface` [extract]), plus `core.browse-surface`/`core.workflow-surface`. So the *same*
  shape is hosted by multiple surfaces. → **The gate's invariant must key on PLACEMENT, not just shape:**
  there is **one RAIL/STAGE *visible* interaction window**; `DEEPLINK` surfaces are *routes into it*
  (entry points), not second windows. This refines §3 from "one surface per interaction shape" to
  "**one visible (RAIL/STAGE) interaction window; deeplinks route into it; no second visible interaction
  surface.**"

- **U4 — modes are declaratively *selected* but idiosyncratically *dispatched* (refines §2.2).**
  `resolveShape` already maps affordance/selection → shapeId (the selection is declarative), but
  `buildDispatchBody`'s `switch(shapeId)` shows each mode builds a *different* request body (`extract`
  carries a schema; `rag-ask` carries docIds; `agent-run` routes to the controller). → A mode is **not**
  a closed-form axis cross-product; the mode catalog declares the axes + a **per-mode dispatch hook**.
  §2.2 holds for selection/chrome; the dispatch is per-mode.

- **U5 — retiring `core.agent-surface` is bounded.** The load-bearing coupling is `AgentSurface.ts:55`
  `registerViewFactory('core.agent-run','jf-agent-surface')` — the agent-run *shape* currently mounts the
  standalone surface; retiring it = **re-point that factory to the one window** (which already hosts the
  agent inline, so it is consistent). Plus the `shell-v0/index.ts` import, two label/icon maps
  (`deriveRichLabel`/`surfaceIcons`), and 3 tests. The deeplink per-shape surfaces (U2-new) reconcile the
  same way — route into the one window.

**Per-claim confidence after this pass:** one canonical window via **bounded composition** — **HIGH**
(shared data/controller; small renders; the engine is not required). The **prevention gate** — **HIGH**
(the served/Java catalog carries audience×shapes; the gate is a near-clone of the `operation-surface`
enforcer, keyed on placement×audience×consumes). **Modes** — **MED-HIGH** (declarative selection + a
per-mode dispatch hook). **Retire + reconcile the extra surfaces** — **MED-HIGH** (bounded, but broader
than expected: agent-surface + 3 deeplinks + the view-factory re-point). **The full 559 composition
engine** — **deferred to 559** (the honest down-scope of §4). **Residuals:** the plugin-surface gate
ceiling (= §5); the spatial composition of the retrospective panels (drawer vs STAGE split) is a
product-UX choice, not a structural blocker.

**Net:** the surface-tier *structure* (§0–§6) is sound and the *prevention* is writable today; the one
real correction is that the *composition* is a **bounded refactor on the reusable data/controller**, NOT
an extension of an unbuilt engine — and the invariant is **placement-keyed** because the fragmentation is
broader than the standalone agent surface. Confidence to implement the one window (lighter path) + the
gate: **HIGH**; the full 559 projection engine remains 559's separate concern.

---

## Appendix A — the data-tier implementation record (condensed; full narrative in git before the 2026-06-03 rewrite)

The pre-rewrite doc carried the full chronological build of the data tier — §1 the kernel, §2 the two
planes (answer/action), §4 the six pillars (P-A..P-F), §5 the projection contract, §9/§10 de-risk passes,
§11 the InteractionLog-fork correction, §12/§13 the implementation status + live verification, §14
thesis-gap closure, §15/§16 merge readiness + rehearsal, §17 the live end-to-end verification, §18 the
fresh-takeover critical review, §19 the firsthand re-verification of §18, §20 the implementation pass
that built the §18.3 ledger (C-4 / #5 / #4 / #6 / C-1 / C-2) live-verified in the browser, plus
Appendices A (the live audit), B (the competitor field study), C/D/E (build logs), F (the de-risk pass).
**That full narrative is preserved in this file's git history before the 2026-06-03 rewrite** and is the
authoritative record of what shipped at the data tier. The load-bearing carry-forwards for the surface
tier are: (1) the History/Timeline projections governed by `operation-surfaces.v1.json` are exactly the
panels §2.3 re-mounts; (2) `AgentSessionController` is surface-agnostic; (3) C-2's `agencyPosture` is the
mode-grading signal; (4) **P-F was explicitly deferred** — and §3 discharges it.

### Carry-forward follow-ups (logged in `docs/observations.md`)
- The autonomy because-line shows the dial fallback ("…run automatically") on a pending card whose actual
  gate is `typed_confirm` — C-4 exposed a latent P-D1 wiring gap (the pending card's `gateBehavior` isn't
  populated). A mode/panel render refinement once the surface tier lands.
- The `SessionListItem` time/status field-name mismatch (`startedAtEpochMs`/`status` vs the backend's
  `startedAt`/`state`) — resolves when the Sessions panel is re-mounted under the one surface.
