/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Intrinsic trust level of an {@link IntentSource}.
 *
 * <p>Per tempdoc 487 §4.4: the source-side input to the
 * {@code (SourceTier × RiskTier) → GateBehavior} trust lattice in
 * {@code OperationExecutorImpl}. Composed with {@link RiskTier} (the
 * operation-side input) to decide whether dispatch is auto-allowed, requires
 * inline confirmation, requires typed confirmation, or is denied.
 *
 * <p>This is a SEPARATE axis from declaration-side {@link TrustTier}. A
 * {@code TrustTier.CORE} operation can be reached by either a
 * {@code SourceTier.TRUSTED} intent (user click) or a
 * {@code SourceTier.UNTRUSTED} intent (LLM emission); both are admitted
 * (declaration trust says "yes this op exists"), and the trust lattice
 * decides what gate behavior runs.
 *
 * <p>Convergent with the 2026 AI-agent industry pattern: MCP elicitation,
 * Microsoft Agent Governance Toolkit's identity layer, OWASP Top 10 for
 * Agentic Applications 2026's "treat the LLM as an untrusted client." See
 * tempdoc 487 Appendix B.8–B.10.
 */
public enum SourceTier {

  /**
   * Direct, unambiguous user intent: rail click, palette selection,
   * keyboard shortcut, button click. Trusted because the user explicitly
   * caused the intent through a direct UI gesture.
   */
  TRUSTED,

  /**
   * User-mediated but indirect: URL bar paste, clipboard paste, browser
   * history restoration, OS-level deep-link from a trusted external app.
   * The user *chose* the action but a layer of indirection (URL string,
   * clipboard, deep-link protocol) sits between the gesture and the
   * resulting intent.
   */
  MEDIUM,

  /**
   * LLM emission, plugin emission, external MCP client, scheduled trigger,
   * rule-engine output, OS-level deep-link from an untrusted external
   * app. The intent originates from a source whose payload the platform
   * cannot fully validate as user-aligned.
   */
  UNTRUSTED
}
