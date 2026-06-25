/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Tempdoc 561 P-D1 — the agent loop's read-only window onto the ONE intent-gate authority.
 *
 * <p>The agent surface used to re-derive its gating explanation FE-side ({@code becauseLine} /
 * {@code agentToolAutoApprove}) from {@code risk} alone, because the tool-call wire carried no
 * reason — the exact second-authority 550 Thesis V says should be the backend issuance policy. This
 * SAM lets the agent loop, at the moment it emits {@code ToolCallPendingApproval}, ask the backend
 * authority ({@code IntentGateEvaluator.evaluate(risk, AGENT_LOOP)}) for the structural
 * {@link GateBehavior} verdict — so the FE renders the backend's actual decision, not a parallel
 * re-derivation that can drift (548's defect class).
 *
 * <p>Implemented in {@code app-services} (where {@code IntentGateEvaluator} lives) and late-bound
 * into the agent loop like {@code BackendIntentRouter} (see {@code AgentLoopWiring}); a null
 * previewer (legacy / test wiring) leaves the wire {@code gateBehavior} absent and the FE falls back
 * to its dial-derived explanation. The transport is fixed to {@code AGENT_LOOP} by the
 * implementation — the agent loop is, by construction, an {@code UNTRUSTED} source.
 */
@FunctionalInterface
public interface IntentPreviewer {

  /**
   * The {@link GateBehavior} issuance verdict the backend applies to an {@code AGENT_LOOP} dispatch
   * of the given {@code risk} under the user's {@code autonomyLevel} (hard-stop already folded in;
   * HIGH never auto-fires). This is the ONE issuance policy (561 P-D) — the FE obeys this verdict
   * instead of re-deriving from {@code risk}. Pure: consults no args/token.
   *
   * <p>Tempdoc 561 §19/C-4: {@code reversible} (the op declares undo or a backend inverse) refines
   * the {@code AUTO} dial — an irreversible MEDIUM write confirms even under {@code AUTO}. Callers
   * pass {@code false} when the signal is unknown (the safe side).
   */
  GateBehavior previewAgentGate(RiskTier risk, AutonomyLevel autonomyLevel, boolean reversible);
}
