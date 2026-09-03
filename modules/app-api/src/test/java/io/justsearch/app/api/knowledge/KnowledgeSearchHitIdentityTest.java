/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSearchHitIdentityTest {

  @Test
  void wholeDocumentUsesStoredUidAndHitId() {
    KnowledgeSearchResponse.Hit hit = hit("C:/docs/a.md", Map.of("doc_uid", "stable-a"));

    assertEquals("C:/docs/a.md", KnowledgeSearchHitIdentity.sourceDocId(hit));
    assertEquals("stable-a", KnowledgeSearchHitIdentity.stableParentDocUid(hit));

    KnowledgeSearchResponse.Hit enriched =
        hit(
            "chunk-internal-id",
            Map.of(
                "parent_doc_id", "C:/docs/a.md",
                "chunk_index", "12",
                "doc_uid", "stable-a"));
    assertEquals("stable-a", KnowledgeSearchHitIdentity.stableParentDocUid(enriched));
  }

  @Test
  void chunkUsesParentPathAndVerifiedParentUid() {
    KnowledgeSearchResponse.Hit hit =
        hit(
            "chunk-internal-id",
            Map.of(
                "parent_doc_id", "C:/docs/a.md",
                "chunk_index", "12",
                "doc_uid", "stable-a#12"));

    assertEquals("C:/docs/a.md", KnowledgeSearchHitIdentity.sourceDocId(hit));
    assertEquals("stable-a", KnowledgeSearchHitIdentity.stableParentDocUid(hit));
  }

  @Test
  void inconsistentChunkIdentityFailsClosed() {
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit(
                "chunk",
                Map.of(
                    "parent_doc_id", "C:/docs/a.md",
                    "chunk_index", "3",
                    "doc_uid", "stable-a#2"))));
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit(
                "chunk",
                Map.of(
                    "parent_doc_id", "C:/docs/a.md",
                    "chunk_index", "not-an-index",
                    "doc_uid", "stable-a#0"))));
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit(
                "chunk",
                Map.of(
                    "parent_doc_id", "C:/docs/a.md",
                    "chunk_index", "4",
                    "doc_uid", "stable-a#4#4"))));
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit("chunk", Map.of("parent_doc_id", "C:/docs/a.md", "chunk_index", "0"))));
  }

  @Test
  void orphanedChunkMarkersCannotMasqueradeAsWholeDocumentUid() {
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit("collapsed", Map.of("doc_uid", "stable-a#3"))));
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit("collapsed", Map.of("doc_uid", "stable-a", "chunk_index", "3"))));
    assertNull(
        KnowledgeSearchHitIdentity.stableParentDocUid(
            hit("collapsed", Map.of("doc_uid", "stable-a", "is_chunk", "true"))));
  }

  private static KnowledgeSearchResponse.Hit hit(String id, Map<String, String> fields) {
    return KnowledgeSearchResponseHitBuilder.builder().id(id).fields(fields).build();
  }
}
