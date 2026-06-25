/**
 * AI Test Harness for JustSearch UI
 * 
 * This module provides helper functions designed for AI agent testing workflows.
 * All functions return structured data that can be easily parsed and acted upon.
 */

import { Page, Locator, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { E2E_TEST_IDS } from './selectors';

// Load the captured wire fixtures via fs (not an ESM JSON import — Node's loader
// would require a `with { type: 'json' }` attribute; this matches how the spec loads
// the baseline register). Paths are relative to this file, under src/api/__fixtures__.
const _hereDir = dirname(fileURLToPath(import.meta.url));
const _fixDir = resolve(_hereDir, '../src/api/__fixtures__');
const _readFixture = (name: string): string => readFileSync(resolve(_fixDir, name), 'utf-8');
// The axe-core standalone bundle (same one the Python ui_measure tier injects).
const _axeBundle = resolve(_hereDir, '../node_modules/axe-core/axe.min.js');

/**
 * Run axe by INJECTING the standalone bundle + calling `axe.run` in the page —
 * the same method the Python ui_measure tier uses (tempdoc 615 §6.2). This avoids
 * the `@axe-core/playwright` AxeBuilder's isolated-world injection, which trips
 * "Cannot assign to read only property 'get' of WeakMap" on the Lit shell (it
 * freezes an intrinsic). Aligning both tiers on one axe invocation is also the
 * P3 shape. Returns the violations + pass/incomplete counts.
 */
export async function runAxeViolations(
  page: Page,
): Promise<{ violations: any[]; passes: number; incomplete: number }> {
  await page.addScriptTag({ path: _axeBundle });
  return page.evaluate(async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const res = await (window as any).axe.run(document, {
      runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'],
    });
    return { violations: res.violations, passes: res.passes.length, incomplete: res.incomplete.length };
  });
}

// ============================================================================
// Types for Structured Output
// ============================================================================

export interface AccessibilityResult {
  passed: boolean;
  violationCount: number;
  violations: Array<{
    id: string;
    impact: 'minor' | 'moderate' | 'serious' | 'critical';
    description: string;
    nodes: Array<{
      selector: string;
      failureSummary: string;
    }>;
  }>;
}

export interface PerformanceResult {
  passed: boolean;
  metrics: {
    renderTime: number;
    layoutShift: number;
    fps: number;
  };
  thresholds: {
    maxRenderTime: number;
    maxLayoutShift: number;
    minFps: number;
  };
}

export interface FocusOrderResult {
  passed: boolean;
  expected: string[];
  actual: string[];
  failures: Array<{
    index: number;
    expected: string;
    actual: string;
  }>;
}

export interface AnimationResult {
  passed: boolean;
  property: string;
  expectedValue: string;
  actualValue: string;
  timeElapsed: number;
}

export interface ConsoleErrorResult {
  hasErrors: boolean;
  errors: Array<{
    type: string;
    text: string;
    location: string;
  }>;
}

// ============================================================================
// Accessibility Helpers
// ============================================================================

/**
 * Run accessibility audit on the current page
 * Returns structured violations data for AI parsing
 */
export async function checkAccessibility(page: Page): Promise<AccessibilityResult> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  return {
    passed: results.violations.length === 0,
    violationCount: results.violations.length,
    violations: results.violations.map(v => ({
      id: v.id,
      impact: v.impact as AccessibilityResult['violations'][0]['impact'],
      description: v.description,
      nodes: v.nodes.map(n => ({
        selector: n.target.join(' > '),
        failureSummary: n.failureSummary || 'No summary available',
      })),
    })),
  };
}

/**
 * Check specific accessibility rule
 */
export async function checkA11yRule(page: Page, ruleId: string): Promise<boolean> {
  const results = await new AxeBuilder({ page })
    .withRules([ruleId])
    .analyze();
  
  return results.violations.length === 0;
}

// ============================================================================
// Focus & Keyboard Navigation Helpers
// ============================================================================

/**
 * Verify that tabbing through the page follows expected focus order
 */
