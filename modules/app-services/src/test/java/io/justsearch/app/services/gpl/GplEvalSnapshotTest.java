package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link GplEvalSnapshot}. */
class GplEvalSnapshotTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("save + load round-trips all fields")
  void roundTrip() {
    String timestamp = "2026-02-26T12:00:00Z";
    Map<String, Long> mimes = Map.of("text/plain", 500L, "application/pdf", 100L);
    GplEvalSnapshot snapshot = new GplEvalSnapshot(600, mimes, 1200, timestamp);

    Path file = tempDir.resolve("gpl-eval-snapshot.json");
    snapshot.save(file);
    assertTrue(Files.exists(file));

    GplEvalSnapshot loaded = GplEvalSnapshot.load(file);
    assertNotNull(loaded);
    assertEquals(600, loaded.docCount());
    assertEquals(1200, loaded.tripleCount());
    assertEquals(Instant.parse(timestamp), loaded.evaluatedAt());
    assertEquals(timestamp, loaded.evaluatedAtRaw());
    assertEquals(500L, loaded.mimeDistribution().get("text/plain"));
    assertEquals(100L, loaded.mimeDistribution().get("application/pdf"));
    assertEquals(2, loaded.mimeDistribution().size());
  }

  @Test
  @DisplayName("load returns null for missing file")
  void loadMissing() {
    Path file = tempDir.resolve("nonexistent.json");
    assertNull(GplEvalSnapshot.load(file));
  }

  @Test
  @DisplayName("load returns null for corrupt file")
  void loadCorrupt() throws Exception {
    Path file = tempDir.resolve("corrupt.json");
    Files.writeString(file, "not valid json {{{");
    assertNull(GplEvalSnapshot.load(file));
  }

  @Test
  @DisplayName("load returns null for empty file")
  void loadEmpty() throws Exception {
    Path file = tempDir.resolve("empty.json");
    Files.writeString(file, "");
    assertNull(GplEvalSnapshot.load(file));
  }

  /**
   * Tempdoc 909 item 3 — the stated corruption policy, end to end: an unparseable snapshot is
   * treated as ABSENT, and "absent" is what makes the evaluation re-run. Asserting only
   * {@code load(...) == null} would stop one step short of the behaviour the policy promises, so
   * this drives the real consumer ({@link GplRevalidationTrigger}) with the loaded value.
   */
  @Test
  @DisplayName("a torn snapshot is treated as absent, so the evaluation re-runs")
  void tornSnapshotMakesTheEvaluationReRun() throws Exception {
    Path file = tempDir.resolve("gpl-eval-snapshot.json");
    new GplEvalSnapshot(1000, Map.of("text/plain", 1000L), 500, Instant.now().toString())
        .save(file);
    byte[] whole = Files.readAllBytes(file);
    Files.write(file, java.util.Arrays.copyOf(whole, whole.length / 2));

    GplEvalSnapshot loaded = GplEvalSnapshot.load(file);
    assertNull(loaded, "a torn snapshot must not load as a partially-trusted one");

    var trigger = new GplRevalidationTrigger(2.0);
    var result = trigger.evaluate(loaded, 1000, Map.of("text/plain", 1000L));
    assertTrue(
        result.shouldRun(),
        "an unreadable snapshot must re-run the evaluation, never silently skip it: "
            + result.reasons());
  }

  /**
   * Tempdoc 909 item 3 — {@code load} is published as a per-request {@code Supplier<GplEvalData>}
   * (HeadAssembly.headInfraRegistry), so an unbounded WARN would repeat for the life of the
   * process. The first failure is reported; the repeats are not.
   */
  @Test
  @DisplayName("an unreadable snapshot warns once per file, not once per read")
  void unreadableSnapshotWarnsOnce() throws Exception {
    Path file = tempDir.resolve("warn-once.json");
    Files.writeString(file, "not valid json {{{");

    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(GplEvalSnapshot.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      for (int i = 0; i < 5; i++) {
        assertNull(GplEvalSnapshot.load(file));
      }
      long warnings =
          appender.list.stream()
              .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
              .count();
      assertEquals(1, warnings, "five reads of the same unreadable snapshot must WARN once");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("save creates parent directories")
  void saveCreatesParentDirs() {
    Path file = tempDir.resolve("sub/dir/snapshot.json");
    GplEvalSnapshot snapshot = new GplEvalSnapshot(10, Map.of(), 0, Instant.now().toString());
    snapshot.save(file);
    assertTrue(Files.exists(file));
  }

  @Test
  @DisplayName("null mimeDistribution defaults to empty map")
  void nullMimeDistribution() {
    GplEvalSnapshot snapshot = new GplEvalSnapshot(10, null, 0, Instant.now().toString());
    assertNotNull(snapshot.mimeDistribution());
    assertTrue(snapshot.mimeDistribution().isEmpty());
  }

  @Test
  @DisplayName("mimeDistribution is immutable")
  void mimeDistributionIsImmutable() {
    Map<String, Long> mimes = Map.of("text/plain", 100L);
    GplEvalSnapshot snapshot = new GplEvalSnapshot(100, mimes, 50, Instant.now().toString());
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.mimeDistribution().put("application/pdf", 50L));
  }

  @Test
  @DisplayName("null evaluatedAt survives round-trip as null")
  void nullEvaluatedAt() throws Exception {
    // Simulates an older snapshot or one written without a timestamp field.
    // The DebugStateController NPE fix relies on evaluatedAtRaw() being safely null here.
    Path file = tempDir.resolve("no-ts.json");
    Files.writeString(file, "{\"docCount\":5,\"mimeDistribution\":{},\"tripleCount\":10}");
    GplEvalSnapshot loaded = GplEvalSnapshot.load(file);
    assertNotNull(loaded);
    assertEquals(5, loaded.docCount());
    assertNull(loaded.evaluatedAtRaw());
    assertNull(loaded.evaluatedAt());
  }

  @Test
  @DisplayName("save leaves no .tmp file after successful write")
  void saveNoTempFile() {
    Path file = tempDir.resolve("snapshot.json");
    GplEvalSnapshot snapshot = new GplEvalSnapshot(1, Map.of(), 0, Instant.now().toString());
    snapshot.save(file);
    assertTrue(Files.exists(file));
    assertFalse(
        Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
        "atomic write should not leave a .tmp file behind");
  }
}
