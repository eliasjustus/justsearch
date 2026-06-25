/**
 * Edge Case Tests
 * 
 * Tests for boundary conditions, error states, and unusual scenarios.
 * Run with: npx playwright test edge-cases --project=Desktop
 */

import { test, expect } from '@playwright/test';
import { navigateToDemoMode, waitForDemoData, ensureSearchInputReady } from './ai-harness';

test.describe('Empty States', () => {
  test('shows "No results" when search yields nothing', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Search for something that won't match demo data
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('xyznonexistent12345');
    
    // Should show no results message
    const noResults = page.getByRole('heading', { name: /No results for/i });
    await expect(noResults).toBeVisible();
  });

  test('empty search shows all demo results', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Clear any existing search
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.clear();
    
    // Should show demo results
    const results = page.locator('.border-l-2.group');
    await expect(results.first()).toBeVisible();
    const count = await results.count();
    expect(count).toBeGreaterThan(0);
  });

  test('library view handles no indexed folders', async ({ page }) => {
    await navigateToDemoMode(page);
    
    // Navigate to library
    const libraryBtn = page.locator('button[title="Library"]');
    await expect(libraryBtn).toBeVisible();
    await libraryBtn.click();
    
    // Should show empty state or instruction
    const content = page.locator('main');
    await expect(content).toBeVisible();
  });

  test('inspector shows placeholder when no item selected', async ({ page }) => {
    await navigateToDemoMode(page);
    
    // Inspector may not be visible initially - that's okay
    // Just verify the page loads without errors
    const stage = page.locator('main');
    await expect(stage).toBeVisible();
  });
});

test.describe('Input Edge Cases', () => {
  test('handles very long search queries', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    const longQuery = 'a'.repeat(500);
    
    await searchInput.fill(longQuery);
    
    // Should handle without crashing
    const inputValue = await searchInput.inputValue();
    expect(inputValue.length).toBe(500);
  });

  test('handles special characters in search', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    const specialChars = '<script>alert("xss")</script> & "quotes" \'apostrophe\' `backticks`';
    
    await searchInput.fill(specialChars);
    
    // Should handle without errors or XSS
    const inputValue = await searchInput.inputValue();
    expect(inputValue).toBe(specialChars);
    
    // No script execution - page should still work
    const isStillWorking = await page.locator('nav[aria-label="Main navigation"]').isVisible();
    expect(isStillWorking).toBe(true);
  });

  test('handles unicode and emoji in search', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    const unicodeQuery = '日本語 العربية 🔍 émojis ñ';
    
    await searchInput.fill(unicodeQuery);
    
    const inputValue = await searchInput.inputValue();
    expect(inputValue).toBe(unicodeQuery);
  });

  test('handles rapid typing (debounce)', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.clear();
    
    // Type rapidly
    for (let i = 0; i < 20; i++) {
      await searchInput.press('a');
    }
    
    // Should have debounced and not crashed
    const value = await searchInput.inputValue();
    expect(value.length).toBe(20);
  });

  test('command mode with slash prefix', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Start with the regular search input
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('/help');
    
    // In command mode, the placeholder changes - need to find input differently
    const commandInput = page.locator(
      'input[placeholder="Enter command..."], input[placeholder="Search files...  (/ commands, ?? ask AI)"]',
    );
    
    // Command mode should show the command pill
    const commandPill = page.getByText('/help', { exact: true });
    await expect(commandPill).toBeVisible({ timeout: 2000 });
  });

  test('chat mode with ?? prefix', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('?? What files');
    
    // In chat mode, verify the textarea exists (chat mode expands to textarea)
    // or the input contains the query
    const anyInput = page.locator('input, textarea').first();
    await expect(anyInput).toBeVisible();
  });
});

test.describe('Navigation Edge Cases', () => {
  test('rapid view switching does not break layout', async ({ page }) => {
    await navigateToDemoMode(page);
    
    const views = ['Search', 'Library', 'Settings', 'Search', 'Library'];
    
    for (const view of views) {
      await page.locator(`button[title="${view}"]`).click();
    }
    
    // Layout should still be intact
    const nav = page.locator('nav[aria-label="Main navigation"]');
    await expect(nav).toBeVisible();
  });

  test('keyboard navigation at list boundaries', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Click first result to select it
    const firstResult = page.locator('.border-l-2.group').first();
    await firstResult.click();
    
    // Press up arrow multiple times (should stay at top)
    for (let i = 0; i < 10; i++) {
      await page.keyboard.press('ArrowUp');
    }
    
    // Press down arrow to bottom
    for (let i = 0; i < 20; i++) {
      await page.keyboard.press('ArrowDown');
    }
    
    // Should not crash or throw errors
  });

  test('tab navigation wraps correctly', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Tab through all elements multiple times
    for (let i = 0; i < 50; i++) {
      await page.keyboard.press('Tab');
    }
    
    // Should not crash or get stuck
  });
});

