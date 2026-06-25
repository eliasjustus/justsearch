package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GplStage3aAnalysisReport")
class GplStage3aAnalysisReportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  @DisplayName("analyze buckets whole-doc and chunk contributions by parent length")
  void analyzeBucketsWholeAndChunkContributions() throws Exception {
    Path triples = tempDir.resolve("gpl-training-triples.ndjson");
    Files.writeString(
        triples,
        """
        {"query_id":"q1","doc_id":"doc-1","whole_sparse":1.0,"whole_vector":0.2,"whole_splade":0.3,"whole_cc":0.8,"chunk_sparse":0.0,"chunk_vector":0.0,"chunk_splade":0.0,"chunk_cc":0.0,"parent_token_count":800}
        {"query_id":"q2","doc_id":"doc-2","whole_sparse":0.0,"whole_vector":0.0,"whole_splade":0.0,"whole_cc":0.0,"chunk_sparse":1.0,"chunk_vector":0.3,"chunk_splade":0.4,"chunk_cc":0.9,"parent_token_count":2500}
        {"query_id":"q3","doc_id":"doc-3","whole_sparse":1.0,"whole_vector":0.2,"whole_splade":0.0,"whole_cc":0.7,"chunk_sparse":1.0,"chunk_vector":0.1,"chunk_splade":0.1,"chunk_cc":0.5,"parent_token_count":5000}
        {"query_id":"q4","doc_id":"doc-4","whole_sparse":0.0,"whole_vector":0.0,"whole_splade":0.0,"whole_cc":0.0,"chunk_sparse":0.0,"chunk_vector":0.0,"chunk_splade":0.0,"chunk_cc":0.0}
        """,
        StandardCharsets.UTF_8);

    GplStage3aAnalysisReport.Report report = GplStage3aAnalysisReport.analyze(triples);

    assertEquals(4L, report.analyzedTriples());
    assertEquals(1L, report.wholeOnlyCount());
    assertEquals(1L, report.chunkOnlyCount());
    assertEquals(1L, report.bothCount());
    assertEquals(1L, report.neitherCount());
    assertEquals(5, report.buckets().size(), "fixed bucket set should be emitted");

    var shortBucket =
        report.buckets().stream()
            .filter(bucket -> "le_1024".equals(bucket.bucket()))
            .findFirst()
            .orElseThrow();
    assertEquals(1L, shortBucket.tripleCount());
    assertEquals(1.0, shortBucket.wholeContributionRate(), 0.0001);

    var longBucket =
        report.buckets().stream()
            .filter(bucket -> "ge_4096".equals(bucket.bucket()))
            .findFirst()
            .orElseThrow();
    assertEquals(1L, longBucket.tripleCount());
    assertEquals(1.0, longBucket.wholeContributionRate(), 0.0001);
    assertEquals(1.0, longBucket.chunkContributionRate(), 0.0001);
  }

  @Test
  @DisplayName("write persists the Stage 3A analysis report next to the triple store")
  void writePersistsReport() throws Exception {
    Path triples = tempDir.resolve("gpl-training-triples.ndjson");
    Files.writeString(
        triples,
        """
        {"query_id":"q1","doc_id":"doc-1","whole_sparse":1.0,"whole_vector":0.0,"whole_splade":0.0,"whole_cc":0.6,"chunk_sparse":0.0,"chunk_vector":0.0,"chunk_splade":0.0,"chunk_cc":0.0,"parent_token_count":900}
        """,
        StandardCharsets.UTF_8);

    Path reportPath = GplStage3aAnalysisReport.write(triples);

    assertTrue(Files.exists(reportPath));
    JsonNode node = MAPPER.readTree(Files.readString(reportPath, StandardCharsets.UTF_8));
    assertEquals(1L, node.get("analyzedTriples").longValue());
    assertEquals("gpl-stage3a-analysis.json", reportPath.getFileName().toString());
  }
}
