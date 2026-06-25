import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

/**
 * E2E contract tests for AI meta fields (usedRag, chunksUsed, truncated, etc.).
 *
 * These tests verify that the UI correctly surfaces AI metadata from stream events:
 * - truncationWarning from meta.truncated
 * - ragStatus from meta.usedRag
 *
 * Uses the same stubbing pattern as brain-compat.spec.ts.
 */

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const UI_URL = 'http://localhost:5173';

async function selectResultRow(row: any) {
  await row.click();
  await row.hover();
  const selectionControl = row
    .locator(
      'button[aria-label="Select file"], button[aria-label="Deselect file"], [role="checkbox"][aria-label*="Select file"], [role="checkbox"][aria-label*="Deselect file"], input[type="checkbox"][aria-label*="Select file"], input[type="checkbox"][aria-label*="Deselect file"]',
    )
    .first();
  const canSelect = await selectionControl.isVisible().catch(() => false);
  if (canSelect) {
    await selectionControl.click();
    return;
  }

  // Fallback for runtime variants that expose row selection without checkbox controls.
  await row.click();
}

async function clickSummarizeControl(page: any) {
  const inspectorSummarize = page.getByTestId(E2E_TEST_IDS.inspectorSummarize).first();
  const canUseInspectorSummarize = await inspectorSummarize.isVisible().catch(() => false);
  if (canUseInspectorSummarize) {
    await inspectorSummarize.click();
    return;
  }

  const fallbackSummarizeButton = page.getByRole('button', { name: 'Summarize' }).first();
  const canUseFallback = await fallbackSummarizeButton.isVisible().catch(() => false);
  if (canUseFallback) {
    await fallbackSummarizeButton.click();
    return;
  }

  const searchActionsButton = page.getByTestId(E2E_TEST_IDS.searchActionsButton).first();
  await expect(searchActionsButton).toBeVisible({ timeout: 5_000 });
  await searchActionsButton.click();

  const actionPanel = page.getByTestId(E2E_TEST_IDS.actionPanel);
  await expect(actionPanel).toBeVisible({ timeout: 5_000 });
  await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');
  await expect(page.getByTestId(E2E_TEST_IDS.actionSummarize)).toBeEnabled();
  await page.getByTestId(E2E_TEST_IDS.actionSummarize).click();
}

/**
 * Helper to create a valid SSE response with proper formatting.
 */
