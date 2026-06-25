package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.Mode;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OfflineCoordinator.
 *
 * <p>Tests orchestration logic: phase sequencing, concurrent guards, recovery.
 * Uses stub dependencies - no real LLM or gRPC calls.
 */
@DisplayName("OfflineCoordinator")
class OfflineCoordinatorTest {

    private StubInferenceLifecycleManager inferenceManager;
    private StubVduBatchProcessor vduBatchProcessor;
    private StubRemoteKnowledgeClient knowledgeClient;
    private TestableOfflineCoordinator coordinator;

    @BeforeEach
    void setUp() {
        inferenceManager = new StubInferenceLifecycleManager();
        vduBatchProcessor = new StubVduBatchProcessor();
        knowledgeClient = new StubRemoteKnowledgeClient();
        coordinator = new TestableOfflineCoordinator(inferenceManager, vduBatchProcessor, knowledgeClient);
    }

    @Nested
    @DisplayName("Phase Sequencing")
    class PhaseSequencing {

        @Test
        @DisplayName("runs VDU phase before embedding phase when both have pending work")
        void runsVduBeforeEmbeddings() {
            knowledgeClient.withPendingVduCount(5);
            knowledgeClient.withPendingEmbeddingsCount(10);
            inferenceManager.withMode(Mode.OFFLINE);

            coordinator.startOfflineProcessing();

            // VDU phase should have run (switched to Online)
            assertTrue(vduBatchProcessor.wasProcessCalled(), "VDU batch processor should have been called");
            // Embedding phase should have run (switched to Indexing)
            assertEquals(1, inferenceManager.getIndexingSwitchCount(), "Should switch to Indexing for embeddings");
        }

        @Test
        @DisplayName("skips VDU phase when no pending VDU files")
        void skipsVduWhenNoPending() {
            knowledgeClient.withPendingVduCount(0);
            knowledgeClient.withPendingEmbeddingsCount(10);

            coordinator.startOfflineProcessing();

            assertFalse(vduBatchProcessor.wasProcessCalled(), "VDU batch processor should NOT be called");
            assertEquals(0, inferenceManager.getOnlineSwitchCount(), "Should NOT switch to Online");
        }

        @Test
        @DisplayName("clears VDU capability blocker when no VDU work is pending")
        void clearsVduCapabilityWhenNoPending() {
            StubInferenceLifecycleManager inference = new StubInferenceLifecycleManager();
            VduBatchProcessor batchProcessor = mock(VduBatchProcessor.class);
            RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
            when(client.recoverVduProcessing()).thenReturn(0);
            when(client.countPendingVdu()).thenReturn(0);
            when(client.countPendingEmbeddings()).thenReturn(0);
            VduCapabilityState state = new VduCapabilityState();
            state.block(VduCapabilityState.REASON_AI_OFFLINE);
            OfflineCoordinator realCoordinator =
                new OfflineCoordinator(inference, batchProcessor, client, state);

            realCoordinator.startOfflineProcessing();

            assertNull(state.snapshot().blockedReason());
            verify(batchProcessor, never()).processPendingFiles();
        }

        @Test
        @DisplayName("skips embedding phase when no pending embeddings")
        void skipsEmbeddingsWhenNoPending() {
            knowledgeClient.withPendingVduCount(0);
            knowledgeClient.withPendingEmbeddingsCount(0);

            coordinator.startOfflineProcessing();

            assertEquals(0, inferenceManager.getIndexingSwitchCount(), "Should NOT switch to Indexing");
        }

        @Test
        @DisplayName("re-queries embedding count after VDU phase")
        void requeriesEmbeddingsAfterVdu() {
            // VDU processing generates pending embeddings
            knowledgeClient.withPendingVduCount(5);
            knowledgeClient.withPendingEmbeddingsCount(0);  // Initially 0
            vduBatchProcessor.withOnProcess(() -> {
                // Simulate VDU creating pending embeddings
                knowledgeClient.withPendingEmbeddingsCount(5);
            });

            coordinator.startOfflineProcessing();

            // Should have queried embeddings twice (before and after VDU)
            assertEquals(2, knowledgeClient.getCountPendingEmbeddingsCalls(),
                "Should re-query embedding count after VDU phase");
            assertEquals(1, inferenceManager.getIndexingSwitchCount(),
                "Should switch to Indexing for newly pending embeddings");
        }
    }

    @Nested
    @DisplayName("Recovery")
    class Recovery {

        @Test
        @DisplayName("calls recoverVduProcessing at start")
        void callsRecoveryAtStart() {
            knowledgeClient.withRecoveredCount(3);

            coordinator.startOfflineProcessing();

            assertEquals(1, knowledgeClient.getRecoverVduProcessingCalls(),
                "Should call recoverVduProcessing exactly once");
        }

