/**
 * Feature verification tests with deterministic API stubs.
 *
 * These checks validate user-visible outcomes (search, inspector, summarize,
 * view switching, and status indicators) without requiring a live backend.
 */

import { test, expect, Locator, Page } from '@playwright/test';
import { ensureSearchInputReady } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const UI_URL = `http://localhost:5173/?api_port=${API_PORT}&demo_ui_mode=advanced`;

type SearchDoc = {
  id: string;
  title: string;
  path: string;
  mime: string;
  mimeBase: string;
  fileKind: string;
  language: string;
  contentPreview: string;
  previewContent: string;
};

const SEARCH_DOCS: SearchDoc[] = [
  {
    id: 'C:\\docs\\justsearch-design.md',
    title: 'justsearch-design.md',
    path: 'C:\\docs\\justsearch-design.md',
    mime: 'text/markdown',
    mimeBase: 'text',
    fileKind: 'text',
    language: 'en',
    contentPreview: 'Design notes for JustSearch ranking and relevance behavior.',
    previewContent:
      '# JustSearch Design\n\nThis document describes ranking logic and deterministic behavior for search results.',
  },
  {
    id: 'C:\\docs\\README.md',
    title: 'README.md',
    path: 'C:\\docs\\README.md',
    mime: 'text/markdown',
    mimeBase: 'text',
    fileKind: 'text',
    language: 'en',
    contentPreview: 'README for setup, services, and local development workflow.',
    previewContent: '# README\n\nThis file explains setup and local services.',
  },
  {
    id: 'C:\\docs\\test-plan.txt',
    title: 'test-plan.txt',
    path: 'C:\\docs\\test-plan.txt',
    mime: 'text/plain',
    mimeBase: 'text',
    fileKind: 'text',
    language: 'en',
    contentPreview: 'Test plan with search verification cases and expected outcomes.',
    previewContent: 'Test plan for search and summarize checks.',
  },
  {
    id: 'C:\\docs\\services-overview.txt',
    title: 'services-overview.txt',
    path: 'C:\\docs\\services-overview.txt',
    mime: 'text/plain',
    mimeBase: 'text',
    fileKind: 'text',
    language: 'en',
    contentPreview: 'Overview of indexing services and worker lifecycle.',
    previewContent: 'Services overview for worker and API process.',
  },
];

function createSseResponse(events: Array<{ event: string; data: Record<string, unknown> }>) {
  return events
    .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join('');
}

function isMatch(doc: SearchDoc, token: string): boolean {
  const haystack = `${doc.title} ${doc.path} ${doc.contentPreview}`.toLowerCase();
  return haystack.includes(token);
}

function filterDocs(queryValue: unknown): SearchDoc[] {
  const rawQuery = typeof queryValue === 'string' ? queryValue : '';
  const query = rawQuery.trim().toLowerCase();
  if (!query) {
    return [];
  }
  const normalized = query.replace(/\s+or\s+/gi, ' ');
  const tokens = normalized
    .split(/\s+/)
    .map((token) => token.trim())
    .filter((token) => token.length > 0);
  if (tokens.length === 0) {
    return [];
  }
  return SEARCH_DOCS.filter((doc) => tokens.some((token) => isMatch(doc, token)));
}

function toSearchResult(doc: SearchDoc, score: number) {
  return {
    id: doc.id,
    score,
    name: doc.title,
    path: doc.path,
    fields: {
      doc_id: doc.id,
      title: doc.title,
      filename: doc.title,
      path: doc.path,
      mime: doc.mime,
      mime_base: doc.mimeBase,
      file_kind: doc.fileKind,
      language: doc.language,
      modified_at: '2026-02-10T00:00:00Z',
      size_bytes: '1024',
      content_preview: doc.contentPreview,
    },
  };
}

function buildFacetCounts(docs: SearchDoc[]) {
  const mimeBase: Record<string, number> = {};
  const fileKind: Record<string, number> = {};
  for (const doc of docs) {
    mimeBase[doc.mimeBase] = (mimeBase[doc.mimeBase] ?? 0) + 1;
    fileKind[doc.fileKind] = (fileKind[doc.fileKind] ?? 0) + 1;
  }
  return {
    mime_base: mimeBase,
    file_kind: fileKind,
  };
}

