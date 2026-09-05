/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSearchControllerFeedbackProjectionTest {

  @Test
  void narrowProjectionFetchesCaptureFieldsInternallyButResponseStripsInjectedFields() {
    List<String> requested = List.of("title", "path");
    assertEquals(
        List.of("title", "path", "doc_uid", "content_sha256"),
        KnowledgeSearchController.withFeedbackUid(requested));

    KnowledgeSearchResponse.Hit hit =
        KnowledgeSearchResponseHitBuilder.builder()
            .id("C:/docs/a.md")
            .fields(
                Map.of(
                    "title",
                    "A",
                    "path",
                    "C:/docs/a.md",
                    "doc_uid",
                    "uid-a",
                    "content_sha256",
                    "rev-a"))
            .build();

    KnowledgeSearchResponse.Hit projected =
        KnowledgeSearchController.withoutInternalFields(
                List.of(hit), KnowledgeSearchController.injectedFeedbackFields(requested))
            .getFirst();

    assertEquals(Map.of("title", "A", "path", "C:/docs/a.md"), projected.fields());
    assertEquals(hit.id(), projected.id());
  }

  @Test
  void aFieldTheCallerAskedForByNameIsNotStripped() {
    List<String> requested = List.of("title", "content_sha256");
    assertEquals(
        List.of("title", "content_sha256", "doc_uid"),
        KnowledgeSearchController.withFeedbackUid(requested));
    assertEquals(List.of("doc_uid"), KnowledgeSearchController.injectedFeedbackFields(requested));

    KnowledgeSearchResponse.Hit projected =
        KnowledgeSearchController.withoutInternalFields(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("d")
                        .fields(Map.of("title", "A", "content_sha256", "rev-a", "doc_uid", "uid-a"))
                        .build()),
                KnowledgeSearchController.injectedFeedbackFields(requested))
            .getFirst();

    assertTrue(
        projected.fields().containsKey("content_sha256"),
        "an explicitly requested capture field stays on the response");
    assertFalse(projected.fields().containsKey("doc_uid"));
  }

  @Test
  void emptyOrFullyExplicitProjectionIsUnchanged() {
    List<String> empty = List.of();
    List<String> explicit = List.of("title", "doc_uid", "content_sha256");

    assertSame(empty, KnowledgeSearchController.withFeedbackUid(empty));
    assertSame(explicit, KnowledgeSearchController.withFeedbackUid(explicit));
    // An empty projection already returns every stored field, so nothing was injected and the
    // capture fields are returned as-is rather than silently removed from the public contract.
    assertEquals(List.of(), KnowledgeSearchController.injectedFeedbackFields(empty));
    assertEquals(List.of(), KnowledgeSearchController.injectedFeedbackFields(explicit));

    KnowledgeSearchResponse.Hit hit =
        KnowledgeSearchResponseHitBuilder.builder()
            .id("d")
            .fields(Map.of("title", "A", "doc_uid", "uid-a", "content_sha256", "rev-a"))
            .build();
    assertSame(
        hit,
        KnowledgeSearchController.withoutInternalFields(
                List.of(hit), KnowledgeSearchController.injectedFeedbackFields(empty))
            .getFirst());
  }
}
