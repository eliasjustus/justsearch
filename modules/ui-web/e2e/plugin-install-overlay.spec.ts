/**
 * E2E spec — V1.5.1 plugin-overlay polish (slice 447-followup-live-wiring §X.12.10
 * Item 1.2 + 447-followup-tier3-tooling §B).
 *
 * Verifies that PluginRegistry.install() correctly drives all three plugin-
 * overlay merge paths (Surface + Resource + Recovery) into their respective
 * catalogs, and uninstall() clears them. Uses the dev-mode fixture loader
 * (window.__justsearchDevFixtures) introduced for this spec.
 *
 * Pre-§X.12.10, all three merge functions had zero production callsites — the
 * V1.5.1 polish was named in slice tempdocs but never shipped. This spec is
 * the regression guard: if a future refactor unwires any of the three paths,
 * the corresponding assertion fails and the gap is caught immediately.
 */

import { test, expect } from '@playwright/test';
import { navigateToDemoMode } from './ai-harness';

test.describe('Plugin V1.5.1 polish — install drives all 3 overlay paths', () => {
  test('install populates SurfaceCatalog + ResourceCatalog + RecoveryOverlay', async ({
    page,
  }) => {
    await navigateToDemoMode(page);

    // Wait for the registry singletons to initialize.
    await page.waitForFunction(
      () =>
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        typeof (window as any).__justsearchDevFixtures === 'object',
      { timeout: 10000 },
    );

    // Install the fixture plugin via the dev-mode helper.
    const installed = await page.evaluate(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      return (window as any).__justsearchDevFixtures.installFixturePlugin();
    });
    expect(installed).toBe(true);

    // Assert all three contribution paths landed in their catalogs.
    const after = await page.evaluate(async () => {
      const [{ getSurface }, { getResource }, { getOverlayRecovery }] =
        await Promise.all([
          import('/src/api/registry/SurfaceCatalogClient.ts'),
          import('/src/api/registry/ResourceCatalogClient.ts'),
          import('/src/api/registry/RecoveryOverlayClient.ts'),
        ]);
      return {
        surface: getSurface('vendor.e2e-fixture.dashboard') !== undefined,
        resource: getResource('vendor.e2e-fixture.dataset') !== undefined,
        overlay:
          getOverlayRecovery('vendor.e2e-fixture.broken', 'fixture-svc') ===
          'vendor.e2e-fixture.fix',
      };
    });
    expect(after.surface).toBe(true);
    expect(after.resource).toBe(true);
    expect(after.overlay).toBe(true);
  });

  test('uninstall clears all 3 overlay paths', async ({ page }) => {
    await navigateToDemoMode(page);

    await page.waitForFunction(
      () =>
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        typeof (window as any).__justsearchDevFixtures === 'object',
      { timeout: 10000 },
    );

    // Install + uninstall.
    await page.evaluate(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (window as any).__justsearchDevFixtures.installFixturePlugin();
    });
    const removed = await page.evaluate(() => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      return (window as any).__justsearchDevFixtures.uninstallFixturePlugin();
    });
    expect(removed).toBe(true);

    // All three paths should now be absent.
    const cleared = await page.evaluate(async () => {
      const [{ getSurface }, { getResource }, { getOverlayRecovery }] =
        await Promise.all([
          import('/src/api/registry/SurfaceCatalogClient.ts'),
          import('/src/api/registry/ResourceCatalogClient.ts'),
          import('/src/api/registry/RecoveryOverlayClient.ts'),
        ]);
      return {
        surface: getSurface('vendor.e2e-fixture.dashboard') === undefined,
        resource: getResource('vendor.e2e-fixture.dataset') === undefined,
        overlay:
          getOverlayRecovery('vendor.e2e-fixture.broken', 'fixture-svc') ===
          undefined,
      };
    });
    expect(cleared.surface).toBe(true);
    expect(cleared.resource).toBe(true);
    expect(cleared.overlay).toBe(true);
  });
});
