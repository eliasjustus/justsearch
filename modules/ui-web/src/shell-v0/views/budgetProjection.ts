// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A3 — the agent loop's budget readout as a pure, honest projection of the budget
 * object (the §2.2 phase-totality obligation: "over budget" is a declared lifecycle phase that must
 * render honestly, never a raw negative leaking to the UI as the live audit's `Remain -383`).
 *
 * The backend accounting is honest data: every consumed token decrements `tokensRemaining` by the
 * same amount, so `tokensConsumed + tokensRemaining === initialBudget` is invariant even once a
 * final completion overruns the ceiling (driving `tokensRemaining` negative). This projection keeps
 * that raw value as the source of truth but presents it as two distinct, non-conflated facets:
 * a clamped `remaining` (never negative) and an explicit `overBudget` / `overBy` phase.
 */

/** The minimal budget shape this projection reads (an {@code AgentBudgetUpdate} on the wire). */
export interface BudgetInput {
  /** Tokens of THIS phase (per-call/projected — NOT cumulative; 577 §2.9 V4). */
  readonly tokensConsumed: number;
  readonly tokensRemaining: number;
  /** Tempdoc 577 Ext III: run-cumulative consumption. Absent/0 on legacy records ⇒ the projection
   * falls back to the per-phase figure (correct only for the first update of a run). */
  readonly totalTokensConsumed?: number;
  /** Tempdoc 577 §2.14 Root II (#14): the latest call's prompt size = current context occupancy. */
  readonly promptTokens?: number;
  /** Tempdoc 577 §2.14 Root II (#14): the model's context window (n_ctx); 0/absent ⇒ no horizon. */
  readonly contextWindow?: number;
}

export interface BudgetProjection {
  /** Tokens spent (clamped non-negative). */
  readonly consumed: number;
  /** Tokens left, clamped to >= 0 for honest display — never a raw negative. */
  readonly remaining: number;
  /** The original budget ceiling: {@code consumed + rawRemaining} (the invariant initialBudget). */
  readonly ceiling: number;
  /** Fraction of the ceiling consumed, 0..100. At or over budget this is 100. */
  readonly pct: number;
  /** The §2.2 over-budget phase — a completion overran the ceiling. */
  readonly overBudget: boolean;
  /** How far over the ceiling, when {@link overBudget} (else 0). */
  readonly overBy: number;
  /** Display band for the budget-bar fill. */
  readonly color: 'green' | 'yellow' | 'red';
}

/**
 * Project a raw budget update into an honest readout. Returns {@code null} when there is no budget
 * data yet, or when both facets are zero (nothing meaningful to show).
 */
export function projectBudget(latest: BudgetInput | null | undefined): BudgetProjection | null {
  if (!latest) return null;
  if (latest.tokensConsumed === 0 && latest.tokensRemaining === 0) return null;

  // Tempdoc 577 Ext III (§2.9 V4 fix): the wire's tokensConsumed is PER-PHASE, so the old
  // `consumed + rawRemaining` ceiling was wrong after the first LLM call. The run-cumulative
  // totalTokensConsumed restores the true invariant (cumulative + rawRemaining === initialBudget);
  // legacy records without it keep the old first-update-only derivation.
  const cumulative =
    typeof latest.totalTokensConsumed === 'number' && latest.totalTokensConsumed > 0
      ? latest.totalTokensConsumed
      : undefined;
  const consumed = Math.max(0, cumulative ?? latest.tokensConsumed);
  const rawRemaining = latest.tokensRemaining;
  const overBudget = rawRemaining < 0;
  const overBy = overBudget ? -rawRemaining : 0;
  const remaining = Math.max(0, rawRemaining);
  const ceiling = consumed + rawRemaining;
  const pct = ceiling > 0 ? Math.min(100, Math.round((consumed / ceiling) * 100)) : 100;
  const color: BudgetProjection['color'] = overBudget
    ? 'red'
    : pct < 50
      ? 'green'
      : pct < 80
        ? 'yellow'
        : 'red';

  return { consumed, remaining, ceiling, pct, overBudget, overBy, color };
}

/**
 * Tempdoc 577 §2.14 Root II (#14) — the COGNITIVE-headroom meter, sibling of the ECONOMIC budget
 * above: how full the model's context window is (latest prompt occupancy ÷ n_ctx), so the user
 * distinguishes "ran out of money" (budget) from "ran out of memory" (context). A pure projection
 * of the same {@code AgentBudgetUpdate} fields.
 */
export interface ContextHorizon {
  /** Current context occupancy in tokens (the latest call's prompt size). */
  readonly occupancy: number;
  /** The model's context window (n_ctx). */
  readonly window: number;
  /** Fraction of the window occupied, 0..100 (clamped). */
  readonly pct: number;
  /** Display band — green plenty of headroom, yellow tightening, red near the ceiling. */
  readonly color: 'green' | 'yellow' | 'red';
}

/**
 * Project the context-headroom meter. Returns {@code null} when no horizon data is available
 * (no n_ctx on the wire, or a non-occupancy phase), so the surface simply omits the meter rather
 * than rendering a misleading 0%.
 */
export function projectContextHorizon(
  latest: BudgetInput | null | undefined,
): ContextHorizon | null {
  if (!latest) return null;
  const window = latest.contextWindow ?? 0;
  const occupancy = latest.promptTokens ?? 0;
  // No denominator (n_ctx unknown) or no occupancy reported yet ⇒ nothing honest to show.
  if (window <= 0 || occupancy <= 0) return null;
  const pct = Math.min(100, Math.round((occupancy / window) * 100));
  const color: ContextHorizon['color'] = pct < 50 ? 'green' : pct < 80 ? 'yellow' : 'red';
  return { occupancy, window, pct, color };
}
