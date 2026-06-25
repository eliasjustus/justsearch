/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.PlatformPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the Knowledge Server client.
 *
 * <p>Handles Dev/Prod profile detection and path resolution.
 *
 * <p>Uses {@link JustSearchConfigurationLoader} for centralized SSOT discovery
 * instead of duplicating the filesystem traversal logic.
 */
public record KnowledgeServerConfig(
        boolean isProduction,
        Path dataDir,
        Path libDir,
        Path workingDirectory,
        Path workerLibDir,
        Path signalFilePath,
        long deadlineMs,
        long portDiscoveryTimeoutMs,
        int maxRetries,
        String workerHeapSize,
        long workerShutdownTimeoutMs,
        long pidValidationTimeoutMs,
        long stabilityWindowMs,
        int batchSize,
        long healthCheckRetryBudgetMs) {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServerConfig.class);

    private static final long DEFAULT_DEADLINE_MS = 5000;
    private static final long DEFAULT_PORT_DISCOVERY_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final String DEFAULT_WORKER_HEAP = "512m";
    private static final long DEFAULT_WORKER_SHUTDOWN_TIMEOUT_MS = 5000;
    private static final long DEFAULT_PID_VALIDATION_TIMEOUT_MS = 5000;
    /** Default stability window: 5 minutes. Worker must run this long to reset restart counter. */
    private static final long DEFAULT_STABILITY_WINDOW_MS = 300_000;
    /** Default batch size for file submissions. Must be <= Worker MAX_BATCH_SIZE (10,000). */
    private static final int DEFAULT_BATCH_SIZE = 5000;
    /**
     * Tempdoc 374 alpha.23 R13-A defect #1: 30-second budget for the bootstrap
     * health-check retry loop. Round-13 cycle 2 caught the worker mid Lucene
     * SearcherManager warmup (~3s observed); 30s gives ~10x margin without
     * meaningfully extending cold-start when the worker wins the race.
     */
    private static final long DEFAULT_HEALTH_CHECK_RETRY_BUDGET_MS = 30_000;

    /**
     * Loads configuration from environment and system properties.
     */
    public static KnowledgeServerConfig load() {
        boolean isProd = detectProductionMode();
        Path dataDir = resolveDataDir();
        Path libDir = resolveLibDir();
        Path workingDir = resolveWorkingDirectory();
        Path workerLibDir = resolveWorkerLibDir(libDir, workingDir);
        Path signalFile = dataDir.resolve("worker_signal.lock");

        long deadline = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_DEADLINE_MS", "justsearch.worker.deadline_ms"),
                DEFAULT_DEADLINE_MS);
        long portTimeout = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_PORT_TIMEOUT_MS", "justsearch.worker.port_timeout_ms"),
                DEFAULT_PORT_DISCOVERY_TIMEOUT_MS);
        int maxRetries = parseInt(
                envOrProperty("JUSTSEARCH_WORKER_MAX_RETRIES", "justsearch.worker.max_retries"),
                DEFAULT_MAX_RETRIES);
        String workerHeap = envOrProperty("JUSTSEARCH_WORKER_HEAP", "justsearch.worker.heap");
        if (workerHeap == null || workerHeap.isBlank()) {
            workerHeap = DEFAULT_WORKER_HEAP;
        }
        long shutdownTimeout = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_SHUTDOWN_TIMEOUT_MS", "justsearch.worker.shutdown_timeout_ms"),
                DEFAULT_WORKER_SHUTDOWN_TIMEOUT_MS);
        long pidValidationTimeout = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_PID_VALIDATION_TIMEOUT_MS", "justsearch.worker.pid_validation_timeout_ms"),
                DEFAULT_PID_VALIDATION_TIMEOUT_MS);
        long stabilityWindow = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_STABILITY_WINDOW_MS", "justsearch.worker.stability_window_ms"),
                DEFAULT_STABILITY_WINDOW_MS);
        int batchSize = parseInt(
                envOrProperty("JUSTSEARCH_WORKER_BATCH_SIZE", "justsearch.worker.batch_size"),
                DEFAULT_BATCH_SIZE);
        long healthCheckRetryBudget = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_HEALTH_RETRY_BUDGET_MS", "justsearch.worker.health_retry_budget_ms"),
                DEFAULT_HEALTH_CHECK_RETRY_BUDGET_MS);

        KnowledgeServerConfig config = new KnowledgeServerConfig(
                isProd,
                dataDir,
                libDir,
                workingDir,
                workerLibDir,
                signalFile,
                deadline,
                portTimeout,
                maxRetries,
                workerHeap,
                shutdownTimeout,
                pidValidationTimeout,
                stabilityWindow,
                batchSize,
                healthCheckRetryBudget);

        log.info("Loaded KnowledgeServerConfig: production={}, dataDir={}, workerLibDir={}",
                isProd, dataDir, workerLibDir);

        return config;
    }

    /**
     * Detects if running in production mode.
     *
     * <p>Production indicators:
     * <ul>
     *   <li>System property "justsearch.prod" = "true"</li>
     *   <li>Running from a bundled JRE (java.home is within app directory)</li>
     *   <li>Environment variable "JUSTSEARCH_PROD" = "true"</li>
     * </ul>
     */
    private static boolean detectProductionMode() {
        // Explicit production flag
        String prodFlag = envOrProperty("JUSTSEARCH_PROD", "justsearch.prod");
        if ("true".equalsIgnoreCase(prodFlag)) {
            return true;
        }
        if ("false".equalsIgnoreCase(prodFlag)) {
            return false;
        }

        // Check if running from bundled runtime
        String javaHome = System.getProperty("java.home", "");
        Path javaHomePath = Path.of(javaHome).toAbsolutePath().normalize();

        // In production, java.home is typically inside the app's dist/runtime directory
        Path distRuntime = resolveLibDir().getParent().resolve("runtime");
        if (Files.exists(distRuntime) && javaHomePath.startsWith(distRuntime.toAbsolutePath())) {
            return true;
        }

        return false;
    }

    /**
     * Resolves data directory using centralized PlatformPaths.
     * See {@link PlatformPaths#resolveDataDir()} for resolution order.
     */
    private static Path resolveDataDir() {
        return PlatformPaths.resolveDataDir().toAbsolutePath();
    }

    private static Path resolveLibDir() {
        // Production/bundled layout: lib is sibling to runtime in the dist folder.
        // Always checked — the bundled layout may exist even when prod=false (e.g.,
        // alpha builds with CORS relaxed for browser testing), so the `isProd` flag
        // that callers used to pass in never changed behaviour.
        String javaHome = System.getProperty("java.home", "");
        Path runtimePath = Path.of(javaHome).getParent();
        if (runtimePath != null) {
            Path libPath = runtimePath.resolve("lib");
            if (Files.isDirectory(libPath)) {
                return libPath.toAbsolutePath();
            }
        }

        // Development: use build output
        Path repoRoot = resolveRepoRoot();
        return repoRoot.resolve("modules").resolve("dist").resolve("build").resolve("libs");
    }

    private static Path resolveWorkingDirectory() {
        // Use repo root in dev, data dir in prod
        String configured = envOrProperty("JUSTSEARCH_WORKING_DIR", "justsearch.working_dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }

        try {
            return resolveRepoRoot();
        } catch (IllegalStateException e) {
            // Fallback to current directory
            return Path.of("").toAbsolutePath();
        }
    }

    private static Path resolveWorkerLibDir(Path libDir, Path workingDir) {
        // 1. Explicit override via env/sysprop (value is a directory path)
        String configured = envOrProperty("JUSTSEARCH_WORKER_LIB_DIR", "justsearch.worker.lib.dir");
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured);
            if (Files.isDirectory(configuredPath)) {
                return configuredPath.toAbsolutePath();
            }
        }

        // 2. Production/bundled layout: lib/worker/ subdirectory alongside Head's lib/
        // Always checked — the bundled layout may exist even when prod=false (e.g.,
        // alpha builds with CORS relaxed for browser testing), so the `isProd` flag
        // that callers used to pass in never changed behaviour.
        Path prodWorkerLib = libDir.resolve("worker");
        if (Files.isDirectory(prodWorkerLib)) {
            return prodWorkerLib.toAbsolutePath();
        }

        // 3. Development: installDist output
        Path devWorkerLib = workingDir
                .resolve("modules")
                .resolve("indexer-worker")
                .resolve("build")
                .resolve("install")
                .resolve("indexer-worker")
                .resolve("lib");

        if (Files.isDirectory(devWorkerLib)) {
            return devWorkerLib.toAbsolutePath();
        }

        throw new IllegalStateException(
                "Worker lib directory not found. Build with: ./gradlew :modules:indexer-worker:installDist");
    }

    /**
     * Resolves the repository root using the centralized configuration loader.
     *
     * <p>This delegates to {@link JustSearchConfigurationLoader} to avoid duplicating
     * the SSOT discovery logic across the codebase.
     *
     * @return the repository root path
     * @throws IllegalStateException if the repo root cannot be found
     */
    private static Path resolveRepoRoot() {
        JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
        Optional<Path> ssotRoot = loader.ssotRoot();
        if (ssotRoot.isPresent()) {
            // SSOT root is inside the repo, so parent is repo root
            return ssotRoot.get().getParent();
        }
        // Fallback: traverse up from CWD (legacy behavior for edge cases)
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("SSOT"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found (missing SSOT directory)");
    }

    private static String envOrProperty(String envKey, String propKey) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return System.getProperty(propKey);
    }

    /**
     * Returns whether GPU acceleration is allowed by policy.
     *
     * <p>This centralizes the policy check so other classes don't need direct env/sysprop access.
     * Defaults to true if not explicitly configured.
     *
     * @return true if GPU acceleration is allowed by policy
     */
    public static boolean isGpuAccelerationPolicyEnabled() {
        String value = envOrProperty("JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED",
            "policy.gpu_acceleration_enabled");
        // Default to true if not set
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
