package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.StateSnapshot;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.intent.IntentEnvelopeEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Post-implementation fix A5: ring-buffer replay verification for the always-on
 * intent stream.
 *
 * <p>The slice 487 §4.3 dedup story — stable {@code payload.id} + FE LRU — is
 * load-bearing for the platform's first event-only always-on stream. Without
 * dedup, an FE reconnect with a stale resume-token replays UPDATE frames from
 * the slice-436 ring buffer; double-firing a destructive operation envelope
 * was the named risk.
 *
 * <p>This test verifies the server-side half of the contract: broadcast UPDATE
 * frames are retained in the channel's ring buffer and the {@code framesSince}
 * query returns them in monotonic seq order — the precondition the FE LRU
 * dedup depends on. The FE-side LRU is tested in
 * {@code modules/ui-web/src/api/intent/bootIntentStreamBridge.test.ts}.
 */
final class IntentEnvelopeChangeRegistryReplayTest {

  private static IntentEnvelopeEvent newEnvelope(String id) {
    Intent intent =
        new Intent(
            new ShellAddress.Navigation(new SurfaceRef("core.library"), StateSnapshot.empty()),
            TransportTag.LLM_EMISSION);
    InvocationProvenance provenance =
        InvocationProvenance.fromTransport(
            TransportTag.LLM_EMISSION, Optional.empty(), Instant.parse("2026-05-13T10:00:00Z"));
    return new IntentEnvelopeEvent(
        id,
        intent,
        provenance,
        new io.justsearch.agent.api.registry.IntentSourceRef("core.llm-chat-emission"));
  }

  @Test
  void broadcastUpdateFramesAreRetainedForReplay() {
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();

    registry.broadcast(newEnvelope("ie-001"));
    registry.broadcast(newEnvelope("ie-002"));
    registry.broadcast(newEnvelope("ie-003"));

    // framesSince(0) returns the full ring buffer contents (all three frames)
    List<SseEnvelope> all = registry.channel().framesSince(0);
    assertEquals(3, all.size(), "all three UPDATE frames retained for replay");
    assertPayloadId(all.get(0), "ie-001");
    assertPayloadId(all.get(1), "ie-002");
    assertPayloadId(all.get(2), "ie-003");
  }

  @Test
  void framesSinceMidStreamReturnsOnlyNewerFrames() {
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    registry.broadcast(newEnvelope("ie-001"));
    registry.broadcast(newEnvelope("ie-002"));
    registry.broadcast(newEnvelope("ie-003"));

    long seqOfSecondFrame = 2L;
    List<SseEnvelope> replay = registry.channel().framesSince(seqOfSecondFrame);
    assertEquals(
        1, replay.size(), "framesSince(2) returns only frames with seq > 2 (just ie-003)");
    assertPayloadId(replay.get(0), "ie-003");
  }

  @Test
  void replayedFramesPreserveEnvelopeIdForFeDedup() {
    // The FE LRU dedup keys on payload.id. Verify the id round-trips through
    // ring-buffer storage unchanged — this is the contract the FE depends on.
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    registry.broadcast(newEnvelope("ie-stable-id-001"));

    List<SseEnvelope> replay = registry.channel().framesSince(0);
    assertEquals(1, replay.size());
    var payload = assertInstanceOf(IntentEnvelopeEvent.class, replay.get(0).payload());
    assertEquals(
        "ie-stable-id-001",
        payload.id(),
        "the id field flows through ring-buffer retention unchanged so FE LRU can dedup on it");
  }

  @Test
  void monotonicSequenceAcrossBroadcasts() {
    IntentEnvelopeChangeRegistry registry = new IntentEnvelopeChangeRegistry();
    registry.broadcast(newEnvelope("ie-001"));
    registry.broadcast(newEnvelope("ie-002"));
    registry.broadcast(newEnvelope("ie-003"));

    List<SseEnvelope> all = registry.channel().framesSince(0);
    long prevSeq = 0;
    for (SseEnvelope frame : all) {
      // Strictly increasing — replay relies on this for correct framesSince(token)
      // semantics. If the channel ever issued non-monotonic seqs the resume protocol
      // would skip or duplicate frames.
      assert frame.seq() > prevSeq
          : "non-monotonic seq detected: prev=" + prevSeq + " current=" + frame.seq();
      prevSeq = frame.seq();
    }
  }

  private static void assertPayloadId(SseEnvelope envelope, String expectedId) {
    var payload = assertInstanceOf(IntentEnvelopeEvent.class, envelope.payload());
    assertEquals(expectedId, payload.id());
  }
}
