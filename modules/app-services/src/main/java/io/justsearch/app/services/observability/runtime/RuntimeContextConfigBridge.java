/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.runtime;

import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.RuntimeContextHolder;
import io.justsearch.configuration.resolved.ConfigChangedEvent;
import io.justsearch.configuration.resolved.ConfigStore;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bridges {@link ConfigStore} change events into {@link RuntimeContextChangeRegistry}
 * broadcasts.
 *
 * <p>Per slice 440 §B.B (post-impl correction): the original substrate read
 * {@link RuntimeContext#automationEnabled()} once at boot and never updated it. Settings
 * mutation via {@code POST /api/settings/v2 → SettingsController.handleUpdateSettingsV2 →
 * ConfigStoreRebuilder.rebuild → ConfigStore.update} fires the listener chain on
 * {@link ConfigStore}, but no listener was registered to broadcast a RuntimeContext
 * replace event. Result: the SSE stream's primary purpose (live mutation broadcasts) never
 * fired for the only mutable field. This bridge wires the missing link.
 *
 * <p>Behavior:
 *
 * <ul>
 *   <li>Listens for {@link ConfigChangedEvent} on the supplied {@link ConfigStore}.
 *   <li>On each event, recomputes the next {@link RuntimeContext} from the new config
 *       (today: only {@code automationEnabled} is config-driven; {@code systemMode} is
 *       boot-static via the {@code justsearch.eval.mode} system property, so the bridge
 *       reads {@code current.systemMode()} unchanged from the holder).
 *   <li>Compares to the current holder value. If unchanged, no-op (don't bump the
 *       registry's version on identity broadcast).
 *   <li>If changed, writes the new context to the holder and broadcasts via the registry.
 * </ul>
 *
 * <p>Listener leak note: ConfigStore is process-global; {@link #stop()} unregisters the
 * listener for clean shutdown but is not always called (existing global-state pattern).
 * Multiple bootstrap instances in the same JVM (e.g., test recreation) accumulate
 * listeners unless {@code stop()} is called. Acceptable for production single-bootstrap;
 * tests should call {@code stop()} in {@code @AfterEach}.
 */
public final class RuntimeContextConfigBridge {

  private final RuntimeContextHolder holder;
  private final RuntimeContextChangeRegistry registry;
  private final ConfigStore configStore;
  private final Consumer<ConfigChangedEvent> listener;

  public RuntimeContextConfigBridge(
      RuntimeContextHolder holder,
      RuntimeContextChangeRegistry registry,
      ConfigStore configStore) {
    this.holder = Objects.requireNonNull(holder, "holder");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.configStore = Objects.requireNonNull(configStore, "configStore");
    this.listener = this::onConfigChanged;
    this.configStore.addListener(this.listener);
  }

  private void onConfigChanged(ConfigChangedEvent event) {
    boolean nextAutomation = event.current().ui().automationEnabled();
    RuntimeContext current = holder.current();
    if (current.automationEnabled() == nextAutomation) {
      return;
    }
    RuntimeContext next = new RuntimeContext(current.systemMode(), nextAutomation);
    holder.set(next);
    registry.broadcast(next);
  }

  /** Unregisters the ConfigStore listener. Idempotent. */
  public void stop() {
    configStore.removeListener(listener);
  }
}
