package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationHistoryChangeRegistry")
final class OperationHistoryChangeRegistryTest {

  private static OperationHistoryEntry entry(int seq) {
    return new OperationHistoryEntry(
        new OperationRef("core.test-" + seq),
        "head",
        Optional.empty(),
        Instant.parse("2026-04-30T12:00:00Z"),
        Instant.parse("2026-04-30T12:00:01Z"),
        OperationOutcome.SUCCESS,
        Optional.empty(),
        InvocationProvenance.systemInternal(Instant.parse("2026-04-30T12:00:00Z")),
        Optional.empty());
  }

  @Test
  @DisplayName("currentSeq starts at 0")
  void initialSeq() {
    assertEquals(0, new OperationHistoryChangeRegistry().currentSeq());
  }

  @Test
  @DisplayName("broadcast increments seq and notifies subscribers with envelope")
  void broadcastNotifies() {
    OperationHistoryChangeRegistry registry = new OperationHistoryChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    OperationHistoryEntry e = entry(1);
    registry.broadcast(e);

    assertEquals(1, registry.currentSeq());
    assertEquals(1, seen.size());
    SseEnvelope env = seen.get(0);
    assertEquals(1L, env.seq());
    assertSame(SseFrameKind.UPDATE, env.frameKind());
    assertSame(OperationHistoryChangeRegistry.STREAM_ID, env.streamId());
    assertEquals(e, env.payload());
  }

  @Test
  @DisplayName("unsubscribe stops further notifications")
  void unsubscribeStops() {
    OperationHistoryChangeRegistry registry = new OperationHistoryChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    registry.broadcast(entry(1));
    sub.unsubscribe();
    registry.broadcast(entry(2));

    assertEquals(1, seen.size());
    assertEquals(2, registry.currentSeq());
  }

  @Test
  @DisplayName("throwing listener is removed inline")
  void throwingListenerRemoved() {
    OperationHistoryChangeRegistry registry = new OperationHistoryChangeRegistry();
    List<SseEnvelope> healthy = new ArrayList<>();
    registry.subscribe(
        a -> {
          throw new RuntimeException("simulated failure");
        });
    registry.subscribe(healthy::add);

    registry.broadcast(entry(1));
    registry.broadcast(entry(2));

    assertEquals(2, healthy.size());
    OperationHistoryEntry e0 = (OperationHistoryEntry) healthy.get(0).payload();
    OperationHistoryEntry e1 = (OperationHistoryEntry) healthy.get(1).payload();
    assertEquals("core.test-1", e0.operationId().value());
    assertEquals("core.test-2", e1.operationId().value());
  }

  @Test
  @DisplayName("broadcast rejects null")
  void broadcastRejectsNull() {
    OperationHistoryChangeRegistry registry = new OperationHistoryChangeRegistry();
    assertThrows(NullPointerException.class, () -> registry.broadcast(null));
  }

  @Test
  @DisplayName("subscribe rejects null")
  void subscribeRejectsNull() {
    OperationHistoryChangeRegistry registry = new OperationHistoryChangeRegistry();
    assertThrows(NullPointerException.class, () -> registry.subscribe(null));
  }
}
