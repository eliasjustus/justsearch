package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.ResumeTokenCodec;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SseEnvelopeWriter")
final class SseEnvelopeWriterTest {

  private static final StreamId STREAM = StreamId.registry("capabilities");

  private SseStreamChannel channel;
  private ScheduledExecutorService heartbeatScheduler;

  @BeforeEach
  void setUp() {
    channel = new SseStreamChannel(STREAM);
    heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "test-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  @AfterEach
  void tearDown() {
    heartbeatScheduler.shutdownNow();
  }

  private SseClient mockSseClient(String sinceToken) {
    SseClient client = mock(SseClient.class);
    Context ctx = mock(Context.class);
    when(client.ctx()).thenReturn(ctx);
    when(ctx.queryParam("since")).thenReturn(sinceToken);
    return client;
  }

  // ============================================================
  // Direct-instance tests (writer methods)
  // ============================================================

  @Test
  @DisplayName("every frame uses constant SSE event name 'frame'")
  void eventNameIsFrame() {
    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    w.sendConnected();
    w.sendSnapshot(Map.of("k", "v"));
    w.sendHeartbeat();

    verify(client, atLeastOnce()).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
  }

  @Test
  @DisplayName("frame body contains streamId / frameKind / seq / ts / payload / resumeToken")
  void frameBodyShape() {
    SseClient client = mock(SseClient.class);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));
    SseStreamChannel hChannel = new SseStreamChannel(StreamId.surface("health-events"));
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, hChannel, Clock.systemUTC());

    hChannel.publish(SseFrameKind.UPDATE, Map.of("foo", "bar"));
    w.sendConnected();

    assertEquals(1, sent.size(), "writer-issued frame; broadcast bypasses writer");
    String connected = sent.get(0);
    assertTrue(connected.contains("\"streamId\":\"surface:health-events\""), connected);
    assertTrue(connected.contains("\"frameKind\":\"LIFECYCLE\""), connected);
    assertTrue(connected.contains("\"seq\":2"), connected);
    assertTrue(connected.contains("\"ts\":"), connected);
    assertTrue(connected.contains("\"resumeToken\":"), connected);
    assertTrue(connected.contains("\"kind\":\"connected\""), connected);
  }

  @Test
  @DisplayName("sendSnapshot wraps extras in {kind: snapshot, ...extras}")
  void snapshotWrapsExtras() {
    SseClient client = mock(SseClient.class);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    w.sendSnapshot(Map.of("conditions", List.of(), "occurrences", List.of()));

    assertEquals(1, sent.size());
    String body = sent.get(0);
    assertTrue(body.contains("\"kind\":\"snapshot\""), body);
    assertTrue(body.contains("\"conditions\""), body);
    assertTrue(body.contains("\"occurrences\""), body);
  }

  // ============================================================
  // attemptResume — Fix B edge cases
  // ============================================================

  @Test
  @DisplayName("attemptResume with valid in-window token replays missed UPDATE frames")
  void resumeWithinWindow() {
    // Populate the channel with 3 UPDATE frames
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 3));

    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    String tokenAtSeq1 = ResumeTokenCodec.encode(STREAM, 1L);
    boolean ok = w.attemptResume(tokenAtSeq1);

    assertTrue(ok, "resume within window returns true");
    // Replay sends seq 2 + seq 3 (strictly after sinceSeq=1). Verify two frames sent.
    verify(client, times(2)).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
  }

  @Test
  @DisplayName("attemptResume with malformed token returns false")
  void resumeMalformed() {
    SseEnvelopeWriter w = new SseEnvelopeWriter(mock(SseClient.class), channel, Clock.systemUTC());
    assertFalse(w.attemptResume("not-a-valid-token"));
    assertFalse(w.attemptResume(null));
    assertFalse(w.attemptResume(""));
  }

  @Test
  @DisplayName("attemptResume with mismatched streamId returns false")
  void resumeWrongStream() {
    SseEnvelopeWriter w = new SseEnvelopeWriter(mock(SseClient.class), channel, Clock.systemUTC());
    String wrongStreamToken = ResumeTokenCodec.encode(StreamId.surface("health-events"), 1L);
    assertFalse(w.attemptResume(wrongStreamToken));
  }

  @Test
  @DisplayName("attemptResume with token predating the buffer returns false")
  void resumeOutsideWindow() {
    SseStreamChannel smallChannel =
        new SseStreamChannel(
            STREAM,
            new io.justsearch.app.observability.stream.StreamSequenceTracker(),
            new io.justsearch.app.observability.stream.FrameHistoryRingBuffer(2),
            Clock.systemUTC());
    // Push 5 UPDATE frames into capacity-2 buffer; oldest retained is seq=4.
    for (int i = 0; i < 5; i++) {
      smallChannel.publish(SseFrameKind.UPDATE, Map.of("i", i));
    }
    SseEnvelopeWriter w =
        new SseEnvelopeWriter(mock(SseClient.class), smallChannel, Clock.systemUTC());

    String oldToken = ResumeTokenCodec.encode(STREAM, 1L); // predates buffer's oldest
    assertFalse(w.attemptResume(oldToken));
  }

  @Test
  @DisplayName("Fix B: attemptResume on empty buffer with positive sinceSeq returns false")
  void resumeEmptyBufferPositiveSeq() {
    // Channel has emitted lifecycle frames (consuming seqs) but NO UPDATE frames.
    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());
    w.sendConnected(); // consumes seq 1
    w.sendHeartbeat(); // consumes seq 2
    // Ring buffer is still empty (no UPDATEs); currentSeq is 2.

    String tokenAtSeq1 = ResumeTokenCodec.encode(STREAM, 1L);
    assertFalse(
        w.attemptResume(tokenAtSeq1),
        "empty buffer with positive sinceSeq must return false (caller emits reset+snapshot)");
  }

  @Test
  @DisplayName("Fix B: attemptResume with sinceSeq > currentSeq returns false")
  void resumeFromFutureServerLifetime() {
    SseClient client = mock(SseClient.class);
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());
    // currentSeq is 0 (fresh channel)

    String futureToken = ResumeTokenCodec.encode(STREAM, 999L);
    assertFalse(
        w.attemptResume(futureToken),
        "token from future/different server lifetime must return false");
  }

  @Test
  @DisplayName("attemptResume with sinceSeq=0 returns true and replays full buffer")
  void resumeFromZeroReplaysAll() {
    SseClient client = mock(SseClient.class);
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));
    SseEnvelopeWriter w = new SseEnvelopeWriter(client, channel, Clock.systemUTC());

    String tokenAtZero = ResumeTokenCodec.encode(STREAM, 0L);
    assertTrue(w.attemptResume(tokenAtZero));
    verify(client, times(2)).sendEvent(eq(SseEnvelopeWriter.EVENT_NAME), any(String.class));
  }

  // ============================================================
  // attach() orchestration
  // ============================================================

  @Test
  @DisplayName("attach: connected → snapshot → subscribe + keepAlive (no resume token)")
  void attachWithoutResumeToken() {
    SseClient client = mockSseClient(null);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("data", "hello"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    verify(client).onClose(any());
    verify(client).keepAlive();
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "connected expected: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "snapshot expected: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"data\":\"hello\"")),
        "snapshot data expected: " + sent);
    // No reset frame when there's no resume token
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"reset\"")),
        "no reset expected without token");
  }

  @Test
  @DisplayName("attach: with valid in-window token replays + skips snapshot")
  void attachReplaysWithValidToken() {
    // Populate 2 UPDATE frames first
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1));
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 2));

    String token = ResumeTokenCodec.encode(STREAM, 1L);
    SseClient client = mockSseClient(token);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("data", "would-be-snapshot"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    // Connected + 1 replay frame (seq 2 only, since sinceSeq=1)
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"connected\"")),
        "connected expected: " + sent);
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "no fresh snapshot when resume succeeded: " + sent);
    assertTrue(
        sent.stream().noneMatch(s -> s.contains("\"kind\":\"reset\"")),
        "no reset when resume succeeded: " + sent);
  }

  @Test
  @DisplayName("attach: with expired token emits reset + fresh snapshot")
  void attachWithExpiredToken() {
    // Populate channel with seqs that move past the token
    channel.publish(SseFrameKind.UPDATE, Map.of("a", 1)); // not in this test buffer (capacity=2)
    SseStreamChannel smallChannel =
        new SseStreamChannel(
            STREAM,
            new io.justsearch.app.observability.stream.StreamSequenceTracker(),
            new io.justsearch.app.observability.stream.FrameHistoryRingBuffer(2),
            Clock.systemUTC());
    for (int i = 0; i < 5; i++) {
      smallChannel.publish(SseFrameKind.UPDATE, Map.of("i", i));
    }

    String oldToken = ResumeTokenCodec.encode(STREAM, 1L); // predates buffer's oldest
    SseClient client = mockSseClient(oldToken);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        smallChannel,
        () -> Map.of("data", "fresh"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"reset\"")),
        "reset expected on expired token: " + sent);
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"kind\":\"snapshot\"")),
        "snapshot expected after reset: " + sent);
  }

  @Test
  @DisplayName("attach: subscribe handle forwards subsequent broadcasts to client")
  void attachForwardsBroadcasts() {
    SseClient client = mockSseClient(null);
    List<String> sent = new ArrayList<>();
    doAnswer(
            inv -> {
              sent.add(inv.getArgument(1, String.class));
              return null;
            })
        .when(client)
        .sendEvent(any(String.class), any(String.class));

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("initial", "yes"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    int frameCountBeforeBroadcast = sent.size();
    channel.publish(SseFrameKind.UPDATE, Map.of("after", "subscribe"));

    assertTrue(sent.size() > frameCountBeforeBroadcast, "broadcast should be forwarded");
    assertTrue(
        sent.stream().anyMatch(s -> s.contains("\"after\":\"subscribe\"")),
        "broadcast payload reached client: " + sent);
  }

  @Test
  @DisplayName("attach: ctx() returns null safely (no resume attempted)")
  void attachWithNullCtx() {
    SseClient client = mock(SseClient.class);
    when(client.ctx()).thenReturn(null);

    SseEnvelopeWriter.attach(
        client,
        channel,
        () -> Map.of("data", "hello"),
        Clock.systemUTC(),
        heartbeatScheduler,
        15L);

    verify(client).keepAlive();
    verify(client).onClose(any());
  }
}
