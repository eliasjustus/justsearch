// @vitest-environment happy-dom

/**
 * Slice B (§6 guards) — contribution-symmetry guard.
 *
 * Root-cause guard for the install/uninstall-asymmetry defect class (F15 was
 * one instance: resolutionAliases applied on install but not removed on
 * uninstall). A plugin contributes one entry per uninstall-tracked axis; the
 * test asserts each axis actually REGISTERED (guards against false-green), then
 * uninstalls and asserts NO trace of the plugin's namespace remains in ANY
 * registry. Any axis whose uninstall removal is missing leaves a trace and
 * fails this test.
 *
 * Scope: the axes routed through PluginRegistry.uninstall's
 * `contributionApplied` block + the alias path (the F15-class surface). Axes
 * that are structurally un-removable (customElements — browser limitation,
 * documented in code) are excluded.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { PluginRegistry } from './PluginRegistry.js';
import type { PluginManifest } from './plugin-types.js';
import { listStatusBarItems } from '../commands/StatusBarRegistry.js';
import { listInspectorTabs } from '../commands/InspectorTabRegistry.js';
import { listContextActions } from '../commands/ContextActionRegistry.js';
import { listEmptyStates } from '../commands/EmptyStateRegistry.js';
import { listWalkthroughs } from '../commands/WalkthroughRegistry.js';
import { getSurfaceAliases, setSurfaceAliases } from '../router/catalogResolver.js';
// Tempdoc 548 §4.3a (S2) — aggregateStrategies is now a per-plugin merge axis in
// the co-located PER_PLUGIN_AXES table; the guard covers it too.
import { getRegisteredCells } from '../aggregate-substrate/aggregateRegistry.js';

const NS = 'symtest';

function allAxesPlugin(): PluginManifest {
  return {
    id: NS,
    version: '1.0.0',
    displayName: 'Symmetry Test',
    capabilities: {},
    contractVersion: '1.1',
    tagNamespace: NS,
    register: () => ({
      statusBarItems: [
        { id: 'sb1', position: 'left', priority: 1, render: () => 'x' },
      ],
      inspectorTabs: [{ id: 'it1', label: 'T', priority: 1, render: () => 'x' }],
      contextActions: [
        { id: 'ca1', context: 'symtest-ctx', label: 'A', priority: 1, handler: () => {} },
      ],
      emptyStateContributions: [
        { id: 'es1', context: 'symtest-ctx', priority: 1, render: () => 'x' },
      ],
      walkthroughs: [
        { id: 'wt1', title: 'W', priority: 1, steps: [{ id: 's1', title: 'S', body: 'b' }] },
      ],
      resolutionAliases: [{ from: 'symtest-alias', to: 'core.search' }],
      // §4.3a merge axis — registers into the aggregate registry under
      // source { plugin: NS }; removed via removePluginAggregateStrategies.
      aggregateStrategies: [
        { aggregate: 'Operation', context: 'button', rank: 50, strategy: () => null },
      ],
    }),
  };
}

/** Count registry entries belonging to the test plugin's namespace. */
function traceCount(): Record<string, number> {
  const hasNs = (id: unknown) => typeof id === 'string' && id.includes(NS);
  return {
    statusBar: listStatusBarItems().filter((e) => hasNs(e.id)).length,
    inspectorTabs: listInspectorTabs().filter((e) => hasNs(e.id)).length,
    contextActions: listContextActions('symtest-ctx').filter((e) => hasNs(e.id)).length,
    emptyStates: listEmptyStates({ context: 'symtest-ctx' }).filter((e) => hasNs(e.id)).length,
    walkthroughs: listWalkthroughs().filter((e) => hasNs(e.id)).length,
    aliases: getSurfaceAliases()['symtest-alias'] !== undefined ? 1 : 0,
    aggregateStrategies: getRegisteredCells().filter(
      (c) => typeof c.source === 'object' && c.source.plugin === NS,
    ).length,
  };
}

describe('PluginRegistry — contribution-symmetry (install/uninstall) [Slice B]', () => {
  beforeEach(() => {
    setSurfaceAliases({});
  });

  it('uninstall removes every axis it installed (no namespace trace remains)', () => {
    const registry = new PluginRegistry();
    registry.install(allAxesPlugin());

    // Guard against false-green: each axis must actually have registered.
    const installed = traceCount();
    for (const [axis, n] of Object.entries(installed)) {
      expect(n, `axis '${axis}' should be registered after install`).toBeGreaterThan(0);
    }

    registry.uninstall(NS);

    // The symmetry property: post-uninstall, zero trace in every axis.
    const remaining = traceCount();
    for (const [axis, n] of Object.entries(remaining)) {
      expect(n, `axis '${axis}' leaked after uninstall (asymmetry)`).toBe(0);
    }
  });
});
