package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationHistoryStore")
final class OperationHistoryStoreTest {

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
  @DisplayName("default capacity is 200")
  void defaultCapacity() {
    assertEquals(200, new OperationHistoryStore().capacity());
  }

  @Test
  @DisplayName("constructor rejects non-positive capacity")
  void rejectsBadCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new OperationHistoryStore(0));
    assertThrows(IllegalArgumentException.class, () -> new OperationHistoryStore(-5));
  }

  @Test
  @DisplayName("append + recent returns chronological order")
  void appendChronological() {
    OperationHistoryStore store = new OperationHistoryStore(10);
    store.append(entry(1));
    store.append(entry(2));
    store.append(entry(3));
    List<OperationHistoryEntry> recent = store.recent();
    assertEquals(3, recent.size());
    assertEquals("core.test-1", recent.get(0).operationId().value());
    assertEquals("core.test-3", recent.get(2).operationId().value());
  }

  @Test
  @DisplayName("ring buffer evicts oldest when capacity exceeded")
  void ringBufferEviction() {
    OperationHistoryStore store = new OperationHistoryStore(3);
    for (int i = 1; i <= 5; i++) {
      store.append(entry(i));
    }
    List<OperationHistoryEntry> recent = store.recent();
    assertEquals(3, recent.size());
    assertEquals("core.test-3", recent.get(0).operationId().value());
    assertEquals("core.test-5", recent.get(2).operationId().value());
  }

  @Test
  @DisplayName("recent(n) returns last n entries")
  void recentN() {
    OperationHistoryStore store = new OperationHistoryStore(10);
    for (int i = 1; i <= 5; i++) {
      store.append(entry(i));
    }
    List<OperationHistoryEntry> last2 = store.recent(2);
    assertEquals(2, last2.size());
    assertEquals("core.test-4", last2.get(0).operationId().value());
    assertEquals("core.test-5", last2.get(1).operationId().value());
  }

  @Test
  @DisplayName("recent(0) returns empty")
  void recentZero() {
    OperationHistoryStore store = new OperationHistoryStore(10);
    store.append(entry(1));
    assertTrue(store.recent(0).isEmpty());
  }

  @Test
  @DisplayName("recent(n) where n >= size returns full set")
  void recentLargeN() {
    OperationHistoryStore store = new OperationHistoryStore(10);
    store.append(entry(1));
    store.append(entry(2));
    assertEquals(2, store.recent(99).size());
  }

  @Test
  @DisplayName("recent(-1) rejects")
  void recentNegativeRejects() {
    OperationHistoryStore store = new OperationHistoryStore();
    assertThrows(IllegalArgumentException.class, () -> store.recent(-1));
  }

  @Test
  @DisplayName("OperationHistoryEntry rejects null")
  void entryRejectsNull() {
    assertThrows(
        NullPointerException.class,
        () ->
            new OperationHistoryEntry(
                null,
                "head",
                Optional.empty(),
                Instant.now(),
                Instant.now(),
                OperationOutcome.SUCCESS,
                Optional.empty(),
                InvocationProvenance.systemInternal(Instant.now()),
                Optional.empty()));
  }

  @Test
  @DisplayName("OperationHistoryEntry rejects blank actor")
  void entryRejectsBlankActor() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new OperationHistoryEntry(
                new OperationRef("core.test"),
                "  ",
                Optional.empty(),
                Instant.now(),
                Instant.now(),
                OperationOutcome.SUCCESS,
                Optional.empty(),
                InvocationProvenance.systemInternal(Instant.now()),
                Optional.empty()));
  }

  @Test
  @DisplayName("OperationHistoryEntry rejects null provenance (slice 490 required field)")
  void entryRejectsNullProvenance() {
    assertThrows(
        NullPointerException.class,
        () ->
            new OperationHistoryEntry(
                new OperationRef("core.test"),
                "head",
                Optional.empty(),
                Instant.now(),
                Instant.now(),
                OperationOutcome.SUCCESS,
                Optional.empty(),
                null,
                Optional.empty()));
  }
}
