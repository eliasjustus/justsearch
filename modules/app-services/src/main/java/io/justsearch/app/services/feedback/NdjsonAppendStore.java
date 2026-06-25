/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 580 §17 (Track C) — a generic append-only NDJSON record store (one JSON object per line,
 * synchronized append), backing the feedback/outcome-tier streams: per-query {@link FeatureSnapshot}s
 * (P1) and {@link ResultDisposition}s (P2). One persistence primitive, many record types — the AHA
 * answer to "two near-identical stores" (and what keeps the disposition store from being a clone of
 * the snapshot store).
 *
 * <p>Mirrors {@link io.justsearch.app.services.gpl.GplTrainingTripleStore}'s NDJSON pattern. Capture
 * is an <strong>observer</strong>, never a dependency of the query path: {@link #append} swallows
 * and logs all failures so feedback can never break search.
 *
 * @param <T> the record type persisted, one per line
 */
public final class NdjsonAppendStore<T> {

  private static final Logger log = LoggerFactory.getLogger(NdjsonAppendStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path storeFile;
  private final Class<T> type;

  /**
   * @param storeFile the NDJSON file (its parent directory is created if absent)
   * @param type the record type, used to deserialize on {@link #readAll}
   */
  public NdjsonAppendStore(Path storeFile, Class<T> type) {
    this.storeFile = storeFile;
    this.type = type;
    try {
      Files.createDirectories(storeFile.getParent());
    } catch (IOException e) {
      log.warn("Failed to create store dir {}: {}", storeFile.getParent(), e.getMessage());
      log.debug("Failed to create store dir (stack trace)", e);
    }
  }

  /** Appends one record as an NDJSON line. Best-effort — never throws. */
  public synchronized void append(T record) {
    try {
      String line = MAPPER.writeValueAsString(record) + "\n";
      Files.writeString(
          storeFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (Exception e) {
      log.warn("Failed to persist {} record: {}", type.getSimpleName(), e.getMessage());
      log.debug("Record persist failure (stack trace)", e);
    }
  }

  /** Reads all records (diagnostic/test; the single-user store stays small). */
  public synchronized List<T> readAll() throws IOException {
    List<T> out = new ArrayList<>();
    if (!Files.exists(storeFile)) {
      return out;
    }
    for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
      if (line.isBlank()) {
        continue;
      }
      out.add(MAPPER.readValue(line, type));
    }
    return out;
  }

  /** The backing NDJSON file path (package-private for tests / diagnostics). */
  Path storeFile() {
    return storeFile;
  }
}
