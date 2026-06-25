import { describe, it, expect } from 'vitest';
import { projectBudget, projectContextHorizon } from './budgetProjection.js';

describe('projectBudget (tempdoc 561 P-A3 — honest over-budget render)', () => {
  it('returns null when there is no budget data', () => {
    expect(projectBudget(null)).toBeNull();
    expect(projectBudget(undefined)).toBeNull();
  });

  it('returns null when both facets are zero (nothing meaningful to show)', () => {
    expect(projectBudget({ tokensConsumed: 0, tokensRemaining: 0 })).toBeNull();
  });

  it('projects a healthy budget with the ceiling, clamped remaining, and a green band', () => {
    const b = projectBudget({ tokensConsumed: 300, tokensRemaining: 700 })!;
    expect(b.ceiling).toBe(1000);
    expect(b.remaining).toBe(700);
    expect(b.pct).toBe(30);
    expect(b.overBudget).toBe(false);
    expect(b.overBy).toBe(0);
    expect(b.color).toBe('green');
  });

  it('renders the OVER-BUDGET phase honestly — clamps remaining to 0, never the raw negative', () => {
    // The live-audit case: a completion overran a 1000-token ceiling by 383.
    const b = projectBudget({ tokensConsumed: 1383, tokensRemaining: -383 })!;
    expect(b.overBudget).toBe(true);
    expect(b.overBy).toBe(383);
    // remaining is clamped — the UI never shows `-383`.
    expect(b.remaining).toBe(0);
    // the ceiling is recovered from the raw value (consumed + rawRemaining), not a shrunk total.
    expect(b.ceiling).toBe(1000);
    expect(b.pct).toBe(100);
    expect(b.color).toBe('red');
  });

  it('treats exactly-exhausted (remaining 0, consumed > 0) as full but not over budget', () => {
    const b = projectBudget({ tokensConsumed: 1000, tokensRemaining: 0 })!;
    expect(b.overBudget).toBe(false);
    expect(b.pct).toBe(100);
    expect(b.color).toBe('red');
  });

  it('bands the color by consumption: green < 50% < yellow < 80% <= red', () => {
    expect(projectBudget({ tokensConsumed: 40, tokensRemaining: 60 })!.color).toBe('green');
    expect(projectBudget({ tokensConsumed: 65, tokensRemaining: 35 })!.color).toBe('yellow');
    expect(projectBudget({ tokensConsumed: 90, tokensRemaining: 10 })!.color).toBe('red');
  });

  // Tempdoc 577 Ext III (§2.9 V4 fix) — the wire's tokensConsumed is PER-PHASE; only the
  // run-cumulative totalTokensConsumed reconstructs the true ceiling after iteration 1.
  describe('cumulative consumption (577 Ext III)', () => {
    it('derives the ceiling from totalTokensConsumed when present (multi-iteration run)', () => {
      // Iteration 3's llm_response: this call used 900 tokens, the run has used 5300 of 6000.
      const b = projectBudget({
        tokensConsumed: 900,
        tokensRemaining: 700,
        totalTokensConsumed: 5300,
      })!;
      expect(b.consumed).toBe(5300);
      expect(b.ceiling).toBe(6000); // NOT 900 + 700 = 1600 (the V4 bug)
      expect(b.pct).toBe(88);
      expect(b.color).toBe('red');
      expect(b.overBudget).toBe(false);
    });

    it('reports an over-budget multi-iteration run with the true granted ceiling', () => {
      const b = projectBudget({
        tokensConsumed: 1200,
        tokensRemaining: -707,
        totalTokensConsumed: 6707,
      })!;
      expect(b.overBudget).toBe(true);
      expect(b.overBy).toBe(707);
      expect(b.ceiling).toBe(6000);
      expect(b.consumed).toBe(6707);
    });

    it('falls back to the per-phase figure on legacy records (absent or zero cumulative)', () => {
      const legacy = projectBudget({ tokensConsumed: 300, tokensRemaining: 700 })!;
      expect(legacy.ceiling).toBe(1000);
      const zeroed = projectBudget({
        tokensConsumed: 300,
        tokensRemaining: 700,
        totalTokensConsumed: 0,
      })!;
      expect(zeroed.ceiling).toBe(1000);
    });
  });

  describe('projectContextHorizon (tempdoc 577 §2.14 Root II — cognitive headroom)', () => {
    it('returns null without budget data', () => {
      expect(projectContextHorizon(null)).toBeNull();
      expect(projectContextHorizon(undefined)).toBeNull();
    });

    it('returns null when n_ctx is unknown (no denominator) or occupancy not yet reported', () => {
      // n_ctx absent ⇒ cannot show a ratio
      expect(
        projectContextHorizon({ tokensConsumed: 1, tokensRemaining: 1, promptTokens: 1024 }),
      ).toBeNull();
      // occupancy absent (e.g. the iteration_start phase) ⇒ nothing honest to show
      expect(
        projectContextHorizon({ tokensConsumed: 1, tokensRemaining: 1, contextWindow: 8192 }),
      ).toBeNull();
    });

    it('projects occupancy ÷ n_ctx into a clamped percentage + status band', () => {
      const h = projectContextHorizon({
        tokensConsumed: 100,
        tokensRemaining: 100,
        promptTokens: 2048,
        contextWindow: 8192,
      })!;
      expect(h.occupancy).toBe(2048);
      expect(h.window).toBe(8192);
      expect(h.pct).toBe(25);
      expect(h.color).toBe('green');
    });

    it('bands yellow at >=50% and red at >=80%, clamping over-full to 100', () => {
      expect(
        projectContextHorizon({ tokensConsumed: 0, tokensRemaining: 0, promptTokens: 4096, contextWindow: 8192 })!
          .color,
      ).toBe('yellow');
      expect(
        projectContextHorizon({ tokensConsumed: 0, tokensRemaining: 0, promptTokens: 7000, contextWindow: 8192 })!
          .color,
      ).toBe('red');
      const over = projectContextHorizon({
        tokensConsumed: 0,
        tokensRemaining: 0,
        promptTokens: 9000,
        contextWindow: 8192,
      })!;
      expect(over.pct).toBe(100);
      expect(over.color).toBe('red');
    });
  });
});
