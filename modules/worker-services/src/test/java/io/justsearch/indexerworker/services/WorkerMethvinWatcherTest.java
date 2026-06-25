package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 418 Phase B — verifies the Worker-side Methvin watcher delivers real filesystem
 * change events into the {@link JobQueue}. Uses a real {@code DirectoryWatcher} against a temp
 * directory; events are async, so the test polls for arrival rather than asserting immediately.
 */
final class WorkerMethvinWatcherTest {

  /**
   * Slice G.2 (M8) — recording delete sink shared across all test cases. Create/modify-focused
   * cases assert {@code sink.observed.isEmpty()} so a spurious DELETE from Methvin's debouncer
   * doesn't disappear silently the way it would with a true no-op sink.
   */
  private static final class RecordingDeleteSink implements Consumer<String> {
    final CopyOnWriteArrayList<String> observed = new CopyOnWriteArrayList<>();

    @Override
    public void accept(String path) {
      observed.add(path);
    }
  }

  @TempDir Path tempDir;

  @Test
  @Timeout(15)
  void deliversCreateEventToJobQueue() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, "docs"), "registerRoot must report success");

      // Methvin uses LAST_MODIFIED_TIME hashing — emit a brief delay so the watcher's initial
      // snapshot completes before the create lands, and the create produces a CREATE event.
      Thread.sleep(500);
      Path created = Files.writeString(root.resolve("hello.txt"), "world");

      Path observed = pollForEnqueued(queue, created, 10_000);
      assertEquals(created, observed, "Worker watcher must enqueue created file path");
      assertEquals("docs", queue.lastCollection, "Collection tag must propagate to enqueue");
      assertTrue(sink.observed.isEmpty(),
          "Create-only path must not produce DELETE sink calls; observed: " + sink.observed);
    }
  }

  @Test
  @Timeout(15)
  void unregisterRootStopsEventDelivery() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("transient"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(queue, null, sink)) {
      watcher.registerRoot(root, null);
      Thread.sleep(500);
      assertTrue(watcher.unregisterRoot(root), "unregisterRoot must report success");
      Files.writeString(root.resolve("after-unregister.txt"), "should-not-fire");
      Thread.sleep(2_000);
      assertFalse(
          queue.enqueuedPaths.stream().anyMatch(p -> p.getFileName().toString().equals("after-unregister.txt")),
          "Events after unregisterRoot must not reach the queue");
      assertTrue(sink.observed.isEmpty(),
          "Unregister-then-mutate must not fire any sink calls; observed: " + sink.observed);
    }
  }

  @Test
  @Timeout(15)
  void deliversDeleteEventToSink() throws Exception {
    // B-H.4 defect G — DELETE events must propagate to the deletePathSink so the parent doc +
    // chunks are removed in one Worker-side write. Pre-fix the case was a no-op.
    Path root = Files.createDirectory(tempDir.resolve("deletes"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, null), "registerRoot must report success");
      Thread.sleep(500);
      Path doomed = Files.writeString(root.resolve("doomed.txt"), "soon-to-die");
      // Wait for the CREATE to flow before deleting; otherwise the watcher's hash-based diff
      // can collapse create+delete into a single observed state.
      pollForEnqueued(queue, doomed, 10_000);
      Files.delete(doomed);

      String observed = pollForDeletedPath(sink.observed, doomed, 10_000);
      assertNotNull(observed, "Delete sink must receive the deleted path");
      assertTrue(
          observed.endsWith("doomed.txt"),
          "Sink path must point at the deleted file (got " + observed + ")");
    }
  }

  /** Records (root, force) reconcile invocations for the Phase-2 overflow/burst recovery tests. */
  private static final class RecordingReconcileSink
      implements java.util.function.BiConsumer<Path, Boolean> {
    final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void accept(Path root, Boolean force) {
      calls.add(root + "|force=" + force);
    }
  }

  @Test
  @Timeout(10)
  void overflowSchedulesForcedReconcile() throws Exception {
    // Tempdoc 626 §Axis-A — OVERFLOW (events dropped by the OS) must trigger an immediate forced
    // reconcile so the index re-converges. Before this the Worker watcher only logged the overflow.
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink delete = new RecordingDeleteSink();
    RecordingReconcileSink reconcile = new RecordingReconcileSink();
    Path root = tempDir;
    try (WorkerMethvinWatcher watcher =
        new WorkerMethvinWatcher(queue, null, delete, reconcile)) {
      watcher.handleOverflow(root, root.resolve("anything"));
      String expected = root + "|force=true";
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline && !reconcile.calls.contains(expected)) {
        Thread.sleep(50);
      }
      assertTrue(
          reconcile.calls.contains(expected),
          "OVERFLOW must schedule a forced reconcile; observed: " + reconcile.calls);
    }
  }

  @Test
  void deleteForChildIsForwardedWhenRootExists() {
    // Tempdoc 626 §I.3-A — the guard must NOT block normal deletions: when the watched root is
    // present, a child DELETE flows through to the sink as before.
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    Path root = tempDir; // exists
    Path child = root.resolve("doc.txt");
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(queue, null, sink)) {
      watcher.handleDelete(root, child);
      assertEquals(1, sink.observed.size(), "Delete under an existing root must reach the sink");
      assertTrue(sink.observed.get(0).endsWith("doc.txt"));
    }
  }

  @Test
  void deleteCascadeIsSkippedWhenWatchedRootIsGone() throws Exception {
    // Tempdoc 626 §I.3-A (the data-loss regression) — when a watched root goes unavailable
    // (unmount / UNC disconnect / drive unplug), the OS fires a cascade of child-DELETE events.
    // Forwarding them would silently wipe the folder's index. The Worker DELETE path must skip the
    // delete while the root is missing (mirroring the Head-side WatcherEventOps.handleDelete guard),
    // leaving reconciliation to a later sync once the root returns.
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    Path missingRoot = Files.createDirectory(tempDir.resolve("removable"));
    Path child = missingRoot.resolve("photo.jpg");
    Files.delete(missingRoot); // simulate the root vanishing (unmount/unplug)
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(queue, null, sink)) {
      watcher.handleDelete(missingRoot, child);
      assertTrue(
          sink.observed.isEmpty(),
          "Delete must be SKIPPED while the watched root is gone; observed: " + sink.observed);
    }
  }

  private static String pollForDeletedPath(
      CopyOnWriteArrayList<String> sink, Path expected, long timeoutMs) throws InterruptedException {
    String tail = expected.getFileName().toString();
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      for (String s : sink) {
        if (s != null && s.endsWith(tail)) return s;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Expected delete sink to receive a path ending with "
            + tail
            + " within "
            + timeoutMs
            + "ms; observed: "
            + new ArrayList<>(sink));
  }

  @Test
  void registerThenReregisterReplacesPriorSubscription() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("idempotent"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, "v1"));
      assertTrue(watcher.registerRoot(root, "v2"), "Re-registration must succeed (idempotent)");
      assertTrue(watcher.unregisterRoot(root));
      assertFalse(watcher.unregisterRoot(root), "Second unregister returns false");
      assertTrue(sink.observed.isEmpty(),
          "Idempotent register-cycle must not fire spurious deletes; observed: " + sink.observed);
    }
  }

  private static Path pollForEnqueued(RecordingQueue queue, Path expected, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      for (Path p : queue.enqueuedPaths) {
        if (p.equals(expected)) return p;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Expected enqueue of "
            + expected
            + " within "
            + timeoutMs
            + "ms; observed: "
            + new ArrayList<>(queue.enqueuedPaths));
  }

  private static final class RecordingQueue implements JobQueue {
    final CopyOnWriteArrayList<Path> enqueuedPaths = new CopyOnWriteArrayList<>();
    volatile String lastCollection;

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
      lastCollection = collection;
      return paths.size();
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void recordIngestionEvent(
        Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
