package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StreamSequenceTracker")
final class StreamSequenceTrackerTest {

  @Test
  @DisplayName("first next() returns 1 (frames seq <= 0 are invalid)")
  void firstSeqIsOne() {
    StreamSequenceTracker t = new StreamSequenceTracker();
    assertEquals(1L, t.next());
  }

  @Test
  @DisplayName("subsequent next() calls are monotonic")
  void monotonic() {
    StreamSequenceTracker t = new StreamSequenceTracker();
    long a = t.next();
    long b = t.next();
    long c = t.next();
    assertTrue(b > a);
    assertTrue(c > b);
  }

  @Test
  @DisplayName("current() returns last issued, 0 before any next()")
  void currentReflectsLast() {
    StreamSequenceTracker t = new StreamSequenceTracker();
    assertEquals(0L, t.current());
    t.next();
    assertEquals(1L, t.current());
    long n = t.next();
    assertEquals(n, t.current());
  }
}
