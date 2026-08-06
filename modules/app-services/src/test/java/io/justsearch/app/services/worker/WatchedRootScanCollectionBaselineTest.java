/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
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
 * BASELINE PIN — the known root-scan collection drop (observation on file). NOT a fix, and NOT a
 * statement that this behaviour is correct.
 *
 * <p>A watched root is registered WITH a collection, and the label reaches the watcher arm
 * ({@code WorkerWatchFn.watch(rootPath, collection)}) — but the scan arm has no channel for it:
 * {@link RootLifecycleOps.ScanRootFn#scan} takes only (rootPath, excludeGlobs, progressConsumer),
 * and the production wiring in {@code RemoteKnowledgeClient} correspondingly passes a literal
 * {@code null} collection to {@code scanRoot}. So the documents the root's OWN scan admits carry no
 * {@code collection} field at all, and the root's label describes only what the watcher later picks
 * up.
 *
 * <p>This test pins that asymmetry so the eventual fix is a deliberate, visible change: threading
 * the collection through the scan arm must change this signature, which fails these assertions.
 */
@DisplayName("watched-root scan — collection carriage (known-gap baseline)")
final class WatchedRootScanCollectionBaselineTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("the watcher arm receives the root's collection; the scan arm has no parameter for it")
  void labeledRootScanCarriesNoCollection() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("labeled"));
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state =
        new WatchedRootsState(
            watchedRoots, new WatchedRootsStore(tempDir.resolve("roots.json"), null));

    AtomicReference<String> watchedCollection = new AtomicReference<>();
    AtomicReference<String> scannedRoot = new AtomicReference<>();
    java.util.concurrent.ExecutorService walkExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    RootLifecycleOps ops =
        new RootLifecycleOps(
            watchedRoots,
            state,
            () -> ExcludeMatcher.empty(true),
            (rootPath, globs, progress) -> {
              scannedRoot.set(rootPath);
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

    ops.addWatchedRoot("my-notes", root);
    walkExecutor.shutdown();
    assertTrue(
        walkExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
        "the queued walk must finish before the assertions read what it dispatched");

    assertEquals(
        "my-notes",
        watchedCollection.get(),
        "the label IS known at this point — the drop is in what the scan arm can carry");
    assertTrue(scannedRoot.get() != null, "the walk did dispatch a scan for the labeled root");

    Method scan = RootLifecycleOps.ScanRootFn.class.getMethod(
        "scan", String.class, java.util.List.class, java.util.function.Consumer.class);
    assertArrayEquals(
        new Class<?>[] {String.class, java.util.List.class, java.util.function.Consumer.class},
        scan.getParameterTypes(),
        "BASELINE: ScanRootFn carries no collection. When the drop is fixed, this assertion is the"
            + " one that must be updated — deliberately.");
  }
}
