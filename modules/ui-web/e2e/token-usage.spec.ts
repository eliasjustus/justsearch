import { test, expect, type Locator, type Page } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const UI_URL = `http://localhost:5173/?api_port=${API_PORT}&demo_ui_mode=advanced`;

function createSseResponse(events: Array<{ event: string; data: Record<string, unknown> }>) {
  return events
    .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join('');
}

async function stubCommon(page: Page) {
  await page.route('**/api/ui/ready', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 200, body: '{}' });
      return;
    }
    await route.continue();
  });

  await page.route('**/api/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        status: 'ok',
        service: 'JustSearch Local API',
        isReady: true,
        indexHealthy: true,
        indexedDocuments: 1,
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
        tier: 'gpu_12gb_plus',
        gpu: {
          cudaAvailable: true,
          totalVramBytes: 16000000000,
          vramDescription: '16 GB',
        },
      }),
    });
  });

  await page.route('**/api/settings/v2', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ui: { mode: 'advanced', theme: 'dark', density: 'comfort' },
        llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
        indexPaths: ['C:\\docs'],
      }),
    });
  });

  await page.route('**/api/indexing/roots**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ roots: [{ path: 'C:\\docs', status: 'indexed', source: 'user' }] }),
    });
  });

  await page.route('**/api/knowledge/search', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        totalHits: 1,
        tookMs: 5,
        results: [
          {
            id: 'C:\\docs\\README.md',
            score: 0.95,
            fields: {
              doc_id: 'C:\\docs\\README.md',
              title: 'README.md',
              filename: 'README.md',
              path: 'C:\\docs\\README.md',
              mime: 'text/markdown',
              mime_base: 'text',
              file_kind: 'text',
              language: 'en',
              content_preview: 'README with deterministic usage-token sample.',
            },
          },
        ],
      }),
    });
  });

  await page.route('**/api/preview**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        docId: 'C:\\docs\\README.md',
        content: '# README\n\nToken usage deterministic preview.',
        truncated: false,
        nextOffsetChars: 0,
        mime: 'text/markdown',
      }),
    });
  });

  await page.route('**/api/summarize/**', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 120));
    const sseBody = createSseResponse([
      { event: 'meta', data: { usedRag: true, chunksUsed: 3, chunksFound: 3, truncated: false } },
      { event: 'chunk', data: { text: 'Deterministic summary output.' } },
      {
        event: 'done',
        data: {
          fileCount: 1,
          usedRag: true,
          chunksUsed: 3,
          contextLimitTokens: 4096,
          usage: { totalTokens: 640, promptTokens: 420, completionTokens: 220 },
        },
      },
    ]);

    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sseBody,
    });
  });
}

async function selectFirstRow(row: Locator) {
  await row.click();
  await row.hover();
  const selectionControl = row
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  if (await selectionControl.isVisible().catch(() => false)) {
    await selectionControl.click();
  }
  await row.click();
}

async function triggerSummarize(page: Page) {
  const inspector = page.locator('aside[aria-label="File details"]');
  await expect(inspector).toBeVisible({ timeout: 5_000 });

  const aiTab = page.getByRole('button', { name: /^AI$/ }).first();
  if (await aiTab.isVisible().catch(() => false)) {
    await aiTab.click();
  }

  const inspectorSummarize = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
  if (await inspectorSummarize.isVisible().catch(() => false)) {
    await inspectorSummarize.click();
    return;
  }

  const searchActionsButton = page.getByTestId(E2E_TEST_IDS.searchActionsButton).first();
  await expect(searchActionsButton).toBeVisible({ timeout: 5_000 });
  await searchActionsButton.click();

  await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible({ timeout: 5_000 });
  await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');
  await expect(page.getByTestId(E2E_TEST_IDS.actionSummarize)).toBeEnabled();
  await page.getByTestId(E2E_TEST_IDS.actionSummarize).click();
}

test.describe('Token usage UI', () => {
  test.beforeEach(async ({ page }) => {
    await stubCommon(page);
  });

  test('shows per-request usage after batch summarize', async ({ page }) => {
    await page.goto(UI_URL);
    await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i, { timeout: 15_000 });

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');

    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5_000 });
    await selectFirstRow(firstRow);

    const summarizeRequest = page.waitForRequest(
      (request) => request.url().includes('/api/summarize/') && request.method() === 'POST',
      { timeout: 10_000 },
    );
    await triggerSummarize(page);
    await summarizeRequest;

    const inspector = page.locator('aside[aria-label="File details"]');
    await expect(inspector).toContainText(/Used:\s*\d[\d,]*.*tokens/i, { timeout: 10_000 });
  });
});
