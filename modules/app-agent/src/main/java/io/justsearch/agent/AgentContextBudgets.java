/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.util.ContextBudget;
import java.util.function.ToIntFunction;

/**
 * Tempdoc 883 decision 3 — the agent loop's view of {@link ContextBudget}: how a delegate run turns
 * the live context window plus its two operator knobs into per-call limits.
 *
 * <p><b>The defect this closes.</b> {@code AgentLlmCaller.DEFAULT_MAX_TOKENS} and
 * {@code AgentContextCompressor.MAX_TOOL_RESULT_CHARS} were {@code static final} fields resolved
 * from config at CLASS-INIT. A window change at runtime — which is now routine, since the launch
 * ladder derives the window per activation and a step-down can change it mid-session — never
 * reached either of them; nor did a config change after the first tool call. Both are resolved here,
 * per call.
 *
 * <p><b>The knobs.</b> {@code justsearch.agent.max_completion_tokens} and
 * {@code justsearch.agent.max_tool_result_chars} both default to {@code 0 = derive from the window};
 * a positive value is an explicit operator ceiling and is honoured verbatim, never silently reduced.
 * That is the same "0 means auto, an override is honoured or fails loud" shape tempdoc 883 PR 1 gave
 * {@code contextLength} — an operator who names a number gets that number.
 */
public final class AgentContextBudgets {

  /**
   * The per-call completion cap when the operator has not named one, and the ceiling the window
   * fraction is capped BY when they have not.
   *
   * <p>Not window-derived upward on purpose: an answer does not get longer because the window did,
   * and {@code AgentBudgetPolicy}'s structural per-run spend bound
   * ({@code maxIterations * (n_ctx + maxTokens)}) is stated against this number. The window fraction
   * inside {@link ContextBudget#withDerivedReserve} can only take it DOWN, at a window too small to
   * afford it.
   */
  static final int PREFERRED_COMPLETION_TOKENS = 1024;

  /** The floor a completion cap is never taken below — a reserve under this cannot answer at all. */
  static final int MIN_COMPLETION_TOKENS = 256;

  /** The floor a tool-result cap is never taken below (mirrors the pre-883 clamp). */
  static final int MIN_TOOL_RESULT_CHARS = 100;

  private AgentContextBudgets() {}

  /**
   * The budget for one agent LLM call, built from the live window and this process's config.
   *
   * @param onlineAiService the inference handle; null or an unavailable one budgets against
   *     {@link ContextBudget#FALLBACK_WINDOW_TOKENS}
   */
  public static ContextBudget forCall(OnlineAiService onlineAiService) {
    Integer observed = onlineAiService == null ? null : onlineAiService.llmContextTokens();
    Integer configured = onlineAiService == null ? null : onlineAiService.configuredContextTokens();
    int operatorCap = resolveInt(rc -> rc.agent().maxCompletionTokens());
    int preferred = operatorCap > 0 ? operatorCap : PREFERRED_COMPLETION_TOKENS;
    ContextBudget budget = ContextBudget.withDerivedReserve(observed, configured, preferred);
    if (budget.completionReserve() >= MIN_COMPLETION_TOKENS) {
      return budget;
    }
    // A window small enough to derive a sub-256 reserve cannot answer; the floor wins and the input
    // budget is recomputed against it, so the two halves of the budget still add up.
    return ContextBudget.of(observed, configured, MIN_COMPLETION_TOKENS);
  }

  /**
   * The Layer-2 per-tool-result character cap for this budget.
   *
   * <p>An explicit {@code justsearch.agent.max_tool_result_chars} wins verbatim; otherwise the
   * window-derived {@link ContextBudget#toolResultCapChars()}.
   */
  public static int toolResultCapChars(ContextBudget budget) {
    int operatorCap = resolveInt(rc -> rc.agent().maxToolResultChars());
    int cap = operatorCap > 0 ? operatorCap : budget.toolResultCapChars();
    return Math.max(MIN_TOOL_RESULT_CHARS, cap);
  }

  /** Resolves an agent config int, or 0 when no ConfigStore is installed (tests, early boot). */
  private static int resolveInt(ToIntFunction<ResolvedConfig> extractor) {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? extractor.applyAsInt(cs.get()) : 0;
  }
}
