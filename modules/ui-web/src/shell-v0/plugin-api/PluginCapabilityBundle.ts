// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 467 (V1.5 alpha) + 477 H2.4 (V1.5.1) — capability bundles
 * per trust tier.
 *
 * Per `slices/470-v1-5-user-ui-authorship-substrate.md` §B.A.5, the
 * WebExtensions permission model is the architectural template:
 * each plugin tier gets a STATIC capability bundle granted at
 * install time (no per-call permission checks).
 *
 * V1.5 alpha (deprecated): bundle was uniform across tiers; the
 * audience floor (PluginRegistry) was the only differentiator.
 *
 * V1.5.1 (this slice — 477 H2.4): bundle attenuates per tier.
 *   - CORE / TRUSTED_PLUGIN — full default bundle (raw `localStorage`,
 *     unrestricted `customElements`, `document`).
 *   - UNTRUSTED_PLUGIN — attenuated bundle: `localStorage` is
 *     scoped to a per-plugin keyspace; `customElements` rejects
 *     tag names that don't start with the plugin's `tagNamespace`.
 *
 * The loader (PluginLoader.loadPluginFromUrl) takes `tier` as
 * input; the caller decides tier OUT OF BAND. V1.5.1 callers:
 *   - Compiled-in plugins: handled by direct `registry.install`
 *     (don't go through this loader); always CORE.
 *   - User-loaded via Settings UI: default UNTRUSTED_PLUGIN until
 *     Sigstore (477 H2.3) flips signed plugins to TRUSTED_PLUGIN.
 *   - Dev-examples / programmatic console install: caller passes
 *     tier explicitly.
 *
 * Per 478 §4.D: trust tier is OUTPUT of TrustChannel, INPUT to this
 * function. This function does not decide trust; it consumes a
 * decision and produces the corresponding endowment bundle.
 *
 * @see slices/470-v1-5-user-ui-authorship-substrate.md §B.A.5
 *      (WebExtensions permission model adoption)
 * @see slices/470-v1-5-user-ui-authorship-substrate.md §10 Group 1
 *      Decision B (mediated network for all tiers — ratified 2026-05-07)
 * @see slices/478-v1-5-structural-design-refinements.md §4.D
 *      (TrustChannel as the only mint-site for Provenance)
 */

import type { PluginTrustTier } from './PluginRegistry.js';
import type { PluginEndowments } from './PluginCompartment.js';
import { buildDefaultEndowments } from './PluginCompartment.js';

/**
 * Build the capability-bundle endowment map for a plugin given its
 * trust tier.
 *
 * @param tier the plugin's trust tier (input — produced by
 *   TrustChannel verification or by the host's compile-time CORE
 *   designation).
 * @param pluginId the plugin's manifest id. Used for tag-namespace
 *   enforcement and per-plugin storage scoping. Pass a placeholder
 *   like `'pre-validation'` for the pre-shape evaluation pass; the
 *   final bundle uses the real id.
 * @returns endowments to construct a `Compartment(endowments)` for
 *   this plugin.
 */
export function buildCapabilityBundle(
  tier: PluginTrustTier,
  pluginId: string,
): PluginEndowments {
  const base = buildDefaultEndowments();
  if (tier === 'CORE' || tier === 'TRUSTED_PLUGIN') {
    // Full bundle — host-endorsed plugins get the unattenuated
    // default. Audience-floor enforcement at the SurfaceCatalog
    // merge still applies (PluginRegistry.surfaceContributions).
    return base;
  }
  // UNTRUSTED_PLUGIN — apply attenuation. Tempdoc 560 Fix C: do NOT endow `document` at all. The
  // raw document re-grants the window via `document.defaultView` — and transitively via
  // `document.body.ownerDocument.defaultView`, which a defaultView-nulling facade could not close.
  // No untrusted plugin uses `document` at register/factory time (the only document user is the CORE
  // host API, capabilities/ui.ts), so omitting it entirely structurally closes the window re-grant:
  // there is no document to climb. Plugins present UI via the constrained component vocabulary, not
  // direct DOM (§4.4 presentation boundary).
  const untrusted: PluginEndowments = {
    ...base,
    localStorage: scopedLocalStorage(pluginId),
    customElements: namespacedCustomElements(pluginId, base['customElements']),
  };
  delete untrusted['document'];
  return untrusted;
}

/**
 * Per-plugin localStorage facade. Keys are auto-prefixed with
 * `plugin:<id>:` so plugins can't read or write keys outside their
 * own namespace. The host's keys (`justsearch.*`) and other
 * plugins' keys are unreachable.
 *
 * The facade preserves the same Web Storage API shape so plugin
 * code that does `localStorage.setItem(...)` works unchanged —
 * only the effective storage location changes.
 */
function scopedLocalStorage(pluginId: string): Storage {
  const prefix = `plugin:${pluginId}:`;
  const realStorage = (globalThis as unknown as { localStorage?: Storage })
    .localStorage;
  if (!realStorage) {
    // Headless environment — return a no-op facade so plugin code
    // doesn't throw on null access. Tests can still pass an
    // override via `endowmentsExtension`.
    return makeNoOpStorage();
  }
  return {
    get length(): number {
      let count = 0;
      for (let i = 0; i < realStorage.length; i++) {
        const key = realStorage.key(i);
        if (key && key.startsWith(prefix)) count++;
      }
      return count;
    },
    key(index: number): string | null {
      // Iterate scoped keys; ignore non-prefixed.
      let scopedIndex = 0;
      for (let i = 0; i < realStorage.length; i++) {
        const k = realStorage.key(i);
        if (k && k.startsWith(prefix)) {
          if (scopedIndex === index) return k.slice(prefix.length);
          scopedIndex++;
        }
      }
      return null;
    },
    getItem(key: string): string | null {
      return realStorage.getItem(prefix + key);
    },
    setItem(key: string, value: string): void {
      realStorage.setItem(prefix + key, value);
    },
    removeItem(key: string): void {
      realStorage.removeItem(prefix + key);
    },
    clear(): void {
      // Clear ONLY this plugin's keys; preserve host + other
      // plugins' keys.
      const toRemove: string[] = [];
      for (let i = 0; i < realStorage.length; i++) {
        const k = realStorage.key(i);
        if (k && k.startsWith(prefix)) toRemove.push(k);
      }
      for (const k of toRemove) realStorage.removeItem(k);
    },
  };
}

function makeNoOpStorage(): Storage {
  const store = new Map<string, string>();
  return {
    get length(): number {
      return store.size;
    },
    key(index: number): string | null {
      return Array.from(store.keys())[index] ?? null;
    },
    getItem(key: string): string | null {
      return store.get(key) ?? null;
    },
    setItem(key: string, value: string): void {
      store.set(key, value);
    },
    removeItem(key: string): void {
      store.delete(key);
    },
    clear(): void {
      store.clear();
    },
  };
}

/**
 * `customElements` proxy that rejects `define(tag, klass)` when the
 * tag doesn't start with the plugin's `tagNamespace` prefix
 * (which equals the plugin id per V1.1 contract). Other methods
 * (`get`, `whenDefined`, `upgrade`) pass through unchanged.
 *
 * This is the runtime enforcement of the documented invariant in
 * `PluginRegistry.install`'s tagNamespace check. PluginRegistry
 * validates the manifest's `tagNamespace === id`; this proxy
 * validates that runtime `define()` calls also respect the
 * namespace.
 */
function namespacedCustomElements(
  pluginId: string,
  realRegistry: unknown,
): CustomElementRegistry {
  if (
    realRegistry === null ||
    realRegistry === undefined ||
    typeof (realRegistry as CustomElementRegistry).define !== 'function'
  ) {
    throw new Error(
      'buildCapabilityBundle: customElements registry not available for tagNamespace enforcement',
    );
  }
  const real = realRegistry as CustomElementRegistry;
  const prefix = `${pluginId}-`;
  return {
    define(name: string, ctor: CustomElementConstructor, options?: ElementDefinitionOptions): void {
      if (!name.startsWith(prefix) && name !== pluginId) {
        throw new Error(
          `Plugin '${pluginId}' attempted to define '${name}' which does not match its tagNamespace ('${pluginId}-*'). ` +
            'Per the V1.1 plugin contract, plugins can only define elements within their own namespace.',
        );
      }
      real.define(name, ctor, options);
    },
    get(name: string): CustomElementConstructor | undefined {
      return real.get(name);
    },
    whenDefined(name: string): Promise<CustomElementConstructor> {
      return real.whenDefined(name);
    },
    upgrade(root: Node): void {
      real.upgrade(root);
    },
    getName(constructor: CustomElementConstructor): string | null {
      // Some browsers/tests expose `getName` on CustomElementRegistry;
      // forward if present, otherwise null.
      const r = real as { getName?: (c: CustomElementConstructor) => string | null };
      return r.getName ? r.getName(constructor) : null;
    },
  };
}
