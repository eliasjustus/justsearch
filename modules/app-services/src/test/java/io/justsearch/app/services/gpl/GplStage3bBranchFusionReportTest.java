package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GplStage3bBranchFusionReport")
class GplStage3bBranchFusionReportTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  @DisplayName("analyze summarizes branch presence by bucket and emits a sweep")
  void analyzeSummarizesBranchPresenceAndSweep() throws Exception {
    Path triples = tempDir.resolve("gpl-training-triples.ndjson");
    Files.writeString(
        triples,
        """
        {"query_id":"q1","doc_id":"doc-pos-short","is_negative":false,"branch_whole":0.9,"branch_chunk":0.2,"branch_present_whole":true,"branch_present_chunk":true,"branch_effective_weight_chunk":0.30,"parent_token_count":800}
        {"query_id":"q1","doc_id":"doc-neg-short","is_negative":true,"branch_whole":0.3,"branch_chunk":0.1,"branch_present_whole":true,"branch_present_chunk":true,"branch_effective_weight_chunk":0.30,"parent_token_count":900}
        {"query_id":"q2","doc_id":"doc-pos-long","is_negative":false,"branch_whole":0.4,"branch_chunk":0.9,"branch_present_whole":true,"branch_present_chunk":true,"branch_effective_weight_chunk":0.80,"parent_token_count":5000}
        {"query_id":"q2","doc_id":"doc-neg-long","is_negative":true,"branch_whole":0.2,"branch_chunk":0.3,"branch_present_whole":true,"branch_present_chunk":true,"branch_effective_weight_chunk":0.80,"parent_token_count":4800}
        {"query_id":"q3","doc_id":"doc-whole-only","is_negative":false,"branch_whole":0.7,"branch_chunk":0.0,"branch_present_whole":true,"branch_present_chunk":false,"branch_effective_weight_chunk":0.00,"parent_token_count":700}
        {"query_id":"q3","doc_id":"doc-whole-only-neg","is_negative":true,"branch_whole":0.1,"branch_chunk":0.0,"branch_present_whole":true,"branch_present_chunk":false,"branch_effective_weight_chunk":0.00,"parent_token_count":700}
        {"query_id":"q4","doc_id":"doc-chunk-only","is_negative":false,"branch_whole":0.0,"branch_chunk":0.8,"branch_present_whole":false,"branch_present_chunk":true,"branch_effective_weight_chunk":1.00,"parent_token_count":4500}
        {"query_id":"q4","doc_id":"doc-chunk-only-neg","is_negative":true,"branch_whole":0.0,"branch_chunk":0.2,"branch_present_whole":false,"branch_present_chunk":true,"branch_effective_weight_chunk":1.00,"parent_token_count":4500}
        """,
        StandardCharsets.UTF_8);

    GplStage3bBranchFusionReport.Report report = GplStage3bBranchFusionReport.analyze(triples);

    assertEquals(8L, report.analyzedTriples());
    assertEquals(4L, report.analyzedQueries());
    assertEquals(5, report.buckets().size());
    assertEquals(22, report.sweep().size(), "11 chunk weights x 2 min multipliers");
    assertNotNull(report.selectedConfig());

    var shortBucket =
        report.buckets().stream()
            .filter(bucket -> "le_1024".equals(bucket.bucket()))
            .findFirst()
            .orElseThrow();
    assertEquals(4L, shortBucket.tripleCount());
    assertEquals(1.0, shortBucket.wholePresentRate(), 0.0001);
    assertEquals(0.5, shortBucket.chunkPresentRate(), 0.0001);

    var longBucket =
        report.buckets().stream()
            .filter(bucket -> "ge_4096".equals(bucket.bucket()))
            .findFirst()
            .orElseThrow();
    assertEquals(4L, longBucket.tripleCount());
    assertEquals(0.5, longBucket.wholePresentRate(), 0.0001);
    assertEquals(1.0, longBucket.chunkPresentRate(), 0.0001);
    assertTrue(longBucket.averageEffectiveChunkWeight() > 0.5);
  }

  @Test
  @DisplayName("write persists the Stage 3B branch-fusion report next to the triple store")
  void writePersistsReport() throws Exception {
    Path triples = tempDir.resolve("gpl-training-triples.ndjson");
    Files.writeString(
        triples,
        """
        {"query_id":"q1","doc_id":"doc-1","is_negative":false,"branch_whole":0.8,"branch_chunk":0.4,"branch_present_whole":true,"branch_present_chunk":true,"branch_effective_weight_chunk":0.40,"parent_token_count":1200}
        {"query_id":"q1","doc_id":"doc-2","is_negative":true,"branch_whole":0.2,"branch_chunk":0.1,"branch_present_whole":true,"branch_present_chunk":true,"branch_effective_weight_chunk":0.40,"parent_token_count":1200}
        """,
        StandardCharsets.UTF_8);

    Path reportPath = GplStage3bBranchFusionReport.write(triples);

    assertTrue(Files.exists(reportPath));
    JsonNode node = MAPPER.readTree(Files.readString(reportPath, StandardCharsets.UTF_8));
    assertEquals(2L, node.get("analyzedTriples").longValue());
    assertEquals("gpl-stage3b-branch-fusion.json", reportPath.getFileName().toString());
    assertTrue(node.get("sweep").isArray());
    assertTrue(node.has("selectedConfig"));
  }
}
