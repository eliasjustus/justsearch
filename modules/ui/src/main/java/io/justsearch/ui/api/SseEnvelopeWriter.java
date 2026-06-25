/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.stream.ResumeTokenCodec;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical per-connection writer that wraps {@link SseClient} to emit envelope-shaped
 * frames.
 *
 * <p>Per slice 436 §B.2: every frame is a single JSON object as the SSE {@code data:}
 * payload; SSE event name is constant {@code "frame"}. Consumers route by
 * {@link SseEnvelope#frameKind()} and (for lifecycle frames) the nested
 * {@code payload.kind}.
 *
 * <p>Per the post-impl Fix A consolidation (2026-05-05): this is the canonical
 * per-connection helper that the 4 retrofitted SSE controllers delegate to via
 * {@link #attach}. Each controller reduces to a ~5-line {@code handle(client)} method
 * supplying its snapshot extras; the writer owns lifecycle frames, resume, broadcast
 * forwarding, heartbeat scheduling, and onClose cleanup.
 *
 * <p>Frame discipline:
 *
 * <ul>
 *   <li>Lifecycle frames (connected/snapshot/heartbeat/reset/closing) consume seqs from
 *       the channel's shared tracker but are NOT appended to the ring buffer (per
 *       {@link SseStreamChannel#nextEnvelope}). Per-client; not visible to other
 *       subscribers.
 *   <li>UPDATE frames flow through {@link SseStreamChannel#publish} (broadcast) and ARE
 *       retained in the ring buffer for resume.
 * </ul>
 *
 * <p><strong>Known limitation — snapshot-vs-subscribe race</strong> (per slice 436 §B.C):
 * between {@link #sendSnapshot} (or {@link #attemptResume}) and
 * {@link SseStreamChannel#subscribe}, broadcasts can fire and be missed by the new
 * subscriber. The window is small (single-thread function call). Fixing requires
 * subscribe-before-snapshot + queue-and-filter pattern; out of scope for V1.
 */
public final class SseEnvelopeWriter {

  private static final Logger log = LoggerFactory.getLogger(SseEnvelopeWriter.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  /** Constant SSE event name carried by every envelope frame. */
  public static final String EVENT_NAME = "frame";

  private final SseClient client;
  private final SseStreamChannel channel;
  @SuppressWarnings("unused") // reserved for future per-frame timestamp injection
  private final Clock clock;

  public SseEnvelopeWriter(SseClient client, SseStreamChannel channel, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Returns the channel this writer is bound to. */
  public SseStreamChannel channel() {
    return channel;
  }

  /** Emits a {@code lifecycle.kind: connected} frame. Call once on subscribe. */
  public void sendConnected() {
    sendLifecycle("connected", Map.of());
  }

  /**
   * Emits a {@code lifecycle.kind: snapshot} frame carrying the caller-provided extras.
   * The wire shape is {@code {kind: "snapshot", ...extras}}.
   */
  public void sendSnapshot(Map<String, Object> extras) {
    Objects.requireNonNull(extras, "extras");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("kind", "snapshot");
    body.putAll(extras);
    sendLifecycle(body);
  }

  /** Emits a {@code lifecycle.kind: heartbeat} frame. */
  public void sendHeartbeat() {
    sendLifecycle("heartbeat", Map.of());
  }

  /**
   * Emits a {@code lifecycle.kind: reset} frame signaling that the consumer should
   * discard cached state. Typically followed by a fresh {@link #sendSnapshot} call.
   */
  public void sendReset(String reason) {
    sendLifecycle("reset", Map.of("reason", reason));
  }

  /** Emits a {@code lifecycle.kind: closing} frame. Call once during shutdown. */
  public void sendClosing() {
    sendLifecycle("closing", Map.of());
  }

  /**
   * Replays buffered frames newer than {@code resumeToken}. Returns true if the replay
   * was within the resume window (and any newer frames were sent); false if the token
   * is outside the window, malformed, from a different stream, or from a different
   * server lifetime (caller follows up with {@link #sendReset} + {@link #sendSnapshot}).
   *
   * <p>Per Fix B: detects three "outside window" cases —
   *
   * <ol>
   *   <li>Token from a future / different server lifetime ({@code sinceSeq > current}).
   *   <li>Empty buffer with a positive sinceSeq (server restarted, or no UPDATEs yet
   *       since the token was issued — can't validate the gap).
   *   <li>Token predates the buffer's oldest retained frame.
   * </ol>
   */
  public boolean attemptResume(String resumeToken) {
    Optional<ResumeTokenCodec.Decoded> decoded = ResumeTokenCodec.decode(resumeToken);
    if (decoded.isEmpty()) {
      return false;
    }
    ResumeTokenCodec.Decoded d = decoded.get();
    if (!d.streamId().equals(channel.streamId())) {
      return false;
    }
    long sinceSeq = d.seq();
    long current = channel.currentSeq();
    long oldest = channel.oldestRetainedSeq();
    // Token from a future / different server lifetime.
    if (sinceSeq > current) {
      return false;
    }
    // Empty buffer with positive sinceSeq, OR token predates the buffer's window.
    if (sinceSeq > 0 && (oldest == 0 || sinceSeq < oldest)) {
      return false;
    }
    for (SseEnvelope frame : channel.framesSince(sinceSeq)) {
      sendFrame(frame);
    }
    return true;
  }

  /**
   * Subscribes to the channel and forwards each broadcast envelope to the client.
   * Returns the {@link SseStreamChannel.Subscription} handle the caller MUST unsubscribe
   * on connection close.
   */
  public SseStreamChannel.Subscription subscribe() {
    return channel.subscribe(this::sendFrame);
  }

  /**
   * Per-connection orchestrator.
   *
   * <ol>
   *   <li>Emits {@code connected} lifecycle.
   *   <li>Reads {@code ?since=<token>} from {@link SseClient#ctx()} (null-safe).
   *   <li>Attempts resume; on miss emits {@code reset}.
   *   <li>If not replayed, emits {@code snapshot} carrying the
   *       {@code snapshotExtras.get()} payload.
   *   <li>Subscribes to the channel for live UPDATE forwarding.
   *   <li>Schedules heartbeat lifecycle frames at {@code heartbeatSeconds} cadence.
   *   <li>Registers onClose to unsubscribe + cancel heartbeat.
   *   <li>Calls {@link SseClient#keepAlive()} to hold the connection open.
   * </ol>
   */
  public static SseEnvelopeWriter attach(
      SseClient client,
      SseStreamChannel channel,
      Supplier<Map<String, Object>> snapshotExtras,
      Clock clock,
      ScheduledExecutorService heartbeatScheduler,
      long heartbeatSeconds) {
    Objects.requireNonNull(snapshotExtras, "snapshotExtras");
    Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    // observations.md L118 fix: force SSE content-type even when client omits
    // `Accept: text/event-stream`. Javalin's content negotiation otherwise
    // returns text/plain Content-Length:0 for ad-hoc curl clients, swallowing
    // every envelope this method writes. Real EventSource clients send the
    // Accept header so this is a no-op for them.
    if (client.ctx() != null) {
      client.ctx().contentType("text/event-stream; charset=utf-8");
      client.ctx().header("Cache-Control", "no-cache");
      client.ctx().header("X-Accel-Buffering", "no");
    }
    SseEnvelopeWriter writer = new SseEnvelopeWriter(client, channel, clock);
    writer.sendConnected();

    String token = (client.ctx() == null) ? null : client.ctx().queryParam("since");
    boolean replayed = false;
    if (token != null && !token.isBlank()) {
      replayed = writer.attemptResume(token);
      if (!replayed) {
        writer.sendReset("resume-window-miss");
      }
    }
    if (!replayed) {
      writer.sendSnapshot(snapshotExtras.get());
    }

    SseStreamChannel.Subscription subscription = writer.subscribe();

    var heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            writer::sendHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

    client.onClose(
        () -> {
          subscription.unsubscribe();
          heartbeat.cancel(false);
        });

    client.keepAlive();
    return writer;
  }

  /**
   * Event-only variant of {@link #attach}: omits the {@code snapshot} lifecycle frame.
   *
   * <p>Per tempdoc 487 §4.3: intent envelopes are events, not state — there is no
   * "current set of intents" to snapshot on subscribe. The stream emits {@code connected}
   * on subscribe and proceeds directly to live UPDATE forwarding. On reconnect-miss the
   * substrate still emits {@code reset} (no snapshot) and the FE clears its dedup LRU.
   *
   * <p>This is the platform's first event-only always-on stream pattern. Future
   * event-only streams (when they land) reuse this overload.
   *
   * <p>Same lifecycle as {@link #attach} otherwise: connected → resume-attempt →
   * subscribe → heartbeat → onClose cleanup.
   */
  public static SseEnvelopeWriter attachEventOnly(
      SseClient client,
      SseStreamChannel channel,
      Clock clock,
      ScheduledExecutorService heartbeatScheduler,
      long heartbeatSeconds) {
    Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    // observations.md L118 fix: same forced content-type as attach() above.
    if (client.ctx() != null) {
      client.ctx().contentType("text/event-stream; charset=utf-8");
      client.ctx().header("Cache-Control", "no-cache");
      client.ctx().header("X-Accel-Buffering", "no");
    }
    SseEnvelopeWriter writer = new SseEnvelopeWriter(client, channel, clock);
    writer.sendConnected();

    String token = (client.ctx() == null) ? null : client.ctx().queryParam("since");
    if (token != null && !token.isBlank()) {
      boolean replayed = writer.attemptResume(token);
      if (!replayed) {
        writer.sendReset("resume-window-miss");
      }
    }
    // No snapshot — event-only stream, no state to materialize.

    SseStreamChannel.Subscription subscription = writer.subscribe();

    var heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            writer::sendHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

    client.onClose(
        () -> {
          subscription.unsubscribe();
          heartbeat.cancel(false);
        });

    client.keepAlive();
    return writer;
  }

  private void sendLifecycle(String kind, Map<String, Object> extras) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("kind", kind);
    body.putAll(extras);
    sendLifecycle(body);
  }

  private void sendLifecycle(Map<String, Object> body) {
    sendFrame(channel.nextEnvelope(SseFrameKind.LIFECYCLE, body));
  }

  private void sendFrame(SseEnvelope envelope) {
    try {
      client.sendEvent(EVENT_NAME, MAPPER.writeValueAsString(envelope));
    } catch (RuntimeException e) {
      log.warn(
          "SSE envelope frame send failed (subscriber will be removed on next broadcast)", e);
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize SSE envelope frame", e);
    }
  }
}
