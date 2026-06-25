package io.justsearch.ipc.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GrpcCircuitBreakerTest {

  @Test
  void opensAfterThresholdThenAllowsSingleHalfOpenProbe() {
    AtomicLong now = new AtomicLong(1_000L);
    GrpcCircuitBreaker breaker = new GrpcCircuitBreaker(2, 500L, now::get);

    assertTrue(breaker.allowRequest());
    breaker.recordFailure();
    assertEquals(GrpcCircuitBreaker.State.CLOSED, breaker.state());

    breaker.recordFailure();
    assertEquals(GrpcCircuitBreaker.State.OPEN, breaker.state());
    assertFalse(breaker.allowRequest());

    now.addAndGet(600L);
    assertTrue(breaker.allowRequest());
    assertEquals(GrpcCircuitBreaker.State.HALF_OPEN, breaker.state());
    assertFalse(breaker.allowRequest());

    breaker.recordSuccess();
    assertEquals(GrpcCircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.allowRequest());
  }

  @Test
  void failedHalfOpenProbeReturnsToOpen() {
    AtomicLong now = new AtomicLong(10_000L);
    GrpcCircuitBreaker breaker = new GrpcCircuitBreaker(1, 100L, now::get);

    breaker.recordFailure();
    assertEquals(GrpcCircuitBreaker.State.OPEN, breaker.state());

    now.addAndGet(101L);
    assertTrue(breaker.allowRequest());
    assertEquals(GrpcCircuitBreaker.State.HALF_OPEN, breaker.state());

    breaker.recordFailure();
    assertEquals(GrpcCircuitBreaker.State.OPEN, breaker.state());
  }

  @Test
  void observerAndCompatibilityAliasesAreWired() {
    AtomicLong now = new AtomicLong(50_000L);
    AtomicInteger transitions = new AtomicInteger();
    AtomicInteger rejections = new AtomicInteger();
    GrpcCircuitBreaker breaker =
        new GrpcCircuitBreaker(
            1,
            100L,
            now::get,
            new GrpcCircuitBreaker.Observer() {
              @Override
              public void onStateTransition(GrpcCircuitBreaker.State from, GrpcCircuitBreaker.State to) {
                transitions.incrementAndGet();
              }

              @Override
              public void onRequestRejected(GrpcCircuitBreaker.State state) {
                rejections.incrementAndGet();
              }
            });

    assertEquals(GrpcCircuitBreaker.State.CLOSED, breaker.getState());
    assertFalse(breaker.isOpen());
    assertEquals(0, breaker.getFailureCount());

    breaker.recordFailure();
    assertTrue(breaker.isOpen());
    assertEquals(1, transitions.get());

    assertFalse(breaker.allowRequest());
    assertEquals(1, rejections.get());

    now.addAndGet(101L);
    assertTrue(breaker.allowRequest());
    assertEquals(2, transitions.get());
    assertFalse(breaker.allowRequest());
    assertEquals(2, rejections.get());

    breaker.recordSuccess();
    assertEquals(GrpcCircuitBreaker.State.CLOSED, breaker.getState());
    assertEquals(3, transitions.get());
    assertEquals(0, breaker.getFailureCount());
  }
}
