/**
 * Preview highlighting + markdown toggle verification with deterministic stubs.
 */

import { test, expect, Page } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const UI_URL = `http://localhost:5173/?api_port=${API_PORT}&demo_ui_mode=advanced`;

type PreviewScenario = {
  query: string;
  docId: string;
  title: string;
  path: string;
  mime: string;
  contentPreview: string;
  previewContent: string;
  previewMime: string;
};

const MARKDOWN_SCENARIO: PreviewScenario = {
  query: 'TensorFlow',
  docId: 'C:\\docs\\ml-guide.md',
  title: 'ml-guide.md',
  path: 'C:\\docs\\ml-guide.md',
  mime: 'text/markdown',
  contentPreview: 'TensorFlow guide for local model workflows.',
  previewContent:
    '# TensorFlow Guide\n\nTensorFlow setup details for offline embeddings.\n\nUse TensorFlow checkpoints for export.',
  previewMime: 'text/markdown',
};

const TEXT_SCENARIO: PreviewScenario = {
  query: 'services',
  docId: 'C:\\docs\\services.txt',
  title: 'services.txt',
  path: 'C:\\docs\\services.txt',
  mime: 'text/plain',
  contentPreview: 'services and worker orchestration notes',
  previewContent: 'The services layer coordinates API requests, queueing, and indexing workers.',
  previewMime: 'text/plain',
};

async function stubCommon(page: Page, scenario: PreviewScenario) {
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
      }),
    });
  });

  await page.route('**/api/settings/v2', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ui: { mode: 'advanced', theme: 'dark' },
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
        tookMs: 7,
        results: [
          {
            id: scenario.docId,
            score: 0.95,
            name: scenario.title,
            path: scenario.path,
            fields: {
              doc_id: scenario.docId,
              title: scenario.title,
              filename: scenario.title,
              path: scenario.path,
              mime: scenario.mime,
              mime_base: scenario.mime.startsWith('text/') ? 'text' : 'application',
              file_kind: 'text',
              content_preview: scenario.contentPreview,
              language: 'en',
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
        docId: scenario.docId,
        content: scenario.previewContent,
        truncated: false,
        nextOffsetChars: 0,
        mime: scenario.previewMime,
      }),
    });
  });
}

async function openSearchWithQuery(page: Page, query: string) {
  await page.goto(UI_URL);
  await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i, { timeout: 15_000 });

  const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
  await searchInput.fill(query);
}

async function openFirstPreview(page: Page) {
  const rows = page.getByTestId(E2E_TEST_IDS.searchResultRow);
  await expect(rows.first()).toBeVisible({ timeout: 5_000 });
  const firstRow = rows.first();
  const maybePreviewResponse = page
    .waitForResponse((response) => response.url().includes('/api/preview') && response.ok(), { timeout: 5_000 })
    .catch(() => null);
  await firstRow.click();
  await maybePreviewResponse;

  const inspector = page.locator('aside[aria-label="File details"]');
  const inspectorVisibleAfterClick = await inspector.isVisible().catch(() => false);
  if (!inspectorVisibleAfterClick) {
    await firstRow.hover();
    const selectionControl = firstRow
      .locator(
        'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"]',
      )
      .first();
    if (await selectionControl.isVisible().catch(() => false)) {
      await selectionControl.click();
    }
    await firstRow.click();
  }

  await expect(inspector).toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId(E2E_TEST_IDS.inspectorPreview)).toBeVisible();
  return inspector;
}

test.describe('Preview: highlighting + Markdown toggle', () => {
  test('markdown file shows Markdown/Raw toggle; Raw highlights query terms', async ({ page }) => {
    await stubCommon(page, MARKDOWN_SCENARIO);
    await openSearchWithQuery(page, MARKDOWN_SCENARIO.query);
    const inspector = await openFirstPreview(page);

    await page.getByTestId(E2E_TEST_IDS.inspectorPreview).hover();
    const markdownButton = inspector.getByRole('button', { name: 'Markdown' });
    const rawButton = inspector.getByRole('button', { name: 'Raw' });
    await expect(markdownButton).toBeVisible();
    await expect(rawButton).toBeVisible();

    await rawButton.click();
    await expect(inspector.locator('mark').first()).toBeVisible({ timeout: 5_000 });
    await expect(inspector.locator('mark').first()).toContainText(/TensorFlow/i);

    await markdownButton.click();
    await expect(inspector.locator('mark')).toHaveCount(0);
  });

  test('non-markdown file highlights query terms without toggle', async ({ page }) => {
    await stubCommon(page, TEXT_SCENARIO);
    await openSearchWithQuery(page, TEXT_SCENARIO.query);
    const inspector = await openFirstPreview(page);

    await expect(inspector.getByRole('button', { name: 'Markdown' })).toHaveCount(0);
    await expect(inspector.getByRole('button', { name: 'Raw' })).toHaveCount(0);
    await expect(inspector.locator('mark').first()).toBeVisible({ timeout: 5_000 });
    await expect(inspector.locator('mark').first()).toContainText(/services/i);
  });
});
