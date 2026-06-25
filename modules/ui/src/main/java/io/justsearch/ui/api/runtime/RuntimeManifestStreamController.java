/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.runtime;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import io.justsearch.ui.api.SseEnvelopeWriter;
import io.justsearch.ui.api.StreamLivenessWindows;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE controller for the runtime-manifest stream at
 * {@code GET /api/runtime/manifest/stream} (tempdoc 501 §3.5).
 *
 * <p>StreamId: {@code registry:runtime-manifest}. Mirrors the
 * {@link io.justsearch.ui.api.CapabilitiesStreamController} shape but is a separate
 * stream because runtime identity and contract-version metadata have different
 * lifecycles. UPDATE frames forward {@link RuntimeManifestPublisher} listener
 * notifications; the snapshot extras carry the wire-level "I just connected" signal,
 * with the actual current manifest fetched via {@code GET /api/runtime/manifest}.
 *
 * <p>Wire envelope is the canonical universal envelope managed by
 * {@link SseEnvelopeWriter}.
 */
public final class RuntimeManifestStreamController {

  /** Stable StreamId for the runtime-manifest stream. */
  public static final StreamId STREAM_ID = StreamId.registry("runtime-manifest");

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final SseStreamChannel channel;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public RuntimeManifestStreamController(RuntimeManifestPublisher publisher) {
    this(publisher, Clock.systemUTC());
  }

  public RuntimeManifestStreamController(RuntimeManifestPublisher publisher, Clock clock) {
    Objects.requireNonNull(publisher, "publisher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.channel = new SseStreamChannel(STREAM_ID);
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "runtime-manifest-stream-heartbeat");
              t.setDaemon(true);
              return t;
            });
    publisher.addListener(this::onManifestChange);
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        channel,
        () -> Map.of("detail", "initial"),
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }

  /** Test/inspection accessor for the underlying channel. */
  public SseStreamChannel channel() {
    return channel;
  }

  private void onManifestChange(RuntimeManifest manifest) {
    // Tempdoc 501 §13.4.5: SSE is an HTTP-class transport; serve the public
    // projection. Each record owns its redaction policy via publicProjection().
    channel.publish(SseFrameKind.UPDATE, manifest.publicProjection());
  }
}
