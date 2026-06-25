package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 550 thesis I / F1 — the one log is id-keyed (idempotent) + bounded. */
@DisplayName("ActionEventStore — idempotent, bounded one-log")
class ActionEventStoreTest {

  private static ActionEvent op(String id, String at) {
    return new ActionEvent.Operation(
        id,
        Instant.parse(at),
        "agent",
        "AGENT_LOOP",
        "core.x",
        "SUCCESS",
        Optional.empty(),
        Optional.empty());
  }

  private static ActionEvent idx(String id, String at) {
    return new ActionEvent.Index(
        id, Instant.parse(at), "system", "WORKER_INDEXER", "h-" + id, "default", "DONE", 0, "");
  }

  @Test
  @DisplayName("a re-appended duplicate id is ignored (the first event is kept) — F1")
  void idempotentAppendKeepsFirst() {
    ActionEventStore store = new ActionEventStore();
    store.append(op("a", "2026-05-26T00:00:00Z"));
    store.append(op("a", "2026-05-26T00:00:09Z")); // same id (e.g. reload re-POST) — ignored
    store.append(op("b", "2026-05-26T00:00:01Z"));

    List<ActionEvent> rows = store.recent();
    assertEquals(2, rows.size(), "the duplicate id did not add a second row");
    assertEquals(
        "2026-05-26T00:00:00Z",
        rows.get(0).occurredAt().toString(),
        "the FIRST event for the id is kept");
  }

  @Test
  @DisplayName("bounded eviction (eldest-overall fallback, all-actor) prunes the evicted id so it can be re-appended")
  void boundedFifoEvictionPrunesId() {
    ActionEventStore store = new ActionEventStore(2);
    store.append(op("a", "2026-05-26T00:00:00Z"));
    store.append(op("b", "2026-05-26T00:00:01Z"));
    store.append(op("c", "2026-05-26T00:00:02Z")); // over capacity → evicts eldest 'a'

    assertEquals(List.of("b", "c"), store.recent().stream().map(ActionEvent::id).toList());

    // 'a' was evicted, so its id is free again — re-appending it succeeds (no stale id retained).
    store.append(op("a", "2026-05-26T00:00:03Z"));
    assertTrue(store.recent().stream().anyMatch(e -> "a".equals(e.id())));
  }

  @Test
  @DisplayName("index-first eviction: an indexing burst never evicts an actor event")
  void indexBurstNeverEvictsActorEvent() {
    // Capacity 3: one actor (operation) event, then a flood of index events. The op must survive;
    // the oldest INDEX events are sacrificed instead (tempdoc 550 thesis III(b) follow-up).
    ActionEventStore store = new ActionEventStore(3);
    store.append(op("op-1", "2026-05-26T00:00:00Z"));
    for (int i = 0; i < 20; i++) {
      store.append(idx("idx-" + i, "2026-05-26T00:01:" + String.format("%02d", i) + "Z"));
    }

    List<ActionEvent> rows = store.recent();
    assertEquals(3, rows.size(), "still bounded at capacity");
    assertTrue(
        rows.stream().anyMatch(e -> "op-1".equals(e.id())),
        "the actor (operation) event survived the indexing burst");
    // The two surviving index rows are the most recent (oldest index evicted first).
    assertTrue(
        rows.stream().anyMatch(e -> "idx-19".equals(e.id())),
        "the newest index event is retained");
    assertTrue(
        rows.stream().noneMatch(e -> "idx-0".equals(e.id())),
        "the oldest index event was evicted, not the actor event");
  }

  @Test
  @DisplayName("with only index events, eviction is FIFO among them (oldest index first)")
  void onlyIndexEventsEvictOldestFirst() {
    ActionEventStore store = new ActionEventStore(2);
    store.append(idx("a", "2026-05-26T00:00:00Z"));
    store.append(idx("b", "2026-05-26T00:00:01Z"));
    store.append(idx("c", "2026-05-26T00:00:02Z")); // evicts oldest index 'a'
    assertEquals(List.of("b", "c"), store.recent().stream().map(ActionEvent::id).toList());
  }
}
