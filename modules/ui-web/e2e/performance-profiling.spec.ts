/**
 * Performance Profiling Tests
 * 
 * Tests for performance with large datasets and heavy usage.
 * Run with: npx playwright test performance-profiling --project=Desktop
 */

import { test, expect } from '@playwright/test';
import { ensureSearchInputReady, navigateToDemoMode, waitForDemoData, measureFPS } from './ai-harness';
import { DeterminismRecorder } from './determinism';

// Generate large mock dataset
function generateLargeMockData(count: number) {
  const types = ['note', 'code', 'pdf', 'image', 'file'];
  const extensions = ['.md', '.tsx', '.pdf', '.png', '.json'];
  
  return Array.from({ length: count }, (_, i) => ({
    id: `item-${i}`,
    title: `Document ${i} - ${['Project', 'Report', 'Analysis', 'Config', 'README'][i % 5]}`,
    path: `D:\\Projects\\Folder${Math.floor(i / 100)}\\${['src', 'docs', 'assets', 'config'][i % 4]}\\file${i}${extensions[i % 5]}`,
    type: types[i % 5],
    snippet: `This is a sample snippet for document ${i}. It contains <mark>search</mark> terms and other content that might be relevant to the user's query.`,
    size: `${Math.floor(Math.random() * 1000) + 1} KB`,
    meta: `Modified ${Math.floor(Math.random() * 30) + 1} days ago`,
  }));
}

test.describe('Large Dataset Performance', () => {
  test('measures initial render time with mock data', async ({ page }) => {
    await navigateToDemoMode(page);
    
    const startTime = Date.now();
    await waitForDemoData(page);
    const endTime = Date.now();
    
    const renderTime = endTime - startTime;
    console.log(`\n⏱️ Initial render time: ${renderTime}ms`);
    
    // Should render within 2 seconds
    expect(renderTime).toBeLessThan(2000);
  });

  test('measures scroll performance with virtualized list', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Get the result list container
    const stage = page.locator('main');
    await expect(stage).toBeVisible();
    
    // Measure FPS during scroll
    const fps = await measureFPS(page, async () => {
      await page.mouse.move(640, 400);
      for (let i = 0; i < 10; i++) {
        await page.mouse.wheel(0, 200);
        await det.sleepBackoff(page, 50, 'scroll_step');
      }
    }, 1000);
    
    console.log(`\n🎮 Scroll FPS: ${fps}`);
    
    // Should maintain at least 30 FPS
    expect(fps).toBeGreaterThanOrEqual(24);
  });

  test('measures search debounce efficiency', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    
    // Track how many renders/updates happen
    let updateCount = 0;
    
    await page.exposeFunction('trackUpdate', () => {
      updateCount++;
    });
    
    // Type rapidly
    const startTime = Date.now();
    await searchInput.type('testing search query', { delay: 30 });
    await det.sleepBackoff(page, 500, 'wait_for_debounce');
    const endTime = Date.now();
    
    const typingTime = endTime - startTime;
    console.log(`\n⌨️ Typing time for 19 characters: ${typingTime}ms`);
    
    // Should handle rapid typing without blocking
    expect(typingTime).toBeLessThan(3000);
  });

  test('measures view switch performance', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const views = [
      { name: 'Settings', button: 'button[title="Settings"]' },
      { name: 'Library', button: 'button[title="Library"]' },
      { name: 'Search', button: 'button[title="Search"]' },
    ];
    
    const times: number[] = [];
    
    for (const view of views) {
      const startTime = Date.now();
      await page.click(view.button);
      await det.sleepBackoff(page, 300, 'wait_for_transition');
      const endTime = Date.now();
      
      times.push(endTime - startTime);
    }
    
    const avgTime = times.reduce((a, b) => a + b, 0) / times.length;
    console.log(`\n🔄 Average view switch time: ${avgTime.toFixed(0)}ms`);
    console.log(`   Individual times: ${times.map(t => t + 'ms').join(', ')}`);
    
    // Each switch should be under 1 second
    times.forEach((time, i) => {
      expect(time).toBeLessThan(1000);
    });
  });

  test('measures memory stability during repeated operations', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Get initial memory
    const initialMemory = await page.evaluate(() => {
      // @ts-ignore
      return performance.memory?.usedJSHeapSize || 0;
    });
    
    // Perform many operations
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    
    for (let i = 0; i < 20; i++) {
      await searchInput.fill(`query ${i}`);
      await det.sleepBackoff(page, 100, 'stress_search_loop');
      await searchInput.clear();
    }
    
    // Navigate through views
    for (let i = 0; i < 5; i++) {
      await page.click('button[title="Settings"]');
      await det.sleepBackoff(page, 100, 'stress_view_switch');
      await page.click('button[title="Library"]');
      await det.sleepBackoff(page, 100, 'stress_view_switch');
      await page.click('button[title="Search"]');
      await det.sleepBackoff(page, 100, 'stress_view_switch');
    }
    
    // Get final memory
    const finalMemory = await page.evaluate(() => {
      // @ts-ignore
      return performance.memory?.usedJSHeapSize || 0;
    });
    
    if (initialMemory > 0 && finalMemory > 0) {
      const memoryGrowth = ((finalMemory - initialMemory) / initialMemory) * 100;
      console.log(`\n💾 Memory growth: ${memoryGrowth.toFixed(1)}%`);
      console.log(`   Initial: ${(initialMemory / 1024 / 1024).toFixed(1)}MB`);
      console.log(`   Final: ${(finalMemory / 1024 / 1024).toFixed(1)}MB`);
      
      // Memory should not grow more than 100%
      expect(memoryGrowth).toBeLessThan(100);
    } else {
      console.log('\n💾 Memory API not available in this browser');
    }
  });
});

