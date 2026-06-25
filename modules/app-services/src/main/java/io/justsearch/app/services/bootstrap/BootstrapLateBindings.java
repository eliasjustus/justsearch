/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.api.DebugStateProvider;
import io.justsearch.app.api.StatusSnapshotProvider;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * §31 Phase 2 — holder for the 3 controller-back-ref bindings that ServicePhase needs at
 * construction time but whose concrete values only become available after LocalApiServer
 * constructs the ui-side controllers.
 *
 * <p>ServicePhase wraps these in Suppliers/Callables when constructing the affected services
 * (SettingsServiceImpl, DiagnosticsServiceImpl). LocalApiServer publishes the controllers'
 * methods/objects into these AtomicReferences after constructing the controllers, before any
 * service method is actually invoked.
 *
 * <p>The bindings are write-once in practice but not enforced; the AtomicReference model is
 * chosen for visibility guarantees across threads (controllers may construct on the API thread
 * while services are invoked on request threads).
 */
public final class BootstrapLateBindings {

  private final AtomicReference<Callable<Map<String, Object>>> settingsResetFn =
      new AtomicReference<>();
  private final AtomicReference<DebugStateProvider> debugStateProvider = new AtomicReference<>();
  private final AtomicReference<StatusSnapshotProvider> statusSnapshotProvider =
      new AtomicReference<>();

  /** Set by LocalApiServer after SettingsController exists. */
  public void setSettingsResetFn(Callable<Map<String, Object>> resetFn) {
    this.settingsResetFn.set(resetFn);
  }

  /** Set by LocalApiServer after DebugStateController exists. */
  public void setDebugStateProvider(DebugStateProvider provider) {
    this.debugStateProvider.set(provider);
  }

  /** Set by LocalApiServer after StatusLifecycleHandler exists. */
  public void setStatusSnapshotProvider(StatusSnapshotProvider provider) {
    this.statusSnapshotProvider.set(provider);
  }

  /** Read by SettingsServiceImpl on each resetToDefaults() call. */
  public Callable<Map<String, Object>> settingsResetFn() {
    return settingsResetFn.get();
  }

  /** Read by DiagnosticsServiceImpl on each exportDiagnostics() call. */
  public DebugStateProvider debugStateProvider() {
    return debugStateProvider.get();
  }

  /** Read by DiagnosticsServiceImpl on each exportDiagnostics() call. */
  public StatusSnapshotProvider statusSnapshotProvider() {
    return statusSnapshotProvider.get();
  }
}
