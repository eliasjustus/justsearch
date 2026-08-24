/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import java.util.Locale;

/**
 * Tempdoc 859 §D §2.1 — the ONE mapping from a delegate run's declared EFFORT rung to its initial
 * token budget.
 *
 * <p><b>The defect this closes.</b> The initial budget used to be {@code n_ctx - 256}: the run's
 * <em>economic</em> allowance initialized to exactly one <em>cognitive</em> window. But a run is
 * many windows of spend by construction — every LLM call is charged its full reported prompt
 * <em>and</em> completion ({@code AgentLlmCaller} → {@link AgentSession#recordUsage}), the prompt
 * grows by roughly one tool result per iteration, and nothing amortizes. Same unit, different
 * meaning; the multiplier was silently 1. That is invisible on a large-context model and fatal on
 * the compact profile.
 *
 * <p><b>Why a multiplier and not three absolute numbers.</b> It scales with the model (per-iteration
 * burn is a function of what the model can hold), it preserves the one existing derivation so
 * nothing else that reads {@code initialBudget} changes shape, and it fixes the actual bug.
 *
 * <p><b>Why {@link #THOROUGH_MULTIPLIER} is 15 — a structural bound, not a burn estimate.</b>
 *
 * <pre>
 *   spend(one call) &lt;= promptTokens + completionTokens &lt;= n_ctx + maxTokens   (maxTokens = 1024)
 *   spend(run)      &lt;= maxIterations * (n_ctx + maxTokens)
 *   as a multiple    = maxIterations * (1 + maxTokens/n_ctx)
 *                    = 12.5x @ n_ctx 4096   = 10.3x @ n_ctx 32k   -&gt; 10x asymptote
 * </pre>
 *
 * 15x clears that bound for every {@code n_ctx >= 2048} and for every burn SHAPE, because the bound
 * does not depend on burn at all. So Thorough means precisely: <em>tokens can never be what stops
 * this run — the iteration cap is.</em>
 *
 * <p><b>The premise the bound rests on.</b> {@code spend(one call) <= n_ctx + maxTokens} holds only
 * while prompts stay within {@code n_ctx}. At 15x that is not free — it is what the context gate's
 * ask-once-then-auto-compact behaviour ({@link AgentStepRunner}) guarantees. The two ship together
 * or the bound is unproven.
 *
 * <p><b>Honest limit.</b> {@link #STANDARD_MULTIPLIER} is the one taste call: its value is fitted to
 * a single measured run (859 §7 — 3840 exhausted after two tool calls on a read-three-files task at
 * {@code n_ctx} 4096) and rests on that datapoint until the live leg L1 measures it. Quick and
 * Thorough are argued structurally; Standard is not.
 *
 * <p><b>No rung reduces today's allowance.</b> {@code 2 * n_ctx > n_ctx - 256} for every
 * {@code n_ctx}, so the leash is only INTER-rung: Quick is the smallest raise, never a restriction.
 */
final class AgentBudgetPolicy {

  private AgentBudgetPolicy() {}

  /** The smallest raise — a couple of steps' worth of room. Not a limiter (see class javadoc). */
  static final int QUICK_MULTIPLIER = 2;

  /** The default rung, and what an absent or unrecognized rung resolves to. */
  static final int STANDARD_MULTIPLIER = 5;

  /** Clears the structural per-run spend bound, so the iteration cap is what stops the run. */
  static final int THOROUGH_MULTIPLIER = 15;

  /**
   * Tempdoc 859 §D §2.9 — a BACKGROUND run is pinned to today's one-window behaviour. It bypasses
   * both the context gate and the budget gate ({@link AgentStepRunner}), so it cannot park and
   * cannot be asked anything: a raised budget for it would be raised UNSUPERVISED spend with no
   * decision point anywhere. A background rung is deliberately future work.
   */
  static final int BACKGROUND_MULTIPLIER = 1;

  /**
   * The pre-859 safety margin, retained for BACKGROUND runs only.
   *
   * <p>"Pinned at today's behaviour" has to be literally true, or the sentence is doing rhetorical
   * work the code does not. Today's behaviour was {@code n_ctx - 256}, not {@code n_ctx}: dropping
   * the margin here would have quietly granted every unsupervised run 256 tokens more than it has
   * now — small, but in the one place the design explicitly refuses to change spend.
   *
   * <p>The FOREGROUND rungs delete the margin, because there it was standing in for "leave room for
   * the response" and the between-step gate does that job properly by projecting the real next
   * prompt. A background run reaches the same gate, so the margin is not load-bearing for it either
   * — it is kept because "unchanged" is the claim being made.
   */
  static final int BACKGROUND_SAFETY_MARGIN = 256;

  /** The wire tokens the FE's {@code Sv3Effort} union sends. Pinned FE-side by its own test. */
  private static final String QUICK = "quick";

  private static final String THOROUGH = "thorough";

  /**
   * The rung → multiplier mapping. An absent, blank or unrecognized rung is Standard: a caller that
   * constructs an {@code AgentRequest} without an effort (the legacy window, a seam adopter, a
   * resumed run) gets the intended fix rather than a failure.
   */
  static int multiplier(String effort, boolean background) {
    if (background) {
      return BACKGROUND_MULTIPLIER;
    }
    if (effort == null) {
      return STANDARD_MULTIPLIER;
    }
    String rung = effort.trim().toLowerCase(Locale.ROOT);
    if (QUICK.equals(rung)) {
      return QUICK_MULTIPLIER;
    }
    if (THOROUGH.equals(rung)) {
      return THOROUGH_MULTIPLIER;
    }
    return STANDARD_MULTIPLIER;
  }

  /**
   * The run's initial economic budget: the model's context window times the rung's multiplier.
   *
   * <p>There is no safety margin subtracted. The 256-token margin this replaced was standing in for
   * "leave room for the response"; the between-step gate already projects the real next prompt
   * against the remaining budget, which is that job done properly.
   *
   * @param effort the rung wire token ({@code quick|standard|thorough}); null/unknown ⇒ Standard
   * @param contextWindow the model's {@code n_ctx}; clamped at 0
   * @param background whether this is a background (ungated, unsupervised) run ⇒ pinned at 1x
   */
  static int initialBudget(String effort, int contextWindow, boolean background) {
    long window = Math.max(0, contextWindow);
    if (background) {
      // Byte-for-byte today's formula — see BACKGROUND_SAFETY_MARGIN.
      return (int) Math.max(0, window - BACKGROUND_SAFETY_MARGIN);
    }
    long budget = window * multiplier(effort, background);
    return (int) Math.min(Integer.MAX_VALUE, budget);
  }
}
