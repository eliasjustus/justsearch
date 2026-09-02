/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

/**
 * Tempdoc 883 decision 3 — the ONE request-scoped answer to "how many tokens may this turn spend,
 * and on what".
 *
 * <p><b>The defect this closes.</b> Every consumer that needed a slice of the context window carried
 * its own literal, each sized against an assumed 4096- or 8192-token window that the launch ladder
 * (tempdoc 883 PR 1) has since made 32768 on a GPU and 8192 on CPU: the hierarchical threshold
 * (5000), the section target (1800), the conversation-history cap (1000, documented as "~25% of a
 * conservative 8K window"), the agent's read-document page (3000 chars) and its tool-result cut
 * (4000 chars). Two of them were {@code static final} resolved at class-init, so a window change at
 * runtime never reached them at all. The numbers were wrong in both directions — over-committing a
 * 4096-token window and leaving seven eighths of a 32768-token one unused.
 *
 * <p><b>The shape.</b> One immutable record, built ONCE per request from the live window and the
 * completion this turn reserves. Every derived quantity is {@code min(fraction * inputBudget, cap)}:
 * the fraction makes it scale with the window, the cap states the reason it should NOT scale past a
 * point, and each accessor's javadoc names that reason. A budget never grows a value silently past
 * the point where growing it stops being useful.
 *
 * <p><b>Window precedence</b> (unchanged from tempdoc 845, generalized to one place): observed
 * {@code /props} {@code n_ctx} → the configured launch window → {@link #FALLBACK_WINDOW_TOKENS}.
 * "Unknown" is never treated as generous — it falls through to the next most authoritative value,
 * and the last of them is the smallest rung any server this app starts can end up with.
 *
 * @param windowTokens the context window this budget was computed against
 * @param completionReserve the whole completion reservation for this turn (reasoning is spent
 *     INSIDE it, tempdoc 835 — never add a reasoning budget on top)
 * @param inputBudget the tokens available for prompt input, 0 when the reservation leaves no room
 * @param source where {@link #windowTokens} came from
 */
