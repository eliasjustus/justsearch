/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.IndexingService;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.CircuitBreakerOpenException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watched-root lifecycle operations: add, remove, reindex, exclude matcher, and watcher
 * coordination.
 *
 * <p>Manages the IndexingService contract methods. Extracted from {@link RemoteKnowledgeClient}.
 */
final class RootLifecycleOps {
    private static final Logger log = LoggerFactory.getLogger(RootLifecycleOps.class);

    private final Map<Path, Instant> watchedRoots;
    private final WatchedRootsState watchedRootsState;
    private final Supplier<ExcludeMatcher> excludeMatcherSupplier;
    private final ScanRootFn scanRootFn;
    private final WorkerWatchFn workerWatchFn;
    private final Function<Path, DeleteByPathResponse> deleteByPathFn;
    private final Function<String, DeleteByIdResponse> deleteByIdFn;
    private final SyncOps syncOps;
    private final ExecutorService walkExecutor;

    // ===== Tempdoc 599 §16/A1 — non-blocking folder-availability cache =====
    // U4 (the design's verified constraint) forbids a per-poll Files.isDirectory on the Head REQUEST
    // thread: it blocks on dead/unmounted (esp. UNC) paths with no timeout pattern. So existence is
    // re-checked on the background walkExecutor and cached here; getWatchedRoots() (request thread)
    // only READS the cached boolean and surfaces a synthetic path-missing walkError — reusing the
    // existing walkError→"unavailable" signal U4 pointed to, with the freshness the bare signal lacks
    // (the walk does NOT re-fire when a folder is unmounted after its last good walk, which is why
    // reusing walkError alone left a stale "✓ indexed"). Continuous re-evaluation (every staleness
    // window) means a re-mounted folder clears the state on its own — guardrail (i): never cache
    // "missing" permanently.
    private static final long AVAIL_STALENESS_MS = 2_000L;
    private static final String ROOT_MISSING_REASON = "ROOT_NOT_DIRECTORY";
    private final Map<Path, Boolean> rootAvailable = new ConcurrentHashMap<>();
    private final Map<Path, Long> rootAvailCheckedAtMs = new ConcurrentHashMap<>();
    private final Set<Path> availRefreshInFlight = ConcurrentHashMap.newKeySet();

    RootLifecycleOps(
            Map<Path, Instant> watchedRoots,
            WatchedRootsState watchedRootsState,
            Supplier<ExcludeMatcher> excludeMatcherSupplier,
            ScanRootFn scanRootFn,
            WorkerWatchFn workerWatchFn,
            Function<Path, DeleteByPathResponse> deleteByPathFn,
            Function<String, DeleteByIdResponse> deleteByIdFn,
            SyncOps syncOps,
            ExecutorService walkExecutor) {
        this.watchedRoots = Objects.requireNonNull(watchedRoots, "watchedRoots");
        this.watchedRootsState = Objects.requireNonNull(watchedRootsState, "watchedRootsState");
        this.excludeMatcherSupplier =
                Objects.requireNonNull(excludeMatcherSupplier, "excludeMatcherSupplier");
        this.scanRootFn = Objects.requireNonNull(scanRootFn, "scanRootFn");
        this.workerWatchFn = Objects.requireNonNull(workerWatchFn, "workerWatchFn");
        this.deleteByPathFn = Objects.requireNonNull(deleteByPathFn, "deleteByPathFn");
        this.deleteByIdFn = Objects.requireNonNull(deleteByIdFn, "deleteByIdFn");
        this.syncOps = Objects.requireNonNull(syncOps, "syncOps");
        this.walkExecutor = Objects.requireNonNull(walkExecutor, "walkExecutor");
    }