export async function verifyFocusOrder(
  page: Page, 
  expectedSelectors: string[]
): Promise<FocusOrderResult> {
  const actual: string[] = [];
  const failures: FocusOrderResult['failures'] = [];

  // Start from body to reset focus
  await page.locator('body').click();
  
  for (let i = 0; i < expectedSelectors.length; i++) {
    await page.keyboard.press('Tab');
    
    // Get currently focused element's selector
    const focused = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return 'body';
      
      // Build a selector for the focused element
      const id = el.id ? `#${el.id}` : '';
      const classes = el.className ? `.${el.className.split(' ').join('.')}` : '';
      const tag = el.tagName.toLowerCase();
      const name = el.getAttribute('aria-label') || el.getAttribute('title') || '';
      
      return name ? `[name="${name}"]` : (id || `${tag}${classes}`);
    });
    
    actual.push(focused);
    
    // Check if focused element matches expected
    const expectedSelector = expectedSelectors[i];
    const isFocused = await page.locator(expectedSelector).evaluate(
      (el) => el === document.activeElement
    ).catch(() => false);
    
    if (!isFocused) {
      failures.push({
        index: i,
        expected: expectedSelector,
        actual: focused,
      });
    }
  }

  return {
    passed: failures.length === 0,
    expected: expectedSelectors,
    actual,
    failures,
  };
}

/**
 * Verify arrow key navigation within a component (e.g., Activity Rail)
 */
export async function verifyArrowNavigation(
  page: Page,
  containerSelector: string,
  itemSelectors: string[],
  direction: 'vertical' | 'horizontal' = 'vertical'
): Promise<FocusOrderResult> {
  const key = direction === 'vertical' ? 'ArrowDown' : 'ArrowRight';
  const failures: FocusOrderResult['failures'] = [];
  const actual: string[] = [];

  // Focus the first item
  await page.locator(itemSelectors[0]).focus();
  actual.push(itemSelectors[0]);

  for (let i = 1; i < itemSelectors.length; i++) {
    await page.keyboard.press(key);
    
    const isFocused = await page.locator(itemSelectors[i]).evaluate(
      (el) => el === document.activeElement
    ).catch(() => false);
    
    const focusedSelector = await page.evaluate(() => {
      const el = document.activeElement;
      return el?.getAttribute('title') || el?.getAttribute('aria-label') || 'unknown';
    });
    
    actual.push(focusedSelector);
    
    if (!isFocused) {
      failures.push({
        index: i,
        expected: itemSelectors[i],
        actual: focusedSelector,
      });
    }
  }

  return {
    passed: failures.length === 0,
    expected: itemSelectors,
    actual,
    failures,
  };
}

// ============================================================================
// Animation & CSS Transition Helpers
// ============================================================================

/**
 * Wait for a CSS property to reach a target value (for Framer Motion animations)
 */
export async function waitForAnimation(
  locator: Locator,
  property: string,
  expectedValue: string,
  timeout: number = 1000
): Promise<AnimationResult> {
  const startTime = Date.now();
  
  try {
    await expect(locator).toHaveCSS(property, expectedValue, { timeout });
    const actualValue = await locator.evaluate(
      (el, prop) => getComputedStyle(el).getPropertyValue(prop),
      property
    );
    
    return {
      passed: true,
      property,
      expectedValue,
      actualValue,
      timeElapsed: Date.now() - startTime,
    };
  } catch (error) {
    const actualValue = await locator.evaluate(
      (el, prop) => getComputedStyle(el).getPropertyValue(prop),
      property
    ).catch(() => 'unknown');
    
    return {
      passed: false,
      property,
      expectedValue,
      actualValue,
      timeElapsed: Date.now() - startTime,
    };
  }
}

/**
 * Verify that an element has stabilized (no more transforms/opacity changes)
 */
