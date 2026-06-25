---
title: "509 — Operation Label Coherence"
---

# 509 — Operation Label Coherence

**Date**: 2026-05-17
**Status**: done
**Closed**: 2026-05-19
**Absorbs**: tempdoc 504 finding F-3 (Reindex x 4 labels), defect class D2
(same-capability-different-name), and the "label registry" discipline fix
from 504 action A-5.

**Closure note (2026-05-19)**: Phase 2 shipped as `<jf-op-button>` in
`modules/ui-web/src/shell-v0/components/OpButton.ts`. The component
accepts only an `operation-id` prop — label / risk / confirmation
strategy resolve from `OperationCatalog` + i18n catalog via
`localizeResourceKey(getOperation(opId).presentation.labelKey)`,
wrapping `<jf-action-button>` for the risk-driven confirmation UX.
The header docstring states: *"There is no `label` prop — the label
comes from the catalog, always. This makes the hardcoded-label path
structurally unreachable."* The 504 F-3 symptom (four different
"Reindex" labels on the same operation) is closed at the component
level. One residual "Reindex" string remains in `HelpSurface.ts:71`
prose ("If search results look stale, use \"Reindex\" in Library …"),
which is documentation, not an operation button.

---

## Problem

Operation labels are hardcoded as string literals in each surface's Lit
template. The same operation appears under different names on different
surfaces. Users cannot predict which button does what; agents have already
shipped functional bugs because of it (503: BrainSurface called
`core.bulk-reindex` instead of `core.rebuild-index` because the names
were confusing).

### Concrete symptoms (F-3)

| Operation ID | Backend label | Surface | Hardcoded label |
|---|---|---|---|
| `core.reindex` | "Reindex Now" | HealthSurface | "Reindex" |
| `core.reindex` | "Reindex Now" | LibrarySurface | "Reindex All" |
| `core.bulk-reindex` | "Bulk Reindex Corpora" | HealthSurface | "Bulk reindex" |
| `core.rebuild-index` | "Rebuild Index" | BrainSurface | "Force Rebuild Index" |

Four labels for three operations, none matching the backend catalog.
No surface reads labels from the i18n catalog.

### Why it recurs (D2 structural cause)

Slice-by-slice feature shipping with no cross-surface naming gate. Each
surface author independently writes a button label string. The backend
has a canonical label for every operation (`registry-operation.en.properties`),
but the frontend never fetches this namespace, so the infrastructure
that could enforce consistency is dormant.

### Functional duplication

`core.bulk-reindex` and `core.rebuild-index` call the same backend method
(`IndexingService.startMigration`). The only difference: `bulk-reindex`
declares a `corpusIds` parameter (ignored in v1), `rebuild-index` takes
none. `rebuild-index` exists solely as a parameterless recovery target
for the condition-recovery-index. In v1, exposing both to users is
confusing — they do the same thing under different names.

---

## Existing infrastructure audit

The infrastructure for catalog-driven operation rendering is 80% complete.
The missing pieces are small but load-bearing.

### What works correctly

| Component | What it does | File |
|---|---|---|
| `CoreOperationCatalog` | Declares every operation with `labelKey`, `descriptionKey`, `riskTier`, `confirmStrategy` | `app-services/.../CoreOperationCatalog.java` |
| `registry-operation.en.properties` | Canonical English labels for all operations | `app-api/src/main/resources/messages/` |
| `/api/messages/registry-operation/en` | Backend endpoint serving the i18n catalog | `LocalApiServer.java` |
| `bootOperationRegistry()` | Fetches operation catalog JSON structs at boot | `OperationCatalogClient.ts` |
| `getOperation(id)` | Synchronous lookup → `Operation` with `presentation.labelKey` | `OperationCatalogClient.ts` |
| `<jf-action-button>` | Risk-tier confirmation UX (LOW: immediate, MEDIUM: "are you sure?", HIGH: typed confirm), pending state, risk-driven styling | `ActionButton.ts` |
| `<jf-row-actions>` | Reads `resource.itemOperations`, resolves labels via `localizeResourceKey(op.presentation.labelKey)`, wires to `OperationClient` | `RowActions.ts` |
| `wireActionButton()` | Imperative helper: connects an `<jf-action-button>` to `OperationClient`, manages pending, handles errors | `wireActionButton.ts` |
| `OperationClient` | Wire transport: `POST /api/operations/{id}/invoke`, confirmation tokens, error normalization | `OperationClient.ts` |
| `localizeResourceKey()` | Synchronous i18n lookup against in-memory `coreCatalog` with raw-key fallback | `resourceCatalog.ts` |

