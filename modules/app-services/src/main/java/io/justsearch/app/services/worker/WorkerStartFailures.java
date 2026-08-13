/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the Head classifies — and recovers from — a failed Knowledge Server start.
 *
 * <p>Boot-time worker start has two failure shapes that used to be indistinguishable at the call
 * site, so both produced the same terminal outcome (worker capability pinned DEGRADED for the life
 * of the process) and the same operator hint ("build the indexer-worker module"):
 *
 * <ul>
 *   <li><b>Unstartable worker</b> — the process could not be launched at all. Terminal; the hint is
 *       correct.</li>
 *   <li><b>Post-spawn timing failure</b> — the process is alive and possibly healthy, but a
 *       confirming step ran out of budget (see {@link PidValidationTimeoutException}). Retryable;
 *       the build hint is wrong and misleads the operator.</li>
 * </ul>
 */
public final class WorkerStartFailures {
    private static final Logger log = LoggerFactory.getLogger(WorkerStartFailures.class);

    /** Bound on cause-chain walks, so a self-referential or cyclic chain cannot spin. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** A single start attempt — {@code KnowledgeServerBootstrap::start} in production. */
    @FunctionalInterface
    public interface StartAttempt {
        void start() throws IOException, InterruptedException;
    }

    private WorkerStartFailures() {}

    /**
     * Whether a failed start is a post-spawn timing failure worth retrying.
     *
     * <p>Deliberately narrow: only the confirmed case (PID validation exhausting its window while
     * the spawned worker is alive). Port-discovery timeouts are NOT included — a worker that never
     * publishes a port within its own 15s window is as likely broken as slow, and retrying it would
     * add ~30s to boot in the broken case for no established benefit.
     */
    public static boolean isTransient(Throwable failure) {
        return chainHasType(failure, PidValidationTimeoutException.class);
    }

    /**
     * Whether the failure plausibly means the worker binary is missing or cannot be launched.
     *
     * <p>{@link IOException} is the launch-side signal: {@code ProcessBuilder.start()} raises it when
     * the java binary or working directory cannot be resolved.
     */
    public static boolean isLikelyUnstartableWorker(Throwable failure) {
        return chainHasType(failure, IOException.class);
    }

    /**
     * The operator "To fix:" line for a failed start, routed by cause.
     *
     * <p>The unconditional "ensure the indexer-worker module is built" hint was actively misleading
     * for timing failures — the module was built and the worker had booted fine.
     */
    public static String operatorHint(Throwable failure) {
        if (isLikelyUnstartableWorker(failure)) {
            return "Ensure the indexer-worker module is built"
                + " (gradlew :modules:indexer-worker:installDist)";
        }
        if (isTransient(failure)) {
            return "The worker process started but did not confirm its identity within the"
                + " validation window — usually transient machine load. Check <dataDir>/logs/worker.log"
                + " for the worker's own startup outcome; if the worker booted cleanly, raise"
                + " justsearch.worker.pid_validation_timeout_ms.";
        }
        return "Check <dataDir>/logs/worker.log for the worker's own startup outcome. If the worker"
            + " never started, ensure the indexer-worker module is built"
            + " (gradlew :modules:indexer-worker:installDist).";
    }

    /**
     * Runs {@code attempt} up to {@code maxAttempts} times, retrying only {@linkplain
     * #isTransient(Throwable) transient} failures.
     *
     * <p>A non-transient failure is rethrown from the first attempt, so genuinely broken
     * installations still fail with their original error and without added boot latency. An
     * interrupt is never retried.
     *
     * @param attempt the start action; each retry re-runs it from scratch
     * @param maxAttempts total attempts including the first (must be >= 1)
     * @param backoffMs pause between attempts; 0 disables the pause
     */
    public static void startWithRetry(StartAttempt attempt, int maxAttempts, long backoffMs)
            throws IOException, InterruptedException {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        for (int n = 1; ; n++) {
            try {
                attempt.start();
                if (n > 1) {
                    log.info("Knowledge Server start succeeded on attempt {}/{}", n, maxAttempts);
                }
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                if (n >= maxAttempts || !isTransient(e)) {
                    throw e;
                }
                log.warn(
                    "Knowledge Server start attempt {}/{} hit a transient timing failure ({});"
                        + " retrying in {}ms",
                    n, maxAttempts, e.toString(), backoffMs);
                if (backoffMs > 0) {
                    Thread.sleep(backoffMs);
                }
            }
        }
    }

    private static boolean chainHasType(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