export async function waitForAnimationComplete(
  locator: Locator,
  timeout: number = 2000
): Promise<boolean> {
  const startTime = Date.now();
  let lastTransform = '';
  let lastOpacity = '';
  let stableCount = 0;
  
  while (Date.now() - startTime < timeout) {
    const [transform, opacity] = await locator.evaluate((el) => {
      const style = getComputedStyle(el);
      return [style.transform, style.opacity];
    });
    
    if (transform === lastTransform && opacity === lastOpacity) {
      stableCount++;
      if (stableCount >= 3) return true; // Stable for 3 consecutive checks
    } else {
      stableCount = 0;
    }
    
    lastTransform = transform;
    lastOpacity = opacity;
    
    await new Promise(r => setTimeout(r, 50));
  }
  
  return false;
}

// ============================================================================
// Performance Helpers
// ============================================================================

/**
 * Measure FPS during an action (e.g., scrolling a virtual list)
 */
export async function measureFPS(
  page: Page,
  action: () => Promise<void>,
  duration: number = 1000
): Promise<number> {
  // Inject FPS measurement script
  await page.evaluate(() => {
    (window as any).__fpsFrames = [];
    (window as any).__fpsRunning = true;
    
    const measureFrame = (timestamp: number) => {
      if ((window as any).__fpsRunning) {
        (window as any).__fpsFrames.push(timestamp);
        requestAnimationFrame(measureFrame);
      }
    };
    requestAnimationFrame(measureFrame);
  });

  // Perform the action
  await action();
  
  // Wait for measurement duration
  await page.waitForTimeout(duration);
  
  // Stop measurement and calculate FPS
  const fps = await page.evaluate(() => {
    (window as any).__fpsRunning = false;
    const frames = (window as any).__fpsFrames as number[];
    
    if (frames.length < 2) return 0;
    
    const totalTime = frames[frames.length - 1] - frames[0];
    const frameCount = frames.length - 1;
    
    return Math.round((frameCount / totalTime) * 1000);
  });

  return fps;
}

/**
 * Measure render time for a component to appear
 */
export async function measureRenderTime(
  page: Page,
  selector: string,
  trigger: () => Promise<void>
): Promise<number> {
  const startTime = Date.now();
  
  await trigger();
  await page.locator(selector).waitFor({ state: 'visible' });
  
  return Date.now() - startTime;
}

/**
 * Get Cumulative Layout Shift score
 */
export async function measureLayoutShift(
  page: Page,
  action: () => Promise<void>
): Promise<number> {
  // Set up CLS observer
  await page.evaluate(() => {
    (window as any).__clsScore = 0;
    
    const observer = new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        if (!(entry as any).hadRecentInput) {
          (window as any).__clsScore += (entry as any).value;
        }
      }
    });
    
    observer.observe({ type: 'layout-shift', buffered: true });
    (window as any).__clsObserver = observer;
  });

  await action();
  await page.waitForTimeout(500); // Wait for any shifts to settle

  const cls = await page.evaluate(() => {
    (window as any).__clsObserver?.disconnect();
    return (window as any).__clsScore || 0;
  });

  return cls;
}

// ============================================================================
// Console & Error Helpers
// ============================================================================

/**
 * Collect all console errors during a test
 */
export function setupConsoleCollector(page: Page): ConsoleErrorResult {
  const result: ConsoleErrorResult = {
    hasErrors: false,
    errors: [],
  };

  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      result.hasErrors = true;
      result.errors.push({
        type: 'console.error',
        text: msg.text(),
        location: msg.location().url,
      });
    }
  });

  page.on('pageerror', (error) => {
    result.hasErrors = true;
    result.errors.push({
      type: 'pageerror',
      text: error.message,
      location: error.stack || 'unknown',
    });
  });

  return result;
}

/**
 * Assert no console errors occurred
 */
export async function assertNoConsoleErrors(result: ConsoleErrorResult): Promise<void> {
  if (result.hasErrors) {
    const errorSummary = result.errors
      .map(e => `[${e.type}] ${e.text}`)
      .join('\n');
    throw new Error(`Console errors detected:\n${errorSummary}`);
  }
}

// ============================================================================
// State Inspection Helpers
// ============================================================================

/**
 * Read Zustand store state from the page
 * Note: Requires store to be exposed on window in dev mode
 */
