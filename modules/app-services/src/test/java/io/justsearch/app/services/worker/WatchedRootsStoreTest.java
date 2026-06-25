package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WatchedRootsStore")
final class WatchedRootsStoreTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Loads new-format roots with lastIndexed when paths exist")
  void loadsNewFormat() throws Exception {
    Path root = tempDir.resolve("root1");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    Map<String, Object> data =
        Map.of(
            "roots",
            List.of(
                Map.of(
                    "path",
                    root.toAbsolutePath().toString(),
                    "lastIndexed",
                    "2026-01-01T00:00:00Z")));
    new ObjectMapper().writeValue(rootsFile.toFile(), data);

    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> loaded = store.loadPersistedRoots();
    assertEquals(1, loaded.size());
    assertTrue(loaded.containsKey(root.toAbsolutePath()));
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), loaded.get(root.toAbsolutePath()));
  }

  @Test
  @DisplayName("Loads old-format roots list when paths exist")
  void loadsOldFormat() throws Exception {
    Path root = tempDir.resolve("root2");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    new ObjectMapper().writeValue(rootsFile.toFile(), List.of(root.toAbsolutePath().toString()));

    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> loaded = store.loadPersistedRoots();
    assertEquals(1, loaded.size());
    assertTrue(loaded.containsKey(root.toAbsolutePath()));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, loaded.get(root.toAbsolutePath()), "Old format has no lastIndexed timestamps");
  }

  @Test
  @DisplayName("Persists roots in new format and round-trips")
  void persistsAndRoundTrips() throws Exception {
    Path root = tempDir.resolve("root3");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);

    Instant ts = Instant.parse("2026-01-01T00:00:00Z");
    store.persistRoots(Map.of(root.toAbsolutePath(), ts), Map.of(), java.util.Set.of());

    assertTrue(Files.exists(rootsFile));
    String written = Files.readString(rootsFile);
    assertTrue(written.contains("\"roots\""));

    Map<Path, Instant> loaded = store.loadPersistedRoots();
    assertEquals(ts, loaded.get(root.toAbsolutePath()));
  }
}
