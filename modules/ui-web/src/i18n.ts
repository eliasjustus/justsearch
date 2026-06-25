// SPDX-License-Identifier: Apache-2.0
import { i18n } from "@lingui/core";
import { messages as enMessages } from "./locales/en/messages.mjs";
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

// Always load English as the fallback
i18n.load("en", enMessages);

/**
 * Detect the user's preferred locale from the browser.
 * Returns the base language code (e.g. "de" from "de-DE").
 * Falls back to "en" if the detected locale has no catalog.
 */
function detectLocale(): string {
  const supported = ["en", "de"];

  // Guard for non-browser environments (Node.js tests)
  if (typeof window === "undefined" || typeof navigator === "undefined") {
    return "en";
  }

  // Allow ?lang=de override for testing without changing browser settings
  const urlLang = new URLSearchParams(window.location.search).get("lang");
  if (urlLang && supported.includes(urlLang)) return urlLang;
  const browserLocale = navigator.language ?? "en";
  const lang = browserLocale.split("-")[0] ?? "en";
  return supported.includes(lang) ? lang : "en";
}

/**
 * Dynamically load and activate a locale.
 * English is bundled; other locales are lazy-loaded.
 */
async function activateLocale(locale: string): Promise<void> {
  if (locale === "en") {
    i18n.activate("en");
    return;
  }

  if (locale === "de") {
    const { messages } = await import("./locales/de/messages.mjs");
    i18n.load("de", messages);
    i18n.activate("de");
    return;
  }

  // Unknown locale — fall back to English
  i18n.activate("en");
}

// On module load: detect locale and activate.
// Start with English synchronously (so the UI renders immediately),
// then upgrade to the detected locale if different.
const detectedLocale = detectLocale();
i18n.activate("en"); // immediate render in English

if (detectedLocale !== "en") {
  // Load the detected locale async — the UI will re-render via I18nProvider
  activateLocale(detectedLocale);
}

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

export { i18n };
