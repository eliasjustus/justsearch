package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.CitationMatchEntry;
import io.justsearch.app.api.DocumentService.CitationMatchResult;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StreamingCitationMatcher} (slice 493). */
final class StreamingCitationMatcherTest {

  @Test
  @DisplayName("ID is stable and namespaced")
  void idIsStable() {
    assertEquals(
        "core.streaming-citation-matcher", StreamingCitationMatcher.ID);
  }

  @Nested
  @DisplayName("onChunk — streaming citation deltas")
  class OnChunkTests {

    @Test
    @DisplayName("returns empty when no citations stashed")
    void noCitations() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var r = matcher.onChunk("Hello world.", stubCtx(Map.of()));
      assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("returns empty when chunk has no sentence boundary")
    void noSentenceBoundary() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "the grass is green")));
      var r = matcher.onChunk("The grass is", ctx);
      assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("emits rag.citation_delta when sentence completes")
    void emitsDeltaOnSentence() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(
          List.of(citation("doc-1", 0, "the grass is green in the field")));

      // First chunk: no sentence boundary
      var r1 = matcher.onChunk("The grass is green", ctx);
      assertTrue(r1.events().isEmpty());

      // Second chunk: sentence completes
      var r2 = matcher.onChunk(" in the field. ", ctx);
      assertEquals(1, r2.events().size());

      SseEvent event = r2.events().get(0);
      assertEquals("rag.citation_delta", event.name());
      assertEquals(0, event.payload().get("sentenceIndex"));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> citations =
          (List<Map<String, Object>>) event.payload().get("citations");
      assertNotNull(citations);
      assertTrue(citations.size() > 0, "should match the citation lexically");
      assertEquals("doc-1", citations.get(0).get("parentDocId"));
    }

    @Test
    @DisplayName("increments sentence index across chunks")
    void sentenceIndexIncrements() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(
          List.of(citation("doc-1", 0, "important information about search")));

      matcher.onChunk("First sentence about search. ", ctx);
      var r = matcher.onChunk("Second sentence about search. ", ctx);

      assertEquals(1, r.events().size());
      assertEquals(1, r.events().get(0).payload().get("sentenceIndex"));
    }

    @Test
    @DisplayName("no delta emitted when sentence doesn't match any citation")
    void noMatchNoDelta() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var ctx = ctxWithCitations(
          List.of(citation("doc-1", 0, "quantum physics entanglement")));

      var r = matcher.onChunk("The weather is nice today. ", ctx);
      assertTrue(r.events().isEmpty(), "no lexical overlap → no delta");
    }
  }

  @Nested
  @DisplayName("onDone — authoritative matching")
  class OnDoneTests {

    @Test
    @DisplayName("emits rag.citation_matches from authoritative matcher")
    void emitsAuthoritativeMatches() {
      var matchResult = new CitationMatchResult(
          List.of(new CitationMatchEntry(0, "Sentence.", 0, 0.9, "doc-1")),
          1, 1, 10L);
      var matcher = new StreamingCitationMatcher(stubDocs(matchResult));
      var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "excerpt")));

      var r = matcher.onDone("Sentence.", ctx);

      assertEquals(1, r.events().size());
      assertEquals("rag.citation_matches", r.events().get(0).name());
    }

    @Test
    @DisplayName("returns empty when fullText is blank")
    void blankText() {
      var matcher = new StreamingCitationMatcher(stubDocs(null));
      var r = matcher.onDone("", stubCtx(Map.of()));
      assertTrue(r.events().isEmpty());
    }

    @Test
    @DisplayName("tolerates service failure non-fatally")
    void serviceFailure() {
      var matcher = new StreamingCitationMatcher(failingDocs());
      var ctx = ctxWithCitations(List.of(citation("doc-1", 0, "excerpt")));
      var r = matcher.onDone("text", ctx);
      assertTrue(r.events().isEmpty());
    }
  }

  @Nested
  @DisplayName("extractCompleteSentences — sentence segmentation")
  class SentenceExtractionTests {

    @Test
    @DisplayName("extracts complete sentences, leaves tail in buffer")
    void basicExtraction() {
      var buf = new StringBuilder("First sentence. Second sentence. Incompl");
      var sentences = StreamingCitationMatcher.extractCompleteSentences(buf);
      assertEquals(2, sentences.size());
      assertEquals("First sentence.", sentences.get(0));
      assertEquals("Second sentence.", sentences.get(1));
      assertEquals("Incompl", buf.toString());
    }

    @Test
    @DisplayName("returns empty for text with no sentence boundary")
    void noSentenceBoundary() {
      var buf = new StringBuilder("hello world");
      var sentences = StreamingCitationMatcher.extractCompleteSentences(buf);
      assertTrue(sentences.isEmpty());
      assertEquals("hello world", buf.toString());
    }

    @Test
    @DisplayName("handles single complete sentence")
    void singleSentence() {
      var buf = new StringBuilder("Hello world. ");
      var sentences = StreamingCitationMatcher.extractCompleteSentences(buf);
      assertEquals(1, sentences.size());
      assertEquals("Hello world.", sentences.get(0));
    }
  }

  @Nested
  @DisplayName("matchSentenceLexical — fast word-overlap matching")
  class LexicalMatchTests {

    @Test
    @DisplayName("matches when significant words overlap")
    void matchesOverlap() {
      var citations = List.of(
          citation("doc-1", 0, "JustSearch indexes your files locally"));
      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "JustSearch indexes files on your machine locally.", citations);
      assertEquals(1, matches.size());
      assertEquals("doc-1", matches.get(0).get("parentDocId"));
    }

    @Test
    @DisplayName("no match when no significant word overlap")
    void noMatch() {
      var citations = List.of(
          citation("doc-1", 0, "quantum physics entanglement theory"));
      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "The weather is nice today.", citations);
      assertTrue(matches.isEmpty());
    }

    @Test
    @DisplayName("skips citations with empty excerpt")
    void emptyExcerpt() {
      var citations = List.of(citation("doc-1", 0, ""));
      var matches = StreamingCitationMatcher.matchSentenceLexical(
          "Any sentence here.", citations);
      assertTrue(matches.isEmpty());
    }
  }

  // -- Test helpers --

  private static ContextCitation citation(String docId, int chunkIdx, String excerpt) {
    return new ContextCitation(
        docId, chunkIdx, 1, 0, 100, 1.0f, excerpt, 0, 10, "", 0);
  }

  private static ConversationContext ctxWithCitations(List<ContextCitation> citations) {
    return stubCtx(Map.of(RAGContext.ATTR_CITATIONS, citations));
  }

  private static ConversationContext stubCtx(Map<String, Object> attrs) {
    return new ConversationContext() {
      private final Map<String, Object> a = new HashMap<>(attrs);

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
        return Map.of();
      }

      @Override
      public Map<String, Object> attributes() {
        return a;
      }
    };
  }

  private static DocumentService stubDocs(CitationMatchResult result) {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitations(
          String answerText, List<ContextCitation> citations, double threshold) {
        return CompletableFuture.completedFuture(result);
      }
    };
  }

  private static DocumentService failingDocs() {
    return new DocumentService() {
      @Override
      public CompletionStage<DocumentRecord> fetch(String docId) {
        return CompletableFuture.failedFuture(new RuntimeException("down"));
      }

      @Override
      public CompletionStage<CitationMatchResult> matchCitations(
          String answerText, List<ContextCitation> citations, double threshold) {
        return CompletableFuture.failedFuture(new RuntimeException("down"));
      }
    };
  }
}
