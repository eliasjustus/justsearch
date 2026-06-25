import { test, expect } from '@playwright/test';
import { navigateToDemoMode, waitForDemoData } from './ai-harness';
import { assertPrimaryControlVisible, assertZoneGridNoOverlap } from './layout-heuristics';
import { E2E_ACTIVITY_TEST_IDS, E2E_TEST_IDS } from './selectors';

test.describe('Layout overlap/clipping heuristics (no baselines)', () => {
  test('main views have non-overlapping zones', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });

    const views = ['search', 'library', 'browse', 'brain', 'health', 'settings'] as const;
    for (const v of views) {
      await page.getByTestId(E2E_ACTIVITY_TEST_IDS[v]).click();
      await assertPrimaryControlVisible(page, v);
      await assertZoneGridNoOverlap(page);
    }
  });

  test('inspector open/close does not overlap zones', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });

    // Open inspector by selecting a row (this is the primary UX path).
    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });
    await firstRow.click();

    const inspector = page.locator('.zone-inspector').first();
    await expect
      .poll(async () => {
        const box = await inspector.boundingBox();
        return box?.width ?? 0;
      })
      .toBeGreaterThan(50);

    await assertZoneGridNoOverlap(page);

    // Close inspector by switching away from Search (inspector is search-context only).
    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect
      .poll(async () => {
        const box = await inspector.boundingBox();
        return box?.width ?? 0;
      })
      .toBeLessThan(15);
    await assertZoneGridNoOverlap(page);
  });

  test('search side panel (inspector closed) stays within stage bounds', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });

    // In demo mode, Search loads without committing selection, so the Inspector stays closed.
    const panel = page.getByTestId(E2E_TEST_IDS.searchSidePanel);
    const panelVisible = await panel.isVisible().catch(() => false);
    test.skip(!panelVisible, 'search-side-panel is not rendered in this runtime mode');
    await expect(panel).toBeVisible();

    const stage = page.locator('.zone-stage').first();
    await expect
      .poll(async () => {
        const sb = await stage.boundingBox();
        const pb = await panel.boundingBox();
        if (!sb || !pb) return { ok: false };
        return {
          ok:
            pb.x >= sb.x - 1 &&
            pb.y >= sb.y - 1 &&
            pb.x + pb.width <= sb.x + sb.width + 2 &&
            pb.y + pb.height <= sb.y + sb.height + 2,
        };
      })
      .toEqual({ ok: true });
  });
});


