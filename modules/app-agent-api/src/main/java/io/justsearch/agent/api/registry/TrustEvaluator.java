/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Pure-function evaluator for the {@code (SourceTier × RiskTier) → GateBehavior}
 * trust lattice (tempdoc 487 §4.4).
 *
 * <p>Composes the source-side {@link SourceTier} (intrinsic trust of the intent
 * source — palette click, URL bar, LLM emission, etc.) with the operation-side
 * {@link RiskTier} (the destination operation's declared risk) to produce a
 * {@link GateBehavior}.
 *
 * <p>Insertion point: {@code OperationExecutorImpl.dispatch} runs the evaluator
 * <em>between</em> {@code validateProvenance(op, provenance)} (transport-tier
 * spoofing defense) and {@code inputValidator.validate(op, argumentsJson)} (schema
 * validation). The lattice fires for every dispatch, regardless of caller (HTTP
 * endpoint, agent loop, plugin emitter, scheduled trigger, MCP) — this is the
 * platform's first dispatcher-level enforcement of gate behavior.
 *
 * <p>Convergent industry framing (Appendix B.8–B.10): MCP elicitation supplies the
 * mechanism for {@code INLINE_CONFIRM}/{@code TYPED_CONFIRM}; Microsoft Agent
 * Governance Toolkit's policy-conditions-on-action-id pattern parallels this
 * source×risk axis pair; OWASP's "intent capsule + per-step re-validation" makes
 * every dispatch a fresh trust evaluation rather than trusting prior consent.
 */
public interface TrustEvaluator {

  /**
   * Compute the gate behavior for the given (source tier, risk tier) pair.
   *
   * <p>Pure function. Deterministic. Safe to call from any thread.
   *
   * @param sourceTier the intent-source's intrinsic trust level (derived from the
   *     transport via the {@link IntentSourceCatalog})
   * @param riskTier the destination operation's declared risk (from
   *     {@code op.policy().risk()})
   * @return the gate behavior the dispatcher should enforce
   */
  GateBehavior evaluate(SourceTier sourceTier, RiskTier riskTier);
}
