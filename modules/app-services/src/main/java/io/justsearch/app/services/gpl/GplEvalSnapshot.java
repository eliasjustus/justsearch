/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.app.api.gpl.GplEvalData;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable snapshot of corpus state at GPL evaluation time.
 *
 * <p>Persisted to {@code gpl-eval-snapshot.json} in the data directory so that the revalidation
 * trigger can compare the current corpus state against the last-evaluated state across process
 * restarts.
 */
public final class GplEvalSnapshot implements GplEvalData {

  private static final Logger log = LoggerFactory.getLogger(GplEvalSnapshot.class);

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private final long docCount;
  private final Map<String, Long> mimeDistribution;
  private final long tripleCount;
  // Stored as ISO-8601 string to avoid jackson-datatype-jsr310 dependency.
  private final String evaluatedAt;

  @JsonCreator
  public GplEvalSnapshot(
      @JsonProperty("docCount") long docCount,
      @JsonProperty("mimeDistribution") Map<String, Long> mimeDistribution,
      @JsonProperty("tripleCount") long tripleCount,
      @JsonProperty("evaluatedAt") String evaluatedAt) {
    this.docCount = docCount;
    this.mimeDistribution = mimeDistribution != null ? Map.copyOf(mimeDistribution) : Map.of();
    this.tripleCount = tripleCount;
    this.evaluatedAt = evaluatedAt;
  }

  /** Creates a snapshot from current Worker status, MIME facet counts, and triple count. */
  public static GplEvalSnapshot capture(
      StatusResponse status, Map<String, Long> mimeCounts, long tripleCount) {
    return new GplEvalSnapshot(
        status.getCore().getDocCount(), mimeCounts, tripleCount, Instant.now().toString());
  }

  /**
   * Loads a snapshot from disk. Returns {@code null} if the file does not exist, is empty, or
   * cannot be parsed.
   */
  public static GplEvalSnapshot load(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return MAPPER.readValue(file.toFile(), GplEvalSnapshot.class);
    } catch (Exception e) {
      log.warn("Failed to load GPL eval snapshot from {}: {}", file, e.getMessage());
      log.debug("Failed to load GPL eval snapshot (stack trace)", e);
      return null;
    }
  }

  /** Best-effort persist to disk. Uses atomic rename to avoid partial-read races. */
  public void save(Path file) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      MAPPER.writeValue(tmp.toFile(), this);
      try {
        Files.move(
            tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      log.warn("Failed to save GPL eval snapshot to {}: {}", file, e.getMessage());
    }
  }

  @Override
  @JsonProperty("docCount")
  public long docCount() {
    return docCount;
  }

  @Override
  @JsonProperty("mimeDistribution")
  public Map<String, Long> mimeDistribution() {
    return mimeDistribution;
  }

  @Override
  @JsonProperty("tripleCount")
  public long tripleCount() {
    return tripleCount;
  }

  /** Returns the evaluation timestamp as an {@link Instant}. */
  @Override
  @com.fasterxml.jackson.annotation.JsonIgnore
  public Instant evaluatedAt() {
    return evaluatedAt != null ? Instant.parse(evaluatedAt) : null;
  }

  /** Returns the raw ISO-8601 string (used by Jackson for {@code evaluatedAt} JSON property). */
  @JsonProperty("evaluatedAt")
  public String evaluatedAtRaw() {
    return evaluatedAt;
  }
}
