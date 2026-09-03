package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.indexing.SchemaFields;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DocumentFieldOpsChunkSliceTest {

  @Test
  void resolvesEachDistinctParentOnceWithExactUtf16Slices() throws Exception {
    String firstParent = "🚀 alpha\r\nbeta ";
    String secondParent = "```java\r\nsecond();\r\n```";
    Map<String, DocumentFieldOps.ChunkSlice> chunks = new LinkedHashMap<>();
    chunks.put("a", new DocumentFieldOps.ChunkSlice("p1", 0, 2));
    chunks.put("b", new DocumentFieldOps.ChunkSlice("p1", 3, 8));
    chunks.put("c", new DocumentFieldOps.ChunkSlice("p2", 0, secondParent.length()));
    chunks.put("missing", new DocumentFieldOps.ChunkSlice("absent", 0, 1));
    chunks.put("out-of-range", new DocumentFieldOps.ChunkSlice("p1", 0, firstParent.length() + 1));

    Map<String, Integer> reads = new HashMap<>();
    Map<String, String> resolved =
        DocumentFieldOps.resolveChunkContents(
            chunks,
            Map.of(),
            parentId -> {
              reads.merge(parentId, 1, Integer::sum);
              return Map.of("p1", firstParent, "p2", secondParent).get(parentId);
            });

    assertEquals("🚀", resolved.get("a"));
    assertEquals("alpha", resolved.get("b"));
    assertEquals(secondParent, resolved.get("c"));
    assertNull(resolved.get("missing"));
    assertNull(resolved.get("out-of-range"));
    assertEquals(Map.of("p1", 1, "p2", 1, "absent", 1), reads);
  }

  @Test
  void knownParentContentNeedsNoReadAndMalformedGeometryIsRejected() throws Exception {
    Map<String, DocumentFieldOps.ChunkSlice> chunks =
        Map.of("known", new DocumentFieldOps.ChunkSlice("p", 1, 4));

    Map<String, String> resolved =
        DocumentFieldOps.resolveChunkContents(
            chunks,
            Map.of("p", " abc "),
            ignored -> {
              throw new AssertionError("known parent must not be loaded again");
            });

    assertEquals("abc", resolved.get("known"));
    assertNull(
        DocumentFieldOps.chunkSliceFrom(
            Map.of(
                SchemaFields.PARENT_DOC_ID,
                "p",
                SchemaFields.CHUNK_START_CHAR,
                "-1",
                SchemaFields.CHUNK_END_CHAR,
                "2")));
    assertNull(
        DocumentFieldOps.chunkSliceFrom(
            Map.of(
                SchemaFields.PARENT_DOC_ID,
                "p",
                SchemaFields.CHUNK_START_CHAR,
                "4",
                SchemaFields.CHUNK_END_CHAR,
                "3")));
  }
}
