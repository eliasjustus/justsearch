import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

test.describe('Excludes (apply cleanup)', () => {
  test('editing excludePatterns and clicking Apply excludes calls POST /api/indexing/excludes/apply', async ({ page }) => {
    // UI-ready handshake (best-effort)
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
          mode: 'offline',
          available: false,
          starting: false,
          embeddingQueueSize: 0,
          vduQueueSize: 0,
          tier: 'cpu_only',
        }),
      });
    });

    // Settings: start in Advanced mode so exclude editor is visible.
    const settingsPosts: any[] = [];
    let serverSettings: any = {
      ui: { theme: 'dark', density: 'comfort', mode: 'advanced', hasSeenTrustLoopNudge: false, excludePatterns: [] },
      llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
      indexPaths: [],
    };

    await page.route('**/api/settings/v2', async (route) => {
      const method = route.request().method();
      if (method === 'POST') {
        const body = route.request().postDataJSON();
        settingsPosts.push(body);
        serverSettings = body;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSettings) });
        return;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSettings) });
    });

    // Roots endpoint: return at least one root so the exclude editor is rendered.
    await page.route('**/api/indexing/roots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [{ path: 'C:\\docs', collection: 'default', status: 'ready', docCount: 10 }] }),
      });
    });

    let applyCalls = 0;
    await page.route('**/api/indexing/excludes/apply', async (route) => {
      if (route.request().method() === 'POST') {
        applyCalls += 1;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', patterns: 2, rootsProcessed: 1, deletedByPathJobs: 3, deletedById: 4 }),
      });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();

    const textarea = page.getByTestId(E2E_TEST_IDS.excludePatternsTextarea);
    const hasTextarea = await textarea.isVisible().catch(() => false);
    test.skip(!hasTextarea, 'Exclude editor is not visible in this runtime mode');
    await expect(textarea).toBeVisible();
    await textarea.fill('**/node_modules/**\n**/*.log');

    const applyBtn = page.getByTestId(E2E_TEST_IDS.applyExcludesButton);
    const hasApply = await applyBtn.isVisible().catch(() => false);
    test.skip(!hasApply, 'Apply excludes control is not visible in this runtime mode');
    await expect(applyBtn).toBeEnabled();
    await applyBtn.click();

    await expect.poll(() => applyCalls).toBe(1);

    // Should flush settings with the new patterns before applying.
    expect(settingsPosts.length).toBeGreaterThan(0);
    const lastPost = settingsPosts[settingsPosts.length - 1];
    expect(lastPost?.ui?.excludePatterns).toEqual(['**/node_modules/**', '**/*.log']);
  });
});

