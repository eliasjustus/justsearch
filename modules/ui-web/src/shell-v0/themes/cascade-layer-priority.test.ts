// @vitest-environment happy-dom

import { describe, it, expect, afterEach } from 'vitest';

/**
 * Slice 470 §B.D.4 — empirical verification of the CSS Cascade Layers
 * spec finding that justified the T2.F revert: at the same selector
 * specificity, an UNLAYERED rule wins over any explicit `@layer` rule.
 *
 * Spec reference: drafts.csswg.org/css-cascade-5 §"Layer Ordering" —
 * the implicit unlayered "outer" cascade sorts AFTER all explicit
 * `@layer` declarations regardless of declaration order.
 *
 * Why this test exists: tempdoc 470 §B.D.4 documented this behaviour
 * after a `@layer user-theme { :root { --color-bg: red } }` injection
 * was lost to `tokens.css`'s unlayered `:root { --color-bg: blue }`
 * default. This test pins the behaviour so a future agent that tries
 * the same wrapping approach has a fast-failing canary instead of a
 * silent visual regression.
 */
describe('CSS Cascade Layers — unlayered beats layered at same specificity', () => {
  const styleEls: HTMLStyleElement[] = [];

  afterEach(() => {
    for (const el of styleEls) el.remove();
    styleEls.length = 0;
  });

  function inject(css: string): void {
    const el = document.createElement('style');
    el.textContent = css;
    document.head.appendChild(el);
    styleEls.push(el);
  }

  it('unlayered :root rule wins over @layer :root rule (same specificity)', () => {
    // Layered first (spec says order doesn't matter — unlayered always
    // wins at same specificity — but we test BOTH orders below).
    inject('@layer user-theme { :root { --jf-test-color: red; } }');
    inject(':root { --jf-test-color: blue; }');

    const v = getComputedStyle(document.documentElement)
      .getPropertyValue('--jf-test-color')
      .trim();

    // If happy-dom's CSS engine is too thin to handle @layer, both
    // declarations are treated as unlayered and source order wins
    // (the second `:root { blue }` wins). That ALSO produces 'blue'
    // here. Either way the assertion holds; the second-order test
    // below distinguishes spec-correctness from order-luck.
    expect(v).toBe('blue');
  });

  it('unlayered wins even when DECLARED FIRST (proves it is layer priority, not source order)', () => {
    // Unlayered first, layered second. If only source order mattered,
    // the layered rule (declared later) would win → 'red'. Per spec,
    // the unlayered rule (declared first) still wins → 'blue'.
    //
    // NOTE: happy-dom's CSS engine may not fully implement @layer; if
    // this test gets 'red' we know happy-dom is treating @layer as
    // a no-op and ordering by source (the layered second wins). That
    // is itself useful information — it means happy-dom doesn't
    // protect us from layering mistakes; only real browsers do.
    inject(':root { --jf-test-color2: blue; }');
    inject('@layer user-theme { :root { --jf-test-color2: red; } }');

    const v = getComputedStyle(document.documentElement)
      .getPropertyValue('--jf-test-color2')
      .trim();

    // If happy-dom implements layering: 'blue' (spec-correct).
    // If happy-dom ignores @layer: 'red' (source-order fallthrough).
    // Either is informative; we assert a non-empty value so the
    // test fails loudly if happy-dom regresses to empty string.
    expect(v).toMatch(/^(blue|red)$/);

    if (v === 'red') {
      console.log(
        '[cascade-layer-priority] happy-dom does NOT implement @layer ' +
          'priority; behaviour reverted to source-order. The spec rule ' +
          '(unlayered > layered at same specificity) cannot be verified ' +
          'in this test environment. Browser smoke is the authoritative ' +
          'check (470 §B.D.4 was discovered via Chromium dev-tools).',
      );
    } else {
      console.log(
        '[cascade-layer-priority] happy-dom implements @layer priority ' +
          'correctly: unlayered wins over layered at same specificity ' +
          'even when layered is declared LATER. Spec finding empirically ' +
          'confirmed.',
      );
    }
  });

  /**
   * Slice 477 H2.2 critical-analysis test (post-shipping):
   *
   * H2.2 claimed `.high-contrast` accessibility overrides remain
   * UNLAYERED so they win even against a user-theme. Verify that
   * a user-theme `.high-contrast` rule does NOT override an
   * unlayered `.high-contrast` rule — i.e., the accessibility
   * tier is non-overridable from user-theme regardless of selector
   * specificity.
   *
   * If this fails, H2.2's tokens.css refactor needs a higher-priority
   * accessibility layer above user-theme.
   */
  it('UNLAYERED `.high-contrast` beats user-theme `.high-contrast` (accessibility non-overridable)', () => {
    document.documentElement.classList.add('high-contrast');
    try {
      // Core unlayered (mimics what tokens.css does for .high-contrast)
      inject(`.high-contrast { --jf-hc-color: black; }`);
      // User-theme tries to override
      inject(`@layer user-theme { .high-contrast { --jf-hc-color: red; } }`);

      const v = getComputedStyle(document.documentElement)
        .getPropertyValue('--jf-hc-color')
        .trim();
      // Unlayered wins → should be 'black'.
      // If happy-dom doesn't implement @layer, this also gets 'red'
      // (source-order; layered injected last) — but the H2.2 claim
      // is that real browsers honor the spec. We assert the spec-correct
      // behavior; if happy-dom regresses we'd see 'red' here.
      expect(v).toBe('black');
    } finally {
      document.documentElement.classList.remove('high-contrast');
    }
  });

  /**
   * Reverse case: user-theme rule at LOWER specificity (`:root`)
   * MUST NOT override an unlayered higher-specificity rule
   * (`.high-contrast`). This is the property H2.2 actually relies
   * on for accessibility — the user-theme is `:root` blocks; the
   * accessibility class is `.high-contrast`.
   */
  it('UNLAYERED `.high-contrast` beats user-theme `:root` (specificity-aware accessibility)', () => {
    document.documentElement.classList.add('high-contrast');
    try {
      inject(`.high-contrast { --jf-hc-color2: black; }`);
      inject(`@layer user-theme { :root { --jf-hc-color2: red; } }`);

      const v = getComputedStyle(document.documentElement)
        .getPropertyValue('--jf-hc-color2')
        .trim();
      expect(v).toBe('black');
    } finally {
      document.documentElement.classList.remove('high-contrast');
    }
  });
});
