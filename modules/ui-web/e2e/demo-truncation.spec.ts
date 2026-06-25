import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

test.describe('Demo mode verification knobs', () => {
  test('demo_truncated forces Partial + Truncated context UI after batch summarize', async ({ page }) => {
    await page.goto('/?demo=true&demo_truncated=1');

    // Ensure we're on the Search view (demo starts on Home).
    await page.locator('button[title="Search"]').click();

    // Demo mode uses local filtering; type a query to populate the result list.
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');

    // Ensure we have results and select via checkbox (multi-select drives the batch AI flow).
    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });

    const firstCheckbox = firstRow
      .locator('button[aria-label="Select file"], [role="checkbox"][aria-label*="Select file"]')
      .first();
    await firstCheckbox.click();

    // Trigger batch summarize.
    const aiTab = page.getByRole('button', { name: /^AI$/ }).first();
    if (await aiTab.isVisible().catch(() => false)) {
      await aiTab.click();
    }

    const summarizeBtn = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
    const hasSummarize = await summarizeBtn.isVisible().catch(() => false);
    if (hasSummarize) {
      await summarizeBtn.click();
    } else {
      const fallbackSummarize = page.getByRole('button', { name: /Summarize/i }).first();
      await expect(fallbackSummarize).toBeVisible({ timeout: 10_000 });
      await fallbackSummarize.click();
    }

    // After streaming completes, context UI should reflect the forced truncation/over-budget state.
    await expect(page.getByText('Context')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/Based on partial document content/i)).toBeVisible({ timeout: 10_000 });

    const truncatedPill = page.getByText('Truncated', { exact: true });
    if (await truncatedPill.isVisible().catch(() => false)) {
      await expect(truncatedPill).toBeVisible({ timeout: 10_000 });
    } else {
      await expect(page.getByText(/Too large/i)).toBeVisible({ timeout: 10_000 });
    }
  });
});


