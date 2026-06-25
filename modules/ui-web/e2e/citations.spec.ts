/**
 * Playwright spec for click-to-verify citations (demo mode).
 *
 * Verifies:
 * - Batch summarization produces structured citations metadata
 * - Inspector renders a Sources list
 * - Clicking a Source jumps Preview to the cited span and highlights it
 */
import { test, expect } from '@playwright/test';
import { navigateToDemoMode, waitForDemoData } from './ai-harness';
import { E2E_SELECTORS, E2E_TEST_IDS } from './selectors';

async function selectFirstResult(row: any) {
  await row.hover();
  const selectionControl = row
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"], input[type="checkbox"][aria-label*="Select file"], input[type="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  const canSelect = await selectionControl.isVisible().catch(() => false);
  if (canSelect) {
    await selectionControl.click();
    return;
  }
  await row.click();
}

async function clickSummarize(page: any) {
  const aiTab = page.getByRole('button', { name: /^AI$/ }).first();
  if (await aiTab.isVisible().catch(() => false)) {
    await aiTab.click();
  }

  const summarizeControl = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
  const canUseInspectorControl = await summarizeControl.isVisible().catch(() => false);
  if (canUseInspectorControl) {
    await summarizeControl.click();
    return;
  }

  const summarizeFallback = page.getByRole('button', { name: /Summarize/i }).first();
  const canUseFallback = await summarizeFallback.isVisible().catch(() => false);
  test.skip(!canUseFallback, 'Summarize control is not visible in this runtime mode');
  await summarizeFallback.click();
}

test.describe('Citations (click-to-verify)', () => {
  test('renders Sources and highlights cited span in Preview', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });

    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await expect(firstRow).toBeVisible();
    await selectFirstResult(firstRow);
    await clickSummarize(page);

    const firstCitation = page.getByTestId(E2E_TEST_IDS.citation0);
    await expect(firstCitation).toBeVisible({ timeout: 15_000 });
    await firstCitation.click();

    const highlight = page.getByTestId(E2E_TEST_IDS.citationHighlight);
    await expect(highlight).toBeVisible({ timeout: 10_000 });
    await expect(highlight).toContainText('deterministic preview content');
  });

  test('trust-loop nudge shows once and stays dismissed', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });

    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await expect(firstRow).toBeVisible();
    await selectFirstResult(firstRow);
    await clickSummarize(page);

    await expect(page.getByTestId(E2E_TEST_IDS.citation0)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId(E2E_TEST_IDS.trustNudge)).toBeVisible();

    await page.getByTestId(E2E_TEST_IDS.trustNudgeDismiss).click();
    await expect(page.getByTestId(E2E_TEST_IDS.trustNudge)).toHaveCount(0);

    await page.reload();
    await page.waitForLoadState('networkidle');
    await waitForDemoData(page, { query: 'justsearch' });

    const firstRow2 = page.locator(E2E_SELECTORS.searchResultRow).first();
    await selectFirstResult(firstRow2);
    await clickSummarize(page);

    await expect(page.getByTestId(E2E_TEST_IDS.citation0)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId(E2E_TEST_IDS.trustNudge)).toHaveCount(0);
  });

  test('shows a warning when citation offsets are misaligned (demo knob)', async ({ page }) => {
    await page.goto('/?demo=true&demo_citation_misaligned=1');
    await page.waitForLoadState('networkidle');
    await waitForDemoData(page, { query: 'justsearch' });

    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await expect(firstRow).toBeVisible();
    await selectFirstResult(firstRow);
    await clickSummarize(page);

    const firstCitation = page.getByTestId(E2E_TEST_IDS.citation0);
    await expect(firstCitation).toBeVisible({ timeout: 15_000 });
    await firstCitation.click();

    const warning = page.getByTestId(E2E_TEST_IDS.citationWarning);
    await expect(warning).toHaveCount(1);
    const highlight = page.getByTestId(E2E_TEST_IDS.citationHighlight);
    await expect(highlight).toBeVisible({ timeout: 10_000 });
    await expect(highlight).toContainText('deterministic preview content');
  });

  test('loads a new preview slice when a citation is outside the current preview page', async ({ page }) => {
    await page.goto('/?demo=true&demo_preview_prefix_chars=12000');
    await page.waitForLoadState('networkidle');
    await waitForDemoData(page, { query: 'justsearch' });

    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await expect(firstRow).toBeVisible();
    await selectFirstResult(firstRow);

    await expect(page.getByRole('button', { name: 'Load more' })).toBeVisible({ timeout: 10_000 });
    await clickSummarize(page);

    const firstCitation = page.getByTestId(E2E_TEST_IDS.citation0);
    await expect(firstCitation).toBeVisible({ timeout: 15_000 });
    await firstCitation.click();

    const highlight = page.getByTestId(E2E_TEST_IDS.citationHighlight);
    await expect(highlight).toBeVisible({ timeout: 10_000 });
    await expect(highlight).toContainText('deterministic preview content');
  });
});
