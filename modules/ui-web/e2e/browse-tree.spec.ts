import { test, expect, type Page } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

// --- Mock data ---

const MOCK_ROOT = { path: 'C:\\docs', collection: 'default', fileCount: 5, status: 'ready' };
const MOCK_SUBFOLDERS = {
  folders: [
    { path: 'C:\\docs\\reports', name: 'reports', fileCount: 3, totalSizeBytes: 8192, lastIndexedAt: 1707600100000 },
    { path: 'C:\\docs\\notes', name: 'notes', fileCount: 2, totalSizeBytes: 4096, lastIndexedAt: 1707600100000 },
  ],
  tookMs: 5,
  truncated: false,
};
const MOCK_FILES = {
  files: [
    {
      docId: 'c:\\docs\\reports\\q1.md',
      fields: {
        path: 'c:\\docs\\reports\\q1.md', filename: 'q1.md', file_kind: 'note',
        size_bytes: '2048', modified_at: '1707600000000', indexed_at: '1707600100000',
      },
    },
    {
      docId: 'c:\\docs\\reports\\q2.md',
      fields: {
        path: 'c:\\docs\\reports\\q2.md', filename: 'q2.md', file_kind: 'note',
        size_bytes: '1536', modified_at: '1707600000000', indexed_at: '1707600100000',
      },
    },
  ],
  totalCount: 2,
  tookMs: 3,
};

// --- Helpers ---

const API_PORT = 33221;
const API_BASE = `http://127.0.0.1:${API_PORT}`;

async function setupBrowseMocks(page: Page) {
  await page.route('**/api/ui/ready', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({ status: 200, body: '{}' });
    } else {
      await route.continue();
    }
  });

  await page.route(`${API_BASE}/api/status`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'ready', isReady: true, pendingJobs: 0, indexedDocCount: 5, embeddingCompatState: 'COMPATIBLE' }),
    });
  });

  await page.route(`${API_BASE}/api/inference/status`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ mode: 'offline', available: false, starting: false, embeddingQueueSize: 0, vduQueueSize: 0, tier: 'cpu_only' }),
    });
  });

  await page.route(`${API_BASE}/api/settings/v2`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ui: { theme: 'dark', density: 'comfort', mode: 'advanced', hasSeenTrustLoopNudge: true, excludePatterns: [] },
        llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
        indexPaths: [],
      }),
    });
  });

  await page.route(`${API_BASE}/api/indexing/roots**`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ roots: [MOCK_ROOT] }),
    });
  });

  await page.route(`${API_BASE}/api/knowledge/folders`, async (route) => {
    const body = route.request().postDataJSON();
    if (body?.parentPath === MOCK_ROOT.path) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_SUBFOLDERS) });
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ folders: [], tookMs: 1, truncated: false }) });
    }
  });

  await page.route(`${API_BASE}/api/knowledge/folder-files`, async (route) => {
    const body = route.request().postDataJSON();
    if (body?.folderPath === 'C:\\docs\\reports') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_FILES) });
    } else {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ files: [], totalCount: 0, tookMs: 1 }) });
    }
  });
}

async function navigateToBrowse(page: Page) {
  await page.goto(`/?api_port=${API_PORT}`);
  await page.waitForLoadState('networkidle');
  await page.getByTestId(E2E_TEST_IDS.activityBrowse).click();
  await expect(page.getByText('Browse', { exact: true })).toBeVisible();
}

// --- Tests ---

test.describe('Browse tree', () => {
  test('root folders visible after navigation', async ({ page }) => {
    await setupBrowseMocks(page);
    await navigateToBrowse(page);

    const folderRows = page.getByTestId(E2E_TEST_IDS.browseFolderRow);
    await expect(folderRows.first()).toBeVisible({ timeout: 5_000 });
    // Root folder shows name derived from path
    await expect(page.getByText('docs')).toBeVisible();
  });

  test('folder expand shows subfolders and files', async ({ page }) => {
    await setupBrowseMocks(page);
    await navigateToBrowse(page);

    // Wait for root folder to appear and click to expand
    const rootFolder = page.getByTestId(E2E_TEST_IDS.browseFolderRow).first();
    await expect(rootFolder).toBeVisible({ timeout: 5_000 });
    await rootFolder.click();

    // Subfolders should appear (reports, notes)
    await expect(page.getByText('reports')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('notes')).toBeVisible();

    // Click into "reports" subfolder to load files
    await page.getByText('reports').click();

    // File rows should appear
    const fileRows = page.getByTestId(E2E_TEST_IDS.browseFileRow);
    await expect(fileRows.first()).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('q1.md')).toBeVisible();
    await expect(page.getByText('q2.md')).toBeVisible();
  });

  test('file click opens Inspector', async ({ page, viewport }) => {
    test.skip((viewport?.width ?? 1280) < 800, 'Inspector not visible on narrow viewports');
    await setupBrowseMocks(page);
    await navigateToBrowse(page);

    // Expand root → reports → files
    const rootFolder = page.getByTestId(E2E_TEST_IDS.browseFolderRow).first();
    await expect(rootFolder).toBeVisible({ timeout: 5_000 });
    await rootFolder.click();
    await expect(page.getByText('reports')).toBeVisible({ timeout: 5_000 });
    await page.getByText('reports').click();

    // Wait for file rows and click one
    const fileRow = page.getByTestId(E2E_TEST_IDS.browseFileRow).first();
    await expect(fileRow).toBeVisible({ timeout: 5_000 });
    await fileRow.click();

    // Inspector pane should open
    const inspector = page.getByTestId(E2E_TEST_IDS.inspectorPreview);
    await expect(inspector).toBeVisible({ timeout: 5_000 });
  });
});