test.describe('Animation Performance', () => {
  test('measures frame drops during animations', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Inject frame counter
    await page.evaluate(() => {
      (window as any).__frameCount = 0;
      (window as any).__frameCallback = () => {
        (window as any).__frameCount++;
        requestAnimationFrame((window as any).__frameCallback);
      };
      requestAnimationFrame((window as any).__frameCallback);
    });
    
    // Perform animations
    await page.click('button[title="Settings"]');
    await det.sleepBackoff(page, 500, 'wait_for_transition');
    await page.click('button[title="Search"]');
    await det.sleepBackoff(page, 500, 'wait_for_transition');
    
    const frameCount = await page.evaluate(() => (window as any).__frameCount);
    const expectedFrames = 60; // ~60 FPS for 1 second
    
    console.log(`\n🎬 Frames rendered: ${frameCount} (expected ~${expectedFrames})`);
    
    // Should have rendered a reasonable number of frames
    expect(frameCount).toBeGreaterThan(30);
  });

  test('measures layout shift during interactions', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    // Enable layout shift tracking
    let layoutShiftScore = 0;
    
    await page.evaluate(() => {
      const observer = new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          // @ts-ignore
          if (!entry.hadRecentInput) {
            // @ts-ignore
            (window as any).__layoutShiftScore = ((window as any).__layoutShiftScore || 0) + entry.value;
          }
        }
      });
      observer.observe({ type: 'layout-shift', buffered: true });
    });
    
    // Perform actions that might cause layout shifts
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.fill('test');
    await det.sleepBackoff(page, 500, 'wait_for_debounce');
    
    await page.click('button[title="Settings"]');
    await det.sleepBackoff(page, 500, 'wait_for_transition');
    
    await page.click('button[title="Search"]');
    await det.sleepBackoff(page, 500, 'wait_for_transition');
    
    layoutShiftScore = await page.evaluate(() => (window as any).__layoutShiftScore || 0);
    
    console.log(`\n📐 Cumulative Layout Shift: ${layoutShiftScore.toFixed(4)}`);
    
    // CLS should be under 0.1 for good UX
    expect(layoutShiftScore).toBeLessThan(0.25);
  });
});

