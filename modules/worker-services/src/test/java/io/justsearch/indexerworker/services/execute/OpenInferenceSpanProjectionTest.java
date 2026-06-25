package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.telemetry.OpenInferenceSpans;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 553 Phase A — verifies the worker {@code search/*} spans project their leg/fusion result
 * onto OpenInference attributes. The projection is a pure function of the {@link SearchResult}, so
 * its record-derived content is asserted here without a live backend; {@code SearchExecutor} applies
 * the result to the live spans (topology + live application covered by
 * {@code SearchExecutorOtelTopologyTest}).
 */
@DisplayName("OpenInferenceSpanProjection: spans project the leg/fusion result they produced")
final class OpenInferenceSpanProjectionTest {

  private static SearchResult resultOf(SearchHit... hits) {
    return new SearchResult(List.of(hits), hits.length, 1L);
  }

  private static SearchHit hit(String id, float score, String content) {
    return new SearchHit(id, score, Map.of(SchemaFields.CONTENT, content));
  }

  @Test
  @DisplayName("retriever(): RETRIEVER kind + per-document id/score/content")
  void retrieverProjectsDocuments() {
    Attributes a =
        OpenInferenceSpanProjection.retriever(resultOf(hit("doc-1", 1.5f, "hello world")));

    assertEquals("RETRIEVER", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertEquals("doc-1", a.get(AttributeKey.stringKey("retrieval.documents.0.document.id")));
    assertEquals(
        1.5, a.get(AttributeKey.doubleKey("retrieval.documents.0.document.score")), 0.0001);
    assertEquals(
        "hello world", a.get(AttributeKey.stringKey("retrieval.documents.0.document.content")));
  }

  @Test
  @DisplayName("reranker(): RERANKER kind + model_name + input_branch_count + output documents")
  void rerankerProjectsFusedOutput() {
    Attributes a =
        OpenInferenceSpanProjection.reranker("cc", 3, resultOf(hit("doc-9", 0.9f, "fused")));

    assertEquals("RERANKER", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertEquals("cc", a.get(AttributeKey.stringKey("reranker.model_name")));
    assertEquals(3L, a.get(AttributeKey.longKey("reranker.input_branch_count")));
    assertEquals(
        "doc-9", a.get(AttributeKey.stringKey("reranker.output_documents.0.document.id")));
    assertEquals(
        0.9, a.get(AttributeKey.doubleKey("reranker.output_documents.0.document.score")), 0.0001);
  }

  @Test
  @DisplayName("chain(): CHAIN kind, no documents")
  void chainIsStructural() {
    Attributes a = OpenInferenceSpanProjection.chain();
    assertEquals("CHAIN", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertNull(a.get(AttributeKey.stringKey("retrieval.documents.0.document.id")));
  }

  @Test
  @DisplayName("documents are bounded to MAX_DOCUMENTS and content to MAX_CONTENT_CHARS")
  void boundsAreEnforced() {
    List<SearchHit> many = new ArrayList<>();
    for (int i = 0; i < OpenInferenceSpans.MAX_DOCUMENTS + 5; i++) {
      many.add(hit("doc-" + i, i, "x".repeat(OpenInferenceSpans.MAX_CONTENT_CHARS + 50)));
    }
    Attributes a =
        OpenInferenceSpanProjection.retriever(new SearchResult(many, many.size(), 1L));

    // Last in-bounds doc present; first out-of-bounds doc absent.
    int last = OpenInferenceSpans.MAX_DOCUMENTS - 1;
    assertEquals(
        "doc-" + last,
        a.get(AttributeKey.stringKey("retrieval.documents." + last + ".document.id")));
    assertNull(
        a.get(
            AttributeKey.stringKey(
                "retrieval.documents."
                    + OpenInferenceSpans.MAX_DOCUMENTS
                    + ".document.id")));
    String content = a.get(AttributeKey.stringKey("retrieval.documents.0.document.content"));
    assertEquals(OpenInferenceSpans.MAX_CONTENT_CHARS, content.length());
  }

  @Test
  @DisplayName("null / empty result → only the span-kind discriminator, no documents")
  void nullResultIsSafe() {
    Attributes a = OpenInferenceSpanProjection.retriever(null);
    assertEquals("RETRIEVER", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertTrue(
        a.asMap().keySet().stream().noneMatch(k -> k.getKey().startsWith("retrieval.documents.")));
  }
}
