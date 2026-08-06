package io.justsearch.app.observability.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
        id, Instant.parse(at), "system", "WORKER_INDEXER", "h-" + id, "default", "DONE", 0, "", "");
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

  @Test
  @DisplayName("append reports whether the id was newly added (tempdoc 812: the journal's write gate)")
  void appendReportsWhetherItAdded() {
    ActionEventStore store = new ActionEventStore(4);
    assertTrue(store.append(op("a", "2026-05-26T00:00:00Z")), "a new id is added");
    assertFalse(store.append(op("a", "2026-05-26T00:00:05Z")), "a duplicate id is not re-added");
    assertFalse(store.append(null), "a null event is not added");
  }

  @Test
  @DisplayName("the actor cliff is not silent: one WARN per episode, counted, re-armed by an index eviction")
  void actorEvictionWarnsOncePerEpisode() {
    Logger ringLogger = (Logger) LoggerFactory.getLogger(ActionEventStore.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    ringLogger.addAppender(captured);
    try {
      // Capacity 2, actor rows only: every append past capacity evicts an actor row.
      ActionEventStore store = new ActionEventStore(2);
      for (int i = 0; i < 12; i++) {
        store.append(op("a" + i, "2026-05-26T00:00:0" + (i % 10) + "Z"));
      }
      assertEquals(10, store.actorEvictions(), "every actor eviction is counted");
      assertEquals(
          1,
          warnCount(captured),
          "ten actor evictions in one episode produce ONE warning, not ten");

      // An index eviction closes the episode. (At capacity 2 with the ring full of actor rows, the
      // incoming index row is itself the oldest INDEX, so index-first eviction sacrifices it — an
      // index eviction either way, which is exactly the "pressure is back on index rows" signal.)
      store.append(idx("i1", "2026-05-26T00:01:00Z"));
      store.append(idx("i2", "2026-05-26T00:01:01Z"));
      assertEquals(1, warnCount(captured), "an index eviction re-arms the warning, it does not emit one");
      store.append(op("z1", "2026-05-26T00:02:00Z"));
      store.append(op("z2", "2026-05-26T00:02:01Z"));
      assertEquals(2, warnCount(captured), "the next actor cliff is reported as a new episode");
    } finally {
      ringLogger.detachAppender(captured);
    }
  }

  private static long warnCount(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
  }
}
