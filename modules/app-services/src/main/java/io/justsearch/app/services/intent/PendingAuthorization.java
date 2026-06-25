/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import java.time.Instant;
import java.util.Objects;

/**
 * A backend-created record of an action that hit a non-AUTO trust gate and is awaiting a
 * human authorization decision — tempdoc 550 Slice C3 (the unified Authorize ceremony) +
 * WA-5 (Tier-0 hardening).
 *
 * <p><strong>Why this exists (the security property).</strong> Before C3, the approval
 * endpoint minted a {@link ConsentCapsuleService consent capsule} for an arbitrary
 * {@code (operationId, args)} presented by the caller. That trusted the caller to only
 * ask for what a human approved — which an in-process agent / prompt-injection could
 * abuse to self-approve an action the user never saw. With C3, a capsule can only be
 * minted against a PendingAuthorization that the <em>backend itself</em> created when it
 * gated a dispatch. You cannot approve what was never gated: the agent cannot conjure a
 * pending entry for an un-gated op, so it cannot forge an approval. The approval gesture
 * is reduced to "consume pending {@code id}", and the capsule binds to the
 * <em>stored</em> {@code (operationId, argsJson)} — not to anything the approve caller
 * supplies.
 *
 * <p>This is the one record the six scattered gate paths (the HTTP 428 path, the
 * agent-loop {@code CompletableFuture} gate, {@code PendingEffect}, capability consent,
 * the 499 resolution, and gated Navigation per WA-4) converge on as producers.
 *
 * @param id opaque server-assigned id ({@code pa-<uuid>}); the only thing the approve
 *     gesture references.
 * @param operationId the gated operation (or, for a gated Navigation, the surface target).
 * @param argsJson the exact serialized args the eventual capsule binds to — captured at
 *     gate time so the approve caller cannot substitute different args.
 * @param sourceTier the source tier the gate evaluated (audit / trust-aware copy).
 * @param riskTier the operation's risk tier.
 * @param gateBehavior the computed gate (INLINE_CONFIRM / TYPED_CONFIRM).
 * @param rationale human-readable why-gated seed (e.g. the dispatcher's message).
 * @param createdAt when the gate fired.
 * @param expiresAt when this pending becomes unusable (stale approvals are refused).
 */
public record PendingAuthorization(
    String id,
    String operationId,
    String argsJson,
    SourceTier sourceTier,
    RiskTier riskTier,
    GateBehavior gateBehavior,
    String rationale,
    Instant createdAt,
    Instant expiresAt) {

  public PendingAuthorization {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(argsJson, "argsJson");
    Objects.requireNonNull(sourceTier, "sourceTier");
    Objects.requireNonNull(riskTier, "riskTier");
    Objects.requireNonNull(gateBehavior, "gateBehavior");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    rationale = rationale == null ? "" : rationale;
  }

  /** True when {@code now} is at or past {@link #expiresAt}. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
