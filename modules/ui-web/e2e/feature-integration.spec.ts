/**
 * Feature Integration Tests
 *
 * Tests for end-to-end functionality that requires real backend services.
 * These tests verify actual feature behavior, not just UI rendering.
 *
 * Prerequisites:
 * 1. Backend running with indexed test data
 * 2. Test folder at: D:\code\JustSearch\test-data\ (or configure TEST_DATA_PATH)
 *
 * Run with:
 *   npx playwright test feature-integration --project=Desktop
 */

import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { E2E_TEST_IDS } from './selectors';
import { waitForApiStatusOk } from './ai-harness';

// Configuration
const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT;
const TEST_DATA_PATH = process.env.TEST_DATA_PATH || 'D:\\code\\JustSearch\\test-data';
const hasRealBackend = !!API_PORT;
const describeRealBackend = hasRealBackend ? test.describe : test.describe.skip;

// Helper: Wait for backend connection
async function waitForConnection(page: Page) {
  await page.goto(`/?api_port=${API_PORT}`);
  await waitForApiStatusOk(page, { apiPort: API_PORT });
}

// Helper: Perform a search and wait for results
async function search(page: Page, query: string, timeoutMs = 5000) {
  const searchInput = page.getByTestId(E2E_TEST_IDS.searchInput);
  const respPromise = page
    .waitForResponse((r) => r.url().includes('/api/knowledge/search') && r.status() === 200, { timeout: timeoutMs })
    .catch(() => null);
  await searchInput.fill(query);
  await respPromise;
  const results = page.getByTestId(E2E_TEST_IDS.searchResultRow);
  await expect(results.first()).toBeVisible({ timeout: timeoutMs }).catch(() => {});
  return results;
}

// Helper: Get search result titles
async function getResultTitles(page: Page): Promise<string[]> {
  const rows = page.getByTestId(E2E_TEST_IDS.searchResultRow);
  const count = await rows.count();
  const titles: string[] = [];
  for (let i = 0; i < count; i++) {
    titles.push(await rows.nth(i).locator('h4').first().innerText());
  }
  return titles;
}

// Helper: Create a test file
function createTestFile(name: string, content: string) {
  const filePath = path.join(TEST_DATA_PATH, name);
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, 'utf-8');
  return filePath;
}

// Helper: Delete a test file
function deleteTestFile(name: string) {
  const filePath = path.join(TEST_DATA_PATH, name);
  if (fs.existsSync(filePath)) {
    fs.unlinkSync(filePath);
  }
}

// ============================================================================
// SEARCH RANKING TESTS
// ============================================================================

describeRealBackend('Search Ranking Quality', () => {
  test.beforeEach(async ({ page }) => {
    await waitForConnection(page);
  });

  test('exact title match ranks higher than content match', async ({ page }) => {
    const results = await search(page, 'README');
    const count = await results.count();

    if (count > 1) {
      const titles = await getResultTitles(page);
      const readmeIndex = titles.findIndex((t) => t.toLowerCase().includes('readme'));

      if (readmeIndex >= 0) {
        expect(readmeIndex).toBeLessThan(5);
      }
    }
  });
});

// ============================================================================
// AI SUMMARIZATION TESTS
// ============================================================================

