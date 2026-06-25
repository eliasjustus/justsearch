import { test, expect } from '@playwright/test';
import { E2E_TEST_IDS } from './selectors';

const BASE_STATUS = Object.freeze({
  status: 'ok',
  service: 'JustSearch Local API',
  indexHealthy: true,
  indexedDocuments: 100,
  indexSizeBytes: 1_024_000,
  uptimeMs: 60_000,
  memoryUsedBytes: 50_000_000,
  memoryMaxBytes: 100_000_000,
  pendingJobs: 0,
});

async function stubBrainCommon(page: any) {
  await page.route('**/api/ui/ready', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });

  await page.route('**/api/settings/v2', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ui: { theme: 'dark', mode: 'advanced' },
        llm: { contextWindow: 4096, maxTokens: 1024 },
        indexPaths: [],
      }),
    });
  });

  await page.route('**/api/inference/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        mode: 'offline',
        available: false,
        starting: false,
        embeddingQueueSize: 0,
        vduQueueSize: 0,
      }),
    });
  });

  await page.route('**/api/ai/install/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ state: 'not_started', phase: 'idle', assets: [] }),
    });
  });

  await page.route('**/api/ai/install/manifest', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ version: '1.0.0', files: [] }),
    });
  });

  await page.route('**/api/policy/effective', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        downloadsEnabled: true,
        onlineAiEnabled: true,
        gpuAccelerationEnabled: true,
        aiDisabledOverride: false,
        machine: { present: false, loaded: false },
        user: { present: false, loaded: false },
        allowlistedModelSha256: [],
        allowlistedPackManifestSha256: [],
      }),
    });
  });
}

async function stubStatus(page: any, overrides: Record<string, unknown>) {
  await page.route('**/api/status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...BASE_STATUS,
        ...overrides,
      }),
    });
  });
}

async function openBrainView(page: any) {
  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const brainButton = page.getByTestId(E2E_TEST_IDS.activityBrain).first();
  await expect(brainButton).toBeVisible({ timeout: 10_000 });
  await brainButton.click();
  await expect(page.getByRole('heading', { name: /AI Brain/i })).toBeVisible({ timeout: 10_000 });

  const switchToAdvanced = page.getByTestId(E2E_TEST_IDS.brainSwitchToAdvanced).first();
  if (await switchToAdvanced.isVisible().catch(() => false)) {
    await switchToAdvanced.click();
  }
}

test.describe('Brain embedding compatibility', () => {
  test('can navigate to AI Brain via ActivityRail', async ({ page }) => {
    await page.goto('/?demo=true');
    await page.waitForLoadState('networkidle');
    await page.getByTestId(E2E_TEST_IDS.activityBrain).click();
    await expect(page.getByRole('heading', { name: /AI Brain/i })).toBeVisible({ timeout: 10_000 });
  });

  test('shows embedding compatibility card when blocked (stubbed)', async ({ page }) => {
    await stubBrainCommon(page);
    await stubStatus(page, {
      embeddingCompatState: 'BLOCKED_LEGACY',
      embeddingCompatReason: 'LEGACY_INDEX_NO_FINGERPRINT',
      embeddingFingerprintCurrent: 'abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890',
      embeddingFingerprintStored: null,
    });

    await openBrainView(page);

    const compatCard = page.getByTestId(E2E_TEST_IDS.embeddingCompatCard);
    await expect(compatCard).toBeVisible({ timeout: 5_000 });
    await expect(compatCard).toContainText('Embedding model fingerprint missing');

    const forceRebuildBtn = page.getByTestId(E2E_TEST_IDS.forceRebuildIndexBtn);
    await expect(forceRebuildBtn).toBeVisible();
    await expect(forceRebuildBtn).toContainText('Force Rebuild Index');
  });

  test('shows embedding compatibility card when mismatch detected (stubbed)', async ({ page }) => {
    await stubBrainCommon(page);
    await stubStatus(page, {
      embeddingCompatState: 'BLOCKED_MISMATCH',
      embeddingCompatReason: 'FINGERPRINT_MISMATCH',
      embeddingFingerprintCurrent: 'newmodel1234567890abcdef1234567890abcdef1234567890abcdef12345678',
      embeddingFingerprintStored: 'oldmodel1234567890abcdef1234567890abcdef1234567890abcdef12345678',
    });

    await openBrainView(page);

    const compatCard = page.getByTestId(E2E_TEST_IDS.embeddingCompatCard);
    await expect(compatCard).toBeVisible({ timeout: 5_000 });
    await expect(compatCard).toContainText('Embedding model mismatch detected');
    await expect(compatCard).toContainText('Stored:');
    await expect(compatCard).toContainText('Current:');
  });

  test('Force Rebuild Index button triggers reindex API call', async ({ page }) => {
    let reindexCalled = false;
    let reindexForceParam = false;

    await stubBrainCommon(page);
    await stubStatus(page, {
      embeddingCompatState: 'BLOCKED_LEGACY',
      embeddingCompatReason: 'LEGACY_INDEX_NO_FINGERPRINT',
    });
    await page.route('**/api/indexing/reindex**', async (route) => {
      reindexCalled = true;
      const url = new URL(route.request().url());
      reindexForceParam = url.searchParams.get('force') === 'true';
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, message: 'Reindex started' }),
      });
    });

    await openBrainView(page);
    const forceRebuildBtn = page.getByTestId(E2E_TEST_IDS.forceRebuildIndexBtn);
    await expect(forceRebuildBtn).toBeVisible({ timeout: 5_000 });
    await forceRebuildBtn.click();

    await expect.poll(() => reindexCalled).toBe(true);
    expect(reindexForceParam).toBe(true);
  });

  test('embedding compat card is NOT visible when compatible', async ({ page }) => {
    await stubBrainCommon(page);
    await stubStatus(page, {
      embeddingCompatState: 'COMPATIBLE',
      embeddingCompatReason: 'FINGERPRINT_MATCH',
      embeddingFingerprintCurrent: 'abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890',
      embeddingFingerprintStored: 'abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890',
    });

    await openBrainView(page);
    await expect(page.getByTestId(E2E_TEST_IDS.embeddingCompatCard)).toHaveCount(0);
  });
});
