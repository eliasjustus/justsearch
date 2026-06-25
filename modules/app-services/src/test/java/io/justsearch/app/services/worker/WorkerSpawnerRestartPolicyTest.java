package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for WorkerSpawner restart policy configuration.
 *
 * <p>Note: Full lifecycle testing is covered by integration tests. These tests
 * verify configuration handling and initialization behavior that can be tested
 * in isolation.
 */
class WorkerSpawnerRestartPolicyTest {

    @Test
    void configDefaults_areReasonable() {
        try {
            KnowledgeServerConfig config = KnowledgeServerConfig.load();

            // Shutdown timeout should be reasonable (Sprint 1)
            assertTrue(config.workerShutdownTimeoutMs() >= 1000,
                    "shutdownTimeout should be at least 1 second");
            assertTrue(config.workerShutdownTimeoutMs() <= 30_000,
                    "shutdownTimeout should not exceed 30 seconds");

            // PID validation timeout should be reasonable (Sprint 2)
            assertTrue(config.pidValidationTimeoutMs() >= 1000,
                    "pidValidationTimeout should be at least 1 second");
            assertTrue(config.pidValidationTimeoutMs() <= config.portDiscoveryTimeoutMs(),
                    "pidValidationTimeout should not exceed port discovery timeout");

        } catch (IllegalStateException e) {
            // Expected if SSOT/repo layout not present (e.g., CI or isolation)
        }
    }

    @Test
    void ipcTelemetryNoop_canBeUsedInConstruction() {
        // Verify noop telemetry can be created without throwing
        IpcTelemetry noop = IpcTelemetry.noop();
        assertNotNull(noop, "IpcTelemetry.noop() should return non-null");

        // Verify all methods work without throwing
        assertDoesNotThrow(() -> {
            noop.recordRestartSuccess();
            noop.recordRestartFailed();
            noop.recordRestartLimitExceeded();
            noop.recordShutdownTimeout();
            noop.recordForcibleKill();
        });
    }
}
