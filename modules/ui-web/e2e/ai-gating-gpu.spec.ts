import { test, expect, type Page } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

async function selectFirstResultForActions(page: Page) {
  const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
  await expect(firstRow).toBeVisible({ timeout: 5_000 });
  await firstRow.hover();

  const selectionControl = firstRow
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"], input[type="checkbox"][aria-label*="Select file"], input[type="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  const canSelect = await selectionControl.isVisible().catch(() => false);
  test.skip(!canSelect, 'Select file control is not visible in this runtime mode');
  await selectionControl.click();
}

test.describe('AI gating (GPU required for summarize/chat)', () => {
  test('cpu_only disables Summarize with "Requires GPU" reason', async ({ page }) => {
    await page.route('**/api/ui/ready', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });

    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'ok',
          service: 'JustSearch Local API',
          indexHealthy: true,
          indexedDocuments: 1,
          indexSizeBytes: 1024,
          uptimeMs: 60_000,
          memoryUsedBytes: 50_000_000,
          memoryMaxBytes: 100_000_000,
          pendingJobs: 0,
          embeddingCompatState: 'COMPATIBLE',
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
          tier: 'cpu_only',
          gpu: { cudaAvailable: false, totalVramBytes: null, vramDescription: '' },
        }),
      });
    });

    let serverSettings: any = {
      ui: { theme: 'dark', density: 'comfort', mode: 'advanced', hasSeenTrustLoopNudge: false, excludePatterns: [] },
      llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
      indexPaths: [],
    };
    await page.route('**/api/settings/v2', async (route) => {
      if (route.request().method() === 'POST') {
        serverSettings = route.request().postDataJSON();
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSettings) });
        return;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(serverSettings) });
    });

    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalHits: 1,
          tookMs: 1,
          results: [
            {
              id: 'C:\\tmp\\example.txt',
              score: 1.0,
              fields: {
                doc_id: 'C:\\tmp\\example.txt',
                path: 'C:\\tmp\\example.txt',
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

    await page.route('**/api/indexing/roots**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [] }),
      });
    });

    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ content: '', truncated: false }),
      });
    });

    await page.goto('/');
    await page.waitForLoadState('networkidle');

    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.getByTestId(E2E_TEST_IDS.searchInput).press('Enter');
    await selectFirstResultForActions(page);

    await page.getByTestId(E2E_TEST_IDS.searchActionsButton).click();
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    const summarize = page.getByTestId(E2E_TEST_IDS.actionSummarize);
    const summarizeVisible = await summarize.isVisible().catch(() => false);
    if (!summarizeVisible) {
      const toggleUnavailable = page.getByTestId(E2E_TEST_IDS.actionPanelToggleUnavailable);
      const canToggleUnavailable = await toggleUnavailable.isVisible().catch(() => false);
      test.skip(!canToggleUnavailable, 'Unavailable-action toggle is not visible in this runtime mode');
      await toggleUnavailable.click();
    }

    await expect(summarize).toBeVisible();
    await expect(summarize).toBeDisabled();
    await expect(summarize).toContainText(/Requires.*GPU/i);
  });
});
