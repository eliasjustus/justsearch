/**
 * Playwright spec for Action Panel functionality.
 *
 * Tests:
 * - Ctrl/Cmd+Shift+P opens the panel
 * - Escape closes the panel
 * - Actions reflect selection state
 * - Keyboard navigation within the panel
 */
import { test, expect } from '@playwright/test';
import { navigateToDemoMode, waitForDemoData } from './ai-harness';
import { E2E_SELECTORS, E2E_TEST_IDS } from './selectors';

const isMac = process.platform === 'darwin';

test.describe('Action Panel', () => {
  test.beforeEach(async ({ page }) => {
    // Load app in demo mode for deterministic state (and ensure results exist).
    await navigateToDemoMode(page);
    await waitForDemoData(page, { query: 'justsearch' });
  });

  test('opens with Ctrl/Cmd+Shift+P', async ({ page }) => {
    // Panel should not be visible initially
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).not.toBeVisible();

    // Press the hotkey
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');

    // Panel should now be visible
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanelInput)).toBeFocused();
  });

  test('closes with Escape', async ({ page }) => {
    // Open the panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Press Escape
    await page.keyboard.press('Escape');

    // Panel should be closed
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).not.toBeVisible();
  });

  test('closes when clicking backdrop', async ({ page }) => {
    // Open the panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Click backdrop
    await page.getByTestId(E2E_TEST_IDS.actionPanelBackdrop).click({ position: { x: 10, y: 10 } });

    // Panel should be closed
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).not.toBeVisible();
  });

  test('toggles with repeated hotkey', async ({ page }) => {
    // Open
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Close
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).not.toBeVisible();
  });

  test('shows disabled actions when no selection', async ({ page }) => {
    // Open panel without selecting any files
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // File actions should be disabled
    const openAction = page.getByTestId(E2E_TEST_IDS.actionOpen);
    await expect(openAction).toBeVisible();
    await expect(openAction).toBeDisabled();
  });

  test('enables file actions when file is selected', async ({ page }) => {
    // Select a file via checkbox (drives selectedIds + enables file actions).
    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Open panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Open action should be enabled
    const openAction = page.getByTestId(E2E_TEST_IDS.actionOpen);
    await expect(openAction).toBeVisible();
    await expect(openAction).toBeEnabled();
  });

  test('groups unavailable actions behind a toggle when some actions are available', async ({ page }) => {
    // Select a file so at least some actions are enabled.
    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Open panel.
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Open should be available.
    await expect(page.getByTestId(E2E_TEST_IDS.actionOpen)).toBeVisible();
    await expect(page.getByTestId(E2E_TEST_IDS.actionOpen)).toBeEnabled();

    const panel = page.getByTestId(E2E_TEST_IDS.actionPanel);
    const toggleUnavailable = page.getByTestId(E2E_TEST_IDS.actionPanelToggleUnavailable);
    await expect(toggleUnavailable).toBeVisible();
    const disabledOptions = panel.locator('[role="option"][disabled]');

    const toggleTextBefore = (await toggleUnavailable.innerText()).toLowerCase();
    const startsWithUnavailableVisible = toggleTextBefore.includes('hide unavailable');
    const disabledBefore = await disabledOptions.count();

    await toggleUnavailable.click();
    const disabledAfter = await disabledOptions.count();

    if (startsWithUnavailableVisible) {
      expect(disabledBefore).toBeGreaterThan(0);
      expect(disabledAfter).toBe(0);
    } else {
      expect(disabledAfter).toBeGreaterThan(0);
    }

    await toggleUnavailable.click();
    const disabledRestored = await disabledOptions.count();
    if (startsWithUnavailableVisible) {
      expect(disabledRestored).toBeGreaterThan(0);
    } else {
      expect(disabledRestored).toBe(0);
    }
  });

  test('opens from Search actions button when selection exists', async ({ page }) => {
    // Select a file via checkbox (drives selectedIds).
    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Search header should expose an Actions entrypoint.
    const actionsBtn = page.getByTestId(E2E_TEST_IDS.searchActionsButton);
    await expect(actionsBtn).toBeVisible();
    await actionsBtn.click();

    // Action Panel should open.
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanelInput)).toBeFocused();
  });

  test('filters actions by search input', async ({ page }) => {
    // Open panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Type to filter
    await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('sum');

    // Only summarize action should be visible
    await expect(page.getByTestId(E2E_TEST_IDS.actionSummarize)).toBeVisible();
    await expect(page.getByTestId(E2E_TEST_IDS.actionOpen)).not.toBeVisible();
  });

  test('navigates with arrow keys', async ({ page }) => {
    // Select a file first
    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Open panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanelInput)).toBeFocused();

    // First item should be selected by default
    const firstAction = page.getByTestId(E2E_TEST_IDS.actionPanel).locator('[data-selected="true"]').first();
    await expect(firstAction).toBeVisible();
    const beforeId = await firstAction.getAttribute('data-testid');

    // Press down to select next item
    await page.keyboard.press('ArrowDown');

    // Selection should have moved
    const newSelection = page.getByTestId(E2E_TEST_IDS.actionPanel).locator('[data-selected="true"]').first();
    await expect(newSelection).toBeVisible();
    const afterId = await newSelection.getAttribute('data-testid');
    expect(afterId).toBeTruthy();
    expect(afterId).not.toEqual(beforeId);
  });

  test('executes action with Enter', async ({ page }) => {
    // Select a file first
    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Open panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).toBeVisible();

    // Navigate to copy-path action
    await page.getByTestId(E2E_TEST_IDS.actionPanelInput).fill('copy');

    // Press Enter
    await page.keyboard.press('Enter');

    // Panel should close after action
    await expect(page.getByTestId(E2E_TEST_IDS.actionPanel)).not.toBeVisible();
  });

  test('shows selection count', async ({ page }) => {
    // Open panel without selection
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');

    // Should show no selection
    await expect(page.getByText('No selection')).toBeVisible();

    // Close and select a file
    await page.keyboard.press('Escape');
    const firstRow = page.locator(E2E_SELECTORS.searchResultRow).first();
    await firstRow.locator('button[aria-label="Select file"]').click();

    // Reopen panel
    await page.keyboard.press(isMac ? 'Meta+Shift+P' : 'Control+Shift+P');

    // Should show 1 file selected
    await expect(page.getByText('1 file selected')).toBeVisible();
  });
});