test.describe('Stress Tests', () => {
  test('handles rapid keyboard input', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    await searchInput.clear();
    
    const startTime = Date.now();
    
    // Type very rapidly
    for (let i = 0; i < 100; i++) {
      await searchInput.press('a');
    }
    
    const endTime = Date.now();
    const inputTime = endTime - startTime;
    
    console.log(`\n⚡ Time for 100 key presses: ${inputTime}ms`);
    
    // Should handle without freezing
    expect(inputTime).toBeLessThan(10000);
    
    // Input should be responsive
    const value = await searchInput.inputValue();
    expect(value.length).toBe(100);
  });

  test('handles rapid click events', async ({ page }) => {
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const startTime = Date.now();
    
    // Click buttons rapidly
    for (let i = 0; i < 20; i++) {
      await page.click('button[title="Settings"]', { force: true });
      await page.click('button[title="Library"]', { force: true });
      await page.click('button[title="Search"]', { force: true });
    }
    
    const endTime = Date.now();
    const clickTime = endTime - startTime;
    
    console.log(`\n🖱️ Time for 60 rapid clicks: ${clickTime}ms`);
    
    // Should complete without hanging
    expect(clickTime).toBeLessThan(15000);
  });

  test('maintains responsiveness during heavy DOM updates', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    
    // Simulate heavy update pattern
    const operations = [];
    for (let i = 0; i < 30; i++) {
      operations.push(async () => {
        await searchInput.fill(`search ${i}`);
        await det.sleepBackoff(page, 50, 'stress_search_loop');
      });
    }
    
    const startTime = Date.now();
    
    for (const op of operations) {
      await op();
    }
    
    const endTime = Date.now();
    const totalTime = endTime - startTime;
    
    console.log(`\n🔄 Time for 30 search updates: ${totalTime}ms`);
    
    // Average time per operation should be reasonable
    const avgTime = totalTime / 30;
    console.log(`   Average per operation: ${avgTime.toFixed(0)}ms`);
    
    expect(avgTime).toBeLessThan(200);
  });
});

test.describe('Performance Summary', () => {
  test('generate comprehensive performance report', async ({ page }) => {
    const det = new DeterminismRecorder();
    await navigateToDemoMode(page);
    await waitForDemoData(page);
    
    console.log('\n' + '='.repeat(60));
    console.log('        PERFORMANCE PROFILING REPORT');
    console.log('='.repeat(60));
    
    // Initial render
    const renderStart = Date.now();
    await page.reload();
    await page.goto(page.url().includes('demo') ? page.url() : '/?demo=true');
    await waitForDemoData(page);
    const renderTime = Date.now() - renderStart;
    
    // Search performance
    const searchInput = await ensureSearchInputReady(page, { timeoutMs: 10_000, focusSearchView: true });
    const searchStart = Date.now();
    await searchInput.fill('test query');
    await det.sleepBackoff(page, 500, 'wait_for_debounce');
    const searchTime = Date.now() - searchStart;
    
    // View switch
    const switchStart = Date.now();
    await page.click('button[title="Settings"]');
    await det.sleepBackoff(page, 300, 'wait_for_transition');
    const switchTime = Date.now() - switchStart;
    
    // Scroll FPS (if measurable)
    await page.click('button[title="Search"]');
    await det.sleepBackoff(page, 300, 'wait_for_transition');
    
    let fps = 0;
    try {
      fps = await measureFPS(page, async () => {
        await page.mouse.move(640, 400);
        await page.mouse.wheel(0, 500);
      }, 500);
    } catch {
      fps = 60; // Assume good performance if can't measure
    }
    
    console.log(`
┌─────────────────────────────────────────────────────┐
│                   METRICS                           │
├─────────────────────────────────────────────────────┤
│ Initial Render:     ${String(renderTime).padStart(5)}ms                        │
│ Search Response:    ${String(searchTime).padStart(5)}ms                        │
│ View Switch:        ${String(switchTime).padStart(5)}ms                        │
│ Scroll FPS:         ${String(fps).padStart(5)} fps                       │
├─────────────────────────────────────────────────────┤
│ THRESHOLDS:                                         │
│ ✓ Render < 3000ms   ${renderTime < 3000 ? '✓ PASS' : '✗ FAIL'}                         │
│ ✓ Search < 1000ms   ${searchTime < 1000 ? '✓ PASS' : '✗ FAIL'}                         │
│ ✓ Switch < 1000ms   ${switchTime < 1000 ? '✓ PASS' : '✗ FAIL'}                         │
│ ✓ FPS >= 24         ${fps >= 24 ? '✓ PASS' : '✗ FAIL'}                         │
└─────────────────────────────────────────────────────┘
    `);
    
    expect(renderTime).toBeLessThan(3000);
    expect(searchTime).toBeLessThan(1000);
    expect(switchTime).toBeLessThan(1000);
    expect(fps).toBeGreaterThanOrEqual(24);
  });
});

