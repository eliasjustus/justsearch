/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.inference.telemetry.TransitionReason;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single writer that converges the observed runtime toward {@link RuntimeSpec} ∧ policy
 * (tempdoc 737 §12a). One dedicated daemon thread + a level-triggered dirty flag — <b>no reconcile
 * work ever runs on a caller or listener thread.</b>
 *
 * <h3>Re-entrancy contract (R2)</h3>
 *
 * {@code TransitionRunner} fires {@link ModeChangeListener}s <i>under its lock</i>, and a
 * re-entrant {@code run()} from a listener throws {@code IllegalStateException} ("Already
 * transitioning"). Therefore this reconciler's mode-change listener ONLY updates
 * {@link RuntimeStatus} + the {@link RuntimeGpuLease} mirror and marks the loop dirty;
 * convergence (the {@code switchTo*} calls) runs later on the reconciler thread.
 *
 * <h3>Phase-1 convergence scope (deliberate — do not exceed)</h3>
 *
 * Convergence runs ONLY on (a) boot ({@link #requestBootConvergence()}) and (b) explicit spec
 * writes ({@link #specChanged()}). A mode change from another actor (e.g. the VDU sampler parking
 * the engine into INDEXING) updates status but does NOT trigger convergence back to spec.
 * Continuous return-to-spec arrives in Phase 2 when procedures are modeled; acting on it now would
 * fight the not-yet-rerouted {@code OfflineCoordinator} (the fighting-controllers failure).
 *
 * <!-- PHASE-2: continuous return-to-spec on foreign mode changes; procedure overlay; and thread
 *      the reconciler-initiated TransitionReason through to the manager instead of the manager's
 *      hard-coded USER_SWITCH (the OnlineAiLifecycleControl.switchTo* interface carries no reason
 *      in Phase 1, so {@link #lastConvergenceReason} is recorded for status/telemetry only). -->
 */
public final class RuntimeReconciler implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RuntimeReconciler.class);

  private static final long BASE_BACKOFF_MS = 1_000L;
  private static final long MAX_BACKOFF_MS = 60_000L;

  /** Stop-primitive that may throw the same checked exception the switch primitives do. */
  @FunctionalInterface
  public interface DetachAction {
    void detach() throws ModeTransitionException;
  }

  private final OnlineAiLifecycleControl control;
  private final Supplier<Mode> modeSupplier;
  private final BooleanSupplier externalAdoption;
  private final DetachAction detach; // nullable
  private final EnterprisePolicyService policy; // nullable
  private final RuntimeSpecStore specStore;
  private final RuntimeGpuLease lease;

  private final AtomicReference<RuntimeStatus> status = new AtomicReference<>(RuntimeStatus.initial());
  private final AtomicLong specVersion = new AtomicLong(0);

  private final Object lock = new Object();
  private volatile boolean running = false;
  private boolean dirty = false; // guarded by lock
  private boolean convergePending = false; // guarded by lock
  private TransitionReason pendingReason = TransitionReason.UNKNOWN; // guarded by lock
  private long retryAtMillis = 0; // guarded by lock
  private long backoffMillis = 0; // guarded by lock
  private boolean converging = false; // guarded by lock — a convergence pass is in flight
  private volatile TransitionReason lastConvergenceReason = null;

  private Thread thread;
  private ModeChangeListener attachedListener;

  public RuntimeReconciler(
      OnlineAiLifecycleControl control,
      Supplier<Mode> modeSupplier,
      BooleanSupplier externalAdoption,
      DetachAction detach,
      EnterprisePolicyService policy,
      RuntimeSpecStore specStore,
      RuntimeGpuLease lease) {
    this.control = control;
    this.modeSupplier = modeSupplier;
    this.externalAdoption = externalAdoption;
    this.detach = detach;
    this.policy = policy;
    this.specStore = specStore;
    this.lease = lease;
  }

  /**
   * Attach the mode-change listener (mirror-initial-state-then-forward — the
   * {@code standalone-capability-stays-stuck} medicine) and start the reconciler thread.
   * Idempotent-ish: calling twice is a programming error and logs a warning.
   */
  public synchronized void start() {
    if (running) {
      log.warn("RuntimeReconciler already started");
      return;
    }
    running = true;

    // Mirror initial state synchronously BEFORE forwarding transitions.
    Mode initial = safeMode();
    lease.mirrorFromMode(initial);
    status.set(RuntimeStatus.derive(initial, safeExternal(), lease.holder(), specVersion.get(), Instant.now()));

    if (control != null) {
      attachedListener =
          (from, to) -> {
            // Runs UNDER the runner's lock — do NOT converge here. Fast, non-reentrant only.
            lease.mirrorFromMode(to);
            markStatusDirty();
          };
      control.addModeChangeListener(attachedListener);
    }

    thread = new Thread(this::loop, "runtime-reconciler");
    thread.setDaemon(true);
    thread.start();
  }

  /** Boot-time first reconcile toward spec (AUTO_START intent). */
  public void requestBootConvergence() {
    requestConvergence(TransitionReason.AUTO_START);
  }

  /** A spec write happened — bump the observed spec version and converge (USER_SWITCH intent). */
  public void specChanged() {
    specVersion.incrementAndGet();
    requestConvergence(TransitionReason.USER_SWITCH);
  }

  private void requestConvergence(TransitionReason reason) {
    synchronized (lock) {
      convergePending = true;
      pendingReason = reason;
      dirty = true;
      lock.notifyAll();
    }
  }

  private void markStatusDirty() {
    synchronized (lock) {
      dirty = true;
      lock.notifyAll();
    }
  }

  /** Latest observed status snapshot (for future projections). */
  public RuntimeStatus current() {
    return status.get();
  }

  /** The TransitionReason the last reconciler-initiated transition was labeled with (may be null). */
  public TransitionReason lastConvergenceReason() {
    return lastConvergenceReason;
  }

  @Override
  public void close() {
    synchronized (lock) {
      running = false;
      lock.notifyAll();
    }
    if (control != null && attachedListener != null) {
      try {
        control.removeModeChangeListener(attachedListener);
      } catch (RuntimeException e) {
        log.debug("removeModeChangeListener failed on close: {}", e.getMessage());
      }
    }
    Thread t = thread;
    if (t != null) {
      t.interrupt();
      try {
        t.join(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // ==================== Reconciler thread ====================

  private void loop() {
    while (running) {
      boolean doConverge;
      TransitionReason reason;
      synchronized (lock) {
        while (running && !dirty && !retryDue()) {
          long wait = computeWaitMillis();
          try {
            if (wait <= 0) {
              lock.wait();
            } else {
              lock.wait(wait);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!running) {
              return;
            }
          }
        }
        if (!running) {
          return;
        }
        dirty = false;
        doConverge = convergePending;
        convergePending = false;
        converging = doConverge; // set in the same critical section — no gap for awaitQuiescent
        reason = pendingReason;
      }

      refreshStatus();
      if (doConverge) {
        attemptConverge(reason);
      }

      synchronized (lock) {
        converging = false;
        lock.notifyAll();
      }
    }
  }

  /** Recompute and publish the observed status from live signals. */
  private void refreshStatus() {
    Mode mode = safeMode();
    boolean external = safeExternal();
    lease.mirrorFromMode(mode);
    status.set(RuntimeStatus.derive(mode, external, lease.holder(), specVersion.get(), Instant.now()));
  }

  private void attemptConverge(TransitionReason reason) {
    Mode mode = safeMode();
    if (mode == Mode.TRANSITIONING) {
      // Mid-transition — cannot switch (would throw "Already transitioning"). Defer: re-arm and
      // wait for the settling transition's listener to re-wake the loop.
      synchronized (lock) {
        convergePending = true;
      }
      return;
    }

    RuntimeSpec spec = specStore.load();
    boolean effective = spec.chatEnabled() && policyOnlineEnabled();
    boolean healthy = mode == Mode.ONLINE;
    boolean down = mode == Mode.OFFLINE || mode == Mode.INDEXING;
    boolean external = safeExternal();

    try {
      if (effective && down && !external) {
        lastConvergenceReason = reason;
        log.info("RuntimeReconciler: converging engine UP (reason={})", reason);
        control.switchToOnlineMode();
      } else if (!effective && healthy) {
        lastConvergenceReason = reason;
        if (external) {
          log.info("RuntimeReconciler: chat disabled + external adoption -> detach (reason={})", reason);
          if (detach != null) {
            detach.detach();
          }
        } else {
          log.info("RuntimeReconciler: converging engine DOWN via stop primitive (reason={})", reason);
          control.switchToIndexingMode();
        }
      }
      // else: already at spec — nothing to do.
      synchronized (lock) {
        retryAtMillis = 0;
        backoffMillis = 0;
      }
      refreshStatus();
    } catch (ModeTransitionException e) {
      log.warn("RuntimeReconciler: transition failed; will retry with backoff", e);
      recordEngineFailure(e);
      synchronized (lock) {
        convergePending = true;
        backoffMillis = backoffMillis == 0 ? BASE_BACKOFF_MS : Math.min(backoffMillis * 2, MAX_BACKOFF_MS);
        retryAtMillis = System.currentTimeMillis() + backoffMillis;
        lock.notifyAll();
      }
    }
  }

  private void recordEngineFailure(ModeTransitionException e) {
    Mode mode = safeMode();
    RuntimeStatus base = RuntimeStatus.derive(mode, safeExternal(), lease.holder(), specVersion.get(), Instant.now());
    // Overlay an error reason onto the ENGINE condition without inventing a new axis.
    java.util.List<RuntimeStatus.Condition> updated = new java.util.ArrayList<>();
    for (RuntimeStatus.Condition c : base.conditions()) {
      if (c.axis() == RuntimeStatus.Axis.ENGINE) {
        updated.add(
            new RuntimeStatus.Condition(
                c.axis(),
                c.status(),
                "transition-failed",
                "Transition failed: " + safeMessage(e),
                c.observedSpecVersion(),
                c.lastTransition()));
      } else {
        updated.add(c);
      }
    }
    status.set(new RuntimeStatus(updated));
  }

  private boolean retryDue() {
    return retryAtMillis > 0 && System.currentTimeMillis() >= retryAtMillis;
  }

  private long computeWaitMillis() {
    if (retryAtMillis > 0) {
      return Math.max(1, retryAtMillis - System.currentTimeMillis());
    }
    return 0; // indefinite until notified
  }

  private boolean policyOnlineEnabled() {
    if (policy == null) {
      return true;
    }
    try {
      return policy.snapshot().onlineAiEnabled();
    } catch (RuntimeException e) {
      // Best-effort, mirroring BrainRuntimeServiceImpl: a policy read error does not deny.
      return true;
    }
  }

  private Mode safeMode() {
    if (modeSupplier == null) {
      return Mode.OFFLINE;
    }
    try {
      Mode m = modeSupplier.get();
      return m == null ? Mode.OFFLINE : m;
    } catch (RuntimeException e) {
      return Mode.OFFLINE;
    }
  }

  private boolean safeExternal() {
    if (externalAdoption == null) {
      return false;
    }
    try {
      return externalAdoption.getAsBoolean();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String safeMessage(Throwable t) {
    String m = t == null ? null : t.getMessage();
    return m == null ? "unknown" : m;
  }

  // ==================== Test support ====================

  /**
   * Blocks until the reconciler thread has completed at least one full loop iteration after this
   * call with no convergence still pending and no retry scheduled. Test-only quiescence barrier.
   */
  public boolean awaitQuiescent(long timeoutMs) {
    long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
    synchronized (lock) {
      while (running && (convergePending || converging || retryAtMillis > 0)) {
        long remainMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainMs <= 0) {
          return false;
        }
        try {
          lock.wait(Math.max(1, remainMs));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
      return running && !convergePending && !converging && retryAtMillis == 0;
    }
  }
}
