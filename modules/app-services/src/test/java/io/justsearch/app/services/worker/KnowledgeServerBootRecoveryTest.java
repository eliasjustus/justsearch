/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot-recovery ARC, driven through a real {@link KnowledgeServerBootstrap} and a real
 * {@link KnowledgeServerHealthMonitor} (tempdoc 825 §D4, middle rung of the ladder).
 *
 * <p>Fixture shape borrowed from {@code KnowledgeServerBootstrapRestartabilityTest}: every path
 * points at an empty temp dir, so the spawned worker JVM cannot find its main class and dies before
 * publishing a port. Every attempt therefore FAILS — which is precisely what makes the budget, the
 * narration and the terminal state observable here. Convergence (the attempt that succeeds) needs a
 * real worker and lives one rung up, in the isolated-backend integration leg with the countdown
 * fault injector.
 *
 * <p>{@code tick()} is called directly rather than via {@code start()}: the schedule is the
 * executor's business, the decision is the arm's, and driving it explicitly keeps the test bounded
 * and deterministic instead of sleeping past a poll interval.
 */
@DisplayName("boot recovery: the arc, over a real bootstrap")
final class KnowledgeServerBootRecoveryTest {

  /** Mirrors {@code KnowledgeServerBootstrapRestartabilityTest.configFor}, with a tighter budget. */
  private static KnowledgeServerConfig configFor(Path dir) {
    return new KnowledgeServerConfig(
        false, dir, dir, dir, dir, dir.resolve("worker_signal.lock"),
        5_000L, 1_000L, 3, "256m", 1_000L, 1_000L, 300_000L, 100, 0L, 0);
  }

  /** No backoff: the arc's attempt schedule is pinned by the pure decision test, not by waiting. */
  private static final BootRecoveryPolicy NO_WAIT = new BootRecoveryPolicy(2, 0, 0);

  private static final String SPAWN_FAILED = LifecycleReasonCode.WORKER_SPAWN_FAILED.code();
  private static final String RECOVERING = LifecycleReasonCode.WORKER_RECOVERING.code();
  private static final String RESTART_EXHAUSTED = LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code();
  private static final String RECOVERY_EXHAUSTED =
      LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code();

