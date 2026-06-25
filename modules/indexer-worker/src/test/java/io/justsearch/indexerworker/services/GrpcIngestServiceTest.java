package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.CountJobsByPathPrefixRequest;
import io.justsearch.ipc.CountJobsByPathPrefixResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GrpcIngestServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;
  private JobQueue jobQueue;
  private GrpcIngestService service;

  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Use stub implementations for dependencies not needed in these tests
    IndexingLoop stubLoop = new StubIndexingLoop();
    WorkerSignalBus stubBus = new StubWorkerSignalBus();
    Path stubIndexBasePath = tempDir.resolve("indexBase");
    Path stubIndexPath = stubIndexBasePath.resolve("indices").resolve("g-test");
    Files.createDirectories(stubIndexPath);
    service = new GrpcIngestService(jobQueue, stubLoop, stubBus, stubIndexBasePath, stubIndexPath, null, null, null, 0L, null);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void submitBatchAcceptsExistingFiles() throws Exception {
    // Create real files for the test
    Path file1 = tempDir.resolve("file1.txt");
    Path file2 = tempDir.resolve("file2.txt");
    Files.writeString(file1, "content1");
    Files.writeString(file2, "content2");

    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths(file1.toAbsolutePath().toString())
        .addFilePaths(file2.toAbsolutePath().toString())
        .build();
    CapturingObserver<BatchResponse> observer = new CapturingObserver<>();

    service.submitBatch(request, observer);

    BatchResponse response = observer.single();
    assertEquals(2, response.getAcceptedCount());
    assertTrue(response.getErrorMessage().isEmpty());
    assertTrue(observer.completed);
  }

  @Test
  void submitBatchRejectsNonExistentFiles() {
    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths("/nonexistent/path/file.txt")
        .build();
    CapturingObserver<BatchResponse> observer = new CapturingObserver<>();

    service.submitBatch(request, observer);

    // Should complete without error but accept 0 files
    BatchResponse response = observer.single();
    assertEquals(0, response.getAcceptedCount());
    assertTrue(response.getErrorMessage().contains("invalid") || response.getErrorMessage().contains("All paths"));
    assertTrue(observer.completed);
  }

  @Test
  void submitBatchRejectsPathTraversalAttempts() throws Exception {
    // Create a real file
    Path realFile = tempDir.resolve("real.txt");
    Files.writeString(realFile, "content");

    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths(realFile.toAbsolutePath().toString())
        .addFilePaths(tempDir.toAbsolutePath() + "/../../../etc/passwd")
        .build();
    CapturingObserver<BatchResponse> observer = new CapturingObserver<>();

    service.submitBatch(request, observer);

    // Should accept the real file but reject the traversal attempt
    BatchResponse response = observer.single();
    assertEquals(1, response.getAcceptedCount());
    assertTrue(observer.completed);
  }

  @Test
  void submitBatchRejectsRelativePaths() throws Exception {
    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths("relative/path/file.txt")
        .build();
    CapturingObserver<BatchResponse> observer = new CapturingObserver<>();

    service.submitBatch(request, observer);

    BatchResponse response = observer.single();
    assertEquals(0, response.getAcceptedCount());
    assertTrue(observer.completed);
  }

  @Test
  void submitBatchHandlesEmptyRequest() {
    BatchRequest request = BatchRequest.newBuilder().build();
    CapturingObserver<BatchResponse> observer = new CapturingObserver<>();

    service.submitBatch(request, observer);

    BatchResponse response = observer.single();
    assertEquals(0, response.getAcceptedCount());
    assertTrue(observer.completed);
  }

  @Test
  void submitBatchRejectsOversizedBatch() {
    // Create a batch larger than MAX_BATCH_SIZE (10,000)
    BatchRequest.Builder builder = BatchRequest.newBuilder();
    for (int i = 0; i < 10_001; i++) {
      builder.addFilePaths("/path/to/file" + i + ".txt");
    }
    ErrorCapturingObserver<BatchResponse> observer = new ErrorCapturingObserver<>();

    service.submitBatch(builder.build(), observer);

    // Should reject with INVALID_ARGUMENT
    assertNotNull(observer.error);
    assertTrue(observer.error instanceof StatusException);
    assertEquals(Status.INVALID_ARGUMENT.getCode(),
        ((StatusException) observer.error).getStatus().getCode());
  }

  @Test
  void indexStatusReturnsQueueState() throws Exception {
    // Create a real file
    Path file = tempDir.resolve("file.txt");
    Files.writeString(file, "content");

    BatchRequest batchRequest = BatchRequest.newBuilder()
        .addFilePaths(file.toAbsolutePath().toString())
        .build();
    service.submitBatch(batchRequest, new CapturingObserver<>());

    CapturingObserver<StatusResponse> observer = new CapturingObserver<>();
    service.indexStatus(StatusRequest.getDefaultInstance(), observer);

    StatusResponse response = observer.single();
    assertEquals(1, response.getCore().getQueueDepth());
    assertEquals("INDEXING", response.getCore().getState());
    assertTrue(response.getCore().getIsHealthy());
    assertTrue(observer.completed);
  }

  @Test
  void indexStatusReturnsIdleWhenEmpty() {
    CapturingObserver<StatusResponse> observer = new CapturingObserver<>();
    service.indexStatus(StatusRequest.getDefaultInstance(), observer);

    StatusResponse response = observer.single();
    assertEquals(0, response.getCore().getQueueDepth());
    assertEquals("IDLE", response.getCore().getState());
    assertTrue(response.getCore().getIsHealthy());
  }

  // ========== SyncDirectory Tests ==========

  @Test
  void syncDirectoryReturnsErrorForBlankRootPath() {
    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath("")
        .build();
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();

    service.syncDirectory(request, observer);

    SyncDirectoryResponse response = observer.single();
    assertEquals("root_path is required", response.getError());
    assertTrue(observer.completed);
  }

  @Test
  void syncDirectoryReturnsErrorWhenIndexRuntimeIsNull() {
    // Service was created with indexRuntime = null
    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(tempDir.toAbsolutePath().toString())
        .build();
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();

    service.syncDirectory(request, observer);

    SyncDirectoryResponse response = observer.single();
    assertEquals("Index runtime not available", response.getError());
    assertTrue(observer.completed);
  }

  @Test
  void syncDirectoryReturnsErrorForNonExistentDirectory() throws Exception {
    // Create a service with a mock indexRuntime that returns 0 for prune
    // But directory doesn't exist, so it should fail before reaching indexRuntime
    Path nonExistent = tempDir.resolve("does_not_exist");

    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(nonExistent.toAbsolutePath().toString())
        .build();
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();

    service.syncDirectory(request, observer);

    // Should fail with "Index runtime not available" first since indexRuntime is null
    // But if indexRuntime was set, it would fail with "does not exist"
    SyncDirectoryResponse response = observer.single();
    assertTrue(response.getError().contains("not available") ||
               response.getError().contains("does not exist"),
        "Expected error about runtime or directory, got: " + response.getError());
    assertTrue(observer.completed);
  }

  @Test
  void syncDirectorySkipsWhenUserActiveAndForceIsFalse() throws Exception {
    // Create a service with user marked as active
    ConfigurableStubWorkerSignalBus activeBus = new ConfigurableStubWorkerSignalBus();
    activeBus.setUserActive(true);

    Path stubIndexBasePath = tempDir.resolve("indexBase");
    Path stubIndexPath = stubIndexBasePath.resolve("indices").resolve("g-test");
    Files.createDirectories(stubIndexPath);
    GrpcIngestService activeService = new GrpcIngestService(
        jobQueue, new StubIndexingLoop(), activeBus, stubIndexBasePath, stubIndexPath, null, null, null, 0L, null);

    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(tempDir.toAbsolutePath().toString())
        .setForce(false)
        .build();
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();

    activeService.syncDirectory(request, observer);

    SyncDirectoryResponse response = observer.single();
    assertTrue(response.getSkipped(), "Expected skipped=true when user is active");
    assertTrue(response.getError().isEmpty(), "Expected no error");
    assertTrue(observer.completed);
  }

  @Test
  void syncDirectoryDoesNotSkipWhenUserActiveAndForceIsTrue() throws Exception {
    // Create a service with user marked as active but force=true
    ConfigurableStubWorkerSignalBus activeBus = new ConfigurableStubWorkerSignalBus();
    activeBus.setUserActive(true);

    // With indexRuntime=null, it will fail after the user activity check
    // This proves force=true bypasses the user activity check
    Path stubIndexBasePath = tempDir.resolve("indexBase");
    Path stubIndexPath = stubIndexBasePath.resolve("indices").resolve("g-test");
    Files.createDirectories(stubIndexPath);
    GrpcIngestService activeService = new GrpcIngestService(
        jobQueue, new StubIndexingLoop(), activeBus, stubIndexBasePath, stubIndexPath, null, null, null, 0L, null);

    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(tempDir.toAbsolutePath().toString())
        .setForce(true)
        .build();
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();

    activeService.syncDirectory(request, observer);

    SyncDirectoryResponse response = observer.single();
    // Should NOT be skipped - should proceed to the indexRuntime check and fail there
    assertFalse(response.getSkipped(), "Expected skipped=false when force=true");
    assertEquals("Index runtime not available", response.getError());
    assertTrue(observer.completed);
  }

  /**
   * Verify that during SWITCHING state, if switch buffer write fails, UNAVAILABLE is returned.
   *
   * <p>This is critical for ACK-without-durability protection: the caller must retry when
   * switch buffer writes fail during the migration cutover phase.
   */
  @Test
  void submitBatchReturnsUnavailableWhenSwitchBufferWriteFailsDuringSwitching() throws Exception {
    // Close existing setup
    if (jobQueue != null) {
      jobQueue.close();
    }

    // Create a fresh index base path with SWITCHING state
    Path indexBasePath = tempDir.resolve("indexBase");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    // Write a state.json with migration_state = SWITCHING
    String stateJson = """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """.formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    // Create a fresh SqliteJobQueue
    Path dbPath = tempDir.resolve("switching-test.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Drop switch_buffer table to simulate SQL failure
    String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE switch_buffer");
    }

    // Create service with the SWITCHING indexBasePath
    IndexingLoop stubLoop = new StubIndexingLoop();
    WorkerSignalBus stubBus = new StubWorkerSignalBus();
    GrpcIngestService switchingService = new GrpcIngestService(
        jobQueue, stubLoop, stubBus, indexBasePath, genDir, null, null, null, 0L, null);

    // Create a real file for the request
    Path file = tempDir.resolve("testfile.txt");
    Files.writeString(file, "test content");

    BatchRequest request = BatchRequest.newBuilder()
        .addFilePaths(file.toAbsolutePath().toString())
        .build();
    ErrorCapturingObserver<BatchResponse> observer = new ErrorCapturingObserver<>();

    // Call submitBatch - should return UNAVAILABLE
    switchingService.submitBatch(request, observer);

    // Assert that we got an error (not a success response)
    assertNotNull(observer.error, "Expected UNAVAILABLE error when switch buffer write fails during SWITCHING");
    assertTrue(observer.error instanceof StatusException, "Error should be a StatusException");
    StatusException se = (StatusException) observer.error;
    assertEquals(Status.Code.UNAVAILABLE, se.getStatus().getCode(),
        "Expected UNAVAILABLE status code when switch buffer write fails");
    assertTrue(se.getStatus().getDescription().contains("Switch buffer write failed"),
        "Error description should mention switch buffer write failure");
  }

  @Test
  void submitBatchReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
    Path indexBasePath = tempDir.resolve("indexBase-submit-nonsqlite");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    Path file = tempDir.resolve("submit-switching-test.txt");
    Files.writeString(file, "test content");

    JobQueue nonSqliteQueue = new NoopJobQueue();
    GrpcIngestService switchingService =
        new GrpcIngestService(
            nonSqliteQueue, new StubIndexingLoop(), new StubWorkerSignalBus(), indexBasePath, genDir, null, null, null, 0L, null);

    ErrorCapturingObserver<BatchResponse> observer = new ErrorCapturingObserver<>();
    switchingService.submitBatch(
        BatchRequest.newBuilder().addFilePaths(file.toAbsolutePath().toString()).build(), observer);

    assertNotNull(observer.error, "Expected UNAVAILABLE error in SWITCHING when queue is not SqliteJobQueue");
    assertTrue(observer.error instanceof StatusException, "Error should be a StatusException");
    StatusException se = (StatusException) observer.error;
    assertEquals(Status.Code.UNAVAILABLE, se.getStatus().getCode());
    assertTrue(se.getStatus().getDescription().contains("Migration is switching"));
  }

  @Test
  void syncDirectoryReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
    Path indexBasePath = tempDir.resolve("indexBase-nonsqlite");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    JobQueue nonSqliteQueue = new NoopJobQueue();
    GrpcIngestService switchingService =
        new GrpcIngestService(
            nonSqliteQueue, new StubIndexingLoop(), new StubWorkerSignalBus(), indexBasePath, genDir, null, null, null, 0L, null);

    ErrorCapturingObserver<SyncDirectoryResponse> observer = new ErrorCapturingObserver<>();
    switchingService.syncDirectory(
        SyncDirectoryRequest.newBuilder().setRootPath(tempDir.toAbsolutePath().toString()).build(),
        observer);

    assertNotNull(observer.error, "Expected UNAVAILABLE error in SWITCHING when queue is not SqliteJobQueue");
    assertTrue(observer.error instanceof StatusException, "Error should be a StatusException");
    StatusException se = (StatusException) observer.error;
    assertEquals(Status.Code.UNAVAILABLE, se.getStatus().getCode());
    assertTrue(se.getStatus().getDescription().contains("Migration is switching"));
  }

  @Test
  void syncDirectoryBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }

    Path indexBasePath = tempDir.resolve("indexBase-sync-sqlite");
    Path indicesDir = indexBasePath.resolve("indices");
    Path statePath = indexBasePath.resolve("state.json");
    Path genDir = indicesDir.resolve("g-test");
    Files.createDirectories(genDir);

    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);

    Path dbPath = tempDir.resolve("switching-sync-sqlite.db");
    SqliteJobQueue sqliteQueue = new SqliteJobQueue(dbPath);
    sqliteQueue.open();
    jobQueue = sqliteQueue;

    GrpcIngestService switchingService =
        new GrpcIngestService(
            sqliteQueue, new StubIndexingLoop(), new StubWorkerSignalBus(), indexBasePath, genDir, null, null, null, 0L, null);

    String rootPath = tempDir.toAbsolutePath().toString();
    CapturingObserver<SyncDirectoryResponse> observer = new CapturingObserver<>();
    switchingService.syncDirectory(
        SyncDirectoryRequest.newBuilder().setRootPath(rootPath).setForce(true).build(),
        observer);

    SyncDirectoryResponse response = observer.single();
    assertTrue(observer.completed);
    assertFalse(response.getSkipped());
    assertEquals(0, response.getFilesAdded());
    assertEquals(0, response.getFilesDeleted());
    assertTrue(response.getDeferredToSwitchBuffer());
    assertTrue(response.getError().contains("DEFERRED"));

    String normalizedRoot =
        io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(rootPath);
    String expectedRoot = normalizedRoot == null ? rootPath : normalizedRoot;
    SqliteJobQueue.SwitchBufferOp op =
        sqliteQueue.listSwitchBufferOps().stream()
            .filter(entry -> ("sync_root:" + expectedRoot).equals(entry.key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing switch_buffer op for sync root"));

    assertEquals("SYNC_ROOT", op.op());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = JSON.readValue(op.payload(), Map.class);
    assertEquals(expectedRoot, payload.get("root_path"));
    assertEquals(true, payload.get("force"));
  }

  // ========== File Walking Behavior Tests ==========

  @Test
  void isCloudPlaceholder_returnsFalseForNormalFile() throws Exception {
    Path file = tempDir.resolve("normal.txt");
    Files.writeString(file, "hello");

    // On any platform, a normal file should not be a cloud placeholder
    assertFalse(SyncDirectoryOps.isCloudPlaceholder(file));
  }

  @Test
  void walkFileTree_visitFileFailed_continuesWalk() throws Exception {
    // Create structure: dir/a.txt, dir/sub/b.txt
    Path dir = tempDir.resolve("walk-resilience");
    Path sub = dir.resolve("sub");
    Files.createDirectories(sub);
    Files.writeString(dir.resolve("a.txt"), "a");
    Files.writeString(sub.resolve("b.txt"), "b");

    // Walk with a visitFileFailed that continues (same pattern as production code)
    List<String> visited = new ArrayList<>();
    List<String> failures = new ArrayList<>();

    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            visited.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            failures.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });

    // Both files should be visited despite being in different directories
    assertTrue(visited.contains("a.txt"), "Expected a.txt visited, got: " + visited);
    assertTrue(visited.contains("b.txt"), "Expected b.txt visited, got: " + visited);
    assertTrue(failures.isEmpty(), "No failures expected for accessible files");
  }

  @Test
  void walkSkipDirs_skipsSystemDirectories() throws Exception {
    // Create structure with system dirs and a normal dir
    Path root = tempDir.resolve("walk-skip");
    Files.createDirectories(root);

    // System dirs that should be skipped
    Path recycleBin = root.resolve("$Recycle.Bin");
    Path nodeModules = root.resolve("node_modules");
    Path gitDir = root.resolve(".git");
    // Normal dir that should be traversed
    Path docs = root.resolve("docs");

    for (Path d : List.of(recycleBin, nodeModules, gitDir, docs)) {
      Files.createDirectories(d);
      Files.writeString(d.resolve("file.txt"), "content");
    }
    Files.writeString(root.resolve("root.txt"), "content");

    // Walk with the same preVisitDirectory pattern as production code
    List<String> visitedFiles = new ArrayList<>();
    Set<String> skipDirs =
        Set.of(
            "$recycle.bin",
            "system volume information",
            ".git",
            ".svn",
            ".hg",
            "node_modules");

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (dir.equals(root)) return FileVisitResult.CONTINUE;
            String name =
                dir.getFileName() != null
                    ? dir.getFileName().toString().toLowerCase(Locale.ROOT)
                    : "";
            if (skipDirs.contains(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            visitedFiles.add(
                root.relativize(file).toString().replace('\\', '/'));
            return FileVisitResult.CONTINUE;
          }
        });

    // root.txt and docs/file.txt should be visited
    assertTrue(
        visitedFiles.contains("root.txt"), "Expected root.txt visited, got: " + visitedFiles);
    assertTrue(
        visitedFiles.contains("docs/file.txt"),
        "Expected docs/file.txt visited, got: " + visitedFiles);

    // Files inside system dirs should NOT be visited
    assertFalse(
        visitedFiles.stream().anyMatch(f -> f.startsWith("$Recycle.Bin")),
        "Should not visit files in $Recycle.Bin");
    assertFalse(
        visitedFiles.stream().anyMatch(f -> f.startsWith("node_modules")),
        "Should not visit files in node_modules");
    assertFalse(
        visitedFiles.stream().anyMatch(f -> f.startsWith(".git")),
        "Should not visit files in .git");
  }

  @Test
  void countJobsByPathPrefixReturnsZeroForEmptyQueue() {
    CountJobsByPathPrefixRequest request =
        CountJobsByPathPrefixRequest.newBuilder()
            .setPathPrefix(tempDir.resolve("roota").toAbsolutePath().toString())
            .build();
    CapturingObserver<CountJobsByPathPrefixResponse> observer = new CapturingObserver<>();

    service.countJobsByPathPrefix(request, observer);

    CountJobsByPathPrefixResponse response = observer.single();
    assertEquals(0, response.getCounts().getPendingCount());
    assertEquals(0, response.getCounts().getProcessingCount());
    assertEquals(0, response.getCounts().getFailedCount());
    assertTrue(observer.completed);
  }

  @Test
  void countJobsByPathPrefixFiltersByPrefix() throws Exception {
    // Two sibling roots; enqueue two PENDING jobs under roota, one under rootb.
    Path rootA = Files.createDirectories(tempDir.resolve("roota"));
    Path rootB = Files.createDirectories(tempDir.resolve("rootb"));
    Path a1 = rootA.resolve("a1.txt");
    Path a2 = rootA.resolve("a2.txt");
    Path b1 = rootB.resolve("b1.txt");
    Files.writeString(a1, "a1");
    Files.writeString(a2, "a2");
    Files.writeString(b1, "b1");
    BatchResponse enqueued =
        observeSingle(
            obs ->
                service.submitBatch(
                    BatchRequest.newBuilder()
                        .addFilePaths(a1.toAbsolutePath().toString())
                        .addFilePaths(a2.toAbsolutePath().toString())
                        .addFilePaths(b1.toAbsolutePath().toString())
                        .build(),
                    obs));
    assertEquals(3, enqueued.getAcceptedCount());

    CountJobsByPathPrefixRequest request =
        CountJobsByPathPrefixRequest.newBuilder()
            .setPathPrefix(rootA.toAbsolutePath().toString())
            .build();
    CapturingObserver<CountJobsByPathPrefixResponse> observer = new CapturingObserver<>();

    service.countJobsByPathPrefix(request, observer);

    CountJobsByPathPrefixResponse response = observer.single();
    // Only roota's two jobs counted; rootb excluded by the prefix boundary.
    assertEquals(2, response.getCounts().getPendingCount());
    assertEquals(0, response.getCounts().getProcessingCount());
    assertEquals(0, response.getCounts().getFailedCount());
    assertTrue(observer.completed);
  }

  @Test
  void countJobsByPathPrefixDoesNotTreatUnderscoreAsWildcard() throws Exception {
    // Tempdoc 599 Fix 2: the prefix "a_b" must NOT match the sibling "aXb" (a LIKE '_' wildcard
    // would). The range-query implementation matches only true path-prefix descendants.
    Path aUnderscoreB = Files.createDirectories(tempDir.resolve("a_b"));
    Path aXb = Files.createDirectories(tempDir.resolve("aXb"));
    Path f1 = aUnderscoreB.resolve("f1.txt");
    Path f2 = aXb.resolve("f2.txt");
    Files.writeString(f1, "f1");
    Files.writeString(f2, "f2");
    BatchResponse enqueued =
        observeSingle(
            obs ->
                service.submitBatch(
                    BatchRequest.newBuilder()
                        .addFilePaths(f1.toAbsolutePath().toString())
                        .addFilePaths(f2.toAbsolutePath().toString())
                        .build(),
                    obs));
    assertEquals(2, enqueued.getAcceptedCount());

    CountJobsByPathPrefixRequest request =
        CountJobsByPathPrefixRequest.newBuilder()
            .setPathPrefix(aUnderscoreB.toAbsolutePath().toString())
            .build();
    CapturingObserver<CountJobsByPathPrefixResponse> observer = new CapturingObserver<>();

    service.countJobsByPathPrefix(request, observer);

    CountJobsByPathPrefixResponse response = observer.single();
    // Only a_b's one job — the aXb sibling is excluded (no underscore-as-wildcard bleed).
    assertEquals(1, response.getCounts().getPendingCount());
    assertTrue(observer.completed);
  }

  private <T> T observeSingle(java.util.function.Consumer<StreamObserver<T>> call) {
    CapturingObserver<T> obs = new CapturingObserver<>();
    call.accept(obs);
    return obs.single();
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;
    boolean completed = false;

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
      completed = true;
    }

    T single() {
      if (value == null) {
        throw new AssertionError("no value captured");
      }
      return value;
    }
  }

  private static final class ErrorCapturingObserver<T> implements StreamObserver<T> {
    Throwable error;

    @Override
    public void onNext(T value) {}

    @Override
    public void onError(Throwable t) {
      this.error = t;
    }

    @Override
    public void onCompleted() {}
  }

  /** Stub IndexingLoop for testing - returns sensible defaults. */
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

  /** Minimal non-SQLite queue for SWITCHING fallback-path tests. */
  private static final class NoopJobQueue implements JobQueue {
    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
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

  /** Stub WorkerSignalBus for testing - returns sensible defaults. */
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
      return false;  // Assume GPU is free in tests
    }

    @Override
    public long startupTime() {
      return startupTime;
    }

    @Override
    public void close() {}
  }

  /** Configurable stub for testing user activity behavior. */
  private static final class ConfigurableStubWorkerSignalBus implements WorkerSignalBus {
    private final long startupTime = System.currentTimeMillis();
    private volatile boolean userActive = false;

    void setUserActive(boolean active) {
      this.userActive = active;
    }

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
      return userActive;
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
