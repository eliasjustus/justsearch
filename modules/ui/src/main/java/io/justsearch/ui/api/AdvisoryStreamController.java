/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassId;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.app.observability.advisory.AdvisoryRecord;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

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

  /**
   * Filters the ring-buffer snapshot before it is replayed to a new subscriber — see {@link
   * #snapshotExtras()}. Defaults to accept-all: {@link AdvisoryLog} is a pure append-only log
   * of past projection events with no notion of "still live", so most advisory classes (e.g.
   * {@code operation.completed}, {@code health.recoverable}) have nothing to filter against —
   * a completed operation or a health recovery doesn't stop being true. Only a class whose
   * projected fact can be superseded by a later action (e.g. {@code authorization.pending} —
   * a pending gets approved/expires) needs a non-trivial filter, supplied by the caller.
   */
  private final Predicate<AdvisoryRecord> liveFilter;

  public AdvisoryStreamController(
      AdvisoryClassId classId,
      AdvisoryLog log,
      AdvisoryChangeRegistry changes,
      Telemetry telemetry) {
    this(classId, log, changes, telemetry, Clock.systemUTC(), record -> true);
  }

  public AdvisoryStreamController(
      AdvisoryClassId classId,
      AdvisoryLog log,
      AdvisoryChangeRegistry changes,
      Telemetry telemetry,
      Clock clock) {
    this(classId, log, changes, telemetry, clock, record -> true);
  }

  /**
   * Tempdoc-driven fix: a consumed/expired advisory must not be replayed to a new subscriber.
   * {@code liveFilter} is applied to every record in {@link #snapshotExtras()}'s ring-buffer
   * snapshot; a record for which it returns {@code false} is omitted.
   */
  public AdvisoryStreamController(
      AdvisoryClassId classId,
      AdvisoryLog log,
      AdvisoryChangeRegistry changes,
      Telemetry telemetry,
      Predicate<AdvisoryRecord> liveFilter) {
    this(classId, log, changes, telemetry, Clock.systemUTC(), liveFilter);
  }

  public AdvisoryStreamController(
      AdvisoryClassId classId,
      AdvisoryLog log,
      AdvisoryChangeRegistry changes,
      Telemetry telemetry,
      Clock clock,
      Predicate<AdvisoryRecord> liveFilter) {
    this.classId = Objects.requireNonNull(classId, "classId");
    this.log = Objects.requireNonNull(log, "log");
    this.changes = Objects.requireNonNull(changes, "changes");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.liveFilter = Objects.requireNonNull(liveFilter, "liveFilter");
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
        sseClient, channel(), this::snapshotExtras, clock, heartbeatScheduler, HEARTBEAT_SECONDS);
  }

  /**
   * This controller's channel. Package-visible (tempdoc 662) so {@link
   * ShellEventsStreamController} can subscribe this class's channel onto the multiplexed
   * connection without re-deriving the {@code classId} lookup.
   */
  io.justsearch.app.observability.stream.SseStreamChannel channel() {
    return changes.channel(classId);
  }

  /**
   * This stream's snapshot-on-subscribe payload. Package-visible (tempdoc 662) so {@link
   * ShellEventsStreamController} can reuse the exact extraction logic instead of forking it.
   */
  Map<String, Object> snapshotExtras() {
    Map<String, Object> extras = new LinkedHashMap<>();
    extras.put("advisories", log.recent().stream().filter(liveFilter).toList());
    return extras;
  }

  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
