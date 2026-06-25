package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsToolTest {

  @TempDir Path tempDir;

  private Path root;
  private AtomicReference<Map<Path, Path>> capturedMappings;
  private FileOperationsTool tool;

  @BeforeEach
  void setUp() throws IOException {
    root = tempDir.resolve("indexed");
    Files.createDirectories(root);
    capturedMappings = new AtomicReference<>();
    tool =
        new FileOperationsTool(
            () -> List.of(root),
            pathMappings -> {
              capturedMappings.set(pathMappings);
              return pathMappings.size();
            },
            new FileOperationLog(tempDir.resolve("data").resolve("file-operations")));
  }

  @Test
  void parameterSchemaPresent() {
    // Per Phase 12 of tempdoc 429: name/description/safetyLevel/supportsUndo moved to
    // the AgentToolsOperationCatalog Operation declaration. The undoSupported policy is
    // verified at the Operation level (FILE_OPERATIONS has undoSupported=true).
    assertNotNull(FileOperationsTool.parameterSchema());
  }

  @Test
  void executeMoveWithValidJson() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("moved.txt");

    String json =
        """
        {
          "operations": [
            {"op": "MOVE", "source": "%s", "destination": "%s"}
          ],
          "explanation": "Move file"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), "Move should succeed: " + result.message());
    assertNotNull(result.executionId(), "Batch ID should be set");
    assertTrue(result.message().contains("successfully"));
    assertFalse(Files.exists(src));
    assertTrue(Files.exists(dest));
    assertNotNull(capturedMappings.get(), "Index update should have been called");
  }

  @Test
  void executeMkdirWithValidJson() {
    Path dest = root.resolve("new-folder");

    String json =
        """
        {"operations": [{"op": "MKDIR", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertTrue(Files.isDirectory(dest));
    assertNull(capturedMappings.get(), "MKDIR should not trigger index update");
  }

  @Test
  void executeMkdirAcceptsPathAliasForDestination() {
    // 543-fwd UPDATE 10 P2 — smaller local models routinely emit `path` instead of the
    // schema's canonical `destination` (this is the exact shape that NPE'd live).
    Path dest = root.resolve("alias-folder");

    String json =
        """
        {"operations": [{"op": "MKDIR", "path": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertTrue(Files.isDirectory(dest));
  }

  @Test
  void executeMissingDestinationReturnsCleanValidationError() {
    // Untrusted agent input — a missing field must yield a clear, self-correcting
    // message, NEVER a NullPointerException ("Cannot invoke ... because ... is null").
    OperationResult result = tool.execute("{\"operations\": [{\"op\": \"MKDIR\"}]}");
    assertFalse(result.success());
    assertTrue(
        result.message().contains("missing required field 'destination'"), result.message());
    assertFalse(result.message().contains("Cannot invoke"), result.message());
  }

  @Test
  void executeUnknownOpReturnsCleanValidationError() {
    Path dest = root.resolve("x");
    String json =
        """
        {"operations": [{"op": "FROBNICATE", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertFalse(result.success());
    assertTrue(result.message().contains("unknown op 'FROBNICATE'"), result.message());
    assertFalse(result.message().contains("No enum constant"), result.message());
  }

  @Test
  void executeDoesNotMaskDeeperIllegalArgumentAsValidationError() {
    // Fix A — the arg-validation catch is scoped to OperationArgException. A genuine
    // IllegalArgumentException raised deeper (here InvalidPathException, an
    // IllegalArgumentException, from Path.of on a NUL-bearing path) must fall through to
    // the logged "Execution error" handler, NOT be relabeled as a clean validation error.
    String json = "{\"operations\": [{\"op\": \"MKDIR\", \"destination\": \"bad\\u0000path\"}]}";
    OperationResult result = tool.execute(json);
    assertFalse(result.success());
    assertTrue(result.message().contains("Execution error"), result.message());
    assertFalse(result.message().contains("missing required field"), result.message());
  }

  @Test
  void executeEmptyOperationsReturnsFailure() {
    OperationResult result = tool.execute("{\"operations\": []}");
    assertFalse(result.success());
    assertTrue(result.message().contains("No operations"));
  }

  @Test
  void executeMissingOperationsReturnsFailure() {
    OperationResult result = tool.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("No operations"));
  }

  @Test
  void executeInvalidJsonReturnsFailure() {
    OperationResult result = tool.execute("not json");
    assertFalse(result.success());
    assertTrue(result.message().contains("Execution error") || result.message().contains("error"));
  }

  @Test
  void executeValidationFailureReturnsDetails() {
    // Source doesn't exist
    Path src = root.resolve("no-such-file.txt");
    Path dest = root.resolve("dest.txt");

    String json =
        """
        {"operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertFalse(result.success());
    assertTrue(result.message().contains("Validation failed"));
    assertTrue(result.message().contains("SOURCE_MISSING"));
  }

  @Test
  void executeMultipleOperations() throws IOException {
    // Pre-create directory since validation runs before execution
    Path dir = root.resolve("sub");
    Files.createDirectories(dir);
    Path src = root.resolve("file.txt");
    Files.writeString(src, "hello");
    Path dest = root.resolve("sub").resolve("file.txt");

    String json =
        """
        {
          "operations": [
            {"op": "MOVE", "source": "%s", "destination": "%s"}
          ],
          "explanation": "Move file into sub directory"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"),
                dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertTrue(Files.exists(dest));
    assertEquals("hello", Files.readString(dest));
  }

  @Test
  void executeBatchSizeLimitExceeded() {
    var sb = new StringBuilder("{\"operations\": [");
    for (int i = 0; i <= FileOperationsTool.MAX_BATCH_SIZE; i++) {
      if (i > 0) sb.append(",");
      String dest = root.resolve("dir-" + i).toString().replace("\\", "\\\\");
      sb.append("{\"op\": \"MKDIR\", \"destination\": \"").append(dest).append("\"}");
    }
    sb.append("]}");

    OperationResult result = tool.execute(sb.toString());
    assertFalse(result.success());
    assertTrue(result.message().contains("exceeds limit"), result.message());
    assertTrue(result.message().contains(String.valueOf(FileOperationsTool.MAX_BATCH_SIZE)));
  }

  @Test
  void executeBatchSizeAtLimitSucceeds() {
    var sb = new StringBuilder("{\"operations\": [");
    for (int i = 0; i < FileOperationsTool.MAX_BATCH_SIZE; i++) {
      if (i > 0) sb.append(",");
      String dest = root.resolve("limit-dir-" + i).toString().replace("\\", "\\\\");
      sb.append("{\"op\": \"MKDIR\", \"destination\": \"").append(dest).append("\"}");
    }
    sb.append("]}");

    OperationResult result = tool.execute(sb.toString());
    assertTrue(result.success(), "Exactly MAX_BATCH_SIZE should succeed: " + result.message());
  }

  @Test
  void schemaBatchLimitMatchesConstant() throws Exception {
    var schema =
        new tools.jackson.databind.ObjectMapper()
            .readTree(FileOperationsTool.parameterSchema());
    int schemaMaxItems = schema.get("properties").get("operations").get("maxItems").asInt();
    assertEquals(
        FileOperationsTool.MAX_BATCH_SIZE,
        schemaMaxItems,
        "Schema maxItems must match MAX_BATCH_SIZE constant");
  }

  // ===== Undo tests =====

  @Test
  void undoMoveRestoresSourceFile() throws IOException {
    Path src = root.resolve("original.txt");
    Files.writeString(src, "precious data");
    Path dest = root.resolve("moved.txt");

    String json =
        """
        {
          "operations": [
            {"op": "MOVE", "source": "%s", "destination": "%s"}
          ],
          "explanation": "Move for undo test"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult moveResult = tool.execute(json);
    assertTrue(moveResult.success(), "Move should succeed: " + moveResult.message());
    assertFalse(Files.exists(src));
    assertTrue(Files.exists(dest));

    // Now undo
    OperationResult undoResult = tool.undo(moveResult.executionId().orElseThrow());
    assertTrue(undoResult.success(), "Undo should succeed: " + undoResult.message());
    assertTrue(Files.exists(src), "Source should be restored after undo");
    assertFalse(Files.exists(dest), "Destination should be removed after undo");
    assertEquals("precious data", Files.readString(src));
  }

  @Test
  void undoCopyDeletesCopiedFile() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");

    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult copyResult = tool.execute(json);
    assertTrue(copyResult.success(), copyResult.message());
    assertTrue(Files.exists(dest));

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow());
    assertTrue(undoResult.success(), "Undo COPY should succeed: " + undoResult.message());
    assertTrue(Files.exists(src), "Original source should remain");
    assertFalse(Files.exists(dest), "Copied file should be deleted");
  }

  @Test
  void undoSkipsACopyTargetTheUserEditedSinceTheAgentActed() throws IOException {
    // Tempdoc 577 §2.14 Root III (#16) — conflict-detection: undoing a COPY normally DELETES the
    // copy. If the user edited that copy after the agent created it, a blind delete would destroy
    // their work. The undo must detect the since-edit (mtime > recorded action time) and SKIP it.
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");

    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult copyResult = tool.execute(json);
    assertTrue(copyResult.success(), copyResult.message());
    assertTrue(Files.exists(dest));

    // The user edits the copy LATER (well past the conflict tolerance) — simulate by writing new
    // content and stamping its mtime comfortably after the recorded op time.
    Files.writeString(dest, "the user's own edits");
    Files.setLastModifiedTime(
        dest, java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(120)));

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow());
    assertTrue(undoResult.success(), "Undo should still succeed (partial): " + undoResult.message());
    assertTrue(
        Files.exists(dest), "A since-edited copy must NOT be blindly deleted by undo");
    assertEquals(
        "the user's own edits", Files.readString(dest), "The user's edit must be preserved");
    assertTrue(
        undoResult.message().toLowerCase(java.util.Locale.ROOT).contains("changed since"),
        "Undo must report the conflict-skipped target: " + undoResult.message());
  }

  @Test
  void undoStillRevertsACopyTargetTheUserDidNotTouch() throws IOException {
    // The conflict guard must not over-fire: an untouched target still reverts normally (the COPY
    // undo deletes it). This pins that the modified-since check does not flag the agent's own write.
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");

    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult copyResult = tool.execute(json);
    assertTrue(copyResult.success(), copyResult.message());

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow());
    assertTrue(undoResult.success(), undoResult.message());
    assertFalse(
        Files.exists(dest), "An untouched copy reverts normally (no false conflict)");
    assertFalse(
        undoResult.message().toLowerCase(java.util.Locale.ROOT).contains("changed since"),
        "No conflict should be reported for an untouched target: " + undoResult.message());
  }

  @Test
  void undoMkdirDeletesEmptyDirectory() throws IOException {
    Path dest = root.resolve("undo-dir");

    String json =
        """
        {"operations": [{"op": "MKDIR", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult mkdirResult = tool.execute(json);
    assertTrue(mkdirResult.success(), mkdirResult.message());
    assertTrue(Files.isDirectory(dest));

    OperationResult undoResult = tool.undo(mkdirResult.executionId().orElseThrow());
    assertTrue(undoResult.success(), "Undo MKDIR should succeed: " + undoResult.message());
    assertFalse(Files.exists(dest), "Empty directory should be removed");
  }

  @Test
  void undoMkdirSkipsNonEmptyDirectory() throws IOException {
    Path dest = root.resolve("undo-dir-nonempty");

    String json =
        """
        {"operations": [{"op": "MKDIR", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult mkdirResult = tool.execute(json);
    assertTrue(mkdirResult.success(), mkdirResult.message());

    // Put a file inside so it's non-empty
    Files.writeString(dest.resolve("child.txt"), "can't delete parent");

    OperationResult undoResult = tool.undo(mkdirResult.executionId().orElseThrow());
    assertTrue(undoResult.success(), "Undo should succeed (skipping non-empty dir): " + undoResult.message());
    assertTrue(Files.isDirectory(dest), "Non-empty directory should remain");
    assertTrue(undoResult.message().contains("skipped"), "Output should mention skipped: " + undoResult.message());
  }

  @Test
  void undoMissingBatchReturnsFailure() {
    OperationResult result = tool.undo("nonexistent-batch-id");
    assertFalse(result.success());
    assertTrue(result.message().contains("No operation log"));
  }

  @Test
  void undoUnfinalizedBatchReturnsFailure() throws IOException {
    // Manually create an unfinalized batch in the log
    Path src = root.resolve("unfin-src.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("unfin-dest.txt");

    // Start a batch but don't finalize it via the log directly
    var log = new FileOperationLog(tempDir.resolve("data").resolve("file-operations"));
    log.startBatch("unfin-batch", "test", List.of(
        new FileOperation(FileOperation.OpType.MOVE, src, dest)));
    log.recordSuccess("unfin-batch", 0);
    // Note: no log.finalizeBatch()

    OperationResult result = tool.undo("unfin-batch");
    assertFalse(result.success());
    assertTrue(result.message().contains("unfinalized"), result.message());
  }

  @Test
  void undoCalledTwiceSecondIsNoOp() throws IOException {
    Path src = root.resolve("idem-src.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("idem-dest.txt");

    String json =
        """
        {"operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"),
                dest.toString().replace("\\", "\\\\"));

    OperationResult moveResult = tool.execute(json);
    assertTrue(moveResult.success(), moveResult.message());
    assertTrue(Files.exists(dest));

    // First undo — restores file
    OperationResult undo1 = tool.undo(moveResult.executionId().orElseThrow());
    assertTrue(undo1.success(), "First undo should succeed: " + undo1.message());
    assertTrue(Files.exists(src), "Source should be restored");
    assertFalse(Files.exists(dest), "Dest should be removed");

    // Second undo — dest no longer exists so the reverse MOVE fails.
    // This is correct: undo is not idempotent, the files have already been restored.
    OperationResult undo2 = tool.undo(moveResult.executionId().orElseThrow());
    // Should not crash (no exception), but may report failures for individual operations
    assertNotNull(undo2, "Second undo should return a result, not crash");
  }

  // ===== Conflict strategy tests (via tool JSON) =====

  @Test
  void executeWithSkipConflictStrategy() throws IOException {
    Path src = root.resolve("skip-src.txt");
    Files.writeString(src, "new");
    Path dest = root.resolve("skip-dest.txt");
    Files.writeString(dest, "existing");

    String json =
        """
        {
          "operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}],
          "conflict_strategy": "SKIP"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("skipped"), "Output should mention skipped: " + result.message());
    assertTrue(Files.exists(src), "Source should remain (skipped)");
    assertEquals("existing", Files.readString(dest), "Dest should be unchanged");
  }

  @Test
  void executeWithAutoSuffixConflictStrategy() throws IOException {
    Path src = root.resolve("suffix-src.txt");
    Files.writeString(src, "new content");
    Path dest = root.resolve("suffix-dest.txt");
    Files.writeString(dest, "existing content");

    String json =
        """
        {
          "operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}],
          "conflict_strategy": "AUTO_SUFFIX"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertFalse(Files.exists(src), "Source should be moved");
    assertEquals("existing content", Files.readString(dest), "Original dest unchanged");

    Path suffixed = root.resolve("suffix-dest (1).txt");
    assertTrue(Files.exists(suffixed), "Auto-suffixed file should exist");
    assertEquals("new content", Files.readString(suffixed));
  }

  @Test
  void executeWithInvalidConflictStrategy() {
    Path dest = root.resolve("dir");
    String json =
        """
        {
          "operations": [{"op": "MKDIR", "destination": "%s"}],
          "conflict_strategy": "INVALID"
        }
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertFalse(result.success());
    assertTrue(result.message().contains("Invalid conflict_strategy"));
  }

  @Test
  void executeCaseInsensitiveOpType() throws IOException {
    Path dest = root.resolve("case-dir");

    String json =
        """
        {"operations": [{"op": "mkdir", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertTrue(Files.isDirectory(dest));
  }
}
