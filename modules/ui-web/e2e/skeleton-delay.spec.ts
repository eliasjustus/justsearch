import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

test.describe('Verification knobs (skeleton capture)', () => {
  test('e2e_view_delay_ms forces skeleton to be visible during view navigation', async ({ page }) => {
    await page.goto('/?demo=true&e2e_view_delay_ms=1500');

    await page.locator('button[title="Library"]').click();

    const skeleton = page.getByTestId(E2E_TEST_IDS.skeletonLibrary);
    await expect(skeleton).toBeVisible({ timeout: 2_000 });

    // After the forced delay elapses, the real view should load and the skeleton should disappear.
    await expect(skeleton).not.toBeVisible({ timeout: 10_000 });
  });
});


