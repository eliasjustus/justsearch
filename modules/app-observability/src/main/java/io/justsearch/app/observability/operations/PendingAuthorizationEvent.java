/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import java.time.Instant;

/**
 * Wire-shaped announcement that a {@code PendingAuthorization} was just created by a gate firing
 * — tempdoc 655. Lets a caller surface other than the live HTTP request that triggered the gate
 * (concretely: an MCP tool call) notify the always-on frontend shell that a human approval is now
 * waiting, without the frontend having to have made the original request.
 *
 * <p>Fields mirror {@code OperationsController.writeConfirmationRequired}'s 428 response body so
 * the same approval-ceremony UI can render either origin identically. {@code argsSummary} is the
 * same privacy-bounded, truncated summary that body already computes (tempdoc 550 F3) — never the
 * raw arguments.
 */
public record PendingAuthorizationEvent(
    String pendingId,
    String operationId,
    String argsSummary,
    SourceTier sourceTier,
    RiskTier riskTier,
    GateBehavior gateBehavior,
    String rationale,
    Instant createdAt,
    Instant expiresAt) {}
