/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/shell-events/stream} — tempdoc 662's cross-channel
 * multiplexer. Aggregates the 5 streams that were previously 5 independent always-on
 * EventSources (which exhausted the browser's ~6-per-host connection pool, starving the cheap
 * {@code /api/status}/{@code /api/inference/status} polls under load — tempdoc 649) onto ONE
 * physical connection via {@link MultiplexedSseWriter}: intent, the two advisory classes
 * (operation-completed, health-recoverable), action-ledger, and indexing-jobs.
 *
 * <p>This controller only ASSEMBLES the 5 {@link MultiplexedSseWriter.ChannelSource}s — it does
 * NOT re-derive any controller's channel-lookup or snapshot-extraction logic. Each source is
 * obtained via the package-visible {@code channel()}/{@code snapshotExtras()} accessors added
 * to the existing single-channel controllers (tempdoc 662 — extend, don't fork): {@link
 * AdvisoryStreamController}, {@link ActionLedgerController}, {@link IndexingJobsStreamController}.
 * Intent has no analogous controller-side reuse beyond {@link IntentEnvelopeChangeRegistry
 * #channel()} (it is event-only — see {@link SseEnvelopeWriter#attachEventOnly}'s contract,
 * mirrored here via a {@code null} snapshot supplier).
 */
public final class ShellEventsStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  /**
   * Dedicated pseudo-channel for this connection's shared heartbeat only — never one of the 5
   * subscribed data channels (a heartbeat is per-client lifecycle data, not real channel state;
   * see {@link MultiplexedSseWriter}'s class doc). Shared across connections: heartbeat frames
   * are never resumed/replayed, so a process-lifetime seq counter is harmless.
   */
  private static final StreamId HEARTBEAT_STREAM_ID = StreamId.system("shell-events-heartbeat");

  private final IntentEnvelopeChangeRegistry intentChanges;
  private final AdvisoryStreamController operationCompletedAdvisory;
  private final AdvisoryStreamController healthRecoverableAdvisory;
  private final ActionLedgerController actionLedger;
  private final IndexingJobsStreamController indexingJobs;
  private final SseStreamChannel heartbeatChannel;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public ShellEventsStreamController(
      IntentEnvelopeChangeRegistry intentChanges,
      AdvisoryStreamController operationCompletedAdvisory,
      AdvisoryStreamController healthRecoverableAdvisory,
      ActionLedgerController actionLedger,
      IndexingJobsStreamController indexingJobs) {
    this(
        intentChanges,
        operationCompletedAdvisory,
        healthRecoverableAdvisory,
        actionLedger,
        indexingJobs,
        Clock.systemUTC());
  }

  public ShellEventsStreamController(
      IntentEnvelopeChangeRegistry intentChanges,
      AdvisoryStreamController operationCompletedAdvisory,
      AdvisoryStreamController healthRecoverableAdvisory,
      ActionLedgerController actionLedger,
      IndexingJobsStreamController indexingJobs,
      Clock clock) {
    this.intentChanges = Objects.requireNonNull(intentChanges, "intentChanges");
    this.operationCompletedAdvisory =
        Objects.requireNonNull(operationCompletedAdvisory, "operationCompletedAdvisory");
    this.healthRecoverableAdvisory =
        Objects.requireNonNull(healthRecoverableAdvisory, "healthRecoverableAdvisory");
    this.actionLedger = Objects.requireNonNull(actionLedger, "actionLedger");
    this.indexingJobs = Objects.requireNonNull(indexingJobs, "indexingJobs");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatChannel = new SseStreamChannel(HEARTBEAT_STREAM_ID);
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "shell-events-stream-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public void handle(SseClient sseClient) {
    List<MultiplexedSseWriter.ChannelSource> sources =
        List.of(
            new MultiplexedSseWriter.ChannelSource(intentChanges.channel(), null),
            new MultiplexedSseWriter.ChannelSource(
                operationCompletedAdvisory.channel(), operationCompletedAdvisory::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(
                healthRecoverableAdvisory.channel(), healthRecoverableAdvisory::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(actionLedger.channel(), actionLedger::snapshotExtras),
            new MultiplexedSseWriter.ChannelSource(indexingJobs.channel(), indexingJobs::snapshotExtras));
    MultiplexedSseWriter.attachAll(
        sseClient, sources, heartbeatChannel, clock, heartbeatScheduler, HEARTBEAT_SECONDS);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
