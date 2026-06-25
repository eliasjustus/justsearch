/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.telemetry.DiagnosticFileRetention;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction log for file operations. Writes a JSON log per batch to {@code
 * {dataDir}/file-operations/{batchId}.json}. Supports undo by recording every operation and its
 * outcome.
 */
public final class FileOperationLog {
  private static final Logger LOG = LoggerFactory.getLogger(FileOperationLog.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final Path logDir;

  public FileOperationLog(Path logDir) {
    this.logDir = logDir;
    try {
      Files.createDirectories(logDir);
    } catch (IOException e) {
      LOG.warn("Failed to create transaction log directory: {}", logDir, e);
    }
    DiagnosticFileRetention.pruneBefore(
        logDir, "", Instant.now().minus(java.time.Duration.ofDays(30)));
  }

  void startBatch(String batchId, String explanation, List<FileOperation> operations) {
    Map<String, Object> log = new HashMap<>();
    log.put("batchId", batchId);
    log.put("timestamp", Instant.now().toString());
    log.put("explanation", explanation);
    log.put(
        "operations",
        operations.stream()
            .map(
                op ->
                    Map.of(
                        "op", op.op().name(),
                        "source", op.source() != null ? op.source().toString() : "",
                        "destination", op.destination().toString()))
            .toList());
    log.put("executed", new ArrayList<Map<String, Object>>());
    writeBatchLog(batchId, log);
  }

  void recordSuccess(String batchId, int index) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "OK");
          entry.put("timestamp", Instant.now().toString());
          executed.add(entry);
        });
  }

  void recordFailure(String batchId, int index, String error) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "FAILED");
          entry.put("error", error);
          entry.put("timestamp", Instant.now().toString());
          executed.add(entry);
        });
  }

  void recordSkip(String batchId, int index, String reason) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "SKIPPED");
          entry.put("reason", reason);
          entry.put("timestamp", Instant.now().toString());
          executed.add(entry);
        });
  }

  void recordRename(String batchId, int index, Path originalDest, Path resolvedDest) {
    updateBatchLog(
        batchId,
        log -> {
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> executed = (List<Map<String, Object>>) log.get("executed");
          Map<String, Object> entry = new HashMap<>();
          entry.put("index", index);
          entry.put("status", "OK_RENAMED");
          entry.put("originalDestination", originalDest.toString());
          entry.put("resolvedDestination", resolvedDest.toString());
          entry.put("timestamp", Instant.now().toString());
          executed.add(entry);
        });
  }

  void finalizeBatch(String batchId) {
    updateBatchLog(batchId, log -> log.put("finalized", Instant.now().toString()));
  }

  /** Read a batch log from disk. Returns null if the batch does not exist. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> readBatch(String batchId) {
    Path logFile = logDir.resolve(batchId + ".json");
    if (!Files.exists(logFile)) {
      return null;
    }
    try {
      return MAPPER.readValue(logFile.toFile(), Map.class);
    } catch (Exception e) {
      LOG.error("Failed to read batch log: {}", batchId, e);
      return null;
    }
  }

  /** List recent batch logs sorted by modification time (newest first). */
  public List<Map<String, Object>> listBatches(int limit) {
    try {
      if (!Files.isDirectory(logDir)) {
        return List.of();
      }
      try (var stream = Files.list(logDir)) {
        return stream
            .filter(p -> p.toString().endsWith(".json"))
            .sorted(
                Comparator.<Path, FileTime>comparing(
                        p -> {
                          try {
                            return Files.getLastModifiedTime(p);
                          } catch (IOException e) {
                            return FileTime.fromMillis(0);
                          }
                        })
                    .reversed())
            .limit(limit)
            .map(this::readBatchFile)
            .filter(Objects::nonNull)
            .toList();
      }
    } catch (IOException e) {
      LOG.error("Failed to list batch logs", e);
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readBatchFile(Path path) {
    try {
      return MAPPER.readValue(path.toFile(), Map.class);
    } catch (Exception e) {
      LOG.error("Failed to read batch log file: {}", path, e);
      return null;
    }
  }

  private void writeBatchLog(String batchId, Map<String, Object> log) {
    try {
      Path logFile = logDir.resolve(batchId + ".json");
      atomicWrite(logFile, log);
    } catch (IOException e) {
      LOG.error("Failed to write transaction log for batch {}", batchId, e);
    }
  }

  @SuppressWarnings("unchecked")
  private void updateBatchLog(String batchId, Consumer<Map<String, Object>> updater) {
    try {
      Path logFile = logDir.resolve(batchId + ".json");
      if (!Files.exists(logFile)) {
        LOG.warn("Batch log not found: {}", batchId);
        return;
      }
      Map<String, Object> log = MAPPER.readValue(logFile.toFile(), Map.class);
      updater.accept(log);
      atomicWrite(logFile, log);
    } catch (IOException e) {
      LOG.error("Failed to update transaction log for batch {}", batchId, e);
    }
  }

  /** Write JSON to a temp file, then atomic-rename to the target. */
  private void atomicWrite(Path target, Map<String, Object> data) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    MAPPER.writeValue(tmp.toFile(), data);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

}
