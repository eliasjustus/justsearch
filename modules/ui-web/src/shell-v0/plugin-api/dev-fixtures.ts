// SPDX-License-Identifier: Apache-2.0
/**
 * Dev-mode fixtures for E2E verification of slice 447 §X.11.5 Phase 6 +
 * 447-followup-live-wiring §X.12.10 Item 1.2 (V1.5.1 plugin-overlay polish).
 *
 * Exposes `installFixturePlugin()` / `uninstallFixturePlugin()` on the
 * `getSessionPluginRegistry()` instance so Playwright specs in
 * `modules/ui-web/e2e/` can exercise the full install/uninstall lifecycle for
 * all three contribution paths (Surface + Resource + Recovery overlay) without
 * loading a real plugin module.
 *
 * Gated on `import.meta.env.DEV`: the helpers register themselves on
 * `window.__justsearchDevFixtures` only in dev mode. In production builds the
 * registration is tree-shaken (Vite's `import.meta.env.DEV` is statically
 * `false`), so `window.__justsearchDevFixtures` is `undefined` and there is
 * no production attack surface.
 *
 * Importing this module from the production bundle (via main.ts) is safe —
 * the registration check short-circuits.
 */

import {
  getSessionPluginRegistry,
} from './sessionRegistry.js';
import {
  PLUGIN_CONTRACT_VERSION,
  type PluginContribution,
  type PluginManifest,
} from './plugin-types.js';

const FIXTURE_PLUGIN_ID = 'vendor.e2e-fixture';
const FIXTURE_TAG_NAMESPACE = 'vendor.e2e-fixture';

class FixtureSurfaceElement extends HTMLElement {
  connectedCallback(): void {
    this.textContent = 'fixture surface';
  }
}

/**
 * Build a synthetic PluginManifest exercising all four contribution paths:
 * customElements + surfaceContributions + resourceContributions + recoveryOverlays.
 * Used by E2E to assert each path lands in the corresponding catalog at install
 * time and clears at uninstall time.
 */
function buildFixtureManifest(): PluginManifest {
  const contribution: PluginContribution = {
    customElements: [
      { tagSuffix: 'panel', klass: FixtureSurfaceElement },
    ],
    surfaceContributions: [
      {
        contribution: {
          id: 'vendor.e2e-fixture.dashboard',
          mountTag: 'vendor.e2e-fixture-panel',
          labelKey: 'vendor.e2e-fixture.dashboard.label',
          descriptionKey: 'vendor.e2e-fixture.dashboard.desc',
          audience: 'USER',
          placement: 'STAGE',
        },
      },
    ],
    resourceContributions: [
      {
        contribution: {
          id: 'vendor.e2e-fixture.dataset',
          // Cast to bypass full Resource shape; the merge function accepts the
          // manifest's "Omit<Resource, 'provenance'>" shape and stamps the rest.
          // E2E asserts presence by id, not full Resource conformance.
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any,
      },
    ],
    recoveryOverlays: [
      {
        conditionId: 'vendor.e2e-fixture.broken',
        subject: 'fixture-svc',
        operationRef: 'vendor.e2e-fixture.fix',
      },
    ],
  };
  return {
    id: FIXTURE_PLUGIN_ID,
    version: '0.0.1',
    displayName: 'E2E fixture',
    contractVersion: PLUGIN_CONTRACT_VERSION,
    tagNamespace: FIXTURE_TAG_NAMESPACE,
    capabilities: {},
    register: () => contribution,
  };
}

/**
 * Install the synthetic fixture plugin into the session-singleton registry.
 * Idempotent: re-installing while present is a no-op (the registry's
 * duplicate-id error is caught + ignored). Returns true on first install.
 */
function installFixturePlugin(): boolean {
  const registry = getSessionPluginRegistry();
  if (registry.has(FIXTURE_PLUGIN_ID)) {
    return false;
  }
  registry.install(buildFixtureManifest());
  return true;
}

/**
 * Uninstall the fixture plugin if present. Returns true if a plugin was
 * removed, false if no fixture was installed.
 */
function uninstallFixturePlugin(): boolean {
  return getSessionPluginRegistry().uninstall(FIXTURE_PLUGIN_ID);
}

/**
 * Expose the fixture helpers on `window.__justsearchDevFixtures` for
 * Playwright specs to call via page.evaluate. Production builds tree-shake
 * the entire body of this module away.
 */
export function registerDevFixturesIfDev(): void {
  // Vite-injected import.meta.env is statically replaced at build time.
  // The cast keeps tsc happy without pulling vite/client types into the
  // shell-v0 tsconfig. In production, Vite emits `false` and the body is
  // dead-code-eliminated.
  // Vite statically replaces `import.meta.env.DEV` with `true` in dev and
  // `false` in production builds, where the dead branch is then tree-shaken.
  // The cast keeps tsc happy without pulling vite/client types into the
  // shell-v0 tsconfig. Direct property access (not via an intermediate
  // variable) is required for Vite's static replacement to fire.
  if (!(import.meta as ImportMeta & { env?: { DEV?: boolean } }).env?.DEV) {
    return;
  }
  const w = globalThis as unknown as {
    __justsearchDevFixtures?: {
      installFixturePlugin: () => boolean;
      uninstallFixturePlugin: () => boolean;
      getFixturePluginId: () => string;
    };
  };
  w.__justsearchDevFixtures = {
    installFixturePlugin,
    uninstallFixturePlugin,
    getFixturePluginId: () => FIXTURE_PLUGIN_ID,
  };
}

// Side-effect: register on module import. Tree-shaken in production.
registerDevFixturesIfDev();
