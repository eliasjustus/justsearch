package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.agent.api.registry.Audience;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 561 P-E — the passive (answer-plane) learning producer's high-precision cue extraction. */
final class MemoryExtractionConsumerTest {

  @Test
  @DisplayName("explicit 'remember that X' directive → persists the fact (stripped of the cue)")
  void remembersDirective() {
    var store = new FakeStore();
    new MemoryExtractionConsumer(store).onDone("ok", ctx("remember that the project ships on Friday"));
    assertEquals(1, store.records.size());
    assertEquals("the project ships on Friday", store.records.get(0).content());
    assertEquals("fact", store.records.get(0).kind());
    assertEquals("chat", store.records.get(0).actor());
  }

  @Test
  @DisplayName("first-person preference → persists verbatim as a preference")
  void remembersPreference() {
    var store = new FakeStore();
    new MemoryExtractionConsumer(store).onDone("ok", ctx("I prefer dark mode and concise answers"));
    assertEquals(1, store.records.size());
    assertEquals("I prefer dark mode and concise answers", store.records.get(0).content());
    assertEquals("preference", store.records.get(0).kind());
  }

  @Test
  @DisplayName("a plain question with no memory cue → persists nothing (high precision)")
  void ignoresPlainTurn() {
    var store = new FakeStore();
    new MemoryExtractionConsumer(store).onDone("ok", ctx("what files did I index yesterday?"));
    assertTrue(store.records.isEmpty());
  }

  @Test
  @DisplayName("restating the same preference is idempotent (id is a content hash → no duplicate)")
  void idempotentOnRestate() {
    var store = new FakeStore();
    var consumer = new MemoryExtractionConsumer(store);
    consumer.onDone("ok", ctx("I prefer dark mode"));
    consumer.onDone("ok", ctx("I prefer dark mode"));
    assertEquals(1, store.records.size());
  }

  private static ConversationContext ctx(String userMessage) {
    List<Map<String, Object>> messages =
        List.of(Map.of("role", "user", "content", userMessage));
    return new ConversationContext() {
      private final Map<String, Object> attrs = new HashMap<>();

      @Override
      public List<Map<String, Object>> messages() {
        return messages;
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return "conv-1";
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }

  /** A list-backed MemoryStore, idempotent on id (mirrors FileMemoryStore semantics). */
  private static final class FakeStore implements MemoryStore {
    private final Map<String, MemoryRecord> byId = new LinkedHashMap<>();
    final List<MemoryRecord> records = new ArrayList<>();

    @Override
    public void remember(MemoryRecord record) {
      if (byId.putIfAbsent(record.id(), record) == null) {
        records.add(record);
      }
    }

    @Override
    public List<MemoryRecord> whatItKnows() {
      return List.copyOf(records);
    }

    @Override
    public void forget(String id) {
      if (byId.remove(id) != null) {
        records.removeIf(r -> r.id().equals(id));
      }
    }

    @Override
    public void clear() {
      byId.clear();
      records.clear();
    }
  }
}
