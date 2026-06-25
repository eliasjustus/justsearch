/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/health/events/stream}.
 *
 * <p>Per slice 436 retrofit + Fix A consolidation: lifecycle / resume / heartbeat /
 * broadcast forwarding live in {@link SseEnvelopeWriter#attach}; this controller only
 * supplies the snapshot extras (current condition set + recent occurrence buffer).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * snapshot lifecycle carries {@code conditions} + {@code occurrences}; UPDATE frames
 * forward {@link HealthEventChangeRegistry} broadcasts; resume via {@code ?since=token}.
 */
public final class HealthEventStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final ConditionStore conditions;
  private final OccurrenceLog occurrences;
  private final HealthEventChangeRegistry changes;

  // Tempdoc 430 Phase 2: Telemetry kept on the constructor for parity; future SSE-volume
  // metrics will read it.
  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public HealthEventStreamController(
      ConditionStore conditions,
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Telemetry telemetry) {
    this(conditions, occurrences, changes, telemetry, Clock.systemUTC());
  }

  public HealthEventStreamController(
      ConditionStore conditions,
      OccurrenceLog occurrences,
      HealthEventChangeRegistry changes,
      Telemetry telemetry,
      Clock clock) {
    this.conditions = Objects.requireNonNull(conditions, "conditions");
    this.occurrences = Objects.requireNonNull(occurrences, "occurrences");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "health-events-stream-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        () -> {
          Map<String, Object> extras = new LinkedHashMap<>();
          extras.put("conditions", conditions.currentSnapshot());
          extras.put("occurrences", occurrences.recent());
          return extras;
        },
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
