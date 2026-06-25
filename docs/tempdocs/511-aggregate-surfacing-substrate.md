---
title: "511 — Aggregate Surfacing Substrate"
---

# 511 — Aggregate Surfacing Substrate

**Date**: 2026-05-18
**Status**: done

## Status-at-a-glance

| Slice | Status | Closure |
|---|---|---|
| Substrate (initial) — Phases 0-10 | shipped | §Implementation status (initial) |
| 511-followup — Tracks A-F | shipped | §511-followup |
| 511-followup-2 — Tracks AA-FF | shipped | §511-followup-2 |
| 511-investigation — Tier 1-4 cost/reality pass | shipped | §511-investigation |
| 511-followup-A — HealthEvent aggregate migration | shipped + live-verified | §511-followup-A |
| 511-followup-B — Lint rule rollout | shipped + lint-verified | §511-followup-B |
| 511-followup-C — Surface migration closure (HelpSurface + SettingsSurface) | shipped + live-verified | §511-followup-C |
| 511-indirect — 9 post-shipping observations | spike-verified | §511-indirect |
| 511-followup-D — §511-indirect items #1, #2, #3, #5, #6, #7, #8 | shipped + live-verified | §511-followup-D |
| #4 audience-hint UX | closed (defer indefinitely; user decision) | §511-indirect Spike D |
| #9 capability-gating substrate | deferred (YAGNI; no consumer demands it) | §511-indirect, §511-followup-D rationale |
| 511-followup-D-patches — registry dedup + prefix regex + op-success live-verify | shipped + live-verified | §511-followup-D-patches |
| 511-future-directions — research-backed ideas catalog | research only (no code) | §511-future-directions |
**Supersedes**: the earlier `511-wire-field-surfacing.md` framing,
which decomposed the problem at the wrong unit (field-level renderer)
and is retracted in §"Why the obvious framing is wrong" below.
**Related**: 509 (operation label coherence — catalog-resolution-by-id
pattern that this generalizes), 508 §4 (UI slot system — orthogonal
contribution scaffold), 504 (defect classes D1 retroactive and D3
directly), 421 framework kernel (existing renderer registries this
design integrates with).

---

## Problem

Wire-emitted aggregates carry metadata the backend intends to drive
visual treatment, but surfaces consume that metadata incoherently —
or not at all. The same observable defects the previous draft listed
still hold:

- `Operation.policy.risk` is HIGH/MEDIUM/LOW on the wire. Op buttons
  in HealthSurface, BrainSurface, and elsewhere render without color,
  border, or icon differentiation, because the surface uses raw
  `<button>` instead of `<jf-action-button>` (which *does* read risk).
- `Operation.executors` is `Set<{UI, AGENT, CLI}>`. The command
  palette cannot filter "show me agent-callable operations" because
  no surface or query primitive reads the field.
- `Resource.itemOperations[i]` resolves to operations whose
  `audience` is `USER` or `OPERATOR`. Surfaces render operator-only
  actions adjacent to user actions with no visual distinction and no
  visibility gate.
- `Operation.provenance.transport` is BUTTON/URL_BAR/PALETTE/RAIL/
  AGENT on `HealthEvent.provenance`. Activity tables render it as
  plain text although `<jf-dispatch-source>` exists and would render
  it as an iconified badge.
- `OperationInvocation.confirmStrategy` is None/Inline/Typed. Buttons
  that the catalog declares as requiring typed confirmation are
  clickable like any other when the surface bypasses
  `<jf-action-button>`.
- `Operation.lineage.affects: ResourceRef[]` lands on the wire (post
  447 §X.11.5 Phase 3) but is not yet consumed anywhere — a fresh
  C-018-shaped instance shipped on `main` because no consumer slice
  has landed.

These defects are tempdoc 504's class **D3** (metadata-not-surfaced)
with 5 specific F-IDs (F-9, F-10, F-23, F-24, F-26), plus the
retroactive half of **D1** (substrate-without-consumer) for fields
already on the wire with no consumer slice planned.