export async function getStoreState<T>(page: Page, storeName: string): Promise<T | null> {
  return page.evaluate((name) => {
    // Zustand stores can be accessed via devtools if exposed
    const store = (window as any).__ZUSTAND_DEVTOOLS__?.[name];
    return store?.getState() || null;
  }, storeName);
}

/**
 * Wait for Vite HMR to complete after code changes
 */
export async function waitForHMR(page: Page, timeout: number = 5000): Promise<boolean> {
  try {
    await page.waitForFunction(
      () => !(window as any).__vite_plugin_react_preamble_installed__?.isHotUpdating,
      { timeout }
    );
    return true;
  } catch {
    return false;
  }
}

// ============================================================================
// Comprehensive Performance Audit
// ============================================================================

export interface PerformanceAuditResult {
  initialRender: {
    time: number;
    passed: boolean;
    threshold: number;
  };
  scrollFPS: {
    fps: number;
    passed: boolean;
    threshold: number;
  };
  layoutShift: {
    cls: number;
    passed: boolean;
    threshold: number;
  };
  viewSwitch: {
    time: number;
    passed: boolean;
    threshold: number;
  };
  overall: boolean;
}

/**
 * Run a comprehensive performance audit
 * Returns structured metrics with pass/fail for each category
 */
export async function runPerformanceAudit(
  page: Page,
  options: {
    initialRenderThreshold?: number;
    fpsThreshold?: number;
    clsThreshold?: number;
    viewSwitchThreshold?: number;
  } = {}
): Promise<PerformanceAuditResult> {
  const thresholds = {
    initialRender: options.initialRenderThreshold ?? 1000,
    fps: options.fpsThreshold ?? 30,
    cls: options.clsThreshold ?? 0.1,
    viewSwitch: options.viewSwitchThreshold ?? 500,
  };

  // 1. Measure initial render time
  const renderStart = Date.now();
  await page.goto('/?demo=true');
  await waitForDemoData(page);
  await page.getByTestId(E2E_TEST_IDS.searchResultRow).first().waitFor({ state: 'visible' });
  const initialRenderTime = Date.now() - renderStart;

  // 2. Measure FPS during scroll
  const scrollFPS = await measureFPS(page, async () => {
    const list = page.locator('.zone-stage');
    await list.hover();
    for (let i = 0; i < 5; i++) {
      await page.mouse.wheel(0, 200);
      await page.waitForTimeout(100);
    }
  }, 500);

  // 3. Measure CLS during view switch
  const cls = await measureLayoutShift(page, async () => {
    await page.locator('button[title="Library"]').click();
    await page.waitForTimeout(300);
    await page.locator('button[title="Search"]').click();
    await page.waitForTimeout(300);
  });

  // 4. Measure view switch time
  const switchStart = Date.now();
  await page.locator('button[title="Settings"]').click();
  await page.getByText('Appearance').waitFor({ state: 'visible' });
  await page.locator('button[title="Search"]').click();
  await page.getByTestId(E2E_TEST_IDS.searchResultRow).first().waitFor({ state: 'visible' });
  const viewSwitchTime = Date.now() - switchStart;

  const result: PerformanceAuditResult = {
    initialRender: {
      time: initialRenderTime,
      passed: initialRenderTime < thresholds.initialRender,
      threshold: thresholds.initialRender,
    },
    scrollFPS: {
      fps: scrollFPS,
      passed: scrollFPS >= thresholds.fps,
      threshold: thresholds.fps,
    },
    layoutShift: {
      cls: Math.round(cls * 1000) / 1000,
      passed: cls < thresholds.cls,
      threshold: thresholds.cls,
    },
    viewSwitch: {
      time: viewSwitchTime,
      passed: viewSwitchTime < thresholds.viewSwitch,
      threshold: thresholds.viewSwitch,
    },
    overall: false,
  };

  result.overall = result.initialRender.passed && 
                   result.scrollFPS.passed && 
                   result.layoutShift.passed && 
                   result.viewSwitch.passed;

  return result;
}

/**
 * Print performance audit results in a readable format
 */
