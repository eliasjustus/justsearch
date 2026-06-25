import { test, expect } from '@playwright/test';

import { navigateToDemoMode, waitForApiStatusOk, waitForDemoData } from './ai-harness';
import { assertPrimaryControlVisible, assertZoneGridNoOverlap } from './layout-heuristics';
import { E2E_ACTIVITY_TEST_IDS, E2E_TEST_IDS } from './selectors';

test.describe('Gate smoke (fast, local-first)', () => {
  test('demo mode loads and renders demo results after typing', async ({ page }) => {
    const diag: string[] = [];
    page.on('console', (msg) => {
      const t = msg.type();
      if (t === 'error' || t === 'warning') {
        diag.push(`[console.${t}] ${msg.text()}`);
      }
    });
    page.on('pageerror', (err) => {
      diag.push(`[pageerror] ${String((err as any)?.stack || err)}`);
    });

    await navigateToDemoMode(page);
    try {
      await waitForDemoData(page);
    } catch (err) {
      // Surface browser-side errors in CI logs (helps diagnose ErrorBoundary screens).
      if (diag.length) {
        console.log('--- demo-mode diagnostics ---');
        for (const line of diag.slice(-50)) console.log(line);
      }
      throw err;
    }

    // Sanity: results should be present.
    await expect(page.locator('[data-selected]').first()).toBeVisible();

    // Heuristic layout check (fast, no baselines): ensure zones don't overlap across main views.
    const views = ['search', 'library', 'browse', 'brain', 'health', 'settings'] as const;
    for (const v of views) {
      await page.getByTestId(E2E_ACTIVITY_TEST_IDS[v]).click();
      await assertPrimaryControlVisible(page, v);
      await assertZoneGridNoOverlap(page);
    }
  });

  test('real backend mode connects (observes /api/status 200) and renders search input', async ({ page }) => {
    const apiPort = process.env.VITE_JUSTSEARCH_API_PORT;
    test.skip(!apiPort, 'Requires VITE_JUSTSEARCH_API_PORT for real-backend smoke verification.');
    const url = apiPort ? `/?api_port=${apiPort}` : '/';

    await page.goto(url);

    // Deterministic readiness oracle: poll status endpoint until it responds 200.
    await waitForApiStatusOk(page, { apiPort, timeoutMs: 60_000 });

    await expect(page.getByTestId(E2E_TEST_IDS.searchInput)).toBeVisible({ timeout: 30_000 });
  });
});