### What is missing (two wires)

**Wire 1: i18n fetch.** `i18n.ts` calls `bootOperationRegistry()` (catalog
structs) but never calls a `bootOperationMessageCatalog()` equivalent to
fetch `GET /api/messages/registry-operation/en` into `coreCatalog`. Without
this, `localizeResourceKey('ops.reindex.label')` returns the raw key.
Pattern exists: `bootSurfaceCatalog()` and `bootHealthEventsCatalog()` do
exactly this for their namespaces.

**Wire 2: surface consumption.** `HealthSurface`, `BrainSurface`,
`LibrarySurface`, and `SettingsSurface` render operation buttons as raw
`<button>` elements with hardcoded labels. They bypass `getOperation()`,
`localizeResourceKey()`, `<jf-action-button>`, and `OperationClient`
entirely. Each surface has its own `invokeOp()` method, its own busy-state
map, and its own (or absent) confirmation UX. The catalog-driven pipeline
(`<jf-row-actions>` → `<jf-action-button>` → `wireActionButton` →
`OperationClient`) is only used for TABULAR Resource item operations.

---

## Design

### Principle

Make it structurally impossible to render an operation button without
going through the catalog. The label, risk tier, and confirmation
strategy should be properties of the operation, not of the surface
that renders it.

### Component: `<jf-op-button>`

A thin catalog-resolving wrapper around `<jf-action-button>`. Accepts
only an operation ID; resolves everything else from the registry.

```
<jf-op-button operation-id="core.reindex"></jf-op-button>
```

Responsibilities:
- On `connectedCallback`: subscribe to `onOperationCatalogChange`
- Resolve `getOperation(id)` → read `presentation.labelKey`,
  `policy.risk`, `policy.confirm`
- Pass resolved label via `localizeResourceKey(labelKey)` to the
  inner `<jf-action-button>`
- Pass `risk` to the inner button (drives confirmation UX automatically)
- Wire `action-invoke` event to an `OperationClient` via
  `wireActionButton()` or inline
- Accept optional `args` property for operations that need parameters
- Accept optional `api-base` attribute (inherited or explicit)
- Fire `op-success` / `op-error` events for the host surface to handle

What `<jf-op-button>` does NOT do:
- Accept a `label` prop. The label comes from the catalog, always.
- Accept a `risk` prop. The risk comes from the catalog, always.
- Implement its own confirmation UX. That's `<jf-action-button>`'s job.

This makes the hardcoded-label path structurally unreachable: there is
no prop to pass a label string. If you want a button for an operation,
you use `<jf-op-button operation-id="...">` and the catalog determines
the label.

### Operation consolidation (v1)

In v1, `core.bulk-reindex` and `core.rebuild-index` are functionally
identical. The design should:

- Keep `core.rebuild-index` as the user-facing operation for full
  index rebuilds. Label: **"Force Rebuild"**. Risk: HIGH.
- Keep `core.bulk-reindex` in the catalog for future corpus-scoped
  rebuilds, but set `Audience.AGENT` (not `Audience.USER`) so it
  does not appear in user-facing surfaces. When corpus-scoped
  migration lands, it can be promoted back to `Audience.USER`.
- `core.reindex` stays as the lightweight incremental operation.
  Label: **"Reindex"**. Risk: LOW.

This reduces the user-visible set from 3 → 2 operations with clear
names and distinct risk levels.

### Surface migration

Each bespoke surface replaces its hardcoded `<button>` elements with
`<jf-op-button>`. Example for HealthSurface:

Before:
```html
<button ?disabled=${!!this.busy['reindex']}
        @click=${() => void this.invokeOp('core.reindex', 'reindex')}>
  Reindex
</button>
```

After:
```html
<jf-op-button operation-id="core.reindex"
              api-base=${this.apiBase}
              @op-success=${() => this.refresh()}>
</jf-op-button>
```

