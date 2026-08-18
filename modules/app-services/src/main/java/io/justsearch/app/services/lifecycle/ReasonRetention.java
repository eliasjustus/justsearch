/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.RetentionClass;

/**
 * Tempdoc 837 §D.1 — the ONE precedence rule for a capability's reason slot, shared by {@link
 * WorkerCapability} and {@link InferenceCapability} so the two cannot grow separate copies.
 *
 * <p>The slot is last-writer-wins by default, and that loses causes in two measured ways:
 *
 * <ul>
 *   <li>{@code RuntimeActivationService} stamps a precise activation failure and the next mode
 *       change overwrites it with the generic {@code inference.offline} — the specific cause the 656
 *       slice worked to surface is gone. After S5 this fires on ANY {@code chatEnabled} toggle
 *       (which re-derives with {@code TransitionReason.UNKNOWN}), so without this rule a user
 *       flipping a setting after a crash would overwrite {@code inference.crashed} with the generic
 *       code — S5 would ship the very collapse it exists to fix, and its happy-path test would pass.
 *   <li>{@code WorkerFatalReasonMarker.readAndClear} DELETES the marker as it reads it, so the
 *       corrupt-index cause is observable exactly once; a later overwrite destroys it permanently.
 * </ul>
 *
 * <p><b>Retention is keyed on the HELD code's class, never on the incoming code's genericness.</b>
 * The first draft of this rule (§1.4) asked "is the incoming code the declared generic fallback?"
 * and was a wrong-gate: once {@code KnowledgeServerBootstrap} started stamping
 * {@code worker.starting}, the literal rule retained a STARTING worker as the reported cause of a
 * spawn failure. What licenses retention is whether the held code is evidence a newer write would
 * destroy.
 */
final class ReasonRetention {

  private ReasonRetention() {}

  /**
   * True when the currently-held reason must survive this write.
   *
   * <p>The new HEALTH is always applied by the caller — only the reason (and its detail) is
   * retained. {@code READY} clears everything: that is the anti-staleness bound, and it is why the
   * rule needs no timer.
   *
   * <p>An incoming {@link RetentionClass#STICKY} code outranks a held {@link RetentionClass#FAULT}
   * for the same reason a newer fault does — it is strictly better information (this is the case
   * §D.1's four-row table does not enumerate; retaining there would reject the corrupt-index cause
   * whenever a {@code worker.lost} tick happened to land first, which is the exact sequence the
   * shipped latch tests pin).
   */
  static boolean retainHeld(String heldCode, String incomingCode, CapabilityHealth newHealth) {
    if (newHealth == CapabilityHealth.READY) {
      return false;
    }
    RetentionClass held = LifecycleReasonCode.retentionClassOf(heldCode);
    if (held == RetentionClass.STICKY) {
      return true;
    }
    if (held != RetentionClass.FAULT) {
      return false;
    }
    RetentionClass incoming = LifecycleReasonCode.retentionClassOf(incomingCode);
    return incoming != RetentionClass.FAULT && incoming != RetentionClass.STICKY;
  }
}
