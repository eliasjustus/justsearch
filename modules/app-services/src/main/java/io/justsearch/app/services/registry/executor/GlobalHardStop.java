/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A process-wide emergency stop for non-user actions — tempdoc 550 E2.
 *
 * <p>When engaged, the trust lattice ({@link OperationExecutorImpl#enforceTrustLattice}) DENIES
 * every dispatch from an UNTRUSTED source tier — the agent loop, MCP, plugin-emitted, and
 * LLM-emission paths — while leaving user-driven dispatch (TRUSTED buttons/rails/palette, and
 * MEDIUM URL-bar) unaffected. Because the lattice is the verified sole chokepoint every non-user
 * action traverses, this is a circuit breaker enforced <b>outside</b> the agent's control: an
 * agent cannot route around it, and it stops NEW dispatches the moment it is engaged.
 *
 * <p>Scope: this denies new dispatches. It does not cancel work a handler already kicked off (a
 * running indexing job continues) — halting in-flight background jobs is a separate, larger
 * mechanism (see tempdoc 550 §Feasibility, E2 "halt in-flight").
 *
 * <p>Additive: default state is released (not engaged), so wiring it changes nothing until an
 * operator engages it.
 */
public final class GlobalHardStop {

  private final AtomicBoolean engaged = new AtomicBoolean(false);
  // Tempdoc 550 thesis IV: the hard stop is a global REVOCATION acting on grants. On the
  // false→true transition it runs this hook (wired to revoke all live grants), so engaging the
  // emergency stop also cancels pending approvals — not only new dispatches. Default no-op.
  private volatile Runnable onEngage = () -> {};

  /** Wire the action run when the stop transitions to engaged (e.g. revoke all live grants). */
  public void setOnEngage(Runnable hook) {
    this.onEngage = hook != null ? hook : () -> {};
  }

  /** Engage the stop — non-user dispatches are denied until {@link #release()}. */
  public void engage() {
    if (engaged.compareAndSet(false, true)) {
      onEngage.run();
    }
  }

  /** Release the stop — non-user dispatches resume their normal gate evaluation. */
  public void release() {
    engaged.set(false);
  }

  /** Set the engaged state directly (for an idempotent toggle endpoint). */
  public void set(boolean value) {
    if (value) {
      engage();
    } else {
      release();
    }
  }

  /** True while the emergency stop is engaged. */
  public boolean isEngaged() {
    return engaged.get();
  }
}
