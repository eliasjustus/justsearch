/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassId;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Generic SSE controller for any advisory class. Per slice 494: replaces the old
 * per-class {@code OperationCompletedAdvisoryStreamController} with a class-agnostic
 * controller parameterised by {@link AdvisoryClassId}.
 *
 * <p>Each advisory class gets its own endpoint (Q1=b per-class Resources); the
 * controller reads from the central {@link AdvisoryChangeRegistry}'s per-class
 * channel and the per-class {@link AdvisoryLog} for snapshot-on-subscribe.
 */
public final class AdvisoryStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final AdvisoryClassId classId;
  private final AdvisoryLog log;
  private final AdvisoryChangeRegistry changes;

  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public AdvisoryStreamController(
      AdvisoryClassId classId,
      AdvisoryLog log,
      AdvisoryChangeRegistry changes,
      Telemetry telemetry) {
    this(classId, log, changes, telemetry, Clock.systemUTC());
  }

  public AdvisoryStreamController(
      AdvisoryClassId classId,
      AdvisoryLog log,
      AdvisoryChangeRegistry changes,
      Telemetry telemetry,
      Clock clock) {
    this.classId = Objects.requireNonNull(classId, "classId");
    this.log = Objects.requireNonNull(log, "log");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t =
                  new Thread(r, "advisory-" + classId.value() + "-stream-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(classId),
        () -> {
          Map<String, Object> extras = new LinkedHashMap<>();
          extras.put("advisories", log.recent());
          return extras;
        },
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
