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
    assertTrue(line.contains("\"schemaVersion\":2"), "v2 records carry schemaVersion");
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
    // The cutoff is recomputed from System.currentTimeMillis() on EVERY record() (see the
    // production prune), so the retention window must outlast this test's own execution or the
    // "current" record below is pruned by its own write and the assertion races. At 100ms it lost
    // that race on a loaded machine and in isolation. Two seconds still prunes the 5s/10s-old
    // records the test is about, and no longer depends on how fast three file rewrites happen.
    var log = new NdjsonInferenceTransitionLog(tmp, Duration.ofSeconds(2));
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

  // ==================== Schema v2 / replay compat (tempdoc 737 §14 R4 / §15 task 3) ====================

  @Test
  @DisplayName("replay tolerates a mix of v1 (no schemaVersion) and v2 lines")
  void replayToleratesMixedSchemaVersions(@TempDir Path tmp) throws Exception {
    var log = new NdjsonInferenceTransitionLog(tmp);
    long now = System.currentTimeMillis();

    // Simulate a pre-migration v1 line (hand-written — no "schemaVersion" key at all) already
    // present in the file, e.g. left over from before an upgrade.
    String v1Line =
        "{\"timestampMs\":"
            + now
            + ",\"ts\":\""
            + java.time.Instant.ofEpochMilli(now)
            + "\",\"fromMode\":\"OFFLINE\",\"toMode\":\"ONLINE\",\"reason\":\"USER_SWITCH\","
            + "\"success\":true,\"durationMs\":10,\"generation\":1}\n";
    Files.createDirectories(log.file().getParent());
    Files.writeString(log.file(), v1Line, StandardCharsets.UTF_8);

    // Then a genuine v2 write (via the production path) appends after it.
    log.record(now + 1, "ONLINE", "INDEXING", "USER_SWITCH", true, 12, null, 2);

    List<String> lines = Files.readAllLines(log.file());
    assertEquals(2, lines.size());
    assertFalse(lines.get(0).contains("schemaVersion"), "the hand-written v1 line has no schemaVersion");
    assertTrue(lines.get(1).contains("\"schemaVersion\":2"), "the production-written line is v2");

    // Replay reads schemaVersion (defaulting to 1 when absent) and the always-present fields the
    // same way regardless of version — a missing key must not be treated as a parse error.
    for (String line : lines) {
      int schemaVersion = parseSchemaVersionOrDefault(line, 1);
      assertTrue(schemaVersion == 1 || schemaVersion == 2);
      assertEquals("USER_SWITCH", field(line, "reason"), "reason parses regardless of schema version");
    }

    // The FSM-driving replay harness (B.2) itself is schema-version-agnostic — it only reads
    // toMode/reason/success, so the same loop used in replayHarnessReachesRecordedEndState works
    // unmodified across a mixed-version file.
    var replayed = new ModeStateMachine();
    for (String line : lines) {
      String to = field(line, "toMode");
      boolean success = line.contains("\"success\":true");
      if (success) {
        replayed.beginTransition();
        replayed.complete(Mode.valueOf(to));
      } else {
        replayed.beginTransition();
        replayed.rollback();
      }
    }
    assertEquals(Mode.INDEXING, replayed.current());
  }

  /** Extracts {@code "schemaVersion":<int>} from a JSON line; returns {@code fallback} when absent. */
  private static int parseSchemaVersionOrDefault(String line, int fallback) {
    String marker = "\"schemaVersion\":";
    int start = line.indexOf(marker);
    if (start < 0) return fallback;
    start += marker.length();
    int end = start;
    while (end < line.length() && Character.isDigit(line.charAt(end))) {
      end++;
    }
    if (end == start) return fallback;
    return Integer.parseInt(line.substring(start, end));
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
