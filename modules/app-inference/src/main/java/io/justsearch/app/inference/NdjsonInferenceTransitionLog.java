/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * NDJSON-backed {@link InferenceTransitionLog}.
 *
 * <p>Appends one JSON line per recorded transition to
 * {@code <dataDir>/telemetry/inference-transitions.ndjson}. File creation is lazy on the
 * first record. Best-effort: I/O failures log at WARN and otherwise no-op so the runner's
 * happy path is never blocked by sidecar trouble.
 *
 * <p>Retention: the file is pruned by age on every {@link #record} call (default 7 days,
 * configurable via constructor). Sized to never need rotation in practice — at the worst
 * case of 100 transitions/day with ~200 bytes/line, the file stays under 200 KB across the
 * retention window. If pathological transition rates ever blow this assumption, switch to
 * rolling by size like {@code NdjsonSpanExporter} does.
 *
 * <p>Tempdoc 518 Appendix G Wave B.1.
 */
public final class NdjsonInferenceTransitionLog implements InferenceTransitionLog {

  private static final Logger LOG = LoggerFactory.getLogger(NdjsonInferenceTransitionLog.class);

  private final Path file;
  private final Duration retention;

  public NdjsonInferenceTransitionLog(Path dataDir) {
    this(dataDir, Duration.ofDays(7));
  }

  public NdjsonInferenceTransitionLog(Path dataDir, Duration retention) {
    this.file = dataDir.resolve("telemetry").resolve("inference-transitions.ndjson");
    this.retention = retention;
  }

  /** Test/diagnostic accessor — path of the underlying NDJSON file. */
  public Path file() {
    return file;
  }

  @Override
  public void record(
      long timestampMs,
      String from,
      String to,
      String reason,
      boolean success,
      long durationMs,
      String wireCode,
      long generation) {
    try {
      Files.createDirectories(file.getParent());
      String line = formatLine(timestampMs, from, to, reason, success, durationMs, wireCode, generation);
      Files.writeString(
          file,
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      maybePrune();
    } catch (IOException | RuntimeException e) {
      LOG.warn(
          "InferenceTransitionLog write failed (best-effort): {} → {} ({})",
          from,
          to,
          e.getMessage());
    }
  }

  private void maybePrune() {
    // Cheap age-prune: read the file, drop lines older than the cutoff, rewrite. Linear in
    // file size; acceptable because the file is bounded by retention * transition-rate, which
    // is small in any realistic scenario.
    try {
      if (!Files.isRegularFile(file)) {
        return;
      }
      long cutoff = System.currentTimeMillis() - retention.toMillis();
      java.util.List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      java.util.List<String> kept = new java.util.ArrayList<>(lines.size());
      for (String line : lines) {
        long ts = parseTimestampMs(line);
        if (ts < 0 || ts >= cutoff) {
          kept.add(line);
        }
      }
      if (kept.size() != lines.size()) {
        Files.write(file, kept, StandardCharsets.UTF_8);
      }
    } catch (IOException | RuntimeException e) {
      LOG.debug("InferenceTransitionLog prune failed (best-effort): {}", e.getMessage());
    }
  }

  /** Extract {@code "timestampMs":<long>} from a JSON line without a full parse. -1 on failure. */
  private static long parseTimestampMs(String line) {
    int key = line.indexOf("\"timestampMs\":");
    if (key < 0) return -1;
    int start = key + "\"timestampMs\":".length();
    int end = start;
    while (end < line.length() && Character.isDigit(line.charAt(end))) {
      end++;
    }
    if (end == start) return -1;
    try {
      return Long.parseLong(line.substring(start, end));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Jackson-backed JSON line. Tempdoc 518 Wave A-E defect Fix-6 — replaces the prior
   * hand-rolled serializer that only escaped {@code \\} and {@code "}. The old shape was
   * safe for today's bounded code-enum values but would have corrupted the NDJSON line if
   * a future enum addition ever included a control character (newline, tab): the line would
   * split into two, breaking the append-only invariant.
   */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String formatLine(
      long timestampMs,
      String from,
      String to,
      String reason,
      boolean success,
      long durationMs,
      String wireCode,
      long generation) {
    java.util.Map<String, Object> record = new java.util.LinkedHashMap<>();
    record.put("timestampMs", timestampMs);
    record.put("ts", Instant.ofEpochMilli(timestampMs).toString());
    record.put("fromMode", from);
    record.put("toMode", to);
    record.put("reason", reason);
    record.put("success", success);
    record.put("durationMs", durationMs);
    if (wireCode != null) {
      record.put("wireCode", wireCode);
    }
    record.put("generation", generation);
    try {
      return MAPPER.writeValueAsString(record) + "\n";
    } catch (Exception e) {
      // Shouldn't happen with a plain Map<String, Object>; minimal fallback so the line
      // never disappears entirely on a serialization edge case.
      return "{\"timestampMs\":" + timestampMs + ",\"serialization_error\":\""
          + e.getClass().getSimpleName() + "\"}\n";
    }
  }
}
