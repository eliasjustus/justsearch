import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

const isMac = process.platform === 'darwin';

test.describe('Explainable actions', () => {
  test('disabled reasons are shown across Action Panel + Context Menu; dangerous actions confirm', async ({ page }) => {
    const apiBase = 'http://127.0.0.1:33221';
    let reindexCalls = 0;

    // Minimal stubs for connection + polling.
    await page.route(`${apiBase}/api/status`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', service: 'JustSearch Local API' }),
      });
    });

    await page.route(`${apiBase}/api/inference/status`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ mode: 'offline', available: false, starting: false }),
      });
    });

    await page.route(`${apiBase}/api/settings/v2`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ui: { density: 'comfort' } }),
      });
    });

    await page.route(`${apiBase}/api/knowledge/search`, async (route) => {
      const req = route.request();
      const body = req.postDataJSON() as any;
      const query = typeof body?.query === 'string' ? body.query : '';

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 1,
          tookMs: 1,
          results: [
            {
              id: 'doc-1',
              score: 1.0,
              fields: {
                doc_id: 'doc-1',
                path: 'C:\\\\docs\\\\doc-1.txt',
                title: `Doc 1 (${query})`,
                content_preview: `This is a preview that contains the term ${query}.`,
                mime: 'text/plain',
                mime_base: 'text/plain',
                file_kind: 'text',
                language: 'en',
                size_bytes: '12',
                modified_at: String(Date.now()),
              },
              matchedFields: ['content_preview'],
            },
          ],
        }),
      });
    });

    await page.route(`${apiBase}/api/indexing/reindex`, async (route) => {
      reindexCalls += 1;
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });

    await page.goto('/?api_port=33221');

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('needle');

    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });

    // Select via checkbox (drives selectedIds).
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Action Panel: desktop-only action should be disabled and explain why.
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    const revealAction = page.getByTestId(E2E_TEST_IDS.actionReveal);
    await expect(revealAction).toBeVisible();
    await expect(revealAction).toBeDisabled();
    await expect(revealAction).toContainText('Desktop only');

    // Dangerous action: reindex should require confirmation and can be cancelled without calling API.
    await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('reindex');
    const reindexAction = page.getByTestId(E2E_TEST_IDS.actionReindex);
    await expect(reindexAction).toBeVisible();
    await expect(reindexAction).toBeEnabled();
    await reindexAction.click();

    await expect(page.getByText('Trigger full reindex?', { exact: true })).toBeVisible({ timeout: 5_000 });
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByText('Trigger full reindex?', { exact: true })).not.toBeVisible();
    expect(reindexCalls).toBe(0);

    // Close action panel.
    await page.keyboard.press('Escape');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).not.toBeVisible();

    // Row gutter: reveal should be disabled and explain why (tooltip/title).
    await firstRow.hover();
    const rowReveal = firstRow.getByTestId(E2E_TEST_IDS.rowActionReveal);
    await expect(rowReveal).toBeVisible();
    await expect(rowReveal).toBeDisabled();
    await expect(rowReveal).toHaveAttribute('title', 'Desktop only');

    // Context menu: same desktop-only reason should surface.
    await firstRow.click({ button: 'right' });
    const contextMenu = page.getByTestId(E2E_TEST_IDS.contextMenu);
    await expect(contextMenu).toBeVisible({ timeout: 5_000 });
    const contextReveal = page.getByTestId(E2E_TEST_IDS.contextActionReveal);
    await expect(contextReveal).toBeVisible();
    await expect(contextReveal).toBeDisabled();
    await expect(contextReveal).toContainText('Desktop only');
  });
});