  /** A bootstrap in the post-boot bricked state: start attempted, failed, capability pinned. */
  private static KnowledgeServerBootstrap bricked(Path tempDir) {
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));
    assertThrows(Exception.class, () -> bootstrap.startWithRetry(1, 0));
    assertFalse(bootstrap.hasClient(), "the fixture must leave no client bound");
    assertEquals(
        SPAWN_FAILED,
        bootstrap.workerCapability().pendingReason(),
        "precondition: the boot failure pins worker.spawn.failed (the 821 §O.4 state)");
    return bootstrap;
  }

  private static List<String> recordTransitions(KnowledgeServerBootstrap bootstrap) {
    WorkerCapability cap = bootstrap.workerCapability();
    List<String> seen = new ArrayList<>();
    cap.addListener((prev, next) -> seen.add(next + "/" + cap.pendingReason()));
    return seen;
  }

  @Test
  @Timeout(180)
  @DisplayName("the pinned state is re-attempted, and the recovery narration is NOT swallowed")
  void firstTickReAttemptsAndNarratesRecovering(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);

    monitor.tick();

    // The ReasonRetention trap (825 §D2 mechanism 4): worker.recovering is TRANSIENT and the held
    // worker.spawn.failed is a FAULT, so without the recovery-supersedes arm this write is silently
    // dropped and pendingReason — published raw on the runtime manifest and the 503 body — keeps
    // saying "failed to start" while the Head is actively re-attempting.
    assertEquals(
        RECOVERING,
        bootstrap.workerCapability().pendingReason(),
        "the in-flight recovery must reach the reason slot, not be latched out by the pin");
    assertEquals(CapabilityHealth.RECOVERING, bootstrap.workerCapability().health());
  }

  @Test
  @Timeout(180)
  @DisplayName("a failing arc gives up exactly once, on the terminal code, after the budget")
  void arcGivesUpOnceAfterTheBudget(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
    List<String> seen = recordTransitions(bootstrap);

    for (int i = 0; i < NO_WAIT.maxAttempts(); i++) {
      monitor.tick();
    }
    assertFalse(
        seen.stream().anyMatch(t -> t.contains(RECOVERY_EXHAUSTED)),
        "the terminal code must not land while attempts remain: " + seen);

    monitor.tick(); // budget spent
    assertEquals(
        RECOVERY_EXHAUSTED,
        bootstrap.workerCapability().pendingReason(),
        "a spent boot-recovery budget is its own terminal code, not worker.spawn.failed");
    assertEquals(CapabilityHealth.DEGRADED, bootstrap.workerCapability().health());

    long terminalWrites = seen.stream().filter(t -> t.contains(RECOVERY_EXHAUSTED)).count();
    assertEquals(1, terminalWrites, "narrated exactly once: " + seen);

    // Further ticks are silent — no re-narration, no further spawns.
    int transitionsAtGiveUp = seen.size();
    monitor.tick();
    monitor.tick();
    assertEquals(transitionsAtGiveUp, seen.size(), "a terminal arc stays quiet: " + seen);
  }

  @Test
  @Timeout(180)
  @DisplayName("no flapping: a whole failing arc narrates no OFFLINE and no per-attempt spawn-failed")
  void arcDoesNotFlap(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
    List<String> seen = recordTransitions(bootstrap);

    for (int i = 0; i <= NO_WAIT.maxAttempts(); i++) {
      monitor.tick();
    }

    assertFalse(
        seen.stream().anyMatch(t -> t.startsWith("OFFLINE")),
        "closeForUpgrade between attempts must not narrate an orderly shutdown: " + seen);
    assertFalse(
        seen.stream().anyMatch(t -> t.contains(SPAWN_FAILED)),
        "the per-attempt spawn-failed pin belongs to the arc's owner, not to each cycle: " + seen);
    // One RECOVERING (the arc, entered once and held) + one terminal DEGRADED. Anything more is the
    // flap the acceptance criterion forbids: with N attempts, an unsuppressed arc would emit
    // RECOVERING/PENDING/DEGRADED/OFFLINE per cycle.
    assertEquals(
        2,
        seen.size(),
        "an arc of " + NO_WAIT.maxAttempts() + " attempts must narrate 2 transitions, got: " + seen);
    assertTrue(seen.get(0).startsWith("RECOVERING"), "first: " + seen);
    assertTrue(seen.get(1).startsWith("DEGRADED"), "last: " + seen);
  }

  @Test
  @Timeout(180)
  @DisplayName("VETO: a held worker.restart_exhausted is never superseded — no attempt, no overwrite")
  void restartExhaustedIsNeverSuperseded(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    // Supervision's terminal verdict lands (this is what SupervisionEvents.onGaveUp writes).
    bootstrap
        .workerCapability()
        .transition(CapabilityHealth.DEGRADED, RESTART_EXHAUSTED, "restart budget exhausted");
    var monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);
    List<String> seen = recordTransitions(bootstrap);

    monitor.tick();
    monitor.tick();

    assertEquals(
        RESTART_EXHAUSTED,
        bootstrap.workerCapability().pendingReason(),
        "boot recovery must not overwrite supervision's terminal verdict with its own");
    assertTrue(seen.isEmpty(), "a vetoed arc narrates nothing at all: " + seen);
  }

  @Test
  @Timeout(180)
  @DisplayName("the manual path shares the authority: it is accepted, then vetoed once terminal")
  void manualRequestSharesTheSameAuthority(@TempDir Path tempDir) {
    var bootstrap = bricked(tempDir);
    var monitor =
        new KnowledgeServerHealthMonitor(bootstrap, 10_000, System::currentTimeMillis, NO_WAIT);

    assertEquals(
        WorkerRecoveryAuthority.Verdict.ACCEPTED,
        monitor.requestRecoveryNow(),
        "POST /api/worker/restart in the null-worker state must reach the recovery loop");

    // Drive the budget to its end through the periodic arm, then ask again.
    for (int i = 0; i <= NO_WAIT.maxAttempts(); i++) {
      monitor.tick();
    }
    assertEquals(
        RECOVERY_EXHAUSTED,
        bootstrap.workerCapability().pendingReason(),
        "precondition: the arc is terminal");
    assertEquals(
        WorkerRecoveryAuthority.Verdict.EXHAUSTED,
        monitor.requestRecoveryNow(),
        "the manual path may not out-spend the declared budget");
  }

  @Test
  @Timeout(180)
  @DisplayName("close() resets initGeneration, so a recovered worker still gets its help files")
  void closeResetsInitGeneration(@TempDir Path tempDir) throws Exception {
    // #439 review finding E / charter item 4: initGeneration outlived the connection it described,
    // so the first successful start AFTER any close() took the generation>=1 "recovery" branch of
    // completeReadyInitialization and skipped tryIngestHelpFiles for the rest of the process. Latent
    // before 825 (nothing re-started a closed bootstrap); LIVE the moment a recovery loop exists.
    //
    // Read reflectively because the counter has no consumer that would justify a public accessor,
    // and asserting it through completeReadyInitialization would need a live gRPC client — the exact
    // substrate this rung of the ladder is defined to exclude.
    var bootstrap = new KnowledgeServerBootstrap(configFor(tempDir));
    var field = KnowledgeServerBootstrap.class.getDeclaredField("initGeneration");
    field.setAccessible(true);
    var generation = (java.util.concurrent.atomic.AtomicLong) field.get(bootstrap);
    generation.set(3); // as if three connections had completed initialization

    bootstrap.closeForUpgrade();

    assertEquals(
        0L,
        generation.get(),
        "close() drops the client, spawner and signal bus; the generation describes that same"
            + " connection and must go with them, or the next start() skips first-connect init");
    assertFalse(bootstrap.hasClient());
  }
}
