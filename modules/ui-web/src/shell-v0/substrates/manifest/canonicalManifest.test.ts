/**
 * 548 §4.3(d) — the core welcome walkthrough is declared via a
 * ContributionManifest (the canonical declaration root), not imperatively in
 * CorePlugin.register(). This pins that the manifest install registers the
 * walkthrough in the WalkthroughRegistry with the manifest's provenance +
 * derived source, and that uninstall rolls it back atomically.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  installCoreWalkthroughManifest,
  __resetCoreWalkthroughManifestInstalledForTest,
} from './canonicalManifest.js';
import { uninstallContributionManifest, __resetManifestRegistryForTest } from './index.js';
import {
  listWalkthroughs,
  __resetWalkthroughsForTest,
} from '../../commands/WalkthroughRegistry.js';

beforeEach(async () => {
  await __resetManifestRegistryForTest();
  __resetWalkthroughsForTest();
  __resetCoreWalkthroughManifestInstalledForTest();
});

describe('core welcome walkthrough as a ContributionManifest (548 §4.3 d)', () => {
  it('installing the manifest registers the welcome walkthrough with manifest provenance', async () => {
    expect(listWalkthroughs().find((w) => w.id === 'welcome')).toBeUndefined();

    await installCoreWalkthroughManifest();

    const welcome = listWalkthroughs().find((w) => w.id === 'welcome');
    expect(welcome).toBeDefined();
    expect(welcome?.title).toBe('Welcome to JustSearch');
    expect(welcome?.steps.length).toBe(4);
    // Provenance + source are DERIVED from the one manifest, not hand-plumbed.
    expect(welcome?.provenance.tier).toBe('CORE');
    expect(welcome?.source).toBe('core');
  });

  it('is idempotent (second install is a no-op)', async () => {
    await installCoreWalkthroughManifest();
    await installCoreWalkthroughManifest();
    expect(listWalkthroughs().filter((w) => w.id === 'welcome')).toHaveLength(1);
  });

  it('uninstall rolls the walkthrough back atomically', async () => {
    await installCoreWalkthroughManifest();
    expect(listWalkthroughs().some((w) => w.id === 'welcome')).toBe(true);

    const removed = await uninstallContributionManifest('core.core-welcome');
    expect(removed).toBe(true);
    expect(listWalkthroughs().some((w) => w.id === 'welcome')).toBe(false);
  });
});
