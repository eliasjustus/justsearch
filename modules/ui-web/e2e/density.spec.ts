import { test, expect } from '@playwright/test';
import { assertZoneGridNoOverlap, assertPrimaryControlVisible } from './layout-heuristics';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

test.describe('Density (compact/comfort/rich)', () => {
  test('changing density updates result row height and keeps zone layout stable', async ({ page }) => {
    const apiBase = 'http://127.0.0.1:33221';

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

    // Start in comfort.
    await page.route(`${apiBase}/api/settings/v2`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ui: { density: 'comfort' } }),
      });
    });

    await page.route(`${apiBase}/api/preview**`, async (route) => {
      const url = new URL(route.request().url());
      const docId = url.searchParams.get('docId') || 'doc-1';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId,
          content: `Preview content for ${docId}.`,
          truncated: false,
          nextOffsetChars: 0,
          mime: 'text/plain',
          title: `Title for ${docId}`,
          path: docId,
          textProvenance: 'tika',
        }),
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

    await page.goto('/?api_port=33221');
    await assertPrimaryControlVisible(page, 'search');

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('needle');

    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });
    await expect(page.locator('html')).toHaveAttribute('data-density', 'comfort');

    const comfortBox = await firstRow.boundingBox();
    expect(comfortBox).not.toBeNull();

    // Switch to compact via the Search header density control.
    await page.getByRole('radio', { name: 'Compact' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-density', 'compact');
    await assertZoneGridNoOverlap(page);

    // Ensure row height changed in-place.
    await expect(firstRow).toBeVisible({ timeout: 5_000 });
    const compactBox = await firstRow.boundingBox();
    expect(compactBox).not.toBeNull();

    // Expect at least a small but real change; avoid brittle absolute pixel numbers.
    expect((compactBox as any).height).toBeLessThan((comfortBox as any).height - 2);

    await assertZoneGridNoOverlap(page);
  });
});


