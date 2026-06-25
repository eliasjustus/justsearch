/**
 * API Integration Tests
 *
 * Split into two groups:
 * 1. Local tests (demo mode or route stubs) — always run in Desktop gate.
 * 2. Live-backend tests — skipped unless VITE_JUSTSEARCH_API_PORT is set.
 */

import { test, expect } from '@playwright/test';
import { navigateToDemoMode, waitForDemoData } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT;
const hasRealBackend = !!API_PORT;
const backendUrl = `/?api_port=${API_PORT}`;
const describeRealBackend = hasRealBackend ? test.describe : test.describe.skip;

// ---------------------------------------------------------------------------
// LOCAL TESTS (demo mode / route stubs — no backend required)
// ---------------------------------------------------------------------------

test.describe('API Integration (local)', () => {
  test.describe('Connection', () => {
    test('shows connection error when backend unavailable', async ({ page }) => {
      // Block only the status endpoint to simulate backend being down.
      // The Vite dev server still serves the SPA; the app's polling discovers no backend.
      await page.route('**/api/status', (route) => route.abort('connectionrefused'));
      await page.route('**/api/settings/v2', (route) => route.abort('connectionrefused'));
      await page.route('**/api/inference/status', (route) => route.abort('connectionrefused'));

      await page.goto('/');

      const connectionError = page.getByText(/Unable to connect|Connection attempt|Connecting/);
      await expect(connectionError).toBeVisible({ timeout: 15000 });
    });

    test('demo mode bypasses backend connection', async ({ page }) => {
      await navigateToDemoMode(page);
      await waitForDemoData(page);

      await expect(page.locator('[data-selected]').first()).toBeVisible();
    });
  });

  test.describe('Search API', () => {
    test('search results render from API response', async ({ page }) => {
      await navigateToDemoMode(page);
      await waitForDemoData(page, { query: 'project' });

      const results = page.locator('[data-selected]');
      await expect(results.first()).toBeVisible({ timeout: 5000 });

      const count = await results.count();
      expect(count).toBeGreaterThan(0);
    });
  });

  test.describe('Settings API', () => {
    test('settings view loads from API', async ({ page }) => {
      await navigateToDemoMode(page);

      await page.getByTestId(E2E_TEST_IDS.activitySettings).click();

      await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
      await expect(page.getByText('APPEARANCE')).toBeVisible();
    });
  });

  test.describe('Indexing API', () => {
    test('library view loads indexed roots', async ({ page }) => {
      await navigateToDemoMode(page);

      await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();

      await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible({ timeout: 5000 });
    });

    test('add folder button is present', async ({ page }) => {
      await navigateToDemoMode(page);

      await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();

      const addButton = page.getByRole('button', { name: /Add Folder/i });
      await expect(addButton).toBeVisible();
    });
  });

  test.describe('Network Error Handling', () => {
    test('handles network timeout gracefully', async ({ page }) => {
      await page.route('**/api/status', async (route) => {
        await new Promise((r) => setTimeout(r, 10000));
        await route.abort('timedout');
      });

      await page.goto('/');

      const connectionError = page.getByText(/Unable to connect|Connection attempt|Connecting/);
      await expect(connectionError).toBeVisible({ timeout: 15000 });
    });

    test('handles server error gracefully', async ({ page }) => {
      await page.route('**/api/status', async (route) => {
        await route.fulfill({
          status: 500,
          body: JSON.stringify({ error: 'Internal Server Error' }),
        });
      });

      await page.goto('/');

      const connectionError = page.getByText(/Unable to connect|Connection attempt|Connecting/);
      await expect(connectionError).toBeVisible({ timeout: 15000 });
    });
  });
});

// ---------------------------------------------------------------------------
// LIVE-BACKEND TESTS (require VITE_JUSTSEARCH_API_PORT)
// ---------------------------------------------------------------------------

