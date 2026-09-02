/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 885 item 3: the in-flight foreground gauge. */
final class ForegroundLoadTest {

  @Test
  @DisplayName("starts at zero in-flight with no recorded foreground timestamp")
  void startsEmpty() {
    ForegroundLoad load = new ForegroundLoad();
    assertEquals(0, load.inFlight());
    assertEquals(0L, load.lastForegroundAtMs());
    assertEquals(0L, load.startedTotal());
  }

  @Test
  @DisplayName("balanced start/finish pairs return the gauge to zero and count the calls")
  void balancedPairs() {
    ForegroundLoad load = new ForegroundLoad();
    load.started();
    load.started();
    assertEquals(2, load.inFlight());
    load.finished();
    assertEquals(1, load.inFlight());
    load.finished();
    assertEquals(0, load.inFlight());
    assertEquals(2L, load.startedTotal());
  }

  @Test
  @DisplayName("an unbalanced decrement clamps at zero — a negative gauge would read as never busy")
  void clampsAtZero() {
    ForegroundLoad load = new ForegroundLoad();
    load.finished();
    load.finished();
    assertEquals(0, load.inFlight());
    load.started();
    assertEquals(1, load.inFlight(), "the gauge must still be usable after an unbalanced decrement");
  }

  @Test
  @DisplayName("both start and completion stamp lastForegroundAtMs (the cooldown anchor)")
  void stampsBothEdges() {
    AtomicLong clock = new AtomicLong(1_000L);
    ForegroundLoad load = new ForegroundLoad(clock::get);
    load.started();
    assertEquals(1_000L, load.lastForegroundAtMs());
    clock.set(1_700L);
    load.finished();
    assertEquals(1_700L, load.lastForegroundAtMs());
  }
}
