// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §ζ2 — empty-state runtime integration test.
 *
 * Verifies the full plugin install → contribution-registration →
 * registry-listing → uninstall lifecycle for empty-state contributions.
 * Synthetic plugin only — no real backend or browser.
 *
 * Covers the case the §11.6 / §13.6 design promised: contributions
 * to `palette-no-results` appear in the registry as soon as the
 * plugin installs and disappear on uninstall.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { PluginRegistry } from './PluginRegistry.js';
import {
  PLUGIN_CONTRACT_VERSION,
  type PluginContribution,
  type PluginManifest,
} from './plugin-types.js';
import {
  listEmptyStates,
  __resetEmptyStateForTest,
} from '../commands/EmptyStateRegistry.js';

beforeEach(() => {
  __resetEmptyStateForTest();
});

function pluginWithContribution(
  contribution: PluginContribution,
): PluginManifest {
  return {
    id: 'synthetic-empty-states',
    version: '1.0.0',
    displayName: 'Synthetic',
    capabilities: {},
    contractVersion: PLUGIN_CONTRACT_VERSION,
    tagNamespace: 'synthetic-empty-states',
    register: () => contribution,
  };
}

describe('EmptyStateRegistry × PluginRegistry runtime install (§ζ2)', () => {
  it('plugin install → contribution appears for matching context', () => {
    const reg = new PluginRegistry();
    reg.install(
      pluginWithContribution({
        emptyStateContributions: [
          {
            id: 'web-search',
            context: 'palette-no-results',
            priority: 1,
            render: (input) =>
              `Search the web for "${input.query ?? ''}"`,
          },
        ],
      }),
    );

    const results = listEmptyStates({
      context: 'palette-no-results',
      query: 'foo',
    });
    expect(results).toHaveLength(1);
    expect(results[0]!.id).toBe('synthetic-empty-states.web-search');
    expect(results[0]!.source).toBe('plugin');

    // Render is callable through the registry boundary.
    const rendered = results[0]!.render({
      context: 'palette-no-results',
      query: 'foo',
    });
    expect(rendered).toBe('Search the web for "foo"');
  });

  it('contribution does NOT appear in other contexts', () => {
    const reg = new PluginRegistry();
    reg.install(
      pluginWithContribution({
        emptyStateContributions: [
          {
            id: 'web-search',
            context: 'palette-no-results',
            priority: 1,
            render: () => 'web',
          },
        ],
      }),
    );
    const other = listEmptyStates({ context: 'search-no-results' });
    expect(other).toHaveLength(0);
  });

  it('multiple contributions sort by priority ascending', () => {
    const reg = new PluginRegistry();
    reg.install(
      pluginWithContribution({
        emptyStateContributions: [
          { id: 'low', context: 'palette-no-results', priority: 10, render: () => 'low' },
          { id: 'high', context: 'palette-no-results', priority: 1, render: () => 'high' },
          { id: 'mid', context: 'palette-no-results', priority: 5, render: () => 'mid' },
        ],
      }),
    );
    const results = listEmptyStates({ context: 'palette-no-results' });
    expect(results.map((r) => r.id)).toEqual([
      'synthetic-empty-states.high',
      'synthetic-empty-states.mid',
      'synthetic-empty-states.low',
    ]);
  });

  it('uninstall removes the contribution', () => {
    const reg = new PluginRegistry();
    reg.install(
      pluginWithContribution({
        emptyStateContributions: [
          {
            id: 'web-search',
            context: 'palette-no-results',
            priority: 1,
            render: () => 'web',
          },
        ],
      }),
    );
    expect(listEmptyStates({ context: 'palette-no-results' })).toHaveLength(1);
    reg.uninstall('synthetic-empty-states');
    expect(listEmptyStates({ context: 'palette-no-results' })).toHaveLength(0);
  });
});
