package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 541 fix-pass Tier 4 — RebuildHistory ring buffer unit coverage. */
@DisplayName("RebuildHistory — tempdoc 541 Tier 4 (C-revised) ring buffer")
class RebuildHistoryTest {

  @Test
  @DisplayName("default capacity is 20")
  void defaultCapacity() {
    RebuildHistory rh = new RebuildHistory();
    assertEquals(20, rh.capacity());
    assertEquals(0, rh.size());
    assertEquals(0L, rh.totalAppends());
  }

  @Test
  @DisplayName("append below capacity grows; snapshot returns oldest→newest")
  void appendBelowCapacity() {
    RebuildHistory rh = new RebuildHistory(5);
    for (int i = 0; i < 3; i++) {
      rh.record(PhaseRecord.ready("event-" + i, i, i + 1, null));
    }
    assertEquals(3, rh.size());
    assertEquals(3L, rh.totalAppends());
    var snap = rh.snapshot();
    assertEquals("event-0", snap.get(0).name());
    assertEquals("event-1", snap.get(1).name());
    assertEquals("event-2", snap.get(2).name());
  }

  @Test
  @DisplayName("at capacity, append evicts oldest")
  void evictionAtCapacity() {
    RebuildHistory rh = new RebuildHistory(3);
    for (int i = 0; i < 5; i++) {
      rh.record(PhaseRecord.ready("event-" + i, i, i + 1, null));
    }
    assertEquals(3, rh.size(), "size stays at capacity");
    assertEquals(5L, rh.totalAppends(), "total reflects all appends including evicted");
    var snap = rh.snapshot();
    assertEquals("event-2", snap.get(0).name(), "oldest retained is event-2 (0,1 evicted)");
    assertEquals("event-3", snap.get(1).name());
    assertEquals("event-4", snap.get(2).name());
  }

  @Test
  @DisplayName("null record is silently dropped")
  void nullRecordSilentlyDropped() {
    RebuildHistory rh = new RebuildHistory(5);
    rh.record(null);
    assertEquals(0, rh.size());
    assertEquals(0L, rh.totalAppends());
  }

  @Test
  @DisplayName("snapshot returns an unmodifiable defensive copy")
  void snapshotDefensive() {
    RebuildHistory rh = new RebuildHistory(5);
    rh.record(PhaseRecord.ready("a", 0, 1, null));
    var snap = rh.snapshot();
    assertThrows(
        UnsupportedOperationException.class,
        () -> snap.add(PhaseRecord.ready("b", 1, 2, null)));
    // Subsequent appends don't affect the prior snapshot.
    rh.record(PhaseRecord.ready("c", 2, 3, null));
    assertEquals(1, snap.size());
    assertEquals(2, rh.snapshot().size());
  }

  @Test
  @DisplayName("capacity < 1 is rejected")
  void rejectInvalidCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new RebuildHistory(0));
    assertThrows(IllegalArgumentException.class, () -> new RebuildHistory(-1));
  }
}
