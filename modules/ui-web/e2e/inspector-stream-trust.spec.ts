import { test, expect, type Page, type Locator } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';
import { ensureSearchInputReady } from './ai-harness';

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
        tookMs: 7,
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
              content_preview: 'Readme preview for trust streaming contract.',
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
        content: '# README\n\nTrust loop preview content for sources.',
        truncated: false,
        nextOffsetChars: 0,
        mime: 'text/markdown',
      }),
    });
  });

  await page.route('**/api/summarize/**', async (route) => {
    const citations = [
      {
        parentDocId: 'C:\\docs\\README.md',
        chunkIndex: 0,
        chunkTotal: 1,
        startChar: 3,
        endChar: 24,
        score: 0.92,
        excerpt: 'Trust loop preview',
      },
    ];
    const sseBody = createSseResponse([
      { event: 'meta', data: { usedRag: true, chunksUsed: 1, chunksFound: 1, truncated: false, citations } },
      { event: 'chunk', data: { text: 'Streaming summary chunk one. ' } },
      { event: 'chunk', data: { text: 'Streaming summary chunk two with citations.' } },
      {
        event: 'done',
        data: {
          fileCount: 1,
          usedRag: true,
          chunksUsed: 1,
          chunksFound: 1,
          citations,
          usage: { totalTokens: 420, promptTokens: 260, completionTokens: 160 },
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

async function selectForSummarize(row: Locator) {
  await row.hover();
  const rowSelected = await row.getAttribute('data-selected');
  if (rowSelected !== 'true') {
    await row.click();
  }
  const checkbox = row
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  if (await checkbox.isVisible().catch(() => false)) {
    const label = (await checkbox.getAttribute('aria-label').catch(() => '')) || '';
    if (/Select file/i.test(label)) {
      await checkbox.click();
    }
  }
}

async function ensureSearchResultsVisible(page: Page, query = 'readme') {
  const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
  await searchInput.fill(query);
  const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
  await expect(firstRow).toBeVisible({ timeout: 7_500 });
  return firstRow;
}

async function startSummarize(page: Page) {
  const inspectorSummarize = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
  if (await inspectorSummarize.isVisible().catch(() => false)) {
    await inspectorSummarize.click();
    return;
  }

  const searchActionsButton = page.getByTestId(E2E_TEST_IDS.searchActionsButton).first();
  if (await searchActionsButton.isVisible().catch(() => false)) {
    await searchActionsButton.click();
  } else {
    await page.getByRole('button', { name: /Open actions/i }).first().click();
  }

  await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible({ timeout: 5_000 });
  await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');
  await expect(page.getByTestId(E2E_TEST_IDS.actionSummarize)).toBeEnabled();
  await page.getByTestId(E2E_TEST_IDS.actionSummarize).click();
}

test.describe('Inspector trust UI during streaming', () => {
  test('keeps Context + Sources visible while batch summarize streams, and clears Generating after done', async ({ page }) => {
    await stubCommon(page);
    await page.goto(UI_URL);
    await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i, { timeout: 15_000 });

    const firstRow = await ensureSearchResultsVisible(page, 'trust');
    await selectForSummarize(firstRow);

    const previewTab = page.getByRole('button', { name: 'Preview', exact: true }).first();
    if (!(await previewTab.isVisible().catch(() => false))) {
      await page.keyboard.press(process.platform === 'darwin' ? 'Meta+\\' : 'Control+\\');
    }
    await expect(previewTab).toBeVisible({ timeout: 5_000 });

    const aiTab = page.getByRole('button', { name: /^AI$/ }).first();
    if (await aiTab.isVisible().catch(() => false)) {
      await aiTab.click();
    }

    await startSummarize(page);

    const generatingButton = page.getByRole('button', { name: /Generating\.\.\./i });
    await generatingButton.isVisible({ timeout: 5_000 }).catch(() => false);

    await expect(page.getByTestId(E2E_TEST_IDS.contextIndicatorAdvanced).first()).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(E2E_TEST_IDS.citation0).first()).toBeVisible({ timeout: 10_000 });

    await expect(generatingButton).toBeHidden({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /Summarize/i })).toBeVisible({ timeout: 15_000 });
  });
});
