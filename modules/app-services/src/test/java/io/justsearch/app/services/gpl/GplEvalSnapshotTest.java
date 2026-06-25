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
