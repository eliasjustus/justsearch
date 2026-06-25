import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

test.describe('Library (watched roots)', () => {
  test('Add Folder uses POST /api/indexing/roots (not /api/knowledge/ingest) and root appears in list', async ({ page }) => {
    // UI-ready handshake (best-effort)
    await page.route('**/api/ui/ready', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 200, body: '{}' });
      } else {
        await route.continue();
      }
    });

    // Status/inference/settings required for App shell to come up.
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
          mode: 'offline',
          available: false,
          starting: false,
          embeddingQueueSize: 0,
          vduQueueSize: 0,
          tier: 'cpu_only',
        }),
      });
    });

    await page.route('**/api/settings/v2', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ui: { theme: 'dark', density: 'comfort', mode: 'advanced', hasSeenTrustLoopNudge: false, excludePatterns: [] },
          llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
          indexPaths: [],
        }),
      });
    });

    // Guardrail: the legacy ingest endpoint must NOT be used by Add Folder.
    let ingestCalls = 0;
    await page.route('**/api/knowledge/ingest', async (route) => {
      ingestCalls += 1;
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'legacy ingest should not be called by Library Add Folder in this flow' }),
      });
    });

    // Roots endpoint: simulate a small in-memory watched-roots store.
    type RootEntry = { path: string; collection: string; fileCount?: number; lastIndexed?: string; status?: string };
    let serverRoots: RootEntry[] = [];
    await page.route('**/api/indexing/roots**', async (route) => {
      const method = route.request().method();
      if (method === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ roots: serverRoots }),
        });
        return;
      }
      if (method === 'POST') {
        const body = route.request().postDataJSON() as any;
        const path = typeof body?.path === 'string' ? body.path : '';
        const collection = typeof body?.collection === 'string' && body.collection ? body.collection : 'default';
        serverRoots = [{ path, collection, fileCount: 0, status: 'pending' }];
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ok' }) });
        return;
      }
      await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'method not allowed' }) });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Go to Library view.
    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();

    // Add a folder in browser mode (manual path input).
    await page.getByRole('button', { name: /Add Folder/i }).first().click();

    const manualInput = page.locator('input[placeholder="C:\\\\Users\\\\YourName\\\\Documents"]');
    await expect(manualInput).toBeVisible();

    const rootPath = 'C:\\\\demo-root';
    await manualInput.fill(rootPath);
    const manualForm = page.locator('form').filter({ has: manualInput });
    await manualForm.getByRole('button', { name: /^Add$/ }).click();

    // Root appears in the list (GET /api/indexing/roots reflects POST result).
    await expect(page.getByText(rootPath, { exact: true })).toBeVisible();

    // Ensure we never hit legacy ingest.
    expect(ingestCalls).toBe(0);
  });
});

