/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background health monitor for the Worker process. Polls {@link
 * KnowledgeServerBootstrap#checkHealth()} and triggers deferred auxiliary initialization
 * on ERROR→READY recovery transitions.
 *
 * <p>Tempdoc 630 (latency-hardening): this periodic loop doubles as the Head-side <b>resume
 * detector</b>. Because it wakes every {@code pollIntervalMs}, an inter-tick wall-clock gap far
 * larger than that interval means the process was frozen — the machine suspended and resumed (see
 * {@link ResumeDetector}). On a detected resume the monitor <b>eagerly</b> reconnects the gRPC
 * channel and re-registers watchers + reconciles, instead of waiting for the reactive recovery
 * (first post-wake RPC reconnects; periodic sync eventually re-walks). Done before {@link
 * KnowledgeServerBootstrap#checkHealth()} so the first post-wake tick checks a fresh channel rather
 * than flipping the capability to DEGRADED on a stale one.
 *
 * <p><b>Tempdoc 825 — one monitor authority, two arms.</b> This monitor is now constructed
 * unconditionally, including when the bootstrap FAILED to start. Before 825 the sole construction
 * site was gated on a non-null bootstrap, so the one outcome that most needed a monitor — the worker
 * never came up — was the one outcome with no monitor at all: {@code /api/health} served 503 for the
 * life of the process and the operator's own escape hatch ({@code POST /api/worker/restart}) 503'd
 * too. Each tick picks its arm from {@link KnowledgeServerBootstrap#hasClient()}:
 *
 * <ul>
 *   <li><b>client bound</b> — today's behaviour: {@link KnowledgeServerBootstrap#checkHealth()} plus
 *       the recovery→READY deferred-initialization hook.
 *   <li><b>no client</b> — the boot-recovery arm: a bounded, backed-off re-attempt of the bootstrap
 *       under {@link BootRecoveryDecision}, ending either in a handover (the API surfaces late-bind
 *       to the now-live worker) or in exactly one terminal
 *       {@code worker.spawn_recovery_exhausted}.
 * </ul>
 *
 * <p>Both arms run on the same single-threaded executor, so an attempt can never overlap a health
 * poll or another attempt — the "one restart authority" constraint from the tempdoc-627 review is
 * structural here, not a convention.
 */
public final class KnowledgeServerHealthMonitor implements Closeable, WorkerRecoveryAuthority {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeServerHealthMonitor.class);

  static final long DEFAULT_POLL_INTERVAL_MS = 10_000;

  /**
   * Resume threshold factor (tempdoc 630): a tick is treated as post-resume only if the observed
   * inter-tick gap exceeds {@code pollIntervalMs * RESUME_TOLERANCE_FACTOR}. Generous so ordinary
   * GC / scheduler jitter never false-triggers an eager reconnect+reconcile.
   */
  static final long RESUME_TOLERANCE_FACTOR = 3;

  private final KnowledgeServerBootstrap bootstrap;
  private final long pollIntervalMs;
  private final ScheduledExecutorService executor;
  private final LongSupplier nowMs;
  private final BootRecoveryPolicy recoveryPolicy;
  /** Wall-clock (epoch ms) of the previous tick; {@code -1} until the first tick. */
  private volatile long lastTickWallMs = -1;

  /**
   * Tempdoc 825: run once a boot-recovery attempt has bound a client, so the composition root can
   * late-bind the now-live worker into the API surfaces (the handover
   * {@code HeadAssembly.connectKnowledgeServer} + {@code LocalApiServer.lateBindKnowledgeServer}
   * pair). Null in tests and standalone launchers that have no surfaces to bind.
   */
  private volatile Consumer<KnowledgeServerBootstrap> onRecoveryConnected;

  // --- boot-recovery arm state. Mutated only by the single executor thread (the CAS below admits
  // one attempt at a time), but READ by requestRecoveryNow on the caller's thread, so volatile. ---
  private volatile int recoveryAttemptsMade;
  private volatile long lastRecoveryAttemptMs = -1;
  private volatile boolean recoveryGaveUp;
  private final AtomicBoolean recoveryAttemptRunning = new AtomicBoolean(false);

  public KnowledgeServerHealthMonitor(KnowledgeServerBootstrap bootstrap) {
    this(bootstrap, DEFAULT_POLL_INTERVAL_MS);
  }

  public KnowledgeServerHealthMonitor(KnowledgeServerBootstrap bootstrap, long pollIntervalMs) {
    this(bootstrap, pollIntervalMs, System::currentTimeMillis);
  }

  /**
   * @param nowMs wall-clock source (epoch ms) for resume detection — injectable so the gap logic is
   *     unit-testable without a real clock or a real OS suspend (tempdoc 630)
   */
  public KnowledgeServerHealthMonitor(
      KnowledgeServerBootstrap bootstrap, long pollIntervalMs, LongSupplier nowMs) {
    this(bootstrap, pollIntervalMs, nowMs, BootRecoveryPolicy.defaults());
  }

  /**
   * @param recoveryPolicy budget for the boot-recovery arm (tempdoc 825) — injectable so a component
   *     test can exercise the give-up path without waiting out the production backoff
   */
  public KnowledgeServerHealthMonitor(
      KnowledgeServerBootstrap bootstrap,
      long pollIntervalMs,
      LongSupplier nowMs,
      BootRecoveryPolicy recoveryPolicy) {
    if (bootstrap == null) {
      throw new IllegalArgumentException("bootstrap must not be null");
    }
    this.bootstrap = bootstrap;
    this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
    this.nowMs = nowMs;
    this.recoveryPolicy = recoveryPolicy != null ? recoveryPolicy : BootRecoveryPolicy.defaults();
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "knowledge-server-health-monitor");
              t.setDaemon(true);
              return t;
            });
  }

  public void start() {
    log.info("Knowledge Server health monitor started (poll interval: {}ms)", pollIntervalMs);
    @SuppressWarnings("unused")
    var ignored =
        executor.scheduleWithFixedDelay(
            this::tick, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
  }

  void tick() {
    try {
      // Tempdoc 630: detect an OS suspend/resume by the inter-tick wall-clock gap, and eagerly
      // re-validate the Worker surface BEFORE the health check (so a stale post-wake channel does
      // not flip the capability to DEGRADED).
      long now = nowMs.getAsLong();
      long gap = ResumeDetector.resumeGapMs(lastTickWallMs, now, pollIntervalMs, RESUME_TOLERANCE_FACTOR);
      lastTickWallMs = now;
      if (gap > 0) {
        eagerlyRevalidateAfterResume(gap);
      }

      // Tempdoc 825: pick the arm. No client ⇒ start() never completed, so there is nothing to poll
      // for health and everything to recover.
      if (!bootstrap.hasClient()) {
        runBootRecoveryArm();
        return;
      }

      CapabilityHealth before = bootstrap.workerCapability().health();
      bootstrap.checkHealth();
      CapabilityHealth after = bootstrap.workerCapability().health();
      if (before != CapabilityHealth.READY && after == CapabilityHealth.READY) {
        log.info(
            "Knowledge Server recovered to READY ({}→{}); running deferred auxiliary"
                + " initialization",
            before,
            after);
        bootstrap.completeReadyInitializationFromMonitor();
      }
    } catch (Exception e) {
      log.warn("Knowledge Server health monitor tick failed: {}", e.getMessage(), e);
      WorkerCapability cap = bootstrap.workerCapability();
      if (cap.health() == CapabilityHealth.READY) {
        // Tempdoc 837 §3.1: guarded on READY — the worker was serving, so this is "lost", not
        // "never started". The exception text is the detail behind the code.
        cap.transition(
            CapabilityHealth.DEGRADED,
            io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_LOST.code(),
            "Health monitor tick exception: " + e.getMessage());
      }
    }
  }

  /**
   * Tempdoc 825: install the handover the composition root performs once a recovery attempt has
   * bound a client. Called before {@link #start()} by {@code HeadlessApp}.
   */
  public void onRecoveryConnected(Consumer<KnowledgeServerBootstrap> handover) {
    this.onRecoveryConnected = handover;
  }

  /**
   * The boot-recovery arm (tempdoc 825 §D2). Reads the observable state, asks the pure
   * {@link BootRecoveryDecision} what to do, and executes that verbatim.
   */
  private void runBootRecoveryArm() {
    BootRecoveryDecision.Decision decision =
        BootRecoveryDecision.decide(currentRecoveryInput(), recoveryPolicy);
    switch (decision.action()) {
      case NONE -> {
        // Already recovered, or already terminal. Silence is the correct behaviour.
      }
      case WAIT ->
          log.debug(
              "Boot recovery attempt {} due in {}ms",
              decision.nextAttempt(),
              decision.waitMs());
      case ATTEMPT -> attemptBootRecovery(decision.nextAttempt());
      case GIVE_UP -> narrateGiveUp(decision.veto());
    }
  }

  /** The observable state the boot-recovery decision is a function of. */
  private BootRecoveryDecision.Input currentRecoveryInput() {
    long sinceLastAttempt =
        lastRecoveryAttemptMs < 0 ? Long.MAX_VALUE : nowMs.getAsLong() - lastRecoveryAttemptMs;
    return new BootRecoveryDecision.Input(
        bootstrap.hasClient(),
        bootstrap.supervisionEngagedOnLastAttempt(),
        LifecycleReasonCode.WORKER_RESTART_EXHAUSTED
            .code()
            .equals(bootstrap.workerCapability().pendingReason()),
        recoveryAttemptsMade,
        recoveryGaveUp,
        sinceLastAttempt);
  }

  /**
   * One bounded re-attempt of the bootstrap. The capability is held at RECOVERING for the whole arc
   * (the bootstrap suppresses its per-attempt narration while
   * {@link KnowledgeServerBootstrap#startForRecovery()} runs), so a multi-cycle recovery that
   * ultimately succeeds narrates ONE {@code worker.restart-attempted} milestone and ONE
   * {@code worker.recovered}, not a flap per cycle.
   */
  private void attemptBootRecovery(int attemptNo) {
    if (!recoveryAttemptRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      recoveryAttemptsMade = attemptNo;
      lastRecoveryAttemptMs = nowMs.getAsLong();
      WorkerCapability cap = bootstrap.workerCapability();
      // Park the forensic context first: the capability-health bridge reads it synchronously from
      // inside the transition below, to attach attempt/kind attributes to the occurrence.
      cap.setRecoveryContext(
          new RecoveryContext(
              attemptNo, "boot", BootRecoveryDecision.backoffMs(attemptNo, recoveryPolicy)));
      cap.transition(
          CapabilityHealth.RECOVERING,
          LifecycleReasonCode.WORKER_RECOVERING.code(),
          "Retrying knowledge server start (attempt "
              + attemptNo
              + " of "
              + recoveryPolicy.maxAttempts()
              + ")");
      log.info(
          "Boot recovery: re-attempting Knowledge Server start ({}/{})",
          attemptNo,
          recoveryPolicy.maxAttempts());
      try {
        bootstrap.startForRecovery();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Boot recovery attempt {} interrupted", attemptNo);
        return;
      } catch (Exception e) {
        log.warn("Boot recovery attempt {} failed: {}", attemptNo, e.toString());
        return;
      }
      if (bootstrap.hasClient()) {
        // The bootstrap is up. Hand it to the surfaces that were late-bound with null at boot, then
        // reset the budget: the arc is over, and any LATER fault is supervision's (the spawner now
        // exists) or the health arm's.
        handOverRecoveredWorker(attemptNo);
        recoveryAttemptsMade = 0;
        lastRecoveryAttemptMs = -1;
      }
    } finally {
      recoveryAttemptRunning.set(false);
    }
  }

  private void handOverRecoveredWorker(int attemptNo) {
    log.info(
        "Boot recovery succeeded on attempt {} — knowledge server is bound (health: {})",
        attemptNo,
        bootstrap.workerCapability().health());
    Consumer<KnowledgeServerBootstrap> handover = this.onRecoveryConnected;
    if (handover == null) {
      return;
    }
    try {
      handover.accept(bootstrap);
    } catch (RuntimeException e) {
      // Never let a surface-binding failure kill the monitor thread: the worker IS up, and the next
      // tick's health arm keeps it observable even if a controller failed to rebind.
      log.error("Boot recovery handover failed after a successful re-attempt", e);
    }
  }

  /**
   * The one terminal narration of this arc. A veto means another authority's verdict already stands
   * ({@code worker.restart_exhausted}, or supervision holding the budget), so we stop trying WITHOUT
   * overwriting it — tempdoc 825 §D5 decision 2.
   */
  private void narrateGiveUp(BootRecoveryDecision.Veto veto) {
    if (recoveryGaveUp) {
      // The terminal state is narrated exactly once per arc. Reachable when a manual request and a
      // periodic tick both resolve to GIVE_UP before either has run.
      return;
    }
    recoveryGaveUp = true;
    switch (veto) {
      case RESTART_EXHAUSTED ->
          log.warn(
              "Boot recovery standing down: supervision has already declared {} — that verdict is"
                  + " terminal and is not superseded",
              LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code());
      case SUPERVISION_ENGAGED ->
          log.warn(
              "Boot recovery standing down: the last start left supervision holding the restart"
                  + " budget, so SupervisionPolicy stays the single restart authority");
      case NONE -> {
        log.error(
            "Boot recovery exhausted after {} attempt(s); the knowledge server will not be retried"
                + " again in this process",
            recoveryAttemptsMade);
        bootstrap
            .workerCapability()
            .transition(
                CapabilityHealth.DEGRADED,
                LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED.code(),
                "Knowledge server failed to start and "
                    + recoveryAttemptsMade
                    + " recovery attempt(s) did not bring it up");
      }
    }
  }

  /**
   * Tempdoc 825 §D5 decision 4: the manual path. Schedules an immediate attempt on the SAME executor
   * the periodic arm uses, so a manual request can never race a tick into two concurrent spawns, and
   * returns what the recovery authority decided rather than blocking an HTTP request on a spawn.
   *
   * <p>An operator's explicit request also clears the backoff wait — but not the budget, and not the
   * vetoes: "the operator asked" is a reason to try sooner, never a reason to try more times than the
   * declared policy or to overrule supervision's terminal verdict.
   */
  @Override
  public Verdict requestRecoveryNow() {
    if (bootstrap.hasClient()) {
      return Verdict.NOT_APPLICABLE;
    }
    if (recoveryAttemptRunning.get()) {
      return Verdict.ALREADY_RUNNING;
    }
    BootRecoveryDecision.Decision decision =
        BootRecoveryDecision.decide(currentRecoveryInput(), recoveryPolicy);
    try {
      return switch (decision.action()) {
        case NONE -> recoveryGaveUp ? Verdict.EXHAUSTED : Verdict.NOT_APPLICABLE;
        case GIVE_UP -> {
          // Narrate on the executor (the arm's own thread) so the manual path lands the same
          // terminal state the periodic path would, exactly once, and no capability write happens
          // off-thread.
          executor.execute(() -> narrateGiveUp(decision.veto()));
          yield switch (decision.veto()) {
            case RESTART_EXHAUSTED -> Verdict.VETOED_RESTART_EXHAUSTED;
            case SUPERVISION_ENGAGED -> Verdict.VETOED_SUPERVISION;
            case NONE -> Verdict.EXHAUSTED;
          };
        }
        // WAIT is an ATTEMPT whose backoff has not elapsed; the request is what makes it due.
        case ATTEMPT, WAIT -> {
          int attemptNo = decision.nextAttempt();
          executor.execute(() -> attemptBootRecovery(attemptNo));
          yield Verdict.ACCEPTED;
        }
      };
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // The monitor is closed (shutdown in progress). Nothing will recover, but an HTTP request must
      // not become a 500 because the process is on its way out — the caller falls back to its own
      // unavailable answer.
      log.debug("Worker recovery request rejected — the monitor is shut down");
      return Verdict.NOT_APPLICABLE;
    }
  }

  /**
   * Tempdoc 630: on a detected resume, eagerly close the two stale-after-suspend windows using the
   * existing actuators — reconnect the gRPC channel ({@link RemoteKnowledgeClient#reconnect()}) and
   * re-register watchers + kick a (freshness-skipping) reconcile walk ({@link
   * RemoteKnowledgeClient#reindexPersistedRoots()}, which catches filesystem events missed while the
   * watcher was frozen). Each step is best-effort and independently guarded so a transient failure
   * never aborts the tick or the other step; the reactive paths (first-RPC reconnect, periodic sync)
   * remain the backstop.
   */
  private void eagerlyRevalidateAfterResume(long gapMs) {
    log.info(
        "Resume detected (process frozen ~{}ms); eagerly reconnecting gRPC + re-registering"
            + " watchers and reconciling",
        gapMs);
    // Tempdoc 630: stamp the resume so /api/status can surface a brief "Catching up after sleep"
    // transient while the reconcile below runs (auto-clears after the notice window).
    bootstrap.markResumed(nowMs.getAsLong());
    RemoteKnowledgeClient client;
    try {
      client = bootstrap.client();
    } catch (RuntimeException e) {
      // Worker not started/ready (client() throws IllegalStateException) — nothing to re-validate;
      // the normal start/spawn path owns bringing it up. Benign on resume.
      log.debug("Post-resume re-validation skipped — worker client not available: {}", e.getMessage());
      return;
    }
    try {
      client.reconnect();
    } catch (RuntimeException e) {
      log.warn("Post-resume gRPC reconnect failed (will retry on next call): {}", e.getMessage());
    }
    try {
      client.reindexPersistedRoots();
    } catch (RuntimeException e) {
      log.warn("Post-resume watcher re-register + reconcile failed: {}", e.getMessage());
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