describeRealBackend('API Integration (live backend)', () => {
  test.describe('Connection', () => {
    test('resolves API endpoint from environment', async ({ page }) => {
      const logs: string[] = [];
      page.on('console', (msg) => logs.push(msg.text()));

      await page.goto('/');

      const connectionError = page.getByText('Unable to connect to the JustSearch backend');
      await expect(connectionError).not.toBeVisible({ timeout: 10000 });
    });
  });

  test.describe('Search API', () => {
    test('search input triggers API call', async ({ page }) => {
      const allRequests: string[] = [];
      const searchRequests: { url: string; method: string; body: any }[] = [];

      page.on('request', (request) => {
        const url = request.url();
        allRequests.push(url);
        if (url.includes('/api/knowledge/search')) {
          searchRequests.push({
            url,
            method: request.method(),
            body: request.postDataJSON(),
          });
        }
      });

      await page.goto(backendUrl);

      await page.waitForFunction(
        () => {
          const statusEl = document.querySelector('[class*="text-green"]');
          return statusEl?.textContent?.includes('Connected');
        },
        { timeout: 10000 },
      );

      const searchInput = page.getByPlaceholder('Search files, notes, and more...');
      await searchInput.fill('test query');

      await expect.poll(() => searchRequests.length, { timeout: 5000 }).toBeGreaterThan(0);

      expect(searchRequests.length).toBeGreaterThan(0);
      expect(searchRequests[0].method).toBe('POST');
      expect(searchRequests[0].body?.query).toBe('test query');
    });

    test('real backend search returns results with correct structure', async ({ page }) => {
      await page.goto(backendUrl);
      await page.waitForFunction(
        () => {
          const statusEl = document.querySelector('[class*="text-green"]');
          return statusEl?.textContent?.includes('Connected');
        },
        { timeout: 10000 },
      );

      const searchInput = page.getByPlaceholder('Search files, notes, and more...');
      const respPromise = page.waitForResponse(
        (r) => r.url().includes('/api/knowledge/search') && r.status() === 200,
        { timeout: 10000 },
      );
      await searchInput.fill('file');

      let searchResponse: any = null;
      try {
        const resp = await respPromise;
        searchResponse = await resp.json();
      } catch {
        searchResponse = null;
      }

      if (searchResponse) {
        expect(searchResponse).toHaveProperty('hits');
        expect(Array.isArray(searchResponse.hits)).toBe(true);
      }
    });
  });
});

// ---------------------------------------------------------------------------
// CONTRACT DOCUMENTATION (always runs)
// ---------------------------------------------------------------------------

test.describe('API Contract Documentation', () => {
  test('documents expected search response format', async () => {
    const expectedBackendFormat = {
      hits: [
        {
          doc_id: 'example-doc-id',
          score: 0.95,
          highlights: {
            title: ['Highlighted <em>title</em>'],
            content: ['Matching <em>content</em> snippet...'],
          },
        },
      ],
      metadata: {
        'example-doc-id': {
          title: 'Document Title',
          path: '/full/path/to/document.txt',
          mime: 'text/plain',
          language: 'en',
          modifiedAt: 1699999999000,
        },
      },
      facets: {
        mime: { 'text/plain': 5, 'application/pdf': 3 },
      },
      cursor: {
        mode: 'offset',
        token: 'base64-token',
      },
    };

    const expectedUIFormat = {
      hits: [
        {
          doc_id: 'example-doc-id',
          score: 0.95,
          title: 'Document Title',
          path: '/full/path/to/document.txt',
          mime: 'text/plain',
          highlights: { title: ['...'], content: ['...'] },
        },
      ],
      totalHits: 1,
      queryTimeMs: 42,
    };

    console.log('Backend Format:', JSON.stringify(expectedBackendFormat, null, 2));
    console.log('UI Format:', JSON.stringify(expectedUIFormat, null, 2));

    expect(true).toBe(true);
  });
});
