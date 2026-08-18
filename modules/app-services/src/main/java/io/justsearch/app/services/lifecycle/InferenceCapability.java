/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Tracks the operational health of the Inference (Brain/llama-server) capability.
 *
 * <p>Inference is optional — {@link #required()} returns false when inference is not configured.
 * Health transitions map from {@code io.justsearch.app.api.Mode}:
 * ONLINE → READY, OFFLINE → OFFLINE, TRANSITIONING → RECOVERING, INDEXING → DEGRADED.
 */
public final class InferenceCapability implements Capability {

  private volatile CapabilityHealth health = CapabilityHealth.PENDING;
  // Tempdoc 837 S5: the reason slot holds a LifecycleReasonCode, never prose — the sentence moved to
  // `detail` (the C.1 typed split, applied to the capability S3 could not reach). The pre-activation
  // default is the generic code, which is exactly what StatusLifecycleHandler already substituted for
  // the prose that used to live here; the change is that the RAW slot (runtime manifest, 503 body)
  // stops carrying a sentence a code-reading consumer has to discard.
  private volatile String reason = LifecycleReasonCode.INFERENCE_OFFLINE.code();
  private volatile String detail = "Inference not yet activated";
  private volatile boolean configured;
  private final List<BiConsumer<CapabilityHealth, CapabilityHealth>> listeners =
      new CopyOnWriteArrayList<>();

  public InferenceCapability(boolean configured) {
    this.configured = configured;
    if (!configured) {
      this.health = CapabilityHealth.OFFLINE;
      this.reason = LifecycleReasonCode.INFERENCE_OFFLINE.code();
      this.detail = "Inference not configured";
    }
  }

  @Override
  public CapabilityHealth health() {
    return health;
  }

  @Override
  public String pendingReason() {
    return health == CapabilityHealth.READY ? null : reason;
  }

  @Override
  public String pendingDetail() {
    return health == CapabilityHealth.READY ? null : detail;
  }

  @Override
  public boolean required() {
    return configured;
  }

  @Override
  public String name() {
    return "inference";
  }

  public void setConfigured(boolean configured) {
    this.configured = configured;
  }

  /**
   * Transition health state. Fires listeners after transition.
   * Returns the previous health state.
   */
  public CapabilityHealth transition(CapabilityHealth newHealth, String newReason) {
    return transition(newHealth, newReason, null);
  }

  /**
   * Transition health state, carrying a human {@code detail} sentence alongside the reason code.
   *
   * <p><b>Reason retention (tempdoc 837 §D.1).</b> The decision is
   * {@link ReasonRetention#retainHeld} — the same rule {@link WorkerCapability} uses, so the two
   * capabilities cannot grow separate precedence policies. The case it exists for here:
   * {@code RuntimeActivationService.reportToCapability} stamps a precise fault
   * ({@code inference.model_not_found}, {@code inference.crashed}) and a subsequent generic
   * re-derivation — every {@code chatEnabled} toggle re-derives with {@code TransitionReason.UNKNOWN}
   * — would otherwise overwrite it with {@code inference.offline}, silently restoring the collapse
   * S5 exists to fix.
   *
   * <p>The new health is ALWAYS applied; only the reason (and its detail) is retained, and READY
   * clears both outright.
   */
  public CapabilityHealth transition(
      CapabilityHealth newHealth, String newReason, String newDetail) {
    CapabilityHealth prev = this.health;
    String prevReason = this.reason;
    boolean retained = ReasonRetention.retainHeld(prevReason, newReason, newHealth);
    String effectiveReason = retained ? prevReason : newReason;
    String effectiveDetail = retained ? this.detail : newDetail;
    this.reason = effectiveReason;
    this.detail = effectiveDetail;
    this.health = newHealth;
    // Tempdoc 656 Task 0: fire listeners on a reason-only change too (health unchanged), not just a
    // health transition. RuntimeManifestListenerWiring is purely listener-driven with no polling
    // fallback, so a more specific reason arriving while health stays e.g. OFFLINE would otherwise
    // never reach the manifest. Tempdoc 837 §1.4: the EFFECTIVE change is what fires — a write whose
    // reason was rejected with no health change must not become a spurious manifest publish.
    if (prev != newHealth || !Objects.equals(prevReason, effectiveReason)) {
      for (BiConsumer<CapabilityHealth, CapabilityHealth> listener : listeners) {
        listener.accept(prev, newHealth);
      }
    }
    return prev;
  }

  public void addListener(BiConsumer<CapabilityHealth, CapabilityHealth> listener) {
    listeners.add(listener);
  }
}
