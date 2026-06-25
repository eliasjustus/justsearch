/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * SSE endpoint at {@code GET /api/intent/stream}.
 *
 * <p>Per tempdoc 487 §4.3: always-on intent envelope stream — the
 * {@code SseIntentForwardingTransport} realization. The FE
 * {@code EnvelopeStream} subscriber boots once at app start, dedups by the
 * envelope's stable {@code payload.id} against a bounded LRU, and dispatches
 * received {@code Intent} envelopes into the existing FE
 * {@code IntentRouter.dispatch}.
 *
 * <p>Wire shape: universal envelope (slice 436) with constant SSE event name
 * {@code "frame"}; per-frame data is the {@code SseEnvelope} JSON. UPDATE
 * frames carry {@link io.justsearch.app.observability.intent.IntentEnvelopeEvent}
 * payloads with the constant {@code "kind": "intent.envelope"} discriminator.
 *
 * <p><strong>Event-only stream (tempdoc §4.3).</strong> Unlike the four
 * state-shaped always-on streams ({@code /api/runtime-context/stream},
 * {@code /api/health/events/stream},
 * {@code /api/advisory/operation-completed/stream}, {@code /infra/capabilities/stream}),
 * this controller does NOT emit a {@code snapshot} lifecycle frame on
 * subscribe — intent envelopes are events, not state. {@code connected} is
 * sent on subscribe; live UPDATE forwarding starts immediately. On
 * reconnect-miss (resume-token-out-of-window), {@code reset} is emitted
 * alone (no snapshot) and the FE clears its dedup LRU and resumes. Uses the
 * {@link SseEnvelopeWriter#attachEventOnly} helper.
 */
public final class IntentStreamController {

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  private final IntentEnvelopeChangeRegistry changes;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  public IntentStreamController(IntentEnvelopeChangeRegistry changes) {
    this(changes, Clock.systemUTC());
  }

  public IntentStreamController(IntentEnvelopeChangeRegistry changes, Clock clock) {
    this.changes = Objects.requireNonNull(changes, "changes");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "intent-stream-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  public void handle(SseClient sseClient) {
    SseEnvelopeWriter.attachEventOnly(
        sseClient, changes.channel(), clock, heartbeatScheduler, HEARTBEAT_SECONDS);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