    /**
     * Tempdoc 418 Phase B — registers/unregisters Worker-side watch subscriptions.
     * Implementations forward to {@code RemoteKnowledgeClient.watchRoot/unwatchRoot}. The
     * Worker-side watcher runs alongside the Head-side watcher during the Phase B soak window;
     * the SQLite jobs table uses {@code INSERT OR REPLACE} so duplicate enqueues from both
     * watchers coalesce automatically (no Head-side dedup required).
     */
    interface WorkerWatchFn {
        void watch(String rootPath, String collection);

        void unwatch(String rootPath);
    }

    /**
     * Tempdoc 418 Phase B — dispatch a Worker-side ScanRoot RPC. Implementations forward each
     * {@link ScanRootProgress} to {@code progressConsumer} and return the terminal event.
     * Production wiring uses {@code RemoteKnowledgeClient.scanRoot}.
     */
    @FunctionalInterface
    interface ScanRootFn {
        ScanRootProgress scan(
                String rootPath,
                List<String> excludeGlobs,
                java.util.function.Consumer<ScanRootProgress> progressConsumer);
    }

    // ========== Watched Root Accessors ==========

    List<Path> getWatchedPaths() {
        return List.copyOf(watchedRoots.keySet());
    }

    List<IndexingService.WatchedRoot> getWatchedRoots() {
        List<IndexingService.WatchedRoot> result = new ArrayList<>();
        for (var entry : watchedRoots.entrySet()) {
            Path root = entry.getKey();
            Instant ts = entry.getValue();
            Instant exposed = WatchedRootsStore.NEVER_INDEXED.equals(ts) ? null : ts;
            String persistedWalkError = watchedRootsState.getWalkError(root);
            // Tempdoc 599 §16/A1 — overlay a synthetic path-missing reason when the (background-
            // refreshed) availability cache says the folder is gone, so the wire's
            // walkError→"unavailable" path fires WITHOUT a Files stat on this (request) thread (U4).
            // A live missing-folder overrides a stale persisted walk error; when it returns, the real
            // persisted state shows through again (the availability overlay is non-destructive).
            String walkError = isRootAvailable(root) ? persistedWalkError : ROOT_MISSING_REASON;
            boolean walkCompleted = watchedRootsState.isWalkCompleted(root);
            // Tempdoc 626 §Axis-C — the latest reconcile's delete-detection verification outcome.
            boolean deleteDetectionUnverified = watchedRootsState.isDeleteDetectionUnverified(root);
            // Tempdoc 626 §Axis-C (drift-corrected) — the latest reconcile's orphan-prune outcome.
            WatchedRootsState.OrphanPrune drift = watchedRootsState.getDriftCorrected(root);
            int driftOrphanCount = drift == null ? 0 : drift.count();
            long driftOrphanAtMs = drift == null ? 0L : drift.atMs();
            // Tempdoc 626 §Recency — the last reconcile that confirmed index↔disk correspondence.
            Instant lastVerifiedAt = watchedRootsState.getLastVerifiedAt(root);
            result.add(
                new IndexingService.WatchedRoot(
                    null, root, exposed, walkError, walkCompleted, deleteDetectionUnverified,
                    driftOrphanCount, driftOrphanAtMs, lastVerifiedAt));
        }
        return result;
    }

    /**
     * Cheap, non-blocking availability read for the request thread (tempdoc 599 §16/A1, honoring U4).
     * Returns the last-known availability (default {@code true} until the first check completes) and,
     * if the cached value is stale, schedules a background re-check on {@code walkExecutor} — it NEVER
     * runs a filesystem stat on the caller's (request) thread, so it cannot block request handling on
     * a dead/unmounted UNC path. Continuous re-evaluation means a re-mounted folder clears the
     * "unavailable" state on its own (never caches "missing" permanently).
     */
    private boolean isRootAvailable(Path root) {
        long now = System.currentTimeMillis();
        Long checkedAt = rootAvailCheckedAtMs.get(root);
        boolean stale = checkedAt == null || (now - checkedAt) > AVAIL_STALENESS_MS;
        if (stale && availRefreshInFlight.add(root)) {
            try {
                walkExecutor.execute(
                    () -> {
                        try {
                            rootAvailable.put(root, Files.isDirectory(root));
                            rootAvailCheckedAtMs.put(root, System.currentTimeMillis());
                        } finally {
                            availRefreshInFlight.remove(root);
                        }
                    });
            } catch (RuntimeException e) {
                // Executor shutting down (or rejected) — drop this refresh, keep the last-known value.
                availRefreshInFlight.remove(root);
            }
        }
        Boolean known = rootAvailable.get(root);
        return known == null || known; // default available until the first background check lands
    }