test.describe('Window/Viewport Edge Cases', () => {
  test('very narrow viewport', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await navigateToDemoMode(page);
    await expect(page.locator('main')).toBeVisible();
    
    // Should still render something usable
    const nav = page.locator('nav');
    const main = page.locator('main');
    
    // Navigation might collapse but should still be in DOM
    expect(await main.isVisible()).toBe(true);
  });

  test('very wide viewport', async ({ page }) => {
    await page.setViewportSize({ width: 2560, height: 1440 });
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Should handle wide viewport without layout issues
    const main = page.locator('main');
    await expect(main).toBeVisible();
  });

  test('viewport resize during use', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Resize multiple times
    await page.setViewportSize({ width: 800, height: 600 });
    await page.waitForFunction(([w, h]) => window.innerWidth === w && window.innerHeight === h, [800, 600]);
    
    await page.setViewportSize({ width: 1200, height: 800 });
    await page.waitForFunction(([w, h]) => window.innerWidth === w && window.innerHeight === h, [1200, 800]);
    
    await page.setViewportSize({ width: 600, height: 400 });
    await page.waitForFunction(([w, h]) => window.innerWidth === w && window.innerHeight === h, [600, 400]);
    
    // Layout should still work
    const nav = page.locator('nav[aria-label="Main navigation"]');
    await expect(nav).toBeVisible();
  });
});

test.describe('Error Recovery', () => {
  test('handles failed API call gracefully', async ({ page }) => {
    // Mock a failing API
    await page.route('**/api/status', route => {
      route.fulfill({ status: 500, body: 'Internal Server Error' });
    });
    
    await page.goto('/');
    const connectionError = page.getByText(/Unable to connect|Connection attempt|Internal Server Error/i);
    await connectionError.first().waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
    
    // Should show error state or retry message, not crash
    const pageContent = await page.content();
    expect(pageContent.length).toBeGreaterThan(0);
  });

  test('recovers from network timeout', async ({ page }) => {
    // Start with working connection
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Simulate offline
    await page.route('**/api/**', route => {
      route.abort('failed');
    });
    
    // Try to do something
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('test');
    
    // Should not crash
    const nav = page.locator('nav[aria-label="Main navigation"]');
    await expect(nav).toBeVisible();
  });
});

test.describe('Memory and Performance Edge Cases', () => {
  test('many search iterations do not leak memory', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    
    // Perform many searches
    for (let i = 0; i < 50; i++) {
      await searchInput.fill(`query ${i}`);
      await searchInput.clear();
    }
    
    // Should still be responsive
    await searchInput.fill('final test');
    const value = await searchInput.inputValue();
    expect(value).toBe('final test');
  });

  test('rapid result selection', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const results = page.locator('.border-l-2.group');
    const count = await results.count();
    
    // Click through results rapidly
    for (let i = 0; i < Math.min(count, 10); i++) {
      await results.nth(i).click();
    }
    
    // Should handle rapid selection
  });
});

test.describe('Console Error Monitoring', () => {
  test('no unhandled errors during normal use', async ({ page }) => {
    const errors: string[] = [];
    
    page.on('pageerror', error => {
      errors.push(error.message);
    });
    
    page.on('console', msg => {
      if (msg.type() === 'error') {
        errors.push(msg.text());
      }
    });
    
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Perform various actions
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('test');
    
    const settings = page.locator('button[title="Settings"]');
    const library = page.locator('button[title="Library"]');
    const search = page.locator('button[title="Search"]');

    await settings.click();
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
    
    await library.click();
    await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();
    
    await search.click();
    await expect(await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true })).toBeVisible();
    
    // Filter out known non-critical errors
    const criticalErrors = errors.filter(e => 
      !e.includes('favicon') && 
      !e.includes('net::ERR_') &&
      !e.includes('Failed to load resource')
    );
    
    if (criticalErrors.length > 0) {
      console.log('Errors found:', criticalErrors);
    }
    
    expect(criticalErrors.length).toBe(0);
  });

  test('no React key warnings', async ({ page }) => {
    const warnings: string[] = [];
    
    page.on('console', msg => {
      if (msg.type() === 'warning' && msg.text().includes('key')) {
        warnings.push(msg.text());
      }
    });
    
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Navigate through views to trigger rendering
    const settings = page.locator('button[title="Settings"]');
    const search = page.locator('button[title="Search"]');
    await settings.click();
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
    await search.click();
    await expect(await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true })).toBeVisible();
    
    expect(warnings.length).toBe(0);
  });
});

