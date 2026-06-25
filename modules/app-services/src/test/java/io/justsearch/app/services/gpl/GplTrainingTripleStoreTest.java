package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link GplTrainingTripleStore}. */
@DisplayName("GplTrainingTripleStore")
class GplTrainingTripleStoreTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  @DisplayName("exists() returns false before any write")
  void existsReturnsFalseBeforeFirstWrite() {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);
    assertFalse(store.exists());
  }

  @Test
  @DisplayName("count() returns 0 when file does not exist")
  void countReturnsZeroWhenFileMissing() throws Exception {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);
    assertEquals(0L, store.count());
  }

  @Test
  @DisplayName("append() creates the file and writes a valid JSON line")
  void appendCreatesFileWithValidLine() throws Exception {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);
    store.append("doc-1", "what is machine learning", 0.85f);

    assertTrue(store.exists());
    List<String> lines = Files.readAllLines(store.storeFile(), StandardCharsets.UTF_8);
    assertEquals(1, lines.size());

    String line = lines.get(0);
    assertTrue(line.startsWith("{"), "line should be a JSON object: " + line);
    assertTrue(line.contains("\"doc_id\":\"doc-1\""), "should contain doc_id");
    assertTrue(line.contains("\"synthetic_query\":\"what is machine learning\""), "should contain query");
    assertTrue(line.contains("\"score\":"), "should contain score");
    assertTrue(line.contains("\"timestamp_ms\":"), "should contain timestamp");
  }

  @Test
  @DisplayName("count() returns the number of appended triples")
  void countReturnsNumberOfAppendedTriples() throws Exception {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);
    store.append("doc-1", "query one", 0.7f);
    store.append("doc-2", "query two", 0.8f);
    store.append("doc-3", "query three", 0.9f);

    assertEquals(3L, store.count());
  }

  @Test
  @DisplayName("append() escapes special characters in JSON strings")
  void appendEscapesSpecialCharacters() throws Exception {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);
    store.append("doc-with\"quotes", "query with\nnewline\ttab", 0.5f);

    List<String> lines = Files.readAllLines(store.storeFile(), StandardCharsets.UTF_8);
    assertEquals(1, lines.size());
    String line = lines.get(0);
    // Verify the escaping is present in the serialized form
    assertTrue(line.contains("\\\""), "double-quote in doc_id should be escaped");
    assertTrue(line.contains("\\n"), "newline in query should be escaped");
    assertTrue(line.contains("\\t"), "tab in query should be escaped");
  }

  @Test
  @DisplayName("storeFile() path is within the data directory")
  void storeFileIsInDataDir() {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);
    assertTrue(
        store.storeFile().startsWith(tempDir),
        "store file should be under tempDir");
    assertEquals(
        "gpl-training-triples.ndjson",
        store.storeFile().getFileName().toString());
  }

  @Test
  @DisplayName("appendWithFeatures writes Stage 3A and Stage 3B branch columns")
  void appendWithFeaturesWritesStage3aAndStage3bColumns() throws Exception {
    GplTrainingTripleStore store = new GplTrainingTripleStore(tempDir);

    store.appendWithFeatures(
        "doc-1#0",
        "doc-1",
        "find document",
        0.83f,
        false,
        GplTrainingTripleStore.FeaturePayload.builder()
            .sparse(12.4f)
            .vector(0.73f)
            .wholeSparse(12.4f)
            .wholeVector(0.73f)
            .wholeSplade(0.21f)
            .wholeCc(0.88f)
            .chunkSparse(6.2f)
            .chunkVector(0.41f)
            .chunkSplade(0.19f)
            .chunkCc(0.44f)
            .branchWhole(0.88f)
            .branchChunk(0.44f)
            .branchCc(0.73f)
            .branchPresentWhole(true)
            .branchPresentChunk(true)
            .branchWeightWhole(0.50f)
            .branchWeightChunk(0.50f)
            .branchEffectiveWeightWhole(0.62f)
            .branchEffectiveWeightChunk(0.38f)
            .branchModifierWhole(1.0f)
            .branchModifierChunk(0.50f)
            .parentTokenCount(1536L)
            .qppMaxIdf(8.2f)
            .qppAvgIctf(6.1f)
            .qppQueryScope(0.33f)
            .rankPosition(2)
            .timestampMs(1234567890L)
            .build());

    JsonNode node = MAPPER.readTree(Files.readString(store.storeFile(), StandardCharsets.UTF_8));
    assertEquals(12.4f, node.get("sparse").floatValue(), 0.001f);
    assertEquals(0.73f, node.get("vector").floatValue(), 0.001f);
    assertEquals(12.4f, node.get("whole_sparse").floatValue(), 0.001f);
    assertEquals(0.73f, node.get("whole_vector").floatValue(), 0.001f);
    assertEquals(0.21f, node.get("whole_splade").floatValue(), 0.001f);
    assertEquals(0.88f, node.get("whole_cc").floatValue(), 0.001f);
    assertEquals(6.2f, node.get("chunk_sparse").floatValue(), 0.001f);
    assertEquals(0.41f, node.get("chunk_vector").floatValue(), 0.001f);
    assertEquals(0.19f, node.get("chunk_splade").floatValue(), 0.001f);
    assertEquals(0.44f, node.get("chunk_cc").floatValue(), 0.001f);
    assertEquals(0.88f, node.get("branch_whole").floatValue(), 0.001f);
    assertEquals(0.44f, node.get("branch_chunk").floatValue(), 0.001f);
    assertEquals(0.73f, node.get("branch_cc").floatValue(), 0.001f);
    assertTrue(node.get("branch_present_whole").booleanValue());
    assertTrue(node.get("branch_present_chunk").booleanValue());
    assertEquals(0.50f, node.get("branch_weight_whole").floatValue(), 0.001f);
    assertEquals(0.50f, node.get("branch_weight_chunk").floatValue(), 0.001f);
    assertEquals(0.62f, node.get("branch_effective_weight_whole").floatValue(), 0.001f);
    assertEquals(0.38f, node.get("branch_effective_weight_chunk").floatValue(), 0.001f);
    assertEquals(1.0f, node.get("branch_modifier_whole").floatValue(), 0.001f);
    assertEquals(0.50f, node.get("branch_modifier_chunk").floatValue(), 0.001f);
    assertEquals(1536L, node.get("parent_token_count").longValue());
    assertEquals(2, node.get("rank_position").intValue());
  }
}
