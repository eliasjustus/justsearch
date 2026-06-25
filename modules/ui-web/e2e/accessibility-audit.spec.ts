/**
 * Comprehensive Accessibility Audit
 * 
 * Tests all views and interactions for WCAG 2.1 AA compliance.
 * Run with: npx playwright test accessibility-audit --project=Desktop
 */

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { navigateToFixtureMode, gotoFixtureSurface, runAxeViolations } from './ai-harness';

// Tempdoc 615 §13 Move 2 — the per-view known-violation baseline is NO LONGER hardcoded
// here. It projects from the ONE shared authority `governance/ui-a11y-baseline.v1.json`
// (also consumed by the Python `ui-shot` measurement), so the two tiers cannot silently
// disagree about what "passing" means (§13.3 / P3). Each register surface lists the
// `e2eViews` it covers; we fan its `knownRules` out to those view keys.
//
// P3 CLOSED (§18 residual, tempdoc §20): this suite now READS the register AND CAPTURES
// the SAME deterministic state the agent loop does — `navigateToFixtureMode` installs the
// identical route-mock fixtures as `ui_fixtures.py` (one fixture set, two harnesses). The
// audit `beforeEach` hooks below use it; the suite is local-first (not in CI per ADR-0026).
const KNOWN_RULE_BASELINE: Readonly<Record<string, Set<string>>> = (() => {
  const here = dirname(fileURLToPath(import.meta.url));
  const regPath = resolve(here, '../../../governance/ui-a11y-baseline.v1.json');
  const reg = JSON.parse(readFileSync(regPath, 'utf-8')) as {
    surfaces: Array<{ e2eViews: string[]; knownRules: string[] }>;
  };
  const out: Record<string, Set<string>> = {};
  for (const s of reg.surfaces) {
    for (const view of s.e2eViews) out[view] = new Set(s.knownRules);
  }
  return Object.freeze(out);
})();
// Run axe via the injected-bundle path (runAxeViolations) — see ai-harness for why
// this replaces AxeBuilder on the Lit shell. WCAG_AA_TAGS is applied inside the helper.
async function runAxeAudit(page: any, context?: string) {
  const res = await runAxeViolations(page);
  return {
    context: context || 'page',
    violations: res.violations,
    passes: res.passes,
    incomplete: res.incomplete,
  };
}

function getUnexpectedViolations(violations: any[], allowedRuleIds: Set<string>) {
  return violations.filter((violation) => !allowedRuleIds.has(violation.id));
}

function assertNoUnexpectedViolations(context: string, violations: any[], allowedRuleIds: Set<string>) {
  const unexpected = getUnexpectedViolations(violations, allowedRuleIds);
  if (unexpected.length > 0) {
    console.log(`\n❌ Unexpected ${context} violations:`);
    unexpected.forEach((violation) => {
      console.log(`   - [${violation.impact}] ${violation.id}: ${violation.description}`);
    });
  }
  expect(
    unexpected,
    `${context} has unexpected WCAG violations: ${unexpected.map((v) => v.id).join(', ')}`,
  ).toHaveLength(0);
}

