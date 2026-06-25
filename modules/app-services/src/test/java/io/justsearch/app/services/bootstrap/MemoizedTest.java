package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 541 §5.2 — unit coverage for the single-shot memoized supplier primitive. */
@DisplayName("Memoized<T> — tempdoc 541 §5.2 primitive")
class MemoizedTest {

  @Test
  @DisplayName("body runs once across multiple get() calls")
  void bodyRunsOnce() {
    AtomicInteger invocations = new AtomicInteger();
    Memoized<String> m =
        Memoized.of(
            () -> {
              invocations.incrementAndGet();
              return "result";
            });
    assertFalse(m.isResolved());
    assertEquals("result", m.get());
    assertTrue(m.isResolved());
    assertEquals("result", m.get());
    assertEquals("result", m.get());
    assertEquals(1, invocations.get(), "body should run exactly once");
  }

  @Test
  @DisplayName("identity preserved across repeated get() calls")
  void identityPreserved() {
    Object sentinel = new Object();
    Memoized<Object> m = Memoized.of(() -> sentinel);
    Object first = m.get();
    Object second = m.get();
    assertSame(first, second);
    assertSame(sentinel, first);
  }

  @Test
  @DisplayName("body failure cached and re-thrown on subsequent get()")
  void bodyFailureCached() {
    AtomicInteger invocations = new AtomicInteger();
    Memoized<String> m =
        Memoized.of(
            () -> {
              invocations.incrementAndGet();
              throw new RuntimeException("boom-" + invocations.get());
            });
    RuntimeException first = assertThrows(RuntimeException.class, m::get);
    assertEquals("boom-1", first.getMessage());
    RuntimeException second = assertThrows(RuntimeException.class, m::get);
    assertEquals("boom-1", second.getMessage(), "captured exception is re-thrown unchanged");
    assertEquals(1, invocations.get(), "body should not retry on subsequent get()");
    assertTrue(m.isResolved(), "isResolved is true even when body failed");
  }

  @Test
  @DisplayName("concurrent first-call resolves to exactly one body invocation")
  void concurrentFirstCall() throws Exception {
    AtomicInteger invocations = new AtomicInteger();
    Memoized<Integer> m =
        Memoized.of(
            () -> {
              try {
                Thread.sleep(20);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return invocations.incrementAndGet();
            });
    int threads = 8;
    Thread[] runners = new Thread[threads];
    Integer[] results = new Integer[threads];
    for (int i = 0; i < threads; i++) {
      final int idx = i;
      runners[i] = new Thread(() -> results[idx] = m.get());
    }
    for (Thread t : runners) t.start();
    for (Thread t : runners) t.join();
    for (Integer r : results) {
      assertEquals(1, r.intValue(), "every concurrent caller sees the same single result");
    }
    assertEquals(1, invocations.get(), "body invoked exactly once even under concurrency");
  }

  @Test
  @DisplayName("null body argument is rejected")
  void rejectNullBody() {
    assertThrows(IllegalArgumentException.class, () -> Memoized.of(null));
  }

  @Test
  @DisplayName("resolution timing — startedAtMs + resolvedAtMs populated on first .get()")
  void resolutionTimingPopulated() throws InterruptedException {
    Memoized<String> m =
        Memoized.of(
            () -> {
              try {
                Thread.sleep(5);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
              return "ok";
            });
    assertTrue(m.startedAtMs().isEmpty(), "no startedAtMs before first .get()");
    assertTrue(m.resolvedAtMs().isEmpty(), "no resolvedAtMs before first .get()");
    long before = System.currentTimeMillis();
    m.get();
    long after = System.currentTimeMillis();
    assertTrue(m.startedAtMs().isPresent());
    assertTrue(m.resolvedAtMs().isPresent());
    assertTrue(m.startedAtMs().getAsLong() >= before);
    assertTrue(m.resolvedAtMs().getAsLong() >= m.startedAtMs().getAsLong());
    assertTrue(m.resolvedAtMs().getAsLong() <= after);
  }

  @Test
  @DisplayName("resolution timing — failure path still captures timestamps")
  void resolutionTimingOnFailure() {
    Memoized<String> m =
        Memoized.of(
            () -> {
              throw new RuntimeException("boom");
            });
    assertThrows(RuntimeException.class, m::get);
    assertTrue(m.startedAtMs().isPresent(), "startedAtMs captured even on failure");
    assertTrue(m.resolvedAtMs().isPresent(), "resolvedAtMs captured even on failure");
  }

  @Test
  @DisplayName("resolution timing — subsequent .get() calls do not change captured timestamps")
  void resolutionTimingFrozenAfterFirstCall() throws InterruptedException {
    Memoized<String> m = Memoized.of(() -> "ok");
    m.get();
    long t0 = m.startedAtMs().getAsLong();
    long t1 = m.resolvedAtMs().getAsLong();
    Thread.sleep(5);
    m.get();
    m.get();
    assertEquals(t0, m.startedAtMs().getAsLong(), "startedAtMs frozen after first .get()");
    assertEquals(t1, m.resolvedAtMs().getAsLong(), "resolvedAtMs frozen after first .get()");
  }
}
