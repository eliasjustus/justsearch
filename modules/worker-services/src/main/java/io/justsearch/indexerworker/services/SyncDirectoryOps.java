/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.PruneOps;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sync-directory orchestration helper for {@link GrpcIngestService}.
 *
 * <p>Contains the prune-walk-commit pipeline: root validation, orphan pruning,
 * indexed-path scanning, disk walk with enqueue, and terminal-state handling.
 * Extracted to reduce the size of the service class.
 */
final class SyncDirectoryOps {
  private static final Logger log = LoggerFactory.getLogger(SyncDirectoryOps.class);

  private static final int SYNC_PRUNE_THROTTLE_BATCH_SIZE = 100;
  private static final int SYNC_WALK_THROTTLE_EVERY_N_FILES = 100;
  private static final int SYNC_ENQUEUE_BATCH_SIZE = 2_000;

  /**
   * Guardrail: max indexed paths for force=false "missing file" detection. Above this the
   * delete-detection scan is too expensive to run inline, so it is skipped — but tempdoc 626 §Axis-B
   * makes that skip NON-silent: the response now carries {@code delete_detection_unverified} so the
   * Head surfaces a per-root "couldn't verify" state rather than a false "✓ indexed" (the
   * unacceptable state being skip + look-healthy). Raising/streaming this cap is a future option; the
   * load-bearing fix is that the skip is observable, not that the cap is gone.
   */
  private static final int MAX_INDEXED_PATHS_FOR_MISSING_SCAN = 200_000;

  private static final boolean HAS_DOS_ATTRIBUTES =
      java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("dos");

  /** FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS — set on OneDrive cloud-only placeholders. */
  private static final int RECALL_ON_DATA_ACCESS = 0x00400000;

  private final ReadPathOps readPathOps;
  private final PruneOps pruneOps;
  private final CommitOps commitOps;
  private final JobQueue jobQueue;
  private final WorkerSignalBus signalBus;
  private final CloudPlaceholderRecorder cloudPlaceholderRecorder;

  SyncDirectoryOps(
      ReadPathOps readPathOps,
      PruneOps pruneOps,
      CommitOps commitOps,
      JobQueue jobQueue,
      WorkerSignalBus signalBus) {
    this.readPathOps = readPathOps;
    this.pruneOps = pruneOps;
    this.commitOps = commitOps;
    this.jobQueue = jobQueue;
    this.signalBus = signalBus;
    this.cloudPlaceholderRecorder = jobQueue == null ? null : new CloudPlaceholderRecorder(jobQueue);
  }

