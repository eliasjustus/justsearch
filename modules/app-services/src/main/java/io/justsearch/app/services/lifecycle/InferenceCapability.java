/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lifecycle;

import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import java.util.List;
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
  private volatile String reason = "Inference not yet activated";
  private volatile boolean configured;
  private final List<BiConsumer<CapabilityHealth, CapabilityHealth>> listeners =
      new CopyOnWriteArrayList<>();

  public InferenceCapability(boolean configured) {
    this.configured = configured;
    if (!configured) {
      this.health = CapabilityHealth.OFFLINE;
      this.reason = "Inference not configured";
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
    CapabilityHealth prev = this.health;
    this.reason = newReason;
    this.health = newHealth;
    if (prev != newHealth) {
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
