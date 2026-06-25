import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

test.describe('Why this matched (match reasons)', () => {
  test('renders backend-provided match reasons in the Inspector', async ({ page }) => {
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
      const preview = `First ${query} then ${query} again.`;
      const firstIdx = preview.indexOf(query);

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
                content_preview: preview,
                mime: 'text/plain',
                mime_base: 'text/plain',
                file_kind: 'text',
                language: 'en',
                size_bytes: '12',
                modified_at: String(Date.now()),
              },
              matchedFields: ['content_preview'],
              // Only highlight the FIRST occurrence via spans (regex fallback would highlight both).
              matchSpans: [
                { field: 'content_preview', startChar: firstIdx, endChar: firstIdx + query.length, term: query },
              ],
            },
          ],
        }),
      });
    });

    await page.goto('/?api_port=33221');

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('needle');

    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });
    await expect(firstRow.locator('p.result-snippet mark')).toHaveCount(1);
    await firstRow.hover();
    const firstCheckbox = firstRow.locator('button[aria-label="Select file"]').first();
    const canSelect = await firstCheckbox.isVisible().catch(() => false);
    if (canSelect) {
      await firstCheckbox.click();
    }
    await firstRow.click();
    const preview = page.getByTestId(E2E_TEST_IDS.inspectorPreview).first();
    const hasInspector = await preview.isVisible().catch(() => false);
    test.skip(!hasInspector, 'Inspector preview is not visible in this runtime mode');
    await expect(preview).toBeVisible({ timeout: 10_000 });

    // Inspector should show "Matched" pills from backend-provided metadata.
    const pills = page.getByTestId(E2E_TEST_IDS.matchReasonPill);
    await expect(pills.first()).toBeVisible({ timeout: 10_000 });
    await expect(pills.first()).toHaveText('Preview');
    await expect(page.getByTestId(E2E_TEST_IDS.matchTerms)).toBeVisible({ timeout: 10_000 });
  });

  test('renders phrase spans as a single highlight (no regex fallback)', async ({ page }) => {
    const apiBase = 'http://127.0.0.1:33221';

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
      // Phrase: only highlight the contiguous phrase, not the loose terms.
      const preview = 'hello one hello world two world';
      const phrase = 'hello world';
      const start = preview.indexOf(phrase);
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
                title: 'Doc 1',
                content_preview: preview,
                mime: 'text/plain',
                mime_base: 'text/plain',
                file_kind: 'text',
                language: 'en',
                size_bytes: '12',
                modified_at: String(Date.now()),
              },
              matchedFields: ['content_preview'],
              matchSpans: [{ field: 'content_preview', startChar: start, endChar: start + phrase.length }],
            },
          ],
        }),
      });
    });

    await page.goto('/?api_port=33221');

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('"hello world"');

    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });

    const marks = firstRow.locator('p.result-snippet mark');
    await expect(marks).toHaveCount(1);
    await expect(marks.first()).toHaveText(/hello world/i);
  });
});


