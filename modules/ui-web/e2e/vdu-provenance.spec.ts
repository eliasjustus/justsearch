import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

/**
 * E2E contract tests for VDU provenance display in InspectionPane.
 *
 * These tests verify that the UI correctly surfaces VDU provenance status
 * from the /api/preview response:
 * - textProvenance values (vdu, vdu_pending, vdu_processing, vdu_failed, tika)
 * - VDU badge visibility and styling
 *
 * Uses API stubbing to simulate different VDU states without needing a full backend.
 */

const API_PORT = process.env.VITE_JUSTSEARCH_API_PORT || '33221';
const UI_URL = 'http://localhost:5173';

async function selectResultAndOpenPreview(page: any) {
  const rows = page.getByTestId(E2E_TEST_IDS.searchResultRow);
  await expect(rows.first()).toBeVisible({ timeout: 5000 });
  const count = await rows.count();
  const target = rows.nth(Math.min(1, Math.max(0, count - 1)));
  if ((await target.getAttribute('data-selected')) !== 'true') {
    const previewResponse = page
      .waitForResponse((r: any) => r.url().includes('/api/preview') && r.request().method() === 'GET' && r.ok(), {
        timeout: 10_000,
      })
      .catch(() => null);
    await target.click();
    await previewResponse;
  }

  const previewTab = page.getByRole('button', { name: /^Preview$/ }).first();
  const hasPreviewTab = await previewTab.isVisible().catch(() => false);
  if (hasPreviewTab) {
    await previewTab.click();
  }

  await expect(page.getByTestId(E2E_TEST_IDS.inspectorPreview)).toBeVisible({ timeout: 5_000 });
}