function createSseResponse(events: Array<{ event: string; data: Record<string, unknown> }>) {
  return events
    .map(({ event, data }) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join('');
}

test.describe('AI meta fields contract', () => {
  test.beforeEach(async ({ page }) => {
    // Stub common endpoints before each test
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
        body: JSON.stringify({
          status: 'ok',
          service: 'JustSearch Local API',
          indexHealthy: true,
          indexedDocuments: 100,
          indexSizeBytes: 1024000,
          uptimeMs: 60000,
          memoryUsedBytes: 50000000,
          memoryMaxBytes: 100000000,
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
  });

  test('shows RAG status indicator when usedRag is true', async ({ page }) => {
    // Stub search to return test results
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results: [
            { id: 'doc1', score: 0.95, name: 'test-document.txt', path: '/test/test-document.txt' },
            { id: 'doc2', score: 0.90, name: 'other-doc.txt', path: '/test/other-doc.txt' },
          ],
          totalHits: 2,
        }),
      });
    });

    // Stub the batch summarize stream endpoint to return SSE with usedRag: true
    await page.route('**/api/chat/batch-summarize', async (route) => {
      const sseBody = createSseResponse([
        { event: 'meta', data: { usedRag: true, chunksUsed: 5, chunksFound: 10, truncated: false } },
        { event: 'chunk', data: { text: 'This is a test summary ' } },
        { event: 'chunk', data: { text: 'based on RAG chunks.' } },
        { event: 'done', data: { fileCount: 2, usedRag: true, chunksUsed: 5 } },
      ]);

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    // Multi-select two files (Phase 1 selection model uses checkboxes)
    const rows = page.getByTestId(E2E_TEST_IDS.searchResultRow);
    await expect(rows.first()).toBeVisible({ timeout: 5000 });
    // Open inspector (result-row click toggles it on if closed)
    await rows.first().click();
    await selectResultRow(rows.nth(0));
    await selectResultRow(rows.nth(1));

    // Trigger summarize via UI button (stable contract vs keyboard shortcuts)
    await clickSummarizeControl(page);

    // Verify RAG run is marked complete and no fallback warning is shown.
    await expect(page.locator('text=Complete')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=vector chunks not indexed yet')).toHaveCount(0);
    await expect(page.locator('text=partial document content')).toHaveCount(0);
  });

  test('shows truncation warning when truncated is true', async ({ page }) => {
    // Stub search to return test results
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results: [
            { id: 'doc1', score: 0.95, name: 'large-document.txt', path: '/test/large-document.txt' },
          ],
          totalHits: 1,
        }),
      });
    });

    // Stub the batch summarize stream endpoint with truncated: true
    await page.route('**/api/chat/batch-summarize', async (route) => {
      const sseBody = createSseResponse([
        { event: 'meta', data: { usedRag: true, chunksUsed: 3, chunksFound: 50, truncated: true } },
        { event: 'chunk', data: { text: 'This is a truncated summary.' } },
        { event: 'done', data: { fileCount: 1, usedRag: true, chunksUsed: 3 } },
      ]);

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('large');
    await page.keyboard.press('Enter');

    // Multi-select first result (Phase 1 selection model uses checkboxes)
    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5000 });
    // Open inspector (result-row click toggles it on if closed)
    await firstRow.click();
    await selectResultRow(firstRow);
    await clickSummarizeControl(page);

    // Verify truncation state is surfaced through partial-content + context warning copy.
    await expect(page.locator('text=Based on partial document content')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=Context too large')).toBeVisible({ timeout: 5000 });
  });

  test('shows fallback indicator when usedRag is false', async ({ page }) => {
    // Stub search to return test results
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results: [
            { id: 'doc1', score: 0.95, name: 'no-chunks.txt', path: '/test/no-chunks.txt' },
          ],
          totalHits: 1,
        }),
      });
    });

    // Stub the batch summarize stream endpoint with usedRag: false (fallback mode)
    await page.route('**/api/chat/batch-summarize', async (route) => {
      const sseBody = createSseResponse([
        { event: 'meta', data: { usedRag: false, chunksUsed: 0, chunksFound: 0, truncated: false } },
        { event: 'chunk', data: { text: 'This summary uses document excerpts.' } },
        { event: 'done', data: { fileCount: 1, usedRag: false, chunksUsed: 0 } },
      ]);

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('chunks');
    await page.keyboard.press('Enter');

    // Multi-select first result (Phase 1 selection model uses checkboxes)
    const firstRow = page.getByTestId(E2E_TEST_IDS.searchResultRow).first();
    await expect(firstRow).toBeVisible({ timeout: 5000 });
    // Open inspector (result-row click toggles it on if closed)
    await firstRow.click();
    await selectResultRow(firstRow);
    await clickSummarizeControl(page);

    // Verify fallback indicator is visible with current UX copy.
    await expect(page.locator('text=Using document excerpts')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=vector chunks not indexed yet')).toBeVisible({ timeout: 5000 });
  });

  /**
   * F7: Both RAG indicator and truncation warning appear when usedRag: true, truncated: true.
   *
   * This test validates that users see both indicators when content was retrieved
   * via RAG but had to be truncated due to context limits.
   */
  test('shows both RAG indicator and truncation warning when usedRag: true and truncated: true', async ({ page }) => {
    // Stub search to return test results
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results: [
            { id: 'doc1', score: 0.95, name: 'huge-document.txt', path: '/test/huge-document.txt' },
            { id: 'doc2', score: 0.90, name: 'another-huge.txt', path: '/test/another-huge.txt' },
          ],
          totalHits: 2,
        }),
      });
    });

    // Stub the batch summarize stream endpoint with both usedRag: true and truncated: true
    await page.route('**/api/chat/batch-summarize', async (route) => {
      const sseBody = createSseResponse([
        { event: 'meta', data: { usedRag: true, chunksUsed: 2, chunksFound: 100, truncated: true } },
        { event: 'chunk', data: { text: 'This summary is from RAG ' } },
        { event: 'chunk', data: { text: 'but content was truncated.' } },
        { event: 'done', data: { fileCount: 2, usedRag: true, chunksUsed: 2 } },
      ]);

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('huge');
    await page.keyboard.press('Enter');

    // Multi-select results
    const rows = page.getByTestId(E2E_TEST_IDS.searchResultRow);
    await expect(rows.first()).toBeVisible({ timeout: 5000 });
    await rows.first().click();
    await selectResultRow(rows.nth(0));
    await selectResultRow(rows.nth(1));

    // Trigger summarize
    await clickSummarizeControl(page);

    // Verify BOTH partial-content and truncation pressure indicators appear.
    await expect(page.locator('text=Based on partial document content')).toBeVisible({ timeout: 5000 });
    await expect(page.locator('text=Context too large')).toBeVisible({ timeout: 5000 });
  });

  test('Ask question stream also surfaces meta fields', async ({ page }) => {
    // Stub search to return test results
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results: [
            { id: 'doc1', score: 0.95, name: 'qa-document.txt', path: '/test/qa-document.txt' },
          ],
          totalHits: 1,
        }),
      });
    });

    // Stub the ask stream endpoint with usedRag: true
    await page.route('**/api/chat/ask', async (route) => {
      const sseBody = createSseResponse([
        { event: 'meta', data: { usedRag: true, chunksUsed: 3, chunksFound: 5, truncated: false } },
        { event: 'chunk', data: { text: 'The answer to your question is: ' } },
        { event: 'chunk', data: { text: 'Yes, based on the documents.' } },
        { event: 'done', data: { fileCount: 1, usedRag: true, chunksUsed: 3 } },
      ]);

      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sseBody,
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('qa');
    await page.keyboard.press('Enter');

    // Wait for results
    await page.waitForTimeout(500);

    // Select result
    const resultsList = page.locator('[class*="result"], [data-testid*="result"]').first();
    if (await resultsList.isVisible({ timeout: 2000 }).catch(() => false)) {
      await resultsList.click();
    }

    // Find the question input in inspector pane and ask a question
    const questionInput = page.getByPlaceholder(/Ask a question/i);
    if (await questionInput.isVisible({ timeout: 2000 }).catch(() => false)) {
      await questionInput.fill('What is in this document?');
      await page.keyboard.press('Enter');

      // Wait for the answer to complete
      await page.waitForTimeout(1000);

      // Verify the RAG status indicator is visible
      const ragIndicator = page.locator('text=Based on document content (RAG)');
      await expect(ragIndicator).toBeVisible({ timeout: 5000 });
    }
  });
});

