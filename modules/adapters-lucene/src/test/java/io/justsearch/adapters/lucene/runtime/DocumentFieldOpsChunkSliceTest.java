package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.adapters.lucene.runtime.ChunkReadRevisionGuard.ParentRevision;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DocumentFieldOpsChunkSliceTest {

  @Test
  void resolvesEachDistinctParentOnceWithExactUtf16Slices() throws Exception {
    String firstParent = "🚀 alpha\r\nbeta ";
    String secondParent = "```java\r\nsecond();\r\n```";
    String firstRev = ChunkParentRevision.sha256Hex(firstParent);
    String secondRev = ChunkParentRevision.sha256Hex(secondParent);
    Map<String, DocumentFieldOps.ChunkSlice> chunks = new LinkedHashMap<>();
    chunks.put("a", new DocumentFieldOps.ChunkSlice("p1", 0, 2, firstRev));
    chunks.put("b", new DocumentFieldOps.ChunkSlice("p1", 3, 8, firstRev));
    chunks.put("c", new DocumentFieldOps.ChunkSlice("p2", 0, secondParent.length(), secondRev));
    chunks.put("missing", new DocumentFieldOps.ChunkSlice("absent", 0, 1, firstRev));
    chunks.put(
        "out-of-range",
        new DocumentFieldOps.ChunkSlice("p1", 0, firstParent.length() + 1, firstRev));

    Map<String, Integer> reads = new HashMap<>();
    Map<String, String> resolved =
        DocumentFieldOps.resolveChunkContents(
            chunks,
            Map.of(),
            parentId -> {
              reads.merge(parentId, 1, Integer::sum);
              Map<String, String> byId = Map.of("p1", firstParent, "p2", secondParent);
              String content = byId.get(parentId);
              return content == null ? null : new ParentRevision(content, null);
            },
            null);

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
        Map.of(
            "known",
            new DocumentFieldOps.ChunkSlice("p", 1, 4, ChunkParentRevision.sha256Hex(" abc ")));

    Map<String, String> resolved =
        DocumentFieldOps.resolveChunkContents(
            chunks,
            Map.of("p", new ParentRevision(" abc ", null)),
            ignored -> {
              throw new AssertionError("known parent must not be loaded again");
            },
            null);

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

  /**
   * Tempdoc 931 §E item 5 — the read-side twin of the RMW guard. A parent rewritten to a different
   * revision of the same length still fits the old offsets, so only the revision hash separates the
   * right text from a silently wrong slice of the newer one.
   */
  @Test
  void aParentAtADifferentRevisionYieldsNoTextAndIsCounted() throws Exception {
    String oldParent = "alpha beta";
    String newParent = "gamma zeta";
    assertEquals(oldParent.length(), newParent.length(), "precondition: the old offsets still fit");

    Map<String, DocumentFieldOps.ChunkSlice> chunks = new LinkedHashMap<>();
    chunks.put(
        "chunk-0",
        new DocumentFieldOps.ChunkSlice("p", 0, 5, ChunkParentRevision.sha256Hex(oldParent)));
    chunks.put("legacy", new DocumentFieldOps.ChunkSlice("p", 6, 10, null));

    int[] counted = {0};
    Map<String, String> resolved =
        DocumentFieldOps.resolveChunkContents(
            chunks,
            Map.of(),
            parentId -> new ParentRevision(newParent, null),
            new LuceneRuntimeTypes.TelemetryEvents() {
              @Override
              public void onChunkRevisionMismatch(int count) {
                counted[0] += count;
              }
            });

    assertNull(resolved.get("chunk-0"), "must not return 'gamma' where the chunk says 'alpha'");
    assertNull(resolved.get("legacy"), "a chunk with no revision identity fails closed too");
    assertEquals(2, counted[0], "one count per chunk read that could not be reconstructed");
  }

  /**
   * The stored parent-level {@code content_sha256} (tempdoc 931 §C.6) is the revision the guard
   * compares against, so the common path hashes nothing. This fixture makes that observable: the
   * stored value is deliberately NOT the digest of the content, so re-hashing would reject the
   * chunk.
   */
  @Test
  void storedParentRevisionIsUsedInsteadOfRehashingTheContent() throws Exception {
    String storedRevision = ChunkParentRevision.sha256Hex("a completely different string");
    Map<String, DocumentFieldOps.ChunkSlice> chunks =
        Map.of("chunk-0", new DocumentFieldOps.ChunkSlice("p", 0, 5, storedRevision));

    Map<String, String> resolved =
        DocumentFieldOps.resolveChunkContents(
            chunks,
            Map.of("p", new ParentRevision("alpha beta", storedRevision)),
            ignored -> null,
            null);

    assertEquals("alpha", resolved.get("chunk-0"));
  }

  /** A parent predating {@code content_sha256} is hashed once, not once per chunk of it. */
  @Test
  void aParentWithoutAStoredRevisionIsHashedOncePerRead() {
    String parent = "alpha beta gamma";
    String revision = ChunkParentRevision.sha256Hex(parent);
    int[] digests = {0};
    ChunkReadRevisionGuard guard =
        new ChunkReadRevisionGuard(
            content -> {
              digests[0]++;
              return ChunkParentRevision.sha256Hex(content);
            });
    ParentRevision noStoredRevision = new ParentRevision(parent, null);

    for (int i = 0; i < 4; i++) {
      assertEquals(
          "alpha",
          guard
              .slice("chunk-" + i, new DocumentFieldOps.ChunkSlice("p", 0, 5, revision),
                  noStoredRevision)
              .orElse(null));
    }

    assertEquals(1, digests[0], "four chunks of one parent must cost one SHA-256, not four");
    assertEquals(0, guard.mismatchCount());
  }
}
