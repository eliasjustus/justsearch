package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mirrors {@link WorkerScanOpsTest#appliesIngestionSkipPolicyAtWalkTime} for the sync-directory
 * walk path — {@code SyncDirectoryOps.visitFile} did not apply {@code IngestionSkipPolicy} at
 * walk time, so junk files (lock files, temp files, thumbnail caches, compiled bytecode) reached
 * the enqueue path instead of being skipped like the initial-scan walk in {@link WorkerScanOps}.
 */
final class SyncDirectoryOpsWalkSkipPolicyTest {

  @TempDir Path tempDir;

  @Test
  void appliesIngestionSkipPolicyAtWalkTime() throws Exception {
    Path root = tempDir.resolve("policy-skip");
    Files.createDirectories(root);
    Files.writeString(root.resolve("module.pyc"), "bytecode");
    Files.writeString(root.resolve("~$Office.docx"), "lock-file");
    Files.writeString(root.resolve("draft.tmp"), "temp");
    Files.writeString(root.resolve("Thumbs.db"), "system");
    Path keep = Files.writeString(root.resolve("notes.md"), "kept");
    RecordingQueue queue = new RecordingQueue();
    SyncDirectoryOps ops = new SyncDirectoryOps(null, null, null, queue, null);

    // force=true so the walk enqueues every non-skipped file unconditionally (no indexed-path
    // lookup, which would require a real readPathOps).
    ops.execute(root.toString(), true, new CapturingObserver());

    assertEquals(List.of(keep), queue.enqueuedPaths, "Only the non-policy-skipped file is enqueued");
  }

  private static final class RecordingQueue implements JobQueue {
    final List<Path> enqueuedPaths = new ArrayList<>();

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
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

  private static final class CapturingObserver implements StreamObserver<SyncDirectoryResponse> {
    @Override
    public void onNext(SyncDirectoryResponse value) {}

    @Override
    public void onError(Throwable t) {
      throw new AssertionError("unexpected error", t);
    }

    @Override
    public void onCompleted() {}
  }
}
