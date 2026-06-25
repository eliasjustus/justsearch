/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.justsearch.agent.api.registry.OperationInvocation;
import io.justsearch.app.observability.metrics.MetricRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Asserted-state body — k8s {@code metav1.Condition}-shaped.
 *
 * <p>Per tempdoc 430 §A.1: used for events like {@code index.unavailable},
 * {@code embedding.blocked}, {@code schema.blocked}. The condition has a status
 * (TRUE/FALSE/UNKNOWN), a PascalCase reason code (machine-readable), and a
 * {@code lastTransitionTime} that updates only on status change per k8s
 * {@code SetStatusCondition} semantics.
 *
 * <p>{@code reason} matches k8s upstream regex
 * {@code ^[A-Za-z]([A-Za-z0-9_,:]*[A-Za-z0-9_])?$} (per
 * {@code apimachinery/pkg/apis/meta/v1/types.go}). Examples: {@code WorkerStarting},
 * {@code WorkerCrashed}, {@code IndexCorrupted}, {@code ModelMismatch}.
 *
 * <p>{@code subject} identifies the resource the condition is asserted on
 * (e.g., {@code "worker"}, {@code "worker.queue-db"}, {@code "inference.embedding"}).
 * Multi-source attribution discipline per k8s API conventions: one subject per
 * condition; aggregate views derive from per-subject conditions.
 */
@JsonIgnoreProperties("kind")
public record AssertedCondition(
    String subject,
    ConditionStatus status,
    String reason,
    Instant lastTransitionTime,
    Optional<String> message,
    Optional<OperationInvocation> recovery,
    List<MetricRef> relatedMetrics)
    implements HealthEventBody {

  /** k8s upstream reason regex (per {@code metav1.Condition} validation). */
  static final Pattern REASON_PATTERN =
      Pattern.compile("^[A-Za-z]([A-Za-z0-9_,:]*[A-Za-z0-9_])?$");

  public AssertedCondition {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(lastTransitionTime, "lastTransitionTime");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(recovery, "recovery");
    Objects.requireNonNull(relatedMetrics, "relatedMetrics");
    relatedMetrics = List.copyOf(relatedMetrics);
    if (subject.isBlank()) {
      throw new IllegalArgumentException("subject must be non-blank");
    }
    if (!REASON_PATTERN.matcher(reason).matches()) {
      throw new IllegalArgumentException(
          "reason must match k8s PascalCase regex "
              + REASON_PATTERN.pattern()
              + ": "
              + reason);
    }
  }
}