The surface no longer owns the label, the busy state, the confirmation
UX, or the dispatch logic. It only reacts to outcomes.

### Backend i18n label updates

`registry-operation.en.properties` labels should be updated to match
the consolidated naming:

| Key | Current | Proposed |
|---|---|---|
| `ops.reindex.label` | "Reindex Now" | "Reindex" |
| `ops.rebuild-index.label` | "Rebuild Index" | "Force Rebuild" |
| `ops.bulk-reindex.label` | "Bulk Reindex Corpora" | (unchanged — agent-only) |

---

## Implementation phases

### Phase 1: Wire the i18n fetch

Add `bootOperationMessageCatalog(baseUrl)` to `i18n.ts` and
`resourceCatalog.ts`, fetching `GET /api/messages/registry-operation/en`
into `coreCatalog`. Same pattern as `bootSurfaceCatalog` and
`bootHealthEventsCatalog`. After this phase, `localizeResourceKey('ops.reindex.label')`
returns "Reindex" instead of the raw key. All existing catalog-driven
consumers (`<jf-row-actions>`, Activity table `deriveOperationLabel`)
immediately benefit.

### Phase 2: Build `<jf-op-button>`

New component in `shell-v0/components/`. Wraps `<jf-action-button>` +
`wireActionButton`. No label prop. Catalog-resolving. Fires `op-success`
/ `op-error`. Test: render `<jf-op-button operation-id="core.reindex">`,
verify it shows "Reindex" with LOW risk styling and no confirmation dialog.

### Phase 3: Migrate surfaces

Replace hardcoded `<button>` elements in `HealthSurface`, `BrainSurface`,
`LibrarySurface`, and `SettingsSurface` with `<jf-op-button>`. Remove
per-surface `invokeOp()` methods, `busy` maps, and `confirmAsync()` calls.
Each surface keeps only its outcome handlers (`@op-success`, `@op-error`).

### Phase 4: Operation consolidation

- Set `core.bulk-reindex` audience to `AGENT` in `CoreOperationCatalog`.
- Remove the `core.bulk-reindex` button from HealthSurface (it's
  redundant with `core.rebuild-index` in v1).
- Update `registry-operation.en.properties` labels.

### Phase 5: Discipline gate

Add to the Pass-8 brief in `slice-execution.md`: *if a slice ships a
surface that renders an operation button, verify it uses `<jf-op-button>`
(not a raw `<button>` with a hardcoded label).* This is the structural
prevention — the D2 defect class cannot recur because the only way to
render an operation button is through the catalog.

---

## What this tempdoc does NOT cover

- F-22 (cross-surface capability fragmentation: Ask x 3 entry points) —
  separate product design question about which chat entry point is
  canonical.
- F-25 (Simple/Advanced scope collision between Brain and Settings) —
  naming decision for non-operation affordances.
- A-1 (F1 palette) — the palette as universal operation discovery is
  a separate feature that benefits from but does not depend on this work.
- A-6 (risk-tier visual rendering at affordance) — `<jf-op-button>`
  inherits `<jf-action-button>`'s risk styling automatically, so A-6's
  risk-tier goals are partially addressed as a side effect.

---

## Appendix: Evidence chain

### The 503 bug (naming confusion → wrong operation)

BrainSurface originally called `core.bulk-reindex` for Force Rebuild.
`bulk-reindex` requires `corpusIds` (declared in schema, ignored in v1).
BrainSurface had no corpus selection UI, so it passed no args. The fix
(503 sweep) switched to `core.rebuild-index` (parameterless). Root cause:
three similar-sounding operations with no catalog-driven rendering made
it easy to pick the wrong one.

### Handler identity proof

Both `BulkReindexHandler` and `RebuildIndexHandler` call the identical
`IndexingService.startMigration(reasonString)`. The only difference is
the declared parameter schema (`corpusIds` vs none) and the reason
string. In v1, they are functionally identical.

### Existing correct pattern (RowActions)

`<jf-row-actions>` in `RowActions.ts` resolves labels via
`localizeResourceKey(getOperation(opId).presentation.labelKey)`,
reads risk from `op.policy.risk`, and renders `<jf-action-button>`
with catalog-driven props. This pattern is proven and tested — it
just needs to be the only path, not one of two.