The previous framing of 511 ("wire field surfacing — declare a
renderer per field; consumers query a registry by field name") was
written to close this class structurally. After investigation it
proves to attack the problem at the wrong unit. The corrected
framing follows.

---

## Why the obvious framing is wrong

The decomposition "wire field → renderer" sounds clean: each field
has a presentation policy, each policy declares a renderer, surfaces
query by field name. It generalizes 509's `<jf-op-button>` pattern
from one field (label) to N fields (risk, audience, transport, …).
But it fails on four axes:

1. **Visual composition is not field-decomposable.** A button's
   color, ceremony, visibility gate, and badge strip are not five
   independent decisions overlaid in pixel space. They are one
   cohesive rendering. A `Operation` with `risk=HIGH` and
   `confirm=Typed` is rendered as a red button with a typed-
   confirmation modal — not as a red button on top of a typed-
   modal-icon badge. Per-field renderers force surfaces to either
   accept visual stacking chaos or write their own composer per
   surface — which reintroduces the original problem.

2. **Surfaces still pick which fields to render.** A field-renderer
   registry is queried by callers. The caller decides which fields
   to query. The original failure mode ("the field exists on the
   wire, the surface ignores it") survives unchanged — the registry
   is queried only for fields the surface already knows to ask
   about. No structural mechanism prevents a surface from ignoring
   a newly-added wire field.

3. **Plugins extend on the wrong axis.** "Plugin contributes a
   renderer for field X" is rarely what plugins want. Plugins want
   "render this aggregate differently in *my* surface context" or
   "add a new surface context where this aggregate appears." Field-
   axis extension does not map cleanly to either.

4. **No type-system-enforced exhaustiveness.** A field-renderer
   registry is keyed by string field names. TypeScript cannot
   verify that every wire field on an aggregate has a registered
   consumer — there is no point in the source code where the full
   set of wire fields is closed over with exhaustiveness checks.
   The structural-prevention goal collapses to a runtime check at
   best.

The correct unit is one level up.

---

## What already exists (load-bearing inventory)

Before proposing new substrate, the design must account for what's
already shipped. Investigation surfaces the following primitives.
The next agent picking this up should not re-discover these.

### Renderer registries (FE)

- **JSON Forms tester-rank registry** at
  `modules/ui-web/src/shell-v0/renderers/registry.ts:94-117`.
  Generic pattern: `(tester: (uischema, schema) => RendererRank,
  tag: string)` pairs in a lazy array; highest rank wins; plugin-
  extensible. Used by `JsonFormsRendererBase` (line 89-150). Scope
  is operation *inputs* — JSON-schema-shaped form controls. Not
  generalized to wire-aggregate output rendering.
- **Resource-view registry** at
  `modules/ui-web/src/shell-v0/renderers/resourceRegistry.ts:128-149`.
  Parallel system: `{ category, hint?, density?, rank, tag }`
  entries; dispatch by `{ category, hint, density }` query;
  specificity tiebreaker; plugin-extensible via
  `registerResourceRenderer()` (line 98-108). Scope is *whole-
  resource* rendering. Closest existing relative to the proposal
  here.
- **Renderer contract spec** lives in 421 framework kernel
  slice 3a.1.5 (Renderer Contract Spec) and slice 3a.1.9 (Resource-
  view renderer contract). Properties bundles are Category-typed
  (`StateRendererProps`, `EventStreamRendererProps`,
  `TabularRendererProps`, …).

### Catalog-resolution-by-id pattern (509)

- **Java-side catalog**: `CoreOperationCatalog.java:37-48` (in
  `modules/app-agent-api/.../catalog/`). Operation record carries
  presentation, intf, policy, availability, lineage, binding,
  provenance, executors, audience, consumers.
- **Wire emitter**: `UIOperationEmitter.java:49-150` (in
  `modules/app-services/.../emit/`). Post-447 §X.11.5 Phase 3 fix,
  serializes all 11 metadata fields including the previously elided
  `availability` and `lineage`. Pass-8 wire-emitter check
  (`docs/reference/contributing/slice-execution.md:1003-1012`)
  enforces this discipline going forward.
- **FE-side resolution**: `OperationCatalogClient.ts:74-151` —
  `bootOperationRegistry()` fetches from `/api/registry/operations`
  into an `entriesById` map with broadcast.
- **Consumption today**: `RowActions.ts:1-42` (in `shell-v0/components/`)
  resolves operation by ID, reads `presentation.labelKey` +
  `policy.risk`, dispatches into `<jf-action-button>`. This is the
  *only* surface today that consistently flows catalog → button.
  HealthSurface, BrainSurface, and others use raw `<button>`.

### Aggregate-aware components (FE)

- **`<jf-action-button>`** at
  `modules/ui-web/src/shell-v0/components/ActionButton.ts:41-214` —
  implements risk-driven UX ceremony (LOW → immediate fire;
  MEDIUM → "Are you sure?" prompt; HIGH → typed-confirmation input
  matching the operation id). Component is real and shipping. Two
  load-bearing gaps surface on inspection (see §Critique entries
  added 2026-05-18 verification): (a) it ignores the wire's
  `policy.confirm` field entirely and derives ceremony from
  `risk` alone — so a wire shape `{risk: 'LOW', confirm: {type:
  'typed'}}` fires immediately with no ceremony; (b) the CSS
  selectors `[data-risk='HIGH']` etc. are uppercase, but the live
  wire payload emits `risk: "high"` lowercase
  (`UIOperationEmitter.java:77` calls `.toLowerCase()`), so risk
  styling silently no-ops when the value flows directly from the
  catalog. Both are pre-existing defects, not introduced by this
  design, but the design must account for them: the canonical
  `(Operation, button)` strategy needs to reconcile the wire shape
  with the component's prop expectations, not assume the
  component already does.
- **`<jf-dispatch-source>`** at
  `modules/ui-web/src/shell-v0/components/DispatchSource.ts:26-120` —
  consumes a `DispatchSourceData` (transport / executor /
  initiator / occurredAt) and renders compact (icon + label) or
  detailed (icon + label + executor + initiator + relative time)
  via `transportChrome()` (`shell-v0/components/TransportChrome.ts`).
  Real and complete. Shape matches the wire's `InvocationProvenance`
  payload directly, so the `(Operation, history-entry)` and
  `(HealthEvent, activity-row)` strategies can mount this
  component without normalization layers.
- **`<jf-row-actions>`** at
  `modules/ui-web/src/shell-v0/components/RowActions.ts` — the only
  catalog-driven row-level operation dispatcher today.

So *some* of the "renderers" the previous draft proposed to add
already ship — `<jf-dispatch-source>` is complete; `<jf-action-
button>` is structurally present but has the two defects above.
The originally claimed `<jf-provenance-badge>` (wire-provenance
renderer) **does not exist** — the file at
`shell-v0/components/ProvenanceBadge.ts` is a slice 471 user-
config-override indicator (a chip showing how many surface/layout
overrides are active in the runtime userConfig), unrelated to
wire provenance. The defect is partly "no renderer exists" and
partly "the renderers that do exist don't actually consume the
wire shape correctly." The design must own both.

### Java-side rendering hints

- **`UIHint` sealed interface** (modules/app-agent-api). Five
  variants: MultiSelect, Autocomplete, FilePicker, Slider,
  CodeEditor. Per-input rendering hint, declared on
  `Interface.uiHints: Map<String, UIHint>`. The backend declares
  the hint; `UIOperationEmitter.java:68-72` serializes it to the
  wire. **No FE code currently reads it** — `grep "uiHints"` in
  `modules/ui-web/src/` returns zero matches (verified 2026-05-18).
  The hint round-trips on the wire but no consumer dispatcher
  exists. This is a precursor of the same defect class this
  tempdoc describes (substrate without consumer), not the symmetric
  precedent the earlier draft claimed it to be. Useful as evidence
  that the structural problem is older and broader than
  Operation/Resource/HealthEvent metadata.

### Plugin contribution model

- **`PluginContribution`** record at
  `modules/ui-web/src/shell-v0/plugin-api/plugin-types.ts:204-307`.
  Axis-oriented: `customElements`, `surfacePorts`, `translations`,
  `surfaceContributions`, `resourceContributions`, `recoveryOverlays`.
  Atomic install/uninstall via merge functions (per slice 447
  follow-up polish). No "renderer" axis, no "aggregate" axis.
- **`PluginRegistry.install()`** at
  `modules/ui-web/src/shell-v0/plugin-api/PluginRegistry.ts:109-150`.
  Calls the plugin's `register(hostApi)` hook; merges the returned
  contribution.

### `plugin-ecosystem-substrate` §4 status

- **Not landed**. `docs/tempdocs/521-plugin-ecosystem-substrate.md:205-269`
  (the `plugin-ecosystem-substrate` tempdoc) describes UI Slot System
  as Phase C. StatusDeck (`StatusDeck.ts:31-36`)
  and InspectorPane (`InspectorPane.ts:44-70`) are still hardcoded
  arrays. The §4 design proposes contribution registries for
  StatusBar, InspectorTabs, ContextActions. That work is
  orthogonal to and complementary with this tempdoc: §4 decides
  *where* aggregates render; this tempdoc decides *how*.

### Pass-8 wire-emitter check (backend half)

- **Location**: `docs/reference/contributing/slice-execution.md:1003-1012`.
- **Discipline**: every newly-added record component on a wire-
  emitted type must be serialized by the production emitter. Caught
  the §X.11.5 Phase 3 elision (`availability` + `lineage` declared
  in the schema, manually dropped by `UIOperationEmitter.toUIEntry`,
  fixed in `9c89d5b7f`).
- **What it doesn't do**: nothing equivalent exists on the FE side.
  A field that ships on the wire correctly but is never read by any
  surface, component, or query primitive is invisible to all
  existing gates.

### 504 D-class summary

The earlier draft referenced tempdoc 504 and D-class taxonomy
(D1–D6) with five specific F-IDs (F-9/10/23/24/26). On
verification 2026-05-18, **no tempdoc 504 exists** in
`docs/tempdocs/` (glob `504*` returns zero results). The D1/D2/
D3/D4 labels and F-IDs appear to have been informal references
from prior conversations rather than catalogued items. The
defect *classes* are still real and useful as informal taxonomy:

- **D1** (substrate-without-consumer): closed reactively by Pass-8
  component-callsite check at PR time. Does not retroactively scan
  the existing wire surface for un-consumed fields.
- **D2** (same-capability-different-name): closed structurally by
  509 (operation label coherence).
- **D3** (metadata-not-surfaced): subject of this tempdoc.
- **D4** (missing-state-narrative): partially closed by 510 for AI.
- **D5** (summary contradicts detail): one-off; not a class.
- **D6** (internal state leaked): small class, separate scope.

The implementation slice picking this design up should either
locate the canonical defect-class document if one is added later,
or treat the D-class names as descriptive labels for the four
known structural patterns rather than as references to a specific
tempdoc.

---

## Design — Aggregate Surfacing Substrate

The corrected unit is **(WireAggregate, SurfaceContext)**: a typed
pair that names what is being rendered and where. The substrate is
a registry keyed by this pair, populated by canonical strategies
that close over the full aggregate type with TypeScript
exhaustiveness. Surfaces never destructure wire records; surfaces
mount aggregate-aware components which dispatch through the
registry.

### Core concepts

#### 1. WireAggregateKind — first-class enumeration

The wire-emitted aggregates the FE can render are an explicit,
named set: `Operation`, `Resource`, `HealthEvent`,
`OperationInvocation`, `AgentSession`, `AdvisoryClass`,
`ConditionState`, `PluginManifest`, and any future addition. Each
is a kind in the registry. Adding a new aggregate is a substrate
change (one place to enumerate). The set is closed at compile
time and TypeScript can exhaustiveness-check it.

#### 2. SurfaceContextKind — named substrate

The places where aggregates can be rendered are also enumerated:

- `button` — primary affordance, full ceremony
- `palette-row` — command-palette result line
- `history-entry` — operation history list item
- `activity-row` — health/event activity table row
- `notification-toast` — transient advisory surface
- `inline-mention` — embedded reference inside other text
- `inspector-tab` — full detail panel
- `list-item` — generic listing row
- `table-cell` — compact tabular rendering
- `badge-strip` — chip / pill ensemble

The set is open in the sense that plugins extend it via TypeScript
module augmentation; closed in the sense that every contribution
names a specific context, never "render this somewhere." A new
context is a substrate change: declaring it forces aggregate
authors to either provide a context-specific strategy or accept a
documented fallback strategy.

#### 3. Canonical strategy per (WireAggregateKind, SurfaceContextKind)

For each pair, exactly one canonical strategy is owned centrally
(in the FE framework module, not in plugin code). The strategy
signature:

```typescript
type AggregateStrategy<K extends WireAggregateKind, C extends SurfaceContextKind> =
  (aggregate: AggregateOf<K>, ctx: SurfaceContextOf<C>, host: HostApi)
    => TemplateResult;
```

The strategy *closes over* the full aggregate type. Adding a wire
field to `Operation` adds a property to `AggregateOf<'Operation'>`;
TypeScript's exhaustiveness check forces the canonical strategy
to consume, explicitly elide, or route the new field before the
build passes.

**Type-shape preconditions (verified 2026-05-18).** Whether TS
exhaustiveness actually catches anything depends on how the wire
type is declared on the FE. The current state is uneven:

- **Resource** (`modules/ui-web/src/api/types/registry.ts:182-215`)
  is hand-written with every field required. A
  `satisfies Record<keyof Resource, true>` pattern or destructure-
  rest-must-be-empty check works as-is. TS will fail a strategy
  that omits a Resource field.
- **Operation** (`modules/ui-web/src/api/types/registry.ts:258-272`)
  is hand-written but **explicitly opts out of exhaustiveness**:
  it declares only id / presentation / interface / policy /
  provenance and then has a `[key: string]: unknown` index
  signature with comment "Pass-through for fields the FE doesn't
  consume but should preserve." Audience, executors, lineage,
  availability, consumers are not on the FE type at all — they
  round-trip via the index signature. Prerequisite for the design:
  remove the index signature and add every metadata field
  explicitly. This is a meaningful surgical change to a hand-
  written type, not free.
- **HealthEvent** (`modules/ui-web/src/api/generated/wire-types.ts:11-18`)
  is *generated* by `WireTypesTsGenerationTest`; every field is
  marked optional (`id?: string`, `severity?: Severity`, etc.).
  TS exhaustiveness over optional-everywhere types is awkward
  (the "missing" branch is just `undefined`). Two paths: change
  schema generation to emit fields as required where the Java
  record makes them non-null (a separate substrate change), or
  fall back to script-based enforcement for generated types.

So this point delivers structural prevention via TS for hand-
written aggregate types that drop their pass-through escape
hatches; generated-type aggregates need either schema-generation
work or a parallel script-based check. The unified mechanism is
the §6 FE-side Pass-8 mirror, which can run as either form.

#### 4. Aggregate components — single sanctioned consumption point

For each WireAggregateKind there is exactly one web component:

- `<jf-operation context="button|palette-row|history-entry|…" id="…">`
- `<jf-resource context="…" id="…">`
- `<jf-health-event context="…" event="…">`
- …

These components are the *only* surface-authoring entry points.
They resolve the aggregate (by id from the relevant catalog, or
take it inline), dispatch into the registry, render the result.

Direct destructuring of wire-aggregate records outside the
canonical strategies is banned by lint rule (or by making the wire
types opaque outside the framework module — see §Implementation
notes deferred).

#### 5. Non-rendering field-consumer axis

Some wire fields don't drive rendering — they drive filters, sorts,
grouping, permission gates. Examples: `Operation.audience` gates
palette visibility for operator-only ops; `Operation.executors`
filters palette by callable channel; `Resource.category` groups
list items; `Operation.lineage.affects` powers "what does this
button affect" tooltips.

The substrate publishes typed query primitives per aggregate:

```typescript
const operationQuery = {
  visibleTo: (op: Operation, audience: AudienceFilter) => boolean,
  callableBy: (op: Operation, executor: ExecutorKind) => boolean,
  affectsResource: (op: Operation, ref: ResourceRef) => boolean,
  // …
};
```

Surfaces import these. Surfaces never write
`if (op.audience === 'OPERATOR') …` inline — that destructures the
wire type. The exhaustiveness check on the query namespace runs
the same way as on the canonical strategy: every wire field is
either consumed by the rendering strategy, by a query primitive,
or explicitly elided.

#### 6. FE-side Pass-8 mirror

A static check, symmetric to the existing wire-emitter check:

> For every record component on a wire-emitted type, the FE-side
> canonical rendering strategy(s) and / or query primitive(s) for
> that aggregate must reference the component, or it must carry an
> explicit `@FieldUse(None | RoutingOnly)` annotation.

**The existing backend Pass-8 check is implemented as a per-emitter
unit test, not a CI script.** Verified 2026-05-18:
`modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationEmitterTest.java`
is the canonical enforcement for `UIOperationEmitter`. The
discipline lives in `docs/reference/contributing/slice-execution.md:1003-1012`
but the load-bearing test is per-emitter, written when the emitter
ships. The FE-side mirror should follow the same form: a
**per-canonical-strategy unit test** asserting every wire field
on the aggregate type is referenced by the strategy or by a
declared query primitive. This integrates naturally with the
existing Vitest harness and keeps the enforcement colocated with
the strategy.

Two enforcement layers compose:
1. **Compile-time** (where the type shape allows it, per §3
   preconditions): TS exhaustiveness over hand-written aggregate
   types whose index signatures have been removed.
2. **Test-time**: per-strategy unit tests for both hand-written
   and generated aggregate types. Drives the retroactive sweep
   (the test starts failing the moment a new wire field lands
   without consumer) and the gate-mode rollout.

A static analysis script remains optional (parallel to the
canonical-doc-driven script-based gates the project already runs)
but is not the load-bearing layer.

#### 7. Optional Java-side `@FieldUse` annotation

Wire-emitted records may annotate metadata fields with their
intended use:

```java
public record Operation(
    OperationRef id,                            // @FieldUse(RoutingOnly)
    Presentation presentation,                  // @FieldUse(Visual)
    Interface intf,                             // @FieldUse(Visual)
    OperationPolicy policy,                     // @FieldUse(Visual)
    OperationAvailability availability,         // @FieldUse(Gate)
    OperationLineage lineage,                   // @FieldUse(Visual + Tooltip)
    Binding binding,                            // @FieldUse(RoutingOnly)
    Provenance provenance,                      // @FieldUse(Visual + Filter)
    Set<ExecutorTag> executors,                 // @FieldUse(Filter)
    Audience audience,                          // @FieldUse(Gate + Filter)
    List<ConsumerHook> consumers                // @FieldUse(RoutingOnly)
) { … }
```

These annotations are exported in the schema baseline (same
machinery as `WireTypesTsGenerationTest`), surface in the
generated `wire-types.ts`, and parameterize the FE Pass-8 check:
"this field claims Filter use; verify a query primitive references
it." Annotations are *advisory* — they describe the backend's
intent. The FE-side check is *normative* — it fails the build if
the intent isn't realized.

This generalizes `UIHint`'s declarative shape (Java declares
rendering intent for inputs) from per-input to per-field. Note
that `UIHint` itself is currently a one-way declaration with no FE
consumer (verified 2026-05-18 — see §"Java-side rendering hints"
inventory entry); `@FieldUse` would land in a similar state until
the FE-side Pass-8 mirror activates, at which point both
mechanisms become load-bearing together. The implementation slice
should consider whether reviving `UIHint` consumption is part of
the same substrate work or a parallel slice.

### Integration with existing systems

#### Wraps Resource-view registry

The existing Resource-view registry
(`resourceRegistry.ts`, verified 2026-05-18: 177-line flat-list
of `{category, hint?, density?, rank, tag}` entries with
`dispatchResourceRenderer({category, hint?, density?}) → tag |
null`) becomes the inner mechanism of the `(Resource, ListItem)`
cell in the aggregate registry. The canonical
`(Resource, ListItem)` strategy delegates to
`dispatchResourceRenderer` for the tag, then mounts the tag with
the Resource as input. Same data, same plugin-extension surface
(`registerResourceRenderer` still works for plugins targeting
the existing Category axis), framed as one cell that holds an
internal sub-dispatch.

This is *additive* substrate — the aggregate registry sits above
the Category dispatch, not in place of it. The earlier draft's
"subsumes" framing overstated the relationship; "wraps" or
"extends" is honest. No rewrite of existing Resource renderers
is required; only the entry point shifts (surfaces mount
`<jf-resource context="list-item">` instead of calling
`dispatchResourceRenderer` directly). The JSON Forms registry
(third existing registry) stays untouched — its scope is JSON-
schema-shaped form controls, genuinely orthogonal.

#### Stays orthogonal to JSON Forms registry

JSON Forms handles operation *inputs* (parameter forms). It's a
separate concern from rendering the operation itself. No merger.

#### Composes with 508 §4 UI slots

508 §4 (when it lands) makes UI zones contribution-registries.
StatusDeck, InspectorPane, ContextActions become extensible zones
where contributions register "place this content here." The
*content* contributions place can be aggregate components — `<jf-
operation>`, `<jf-health-event>`, etc. — already governed by the
aggregate substrate.

The two layers compose: §4 decides *where*, this tempdoc decides
*how*. A plugin contribution can do both (register a zone
contribution that mounts `<jf-operation context="palette-row"
id="my-plugin.do-thing">`).

#### Generalizes 509's catalog-resolution-by-id

509 made operation labels catalog-driven via `<jf-row-actions>` and
the proposed `<jf-op-button>`. Under this design, `<jf-op-button>`
becomes `<jf-operation context="button">`. The same shape; one
more axis (context); one canonical strategy per (Operation,
context) pair owning the composition.

#### Extends plugin contribution model

`PluginContribution` gains an axis:

```typescript
interface PluginContribution {
  // …existing axes…
  aggregateStrategies?: ReadonlyArray<{
    aggregate: WireAggregateKind;
    context: SurfaceContextKind;
    strategy: AggregateStrategy<…>;
    capability: PluginCapability; // override-existing-strategy requires elevated capability
  }>;
}
```

Plugins can:
- Add strategies for new SurfaceContextKinds.
- Override existing canonical strategies (capability-gated; e.g., a
  theming plugin overrides `(Operation, button)` to match a design
  system).
- Add query primitives for plugin-defined aggregates (if plugins
  introduce their own wire-emitted aggregates).

### Long-term structural guarantees

A design at this level produces structural prevention rather than
discipline:

1. **No wire field ships without a consumer.** TS exhaustiveness
   (where the type shape supports it, per §3 preconditions) +
   per-strategy unit tests (the FE-side Pass-8 mirror) combine to
   make this a compile-time / test-time gate. Retroactive sweep on
   first introduction produces the punch list for currently-un-
   consumed fields (the D1-retroactive close).

2. **No surface can hardcode aggregate rendering.** Aggregate
   components are the only sanctioned consumption point. Wire-
   record destructuring outside canonical strategies is banned by
   lint rule. A surface that wants to render an Operation mounts
   `<jf-operation>` — there is no alternative path.

3. **No drift between catalog state and surface rendering.** A
   single canonical strategy per (aggregate, context) pair means
   visual treatment is centrally owned. If the design system
   changes, one strategy changes, every surface follows.

4. **Adding a wire field forces a decision.** For hand-written
   aggregate types with no index-signature escape hatch (per §3),
   TS exhaustiveness in the canonical strategy fails the build
   until the new field is consumed, explicitly elided, or routed.
   For generated aggregate types or types still carrying index
   signatures, the per-strategy unit test (§6) catches the missing
   consumer at test-time. The "fields exist on the wire today;
   consumers do not" pattern becomes structurally unreachable
   provided both mechanisms are in place.

5. **Plugin contribution is first-class but capability-gated.**
   Plugins can override or extend without forking the framework,
   subject to capability checks for security-sensitive overrides.

6. **Surface authoring contract is inverted.** Surfaces author
   *where and which* aggregates appear (slot composition, query
   filters). Surfaces never author *how* an aggregate renders.
   This is the same inversion 509 produced for one operation
   field (label); this design produces it for all aggregates in
   all contexts.

### Relationship to D-classes

(D-class labels are descriptive — no canonical defect-class
tempdoc exists; see verification entry on the D-class summary.)

- **D3** (metadata-not-surfaced): directly closed. The specific
  defects this design closes are: Operation.policy.risk not
  rendered on buttons; Operation.policy.confirm ignored by
  ActionButton (using risk as proxy); Operation.audience not
  gating visibility or filtering palette; Operation.executors not
  filtering palette; Operation.provenance / Resource.provenance
  rendered as plain text where rendered at all;
  HealthEvent.provenance.transport rendered as plain text instead
  of via `<jf-dispatch-source>`; Operation.lineage.affects not
  surfaced in any tooltip or "what does this affect" UI.
- **D1** (substrate-without-consumer) retroactive half: closed by
  the FE-side Pass-8 sweep. Current un-consumed wire fields are
  enumerated on first run; each becomes a punch-list item that
  either gets consumed by the canonical strategy or annotated as
  `@FieldUse(RoutingOnly)` with justification.
- **D1 going forward**: closed structurally by TS exhaustiveness
  (where supported) + per-strategy unit tests. New fields cannot
  reach `main` without a consumer.

---

## Critique of this design

Honesty about where this proposal is fragile:

1. **TS exhaustiveness over deep record types is delicate.** Wire
   aggregates have nested structures (Operation.policy.risk,
   Operation.provenance.transport). Exhaustiveness needs to bottom
   out on leaf fields, not just top-level components. The pattern
   probably looks like a generated `consumedFields<T>()` helper
   that descends the type — non-trivial to implement correctly,
   and any escape hatch defeats the structural guarantee.

2. **`@FieldUse` annotations risk drifting from reality.** They
   describe intent, but only the FE-side check verifies that
   intent is realized. If the annotation is wrong (says
   `RoutingOnly` for a field that should be Visual), the silent-
   defect mode reappears at the annotation layer. Mitigation:
   the annotation should never *exempt* a field from consumption —
   only categorize *how* it is consumed. Every field still needs
   a positive reference somewhere.

3. **SurfaceContextKind enumeration may sprawl.** Ten contexts is
   manageable. Fifty is not. The design depends on contexts being
   coarse enough that surfaces compose them, fine enough that
   each canonical strategy is cohesive. Drawing the line is a
   design exercise that should happen during the actual
   implementation slice, not deferred to later.

4. **One canonical strategy per cell is restrictive.** What if two
   surfaces both render Operation as a button but with subtly
   different chrome (e.g., toolbar button vs. inline button)?
   The design says: that's a different SurfaceContextKind (`button-
   toolbar` vs. `button-inline`). This is correct in principle
   but produces a combinatorial explosion if context distinctions
   are mostly visual rather than semantic. A discriminator on
   `SurfaceContextOf<C>` (e.g., `ctx.density = compact|comfortable`)
   keeps the cell count down while preserving cohesion.

5. **The capability-gated plugin override is a security surface.**
   "Theming plugin overrides (Operation, button)" sounds benign;
   "malicious plugin overrides (Operation, button) to hide
   confirm ceremony" is the same mechanism. The capability check
   must be load-bearing, not advisory. Reference: the
   `PluginCapabilityBundle` model already covers this; the
   override needs a dedicated capability.

6. **Retroactive sweep is one-time, but the design promises
   prevention.** If the sweep finds 30 un-consumed fields, that's
   30 punch-list items that need to be addressed before the FE-
   side Pass-8 check can become a hard gate. There's a transition
   period where the check runs in warn-only mode. This is
   expected and not a design flaw, but it should be planned.

7. **Java-side `@FieldUse` is optional. Optional substrate doesn't
   prevent drift.** A truly load-bearing design might require
   `@FieldUse` on every metadata field of every wire-emitted
   record, enforced by a backend test (parallel to Pass-8 wire-
   emitter). The current proposal leaves this optional because
   it's defensible to start with the FE-side check alone and add
   the backend annotation later. But "later" has a track record
   of becoming "never." A future revision should consider making
   this mandatory.

8. **Wire-payload casing is inconsistent between aggregates
   (verified live 2026-05-18).** `UIOperationEmitter` calls
   `.toLowerCase()` on risk, audit, provenance.tier, and executor
   names (`risk: "high"`, `tier: "core"`), but emits audience
   uppercase (`audience: "OPERATOR"`). The `Resource` emitter
   emits everything uppercase (`category: "EVENT_STREAM"`,
   `audience: "USER"`, `tier: "CORE"`). The FE hand-written types
   in `registry.ts` declare uppercase string-unions for both
   (`RiskTier = 'LOW'|'MEDIUM'|'HIGH'`, `ProvenanceTier = 'CORE'|
   'TRUSTED_PLUGIN'|'UNTRUSTED_PLUGIN'`). When wire data lands
   directly on a consumer, the type system's nominal correctness
   masks a runtime mismatch — observed concretely in
   `ActionButton.ts:92-98` where `[data-risk='HIGH']` styles
   never match the wire value `"high"`. The aggregate-surfacing
   design does not fix this — it inherits the problem. Either
   (a) the canonical strategies all normalize at the boundary
   (lower → upper on read), or (b) the emitters are made
   consistent (a separate backend slice). Without one of these,
   silent type-style defects survive into the new substrate.

9. **`<jf-action-button>` consumes `risk` as a proxy for confirm
   ceremony (verified 2026-05-18).** The component's
   `handleInitialClick` (line 177-191 of ActionButton.ts) reads
   `this.risk` to decide ceremony: `LOW → fire`,
   `MEDIUM → confirm prompt`, `HIGH → typed input`. It never
   reads a `confirm` prop. The wire ships a richer
   `policy.confirm: {kind: 'NONE'|'INLINE'|'TYPED', confirmTextKey?}`
   (see `registry.ts:244-247`), which the component ignores.
   Effects: an Operation with `{risk: 'HIGH', confirm: {kind:
   'NONE'}}` still demands typed confirmation in the UI; an
   Operation with `{risk: 'LOW', confirm: {kind: 'TYPED'}}` fires
   without any ceremony. The canonical `(Operation, button)`
   strategy under this design must either (a) pass `confirm` as a
   first-class prop to the button and update the button to use it,
   or (b) keep the risk-only coupling but document the
   constraint that wire shapes must align risk with confirm.
   Both are real work; the design doesn't escape it.

10. **The "remove Operation's index signature" prerequisite is
    not free.** The current `Operation` FE type at
    `registry.ts:264-272` has `[key: string]: unknown` precisely
    *because* fields the FE doesn't consume are passed through
    without explicit declaration. Removing the signature means
    enumerating every wire field on the Operation interface
    (audience, executors, lineage, availability, consumers, plus
    nested types for ConsumerHook etc.). This is the single
    structural prerequisite for the design's strongest claim
    (compile-time exhaustiveness). It is a one-time cost — but
    it must happen, and the slice picking this up should plan
    for it as Phase 0.

---

## Out of scope for this design document

The following are explicitly deferred to a future implementation-
phasing slice. This tempdoc is design context only.

- **Implementation phasing.** Which aggregates to register first,
  which contexts to enumerate first, migration order from
  existing scattered renderers, deprecation path for direct wire-
  record destructuring in current surfaces.
- **File-level placement.** Where the registry lives (likely a new
  `modules/ui-web/src/shell-v0/aggregate-substrate/` directory but
  not committed). Where canonical strategies live (per-aggregate
  files? one file per cell?). Where query primitives live.
- **Test strategy.** Unit-test shape for canonical strategies, the
  Pass-8 check implementation, plugin-override capability tests,
  TS exhaustiveness test harness.
- **Verification approach.** How the retroactive sweep runs, how
  warn-mode transitions to gate-mode, what the punch-list output
  format looks like.
- **Specific renderer code for the D3 defects.** Each defect
  listed in §"Relationship to D-classes" has a definite "what the
  rendering should look like" answer; that's a design-system
  question, not an architecture question, and it doesn't belong
  here.
- **i18n integration.** Catalog already integrates with
  `localizeResourceKey`; the new aggregate strategies will too,
  but the wiring is implementation detail.
- **Performance considerations.** Dispatching through the registry
  per render is fine for current scale; if the FE renders
  thousands of aggregate cells in a frame this needs measurement.
  Deferred.

The next slice picking this up should produce a phased
implementation plan, starting with the smallest aggregate-context
pair (likely `(Operation, button)` since the canonical strategy
already mostly exists in `<jf-action-button>`), validating the
registry shape on one concrete cell before generalizing, and
running the retroactive Pass-8 sweep to enumerate the existing
punch list.

---

## Verification log

**Date**: 2026-05-18.
**Method**: static codebase reads (Tier A) + live dev-stack probe
against `/api/registry/operations` and `/api/registry/resources`
on backend port 50967 (Tier C). TS exhaustiveness sandbox
experiment (Tier B) skipped — the type-shape reads were
conclusive without it. Surface inventory grep (Tier D) confirmed
28 view files in `shell-v0/views/` and components contain
`<button>` markup.

**Claim status**:

| ID | Claim | Status | Where revised |
|----|-------|--------|---------------|
| C1 | TS exhaustiveness over wire records | Mixed — works for Resource as-is (required fields, no index sig); needs Operation type surgery (remove `[key: string]: unknown` and add audience/executors/lineage/availability/consumers); needs schema-gen change or test-based enforcement for HealthEvent (all-optional generated) | §Design point 3 rewritten; §Long-term guarantees 1 + 4 nuanced; §Critique 10 added |
| C2 | "Half the renderers already exist" | Partially confirmed. `<jf-action-button>` and `<jf-dispatch-source>` real and shipping; **`<jf-provenance-badge>` does NOT exist** — file at that path is a slice 471 user-config override indicator, unrelated to wire provenance | §What already exists / Aggregate-aware components rewritten |
| C3 | Operation wire payload reality | Confirmed populated. All Operation fields (audience, executors, lineage, provenance, confirm, consumers, availability) appear on live wire. Reveals casing inconsistency (lower/upper mix) | §Critique 8 added |
| C4 | Resource-view registry "subsumes" | Reframed as "wraps" — the aggregate registry sits above the existing Category dispatch as the `(Resource, ListItem)` cell's inner mechanism, not as a replacement | §Integration / "Wraps Resource-view registry" rewritten |
| C5 | Hardcoded surfaces inventory | Confirmed at scale: 28 files in shell-v0 contain raw `<button>` markup. Includes HealthSurface, BrainSurface, SearchSurface, LibrarySurface, SettingsSurface, plus most chat / advisory components | Documented in this log; phasing implications belong to a future implementation slice |
| C6 | 508 §4 not landed | Confirmed: `StatusBarContribution\|InspectorTabContribution\|ContextActionContribution` grep returns zero matches | No tempdoc change needed |
| C7 | Plugin contribution merge model accommodates new axis | Confirmed extensible: paired merge/remove functions per axis (`mergePluginSurfaceContributions` / `removePluginSurfaceContributions` etc.) called from `PluginRegistry.install`/`uninstall`. Adding `aggregateStrategies` follows the same pattern | No tempdoc change needed |
| C8 | Java `UIHint` analogy | **Invalidated** — `grep "uiHints"` in `modules/ui-web/src/` returns zero matches. Java emits the hint via `UIOperationEmitter.java:68-72`, no FE consumer exists | §What already exists / Java-side rendering hints rewritten; §Design point 7 nuanced |
| C9 | 504 F-IDs (F-9/10/23/24/26) | **Invalidated** — `docs/tempdocs/504*` returns zero results. F-IDs were informal references, not catalogued items | §Relationship to D-classes rewritten; §Out of scope adjusted |
| C10 | Pass-8 wire-emitter check enforcement | Confirmed: per-emitter unit test (`UIOperationEmitterTest.java`), not a CI script. FE-side mirror should follow same form: per-canonical-strategy unit test | §Design point 6 rewritten |
| C11 | Retroactive sweep magnitude | Manageable: ~5 Operation metadata fields un-consumed (audience, executors, lineage, availability, consumers) + transport defect on existing surfaces. Resource has parallel un-consumption pattern. Order of magnitude tens, not hundreds | Documented in this log |

**New findings surfaced by verification** (not in original
uncertainty list, added to §Critique 8–10):

- **N1 — Casing inconsistency between aggregates.**
  `UIOperationEmitter` calls `.toLowerCase()` on risk / audit /
  provenance.tier / executors, but emits audience uppercase. The
  `Resource` emitter emits everything uppercase. FE hand-written
  types in `registry.ts` declare uppercase string-unions for
  both. Concrete consequence: ActionButton's CSS
  `[data-risk='HIGH']` never matches the live wire value
  `risk: "high"` — risk styling silently no-ops. The aggregate
  design does not fix this; it inherits the problem and must
  either normalize at the canonical-strategy boundary or wait on
  a separate backend slice that aligns the emitters.

- **N2 — ActionButton ignores `policy.confirm`.** It derives
  ceremony from `risk` alone (LOW → fire, MED → confirm prompt,
  HIGH → typed input), never reads the wire's
  `policy.confirm.kind`. The wire ships a separate
  `policy.confirm: {kind: 'NONE'|'INLINE'|'TYPED', confirmTextKey?}`.
  Wire shapes can disagree (e.g., risk=LOW, confirm=TYPED), and
  the component will follow risk silently. The `(Operation,
  button)` strategy must reconcile or extend the component.

- **N3 — Operation FE type has explicit anti-exhaustiveness
  escape hatch.** `[key: string]: unknown` index signature at
  `registry.ts:264-272` deliberately allows un-declared wire
  fields to round-trip without acknowledgment. Removing it is
  the Phase 0 prerequisite for compile-time exhaustiveness on
  Operation.

**Open items**:

- A live HealthEvent payload probe was not run (no whitelisted
  endpoint via MCP `api_call`; `/api/health/events/stream` is
  SSE and would have required manual curl streaming). Claim C3
  for HealthEvent rests on the generated `wire-types.ts` (all
  optional, confirmed read).

- The TS exhaustiveness sandbox experiment was skipped — the
  static type-shape reads (Resource fully required vs Operation
  with index signature vs HealthEvent all optional) were
  conclusive enough to make the call. A future implementation
  slice should still write a real exhaustiveness probe as Phase 0
  to validate the chosen pattern (e.g., `satisfies Record<keyof
  T, true>` or destructure-rest-empty) compiles correctly under
  the project's `tsconfig.json` `strict` settings.

- An `OperationCatalogClient` deep read (the actual FE
  resolution path from `/api/registry/operations` to consumers)
  was deferred — the explorer report and the resourceRegistry
  read cover the pattern adequately for design purposes.
  Implementation slice should read this file before drafting the
  Operation aggregate component.

The tempdoc above reflects post-verification claims. Original
unverified claims survive in §Problem (the observable defects —
these were re-confirmed) and in §Why the obvious framing is
wrong (the architectural critique — unaffected by verification).

---

## Implementation status (2026-05-18, autonomous overnight session)

The substrate is implemented and merged to `main`. Live verification
ran against the dev stack (cold-start, port 58623). Final unit test
count: 1320 passed (was 1215 pre-Phase 1; net +105 across all
phases).

### Phases shipped

- **Phase 0 — Prerequisites**: ✅ shipped.
  - `Operation` FE type closed (removed `[key: string]: unknown`
    escape hatch); audience / executors / lineage / availability /
    consumers + new sub-types (Audience, ExecutorTag,
    OperationAvailability, OperationLineage, ConsumerHook) added.
  - `Resource` FE type gained `audience` + `consumers` (wire always
    carried; FE type was missing).
  - `OperationCatalogClient.normalizeOperationFromWire` reconciles
    lowercase enums + `confirm.type` → uppercase enums + `confirm.kind`
    at the fetch boundary. Storage cache key bumped to `.v2`.
  - `<jf-action-button>` gained optional `confirm-kind` prop
    (wire-driven ceremony override; falls back to risk-derived
    ceremony when unset).
  - 5 test fixtures updated for the new Resource shape.

- **Phase 1 — Aggregate substrate module**: ✅ shipped.
  - `modules/ui-web/src/shell-v0/aggregate-substrate/` with
    `aggregateKinds.ts`, `surfaceContextKinds.ts`,
    `aggregateRegistry.ts`, `queryPrimitives.ts`,
    `assertExhaustive.ts`, `bootstrap.ts`, `index.ts`.
  - `PluginContribution.aggregateStrategies` axis;
    `PluginRegistry.install` / `uninstall` wired to it (parallel
    to existing surface/resource/recovery merge-axes).

- **Phase 2 — (Operation, button) canonical cell**: ✅ shipped.
  - `strategies/operationButton.ts` mounts `<jf-op-button>` (slice
    509's catalog-driven invocation component) + adds audience
    gate via `operationVisibleTo` + `data-*` attrs for every
    metadata field. Compile-time exhaustiveness enforced via
    `OPERATION_BUTTON_CONSUMED: Consumed<Operation>` (the
    Operation type's escape hatch was removed in Phase 0, so a
    missing entry fails compilation).
  - `components/JfOperation.ts` (`<jf-operation>`) — the sanctioned
    consumption point. Subscribes to OperationCatalogClient changes.

- **Phase 3 — FE-side Pass-8 mirror**: ✅ shipped.
  - `operationButton.test.ts` enumerates Operation wire keys and
    asserts symmetry with `OPERATION_BUTTON_CONSUMED`. Mirrors the
    backend `UIOperationEmitterTest` pattern per slice-execution.md
    §X.12.10.

- **Phase 4 — HealthSurface migration + live verification**: ✅ shipped.
  - 6 Quick Action buttons migrated from raw `<button>` /
    `<jf-op-button>` → `<jf-operation context="button"
    viewer-audience="OPERATOR">`. Live-verified: all 6 buttons
    render with wire metadata on data attributes; HIGH-risk
    `core.restart-worker` triggers typed-confirm ceremony on click;
    audience gate hides OPERATOR-only ops when viewer-audience is
    flipped to USER.

- **Phase 6 — (Resource, list-item) cell + ActivitySurface
  migration + live verification**: ✅ shipped.
  - `strategies/resourceListItem.ts` mounts `<jf-resource-view>`
    (slice 3a.1.9's substrate consumer) + audience gate via
    `resourceVisibleTo` + `data-*` attrs for all 17 Resource wire
    fields. `RESOURCE_LIST_ITEM_CONSUMED` exhaustively declared.
  - `components/JfResource.ts` (`<jf-resource>`).
  - ActivitySurface migrated to `<jf-resource context="list-item"
    resource-id="core.operation-history" viewer-audience="USER">`.
  - Live-verified: `<jf-resource>` mounts; strategy dispatches;
    inner `<jf-resource-view>` carries all data attributes
    (category=EVENT_STREAM, kind=operation-history, provenance=CORE,
    subscriptionMode=SSE_STREAM, audience=USER).

- **Phase 7 — (HealthEvent, activity-row) substrate**: ✅ shipped.
  - `strategies/healthEventActivityRow.ts` with all 6 wire fields
    consumed. Generated-type Pass-8 mirror enumerates via
    `Object.keys(reference)` since `Record<keyof T, true>` doesn't
    catch missing keys when every key is optional.
  - **Production consumer named follow-up**: slice
    `511-followup-A — migrate HealthSurface.renderEvents to wire
    HealthEvent shape`. Current HealthSurface uses an ad-hoc local
    HealthEvent interface (title/message/level/timestamp) different
    from the wire HealthEvent (severity/source/i18nKey/body). The
    migration requires reconciling the two shapes; not infeasible,
    but cleanly a separate concern.

- **Phase 8 — Retroactive sweep**: ✅ implicit in the per-strategy
  Pass-8 unit tests. Every wire field on Operation / Resource /
  HealthEvent is enumerated by a `CONSUMED` declaration and
  exercised by a test. Adding a new wire field requires updating
  the declaration; TypeScript (Operation, Resource) or the test
  (HealthEvent) catches the gap. The "punch list" is empty by
  construction.

- **Phase 9 — Surface migration**: ✅ shipped.
  - HealthSurface (6 ops), ActivitySurface (1 Resource),
    BrainSurface (1 op — conditional render), LibrarySurface (1
    op). Now zero `<jf-op-button>` usages in `views/` — every
    callsite uses the aggregate component. Remaining `<button>`
    instances are non-operation (nav / form / dropdown) and not
    migration candidates.

- **Phase 10 — Bulk live verification**: ✅ shipped.
  - Verified registered cells via the aggregate registry's
    `getRegisteredCells()`: 3 cells all sourced 'core'.
  - Surface mounts verified for HealthSurface, LibrarySurface,
    ActivitySurface. BrainSurface migrated but conditional render
    not active in default state (no embedding-incompat condition).

### Phases deferred — with specific blockers

- **Phase 5 — Other Operation contexts (palette-row /
  history-entry / inline-mention / badge-strip)**:
  **Blocker**: no production consumer surfaces exist for these
  SurfaceContextKinds. No command palette, no operation-history
  list, no inline-mention rich-text consumer, no badge-strip user.
  Shipping the strategies would create C-018 substrate-without-
  consumer instances (per `.claude/rules/agent-lessons.md`
  C-018 discipline). Ship when surfaces materialize. The enum
  entries are declared in `surfaceContextKinds.ts` so adding a
  strategy later is a localized change.

- **Named consumer follow-up slices (queued, not infeasible)**:
  - `511-followup-A — wire-HealthEvent consumer surface`: see
    Phase 7 above. Migrates HealthSurface's local-shape events to
    wire shape + renders via `<jf-health-event>`.
  - `511-followup-B — extend OpButton to consume policy.confirm.kind`:
    OpButton currently derives ceremony from `risk` alone
    (Critique #9). Pass `confirm-kind` through to inner
    `<jf-action-button>` (which now supports the prop) so wire-
    driven ceremony wins over risk-derived. ActionButton already
    accepts `confirm-kind`; OpButton just needs to forward it. One
    file change.
  - `511-followup-C — OperationInvocation aggregate component`:
    `OperationInvocation` is in `WireAggregateKind` but has no
    canonical strategy. It carries `target` + `defaultArgsJson`;
    useful for recovery overlays and operation-invocation
    references. Ship when a consumer materializes.

- **Java-side `@FieldUse` annotation**: deferred per design
  (optional). FE-side Pass-8 mirror is normative and sufficient
  for V1.

- **508 §4 UI slot system landing**: separate substrate; the
  aggregate registry composes when §4 lands but does not depend
  on it. Confirmed orthogonal during Phase 9 migrations.

- **Lint rule banning wire-record destructuring outside canonical
  strategies**: deferred. Convention-based discipline + Pass-8
  unit tests catch the failure mode at PR time; the lint rule
  would be belt-and-suspenders.

### Open observations from implementation

1. **Main repo / worktree divergence surprise.** The 502 worktree
   was significantly behind main when implementation began. Main
   had landed slice 509's `<jf-op-button>` (catalog-driven button
   the tempdoc was designed around) plus 30+ other commits.
   Reconciled via `git merge main` into the worktree branch with
   3 textual conflicts — all in files this slice touched
   (plugin-types.ts axis additions, PluginRegistry.ts axis wiring,
   HealthSurface.ts Quick Actions). Live verification required
   merging to main because the MCP dev runner serves from main
   (not the worktree). Branch name (`worktree-502-boot-composition`)
   mismatches the 511 scope per autonomous-session continuity
   constraint — addressed in commit messages.

2. **Casing inconsistency reconciliation worked end-to-end.** The
   wire emits `risk: "high"` lowercase + `confirm: {type: "typed"}`
   while the FE expects uppercase + `confirm: {kind: "TYPED"}`.
   `normalizeOperationFromWire` reconciles at the catalog-client
   boundary. Live probe confirmed: ActionButton's
   `[data-risk='HIGH']` CSS selector now matches the value the
   catalog produces. This was Critique #8's silent defect; fixed.

3. **ActionButton confirm-kind prop is accepted but not yet
   driven from wire by OpButton.** ActionButton (Phase 0) added
   `confirm-kind` prop. OpButton (slice 509) still derives
   ceremony from risk only. The (Operation, button) strategy
   surfaces `data-confirm-kind` on the OpButton DOM but OpButton
   doesn't read it back. Closed via 511-followup-B (see above) —
   a localized one-file fix.

4. **Substrate health check via registry introspection.**
   `getRegisteredCells()` returned the expected 3 cells at live-
   verify time (Operation/button, Resource/list-item,
   HealthEvent/activity-row), all rank 0, source 'core'. This is
   the diagnostic surface the retroactive sweep + plugin overlay
   tooling will use.

### What didn't go as planned

- The plan assumed I could migrate the tempdoc into the worktree
  and work there cleanly. Worktree-vs-main divergence forced a
  mid-stream `git merge main` plus a merge to main for live
  verification. Pre-flight checking `git log HEAD..main` at the
  start of an autonomous session is worth adding to the slice-
  execution discipline.

- Phase 5 contexts were planned as "ship strategies for all
  declared SurfaceContextKinds." This collided with the C-018
  rule when none of the contexts had production consumers.
  Reframed as "ship the enum + cells; ship strategies when
  consumers materialize," which is the correct discipline.

### Net delta

- 25 files changed, 2378 insertions / 24 deletions across the
  three merges to main (Phase 0-3-4 + Phase 6 + Phase 7-9).
- 1320 tests passing (+105 vs baseline).
- 3 canonical cells registered, 2 with production consumers
  (Operation/button, Resource/list-item).
- 4 surfaces migrated (HealthSurface, ActivitySurface,
  BrainSurface, LibrarySurface).
- 0 `<jf-op-button>` usages remain in `views/`; the aggregate
  component is the sole sanctioned consumption point.

---

## 511-followup (correction pass, 2026-05-18)

A critical analysis after the initial closure surfaced four
defects in the substrate as shipped:

1. **The audience gate was theater.** No viewer-identity store
   existed; surfaces hardcoded `viewer-audience="OPERATOR"` /
   `"USER"`. The gate filtered with a value the surface itself
   chose, so it never gated anything.
2. **The Pass-8 mirror was fake exhaustiveness.** It enumerated
   keys via `Record<keyof T, true>` but didn't verify the strategy
   actually read each field. After Track D's data-* removal (which
   exposed how little of the wire the strategies actually consume)
   the records were obviously lying.
3. **`confirm-kind` was dead code.** ActionButton accepted the
   prop (Phase 0). The (Operation, button) strategy emitted
   `data-confirm-kind` on `<jf-op-button>`. OpButton never
   forwarded it. The wire's `policy.confirm.kind` was discarded.
4. **Casing reconciliation was a band-aid.** `UIOperationEmitter`
   lower-cased risk / audit / provenance.tier / executors and
   used `confirm.type`; the FE catalog client normalized to
   uppercase + `confirm.kind`. Any consumer reading the wire
   directly bypassed the normalizer.

Plus extensive substrate-without-consumer: 5/7 query primitives,
8/10 SurfaceContextKind values, 2/4 WireAggregateKind values, and
ALL `data-*` attributes had zero production callsites.

### Tracks shipped

- **Track F — Hygiene**: tests from main HEAD green (1320); the
  `core.reindex` / `core.rebuild-index` / `core.bulk-reindex` triad
  confirmed as 3 distinct backend handlers, not aliases.

- **Track D — Remove substrate-without-consumer**:
  - `queryPrimitives.ts`: dropped `operationCallableBy`,
    `operationAffectsResource`, `operationSupersedes`,
    `operationGroupKey`, `resourceGroupKey`. Kept `operationVisibleTo`
    + `resourceVisibleTo` (the two used by the strategies).
  - `surfaceContextKinds.ts`: dropped 8 unused kinds; kept `button`
    + `list-item`.
  - `aggregateKinds.ts`: dropped `HealthEvent` (the Phase 7
    strategy had no production consumer; `HealthSurface.renderEvents`
    uses an ad-hoc local shape) and `OperationInvocation` (never
    had a strategy). Reintroduce when consumers ship.
  - Stripped 10 + 16 `data-*` attributes from the two remaining
    canonical strategies' rendered output. No CSS or JS reads
    them in production; their presence on the DOM was
    "documentation that nothing reads."
  - Result: matrix shrinks from 4 × 10 = 40 declared cells with 3
    populated to 2 × 2 = 4 declared cells with 2 populated.

- **Track C — Output-driven Pass-8 mirror**:
  - Added `FieldRole = 'visual' | 'gate' | 'routing' | 'elided'`
    and `FieldRoles<T> = Record<keyof T, FieldRole>` in
    `assertExhaustive.ts`.
  - New `behavioralPass8.ts` with `assertBehavioralPass8(spec)`
    that mutates each wire field per a mutations map and asserts:
    'visual' / 'gate' MUST produce a rendered-output diff;
    'routing' / 'elided' MUST NOT. A strategy that claims 'visual'
    for a field it never reads fails the test with a clear error.
  - Strategy declarations replaced: `OPERATION_BUTTON_CONSUMED` →
    `OPERATION_BUTTON_ROLES` (honestly classifying the 4 visual /
    1 gate / 3 routing / 3 elided fields); same for Resource.
  - Tests rewritten to use `assertBehavioralPass8`.

- **Track B — `confirm-kind` end-to-end plumbing**:
  - OpButton accepts `confirm-kind` attribute. New
    `deriveConfirmKind(operationId, override)` chooses the explicit
    override → catalog's `policy.confirm.kind` → '' (ActionButton
    falls back to risk-derived ceremony for back-compat).
  - OpButton forwards to inner `<jf-action-button confirm-kind=…>`.
  - ActionButton's `resolvedConfirmKind()` already preferred the
    explicit prop (Phase 0). Now wire-driven ceremony wins for any
    operation flowing through the catalog. Critique #9 closed.
  - 3 new OpButton tests: wire-driven TYPED reaches ActionButton;
    `risk=LOW + confirm=TYPED` produces the right ceremony
    selection; explicit attribute overrides the catalog value.

- **Track A — Real audience gate via UserStateDocument**:
  - Extended `UserStateV1` with optional `viewerAudience: Audience`
    (default 'USER'); validateV1 rejects malformed values.
  - New `state/viewerAudienceState.ts` projection:
    `getViewerAudience()` / `subscribeViewerAudience()` /
    `setViewerAudience()`. Mirrors the `userConfigState.ts`
    pattern over `UserStateDocument`.
  - `<jf-operation>` + `<jf-resource>` now treat `viewer-audience`
    as an OPTIONAL OVERRIDE. Empty (default) reads from the
    store; subscribes to changes for live re-render.
  - Removed hardcoded `viewer-audience="..."` from HealthSurface,
    BrainSurface, LibrarySurface, ActivitySurface.
  - SettingsSurface gains a `renderViewerAudience()` section with
    three buttons (User / Operator / Developer) — the user-facing
    input source the gate was missing.
  - New `viewerAudienceState.test.ts`: default, persistence,
    subscriber semantics.

- **Track E — Wire casing alignment**:
  - `UIOperationEmitter.java`: removed `.toLowerCase(Locale.ROOT)`
    on risk / audit / provenance.tier / executors. Switched
    `confirm.type` discriminator to `confirm.kind` matching the FE
    `ConfirmStrategy` shape.
  - `UIOperationEmitterTest.java`: flipped assertions.
  - `OperationCatalogClient.ts`: deleted the entire normalizer
    block (170 lines). Boot/refresh paths now consume the wire
    body directly. Storage key bumped to `.v3`.

### Live verification (final sweep, after Track E + Track A + Track B all merged)

Dev stack re-built + restarted (the `:modules:ui:installDist`
output was stale until forced; that's a discovery for future
slice-execution hygiene). Probe via Chrome browser tools at
`http://localhost:5173/?lit-chrome=1#justsearch://surface/core.health-surface`:

- `curl /api/registry/operations` for `core.restart-worker`:
  ```
  risk: HIGH, audit: METADATA_ONLY, tier: CORE,
  executors: [UI], audience: OPERATOR,
  confirm: { confirmTextKey: …, kind: TYPED }
  ```
  Canonical uppercase + `kind` discriminator. ✓ Track E.

- Default viewer audience (USER from store): only `core.reindex`
  (audience=USER) renders; 5 OPERATOR-only ops hidden. ✓ Track A.

- `setViewerAudience('OPERATOR')` via the store: all 6 ops now
  render. Each op's inner ActionButton carries the correct
  `confirm-kind` from the wire — LOW/NONE, HIGH/TYPED, HIGH/INLINE,
  MEDIUM/INLINE, LOW/NONE, MEDIUM/INLINE. ✓ Track A, Track B.

- The audience gate is no longer theater: the same operation has
  different visibility based on the user's chosen audience tier,
  not based on what the surface decides to claim.

### Net delta after 511-followup

- 7 Track-shipped commits (D, C, B, A, E + 2 merge commits) on top
  of the initial 511 close.
- 1321 unit tests passing (was 1320; +3 from viewerAudienceState
  test; -1 from one merged operationButton test case; -3 from
  removed HealthEvent strategy tests; -1 from removed provenance
  data-attr test; +5 from behavioralPass8 + new OpButton cases).
- Backend `./gradlew.bat build -x test` green;
  `UIOperationEmitterTest` green.
- 1 line of dead code remaining at the strategy → OpButton chain
  (substrate is honest).

### Open items (named follow-up slices, with specific blockers)

- **511-followup-A — HealthEvent consumer migration.**
  HealthSurface.renderEvents reads ad-hoc local-shape HealthEvent;
  the wire HealthEvent (severity / source / i18nKey / body) is a
  different shape. Migration requires reconciling the two
  representations. The HealthEvent strategy + aggregate kind +
  activity-row context can be re-introduced together with the
  consumer in that slice; until then, they're not on the wire
  surface at all (Track D removal).

- **511-followup-C — OperationInvocation aggregate.**
  Same situation: substrate ready in concept, no concrete
  consumer surface today. Re-add WireAggregateKind +
  SurfaceContextKind + strategy when a recovery-action chip or
  similar consumer materializes.

- **Style consumption for `risk` / `audience` / `provenance.tier`.**
  The (Operation, button) strategy now reads these via OpButton
  → ActionButton attributes, not via `data-*`. If a CSS pass
  later wants per-tier styling on the outer `<jf-operation>`
  shell, that's a separate concern that adds new attributes /
  classes — at that point with a CSS consumer. Not done now.

- **Slice-execution hygiene: post-build installDist freshness.**
  The dev runner's worker dist can lag behind a fresh build if
  upstream Gradle tasks report UP-TO-DATE. Track E live-verify
  needed `./gradlew.bat :modules:ui:installDist` explicitly
  before `dev_start` to pull the new jar through. Worth adding
  to the slice-execution discipline alongside the existing
  pre-flight check.

---

## 511-followup-2 (second correction pass, 2026-05-18)

A second critical-analysis pass after 511-followup closed surfaced
four real defects and three test coverage gaps:

1. **Schema baseline disagreed with the wire on `confirm`**.
   Track E flipped UIOperationEmitter to emit `confirm.kind`
   (uppercase) but the SSOT operation.v1.json schema still declared
   `confirm.type` (lowercase). `ConfirmStrategy.java`'s Jackson
   annotation `@JsonTypeInfo(property = "type")` is what
   `SubstrateSchemaGenTest` reads to regenerate the schema — so
   the annotation, the schema, AND the emitter all had to agree.
   Only the emitter did.

2. **FE `AuditPolicy` type had a quiet pre-existing defect**.
   Declared as `'NONE' | 'METADATA_ONLY' | 'FULL'`. The Java
   enum is `NONE | METADATA_ONLY | FULL_PAYLOAD`. The FE was
   silently wrong; no production op currently emits FULL_PAYLOAD
   so the bug never surfaced. Discovered during Track AA's schema
   investigation.

3. **Audience UI overstated what the mechanism does.**
   511-followup Track A wired the audience gate to a real store
   driven by a user-controlled SettingsSurface toggle. But the
   user picks their own tier; the wire still ships every op's
   metadata to every client; `OperationClient.invoke()` will call
   any operation regardless of the chosen tier. The "gate" filters
   what the UI RENDERS, not what's PERMITTED. The UI copy
   ("Viewer mode", "Audience tier") oversold this.

4. **Test coverage gaps from 511-followup.** No component-level
   `<jf-resource>` test, no SettingsSurface audience-toggle
   wire-up test, no viewerAudienceState persistence round-trip.

### Tracks shipped

- **Track AA — Schema alignment.**
  - `modules/app-agent-api/.../ConfirmStrategy.java`:
    `@JsonTypeInfo(property = "kind")`; `@JsonSubTypes` names
    `"NONE"`/`"INLINE"`/`"TYPED"`. Jackson, the emitter, the
    schema generator, and the FE type now all agree.
  - `SSOT/schemas/operation.v1.json` + classpath copy
    `modules/ui/src/main/resources/SSOT/schemas/operation.v1.json`:
    regenerated via `./gradlew :modules:app-api:test
    --tests "*SubstrateSchemaGenTest*"` (delete-and-recapture).
    Both copies updated per the classpath-drift discipline.
  - `modules/ui-web/src/api/types/registry.ts`: `AuditPolicy =
    'NONE' | 'METADATA_ONLY' | 'FULL_PAYLOAD'` (was 'FULL').

- **Track DD — `hasAttribute` semantics for `viewer-audience`.**
  - JfOperation / JfResource: replaced the empty-string sentinel
    (`viewerAudience: Audience | ''`) with explicit `hasAttribute`
    semantics (`viewerAudience: Audience | null`; null when the
    attribute is absent). The public attribute contract is
    unchanged; the internal property type is now clean.

- **Track CC — Missing tests.**
  - `JfResource.test.ts` (new, 5 cases): catalog resolution,
    missing-resource, USER-viewer denies OPERATOR resource,
    explicit override beats store, store-driven default.
    Stubs EventSource (NoopEventSource) to handle ResourceView's
    SSE connect under happy-dom.
  - `SettingsSurface.viewerAudience.test.ts` (new, 6 cases):
    finds the three audience buttons, asserts default-selected
    state, clicks each tier, verifies `getViewerAudience()` and
    the `.selected` CSS class follow the active tier.
  - `viewerAudienceState.test.ts`: added `@vitest-environment
    happy-dom` so localStorage exists; new persistence round-trip
    case using new helper `__resetInMemoryStateForTest` (drops
    `document_` + listeners + initialized without touching
    localStorage). Verifies set → in-memory-reset → re-read
    returns the persisted value.

- **Track BB — Honest audience UI copy.**
  - SettingsSurface section: "Viewer mode" → "View tier";
    `toggle-label` "Audience tier" → "View tier preference";
    `toggle-desc` rewritten to say "view preference — does not
    restrict backend access."
  - `viewerAudienceState.ts` doc comment + `renderViewerAudience()`
    doc comment: explicit framing + pointer to "Option A2" (real
    server-side authorization, out of scope for local single-user).

- **Track EE — Wire-emitter discipline note.**
  - Added entry to `docs/reference/contributing/slice-execution.md`
    Pass 8 static-check list: future emitters MUST emit
    `enum.name()` raw + use FE-canonical discriminator names.

- **Track FF — installDist staleness note.**
  - Added entry to `CLAUDE.md` Common Pitfalls: after Java edits
    in classpath modules, run `./gradlew.bat :modules:ui:installDist`
    before `dev_start`. Symptom: wire payload reflects the
    previous build.

### Verification

- `./gradlew.bat :modules:app-api:test --tests "*SubstrateSchemaGenTest*"`
  green (verify-mode pass against regenerated baselines).
- `./gradlew.bat build -x test` green from main.
- `npm run typecheck` green.
- `npm run test:unit:run` — 1357 tests passing (was 1321 before
  Track CC; +12 from Track CC's new test cases + ~24 from main's
  unrelated slice 517/520 work pulled in via the closing merge).

### What 511-followup-2 explicitly does NOT do

- **Real authorization (Option A2).** Session identity +
  server-side OperationCatalog filtering by audience claim + per-
  request auth check. Substantial slice; the local single-user
  deployment doesn't make this urgent. Track BB's UI copy ensures
  users aren't misled.
- **Branch rename.** `worktree-502-boot-composition` now contains
  502 + 511 + 511-followup + 511-followup-2. Bookkeeping
  follow-up; procedural.
- **`<jf-resource>` vs `<jf-resource-view>` naming collision
  cleanup.** Mechanical but high-touch; defer.
- **Auto-refreshing the worker dist** when Java sources change
  under installDist's task graph. Documented as a pitfall; the
  fix is a Gradle plumbing slice on its own.

### Open items

The named follow-ups from 511-followup remain:
- **511-followup-A**: wire-HealthEvent consumer migration
  (HealthSurface.renderEvents → wire shape). When this ships,
  re-add `HealthEvent` to `WireAggregateKind`, `activity-row` to
  `SurfaceContextKind`, and re-register the strategy.
- **511-followup-C**: `OperationInvocation` aggregate when a
  consumer materializes.

---

## 511-investigation (confidence-building pass, 2026-05-18)

After 511-followup-2 closed, the question of "what comes next?"
needed evidence — several of my prior cost estimates were guesses,
and several "named follow-ups" rested on assumptions I hadn't
verified. This pass investigates without implementing; the output
is a refined estimate table that the next slice can plan against.

### Findings table

| Remaining work item | Prior estimate | Refined finding | Blockers / corrections |
|---|---|---|---|
| **Plugin capability gating** | ~30 lines, easy | ~50-80 lines + design decision | No existing capability-CHECK pattern exists. `PluginCapabilities` describes contribution axes (customElementTags, surfacePorts, surfaces) — there's no precedent for permission claims like `override-core-aggregate-strategy`. Adding the first capability-check is novel substrate, not a tweak. |
| **Provenance-tier badge** | ~30 lines | ~30-60 lines + visual design | `ActionButton.ts` has no slot or badge-rendering markup today (`render()` is button + confirm-row only). Adding a `<span class="badge">` + a `tierBadge` prop + CSS variables is mechanical, but the visual design (when to show it, what tiers warrant a chip, accessibility) is real UX intent. |
| **Surface migration closure** | "4-8 sites" | **2 sites** (HelpSurface: `core.export-diagnostics`; SettingsSurface: `core.reset-settings`) | None — purely mechanical. My memory inflated the count. |
| **511-followup-C OperationInvocation** | "no production consumer" | **Real consumers exist** — `AdvisoryToastHost.ts:290` and `AdvisoryInboxDrawer.ts:436` actively call `JSON.parse(action.defaultArgsJson)` then invoke. The "no consumer" verdict was wrong. | The candidate substrate context is something like `(OperationInvocation, advisory-action)`. The advisory consumers are specialized (toast UX, inbox row UX) — the substrate would have to either replace their specialized rendering or just route the invocation. Worth pursuing; not a 30-line add. |
| **511-followup-A HealthEvent migration** | "design-intensive" | **More urgent than thought.** HealthSurface.renderEvents uses a local interface (`title, message, level, timestamp`). LifecycleSnapshotTap + HeadHealthEventsEmitter actually emit the wire-types.ts `HealthEvent` shape (`severity, source, i18nKey, body`). The SSE-stream cast `payload.conditions as HealthEvent[]` at HealthSurface.ts:601 is a runtime lie — fields are undefined at render time. The "Recent events" section is **currently silently broken**. | Migration isn't just "wrap with `<jf-health-event>`" — needs to either translate the wire shape into something the existing render produces meaningfully, or redesign the event row to match the wire's structured `body` sealed-union (AssertedCondition / LifecycleEvent / ThresholdState). Real design work, ~100-200 lines plus an i18n key audit. Live SSE probe didn't capture data on idle stack; static analysis is conclusive. |
| **Java @FieldUse annotation pipeline** | "feasible / low marginal value" | **Pipeline cost higher than assumed.** `WireTypesTsGenerationTest` was retired in slice 3a-1-8 Phase 4 (2026-05-06); TS gen now reads from `contracts/wire/*.proto`. Operation/Resource are NOT in the proto pipeline — they're hand-written FE types in `api/types/registry.ts`. `@FieldUse` annotations on Java records would (a) reach the JSON Schema baseline via `SubstrateSchemaGenTest` if the schema generator picks up the annotation, (b) NOT reach the FE types automatically. | Requires manual FE-side sync OR extending the schema-gen to surface annotations + a new FE codegen step. Marginal value (the FE-side behavioral test from Track C already does the work) stays low; cost is now medium, not "free." |
| **Lint rule (wire destructuring)** | "high friction; >100 violations likely" | **Low friction.** 21 importers of `api/types/registry` total. Breakdown: 10 substrate-internal (aggregate-substrate dir + tests), 2 state/projection (UserStateDocument, viewerAudienceState), 4 test fixtures, 5 violation-candidates. Inspection of the 5 candidates: ResourceView + resourceViewContract + subscriptionStrategy are substrate-adjacent (allowlist with category); AdvisoryStore is a catalog client (allowlist); SettingsSurface needs Audience for the toggle (allowlist). **Zero true violations on current source.** | Rule would only fire on future code. `no-restricted-imports` precedent already exists in `eslint.config.js` (FSD layer boundaries). Estimated ~30 LOC config change. My prior "high friction" estimate was wrong. |
| **Matrix population ceiling** | "~10-15 cells realistic" | **~4-5 plausible aggregate kinds + 3-4 contexts.** Confirmed consumers exist for: AgentSession (AgentSurface, AgentSessionController, ToolCallCard), AdvisoryClass (AdvisoryClassChrome, AdvisoryToastHost, AdvisoryInboxDrawer), PluginManifest (SettingsSurface plugin section). ConditionState less clear. Contexts: button + list-item exist; activity-row / drawer-row / settings-row / inline-action are plausible. | The matrix is sparser than the design imagined (~80 cells aspirational) but more populated than I estimated (~12-20 honest cells reachable). |
| **Compile-time exhaustiveness probe** | "probably impossible" | **Confirmed impossible cleanly.** TS-Playground probe with mapped-type tuple `AllKeysCovered<T, K>` works for listing-enforcement (forces every key to be named in a tuple) but doesn't enforce reading. Adds nothing over the existing role-record pattern from Track C. | The 511-followup Track C behavioral-test approach IS the practical ceiling. Verdict stands. |
| **Option A2 (server-side auth)** | "wrong architecture" | **Confirmed wrong architecture.** `docs/explanation/02-process-coordination.md:260` is explicit: "Honor system is sufficient for single-user desktop app." Loopback-only binding is doctrinal (`07-ui-host-architecture.md:122`, `12-desktop-installer-and-sandbox-setup.md:170`). Multi-user deployment is not a planned axis. | A2 is not "deferred follow-up"; it belongs to a different product. Track BB's UI copy is the right answer. |
| **Retroactive sweep script** | "modest value" | (Not investigated; unchanged.) | Role records ARE the punch list; a separate script just renders them differently. |

### Key reversals vs my prior tier ranking

- **511-followup-C moved up.** Wasn't blocked on a consumer (consumers exist in AdvisoryToastHost + AdvisoryInboxDrawer). Substrate fit needs design, but the case for shipping it strengthened.
- **511-followup-A moved up — found a live defect.** Not just substrate hygiene; the "Recent events" section in HealthSurface is silently rendering undefined fields. Migration fixes a real bug.
- **Lint rule moved up.** Low friction, low cost; the design's structural-guarantee claim is reachable for less than I estimated.
- **@FieldUse moved down.** Cost is higher than expected (proto pipeline retirement means no clean Java→TS path); value still low.
- **Capability gating moved down.** Novel substrate, not a tweak. ~50-80 lines + a design decision about where capability constants live, what claim format looks like.

### Recommended next-slice candidates (in priority order)

1. **511-followup-A** — fixes a live defect, exercises the substrate on a third aggregate (HealthEvent). High value, ~100-200 lines + an i18n audit. The right slice to ship next.
2. **Lint rule rollout** — low friction, materializes a structural guarantee that's currently convention. ~30 LOC + the small `no-restricted-imports` precedent already in `eslint.config.js`. Worth pairing with whatever slice ships next so the surrounding fingerprint stays clean.
3. **Surface migration closure (HelpSurface + SettingsSurface)** — 2 sites, mechanical. Easy cleanup; closes the "every op-button goes through the aggregate" claim for the views/ directory.
4. **511-followup-C** — needs design (specialized advisory rendering vs substrate fit). Lower urgency than A.
5. **Provenance-tier badge** — needs visual design intent; defer to when the design system has guidance.
6. **Capability gating** — defer until a plugin ecosystem exists.
7. **@FieldUse** — defer indefinitely; marginal value over what's shipped.

### Open observations

- The HealthSurface silent-defect finding (#2 in the table) is worth a one-line entry in `observations.md` regardless of whether the migration ships soon.
- The capability-gating + plugin-axis substrate is itself an instance of "no production consumer" — declared in `aggregateStrategies` axis on `PluginContribution`, never exercised by any plugin (because there are no plugins). C-018 applies here too; worth flagging.

### Verification

- Static analysis only (no code changed). One TS probe file written + deleted under `tmp/`. Dev stack started briefly to attempt a live SSE probe (idle stack didn't emit; static analysis was conclusive).
- All findings cite source by file:line.

## 511-followup-A (HealthEvent consumer migration, 2026-05-18) — closure

The investigation's #1 priority slice. Shipped.

### What landed

1. **Re-added `'HealthEvent'`** to `WIRE_AGGREGATE_KINDS`
   (`aggregateKinds.ts`) and **`'activity-row'`** to
   `SURFACE_CONTEXT_KINDS` (`surfaceContextKinds.ts`). Both were
   removed in Track D for lack of consumer; re-adding them with the
   consumer ships them as substrate that's exercised from day one
   (C-018-compliant by construction).

2. **`healthEventActivityRow` canonical strategy + Pass-8 test**
   (`strategies/healthEventActivityRow.ts` +
   `healthEventActivityRow.test.ts`). All 6 wire fields classified
   `'visual'`; the behavioral Pass-8 mirror mutates each and asserts
   the rendered output diffs. Variant-aware body extraction:
   `'condition'` → `message | reason | status: <s>`; `'lifecycle'` →
   `attributes.message | disposition: <d> | flattened`; `'threshold'`
   → `message | phase: <p> / k=v`.

3. **`<jf-health-event>` aggregate component** (`components/JfHealthEvent.ts`
   + smoke test). Mirrors `<jf-operation>` / `<jf-resource>` shape with
   one key difference — `event` is an inline property (stream-fed),
   not a catalog-fetched id. Light DOM so surrounding surface CSS
   cascades.

4. **`bootstrap.ts`** registers the strategy + side-effect imports
   `JfHealthEvent`. The substrate now has 3 populated cells
   ((Operation, button), (Resource, list-item), (HealthEvent,
   activity-row)).

5. **`HealthSurface.ts` migration**:
   - Deleted the local `HealthEvent` interface (lines 79-87 of the
     pre-followup-A file). The local shape (`title, message, level,
     timestamp:number`) was structurally incompatible with the
     wire emission (`severity, source, i18nKey, body, timestamp:string`).
     Result: every event row rendered with undefined fields.
   - Imported the wire `HealthEvent` from `api/generated/wire-types.js`.
   - `connectedCallback()` calls `bootstrapAggregateSubstrate()` to
     ensure the strategy is registered before the first event arrives
     (idempotent).
   - `renderEvents()` replaced its inline Lit template with a `.map`
     that mounts `<jf-health-event .event=${e}>` per event. The
     strategy owns per-row rendering; the surface owns chrome + empty
     state.
   - Removed the now-unused `formatRelativeIso` import.

### Defect fixed

`HealthSurface.renderEvents()` was silently broken: the local-shape
interface (`title, message, level, timestamp:number`) didn't match the
wire shape (`severity, source, i18nKey, body, timestamp:string`). The
SSE handler at line 601 cast `payload.conditions as HealthEvent[]`,
then `renderEvents` read `e.level`, `e.title`, `e.message`, and a
number-timestamp — all undefined at runtime. Every event row rendered
with empty title, empty message, default `'info'` severity class.
Reported in `docs/observations.md` during the 511-investigation pass.

After this slice, `renderEvents` mounts `<jf-health-event>` per event;
the canonical strategy reads the wire shape directly, applies
variant-aware body extraction, and produces a row with i18n-resolved
title, body-derived message, severity-colored class, relative time,
and source chip. No more undefined field leaks.

### Substrate growth

- **3 populated cells** in the canonical registry: (Operation, button),
  (Resource, list-item), (HealthEvent, activity-row).
- The audit's "substrate-without-consumer" cleanup discipline produced
  the right outcome: HealthEvent was removed in Track D when it had
  zero consumers, and is now back with a real consumer. The two
  round-trip events validate that adding/removing aggregates is cheap
  and reversible — exactly the behavior the substrate was designed
  for.

### Tests

- `healthEventActivityRow.test.ts` — 5 tests: roles record covers all
  6 wire keys; behavioral Pass-8 against each field; AssertedCondition
  `message` extraction; ThresholdState fallback chain; nothing-on-missing-id.
- `JfHealthEvent.test.ts` — 4 smoke tests: row mounts with sample
  event; nothing when event is null; severity reflected in CSS class;
  body-derived message text.
- Full suite: 132 files / 1366 tests green. Typecheck clean.

### Live verification

- Dev stack started; SSE endpoint probed via curl with
  `Accept: text/event-stream` → wire HealthEvent shape verified
  on the live emit path:
  `{id, timestamp:ISO, source:{serviceName,...}, severity:'INFO'|'WARNING'|'ERROR',
  i18nKey, body:{kind:'condition'|..., ...}}`. Three live conditions in
  the snapshot, matching the strategy's mapping exactly.
- The dev-runner serves vite from `F:/JustSearch` (main checkout),
  not from the worktree. Cross-branch live verification therefore
  requires the worktree commits to land on main first. Verified
  post-merge.
- Pre-existing dev-only artifact noticed during probing: HealthSurface's
  fetch-based SSE handler does not send `Accept: text/event-stream`,
  so Javalin's SSE endpoint returns `content-length: 0` to the bundle's
  fetch on this dev configuration (verified via direct curl on both
  port 5173 proxy and 58057 direct). Out of 511-followup-A scope —
  the rendering pipeline is what this slice fixes. Logged to
  `docs/observations.md` separately.

### Files

- `modules/ui-web/src/shell-v0/aggregate-substrate/aggregateKinds.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/surfaceContextKinds.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.ts` (new)
- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts` (new)
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts` (new)
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.test.ts` (new)
- `modules/ui-web/src/shell-v0/aggregate-substrate/bootstrap.ts`
- `modules/ui-web/src/shell-v0/views/HealthSurface.ts`

## 511-followup-B (lint rule rollout, 2026-05-18) — closure

The investigation's #2 priority slice. Shipped.

### What landed

A `no-restricted-imports` rule in `modules/ui-web/eslint.config.js`
that bans direct imports of `api/types/registry` outside the
canonical-consumer chain. Surfaces and other code must mount an
aggregate component (`<jf-operation>`, `<jf-resource>`,
`<jf-health-event>`) instead of destructuring wire records
themselves. The rule materializes the structural-guarantee claim
that was previously convention-based.

### Allowlist (per §511-investigation Track 3)

- `src/shell-v0/aggregate-substrate/**` — substrate-internal
  (canonical-strategy chain itself).
- `src/shell-v0/state/UserStateDocument.ts`,
  `src/shell-v0/state/viewerAudienceState.ts` — state /
  projection mirroring a wire enum.
- `src/shell-v0/components/ResourceView.ts`,
  `src/shell-v0/components/advisory/AdvisoryStore.ts`,
  `src/shell-v0/renderers/resourceViewContract.ts`,
  `src/shell-v0/strategies/subscriptionStrategy.ts` —
  substrate-adjacent (catalog client, view contract,
  subscription strategy).
- `src/shell-v0/views/SettingsSurface.ts` — audience toggle
  needs the `Audience` enum directly.
- `src/**/*.test.{ts,tsx}` + `src/**/__fixtures__/**` +
  `src/shell-v0/hooks/resolvePathLazy.ts` — fixtures legitimately
  construct wire records.

### Wire-types rule tightening

While verifying that the new rule fires correctly, discovered
that the existing `api/generated/wire-types` restriction (slice
3a.1.3) had a `.js`-suffix bypass — patterns matched only
extensionless paths, so any ESM-style `wire-types.js` import slipped
through. Tightened the pattern set to cover the `.js` and `.ts`
suffix variants. Caught 5 violations in followup-A's new files
plus `HealthSurface.ts:27` (after consolidating the rule block so
the registry rule did not silently override the wire-types rule —
see below). Migrated all 6 sites to use the `api/generated`
barrel.

### Eslint flat-config subtlety

Initially structured the registry rule as a second
`no-restricted-imports` config block alongside the wire-types
block. Verified via `eslint --print-config` that flat-config
**replaces** array-typed rule values when multiple blocks match a
file (does not merge). The second block silently discarded the
first rule for any file matching both. Consolidated into a single
block with both pattern groups; the block's `ignores` is the
union of both restrictions' allowlists. Comment in the config
documents the trade-off (substrate-internal exempted from BOTH
rules; benign because the substrate uses the barrel by
convention).

### Verification

- Lint probe `__lint-probe-registry.ts` placed in `views/`
  (not allowlisted) confirms the registry rule fires with the
  intended message.
- Lint probe `__lint-probe-wiretypes.ts` with a `.js`-suffix
  import confirms the wire-types rule fires too. Probes deleted
  after verification.
- `npx eslint src/` on the full source tree: zero
  `no-restricted-imports` errors (pre-existing 6 unrelated errors
  remain).
- `npm run typecheck` clean.
- `npm run test:unit:run` — 132 files / 1366 tests green
  (re-verifies the barrel migration didn't break runtime shape).

### Files

- `modules/ui-web/eslint.config.js`
- `modules/ui-web/src/shell-v0/aggregate-substrate/aggregateKinds.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/components/JfHealthEvent.test.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.ts`
- `modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts`
- `modules/ui-web/src/shell-v0/views/HealthSurface.ts`

## 511-followup-C (surface migration closure, 2026-05-18) — closure

The investigation's #3 priority slice. Shipped. Closes the
"every op-button in `views/` goes through `<jf-operation>`"
claim that the Phase 9 retroactive sweep partially completed.

### What landed

Two remaining direct-button call-sites migrated to mount
`<jf-operation>` instead.

1. **HelpSurface — `core.export-diagnostics`**.
   - Replaced bespoke `<button>` + `handleExport()` + `OperationClient`
     wiring with `<jf-operation operation-id="core.export-diagnostics">`.
   - The export-path post-success display preserved via an
     `@op-success`/`@op-error` listener on a wrapping `<div>`.
     `structuredData.path` is read off the event detail; surface
     state retains only `exportPath` + `exportError`.
   - Removed: `OperationClient` import + ref, `OP_EXPORT_DIAGNOSTICS`
     constant, `exporting` state field, `client()` helper,
     `handleExport()`. Loader-icon / file-down-icon spinning UX
     now driven by ActionButton internals (uniform with the rest
     of the substrate).

2. **SettingsSurface — `core.reset-settings`**.
   - Replaced the `<button @click="resetToDefaults">` + `confirmAsync`
     modal with `<jf-operation operation-id="core.reset-settings">`.
     The wire's `ConfirmStrategy.Inline` policy + `RiskTier.MEDIUM`
     drive inline confirm in ActionButton — same ceremony level,
     uniform UX.
   - `resetToDefaults()` body deleted; replaced by `handleResetSuccess()`
     (refreshes settings) + `handleResetError(e)` (surfaces message).
   - Removed: `OperationClient`/`OperationError` import,
     `clientRef`, `client()` helper, the `resetToDefaults` async
     method body.
   - **UX shift**: the wire policy declares `Audience.OPERATOR`.
     Post-migration, the reset button is gated behind the audience
     toggle (511-followup-2 Track BB). USER-tier viewers won't see
     it; flipping to OPERATOR via SettingsSurface's existing
     audience toggle reveals it. This honors the wire's intent —
     reset-settings is operator-class destructive.

### Substrate enrichment (incidental)

To preserve HelpSurface's export-path display, `OpButton`'s
`op-success` event detail now carries `structuredData` alongside
`message` + `executionId`. Same shape `OperationResult` already
emits on the wire; the substrate just stopped dropping it on the
event boundary. Test in `OpButton.test.ts` asserts the new field.

### What's no longer duplicated

`core.export-diagnostics` is now mounted in two places —
HealthSurface's Quick Actions panel (existing) and HelpSurface
(this slice). Both go through the canonical `(Operation, button)`
cell, so renaming / removing the operation in the catalog touches
zero surface code.

### Verification

- `npm run typecheck` clean.
- `npm run test:unit:run` — 132 files / 1366 tests green
  (including the new `OpButton.test.ts` assertion for
  `structuredData` forwarding).
- `npx eslint src/shell-v0/views/HelpSurface.ts
  src/shell-v0/views/SettingsSurface.ts
  src/shell-v0/components/OpButton.ts` — 0 errors (only
  pre-existing `fetch` warnings remain).
- `./gradlew.bat build -x test` from worktree — green.

### Files

- `modules/ui-web/src/shell-v0/components/OpButton.ts`
- `modules/ui-web/src/shell-v0/components/OpButton.test.ts`
- `modules/ui-web/src/shell-v0/views/HelpSurface.ts`
- `modules/ui-web/src/shell-v0/views/SettingsSurface.ts`

## 511-indirect (post-shipping observations, 2026-05-18)

After followups A/B/C landed and were live-verified, a casting-wide
pass surfaced nine indirect findings — things this session's
substrate work *exposed* but did not tackle. Recorded here so a
future reader sees them without re-running the analysis.

Priorities are ordered by confidence of correctness × user-visible
impact × cost. Items 1–3 are local, small, and load-bearing.
Items 4–5 are user-facing-but-need-design. Items 6–9 are
flag-and-defer.

### 1. SSE `Accept` header missing on `fetch()`-based streams

**Status**: latent bug, observations.md row exists.
**Cost**: ~5 LOC across two methods (one file).
**Files**: `modules/ui-web/src/shell-v0/views/HealthSurface.ts:587`,
`:647`.

`startEventStream()` and `startRecoveryIndexStream()` build an SSE
connection via `fetch()` + `ReadableStream` reader (chosen for
AbortController cancelability). Neither sends
`Accept: text/event-stream`, so Javalin's SSE endpoint negotiates
down to `text/plain; content-length: 0` in the dev configuration
(verified via direct curl on both vite proxy port 5173 and the
direct API port). Production may behave differently if some
intermediary adds the header, but verifying that requires a prod
trace — easier to just fix.

Fix shape:
```ts
const res = await fetch(url, {
  signal: ac.signal,
  headers: { Accept: 'text/event-stream' },
});
```

Same shape both methods. No test changes; the change is invisible
to unit tests (which use stubbed EventSource). Live-verify after.

### 2. `op-success` / `op-error` event details are untyped at the boundary

**Status**: subtle correctness hole shipped this session.
**Cost**: ~15 LOC (export interfaces, update 2 consumers).
**Files**: `modules/ui-web/src/shell-v0/components/OpButton.ts`,
`HelpSurface.ts`, `SettingsSurface.ts`.

`OpButton.dispatchEvent(new CustomEvent('op-success', { detail: {
message, executionId, structuredData } }))` — but the detail
shape isn't exported as an interface. `HelpSurface.handleOpSuccess`
reads `e.detail?.structuredData?.path`. A misspelling (`structureData`)
or stale shape compiles cleanly. Two-shot fix:

- Export `OpSuccessEventDetail` + `OpErrorEventDetail` from `OpButton.ts`.
- Surfaces declare `(e: CustomEvent<OpSuccessEventDetail>)` in
  handler signatures.

### 3. Substrate bootstrap is now duplicated across 4 surfaces

**Status**: footgun, not a bug yet.
**Cost**: ~10 LOC; one cross-cutting edit.
**Files**: `shell-v0/index.ts` (move bootstrap call there) or
`aggregate-substrate/components/*.ts` (move into each component's
constructor / `customElements.define` side-effect).

HealthSurface, HelpSurface, SettingsSurface, and the future plugin
host all call `bootstrapAggregateSubstrate()` in `connectedCallback`.
Idempotent so no runtime damage, but every new surface that mounts
`<jf-operation>` / `<jf-resource>` / `<jf-health-event>` must remember
to bootstrap or it silently renders nothing. Two cleaner options:

(a) **Bootstrap at module-load** — `shell-v0/index.ts` calls
    `bootstrapAggregateSubstrate()` once at the end of imports.
    Surfaces don't need to know.

(b) **Bootstrap on first component creation** — each aggregate
    component's `customElements.define` side-effect calls bootstrap.

(a) is simpler; (b) couples lifecycle to component-load. Prefer (a).

### 4. Audience-gate UX hint is missing

**Status**: user-visible regression; needs design intent.
**Cost**: design + ~10 LOC.
**Files**: `modules/ui-web/src/shell-v0/views/SettingsSurface.ts`,
and any surface that mounts audience-gated `<jf-operation>` instances.

Post-followup-C, `core.reset-settings` is hidden when the viewer
audience is USER. The user has no in-surface affordance that says
"more controls are revealed at OPERATOR tier" — buttons just *aren't
there*. SettingsSurface has the audience toggle (Track BB), but
discoverability of the link between toggle and hidden controls is
zero.

Options:
- Inline helper text near the audience toggle: "OPERATOR view reveals
  destructive actions like Reset settings."
- A count-of-hidden-by-tier indicator: "3 actions hidden at USER
  tier — switch view to see them."
- A "show locked" mode that renders gated buttons grayed-out with a
  tier-required badge.

Each carries product decisions (does showing locked buttons leak too
much info? is the count UI worth the complexity?). Defer until
product input.

### 5. `prepare_delete_data` / `confirm_delete_data` is the substrate's inverse pattern

**Status**: intentional or eligible — unclear; needs decision.
**Cost**: documentation only (decision), OR substrate extension
(if eligible).
**Files**: `modules/ui-web/src/shell-v0/views/SettingsSurface.ts:571-573`
(the existing call-site).

SettingsSurface's "Delete all data" button calls
`mod.invoke('prepare_delete_data')` directly via Tauri's IPC, not
through the OperationCatalog. The substrate's premise is "every
operator-facing destructive action goes through the wire's
OperationCatalog." A Tauri-shell-direct call is the explicit
exception — but the exception isn't documented anywhere.

Two paths:

(a) **Sanction the exception**: add a §discipline note that
    shell-direct operations (Tauri IPC, deep-link handlers,
    OS-level integrations) are intentionally outside the substrate
    and explain why.

(b) **Model it in the catalog**: add `core.delete-all-data` to
    `CoreOperationCatalog` with a `ExecutorTag.TAURI_SHELL` (which
    doesn't yet exist) and route through `<jf-operation>`. Larger
    work; depends on the substrate-vs-Tauri-shell question.

### 6. Lint-rule allowlist is unannotated at the call-sites

**Status**: pure hygiene.
**Cost**: ~14 LOC (one comment per allowlisted file).
**Files**: the 7 allowlist entries in `eslint.config.js`'s 511-followup-B block.

A maintainer who notices a registry-type import in
`SettingsSurface.ts` has no in-file pointer to "this is allowlisted
in eslint.config.js." Adding a one-line comment at each import
referencing the eslint rule's allowlist would make the convention
self-documenting. Stop-the-bleed: any new code in those files that
*adds* registry-type imports stays sanctioned by accident.

### 7. Tempdoc 511 status-at-a-glance is buried

**Status**: doc hygiene.
**Cost**: ~20 LOC at the top of the tempdoc.

This file is now 1700+ lines with six closure sections (followup,
followup-2, investigation, followup-A, B, C). The "what's currently
shipped vs. open?" answer requires reading every closure. A short
status table at the top — three columns (slice, status, link to
closure) — would let a future reader orient in seconds.

### 8. No enforcement of the `jf-` custom-element prefix

**Status**: convention, not enforced.
**Cost**: ~30 LOC (an ArchUnit-style FE test).

All custom elements in this codebase follow `jf-<name>`:
`<jf-operation>`, `<jf-resource>`, `<jf-health-event>`, plus surface
elements. Nothing fails if a future component registers as
`<my-thing>`. A test that scans `customElements.define` calls (or
class `static get tagName`) and asserts the prefix would prevent
drift. Lower priority than #6 because the convention is sticky.

### 9. The audience tier is the only gating axis

**Status**: latent — pressure may not materialize.
**Cost**: re-evaluate the deferred capability-gating slice (large).

The audience enum has four tiers (USER, OPERATOR, AGENT, DEVELOPER).
Some operations have orthogonal requirements ("OPERATOR-with-license",
"DEVELOPER-on-internal-build"). The substrate currently encodes
these by stuffing them into audience or by adding ad-hoc surface-side
checks — both defeat the substrate's claim of being the single
gating point. The deferred capability-gating slice was the answer;
this session's broader deployment of `<jf-operation>` raises the cost
of *not* having it.

Defer until a real call-site demands it. Re-evaluate at the next
audience-related decision.

### Recommended pickup order

1. **#1 (SSE Accept)** — five-line fix, latent bug, has bitten three slices.
2. **#2 (typed events)** — small, prevents silent breakage.
3. **#3 (consolidated bootstrap)** — removes a footgun for future surface authors.
4. **#4 (audience hint)** — needs product input before code.
5. **#7 (status table)** — doc hygiene; pair with whatever ships next.
6. **#6 (allowlist annotations)** — pair with #2 since both touch consumers.
7. **#5, #8, #9** — defer; flagged for future re-evaluation.

### Confidence-raising spike results (2026-05-18)

Four spikes ran to verify or falsify the assumptions behind the
unimplemented items. Results below; the implementation plan for
the next slice can rest on these numbers.

**Spike A — SSE `Accept` header (raised #1 from 95% → 99%).**

Four-way curl probe (vite proxy + direct API, with + without
`Accept: text/event-stream`):

| | proxy | direct API |
|---|---|---|
| no header | `content-length: 0`, `text/plain` | `content-length: 0`, `text/plain` |
| with header | event-stream + live frames | event-stream + live frames |

Vite proxy forwards the header verbatim. Behavior is uniform
between proxied and direct calls. Bug is confined to
`HealthSurface.ts:587, :647` (the two `fetch`-based methods).
`HealthLitView.ts` uses `EnvelopeStream` which wraps native
`EventSource` — `Accept: text/event-stream` is auto-set by the
browser and that path is unaffected. Tauri webview behavior
unverified; cheap to live-verify in a Tauri build after the fix.

**Spike B — Bootstrap consolidation (raised #3 from 70% → 98%).**

Temporarily added a top-level `bootstrapAggregateSubstrate()` call
at the end of the side-effect imports in `shell-v0/index.ts`.

- `npm run typecheck`: clean.
- `npm run test:unit:run`: 132 files / 1366 tests green.
- `npm run build` (vite production): green.
- Probe test in `aggregate-substrate/__spikeB_module_load.test.ts`
  (deleted after spike) imported `shell-v0/index.js` fresh and
  asserted the aggregate registry held ≥3 cells WITHOUT calling
  bootstrap manually. Passed.

Conclusion: ES module evaluation order (depth-first, post-order)
guarantees that all strategy + component modules are fully imported
before the top-level `bootstrapAggregateSubstrate()` runs at the
bottom of `index.ts`. Per-surface `connectedCallback` calls can be
safely removed; the module-load path covers production.

**Spike C — Custom-element prefix test (raised #8 from 65% → 95%).**

Probe test in `__spikeC_prefix.test.ts` (deleted after spike):

- `fs.readdirSync(dir, { recursive: false })` recursion mirrored
  the `theme-coverage.test.ts` pattern; collected ~140 production
  `.ts` files.
- Regex `/customElements\s*\.\s*define\s*\(\s*['"]([^'"]+)['"]/g`
  found 69+ matches.
- **Initial naive regex flagged a false positive**:
  `PluginLoader.ts` had `customElements.define('foo', Foo)` in a
  JSDoc comment example. Comment-stripping via
  `.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '')`
  before matching eliminated the false positive.
- After comment-stripping: 0 violations on current source.

The real test must include the strip-comments pass; the regex
alone is insufficient. Easy to specify; the spike caught the
issue before code shipped.

**Spike D — Audience-UX direction (#4 resolved as defer).**

User picked "Defer indefinitely." The existing SettingsSurface
audience-toggle copy ("switch up for admin or debug workflows")
is sufficient. No further UX work for this slice; #4 is closed as
a deferred-by-design item.

### Revised confidence summary

| Item | Initial | After exploration | After spikes | Status |
|---|---|---|---|---|
| #1 SSE Accept | 95% | 95% | **99%** | ready to ship |
| #2 Typed op-success events | 90% | 97% | 97% | ready to ship |
| #3 Consolidated bootstrap | 70% | 92% | **98%** | ready to ship |
| #4 Audience hint UX | 50% | 50% | **(deferred)** | closed |
| #5 Tauri-direct convention | 40% | 85% | 85% | doc-only, ready |
| #6 Allowlist annotations | 98% | 98% | 98% | ready to ship |
| #7 Status table | 95% | 95% | 95% | ready to ship |
| #8 Prefix-test enforcement | 65% | 80% | **95%** | ready to ship |
| #9 Capability gating | 30% | 30% | 30% | deferred |

## 511-followup-D (§511-indirect batch, 2026-05-18) — closure

The §511-indirect register's 7 ready-to-ship items, plus the two
closed/deferred items, landed as a single slice. Live-verified
against the dev stack on `main` post-merge.

### What landed (7 items)

**#1 — SSE `Accept` header.** Added `Accept: text/event-stream`
to the two `fetch()`-based SSE methods in `HealthSurface.ts`. Live
result on `core.health-surface`: the "Recent events" list now
auto-populates with 3 wire-shape rows (`ERROR/condition`,
`WARNING/condition`, `INFO/condition`) within seconds of mount —
the silent-empty defect from §511-indirect Spike A is gone. This
is the slice's biggest behavioral win: followup-A's HealthEvent
migration is now actually delivering events end-to-end, not just
rendering injected mocks.

**#2 — Typed `op-success` / `op-error` event details.** Exported
`OpSuccessEventDetail` + `OpErrorEventDetail` from `OpButton.ts`;
annotated handler signatures in `HelpSurface.handleOpSuccess` /
`handleOpError` and `SettingsSurface.handleResetError`. Test mock
(`OpButton.test.ts`) updated to assert the typed shape. Live
result: clicking Export Diagnostics with a not-yet-ready worker
fired `op-error` carrying `{message: "Required capability
unavailable: worker-online"}`, which propagated through the
typed handler and rendered in `.export-status.export-error`. The
op-success path with `structuredData.path` is unit-tested but
could not be live-verified — see "Specific blockers" below.

**#3 — Module-load bootstrap consolidation.** Moved
`bootstrapAggregateSubstrate()` from per-surface
`connectedCallback` calls (HealthSurface, HelpSurface,
SettingsSurface) to a single top-level invocation at the end of
`shell-v0/index.ts`. Live result: navigation to all three
surfaces still renders the substrate-mounted controls (6 Quick
Actions on HealthSurface, 1 Export button on HelpSurface, 1 Reset
button on SettingsSurface gated to OPERATOR). Removes the
footgun: new surfaces using the substrate no longer need to
remember the bootstrap call.

**#5 — Tauri shell-direct discipline note.** Added a new section
"Tauri Shell-Direct Operations" to
`docs/explanation/07-ui-host-architecture.md`. Documents the
sanctioned exception (factory reset
`prepare_delete_data`/`confirm_delete_data` needs shell-process
scope) and the criteria a new operation must satisfy to bypass
the OperationCatalog (shell-process scope, planned app exit, or
Head unavailability).

**#6 — Lint allowlist annotations.** Added one-line "Allowlisted
in eslint.config.js — see 511-followup-B" comments at each of the
7 production `api/types/registry` import sites
(UserStateDocument, viewerAudienceState, ResourceView,
AdvisoryStore, resourceViewContract, subscriptionStrategy,
SettingsSurface). Also removed the stale `hooks/resolvePathLazy.ts`
entry from the eslint allowlist — that file doesn't import the
restricted module.

**#7 — Status-at-a-glance table.** Added an 11-row table at the
top of this tempdoc mapping every slice (initial + 6 followups +
investigation + indirect + closed/deferred items) to its status
and closure section. A reader now sees the substrate's current
state in 11 rows instead of scanning 1700+ lines.

**#8 — Custom-element prefix enforcement.** New source-scanning
test at
`modules/ui-web/src/shell-v0/__tests__/customElementPrefix.test.ts`
that walks shell-v0's production `.ts`/`.tsx` files, strips
comments, regex-finds every `customElements.define(...)` call,
and asserts each registered tag uses the `jf-` prefix. The
strip-comments pass eliminates the JSDoc false positive caught
in §511-indirect Spike C (PluginLoader.ts had `'foo'` in an
example block). Sanity check asserts the canonical set
(jf-operation, jf-resource, jf-health-event, jf-shell,
jf-health-surface) is present so a future regex regression
doesn't silently pass.

### Closed / deferred (2 items)

**#4 — Audience hint UX.** User decision in §511-indirect Spike
D: defer indefinitely. The existing SettingsSurface audience-
toggle copy ("switch up for admin or debug workflows") is
sufficient discoverability. No code change.

**#9 — Capability-gating substrate.** Deferred per the tempdoc's
own YAGNI reasoning. The current 4-tier audience enum covers
every existing operation; implementing pre-need substrate is
exactly the C-018 anti-pattern that 511-followup Track D cleaned
up. Re-evaluate when a real consumer materializes. This is not
"deferred to user-driven session" — it is structurally not
needed by anything in the codebase right now.

### Live verification

After merging to `main` and refreshing the dev-runner dist
(`./gradlew.bat :modules:ui:installDist`), started the dev stack
via the `justsearch_dev` MCP tool and probed:

1. **HealthSurface** (`core.health-surface`): rendered 6
   `<jf-operation>` Quick Action buttons (core.reindex,
   core.restart-worker, core.rebuild-index,
   core.clear-failed-jobs, core.export-diagnostics, core.index-gc).
   The "Recent events" section populated with 3 wire-shape rows
   carrying `data-severity`, `data-body-kind`, i18n-resolved
   titles, body-derived messages, and source chips — the
   substrate's HealthEvent path is end-to-end working in dev
   for the first time since the SSE Accept bug was introduced.

2. **HelpSurface** (`core.help-surface`): rendered the
   `<jf-operation operation-id="core.export-diagnostics">` button
   with label "Export Diagnostics", risk LOW. Clicking the button
   fired the operation, which returned `op-error` with `{message:
   "Required capability unavailable: worker-online"}`; the surface's
   typed `handleOpError` handler captured the message and rendered
   it in `.export-status.export-error`. The typed event-detail
   plumbing works end-to-end.

3. **SettingsSurface** (`core.settings-surface`):
   - At `viewerAudience: USER` (set via `setViewerAudience('USER')`
     in console — the in-memory store wins over raw localStorage
     edits, an important detail caught during verification),
     the `<jf-operation operation-id="core.reset-settings">`
     element was mounted but rendered empty — audience gate
     filters operator-tier operations from USER view.
   - At `viewerAudience: OPERATOR`, the same element rendered an
     ActionButton with label "Reset Settings to Defaults", risk
     MEDIUM, `confirm-kind="INLINE"` — the wire policy is the
     ceremony source.

4. **HealthLitView** (verified during exploration): uses native
   `EventSource` via `EnvelopeStream`, not the affected
   `fetch()`-based path. Unaffected by #1.

### Specific blockers

**Worker spawn instability in this dev session.** Three dev-stack
restarts during verification: the first completed start was used
to verify items #1, #2 (op-error path), #3, and the audience gate
end-to-end. After the first stop, subsequent starts hit
`worker.spawn.failed` (lifecycle ERROR) without surfacing a
stderr message — a known dev-runner pain point unrelated to this
slice's code. This blocked live-verification of the
`op-success`-with-`structuredData` path on
`core.export-diagnostics` (would require worker-online to actually
produce a zip and return `structuredData.path`). The path is
covered by `OpButton.test.ts`'s typed `OpSuccessEventDetail`
assertion (mock invoke returns `{path: '/tmp/diag.zip'}`,
listener verifies forwarding). The dispatch mechanism is the same
CustomEvent code path live-verified for op-error.

**Tauri webview behavior on the SSE Accept fix.** Out of scope
for the dev-stack spike (§511-indirect Spike A). The fix should
work in Tauri's webview the same way it works in the dev
browser — both call `fetch()` and headers pass through — but
confirming requires a Tauri binary build + native runtime, which
is not part of this dev session.

### Verification artifacts

- `./gradlew.bat build -x test` from worktree + post-merge from
  main: green.
- `cd modules/ui-web && npm run typecheck`: clean.
- `cd modules/ui-web && npm run test:unit:run`: 1584 tests green
  on main (worktree had 1368; main has additional tests from
  other in-flight slices). The new prefix test adds 2 cases.
- `cd modules/ui-web && npx eslint src/`: zero
  `no-restricted-imports` errors (pre-existing escape/redeclare
  warnings unrelated).

### Files

**Modified**:
- `modules/ui-web/src/shell-v0/views/HealthSurface.ts` (#1, #3)
- `modules/ui-web/src/shell-v0/views/HelpSurface.ts` (#2, #3)
- `modules/ui-web/src/shell-v0/views/SettingsSurface.ts` (#2, #3, #6)
- `modules/ui-web/src/shell-v0/index.ts` (#3)
- `modules/ui-web/src/shell-v0/components/OpButton.ts` (#2)
- `modules/ui-web/src/shell-v0/components/OpButton.test.ts` (#2)
- `modules/ui-web/eslint.config.js` (#6, removed stale allowlist)
- `modules/ui-web/src/shell-v0/state/UserStateDocument.ts` (#6)
- `modules/ui-web/src/shell-v0/state/viewerAudienceState.ts` (#6)
- `modules/ui-web/src/shell-v0/components/ResourceView.ts` (#6)
- `modules/ui-web/src/shell-v0/components/advisory/AdvisoryStore.ts` (#6)
- `modules/ui-web/src/shell-v0/renderers/resourceViewContract.ts` (#6)
- `modules/ui-web/src/shell-v0/strategies/subscriptionStrategy.ts` (#6)
- `docs/explanation/07-ui-host-architecture.md` (#5)

**Created**:
- `modules/ui-web/src/shell-v0/__tests__/customElementPrefix.test.ts` (#8)

## 511-followup-D-patches (analysis-pass closeout, 2026-05-18)

After 511-followup-D shipped, a critical-analysis pass found three
defects and two doc improvements that warranted a coda slice
rather than a fresh tempdoc.

### What landed

**(D) `aggregateRegistry` slot dedup.** Before this patch,
`registerAggregateStrategy` always appended to `_entries` without
deduplication. The registry is module-state that survives Vite
HMR — each HMR reload of bootstrap.ts re-registered the 3 core
strategies, growing the registry unboundedly across dev sessions.
Dispatch behavior remained correct (highest rank, ties →
last-registered wins) but the leak was real. Fixed by introducing
a `sameSlot` predicate keyed on `(aggregate, context, rank, source)`
and splicing any matching prior entry before pushing the new one.
Plugin overrides at distinct rank/source still coexist as before.
New `aggregateRegistry.test.ts` covers 4 cases: same-slot replace,
different-rank coexist, different-plugin coexist, same-plugin-same-
rank replace.

**(B) Prefix-enforcement regex tightening.** The followup-D
prefix test asserted `tag.startsWith('jf-')`, which accepted
`jf-`, `jf-FOO`, `jf--double`, `jf-trailing-`, `jf-1starts`.
Replaced with `/^jf-[a-z](?:[a-z0-9-]*[a-z0-9])?$/`. Added a
positive/negative sub-test enumerating ~10 valid + ~10 invalid
names so the regex contract is pinpointed independent of the
source-scan walk.

**(E) Observations entry.** Logged the viewerAudience
localStorage-vs-store cache trap that cost ~10 min during
followup-D live-verification. Future verifiers should use
`setViewerAudience()` (or the SettingsSurface UI) to flip tiers,
not raw localStorage writes.

**(F) Tempdoc cleanup.** Deleted the "Original framing notes"
paragraph (redundant with the status-at-a-glance table).

### Live-verification of op-success path (closing the followup-D gap)

The followup-D closure documented an unverified op-success path as
a "specific blocker." After investigation,
`ResetSettingsHandler.execute()` has no worker-online gate; it
calls `svc.resetToDefaults()` unconditionally. Live-verified on
`core.settings-surface` at OPERATOR audience:

1. Mounted `<jf-operation operation-id="core.reset-settings">`.
2. Set viewer audience to `OPERATOR` via `setViewerAudience('OPERATOR')`
   (the localStorage trap from (E) above — store API is the source
   of truth).
3. Clicked "Reset Settings to Defaults". The wire's inline confirm
   appeared (Yes / Cancel buttons, per
   `ConfirmStrategy.Inline`).
4. Clicked Yes.
5. Captured `op-success` event detail:
   - `message: "Settings reset to defaults"` (typed `message` field
     of `OpSuccessEventDetail`).
   - `structuredData` populated with `{indexPaths, settingsMode,
     ui, llm}` — proving the typed `structuredData` forwarding
     from #2 works end-to-end through the substrate's CustomEvent
     dispatch.
   - `op-error` did NOT fire.

This closes the followup-D verification gap. The op-success path
is now confirmed live, complementing the op-error path that was
verified during followup-D's HelpSurface probe.

Note: a transient `Failed to fetch` error fired on a first
attempt during the dev-stack takeover (port race after MCP
`takeover: "warn"`). After the second attempt — once the bundle
re-resolved the vite proxy — the success path executed cleanly.
Worth flagging that dev-stack takeover briefly invalidates the
FE's API endpoint cache; documented here as a verification-time
observation, not a substrate bug.

### Verification artifacts

- `npm run typecheck`: clean.
- `npm run test:unit:run`: 134 files / 1373 tests green on
  worktree; 150 / 1589 green on main post-merge (new tests: 4
  registry-dedup + 1 prefix-regex case).
- `npx eslint src/`: zero `no-restricted-imports` errors.
- `./gradlew.bat build -x test`: green from worktree + post-merge
  from main.
- Live-verify: op-success path with `structuredData` forwarded
  through `OpSuccessEventDetail` (described above).

### Files

**Modified**:
- `modules/ui-web/src/shell-v0/aggregate-substrate/aggregateRegistry.ts` (D)
- `modules/ui-web/src/shell-v0/__tests__/customElementPrefix.test.ts` (B)
- `docs/observations.md` (E)
- `docs/tempdocs/511-aggregate-surfacing-substrate.md` (F + closure)

**Created**:
- `modules/ui-web/src/shell-v0/aggregate-substrate/aggregateRegistry.test.ts` (D)

## 511-future-directions (research-backed ideas catalog, 2026-05-18)

After 511-followup-D-patches closed, an open-ended research pass
explored what the substrate could theoretically enable. Four web-
research rounds plus internal synthesis. This section is **ideas
only — no code shipped**. Each idea cites the research that
surfaced it; each is concrete enough that a future slice can pick
it up without re-running the research.

The catalog is unordered — all items are viable; "no rush" per
the prompt that initiated the research. Pickup order depends on
which axis (security hardening, UX polish, developer experience,
new features, simplification) the next session prioritizes.

### Research log

**Round 1 — Plugin capability systems.** Three reference points:

- **VS Code (cautionary).** Declarative contribution points with
  *zero runtime enforcement*. Extensions run with the same
  privileges as VS Code itself; can read any file, open any port,
  modify any configuration. The architecture matches JustSearch's
  current `aggregateStrategies` axis — declarative-only. VS Code's
  ongoing discussion threads (microsoft/vscode#52116, #59756,
  #102894) treat this as load-bearing security debt.
- **Figma (working model).** Declarative `permissions` +
  `networkAccess` allowlist + WASM-compiled JavaScript VM sandbox.
  Plugin code can't see `XMLHttpRequest`, `fetch`, `setTimeout`,
  or DOM by default; must request explicitly via `figma.showUI()`
  iframe. Two-tier model: declarative manifest + runtime
  enforcement.
- **Chrome Manifest V3.** Separated API `permissions` from
  `host_permissions`. Some are install-time-granted; host-access
  is runtime-granted per-site with safe defaults. Suggests UI
  overrides (install-time fine) and data access (runtime confirm)
  warrant different shapes.

**Round 2 — Audience UX (hidden vs disabled).** Industry
consensus (Smashing, IxDF, UXPsychology, MS Windows guidelines):

- **Hide** when user has no path to access (safety/security).
- **Disable + tooltip** when user *could* gain access. The
  disabled control informs them the feature exists; the tooltip
  tells them how to enable it.

Direct implication for §511-indirect #4 (audience-hint UX,
closed as deferred indefinitely): JustSearch's "hide at lower
audience tier" pattern *does not match* the canonical industry
pattern. The user can toggle their own audience in SettingsSurface;
hiding is wrong by the framework's own framing ("view preference,
not access control" per Track BB). Disable-plus-tooltip is the
research-supported answer.

**Round 3 — Wire-shape rendering substrates in other frameworks.**

- **React Aria / Radix Primitives.** Headless components — behavior
  + accessibility hooks, no rendering. The *opposite* of
  JustSearch's substrate. More flexible but loses the
  "single canonical visual treatment per cell" guarantee.
- **Strapi custom fields.** Discriminated union keyed on
  `__component` + component registry. *Same shape* as
  JustSearch's `WireAggregateKind → strategy`, but at field
  granularity. The tempdoc explicitly rejected field-level
  decomposition (§"Why the obvious framing is wrong"); Strapi
  validates the rejection — they end up needing schema-driven
  composition above the field layer for actual UX.
- **Hotwire Turbo Stream.** Nine canonical actions (append /
  prepend / before / after / replace / update / remove / morph /
  refresh) with custom-action extensibility via
  `turbo:before-stream-render` event override. Suggests a model
  for plugin overrides that's narrower than "replace the whole
  strategy": expose a small set of *composition* actions
  (decorate, augment, wrap) that plugins can register against.
- **`<turbo-stream-source>` connectedCallback pattern.** Same as
  JustSearch's `<jf-operation>` lifecycle. Confirmed-canonical
  approach in the Lit/web-components ecosystem.

**Round 4 — Custom-element tooling.**

- **Custom Elements Manifest** (`custom-elements.json`, schema
  v2.1.0). File format describing custom elements: properties,
  attributes, methods, slots, CSS shadow parts, CSS custom
  properties, inheritance. Auto-generated by the Custom Elements
  Analyzer (Lit / Stencil / FAST supported). Storybook integrates
  automatically. IDEs use it for autocomplete.
- **Adobe Commerce `uiRegistry`** + ServiceNow Next Experience
  Inspector + Storybook patterns. Common shape: tree-view of
  registered components + properties panel + state panel. The
  `getRegisteredCells()` function already implements the data
  layer; only the visualization is missing.

### Ideas catalog

Grouped by what each unlocks.

#### A. Visual / UX features the substrate already has the data for

A1. **Provenance-tier badge.** `Operation.provenance.tier`
(CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN) is in every catalog
entry, surfaced nowhere. A small chip in the
`(Operation, button)` strategy renders the tier. Investigation
estimate: ~30-60 LOC + visual design.

A2. **"Affects" tooltip.** `Operation.lineage.affects:
ResourceRef[]` lists resources an op modifies (e.g.,
`core.rebuild-index` affects the search index Resource). Hover-
tooltip on the button: "this operation affects: <Resource[]>."
Tempdoc §Problem explicitly called this out as a D3 defect.

A3. **Risk-tinted button styling sweep.** The Track E casing
alignment made `[data-risk='HIGH']` selectors match wire values.
A CSS sweep would verify every risk tier (HIGH/MEDIUM/LOW) has
distinctive treatment.

A4. **Resource live-indicator.** `Resource.subscription.mode:
SSE_STREAM | POLLING | ONE_SHOT`. SSE_STREAM resources could
render a small pulsing dot in `(Resource, list-item)`. Helps
users understand which data is live.

A5. **Executor-filtered surfaces.** `Operation.executors:
Set<UI | AGENT | CLI>`. A command-palette context
`(Operation, palette-row)` could filter "show me only operations
the agent can invoke" or "only UI-callable operations." Wire
data unused.

A6. **Audience-gate disclosure (#4 reversal candidate).**
Industry pattern (round 2 research) supports **disable + tooltip**
for tier-gated controls, not hide. User chose defer indefinitely
during 511-followup-D's Spike D — worth re-evaluating with the
research. Two implementations:
- *Render-locked-with-tooltip mode.* Substrate gains a third
  return state: `{kind: 'locked', requiredAudience: 'OPERATOR'}`.
  Aggregate components render disabled with hover/click tooltip.
- *Inline-helper-text mode.* SettingsSurface's audience toggle
  gains "Operator (N more actions)" labels with counts derived
  from the registered cells × current audience.

#### B. Substrate extensions

B1. **More populated cells.** Investigation Track 8 listed
plausible aggregate kinds with real consumers:
- `AgentSession` — AgentSurface, AgentSessionController,
  ToolCallCard. A `(AgentSession, sidebar-row)` cell.
- `AdvisoryClass` — AdvisoryClassChrome, AdvisoryToastHost,
  AdvisoryInboxDrawer. Currently destructures
  `OperationInvocation.defaultArgsJson` directly. A
  `(OperationInvocation, advisory-action)` cell would close the
  followup-C gap.
- `PluginManifest` — SettingsSurface plugin section.
- `ConditionState` — recovery overlay workflows.

B2. **More contexts.** Tempdoc declared 10 in the design, only
3 are populated (button, list-item, activity-row). Pending
contexts with real candidates: `palette-row` (when a command
palette ships), `history-entry` (operation history list),
`inspector-tab` (full detail panel), `badge-strip` (chip
ensemble in toolbars), `notification-toast` (advisory surface).

B3. **Capability-gated plugin overrides — Figma model adopted.**
Closes the substrate's single load-bearing false promise. Design:
- Plugin manifest declares `aggregateOverrides: ["Operation/button",
  "Resource/list-item"]` (which cells the plugin may touch).
- Capability bundle includes named claims:
  `override-core-strategy`, `theme-only`, `high-risk-rendering`.
- Substrate's `registerAggregateStrategy` consults a capability
  enforcer at registration time; rejects plugin sources lacking
  the claim.
- User confirms `override-core-strategy` grants at install time
  (Figma model). `theme-only` is install-grantable. Anything
  affecting destructive operations (high-risk) is runtime-
  confirmed (MV3 model).

B4. **Composition-action extensibility (Hotwire-inspired
alternative to B3).** Instead of plugin replacement of whole
strategies, expose a small set of decoration actions plugins can
register: `decorate-with-badge`, `wrap-with-tooltip`, `append-suffix`,
`prepend-prefix`. Lower-trust path that limits how much a plugin
can change. Composable; lower security surface than full
overrides. Worth comparing trade-offs vs B3 in a real design
slice.

B5. **`@FieldUse` Java annotation.** Tempdoc Design point 7.
Investigation Track 6 deferred (cost medium, value low). Still
on the table as a "land when convenient" addition; would close
the structural-prevention claim on the Java side.

#### C. Developer experience

C1. **Custom Elements Manifest for `<jf-*>` components.**
Auto-generated via Lit analyzer (round 4 research). Outputs
`custom-elements.json` describing every JF component:
properties, slots, events, CSS shadow parts. Powers:
- IDE autocomplete (VS Code, JetBrains).
- Storybook auto-config.
- Plugin-author documentation (when plugin ecosystem ships).
- Future automated `@FieldUse`-style checks (the manifest can
  list which wire types each component consumes).

C2. **Substrate inspector surface.** A developer-tier-only
surface that renders the substrate state. Tree view of registered
cells (by aggregate × context), source attribution (core /
plugin / which plugin), rank visualization, count of consumers
per cell, last-dispatched-strategy diagnostic. Live re-renders
on registry change. Pattern: ServiceNow Inspector + Storybook
sidebar (round 4 research). Reads from existing
`getRegisteredCells()`; UI-only addition.

C3. **`window.__jfSubstrate` browser-console handle.**
Adobe Commerce's `uiRegistry` pattern (round 4 research). ~5 LOC
to expose `getRegisteredCells`, `dispatchAggregateStrategy`,
and the registry's clear/reset helpers under a namespaced global
for ad-hoc DevTools inspection. Dev-build only.

C4. **Storybook stories for every aggregate component.** With
sample wire data per cell. Plugin authors writing custom
strategies get instant feedback. Pairs with C1.

C5. **Retroactive sweep script (deferred at design time).**
Tempdoc §"Out of scope" deferred this. The per-strategy unit
tests already enforce it; a script would render the same data
differently (CI dashboard, weekly drift report). Low marginal
value over what's shipped.

#### D. New UX features built on top

D1. **"What can I do here?" overlay.** Contextual command
palette filtered by current viewer-audience + executor.
Implementation: `(Operation, palette-row)` cell + a surface that
mounts every operation the substrate's audience gate allows.
Substantively different from a generic command palette because
it uses the substrate's structural knowledge.

D2. **Recovery action inline rendering.** `Operation.policy` has
recovery-related fields (`recoveryFor`, etc., already used by
ConditionRecoveryIndex). A health-event row could carry inline
"Recommended fix: <jf-operation context='recovery-action'
operation-id='...'>" using a new context cell. Replaces the
ConditionRecovery overlay's bespoke rendering.

D3. **Agent operation transparency.** When the agent invokes an
operation, the agent surface renders the invocation via
`(OperationInvocation, agent-action)` cell — same visual
treatment the user sees when they click the button themselves.
Identical mental model; builds trust. Pairs with B1's
`OperationInvocation` cell.

D4. **Session audit timeline surface.** A surface listing every
operation invocation in the current session with provenance,
audience, outcome (success / failure), structuredData summary.
Substrate-mounted: each row is a `(OperationInvocation,
history-entry)` cell. Replaces ad-hoc debugging dialogs.

D5. **Plugin theme marketplace** (post-B3 capability gating).
Plugins ship `(Operation, button)` overrides + theme tokens.
Capability-gated under `theme-only`. User previews before
installing. Concrete realization of the design's "plugin
contribution is first-class but capability-gated" guarantee.

#### E. Code simplification

E1. **EnvelopeStream-everywhere migration.** HealthSurface's
fetch-based SSE methods (`startEventStream`,
`startRecoveryIndexStream`) were the source of the §511-indirect
#1 latent bug. Native `EventSource` (which `EnvelopeStream`
wraps) auto-sends `Accept: text/event-stream`. Migrating to
`EnvelopeStream` eliminates the bug class entirely. Investigation
already confirmed HealthLitView uses EnvelopeStream and is
unaffected by #1; pattern is proven.

E2. **HMR-survival registry pattern as a shared util.** The
`sameSlot` predicate + replace-on-equal logic in
`aggregateRegistry.ts` is generally useful — themes, plugin
contributions, theme tokens. Extract to a shared helper like
`registerWithDedup(arr, entry, sameSlot)` so other module-state
registries don't reinvent it.

E3. **Bootstrap-at-module-load convention codified.** Right now
it's custom code in `shell-v0/index.ts`. Could become a
project-wide convention: "any module registering substrate cells
must side-effect-export its bootstrap from `index.ts`." Pairs
with a lint rule that flags `connectedCallback`-based bootstrap
calls in surfaces.

### Notes for the next picker

- **Quickest wins (by structural payoff per LOC):** A6 (audience
  reversal), A1 (provenance tier), A2 (affects tooltip). All
  use data already in the catalog; pure rendering changes.
- **Biggest open promise:** B3 (capability gating). Closes the
  substrate's load-bearing security claim. Largest design surface
  among the candidates here.
- **Highest cleanup yield:** E1 (EnvelopeStream migration).
  Eliminates the bug class that bit followup-A through
  followup-D.
- **Plugin-ecosystem-readiness:** C1 + B3 together. CEM gives
  plugin authors the substrate's API; capability gating gives
  the substrate something to enforce when they call it.

### Sources

Round 1 — Plugin capability systems:
- `https://code.visualstudio.com/api/references/contribution-points`
- `https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security`
- `https://www.figma.com/plugin-docs/manifest/`
- `https://www.figma.com/blog/an-update-on-plugin-security/`
- `https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions`
- `https://code.visualstudio.com/blogs/2022/11/28/vscode-sandbox`

Round 2 — Audience UX:
- `https://www.smashingmagazine.com/2024/05/hidden-vs-disabled-ux/`
- `https://ixdf.org/literature/topics/progressive-disclosure`
- `https://uxpsychology.substack.com/p/hidden-vs-disabled-states`
- `https://learn.microsoft.com/en-us/windows/win32/uxguide/ctrl-progressive-disclosure-controls`

Round 3 — Rendering substrates:
- `https://blog.logrocket.com/headless-ui-alternatives/`
- `https://strapi.io/blog/strapi-v5-dynamic-zones-react-type-safe-rendering`
- `https://turbo.hotwired.dev/reference/streams`
- `https://martinfowler.com/articles/headless-component.html`

Round 4 — Tooling:
- `https://custom-elements-manifest.open-wc.org/`
- `https://github.com/webcomponents/custom-elements-manifest`
- `https://developer.adobe.com/commerce/frontend-core/ui-components/concepts/registry`
- `https://storybook.js.org/addons/wc-storybook-helpers`
