/**
 * ContributionManifest substrate unit tests — Tempdoc 543 §13.2.3.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  installContributionManifest,
  uninstallContributionManifest,
  listInstalledManifests,
  getInstalledManifest,
  subscribeManifests,
  __resetManifestRegistryForTest,
  type ContributionManifest,
} from './index.js';
import {
  __resetActionsForTest,
  getAction,
} from '../actions/index.js';
import { __resetForTest as __resetStatusBarForTest } from '../../commands/StatusBarRegistry.js';
import { makePluginProvenance } from '../../primitives/provenance.js';

beforeEach(async () => {
  await __resetManifestRegistryForTest();
  __resetActionsForTest();
  __resetStatusBarForTest();
});

const STUB_MANIFEST = (
  overrides: Partial<ContributionManifest> = {},
): ContributionManifest => ({
  id: 'test.plugin',
  version: '1.0.0',
  provenance: makePluginProvenance('test.plugin', '1.0.0'),
  contributes: {},
  ...overrides,
});

describe('installContributionManifest (§13.2.3)', () => {
  it('install + getInstalledManifest round-trip', async () => {
    const m = STUB_MANIFEST();
    await installContributionManifest(m);
    const stored = getInstalledManifest('test.plugin');
    // §25.α7 — install stamps Provenance with installedAt, so the
    // stored manifest is a structurally equal but distinct object.
    expect(stored).toBeDefined();
    expect(stored!.id).toBe(m.id);
    expect(stored!.version).toBe(m.version);
    expect(stored!.provenance.contributorId).toBe(m.provenance.contributorId);
    expect(stored!.provenance.installedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(listInstalledManifests()).toHaveLength(1);
  });

  it('throws on duplicate id', async () => {
    await installContributionManifest(STUB_MANIFEST());
    await expect(installContributionManifest(STUB_MANIFEST())).rejects.toThrow(
      /already installed/,
    );
  });

  it('throws when dependency is missing', async () => {
    await expect(
      installContributionManifest(
        STUB_MANIFEST({ id: 'b', dependencies: ['a'] }),
      ),
    ).rejects.toThrow(/depends on missing a/);
  });

  it('dependency present → installs in order', async () => {
    await installContributionManifest(STUB_MANIFEST({ id: 'a' }));
    await installContributionManifest(
      STUB_MANIFEST({ id: 'b', dependencies: ['a'] }),
    );
    expect(listInstalledManifests().map((m) => m.id).sort()).toEqual([
      'a',
      'b',
    ]);
  });
});

describe('ContributionEntries dispatch (§13.2.3)', () => {
  it('actions: each entry routes through registerAction with manifest provenance', async () => {
    const handler = vi.fn(() => ({ kind: 'noop' as const }));
    await installContributionManifest(
      STUB_MANIFEST({
        contributes: {
          actions: [{ id: 'demo.action', title: 'Demo', handler }],
        },
      }),
    );
    const a = getAction('demo.action');
    expect(a).toBeDefined();
    expect(a!.provenance.contributorId).toBe('test.plugin');
  });

  it('statusBarItems: each entry routes with source=plugin + manifest provenance', async () => {
    await installContributionManifest(
      STUB_MANIFEST({
        contributes: {
          statusBarItems: [
            {
              id: 'demo.bar',
              position: 'right',
              priority: 50,
              render: () => 'demo',
            },
          ],
        },
      }),
    );
    const { listStatusBarItems } = await import(
      '../../commands/StatusBarRegistry.js'
    );
    const items = listStatusBarItems('right');
    const demo = items.find((i) => i.id === 'demo.bar');
    expect(demo).toBeDefined();
    expect(demo!.source).toBe('plugin');
    expect(demo!.provenance?.contributorId).toBe('test.plugin');
  });
});

describe('Atomic rollback on failure', () => {
  it('rolls back registered actions when a later entry throws', async () => {
    // Pre-register an action that collides with the manifest's second
    // action — install should roll back the first.
    const { registerAction } = await import('../actions/index.js');
    registerAction({
      id: 'collide.id',
      title: 'pre-existing',
      handler: () => ({ kind: 'noop' as const }),
      provenance: makePluginProvenance('other', '1.0'),
    });

    await expect(
      installContributionManifest(
        STUB_MANIFEST({
          contributes: {
            actions: [
              {
                id: 'rollback.first',
                title: 'first',
                handler: () => ({ kind: 'noop' as const }),
              },
              {
                id: 'collide.id', // duplicate triggers throw
                title: 'duplicate',
                handler: () => ({ kind: 'noop' as const }),
              },
            ],
          },
        }),
      ),
    ).rejects.toThrow(/already registered/);

    // The first action was rolled back; the pre-existing 'collide.id'
    // remains.
    expect(getAction('rollback.first')).toBeUndefined();
    expect(getAction('collide.id')).toBeDefined();
    // Manifest itself was NOT installed.
    expect(getInstalledManifest('test.plugin')).toBeUndefined();
  });
});

describe('Lifecycle hooks (§13.2.3)', () => {
  it('activate runs after install', async () => {
    const activate = vi.fn();
    await installContributionManifest(
      STUB_MANIFEST({ lifecycle: { activate } }),
    );
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('deactivate runs before uninstall', async () => {
    const deactivate = vi.fn();
    await installContributionManifest(
      STUB_MANIFEST({ lifecycle: { deactivate } }),
    );
    await uninstallContributionManifest('test.plugin');
    expect(deactivate).toHaveBeenCalledTimes(1);
  });

  it('activate throw → manifest rolled back', async () => {
    await expect(
      installContributionManifest(
        STUB_MANIFEST({
          contributes: {
            actions: [
              {
                id: 'doomed',
                title: 'd',
                handler: () => ({ kind: 'noop' as const }),
              },
            ],
          },
          lifecycle: {
            activate: () => {
              throw new Error('boom');
            },
          },
        }),
      ),
    ).rejects.toThrow(/boom/);
    expect(getInstalledManifest('test.plugin')).toBeUndefined();
    expect(getAction('doomed')).toBeUndefined();
  });

  it('deactivate throw is swallowed; uninstall still completes', async () => {
    await installContributionManifest(
      STUB_MANIFEST({
        lifecycle: {
          deactivate: () => {
            throw new Error('boom');
          },
        },
      }),
    );
    expect(await uninstallContributionManifest('test.plugin')).toBe(true);
    expect(getInstalledManifest('test.plugin')).toBeUndefined();
  });
});

describe('Subscriptions', () => {
  it('subscribeManifests fires on install + uninstall', async () => {
    const listener = vi.fn();
    subscribeManifests(listener);
    await installContributionManifest(STUB_MANIFEST());
    await uninstallContributionManifest('test.plugin');
    expect(listener).toHaveBeenCalledTimes(2);
  });
});

describe('§25.γ5 effectInverses ContributionEntries', () => {
  it('install populates the inverse registry; effects substrate uses it', async () => {
    const { lookupEffectInverse, __resetEffectInversesForTest } = await import(
      './index.js'
    );
    __resetEffectInversesForTest();

    await installContributionManifest(
      STUB_MANIFEST({
        contributes: {
          effectInverses: {
            'core.rename': {
              kind: 'invoke-operation' as const,
              operationId: 'core.unrename',
              args: {},
            },
          },
        },
      }),
    );

    const inverse = lookupEffectInverse('core.rename');
    expect(inverse).toBeDefined();
    expect(inverse!.kind).toBe('invoke-operation');
  });

  it('deriveInverse on invoke-operation prefers manifest-declared inverse', async () => {
    const { lookupEffectInverse, __resetEffectInversesForTest } = await import(
      './index.js'
    );
    const { recordEffect, __resetJournalForTest } = await import(
      '../effects/index.js'
    );
    const { CORE_PROVENANCE } = await import('../../primitives/provenance.js');
    __resetEffectInversesForTest();
    __resetJournalForTest();

    await installContributionManifest(
      STUB_MANIFEST({
        contributes: {
          effectInverses: {
            'core.delete-item': {
              kind: 'toast' as const,
              message: 'Item restored',
              severity: 'success' as const,
            },
          },
        },
      }),
    );

    expect(lookupEffectInverse('core.delete-item')).toBeDefined();

    const entry = recordEffect(
      {
        kind: 'invoke-operation' as const,
        operationId: 'core.delete-item',
      },
      CORE_PROVENANCE,
    );
    // The declared inverse beat the placeholder toast.
    expect(entry.inverse).toEqual({
      kind: 'toast',
      message: 'Item restored',
      severity: 'success',
    });
  });

  it('uninstall removes the inverse from the registry', async () => {
    const { lookupEffectInverse, __resetEffectInversesForTest } = await import(
      './index.js'
    );
    __resetEffectInversesForTest();

    await installContributionManifest(
      STUB_MANIFEST({
        contributes: {
          effectInverses: {
            'core.x': { kind: 'noop' as const },
          },
        },
      }),
    );
    expect(lookupEffectInverse('core.x')).toBeDefined();
    await uninstallContributionManifest('test.plugin');
    expect(lookupEffectInverse('core.x')).toBeUndefined();
  });
});
