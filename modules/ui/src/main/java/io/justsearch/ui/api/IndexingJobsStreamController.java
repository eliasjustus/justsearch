/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.services.worker.RemoteIndexingJobsBridge;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/indexing-jobs/stream}.
 *
 * <p>Slice 445 — backs the {@code core.indexing-jobs} TABULAR Resource. Mirrors
 * {@link HealthEventStreamController}'s shape: {@link SseEnvelopeWriter#attach}
 * handles lifecycle / resume / heartbeat / broadcast forwarding; this
 * controller supplies the snapshot extras (current job collection from the
 * bridge's cache).
 *
 * <p>Wire shape: universal envelope; snapshot lifecycle frame carries
 * {@code items} (current {@code IndexingJobView} list) + {@code snapshotSeq}.
 * UPDATE frames forward {@link IndexingJobsChangeRegistry} broadcasts (Insert,
 * Update, Delete, SnapshotReplaced).
 */
public final class IndexingJobsStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final IndexingJobsChangeRegistry changes;
  private final RemoteIndexingJobsBridge bridge;

  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public IndexingJobsStreamController(
      IndexingJobsChangeRegistry changes,
      RemoteIndexingJobsBridge bridge,
      Telemetry telemetry) {
    this(changes, bridge, telemetry, Clock.systemUTC());
  }

  public IndexingJobsStreamController(
      IndexingJobsChangeRegistry changes,
      RemoteIndexingJobsBridge bridge,
      Telemetry telemetry,
      Clock clock) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.bridge = Objects.requireNonNull(bridge, "bridge");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "indexing-jobs-stream-heartbeat");
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
   * connection.
   */
  io.justsearch.app.observability.stream.SseStreamChannel channel() {
    return changes.channel();
  }

  /**
   * This stream's snapshot-on-subscribe payload. Package-visible (tempdoc 662) so {@link
   * ShellEventsStreamController} can reuse the exact atomic-read logic instead of forking it.
   */
  Map<String, Object> snapshotExtras() {
    // Read the (items, seq) pair as ONE atomic read so a concurrent worker delta can't tear
    // them apart — a torn pair would make the new subscriber subscribe past an unseen delta
    // and go permanently stale (tempdoc 550 §B.2 fix-pass).
    var snap = bridge.latestSnapshotPair();
    Map<String, Object> extras = new LinkedHashMap<>();
    extras.put("items", snap.items());
    extras.put("snapshotSeq", snap.seq());
    return extras;
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
