import { test, expect } from '@playwright/test';
import { navigateToDemoMode, waitForDemoData } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

test.describe('GlobalCommand chrome + query syntax indicator', () => {
  test('query syntax pill is only shown on Search view', async ({ page }) => {
    // Force Advanced mode via URL param so the query syntax control is rendered.
    await page.goto('/?demo=true&demo_ui_mode=advanced');
    await page.waitForLoadState('networkidle');
    await waitForDemoData(page, { query: 'justsearch' });

    const querySyntaxControl = page.getByRole('button', { name: /^(Basic|Lucene)$/ });
    await expect(querySyntaxControl).toBeVisible({ timeout: 5_000 });

    // Hidden on other views.
    await page.getByTestId(E2E_TEST_IDS.activitySettings).click();
    await expect(page.getByRole('button', { name: /^(Basic|Lucene)$/ })).toHaveCount(0);

    await page.getByTestId(E2E_TEST_IDS.activityHealth).click();
    await expect(page.getByRole('button', { name: /^(Basic|Lucene)$/ })).toHaveCount(0);

    await page.getByTestId(E2E_TEST_IDS.activityBrain).click();
    await expect(page.getByRole('button', { name: /^(Basic|Lucene)$/ })).toHaveCount(0);
  });

  test('focus visibly changes GlobalCommand border chrome', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });

    const chrome = page.getByTestId(E2E_TEST_IDS.globalCommandChrome);
    const getChromeClass = async () => (await chrome.getAttribute('class')) || '';

    // Blur by focusing a different control without changing views.
    await page.getByTestId(E2E_TEST_IDS.activityLibrary).focus();
    const classBlur = await getChromeClass();

    // Focus the search input; focus-within classes should change.
    await page.getByTestId(E2E_TEST_IDS.searchInput).focus();
    await expect(page.getByTestId(E2E_TEST_IDS.searchInput)).toBeFocused();
    await expect.poll(getChromeClass).not.toBe(classBlur);
    const classFocus = await getChromeClass();

    expect(classFocus).toContain('border-teal-500/25');
  });
});