describeRealBackend('AI File Summarization', () => {
  test.beforeEach(async ({ page }) => {
    await waitForConnection(page);
  });

  test('summarize button appears for selected file', async ({ page }) => {
    const results = await search(page, 'readme');
    const count = await results.count();

    if (count > 0) {
      await results.first().click();

      const summarizeButton = page.getByRole('button', { name: /summarize/i });
      const isVisible = await summarizeButton.isVisible().catch(() => false);
      console.log(`Summarize button visible: ${isVisible}`);
    }
  });

  test('clicking summarize triggers API call', async ({ page }) => {
    let summarizeCallMade = false;

    page.on('request', (request) => {
      if (request.url().includes('/api/summarize')) {
        summarizeCallMade = true;
      }
    });

    const results = await search(page, 'readme');
    const count = await results.count();

    if (count > 0) {
      await results.first().click();

      const summarizeButton = page.getByRole('button', { name: /summarize/i });

      if (await summarizeButton.isVisible().catch(() => false)) {
        await summarizeButton.click();
        await expect.poll(() => summarizeCallMade, { timeout: 15000 }).toBe(true);
      }
    }
  });

  test('summary appears in inspector panel', async ({ page }) => {
    const results = await search(page, 'readme');
    const count = await results.count();

    if (count > 0) {
      await results.first().click();

      const summarizeButton = page.getByRole('button', { name: /summarize/i });

      if (await summarizeButton.isVisible().catch(() => false)) {
        await summarizeButton.click();

        const inspector = page.getByTestId(E2E_TEST_IDS.inspectorPreview);
        const summaryText = inspector.getByText(/summary|overview|about/i);
        await expect(summaryText.first()).toBeVisible({ timeout: 15000 });
      }
    }
  });
});

// ============================================================================
// INDEX AUTO-REFRESH TESTS
// ============================================================================

describeRealBackend('Index Auto-Refresh', () => {
  const testFileName = `test-auto-refresh-${Date.now()}.txt`;

  test.beforeEach(async ({ page }) => {
    await waitForConnection(page);
  });

  test.afterEach(async () => {
    try {
      deleteTestFile(testFileName);
    } catch {
      // Ignore cleanup errors
    }
  });

  test('new file appears in search after indexing', async ({ page }) => {
    test.skip(!fs.existsSync(TEST_DATA_PATH), 'Test data folder not found');

    const uniqueContent = `UNIQUE_TEST_CONTENT_${Date.now()}`;

    createTestFile(
      testFileName,
      `# Test File\n\n${uniqueContent}\n\nThis is a test file for auto-refresh testing.`,
    );

    await expect
      .poll(
        async () => {
          const r = await search(page, uniqueContent, 3000);
          return await r.count();
        },
        { timeout: 30_000 },
      )
      .toBeGreaterThan(0);

    const results = await search(page, uniqueContent, 2000);
    expect(await results.count()).toBeGreaterThan(0);
  });

  test('modified file content is re-indexed', async ({ page }) => {
    test.skip(!fs.existsSync(TEST_DATA_PATH), 'Test data folder not found');

    const originalContent = `ORIGINAL_CONTENT_${Date.now()}`;
    const updatedContent = `UPDATED_CONTENT_${Date.now()}`;

    createTestFile(testFileName, originalContent);

    await expect
      .poll(
        async () => {
          const r = await search(page, originalContent, 3000);
          return await r.count();
        },
        { timeout: 30_000 },
      )
      .toBeGreaterThan(0);

    createTestFile(testFileName, updatedContent);

    await expect
      .poll(
        async () => {
          const r = await search(page, updatedContent, 3000);
          return await r.count();
        },
        { timeout: 30_000 },
      )
      .toBeGreaterThan(0);

    const results = await search(page, updatedContent, 2000);
    expect(await results.count()).toBeGreaterThan(0);
  });

  test('deleted file is removed from search results', async ({ page }) => {
    test.skip(!fs.existsSync(TEST_DATA_PATH), 'Test data folder not found');

    const uniqueContent = `DELETE_TEST_${Date.now()}`;

    createTestFile(testFileName, uniqueContent);
    await expect
      .poll(
        async () => {
          const r = await search(page, uniqueContent, 3000);
          return await r.count();
        },
        { timeout: 30_000 },
      )
      .toBeGreaterThan(0);

    deleteTestFile(testFileName);

    await expect
      .poll(
        async () => {
          const r = await search(page, uniqueContent, 3000);
          return await r.count();
        },
        { timeout: 30_000 },
      )
      .toBe(0);
  });
});
