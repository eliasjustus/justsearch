/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort persistence for migration enumerator progress.
 *
 * <p>This is an observability aid only. It does NOT make enumeration resumable; on restart the worker
 * may re-enumerate from scratch. The persisted snapshot exists to keep "last known progress" visible
 * across restarts.
 */
public final class MigrationProgressStore {
  private static final Logger log = LoggerFactory.getLogger(MigrationProgressStore.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long MIN_WRITE_INTERVAL_MS = 1_000L;

  private final Path progressPath;
  private final AtomicLong lastWriteMs = new AtomicLong(0L);

  public MigrationProgressStore(Path indexBasePath) {
    this.progressPath = indexBasePath.resolve("migration_progress.json");
  }

  public Path progressPath() {
    return progressPath;
  }

  public MigrationProgressSnapshot readBestEffort() {
    try {
      if (!Files.exists(progressPath)) {
        return null;
      }
      JsonNode n = JSON.readTree(Files.readString(progressPath, StandardCharsets.UTF_8));
      return new MigrationProgressSnapshot(
          n.path("enumerator_running").asBoolean(false),
          n.path("enumerator_done").asBoolean(false),
          n.path("roots_total").asLong(0L),
          n.path("roots_done").asLong(0L),
          n.path("files_seen").asLong(0L),
          n.path("files_enqueued").asLong(0L),
          n.path("started_at_ms").asLong(0L),
          n.path("finished_at_ms").asLong(0L),
          n.path("last_path").asText(""));
    } catch (Exception e) {
      log.debug("Failed to read migration progress (best-effort): {}", e.getMessage());
      return null;
    }
  }

  public void writeBestEffort(MigrationProgressSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    long now = System.currentTimeMillis();
    long prev = lastWriteMs.get();
    boolean force = snapshot.enumeratorDone();
    if (!force && (now - prev) < MIN_WRITE_INTERVAL_MS) {
      return;
    }
    lastWriteMs.set(now);

    try {
      Files.createDirectories(progressPath.getParent());
      Path tmp = progressPath.resolveSibling(progressPath.getFileName() + ".tmp");

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("format_version", 1);
      out.put("updated_at_ms", now);
      out.put("enumerator_running", snapshot.enumeratorRunning());
      out.put("enumerator_done", snapshot.enumeratorDone());
      out.put("roots_total", snapshot.rootsTotal());
      out.put("roots_done", snapshot.rootsDone());
      out.put("files_seen", snapshot.filesSeen());
      out.put("files_enqueued", snapshot.filesEnqueued());
      out.put("started_at_ms", snapshot.startedAtMs());
      out.put("finished_at_ms", snapshot.finishedAtMs());
      out.put("last_path", snapshot.lastPath() == null ? "" : snapshot.lastPath());

      Files.writeString(tmp, JSON.writeValueAsString(out), StandardCharsets.UTF_8);
      try {
        Files.move(
            tmp,
            progressPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, progressPath, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      log.debug("Failed to persist migration progress (best-effort): {}", e.getMessage());
    }
  }
}
