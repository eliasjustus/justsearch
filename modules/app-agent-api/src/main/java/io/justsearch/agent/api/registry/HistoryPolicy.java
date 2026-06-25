/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Retention semantics for a {@link Resource} entry whose {@link Category} implies retained
 * history ({@link Category#EVENT_STREAM}, {@link Category#HISTORY}).
 *
 * <p>Per slice 444a §B.4 + §B.A.2/§B.A.3/§B.A.4 +
 * {@code 20-systems/01-resources.md} §"HistoryPolicy".
 *
 * <p>For Resources whose body is hybrid (state-portion + history-portion, like the HealthEvent
 * Resource entry), this policy describes only the history-shaped portion. State-shaped portions
 * are body-internal in the snapshot and don't carry separate retention.
 *
 * <p>Compact-constructor invariants:
 *
 * <ul>
 *   <li>{@link Mode#RING_BUFFER} requires {@code capacity} to be present.
 *   <li>{@link Mode#DURABLE} requires at least one of {@code capacity} / {@code retention}.
 *   <li>{@link Mode#EXTERNAL} forbids {@code onOverflow == }{@link OnOverflow#BACKPRESSURE} —
 *       backpressure on an external system the Resource doesn't manage is semantically
 *       nonsensical.
 * </ul>
 *
 * <p>The {@code resumeWindow} composes with slice 1.8's streaming envelope (tempdoc 436): the
 * resume contract is {@code min(client_token_age, history.resumeWindow)}. For
 * {@link Mode#EXTERNAL} the value is informational only (effective retention is opaque to the
 * Resource).
 */
public record HistoryPolicy(
    Mode mode,
    Optional<Integer> capacity,
    Optional<Duration> retention,
    OnOverflow onOverflow,
    Duration resumeWindow)
    implements PreciseWire {

  /** Where retained history physically lives. */
  public enum Mode {
    /** In-memory bounded buffer (e.g., {@code OccurrenceLog}'s ArrayDeque). Capacity required. */
    RING_BUFFER,

    /** Persistent store (SQLite, Postgres, etc.). At least one of capacity/retention required. */
    DURABLE,

    /**
     * Defers to the underlying source's retention (Logback rolling policy, syslog rotation).
     * The Resource doesn't manage retention itself; declared values are informational.
     */
    EXTERNAL
  }

  public HistoryPolicy {
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(capacity, "capacity");
    Objects.requireNonNull(retention, "retention");
    Objects.requireNonNull(onOverflow, "onOverflow");
    Objects.requireNonNull(resumeWindow, "resumeWindow");

    switch (mode) {
      case RING_BUFFER -> {
        if (capacity.isEmpty()) {
          throw new IllegalArgumentException("RING_BUFFER mode requires capacity");
        }
      }
      case DURABLE -> {
        if (capacity.isEmpty() && retention.isEmpty()) {
          throw new IllegalArgumentException(
              "DURABLE mode requires at least one of capacity / retention");
        }
      }
      case EXTERNAL -> {
        if (onOverflow == OnOverflow.BACKPRESSURE) {
          throw new IllegalArgumentException(
              "EXTERNAL mode forbids BACKPRESSURE; backpressure is not the Resource's concern");
        }
      }
    }
  }
}