    // ========== Root Add/Remove ==========

    void addWatchedPath(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        // Register root so walkAndSubmit()'s cancellation check doesn't abort the walk.
        watchedRootsState.markNeverIndexed(normalized);
        walkExecutor.execute(() -> walkAndSubmit(normalized));
    }

    /**
     * Tempdoc 418 Phase B + B-H.3 — dispatch a Worker-side ScanRoot RPC. The Worker owns
     * walk, admission, batched enqueue, backpressure (queue-depth-aware throttling) and
     * cancellation (via {@code ServerCallStreamObserver.isCancelled()}); Head only persists state
     * based on the terminal {@link ScanRootProgress}. A {@code CLIENT_CANCELLED} terminal reason
     * is recorded as a markWalkFailed reason just like any other non-empty terminal reason.
     *
     */
    private void walkAndSubmit(Path normalized) {
        if (!watchedRoots.containsKey(normalized)) {
            log.debug("Walk cancelled before start: root {} removed", normalized);
            return;
        }
        ExcludeMatcher excludes = excludeMatcherSupplier.get();
        List<String> excludeGlobs = excludes.patterns();
        long[] admittedTotal = new long[] {0L};
        try {
            ScanRootProgress terminal =
                    scanRootFn.scan(
                            normalized.toString(),
                            excludeGlobs,
                            progress -> admittedTotal[0] = progress.getFilesAdmitted());

            if (!watchedRoots.containsKey(normalized)) {
                log.debug(
                        "Walk completed but root {} was removed mid-scan; skipping state update",
                        normalized);
                return;
            }
            String reason = terminal == null ? "EMPTY_STREAM" : terminal.getTerminalReasonCode();
            if (reason == null || reason.isEmpty()) {
                if (admittedTotal[0] > 0) {
                    watchedRootsState.markIndexed(normalized);
                } else {
                    // Walk terminated cleanly but admitted zero files (empty / all-excluded).
                    // Mark walk-completed so the wire can render "empty", not a perpetual
                    // "scanning" (tempdoc 599 Fix 1).
                    watchedRootsState.markWalkedEmpty(normalized);
                }
            } else {
                watchedRootsState.markWalkFailed(normalized, reason);
            }
            watchedRootsState.persist();
        } catch (RuntimeException e) {
            log.error("Walk aborted for root {} after submission failure", normalized, e);
            if (!watchedRoots.containsKey(normalized)) {
                return;
            }
            if (admittedTotal[0] == 0) {
                watchedRootsState.markWalkFailed(normalized, e.getMessage());
                watchedRootsState.persist();
            }
        }
    }