public record ContextBudget(
    int windowTokens, int completionReserve, int inputBudget, Source source) {

  /** Where the window this budget was computed against came from. */
  public enum Source {
    /** A running llama-server reported it through {@code /props}. */
    OBSERVED,
    /** No server has been observed; this is the window a launch was configured with. */
    CONFIGURED,
    /** Neither is known: {@link ContextBudget#FALLBACK_WINDOW_TOKENS}. */
    FALLBACK
  }

  /**
   * Last-resort window when nothing has been observed and nothing is configured — the smallest rung
   * of the launch ladder ({@code ContextWindowPolicy}), i.e. the smallest window any server this app
   * starts can end up with.
   *
   * <p>Deliberately NOT the derived default, which tempdoc 883 PR 1 made 32768 / 8192 by backend:
   * over-committing a budget against a window that may not exist is the failure this constant exists
   * to prevent.
   */
  public static final int FALLBACK_WINDOW_TOKENS = 4096;

  /**
   * The section target's ceiling. A section is one blocking map-step LLM call; past a few thousand
   * tokens the per-section latency, not the window, is what the user feels.
   */
  static final int SECTION_TARGET_CAP_TOKENS = 4096;

  /**
   * The conversation-history ceiling. Prior turns are low value per token next to the material this
   * turn actually retrieved, so history is allowed to grow with the window only up to a point.
   */
  static final int EXTERNAL_CONTEXT_CAP_TOKENS = 2048;

  /**
   * The read-document page ceiling. Agent-context hygiene: a 12k-token page at a 32k window fills
   * the prompt with one document and defeats the compressor that is supposed to keep several tool
   * results in view.
   */
  static final int READ_PAGE_CAP_TOKENS = 4096;

  /** The tool-result ceiling. One tool result must not own the prompt. */
  static final int TOOL_RESULT_CAP_TOKENS = 2048;

  /**
   * The fraction of the WINDOW a caller that lets this budget choose its own completion reserve may
   * reserve. Applied only by {@link #withDerivedReserve}: a caller whose reserve is already fixed
   * (the chat engine sends a real {@code max_tokens}) passes it verbatim, because clamping it here
   * would promise input room the real completion can still eat.
   */
  private static final int DERIVED_RESERVE_DIVISOR = 4;

  public ContextBudget {
    windowTokens = Math.max(0, windowTokens);
    completionReserve = Math.max(0, completionReserve);
    inputBudget = Math.max(0, inputBudget);
    source = source == null ? Source.FALLBACK : source;
  }

  /**
   * The budget for a caller whose completion reserve is already decided — the chat engine's real
   * {@code max_tokens} for this turn.
   *
   * @param observedWindow the window a running server reported, or null when none has been
   * @param configuredWindow the window a launch was configured with, or null when unknown
   * @param completionReserve the whole completion reservation this turn will send
   */
  public static ContextBudget of(
      Integer observedWindow, Integer configuredWindow, int completionReserve) {
    int window = resolveWindow(observedWindow, configuredWindow);
    Source source = resolveSource(observedWindow, configuredWindow);
    int reserve = Math.max(0, completionReserve);
    return new ContextBudget(
        window,
        reserve,
        TokenEstimation.computeSafeInputBudgetTokens(window, reserve),
        source);
  }

  /**
   * The budget for a caller that lets the budget CHOOSE its completion reserve — today only the
   * agent loop, whose per-call cap is a config knob rather than a request field.
   *
   * <p>The reserve is {@code min(preferredReserve, window / 4)}. A reserve is not linear in the
   * window (an answer does not get longer because the window did), so the preferred cap is the
   * normal answer; the window fraction only bites at a window too small to afford it, which is where
   * a flat reserve starves the input instead.
   *
   * @param preferredReserve the reserve to use when the window can afford it; {@code <= 0} means
   *     "derive it entirely", i.e. {@code window / 4}
   */
  public static ContextBudget withDerivedReserve(
      Integer observedWindow, Integer configuredWindow, int preferredReserve) {
    int window = resolveWindow(observedWindow, configuredWindow);
    int windowShare = window / DERIVED_RESERVE_DIVISOR;
    int reserve = preferredReserve > 0 ? Math.min(preferredReserve, windowShare) : windowShare;
    return of(observedWindow, configuredWindow, reserve);
  }

  private static int resolveWindow(Integer observedWindow, Integer configuredWindow) {
    if (observedWindow != null && observedWindow > 0) {
      return observedWindow;
    }
    if (configuredWindow != null && configuredWindow > 0) {
      return configuredWindow;
    }
    return FALLBACK_WINDOW_TOKENS;
  }

  private static Source resolveSource(Integer observedWindow, Integer configuredWindow) {
    if (observedWindow != null && observedWindow > 0) {
      return Source.OBSERVED;
    }
    if (configuredWindow != null && configuredWindow > 0) {
      return Source.CONFIGURED;
    }
    return Source.FALLBACK;
  }

  // ==========================================================================
  // Derived quantities — every one is min(fraction * inputBudget, cap)
  // ==========================================================================

  /**
   * The largest document that may be summarized in a SINGLE pass. Above it the hierarchical
   * map-reduce runs instead.
   *
   * <p>No cap: it is the budget. A document that does not fit the prompt cannot be summarized in one
   * call, whatever a constant says — which is precisely what the old 5000-token literal claimed at a
   * 4096-token window.
   */
  public int hierarchicalThreshold() {
    return inputBudget;
  }

  /**
   * The target size of one map-step section.
   *
   * <p>Half the budget, so two sections plus their instructions fit a single call, capped at
   * {@value #SECTION_TARGET_CAP_TOKENS} because a section is one blocking LLM call and past a few
   * thousand tokens the per-section latency, not the window, is what the user waits on.
   */
  public int sectionTarget() {
    return capped(inputBudget / 2, SECTION_TARGET_CAP_TOKENS);
  }

  /**
   * The tokens of prior conversation turns that may ride along with this one.
   *
   * <p>A quarter of the budget, capped at {@value #EXTERNAL_CONTEXT_CAP_TOKENS}: history is low value
   * per token next to the material this turn retrieved, so it grows with the window only up to a
   * point. (The old literal was 1000, documented as "~25% of a conservative 8K window" — the same
   * fraction, against a window that was not the real one.)
   */
  public int externalContextCap() {
    return capped(inputBudget / 4, EXTERNAL_CONTEXT_CAP_TOKENS);
  }

  /**
   * The tokens one page of a read document may occupy.
   *
   * <p>Half the budget, capped at {@value #READ_PAGE_CAP_TOKENS} for agent-context hygiene: a 12k
   * page at a 32k window fills the prompt with one document and defeats the compressor whose job is
   * to keep several tool results in view.
   */
  public int readDocumentPageTokens() {
    return capped(inputBudget / 2, READ_PAGE_CAP_TOKENS);
  }

  /** {@link #readDocumentPageTokens()} as a character budget. */
  public int readDocumentPageChars() {
    return TokenEstimation.charsForTokens(readDocumentPageTokens());
  }

  /**
   * The tokens one tool result may occupy before it is cut.
   *
   * <p>A quarter of the budget, capped at {@value #TOOL_RESULT_CAP_TOKENS}: one tool result must not
   * own the prompt, because the agent loop's whole value is holding several of them at once.
   */
  public int toolResultCap() {
    return capped(inputBudget / 4, TOOL_RESULT_CAP_TOKENS);
  }

  /** {@link #toolResultCap()} as a character budget. */
  public int toolResultCapChars() {
    return TokenEstimation.charsForTokens(toolResultCap());
  }

  /** {@link #inputBudget()} as a character budget, for consumers whose own cut is in characters. */
  public int inputBudgetChars() {
    return TokenEstimation.charsForTokens(inputBudget);
  }

  /**
   * A fraction of the budget with its ceiling applied, never below 1 token.
   *
   * <p>The floor matters: {@code computeSafeInputBudgetTokens} genuinely returns 0 when the
   * completion reservation leaves no room (tempdoc 845), and a derived cap of 0 would mean "cut
   * everything" at consumers whose contract is "cut to a budget". Zero input budget is reported
   * honestly by {@link #inputBudget()}; the derived caps stay usable.
   */
  private static int capped(int fraction, int cap) {
    return Math.max(1, Math.min(fraction, cap));
  }
}
