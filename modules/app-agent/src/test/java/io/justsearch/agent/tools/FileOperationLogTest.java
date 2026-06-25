package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationLogTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  private FileOperationLog log;
  private Path logDir;

  @BeforeEach
  void setUp() {
    logDir = tempDir.resolve("file-operations");
    log = new FileOperationLog(logDir);
  }

  @Test
  void startBatchCreatesJsonFile() throws Exception {
    log.startBatch(
        "batch-1",
        "Move files",
        List.of(
            new FileOperation(
                FileOperation.OpType.MOVE, Path.of("/src/a.txt"), Path.of("/dst/a.txt"))));

    Path logFile = logDir.resolve("batch-1.json");
    assertTrue(Files.exists(logFile), "Log file should be created");

    JsonNode json = MAPPER.readTree(logFile.toFile());
    assertEquals("batch-1", json.get("batchId").asText());
    assertEquals("Move files", json.get("explanation").asText());
    assertNotNull(json.get("timestamp"));
    assertTrue(json.get("operations").isArray());
    assertEquals(1, json.get("operations").size());
    assertEquals("MOVE", json.get("operations").get(0).get("op").asText());
    assertTrue(json.get("executed").isArray());
    assertEquals(0, json.get("executed").size());
  }

  @Test
  void recordSuccessAppendsToExecuted() throws Exception {
    log.startBatch(
        "batch-2",
        "Copy files",
        List.of(
            new FileOperation(
                FileOperation.OpType.COPY, Path.of("/src/a.txt"), Path.of("/dst/a.txt"))));
    log.recordSuccess("batch-2", 0);

    JsonNode json = MAPPER.readTree(logDir.resolve("batch-2.json").toFile());
    assertEquals(1, json.get("executed").size());

    JsonNode entry = json.get("executed").get(0);
    assertEquals(0, entry.get("index").asInt());
    assertEquals("OK", entry.get("status").asText());
    assertNotNull(entry.get("timestamp"));
  }

  @Test
  void recordFailureAppendsErrorToExecuted() throws Exception {
    log.startBatch(
        "batch-3",
        "Failed op",
        List.of(
            new FileOperation(
                FileOperation.OpType.MOVE, Path.of("/src/a.txt"), Path.of("/dst/a.txt"))));
    log.recordFailure("batch-3", 0, "File not found");

    JsonNode json = MAPPER.readTree(logDir.resolve("batch-3.json").toFile());
    assertEquals(1, json.get("executed").size());

    JsonNode entry = json.get("executed").get(0);
    assertEquals(0, entry.get("index").asInt());
    assertEquals("FAILED", entry.get("status").asText());
    assertEquals("File not found", entry.get("error").asText());
  }

  @Test
  void finalizeBatchAddsTimestamp() throws Exception {
    log.startBatch(
        "batch-4",
        "Finalize test",
        List.of(
            new FileOperation(
                FileOperation.OpType.MKDIR, null, Path.of("/dst/newdir"))));
    log.finalizeBatch("batch-4");

    JsonNode json = MAPPER.readTree(logDir.resolve("batch-4.json").toFile());
    assertNotNull(json.get("finalized"), "Finalized timestamp should be present");
    assertFalse(json.get("finalized").asText().isEmpty());
  }

  @Test
  void multipleOperationsRecordedInOrder() throws Exception {
    log.startBatch(
        "batch-5",
        "Multi-op",
        List.of(
            new FileOperation(
                FileOperation.OpType.MKDIR, null, Path.of("/dst/dir")),
            new FileOperation(
                FileOperation.OpType.MOVE, Path.of("/src/a.txt"), Path.of("/dst/dir/a.txt"))));
    log.recordSuccess("batch-5", 0);
    log.recordSuccess("batch-5", 1);
    log.finalizeBatch("batch-5");

    JsonNode json = MAPPER.readTree(logDir.resolve("batch-5.json").toFile());
    assertEquals(2, json.get("operations").size());
    assertEquals(2, json.get("executed").size());
    assertEquals(0, json.get("executed").get(0).get("index").asInt());
    assertEquals(1, json.get("executed").get(1).get("index").asInt());
  }

  @Test
  void logDirectoryCreatedAutomatically() {
    assertTrue(Files.isDirectory(logDir), "Log directory should be created by constructor");
  }

  @Test
  void recordOnMissingBatchIsNoOp() {
    // Should not throw — just logs a warning
    assertDoesNotThrow(() -> log.recordSuccess("nonexistent-batch", 0));
    assertDoesNotThrow(() -> log.recordFailure("nonexistent-batch", 0, "error"));
    assertDoesNotThrow(() -> log.finalizeBatch("nonexistent-batch"));
  }

  // ===== readBatch tests =====

  @Test
  void readBatchReturnsNullForUnknownBatchId() {
    assertNull(log.readBatch("no-such-batch"));
  }

  @Test
  void readBatchReturnsFullContentAfterStartAndFinalize() {
    log.startBatch(
        "read-batch-1",
        "Test read",
        List.of(
            new FileOperation(
                FileOperation.OpType.MOVE, Path.of("/src/a.txt"), Path.of("/dst/a.txt"))));
    log.recordSuccess("read-batch-1", 0);
    log.finalizeBatch("read-batch-1");

    var batch = log.readBatch("read-batch-1");
    assertNotNull(batch);
    assertEquals("read-batch-1", batch.get("batchId"));
    assertEquals("Test read", batch.get("explanation"));
    assertNotNull(batch.get("timestamp"));
    assertNotNull(batch.get("finalized"));

    @SuppressWarnings("unchecked")
    var ops = (List<?>) batch.get("operations");
    assertEquals(1, ops.size());

    @SuppressWarnings("unchecked")
    var executed = (List<?>) batch.get("executed");
    assertEquals(1, executed.size());
  }

  // ===== listBatches tests =====

  @Test
  void listBatchesReturnsSortedByRecency() throws Exception {
    log.startBatch("batch-a", "First", List.of(
        new FileOperation(FileOperation.OpType.MKDIR, null, Path.of("/dir1"))));
    log.finalizeBatch("batch-a");

    // Ensure different mtime
    Thread.sleep(50);

    log.startBatch("batch-b", "Second", List.of(
        new FileOperation(FileOperation.OpType.MKDIR, null, Path.of("/dir2"))));
    log.finalizeBatch("batch-b");

    var batches = log.listBatches(10);
    assertEquals(2, batches.size());
    // Most recent first
    assertEquals("batch-b", batches.get(0).get("batchId"));
    assertEquals("batch-a", batches.get(1).get("batchId"));
  }

  @Test
  void listBatchesRespectsLimit() {
    for (int i = 0; i < 5; i++) {
      log.startBatch("limit-batch-" + i, "Batch " + i, List.of(
          new FileOperation(FileOperation.OpType.MKDIR, null, Path.of("/dir" + i))));
      log.finalizeBatch("limit-batch-" + i);
    }

    var batches = log.listBatches(2);
    assertEquals(2, batches.size());
  }

  @Test
  void listBatchesEmptyWhenNoLogs() {
    // Create a fresh log in a new directory with no batches
    var emptyLog = new FileOperationLog(tempDir.resolve("empty-logs"));
    var batches = emptyLog.listBatches(10);
    assertTrue(batches.isEmpty());
  }

  // ===== Corrupted batch file tests =====

  @Test
  void corruptedBatchFileIsSkippedInList() throws Exception {
    // Create a valid batch
    log.startBatch("good-batch", "Good", List.of(
        new FileOperation(FileOperation.OpType.MKDIR, null, Path.of("/dir"))));
    log.finalizeBatch("good-batch");

    // Create a corrupted JSON file in the log dir
    Files.writeString(logDir.resolve("corrupted-batch.json"), "not valid json{{{");

    var batches = log.listBatches(10);
    assertEquals(1, batches.size(), "Corrupted batch should be filtered out");
    assertEquals("good-batch", batches.get(0).get("batchId"));
  }

  @Test
  void readBatchReturnsNullForCorruptedFile() throws Exception {
    // Create a corrupted batch file
    Files.createDirectories(logDir);
    Files.writeString(logDir.resolve("broken.json"), "{{invalid}}");

    assertNull(log.readBatch("broken"));
  }

  // ===== recordSkip tests =====

  @Test
  void recordSkipProducesCorrectStatus() throws Exception {
    log.startBatch("skip-batch", "Skip test", List.of(
        new FileOperation(
            FileOperation.OpType.MOVE, Path.of("/src/a.txt"), Path.of("/dst/a.txt"))));
    log.recordSkip("skip-batch", 0, "Destination already exists");

    JsonNode json = MAPPER.readTree(logDir.resolve("skip-batch.json").toFile());
    assertEquals(1, json.get("executed").size());

    JsonNode entry = json.get("executed").get(0);
    assertEquals(0, entry.get("index").asInt());
    assertEquals("SKIPPED", entry.get("status").asText());
    assertEquals("Destination already exists", entry.get("reason").asText());
    assertNotNull(entry.get("timestamp"));
  }

  // ===== recordRename tests =====

  @Test
  void recordRenameProducesCorrectStatus() throws Exception {
    log.startBatch("rename-batch", "Rename test", List.of(
        new FileOperation(
            FileOperation.OpType.MOVE, Path.of("/src/a.txt"), Path.of("/dst/a.txt"))));
    log.recordRename("rename-batch", 0, Path.of("/dst/a.txt"), Path.of("/dst/a (1).txt"));

    JsonNode json = MAPPER.readTree(logDir.resolve("rename-batch.json").toFile());
    assertEquals(1, json.get("executed").size());

    JsonNode entry = json.get("executed").get(0);
    assertEquals(0, entry.get("index").asInt());
    assertEquals("OK_RENAMED", entry.get("status").asText());
    assertEquals("/dst/a.txt", entry.get("originalDestination").asText().replace("\\", "/"));
    assertEquals("/dst/a (1).txt", entry.get("resolvedDestination").asText().replace("\\", "/"));
    assertNotNull(entry.get("timestamp"));
  }
}
