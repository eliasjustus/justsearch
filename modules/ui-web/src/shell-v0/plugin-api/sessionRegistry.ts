// SPDX-License-Identifier: Apache-2.0
/**
 * sessionRegistry — V1.5 / 477 H1 — session-singleton PluginRegistry.
 *
 * The host needs a single shared PluginRegistry instance that:
 *   - The PluginLoader writes to (when `loadPluginFromUrl` succeeds)
 *   - The Settings UI reads from (to list installed plugins)
 *   - The Stage's surface dispatch consults indirectly via the
 *     SurfaceCatalog merge (which is fed by PluginRegistry's
 *     `surfaceContributions()` after install)
 *
 * V1.5 alpha context: tests instantiate their own PluginRegistry
 * (`new PluginRegistry()`) and that's correct — tests want isolation.
 * Production lacks a single source of truth for "what plugins are
 * loaded?" — this module is that source.
 *
 * Design choice (478 §4.D / §4.H): the session singleton is a
 * forward-compat hook for the lifecycle state machine. Today it
 * holds a plain PluginRegistry instance; once §4.H lands, the
 * singleton becomes a state-machine view over the registry.
 *
 * Test isolation: production callers use {@link getSessionPluginRegistry};
 * tests should NOT use this — they construct their own
 * PluginRegistry to avoid cross-test state pollution. The
 * {@link __resetSessionRegistryForTest} helper exists for
 * integration tests that need to verify Settings UI rendering
 * against a known plugin set.
 */

import { PluginRegistry } from './PluginRegistry.js';

let instance: PluginRegistry | null = null;

/**
 * Get the session-singleton PluginRegistry. Creates the instance on
 * first call. All production callers that need a "the" registry
 * should use this.
 */
export function getSessionPluginRegistry(): PluginRegistry {
  if (instance === null) {
    instance = new PluginRegistry();
  }
  return instance;
}

/**
 * Test-only: replace the session singleton with a fresh instance.
 * Used by integration tests that exercise Settings UI's plugins
 * section against a controlled plugin set.
 */
export function __resetSessionRegistryForTest(): PluginRegistry {
  instance = new PluginRegistry();
  return instance;
}

/**
 * Test-only: explicitly seed the session singleton with a
 * pre-constructed instance. Used when the test wants to install
 * specific plugins before any consumer reads.
 */
export function __seedSessionRegistryForTest(registry: PluginRegistry): void {
  instance = registry;
}
