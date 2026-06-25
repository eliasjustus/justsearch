package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ChunkDocumentWriter (Tier 2)")
final class ChunkDocumentWriterTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  @Test
  @DisplayName("regenerateChunksFromExistingParent writes offsets and inherits metadata")
  void regenerateChunksFromExistingParentWritesOffsetsAndInheritsMetadata() throws Exception {
    String parentDocId = "d:/docs/report.pdf";
    String mime = "application/pdf";
    String mimeBase = "application/pdf";
    String fileKind = "pdf";
    long parentTokenCount = 2048L;

    String content = "     " + repeat("lorem ipsum ", 600);
    assertTrue(content.length() > ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS);

    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, parentDocId,
        SchemaFields.DOC_UID, parentDocId + "#0",
        SchemaFields.PATH, parentDocId,
        SchemaFields.CONTENT, content,
        SchemaFields.MIME, mime,
        SchemaFields.MIME_BASE, mimeBase,
        SchemaFields.FILE_KIND, fileKind,
        SchemaFields.LANGUAGE, "en-US",
        SchemaFields.PARENT_TOKEN_COUNT, String.valueOf(parentTokenCount)
    )));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    int regenerated = ChunkDocumentWriter.regenerateChunksFromExistingParent(lifecycle.documentFieldOps(), lifecycle.indexingCoordinator(), parentDocId, content);
    assertTrue(regenerated > 0);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    List<ChunkSplitter.Chunk> expected =
        ChunkSplitter.splitWithMetadata(content, ChunkDocumentWriter.CHUNK_TARGET_TOKENS, ChunkDocumentWriter.CHUNK_OVERLAP_TOKENS);
    int expectedChunks = expected.size();
    assertTrue(expectedChunks > 1);

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(parentDocId);
    assertEquals(expectedChunks, hits.size());

    // Sort by chunk index so we can compare 1:1.
    hits.sort(Comparator.comparingInt(h -> Integer.parseInt(h.fields().get(SchemaFields.CHUNK_INDEX))));

    // ChunkSplitter now returns offsets relative to the original content,
    // so we don't need to add leading whitespace offset separately.
    for (int i = 0; i < expectedChunks; i++) {
      var hit = hits.get(i);
      var fields = hit.fields();

      assertEquals(parentDocId, fields.get(SchemaFields.PARENT_DOC_ID));
      assertEquals(String.valueOf(expectedChunks), fields.get(SchemaFields.CHUNK_TOTAL));

      String chunkIndexStr = fields.get(SchemaFields.CHUNK_INDEX);
      assertNotNull(chunkIndexStr);
      assertEquals(String.valueOf(i), chunkIndexStr);

      String chunkContent = fields.get(SchemaFields.CHUNK_CONTENT);
      assertNotNull(chunkContent);
      assertEquals(expected.get(i).content(), chunkContent);

      long start = Long.parseLong(fields.get(SchemaFields.CHUNK_START_CHAR));
      long end = Long.parseLong(fields.get(SchemaFields.CHUNK_END_CHAR));
      assertTrue(start >= 0);
      assertTrue(end > start);
      // Offsets from ChunkSplitter are now relative to original content (including leading whitespace)
      assertEquals(expected.get(i).startChar(), start);
      assertEquals(expected.get(i).endChar(), end);

      assertEquals(mime, fields.get(SchemaFields.MIME));
      assertEquals(mimeBase, fields.get(SchemaFields.MIME_BASE));
      assertEquals(fileKind, fields.get(SchemaFields.FILE_KIND));
      assertNotNull(fields.get(SchemaFields.LANGUAGE));
      assertEquals(String.valueOf(parentTokenCount), fields.get(SchemaFields.PARENT_TOKEN_COUNT));

      // Verify offsets slice back to the same chunk text (modulo trim).
      String slice = content.substring((int) start, (int) end).trim();
      assertEquals(chunkContent, slice);
    }
  }

  @Test
  @DisplayName("regenerateChunks writes parent token count from supplied metadata")
  void regenerateChunksWritesParentTokenCountFromMetadata() throws Exception {
    String parentDocId = "d:/docs/guide.md";
    long parentTokenCount = 512L;
    String content = repeat("stage three fusion ", 500);

    int regenerated =
        ChunkDocumentWriter.regenerateChunks(
            lifecycle.documentFieldOps(),
            lifecycle.indexingCoordinator(),
            parentDocId,
            content,
            new ChunkDocumentWriter.ParentChunkMetadata(
                "text/markdown", "text/markdown", "markdown", "en", parentTokenCount));
    assertTrue(regenerated > 0);
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    List<LuceneRuntimeTypes.SearchHit> hits = findChunks(parentDocId);
    assertFalse(hits.isEmpty());
    for (LuceneRuntimeTypes.SearchHit hit : hits) {
      assertEquals(
          String.valueOf(parentTokenCount),
          hit.fields().get(SchemaFields.PARENT_TOKEN_COUNT),
          "chunks must inherit parent_token_count from the supplied metadata");
    }
  }

  private List<LuceneRuntimeTypes.SearchHit> findChunks(String parentDocId) {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER);
    qb.add(new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)), BooleanClause.Occur.FILTER);
    Query q = qb.build();

    Set<String> projection =
        Set.of(
            SchemaFields.PARENT_DOC_ID,
            SchemaFields.CHUNK_INDEX,
            SchemaFields.CHUNK_TOTAL,
            SchemaFields.CHUNK_CONTENT,
            SchemaFields.CHUNK_START_CHAR,
            SchemaFields.CHUNK_END_CHAR,
            SchemaFields.MIME,
            SchemaFields.MIME_BASE,
            SchemaFields.FILE_KIND,
            SchemaFields.LANGUAGE,
            SchemaFields.PARENT_TOKEN_COUNT);

    var result = lifecycle.readPathOps().search(q, 10_000, projection, LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null);
    return new ArrayList<>(result.hits());
  }

  private static String repeat(String s, int times) {
    StringBuilder sb = new StringBuilder(s.length() * Math.max(0, times));
    for (int i = 0; i < times; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
