import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

/**
 * E2E contract tests for Inspector context/capacity UX.
 *
 * Goals:
 * - Simple mode shows qualitative state (Safe / Near limit / Too large)
 * - Advanced mode shows token details (Limit/Used) rather than the simplified label
 *
 * Uses API stubbing to be deterministic.
 */

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const UI_URL = 'http://localhost:5173';

function createSseResponse(events: Array<{ event: string; data: Record<string, unknown> }>) {
  return events
    .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join('');
}

async function selectFirstResultForActions(page: any) {
  const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
  await expect(firstRow).toBeVisible({ timeout: 5_000 });
  await firstRow.hover();
  const rowSelected = await firstRow.getAttribute('data-selected');
  if (rowSelected !== 'true') {
    await firstRow.click();
  }
  const selectionControl = firstRow
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  if (await selectionControl.isVisible().catch(() => false)) {
    const label = (await selectionControl.getAttribute('aria-label').catch(() => '')) || '';
    if (/Select file/i.test(label)) {
      await selectionControl.click();
    }
  }

  const previewTab = page.getByRole('button', { name: 'Preview', exact: true }).first();
  if (!(await previewTab.isVisible().catch(() => false))) {
    await page.keyboard.press(process.platform === 'darwin' ? 'Meta+\\' : 'Control+\\');
  }
  await expect(previewTab).toBeVisible({ timeout: 5_000 });
}

async function clickSummarizeControl(page: any) {
  const aiTab = page.getByRole('button', { name: /^AI$/ }).first();
  if (await aiTab.isVisible().catch(() => false)) {
    await aiTab.click();
  }

  const inspectorSummarize = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
  if (await inspectorSummarize.isVisible().catch(() => false)) {
    await inspectorSummarize.click();
    return;
  }

  const summarizeBtn = page.getByRole('button', { name: /summarize/i }).first();
  if (await summarizeBtn.isVisible().catch(() => false)) {
    await summarizeBtn.click();
    return;
  }

  const searchActionsButton = page.getByTestId(E2E_TEST_IDS.searchActionsButton).first();
  const hasSearchActionButton = await searchActionsButton.isVisible().catch(() => false);
  if (hasSearchActionButton) {
    await searchActionsButton.click();
  } else {
    await page.getByRole('button', { name: /Open actions/i }).first().click();
  }
  await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible({ timeout: 5_000 });
  await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');
  const actionSummarize = page.getByTestId(E2E_TEST_IDS.actionSummarize);
  const canUseAction = await actionSummarize.isEnabled().catch(() => false);
  if (canUseAction) {
    await actionSummarize.click();
    return;
  }

  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+Shift+P' : 'Control+Shift+P');
  await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible({ timeout: 5_000 });
  await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');
  await expect(actionSummarize).toBeEnabled();
  await actionSummarize.click();
}

function stubCommon(page: any, opts: { uiMode: 'simple' | 'advanced' }) {
  // UI-ready handshake (best-effort)
  return Promise.all([
    page.route('**/api/ui/ready', async (route: any) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({ status: 200, body: '{}' });
      } else {
        await route.continue();
      }
    }),

    page.route('**/api/status', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 'ok',
          service: 'JustSearch Local API',
          isReady: true,
          indexHealthy: true,
          indexedDocuments: 100,
          pendingJobs: 0,
          embeddingCompatState: 'COMPATIBLE',
        }),
      });
    }),

    page.route('**/api/inference/status', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          mode: 'online',
          available: true,
          starting: false,
          switching: false,
          embeddingQueueSize: 0,
          vduQueueSize: 0,
          configuredContextTokens: 4096,
          tier: 'gpu_12gb_plus',
          gpu: {
            cudaAvailable: true,
            totalVramBytes: 16000000000,
            vramDescription: '16 GB',
          },
        }),
      });
    }),

    page.route('**/api/indexing/roots**', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ roots: [] }),
      });
    }),

    page.route('**/api/settings/v2', async (route: any) => {
      const method = route.request().method();
      if (method === 'POST') {
        // Echo POST body to simulate persistence.
        const posted = route.request().postDataJSON();
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(posted) });
        return;
      }

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ui: { theme: 'dark', mode: opts.uiMode },
          llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
        }),
      });
    }),

    page.route('**/api/knowledge/search', async (route: any) => {
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
    }),

    page.route('**/api/preview**', async (route: any) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'C:\\\\tmp\\\\example.txt',
          content: 'Example preview content',
          truncated: false,
          nextOffsetChars: 0,
          mime: 'text/plain',
        }),
      });
    }),

    page.route('**/api/summarize/**', async (route: any) => {
      const sseBody = createSseResponse([
        { event: 'meta', data: { usedRag: true, chunksUsed: 2, chunksFound: 3, truncated: false } },
        { event: 'chunk', data: { text: 'Summary content for context indicator tests.' } },
        {
          event: 'done',
          data: {
            fileCount: 1,
            usedRag: true,
            chunksUsed: 2,
            contextLimitTokens: 4096,
            usage: { totalTokens: 600, promptTokens: 250, completionTokens: 350 },
          },
        },
      ]);

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    }),
  ]);
}

test.describe('Inspector context capacity UX', () => {
  test('Simple mode shows qualitative Context state (Safe)', async ({ page }) => {
    await stubCommon(page, { uiMode: 'simple' });

    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });

    // Perform a search and select a result so the Inspector is populated.
    await searchInput.fill('test');
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow).first()).toBeVisible({ timeout: 5_000 });
    await selectFirstResultForActions(page);
    await clickSummarizeControl(page);

    const simpleIndicator = page.getByTestId(E2E_TEST_IDS.contextIndicatorSimple);
    await expect(simpleIndicator).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId(E2E_TEST_IDS.contextStatePill)).toContainText('Safe');

    await expect(page.getByTestId(E2E_TEST_IDS.contextIndicatorAdvanced)).toHaveCount(0);
  });

  test('Advanced mode shows token details (Limit: … tokens)', async ({ page }) => {
    await stubCommon(page, { uiMode: 'advanced' });

    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });

    // Perform a search and select a result so the Inspector is populated.
    await searchInput.fill('test');
    await expect(page.getByTestId(E2E_TEST_IDS.searchResultRow).first()).toBeVisible({ timeout: 5_000 });
    await selectFirstResultForActions(page);
    await clickSummarizeControl(page);

    const advIndicator = page.getByTestId(E2E_TEST_IDS.contextIndicatorAdvanced);
    await expect(advIndicator).toBeVisible({ timeout: 5_000 });
    await expect(advIndicator).toContainText('Limit:');

    await expect(page.getByTestId(E2E_TEST_IDS.contextIndicatorSimple)).toHaveCount(0);
  });
});
