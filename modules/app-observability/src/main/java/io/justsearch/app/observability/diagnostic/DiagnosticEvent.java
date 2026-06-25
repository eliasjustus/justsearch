/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import io.justsearch.agent.api.registry.DataClass;
import io.justsearch.agent.api.registry.SubCategory;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Wire-payload record for a single emission on a {@link
 * io.justsearch.agent.api.registry.DiagnosticChannel}.
 *
 * <p>Per slice 448 phase 3 D1: each emission carries the typed log-event fields plus the
 * resolved {@link SubCategory} and a {@link Set} of {@link DataClass} values. The set is
 * the per-event privacy classification — V1 uses the channel's default set verbatim;
 * future emitter sites may extend the set with event-specific classes (e.g., an
 * exception-body emission tagging itself with {@link DataClass#EXCEPTION_BODIES}). The
 * subscriber takes the union of channel-default and per-event sets when applying
 * redaction policy.
 *
 * <p>{@link #mdc} captures the logger MDC at log-call time on the originating thread (per
 * slice 448 §B.A.G1 fix — the appender invokes {@code event.getMDCPropertyMap()} before
 * any thread hop). Carrying MDC inline ensures consumers can correlate via {@code
 * trace_id} / {@code span_id} without thread-context propagation.
 */
public record DiagnosticEvent(
    String level,
    String message,
    String loggerName,
    String threadName,
    long threadId,
    Instant timestamp,
    Map<String, String> mdc,
    Set<DataClass> dataClasses,
    SubCategory subCategory) {

  public DiagnosticEvent {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(loggerName, "loggerName");
    Objects.requireNonNull(threadName, "threadName");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(subCategory, "subCategory");
    mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    dataClasses = dataClasses == null ? Set.of() : Set.copyOf(dataClasses);
  }
}
