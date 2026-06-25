import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

test.describe('UI mode (Simple vs Advanced)', () => {
  test('default is Simple; toggling Advanced reveals advanced-only controls', async ({ page }) => {
    // Minimal stubs to get the app into a stable state.
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
        body: JSON.stringify({ status: 'ready', isReady: true, embeddingCompatState: 'COMPATIBLE' }),
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
          tier: 'cpu_only',
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

    await page.route('**/api/indexing/suggested-roots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [] }),
      });
    });

    // Settings: start in Simple mode. Echo POST bodies to simulate persistence.
    let serverSettings: any = {
      ui: { theme: 'dark', density: 'comfort', mode: 'simple', hasSeenTrustLoopNudge: false, excludePatterns: [] },
      llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
      indexPaths: [],
    };

    await page.route('**/api/settings/v2', async (route) => {
      const method = route.request().method();
      if (method === 'POST') {
        serverSettings = route.request().postDataJSON();
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSettings) });
        return;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSettings) });
    });

    // Search results endpoint (avoid failures; minimal payload).
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 1,
          tookMs: 1,
          results: [
            {
              id: 'C:\\\\tmp\\\\example.txt',
              score: 1.0,
              fields: {
                doc_id: 'C:\\\\tmp\\\\example.txt',
                path: 'C:\\\\tmp\\\\example.txt',
                filename: 'example.txt',
                title: 'example.txt',
                mime_base: 'text',
                file_kind: 'file',
                language: 'en',
                modified_at: '2026-01-01T00:00:00Z',
                size_bytes: '123',
                content_preview: 'Example content',
              },
            },
          ],
        }),
      });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Trigger a search so the SearchFiltersBar is rendered (Launchpad is shown when query is empty).
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('test');
    await searchInput.press('Enter');
    await page.waitForTimeout(300);

    // In Simple mode, advanced-only Search filter input should not be visible.
    const filtersToggle = page.getByTestId(E2E_TEST_IDS.filtersToggle).first();
    await filtersToggle.focus();
    await filtersToggle.press('Enter');
    await expect(page.locator('input[placeholder="Path prefix"]')).toHaveCount(0);

    // Switch to Settings and toggle Advanced.
    await page.getByTestId(E2E_TEST_IDS.activitySettings).click();
    await page.getByRole('button', { name: 'Advanced' }).click();

    // Go back to Search and confirm advanced-only control becomes visible.
    await page.getByTestId(E2E_TEST_IDS.activitySearch).click();
    const filtersToggleAfterModeSwitch = page.getByTestId(E2E_TEST_IDS.filtersToggle).first();
    await expect(filtersToggleAfterModeSwitch).toBeVisible();
    await expect(page.getByRole('button', { name: /^(Basic|Lucene)$/ })).toBeVisible();
    await filtersToggleAfterModeSwitch.focus();
    await filtersToggleAfterModeSwitch.press('Enter');
    await expect(page.locator('input[placeholder="Path prefix"]')).toBeVisible();

    // Library: Reindex controls are Advanced-only.
    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect(page.getByRole('button', { name: /Reindex All/i })).toBeVisible();

    // Brain view should load and expose advanced controls when available.
    await page.getByTestId(E2E_TEST_IDS.activityBrain).click();
    const brainConfigured = page.getByText('Configure local language models');
    const brainNoConnection = page.getByText('No API Connection');
    await expect(brainConfigured.or(brainNoConnection)).toBeVisible();
  });
});