  /**
   * Executes the full sync-directory pipeline: validate root, prune orphans, walk disk,
   * enqueue missing files, commit, and respond.
   */
  void execute(
      String rootPath, boolean force, StreamObserver<SyncDirectoryResponse> responseObserver) {
    Path root = resolveSyncRootOrReplyInvalid(rootPath, responseObserver);
    if (root == null) {
      return;
    }

    int filesAdded = 0;
    int filesDeleted = 0;

    try {
      // STEP 1: Prune orphans (reuse existing logic with throttle + abort)
      int pruneResult = pruneOrphansForSync(rootPath, force);

      if (handleSyncPruneAbortIfNeeded(pruneResult, force, responseObserver)) {
        return;
      }
      filesDeleted = Math.max(0, pruneResult);

      // STEP 2: For force=true (OVERFLOW/burst), we do not attempt to compute the full indexed
      // set (can be large). We enqueue all disk files and rely on IndexingLoop's "unchanged"
      // fast-path.
      Set<String> indexedPaths = indexedPathsForSync(rootPath, force);
      if (handleSyncIndexedPathsScanSkipIfNeeded(
          rootPath, force, indexedPaths, filesDeleted, responseObserver)) {
        return;
      }
      if (!force) {
        log.debug("syncDirectory: found {} indexed paths under {}", indexedPaths.size(), rootPath);
      }

      // STEP 3: Walk disk and find missing files
      SyncWalkPhaseResult walk = walkAndEnqueueMissingFiles(root, force, indexedPaths);
      filesAdded = walk.filesAdded();

      if (handleSyncWalkTerminalState(walk, filesDeleted, filesAdded, responseObserver)) {
        return;
      }

      // Commit after deletions
      if (filesDeleted > 0 && commitOps != null) {
        commitOps.commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.SYNC_PRUNE);
      }

      log.info(
          "syncDirectory complete for {}: {} deleted, {} added",
          rootPath,
          filesDeleted,
          filesAdded);
      responseObserver.onNext(syncDirectoryResultResponse(filesDeleted, filesAdded));
      responseObserver.onCompleted();

    } catch (Exception e) {
      log.error("syncDirectory failed for {}", rootPath, e);
      responseObserver.onNext(
          syncDirectoryErrorResponse(filesDeleted, filesAdded, e.getMessage()));
      responseObserver.onCompleted();
    }
  }

  // ==================== Phase helpers ====================

  private boolean handleSyncPruneAbortIfNeeded(
      int pruneResult, boolean force, StreamObserver<SyncDirectoryResponse> responseObserver) {
    if (pruneResult < 0 && !force) {
      log.info("syncDirectory aborted during prune phase (user activity)");
      responseObserver.onNext(syncDirectorySkippedResponse());
      responseObserver.onCompleted();
      return true;
    }
    return false;
  }

  private boolean handleSyncIndexedPathsScanSkipIfNeeded(
      String rootPath,
      boolean force,
      Set<String> indexedPaths,
      int filesDeleted,
      StreamObserver<SyncDirectoryResponse> responseObserver) {
    if (force || indexedPaths != null) {
      return false;
    }
    log.warn(
        "syncDirectory: skipping missing-file detection for {} "
            + "(too many indexed paths; still pruned {} orphans) — marking delete-detection UNVERIFIED",
        rootPath,
        filesDeleted);
    // Tempdoc 626 §Axis-B/C — surface the skip instead of returning a silent skipped/healthy result.
    responseObserver.onNext(syncDirectoryDeleteUnverifiedResponse(filesDeleted, 0));
    responseObserver.onCompleted();
    return true;
  }

  private boolean handleSyncWalkTerminalState(
      SyncWalkPhaseResult walk,
      int filesDeleted,
      int filesAdded,
      StreamObserver<SyncDirectoryResponse> responseObserver) {
    if (walk.walkInterrupted()) {
      Thread.currentThread().interrupt();
      responseObserver.onNext(syncDirectoryErrorResponse(filesDeleted, filesAdded, "Interrupted"));
      responseObserver.onCompleted();
      return true;
    }
    if (walk.walkAborted()) {
      responseObserver.onNext(syncDirectorySkippedResponse(filesDeleted, filesAdded));
      responseObserver.onCompleted();
      return true;
    }
    return false;
  }

  private Path resolveSyncRootOrReplyInvalid(
      String rootPath, StreamObserver<SyncDirectoryResponse> responseObserver) {
    Path root = Path.of(rootPath);
    if (!Files.exists(root) || !Files.isDirectory(root)) {
      log.warn("syncDirectory: root does not exist or is not a directory: {}", rootPath);
      responseObserver.onNext(
          syncDirectoryErrorResponse("Root path does not exist or is not a directory"));
      responseObserver.onCompleted();
      return null;
    }
    return root;
  }

  private int pruneOrphansForSync(String rootPath, boolean force) {
    if (pruneOps == null) {
      return 0;
    }
    // Tempdoc 599 §16/A1 (data-loss guard) — if the watched root itself is gone (unmounted /
    // disconnected drive), DO NOT prune: every file under it would read as "missing" and its whole
    // index would be silently deleted. Skip; a later sync re-prunes once the root is back. (Worker
    // background thread, so the existence check's latency on a dead mount is tolerable.)
    if (!Files.isDirectory(Path.of(rootPath))) {
      log.warn(
          "pruneOrphansForSync: root unavailable (likely unmounted), skipping prune to avoid"
              + " deleting the folder's index: {}",
          rootPath);
      return 0;
    }
    return pruneOps.pruneByPathPrefix(
        rootPath, force ? () -> false : signalBus::isUserActive, SYNC_PRUNE_THROTTLE_BATCH_SIZE);
  }

  private Set<String> indexedPathsForSync(String rootPath, boolean force) {
    if (force) {
      return null;
    }
    return getIndexedPathsUnderPrefix(rootPath);
  }

  // ==================== Walk logic ====================

  record SyncWalkPhaseResult(int filesAdded, boolean walkAborted, boolean walkInterrupted) {}

  @SuppressWarnings("PMD.CognitiveComplexity")
  private SyncWalkPhaseResult walkAndEnqueueMissingFiles(
      Path root, boolean force, Set<String> indexedPaths) throws IOException {
    // 391/E-J-N12: collect the full list first, sort by absolute path, then
    // enqueue in deterministic batches. Filesystem-order enumeration varies
    // across runs of the same unchanged corpus (NTFS MFT state, OS cache),
    // which causes non-deterministic chunk-boundary placement downstream
    // (ner_total observed to vary 5300-7300 across back-to-back scifact runs).
    // Sorting up-front costs O(n log n) and ~200 B per path — ~1 MB extra
    // for 5K files; negligible for benchmarking corpora.
    //
    // Scalability trade-off (tempdoc 393 § 3.4): collect-all-then-sort delays
    // the first enqueue by the walk-complete time, losing pipelining with the
    // indexing loop. At ~5K-10K files this is invisible (<1 s walk). At ~100K
    // files the walk can run 10-30 s and the loop sits idle during it. If a
    // user-facing workload ever pushes past ~50K files per sync, switch to a
    // streaming approach: hash-bucket the path space and sort per-bucket so
    // enqueue can start after the first bucket settles. The current approach
    // is intentional for determinism; this note exists so the next agent
    // doesn't rediscover the trade-off by accident.
    List<Path> collected = new ArrayList<>();
    int[] counters = {0}; // [0]=fileCount (for throttle)
    boolean[] walkAborted = {false};
    boolean[] walkInterrupted = {false};
    final Set<String> indexedPathsFinal = indexedPaths;

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) return FileVisitResult.CONTINUE;
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if (IngestionSkipPolicy.isSkippedDirectoryName(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
            if (!Files.isReadable(file)) return FileVisitResult.CONTINUE;
            if (isCloudPlaceholder(file)) {
              recordCloudPlaceholderObservation(file);
              return FileVisitResult.CONTINUE;
            }

            counters[0]++;

            // Throttle: check abort + yield periodically.
            if (counters[0] % SYNC_WALK_THROTTLE_EVERY_N_FILES == 0) {
              if (Thread.interrupted()) {
                log.info("syncDirectory interrupted after {} files", counters[0]);
                walkInterrupted[0] = true;
                return FileVisitResult.TERMINATE;
              }
              if (!force && signalBus.isUserActive()) {
                log.info("syncDirectory aborted during walk phase (user activity)");
                walkAborted[0] = true;
                return FileVisitResult.TERMINATE;
              }
              try {
                Thread.sleep(1);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                walkInterrupted[0] = true;
                return FileVisitResult.TERMINATE;
              }
            }

            String normalizedPath =
                PathNormalizer.normalizePath(file.toAbsolutePath().toString());
            if (force
                || (indexedPathsFinal != null && !indexedPathsFinal.contains(normalizedPath))) {
              collected.add(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            log.debug(
                "Skipping inaccessible path: {} ({})",
                file,
                exc != null ? exc.getMessage() : "unknown");
            return FileVisitResult.CONTINUE;
          }
        });

    // Sort once, after the walk, for a stable enqueue order across runs.
    // Uses the normalized absolute path string so the ordering is independent
    // of the Path implementation's natural ordering (which could change
    // between JDKs).
    collected.sort(
        Comparator.comparing(p -> PathNormalizer.normalizePath(p.toAbsolutePath().toString())));

    int filesAdded = 0;
    List<Path> batch = new ArrayList<>(SYNC_ENQUEUE_BATCH_SIZE);
    for (Path file : collected) {
      batch.add(file);
      if (batch.size() >= SYNC_ENQUEUE_BATCH_SIZE) {
        filesAdded += jobQueue.enqueue(batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      filesAdded += jobQueue.enqueue(batch);
    }
    if (!walkInterrupted[0] && !walkAborted[0] && filesAdded > 0) {
      log.info("syncDirectory: enqueued {} missing files for indexing", filesAdded);
    }
    return new SyncWalkPhaseResult(filesAdded, walkAborted[0], walkInterrupted[0]);
  }

  // ==================== Index path query ====================

  /**
   * Returns all indexed document paths under the given prefix.
   *
   * <p>Used by syncDirectory to determine which files are missing from the index. Uses
   * field-based filtering (is_chunk != true) to exclude chunk documents rather than string
   * matching on doc_id, which can misclassify legitimate paths.
   */
  private Set<String> getIndexedPathsUnderPrefix(String prefix) {
    Set<String> paths = new HashSet<>();
    if (readPathOps == null) {
      return paths;
    }

    try {
      String normalizedPrefix = PathNormalizer.normalizePath(prefix);

      var prefixQuery =
          new org.apache.lucene.search.PrefixQuery(
              new org.apache.lucene.index.Term(SchemaFields.PATH, normalizedPrefix));
      var query =
          new org.apache.lucene.search.BooleanQuery.Builder()
              .add(prefixQuery, org.apache.lucene.search.BooleanClause.Occur.MUST)
              .add(
                  new org.apache.lucene.search.TermQuery(
                      new org.apache.lucene.index.Term(SchemaFields.IS_CHUNK, "true")),
                  org.apache.lucene.search.BooleanClause.Occur.MUST_NOT)
              .build();

      String cursor = null;
      final int batchSize = 10_000;
      while (true) {
        var result =
            readPathOps.search(
                query,
                batchSize,
                Set.of(SchemaFields.DOC_ID),
                LuceneRuntimeTypes.RuntimeSearchSort.PATH_ASC,
                cursor);

        for (var hit : result.hits()) {
          String path = hit.docId();
          if (path != null) {
            paths.add(path);
            if (paths.size() >= MAX_INDEXED_PATHS_FOR_MISSING_SCAN) {
              return null;
            }
          }
        }

        cursor = result.nextCursor();
        if (cursor == null || cursor.isBlank()) {
          break;
        }
      }
    } catch (Exception e) {
      log.warn("Failed to get indexed paths for prefix {}", prefix, e);
    }

    return paths;
  }

  // ==================== Platform helper ====================

  /**
   * Detects OneDrive Files-on-Demand cloud-only placeholder files. Reading these triggers a
   * network download; skip them during indexing.
   */
  static boolean isCloudPlaceholder(Path file) {
    if (!HAS_DOS_ATTRIBUTES) return false;
    try {
      int attrs = (int) Files.getAttribute(file, "dos:attributes");
      return (attrs & RECALL_ON_DATA_ACCESS) != 0;
    } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
      // A file whose Windows cloud-placeholder attribute cannot be read is not a placeholder.
      // On some non-Windows JDKs the default FS advertises a "dos" view (so HAS_DOS_ATTRIBUTES
      // is true) yet rejects the raw "attributes" name with IllegalArgumentException
      // ('attributes' not recognized) — treat that as "not a placeholder", same as the other
      // unreadable cases. Keeps the worker's scan cross-platform (tempdoc 668). No-op on Windows,
      // where the attribute is always readable.
      return false;
    }
  }

  /** Delegates to the shared {@link CloudPlaceholderRecorder}; retained for direct test access. */
  void recordCloudPlaceholderObservation(Path file) {
    if (cloudPlaceholderRecorder == null) {
      return;
    }
    cloudPlaceholderRecorder.record(file);
  }
}