export function formatPerformanceAudit(result: PerformanceAuditResult): string {
  const lines = [
    '┌─────────────────────────────────────────────────────┐',
    '│              PERFORMANCE AUDIT RESULTS              │',
    '├─────────────────────────────────────────────────────┤',
    `│ Initial Render: ${result.initialRender.time}ms (threshold: <${result.initialRender.threshold}ms) ${result.initialRender.passed ? '✓' : '✗'}`,
    `│ Scroll FPS:     ${result.scrollFPS.fps} (threshold: ≥${result.scrollFPS.threshold}) ${result.scrollFPS.passed ? '✓' : '✗'}`,
    `│ Layout Shift:   ${result.layoutShift.cls} (threshold: <${result.layoutShift.threshold}) ${result.layoutShift.passed ? '✓' : '✗'}`,
    `│ View Switch:    ${result.viewSwitch.time}ms (threshold: <${result.viewSwitch.threshold}ms) ${result.viewSwitch.passed ? '✓' : '✗'}`,
    '├─────────────────────────────────────────────────────┤',
    `│ OVERALL: ${result.overall ? 'PASS ✓' : 'FAIL ✗'}`,
    '└─────────────────────────────────────────────────────┘',
  ];
  return lines.join('\n');
}

// ============================================================================
// Demo Mode Helpers
// ============================================================================

/**
 * Navigate to demo mode (no backend required)
 */
export async function navigateToDemoMode(page: Page): Promise<void> {
  await page.goto('/?demo=true');
  await page.waitForLoadState('networkidle');
}

// ---------------------------------------------------------------------------
// Deterministic FIXTURE mode (tempdoc 615 §13 Move 2 / §18 P3 closure)
// ---------------------------------------------------------------------------
// Mirror of the Python `scripts/jseval/jseval/ui_fixtures.py` route-mock so BOTH
// harnesses capture the SAME deterministic state from ONE fixture set (the P3
// shape — a UI invariant checked at multiple tiers must project from one source).
// The FE parse boundary is non-fail-open, so boot-critical contracts get the
// captured schema-valid `*-live.json`; registry endpoints get minimal-valid EMPTY
// catalogs; the matcher targets the `/api` root ONLY (never the FE's own
// `/src/api/*.ts` modules). Supersedes the stale demo state (§14 U1).

const emptyCatalog = (primitive: string): string =>
  JSON.stringify({ schemaVersion: '1.0.0', catalogVersion: 0, namespace: 'core', primitive, entries: [] });

const FIXTURE_ROUTES: ReadonlyArray<readonly [string, string]> = [
  ['/api/status', _readFixture('status-response-live.json')],
  ['/api/knowledge/search', _readFixture('search-response-live.json')],
  ['/api/settings', _readFixture('settings-v2-live.json')],
  ['/api/registry/operations', emptyCatalog('Operation')],
  ['/api/registry/resources', emptyCatalog('Resource')],
  ['/api/registry/diagnostic-channels', emptyCatalog('DiagnosticChannel')],
];

// Dismiss the first-run 'welcome' walkthrough (id per canonicalManifest.ts) + pin
// the inspector tab — same seed as ui_fixtures.WALKTHROUGH_SEED.
const FIXTURE_SEED = `try {
  localStorage.setItem('justsearch-inspector-tab','ai');
  localStorage.setItem('justsearch.userState.v2', JSON.stringify({
    version: 2, activeProfileId: 'default', profiles: {},
    walkthroughState: { welcome: { activeStepIndex: 0, completedStepIds: [], dismissed: true } }
  }));
} catch (e) {}`;

/**
 * Navigate to the deterministic fixture state (no backend, route-mocked).
 *
 * Installs the shared route-mock + walkthrough seed, then waits for the search rail
 * button — the app-ready signal present on every surface. This is the e2e twin of
 * `jseval ui-shot --fixtures`, so the e2e a11y audit and the agent loop now read AND
 * capture the one state described by `governance/ui-a11y-baseline.v1.json`.
 */