    /**
     * Adds a watched root with collection name and starts the file watcher.
     *
     * <p>Registers the root immediately (with a NEVER_INDEXED sentinel), starts the file watcher,
     * and queues a background walk on {@code walkExecutor}. The walk streams files in batches with
     * backpressure and updates the root's indexed timestamp on completion.
     *
     * @param collection the collection name (for watcher tagging)
     * @param path the root path to watch
     */
    void addWatchedRoot(String collection, Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        Path normalized = path.toAbsolutePath().normalize();
        String collectionName =
                (collection == null || collection.isBlank()) ? "default" : collection;

        // Tempdoc 608 — idempotency at the convergence point. A re-add of an already-watched root (whether
        // mid-walk or walk-complete) is a no-op: without this, the duplicate-submit the UI used to invite
        // (no in-flight acknowledgement) would re-run markNeverIndexed below, resetting a fully-indexed
        // root's timestamp + clearing its walk-completed flag - reverting a fully-indexed folder to
        // "Scanning" and re-queuing a redundant full walk. This guards EVERY caller (UI op + agent tool),
        // including ones the FE busy-overlay guard can't reach. Reindex is unaffected: it takes a different
        // path (reindexWatchedRoots/markIndexed) and never reaches this method.
        if (watchedRoots.containsKey(normalized)) {
            log.debug("addWatchedRoot: {} already watched — no-op (idempotent re-add)", normalized);
            return;
        }

        // 1. Register root immediately so getWatchedRoots() returns it and
        //    walkAndSubmit()'s cancellation check sees it in the map.
        watchedRootsState.markNeverIndexed(normalized);
        watchedRootsState.persist();

        // 2. Start file watcher (does not depend on walk completion)
        startWatcherIfAvailable(collectionName, normalized);

        // 3. Queue async walk â€” batching, backpressure, and state persistence
        //    are handled by walkAndSubmit() on the walk-bg thread.
        walkExecutor.execute(() -> walkAndSubmit(normalized));
    }

    int deleteDocsByPathPrefix(Path pathPrefix) {
        if (pathPrefix == null) {
            return 0;
        }
        Path normalized = pathPrefix.toAbsolutePath().normalize();
        try {
            DeleteByPathResponse response = deleteByPathFn.apply(normalized);
            if (!response.getError().isEmpty()) {
                log.warn(
                        "deleteByPath RPC returned error for {}: {}",
                        normalized,
                        response.getError());
                return 0;
            }
            return (int) response.getDeletedJobs();
        } catch (CircuitBreakerOpenException e) {
            log.debug("deleteByPath rejected by circuit breaker for {}", normalized);
            return 0;
        } catch (Exception e) {
            log.warn("deleteByPath RPC failed for {}", normalized, e);
            return 0;
        }
    }

    boolean deleteDocById(String docId) {
        if (docId == null || docId.isBlank()) {
            return false;
        }
        try {
            DeleteByIdResponse response = deleteByIdFn.apply(docId);
            return response != null && response.getSuccess();
        } catch (CircuitBreakerOpenException e) {
            log.debug("deleteDocById rejected by circuit breaker for {}", docId);
            return false;
        } catch (Exception e) {
            log.warn("deleteById RPC failed for {}", docId, e);
            return false;
        }
    }

    int removeWatchedPath(Path path) {
        if (path == null) {
            return 0;
        }

        Path normalized = path.toAbsolutePath().normalize();
        log.info("Removing watched root: {} (stopping watcher, deleting from index)", normalized);

        // Tempdoc 626 §Axis-A — unregister the Worker-side watcher (the sole event source).
        try {
            workerWatchFn.unwatch(normalized.toString());
        } catch (RuntimeException e) {
            log.debug("Worker unwatch failed for {}: {}", normalized, e.getMessage());
        }

        // 2. Call gRPC to delete from worker
        int deletedJobs = 0;
        try {
            DeleteByPathResponse response = deleteByPathFn.apply(normalized);

            if (!response.getError().isEmpty()) {
                log.error("deleteByPath RPC returned error: {}", response.getError());
            } else {
                deletedJobs = (int) response.getDeletedJobs();
                log.info("deleteByPath RPC success: {} jobs deleted", deletedJobs);
            }
        } catch (CircuitBreakerOpenException e) {
            log.debug(
                    "removeWatchedPath deleteByPath rejected by circuit breaker for {}", normalized);
            // Continue to update local state even if circuit breaker rejected
        } catch (Exception e) {
            log.error("deleteByPath RPC failed for: {}", normalized, e);
            // Continue to update local state even if RPC fails
        }

        // 3. Update local state (even if RPC failed - user wanted to remove)
        watchedRootsState.removeRootAndNested(normalized);
        watchedRootsState.persist();

        return deletedJobs;
    }

