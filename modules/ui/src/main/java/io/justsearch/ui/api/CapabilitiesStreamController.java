/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.WireContractVersion;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.telemetry.Telemetry;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint for capability change events at {@code /infra/capabilities/stream}.
 *
 * <p>Per tempdoc 429 §A.4 + slice 436 retrofit + Fix A consolidation: lifecycle /
 * resume / heartbeat / broadcast forwarding live in {@link SseEnvelopeWriter#attach};
 * this controller only supplies the snapshot extras. The capabilities snapshot extras
 * carry {@code detail: "initial"} (the FE fetches the actual capability snapshot via
 * the REST {@code /infra/capabilities} endpoint; this lifecycle frame is the wire-level
 * "I just connected" signal).
 *
 * <p>Wire shape: universal envelope with constant SSE event name {@code "frame"};
 * UPDATE frames forward {@link CapabilitiesChangeRegistry} broadcasts; resume via
 * {@code ?since=token}.
 */
public final class CapabilitiesStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final CapabilitiesChangeRegistry changes;

  // Telemetry retained on the constructor for parity; future SSE-volume metrics will read it.
  @SuppressWarnings("unused")
  private final Telemetry telemetry;

  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public CapabilitiesStreamController(
      CapabilitiesChangeRegistry changes, Telemetry telemetry) {
    this(changes, telemetry, Clock.systemUTC());
  }

  public CapabilitiesStreamController(
      CapabilitiesChangeRegistry changes, Telemetry telemetry, Clock clock) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.telemetry = telemetry;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "capabilities-stream-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        // Slice 3a-1-8 Phase 5a: opt-in adopter of per-envelope contract version
        // tagging. The snapshot extras are wrapped with `contractVersion` so FE
        // consumers exercising the runtime-continuous capability can detect
        // cross-version reads. Other endpoints' frames remain untagged per the
        // substrate's capability-vs-mandate discipline.
        () -> WireContractVersion.tagPayload(Map.of("detail", "initial")),
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
