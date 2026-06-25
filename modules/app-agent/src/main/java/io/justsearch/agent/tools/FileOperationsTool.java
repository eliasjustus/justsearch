/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for executing file system operations: MOVE, RENAME, MKDIR, COPY.
 *
 * <p>All paths are sandboxed to indexed roots. Operations are logged and can be undone. After
 * MOVE/RENAME operations, the Lucene index is updated to reflect new file paths.
 *
 * <p>Safety level is WRITE — requires user approval before execution.
 */
/**
 * Destructive file-operations tool. Per Phase 12 of tempdoc 429: previously implemented
 * {@code ToolDefinition}; now a plain class invoked via
 * {@link io.justsearch.app.services.registry.operations.handlers.FileOperationsHandler}.
 * The handler delegates {@code execute(...)} and {@code undo(...)} to this class; the
 * substrate's {@code OperationPolicy.undoSupported()} replaces the deleted
 * {@code supportsUndo()} contract.
 */
public final class FileOperationsTool {
  private static final Logger LOG = LoggerFactory.getLogger(FileOperationsTool.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  static final int MAX_BATCH_SIZE = 50;
  // Tempdoc 577 §2.14 Root III (#16) — conflict-detection tolerance: the slack between an op's
  // filesystem write and the log's Instant.now() record, so the agent's own write never reads as a
  // user since-edit. A real user edit is seconds-to-minutes later, well outside this window.
  private static final Duration CONFLICT_TOLERANCE = Duration.ofSeconds(2);

  private static final String PARAMETER_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "operations": {
            "type": "array",
            "description": "List of file operations to execute sequentially",
            "maxItems": 50,
            "items": {
              "type": "object",
              "properties": {
                "op": {
                  "type": "string",
                  "enum": ["MOVE", "RENAME", "MKDIR", "COPY"],
                  "description": "Operation type"
                },
                "source": {
                  "type": "string",
                  "description": "Source file or folder path (not needed for MKDIR)"
                },
                "destination": {
                  "type": "string",
                  "description": "Destination file or folder path"
                }
              },
              "required": ["op", "destination"]
            }
          },
          "explanation": {
            "type": "string",
            "description": "Human-readable explanation of what these operations accomplish"
          },
          "conflict_strategy": {
            "type": "string",
            "enum": ["FAIL", "SKIP", "AUTO_SUFFIX"],
            "default": "FAIL",
            "description": "How to handle destination conflicts: FAIL (default) aborts on conflict, SKIP skips conflicting operations, AUTO_SUFFIX renames to a unique name like file (1).txt"
          }
        },
        "required": ["operations"]
      }
      """;

  private final FileOperationExecutor executor;
  private final FileOperationLog transactionLog;

  /**
   * Creates a new FileOperationsTool.
   *
   * @param indexedRootsSupplier supplies current indexed roots for path sandboxing
   * @param indexUpdateCallback callback to update the search index after file moves
   * @param transactionLog shared transaction log for recording operations and undo
   */
  public FileOperationsTool(
      Supplier<List<Path>> indexedRootsSupplier,
      IndexUpdateCallback indexUpdateCallback,
      FileOperationLog transactionLog) {
    this.transactionLog = transactionLog;
    this.executor =
        new FileOperationExecutor(indexedRootsSupplier, indexUpdateCallback, transactionLog);
  }

  /** Per tempdoc 429 §C.G: parameter schema preserved as a constant for unit tests. */
  public static String parameterSchema() {
    return PARAMETER_SCHEMA;
  }

  public OperationResult execute(String argumentsJson) {
    try {
      JsonNode args = MAPPER.readTree(argumentsJson);
      JsonNode opsNode = args.get("operations");
      if (opsNode == null || !opsNode.isArray() || opsNode.isEmpty()) {
        return OperationResult.failure("No operations specified");
      }
      if (opsNode.size() > MAX_BATCH_SIZE) {
        return OperationResult.failure(
            "Too many operations: "
                + opsNode.size()
                + " exceeds limit of "
                + MAX_BATCH_SIZE
                + ". Split into smaller batches.");
      }

      List<FileOperation> operations = parseOperations(opsNode);
      String explanation =
          args.has("explanation") ? args.get("explanation").asText() : "File operations";

      ConflictStrategy strategy = ConflictStrategy.FAIL;
      if (args.has("conflict_strategy") && !args.get("conflict_strategy").isNull()) {
        try {
          strategy =
              ConflictStrategy.valueOf(
                  args.get("conflict_strategy").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
          return OperationResult.failure(
              "Invalid conflict_strategy: " + args.get("conflict_strategy").asText());
        }
      }

      // Validate all operations first
      var validation = executor.validate(operations, strategy);
      if (!validation.allValid()) {
        return OperationResult.failure("Validation failed: " + validation.summary());
      }

      // Execute
      var report = executor.execute(operations, explanation, strategy);

      if (report.allSucceeded()) {
        return OperationResult.success(report.summary(), report.batchId());
      } else {
        return OperationResult.failure(report.summary());
      }

    } catch (OperationArgException e) {
      // Request-validation problem (bad/missing operation fields) — a clean,
      // self-correcting message for the agent, not an "Execution error". Scoped to
      // THIS exception so a genuine IllegalArgumentException from validate/execute
      // still falls through to the logged generic handler below.
      return OperationResult.failure(e.getMessage());
    } catch (Exception e) {
      LOG.error("FileOperationsTool execution failed", e);
      return OperationResult.failure("Execution error: " + e.getMessage());
    }
  }

  public OperationResult undo(String executionId) {
    try {
      Map<String, Object> batch = transactionLog.readBatch(executionId);
      if (batch == null) {
        return OperationResult.failure("No operation log found for batch: " + executionId);
      }
      if (!batch.containsKey("finalized")) {
        return OperationResult.failure("Cannot undo unfinalized batch: " + executionId);
      }

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> operations =
          (List<Map<String, Object>>) batch.get("operations");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> executed =
          (List<Map<String, Object>>) batch.get("executed");

      if (operations == null || executed == null) {
        return OperationResult.failure("Malformed batch log: " + executionId);
      }

      // Collect indices and resolved destinations from successfully executed ops
      Set<Integer> successIndices = new LinkedHashSet<>();
      Map<Integer, Path> resolvedDestinations = new HashMap<>();
      // Tempdoc 577 §2.14 Root III (#16) — the per-op completion time, the conflict-detection
      // baseline: a target whose mtime is later than this was changed AFTER the agent acted.
      Map<Integer, Instant> executedAt = new HashMap<>();

      for (Map<String, Object> entry : executed) {
        int index = ((Number) entry.get("index")).intValue();
        String status = (String) entry.get("status");
        if ("OK".equals(status) || "OK_RENAMED".equals(status)) {
          successIndices.add(index);
          if ("OK_RENAMED".equals(status) && entry.containsKey("resolvedDestination")) {
            resolvedDestinations.put(index, Path.of((String) entry.get("resolvedDestination")));
          }
          Object ts = entry.get("timestamp");
          if (ts instanceof String s) {
            try {
              executedAt.put(index, Instant.parse(s));
            } catch (DateTimeParseException ignored) {
              // Unparseable timestamp ⇒ no conflict baseline; the op reverts as before.
            }
          }
        }
      }

      if (successIndices.isEmpty()) {
        return OperationResult.success("Nothing to undo (no successful operations in batch).");
      }

      // Build reverse operations in reverse order
      List<Integer> indices = new ArrayList<>(successIndices);
      Collections.reverse(indices);

      List<FileOperation> reverseMovesAndRenames = new ArrayList<>();
      List<UndoAction> directActions = new ArrayList<>();
      // Tempdoc 577 §2.14 Root III (#16) — conflict-detection: a target the USER changed since the
      // agent acted must NOT be blindly reverted (a COPY-undo deletes it; a MOVE-undo relocates the
      // user's edit). Such ops are collected here and SKIPPED, then reported "changed since — review
      // before undo", so the bulk undo is honest and never destroys a since-edited file.
      List<Path> conflicted = new ArrayList<>();

      for (int idx : indices) {
        Map<String, Object> opMap = operations.get(idx);
        FileOperation.OpType opType =
            FileOperation.OpType.valueOf((String) opMap.get("op"));
        String sourceStr = (String) opMap.get("source");
        Path source =
            sourceStr != null && !sourceStr.isEmpty() ? Path.of(sourceStr) : null;
        Path destination =
            resolvedDestinations.containsKey(idx)
                ? resolvedDestinations.get(idx)
                : Path.of((String) opMap.get("destination"));

        // MKDIR-undo only deletes an EMPTY directory (already guarded below), so it can never lose
        // user content — exempt it from the modified-since check. MOVE/RENAME/COPY-undo touch a
        // file target whose since-edit would be lost, so they are conflict-checked.
        if (opType != FileOperation.OpType.MKDIR
            && modifiedSince(destination, executedAt.get(idx))) {
          conflicted.add(destination);
          continue; // do not revert a target the user changed since the agent acted
        }

        switch (opType) {
          case MOVE, RENAME ->
              reverseMovesAndRenames.add(
                  new FileOperation(FileOperation.OpType.MOVE, destination, source));
          case MKDIR -> directActions.add(new UndoAction(opType, destination));
          case COPY -> directActions.add(new UndoAction(opType, destination));
        }
      }

      StringBuilder result = new StringBuilder();
      int undoneCount = 0;
      int skippedCount = 0;

      // Execute reverse MOVE/RENAME through executor for index updates
      if (!reverseMovesAndRenames.isEmpty()) {
        var validation = executor.validate(reverseMovesAndRenames);
        if (!validation.allValid()) {
          return OperationResult.failure("Undo validation failed: " + validation.summary());
        }
        var report =
            executor.execute(reverseMovesAndRenames, "Undo of batch " + executionId);
        undoneCount += report.successCount();
        result.append(report.summary());
      }

      // Handle MKDIR and COPY undo directly (no executor pipeline needed)
      for (UndoAction action : directActions) {
        try {
          if (action.opType == FileOperation.OpType.MKDIR) {
            if (Files.isDirectory(action.path) && isDirectoryEmpty(action.path)) {
              Files.delete(action.path);
              undoneCount++;
            } else {
              skippedCount++;
              LOG.info("Skipping MKDIR undo for non-empty directory: {}", action.path);
            }
          } else if (action.opType == FileOperation.OpType.COPY) {
            if (Files.exists(action.path)) {
              if (Files.isDirectory(action.path)) {
                executor.deleteDirectory(action.path);
              } else {
                Files.delete(action.path);
              }
              undoneCount++;
            } else {
              skippedCount++;
            }
          }
        } catch (IOException e) {
          LOG.warn("Undo action failed for {}: {}", action.path, e.getMessage());
          skippedCount++;
        }
      }

      if (result.isEmpty()) {
        result.append(
            String.format("Undo completed: %d operations reversed", undoneCount));
      }
      if (skippedCount > 0) {
        result.append(String.format(", %d skipped", skippedCount));
      }
      // Tempdoc 577 §2.14 Root III (#16) — surface the conflict-skipped targets so the user knows
      // exactly what was NOT reverted (and why), instead of a silent partial undo.
      if (!conflicted.isEmpty()) {
        result.append(
            String.format(
                ", %d changed since the agent acted — not reverted (review before undo): %s",
                conflicted.size(),
                conflicted.stream().map(Path::toString).collect(Collectors.joining(", "))));
      }
      result.append(".");

      return OperationResult.success(result.toString());

    } catch (Exception e) {
      LOG.error("Undo failed for batch {}", executionId, e);
      return OperationResult.failure("Undo error: " + e.getMessage());
    }
  }

  private boolean isDirectoryEmpty(Path dir) throws IOException {
    try (var entries = Files.list(dir)) {
      return entries.findFirst().isEmpty();
    }
  }

  /**
   * Tempdoc 577 §2.14 Root III (#16) — the conflict-detection predicate: true iff {@code target}
   * exists and was modified AFTER the agent's recorded action time (i.e. the user changed it since,
   * so reverting would destroy that change). A small tolerance absorbs the gap between the
   * filesystem write and the log's {@code Instant.now()} record, avoiding false positives. A
   * missing target or unknown baseline is NOT a conflict (a different skip path handles those).
   */
  private boolean modifiedSince(Path target, Instant actionTime) {
    if (actionTime == null) return false;
    try {
      if (!Files.exists(target)) return false;
      Instant mtime = Files.getLastModifiedTime(target).toInstant();
      return mtime.isAfter(actionTime.plus(CONFLICT_TOLERANCE));
    } catch (IOException e) {
      return false; // cannot determine ⇒ do not block the undo
    }
  }

  /**
   * Parse the agent-supplied operations. Agent tool arguments are UNTRUSTED input
   * (§32.9 — "treat the LLM as an untrusted client"): a missing/misspelled field must
   * produce a clear, self-correcting validation error, never an NPE. Mirrors the
   * defensive idiom this class already uses for `explanation`/`conflict_strategy`.
   */
  private List<FileOperation> parseOperations(JsonNode opsNode) {
    List<FileOperation> operations = new ArrayList<>();
    int index = 0;
    for (JsonNode opNode : opsNode) {
      String opStr = textField(opNode, "op");
      if (opStr == null || opStr.isBlank()) {
        throw new OperationArgException("operation " + index + ": missing required field 'op'");
      }
      FileOperation.OpType opType;
      try {
        opType = FileOperation.OpType.valueOf(opStr.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new OperationArgException(
            "operation " + index + ": unknown op '" + opStr + "' (expected one of MOVE, RENAME, MKDIR, COPY)");
      }

      String sourceStr = textField(opNode, "source");
      Path source = (sourceStr != null && !sourceStr.isEmpty()) ? Path.of(sourceStr) : null;

      // Accept `path` as an alias for the schema's canonical `destination`: smaller
      // local models routinely emit `path` (notably for mkdir).
      String destStr = textField(opNode, "destination");
      if (destStr == null || destStr.isBlank()) {
        destStr = textField(opNode, "path");
      }
      if (destStr == null || destStr.isBlank()) {
        throw new OperationArgException(
            "operation " + index + " (" + opStr + "): missing required field 'destination'");
      }

      operations.add(new FileOperation(opType, source, Path.of(destStr)));
      index++;
    }
    return operations;
  }

  /** Null-safe text extraction: returns null when the field is absent or JSON null. */
  private static String textField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return (value == null || value.isNull()) ? null : value.asText();
  }

  /**
   * Thrown by {@link #parseOperations} for malformed/missing operation fields. A dedicated
   * type (not a bare IllegalArgumentException) so {@link #execute} can return a clean,
   * agent-facing validation message for THESE errors only — without also swallowing an
   * IllegalArgumentException raised deeper in validation/execution (which must stay a
   * logged "Execution error").
   */
  private static final class OperationArgException extends RuntimeException {
    OperationArgException(String message) {
      super(message);
    }
  }

  private record UndoAction(FileOperation.OpType opType, Path path) {}

  /** Callback for updating the search index after file MOVE/RENAME operations. */
  @FunctionalInterface
  public interface IndexUpdateCallback {
    /**
     * Updates document paths in the search index.
     *
     * @param pathMappings map of old absolute path to new absolute path
     * @return number of parent documents updated
     */
    int updatePaths(Map<Path, Path> pathMappings);
  }
}
