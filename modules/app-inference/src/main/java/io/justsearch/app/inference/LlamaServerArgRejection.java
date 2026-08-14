/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Detects llama-server rejecting a launch argument — the authoritative capability signal for flags
 * whose support varies by build (tempdoc 835 §5.2 signal 1).
 *
 * <p>Match the argument-name <em>prefix</em>, never the parser's suffix: b8185 emitted {@code
 * invalid value} and b8571 emits {@code invalid stoi argument} for the same rejection, and a
 * suffix-matching detector in a CI script silently stopped firing when the bundled build moved. The
 * marker is declared once in {@code governance/llama-server-arg-rejection.v1.json}; this constant is
 * pinned to that register by {@code LlamaServerArgRejectionContractTest}, and the CI script reads
 * the register directly, so no third copy exists.
 */
final class LlamaServerArgRejection {

  /** Declared rejection marker for {@code --reasoning-budget}. */
  static final String REASONING_BUDGET_MARKER = "error while handling argument \"--reasoning-budget\"";

  private LlamaServerArgRejection() {}

  /** True when llama-server output shows this build refusing the {@code --reasoning-budget} flag. */
  static boolean isReasoningBudgetRejection(String serverOutput) {
    return serverOutput != null && serverOutput.contains(REASONING_BUDGET_MARKER);
  }
}
