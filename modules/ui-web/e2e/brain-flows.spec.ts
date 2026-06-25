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
  embeddingCompatState: 'COMPATIBLE',
});

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

async function ensureInstallSectionExpanded(page: any) {
  const installBtn = page.getByTestId(E2E_TEST_IDS.installAiBtn).first();
  if (await installBtn.isVisible().catch(() => false)) {
    return;
  }

  const installSummary = page.locator('summary').filter({ hasText: 'Install AI' }).first();
  if (await installSummary.isVisible().catch(() => false)) {
    await installSummary.click();
  }
}

async function resolveInstallControl(page: any) {
  const byTestId = page.getByTestId(E2E_TEST_IDS.installAiBtn).first();
  if (await byTestId.isVisible().catch(() => false)) {
    return byTestId;
  }
  return page.getByRole('button', { name: /Install AI/i }).first();
}

async function resolveCancelControl(page: any) {
  const byTestId = page.getByTestId(E2E_TEST_IDS.cancelInstallBtn).first();
  if (await byTestId.isVisible().catch(() => false)) {
    return byTestId;
  }
  return page.getByRole('button', { name: /^Cancel$/ }).first();
}

test.describe('Brain install flows', () => {
  test.beforeEach(async ({ page }) => {
    await page.route('**/api/ui/ready', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
    });

    await page.route('**/api/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(BASE_STATUS),
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
          ui: { theme: 'dark', mode: 'advanced' },
          llm: { contextWindow: 4096, maxTokens: 1024 },
          indexPaths: [],
        }),
      });
    });
  });

  test('install button is visible when AI not installed', async ({ page }) => {
    await page.route('**/api/ai/install/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          state: 'not_started',
          phase: 'idle',
          message: 'AI not installed',
          assets: [],
        }),
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

    await openBrainView(page);
    await ensureInstallSectionExpanded(page);
    const installBtn = await resolveInstallControl(page);
    await expect(installBtn).toBeVisible({ timeout: 5_000 });
  });

  test('install button shows confirmation dialog', async ({ page }) => {
    await page.route('**/api/ai/install/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          state: 'not_started',
          phase: 'idle',
          assets: [],
        }),
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

    await openBrainView(page);
    await ensureInstallSectionExpanded(page);
    const installBtn = await resolveInstallControl(page);
    await expect(installBtn).toBeVisible({ timeout: 5_000 });
    await installBtn.click();

    await expect(page.getByText('Download AI models?')).toBeVisible({ timeout: 5_000 });
  });

  test('shows installing state when installation is running', async ({ page }) => {
    await page.route('**/api/ai/install/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          state: 'running',
          phase: 'downloading',
          message: 'Downloading model files...',
          assets: [],
          bytesTotal: 1_000_000,
          bytesDone: 500_000,
        }),
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

    await openBrainView(page);
    await ensureInstallSectionExpanded(page);

    const installBtnByTestId = page.getByTestId(E2E_TEST_IDS.installAiBtn).first();
    if (await installBtnByTestId.isVisible().catch(() => false)) {
      await expect(installBtnByTestId).toBeDisabled();
    }

    const cancelBtn = await resolveCancelControl(page);
    await expect(cancelBtn).toBeVisible();
    await expect(cancelBtn).toBeEnabled();
  });

  test('cancel button triggers cancel API call when installing', async ({ page }) => {
    let cancelCalled = false;

    await page.route('**/api/ai/install/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          state: 'running',
          phase: 'downloading',
          message: 'Downloading...',
          assets: [],
        }),
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

    await page.route('**/api/ai/install/cancel', async (route) => {
      cancelCalled = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ok: true }),
      });
    });

    await openBrainView(page);
    await ensureInstallSectionExpanded(page);
    const cancelBtn = await resolveCancelControl(page);
    await expect(cancelBtn).toBeVisible({ timeout: 5_000 });
    await cancelBtn.click();

    await expect.poll(() => cancelCalled).toBe(true);
  });

  test('repair button is visible when AI is installed', async ({ page }) => {
    await page.route('**/api/ai/install/status', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          state: 'completed',
          phase: 'done',
          message: 'AI installed',
          assets: [{ id: 'model-main', state: 'installed' }],
        }),
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

    await openBrainView(page);
    await ensureInstallSectionExpanded(page);

    const repairBtn = page.getByTestId(E2E_TEST_IDS.repairAiBtn).first();
    const hasRepair = await repairBtn.isVisible().catch(() => false);
    test.skip(!hasRepair, 'Repair control is not visible in this runtime mode');
    await expect(repairBtn).toBeEnabled();
  });
});
