/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Per-consumer strategy that decides what to do with a {@link ResolutionResult}
 * (tempdoc 499 §3.3).
 *
 * <p>The policy is a property of the lookup context (agent loop, MCP handler,
 * interactive FE, test harness), not of the catalog entry. The same
 * {@link ResolutionResult.Unresolved} for the same operation ID should produce
 * different {@link RecoveryAction} variants depending on who is asking.
 *
 * @param <T> the catalog entry type
 */
@FunctionalInterface
public interface ResolutionRecoveryPolicy<T> {

  RecoveryAction<T> decide(ResolutionResult<T> result);

  /**
   * Strict policy: Resolved → Proceed, everything else → Abort. For validation
   * and test contexts where fuzzy recovery is inappropriate.
   */
  static <T> ResolutionRecoveryPolicy<T> strict() {
    return result -> switch (result) {
      case ResolutionResult.Resolved<T> r -> new RecoveryAction.Proceed<>(r.entry());
      case ResolutionResult.Redirected<T> r -> new RecoveryAction.Abort<>(
          r.originalId(), "Strict policy: redirects not allowed");
      case ResolutionResult.Unresolved<T> r -> new RecoveryAction.Abort<>(
          r.attemptedId(), r.diagnosis().detail());
    };
  }

  /**
   * MCP policy: Resolved/Redirected → Proceed, Unresolved with alternatives →
   * SuggestToUser, Unresolved without → Abort.
   */
  static <T> ResolutionRecoveryPolicy<T> mcp() {
    return result -> switch (result) {
      case ResolutionResult.Resolved<T> r -> new RecoveryAction.Proceed<>(r.entry());
      case ResolutionResult.Redirected<T> r -> new RecoveryAction.Proceed<>(r.entry());
      case ResolutionResult.Unresolved<T> r -> r.alternatives().isEmpty()
          ? new RecoveryAction.Abort<>(r.attemptedId(), r.diagnosis().detail())
          : new RecoveryAction.SuggestToUser<>(r.attemptedId(), r.alternatives());
    };
  }

  /**
   * Agent tool policy: Resolved/Redirected → Proceed, Unresolved with
   * alternatives → InjectHint (let the model self-correct), Unresolved
   * without → Abort.
   */
  static <T> ResolutionRecoveryPolicy<T> agentTool() {
    return result -> switch (result) {
      case ResolutionResult.Resolved<T> r -> new RecoveryAction.Proceed<>(r.entry());
      case ResolutionResult.Redirected<T> r -> new RecoveryAction.Proceed<>(r.entry());
      case ResolutionResult.Unresolved<T> r -> {
        if (r.alternatives().isEmpty()) {
          yield new RecoveryAction.Abort<>(r.attemptedId(), r.diagnosis().detail());
        }
        String topSuggestions = r.alternatives().stream()
            .limit(3)
            .map(s -> "'" + s.refId() + "'")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
        String hint = "Unknown tool '" + r.attemptedId()
            + "'. Did you mean: " + topSuggestions + "?";
        yield new RecoveryAction.InjectHint<>(r.attemptedId(), hint, r.alternatives());
      }
    };
  }

  /**
   * SSE stream policy: same decisions as {@link #mcp()} but named for the
   * chat-stream SSE context (URLExtractor). Resolved/Redirected → Proceed,
   * Unresolved with alternatives → SuggestToUser, Unresolved without → Abort.
   */
  static <T> ResolutionRecoveryPolicy<T> sseStream() {
    return mcp();
  }
}
