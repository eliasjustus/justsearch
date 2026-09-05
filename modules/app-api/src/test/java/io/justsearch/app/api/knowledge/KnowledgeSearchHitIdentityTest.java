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

  /**
   * Tempdoc 931 §C.6 — the revision is read verbatim off the hit, for BOTH shapes (the Worker
   * lifts the parent's revision onto a chunk hit), and absence stays absence.
   */
  @Test
  void contentRevisionIsReadForBothHitShapesAndAbsenceStaysNull() {
    assertEquals(
        "rev-a",
        KnowledgeSearchHitIdentity.contentRevision(
            hit("C:/docs/a.md", Map.of("doc_uid", "stable-a", "content_sha256", "rev-a"))));
    assertEquals(
        "rev-a",
        KnowledgeSearchHitIdentity.contentRevision(
            hit(
                "chunk-internal-id",
                Map.of(
                    "parent_doc_id", "C:/docs/a.md",
                    "chunk_index", "12",
                    "doc_uid", "stable-a#12",
                    "content_sha256", "rev-a"))),
        "a chunk hit reports its PARENT's revision — the label ages with the parent document");

    assertNull(
        KnowledgeSearchHitIdentity.contentRevision(hit("C:/docs/a.md", Map.of("doc_uid", "u"))),
        "a document predating the field is unknown, not mismatched");
    assertNull(
        KnowledgeSearchHitIdentity.contentRevision(
            hit("C:/docs/a.md", Map.of("doc_uid", "u", "content_sha256", "   "))),
        "a blank revision is unknown, not a value that could differ from every real one");
    assertNull(KnowledgeSearchHitIdentity.contentRevision(null));
  }

  private static KnowledgeSearchResponse.Hit hit(String id, Map<String, String> fields) {
    return KnowledgeSearchResponseHitBuilder.builder().id(id).fields(fields).build();
  }
}
