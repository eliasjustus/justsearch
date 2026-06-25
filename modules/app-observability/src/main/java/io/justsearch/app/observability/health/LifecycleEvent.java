/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One-shot occurrence body — fire-and-forget events with no asserted-state lifecycle.
 *
 * <p>Per tempdoc 430 §A.1: used for events like {@code agent.session.completed},
 * {@code worker.job.failed}, {@code worker.job.retry-scheduled}. No "resolved"
 * counterpart; appended to the {@link OccurrenceLog} ring buffer rather than tracked in
 * {@link ConditionStore}.
 *
 * <p>{@code attributes} carries arbitrary key/value metadata per CloudEvents 1.0
 * extension-attribute discipline (e.g., {@code "session_id"}, {@code "duration_ms"},
 * {@code "path"}, {@code "error_class"}). Defensively copied; null becomes empty.
 */
@JsonIgnoreProperties("kind")
public record LifecycleEvent(
    Map<String, Object> attributes, Optional<OperationInvocation> recovery)
    implements HealthEventBody {

  public LifecycleEvent {
    Objects.requireNonNull(recovery, "recovery");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** Convenience factory for an empty-attribute lifecycle event. */
  public static LifecycleEvent empty() {
    return new LifecycleEvent(Map.of(), Optional.empty());
  }

  /** Convenience factory for a single-key attribute payload. */
  public static LifecycleEvent of(String key, Object value) {
    Objects.requireNonNull(key, "key");
    return new LifecycleEvent(Map.of(key, value), Optional.empty());
  }
}
