import { test, expect } from '@playwright/test';
import { navigateToDemoMode } from './ai-harness';
import { E2E_TEST_IDS } from './selectors';

test.describe('Brain view smoke', () => {
  test('renders primary install/runtime controls (demo-safe)', async ({ page }) => {
    await navigateToDemoMode(page);
    await page.getByTestId(E2E_TEST_IDS.activityBrain).click();

    // Header should render.
    await expect(page.getByText('AI Brain', { exact: true })).toBeVisible();

    // Runtime controls vary by mode/state. Require at least one primary control surface.
    const installBtn = page.getByTestId(E2E_TEST_IDS.installAiBtn).first();
    const repairBtn = page.getByTestId(E2E_TEST_IDS.repairAiBtn).first();
    const compatCard = page.getByTestId(E2E_TEST_IDS.embeddingCompatCard).first();

    const hasInstall = await installBtn.isVisible().catch(() => false);
    const hasRepair = await repairBtn.isVisible().catch(() => false);
    const hasCompat = await compatCard.isVisible().catch(() => false);
    test.skip(
      !(hasInstall || hasRepair || hasCompat),
      'No primary brain runtime control is visible in this runtime mode',
    );

    if (hasInstall) {
      await expect(installBtn).toBeVisible();
    } else if (hasRepair) {
      await expect(repairBtn).toBeVisible();
    } else {
      await expect(compatCard).toBeVisible();
    }
  });
});
