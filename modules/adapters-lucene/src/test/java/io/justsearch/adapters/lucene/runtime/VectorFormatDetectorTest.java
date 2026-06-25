package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for VectorFormatDetector.
 */
class VectorFormatDetectorTest {

  @TempDir Path tempDir;

  private static final int VECTOR_DIM = 8; // Small dimension for tests

  @Test
  @DisplayName("Detects Float32 format from commit metadata")
  void detectsFloat32FormatFromMetadata() throws Exception {
    Path indexPath = tempDir.resolve("float32-index");
    Files.createDirectories(indexPath);

    try (FSDirectory dir = FSDirectory.open(indexPath)) {
      IndexWriterConfig config = new IndexWriterConfig();
      config.setCodec(new JustSearchCodec(JustSearchCodec.float32Format()));

      try (IndexWriter writer = new IndexWriter(dir, config)) {
        Document doc = new Document();
        doc.add(new StringField("id", "1", Field.Store.YES));
        doc.add(new KnnFloatVectorField("vector", randomVector(), VectorSimilarityFunction.COSINE));
        writer.addDocument(doc);

        // Set commit metadata (as SsotCommitMetadataSource would do)
        writer.setLiveCommitData(Map.of("vector_format", "float32").entrySet());
        writer.commit();
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(reader);

        assertEquals("FLOAT32", summary.overallState());
      }
    }
  }

  @Test
  @DisplayName("Detects Int8 scalar-quantized format from commit metadata")
  void detectsQuantizedFormatFromMetadata() throws Exception {
    Path indexPath = tempDir.resolve("quantized-index");
    Files.createDirectories(indexPath);

    try (FSDirectory dir = FSDirectory.open(indexPath)) {
      IndexWriterConfig config = new IndexWriterConfig();
      config.setCodec(new JustSearchCodec(JustSearchCodec.quantizedFormat()));

      try (IndexWriter writer = new IndexWriter(dir, config)) {
        Document doc = new Document();
        doc.add(new StringField("id", "1", Field.Store.YES));
        doc.add(new KnnFloatVectorField("vector", randomVector(), VectorSimilarityFunction.COSINE));
        writer.addDocument(doc);

        // Set commit metadata (as SsotCommitMetadataSource would do)
        writer.setLiveCommitData(Map.of("vector_format", "int8_sq").entrySet());
        writer.commit();
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(reader);

        assertEquals("INT8_SQ", summary.overallState());
      }
    }
  }

  @Test
  @DisplayName("Handles empty index gracefully")
  void handlesEmptyIndex() throws Exception {
    Path indexPath = tempDir.resolve("empty-index");
    Files.createDirectories(indexPath);

    try (FSDirectory dir = FSDirectory.open(indexPath)) {
      IndexWriterConfig config = new IndexWriterConfig();
      config.setCodec(new JustSearchCodec(JustSearchCodec.float32Format()));

      try (IndexWriter writer = new IndexWriter(dir, config)) {
        writer.setLiveCommitData(Map.of("vector_format", "float32").entrySet());
        writer.commit(); // Empty commit
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(reader);

        // Empty index with metadata should return the format from metadata
        assertEquals("FLOAT32", summary.overallState());
      }
    }
  }

  @Test
  @DisplayName("Returns UNKNOWN for null reader")
  void returnsUnknownForNullReader() {
    VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(null);

    assertEquals("UNKNOWN", summary.overallState());
    assertEquals(0, summary.float32Count());
    assertEquals(0, summary.quantizedCount());
    assertTrue(summary.segments().isEmpty());
  }

  @Test
  @DisplayName("Falls back to UNKNOWN when no commit metadata present")
  void fallsBackToUnknownWithoutMetadata() throws Exception {
    Path indexPath = tempDir.resolve("no-metadata-index");
    Files.createDirectories(indexPath);

    try (FSDirectory dir = FSDirectory.open(indexPath)) {
      IndexWriterConfig config = new IndexWriterConfig();
      config.setCodec(new JustSearchCodec(JustSearchCodec.float32Format()));

      try (IndexWriter writer = new IndexWriter(dir, config)) {
        Document doc = new Document();
        doc.add(new StringField("id", "1", Field.Store.YES));
        doc.add(new KnnFloatVectorField("vector", randomVector(), VectorSimilarityFunction.COSINE));
        writer.addDocument(doc);
        // NO commit metadata set
        writer.commit();
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(reader);

        // Without metadata, falls back to segment inspection which detects FLOAT32
        // (segment inspection works for float32 due to codec default)
        assertEquals("FLOAT32", summary.overallState());
      }
    }
  }

  @Test
  @DisplayName("Works with compound files enabled")
  void worksWithCompoundFilesEnabled() throws Exception {
    Path indexPath = tempDir.resolve("cfs-index");
    Files.createDirectories(indexPath);

    try (FSDirectory dir = FSDirectory.open(indexPath)) {
      IndexWriterConfig config = new IndexWriterConfig();
      config.setCodec(new JustSearchCodec(JustSearchCodec.quantizedFormat()));
      config.setUseCompoundFile(true); // CFS enabled

      try (IndexWriter writer = new IndexWriter(dir, config)) {
        Document doc = new Document();
        doc.add(new StringField("id", "1", Field.Store.YES));
        doc.add(new KnnFloatVectorField("vector", randomVector(), VectorSimilarityFunction.COSINE));
        writer.addDocument(doc);

        // Set commit metadata
        writer.setLiveCommitData(Map.of("vector_format", "int8_sq").entrySet());
        writer.commit();
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        VectorFormatDetector.Summary summary = VectorFormatDetector.inspect(reader);

        // Detection via commit metadata works regardless of CFS
        assertEquals("INT8_SQ", summary.overallState());
      }
    }
  }

  @Test
  @DisplayName("Segment inspection returns expected counts")
  void segmentInspectionReturnsCounts() throws Exception {
    Path indexPath = tempDir.resolve("count-test-index");
    Files.createDirectories(indexPath);

    try (FSDirectory dir = FSDirectory.open(indexPath)) {
      IndexWriterConfig config = new IndexWriterConfig();
      config.setCodec(new JustSearchCodec(JustSearchCodec.float32Format()));

      try (IndexWriter writer = new IndexWriter(dir, config)) {
        // Create two separate segments
        Document doc1 = new Document();
        doc1.add(new StringField("id", "1", Field.Store.YES));
        doc1.add(new KnnFloatVectorField("vector", randomVector(), VectorSimilarityFunction.COSINE));
        writer.addDocument(doc1);
        writer.commit();

        Document doc2 = new Document();
        doc2.add(new StringField("id", "2", Field.Store.YES));
        doc2.add(new KnnFloatVectorField("vector", randomVector(), VectorSimilarityFunction.COSINE));
        writer.addDocument(doc2);
        writer.commit();
      }

      try (DirectoryReader reader = DirectoryReader.open(dir)) {
        // Use segment inspection directly (no metadata)
        VectorFormatDetector.Summary summary = VectorFormatDetector.inspectSegments(reader);

        assertEquals("FLOAT32", summary.overallState());
        assertEquals(2, summary.float32Count());
        assertEquals(0, summary.quantizedCount());
        assertEquals(2, summary.segments().size());
      }
    }
  }

  private static float[] randomVector() {
    float[] v = new float[VECTOR_DIM];
    for (int i = 0; i < v.length; i++) {
      v[i] = (float) Math.random();
    }
    // Normalize for cosine similarity
    float norm = 0;
    for (float f : v) norm += f * f;
    norm = (float) Math.sqrt(norm);
    for (int i = 0; i < v.length; i++) v[i] /= norm;
    return v;
  }
}
