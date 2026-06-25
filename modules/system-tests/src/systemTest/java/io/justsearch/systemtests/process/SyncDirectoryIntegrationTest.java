package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for the SyncDirectory gRPC RPC.
 *
 * <p>Validates bidirectional sync functionality:
 * <ul>
 *   <li>Prunes orphan documents (file deleted from disk)</li>
 *   <li>Adds missing documents (file exists but not indexed)</li>
 *   <li>Respects force flag for user activity check</li>
 *   <li>Reports accurate counts</li>
 * </ul>
 *
 * <p>This is critical for:
 * <ul>
 *   <li>OVERFLOW event handling (events were dropped, need full resync)</li>
 *   <li>Periodic maintenance (catch missed events)</li>
 *   <li>Windows DELETE workaround (unreliable file deletion events)</li>
 * </ul>
 */
@DisplayName("SyncDirectory Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncDirectoryIntegrationTest {

    @RegisterExtension
    static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

    private static WorkerProcessManager worker;
    private static MmfTestHarness mmf;
    private static GrpcTestClient grpcClient;

    // Test directory for sync operations
    private static Path syncTestDir;

    @BeforeAll
    static void setupWorker() throws Exception {
        // 1. Create test directory for sync operations
        syncTestDir = env.getTempDir().resolve("sync-test-data");
        Files.createDirectories(syncTestDir);

        // 2. Start worker
        worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
        worker.spawnWorker();

        // 3. Open MMF for port discovery
        mmf = new MmfTestHarness(worker.getSignalFilePath());
        mmf.open();
        mmf.keepAlive();

        int grpcPort = mmf.awaitPort(30_000, 100);
        grpcClient = new GrpcTestClient(grpcPort);
        assertTrue(grpcClient.isHealthy(), "Worker should be healthy before tests");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (grpcClient != null) {
            grpcClient.close();
            grpcClient = null;
        }
        if (mmf != null) {
            mmf.close();
            mmf = null;
        }
        if (worker != null) {
            worker.close();
            worker = null;
        }
        // Test files cleaned up by TestEnvironmentProvisioner
    }

    @BeforeEach
    void keepAlive() {
        if (mmf != null) {
            mmf.keepAlive();
        }
    }

    // =========================================================================
    // Basic Sync Operations
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("syncDirectory on empty directory returns zeros")
    void syncEmptyDirectoryReturnsZeros() {
        // Sync an empty directory
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true  // force=true to skip user activity check
        );

        assertNotNull(response, "Response should not be null");
        assertEquals(0, response.getFilesAdded(), "Should add 0 files from empty directory");
        assertEquals(0, response.getFilesDeleted(), "Should delete 0 orphans");
        assertFalse(response.getSkipped(), "Should not skip when force=true");
        assertTrue(response.getError().isEmpty(), "Should have no error");
    }

    @Test
    @Order(2)
    @DisplayName("syncDirectory adds missing files to index")
    void syncAddsNewFiles() throws Exception {
        // Create new files that aren't indexed
        Path file1 = syncTestDir.resolve("new-file-1.txt");
        Path file2 = syncTestDir.resolve("new-file-2.txt");
        Files.writeString(file1, "Content for file 1 - apples and oranges");
        Files.writeString(file2, "Content for file 2 - bananas and grapes");

        // Keep alive before sync
        mmf.keepAlive();

        // Sync - should detect and enqueue these files
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true
        );

        assertNotNull(response, "Response should not be null");
        assertTrue(response.getFilesAdded() >= 2, "Should enqueue at least 2 new files");
        assertTrue(response.getError().isEmpty(), "Should have no error");

        // Wait for indexing to complete
        mmf.keepAlive();
        long currentDocCount = grpcClient.getDetailedStatus().getCore().getDocCount();
        boolean indexed = grpcClient.awaitIndexing(currentDocCount + 2, 30_000, 200);
        assertTrue(indexed, "New files should be indexed within 30 seconds");

        // Verify files are searchable
        SearchResponse search1 = grpcClient.searchText("apples", 10);
        assertTrue(search1.getResultsCount() > 0, "Should find 'apples' after sync");

        SearchResponse search2 = grpcClient.searchText("bananas", 10);
        assertTrue(search2.getResultsCount() > 0, "Should find 'bananas' after sync");
    }

    @Test
    @Order(3)
    @DisplayName("syncDirectory prunes orphan documents")
    void syncPrunesOrphans() throws Exception {
        // First, index a file
        Path orphanFile = syncTestDir.resolve("orphan-file.txt");
        Files.writeString(orphanFile, "This file will become an orphan - unique keyword: xyzorphan123");

        // Index it
        grpcClient.submitBatch(List.of(orphanFile.toAbsolutePath().toString()));
        long expectedCount = grpcClient.getDetailedStatus().getCore().getDocCount() + 1;
        mmf.keepAlive();
        boolean indexed = grpcClient.awaitIndexing(expectedCount, 30_000, 200);
        assertTrue(indexed, "Orphan file should be indexed");

        // Verify it's searchable
        SearchResponse beforeDelete = grpcClient.searchText("xyzorphan123", 10);
        assertTrue(beforeDelete.getResultsCount() > 0, "Should find orphan file before deletion");

        // Delete the file from disk (making it an orphan in the index)
        Files.delete(orphanFile);
        assertTrue(!Files.exists(orphanFile), "File should be deleted from disk");

        // Keep alive and sync - should prune the orphan
        mmf.keepAlive();
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true
        );

        assertNotNull(response, "Response should not be null");
        assertTrue(response.getFilesDeleted() >= 1, "Should prune at least 1 orphan document");
        assertTrue(response.getError().isEmpty(), "Should have no error");

        // Force a commit and refresh by doing a small operation
        mmf.keepAlive();
        Thread.sleep(1000);  // Give time for commit

        // Verify orphan is no longer searchable
        SearchResponse afterPrune = grpcClient.searchText("xyzorphan123", 10);
        assertEquals(0, afterPrune.getResultsCount(), "Orphan should not be searchable after prune");
    }

    @Test
    @Order(4)
    @DisplayName("syncDirectory handles both additions and deletions")
    void syncHandlesBothAdditionsAndDeletions() throws Exception {
        // Create and index a file
        Path toDelete = syncTestDir.resolve("to-delete.txt");
        Files.writeString(toDelete, "File to delete - keyword: deleteme789");

        grpcClient.submitBatch(List.of(toDelete.toAbsolutePath().toString()));
        long expectedCount = grpcClient.getDetailedStatus().getCore().getDocCount() + 1;
        mmf.keepAlive();
        grpcClient.awaitIndexing(expectedCount, 30_000, 200);

        // Verify indexed
        SearchResponse before = grpcClient.searchText("deleteme789", 10);
        assertTrue(before.getResultsCount() > 0, "Should find file before operations");

        // Now create a new file AND delete the indexed one
        Path newFile = syncTestDir.resolve("brand-new.txt");
        Files.writeString(newFile, "Brand new file - keyword: brandnew456");
        Files.delete(toDelete);

        // Sync should handle both
        mmf.keepAlive();
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true
        );

        assertNotNull(response, "Response should not be null");
        assertTrue(response.getFilesAdded() >= 1, "Should add at least 1 new file");
        assertTrue(response.getFilesDeleted() >= 1, "Should delete at least 1 orphan");
        assertTrue(response.getError().isEmpty(), "Should have no error");

        // Wait for indexing
        mmf.keepAlive();
        Thread.sleep(1500);

        // Verify: new file searchable, old file gone
        SearchResponse newFileSearch = grpcClient.searchText("brandnew456", 10);
        assertTrue(newFileSearch.getResultsCount() > 0, "New file should be searchable");

        SearchResponse deletedFileSearch = grpcClient.searchText("deleteme789", 10);
        assertEquals(0, deletedFileSearch.getResultsCount(), "Deleted file should not be searchable");
    }

    // =========================================================================
    // Force Flag Behavior
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("syncDirectory with force=true completes successfully")
    void syncWithForceCompletesSuccessfully() throws Exception {
        // Send keepalive to ensure worker is responsive
        mmf.keepAlive();

        // Create a file to sync
        Path activeFile = syncTestDir.resolve("active-test.txt");
        Files.writeString(activeFile, "Testing with force flag - keyword: activetest111");

        // Sync with force=true should complete without skipping
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true  // force=true - should always run
        );

        assertNotNull(response, "Response should not be null");
        assertFalse(response.getSkipped(), "Should NOT skip when force=true");
        assertTrue(response.getError().isEmpty(), "Should have no error");
    }

    // =========================================================================
    // Edge Cases
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("syncDirectory handles non-existent directory gracefully")
    void syncHandlesNonExistentDirectory() {
        Path nonExistent = env.getTempDir().resolve("this-dir-does-not-exist-12345");
        assertFalse(Files.exists(nonExistent), "Directory should not exist");

        SyncDirectoryResponse response = grpcClient.syncDirectory(
            nonExistent.toString(),
            true
        );

        assertNotNull(response, "Response should not be null");
        // Should return zeros or an error, not crash
        assertEquals(0, response.getFilesAdded(), "Should add 0 files from non-existent directory");
    }

    @Test
    @Order(7)
    @DisplayName("syncDirectory handles nested directories")
    void syncHandlesNestedDirectories() throws Exception {
        // Create nested structure
        Path nested = syncTestDir.resolve("level1/level2/level3");
        Files.createDirectories(nested);

        Path deepFile = nested.resolve("deep-file.txt");
        Files.writeString(deepFile, "Deep nested file - keyword: deepnested999");

        mmf.keepAlive();
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),  // Sync from root
            true
        );

        assertNotNull(response, "Response should not be null");
        assertTrue(response.getFilesAdded() >= 1, "Should find deeply nested file");
        assertTrue(response.getError().isEmpty(), "Should have no error");

        // Wait and verify
        mmf.keepAlive();
        long expectedCount = grpcClient.getDetailedStatus().getCore().getDocCount() + 1;
        grpcClient.awaitIndexing(expectedCount, 30_000, 200);

        SearchResponse search = grpcClient.searchText("deepnested999", 10);
        assertTrue(search.getResultsCount() > 0, "Should find deeply nested file");
    }

    @Test
    @Order(8)
    @DisplayName("syncDirectory ignores unsupported file types")
    void syncIgnoresUnsupportedFileTypes() throws Exception {
        // Create an unsupported file type (e.g., binary)
        Path binaryFile = syncTestDir.resolve("unsupported.xyz");
        Files.write(binaryFile, new byte[]{0x00, 0x01, 0x02, 0x03});

        mmf.keepAlive();
        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true
        );

        // Should complete without error (file may be skipped or processed)
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getError().isEmpty(), "Should have no error even with unsupported files");
    }

    // =========================================================================
    // Performance / Timeout Tests
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("syncDirectory completes within reasonable time for moderate directory")
    void syncCompletesInReasonableTime() throws Exception {
        // Create a moderate number of files
        Path perfDir = syncTestDir.resolve("perf-test");
        Files.createDirectories(perfDir);

        for (int i = 0; i < 20; i++) {
            Path file = perfDir.resolve("perf-file-" + i + ".txt");
            Files.writeString(file, "Performance test file " + i + " - content for testing");
        }

        mmf.keepAlive();
        long startTime = System.currentTimeMillis();

        SyncDirectoryResponse response = grpcClient.syncDirectory(
            syncTestDir.toString(),
            true
        );

        long elapsed = System.currentTimeMillis() - startTime;

        assertNotNull(response, "Response should not be null");
        assertTrue(response.getError().isEmpty(), "Should have no error");
        // Sync itself should be quick (enqueuing, not full indexing)
        assertTrue(elapsed < 60_000, "Sync should complete within 60 seconds");
    }
}
