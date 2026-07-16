// SPDX-License-Identifier: Apache-2.0
// Tempdoc 742: the Lingui bootstrap (locale detection, catalog activation,
// `src/locales/**` .po/.mjs catalogs) was removed — a verified clean
// extraction found zero t()/msg()/Trans usage anywhere in src/ (the Lit
// shell-v0 rewrite never adopted lingui macros). This file now only boots
// the backend-served message catalogs (error/resource/surface/health-event/
// operation/workflow), which were always locale-agnostic (`/en`-only,
// tempdoc 434) and independent of the lingui runtime.
import { resolveApiEndpoint } from "./api/http";
import { bootErrorCatalog } from "./i18n/errorCatalog";
import { bootResourceCatalog, bootSurfaceCatalog, bootHealthEventsCatalog, bootOperationMessageCatalog, bootWorkflowCatalog } from "./i18n/resourceCatalog";
// Slice 3a.1.9 §B.B.B D3: registry catalog boot moved here from
// HealthView so any future <jf-resource-view> mount works regardless
// of route. Renamed bootResourceCatalog → bootResourceRegistry (D4)
// to disambiguate from the i18n companion above.
import { bootResourceRegistry } from "./api/registry/ResourceCatalogClient";
import { bootOperationRegistry } from "./api/registry/OperationCatalogClient";
// Slice 448 phase 5: DiagnosticChannel — fourth registry primitive.
import { bootDiagnosticChannelRegistry } from "./api/registry/DiagnosticChannelCatalogClient";
// Slice 449 phase 5: Surface Manifest — second Manifest tier alongside Plugin.
import { bootSurfaceRegistry } from "./api/registry/SurfaceCatalogClient";
import { bootConversationShapeRegistry } from "./api/registry/ConversationShapeCatalogClient";
// Tempdoc 511 — aggregate-substrate core strategy registration. Runs
// synchronously at module load; no fetch needed (strategies are
// compiled-in). Side-effect import ensures registration happens
// before any <jf-operation> mount.
import { bootstrapAggregateSubstrate } from "./shell-v0/aggregate-substrate/bootstrap";
bootstrapAggregateSubstrate();

// Background-fetch the backend message catalogs. Runs async; UI mounts immediately
// without waiting. Until each catalog arrives, lookups fall back to the raw key
// or wire message per tempdoc 434 §3.
//
// Slice 3a.1.4b: extends the boot sequence with the registry-resource catalog so
// HealthLitView can resolve MetricRef.label keys without dragging Lingui into Lit.
if (typeof window !== "undefined") {
  resolveApiEndpoint()
    .then((endpoint) => {
      if (endpoint.baseUrl) {
        return Promise.all([
          bootErrorCatalog(endpoint.baseUrl),
          bootResourceCatalog(endpoint.baseUrl),
          bootSurfaceCatalog(endpoint.baseUrl),
          bootHealthEventsCatalog(endpoint.baseUrl),
          // Slice 3a.1.9 §B.B.B D3: registry catalog boot at app
          // startup so any <jf-resource-view> mount on any route
          // resolves catalog entries (was lazy-bound in HealthView
          // only).
          bootResourceRegistry(endpoint.baseUrl),
          bootOperationRegistry(endpoint.baseUrl),
          // Slice 448 phase 5: fourth primitive's catalog (DiagnosticChannel).
          bootDiagnosticChannelRegistry(endpoint.baseUrl),
          // Slice 449 phase 5: Surface Manifest catalog. Manifests are the
          // second tier alongside primitives — they compose primitives into
          // chrome affordances. V1 ships one entry: core.library-surface.
          bootSurfaceRegistry(endpoint.baseUrl),
          // Slice 491 §9.D Phase E (C0): ConversationShape catalog — the
          // Manifest tier for LLM-output flows. V1 ships 6 shapes (agent,
          // navigate-chat, ask, summarize, batch-summarize,
          // hierarchical-summarize). Consumed by <jf-chat-shape-mount>.
          bootConversationShapeRegistry(endpoint.baseUrl),
          bootOperationMessageCatalog(endpoint.baseUrl),
          // Tempdoc 565 §27.4: workflow picker authored-label catalog, so
          // present({kind:'workflow', labelKey}) resolves the authored label
          // instead of the humanizeId fallback.
          bootWorkflowCatalog(endpoint.baseUrl),
        ]);
      }
      return undefined;
    })
    .catch((err) => {
      console.debug("[i18n] message catalog boot fetch failed", err);
    });
}
