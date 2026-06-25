/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import java.util.Objects;

/**
 * Wire-explicit discriminator wrapper for {@link DiagnosticEvent}.
 *
 * <p>Per slice 448 phase 3 D1: mirrors the {@code IndexingJobsChangeRegistry.DeltaEnvelope}
 * pattern (slice 3a.1.9 §B.B.B B1) — the {@code kind} field carries an explicit string
 * discriminator that FE consumers dispatch on, rather than relying on payload-shape
 * probing or sealed-interface polymorphism.
 *
 * <p>V1 emits {@code kind = "log-event"} for every diagnostic emission. The discriminator
 * is a forward-compat slot for future diagnostic kinds (span records, audit-log events)
 * when those producers ship — the FE consumer's switch on {@code kind} extends without
 * a breaking wire-shape change.
 */
public record DiagnosticEventEnvelope(String kind, DiagnosticEvent event) {

  /** Wire constant for V1 log emissions. */
  public static final String KIND_LOG_EVENT = "log-event";

  public DiagnosticEventEnvelope {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(event, "event");
    if (kind.isBlank()) {
      throw new IllegalArgumentException("kind must be non-blank");
    }
  }

  /** Convenience factory for the V1 log-event kind. */
  public static DiagnosticEventEnvelope ofLogEvent(DiagnosticEvent event) {
    return new DiagnosticEventEnvelope(KIND_LOG_EVENT, event);
  }
}
