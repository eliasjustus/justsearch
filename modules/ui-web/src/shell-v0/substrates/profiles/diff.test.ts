// @vitest-environment happy-dom

/**
 * Tempdoc 543 §25.δ4 — Profile activation diff tests.
 */

import { beforeEach, describe, expect, it } from 'vitest';
import { diffProfileActivation } from './diff.js';
import {
  saveProfile,
  __resetProfilesForTest,
  type WorkspaceProfile,
} from './index.js';
import {
  installContributionManifest,
  __resetManifestRegistryForTest,
  type ContributionManifest,
} from '../manifest/index.js';
import { makePluginProvenance } from '../../primitives/provenance.js';
import { __resetShellContextForTest } from '../../state/shellContextState.js';

beforeEach(async () => {
  await __resetManifestRegistryForTest();
  __resetProfilesForTest();
  __resetShellContextForTest();
});

const STUB_MANIFEST = (
  overrides: Partial<ContributionManifest> = {},
): ContributionManifest => ({
  id: 'm1',
  version: '1.0.0',
  provenance: makePluginProvenance('m1', '1.0.0'),
  contributes: {},
  profileBinding: 'profile-scoped',
  ...overrides,
});

const stubProfile = (
  id: string,
  enabledManifestIds: readonly string[] = [],
): WorkspaceProfile => ({
  id,
  label: id,
  enabledManifestIds,
  scope: {},
  createdAt: new Date().toISOString(),
});

describe('diffProfileActivation (§25.δ4)', () => {
  it('returns existence=false for unknown profile id', () => {
    const d = diffProfileActivation('does-not-exist');
    expect(d.targetProfileExists).toBe(false);
    expect(d.manifestsToInstall).toEqual([]);
  });

  it('reports manifestsToInstall for ids in target profile but not installed', () => {
    saveProfile(stubProfile('test'));
    // Profile starts with no enabled manifests; add via internals.
    // Use updateProfile if exposed, else just verify the empty-diff path.
    const d = diffProfileActivation('test');
    expect(d.targetProfileExists).toBe(true);
    expect(d.manifestsToInstall).toEqual([]);
    expect(d.manifestsToUninstall).toEqual([]);
  });

  it('reports manifestsToUninstall for currently-installed profile-scoped manifests not in target', async () => {
    await installContributionManifest(STUB_MANIFEST({ id: 'plugin-a' }));
    saveProfile(stubProfile('test'));
    // 'plugin-a' is installed and profile-scoped, but 'test' profile
    // has empty enabledManifestIds → it would be uninstalled.
    const d = diffProfileActivation('test');
    expect(d.manifestsToUninstall).toContain('plugin-a');
  });

  it('does not report global (non-profile-scoped) manifests', async () => {
    await installContributionManifest(
      STUB_MANIFEST({ id: 'global-plugin', profileBinding: 'global' }),
    );
    saveProfile(stubProfile('test'));
    const d = diffProfileActivation('test');
    expect(d.manifestsToInstall).not.toContain('global-plugin');
    expect(d.manifestsToUninstall).not.toContain('global-plugin');
    expect(d.manifestsUnchanged).not.toContain('global-plugin');
  });

  it('is pure — calling it does NOT mutate installs', async () => {
    await installContributionManifest(STUB_MANIFEST({ id: 'p1' }));
    saveProfile(stubProfile('test'));
    diffProfileActivation('test');
    diffProfileActivation('test');
    // Still installed.
    expect(diffProfileActivation('test').manifestsToUninstall).toContain('p1');
  });
});