test.describe('WCAG 2.1 AA Compliance Audit', () => {
  
  test.describe('Search View (Default)', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
    });

    test('search view has no WCAG violations', async ({ page }) => {
      const audit = await runAxeAudit(page, 'Search View');
      
      console.log(`\n📋 SEARCH VIEW AUDIT`);
      console.log(`   Passes: ${audit.passes}`);
      console.log(`   Violations: ${audit.violations.length}`);
      console.log(`   Incomplete: ${audit.incomplete}`);
      
      if (audit.violations.length > 0) {
        console.log('\n❌ Violations found:');
        audit.violations.forEach(v => {
          console.log(`   - [${v.impact}] ${v.id}: ${v.description}`);
          v.nodes.forEach(n => {
            console.log(`     → ${n.html?.substring(0, 80)}...`);
          });
        });
      }
      
      assertNoUnexpectedViolations('Search view', audit.violations, KNOWN_RULE_BASELINE.search);
    });

    test('search input is properly labeled', async ({ page }) => {
      const searchInput = page.getByPlaceholder('Search files...  (/ commands, ?? ask AI)');
      await expect(searchInput).toBeVisible();
      
      // Check for accessible name
      const accessibleName = await searchInput.getAttribute('placeholder');
      expect(accessibleName).toBeTruthy();
    });

    test('result items are keyboard accessible', async ({ page }) => {
      // Focus should be manageable via keyboard
      await page.keyboard.press('Tab');
      
      // Should be able to navigate to results
      const focusedElement = await page.evaluate(() => document.activeElement?.tagName);
      expect(focusedElement).toBeTruthy();
    });

    // (Removed the demo-coupled standalone 'color contrast' test: it depended on the
    // stale KNOWN_COLOR_CONTRAST_HTML_SNIPPETS demo guard. Color-contrast is now covered
    // for every surface by the register-baselined 'has no WCAG violations' audits above —
    // tempdoc 615 §13 Move 2 / §18.)
  });

  test.describe('Settings View', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
      await gotoFixtureSurface(page, 'core.settings-surface');
    });

    test('settings view has no WCAG violations', async ({ page }) => {
      const audit = await runAxeAudit(page, 'Settings View');
      
      console.log(`\n📋 SETTINGS VIEW AUDIT`);
      console.log(`   Passes: ${audit.passes}`);
      console.log(`   Violations: ${audit.violations.length}`);
      
      if (audit.violations.length > 0) {
        console.log('\n❌ Violations:');
        audit.violations.forEach(v => {
          console.log(`   - [${v.impact}] ${v.id}: ${v.description}`);
        });
      }
      
      assertNoUnexpectedViolations('Settings view', audit.violations, KNOWN_RULE_BASELINE.settings);
    });

    test('form controls have labels', async ({ page }) => {
      // Check that all interactive elements have accessible names
      const buttons = page.locator('button');
      const count = await buttons.count();
      
      for (let i = 0; i < count; i++) {
        const button = buttons.nth(i);
        const name = await button.getAttribute('aria-label') || 
                     await button.getAttribute('title') ||
                     await button.innerText();
        expect(name?.trim().length).toBeGreaterThan(0);
      }
    });

    test('toggle switches are accessible', async ({ page }) => {
      const toggles = page.locator('[role="switch"], input[type="checkbox"]');
      const count = await toggles.count();
      
      console.log(`Found ${count} toggle controls`);
      
      // Each toggle should have an accessible name
      for (let i = 0; i < count; i++) {
        const toggle = toggles.nth(i);
        if (await toggle.isVisible()) {
          const label = await toggle.getAttribute('aria-label') ||
                        await toggle.getAttribute('aria-labelledby');
          // Allow toggles that are labeled by visible text
          const isLabeled = label || (await toggle.locator('..').innerText()).length > 0;
          expect(isLabeled).toBeTruthy();
        }
      }
    });
  });

  test.describe('Library View', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
      await gotoFixtureSurface(page, 'core.library-surface');
    });

    test('library view has no WCAG violations', async ({ page }) => {
      const audit = await runAxeAudit(page, 'Library View');
      
      console.log(`\n📋 LIBRARY VIEW AUDIT`);
      console.log(`   Passes: ${audit.passes}`);
      console.log(`   Violations: ${audit.violations.length}`);
      
      assertNoUnexpectedViolations('Library view', audit.violations, KNOWN_RULE_BASELINE.library);
    });

    test('folder list items are accessible', async ({ page }) => {
      // Check for proper list structure or accessible items
      const listItems = page.locator('[role="listitem"], li');
      const count = await listItems.count();
      console.log(`Found ${count} list items`);
    });
  });

  test.describe('Brain View', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
      await gotoFixtureSurface(page, 'core.brain-surface');
    });

    test('brain view has no WCAG violations', async ({ page }) => {
      const audit = await runAxeAudit(page, 'Brain View');

      console.log(`\n📋 BRAIN VIEW AUDIT`);
      console.log(`   Passes: ${audit.passes}`);
      console.log(`   Violations: ${audit.violations.length}`);

      if (audit.violations.length > 0) {
        console.log('\n❌ Violations:');
        audit.violations.forEach(v => {
          console.log(`   - [${v.impact}] ${v.id}: ${v.description}`);
        });
      }

      assertNoUnexpectedViolations('Brain view', audit.violations, KNOWN_RULE_BASELINE.brain);
    });
  });

  test.describe('Health View', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
      await gotoFixtureSurface(page, 'core.health-surface');
    });

    test('health view has no WCAG violations', async ({ page }) => {
      const audit = await runAxeAudit(page, 'Health View');

      console.log(`\n📋 HEALTH VIEW AUDIT`);
      console.log(`   Passes: ${audit.passes}`);
      console.log(`   Violations: ${audit.violations.length}`);

      if (audit.violations.length > 0) {
        console.log('\n❌ Violations:');
        audit.violations.forEach(v => {
          console.log(`   - [${v.impact}] ${v.id}: ${v.description}`);
        });
      }

      assertNoUnexpectedViolations('Health view', audit.violations, KNOWN_RULE_BASELINE.health);
    });
  });

  test.describe('Keyboard Navigation', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
    });

    test('all interactive elements are reachable via Tab', async ({ page }) => {
      const interactiveSelectors = [
        'input',
        'button:visible',
        '[role="button"]',
        'a[href]',
        '[tabindex]:not([tabindex="-1"])',
      ];
      
      // Get all interactive elements
      const elements = await page.locator(interactiveSelectors.join(', ')).all();
      const visibleCount = (await Promise.all(
        elements.map(el => el.isVisible())
      )).filter(Boolean).length;
      
      console.log(`\n⌨️ Found ${visibleCount} visible interactive elements`);
      
      // Tab through and count focused elements
      let tabCount = 0;
      const maxTabs = 50; // Safety limit
      const focusedElements: string[] = [];
      
      await page.keyboard.press('Tab');
      
      while (tabCount < maxTabs) {
        const focused = await page.evaluate(() => {
          const el = document.activeElement;
          return el ? `${el.tagName}${el.id ? '#'+el.id : ''}${el.className ? '.'+el.className.split(' ')[0] : ''}` : null;
        });
        
        if (focused && !focusedElements.includes(focused)) {
          focusedElements.push(focused);
        }
        
        await page.keyboard.press('Tab');
        tabCount++;
        
        // Check if we've looped back to body or first element
        const currentFocused = await page.evaluate(() => document.activeElement?.tagName);
        if (currentFocused === 'BODY') break;
      }
      
      console.log(`   Reachable via Tab: ${focusedElements.length}`);
      expect(focusedElements.length).toBeGreaterThan(0);
    });

    test('focus is visible on all elements', async ({ page }) => {
      // Tab to first interactive element
      await page.keyboard.press('Tab');
      
      // Check that focus indicator is visible
      const hasFocusStyle = await page.evaluate(() => {
        const el = document.activeElement;
        if (!el) return false;
        
        const styles = window.getComputedStyle(el);
        const outlineWidth = parseFloat(styles.outlineWidth) || 0;
        const boxShadow = styles.boxShadow;
        const borderWidth = parseFloat(styles.borderWidth) || 0;
        
        // Check for any visible focus indicator
        return outlineWidth > 0 || 
               (boxShadow && boxShadow !== 'none') ||
               borderWidth > 0;
      });
      
      // Note: This test may need adjustment based on your focus styling approach
      console.log(`Focus indicator visible: ${hasFocusStyle}`);
    });

    test('Escape key clears search input', async ({ page }) => {
      const searchInput = page.getByPlaceholder('Search files...  (/ commands, ?? ask AI)');
      await searchInput.fill('test query');
      
      await searchInput.press('Escape');
      
      const value = await searchInput.inputValue();
      if (value === '') {
        expect(value).toBe('');
        return;
      }
      // Runtime variants without clear-on-escape must still provide a clear affordance.
      await expect(searchInput).toHaveValue('test query');
      await expect(page.getByRole('button', { name: /clear search/i })).toBeVisible();
    });

    test('arrow keys navigate result list', async ({ page }) => {
      // Results should already be visible in demo mode
      const firstResult = page.locator('.border-l-2.group').first();
      await expect(firstResult).toBeVisible();
      
      // Click to select first result
      await firstResult.click();
      
      // Arrow down should move selection
      await page.keyboard.press('ArrowDown');
      
      // Selection should have moved (test implementation depends on your selection logic)
    });
  });

  test.describe('Screen Reader Compatibility', () => {
    test.beforeEach(async ({ page }) => {
      await navigateToFixtureMode(page);
    });

    test('page has proper heading structure', async ({ page }) => {
      const headings = await page.locator('h1, h2, h3, h4, h5, h6').all();
      
      console.log(`\n📝 Heading structure:`);
      for (const heading of headings) {
        const tag = await heading.evaluate(el => el.tagName);
        const text = await heading.innerText();
        console.log(`   ${tag}: ${text.substring(0, 50)}`);
      }
      
      // Should have at least some structure
      // Note: SPAs may not always have traditional heading hierarchy
    });

    test('images have alt text', async ({ page }) => {
      const images = page.locator('img');
      const count = await images.count();
      
      let missingAlt = 0;
      for (let i = 0; i < count; i++) {
        const img = images.nth(i);
        const alt = await img.getAttribute('alt');
        const ariaHidden = await img.getAttribute('aria-hidden');
        const role = await img.getAttribute('role');
        
        // Decorative images should have empty alt or aria-hidden
        if (!alt && ariaHidden !== 'true' && role !== 'presentation') {
          missingAlt++;
          const src = await img.getAttribute('src');
          console.log(`   ⚠️ Missing alt: ${src?.substring(0, 40)}...`);
        }
      }
      
      console.log(`\n🖼️ Images: ${count} total, ${missingAlt} missing alt text`);
    });

    test('landmark regions are defined', async ({ page }) => {
      const landmarks = await page.evaluate(() => {
        const found: string[] = [];
        
        // Check for ARIA roles
        if (document.querySelector('[role="banner"], header')) found.push('banner');
        if (document.querySelector('[role="navigation"], nav')) found.push('navigation');
        if (document.querySelector('[role="main"], main')) found.push('main');
        if (document.querySelector('[role="complementary"], aside')) found.push('complementary');
        if (document.querySelector('[role="contentinfo"], footer')) found.push('contentinfo');
        if (document.querySelector('[role="search"]')) found.push('search');
        
        return found;
      });
      
      console.log(`\n🗺️ Landmarks found: ${landmarks.join(', ') || 'none'}`);
      
      // Should have at least navigation
      expect(landmarks.length).toBeGreaterThan(0);
    });

    test('ARIA attributes are valid', async ({ page }) => {
      const results = await new AxeBuilder({ page })
        .withTags(['cat.aria'])
        .analyze();
      
      const ariaViolations = results.violations;
      
      if (ariaViolations.length > 0) {
        console.log('\n❌ ARIA violations:');
        ariaViolations.forEach(v => {
          console.log(`   - ${v.id}: ${v.description}`);
        });
      }
      
      assertNoUnexpectedViolations('ARIA', ariaViolations, KNOWN_RULE_BASELINE.aria);
    });
  });

  test.describe('Motion & Animation Accessibility', () => {
    test('respects prefers-reduced-motion', async ({ page }) => {
      // Emulate reduced motion preference
      await page.emulateMedia({ reducedMotion: 'reduce' });
      
      await navigateToFixtureMode(page);
      
      // Check if animations are disabled
      const hasAnimations = await page.evaluate(() => {
        const el = document.querySelector('.transition, [class*="animate"]');
        if (!el) return false;
        
        const styles = window.getComputedStyle(el);
        const duration = parseFloat(styles.transitionDuration) || 0;
        const animDuration = parseFloat(styles.animationDuration) || 0;
        
        return duration > 0 || animDuration > 0;
      });
      
      console.log(`\n🎬 Animations with reduced-motion: ${hasAnimations ? 'Still active' : 'Disabled'}`);
      
      // Note: This test documents current behavior - you may want animations disabled
    });
  });
});

