import { test, expect } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

test.describe('Search filters UI (collapsed by default)', () => {
  test('filters toggle reveals advanced controls in demo mode', async ({ page }) => {
    await page.goto('/?demo=true&demo_ui_mode=advanced');

    // Ensure we're on Search (demo starts on empty-search Launchpad).
    await page.locator('button[title="Search"]').click();

    // Trigger results view so the filter bar renders.
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');
    await page.keyboard.press('Escape').catch(() => {});

    const toggle = page.getByTestId(E2E_TEST_IDS.filtersToggle).first();
    await expect(toggle).toBeVisible({ timeout: 5_000 });

    // Collapsed by default: advanced controls are hidden.
    await expect(page.getByRole('button', { name: 'Scope' })).toBeHidden();

    // Expand.
    await toggle.focus();
    await toggle.press('Enter');
    const afterEnter = await toggle.getAttribute('aria-expanded');
    if (afterEnter !== 'true') {
      await toggle.click({ force: true });
    }
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('button', { name: 'Scope' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Filter by type' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Filter by date' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Filter by language' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Filter by path prefix' })).toBeVisible();

    // Collapse again.
    await toggle.focus();
    await toggle.press('Enter');
    const afterCollapseEnter = await toggle.getAttribute('aria-expanded');
    if (afterCollapseEnter !== 'false') {
      await toggle.click({ force: true });
    }
    await expect(page.getByRole('button', { name: 'Scope' })).toBeHidden();
  });
});


