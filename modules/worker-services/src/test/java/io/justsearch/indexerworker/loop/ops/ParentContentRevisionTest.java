/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §C.6 — the parent's content revision is written wherever its content is, from the
 * same string, so feedback can be keyed on {@code (doc_uid, content_revision)}.
 */
final class ParentContentRevisionTest {

  @Test
  void aParentCarriesTheDigestOfTheExactContentItStores() {
    String content = "the parent content this label will be attached to";
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("revision.txt"), new ExtractionResult(content, "revision", "text/plain"));

    Object stored = doc.fields().get(SchemaFields.CONTENT);
    Object revision = doc.fields().get(SchemaFields.CONTENT_SHA256);
    assertNotNull(revision, "a parent written with content must carry its revision");
    assertEquals(
        ChunkParentRevision.sha256Hex((String) stored),
        revision,
        "the digest must be over the STORED content, not the pre-extraction source");
    assertEquals(
        ChunkParentRevision.sha256Hex(content),
        revision,
        "and for a plain-text extraction that stored content is the extracted content verbatim");
  }

  @Test
  void editingTheContentAdvancesTheRevisionWhileTheDigestIsStableForUnchangedContent() {
    String content = "identity survives the edit; the revision must not";
    IndexDocument first =
        TestDocumentBuilder.buildDocument(
            Path.of("revision.txt"), new ExtractionResult(content, "revision", "text/plain"));
    IndexDocument unchanged =
        TestDocumentBuilder.buildDocument(
            Path.of("revision.txt"), new ExtractionResult(content, "revision", "text/plain"));
    IndexDocument edited =
        TestDocumentBuilder.buildDocument(
            Path.of("revision.txt"),
            new ExtractionResult(content + " (edited)", "revision", "text/plain"));

    assertEquals(
        first.fields().get(SchemaFields.CONTENT_SHA256),
        unchanged.fields().get(SchemaFields.CONTENT_SHA256),
        "a re-index of unchanged content must not invalidate labels");
    assertNotEquals(
        first.fields().get(SchemaFields.CONTENT_SHA256),
        edited.fields().get(SchemaFields.CONTENT_SHA256),
        "every content change advances the revision");
  }

  @Test
  void theParentRevisionIsTheSameDigestChunksCarry() {
    // The parent field and chunk_parent_content_sha256 are directly comparable BY CONSTRUCTION —
    // both are ChunkParentRevision.sha256Hex over the parent's stored content. A second digest
    // definition here would make the two silently incomparable.
    String content = "shared digest definition";
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("shared.txt"), new ExtractionResult(content, "shared", "text/plain"));

    assertEquals(
        ChunkParentRevision.sha256Hex((String) doc.fields().get(SchemaFields.CONTENT)),
        doc.fields().get(SchemaFields.CONTENT_SHA256));
    assertEquals(
        64,
        ((String) doc.fields().get(SchemaFields.CONTENT_SHA256)).length(),
        "lowercase SHA-256 hex");
  }
}
