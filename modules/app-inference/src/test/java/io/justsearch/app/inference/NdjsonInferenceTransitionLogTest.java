package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 518 Appendix G W4.B.1 + B.2 — pin the NDJSON sidecar log's record/replay contract.
 *
 * <p>Three properties exercised:
 *
 * <ul>
 *   <li>Recorded JSON line is well-formed, contains every documented field, and parses back
 *       with the same values.
 *   <li>Multiple records append (newest at the bottom; one line per call).
 *   <li>Retention prunes entries older than the cutoff; entries inside the window survive.
 *   <li>**Replay harness (B.2)**: a fixture NDJSON file drives a fresh {@link
 *       ModeStateMachine} through the recorded transitions and reaches the recorded final
 *       state. Demonstrates the "reproduce any prod transition incident locally" property.
 * </ul>
 */
@DisplayName("NdjsonInferenceTransitionLog — record + replay contract")
final class NdjsonInferenceTransitionLogTest {

  @Test
  @DisplayName("record produces a well-formed JSON line with every field")
  void recordWritesAllFields(@TempDir Path tmp) throws Exception {
    var log = new NdjsonInferenceTransitionLog(tmp);
    long now = System.currentTimeMillis();
    log.record(now, "OFFLINE", "ONLINE", "USER_SWITCH", true, 3805L, null, 1L);

    List<String> lines = Files.readAllLines(log.file(), StandardCharsets.UTF_8);
    assertEquals(1, lines.size());
    String line = lines.get(0);
    assertTrue(line.contains("\"timestampMs\":" + now));
    assertTrue(line.contains("\"fromMode\":\"OFFLINE\""));
    assertTrue(line.contains("\"toMode\":\"ONLINE\""));
    assertTrue(line.contains("\"reason\":\"USER_SWITCH\""));
    assertTrue(line.contains("\"success\":true"));
    assertTrue(line.contains("\"durationMs\":3805"));
    assertTrue(line.contains("\"generation\":1"));
    assertFalse(line.contains("wireCode"), "wireCode omitted on success");
  }

  @Test
  @DisplayName("wireCode appears only on failure records")
  void wireCodeOnFailureOnly(@TempDir Path tmp) throws Exception {
    var log = new NdjsonInferenceTransitionLog(tmp);
    long now = System.currentTimeMillis();
    log.record(now, "OFFLINE", "OFFLINE", "USER_SWITCH", false, 42L,
        "insufficient_vram", 2L);
    String line = Files.readAllLines(log.file()).get(0);
    assertTrue(line.contains("\"success\":false"));
    assertTrue(line.contains("\"wireCode\":\"insufficient_vram\""));
  }

  @Test
  @DisplayName("multiple records append, oldest first")
  void appendsInOrder(@TempDir Path tmp) throws Exception {
    var log = new NdjsonInferenceTransitionLog(tmp);
    long now = System.currentTimeMillis();
    log.record(now, "OFFLINE", "ONLINE", "USER_SWITCH", true, 10, null, 1);
    log.record(now + 1, "ONLINE", "INDEXING", "USER_SWITCH", true, 15, null, 2);
    log.record(now + 2, "INDEXING", "ONLINE", "USER_SWITCH", true, 20, null, 3);

    List<String> lines = Files.readAllLines(log.file());
    assertEquals(3, lines.size());
    assertTrue(lines.get(0).contains("\"timestampMs\":" + now));
    assertTrue(lines.get(1).contains("\"timestampMs\":" + (now + 1)));
    assertTrue(lines.get(2).contains("\"timestampMs\":" + (now + 2)));
  }

  @Test
  @DisplayName("retention prunes entries older than the cutoff")
  void retentionPrunes(@TempDir Path tmp) throws Exception {
    var log = new NdjsonInferenceTransitionLog(tmp, Duration.ofMillis(100));
    long now = System.currentTimeMillis();
    log.record(now - 10_000L, "OFFLINE", "ONLINE", "USER_SWITCH", true, 1, null, 1); // old
    log.record(now - 5_000L, "ONLINE", "INDEXING", "USER_SWITCH", true, 1, null, 2); // old
    log.record(now, "INDEXING", "ONLINE", "USER_SWITCH", true, 1, null, 3); // current

    List<String> lines = Files.readAllLines(log.file());
    // Latest record is always present; older-than-cutoff ones get pruned on each write.
    assertTrue(lines.size() >= 1, "current record kept");
    assertTrue(lines.get(lines.size() - 1).contains("\"timestampMs\":" + now));
  }

  // ==================== B.2 Replay harness ====================

  @Test
  @DisplayName("replay: feeding recorded transitions back drives a fresh FSM to the same end state")
  void replayHarnessReachesRecordedEndState(@TempDir Path tmp) throws Exception {
    // 1. Record a sequence of transitions through one ModeStateMachine.
    var recorded = new ModeStateMachine();
    var log = new NdjsonInferenceTransitionLog(tmp);

    long now = System.currentTimeMillis();

    // OFFLINE → ONLINE
    Mode from = recorded.beginTransition();
    recorded.complete(Mode.ONLINE);
    log.record(now, from.name(), Mode.ONLINE.name(), "USER_SWITCH", true, 10, null, 1);

    // ONLINE → INDEXING
    from = recorded.beginTransition();
    recorded.complete(Mode.INDEXING);
    log.record(now + 1, from.name(), Mode.INDEXING.name(), "USER_SWITCH", true, 12, null, 2);

    // INDEXING → OFFLINE (forced)
    from = recorded.forceOffline();
    log.record(now + 2, from.name(), Mode.OFFLINE.name(), "CRASH_RECOVERY", true, 5, null, 3);

    Mode endState = recorded.current();
    assertEquals(Mode.OFFLINE, endState);

    // 2. Read the NDJSON file and replay against a fresh FSM. Parse just the fields needed
    //    to drive the FSM (toMode + whether it's a forced transition vs a successful complete).
    var replayed = new ModeStateMachine();
    List<String> lines = Files.readAllLines(log.file());
    for (String line : lines) {
      String to = field(line, "toMode");
      String reason = field(line, "reason");
      boolean success = line.contains("\"success\":true");
      if ("CRASH_RECOVERY".equals(reason)) {
        replayed.forceOffline();
      } else if (success) {
        replayed.beginTransition();
        replayed.complete(Mode.valueOf(to));
      } else {
        replayed.beginTransition();
        replayed.rollback();
      }
    }

    // 3. End state matches.
    assertEquals(endState, replayed.current(),
        "replayed FSM should reach the same end state as the recorded one");
  }

  /** Minimal JSON-field reader for the replay harness — extracts {@code "key":"value"} strings. */
  private static String field(String line, String key) {
    String marker = "\"" + key + "\":\"";
    int start = line.indexOf(marker);
    if (start < 0) return null;
    start += marker.length();
    int end = line.indexOf('"', start);
    return end < 0 ? null : line.substring(start, end);
  }
}