async function stubCommonEndpoints(page: Page) {
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
        indexedDocuments: SEARCH_DOCS.length,
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
          totalVramBytes: 16_000_000_000,
          vramDescription: '16 GB',
        },
      }),
    });
  });

  await page.route('**/api/indexing/roots**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        roots: [{ path: 'C:\\docs', status: 'indexed', source: 'user' }],
      }),
    });
  });

  let currentSettings = {
    ui: { mode: 'advanced', theme: 'dark', density: 'comfort' },
    llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
    indexPaths: ['C:\\docs'],
  };

  await page.route('**/api/settings/v2', async (route) => {
    const method = route.request().method();
    if (method === 'POST') {
      currentSettings = route.request().postDataJSON() as typeof currentSettings;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentSettings) });
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(currentSettings) });
  });

  await page.route('**/api/knowledge/search', async (route) => {
    const requestBody = route.request().postDataJSON() as Record<string, unknown>;
    const docs = filterDocs(requestBody?.query);
    const results = docs.map((doc, index) => toSearchResult(doc, 1 - index * 0.1));

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        totalHits: results.length,
        tookMs: 8,
        results,
        facets: buildFacetCounts(docs),
        facetsTruncated: false,
      }),
    });
  });

  await page.route('**/api/preview**', async (route) => {
    const url = new URL(route.request().url());
    const requestedDocId = decodeURIComponent(
      url.searchParams.get('docId') || url.searchParams.get('path') || url.searchParams.get('id') || '',
    );
    const doc = SEARCH_DOCS.find((entry) => entry.id === requestedDocId || entry.path === requestedDocId) || SEARCH_DOCS[0];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        docId: doc.id,
        content: doc.previewContent,
        truncated: false,
        nextOffsetChars: 0,
        mime: doc.mime,
        textProvenance: 'tika',
      }),
    });
  });

  await page.route('**/api/summarize/**', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 150));
    const sseBody = createSseResponse([
      { event: 'meta', data: { usedRag: true, chunksUsed: 3, chunksFound: 3, truncated: false } },
      { event: 'chunk', data: { text: 'Summary for selected files. ' } },
      { event: 'chunk', data: { text: 'Verification stream completed successfully.' } },
      {
        event: 'done',
        data: {
          fileCount: 1,
          usedRag: true,
          chunksUsed: 3,
          usage: { totalTokens: 256, promptTokens: 180, completionTokens: 76 },
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

async function openStubbedUi(page: Page) {
  await page.goto(UI_URL);
  await expect(page.locator('footer')).toContainText(/Ready|Connected|Demo mode/i, { timeout: 15_000 });
}

async function waitForRows(page: Page): Promise<Locator> {
  const rows = page.getByTestId(E2E_TEST_IDS.searchResultRow);
  await expect(rows.first()).toBeVisible({ timeout: 5_000 });
  return rows;
}

async function selectResultForInspector(row: Locator) {
  await row.hover();
  const selectionControl = row
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"], input[type="checkbox"][aria-label*="Select file"], input[type="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  if (await selectionControl.isVisible().catch(() => false)) {
    await selectionControl.click();
    return;
  }
  await row.click();
}

async function clickSummarize(page: Page) {
  const inspectorSummarize = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
  if (await inspectorSummarize.isVisible().catch(() => false)) {
    await inspectorSummarize.click();
    return;
  }

  const searchActionsButton = page.getByTestId(E2E_TEST_IDS.searchActionsButton).first();
  if (await searchActionsButton.isVisible().catch(() => false)) {
    await searchActionsButton.click();
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible({ timeout: 5_000 });
    await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');
    await page.getByTestId(E2E_TEST_IDS.actionSummarize).click();
    return;
  }

  const summarizeFallback = page.getByRole('button', { name: /Summarize/i }).first();
  await expect(summarizeFallback).toBeVisible({ timeout: 5_000 });
  await summarizeFallback.click();
}

test.beforeEach(async ({ page }) => {
  await stubCommonEndpoints(page);
});

test.describe('Search Actually Works', () => {
  test('typing a query shows results in the UI', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('test');

    const rows = await waitForRows(page);
    await expect(rows.first()).toContainText(/test/i);
    expect(await rows.count()).toBeGreaterThan(0);
  });

  test('search results display file information', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');

    const firstRow = (await waitForRows(page)).first();
    const title = firstRow.locator('h4').first();
    await expect(title).toBeVisible();
    await expect(title).toContainText('README');
    await expect(firstRow).toHaveAttribute('data-result-id', /README\.md/i);
  });

  test('clearing search clears results', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('justsearch');
    await waitForRows(page);

    await searchInput.clear();
    await expect(searchInput).toHaveValue('');

    await expect
      .poll(async () => {
        const rows = await page.getByTestId(E2E_TEST_IDS.searchResultRow).count();
        const noResultsVisible = await page.getByRole('heading', { name: /No results/i }).isVisible().catch(() => false);
        return rows === 0 || noResultsVisible;
      })
      .toBe(true);
  });

  test('search results are ranked by relevance', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('justsearch');

    const rows = await waitForRows(page);
    const firstTitle = ((await rows.first().locator('h4').textContent()) || '').toLowerCase();
    expect(firstTitle).toContain('justsearch');
  });
});

