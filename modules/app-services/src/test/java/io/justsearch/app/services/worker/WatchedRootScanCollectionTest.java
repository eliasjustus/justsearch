/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C2 — a watched root's OWN initial scan carries the root's collection.
 *
 * <p>This file used to be the baseline pin for the opposite behaviour: the label reached the
 * watcher arm ({@code WorkerWatchFn.watch(rootPath, collection)}) but the scan arm had no channel
 * for it, so {@link RootLifecycleOps.ScanRootFn#scan} took only (rootPath, excludeGlobs,
 * progressConsumer) and the documents the initial scan admitted carried no {@code collection} at
 * all. The scan arm now takes the label too, and the assertions below are the positive inversion:
 * both arms must receive the SAME collection for the same root.
 */
@DisplayName("watched-root scan — collection carriage")
final class WatchedRootScanCollectionTest {

  @TempDir Path tempDir;

  /** Captures what each arm received for one addWatchedRoot call. */
  private record Arms(String watched, String scanned, String scannedRoot) {}

  private Arms addRootAndCapture(String label, Path root) throws Exception {
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state =
        new WatchedRootsState(
            watchedRoots, new WatchedRootsStore(tempDir.resolve(root.getFileName() + ".json"), null));

    AtomicReference<String> watchedCollection = new AtomicReference<>();
    AtomicReference<String> scannedCollection = new AtomicReference<>();
    AtomicReference<String> scannedRoot = new AtomicReference<>();
    java.util.concurrent.ExecutorService walkExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    RootLifecycleOps ops =
        new RootLifecycleOps(
            watchedRoots,
            state,
            () -> ExcludeMatcher.empty(true),
            (rootPath, collection, globs, progress) -> {
              scannedRoot.set(rootPath);
              scannedCollection.set(collection);
              return null;
            },
            new RootLifecycleOps.WorkerWatchFn() {
              @Override
              public void watch(String rootPath, String collection) {
                watchedCollection.set(collection);
              }

              @Override
              public void unwatch(String rootPath) {}
            },
            p -> null,
            s -> null,
            mock(SyncOps.class),
            walkExecutor);

    ops.addWatchedRoot(label, root);
    walkExecutor.shutdown();
    assertTrue(
        walkExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
        "the queued walk must finish before the assertions read what it dispatched");
    return new Arms(watchedCollection.get(), scannedCollection.get(), scannedRoot.get());
  }

  @Test
  @DisplayName("both the watcher arm AND the scan arm receive the root's collection")
  void labeledRootScanCarriesItsCollection() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("labeled"));

    Arms arms = addRootAndCapture("my-notes", root);

    assertTrue(arms.scannedRoot() != null, "the walk did dispatch a scan for the labeled root");
    assertEquals(
        "my-notes", arms.watched(), "the watcher arm carries the label (unchanged behaviour)");
    assertEquals(
        "my-notes",
        arms.scanned(),
        "the scan arm must carry the SAME label — the initial scan's documents belong to the"
            + " root's collection, not the default bucket");
  }

  @Test
  @DisplayName("a blank label normalizes to the default bucket on BOTH arms, not to null")
  void blankLabelNormalizesOnBothArms() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("unlabeled"));

    Arms arms = addRootAndCapture("  ", root);

    // Precision: proves the scan arm forwards addWatchedRoot's NORMALIZED label rather than
    // happening to agree by both being null/blank.
    assertEquals("default", arms.watched(), "watcher arm sees the normalized label");
    assertEquals("default", arms.scanned(), "scan arm sees the same normalized label");
  }
}