export async function navigateToFixtureMode(page: Page): Promise<void> {
  await page.addInitScript(FIXTURE_SEED);
  await page.route(
    (url) => url.pathname === '/api' || url.pathname.startsWith('/api/'),
    async (route) => {
      const url = route.request().url();
      const accept = route.request().headers()['accept'] ?? '';
      if (url.includes('/stream') || accept.includes('text/event-stream')) {
        await route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
        return;
      }
      const hit = FIXTURE_ROUTES.find(([needle]) => url.includes(needle));
      await route.fulfill({ status: 200, contentType: 'application/json', body: hit ? hit[1] : '{}' });
    },
  );
  await page.goto('/');
  await page
    .locator('[data-surface-id="core.search-surface"]')
    .first()
    .waitFor({ state: 'visible', timeout: 15_000 });
}

/**
 * Navigate to a rail surface by its `core.<x>-surface` id (live Lit rail nav).
 * Replaces the retired React-era `button[title="X"]` selectors for the audit tests.
 */
export async function gotoFixtureSurface(page: Page, surfaceId: string): Promise<void> {
  const btn = page.locator(`[data-surface-id="${surfaceId}"]`).first();
  if (await btn.isVisible().catch(() => false)) {
    await btn.click();
  } else {
    // Off-rail DEEPLINK surfaces (Health/Help) have no rail button — use the shell's
    // surface hash route (mirrors ui_check._goto_surface's fallback).
    await page.evaluate((id) => {
      location.hash = `justsearch://surface/${id}`;
    }, surfaceId);
  }
  await page.waitForLoadState('networkidle');
}

/**
 * Ensure the Search input is visible and ready to accept input.
 *
 * Some flows land on non-search surfaces first (or transient launchpad states);
 * this helper normalizes that by selecting Search when available.
 */
export async function ensureSearchInputReady(
  page: Page,
  opts?: { timeoutMs?: number; focusSearchView?: boolean },
): Promise<Locator> {
  const timeoutMs = opts?.timeoutMs ?? 10_000;
  const focusSearchView = opts?.focusSearchView ?? true;
  const searchInput = page.getByTestId(E2E_TEST_IDS.searchInput).first();

  if (focusSearchView) {
    const visible = await searchInput.isVisible().catch(() => false);
    if (!visible) {
      const searchActivity = page.getByTestId(E2E_TEST_IDS.activitySearch).first();
      if (await searchActivity.isVisible().catch(() => false)) {
        await searchActivity.click();
      }
    }
  }

  await searchInput.waitFor({ state: 'visible', timeout: timeoutMs });
  return searchInput;
}

/**
 * Wait until `/api/status` responds with HTTP 200.
 *
 * If `apiPort` is provided, polls loopback directly; otherwise uses same-origin `/api/status`.
 */
export async function waitForApiStatusOk(
  page: Page,
  opts?: { apiPort?: string; timeoutMs?: number },
): Promise<void> {
  const timeoutMs = opts?.timeoutMs ?? 30_000;
  const apiPort = opts?.apiPort?.trim() ?? '';

  await expect
    .poll(
      async () =>
        page.evaluate(async (port) => {
          const base = port ? `http://127.0.0.1:${port}` : '';
          try {
            const resp = await fetch(`${base}/api/status`);
            return resp.status;
          } catch {
            return 0;
          }
        }, apiPort),
      { timeout: timeoutMs },
    )
    .toBe(200);
}

/**
 * Wait for demo data to load
 */
export async function waitForDemoData(
  page: Page,
  opts?: { query?: string; timeoutMs?: number },
): Promise<void> {
  // Demo mode currently renders *no results* until a query is present.
  // Keep this helper aligned with the product behavior so demo-mode tests remain deterministic.
  const query = opts?.query ?? 'justsearch';
  const timeoutMs = opts?.timeoutMs ?? 10_000;

  const searchInput = await ensureSearchInputReady(page, { timeoutMs, focusSearchView: true });
  await searchInput.fill(query);

  // ResultRow uses data-selected on every row; this is more specific than generic CSS classes.
  await page.locator('[data-selected]').first().waitFor({ state: 'visible', timeout: timeoutMs });
}