    void flush() {
        // No-op
    }

    /**
     * Clears all watched roots — stops watchers, clears state, persists empty. Used by profiling
     * reset. Does NOT issue per-root gRPC deletion (the Worker deleteAll handles that).
     */
    void clearAllRoots() {
        log.info("clearAllRoots: clearing root state");
        // Tempdoc 626 §Axis-A — no Head-side watcher to stop; the Worker watcher is unregistered
        // per-root via removeWatchedPath, and a profiling reset clears Worker state via deleteAll.
        watchedRootsState.clearAll();
        log.info("clearAllRoots: all roots cleared and persisted");
    }

    // ========== Reindexing ==========

    void reindexWatchedRoots(boolean force) {
        if (watchedRoots.isEmpty()) {
            log.info("No watched roots to reindex");
            return;
        }

        // Take a snapshot of roots to avoid ConcurrentModificationException
        List<Path> roots = List.copyOf(watchedRoots.keySet());
        log.info("Reindexing {} watched roots (force={})", roots.size(), force);

        for (Path root : roots) {
            if (!Files.exists(root)) {
                log.warn("Watched root no longer exists, skipping: {}", root);
                continue;
            }

            ExcludeMatcher excludes = excludeMatcherSupplier.get();
            boolean hasExcludes = !excludes.isEmpty();

            // force=false without excludes: prefer Worker-side syncDirectory which streams
            // the disk walk and enqueues in batches internally.
            if (!force && !hasExcludes) {
                SyncDirectoryResponse r = syncOps.syncDirectory(root.toString(), true);
                if (r != null && r.getError().isEmpty()) {
                    watchedRootsState.markIndexed(root);
                }
                continue;
            }

            // force=true OR has excludes: prune orphans then walk asynchronously with
            // backpressure. walkAndSubmit() handles batching, state marking, and persistence.
            walkExecutor.execute(() -> {
                syncOps.pruneMissing(root.toString());
                walkAndSubmit(root);
            });
        }
        watchedRootsState.persist();
    }

    /**
     * Re-indexes all persisted roots and starts file watchers. Call AFTER gRPC connection is
     * established AND watcher bootstrap is set.
     *
     * <p>Starts file watchers immediately for each root, then queues background walks on {@code
     * walkExecutor}. Walks stream files in batches with backpressure and update root state on
     * completion. Returns immediately â€” indexing continues asynchronously.
     */
    void reindexPersistedRoots() {
        if (watchedRoots.isEmpty()) {
            return;
        }
        log.info("Re-indexing {} persisted roots...", watchedRoots.size());

        List<Path> rootsToReindex = List.copyOf(watchedRoots.keySet());
        for (Path root : rootsToReindex) {
            Path normalized = root.toAbsolutePath().normalize();
            startWatcherIfAvailable("default", normalized);
            walkExecutor.execute(() -> walkAndSubmit(normalized));
        }
        // Runs after all walks complete (single-thread executor serializes tasks).
        int count = rootsToReindex.size();
        walkExecutor.execute(() -> log.info("Re-indexing of {} persisted roots complete", count));
    }

    // ========== Helpers ==========

    private void startWatcherIfAvailable(String collection, Path normalized) {
        // Tempdoc 626 §Axis-A (the 418 Phase-C cutover) — the Worker-side watcher is now the SOLE
        // file-event source; the redundant Head-side watcher stack was deleted. The reconciler
        // (periodic syncDirectory + reindexPersistedRoots) remains the source-of-truth backstop.
        try {
            workerWatchFn.watch(normalized.toString(), collection);
        } catch (RuntimeException e) {
            log.debug(
                    "Worker watch registration failed for {}: {}", normalized, e.getMessage());
        }
    }
}
