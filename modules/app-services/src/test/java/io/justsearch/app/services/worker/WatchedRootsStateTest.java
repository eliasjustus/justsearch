package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WatchedRootsState")
final class WatchedRootsStateTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("markIndexed uses the injected clock and persists timestamp")
  void markIndexedPersistsClockInstant() throws Exception {
    Path root = tempDir.resolve("indexed-root");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    Instant now = Instant.parse("2026-02-09T12:34:56Z");
    WatchedRootsState state =
        new WatchedRootsState(watchedRoots, store, Clock.fixed(now, ZoneOffset.UTC));

    Path normalized = root.toAbsolutePath().normalize();
    state.markIndexed(normalized);
    state.persist();

    assertEquals(now, watchedRoots.get(normalized));
    assertEquals(now, store.loadPersistedRoots().get(normalized));
  }

  @Test
  @DisplayName("§Recency: clearing the unverified flag stamps lastVerifiedAt via the injected clock")
  void verifyingStampsLastVerifiedAt() {
    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Instant now = Instant.parse("2026-06-21T08:00:00Z");
    WatchedRootsState state =
        new WatchedRootsState(new ConcurrentHashMap<>(), store, Clock.fixed(now, ZoneOffset.UTC));
    Path root = tempDir.resolve("verified-root").toAbsolutePath().normalize();

    // A cap-skipped scan marks UNVERIFIED and must NOT stamp a verification time.
    state.setDeleteDetectionUnverified(root, true);
    assertTrue(state.isDeleteDetectionUnverified(root));
    assertEquals(null, state.getLastVerifiedAt(root), "marking unverified is not a verification");

    // A clean reconcile clears the flag → that IS the verification: stamp the heartbeat.
    state.setDeleteDetectionUnverified(root, false);
    assertFalse(state.isDeleteDetectionUnverified(root));
    assertEquals(now, state.getLastVerifiedAt(root), "clearing unverified stamps lastVerifiedAt");

    // Cleanup removes the heartbeat with the root.
    state.removeRootAndNested(root);
    assertEquals(null, state.getLastVerifiedAt(root), "removed root drops its lastVerifiedAt");
  }

  @Test
  @DisplayName("markNeverIndexed persists sentinel-backed entry without timestamp")
  void markNeverIndexedPersistsSentinel() throws Exception {
    Path root = tempDir.resolve("never-indexed-root");
    Files.createDirectories(root);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, store);

    Path normalized = root.toAbsolutePath().normalize();
    state.markNeverIndexed(normalized);
    state.persist();

    assertEquals(WatchedRootsStore.NEVER_INDEXED, watchedRoots.get(normalized));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, store.loadPersistedRoots().get(normalized));
  }

  @Test
  @DisplayName("Fix 1: markWalkedEmpty flags walkCompleted (distinct from registration) and persists")
  void markWalkedEmptyFlagsWalkCompleted() throws Exception {
    Path empty = tempDir.resolve("empty-root");
    Path scanning = tempDir.resolve("scanning-root");
    Files.createDirectories(empty);
    Files.createDirectories(scanning);
    Path emptyN = empty.toAbsolutePath().normalize();
    Path scanningN = scanning.toAbsolutePath().normalize();

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    WatchedRootsState state = new WatchedRootsState(new ConcurrentHashMap<>(), store);

    // Registration only (walk not finished) → NOT completed; walked-empty terminal → completed.
    state.markNeverIndexed(scanningN);
    state.markWalkedEmpty(emptyN);
    state.persist();

    assertFalse(state.isWalkCompleted(scanningN));
    assertTrue(state.isWalkCompleted(emptyN));
    // Both have no lastIndexed timestamp — the flag is the ONLY distinguishing signal.
    assertEquals(WatchedRootsStore.NEVER_INDEXED, store.loadPersistedRoots().get(emptyN));

    // Round-trips: a reload preserves walkCompleted for the empty root, not the scanning one.
    WatchedRootsState reloaded = new WatchedRootsState(new ConcurrentHashMap<>(), store);
    reloaded.loadPersistedRoots();
    assertTrue(reloaded.isWalkCompleted(emptyN));
    assertFalse(reloaded.isWalkCompleted(scanningN));
  }

  @Test
  @DisplayName("removeRootAndNested removes root and child entries but keeps siblings")
  void removeRootAndNestedRemovesOnlyTargetBranch() throws Exception {
    Path root = tempDir.resolve("root");
    Path child = root.resolve("child");
    Path sibling = tempDir.resolve("sibling");
    Files.createDirectories(child);
    Files.createDirectories(sibling);

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, store);

    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedChild = child.toAbsolutePath().normalize();
    Path normalizedSibling = sibling.toAbsolutePath().normalize();
    watchedRoots.put(normalizedRoot, Instant.now());
    watchedRoots.put(normalizedChild, Instant.now());
    watchedRoots.put(normalizedSibling, Instant.now());

    state.removeRootAndNested(normalizedRoot);

    assertFalse(watchedRoots.containsKey(normalizedRoot));
    assertFalse(watchedRoots.containsKey(normalizedChild));
    assertTrue(watchedRoots.containsKey(normalizedSibling));
  }

  @Test
  @DisplayName("loadPersistedRoots hydrates state map from store")
  void loadPersistedRootsHydratesState() throws Exception {
    Path root = tempDir.resolve("persisted-root");
    Files.createDirectories(root);
    Path normalized = root.toAbsolutePath().normalize();
    Instant ts = Instant.parse("2026-02-09T13:00:00Z");

    Path rootsFile = tempDir.resolve("watched_roots.json");
    WatchedRootsStore store = new WatchedRootsStore(rootsFile, null);
    store.persistRoots(Map.of(normalized, ts), Map.of(), java.util.Set.of());

    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, store);
    state.loadPersistedRoots();

    assertEquals(ts, watchedRoots.get(normalized));
  }
}
