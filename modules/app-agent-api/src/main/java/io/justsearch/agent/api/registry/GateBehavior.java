/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Output of the {@code (SourceTier × RiskTier) → GateBehavior} trust lattice
 * (tempdoc 487 §4.4).
 *
 * <p>The lattice composes the {@link SourceTier} of an intent's source (where the
 * intent originated — palette click, URL bar, LLM emission, etc.) with the
 * {@link RiskTier} of the destination operation to decide whether the dispatch
 * should auto-fire, surface an inline confirmation, surface a typed confirmation, or
 * be denied outright.
 *
 * <p>Distinct from {@link ConfirmStrategy} (the operation's declared confirmation
 * <em>mechanism</em> — None / Inline / Typed). The lattice is the policy that decides
 * <em>which</em> mechanism (if any) runs for a given source × operation combination;
 * the {@code ConfirmStrategy} is the rendering hint when one runs.
 *
 * <p>Convergent with the 2026 industry pattern (Appendix B.8–B.10 in tempdoc 487):
 * the MCP "elicitation" primitive corresponds to {@link #INLINE_CONFIRM} /
 * {@link #TYPED_CONFIRM}; Microsoft Agent Governance Toolkit's hard ALLOW/DENY
 * corresponds to {@link #AUTO} / {@link #DENY}; OWASP's
 * "treat the LLM as an untrusted client" pushes {@link SourceTier#UNTRUSTED} sources
 * away from {@link #AUTO} for any non-LOW risk.
 */
public enum GateBehavior {

  /**
   * Dispatch immediately. No user-facing confirmation surfaces. The combination of
   * source trust and operation risk is low enough that automatic execution is safe.
   */
  AUTO,

  /**
   * Surface a lightweight inline confirmation (e.g., a hover-state acknowledgement)
   * before dispatch. The destination operation's {@link ConfirmStrategy} renders the
   * mechanism; the lattice having selected {@code INLINE_CONFIRM} means "this is
   * required regardless of what the operation declared."
   */
  INLINE_CONFIRM,

  /**
   * Surface a typed confirmation (e.g., "type the operation name to confirm") before
   * dispatch. Used for destructive operations and for any MEDIUM-or-higher risk
   * dispatched from an UNTRUSTED source. The dispatcher emits trust-aware copy
   * (e.g., "An LLM is requesting...") when the source is UNTRUSTED.
   */
  TYPED_CONFIRM,

  /**
   * Reject the dispatch outright; no user prompt. Reserved for combinations the
   * platform's policy chooses never to elicit consent for. Today's lattice values
   * do not produce {@code DENY} from any (source × risk) pair — the enum value is
   * reserved for future plugin-emitted UNTRUSTED ops on HIGH-risk operations the
   * platform wishes to disallow categorically.
   */
  DENY
}
