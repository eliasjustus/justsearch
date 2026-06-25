/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.navigation;

import io.justsearch.agent.api.registry.InvocationProvenance;
import java.time.Instant;
import java.util.Objects;

/**
 * Wire-format record for a single Navigation intent that was forwarded over the intent
 * stream — the Navigation sibling to {@link
 * io.justsearch.app.observability.operations.OperationHistoryEntry}.
 *
 * <p>Tempdoc 550 (agent-action lifecycle spine), Slice F1 — the <b>Outcome face</b>'s
 * missing third. Today Operation Invocations leave an {@code OperationHistoryEntry} and
 * pure-FE Effects leave an Effect-Journal entry, but Navigation intents — the action
 * kind that bypasses the trust lattice entirely (see {@code
 * BackendIntentRouterImpl.forwardNavigation}) — leave <i>no</i> attributed record at all.
 * This is the sibling that slice 487 §4.3 named ("tomorrow: extended with a {@code
 * NavigationHistoryEntry} sibling so navigation events are reviewable the same way") but
 * never shipped. With it, every dispatched Navigation becomes an attributed action that
 * the receipt / activity-timeline / audit read-views can surface.
 *
 * <p>This is a deliberately <b>federated</b> record, not a merge into {@code
 * OperationHistoryEntry}: per 550's federated-ledger decision, cross-boundary action
 * records stay in per-kind stores and a unified read-view projects over them. Unifying
 * the projection across Operation + Navigation + Effect is the flagged cross-cutting
 * cutover, not this additive slice.
 *
 * <p>Navigation has no backend success/failure axis — the surface activation happens
 * FE-side after the envelope is broadcast — so the backend outcome is uniformly
 * "forwarded." The record therefore captures attribution (who/what/when), not a
 * success/failure outcome:
 *
 * <ul>
 *   <li>{@code envelopeId} — the server-assigned {@code ie-<uuid>} stamped in {@code
 *       forwardNavigation}; correlates this record with the broadcast {@code
 *       IntentEnvelopeEvent} the FE consumed.
 *   <li>{@code targetSurface} — the destination {@code SurfaceRef} value.
 *   <li>{@code sourceId} — the {@code IntentSourceRef} value resolved from the
 *       transport ({@code core.unregistered-transport} when the ingress was not
 *       registered — the Pass-9 commitment-4 sentinel).
 *   <li>{@code occurredAt} — when the navigation was forwarded.
 *   <li>{@code provenance} — the full typed {@link InvocationProvenance} (transport /
 *       executor / initiator / occurredAt); the {@code transport} axis is the source
 *       category the trust audit reads.
 * </ul>
 */
public record NavigationHistoryEntry(
    String envelopeId,
    String targetSurface,
    String sourceId,
    Instant occurredAt,
    InvocationProvenance provenance) {

  public NavigationHistoryEntry {
    Objects.requireNonNull(envelopeId, "envelopeId");
    Objects.requireNonNull(targetSurface, "targetSurface");
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(provenance, "provenance");
    if (envelopeId.isBlank()) {
      throw new IllegalArgumentException("envelopeId must be non-blank");
    }
    if (targetSurface.isBlank()) {
      throw new IllegalArgumentException("targetSurface must be non-blank");
    }
    if (sourceId.isBlank()) {
      throw new IllegalArgumentException("sourceId must be non-blank");
    }
  }
}