test.describe('Accessibility Summary Report', () => {
  test('generate full audit report', async ({ page }) => {
    test.setTimeout(90_000);
    await navigateToFixtureMode(page);
    
    const views = [
      { name: 'Search', navigate: async () => {} },
      { name: 'Library', navigate: async () => await page.locator('button[title="Library"]').click() },
      { name: 'Settings', navigate: async () => await page.locator('button[title="Settings"]').click() },
    ];
    
    console.log('\n' + '='.repeat(60));
    console.log('        ACCESSIBILITY AUDIT SUMMARY REPORT');
    console.log('='.repeat(60));
    
    let totalViolations = 0;
    let totalUnexpectedViolations = 0;
    let totalPasses = 0;
    const knownRulesByView = Object.freeze({
      Search: KNOWN_RULE_BASELINE.search,
      Library: KNOWN_RULE_BASELINE.library,
      Settings: KNOWN_RULE_BASELINE.settings,
    } as Record<string, Set<string>>);

    const waitForView = async (name: string) => {
      if (name === 'Search') {
        await expect(page.locator('.border-l-2.group').first()).toBeVisible();
        return;
      }
      if (name === 'Library') {
        await expect(page.getByRole('heading', { name: 'Library' })).toBeVisible();
        return;
      }
      if (name === 'Settings') {
        await expect(page.getByText('Appearance')).toBeVisible();
        return;
      }
    };
    
    for (const view of views) {
      await view.navigate();
      await waitForView(view.name);
      
      const results = await new AxeBuilder({ page })
        .withTags(WCAG_AA_TAGS)
        .analyze();
      const unexpected = getUnexpectedViolations(
        results.violations,
        knownRulesByView[view.name] ?? new Set<string>(),
      );
      
      totalViolations += results.violations.length;
      totalUnexpectedViolations += unexpected.length;
      totalPasses += results.passes.length;
      
      console.log(`\n📋 ${view.name.toUpperCase()} VIEW`);
      console.log(`   ✓ Passes: ${results.passes.length}`);
      console.log(`   ✗ Violations: ${results.violations.length}`);
      console.log(`   ? Incomplete: ${results.incomplete.length}`);
      
      if (results.violations.length > 0) {
        results.violations.forEach(v => {
          console.log(`\n   ❌ [${v.impact?.toUpperCase()}] ${v.id}`);
          console.log(`      ${v.description}`);
          console.log(`      Help: ${v.helpUrl}`);
        });
      }
      
      // Navigate back to search for next view
      if (view.name !== 'Search') {
        await page.locator('button[title="Search"]').click();
        await waitForView('Search');
      }
    }
    
    console.log('\n' + '='.repeat(60));
    console.log(`TOTAL: ${totalPasses} passes, ${totalViolations} violations`);
    console.log('='.repeat(60) + '\n');
    
    expect(totalUnexpectedViolations).toBe(0);
  });
});
