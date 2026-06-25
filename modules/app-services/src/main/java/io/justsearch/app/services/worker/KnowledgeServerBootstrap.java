/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.app.util.EnergyState;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.telemetry.Telemetry;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap for the Knowledge Server client-side integration.
 *
 * <p>Manages the complete lifecycle:
 * <ol>
 *   <li>Loads configuration</li>
 *   <li>Opens signal bus</li>
 *   <li>Spawns worker process</li>
 *   <li>Connects gRPC client</li>
 *   <li>Provides health monitoring</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * KnowledgeServerBootstrap bootstrap = new KnowledgeServerBootstrap();
 * bootstrap.start();
 *
 * // Use the client
 * RemoteKnowledgeClient client = bootstrap.client();
 * client.search("query", 10);
 *
 * // On shutdown
 * bootstrap.close();
 * }</pre>
 */
public final class KnowledgeServerBootstrap implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeServerBootstrap.class);

    private final KnowledgeServerConfig config;
    private final Telemetry telemetry;
    private final WorkerCapability workerCapability;
    private final AtomicBoolean started = new AtomicBoolean(false);
    /**
     * Tempdoc 502 §4.4: generation counter replaces the never-reset boolean CAS.
     * Generation 0 = never initialized. Generation 1 = first connect (full init).
     * Generation 2+ = recovery (partial re-init: reindex + periodic sync only).
     */
    private final java.util.concurrent.atomic.AtomicLong initGeneration =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.locks.ReentrantLock initLock =
        new java.util.concurrent.locks.ReentrantLock();

    private MainSignalBus signalBus;
    private WorkerSpawner spawner;
    private RemoteKnowledgeClient client;

    /** Tempdoc 630: epoch-ms of the most recent OS-resume handled, for the "Catching up" notice. */
    private final java.util.concurrent.atomic.AtomicLong lastResumeEpochMs =
        new java.util.concurrent.atomic.AtomicLong(0);
    /** How long after a resume the transient "Catching up after sleep" notice stays up. */
    private static final long RESUME_NOTICE_WINDOW_MS = 30_000;
    private AppInstanceLock appLock;
    private IpcTelemetry ipcTelemetry;

    public KnowledgeServerBootstrap() {
        this(KnowledgeServerConfig.load(), new NoopTelemetry());
    }

    /** Tempdoc 627 Deliverable 10: production async-start ctor — loaded config + the injected shared capability. */
    public KnowledgeServerBootstrap(WorkerCapability workerCapability) {
        this(KnowledgeServerConfig.load(), new NoopTelemetry(), workerCapability);
    }

    public KnowledgeServerBootstrap(KnowledgeServerConfig config) {
        this(config, new NoopTelemetry());
    }

    public KnowledgeServerBootstrap(KnowledgeServerConfig config, Telemetry telemetry) {
        this(config, telemetry, new WorkerCapability());
    }

    /**
     * Tempdoc 627 Deliverable 10: inject a shared {@link WorkerCapability} so the Head's
     * {@code CapabilityGraph} and this supervisor drive ONE instance — eliminating the
     * {@code HeadAssembly.connectKnowledgeServer} mirror and its silent-drift bug class. The
     * no-arg / 2-arg ctors keep their own instance for tests and isolated launchers.
     */
    public KnowledgeServerBootstrap(
        KnowledgeServerConfig config, Telemetry telemetry, WorkerCapability workerCapability) {
        this.config = config;
        this.telemetry = telemetry != null ? telemetry : new NoopTelemetry();
        this.workerCapability = workerCapability != null ? workerCapability : new WorkerCapability();
    }

    /**
     * Starts the Knowledge Server integration.
     *
     * <p>Steps:
     * <ol>
     *   <li>Creates signal bus</li>
     *   <li>Starts worker spawner (which spawns the process)</li>
     *   <li>Connects gRPC client to discovered port</li>
     * </ol>
     *
     * @throws IOException if spawn or connection fails
     * @throws InterruptedException if startup is interrupted
     */
    public void start() throws IOException, InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("KnowledgeServerBootstrap already started");
        }

        workerCapability.transition(CapabilityHealth.PENDING, "Worker starting");
        log.info("Starting Knowledge Server integration...");

        try {
            // 0. Enforce single-instance semantics for this data directory.
            // This must happen before we spawn the Worker (and before any code tries to mutate jobs.db/index).
            //
            // Tempdoc 501 §3.7: when launched under HeadlessApp, the Head has already
            // acquired AppInstanceLock at startup. Re-acquiring in the same JVM on a
            // different FileChannel would throw OverlappingFileLockException, so we skip
            // when AppInstanceLock.isHeldByThisJvm(dataDir) reports true. Standalone
            // callers (tests, isolated launchers) take the acquire path as before.
            if (AppInstanceLock.isHeldByThisJvm(config.dataDir())) {
                log.debug(
                    "Skipping AppInstanceLock acquire: Head already holds it (tempdoc 501 §3.7)");
            } else {
                appLock = new AppInstanceLock(config.dataDir());
                appLock.acquire();
            }

            // 1. Create IPC telemetry for worker lifecycle instrumentation. Tempdoc 417 Phase 2e:
            // IpcTelemetry now wraps a typed catalog. Build it from LocalTelemetry's registry when
            // available; otherwise use the noop variant.
            ipcTelemetry =
                telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt
                    ? new IpcTelemetry(new IpcMetricCatalog(lt.registry()))
                    : IpcTelemetry.noop();

            // 2. Create signal bus
            signalBus = new MainSignalBus(config.signalFilePath());

            // 3. Create and start worker spawner (with IPC telemetry)
            spawner = new WorkerSpawner(config, signalBus, ipcTelemetry);
            // Tempdoc 627: bridge the spawner's supervision lifecycle to the worker capability so a
            // recovery (RECOVERING) or terminal give-up (DEGRADED + worker.restart_exhausted) is
            // legible on /api/health. Mirrors the Brain's InferenceCapabilityWiring mode→capability
            // bridge. onRecovered is intentionally a no-op: the next health poll confirms READY, so we
            // never claim healthy before it is verified.
            spawner.setSupervisionEvents(new SupervisionEvents() {
                @Override
                public void onRecovering(String reason, RecoveryContext ctx) {
                    // Tempdoc 627 (N2): park the forensic context before the transition so the
                    // capability-health bridge (a synchronous transition listener) attaches it to the
                    // worker.restart-attempted occurrence.
                    workerCapability.setRecoveryContext(ctx);
                    workerCapability.transition(CapabilityHealth.RECOVERING, reason);
                }

                @Override
                public void onGaveUp(String reason) {
                    workerCapability.transition(
                        CapabilityHealth.DEGRADED, LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code());
                }
            });
            int port = spawner.start();

            // 4. Create circuit breaker for gRPC failure handling
            GrpcCircuitBreaker circuitBreaker = new GrpcCircuitBreaker(ipcTelemetry);

            // 5. Create and connect client (with circuit breaker and telemetry)
            client = new RemoteKnowledgeClient(signalBus, config.deadlineMs(), config.maxRetries(), config.batchSize(), circuitBreaker, ipcTelemetry);
            client.connect(port);

            // 5.5. Validate that the connected port belongs to our spawned worker PID
            long expectedPid = spawner.getWorkerPid();
            validateWorkerPid(expectedPid, config.pidValidationTimeoutMs());

            // 5.6. Check for Head→Worker config divergence (tempdoc 329)
            checkConfigDivergence();

            // 6. Verify health with bounded retry (Tempdoc 374 alpha.23 R13-A defect #1).
            // Round-13 cycle 2 caught the worker still warming up Lucene SearcherManager.
            // Pre-fix: a single isHealthy() call straddled the warmup, transitioned to ERROR,
            // and steps 7-9 never ran. Post-fix: poll for up to healthCheckRetryBudgetMs.
            // If the budget elapses without success, KnowledgeServerHealthMonitor takes over.
            long retryBudgetMs = config.healthCheckRetryBudgetMs();
            long healthCheckStartMs = System.currentTimeMillis();
            boolean healthy = client.isHealthy();
            while (!healthy && (System.currentTimeMillis() - healthCheckStartMs) < retryBudgetMs) {
                Thread.sleep(1000);
                healthy = client.isHealthy();
            }
            long healthCheckElapsedMs = System.currentTimeMillis() - healthCheckStartMs;

            if (healthy) {
                workerCapability.transition(CapabilityHealth.READY, null);
                if (healthCheckElapsedMs >= 1000) {
                    log.info("Knowledge Server became healthy after {}ms of warmup polling on port {}",
                            healthCheckElapsedMs, port);
                } else {
                    log.info("Knowledge Server is READY on port {}", port);
                }
                completeReadyInitialization();
            } else {
                workerCapability.transition(CapabilityHealth.DEGRADED, workerDownReason("Health check failed after " + healthCheckElapsedMs + "ms"));
                log.warn("Knowledge Server health check failed after {}ms budget; auxiliary services not initialized — background monitor will retry",
                        healthCheckElapsedMs);
            }

        } catch (Exception e) {
            workerCapability.transition(CapabilityHealth.DEGRADED, workerDownReason("Start failed: " + e.getMessage()));
            log.error("Failed to start Knowledge Server integration", e);
            close();
            throw e;
        }
    }

    /**
     * Tempdoc 502 §4.4: generation-based initialization. First call (generation 0→1)
     * runs full initialization. Subsequent calls (recovery) re-run only catch-up steps
     * (reindex + periodic sync). Called from both the bootstrap success path and the
     * health-monitor recovery path.
     */
    private void completeReadyInitialization() {
        if (!initLock.tryLock()) {
            log.debug("completeReadyInitialization already running — skipping");
            return;
        }
        try {
            long prevGen = initGeneration.getAndIncrement();
            if (prevGen == 0) {
                // Tempdoc 626 §Axis-A — the redundant Head-side file watcher was removed; the
                // Worker-side watcher (registered via WatchRoot during the root walk) is the sole
                // event source, and the periodic sync + reindexPersistedRoots are the reconcile
                // backstop. File-event integration now lives entirely in the Worker process.
                client.reindexPersistedRoots();
                tryIngestHelpFiles(client, config);
                client.startPeriodicSync();
            } else {
                log.info("Worker recovery detected (generation {}); re-running catch-up initialization", prevGen + 1);
                client.reindexPersistedRoots();
                client.startPeriodicSync();
            }
        } finally {
            initLock.unlock();
        }
    }

    /**
     * Tempdoc 374 alpha.23 R13-A defect #2: package-private hook called by
     * {@link KnowledgeServerHealthMonitor} when the worker recovers from
     * non-READY to READY. Delegates to the same idempotent helper used by
     * the bootstrap success path.
     */
    void completeReadyInitializationFromMonitor() {
        completeReadyInitialization();
        tryIngestHelpFiles(client, config);
    }

    /**
     * Returns true if the Knowledge Server is ready.
     * Delegates to {@link WorkerCapability#available()}.
     */
    public boolean isReady() {
        return workerCapability.available();
    }

    public WorkerCapability workerCapability() {
        return workerCapability;
    }

    /**
     * Validates that the connected gRPC port belongs to the expected worker PID.
     *
     * <p>This prevents connecting to a stale/zombie process that wrote its port
     * between zeroPort() and the new worker starting.
     *
     * @param expectedPid the PID of the spawned worker process
     * @param timeoutMs maximum time to retry PID validation
     * @throws IllegalStateException if PID validation fails after timeout
     */
    private void validateWorkerPid(long expectedPid, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int retryCount = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                HealthCheckResponse response = client.getHealthCheck();
                long actualPid = response.getPid();

                if (actualPid == expectedPid) {
                    log.info("Worker PID validated: {}", actualPid);
                    return;
                }

                // PID mismatch - likely stale port from zombie process
                log.warn("PID mismatch: expected {}, got {} (retry {})", expectedPid, actualPid, ++retryCount);
                ipcTelemetry.recordPidMismatch();

                // Zero the stale port and wait for new worker to write its port
                signalBus.zeroPort();
                Thread.sleep(100);

                // Use remaining time, capped at 1 second per attempt
                long remaining = deadline - System.currentTimeMillis();
                long awaitTimeout = Math.min(1000, Math.max(100, remaining / 2));
                int newPort = signalBus.awaitPort(awaitTimeout, 100);
                client.connect(newPort);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.debug("PID validation attempt failed: {}", e.getMessage());
                Thread.sleep(100);
            }
        }

        throw new IllegalStateException(
                "PID validation timeout after " + timeoutMs + "ms: expected PID " + expectedPid);
    }

    /**
     * Compares critical config values between Head and Worker, logging WARN on divergence.
     *
     * <p>This turns silent misconfiguration (tempdoc 312 item 20) into a visible signal.
     * Best-effort: failures are logged but do not block startup.
     */
    private void checkConfigDivergence() {
        try {
            HealthCheckResponse response = client.getHealthCheck();
            Map<String, String> workerConfig = response.getEffectiveConfigMap();
            if (workerConfig.isEmpty()) {
                log.debug("Worker did not report effective config (older version?)");
                return;
            }

            int mismatches = 0;
            for (EnvRegistry key : EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS) {
                String headValue = key.get().orElse("");
                String workerValue = workerConfig.getOrDefault(key.sysProp(), "");
                if (!headValue.equals(workerValue)) {
                    log.warn("Config divergence [{}]: head='{}', worker='{}'",
                            key.sysProp(), headValue, workerValue);
                    mismatches++;
                }
            }
            if (mismatches == 0) {
                log.info("Head→Worker config check passed ({} keys verified)",
                        EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS.size());
            } else {
                log.warn("Head→Worker config divergence detected: {} key(s) differ. "
                        + "Check env var / system property forwarding.", mismatches);
            }
        } catch (Exception e) {
            log.debug("Config divergence check failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Returns the gRPC client for Knowledge Server operations.
     *
     * @throws IllegalStateException if not started or not ready
     */
    public RemoteKnowledgeClient client() {
        if (client == null) {
            throw new IllegalStateException("Knowledge Server not started");
        }
        return client;
    }

    /**
     * Returns the worker spawner for process management.
     */
    public WorkerSpawner spawner() {
        return spawner;
    }

    /**
     * Returns the signal bus for inter-process coordination.
     *
     * <p>Used by InferenceLifecycleManager to broadcast GPU status changes
     * to the Worker process.
     *
     * @return the MainSignalBus, or null if not started
     */
    public MainSignalBus signalBus() {
        return signalBus;
    }

    /**
     * The latest polled OS energy-intent (tempdoc 630), for the /api/status "Paused — saving energy"
     * Queue-card state. Null-safe: returns {@link EnergyState#unknown()} before the spawner exists.
     */
    public EnergyState energyState() {
        WorkerSpawner s = spawner;
        return s != null ? s.energyState() : EnergyState.unknown();
    }

    /**
     * Marks an OS resume just handled (tempdoc 630). Called by the health monitor's
     * post-resume eager re-validation so /api/status can surface a brief "Catching up after sleep"
     * transient while the reconcile runs.
     */
    public void markResumed(long nowEpochMs) {
        lastResumeEpochMs.set(nowEpochMs);
    }

    /**
     * Whether an OS resume was handled within the recent notice window (tempdoc 630) — drives the
     * transient "Catching up after sleep" verdict, which auto-clears once the window elapses.
     */
    public boolean recentlyResumed(long nowEpochMs) {
        long last = lastResumeEpochMs.get();
        return last > 0 && (nowEpochMs - last) < RESUME_NOTICE_WINDOW_MS;
    }

    /**
     * Signals user activity for breath holding.
     */
    public void signalUserActivity() {
        if (spawner != null) {
            spawner.signalUserActivity();
        }
    }

    /**
     * Performs a health check and updates state.
     *
     * @return true if healthy
     */
    /**
     * tempdoc 628 Stage D-part2: enrich a worker-down reason with the corruption cause when the worker
     * exited fatally because the index was corrupt and could not be auto-recovered (the opt-in
     * FAIL_CLOSED policy — G2's self-heal default keeps it alive). Lets the Head surface "worker down:
     * the index is corrupt — rebuild to recover" instead of a generic/silent restart-loop. The dying
     * worker stamps {@link io.justsearch.ipc.WorkerFatalReasonMarker}; this reads + clears it.
     */
    private String workerDownReason(String generic) {
        String fatal = io.justsearch.ipc.WorkerFatalReasonMarker.readAndClear(config.dataDir());
        if (io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_CORRUPT.equals(fatal)) {
            return "The search index is corrupt and the worker could not auto-recover under the"
                + " fail-closed policy. Set index.recovery.policy=BACKUP_REBUILD (or remove the index"
                + " directory) to rebuild it from your files.";
        }
        return generic;
    }

    public boolean checkHealth() {
        if (client == null) {
            return false;
        }
        boolean healthy = client.isHealthy();
        // Tempdoc 627: feed each poll into the spawner's hang detector. A sustained-unhealthy streak on
        // a still-alive worker (the "liveness" signal) triggers a budgeted graceful restart — closing
        // the Worker's observation→actuation loop. This is the only wiring the health monitor needs.
        if (spawner != null) {
            spawner.recordHealthResult(healthy);
        }
        CapabilityHealth current = workerCapability.health();
        if (healthy && current != CapabilityHealth.READY) {
            workerCapability.transition(CapabilityHealth.READY, null);
            log.info("Knowledge Server recovered to READY state");
        } else if (!healthy && current == CapabilityHealth.READY) {
            workerCapability.transition(CapabilityHealth.DEGRADED, workerDownReason("Health check failed"));
            log.warn("Knowledge Server health check failed");
        }
        return healthy;
    }

    @Override
    public void close() {
        log.info("Shutting down Knowledge Server integration...");

        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing client", e);
            }
            client = null;
        }

        if (spawner != null) {
            try {
                spawner.close();
            } catch (Exception e) {
                log.warn("Error closing spawner", e);
            }
            spawner = null;
        }

        // Signal bus is closed by spawner, but ensure cleanup
        signalBus = null;

        // Release app lock last (after we have stopped all components that might touch the data dir).
        if (appLock != null) {
            try {
                appLock.close();
            } catch (Exception e) {
                log.warn("Error releasing app lock", e);
            }
            appLock = null;
        }

        workerCapability.transition(CapabilityHealth.OFFLINE, "Worker shut down");
        started.set(false);
        log.info("Knowledge Server integration shutdown complete");
    }

    /** Version stamp for built-in help files. Bump when help content changes. */
    private static final String HELP_FILES_VERSION = "v2";

    /** Collection tag for built-in help documents. */
    private static final String HELP_COLLECTION = "justsearch-help";

    /**
     * Auto-ingests built-in help files if not already done for this version.
     *
     * <p>Uses a marker file in the data directory to track which version of
     * help files has been ingested, avoiding unnecessary re-ingestion on every startup.
     */
    // Package-private for unit tests (KnowledgeServerBootstrapEvalModeTest).
    // Not intended as a stable API surface.
    void tryIngestHelpFiles(RemoteKnowledgeClient client, KnowledgeServerConfig config) {
        try {
            // Skip help-file auto-ingest in eval mode so a "fresh" index truly starts empty.
            // The 5 bundled help docs would otherwise pollute baseline measurements
            // (precision, doc counts) with non-eval content. `justsearch.eval.mode` is the
            // same flag that gates `/api/debug/reset-index` (LocalApiServer) and is set by
            // the `runHeadlessEval` Gradle task.
            if (Boolean.getBoolean("justsearch.eval.mode")) {
                log.info("Skipping help-file auto-ingest (eval mode)");
                return;
            }

            Path marker = config.dataDir().resolve(".help-ingested-version");

            // Check if already ingested for this version
            if (Files.exists(marker)) {
                String ingested = Files.readString(marker).trim();
                if (HELP_FILES_VERSION.equals(ingested)) {
                    log.debug("Help files already ingested (version {})", ingested);
                    return;
                }
            }

            // Resolve help directory
            Path helpDir = resolveHelpDir(config);
            if (helpDir == null) {
                log.debug("Help files directory not found, skipping auto-ingestion");
                return;
            }

            // Collect .md files
            List<Path> helpFiles;
            try (Stream<Path> walk = Files.walk(helpDir, 1)) {
                helpFiles = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .toList();
            }

            if (helpFiles.isEmpty()) {
                log.debug("No help files found in {}", helpDir);
                return;
            }

            // Ingest with collection tag
            client.submitBatch(helpFiles, true, HELP_COLLECTION);
            Files.writeString(marker, HELP_FILES_VERSION);
            log.info("Ingested {} built-in help files (collection={})", helpFiles.size(), HELP_COLLECTION);

        } catch (Exception e) {
            // Non-fatal: help file ingestion failure should not block startup
            log.warn("Failed to ingest help files: {}", e.getMessage());
            log.debug("Failed to ingest help files (stack trace)", e);
        }
    }

    /**
     * Resolves the help files directory from the config's working directory.
     *
     * <p>The working directory is resolved by {@link KnowledgeServerConfig} using the
     * SSOT discovery logic, so this works in both development and production.
     */
    private Path resolveHelpDir(KnowledgeServerConfig config) {
        Path helpDir = config.workingDirectory().resolve("SSOT").resolve("docs").resolve("help");
        if (Files.isDirectory(helpDir)) {
            return helpDir;
        }
        return null;
    }

    /**
     * No-op telemetry marker for when real telemetry is not needed. Tempdoc 417 Phase 3e:
     * Telemetry is now an empty marker interface; no methods to override.
     */
    private static final class NoopTelemetry implements Telemetry {
        @Override
        public void close() {}
    }
}
