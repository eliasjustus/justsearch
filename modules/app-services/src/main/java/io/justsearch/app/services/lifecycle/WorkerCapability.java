/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.services.worker.RecoveryContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Tracks the operational health of the Worker (Knowledge Server) capability.
 *
 * <p>Structurally acquired when the Worker first connects (health transitions from PENDING).
 * Health fluctuates thereafter as the Worker process crashes/restarts/recovers.
 *
 * <p>The generation counter distinguishes first-connect from recovery: generation 0→1 is
 * first connect, 1→2+ is recovery after crash. Callers can use this to decide which
 * initialization to re-run.
 */
public final class WorkerCapability implements Capability {

  private volatile CapabilityHealth health = CapabilityHealth.PENDING;
  private volatile String reason = "Worker not yet connected";
  // Tempdoc 627 (N2): the most-recent recovery context, parked by the supervision bridge immediately
  // before the RECOVERING transition so the capability-health bridge can attach it as forensic
  // attributes on the recovery occurrence. Read synchronously by transition listeners.
  private volatile RecoveryContext lastRecoveryContext;
  private final AtomicLong generation = new AtomicLong(0);
  private final List<BiConsumer<CapabilityHealth, CapabilityHealth>> listeners =
      new CopyOnWriteArrayList<>();

  @Override
  public CapabilityHealth health() {
    return health;
  }

  @Override
  public String pendingReason() {
    return health == CapabilityHealth.READY ? null : reason;
  }

  @Override
  public boolean required() {
    return true;
  }

  @Override
  public String name() {
    return "worker";
  }

  public long generation() {
    return generation.get();
  }

  public boolean isFirstConnect() {
    return generation.get() <= 1;
  }

  /**
   * Transition health state. Fires listeners after transition.
   * Returns the previous health state.
   */
  public CapabilityHealth transition(CapabilityHealth newHealth, String newReason) {
    CapabilityHealth prev = this.health;
    String prevReason = this.reason;
    this.reason = newReason;
    this.health = newHealth;
    boolean healthChanged = prev != newHealth;
    if (healthChanged) {
      if (prev == CapabilityHealth.PENDING) {
        generation.set(1);
      } else if (newHealth == CapabilityHealth.READY
          && (prev == CapabilityHealth.RECOVERING || prev == CapabilityHealth.DEGRADED)) {
        generation.incrementAndGet();
      }
    }
    // Tempdoc 656 Task 0: fire listeners on a reason-only change too, not just a health transition —
    // see InferenceCapability.transition() for the shared rationale. Generation-counter side effects
    // above stay gated on healthChanged only; this widening only affects listener notification.
    if (healthChanged || !Objects.equals(prevReason, newReason)) {
      for (BiConsumer<CapabilityHealth, CapabilityHealth> listener : listeners) {
        listener.accept(prev, newHealth);
      }
    }
    return prev;
  }

  public void addListener(BiConsumer<CapabilityHealth, CapabilityHealth> listener) {
    listeners.add(listener);
  }

  /**
   * Tempdoc 627 (N2): park the most-recent recovery context. The supervision bridge calls this
   * immediately before {@link #transition} to RECOVERING so a synchronous transition listener (the
   * capability-health bridge) can read it when it emits the recovery occurrence.
   */
  public void setRecoveryContext(RecoveryContext ctx) {
    this.lastRecoveryContext = ctx;
  }

  /** The most-recent recovery context, or {@code null} if no recovery has been recorded yet. */
  public RecoveryContext lastRecoveryContext() {
    return lastRecoveryContext;
  }
}