test.describe('VDU provenance display', () => {
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
        }),
      });
    });

    // Stub search to return a test PDF document
    await page.route('**/api/knowledge/search', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          results: [
            { id: 'D:\\docs\\scanned.pdf', score: 0.95, name: 'scanned.pdf', path: 'D:\\docs\\scanned.pdf', mime: 'application/pdf' },
            { id: 'D:\\docs\\second.pdf', score: 0.90, name: 'second.pdf', path: 'D:\\docs\\second.pdf', mime: 'application/pdf' },
          ],
          totalHits: 2,
        }),
      });
    });
  });

  test('shows VDU badge when textProvenance is "vdu"', async ({ page }) => {
    // Stub preview endpoint with VDU-completed response
    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'D:\\docs\\scanned.pdf',
          content: 'VDU-extracted text from scanned document',
          truncated: false,
          nextOffsetChars: 41,
          mime: 'application/pdf',
          textProvenance: 'vdu',
          vduStatus: 'COMPLETED',
          vduProcessed: true,
          vduPageCount: 3,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    await selectResultAndOpenPreview(page);

    // Verify the VDU badge is visible with correct styling
    const vduBadge = page.getByTestId(E2E_TEST_IDS.vduProvenanceBadge);
    await expect(vduBadge).toBeVisible({ timeout: 5000 });
    await expect(vduBadge).toContainText('VDU');
    // Should have emerald (green) styling for completed VDU
    await expect(vduBadge).toHaveClass(/emerald/);
  });

  test('shows VDU pending badge when textProvenance is "vdu_pending"', async ({ page }) => {
    // Stub preview endpoint with VDU-pending response
    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'D:\\docs\\scanned.pdf',
          content: 'Garbage Tika extraction that needs VDU processing',
          truncated: false,
          nextOffsetChars: 49,
          mime: 'application/pdf',
          textProvenance: 'vdu_pending',
          vduStatus: 'PENDING',
          vduProcessed: false,
          vduPageCount: null,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    await selectResultAndOpenPreview(page);

    // Verify the VDU pending badge is visible with amber styling
    const vduBadge = page.getByTestId(E2E_TEST_IDS.vduProvenanceBadge);
    await expect(vduBadge).toBeVisible({ timeout: 5000 });
    await expect(vduBadge).toContainText('VDU pending');
    // Should have amber styling for pending
    await expect(vduBadge).toHaveClass(/amber/);
  });

  test('shows VDU processing badge when textProvenance is "vdu_processing"', async ({ page }) => {
    // Stub preview endpoint with VDU-processing response
    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'D:\\docs\\scanned.pdf',
          content: 'Temporary content while VDU is running',
          truncated: false,
          nextOffsetChars: 40,
          mime: 'application/pdf',
          textProvenance: 'vdu_processing',
          vduStatus: 'PROCESSING',
          vduProcessed: false,
          vduPageCount: null,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    await selectResultAndOpenPreview(page);

    // Verify the VDU processing badge is visible with sky (blue) styling
    const vduBadge = page.getByTestId(E2E_TEST_IDS.vduProvenanceBadge);
    await expect(vduBadge).toBeVisible({ timeout: 5000 });
    await expect(vduBadge).toContainText('VDU processing');
    // Should have sky styling for processing
    await expect(vduBadge).toHaveClass(/sky/);
  });

  test('shows VDU failed badge when textProvenance is "vdu_failed"', async ({ page }) => {
    // Stub preview endpoint with VDU-failed response
    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'D:\\docs\\corrupted.pdf',
          content: 'Garbage extraction - VDU processing failed',
          truncated: false,
          nextOffsetChars: 43,
          mime: 'application/pdf',
          textProvenance: 'vdu_failed',
          vduStatus: 'FAILED',
          vduProcessed: false,
          vduPageCount: null,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    await selectResultAndOpenPreview(page);

    // Verify the VDU failed badge is visible with red styling
    const vduBadge = page.getByTestId(E2E_TEST_IDS.vduProvenanceBadge);
    await expect(vduBadge).toBeVisible({ timeout: 5000 });
    await expect(vduBadge).toContainText('VDU failed');
    // Should have red styling for failed
    await expect(vduBadge).toHaveClass(/red/);
  });

  test('does not show VDU badge when textProvenance is "tika" (normal extraction)', async ({ page }) => {
    // Stub preview endpoint with normal Tika response (no VDU needed)
    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'D:\\docs\\clean.pdf',
          content: 'Clean Tika extraction - VDU not needed',
          truncated: false,
          nextOffsetChars: 40,
          mime: 'application/pdf',
          textProvenance: 'tika',
          vduStatus: 'NOT_NEEDED',
          vduProcessed: false,
          vduPageCount: null,
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    await selectResultAndOpenPreview(page);

    // Verify the VDU badge is NOT visible for normal Tika extraction
    const vduBadge = page.getByTestId(E2E_TEST_IDS.vduProvenanceBadge);
    await expect(vduBadge).not.toBeVisible({ timeout: 2000 });
  });

  test('does not show VDU badge when textProvenance is null/undefined', async ({ page }) => {
    // Stub preview endpoint without VDU provenance fields (legacy response)
    await page.route('**/api/preview**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          docId: 'D:\\docs\\old.txt',
          content: 'Plain text file content',
          truncated: false,
          nextOffsetChars: 24,
          mime: 'text/plain',
          // No textProvenance, vduStatus, etc.
        }),
      });
    });

    // Navigate to UI
    await page.goto(`${UI_URL}?api_port=${API_PORT}`);
    await page.getByTestId(E2E_TEST_IDS.searchInput).waitFor({ state: 'visible', timeout: 10000 });

    // Perform a search
    await page.getByTestId(E2E_TEST_IDS.searchInput).fill('test');
    await page.keyboard.press('Enter');

    await selectResultAndOpenPreview(page);

    // Verify the VDU badge is NOT visible when provenance is missing
    const vduBadge = page.getByTestId(E2E_TEST_IDS.vduProvenanceBadge);
    await expect(vduBadge).not.toBeVisible({ timeout: 2000 });
  });
});

