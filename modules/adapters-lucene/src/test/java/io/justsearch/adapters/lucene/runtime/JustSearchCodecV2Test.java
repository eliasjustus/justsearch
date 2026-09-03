/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene104.Lucene104HnswScalarQuantizedVectorsFormat;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JustSearchCodecV2Test {

  private static final int DOCUMENTS_PER_SEGMENT = 128;
  private static final float[] QUERY_VECTOR = {1.0f, 0.0f, 0.0f, 0.0f};
  private static final String SOFT_DELETES_FIELD = "_soft_delete";

  @TempDir Path tempDir;

  @Test
  void serviceLoaderCanResolveBothCodecGenerations() {
    assertInstanceOf(JustSearchCodec.class, Codec.forName("JustSearchCodec"));
    assertInstanceOf(JustSearchCodecV2.class, Codec.forName(JustSearchCodecV2.NAME));
  }

  @Test
  void defaultUsesQuantizationBehindPerFieldPersistence() {
    JustSearchCodecV2 codec = new JustSearchCodecV2();
    PerFieldKnnVectorsFormat perField =
        assertInstanceOf(PerFieldKnnVectorsFormat.class, codec.knnVectorsFormat());
    KnnVectorsFormat format =
        assertInstanceOf(
        Lucene104HnswScalarQuantizedVectorsFormat.class,
        perField.getKnnVectorsFormatForField("vector"));
    String description = format.toString();
    assertTrue(description.contains("encoding=UNSIGNED_BYTE"));
    assertTrue(
        description.contains("maxConn=" + ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_M));
    assertTrue(
        description.contains(
            "beamWidth=" + ResolvedConfig.Index.DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION));
  }

  @Test
  void quantizedSegmentRemainsReadableAfterCodecReinstantiation() throws Exception {
    Path indexPath = tempDir.resolve("int8");
    KnnVectorsFormat format = JustSearchCodecV2.quantizedFormat();
    appendSegment(indexPath, new JustSearchCodecV2(format), "int8");
    assertReadableWithFormats(indexPath, Set.of(format.getName()));
  }

  @Test
  void explicitFloatSegmentRemainsReadableAfterCodecReinstantiation() throws Exception {
    Path indexPath = tempDir.resolve("float32");
    KnnVectorsFormat format = JustSearchCodecV2.float32Format();
    appendSegment(indexPath, new JustSearchCodecV2(format), "float32");
    assertReadableWithFormats(indexPath, Set.of(format.getName()));
  }

  @Test
  void mixedV2FormatsRemainReadableAndMergeIntoCurrentDefault() throws Exception {
    Path indexPath = tempDir.resolve("mixed-v2");
    KnnVectorsFormat float32 = JustSearchCodecV2.float32Format();
    KnnVectorsFormat int8 = JustSearchCodecV2.quantizedFormat();
    appendSegment(indexPath, new JustSearchCodecV2(float32), "float32");
    appendSegment(indexPath, new JustSearchCodecV2(int8), "int8");
    softDeleteAndReplace(indexPath, new JustSearchCodecV2(int8), "float32-0");
    assertReadableWithFormats(indexPath, Set.of(float32.getName(), int8.getName()));
    assertEquals("MIXED", inspect(indexPath).overallState());
    assertEquals(
        "MIXED",
        inspectThroughProductionSoftDeletesWrapper(indexPath).overallState(),
        "the production reader wrapper must not hide per-segment format metadata");

    forceMerge(indexPath, new JustSearchCodecV2(int8));
    assertSegmentCount(indexPath, 1);
    assertReadableWithFormats(indexPath, Set.of(int8.getName()));
    assertEquals("INT8_SQ", inspect(indexPath).overallState());
  }

  @Test
  void legacyFloatAndV2Int8UpgradePathRemainsReadableAndMergeable() throws Exception {
    Path indexPath = tempDir.resolve("legacy-upgrade");
    appendSegment(indexPath, new JustSearchCodec(JustSearchCodec.float32Format()), "legacy");
    appendSegment(indexPath, new JustSearchCodecV2(JustSearchCodecV2.quantizedFormat()), "v2");
    assertReadable(indexPath);

    forceMerge(indexPath, new JustSearchCodecV2());
    assertSegmentCount(indexPath, 1);
    assertReadableWithFormats(
        indexPath, Set.of(JustSearchCodecV2.quantizedFormat().getName()));
  }

  private static void appendSegment(Path indexPath, Codec codec, String idPrefix) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(
                directory,
                new IndexWriterConfig()
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setCodec(codec)
                    .setSoftDeletesField(SOFT_DELETES_FIELD)
                    .setMergePolicy(NoMergePolicy.INSTANCE))) {
      for (int i = 0; i < DOCUMENTS_PER_SEGMENT; i++) {
        float[] vector = unitVector(i);
        writer.addDocument(vectorDocument(idPrefix + "-" + i, vector));
      }
      writer.commit();
    }
  }

  private static void softDeleteAndReplace(Path indexPath, Codec codec, String id) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(
                directory,
                new IndexWriterConfig()
                    .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
                    .setCodec(codec)
                    .setSoftDeletesField(SOFT_DELETES_FIELD)
                    .setMergePolicy(NoMergePolicy.INSTANCE))) {
      writer.softUpdateDocument(
          new Term("id", id),
          vectorDocument(id, QUERY_VECTOR),
          new NumericDocValuesField(SOFT_DELETES_FIELD, 1));
      writer.commit();
    }
  }

  private static Document vectorDocument(String id, float[] vector) {
    Document document = new Document();
    document.add(new StringField("id", id, Field.Store.YES));
    document.add(new KnnFloatVectorField("vector", vector));
    document.add(new KnnFloatVectorField("chunk_vector", vector));
    return document;
  }

  private static void forceMerge(Path indexPath, Codec codec) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(
                directory,
                new IndexWriterConfig()
                    .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
                    .setCodec(codec)
                    .setSoftDeletesField(SOFT_DELETES_FIELD))) {
      writer.forceMerge(1);
      writer.commit();
    }
  }

  private static void assertReadableWithFormats(Path indexPath, Set<String> expectedFormats)
      throws Exception {
    Set<String> actualFormats = new HashSet<>();
    try (FSDirectory directory = FSDirectory.open(indexPath);
        DirectoryReader reader = DirectoryReader.open(directory)) {
      for (var leaf : reader.leaves()) {
        for (String field : Set.of("vector", "chunk_vector")) {
          FieldInfo info = leaf.reader().getFieldInfos().fieldInfo(field);
          String format = info.getAttribute(PerFieldKnnVectorsFormat.PER_FIELD_FORMAT_KEY);
          if (format != null) {
            actualFormats.add(format);
            assertTrue(
                info.getAttribute(PerFieldKnnVectorsFormat.PER_FIELD_SUFFIX_KEY) != null,
                "per-field persistence must record the format suffix for " + field);
          }
        }
      }
    }
    assertEquals(expectedFormats, actualFormats);
    assertReadable(indexPath);
  }

  private static void assertReadable(Path indexPath) throws Exception {
    // A new DirectoryReader resolves the segment's codec by its persisted name. This is the O15
    // regression boundary: the old single-format codec could write Int8 but reopened it as Float32.
    try (FSDirectory reopenedDirectory = FSDirectory.open(indexPath);
        DirectoryReader reader = DirectoryReader.open(reopenedDirectory)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      for (String field : Set.of("vector", "chunk_vector")) {
        var hits = searcher.search(new KnnFloatVectorQuery(field, QUERY_VECTOR, 10), 10);
        assertEquals(10, hits.scoreDocs.length);
        assertTrue(
            searcher.storedFields().document(hits.scoreDocs[0].doc).get("id").contains("-"));
      }
    }
  }

  private static VectorFormatDetector.Summary inspect(Path indexPath) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        DirectoryReader reader = DirectoryReader.open(directory)) {
      return VectorFormatDetector.inspectSegments(reader);
    }
  }

  private static VectorFormatDetector.Summary inspectThroughProductionSoftDeletesWrapper(
      Path indexPath) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        DirectoryReader wrapped =
            new SoftDeletesDirectoryReaderWrapper(
                DirectoryReader.open(directory), SOFT_DELETES_FIELD)) {
      assertTrue(
          wrapped.leaves().stream()
              .anyMatch(leaf -> !(leaf.reader() instanceof SegmentReader)),
          "precondition: an actual soft deletion must produce a wrapped production leaf");
      return VectorFormatDetector.inspect(wrapped);
    }
  }

  private static void assertSegmentCount(Path indexPath, int expected) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        DirectoryReader reader = DirectoryReader.open(directory)) {
      assertEquals(expected, reader.leaves().size());
    }
  }

  private static float[] unitVector(int ordinal) {
    double angle = ordinal * Math.PI * 2.0 / DOCUMENTS_PER_SEGMENT;
    return new float[] {(float) Math.cos(angle), (float) Math.sin(angle), 0.0f, 0.0f};
  }
}
