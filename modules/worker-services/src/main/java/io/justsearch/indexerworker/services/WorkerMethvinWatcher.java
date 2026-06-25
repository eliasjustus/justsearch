/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.hashing.FileHasher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 418 Phase B — Worker-side Methvin file watcher.
 *
 * <p>Replaces the Head-side {@code MethvinWatcherStrategy} (modules/app-indexing) with a watcher
 * that lives in the same process as {@link JobQueue} — events feed straight into the queue with
 * no IPC hop and no per-event gRPC submitBatch. The watcher is registered per root via
 * {@link #registerRoot(Path, String)}; deregistration via {@link #unregisterRoot(Path)} closes
 * the underlying Methvin {@code DirectoryWatcher} for that root.
 *
 * <p>Event handling:
 * <ul>
 *   <li>{@code CREATE} / {@code MODIFY} → {@link JobQueue#enqueue(List, String)} with the
 *       collection from the watch subscription. {@code WorkerIngestionAuthority} applies its
 *       admission rules when the loop later picks the path up.
 *   <li>{@code DELETE} → forwarded to a {@code Consumer<String> deletePathSink} (B-H.4).
 *       Production wiring routes the sink to {@code IndexingCoordinator.deleteByIdAndChunks}
 *       so the parent doc + its chunks are removed in one Worker-side write. The sink is
 *       best-effort: failures are logged and dropped (the file may already be gone, the
 *       coordinator may be draining, etc.).
 *   <li>{@code OVERFLOW} → logged at warn; the periodic sync flow eventually catches up.
 * </ul>
 *
 * <p>Telemetry: per-event-kind counters under {@code index.watcher.events_total} with a
 * {@code component=worker_watcher} tag, paralleling the Head-side metric so dashboards can
 * compare event volumes during the cutover soak window.
 *
 * <p>Threading: each root gets its own {@code DirectoryWatcher} which manages its own thread
 * via {@code watchAsync()}. {@link #close()} cancels all futures and closes all watchers.
 *
 * <p>Linux note: inotify limit exhaustion is treated as a soft failure — the registration
 * returns without throwing, the periodic sync eventually catches changes for that root.
 */
public final class WorkerMethvinWatcher implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(WorkerMethvinWatcher.class);

  /** Events/second crossing this per-root rate schedules a reconcile (heuristic missed-event net). */
  static final int BURST_THRESHOLD = 100;

  /** Delay before a burst-triggered reconcile, so a spike coalesces into one walk. */
  static final int BURST_RECONCILE_DELAY_SECONDS = 5;

  private final JobQueue jobQueue;
  private final Consumer<String> deletePathSink;
  private final WorkerWatcherMetricCatalog watcherCatalog;
  // Tempdoc 626 §Axis-A — overflow/burst recovery relocated onto the Worker watcher so the Head
  // watcher could be retired. reconcileSink runs the in-process reconciler (force flag); it is
  // dispatched off the watcher's event thread via reconcileExecutor so a long walk never blocks
  // event delivery (mirrors the Head's dedicated sync-scheduler thread).
  private final BiConsumer<Path, Boolean> reconcileSink;
  private final WorkerBurstDetector burstDetector = new WorkerBurstDetector();
  private final ScheduledExecutorService reconcileExecutor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "worker-watcher-reconcile");
            t.setDaemon(true);
            return t;
          });
  private final Map<Path, RootSubscription> watchers = new ConcurrentHashMap<>();
  private final Map<Path, CompletableFuture<Void>> watchFutures = new ConcurrentHashMap<>();

  public enum Kind {
    CREATE,
    MODIFY,
    DELETE,
    OVERFLOW
  }

  /**
   * Constructs the Worker-side watcher. Tempdoc 418 B-H.4 introduced the {@code deletePathSink};
   * tempdoc 418 Phase C sub-commit A (Slice D, 2026-04-25) deleted the legacy 2-arg
   * back-compat constructor so production callers cannot accidentally drop DELETE events.
   * Tests pass an explicit sink (no-op for create/modify-focused cases, recording sink for
   * delete-focused cases).
   *
   * <p>Tempdoc 417 → 410 merge: telemetry now flows through {@link WorkerWatcherMetricCatalog}
   * (typed) instead of the legacy {@code Telemetry.Counter} per-kind map.
   */
  public WorkerMethvinWatcher(
      JobQueue jobQueue,
      WorkerWatcherMetricCatalog watcherCatalog,
      Consumer<String> deletePathSink,
      BiConsumer<Path, Boolean> reconcileSink) {
    this.jobQueue = Objects.requireNonNull(jobQueue, "jobQueue");
    this.deletePathSink = Objects.requireNonNull(deletePathSink, "deletePathSink");
    this.watcherCatalog = watcherCatalog == null ? WorkerWatcherMetricCatalog.noop() : watcherCatalog;
    this.reconcileSink = reconcileSink == null ? (root, force) -> {} : reconcileSink;
  }

  /**
   * Test-only convenience: no reconcile sink (overflow/burst recovery becomes a no-op). Production
   * wiring uses the 4-arg constructor so OVERFLOW/burst recovery is never silently dropped.
   */
  WorkerMethvinWatcher(
      JobQueue jobQueue, WorkerWatcherMetricCatalog watcherCatalog, Consumer<String> deletePathSink) {
    this(jobQueue, watcherCatalog, deletePathSink, null);
  }

  /**
   * Registers a watch subscription for {@code root}. Idempotent: if the root is already being
   * watched, the prior subscription is closed and replaced (so the collection tag can be
   * updated). Returns true if the watcher started successfully, false on inotify exhaustion or
   * other soft failure.
   */
  synchronized boolean registerRoot(Path root, String collection) {
    Objects.requireNonNull(root, "root");
    Path normalized = root.toAbsolutePath().normalize();
    String coll = collection == null || collection.isBlank() ? null : collection;
    closeWatcherFor(normalized);
    log.info("Worker watcher registering root: {} (collection={})", normalized, coll);
    try {
      DirectoryWatcher watcher =
          DirectoryWatcher.builder()
              .path(normalized)
              .fileHasher(FileHasher.LAST_MODIFIED_TIME)
              .listener(event -> handleEvent(normalized, coll, event))
              .build();
      watchers.put(normalized, new RootSubscription(normalized, coll, watcher));
      watchFutures.put(normalized, watcher.watchAsync());
      return true;
    } catch (IOException e) {
      if (isInotifyExhausted(e)) {
        log.warn(
            "Inotify limit reached for {}: file changes may not be detected; periodic sync will catch up",
            normalized);
        return false;
      }
      log.warn("Failed to register worker watcher for {}: {}", normalized, e.getMessage());
      return false;
    }
  }

  /** Unregisters a watcher by root path. Returns true if a subscription was found and closed. */
  synchronized boolean unregisterRoot(Path root) {
    Objects.requireNonNull(root, "root");
    Path normalized = root.toAbsolutePath().normalize();
    return closeWatcherFor(normalized);
  }

  private boolean closeWatcherFor(Path normalized) {
    RootSubscription prior = watchers.remove(normalized);
    CompletableFuture<Void> priorFuture = watchFutures.remove(normalized);
    burstDetector.removeRoot(normalized);
    if (priorFuture != null) {
      priorFuture.cancel(true);
    }
    if (prior != null) {
      try {
        prior.watcher().close();
      } catch (IOException e) {
        log.debug("Failed to close prior worker watcher for {}: {}", normalized, e.getMessage());
      }
      return true;
    }
    return false;
  }

  private void handleEvent(Path root, String collection, DirectoryChangeEvent event) {
    Kind kind = mapEventKind(event.eventType());
    if (kind == null) return;
    watcherCatalog.eventsTotal.increment(WorkerWatcherEventTags.of(kind));
    Path path = event.path();
    log.trace("Worker watcher event: {} {}", kind, path);
    switch (kind) {
      case CREATE, MODIFY -> {
        try {
          jobQueue.enqueue(List.of(path), collection);
        } catch (RuntimeException e) {
          log.warn("Worker watcher enqueue failed for {}: {}", path, e.getMessage());
        }
        maybeScheduleBurstReconcile(root);
      }
      case DELETE -> {
        handleDelete(root, path);
        maybeScheduleBurstReconcile(root);
      }
      case OVERFLOW -> handleOverflow(root, path);
    }
  }

  /**
   * OVERFLOW is a deterministic OS signal that events were dropped — the index is known stale, so
   * reconcile immediately with force=true (tempdoc 626 §Axis-A; mirrors the retired Head-side
   * {@code WatcherEventOps.handleOverflow}). The reconcile runs off the event thread so a long walk
   * never blocks event delivery. Package-private for deterministic unit-testing.
   */
  void handleOverflow(Path root, Path path) {
    log.warn("Worker watcher buffer overflow for {} — triggering reconcile (force=true)", path);
    submitReconcile(root, /* force= */ true, /* delaySeconds= */ 0);
  }

  /**
   * Heuristic missed-event net: when a per-root event spike crosses {@link #BURST_THRESHOLD}/sec,
   * schedule a (non-forced) reconcile after a short coalescing delay. force=false so the reconciler
   * yields to active user search; this catches OS-dropped events that arrive without an OVERFLOW
   * (e.g. Windows DELETE misses during bulk operations).
   */
  private void maybeScheduleBurstReconcile(Path root) {
    if (root != null && burstDetector.recordEvent(root, BURST_THRESHOLD)) {
      log.info(
          "Worker watcher burst (>{} events/s) for {} — scheduling reconcile in {}s",
          BURST_THRESHOLD,
          root,
          BURST_RECONCILE_DELAY_SECONDS);
      submitReconcile(root, /* force= */ false, BURST_RECONCILE_DELAY_SECONDS);
    }
  }

  private void submitReconcile(Path root, boolean force, int delaySeconds) {
    if (root == null) return;
    try {
      var unused =
          reconcileExecutor.schedule(
              () -> {
                try {
                  reconcileSink.accept(root, force);
                } catch (RuntimeException e) {
                  log.warn(
                      "Worker watcher reconcile failed for {} (force={}): {}",
                      root,
                      force,
                      e.getMessage());
                }
              },
              delaySeconds,
              TimeUnit.SECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // Executor shutting down (close() in progress) — drop; periodic sync is the backstop.
      log.debug("Worker watcher reconcile rejected (shutting down) for {}", root);
    }
  }

  /**
   * Routes a DELETE event to the delete sink, guarded by the tempdoc-599 unmount-cascade check
   * (tempdoc 626 §I.3-A). When a watched root goes unavailable (unmount / UNC disconnect / drive
   * unplug) the OS fires a cascade of child-DELETE events; forwarding them would silently wipe the
   * folder's index. The Head-side {@code WatcherEventOps.handleDelete} already guards against this;
   * before tempdoc 626 the Worker-side path did NOT, reopening the 599 data-loss class through the
   * parallel watcher. A later sync/rewalk reconciles real deletions once the root is back.
   *
   * <p>Package-private so the guard is deterministically unit-testable without a live
   * {@code DirectoryWatcher} (real unmount events are OS-timing-dependent and flaky to reproduce).
   */
  void handleDelete(Path root, Path path) {
    if (root != null && !Files.exists(root)) {
      log.warn(
          "Worker watcher: watched root {} is unavailable (likely unmounted); skipping delete of {}"
              + " to avoid wiping the folder's index",
          root,
          path);
      return;
    }
    try {
      String normalizedPath = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
      deletePathSink.accept(normalizedPath);
    } catch (RuntimeException e) {
      // Best-effort drop: file may already be gone, coordinator may be draining, etc.
      log.debug("Worker watcher delete sink failed for {}: {}", path, e.getMessage());
    }
  }

  private static Kind mapEventKind(DirectoryChangeEvent.EventType eventType) {
    return switch (eventType) {
      case CREATE -> Kind.CREATE;
      case MODIFY -> Kind.MODIFY;
      case DELETE -> Kind.DELETE;
      case OVERFLOW -> Kind.OVERFLOW;
    };
  }

  private static boolean isInotifyExhausted(IOException e) {
    String msg = e.getMessage();
    if (msg == null) return false;
    String lower = msg.toLowerCase(Locale.ROOT);
    return lower.contains("no space left")
        || lower.contains("inotify")
        || lower.contains("user limit");
  }

  @Override
  public synchronized void close() {
    for (CompletableFuture<Void> future : watchFutures.values()) {
      future.cancel(true);
    }
    for (RootSubscription sub : watchers.values()) {
      try {
        sub.watcher().close();
      } catch (IOException e) {
        log.debug("Failed to close worker watcher for {}: {}", sub.root(), e.getMessage());
      }
    }
    watchers.clear();
    watchFutures.clear();
    reconcileExecutor.shutdownNow();
  }

  private record RootSubscription(Path root, String collection, DirectoryWatcher watcher) {}
}
