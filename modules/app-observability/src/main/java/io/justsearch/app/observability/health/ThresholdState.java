/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.justsearch.agent.api.registry.OperationInvocation;
import io.justsearch.app.observability.metrics.MetricRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Threshold-state body — Prometheus-shaped magnitude + dwell-time event.
 *
 * <p>Per tempdoc 430 §A.1: used for events like {@code worker.throughput.stalled},
 * {@code gpu.saturated}, {@code memory.pressure}. Carries the current measurement
 * magnitudes plus the {@link ThresholdPhase} (PENDING / FIRING / RESOLVED) computed
 * by the dwell-time scheduler.
 *
 * <p>{@code magnitudes} is a typed map of measurement name to numeric value
 * (e.g., {@code {"utilization_pct": 87, "window_seconds": 180}}). The FE renders these
 * inline with the i18n message via ICU MessageFormat substitution. Defensively copied;
 * null becomes empty.
 *
 * <p>{@code lastTransitionTime} updates on each phase change (PENDING → FIRING →
 * RESOLVED), mirroring {@link AssertedCondition#lastTransitionTime()}.
 */
@JsonIgnoreProperties("kind")
public record ThresholdState(
    String subject,
    ThresholdPhase phase,
    Map<String, Number> magnitudes,
    Instant lastTransitionTime,
    Optional<String> message,
    Optional<OperationInvocation> recovery,
    List<MetricRef> relatedMetrics)
    implements HealthEventBody {

  public ThresholdState {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(lastTransitionTime, "lastTransitionTime");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(recovery, "recovery");
    Objects.requireNonNull(relatedMetrics, "relatedMetrics");
    magnitudes = magnitudes == null ? Map.of() : Map.copyOf(magnitudes);
    relatedMetrics = List.copyOf(relatedMetrics);
    if (subject.isBlank()) {
      throw new IllegalArgumentException("subject must be non-blank");
    }
  }
}