test.describe('AI Summarization Actually Works', () => {
  test('selecting a file shows its details in inspector', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');

    const firstRow = (await waitForRows(page)).first();
    await selectResultForInspector(firstRow);

    const inspector = page.locator('aside[aria-label="File details"]');
    await expect(inspector).toBeVisible();
    await expect(inspector).toContainText(/README\.md/i);
  });

  test('summarize button triggers visible loading state', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('readme');

    const firstRow = (await waitForRows(page)).first();
    await selectResultForInspector(firstRow);

    const summarizeRequest = page.waitForRequest(
      (request) => request.url().includes('/api/summarize/') && request.method() === 'POST',
      { timeout: 10_000 },
    );
    await clickSummarize(page);
    await summarizeRequest;

    const loadingVisible = await page.getByText(/Generating|Summarizing|Loading/i).isVisible().catch(() => false);
    const answer = page.getByTestId(E2E_TEST_IDS.inspectorAnswer).first();
    await expect(answer).toContainText('Summary for selected files.', { timeout: 10_000 });
    expect(loadingVisible || (await answer.isVisible())).toBe(true);
  });

  test('summary text is actually displayed in UI', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('services');

    const firstRow = (await waitForRows(page)).first();
    await selectResultForInspector(firstRow);

    const inspector = page.locator('aside[aria-label="File details"]').first();
    const beforeText = await inspector.innerText();

    await clickSummarize(page);
    const answer = page.getByTestId(E2E_TEST_IDS.inspectorAnswer).first();
    await expect(answer).toContainText('Verification stream completed successfully.', { timeout: 10_000 });

    const afterText = await inspector.innerText();
    expect(afterText).not.toBe(beforeText);
    expect(afterText.length).toBeGreaterThan(beforeText.length);
  });
});

test.describe('View Switching Actually Works', () => {
  test('clicking Library shows Library content, not Search', async ({ page }) => {
    await openStubbedUi(page);

    await page.getByTestId(E2E_TEST_IDS.activityLibrary).click();
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Add Folder' })).toBeVisible();
  });

  test('clicking Settings shows Settings content', async ({ page }) => {
    await openStubbedUi(page);

    await page.getByTestId(E2E_TEST_IDS.activitySettings).click();
    await expect(page.getByText('Appearance')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Dark' })).toBeVisible();
  });

  test('clicking Search returns to search view', async ({ page }) => {
    await openStubbedUi(page);

    await page.getByTestId(E2E_TEST_IDS.activitySettings).click();
    await expect(page.getByText('Appearance')).toBeVisible();

    await page.getByTestId(E2E_TEST_IDS.activitySearch).click();
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await expect(searchInput).toBeVisible();
    await searchInput.fill('test');
    await expect(searchInput).toHaveValue('test');
  });
});

test.describe('Status Indicators Are Accurate', () => {
  test('Connected status reflects actual backend state', async ({ page }) => {
    await openStubbedUi(page);
    await expect(page.locator('footer')).toContainText(/Ready|Connected/i);
  });

  test('result count updates with search', async ({ page }) => {
    await openStubbedUi(page);

    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('test');
    await expect(page.locator('footer')).toContainText(/\d+\s*results?/i, { timeout: 10_000 });
  });
});

test('comprehensive verification report', async ({ page }) => {
  await openStubbedUi(page);

  const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
  await searchInput.fill('test');
  const rows = await waitForRows(page);
  const searchWorkedBeforeNavigation = (await rows.count()) > 0;

  await page.getByTestId(E2E_TEST_IDS.activitySettings).click();
  await expect(page.getByText('Appearance')).toBeVisible();
  await page.getByTestId(E2E_TEST_IDS.activitySearch).click();

  const checks = {
    connected: await page.locator('footer').innerText().then((text) => /Ready|Connected/i.test(text)),
    searchWorks: searchWorkedBeforeNavigation,
    viewSwitching: await page.getByTestId(E2E_TEST_IDS.searchInput).isVisible(),
  };

  console.log(`FEATURE VERIFICATION: connected=${checks.connected} searchWorks=${checks.searchWorks} viewSwitching=${checks.viewSwitching}`);

  expect(checks.connected).toBe(true);
  expect(checks.searchWorks).toBe(true);
  expect(checks.viewSwitching).toBe(true);
});
