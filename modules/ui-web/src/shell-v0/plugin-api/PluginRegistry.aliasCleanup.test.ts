// @vitest-environment happy-dom

/**
 * Regression test for F15 (see docs/tempdocs/547): a plugin's
 * `resolutionAliases` are merged into the global CatalogResolver alias
 * map on install (applyContribution -> setSurfaceAliases) but were NOT
 * removed on uninstall — leaving aliases that point at a now-uninstalled
 * surface. This is the "uninstall doesn't invert one of the ~13
 * contribution axes" defect class.
 *
 * The fix removes the plugin's alias keys in uninstall, best-effort: only
 * a key whose current target still matches what this plugin set is
 * deleted, so an alias a later contributor overrode for the same key is
 * not clobbered (second test).
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { PluginRegistry } from './PluginRegistry.js';
import { getSurfaceAliases, setSurfaceAliases } from '../router/catalogResolver.js';
import type { PluginManifest } from './plugin-types.js';

function aliasPlugin(id: string, from: string, to: string): PluginManifest {
  return {
    id,
    version: '1.0.0',
    displayName: id,
    capabilities: {},
    contractVersion: '1.1',
    tagNamespace: id,
    register: () => ({ resolutionAliases: [{ from, to }] }),
  };
}

describe('PluginRegistry — resolution alias cleanup on uninstall (F15)', () => {
  beforeEach(() => {
    setSurfaceAliases({});
  });

  it('removes the plugin\'s aliases when it is uninstalled', () => {
    const registry = new PluginRegistry();
    registry.install(aliasPlugin('acme', 'dash', 'acme.dashboard'));
    expect(getSurfaceAliases()['dash']?.target).toBe('acme.dashboard');

    registry.uninstall('acme');
    expect(getSurfaceAliases()['dash']).toBeUndefined();
  });

  it('does NOT clobber an alias a later contributor overrode for the same key', () => {
    const registry = new PluginRegistry();
    registry.install(aliasPlugin('acme', 'dash', 'acme.dashboard'));
    // Another source (promoted alias / different plugin) overrides 'dash'.
    setSurfaceAliases({
      ...getSurfaceAliases(),
      dash: { target: 'other.surface', reason: 'alias' },
    });

    registry.uninstall('acme');
    // acme's uninstall must not remove the now-foreign 'dash' alias.
    expect(getSurfaceAliases()['dash']?.target).toBe('other.surface');
  });
});
