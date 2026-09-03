/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSearchControllerFeedbackProjectionTest {

  @Test
  void narrowProjectionFetchesUidInternallyButResponseStripsInjectedField() {
    assertEquals(
        List.of("title", "path", "doc_uid"),
        KnowledgeSearchController.withFeedbackUid(List.of("title", "path")));

    KnowledgeSearchResponse.Hit hit =
        KnowledgeSearchResponseHitBuilder.builder()
            .id("C:/docs/a.md")
            .fields(Map.of("title", "A", "path", "C:/docs/a.md", "doc_uid", "uid-a"))
            .build();

    KnowledgeSearchResponse.Hit projected =
        KnowledgeSearchController.withoutInternalDocUid(List.of(hit)).getFirst();

    assertEquals(Map.of("title", "A", "path", "C:/docs/a.md"), projected.fields());
    assertEquals(hit.id(), projected.id());
  }

  @Test
  void emptyOrUidExplicitProjectionIsUnchanged() {
    List<String> empty = List.of();
    List<String> explicit = List.of("title", "doc_uid");

    assertSame(empty, KnowledgeSearchController.withFeedbackUid(empty));
    assertSame(explicit, KnowledgeSearchController.withFeedbackUid(explicit));
    assertFalse(
        KnowledgeSearchController.withoutInternalDocUid(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("d")
                        .fields(Map.of("title", "A"))
                        .build()))
            .getFirst()
            .fields()
            .containsKey("doc_uid"));
  }
}
