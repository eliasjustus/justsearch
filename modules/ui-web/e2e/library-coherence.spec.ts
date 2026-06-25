import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

test.describe('Library/Launchpad coherence', () => {
  async function stubCommon(page: any, opts: { indexedDocuments: number; roots?: { path: string; collection: string }[]; pendingJobs?: number }) {
    // UI-ready handshake (best-effort)
    await page.route('**/api/ui/ready', async (route: any) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 200, body: '{}' });
      } else {
        await route.continue();
      }
    });

    await page.route('**/api/status', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'ok',
          service: 'JustSearch Local API',
          isReady: true,
          indexHealthy: true,
          indexedDocuments: opts.indexedDocuments,
          indexSizeBytes: 1024,
          uptimeMs: 60_000,
          memoryUsedBytes: 50_000_000,
          memoryMaxBytes: 100_000_000,
          pendingJobs: opts.pendingJobs ?? 0,
          embeddingCompatState: 'COMPATIBLE',
        }),
      });
    });

    await page.route('**/api/inference/status', async (route: any) => {
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

    await page.route('**/api/settings/v2', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ui: { theme: 'dark', density: 'comfort', mode: 'simple', hasSeenTrustLoopNudge: false, excludePatterns: [] },
          llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
          indexPaths: [],
        }),
      });
    });

    await page.route('**/api/indexing/roots**', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: opts.roots ?? [] }),
      });
    });
  }

  test('when docs exist but roots are empty: use watched-roots copy (Launchpad + Library)', async ({ page }) => {
    await stubCommon(page, { indexedDocuments: 72 });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Launchpad "seeded" state: help files indexed but no watched folders.
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadSetup)).toBeVisible();
    await expect(page.getByText('Add a watched folder')).toBeVisible();
    await expect(page.getByText(/from built-in help/i)).toBeVisible();

    // Library empty-state should use watched-roots wording.
    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();
    await expect(page.getByText('Your library is ready to grow')).toBeVisible();
    await expect(page.getByText(/72 files indexed/i)).toBeVisible();
  });

  test('when no docs and roots are empty: use first-time setup copy (Launchpad + Library)', async ({ page }) => {
    await stubCommon(page, { indexedDocuments: 0 });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Launchpad "fresh" state: no docs, no folders.
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadSetup)).toBeVisible();
    await expect(page.getByText('Add a folder to index')).toBeVisible();

    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();
    await expect(page.getByText('Start building your library')).toBeVisible();
  });

  test('when roots exist but no docs yet: indexing state (Launchpad)', async ({ page }) => {
    await stubCommon(page, {
      indexedDocuments: 0,
      roots: [{ path: 'C:\\Users\\test\\Documents', collection: 'default' }],
      pendingJobs: 42,
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Launchpad "indexing" state: roots configured but nothing indexed yet.
    await expect(page.getByText(/Indexing your files/i)).toBeVisible();
    await expect(page.getByText(/42 pending/i)).toBeVisible();

    // Setup CTA should NOT be visible (user already added a folder).
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadSetup)).not.toBeVisible();

    // Help topic cards and nav row should be visible.
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadQueryChip).first()).toBeVisible();
  });

  test('when roots and docs exist: ready state with stats (Launchpad)', async ({ page }) => {
    await stubCommon(page, {
      indexedDocuments: 500,
      roots: [{ path: 'C:\\Users\\test\\Documents', collection: 'default' }],
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    // Launchpad "ready" state: greeting + file count.
    await expect(page.getByText(/Good (morning|afternoon|evening)/i)).toBeVisible();
    await expect(page.getByText('500 files indexed and ready')).toBeVisible();

    // Help topic chips should be visible (compact variant in ready state).
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadQueryChip).first()).toBeVisible();

    // Setup CTA should NOT be visible.
    await expect(page.getByTestId(E2E_TEST_IDS.launchpadSetup)).not.toBeVisible();
  });
});

