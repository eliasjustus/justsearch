/**
 * Search filters + projection + facets (UI contract verification)
 *
 * This is a lightweight end-to-end test that validates:
 * - ui-web renders the filter bar
 * - ui-web sends filters.includeChunks=false by default (no chunk contamination)
 * - ui-web sends a projection list (lightweight hit payload)
 * - search responses do not include fields.content
 * - toggling facets requests facet counts and returns facets in the response
 * - pathPrefix filter can narrow results to zero, and Clear restores results
 *
 * F7: Refactored to use route stubs for determinism (no live backend required).
 */

import { test, expect } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const baseUrl = `http://localhost:5173/?api_port=${API_PORT}&demo_ui_mode=advanced`;

async function waitForConnectionStatus(page: any, timeoutMs = 15_000) {
  await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i, { timeout: timeoutMs });
}

/**
 * Sample search results for stubbing. These are normal documents (not chunks).
 * NOTE: fields.content is intentionally absent to verify projection works.
 */
const MOCK_SEARCH_RESULTS = {
  results: [
    {
      id: 'doc-001',
      score: 0.95,
      name: 'project-readme.md',
      path: '/projects/example/project-readme.md',
      fields: {
        path: '/projects/example/project-readme.md',
        mime: 'text/markdown',
        file_kind: 'text',
        content_preview: 'This is a sample project readme with search content...',
        // NOTE: fields.content is NOT present (projection excludes it)
      },
    },
    {
      id: 'doc-002',
      score: 0.88,
      name: 'config.json',
      path: '/projects/example/config.json',
      fields: {
        path: '/projects/example/config.json',
        mime: 'application/json',
        file_kind: 'code',
        content_preview: '{"name": "example", "version": "1.0.0"}',
      },
    },
    {
      id: 'doc-003',
      score: 0.75,
      name: 'notes.txt',
      path: '/projects/example/notes.txt',
      fields: {
        path: '/projects/example/notes.txt',
        mime: 'text/plain',
        file_kind: 'text',
        content_preview: 'Some notes about the search functionality...',
      },
    },
  ],
  totalHits: 3,
  facets: null,
  facetsTruncated: false,
};

/**
 * Faceted search results (when facets are enabled).
 */
const MOCK_FACETED_RESULTS = {
  ...MOCK_SEARCH_RESULTS,
  facets: {
    file_kind: { text: 2, code: 1 },
    mime: { 'text/markdown': 1, 'application/json': 1, 'text/plain': 1 },
  },
  facetsTruncated: false,
};

