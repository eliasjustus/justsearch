package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.PromptFragment;
import io.justsearch.agent.api.registry.Audience;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RAGQAStyle} (slice 491 C3). */
final class RAGQAStyleTest {

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.rag-qa-style", RAGQAStyle.ID);
  }

  @Test
  @DisplayName("Singleton instance is reused")
  void singletonInstance() {
    assertNotNull(RAGQAStyle.INSTANCE);
    assertEquals(RAGQAStyle.INSTANCE, RAGQAStyle.INSTANCE);
  }

  @Test
  @DisplayName("contribute returns the RAG-ask prompt with priority 10")
  void contributeReturnsPromptAtPriority10() {
    PromptFragment fragment = RAGQAStyle.INSTANCE.contribute(stubCtx()).orElseThrow();
    assertEquals(10, fragment.priority());
    assertTrue(fragment.text().contains("documents"), "should reference documents");
    assertTrue(
        fragment.text().toLowerCase().contains("not in the documents"),
        "should instruct on missing-answer behavior");
  }

  @Test
  @DisplayName("contribute is stateless — same result regardless of request body")
  void stateless() {
    var ctxA = stubCtxWithBody(Map.of("question", "foo"));
    var ctxB = stubCtxWithBody(Map.of("question", "bar", "extra", "data"));
    assertEquals(
        RAGQAStyle.INSTANCE.contribute(ctxA).orElseThrow().text(),
        RAGQAStyle.INSTANCE.contribute(ctxB).orElseThrow().text());
  }

  private static ConversationContext stubCtx() {
    return stubCtxWithBody(Map.of());
  }

  private static ConversationContext stubCtxWithBody(Map<String, Object> body) {
    return new ConversationContext() {
      private final Map<String, Object> attrs = new HashMap<>();

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
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
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return Map.copyOf(body);
      }

      @Override
      public Map<String, Object> attributes() {
        return attrs;
      }
    };
  }
}
