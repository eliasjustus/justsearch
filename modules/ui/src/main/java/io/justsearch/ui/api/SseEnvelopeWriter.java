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
import java.util.OptionalLong;
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
 * <p><strong>Known limitation — snapshot-vs-subscribe race</strong> (per slice 436 §B.C),
 * now scoped to the NO-CURSOR path only. On a fresh connect (no {@code ?since=}) the
 * snapshot is built from a caller-supplied supplier and only then is
 * {@link SseStreamChannel#subscribe} called, so a broadcast in between can be missed. That
 * window stays open deliberately: closing it means invoking {@code snapshotExtras.get()}
 * under the channel's monitor — lock inversion across 18 controllers (tempdoc 834 §1.3.1).
 *
 * <p>The RESUME path ({@code ?since=<token>}) no longer has the race: {@link #attach} and
 * {@link #attachEventOnly} take it through
 * {@link SseStreamChannel#subscribeAndReplay}, which registers the listener and snapshots
 * the replay under one lock, so a frame published mid-attach reaches the client either via
 * the replay or via the live fan-out — never both, never neither.
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
   *
   * <p>Non-atomic: this replays only, and the caller subscribes separately, so a frame
   * broadcast in between is missed. {@link MultiplexedSseWriter} is its remaining caller —
   * per-channel atomic attach across a fan-in connection is not in tempdoc 834 S3a's scope
   * (§1.3.1 names {@code attach}). Single-channel connections use
   * {@link #attemptResumeAndSubscribe}.
   */
  public boolean attemptResume(String resumeToken) {
    OptionalLong sinceSeq = resumeCursor(resumeToken);
    if (sinceSeq.isEmpty() || !channel.isWithinResumeWindow(sinceSeq.getAsLong())) {
      return false;
    }
    for (SseEnvelope frame : channel.framesSince(sinceSeq.getAsLong())) {
      sendFrame(frame);
    }
    return true;
  }

  /**
   * Atomic form of {@link #attemptResume}: replays the missed frames AND subscribes for
   * live forwarding under one channel lock, so no broadcast can slip between the two
   * (tempdoc 834 §1.3.1). Returns the subscription handle the caller MUST unsubscribe on
   * connection close, or empty when the token is unusable — malformed, from another stream,
   * or outside the resume window — in which case nothing was replayed and no listener was
   * registered, and the caller falls back to reset + snapshot + {@link #subscribe}.
   */
  public Optional<SseStreamChannel.Subscription> attemptResumeAndSubscribe(String resumeToken) {
    OptionalLong sinceSeq = resumeCursor(resumeToken);
    if (sinceSeq.isEmpty()) {
      return Optional.empty();
    }
    return channel.subscribeAndReplay(this::sendFrame, sinceSeq.getAsLong());
  }

  /**
   * Decodes {@code resumeToken} to its cursor seq, or empty when it is malformed, null, or
   * addressed to a different stream. The window check is the channel's
   * ({@link SseStreamChannel#isWithinResumeWindow}) so the two resume forms above cannot
   * drift apart on it.
   */
  private OptionalLong resumeCursor(String resumeToken) {
    Optional<ResumeTokenCodec.Decoded> decoded = ResumeTokenCodec.decode(resumeToken);
    if (decoded.isEmpty() || !decoded.get().streamId().equals(channel.streamId())) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(decoded.get().seq());
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
   *   <li>Attempts resume — atomically replaying AND subscribing; on miss emits
   *       {@code reset}.
   *   <li>If not replayed, emits {@code snapshot} carrying the
   *       {@code snapshotExtras.get()} payload.
   *   <li>Subscribes to the channel for live UPDATE forwarding (only when the resume path
   *       did not already do so).
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
    forceSseHeaders(client);
    SseEnvelopeWriter writer = new SseEnvelopeWriter(client, channel, clock);
    writer.sendConnected();

    String token = (client.ctx() == null) ? null : client.ctx().queryParam("since");
    Optional<SseStreamChannel.Subscription> resumed = Optional.empty();
    if (token != null && !token.isBlank()) {
      resumed = writer.attemptResumeAndSubscribe(token);
      if (resumed.isEmpty()) {
        writer.sendReset("resume-window-miss");
      }
    }
    if (resumed.isEmpty()) {
      writer.sendSnapshot(snapshotExtras.get());
    }

    SseStreamChannel.Subscription subscription = resumed.orElseGet(writer::subscribe);

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
    forceSseHeaders(client);
    SseEnvelopeWriter writer = new SseEnvelopeWriter(client, channel, clock);
    writer.sendConnected();

    String token = (client.ctx() == null) ? null : client.ctx().queryParam("since");
    Optional<SseStreamChannel.Subscription> resumed = Optional.empty();
    if (token != null && !token.isBlank()) {
      resumed = writer.attemptResumeAndSubscribe(token);
      if (resumed.isEmpty()) {
        writer.sendReset("resume-window-miss");
      }
    }
    // No snapshot — event-only stream, no state to materialize.

    SseStreamChannel.Subscription subscription = resumed.orElseGet(writer::subscribe);

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
   * Forces the SSE content-type/headers even when the client omits {@code Accept:
   * text/event-stream} (observations.md L118 fix — Javalin's content negotiation otherwise
   * returns {@code text/plain Content-Length:0} for ad-hoc curl clients, swallowing every
   * envelope a writer sends; real {@code EventSource} clients send the Accept header so this
   * is a no-op for them). Shared by {@link #attach}, {@link #attachEventOnly}, and {@link
   * MultiplexedSseWriter} (which forces headers once for a connection carrying several
   * channels rather than re-deriving this 3-liner).
   */
  static void forceSseHeaders(SseClient client) {
    if (client.ctx() != null) {
      client.ctx().contentType("text/event-stream; charset=utf-8");
      client.ctx().header("Cache-Control", "no-cache");
      client.ctx().header("X-Accel-Buffering", "no");
    }
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
