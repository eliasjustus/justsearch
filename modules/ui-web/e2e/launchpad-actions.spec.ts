import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

test.describe('Launchpad (empty search) actions', () => {
  test('Actions card opens the Action Panel in demo mode', async ({ page }) => {
    await page.goto('/?demo=true');

    // Launchpad should be visible (empty search state — demo lands in "fresh" state).
    await expect(page.getByRole('heading', { name: /Welcome to JustSearch/i })).toBeVisible();

    // Clicking the Actions button in the compact nav row should open the Action Panel.
    await page.getByRole('button', { name: /Actions/i }).click();
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible({ timeout: 5_000 });
  });

  test('shows help topic chips that drive search', async ({ page }) => {
    await page.goto('/?demo=true');

    // Demo mode should NOT show setup CTA (subtitle already covers demo messaging).
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadSetup)).not.toBeVisible({ timeout: 2_000 });

    const chip = page
      .getByTestId(E2E_TEST_IDS.launchpadQueryChip)
      .filter({ hasText: 'Getting Started' })
      .first();
    await expect(chip).toBeVisible({ timeout: 5_000 });
    await chip.click();

    // Query should populate the GlobalCommand input.
    const searchInput = page.getByPlaceholder(/Search files/);
    await expect(searchInput).toHaveValue('getting started', { timeout: 5_000 });

    // Results should render in demo mode.
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow).first()).toBeVisible({ timeout: 5_000 });
  });
});
