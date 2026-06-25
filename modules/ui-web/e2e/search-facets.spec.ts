import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

/**
 * E2E tests for search facets functionality.
 *
 * These tests verify:
 * 1. MIME base facet toggles always appear when search returns facets
 * 2. Clicking a MIME base toggle updates the search request with the correct filter
 * 3. URL sync works for mime_base filter state
 */

const UI_URL = 'http://localhost:5173';

async function ensureExpandedFilters(page: any) {
  const filtersToggle = page.getByTestId(E2E_TEST_IDS.filtersToggle).first();
  await expect(filtersToggle).toBeVisible({ timeout: 5_000 });
  const expanded = await filtersToggle.getAttribute('aria-expanded');
  if (expanded !== 'true') {
    await filtersToggle.focus();
    await filtersToggle.press('Enter');
    const afterEnter = await filtersToggle.getAttribute('aria-expanded');
    if (afterEnter !== 'true') {
      await filtersToggle.click({ force: true });
    }
    await expect(filtersToggle).toHaveAttribute('aria-expanded', 'true');
  }
}

test.describe('Search facets', () => {
  test.beforeEach(async ({ page }) => {
    // Stub POST /api/ui/ready (UI-ready handshake, best-effort)
    await page.route('**/api/ui/ready', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 200, body: '{}' });
      } else {
        await route.continue();
      }
    });

    // Stub GET /api/status
    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'ok',
          service: 'JustSearch Local API',
          indexHealthy: true,
          indexedDocuments: 100,
        }),
      });
    });

    // Stub GET /api/inference/status
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

    // Stub GET /api/settings/v2
    await page.route('**/api/settings/v2', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ui: { mode: 'advanced' }, llm: {}, indexPaths: [] }),
      });
    });

    // Stub GET /api/indexing/roots
    await page.route('**/api/indexing/roots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [] }),
      });
    });
  });

  test('shows mime_base toggles when search returns facets', async ({ page }) => {
    // Track search requests
    const searchRequests: Array<{ body: Record<string, unknown> }> = [];

    // Stub POST /api/knowledge/search with facets
    await page.route('**/api/knowledge/search', async (route) => {
      const body = route.request().postDataJSON();
      searchRequests.push({ body });

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 3,
          tookMs: 10,
          results: [
            { id: 'doc1', score: 0.9, fields: { path: '/test/doc1.txt', title: 'Doc 1', mime_base: 'text' } },
            { id: 'doc2', score: 0.8, fields: { path: '/test/doc2.pdf', title: 'Doc 2', mime_base: 'application' } },
            { id: 'doc3', score: 0.7, fields: { path: '/test/image.png', title: 'Image', mime_base: 'image' } },
          ],
          facets: {
            file_kind: { text: 2, pdf: 1 },
            language: { en: 3 },
            mime_base: { text: 10, application: 5, image: 3 },
          },
          facetsTruncated: false,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?demo_ui_mode=advanced`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Type a search query
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');

    // Wait for search results
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow)).toHaveCount(3, { timeout: 5000 });
    await ensureExpandedFilters(page);

    // MIME toggles should appear automatically (facets always on)
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleText)).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleApplication)).toBeVisible();
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleImage)).toBeVisible();

    // Verify the toggles show counts
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleText)).toContainText('(10)');
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleApplication)).toContainText('(5)');
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleImage)).toContainText('(3)');
  });

  test('clicking mime_base toggle sends filter in search request', async ({ page }) => {
    // Track search requests
    const searchRequests: Array<{ body: Record<string, unknown> }> = [];

    // Stub POST /api/knowledge/search with facets
    await page.route('**/api/knowledge/search', async (route) => {
      const body = route.request().postDataJSON();
      searchRequests.push({ body });

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 3,
          tookMs: 10,
          results: [
            { id: 'doc1', score: 0.9, fields: { path: '/test/doc1.txt', title: 'Doc 1', mime_base: 'text' } },
            { id: 'doc2', score: 0.8, fields: { path: '/test/doc2.pdf', title: 'Doc 2', mime_base: 'application' } },
            { id: 'doc3', score: 0.7, fields: { path: '/test/image.png', title: 'Image', mime_base: 'image' } },
          ],
          facets: {
            file_kind: { text: 2, pdf: 1 },
            language: { en: 3 },
            mime_base: { text: 10, application: 5, image: 3 },
          },
          facetsTruncated: false,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?demo_ui_mode=advanced`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Type a search query
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');

    // Wait for search results
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow)).toHaveCount(3, { timeout: 5000 });
    await ensureExpandedFilters(page);

    // MIME toggles should appear automatically (facets always on)
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleText)).toBeVisible({ timeout: 5000 });

    // Clear search requests to track only the new ones
    const requestCountBefore = searchRequests.length;

    // Click the "text" mime_base toggle
    await page.getByTestId(E2E_TEST_IDS.mimeBaseToggleText).click();

    // Wait for a new search request (debounced)
    await page.waitForTimeout(300);

    // Verify a new search request was made
    expect(searchRequests.length).toBeGreaterThan(requestCountBefore);

    // Get the last request
    const lastRequest = searchRequests[searchRequests.length - 1];
    expect(lastRequest).toBeDefined();

    // Verify the request includes the mimeBase filter
    const filters = lastRequest?.body?.filters as Record<string, unknown> | undefined;
    expect(filters?.mimeBase).toEqual(['text']);
  });

  test('mime_base filter persists in URL', async ({ page }) => {
    const searchRequests: Array<{ body: Record<string, unknown> }> = [];

    // Stub POST /api/knowledge/search with facets
    await page.route('**/api/knowledge/search', async (route) => {
      const body = route.request().postDataJSON();
      searchRequests.push({ body });

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 3,
          tookMs: 10,
          results: [
            { id: 'doc1', score: 0.9, fields: { path: '/test/doc1.txt', title: 'Doc 1', mime_base: 'text' } },
          ],
          facets: {
            mime_base: { text: 10, application: 5, image: 3 },
          },
          facetsTruncated: false,
        }),
      });
    });

    // Navigate to UI with existing mime_base filter in URL
    await page.goto(`${UI_URL}?q=test&mime_base=text,image&demo_ui_mode=advanced`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });
    await ensureExpandedFilters(page);

    // Wait for the MIME toggles to appear
    await expect(page.getByTestId(E2E_TEST_IDS.mimeBaseToggleText)).toBeVisible({ timeout: 5000 });

    // Verify URL state propagated into the search request.
    const firstRequest = searchRequests[0];
    expect(firstRequest).toBeDefined();
    const filters = firstRequest?.body?.filters as Record<string, unknown> | undefined;
    expect(filters?.mimeBase).toEqual(['text', 'image']);

    // Verify chips show the active filters
    await expect(page.getByRole('button', { name: /MIME: text/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /MIME: image/i })).toBeVisible();
  });

  test('removing mime_base chip clears the filter', async ({ page }) => {
    // Track search requests
    const searchRequests: Array<{ body: Record<string, unknown> }> = [];

    // Stub POST /api/knowledge/search with facets
    await page.route('**/api/knowledge/search', async (route) => {
      const body = route.request().postDataJSON();
      searchRequests.push({ body });

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 1,
          tookMs: 10,
          results: [
            { id: 'doc1', score: 0.9, fields: { path: '/test/doc1.txt', title: 'Doc 1', mime_base: 'text' } },
          ],
          facets: {
            mime_base: { text: 10, application: 5 },
          },
          facetsTruncated: false,
        }),
      });
    });

    // Navigate to UI with existing mime_base filter in URL
    await page.goto(`${UI_URL}?q=test&mime_base=text&demo_ui_mode=advanced`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Wait for results
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow)).toHaveCount(1, { timeout: 5000 });

    // Verify the chip is visible
    const mimeChip = page.getByRole('button', { name: /MIME: text/i });
    await expect(mimeChip).toBeVisible();

    // Clear search requests to track only the new ones
    const requestCountBefore = searchRequests.length;

    // Click the chip to remove the filter
    await mimeChip.click();

    // Wait for a new search request (debounced)
    await page.waitForTimeout(300);

    // Verify a new search request was made
    expect(searchRequests.length).toBeGreaterThan(requestCountBefore);

    // Get the last request
    const lastRequest = searchRequests[searchRequests.length - 1];
    expect(lastRequest).toBeDefined();

    // Verify the request does NOT include mimeBase filter (or it's empty)
    const filters = lastRequest?.body?.filters as Record<string, unknown> | undefined;
    expect(filters?.mimeBase).toBeUndefined();

    // Verify the chip is no longer visible
    await expect(mimeChip).not.toBeVisible();
  });
});

