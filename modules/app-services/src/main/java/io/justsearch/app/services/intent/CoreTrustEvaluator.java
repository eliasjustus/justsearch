/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.agent.api.registry.TrustLattice;
import java.util.List;
import java.util.Objects;

/**
 * V1 lattice implementation per tempdoc 487 §4.4.
 *
 * <p>The matrix below is the platform's first dispatcher-level gate enforcement. Cell
 * values were authored at slice 487 implementation time (2026-05-13) with the design
 * principle that the lattice preserves <em>today's user-facing behavior</em> while
 * introducing a single point of gate enforcement at the dispatcher boundary:
 *
 * <pre>
 * SourceTier ↓ RiskTier →   LOW              MEDIUM           HIGH
 * ──────────────────────────────────────────────────────────────────────────
 * TRUSTED                   AUTO             AUTO             TYPED_CONFIRM
 * MEDIUM                    AUTO             INLINE_CONFIRM   TYPED_CONFIRM
 * UNTRUSTED                 AUTO             TYPED_CONFIRM    TYPED_CONFIRM
 * </pre>
 *
 * <h3>Per-cell rationale</h3>
 *
 * <ul>
 *   <li><b>* × LOW = AUTO.</b> Read-only operations auto-fire regardless of source.
 *       LLM-emitted "open search results" auto-routes; URL-bar-pasted bookmark
 *       restores immediately; rail-click search executes. The platform's existing
 *       behavior matches.
 *   <li><b>TRUSTED × MEDIUM = AUTO.</b> User direct gestures (palette, rail click)
 *       on MEDIUM-risk ops (e.g., "rebuild index") fire without prompt. The user
 *       just clicked the button; the gesture is the confirmation.
 *   <li><b>TRUSTED × HIGH = TYPED_CONFIRM.</b> Destructive ops require typed
 *       confirm even for direct user gestures. This matches today's FE
 *       ActionButton behavior — destructive ops surface a typed-confirm dialog
 *       BEFORE the HTTP POST; the dispatcher-level lattice is a defense-in-depth
 *       check that the FE already gated.
 *   <li><b>MEDIUM × MEDIUM = INLINE_CONFIRM.</b> URL paste / clipboard restore of a
 *       MEDIUM-risk op gets a lightweight inline confirmation. Today's behavior
 *       is no prompt; the lattice tightens this — pasted URLs that invoke write
 *       operations now confirm. This is a deliberate increase in user trust over
 *       today's silent-execute.
 *   <li><b>MEDIUM × HIGH = TYPED_CONFIRM.</b> URL paste / clipboard restore of a
 *       destructive op gets typed confirm. Today's behavior is the same (FE
 *       ActionButton-side typed confirm) — preserved here, plus dispatcher-level
 *       defense-in-depth.
 *   <li><b>UNTRUSTED × MEDIUM = TYPED_CONFIRM.</b> LLM-emitted write op
 *       escalates to typed confirm. Different from TRUSTED×MEDIUM (which is AUTO)
 *       because the model could hallucinate; the user should confirm.
 *   <li><b>UNTRUSTED × HIGH = TYPED_CONFIRM.</b> LLM-emitted destructive op gets
 *       typed confirm with trust-aware copy (the caller's elicitation UX
 *       renders "An LLM is requesting to run {opTitle}..." prefix). Same gate
 *       mechanism as TRUSTED×HIGH but different presentation.
 *   <li><b>No DENY cells in V1.</b> Future plugin-emitted UNTRUSTED ops on HIGH
 *       risk may justify categorical denial; reserved for that direction.
 * </ul>
 *
 * <p><strong>Pass-8 reviewer note:</strong> these cell values are inputs to the
 * substrate-shape Pass-8 review of this slice. Adjustments based on UX evidence
 * (e.g., MEDIUM × MEDIUM = INLINE_CONFIRM proving annoying in production) land
 * via a small follow-up edit to this class; the lattice's shape doesn't change.
 */
public final class CoreTrustEvaluator implements TrustEvaluator {

  /**
   * Tempdoc 560 WS6 — the matrix above, now expressed as a 2-dimension {@link TrustLattice} (the
   * {@code source × risk} convenience over the genuinely N-dimensional core) rather than a hardcoded
   * 3×3 {@code switch}. All nine cells are enumerated, so the lattice's {@link
   * TrustLattice.Builder#defaultOnMissing} ({@code DENY}) never fires for the 2D evaluator — the
   * behavior is byte-identical to the prior switch (asserted by {@code CoreTrustEvaluatorTest}). The
   * value is that a third dimension (e.g. a corpus-sensitivity or workflow-trust axis) is now a longer
   * coordinate through the SAME lattice, not a forked evaluator — see {@code TrustLatticeTest}.
   */
  private static final TrustLattice LATTICE =
      TrustLattice.builder()
          .dimensions("source", "risk")
          .cell(List.of(SourceTier.TRUSTED, RiskTier.LOW), GateBehavior.AUTO)
          .cell(List.of(SourceTier.TRUSTED, RiskTier.MEDIUM), GateBehavior.AUTO)
          .cell(List.of(SourceTier.TRUSTED, RiskTier.HIGH), GateBehavior.TYPED_CONFIRM)
          .cell(List.of(SourceTier.MEDIUM, RiskTier.LOW), GateBehavior.AUTO)
          .cell(List.of(SourceTier.MEDIUM, RiskTier.MEDIUM), GateBehavior.INLINE_CONFIRM)
          .cell(List.of(SourceTier.MEDIUM, RiskTier.HIGH), GateBehavior.TYPED_CONFIRM)
          .cell(List.of(SourceTier.UNTRUSTED, RiskTier.LOW), GateBehavior.AUTO)
          .cell(List.of(SourceTier.UNTRUSTED, RiskTier.MEDIUM), GateBehavior.TYPED_CONFIRM)
          .cell(List.of(SourceTier.UNTRUSTED, RiskTier.HIGH), GateBehavior.TYPED_CONFIRM)
          .defaultOnMissing(GateBehavior.DENY)
          .build();

  @Override
  public GateBehavior evaluate(SourceTier sourceTier, RiskTier riskTier) {
    Objects.requireNonNull(sourceTier, "sourceTier");
    Objects.requireNonNull(riskTier, "riskTier");
    return LATTICE.gate(List.of(sourceTier, riskTier));
  }

  /**
   * The generic lattice backing this 2D convenience — exposed so callers/tests can inspect the table
   * (and so the "a third dimension is the same lookup, not a fork" claim is verifiable against the
   * real core's cells).
   */
  public static TrustLattice lattice() {
    return LATTICE;
  }
}
