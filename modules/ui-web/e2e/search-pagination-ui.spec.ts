/**
 * Search pagination + sorting (cursor/searchAfter) contract verification.
 *
 * Validates:
 * - backend can return nextCursor when page size is small
 * - ui-web sends cursor on "Load more" and appends results
 * - ui-web sends sort and resets paging
 */

import { test, expect } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const baseUrl = `http://localhost:5173/?api_port=${API_PORT}&page_size=1&demo_ui_mode=advanced`;

test.describe('Search: pagination + sorting', () => {
  test('load more uses cursor and sort is sent', async ({ page }) => {
    await page.route('**/api/ui/ready', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 200, body: '{}' });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'ok',
          service: 'JustSearch Local API',
          indexHealthy: true,
          indexedDocuments: 2,
        }),
      });
    });

    await page.route('**/api/inference/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          mode: 'offline',
          available: false,
          starting: false,
          embeddingQueueSize: 0,
          vduQueueSize: 0,
        }),
      });
    });

    await page.route('**/api/settings/v2', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ui: { mode: 'advanced' }, llm: {}, indexPaths: [] }),
      });
    });

    await page.route('**/api/indexing/roots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [] }),
      });
    });

    await page.route('**/api/knowledge/search', async (route) => {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      const cursor = typeof body?.cursor === 'string' ? body.cursor : '';

      if (!cursor) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            totalHits: 2,
            results: [
              {
                id: 'doc-1',
                score: 0.95,
                fields: {
                  title: 'Doc 1',
                  path: '/test/doc-1.txt',
                  mime: 'text/plain',
                },
              },
            ],
            nextCursor: 'safter-v1:cursor-page-2',
          }),
        });
        return;
      }

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 2,
          results: [
            {
              id: 'doc-2',
              score: 0.90,
              fields: {
                title: 'Doc 2',
                path: '/test/doc-2.txt',
                mime: 'text/plain',
              },
            },
          ],
          nextCursor: null,
        }),
      });
    });

    await page.goto(baseUrl);
    await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i, { timeout: 15_000 });

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('search');

    const firstResp = await page.waitForResponse(
      (r) => r.url().includes('/api/knowledge/search') && r.request().method() === 'POST' && r.ok(),
      { timeout: 15_000 },
    );
    const firstJson = await firstResp.json();
    expect(typeof firstJson?.nextCursor).toBe('string');

    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow)).toHaveCount(1, { timeout: 15_000 });
    const loadMoreButton = page.getByRole('button', { name: /Load.*more/i });
    await expect(loadMoreButton).toBeVisible();

    const reqPromise = page.waitForRequest(
      (r) => r.url().includes('/api/knowledge/search') && r.method() === 'POST',
      { timeout: 15_000 },
    );
    await loadMoreButton.click();
    const req = await reqPromise;

    const reqBody = JSON.parse(req.postData() || '{}');
    expect(typeof reqBody?.cursor).toBe('string');
    expect(String(reqBody.cursor)).toContain('safter-v1:');

    const resp = await req.response();
    expect(resp).toBeTruthy();
    expect(resp!.status()).toBe(200);

    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow)).toHaveCount(2, { timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Load.*more/i })).toHaveCount(0);

    const filtersToggle = page.getByTestId(E2E_TEST_IDS.filtersToggle).first();
    const expanded = await filtersToggle.getAttribute('aria-expanded');
    if (expanded !== 'true') {
      await filtersToggle.focus();
      await filtersToggle.press('Enter');
      const expandedAfterEnter = await filtersToggle.getAttribute('aria-expanded');
      if (expandedAfterEnter !== 'true') {
        await filtersToggle.click({ force: true });
      }
    }

    const sortSelect = page.getByRole('button', { name: 'Sort by' });
    await expect(sortSelect).toBeVisible();

    const sortReqPromise = page.waitForRequest(
      (r) => r.url().includes('/api/knowledge/search') && r.method() === 'POST',
      { timeout: 15_000 },
    );
    await sortSelect.click();
    await page.getByRole('option', { name: 'Newest' }).click();
    const sortReq = await sortReqPromise;
    const sortReqBody = JSON.parse(sortReq.postData() || '{}');
    expect(sortReqBody?.sort).toBe('modified_desc');
    expect(sortReqBody?.cursor).toBeUndefined();
  });
});