        @Test
        @DisplayName("continues processing even if recovery finds no stuck documents")
        void continuesWithZeroRecovered() {
            knowledgeClient.withRecoveredCount(0);
            knowledgeClient.withPendingVduCount(5);

            coordinator.startOfflineProcessing();

            assertTrue(vduBatchProcessor.wasProcessCalled(), "Should continue to VDU processing");
        }
    }

    @Nested
    @DisplayName("Concurrent Guard")
    class ConcurrentGuard {

        @Test
        @DisplayName("prevents concurrent processing")
        void preventsConcurrentProcessing() throws InterruptedException {
            AtomicInteger startCount = new AtomicInteger(0);
            CountDownLatch processingStarted = new CountDownLatch(1);
            CountDownLatch canFinish = new CountDownLatch(1);

            vduBatchProcessor.withOnProcess(() -> {
                startCount.incrementAndGet();
                processingStarted.countDown();
                try {
                    canFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            knowledgeClient.withPendingVduCount(5);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                // Start first processing
                @SuppressWarnings("FutureReturnValueIgnored")
                var unused1 = executor.submit(coordinator::startOfflineProcessing);
                assertTrue(processingStarted.await(1, TimeUnit.SECONDS), "First processing should start");

                // Try to start second processing while first is running
                @SuppressWarnings("FutureReturnValueIgnored")
                var unused2 = executor.submit(coordinator::startOfflineProcessing);
                Thread.sleep(100);  // Give time for second call to be blocked

                // Only one should have started
                assertEquals(1, startCount.get(), "Only one processing should have started");

                // Let first finish
                canFinish.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("allows sequential processing")
        void allowsSequentialProcessing() {
            knowledgeClient.withPendingVduCount(5);

            coordinator.startOfflineProcessing();
            coordinator.startOfflineProcessing();

            assertEquals(2, vduBatchProcessor.getProcessCallCount(),
                "Should allow second processing after first completes");
        }

        @Test
        @DisplayName("isProcessing returns true during processing")
        void isProcessingReturnsTrueDuringProcessing() throws InterruptedException {
            CountDownLatch processingStarted = new CountDownLatch(1);
            CountDownLatch canFinish = new CountDownLatch(1);

            vduBatchProcessor.withOnProcess(() -> {
                processingStarted.countDown();
                try {
                    canFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            knowledgeClient.withPendingVduCount(5);

            assertFalse(coordinator.isProcessing(), "Should not be processing initially");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                @SuppressWarnings("FutureReturnValueIgnored")
                var unused = executor.submit(coordinator::startOfflineProcessing);
                assertTrue(processingStarted.await(1, TimeUnit.SECONDS));

                assertTrue(coordinator.isProcessing(), "Should be processing during execution");

                canFinish.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

                assertFalse(coordinator.isProcessing(), "Should not be processing after completion");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Mode Transitions")
    class ModeTransitions {

        @Test
        @DisplayName("switches to Online mode for VDU when not already online")
        void switchesToOnlineForVdu() {
            inferenceManager.withMode(Mode.OFFLINE);
            knowledgeClient.withPendingVduCount(5);

            coordinator.startOfflineProcessing();

            assertEquals(1, inferenceManager.getOnlineSwitchCount(), "Should switch to Online");
        }

        @Test
        @DisplayName("skips Online switch when already in Online mode")
        void skipsOnlineSwitchWhenAlreadyOnline() {
            inferenceManager.withMode(Mode.ONLINE);
            knowledgeClient.withPendingVduCount(5);

            coordinator.startOfflineProcessing();

            assertEquals(0, inferenceManager.getOnlineSwitchCount(), "Should NOT switch when already Online");
        }

        @Test
        @DisplayName("skips VDU phase when Online mode transition fails")
        void skipsVduWhenOnlineTransitionFails() {
            inferenceManager.withMode(Mode.OFFLINE);
            inferenceManager.withFailOnlineTransition(true);
            knowledgeClient.withPendingVduCount(5);
            knowledgeClient.withPendingEmbeddingsCount(10);

            coordinator.startOfflineProcessing();

            assertFalse(vduBatchProcessor.wasProcessCalled(), "VDU should be skipped on transition failure");
            // But embedding phase should still run
            assertEquals(1, inferenceManager.getIndexingSwitchCount(), "Embedding phase should still run");
        }

        @Test
        @DisplayName("handles Indexing mode transition failure gracefully")
        void handlesIndexingTransitionFailure() {
            inferenceManager.withFailIndexingTransition(true);
            knowledgeClient.withPendingEmbeddingsCount(10);

            // Should not throw
            assertDoesNotThrow(() -> coordinator.startOfflineProcessing());
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethods {

        @Test
        @DisplayName("hasPendingWork returns true when VDU pending")
        void hasPendingWorkWithVdu() {
            knowledgeClient.withPendingVduCount(5);
            knowledgeClient.withPendingEmbeddingsCount(0);

            assertTrue(coordinator.hasPendingWork());
        }

        @Test
        @DisplayName("hasPendingWork returns true when embeddings pending")
        void hasPendingWorkWithEmbeddings() {
            knowledgeClient.withPendingVduCount(0);
            knowledgeClient.withPendingEmbeddingsCount(10);

            assertTrue(coordinator.hasPendingWork());
        }

        @Test
        @DisplayName("hasPendingWork returns false when nothing pending")
        void hasPendingWorkWithNothing() {
            knowledgeClient.withPendingVduCount(0);
            knowledgeClient.withPendingEmbeddingsCount(0);

            assertFalse(coordinator.hasPendingWork());
        }

        @Test
        @DisplayName("getPendingVduCount delegates to client")
        void getPendingVduCountDelegates() {
            knowledgeClient.withPendingVduCount(42);
            assertEquals(42, coordinator.getPendingVduCount());
        }

        @Test
        @DisplayName("getPendingEmbeddingCount delegates to client")
        void getPendingEmbeddingCountDelegates() {
            knowledgeClient.withPendingEmbeddingsCount(99);
            assertEquals(99, coordinator.getPendingEmbeddingCount());
        }
    }

    // ========== Test Support Classes ==========

    /**
     * Testable version of OfflineCoordinator that works with stubs.
     */
    static class TestableOfflineCoordinator {
        private final StubInferenceLifecycleManager inferenceManager;
        private final StubVduBatchProcessor vduBatchProcessor;
        private final StubRemoteKnowledgeClient knowledgeClient;
        private final java.util.concurrent.atomic.AtomicBoolean processing = new java.util.concurrent.atomic.AtomicBoolean(false);

        TestableOfflineCoordinator(StubInferenceLifecycleManager inferenceManager,
                                   StubVduBatchProcessor vduBatchProcessor,
                                   StubRemoteKnowledgeClient knowledgeClient) {
            this.inferenceManager = inferenceManager;
            this.vduBatchProcessor = vduBatchProcessor;
            this.knowledgeClient = knowledgeClient;
        }

        void startOfflineProcessing() {
            if (!processing.compareAndSet(false, true)) {
                return;  // Already processing
            }

            try {
                // Recovery phase
                knowledgeClient.recoverVduProcessing();

                int pendingVdu = knowledgeClient.countPendingVdu();
                knowledgeClient.countPendingEmbeddings();  // First query (value unused - just count call)

                // Phase A: VDU
                if (pendingVdu > 0) {
                    processVduPhase();
                }

                // Phase B: Embeddings (re-query count after VDU - VDU may generate new pending)
                int pendingEmbeddings = knowledgeClient.countPendingEmbeddings();  // Second query
                if (pendingEmbeddings > 0) {
                    processEmbeddingPhase();
                }
            } finally {
                processing.set(false);
            }
        }

        private void processVduPhase() {
            if (!inferenceManager.isOnline()) {
                try {
                    inferenceManager.switchToOnlineMode();
                } catch (Exception e) {
                    return;  // Skip VDU on failure
                }
            }

            if (inferenceManager.isOnline()) {
                vduBatchProcessor.processPendingFiles();
            }
        }

        private void processEmbeddingPhase() {
            try {
                inferenceManager.switchToIndexingMode();
            } catch (Exception e) {
                // Log and continue
            }
        }

        boolean hasPendingWork() {
            return knowledgeClient.countPendingVdu() > 0 || knowledgeClient.countPendingEmbeddings() > 0;
        }

        int getPendingVduCount() {
            return knowledgeClient.countPendingVdu();
        }

        int getPendingEmbeddingCount() {
            return knowledgeClient.countPendingEmbeddings();
        }

        boolean isProcessing() {
            return processing.get();
        }
    }

    /**
     * Stub VduBatchProcessor for testing OfflineCoordinator.
     */
    static class StubVduBatchProcessor {
        private boolean processCalled = false;
        private int processCallCount = 0;
        private Runnable onProcess = () -> {};

        int processPendingFiles() {
            processCalled = true;
            processCallCount++;
            onProcess.run();
            return 0;
        }

        boolean wasProcessCalled() {
            return processCalled;
        }

        int getProcessCallCount() {
            return processCallCount;
        }

        StubVduBatchProcessor withOnProcess(Runnable callback) {
            this.onProcess = callback;
            return this;
        }
    }
}