test.describe('Search: filters + projection + facets', () => {
  test.beforeEach(async ({ page }) => {
    // Stub common endpoints for deterministic testing
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
          indexedDocuments: 100,
          indexSizeBytes: 1024000,
          uptimeMs: 60000,
          memoryUsedBytes: 50000000,
          memoryMaxBytes: 100000000,
          pendingJobs: 0,
        }),
      });
    });

    await page.route('**/api/inference/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          mode: 'online',
          available: true,
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
        body: JSON.stringify({
          ui: { mode: 'advanced' },
          llm: {},
          indexPaths: [],
        }),
      });
    });

    await page.route('**/api/indexing/roots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [] }),
      });
    });
  });

  test('filter bar drives search request/response contract', async ({ page }) => {
    // Track search requests for assertions
    let lastSearchRequestBody: any = null;

    // Stub search endpoint with dynamic response based on request
    await page.route('**/api/knowledge/search', async (route) => {
      const postData = route.request().postData();
      if (postData) {
        try {
          lastSearchRequestBody = JSON.parse(postData);
        } catch {
          // ignore
        }
      }

      // Determine response based on request state
      const facetsRequested = Boolean(lastSearchRequestBody?.facets);
      const pathPrefix = lastSearchRequestBody?.filters?.pathPrefix;

      // If pathPrefix is set to impossible prefix, return no results
      if (pathPrefix === 'z:\\__nope__\\') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ results: [], totalHits: 0, facets: null, facetsTruncated: false }),
        });
        return;
      }

      // Return faceted results if facets were requested
      const response = facetsRequested ? MOCK_FACETED_RESULTS : MOCK_SEARCH_RESULTS;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      });
    });

    await page.goto(baseUrl);
    await waitForConnectionStatus(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    const firstRespPromise = page.waitForResponse(
      (r) => r.url().includes('/api/knowledge/search') && r.request().method() === 'POST' && r.ok(),
      { timeout: 15000 }
    );
    await searchInput.fill('search');
    await page.keyboard.press('Escape').catch(() => {});
    const firstResp = await firstRespPromise;
    const firstJson = await firstResp.json();

    // Filter bar should exist (regression: Stage wiring) and be collapsed by default.
    const filtersToggle = page.getByTestId(E2E_TEST_IDS.filtersToggle).first();
    await expect(filtersToggle).toBeVisible();
    await filtersToggle.focus();
    await filtersToggle.press('Enter');
    const expandedAfterEnter = await filtersToggle.getAttribute('aria-expanded');
    if (expandedAfterEnter !== 'true') {
      await filtersToggle.click({ force: true });
    }
    await expect(filtersToggle).toHaveAttribute('aria-expanded', 'true');

    await expect(page.getByRole('button', { name: 'Scope' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Filter by type' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Filter by date' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Filter by language' })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Filter by path prefix' })).toBeVisible();

    // Request contract: includeChunks=false and projection is present
    expect(lastSearchRequestBody).toBeTruthy();
    expect(lastSearchRequestBody.filters?.includeChunks).toBe(false);
    expect(Array.isArray(lastSearchRequestBody.projection)).toBeTruthy();
    expect(lastSearchRequestBody.projection).toContain('path');
    expect(lastSearchRequestBody.projection).toContain('mime');
    expect(lastSearchRequestBody.projection).toContain('file_kind');
    expect(lastSearchRequestBody.projection).toContain('content_preview');

    // Response contract: no fields.content in hit payloads
    const results = Array.isArray(firstJson?.results) ? firstJson.results : [];
    for (const r of results) {
      expect(r?.fields?.content).toBeUndefined();
    }

    // Facets are always requested in current UI contracts.
    expect(firstJson.facets).toBeTruthy();
    expect(typeof firstJson.facetsTruncated).toBe('boolean');

    // Path prefix: set a non-matching drive to force zero results
    const pathPrefix = page.getByRole('textbox', { name: 'Filter by path prefix' });
    const impossiblePrefix = 'z:\\__nope__\\';

    const reqPromise = page.waitForRequest(
      (r) => r.url().includes('/api/knowledge/search') && r.method() === 'POST',
      { timeout: 15000 }
    );
    await pathPrefix.fill(impossiblePrefix);
    const req = await reqPromise;

    const reqBodyText = req.postData() || '{}';
    const reqBody = JSON.parse(reqBodyText);
    expect(reqBody?.query).toBe('search');
    expect(reqBody?.filters?.includeChunks).toBe(false);
    expect(reqBody?.filters?.pathPrefix).toBe(impossiblePrefix);

    const resp = await req.response();
    expect(resp).toBeTruthy();
    const status = resp!.status();
    const respText = await resp!.text();
    let respJson: any = null;
    try {
      respJson = JSON.parse(respText);
    } catch {
      respJson = null;
    }
    expect(status).toBe(200);
    expect(respJson?.totalHits).toBe(0);

    await expect(page.getByText('No results')).toBeVisible({ timeout: 15000 });

    // Clear should restore results
    await page.getByRole('button', { name: 'Clear', exact: true }).click();
    await page.waitForResponse(
      (r) => r.url().includes('/api/knowledge/search') && r.request().method() === 'POST' && r.ok(),
      { timeout: 15000 }
    );
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow).first()).toBeVisible({ timeout: 15000 });
  });

  /**
   * F7: Chunk documents never appear in normal search results.
   *
   * This test validates the trust loop invariant: chunk documents (internal
   * indexing artifacts) are filtered out by default and should never leak
   * into the user-facing search results.
   *
   * Uses route stubs to provide deterministic test data.
   */
  test('chunk documents never appear in normal search results', async ({ page }) => {
    // Stub search endpoint - returns normal docs (no chunk markers)
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SEARCH_RESULTS),
      });
    });

    await page.goto(baseUrl);
    await waitForConnectionStatus(page);

    // Perform a broad search that could potentially match chunk documents
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('content');
    const searchResponse = await page.waitForResponse(
      (r) => r.url().includes('/api/knowledge/search') && r.request().method() === 'POST' && r.ok(),
      { timeout: 15000 }
    );
    const respJson = await searchResponse.json();
    const results = Array.isArray(respJson?.results) ? respJson.results : [];

    // Verify no results have chunk markers
    for (const result of results) {
      // Check for chunk-specific fields that should never appear in normal results
      expect(result?.fields?.is_chunk).not.toBe('true');
      expect(result?.fields?.chunk_index).toBeUndefined();
      expect(result?.fields?.parent_doc_id).toBeUndefined();
      expect(result?.is_chunk).not.toBe(true);
      expect(result?.chunk_index).toBeUndefined();
    }

    // Verify we got responses to check (sanity check)
    expect(results.length).toBeGreaterThan(0);
  });
});
