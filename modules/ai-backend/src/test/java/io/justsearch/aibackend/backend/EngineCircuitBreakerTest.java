package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

final class EngineCircuitBreakerTest {

  @Test
  void startsInClosedState() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker();
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
    assertTrue(breaker.isClosed());
    assertFalse(breaker.isOpen());
  }

  @Test
  void remainsClosedAfterSuccesses() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker();
    breaker.recordSuccess();
    breaker.recordSuccess();
    breaker.recordSuccess();
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
  }

  @Test
  void opensAfterConsecutiveFailures() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(3, Duration.ofMinutes(1));

    breaker.recordFailure(new RuntimeException("fail 1"));
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
    assertEquals(1, breaker.consecutiveFailures());

    breaker.recordFailure(new RuntimeException("fail 2"));
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
    assertEquals(2, breaker.consecutiveFailures());

    breaker.recordFailure(new RuntimeException("fail 3"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
    assertTrue(breaker.isOpen());
  }

  @Test
  void successResetsFailureCount() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(3, Duration.ofMinutes(1));

    breaker.recordFailure(new RuntimeException("fail 1"));
    breaker.recordFailure(new RuntimeException("fail 2"));
    assertEquals(2, breaker.consecutiveFailures());

    breaker.recordSuccess();
    assertEquals(0, breaker.consecutiveFailures());
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
  }

  @Test
  void fatalErrorImmediatelyOpensCircuit() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(100, Duration.ofMinutes(1));

    // OutOfMemoryError is fatal
    breaker.recordFailure(new OutOfMemoryError());
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());

    // Reset and try with GGML_ASSERT message
    breaker.reset();
    breaker.recordFailure(new RuntimeException("GGML_ASSERT failed in some_function"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
  }

  @Test
  void requireClosedThrowsWhenOpen() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(1, Duration.ofMinutes(1));
    breaker.recordFailure(new RuntimeException("fail"));

    BackendException ex = assertThrows(BackendException.class, breaker::requireClosed);
    assertEquals(BackendException.Category.FATAL, ex.category());
    assertTrue(ex.requiresRestart());
  }

  @Test
  void manualTripOpensCircuit() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker();
    breaker.trip("manual test");
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
    assertEquals("manual test", breaker.tripReason());
  }

  @Test
  void manualResetClosesCircuit() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(1, Duration.ofMinutes(1));
    breaker.recordFailure(new RuntimeException("fail"));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());

    breaker.reset();
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
    assertEquals(0, breaker.consecutiveFailures());
    assertNull(breaker.tripReason());
  }

  @Test
  void backendExceptionWithFatalCategoryTripsCircuit() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(100, Duration.ofMinutes(1));
    breaker.recordFailure(BackendException.nativeError("test crash", null));
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
  }

  @Test
  void transientExceptionDoesNotImmediatelyTrip() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(5, Duration.ofMinutes(1));
    breaker.recordFailure(BackendException.slotsFull());
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());
    assertEquals(1, breaker.consecutiveFailures());
  }

  @Test
  void zeroThresholdNeverAutoOpens() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker(0, Duration.ofMinutes(1));
    for (int i = 0; i < 100; i++) {
      breaker.recordFailure(new RuntimeException("non-fatal"));
    }
    // Still closed because threshold is 0 (disabled)
    assertEquals(EngineCircuitBreaker.State.CLOSED, breaker.state());

    // But fatal errors still trip it
    breaker.recordFailure(new OutOfMemoryError());
    assertEquals(EngineCircuitBreaker.State.OPEN, breaker.state());
  }

  @Test
  void lastFailureIsTracked() {
    EngineCircuitBreaker breaker = new EngineCircuitBreaker();
    assertNull(breaker.lastFailure());

    RuntimeException ex = new RuntimeException("test");
    breaker.recordFailure(ex);
    assertSame(ex, breaker.lastFailure());
  }
}
