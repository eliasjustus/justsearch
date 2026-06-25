import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

test.describe('Inspector tabbed AI flow', () => {
  test('Summarize auto-switches to AI tab and shows answer', async ({ page }) => {
    await page.goto('/?demo=true');

    // Ensure we're on Search (demo starts on the Launchpad / empty-search state).
    await page.locator('button[title="Search"]').click();

    // Populate demo results.
    const searchInput = page.getByTestId(E2E_TEST_IDS.searchInput);
    await searchInput.fill('readme');

    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });

    // Multi-select drives the batch AI flow and opens the Inspector.
    await page.getByRole('checkbox', { name: 'Select file' }).first().click();

    const inspector = page.locator('aside[aria-label="File details"]');
    await expect(inspector).toBeVisible({ timeout: 5_000 });

    // Switch to AI tab (Summarize button lives there).
    const aiTab = inspector.getByRole('button', { name: 'AI' });
    await aiTab.click();

    const summarizeBtn = inspector.getByTestId(E2E_TEST_IDS.inspectorSummarize);
    await expect(summarizeBtn).toBeVisible({ timeout: 5_000 });
    await summarizeBtn.click();

    // Wait for completion.
    await expect(summarizeBtn.getByText('Generating...', { exact: false })).toBeVisible({ timeout: 5_000 });
    await expect(summarizeBtn.getByText('Generating...', { exact: false })).toBeHidden({ timeout: 20_000 });

    // AI answer should be visible on the AI tab (auto-switched by Summarize).
    const responseText = inspector.getByText('This is a simulated AI summary', { exact: false });
    await expect(responseText).toBeVisible({ timeout: 10_000 });

    const answer = inspector.getByTestId(E2E_TEST_IDS.inspectorAnswer);
    await expect(answer).toBeVisible({ timeout: 5_000 });

    // Preview tab content should be hidden (on inactive tab).
    const preview = inspector.getByTestId(E2E_TEST_IDS.inspectorPreview);
    await expect(preview).toBeHidden();

    // Switch to Preview tab — preview should become visible.
    const previewTab = inspector.getByRole('button', { name: 'Preview', exact: true });
    await previewTab.click();
    await expect(preview).toBeVisible({ timeout: 5_000 });

    // Answer should now be hidden (on inactive AI tab).
    await expect(answer).toBeHidden();
  });
});
