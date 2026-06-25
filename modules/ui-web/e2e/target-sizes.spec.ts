import { test, expect } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

test.describe('Minimum target sizes (UI ergonomics)', () => {
  test('key click targets are at least 24x24 in demo mode', async ({ page }) => {
    await page.goto('/?demo=true');

    // Activity rail targets (Zone B)
    const searchNav = page.locator('button[title="Search"]');
    const searchNavBox = await searchNav.boundingBox();
    expect(searchNavBox).toBeTruthy();
    expect(searchNavBox!.width).toBeGreaterThanOrEqual(24);
    expect(searchNavBox!.height).toBeGreaterThanOrEqual(24);

    // Ensure we're on Search.
    await searchNav.click();

    // Trigger results view so filters render.
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');
    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });

    // Filters toggle should be a reasonable target size.
    const filtersToggle = page.getByRole('button', { name: /Toggle filters/i });
    const filtersBox = await filtersToggle.boundingBox();
    expect(filtersBox).toBeTruthy();
    expect(filtersBox!.height).toBeGreaterThanOrEqual(24);

    // Result multi-select checkbox target size
    await firstRow.hover();
    const firstCheckbox = firstRow
      .locator(
        'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"]',
      )
      .first();
    const canMeasureCheckbox = await firstCheckbox.isVisible().catch(() => false);
    test.skip(!canMeasureCheckbox, 'Row selection control is unavailable in this runtime mode');
    const cb = await firstCheckbox.boundingBox();
    expect(cb).toBeTruthy();
    expect(cb!.width).toBeGreaterThanOrEqual(24);
    expect(cb!.height).toBeGreaterThanOrEqual(24);
  });
});


