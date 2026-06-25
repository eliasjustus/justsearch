package io.justsearch.app.observability.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuntimeContextChangeRegistry")
final class RuntimeContextChangeRegistryTest {

  @Test
  @DisplayName("currentSeq starts at 0")
  void initialSeq() {
    assertEquals(0, new RuntimeContextChangeRegistry().currentSeq());
  }

  @Test
  @DisplayName("broadcast increments seq and notifies subscribers with envelope")
  void broadcastNotifies() {
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    registry.subscribe(seen::add);

    RuntimeContext ctx = new RuntimeContext(SystemMode.EVAL, true);
    registry.broadcast(ctx);

    assertEquals(1, registry.currentSeq());
    assertEquals(1, seen.size());
    SseEnvelope env = seen.get(0);
    assertEquals(1L, env.seq());
    assertSame(SseFrameKind.UPDATE, env.frameKind());
    assertSame(RuntimeContextChangeRegistry.STREAM_ID, env.streamId());
    assertEquals(ctx, env.payload());
  }

  @Test
  @DisplayName("subscription unsubscribe stops further notifications")
  void unsubscribeStops() {
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<SseEnvelope> seen = new ArrayList<>();
    var sub = registry.subscribe(seen::add);

    registry.broadcast(new RuntimeContext(SystemMode.PRODUCTION, false));
    sub.unsubscribe();
    registry.broadcast(new RuntimeContext(SystemMode.EVAL, false));

    assertEquals(1, seen.size());
    assertEquals(2, registry.currentSeq());
  }

  @Test
  @DisplayName("throwing listener is removed inline")
  void throwingListenerRemoved() {
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    List<SseEnvelope> healthy = new ArrayList<>();
    registry.subscribe(
        change -> {
          throw new RuntimeException("simulated failure");
        });
    registry.subscribe(healthy::add);

    registry.broadcast(new RuntimeContext(SystemMode.PRODUCTION, false));
    registry.broadcast(new RuntimeContext(SystemMode.EVAL, false));

    assertEquals(2, healthy.size());
    RuntimeContext c0 = (RuntimeContext) healthy.get(0).payload();
    RuntimeContext c1 = (RuntimeContext) healthy.get(1).payload();
    assertTrue(c0.systemMode() == SystemMode.PRODUCTION);
    assertTrue(c1.systemMode() == SystemMode.EVAL);
  }

  @Test
  @DisplayName("broadcast rejects null")
  void broadcastRejectsNull() {
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    assertThrows(NullPointerException.class, () -> registry.broadcast(null));
  }

  @Test
  @DisplayName("subscribe rejects null")
  void subscribeRejectsNull() {
    RuntimeContextChangeRegistry registry = new RuntimeContextChangeRegistry();
    assertThrows(NullPointerException.class, () -> registry.subscribe(null));
  }
}
