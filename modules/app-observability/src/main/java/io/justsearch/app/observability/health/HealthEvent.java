/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Wire-format record for a single health event.
 *
 * <p>Per tempdoc 430 §A.1: the canonical envelope carrying one of three structural body
 * variants (lifecycle / condition / threshold). The {@code id} is the stable
 * machine-readable identifier (e.g., {@code "index.unavailable"},
 * {@code "agent.session.completed"}); the {@code body} carries the structural payload;
 * {@code source} carries OTel-aligned provenance (per §B.I); {@code severity} is a
 * per-occurrence wire field (per §B.A); {@code i18nKey} optionally points into the
 * display catalog at {@code /api/messages/health-events/{locale}}.
 *
 * <p>HealthEvent does NOT implement {@code RegistryEntry} — registry primitives are
 * catalog envelopes (Operation/Resource/Prompt); HealthEvent is body data carried within
 * the {@code core.health-events} Resource entry's stream (per §B.G).
 *
 * <p>Wire dedup tuples (per §"Pattern reference"):
 *
 * <ul>
 *   <li>For Conditions: {@code (source.serviceInstanceId, id, lastTransitionTime)} — k8s
 *       {@code SetStatusCondition} convention.
 *   <li>For Occurrences: {@code (source, id, idempotencyKey)} — CloudEvents 1.0
 *       convention (idempotencyKey set by emitters that need it; absent by default).
 * </ul>
 */
public record HealthEvent(
    String id,
    Instant timestamp,
    Source source,
    Severity severity,
    Optional<String> i18nKey,
    HealthEventBody body) {

  public HealthEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(i18nKey, "i18nKey");
    Objects.requireNonNull(body, "body");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must be non-blank");
    }
  }
}
