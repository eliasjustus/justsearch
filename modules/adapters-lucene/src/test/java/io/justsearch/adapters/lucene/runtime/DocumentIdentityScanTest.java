/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("DocumentFieldOps identity recovery scan")
final class DocumentIdentityScanTest {

  @Test
  @DisplayName("identity recovery scans parents and excludes their chunks")
  void scanReturnsOnlyParentIdentities(@TempDir Path tempDir) throws Exception {
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open()) {
      String parentId = "parent.txt";
      String parentUid = "00000000-0000-4000-8000-000000000220";
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID,
                      parentId,
                      SchemaFields.DOC_UID,
                      parentUid,
                      SchemaFields.CONTENT,
                      "parent")));
      for (int i = 0; i < 2; i++) {
        runtime
            .indexingCoordinator()
            .indexSingle(
                new IndexDocument(
                    Map.of(
                        SchemaFields.DOC_ID,
                        "chunk-" + i,
                        SchemaFields.DOC_UID,
                        parentUid + "#" + i,
                        SchemaFields.IS_CHUNK,
                        "true",
                        SchemaFields.PARENT_DOC_ID,
                        parentId,
                        SchemaFields.CHUNK_INDEX,
                        String.valueOf(i),
                        SchemaFields.CONTENT,
                        "chunk " + i)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var identities = runtime.documentFieldOps().listParentDocumentIdentities();

      assertEquals(1, identities.size());
      assertEquals(parentId, identities.getFirst().docId());
      assertEquals(parentUid, identities.getFirst().docUid());
    }
  }

  @Test
  @DisplayName("a live parent missing doc_uid fails recovery instead of being silently omitted")
  void missingParentUidFailsClosed(@TempDir Path tempDir) throws Exception {
    try (RunningRuntime runtime =
        IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open()) {
      Document malformed = new Document();
      malformed.add(new StringField(SchemaFields.DOC_ID, "malformed-parent", Field.Store.YES));
      malformed.add(
          new SortedDocValuesField(SchemaFields.DOC_ID, new BytesRef("malformed-parent")));
      new LifecycleTestAccessor(runtime).addRawDocument(malformed);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      IndexRuntimeIOException error =
          assertThrows(
              IndexRuntimeIOException.class,
              () -> runtime.documentFieldOps().listParentDocumentIdentities());
      assertEquals(IndexRuntimeIOException.Reason.CORRUPT_INDEX, error.reason());
    }
  }
}
