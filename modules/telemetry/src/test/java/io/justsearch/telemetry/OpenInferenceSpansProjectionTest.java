package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.telemetry.OpenInferenceSpans.Doc;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 553 Phase A/D — conformance test for the shared OpenInference span projector used by the
 * worker {@code search/*} spans and the head {@code search/cross_encoder|lambdamart|rerank} spans.
 * Pins the OpenInference key vocabulary + bounded document encoding in one place so every surface
 * projects identically (no per-module fork). The named guard for the otel-spans-* projection surfaces.
 */
@DisplayName("OpenInferenceSpans: the shared search-span document projector")
final class OpenInferenceSpansProjectionTest {

  @Test
  @DisplayName("retriever(): RETRIEVER kind + per-document id/score/content")
  void retrieverProjectsDocuments() {
    Attributes a = OpenInferenceSpans.retriever(List.of(new Doc("doc-1", 1.5, "hello world")));
    assertEquals("RETRIEVER", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertEquals("doc-1", a.get(AttributeKey.stringKey("retrieval.documents.0.document.id")));
    assertEquals(1.5, a.get(AttributeKey.doubleKey("retrieval.documents.0.document.score")), 1e-6);
    assertEquals(
        "hello world", a.get(AttributeKey.stringKey("retrieval.documents.0.document.content")));
  }

  @Test
  @DisplayName("reranker(): RERANKER kind + model + input count + output documents")
  void rerankerProjectsOutput() {
    Attributes a =
        OpenInferenceSpans.reranker("cross-encoder", 8, List.of(new Doc("d9", 0.9, "fused")));
    assertEquals("RERANKER", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertEquals("cross-encoder", a.get(AttributeKey.stringKey("reranker.model_name")));
    assertEquals(8L, a.get(AttributeKey.longKey("reranker.input_branch_count")));
    assertEquals("d9", a.get(AttributeKey.stringKey("reranker.output_documents.0.document.id")));
    assertEquals(
        0.9, a.get(AttributeKey.doubleKey("reranker.output_documents.0.document.score")), 1e-6);
  }

  @Test
  @DisplayName("content-only docs (null id, e.g. cross-encoder texts) omit the id attribute")
  void contentOnlyDocOmitsId() {
    Attributes a = OpenInferenceSpans.reranker("cross-encoder", 1, List.of(new Doc(null, 0.5, "txt")));
    assertNull(a.get(AttributeKey.stringKey("reranker.output_documents.0.document.id")));
    assertEquals(
        "txt", a.get(AttributeKey.stringKey("reranker.output_documents.0.document.content")));
  }

  @Test
  @DisplayName("chain(): CHAIN kind, no documents")
  void chainIsStructural() {
    Attributes a = OpenInferenceSpans.chain();
    assertEquals("CHAIN", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertNull(a.get(AttributeKey.stringKey("retrieval.documents.0.document.id")));
  }

  @Test
  @DisplayName("documents bounded to MAX_DOCUMENTS, content to MAX_CONTENT_CHARS")
  void boundsEnforced() {
    List<Doc> many = new ArrayList<>();
    for (int i = 0; i < OpenInferenceSpans.MAX_DOCUMENTS + 5; i++) {
      many.add(new Doc("d" + i, i, "x".repeat(OpenInferenceSpans.MAX_CONTENT_CHARS + 50)));
    }
    Attributes a = OpenInferenceSpans.retriever(many);
    int last = OpenInferenceSpans.MAX_DOCUMENTS - 1;
    assertEquals(
        "d" + last, a.get(AttributeKey.stringKey("retrieval.documents." + last + ".document.id")));
    assertNull(
        a.get(
            AttributeKey.stringKey(
                "retrieval.documents." + OpenInferenceSpans.MAX_DOCUMENTS + ".document.id")));
    assertEquals(
        OpenInferenceSpans.MAX_CONTENT_CHARS,
        a.get(AttributeKey.stringKey("retrieval.documents.0.document.content")).length());
  }

  @Test
  @DisplayName("null doc list → only the span-kind discriminator")
  void nullListSafe() {
    Attributes a = OpenInferenceSpans.retriever(null);
    assertEquals("RETRIEVER", a.get(AttributeKey.stringKey("openinference.span.kind")));
    assertTrue(
        a.asMap().keySet().stream().noneMatch(k -> k.getKey().startsWith("retrieval.documents.")));
  }
}
