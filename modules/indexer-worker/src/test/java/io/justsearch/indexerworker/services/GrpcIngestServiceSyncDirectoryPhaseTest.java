package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GrpcIngestService syncDirectory phase behavior")
final class GrpcIngestServiceSyncDirectoryPhaseTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private RunningRuntime lifecycle;
  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forTesting(0)).atPath(tempDir).open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
    // Clear interrupted flag if any test set it.
    Thread.interrupted();
  }

  @Test
  @DisplayName("syncDirectory prunes indexed orphans and enqueues missing files")
  void syncDirectoryPrunesOrphansAndEnqueuesMissingFiles() throws Exception {
    Path root = tempDir.resolve("sync-root-prune-walk");
    Files.createDirectories(root);

    Path liveFile = root.resolve("live.txt");
    Files.writeString(liveFile, "live");

    Path orphanPath = root.resolve("orphan-missing.txt");
    indexDocument(orphanPath, "orphan-doc");

    GrpcIngestService service = newService(new StubWorkerSignalBus());
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();
    service.syncDirectory(
        SyncDirectoryRequest.newBuilder().setRootPath(root.toAbsolutePath().toString()).build(),
        observer);

    SyncDirectoryResponse response = observer.single();
    assertTrue(observer.completed);
    assertFalse(response.getSkipped(), "Expected full sync path");
    assertEquals("", response.getError());
    assertTrue(response.getFilesDeleted() >= 1, "Expected orphan pruning to delete at least one doc");
    assertTrue(response.getFilesAdded() >= 1, "Expected missing disk file to be enqueued");
  }

  @Test
  @DisplayName("syncDirectory force mode enqueues files without indexed-path scan")
  void syncDirectoryForceModeEnqueuesFilesWithoutIndexedScan() throws Exception {
    Path root = tempDir.resolve("sync-root-force");
    Files.createDirectories(root);
    for (int i = 0; i < 3; i++) {
      Files.writeString(root.resolve("force-" + i + ".txt"), "x");
    }

    GrpcIngestService service = newService(new StubWorkerSignalBus());
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();
    service.syncDirectory(
        SyncDirectoryRequest.newBuilder()
            .setRootPath(root.toAbsolutePath().toString())
            .setForce(true)
            .build(),
        observer);

    SyncDirectoryResponse response = observer.single();
    assertTrue(observer.completed);
    assertFalse(response.getSkipped(), "Expected force-mode sync to complete");
    assertEquals("", response.getError());
    assertTrue(response.getFilesAdded() >= 3, "Expected force-mode walk to enqueue all files");
  }

  @Test
  @DisplayName("syncDirectory returns error when root directory does not exist")
  void syncDirectoryReturnsErrorForNonExistentRoot() throws Exception {
    Path missingRoot = tempDir.resolve("sync-root-missing");

    GrpcIngestService service = newService(new StubWorkerSignalBus());
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();
    service.syncDirectory(
        SyncDirectoryRequest.newBuilder().setRootPath(missingRoot.toAbsolutePath().toString()).build(),
        observer);

    SyncDirectoryResponse response = observer.single();
    assertTrue(observer.completed);
    assertFalse(response.getSkipped(), "Expected error path, not skipped");
    assertEquals("Root path does not exist or is not a directory", response.getError());
    assertEquals(0, response.getFilesAdded());
    assertEquals(0, response.getFilesDeleted());
  }

  private void indexDocument(Path docPath, String uidSuffix) throws Exception {
    String normalized = io.justsearch.indexerworker.util.PathNormalizer.normalizePath(docPath.toAbsolutePath().toString());
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, normalized);
    fields.put(SchemaFields.DOC_UID, "uid-" + uidSuffix);
    fields.put(SchemaFields.PATH, normalized);
    fields.put(SchemaFields.CONTENT, "indexed content");
    fields.put(SchemaFields.CONTENT_PREVIEW, "indexed content");
    fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());
    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(fields));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  private GrpcIngestService newService(WorkerSignalBus bus) throws Exception {
    Path indexBasePath = tempDir.resolve("indexBase-" + System.nanoTime());
    Path indexPath = tempDir.resolve("index-" + System.nanoTime());
    Files.createDirectories(indexBasePath);
    Files.createDirectories(indexPath);
    return new GrpcIngestService(
        jobQueue, new StubIndexingLoop(), bus, indexBasePath, indexPath, lifecycle, lifecycle, null, 0L, null);
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;
    boolean completed;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable t) {
      throw new AssertionError("unexpected error", t);
    }

    @Override
    public void onCompleted() {
      this.completed = true;
    }

    T single() {
      if (value == null) {
        throw new AssertionError("no value captured");
      }
      return value;
    }
  }

  private static final class StubIndexingLoop extends IndexingLoop {
    StubIndexingLoop() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public long getLastCommitTime() {
      return System.currentTimeMillis();
    }

    @Override
    public String getCurrentState() {
      return "IDLE";
    }

    @Override
    public void start() {}

    @Override
    public void close() {}
  }

  private static final class StubWorkerSignalBus implements WorkerSignalBus {
    private final long startupTime = System.currentTimeMillis();

    @Override
    public void open() {}

    @Override
    public void writePort(int port) {}

    @Override
    public long readActivity() {
      return 0;
    }

    @Override
    public long readHeartbeat() {
      return System.currentTimeMillis();
    }

    @Override
    public boolean isShutdownRequested() {
      return false;
    }

    @Override
    public boolean shouldDie() {
      return false;
    }

    @Override
    public boolean isUserActive() {
      return false;
    }

    @Override
    public boolean isMainGpuActive() {
      return false;
    }

    @Override
    public long startupTime() {
      return startupTime;
    }

    @Override
    public void close() {}
  }
}
