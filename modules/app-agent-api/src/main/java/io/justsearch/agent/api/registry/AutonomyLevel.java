/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Tempdoc 561 P-D — the user's agent-autonomy preference (the Watch / Assist / Auto dial), as a
 * first-class backend value.
 *
 * <p>Before 561 P-D the dial lived only on the FE, which re-derived auto-approval from {@code risk}
 * ({@code agentToolAutoApprove}) — the exact second-authority 550 Thesis V says must be the backend
 * issuance policy. This enum lets the dial ride the wire (on {@code AgentRequest} / the mid-run
 * autonomy endpoint) so the ONE backend authority ({@code IntentGateEvaluator}) computes the
 * issuance verdict and the FE simply OBEYS it. The dial expresses how much the user trusts their own
 * agent; the absolute safety floors (HIGH/destructive never auto-fires; the Global Hard Stop; a
 * background run with no watcher) are independent of the dial.
 */
public enum AutonomyLevel {
  /** Review everything before it runs — even read-only tool calls surface an acknowledgement. */
  WATCH,
  /** The safe default: read-only tools auto-run; write/destructive tools require approval. */
  ASSIST,
  /** Trust the agent more: read + write tools auto-run; destructive still requires approval. */
  AUTO;

  /** The default when no dial preference is supplied (legacy/background paths). */
  public static final AutonomyLevel DEFAULT = ASSIST;

  /**
   * Parse a wire value (the FE sends lowercase {@code "watch"|"assist"|"auto"}); unknown/blank/null
   * falls back to {@link #DEFAULT} (never throws — a malformed dial must not break a run).
   */
  public static AutonomyLevel fromWire(String wire) {
    if (wire == null) {
      return DEFAULT;
    }
    return switch (wire.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "watch" -> WATCH;
      case "assist" -> ASSIST;
      case "auto" -> AUTO;
      default -> DEFAULT;
    };
  }

  /** The lowercase wire token (matches the FE {@code AutonomyLevel} string union). */
  public String wire() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
