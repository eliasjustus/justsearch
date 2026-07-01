/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.stream.ResumeTokenCodec;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fan-in writer that aggregates SEVERAL {@link SseStreamChannel}s onto ONE {@link SseClient}
 * connection. Tempdoc 662 — the cross-channel multiplexer: where {@link SseEnvelopeWriter}
 * binds one connection to one channel, this binds one connection to N channels, each frame
 * still carrying its own {@code streamId} (the existing {@link
 * io.justsearch.app.api.stream.SseEnvelope#streamId} discriminator) so the FE can demux.
 *
 * <p>Per-channel resume is REUSED, not reinvented: each frame's {@code resumeToken} is already
 * the existing {@link ResumeTokenCodec} {@code streamId:seq} pair, so a multiplexed resume is
 * just the SET of those per-channel tokens, comma-joined in one {@code ?since=} query param. No
 * new codec; {@link #parseTokenBundle} only splits the bundle and decodes each token with the
 * existing codec, then each channel's resume/reset/snapshot decision reuses {@link
 * SseEnvelopeWriter#attemptResume} unchanged. {@code attemptResume} already rejects a token
 * whose decoded {@code streamId} doesn't match its channel, so a misrouted bundle entry safely
 * degenerates to reset+snapshot rather than corrupting another channel's state.
 *
 * <p>ONE shared heartbeat ticks the whole connection (not one per channel) via a dedicated
 * {@code system:shell-events-heartbeat} pseudo-channel — a heartbeat frame is a per-client
 * lifecycle frame (never appended to any channel's resumable ring buffer per {@link
 * SseStreamChannel#nextEnvelope}), so it carries no real channel's data and is purely a
 * connection-liveness tick the FE's per-streamId reducers can safely ignore (each already
 * tolerates an unrelated {@code streamId} on heartbeat-style lifecycle frames since they only
 * ever respond to their OWN streamId's frames after the FE-side demux).
 */
public final class MultiplexedSseWriter {

  private MultiplexedSseWriter() {}

  /**
   * One channel to multiplex onto the connection, paired with its optional snapshot-on-subscribe
   * supplier. {@code snapshotExtras == null} means the channel is event-only (no state to
   * snapshot — mirrors {@link SseEnvelopeWriter#attachEventOnly}'s contract for that channel).
   */
  public record ChannelSource(SseStreamChannel channel, Supplier<Map<String, Object>> snapshotExtras) {
    public ChannelSource {
      Objects.requireNonNull(channel, "channel");
    }
  }

  /**
   * Attaches all {@code sources} onto one {@code client} connection. Mirrors {@link
   * SseEnvelopeWriter#attach}'s per-connection orchestration (connected → resume-attempt →
   * [snapshot] → subscribe → heartbeat → onClose cleanup), run once per channel, sharing one
   * client, one parsed resume bundle, and one heartbeat.
   *
   * @param heartbeatChannel a channel dedicated to heartbeat lifecycle frames only — must NOT be
   *     one of {@code sources}' channels (heartbeats are not real channel data).
   */
  public static List<SseStreamChannel.Subscription> attachAll(
      SseClient client,
      List<ChannelSource> sources,
      SseStreamChannel heartbeatChannel,
      Clock clock,
      ScheduledExecutorService heartbeatScheduler,
      long heartbeatSeconds) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(sources, "sources");
    Objects.requireNonNull(heartbeatChannel, "heartbeatChannel");
    Objects.requireNonNull(heartbeatScheduler, "heartbeatScheduler");
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("sources must not be empty");
    }
    SseEnvelopeWriter.forceSseHeaders(client);

    Map<String, String> tokensByStreamId =
        parseTokenBundle(client.ctx() == null ? null : client.ctx().queryParam("since"));

    // Tempdoc 662 post-implementation fix (critical-analysis pass): if any channel's processing
    // throws partway through (e.g. a snapshotExtras supplier — action-ledger's currentEvents(),
    // indexing-jobs's gRPC-backed bridge.latestSnapshotPair() — failing), the earlier channels'
    // subscriptions in THIS attempt must not be left dangling. client.onClose(...) below is the
    // normal cleanup path, but it is only wired up AFTER this loop completes, so a mid-loop
    // exception would previously skip it entirely, leaking the already-subscribed channels'
    // listeners until SseStreamChannel.publish's self-healing removeIf evicts them on the next
    // broadcast against the by-then-dead client. Explicit try/catch closes that gap.
    List<SseStreamChannel.Subscription> subscriptions = new ArrayList<>(sources.size());
    try {
      for (ChannelSource source : sources) {
        SseEnvelopeWriter writer = new SseEnvelopeWriter(client, source.channel(), clock);
        writer.sendConnected();

        String token = tokensByStreamId.get(source.channel().streamId().value());
        boolean replayed = token != null && writer.attemptResume(token);
        if (!replayed) {
          if (token != null) {
            writer.sendReset("resume-window-miss");
          }
          if (source.snapshotExtras() != null) {
            writer.sendSnapshot(source.snapshotExtras().get());
          }
        }
        subscriptions.add(writer.subscribe());
      }
    } catch (RuntimeException e) {
      // Tempdoc 662 post-implementation fix (critical-analysis pass): guard each unsubscribe
      // individually so a cleanup failure can't mask the original exception or abort cleanup of
      // the remaining subscriptions. Latent today (SseStreamChannel's Subscription is a plain
      // listeners.remove(listener) that cannot practically throw) but this loop exists precisely
      // to be robust against failure, so it must not itself have an unguarded failure mode.
      for (SseStreamChannel.Subscription subscription : subscriptions) {
        try {
          subscription.unsubscribe();
        } catch (RuntimeException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
        }
      }
      throw e;
    }

    SseEnvelopeWriter heartbeatWriter = new SseEnvelopeWriter(client, heartbeatChannel, clock);
    var heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            heartbeatWriter::sendHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

    List<SseStreamChannel.Subscription> immutableSubscriptions = List.copyOf(subscriptions);
    client.onClose(
        () -> {
          for (SseStreamChannel.Subscription subscription : immutableSubscriptions) {
            subscription.unsubscribe();
          }
          heartbeat.cancel(false);
        });

    client.keepAlive();
    return immutableSubscriptions;
  }

  /**
   * Splits a comma-joined bundle of per-channel resume tokens (the multiplexed {@code ?since=}
   * value) and decodes each with the existing {@link ResumeTokenCodec}, keyed by the decoded
   * {@code streamId}'s wire value. Malformed entries are dropped silently (the per-channel
   * {@link SseEnvelopeWriter#attemptResume} treats a missing token identically to a decode
   * failure — reset+snapshot — so dropping here is equivalent and avoids a second failure path).
   */
  static Map<String, String> parseTokenBundle(String since) {
    if (since == null || since.isBlank()) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String raw : since.split(",")) {
      String token = raw.trim();
      if (token.isEmpty()) {
        continue;
      }
      ResumeTokenCodec.decode(token).ifPresent(decoded -> out.put(decoded.streamId().value(), token));
    }
    return out;
  }
}
